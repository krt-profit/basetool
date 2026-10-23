#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

: "${BACKEND_IMAGE:?}" "${FRONTEND_IMAGE:?}" "${INGEST_IMAGE:?}" "${KEYCLOAK_IMAGE:?}"
: "${CACHE_REPO:?}" "${CACHE_WRITE:?}"

case "$CACHE_WRITE" in
  true | false) ;;
  *)
    echo "::error::CACHE_WRITE must be true or false (got '${CACHE_WRITE}')"
    exit 1
    ;;
esac

build() {
  local dockerfile="$1" tag="$2" cache="$3" attempt
  shift 3
  local args=(--file "$dockerfile" --load --tag "$tag"
    --cache-from "type=registry,ref=${CACHE_REPO}:${cache}")
  if [ "$CACHE_WRITE" = true ]; then
    args+=(--cache-to "type=registry,ref=${CACHE_REPO}:${cache},mode=max")
  fi
  for attempt in 1 2; do
    if docker buildx build "${args[@]}" "$@" .; then
      return 0
    fi
    echo "::warning::building ${tag} failed (attempt ${attempt}/2)"
    sleep 15
  done
  return 1
}

echo "cache: ${CACHE_REPO} (write: ${CACHE_WRITE})"
(
  build docker/app/Dockerfile "$INGEST_IMAGE" ingest --build-arg MODULE=ingest
  build docker/sandbox/keycloak/Dockerfile "$KEYCLOAK_IMAGE" keycloak
) > side-builds.log 2>&1 &
side=$!
status=0
build docker/app/Dockerfile "$BACKEND_IMAGE" backend --build-arg MODULE=backend || status=1
build docker/app/Dockerfile "$FRONTEND_IMAGE" frontend --build-arg MODULE=frontend || status=1
wait "$side" || status=1
echo "::group::ingest and sandbox Keycloak build log"
cat side-builds.log
echo "::endgroup::"
[ "$status" -eq 0 ]
docker image ls --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep ':e2e-local '
