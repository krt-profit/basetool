# ADR-0108 — Materialbörse stock-backed item offers (released from item stock)

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** @greluc
- **Related:** ADR-0082 (offer model) · ADR-0086 (partial offers, clamp-on-read + ratchet) ·
  **partially supersedes ADR-0087** (the "item offer quantity is a free-stated, non-live claim"
  decision) · ADR-0101 (game items as catalog-discriminated Lager stock rows) · spec REQ-MARKET-014
  (`docs/specs/materialboerse.md`), amends REQ-MARKET-012/013 · design `docs/DESIGN_ITEM_INVENTORY.md`
  §8 · REQ-AUDIT-001 · REQ-FE-010

## Context

ADR-0087 introduced Materialbörse **item offers** as *free-stated*: because a craftable item had no
Lager row, the offering player **stated** the quantity, with no backing stock, no clamp and (edit)
remark-only. That was the only option at the time — there was nothing to read the quantity from.

ADR-0101 changed the premise: game items are now trackable as **Lager item-stock rows**
(`InventoryItem.gameItem`, REQ-INV-029). Once item stock exists, an item offer can be released **from
stock, analogous to a material offer** (ADR-0082/0086), instead of only as a free-stated listing —
with the same live-backed quantity guarantees material offers already have (clamp-on-read, ratchet on
decrement, one-active-offer-per-row). This **partially supersedes** ADR-0087's decision that an item
offer's quantity is an unbacked point-in-time claim: it stays true for a *free-stated* offer, but a
*stock-backed* offer's quantity is now bound to physical stock.

The identity question ADR-0087 answered stands unchanged: a stock row keys on a `GameItem`, but an
offer keys on the blueprint `product_key` (a product is not 1:1 with a `game_item` FK), so the offer
model keeps `product_key` as the identity — the `inventory_item_id` is the *physical* link, not the
identity.

## Decision

Extend the `MaterialExchangeOffer` `ITEM` kind into **two flavours** within the one aggregate, rather
than adding a third kind or a sibling entity:

- **Free-stated item offer** — unchanged from ADR-0087: no `inventory_item_id` (craft-on-demand), a
  stated `item_quantity`, no clamp, deliberately multi-listable (REQ-MARKET-012).
- **Stock-backed item offer** — released from a game-item Lager row: `inventory_item_id` **set** to
  that row. Its `item_quantity` is validated `<=` the row's current whole-unit stock at release
  **and** edit (the item sibling of the material `requireOfferableAmount`), clamped to stock on read
  (ADR-0086 mirror) and **ratcheted down** on every stock decrement (REQ-MARKET-013/014). It still
  carries no `offered_amount` (that stays MATERIAL-only) and no quality.

Concrete choices:

- **Relaxed CHECK (V221), not a new column.** The V213 exactly-one-branch `CHECK` forbade an `ITEM`
  offer from carrying `inventory_item_id`. V221 drops **only** that half of the `ITEM` branch; the
  `MATERIAL` branch and `offered_amount IS NULL` on `ITEM` are unchanged. Loosening a `CHECK` is a
  two-phase-safe migration (no existing row can newly fail, old free-stated-only code keeps passing).
- **Identity bridge = derive `product_key` from the row's game item.** A stock-backed release resolves
  the game item to its blueprint product (`BlueprintProductService.resolveByGameItem` → the first
  active blueprint's normalized `outputName`, then `resolveByProductKey`), snapshotting the **same**
  `product_key`/`item_name` a free-stated offer of that item would carry (ADR-0087 identity unchanged).
  The catalog predicate (REQ-INV-029, `findItemsWithActiveBlueprint`) guarantees a stocked game item
  resolves; one that no longer does is rejected (400).
- **One-active-offer-per-row now spans item stock.** The V210 partial-unique index
  `(inventory_item_id) WHERE status = 'ACTIVE'` naturally governs stock-backed item offers too (their
  FK is non-null) — consistent, since the row *is* the physical stock, so re-releasing re-activates it.
  Free-stated offers keep their `NULL` FK (distinct under a partial unique index), so their
  multi-listing freedom is intact.
- **Kind-aware ratchet.** A sibling `clampItemQuantityToStock` (whole units, `ITEM`-only, active-only)
  runs in the same book-out / transfer / rebooking decrement transactions as the material clamp
  (`InventoryCheckoutService`); item rows have no refinery/handover-material consumption path, so no
  other site needs it. Each atomic conditional update is a no-op for the other kind.
- **Kind-aware `updateOffer` (fixes a latent defect).** The edit path previously validated
  `offeredAmount` against `offer.getInventoryItem()` unconditionally, which NPEs/400s on any item
  offer. It now branches: MATERIAL → offered amount vs stock; stock-backed ITEM → item quantity vs
  stock; free-stated ITEM → item quantity ≥ 1. The board's edit CTA is enabled for stock-backed item
  offers (free-stated ones stay edit-remark-only in the UI).

## Consequences

- One aggregate, three offer shapes (material, stock-backed item, free-stated item); the board query's
  effective-amount `COALESCE(LEAST(offeredAmount, item.amount), LEAST(itemQuantity, item.amount),
  itemQuantity)` and its filter/sort span all three without dropping any.
- A stock-backed item offer's quantity is now **live-backed**: it shrinks as the row is booked out and
  is deleted with the row (the `ON DELETE CASCADE` FK ADR-0087 already gave item offers), so a
  zero-stock item offer never lingers — the same guarantees a material offer has.
- ADR-0087's "quantity is a point-in-time claim; edit is remark-only" is **partially superseded**:
  still true for a free-stated offer, no longer for a stock-backed one.
- No new audit event type: the five `MARKET_*` events are reused with a kind-aware, PII-free details
  payload (`kind`/`item`/`product`/`qty`/`stock`), consistent with REQ-MARKET-008.

## Alternatives considered

- **Keep item offers free-stated only.** Rejected — item stock now exists; not binding an offer to it
  forgoes the ratchet, the clamp-on-read and the one-active-per-row consistency material offers enjoy,
  and leaves the owner to keep a phantom quantity truthful by hand.
- **A `game_item` FK as the offer identity.** Rejected again (as in ADR-0087) — a product is not 1:1
  with a `game_item`; `product_key` stays the identity and `inventory_item_id` carries the physical
  link, keeping the product-key↔item fuzziness a display concern.
- **A third `kind` (e.g. `ITEM_STOCK`).** Rejected — the free/stock distinction is exactly "is
  `inventory_item_id` set", already visible on the row; a third enum value would fork every kind switch
  for no modelling gain.
- **A separate write endpoint for stock-backed item release.** Rejected — the existing
  `POST /offers` release already carries the Lager row id; branching on the row's kind inside the
  service reuses the one release path, the one picker and the one modal.

