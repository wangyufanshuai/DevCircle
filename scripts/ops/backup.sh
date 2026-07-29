#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
BACKUP_ROOT="${BACKUP_ROOT:-${ROOT_DIR}/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${BACKUP_ROOT}/${STAMP}"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

test -f "${ENV_FILE}" || { echo "Missing ${ENV_FILE}" >&2; exit 2; }
mkdir -p "${DEST}"

"${COMPOSE[@]}" exec -T mysql sh -c '
  exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" +    --single-transaction --routines --events --triggers --hex-blob +    --databases dc_auth dc_user dc_content dc_comment dc_interaction dc_notification dc_file dc_search
' | gzip -9 > "${DEST}/mysql.sql.gz"

"${COMPOSE[@]}" exec -T minio sh -c 'tar -C /data -czf - .' > "${DEST}/minio-data.tar.gz"
sha256sum "${DEST}/mysql.sql.gz" "${DEST}/minio-data.tar.gz" > "${DEST}/SHA256SUMS"

find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime +"${BACKUP_RETENTION_DAYS:-14}" -print -exec rm -rf -- {} +
echo "Backup completed: ${DEST}"
