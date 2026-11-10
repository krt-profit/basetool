# ADR-0362 — An offer write and a Lager delete take turns on the row lock

- **Status:** Accepted — the `StockCommands.lockRow` consequence is **amended by
  [ADR-0363](0363-a-lager-row-is-locked-in-one-statement-without-a-join.md)** (every single-row
  Lager lock is join-free)
- **Date:** 2026-11-10
- **Deciders:** @greluc
- **Related:** spec [`materialboerse.md`](../specs/materialboerse.md) (REQ-MARKET-013) ·
  [`inventory-lager.md`](../specs/inventory-lager.md) (REQ-INV-026, REQ-INV-041) ·
  [`audit.md`](../specs/audit.md) (REQ-AUDIT-001) · issue #3261

## Context

A Materialbörse offer stands on a Lager row through a foreign key with `ON DELETE CASCADE`. Every
Lager path that deletes rows first reads their active offers, records `MARKET_OFFER_REMOVED` and
sends `STOCK_GONE` for each (REQ-MARKET-013), then deletes the rows, and the cascade removes the
offers. Nothing kept an offer from being released between that read and the `DELETE`:

- The release read the row without a lock and inserted the offer. The insert's foreign-key check
  takes `FOR KEY SHARE` on the row.
- The bulk checkout and the bulk rebooking lock their rows with `PESSIMISTIC_WRITE`, which
  Hibernate renders on PostgreSQL as `FOR NO KEY UPDATE`. That lock does not conflict with
  `FOR KEY SHARE`.
- The global wipe and the account deletion held no row lock at all.

An offer released in that window was cascaded away with a `MARKET_OFFER_RELEASED` and no
`MARKET_OFFER_REMOVED`. In the reverse order, an offer deactivated in the window was reported as
removed, and its interested members got a `STOCK_GONE` after their `WITHDRAWN`.

## Decision

1. **The row lock is the one serialization point.** Every path that deletes Lager rows holds their
   `PESSIMISTIC_WRITE` locks before it reads their offers. Every Materialbörse write that makes or
   ends an active offer on a row locks that row before it reads it. Two writers on one row then take
   turns, and the offer read of a delete is a later statement than its lock, so under `READ
   COMMITTED` it sees every offer committed before the lock was granted.
2. **The release and the deactivation lock the row.** `StockRowLocks.lockRowForOfferWrite` takes
   the lock in one statement with no join. `StockRowLocks` is a lock-only Lager API beside
   `StockCommands`; its implementation writes nothing, so a Materialbörse controller does not
   become a Lager writer for the tenancy guard (REQ-ORG-028). A row that a delete removed while the lock waited reads
   as absent, so the release answers the existing 404 (`Inventory item not found`) and the
   deactivation the existing `Offer not found`. The deactivation looks up the offer's row id first,
   locks the row, and only then loads the offer.
3. **A bulk delete deletes exactly the rows it locked.** The global wipe locks the shared rows of
   its scope in ascending id order (`lockNonPersonalIdsInScope`), audits the offers on those ids and
   deletes those ids in one statement, binding them as one `uuid[]` parameter. A row booked into the
   scope while the wipe runs survives it, as if it were booked after the wipe. Every later
   server-side wipe follows the same lock, audit, delete-by-id order.
4. **The account deletion locks the member's rows** (`StockRowLocks.lockRowsOf`) before the
   Materialbörse reads the offers on them.
5. **The stock merge checks again after it locks.** Its locking read excludes offer-backed rows as
   that statement saw them. An offer released while the merge waited is checked in a fresh
   statement after the lock, and its row is not folded.

## Consequences

- Each of the paths in REQ-MARKET-013 either audits an offer it cascades away or refuses the
  release. `MaterialExchangeOfferDeleteRaceIntegrationTest` shows both orders with two transactions.
- The release checks the offered amount against stock it holds locked.
- A wipe blocks writes to the rows in its scope until it commits, and it waits for writes already
  holding one of them. It locks its rows in ascending id order.
- The offer read by row ids binds one array parameter, so a wipe of any size stays one statement.
- `StockCommands.lockRow` keeps Hibernate's follow-on locking: it loads the row with its
  associations, then locks and re-reads it. A row deleted while that lock waits raises a Hibernate
  `AssertionFailure` (a 500) rather than an empty result. The offer writes do not use it.

## Alternatives considered

- **Lock the rows `FOR UPDATE` on every delete path** (a native query, since `PESSIMISTIC_WRITE`
  renders `FOR NO KEY UPDATE`), so that the release's foreign-key check waits. Rejected: it touches
  every delete path, the release fails late as a foreign-key violation, and a deactivation still
  slips through, because an update of the offer takes no key-share lock on the row.
- **Lock the wipe's rows but delete by the scope predicate.** Rejected: a row that entered the scope
  after the lock, with an offer released on it, would still cascade away unaudited.
- **A scope-wide advisory lock that the release takes in shared mode.** Rejected: the release would
  need every scope key its row falls under, including the admin all-scope, for a guarantee the row
  lock already gives.
- **Answer 409 when the row disappeared while the release waited.** Rejected: telling "never
  existed" from "just deleted" needs a second, unlocked read, and either way the row is gone.
