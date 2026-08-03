#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
BACKUP_ROOT="${BACKUP_ROOT:-${ROOT_DIR}/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

# shellcheck source=scripts/ops/content-baseline.sh
source "${ROOT_DIR}/scripts/ops/content-baseline.sh"

test -f "${ENV_FILE}" || { echo "Missing ${ENV_FILE}" >&2; exit 2; }
bash "${ROOT_DIR}/scripts/ops/validate-production-env.sh" "${ENV_FILE}" >/dev/null
mkdir -p "${BACKUP_ROOT}"
BACKUP_ROOT="$(cd "${BACKUP_ROOT}" && pwd -P)"
case "${BACKUP_ROOT}" in
  /|/home|/srv|/var) echo "Unsafe BACKUP_ROOT: ${BACKUP_ROOT}" >&2; exit 2 ;;
esac
DEST="${BACKUP_ROOT}/${STAMP}"
umask 077
mkdir -p "${DEST}"
BACKUP_COMPLETE=false
cleanup_incomplete_backup() {
  if [[ "${BACKUP_COMPLETE}" != "true" && -d "${DEST}" ]]; then
    rm -rf -- "${DEST}"
  fi
}
trap cleanup_incomplete_backup EXIT

load_content_baseline
BASELINE_POST_COUNT="${CONTENT_POST_COUNT}"
BASELINE_PUBLISHED_POST_COUNT="${CONTENT_PUBLISHED_POST_COUNT}"
BASELINE_CONTENT_CHECKSUM="${CONTENT_CHECKSUM}"

# MYSQL_ROOT_PASSWORD is expanded inside the container.
# shellcheck disable=SC2016
"${COMPOSE[@]}" exec -T mysql sh -c '
  exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
    --single-transaction --quick --routines --events --triggers --hex-blob \
    --databases dc_auth dc_user dc_content dc_comment dc_interaction dc_notification dc_file dc_search
' | gzip -9 > "${DEST}/mysql.sql.gz"

"${COMPOSE[@]}" run --rm --no-deps -T minio-maintenance \
  sh -c 'tar -C /minio-data -czf - .' > "${DEST}/minio-data.tar.gz"
test -s "${DEST}/mysql.sql.gz" -a -s "${DEST}/minio-data.tar.gz" || {
  echo "Backup output is incomplete: ${DEST}" >&2
  exit 1
}

# Reject a backup when content changed while MySQL and MinIO were captured.
# This avoids publishing a manifest that describes a different content state.
assert_content_baseline \
  "${BASELINE_POST_COUNT}" \
  "${BASELINE_PUBLISHED_POST_COUNT}" \
  "${BASELINE_CONTENT_CHECKSUM}"

MYSQL_SHA256="$(sha256sum "${DEST}/mysql.sql.gz" | awk '{print $1}')"
MINIO_SHA256="$(sha256sum "${DEST}/minio-data.tar.gz" | awk '{print $1}')"
IMAGE_TAG="$(sed -n 's/^IMAGE_TAG=//p' "${ENV_FILE}" | tail -n 1 | tr -d '\r')"
GIT_COMMIT="${GIT_COMMIT:-$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || true)}"
GIT_COMMIT="${GIT_COMMIT:-${IMAGE_TAG}}"
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

[[ "${IMAGE_TAG}" =~ ^[0-9a-fA-F]{40}$ ]] || {
  echo "Could not record a valid IMAGE_TAG in the backup manifest" >&2
  exit 1
}
[[ "${GIT_COMMIT}" =~ ^[0-9a-fA-F]{40}$ ]] || {
  echo "Could not record a valid Git commit in the backup manifest" >&2
  exit 1
}

cat > "${DEST}/backup-manifest.json" <<EOF
{
  "schemaVersion": 1,
  "createdAt": "${CREATED_AT}",
  "gitCommit": "${GIT_COMMIT}",
  "imageTag": "${IMAGE_TAG}",
  "postCount": ${BASELINE_POST_COUNT},
  "publishedPostCount": ${BASELINE_PUBLISHED_POST_COUNT},
  "contentHash": "${BASELINE_CONTENT_CHECKSUM}",
  "mysqlSha256": "${MYSQL_SHA256}",
  "minioSha256": "${MINIO_SHA256}"
}
EOF

(
  cd "${DEST}"
  sha256sum mysql.sql.gz minio-data.tar.gz backup-manifest.json > SHA256SUMS
)

BACKUP_COMPLETE=true

find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime +"${BACKUP_RETENTION_DAYS:-14}" -print -exec rm -rf -- {} +
echo "Backup completed: ${DEST}"
