#!/usr/bin/env bash

# Shared content integrity queries for backup, restore, and migration checks.
# The caller must define COMPOSE as a docker compose command array.

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

content_mysql_query() {
  local sql="$1"
  # MYSQL_ROOT_PASSWORD and $1 are expanded inside the container.
  # shellcheck disable=SC2016
  "${COMPOSE[@]}" exec -T mysql sh -c \
    'exec mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" dc_content -e "$1"' _ "${sql}"
}

load_content_baseline() {
  CONTENT_POST_COUNT="$(content_mysql_query 'SELECT COUNT(*) FROM post;')"
  CONTENT_PUBLISHED_POST_COUNT="$(content_mysql_query "SELECT COUNT(*) FROM post WHERE status = 'PUBLISHED';")"
  CONTENT_CHECKSUM="$(content_mysql_query "${CONTENT_HASH_SQL}" | sha256sum | awk '{print $1}')"

  [[ "${CONTENT_POST_COUNT}" =~ ^[0-9]+$ ]] || {
    echo "Could not parse post count" >&2
    return 1
  }
  [[ "${CONTENT_PUBLISHED_POST_COUNT}" =~ ^[0-9]+$ ]] || {
    echo "Could not parse published post count" >&2
    return 1
  }
  [[ "${CONTENT_CHECKSUM}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "Could not calculate content checksum" >&2
    return 1
  }
}

assert_content_baseline() {
  local expected_post_count="$1"
  local expected_published_post_count="$2"
  local expected_content_checksum="$3"

  load_content_baseline
  test "${CONTENT_POST_COUNT}" = "${expected_post_count}" || {
    echo "Post count mismatch: expected ${expected_post_count}, got ${CONTENT_POST_COUNT}" >&2
    return 1
  }
  test "${CONTENT_PUBLISHED_POST_COUNT}" = "${expected_published_post_count}" || {
    echo "Published post count mismatch: expected ${expected_published_post_count}, got ${CONTENT_PUBLISHED_POST_COUNT}" >&2
    return 1
  }
  test "${CONTENT_CHECKSUM}" = "${expected_content_checksum}" || {
    echo "Content checksum mismatch: expected ${expected_content_checksum}, got ${CONTENT_CHECKSUM}" >&2
    return 1
  }
}
