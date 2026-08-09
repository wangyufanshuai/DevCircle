#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERIFY_IMAGE="${ROOT_DIR}/scripts/ops/verify-image-identity.sh"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf -- "${TEMP_ROOT}"' EXIT
FAKE_BIN="${TEMP_ROOT}/bin"
COSIGN_LOG="${TEMP_ROOT}/cosign.log"
COSIGN_COUNT="${TEMP_ROOT}/cosign.count"
mkdir -p "${FAKE_BIN}"

cat > "${FAKE_BIN}/uname" <<'EOF'
#!/usr/bin/env bash
printf 'Linux\n'
EOF

cat > "${FAKE_BIN}/cosign" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
count=0
[[ ! -f "${COSIGN_COUNT}" ]] || count="$(cat "${COSIGN_COUNT}")"
count=$((count + 1))
printf '%s' "${count}" > "${COSIGN_COUNT}"
printf '%s\n' "$*" >> "${COSIGN_LOG}"
if (( count <= ${FAKE_COSIGN_FAILURES:-0} )); then
  echo "registry object not visible yet" >&2
  exit 1
fi
EOF

chmod +x "${FAKE_BIN}/uname" "${FAKE_BIN}/cosign"
export PATH="${FAKE_BIN}:${PATH}" COSIGN_LOG COSIGN_COUNT
IMAGE="ghcr.io/wangyufanshuai/devcircle/dc-auth@sha256:$(printf 'a%.0s' {1..64})"

FAKE_COSIGN_FAILURES=1 VERIFY_ATTEMPTS=3 VERIFY_RETRY_SECONDS=0 \
  bash "${VERIFY_IMAGE}" "${IMAGE}" >/dev/null
[[ "$(cat "${COSIGN_COUNT}")" == "3" ]]
grep -q '^verify ' "${COSIGN_LOG}"
grep -q '^verify-attestation ' "${COSIGN_LOG}"

if VERIFY_ATTEMPTS=1 VERIFY_RETRY_SECONDS=0 \
  bash "${VERIFY_IMAGE}" "ghcr.io/wangyufanshuai/devcircle/dc-auth:latest" >/dev/null 2>&1; then
  echo "Expected a mutable image tag to be rejected" >&2
  exit 1
fi

rm -f "${COSIGN_COUNT}" "${COSIGN_LOG}"
if FAKE_COSIGN_FAILURES=10 VERIFY_ATTEMPTS=2 VERIFY_RETRY_SECONDS=0 \
  bash "${VERIFY_IMAGE}" "${IMAGE}" >/dev/null 2>&1; then
  echo "Expected exhausted identity verification to fail closed" >&2
  exit 1
fi
[[ "$(cat "${COSIGN_COUNT}")" == "3" ]]

echo "Image identity verifier tests passed"
