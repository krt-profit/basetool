# ADR-0309 — Blueprint stat base values come from the SC Wiki item payload

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** @greluc
- **Related:** REQ-DATA-027 · REQ-INV-069 · REQ-INV-009 · ADR-0195 (the item sync's kind passes) ·
  migration `V311`

## Context

A blueprint's requirement groups carry modifiers: the stat a slot changes (`property_key`, e.g.
`health_maxhealth`) and the factor an ingredient's quality applies (`×0,90` … `×1,10`). „Meine
Blueprints" showed only that factor. What a member wants to know is the value the crafted item ends
up with — „Integrity 550 → 605" — and that needs the item's own base value, which the Basetool did
not store anywhere: `game_item` has no stat columns, and the blueprint sync captures only the
modifier bands.

Two sources carry item stats:

- **The SC Wiki item payload.** `GET /api/items/{uuid}` and the rows of the kind list endpoints
  (`/api/weapons`, `/api/vehicle-weapons`, `/api/vehicle-items`, `/api/armor`, `/api/clothes`) carry
  stat blocks: `durability`, `personal_weapon`, `vehicle_weapon`, `mining_laser`, `shield`,
  `power_plant`, `cooler`, `quantum_drive`, `radar`, `tractor_beam`, `weapon_modifier`, `clothing`,
  `suit_armor`, `temperature_resistance`, `radiation_resistance`. The residual `/api/items` list rows
  carry none. The item sync already reads these payloads every day.
- **The P4KX catalog export.** `Item.stats` covers health, weapons, shields, quantum drives, power
  plants, coolers and missiles, but no armour or clothing yet, and a blueprint's `PropertyModifier`
  points at a `GameplayProperty` that names no stat path. The Basetool's P4K import reads none of it.

A survey on 2026-10-23 of all 1617 SC Wiki blueprints found 24 distinct property keys. 21 of them map
to an SC Wiki field, and the kind list rows alone carry a value for at least 98 % of the blueprint
outputs per key; 65 outputs are served only by the residual list. The three recoil keys
(`weapon_recoil_kick`, `_handling`, `_smoothness`) have no field in either source.

## Decision

1. **The SC Wiki item payload is the only source, for every stat.** It covers all 21 mappable keys
   today, including the armour and clothing keys that make up most blueprints, and it arrives through
   a sync that already runs. P4KX would add nothing the Wiki lacks and would need a second import
   path plus a property-to-stat mapping it does not publish.
2. **The mapping is an enum.** `BlueprintStatProperty` lists each mapped property key with its unit;
   `ScWikiItemStatExtractor` reads each one through an exhaustive switch, so a new constant fails the
   build until it has a field. A key the enum does not list keeps the multiplier-only display.

   | Property key | Field | Unit |
   | --- | --- | --- |
   | `health_maxhealth` | `durability.health` | — |
   | `weapon_damage` | `mining_laser.laser_power.max`, else `personal_weapon.damage_per_shot`, else `vehicle_weapon.damage_per_shot` | — |
   | `weapon_firerate` | `personal_weapon.rpm` | rounds per minute |
   | `shield_maxhealth` | `shield.max_health` | — |
   | `itemresource_powergeneration` | `power_plant.power_segment_generation` | — |
   | `itemresource_coolantgeneration` | `cooler.coolant_segment_generation` | — |
   | `quantum_speed` | `quantum_drive.standard_jump.drive_speed` | m/s |
   | `quantum_fuelrequirement` | `quantum_drive.quantum_fuel_requirement` | — |
   | `radar_minaimassistdistance` / `radar_maxaimassistdistance` | `radar.aim_assist.distance_min_assignment` / `distance_max_assignment` | m |
   | `weapon_tractor_force` / `weapon_tractor_maxvolume` | `tractor_beam.max_force` / `max_volume` | — |
   | `weapon_tractor_maxdist` / `weapon_tractor_fullstrengthdist` | `tractor_beam.max_distance` / `full_strength_distance` | m |
   | `weapon_hullscraping_radius` / `weapon_hullscraping_speed` | `weapon_modifier.salvage.radius_multiplier` / `salvage_speed_multiplier` | — |
   | `weapon_hullscraping_efficiency` | `weapon_modifier.salvage.extraction_efficiency` | fraction |
   | `armor_damagemitigation` | `1 − clothing.damage_resistance.physical.multiplier` (else `suit_armor.…`) | fraction |
   | `armor_temperaturemin` / `armor_temperaturemax` | `temperature_resistance.min` / `max` (else `clothing.temp_resistance_min` / `max`) | °C |
   | `armor_radiationdissipation` | `radiation_resistance.radiation_dissipation_rate` | — |

3. **Stored per game item in `game_item_stat`**, one row per item and property, written only for a
   craftable item, never for a zero value, and never wiped by a payload that carries no value.
4. **The full run completes what the list rows miss.** After its kind passes, the item sync fetches
   the detail of every active blueprint output that still has no row.
5. **The value at a quality is base × factor, for every unit.** The damage mitigation is stored as
   the fraction the armour absorbs, so a factor above one raises it; a minimum temperature is
   negative, so a factor above one widens the range. Both are the directions the factors' better-when
   hints describe. The view shows „base → value" with the unit once at the end, and the factor beside
   it.

## Consequences

- The recipe view shows real values for 21 of 24 stats; the recoil stats stay factor-only until a
  source publishes them.
- A full item sync makes up to a few dozen extra detail requests a day (the outputs no kind list
  serves, plus those whose payload has no mappable stat — containers, cargo, attachments). A Wiki
  field rename would empty the stored values silently, so the gauge
  `basetool_catalogue_items_with_stats` and the alert `ScWikiItemStatsMissing` watch for it.
- The mitigation reading assumes the factor scales the absorbed share, not the damage that passes.
  Should the game prove otherwise, only the stored transform in `ScWikiItemStatExtractor` changes.

## Alternatives considered

- **P4KX `Item.stats` first, the Wiki for armour.** Two import paths and two mappings for the same
  table, while P4KX covers a subset of what the Wiki already serves, and a `GameplayProperty` names no
  stat path to map from. Rejected; P4KX can become a second writer of the same table once it carries
  armour and a stat path.
- **A detail fetch for every blueprint output on every run.** About 1500 extra requests a day for
  values the kind passes already deliver. Rejected in favour of the targeted follow-up.
- **Computing the value on the server.** The factor moves with a slider in the browser; the server
  would have to send a value per quality step. The base value and the existing factor curve are
  enough.
