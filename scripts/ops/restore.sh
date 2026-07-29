#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
BACKUP_DIR="${1:-}"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")

test -n "${BACKUP_DIR}" || { echo "Usage: CONFIRM_RESTORE=RESTORE $0 <backup-directory>" >&2; exit 2; }
test "${CONFIRM_RESTORE:-}" = "RESTORE" || { echo "Set CONFIRM_RESTORE=RESTORE to continue" >&2; exit 2; }
test -f "${BACKUP_DIR}/mysql.sql.gz" -a -f "${BACKUP_DIR}/minio-data.tar.gz" || { echo "Incomplete backup" >&2; exit 2; }
(cd "${BACKUP_DIR}" && sha256sum -c SHA256SUMS)

APP_SERVICES=(web web-static dc-gateway dc-identity dc-content-aggregate dc-discovery dc-auth dc-user dc-content dc-comment dc-interaction dc-search dc-recommend dc-notification dc-file dc-admin)
"${COMPOSE[@]}" stop "${APP_SERVICES[@]}"

gunzip -c "${BACKUP_DIR}/mysql.sql.gz" | "${COMPOSE[@]}" exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
"${COMPOSE[@]}" exec -T minio sh -c 'rm -rf /data/* && tar -C /data -xzf -' < "${BACKUP_DIR}/minio-data.tar.gz"

"${COMPOSE[@]}" start "${APP_SERVICES[@]}"
echo "Restore completed from ${BACKUP_DIR}"
