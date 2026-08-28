# ADR-0148 — An inbound mapping binds only what the upstream serves, and a phantom field is deleted rather than left writing null

> **Status:** Accepted · **Date:** 2026-08-28 · **Deciders:** @greluc
> **Related:** `REQ-DATA-015`, `REQ-DATA-014`, ADR-0147 (the page-walk census), `ScWikiItemSyncService`,
> `UexVehicleService`, `UexUniverseSyncService`, `UexCommodityService`

## Context

Every inbound catalogue DTO is `@JsonIgnoreProperties(ignoreUnknown = true)`. That is the right
setting for a third-party feed — a new upstream field must not break the sync — but it makes the
opposite mistake silent: a component bound to a name the payload does **not** carry decodes to
`null` on every row of every run, and the sync services then write that `null` onto their entities.
Nothing fails, nothing warns, and the column simply never holds a value.

A field-by-field comparison of both catalogue APIs against their live payloads on 2026-08-28 found
three live instances:

- **`ScWikiDimensionDto` binds `{x, y, z}`.** The Wiki serves `{width, height, length, volume,
  true_dimension, cargo_dimension, ui_dimension, …}`. `game_item.dimension_x/y/z` had therefore
  never held a single value since R4.
- **`UexVehicleDto` binds eleven fields UEX does not serve** — `crew_min`, `crew_max`, `mass_total`,
  `vehicle_inventory`, `ore_capacity`, `max_medical_tier`, `health`, `shield_hp`, `url_wiki`,
  `description`, `description_de` — and `UexVehicleService` wrote every one of them onto
  `ship_type`, explicitly "so a missing field on the UEX side clears the local row to null". Eight
  columns were consequently always empty, and two of them, `vehicle_inventory_scu` and
  `description_en`, are also written by the **SC-Wiki** vehicle sync: the Wiki fills them when
  blank, UEX blanks them again on its next run, every cycle.
- **`code` on five UEX universe DTOs** (`factions`, `jurisdictions`, `outposts`, `poi`,
  `space_stations`) and `slug` / `type` on `UexCommodityDto`. `UexFactionDto` additionally binds
  `is_available_live`, whose absence its `checkIsAvailableLive()` turned into a hard `false` — a
  claim UEX never made.

The crew range is the interesting case: UEX does serve it, as the compact `crew` string (`"1"`,
`"1,2"`). The mapping was not obsolete, it was mis-shaped.

## Decision

**A DTO binds a field only if the live payload carries it. When it does not, the component and its
write are deleted — never kept as a null-writer.**

1. Phantom components are **removed** from the record, not left in place "in case it comes back".
   A component that decodes to `null` on every row is not a defensive placeholder; it is an
   instruction to erase a column.
2. **A sync never writes a column it has no value for.** Where another source fills the same column
   (`vehicle_inventory_scu`, `description_en`), not writing is what keeps that source's value.
   Where no source fills it, the column is simply left alone. The columns themselves stay — dropping
   them is a migration with no benefit, and a future source may fill them.
3. **Where the data exists in another shape, it is re-derived rather than dropped.**
   `UexValues.parseCrew` splits UEX's `crew` string back into the min / max bounds the two columns
   want, so removing `crew_min` / `crew_max` restores the field instead of retiring it.
4. **The mapping is pinned by a test, not by vigilance.** `ExternalCatalogueMappingTest` reflects
   over every inbound catalogue record and asserts that each bound JSON name appears in the key set
   its endpoint returned when it was last captured. It checks one direction only: every mapped name
   must exist upstream. The other direction — upstream fields we do not bind — is a deliberate,
   ever-growing choice, so asserting on it would be noise.

## Consequences

- `game_item.dimension_x/y/z` starts holding real values on the next item sync; the ship-type
  crew range starts holding real values on the next vehicle sync; `ship_type.description_en` and
  `vehicle_inventory_scu` stop being erased every cycle.
- Eight `ship_type` columns and six other columns are now written by nobody. That is not a
  regression — they were already always null — but it is now visible in the code rather than hidden
  behind a setter call, and the ADR records why they are empty.
- The mapping test needs its key sets re-captured when an upstream endpoint changes shape. That is
  the intended maintenance: a failure means "go look at the live payload", which is exactly the step
  that was skipped for three years.
- `UexFactionDto.checkIsAvailableLive()` is gone. Its two siblings (`checkIsPiracy`,
  `checkIsBountyHunting`) stay — those flags are served.

**Rejected.** *Keeping the components and skipping the write when null* — indistinguishable at the
call site from a field that is served but genuinely empty, and it leaves the record claiming the
upstream has a field it does not. *Dropping the now-unwritten columns* — a migration that buys
nothing and forecloses a future source. *Widening the mapping to whatever the payload happens to
carry* — the syncs want specific facts, not a mirror of the upstream. *Treating this as a one-off
fix without the test* — the same class of bug had been live for years in three separate places,
which is what a missing invariant looks like.
