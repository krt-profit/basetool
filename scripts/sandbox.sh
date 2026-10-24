#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/sandbox.sh <up|reset|down> [--build]

  up       start the local exchange sandbox and seed it
  reset    remove the sandbox with all its data, then start it again
  down     stop the sandbox and remove all its data

  --build  build the images from this checkout instead of pulling the published ones
USAGE
  exit 2
}

[ $# -ge 1 ] || usage
action="$1"
shift
build=false
for arg in "$@"; do
  case "$arg" in
    --build) build=true ;;
    *) usage ;;
  esac
done

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"

files=(-f docker-compose.yml -f docker-compose.test.yml -f docker-compose.sandbox.yml)
if [ "$build" = true ]; then
  files+=(-f docker-compose.sandbox-build.yml)
fi
compose=(docker compose --env-file docker/sandbox/sandbox.env "${files[@]}" --profile sandbox)
services=(redis-dev db-backend-dev db-keycloak-dev keycloak-dev backend-dev frontend-dev ingest-dev)

check_issuer_host() {
  local resolved
  if ! command -v getent >/dev/null 2>&1; then
    echo "sandbox: cannot check that host.docker.internal resolves to 127.0.0.1 here; see docs/exchange/sandbox.md" >&2
    return 0
  fi
  resolved="$(getent hosts host.docker.internal 2>/dev/null | awk '{ print $1; exit }' || true)"
  case "$resolved" in
    127.*|::1)
      return 0 ;;
    "")
      echo "sandbox: host.docker.internal does not resolve on this machine." >&2 ;;
    *)
      echo "sandbox: host.docker.internal resolves to $resolved, not to the loopback." >&2 ;;
  esac
  cat >&2 <<'HOSTS'
sandbox: the issuer is http://host.docker.internal:18080/auth/realms/basetool and the sandbox listens on
sandbox: 127.0.0.1 only, so this machine must resolve that name to the loopback. Add this line to
sandbox: /etc/hosts (it needs root) and run the command again:

    127.0.0.1 host.docker.internal
HOSTS
  return 1
}

up() {
  if [ "$build" = true ]; then
    "${compose[@]}" build
  else
    "${compose[@]}" pull --ignore-buildable
  fi
  "${compose[@]}" up -d --wait --wait-timeout 600 "${services[@]}"
  "${compose[@]}" run --rm sandbox-seed
  echo "sandbox: up. Issuer http://host.docker.internal:18080/auth/realms/basetool,"
  echo "sandbox: gateway https://localhost:11262/exchange/v1, web https://localhost:18081"
  echo "sandbox: the gateway answers 503 EXCHANGE_DISABLED for up to about 15 s until it sees the switch"
}

down() {
  "${compose[@]}" down --volumes --remove-orphans
}

case "$action" in
  up)
    check_issuer_host || true
    up ;;
  reset)
    check_issuer_host || true
    down
    up ;;
  down)
    down ;;
  *)
    usage ;;
esac
