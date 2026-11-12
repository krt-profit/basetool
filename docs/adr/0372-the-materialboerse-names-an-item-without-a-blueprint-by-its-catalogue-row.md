# ADR-0372 — The Materialbörse names an item without a blueprint by its catalogue row

- **Status:** Accepted
- **Date:** 2026-11-11
- **Deciders:** @greluc
- **Related:** amends the item identity of ADR-0087, the identity bridge of ADR-0108 and the item
  requests of ADR-0116 · ADR-0370 (the stocked item groups) · ADR-0349 (catalogue reconciliation) ·
  spec `docs/specs/materialboerse.md` (REQ-MARKET-008, -012, -014, -015, -023),
  `docs/specs/inventory-items.md` (REQ-INV-029), `docs/specs/audit.md` (REQ-AUDIT-001),
  `docs/specs/data-persistence.md` (REQ-DATA-034)

## Context

ADR-0087 named every Materialbörse item by its blueprint `product_key`, which doubled as the gate
"a blueprint exists". ADR-0108 bridged a stock-backed release to that key through the row's game
item, and ADR-0116 used the same key for item requests. An item no active blueprint produces could
therefore be neither offered nor requested.

ADR-0370 widened the Lager to every item of a stocked type. Members now book weapons, armour,
components and loot nobody can craft — the Scourge Railgun, the Animus Missile Launcher — and the
release of such a row was refused with `400`. The owner decided that the Börse offers and requests
the same items the Lager books.

## Decision

An `ITEM` offer or request names its item by **exactly one** of two identities: the blueprint
`product_key` when an active blueprint produces the item, otherwise `item_game_item_id`, the
catalogue row (V407, a foreign key onto `game_item`). The kind checks of both tables require
exactly one of the two on the `ITEM` branch and neither on the `MATERIAL` branch.

- **The product wins.** A write naming a catalogue row that an active blueprint produces is stored
  under its product key, so one item never lists under two identities. `ExchangeItemSubjects`
  resolves the identity on every write.
- **Which rows.** A free-stated offer or a request may name a listed catalogue row the Lager books
  through its type (`StockItemCatalogue#isStockedByType`, the groups of ADR-0370); any other row is
  refused with `400`. A stock-backed release takes the row's item whatever its type, because the row
  is already stock; only a retired item is refused (REQ-P4K-013).
- **Pickers.** The "Item anbieten" and "Item suchen" pickers search the blueprint products and the
  stocked items no active blueprint produces (`GET /api/v1/game-items?withoutBlueprint=true`) and
  show both in one name order.
- **Board.** A page resolves product keys and catalogue rows to game-item references in one batch
  (REQ-MARKET-023), following the redirect of a reconciled row.
- **Audit.** The details carry `gameItem=<id>` in place of `product=<key>`; never the display name.
- **Catalogue merges.** The Börse joins the reconciliation SPI: `MaterialExchangeItemReassignment`
  moves the entries of a retired row to the survivor, bumps their versions, records one
  `MARKET_CATALOGUE_REASSIGNED` per entry and refreshes the `board` section.

## Consequences

- Members can offer and request their bought and looted gear, matching what the Lager books.
- The identity is fixed when an entry is written. If a blueprint starts producing an item later,
  older entries keep the catalogue row while new ones carry the product; both resolve to the same
  game item on the board.
- The foreign key makes a catalogue merge that skipped the Börse fail on the row's deletion instead
  of leaving dangling references.
- Job orders stay on blueprint outputs.

## Alternatives considered

- **A synthetic product key for items without a blueprint.** Rejected: product keys name what
  blueprints produce and resolve through the blueprint products; a made-up key would resolve
  nowhere and would not follow a catalogue merge.
- **The catalogue row as the only identity.** Rejected: every existing entry and the personal
  blueprints key on the product; migrating them buys nothing for the items that have one.
- **The display name alone.** Rejected: the board could not show a component's type, size, grade
  and class, and a merge could not repoint the entry.
