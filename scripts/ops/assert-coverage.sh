#!/usr/bin/env bash
set -Eeuo pipefail

line_min="${MIN_LINE_COVERAGE:-68.0}"
branch_min="${MIN_BRANCH_COVERAGE:-64.0}"
required_modules_csv="${REQUIRED_COVERAGE_MODULES:-dc-common,dc-messaging-support,dc-gateway,dc-identity,dc-content-deployment,dc-auth,dc-user,dc-content,dc-comment,dc-interaction,dc-search,dc-recommend,dc-notification,dc-file,dc-admin}"
allowed_missing_csv="${ALLOW_MISSING_COVERAGE_MODULES:-}"

IFS=',' read -r -a required_modules <<<"${required_modules_csv}"
declare -A allowed_missing=()
IFS=',' read -r -a allowed_modules <<<"${allowed_missing_csv}"
for module in "${allowed_modules[@]}"; do
  [[ -n "${module}" ]] && allowed_missing["${module}"]=1
done

missing_modules=()
for module in "${required_modules[@]}"; do
  [[ -n "${module}" ]] || { echo "REQUIRED_COVERAGE_MODULES contains an empty module name" >&2; exit 1; }
  report="./${module}/target/site/jacoco/jacoco.xml"
  if [[ ! -f "${report}" ]]; then
    if [[ -n "${allowed_missing[${module}]:-}" ]]; then
      echo "Coverage report intentionally absent for ${module}" >&2
    else
      missing_modules+=("${module}")
    fi
  fi
done

if (( ${#missing_modules[@]} > 0 )); then
  printf 'Missing required JaCoCo reports for:' >&2
  printf ' %s' "${missing_modules[@]}" >&2
  printf '\nRun the full coverage suite; do not treat a partial aggregate as complete.\n' >&2
  exit 1
fi

shopt -s nullglob
reports=(./dc-*/target/site/jacoco/jacoco.xml)
if (( ${#reports[@]} == 0 )); then
  echo "No JaCoCo XML reports found; run ./mvnw verify -Pcoverage first" >&2
  exit 1
fi

line_covered=0
line_missed=0
branch_covered=0
branch_missed=0

for report in "${reports[@]}"; do
  line_counter="$(grep -oE '<counter type="LINE" missed="[0-9]+" covered="[0-9]+"/>' "${report}" | tail -n 1)"
  branch_counter="$(grep -oE '<counter type="BRANCH" missed="[0-9]+" covered="[0-9]+"/>' "${report}" | tail -n 1)"
  [[ -n "${line_counter}" ]] || { echo "Missing report-level LINE counter in ${report}" >&2; exit 1; }
  [[ -n "${branch_counter}" ]] || { echo "Missing report-level BRANCH counter in ${report}" >&2; exit 1; }
  line_missed=$((line_missed + $(sed -E 's/.*missed="([0-9]+)".*/\1/' <<<"${line_counter}")))
  line_covered=$((line_covered + $(sed -E 's/.*covered="([0-9]+)".*/\1/' <<<"${line_counter}")))
  branch_missed=$((branch_missed + $(sed -E 's/.*missed="([0-9]+)".*/\1/' <<<"${branch_counter}")))
  branch_covered=$((branch_covered + $(sed -E 's/.*covered="([0-9]+)".*/\1/' <<<"${branch_counter}")))
done

line_percent="$(awk -v covered="${line_covered}" -v missed="${line_missed}" 'BEGIN { printf "%.2f", covered * 100 / (covered + missed) }')"
branch_percent="$(awk -v covered="${branch_covered}" -v missed="${branch_missed}" 'BEGIN { printf "%.2f", covered * 100 / (covered + missed) }')"
echo "Aggregate JaCoCo coverage: line=${line_percent}% branch=${branch_percent}% reports=${#reports[@]}"

awk -v actual="${line_percent}" -v minimum="${line_min}" 'BEGIN { exit !(actual + 0.000001 >= minimum) }' || {
  echo "Coverage regression: line ${line_percent}% < ${line_min}%" >&2
  exit 1
}
awk -v actual="${branch_percent}" -v minimum="${branch_min}" 'BEGIN { exit !(actual + 0.000001 >= minimum) }' || {
  echo "Coverage regression: branch ${branch_percent}% < ${branch_min}%" >&2
  exit 1
}
