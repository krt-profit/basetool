# ADR-0399 — A PostgreSQL major moves by dump and restore beside the old cluster

- **Status:** Accepted
- **Date:** 2026-11-20
- **Deciders:** @greluc
- **Related:** spec [`deployment-delivery.md`](../specs/deployment-delivery.md) (REQ-OPS-063,
  REQ-OPS-006) · [`backup-recovery.md`](../specs/backup-recovery.md) (REQ-OPS-009, REQ-OPS-011) ·
  [ADR-0049](0049-config-as-promotable-oci-artifact.md) ·
  [ADR-0056](0056-offsite-encrypted-backup-to-nextcloud.md) ·
  [ADR-0189](0189-stateful-containers-run-as-their-own-uid.md) · runbook
  [`docs/postgres-major-upgrade.md`](../postgres-major-upgrade.md)

## Context

`deploy.sh` refuses a changed `postgres:` tag until an operator has done "the documented manual
upgrade" and passes `--force` (REQ-OPS-006), but no such procedure was written: `docs/deployment.md`
said so and asked for one before a major is promoted. A server of a new major does not start on the
old major's data directory, so the step cannot be skipped.

PostgreSQL 19.0 was released on 2026-11-19, which made the gap concrete. An evaluation on
2026-11-20 against the pinned `postgres:19-alpine` (19.0) found:

- the backend's 11 082 tests pass on 19 apart from two that assert the major 18 itself;
- all 433 Flyway migrations (to V438) applied on 18.7 and dumped — with 18's `pg_dump` and with 19's
  — restore into 19 with identical per-table row counts, no invalid constraint or index, and a
  clean `flyway validate`; Keycloak 26.8.2's schema does the same, and Keycloak starts on the
  restored cluster with its realm and users;
- the only difference between the two clusters' schema dumps is how 19 prints 100 `IN (…)` check
  constraints, with the same meaning.

The facts the method has to live with:

- **The official image carries one major.** `pg_upgrade` needs the old and the new binaries side by
  side, which means a third-party image or a hand-built one.
- **The clusters are small, and their dump is proven.** `backup.sh` dumps both every night inside a
  quiesced window of seconds, and the weekly restore drill restores those dumps into a throwaway
  server and checks them (REQ-OPS-011).
- **An empty cluster is dangerous.** The backend's Flyway builds a complete schema on an empty
  database and Keycloak bootstraps an empty realm; a restore into either afterwards collides.
- **The data directory sits one level below the mount.** Each unit mounts `/var/lib/basetool/<db>`
  and sets `PGDATA` to `pgdata` inside it, so a second cluster fits beside the first with the same
  owner (container uid 70) and SELinux label, without a new host path.

## Decision

A PostgreSQL major is moved by **dumping each cluster and restoring the dump into a fresh cluster of
the new major beside the old data directory**, following
[`docs/postgres-major-upgrade.md`](../postgres-major-upgrade.md).

- **The old cluster stays untouched.** Its `pgdata` is renamed to `pgdata-<old major>` and nothing
  opens it again until the new cluster has been verified, backed up and drilled. Going back before
  members write is a rename.
- **The dump is the nightly one's.** `pg_dump -Fc` of the old major inside the running container,
  with the writers stopped — the path the backups and the drill already exercise. The new major's
  `pg_dump` over the database network stays the fallback for a release whose notes require it.
- **Nothing reaches the new cluster before it is complete.** A throwaway container of the release's
  image, with the unit's uid, environment file and mount but no network, initialises it; the
  cluster-wide roles a dump does not carry are created first, then the dump is restored and
  analyzed. Only then does `deploy.sh --force` start the stack on it.
- **A fingerprint decides.** Per-table row counts, sequence values and the number of invalid
  constraints and indexes must be identical in both clusters before the release is applied. That
  is the go/no-go, and the last point at which going back loses nothing.
- **The major moves alone.** The release that changes the tag changes nothing else, is rehearsed end
  to end on the testing host, and production's latest backup is first restored into the new major
  by the restore drill (`BASETOOL_DRILL_IMAGE`).
- **The two clusters may move apart.** `db-keycloak` moves only once Keycloak lists the major as
  supported; `db-backend` may go first.

## Consequences

- **Easier:** a major upgrade is a written, rehearsable procedure built on the dump the backups take
  every night. Rolling back before reopening is a rename and a start. The new cluster is created by
  the current `initdb` — with the new major's default extension versions, and with checksums since
  PostgreSQL 18 — rather than carrying the old one's defaults forward.
- **Harder / costs accepted:** the downtime is the dump, the restore and the analyze, so it grows
  with the data; `pg_upgrade --link` would stay near constant. `/var/lib/basetool` needs room for
  both clusters and the dumps until the clean-up. After members have written to the new cluster,
  going back loses those writes, because the old major's `pg_dump` refuses a newer server. Planner
  and cumulative statistics, `pg_stat_statements` included, start empty. Flyway warns on every start
  until it lists the new major. The procedure is a runbook of manual steps; nothing but the
  operator enforces its order.

## Alternatives considered

- **`pg_upgrade` with `--link`, `--clone` or `--swap`** (for example through `tianon/postgres-upgrade`,
  which carries two majors). Fast and independent of size, but it needs a third-party image with
  both binaries, a mount layout both sides agree on, matching checksum and locale settings, and the
  extension updates afterwards; `--link` and `--swap` leave no old cluster to go back to once the new
  one has started. At this size the dump costs minutes, and it is the path already proven nightly.
- **An image that upgrades in place on start (`pgautoupgrade`).** It would turn the operator gate of
  REQ-OPS-006 into an automatic, unverified migration the moment a unit starts — the blind recreate
  the gate exists to prevent.
- **Logical replication into a second instance.** Near-zero downtime, but it needs
  `wal_level = logical` and a restart on the old side, a second running cluster, a frozen schema
  and hand-synchronised sequences — out of proportion for minutes of planned downtime.
- **The new major's `pg_dump` against the old server.** PostgreSQL recommends it, and the
  evaluation found its dump restores identically for both schemas. It needs a throwaway container
  joined to the internal database network for the dump; the procedure keeps it as the fallback.
