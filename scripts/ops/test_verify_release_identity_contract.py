from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "scripts" / "ops" / "verify-release-identity.sh").read_text(encoding="utf-8")
IMAGE_SOURCE = (ROOT / "scripts" / "ops" / "verify-image-identity.sh").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")


class VerifyReleaseIdentityContractTest(unittest.TestCase):
    def test_is_linux_only_and_requires_immutable_sha(self) -> None:
        self.assertIn('[[ "$(uname -s)" == "Linux" ]]', SOURCE)
        self.assertIn('IMAGE_TAG must be a lowercase 40-character commit SHA', SOURCE)
        self.assertIn('IMAGE_TAG does not match checked-out Git SHA', SOURCE)

    def test_requires_signature_and_provenance_verification(self) -> None:
        self.assertIn('bash "${VERIFY_IMAGE}" "${image}"', SOURCE)
        self.assertIn("cosign verify \\", IMAGE_SOURCE)
        self.assertIn("cosign verify-attestation \\", IMAGE_SOURCE)
        self.assertIn("--certificate-oidc-issuer", IMAGE_SOURCE)
        self.assertIn("--type slsaprovenance", IMAGE_SOURCE)

    def test_single_image_verifier_rejects_tags_and_other_registries(self) -> None:
        self.assertIn("immutable DevCircle GHCR digest", IMAGE_SOURCE)
        self.assertIn("@sha256:[0-9a-f]{64}", IMAGE_SOURCE)

    def test_registry_propagation_retry_is_bounded_and_still_fails_closed(self) -> None:
        self.assertIn('VERIFY_ATTEMPTS="${VERIFY_ATTEMPTS:-5}"', IMAGE_SOURCE)
        self.assertIn("VERIFY_ATTEMPTS <= 10", IMAGE_SOURCE)
        self.assertIn("attempt == VERIFY_ATTEMPTS", IMAGE_SOURCE)
        self.assertIn('fail "${description} failed for ${IMAGE}', IMAGE_SOURCE)

    def test_ci_verifies_each_backend_and_web_digest_after_attestation(self) -> None:
        invocation = 'bash scripts/ops/verify-image-identity.sh "${IMAGE_REF}@${IMAGE_DIGEST}"'
        self.assertEqual(2, WORKFLOW.count(invocation))
        self.assertLess(WORKFLOW.index("actions/attest-build-provenance@"), WORKFLOW.index(invocation))


if __name__ == "__main__":
    unittest.main()
