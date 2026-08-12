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
        self.assertIn('docker image inspect --format', SOURCE)
        self.assertIn('bash "${VERIFY_IMAGE}" "${digest}"', SOURCE)
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
        verified_asset_steps = {
            "backend image matrix": (
                "Verify signed image and provenance",
                "IMAGE_REF: ghcr.io/wangyufanshuai/devcircle/${{ matrix.image }}",
            ),
            "Notification cutover Web image": (
                "Verify Notification cutover Web signature and provenance",
                "IMAGE_REF: ghcr.io/wangyufanshuai/devcircle/web-notification-cutover",
            ),
            "Notification standalone Web image": (
                "Verify Notification standalone Web signature and provenance",
                "IMAGE_REF: ghcr.io/wangyufanshuai/devcircle/web-notification-standalone",
            ),
        }
        self.assertEqual(len(verified_asset_steps), WORKFLOW.count(invocation))
        for description, (step_name, image_ref) in verified_asset_steps.items():
            with self.subTest(asset=description):
                step_start = WORKFLOW.index(f"- name: {step_name}")
                step_end = WORKFLOW.find("\n      - ", step_start + 1)
                step = WORKFLOW[step_start:step_end if step_end != -1 else None]
                self.assertIn(image_ref, step)
                self.assertIn(invocation, step)
        self.assertLess(WORKFLOW.index("actions/attest-build-provenance@"), WORKFLOW.index(invocation))

    def test_release_maps_each_notification_asset_to_its_approved_sha(self) -> None:
        self.assertIn("*/web-notification-cutover)", SOURCE)
        self.assertIn("*/web-notification-standalone|*/dc-notification)", SOURCE)
        self.assertIn('expected_tag="${cutover_asset_tag}"', SOURCE)
        self.assertIn('expected_tag="${rollback_asset_tag}"', SOURCE)

    def test_mode_selects_only_the_compose_assets_that_are_deployed(self) -> None:
        self.assertIn('mode="$(production_env_value NOTIFICATION_DEPLOY_MODE)"', SOURCE)
        self.assertIn('cutover) compose_files+=', SOURCE)
        self.assertIn('rollback) compose_files+=', SOURCE)
        self.assertNotIn("COMPOSE_OVERLAY_FILE", SOURCE)
        self.assertIn('[[ "${mode}" == "cutover" ]] || continue', SOURCE)


if __name__ == "__main__":
    unittest.main()
