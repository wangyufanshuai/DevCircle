#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.e2e.yml"
PROJECT_NAME="${REAL_E2E_PROJECT_NAME:-devcircle-real-review-e2e}"
WAIT_TIMEOUT="${REAL_E2E_WAIT_TIMEOUT:-180}"
ADMIN_EMAIL="real-review-admin@example.test"
ADMIN_PASSWORD="OrbitAdmin2026!"
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" --profile review)

cleanup() {
  status=$?
  if (( status != 0 )); then
    "${COMPOSE[@]}" logs --no-color --tail=200 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }
command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "npx is required" >&2; exit 2; }
test -d "${ROOT_DIR}/web/node_modules" || { echo "Run npm ci in web before the real E2E suite" >&2; exit 2; }

if command -v java >/dev/null 2>&1; then
  "${ROOT_DIR}/mvnw" -q -pl dc-auth,dc-content,dc-admin,dc-gateway -am -DskipTests package
elif command -v cmd.exe >/dev/null 2>&1; then
  (cd "${ROOT_DIR}" && cmd.exe /d /c "mvnw.cmd -q -pl dc-auth,dc-content,dc-admin,dc-gateway -am -DskipTests package")
else
  echo "Java 21 is required to package the real E2E services" >&2
  exit 2
fi

"${COMPOSE[@]}" up -d --build --wait --wait-timeout "${WAIT_TIMEOUT}"

curl --fail --silent --show-error --output /dev/null \
  --request POST 'http://127.0.0.1:28080/api/auth/register' \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: bootstrap-admin-${PROJECT_NAME}" \
  --data "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\",\"displayName\":\"真实审核员\"}"

"${COMPOSE[@]}" exec -T mysql mysql -uroot -pe2e-root-password --database=dc_auth --execute="
  INSERT IGNORE INTO user_role(user_id, role_id)
  SELECT ua.id, r.id FROM user_account ua JOIN role r ON r.code = 'ADMIN'
  WHERE ua.email = '${ADMIN_EMAIL}';
"

admin_roles="$("${COMPOSE[@]}" exec -T mysql mysql -uroot -pe2e-root-password --database=dc_auth --skip-column-names --batch --execute="
  SELECT COUNT(*) FROM user_role ur
  JOIN user_account ua ON ua.id = ur.user_id
  JOIN role r ON r.id = ur.role_id
  WHERE ua.email = '${ADMIN_EMAIL}' AND r.code = 'ADMIN';
")"
test "${admin_roles}" = "1" || { echo "Failed to prepare the real review administrator" >&2; exit 1; }

(cd "${ROOT_DIR}/web" && REAL_REVIEW_ADMIN_EMAIL="${ADMIN_EMAIL}" REAL_REVIEW_ADMIN_PASSWORD="${ADMIN_PASSWORD}" \
  npx playwright test -c playwright.real.config.ts tests-real/review.spec.ts)
