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
IDENTITY_DB_CONNECTIONS_PER_REPLICA = 16
APP_SERVICES = {
    "dc-admin", "dc-comment", "dc-content-aggregate",
    "dc-discovery", "dc-gateway", "dc-identity",
    "dc-recommend",
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
    "DC_USER_URI": "http://dc-identity:8201",
    "DC_INTERACTION_URI": "http://dc-identity:8201",
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


def rendered_compose(compose_files: Path | list[Path], env_file: Path) -> dict:
    files = [compose_files] if isinstance(compose_files, Path) else compose_files
    command = [
        "bash", "scripts/ops/production-compose.sh", env_file.as_posix(),
    ]
    for compose_file in files:
        command.extend(("-f", compose_file.as_posix()))
    command.extend(("config", "--format", "json"))
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


def validate_helm_topology(
        base: dict[str, object],
        production: dict[str, object],
        template: str,
        cutover: dict[str, object] | None = None,
) -> None:
    values = _merge_mappings(base, production)
    if cutover is not None:
        values = _merge_mappings(values, cutover)
    services = {
        name: service
        for name, service in _mapping(values.get("services"), "services").items()
        if service is not None and str(service).lower() != "null"
    }
    cutover_enabled = str(
        _mapping(values.get("notificationIdentityCutover"), "notificationIdentityCutover")
        .get("enabled", "false")
    ).lower() == "true"
    global_environment = _mapping(values.get("env"), "env")
    global_secret_refs = _mapping(values.get("secretEnvRefs"), "secretEnvRefs")
    inline_secret_values = _mapping(values.get("secretEnv", {}), "secretEnv")
    secrets = _mapping(values.get("secrets"), "secrets")
    if "auth" in services:
        fail("Helm must not deploy dc-auth separately in the production topology")
    if "user" in services:
        fail("Helm must embed User in identity instead of deploying dc-user separately")
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
    if "interaction" in services:
        fail("Helm must not deploy standalone Interaction by default")
    expected_notification_uri = (
        "http://dc-identity:8201" if cutover_enabled else "http://dc-notification:8108"
    )
    if (gateway_service_environment.get("DC_NOTIFICATION_URI") != expected_notification_uri
            or gateway.get("DC_NOTIFICATION_URI") != expected_notification_uri):
        fail(
            "Helm gateway must explicitly set "
            f"DC_NOTIFICATION_URI={expected_notification_uri}"
        )
    if cutover_enabled and "notification" in services:
        fail("Helm must not deploy standalone Notification after Identity cutover")
    if not cutover_enabled and "notification" not in services:
        fail("Helm must deploy standalone Notification before Identity cutover")

    discovery_environment = _mapping(
        _mapping(services.get("discovery"), "services.discovery").get("env", {}),
        "services.discovery.env",
    )
    if "DC_SEARCH_URI" in discovery_environment or "DC_SEARCH_URI" in global_environment:
        fail("Helm discovery must embed search instead of proxying DC_SEARCH_URI")

    content_environment = _mapping(
        _mapping(services.get("content-aggregate"), "services.content-aggregate").get("env", {}),
        "services.content-aggregate.env",
    )
    for key, expected in {
        "DC_USER_URI": "http://dc-identity:8201",
        "DC_INTERACTION_URI": "http://dc-identity:8201",
    }.items():
        if content_environment.get(key) != expected:
            fail(f"Helm content-aggregate must set {key}={expected}")

    identity_environment = _mapping(
        _mapping(services.get("identity"), "services.identity").get("env", {}),
        "services.identity.env",
    )
    if "DC_AUTH_URI" in identity_environment or "DC_AUTH_URI" in global_environment:
        fail("Helm identity must embed authentication instead of proxying DC_AUTH_URI")
    if "DC_USER_URI" in identity_environment or "DC_USER_URI" in global_environment:
        fail("Helm identity must use its embedded User runtime instead of an HTTP self-call")
    if "DC_INTERACTION_URI" in identity_environment or "DC_INTERACTION_URI" in global_environment:
        fail("Helm identity dashboard must use its embedded Interaction runtime without an HTTP self-call")
    if "DC_NOTIFICATION_URI" in global_environment:
        fail("Helm must scope the Notification rollback URI to identity")
    if identity_environment.get("DC_NOTIFICATION_URI") != "http://dc-notification:8108":
        fail("Helm identity must retain DC_NOTIFICATION_URI for one-version rollback compatibility")
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
    if str(identity_environment.get("INTERACTION_FLYWAY_ENABLED", "")).lower() != "true":
        fail("Helm production identity must enable Interaction Flyway")
    if str(identity_environment.get("INTERACTION_RUNTIME_OUTBOX_ENABLED", "")).lower() != "true":
        fail("Helm production identity must enable Interaction Outbox")
    for key, label in {
        "NOTIFICATION_RUNTIME_API_ENABLED": "API",
        "NOTIFICATION_FLYWAY_ENABLED": "Flyway",
        "NOTIFICATION_RUNTIME_CONSUMER_ENABLED": "consumer",
        "NOTIFICATION_RUNTIME_TOPIC_ADMIN_ENABLED": "topic admin",
        "NOTIFICATION_RUNTIME_WEBSOCKET_ENABLED": "WebSocket",
    }.items():
        expected = "true" if cutover_enabled else "false"
        if str(identity_environment.get(key, "")).lower() != expected:
            fail(
                f"Helm production identity Notification {label} owner switch "
                f"must be {expected}"
            )
    identity_pool_sizes = {}
    for domain, key in {
        "Auth": "IDENTITY_AUTH_DB_POOL_MAX_SIZE",
        "User": "IDENTITY_USER_DB_POOL_MAX_SIZE",
        "Interaction": "IDENTITY_INTERACTION_DB_POOL_MAX_SIZE",
        "Notification": "IDENTITY_NOTIFICATION_DB_POOL_MAX_SIZE",
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
    try:
        identity_connection_budget = int(values.get("identityDatabaseConnectionBudget", 0))
    except (TypeError, ValueError):
        fail("Helm identityDatabaseConnectionBudget must be an integer")
    maximum_calculable_budget = IDENTITY_DB_CONNECTIONS_PER_REPLICA * replica_count
    if not 1 <= identity_connection_budget <= maximum_calculable_budget:
        fail(
            "Helm identityDatabaseConnectionBudget must be positive and must not exceed "
            f"{IDENTITY_DB_CONNECTIONS_PER_REPLICA} connections per replica"
        )
    required_identity_connections = sum(identity_pool_sizes.values()) * replica_count
    if required_identity_connections > identity_connection_budget:
        fail("Helm identity aggregate DB pools must fit identityDatabaseConnectionBudget")
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
    if admin_environment.get("DC_USER_URI") != "http://dc-identity:8201":
        fail("Helm admin must read the logical dc-user Outbox source from dc-identity")
    if admin_environment.get("DC_INTERACTION_URI") != "http://dc-identity:8201":
        fail("Helm admin must read the logical dc-interaction Outbox source from dc-identity")
    for key, expected in {
        "DC_AUTH_OUTBOX_PATH": "/internal/ops/identity/outbox/auth/status",
        "DC_USER_OUTBOX_PATH": "/internal/ops/identity/outbox/user/status",
        "DC_INTERACTION_OUTBOX_PATH": "/internal/ops/identity/outbox/interaction/status",
    }.items():
        if admin_environment.get(key) != expected:
            fail(f"Helm admin must set {key}={expected}")
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
        "production identity Auth, User, Interaction and Notification pool budgets must all be positive",
        "production identity %s must be %s for the selected Notification owner",
        "production identity must retain DC_NOTIFICATION_URI for one-version rollback compatibility",
        "approved Notification Identity cutover must remove the standalone Notification service",
        "production notification service is required before approved Identity cutover",
        "production notification PUBLIC_ORIGIN must match publicOrigin",
        "production identity database connection budget is required",
        "production identity database connection budget must be positive",
        "production identity database connection budget must not exceed 16 connections per replica",
        "production identity aggregate DB pools must fit identityDatabaseConnectionBudget",
        "production services must project DEVCIRCLE_INTERNAL_OPS_KEY from a Secret",
        "production S3_PRESIGN_ENDPOINT must match publicOrigin",
        "production S3_PUBLIC_BASE_URL must derive from publicOrigin",
    ):
        if required_guard not in template:
            fail(f"Helm service template is missing guard: {required_guard}")


def validate(data: dict, notification_owner: str = "standalone") -> tuple[int, int]:
    if notification_owner not in {"identity", "standalone"}:
        fail("notification_owner must be identity or standalone")
    services = data.get("services") or {}
    missing_apps = APP_SERVICES.difference(services)
    if missing_apps:
        fail(f"missing application services: {sorted(missing_apps)}")
    expected_services = EXPECTED_SERVICES | (
        {"dc-notification"} if notification_owner == "standalone" else set()
    )
    missing_services = expected_services.difference(services)
    unexpected_services = set(services).difference(expected_services)
    if missing_services or unexpected_services:
        fail(
            f"production services do not match the {len(expected_services)}-service contract: "
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

    expected_web_image = (
        "/web-notification-cutover:"
        if notification_owner == "identity"
        else "/web-notification-standalone:"
    )
    web_image = str(services["web-static"].get("image", ""))
    if expected_web_image not in web_image:
        fail(
            f"{notification_owner} Notification topology must select "
            f"{expected_web_image.strip('/:')} Web image"
        )

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
    if "dc-user" in services:
        fail("dc-user must not run as a separate production service")
    identity_environment = services["dc-identity"].get("environment") or {}
    if "DC_AUTH_URI" in identity_environment:
        fail("dc-identity must embed authentication instead of proxying DC_AUTH_URI")
    if "DC_USER_URI" in identity_environment:
        fail("dc-identity must use its embedded User runtime instead of an HTTP self-call")
    if identity_environment.get("DC_NOTIFICATION_URI") != "http://dc-notification:8108":
        fail("dc-identity must retain DC_NOTIFICATION_URI for one-version rollback compatibility")
    if identity_environment.get("INTERACTION_FLYWAY_ENABLED") != "true":
        fail("dc-identity must enable Interaction Flyway in the default production topology")
    if identity_environment.get("INTERACTION_RUNTIME_OUTBOX_ENABLED") != "true":
        fail("dc-identity must enable Interaction Outbox in the default production topology")
    expected_owner_switch = "true" if notification_owner == "identity" else "false"
    for key, label in {
        "NOTIFICATION_RUNTIME_API_ENABLED": "API",
        "NOTIFICATION_FLYWAY_ENABLED": "Flyway",
        "NOTIFICATION_RUNTIME_CONSUMER_ENABLED": "consumer",
        "NOTIFICATION_RUNTIME_TOPIC_ADMIN_ENABLED": "topic admin",
        "NOTIFICATION_RUNTIME_WEBSOCKET_ENABLED": "WebSocket",
    }.items():
        if identity_environment.get(key) != expected_owner_switch:
            fail(
                f"dc-identity Notification {label} owner switch must be "
                f"{expected_owner_switch} when {notification_owner} owns Notification"
            )
    identity_pool_sizes = {}
    for domain, key in {
        "Auth": "IDENTITY_AUTH_DB_POOL_MAX_SIZE",
        "User": "IDENTITY_USER_DB_POOL_MAX_SIZE",
        "Interaction": "IDENTITY_INTERACTION_DB_POOL_MAX_SIZE",
        "Notification": "IDENTITY_NOTIFICATION_DB_POOL_MAX_SIZE",
    }.items():
        try:
            identity_pool_sizes[domain] = int(identity_environment.get(key, "0"))
        except (TypeError, ValueError):
            fail(f"dc-identity {domain} pool budget must be an integer")
        if not 1 <= identity_pool_sizes[domain] <= 10:
            fail(f"dc-identity {domain} pool budget must be between 1 and 10")
    if sum(identity_pool_sizes.values()) > 16:
        fail("dc-identity aggregate Auth, User, Interaction and Notification pool ceiling must not exceed 16 connections")
    if notification_owner == "standalone":
        notification_environment = services["dc-notification"].get("environment") or {}
        for key, expected in PRODUCTION_ENVIRONMENT.items():
            if str(notification_environment.get(key, "")).lower() != expected:
                fail(f"dc-notification must set {key}={expected}")
        if (not notification_environment.get("PUBLIC_ORIGIN")
                or notification_environment.get("PUBLIC_ORIGIN")
                != notification_environment.get("S3_PRESIGN_ENDPOINT")):
            fail("dc-notification PUBLIC_ORIGIN must match the production public origin")
    admin_environment = services["dc-admin"].get("environment") or {}
    if admin_environment.get("DC_AUTH_URI") != "http://dc-identity:8201":
        fail("dc-admin must read the logical dc-auth Outbox source from dc-identity")
    if admin_environment.get("DC_USER_URI") != "http://dc-identity:8201":
        fail("dc-admin must read the logical dc-user Outbox source from dc-identity")
    if admin_environment.get("DC_INTERACTION_URI") != "http://dc-identity:8201":
        fail("dc-admin must read the logical dc-interaction Outbox source from dc-identity")
    for key, expected in {
        "DC_AUTH_OUTBOX_PATH": "/internal/ops/identity/outbox/auth/status",
        "DC_USER_OUTBOX_PATH": "/internal/ops/identity/outbox/user/status",
        "DC_INTERACTION_OUTBOX_PATH": "/internal/ops/identity/outbox/interaction/status",
    }.items():
        if admin_environment.get(key) != expected:
            fail(f"dc-admin must set {key}={expected}")
    content_environment = services["dc-content-aggregate"].get("environment") or {}
    for key, expected in {
        "DC_USER_URI": "http://dc-identity:8201",
        "DC_INTERACTION_URI": "http://dc-identity:8201",
    }.items():
        if content_environment.get(key) != expected:
            fail(f"dc-content-aggregate must set {key}={expected} for detail composition")
    gateway_environment = services["dc-gateway"].get("environment") or {}
    expected_gateway_routes = {
        "DC_AUTH_URI": "http://dc-identity:8201",
        "DC_IDENTITY_URI": "http://dc-identity:8201",
        "DC_USER_URI": "http://dc-identity:8201",
        "DC_INTERACTION_URI": "http://dc-identity:8201",
        "DC_NOTIFICATION_URI": (
            "http://dc-identity:8201"
            if notification_owner == "identity"
            else "http://dc-notification:8108"
        ),
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
    if notification_owner == "identity" and "dc-notification" in gateway_dependencies:
        fail("dc-gateway must not depend on standalone Notification after cutover")
    if notification_owner == "standalone":
        dependency = gateway_dependencies.get("dc-notification") or {}
        if dependency.get("condition") != "service_healthy":
            fail("dc-gateway must wait for healthy standalone Notification before cutover")
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


def validate_legacy_user_rollback(
        production: dict,
        rollback: dict,
        production_prometheus: Path,
        rollback_prometheus: Path,
        notification_owner: str = "standalone",
) -> tuple[int, int]:
    """Validate the bounded, observable standalone User rollback topology."""
    validate(production, notification_owner)
    services = rollback.get("services") or {}
    expected_services = EXPECTED_SERVICES | {"dc-user"} | (
        {"dc-notification"} if notification_owner == "standalone" else set()
    )
    if set(services) != expected_services:
        fail(
            f"legacy User rollback services do not match the {len(expected_services)}-service contract: "
            f"missing={sorted(expected_services.difference(services))} "
            f"unexpected={sorted(set(services).difference(expected_services))}"
        )

    user = services["dc-user"]
    memory = int(positive_number("dc-user", user, "mem_limit"))
    if not 384 * MIB <= memory <= 768 * MIB:
        fail("legacy dc-user memory limit must stay between 384 and 768 MiB")
    if positive_number("dc-user", user, "cpus") > 2:
        fail("legacy dc-user CPU limit exceeds the single-host budget")
    if int(positive_number("dc-user", user, "pids_limit")) > 512:
        fail("legacy dc-user PID limit exceeds 512")
    port_contract("dc-user", user)
    logging_contract("dc-user", user)
    if "8102" not in {str(port) for port in user.get("expose", [])}:
        fail("legacy dc-user must expose its internal HTTP port 8102")
    health_test = (user.get("healthcheck") or {}).get("test") or []
    if "http://localhost:8102/actuator/health/readiness" not in health_test:
        fail("legacy dc-user must use the readiness actuator health check")
    user_environment = user.get("environment") or {}
    for key, expected in PRODUCTION_ENVIRONMENT.items():
        if str(user_environment.get(key, "")).lower() != expected:
            fail(f"legacy dc-user must set {key}={expected}")
    for option in ("-XX:MaxRAMPercentage=", "-XX:+ExitOnOutOfMemoryError"):
        if option not in user_environment.get("JAVA_TOOL_OPTIONS", ""):
            fail(f"legacy dc-user JAVA_TOOL_OPTIONS is missing {option}")
    if not 1 <= int(user_environment.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "0")) <= 10:
        fail("legacy dc-user Hikari maximum pool size must be between 1 and 10")
    if not 500 <= int(user_environment.get("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "0")) <= 5000:
        fail("legacy dc-user Hikari connection timeout must be between 500 and 5000 ms")
    if not 8 <= int(user_environment.get("SERVER_TOMCAT_THREADS_MAX", "0")) <= 64:
        fail("legacy dc-user Tomcat thread ceiling must be between 8 and 64")
    if len(str(user_environment.get("DEVCIRCLE_INTERNAL_OPS_KEY", ""))) < 32:
        fail("legacy dc-user must receive DEVCIRCLE_INTERNAL_OPS_KEY")

    expected_user_route = "http://dc-user:8102"
    route_owners = ("dc-identity", "dc-gateway", "dc-content-aggregate", "dc-admin")
    for owner in route_owners:
        environment = services[owner].get("environment") or {}
        if environment.get("DC_USER_URI") != expected_user_route:
            fail(f"legacy User rollback must set {owner} DC_USER_URI={expected_user_route}")
        dependencies = services[owner].get("depends_on") or {}
        dependency = dependencies.get("dc-user") or {}
        if dependency.get("condition") != "service_healthy":
            fail(f"legacy User rollback must make {owner} wait for healthy dc-user")
    identity_environment = services["dc-identity"].get("environment") or {}
    for switch in (
        "USER_RUNTIME_CONSUMER_ENABLED",
        "USER_PROFILE_BACKFILL_ENABLED",
        "USER_RUNTIME_OUTBOX_ENABLED",
        "USER_FLYWAY_ENABLED",
    ):
        if str(identity_environment.get(switch, "")).lower() != "false":
            fail(f"legacy User rollback must disable {switch} in dc-identity")
    admin_environment = services["dc-admin"].get("environment") or {}
    if admin_environment.get("DC_USER_OUTBOX_PATH") != "/internal/ops/outbox/status":
        fail("legacy User rollback must restore the standalone dc-user Outbox status path")

    prometheus_volumes = services["prometheus"].get("volumes") or []
    config_mounts = [
        volume for volume in prometheus_volumes
        if volume.get("target") == "/etc/prometheus/prometheus.yml"
    ]
    if len(config_mounts) != 1 or not str(config_mounts[0].get("source", "")).replace("\\", "/").endswith(
            "/deploy/prometheus/prometheus.legacy-user-rollback.yml"):
        fail("legacy User rollback must mount prometheus.legacy-user-rollback.yml")
    production_prometheus_text = production_prometheus.read_text(encoding="utf-8")
    rollback_prometheus_text = rollback_prometheus.read_text(encoding="utf-8")
    if "dc-user:8102" in production_prometheus_text or "service: dc-user" in production_prometheus_text:
        fail("normal production Prometheus must not scrape the absent dc-user target")
    if "targets: [\"dc-user:8102\"]" not in rollback_prometheus_text \
            or "labels: { service: dc-user }" not in rollback_prometheus_text:
        fail("legacy User rollback Prometheus must scrape dc-user actuator metrics")

    total_memory = sum(int(positive_number(name, config, "mem_limit"))
                       for name, config in services.items())
    if total_memory > 15 * GIB:
        fail(
            "legacy User rollback aggregate container memory ceiling "
            f"{total_memory / GIB:.2f} GiB exceeds 15 GiB"
        )
    return len(services), total_memory


def validate_legacy_interaction_rollback(
        production: dict,
        rollback: dict,
        production_prometheus: Path,
        rollback_prometheus: Path,
        notification_owner: str = "standalone",
) -> tuple[int, int]:
    """Validate the bounded, single-owner standalone Interaction rollback topology."""
    validate(production, notification_owner)
    services = rollback.get("services") or {}
    expected_services = EXPECTED_SERVICES | {"dc-interaction"} | (
        {"dc-notification"} if notification_owner == "standalone" else set()
    )
    if set(services) != expected_services:
        fail(
            f"legacy Interaction rollback services do not match the {len(expected_services)}-service contract: "
            f"missing={sorted(expected_services.difference(services))} "
            f"unexpected={sorted(set(services).difference(expected_services))}"
        )

    interaction = services["dc-interaction"]
    memory = int(positive_number("dc-interaction", interaction, "mem_limit"))
    if not 384 * MIB <= memory <= 768 * MIB:
        fail("legacy dc-interaction memory limit must stay between 384 and 768 MiB")
    if positive_number("dc-interaction", interaction, "cpus") > 2:
        fail("legacy dc-interaction CPU limit exceeds the single-host budget")
    if int(positive_number("dc-interaction", interaction, "pids_limit")) > 512:
        fail("legacy dc-interaction PID limit exceeds 512")
    port_contract("dc-interaction", interaction)
    logging_contract("dc-interaction", interaction)
    if "8105" not in {str(port) for port in interaction.get("expose", [])}:
        fail("legacy dc-interaction must expose its internal HTTP port 8105")
    health_test = (interaction.get("healthcheck") or {}).get("test") or []
    if "http://localhost:8105/actuator/health/readiness" not in health_test:
        fail("legacy dc-interaction must use the readiness actuator health check")
    interaction_environment = interaction.get("environment") or {}
    for key, expected in PRODUCTION_ENVIRONMENT.items():
        if str(interaction_environment.get(key, "")).lower() != expected:
            fail(f"legacy dc-interaction must set {key}={expected}")
    for option in ("-XX:MaxRAMPercentage=", "-XX:+ExitOnOutOfMemoryError"):
        if option not in interaction_environment.get("JAVA_TOOL_OPTIONS", ""):
            fail(f"legacy dc-interaction JAVA_TOOL_OPTIONS is missing {option}")
    if not 1 <= int(interaction_environment.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "0")) <= 10:
        fail("legacy dc-interaction Hikari maximum pool size must be between 1 and 10")
    if not 500 <= int(interaction_environment.get("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "0")) <= 5000:
        fail("legacy dc-interaction Hikari connection timeout must be between 500 and 5000 ms")
    if not 8 <= int(interaction_environment.get("SERVER_TOMCAT_THREADS_MAX", "0")) <= 64:
        fail("legacy dc-interaction Tomcat thread ceiling must be between 8 and 64")
    if len(str(interaction_environment.get("DEVCIRCLE_INTERNAL_OPS_KEY", ""))) < 32:
        fail("legacy dc-interaction must receive DEVCIRCLE_INTERNAL_OPS_KEY")

    expected_route = "http://dc-interaction:8105"
    for owner in ("dc-identity", "dc-gateway", "dc-content-aggregate", "dc-admin"):
        environment = services[owner].get("environment") or {}
        if environment.get("DC_INTERACTION_URI") != expected_route:
            fail(f"legacy Interaction rollback must set {owner} DC_INTERACTION_URI={expected_route}")
        dependency = (services[owner].get("depends_on") or {}).get("dc-interaction") or {}
        if dependency.get("condition") != "service_healthy":
            fail(f"legacy Interaction rollback must make {owner} wait for healthy dc-interaction")
    identity_environment = services["dc-identity"].get("environment") or {}
    for switch in ("INTERACTION_FLYWAY_ENABLED", "INTERACTION_RUNTIME_OUTBOX_ENABLED"):
        if str(identity_environment.get(switch, "")).lower() != "false":
            fail(f"legacy Interaction rollback must disable {switch} in dc-identity")
    admin_environment = services["dc-admin"].get("environment") or {}
    if admin_environment.get("DC_INTERACTION_OUTBOX_PATH") != "/internal/ops/outbox/status":
        fail("legacy Interaction rollback must restore the standalone Outbox status path")

    config_mounts = [
        volume for volume in (services["prometheus"].get("volumes") or [])
        if volume.get("target") == "/etc/prometheus/prometheus.yml"
    ]
    if len(config_mounts) != 1 or not str(config_mounts[0].get("source", "")).replace("\\", "/").endswith(
            "/deploy/prometheus/prometheus.legacy-interaction-rollback.yml"):
        fail("legacy Interaction rollback must mount prometheus.legacy-interaction-rollback.yml")
    production_text = production_prometheus.read_text(encoding="utf-8")
    rollback_text = rollback_prometheus.read_text(encoding="utf-8")
    if "dc-interaction:8105" in production_text or "service: dc-interaction" in production_text:
        fail("normal production Prometheus must not scrape the absent dc-interaction target")
    if "targets: [\"dc-interaction:8105\"]" not in rollback_text \
            or "labels: { service: dc-interaction }" not in rollback_text:
        fail("legacy Interaction rollback Prometheus must scrape dc-interaction actuator metrics")

    total_memory = sum(int(positive_number(name, config, "mem_limit"))
                       for name, config in services.items())
    if total_memory > 15 * GIB:
        fail(
            "legacy Interaction rollback aggregate container memory ceiling "
            f"{total_memory / GIB:.2f} GiB exceeds 15 GiB"
        )
    return len(services), total_memory


def validate_legacy_notification_rollback(
        cutover: dict,
        rollback: dict,
        cutover_prometheus: Path,
        rollback_prometheus: Path,
) -> tuple[int, int]:
    """Validate exactly-one Notification ownership in the rollback topology."""
    validate(cutover, "identity")
    services = rollback.get("services") or {}
    expected_services = EXPECTED_SERVICES | {"dc-notification"}
    if set(services) != expected_services:
        fail(
            "legacy Notification rollback services do not match the 18-service contract: "
            f"missing={sorted(expected_services.difference(services))} "
            f"unexpected={sorted(set(services).difference(expected_services))}"
        )
    notification = services["dc-notification"]
    memory = int(positive_number("dc-notification", notification, "mem_limit"))
    if not 384 * MIB <= memory <= 768 * MIB:
        fail("legacy dc-notification memory limit must stay between 384 and 768 MiB")
    port_contract("dc-notification", notification)
    logging_contract("dc-notification", notification)
    health_test = (notification.get("healthcheck") or {}).get("test") or []
    if "http://localhost:8108/actuator/health/readiness" not in health_test:
        fail("legacy dc-notification must use the readiness actuator health check")
    for key, expected in PRODUCTION_ENVIRONMENT.items():
        if str((notification.get("environment") or {}).get(key, "")).lower() != expected:
            fail(f"legacy dc-notification must set {key}={expected}")

    identity_environment = services["dc-identity"].get("environment") or {}
    for switch in (
        "NOTIFICATION_RUNTIME_API_ENABLED", "NOTIFICATION_FLYWAY_ENABLED",
        "NOTIFICATION_RUNTIME_CONSUMER_ENABLED", "NOTIFICATION_RUNTIME_TOPIC_ADMIN_ENABLED",
        "NOTIFICATION_RUNTIME_WEBSOCKET_ENABLED",
    ):
        if str(identity_environment.get(switch, "")).lower() != "false":
            fail(f"legacy Notification rollback must disable {switch} in dc-identity")
    for owner in ("dc-identity", "dc-gateway"):
        environment = services[owner].get("environment") or {}
        if environment.get("DC_NOTIFICATION_URI") != "http://dc-notification:8108":
            fail(f"legacy Notification rollback must route {owner} to dc-notification")
        dependency = (services[owner].get("depends_on") or {}).get("dc-notification") or {}
        if dependency.get("condition") != "service_healthy":
            fail(f"legacy Notification rollback must make {owner} wait for healthy dc-notification")
    web_image = str(services["web-static"].get("image", ""))
    if "/web-notification-standalone:" not in web_image:
        fail("legacy Notification rollback must select the socket-disabled Web image")

    config_mounts = [
        volume for volume in (services["prometheus"].get("volumes") or [])
        if volume.get("target") == "/etc/prometheus/prometheus.yml"
    ]
    if len(config_mounts) != 1 or not str(config_mounts[0].get("source", "")).replace("\\", "/").endswith(
            "/deploy/prometheus/prometheus.legacy-notification-rollback.yml"):
        fail("legacy Notification rollback must mount its Prometheus configuration")
    cutover_text = cutover_prometheus.read_text(encoding="utf-8")
    rollback_text = rollback_prometheus.read_text(encoding="utf-8")
    if "dc-notification:8108" in cutover_text or "service: dc-notification" in cutover_text:
        fail("Notification Identity cutover Prometheus must not scrape absent standalone Notification")
    if "targets: [\"dc-notification:8108\"]" not in rollback_text:
        fail("legacy Notification rollback Prometheus must scrape dc-notification")
    total_memory = sum(int(positive_number(name, config, "mem_limit"))
                       for name, config in services.items())
    if total_memory > 15 * GIB:
        fail("legacy Notification rollback aggregate memory exceeds 15 GiB")
    return len(services), total_memory


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--compose-file", default="docker-compose.prod.yml", type=Path)
    parser.add_argument(
        "--legacy-user-overlay",
        default="deploy/compose/docker-compose.legacy-user-rollback.yml",
        type=Path,
    )
    parser.add_argument(
        "--legacy-interaction-overlay",
        default="deploy/compose/docker-compose.legacy-interaction-rollback.yml",
        type=Path,
    )
    parser.add_argument(
        "--notification-identity-cutover-overlay",
        default="deploy/compose/docker-compose.notification-identity-cutover.yml",
        type=Path,
    )
    parser.add_argument(
        "--legacy-notification-overlay",
        default="deploy/compose/docker-compose.legacy-notification-rollback.yml",
        type=Path,
    )
    parser.add_argument("--env-file", default=".env.production.example", type=Path)
    parser.add_argument("--caddyfile", default="deploy/Caddyfile", type=Path)
    parser.add_argument("--helm-values", default="deploy/helm/devcircle/values.yaml", type=Path)
    parser.add_argument("--helm-prod-values", default="deploy/helm/devcircle/values-prod.yaml", type=Path)
    parser.add_argument(
        "--helm-notification-identity-cutover-values",
        default="deploy/helm/devcircle/values-notification-identity-cutover.yaml",
        type=Path,
    )
    parser.add_argument(
        "--helm-service-template",
        default="deploy/helm/devcircle/templates/services.yaml",
        type=Path,
    )
    args = parser.parse_args()
    try:
        production = rendered_compose(args.compose_file, args.env_file)
        service_count, total_memory = validate(production, "standalone")
        cutover = rendered_compose(
            [args.compose_file, args.notification_identity_cutover_overlay], args.env_file
        )
        cutover_count, cutover_memory = validate(cutover, "identity")
        rollback_count, rollback_memory = validate_legacy_user_rollback(
            production,
            rendered_compose([args.compose_file, args.legacy_user_overlay], args.env_file),
            Path("deploy/prometheus/prometheus.prod.yml"),
            Path("deploy/prometheus/prometheus.legacy-user-rollback.yml"),
        )
        interaction_rollback_count, interaction_rollback_memory = validate_legacy_interaction_rollback(
            production,
            rendered_compose([args.compose_file, args.legacy_interaction_overlay], args.env_file),
            Path("deploy/prometheus/prometheus.prod.yml"),
            Path("deploy/prometheus/prometheus.legacy-interaction-rollback.yml"),
        )
        notification_rollback_count, notification_rollback_memory = validate_legacy_notification_rollback(
            cutover,
            rendered_compose([args.compose_file, args.legacy_notification_overlay], args.env_file),
            Path("deploy/prometheus/prometheus.notification-identity-cutover.yml"),
            Path("deploy/prometheus/prometheus.legacy-notification-rollback.yml"),
        )
        validate_caddyfile(args.caddyfile)
        validate_production_mode_bindings(Path("."))
        validate_helm_topology(
            load_simple_yaml_mapping(args.helm_values),
            load_simple_yaml_mapping(args.helm_prod_values),
            args.helm_service_template.read_text(encoding="utf-8"),
        )
        validate_helm_topology(
            load_simple_yaml_mapping(args.helm_values),
            load_simple_yaml_mapping(args.helm_prod_values),
            args.helm_service_template.read_text(encoding="utf-8"),
            load_simple_yaml_mapping(args.helm_notification_identity_cutover_values),
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Production resource budget validation failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Production resource budgets validated: services={service_count} "
        f"memory_ceiling={total_memory / GIB:.2f}GiB published_services={len(PUBLISHED_PORTS)} "
        f"notification_cutover_services={cutover_count} "
        f"notification_cutover_memory_ceiling={cutover_memory / GIB:.2f}GiB "
        f"legacy_user_services={rollback_count} "
        f"legacy_user_memory_ceiling={rollback_memory / GIB:.2f}GiB "
        f"legacy_interaction_services={interaction_rollback_count} "
        f"legacy_interaction_memory_ceiling={interaction_rollback_memory / GIB:.2f}GiB "
        f"legacy_notification_services={notification_rollback_count} "
        f"legacy_notification_memory_ceiling={notification_rollback_memory / GIB:.2f}GiB "
        "helm_notification_default=standalone helm_notification_cutover=identity"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
