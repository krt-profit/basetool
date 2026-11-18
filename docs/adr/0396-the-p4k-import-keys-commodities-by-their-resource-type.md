# ADR-0396 — The P4K import keys commodities by their resource type

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** @greluc
- **Related:** spec [`p4k-catalogue.md`](../specs/p4k-catalogue.md) (REQ-P4K-010, REQ-P4K-016,
  REQ-P4K-024…026, REQ-P4K-029, REQ-P4K-070…079) · [ADR-0339](0339-the-p4k-catalogue-leads-commodities-and-its-resource-groups-become-the-commodity-categories.md) ·
  [ADR-0393](0393-p4k-item-cases-open-only-where-an-admin-can-decide.md)

## Context

After the APPLY of LIVE 4.10.197 (170 commodity records, 19 resource groups, 207 resource types),
176 `NO_RESOURCE_GROUP`, 6 commodity `GUID_CONFLICT`, 6 `SEEDED_NOT_CREATABLE` and one
resource-group `GUID_CONFLICT` case were open, and every APPLY reported 123 commodity
`IDENTITY_MISMATCH` rows. Checked against the catalogue and the stored rows:

- The SC Wiki keys every commodity by the GUID of its `ResourceType` (186 of 186), which is the
  record's `subtypeGuid`. The matcher compared the Wiki UUID with the record's own GUID, so every
  material it matched by name reported a mismatch, and a record whose name another record shares
  could not be matched at all (the two "Redfin Energy Modulators", `raw_hephaestanite`).
- Every `ResourceType` belongs to exactly one resource group, the same as its records' `typeGuid`
  (169 of 169). The version-2 catalogue lists the types (`resourceTypes`, with `groupGuids`), but
  the import did not read them.
- The three "SLAM" records are packagings of one commodity: same type, name and group.
- A row already holding another listed record's GUID still counted as a name candidate: the
  counterfeit Redfin record and the second "Man-made" group (`Bulk_Supplies`) collided with rows
  that were never theirs.
- 30 materials without a group were UEX commodities the game files do not contain at all; nothing an
  admin could pick would be right for them, and 26 of them are offered in every picker although no
  game source, no price and no member uses them.

The owner decided on 2026-11-17 that the import opens no case where the game has nothing to link,
and that UEX commodities no game source knows, unpriced and unused, are hidden until a source or a
price brings them back.

## Decision

1. **A commodity's Wiki key is its resource type (REQ-P4K-070).** `P4kMatchRules` gains
   `wikiKey`, which the matcher's Wiki step looks up and the mismatch report accepts; the commodity
   rules return the `subtypeGuid` when that type joins the record alone, every other kind its GUID.
2. **Packagings are one commodity (REQ-P4K-071).** Records sharing type, English name and group are
   led by the record of the type's class name, else the lowest class name; the others are covered,
   and the join, the creation rule and the repeated-name index see only the leading record.
3. **A row of another listed record is no candidate (REQ-P4K-072).** A material belongs to the
   record whose GUID it holds or whose Wiki key it carries; a group to the GUID it holds. Neither
   is a class-name or name candidate of another record.
4. **Groups come from the resource type (REQ-P4K-073).** The reader reads `resourceTypes` as a held
   extra beside the paints (`P4kResourceTypeDto`); a catalogue without them falls back to the types
   its commodity records name. A material no record reconciles takes the group of the type its Wiki
   UUID or stored `subtypeGuid` names, else of the only type of its reduced name, and the group
   reconciler creates those groups. An admin's override wins. A placeholder group — no English
   name, and no commodity record names it as `typeGuid` (`HeatPlaceholder`, `CleanAir`,
   `LifeSupportPlaceholder`, `PowerPlaceholder`) — is never given or created this way: it has no
   meaning a member could read. The name criterion alone would also catch `UnrefinedOres`,
   `Raw_Minerals` and `Halogen`, which commodities use, and the usage criterion alone
   `Bulk_Supplies`, which the ship ammunition needs; only both together pick the four.
5. **No case without a game record (REQ-P4K-074).** The group review opens `NO_RESOURCE_GROUP` only
   for a material the game files know, and a type only a placeholder group lists does not count.
6. **Sourceless UEX commodities hide themselves (REQ-P4K-075).** An APPLY marks
   `material.sourceless_hidden_at` (V436) on a UEX-only material without a game identity, a price
   or a reference; the modules holding material references answer through a new observer SPI,
   `MaterialReferenceParticipant`, the catalogue included. The pickers leave such a material out
   only while it is still sourceless and unpriced (`SelectableMaterialJpql`), so the hide lifts the
   moment a source or a price arrives; the next APPLY clears the stale mark. Every mark and lift is
   audited as `CATALOGUE_MATERIAL_SOURCELESS_VISIBILITY_CHANGED`.
7. **Commodity and group cases settle per APPLY (REQ-P4K-016)**, the way item cases do.
8. **Namesake groups stay distinguishable (REQ-P4K-025).** When groups of one catalogue share an
   English or German name, the group of the lowest GUID keeps it and every other takes its class
   name in readable form in parentheses ("Hergestellte Produkte (Bulk Supplies)"). The GUID orders
   them because it is the group's stable identity and independent of the file's order; ordering by
   class name would rename the established `SyntheticMaterials` category instead of the new
   `Bulk_Supplies` one.

## Consequences

- Replayed on the stored rows of 4.10.197: 161 commodities match, none conflicts, one stays
  unmatched (the counterfeit Redfin record), the 123 mismatch rows end, 27 materials take a group by
  their type's identity and 12 by its name (the seven "Ship Ammunition – Size N" included), 32
  materials open no case because the game lacks them or knows them only by a placeholder group
  ("Molina Mold"), and 26 materials are hidden.
- Groups the stored materials need are created even when no commodity record names them; a
  placeholder group never is.
- The new `Bulk_Supplies` group shows as "Man-made (Bulk Supplies)" / "Hergestellte Produkte (Bulk
  Supplies)" beside the existing "Man-made" / "Hergestellte Produkte".
- The 6 seeded `SEEDED_NOT_CREATABLE` rows, twin handovers and removals, the Party Favors /
  Fireworks merge and the blueprint aliases stay for later rules.

## Amendment — leftover materials and replaced blueprint names (2026-11-17)

The owner decided on 2026-11-17 that a leftover material nothing uses is hidden or folded into its
twin automatically, that every such action is audited, and that no case opens where nothing is
left to decide. Before the commodity pass an APPLY therefore retires:

9. **A seeded commodity no rule would create (REQ-P4K-076).** Unless a member row references it,
   it hands its P4K identity to the live material its record's Wiki key names, which the pass then
   matches as usual, and is deleted; while a catalogue row references it, deleting it would break
   that row, so it is hidden under its name and GUID instead. Without such a twin it is hidden,
   as seeded items are (REQ-P4K-056).
10. **An admin twin holding the P4K name (REQ-P4K-077)** and **an invisible Wiki-only twin
    (REQ-P4K-078)** that nothing references are deleted; the Wiki-only twin first hands its Wiki
    identity to the material a UEX or refinery-screen alias names. Both run in the APPLY, not the
    SC Wiki sync: it reads the rows, the aliases and the references in one transaction under the
    lock both syncs share, and the cases these twins raised are settled in the same run.
11. **The member/catalogue split of the material references.** `MaterialReferenceParticipant` is
    answered by the member modules only; the catalogue reads its own references
    (`CatalogueMaterialReferences`), so a member's reference keeps a case open and a catalogue
    row's only turns a deletion into a hide.
12. **A replaced blueprint output name stays a name of its product (REQ-P4K-079).** An element
    collection of `blueprint` (`blueprint_former_name`, V437) keeps it, and the key upgrade of the
    members' fallback keys resolves through it, so a key of the old name moves in the same APPLY;
    a name another product carries stays ambiguous. V437 backfills the names earlier APPLYs
    replaced from their change reports where the old name belonged to one product. The
    personal-blueprint import's `blueprint_external_alias` was not used: it maps a Game.log name
    the member's import learned, and the key upgrade only moves its keys.

A merge of two visible, priced materials (Party Favors / Fireworks) needs a material merge that
moves references across many tables and stays out of these rules.
