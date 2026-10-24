#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${HERE}/migrate-host-layout.sh"
HELPER="${HERE}/migrate-host-env.py"
ANSIBLE_SELINUX="${HERE}/../ansible/roles/basetool_host/tasks/40-selinux.yml"
for f in "$SCRIPT" "$HELPER"; do
  [[ -f "$f" ]] || { echo "FATAL: ${f} not found" >&2; exit 1; }
done
PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" > /dev/null 2>&1 || { echo "FATAL: ${PYTHON} not found" >&2; exit 1; }

PASSED=0
FAILED=0
ok() { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() {
  FAILED=$((FAILED + 1))
  printf '  FAIL  %s\n' "$*"
  if [[ -f "${OUT:-/nonexistent}" ]]; then
    tail -n 8 "$OUT" | sed 's/^/        out: /'
  fi
}
check() {
  local what="$1"
  shift
  if "$@"; then ok "$what"; else bad "$what"; fi
}

WORK="$(mktemp -d "${TMPDIR:-/tmp}/migrate-layout-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
BIN="${WORK}/bin"
FAKE="${WORK}/fake"
ROOT="${WORK}/root"
CACHE="${WORK}/cache"
CALLS="${FAKE}/calls.log"
OUT="${WORK}/out"
ALLOUT="${WORK}/all-output"
mkdir -p "$BIN" "$CACHE"
: > "$ALLOUT"

stub() {
  local name="$1"
  cat > "${BIN}/${name}"
  chmod +x "${BIN}/${name}"
}

stub systemctl <<'STUB'
#!/usr/bin/env bash
echo "systemctl $*" >> "$FAKE/calls.log"
user_mode=0
cmd=""
units=()
now=0
for a in "$@"; do
  case "$a" in
    --user) user_mode=1 ;;
    --now) now=1 ;;
    -*) ;;
    *) if [[ -z "$cmd" ]]; then cmd="$a"; else units+=("$a"); fi ;;
  esac
done
mkdir -p "$FAKE/active" "$FAKE/enabled"
case "$cmd" in
  is-active)
    if [[ -e "$FAKE/active/${units[0]}" ]]; then echo active; exit 0; fi
    echo inactive
    exit 3
    ;;
  is-enabled)
    if [[ -e "$FAKE/enabled/${units[0]}" ]]; then echo enabled; exit 0; fi
    echo disabled
    exit 1
    ;;
  enable | disable)
    for u in "${units[@]}"; do
      if [[ "$cmd" == enable ]]; then touch "$FAKE/enabled/$u"; else rm -f "$FAKE/enabled/$u"; fi
      if (( now )); then
        if [[ "$cmd" == enable ]]; then touch "$FAKE/active/$u"; else rm -f "$FAKE/active/$u"; fi
      fi
    done
    ;;
  start | stop)
    for u in "${units[@]}"; do
      if [[ "$cmd" == start ]]; then
        if (( user_mode )); then
          case "$u" in
            *-volume.service) mkdir -p "$FAKE/podman-store/vol/${u%-volume.service}" ;;
          esac
        else
          touch "$FAKE/active/$u"
          case "$u" in
            user@*.service) n="${u#user@}"; mkdir -p "$ROOT/run/user/${n%.service}" ;;
            user-runtime-dir@*.service) n="${u#user-runtime-dir@}"; mkdir -p "$ROOT/run/user/${n%.service}" ;;
          esac
        fi
      else
        rm -f "$FAKE/active/$u"
        case "$u" in
          user@*.service) n="${u#user@}"; rmdir "$ROOT/run/user/${n%.service}" 2> /dev/null || true ;;
          user-runtime-dir@*.service) n="${u#user-runtime-dir@}"; rmdir "$ROOT/run/user/${n%.service}" 2> /dev/null || true ;;
        esac
      fi
    done
    ;;
esac
exit 0
STUB

stub ps <<'STUB'
#!/usr/bin/env bash
echo "ps $*" >> "$FAKE/calls.log"
cat "$FAKE/ps.out" 2> /dev/null || true
exit 0
STUB

stub runuser <<'STUB'
#!/usr/bin/env bash
echo "runuser $*" >> "$FAKE/calls.log"
shift 3
exec "$@"
STUB

stub pkill <<'STUB'
#!/usr/bin/env bash
echo "pkill $*" >> "$FAKE/calls.log"
awk -v u="$2" -v n="$4" '!($1 == u && $3 == n)' "$FAKE/ps.out" > "$FAKE/ps.new" && mv "$FAKE/ps.new" "$FAKE/ps.out"
exit 0
STUB

stub podman <<'STUB'
#!/usr/bin/env bash
echo "podman $* (HOME=${HOME:-})" >> "$FAKE/calls.log"
if [[ "$1" != --version && -e "$FAKE/pause-process" ]]; then echo "  994  777 catatonit" >> "$FAKE/ps.out"; fi
if [[ "$1" != --version && -e "$FAKE/other-process" ]]; then echo "  994  778 sleep" >> "$FAKE/ps.out"; fi
store="$FAKE/podman-store"
case "$1 $2" in
  "volume exists") [[ -d "$store/vol/$3" ]] ;;
  "volume export")
    [[ ! -e "$FAKE/export-fails" ]] || exit 125
    [[ -d "$store/vol/$3" ]] || exit 125
    tar -C "$store/vol/$3" -cf - .
    ;;
  "volume import")
    [[ "$4" == "-" ]] || exit 2
    mkdir -p "$store/vol/$3"
    tar -C "$store/vol/$3" -xf -
    ;;
  "system reset")
    rm -rf "$store" "$ROOT/home/iri/.local/share/containers/storage"
    if [[ -e "$FAKE/recreate-db" ]]; then
      mkdir -p "$ROOT/home/iri/.local/share/containers/storage/libpod"
      : > "$ROOT/home/iri/.local/share/containers/storage/libpod/db.sql"
    fi
    exit 0
    ;;
  *) case "$1" in --version) echo "podman version 5.8.2" ;; esac ;;
esac
STUB

stub usermod <<'STUB'
#!/usr/bin/env bash
echo "usermod $*" >> "$FAKE/calls.log"
echo "usermod $(umask)" >> "$FAKE/umask.log"
f="$ROOT/etc/passwd"
setfield() {
  awk -F: -v OFS=: -v n="$1" -v h="$2" '$1 == n { $6 = h } 1' "$f" > "$f.new" && mv "$f.new" "$f"
}
case "$1" in
  -l)
    awk -F: -v OFS=: -v o="$3" -v n="$2" '$1 == o { $1 = n } 1' "$f" > "$f.new" && mv "$f.new" "$f"
    ;;
  -d)
    home="$2"
    shift 2
    domove=0
    if [[ "$1" == -m ]]; then domove=1; shift; fi
    name="$1"
    old="$(awk -F: -v n="$name" '$1 == n { print $6 }' "$f")"
    setfield "$name" "$home"
    if (( domove )); then mkdir -p "$(dirname "$ROOT$home")"; mv "$ROOT$old" "$ROOT$home"; fi
    ;;
esac
exit 0
STUB

stub groupmod <<'STUB'
#!/usr/bin/env bash
echo "groupmod $*" >> "$FAKE/calls.log"
echo "groupmod $(umask)" >> "$FAKE/umask.log"
f="$ROOT/etc/group"
awk -F: -v OFS=: -v o="$3" -v n="$2" '$1 == o { $1 = n } 1' "$f" > "$f.new" && mv "$f.new" "$f"
exit 0
STUB

stub loginctl <<'STUB'
#!/usr/bin/env bash
echo "loginctl $*" >> "$FAKE/calls.log"
mkdir -p "$ROOT/var/lib/systemd/linger" "$FAKE/active"
case "$1" in
  enable-linger) : > "$ROOT/var/lib/systemd/linger/$2"; mkdir -p "$ROOT/run/user/994"; touch "$FAKE/active/user@994.service" ;;
  disable-linger) rm -f "$ROOT/var/lib/systemd/linger/$2" ;;
esac
exit 0
STUB

stub semanage <<'STUB'
#!/usr/bin/env bash
echo "semanage $*" >> "$FAKE/calls.log"
if [[ "$2" != -l ]]; then echo "semanage $(umask)" >> "$FAKE/umask.log"; fi
f="$FAKE/fcontext.local"
touch "$f"
drop() { awk -F'\t' -v p="$1" '$1 != p' "$f" > "$f.new" && mv "$f.new" "$f"; }
case "$2" in
  -l) cat "$f" ;;
  -a) printf '%s\tall files\tsystem_u:object_r:%s:s0\n' "$5" "$4" >> "$f" ;;
  -m) drop "$5"; printf '%s\tall files\tsystem_u:object_r:%s:s0\n' "$5" "$4" >> "$f" ;;
  -d) drop "$3" ;;
esac
exit 0
STUB

stub stat <<'STUB'
#!/usr/bin/env bash
if [[ "$1" == -c && "$2" == %d && -f "$FAKE/other-fs" ]] && grep -qxF "${3#"$ROOT"}" "$FAKE/other-fs"; then
  echo 777
  exit 0
fi
exec /usr/bin/stat "$@"
STUB

stub restorecon <<'STUB'
#!/usr/bin/env bash
echo "restorecon $*" >> "$FAKE/calls.log"
case "$1" in
  -nRFv) cat "$FAKE/restorecon.pending" 2> /dev/null || true ;;
  *) rm -f "$FAKE/restorecon.pending" ;;
esac
exit 0
STUB

for name in chown systemd-tmpfiles; do
  stub "$name" <<'STUB'
#!/usr/bin/env bash
echo "$(basename "$0") $*" >> "$FAKE/calls.log"
exit 0
STUB
done

mkfile() {
  mkdir -p "$(dirname "$1")"
  printf '%s' "${2-${1#"$ROOT"}}" > "$1"
}

FILES=(
  "/var/iri/code/env.d/backend.env|/etc/basetool/env.d/backend.env"
  "/var/iri/code/scripts/deploy.sh|/opt/basetool/scripts/deploy.sh"
  "/var/iri/code/scripts/lib/common.sh|/opt/basetool/scripts/lib/common.sh"
  "/var/iri/code/docker-compose.yml|/opt/basetool/docker-compose.yml"
  "/var/iri/code/keycloak/providers/keycloak-spi.jar|/opt/basetool/keycloak/providers/keycloak-spi.jar"
  "/var/iri/code/monitoring/alloy/config.alloy|/opt/basetool/monitoring/alloy/config.alloy"
  "/var/iri/secrets/keystore.p12|/etc/basetool/secrets/keystore.p12"
  "/var/iri/secrets/tls/ca.crt|/etc/basetool/secrets/tls/ca.crt"
  "/var/iri/monitoring/secrets/web_password|/etc/basetool/monitoring/secrets/web_password"
  "/var/iri/monitoring/certs/grafana.crt|/etc/basetool/monitoring/certs/grafana.crt"
  "/var/iri/monitoring/textfile/deploy.prom|/var/lib/basetool/textfile/deploy.prom"
  "/var/iri/monitoring/data/grafana/grafana.db|/var/lib/basetool/monitoring/grafana/grafana.db"
  "/var/iri/db-backend/PG_VERSION|/var/lib/basetool/db-backend/PG_VERSION"
  "/var/iri/db-keycloak/PG_VERSION|/var/lib/basetool/db-keycloak/PG_VERSION"
  "/var/iri/redis/users.acl|/var/lib/basetool/redis/users.acl"
  "/var/iri/backup/staging/.keep|/var/lib/basetool/backup/staging/.keep"
  "/var/iri/backend/log/backend.json|/var/log/basetool/backend/backend.json"
  "/var/iri/frontend/log/frontend.json|/var/log/basetool/frontend/frontend.json"
  "/var/iri/ingest/log/ingest.json|/var/log/basetool/ingest/ingest.json"
  "/var/iri/keycloak/log/keycloak.log|/var/log/basetool/keycloak/keycloak.log"
  "/var/lib/iri/restic-cache/data/x|/var/cache/basetool/restic/data/x"
  "/var/lib/iri/last-deployed.digests|/var/lib/basetool/deploy/last-deployed.digests"
  "/var/lib/iri/current-digest-pin.yml|/var/lib/basetool/deploy/current-digest-pin.yml"
  "/var/lib/iri/previous-digest-pin.yml|/var/lib/basetool/deploy/previous-digest-pin.yml"
  "/var/lib/iri/keycloak-spi-previous.jar|/var/lib/basetool/deploy/keycloak-spi-previous.jar"
  "/var/lib/iri/config-previous/docker-compose.yml|/var/lib/basetool/deploy/config-previous/docker-compose.yml"
  "/var/lib/iri/config-stage/docker-compose.yml|/var/lib/basetool/deploy/config-stage/docker-compose.yml"
  "/var/lib/iri/.docker/config.json|/var/lib/basetool/deploy/.docker/config.json"
  "/var/lib/iri/failed.digests|/var/lib/basetool/deploy/failed.digests"
  "/var/lib/iri/reapply-failed.digests|/var/lib/basetool/deploy/reapply-failed.digests"
  "/var/lib/iri/mixed-release.since|/var/lib/basetool/deploy/mixed-release.since"
  "/var/lib/iri/health-restart.digests|/var/lib/basetool/deploy/health-restart.digests"
  "/var/lib/iri/config-blocked.marker|/var/lib/basetool/deploy/config-blocked.marker"
  "/var/iri/code/quadlet/systemd/backend.container|/opt/basetool/quadlet/systemd/backend.container"
  "/var/iri/code/docker/edge/nginx.conf|/opt/basetool/docker/edge/nginx.conf"
  "/var/iri/code/keycloak-theme/theme.properties|/opt/basetool/keycloak-theme/theme.properties"
  "/var/iri/code/host-scripts.sha256|/opt/basetool/host-scripts.sha256"
  "/var/iri/code/docker-compose.monitoring.yml|/opt/basetool/docker-compose.monitoring.yml"
  "/var/lib/iri/hardening/report.txt|/var/lib/basetool/deploy/hardening/report.txt"
  "/etc/iri/ghcr-pull-token|/etc/basetool/deploy/ghcr-pull-token"
  "/etc/iri/rclone.conf|/etc/basetool/deploy/rclone.conf"
  "/etc/iri/ssg-tailoring.xml|/etc/basetool/ssg-tailoring.xml"
  "/var/log/iri-deploy.log|/var/log/basetool/deploy.log"
  "/var/log/iri-deploy.log-20261018|/var/log/basetool/deploy.log-20261018"
  "/var/log/iri-deploy.log-20261011.gz|/var/log/basetool/deploy.log-20261011.gz"
  "/var/log/iri-backup.log|/var/log/basetool/backup.log"
  "/var/log/iri-container-cleanup.log|/var/log/basetool/container-cleanup.log"
  "/var/log/iri-restore-drill.log|/var/log/basetool/restore-drill.log"
  "/var/log/iri-restore-drill.trace|/var/log/basetool/restore-drill.trace"
)
ENV_OLD=$'IRI_KEYSTORE_HOST_PATH=/var/iri/secrets/keystore.p12\nIRI_BASETOOL_VERSION=1.19.12\nIRI_IMAGE_NAMESPACE=krt-profit\n# IRI_KEEP=/var/iri/code\nSERVER_SSL_KEY_STORE_PASSWORD=TOPSECRETVALUE\nEDGE_HOST_FRONTEND=profit-base.online\nIRI_KEYCLOAK_ISSUER_URI=https://profit-base.online/auth/realms/iri\nIRI_COMPOSE_DIR=/var/iri/code\nIRI_ENV_D_DIR=/var/iri/code/env.d\nIRI_LOCKFILE=/var/lock/iri-deploy.lock\n'
ENV_BACKUP_A=$'IRI_KEYSTORE_HOST_PATH=/var/iri/secrets/keystore.p12\nREDIS_PASSWORD=ANOTHERSECRET\n'
ENV_BACKUP_B=$'IRI_STATE_DIR=/var/lib/iri\nSERVER_SSL_KEY_STORE_PASSWORD=OLDSECRETVALUE\n'
BACKUP_ENV_OLD=$'RESTIC_REPOSITORY=rclone:nextcloud:Basetool-Backups\nRCLONE_CONFIG=/etc/iri/rclone.conf\nRESTIC_PASSWORD=RESTICSECRET\nIRI_KEEP_DAILY=7\n'
REALM_OLD=$'{\n  "realm": "iri",\n  "roles": {"realm": [{"name": "default-roles-iri", "containerId": "iri"}]},\n  "clients": [{"clientId": "basetool-frontend", "name": "IRIDIUM Basetool Frontend", "description": "Frontend Application for IRIDIUM Basetool", "rootUrl": "https://h/auth/realms/iri/x", "redirectUris": ["/admin/iri/console/*"]}]\n}\n'
SECRETS_RE='HOSTLOCAL-SECRET-ONE|TOPSECRETVALUE|OLDSECRETVALUE|RESTICSECRET|ANOTHERSECRET|PRIVATE-KEY-MATERIAL|ACME-ACCOUNT'

build_template() {
  rm -rf "$ROOT" "$FAKE"
  mkdir -p "$FAKE/active" "$FAKE/enabled" "$FAKE/podman-store/vol" "$ROOT"
  : > "$FAKE/ps.out"
  printf '%s\t%s\t%s\n' '/var/iri(/.*)?' 'all files' 'system_u:object_r:container_file_t:s0' >> "$FAKE/fcontext.local"
  printf '%s\t%s\t%s\n' '/var/iri/code/scripts(/.*)?' 'all files' 'system_u:object_r:bin_t:s0' >> "$FAKE/fcontext.local"
  printf '%s\n' '/var/iri/secrets/keystore.p12 unlabeled' > "$FAKE/restorecon.pending"
  mkfile "$ROOT/etc/passwd" $'root:x:0:0:root:/root:/bin/bash\ndeploy:x:992:992::/var/lib/iri:/sbin/nologin\niri:x:994:994::/home/iri:/sbin/nologin\n'
  mkfile "$ROOT/etc/group" $'root:x:0:\ndeploy:x:992:\niri:x:994:\n'
  mkfile "$ROOT/etc/subuid" $'iri:100000:65536\ngreluc:589824:65536\n'
  mkfile "$ROOT/etc/subgid" $'iri:100000:65536\ngreluc:589824:65536\n'
  mkfile "$ROOT/var/lib/systemd/linger/iri" ''
  mkfile "$ROOT/home/iri/.config/containers/containers.conf" 'pasta'
  mkfile "$ROOT/home/iri/.local/share/containers/storage/db.sql" 'db'
  local u="$ROOT/etc/containers/systemd/users/994" v t s c f row
  for v in edge-certs edge-acme-state edge-acme-webroot; do
    mkfile "$u/${v}.volume" "[Volume]
VolumeName=${v}
"
    mkdir -p "$FAKE/podman-store/vol/${v}"
  done
  mkfile "$FAKE/podman-store/vol/edge-certs/fullchain.pem" 'CERT'
  mkfile "$FAKE/podman-store/vol/edge-certs/privkey.pem" 'PRIVATE-KEY-MATERIAL'
  mkfile "$FAKE/podman-store/vol/edge-acme-state/account.json" 'ACME-ACCOUNT'
  mkfile "$u/backend.container" $'[Container]\nImage=x'
  mkfile "$u/backend.container.d/10-digest-pin.conf" $'[Container]\nImage=y'
  mkfile "$u/frontend-net.network" '[Network]'
  s="$ROOT/etc/systemd/system"
  for t in deploy backup restore-drill container-cleanup container-metrics cert-expiry; do
    mkfile "$s/iri-${t}.service" $'[Service]\nExecStart=/var/iri/code/scripts/x.sh\n'
    mkfile "$s/iri-${t}.timer" $'[Timer]\nOnCalendar=daily\n'
    mkfile "$s/iri-${t}.service.d/10-deploy-account-sandbox.conf" '[Service]'
    mkfile "$s/timers.target.wants/iri-${t}.timer" 'link'
    mkfile "$ROOT/etc/logrotate.d/iri-${t}" "/var/log/iri-${t}.log {}"
    touch "$FAKE/enabled/iri-${t}.timer"
  done
  mkfile "$s/iri-host-updates-metrics.service" $'[Service]\n[Install]\nWantedBy=multi-user.target\n'
  touch "$FAKE/enabled/iri-host-updates-metrics.service"
  mkfile "$ROOT/etc/tmpfiles.d/iri-locks.conf" 'f /var/lock/iri-deploy.lock 0600 deploy deploy'
  mkfile "$ROOT/run/lock/iri-deploy.lock" ''
  mkfile "$ROOT/etc/default/prometheus-node-exporter" 'ARGS="--collector.textfile.directory=/var/iri/monitoring/textfile"'
  mkfile "$ROOT/etc/sudoers.d/basetool-deploy" 'deploy ALL=(iri) NOPASSWD: /usr/bin/podman'
  c="$ROOT/var/iri/code"
  mkfile "$c/.env" "$ENV_OLD"
  mkfile "$c/.env.backup-20260925-175805-step4" "$ENV_BACKUP_A"
  mkfile "$c/.env.backup-20261001-000000-redis" "$ENV_BACKUP_B"
  mkfile "$c/realm-export.json" "$REALM_OLD"
  mkfile "$ROOT/etc/iri/backup.env" "$BACKUP_ENV_OLD"
  for row in "${FILES[@]}"; do
    f="${row%%|*}"
    mkfile "${ROOT}${f}"
  done
  mkdir -p "$ROOT/var/lock"
  touch "$FAKE/active/user@994.service" "$FAKE/active/dnf-automatic.timer"
  mkdir -p "$ROOT/run/user/994"
  mkfile "$ROOT/var/lib/systemd/timers/stamp-iri-deploy.timer" ''
  mkfile "$ROOT/var/lib/systemd/timers/stamp-dnf-automatic.timer" ''
  mkfile "$s/iri-deploy.service.d/override.conf" $'[Service]\nEnvironment=IRI_LOCKFILE=/var/lock/iri-deploy.lock\nExecStartPre=/var/iri/code/scripts/pre.sh\n'
  mkfile "$s/iri-deploy.service.d/monitoring.conf" $'[Unit]\nAfter=iri-backup.service\n# IRI_X /var/iri/npm stays in a comment\n'
  mkfile "$s/iri-backup.timer.d/schedule.conf" $'[Timer]\nOnCalendar=*-*-* 03:00:00\n'
  chmod 0640 "$s/iri-deploy.service.d/override.conf"
  mkfile "$c/.env.bak1" 'HOSTLOCAL-SECRET-ONE'
  mkfile "$c/kc-client.before.json" 'HOSTLOCAL-JSON'
  chmod 0600 "$c/.env.bak1"
  mkdir -p "$CACHE/template"
  cp -a "$ROOT" "$CACHE/template/root"
  cp -a "$FAKE" "$CACHE/template/fake"
}

restore_state() {
  rm -rf "$ROOT" "$FAKE"
  cp -a "$CACHE/$1/root" "$ROOT"
  cp -a "$CACHE/$1/fake" "$FAKE"
  : > "$CALLS"
}

save_state() {
  rm -rf "${CACHE:?}/$1"
  mkdir -p "$CACHE/$1"
  cp -a "$ROOT" "$CACHE/$1/root"
  cp -a "$FAKE" "$CACHE/$1/fake"
}

run_script() {
  : > "$CALLS"
  env PATH="${BIN}:${PATH}" FAKE="$FAKE" ROOT="$ROOT" MIGRATE_ROOT="$ROOT" \
    MIGRATE_EUID="${EUID_FOR_RUN:-0}" MIGRATE_WAIT_SECONDS=0 MIGRATE_POLL_SECONDS=0 \
    MIGRATE_RUNTIME_WAIT_SECONDS=0 MIGRATE_PYTHON="$PYTHON" TMPDIR="$WORK" \
    bash "$SCRIPT" "$@" > "$OUT" 2>&1
  RC=$?
  printf '## %s (rc %s)\n' "$*" "$RC" >> "$ALLOUT"
  cat "$OUT" >> "$ALLOUT"
}

tree_full() {
  (
    cd "$ROOT" || exit 1
    find . -printf '%p %y %m %s %T@\n' | LC_ALL=C sort
    find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
  )
}

tree_content() {
  (
    cd "${1:-$ROOT}" || exit 1
    find . -printf '%p %y %m\n' | LC_ALL=C sort
    find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
  )
}

MUTATING='^(usermod|groupmod|loginctl|chown|systemd-tmpfiles) |^systemctl (stop|start|disable|enable|daemon-reload|reset-failed|--user)|^semanage fcontext -[amd]|^podman (system|volume (export|import))|^runuser|^pkill |^restorecon -RF'

calls_match() {
  grep -qE "$1" "$CALLS"
}

no_mutation() {
  ! grep -qE "$MUTATING" "$CALLS"
}

fctx_has() {
  awk -F'	' -v p="$1" -v t="$2" '$1 == p && index($3, ":" t ":") > 0 { found = 1 } END { exit !found }' "$FAKE/fcontext.local"
}

check_calls() {
  if calls_match "$2"; then ok "$1"; else bad "$1"; fi
}

check_nomut() {
  if no_mutation; then ok "$1"; else bad "$1"; fi
}

check_fctx() {
  if fctx_has "$2" "$3"; then ok "$1"; else bad "$1"; fi
}

phase_ok() {
  local what="$1"
  shift
  run_script "$@"
  if (( RC == 0 )); then ok "$what"; else bad "$what (rc ${RC})"; fi
}

refused() {
  local what="$1" pattern="$2" before after
  shift 2
  before="$(tree_full)"
  run_script "$@"
  after="$(tree_full)"
  if (( RC == 3 )) && grep -qE "$pattern" "$OUT"; then ok "$what"; else bad "$what (rc ${RC})"; fi
  check "$what: the host is untouched" test "$before" = "$after"
  check_nomut "$what: no mutating command ran"
}

refused_late() {
  local what="$1" pattern="$2"
  shift 2
  run_script "$@"
  if (( RC == 3 )) && grep -qE "$pattern" "$OUT"; then ok "$what"; else bad "$what (rc ${RC})"; fi
}

dry_run_changes_nothing() {
  local what="$1" before after
  shift
  before="$(tree_full)"
  run_script --dry-run "$@"
  after="$(tree_full)"
  check "$what: --dry-run succeeds" test "$RC" = 0
  check "$what: --dry-run changes no file" test "$before" = "$after"
  check_nomut "$what: --dry-run runs no mutating command"
  check "$what: --dry-run says what it would do" grep -q 'would: ' "$OUT"
}

noop_rerun() {
  local what="$1" before after
  shift
  before="$(tree_full)"
  run_script "$@"
  after="$(tree_full)"
  check "$what: a second run succeeds" test "$RC" = 0
  check "$what: a second run says it has nothing to do" grep -q 'nothing to do, already done' "$OUT"
  check "$what: a second run changes no file" test "$before" = "$after"
  check_nomut "$what: a second run runs no mutating command"
}

echo "== the env helper =="

env_case() {
  local what="$1" input="$2" expected="$3" f="${WORK}/case.env"
  printf '%s' "$input" > "$f"
  "$PYTHON" -I "$HELPER" env "$f" > "${WORK}/helper.out" 2>&1
  if [[ "$(cat "$f"; printf x)" == "${expected}x" ]]; then ok "$what"; else bad "$what"; printf '        got: %s\n' "$(cat "$f")"; fi
}

while IFS='|' read -r from to; do
  [[ -n "$from" ]] || continue
  env_case "path ${from}" "SOME_PATH=${from}"$'\n' "SOME_PATH=${to}"$'\n'
done <<'TABLE'
/var/iri/code/.env.backup-20260925|/etc/basetool/basetool.env.backup-20260925
/var/iri/code/.env|/etc/basetool/basetool.env
/var/iri/code/env.d|/etc/basetool/env.d
/var/iri/code/env.d/backend.env|/etc/basetool/env.d/backend.env
/var/iri/code/realm-export.json|/etc/basetool/realm-export.json
/var/iri/code|/opt/basetool
/var/iri/code/scripts/deploy.sh|/opt/basetool/scripts/deploy.sh
/var/iri/code/keycloak/providers|/opt/basetool/keycloak/providers
/var/iri/secrets/tls/ca.crt|/etc/basetool/secrets/tls/ca.crt
/var/iri/monitoring/secrets|/etc/basetool/monitoring/secrets
/var/iri/monitoring/certs/x.crt|/etc/basetool/monitoring/certs/x.crt
/var/iri/monitoring/data|/var/lib/basetool/monitoring
/var/iri/monitoring/data/grafana|/var/lib/basetool/monitoring/grafana
/var/iri/monitoring/textfile|/var/lib/basetool/textfile
/var/iri/db-backend|/var/lib/basetool/db-backend
/var/iri/db-keycloak|/var/lib/basetool/db-keycloak
/var/iri/redis/users.acl|/var/lib/basetool/redis/users.acl
/var/iri/backup/staging|/var/lib/basetool/backup/staging
/var/iri/backend/log|/var/log/basetool/backend
/var/iri/frontend/log|/var/log/basetool/frontend
/var/iri/ingest/log|/var/log/basetool/ingest
/var/iri/keycloak/log/keycloak.log|/var/log/basetool/keycloak/keycloak.log
/var/lib/iri/restic-cache|/var/cache/basetool/restic
/var/lib/iri|/var/lib/basetool/deploy
/var/lib/iri/current-digest-pin.yml|/var/lib/basetool/deploy/current-digest-pin.yml
/etc/iri/ssg-tailoring.xml|/etc/basetool/ssg-tailoring.xml
/etc/iri/rclone.conf|/etc/basetool/deploy/rclone.conf
/var/log/iri-deploy.log|/var/log/basetool/deploy.log
/var/log/iri-deploy.log-20261018|/var/log/basetool/deploy.log-20261018
/hostlog/iri-backup.log|/hostlog/basetool/backup.log
/var/lock/iri-deploy.lock|/run/lock/basetool-deploy.lock
/var/lock/iri-container-cleanup.lock|/run/lock/basetool-container-cleanup.lock
/tmp/iri-work|/tmp/basetool-work
/home/iri|/home/basetool
/home/iri/.config|/home/basetool/.config
/etc/systemd/system/iri-deploy.service|/etc/systemd/system/basetool-deploy.service
/etc/logrotate.d/iri-backup|/etc/logrotate.d/basetool-backup
/etc/tmpfiles.d/iri-locks.conf|/etc/tmpfiles.d/basetool-locks.conf
https://h.example/auth/realms/iri|https://h.example/auth/realms/basetool
https://h.example/auth/realms/iri/protocol/openid-connect/token|https://h.example/auth/realms/basetool/protocol/openid-connect/token
/etc/iri/a:/var/iri/secrets/b|/etc/basetool/deploy/a:/etc/basetool/secrets/b
"/var/iri/secrets/keystore.p12"|"/etc/basetool/secrets/keystore.p12"
'/etc/iri/rclone.conf'|'/etc/basetool/deploy/rclone.conf'
file:///var/iri/secrets/k|file:///etc/basetool/secrets/k
${KEYCLOAK_HOSTNAME:-https://h/auth}/realms/iri}|${KEYCLOAK_HOSTNAME:-https://h/auth}/realms/basetool}
${OTHER:-/var/iri/secrets/k.p12}|${OTHER:-/etc/basetool/secrets/k.p12}
TABLE

for word in airiness /var/iridium/data /mnt/var/iri/code EDGE=iridium.example.org /realms/iridium iri squirrel-iri-x /opt/siri/code /usr/share/iri-tools /var/lib/iridium /etc/iridium/x; do
  env_case "a value that merely contains iri is untouched: ${word}" "SOME_PATH=${word}"$'\n' "SOME_PATH=${word}"$'\n'
done

env_case "keys: the prefix, the version key, export, spacing" \
  $'IRI_KEYSTORE_HOST_PATH=a\nIRI_BASETOOL_VERSION=1.0\nexport IRI_X = "v"\nIRISH_KEY=1\nEMPIRI_KEY=2\nBASETOOL_KEEP=3\n\tIRI_T=\t4\n' \
  $'BASETOOL_KEYSTORE_HOST_PATH=a\nBASETOOL_VERSION=1.0\nexport BASETOOL_X = "v"\nIRISH_KEY=1\nEMPIRI_KEY=2\nBASETOOL_KEEP=3\n\tBASETOOL_T=\t4\n'

env_case "references inside values follow the key rename" \
  $'IRI_A=1\nB=${IRI_A}/${IRI_BASETOOL_VERSION:-x}/$IRI_A/${IRI_BASETOOL_VERSIONX}\n' \
  $'BASETOOL_A=1\nB=${BASETOOL_A}/${BASETOOL_VERSION:-x}/$BASETOOL_A/${BASETOOL_BASETOOL_VERSIONX}\n'

env_case "IRI_ in a value that is not a reference stays" \
  $'NOTE=see IRI_X and iri_y\n' $'NOTE=see IRI_X and iri_y\n'

env_case "comments and blank lines are left alone" \
  $'# IRI_COMMENTED=/var/iri/code\n\n  # indented /etc/iri\nIRI_A=1\n\n' \
  $'# IRI_COMMENTED=/var/iri/code\n\n  # indented /etc/iri\nBASETOOL_A=1\n\n'

env_case "quotes, hash signs and other values stay byte for byte" \
  $'A="x y"\nB=\'q\'\nC=a#b\nD="/var/iri/secrets/x" # note\nE=  padded  \nIRI_F=\'/etc/iri/z\'\n' \
  $'A="x y"\nB=\'q\'\nC=a#b\nD="/etc/basetool/secrets/x" # note\nE=  padded  \nBASETOOL_F=\'/etc/basetool/deploy/z\'\n'

env_case "a realm-named key with the old realm becomes the new realm" \
  $'KEYCLOAK_REALM=iri\nIRI_ADMIN_REALM="iri"\nOTHER=iri\nDISPLAY_REALM=iridium\nREALMS=iri\n' \
  $'KEYCLOAK_REALM=basetool\nBASETOOL_ADMIN_REALM="basetool"\nOTHER=iri\nDISPLAY_REALM=iridium\nREALMS=iri\n'

env_case "a file without a trailing newline" 'IRI_A=/var/iri/code' 'BASETOOL_A=/opt/basetool'
env_case "an empty value stays empty" $'IRI_EMPTY=\nIRI_B=\n' $'BASETOOL_EMPTY=\nBASETOOL_B=\n'
env_case "a line without an equals sign is not touched" $'IRI_ORPHAN\nA=b\n' $'IRI_ORPHAN\nA=b\n'
env_case "an unrelated file is unchanged" $'A=1\nB=two\n' $'A=1\nB=two\n'

printf 'IRI_A=/var/iri/code\r\nIRI_B=x\r\n' > "${WORK}/crlf.env"
"$PYTHON" -I "$HELPER" env "${WORK}/crlf.env" > /dev/null 2>&1
check "CRLF line endings are kept" test "$(od -An -c "${WORK}/crlf.env" | tr -d ' \n')" = 'BASETOOL_A=/opt/basetool\r\nBASETOOL_B=x\r\n'

printf 'IRI_A=1\nB=\xff\xfe\x0c\nC=/var/iri/code\n' > "${WORK}/bytes.env"
"$PYTHON" -I "$HELPER" env "${WORK}/bytes.env" > /dev/null 2>&1
printf 'BASETOOL_A=1\nB=\xff\xfe\x0c\nC=/opt/basetool\n' > "${WORK}/bytes.expected"
check "bytes outside the rewritten tokens survive, invalid UTF-8 and form feed included" cmp -s "${WORK}/bytes.env" "${WORK}/bytes.expected"

printf 'IRI_A=1\n' > "${WORK}/mode.env"
chmod 640 "${WORK}/mode.env"
MODE_BEFORE="$(stat -c %a "${WORK}/mode.env")"
"$PYTHON" -I "$HELPER" env "${WORK}/mode.env" > /dev/null 2>&1
check "the file is rewritten" grep -qx 'BASETOOL_A=1' "${WORK}/mode.env"
check "the file mode survives the rewrite" test "$(stat -c %a "${WORK}/mode.env")" = "${MODE_BEFORE}"
check "no temporary file is left next to the rewritten file" test "$(find "$WORK" -maxdepth 1 -name '.mode.env.*' | wc -l | tr -d '[:space:]')" = 0

printf 'IRI_X=1\nBASETOOL_X=2\n' > "${WORK}/collide.env"
"$PYTHON" -I "$HELPER" env "${WORK}/collide.env" > "${WORK}/collide.out" 2>&1
rc=$?
check "a rename that collides with an existing key stops with 3" test "$rc" = 3
check "and writes nothing" test "$(cat "${WORK}/collide.env")" = $'IRI_X=1\nBASETOOL_X=2'
check "and names the key" grep -q 'collision: BASETOOL_X' "${WORK}/collide.out"

printf 'IRI_SECRET_KEY=/var/iri/monitoring\nOTHER=/var/iri/npm/x\nFINE=1\n' > "${WORK}/unmapped.env"
"$PYTHON" -I "$HELPER" env "${WORK}/unmapped.env" > "${WORK}/unmapped.out" 2>&1
rc=$?
check "an old path with no mapping stops with 3" test "$rc" = 3
check "and writes nothing" test "$(head -n 1 "${WORK}/unmapped.env")" = 'IRI_SECRET_KEY=/var/iri/monitoring'
check "and names the keys, not the values" bash -c "grep -q 'value of BASETOOL_SECRET_KEY' '${WORK}/unmapped.out' && grep -q 'value of OTHER' '${WORK}/unmapped.out' && ! grep -q '/var/iri' '${WORK}/unmapped.out'"

printf 'IRI_MONITORING_DIR=/somewhere\nA=1\n' > "${WORK}/retired.env"
"$PYTHON" -I "$HELPER" env "${WORK}/retired.env" > "${WORK}/retired.out" 2>&1
rc=$?
check "a key without a successor stops with 3" test "$rc" = 3
check "and says what replaces it" grep -q 'BASETOOL_MONITORING_DIR no longer exists: .*BASETOOL_MONITORING_DATA_DIR' "${WORK}/retired.out"
check "and writes nothing" test "$(head -n 1 "${WORK}/retired.env")" = 'IRI_MONITORING_DIR=/somewhere'

env_case "IRI_MONITORING_DIR with the old default value is dropped, the rest stays" \
  $'IRI_A=1\nIRI_MONITORING_DIR=/var/iri/monitoring\nB=2\n' $'BASETOOL_A=1\nB=2\n'
env_case "the same with quotes" $'IRI_MONITORING_DIR="/var/iri/monitoring"\nB=2\n' $'B=2\n'
printf 'IRI_MONITORING_DIR=/var/iri/monitoring\n' > "${WORK}/dropped.env"
"$PYTHON" -I "$HELPER" env --check "${WORK}/dropped.env" > "${WORK}/dropped.out" 2>&1
check "the dropped line is counted, not shown" bash -c "grep -q 'lines dropped 1' '${WORK}/dropped.out' && ! grep -q '/var/iri' '${WORK}/dropped.out'"

printf '[Unit]\nAfter=iri-backup.service iri-deploy.timer\n# IRI_NOTE /var/iri/npm\n[Service]\nEnvironment=IRI_LOCKFILE=/var/lock/iri-deploy.lock IRI_BASETOOL_VERSION=1\nExecStart=/var/iri/code/scripts/x.sh --realm /realms/iri\n' > "${WORK}/unit.conf"
printf '[Unit]\nAfter=basetool-backup.service basetool-deploy.timer\n# IRI_NOTE /var/iri/npm\n[Service]\nEnvironment=BASETOOL_LOCKFILE=/run/lock/basetool-deploy.lock BASETOOL_VERSION=1\nExecStart=/opt/basetool/scripts/x.sh --realm /realms/basetool\n' > "${WORK}/unit.expected"
"$PYTHON" -I "$HELPER" unit "${WORK}/unit.conf" > "${WORK}/unit.out" 2>&1
check "a drop-in: paths, unit names and IRI_ names change, comments stay" cmp -s "${WORK}/unit.conf" "${WORK}/unit.expected"
"$PYTHON" -I "$HELPER" unit "${WORK}/unit.conf" > "${WORK}/unit2.out" 2>&1
check "a drop-in: a second run reports unchanged" grep -q ': unchanged;' "${WORK}/unit2.out"
printf '[Service]\nExecStart=/var/iri/npm/x\nEnvironment=SECRETX=1\n' > "${WORK}/unit-bad.conf"
"$PYTHON" -I "$HELPER" unit "${WORK}/unit-bad.conf" > "${WORK}/unit-bad.out" 2>&1
rc=$?
check "a drop-in with an unmappable path stops with 3, names the line only and writes nothing" bash -c "test '$rc' = 3 && grep -q 'unmapped old name or path on line 2' '${WORK}/unit-bad.out' && ! grep -q '/var/iri' '${WORK}/unit-bad.out' && test \"\$(head -n 2 '${WORK}/unit-bad.conf' | tail -n 1)\" = 'ExecStart=/var/iri/npm/x'"

printf 'IRI_PASSWORD=VERYSECRETVALUE\nSERVER_SSL_KEY_STORE_PASSWORD=ANOTHERSECRETVALUE\nIRI_PATH=/var/iri/secrets/k\n' > "${WORK}/secret.env"
"$PYTHON" -I "$HELPER" env --check "${WORK}/secret.env" > "${WORK}/secret.out" 2>&1
check "--check prints no value" bash -c "! grep -q -e SECRETVALUE -e '/var/iri' -e '/etc/basetool' '${WORK}/secret.out'"
check "--check writes nothing" test "$(head -n 1 "${WORK}/secret.env")" = 'IRI_PASSWORD=VERYSECRETVALUE'
check "--check reports counts" grep -q 'would change; keys renamed 2, values path-mapped 1' "${WORK}/secret.out"
"$PYTHON" -I "$HELPER" env "${WORK}/secret.env" > "${WORK}/secret2.out" 2>&1
check "the rewrite prints no value" bash -c "! grep -q -e SECRETVALUE -e '/etc/basetool' '${WORK}/secret2.out'"
check "the rewrite reports it changed the file" grep -q ': changed; keys renamed 2' "${WORK}/secret2.out"
"$PYTHON" -I "$HELPER" env "${WORK}/secret.env" > "${WORK}/secret3.out" 2>&1
check "a second run reports unchanged" grep -q ': unchanged;' "${WORK}/secret3.out"

printf '{\n\t"realm":"iri",\n  "id" : "iri",\n  "roles": {"realm": [{"name": "default-roles-iri", "containerId": "iri"}]},\n  "clients": [{"name": "IRIDIUM Basetool Frontend", "description": "Frontend Application for IRIDIUM Basetool", "rootUrl": "https://h/auth/realms/iri/x", "redirectUris": ["/admin/iri/console/*"], "baseUrl": "/auth/admin/iri/"}],\n  "realmNote": "iri",\n  "displayName": "Iridium"\n}' > "${WORK}/realm.json"
printf '{\n\t"realm":"basetool",\n  "id" : "iri",\n  "roles": {"realm": [{"name": "default-roles-basetool", "containerId": "iri"}]},\n  "clients": [{"name": "Profit Basetool Frontend", "description": "Frontend Application for Profit Basetool", "rootUrl": "https://h/auth/realms/basetool/x", "redirectUris": ["/admin/basetool/console/*"], "baseUrl": "/auth/admin/basetool/"}],\n  "realmNote": "iri",\n  "displayName": "Iridium"\n}' > "${WORK}/realm.expected"
cp "${WORK}/realm.json" "${WORK}/realm.orig"
"$PYTHON" -I "$HELPER" realm-export --check "${WORK}/realm.json" > "${WORK}/realm.check" 2>&1
check "realm export --check writes nothing" cmp -s "${WORK}/realm.json" "${WORK}/realm.orig"
check "realm export --check reports counts only" bash -c "grep -q 'would change; realm names 1, default roles 1, realm URLs 3, brand labels 2, old-name occurrences left 3' '${WORK}/realm.check' && ! grep -q 'Frontend' '${WORK}/realm.check'"
"$PYTHON" -I "$HELPER" realm-export "${WORK}/realm.json" > "${WORK}/realm.out" 2>&1
check "realm export: exactly the tokens change, formatting and ids stay" cmp -s "${WORK}/realm.json" "${WORK}/realm.expected"
"$PYTHON" -I "$HELPER" realm-export "${WORK}/realm.json" > "${WORK}/realm2.out" 2>&1
check "realm export: a second run reports unchanged" grep -q ': unchanged;' "${WORK}/realm2.out"
printf '{"realm": "iri",' > "${WORK}/broken.json"
"$PYTHON" -I "$HELPER" realm-export "${WORK}/broken.json" > "${WORK}/broken.out" 2>&1
rc=$?
check "a realm export that is not JSON stops with 3 and is left alone" bash -c "test '$rc' = 3 && test \"\$(cat '${WORK}/broken.json')\" = '{\"realm\": \"iri\",'"
"$PYTHON" -I "$HELPER" env "${WORK}/missing.env" > "${WORK}/missing.out" 2>&1
check "a missing file is reported with exit 1" test "$?" = 1

cat > "${WORK}/meta_test.py" <<'PY'
import importlib.util
import os
import stat
import sys
import tempfile
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location("helper", sys.argv[1])
helper = importlib.util.module_from_spec(spec)
sys.modules["helper"] = helper
spec.loader.exec_module(helper)

failures = []


def check(name, condition):
    print(("  ok    " if condition else "  FAIL  ") + name)
    if not condition:
        failures.append(name)


tmp = Path(tempfile.mkdtemp())
target = tmp / "basetool.env"
target.write_bytes(b"IRI_A=1\n")
os.chmod(target, 0o640)
original = target.stat()
extended = {"system.posix_acl_access": b"ACL", "user.note": b"N", "security.selinux": b"ctx"}
events = []


def record_chmod(path, mode):
    events.append(("chmod", Path(path).name, stat.S_IMODE(mode)))


def record_chown(path, uid, gid):
    events.append(("chown", Path(path).name, uid, gid))


def record_setxattr(path, name, value):
    events.append(("setxattr", Path(path).name, name, value))


with mock.patch.object(os, "chown", create=True, side_effect=record_chown), \
        mock.patch.object(os, "listxattr", create=True, return_value=list(extended)), \
        mock.patch.object(os, "getxattr", create=True, side_effect=lambda p, n: extended[n]), \
        mock.patch.object(os, "setxattr", create=True, side_effect=record_setxattr), \
        mock.patch.object(os, "chmod", side_effect=record_chmod):
    helper._write_atomically(target, b"BASETOOL_A=1\n")

temp_events = [e for e in events if e[1] != target.name]
check("the replacement content is written", target.read_bytes() == b"BASETOOL_A=1\n")
check("the mode of the original is applied to the temporary file",
      any(e[0] == "chmod" and e[2] == stat.S_IMODE(original.st_mode) for e in temp_events))
check("the owner of the original is applied to the temporary file",
      any(e[0] == "chown" and e[2:] == (original.st_uid, original.st_gid) for e in temp_events))
check("the ACL and user attributes are copied",
      {e[2] for e in events if e[0] == "setxattr"} == {"system.posix_acl_access", "user.note"})
check("the SELinux label is not copied", not any(e[0] == "setxattr" and e[2] == "security.selinux" for e in events))
check("the ACL is set before the mode, so the mask follows the mode",
      [e[0] for e in temp_events].index("setxattr") < [e[0] for e in temp_events].index("chmod"))
check("no temporary file is left", [p.name for p in tmp.iterdir()] == ["basetool.env"])

target.write_bytes(b"IRI_A=1\n")
with mock.patch.object(os, "chown", create=True, side_effect=PermissionError), \
        mock.patch.object(os, "geteuid", create=True, return_value=original.st_uid + 1), \
        mock.patch.object(os, "getegid", create=True, return_value=original.st_gid + 1):
    code = helper.process(target, "env", False)
check("an owner that cannot be kept stops the rewrite with 3", code == helper.EXIT_PROBLEM)
check("and leaves the original untouched", target.read_bytes() == b"IRI_A=1\n")
check("and leaves no temporary file", [p.name for p in tmp.iterdir()] == ["basetool.env"])

with mock.patch.object(os, "replace", side_effect=OSError("boom")):
    try:
        helper._write_atomically(target, b"BASETOOL_A=1\n")
        raised = False
    except OSError:
        raised = True
check("a failing replace raises", raised)
check("and leaves the original and no temporary file",
      target.read_bytes() == b"IRI_A=1\n" and [p.name for p in tmp.iterdir()] == ["basetool.env"])

sys.exit(1 if failures else 0)
PY
"$PYTHON" -I "${WORK}/meta_test.py" "$HELPER" > "${WORK}/meta.out" 2>&1
META_RC=$?
sed 's/^  \(ok\|FAIL\)  /  \1    helper: /' "${WORK}/meta.out"
PASSED=$((PASSED + $(grep -c '^  ok' "${WORK}/meta.out")))
FAILED=$((FAILED + $(grep -c '^  FAIL' "${WORK}/meta.out")))
check "the metadata tests ran" test "$(grep -c '^  ok\|^  FAIL' "${WORK}/meta.out")" -ge 10
if (( META_RC != 0 )); then bad "the in-process helper tests exit 0"; fi

echo "== the layout script: usage =="

build_template
restore_state template
run_script
check "no phase prints the usage and exits 2" test "$RC" = 2
run_script bogus
check "an unknown phase exits 2" test "$RC" = 2
run_script stop move
check "two phases exit 2" test "$RC" = 2
run_script --bogus
check "an unknown option exits 2" test "$RC" = 2
run_script --help
check "--help exits 0 and lists every phase" bash -c "test '$RC' = 0 && for p in check stop export-volumes reset-podman accounts move rewrite cleanup import-volumes; do grep -q \"^  \$p \" '$OUT' || exit 1; done"
for p in stop export-volumes reset-podman accounts move rewrite cleanup import-volumes; do
  EUID_FOR_RUN=1000 refused "$p refuses to run unprivileged" 'run as root' "$p"
done
EUID_FOR_RUN=1000 run_script check
check "check does not pass unprivileged: it blocks and says why" bash -c "test '$RC' = 3 && grep -q '^\[block\] not root' '$OUT'"

echo "== check =="

restore_state template
BEFORE="$(tree_full)"
run_script check
AFTER="$(tree_full)"
check "check on a clean old host succeeds" test "$RC" = 0
check "check changes nothing: tree listing, modes, mtimes and checksums" test "$BEFORE" = "$AFTER"
check_calls "check really consulted the system (the stubs were called)" '^systemctl is-active'
check_nomut "check calls no mutating command"
check "check does not create the staging directory" test ! -e "$ROOT/root/basetool-migration"
check "check lists every move row, e.g. the release tree" grep -q '^\[todo\] move /var/iri/code -> /opt/basetool' "$OUT"
check "check lists the env backups" grep -q '^\[todo\] move /var/iri/code/.env.backup-20261001-000000-redis -> /etc/basetool/basetool.env.backup-20261001-000000-redis' "$OUT"
check "check lists the log moves" grep -q '^\[todo\] move /var/log/iri-deploy.log-20261018 -> /var/log/basetool/deploy.log-20261018' "$OUT"
check "check reports the account" grep -q 'account iri uid 994 gid 994 home /home/iri will become basetool' "$OUT"
check "check reports the old and the new label rules" bash -c "grep -q '^\[todo\] old label rule present: /var/iri(/.\*)?' '$OUT' && grep -q '^\[todo\] label rule to add: /opt/basetool/scripts(/.\*)? bin_t' '$OUT'"
check "check reports the env rewrite as counts, without a value" bash -c "grep -q 'would change; keys renamed 7, values path-mapped 4, values realm-mapped 1' '$OUT' && grep -q 'would change; keys renamed 1, values path-mapped 1, values realm-mapped 0' '$OUT' && ! grep -q -E '$SECRETS_RE' '$OUT'"
check "check reports the realm export rewrite" grep -q 'realm names 1, default roles 1' "$OUT"
check "check names the files that still mention the old layout" bash -c "grep -q 'mentions the old layout.*: /etc/systemd/system/iri-deploy.service' '$OUT' && grep -q 'mentions the old layout.*: /etc/sudoers.d/basetool-deploy' '$OUT' && grep -q 'mentions the old layout.*: /etc/default/prometheus-node-exporter' '$OUT'"
check "check prints the plan" grep -q '^\[info\] plan: stop -> export-volumes' "$OUT"
check "check says the cleanup waits for the role" grep -q 'basetool-\* units are not installed yet' "$OUT"
check "check reports the staging directory it would create" grep -q 'staging directory .* will be created' "$OUT"

block_case() {
  local what="$1" pattern="$2" before after
  before="$(tree_full)"
  run_script check
  after="$(tree_full)"
  check "check blocks: ${what}" bash -c "test '$RC' = 3 && grep -qE '^\[block\] ${pattern}' '$OUT'"
  check "check blocks: ${what} (and still writes nothing)" test "$before" = "$after"
}

restore_state template
mv "$ROOT/var/iri/db-backend" "$ROOT/var/iri/db-backend.gone"
block_case "a required path absent on both sides" 'required, absent on both sides: /var/iri/db-backend'
restore_state template
mkdir -p "$ROOT/var/iri/npm"
block_case "an entry the plan does not know" 'unplanned entry in /var/iri: npm'
restore_state template
mkdir -p "$ROOT/var/iri/monitoring/extra"
block_case "an unknown entry below /var/iri/monitoring" 'unplanned entry in /var/iri/monitoring: extra'
restore_state template
mkfile "$ROOT/etc/basetool/basetool.env" 'in the way'
block_case "a target that exists next to its source" 'both exist: /var/iri/code/.env and /etc/basetool/basetool.env'
restore_state template
mkdir -p "$ROOT/home/basetool"
block_case "the new home already existing" '/home/basetool already exists'
restore_state template
printf 'iri:200000:65536\n' > "$ROOT/etc/subuid"
block_case "a subuid range other than the expected one" 'subuid/subgid do not both hold iri:100000:65536'
restore_state template
printf 'basetool:x:995:995::/home/b:/sbin/nologin\n' >> "$ROOT/etc/passwd"
block_case "both accounts existing" 'both iri and basetool exist'
restore_state template
rm "$ROOT/etc/containers/systemd/users/994/edge-certs.volume"
block_case "a missing Quadlet volume unit" 'volume unit missing: edge-certs.volume'
restore_state template
mkfile "$ROOT/var/lib/iri/config-apply.incomplete" ''
block_case "an unfinished config apply" 'an earlier config apply did not complete'
restore_state template
printf 'IRI_MONITORING_DIR=/elsewhere\n' >> "$ROOT/var/iri/code/.env"
block_case "an env file the rewrite would refuse" 'the env rewrite of /var/iri/code/.env would refuse'
restore_state template
rm "$ROOT/var/iri/code/realm-export.json"
block_case "a missing realm export" 'realm-export.json not found'
restore_state template
printf '{"realm":' > "$ROOT/var/iri/code/realm-export.json"
block_case "a realm export that is not JSON" 'the realm export cannot be rewritten'

restore_state template
run_script check
check "check lists the hand-made drop-ins it will carry over, and the role-managed ones it will not" bash -c "grep -q '^\[todo\] drop-in carried over: /etc/systemd/system/iri-deploy.service.d/override.conf -> /etc/systemd/system/basetool-deploy.service.d/override.conf' '$OUT' && grep -q '^\[todo\] drop-in carried over: .*iri-backup.timer.d/schedule.conf -> .*basetool-backup.timer.d/schedule.conf' '$OUT' && grep -q '^\[info\] role-managed drop-in, not carried over: .*iri-deploy.service.d/10-deploy-account-sandbox.conf' '$OUT'"
check "check lists host-local leftovers by name" bash -c "grep -q '^\[todo\] move /var/iri/code/.env.bak1 -> /etc/basetool/host-local/.env.bak1' '$OUT' && grep -q '^\[todo\] move /var/iri/code/kc-client.before.json -> /etc/basetool/host-local/kc-client.before.json' '$OUT'"
check "check prints no content of them" bash -c "! grep -q HOSTLOCAL '$OUT'"
check "check does not list release entries as host-local" bash -c "! grep -qE 'host-local/(scripts|monitoring|keycloak|docker-compose.yml)' '$OUT'"
restore_state template
mkfile "$ROOT/etc/systemd/system/iri-deploy.service.d/bad.conf" $'[Service]\nExecStart=/var/iri/npm/x\n'
block_case "a hand-made drop-in that cannot be carried over" 'the drop-in /etc/systemd/system/iri-deploy.service.d/bad.conf cannot be carried over'
check "and the report names the line, not the content" bash -c "grep -q 'unmapped old name or path on line 2' '$OUT' && ! grep -q '/var/iri/npm' '$OUT'"
restore_state template
printf 'IRI_MONITORING_DIR=/elsewhere\n' >> "$ROOT/var/iri/code/.env.backup-20261001-000000-redis"
run_script check
check "check: an old backup that cannot be rewritten blocks nothing, and is reported" bash -c "test '$RC' = 0 && grep -q '^\[info\] the backup /var/iri/code/.env.backup-20261001-000000-redis cannot be rewritten' '$OUT'"
restore_state template
printf '%s\n' /var/log > "$FAKE/other-fs"
block_case "a target root on another filesystem" '/var/iri and /var/log are on different filesystems'
restore_state template
sed -i "s/^iri:x:994:994/iri:x:$(id -u):994/" "$ROOT/etc/passwd"
run_script check
check "check lists deploy-state entries owned by the service uid, by name" bash -c "grep -q 'owned by the service uid .*: .*\.docker' '$OUT'"

restore_state template
touch "$FAKE/active/iri-deploy.timer" "$FAKE/active/iri-backup.service" "$FAKE/active/alloy.service"
printf '  110000  4242 postgres\n  110001  4243 java\n  994  12 systemd\n' > "$FAKE/ps.out"
BEFORE="$(tree_full)"
run_script check
check "check works before the window: a running stack is reported, not a failure" bash -c "test '$RC' = 0 && ! grep -q '^\[block\]' '$OUT'"
check "check says what stop will find: active units by name, services, manager, container processes" bash -c "grep -q 'window: 2 old unit(s) active (.*iri-deploy.timer' '$OUT' && grep -q 'window: alloy.service active, dnf-automatic.timer active, user@994.service active; stop will stop all three' '$OUT' && grep -q 'window: 2 process(es) in the subuid range now' '$OUT'"
check "and still changes nothing" test "$BEFORE" = "$(tree_full)"
: > "$FAKE/ps.out"

echo "== stop =="

restore_state template
printf '  994  4242 systemd\n' > "$FAKE/ps.out"
refused_late "stop refuses while a process of the account still runs" 'processes of uid 994' stop
check_calls "stop has disabled the timers before it refused" 'systemctl disable --now iri-deploy.timer'
restore_state template
touch "$FAKE/active/iri-backup.service"
refused_late "stop refuses while a job is running and names it" 'still running: iri-backup.service' stop
check_calls "stop disabled the timers before it refused, so no new job starts" 'systemctl disable --now iri-backup.timer'
check "stop touched neither alloy nor the user manager" bash -c "! grep -qE 'systemctl stop (alloy|user@)' '$CALLS'"

restore_state template
touch "$FAKE/active/iri-deploy.timer" "$FAKE/active/alloy.service"
dry_run_changes_nothing "stop" stop
check "stop --dry-run names the first timer it would disable" grep -q 'would: systemctl disable --now iri-deploy.timer' "$OUT"

restore_state template
touch "$FAKE/active/iri-deploy.timer" "$FAKE/active/alloy.service"
phase_ok "stop succeeds" stop
check "stop disables every old timer" bash -c "for t in deploy backup restore-drill container-cleanup container-metrics cert-expiry; do grep -q \"systemctl disable --now iri-\$t.timer\" '$CALLS' || exit 1; done"
check "no old timer is enabled any more" bash -c "! ls '$FAKE'/enabled/iri-*.timer > /dev/null 2>&1"
check "stop disables the old service that has an install section" bash -c "grep -q 'systemctl disable iri-host-updates-metrics.service' '$CALLS' && test ! -e '$FAKE/enabled/iri-host-updates-metrics.service'"
check_calls "stop stops alloy" 'systemctl stop alloy.service'
check_calls "stop stops the user manager" 'systemctl stop user@994.service'
check_calls "stop stops the automatic updates for the window" 'systemctl stop dnf-automatic.timer'
check "stop remembers that it stopped them" test -e "$ROOT/root/basetool-migration/state/dnf-stopped"
check "stop parks the container units, their drop-ins and the networks" bash -c "S='$ROOT/root/basetool-migration/units'; test -e \$S/backend.container && test -e \$S/backend.container.d/10-digest-pin.conf && test -e \$S/frontend-net.network"
check "stop keeps the three volume units where they are" bash -c "for v in edge-certs edge-acme-state edge-acme-webroot; do test -e '$ROOT/etc/containers/systemd/users/994/'\$v.volume || exit 1; done; test \"\$(ls '$ROOT/etc/containers/systemd/users/994' | wc -l | tr -d '[:space:]')\" = 3"
check "stop creates the staging directory with mode 0700" grep -q "chmod 0700 ${ROOT}/root/basetool-migration\$" "$OUT"
check "stop moves no data" bash -c "test -e '$ROOT/var/iri/code/.env' && test ! -e '$ROOT/etc/basetool'"
save_state s1
noop_rerun "stop" stop

echo "== export-volumes =="

restore_state template
refused "export-volumes refuses before stop (timer active)" 'run the stop phase first' export-volumes
restore_state s1
touch "$FAKE/active/iri-deploy.timer"
refused "export-volumes refuses while a timer is active" 'iri-deploy.timer is still active' export-volumes
restore_state s1
printf '  110000  999 postgres\n' > "$FAKE/ps.out"
refused "export-volumes refuses while container processes run" 'container processes' export-volumes
restore_state s1
mkfile "$ROOT/etc/containers/systemd/users/994/backend.container" 'unit'
refused "export-volumes refuses while Quadlet units are not parked" 'Quadlet units are still in' export-volumes
restore_state s1
rm "$ROOT/etc/containers/systemd/users/994/edge-acme-state.volume"
refused "export-volumes refuses without the volume unit" 'edge-acme-state.volume does not exist' export-volumes
restore_state s1
rm -rf "$FAKE/podman-store/vol/edge-acme-webroot"
refused_late "export-volumes refuses when a volume is missing in the store" 'edge-acme-webroot does not exist in the store' export-volumes
check "and exported none of the three" bash -c "! ls '$ROOT'/root/basetool-migration/volumes/*.tar > /dev/null 2>&1"
check_calls "and stopped the runtime directory again" 'systemctl stop user-runtime-dir@994.service'

restore_state s1
dry_run_changes_nothing "export-volumes" export-volumes
restore_state s1
phase_ok "export-volumes succeeds" export-volumes
V="$ROOT/root/basetool-migration/volumes"
check "export-volumes writes one non-empty archive and hash per volume" bash -c "cd '$V' && for v in edge-certs edge-acme-state edge-acme-webroot; do test -s \$v.tar && test -s \$v.tar.sha256 && sha256sum --check --status \$v.tar.sha256 || exit 1; done"
check "the archive holds the key material" bash -c "tar -tf '$V/edge-certs.tar' | grep -q privkey.pem"
check "the archive of the other volume holds its account" bash -c "tar -xOf '$V/edge-acme-state.tar' ./account.json | grep -qx ACME-ACCOUNT"
check "no partial file is left behind" bash -c "! ls '$V'/*.partial > /dev/null 2>&1"
check_calls "the export runs as the old user with its own home" 'runuser -u iri -- env HOME=/home/iri XDG_RUNTIME_DIR=/run/user/994 podman volume export edge-certs'
check "only the runtime directory is started and stopped, never the user manager" bash -c "grep -q 'systemctl start user-runtime-dir@994.service' '$CALLS' && grep -q 'systemctl stop user-runtime-dir@994.service' '$CALLS' && ! grep -q 'start user@994' '$CALLS'"
save_state s2
noop_rerun "export-volumes" export-volumes
check "a second run exports nothing again" bash -c "! grep -q 'volume export' '$CALLS'"
printf 'tampered' >> "$V/edge-certs.tar"
run_script export-volumes
check "an archive that no longer matches its hash is exported again" bash -c "test '$RC' = 0 && grep -q 'volume export edge-certs' '$CALLS' && ! grep -q 'volume export edge-acme-state' '$CALLS' && cd '$V' && sha256sum --check --status edge-certs.tar.sha256 && tar -tf edge-certs.tar > /dev/null"

echo "== reset-podman =="

restore_state s1
refused "reset-podman refuses without any export" 'no verified, non-empty export' reset-podman
restore_state s2
rm "$ROOT/root/basetool-migration/volumes/edge-acme-webroot.tar"
refused "reset-podman refuses when one of the three archives is missing" 'no verified, non-empty export' reset-podman
restore_state s2
rm "$ROOT/root/basetool-migration/volumes/edge-acme-webroot.tar.sha256"
refused "reset-podman refuses when one hash file is missing" 'no verified, non-empty export' reset-podman
restore_state s2
printf 'tampered' >> "$ROOT/root/basetool-migration/volumes/edge-certs.tar"
refused "reset-podman refuses when an archive no longer matches its hash" 'no verified, non-empty export' reset-podman
restore_state s2
: > "$ROOT/root/basetool-migration/volumes/edge-certs.tar"
( cd "$ROOT/root/basetool-migration/volumes" && sha256sum edge-certs.tar > edge-certs.tar.sha256 )
refused "reset-podman refuses an empty archive even when its hash matches" 'no verified, non-empty export' reset-podman
restore_state s2
touch "$FAKE/active/iri-deploy.timer"
refused "reset-podman refuses before stop" 'iri-deploy.timer is still active' reset-podman
restore_state s2
printf '  110000  999 postgres\n' > "$FAKE/ps.out"
refused "reset-podman refuses while container processes run" 'container processes' reset-podman

restore_state s2
dry_run_changes_nothing "reset-podman" reset-podman
check "reset-podman --dry-run leaves the volumes in the store" test -e "$FAKE/podman-store/vol/edge-certs/privkey.pem"
restore_state s2
phase_ok "reset-podman succeeds" reset-podman
check_calls "reset-podman resets as the old user" 'runuser -u iri -- env HOME=/home/iri XDG_RUNTIME_DIR=/run/user/994 podman system reset --force'
check "the store is empty afterwards" test ! -e "$FAKE/podman-store/vol/edge-certs"
check "the old user's container configuration is archived first" bash -c "tar -tf '$ROOT/root/basetool-migration/user-containers-config.tar' | grep -q 'containers.conf'"
check "the reset is recorded" test -e "$ROOT/root/basetool-migration/state/reset.done"
check "the exports survive the reset" bash -c "cd '$ROOT/root/basetool-migration/volumes' && sha256sum --check --status edge-certs.tar.sha256"
save_state s3
noop_rerun "reset-podman" reset-podman
check "a second run resets nothing again" bash -c "! grep -q 'system reset' '$CALLS'"

echo "== podman leaves its pause process behind =="

restore_state s1
touch "$FAKE/pause-process"
phase_ok "export-volumes completes although podman leaves a pause process" export-volumes
check_calls "only the pause process of the account is ended" '^pkill -U 994 -x catatonit$'
check "and nothing else was killed" test "$(grep -c '^pkill' "$CALLS")" = 1
check "nothing of the account is left running" test ! -s "$FAKE/ps.out"
phase_ok "reset-podman completes the same way" reset-podman
check "reset.done is written" test -e "$ROOT/root/basetool-migration/state/reset.done"
phase_ok "accounts proceeds" accounts
restore_state s1
touch "$FAKE/pause-process" "$FAKE/other-process"
refused_late "a real other process of the account still refuses, the pause process or not" 'processes of uid 994' export-volumes
check "only the pause process was ended" bash -c "grep -q 'pkill -U 994 -x catatonit' '$CALLS' && grep -q sleep '$FAKE/ps.out'"

echo "== reset-podman: a second podman process =="

restore_state s1
touch "$FAKE/active/user@994.service"
refused "export-volumes refuses while the user manager runs" 'user manager user@994.service is running' export-volumes
restore_state s2
touch "$FAKE/recreate-db"
refused_late "reset-podman refuses when a store reappears after the reset" 'reappeared' reset-podman
check "and records no reset" test ! -e "$ROOT/root/basetool-migration/state/reset.done"
refused "accounts then refuses as well" 'reset-podman has not completed' accounts
restore_state s3
check "after a good reset the old store is gone" test ! -e "$ROOT/home/iri/.local/share/containers/storage"
mkdir -p "$ROOT/home/iri/.local/share/containers/storage/libpod"
refused "accounts refuses while a podman store exists under the old home" 'podman store exists' accounts

echo "== accounts =="

restore_state s2
refused "accounts refuses before the reset" 'reset-podman has not completed' accounts
restore_state s3
printf '  994  777 systemd\n' > "$FAKE/ps.out"
refused "accounts refuses while a process of the account runs" 'processes of uid 994' accounts
restore_state s3
printf '  120000  778 postgres\n' > "$FAKE/ps.out"
refused "accounts refuses while a process of the subuid range runs" 'processes of uid 994 or of its subuid range' accounts
restore_state s3
printf 'iri:200000:65536\ngreluc:589824:65536\n' > "$ROOT/etc/subuid"
refused "accounts refuses a subuid range other than 100000:65536" 'no iri:100000:65536 line' accounts
restore_state s3
printf 'iri:100000:1000\ngreluc:589824:65536\n' > "$ROOT/etc/subgid"
refused "accounts refuses a subgid range other than 100000:65536" 'no iri:100000:65536 line' accounts
restore_state s3
printf 'basetool:100000:65536\n' >> "$ROOT/etc/subuid"
refused "accounts refuses a subuid file that has both names" 'has both iri and basetool' accounts
restore_state s3
mkdir -p "$ROOT/home/basetool"
refused "accounts refuses when the new home already exists" '/home/basetool already exists' accounts
restore_state s3
printf 'basetool:x:995:995::/home/b:/sbin/nologin\n' >> "$ROOT/etc/passwd"
refused "accounts refuses when both users exist" 'both iri and basetool exist' accounts
restore_state s3
printf 'basetool:x:995:\n' >> "$ROOT/etc/group"
refused "accounts refuses when both groups exist" 'both group iri and group basetool exist' accounts

restore_state s3
restore_state s3
dry_run_changes_nothing "accounts" accounts
check "accounts --dry-run names the rename" bash -c "grep -q 'would: usermod -l basetool iri' '$OUT' && grep -q 'would: usermod -d /home/basetool -m basetool' '$OUT'"
restore_state s3
phase_ok "accounts succeeds" accounts
check "user renamed, uid and gid kept, home moved" grep -qx 'basetool:x:994:994::/home/basetool:/sbin/nologin' "$ROOT/etc/passwd"
check "the other accounts are unchanged" bash -c "grep -qx 'deploy:x:992:992::/var/lib/iri:/sbin/nologin' '$ROOT/etc/passwd' && grep -qx 'root:x:0:0:root:/root:/bin/bash' '$ROOT/etc/passwd'"
check "group renamed, gid kept" grep -qx 'basetool:x:994:' "$ROOT/etc/group"
check "no account or group is left under the old name" bash -c "! grep -q '^iri:' '$ROOT/etc/passwd' '$ROOT/etc/group' '$ROOT/etc/subuid' '$ROOT/etc/subgid'"
check "subuid and subgid carry the new name with the same range, the other entry stays" bash -c "grep -qx 'basetool:100000:65536' '$ROOT/etc/subuid' && grep -qx 'basetool:100000:65536' '$ROOT/etc/subgid' && grep -qx 'greluc:589824:65536' '$ROOT/etc/subuid' && grep -qx 'greluc:589824:65536' '$ROOT/etc/subgid'"
check "the home moved with its content, nothing is left at the old path" bash -c "test -e '$ROOT/home/basetool/.config/containers/containers.conf' && test ! -e '$ROOT/home/iri'"
check "linger moved to the new name" bash -c "test -e '$ROOT/var/lib/systemd/linger/basetool' && test ! -e '$ROOT/var/lib/systemd/linger/iri'"
check "the order is: disable linger, groupmod, usermod -l, usermod -d -m, enable linger" bash -c "a=\$(grep -n 'loginctl disable-linger iri' '$CALLS' | cut -d: -f1); b=\$(grep -n 'groupmod -n basetool iri' '$CALLS' | cut -d: -f1); c=\$(grep -n 'usermod -l basetool iri' '$CALLS' | cut -d: -f1); d=\$(grep -n 'usermod -d /home/basetool -m basetool' '$CALLS' | cut -d: -f1); e=\$(grep -n 'loginctl enable-linger basetool' '$CALLS' | cut -d: -f1); test \$a -lt \$b -a \$b -lt \$c -a \$c -lt \$d -a \$d -lt \$e"
save_state s4
noop_rerun "accounts" accounts
check "a second run, with the lingering manager running, does not refuse" test -e "$FAKE/active/user@994.service"

restore_state s3
sed -i 's/^iri:x:994:$/basetool:x:994:/' "$ROOT/etc/group"
phase_ok "accounts resumes after a rename that stopped after the group" accounts
check "the resumed run renames the user once and ends in the same state" bash -c "grep -qx 'basetool:x:994:994::/home/basetool:/sbin/nologin' '$ROOT/etc/passwd' && ! grep -q groupmod '$CALLS' && grep -c 'usermod -l' '$CALLS' | grep -qx 1 && test -e '$ROOT/var/lib/systemd/linger/basetool'"
restore_state s3
sed -i 's/^iri:x:994:$/basetool:x:994:/' "$ROOT/etc/group"
sed -i 's/^iri:x:994:994::\/home\/iri/basetool:x:994:994::\/home\/iri/' "$ROOT/etc/passwd"
rm "$ROOT/var/lib/systemd/linger/iri"
phase_ok "accounts resumes after a rename that stopped before the sub-id files" accounts
check "the resumed run fixes subuid, subgid and the home" bash -c "grep -qx 'basetool:100000:65536' '$ROOT/etc/subuid' && grep -qx 'basetool:100000:65536' '$ROOT/etc/subgid' && test -e '$ROOT/home/basetool/.config/containers/containers.conf' && ! grep -q 'usermod -l' '$CALLS'"

echo "== move =="

restore_state s3
refused "move refuses while the old account still exists" 'run the accounts phase first' move
restore_state s4
printf '  120000  999 postgres\n' > "$FAKE/ps.out"
refused "move refuses while container processes run" 'container processes' move
restore_state s4
mkfile "$ROOT/etc/basetool/basetool.env" 'in the way'
refused "move refuses a target that exists with other content" '1 path\(s\) are in conflict' move
check "the conflict leaves the source in place" test -e "$ROOT/var/iri/code/.env"
restore_state s4
mkfile "$ROOT/opt/basetool/keycloak/providers/stray.jar" 'role-created'
refused "move refuses a non-empty target directory" 'in conflict' move
restore_state s4
mv "$ROOT/var/iri/db-keycloak" "$ROOT/var/iri/db-keycloak.gone"
refused "move refuses when a required path is absent" 'conflict or required but absent' move

restore_state s4
dry_run_changes_nothing "move" move

restore_state s4
phase_ok "move succeeds" move
for row in "${FILES[@]}"; do
  old="${row%%|*}"
  new="${row##*|}"
  check "row ${old} -> ${new}: content arrives, source is gone" bash -c "test \"\$(cat '${ROOT}${new}')\" = '${old}' && test ! -e '${ROOT}${old}'"
done
check "row .env -> basetool.env (outside the release tree)" bash -c "test \"\$(cat '$ROOT/etc/basetool/basetool.env')\" = \"\$(printf '%s' '$ENV_OLD')\" && test ! -e '$ROOT/opt/basetool/.env' && test ! -e '$ROOT/opt/basetool/basetool.env'"
check "row env backups -> basetool.env.backup-*" bash -c "test \"\$(cat '$ROOT/etc/basetool/basetool.env.backup-20260925-175805-step4')\" = \"\$(printf '%s' '$ENV_BACKUP_A')\" && test \"\$(cat '$ROOT/etc/basetool/basetool.env.backup-20261001-000000-redis')\" = \"\$(printf '%s' '$ENV_BACKUP_B')\" && ! ls '$ROOT'/opt/basetool/.env* > /dev/null 2>&1"
check "row realm-export.json -> /etc/basetool" bash -c "test -f '$ROOT/etc/basetool/realm-export.json' && test ! -e '$ROOT/opt/basetool/realm-export.json'"
check "row env.d stays a directory beside the env file" test -d "$ROOT/etc/basetool/env.d" -a ! -e "$ROOT/opt/basetool/env.d"
check "row /etc/iri/backup.env -> deploy/backup.env" bash -c "test \"\$(cat '$ROOT/etc/basetool/deploy/backup.env')\" = \"\$(printf '%s' '$BACKUP_ENV_OLD')\""
check "no old path is left: /var/iri holds no file, /etc/iri and /var/lib/iri are gone" bash -c "test -z \"\$(find '$ROOT/var/iri' -type f)\" && test ! -e '$ROOT/etc/iri' && test ! -e '$ROOT/var/lib/iri' && ! ls '$ROOT'/var/log/iri-* > /dev/null 2>&1"
EXPECTED_CHOWN="$(for d in opt etc/basetool etc/basetool/host-local etc/basetool/monitoring var/lib/basetool var/log/basetool var/cache var/cache/basetool; do printf 'chown root:root %s/%s\n' "$ROOT" "$d"; done | LC_ALL=C sort)"
EXPECTED_CHMOD="$(for d in opt etc/basetool etc/basetool/monitoring var/lib/basetool var/log/basetool var/cache var/cache/basetool; do printf '  do: chmod 0755 %s/%s\n' "$ROOT" "$d"; done | LC_ALL=C sort)"
check "exactly the missing new roots are created, root:root, and no moved path is chowned" test "$(grep '^chown ' "$CALLS" | LC_ALL=C sort)" = "$EXPECTED_CHOWN"
check "and each of them gets mode 0755" test "$(grep '^  do: chmod 0755 ' "$OUT" | LC_ALL=C sort)" = "$EXPECTED_CHMOD"
check "host-local leftovers go to /etc/basetool/host-local, never into the release tree" bash -c "test \"\$(cat '$ROOT/etc/basetool/host-local/.env.bak1')\" = HOSTLOCAL-SECRET-ONE && test \"\$(cat '$ROOT/etc/basetool/host-local/kc-client.before.json')\" = HOSTLOCAL-JSON && test ! -e '$ROOT/opt/basetool/.env.bak1' && test ! -e '$ROOT/opt/basetool/kc-client.before.json' && test ! -e '$ROOT/var/iri/code'"
check "the host-local directory is made root:root 0700" bash -c "grep -q 'do: chmod 0700 $ROOT/etc/basetool/host-local\$' '$OUT' && grep -q 'do: chown root:root $ROOT/etc/basetool/host-local\$' '$OUT'"
check "a host-local file keeps its mode" test "$(stat -c %a "$ROOT/etc/basetool/host-local/.env.bak1")" = "$(stat -c %a "$CACHE/s4/root/var/iri/code/.env.bak1")"
check "release entries are not treated as host-local" bash -c "! ls '$ROOT/etc/basetool/host-local' | grep -qE '^(scripts|monitoring|keycloak|docker-compose.yml)\$'"
check "semanage, usermod and groupmod ran with umask 0022" bash -c "grep -q '^semanage' '$FAKE/umask.log' && grep -q '^usermod' '$FAKE/umask.log' && grep -q '^groupmod' '$FAKE/umask.log' && ! grep -qv ' 0022\$' '$FAKE/umask.log'"
check "the deploy account's home follows its state directory" grep -qx 'deploy:x:992:992::/var/lib/basetool/deploy:/sbin/nologin' "$ROOT/etc/passwd"
while read -r pattern type; do
  check_fctx "label rule ${pattern} ${type}" "$pattern" "$type"
done <<'RULES'
/opt/basetool(/.*)? container_file_t
/opt/basetool/scripts(/.*)? bin_t
/etc/basetool(/.*)? container_file_t
/etc/basetool/deploy(/.*)? etc_t
/var/lib/basetool(/.*)? container_file_t
/var/lib/basetool/deploy(/.*)? var_lib_t
/var/log/basetool/(backend|frontend|ingest|keycloak)(/.*)? container_file_t
RULES
check "the host-script logs get no container label rule" bash -c "! awk -F'\t' '\$1 == \"/var/log/basetool(/.*)?\"' '$FAKE/fcontext.local' | grep -q ."
check "the two old label rules are deleted" bash -c "! grep -qF '/var/iri' '$FAKE/fcontext.local'"
check_calls "restorecon relabels exactly the five new roots" "^restorecon -RF ${ROOT}/opt/basetool ${ROOT}/etc/basetool ${ROOT}/var/lib/basetool ${ROOT}/var/log/basetool ${ROOT}/var/cache/basetool\$"
check "the env file is moved before the release tree" bash -c "a=\$(grep -n 'do: mv .*var/iri/code/.env ' '$OUT' | head -n 1 | cut -d: -f1); b=\$(grep -n 'do: mv .*var/iri/code .*opt/basetool' '$OUT' | head -n 1 | cut -d: -f1); test -n \"\$a\" -a -n \"\$b\" && test \$a -lt \$b"
check "the stack's own data is moved, not copied" bash -c "! grep -q 'do: cp' '$OUT'"
save_state s5
noop_rerun "move" move

restore_state s4
mkdir -p "$ROOT/var/lib/basetool"
chmod 0755 "$ROOT/var/lib/basetool"
mv "$ROOT/var/iri/db-backend" "$ROOT/var/lib/basetool/db-backend"
phase_ok "move resumes after one row was moved by hand" move
check "the resumed run ends in the state of an uninterrupted one" test "$(tree_content)" = "$(tree_content "$CACHE/s5/root")"
check "the resumed run did not move the row that was done" bash -c "! grep -q 'do: mv .*db-backend' '$OUT' && grep -q 'already in place: /var/lib/basetool/db-backend' '$OUT'"

restore_state s4
mkdir -p "$ROOT/etc/basetool/env.d" "$ROOT/etc/basetool/secrets" "$ROOT/opt/basetool"
phase_ok "move replaces empty target directories that a role run left behind" move
check "and the content arrived" test -f "$ROOT/etc/basetool/secrets/keystore.p12" -a -f "$ROOT/opt/basetool/scripts/deploy.sh" -a -f "$ROOT/etc/basetool/env.d/backend.env"

echo "== rewrite =="

restore_state s4
refused "rewrite refuses before the move" 'run the move phase first' rewrite
restore_state s5
dry_run_changes_nothing_rewrite() {
  local before after
  before="$(tree_full)"
  run_script --dry-run rewrite
  after="$(tree_full)"
  check "rewrite: --dry-run succeeds" test "$RC" = 0
  check "rewrite: --dry-run changes no file and stages nothing" test "$before" = "$after"
  check "rewrite: --dry-run reports what would change" grep -q 'would change; keys renamed' "$OUT"
  check "rewrite: --dry-run prints no value" bash -c "! grep -qE '$SECRETS_RE' '$OUT'"
}
dry_run_changes_nothing_rewrite

restore_state s5
printf 'NPM_DIR=/var/iri/npm/data\n' >> "$ROOT/etc/basetool/deploy/backup.env"
refused "rewrite refuses an old path it cannot map, in the last file, and rewrites NONE of the files" 'cannot be rewritten as it is' rewrite
check "the refusal named the key, not the value" bash -c "grep -q 'value of NPM_DIR' '$OUT' && ! grep -q '/var/iri/npm' '$OUT'"
restore_state s5
printf 'IRI_MONITORING_DIR=/x\n' >> "$ROOT/etc/basetool/basetool.env"
refused "rewrite refuses a key without a successor and rewrites none of the files" 'cannot be rewritten as it is' rewrite
restore_state s5
printf 'BASETOOL_KEEP_DAILY=9\n' >> "$ROOT/etc/basetool/deploy/backup.env"
refused "rewrite refuses a key collision and rewrites none of the files" 'cannot be rewritten as it is' rewrite
restore_state s5
printf 'not json' > "$ROOT/etc/basetool/realm-export.json"
refused "rewrite refuses a realm export that is not JSON and rewrites none of the files" 'cannot be rewritten as it is' rewrite

restore_state s5
ENVF="$ROOT/etc/basetool/basetool.env"
printf '%s\n' "$ROOT/etc/basetool/basetool.env unlabeled" > "$FAKE/restorecon.pending"
phase_ok "rewrite succeeds" rewrite
check "rewrite prints no value" bash -c "! grep -qE '$SECRETS_RE' '$OUT'"
check "the env file has BASETOOL_ keys with mapped paths" bash -c "grep -qx 'BASETOOL_KEYSTORE_HOST_PATH=/etc/basetool/secrets/keystore.p12' '$ENVF' && grep -qx 'BASETOOL_VERSION=1.19.12' '$ENVF' && grep -qx 'BASETOOL_IMAGE_NAMESPACE=krt-profit' '$ENVF' && grep -qx 'BASETOOL_COMPOSE_DIR=/opt/basetool' '$ENVF' && grep -qx 'BASETOOL_ENV_D_DIR=/etc/basetool/env.d' '$ENVF' && grep -qx 'BASETOOL_LOCKFILE=/run/lock/basetool-deploy.lock' '$ENVF'"
check "values that are not paths, and the comment, stay" bash -c "grep -qx 'SERVER_SSL_KEY_STORE_PASSWORD=TOPSECRETVALUE' '$ENVF' && grep -qx 'EDGE_HOST_FRONTEND=profit-base.online' '$ENVF' && grep -qx '# IRI_KEEP=/var/iri/code' '$ENVF'"
check "the issuer URL names the new realm" grep -qx 'BASETOOL_KEYCLOAK_ISSUER_URI=https://profit-base.online/auth/realms/basetool' "$ENVF"
check "the env file has the same number of lines as before" test "$(wc -l < "$ENVF" | tr -d '[:space:]')" = "$(printf '%s' "$ENV_OLD" | wc -l | tr -d '[:space:]')"
check "no uncommented IRI_ key is left in the env file" bash -c "! grep -q '^IRI_' '$ENVF'"
check "both env backups are rewritten too" bash -c "grep -qx 'BASETOOL_KEYSTORE_HOST_PATH=/etc/basetool/secrets/keystore.p12' '$ROOT/etc/basetool/basetool.env.backup-20260925-175805-step4' && grep -qx 'BASETOOL_STATE_DIR=/var/lib/basetool/deploy' '$ROOT/etc/basetool/basetool.env.backup-20261001-000000-redis' && grep -qx 'REDIS_PASSWORD=ANOTHERSECRET' '$ROOT/etc/basetool/basetool.env.backup-20260925-175805-step4'"
check "backup.env: key renamed, RCLONE_CONFIG mapped, the secret untouched" bash -c "f='$ROOT/etc/basetool/deploy/backup.env'; grep -qx 'RCLONE_CONFIG=/etc/basetool/deploy/rclone.conf' \$f && grep -qx 'BASETOOL_KEEP_DAILY=7' \$f && grep -qx 'RESTIC_PASSWORD=RESTICSECRET' \$f && grep -qx 'RESTIC_REPOSITORY=rclone:nextcloud:Basetool-Backups' \$f"
check "the realm export names the new realm, role, URLs and client labels" bash -c "f='$ROOT/etc/basetool/realm-export.json'; grep -q '\"realm\": \"basetool\"' \$f && grep -q 'default-roles-basetool' \$f && grep -q '/realms/basetool/x' \$f && grep -q '/admin/basetool/console' \$f && grep -q 'Profit Basetool Frontend' \$f && ! grep -q 'IRIDIUM' \$f"
check "the originals are staged before the rewrite, byte for byte" bash -c "S='$ROOT/root/basetool-migration/originals/etc/basetool'; test \"\$(cat \$S/basetool.env)\" = \"\$(printf '%s' '$ENV_OLD')\" && test \"\$(cat \$S/deploy/backup.env)\" = \"\$(printf '%s' '$BACKUP_ENV_OLD')\" && test \"\$(cat \$S/realm-export.json)\" = \"\$(printf '%s' '$REALM_OLD')\" && test -f \$S/basetool.env.backup-20260925-175805-step4"
check "every rollback anchor, pin, marker and counter of the old release is parked outside the state directory" bash -c "A='$ROOT/root/basetool-migration/anchors'; D='$ROOT/var/lib/basetool/deploy'; for n in config-previous config-stage previous-digest-pin.yml current-digest-pin.yml last-deployed.digests failed.digests reapply-failed.digests mixed-release.since health-restart.digests config-blocked.marker keycloak-spi-previous.jar; do test -e \$A/\$n && test ! -e \$D/\$n || exit 1; done"
check "the old release's config tree is parked too, so deploy.sh sees a first bundle" bash -c "T='$ROOT/root/basetool-migration/release-tree'; for n in docker-compose.yml docker-compose.monitoring.yml monitoring docker keycloak-theme quadlet host-scripts.sha256; do test -e \$T/\$n && test ! -e '$ROOT/opt/basetool'/\$n || exit 1; done"
check "only the host scripts and the live provider JAR stay in the release directory" test "$(find "$ROOT/opt/basetool" -mindepth 1 -maxdepth 1 -printf '%f\n' | LC_ALL=C sort | tr '\n' ' ')" = "keycloak scripts "
check "the deploy state keeps what is not tied to the old release" bash -c "test -d '$ROOT/var/lib/basetool/deploy/.docker' && test -f '$ROOT/var/lib/basetool/deploy/hardening/report.txt'"
check_calls "the relabel ran for /etc/basetool" "^restorecon -RF ${ROOT}/etc/basetool\$"
save_state s6
noop_rerun "rewrite" rewrite
check "a second run leaves the staged originals alone" bash -c "grep -qx 'IRI_KEYSTORE_HOST_PATH=/var/iri/secrets/keystore.p12' '$ROOT/root/basetool-migration/originals/etc/basetool/basetool.env'"
printf 'NEW' > "$ROOT/var/lib/basetool/deploy/last-deployed.digests"
printf 'NEWPIN' > "$ROOT/var/lib/basetool/deploy/current-digest-pin.yml"
mkdir -p "$ROOT/opt/basetool/quadlet"
printf 'NEWUNIT' > "$ROOT/opt/basetool/quadlet/new.container"
run_script rewrite
check "a rewrite re-run after the first new deploy leaves that deploy's state and tree alone" bash -c "test '$RC' = 0 && grep -q 'nothing to do' '$OUT' && test \"\$(cat '$ROOT/var/lib/basetool/deploy/last-deployed.digests')\" = NEW && test \"\$(cat '$ROOT/var/lib/basetool/deploy/current-digest-pin.yml')\" = NEWPIN && test -f '$ROOT/opt/basetool/quadlet/new.container'"

restore_state s5
BAD="$ROOT/etc/basetool/basetool.env.backup-20261001-000000-redis"
printf 'IRI_MONITORING_DIR=/elsewhere\n' >> "$BAD"
cp "$BAD" "$WORK/bad.before"
phase_ok "rewrite succeeds although one old backup cannot be rewritten" rewrite
check "that backup is left byte for byte, and named" bash -c "cmp -s '$BAD' '$WORK/bad.before' && grep -q 'left as it is, and not blocking the others: /etc/basetool/basetool.env.backup-20261001-000000-redis' '$OUT'"
check "the live env file and the other backup are rewritten" bash -c "grep -qx 'BASETOOL_COMPOSE_DIR=/opt/basetool' '$ENVF' && grep -qx 'BASETOOL_KEYSTORE_HOST_PATH=/etc/basetool/secrets/keystore.p12' '$ROOT/etc/basetool/basetool.env.backup-20260925-175805-step4'"
restore_state s5
phase_ok "rewrite on the clean move result" rewrite
check "the realm export keeps the container id (ids stay)" grep -q '"containerId": "iri"' "$ROOT/etc/basetool/realm-export.json"
check "the realm export has the new realm and role name" bash -c "grep -q '\"realm\": \"basetool\"' '$ROOT/etc/basetool/realm-export.json' && grep -q 'default-roles-basetool' '$ROOT/etc/basetool/realm-export.json'"

echo "== cleanup =="

restore_state s5
refused "cleanup refuses while the env file still has IRI_ keys" 'run the rewrite phase first' cleanup
restore_state s6
refused "cleanup refuses while the basetool-* units are not installed" 'units are not installed yet' cleanup
simulate_role() {
  mkfile "$ROOT/etc/systemd/system/basetool-deploy.service" '[Service]'
  mkfile "$ROOT/etc/systemd/system/basetool-deploy.timer" '[Timer]'
  mkfile "$ROOT/etc/systemd/system/basetool-backup.service" '[Service]'
  mkfile "$ROOT/etc/systemd/system/basetool-deploy.service.d/10-deploy-account-sandbox.conf" '[Service]'
  mkfile "$ROOT/etc/logrotate.d/basetool-deploy" '/var/log/basetool/deploy.log {}'
  mkfile "$ROOT/etc/tmpfiles.d/basetool-locks.conf" 'f /run/lock/basetool-deploy.lock 0600 deploy deploy'
  mkfile "$ROOT/run/lock/basetool-deploy.lock" ''
}
restore_state s6
simulate_role
save_state s7
dry_run_changes_nothing "cleanup" cleanup
restore_state s7
phase_ok "cleanup succeeds" cleanup
check "cleanup removes the old units, timers, drop-ins and wants links" bash -c "! ls '$ROOT'/etc/systemd/system/iri-* > /dev/null 2>&1 && ! ls '$ROOT'/etc/systemd/system/timers.target.wants/iri-* > /dev/null 2>&1"
check "cleanup removes the old logrotate, tmpfiles, lock and linger files" bash -c "! ls '$ROOT'/etc/logrotate.d/iri-* > /dev/null 2>&1 && test ! -e '$ROOT/etc/tmpfiles.d/iri-locks.conf' && test ! -e '$ROOT/run/lock/iri-deploy.lock' && test ! -e '$ROOT/var/lib/systemd/linger/iri'"
check "cleanup keeps every new unit, logrotate file, tmpfiles file and lock" bash -c "test -f '$ROOT/etc/systemd/system/basetool-deploy.service' && test -f '$ROOT/etc/systemd/system/basetool-deploy.timer' && test -f '$ROOT/etc/systemd/system/basetool-deploy.service.d/10-deploy-account-sandbox.conf' && test -f '$ROOT/etc/logrotate.d/basetool-deploy' && test -f '$ROOT/etc/tmpfiles.d/basetool-locks.conf' && test -f '$ROOT/run/lock/basetool-deploy.lock' && test -f '$ROOT/var/lib/systemd/linger/basetool'"
check "cleanup stages the removed files first" bash -c "test -f '$ROOT/root/basetool-migration/old-files/etc/systemd/system/iri-deploy.timer' && test -f '$ROOT/root/basetool-migration/old-files/etc/systemd/system/iri-deploy.service.d/10-deploy-account-sandbox.conf' && test -f '$ROOT/root/basetool-migration/old-files/etc/logrotate.d/iri-backup' && test -f '$ROOT/root/basetool-migration/old-files/etc/tmpfiles.d/iri-locks.conf'"
check_calls "cleanup reloads systemd" 'systemctl daemon-reload'
check "cleanup removes the empty old tree" test ! -e "$ROOT/var/iri"
check "cleanup keeps the volume units" test -f "$ROOT/etc/containers/systemd/users/994/edge-certs.volume"
printf '[Service]\nEnvironment=BASETOOL_LOCKFILE=/run/lock/basetool-deploy.lock\nExecStartPre=/opt/basetool/scripts/pre.sh\n' > "$WORK/override.expected"
printf '[Unit]\nAfter=basetool-backup.service\n# IRI_X /var/iri/npm stays in a comment\n' > "$WORK/monitoring.expected"
printf '[Timer]\nOnCalendar=*-*-* 03:00:00\n' > "$WORK/schedule.expected"
check "cleanup carries the hand-made drop-ins over, rewritten" bash -c "d='$ROOT/etc/systemd/system'; cmp -s \$d/basetool-deploy.service.d/override.conf '$WORK/override.expected' && cmp -s \$d/basetool-deploy.service.d/monitoring.conf '$WORK/monitoring.expected' && cmp -s \$d/basetool-backup.timer.d/schedule.conf '$WORK/schedule.expected'"
check "the role-managed drop-in of the new unit is untouched" test "$(cat "$ROOT/etc/systemd/system/basetool-deploy.service.d/10-deploy-account-sandbox.conf")" = '[Service]'
check "a carried-over drop-in keeps its mode" test "$(stat -c %a "$ROOT/etc/systemd/system/basetool-deploy.service.d/override.conf")" = "$(stat -c %a "$CACHE/s7/root/etc/systemd/system/iri-deploy.service.d/override.conf")"
check "the originals stay in the staging directory" test -f "$ROOT/root/basetool-migration/old-files/etc/systemd/system/iri-deploy.service.d/override.conf"
check "cleanup removes the old timer stamps and only those" bash -c "test ! -e '$ROOT/var/lib/systemd/timers/stamp-iri-deploy.timer' && test -e '$ROOT/var/lib/systemd/timers/stamp-dnf-automatic.timer'"
check_calls "cleanup clears the failed state of the old units" 'systemctl reset-failed iri-\*'
check "cleanup keeps the files only the role rewrites" test -f "$ROOT/etc/sudoers.d/basetool-deploy" -a -f "$ROOT/etc/default/prometheus-node-exporter"
save_state s8
noop_rerun "cleanup" cleanup

restore_state s7
mkdir -p "$ROOT/var/iri/npm"
printf 'x' > "$ROOT/var/iri/npm/state"
run_script cleanup
check "cleanup lists an entry it must not delete and fails" bash -c "test '$RC' = 1 && grep -q 'left in /var/iri: npm' '$OUT' && test -f '$ROOT/var/iri/npm/state'"
check "and it had already removed the old units" bash -c "! ls '$ROOT'/etc/systemd/system/iri-* > /dev/null 2>&1"

restore_state s7
mkfile "$ROOT/etc/systemd/system/iri-deploy.service.d/bad.conf" $'[Service]\nExecStart=/var/iri/npm/x\n'
refused "cleanup refuses a hand-made drop-in it cannot carry over" 'cannot be carried over' cleanup
check "and the refusal prints no content" bash -c "! grep -q '/var/iri/npm' '$OUT'"
restore_state s7
mkfile "$ROOT/etc/systemd/system/basetool-deploy.service.d/override.conf" 'someone else'
refused_late "cleanup refuses when the target drop-in exists with other content" 'exists with other content' cleanup
check "and has removed nothing yet" test -e "$ROOT/etc/systemd/system/iri-deploy.timer"
restore_state s3
refused "cleanup refuses before the accounts phase" 'basetool does not exist' cleanup
restore_state s4
refused "cleanup refuses before the move" 'basetool.env does not exist' cleanup
mkfile "$ROOT/etc/basetool/basetool.env" 'BASETOOL_A=1'
refused "cleanup refuses while the release directory still exists" '/var/iri/code still exists' cleanup
restore_state s7
printf 'export IRI_X=1\n' >> "$ROOT/etc/basetool/basetool.env"
refused "cleanup also catches an exported IRI_ key" 'still has IRI_ keys' cleanup

echo "== import-volumes =="

restore_state s3
refused "import-volumes refuses before the accounts phase" 'run the accounts phase first' import-volumes
restore_state s8
rm -f "$ROOT/var/lib/systemd/linger/basetool"
refused "import-volumes refuses when the new user is not lingering" 'not lingering' import-volumes
restore_state s8
rm "$ROOT/root/basetool-migration/state/reset.done"
refused "import-volumes refuses without the recorded reset" 'reset-podman has not completed' import-volumes
restore_state s8
printf 'tampered' >> "$ROOT/root/basetool-migration/volumes/edge-certs.tar"
refused "import-volumes refuses an export that no longer matches its hash" 'no verified, non-empty export' import-volumes
restore_state s8
rm "$ROOT/root/basetool-migration/volumes/edge-acme-webroot.tar"
refused "import-volumes refuses with a missing export" 'no verified, non-empty export' import-volumes
restore_state s8
rm "$ROOT/etc/containers/systemd/users/994/edge-certs.volume"
refused "import-volumes refuses without the Quadlet volume unit" 'edge-certs.volume does not exist' import-volumes
restore_state s8
mkdir -p "$FAKE/podman-store/vol/edge-certs"
printf 'someone else' > "$FAKE/podman-store/vol/edge-certs/stray"
refused_late "import-volumes refuses to import over other content" 'already holds content' import-volumes
check "and imported nothing into it" bash -c "test \"\$(ls '$FAKE/podman-store/vol/edge-certs')\" = stray"

restore_state s8
dry_run_changes_nothing "import-volumes" import-volumes
check "import-volumes --dry-run imports nothing" bash -c "! grep -q 'volume import' '$CALLS'"
restore_state s8
phase_ok "import-volumes succeeds" import-volumes
check "the volumes carry the exported content again" bash -c "test \"\$(cat '$FAKE/podman-store/vol/edge-certs/privkey.pem')\" = 'PRIVATE-KEY-MATERIAL' && test \"\$(cat '$FAKE/podman-store/vol/edge-acme-state/account.json')\" = 'ACME-ACCOUNT' && test -d '$FAKE/podman-store/vol/edge-acme-webroot'"
check "the volumes were created through their Quadlet units, as the new user" bash -c "grep -q 'runuser -u basetool -- env HOME=/home/basetool XDG_RUNTIME_DIR=/run/user/994 systemctl --user start edge-certs-volume.service' '$CALLS' && grep -q 'runuser -u basetool -- env HOME=/home/basetool XDG_RUNTIME_DIR=/run/user/994 podman volume import edge-certs -' '$CALLS'"
check "the import never reads the root-only staging directory as the new user" bash -c "! grep -q 'podman volume import .*basetool-migration' '$CALLS'"
check "each import is recorded" bash -c "for v in edge-certs edge-acme-state edge-acme-webroot; do test -e '$ROOT/root/basetool-migration/state/import-'\$v'.done' || exit 1; done"
check "the automatic updates are started again at the end" bash -c "test -e '$FAKE/active/dnf-automatic.timer' && test ! -e '$ROOT/root/basetool-migration/state/dnf-stopped'"
save_state s9
noop_rerun "import-volumes" import-volumes
printf 'renewed' > "$FAKE/podman-store/vol/edge-certs/renewed.pem"
run_script import-volumes
check "a second run leaves the volumes alone even after the stack changed them" bash -c "test '$RC' = 0 && grep -q 'nothing to do' '$OUT' && ! grep -q 'volume import' '$CALLS' && test \"\$(cat '$FAKE/podman-store/vol/edge-certs/renewed.pem')\" = renewed"

restore_state s8
mkdir -p "$FAKE/podman-store/vol/edge-acme-state"
printf 'x' > "$FAKE/podman-store/vol/edge-acme-state/stray"
run_script import-volumes
check "import-volumes stops at the second volume" test "$RC" = 3
check "and had imported the first one" bash -c "test \"\$(cat '$FAKE/podman-store/vol/edge-certs/privkey.pem')\" = 'PRIVATE-KEY-MATERIAL' && test -e '$ROOT/root/basetool-migration/state/import-edge-certs.done' && test ! -e '$ROOT/root/basetool-migration/state/import-edge-acme-state.done'"
rm -rf "$FAKE/podman-store/vol/edge-acme-state"
phase_ok "import-volumes resumes after the stray content is gone" import-volumes
check "the resumed run imports only the volumes still missing" bash -c "! grep -q 'volume import edge-certs' '$CALLS' && grep -q 'volume import edge-acme-state' '$CALLS' && grep -q 'volume import edge-acme-webroot' '$CALLS' && test \"\$(cat '$FAKE/podman-store/vol/edge-acme-state/account.json')\" = ACME-ACCOUNT"

restore_state s8
touch "$FAKE/export-fails"
run_script import-volumes
check "an unreadable volume is not treated as empty: nothing is imported, exit 1" bash -c "test '$RC' = 1 && ! grep -q 'volume import' '$CALLS' && grep -q 'cannot read volume edge-certs' '$OUT'"

echo "== the whole way =="

check "no path of the migrated host carries the old name, outside the staging directory" bash -c "cd '$CACHE/s9/root' && test -z \"\$(find . -path ./root/basetool-migration -prune -o \( -name 'iri' -o -name 'iri-*' -o -name '*-iri' -o -name '.env' \) -print)\""
check "no old-layout directory is left" bash -c "cd '$CACHE/s9/root' && for d in var/iri etc/iri var/lib/iri home/iri; do test ! -e \$d || exit 1; done"
check "the migrated host has exactly the new account, group and sub-id entries" bash -c "cd '$CACHE/s9/root' && grep -q '^basetool:' etc/passwd && grep -q '^basetool:' etc/group && grep -q '^basetool:' etc/subuid && ! grep -qw iri etc/passwd etc/group etc/subuid etc/subgid"
restore_state s9
before="$(tree_full)"
for p in stop export-volumes reset-podman accounts move rewrite cleanup import-volumes; do
  run_script "$p"
  if (( RC == 0 )) && grep -q 'nothing to do, already done' "$OUT"; then ok "after the whole migration, ${p} is a no-op"; else bad "after the whole migration, ${p} is a no-op (rc ${RC})"; fi
  check_nomut "after the whole migration, ${p} runs no mutating command"
done
check "after the whole migration, the eight re-runs changed no file" test "$before" = "$(tree_full)"
run_script check
check "check on the migrated host succeeds" test "$RC" = 0
check "check on the migrated host finds nothing left to do" bash -c "! grep -q '^\[todo\] move' '$OUT' && ! grep -q '^\[block\]' '$OUT'"
check "no phase printed a secret" bash -c "! grep -qE '$SECRETS_RE' '$ALLOUT'"
check "the output log really holds the phases" test "$(grep -c '^## ' "$ALLOUT")" -gt 80
check "the transcript shows the phase summaries" bash -c "for p in stop export-volumes reset-podman accounts move rewrite cleanup import-volumes; do grep -q \"^\$p: done\" '$ALLOUT' || exit 1; done"

echo "== the label rules and the role agree =="

role_pairs="$(awk '
  /^[[:space:]]+target:/ { sub(/^[[:space:]]+target:[[:space:]]*/, ""); gsub(/"/, ""); t = $0 }
  /^[[:space:]]+setype:/ { sub(/^[[:space:]]+setype:[[:space:]]*/, ""); print t " " $0 }
' "$ANSIBLE_SELINUX" \
  | sed -e 's#{{ basetool_host_release_dir }}#/opt/basetool#' \
        -e 's#{{ basetool_host_config_dir }}#/etc/basetool#' \
        -e 's#{{ basetool_host_token_dir }}#/etc/basetool/deploy#' \
        -e 's#{{ basetool_host_data_dir }}#/var/lib/basetool#' \
        -e 's#{{ basetool_host_state_dir }}#/var/lib/basetool/deploy#' \
        -e 's#{{ basetool_host_log_dir }}#/var/log/basetool#' | LC_ALL=C sort)"
script_pairs="$(sed -n '/^FCONTEXT_RULES=(/,/^)/p' "$SCRIPT" | sed -n 's/^  "\(.*\)"$/\1/p' | LC_ALL=C sort)"
check "the role file was found and parsed" test -n "$role_pairs"
check "the script sets exactly the label rules the Ansible role sets" test "$role_pairs" = "$script_pairs"

echo
echo "${PASSED} passed, ${FAILED} failed"
exit $(( FAILED > 0 ? 1 : 0 ))
