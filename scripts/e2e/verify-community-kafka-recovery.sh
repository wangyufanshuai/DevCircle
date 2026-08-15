#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_NAME="${REAL_E2E_PROJECT_NAME:-devcircle-real-community-e2e}"
COMPOSE_FILE="${REAL_E2E_COMPOSE_FILE:-${ROOT_DIR}/docker-compose.e2e.yml}"
BASE_URL="${REAL_E2E_BASE_URL:-http://127.0.0.1:28080}"
WAIT_ATTEMPTS="${REAL_E2E_RECOVERY_WAIT_ATTEMPTS:-90}"
# shellcheck source=node-runtime.sh
source "${ROOT_DIR}/scripts/e2e/node-runtime.sh"
require_e2e_node
# shellcheck source=docker-runtime.sh
source "${ROOT_DIR}/scripts/e2e/docker-runtime.sh"
require_e2e_docker_runtime
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" --profile community)
MYSQL=("${COMPOSE[@]}" exec -T mysql mysql -uroot -pe2e-root-password --skip-column-names --batch)
kafka_stopped=false

restore_kafka() {
  if [[ "${kafka_stopped}" == "true" ]]; then
    "${COMPOSE[@]}" start kafka >/dev/null 2>&1 || true
  fi
}
trap restore_kafka EXIT

sql() {
  local database="$1"
  local statement="$2"
  "${MYSQL[@]}" --database="${database}" --execute="${statement}" 2>/dev/null | tr -d '\r'
}

register_user() {
  local label="$1"
  local unique email key result status response
  unique="$(node -e "console.log(Date.now().toString(36)+'-'+require('crypto').randomUUID())" | tr -d '\r\n')"
  email="${label}-${unique}@example.test"
  key="fault-register-${unique}"
  result="$(curl -sS -X POST "${BASE_URL}/api/auth/register" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${key}" \
    --data "{\"email\":\"${email}\",\"password\":\"OrbitReader2026!\",\"displayName\":\"${label}-${unique: -6}\"}" \
    --write-out $'\n%{http_code}')"
  status="${result##*$'\n'}"
  response="${result%$'\n'*}"
  [[ "${status}" == "200" ]] || {
    echo "Failed to register ${label}: HTTP ${status} ${response}" >&2
    return 1
  }
  node -e "const body=JSON.parse(process.argv[1]); const user=body.data?.user; if(!user?.userId || !body.data?.accessToken) process.exit(2); console.log(user.userId); console.log(body.data.accessToken)" "${response}"
}

wait_for_profile() {
  local user_id="$1"
  for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
    if curl -fsS "${BASE_URL}/api/users/${user_id}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Profile ${user_id} did not arrive through Kafka" >&2
  return 1
}

wait_for_kafka() {
  for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
    if "${COMPOSE[@]}" exec -T kafka sh -c \
      '/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1' \
      >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Kafka did not become healthy after restart" >&2
  return 1
}

mapfile -t follower < <(register_user "fault-follower")
mapfile -t author < <(register_user "fault-author")
follower_id="${follower[0]}"
follower_token="${follower[1]}"
author_id="${author[0]}"

wait_for_profile "${follower_id}"
wait_for_profile "${author_id}"

"${COMPOSE[@]}" stop -t 10 kafka >/dev/null
kafka_stopped=true
kafka_container="$("${COMPOSE[@]}" ps --all -q kafka)"
[[ -n "${kafka_container}" ]] || { echo "Kafka container disappeared during fault injection" >&2; exit 1; }
[[ "$(docker inspect --format '{{.State.Running}}' "${kafka_container}")" == "false" ]] || {
  echo "Kafka was still running after stop" >&2
  exit 1
}

follow_key="fault-follow-$(node -e "process.stdout.write(require('crypto').randomUUID())")"
follow_response="$(curl -fsS -X POST "${BASE_URL}/api/users/${author_id}/follow" \
  -H "Authorization: Bearer ${follower_token}" \
  -H "Idempotency-Key: ${follow_key}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.code!=='OK' || body.data?.followed!==true || body.data?.duplicated!==false) process.exit(2)" "${follow_response}"

following="$(curl -fsS "${BASE_URL}/api/users/${author_id}/follow-state" \
  -H "Authorization: Bearer ${follower_token}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.code!=='OK' || body.data?.following!==true) process.exit(2)" "${following}"

event_id="$(sql dc_user "SELECT event_id FROM outbox_event WHERE topic = 'follow.changed' AND aggregate_id = '${author_id}' ORDER BY id DESC LIMIT 1;")"
[[ "${event_id}" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "Follow Outbox event was not persisted" >&2; exit 1; }

failed=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  outbox_status="$(sql dc_user "SELECT status FROM outbox_event WHERE event_id = '${event_id}';")"
  if [[ "${outbox_status}" == "FAILED" ]]; then
    failed=true
    break
  fi
  [[ "${outbox_status}" != "DEAD" && "${outbox_status}" != "PUBLISHED" ]] || {
    echo "Unexpected Outbox status while Kafka is down: ${outbox_status}" >&2
    exit 1
  }
  sleep 1
done
[[ "${failed}" == "true" ]] || { echo "Outbox event did not enter FAILED while Kafka was down" >&2; exit 1; }

notification_before="$(sql dc_notification "SELECT COUNT(*) FROM notification WHERE receiver_id = ${author_id} AND actor_id = ${follower_id} AND type = 'FOLLOW';")"
projection_before="$(sql dc_content "SELECT COUNT(*) FROM recommend_follow_projection WHERE viewer_id = ${follower_id} AND author_id = ${author_id};")"
[[ "${notification_before}" == "0" && "${projection_before}" == "0" ]] || {
  echo "Kafka-down event leaked into a consumer projection" >&2
  exit 1
}

"${COMPOSE[@]}" start kafka >/dev/null
kafka_stopped=false
wait_for_kafka

recovered=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  outbox_status="$(sql dc_user "SELECT status FROM outbox_event WHERE event_id = '${event_id}';")"
  notification_count="$(sql dc_notification "SELECT COUNT(*) FROM notification WHERE receiver_id = ${author_id} AND actor_id = ${follower_id} AND type = 'FOLLOW';")"
  projection_state="$(sql dc_content "SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(followed), 0)) FROM recommend_follow_projection WHERE viewer_id = ${follower_id} AND author_id = ${author_id};")"
  notification_dedup="$(sql dc_notification "SELECT COUNT(*) FROM consumer_dedup WHERE event_id = '${event_id}' AND topic = 'follow.changed';")"
  recommend_dedup="$(sql dc_content "SELECT COUNT(*) FROM recommend_consumer_dedup WHERE event_id = '${event_id}' AND topic = 'follow.changed' AND status = 'COMPLETED';")"
  if [[ "${outbox_status}" == "PUBLISHED" && "${notification_count}" == "1" \
      && "${projection_state}" == "1:1" && "${notification_dedup}" == "1" && "${recommend_dedup}" == "1" ]]; then
    recovered=true
    break
  fi
  [[ "${outbox_status}" != "DEAD" ]] || { echo "Outbox event became DEAD after Kafka recovery" >&2; exit 1; }
  sleep 1
done
[[ "${recovered}" == "true" ]] || {
  echo "Community projections did not catch up after Kafka recovery" >&2
  exit 1
}

replay="$(curl -fsS -X POST "${BASE_URL}/api/users/${author_id}/follow" \
  -H "Authorization: Bearer ${follower_token}" \
  -H "Idempotency-Key: ${follow_key}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.code!=='OK' || body.data?.followed!==true || body.data?.duplicated!==true) process.exit(2)" "${replay}"

event_count="$(sql dc_user "SELECT COUNT(*) FROM outbox_event WHERE event_id = '${event_id}';")"
notification_count="$(sql dc_notification "SELECT COUNT(*) FROM notification WHERE receiver_id = ${author_id} AND actor_id = ${follower_id} AND type = 'FOLLOW';")"
[[ "${event_count}" == "1" && "${notification_count}" == "1" ]] || {
  echo "Idempotent replay duplicated the recovered event or notification" >&2
  exit 1
}

echo "Kafka recovery verified event=${event_id}: FAILED -> PUBLISHED, notification=1, recommendation=1"
