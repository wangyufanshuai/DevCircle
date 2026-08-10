#!/usr/bin/env bash

configure_community_auth_profile() {
  COMMUNITY_AUTH_PROFILE="${REAL_E2E_AUTH_PROFILE:-identity}"
  case "${COMMUNITY_AUTH_PROFILE}" in
    identity)
      COMMUNITY_AUTH_SERVICE="dc-identity"
      COMMUNITY_AUTH_PORT="8201"
      COMMUNITY_AUTH_URI="http://dc-identity:8201"
      COMMUNITY_AUTH_MAVEN_MODULE=""
      COMMUNITY_AUTH_COMPOSE_PROFILES=(--profile community)
      ;;
    legacy-auth)
      COMMUNITY_AUTH_SERVICE="dc-auth"
      COMMUNITY_AUTH_PORT="8101"
      COMMUNITY_AUTH_URI="http://dc-auth:8101"
      COMMUNITY_AUTH_MAVEN_MODULE="dc-auth"
      COMMUNITY_AUTH_COMPOSE_PROFILES=(--profile community --profile legacy-auth)
      ;;
    *)
      echo "REAL_E2E_AUTH_PROFILE must be identity or legacy-auth" >&2
      return 2
      ;;
  esac
  export REAL_E2E_AUTH_URI="${COMMUNITY_AUTH_URI}"
}

community_outbox_runtime_sources() {
  local auth_path="/internal/ops/outbox/status"
  if [[ "${COMMUNITY_AUTH_SERVICE}" == "dc-identity" ]]; then
    auth_path="/internal/ops/identity/outbox/auth/status"
  fi
  printf '%s\n' \
    "${COMMUNITY_AUTH_SERVICE}:${COMMUNITY_AUTH_PORT}:dc-auth:${auth_path}" \
    "dc-identity:8201:dc-user:/internal/ops/identity/outbox/user/status" \
    "dc-content-aggregate:8202:dc-content:/internal/ops/outbox/status" \
    "dc-comment:8104:dc-comment:/internal/ops/outbox/status" \
    "dc-interaction:8105:dc-interaction:/internal/ops/outbox/status"
}
