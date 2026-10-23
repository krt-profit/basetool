#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

: "${SCENARIO:?}" "${CACHE_FROM:?}" "${CACHE_REPO:?}" "${GITHUB_STEP_SUMMARY:?}"

printf '| scenario | image | seconds | cached steps |\n|---|---|---:|---:|\n' >> "$GITHUB_STEP_SUMMARY"

measure() {
  local name="$1" start end cached
  shift
  local args=(--progress=plain --load --tag "probe-${name}:local"
    --cache-to "type=registry,ref=${CACHE_REPO}:${name},mode=max")
  if [ "$CACHE_FROM" = true ]; then
    args+=(--cache-from "type=registry,ref=${CACHE_REPO}:${name}")
  fi
  start=$(date +%s)
  if ! docker buildx build "${args[@]}" "$@" . > "/tmp/${name}.log" 2>&1; then
    tail -n 80 "/tmp/${name}.log"
    exit 1
  fi
  end=$(date +%s)
  cached=$(grep -c ' CACHED$' "/tmp/${name}.log" || true)
  printf '| %s | %s | %s | %s |\n' "$SCENARIO" "$name" "$((end - start))" "$cached" >> "$GITHUB_STEP_SUMMARY"
  echo "${name}: $((end - start)) s, ${cached} cached steps"
  grep -E 'exporting cache|importing cache|preparing build cache' "/tmp/${name}.log" | tail -n 6 || true
}

measure backend --file docker/app/Dockerfile --build-arg MODULE=backend
measure frontend --file docker/app/Dockerfile --build-arg MODULE=frontend
measure ingest --file docker/app/Dockerfile --build-arg MODULE=ingest
measure keycloak --file docker/sandbox/keycloak/Dockerfile
