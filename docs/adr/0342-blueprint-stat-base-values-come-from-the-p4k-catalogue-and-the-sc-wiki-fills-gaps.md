# ADR-0342 — Blueprint stat base values come from the P4K catalogue and the SC Wiki fills gaps

- **Status:** Accepted (implementation pending)
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-030, REQ-OBS-023) · REQ-INV-069 · supersedes the source decision of
  [ADR-0309](0309-blueprint-stat-base-values-come-from-the-sc-wiki-item-payload.md) · #2927 · map
  #2884 · [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

The recipe view shows a blueprint modifier's stat as „base → value", which needs the produced
item's base value per stat (`game_item_stat`). ADR-0309 made the SC Wiki item payload the only
source, because the P4K catalogue then lacked armour and clothing and named no stat path for a
gameplay property.

The catalogue of p4kx v0.4.2 (`schemaVersion` 2) closes both gaps:

- `Item.stats` holds the raw stat blocks of weapons, shields, quantum drives, power plants,
  coolers, missiles, suit armour, clothing, radars, tractor beams, attachment modifiers, mining
  lasers and mining modules and gadgets — stored values in stored units, no derived value.
- The archive still has no link from a gameplay property to a stat. The contract fixes one in the
  table "From a gameplay property to a stat field" (29 properties): a blueprint modifier's
  `propertyGuid` names an entry of `gameplayProperties`, whose `className` selects the `stats`
  field.
- The three recoil properties and the reload speed map to no stat field. The SC Wiki has no field
  for the recoil properties either.
- Damage mitigation is the raw damage multiplier (`armor.damageResistance.<type>.multiplier`).

## Decision

1. **The P4K catalogue leads the stat base values; the SC Wiki fills gaps.** The P4K import writes
   the value from `Item.stats` for every item the catalogue covers; the Wiki writes only where no
   P4K value exists.
2. **The link follows the contract's table.** `BlueprintStatProperty` maps the gameplay property's
   `className` (from `gameplayProperties`, joined by the modifier's `propertyGuid`) to the stat
   field, following the table "From a gameplay property to a stat field" (29 properties).
3. **Each stored value names its source.** `game_item_stat` gains a `source` column (`P4K`,
   `SCWIKI`).
4. **A disagreement is reported.** A Wiki value that differs from the P4K value by more than 1 %
   relative goes to `external_sync_report`.
5. **The recoil keys stay without a source.** p4kx maps recoil and reload to no stat field.
6. **The mitigation conversion stays in the Basetool.** The catalogue carries the raw damage
   multiplier; the `1 − multiplier` conversion applies to both sources.
7. **The display is unchanged except for the tooltip.** The recipe view shows every value as
   REQ-INV-069 describes; the tooltip names the source (P4K or SC Wiki). No build stamp sits
   beside the values.
8. **Monitoring follows the source.** `basetool_catalogue_items_with_stats` gains a bounded
   `source` label; the `ScWikiItemStatsMissing` alert fires only for items neither source covers.

## Consequences

- Armour, clothing and the other stat blocks come from the build the game runs, and the Wiki keeps
  covering items the catalogue does not.
- The P4K catalogue spec takes over the precedence rule from the data-persistence spec's stat
  requirement, which ADR-0309 backed.
- The observability spec and the monitoring rules change for the `source` label and the narrowed
  alert.
- Pending: the `source` column, the P4K writer in the import, the `className` mapping in
  `BlueprintStatProperty`, the 1 % disagreement report, the tooltip, and the metric and alert
  change.
