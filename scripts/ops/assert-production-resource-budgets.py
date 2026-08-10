#!/usr/bin/env python3
"""Validate the rendered production Compose resource and exposure contract."""

from __future__ import annotations

import argparse
import copy
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


MIB = 1024 * 1024
GIB = 1024 * MIB
APP_SERVICES = {
    "dc-admin", "dc-comment", "dc-content-aggregate",
    "dc-discovery", "dc-gateway", "dc-identity", "dc-interaction",
    "dc-notification", "dc-recommend", "dc-user",
}
OBSERVABILITY_SERVICES = {"prometheus", "alertmanager", "jaeger"}
EXPECTED_SERVICES = APP_SERVICES | {
    "mysql", "redis", "kafka", "elasticsearch", "minio", "web-static", "web",
} | OBSERVABILITY_SERVICES
PUBLISHED_PORTS = {
    "dc-gateway": {("127.0.0.1", 8080, 8080, "tcp")},
    "web": {(None, 80, 80, "tcp"), (None, 443, 443, "tcp"), (None, 443, 443, "udp")},
}

PRODUCTION_ENVIRONMENT = {
    "DEVCIRCLE_PRODUCTION_MODE": "true",
    "DEVCIRCLE_READ_FALLBACK_ENABLED": "false",
    "DEVCIRCLE_THEME_CONTENT_SEED_ENABLED": "false",
    "SPRINGDOC_API_DOCS_ENABLED": "false",
    "SPRINGDOC_SWAGGER_UI_ENABLED": "false",
    "MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS": "true",
    "OTEL_EXPORTER_OTLP_ENDPOINT": "http://jaeger:4318",
}
LOGGING_CONTRACT = {
    "driver": "json-file",
    "options": {"max-size": "20m", "max-file": "5"},
}
HELM_GATEWAY_ENVIRONMENT = {
    "SPRING_PROFILES_ACTIVE": "aggregate",
    "DC_AUTH_URI": "http://dc-identity:8201",
    "DC_IDENTITY_URI": "http://dc-identity:8201",
    "DC_CONTENT_AGGREGATE_URI": "http://dc-content-aggregate:8202",
    "DC_DISCOVERY_URI": "http://dc-discovery:8203",
    "DC_SEARCH_URI": "http://dc-discovery:8203",
    "DC_RECOMMEND_URI": "http://dc-recommend:8107",
}
PRODUCTION_MODE_APPLICATIONS = (
    "dc-auth", "dc-identity", "dc-content-deployment", "dc-content",
    "dc-user", "dc-comment", "dc-interaction",
)


def fail(message: str) -> None:
    raise ValueError(message)


def rendered_compose(compose_file: Path, env_file: Path) -> dict:
    command = [
        "docker", "compose", "--env-file", str(env_file), "-f", str(compose_file),
        "config", "--format", "json",
    ]
    completed = subprocess.run(command, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        fail(f"docker compose config failed: {completed.stderr.strip()}")
    return json.loads(completed.stdout)


def positive_number(service: str, config: dict, field: str) -> float:
    raw = config.get(field)
    try:
        value = float(raw)
    except (TypeError, ValueError):
        fail(f"{service} must define numeric {field}")
    if value <= 0:
        fail(f"{service} {field} must be positive")
    return value


def port_contract(service: str, config: dict) -> set[tuple[str | None, int, int, str]]:
    ports = set()
    for port in config.get("ports", []):
        ports.add((port.get("host_ip"), int(port["published"]), int(port["target"]),
                   port.get("protocol", "tcp")))
    expected = PUBLISHED_PORTS.get(service, set())
    if ports != expected:
        fail(f"{service} published ports {sorted(ports)} do not match {sorted(expected)}")
    return ports


def logging_contract(service: str, config: dict) -> None:
    logging = config.get("logging") or {}
    if logging.get("driver") != LOGGING_CONTRACT["driver"]:
        fail(f"{service} must use the json-file logging driver")
    options = logging.get("options") or {}
    for key, expected in LOGGING_CONTRACT["options"].items():
        if str(options.get(key, "")) != expected:
            fail(f"{service} logging option {key} must equal {expected}")


def validate_caddyfile(caddyfile: Path) -> None:
    try:
        text = caddyfile.read_text(encoding="utf-8")
    except OSError as error:
        fail(f"cannot read production Caddyfile: {error}")
    if "admin localhost:2019" not in text:
        fail("Caddy admin API must listen on localhost:2019")
    if "admin 0.0.0.0:" in text or "admin :2019" in text:
        fail("Caddy admin API must not listen on all container interfaces")


def validate_production_mode_bindings(root: Path) -> None:
    expected = re.compile(
        r"(?m)^\s{2}production:\s*\$\{DEVCIRCLE_PRODUCTION_MODE:false\}\s*$"
    )
    for module in PRODUCTION_MODE_APPLICATIONS:
        application = root / module / "src/main/resources/application.yml"
        try:
            text = application.read_text(encoding="utf-8")
        except OSError as error:
            fail(f"cannot read {module} production configuration: {error}")
        if expected.search(text) is None:
            fail(f"{module} must bind DEVCIRCLE_PRODUCTION_MODE to devcircle.production")


def _without_yaml_comment(line: str) -> str:
    quote: str | None = None
    escaped = False
    for index, character in enumerate(line):
        if escaped:
            escaped = False
            continue
        if character == "\\" and quote == '"':
            escaped = True
            continue
        if character in {"'", '"'}:
            quote = None if quote == character else character if quote is None else quote
            continue
        if character == "#" and quote is None and (index == 0 or line[index - 1].isspace()):
            return line[:index]
    return line


def _yaml_scalar(raw: str) -> str:
    value = raw.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def _inline_yaml_mapping(raw: str, source: Path, line_number: int) -> dict[str, object]:
    value = raw.strip()
    if not (value.startswith("{") and value.endswith("}")):
        fail(f"unsupported inline YAML value at {source}:{line_number}")
    body = value[1:-1].strip()
    if not body:
        return {}
    result: dict[str, object] = {}
    for entry in body.split(","):
        key, separator, scalar = entry.partition(":")
        if not separator or not key.strip():
            fail(f"invalid inline YAML mapping at {source}:{line_number}")
        name = key.strip()
        if name in result:
            fail(f"duplicate YAML key {name} at {source}:{line_number}")
        result[name] = _yaml_scalar(scalar)
    return result


def load_simple_yaml_mapping(source: Path) -> dict[str, object]:
    """Load the chart's mapping-only values subset without Helm or third-party YAML packages."""
    try:
        lines = source.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        fail(f"cannot read Helm values {source}: {error}")
    root: dict[str, object] = {}
    stack: list[tuple[int, dict[str, object]]] = [(-1, root)]
    for line_number, raw_line in enumerate(lines, start=1):
        if "\t" in raw_line[:len(raw_line) - len(raw_line.lstrip())]:
            fail(f"tabs are not supported in Helm values at {source}:{line_number}")
        line = _without_yaml_comment(raw_line).rstrip()
        if not line.strip() or line.lstrip().startswith("- "):
            continue
        indentation = len(line) - len(line.lstrip(" "))
        key, separator, raw_value = line.strip().partition(":")
        if not separator or not key.strip():
            fail(f"invalid YAML mapping at {source}:{line_number}")
        while stack[-1][0] >= indentation:
            stack.pop()
        parent = stack[-1][1]
        name = key.strip()
        if name in parent:
            fail(f"duplicate YAML key {name} at {source}:{line_number}")
        value = raw_value.strip()
        if not value:
            child: dict[str, object] = {}
            parent[name] = child
            stack.append((indentation, child))
        elif value.startswith("{"):
            parent[name] = _inline_yaml_mapping(value, source, line_number)
        else:
            parent[name] = _yaml_scalar(value)
    return root


def _merge_mappings(base: dict[str, object], override: dict[str, object]) -> dict[str, object]:
    merged = copy.deepcopy(base)
    for key, value in override.items():
        current = merged.get(key)
        if isinstance(current, dict) and isinstance(value, dict):
            merged[key] = _merge_mappings(current, value)
        else:
            merged[key] = copy.deepcopy(value)
    return merged


def _mapping(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        fail(f"Helm {label} must be a mapping")
    return value


def _public_https_origin(value: object, label: str) -> str:
    origin = str(value or "").strip().rstrip("/")
    parsed = urlsplit(origin)
    if (parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}
            or parsed.hostname.lower() in {"localhost", "127.0.0.1", "::1"}):
        fail(f"Helm {label} must be a public HTTPS origin")
    return origin


def _weak_internal_operations_key(value: object) -> bool:
    candidate = str(value or "").strip()
    lowered = candidate.lower()
    return (len(candidate) < 32
            or len(set(candidate)) < 12
            or "local-development" in lowered
            or "replace-with" in lowered
            or "changeme" in lowered
            or lowered == "devcircle_internal_ops_key")


def validate_helm_topology(base: dict[str, object], production: dict[str, object], template: str) -> None:
    values = _merge_mappings(base, production)
    services = _mapping(values.get("services"), "services")
    global_environment = _mapping(values.get("env"), "env")
    global_secret_refs = _mapping(values.get("secretEnvRefs"), "secretEnvRefs")
    inline_secret_values = _mapping(values.get("secretEnv", {}), "secretEnv")
    secrets = _mapping(values.get("secrets"), "secrets")
    if "auth" in services:
        fail("Helm must not deploy dc-auth separately in the production topology")
    if "search" in services:
        fail("Helm must embed search in discovery instead of deploying dc-search separately")
    if not str(secrets.get("existingSecret", "")).strip():
        fail("Helm secrets.existingSecret is required")
    global_collisions = set(global_environment).intersection(global_secret_refs)
    if global_collisions:
        fail(f"Helm global environment duplicates Secret projections {sorted(global_collisions)}")
    if global_secret_refs.get("DEVCIRCLE_INTERNAL_OPS_KEY") != "DEVCIRCLE_INTERNAL_OPS_KEY":
        fail("Helm must project DEVCIRCLE_INTERNAL_OPS_KEY from the existing Secret")
    if ("DEVCIRCLE_INTERNAL_OPS_KEY" in inline_secret_values
            and _weak_internal_operations_key(inline_secret_values["DEVCIRCLE_INTERNAL_OPS_KEY"])):
        fail("Helm must not render a default or weak DEVCIRCLE_INTERNAL_OPS_KEY")

    projected: dict[str, dict[str, object]] = {}
    projected_secret_refs: dict[str, dict[str, object]] = {}
    for service_name, raw_service in services.items():
        service = _mapping(raw_service, f"services.{service_name}")
        service_environment = _mapping(service.get("env", {}), f"services.{service_name}.env")
        service_secret_refs = _mapping(
            service.get("secretEnvRefs", {}), f"services.{service_name}.secretEnvRefs"
        )
        duplicates = set(global_environment).intersection(service_environment)
        if duplicates:
            fail(f"Helm service {service_name} duplicates global environment keys {sorted(duplicates)}")
        secret_duplicates = set(global_secret_refs).intersection(service_secret_refs)
        if secret_duplicates:
            fail(
                f"Helm service {service_name} duplicates global Secret projections "
                f"{sorted(secret_duplicates)}"
            )
        plain_secret_duplicates = set(service_environment).intersection(
            set(global_secret_refs) | set(service_secret_refs)
        )
        if plain_secret_duplicates:
            fail(
                f"Helm service {service_name} environment duplicates Secret projections "
                f"{sorted(plain_secret_duplicates)}"
            )
        projected[service_name] = {**global_environment, **service_environment}
        projected_secret_refs[service_name] = {**global_secret_refs, **service_secret_refs}

    gateway = projected.get("gateway")
    if gateway is None:
        fail("Helm must deploy the gateway service")
    gateway_service_environment = _mapping(
        _mapping(services["gateway"], "services.gateway").get("env", {}),
        "services.gateway.env",
    )
    for key, expected in HELM_GATEWAY_ENVIRONMENT.items():
        if gateway_service_environment.get(key) != expected or gateway.get(key) != expected:
            fail(f"Helm gateway must explicitly set {key}={expected}")

    discovery_environment = _mapping(
        _mapping(services.get("discovery"), "services.discovery").get("env", {}),
        "services.discovery.env",
    )
    if "DC_SEARCH_URI" in discovery_environment or "DC_SEARCH_URI" in global_environment:
        fail("Helm discovery must embed search instead of proxying DC_SEARCH_URI")

    identity_environment = _mapping(
        _mapping(services.get("identity"), "services.identity").get("env", {}),
        "services.identity.env",
    )
    if "DC_AUTH_URI" in identity_environment or "DC_AUTH_URI" in global_environment:
        fail("Helm identity must embed authentication instead of proxying DC_AUTH_URI")
    public_origin = _public_https_origin(values.get("publicOrigin"), "publicOrigin")
    if global_environment.get("S3_PRESIGN_ENDPOINT") != public_origin:
        fail("Helm S3_PRESIGN_ENDPOINT must match publicOrigin")
    if global_environment.get("S3_PUBLIC_BASE_URL") != f"{public_origin}/devcircle":
        fail("Helm S3_PUBLIC_BASE_URL must derive from publicOrigin")
    if str(identity_environment.get("AUTH_COOKIE_SECURE", "")).lower() != "true":
        fail("Helm production identity must set AUTH_COOKIE_SECURE=true")
    if identity_environment.get("PUBLIC_ORIGIN") != public_origin:
        fail("Helm identity PUBLIC_ORIGIN must match publicOrigin")
    expected_callbacks = {
        "AUTH_FRONTEND_CALLBACK_URI": f"{public_origin}/auth/callback",
        "GITHUB_CALLBACK_URI": f"{public_origin}/api/auth/oauth2/github/callback",
    }
    for key, expected in expected_callbacks.items():
        callback = str(identity_environment.get(key, ""))
        if callback != expected or "localhost" in callback.lower():
            fail(f"Helm identity {key} must derive from the public HTTPS origin")
    if str(identity_environment.get("GITHUB_OAUTH_ENABLED", "")).lower() != "true":
        fail("Helm production identity must explicitly enable GitHub OAuth")
    identity_pool_sizes = {}
    for domain, key in {
        "Auth": "IDENTITY_AUTH_DB_POOL_MAX_SIZE",
        "User": "IDENTITY_USER_DB_POOL_MAX_SIZE",
    }.items():
        try:
            identity_pool_sizes[domain] = int(identity_environment.get(key, "0"))
        except (TypeError, ValueError):
            fail(f"Helm identity {domain} pool budget must be an integer")
        if not 1 <= identity_pool_sizes[domain] <= 10:
            fail(f"Helm identity {domain} pool budget must be between 1 and 10")
    replica_count = int(values.get("replicaCount", 0))
    if replica_count < 1:
        fail("Helm replicaCount must be positive")
    if sum(identity_pool_sizes.values()) * replica_count > 40:
        fail("Helm identity aggregate DB pool ceiling must not exceed 40 connections")
    identity_secret_refs = projected_secret_refs.get("identity", {})
    for key in ("DEVCIRCLE_INTERNAL_OPS_KEY", "GITHUB_CLIENT_ID", "GITHUB_CLIENT_SECRET"):
        if identity_secret_refs.get(key) != key:
            fail(f"Helm identity must project {key} from the existing Secret")
    admin_environment = _mapping(
        _mapping(services.get("admin"), "services.admin").get("env", {}),
        "services.admin.env",
    )
    if admin_environment.get("DC_AUTH_URI") != "http://dc-identity:8201":
        fail("Helm admin must read the logical dc-auth Outbox source from dc-identity")
    if projected_secret_refs.get("admin", {}).get("DEVCIRCLE_INTERNAL_OPS_KEY") != "DEVCIRCLE_INTERNAL_OPS_KEY":
        fail("Helm admin must project DEVCIRCLE_INTERNAL_OPS_KEY from the existing Secret")

    range_pattern = re.compile(
        r"{{-?\s*range\s+\$[A-Za-z][A-Za-z0-9]*\s*,\s*\$[A-Za-z][A-Za-z0-9]*\s*:=\s*(.*?)\s*-?}}"
    )
    range_sources = [re.sub(r"\s+", "", source) for source in range_pattern.findall(template)]
    if range_sources.count("$.Values.env") != 1:
        fail("Helm service template must render global environment exactly once")
    if range_sources.count("($svc.env|defaultdict)") != 1:
        fail("Helm service template must render service environment exactly once")
    if range_sources.count("$.Values.secretEnvRefs") != 1:
        fail("Helm service template must render global Secret projections exactly once")
    if range_sources.count("($svc.secretEnvRefs|defaultdict)") != 1:
        fail("Helm service template must render service Secret projections exactly once")
    if template.count("optional: false") != 2:
        fail("Helm Secret projections must fail closed when a required key is missing")
    duplicate_guard = re.compile(
        r"{{-?\s*if\s+hasKey\s+\$\.Values\.env\s+\$key\s*-?}}.*?"
        r"{{-?\s*fail\s+\(printf\s+[^}]+\)\s*-?}}",
        re.DOTALL,
    )
    if duplicate_guard.search(template) is None:
        fail("Helm service template must fail on duplicate global and service environment keys")
    for required_guard in (
        "duplicates a global Secret projection",
        "duplicates a service Secret projection",
        "production identity must set AUTH_COOKIE_SECURE=true",
        "production identity AUTH_FRONTEND_CALLBACK_URI must derive from publicOrigin",
        "production identity GITHUB_CALLBACK_URI must derive from publicOrigin",
        "production identity must project GitHub OAuth credentials from a Secret",
        "production identity Auth and User pool budgets must both be positive",
        "production identity aggregate DB pool ceiling must not exceed 40 connections",
        "production services must project DEVCIRCLE_INTERNAL_OPS_KEY from a Secret",
        "production S3_PRESIGN_ENDPOINT must match publicOrigin",
        "production S3_PUBLIC_BASE_URL must derive from publicOrigin",
    ):
        if required_guard not in template:
            fail(f"Helm service template is missing guard: {required_guard}")


def validate(data: dict) -> tuple[int, int]:
    services = data.get("services") or {}
    missing_apps = APP_SERVICES.difference(services)
    if missing_apps:
        fail(f"missing application services: {sorted(missing_apps)}")
    missing_services = EXPECTED_SERVICES.difference(services)
    unexpected_services = set(services).difference(EXPECTED_SERVICES)
    if missing_services or unexpected_services:
        fail(
            "production services do not match the 20-service contract: "
            f"missing={sorted(missing_services)} unexpected={sorted(unexpected_services)}"
        )

    total_memory = 0
    for name, config in services.items():
        memory = int(positive_number(name, config, "mem_limit"))
        cpu = positive_number(name, config, "cpus")
        pids = int(positive_number(name, config, "pids_limit"))
        total_memory += memory
        if memory < 128 * MIB:
            fail(f"{name} memory limit is below 128 MiB")
        if cpu > 2:
            fail(f"{name} CPU limit exceeds the single-host budget")
        if pids > 512:
            fail(f"{name} PID limit exceeds 512")
        port_contract(name, config)
        logging_contract(name, config)

    if total_memory > 15 * GIB:
        fail(f"aggregate container memory ceiling {total_memory / GIB:.2f} GiB exceeds 15 GiB")

    for name in APP_SERVICES:
        config = services[name]
        memory = int(config["mem_limit"])
        if not 384 * MIB <= memory <= 768 * MIB:
            fail(f"{name} memory limit must stay between 384 and 768 MiB")
        environment = config.get("environment") or {}
        for key, expected in PRODUCTION_ENVIRONMENT.items():
            if str(environment.get(key, "")).lower() != expected:
                fail(f"{name} must set {key}={expected}")
        internal_ops_key = str(environment.get("DEVCIRCLE_INTERNAL_OPS_KEY", ""))
        if len(internal_ops_key) < 32:
            fail(f"{name} must receive DEVCIRCLE_INTERNAL_OPS_KEY")
        java_options = environment.get("JAVA_TOOL_OPTIONS", "")
        for option in ("-XX:MaxRAMPercentage=", "-XX:+ExitOnOutOfMemoryError"):
            if option not in java_options:
                fail(f"{name} JAVA_TOOL_OPTIONS is missing {option}")
        pool_size = int(environment.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "0"))
        if not 1 <= pool_size <= 10:
            fail(f"{name} Hikari maximum pool size must be between 1 and 10")
        connection_timeout = int(environment.get("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "0"))
        if not 500 <= connection_timeout <= 5000:
            fail(f"{name} Hikari connection timeout must be between 500 and 5000 ms")
        tomcat_threads = int(environment.get("SERVER_TOMCAT_THREADS_MAX", "0"))
        if not 8 <= tomcat_threads <= 64:
            fail(f"{name} Tomcat thread ceiling must be between 8 and 64")

    if "dc-auth" in services:
        fail("dc-auth must not run as a separate production service")
    identity_environment = services["dc-identity"].get("environment") or {}
    if "DC_AUTH_URI" in identity_environment:
        fail("dc-identity must embed authentication instead of proxying DC_AUTH_URI")
    identity_pool_sizes = {}
    for domain, key in {
        "Auth": "IDENTITY_AUTH_DB_POOL_MAX_SIZE",
        "User": "IDENTITY_USER_DB_POOL_MAX_SIZE",
    }.items():
        try:
            identity_pool_sizes[domain] = int(identity_environment.get(key, "0"))
        except (TypeError, ValueError):
            fail(f"dc-identity {domain} pool budget must be an integer")
        if not 1 <= identity_pool_sizes[domain] <= 10:
            fail(f"dc-identity {domain} pool budget must be between 1 and 10")
    if sum(identity_pool_sizes.values()) > 16:
        fail("dc-identity aggregate Auth and User pool ceiling must not exceed 16 connections")
    admin_environment = services["dc-admin"].get("environment") or {}
    if admin_environment.get("DC_AUTH_URI") != "http://dc-identity:8201":
        fail("dc-admin must read the logical dc-auth Outbox source from dc-identity")
    content_environment = services["dc-content-aggregate"].get("environment") or {}
    for key, expected in {
        "DC_USER_URI": "http://dc-user:8102",
        "DC_INTERACTION_URI": "http://dc-interaction:8105",
    }.items():
        if content_environment.get(key) != expected:
            fail(f"dc-content-aggregate must set {key}={expected} for detail composition")
    gateway_environment = services["dc-gateway"].get("environment") or {}
    expected_gateway_routes = {
        "DC_AUTH_URI": "http://dc-identity:8201",
        "DC_IDENTITY_URI": "http://dc-identity:8201",
        "DC_USER_URI": "http://dc-user:8102",
        "DC_INTERACTION_URI": "http://dc-interaction:8105",
        "DC_NOTIFICATION_URI": "http://dc-notification:8108",
        "DC_DISCOVERY_URI": "http://dc-discovery:8203",
        "DC_SEARCH_URI": "http://dc-discovery:8203",
        "DC_RECOMMEND_URI": "http://dc-recommend:8107",
    }
    for key, expected in expected_gateway_routes.items():
        if gateway_environment.get(key) != expected:
            fail(f"dc-gateway must set {key}={expected}")
    gateway_dependencies = services["dc-gateway"].get("depends_on") or {}
    for dependency in ("dc-identity", "dc-content-aggregate", "dc-discovery", "dc-recommend"):
        if dependency not in gateway_dependencies:
            fail(f"dc-gateway must depend on {dependency} for aggregate routing")
    discovery_environment = services["dc-discovery"].get("environment") or {}
    if "DC_SEARCH_URI" in discovery_environment:
        fail("dc-discovery must embed search instead of proxying DC_SEARCH_URI")
    discovery_dependencies = services["dc-discovery"].get("depends_on") or {}
    for dependency in ("mysql", "elasticsearch", "kafka", "dc-content-aggregate"):
        if dependency not in discovery_dependencies:
            fail(f"dc-discovery must depend on {dependency} for the embedded search runtime")

    mysql_command = services["mysql"].get("command") or []
    if "--max-connections=120" not in mysql_command or "--innodb-buffer-pool-size=768M" not in mysql_command:
        fail("mysql must cap connections and the InnoDB buffer pool")
    kafka_heap = (services["kafka"].get("environment") or {}).get("KAFKA_HEAP_OPTS", "")
    if "-Xmx768m" not in kafka_heap:
        fail("kafka heap must be capped at 768 MiB")
    elastic_heap = (services["elasticsearch"].get("environment") or {}).get("ES_JAVA_OPTS", "")
    if "-Xmx1g" not in elastic_heap:
        fail("Elasticsearch heap must be capped at 1 GiB")

    prometheus_command = services["prometheus"].get("command") or []
    for option in ("--storage.tsdb.retention.time=35d", "--storage.tsdb.retention.size=1GB"):
        if option not in prometheus_command:
            fail(f"prometheus command is missing {option}")
    jaeger_environment = services["jaeger"].get("environment") or {}
    if str(jaeger_environment.get("BADGER_EPHEMERAL", "")).lower() != "false":
        fail("jaeger must use persistent Badger storage")
    if jaeger_environment.get("SPAN_STORAGE_TYPE") != "badger":
        fail("jaeger SPAN_STORAGE_TYPE must be badger")

    return len(services), total_memory


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--compose-file", default="docker-compose.prod.yml", type=Path)
    parser.add_argument("--env-file", default=".env.production.example", type=Path)
    parser.add_argument("--caddyfile", default="deploy/Caddyfile", type=Path)
    parser.add_argument("--helm-values", default="deploy/helm/devcircle/values.yaml", type=Path)
    parser.add_argument("--helm-prod-values", default="deploy/helm/devcircle/values-prod.yaml", type=Path)
    parser.add_argument(
        "--helm-service-template",
        default="deploy/helm/devcircle/templates/services.yaml",
        type=Path,
    )
    args = parser.parse_args()
    try:
        service_count, total_memory = validate(rendered_compose(args.compose_file, args.env_file))
        validate_caddyfile(args.caddyfile)
        validate_production_mode_bindings(Path("."))
        validate_helm_topology(
            load_simple_yaml_mapping(args.helm_values),
            load_simple_yaml_mapping(args.helm_prod_values),
            args.helm_service_template.read_text(encoding="utf-8"),
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Production resource budget validation failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Production resource budgets validated: services={service_count} "
        f"memory_ceiling={total_memory / GIB:.2f}GiB published_services={len(PUBLISHED_PORTS)} "
        "helm_auth_runtime=dc-identity:8201"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
