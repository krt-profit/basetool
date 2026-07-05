# Backup & disaster recovery — operator runbook

> **Doc type:** Runbook — the *how-to*. The binding requirements (the *what-must-hold*) live in
> [`docs/specs/backup-recovery.md`](specs/backup-recovery.md) (`REQ-OPS-008..012`); the decision
> record is [ADR-0056](adr/0056-offsite-encrypted-backup-to-nextcloud.md).

## What this does

A nightly job ([`scripts/backup.sh`](../scripts/backup.sh)) captures a consistent, full-restore
backup set and pushes it **client-side encrypted** to a **Nextcloud** target via `restic` over an
`rclone` WebDAV remote. A weekly restore drill ([`scripts/restore-drill.sh`](../scripts/restore-drill.sh))
proves the backups are actually recoverable. Everything is outbound-only — consistent with the
pull-only host posture (`REQ-OPS-001`).

- **Schedule:** backup daily **04:15** (host-local), drill **Sunday 05:30**.
- **Retention (GFS):** keep 7 daily, 4 weekly, 6 monthly.
- **Downtime:** only the database dump (seconds), inside the 04:00–05:00 window — the slow upload
  runs after the stack is back up. See *How consistency works* below.

## What is and isn't backed up

**Captured** (the full-restore surface, `REQ-OPS-010`):

|           Item            |                                                               How                                                                |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| Backend DB `krt_basetool` | `pg_dump -Fc` inside the `db-backend` container                                                                                  |
| Keycloak DB `keycloak`    | `pg_dump -Fc` inside the `db-keycloak` container — **the live source of truth** for realm/users/clients, not `realm-export.json` |
| nginx-proxy-manager state | `tar` of `/var/iri/npm` (SQLite config + access lists + Let's Encrypt)                                                           |
| Host secrets/config       | `.env`, `keystore.p12`, `realm-export.json`, `keycloak/providers`                                                                |

**Excluded by design:**

- **Redis** — only Spring sessions; users transparently re-login after a restore.
- **Logs** — `/var/iri/{backend,frontend,keycloak}/log`.
- **WireGuard `wg0.conf`** — irreplaceable, but **you back this key up out-of-band yourself**
  (owner decision). It must not ride the same channel as the application data. Store it encrypted
  somewhere independent (e.g. a password manager / offline vault). A lost `wg0.conf` private key
  forces re-keying every peer.

## How consistency works (`REQ-OPS-009`)

`pg_dump` is already a transactionally consistent snapshot, so strictly no downtime is required.
For a *globally* quiescent instant, the job:

1. acquires the **same `flock`** `deploy.sh` uses (`/var/lock/iri-deploy.lock`) so a deploy tick
   cannot recreate containers mid-backup;
2. `docker compose stop`s the writers (`frontend`, `backend`, `ingest`) — NPM keeps serving the
   maintenance page;
3. dumps both databases + NPM + host config to local staging (**seconds**);
4. **restarts the writers and releases the lock** — production is live again;
5. only *then* runs the slow `restic` encrypt + upload + prune + check.

A `trap` guarantees the writers are restarted even if a dump step fails, so production is never
left down. Pass `--no-quiesce` for a zero-downtime online dump (accepting only a benign theoretical
cross-database edge case).

---

## One-time setup

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

### 2. Install the tools (on the prod host)

```bash
sudo apt-get update && sudo apt-get install -y restic rclone
```

### 3. Configure the rclone WebDAV remote

Create `/etc/iri/rclone.conf` (the app password goes here, obscured by rclone):

```bash
sudo install -d -m 0700 -o deploy -g deploy /etc/iri
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

> **Keep `RESTIC_PASSWORD` safe and separate.** It is the *only* key to your backups — if you lose
> it, the encrypted repo is unrecoverable. Store a copy in your password manager, **separately**
> from the Nextcloud app password.

### 5. Create the staging dir and initialize the repo

```bash
sudo install -d -m 0700 -o deploy -g deploy /var/iri/backup
sudo -u deploy env $(grep -v '^#' /etc/iri/backup.env | xargs) restic init   # one-time (backup.sh also self-inits)
```

### 6. Install the systemd units + logrotate, enable the timers

```bash
sudo cp /var/iri/code/scripts/iri-backup.service        /etc/systemd/system/
sudo cp /var/iri/code/scripts/iri-backup.timer          /etc/systemd/system/
sudo cp /var/iri/code/scripts/iri-restore-drill.service /etc/systemd/system/
sudo cp /var/iri/code/scripts/iri-restore-drill.timer   /etc/systemd/system/
sudo cp /var/iri/code/scripts/iri-backup.logrotate        /etc/logrotate.d/iri-backup
sudo cp /var/iri/code/scripts/iri-restore-drill.logrotate /etc/logrotate.d/iri-restore-drill
sudo chmod 0644 /etc/logrotate.d/iri-backup /etc/logrotate.d/iri-restore-drill
sudo systemctl daemon-reload
sudo systemctl enable --now iri-backup.timer iri-restore-drill.timer
```

### 7. Verify

```bash
sudo systemctl start iri-backup.service          # run a backup now
journalctl -u iri-backup.service -f              # watch it
sudo -u deploy /var/iri/code/scripts/backup.sh --dry-run   # show plan + snapshots
sudo systemctl start iri-restore-drill.service   # prove a restore works
systemctl list-timers 'iri-*'                    # confirm next fire times
```

---

## Restoring (disaster recovery)

Assumes a **bootstrapped host** (OS, Docker, the `deploy` user, the systemd units and the GHCR pull
token already in place — that is the [`docs/deployment.md`](deployment.md) bootstrap). This restores
*data + config* onto it.

1. **Fetch the snapshot** on a host with restic + `/etc/iri/backup.env` + rclone config:

   ```bash
   restic snapshots                         # pick the snapshot id
   restic restore latest --target /var/iri/backup/restore
   ```
2. **Restore host config** from `…/restore/.../config/`: place `dotenv` → `/var/iri/code/.env`,
   `keystore.p12` → `/var/iri/secrets/keystore.p12`, `realm-export.json` → `/var/iri/code/`, and
   extract `providers.tar.gz` → `/var/iri/code/keycloak/`. Re-apply the keystore's mode + uid-1000
   ACL (restic may not carry POSIX ACLs): `sudo chown root:10001 /var/iri/secrets/keystore.p12 &&
   sudo chmod 0640 /var/iri/secrets/keystore.p12 && sudo setfacl -m u:1000:r
   /var/iri/secrets/keystore.p12` (see [`docs/deployment.md`](deployment.md) → *5.2 PKCS12 keystore*).
3. **Restore NPM:** extract `npm.tar.gz` into `/var/iri/npm/` (recreates `data` + `letsencrypt`).
4. **Start the databases only**, then restore each dump into its database:

   ```bash
   cd /var/iri/code && docker compose --profile prod up -d db-backend db-keycloak
   docker compose --profile prod exec -T db-backend  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" dropdb   -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 --if-exists "$POSTGRES_DB"'
   docker compose --profile prod exec -T db-backend  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" createdb -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 "$POSTGRES_DB"'
   docker compose --profile prod exec -T db-backend  sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 --no-owner -d "$POSTGRES_DB"' < .../krt_basetool.dump
   # repeat for db-keycloak on port 15433 with the keycloak.dump
   ```

   > Restore Keycloak from **`keycloak.dump`**, not `realm-export.json` — the dump is the source of
   > truth (live client secret + users); the export only seeds an empty realm.

5. **Bring the rest up:** `docker compose --profile prod up -d`.
6. **WireGuard:** restore your out-of-band `wg0.conf` to `/etc/wireguard/wg0.conf` and
   `wg-quick up wg0` (not in this backup — see exclusions).

### Rotate secrets after a compromise-driven restore

The host-config archive restores the **live secrets** (`.env`, `keystore.p12`, `realm-export.json`,
the provider JARs). If this restore is the recovery from a **suspected host compromise**
(ransomware, intrusion — as opposed to hardware loss or an accidental delete), those secrets must be
assumed exposed: **rotate them once the stack is back up** (REQ-OPS-010). At minimum —

- the backend/Keycloak **database passwords** (`POSTGRES_PASSWORD`, `KC_POSTGRES_PASSWORD`) and the
  **Redis password** (`REDIS_PASSWORD`) in `/var/iri/code/.env`, applied to the DBs/Redis;
- the **Keycloak admin** bootstrap password and the **OIDC client secrets**
  (`KEYCLOAK_ADMIN_CLIENT_SECRET`) and the **SPI shared secret** (`KRT_DISCORD_SPI_SHARED_SECRET`);
- the internal **`keystore.p12`** (regenerate per [`docs/deployment.md`](deployment.md) → *Keycloak
  behind NPM* — it carries `dns:keycloak`) and re-apply its ACL;
- the **GHCR pull token** and the **backup** repo password + Nextcloud app password (REQ-OPS-012).

A restore with **no** compromise suspected keeps the restored secrets as-is.

## Routine operations

- **List / inspect:** `sudo -u deploy restic snapshots` (after sourcing `/etc/iri/backup.env`).
- **Rotate the Nextcloud credential:** create a new app password in Nextcloud, update
  `/etc/iri/rclone.conf`, revoke the old token. A host compromise is contained by revoking this one
  token (`REQ-OPS-012`).
- **A failed backup or drill** shows `failed` in `systemctl` / `journalctl`. Treat a failed
  **restore drill** as a severe incident — it means the latest backup did not restore cleanly.
- **Change retention/schedule:** edit `/etc/iri/backup.env` (retention) or the timer's `OnCalendar`.

