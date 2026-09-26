#!/usr/bin/env bash
set -euo pipefail

export MSYS2_ARG_CONV_EXCL='/in'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
IMAGE="alpine:3"
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

ACME_SCRIPT="${REPO_ROOT}/docker/acme/publish-loop.sh"
[[ -f "${ACME_SCRIPT}" ]] \
  || { echo "FAIL: ${ACME_SCRIPT} does not exist"; exit 1; }
grep -qF 'command: ["/etc/acme/publish-loop.sh"]' "${COMPOSE_FILE}" \
  || { echo "FAIL: the acme service no longer runs /etc/acme/publish-loop.sh -- this gate would be reading a file nobody executes"; exit 1; }

"${PY}" - "${ACME_SCRIPT}" "${WORK}/acme.sh" <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
body = open(src, encoding="utf-8").read().split(chr(10))

out = []
for line in body:
    stripped = line.strip()
    if "/lego " in stripped:
        out.append(line[:len(line) - len(line.lstrip())] + ": # lego stubbed out")
    elif stripped.startswith("sleep "):
        out.append(line[:len(line) - len(line.lstrip())] + "exit 0")
    else:
        out.append(line)

script = chr(10).join(out)
if "ACME_HOSTS" not in script:
    sys.exit("FAIL: the extracted script never mentions ACME_HOSTS")
open(dst, "w", newline="\n", encoding="utf-8").write(script + "\n")
print(f"==> extracted {len(out)} lines of the acme command")
PY

sh -n "${WORK}/acme.sh" || { echo "FAIL: the extracted acme command is not valid POSIX shell"; exit 1; }

chmod 0755 "${WORK}"
chmod 0644 "${WORK}/acme.sh"

HOSTS_LINE="edge1.check.invalid edge2.check.invalid edge3.check.invalid"
# shellcheck disable=SC2206
HOSTS=(${HOSTS_LINE})
PRIMARY="${HOSTS[0]}"

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

for pass in 1 2; do
  echo "==> publish pass ${pass}"
  if ! docker run --rm --cap-drop ALL --cap-add CHOWN \
      -e ACME_EMAIL=check@example.invalid -e "ACME_HOSTS=${HOSTS_LINE}" \
      -v "${VOL_DATA}:/data" -v "${VOL_CERTS}:/certs" \
      -v "$(if command -v cygpath >/dev/null 2>&1; then cygpath -m "${WORK}"; else printf '%s' "${WORK}"; fi):/in:ro" \
      "${IMAGE}" sh /in/acme.sh; then
    echo "FAIL: the acme command exited non-zero on pass ${pass} (output above)"
    exit 1
  fi
done

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
leftover=\"\$(find /certs -name '*.new' -print)\"
[ -z \"\$leftover\" ] || { echo \"FAIL: temporary files left behind: \$leftover\"; rc=1; }
exit \$rc
"

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
