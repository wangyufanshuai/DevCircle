#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_NAME="${REAL_E2E_PROJECT_NAME:-devcircle-real-community-e2e}"
COMPOSE_FILE="${REAL_E2E_COMPOSE_FILE:-${ROOT_DIR}/docker-compose.e2e.yml}"
BASE_URL="${REAL_E2E_BASE_URL:-http://127.0.0.1:28080}"
WAIT_ATTEMPTS="${REAL_E2E_RECOVERY_WAIT_ATTEMPTS:-120}"
# shellcheck source=community-auth-profile.sh
source "${ROOT_DIR}/scripts/e2e/community-auth-profile.sh"
configure_community_auth_profile
COMPOSE=(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" "${COMMUNITY_AUTH_COMPOSE_PROFILES[@]}")
MYSQL=("${COMPOSE[@]}" exec -T mysql mysql -uroot -pe2e-root-password --skip-column-names --batch)
kafka_stopped=false

report_failure() {
  local status=$?
  echo "Community DEAD recovery failed at line ${BASH_LINENO[0]}: ${BASH_COMMAND} (exit ${status})" >&2
  return "${status}"
}

restore_kafka() {
  if [[ "${kafka_stopped}" == "true" ]]; then
    "${COMPOSE[@]}" start kafka >/dev/null 2>&1 || true
  fi
}
trap report_failure ERR
trap restore_kafka EXIT

sql() {
  local database="$1"
  local statement="$2"
  "${MYSQL[@]}" --database="${database}" --execute="${statement}" 2>/dev/null | tr -d '\r'
}

register_user() {
  local label="$1"
  local email="$2"
  local key="$3"
  local result
  result="$(curl -fsS -X POST "${BASE_URL}/api/auth/register" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${key}" \
    --data "{\"email\":\"${email}\",\"password\":\"OrbitReader2026!\",\"displayName\":\"${label}\"}")"
  node -e "const body=JSON.parse(process.argv[1]); const user=body.data?.user; if(!user?.userId || !body.data?.accessToken) process.exit(2); console.log(user.userId); console.log(body.data.accessToken)" "${result}"
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

wait_for_service() {
  local service="$1"
  local port="$2"
  for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
    if "${COMPOSE[@]}" exec -T "${service}" curl -fsS \
      "http://localhost:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "${service} did not recover readiness" >&2
  return 1
}

ensure_topic() {
  local topic="$1"
  "${COMPOSE[@]}" exec -T kafka sh -c \
    '/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic "$1" --partitions 1 --replication-factor 1 >/dev/null' \
    kafka-topic "${topic}"
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

unique="$(node -e "process.stdout.write(Date.now().toString(36)+'-'+require('crypto').randomUUID())")"
admin_email="dead-admin-${unique}@example.test"
author_email="dead-author-${unique}@example.test"
mapfile -t admin < <(register_user "Dead Letter Admin" "${admin_email}" "dead-admin-${unique}")
mapfile -t author < <(register_user "Dead Letter Author" "${author_email}" "dead-author-${unique}")
author_token="${author[1]}"

"${COMPOSE[@]}" exec -T mysql mysql -uroot -pe2e-root-password --database=dc_auth --execute="
  INSERT IGNORE INTO user_role(user_id, role_id)
  SELECT ua.id, r.id FROM user_account ua JOIN role r ON r.code = 'ADMIN'
  WHERE ua.email = '${admin_email}';
"
admin_login="$(curl -fsS -X POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dead-admin-login-${unique}" \
  --data "{\"email\":\"${admin_email}\",\"password\":\"OrbitReader2026!\"}")"
admin_token="$(node -e "const body=JSON.parse(process.argv[1]); if(!body.data?.user?.roles?.includes('ADMIN')) process.exit(2); process.stdout.write(body.data.accessToken)" "${admin_login}")"

unauthorized_internal="$("${COMPOSE[@]}" exec -T dc-user curl -sS -o /dev/null -w '%{http_code}' \
  'http://localhost:8102/internal/ops/outbox/status')"
[[ "${unauthorized_internal}" == "403" ]] || {
  echo "Internal Outbox endpoint was not protected: HTTP ${unauthorized_internal}" >&2
  exit 1
}
mapfile -t outbox_runtime_sources < <(community_outbox_runtime_sources)
for runtime in "${outbox_runtime_sources[@]}"; do
  IFS=: read -r service port logical_source <<<"${runtime}"
  authorized_internal="$("${COMPOSE[@]}" exec -T "${service}" curl -fsS \
    -H 'X-Internal-Ops-Key: E2e-Internal-Ops-Key-2026-Only-For-Isolated-Compose' \
    "http://localhost:${port}/internal/ops/outbox/status")"
  node -e "const body=JSON.parse(process.argv[1]); if(body.service!==process.argv[2]) process.exit(2)" \
    "${authorized_internal}" "${logical_source}"
done

title="outbox-dead-${unique}"
draft_response="$(curl -fsS -X POST "${BASE_URL}/api/posts/drafts" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${author_token}" \
  -H "Idempotency-Key: dead-draft-${unique}" \
  --data "{\"title\":\"${title}\",\"content\":\"A dead Outbox event must be visible and recover in aggregate order.\",\"tags\":[\"recovery\"],\"type\":\"TEXT\",\"mediaIds\":[],\"media\":[],\"theme\":\"COSMOS\",\"contentKind\":\"COSMIC_ARTICLE\",\"difficulty\":\"MEDIUM\",\"solvable\":false,\"clues\":\"\",\"suspects\":\"\",\"timeline\":\"\",\"question\":\"\",\"answerKeywords\":\"\",\"solution\":\"\",\"version\":0}")"
post_id="$(node -e "const body=JSON.parse(process.argv[1]); if(body.data?.status!=='DRAFT') process.exit(2); process.stdout.write(String(body.data.id))" "${draft_response}")"
draft_version="$(node -e "const body=JSON.parse(process.argv[1]); process.stdout.write(String(body.data?.version))" "${draft_response}")"

ensure_topic 'post.submitted'
ensure_topic 'content.rejected'

"${COMPOSE[@]}" stop -t 10 kafka >/dev/null
kafka_stopped=true
kafka_container="$("${COMPOSE[@]}" ps --all -q kafka)"
[[ -n "${kafka_container}" && "$(docker inspect --format '{{.State.Running}}' "${kafka_container}")" == "false" ]] || {
  echo "Kafka was still running after stop" >&2
  exit 1
}

submit_response="$(curl -fsS -X POST "${BASE_URL}/api/posts/drafts/${post_id}/submit?version=${draft_version}" \
  -H "Authorization: Bearer ${author_token}" \
  -H "Idempotency-Key: dead-submit-${unique}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.status!=='PENDING') process.exit(2)" "${submit_response}"
submitted_version="$(node -e "const body=JSON.parse(process.argv[1]); process.stdout.write(String(body.data?.version))" "${submit_response}")"
task_id="$(sql dc_content "SELECT id FROM moderation_task WHERE post_id = ${post_id} AND status = 'PENDING' ORDER BY id DESC LIMIT 1;")"
[[ "${task_id}" =~ ^[1-9][0-9]*$ ]] || { echo "Moderation task was not created" >&2; exit 1; }

changes_response="$(curl -fsS -X POST "${BASE_URL}/api/admin/moderation/tasks/${task_id}/require-changes" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${admin_token}" \
  -H "Idempotency-Key: dead-changes-${unique}" \
  --data '{"reason":"Require one deterministic resubmission for ordered Outbox recovery testing."}')"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.attributes?.status!=='CHANGES_REQUIRED') process.exit(2)" "${changes_response}"

resubmit_response="$(curl -fsS -X POST "${BASE_URL}/api/posts/drafts/${post_id}/submit?version=${submitted_version}" \
  -H "Authorization: Bearer ${author_token}" \
  -H "Idempotency-Key: dead-resubmit-${unique}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.status!=='PENDING') process.exit(2)" "${resubmit_response}"

submitted_event="$(sql dc_content "SELECT event_id FROM outbox_event WHERE topic = 'post.submitted' AND aggregate_id = '${post_id}' ORDER BY id LIMIT 1;")"
resubmitted_event="$(sql dc_content "SELECT event_id FROM outbox_event WHERE topic = 'post.submitted' AND aggregate_id = '${post_id}' ORDER BY id LIMIT 1 OFFSET 1;")"
rejected_event="$(sql dc_content "SELECT event_id FROM outbox_event WHERE topic = 'content.rejected' AND aggregate_id = '${post_id}' ORDER BY id LIMIT 1;")"
[[ "${submitted_event}" =~ ^[0-9a-fA-F-]{36}$ && "${resubmitted_event}" =~ ^[0-9a-fA-F-]{36}$ \
    && "${rejected_event}" =~ ^[0-9a-fA-F-]{36}$ ]] || {
  echo "Expected ordered content Outbox events were not persisted" >&2
  exit 1
}

dead_observed=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  submitted_state="$(sql dc_content "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_id = '${submitted_event}';")"
  resubmitted_state="$(sql dc_content "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_id = '${resubmitted_event}';")"
  rejected_state="$(sql dc_content "SELECT CONCAT(status, ':', attempts) FROM outbox_event WHERE event_id = '${rejected_event}';")"
  if [[ "${submitted_state}" == "DEAD:2" && "${resubmitted_state}" == "NEW:0" \
      && "${rejected_state}" == "DEAD:2" ]]; then
    dead_observed=true
    break
  fi
  sleep 1
done
[[ "${dead_observed}" == "true" ]] || {
  echo "Outbox did not reach ordered DEAD/NEW state: submitted=${submitted_state} resubmitted=${resubmitted_state} rejected=${rejected_state}" >&2
  exit 1
}

fleet_dead_observed=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  health="$(curl -fsS "${BASE_URL}/api/admin/ops/health" -H "Authorization: Bearer ${admin_token}")"
  if node -e "const body=JSON.parse(process.argv[1]); const h=body.data; if(h?.degraded!==true || h?.degradationReason!=='OUTBOX_DEAD' || h?.outboxDead<2 || h?.outboxBlockedByDead<1 || h?.outboxScope!=='FLEET' || h?.outboxComplete!==true || h?.outboxSources?.length!==5) process.exit(2)" "${health}"; then
    fleet_dead_observed=true
    break
  fi
  sleep 1
done
[[ "${fleet_dead_observed}" == "true" ]] || {
  echo "Fleet did not converge on complete DEAD health: ${health}" >&2
  exit 1
}
dead_list="$(curl -fsS --get "${BASE_URL}/api/admin/ops/outbox" \
  -H "Authorization: Bearer ${admin_token}" --data-urlencode 'status=DEAD' --data-urlencode 'topic=post.submitted')"
node -e "const body=JSON.parse(process.argv[1]); const id=process.argv[2]; const event=body.data?.find(item=>item.eventId===id); if(!event || event.attempts!==2 || !event.lastError) process.exit(2)" "${dead_list}" "${submitted_event}"

"${COMPOSE[@]}" start kafka >/dev/null
kafka_stopped=false
wait_for_kafka
sleep 3
[[ "$(sql dc_content "SELECT status FROM outbox_event WHERE event_id = '${submitted_event}';")" == "DEAD" ]] || {
  echo "DEAD event recovered without explicit operator replay" >&2
  exit 1
}
[[ "$(sql dc_content "SELECT status FROM outbox_event WHERE event_id = '${resubmitted_event}';")" == "NEW" \
    && "$(sql dc_content "SELECT status FROM outbox_event WHERE event_id = '${rejected_event}';")" == "DEAD" ]] || {
  echo "Ordered Outbox state changed before explicit operator replay" >&2
  exit 1
}

submitted_before="$(topic_end_offset 'post.submitted')"
rejected_before="$(topic_end_offset 'content.rejected')"
replay_key="dead-outbox-replay-${unique}"
replay="$(curl -fsS -X POST "${BASE_URL}/api/admin/ops/outbox/replay?batchSize=10" \
  -H "Authorization: Bearer ${admin_token}" -H "Idempotency-Key: ${replay_key}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.attributes?.affected!==2 || body.data?.attributes?.duplicateRequest===true) process.exit(2)" "${replay}"

recovered=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  submitted_state="$(sql dc_content "SELECT status FROM outbox_event WHERE event_id = '${submitted_event}';")"
  resubmitted_state="$(sql dc_content "SELECT status FROM outbox_event WHERE event_id = '${resubmitted_event}';")"
  rejected_state="$(sql dc_content "SELECT status FROM outbox_event WHERE event_id = '${rejected_event}';")"
  if [[ "${submitted_state}" == "PUBLISHED" && "${resubmitted_state}" == "PUBLISHED" \
      && "${rejected_state}" == "PUBLISHED" ]]; then
    recovered=true
    break
  fi
  sleep 1
done
[[ "${recovered}" == "true" ]] || {
  echo "Ordered Outbox events did not recover: submitted=${submitted_state} resubmitted=${resubmitted_state} rejected=${rejected_state}" >&2
  exit 1
}

submitted_after="$(topic_end_offset 'post.submitted')"
rejected_after="$(topic_end_offset 'content.rejected')"
[[ "$((submitted_after - submitted_before))" == "2" && "$((rejected_after - rejected_before))" == "1" ]] || {
  echo "Recovered events were not published exactly once: submitted=${submitted_before}->${submitted_after} rejected=${rejected_before}->${rejected_after}" >&2
  exit 1
}

duplicate="$(curl -fsS -X POST "${BASE_URL}/api/admin/ops/outbox/replay?batchSize=10" \
  -H "Authorization: Bearer ${admin_token}" -H "Idempotency-Key: ${replay_key}")"
node -e "const body=JSON.parse(process.argv[1]); if(body.data?.attributes?.duplicateRequest!==true) process.exit(2)" "${duplicate}"
sleep 2
[[ "$(topic_end_offset 'post.submitted')" == "${submitted_after}" && "$(topic_end_offset 'content.rejected')" == "${rejected_after}" ]] || {
  echo "Idempotent Outbox replay appended duplicate records" >&2
  exit 1
}

healthy="$(curl -fsS "${BASE_URL}/api/admin/ops/health" -H "Authorization: Bearer ${admin_token}")"
node -e "const body=JSON.parse(process.argv[1]); const h=body.data; if(h?.degraded!==false || h?.outboxDead!==0 || h?.outboxBlockedByDead!==0 || h?.outboxBacklog!==0 || h?.outboxScope!=='FLEET' || h?.outboxComplete!==true) process.exit(2)" "${healthy}"
replay_audit="$(sql dc_content "SELECT COUNT(*) FROM audit_log WHERE action = 'REPLAY_OUTBOX' AND user_id = ${admin[0]};")"
[[ "${replay_audit}" == "1" ]] || { echo "Expected one audited Outbox replay, got ${replay_audit}" >&2; exit 1; }

"${COMPOSE[@]}" stop -t 10 dc-user >/dev/null
partial="$(curl -fsS "${BASE_URL}/api/admin/ops/health" -H "Authorization: Bearer ${admin_token}")"
node -e "const body=JSON.parse(process.argv[1]); const h=body.data; if(h?.degraded!==true || h?.degradationReason!=='OUTBOX_SOURCE_UNAVAILABLE' || h?.outboxComplete!==false || !h?.unavailableOutboxSources?.includes('dc-user')) process.exit(2)" "${partial}"
"${COMPOSE[@]}" start dc-user >/dev/null
wait_for_service dc-user 8102
complete_again=false
for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
  recovered_health="$(curl -fsS "${BASE_URL}/api/admin/ops/health" -H "Authorization: Bearer ${admin_token}")"
  if node -e "const body=JSON.parse(process.argv[1]); const h=body.data; if(h?.degraded!==false || h?.outboxComplete!==true || h?.unavailableOutboxSources?.length!==0) process.exit(2)" "${recovered_health}"; then
    complete_again=true
    break
  fi
  sleep 1
done
[[ "${complete_again}" == "true" ]] || { echo "Fleet health did not recover after dc-user restart" >&2; exit 1; }

echo "Outbox fleet recovery verified post=${post_id}: DEAD/NEW -> PUBLISHED/PUBLISHED, five domains complete -> partial -> complete"
