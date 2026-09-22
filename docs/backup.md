# Backup & disaster recovery — operator runbook

> **Doc type:** Runbook — the *how-to*. Last reviewed: 2026-09-22. The binding requirements (the
> *what-must-hold*) live in [`docs/specs/backup-recovery.md`](specs/backup-recovery.md)
> (`REQ-OPS-008..012`); the decision record is
> [ADR-0056](adr/0056-offsite-encrypted-backup-to-nextcloud.md).
>
> **Runtime:** production runs **rootless Podman under Quadlet on Rocky Linux 10** since
> 2026-09-22 — the containers belong to the service user `iri`, the operational units run as the
> `deploy` account. Every command below is written for that host. The scripts themselves are
> runtime-agnostic (they go through [`scripts/lib/container-runtime.sh`](../scripts/lib/container-runtime.sh)),
> so they still work on a Docker Compose host; the restore commands do not.

## What this does

A nightly job ([`scripts/backup.sh`](../scripts/backup.sh)) captures a consistent, full-restore
backup set and pushes it **client-side encrypted** to a **Nextcloud** target via `restic` over an
`rclone` WebDAV remote. A weekly restore drill ([`scripts/restore-drill.sh`](../scripts/restore-drill.sh))
proves the backups are actually recoverable. Everything is outbound-only — consistent with the
pull-only host posture (`REQ-OPS-001`).

- **Schedule:** backup daily **04:15** (host-local, `iri-backup.timer`), drill **Sunday 05:30**
  (`iri-restore-drill.timer`, up to 5 min randomized delay). Both timers are `Persistent=true`.
- **Retention (GFS):** keep 7 daily, 4 weekly, 6 monthly; `restic check` after every upload.
- **Downtime:** only the database dump (seconds), inside the 04:00–05:00 window — the slow upload
  runs after the stack is back up. See *How consistency works* below.

## What is and isn't backed up

**Captured** (the full-restore surface, `REQ-OPS-010`) — each item lands in the snapshot under the
staging directory `…/staging/<UTC timestamp>/`:

|              Item              |              In the snapshot              |                                                            How                                                             |
|--------------------------------|-------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| Backend DB `krt_basetool`      | `krt_basetool.dump`                       | `pg_dump -Fc` inside the `db-backend` container                                                                            |
| Keycloak DB `keycloak`         | `keycloak.dump`                           | `pg_dump -Fc` inside the `db-keycloak` container — **the live source of truth** for realm/users/clients, not `realm-export.json` |
| Edge TLS material + ACME state | `edge-certs.tar.gz`, `edge-acme-state.tar.gz`, `edge-acme-webroot.tar.gz` | `tar` of the three named volumes through a helper container; a volume that does not exist is logged as skipped |
| Host secrets/config            | `config/dotenv`, `config/keystore.p12`, `config/realm-export.json`, `config/providers.tar.gz`, `config/users.acl` | `.env`, the keystore, the realm export, `keycloak/providers`, and the **redis ACL** (access control, not session data — redis refuses to start without it) |
| Monitoring plane (ADR-0072)    | `monitoring/grafana.db`, `monitoring/secrets.tar.gz`, `monitoring/alertmanager.tar.gz` | Grafana SQLite (brief `grafana` stop for a consistent copy), `/var/iri/monitoring/{secrets,certs}`, Alertmanager silences + notification log |
| Prometheus TSDB (Sundays only) | `monitoring/prometheus-tsdb-snapshot.tar.gz` | admin-API snapshot via a throwaway curl container on `net-monitoring-core` |

Root-owned files and named volumes are read through a throwaway **helper container**
(`IRI_BACKUP_HELPER_IMAGE`, default `docker.io/library/postgres:18-alpine`). If you override it,
**qualify it fully**: rootless Podman on Rocky enforces short-name resolution and refuses a short
name without a TTY, and every helper read then fails as a best-effort `WARN` — the backup reports
success over a snapshot missing the certificates, the ACL and the keystore.

**Excluded by design:**

- **Redis session data** (`appendonlydir`, `dump.rdb`) — only Spring sessions; users transparently
  re-login after a restore. The `users.acl` beside them *is* captured.
- **Logs** — `/var/iri/{backend,frontend,keycloak}/log`.
- **The Loki log store** — a deliberate data-protection decision (ADR-0072): its GFS retention
  would silently extend the approved 31-day IP retention. Tempo traces and exporter/textfile data
  are regenerable and excluded too.

## How consistency works (`REQ-OPS-009`)

`pg_dump` is already a transactionally consistent snapshot, so strictly no downtime is required.
For a *globally* quiescent instant, the job:

1. acquires the **same `flock`** `deploy.sh` uses (`/var/lock/iri-deploy.lock`) so a deploy tick
   cannot recreate containers mid-backup;
2. stops the writers (`frontend`, `backend`, `ingest` — `systemctl --user stop <svc>.service` as
   the service user) — the edge keeps serving the maintenance page;
3. dumps both databases and captures the edge volumes, the redis ACL and the host config to local
   staging (**seconds**);
4. **restarts the writers**. A writer that is slow to report healthy (`Notify=healthy`) is logged
   as a `WARN` and the run continues, so the dumps still reach the repository;
5. captures the monitoring plane, then **releases the lock** — production is fully live again;
6. only *then* runs the slow `restic` encrypt + upload + `forget --prune` + `check`, and writes
   `basetool_backup_last_success_timestamp` / `basetool_backup_duration_seconds` into the
   node-exporter textfile directory (`/var/iri/monitoring/textfile/backup.prom`).

A `trap` guarantees the writers are restarted even if a dump step fails, so production is never
left down, and the plaintext staging directory is removed on every exit. Pass `--no-quiesce` for a
zero-downtime online dump (accepting only a benign theoretical cross-database edge case).

---

## One-time setup

The host bootstrap ([`ansible/`](../ansible/README.md), role `basetool_host`) already does
everything that is not a secret: it installs `restic` and `rclone` (`10-packages.yml`), creates
`/etc/iri` (`deploy:deploy 0700`) and the staging tree `/var/iri/backup` (`deploy:deploy 0700`),
installs `backup.sh` / `restore-drill.sh` under `/var/iri/code/scripts/`, the four
`iri-{backup,restore-drill}.{service,timer}` units and their logrotate entries (`25-scripts.yml`),
and **enables** both timers without starting them. What remains is the Nextcloud target and the
two host-only secret files. Run the host commands below as root, from `/` — `sudo -u deploy` keeps
the working directory, and `deploy` cannot enter `/root`.

### 1. Set up the Nextcloud target (secured, dedicated account — not a public link)

Use an **authenticated dedicated account + app password + private folder**, never a public/
anonymous share link.

1. **Dedicated user.** As a Nextcloud admin: *Users → New user*, e.g. `basetool-backup`, with a
   long random password and **no** admin rights (optionally a `backups` group). Set a **storage
   quota** (e.g. 50 GB) to bound damage and surface "disk full" early.
2. **Private folder.** As `basetool-backup`, create a folder `Basetool-Backups` (restic creates its
   encrypted repo inside). **Share it with no one.**
3. **App password.** As `basetool-backup`: *Settings → Security → Devices & sessions → create a new
   app password* (name it `basetool-restic`). **Copy the token now** (shown once). Only this token
   goes on the server — never the account password. It is individually revocable.
4. **2FA** on the interactive login of this account (and your admin). App passwords intentionally
   bypass 2FA for the automated WebDAV access; the web login stays protected.
5. **Server hardening.** Valid TLS certificate (so rclone verifies strictly — avoid
   `--no-check-certificate`); built-in brute-force protection on; trusted domains correct; keep
   Nextcloud updated. Server-side encryption is optional — the real protection is restic's
   **client-side** encryption.
6. **Independence.** The Nextcloud instance must be on **separate hardware** from the basetool prod
   host (different machine, ideally different site/provider) — otherwise the off-site property is
   lost.

WebDAV URL for rclone: `https://YOUR-NEXTCLOUD/remote.php/dav/files/basetool-backup/` →
restic target folder `Basetool-Backups`.

### 2. Confirm the tools and directories

```bash
rpm -q restic rclone                     # installed by the role; otherwise: sudo dnf install restic rclone
stat -c '%U:%G %a %n' /etc/iri /var/iri/backup   # expect deploy:deploy 700 for both
```

### 3. Configure the rclone WebDAV remote

Create `/etc/iri/rclone.conf` (the app password goes here, obscured by rclone):

```bash
sudo -u deploy rclone config create nextcloud webdav \
  url   "https://YOUR-NEXTCLOUD/remote.php/dav/files/basetool-backup/" \
  vendor nextcloud \
  user  "basetool-backup" \
  pass  "PASTE-THE-APP-PASSWORD" \
  --config /etc/iri/rclone.conf
sudo chown deploy:deploy /etc/iri/rclone.conf && sudo chmod 0600 /etc/iri/rclone.conf
```

### 4. Create the backup secrets file

`/etc/iri/backup.env` (host-only, `REQ-OPS-012` — never in git, never in the config bundle):

```bash
sudo tee /etc/iri/backup.env >/dev/null <<'EOF'
RESTIC_REPOSITORY=rclone:nextcloud:Basetool-Backups
RESTIC_PASSWORD=GENERATE-A-LONG-RANDOM-RESTIC-REPO-PASSWORD
RCLONE_CONFIG=/etc/iri/rclone.conf
# Optional retention overrides (defaults shown):
# IRI_KEEP_DAILY=7
# IRI_KEEP_WEEKLY=4
# IRI_KEEP_MONTHLY=6
EOF
sudo chown deploy:deploy /etc/iri/backup.env && sudo chmod 0600 /etc/iri/backup.env
```

`RESTIC_PASSWORD_FILE` may be used instead of `RESTIC_PASSWORD`.

> **Keep `RESTIC_PASSWORD` safe and separate.** It is the *only* key to your backups — if you lose
> it, the encrypted repo is unrecoverable. Store a copy in your password manager, **separately**
> from the Nextcloud app password.

### 5. Initialize the repository

```bash
sudo -u deploy env RESTIC_CACHE_DIR=/var/lib/iri/restic-cache \
  $(sudo grep -v '^#' /etc/iri/backup.env | xargs) restic init   # one-time (backup.sh also self-inits)
```

### 6. Start the timers

The role enabled them for the next boot; start them now so they fire without one:

```bash
sudo systemctl start iri-backup.timer iri-restore-drill.timer
systemctl list-timers 'iri-*'            # confirm next fire times
```

On a host **not** built by the role, install the units by hand from `/var/iri/code/scripts/`
(`iri-backup.{service,timer}`, `iri-restore-drill.{service,timer}` → `/etc/systemd/system/`,
`iri-backup.logrotate` / `iri-restore-drill.logrotate` → `/etc/logrotate.d/`), then
`systemctl daemon-reload` and `systemctl enable --now` both timers.

> [!danger] Never run a backup from a host that has no data
> `restore-drill.sh` and a disaster restore both read `latest` by default, and every host writes to
> the same repository. A `backup.sh` run on a freshly built host — before its data was restored —
> pushes a snapshot of nothing and makes it `latest`. Run the **drill** on a new host to prove it
> can read the repository; leave the **backup** to the host that holds the data.

### 7. Verify

```bash
sudo systemctl start iri-backup.service          # run a backup now
sudo -u deploy /var/iri/code/scripts/backup.sh --dry-run   # show plan + snapshots
sudo systemctl start iri-restore-drill.service   # prove a restore works
```

The units append stdout/stderr to `/var/log/iri-backup.log` and `/var/log/iri-restore-drill.log`
(not to the journal); Alloy ships them to Loki as `{app="ops-backup"}` and
`{app="ops-restore-drill"}`. In the backup log, confirm the captures that are best-effort and
easy to miss:

```
  edge-certs: captured
  edge-acme-state: captured
  capturing the redis ACL (/var/iri/redis/users.acl)
  capturing host config (.env, keystore, realm-export, providers)
```

---

## Restoring (disaster recovery)

Assumes a **bootstrapped host**: the `basetool_host` Ansible role has run (service user `iri` with
its subuid range, the `deploy` account and its sudoers rule, the directory tree, restic/rclone, the
operational units), and `/etc/iri/{backup.env,rclone.conf}` and the GHCR pull token are in place.
This restores *data + config* onto it. Run the steps as root, from `/`
([`deployment.md` → *Shell conventions*](deployment.md#shell-conventions-used-below)).

> [!important] Container uids are translated on disk
> Under rootless Podman a container's uid `N` is stored on the host as `100000 + N - 1` (container
> root is the `iri` user itself). A restored file that a container must read therefore gets the
> **translated** owner, never the old Docker host's literal value. The arithmetic is in
> [`ansible/README.md` → *The uid arithmetic*](../ansible/README.md#the-uid-arithmetic).

```bash
cd /    # sudo -u keeps the working directory, and neither deploy nor iri can enter /root
IRI_UID=$(id -u iri)
PODMAN="sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} podman"
SYSTEMCTL="sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} systemctl --user"
# No deploy tick may bring stopped services back mid-restore, and no backup may run before the
# data is back (see the danger box above).
systemctl stop iri-deploy.timer iri-backup.timer
flock /var/lock/iri-deploy.lock true      # returns once no deploy is running
```

1. **Fetch the snapshot** (by id when restoring a specific night; `latest` otherwise):

   ```bash
   set -a; . /etc/iri/backup.env; set +a
   restic snapshots
   restic restore <snapshot-id> --target /var/iri/backup/restore
   R=$(echo /var/iri/backup/restore/var/iri/backup/staging/*)   # the one timestamped directory
   ```

   `restic restore` **exits 1 on this host** although the payload is complete: it cannot strip the
   `security.selinux` xattr from the files it wrote and reports one `xattr.LRemove … permission
   denied` per entry. Verify the payload instead — every expected file present and non-empty, and
   `gzip -t` on each archive.

2. **Restore host config** from `${R}/config/`:

   ```bash
   install -o deploy -g deploy -m 0640 "${R}/config/dotenv" /var/iri/code/.env
   install -o iri -g iri -m 0640 "${R}/config/realm-export.json" /var/iri/code/realm-export.json
   tar -C /var/iri/code/keycloak -xzf "${R}/config/providers.tar.gz"
   chown -R deploy:deploy /var/iri/code/keycloak/providers   # deploy.sh stages the SPI JAR here
   # keystore: gid 10001 (backend/frontend/ingest) -> 110000, Keycloak uid 1000 -> 100999
   install -o root -g 110000 -m 0640 "${R}/config/keystore.p12" /var/iri/secrets/keystore.p12
   setfacl -m u:100999:r /var/iri/secrets/keystore.p12
   # redis ACL: a plain root-owned file, nothing namespaced about it
   install -o root -g root -m 0644 "${R}/config/users.acl" /var/iri/redis/users.acl
   restorecon -F /var/iri/code/.env /var/iri/code/realm-export.json /var/iri/secrets/keystore.p12 /var/iri/redis/users.acl
   grep -c '^user default ' /var/iri/redis/users.acl      # MUST print 1
   ```

   The keystore ACL is re-applied by hand because restic does not carry POSIX ACLs; verify with
   `getfacl -p` (`group::r--`, `user:100999:r--`, a `mask::r--` that does not mask them away). A
   `users.acl` without a `default` line makes redis come up with **no authentication** (the
   2026-07-10 defect) — the `grep` must answer `1`.

3. **Restore the edge volumes** — as the service user, *inside* its user namespace, so the
   archived container uids land translated:

   ```bash
   chown iri:iri "${R}"/edge-certs.tar.gz "${R}"/edge-acme-state.tar.gz   # the namespace must be able to read them
   for v in edge-certs edge-acme-state; do
     ${PODMAN} volume create "${v}" 2>/dev/null || true
     ${PODMAN} unshare tar -C "$(${PODMAN} volume inspect "${v}" --format '{{.Mountpoint}}')" \
       -xzf "${R}/${v}.tar.gz"
   done
   ```

   The Quadlet `.volume` units declare `VolumeName=edge-certs` / `edge-acme-state`, so they adopt
   these volumes rather than creating new ones. `edge-acme-webroot` is the http-01 challenge root
   and empty between validations; it need not be restored.

4. **Restore the monitoring secrets and certs** from `${R}/monitoring/secrets.tar.gz` into
   `/var/iri/monitoring` — **except** `certs/grafana.{crt,key}`, Grafana's self-signed per-host
   leaf, which must not be overwritten with another host's. The files are owned by container uid
   65534 (`165533` on the host), `secrets/*` mode `600`, `certs/basetool-ca.crt` mode `644`, then
   `restorecon -RF` both directories. `alertmanager.tar.gz` and `grafana.db` are optional (silences
   and dashboards edited in the UI); restore them into `/var/iri/monitoring/data/{alertmanager,grafana}`
   with their translated owners before the monitoring stack starts.

5. **If the stack has never been deployed on this host, deploy it first.** The Quadlet units
   (`db-backend.service`, …) only exist after the first deploy generates them:
   `systemctl start iri-deploy.service`. It starts everything against empty databases, which is
   harmless — step 6 replaces them. `realm-export.json` and `keycloak/providers` from step 2 are
   start prerequisites for that deploy.

6. **Restore the databases**, with everything that talks to them stopped:

   ```bash
   ${SYSTEMCTL} stop frontend.service backend.service ingest.service keycloak.service
   ${SYSTEMCTL} start db-backend.service db-keycloak.service
   ${PODMAN} exec -i db-backend sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" dropdb   -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 --if-exists "$POSTGRES_DB"'
   ${PODMAN} exec -i db-backend sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" createdb -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 "$POSTGRES_DB"'
   ${PODMAN} exec -i db-backend sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 --no-owner -d "$POSTGRES_DB"' < "${R}/krt_basetool.dump"
   # repeat for db-keycloak on port 15433 with keycloak.dump
   ${SYSTEMCTL} start keycloak.service backend.service ingest.service frontend.service
   ```

   > Restore Keycloak from **`keycloak.dump`**, not `realm-export.json` — the dump is the source of
   > truth (live client secrets + users); the export only seeds an empty realm.

7. **Verify and clean up.** Compare the Flyway row count and latest version, and the realm's
   users/clients, against what you expect; confirm the `redis-requires-auth` and blackbox probes
   are green in Grafana. Then `rm -rf /var/iri/backup/restore` — it holds plaintext secrets and
   PII. Only now start the timers again: `systemctl start iri-deploy.timer iri-backup.timer`.

Sessions are not restored, so every member logs in again; that is expected.

### Rotate secrets after a compromise-driven restore

The host-config archive restores the **live secrets** (`.env`, `keystore.p12`, `realm-export.json`,
the provider JARs, the redis ACL). If this restore is the recovery from a **suspected host
compromise** (ransomware, intrusion — as opposed to hardware loss or an accidental delete), those
secrets must be assumed exposed: **rotate them once the stack is back up** (REQ-OPS-010). At
minimum —

- the backend/Keycloak **database passwords** (`POSTGRES_PASSWORD`, `KC_POSTGRES_PASSWORD`) and the
  **Redis password** (`REDIS_PASSWORD`) in `/var/iri/code/.env`, applied to the DBs — and to the
  `default` line of `/var/iri/redis/users.acl`, which is where redis actually reads it;
- the **Keycloak admin** bootstrap password and the **OIDC client secrets**
  (`KEYCLOAK_ADMIN_CLIENT_SECRET`) and the **SPI shared secret** (`KRT_DISCORD_SPI_SHARED_SECRET`);
- the internal **`keystore.p12`** (regenerate per [`deployment.md` → *Internal keystore and certificate rotation*](deployment.md#internal-keystore-and-certificate-rotation) — it must
  carry `dns:keycloak`) and re-apply its translated ownership and ACL (step 2);
- the **monitoring secrets** (`scrape_password`, `prometheus_web_password`, the Alertmanager
  receiver credentials);
- the **GHCR pull token** and the **backup** repo password + Nextcloud app password (REQ-OPS-012).

A restore with **no** compromise suspected keeps the restored secrets as-is.

## Routine operations

- **List / inspect:** `sudo -u deploy env RESTIC_CACHE_DIR=/var/lib/iri/restic-cache $(sudo grep -v '^#' /etc/iri/backup.env | xargs) restic snapshots`.
- **Rotate the Nextcloud credential:** create a new app password in Nextcloud, update
  `/etc/iri/rclone.conf`, revoke the old token. A host compromise is contained by revoking this one
  token (`REQ-OPS-012`).
- **A failed backup or drill** is alerted on, not just shown as `failed` in `systemctl`:
  `BackupStaleOrMissing` (no successful backup for 26 h, or the metric absent),
  `RestoreDrillStaleOrMissing` (8 days) and `RestoreDrillArtifactNotRestorable` (any
  `basetool_restore_drill_artifact_ok{artifact=…} == 0`) — all critical, in
  [`monitoring/prometheus/alerts/ops-automation.yml`](../monitoring/prometheus/alerts/ops-automation.yml).
  Treat a failed **restore drill** as a severe incident — it means the latest backup did not
  restore cleanly.
- **Change retention/schedule:** edit `/etc/iri/backup.env` (retention) or the timer's `OnCalendar`
  (a drop-in under `/etc/systemd/system/iri-backup.timer.d/`, so the role's next run does not
  overwrite it).
