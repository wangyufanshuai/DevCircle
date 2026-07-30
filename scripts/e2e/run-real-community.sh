#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.e2e.yml"
PROJECT_NAME="${REAL_E2E_PROJECT_NAME:-devcircle-real-community-e2e}"
WAIT_TIMEOUT="${REAL_E2E_WAIT_TIMEOUT:-240}"
PULL_POLICY="${REAL_E2E_PULL_POLICY:-missing}"
BUILD_MODE="${REAL_E2E_BUILD:-true}"
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" --profile community)

cleanup() {
  status=$?
  if (( status != 0 )); then
    "${COMPOSE[@]}" logs --no-color --tail=250 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "npx is required" >&2; exit 2; }
test -d "${ROOT_DIR}/web/node_modules" || { echo "Run npm ci in web before the real E2E suite" >&2; exit 2; }

MODULES="dc-auth,dc-user,dc-content,dc-recommend,dc-notification,dc-gateway"
if command -v java >/dev/null 2>&1; then
  "${ROOT_DIR}/mvnw" -q -pl "${MODULES}" -am -DskipTests package
elif command -v cmd.exe >/dev/null 2>&1; then
  (cd "${ROOT_DIR}" && cmd.exe /d /c "mvnw.cmd -q -pl ${MODULES} -am -DskipTests package")
else
  echo "Java 21 is required to package the real E2E services" >&2
  exit 2
fi

"${COMPOSE[@]}" down -v --remove-orphans
if [[ "${BUILD_MODE}" == "true" ]]; then
  BUILD_ARGS=(--build)
elif [[ "${BUILD_MODE}" == "false" ]]; then
  BUILD_ARGS=(--no-build)
else
  echo "REAL_E2E_BUILD must be true or false" >&2
  exit 2
fi
"${COMPOSE[@]}" up -d "${BUILD_ARGS[@]}" --pull "${PULL_POLICY}" --wait --wait-timeout "${WAIT_TIMEOUT}"

(cd "${ROOT_DIR}/web" && npx playwright test -c playwright.real.config.ts tests-real/community.spec.ts)

projection="$(${COMPOSE[@]} exec -T mysql mysql -uroot -pe2e-root-password --database=dc_content --skip-column-names --batch \
  --execute="SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(followed), 0)) FROM recommend_follow_projection;")"
notifications="$(${COMPOSE[@]} exec -T mysql mysql -uroot -pe2e-root-password --database=dc_notification --skip-column-names --batch \
  --execute="SELECT COUNT(*) FROM notification WHERE type = 'FOLLOW';")"
dedup="$(${COMPOSE[@]} exec -T mysql mysql -uroot -pe2e-root-password --database=dc_notification --skip-column-names --batch \
  --execute="SELECT COUNT(*) FROM consumer_dedup WHERE topic = 'follow.changed';")"
recommend_dedup="$(${COMPOSE[@]} exec -T mysql mysql -uroot -pe2e-root-password --database=dc_content --skip-column-names --batch \
  --execute="SELECT COUNT(*) FROM recommend_consumer_dedup WHERE topic = 'follow.changed' AND status = 'COMPLETED';")"

[[ "${projection}" == "1:0" ]] || { echo "unexpected follow projection state: ${projection}" >&2; exit 1; }
[[ "${notifications}" == "1" ]] || { echo "expected one follow notification, got ${notifications}" >&2; exit 1; }
[[ "${dedup}" == "2" ]] || { echo "expected followed and unfollowed events to be deduplicated, got ${dedup}" >&2; exit 1; }
[[ "${recommend_dedup}" == "2" ]] || { echo "expected two completed recommendation projections, got ${recommend_dedup}" >&2; exit 1; }
