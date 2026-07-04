#!/bin/bash
# =============================================================================
# Profit Basetool – Docker Housekeeping Script
#
# Räumt auf dem Produktions-VM regelmäßig ungenutzte Docker-Ressourcen weg,
# damit die Festplatte nicht durch alte Image-Layer, Build-Cache und
# verwaiste Container/Netzwerke vollläuft. Gedacht für einen wöchentlichen
# Cron-Lauf (siehe scripts/docker-cleanup.cron, Samstag 02:00 UTC).
#
# Was entfernt wird (jeweils nur, wenn von KEINEM Container belegt):
#   * gestoppte Container       älter als IRI_CLEANUP_CONTAINER_UNTIL
#   * ungenutzte Images (-a)    älter als IRI_CLEANUP_IMAGE_UNTIL
#   * Build-Cache               älter als IRI_CLEANUP_BUILDER_UNTIL
#   * ungenutzte Netzwerke      älter als IRI_CLEANUP_NETWORK_UNTIL
#   * anonyme, ungenutzte Volumes (nur wenn IRI_CLEANUP_PRUNE_VOLUMES=true)
#
# SICHERHEIT – warum das den laufenden Stack nicht beschaedigt:
#   * Alle persistenten Produktionsdaten (Postgres, Redis, Keycloak, NPM, Logs)
#     liegen als BIND MOUNTS unter /var/iri/... und sind dem Docker-Daemon
#     unbekannt. `docker volume prune` kann sie daher NICHT anfassen; es trifft
#     ausschliesslich anonyme Wegwerf-Volumes. Trotzdem ist das Volume-Pruning
#     defensiv per Schalter abschaltbar (IRI_CLEANUP_PRUNE_VOLUMES=false).
#   * Bilder/Container/Netzwerke, die ein laufender Container belegt, werden
#     von Docker grundsaetzlich uebersprungen ("resource is in use").
#   * Die until=-Fenster halten frisch gezogene Images vor: das Default-Fenster
#     fuer Images (336h = 14 Tage) ueberlebt zwei woechentliche Laeufe, sodass
#     das Rollback-Image des letzten deploy.sh-Laufs erhalten bleibt. Wird es
#     doch einmal entfernt, zieht deploy.sh es beim Rollback erneut aus GHCR.
#
# Aufruf:
#   sudo /var/iri/code/scripts/docker-cleanup.sh                 # aufraeumen
#   sudo /var/iri/code/scripts/docker-cleanup.sh --dry-run       # nur anzeigen
#   sudo /var/iri/code/scripts/docker-cleanup.sh --help
#
# Cron-Beispiel (woechentlich, Samstag 02:00 UTC):
#   0 2 * * 6 /var/iri/code/scripts/docker-cleanup.sh >> /var/log/iri-docker-cleanup.log 2>&1
# =============================================================================

set -euo pipefail

# --- Konfiguration (alle ueber Umgebungsvariablen ueberschreibbar) ----------
# until=-Werte verstehen Docker-Dauern: 24h, 168h, 336h, 720h ...
IMAGE_UNTIL="${IRI_CLEANUP_IMAGE_UNTIL:-336h}"       # 14 Tage – Rollback-Puffer
BUILDER_UNTIL="${IRI_CLEANUP_BUILDER_UNTIL:-168h}"   # 7 Tage
CONTAINER_UNTIL="${IRI_CLEANUP_CONTAINER_UNTIL:-24h}"
NETWORK_UNTIL="${IRI_CLEANUP_NETWORK_UNTIL:-24h}"
PRUNE_VOLUMES="${IRI_CLEANUP_PRUNE_VOLUMES:-true}"
LOCKFILE="${IRI_CLEANUP_LOCKFILE:-/var/lock/iri-docker-cleanup.lock}"

# Monitoring textfile metrics (epic #936). This job runs from CRON, invisible to node_exporter's
# systemd collector, so this textfile is its ONLY monitoring signal — the "docker-cleanup stale >8d
# or absent" warning reads basetool_docker_cleanup_last_success_timestamp.
TEXTFILE_DIR="${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}"
START_EPOCH="$(date +%s)"

DRY_RUN=false

# --- CLI-Argumente ----------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      cat <<'USAGE'
Usage: docker-cleanup.sh [--dry-run]

Entfernt ungenutzte Docker-Ressourcen (Container, Images, Build-Cache,
Netzwerke und – optional – anonyme Volumes). Belegte Ressourcen und alles
innerhalb des jeweiligen until=-Zeitfensters bleiben unberuehrt.

Optionen:
  --dry-run    Nichts loeschen; nur den aktuellen Plattenverbrauch
               (docker system df) und die geplanten Schritte ausgeben.
  -h, --help   Diese Hilfe anzeigen.

Umgebungsvariablen (Defaults in Klammern):
  IRI_CLEANUP_IMAGE_UNTIL=336h       Mindestalter ungenutzter Images
  IRI_CLEANUP_BUILDER_UNTIL=168h     Mindestalter des Build-Cache
  IRI_CLEANUP_CONTAINER_UNTIL=24h    Mindestalter gestoppter Container
  IRI_CLEANUP_NETWORK_UNTIL=24h      Mindestalter ungenutzter Netzwerke
  IRI_CLEANUP_PRUNE_VOLUMES=true     Anonyme ungenutzte Volumes mit aufraeumen
  IRI_CLEANUP_LOCKFILE=/var/lock/iri-docker-cleanup.lock
USAGE
      exit 0
      ;;
    *)
      echo "FATAL: unbekanntes Argument: $1 (siehe --help)" >&2
      exit 1
      ;;
  esac
done

# --- Helfer -----------------------------------------------------------------
log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

# Fuehrt einen Prune-Schritt aus (oder zeigt ihn im Dry-Run nur an). Ein
# fehlschlagender Schritt darf den restlichen Lauf nicht abbrechen – z.B. kann
# eine kurzzeitig gehaltene Referenz einen einzelnen Prune blockieren, ohne
# dass deshalb die anderen Aufraeum-Schritte ausfallen sollen.
run_prune() {
  local label="$1"
  shift
  if [[ "${DRY_RUN}" == "true" ]]; then
    log "[dry-run] ${label}: $*"
    return 0
  fi
  log "${label} ..."
  if "$@"; then
    log "${label}: OK"
  else
    log "[WARNUNG] ${label}: fehlgeschlagen (Exit $?), weiter mit naechstem Schritt"
  fi
}

# Wandelt eine Docker/go-units-Groessenangabe (z.B. "1.5GB", "512MB", "0B") in ganze Bytes um.
to_bytes() {
  awk -v s="$1" 'BEGIN{
    if (s=="") { print 0; exit }
    u="B"; v=s
    if (match(s,/[A-Za-z]+$/)) { u=substr(s,RSTART,RLENGTH); v=substr(s,1,RSTART-1) }
    m=1
    if (u=="B") m=1;
    else if (u=="kB"||u=="KB") m=1000;
    else if (u=="MB") m=1000000;
    else if (u=="GB") m=1000000000;
    else if (u=="TB") m=1000000000000;
    else if (u=="KiB") m=1024;
    else if (u=="MiB") m=1048576;
    else if (u=="GiB") m=1073741824;
    else if (u=="TiB") m=1099511627776;
    printf "%d", (v*m)
  }'
}

# Summe der "Size"-Spalte von `docker system df` in Bytes (Best-Effort, 0 bei Fehler).
df_total_bytes() {
  local total=0 line b
  while IFS= read -r line; do
    b="$(to_bytes "${line}")"
    total=$(( total + b ))
  done < <(docker system df --format '{{.Size}}' 2>/dev/null || true)
  printf '%d' "${total}"
}

# Schreibt die Cleanup-Textfile-Metrik atomar (erst .tmp, dann mv), damit der Collector nie eine
# halb geschriebene Datei liest.
write_cleanup_metrics() {
  local reclaimed="$1" now dur tmp
  now="$(date +%s)"
  dur=$(( now - START_EPOCH ))
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${TEXTFILE_DIR}/docker_cleanup.prom.$$"
  if {
    echo "# HELP basetool_docker_cleanup_last_success_timestamp Unix time of the last successful docker cleanup."
    echo "# TYPE basetool_docker_cleanup_last_success_timestamp gauge"
    echo "basetool_docker_cleanup_last_success_timestamp ${now}"
    echo "# HELP basetool_docker_cleanup_duration_seconds Runtime of the last docker cleanup in seconds."
    echo "# TYPE basetool_docker_cleanup_duration_seconds gauge"
    echo "basetool_docker_cleanup_duration_seconds ${dur}"
    echo "# HELP basetool_docker_cleanup_reclaimed_bytes Bytes reclaimed by the last docker cleanup."
    echo "# TYPE basetool_docker_cleanup_reclaimed_bytes gauge"
    echo "basetool_docker_cleanup_reclaimed_bytes ${reclaimed}"
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${TEXTFILE_DIR}/docker_cleanup.prom" 2>/dev/null \
      || log "[WARNUNG] Konnte Textfile-Metrik nicht verschieben (${TEXTFILE_DIR})"
  else
    log "[WARNUNG] Konnte Textfile-Metrik nicht schreiben (${TEXTFILE_DIR})"
    rm -f "${tmp}" 2>/dev/null || true
  fi
}

# --- Lock: verhindert parallele Aufraeum-Laeufe -----------------------------
exec 200>"${LOCKFILE}"
flock -n 200 || {
  log "[FEHLER] Aufraeum-Lauf laeuft bereits (Lock: ${LOCKFILE}). Abbruch."
  exit 1
}

# --- Vorbedingung: Docker erreichbar ----------------------------------------
if ! docker info >/dev/null 2>&1; then
  log "[FEHLER] Docker-Daemon nicht erreichbar (laeuft das Script als root bzw. docker-Gruppe?). Abbruch."
  exit 1
fi

echo "================================================================"
log "Starte Docker-Aufraeumen${DRY_RUN:+ (Dry-Run)}"
log "Fenster: Images>${IMAGE_UNTIL}, Cache>${BUILDER_UNTIL}, Container>${CONTAINER_UNTIL}, Netze>${NETWORK_UNTIL}, Volumes=${PRUNE_VOLUMES}"
echo "================================================================"

log "Plattenverbrauch VORHER:"
docker system df || true
BEFORE_BYTES="$(df_total_bytes)"

# --- Aufraeum-Schritte ------------------------------------------------------
# Reihenfolge: erst Container (gibt Image-Referenzen frei), dann Images,
# Build-Cache, Netzwerke und zuletzt – optional – anonyme Volumes.
run_prune "Gestoppte Container" \
  docker container prune --force --filter "until=${CONTAINER_UNTIL}"

run_prune "Ungenutzte Images" \
  docker image prune --all --force --filter "until=${IMAGE_UNTIL}"

run_prune "Build-Cache" \
  docker builder prune --force --filter "until=${BUILDER_UNTIL}"

run_prune "Ungenutzte Netzwerke" \
  docker network prune --force --filter "until=${NETWORK_UNTIL}"

if [[ "${PRUNE_VOLUMES}" == "true" ]]; then
  # Ohne --all werden ausschliesslich ANONYME ungenutzte Volumes entfernt;
  # benannte Volumes (falls jemand spaeter welche anlegt) und – ohnehin nicht
  # betroffen – die /var/iri-Bind-Mounts bleiben unangetastet.
  run_prune "Anonyme ungenutzte Volumes" \
    docker volume prune --force
else
  log "Volume-Pruning per IRI_CLEANUP_PRUNE_VOLUMES=false uebersprungen"
fi

echo "----------------------------------------------------------------"
log "Plattenverbrauch NACHHER:"
docker system df || true

# Monitoring-Signal schreiben (nur bei echtem Lauf). reclaimed = frei gewordene Bytes laut
# `docker system df` (Best-Effort, nie negativ).
if [[ "${DRY_RUN}" != "true" ]]; then
  AFTER_BYTES="$(df_total_bytes)"
  RECLAIMED=$(( BEFORE_BYTES - AFTER_BYTES ))
  (( RECLAIMED < 0 )) && RECLAIMED=0
  write_cleanup_metrics "${RECLAIMED}"
  log "Textfile-Metrik geschrieben: basetool_docker_cleanup_* (reclaimed=${RECLAIMED} Bytes)"
fi

echo "================================================================"
log "Docker-Aufraeumen abgeschlossen${DRY_RUN:+ (Dry-Run – nichts entfernt)}"
echo "================================================================"
