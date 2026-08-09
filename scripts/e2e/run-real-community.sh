#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.e2e.yml"
PROJECT_NAME="${REAL_E2E_PROJECT_NAME:-devcircle-real-community-e2e}"
WAIT_TIMEOUT="${REAL_E2E_WAIT_TIMEOUT:-240}"
PULL_POLICY="${REAL_E2E_PULL_POLICY:-missing}"
BUILD_MODE="${REAL_E2E_BUILD:-true}"
RECOVERY_MODE="${REAL_E2E_COMMUNITY_RECOVERY_MODE:-retry}"
WAIT_ATTEMPTS="${REAL_E2E_RECOVERY_WAIT_ATTEMPTS:-120}"
# shellcheck source=community-auth-profile.sh
source "${ROOT_DIR}/scripts/e2e/community-auth-profile.sh"
configure_community_auth_profile
export REAL_E2E_ENABLE_OUTBOX=true
export REAL_E2E_OUTBOX_SEND_TIMEOUT_MS="${REAL_E2E_OUTBOX_SEND_TIMEOUT_MS:-2000}"
if [[ "${RECOVERY_MODE}" == "dead" ]]; then
  export REAL_E2E_OUTBOX_MAX_ATTEMPTS="${REAL_E2E_OUTBOX_MAX_ATTEMPTS:-2}"
else
  export REAL_E2E_OUTBOX_MAX_ATTEMPTS="${REAL_E2E_OUTBOX_MAX_ATTEMPTS:-8}"
fi
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" "${COMMUNITY_AUTH_COMPOSE_PROFILES[@]}")
# shellcheck source=docker-preflight.sh
source "${ROOT_DIR}/scripts/e2e/docker-preflight.sh"

cleanup() {
  status=$?
  if (( status != 0 )); then
    "${COMPOSE[@]}" logs --no-color --tail=250 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "${status}"
}

command -v npx >/dev/null 2>&1 || { echo "npx is required" >&2; exit 2; }
test -d "${ROOT_DIR}/web/node_modules" || { echo "Run npm ci in web before the real E2E suite" >&2; exit 2; }
require_real_e2e_docker "community E2E"

[[ "${RECOVERY_MODE}" == "retry" || "${RECOVERY_MODE}" == "dead" ]] || {
  echo "REAL_E2E_COMMUNITY_RECOVERY_MODE must be retry or dead" >&2
  exit 2
}

MODULES="dc-identity,dc-user,dc-content-deployment,dc-comment,dc-interaction,dc-recommend,dc-notification,dc-admin,dc-gateway"
if [[ -n "${COMMUNITY_AUTH_MAVEN_MODULE}" ]]; then
  MODULES="${COMMUNITY_AUTH_MAVEN_MODULE},${MODULES}"
fi
if command -v java >/dev/null 2>&1; then
  "${ROOT_DIR}/mvnw" -q -pl "${MODULES}" -am -DskipTests package
elif command -v cmd.exe >/dev/null 2>&1; then
  (cd "${ROOT_DIR}" && cmd.exe /d /c "mvnw.cmd -q -pl ${MODULES} -am -DskipTests package")
else
  echo "Java 21 is required to package the real E2E services" >&2
  exit 2
fi

if [[ "${BUILD_MODE}" == "true" ]]; then
  BUILD_ARGS=(--build)
elif [[ "${BUILD_MODE}" == "false" ]]; then
  BUILD_ARGS=(--no-build)
else
  echo "REAL_E2E_BUILD must be true or false" >&2
  exit 2
fi
trap cleanup EXIT
"${COMPOSE[@]}" down -v --remove-orphans
"${COMPOSE[@]}" up -d "${BUILD_ARGS[@]}" --pull "${PULL_POLICY}" --wait --wait-timeout "${WAIT_TIMEOUT}"

(cd "${ROOT_DIR}/web" && npx playwright test -c playwright.real.config.ts tests-real/community.spec.ts)

community_projection_state=""
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  community_projection_state="$(${COMPOSE[@]} exec -T mysql mysql -uroot -pe2e-root-password --skip-column-names --batch \
    --execute="SELECT CONCAT(
      (SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(followed), 0)) FROM dc_content.recommend_follow_projection), '|',
      (SELECT COUNT(*) FROM dc_notification.notification WHERE type = 'FOLLOW'), '|',
      (SELECT COUNT(*) FROM dc_notification.consumer_dedup WHERE topic = 'follow.changed'), '|',
      (SELECT COUNT(*) FROM dc_content.recommend_consumer_dedup WHERE topic = 'follow.changed' AND status = 'COMPLETED')
    );")"
  [[ "${community_projection_state}" == "1:0|1|2|2" ]] && break
  sleep 1
done

if [[ "${community_projection_state}" != "1:0|1|2|2" ]]; then
  echo "Community projections did not converge after ${WAIT_ATTEMPTS}s: expected follow_projection|notifications|notification_dedup|recommend_dedup=1:0|1|2|2, got ${community_projection_state}" >&2
  exit 1
fi

if [[ "${RECOVERY_MODE}" == "dead" ]]; then
  REAL_E2E_PROJECT_NAME="${PROJECT_NAME}" REAL_E2E_COMPOSE_FILE="${COMPOSE_FILE}" \
    REAL_E2E_AUTH_PROFILE="${COMMUNITY_AUTH_PROFILE}" \
    bash "${ROOT_DIR}/scripts/e2e/verify-community-outbox-dead-recovery.sh"
else
  REAL_E2E_PROJECT_NAME="${PROJECT_NAME}" REAL_E2E_COMPOSE_FILE="${COMPOSE_FILE}" \
    bash "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
fi
