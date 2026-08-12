#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/ops/production-env.sh
source "${ROOT_DIR}/scripts/ops/production-env.sh"

GOOD_IMAGE=1111111111111111111111111111111111111111
GOOD_CUTOVER=2222222222222222222222222222222222222222
GOOD_ROLLBACK=3333333333333333333333333333333333333333
ATTACK_IMAGE=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
ATTACK_CUTOVER=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
ATTACK_ROLLBACK=cccccccccccccccccccccccccccccccccccccccc
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

write_env() {
  local mode="$1" output="$2"
  sed \
    -e "s/^IMAGE_TAG=.*/IMAGE_TAG=${GOOD_IMAGE}/" \
    -e "s/^NOTIFICATION_DEPLOY_MODE=.*/NOTIFICATION_DEPLOY_MODE=${mode}/" \
    -e "s/^CUTOVER_ASSET_TAG=.*/CUTOVER_ASSET_TAG=${GOOD_CUTOVER}/" \
    -e "s/^ROLLBACK_ASSET_TAG=.*/ROLLBACK_ASSET_TAG=${GOOD_ROLLBACK}/" \
    -e 's/^LEGACY_NOTIFICATION_ROLLBACK=.*/LEGACY_NOTIFICATION_ROLLBACK=true/' \
    "${ROOT_DIR}/.env.production.example" > "${output}"
}

render_images() {
  local env_file="$1"
  shift
  IMAGE_TAG="${ATTACK_IMAGE}" \
  CUTOVER_ASSET_TAG="${ATTACK_CUTOVER}" \
  ROLLBACK_ASSET_TAG="${ATTACK_ROLLBACK}" \
    production_compose "${env_file}" "$@" config --images
}

assert_uses_file_not_parent() {
  local rendered="$1" required="$2"
  [[ "${rendered}" == *":${required}"* ]] || {
    echo "Expected rendered images to use env-file tag ${required}" >&2
    exit 1
  }
  [[ "${rendered}" != *":${ATTACK_IMAGE}"* ]]
  [[ "${rendered}" != *":${ATTACK_CUTOVER}"* ]]
  [[ "${rendered}" != *":${ATTACK_ROLLBACK}"* ]]
}

base_env="${TEMP_DIR}/base.env"
cutover_env="${TEMP_DIR}/cutover.env"
rollback_env="${TEMP_DIR}/rollback.env"
write_env standalone "${base_env}"
write_env cutover "${cutover_env}"
write_env rollback "${rollback_env}"

base_images="$(render_images "${base_env}" -f "${ROOT_DIR}/docker-compose.prod.yml")"
cutover_images="$(render_images "${cutover_env}" -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/deploy/compose/docker-compose.notification-identity-cutover.yml")"
rollback_images="$(render_images "${rollback_env}" -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-notification-rollback.yml")"

assert_uses_file_not_parent "${base_images}" "${GOOD_IMAGE}"
assert_uses_file_not_parent "${cutover_images}" "${GOOD_CUTOVER}"
assert_uses_file_not_parent "${rollback_images}" "${GOOD_ROLLBACK}"

dangerous_env="${TEMP_DIR}/dangerous.env"
printf 'IMAGE_TAG=%s\nDOCKER_HOST=tcp://attacker.invalid:2375\n' "${GOOD_IMAGE}" > "${dangerous_env}"
if load_production_env "${dangerous_env}" >/dev/null 2>&1; then
  echo "production env loader accepted a Docker runtime control key" >&2
  exit 1
fi

echo "Production Compose env precedence checks passed"
