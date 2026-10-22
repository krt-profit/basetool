# ADR-0307 — UEX vehicles reach their ship type through a curated class-name alias

- **Status:** Accepted
- **Date:** 2026-10-22
- **Deciders:** @greluc
- **Related:** REQ-DATA-026 · REQ-DATA-004 / ADR-0023 (the same pattern for manufacturers) ·
  migration `V310` · #2884 · #2897

## Context

Three sources write `ship_type`: the UEX vehicle sync, the SC Wiki vehicle sync and the P4K import.
Each finds an existing row by the in-game UUID (`external_uuid`), then by its own key, then by the
exact name, and seeds a new row when nothing matches.

UEX serves some vehicles without the in-game UUID, and under a name that differs from the in-game
one. For those, nothing ties the UEX record to the row the Wiki and the P4K import write, so each
side seeds its own. A production read on 2026-10-22 found twelve such pairs, among them
`San tok.Yāi` / `San'tok.yāi`, `Ares Inferno Starfighter` / `Ares Star Fighter Inferno` and
`Nova Tank` / `Nova`. Hangar ships, mission units and items were bound to both halves: the Ares
Inferno owners' ships to the UEX half, the exchange's `scRecord` resolution to the other.

No automatic rule separates these pairs from genuine variants. A name normalised to its letters
finds `San'tok.yāi` but not the Ares pairs, and token overlap ties a Wikelo special or a Best in
Show edition to its base ship.

## Decision

1. **A curated alias table ties a UEX vehicle id to a game class name.** `ship_type_uex_alias`
   holds one row per vehicle, keyed by the UEX vehicle id, with the class name unique ignoring
   case. The class name, not a `ship_type` id, is the target, because both the Wiki and the P4K
   import carry it and it survives a row being re-seeded on a fresh database.
2. **All three sources consult it before they seed.** The UEX sync looks up the class name after the
   UUID and the UEX id, and uses it only when exactly one row carries it. The Wiki sync and the P4K
   import look up the UEX vehicle id for the class name and take the row that holds it. Whichever
   source runs first, the second finds the first one's row.
3. **The table is curated by migration.** No sync and no request writes it. A new pair is a new
   migration row after the same production check.
4. **V310 merges the pairs that exist.** The half the Wiki or the P4K import wrote survives, with
   its in-game name; it takes the UEX columns and the UEX vehicle id, and every reference moves to
   it. The UEX half is deleted.

## Consequences

- A split ship type in the hangar, the mission planner and the exchange becomes one row; members of
  the affected pairs see the in-game name from then on.
- A hangar import whose name only matched the UEX spelling can stay unmatched, where the in-game
  name's tokens do not cover it (`Ares Inferno Starfighter`, the ATLS IKTI names). The import never
  deletes a ship, so the result is a skipped line, which the member sees.
- An exchange client that stored the deleted half's `bt` resolves through its `scRecord` or name on
  the next call.
- New pairs need a person to notice them. The `UEX_ONLY` rows without a class name are the place
  to look.

## Alternatives considered

- **Match by the normalised name or by token overlap.** Finds a fraction of the pairs and ties
  genuine variants together.
- **Match UEX's `name_full` against the Wiki's `game_name`.** Equal for `San'tok.yāi` after
  normalisation, different for the Ares and ATLS pairs.
- **Key the alias on the `ship_type` id.** Cannot be seeded before the row exists, and breaks when a
  row is re-seeded.
- **Keep the UEX name on the survivor.** Keeps today's display for the affected members, but writes
  a UEX spelling into the row whose identity the P4K import is set to lead (#2884); left for #2897,
  which decides the source precedence for a ship's identity.
