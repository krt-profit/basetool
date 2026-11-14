# ADR-0382 — Patch effects are excepted from the invalid-responsibility gate

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** @greluc
- **Related:** [patch-change spec](../specs/patch-change.md) (REQ-PATCH-012 … REQ-PATCH-014) ·
  [orders-core spec](../specs/orders-core.md) (REQ-ORDERS-055) ·
  [ADR-0356](0356-job-orders-use-profit-hierarchy-responsibility-and-participation.md) ·
  [ADR-0360](0360-material-promises-track-uncovered-demand-and-explicit-provision.md) ·
  [ADR-0380](0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md) ·
  decisions #3249, #3251 (resolution and amendment)

## Context

REQ-ORDERS-055 lets a job order whose responsible unit no longer satisfies the Profit boundary be
read and transferred, nothing else. Every Inventory write that changes a row earmarked for such an
order is refused with `orders.responsibility.invalid` (HTTP 400): the Lager's order-write planning
asks the job-order module to validate every affected order, all or nothing.

A patch change's wipe removes every member's pre-patch stock in one transaction. One earmark for
one order with invalid responsibility, anywhere in the squadron, would refuse the whole patch
change, although nobody writes the order: the game removed the stock. An admin could only trigger
after every such order had been transferred, which the admin may not be able to do.

## Decision

1. **A patch effect that removes stock is a game fact, not an order write.** The job-order module
   offers the Lager a second pair of checks for it, `beforeGameFactStockLoss` and
   `assertGameFactMaterialLoss` on `inventory.api.OrderLinkedStockWriteObserver`: they take the
   shared hierarchy lock, require the orders to exist and require the material scopes to be held,
   but skip the responsibility check.
2. **Only the patch wipe uses them.** `InventoryOrderWrites.duringGameFactLoss` routes the
   checks of the running stock plan to the game-fact pair for the duration of the wipe's lock and
   delete; every other stock write keeps the ordinary checks.
3. **Every lock stays.** The wipe takes the shared hierarchy lock first, then the material scopes,
   the lots and the rows in the common resource order (ADR-0229, ADR-0360), so it never races an
   organisational change or another stock write.
4. **The loss is genuine** (ADR-0360): credited contribution stays, uncovered demand rises with
   the earmarks that go with the rows, and no ended promise revives.

The currency wipe of a later slice is excepted the same way for the aUEC holdings it ends.

## Consequences

- A patch change cannot be blocked by an order nobody can transfer at the moment; the order keeps
  its history and its invalid responsibility, and the job-order trail records one summary event.
- The Lager's planning carries one more transaction-scoped flag; a check routed wrongly would
  either let an ordinary write past an invalid order or refuse a wipe, which the trigger's
  integration test pins with an order of invalid responsibility.

## Alternatives considered

- **Refuse the patch change until every order is valid.** Correct by the letter of REQ-ORDERS-055,
  but it lets one stale order hold the whole squadron's patch hostage.
- **Skip the earmarked rows.** The game removed the stock; the Lager would keep stock that no longer
  exists and the orders would count it as covered.
- **Skip the hierarchy lock as well.** A concurrent organisational change could then judge the
  orders on a half-wiped stock state.
