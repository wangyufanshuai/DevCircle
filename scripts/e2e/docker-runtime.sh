#!/usr/bin/env bash

require_e2e_docker_runtime() {
  local executable="${REAL_E2E_DOCKER_COMMAND:-docker}"
  command -v "${executable}" >/dev/null 2>&1 || {
    echo "docker is required" >&2
    return 2
  }
}

docker() {
  local executable="${REAL_E2E_DOCKER_COMMAND:-docker}"
  local -a arguments=("$@")
  local -a propagated_names=()

  if [[ "${executable}" == *.exe ]] && command -v wslpath >/dev/null 2>&1; then
    local index name propagated
    for index in "${!arguments[@]}"; do
      if [[ "${arguments[index]}" =~ ^/mnt/[a-zA-Z]/ ]]; then
        arguments[index]="$(wslpath -w "${arguments[index]}")"
      fi
    done
    while IFS='=' read -r name _; do
      [[ "${name}" == REAL_E2E_* ]] && propagated_names+=("${name}")
    done < <(env)
    if ((${#propagated_names[@]} > 0)); then
      propagated="$(IFS=:; echo "${propagated_names[*]}")"
      (
        export WSLENV="${WSLENV:+${WSLENV}:}${propagated}"
        command "${executable}" "${arguments[@]}"
      )
      return
    fi
  fi

  command "${executable}" "${arguments[@]}"
}
