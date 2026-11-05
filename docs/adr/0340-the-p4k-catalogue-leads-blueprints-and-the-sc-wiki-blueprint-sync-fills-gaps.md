# ADR-0340 — The P4K catalogue leads blueprints and the SC Wiki blueprint sync fills gaps

- **Status:** Accepted (implementation pending)
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-027, REQ-P4K-028), [`docs/specs/p4k-catalogue.md`](../specs/p4k-catalogue.md) ·
  `REQ-DATA-015` ([`data-persistence.md`](../specs/data-persistence.md)) · issue #2892 · map #2884 ·
  [ADR-0335](0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
  [ADR-0342](0342-blueprint-stat-base-values-come-from-the-p4k-catalogue-and-the-sc-wiki-fills-gaps.md) ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

Blueprints are written by the SC Wiki blueprint sync and the P4K import. The p4kx catalogue's
`blueprints` kind carries the whole recipe: the produced item (`producedItemGuid`), all tiers,
mandatory and optional cost lines with choose-n selections (`count`, `options`), quantities with a
`quantityMultiplier`, minimum quality, craft time, modifiers (`propertyGuid`, linear `ranges`), the
category (`categoryGuid`, a record of `blueprintCategories`), `defaultAvailable`, and the dismantle
link (`{processGuid, tierIndex}`), with exclusions in `craftingGlobal`. A blueprint's own `name` is
almost always a placeholder. The dismantle rules are unverified library assumptions in p4kx. The
catalogue also holds `blueprintPools`, `qualityDistributions`, `qualityQuantizations` and
`crafters`, which nothing in the Basetool consumes.

## Decision

1. **The P4K leads every recipe attribute:** produced item, tiers, mandatory and optional cost
   lines, choose-n selections, quantities (with `quantityMultiplier` applied by the Basetool),
   minimum quality, craft time, modifiers, category, `defaultAvailable` and dismantle.
2. **Naming.** A blueprint is named through its produced item, never through its own `name`.
3. **The Wiki blueprint sync becomes a gap filler.** It writes only fields the P4K delivers as
   `null` on a row, and keeps writing rows the P4K does not know. Where both have a value and they
   differ, the P4K value stays and the difference goes to `external_sync_report`.
4. **Seeding.** The import creates a blueprint when its `producedItemGuid` resolves to an item that
   exists or is created in the same run, behind the same off-by-default per-run checkbox as items
   and ships (ADR-0335); the preview counts them. Other blueprints stay match only and appear in
   the change report's unmatched list.
5. **Categories.** `blueprintCategories` become the blueprint categories, P4K GUID as identity;
   they do not nest.
6. **Not imported:** `blueprintPools`, `qualityDistributions`, `qualityQuantizations` and
   `crafters`.

## Consequences

- Recipes follow the game file; the Wiki still covers blueprints and fields the P4K lacks.
- Because the dismantle rules are unverified, the Wiki cross-check of differing values stays
  meaningful.
- The stat base values are decided separately in ADR-0342.
- The rules go into the P4K catalogue spec, `docs/specs/p4k-catalogue.md`. Implementation is
  pending.

## Alternatives considered

- **Switch the Wiki blueprint sync off.** Rejected: it fills fields and blueprints the P4K lacks,
  and its differences check the unverified dismantle rules.
- **Keep the Wiki sync as a cross-check only.** Rejected for a gap filler, which also writes the
  fields the P4K delivers as `null` and the rows it does not know.
