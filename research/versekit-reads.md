# What VerseKit reads today, per catalogue domain

Research for [#3075](https://github.com/krt-profit/basetool/issues/3075), part of the map
[#3072](https://github.com/krt-profit/basetool/issues/3072). Status: findings as of 2026-11-08.

## Sources

Primary sources only. Each claim below names its file.

- **VerseKit source**, public, GPL-3.0: <https://github.com/Xharig/VerseKit>, `main` at commit
  `b10ffb465110d5a5d592c362f9b291812a6bb7ea` (2026-11-08). Paths below are relative to that
  repository. The crates that matter are `versekit-gamedata` (archive reading), `versekit-core`
  (fetching, caches, the Basetool sync) and `versekit-parity` (developer cross-check tools that do
  not run in the app).
- **VerseKit documentation**: `README.en.md` (the "Your data and the network" table and the
  credits), `MANUAL.en.md`, `PRIVACY.en.md`.
- **p4kx catalogue contract**:
  [`docs/basetool-catalog.md`](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md),
  `main` at `f43f12f3cd9ac73137ae593cfc6b9bd5ea0a27b1` (2026-11-07). VerseKit pins p4kx at tag
  `v0.4.2` (`Cargo.toml`).
- **scmdb**: the public data mirror VerseKit uses,
  `https://raw.githubusercontent.com/KrovaxCode/SCMDB_DATA/main/data` (`versions.json`,
  `cig_data_issues-4.10.2-live.12913470.json`, read 2026-11-08).
- **Basetool**: `docs/legal/approved-clients.md`, `docs/specs/external-exchange.md`
  (REQ-XCH-050), `docs/specs/p4k-catalogue.md`, ADR-0344, and the owner's decision comment on
  [#2900](https://github.com/krt-profit/basetool/issues/2900).

## The short answer

- **VerseKit already reads the archive through p4kx.** It depends on the p4kx crates
  (`p4kx-archive`, `p4kx-dataforge`, `p4kx-export`, `p4kx-localization`, tag `v0.4.2`), reads
  `Data\Game2.dcb` (then `Data\Game.dcb`) and the `global.ini` files out of the member's own
  `Data.p4k`, unencrypted entries only and never with a key (`crates/versekit-gamedata/src/lib.rs`,
  `record_encrypted`). For contracts and ship loadouts it even runs the **p4kx Basetool catalogue
  locally**, filtered (`contract_facts.rs`, `ship_slots.rs`).
- **Its identifiers are already the DataForge record GUIDs**, which are the p4kx GUIDs, for
  blueprints, items, manufacturers, ships, loadout parts and pools. The exceptions: contracts are
  keyed by their **debug name**, starmap places by their **record name in lower case**, and the
  Basetool sync identifies a blueprint by a recipe tag (`scRecord`) and its English name and a ship
  by maker and name, not by GUID.
- **Refresh is per archive state, not per clock.** The archive is read again when its path, size or
  modification time changes (once per patch), and only while the game is closed
  (`archive_lock`). scmdb is looked at every six hours for a new build; UEX daily to every 90 days;
  erkul once per patch.
- **Shops, prices, cargo and storage places do not come from the archive at all**: they come from
  UEX. The mining locations come from scmdb.
- **Several fields VerseKit uses are game texts** under ADR-0344 and so can never come from the
  Basetool: contract titles, mission types, contractors, faction display names and pickup places;
  starmap place names; the refinery method lines; item descriptions, from which VerseKit even
  parses the item class.
- **VerseKit already applies scmdb's `cig_data_issues-<build>.json`**, plus six corrections of its
  own, in memory, build-scoped, with a per-value `corrected` mark (`field`, `raw`, `value`,
  `reason`, `source`). Its model names #2899 as its source. That is close to the
  `corrected: { raw, reason }` marker #2900 asks for.

## How VerseKit reads, in general

| Source | What for | How often | Where |
|---|---|---|---|
| The member's `Data.p4k`: `Game2.dcb` through `p4kx-dataforge`, `global.ini` through `p4kx-localization` | blueprints, items, recipes, item values, contract facts, ship slots, places, quality goods, refining methods | once per archive state (path, size, time); waits until the game is closed | `versekit-gamedata/src/lib.rs`, `list.rs`, `values_store.rs` |
| The p4kx Basetool catalogue, built **locally** with Filters | contract facts (`contracts`); ships, items, manufacturers, loadouts (`entities/spaceships`, `entities/groundvehicles`, `entities/scitem`, `scitemmanufacturer`) | with the archive reading above | `contract_facts.rs`, `ship_slots.rs` (`FILTERS`) |
| scmdb (mirror first, then `scmdb.net/data`) | `versions.json`, `merged-<build>.json`, `crafting_blueprints-<build>.json`, `crafting_items-<build>.json`, `mining-data.json`, `cig_data_issues-<build>.json` | `versions.json` every 6 h; files once per build; 6 h retry after a failure | `versekit-core/src/scmdb.rs`, `game_texts/watch.rs` (`RECIPES_POLL_SECONDS`), `recipe_file.rs`, `list.rs` (`RETRY_SECONDS`) |
| UEX (`api.uexcorp.uk/2.0`) | commodity prices, vehicles and their buy/rent prices, terminals (storage places), item prices per shop, the goods list | daily (prices, vehicles); 30 days (terminals, item prices); 90 days (shop catalogue) | `commodity_prices.rs`, `ship_market.rs`, `places.rs`, `shops.rs`, `stock_fetch.rs` |
| erkul.games | ship slots, only for ships missing from the archive reading | once per game patch | `erkul_fetch.rs`, README |
| scunpacked-data (Star Citizen Wiki) | reputation thresholds of ranks | once per game version | `injection/ranks.rs` |
| `Game.log` | blueprint unlocks, landing places | every 3 s (adjustable) | `MANUAL.en.md`, `location.rs` |

The precedence VerseKit applies, from its own documentation (`value_source.rs`, `list.rs`,
`overrides.rs`):

1. the member's own corrections (`bp-overrides.json`: class, size, grade);
2. the archive reading, always first;
3. the known data errors, applied while reading;
4. scmdb of the same build: the fallback when the archive cannot be read, the filler for what the
   archive does not state (rewards and places of some contracts), and a standing cross-check whose
   differences go to the error log (`cross_check.rs`).

`value_source.rs` lists the Basetool (#2900) as a future source "only to compare". That conflicts
with the owner's decision on #2900 that the interface becomes the **leading** source; see the open
questions.

## Per domain

"Green" below means an identifier, short name or fact value that may leave the Basetool; "game
text" means a field p4kx marks `"x-gameText": true` or one ADR-0344 classifies yellow, which never
leaves.

### Blueprints and blueprint categories

**Read from the archive** (`catalog.rs`, `sources.rs`, `recipes.rs`):

- `CraftingBlueprintRecord` under `libs/foundry/records/crafting/blueprints/crafting/`, minus
  development leftovers (a whole-word `test`, `template`, `dummy`, `debug`, `deprecated`, `dev`):
  record GUID, class name, record path, name (localisation key and text, falling back to the
  item's name and then the class name).
- `default`: listed in `CraftingGlobalParams.defaultBlueprintSelection`.
- The item it makes (`processSpecificData.entityClass`, by GUID).
- The reward pools that list it (`BlueprintPoolRecord.blueprintRewards[].blueprintRecord`).
- Its sources: contracts of `ContractGenerator` records (lists `contracts`, `introContracts`,
  `legacyContracts`, `PVPBountyContract`, sub contracts included), unreleased ones left out, and
  reward tiers of `ScenarioProgress`. Per source: kind, debug name, record path, title, mission
  type, contractor, faction, places, minimum and maximum rank with reputation, shareable, cooldown,
  reputation gain, scenario points, fixed aUEC reward, chance, pool.
- Which pools a contract entry hands out, its giver faction, the places it is offered at and
  whether it can be shared come from the **local p4kx catalogue** (`blueprintRewards`,
  `giverFaction`, `offeredAt`, `canBeShared`; `contract_facts.rs`).
- Recipes through `p4kx_export::read_crafting`: per tier the craft time and slots; per slot its
  options (a resource in SCU or an item as a count, with its GUID and minimum quality) and the
  quality modifiers (gameplay property key, `startQuality`, `endQuality`, `modifierAtStart`,
  `modifierAtEnd`, kind).

**From scmdb** (`scmdb_catalog.rs`, `contract_info.rs`, `scmdb.rs`): `merged-<build>.json` gives
the ways to a blueprint (contracts, easiest first) and the pool of a blueprint no contract gives,
and fills rewards and places the archive does not state; `crafting_blueprints-<build>.json` and
`crafting_items-<build>.json` are the fallback and the cross-check of recipes and product values.

**Identifiers**: record GUIDs (blueprint, item, pool, material). Contracts by **debug name**.
Toward the Basetool's blueprint sync, VerseKit sends `scRecord` (the recipe tag, where unambiguous)
and the English name to `catalog/resolve` and keeps the returned Basetool key
(`basetool/blueprint_sync.rs`, `item_ref`), not a GUID.

**Refresh**: once per archive state; scmdb files once per build.

**Green vs game text**: GUIDs, class names, record paths, `default`, pools, ranks as thresholds,
reputation numbers, cooldowns, rewards, chances, recipe numbers, craft time and modifiers are green;
the blueprint `name` is a name of a non-text kind under the p4kx rule and so green. The contract
`title`, `missionType`, `contractor`, the faction's `displayName` and the pickup `place` are game
texts. VerseKit shows all of them per source, so those stay with its own reading.

**Blueprint categories**: no reader of `blueprintCategories` or `categoryGuid` was found in the app
crates. What VerseKit groups by is the recipe's tag, subtype and kind (`list.rs`, cache format 4).

### Items

**Read from the archive** (`catalog.rs` `Item`, `values.rs` `ItemValues`, `item_values.rs`):

- Record GUID, class name, record path, name, item type and sub type (attach type), size, grade,
  crafting item type (`cgItemType`), manufacturer (link and name).
- Parsed from the **English description**: the class (`Class:` line), the grade the description
  states and the manufacturer it states; and the description's localisation key.
- Values: integrity (`SHealthComponentParams.Health`); shield (`MaxShieldHealth`,
  `MaxShieldRegen`, `DownedRegenDelay`, `DamagedRegenDelay`); weapon fire modes (rate, delay, damage
  multiplier, pellets, heat, spread, recoil); ammunition (speed, lifetime, damage, detonation);
  magazine; armour (six resistances, temperature range, radiation capacity and dissipation); beams.
  `cross_check.rs` notes that cooling, power, quantum, radar, tractor and salvage values are not yet
  read from the archive; `item_values.rs` expects to switch to p4kx typed stat blocks once they
  exist.

**From UEX** (`shops.rs`): what a part costs and where. The lookup key is "the entity code of the
game, which is exactly the `uuid` of UEX", i.e. the item's entity GUID.

**Identifiers**: entity GUID throughout; UEX `id_item` as a fallback for a part without `uuid`.

**Refresh**: archive once per state; UEX item prices kept up to 30 days, the shop catalogue up to
90.

**Green vs game text**: GUID, class name, type, sub type, size, grade, `cgItemType`, manufacturer
link and every stat value are green. The description is a game text, so the **class, grade and
manufacturer VerseKit parses out of it** cannot be served as text. The Basetool could serve them
only as facts in their own right, for example the p4kx `itemClass` value, if its meaning matches;
see the open questions.

### Manufacturers

**Read from the archive** (`catalog.rs` `Manufacturer`; the local p4kx catalogue in
`ship_slots.rs`): record GUID, class name, short code, name. A built-in correction fixes the name of
one manufacturer record whose name text belongs to another maker (`corrections.rs`, `built_in`).

**Identifiers**: record GUID. **Refresh**: with the archive. **Green**: all of it; a
`description` is not read.

### Ships and paints

**From the local p4kx catalogue** (`ship_slots.rs`): per ship its GUID, class name, English
`name`, `manufacturerGuid` and `playability` (only `player` ships are kept).

**Paints**: p4kx fits a paint to a ship by the paint ports of the vehicle implementation, which sits
in the encrypted part of the archive. Without the key it fits 12 of 1,064 paints, so VerseKit reads
what the DataForge gives readably instead: the tags a paint requires of its port (`RequiredTags`)
and the paint ports of 21 ship records. Every other paint port stays without a rule
(`ship_slots.rs`, `PaintFacts`).

**From UEX** (`ship_market.rs`, `ships.rs`): the ship list with names, concept state and cargo, and
buy and rent prices, joined by `id_vehicle`, daily.

**From erkul.games**: slots of ships missing from the archive reading, once per patch.

**Hangar names**: own names at the ASOP terminal are keyed by the `vehicle_Name…` keys of
`global.ini` (`asop.rs`).

**Identifiers**: ship GUID and class name from the archive; UEX `id_vehicle`; erkul codes; toward
the Basetool's hangar sync, an `externalId` built from maker and name (`basetool/ship_sync.rs`).

**Refresh**: archive per state; UEX daily; erkul once per patch.

**Green vs game text**: GUID, class name, name, manufacturer, playability and paint-to-ship links
are green. Insurance and the ship base data of the p4kx contract are green but not read by VerseKit
today.

### Default loadouts

**From the local p4kx catalogue** (`ship_slots.rs`), written into files of the erkul shape
(`erkul-schiffe.json`, `erkul-teile.json`) so the existing readers keep working: per ship and per
part with ports, each port's name, types and sub types, `minSize`, `maxSize`, `portTags`,
`requiredTags`, `flags` (`uneditable`, `invisible`), the factory part (`itemGuid`,
`itemClassName`), `dockedVehicle` and the nested ports, down to turret guns, mining modules, rack
missiles and salvage modifiers.

The ports directly on a ship come without kind and size, because they sit in the encrypted vehicle
implementation; VerseKit then takes the factory part's kind and size, or derives the kind from the
port name (`ship_slots.rs`).

**Identifiers**: entity GUIDs. **Refresh**: rebuilt with the game values when the archive changes
(`values_store.rs`). **Green**: all of it.

### Commodities and resource groups

**From the archive** (`quality_goods.rs`): every good with a quality from its
`CraftingQualityQuantizationRecord`, matched to a `ResourceType`; whether it counts in SCU
(refined) or pieces; the piece size from `microSCU`. The readable name is found in the English
`global.ini` (`items_commodities_<stem>` and close matches).

**From scmdb** (`mining_data.rs`, `stock_fetch.rs`): `mining-data.json`, one file per build:
where which ore lies and its share per way of mining.

**From UEX**: raw-material prices (`commodities`, daily; `commodity_prices.rs`) and the goods list
(`stock_fetch.rs`, follows the patch, 90 days at most).

**Refining methods** (`refining_processes.rs`): the nine `RefiningProcess` records, but the three
levels VerseKit compares come from the line the terminal shows below each method (the
`processName` key with `_Details`). That line is a game text.

**Identifiers**: record GUIDs for quantizations and resource types; UEX numbers for prices.

**Green vs game text**: GUIDs, the SCU-or-pieces flag, sizes and the mining numbers are green; the
commodity names VerseKit takes from `global.ini` are localisation texts, and the p4kx contract
marks `processName` and `depositName` as game texts.

**Resource groups**: no reader of `resourceGroups` was found in the app crates. The developer tool
`versekit-parity abbau_katalog` compares the scmdb mining data with the p4kx kinds
`mineableCompositions`, `mineableElements`, `harvestableProviderPresets` and `refiningProcesses`
(`versekit-parity/src/mining_catalog.rs`), but nothing in the app reads them.

### Starmap objects

**From the archive** (`place_names.rs`): every `StarMapObject`, keyed by its **record name in lower
case**, with the English text its `name` points to, kept as `orte-namen.json` once per archive
state; the overlay's location row reads it. Which place the player is at comes from `Game.log`
(`location.rs`).

**From UEX** (`places.rs`): storage places (stations, cities, outposts) from the `terminals` list,
30 days.

**Identifiers**: record name, not GUID.

**Green vs game text**: the starmap `name` is a game text (p4kx contract, ADR-0344), and the name
is the only thing VerseKit takes from a starmap object. So nothing VerseKit reads today in this
domain could come from the interface. GUID, type, parent, system and position are green but unused.

### Shops

Nothing comes from the archive. Shops, what each sells and at which price come from UEX
(`items_prices`, `categories`, `items`, `items_attributes`; `shops.rs`), the shop catalogue built in
the background and kept up to 90 days, the prices per part up to 30 days. The p4kx `shops` kind
holds no prices or stock, only the identity and location of a shop placement, so it does not cover
what VerseKit uses this domain for.

## How VerseKit applies scmdb's `cig_data_issues` today

From `crates/versekit-gamedata/src/corrections.rs` and `crates/versekit-core/src/recipe_file.rs`:

- **Fetch**: `cig_data_issues-<build>.json` for the build of the member's archive, in scmdb's
  naming (`4.10.2-live.12913470`). When the list for the current build is missing it asks again
  after 6 h. The list is kept as `cig-datenfehler.json` beside the recipe file; a fingerprint over
  the entries notices a changed list and makes the readers correct anew.
- **Model**: a `Correction` names the section (blueprint, pool, entity, contract, manufacturer),
  the record (GUID in lower case; a contract by its **debug name**), the field, the raw value where
  known, the meant value (or none: mark only), whether the raw value is a reference to a missing
  record, the reason in English, the build (or none: every build), and the source (`scmdb` or
  `versekit`). The module doc says the model follows #2899.
- **What it reads from scmdb's list**:
  - `blueprintRecords`: `expected.entityClass` (product), `expected.name`, and modifier range
    values in a `slot` / `propertyKey`;
  - `blueprintPools`: `duplicateReferences` (the second of two equal references, with the meant
    replacement);
  - `entities`: `expected.attachType`, `cgItemType`, `manufacturer`;
  - `contracts`: only `field = factionGuid`.

  The build-4.10.2 list on the mirror holds 5 blueprint, 2 pool, 14 entity and 3 contract entries.
- **Its own list** (`built_in`): six corrections found with an independent reader on build
  4.10.197: the product of two blueprints (one pointing at the wrong shield, one at an entity that
  does not exist), the name of one manufacturer record (`scitemmanufacturer/mxox`), and the faction
  of three Foxwell hauling contracts. They have no build scope; the record corrections carry the raw
  value, so they hold back by themselves once CIG changes the data. They are applied first, so a
  cause named once (a manufacturer record) wins over the single items scmdb lists.
- **Applying**: in memory only, never written into a file. A correction made for another build is
  skipped (after a patch the same GUID may hold other values). The outcomes are `Applied`,
  `Marked` (no meant value known), `AlreadyRight` (CIG fixed it), `Repeated`, `RawChanged` (held
  back until someone checks), `NotFound`, `Ambiguous` and `Elsewhere`.
- **Marker in the data**: each corrected value carries `Corrected { field, raw, value, reason,
  source }`; the user interface shows a hint with the raw value and where the correction came from,
  and tells the member about a fixed error and a held-back correction.

**Fit with #2899 / #2900**: the Basetool's planned `corrected: { raw, reason }` covers `raw` and
`reason`. VerseKit additionally uses `field`, `value`, `source`, a build scope, the
missing-reference case and the hold-back outcome when the raw value changed. Contracts are keyed by
debug name, not GUID.

## What VerseKit would need from the interface to make it the leading source

Taken from what VerseKit reads today, limited to green fields of the domains in scope on #3072:

| Domain | Fields | Identifier |
|---|---|---|
| Blueprints | GUID, class name, record path, name, `default`, product GUID, pool GUIDs; recipe tiers with craft time, slots, options (resource or item GUID, SCU or count, minimum quality), modifiers (property key, four range values) | blueprint GUID |
| Contracts (as the source of a blueprint) | debug name or GUID, pools awarded with chance, giver faction (GUID), offered-at places (GUID), shareable, rank thresholds, reputation gain, cooldown, fixed reward. Not title, mission type, contractor, faction display name, pickup place | contract GUID plus sub contract GUID in p4kx; debug name in VerseKit |
| Items | GUID, class name, type, sub type, size, grade, `cgItemType`, manufacturer GUID, integrity, shield, fire modes, ammunition, magazine, armour, beams; a class fact in place of the description parse | entity GUID |
| Manufacturers | GUID, class name, code, name | GUID |
| Ships and paints | GUID, class name, name, manufacturer GUID, playability; paint item GUID to ship GUIDs | GUID |
| Default loadouts | ports with name, types and sub types, sizes, port tags, required tags, flags, factory part GUID, docked vehicle, nested ports | entity GUID |
| Commodities and resource groups | quantization GUID to resource type GUID, SCU or pieces, piece size | GUID |
| Corrections | per corrected value at least `raw`, `reason`; VerseKit's model also uses `field`, `value`, source, build | as the value it sits on |

What stays with VerseKit's own reading whatever the interface carries: every game text above
(contract texts, starmap names, refinery method lines, descriptions, `global.ini` names it uses for
display and for the blueprint notes it writes into `global.ini`), plus everything VerseKit takes
from UEX, erkul, scunpacked-data and `Game.log`.

## Open questions for VerseKit's maintainers

These cannot be answered from the source and are not guessed here.

1. **Leading or comparing?** `value_source.rs` lists the Basetool as a future source "only to
   compare", while the owner's decision on #2900 makes the interface the leading source and
   VerseKit's own reading the fallback. Will VerseKit adopt that order, and per domain or all at
   once?
2. **Contract identity**: VerseKit keys contracts (and scmdb keys its contract corrections) by debug
   name; p4kx keys them by `guid` plus `subContractGuid`. Which key does VerseKit want, or both?
3. **Item class**: VerseKit parses class, grade and manufacturer from the English description, a
   game text the Basetool cannot send. Would a fact field (the p4kx `itemClass`, or the Basetool's
   own) replace that parse, and does its meaning match the `Class:` line?
4. **Blueprint sync identity**: the sync resolves blueprints by `scRecord` and English name through
   `catalog/resolve`. Once GUIDs are shared, should the sync move to the blueprint GUID?
5. **Ship identity in the hangar sync**: `externalId` from maker and name. Should it become the ship
   GUID?
6. **Domains VerseKit does not read today**: no app code reads blueprint categories, resource
   groups, starmap object facts (beyond the name) or the p4kx shop kind. Does VerseKit want them from
   the interface at all, or can those endpoints wait for another client?
7. **Shops**: VerseKit's shop data (what is sold where, at which price) comes from UEX and is not
   in the p4kx catalogue. Is a shop endpoint without prices useful to VerseKit?
8. **Corrections marker**: is `corrected: { raw, reason }` enough, or does VerseKit need `field`,
   `value`, the source, the build scope and the "raw value changed, held back" state that its own
   model carries? Does it keep applying scmdb's list and its own six corrections alongside the
   Basetool's, and in which order?
9. **Refresh**: VerseKit re-reads once per archive state and polls scmdb every 6 h. What cadence
   and conditional-request behaviour (ETag per domain) does it expect, and what should it see when
   the Basetool has not yet imported the member's build?
10. **Build match**: VerseKit scopes corrections to the exact build of the member's archive. How
    should it treat interface data from a different LIVE build than the one installed?
11. **Item stats coverage**: cooling, power, quantum, radar, tractor and salvage values are not yet
    read. Would VerseKit take them from the interface first?
12. **Paints and port kinds without the key**: VerseKit fits only part of the paints and lacks the
    kinds and sizes of ship ports because they sit in encrypted entries. Does it expect the
    interface to carry the complete paint fits and port data the Basetool's import holds?
