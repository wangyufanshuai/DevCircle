#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
BACKUP_DIR="${1:-}"
MANAGE_APPLICATIONS="${RESTORE_MANAGE_APPLICATIONS:-true}"
COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml")
if [[ "${LEGACY_AUTH_ROLLBACK:-false}" == "true" ]]; then
  COMPOSE+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-auth-rollback.yml")
elif [[ "${LEGACY_AUTH_ROLLBACK:-false}" != "false" ]]; then
  echo "LEGACY_AUTH_ROLLBACK must be true or false" >&2
  exit 2
fi

# shellcheck source=scripts/ops/backup-manifest.sh
source "${ROOT_DIR}/scripts/ops/backup-manifest.sh"
# shellcheck source=scripts/ops/content-baseline.sh
source "${ROOT_DIR}/scripts/ops/content-baseline.sh"

test -n "${BACKUP_DIR}" || { echo "Usage: CONFIRM_RESTORE=RESTORE $0 <backup-directory>" >&2; exit 2; }
test "${CONFIRM_RESTORE:-}" = "RESTORE" || { echo "Set CONFIRM_RESTORE=RESTORE to continue" >&2; exit 2; }
test -f "${ENV_FILE}" || { echo "Missing ${ENV_FILE}" >&2; exit 2; }
bash "${ROOT_DIR}/scripts/ops/validate-production-env.sh" "${ENV_FILE}" >/dev/null
BACKUP_DIR="$(cd "${BACKUP_DIR}" && pwd -P)"
test -f "${BACKUP_DIR}/mysql.sql.gz" -a -f "${BACKUP_DIR}/minio-data.tar.gz" -a -f "${BACKUP_DIR}/SHA256SUMS" || {
  echo "Incomplete backup" >&2
  exit 2
}
(cd "${BACKUP_DIR}" && sha256sum -c SHA256SUMS)

HAS_MANIFEST=false
if [[ -f "${BACKUP_DIR}/backup-manifest.json" ]]; then
  verify_manifest_artifacts "${BACKUP_DIR}"
  HAS_MANIFEST=true
elif [[ "${ALLOW_LEGACY_BACKUP:-false}" == "true" ]]; then
  echo "WARNING: restoring a legacy backup without a business data manifest" >&2
else
  echo "Backup has no backup-manifest.json; set ALLOW_LEGACY_BACKUP=true only for a verified legacy backup" >&2
  exit 2
fi

APP_SERVICES=(web web-static dc-gateway dc-identity dc-content-aggregate dc-discovery dc-user dc-content dc-comment dc-interaction dc-search dc-recommend dc-notification dc-file dc-admin)
if [[ "${LEGACY_AUTH_ROLLBACK:-false}" == "true" ]]; then
  APP_SERVICES+=(dc-auth)
fi
if [[ "${MANAGE_APPLICATIONS}" == "true" ]]; then
  "${COMPOSE[@]}" stop "${APP_SERVICES[@]}"
elif [[ "${MANAGE_APPLICATIONS}" != "false" ]]; then
  echo "RESTORE_MANAGE_APPLICATIONS must be true or false" >&2
  exit 2
fi

# MYSQL_ROOT_PASSWORD is expanded inside the container.
# shellcheck disable=SC2016
gunzip -c "${BACKUP_DIR}/mysql.sql.gz" | "${COMPOSE[@]}" exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'

if [[ "${HAS_MANIFEST}" == "true" ]]; then
  assert_content_baseline \
    "${MANIFEST_POST_COUNT}" \
    "${MANIFEST_PUBLISHED_POST_COUNT}" \
    "${MANIFEST_CONTENT_HASH}"
fi

"${COMPOSE[@]}" run --rm --no-deps -T minio-maintenance sh -c '
  test -d /minio-data
  find /minio-data -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  tar -C /minio-data -xzf -
' < "${BACKUP_DIR}/minio-data.tar.gz"

if [[ "${MANAGE_APPLICATIONS}" == "true" ]]; then
  "${COMPOSE[@]}" start "${APP_SERVICES[@]}"
fi
echo "Restore completed from ${BACKUP_DIR}"
