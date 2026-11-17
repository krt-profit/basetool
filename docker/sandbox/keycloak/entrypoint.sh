#!/bin/bash
set -euo pipefail

if [ "$#" -gt 0 ] && [ "$1" = "start-dev" ]; then
  exec /opt/keycloak/bin/kc.sh "$@"
fi

printf '%s\n' \
  "This is the Basetool sandbox Keycloak: its realm carries published throwaway secrets," \
  "so it only runs as 'start-dev' and refuses '${1:-no command}' (REQ-XCH-029)." >&2
exit 64
