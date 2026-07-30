#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.e2e.yml"
PROJECT_NAME="${REAL_E2E_PROJECT_NAME:-devcircle-real-auth-e2e}"
WAIT_TIMEOUT="${REAL_E2E_WAIT_TIMEOUT:-180}"
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}")

cleanup() {
  status=$?
  if (( status != 0 )); then
    "${COMPOSE[@]}" logs --no-color --tail=200 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "npx is required" >&2; exit 2; }
test -d "${ROOT_DIR}/web/node_modules" || { echo "Run npm ci in web before the real E2E suite" >&2; exit 2; }

if command -v java >/dev/null 2>&1; then
  "${ROOT_DIR}/mvnw" -q -pl dc-auth,dc-gateway -am -DskipTests package
elif command -v cmd.exe >/dev/null 2>&1; then
  (cd "${ROOT_DIR}" && cmd.exe /d /c "mvnw.cmd -q -pl dc-auth,dc-gateway -am -DskipTests package")
else
  echo "Java 21 is required to package the real E2E services" >&2
  exit 2
fi
"${COMPOSE[@]}" up -d --build --wait --wait-timeout "${WAIT_TIMEOUT}"

(cd "${ROOT_DIR}/web" && npx playwright test -c playwright.real.config.ts tests-real/auth.spec.ts)
