from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("assert-production-resource-budgets.py")
ROOT = SCRIPT.parents[2]
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
    services["dc-gateway"]["environment"].update({
        "DC_AUTH_URI": "http://dc-identity:8201",
        "DC_IDENTITY_URI": "http://dc-identity:8201",
        "DC_USER_URI": "http://dc-identity:8201",
        "DC_INTERACTION_URI": "http://dc-interaction:8105",
        "DC_NOTIFICATION_URI": "http://dc-notification:8108",
        "DC_DISCOVERY_URI": "http://dc-discovery:8203",
        "DC_SEARCH_URI": "http://dc-discovery:8203",
        "DC_RECOMMEND_URI": "http://dc-recommend:8107",
    })
    services["dc-gateway"]["depends_on"] = {
        name: {"condition": "service_healthy"}
        for name in ("dc-identity", "dc-content-aggregate", "dc-discovery", "dc-recommend")
    }
    services["dc-admin"]["environment"].update({
        "DC_AUTH_URI": "http://dc-identity:8201",
        "DC_USER_URI": "http://dc-identity:8201",
        "DC_AUTH_OUTBOX_PATH": "/internal/ops/identity/outbox/auth/status",
        "DC_USER_OUTBOX_PATH": "/internal/ops/identity/outbox/user/status",
    })
    services["dc-identity"]["environment"].update({
        "IDENTITY_AUTH_DB_POOL_MAX_SIZE": "6",
        "IDENTITY_USER_DB_POOL_MAX_SIZE": "5",
        "IDENTITY_INTERACTION_DB_POOL_MAX_SIZE": "5",
    })
    services["dc-content-aggregate"]["environment"].update({
        "DC_USER_URI": "http://dc-identity:8201",
        "DC_INTERACTION_URI": "http://dc-interaction:8105",
    })
    services["dc-discovery"]["depends_on"] = {
        name: {"condition": "service_healthy"}
        for name in ("mysql", "elasticsearch", "kafka", "dc-content-aggregate")
    }
    return {"services": services}


def valid_legacy_user_rollback() -> dict:
    rollback = copy.deepcopy(valid_compose())
    services = rollback["services"]
    services["dc-user"] = {
        **service(),
        "environment": app_environment(),
        "expose": ["8102"],
        "healthcheck": {
            "test": [
                "CMD", "curl", "-fsS",
                "http://localhost:8102/actuator/health/readiness",
            ],
        },
    }
    for owner in ("dc-identity", "dc-gateway", "dc-content-aggregate", "dc-admin"):
        services[owner]["environment"]["DC_USER_URI"] = "http://dc-user:8102"
        services[owner].setdefault("depends_on", {})["dc-user"] = {
            "condition": "service_healthy",
        }
    services["dc-identity"]["environment"].update({
        "USER_RUNTIME_CONSUMER_ENABLED": "false",
        "USER_PROFILE_BACKFILL_ENABLED": "false",
        "USER_RUNTIME_OUTBOX_ENABLED": "false",
        "USER_FLYWAY_ENABLED": "false",
    })
    services["dc-admin"]["environment"]["DC_USER_OUTBOX_PATH"] = (
        "/internal/ops/outbox/status"
    )
    services["prometheus"]["volumes"] = [{
        "type": "bind",
        "source": str(ROOT / "deploy/prometheus/prometheus.legacy-user-rollback.yml"),
        "target": "/etc/prometheus/prometheus.yml",
        "read_only": True,
    }]
    return rollback


class ProductionResourceBudgetTest(unittest.TestCase):
    def setUp(self) -> None:
        self.compose = valid_compose()

    def test_accepts_the_19_service_production_contract(self) -> None:
        service_count, total_memory = budgets.validate(self.compose)

        self.assertEqual(19, service_count)
        self.assertLessEqual(total_memory, 15 * budgets.GIB)

    def test_rejects_missing_internal_operations_key(self) -> None:
        del self.compose["services"]["dc-identity"]["environment"]["DEVCIRCLE_INTERNAL_OPS_KEY"]

        with self.assertRaisesRegex(ValueError, "DEVCIRCLE_INTERNAL_OPS_KEY"):
            budgets.validate(self.compose)

    def test_rejects_missing_or_excessive_identity_logical_pool_budgets(self) -> None:
        del self.compose["services"]["dc-identity"]["environment"][
            "IDENTITY_USER_DB_POOL_MAX_SIZE"
        ]
        with self.assertRaisesRegex(ValueError, "User pool budget"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        self.compose["services"]["dc-identity"]["environment"].update({
            "IDENTITY_AUTH_DB_POOL_MAX_SIZE": "6",
            "IDENTITY_USER_DB_POOL_MAX_SIZE": "6",
            "IDENTITY_INTERACTION_DB_POOL_MAX_SIZE": "6",
        })
        with self.assertRaisesRegex(ValueError, "aggregate Auth, User and Interaction pool ceiling"):
            budgets.validate(self.compose)

    def test_rejects_a_missing_memory_limit(self) -> None:
        del self.compose["services"]["dc-identity"]["mem_limit"]

        with self.assertRaisesRegex(ValueError, "dc-identity must define numeric mem_limit"):
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
        self.compose["services"]["dc-identity"]["environment"]["JAVA_TOOL_OPTIONS"] = (
            "-XX:+ExitOnOutOfMemoryError"
        )

        with self.assertRaisesRegex(ValueError, "dc-identity JAVA_TOOL_OPTIONS is missing"):
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
        self.compose["services"]["dc-content-aggregate"]["environment"]["DEVCIRCLE_READ_FALLBACK_ENABLED"] = "true"
        with self.assertRaisesRegex(ValueError, "DEVCIRCLE_READ_FALLBACK_ENABLED=false"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        self.compose["services"]["dc-identity"]["environment"]["SPRINGDOC_API_DOCS_ENABLED"] = "true"
        with self.assertRaisesRegex(ValueError, "SPRINGDOC_API_DOCS_ENABLED=false"):
            budgets.validate(self.compose)

    def test_rejects_a_separate_auth_service_or_identity_auth_proxy(self) -> None:
        self.compose["services"]["dc-auth"] = {
            **service(), "environment": app_environment()
        }
        with self.assertRaisesRegex(ValueError, "unexpected=.*dc-auth"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        self.compose["services"]["dc-identity"]["environment"]["DC_AUTH_URI"] = "http://dc-auth:8101"
        with self.assertRaisesRegex(ValueError, "embed authentication"):
            budgets.validate(self.compose)

    def test_rejects_a_separate_user_service_or_identity_user_self_proxy(self) -> None:
        self.compose["services"]["dc-user"] = {
            **service(), "environment": app_environment()
        }
        with self.assertRaisesRegex(ValueError, "unexpected=.*dc-user"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        self.compose["services"]["dc-identity"]["environment"]["DC_USER_URI"] = (
            "http://dc-identity:8201"
        )
        with self.assertRaisesRegex(ValueError, "embedded User runtime"):
            budgets.validate(self.compose)

    def test_rejects_outbox_or_gateway_routes_that_bypass_the_new_topology(self) -> None:
        self.compose["services"]["dc-admin"]["environment"]["DC_AUTH_URI"] = "http://dc-auth:8101"
        with self.assertRaisesRegex(ValueError, "Outbox source from dc-identity"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        self.compose["services"]["dc-admin"]["environment"]["DC_USER_OUTBOX_PATH"] = (
            "/internal/ops/outbox/status"
        )
        with self.assertRaisesRegex(ValueError, "DC_USER_OUTBOX_PATH"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        del self.compose["services"]["dc-gateway"]["environment"]["DC_AUTH_URI"]
        with self.assertRaisesRegex(ValueError, "DC_AUTH_URI"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        del self.compose["services"]["dc-gateway"]["environment"]["DC_USER_URI"]
        with self.assertRaisesRegex(ValueError, "DC_USER_URI"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        del self.compose["services"]["dc-content-aggregate"]["environment"]["DC_INTERACTION_URI"]
        with self.assertRaisesRegex(ValueError, "DC_INTERACTION_URI"):
            budgets.validate(self.compose)

    def test_rejects_a_standalone_or_proxy_search_production_runtime(self) -> None:
        self.compose["services"]["dc-search"] = {
            **service(), "environment": app_environment()
        }
        with self.assertRaisesRegex(ValueError, "unexpected=.*dc-search"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        self.compose["services"]["dc-discovery"]["environment"]["DC_SEARCH_URI"] = (
            "http://dc-search:8106"
        )
        with self.assertRaisesRegex(ValueError, "embed search"):
            budgets.validate(self.compose)

        self.compose = valid_compose()
        del self.compose["services"]["dc-discovery"]["depends_on"]["elasticsearch"]
        with self.assertRaisesRegex(ValueError, "depend on elasticsearch"):
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


class LegacyUserRollbackResourceBudgetTest(unittest.TestCase):
    def setUp(self) -> None:
        self.production = valid_compose()
        self.rollback = valid_legacy_user_rollback()
        self.production_prometheus = ROOT / "deploy/prometheus/prometheus.prod.yml"
        self.rollback_prometheus = (
            ROOT / "deploy/prometheus/prometheus.legacy-user-rollback.yml"
        )

    def validate(self) -> tuple[int, int]:
        return budgets.validate_legacy_user_rollback(
            self.production,
            self.rollback,
            self.production_prometheus,
            self.rollback_prometheus,
        )

    def test_accepts_the_bounded_observable_legacy_user_topology(self) -> None:
        service_count, total_memory = self.validate()

        self.assertEqual(20, service_count)
        self.assertLessEqual(total_memory, 15 * budgets.GIB)

    def test_rejects_identity_without_the_legacy_dashboard_user_route(self) -> None:
        del self.rollback["services"]["dc-identity"]["environment"]["DC_USER_URI"]

        with self.assertRaisesRegex(ValueError, "dc-identity DC_USER_URI"):
            self.validate()

    def test_rejects_unbounded_or_unobservable_legacy_user(self) -> None:
        self.rollback["services"]["dc-user"]["mem_limit"] = budgets.GIB
        with self.assertRaisesRegex(ValueError, "legacy dc-user memory limit"):
            self.validate()

        self.rollback = valid_legacy_user_rollback()
        self.rollback["services"]["prometheus"]["volumes"][0]["source"] = (
            str(self.production_prometheus)
        )
        with self.assertRaisesRegex(ValueError, "must mount prometheus.legacy-user-rollback.yml"):
            self.validate()

    def test_rejects_dual_identity_user_writers(self) -> None:
        self.rollback["services"]["dc-identity"]["environment"][
            "USER_RUNTIME_OUTBOX_ENABLED"
        ] = "true"

        with self.assertRaisesRegex(ValueError, "disable USER_RUNTIME_OUTBOX_ENABLED"):
            self.validate()


class HelmTopologyContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.values = budgets.load_simple_yaml_mapping(
            ROOT / "deploy/helm/devcircle/values.yaml"
        )
        self.production = budgets.load_simple_yaml_mapping(
            ROOT / "deploy/helm/devcircle/values-prod.yaml"
        )
        self.template = (
            ROOT / "deploy/helm/devcircle/templates/services.yaml"
        ).read_text(encoding="utf-8")

    def test_all_reliable_messaging_runtimes_bind_the_production_guard(self) -> None:
        budgets.validate_production_mode_bindings(ROOT)

    def test_accepts_identity_as_the_explicit_helm_auth_runtime_without_helm(self) -> None:
        budgets.validate_helm_topology(self.values, self.production, self.template)

    def test_rejects_a_separate_or_proxied_search_helm_runtime(self) -> None:
        separate = copy.deepcopy(self.values)
        separate["services"]["search"] = {"port": "8106"}
        with self.assertRaisesRegex(ValueError, "embed search"):
            budgets.validate_helm_topology(separate, self.production, self.template)

        proxied = copy.deepcopy(self.values)
        proxied["services"]["discovery"]["env"] = {
            "DC_SEARCH_URI": "http://dc-search:8106"
        }
        with self.assertRaisesRegex(ValueError, "embed search"):
            budgets.validate_helm_topology(proxied, self.production, self.template)

    def test_rejects_a_missing_or_globally_duplicated_gateway_auth_route(self) -> None:
        missing = copy.deepcopy(self.values)
        del missing["services"]["gateway"]["env"]["DC_AUTH_URI"]
        with self.assertRaisesRegex(ValueError, "explicitly set DC_AUTH_URI"):
            budgets.validate_helm_topology(missing, self.production, self.template)

        duplicated = copy.deepcopy(self.production)
        duplicated["env"]["DC_AUTH_URI"] = "http://dc-identity:8201"
        with self.assertRaisesRegex(ValueError, "duplicates global environment"):
            budgets.validate_helm_topology(self.values, duplicated, self.template)

    def test_rejects_a_template_that_drops_service_environment_rendering(self) -> None:
        broken = self.template.replace("($svc.env | default dict)", "$.Values.missingServiceEnv")
        with self.assertRaisesRegex(ValueError, "render service environment exactly once"):
            budgets.validate_helm_topology(self.values, self.production, broken)

    def test_rejects_insecure_identity_cookie_or_callback_configuration(self) -> None:
        insecure_cookie = copy.deepcopy(self.production)
        insecure_cookie["services"]["identity"]["env"]["AUTH_COOKIE_SECURE"] = "false"
        with self.assertRaisesRegex(ValueError, "AUTH_COOKIE_SECURE=true"):
            budgets.validate_helm_topology(self.values, insecure_cookie, self.template)

        local_callback = copy.deepcopy(self.production)
        local_callback["services"]["identity"]["env"]["GITHUB_CALLBACK_URI"] = (
            "http://localhost:8080/api/auth/oauth2/github/callback"
        )
        with self.assertRaisesRegex(ValueError, "GITHUB_CALLBACK_URI"):
            budgets.validate_helm_topology(self.values, local_callback, self.template)

        local_origin = copy.deepcopy(self.production)
        local_origin["publicOrigin"] = "http://localhost:8080"
        with self.assertRaisesRegex(ValueError, "public HTTPS origin"):
            budgets.validate_helm_topology(self.values, local_origin, self.template)

    def test_rejects_invalid_identity_pool_budget_or_replica_ceiling(self) -> None:
        missing = copy.deepcopy(self.production)
        missing["services"]["identity"]["env"]["IDENTITY_USER_DB_POOL_MAX_SIZE"] = "0"
        with self.assertRaisesRegex(ValueError, "User pool budget"):
            budgets.validate_helm_topology(self.values, missing, self.template)

        excessive = copy.deepcopy(self.production)
        excessive["services"]["identity"]["env"].update({
            "IDENTITY_AUTH_DB_POOL_MAX_SIZE": "10",
            "IDENTITY_USER_DB_POOL_MAX_SIZE": "10",
            "IDENTITY_INTERACTION_DB_POOL_MAX_SIZE": "10",
        })
        excessive["replicaCount"] = "3"
        with self.assertRaisesRegex(ValueError, "fit identityDatabaseConnectionBudget"):
            budgets.validate_helm_topology(self.values, excessive, self.template)

        unbounded = copy.deepcopy(self.production)
        unbounded["identityDatabaseConnectionBudget"] = "33"
        with self.assertRaisesRegex(ValueError, "must not exceed 16 connections per replica"):
            budgets.validate_helm_topology(self.values, unbounded, self.template)

        undersized = copy.deepcopy(self.production)
        undersized["identityDatabaseConnectionBudget"] = "31"
        with self.assertRaisesRegex(ValueError, "fit identityDatabaseConnectionBudget"):
            budgets.validate_helm_topology(self.values, undersized, self.template)

    def test_rejects_internal_or_mismatched_browser_object_storage_endpoints(self) -> None:
        internal = copy.deepcopy(self.production)
        internal["env"]["S3_PRESIGN_ENDPOINT"] = "http://minio:9000"
        with self.assertRaisesRegex(ValueError, "S3_PRESIGN_ENDPOINT"):
            budgets.validate_helm_topology(self.values, internal, self.template)

        mismatched = copy.deepcopy(self.production)
        mismatched["env"]["S3_PUBLIC_BASE_URL"] = "https://assets.example.com/devcircle"
        with self.assertRaisesRegex(ValueError, "S3_PUBLIC_BASE_URL"):
            budgets.validate_helm_topology(self.values, mismatched, self.template)

    def test_ingress_routes_the_private_bucket_path_to_object_storage(self) -> None:
        ingress = (ROOT / "deploy/helm/devcircle/templates/ingress.yaml").read_text(encoding="utf-8")
        self.assertIn("path: {{ .Values.objectStorage.path }}/", ingress)
        self.assertIn("name: {{ .Values.objectStorage.serviceName }}", ingress)

    def test_rejects_missing_or_unsafe_auth_secret_projections(self) -> None:
        missing_ops = copy.deepcopy(self.values)
        del missing_ops["secretEnvRefs"]["DEVCIRCLE_INTERNAL_OPS_KEY"]
        with self.assertRaisesRegex(ValueError, "DEVCIRCLE_INTERNAL_OPS_KEY"):
            budgets.validate_helm_topology(missing_ops, self.production, self.template)

        missing_github_secret = copy.deepcopy(self.production)
        del missing_github_secret["services"]["identity"]["secretEnvRefs"]["GITHUB_CLIENT_SECRET"]
        with self.assertRaisesRegex(ValueError, "GITHUB_CLIENT_SECRET"):
            budgets.validate_helm_topology(self.values, missing_github_secret, self.template)

        default_ops = copy.deepcopy(self.production)
        default_ops["secretEnv"]["DEVCIRCLE_INTERNAL_OPS_KEY"] = (
            "local-development-internal-ops-key"
        )
        with self.assertRaisesRegex(ValueError, "default or weak"):
            budgets.validate_helm_topology(self.values, default_ops, self.template)

    def test_rejects_plain_and_secret_environment_collisions(self) -> None:
        duplicate = copy.deepcopy(self.production)
        duplicate["services"]["identity"]["env"]["GITHUB_CLIENT_ID"] = "not-a-secret"
        with self.assertRaisesRegex(ValueError, "duplicates Secret projections"):
            budgets.validate_helm_topology(self.values, duplicate, self.template)

        broken = self.template.replace("optional: false", "optional: true", 1)
        with self.assertRaisesRegex(ValueError, "fail closed"):
            budgets.validate_helm_topology(self.values, self.production, broken)


if __name__ == "__main__":
    unittest.main()
