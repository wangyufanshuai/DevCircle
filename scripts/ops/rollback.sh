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
COMPOSE_FILES=(-f "${ROOT_DIR}/docker-compose.prod.yml")
ROLLBACK_SERVICES=(dc-user dc-content dc-comment dc-interaction dc-recommend dc-notification dc-file dc-admin dc-identity dc-content-aggregate dc-discovery dc-gateway web-static web)
if [[ "${LEGACY_AUTH_ROLLBACK:-false}" == "true" ]]; then
  COMPOSE_FILES+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-auth-rollback.yml")
  ROLLBACK_SERVICES=(dc-auth "${ROLLBACK_SERVICES[@]}")
elif [[ "${LEGACY_AUTH_ROLLBACK:-false}" != "false" ]]; then
  echo "LEGACY_AUTH_ROLLBACK must be true or false" >&2
  exit 2
fi
if [[ "${LEGACY_SEARCH_ROLLBACK:-false}" == "true" ]]; then
  COMPOSE_FILES+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-search-rollback.yml")
  ROLLBACK_SERVICES+=(dc-search)
elif [[ "${LEGACY_SEARCH_ROLLBACK:-false}" != "false" ]]; then
  echo "LEGACY_SEARCH_ROLLBACK must be true or false" >&2
  exit 2
fi
docker compose --env-file "${ENV_FILE}" "${COMPOSE_FILES[@]}" up -d --no-deps "${ROLLBACK_SERVICES[@]}"
echo "Rollback completed with image ${PREVIOUS_IMAGE_TAG}"
