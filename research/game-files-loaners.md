# Loaner data in the game files and the SC Wiki

Research for
[Do the game files or the SC Wiki carry loaners?](https://github.com/krt-profit/basetool/issues/3668),
part of the map
[Wayfinder: loaners of pledged ships that are not in the game](https://github.com/krt-profit/basetool/issues/3666),
for the request
[Hangar: show the loaners of pledged ships that are not in the game (loaner matrix)](https://github.com/krt-profit/basetool/issues/3665).
Status: findings as of 2026-11-24. Facts only; nothing is decided here.

## Sources

Primary sources only. Each claim below names its URL, file and commit.

- **SC Wiki API, live responses** fetched 2026-11-24 with a generic client:
  `GET https://api.star-citizen.wiki/api/vehicles` (311 game vehicles, every `version`
  `4.10.3-LIVE.13165946`), `GET …/api/vehicles/MISC_Hull_C`,
  `GET …/api/shipmatrix/vehicles` (267 Ship Matrix vehicles; this endpoint pages with
  `page[number]` / `page[size]`, a plain `page=` repeats the first page) and
  `GET …/api/shipmatrix/vehicles/hull-d`.
- **SC Wiki API source**: <https://github.com/StarCitizenWiki/API>, `develop` at
  `23082ea7dbd1f916939b971a6bdad4c04832f1ad` (2026-11-21). Which commit the live service runs is
  not published; the live responses match this code.
- **The Wiki's game data**: <https://github.com/StarCitizenWiki/scunpacked-data>, `master` at
  `3cc8072b24df2a2d71eb6207708e2039f909b342` (2026-11-21, commit message
  `4.10.3-LIVE.13165946`; the API repository pins the submodule at
  `4fb27372c0150254b2bb2ae717436febc84454ce`). It is the output of
  <https://github.com/octfx/ScDataDumper>, `master` at
  `1b60ad43cd8f23db4ed2a7177386faeb63fc75e5` (2026-11-21).
- **RSI Knowledge Base article 360003093114** through the Zendesk Help Center API,
  `https://support.robertsspaceindustries.com/api/v2/help_center/en-us/articles/360003093114.json`:
  `updated_at` 2026-11-12T17:18:59Z, header "Last Updated: November 12th, 2026 |
  4.10.3-live.13165946", 53 table rows (a header and 52 ships). One archived copy:
  Wayback snapshot `20231130115819` of
  `https://support.robertsspaceindustries.com/hc/en-us/articles/360003093114-Loaner-Ship-Matrix`
  (header "Last Updated: November 25th, 2023 | 3.21.1-live.8892745").
- **p4kx**: <https://github.com/p4kx-org/p4kx>, `main` at
  `f43f12f3cd9ac73137ae593cfc6b9bd5ea0a27b1` (2026-11-07); latest release `v0.4.2`
  (2026-11-01), the release whose catalogue schema the Basetool vendors
  (`backend/src/test/resources/p4k/README.md`). Read from a shallow clone; nothing was built or run.
- **Game localisation**: the English `global.ini` of LIVE 4.10.3 (change 13165946) as mirrored
  in <https://github.com/rjcncpt/StarCitizen-Deutsch-INI> at
  `69de39a2fac5ebede75a6b32c628656131e7ae34`, path `.github/en/live/global.ini`
  (10,608,856 bytes). A third-party copy of a game file, not read from a `Data.p4k` here.
- **Basetool**, `main` at `78a52dd8fb7ba0412c796604a723c4c58f986bd7`: ADR-0404,
  `docs/specs/p4k-catalogue.md` (REQ-P4K-007, -009, -050, -080).

**Not done:** no `Data.p4k` was opened and no reader was built or run. Every statement about the
game files rests on the exporters' code and their published output listed above.

## The short answer

- **The Wiki's `loaner` field is the RSI loaner article, not game data.** One job,
  `ImportLoaner`, scrapes article 360003093114 daily, resolves its names against the Wiki's
  copy of the RSI Ship Matrix and stamps each row with the article's "Last Updated" build. The
  game-data import holds no loaner code.
- **The Wiki does carry concept-ship loaners**, on `/api/shipmatrix/vehicles`: 25 of its 29
  in-concept ships, among them Hull D, Hull E, Kraken, Kraken Privateer, Odin, Pioneer and
  Endeavor, all stamped `4.10.3-LIVE.13165946`. `/api/vehicles` lists game vehicles only, so a
  concept ship shows there only when a game record exists: the Javelin.
- **The 90 game vehicles with loaners are article rows, current or stale.** 43 carry rows of the
  current article; 47 carry only rows of older article versions (stamps from
  `3.20.0-LIVE.8701927` to `4.10.0-LIVE.12519617`), because the importer never removes the rows
  of a ship the article no longer lists. Hull C → Arrow, Reliant → Avenger Titan Renegade and
  MOLE → Prospector are such stale rows. None of them is a game-mode loaner or insurance.
- **No reader output read here holds loaner data.** Neither the p4kx catalogue contract nor
  ScDataDumper nor its 4.10.3 output mentions a loaner; the English 4.10.3 `global.ini` uses the
  word only for a mission that lends a mining ship.
- **No entity record was found for Hull D, Hull E, Kraken, Odin, Pioneer or Endeavor**, while the
  Javelin, also in concept, has one (`AEGS_Javelin`). The localisation carries name and
  description keys for all of them.
- **p4kx reads and exports no loaner field today.** Per-ship data sits in the catalogue's
  `ships[]` entry, beside `insurance`; a concept ship has no such entry. p4kx keeps one curated,
  non-Archive data set, `shipStoreSlugs`, whose file names the concept ships but does not export
  them.

## 1. The SC Wiki

### 1.1 Where the `loaner` field comes from

- **The only writer** of the loaner relation is the job
  [`app/Jobs/StarCitizen/Vehicle/ImportLoaner.php`](https://github.com/StarCitizenWiki/API/blob/23082ea7dbd1f916939b971a6bdad4c04832f1ad/app/Jobs/StarCitizen/Vehicle/ImportLoaner.php),
  run daily (`routes/console.php`, `Schedule::job(new ImportLoaner)->daily()`) and by hand as
  `vehicles:import-loaner` (`app/Console/Commands/StarCitizen/Vehicle/ImportLoaner.php`). A grep
  of `app/` for `loaner` finds no other writer; `app/Console/Commands/MigrateData.php` and
  `config/data_migration.php` only copy an older `vehicle_loaners` table into the current one.
- **Input**: line 73 fetches
  `https://support.robertsspaceindustries.com/api/v2/help_center/en-us/articles/360003093114`
  and reads `article.body`. Lines 79–84 take every `table tbody tr`: the first cell is the ship,
  the last cell the loaners, split at commas with "and" removed (lines 95–99).
- **Names**: grouped article names go through `$modelsMap` (lines 28–48, credited to FleetYards'
  `app/lib/rsi/loaner_loader.rb` at `de56bf2…`), loaner names through `$modelMap` (lines 50–62). Each name is
  resolved against **Ship Matrix vehicles** (`App\Models\StarCitizen\ShipMatrix\Vehicle\Vehicle`,
  the Wiki's copy of the RSI ship matrix), first by exact name, then case-insensitively, then by
  `LIKE %name%` (`findVehicleByName`, lines 163–191). Unresolved names are only logged.
- **Write**: `$vehicle->loaner()->sync($loanerIDs)` (line 147) per ship the article names, into
  `shipmatrix_vehicle_loaners (vehicle_id, loaner_id, version)`
  (`database/migrations/v2/starcitizen/ship_matrix/vehicle/2023_05_25_153213_create_vehicle_loaners_table.php`).
  The relation joins two Ship Matrix vehicles; no game record takes part.
- **Version stamp**: `getLastUpdateVersion` (lines 193–212) takes the paragraph containing "Last
  Update", splits it at `|` and upper-cases the second part: "4.10.3-live.13165946" becomes
  `4.10.3-LIVE.13165946`. The `version` of a loaner entry is therefore the article's build at the
  time of the import that wrote the row (the test `tests/Feature/ImportLoanerTest.php`, "stores
  the version on the pivot table", asserts exactly that). The game vehicle's own `version` is the
  build of the game-data import; today both read `4.10.3-LIVE.13165946`.
- **Output**:
  - `/api/vehicles` (game vehicles): `App\Http\Resources\Game\Vehicle\VehicleResource::getLoaner`
    (line 1337) returns the loaners of the Ship Matrix vehicle the game vehicle links to; the
    OpenAPI text of the field is "Ship-Matrix loaner vehicles" (line 719).
  - `/api/shipmatrix/vehicles` (`App\Http\Controllers\Api\StarCitizen\VehicleController`, "SKU
    variants and loaner vehicles are included by default", line 89) returns the same relation for
    every Ship Matrix vehicle.
  - Each entry (`app/Http/Resources/StarCitizen/Vehicle/VehicleLoanerResource.php`) carries
    `uuid` (the game UUID of the loaner's game vehicle, `null` when its Ship Matrix vehicle links
    to none), `name`, `slug`, `link` and `version` (the stamp above).

### 1.2 Can it list a ship the game files do not contain?

Yes, on the Ship Matrix endpoint.

- `/api/shipmatrix/vehicles`: 267 vehicles, 238 `flight-ready` and 29 `in-concept`. 104 carry
  loaners: 79 flight-ready and 25 in-concept ships. Every loaner of an in-concept ship is stamped
  `4.10.3-LIVE.13165946`.
- The ships the request names:

  | Ship Matrix vehicle | Status | Loaners on the Wiki |
  | --- | --- | --- |
  | Hull D | in-concept | Hull C, C2 Hercules |
  | Hull E | in-concept | Hull C, C2 Hercules |
  | Kraken | in-concept | Polaris, Ironclad Assault, Buccaneer |
  | Kraken Privateer | in-concept | Polaris, Ironclad, Buccaneer |
  | Odin | in-concept | Idris-P |
  | Pioneer | in-concept | Caterpillar, Nomad |
  | Endeavor | in-concept | Starfarer, Cutlass Red |

- The four in-concept ships without loaners: Crucible (the article's loaner "Constellation
  Andromeda" resolves to no Wiki name; the Wiki calls it "Constellation Mk IV Andromeda"),
  Geotack Planetary Beacon, Geotack-X Planetary Beacon and Nautilus Solstice Edition (no article
  row).
- **Keys**: a concept ship is keyed by its RSI Ship Matrix `id` and `slug`; its loaners carry the
  game UUIDs of the loaner ships. `GET …/api/shipmatrix/vehicles/hull-d` returns `id` 86,
  `in-concept`, and the loaners Hull C (`c4ceff45-d851-44b1-921b-bfef93a6cd6a`, the `uuid` of
  `MISC_Hull_C` on `/api/vehicles`) and C2 Hercules (`7533ff77-836c-4bd2-9171-487d9c63863c`). The
  id 86 is the RSI store id of the slug `Hull-D` in p4kx's store-slug file (section 3).
- `/api/vehicles` lists game vehicles only. Of its 311, one is in concept on the Ship Matrix: the
  Javelin (`AEGS_Javelin`, loaners Idris-P and MPUV Cargo at `4.10.3-LIVE.13165946`). Hull D,
  Hull E, Kraken, Odin, Pioneer and Endeavor are absent. 58 of the 311 link to no Ship Matrix
  vehicle and can carry no loaner at all.

### 1.3 What the 90 vehicles with loaners have in common

- All 90 are game vehicles whose linked Ship Matrix vehicle holds rows written by `ImportLoaner`,
  that is, rows of the RSI article. 43 carry rows stamped with the current article
  (`4.10.3-LIVE.13165946`); 47 carry only rows with one of 13 older stamps, from
  `3.20.0-LIVE.8701927` to `4.10.0-LIVE.12519617`.
- **Why stale rows stay**: `sync` runs only for the ships the current article names. A ship that
  left the article keeps its last loaners with their old stamp; nothing deletes them.
- **The three examples of the request are stale rows**:

  | Game vehicle | Loaner on the Wiki | Stamp |
  | --- | --- | --- |
  | MISC Hull C | Arrow | `4.7.1-LIVE.11592622` |
  | MISC Reliant Kore, Mako, Sen, Tana | Avenger Titan Renegade | `3.22.0-LIVE.9003376` |
  | MOLE | Prospector | `4.8.2-LIVE.12030094` |

  The archived article of 2023-11-25 (`3.21.1-live.8892745`) lists "Hull C | Arrow" and
  "Reliant Variants | Avenger Titan"; the current article lists neither ship.
- **Kind**: RSI matrix rows. The article says it lists "all ships not available in the PU" with
  their loaners until each becomes flight ready. The current rows also cover ships the game lists (ground vehicles → Mustang Alpha, Carrack → C8X
  Pisces Expedition and URSA, Idris → F7C-M and MPUV); the article states no reason for those.
  None of the 90 comes from game data, and none is insurance: insurance is a separate field,
  `insurance`, filled from the game data (`SEntityInsuranceProperties`, see 2.1).
- **Artefacts of the name resolution in today's data** (current article against the Wiki):
  - "Carrack / Carrack Expedition" and "Carrack w/ C8X / Carrack Expedition w/C8X" both map to
    the Wiki's Carrack; the second `sync` replaces the first, so the Carrack shows C8X Pisces
    Expedition and Ursa, the C8 Pisces is lost and the Carrack Expedition has none.
  - "Constellation Andromeda", "Constellation Aquila" and "Constellation Phoenix" resolve to no
    Ship Matrix name; the Wiki's "Constellation Mk IV …" vehicles keep rows stamped
    `4.10.0-LIVE.12519617`.
  - "MXC" has no Ship Matrix vehicle; its row is dropped.

## 2. The game files

### 2.1 Do the records the readers export hold loaner entries?

- **p4kx catalogue contract** (`schemas/basetool-catalog.schema.json` at `f43f12f`): the `Ship`
  definition has the fields `career`, `cargoGridsWithIssue`, `cargoScu`, `className`,
  `crewSize`, `description`, `guid`, `hullHealth`, `insurance`, `manufacturerGuid`, `mass`,
  `massBasis`, `name`, `playability`, `recordPath`, `role`, `size`, `variantOf`,
  `vehicleDefinition` and `vehicleKind`. No definition of the schema mentions a loan, a rental or
  an entitlement. A grep of the whole repository (code, docs, schemas, tests) finds no "loaner".
- **ScDataDumper** at `1b60ad4`: no "loaner" or "loan" anywhere in its source.
- **ScDataDumper's 4.10.3 output** in scunpacked-data `3cc8072`: no "loan" in `ships.json`
  (48,963,758 bytes) nor in `ships/misc_hull_c.json`, `ships/misc_reliant.json`,
  `ships/argo_mole.json` and `ships/aegs_javelin.json`. A ship file's top-level keys include
  `Insurance`, nothing loaner-like.
- **Insurance is the per-ship ownership datum the readers do export**: p4kx reads it from the ship
  Record's `StaticEntityClassData` / `SEntityInsuranceProperties` (`docs/basetool-catalog.md`,
  "Ship insurance"; `crates/p4kx-export/src/ship.rs`, `fn insurance`).
- **Localisation**: the English 4.10.3 `global.ini` has no string for a pledge loaner. "Loan"
  occurs only in mission texts of the Battaglia mining mission, which lends the player a ship:
  `PU_BATTAGLIA_PEO_F_MG_RBMMission1AcceptShipOffer_GP_001` (line 27710, "a loaner mining ship")
  and `mg_battaglia_mining_objective_display_001`, `…_long_004`, `…_short_002`, `…_short_004`
  (lines 76547–76557, "Return loaned vessel"). That is a mission mechanic, not the RSI matrix.
- **Limit**: no DataForge was searched field by field for a loaner-named struct here. p4kx has
  the generic tools for it (`dcb export`, `dcb query`, `dcb fields`, and `grep` over Records,
  localisation and text Entries, `README.md`); they were not run.

### 2.2 Is there a record for a ship that cannot be flown yet?

- **Javelin: yes.** `AEGS_Javelin` is in scunpacked-data 4.10.3 (`ships/aegs_javelin.json`,
  "Aegis Javelin", with an `Insurance` block) and in the Wiki's `/api/vehicles`, and a p4kx
  real-archive test assembles it
  (`crates/p4kx-assembly/tests/real_archive/real_port_flag_geometry.rs`, line 126,
  `assemble(&archive, "AEGS_Javelin")`). The RSI Ship Matrix lists it `in-concept`, and the RSI
  article grants it loaners (Idris-P, MPUV Cargo).
- **Hull D, Hull E, Kraken, Kraken Privateer, Odin, Pioneer, Endeavor: none found.**
  - None is among the 332 files of scunpacked-data `ships/` at `3cc8072` or the 311 vehicles of
    `/api/vehicles`. ScDataDumper skips every Record whose name holds a token such as `ai`, `npc`,
    `pu`, `template`, `unmanned` or `wreck`
    ([`src/Services/VehicleService.php`](https://github.com/octfx/ScDataDumper/blob/1b60ad43cd8f23db4ed2a7177386faeb63fc75e5/src/Services/VehicleService.php),
    `$avoids`, lines 40–89), so its output alone does not prove that no Record exists.
  - p4kx maps RSI store slugs only to `player` ships of the LIVE Archive. On LIVE 4.7, 205 of the
    256 store slugs mapped and 51 stayed unmapped, all 29 concept ships among them
    (`docs/basetool-catalog.md`, lines 2340–2343). Its data file lists
    them with `className` and `guid` `null` (`crates/p4kx-export/data/ship-store-slugs.json`:
    `Javelin` 63, `Hull-D` 86, `Hull-E` 87, `Endeavor` 97, `Pioneer` 149, `Kraken` 170,
    `Kraken-Privateer` 175, `Odin` 317).
  - In the Basetool's production on 2026-11-22, the UEX rows of 43 concept ships (Hull D, Hull E,
    Kraken, Odin, Orion, Pioneer, …) hold no P4K GUID (ADR-0404): the P4K import, which reads the
    catalogue's `ships` of every playability, matched no Record to them by GUID, Wiki UUID, class
    name or name (REQ-P4K-007). That shows no matching Record, not the absence of every Record.
  - The 4.10.3 localisation does carry their keys: `vehicle_NameMISC_Hull_D` (line 90727),
    `vehicle_NameMISC_Hull_E` (90729), `vehicle_NameDRAK_Kraken` (90628),
    `vehicle_NameDRAK_Kraken_Privateer` (90629), `vehicle_NameANVL_Odin` (90446),
    `vehicle_NameCNOU_Pioneer` (90524), `vehicle_NameMISC_Endeavor` (90705), each with a
    `vehicle_Desc…` beside it. These are strings, not entity Records.
- **Summary of the limit**: no reader output read here contains an entity Record for these ships.
  A non-player Record that ScDataDumper filters out and the Basetool's name match misses cannot be
  ruled out without a full Record list of the 4.10.3 Archive.

### 2.3 What kind of loaner the game files carry

Only the mission loan of 2.1 (a ship lent by a mission, with "return the loaned vessel"
objectives) was found, and only as text. No reader derives a pledge loaner, a game-mode loaner or
a matrix row from the game data.

## 3. p4kx

- **Reads or exports no loaner field today**, at `v0.4.2` and at `main` `f43f12f` (section 2.1).
  No p4kx issue mentions loaners (`gh issue list --repo p4kx-org/p4kx --search loaner --state all`
  returns none).
- **Where per-ship data sits in the export**: the catalogue kind `ships` holds one entry per
  `EntityClassDefinition` Record under `entities/spaceships` and `entities/groundvehicles`
  (`crates/p4kx-export/src/selection_rules.rs`, lines 23–29), minus the leftover tokens `test`,
  `template`, `dummy`, `debug`, `deprecated`, `dev` and, for ships, `temp`, `display`, `hijacked`
  (`crates/p4kx-export/src/selection.rs`, lines 14 and 26); AI, mission and special ships are
  listed with their `playability`. The entry is the struct `Ship`
  (`crates/p4kx-export/src/contract_entities.rs`, line 89), filled in
  `crates/p4kx-export/src/ship.rs` (`fn ship`); data read from the ship Record, such as
  `insurance`, is a field of that entry. A ship without a Record, such as a concept ship, has no
  `ships[]` entry.
- **Curated, non-Archive data in the catalogue**: `shipStoreSlugs`, compiled from
  `crates/p4kx-export/data/ship-store-slugs.json` (p4kx ADR-0117, "The RSI store slugs are curated
  data in P4KX, and the catalog exports them as the kind `shipStoreSlugs`"). The file holds 256
  slugs with the RSI store id and store name, 51 of them unmapped. ADR-0117 exports neither an
  unmapped slug nor the slug of a ship the game does not have; a human refreshes the file, the
  tool fetches nothing.

## 4. Corrections to the source table of the request

- **SC Wiki, "Concept ships: no"**: the Wiki carries loaners for 25 of its 29 in-concept ships on
  `/api/shipmatrix/vehicles`, among them Hull D, Hull E, Kraken, Odin, Pioneer and Endeavor;
  `/api/vehicles` lists one concept ship with loaners, the Javelin.
- **SC Wiki, "keyed by game UUID"**: a loaner entry carries the loaner ship's game UUID; the
  granting ship is a Ship Matrix vehicle, keyed by its RSI ship id and slug.
- **SC Wiki, "stamped with the build"**: the stamp is the article's "Last Updated" build at the
  import that wrote the row; 47 of the 90 game vehicles carry only older stamps.
- **SC Wiki, "loaners the RSI article lacks … probably game-mode loaners from the game data"**:
  they are rows of older article versions that the importer never removes.
- **RSI article**: 52 ship rows, confirmed.
- **P4K / p4kx, "presumably no"**: confirmed for every reader output read here; the Javelin is a
  concept ship that does have a Record.
