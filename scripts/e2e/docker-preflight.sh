#!/usr/bin/env bash

E2E_RUNTIME_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=docker-runtime.sh
source "${E2E_RUNTIME_DIR}/docker-runtime.sh"

require_real_e2e_docker() {
  local suite_name="${1:-real E2E}"
  local minimum_memory_bytes="${REAL_E2E_MIN_DOCKER_MEMORY_BYTES:-2147483648}"
  local shared_memory_bytes="${REAL_E2E_SHARED_DOCKER_MEMORY_BYTES:-4294967296}"
  local allow_shared_docker="${REAL_E2E_ALLOW_SHARED_DOCKER:-false}"
  local probe_timeout_seconds="${REAL_E2E_DOCKER_PROBE_TIMEOUT_SECONDS:-10}"
  local docker_memory_bytes
  local running_containers
  local running_container_count

  require_e2e_docker_runtime || return 2
  command -v timeout >/dev/null 2>&1 || {
    echo "timeout is required for Docker preflight" >&2
    return 2
  }
  [[ "${probe_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || {
    echo "REAL_E2E_DOCKER_PROBE_TIMEOUT_SECONDS must be a positive integer" >&2
    return 2
  }
  [[ "${minimum_memory_bytes}" =~ ^[1-9][0-9]*$ && "${shared_memory_bytes}" =~ ^[1-9][0-9]*$ ]] || {
    echo "REAL_E2E_*_DOCKER_MEMORY_BYTES must be positive byte counts" >&2
    return 2
  }
  (( shared_memory_bytes >= minimum_memory_bytes )) || {
    echo "REAL_E2E_SHARED_DOCKER_MEMORY_BYTES must not be lower than REAL_E2E_MIN_DOCKER_MEMORY_BYTES" >&2
    return 2
  }
  [[ "${allow_shared_docker}" == "true" || "${allow_shared_docker}" == "false" ]] || {
    echo "REAL_E2E_ALLOW_SHARED_DOCKER must be true or false" >&2
    return 2
  }

  docker_memory_bytes="$(timeout --signal=KILL "${probe_timeout_seconds}s" \
    docker info --format '{{.MemTotal}}' 2>/dev/null)" || {
    echo "Docker daemon is unavailable; ${suite_name} was not executed" >&2
    return 2
  }
  [[ "${docker_memory_bytes}" =~ ^[0-9]+$ ]] || {
    echo "Docker reported an invalid memory limit: ${docker_memory_bytes}" >&2
    return 2
  }
  if (( docker_memory_bytes < minimum_memory_bytes )); then
    echo "${suite_name} requires at least ${minimum_memory_bytes} bytes of Docker memory; configured bytes=${docker_memory_bytes}" >&2
    return 2
  fi

  running_containers="$(timeout --signal=KILL "${probe_timeout_seconds}s" docker ps -q 2>/dev/null)" || {
    echo "Docker daemon stopped responding while listing containers; ${suite_name} was not executed" >&2
    return 2
  }
  running_container_count=0
  while IFS= read -r container_id; do
    [[ -z "${container_id}" ]] || ((running_container_count += 1))
  done <<<"${running_containers}"
  if (( running_container_count > 0 && docker_memory_bytes < shared_memory_bytes )) \
      && [[ "${allow_shared_docker}" != "true" ]]; then
    echo "${suite_name} requires ${shared_memory_bytes} bytes when other containers are running; stop them or set REAL_E2E_ALLOW_SHARED_DOCKER=true explicitly" >&2
    return 2
  fi
}
