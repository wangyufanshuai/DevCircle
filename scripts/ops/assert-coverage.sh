#!/usr/bin/env bash
set -Eeuo pipefail

line_min="${MIN_LINE_COVERAGE:-35.0}"
branch_min="${MIN_BRANCH_COVERAGE:-32.0}"

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
