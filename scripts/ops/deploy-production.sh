#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.production}"
# shellcheck source=scripts/ops/production-env.sh
source "${ROOT_DIR}/scripts/ops/production-env.sh"
load_production_env "${ENV_FILE}"
MODE="$(production_env_value NOTIFICATION_DEPLOY_MODE)"
BASE=(-f "${ROOT_DIR}/docker-compose.prod.yml")
CUTOVER=(-f "${ROOT_DIR}/docker-compose.prod.yml" -f "${ROOT_DIR}/deploy/compose/docker-compose.notification-identity-cutover.yml")
ROLLBACK=(-f "${ROOT_DIR}/docker-compose.prod.yml" -f "${ROOT_DIR}/deploy/compose/docker-compose.legacy-notification-rollback.yml")

fail() { echo "Notification deployment gate failed: $*" >&2; exit 2; }

bash "${ROOT_DIR}/scripts/ops/validate-production-env.sh" "${ENV_FILE}"
production_compose "${ENV_FILE}" "${BASE[@]}" config --quiet

case "${MODE}" in
  standalone)
    # This is intentionally the default. If the host previously ran cutover,
    # recreate Identity with all five owners disabled before starting standalone.
    production_compose "${ENV_FILE}" "${BASE[@]}" pull
    ENV_FILE="${ENV_FILE}" bash "${ROOT_DIR}/scripts/ops/verify-release-identity.sh"
    production_compose "${ENV_FILE}" "${BASE[@]}" stop dc-notification || true
    production_compose "${ENV_FILE}" "${BASE[@]}" rm -f -s dc-notification || true
    production_compose "${ENV_FILE}" "${BASE[@]}" up -d --no-deps --force-recreate --wait dc-identity
    production_compose "${ENV_FILE}" "${BASE[@]}" up -d --no-deps --force-recreate --wait dc-notification
    production_compose "${ENV_FILE}" "${BASE[@]}" up -d --remove-orphans
    ;;
  cutover)
    production_compose "${ENV_FILE}" "${CUTOVER[@]}" config --quiet
    production_compose "${ENV_FILE}" "${CUTOVER[@]}" pull
    ENV_FILE="${ENV_FILE}" bash "${ROOT_DIR}/scripts/ops/verify-release-identity.sh"
    # Exactly-one ordering: old API/consumer/migrator is gone before Identity
    # is recreated with its five Notification ownership switches enabled.
    production_compose "${ENV_FILE}" "${BASE[@]}" stop dc-notification
    production_compose "${ENV_FILE}" "${BASE[@]}" rm -f -s dc-notification
    production_compose "${ENV_FILE}" "${CUTOVER[@]}" up -d --no-deps --force-recreate --wait dc-identity
    production_compose "${ENV_FILE}" "${CUTOVER[@]}" up -d --remove-orphans
    ;;
  rollback) fail "rollback mode must use scripts/ops/rollback.sh" ;;
  *) fail "NOTIFICATION_DEPLOY_MODE must be standalone, cutover, or rollback" ;;
esac

echo "Production deployment completed in Notification mode=${MODE}"
