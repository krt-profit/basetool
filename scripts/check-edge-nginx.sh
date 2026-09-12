#!/usr/bin/env bash
# =============================================================================
# Validate the edge nginx configuration (ADR-0162).
#
# `nginx -t` is a real gate, not a syntax check: it parses every include,
# resolves every `limit_req_zone` reference by name, rejects an unknown
# directive, AND loads every certificate named by `ssl_certificate`. That last
# part is why this script mints throwaway certificates first — the real ones are
# written by the acme container on the production host and are not, and must not
# be, in this repository.
#
# The certificates are generated on the HOST rather than inside the container:
# `nginxinc/nginx-unprivileged` ships no openssl, and adding one with `apk` would
# make a config check depend on a package mirror. Git-Bash and the GitHub runner
# both have openssl, so the container stays offline and the gate stays fast.
#
# Usage:  scripts/check-edge-nginx.sh
# Exit:   0 = configuration is valid, 1 = it is not (nginx's own message is
#         printed verbatim)
# =============================================================================
set -euo pipefail

# Git-Bash/MSYS rewrites an argument that looks like a POSIX path, so `-subj
# "/CN=host"` arrives at openssl as `C:/Program Files/Git/CN=host` and the
# request is rejected. Excluding ONLY arguments that start with `/CN=` fixes
# that while leaving `-keyout` / `-out` converted — a blanket MSYS_NO_PATHCONV
# would break those instead, since a Windows openssl cannot write to `/tmp/...`.
# The variable is ignored on the Linux runner and in any real shell.
export MSYS2_ARG_CONV_EXCL='/CN='

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EDGE_DIR="${REPO_ROOT}/docker/edge"
IMAGE="nginxinc/nginx-unprivileged:1.29.3-alpine"

[[ -d "${EDGE_DIR}" ]] || { echo "FAIL: ${EDGE_DIR} does not exist"; exit 1; }
command -v openssl >/dev/null || { echo "FAIL: openssl not on PATH"; exit 1; }

# The hostnames the configuration names. Kept in one place so a new vhost fails
# loudly here rather than at deploy time with a missing certificate.
HOSTS=(
  profit-base.online
  keycloak.profit-base.online
  ingest.profit-base.online
  grafana.profit-base.online
  api.profit-base.online
)

CERT_DIR="$(mktemp -d)"
trap 'rm -rf "${CERT_DIR}"' EXIT

echo "==> minting throwaway certificates for ${#HOSTS[@]} hosts"
for h in "${HOSTS[@]}"; do
  mkdir -p "${CERT_DIR}/${h}"
  # 2048-bit RSA and one day of validity: this material exists for the length of
  # one `nginx -t` and key generation is otherwise the slowest step in the gate.
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj "/CN=${h}" \
    -keyout "${CERT_DIR}/${h}/privkey.pem" \
    -out    "${CERT_DIR}/${h}/fullchain.pem" >/dev/null 2>&1
done

# The trust anchor referenced by proxy_ssl_trusted_certificate. On the host this
# is the public certificate exported from the shared keystore; here any valid PEM
# proves the directive parses and the file is readable.
cp "${CERT_DIR}/${HOSTS[0]}/fullchain.pem" "${CERT_DIR}/upstream-ca.crt"

echo "==> validating ${EDGE_DIR} with ${IMAGE}"

# --user 0:0 applies to THIS VALIDATION CONTAINER ONLY. The image runs as uid 101
# and the runtime keeps it that way — that is the whole point of the unprivileged
# image. The validator needs root solely to assemble a writable /etc/nginx and
# the document roots. Nothing produced here is shipped.
#
# --network none: the check must never depend on the network, and an accidental
# `apk add` or upstream lookup creeping in should fail loudly rather than work on
# a developer's machine and hang in CI.
# Docker needs a native path for a bind mount. Git-Bash hands out `/d/Coding/...`,
# which the daemon cannot resolve; `cygpath -m` turns it into `D:/Coding/...`,
# which MSYS then also leaves alone because it no longer looks like a POSIX path.
# On Linux there is no cygpath and the paths are already native.
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

docker run --rm --user 0:0 --network none \
  -v "$(to_native "${EDGE_DIR}"):/edge:ro" \
  -v "$(to_native "${CERT_DIR}"):/certs:ro" \
  --entrypoint sh \
  "${IMAGE}" -c '
set -eu
# Mirrors the runtime layout exactly: ONE directory at /etc/nginx/edge, and the
# main config selected with -c. Anything else would validate a shape the
# container never runs.
cp -r /edge /etc/nginx/edge
cp -r /certs /etc/nginx/certs
mv /etc/nginx/certs/upstream-ca.crt /etc/nginx/upstream-ca.crt
mkdir -p /var/www/acme /usr/share/nginx/html/maintenance /tmp/nginx
# nginx does not open these at parse time, but the roots must exist.
: > /usr/share/nginx/html/maintenance/maintenance.html
: > /usr/share/nginx/html/maintenance/maintenance.json
nginx -t -c /etc/nginx/edge/nginx.conf
' 2>&1 | tee "${CERT_DIR}/nginx-t.out"

# `nginx -t` exits 0 on a warning, and a configuration that always warns is one
# where the next — real — warning is not read. Treat any [warn] as a failure.
if grep -q '\[warn\]' "${CERT_DIR}/nginx-t.out"; then
  echo "FAIL: nginx -t emitted a warning (shown above). Fix it or state why it is acceptable."
  exit 1
fi

echo "==> edge configuration is valid"
