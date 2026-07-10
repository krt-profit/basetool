# ADR-0086 — Materialbörse: offered amount is an owner-chosen partial quantity, not live-read

- **Status:** Accepted
- **Date:** 2026-07-09
- **Deciders:** @greluc
- **Related:** ADR-0082 (amends decision D1) · spec REQ-MARKET-002/007/008/009
  (`docs/specs/materialboerse.md`) · REQ-AUDIT-001 · issue #1183

## Context

ADR-0082 modelled the offer as a thin signal-only overlay whose **material, quality and amount** are
read **live** from the linked `InventoryItem` (decision D1) — the offer stored no quantity of its
own. In practice a member often wants to trade only a **part** of a stock row (e.g. offer 120 of 500
SCU) while keeping the rest; live-reading the whole row's amount makes that impossible (#1183). The
amount therefore has to become a value the owner sets, decoupled from the row's total stock.

Quality is different: it is a property of the material row, not a splittable quantity — there is no
"offer a part of the quality". So only the amount needs to change; quality stays live.

## Decision

The offer stores an **`offeredAmount`** (SCU) — an owner-chosen quantity that may be the whole row or
only a part of it. This **amends ADR-0082 decision D1**: material and quality stay live-read from the
`InventoryItem`; the amount is now a stored offer column.

- **Validation.** `offeredAmount` must be **positive and at most the item's current stock**, checked
  in the service against the live item at every release **and** edit (`BadRequestException` → 400),
  and rounded to three-decimal SCU precision. The `@Positive`/`@NotNull` DTO constraints cover the
  null/non-positive case; the ceiling is a cross-field rule `@Valid` cannot express.
- **Clamped to live stock on read (partial book-out).** The board must never advertise more than is
  in stock, so the **effective** offered quantity served, filtered and sorted is `LEAST(offeredAmount,
  item.amount)` — the stated amount clamped to the item's current stock. As the row is partially
  booked out the offer shrinks with it; editing the offer persists the clamped value. `offeredAmount`
  remains the owner's stated intent (the upper bound), never itself mutated by a book-out. This
  **clamp-on-read** is deliberately chosen over reconciling `offeredAmount` from every inventory write
  path: it cannot fall out of sync (there is nothing to sync — the item's amount is the single source
  of truth), and it sidesteps the optimistic-locking landmines of mutating the offer inside another
  aggregate's booking/handover/refinery transaction (CLAUDE.md concurrency rules, the `@Version` 409
  traps).
- **Full depletion deletes the offer (no lingering junk).** A row that is booked out to 0 is
  **deleted** by every inventory book-out path (`InventoryCheckoutService` book-out/transfer,
  `JobOrderHandoverService` — all `inventoryItemRepository.delete(item)` at `remaining <= EPSILON`),
  and the offer's `ON DELETE CASCADE` (V210) removes it with the row. A row can never be *edited* to 0
  either (`@ValidQuantityAmount` enforces `amount > 0`). So no zero-stock `ACTIVE` offer persists —
  the board needs no "hide depleted" guard and the tab counts need no stock filter; every `ACTIVE`
  offer is a live, on-board one. The clamp above therefore only ever narrows a *partially* booked-out
  offer (stock still `> 0`), never produces a `0 SCU` phantom.
- **Board filter and sort use the effective amount.** The "min. Menge" filter and the "Menge ↓" sort
  operate on `LEAST(offeredAmount, item.amount)` (the sort via `JpaSort.unsafe`), matching the clamped
  display.
- **Editable after release.** The existing owner-only edit ("Angebot bearbeiten", version-guarded)
  now edits the offered amount alongside the remark; a raised amount is re-validated against the
  item's current stock. The item's total stock is exposed to the **owner only** as
  `availableAmount` on the DTO (null for everyone else) so the edit dialog can bound the input
  without leaking how much stock the anbieter holds beyond what is offered.
- **Schema.** `V212` adds the `offered_amount` column and backfills every existing offer with its
  item's current amount (so pre-migration offers keep meaning "the whole row"), then enforces
  `NOT NULL`.
- **Audit.** `MARKET_OFFER_RELEASED` records the offered amount plus the item's stock; the offer-edit
  event (`MARKET_REMARK_UPDATED`, kept for historical continuity) records the new offered amount — no
  new event type, no PII.

## Consequences

- A member can offer a slice of a row and keep the rest; the board's "Menge" is what is on offer, not
  the anbieter's total holdings, and it is always capped at what is actually in stock.
- The board keeps ADR-0082's truthfulness property: as stock is partially booked out the advertised
  quantity shrinks with no write to the offer, across **every** inventory write path — present or
  future — because the clamp is derived at read time, not maintained by a hook. Full depletion is
  cleaned up by the existing delete-on-depletion + `ON DELETE CASCADE`, so no dead offer accumulates.
- The stored `offeredAmount` can exceed the current stock (the stated upper bound); if stock later
  returns (a partial book-out that is then restocked), the offer re-expands up to that bound.
  Accepted — the owner can lower it any time via "Angebot bearbeiten", and editing persists the clamp.
- Exposing `availableAmount` only to the owner keeps the edit UX bounded without disclosing the
  anbieter's spare stock to other members.
- The board query gains a `LEAST(...)` in its `WHERE`/`ORDER BY` (the sort via `JpaSort.unsafe`); the
  count queries are unchanged (no stock guard needed). The one-active-offer-per-item invariant (V210,
  and its `ON DELETE CASCADE`) and the live-read of quality are unchanged.

## Alternatives considered

- **Keep amount fully live-read (status quo).** Rejected — it is exactly what prevents offering a
  part of a row (#1183).
- **Store `offeredAmount` and leave it un-clamped on read (curate only via edit).** Rejected — the
  board would advertise more than is in stock after a book-out until the owner noticed and edited,
  breaking the "can't offer more than in stock" invariant (#1183 follow-up).
- **Persistently reconcile `offeredAmount` from every inventory write path (or a Hibernate flush
  listener).** Rejected — inventory amount is mutated by ~8 services (book-out, handover, refinery,
  transfer, …); writing the offer inside those transactions risks the `@Version` 409 traps the
  CLAUDE.md concurrency rules warn about, and a per-path hook is exactly the kind of "mirror point"
  that silently rots when a new write path is added. Clamp-on-read has nothing to keep in sync.
- **A scheduled reconciliation job.** Rejected — adds a scheduled task (its own metrics/alerts per
  REQ-OBS) and a visible lag window where the board over-advertises; clamp-on-read is immediate.
- **Expose the item's total stock to everyone.** Rejected — it leaks how much the anbieter is holding
  back; `availableAmount` is owner-only.
- **A separate "edit amount" endpoint/event.** Rejected — the offered amount rides the existing
  owner-only edit and its audit event; a second surface would duplicate the optimistic-lock plumbing.

