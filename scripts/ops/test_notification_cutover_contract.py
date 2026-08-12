from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]

class NotificationCutoverContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.deploy = (ROOT / "scripts/ops/deploy-production.sh").read_text(encoding="utf-8")
        cls.restore = (ROOT / "scripts/ops/restore.sh").read_text(encoding="utf-8")
        cls.rollback = (ROOT / "scripts/ops/rollback.sh").read_text(encoding="utf-8")
        cls.overlay = (ROOT / "deploy/compose/docker-compose.legacy-notification-rollback.yml").read_text(encoding="utf-8")
        cls.cutover_overlay = (ROOT / "deploy/compose/docker-compose.notification-identity-cutover.yml").read_text(encoding="utf-8")
        cls.production = (ROOT / "docker-compose.prod.yml").read_text(encoding="utf-8")
        cls.workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        cls.verify_release = (ROOT / "scripts/ops/verify-release-identity.sh").read_text(encoding="utf-8")
        cls.runbook = (ROOT / "docs/production-runbook.md").read_text(encoding="utf-8")
        cls.production_env = (ROOT / "scripts/ops/production-env.sh").read_text(encoding="utf-8")
        cls.workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    def test_release_removes_legacy_owner_before_enabling_identity(self):
        stop = self.deploy.index("stop dc-notification")
        remove = self.deploy.index("rm -f -s dc-notification")
        up = self.deploy.index("up -d --remove-orphans")
        self.assertLess(stop, remove)
        self.assertLess(remove, up)

    def test_restore_recreates_overlay_and_never_starts_old_config(self):
        self.assertNotRegex(self.restore, r'\bstart\s+"?\$\{APP_SERVICES')
        self.assertIn("--force-recreate --wait dc-identity", self.restore)
        self.assertIn("--force-recreate --wait dc-notification", self.restore)
        self.assertLess(self.restore.index("--force-recreate --wait dc-identity"), self.restore.index("--force-recreate --wait dc-notification"))

    def test_rollback_uses_independent_immutable_asset_tag(self):
        self.assertNotIn("RESTORE_APPLICATION_MODE", self.rollback)
        self.assertNotIn("APPLICATION_MODE", self.restore)
        self.assertIn('NOTIFICATION_DEPLOY_MODE=rollback is required', self.rollback)
        self.assertIn('ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE', self.rollback)
        self.assertIn("ROLLBACK_ASSET_TAG", self.rollback)
        self.assertIn("web-notification-cutover:${CUTOVER_ASSET_TAG", self.cutover_overlay)
        self.assertNotIn("web-notification-standalone:${IMAGE_TAG", self.overlay)
        self.assertIn("web-notification-standalone:${ROLLBACK_ASSET_TAG", self.overlay)
        self.assertIn("web-notification-standalone:${IMAGE_TAG", self.production)
        self.assertIn("dc-notification:${ROLLBACK_ASSET_TAG", self.overlay)
        self.assertNotIn(":latest", self.overlay)
        self.assertIn('"${BASE[@]}" pull', self.deploy)
        self.assertIn('"${CUTOVER[@]}" pull', self.deploy)
        self.assertIn('MODE="$(production_env_value NOTIFICATION_DEPLOY_MODE)"', self.deploy)
        self.assertIn('cutover) compose_files+=', self.verify_release)
        self.assertIn('rollback) compose_files+=', self.verify_release)
        self.assertNotIn("COMPOSE_OVERLAY_FILE", self.verify_release)

    def test_all_release_entrypoints_use_the_env_precedence_wrapper(self):
        for name, source in (
            ("deploy", self.deploy),
            ("restore", self.restore),
            ("rollback", self.rollback),
            ("release verification", self.verify_release),
        ):
            with self.subTest(entrypoint=name):
                self.assertIn("production_compose", source)
                self.assertNotIn('docker compose --env-file "${ENV_FILE}"', source)
        self.assertIn('unset "${key}"', self.production_env)
        self.assertIn('docker compose --env-file "${env_file}"', self.production_env)
        self.assertIn("test-production-compose-env-precedence.sh", self.workflow)

    def test_runbook_uses_the_single_env_file_rollback_contract(self):
        self.assertNotIn("PREVIOUS_IMAGE_TAG", self.runbook)
        self.assertNotRegex(
            self.runbook,
            r"LEGACY_NOTIFICATION_ROLLBACK=true\s*\\\s*\n"
            r"ROLLBACK_ASSET_TAG=.*?scripts/ops/rollback\.sh",
        )
        self.assertIn("cp .env.production .env.rollback", self.runbook)
        self.assertIn("chmod 0600 .env.rollback", self.runbook)
        for setting in (
            "NOTIFICATION_DEPLOY_MODE=rollback",
            "IMAGE_TAG=<business-image-rollback-sha>",
            "ROLLBACK_ASSET_TAG=<cutover-asset-sha>",
            "LEGACY_NOTIFICATION_ROLLBACK=true",
        ):
            self.assertIn(setting, self.runbook)
        self.assertIn(
            "ENV_FILE=.env.rollback scripts/ops/rollback.sh "
            "/srv/backups/devcircle/<pre-migration-timestamp>",
            self.runbook,
        )
        self.assertIn("禁止使用 `latest`", self.runbook)

    def test_ci_checks_base_cutover_rollback_topologies_and_prometheus(self):
        self.assertIn("Validate Notification Identity cutover Compose topology", self.workflow)
        self.assertIn("Validate legacy Notification rollback Compose topology", self.workflow)
        self.assertIn("prometheus.notification-identity-cutover.yml", self.workflow)
        self.assertIn("prometheus.legacy-notification-rollback.yml", self.workflow)

    def test_ci_builds_and_gates_both_immutable_web_assets(self):
        for image, enabled in (
            ("web-notification-cutover", "true"),
            ("web-notification-standalone", "false"),
        ):
            self.assertIn(
                f"tags: ghcr.io/wangyufanshuai/devcircle/{image}:${{{{ github.sha }}}}",
                self.workflow,
            )
            self.assertIsNotNone(re.search(
                rf"tags: ghcr\.io/wangyufanshuai/devcircle/{image}:.*?"
                rf"build-args: VITE_NOTIFICATION_SOCKET_ENABLED={enabled}",
                self.workflow,
                re.S,
            ))
            self.assertIsNotNone(re.search(
                rf"Scan Notification .*? Web image.*?"
                rf"image-ref: ghcr\.io/wangyufanshuai/devcircle/{image}:.*?"
                rf"severity: CRITICAL,HIGH.*?exit-code: \"1\"",
                self.workflow,
                re.S,
            ))
            self.assertIn(f"IMAGE_REF: ghcr.io/wangyufanshuai/devcircle/{image}", self.workflow)
            self.assertIn(f"subject-name: ghcr.io/wangyufanshuai/devcircle/{image}", self.workflow)
        self.assertIn("Verify Notification standalone Web signature and provenance", self.workflow)
        self.assertIn("Verify Notification cutover Web signature and provenance", self.workflow)

    def test_ci_keeps_rollback_backend_under_the_same_supply_chain_gates(self):
        self.assertIn(
            "module: dc-notification, image: dc-notification, release_class: legacy-rollback",
            self.workflow,
        )
        self.assertIn("labels: io.github.devcircle.release-class=${{ matrix.release_class }}", self.workflow)
        self.assertIn("Verify signed image and provenance", self.workflow)

if __name__ == "__main__":
    unittest.main()
