# ADR-0098 — Inventory associations as to-many quantity splits (Variante C / Modell G)

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** Repository owner (@greluc)
- **Related:** amends [ADR-0003](0003-inventory-append-only-group-on-read.md) &
  [ADR-0097](0097-inventory-piece-scu-stock-merge.md) · spec
  [`inventory-lager.md`](../specs/inventory-lager.md) `REQ-INV-027` ·
  [`audit.md`](../specs/audit.md) `REQ-AUDIT-001` · issue #1182

## Context

An `InventoryItem` carried its job-order and mission association as **one scalar foreign-key column
each** (`job_order_id`, `mission_id`), and those two columns were part of the Lager **stack
identity** (the group-on-read `GROUP BY` key, ADR-0003). A per-entry boolean `delivered` marked
whether the earmarked stock had been handed over.

This could not express what operators needed: a single contribution is routinely destined for
**several** job orders and/or **several** missions, in different amounts — "of these 100 SCU, 60 are
for order A and 40 for order B". The scalar model forced one order and one mission per row and made
`delivered` ambiguous once a row served more than one order. Splitting a contribution meant creating
several physical rows, defeating the append-only provenance the Lager is built on.

## Decision

We will model an inventory entry's job-order and mission associations as **two independent to-many
quantity splits** ("Modell G"), replacing the scalar columns.

- Two allocation tables — `inventory_item_job_order_allocation` and
  `inventory_item_mission_allocation` (V217) — each row earmarking an `amount` of the entry to one
  target, `UNIQUE(inventory_item_id, target_id)` per dimension, both FKs `ON DELETE CASCADE`. An
  entry may hold several rows per dimension; the two dimensions are split independently.
- The invariant **R5**: per dimension, Σ(slice amounts) ≤ the entry's amount. Over-allocation (and
  any amount-lowering write that would breach it) is rejected with **HTTP 422**
  (`OverAllocationException`); amounts are never silently shrunk. The unallocated remainder is a
  first-class "frei" state, not an error.
- The scalar `job_order_id` / `mission_id` / `delivered` columns are **dropped** (V218), and the
  group-on-read **stack key narrows to pure physical identity** (owner · material · location ·
  quality · personal · owning org unit). The earmarks move down to the individual entry as amount
  chips. All reads — stacking, filters, and job-order / mission fulfilment sums — go through the
  allocation tables, so an order is credited only its allocated share of a split entry.
- `delivered` moves onto the **job-order allocation** ("Variante A") — per-(entry, order), so one
  entry can be delivered for one order and open for another.
- The write-time stock merge (ADR-0097 / REQ-INV-026) folds on the narrowed physical key and
  **unions** the folded rows' allocations into the survivor (summed per target, delivered
  OR-combined); R5 holds because the survivor's amount already absorbed the folded amounts (**R1**).
- A `SELL` of mission-earmarked stock books **seller-chosen per-mission income attributions** — one
  squadron-`INCOME` `MissionFinanceEntry` per chosen mission (Σ ≤ the sale proceeds, remainder
  personal), only for missions the seller participates in.
- Assignment writes go through dedicated per-allocation endpoints (`POST`/`PATCH`/`DELETE
  /api/v1/inventory/{id}/allocation`) and, for check-in, per-dimension allocation lists on the create
  payload (**R4**). All reuse the existing owner-scoped inventory-edit gate — **no new role** — and
  are audited via `INVENTORY_ALLOCATION_ADDED` / `_CHANGED` / `_REMOVED`.

## Consequences

- Fulfilment is more correct: an order sees exactly the stock earmarked to it, and a contribution
  can serve several orders/missions without duplicating rows.
- The entry's `@Version` is the single optimistic-lock token for its allocations (inverse-side slice
  writes force-increment it), so the client keeps echoing one version.
- The change is a schema reshape rolled out under a **soak** (V217 add + backfill + dual-write, then
  V218 drop) so no read/write path is ever left half-migrated. The scalar-drop is a single atomic
  step because the mapper and the merge key both read the scalar until the drop.
- Prior ADRs are amended, not superseded: ADR-0003's append-only + group-on-read model stands, only
  its stack key narrows; ADR-0097's merge stands, only its key narrows and its fold gains the
  allocation union.
- The DTO keeps soak-compatible first-allocation scalar fields (`jobOrderId` / `missionId` / …) for
  now; retiring them from the wire is a later cleanup.

## Alternatives considered

- **Keep one scalar, allow only the mission (or only the order) to be many.** Rejected — operators
  split both dimensions; a half-measure would not remove the row-duplication workaround.
- **Model the split as N physical rows sharing a stack (no allocation tables).** Rejected — it
  re-introduces the provenance loss append-only was built to avoid and makes "amount for this order"
  a query over sibling rows rather than a stored fact.
- **Auto-proportional SELL income split across missions.** Rejected in favour of a seller-chosen
  split — the operator decides which missions earn how much (and only missions they participate in
  are creditable), which the automatic split could not express.

