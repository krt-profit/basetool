# ADR-0339 — The P4K catalogue leads commodities and its resource groups become the commodity categories

- **Status:** Accepted (implementation pending)
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-024…026), [`docs/specs/p4k-catalogue.md`](../specs/p4k-catalogue.md) ·
  `REQ-DATA-015` ([`data-persistence.md`](../specs/data-persistence.md)) · issues #2891, #2825 ·
  map #2884 ·
  [ADR-0336](0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) ·
  [ADR-0338](0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md) ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

Commodities (`material`) are written by UEX, the SC Wiki and the P4K import; their categories are
maintained by admins. The p4kx catalogue's `commodities` kind identifies a commodity by its own
`guid`, names its `ResourceType` in `subtypeGuid` (not unique: in the LIVE export of p4kx 0.4.2
three SLAM commodities share one, MedPens and Redfin Energy Modulators share one, and one commodity
has none) and links it to its group (`typeGuid`, a record of the
`resourceGroups` kind), to the commodity it refines into (`refinedSubtypeGuid`, one step) and to
its density (`densityGramsPerCubicCentimetre`, present for all 207 records). A resource group
carries `name` and `parentGuid`. The catalogue has no RAW / REFINED flag, and the archive holds no
refinery yields. Instability lives on the `mineableElements` kind, not on the commodity.

## Decision

1. **Identity.** A commodity's P4K identity is its own `guid`, stored in a unique `p4k_uuid`;
   match order P4K GUID, then the UEX id, then the name. `subtypeGuid` is stored in a non-unique
   column and is only the join for blueprint ingredients (`resourceGuid`) and for
   `refinedSubtypeGuid`. An ingredient that matches several commodities goes to the review list;
   the admin's assignment survives re-imports.
2. **The P4K leads** `name`, `name_de`, the group (`typeGuid`), the density
   (`densityGramsPerCubicCentimetre`) and the refined-from relation (`refinedSubtypeGuid`). The Wiki
   and UEX write these only on rows without a P4K GUID; a disagreement goes to
   `external_sync_report`.
3. **UEX keeps** trade flags, prices, terminals, stock, refinery yields and the RAW / REFINED type.
4. **RAW / REFINED without UEX.** On a row UEX does not know, the import sets RAW on a commodity
   that has a `refinedSubtypeGuid` and REFINED on its target; otherwise the type stays `null`.
5. **Out of scope.** Instability is not imported; refining methods (`refiningProcesses`) stay with
   the existing refinery data.
6. **Categories.** The `resourceGroups` kind becomes the commodity categories: P4K GUID as
   identity, `name` / `name_de` from the P4K (falling back to `className` when the name is a
   placeholder), `parentGuid` as parent, with a cycle guard. A one-off migration maps every
   admin-maintained category onto a group and removes the admin categories; admins no longer create
   or rename categories. A group that vanishes from a `complete` kind is stamped like any other P4K
   row (ADR-0336).
7. **Seeding.** The import creates a commodity only when a record imported in the same run
   references it: a blueprint ingredient (`resourceGuid`) or a `refinedSubtypeGuid`. Such a row is
   visible at once. Everything else stays match only and appears in the change report's unmatched
   list.

## Consequences

- Commodity names, groups, density and the refining relation follow the game; trade and refinery
  economics stay with UEX.
- The commodity categories follow the game's resource groups; the admin category editor goes away
  and the migration maps the existing categories.
- A commodity a blueprint needs exists even when UEX does not list it.
- The rules go into the P4K catalogue spec, `docs/specs/p4k-catalogue.md`. Implementation is
  pending.
