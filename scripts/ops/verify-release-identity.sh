#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.production}}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.prod.yml}"
COSIGN_IDENTITY_REGEXP="${COSIGN_IDENTITY_REGEXP:-^https://github.com/wangyufanshuai/devcircle/.github/workflows/ci.yml@refs/heads/main$}"
COSIGN_OIDC_ISSUER="${COSIGN_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"

fail() {
  echo "Release identity verification failed: $*" >&2
  exit 1
}

[[ "$(uname -s)" == "Linux" ]] || fail "must run on Linux"
command -v cosign >/dev/null 2>&1 || fail "cosign is required"
command -v docker >/dev/null 2>&1 || fail "docker is required"
[[ -f "${ENV_FILE}" ]] || fail "missing ${ENV_FILE}"
[[ -f "${COMPOSE_FILE}" ]] || fail "missing ${COMPOSE_FILE}"

git_sha="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
image_tag="$(sed -n 's/^IMAGE_TAG=//p' "${ENV_FILE}" | tail -n 1 | tr -d '\r')"
[[ "${image_tag}" =~ ^[0-9a-f]{40}$ ]] || fail "IMAGE_TAG must be a lowercase 40-character commit SHA"
[[ "${image_tag}" == "${git_sha}" ]] || fail "IMAGE_TAG does not match checked-out Git SHA"

mapfile -t images < <(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --images \
  | awk '/^ghcr\.io\/wangyufanshuai\/devcircle\// { print }' | sort -u)
(( ${#images[@]} > 0 )) || fail "no immutable DevCircle GHCR images found in rendered Compose config"

for image in "${images[@]}"; do
  echo "Verifying signature and provenance: ${image}"
  cosign verify \
    --certificate-identity-regexp "${COSIGN_IDENTITY_REGEXP}" \
    --certificate-oidc-issuer "${COSIGN_OIDC_ISSUER}" \
    "${image}" >/dev/null || fail "signature verification failed for ${image}"
  cosign verify-attestation \
    --type slsaprovenance \
    --certificate-identity-regexp "${COSIGN_IDENTITY_REGEXP}" \
    --certificate-oidc-issuer "${COSIGN_OIDC_ISSUER}" \
    "${image}" >/dev/null || fail "SLSA provenance verification failed for ${image}"
done

echo "Release identity verified for ${#images[@]} images at ${image_tag}"
