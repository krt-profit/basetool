# ADR-0400 — A material handover credits its lines and keeps the requested amount

- **Status:** Accepted
- **Date:** 2026-11-21
- **Deciders:** @greluc
- **Related:** REQ-ORDERS-038 · REQ-ORDERS-053 · REQ-ORDERS-054 · REQ-ORDERS-024 · REQ-ORDERS-017
  · ADR-0121 (item-order edits reconcile lines in place) · migration `V439`

## Context

A `MATERIAL` order's requirement line (`job_order_material`) stored one quantity, `amount`. A
handover overwrote it with what was still open (`JobOrderHandoverService#createHandover`), so the
order no longer knew what had been requested. Every reader therefore read `amount` as the
remaining need, and the order overview could not show "requested" and "how much of it is
delivered".

Three facts were lost with it:

- **Which line a delivery counted toward.** `job_order_handover_item` kept material, quality,
  amount, location and the stolen flag, but not the quality tier it was booked against.
  `MaterialHandoverCalculation` books an entry against the explicitly chosen tier first, then the
  other lines its quality meets by descending floor, so with several tiers the split was gone.
- **Over-delivery.** A handover is capped by the stock row and its order allocation, not by the
  need. What no line could take was booked to nothing (REQ-ORDERS-038 called it "dropped").
- **Line identity.** An order edit cleared and rebuilt every line
  (`JobOrderService#replaceMaterialsWithinTransaction`), so any per-line fact would not survive
  an edit.

The production data on 2026-11-21 (read-only measurement): 39 `MATERIAL` orders, 12 with
handovers, all 12 closed. 35 (order, material) pairs were handed over. 2 had remaining > 0, so
their requested amount is exact. 33 were at remaining 0, where an over-delivery cannot be told
apart. No pair had several tiers, a duplicate line or a removed line, and no order was edited after
a handover.

## Decision

**`amount` is the requested amount; handovers never change it.** Each line also carries
`credited_amount`, the sum of the handover credits booked against it. The remaining need is
derived: `requested − credited`, never below 0 (`JobOrderMaterial#remainingAmount`, and
`m.amount - m.creditedAmount` in JPQL).

**A handover item records its credit per line and its surplus.**
`MaterialHandoverCalculation.Booking` returns, per entry, the per-line takes (`LineCredit`) and the
part beyond every eligible line's remaining need. The handover persists them as the element
collection `job_order_handover_item.credits` (table `job_order_handover_credit`: item, line,
amount) and the column `surplus_amount`. `JobOrderHandoverItem#credit` adds the credit row and
raises the line's `credited_amount` in one step, so both always change together. Over-delivery stays
possible and is now recorded.

**Every reader of the open need uses the remaining amount.** Most of them go through one hub,
`JobOrderMaterialRequirementResolver#requirementsOf`. The others are the handover requirements
query, the stock projection, the need snapshot and the frontend KPI strip. The DTO
`JobOrderMaterialDto` carries `amount` (requested), `handedOverAmount` and `remainingAmount`.

**An order edit reconciles lines in place** (`JobOrderMaterialLines#reconcile`), as ADR-0121 does
for item orders. The payload carries no line id, so a line is matched by (material, tier). A
matched line takes the edited amount as its requested amount. The edit form shows and posts the
requested total. Decided with @greluc:

- A line cannot be requested below its handed-over credit (400
  `error.job_order.material_below_handed_over`).
- A line with credit cannot be removed (400 `error.job_order.material_line_handed_over`). "Nothing
  more needed" means setting it to the handed-over amount.
- A line with credit cannot change its tier, because a tier change is a removal plus an addition.
  The old line stays at least at its credit, and a new line takes the rest.

So no edit changes, moves or orphans a credit, and "credited + surplus = delivered" holds for
every new item. The database backs the first rule with `credited_amount <= amount`.

**Backfill (`V439`, decided with @greluc).** Every existing item is credited in full to one line
of its order and material, with surplus 0, because the old handover lowered that line by it. The
line is the one with the highest floor the item's quality meets. If no floor is met, it is the
line with the lowest floor, since older versions accepted such stock and lowered that line. Ties
go to the smallest line id. Then requested = stored remaining + credit. At remaining 0 this
assumes no over-delivery: the requested amount becomes the handed-over amount, the best available
upper bound. An item whose material has no line on its order any more becomes surplus.

## Consequences

- **Positive.** The requested amount survives handovers and edits, so the overview can show
  "requested" and progress (REQ-ORDERS-017). Each delivery says which tier it counted toward, and
  over-delivery is a recorded fact instead of a loss. Remaining need has one definition, used by
  the claims, promise capacity, demand overview, stock levels, the exchange feed and the
  auto-complete check.
- **Negative / cost.** `credited_amount` duplicates the sum of a line's credit rows. Only the
  handover writes either, through one method, and the migration test checks both. Edits that
  removed a delivered line or moved its tier are now refused. Historic requested amounts at
  remaining 0 are reconstructed: where an over-delivery happened, "requested" shows the delivered
  amount.
- **Neutral.** Lines at remaining 0 stay live claim and coverage buckets, as before. There is still
  no handover deletion or reversal, so credits never shrink. Orders whose material lines a
  material merge left as two lines of the same (material, tier) match the smallest line id. The
  other line is then treated as removed and refused if it carries credit.

## Alternatives considered

- **Keep `amount` as the remaining need and add a `requested_amount` column.** Fewer readers to
  touch, but two stored figures that must agree for every line, and the per-line credit and
  surplus would still be missing.
- **Derive the credited sum on read** (`@Formula` subselect, or a join in every demand query).
  There is no redundant column, but a formula is stale inside the handover's own transaction once
  credits are inserted, and every demand query repeats the aggregation.
- **Reconstruct the requested amount on read** from the handover items. This is ambiguous for
  several tiers and for over-delivery, and wrong after an edit.
- **Let an edit lower a line below its credit and book the excess as surplus.** This rewrites past
  handovers after the fact. @greluc chose to refuse it.
