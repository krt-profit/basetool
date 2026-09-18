#!/bin/sh
# Profit Basetool -- the ACME certificate loop (ADR-0162, REQ-OPS-026).
#
# Extracted from docker-compose.yml's `command:` block scalar on 2026-09-16, byte-identical apart
# from compose's `$$` escaping becoming a single `$`. Two reasons it had to move:
#
#   - Quadlet's Exec= is a single line, so a multi-line command cannot be translated at all
#     (scripts/generate-quadlet.py refuses rather than mangling it);
#   - a shell script inside a YAML block scalar is invisible to shellcheck, and this one publishes
#     the certificate material the edge opens on every reload. The edge already does it this way
#     with render-and-run.sh.
#
# The reasoning in the comments below is the original's and is preserved verbatim: every one of
# them records something that broke once.
set -eu
# Checked HERE, by the shell, and not with a compose-level required-variable
# marker in the environment below. (This comment deliberately spells out no
# dollar-brace form: compose interpolates the whole block scalar, COMMENTS
# INCLUDED, so a literal one here fails the file for every profile.)
# Compose interpolates every variable when it LOADS the file, regardless of
# which profile is active, so a `:?` on a prod-only service made `--profile
# dev` — and with it every E2E run and every local test stack — fail with
# "ACME_EMAIL must be set in .env". The requirement is real, it just belongs
# to the container that needs it rather than to parsing the file.
# This check comes FIRST, ahead of the ACME_EMAIL guard below, and the
# order is not cosmetic: an environment that manages no certificates has
# no reason to carry an ACME contact address either, so the guard would
# fire, the container would exit, and `restart: unless-stopped` would
# turn that into a restart loop — which reads as a crash in every
# dashboard we have.
if [ -z "${ACME_HOSTS:-}" ]; then
  echo "acme: ACME_HOSTS is empty — no certificates are managed on this host"
  while true; do sleep 86400; done
fi
: "${ACME_EMAIL:?ACME_EMAIL must be set in .env for the acme container}"
# /lego, not `lego`: the image ships the binary at the filesystem root and
# its PATH is the stock /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:
# /sbin:/bin, so the bare name is not found. With the `|| echo` tail that
# failure is swallowed and the container keeps running — it would have sat
# there for sixty days looking healthy and never issuing a certificate.
# The host list comes from the host `.env`, the same place the edge's
# vhost names come from, and scripts/check-edge-nginx.sh asserts the two
# sides name the same vhosts. A vhost the edge reads but acme does not
# carry keeps whatever seeded it and expires without a word.
#
# EMPTY is a valid and meaningful setting: an environment whose
# certificate is provided rather than issued — a test host behind a proxy,
# where HTTP-01 cannot reach anything — sets nothing and this container
# idles. It idles rather than exiting because `restart: unless-stopped`
# turns a clean exit into a restart loop, which is indistinguishable from
# a crash in every dashboard we have.
# lego names the certificate after the FIRST -d and puts every other host in
# its SAN list, so one file covers all of them.
PRIMARY="${ACME_HOSTS%% *}"
DOMAINS=""
for h in ${ACME_HOSTS}; do DOMAINS="${DOMAINS} -d $h"; done
# lego v5 (migration guide: https://github.com/go-acme/lego/blob/master/docs/content/migration/cli.md):
#   - global flags became flags of the COMMAND (`lego --foo run` -> `lego run --foo`).
#   - `renew` was removed: `run` now obtains OR renews depending on whether a
#     certificate for the same cert ID (here: $PRIMARY, the first -d) already
#     exists on disk — it also skips renewing when the certificate is not yet
#     due, so calling it unconditionally on every pass (below) is safe and
#     replaces the v4 if/else on /data/certificates.
#   - `--days` (the renewal window) was renamed `--renew-days`.
# `lego migrate` rewrites the on-disk layout (account keys move out of
# accounts/.../keys/, and the JSON content itself changes) and v5 refuses to
# operate on a still-v4 volume without it. It is idempotent — a no-op once the
# volume is migrated — so it stays inside the loop like every other step here
# rather than running once: a failed migration is retried on the next pass
# instead of wedging the container until a manual restart. It always asks for
# confirmation even when there is nothing to migrate, and this container has
# no TTY, hence the piped "Y".
while true; do
  echo "Y" | /lego migrate --path /data ||
    echo "acme: migrate failed, will retry on the next pass" >&2
  # shellcheck disable=SC2086  # $DOMAINS holds "-d host1 -d host2 ..." and MUST split into
  # separate arguments; quoting it hands lego one argument and it issues nothing. This is the
  # only place in the loop where word splitting is intended, and it was invisible to any
  # linter while the script lived inside a YAML block scalar.
  /lego run --accept-tos --email "${ACME_EMAIL}" --path /data                --http --http.webroot /webroot --renew-days 30 ${DOMAINS} ||             echo "acme: run/renew failed, will retry on the next pass" >&2
  # Publish the material the edge consumes, under a stable per-host layout
  # it can mount read-only. Driven by ACME_HOSTS, not by the certificate
  # FILES: lego writes a SINGLE multi-SAN certificate for the whole -d list,
  # so iterating /data/certificates/*.crt produced exactly one host
  # directory and left the other four vhosts on whatever seeded them, to
  # expire in silence. Every host gets that one pair.
  if [ -f "/data/certificates/${PRIMARY}.crt" ]; then
    # The DIRECTORIES stay root-owned, deliberately. `cap_drop: [ALL]` takes
    # CAP_DAC_OVERRIDE away, so root in this container obeys the permission
    # bits like anyone else: against a 101-owned 0755 directory it holds only
    # r-x, cannot unlink the previous file, and `cp` fails with
    #   cp: can't create '/certs/<host>/fullchain.pem': File exists
    # after which `set -eu` ends the script and the container restart-loops.
    # A `chown -R 101:101 /certs` here caused exactly that on 2026-09-12 by
    # handing the directories away on its own first pass. Only the FILES go
    # to uid 101 — they are all the edge ever opens.
    # Write a temporary file beside the target and rename it into place.
    # Three reasons, in order of how much they hurt when skipped:
    #   1. `mv` within one directory is rename(2) — atomic. The edge opens
    #      these on every reload, and a half-copied certificate is a failed
    #      start, not a retry.
    #   2. The temporary file is created root-owned, so `chmod` works.
    #      cap_drop: [ALL] also removes CAP_FOWNER, and root may not chmod a
    #      file it does not own: mode BEFORE ownership, never the reverse.
    #   3. The rename needs write permission on the DIRECTORY only, which is
    #      why the directories stay root-owned above.
    publish() {
      cp "$1" "$2.new"
      chmod "$3" "$2.new"
      # lego runs as root and writes the key 0600 root-owned. The edge runs
      # as uid 101 and its MASTER opens both at startup, so without this it
      # cannot read the key and exits immediately — which surfaces as a
      # three-second health-check failure and a rollback, never as a
      # permission error. Ownership, not mode: the key stays unreadable to
      # everyone else.
      chown 101:101 "$2.new"
      mv -f "$2.new" "$2"
    }
    chown 0:0 /certs
    for h in ${ACME_HOSTS}; do
      mkdir -p "/certs/$h"
      chown 0:0 "/certs/$h"
      publish "/data/certificates/${PRIMARY}.crt" "/certs/$h/fullchain.pem" 0644
      publish "/data/certificates/${PRIMARY}.key" "/certs/$h/privkey.pem"   0600
    done
  fi
  sleep 43200
done
