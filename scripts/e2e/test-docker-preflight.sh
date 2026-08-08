#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORIGINAL_PATH="${PATH}"
FAKE_BIN="${ROOT_DIR}/scripts/e2e/test-fixtures"

# shellcheck source=docker-preflight.sh
source "${ROOT_DIR}/scripts/e2e/docker-preflight.sh"

run_preflight() (
  PATH="${FAKE_BIN}:${ORIGINAL_PATH}"
  require_real_e2e_docker "test E2E"
)

expect_failure() {
  local expected_message="$1"
  shift
  local output
  local status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  [[ "${status}" -eq 2 ]] || {
    echo "Expected exit 2, got ${status}: ${output}" >&2
    exit 1
  }
  [[ "${output}" == *"${expected_message}"* ]] || {
    echo "Expected message '${expected_message}', got: ${output}" >&2
    exit 1
  }
}

expect_failure "Docker daemon is unavailable" env FAKE_DOCKER_INFO_AVAILABLE=false bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"
expect_failure "must be a positive integer" env REAL_E2E_DOCKER_PROBE_TIMEOUT_SECONDS=0 bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"
expect_failure "invalid memory limit" env FAKE_DOCKER_MEMORY_BYTES=unknown bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"
expect_failure "requires at least" env FAKE_DOCKER_MEMORY_BYTES=1073741824 bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"
expect_failure "must not be lower" env REAL_E2E_MIN_DOCKER_MEMORY_BYTES=4294967296 \
  REAL_E2E_SHARED_DOCKER_MEMORY_BYTES=2147483648 bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"
expect_failure "stopped responding while listing containers" env FAKE_DOCKER_PS_AVAILABLE=false bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"
expect_failure "when other containers are running" env FAKE_DOCKER_MEMORY_BYTES=3221225472 \
  FAKE_DOCKER_RUNNING_COUNT=1 bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"
expect_failure "must be true or false" env REAL_E2E_ALLOW_SHARED_DOCKER=yes bash -c \
  "PATH='${FAKE_BIN}:${ORIGINAL_PATH}'; source '${ROOT_DIR}/scripts/e2e/docker-preflight.sh'; require_real_e2e_docker 'test E2E'"

FAKE_DOCKER_MEMORY_BYTES=3221225472 FAKE_DOCKER_RUNNING_COUNT=1 \
  REAL_E2E_ALLOW_SHARED_DOCKER=true run_preflight
FAKE_DOCKER_MEMORY_BYTES=8589934592 FAKE_DOCKER_RUNNING_COUNT=3 run_preflight

assert_profile_outbox_mode() {
  local profile="$1"
  local expected="$2"
  local output
  output="$(REAL_E2E_ENABLE_OUTBOX="${expected}" docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" \
    --profile "${profile}" config)"
  local values
  values="$(grep -E '^      DEVCIRCLE_OUTBOX_ENABLED:' <<<"${output}" | sort -u)"
  [[ "${values}" == "      DEVCIRCLE_OUTBOX_ENABLED: \"${expected}\"" ]] || {
    echo "Expected ${profile} E2E profile to set Outbox=${expected}, got: ${values}" >&2
    exit 1
  }
}

assert_profile_outbox_mode review false
assert_profile_outbox_mode community true
assert_profile_outbox_mode search-recovery true

timeout_config="$(REAL_E2E_OUTBOX_SEND_TIMEOUT_MS=2345 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile community config)"
timeout_values="$(grep -E '^      DEVCIRCLE_OUTBOX_SEND_TIMEOUT_MS:' <<<"${timeout_config}" | sort -u)"
[[ "${timeout_values}" == '      DEVCIRCLE_OUTBOX_SEND_TIMEOUT_MS: "2345"' ]] || {
  echo "Community E2E Outbox send timeout was not propagated: ${timeout_values}" >&2
  exit 1
}

bash -n "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
grep -q 'stop -t 10 kafka' "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
grep -q 'FAILED' "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
grep -q 'PUBLISHED' "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
grep -q 'verify-community-kafka-recovery.sh' "${ROOT_DIR}/scripts/e2e/run-real-community.sh"

search_services="$(docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" \
  --profile search-recovery config --services | sort)"
expected_search_services="$(printf '%s\n' dc-admin dc-auth dc-content dc-gateway dc-search \
  elasticsearch kafka mysql redis | sort)"
[[ "${search_services}" == "${expected_search_services}" ]] || {
  echo "Unexpected search-recovery services: ${search_services}" >&2
  exit 1
}

search_retry_config="$(REAL_E2E_SEARCH_RETRY_INITIAL_INTERVAL_MS=1234 \
  REAL_E2E_SEARCH_RETRY_MULTIPLIER=1.5 REAL_E2E_SEARCH_RETRY_MAX_INTERVAL_MS=5678 \
  REAL_E2E_SEARCH_RETRY_MAX_ATTEMPTS=42 docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" \
  --profile search-recovery config)"
grep -q 'DEVCIRCLE_KAFKA_RETRY_INITIAL_INTERVAL_MS: "1234"' <<<"${search_retry_config}"
grep -q 'DEVCIRCLE_KAFKA_RETRY_MULTIPLIER: "1.5"' <<<"${search_retry_config}"
grep -q 'DEVCIRCLE_KAFKA_RETRY_MAX_INTERVAL_MS: "5678"' <<<"${search_retry_config}"
grep -q 'DEVCIRCLE_KAFKA_RETRY_MAX_ATTEMPTS: "42"' <<<"${search_retry_config}"

bash -n "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'stop -t 10 elasticsearch' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'FAILED' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'COMPLETED' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q '2020-01-01T00:00:00Z' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'Unable to read Kafka offsets' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
if grep -q 'exec -T kafka /opt/kafka/bin' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"; then
  echo "Kafka container paths must be protected from Git Bash path conversion with sh -c" >&2
  exit 1
fi

echo "Docker preflight tests passed"
