# ADR-0087 — Materialbörse item offers (blueprint products, stated quantity)

- **Status:** Accepted
- **Date:** 2026-07-10
- **Deciders:** @greluc
- **Related:** ADR-0082 (extends D1; the partial-offer amount ADR is a separate D1 amendment) · spec REQ-MARKET-012 (`docs/specs/materialboerse.md`) · REQ-AUDIT-001 · REQ-OBS-011 · REQ-FE-010

## Context

The Materialbörse (ADR-0082) trades only Lager rows (`InventoryItem`), whose facts — material,
quality, amount — are read **live** from the item (D1). Issue #1185 adds a second thing to trade: a
**craftable item** ("an item for which a blueprint exists"). Such an item has no Lager row, no
quality, and — crucially — no live source for its quantity, so the offering player must **state** the
quantity. This breaks ADR-0082 D1's "facts read live, the client never sets them" for the new offer
kind. The identity question also differs: a blueprint product is not 1:1 with a `game_item` row (a
product groups several recipes and its resolved `game_item` FK is frequently null), so a hard
`game_item` FK is not a reliable identity.

## Decision

We extend `MaterialExchangeOffer` into a **discriminated aggregate** (`kind ∈ {MATERIAL, ITEM}`)
rather than adding a sibling entity, keeping one board query, one interest aggregate, one audit domain
and one live-sync relay.

- **Discriminator + relaxed FK.** Add `offer_kind` (NOT NULL), make `inventory_item_id` nullable, and
  add `item_product_key` / `item_name` / `item_quantity` (V213). A DB `CHECK` enforces exactly one
  branch: a `MATERIAL` offer has a Lager row and no item fields; an `ITEM` offer has a product
  key/name/positive quantity and no Lager row.
- **Product identity = normalized `product_key`** (the same identity `personal_blueprint` /
  `default_blueprint` use), **not** a `game_item` FK. A release validates the key against
  `BlueprintProductService.resolveByProductKey(...)` — this is both the "a blueprint exists" gate
  (#1185) and the source of the canonical display name snapshotted onto the offer. An item an active
  blueprint does not produce cannot be listed.
- **Stated quantity, no live-read (the D1 deviation).** For an `ITEM` offer the client supplies the
  whole-piece quantity and it is stored on the offer; there is no quality. `MATERIAL` offers keep D1
  live-read unchanged.
- **No de-duplication for item offers.** The V210 one-active-per-Lager-row partial-unique index still
  governs material offers (an item offer's NULL `inventory_item_id` is distinct under it). Item offers
  add **no** unique index: a member may deliberately list the same product several times.
- **Owner/squadron stamped from the acting member.** With no source item to copy from, an item offer
  denormalises the owner (the caller) and the owning org unit from the active-context
  `OwnerScopeService.currentOrgUnit()` (nullable for a member in no Staffel/SK).
- **Reused cross-cutting surfaces.** Item offers reuse the five `MARKET_*` audit events (kind-aware,
  PII-free `kind`/`product`/`qty` details — never the display name or remark body), the single
  `basetool_material_exchange_active_count` gauge (now spanning both kinds), and the existing `board`
  live-sync section key (no new key ⇒ the REQ-FE-010 three mirror points stay unchanged). The board
  query becomes a `LEFT JOIN`/`COALESCE` read spanning both kinds so item offers (null
  `inventory_item_id`) are not dropped by an implicit inner join; a non-zero min-quality filter
  excludes item offers (they have none).

## Consequences

- One aggregate/board/relay for two offer kinds — no parallel entity, no second sync stack.
- The board's amount sort/filter mixes SCU (material) and piece counts (item) via `COALESCE`; this is
  an inherently rough ordering across incomparable units, accepted for a mixed board.
- An item offer's quantity is a point-in-time claim, not a live figure — deliberately, since there is
  nothing to read it from; the owner keeps it truthful (edit is remark-only for now).
- The `game_item` link is intentionally not persisted; if an item card ever needs an icon that is a
  new, additive join, not a change to the identity.

## Alternatives considered

- **Sibling entity `ItemExchangeOffer`.** Rejected — would duplicate the board query, the interest
  aggregate, the audit domain and the live-sync relay for no modelling gain.
- **`game_item` FK as the item identity.** Rejected — a blueprint product's resolved `game_item` is
  frequently null and not 1:1 with a product; `product_key` is the always-present canonical identity.
- **Snapshot a "quality" for item offers.** Rejected — a craftable item has no inherent 0–1000 quality
  and the issue asks only for a quantity; adding one is model + UI weight with no source.
- **One active item offer per (owner, product) partial-unique.** Rejected — @greluc chose to allow
  multiple active listings of the same item.

