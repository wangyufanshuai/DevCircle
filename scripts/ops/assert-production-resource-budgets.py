#!/usr/bin/env python3
"""Validate the rendered production Compose resource and exposure contract."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


MIB = 1024 * 1024
GIB = 1024 * MIB
APP_SERVICES = {
    "dc-admin", "dc-auth", "dc-comment", "dc-content", "dc-content-aggregate",
    "dc-discovery", "dc-file", "dc-gateway", "dc-identity", "dc-interaction",
    "dc-notification", "dc-recommend", "dc-search", "dc-user",
}
EXPECTED_SERVICES = APP_SERVICES | {
    "mysql", "redis", "kafka", "elasticsearch", "minio", "web-static", "web",
}
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
}


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


def validate_caddyfile(caddyfile: Path) -> None:
    try:
        text = caddyfile.read_text(encoding="utf-8")
    except OSError as error:
        fail(f"cannot read production Caddyfile: {error}")
    if "admin localhost:2019" not in text:
        fail("Caddy admin API must listen on localhost:2019")
    if "admin 0.0.0.0:" in text or "admin :2019" in text:
        fail("Caddy admin API must not listen on all container interfaces")


def validate(data: dict) -> tuple[int, int]:
    services = data.get("services") or {}
    missing_apps = APP_SERVICES.difference(services)
    if missing_apps:
        fail(f"missing application services: {sorted(missing_apps)}")
    missing_services = EXPECTED_SERVICES.difference(services)
    unexpected_services = set(services).difference(EXPECTED_SERVICES)
    if missing_services or unexpected_services:
        fail(
            "production services do not match the 21-service contract: "
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

    mysql_command = services["mysql"].get("command") or []
    if "--max-connections=120" not in mysql_command or "--innodb-buffer-pool-size=768M" not in mysql_command:
        fail("mysql must cap connections and the InnoDB buffer pool")
    kafka_heap = (services["kafka"].get("environment") or {}).get("KAFKA_HEAP_OPTS", "")
    if "-Xmx768m" not in kafka_heap:
        fail("kafka heap must be capped at 768 MiB")
    elastic_heap = (services["elasticsearch"].get("environment") or {}).get("ES_JAVA_OPTS", "")
    if "-Xmx1g" not in elastic_heap:
        fail("Elasticsearch heap must be capped at 1 GiB")

    return len(services), total_memory


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--compose-file", default="docker-compose.prod.yml", type=Path)
    parser.add_argument("--env-file", default=".env.production.example", type=Path)
    parser.add_argument("--caddyfile", default="deploy/Caddyfile", type=Path)
    args = parser.parse_args()
    try:
        service_count, total_memory = validate(rendered_compose(args.compose_file, args.env_file))
        validate_caddyfile(args.caddyfile)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Production resource budget validation failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Production resource budgets validated: services={service_count} "
        f"memory_ceiling={total_memory / GIB:.2f}GiB published_services={len(PUBLISHED_PORTS)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
