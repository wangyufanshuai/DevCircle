from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "scripts" / "ops" / "verify-release-identity.sh").read_text(encoding="utf-8")


class VerifyReleaseIdentityContractTest(unittest.TestCase):
    def test_is_linux_only_and_requires_immutable_sha(self) -> None:
        self.assertIn('[[ "$(uname -s)" == "Linux" ]]', SOURCE)
        self.assertIn('IMAGE_TAG must be a lowercase 40-character commit SHA', SOURCE)
        self.assertIn('IMAGE_TAG does not match checked-out Git SHA', SOURCE)

    def test_requires_signature_and_provenance_verification(self) -> None:
        self.assertIn("cosign verify \\", SOURCE)
        self.assertIn("cosign verify-attestation \\", SOURCE)
        self.assertIn("--certificate-oidc-issuer", SOURCE)
        self.assertIn("--type slsaprovenance", SOURCE)


if __name__ == "__main__":
    unittest.main()
