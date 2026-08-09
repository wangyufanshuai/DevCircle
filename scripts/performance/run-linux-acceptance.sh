#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-${ROOT_DIR}/artifacts/linux-acceptance}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
READ_VUS="${READ_VUS:-40}"
WRITE_VUS="${WRITE_VUS:-10}"
DURATION="${DURATION:-3m}"
MIN_CPU="${MIN_CPU:-4}"
MIN_MEMORY_GIB="${MIN_MEMORY_GIB:-15}"
MIN_DISK_GIB="${MIN_DISK_GIB:-180}"
MIN_AVAILABILITY="${MIN_AVAILABILITY:-0.99}"
MAX_READ_P95_SECONDS="${MAX_READ_P95_SECONDS:-0.3}"
MAX_WRITE_P95_SECONDS="${MAX_WRITE_P95_SECONDS:-0.6}"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

require_value() {
  local name="$1"
  local value="${!name:-}"
  test -n "${value}" || { echo "${name} is required" >&2; exit 2; }
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "$1 is required" >&2; exit 2; }
}

test "$(uname -s)" = "Linux" || {
  echo "Formal acceptance must run on the target Linux host" >&2
  exit 2
}
test -f "${ENV_FILE}" || { echo "Missing ${ENV_FILE}" >&2; exit 2; }
require_command docker
require_command curl
require_command git
require_command k6
require_command sha256sum
require_command jq
require_value POST_ID
require_value TOKENS
require_value ADMIN_TOKEN

bash "${ROOT_DIR}/scripts/ops/validate-production-env.sh" "${ENV_FILE}" >/dev/null
"${COMPOSE[@]}" config --quiet

cpu_count="$(nproc)"
memory_gib="$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 / 1024 ))"
disk_gib="$(( $(df -Pk "${ROOT_DIR}" | awk 'NR==2 {print $2}') / 1024 / 1024 ))"
(( cpu_count >= MIN_CPU )) || { echo "CPU preflight failed: ${cpu_count} < ${MIN_CPU}" >&2; exit 2; }
(( memory_gib >= MIN_MEMORY_GIB )) || { echo "Memory preflight failed: ${memory_gib}GiB < ${MIN_MEMORY_GIB}GiB" >&2; exit 2; }
(( disk_gib >= MIN_DISK_GIB )) || { echo "Disk preflight failed: ${disk_gib}GiB < ${MIN_DISK_GIB}GiB" >&2; exit 2; }

git_sha="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
image_tag="$(sed -n 's/^IMAGE_TAG=//p' "${ENV_FILE}" | tail -n 1 | tr -d '\r')"
test "${image_tag}" = "${git_sha}" || {
  echo "IMAGE_TAG ${image_tag} does not match checked-out Git SHA ${git_sha}" >&2
  exit 2
}
test -z "$(git -C "${ROOT_DIR}" status --porcelain --untracked-files=normal)" || {
  echo "Formal acceptance requires a clean worktree" >&2
  exit 2
}

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_id="linux-${stamp}-${git_sha:0:12}"
evidence_dir="${EVIDENCE_ROOT}/${stamp}-${git_sha:0:12}"
test ! -e "${evidence_dir}" || { echo "Evidence directory already exists: ${evidence_dir}" >&2; exit 2; }
umask 077
mkdir -p "${evidence_dir}"

cleanup() {
  status=$?
  printf '%s\n' "${status}" > "${evidence_dir}/runner-exit-code.txt"
  (cd "${evidence_dir}" && sha256sum ./*.json ./*.txt > SHA256SUMS 2>/dev/null) || true
  exit "${status}"
}
trap cleanup EXIT

cat > "${evidence_dir}/host.json" <<EOF
{
  "os": "Linux",
  "capturedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "gitSha": "${git_sha}",
  "imageTag": "${image_tag}",
  "cpuCount": ${cpu_count},
  "memoryGiB": ${memory_gib},
  "diskGiB": ${disk_gib},
  "readVus": ${READ_VUS},
  "writeVus": ${WRITE_VUS},
  "duration": "${DURATION}",
  "baseUrl": "${BASE_URL}",
  "thresholds": {
    "minimumAvailability": ${MIN_AVAILABILITY},
    "maximumReadP95Seconds": ${MAX_READ_P95_SECONDS},
    "maximumWriteP95Seconds": ${MAX_WRITE_P95_SECONDS}
  }
}
EOF

uname -a > "${evidence_dir}/uname.txt"
lsblk -o NAME,TYPE,SIZE,ROTA,MOUNTPOINTS > "${evidence_dir}/block-devices.txt"
docker version > "${evidence_dir}/docker-version.txt"
"${COMPOSE[@]}" ps --format json > "${evidence_dir}/compose-before.json"
docker stats --no-stream > "${evidence_dir}/docker-stats-before.txt"
curl --fail --silent --show-error "${BASE_URL}/actuator/health/readiness" > "${evidence_dir}/gateway-readiness-before.json"

set +e
BASE_URL="${BASE_URL}" POST_ID="${POST_ID}" TOKENS="${TOKENS}" RUN_ID="${run_id}" \
READ_VUS="${READ_VUS}" WRITE_VUS="${WRITE_VUS}" DURATION="${DURATION}" \
  k6 run --summary-export "${evidence_dir}/k6-summary.json" "${ROOT_DIR}/tests/k6/mysticverse-smoke.js" \
  > "${evidence_dir}/k6-output.txt" 2>&1
k6_status=$?
set -e

"${COMPOSE[@]}" ps --format json > "${evidence_dir}/compose-after.json"
docker stats --no-stream > "${evidence_dir}/docker-stats-after.txt"
curl --fail --silent --show-error "${BASE_URL}/actuator/health/readiness" > "${evidence_dir}/gateway-readiness-after.json"
curl --fail --silent --show-error "${BASE_URL}/api/admin/ops/health" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" > "${evidence_dir}/outbox-fleet-health.json"

prometheus_query() {
  local name="$1"
  local query="$2"
  "${COMPOSE[@]}" exec -T prometheus promtool query instant http://localhost:9090 "${query}" \
    > "${evidence_dir}/prometheus-${name}.txt"
}

prometheus_scalar() {
  local name="$1"
  local file="${evidence_dir}/prometheus-${name}.txt"
  local value
  value="$(awk '
    BEGIN {
      numeric = "^[-+]?[0-9]+([.][0-9]*)?([eE][-+]?[0-9]+)?$|^[-+]?([.][0-9]+)([eE][-+]?[0-9]+)?$"
    }
    NF {
      token = $NF
      if (token == "NaN" || token == "nan" || token == "+Inf" || token == "-Inf" || token == "Inf") {
        invalid = 1
      } else if (token ~ numeric) {
        value = token
        count++
      }
    }
    END {
      if (invalid || count != 1) exit 1
      print value
    }
  ' "${file}")" || {
    echo "Prometheus query ${name} did not return exactly one finite numeric value" >&2
    exit 1
  }
  printf '%s' "${value}"
}

assert_metric_at_least() {
  local name="$1"
  local value="$2"
  local threshold="$3"
  awk -v value="${value}" -v threshold="${threshold}" \
    'BEGIN { exit !(value == value && value + 0 >= threshold + 0) }' || {
      echo "Prometheus threshold failed: ${name}=${value}, expected >= ${threshold}" >&2
      exit 1
    }
}

assert_metric_at_most() {
  local name="$1"
  local value="$2"
  local threshold="$3"
  awk -v value="${value}" -v threshold="${threshold}" \
    'BEGIN { exit !(value == value && value + 0 <= threshold + 0) }' || {
      echo "Prometheus threshold failed: ${name}=${value}, expected <= ${threshold}" >&2
      exit 1
    }
}

validate_outbox_health() {
  jq -e '
    .data as $data |
    (.code == "OK")
    and ($data.outboxScope == "FLEET")
    and ($data.outboxComplete == true)
    and (($data.outboxDead // -1) == 0)
    and (($data.outboxBlockedByDead // -1) == 0)
    and (($data.unavailableOutboxSources // ["missing"]) | length == 0)
    and (($data.outboxSources | type) == "array")
    and (($data.outboxSources | length) > 0)
    and ([$data.outboxSources[] | .status] | all(. == "UP"))
  ' "${evidence_dir}/outbox-fleet-health.json" >/dev/null || {
    echo "Outbox fleet health is incomplete or unhealthy; see outbox-fleet-health.json" >&2
    exit 1
  }
}

prometheus_query availability_5m \
  '1 - (sum(rate(http_server_requests_seconds_count{job="devcircle",service="dc-gateway",status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{job="devcircle",service="dc-gateway"}[5m])), 0.000001))'
prometheus_query read_p95_5m \
  'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="devcircle",service="dc-gateway",method=~"GET|HEAD|OPTIONS"}[5m])))'
prometheus_query write_p95_5m \
  'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="devcircle",service="dc-gateway",method=~"POST|PUT|PATCH|DELETE"}[5m])))'
prometheus_query outbox_complete 'devcircle_outbox_fleet_complete{service="dc-admin"}'
prometheus_query outbox_dead 'devcircle_outbox_fleet_dead{service="dc-admin"}'
prometheus_query outbox_blocked 'devcircle_outbox_fleet_blocked{service="dc-admin"}'
prometheus_query outbox_backlog 'devcircle_outbox_fleet_backlog{service="dc-admin"}'

availability="$(prometheus_scalar availability_5m)"
read_p95_seconds="$(prometheus_scalar read_p95_5m)"
write_p95_seconds="$(prometheus_scalar write_p95_5m)"
outbox_complete="$(prometheus_scalar outbox_complete)"
outbox_dead="$(prometheus_scalar outbox_dead)"
outbox_blocked="$(prometheus_scalar outbox_blocked)"
outbox_backlog="$(prometheus_scalar outbox_backlog)"

assert_metric_at_least availability_5m "${availability}" "${MIN_AVAILABILITY}"
assert_metric_at_most read_p95_5m "${read_p95_seconds}" "${MAX_READ_P95_SECONDS}"
assert_metric_at_most write_p95_5m "${write_p95_seconds}" "${MAX_WRITE_P95_SECONDS}"
assert_metric_at_least outbox_complete "${outbox_complete}" 1
assert_metric_at_most outbox_dead "${outbox_dead}" 0
assert_metric_at_most outbox_blocked "${outbox_blocked}" 0
validate_outbox_health

cat > "${evidence_dir}/prometheus-summary.json" <<EOF
{
  "availability5m": ${availability},
  "readP95Seconds5m": ${read_p95_seconds},
  "writeP95Seconds5m": ${write_p95_seconds},
  "outboxComplete": ${outbox_complete},
  "outboxDead": ${outbox_dead},
  "outboxBlocked": ${outbox_blocked},
  "outboxBacklog": ${outbox_backlog},
  "thresholds": {
    "minimumAvailability": ${MIN_AVAILABILITY},
    "maximumReadP95Seconds": ${MAX_READ_P95_SECONDS},
    "maximumWriteP95Seconds": ${MAX_WRITE_P95_SECONDS}
  }
}
EOF

printf '%s\n' "${k6_status}" > "${evidence_dir}/k6-exit-code.txt"
if (( k6_status != 0 )); then
  echo "k6 thresholds failed; evidence retained in ${evidence_dir}" >&2
  exit "${k6_status}"
fi

echo "Linux acceptance evidence completed: ${evidence_dir}"
