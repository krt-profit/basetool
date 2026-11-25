# ADR-0056 — Off-site encrypted backups to Nextcloud via restic over rclone WebDAV

- **Status:** Accepted — amended 2026-11-25 (the nightly dump runs online, see amendment 1)
- **Date:** 2026-06-30
- **Deciders:** @greluc
- **Related:** spec `REQ-OPS-008..012` ([`backup-recovery.md`](../specs/backup-recovery.md)) · runbook [`docs/backup.md`](../backup.md) · builds on `REQ-OPS-001` (pull-only host) and `REQ-OPS-005` (no secrets in the config bundle)

> **Note (2026-09-22):** two statements below never described the production host. There is no
> WireGuard on it — the management VPN was planned and not carried out
> ([`MGMT_VPN_PLAN.md`](../archive/MGMT_VPN_PLAN.md)) — so there is no `wg0.conf` to exclude or to
> back up, and the "SMB over the existing WireGuard tunnel" alternative had no tunnel to use. The
> maintenance page during the quiesce is served by the native-nginx edge (ADR-0162), not NPM. The
> restic-over-rclone design itself stands; the current procedure is [`backup.md`](../backup.md).

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
  new host dependencies (`restic`, `rclone`); host-only backup credentials in `/etc/basetool/deploy/backup.env`
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

## Amendment 1 — the nightly dump runs online — 2026-11-25

**Context.** The decision above accepted "a brief nightly write-downtime (dump duration only)". On
production the dump is that short, but the stop and restart around it are not. Measured in
`backup.log` and the service user's journal on 2026-11-22 to 2026-11-25, every night alike:

| Step (UTC, 2026-11-25) | Duration |
| --- | --- |
| stop `frontend`, `ingest`, `backend` one after the other, each with its graceful drain | 45 s |
| both `pg_dump`s, the edge volumes, the ACLs and the host config | 9 s |
| start `backend`, `frontend`, `ingest` one after the other, each until healthy | 95 s |

The frontend answered with the maintenance page from 04:15:12 to 04:17:10, ingest from 04:15:33 to
04:17:41. About 400 connections reached the host in that span each night.

The quiesce bought less than its name says. Keycloak and its database keep running throughout, so
the two dumps were never of one instant either. Each `pg_dump` already reads one MVCC snapshot,
the committed state at one instant, which is the state the backend must survive after any crash.
Schema changes come only from Flyway when a release starts the backend, and the deploy `flock` keeps
a release out of the dump whether the writers run or not. The backend takes no table lock that
conflicts with `pg_dump`: there is no `TRUNCATE`, `LOCK TABLE` or `REFRESH MATERIALIZED VIEW` at run
time, only advisory locks.

**Decision (the owner's, in chat).** `backup.sh` dumps **online by default**. It keeps the deploy
`flock` across the dumps and dumps `krt_basetool` before `keycloak`, so a Keycloak account newer than
the backend dump gets its backend row on its next login, and no backend row can be left without its
Keycloak account. `--quiesce` replaces `--no-quiesce` as the opt-in that stops the writers for the
dumps. The Grafana stop for its SQLite copy stays: it takes only the monitoring UI down, for about
30 s. REQ-OPS-009 is rewritten to match.

**Consequences.** No member-facing downtime is left in the nightly run, and with it goes the
nightly restart that could leave a writer unhealthy until the deploy bot healed it. A restore
carries a skew of a few seconds between the two databases, which the identity reconciliation
absorbs. A snapshot from before this amendment restores the same way.

**Rejected:** keeping the quiesce and shortening it by starting `frontend` and `ingest` in parallel
(about 30 s less, still over a minute of maintenance page every night, for a guarantee Keycloak's
running database never gave); dumping Keycloak first (a backend row whose Keycloak account is missing
turns into a second, pending account on the member's next sign-up).
