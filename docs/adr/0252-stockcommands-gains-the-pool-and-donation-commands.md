# ADR-0252 — `StockCommands` gains the pool and donation commands

- **Status:** Accepted
- **Date:** 2026-10-13
- **Deciders:** @greluc
- **Related:** spec [`inventory-lager.md`](../specs/inventory-lager.md) (REQ-INV-057, REQ-INV-058,
  REQ-INV-055) · [ADR-0229](0229-exchange-stock-writes-take-an-advisory-lock-per-lot.md) ·
  [ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md) ·
  [`SUPPLY_AND_PURCHASING_PLAN.md`](../archive/SUPPLY_AND_PURCHASING_PLAN.md) (E-11)

## Context

The material supply and purchasing plan moves stock into an org unit's pool twice: when a unit receives
a member's donation and when a unit accepts a purchased delivery. The entity `InventoryItem` lies in
`inventory.internal`, so another module cannot create a row itself, and `inventory.api.StockCommands`
had no call that books into a pool, moves a row into one or earmarks stock for a pledge. Writing a row
from `supply` or `purchasing` would be the unguarded stock write that APPSEC-01 closed.

A donation also changes a row's owner. The generic transfer path would tell the donor about an
"Umbuchung" (REQ-INV-055) and, when a Logistician receives the stock, about a booking by somebody else
(REQ-INV-056), next to the donation notice.

## Decision

`StockCommands` is extended, owned by inventory, `MANDATORY`, under the lot-lock protocol:

- `bookIntoPool` and `transferToPool` put stock into a shared row held by a member and stamped with the
  org unit. `transferToPool` is a command of its own, not a reason flag on the `TRANSFER` book-out, and
  publishes no event, so no owner-change notice fires.
- A pledged donation earmarks stock as a **third allocation kind** (`inventory_item_donation_allocation`)
  beside the job-order and mission slices, pointing at the donation by id with no foreign key into
  another module. `earmarkForDonation` and `releaseDonationEarmark` write it.
- The slice sums are checked per dimension against the row's amount on every lowering write, and a row
  with a donation slice is neither depleted nor moved in full.
- `StockBookingReason` (`DONATION`, `PURCHASE`) names why stock was booked in; it is the audit detail of
  the new commands and not the `StockChangeReason` the offer ratchet records.
- `StockLot.key()` is the lot key the exchange locks under, so `supply` locks the same lots.

## Consequences

- `supply` and `purchasing` write no stock row of their own.
- Every amount-lowering path now also protects a pledge, at the cost of a donation sum per check.
- `StockRow` gains `donationReserved()`; the exchange counts it in its free-stock figure.
- Showing a pledge on a Lager row needs a read SPI of inventory that `supply` implements; it comes with
  the supply module.

## Alternatives considered

- **A reason flag on the `TRANSFER` book-out.** Rejected: every notice path would have to branch on it.
- **A foreign `new InventoryItem()` in `supply`.** Rejected: the entity is no longer reachable, and the
  guard it bypasses is the point.
- **A donation earmark as a job-order slice.** Rejected: a pledge is not an order, and the order's
  delivery and handover logic would have to learn to ignore it.
