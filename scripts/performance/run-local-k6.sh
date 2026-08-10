#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_NAME="${PERF_PROJECT_NAME:-devcircle-local-performance}"
WAIT_TIMEOUT="${PERF_WAIT_TIMEOUT:-300}"
PULL_POLICY="${PERF_PULL_POLICY:-never}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.55.0}"
BUILD_MODE="${PERF_BUILD:-true}"
KEEP_ON_FAILURE="${PERF_KEEP_ON_FAILURE:-false}"
POST_ID="${POST_ID:-1001}"
READ_VUS="${READ_VUS:-40}"
WRITE_VUS="${WRITE_VUS:-10}"
DURATION="${DURATION:-1m}"
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile performance)

export REAL_E2E_ENABLE_OUTBOX=false
export REAL_E2E_ENABLE_THEME_SEED=true

cleanup() {
  status=$?
  if (( status != 0 )); then
    "${COMPOSE[@]}" logs --no-color --tail=200 || true
    if [[ "${KEEP_ON_FAILURE}" == "true" ]]; then
      echo "Keeping failed performance environment: ${PROJECT_NAME}" >&2
      exit "${status}"
    fi
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT

command -v node >/dev/null 2>&1 || { echo "Node.js is required" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }
docker image inspect "${K6_IMAGE}" >/dev/null 2>&1 || {
  echo "Missing ${K6_IMAGE}; pull the pinned load-test image before running" >&2
  exit 2
}

if [[ "${BUILD_MODE}" == "true" ]]; then
  MODULES="dc-identity,dc-content-deployment,dc-discovery,dc-recommend,dc-gateway"
  if command -v java >/dev/null 2>&1; then
    "${ROOT_DIR}/mvnw" -q -pl "${MODULES}" -am -DskipTests package
  elif command -v cmd.exe >/dev/null 2>&1; then
    (cd "${ROOT_DIR}" && cmd.exe /d /c "mvnw.cmd -q -pl ${MODULES} -am -DskipTests package")
  else
    echo "Java 21 is required to package the performance services" >&2
    exit 2
  fi
  build_args=(--build)
elif [[ "${BUILD_MODE}" == "false" ]]; then
  build_args=(--no-build)
else
  echo "PERF_BUILD must be true or false" >&2
  exit 2
fi

"${COMPOSE[@]}" down -v --remove-orphans
"${COMPOSE[@]}" up -d "${build_args[@]}" --pull "${PULL_POLICY}" --wait --wait-timeout "${WAIT_TIMEOUT}"

registration_id="$(node -e "console.log(Date.now().toString(36)+'-'+require('crypto').randomUUID())" | tr -d '\r\n')"
tokens=()
for index in $(seq 1 "${WRITE_VUS}"); do
  registration_key="perf-register-${registration_id}-${index}"
  result="$(curl -sS -X POST http://127.0.0.1:28080/api/auth/register \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${registration_key}" \
    --data "{\"email\":\"perf-${registration_id}-${index}@example.test\",\"password\":\"OrbitReader2026!\",\"displayName\":\"Load Reader ${index}\"}" \
    --write-out $'\n%{http_code}')"
  status="${result##*$'\n'}"
  response="${result%$'\n'*}"
  if [[ "${status}" != "200" ]]; then
    echo "Performance user registration ${index} failed with HTTP ${status}: ${response}" >&2
    exit 1
  fi
  token="$(node -e "const body=JSON.parse(process.argv[1]); if(!body.data?.accessToken) process.exit(2); process.stdout.write(body.data.accessToken)" "${response}")"
  tokens+=("${token}")
done
token_csv="$(IFS=,; echo "${tokens[*]}")"

"${COMPOSE[@]}" exec -T dc-discovery curl -fsS -X POST http://localhost:8203/api/search/admin/reindex >/dev/null

# Initialize the recommendation datasource and JIT path before the timed steady-state window.
# Cold-start behavior is validated by Compose health checks and reported separately.
for _ in $(seq 1 5); do
  curl -fsS "http://127.0.0.1:28080/api/recommend/feed?scene=mixed&size=12" \
    -H "Authorization: Bearer ${tokens[0]}" >/dev/null
done

if command -v cygpath >/dev/null 2>&1; then
  script_mount="$(cygpath -w "${ROOT_DIR}/tests/k6" | tr '\\' '/')"
else
  script_mount="${ROOT_DIR}/tests/k6"
fi
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${script_mount}:/scripts:ro" \
  -e BASE_URL=http://host.docker.internal:28080 \
  -e POST_ID="${POST_ID}" \
  -e TOKENS="${token_csv}" \
  -e READ_VUS="${READ_VUS}" \
  -e WRITE_VUS="${WRITE_VUS}" \
  -e DURATION="${DURATION}" \
  -e RUN_ID="local-${registration_id}" \
  "${K6_IMAGE}" run /scripts/mysticverse-smoke.js
