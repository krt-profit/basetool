# ADR-0363 — A Lager row is locked in one statement without a join

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** @greluc
- **Related:** spec [`inventory-lager.md`](../specs/inventory-lager.md) (REQ-INV-071, REQ-INV-044) ·
  [`supply.md`](../specs/supply.md) (REQ-SUP-009) ·
  [`orders-item-production.md`](../specs/orders-item-production.md) · amends
  [ADR-0362](0362-an-offer-write-and-a-lager-delete-take-turns-on-the-row-lock.md)

## Context

The Lager locked one row by id through two queries with `@Lock(PESSIMISTIC_WRITE)` and an
`@EntityGraph` of the row's associations: `InventoryItemRepository#findByIdForUpdate` (the bulk
check-out, the org-stock entry commands and `StockCommands.lockRow`, which the material handover,
the production consumption and the donation pledge and receipt use) and `#findByIdForRebook` (the
bulk rebooking, the stolen marking, the org-unit change and the pool change).

The graph's nullable associations are outer joins. PostgreSQL cannot lock the nullable side of an
outer join, so Hibernate 7.4 runs such a query as follow-on locking: a plain `SELECT` with the joins,
then a second statement that locks the row and copies its columns into the loaded entity. Measured
on PostgreSQL 18 with two transactions:

- A row another transaction deleted while the lock statement waited came back as no row, and
  Hibernate threw `AssertionFailure: Expecting results`. The client got a 500 instead of the
  not-found the callers answer for an unknown row.
- A row the persistence context already held was not locked at all: Hibernate registers only rows
  it loads in that query for the follow-on lock. The pledge and the receipt read their row before
  they lock it, so their `lockRow` took no row lock.
- A held row whose newer version another transaction had committed raised the optimistic-lock
  conflict (409) in the plain `SELECT`, so the follow-on lock never refreshed a held row.

A query without joins is locked in its own statement. That statement waits for the row, re-reads
it after the wait, returns no row when it was deleted, and runs the same version check on a held
row. ADR-0362 already locks the Materialbörse offer writes this way (`lockById`).

## Decision

1. **Every single-row Lager lock is `InventoryRowLockRepository#lockById`**: one
   `SELECT … FOR NO KEY UPDATE` on `inventory_item`, no join. `findByIdForUpdate` and
   `findByIdForRebook` are removed, and `StockCommands.lockRow` delegates to `lockById`. The row's
   associations load on first use inside the transaction.
2. **A row deleted while the lock waited reads as absent.** Each write answers what it answers for
   an unknown row: `404 Inventory item not found`, `Stock row not found` for a pledge, the pool
   refusal `NOT_PERMITTED` for an org-stock entry command.
3. **A receipt whose source row is gone books into the pool.** A donation receipt whose earmarked
   row was deleted while its lock waited books the received amount with `bookIntoPool`, as it does
   for a source row that was gone before the receipt started (REQ-SUP-009).
4. **A held row is locked and version-checked.** A write that read its row before it locks it
   holds the lock afterwards. When a newer version was committed meanwhile, the lock raises the
   optimistic-lock conflict, as the follow-on path did.
5. **No pessimistic lock of a repository takes the follow-on path.** A `@Lock(PESSIMISTIC_*)`
   query carries no `@EntityGraph`, no outer join, no `DISTINCT`, `GROUP BY`, `HAVING` or
   aggregate, and reads a single-table entity. `PessimisticLockShapeTest` checks every repository.

## Consequences

- The bulk check-out, the bulk rebooking, the stolen marking, the org-unit and pool changes, the
  handover, the production consumption and the pledge answer their not-found when a concurrent
  delete wins. `InventoryRowLockRaceIntegrationTest`, `JobOrderStockLockRaceIntegrationTest` and
  `SupplyDonationStockLockRaceIntegrationTest` drive each with two transactions; each fails on the
  follow-on queries.
- The pledge and the receipt now hold their row's lock from `lockRow` on. A concurrent writer of
  that row waits for them instead of failing at its own flush.
- One locking statement replaces the plain read plus the lock statement. The material, owner,
  location, game item and org unit load lazily; `default_batch_fetch_size` batches them across the
  rows of a bulk action.
- The org-stock entry commands lock their rows in `PoolEntryCommands.lockLots` first, so their
  `lockById` finds the row already locked and never waits.
- A later lock query with an association graph fails `PessimisticLockShapeTest`; it loads the
  associations after the lock instead.

## Alternatives considered

- **Lock join-free first, then run the graphed lock query.** Rejected: the second statement adds
  nothing. It neither refreshes a held row nor locks one, and it costs an extra query per row.
- **Catch the `AssertionFailure` and answer empty.** Rejected: it is Hibernate's internal
  assertion, the session then holds an entity whose row is gone, and a held row would still take no
  lock.
- **`EntityManager.refresh` with a lock mode after a plain read.** Rejected: a refresh joins the
  collections that cascade `REFRESH` and runs as follow-on locking again, and it fails on a child
  row deleted concurrently.
- **Disallow follow-on locking globally** (`hibernate.query.followOnStrategy`). Rejected: it is a
  per-query hint, and as a default it would turn every follow-on query anywhere into a runtime
  error rather than a failing test.
