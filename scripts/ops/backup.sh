#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
BACKUP_ROOT="${BACKUP_ROOT:-${ROOT_DIR}/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

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
sha256sum "${DEST}/mysql.sql.gz" "${DEST}/minio-data.tar.gz" > "${DEST}/SHA256SUMS"

find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime +"${BACKUP_RETENTION_DAYS:-14}" -print -exec rm -rf -- {} +
echo "Backup completed: ${DEST}"
