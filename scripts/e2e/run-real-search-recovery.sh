#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.e2e.yml"
RECOVERY_MODE="${REAL_E2E_SEARCH_RECOVERY_MODE:-retry}"
SEARCH_DEPLOYMENT="${REAL_E2E_SEARCH_DEPLOYMENT:-discovery}"
case "${SEARCH_DEPLOYMENT}" in
  discovery)
    SEARCH_SERVICE="dc-discovery"
    SEARCH_PORT=8203
    SEARCH_PROFILE="search-recovery"
    ;;
  legacy)
    SEARCH_SERVICE="dc-search"
    SEARCH_PORT=8106
    SEARCH_PROFILE="legacy-search"
    export REAL_E2E_SEARCH_URI="http://dc-search:8106"
    ;;
  *)
    echo "REAL_E2E_SEARCH_DEPLOYMENT must be discovery or legacy" >&2
    exit 2
    ;;
esac
if [[ "${RECOVERY_MODE}" == "dlt" ]]; then
  DEFAULT_PROJECT_NAME="devcircle-real-search-dlt-e2e"
  DEFAULT_RETRY_INTERVAL_MS=500
  DEFAULT_RETRY_ATTEMPTS=1
else
  DEFAULT_PROJECT_NAME="devcircle-real-search-recovery-e2e"
  DEFAULT_RETRY_INTERVAL_MS=2000
  DEFAULT_RETRY_ATTEMPTS=60
fi
PROJECT_NAME="${REAL_E2E_PROJECT_NAME:-${DEFAULT_PROJECT_NAME}}"
BASE_URL="${REAL_E2E_BASE_URL:-http://127.0.0.1:28080}"
WAIT_TIMEOUT="${REAL_E2E_WAIT_TIMEOUT:-240}"
WAIT_ATTEMPTS="${REAL_E2E_RECOVERY_WAIT_ATTEMPTS:-180}"
PULL_POLICY="${REAL_E2E_PULL_POLICY:-missing}"
BUILD_MODE="${REAL_E2E_BUILD:-true}"
KEEP_ON_FAILURE="${REAL_E2E_KEEP_ON_FAILURE:-false}"
export REAL_E2E_ENABLE_OUTBOX=true
export REAL_E2E_SEARCH_RETRY_INITIAL_INTERVAL_MS="${REAL_E2E_SEARCH_RETRY_INITIAL_INTERVAL_MS:-${DEFAULT_RETRY_INTERVAL_MS}}"
export REAL_E2E_SEARCH_RETRY_MULTIPLIER="${REAL_E2E_SEARCH_RETRY_MULTIPLIER:-1.0}"
export REAL_E2E_SEARCH_RETRY_MAX_INTERVAL_MS="${REAL_E2E_SEARCH_RETRY_MAX_INTERVAL_MS:-${DEFAULT_RETRY_INTERVAL_MS}}"
export REAL_E2E_SEARCH_RETRY_MAX_ATTEMPTS="${REAL_E2E_SEARCH_RETRY_MAX_ATTEMPTS:-${DEFAULT_RETRY_ATTEMPTS}}"
export REAL_E2E_MIN_DOCKER_MEMORY_BYTES="${REAL_E2E_MIN_DOCKER_MEMORY_BYTES:-4294967296}"
ADMIN_EMAIL="real-search-admin@example.test"
ADMIN_PASSWORD="OrbitAdmin2026!"
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" --profile "${SEARCH_PROFILE}")
MYSQL=("${COMPOSE[@]}" exec -T mysql mysql -uroot -pe2e-root-password --skip-column-names --batch)
# shellcheck source=docker-preflight.sh
source "${ROOT_DIR}/scripts/e2e/docker-preflight.sh"

cleanup() {
  status=$?
  if (( status != 0 )); then
    "${COMPOSE[@]}" logs --no-color --tail=240 "${SEARCH_SERVICE}" dc-admin dc-content-aggregate dc-gateway || true
    if [[ "${KEEP_ON_FAILURE}" == "true" ]]; then
      echo "Search recovery environment retained for debugging: project=${PROJECT_NAME}" >&2
      exit "${status}"
    fi
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  exit "${status}"
}

report_error() {
  local status=$?
  echo "Search recovery command failed at line ${BASH_LINENO[0]} with status ${status}: ${BASH_COMMAND}" >&2
  return "${status}"
}

sql() {
  local database="$1"
  local statement="$2"
  "${MYSQL[@]}" --database="${database}" --execute="${statement}" 2>/dev/null | tr -d '\r'
}

register_user() {
  local label="$1"
  local email="$2"
  local key="$3"
  local password="$4"
  local result
  result="$(curl -fsS -X POST "${BASE_URL}/api/auth/register" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${key}" \
    --data "{\"email\":\"${email}\",\"password\":\"${password}\",\"displayName\":\"${label}\"}")"
  node -e "const body=JSON.parse(process.argv[1]); const user=body.data?.user; if(!user?.userId || !body.data?.accessToken) process.exit(2); console.log(user.userId); console.log(body.data.accessToken)" "${result}"
}

wait_for_elasticsearch() {
  for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
    if "${COMPOSE[@]}" exec -T elasticsearch curl -fsS \
      'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=1s' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Elasticsearch did not become healthy after restart" >&2
  return 1
}

wait_for_search_readiness() {
  for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
    if "${COMPOSE[@]}" exec -T "${SEARCH_SERVICE}" curl -fsS \
      "http://localhost:${SEARCH_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "${SEARCH_SERVICE} did not recover readiness after Elasticsearch restart" >&2
  return 1
}

search_count() {
  local title="$1"
  local response
  response="$(curl -fsS --get "${BASE_URL}/api/search/posts" --data-urlencode "keyword=${title}")"
  node -e "const body=JSON.parse(process.argv[1]); const title=process.argv[2]; const data=body.data; const records=Array.isArray(data)?data:(data?.records??[]); console.log(records.filter(item=>item.title===title).length)" "${response}" "${title}"
}

topic_end_offset() {
  local topic="$1"
  local offsets
  offsets="$("${COMPOSE[@]}" exec -T kafka sh -c \
    '/opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$1"' \
    kafka-offsets "${topic}" 2>/dev/null)" || {
    echo "Unable to read Kafka offsets for ${topic}" >&2
    return 1
  }
  awk -F: '{ total += $3 } END { print total + 0 }' <<<"${offsets}"
}

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }
command -v node >/dev/null 2>&1 || { echo "node is required" >&2; exit 2; }
[[ "${KEEP_ON_FAILURE}" == "true" || "${KEEP_ON_FAILURE}" == "false" ]] || {
  echo "REAL_E2E_KEEP_ON_FAILURE must be true or false" >&2
  exit 2
}
[[ "${RECOVERY_MODE}" == "retry" || "${RECOVERY_MODE}" == "dlt" ]] || {
  echo "REAL_E2E_SEARCH_RECOVERY_MODE must be retry or dlt" >&2
  exit 2
}
require_real_e2e_docker "search recovery E2E"

if command -v java >/dev/null 2>&1; then
  if [[ "${SEARCH_DEPLOYMENT}" == "discovery" ]]; then
    "${ROOT_DIR}/mvnw" -q -pl dc-identity,dc-content-deployment,dc-admin,dc-discovery,dc-gateway -am -DskipTests package
  else
    "${ROOT_DIR}/mvnw" -q -pl dc-identity,dc-content-deployment,dc-admin,dc-search,dc-gateway -am -DskipTests package
  fi
elif command -v cmd.exe >/dev/null 2>&1; then
  if [[ "${SEARCH_DEPLOYMENT}" == "discovery" ]]; then
    (cd "${ROOT_DIR}" && cmd.exe /d /c "mvnw.cmd -q -pl dc-identity,dc-content-deployment,dc-admin,dc-discovery,dc-gateway -am -DskipTests package")
  else
    (cd "${ROOT_DIR}" && cmd.exe /d /c "mvnw.cmd -q -pl dc-identity,dc-content-deployment,dc-admin,dc-search,dc-gateway -am -DskipTests package")
  fi
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

trap report_error ERR
trap cleanup EXIT
"${COMPOSE[@]}" down -v --remove-orphans
"${COMPOSE[@]}" up -d "${BUILD_ARGS[@]}" --pull "${PULL_POLICY}" --wait --wait-timeout "${WAIT_TIMEOUT}"

unique="$(node -e "process.stdout.write(Date.now().toString(36)+'-'+require('crypto').randomUUID())")"
author_email="search-author-${unique}@example.test"
title="search-recovery-${unique}"

mapfile -t admin < <(register_user "Search Admin" "${ADMIN_EMAIL}" "search-admin-${unique}" "${ADMIN_PASSWORD}")
[[ "${admin[0]}" =~ ^[1-9][0-9]*$ ]] || { echo "Search administrator registration failed" >&2; exit 1; }
"${COMPOSE[@]}" exec -T mysql mysql -uroot -pe2e-root-password --database=dc_auth --execute="
  INSERT IGNORE INTO user_role(user_id, role_id)
  SELECT ua.id, r.id FROM user_account ua JOIN role r ON r.code = 'ADMIN'
  WHERE ua.email = '${ADMIN_EMAIL}';
"

admin_login="$(curl -fsS -X POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: search-admin-login-${unique}" \
  --data "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}")"
admin_token="$(node -e "const body=JSON.parse(process.argv[1]); if(!body.data?.user?.roles?.includes('ADMIN')) process.exit(2); process.stdout.write(body.data.accessToken)" "${admin_login}")"

mapfile -t author < <(register_user "Search Author" "${author_email}" "search-author-${unique}" "OrbitReader2026!")
author_token="${author[1]}"

draft_response="$(curl -fsS -X POST "${BASE_URL}/api/posts/drafts" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${author_token}" \
  -H "Idempotency-Key: search-draft-${unique}" \
  --data "{\"title\":\"${title}\",\"content\":\"A durable indexing event must survive a temporary search outage.\",\"tags\":[\"recovery\"],\"type\":\"TEXT\",\"mediaIds\":[],\"media\":[],\"theme\":\"COSMOS\",\"contentKind\":\"COSMIC_ARTICLE\",\"difficulty\":\"MEDIUM\",\"solvable\":false,\"clues\":\"\",\"suspects\":\"\",\"timeline\":\"\",\"question\":\"\",\"answerKeywords\":\"\",\"solution\":\"\",\"version\":0}")"
post_id="$(node -e "const body=JSON.parse(process.argv[1]); if(body.data?.status!=='DRAFT') process.exit(2); process.stdout.write(String(body.data.id))" "${draft_response}")"
draft_version="$(node -e "const body=JSON.parse(process.argv[1]); process.stdout.write(String(body.data?.version))" "${draft_response}")"

submit_response="$(curl -fsS -X POST "${BASE_URL}/api/posts/drafts/${post_id}/submit?version=${draft_version}" \
  -H "Authorization: Bearer ${author_token}" \
  -H "Idempotency-Key: search-submit-${unique}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.status!=='PENDING') process.exit(2)" "${submit_response}"

task_id=""
for _ in $(seq 1 30); do
  tasks="$(curl -fsS "${BASE_URL}/api/admin/moderation/tasks?status=PENDING&page=1&size=20" \
    -H "Authorization: Bearer ${admin_token}")"
  task_id="$(node -e "const body=JSON.parse(process.argv[1]); const postId=Number(process.argv[2]); const item=body.data?.find(value=>Number(value.postId)===postId); process.stdout.write(item?String(item.taskId):'')" "${tasks}" "${post_id}")"
  [[ -z "${task_id}" ]] || break
  sleep 1
done
[[ "${task_id}" =~ ^[1-9][0-9]*$ ]] || { echo "Moderation task for post ${post_id} was not created" >&2; exit 1; }
[[ "$(search_count "${title}")" == "0" ]] || { echo "Pending post was visible in search" >&2; exit 1; }
echo "Search recovery setup ready: post=${post_id} task=${task_id}"

"${COMPOSE[@]}" stop -t 10 elasticsearch >/dev/null
elasticsearch_container="$("${COMPOSE[@]}" ps --all -q elasticsearch)"
[[ -n "${elasticsearch_container}" ]] || { echo "Elasticsearch container disappeared during fault injection" >&2; exit 1; }
[[ "$(docker inspect --format '{{.State.Running}}' "${elasticsearch_container}")" == "false" ]] || {
  echo "Elasticsearch was still running after stop" >&2
  exit 1
}

approval_key="search-approve-${unique}"
approval_url="${BASE_URL}/api/admin/moderation/tasks/${task_id}/approve"
approval_response="$(curl -fsS -X POST "${approval_url}" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${admin_token}" \
  -H "Idempotency-Key: ${approval_key}" \
  --data '{"reason":"Verified complete content for search recovery testing."}')"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.attributes?.status!=='PUBLISHED') process.exit(2)" "${approval_response}"

detail="$(curl -fsS "${BASE_URL}/api/posts/${post_id}")"
node -e "const body=JSON.parse(process.argv[1]); const id=Number(process.argv[2]); const title=process.argv[3]; if(body.data?.id!==id || body.data?.title!==title || body.data?.status!=='PUBLISHED') process.exit(2)" "${detail}" "${post_id}" "${title}"

set +e
search_failure="$(curl -sS --max-time 20 --get "${BASE_URL}/api/search/posts" \
  --data-urlencode "keyword=${title}" --write-out $'\n%{http_code}')"
search_curl_status=$?
set -e
[[ "${search_curl_status}" == "0" ]] || { echo "Search failure response could not be read" >&2; exit 1; }
search_http_status="${search_failure##*$'\n'}"
search_error_body="${search_failure%$'\n'*}"
[[ "${search_http_status}" == "503" ]] || { echo "Expected search HTTP 503 while Elasticsearch was down, got ${search_http_status}" >&2; exit 1; }
node -e "const body=JSON.parse(process.argv[1]); if(body.code!=='DEPENDENCY_UNAVAILABLE') process.exit(2)" "${search_error_body}"

event_id="$(sql dc_content "SELECT event_id FROM outbox_event WHERE topic = 'audit.completed' AND aggregate_id = '${post_id}' ORDER BY id DESC LIMIT 1;")"
[[ "${event_id}" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "Approval Outbox event was not persisted" >&2; exit 1; }

outbox_published=false
consumer_failed=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  outbox_status="$(sql dc_content "SELECT status FROM outbox_event WHERE event_id = '${event_id}';")"
  consumer_status="$(sql dc_search "SELECT status FROM search_consumer_dedup WHERE event_id = '${event_id}';")"
  [[ "${outbox_status}" == "PUBLISHED" ]] && outbox_published=true
  [[ "${consumer_status}" == "FAILED" ]] && consumer_failed=true
  if [[ "${outbox_published}" == "true" && "${consumer_failed}" == "true" ]]; then
    break
  fi
  [[ "${outbox_status}" != "DEAD" && "${consumer_status}" != "COMPLETED" ]] || {
    echo "Unexpected recovery state while Elasticsearch is down: outbox=${outbox_status} consumer=${consumer_status}" >&2
    exit 1
  }
  sleep 1
done
[[ "${outbox_published}" == "true" && "${consumer_failed}" == "true" ]] || {
  echo "Search event did not reach PUBLISHED/FAILED split state while Elasticsearch was down" >&2
  exit 1
}
echo "Elasticsearch outage observed: outbox=PUBLISHED consumer=FAILED event=${event_id} mode=${RECOVERY_MODE}"

if [[ "${RECOVERY_MODE}" == "retry" ]]; then
  [[ "$(topic_end_offset 'audit.completed.DLT')" == "0" ]] || {
    echo "Search event reached the DLT before Elasticsearch recovery" >&2
    exit 1
  }
else
  dlt_ready=false
  for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
    dlt_offset_end="$(topic_end_offset 'audit.completed.DLT')"
    if [[ "${dlt_offset_end}" == "1" ]]; then
      dlt_ready=true
      break
    fi
    [[ "${dlt_offset_end}" =~ ^[0-9]+$ ]] && (( dlt_offset_end < 2 )) || {
      echo "Unexpected audit.completed DLT offset: ${dlt_offset_end}" >&2
      exit 1
    }
    sleep 1
  done
  [[ "${dlt_ready}" == "true" ]] || { echo "Failed search event did not reach the DLT" >&2; exit 1; }
  source_offset_before_replay="$(topic_end_offset 'audit.completed')"
  [[ "${source_offset_before_replay}" == "1" ]] || {
    echo "Unexpected source topic offset before DLT replay: ${source_offset_before_replay}" >&2
    exit 1
  }
  echo "Search event exhausted retries and reached DLT offset 0"
fi

"${COMPOSE[@]}" start elasticsearch >/dev/null
wait_for_elasticsearch
wait_for_search_readiness
echo "Elasticsearch and search readiness recovered"

if [[ "${RECOVERY_MODE}" == "dlt" ]]; then
  dlt_replay_key="search-dlt-replay-${unique}"
  dlt_replay_url="${BASE_URL}/api/admin/ops/dlt/replay"
  dlt_replay_result="$(curl -sS -X POST "${dlt_replay_url}" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${admin_token}" \
    -H "Idempotency-Key: ${dlt_replay_key}" \
    --data '{"sourceTopic":"audit.completed","partition":0,"offset":0,"reason":"Elasticsearch recovered; replay the failed search projection."}' \
    --write-out $'\n%{http_code}')"
  dlt_replay_status="${dlt_replay_result##*$'\n'}"
  dlt_replay_response="${dlt_replay_result%$'\n'*}"
  [[ "${dlt_replay_status}" == "200" ]] || {
    echo "DLT replay API failed with HTTP ${dlt_replay_status}: ${dlt_replay_response}" >&2
    exit 1
  }
  node -e "const body=JSON.parse(process.argv[1]); const eventId=process.argv[2]; const data=body.data; if(data?.action!=='REPLAY_KAFKA_DLT' || data?.attributes?.eventId!==eventId || data?.attributes?.duplicateRequest!==false) process.exit(2)" "${dlt_replay_response}" "${event_id}"
fi

recovered=false
attempts=0
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  consumer_state="$(sql dc_search "SELECT CONCAT(status, ':', attempts) FROM search_consumer_dedup WHERE event_id = '${event_id}';")"
  if [[ "${consumer_state}" == COMPLETED:* ]]; then
    attempts="${consumer_state#*:}"
    if [[ "$(search_count "${title}")" == "1" ]]; then
      recovered=true
      break
    fi
  fi
  sleep 1
done
if [[ "${recovered}" != "true" || ! "${attempts}" =~ ^[0-9]+$ ]] || (( attempts < 2 )); then
  echo "Search projection did not recover after a failed Elasticsearch attempt: state=${consumer_state}" >&2
  exit 1
fi
echo "Search projection recovered: event=${event_id} attempts=${attempts}"

if [[ "${RECOVERY_MODE}" == "dlt" ]]; then
  source_offset_after_replay="$(topic_end_offset 'audit.completed')"
  [[ "${source_offset_after_replay}" == "2" ]] || {
    echo "DLT replay did not append exactly one source event: ${source_offset_after_replay}" >&2
    exit 1
  }
  dlt_replay_duplicate="$(curl -fsS -X POST "${dlt_replay_url}" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${admin_token}" \
    -H "Idempotency-Key: ${dlt_replay_key}" \
    --data '{"sourceTopic":"audit.completed","partition":0,"offset":0,"reason":"Elasticsearch recovered; replay the failed search projection."}')"
  node -e "const body=JSON.parse(process.argv[1]); if(body.data?.attributes?.duplicateRequest!==true) process.exit(2)" "${dlt_replay_duplicate}"
  [[ "$(topic_end_offset 'audit.completed')" == "2" ]] || {
    echo "Idempotent DLT replay appended a duplicate source event" >&2
    exit 1
  }
  dlt_replay_rows="$(sql dc_content "SELECT COUNT(*) FROM kafka_dlt_replay WHERE event_id = '${event_id}' AND source_topic = 'audit.completed';")"
  dlt_replay_audits="$(sql dc_content "SELECT COUNT(*) FROM audit_log WHERE action = 'REPLAY_KAFKA_DLT' AND target_id = '0:0';")"
  [[ "${dlt_replay_rows}" == "1" && "${dlt_replay_audits}" == "1" ]] || {
    echo "DLT replay persistence or audit is incomplete: rows=${dlt_replay_rows} audits=${dlt_replay_audits}" >&2
    exit 1
  }
  echo "Operator DLT replay recovered and remained idempotent"
fi

watermark_event="$(sql dc_search "SELECT last_event_id FROM search_event_watermark WHERE post_id = ${post_id};")"
watermark_action="$(sql dc_search "SELECT last_action FROM search_event_watermark WHERE post_id = ${post_id};")"
[[ "${watermark_event}" == "${event_id}" && "${watermark_action}" == "UPSERT" ]] || {
  echo "Recovered search watermark is incorrect: event=${watermark_event} action=${watermark_action}" >&2
  exit 1
}

stale_event_id="$(node -e "process.stdout.write(require('crypto').randomUUID())")"
stale_event="$(node -e "const [eventId,postId]=process.argv.slice(1); process.stdout.write(JSON.stringify({eventId,type:'content.rejected',version:1,occurredAt:'2020-01-01T00:00:00Z',producer:'dc-admin',aggregateId:postId,payload:{postId:Number(postId),status:'REJECTED',action:'STALE_RECOVERY_TEST'}}))" "${stale_event_id}" "${post_id}")"
printf '%s\n' "${stale_event}" | "${COMPOSE[@]}" exec -T kafka sh -c \
  '/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic content.rejected' >/dev/null

stale_completed=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  stale_status="$(sql dc_search "SELECT status FROM search_consumer_dedup WHERE event_id = '${stale_event_id}';")"
  if [[ "${stale_status}" == "COMPLETED" ]]; then
    stale_completed=true
    break
  fi
  sleep 1
done
[[ "${stale_completed}" == "true" ]] || { echo "Stale search event was not recorded as completed" >&2; exit 1; }
[[ "$(search_count "${title}")" == "1" ]] || { echo "Stale event removed the recovered search projection" >&2; exit 1; }
[[ "$(sql dc_search "SELECT last_event_id FROM search_event_watermark WHERE post_id = ${post_id};")" == "${event_id}" ]] || {
  echo "Stale event moved the search watermark backwards" >&2
  exit 1
}
echo "Stale search event ignored: event=${stale_event_id}"

replay="$(curl -fsS -X POST "${approval_url}" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${admin_token}" \
  -H "Idempotency-Key: ${approval_key}" \
  --data '{"reason":"Verified complete content for search recovery testing."}')"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.attributes?.duplicateRequest!==true) process.exit(2)" "${replay}"

event_count="$(sql dc_content "SELECT COUNT(*) FROM outbox_event WHERE event_id = '${event_id}';")"
[[ "${event_count}" == "1" && "$(search_count "${title}")" == "1" ]] || {
  echo "Idempotent approval replay duplicated the event or search projection" >&2
  exit 1
}
expected_audit_dlt_offset=0
[[ "${RECOVERY_MODE}" == "retry" ]] || expected_audit_dlt_offset=1
[[ "$(topic_end_offset 'audit.completed.DLT')" == "${expected_audit_dlt_offset}" && "$(topic_end_offset 'content.rejected.DLT')" == "0" ]] || {
  echo "A successfully recovered or stale event was written to a search DLT" >&2
  exit 1
}

echo "Search recovery verified mode=${RECOVERY_MODE} post=${post_id} event=${event_id}: FAILED -> COMPLETED in ${attempts} attempts; stale event ignored"
