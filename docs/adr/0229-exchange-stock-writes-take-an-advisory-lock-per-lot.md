# ADR-0229 — Exchange stock writes take an advisory lock per lot, in key order

- **Status:** Accepted — owner decision 2026-09-28 (option (a) of the load test's finding 7);
  amended 2026-10-02 (the protocol moves into inventory, see below).
- **Date:** 2026-09-28
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-016`,
  `REQ-XCH-022`, `REQ-XCH-034`) · [ADR-0218](0218-exchange-sync-semantics.md) (sync semantics,
  journal and undo) · [ADR-0227](0227-an-admin-undoes-one-client-for-every-member-in-the-background.md)
  (the admin bulk undo) · issue #2092 (the go-live load test)

## Context

A client's stock change set sets lots to quantities against the quantity the client last saw
(`expectedQuantity`), and answers `VERSION_CONFLICT` when the lot holds something else. The check
ran under row locks: `InventoryItemRepository.lockPersonalMaterialLot` / `lockPersonalItemLot`
(`SELECT … FOR NO KEY UPDATE`) over the member's personal rows of the lot.

The go-live load test of 2026-09-28 and the work that followed found two faults in that:

1. **Deadlocks.** The rows were locked in the order the ops arrived, so two sets of one member naming
   the same lots in different orders deadlocked (`40P01`, 230 in 90 s under load).
2. **A second rise of the same lot also applies.** A rise books SCU stock in as a *new row*. A set
   that waited on the lot's row locks gets back, when they are released, only the rows its
   statement's snapshot saw (PostgreSQL, `READ COMMITTED`), so it misses the row the first set
   booked in, compares against the old total, and applies too: two installations sending `5 → 6`
   left the lot at 7. A lot with no rows has nothing to lock at all, so two book-ins into an empty
   lot never wait for each other. The undo had the same gap: it checked that nothing changed an
   entry after the client's write, and only then locked the lot's rows, so a write committing in
   between was overwritten.

Row locks cannot express "this lot", because a lot is a set of rows that grows and may be empty.

## Decision

1. **Every exchange stock write takes a transaction-scoped PostgreSQL advisory lock per lot**
   (`pg_advisory_xact_lock`) before it reads any of the lot's rows, through
   `ExchangeStockWriteService.lockLots`. It is released at commit or rollback, like a row lock, and
   exists whether or not the lot has rows. A writer that waited for it starts its row read after
   the holder committed, so it sees the holder's rows — including the new ones.
2. **The lock key is a stable 64-bit value derived from the member and the lot key**: the first
   eight bytes of SHA-256 over `exchange-stock-lot|<member>|<lot key>`, the lot key being the one the
   change feed records. The prefix keeps it apart from any other advisory lock the database may
   carry later.
3. **A set takes all its locks up front, in ascending order of the lock key**, then locks each lot's
   rows in the order of the lot keys (each lot's rows in id order), and only then decides its ops in
   their own order. One total order over all locks means two sets can never wait on each other in a
   cycle.
4. **A collision only over-serialises.** Two different lots whose keys hash alike share one lock:
   their writers wait for each other although they need not. Mutual exclusion and the order are
   unaffected, because the order is over the lock keys themselves, not over the lot keys. At 64 bits
   a collision within one member's lots is practically never; it costs a wait, never a wrong result.
5. **The undo takes the same locks.** `ExchangeUndoService` takes the advisory locks of every stock
   lot it will restore, in the same order, before it checks whether an entry was changed afterwards,
   and restores the entries in the order of their keys. The member's undo and the admin's bulk undo
   share that path.

## Consequences

- Concurrent sets of one member over the same lots neither deadlock nor both apply: the later one
  finds the earlier one's result and answers `VERSION_CONFLICT` (REQ-XCH-016's acceptance), for an
  empty lot too. An undo that meets a running write waits and then leaves the lot alone as
  `CHANGED_AFTERWARDS`.
- Sets of one member over the same lots serialise for their whole transaction — which they already
  did on the rows. Sets of different members, or over different lots, never meet.
- The lock covers the exchange's writers only. The Lager's web and app paths do not take it; a web
  book-in racing an exchange write of the same lot keeps the row-lock behaviour. Extending the lock to
  them is a separate decision.
- The first advisory lock in the codebase. `pg_locks` shows it as `locktype = 'advisory'`, and a
  waiter shows `wait_event = 'advisory'` in `pg_stat_activity`.

## Alternatives considered

- **Sorting the row locks alone** — fixes the deadlock, not the missing new row or the empty lot.
- **A lock per member** (the `app_user` row, or one advisory lock per member) — simpler, but
  serialises all of a member's stock sets, and a lock on `app_user` also blocks every update of the
  member's user row (roster sync, profile) for the length of a 500-op set.
- **`SERIALIZABLE` for the stock write** — detects the conflict, but answers it with a
  serialisation failure the gateway would have to retry, and raises the failure rate of every
  concurrent write in the transaction, not just the stock lots.
- **Re-reading the rows after the lock** — covers rows booked in by the writer that was waited for,
  but not the empty lot, where nothing is waited for.

## Amendment — 2026-10-02: the lot-lock protocol moves into inventory

Domain modularisation plan §7.5 (Phase 3, the inventory command API);
[ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md),
[ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md). Lots are
inventory's aggregate, so the protocol becomes inventory's: with `inventory.api.StockCommands`, the
advisory lock per lot, its key derivation, the ascending key order and the row-lock order move from
`ExchangeStockWriteService.lockLots` into the inventory module, and the exchange and the undo call it
through the command API in their own transaction (`MANDATORY`). The protocol itself — key, prefix,
order, transaction scope — is unchanged, so the move changes no lock and no deadlock property; the
ADR-0229 load-test cases and the inventory concurrency tests guard it. Whether the Lager's web and
app paths also take the lock stays the separate decision the consequences name.
