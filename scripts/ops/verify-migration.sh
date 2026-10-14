#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
EXPECTED_POST_COUNT="${EXPECTED_POST_COUNT:-180}"
BACKUP_MANIFEST="${BACKUP_MANIFEST:-}"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

# shellcheck source=scripts/ops/backup-manifest.sh
source "${ROOT_DIR}/scripts/ops/backup-manifest.sh"
# shellcheck source=scripts/ops/content-baseline.sh
source "${ROOT_DIR}/scripts/ops/content-baseline.sh"

test -f "${ENV_FILE}" || { echo "Missing ${ENV_FILE}" >&2; exit 2; }
bash "${ROOT_DIR}/scripts/ops/validate-production-env.sh" "${ENV_FILE}" >/dev/null

if [[ -n "${BACKUP_MANIFEST}" ]]; then
  BACKUP_MANIFEST="$(cd "$(dirname "${BACKUP_MANIFEST}")" && pwd -P)/$(basename "${BACKUP_MANIFEST}")"
  test "$(basename "${BACKUP_MANIFEST}")" = "backup-manifest.json" || {
    echo "BACKUP_MANIFEST must point to backup-manifest.json" >&2
    exit 2
  }
  verify_manifest_artifacts "$(dirname "${BACKUP_MANIFEST}")"
  EXPECTED_POST_COUNT="${MANIFEST_POST_COUNT}"
fi

load_content_baseline
COUNT="${CONTENT_POST_COUNT}"
PUBLISHED_COUNT="${CONTENT_PUBLISHED_POST_COUNT}"
CHECKSUM="${CONTENT_CHECKSUM}"

test "${COUNT}" = "${EXPECTED_POST_COUNT}" || {
  echo "Post count mismatch: expected ${EXPECTED_POST_COUNT}, got ${COUNT}" >&2
  exit 1
}

if [[ -n "${BACKUP_MANIFEST}" ]]; then
  test "${PUBLISHED_COUNT}" = "${MANIFEST_PUBLISHED_POST_COUNT}" || {
    echo "Published post count mismatch: expected ${MANIFEST_PUBLISHED_POST_COUNT}, got ${PUBLISHED_COUNT}" >&2
    exit 1
  }
  test "${CHECKSUM}" = "${MANIFEST_CONTENT_HASH}" || {
    echo "Content checksum mismatch: expected ${MANIFEST_CONTENT_HASH}, got ${CHECKSUM}" >&2
    exit 1
  }
fi

INDEX_RESPONSE="$("${COMPOSE[@]}" exec -T elasticsearch sh -c \
  "curl --fail --silent --show-error 'http://localhost:9200/devcircle-posts-v2/_count?q=status%3APUBLISHED'")"
INDEXED_DOCUMENTS="$(printf '%s' "${INDEX_RESPONSE}" | sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
[[ "${INDEXED_DOCUMENTS}" =~ ^[0-9]+$ ]] || {
  echo "Could not parse Elasticsearch document count" >&2
  exit 1
}
test "${INDEXED_DOCUMENTS}" = "${PUBLISHED_COUNT}" || {
  echo "Elasticsearch document count mismatch: expected ${PUBLISHED_COUNT}, got ${INDEXED_DOCUMENTS}" >&2
  exit 1
}

SEARCH_RESPONSE="$("${COMPOSE[@]}" exec -T elasticsearch sh -c \
  "curl --fail --silent --show-error 'http://localhost:9200/devcircle-posts-v2/_count?q=-status%3APUBLISHED%20AND%20-status%3ADELETED'")"
UNPUBLISHED_INDEXED="$(printf '%s' "${SEARCH_RESPONSE}" | sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
[[ "${UNPUBLISHED_INDEXED}" =~ ^[0-9]+$ ]] || {
  echo "Could not parse Elasticsearch verification response" >&2
  exit 1
}
test "${UNPUBLISHED_INDEXED}" = "0" || {
  echo "Elasticsearch contains ${UNPUBLISHED_INDEXED} unexpected non-published documents (excluding versioned tombstones)" >&2
  exit 1
}

VERSION_RESPONSE="$("${COMPOSE[@]}" exec -T elasticsearch sh -c \
  "curl --fail --silent --show-error 'http://localhost:9200/devcircle-posts-v2/_count?q=-_exists_%3AaggregateVersion'")"
UNVERSIONED="$(printf '%s' "${VERSION_RESPONSE}" | sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
[[ "${UNVERSIONED}" == "0" ]] || {
  echo "Elasticsearch contains unversioned documents or returned an invalid count" >&2
  exit 1
}

printf '{"postCount":%s,"publishedPostCount":%s,"indexedSearchDocuments":%s,"contentChecksum":"%s","unexpectedSearchDocuments":%s}\n' \
  "${COUNT}" "${PUBLISHED_COUNT}" "${INDEXED_DOCUMENTS}" "${CHECKSUM}" "${UNPUBLISHED_INDEXED}"
