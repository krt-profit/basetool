> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-30.
> **Owner area:** OPS · **Related ADRs:** [ADR-0056](../adr/0056-offsite-encrypted-backup-to-nextcloud.md)

# Backup & disaster recovery

## Context & goal

The production host carries irreplaceable state — two PostgreSQL databases (the backend
`krt_basetool` and the Keycloak `keycloak` realm/users), the nginx-proxy-manager
configuration, and a handful of host secrets — but until this spec there was **no automated
backup of any of it**. A lost or corrupted host, a bad migration, an accidental delete, or a
ransomware event would be unrecoverable.

This spec pins the binding requirements for an **automated, scheduled, client-side-encrypted,
off-site** backup of everything needed for a full restore, plus a recurring proof that those
backups are actually recoverable. The implementation is `scripts/backup.sh` /
`scripts/restore-drill.sh` driven by the `iri-backup.{service,timer}` and
`iri-restore-drill.{service,timer}` systemd units; the operator runbook (the *how-to*) lives in
[`docs/backup.md`](../backup.md). See [ADR-0056](../adr/0056-offsite-encrypted-backup-to-nextcloud.md)
for the decision record (restic over rclone WebDAV to Nextcloud; why not the UniFi
`drop.ui.com` share or SMB-over-VPN).

These requirements continue the `REQ-OPS-*` series begun in
[`deployment-delivery.md`](deployment-delivery.md) (`REQ-OPS-001..007`).

## Requirements

### REQ-OPS-008 — Automated, scheduled, encrypted, off-site backups

A scheduled job captures the full-restore surface (REQ-OPS-010), encrypts it **client-side**,
and stores it on an **off-site** target that is independent of the production host. Encryption
is mandatory and non-negotiable: every backup contains host secrets (`.env`, keystore) and
PII (the Keycloak user database), so the storage target must never see plaintext. The default
schedule is **daily at 04:15 host-local**, with **GFS retention** (keep 7 daily, 4 weekly, 6
monthly) and a post-upload **integrity check**. Delivery is **outbound-only** (the host pushes;
nothing is pulled or exposed), consistent with the pull-only host posture of REQ-OPS-001.

**Acceptance**

- [ ] `iri-backup.timer` fires `backup.sh` daily at 04:15; `Persistent=true` catches up a missed run.
- [ ] The repository is a restic repo (client-side encrypted, deduplicated); the storage target
  (Nextcloud over rclone WebDAV) only ever receives encrypted blobs.
- [ ] After each upload the job runs `restic forget --keep-daily 7 --keep-weekly 4 --keep-monthly 6
  --prune` and `restic check`.
- [ ] The job opens no listening socket and requires no inbound access to the host.

**Enforced by:** `scripts/backup.sh` · `scripts/iri-backup.{service,timer}` · **Runbook:** [`docs/backup.md`](../backup.md)

### REQ-OPS-009 — Consistency via a minimal, bounded nightly quiesce

The backup must be **consistent**. `pg_dump` is already a transactionally consistent per-database
snapshot, but to obtain one globally quiescent instant the job briefly **stops the writer
services** (`frontend`, `backend`, `ingest`) for the **dump only**, then restarts them **before**
the slow upload — so the user-facing window is the dump duration (seconds), never the upload.
The quiesce must (a) happen inside the **04:00–05:00** window, (b) be as short as possible, (c)
**guarantee the stack is restarted** even if a dump step fails, and (d) **coordinate with the
deployer** so a deploy tick cannot recreate containers mid-backup. NPM stays up throughout and
serves the existing maintenance page. An operator may opt into a zero-downtime online dump
(`--no-quiesce`), accepting only the benign theoretical cross-database edge case.

**Acceptance**

- [ ] The dump runs with `frontend`/`backend`/`ingest` stopped; they are restarted before the
  restic upload begins.
- [ ] A `trap` restarts the writers on any failure path — a failed dump never leaves production down.
- [ ] `backup.sh` acquires the same `flock` (`/var/lock/iri-deploy.lock`) as `deploy.sh` and
  releases it as soon as the writers are back up.
- [ ] The scheduled start is at 04:15, after the 04:00 `vpn-restart`, inside the 04:00–05:00 window.

**Enforced by:** `scripts/backup.sh` (quiesce + trap + flock) · **Runbook:** [`docs/backup.md`](../backup.md)

### REQ-OPS-010 — Defined backup surface and explicit exclusions

The backup set is exactly what a full restore needs, and exclusions are deliberate, not
accidental. **Captured:** `pg_dump -Fc` of `krt_basetool`; `pg_dump -Fc` of `keycloak` (the live
source of truth — **not** the sanitized `realm-export.json`); the nginx-proxy-manager state
(`/var/iri/npm` = SQLite config + Let's Encrypt); and the host secrets/config needed to stand the
stack up (`.env`, `keystore.p12`, `realm-export.json`, `keycloak/providers`). **Excluded by
design:** Redis (sessions transparently re-login), logs, and the WireGuard `wg0.conf` key — the
operator backs that key up **out-of-band** (it is irreplaceable but must not ride the same
channel as the application data, by owner decision).

Because the host-config archive carries the live secrets (`.env`, `keystore.p12`, `realm-export.json`,
the `keycloak/providers` JARs), a restore that follows a **suspected host compromise** (ransomware is
a named DR driver, above) restores *potentially-exposed* secrets. Such a restore must therefore be
followed by **rotation** of the restored secrets — the DB/Redis/Keycloak-admin passwords, the OIDC
client secrets, the SPI shared secret, the internal keystore, and the GHCR pull token — not treated
as clean. A restore for hardware loss / accidental deletion (no compromise suspected) does not.

**Acceptance**

- [ ] A backup contains both database dumps, the NPM archive, and the host-config archive.
- [ ] `wg0.conf` and the Redis dump are **not** in the backup set; the runbook documents the
  operator's out-of-band responsibility for `wg0.conf`.
- [ ] The Keycloak DB dump — not `realm-export.json` — is the documented restore source for
  realm/users/clients.
- [ ] The restore runbook documents that a **compromise-driven** restore must be followed by
  rotating the restored secrets; a non-compromise restore need not.

**Enforced by:** `scripts/backup.sh` (capture list) · **Runbook:** [`docs/backup.md`](../backup.md) → *What is and isn't backed up*, *Rotate secrets after a compromise-driven restore*

### REQ-OPS-011 — Recoverability is proven, not assumed

A backup that has never been restored is unverified. A **weekly** restore drill pulls the latest
off-site snapshot, restores **both** database dumps into a **throwaway** PostgreSQL instance, and
verifies them with sanity queries (backend: `flyway_schema_history` present and non-empty + a
public-table-count floor; Keycloak: a public-table-count floor). The drill touches **no**
production state, and a failure makes the unit report `failed` so it is caught.

**Acceptance**

- [ ] `iri-restore-drill.timer` runs `restore-drill.sh` weekly.
- [ ] The drill restores into an ephemeral container and tears it down; it never writes to a
  production database, volume, or the live stack.
- [ ] An empty/garbage restore (no `flyway_schema_history` rows, or table counts below the floor)
  exits non-zero (`failed`).

**Enforced by:** `scripts/restore-drill.sh` · `scripts/iri-restore-drill.{service,timer}` · **Runbook:** [`docs/backup.md`](../backup.md)

### REQ-OPS-012 — Backup credentials are host-only secrets

The restic repository password and the rclone/Nextcloud **app password** are host-only secrets in
`/etc/iri/backup.env` (and the rclone config), readable only by the backup user. They are **never**
committed to git and **never** carried in the `basetool-config` OCI bundle — the same prohibition
REQ-OPS-005 places on `.env`/keystore. The Nextcloud target is accessed via a **dedicated,
non-admin account + revocable app password** (not the human account password, not a public share
link), so a host compromise is contained by revoking one token.

**Acceptance**

- [ ] `/etc/iri/backup.env` and the rclone config are mode `0600`/owner-restricted and outside the repo.
- [ ] No backup credential, restic password, or Nextcloud app password appears in git history or the
  config bundle.
- [ ] The runbook provisions a dedicated Nextcloud account with an app password and quota, not a
  public/anonymous share link.

**Enforced by:** `/etc/iri/backup.env` (host) · `scripts/backup.sh` (sources it, never logs it) · **Runbook:** [`docs/backup.md`](../backup.md) → *Set up the Nextcloud target*

## Out of scope

- **Point-in-time recovery (PITR / WAL archiving).** Logical `pg_dump` snapshots give a per-day
  recovery point, which fits this app's size and RPO. Continuous WAL archiving is deliberately not
  pursued (see ADR-0056 alternatives); promote to an ADR if a sub-day RPO is ever required.
- **The WireGuard `wg0.conf` key.** Irreplaceable, but backed up out-of-band by the operator by
  owner decision (REQ-OPS-010) — not this job's concern.
- **Restoring/rebuilding the host OS, Docker, the `deploy` user, systemd units, and the GHCR pull
  token.** These are the bootstrap concern of [`docs/deployment.md`](../deployment.md); the restore
  runbook in [`docs/backup.md`](../backup.md) assumes a bootstrapped host and restores *data + config*
  onto it.

## Open questions

- Wiring an `OnFailure=` alerting unit (e-mail / notification) onto `iri-backup.service` and
  `iri-restore-drill.service` so a failed backup or drill pages the operator rather than only showing
  `failed` in `systemctl`. Promote to an ADR/issue when an alerting transport is chosen.
- Whether to add an occasional `restic check --read-data-subset` (full data re-read) beyond the
  structural nightly `restic check`, traded off against egress cost.

