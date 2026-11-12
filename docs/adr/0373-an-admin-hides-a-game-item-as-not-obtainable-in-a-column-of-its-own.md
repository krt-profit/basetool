# ADR-0373 — An admin hides a game item as not obtainable, in a column of its own

- **Status:** Accepted
- **Date:** 2026-11-12
- **Deciders:** @greluc
- **Related:** amends the picker and write rules of ADR-0336 (REQ-P4K-013) · ADR-0370 (the Lager
  books every item of a stocked type) · ADR-0372 (the Materialbörse names an item without a
  blueprint by its catalogue row) · ADR-0345 (the starmap visibility override, the same kind of
  admin decision on a P4K row) · spec `docs/specs/p4k-catalogue.md` (REQ-P4K-048, REQ-P4K-013,
  REQ-P4K-046), `docs/specs/observability.md` (REQ-OBS-022)

## Context

The P4K catalogue lists every player item of the LIVE build's game files (REQ-P4K-001,
REQ-P4K-009). It carries no "obtainable right now" signal: shop and UEX price links cover a
fraction of the items and the catalogue has no loot flag (ADR-0370). Since the Lager books every
item of a stocked type and the Materialbörse offers items without a blueprint, the item pickers
offer items that exist in the files but cannot be obtained in the game yet. Admins need a lever to
take a single item out of the pickers and to put it back.

The catalogue already knows one hide: an admin who resolves a P4K review case as hide stamps
`p4k_hidden_at` on the row (REQ-P4K-016). That column is part of the row's P4K identity: the
duplicate merge moves it with the P4K GUID (`P4kItemParticipation`), a resolved case is never
resolved again, and every reader of `isP4kRetired()` treats it as "no longer in the game" — the
"no longer in the game" notice on existing references, the minimum stock levels that drop out of
the Lager list (REQ-INV-067) and the exchange's refusal to book the item in (REQ-XCH-045).

## Decision

1. **An own column, `game_item.unobtainable_since`.** An admin sets it when hiding an item as not
   obtainable in the game and clears it when offering the item again. No APPLY, sweep, Wiki or UEX
   sync and no duplicate merge writes it; the merge keeps the surviving row's own state.
2. **A selectable item is listed and obtainable.** `GameItem#isSelectable()` — the P4K lists the
   item (`isP4kRetired()` is false) and no admin hid it as not obtainable — and its query form
   `SelectableGameItemJpql.SELECTABLE_ITEM` replace the listed state wherever a new reference is
   chosen:
   - the item pickers filter on it: the Lager picker (`GameItemRepository.findStockable`), the
     Materialbörse's stocked items without a blueprint (`findStockedWithoutBlueprint`, ADR-0372)
     and the item-order picker (`BlueprintRepository.findOrderableItems`);
   - the blueprint-product search drops a product whose output item is unobtainable. It backs the
     Materialbörse item pickers and the personal and default blueprint pickers; a blueprint of an
     item nobody can obtain cannot be obtained either;
   - every write that names a game item by id refuses an unobtainable one like a retired one,
     through `SelectableGameItems` or, for an item-order line, its write facts, which concatenate
     the same fragment: `400`, code `CATALOGUE_ITEM_RETIRED`, an edit that keeps the item
     included. The refusal's wording covers both causes.
3. **What stays open.** Reads, lists, deletes, withdrawals and the state changes of existing
   entries; the minimum stock levels of an unobtainable item stay listed and counted; existing
   references show no notice, because the item is still in the game. Observed game data is not
   refused: the exchange books what a member's client reads (REQ-XCH-045) and the blueprint imports
   match by name, and the writes that name a blueprint product by key follow the blueprint
   (REQ-P4K-013), as they do for a retired item.
4. **The admin surface.** `/admin/items` ("Gegenstände"), a tab beside the starmap in the P4K
   data-import tab group: every item the P4K lists, searched by name, or only the hidden ones (the
   retired among them), with one hide or offer-again action per row. The page updates the row in
   place and follows a peer admin's change through the `catalogue-admin` room's `items` section
   (REQ-FE-015). The P4K review list stays as it is.
5. **Concurrency.** A change names the wanted state instead of echoing a version: a repeated or raced
   change is idempotent and an unchanged state records nothing. The syncs bump every row's version,
   so an echo would only refuse an admin after every sync. The change takes the `SyncCoordinator`
   lock without waiting, like a review resolution, and is refused with `409` while an APPLY or a Wiki
   or UEX sync runs.
6. **Audit and monitoring.** Each change is one `CATALOGUE_GAME_ITEM_OBTAINABILITY_CHANGED` event
   (subject the item, details `obtainable`); the gauge `basetool_catalogue_items_unobtainable` counts
   the hidden items.

## Consequences

- Admins can take an unobtainable item out of every picker without touching the import, and an
  APPLY never brings it back.
- A member who holds an unobtainable item keeps the entry and can withdraw or delete it, but cannot
  edit it or book it anew until an admin offers the item again — the rule REQ-P4K-013 sets for a
  retired item.
- The exchange still books an item an admin hid when a member's client reads it; a hide that real
  stock contradicts is the admin's to lift.
- A new picker over game items concatenates `SELECTABLE_ITEM`, not `P4kRetirementJpql.LISTED_ITEM`;
  the listed fragment stays for the reads of existing entries.

## Alternatives considered

- **Reuse `p4k_hidden_at`.** Rejected: the merge moves and overwrites it with the P4K identity, an
  offer-again would lift a review-case hide behind its resolved case, and every retirement reader
  would turn the hide into "no longer in the game", dropping minimum stock levels and refusing the
  exchange.
- **A separate table of hidden item ids.** Rejected: one more join in every picker query and a
  second lifecycle for a single fact of the row; the audit trail already records who changed it.
- **A version echo, like the starmap override.** Rejected for the reason in point 5.
- **A problem code of its own.** Rejected: the client handles the refusal the same way; one code
  with one wording keeps every write path unchanged.
- **Treat the hide as a full retirement.** Rejected: the item is still in the game, the notice would
  be wrong, and the exchange would lose what members really hold.
