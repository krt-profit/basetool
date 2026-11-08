# ADR-0341 — The P4K catalogue leads a ship type's base data and ship types match by class name before name

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-031, REQ-P4K-032), [`docs/specs/p4k-catalogue.md`](../specs/p4k-catalogue.md) ·
  `REQ-XCH-012` ([`external-exchange.md`](../specs/external-exchange.md)) ·
  `REQ-XCH-026` / [ADR-0219](0219-the-exchange-contract-grows-additively-under-a-path-major-version.md) ·
  `REQ-DATA-015` ([`data-persistence.md`](../specs/data-persistence.md)) · issue #2897 · map #2884 ·
  [ADR-0307](0307-uex-vehicles-reach-their-ship-type-through-a-curated-class-name-alias.md) ·
  [ADR-0335](0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

Three sources write `ship_type`: UEX, the SC Wiki and the P4K import. The p4kx catalogue's `ships`
kind carries per ship the GUID, `className`, `manufacturerGuid`, the names, `crewSize`, `role`,
`career`, `mass`, `hullHealth`, `size`, `vehicleKind`, `insurance`, `cargoScu` with
`cargoGridsWithIssue` (above 0, `cargoScu` is a lower bound), `playability` (`player`, `ai`,
`mission`, `special` or `null`) and `variantOf`, the GUID of the base of the ship's variant group.
It exports no dimensions. Its `shipStoreSlugs` kind is curated, not extracted.

The hangar import and the exchange both resolve ship types through `ShipTypeMatcher`. REQ-XCH-012
resolves a `SHIP_TYPE` reference by `bt`, `scRecord` (`class_name`), `scGuid`, `uexId`, `locKey` and
`name`, and the `name` step delegates to the matcher, which indexes normalised names with
`putIfAbsent`: two ship types with the same normalised name resolve silently to the first one.

## Decision

1. **The P4K leads** a matched `ship_type`'s identity (unique `p4k_uuid`), `class_name`,
   manufacturer (`manufacturerGuid`), `name`, `name_de`, `crewSize`, `role`, `career`, `mass`,
   `hullHealth`, `size`, `vehicleKind` and the new `insurance` facts. The Wiki and UEX write these
   only on rows without a P4K GUID; disagreements go to `external_sync_report`.
2. **Cargo.** The P4K leads `cargoScu` only when `cargoGridsWithIssue` is 0. Above 0 the stored
   value stays, and a difference goes to `external_sync_report`.
3. **UEX keeps** store URLs, purchasability and prices. `shipStoreSlugs` serves only as a
   cross-check of the UEX store URL into `external_sync_report`. Dimensions stay with the Wiki.
4. **Seeding.** `playability = player` is created behind the per-run checkbox (ADR-0335); `ai` and
   `mission` are never created and only counted; an unmatched record with `null` or `special` goes
   on the review list.
5. **Matching order** for the hangar import and the exchange (`ShipTypeMatcher`, REQ-XCH-012
   `SHIP_TYPE`): P4K GUID / class name first, then the UEX id and the curated alias of ADR-0307,
   then the name as the last step. A name that matches several ship types answers `ambiguous` with
   at most ten `{bt, name}` candidates, never the first duplicate.
6. **Variants.** A class name resolves to the `ship_type` that carries exactly that class name. If
   none does and the record has `variantOf`, it resolves to the ship type of the group base.
   Variants are never merged automatically.
7. **Exchange.** Narrowing what `name` resolves is a behaviour change within `v1`: REQ-XCH-012,
   `docs/exchange/changelog.md` and the check against `docs/exchange/versioning.md` ship with it,
   and `ExchangeResolveServiceTest` covers precedence, duplicate names and a variant's class name.

## Consequences

- A ship type's base data follows the game; store and price data stay with UEX, dimensions with the
  Wiki.
- A ship whose cargo grids cannot all be read keeps its stored cargo value.
- An exchange client or hangar import that relied on a duplicate name now gets `ambiguous` with
  candidates instead of the first match; no wrong ship is written.
- A variant without a ship type of its own lands on its group base's ship type; the P4K import keeps
  the variant groups in `ship_type_p4k_variant` for that (migration `V350`).
- The rules go into the P4K catalogue spec, `docs/specs/p4k-catalogue.md`, and REQ-XCH-012 moves
  with the change.

## Alternatives considered

- **Class-name matching replaces the name fallback.** Rejected for matching that precedes it: a
  `name`-only reference from an admitted client would turn from `resolved` into `ambiguous` or
  `unmatched` and drop out of the sync.
- **A variant's class name always resolves to its group's canonical type.** Rejected: a variant
  with its own `ship_type` resolves to itself; the base is the fallback only.
- **Take `cargoScu` whenever the catalogue has a number.** Rejected: above 0 grids with an issue
  the number is a lower bound and would understate the ship's cargo.
