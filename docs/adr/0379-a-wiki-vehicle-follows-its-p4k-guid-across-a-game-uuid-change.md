# ADR-0379 — A Wiki vehicle follows its P4K GUID across a game UUID change

- **Status:** Accepted
- **Date:** 2026-11-14
- **Deciders:** @greluc
- **Related:** spec [`data-persistence.md`](../specs/data-persistence.md) (REQ-DATA-014,
  REQ-DATA-026) · [ADR-0307](0307-uex-vehicles-reach-their-ship-type-through-a-curated-class-name-alias.md) ·
  [ADR-0361](0361-a-wiki-manufacturer-follows-its-uuid-change-and-a-wiki-duplicate-becomes-an-alias.md)

## Context

Since game build 4.10.3 every SC Wiki sync has ended `SHIP_TYPE = PARTIAL`, and `scwiki_sync`
fails daily. There are four identity conflicts:

- **Galaxy:** three Wiki vehicles carry the name *Galaxy*. The stored Galaxy ship type is still
  linked to a game UUID that the Wiki no longer lists. A shared name never links by name, and a
  holder outside the census blocks the run.
- **Hammerhead:** the Wiki no longer lists `AEGS_Hammerhead`. It lists only `AEGS_Hammerhead_GS`,
  under the name *Hammerhead*. The stored Hammerhead row has another class name and a game UUID the
  census lacks, so the variant collides with it by name.

The P4K import review resolves a ship's GUID conflict by setting `p4k_uuid`. It never writes
`external_uuid`. The Wiki vehicle sync looked only at `external_uuid`, the UEX alias and the name,
so even a reviewed P4K import could never clear the conflict. The Wiki syncs for manufacturers,
blueprints and items already resolve a Wiki UUID through `p4k_uuid`; vehicles were the exception.

## Decision

1. **The Wiki vehicle sync resolves `p4k_uuid` second**, after `external_uuid` and before the UEX
   alias and the name. Wiki vehicle UUIDs and P4K GUIDs are the same game identifiers.
2. **A game UUID change follows the P4K GUID.** When the row found through `p4k_uuid` holds an
   `external_uuid` that the census no longer carries, it moves to the Wiki vehicle's UUID and the
   move is reported as `VEHICLE_RELINKED`. A game UUID the census still carries is never moved.
3. **A holder whose P4K GUID is in the census covers a variant** that shares its name. The outcome
   therefore does not depend on which of the variants the sync writes first.
4. **A name held by a departed vehicle of another class does not block the run.** Departed means
   the holder's game UUID is absent from the census; another class means both class names are known
   and differ. The Wiki vehicle stays unlinked and is reported as `SOURCE_IDENTITY_CONFLICT`, and
   the census stays complete. An unknown or equal class name still blocks.

## Consequences

- Once the P4K import of the current build has attached the new Galaxy GUID in its review, the next
  Wiki sync relinks the Galaxy, and its variants count as covered.
- The Hammerhead conflict stays visible in the sync report but no longer fails the job. The
  departed Hammerhead row is tombstoned as a Wiki row by the REQ-DATA-014 sweep, like any other
  vehicle the Wiki stopped listing.
- An unlinked Wiki vehicle stores nothing, so leaving it unlinked cannot hide a row from the sweep.
  The guard against wrong tombstones (REQ-DATA-014) is unchanged.
