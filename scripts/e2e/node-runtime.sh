#!/usr/bin/env bash

require_e2e_node() {
  if command -v node >/dev/null 2>&1; then
    return 0
  fi
  if command -v node.exe >/dev/null 2>&1; then
    node() {
      command node.exe "$@"
    }
    return 0
  fi
  echo "node or node.exe is required" >&2
  return 2
}
