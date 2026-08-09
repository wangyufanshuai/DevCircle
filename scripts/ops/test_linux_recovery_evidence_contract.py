from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "scripts" / "ops" / "finalize-linux-recovery-evidence.sh").read_text(encoding="utf-8")


class LinuxRecoveryEvidenceContractTest(unittest.TestCase):
    def test_requires_linux_confirmation_and_immutable_identity(self) -> None:
        self.assertIn('[[ "$(uname -s)" == "Linux" ]]', SOURCE)
        self.assertIn('RECOVERY_EVIDENCE_CONFIRM', SOURCE)
        self.assertIn('imageTag does not match checked-out Git SHA', SOURCE)

    def test_requires_full_recovery_evidence_set(self) -> None:
        for artifact in (
            "host.json", "compose-before.json", "compose-after.json", "backup-manifest.json",
            "SHA256SUMS", "recovery-timeline.json", "verify-migration.json",
            "elasticsearch-rebuild.json", "kafka-catchup.json", "dlt-replay.json",
            "service-health.json", "public-health.json",
        ):
            with self.subTest(artifact=artifact):
                self.assertIn(artifact, SOURCE)

    def test_enforces_rto_rpo_and_component_pass_states(self) -> None:
        self.assertIn("MAX_RTO_SECONDS", SOURCE)
        self.assertIn("MAX_RPO_SECONDS", SOURCE)
        self.assertIn('component_status}" == "PASS"', SOURCE)
        self.assertIn("recovery-summary.json", SOURCE)


if __name__ == "__main__":
    unittest.main()
