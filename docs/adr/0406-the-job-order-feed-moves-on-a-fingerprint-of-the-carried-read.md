# ADR-0406 — The job-order feed moves on a fingerprint of the carried read

- **Status:** Accepted — owner decision in #3615 (the feed) and #3618 (its monitoring).
- **Date:** 2026-11-23
- **Deciders:** @greluc
- **Related:** spec [`orders-exchange.md`](../specs/orders-exchange.md) (`REQ-OXCH-003`) ·
  [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-013`) ·
  [`observability.md`](../specs/observability.md) (`REQ-OBS-025`) ·
  [ADR-0224](0224-the-change-feed-is-a-trigger-written-key-log.md) (the trigger-written key log this
  sits beside)

## Context

ADR-0224 makes every exchange feed a key log that `AFTER` row triggers write on every write of a
synced table. For job orders that is too much and too little at once:

- **Too much.** The threat model lets the job-order feed move only when a field the client read
  carries changes. A provision, a holder movement, a fee, a unit commitment or a claim that leaves
  the open amount unchanged writes rows of the order but changes nothing a client reads; a feed
  entry for it would tell a client when money moved.
- **Too little.** Several carried figures are derived, not stored. `netHandedOver`, `remaining`,
  `overpaid` and `settlementOpen` come from the money ledger in Java, the MATERIAL `open` amount
  from linked stock and claims, and `responsibilityInvalid` from the Profit hierarchy, which can
  change without writing the order at all.

A trigger cannot tell whether a write changed a derived figure, and a hook in every service misses
bulk SQL, the patch change, account merges, the name retention and hierarchy changes.

## Decision

1. **One projection, fingerprinted.** The job-order module projects each order's
   member-independent exchange read (`JobOrderExchangeReads` in `joborder.api`, the record
   `JobOrderExchangeRead`): header, status, priority, units and active support by id, comment, the
   MATERIAL and ITEM contact, `responsibilityInvalid`, `versions { lifecycle, target, comment }`,
   assignees by id and each kind's progress. The exchange stores the SHA-256 of a canonical encoding
   of that record. The encoding walks the record's components in declaration order, each value
   type-tagged and strings and lists length-prefixed, an amount without its trailing zeros, so
   every component is fingerprinted by construction. The client read serves the same projection
   and adds only the member's own part (relations, own note, names and refs).
2. **The fingerprint sits beside the order.** `exchange_job_order_fingerprint` holds one row per
   order (cascading with it): the fingerprint, its instant and the read's `settlementOpen`. The
   worker never locks a `job_order` row, so it neither waits behind nor blocks a web write, and the
   order's own trigger never sees the worker's write.
3. **Triggers only mark.** `AFTER` row triggers on the order, its material and item lines,
   assignees, support, earmarks (and the material and quality of an earmarked stock row), claims,
   money operations, corrections, holders, Bank account positions and the target insert a dirty
   mark in `exchange_job_order_dirty`, one per order and marking transaction. A later mark never
   replaces an earlier one, so the oldest mark keeps its age, and inserting a mark never waits on
   another transaction's mark. The triggers are named `trg_<table>_exchange_dirty`, never
   `trg_<table>_exchange_change`, so the guard of ADR-0224 point 6 keeps counting one feed trigger
   per table.
4. **A hierarchy change marks the client set.** A change of a unit's activation, kind, department
   or parent, or an active Profit area added or deleted, marks every order of the client set:
   active, closed within 7 days by `closed_at`, and aUEC while the stored `settlementOpen` holds.
   The stored flag is how SQL selects a set whose last condition is computed in Java.
5. **A worker recomputes after the commit.** Every few seconds, on one backend instance at a time
   (advisory lock space `OXFP`), and whatever the job-order switches say, a run reads the marks of
   the orders marked longest ago and projects those orders in one `REPEATABLE READ` transaction. It
   writes a fingerprint and its instant only where the fingerprint changed, and deletes exactly the
   marks it read. A mark committed after the run's snapshot belongs to a write the run did not see
   and survives for the next run, so no write is lost.
6. **The instant is the worker's.** The instant stored with a changed fingerprint is when the
   worker found the change; it is the read's `updatedAt`. A mark's own time is not used: a mark
   can come from a write that changed nothing carried, and its time would leak that write.
7. **The log is per unit and per member, written from the fingerprint.** A changed fingerprint
   writes the `exchange_change` entries of the order, through a trigger on the fingerprint row, so
   the key log of ADR-0224 stays trigger-written: one entry each for the requesting, the
   responsible and every actively supporting unit, old and new, and one for each assignee, old and
   new. The fingerprint row keeps the units and assignees it was announced to, read in the worker's
   snapshot, so a change and a hard delete reach the old ones. A feed page reads the entries of the
   member's current direct units and the member's own entries and resolves every key against the
   member's current set.
8. **A member who stops reading through a unit is told per member.** A membership that ends or
   moves and a unit that is deactivated announce to each member concerned, in their own log, the
   unit's orders of the client set, the orders last announced to the unit and every order the
   unit's log still names. A client that syncs only after its member left therefore still drops an
   order that left the unit in the meantime, although the member no longer reads the unit's log.

## Consequences

- The feed moves on a changed carried field and on nothing else, a hierarchy change and bulk SQL
  included; a client learns a change a few seconds late.
- A new component of `JobOrderExchangeRead` is carried and fingerprinted at once. Renaming,
  reordering or adding a component changes every fingerprint once, so every order of the set
  reaches the feed once.
- A recomputation that keeps failing, for example over a broken order, holds the backlog:
  `JobOrderFingerprintWorkerStale` and `JobOrderExchangeFeedLagging` show it. A trigger loop shows
  as `JobOrderFingerprintChurn` (REQ-OBS-025).
- Writes to orders outside the client set are marked and recomputed too; the hash simply does not
  move for them.
- A membership end or a unit's deactivation writes one entry per member and order the unit's log
  names, a burst bounded by the log's 90 days; a client ignores the keys it never held.

## Alternatives considered

- **Source triggers as the change signal (ADR-0224 as it is).** Rejected: a trigger cannot tell
  whether a holder movement or a partial settlement changed a carried figure, so it either leaks
  the timing of money writes or misses a change.
- **A hook in every write path before the commit.** Rejected: bulk SQL and hierarchy changes would
  reach the feed only through a nightly reconcile.
- **A periodic full recompute.** Rejected: constant load and up to a minute of delay.
- **The fingerprint as columns of `job_order`.** Rejected: the worker would take a row lock that web
  writes wait on and that can deadlock with their own lock order, and the order's update trigger
  would mark the order again on every worker write.
- **One mark per order.** Rejected: updating it on every further mark holds the mark row until the
  writer commits, so writers of one order wait on each other there; leaving it as it is lets a
  worker that deletes the mark drop the change of a writer that found it already set.
- **Hashing a JSON rendering of the read.** Rejected: a serialiser upgrade that renders a number
  differently would change every fingerprint at once.
