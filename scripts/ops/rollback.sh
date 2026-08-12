#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
BACKUP_DIR="${1:-}"
# shellcheck source=scripts/ops/production-env.sh
source "${ROOT_DIR}/scripts/ops/production-env.sh"
load_production_env "${ENV_FILE}"
MODE="$(production_env_value NOTIFICATION_DEPLOY_MODE)"
IMAGE_TAG="$(production_env_value IMAGE_TAG)"
ROLLBACK_ASSET_TAG="$(production_env_value ROLLBACK_ASSET_TAG)"
LEGACY_NOTIFICATION_ROLLBACK="$(production_env_value LEGACY_NOTIFICATION_ROLLBACK false)"

[[ "${MODE}" == "rollback" ]] || { echo "NOTIFICATION_DEPLOY_MODE=rollback is required in ${ENV_FILE}" >&2; exit 2; }
[[ "${IMAGE_TAG}" =~ ^[0-9a-f]{40}$ ]] || { echo "IMAGE_TAG must identify the previous business release" >&2; exit 2; }
[[ "${LEGACY_NOTIFICATION_ROLLBACK}" == "true" ]] || { echo "LEGACY_NOTIFICATION_ROLLBACK=true is required in ${ENV_FILE}" >&2; exit 2; }
[[ "${ROLLBACK_ASSET_TAG}" =~ ^[0-9a-f]{40}$ ]] || { echo "ROLLBACK_ASSET_TAG must identify the cutover-built legacy assets" >&2; exit 2; }
test -n "${BACKUP_DIR}" || { echo "Usage: ENV_FILE=.env.rollback $0 <pre-migration-backup>" >&2; exit 2; }

bash "${ROOT_DIR}/scripts/ops/validate-production-env.sh" "${ENV_FILE}"
production_compose "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-notification-rollback.yml" config --quiet
production_compose "${ENV_FILE}" -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-notification-rollback.yml" pull
ENV_FILE="${ENV_FILE}" bash "${ROOT_DIR}/scripts/ops/verify-release-identity.sh"
COMPOSE_FILES=(-f "${ROOT_DIR}/docker-compose.prod.yml" -f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-notification-rollback.yml")
ROLLBACK_SERVICES=(dc-comment dc-recommend dc-notification dc-admin dc-identity dc-content-aggregate dc-discovery dc-gateway web-static web)
LEGACY_AUTH_ROLLBACK="$(production_env_value LEGACY_AUTH_ROLLBACK false)"
LEGACY_USER_ROLLBACK="$(production_env_value LEGACY_USER_ROLLBACK false)"
LEGACY_INTERACTION_ROLLBACK="$(production_env_value LEGACY_INTERACTION_ROLLBACK false)"
LEGACY_CONTENT_ROLLBACK="$(production_env_value LEGACY_CONTENT_ROLLBACK false)"
LEGACY_SEARCH_ROLLBACK="$(production_env_value LEGACY_SEARCH_ROLLBACK false)"
if [[ "${LEGACY_AUTH_ROLLBACK}" == "true" ]]; then
  COMPOSE_FILES+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-auth-rollback.yml")
  ROLLBACK_SERVICES=(dc-auth "${ROLLBACK_SERVICES[@]}")
elif [[ "${LEGACY_AUTH_ROLLBACK}" != "false" ]]; then
  echo "LEGACY_AUTH_ROLLBACK must be true or false" >&2; exit 2
fi
if [[ "${LEGACY_USER_ROLLBACK}" == "true" ]]; then
  COMPOSE_FILES+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-user-rollback.yml")
  ROLLBACK_SERVICES+=(dc-user)
elif [[ "${LEGACY_USER_ROLLBACK}" != "false" ]]; then
  echo "LEGACY_USER_ROLLBACK must be true or false" >&2; exit 2
fi
if [[ "${LEGACY_INTERACTION_ROLLBACK}" == "true" ]]; then
  COMPOSE_FILES+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-interaction-rollback.yml")
  ROLLBACK_SERVICES+=(dc-interaction)
elif [[ "${LEGACY_INTERACTION_ROLLBACK}" != "false" ]]; then
  echo "LEGACY_INTERACTION_ROLLBACK must be true or false" >&2; exit 2
fi
if [[ "${LEGACY_CONTENT_ROLLBACK}" == "true" ]]; then
  COMPOSE_FILES+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-content-rollback.yml")
  ROLLBACK_SERVICES+=(dc-content dc-file)
elif [[ "${LEGACY_CONTENT_ROLLBACK}" != "false" ]]; then
  echo "LEGACY_CONTENT_ROLLBACK must be true or false" >&2; exit 2
fi
if [[ "${LEGACY_SEARCH_ROLLBACK}" == "true" ]]; then
  COMPOSE_FILES+=(-f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-search-rollback.yml")
  ROLLBACK_SERVICES+=(dc-search)
elif [[ "${LEGACY_SEARCH_ROLLBACK}" != "false" ]]; then
  echo "LEGACY_SEARCH_ROLLBACK must be true or false" >&2; exit 2
fi
ENV_FILE="${ENV_FILE}" CONFIRM_RESTORE=RESTORE \
  "${ROOT_DIR}/scripts/ops/restore.sh" "${BACKUP_DIR}"
production_compose "${ENV_FILE}" "${COMPOSE_FILES[@]}" up -d --no-deps --force-recreate --remove-orphans "${ROLLBACK_SERVICES[@]}"
echo "Notification rollback completed with business image ${IMAGE_TAG} and rollback assets ${ROLLBACK_ASSET_TAG}"
