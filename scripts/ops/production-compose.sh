#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-}"
[[ -n "${ENV_FILE}" ]] || { echo "Usage: $0 <env-file> [compose arguments...]" >&2; exit 2; }
shift
# shellcheck source=scripts/ops/production-env.sh
source "${ROOT_DIR}/scripts/ops/production-env.sh"
production_compose "${ENV_FILE}" "$@"
