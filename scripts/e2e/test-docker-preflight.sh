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

default_auth_services="$(docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" config --services | sort)"
expected_default_auth_services="$(printf '%s\n' dc-gateway dc-identity mysql redis | sort)"
[[ "${default_auth_services}" == "${expected_default_auth_services}" ]] || {
  echo "Unexpected default authentication services: ${default_auth_services}" >&2
  exit 1
}

legacy_auth_config="$(REAL_E2E_AUTH_URI=http://dc-auth:8101 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile legacy-auth config)"
grep -q '^  dc-auth:$' <<<"${legacy_auth_config}"
grep -q 'DC_AUTH_URI: http://dc-auth:8101' <<<"${legacy_auth_config}"
bash -n "${ROOT_DIR}/scripts/e2e/run-real-auth.sh"
grep -q -- '-pl dc-identity,dc-gateway' "${ROOT_DIR}/scripts/e2e/run-real-auth.sh"
if grep -q -- '-pl dc-auth,dc-gateway' "${ROOT_DIR}/scripts/e2e/run-real-auth.sh"; then
  echo "Authentication E2E must build the embedded identity deployment by default" >&2
  exit 1
fi

timeout_config="$(REAL_E2E_OUTBOX_SEND_TIMEOUT_MS=2345 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile community config)"
timeout_values="$(grep -E '^      DEVCIRCLE_OUTBOX_SEND_TIMEOUT_MS:' <<<"${timeout_config}" | sort -u)"
[[ "${timeout_values}" == '      DEVCIRCLE_OUTBOX_SEND_TIMEOUT_MS: "2345"' ]] || {
  echo "Community E2E Outbox send timeout was not propagated: ${timeout_values}" >&2
  exit 1
}

retry_config="$(REAL_E2E_OUTBOX_BATCH_SIZE=23 REAL_E2E_OUTBOX_MAX_ATTEMPTS=3 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile community config)"
grep -q 'DEVCIRCLE_OUTBOX_BATCH_SIZE: "23"' <<<"${retry_config}"
grep -q 'DEVCIRCLE_OUTBOX_MAX_ATTEMPTS: "3"' <<<"${retry_config}"

pool_config="$(REAL_E2E_DB_POOL_MAX_SIZE=3 REAL_E2E_DB_POOL_MIN_IDLE=0 \
  REAL_E2E_DB_CONNECTION_TIMEOUT_MS=2345 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile community config)"
grep -q 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "3"' <<<"${pool_config}"
grep -q 'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: "0"' <<<"${pool_config}"
grep -q 'SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT: "2345"' <<<"${pool_config}"

bash -n "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
bash -n "${ROOT_DIR}/scripts/e2e/verify-community-outbox-dead-recovery.sh"
bash -n "${ROOT_DIR}/scripts/e2e/run-real-community.sh"
bash -n "${ROOT_DIR}/scripts/e2e/community-auth-profile.sh"
grep -q 'dc-content-deployment' "${ROOT_DIR}/scripts/e2e/run-real-content.sh"
grep -q 'dc-content-deployment' "${ROOT_DIR}/scripts/e2e/run-real-review.sh"
grep -q 'dc-content-deployment' "${ROOT_DIR}/scripts/e2e/run-real-community.sh"
grep -q 'dc-content-deployment' "${ROOT_DIR}/scripts/performance/run-local-k6.sh"
# shellcheck source=community-auth-profile.sh
source "${ROOT_DIR}/scripts/e2e/community-auth-profile.sh"
unset REAL_E2E_AUTH_PROFILE
configure_community_auth_profile
[[ "${COMMUNITY_AUTH_SERVICE}:${COMMUNITY_AUTH_PORT}:${COMMUNITY_AUTH_URI}" == \
  "dc-identity:8201:http://dc-identity:8201" ]]
[[ "${COMMUNITY_AUTH_COMPOSE_PROFILES[*]}" == "--profile community" ]]
[[ "$(community_outbox_runtime_sources | head -n 1)" == \
  "dc-identity:8201:dc-auth:/internal/ops/identity/outbox/auth/status" ]]
grep -qx 'dc-identity:8201:dc-user:/internal/ops/identity/outbox/user/status' < <(community_outbox_runtime_sources)
grep -qx 'dc-content-aggregate:8202:dc-content:/internal/ops/outbox/status' < <(community_outbox_runtime_sources)
REAL_E2E_AUTH_PROFILE=legacy-auth
configure_community_auth_profile
[[ "${COMMUNITY_AUTH_SERVICE}:${COMMUNITY_AUTH_PORT}:${COMMUNITY_AUTH_URI}" == \
  "dc-auth:8101:http://dc-auth:8101" ]]
[[ "${COMMUNITY_AUTH_COMPOSE_PROFILES[*]}" == "--profile community --profile legacy-auth" ]]
[[ "${COMMUNITY_AUTH_MAVEN_MODULE}" == "dc-auth" ]]
[[ "$(community_outbox_runtime_sources | head -n 1)" == \
  "dc-auth:8101:dc-auth:/internal/ops/outbox/status" ]]
grep -qx 'dc-content-aggregate:8202:dc-content:/internal/ops/outbox/status' < <(community_outbox_runtime_sources)
REAL_E2E_AUTH_PROFILE=unsupported
if configure_community_auth_profile >/dev/null 2>&1; then
  echo "Unsupported community authentication profiles must fail closed" >&2
  exit 1
fi
unset REAL_E2E_AUTH_PROFILE
unset REAL_E2E_AUTH_URI
configure_community_auth_profile
grep -q 'stop -t 10 kafka' "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
grep -q 'FAILED' "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
grep -q 'PUBLISHED' "${ROOT_DIR}/scripts/e2e/verify-community-kafka-recovery.sh"
grep -q 'verify-community-kafka-recovery.sh' "${ROOT_DIR}/scripts/e2e/run-real-community.sh"
grep -q 'REAL_E2E_COMMUNITY_RECOVERY_MODE' "${ROOT_DIR}/scripts/e2e/run-real-community.sh"
grep -q 'DEAD:2' "${ROOT_DIR}/scripts/e2e/verify-community-outbox-dead-recovery.sh"
grep -q 'OUTBOX_DEAD' "${ROOT_DIR}/scripts/e2e/verify-community-outbox-dead-recovery.sh"
grep -q 'outboxBlockedByDead' "${ROOT_DIR}/scripts/e2e/verify-community-outbox-dead-recovery.sh"

default_community_config="$(docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" \
  --profile community config)"
default_community_auth_routes="$(grep -E '^      DC_AUTH_URI:' <<<"${default_community_config}" | sort -u)"
[[ "${default_community_auth_routes}" == "      DC_AUTH_URI: http://dc-identity:8201" ]] || {
  echo "Community authentication routes must default to dc-identity: ${default_community_auth_routes}" >&2
  exit 1
}
legacy_community_config="$(REAL_E2E_AUTH_URI=http://dc-auth:8101 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile community --profile legacy-auth config)"
grep -q '^  dc-auth:$' <<<"${legacy_community_config}"
legacy_community_auth_routes="$(grep -E '^      DC_AUTH_URI:' <<<"${legacy_community_config}" | sort -u)"
[[ "${legacy_community_auth_routes}" == "      DC_AUTH_URI: http://dc-auth:8101" ]] || {
  echo "Legacy community authentication routes must select dc-auth: ${legacy_community_auth_routes}" >&2
  exit 1
}

community_services="$(docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" \
  --profile community config --services | sort)"
expected_community_services="$(printf '%s\n' dc-admin dc-comment dc-content-aggregate dc-gateway dc-identity dc-interaction \
  dc-notification dc-recommend kafka minio mysql redis | sort)"
[[ "${community_services}" == "${expected_community_services}" ]] || {
  echo "Unexpected community services: ${community_services}" >&2
  exit 1
}

search_services="$(docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" \
  --profile search-recovery config --services | sort)"
expected_search_services="$(printf '%s\n' dc-admin dc-content-aggregate dc-discovery dc-gateway dc-identity \
  elasticsearch kafka minio mysql redis | sort)"
[[ "${search_services}" == "${expected_search_services}" ]] || {
  echo "Unexpected search-recovery services: ${search_services}" >&2
  exit 1
}
default_search_routes="$(docker compose -f "${ROOT_DIR}/docker-compose.e2e.yml" \
  --profile search-recovery config | grep -E '^      DC_SEARCH_URI:' | sort -u)"
[[ "${default_search_routes}" == "      DC_SEARCH_URI: http://dc-discovery:8203" ]] || {
  echo "Search recovery must default to dc-discovery: ${default_search_routes}" >&2
  exit 1
}

legacy_search_services="$(REAL_E2E_SEARCH_URI=http://dc-search:8106 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile legacy-search config --services | sort)"
expected_legacy_search_services="$(printf '%s\n' dc-admin dc-content-aggregate dc-gateway dc-identity dc-search \
  elasticsearch kafka minio mysql redis | sort)"
[[ "${legacy_search_services}" == "${expected_legacy_search_services}" ]] || {
  echo "Unexpected legacy-search services: ${legacy_search_services}" >&2
  exit 1
}
legacy_search_routes="$(REAL_E2E_SEARCH_URI=http://dc-search:8106 docker compose \
  -f "${ROOT_DIR}/docker-compose.e2e.yml" --profile legacy-search config \
  | grep -E '^      DC_SEARCH_URI:' | sort -u)"
[[ "${legacy_search_routes}" == "      DC_SEARCH_URI: http://dc-search:8106" ]] || {
  echo "Legacy search recovery must select dc-search: ${legacy_search_routes}" >&2
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
grep -q 'dc-content-deployment' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'stop -t 10 elasticsearch' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'FAILED' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'COMPLETED' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q '2020-01-01T00:00:00Z' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'Unable to read Kafka offsets' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'REAL_E2E_SEARCH_RECOVERY_MODE' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'REAL_E2E_SEARCH_DEPLOYMENT' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'SEARCH_SERVICE="dc-discovery"' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'SEARCH_SERVICE="dc-search"' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'REPLAY_KAFKA_DLT' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
grep -q 'kafka_dlt_replay' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"
if grep -q 'exec -T kafka /opt/kafka/bin' "${ROOT_DIR}/scripts/e2e/run-real-search-recovery.sh"; then
  echo "Kafka container paths must be protected from Git Bash path conversion with sh -c" >&2
  exit 1
fi

# Production must stay free of the rollback-only process and scrape target. The
# explicit overlay must restore the User route for old Identity dashboard code,
# bound its resources, and replace the Prometheus configuration atomically.
production_compose=(docker compose --env-file "${ROOT_DIR}/.env.production.example" \
  -f "${ROOT_DIR}/docker-compose.prod.yml")
legacy_user_compose=("${production_compose[@]}" \
  -f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-user-rollback.yml")
if "${production_compose[@]}" config --services | grep -qx 'dc-user'; then
  echo "Default production must not start rollback-only dc-user" >&2
  exit 1
fi
if grep -q 'dc-user:8102' "${ROOT_DIR}/deploy/prometheus/prometheus.prod.yml"; then
  echo "Default production Prometheus must not scrape an absent dc-user" >&2
  exit 1
fi
"${legacy_user_compose[@]}" config --format json | python3 -c '
import json, sys
data = json.load(sys.stdin)
services = data["services"]
assert "dc-user" in services
for owner in ("dc-identity", "dc-gateway", "dc-content-aggregate", "dc-admin"):
    assert services[owner]["environment"]["DC_USER_URI"] == "http://dc-user:8102", owner
    assert services[owner]["depends_on"]["dc-user"]["condition"] == "service_healthy", owner
user = services["dc-user"]
assert int(user["mem_limit"]) == 512 * 1024 * 1024
assert user["healthcheck"]["test"][-1] == "http://localhost:8102/actuator/health/readiness"
mounts = [v for v in services["prometheus"]["volumes"] if v["target"] == "/etc/prometheus/prometheus.yml"]
assert len(mounts) == 1
assert mounts[0]["source"].replace("\\", "/").endswith("/deploy/prometheus/prometheus.legacy-user-rollback.yml")
'
grep -Fq 'labels: io.github.devcircle.release-class=${{ matrix.release_class }}' \
  "${ROOT_DIR}/.github/workflows/ci.yml"
grep -Fq 'docker-compose.legacy-user-rollback.yml' "${ROOT_DIR}/.github/workflows/ci.yml"

echo "Docker preflight tests passed"
