#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.production}}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.prod.yml}"
VERIFY_IMAGE="${ROOT_DIR}/scripts/ops/verify-image-identity.sh"
# shellcheck source=scripts/ops/production-env.sh
source "${ROOT_DIR}/scripts/ops/production-env.sh"

fail() {
  echo "Release identity verification failed: $*" >&2
  exit 1
}

[[ "$(uname -s)" == "Linux" ]] || fail "must run on Linux"
command -v cosign >/dev/null 2>&1 || fail "cosign is required"
command -v docker >/dev/null 2>&1 || fail "docker is required"
[[ -f "${ENV_FILE}" ]] || fail "missing ${ENV_FILE}"
[[ -f "${COMPOSE_FILE}" ]] || fail "missing ${COMPOSE_FILE}"
compose_files=(-f "${COMPOSE_FILE}")
[[ -f "${VERIFY_IMAGE}" ]] || fail "missing ${VERIFY_IMAGE}"
load_production_env "${ENV_FILE}"
mode="$(production_env_value NOTIFICATION_DEPLOY_MODE)"
case "${mode}" in
  standalone) ;;
  cutover) compose_files+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.notification-identity-cutover.yml") ;;
  rollback) compose_files+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-notification-rollback.yml") ;;
  *) fail "NOTIFICATION_DEPLOY_MODE must be standalone, cutover, or rollback" ;;
esac

git_sha="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
image_tag="$(production_env_value IMAGE_TAG)"
cutover_asset_tag="$(production_env_value CUTOVER_ASSET_TAG)"
rollback_asset_tag="$(production_env_value ROLLBACK_ASSET_TAG)"
[[ "${image_tag}" =~ ^[0-9a-f]{40}$ ]] || fail "IMAGE_TAG must be a lowercase 40-character commit SHA"
if [[ "${mode}" != "rollback" ]]; then
  [[ "${image_tag}" == "${git_sha}" ]] || fail "IMAGE_TAG does not match checked-out Git SHA"
fi

mapfile -t images < <(production_compose "${ENV_FILE}" "${compose_files[@]}" config --images \
  | awk '/^ghcr\.io\/wangyufanshuai\/devcircle\// { print }' | sort -u)
(( ${#images[@]} > 0 )) || fail "no immutable DevCircle GHCR images found in rendered Compose config"

for image in "${images[@]}"; do
  repository="${image%:*}"
  actual_tag="${image##*:}"
  case "${repository}" in
    */web-notification-cutover)
      [[ "${mode}" == "cutover" ]] || continue
      expected_tag="${cutover_asset_tag}"
      ;;
    */web-notification-standalone|*/dc-notification)
      if [[ "${mode}" == "rollback" ]]; then
        expected_tag="${rollback_asset_tag}"
      elif [[ "${mode}" == "cutover" && "${repository}" == */dc-notification ]]; then
        continue
      else
        expected_tag="${image_tag}"
      fi
      ;;
    *) expected_tag="${image_tag}" ;;
  esac
  [[ "${expected_tag}" =~ ^[0-9a-f]{40}$ ]] || fail "missing immutable asset tag for ${repository}"
  [[ "${actual_tag}" == "${expected_tag}" ]] || fail "${repository} tag does not match its approved release asset SHA"

  digest="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${image}" \
    | awk -v repository="${repository}@sha256:" 'index($0, repository) == 1 { print; exit }')"
  [[ "${digest}" =~ ^ghcr\.io/wangyufanshuai/devcircle/[a-z0-9-]+@sha256:[0-9a-f]{64}$ ]] || \
    fail "no pulled immutable RepoDigest found for ${image}"
  echo "Verifying signature and provenance: ${digest}"
  bash "${VERIFY_IMAGE}" "${digest}" || fail "identity verification failed for ${digest}"
done

echo "Release identity verified for ${#images[@]} images at ${image_tag}"
