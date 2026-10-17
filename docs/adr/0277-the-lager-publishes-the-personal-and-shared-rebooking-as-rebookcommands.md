# ADR-0277 — The Lager publishes the personal/shared rebooking as `RebookCommands`

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec [`inventory-lager.md`](../specs/inventory-lager.md) (REQ-INV-007, REQ-INV-060) ·
  [ADR-0229](0229-exchange-stock-writes-take-an-advisory-lock-per-lot.md) ·
  [ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md) ·
  [ADR-0252](0252-stockcommands-gains-the-pool-and-donation-commands.md) · issues #2693, #2694

## Context

A member rebooks their own stock between personal and a unit's pool in the web (Umbuchung,
REQ-INV-007). The exchange is to offer the same to a connected application (#2693), but the
rebooking lived in `InventoryCheckoutService`, an internal of the inventory module, and was addressed
by one row id. A client addresses stock by lot, not by row, and reports a refusal per op instead of
failing the whole change set.

## Decision

- `inventory.api.RebookCommands` is a published command of its own, beside `StockCommands` and
  `PoolEntryCommands`. Its one call, `rebook`, moves an amount of a member's lot between personal and
  one named pool, in either direction, `MANDATORY`, under the lot-lock protocol of ADR-0229.
- It picks the rows itself (REQ-INV-060): into the pool, the personal rows of the target unit, then
  those without a unit, then the rest, each oldest first; back to personal, only the member's own
  shared rows of that pool.
- It answers a refusal as a value (`RebookRefusal`), like `PoolEntryCommands`, and reports every row
  it created, changed or removed, so a caller can journal the change for undo.
- Each moved row goes through the web's own row rebooking (`InventoryCheckoutService#rebookRow`),
  which keeps holder, lot and stolen marker, lowers or deletes the source and records
  `INVENTORY_ITEM_DEPERSONALIZED` / `INVENTORY_ITEM_PERSONALIZED`. The audit takes the client from
  the request's authentication, as every audit row does.
- The command never chooses a pool and never books a shared row without a unit. The web keeps both
  as its own extras on top of the row rebooking.

## Consequences

- The exchange can offer `share` / `unshare` without reaching into inventory internals.
- The single web rebooking and the command share one row primitive, so their bookings and audit
  cannot drift apart.
- The bulk rebooking (REQ-INV-036) keeps its own whole-row move: it carries the earmarks and the note
  onto the moved row, which the single rebooking does not, so routing it through the same primitive
  would change what it books.

## Alternatives considered

- **Extend `StockCommands`.** Rejected: its calls take row ids under the caller's selection, while a
  rebooking chooses its rows and answers refusals as values, as `PoolEntryCommands` does.
- **Let the exchange select rows and call a row-level rebook.** Rejected: the row order and the
  earmark and offer rules are the Lager's, and a second copy in the exchange would drift.
