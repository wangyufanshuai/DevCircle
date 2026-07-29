#!/usr/bin/env bash
set -Eeuo pipefail

THRESHOLD="${DISK_THRESHOLD_PERCENT:-80}"
USED="$(df -P "${1:-/}" | awk 'NR==2 {gsub("%","",$5); print $5}')"
if (( USED >= THRESHOLD )); then
  echo "CRITICAL: disk usage is ${USED}% (threshold ${THRESHOLD}%)" >&2
  exit 1
fi
echo "OK: disk usage is ${USED}%"
