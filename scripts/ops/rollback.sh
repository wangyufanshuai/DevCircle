#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
PREVIOUS_IMAGE_TAG="${PREVIOUS_IMAGE_TAG:-}"
BACKUP_DIR="${1:-}"

test -n "${PREVIOUS_IMAGE_TAG}" || { echo "PREVIOUS_IMAGE_TAG is required" >&2; exit 2; }
test -n "${BACKUP_DIR}" || { echo "Usage: PREVIOUS_IMAGE_TAG=<sha> $0 <pre-migration-backup>" >&2; exit 2; }

export IMAGE_TAG="${PREVIOUS_IMAGE_TAG}"
ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE "${ROOT_DIR}/scripts/ops/restore.sh" "${BACKUP_DIR}"
docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml" up -d --no-deps dc-auth dc-user dc-content dc-comment dc-interaction dc-search dc-recommend dc-notification dc-file dc-admin dc-identity dc-content-aggregate dc-discovery dc-gateway web-static web
echo "Rollback completed with image ${PREVIOUS_IMAGE_TAG}"
