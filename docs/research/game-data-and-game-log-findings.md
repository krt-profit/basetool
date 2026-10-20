# Game data and Game.log — what can be read, and what it means for the Basetool

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-10-20. Nothing here is binding; a feature built on it gets its own `REQ-…` and, where it
> decides something, its own ADR.
>
> **Purpose:** the starting point for extending the Basetool with the export of the community
> p4k extractor once that extractor is finished, and with Game.log data later on.

## 1. Sources

| Source | What it is | Status |
|---|---|---|
| **Community extractor package** (state of October 2026) | Rust extractor `spieldaten` (crates `p4k`, `dataforge`, `spieldaten`), two reverse specs, verification scripts, SQLite schema `schema-lieferdaten.sql` | Code delivered, **not built or run** in this review |
| **Companion data model** (SQL Server schema script) | Schema only, no rows: `dm` (game data model) and `lg` (Game.log model) | Schema read in full |
| **Log sample** | Anonymised example Game.log lines for store, move, interaction, unstow, freight elevator, blueprint notification | Read |
| **Game.log data atlas** (as of 2026-10-17, branch `sc-alpha-4.10.2`) | Two independent studies: **A** = 134 logs (2026-02-28 – 10-17, 4.6.0 – 4.10.x, 39,657 actions, controlled experiments with 60 fps video); **B** = 528 logs of a second data set (2025-11-26 – 2026-10-17, 4.4.0 – 4.10.2, 617 distinct markers, counting only) | Read |

The package files were treated as data only; they were inspected, not executed.

## 2. Where the Basetool already stands

Relevant before anything new is designed:

- **P4K catalogue import exists.** `catalogue/internal/P4kImportService`, async job
  (`P4kImportJobRunner`, `p4k_import_job`, V140/V141), admin page `catalogue/admin/p4k-import`.
  The payload `P4kCatalogDto` carries `manufacturers`, `items`, `ships`, `commodities`,
  `blueprints`. A new extractor export most likely lands here, as a new or extended payload.
- **ADR-0283:** a game item's **size, grade and class come from the SC Wiki only**. The P4K
  import must not write them, even though the game files contain them. Using the extractor's
  values for these columns means amending ADR-0283 and REQ-DATA-024 first.
- **REQ-DATA-015:** an external sync must not overwrite a value another source owns on every
  cycle; REQ-DATA-025: P4K descriptions are stored with real line breaks (`P4kText`).
- **Game.log is already read** by the separate desktop tool `basetool-sc-extractor` for
  **blueprints** (`"Received Blueprint: <name>"`, localised clients — see
  [`blueprint-import-name-matching.md`](../specs/blueprint-import-name-matching.md)). It sends
  drafts through the exchange draft routes (REQ-XCH-019). Only owner-approved clients may use the
  exchange (REQ-XCH-002/-003, [`desktop-ingest.md`](../specs/desktop-ingest.md)).

## 3. The community extractor (`spieldaten`)

### 3.1 What it is

- Rust workspace, Edition 2024, Rust ≥ 1.88 (built and tested with 1.99), licence
  **GPL-3.0-only**. Dependencies: `aes`, `cbc`, `ruzstd`, `miniz_oxide`, `crc32fast`,
  `serde_json`, `rusqlite` (bundled). No network code, no external process calls.
- `p4k` reads `Data.p4k` directly (ZIP64, AES, zstd). **The archive AES key is hard-coded**
  (`AES_SCHLUESSEL` in `crates/p4k/src/lib.rs`), described as the key publicly known from
  `unp4k`. The package itself leaves open whether it may be redistributed with that key.
- `dataforge` reads the DataForge database `Game2.dcb`.
- `spieldaten` builds a SQLite reference database from both and converts CryEngine binary XML
  (`CryXmlB`) to readable XML; it also reads the localisation (`--sprache english` default).
- Claimed accuracy: 99.94 % agreement with StarBreaker on the compared files (build 4.10.193,
  measured 2026-10-05; not re-measured for this package).

### 3.2 Commands

```text
spieldaten info <Data.p4k>
spieldaten liste <Data.p4k> <text>
spieldaten extrahiere <Data.p4k> <text> <dir> [--xml]
spieldaten suche <Data.p4k> <text>
spieldaten datensatz <Data.p4k> <name or path>
spieldaten export-json <Data.p4k> <dir> [path filter]
spieldaten text <Data.p4k> <@loc_key>
spieldaten xml <Data.p4k> <archive name>
spieldaten abfrage <Data.p4k> <struct type>
spieldaten datenbank <Data.p4k> <out.sqlite> [--max N] [--sprache english] [--schlank]
spieldaten datenbank-archiv <Data.p4k> <db.sqlite> [--mit-xml]
spieldaten datenbank-schlank <full.sqlite> <slim.sqlite>
```

### 3.3 The reference database

Measured on build 4.10.193 by the authors: full build ≈ 3 min (+ ≈ 2 min archive index),
**5,756 MB** full, **587 MB** slim, ≈ 1.7 GB RAM while building.

| Table | Size | Note |
|---|---:|---|
| `wert` | 2,273 MB | 36.2 M rows; 23.2 M belong to `EntityClassDefinition` |
| `xml_dokument` | 1,758 MB | 48,249 documents; the companion app reads 172 vehicle XMLs |
| `archiv_eintrag` | 416 MB | archive index |
| `suche_*` (FTS) | ≈ 660 MB | CLI search only |
| `verweis`, `kante_datei`, `datensatz`, `textwert`, `lokal`, `pfad` | 146 / 97 / 61 / 31 / 17 / 11 MB | |

The **slim selection** (`crates/spieldaten/src/schlank.rs`) keeps eight tables (`datensatz`,
`wert`, `pfad`, `textwert`, `verweis`, `lokal`, `archiv_eintrag`, `xml_dokument`), the 172
vehicle XMLs under `data/scripts/entities/vehicles/implementations/xml/`, and from
`EntityClassDefinition` only the components: ports, attachments, weapons, missiles, bombs,
quantum drive, shield, armour, default loadout, physics/mass, vehicle, resources, signatures.
Types kept whole: `StarMapObject`, `StarMapObjectType`, `SCItemManufacturer`, `AmmoParams`,
`SolarSystem`, `InventoryContainer`, `ResourceType`, `ResourceTypeGroup`, `VehicleCareer`,
`VehicleRole`, `Tag`, `StarMapAmenityTypeEntry`, `SLoadoutAssortment`.

**Not in the slim selection** but named by the authors as needed for later modules: crafting
(`CraftingBlueprintRecord`), missions (`MissionBrokerEntry`, `ContractGenerator`,
`ContractTemplate`, `MissionLocationTemplate`), mining (`SubHarvestableMultiConfigRecord`).

### 3.4 Delivery schema (`sql/schema-lieferdaten.sql`)

A separate SQLite schema for curated research deliveries, not filled by the extractor:
`item`, `schiff`, `namensabgleich` (log class name ↔ display name), `mission_geber` (+ location,
contract type, contract class), `handel_preis` (observation: price per terminal per day),
`fundort` (observation: loot location with mandatory sample size), and `pruefung` (status
`bestaetigt` / `mehrdeutig` / `nicht_gefunden` / `nicht_belegbar`). Rules worth keeping:
unknown numbers stay `NULL`, never `0`; prices cannot be confirmed from game data; loot only as
an observation with a sample count; no player names anywhere.

## 4. Companion data model, schema `dm` (game data)

| Area | Tables / views | Content |
|---|---|---|
| Generic records | `entitaet`, `entitaetstyp`, `entitaet_wert`, `pfad`, `verweis`, `pfad_aufloesung`, `s_struktur`, `s_feld`, `s_vorfahr`, `typ_beziehung`, `v_s_feld_wirksam`, `v_beziehung` | DataForge records, values per field path, references between records, struct schema with inherited fields |
| Components | `bauteil` (type, subtype, size, grade, class, manufacturer, EN/DE name, damage type, tags, required tags, EN description, base model), `bauteil_kennwert` (+ `_def`: value name → field path, unit), `bauteil_quelle` (game version, build date, P4 change, branch) | One view per component type, see below |
| Text | `text` (EN/DE, status, placeholders), `schluessel`, `text_schluessel`, `entitaet_locale`, `familie`, `familie_regel`, `namensverwendung`, `befund`, `unbekannt` | Localisation keys mapped to records, German translation status, findings |
| Key bindings | `aktionsgruppe`, `aktion`, `taste`, `geraet`, `layout`, `belegung`, `layout_belegung`, `v_text_aktion` | Action maps, keys per device, layouts |

Component views (`dm.v_bt_*`): power plant, cooler, shield, quantum drive, jump drive, radar,
weapon, countermeasure, missile, missile rack, bomb rack, bomb, turret, utility turret, main
thruster, manoeuvring thruster, fuel tank, quantum fuel tank, fuel intake, mining laser, mining
module, tractor beam, salvage head, EMP, quantum interdiction, scanner; plus
`v_bauteil_uebersicht`.

Common values: mass (kg), hit points, EM and IR signature, repair time, power segments, coolant.
Weapon values, as an example of depth: fire rate single/rapid/burst (rpm), burst shots, pellets
per shot, charge time, ammo max/start, energy demand, heat per shot, regen values, spread
min/max, damage per type (physical, energy, distortion, thermal, stun, biochemical), projectile
speed and lifetime, impact radius, penetration. Derived: damage per pellet, per shot, DPS
(beam vs. ammo), range = speed × lifetime.

## 5. Companion data model, schema `lg` (Game.log)

**The parser that fills these tables was not delivered** — only the target schema.

| Object | Content |
|---|---|
| `lg.sitzung` | One row per log file: start, end, branch, source path, size in MB, line count |
| `lg.klasse`, `lg.ort` | Class names and location names from the log, linked to `dm.entitaet` and to a localisation key |
| `lg.item_aktion` | One row per recognised event: time (UTC), action, source tag, class, display text, instance number, quantity, location, raw side A/B (inventory, kind, location, container class), port, shop, price, result, caller, remark, and `von_*` / `nach_*` with `richtung` (`'Queued'` = direction taken from the Queued line) |
| `lg.anmerkung`, `lg.anmerkung_item` | Manual annotations per session line, optionally linked to records |
| `lg.v_item_aktion` | Every action readable, names resolved DE/EN |
| `lg.v_item_uebersicht` | Per item and action: count, sessions, first/last, succeeded/failed |
| `lg.v_aufzug_vorgang` | Per freight-elevator unstow: searches typed in the 3 min before (`text -> hits`), platform up/down times, seconds up, blueprint library connect |
| `lg.v_exemplar` | Per instance: first/last mention, actions |
| `lg.v_aufzug_neue_exemplare` | Instances first mentioned during an unstow; evidence `Warnung beim Entstehen` (first seen in a parent-assignment warning — reliable) vs. `nur zeitlich` (only time window — weak) |

Action names visible in the views: `Aufzug: Auslagern (Anfrage)`, `Aufzug: Lager abgefragt`,
`Aufzug: Plattform hoch`, `Aufzug: Plattform runter`, `Bauplan-Bibliothek verbunden`,
`Zuordnungswarnung (Eltern)`.

The schema has no player-name column. `sitzung.quelle` (a file path) and the free-text columns
`aufrufer` and `bemerkung` can still carry personal data.

## 6. Game.log data atlas — findings

Status legend: **secured** = confirmed by A and B · **evidenced** = triggered deliberately by A ·
**measured** = counted, not triggered · **corrected** = B contradicts A · **open**.

Player name, player number, shop and kiosk numbers appear in almost every line; an evaluation
should discard them.

### 6.1 What can be read

| Data | Marker | Readable | Missing | Status |
|---|---|---|---|---|
| **Trade: commodities** | | | | |
| Commodity buy | `…CommodityUIProvider::SendCommodityBuyRequest` | shop, total `price`, price per cSCU, quantity in cSCU, box size and count, commodity GUID, auto-load | commodity name (GUID only), final result | measured |
| Commodity sell | `…SendCommoditySellRequest` | shop, proceeds `amount`, quantity, source `transactionMode` (Location, Warehouse, CargoGrid, ResourceContainer, Entities), one box kind, GUID | name, final result, boxes across sizes | measured |
| Box offered | `…AddPlayerCommodityItem` | box size and count offered at the terminal | commodity name (field empty) | measured |
| Ship cargo at terminal | `…LoadSelectedShipBindings`, `RmToken_VehicleCargoDataResponse` | selected ship (instance), cargo query | cargo contents | measured |
| **Trade: items** | | | | |
| Shop buy | `…ShopUIProvider::SendShopBuyRequest` | shop, class name, class GUID, quantity, total `client_price` | currency, per-request result | secured |
| Kiosk buy | `…ShoppingProvider::SendStandardItemBuyRequest` | as above plus `currencyType[UEC]`; always followed by "Transaction Complete" after 0.4 s | kiosk number is 0 | measured |
| Response | `RmShopFlowResponse` | `result[Success]`, partly buy/sell | item; matched by order only | secured |
| **Inventory and material movement** | | | | |
| Action chain | `Add Inventory Management Move` → `Inventory Mgmt Request Queued` → `Player Inventory Request Complete` | kind, class, instance, source, target, quantities, caller, result | result before June 2026; full chain only from August 2026 | secured |
| Store confirmed | `OnInventoryStoreItem`, `StoreItem` | class, instance, target container, stack `ItemsCount` | location | evidenced |
| Container contents | `Update Container Items Add New Item` | class and container per listed piece | quantity per piece | corrected |
| Fill level on drag | `OnDragInventoryItemModifyTarget` | capacity, used, moved, fill after (µSCU) for source and target | item; 62 % without capacity | measured |
| Inventory location change | `Update Inventory Location` | old and new landing zone and location id | location name (link via `RequestLocationInventory`) | measured |
| Drop | `Inventory Drop From Container`, chain with `Type[Drop]` | instance, source, result | position on the ground | corrected |
| Ammo refill | chain with `Type[AmmoRepool]` | Queued and result, 14 % `Failed` | New line | measured |
| **Storage and freight elevator** | | | | |
| Storage search | `InventoryManagement` "Freight Inventory Grid" | search text after typing pause, hit count, location inventory | on-screen filter | evidenced |
| Unstow | `…FreightElevatorUIProvider::FillUnstowRequest` | number of pieces, location id, request number | which pieces | secured |
| Stack move / split | `RmTokenMoveItemStackResult`, `RmTokenSplitItemStackResult` | result, origin (location inventory), kiosk | item, quantity | corrected |
| Platform | `CSCLoadingPlatformManager::OnLoadingPlatformStateChanged` | states with fixed durations, hangar size, location | link to request by time only | secured |
| Spawned piece | `UnstowPendingEntities` | class and instance of one unstowed piece | appears after < 1 % of elevator unstows | secured |
| **Missions, rewards, refinery** | | | | |
| Mission accepted / ended | `SHUDEvent_OnNotification`, `EndMission`, `MissionEnded` | title, mission id, end type (Complete, Abandon, Fail) | contract giver in clear text | measured |
| Delivery objective | notification "New Objective: Deliver x/y SCU of <commodity> to <place>" | commodity display name, progress in SCU, destination | class name | measured |
| Reward | notification "Awarded n aUEC" | amount in aUEC | mission, by time only | measured |
| Refinery | notification "Refinery Work Order(s) … Completed at <station>" | station, number of finished orders | ore, quantity, yield | measured |
| Hauling item | `SMarkerHandler_Hauling::OnItemRegistered` | class, instance, mission id, pickup and drop-off | — | measured |
| **Equipment, death, ships, crafting** | | | | |
| On the body | `AttachmentReceived`, `EquipItem` | class, instance, port | port meaning only inferred | evidenced |
| Lost on death | `Adding non kept item` | class, instance, port, class GUID, partly `KeptId` | killer (no kill lines since 4.4) | measured |
| Insurance | `CWallet::ProcessClaimToNextStep`, `…OnProcessClaimCallback` | claim per entitlement, result code (11, 7) | ship in clear text; only until 2026-04-28 | measured |
| Ship stored | `LandingArea_…StowingVehicle`, `OnRequestFetchVehicles` | landing zone, vehicle instance, location id | ship class | measured |
| Blueprint | notification "Received Blueprint", `ReuseChannel` with `BlueprintLibraryService` | blueprint display name; crafting terminal opened | class name, crafting itself | evidenced |

### 6.2 Trade figures

- **Commodity buy, 217 requests, the arithmetic holds in all 217:**
  `price = shopPricePerCentiSCU × quantity(cSCU)` and
  `quantity(cSCU) = boxSize × unitAmount × 100`. Example: 32.368 × 30,400 cSCU = 983,988, i.e.
  19 boxes of 16 SCU. 34 commodities in 9 shops, total 196.9 M.
- **Commodity sell, 90 requests:** `amount` is the proceeds. Quantity matches the stated box in
  only 37 of 88 cases, because the line names one box size only. Proceeds per unit drift slightly
  even within one session (e.g. 4,050.37 – 4,050.40). 29 commodities, 5 shops, total 149.4 M. No
  unit price field.
- **Items, 704 requests:** `client_price` is the **total** price (A: qty 1 → 275, qty 3 → 825;
  B: same unit price in 8 of 8 groups). The same item costs different amounts in different shops
  and at different times (e.g. 239 – 265), so a price comparison needs shop and date.
- Counts (B): shop items 538 requests / 537 Success (2025-11-26 – 2026-10-09); kiosk items (UEC)
  166 / 166 (2025-12-18 – 2026-10-09); commodity buy 217, sell 90 (result not readable);
  "Awarded aUEC" 127 notifications, together 363,500 aUEC, 500 – 50,250 each.
- **Not in the trade lines:** commodity names (GUID only, resolve via game data), final result of
  a commodity deal (11 cases stay `WaitingForPendingResult`), account balance, currency of a shop
  buy (only the kiosk writes `UEC`).

### 6.3 Material movement

One action = three lines with the same request number. Queued follows after a median of 2 ms,
the result after 0.79 s (A: 1 – 2 ms, 0.7 – 0.9 s). **Only the Complete line says whether it
worked.**

| Kind | A lines | A ok / failed / open | B lines | B ok / failed / open | Remark |
|---|---:|---:|---:|---:|---|
| `Move` | 408 | 401 / 2 / 5 | 844 | 817 / 24 / 3 | direction from raw line almost always right |
| `Store` | 1,183 | 1,027 / 44 / 112 | 366 | 354 / 1 / 11 | raw line swaps source and target |
| `Interaction` | 1,039 | 1,011 / 8 / 20 | 594 | 588 / 6 / 0 | B: 206× without New line |
| `Stack` | 146 | 142 / 4 / 0 | 66 | 66 / 0 / 0 | failure reasons open |
| `Split` | 10 | 10 / 0 / 0 | 41 | 41 / 0 / 0 | source = target |
| `Drop` | 35 | 35 / 0 / 0 | 165 | 163 / 2 / 0 | corrected: always has a Queued line in B |
| `Swap` | 10 | 7 / 3 / 0 | 5 | old logs only | no case with result in B |
| `AmmoRepool` | – | – | 681 | 587 / 94 / 0 | new in B, no New line |
| `StackAll` | – | – | 10 | 7 / 3 / 0 | new in B |
| `Sort`, `QueryOrCreate` | – | – | 27 / 4,233 | old logs only | new in B |

B counts only the 79 logs with a complete chain (from August 2026).

**Store direction (secured):** read the direction from the **Queued** line. Raw line vs. Queued:
A 46 same / 1,008 swapped / 129 unusable; B 78 / 275 / 7. In Queued, `Store` has the target set
almost always (B: 1,829 of 1,850) and the source `INVALID` almost always (1,781 of 1,850).

### 6.4 Freight elevator

| Step | A (4 runs) | B median |
|---|---:|---:|
| request → RaisingPlatform | 2.97 – 3.04 s | 3.34 s (B spread 0.3 – 9.9 s, 692 cases) |
| RaisingPlatform → OpeningFrontGate | 5.17 – 5.25 s | 5.14 s |
| OpeningFrontGate → OpenIdle | 2.10 – 2.14 s | 2.10 s |
| ClosingFrontGate → LoweringPlatform | 2.10 – 2.15 s | 2.10 s |
| LoweringPlatform → ClosedIdle | 5.17 – 5.23 s | 5.14 s |

Ship elevators have further states (`OpeningLoadingGate`, `ObstructedAtLoadingPlatformLower`).
`FillUnstowRequest` names only the number of pieces (B: mostly 1 – 4, max 35).
`UnstowPendingEntities` follows rarely (A: 8 of 63; B: 6 of 719 within 120 s). A identifies the
piece anyway: a storage search with the full display name returns exactly one hit, and an
unstowed weapon then appears as `AttachmentReceived` on the right hand.

### 6.5 Hypotheses checked

Secured: timestamps are UTC (local time in file name minus first line: 1 h in 298 logs, 2 h in
229); the level prefix can be compound (104,353 × `[SPAM #][Notice]`, 420 × `[SPAM #][Error]`);
the three-line chain and its timing; direction from Queued; location id stable across sessions
(81 locations, 48 in several sessions); `client_price` is the total; all shop responses
Success (703 of 703); `UnstowPendingEntities` rare (6 of 719).

Corrected by B: `Drop` does have a Queued line; `Update Container Items Add New Item` exists
from 2026-06-25 (not 08-07); `RmTokenMoveItemStackResult` spans 2025-11-26 – 2026-10-11 (444
lines); `amount[…] > 0` in 49 % (6,619 of 13,368), not every fifth; no `Actor Death` line since
4.4.0 (not 4.8); marker eras: `Add Inventory Management Move` and
`Player Inventory Request Complete` from 06-20, `Inventory Mgmt Request Queued` from 08-15,
`InventoryManagementRequest` until 08-26. A probably lacks logs from some periods (4.9.0: A has
1, B has 94). Not checkable: whether `log_WriteToFileVerbosity` is ignored.

### 6.6 Limits and open questions

**Not in the log:** what the freight elevator unstows (count only); commodity names; account
balance and final result of commodity deals; ore, quantity and yield at the refinery; who caused
a death; **current stock** — the log shows events, not inventories.

**Open:** why `Stack`, `Store` and `AmmoRepool` end in `Failed`; what `amount[…]` in the Queued
line means; when `UnstowPendingEntities` is written; meaning of insurance codes 11 and 7; meaning
of most port names; whether log verbosity can be raised without anti-cheat risk.

### 6.7 Parser rules

- The old request line writes `New request[n]` with a lower-case "r"; a pattern on
  `New Request` misses it.
- The level prefix can be compound (`[SPAM 36][Notice]`).
- Filter the branch by `Branch:`, not `FileVersion:`; older logs have no `Branch:` line before
  `FileVersion:`.
- Request, container and instance numbers are valid within one session only; the location id is
  valid across sessions.
- Resolve a location name through `RequestLocationInventory` and the inventory line in the same
  second.
- Resolve class names and GUIDs through game data; commodities only have the GUID.
- Remove player name, player number, shop and kiosk numbers, and other players' names in
  notifications (e.g. "Quantum Travel Calibration Started By …") **before storing**.

## 7. The `lg` model against the atlas

- **Consistent:** direction taken from the Queued line (`richtung = 'Queued'`); the three-line
  chain; UTC; stable location id; freight-elevator search text with hit count; platform states.
- **Beyond the atlas:** `v_aufzug_vorgang` / `v_aufzug_neue_exemplare` try to answer the atlas's
  "what was unstowed" gap — from the full-name search with one hit, and from instances first
  named in a parent-assignment warning. A heuristic with its own confidence grading, not a log
  fact.
- **Not modelled (or not visible without the parser):** commodity trade (price per cSCU, GUID),
  missions, rewards, refinery completion, items lost on death, insurance, stored ships. The
  columns `laden`, `preis` and `anschluss` suggest shop buys and equipment may be captured.

## 8. Checklist for a Basetool extension

**From the extractor export (game data):**

- Land it through the existing P4K import (`P4kCatalogDto`, `P4kImportService`) rather than a
  second pipeline; extend the DTO for new record kinds.
- Respect ADR-0283 (size/grade/class from the SC Wiki only) and REQ-DATA-015 (no per-cycle
  overwrite of another source's column) — or amend them first, with owner approval.
- Candidate content the Basetool does not hold yet: component stats per type (§4), commodity GUID
  → name mapping (needed for any Game.log trade data), star map locations and location ids,
  vehicle careers and roles, default loadouts, crafting records, mission templates, mining
  configs (the last three are outside the slim selection).
- Licence: the extractor is GPL-3.0-only. Consuming its **output** keeps the Basetool's own
  licence untouched; linking or vendoring its code does not. Game content rights and the
  hard-coded archive key are unresolved in the package itself.
- Do not commit game files or a built database to a repository (the package's own rule Q-006).

**From Game.log (a later step, through an approved client):**

- Client side only: the Game.log lives on the member's PC; data reaches the Basetool through the
  exchange (REQ-XCH-*) from an approved client such as `basetool-sc-extractor`.
- Strip personal data before sending (§6.7); never store player names (REQ-OBS logging rules,
  privacy).
- The log yields **events, not stock**: it can propose Lager or personal-inventory changes as a
  draft for the member to confirm, not replace a count.
- Likely useful mappings: shop and commodity prices with shop and date → purchasing / Materialbörse
  price observations; "Refinery Work Order(s) Completed" → refinery order status hint; "Awarded
  aUEC" and mission end → mission follow-up; Store/Move/Drop chains → personal inventory drafts;
  blueprint notifications → already covered by the SC Extractor.
- Every such feature touches an audited area, monitoring and the wiki — follow the root
  `CLAUDE.md` rules when it is built.

## 9. Open questions for the extractor's author

1. Can the Game.log parser that fills `lg.*` be shared, and under which licence?
2. Which export format will the finished extractor provide to third parties — the SQLite
   reference database, JSON (`export-json`), or a curated subset?
3. Does `lg.item_aktion` cover commodity trade, missions, refinery and death events, and which
   `aktion` values exist besides the six named in the views?
4. How is the German text in `dm.text` produced, and what do the `status` values mean?
5. How is the archive key meant to be handled if the extractor is distributed?
