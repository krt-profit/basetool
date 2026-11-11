# ADR-0367 — Role sections and transaction resources have independent lifetimes

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** @greluc
- **Related:** [REQ-SEC-088](../specs/security-and-access.md),
  [REQ-DATA-039/040](../specs/data-persistence.md), audit epic #3267

## Context

Role descriptions and permission sets are edited independently. A fresh entity load cannot
detect a stale client save, while one screen-wide expected version would reject unrelated edits.
Custom transaction resources also require lifecycle boundaries: a suspended outer transaction's
live-sync collection and read memo must not serve an inner `REQUIRES_NEW` transaction.
Announcement GETs must not create state, and concurrent initial writes need a database singleton
invariant without discarding existing rows or changing banner dismissal semantics.

## Decision

1. Role permission and description requests carry their independent expected section version.
   Each mutation obtains a join-free role row lock, checks through `OptimisticLock` and increments
   only its section counter. The row lock serializes database writes; section counters determine
   whether a client is stale.
2. Every custom transaction-bound live-sync collection or memo unbinds on `suspend`, rebinds on
   `resume` and releases its resource on completion. Commit notifications belong to the transaction
   that collected them. The four inner/outer commit and rollback combinations are regressions.
3. The announcement table has a current-row discriminator and a partial unique index permitting
   one current row. Migration selects the visible banner deterministically and retains all other
   rows as non-current. GET returns an unsaved empty view when absent; conflict-safe initialization
   occurs only on a write. Deleting the current banner removes that row, so the next banner gets
   a fresh UUID and is not hidden by an earlier dismissal marker.

## Consequences

The role API exposes both counters and accepts versioned records. Permissions and descriptions
remain independently editable. Transaction-local state and notifications cannot leak across
suspension, including when either transaction rolls back. These tests characterize infrastructure
behavior without asserting a previously observed production trigger. Banner reads are pure and
concurrent creation is constrained in PostgreSQL; retained non-current rows are excluded from all
application banner reads and writes.
