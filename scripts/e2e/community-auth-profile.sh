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
  printf '%s\n' \
    "${COMMUNITY_AUTH_SERVICE}:${COMMUNITY_AUTH_PORT}:dc-auth" \
    "dc-user:8102:dc-user" \
    "dc-content-aggregate:8202:dc-content" \
    "dc-comment:8104:dc-comment" \
    "dc-interaction:8105:dc-interaction"
}
