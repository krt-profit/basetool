# ADR-0320 — A need shows its linked order's stock, and only donations and purchases cover it

- **Status:** Accepted
- **Date:** 2026-10-31
- **Deciders:** @greluc
- **Related:** REQ-SUP-003 · REQ-SUP-005 · REQ-SUP-023 · REQ-SUP-024 · REQ-ORDERS-034 · spec
  [`supply.md`](../specs/supply.md)

## Context

A unit tracks material in two places. The material-demand overview (REQ-ORDERS-034) counts the stock
booked onto the unit's open job orders. A need under „Bedarf & Spenden" counts only what received
donations and accepted purchase deliveries cover (REQ-SUP-005). When members move stock onto an
order with an ordinary Lager transfer, the overview shows the order covered while the need for the
same material still reads „0 von 15 SCU". Members read this as the two pages being out of sync.

A need could already reference a job order (`supply_need.job_order_id`, REQ-SUP-003), but the need
form never offered one and sent no order on every edit, so no need was ever linked and an edit
unlinked one set through the API.

## Decision

1. **The need form offers the unit's job orders.** The picker lists the open and in-progress orders
   of the units the caller manages, filtered to the selected unit. A newly linked order must be open
   or in progress and belong to the need's unit; a link to an order that has closed since survives an
   edit unchanged.
2. **A linked need shows the order's coverage as a hint.** For the unit's managers, an open or paused
   need shows how much order-linked stock the linked order has for the need's material and tier — the
   figure the overview shows for that order — and an „Auftrag gedeckt" chip when it reaches the
   order's requirement. `joborder.api.JobOrderMaterialCoverage` computes it with the overview's
   allocator, batched per page.
3. **The coverage never counts toward the need.** The need's progress, its `FULFILLED` transition,
   the donation statistics and the audit trail stay driven by donations and purchases only.
4. **The unit is told once when the linked order is covered.** A timed producer raises
   `SUPPLY_NEED_ORDER_COVERED` to the unit's lead and Logisticians while the need is still open, marks
   the need so it fires once per link, and the need's fulfilment or withdrawal clears the notice
   (REQ-SUP-024). The notice asks the unit to act; it does not act on the need.

## Consequences

- A unit sees on the need itself that the order is already covered and can lower or withdraw the
  need; nothing changes the need behind its back.
- The hint is a read-time projection: it refreshes with the need list, not when someone books stock
  onto the order elsewhere.
- Members who are not managers of the unit see no order data on a need, so the order's visibility
  scope (REQ-ORG-*) is not widened.

## Alternatives considered

- **Credit the order's stock to the need automatically.** The need would turn `FULFILLED` without a
  donation, the donation statistics that measure the pilot's abort criterion would be wrong, and a
  stock move off the order would have to un-credit the need.
- **Match by unit, material and tier instead of an explicit link.** Works for needs nobody linked,
  but attaches a hint to needs that have nothing to do with an order.
