#!/usr/bin/env bash
set -Eeuo pipefail

mapfile -t reports < <(find . -path '*/target/surefire-reports/TEST-*.xml' -type f -print)
if (( ${#reports[@]} == 0 )); then
  echo "No Surefire reports were found" >&2
  exit 1
fi

skipped="$(grep -H -E '<testsuite[^>]*skipped="[1-9][0-9]*"' "${reports[@]}" || true)"
if [[ -n "${skipped}" ]]; then
  echo "Integration tests were skipped; CI must not report a green backend build:" >&2
  echo "${skipped}" >&2
  exit 1
fi

echo "No Surefire tests were skipped"
