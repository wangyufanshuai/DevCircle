#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-${ROOT_DIR}/.env.production}}"

test -f "${ENV_FILE}" || { echo "Missing ${ENV_FILE}" >&2; exit 2; }

declare -A values=()
while IFS= read -r raw || [[ -n "${raw}" ]]; do
  raw="${raw%$'\r'}"
  [[ -z "${raw//[[:space:]]/}" || "${raw}" =~ ^[[:space:]]*# ]] && continue
  [[ "${raw}" == *=* ]] || continue
  key="${raw%%=*}"
  key="${key//[[:space:]]/}"
  values["${key}"]="${raw#*=}"
done < "${ENV_FILE}"

errors=()
fail() { errors+=("$1"); }

required=(
  IMAGE_TAG DOMAIN PUBLIC_ORIGIN TLS_EMAIL MYSQL_USER MYSQL_PASSWORD MYSQL_ROOT_PASSWORD
  REDIS_PASSWORD MINIO_ROOT_USER MINIO_ROOT_PASSWORD DEVCIRCLE_JWT_SECRET
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

image_tag="${values[IMAGE_TAG]:-}"
if [[ ! "${image_tag}" =~ ^[0-9a-f]{40}$ || "${image_tag}" == "0123456789abcdef0123456789abcdef01234567" ]]; then
  fail "IMAGE_TAG must be a real lowercase 40-character commit SHA"
fi

domain="${values[DOMAIN]:-}"
[[ "${domain}" =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$ ]] || fail "DOMAIN must be a valid public hostname"
[[ "${values[PUBLIC_ORIGIN]:-}" == "https://${domain}" ]] || fail "PUBLIC_ORIGIN must equal https://DOMAIN"
[[ "${values[TLS_EMAIL]:-}" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || fail "TLS_EMAIL must be a valid email address"

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
