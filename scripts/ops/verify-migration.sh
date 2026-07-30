#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
EXPECTED_POST_COUNT="${EXPECTED_POST_COUNT:-180}"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

test -f "${ENV_FILE}" || { echo "Missing ${ENV_FILE}" >&2; exit 2; }
bash "${ROOT_DIR}/scripts/ops/validate-production-env.sh" "${ENV_FILE}" >/dev/null

COUNT_SQL='SELECT COUNT(*) FROM post;'
# MYSQL_ROOT_PASSWORD and $1 are expanded inside the container.
# shellcheck disable=SC2016
COUNT="$("${COMPOSE[@]}" exec -T mysql sh -c \
  'exec mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" dc_content -e "$1"' _ "${COUNT_SQL}")"

test "${COUNT}" = "${EXPECTED_POST_COUNT}" || {
  echo "Post count mismatch: expected ${EXPECTED_POST_COUNT}, got ${COUNT}" >&2
  exit 1
}

CONTENT_HASH_SQL="$(cat <<'SQL'
SELECT p.id,
       SHA2(CONCAT_WS(CHAR(31), p.author_id, p.type, p.theme, p.content_kind,
                      p.title, p.status, COALESCE(pc.markdown, ''),
                      COALESCE(CAST(pc.clues AS CHAR), ''), COALESCE(pc.solution, '')), 256)
FROM post p
LEFT JOIN post_content pc ON pc.post_id = p.id
ORDER BY p.id;
SQL
)"
# MYSQL_ROOT_PASSWORD and $1 are expanded inside the container.
# shellcheck disable=SC2016
CHECKSUM="$("${COMPOSE[@]}" exec -T mysql sh -c \
  'exec mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" dc_content -e "$1"' _ "${CONTENT_HASH_SQL}" \
  | sha256sum | awk '{print $1}')"

SEARCH_RESPONSE="$("${COMPOSE[@]}" exec -T elasticsearch sh -c \
  "curl --fail --silent --show-error 'http://localhost:9200/posts/_count?q=-status%3APUBLISHED'")"
UNPUBLISHED_INDEXED="$(printf '%s' "${SEARCH_RESPONSE}" | sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
[[ "${UNPUBLISHED_INDEXED}" =~ ^[0-9]+$ ]] || {
  echo "Could not parse Elasticsearch verification response" >&2
  exit 1
}
test "${UNPUBLISHED_INDEXED}" = "0" || {
  echo "Elasticsearch contains ${UNPUBLISHED_INDEXED} non-published documents" >&2
  exit 1
}

printf '{"postCount":%s,"contentChecksum":"%s","unexpectedSearchDocuments":%s}\n' \
  "${COUNT}" "${CHECKSUM}" "${UNPUBLISHED_INDEXED}"
