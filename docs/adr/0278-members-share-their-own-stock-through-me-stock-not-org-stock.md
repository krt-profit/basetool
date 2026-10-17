# ADR-0278 — Members share their own stock through `/me/stock`, not `/org-stock`

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-016,
  REQ-XCH-022, REQ-XCH-045) · [ADR-0229](0229-exchange-stock-writes-take-an-advisory-lock-per-lot.md) ·
  [ADR-0230](0230-an-exchange-stock-lot-is-everything-the-member-holds-at-its-quality.md) ·
  [ADR-0257](0257-logisticians-read-and-write-their-units-shared-stock-through-the-exchange.md) ·
  [ADR-0277](0277-the-lager-publishes-the-personal-and-shared-rebooking-as-rebookcommands.md) ·
  issues #2693, #2696

## Context

In the web a member rebooks their own warehouse stock from personal into the pool of a unit they
belong to (REQ-INV-007). It is an owner action and needs no role. A connected application could
not do the same: `/me/stock` only knew `set-quantity`, whose rises are always personal stock
without a unit. `/org-stock` writes are addressed by pool entry, are for the pool's Logisticians
only and never touch personal rows (REQ-XCH-045). Members of VerseKit asked for "store in unit
storage", and their unit's items ended up in their personal stock.

## Decision

- The member's own stock change set, `POST /exchange/v1/me/stock/changes`, gains a `share` op
  beside `set-quantity`. `/org-stock` stays as it is.
- A share is addressed by lot, like `set-quantity`, and names `orgUnit` `{id}`. There is no
  automatic choice of a sole membership and no shared row without a unit.
- The target must be one of the member's own direct memberships, the set `GET /me/org-units`
  returns. An admin role or a leadership seat adds nothing.
- `expectedQuantity` is the lot's `split.personal` the client last saw. It is compared under the
  lot lock of ADR-0229 (`VERSION_CONFLICT`), and a `quantity` above it is refused
  (`QUANTITY_ABOVE_EXPECTED`).
- The rows, the row order and the earmark and offer refusals are the Lager's `RebookCommands`
  (ADR-0277). The exchange maps its refusals to per-op reasons.
- A share is audited by the Lager's own `INVENTORY_ITEM_DEPERSONALIZED` under the client, journaled
  as `STOCK_SHARE` under the lot's key, and never counts as a removal for the mass-change guard.
  An undo moves the shared amount back to personal before it sets the lot's quantity back.

## Consequences

- A client offers "give to unit storage" with the scope it already holds, `exchange.stock.write`.
- The lot's `key` and total never change on a share; the lot reaches the feed with its new
  `split`.
- An undo cannot take back a share whose pool stock is gone: the lot is skipped as `GONE`.
- Taking stock back out of a pool (`unshare`) is a separate op on the same change set.

## Alternatives considered

- **A `share` op on `/org-stock`.** Rejected: that resource is addressed by pool and limited to
  Logisticians, and opening it to every member for one op would blur who may write a pool.
- **Book `set-quantity` rises into the member's sole unit.** Rejected: the client would not know
  where the stock lands, and a member of several units could not choose.
