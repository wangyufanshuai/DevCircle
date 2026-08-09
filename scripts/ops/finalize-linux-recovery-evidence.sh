#!/usr/bin/env bash
set -Eeuo pipefail

# Finalizes an operator-run recovery rehearsal. It never starts or stops services;
# that boundary keeps a failed validation from changing production state.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVIDENCE_DIR="${1:-}"
MAX_RTO_SECONDS="${MAX_RTO_SECONDS:-7200}"
MAX_RPO_SECONDS="${MAX_RPO_SECONDS:-86400}"

fail() {
  echo "Recovery evidence validation failed: $*" >&2
  exit 1
}

[[ "$(uname -s)" == "Linux" ]] || fail "must run on Linux"
[[ "${RECOVERY_EVIDENCE_CONFIRM:-}" == "CONFIRM" ]] || \
  fail "set RECOVERY_EVIDENCE_CONFIRM=CONFIRM to finalize an operator-run rehearsal"
command -v jq >/dev/null 2>&1 || fail "jq is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
[[ -n "${EVIDENCE_DIR}" && -d "${EVIDENCE_DIR}" ]] || \
  fail "usage: RECOVERY_EVIDENCE_CONFIRM=CONFIRM $0 <evidence-directory>"

EVIDENCE_DIR="$(cd "${EVIDENCE_DIR}" && pwd -P)"
required_files=(
  host.json compose-before.json compose-after.json backup-manifest.json SHA256SUMS
  recovery-timeline.json verify-migration.json elasticsearch-rebuild.json
  kafka-catchup.json dlt-replay.json service-health.json public-health.json
)
for file in "${required_files[@]}"; do
  [[ -f "${EVIDENCE_DIR}/${file}" ]] || fail "missing ${file}"
done

(
  cd "${EVIDENCE_DIR}"
  sha256sum -c SHA256SUMS >/dev/null
) || fail "SHA256SUMS verification failed"

for file in "${required_files[@]}"; do
  [[ "${file}" == "SHA256SUMS" ]] || jq empty "${EVIDENCE_DIR}/${file}" >/dev/null || fail "invalid JSON: ${file}"
done

git_sha="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
host_os="$(jq -r '.os // ""' "${EVIDENCE_DIR}/host.json")"
cpu_count="$(jq -r '.cpuCount // 0' "${EVIDENCE_DIR}/host.json")"
memory_gib="$(jq -r '.memoryGiB // 0' "${EVIDENCE_DIR}/host.json")"
disk_gib="$(jq -r '.diskGiB // 0' "${EVIDENCE_DIR}/host.json")"
image_tag="$(jq -r '.imageTag // ""' "${EVIDENCE_DIR}/host.json")"
[[ "${host_os}" == "Linux" ]] || fail "host.json must identify Linux"
[[ "${cpu_count}" =~ ^[0-9]+$ && "${cpu_count}" -ge 4 ]] || fail "host must have at least 4 CPUs"
[[ "${memory_gib}" =~ ^[0-9]+([.][0-9]+)?$ ]] && awk "BEGIN { exit !(${memory_gib} >= 15) }" || fail "host must have at least 15 GiB memory"
[[ "${disk_gib}" =~ ^[0-9]+([.][0-9]+)?$ ]] && awk "BEGIN { exit !(${disk_gib} >= 180) }" || fail "host must have at least 180 GiB disk"
[[ "${image_tag}" == "${git_sha}" ]] || fail "imageTag does not match checked-out Git SHA"

timeline="${EVIDENCE_DIR}/recovery-timeline.json"
status="$(jq -r '.status // ""' "${timeline}")"
rto="$(jq -r '.rtoSeconds // -1' "${timeline}")"
rpo="$(jq -r '.rpoSeconds // -1' "${timeline}")"
timeline_git="$(jq -r '.gitCommit // ""' "${timeline}")"
[[ "${status}" == "PASS" ]] || fail "recovery-timeline.status must be PASS"
[[ "${timeline_git}" == "${git_sha}" ]] || fail "timeline gitCommit does not match checked-out SHA"
[[ "${rto}" =~ ^[0-9]+$ && "${rto}" -le "${MAX_RTO_SECONDS}" ]] || fail "RTO exceeds ${MAX_RTO_SECONDS}s"
[[ "${rpo}" =~ ^[0-9]+$ && "${rpo}" -le "${MAX_RPO_SECONDS}" ]] || fail "RPO exceeds ${MAX_RPO_SECONDS}s"

for component in verify-migration elasticsearch-rebuild kafka-catchup dlt-replay service-health public-health; do
  component_status="$(jq -r '.status // ""' "${EVIDENCE_DIR}/${component}.json")"
  [[ "${component_status}" == "PASS" ]] || fail "${component}.json status must be PASS"
done

jq -n \
  --arg evidenceDir "${EVIDENCE_DIR}" \
  --arg gitCommit "${git_sha}" \
  --argjson rtoSeconds "${rto}" \
  --argjson rpoSeconds "${rpo}" \
  --argjson cpuCount "${cpu_count}" \
  --argjson memoryGiB "${memory_gib}" \
  --argjson diskGiB "${disk_gib}" \
  '{status:"PASS", evidenceDir:$evidenceDir, gitCommit:$gitCommit,
    host:{cpuCount:$cpuCount,memoryGiB:$memoryGiB,diskGiB:$diskGiB},
    rtoSeconds:$rtoSeconds, rpoSeconds:$rpoSeconds,
    verifiedAt:(now | strftime("%Y-%m-%dT%H:%M:%SZ"))}' \
  > "${EVIDENCE_DIR}/recovery-summary.json"

(
  cd "${EVIDENCE_DIR}"
  sha256sum ./*.json ./*.txt > SHA256SUMS
)
echo "Linux recovery evidence finalized: ${EVIDENCE_DIR}"
