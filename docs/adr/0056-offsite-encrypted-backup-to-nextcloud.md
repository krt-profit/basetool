# ADR-0056 — Off-site encrypted backups to Nextcloud via restic over rclone WebDAV

- **Status:** Accepted
- **Date:** 2026-06-30
- **Deciders:** @greluc
- **Related:** spec `REQ-OPS-008..012` ([`backup-recovery.md`](../specs/backup-recovery.md)) · runbook [`docs/backup.md`](../backup.md) · builds on `REQ-OPS-001` (pull-only host) and `REQ-OPS-005` (no secrets in the config bundle)

## Context

The production host carried irreplaceable state with **no automated backup**: the backend
PostgreSQL database (`krt_basetool`), the Keycloak PostgreSQL database (realm/users/clients —
the live source of truth, of which `realm-export.json` is only a sanitized seed), the
nginx-proxy-manager configuration (proxy topology, access lists, Let's Encrypt certs), and the
host secrets needed to stand the stack up (`.env`, `keystore.p12`). A lost host, a bad
migration, an accidental delete, or ransomware would be unrecoverable.

Constraints shaping the choice:

- **Off-site & independent.** A backup must survive losing the production host, so it has to land
  on storage in a different failure domain.
- **The host is pull-only (REQ-OPS-001).** Nothing may open an inbound path to it; a backup may
  only push *outbound*.
- **The data is sensitive.** Every backup contains secrets and PII, so it must be encrypted
  *before* it leaves the host — the storage target must never see plaintext.
- **The requested target was a UniFi drive** shared via a `drop.ui.com` link. Investigation showed
  `drop.ui.com` is a download/share feature with **no documented upload API**, and UniFi Drive/UNAS
  only pushes its *own* data outbound (S3/SMB) — it is not designed to *receive* automated uploads
  from an external server. The owner then chose **Nextcloud** (self-hosted, separate hardware) as
  the target instead.

## Decision

We will back up with **`restic`** writing to a **Nextcloud** repository over an **`rclone` WebDAV**
remote, scheduled by systemd:

- **restic** because it gives client-side encryption, deduplication, incremental snapshots, GFS
  retention/pruning, and integrity checks (`restic check`) out of the box. The Nextcloud server
  only ever stores encrypted, deduplicated blobs; the key lives only on the host.
- **Nextcloud over rclone WebDAV** because Nextcloud exposes first-class WebDAV over HTTPS with a
  revocable **app password** on a dedicated, non-admin account — a clean, authenticated, outbound
  push with no VPN, no SMB, and no anonymous share link.
- **Consistency via a minimal quiesce (REQ-OPS-009):** the writer services are stopped only for the
  database dump (seconds), then restarted before the slow upload; NPM serves the maintenance page
  meanwhile. The job coordinates with `deploy.sh` via the shared `flock` and guarantees a restart
  via a `trap`.
- **Recoverability is proven (REQ-OPS-011):** a weekly restore drill restores the latest snapshot
  into a throwaway PostgreSQL container and verifies it.
- The WireGuard `wg0.conf` key is **excluded** and backed up out-of-band by the operator (owner
  decision, REQ-OPS-010).

## Consequences

- **Easier:** a single command path produces a verified, encrypted, off-site, retention-managed
  backup; restores are rehearsed weekly; the whole thing is outbound-only and fits the existing
  unprivileged-`deploy`-user + systemd-timer operational model.
- **Accepted costs:** a brief nightly write-downtime (dump duration only, inside 04:00–05:00); two
  new host dependencies (`restic`, `rclone`); host-only backup credentials in `/etc/iri/backup.env`
  to provision and rotate; and a hard dependency on the Nextcloud instance being **independent** of
  the prod host (if it is co-located, the off-site property is lost — called out in the runbook).
- **Follow-up:** wire an `OnFailure=` alert so a failed backup/drill pages the operator rather than
  only showing `failed` (tracked as an open question in the spec).

## Alternatives considered

- **UniFi `drop.ui.com` share link** — rejected: no documented/stable upload API; automating it
  means reverse-engineering an undocumented endpoint that breaks silently — the worst property for a
  backup channel.
- **SMB-to-UNAS over a VPN tunnel** — viable (the host already runs WireGuard) but more moving parts
  (tunnel + SMB credentials + mount reliability) and ties the backup to LAN reachability. Kept as a
  fallback, not the primary.
- **S3-compatible object storage (Backblaze B2 / Wasabi)** — technically the most robust target and
  fully restic-supported; rejected as the *primary* only because the owner wanted the backups on
  their own Nextcloud. restic makes switching/adding this target a one-line `RESTIC_REPOSITORY`
  change if desired.
- **Plain `pg_dump` cron without encryption** — rejected: backups carry secrets + PII and must be
  encrypted off-site.
- **Continuous WAL archiving / PITR** — rejected as overkill for this data size and RPO; daily
  logical dumps suffice (see spec *Out of scope*).
