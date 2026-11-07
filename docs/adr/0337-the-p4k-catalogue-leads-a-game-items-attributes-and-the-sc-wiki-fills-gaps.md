# ADR-0337 — The P4K catalogue leads a game item's attributes and the SC Wiki fills gaps

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-017…019, REQ-OBS-023), [`docs/specs/p4k-catalogue.md`](../specs/p4k-catalogue.md) ·
  `REQ-DATA-015` ([`data-persistence.md`](../specs/data-persistence.md)) · issue #2889 · map #2884 ·
  supersedes [ADR-0283](0283-a-game-items-size-grade-and-class-come-from-the-sc-wiki-only.md) ·
  [ADR-0338](0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md) ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

ADR-0283 made the SC Wiki item sync the only writer of a `game_item`'s size, grade and class. The
p4kx catalogue (`schemaVersion` 2) now carries these facts for every item, read from the game
archive: `type`, `subType`, `size`, `grade`, `itemClass`, `mass`, `manufacturerGuid` and the names.

- `grade` is the raw DataForge integer; the catalogue carries no letter. For ship components the
  letter is in the `Grade:` line of the description, a game text. Of the items with such a line,
  the integer maps 1 to 4 onto A to D for the component types; 19 items, all with grade 1, do not
  match and the contract names them by type only. Elsewhere grade 1 carries no letter evidence, and
  `WeaponAttachment` runs from 3 to 7 with the size of the part.
- `itemClass` is one of `Civilian`, `Competition`, `Industrial`, `Military` or `Stealth`, or
  `null`; p4kx parses it from the English description's `Class:` line, and it is `null` for FPS
  weapons. A reader maps an unknown value of a closed set to `null`.
- The catalogue carries no dimensions, no UEX category and no prices.

## Decision

1. **The P4K leads per column, the Wiki fills gaps.** On a matched `game_item` the P4K import
   writes `type`, `sub_type`, `size`, `grade`, `item_class`, `mass`, the manufacturer (by
   `manufacturerGuid`), `name`, `name_de` and the name keys. The SC Wiki item sync writes these
   columns only where the P4K value is `null`. A Wiki value that differs from a P4K value goes to
   `external_sync_report` (REQ-DATA-015); the P4K value stays.
2. **Columns the catalogue does not carry keep their source.** Dimensions stay with the Wiki; the
   UEX category and every price or terminal field stay with UEX.
3. **Grade letter.** The import maps 1 → A, 2 → B, 3 → C, 4 → D only for the types `Cooler`,
   `PowerPlant`, `QuantumDrive`, `Radar` and `Shield`. The contract names the 19 mismatching
   grade-1 items by type only, not by GUID, so those of a mapped type map like their type and a
   differing Wiki letter is reported; the others get no letter from their type. Everywhere else the P4K grade counts as `null` for the letter
   column, so the Wiki fills it. The import never reads the `Grade:` description line.
4. **Raw grade.** A new nullable column `p4k_grade_raw` stores the integer for every matched item,
   whatever its type. It is a fact value, may reach the exchange, and has no UI.
5. **Class.** A `null` `itemClass` never clears a stored class; the Wiki fills it. An unknown value
   is stored as no class and counted in `basetool_catalogue_item_class_unknown_total`.
6. **Unknown grade values** (5 to 7 on `WeaponAttachment`, anything above) leave the letter `null`,
   keep the raw value and count in `basetool_catalogue_item_grade_unknown_total`.
7. **Disagreements are handled at the source.** The sync report lists them; the admin acts on the
   source, never by an override column. There is no review status for item disagreements.

## Consequences

- Items the Wiki does not know get size, grade and class from the archive; the Wiki keeps the
  columns the P4K leaves `null`, so FPS weapons keep their Wiki class and non-component items their
  Wiki grade.
- A disagreement between the sources stays visible in `external_sync_report` instead of being
  overwritten each cycle.
- The share of `null` `itemClass` per run is monitored as a gauge.
- REQ-DATA-024 is superseded in the P4K catalogue spec, `docs/specs/p4k-catalogue.md`, which
  carries these rules.
- The P4K's own value of each attribute is kept in a `p4k_*` column beside the displayed one, so
  the Wiki and UEX can tell a P4K value from their own.

## Alternatives considered

- **The SC Wiki only (ADR-0283).** Superseded: the catalogue now delivers these facts for every
  item from the archive itself.
- **The P4K strictly, with no Wiki writes on these columns.** Rejected for a gap filler: the P4K
  value is `null` for FPS weapon classes and for grades outside the five component types, where
  the Wiki has a value.
- **An admin override column for disagreements.** Rejected: the admin acts on the source.
