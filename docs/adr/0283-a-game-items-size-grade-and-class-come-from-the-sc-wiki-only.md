# ADR-0283 — A game item's size, grade and class come from the SC Wiki only

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** spec `REQ-DATA-024` ([`data-persistence.md`](../specs/data-persistence.md)) ·
  spec `REQ-DATA-015`, `REQ-INV-061`, `REQ-INV-062` ([`inventory-items.md`](../specs/inventory-items.md)) ·
  [ADR-0148](0148-an-inbound-mapping-binds-only-what-the-upstream-serves.md) ·
  `backend/…/catalogue/internal/ScWikiItemSyncService.java`,
  `backend/…/catalogue/api/ShipComponentClass.java`, `V307__add_game_item_item_class.sql`

## Context

The Lager shows a ship component's size, grade and class beside its name (REQ-INV-061,
REQ-INV-062). Three catalogue sources could in principle supply them:

- the **SC Wiki** item API serves `size`, `grade` and a top-level `class` (`Military`,
  `Civilian`, `Industrial`, `Stealth`, `Competition`) for every ship component;
- the **KRT P4K Reader** import reads the game files, where the same facts sit in DataForge
  records under other names and in another shape;
- **UEX** serves a free-text quality tier (`uex_quality`) and no class.

`size_class` and `grade` have been filled by the wiki sync since R4. The wiki sync wrote every
column unconditionally, so a payload that happened to omit `size` or `grade` erased a value a
previous run had stored. Adding the class raises two further questions: what to do with a value
the code does not know, and whether a second source should fill gaps.

## Decision

**Size, grade and class of a `game_item` are written by the SC Wiki item sync only. The P4K
import and the UEX sync do not write them.**

1. **One source.** The wiki is the only source that serves all three for every component in one
   shape. A second writer would need a precedence rule per column and would make a disagreement
   between the sources invisible — exactly the per-cycle overwrite REQ-DATA-015 forbids.
2. **A fixed value set for the class.** `item_class` is an enum column (`ShipComponentClass`) with
   a CHECK constraint, not free text, so the frontend can map every value to a translated label and
   a design-system colour.
3. **An unknown class is stored as no class, and made visible.** The sync logs each unknown value
   once, increments `basetool_catalogue_item_class_unknown_total` per affected item, and writes the
   rest of the row. A new in-game class therefore never fails a sync and never disappears silently;
   adding it is one enum constant, two labels and a migration widening the CHECK.
4. **An absent field keeps the stored value.** A blank or missing `size`, `grade` or `class` leaves
   the column alone; only a present value overwrites it.

## Consequences

- Items the wiki does not know (UEX-only rows) carry no size, grade or class and show no chips.
  That is accepted: those are rarely ship components.
- A component whose class the wiki drops keeps its last class until the wiki serves a new one. A
  stale class is preferred to one that blinks in and out with an upstream payload gap.
- The values appear after the next wiki item sync, not at deploy time.
- `ScWikiItemClassUnknown` (warning) fires while the counter rises, pointing at the backend WARN
  that names the value.

## Alternatives considered

- **P4K as a fallback for missing values.** Rejected: the P4K import runs on demand, not on a
  schedule, and its class lives in a different vocabulary that would need its own mapping to keep
  in step with the wiki's.
- **Free-text class column.** Rejected: an unknown upstream spelling would reach the UI as raw text
  or as a chip without a colour.
- **Failing the item on an unknown class.** Rejected: one new value would stop every component of
  that class from being refreshed.
