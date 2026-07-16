# ADR-0100 — Track game items as catalog-discriminated inventory rows

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** @greluc
- **Related:** ADR-0098 (Variante-C allocations), ADR-0099 (production booking), ADR-0053
  (searchable combobox, extended by REQ-FE-016) · spec `docs/specs/inventory-items.md`
  (REQ-INV-029..032) · design `docs/DESIGN_ITEM_INVENTORY.md`

## Context

Game items (`GameItem`, initially blueprint outputs) were not trackable in the Lager:
`InventoryItem` was strictly material-based, and a production booking (ADR-0099) consumed
material stock but only incremented `JobOrderItem.manufacturedAmount` — the produced item
existed nowhere as stock. The owner wants items as separate Lager views with
material-equal booking flows (no quality; allocations only to ITEM orders), production
booking that books the produced units in at a user-chosen location/owner, and
forward-compatibility with Materialbörse item offers released from stock.

Two shapes were considered: (A) extend `InventoryItem` with a nullable `gameItem`
reference (XOR with `material`), or (B) a parallel item-inventory aggregate (own entity,
allocation tables, services, controllers, templates).

## Decision

**Extend `InventoryItem` (option A): one aggregate, catalog-discriminated by which of
`material_id` / `game_item_id` is set** (DB CHECK XOR + quality-by-kind pairing, V220; no
stored discriminator column — partial indexes key on `game_item_id IS NOT NULL`).

- Item rows: no quality, positive whole units, PIECE auto-merge (REQ-INV-026), stack key
  `user · gameItem · location · personal · owningOrgUnit`.
- Variante-C allocation tables, merge, book-out/rebook, Herkunft picker, audit, tenancy,
  live sync, bulk checkout, reconciler and wipe are **reused, not duplicated** — the
  "same functions as material" requirement holds by construction.
- Allocations are gated by a `requiredGameItemIds` sibling of the REQ-ORDERS-018 material
  gate (ITEM orders requesting the gameItem only; no mission dimension).
- Production booking gains a `bookIn` block (location / owner / org unit / personal /
  auto-earmark) executed in the same transaction, audited as
  `INVENTORY_RECEIVED_FROM_PRODUCTION`.
- The catalog predicate ("bookable item") is *output of ≥ 1 active blueprint* — a
  superset of the order picker's RESOURCE-bearing `findOrderableItems`, and it guarantees
  a resolvable blueprint product key for the planned Materialbörse phase.

## Consequences

- The cost of (A) is a **bounded one-time query audit**: with `material_id` nullable,
  every query navigating `i.material` needed a per-query verdict (Hibernate drops
  NULL-material rows on attribute navigation but not on id-only FK dereference — and the
  flat lists' default `material.name` Pageable sort injects the same trap at runtime).
  The binding remediation list lives in the design doc §4.4; each entry carries a
  regression test with an item row present. Material-only surfaces (Materialsammlung,
  Materialbörse picker, order stock index, blueprint availability) keep explicit
  `material IS NOT NULL` guards.
- The `@ManyToOne(optional = ...)` flag on `InventoryItem.material` is load-bearing for
  those verdicts and documented on the entity.
- The audit subject-label snapshot (`InventoryAuditLabels`) renders the gameItem name for
  item rows; reused `INVENTORY_*` event types carry `gameItemId=` payload keys.
- Forward-compatibility (design §8): the Materialbörse offer FK works for item rows
  unchanged; `mergeStockIfRequested` keeps excluding offer-backed rows; the clamp is
  NULL-material-safe. Stock-backed item offers (Phase 5) need only offer-side changes.
- **Boundary to "Mein Inventar"** (`PersonalInventoryItem`): Lager item stock =
  catalog-linked `GameItem` rows with org visibility, allocations and booking flows; Mein
  Inventar = free-text personal records (blueprints V3). A Lager item row with
  `personal = true` is still catalog-linked org data. No consolidation is intended.

## Alternatives considered

- **Parallel item-inventory aggregate (B)** — rejected: duplicates the entity, two
  allocation tables, three services, mapper, controllers, templates and live-sync wiring;
  every future Lager change would land twice, and "same functions as material" becomes an
  ongoing promise instead of a construction guarantee. The Materialbörse offer model
  would also need a second FK + kind plumbing.
- **Stored discriminator column** — rejected: redundant with the XOR CHECK; partial
  indexes and `game_item_id IS NOT NULL` predicates cover every query need.
- **Quality sentinel (0) instead of NULL for item rows** — rejected: leaks a fake quality
  into filters/aggregates and the merge key; NULL with a DB-enforced pairing keeps the
  dimension honest at the cost of NULL-branches in two queries.
- **`GameItem.isCraftable` as the catalog predicate** — rejected: the blueprint→output
  relation is what the orders feature treats as authoritative; the flag is sync-derived
  display metadata.

