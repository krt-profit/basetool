# ADR-0009 — PostgreSQL stays the single datastore for the Kartell bank

- **Status:** Accepted (epic #556 delivered, 2026-06-13)
- **Date:** 2026-06-12
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-BANK-018, REQ-BANK-020 (`docs/specs/bank.md`) · ADR-0010 ·
  epic [#556](https://github.com/krt-profit/basetool/issues/556)

## Context

The bank feature introduces a financial ledger (accounts, postings, audit log) and the
question was raised explicitly whether PostgreSQL remains optimal for full or partial
data retention of a bank, or whether an additional data store is needed for performance
and availability.

Forces:

- **Scale is small.** The org has on the order of hundreds of users; even very active
  manual bookkeeping produces thousands — not millions — of postings per month. A
  100k-posting ledger with a composite index answers a per-account balance aggregate in
  single-digit milliseconds on the existing 4 vCPU / 8 GB VM.
- **Correctness needs ACID.** Double-entry invariants (postings sum to zero, no
  overdraft under concurrency, audit row in the same transaction) are exactly what a
  transactional RDBMS gives for free and what eventually-consistent stores make hard.
- **Operational surface is fixed.** Deployment is a single Hetzner VM with Docker
  Compose, nightly-class backups, Flyway migrations on startup and a pull-only deploy
  pipeline (`docs/deployment.md`). Every additional store adds backup, monitoring,
  upgrade and failure modes to a deliberately small operation.
- **Availability is bounded by the VM, not the database.** Backend, frontend, Keycloak
  and Redis share the same host; adding a second datastore would not raise availability
  one bit — it would add a component that can be down.
- Precedent: the inventory ledger (ADR-0003) already runs append-only + group-on-read on
  PostgreSQL with no performance issues.

## Decision

We will keep **PostgreSQL as the single datastore** for all bank data — accounts,
holders, transactions, postings, grants and the audit log. No second database, no
event store, no separate OLAP/reporting store, no Redis-backed balance cache. Read
performance is secured structurally instead: append-only postings with a composite index
`(account_id, created_at)`, grouped single-statement aggregates for balances, dashboards
and statements, and (only if ever needed) later opt-ins like a running-balance column or
PostgreSQL native partitioning — both of which are internal optimizations that do not
change the spec contract (REQ-BANK-004).

## Consequences

- **Easier:** one backup/restore story covers the bank; Flyway owns the schema; the
  wipe-reset and audit trail live in the same transactional boundary as the ledger;
  tests stay plain Spring/JPA tests.
- **Easier:** the existing optimistic-locking, pagination and RFC 7807 conventions apply
  unchanged.
- **Harder / accepted:** very large analytical queries (multi-year, all accounts) run on
  the OLTP store — acceptable at org scale; the three-month management export is the
  designed ceiling for heavy reads (REQ-BANK-015).
- **Accepted cost:** if the org ever federates across multiple servers or sees orders of
  magnitude more volume, this ADR must be superseded (running-balance snapshots and
  partitioning first; a dedicated store only after those are exhausted).
- Follow-up work: seeded volume test (≥ 100 accounts / ≥ 100k postings) pins the
  performance assumption (REQ-BANK-020).

## Alternatives considered

- **Dedicated event store (Kafka, EventStoreDB)** — rejected: massive operational
  footprint on a single VM, no consumer needing a stream, double-entry invariants would
  need re-implementing on top.
- **Second PostgreSQL instance / read replica for reports** — rejected: availability is
  host-bound; replication adds ops burden without removing the single point of failure.
- **NoSQL document store for postings** — rejected: gives up the transactional
  invariants the ledger exists for; aggregation is weaker, not stronger, at this scale.
- **Redis as balance cache** — rejected for v1: balances are cheap SQL aggregates;
  a cache adds an invalidation bug class for zero measured need. Can be revisited with
  data from the volume test.
