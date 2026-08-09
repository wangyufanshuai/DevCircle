#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE="${1:-}"
COSIGN_IDENTITY_REGEXP="${COSIGN_IDENTITY_REGEXP:-^https://github.com/wangyufanshuai/devcircle/.github/workflows/ci.yml@refs/heads/main$}"
COSIGN_OIDC_ISSUER="${COSIGN_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"
VERIFY_ATTEMPTS="${VERIFY_ATTEMPTS:-5}"
VERIFY_RETRY_SECONDS="${VERIFY_RETRY_SECONDS:-3}"

fail() {
  echo "Image identity verification failed: $*" >&2
  exit 1
}

[[ "$(uname -s)" == "Linux" ]] || fail "must run on Linux"
command -v cosign >/dev/null 2>&1 || fail "cosign is required"
[[ "${IMAGE}" =~ ^ghcr\.io/wangyufanshuai/devcircle/[a-z0-9-]+@sha256:[0-9a-f]{64}$ ]] || \
  fail "image must be an immutable DevCircle GHCR digest"
[[ "${VERIFY_ATTEMPTS}" =~ ^[0-9]+$ ]] && (( VERIFY_ATTEMPTS >= 1 && VERIFY_ATTEMPTS <= 10 )) || \
  fail "VERIFY_ATTEMPTS must be between 1 and 10"
[[ "${VERIFY_RETRY_SECONDS}" =~ ^[0-9]+$ ]] && (( VERIFY_RETRY_SECONDS <= 30 )) || \
  fail "VERIFY_RETRY_SECONDS must be between 0 and 30"

verify_with_retry() {
  local description="$1"
  shift
  local attempt
  for (( attempt=1; attempt<=VERIFY_ATTEMPTS; attempt++ )); do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    if (( attempt == VERIFY_ATTEMPTS )); then
      "$@" >&2 || true
      fail "${description} failed for ${IMAGE} after ${VERIFY_ATTEMPTS} attempts"
    fi
    sleep "${VERIFY_RETRY_SECONDS}"
  done
}

verify_with_retry "signature verification" cosign verify \
  --certificate-identity-regexp "${COSIGN_IDENTITY_REGEXP}" \
  --certificate-oidc-issuer "${COSIGN_OIDC_ISSUER}" \
  "${IMAGE}"

verify_with_retry "SLSA provenance verification" cosign verify-attestation \
  --type slsaprovenance \
  --certificate-identity-regexp "${COSIGN_IDENTITY_REGEXP}" \
  --certificate-oidc-issuer "${COSIGN_OIDC_ISSUER}" \
  "${IMAGE}"

echo "Image identity verified: ${IMAGE}"
