#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VALIDATOR="${ROOT_DIR}/scripts/ops/validate-production-env.sh"
TEMP_ENV="$(mktemp)"
trap 'rm -f "${TEMP_ENV}"' EXIT

if bash "${VALIDATOR}" "${ROOT_DIR}/.env.production.example" >/dev/null 2>&1; then
  echo "Expected the documented placeholder environment to be rejected" >&2
  exit 1
fi

cat > "${TEMP_ENV}" <<'EOF'
IMAGE_TAG=4f1a9c2e8b7d6a5f0e3c2b1a9876543210abcdef
NOTIFICATION_DEPLOY_MODE=standalone
DOMAIN=mysticverse.test
PUBLIC_ORIGIN=https://mysticverse.test
TLS_EMAIL=ops@mysticverse.test
MYSQL_USER=mv_prod_operator
MYSQL_PASSWORD=MySQL-7u!pR2#xV9@cK4~zN8qL
MYSQL_ROOT_PASSWORD=RootDB-3m@T8!wQ5#jH7~xP2vC
REDIS_PASSWORD=Redis-6k#Y1@bN9!sF4~xU8qW
MINIO_ROOT_USER=mv_object_admin
MINIO_ROOT_PASSWORD=Object-9z!D2@rM7#pL4~xC6vB
DEVCIRCLE_JWT_SECRET=Aa1!Bb2@Cc3#Dd4~Ee5%Ff6^Gg7&Hh8*Ii9(Jj0)Kk1_Ll2+Mm3-Nn4=Oo5?Pp6Qq7~
DEVCIRCLE_INTERNAL_OPS_KEY=Ops-4w!N8@kR2#sV7~xM5qL9cD1pT6yH
ALERTMANAGER_CONFIG_PATH=/srv/devcircle/secrets/alertmanager.yml
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
TRACING_SAMPLE_RATE=0.1
EOF

bash "${VALIDATOR}" "${TEMP_ENV}" >/dev/null

expect_mode_failure() {
  local mode="$1" additions="$2" expected="$3" mode_env output
  mode_env="$(mktemp)"
  sed '/^NOTIFICATION_DEPLOY_MODE=/d' "${TEMP_ENV}" > "${mode_env}"
  printf 'NOTIFICATION_DEPLOY_MODE=%s\n%s\n' "${mode}" "${additions}" >> "${mode_env}"
  if output="$(bash "${VALIDATOR}" "${mode_env}" 2>&1)"; then
    rm -f "${mode_env}"
    echo "Expected ${mode} environment to fail" >&2
    exit 1
  fi
  grep -q "${expected}" <<<"${output}" || { echo "Missing expected failure: ${expected}" >&2; rm -f "${mode_env}"; exit 1; }
  rm -f "${mode_env}"
}

expect_mode_failure cutover 'NOTIFICATION_CUTOVER_APPROVED=true
NOTIFICATION_CUTOVER_EVIDENCE_VERIFIED=true' CUTOVER_ASSET_TAG
expect_mode_failure cutover 'CUTOVER_ASSET_TAG=5a2b8d3f9c7e6a4b1f0d2c3e9876543210abcdef
NOTIFICATION_CUTOVER_EVIDENCE_VERIFIED=true' NOTIFICATION_CUTOVER_APPROVED
expect_mode_failure cutover 'CUTOVER_ASSET_TAG=5a2b8d3f9c7e6a4b1f0d2c3e9876543210abcdef
NOTIFICATION_CUTOVER_APPROVED=true' NOTIFICATION_CUTOVER_EVIDENCE_VERIFIED
expect_mode_failure rollback 'LEGACY_NOTIFICATION_ROLLBACK=true' ROLLBACK_ASSET_TAG

expect_invalid_outbox_value() {
  local key="$1" value="$2" invalid_env
  invalid_env="$(mktemp)"
  cp "${TEMP_ENV}" "${invalid_env}"
  printf '%s=%s\n' "${key}" "${value}" >> "${invalid_env}"
  if bash "${VALIDATOR}" "${invalid_env}" >/dev/null 2>&1; then
    rm -f "${invalid_env}"
    echo "Expected ${key}=${value} to be rejected" >&2
    exit 1
  fi
  rm -f "${invalid_env}"
}

expect_invalid_outbox_value OUTBOX_BATCH_SIZE 501
expect_invalid_outbox_value OUTBOX_MAX_ATTEMPTS 0
expect_invalid_outbox_value OUTBOX_SEND_TIMEOUT_MS not-a-number

# The literal dollar sign is the unsafe Compose input under test.
# shellcheck disable=SC2016
printf '%s\n' 'MYSQL_PASSWORD=Unsafe-Compose-$PASSWORD-Interpolation-Value' >> "${TEMP_ENV}"
if bash "${VALIDATOR}" "${TEMP_ENV}" >/dev/null 2>&1; then
  echo "Expected a dollar sign in a Compose secret to be rejected" >&2
  exit 1
fi
echo "Production environment validator tests passed"
