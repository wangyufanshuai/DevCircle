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
DOMAIN=mysticverse.test
PUBLIC_ORIGIN=https://mysticverse.test
TLS_EMAIL=ops@mysticverse.test
MYSQL_USER=mv_prod_operator
MYSQL_PASSWORD=MySQL-7u!pR2#xV9@cK4$zN8qL
MYSQL_ROOT_PASSWORD=RootDB-3m@T8!wQ5#jH7$xP2vC
REDIS_PASSWORD=Redis-6k#Y1@bN9!sF4$xU8qW
MINIO_ROOT_USER=mv_object_admin
MINIO_ROOT_PASSWORD=Object-9z!D2@rM7#pL4$xC6vB
DEVCIRCLE_JWT_SECRET=Aa1!Bb2@Cc3#Dd4$Ee5%Ff6^Gg7&Hh8*Ii9(Jj0)Kk1_Ll2+Mm3-Nn4=Oo5?Pp6Qq7~
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
TRACING_SAMPLE_RATE=0.1
EOF

bash "${VALIDATOR}" "${TEMP_ENV}" >/dev/null
echo "Production environment validator tests passed"
