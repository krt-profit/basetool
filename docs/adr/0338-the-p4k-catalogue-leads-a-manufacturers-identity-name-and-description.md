# ADR-0338 — The P4K catalogue leads a manufacturer's identity, name and description

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-020…023), [`docs/specs/p4k-catalogue.md`](../specs/p4k-catalogue.md) ·
  `REQ-DATA-004`, `REQ-DATA-015` ([`data-persistence.md`](../specs/data-persistence.md)) ·
  issue #2890 · map #2884 · [ADR-0023](0023-manufacturer-uex-company-alias-merge.md) ·
  [ADR-0335](0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

Three sources write `manufacturer`: UEX (which also holds the alias table
`manufacturer_uex_company`, the join key for UEX items and vehicles), the SC Wiki manufacturer
sync and the P4K import. The p4kx catalogue's `manufacturers` kind carries each manufacturer with
its GUID, `className`, `name` and `description` (each with `key`, `en` and `de`) and `code`, the
abbreviation, e.g. `ACAS`; `description` is a game text, `code` a short name. It also holds
pseudo-manufacturers that nothing playable references. `name` is unique on `manufacturer`, and the
abbreviation is never an identity (REQ-DATA-004). The Basetool shows only English names today.

## Decision

1. **Identity.** `manufacturer.p4k_uuid` becomes unique and is the first match key: P4K GUID, then
   the UEX alias, then the name. `scwiki_uuid` stays as a cross-reference.
2. **Name and abbreviation.** On rows with a P4K GUID only the P4K import writes `name` and
   `abbreviation` (from `code`). UEX writes them only on rows the P4K does not know.
   Disagreements between UEX and the P4K go to `external_sync_report` (REQ-DATA-015).
3. **Name conflict.** When the P4K name is already held by another row, the import does not rename.
   It records the conflict in the change report and puts the row on the review list. Merging two
   rows (repointing `ship_type` / `game_item`, carrying the UEX aliases and ids along) is an admin
   action; the import never merges.
4. **Description.** The P4K leads `description` and a new `description_de`. UEX stops writing the
   description. Rows without a P4K match keep their value.
5. **UEX keeps** `nickname`, `industry`, `wiki`, `is_item_manufacturer`,
   `is_vehicle_manufacturer` and the alias table.
6. **Seeding.** The P4K import creates a manufacturer only when an item or ship seeded in the same
   run references it, so pseudo-manufacturers are never created; other unmatched records appear in
   the change report only. UEX keeps creating manufacturers, and the next P4K import adopts the row
   by UEX alias or name.
7. **The Wiki manufacturer sync stays a cross-check.** It writes only its `scwiki_*` columns, never
   the name or abbreviation, and is never an identity source. A name or code that differs from the
   P4K goes to `external_sync_report`.
8. **`name_de` for every kind.** A new `name_de` on `manufacturer`, `game_item`, `ship_type` and
   `material`, written by the P4K import only. The UI shows `name_de` when the page language is
   German and falls back to `name`. Search and matching keep running on `name`.
9. **Logos.** Unmodified Fan Kit PNGs ship as static frontend resources, mapped from the P4K GUID
   to the file name by a versioned mapping file; no database column, no upload. They are shown in
   the Ship-Data manufacturer list, the personal and squadron hangar, the ship-type form picker and
   the item and inventory views; a manufacturer without a logo gets an abbreviation badge. Logos
   never reach the exchange, and the Fan Kit compliance test covers the PNGs.

## Consequences

- A manufacturer has one identity across the sources, and its name follows the game.
- A renamed manufacturer whose new name another row holds waits on the review list until an admin
  merges the two.
- German pages show German names for manufacturers, items, ships and commodities.
- Logos come from the Fan Kit, not from the game archive, so the rule against archive media does
  not apply to them.
- The rules go into the P4K catalogue spec, `docs/specs/p4k-catalogue.md`.

## Alternatives considered

- **Merge rows on a name conflict in the import.** Rejected: merging repoints member references and
  is an admin decision.
- **The Wiki manufacturer sync as an identity or name source.** Rejected for a cross-check that
  reports differences into `external_sync_report`.
