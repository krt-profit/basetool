# What the p4kx catalogue v0.5.0 carries for route calculation

Research for [#3452](https://github.com/krt-profit/basetool/issues/3452) (map
[#3450](https://github.com/krt-profit/basetool/issues/3450), trade-route tool): what the p4kx
catalogue carries for route calculation as of the v0.5.0 milestone, and what is missing.

Read on 2026-11-15 from `p4kx-org/p4kx` (source code, contract docs, JSON Schemas, ADRs, issues
and pull requests) and from this repository at `732fd614f`. Nothing from p4kx was executed; no game
Archive was opened. Every claim names the file and line or the issue it was read from.

## The three states of p4kx this note distinguishes

| Label | Commit | What it is |
|---|---|---|
| **v0.4.2** | tag [`v0.4.2`](https://github.com/p4kx-org/p4kx/tree/v0.4.2) (`4dc2c5de`) | The released contract, `schemaVersion` 2. The Basetool vendors this schema. |
| **main** | [`f43f12f3`](https://github.com/p4kx-org/p4kx/tree/f43f12f3cd9ac73137ae593cfc6b9bd5ea0a27b1) | 53 commits after v0.4.2. Route-relevant contract unchanged (see below). |
| **v0.5.0 branch** | [`e6c11832`](https://github.com/p4kx-org/p4kx/tree/e6c118325307452b77a973faf695089998d15009) on `codex/implement-v0.5.0` | The v0.5.0 work: 854 commits, **draft** PR [p4kx#1117](https://github.com/p4kx-org/p4kx/pull/1117), not merged, not tagged. `schemaVersion` 3. |

- **Nothing route-relevant was merged on main for v0.5.0.** `git diff v0.4.2 f43f12f3 --
  docs/basetool-catalog.md schemas/` shows two hunks only: strict mission location conditions
  (#1011) and `MiningGlobalParams.compositionMassMultiplier` (#846). For this note main equals
  v0.4.2.
- **The milestone [v0.5.0](https://github.com/p4kx-org/p4kx/milestone/17)** holds 158 issues and
  PRs (127 closed, 31 open). Its catalog issues were closed as *completed* between 2026-11-11 and
  2026-11-15 against the v0.5.0 branch, while main's last commit is from 2026-11-07. "Closed" on
  this milestone therefore means "implemented on the unmerged branch", not "released".
- **The branch is still moving.** PR #1117 says "the complete wire migration is still in progress"
  and stays a draft; open milestone issues include
  [#1048](https://github.com/p4kx-org/p4kx/issues/1048) (export expansion tracker),
  [#1125](https://github.com/p4kx-org/p4kx/issues/1125) (per-value encryption origin),
  [#1151](https://github.com/p4kx-org/p4kx/issues/1151) (transport size) and
  [#1116](https://github.com/p4kx-org/p4kx/issues/1116) (release gate). Field paths quoted from
  the branch below can change before the tag.

## Short answer

- **v0.5.0 is a breaking schema 3.**
  [ADR-0339](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/adr/0339-catalog-v3-and-serialization-schema.md)
  on the branch: "The 0.5.0 catalog uses schema version 3", with no version-2 export mode
  ([`docs/catalog.md` L12-13](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog.md#L12-L13)).
  `items[].stats` is retired (ADR-0339 L7-8; the branch schema's `Item` has no `stats`), and the
  root `starmapObjects` kind moves to `definitions.starmapObjects` + `placements.starmapPlacements`
  ([`docs/catalog-v3-data-map.md` L103, L149](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-v3-data-map.md#L103)).
  The Basetool refuses `schemaVersion` 3 today (REQ-P4K-001); adopting it is
  [#3107](https://github.com/krt-profit/basetool/issues/3107), open and blocked by the p4kx release.
- **Quantum drives are complete enough for a travel-time model already in v0.4.2** (speed, spool,
  both acceleration stages, cooldown, engage speed, fuel requirement, jump range). The Basetool
  imports only speed and fuel requirement. v0.5.0 adds the spline parameter set, calibration, heat
  and boost.
- **There is no jump-tunnel graph in any version.** Jump points are starmap objects with a system
  and an entity name such as `jumppoint_stanton_pyro`; p4kx deliberately refuses to pair endpoints
  by name, and v0.5.0's `projections.jumpConnections` is empty by design.
- **Armistice and monitored zones, freight elevators, loading docks, auto-load and loading times
  are in no version of the catalogue,** and no p4kx doc, code or issue names an Archive source for
  them. UEX stays the only source the Basetool has for these.
- **Cargo grids:** v0.4.2 exports only the summed `cargoScu`. v0.5.0 exports each grid's
  dimensions, the inventory container's interior dimensions and its minimum and maximum permitted
  item size, and the crate entity a commodity uses per box size.
- **Fuel capacities:** none in v0.4.2; v0.5.0 exports each tank's typed capacity, joined to a ship
  through the loadout.
- **Shops:** v0.4.2 has item lists only, explicitly without prices or stock. v0.5.0 exports the
  inventory files' `BuyPrice`, `SellPrice`, `CurrentInventory` and `MaxInventory` as *offline
  design-time* values. Live prices, stock and restock are server-side; the Archive has no
  per-terminal commodity list.

## Quantum drives

| Fact | v0.4.2 field path | v0.5.0 branch | Basetool today |
|---|---|---|---|
| Speed | `items[].stats.quantumDrive.driveSpeed` | `configurations.quantumDrives.rows[].standard.driveSpeed` (m/s), and `.spline.driveSpeed` | `game_item_stat` `QUANTUM_SPEED` |
| Spool time | `….quantumDrive.spoolUpTime` | `….standard.spoolUpTime` (s) | not imported |
| Acceleration stages | `….stageOneAccelRate`, `….stageTwoAccelRate` | same names under `standard` / `spline`; units "retain their original representation" | not imported |
| Engage speed | `….engageSpeed` | `….standard.engageSpeed` (m/s) | not imported |
| Cooldown | `….cooldownTime` | `….standard.cooldownTime` (s) | not imported |
| Fuel use | `….fuelRequirement` (`quantumFuelRequirement`, as stored) | `….fuelRequirement` (raw) | `QUANTUM_FUEL_REQUIREMENT` |
| Range | `….jumpRange`, `….disconnectRange` | same, metres | not imported |
| Calibration, heat, boost | none | `standard`/`spline` calibration fields, `heat` (five thermal energy draws), `boost` (`maxBoostSpeed`, `minJumpDistance`, …) | none |

Sources: v0.4.2
[`docs/basetool-catalog.md` L336](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L336)
(the block) and L427-429 (`jumpRange` is `f32::MAX` on an unbounded development drive; read it as
unlimited); v0.5.0
[`docs/catalog-quantum-drives.md` L8-49](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-quantum-drives.md#L8-L49)
([#1055](https://github.com/p4kx-org/p4kx/issues/1055)); Basetool
`backend/…/catalogue/internal/p4k/P4kItemStats.java` L113-120 binds only `driveSpeed` and
`fuelRequirement`. LIVE 4.10 has 63 Records with `SCItemQuantumDriveParams`
([`docs/coverage.md` L160](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/coverage.md#L160)).

- **Units.** v0.4.2 states no units; the v0.5.0 doc states m/s for the speeds, seconds for spool and
  cooldown, metres for the ranges, and nothing for the acceleration rates and the fuel requirement.
- **No formulas.** p4kx exports raw fields only (ADR-0065): "Fuel, acceleration, travel time and
  efficiency calculations belong to consumers" (catalog-quantum-drives.md L44-45). No p4kx source
  states how `fuelRequirement` turns into fuel per distance.
- **Migration.** On v0.5.0 the flat v0.4.2 values map to `standard.value`; the selection is per
  physical component slot (catalog-quantum-drives.md L47-50). REQ-P4K-030 reads `items[].stats`, so
  it breaks with schema 3; #3107 does not list this move yet.

## Starmap

### Positions and reference frames

- **v0.4.2:** `starmapObjects[].position` is `[x, y, z]` in metres "in the frame of the system
  container, rounded to a millimetre", composed from the object containers with rotations
  ([L1746-1761](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L1746-L1761)).
  Also `system`, `parentGuid`, `placedUnderGuid`, `entityName`, `type`, `typeGuid`, `hideInStarmap`.
  The positions are static authoring transforms; no field describes orbits or a body's rotation
  over time.
- **Coverage:** on LIVE (2026-10-22) 1,239 of 2,093 objects have a position; 816 of the rest are
  placed by no container at all, among them the `Anomaly` jump-point Records of Pyro and Nyx
  ([L1786-1807](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L1786-L1807)).
- **4.11 risk:** on the Tech Preview of SC 4.11, Starchitect-rebuilt system containers make v0.4.2
  lose the position, `system` and `entityName` of 52 objects
  ([#844](https://github.com/p4kx-org/p4kx/issues/844), fixed on the v0.5.0 branch only).
- **v0.5.0:** the definition (`definitions.starmapObjects`) and the placement
  (`placements.starmapPlacements`: `position`, `system`, `entityName`, `placedUnderGuid`) are
  separate tables. New and useful for routing: `quantumTravelData` (`obstructionRadius`,
  `arrivalRadius`, `adoptionRadius`, `arrivalPointDetectionOffset`, `subPointRadiusMultiplier`,
  source units kept), `blockTravel`, `hideInWorld`, and on the type `validQuantumTravelDestination`
  and `ignoreAsRoutingObstacle`
  ([`docs/catalog-starmap-definitions.md` L15-26, L47-68](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-starmap-definitions.md#L15-L26);
  schema `StarmapQuantumTravel`, `StarmapTypeDefinition`; [#1067](https://github.com/p4kx-org/p4kx/issues/1067)).
- **Basetool today:** `starmap_object` holds GUID, type, parent, position, system, hideInStarmap and
  name (REQ-P4K-034; `P4kStarmapObjectDto` L43-52). `entityName`, `placedUnderGuid` and `typeGuid`
  are skipped.

### Jump points and the tunnel graph

- **v0.4.2:** jump points are starmap objects of type `JumpPoint` or `Anomaly`
  ([ADR-0067 L38-40](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/adr/0067-the-archive-has-no-reliable-released-marker-for-starmap-objects.md#L38-L40)).
  A placed one carries its `system` and the `entityName` of its Child, e.g. `jumppoint_stanton_pyro`
  ([L1744](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L1744)). No field
  links two endpoints or two systems.
- **p4kx refuses name pairing.** #1068's acceptance: "Similar or reversed endpoint names alone never
  produce a confirmed connection" ([#1068](https://github.com/p4kx-org/p4kx/issues/1068)); it names
  the ScDataDumper name-pairing as the approach it rejects.
- **v0.5.0:** `placements.jumpEndpoints` holds each endpoint occurrence with `system`, local and
  world pose, the gate evidence of its SOCpak, `declaredPartner`, `targetPath` and
  `targetCandidates`. `projections.jumpConnections` "reports absence"; "Both states currently carry
  empty rows", because "route pairing needs an authored target relation and direction"
  ([`docs/catalog-jump-points.md` L21-55](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-jump-points.md#L21-L55));
  the schema's `JumpConnection` has no properties.
- **So a system graph is the Basetool's own:** pair endpoints by `entityName` (a heuristic p4kx
  rejects), take UEX's jump-point data, or curate it.
- **Basetool today:** jump points are imported as P4K-only objects and are no trade place
  (REQ-P4K-034); `terminal.is_jump_point` comes from UEX.

### Jump-point operating and tunnel transit data

- **v0.4.2:** none.
- **v0.5.0:** `configurations.jumpPointConfigurations` per `SJumpPointParams` component:
  `requiredFuel` and `linkingRange` ("the source establishes storage but leaves units
  unconfirmed"), `largestShipSize` (a Reference to a `LandingPadSize` Record), push-area and effect
  settings (catalog-jump-points.md L3-10). `configurations.globalJumpPointConfigurations` holds
  `GlobalJumpPointParams` (alignment, closing and pull-in timing) and `GlobalJumpTunnelHostParams`
  (tunnel acceleration, generation, sections, exit) (L12-19). The ship side is
  `configurations.jumpDrives`, `jumpDriveFlightTuning` and `jumpTunnelForces`
  ([`docs/catalog-jump-drives.md`](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-jump-drives.md),
  [#1077](https://github.com/p4kx-org/p4kx/issues/1077)).
- **No transit time or tunnel length** is a field anywhere. The tunnel is generated from sections
  with probabilities (L17-19); a transit time would be a model of the Basetool's.

### Armistice and monitored zones

- **Not exported in any version, and no p4kx source names an Archive source for zone geometry.**
  Searched: the v0.4.2 contract, main, every doc, the schema and `crates/p4kx-export/src` of the
  v0.5.0 branch, and the milestone's issue titles, for armistice, green zone, no-fire, monitored and
  comm array.
- **Closest facts on v0.5.0:** a starmap object's `jurisdiction` Reference and the
  `definitions.jurisdictions` table (fines, `prohibitedGoods`, `prohibitedResources`,
  `controlledSubstanceClasses`, `maxStolenGoodsPossessionScu`, impounding)
  ([`docs/catalog-law.md` L29-46](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-law.md#L29-L46),
  [#1072](https://github.com/p4kx-org/p4kx/issues/1072)). The field name `greenZoneCheckRange` of a
  quantum interdiction device (`crates/p4kx-export/src/catalog_quantum_interdiction.rs` L130 on the
  branch) shows the game knows green zones, not where they are.
- **Candidate p4kx issue:** survey the object containers and DataForge for armistice and monitored
  zone declarations. Basetool today: `is_armistice` on station, city, outpost and POI from UEX.

### Landing zones and location kind

- **v0.4.2:** the only kind is `starmapObjects[].type`, the `StarMapObjectType` name. LIVE types
  include `LandingZone`, `Manmade`, `Manmade_VisibleOnInteraction`, `Outpost`, `Outpost_InvalidQT`,
  `PointOfInterest`, `Asteroid_ValidQT`, `JumpPoint`, `Anomaly`, `Planet`, `Moon` (contract
  [L1787-1794](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L1787-L1794),
  ADR-0067 L38-43, and REQ-P4K-035 in `docs/specs/p4k-catalogue.md` L1352-1361, which takes its
  families "from the types of the LIVE catalogue"). The type does not separate
  station, city and outpost: REQ-P4K-035 has to map a city to `landingzone` or `manmade` (Levski), a
  space station to `manmade` (Everus Harbor), `manmade_visibleoninteraction` (the Lagrange
  stations) or `landingzone`, and an outpost to `outpost` or `manmade`.
- **v0.5.0 adds** the type's `classification` (a public short name), `amenities` (ordered
  References to `StarMapAmenityTypeEntry`: `name`, `icon`, `displayName`, `requiredEntity`),
  `respawnLocationType` and `locationHierarchyTag`
  ([catalog-starmap-definitions.md L9-13, L45-66](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-starmap-definitions.md#L45-L66)).
  LIVE 4.10 has 25 `StarMapAmenityTypeEntry` Records
  ([`docs/coverage.md` L105](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/coverage.md#L105));
  their names are not documented, so whether one means refuel, cargo or a pad is open.
- **Landing pads** (count, size per place) are not exported in any version. The `LandingPadSize`
  Record type exists (jump-point `largestShipSize` links to it).
- **Basetool today:** station, city and outpost come from which UEX parent name is set;
  `pad_types` and `is_landable` come from UEX.

## Ships

### Cargo grids, their dimensions and the largest container

- **v0.4.2:** `ships[].cargoScu` (the sum of the default Loadout's grids: an open container is
  `x * y * z / 1.953125` of its `interiorDimensions`, a closed one its capacity) and
  `cargoGridsWithIssue`
  ([L477-478, L528-529](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L528-L529)).
  The per-grid list (`port`, `dimensions`, `cells`, `shape`, `scu`) exists only in the separate
  `p4kx ships` export, not in the catalogue
  ([`docs/ship-base-data.md` L106-133](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/ship-base-data.md#L106-L133)).
  No outer ship dimensions (L551-555).
- **v0.5.0:** `configurations.facilities` kind `cargoGrid` (`SCItemCargoGridParams.dimensions`,
  `miningOnly`, `invisible`, …), `configurations.inventoryConfigurations` and
  `definitions.inventoryContainers` (`interiorDimensions`; for an open container `gridCellSize`,
  `minPermittedItemSize`, `maxPermittedItemSize`, `gridPosOffset`, `isExternalContainer`; for a
  closed one `capacity` in SCU, centi-SCU or micro-SCU), and `placements.facilityInstances` tying
  each to its ship and Loadout path
  ([`docs/catalog-inventory-storage.md` L7-34](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-inventory-storage.md#L7-L34),
  [`docs/catalog-ship-facilities.md` L53-104](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-ship-facilities.md#L53-L104),
  `crates/p4kx-export/src/inventory_open_container.rs` on the branch;
  [#1057](https://github.com/p4kx-org/p4kx/issues/1057), [#1063](https://github.com/p4kx-org/p4kx/issues/1063)).
- **Largest container per grid:** `maxPermittedItemSize` is a size vector, not an SCU class. Turning
  it into "fits a 16 SCU box" needs the crate's bounds (`configurations.itemBounds`) and the crate a
  commodity uses per box size: `definitions.resourceTypes.rows[].defaultCargoContainers`, slots
  `one_eighthSCU` … `thirtyTwoSCU` each a Reference to the crate entity
  ([`docs/catalog-resource-types.md` L59-71](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-resource-types.md#L59-L71),
  [#1069](https://github.com/p4kx-org/p4kx/issues/1069)). That derivation is the Basetool's.
- **Basetool today:** `ship_type.scu` from `cargoScu` when no grid has an issue (REQ-P4K-031);
  `container_sizes` from UEX; no grid dimensions.

### Default quantum drive in the port tree

- **v0.4.2:** `loadouts[].ports[]` with `types`, `itemGuid` and nested `ports`; the drive is the
  port whose item has `items[].type` `QuantumDrive`
  ([L1012-1062](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L1012-L1062)).
- **Basetool today:** stored as `ship_type_loadout_port` with the default item (REQ-P4K-040), so the
  default drive is already reachable; the drive's stats are the gap (see above).
- **v0.5.0:** `loadouts` stays; a port's `sourceConstraints.attachedItem.targetRecordIndex` joins
  the per-component tables such as `configurations.quantumDrives`
  ([`docs/catalog-fuel-capacity.md` L38-45](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog-fuel-capacity.md#L38-L45)).

### Fuel capacities

- **v0.4.2:** none; only the drive's `fuelRequirement`.
- **v0.5.0:** `configurations.resourceContainers[].capacity` (typed: `standardCargoUnits`,
  `centiSCU` or `microSCU`) and composition of each tank item, `configurations.fuelTanks`
  (hydrogen and quantum flow multipliers), `configurations.fuelIntakes` (`fuelPushRate`,
  `minimumRate`) (catalog-fuel-capacity.md L7-36; [#1061](https://github.com/p4kx-org/p4kx/issues/1061)).
  A ship's total is a consumer join through the loadout; #1061 explicitly leaves aggregation and
  range estimates out. Hydrogen use in flight is part of the IFCS tables (`IfcsFuelUsage`,
  [#1060](https://github.com/p4kx-org/p4kx/issues/1060)).
- **Basetool today:** `ship_type.fuel_quantum` and `fuel_hydrogen` from UEX (`UexVehicleService`
  L288).

## Shops and cargo handling

| Fact | v0.4.2 | v0.5.0 branch | Archive |
|---|---|---|---|
| Commodities a terminal trades | `shops[].itemGuids` where the inventory file resolves (294 of 1,184 shops on LIVE 4.10.197; 16 shops are `commodityExchange`) | unchanged, plus `declarations.shopInventoryResolution` (status per shop) | "a commodity has no per-terminal list in the Archive"; all 21 GUIDs of `inv_commex_tdd_area18` name no Record |
| Buy and sell price | never read | `definitions.shopInventories` line fields, `meaning: "offlineDesignTime"` | in the inventory files as design values; live prices are server-side |
| Current and maximum stock | never read | same table: current and maximum inventory | same |
| Restock rate | none | none | no field in the inventory files; `globalshopparams` (4 Records) not exported |
| Price ranges | none | only the offline buy and sell values | `globalshopcommoditydata` holds supply and demand level thresholds and box-size base prices; not exported |
| Freight elevator, loading dock, auto-load, loading times | none | none | not named by any p4kx source |

Sources: v0.4.2
[`docs/basetool-catalog.md` L2126-2232](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/basetool-catalog.md#L2126-L2232)
(L2209: "`BuyPrice`, `SellPrice`, `CurrentInventory`, `MaxInventory` and `RentalOfferings` are
never read or exported"); [`docs/shops-and-terminals.md` L6, L17, L30, L80-84, L112-117](https://github.com/p4kx-org/p4kx/blob/v0.4.2/docs/shops-and-terminals.md#L112-L117);
v0.5.0 [`docs/catalog.md` L2443-2493](https://github.com/p4kx-org/p4kx/blob/e6c118325307452b77a973faf695089998d15009/docs/catalog.md#L2443-L2493)
([#1070](https://github.com/p4kx-org/p4kx/issues/1070)).

- **Offline design values are not UEX.** The branch calls them "offline design settings"; the
  shops doc: "Live prices, stock and demand remain server-side". They could serve as a fallback or a
  plausibility bound, never as the live stock.
- **Archive key.** On v0.5.0 a catalogue written without a usable Archive key is "valid but
  incomplete: vehicle implementations, XML Loadouts, shop inventories and the like are absent"
  (`source.archiveKey`, catalog.md L176).
- **Shop kinds.** The container folders include a kind `cargo_admin` (6 containers)
  (shops-and-terminals.md L35-37); `shops[].kind` carries it. Nothing says what it offers.
- **Basetool today:** shops are imported with key, system, kind, brand, `commodityExchange` and the
  starmap link (REQ-P4K-042); `itemGuids` become "available at" rows on `game_item` (REQ-P4K-044);
  "Prices, stock and demand never come from the P4K" (REQ-P4K-042). Loading dock, freight elevator
  and auto-load stay with UEX (`terminal`).

## Delivery risks of v0.5.0 for the Basetool

- **Schema 3 adoption** is [#3107](https://github.com/krt-profit/basetool/issues/3107), open and
  blocked by the p4kx release. p4kx #1151 states "The Basetool schema-v3 migration is implemented
  and tracked in the Basetool repository"; this repository's `main` still refuses version 3
  (REQ-P4K-001), and #3107 is the only tracker found.
- **Size.** On LIVE a catalogue filtered to the starmap Records serialised to 2,811,174,669 bytes,
  above the Basetool's 1 GB decompressed limit; most of it is `placements.containerInstances`
  (1.73 GB) and `provenance.uses` (0.61 GB) ([#1151](https://github.com/p4kx-org/p4kx/issues/1151),
  open). The 64 MB compressed limit is not yet measured for a full export.
- **Starmap on SC 4.11** needs v0.5.0 (#844), so staying on v0.4.2 is not a stable option once 4.11
  reaches LIVE.

## Candidate p4kx issues

Not opened; for the owner to decide.

1. **Jump connections:** an authoritative system-to-system relation, if the Archive holds one beyond
   the endpoint's `targetPath` (v0.5.0 leaves `projections.jumpConnections` empty).
2. **Armistice and monitored zones:** survey object containers and DataForge for their declarations.
3. **Cargo handling at places:** survey for freight elevator, loading dock and auto-load
   declarations and any loading timing (none named by p4kx today).
4. **Landing pads per place:** count and `LandingPadSize` per station, city and outpost.
5. **Global shop parameters:** export the `globalshopparams` Records (supply and demand thresholds,
   box-size base prices, anything on restock).

## Open points for the decision tickets

- Whether the route tool waits for p4kx 0.5.0 and #3107, or uses v0.4.2 fields now (quantum drive
  stats beyond speed and fuel, `entityName`) and migrates later; the `items[].stats` move breaks
  REQ-P4K-030 either way.
- Where the jump-tunnel graph comes from: `entityName` pairing, UEX, or curation.
- Which travel-time model the Basetool owns: p4kx gives raw drive fields and, on v0.5.0, QT
  arrival and obstruction radii, but no formula and no units for the acceleration rates or the
  fuel requirement.
- Whether starmap positions are good enough as static points: no orbit or rotation data in any
  version; surface places move in game (inferred, not stated by p4kx).
- Whether offline `CurrentInventory` / `MaxInventory` have any use beside UEX stock.
- What the 25 `StarMapAmenityTypeEntry` Records name, and whether one stands for refuel or cargo
  (needs a v0.5.0 export to read).
- Whether the v0.5.0 catalogue fits the upload limits at all (#1151), which decides whether the
  starmap and inventory tables can be imported in one upload.
