from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "scripts" / "performance" / "run-linux-acceptance.sh").read_text(encoding="utf-8")


class LinuxAcceptanceContractTest(unittest.TestCase):
    def test_rejects_non_linux_and_under_sized_hosts(self) -> None:
        self.assertIn('test "$(uname -s)" = "Linux"', SOURCE)
        for requirement in ("MIN_CPU", "MIN_MEMORY_GIB", "MIN_DISK_GIB"):
            self.assertIn(requirement, SOURCE)

    def test_requires_immutable_release_identity_and_real_credentials(self) -> None:
        self.assertIn('require_value POST_ID', SOURCE)
        self.assertIn('require_value TOKENS', SOURCE)
        self.assertIn('require_value ADMIN_TOKEN', SOURCE)
        self.assertIn('test "${image_tag}" = "${git_sha}"', SOURCE)
        self.assertIn('status --porcelain --untracked-files=normal', SOURCE)

    def test_retains_load_runtime_and_reliability_evidence(self) -> None:
        required_artifacts = (
            "k6-summary.json",
            "compose-before.json",
            "compose-after.json",
            "docker-stats-before.txt",
            "docker-stats-after.txt",
            "outbox-fleet-health.json",
            "SHA256SUMS",
        )
        for artifact in required_artifacts:
            with self.subTest(artifact=artifact):
                self.assertIn(artifact, SOURCE)
        self.assertIn("prometheus_query read_p95_5m", SOURCE)
        self.assertIn("prometheus_query write_p95_5m", SOURCE)
        self.assertIn('DURATION="${DURATION:-3m}"', SOURCE)
        self.assertIn('READ_VUS="${READ_VUS:-40}"', SOURCE)
        self.assertIn('WRITE_VUS="${WRITE_VUS:-10}"', SOURCE)


if __name__ == "__main__":
    unittest.main()
