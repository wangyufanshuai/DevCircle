#!/usr/bin/env bash

# Safe dotenv reader shared by production operations. This file parses data;
# it never evaluates or sources operator-controlled values.
declare -gA PRODUCTION_ENV=()
declare -ga PRODUCTION_ENV_KEYS=()

production_env_key_is_safe() {
  local key="$1"
  case "${key}" in
    PATH|HOME|USER|LOGNAME|SHELL|IFS|ENV|ENV_FILE|BASH_*|SHELLOPTS|BASHOPTS|CDPATH|GLOBIGNORE|PROMPT_COMMAND|EUID|UID|PPID|GROUPS|FUNCNAME|DIRSTACK|PIPESTATUS|RANDOM|SECONDS|LINENO|OPTIND|PWD|OLDPWD|SHLVL|_|LD_*|DYLD_*|DOCKER_*|COMPOSE_*|XDG_*|TMP|TEMP|TMPDIR|SYSTEMROOT|WINDIR)
      return 1
      ;;
    *) return 0 ;;
  esac
}

load_production_env() {
  local env_file="$1" raw key
  [[ -f "${env_file}" ]] || { echo "Missing ${env_file}" >&2; return 2; }
  PRODUCTION_ENV=()
  PRODUCTION_ENV_KEYS=()
  while IFS= read -r raw || [[ -n "${raw}" ]]; do
    raw="${raw%$'\r'}"
    [[ -z "${raw//[[:space:]]/}" || "${raw}" =~ ^[[:space:]]*# ]] && continue
    [[ "${raw}" == *=* ]] || { echo "Invalid line in ${env_file}: expected KEY=VALUE" >&2; return 2; }
    key="${raw%%=*}"
    key="${key//[[:space:]]/}"
    [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || { echo "Invalid key in ${env_file}: ${key}" >&2; return 2; }
    production_env_key_is_safe "${key}" || {
      echo "Runtime control key is forbidden in ${env_file}: ${key}" >&2
      return 2
    }
    [[ ! -v "PRODUCTION_ENV[${key}]" ]] || { echo "Duplicate key in ${env_file}: ${key}" >&2; return 2; }
    PRODUCTION_ENV["${key}"]="${raw#*=}"
    PRODUCTION_ENV_KEYS+=("${key}")
  done < "${env_file}"
}

production_env_value() {
  local key="$1" default_value="${2:-}"
  printf '%s' "${PRODUCTION_ENV[${key}]:-${default_value}}"
}

# Render and execute Compose using one authoritative env file. Docker Compose
# normally gives the invoking process environment precedence over --env-file.
# Clear only keys controlled by the validated file in a subshell so an
# operator's exported IMAGE_TAG (or secret) cannot change the deployed graph.
# Runtime variables such as PATH and DOCKER_HOST are deliberately preserved and
# cannot be declared by the production env file.
production_compose() {
  local env_file="$1" key
  shift
  load_production_env "${env_file}" || return
  (
    for key in "${PRODUCTION_ENV_KEYS[@]}"; do
      unset "${key}"
    done
    docker compose --env-file "${env_file}" "$@"
  )
}
