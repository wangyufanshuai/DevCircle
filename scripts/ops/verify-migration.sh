#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
EXPECTED_POST_COUNT="${EXPECTED_POST_COUNT:-180}"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

read -r COUNT CHECKSUM < <("${COMPOSE[@]}" exec -T mysql sh -c '
  mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" dc_content -e "
    SELECT COUNT(*),
           COALESCE(SUM(CRC32(CONCAT_WS(CHAR(31), id, author_id, type, theme, content_kind, title, status))), 0)
    FROM post;"
')

test "${COUNT}" = "${EXPECTED_POST_COUNT}" || {
  echo "Post count mismatch: expected ${EXPECTED_POST_COUNT}, got ${COUNT}" >&2
  exit 1
}

UNPUBLISHED_INDEXED="$("${COMPOSE[@]}" exec -T elasticsearch sh -c +  "curl -fsS 'http://localhost:9200/posts/_count?q=-status:PUBLISHED' 2>/dev/null || true")"

printf '{"postCount":%s,"contentChecksum":"%s","unexpectedSearchDocuments":%s}\n' +  "${COUNT}" "${CHECKSUM}" "${UNPUBLISHED_INDEXED:-null}"
