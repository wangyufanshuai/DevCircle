from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("assert-production-resource-budgets.py")
SPEC = importlib.util.spec_from_file_location("production_resource_budgets", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load {SCRIPT}")
budgets = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(budgets)


def app_environment() -> dict[str, str]:
    return {
        "JAVA_TOOL_OPTIONS": (
            "-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=20.0 "
            "-XX:+ExitOnOutOfMemoryError"
        ),
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "8",
        "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT": "3000",
        "SERVER_TOMCAT_THREADS_MAX": "48",
        "DEVCIRCLE_PRODUCTION_MODE": "true",
        "DEVCIRCLE_READ_FALLBACK_ENABLED": "false",
        "DEVCIRCLE_THEME_CONTENT_SEED_ENABLED": "false",
        "SPRINGDOC_API_DOCS_ENABLED": "false",
        "SPRINGDOC_SWAGGER_UI_ENABLED": "false",
        "MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS": "true",
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://jaeger:4318",
        "DEVCIRCLE_INTERNAL_OPS_KEY": "Ops-4w-N8-kR2-sV7-xM5-qL9-cD1-pT6-yH",
    }


def service(memory: int = 512 * budgets.MIB) -> dict:
    return {
        "mem_limit": memory,
        "cpus": 0.75,
        "pids_limit": 256,
        "logging": copy.deepcopy(budgets.LOGGING_CONTRACT),
    }


def valid_compose() -> dict:
    services = {
        name: {**service(), "environment": app_environment()}
        for name in budgets.APP_SERVICES
    }
    services.update({
        "mysql": {
            **service(2 * budgets.GIB),
            "cpus": 1.5,
            "pids_limit": 512,
            "command": ["--max-connections=120", "--innodb-buffer-pool-size=768M"],
        },
        "redis": service(384 * budgets.MIB),
        "kafka": {
            **service(1536 * budgets.MIB),
            "cpus": 1.25,
            "pids_limit": 512,
            "environment": {"KAFKA_HEAP_OPTS": "-Xms512m -Xmx768m"},
        },
        "elasticsearch": {
            **service(2 * budgets.GIB),
            "cpus": 1.5,
            "pids_limit": 512,
            "environment": {"ES_JAVA_OPTS": "-Xms1g -Xmx1g"},
        },
        "minio": service(768 * budgets.MIB),
        "web-static": service(128 * budgets.MIB),
        "web": {
            **service(256 * budgets.MIB),
            "ports": [
                {"published": 80, "target": 80, "protocol": "tcp"},
                {"published": 443, "target": 443, "protocol": "tcp"},
                {"published": 443, "target": 443, "protocol": "udp"},
            ],
        },
        "prometheus": {
            **service(256 * budgets.MIB),
            "command": [
                "--storage.tsdb.retention.time=35d",
                "--storage.tsdb.retention.size=1GB",
            ],
        },
        "alertmanager": service(128 * budgets.MIB),
        "jaeger": {
            **service(256 * budgets.MIB),
            "environment": {"SPAN_STORAGE_TYPE": "badger", "BADGER_EPHEMERAL": "false"},
        },
    })
    services["dc-gateway"]["ports"] = [
        {"host_ip": "127.0.0.1", "published": 8080, "target": 8080, "protocol": "tcp"},
    ]
    return {"services": services}


class ProductionResourceBudgetTest(unittest.TestCase):
    def setUp(self) -> None:
        self.compose = valid_compose()

    def test_accepts_the_24_service_production_contract(self) -> None:
        service_count, total_memory = budgets.validate(self.compose)

        self.assertEqual(24, service_count)
        self.assertLessEqual(total_memory, 15 * budgets.GIB)

    def test_rejects_missing_internal_operations_key(self) -> None:
        del self.compose["services"]["dc-auth"]["environment"]["DEVCIRCLE_INTERNAL_OPS_KEY"]

        with self.assertRaisesRegex(ValueError, "DEVCIRCLE_INTERNAL_OPS_KEY"):
            budgets.validate(self.compose)

    def test_rejects_a_missing_memory_limit(self) -> None:
        del self.compose["services"]["dc-auth"]["mem_limit"]

        with self.assertRaisesRegex(ValueError, "dc-auth must define numeric mem_limit"):
            budgets.validate(self.compose)

    def test_rejects_a_service_without_bounded_log_rotation(self) -> None:
        del self.compose["services"]["elasticsearch"]["logging"]["options"]["max-size"]

        with self.assertRaisesRegex(ValueError, "elasticsearch logging option max-size"):
            budgets.validate(self.compose)

    def test_rejects_a_private_service_with_a_published_port(self) -> None:
        self.compose["services"]["redis"]["ports"] = [
            {"published": 6379, "target": 6379, "protocol": "tcp"},
        ]

        with self.assertRaisesRegex(ValueError, "redis published ports"):
            budgets.validate(self.compose)

    def test_rejects_an_aggregate_memory_ceiling_above_15_gib(self) -> None:
        self.compose["services"]["mysql"]["mem_limit"] = 9 * budgets.GIB

        with self.assertRaisesRegex(ValueError, "exceeds 15 GiB"):
            budgets.validate(self.compose)

    def test_rejects_a_jvm_without_the_heap_contract(self) -> None:
        self.compose["services"]["dc-auth"]["environment"]["JAVA_TOOL_OPTIONS"] = (
            "-XX:+ExitOnOutOfMemoryError"
        )

        with self.assertRaisesRegex(ValueError, "dc-auth JAVA_TOOL_OPTIONS is missing"):
            budgets.validate(self.compose)

    def test_rejects_an_unexpected_production_service(self) -> None:
        invalid = copy.deepcopy(self.compose)
        invalid["services"]["debug-console"] = service()

        with self.assertRaisesRegex(ValueError, "unexpected=.*debug-console"):
            budgets.validate(invalid)

    def test_rejects_a_gateway_port_bound_to_all_interfaces(self) -> None:
        self.compose["services"]["dc-gateway"]["ports"][0].pop("host_ip")

        with self.assertRaisesRegex(ValueError, "dc-gateway published ports"):
            budgets.validate(self.compose)

    def test_rejects_production_fallback_or_api_documentation(self) -> None:
        self.compose["services"]["dc-content"]["environment"]["DEVCIRCLE_READ_FALLBACK_ENABLED"] = "true"
        with self.assertRaisesRegex(ValueError, "DEVCIRCLE_READ_FALLBACK_ENABLED=false"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        self.compose["services"]["dc-auth"]["environment"]["SPRINGDOC_API_DOCS_ENABLED"] = "true"
        with self.assertRaisesRegex(ValueError, "SPRINGDOC_API_DOCS_ENABLED=false"):
            budgets.validate(self.compose)

    def test_caddy_admin_api_is_restricted_to_the_container_loopback(self) -> None:
        temporary = Path(self.id().replace(".", "-") + ".Caddyfile")
        try:
            temporary.write_text("{\n    admin localhost:2019\n}\n", encoding="utf-8")
            budgets.validate_caddyfile(temporary)
            temporary.write_text("{\n    admin 0.0.0.0:2019\n}\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "localhost:2019"):
                budgets.validate_caddyfile(temporary)
        finally:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
