#!/usr/bin/env bash
# =============================================================================
# Run the acme container's certificate-publishing step and assert it lands
# (ADR-0162).
#
# This step has broken twice, both times silently and both times only on a host
# that already had certificates:
#
#   1. It iterated /data/certificates/*.crt. lego issues ONE multi-SAN
#      certificate for its whole -d list, so exactly one host directory was ever
#      written and the other four kept whatever seeded them — to expire.
#   2. It ran `chown -R 101:101 /certs`, handing the DIRECTORIES to uid 101.
#      `cap_drop: [ALL]` removes CAP_DAC_OVERRIDE, so root then held only r-x
#      there, could not replace the files, and `set -eu` turned the next pass
#      into a restart loop.
#
# Neither is visible in `docker compose config`, in a syntax check, or on a
# first run against an empty volume. Both are visible here.
#
# What makes this a real gate: the script under test is EXTRACTED FROM
# docker-compose.yml, not restated. A fix applied to a copy in this file would
# leave production broken and this check green.
#
# lego itself is stubbed out — the publishing step is what is under test, and a
# config check must never depend on reaching Let's Encrypt.
#
# Usage:  scripts/check-acme-publish.sh
# Exit:   0 = the publish step works, 1 = it does not
# =============================================================================
set -euo pipefail

# Git-Bash/MSYS rewrites any argument that looks like a POSIX path, so the
# container-side `/in/acme.sh` arrives as `C:/Program Files/Git/in/acme.sh`.
# Excluding that one prefix leaves the host paths in `-v` alone, which still need
# converting. Ignored on Linux and in any real shell.
export MSYS2_ARG_CONV_EXCL='/in'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
IMAGE="alpine:3"
# The uid the edge runs as, and therefore the uid the certificate files must end
# up owned by. Mirrors `user: "101:101"` on the edge service.
EDGE_UID=101

[[ -f "${COMPOSE_FILE}" ]] || { echo "FAIL: ${COMPOSE_FILE} does not exist"; exit 1; }
command -v python >/dev/null 2>&1 && PY=python || PY=python3
command -v "${PY}" >/dev/null || { echo "FAIL: python not on PATH"; exit 1; }

WORK="$(mktemp -d)"
VOL_CERTS="acme-publish-check-certs-$$"
VOL_DATA="acme-publish-check-data-$$"
cleanup() {
  rm -rf "${WORK}"
  docker volume rm -f "${VOL_CERTS}" "${VOL_DATA}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- Extract the acme command, verbatim -------------------------------------
# The service's `command:` is a single YAML block scalar. Compose escapes shell
# variables in it as `$$`, which it collapses to `$` when it loads the file, so
# the same collapse happens here. The two `/lego` invocations become no-ops and
# the 12-hour sleep becomes a clean exit, so one pass runs and returns.
"${PY}" - "${COMPOSE_FILE}" "${WORK}/acme.sh" <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
lines = open(src, encoding="utf-8").read().split("\n")

try:
    start = next(i for i, l in enumerate(lines) if l == "  acme:")
except StopIteration:
    sys.exit("FAIL: no `acme:` service in docker-compose.yml")

# The block scalar opener, then every line indented under it.
try:
    opener = next(i for i in range(start, len(lines)) if lines[i].strip() == "- |")
except StopIteration:
    sys.exit("FAIL: no block scalar under the acme service's command:")

body, indent = [], None
for line in lines[opener + 1:]:
    if not line.strip():
        body.append("")
        continue
    pad = len(line) - len(line.lstrip())
    if indent is None:
        indent = pad
    elif pad < indent:
        break
    body.append(line[indent:])

out = []
for line in body:
    stripped = line.strip()
    if stripped.startswith("/lego "):
        # Keep the indentation so the surrounding if/else stays valid.
        out.append(line[:len(line) - len(line.lstrip())] + ": # lego stubbed out")
    elif stripped.startswith("sleep "):
        out.append(line[:len(line) - len(line.lstrip())] + "exit 0")
    else:
        out.append(line)

script = "\n".join(out).replace("$$", "$")
if "ACME_HOSTS=" not in script:
    sys.exit("FAIL: the extracted script defines no ACME_HOSTS")
open(dst, "w", newline="\n", encoding="utf-8").write(script + "\n")
print(f"==> extracted {len(out)} lines of the acme command")
PY

sh -n "${WORK}/acme.sh" || { echo "FAIL: the extracted acme command is not valid POSIX shell"; exit 1; }

# The container mounts this directory and runs as root WITHOUT CAP_DAC_OVERRIDE —
# the very rule under test. `mktemp -d` gives 0700 owned by the invoking user, so
# on a Linux runner that root cannot read its own input and the check fails with
# `sh: can't open '/in/acme.sh': Permission denied`. Nothing secret is in here.
chmod 0755 "${WORK}"
chmod 0644 "${WORK}/acme.sh"

HOSTS_LINE="$(sed -n 's/^[[:space:]]*ACME_HOSTS="\([^"]*\)".*/\1/p' "${COMPOSE_FILE}")"
# shellcheck disable=SC2206  # deliberate word splitting: ACME_HOSTS is space-separated
HOSTS=(${HOSTS_LINE})
PRIMARY="${HOSTS[0]}"

# --- Build the state a production host is actually in ------------------------
# Not an empty volume: both defects only appear when /certs already holds the
# seeded material, owned by uid 101, exactly as the cutover leaves it.
docker volume create "${VOL_CERTS}" >/dev/null
docker volume create "${VOL_DATA}" >/dev/null
docker run --rm -v "${VOL_DATA}:/data" -v "${VOL_CERTS}:/certs" "${IMAGE}" sh -c "
set -eu
mkdir -p /data/certificates
echo ISSUED-BY-LEGO > /data/certificates/${PRIMARY}.crt
echo ISSUED-KEY     > /data/certificates/${PRIMARY}.key
echo ISSUER         > /data/certificates/${PRIMARY}.issuer.crt
for h in ${HOSTS_LINE}; do
  mkdir -p /certs/\$h
  echo SEEDED-BY-CUTOVER > /certs/\$h/fullchain.pem
  echo SEEDED-KEY        > /certs/\$h/privkey.pem
done
chown -R ${EDGE_UID}:${EDGE_UID} /certs
" >/dev/null

# --- Run it under the container's real capabilities --------------------------
# `--cap-drop ALL --cap-add CHOWN` and root are what the compose file grants. Run
# it TWICE: the first pass is the cutover, the second is a renewal 12 hours later
# against a tree the first pass already wrote. Defect 2 only showed on the second.
for pass in 1 2; do
  echo "==> publish pass ${pass}"
  if ! docker run --rm --cap-drop ALL --cap-add CHOWN \
      -e ACME_EMAIL=check@example.invalid \
      -v "${VOL_DATA}:/data" -v "${VOL_CERTS}:/certs" \
      -v "$(if command -v cygpath >/dev/null 2>&1; then cygpath -m "${WORK}"; else printf '%s' "${WORK}"; fi):/in:ro" \
      "${IMAGE}" sh /in/acme.sh; then
    echo "FAIL: the acme command exited non-zero on pass ${pass} (output above)"
    exit 1
  fi
done

# --- Assert what the edge needs ----------------------------------------------
docker run --rm -v "${VOL_CERTS}:/certs:ro" "${IMAGE}" sh -c "
set -eu
rc=0
for h in ${HOSTS_LINE}; do
  for f in fullchain.pem privkey.pem; do
    p=\"/certs/\$h/\$f\"
    [ -f \"\$p\" ] || { echo \"FAIL: \$p missing — acme published nothing for this host\"; rc=1; continue; }
    case \"\$(cat \"\$p\")\" in
      SEEDED-*) echo \"FAIL: \$p still holds the seeded material — acme never replaced it\"; rc=1 ;;
    esac
    owner=\"\$(stat -c '%u:%g' \"\$p\")\"
    [ \"\$owner\" = '${EDGE_UID}:${EDGE_UID}' ] || { echo \"FAIL: \$p owned by \$owner, the edge reads it as uid ${EDGE_UID}\"; rc=1; }
  done
  mode=\"\$(stat -c '%a' \"/certs/\$h/privkey.pem\")\"
  [ \"\$mode\" = '600' ] || { echo \"FAIL: /certs/\$h/privkey.pem is mode \$mode, expected 600\"; rc=1; }
  mode=\"\$(stat -c '%a' \"/certs/\$h/fullchain.pem\")\"
  [ \"\$mode\" = '644' ] || { echo \"FAIL: /certs/\$h/fullchain.pem is mode \$mode, expected 644\"; rc=1; }
done
# A stray temporary file means a rename was skipped somewhere.
leftover=\"\$(find /certs -name '*.new' -print)\"
[ -z \"\$leftover\" ] || { echo \"FAIL: temporary files left behind: \$leftover\"; rc=1; }
exit \$rc
"

# --- Close the chain: can the edge actually OPEN what acme just wrote? --------
# Ownership and mode are only a proxy. This is the real question, and it is the
# one that kept coming back: the edge's MASTER process opens every certificate
# and key at startup AS uid 101, and a file it cannot read is not a permission
# error in the log — it is a container that exits in three seconds, a failed
# health check and an automatic rollback. Asked directly, in the real image.
EDGE_IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(nginxinc\/nginx-unprivileged:[^[:space:]@]*\).*/\1/p' "${COMPOSE_FILE}" | head -1)"
[[ -n "${EDGE_IMAGE}" ]] \
  || { echo "FAIL: no nginx-unprivileged image in docker-compose.yml — the edge service changed"; exit 1; }

if ! docker run --rm --user "${EDGE_UID}:${EDGE_UID}" --network none \
    -v "${VOL_CERTS}:/etc/nginx/certs:ro" \
    --entrypoint sh "${EDGE_IMAGE}" -c "
set -eu
rc=0
for h in ${HOSTS_LINE}; do
  for f in fullchain.pem privkey.pem; do
    p=\"/etc/nginx/certs/\$h/\$f\"
    head -c 1 \"\$p\" >/dev/null 2>&1 \
      || { echo \"FAIL: uid ${EDGE_UID} cannot read \$p — the edge would exit at startup\"; rc=1; }
  done
done
exit \$rc
"; then
  echo "FAIL: the edge cannot read the material acme published (output above)"
  exit 1
fi

echo "==> acme publishes to all ${#HOSTS[@]} hosts, twice, and uid ${EDGE_UID} can read every file"
