#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.production}}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.prod.yml}"
VERIFY_IMAGE="${ROOT_DIR}/scripts/ops/verify-image-identity.sh"

fail() {
  echo "Release identity verification failed: $*" >&2
  exit 1
}

[[ "$(uname -s)" == "Linux" ]] || fail "must run on Linux"
command -v cosign >/dev/null 2>&1 || fail "cosign is required"
command -v docker >/dev/null 2>&1 || fail "docker is required"
[[ -f "${ENV_FILE}" ]] || fail "missing ${ENV_FILE}"
[[ -f "${COMPOSE_FILE}" ]] || fail "missing ${COMPOSE_FILE}"
[[ -f "${VERIFY_IMAGE}" ]] || fail "missing ${VERIFY_IMAGE}"

git_sha="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
image_tag="$(sed -n 's/^IMAGE_TAG=//p' "${ENV_FILE}" | tail -n 1 | tr -d '\r')"
[[ "${image_tag}" =~ ^[0-9a-f]{40}$ ]] || fail "IMAGE_TAG must be a lowercase 40-character commit SHA"
[[ "${image_tag}" == "${git_sha}" ]] || fail "IMAGE_TAG does not match checked-out Git SHA"

mapfile -t images < <(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --images \
  | awk '/^ghcr\.io\/wangyufanshuai\/devcircle\// { print }' | sort -u)
(( ${#images[@]} > 0 )) || fail "no immutable DevCircle GHCR images found in rendered Compose config"

for image in "${images[@]}"; do
  echo "Verifying signature and provenance: ${image}"
  bash "${VERIFY_IMAGE}" "${image}" || fail "identity verification failed for ${image}"
done

echo "Release identity verified for ${#images[@]} images at ${image_tag}"
