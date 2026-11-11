# ADR-0370 — The Lager books every item of a stocked type beside the blueprint outputs

- **Status:** Accepted — the Materialbörse consequence is resolved by ADR-0371
- **Date:** 2026-11-11
- **Deciders:** @greluc
- **Related:** amends the catalog predicate of ADR-0101 · ADR-0087, ADR-0108 (Materialbörse item
  identity) · spec `docs/specs/inventory-items.md` (REQ-INV-029), `docs/specs/supply.md`
  (REQ-SUP-003), `docs/specs/frontend-ajax-mutations.md` (REQ-FE-016)

## Context

ADR-0101 made game items Lager stock and limited the item picker to the output of at least one
active blueprint: the feature was built for what job orders produce, and bought or looted items
were left for later. Members also keep weapons, armour, components, ordnance and loot nobody can
craft. The Scourge Railgun, the Animus Missile Launcher and the Boomtube Rocket Launcher are
catalogue items without a blueprint, so a member could not book them by hand.

The rule had already stopped describing the Lager. A production read on 2026-11-11 found 27 of
103 item rows on items without a blueprint — missiles, mining gadgets and modules, ship guns, a
helmet, a storage container and loot such as artifacts, medals and ingots — because the exchange
API books any listed catalogue item. Of 14,638 listed catalogue items, 1,613 had an active
blueprint.

The catalogue cannot simply be offered whole. Its item types include a ship's fixed parts (seats,
doors, displays, tanks, thrusters, countermeasure launchers, turrets), cargo, clothing, paints,
decoration and consumables, and about 1,400 rows carry no type at all: UEX duplicates of typed
rows, armour sets that are no single item, and stray game-file records.

## Decision

The Lager item picker offers every listed item that a listed active blueprint produces, **and**
every listed item whose P4K type (`GameItem#wikiType`, the SC Wiki filling a gap) belongs to a
`StockableItemGroup`: FPS weapons without grenades and flares, weapon attachments without
magazines and launcher ammunition, armour, jetpacks, every `ShipComponentType` except the turret
plus ground-vehicle missile launchers, mining and salvage modules, storage containers, and loot
(`Misc`) without mineable gems and food.

- The scope is an **allow-list of types** with sub-types left out per group. A type the game adds
  later stays out until a group names it, so the picker cannot fill up with new internal records.
- **Blueprint outputs stay bookable** whatever their type, so a magazine a job order produces can
  still be booked by hand.
- **Rows without a type** are bookable only as blueprint outputs.
- The ship groups reuse `ShipComponentType`, the taxonomy behind the component chips of
  REQ-INV-061, so what the Lager labels as a component is what it books.
- The item-order picker stays on blueprint outputs with a resolved ingredient. The write paths do
  not re-check the picker's rule, as before.
- One query, `GameItemRepository.findStockable`, takes the groups' types and the excluded
  `type:subType` keys as parameters, so `StockableItemGroup` is the rule's only source.
- The supply need, pledge and recorded-donation forms use the same picker through the
  server-searched `remote-game-items` combobox. Their preloaded list of the first 500 items had
  already cut off part of the 1,613 craftable items.

## Consequences

- Members can book their bought and looted gear, and the web picker matches what the exchange
  clients already book.
- The picker grows from about 1,600 to about 4,500 items. It searches by name on the server, so the
  size costs no page weight, but same-name rows become visible (for example eleven "Pyro RYT
  Multi-Tool" rows). A catalogue-level answer to those duplicates is follow-up work.
- A stock-backed Materialbörse offer keys on a blueprint product (ADR-0087, ADR-0108). A Lager row
  of an item without a blueprint has no product, and its release is refused with `400`, as it was
  for the rows the exchange booked. Giving the Börse an identity for such items is a separate
  decision.
- Clothing, paints, decoration and turrets stay out by owner decision. Adding a group is a change
  to `StockableItemGroup` and to REQ-INV-029.

## Alternatives considered

- **Every listed catalogue item.** Rejected: about 10,000 further rows — a ship's fixed parts,
  cargo records, consumables, cosmetics and untyped rows — would bury the items members store.
- **A deny-list of consumable types.** Rejected: every new internal record type the game ships
  would appear in the picker until someone listed it.
- **Obtainability from shop, price and loot data.** Rejected: the shop and UEX price links cover a
  fraction of the items (26 of 415 FPS weapons), the catalogue stores no loot flag, and event,
  reward and pledge items would drop out.
- **Rows without a type by their UEX-derived kind.** Rejected: they are mostly duplicates of typed
  rows or bundles, so a member would book the same item under two catalogue rows.
