#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ASSERT_COVERAGE="${ROOT_DIR}/scripts/ops/assert-coverage.sh"
TEMP_ROOT="$(mktemp -d)"
TEMP_ROOT="$(cd "${TEMP_ROOT}" && pwd -P)"
TEMP_PREFIX="$(cd "${TMPDIR:-/tmp}" && pwd -P)/tmp."
cleanup() {
  case "${TEMP_ROOT}" in
    "${TEMP_PREFIX}"*) rm -rf -- "${TEMP_ROOT}" ;;
    *) echo "Refusing to remove unexpected temporary path: ${TEMP_ROOT}" >&2 ;;
  esac
}
trap cleanup EXIT

write_report() {
  local module="$1"
  local line_missed="$2"
  local line_covered="$3"
  local branch_missed="$4"
  local branch_covered="$5"
  mkdir -p "${TEMP_ROOT}/${module}/target/site/jacoco"
  cat > "${TEMP_ROOT}/${module}/target/site/jacoco/jacoco.xml" <<EOF
<report name="${module}"><counter type="LINE" missed="${line_missed}" covered="${line_covered}"/><counter type="BRANCH" missed="${branch_missed}" covered="${branch_covered}"/></report>
EOF
}

write_report dc-alpha 1 3 1 1
write_report dc-beta 1 1 0 2

output="$(cd "${TEMP_ROOT}" && REQUIRED_COVERAGE_MODULES=dc-alpha,dc-beta \
  MIN_LINE_COVERAGE=66 MIN_BRANCH_COVERAGE=75 bash "${ASSERT_COVERAGE}")"
[[ "${output}" == *"line=66.67% branch=75.00% reports=2"* ]]

rm -f "${TEMP_ROOT}/dc-beta/target/site/jacoco/jacoco.xml"
if (cd "${TEMP_ROOT}" && REQUIRED_COVERAGE_MODULES=dc-alpha,dc-beta \
  bash "${ASSERT_COVERAGE}" >/dev/null 2>"${TEMP_ROOT}/missing.err"); then
  echo "Expected a missing module report to fail coverage verification" >&2
  exit 1
fi
grep -q 'Missing required JaCoCo reports for: dc-beta' "${TEMP_ROOT}/missing.err"

output="$(cd "${TEMP_ROOT}" && REQUIRED_COVERAGE_MODULES=dc-alpha,dc-beta \
  ALLOW_MISSING_COVERAGE_MODULES=dc-beta MIN_LINE_COVERAGE=75 MIN_BRANCH_COVERAGE=50 \
  bash "${ASSERT_COVERAGE}" 2>"${TEMP_ROOT}/allowed.err")"
[[ "${output}" == *"line=75.00% branch=50.00% reports=1"* ]]
grep -q 'Coverage report intentionally absent for dc-beta' "${TEMP_ROOT}/allowed.err"

if (cd "${TEMP_ROOT}" && REQUIRED_COVERAGE_MODULES=dc-alpha \
  MIN_LINE_COVERAGE=76 MIN_BRANCH_COVERAGE=50 bash "${ASSERT_COVERAGE}" \
  >/dev/null 2>"${TEMP_ROOT}/regression.err"); then
  echo "Expected a line coverage regression to fail verification" >&2
  exit 1
fi
grep -q 'Coverage regression: line 75.00% < 76%' "${TEMP_ROOT}/regression.err"

echo "Coverage assertion tests passed"
