#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.production}}"
# shellcheck source=scripts/ops/production-env.sh
source "${ROOT_DIR}/scripts/ops/production-env.sh"
load_production_env "${ENV_FILE}"
declare -n values=PRODUCTION_ENV

errors=()
fail() { errors+=("$1"); }

required=(
  IMAGE_TAG NOTIFICATION_DEPLOY_MODE DOMAIN PUBLIC_ORIGIN TLS_EMAIL MYSQL_USER MYSQL_PASSWORD MYSQL_ROOT_PASSWORD
  REDIS_PASSWORD MINIO_ROOT_USER MINIO_ROOT_PASSWORD DEVCIRCLE_JWT_SECRET
  DEVCIRCLE_INTERNAL_OPS_KEY
  ALERTMANAGER_CONFIG_PATH
)
for key in "${required[@]}"; do
  [[ -n "${values[${key}]:-}" ]] || fail "${key} is required"
done

placeholder() {
  local key="$1" value="${values[$1]:-}" lower
  lower="${value,,}"
  if [[ "${lower}" == *replace-with* || "${lower}" == *change-me* || "${lower}" == *example* || "${lower}" == *local-development* ]]; then
    fail "${key} still contains a documented placeholder"
  fi
}

secret_quality() {
  local key="$1" minimum="$2" diversity="$3" value="${values[$1]:-}" unique
  placeholder "${key}"
  [[ "${value}" != *'$'* ]] || fail "${key} must not contain dollar signs because Compose interpolates them"
  (( ${#value} >= minimum )) || fail "${key} must contain at least ${minimum} characters"
  unique="$(printf '%s' "${value}" | fold -w1 | LC_ALL=C sort -u | wc -l | tr -d '[:space:]')"
  (( unique >= diversity )) || fail "${key} does not contain enough character diversity"
}

for key in DOMAIN PUBLIC_ORIGIN TLS_EMAIL MYSQL_USER MINIO_ROOT_USER; do
  placeholder "${key}"
done

secret_quality MYSQL_PASSWORD 24 10
secret_quality MYSQL_ROOT_PASSWORD 24 10
secret_quality REDIS_PASSWORD 24 10
secret_quality MINIO_ROOT_PASSWORD 24 10
secret_quality DEVCIRCLE_JWT_SECRET 64 16
secret_quality DEVCIRCLE_INTERNAL_OPS_KEY 32 12

image_tag="${values[IMAGE_TAG]:-}"
if [[ ! "${image_tag}" =~ ^[0-9a-f]{40}$ || "${image_tag}" == "0123456789abcdef0123456789abcdef01234567" ]]; then
  fail "IMAGE_TAG must be a real lowercase 40-character commit SHA"
fi

rollback_asset_tag="${values[ROLLBACK_ASSET_TAG]:-}"
mode="${values[NOTIFICATION_DEPLOY_MODE]:-}"
case "${mode}" in
  standalone) ;;
  cutover)
    cutover_asset_tag="${values[CUTOVER_ASSET_TAG]:-}"
    [[ "${cutover_asset_tag}" =~ ^[0-9a-f]{40}$ && "${cutover_asset_tag}" != "0123456789abcdef0123456789abcdef01234567" ]] || \
      fail "CUTOVER_ASSET_TAG must be the real lowercase 40-character cutover SHA"
    [[ "${values[NOTIFICATION_CUTOVER_APPROVED]:-}" == "true" ]] || fail "NOTIFICATION_CUTOVER_APPROVED must be true in cutover mode"
    [[ "${values[NOTIFICATION_CUTOVER_EVIDENCE_VERIFIED]:-}" == "true" ]] || fail "NOTIFICATION_CUTOVER_EVIDENCE_VERIFIED must be true in cutover mode"
    ;;
  rollback)
    [[ "${rollback_asset_tag}" =~ ^[0-9a-f]{40}$ && "${rollback_asset_tag}" != "0123456789abcdef0123456789abcdef01234567" ]] || \
      fail "ROLLBACK_ASSET_TAG must be the real lowercase 40-character cutover SHA, never latest"
    [[ "${values[LEGACY_NOTIFICATION_ROLLBACK]:-}" == "true" ]] || fail "LEGACY_NOTIFICATION_ROLLBACK must be true in rollback mode"
    ;;
  *) fail "NOTIFICATION_DEPLOY_MODE must be standalone, cutover, or rollback" ;;
esac

domain="${values[DOMAIN]:-}"
[[ "${domain}" =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$ ]] || fail "DOMAIN must be a valid public hostname"
[[ "${values[PUBLIC_ORIGIN]:-}" == "https://${domain}" ]] || fail "PUBLIC_ORIGIN must equal https://DOMAIN"
[[ "${values[TLS_EMAIL]:-}" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || fail "TLS_EMAIL must be a valid email address"

alertmanager_config="${values[ALERTMANAGER_CONFIG_PATH]:-}"
[[ "${alertmanager_config}" == /* ]] || fail "ALERTMANAGER_CONFIG_PATH must be an absolute Linux path"
[[ "${alertmanager_config,,}" != *example* && "${alertmanager_config,,}" != *.invalid* ]] || \
  fail "ALERTMANAGER_CONFIG_PATH must point to an operator-managed production config"

if [[ "${values[MYSQL_PASSWORD]:-}" == "${values[MYSQL_ROOT_PASSWORD]:-}" ]]; then
  fail "MYSQL_PASSWORD and MYSQL_ROOT_PASSWORD must be different"
fi
if [[ "${values[MINIO_ROOT_PASSWORD]:-}" == "${values[DEVCIRCLE_JWT_SECRET]:-}" ]]; then
  fail "MINIO_ROOT_PASSWORD and DEVCIRCLE_JWT_SECRET must be different"
fi

github_id="${values[GITHUB_CLIENT_ID]:-}"
github_secret="${values[GITHUB_CLIENT_SECRET]:-}"
if [[ -n "${github_id}" || -n "${github_secret}" ]]; then
  [[ -n "${github_id}" && -n "${github_secret}" ]] || fail "GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET must be configured together"
  [[ "${github_secret,,}" != *replace-with* ]] || fail "GITHUB_CLIENT_SECRET still contains a documented placeholder"
fi

bounded_integer() {
  local key="$1" default_value="$2" minimum="$3" maximum="$4" value
  value="${values[$key]:-${default_value}}"
  if [[ ! "${value}" =~ ^[0-9]+$ ]] || (( value < minimum || value > maximum )); then
    fail "${key} must be an integer between ${minimum} and ${maximum}"
  fi
}

bounded_integer OUTBOX_BATCH_SIZE 50 1 500
bounded_integer OUTBOX_MAX_ATTEMPTS 8 1 100
bounded_integer OUTBOX_SEND_TIMEOUT_MS 15000 100 60000

if (( ${#errors[@]} > 0 )); then
  echo "Production environment validation failed:" >&2
  printf ' - %s\n' "${errors[@]}" >&2
  exit 1
fi

echo "Production environment validation passed for ${DOMAIN:-${domain}}"
