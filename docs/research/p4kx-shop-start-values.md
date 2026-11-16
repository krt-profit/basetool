# p4kx shop start values against observed stock

Research for [#3481](https://github.com/krt-profit/basetool/issues/3481) (map
[#3477](https://github.com/krt-profit/basetool/issues/3477), terminal restock rates).

## Question

What do the offline `CurrentInventory` and `MaxInventory` that p4kx v0.5.0 (draft, schema 3)
exports mean for commodity terminals? Which terminals and commodities do they cover, what does
`CurrentInventory` stand for, do the values match the stock UEX observes, and could `MaxInventory`
serve as the cap a refill runs towards or as the scale that turns UEX grades 1–7 into SCU?

## Summary

- **The values are a legacy offline snapshot, not the live economy.** p4kx v0.5.0 exports them
  verbatim as `offlineDesignTime`; the shop component that names the file calls it
  `offlineInventoryJSON`. Prices sit at old per-unit levels (Gold 5.84, Laranite 28.07, Waste 0.01
  per unit against 30,000, 8,500 and 199 aUEC per SCU on UEX for 4.10.3), files still exist for
  Port Olisar and five "Small Base" admins, and none exists for Orison or any Pyro place.
- **Coverage is tiny.** 21 inventory files with commodity lines hold 397 lines and 36 resolvable
  commodities; 162 lines carry IDs that name no DataForge Record, including all 21 lines of the
  Area 18 TDD file. UEX lists 161 commodity terminals (114 live) and 85 commodities at the Area 18
  TDD alone. p4kx links only some of these files to a shop.
- **`CurrentInventory` is a frozen fill level, not a spawn stock or a target** (inferred): mostly
  fractional values between 0 and the maximum, the same odd value repeated across commodities of a
  file, 54 lines at 0, 11 at the maximum and 5 above it.
- **The values do not match observed stock.** In a 12-pair sample, six pairs have grade-7
  readings that pin the cap. Waste matches: a `MaxInventory` of 7,500 read as centi-SCU equals the
  75 SCU cap UEX sees at the Lorville CBD and at 39 other terminals. Gold is about 35 times too
  large at both terminals sampled. Processed Food at TDD Area 18 and Agricium at Levski trade on
  the opposite side of the terminal. Hydrogen Fuel at Levski has no file line. The remaining pairs
  give only lower bounds.
- **`MaxInventory` is neither a usable refill cap nor a grade scale.** The game's grade thresholds
  live in `GlobalShopCommodityParams` (six supply and six demand level floats), which p4kx v0.5.0
  retains raw in `configurations.shopCommodityGlobals.rows[].additionalFields`; their values were
  not read. UEX's grade-7 readings are the better cap source.

## Sources

| Source | Version | What was read |
|---|---|---|
| [`p4kx-org/p4kx`](https://github.com/p4kx-org/p4kx) branch `codex/implement-v0.5.0` | [`1f614ff4`](https://github.com/p4kx-org/p4kx/tree/1f614ff41bb35f78e694b72fc5f370d16737f0f0), draft PR [#1117](https://github.com/p4kx-org/p4kx/pull/1117), milestone [v0.5.0](https://github.com/p4kx-org/p4kx/milestone/17) open (25 open, 136 closed) | `docs/catalog.md`, `docs/shops-and-terminals.md`, ADR-0073, `docs/catalog-cargo-loading.md`, `shop_inventory_fields.rs`, `catalog_v3/cargo_globals.rs`, the real-Archive shop-inventory test, issue [#1070](https://github.com/p4kx-org/p4kx/issues/1070) |
| [`versetools/versetools`](https://github.com/versetools/versetools) | [`533c1f6d`](https://github.com/versetools/versetools/blob/533c1f6d3c0bd607d58941aa86018086f85707aa/apps/sc-data-extractor/STAR_CITIZEN_DATA_CATALOG.md#L240-L275) | `STAR_CITIZEN_DATA_CATALOG.md`, shop inventory section |
| [`VeeLume/sc-holotable`](https://github.com/VeeLume/sc-holotable) | [`353a7fac`](https://github.com/VeeLume/sc-holotable/blob/353a7fac91ef577f78b5a54b835f358af64ce33c/crates/sc-extract-generated/src/generated/globalshopparams/types.rs#L73-L103) | generated `GlobalShopCommodityParams` fields |
| [`ampynjord/starvis`](https://github.com/ampynjord/starvis) extractor and public API | repo [`22b28fd5`](https://github.com/ampynjord/starvis/blob/22b28fd5aec25d51b73dc1ff62e39b048dfd48d3/extractor/src/persisters/shops.ts#L270-L333); API data `4.10.2` LIVE, extracted 2026-10-25 | `GET /api/v1/shops/{id}/inventory?env=live` for the 22 shops built from commodity-like inventory files |
| [UEX API 2.0](https://api.uexcorp.space/2.0/), token-free | game version `4.10.3`, read 2026-11-16 | `terminals?type=commodity`, `commodities_prices` (10 terminals; Waste; Processed Food), `commodities_prices_history` (16 pairs); 20 requests |

No game Archive was opened and p4kx was not run. The file values come from starvis, a second
extractor that parses the same `Data/Scripts/ShopInventories/*.json` files and names one shop per
file after its stem (`shops.ts` L277-L333); versetools quotes one of the same lines (Waste
3,817.8 of 7,500), so two extractors agree on the values.
[`StarCitizenWiki/scunpacked-data`](https://github.com/StarCitizenWiki/scunpacked-data/blob/db00b749833ebe4c4687f766c15fb88ba093fd6e/trade_locations.json)
publishes no shop inventory (its `trade_locations.json` holds produce and consume tags), and
GitHub code search found no committed copy of the inventory files.

## Findings

### What p4kx v0.5.0 exports

- `definitions.shopInventories` has `meaning: "offlineDesignTime"`, covers every `.json` file in
  `Data\Scripts\ShopInventories` including orphan and placeholder files, keeps every number as its
  exact authored token, and says that "Price, stock and rental values describe offline design
  settings" ([`catalog.md` L2444-L2468](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/docs/catalog.md#L2444-L2468);
  line fields in [`shop_inventory_fields.rs` L40-L48](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/crates/p4kx-export/src/shop_inventory_fields.rs#L40-L48)).
- A file reaches a shop only through the `SCShop` component parameter `offlineInventoryJSON` (or
  `offlineInventoryXMLName`) of its container or prefab
  ([`catalog.md` L2418-L2433](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/docs/catalog.md#L2418-L2433));
  `declarations.shopInventoryResolution` records the status per shop.
- No unit is stated for `CurrentInventory` or `MaxInventory`; "unit-bearing source fields retain
  their original form" (L2467-L2468).
- **Doc drift:** the shops section still ends "Prices, stock and demand are never exported"
  ([L2514-L2515](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/docs/catalog.md#L2514-L2515)),
  which contradicts L2444-L2468.

### Which terminals and commodities are covered

- **Commodity terminals in the Archive:** `SCShop_CommEx_TDD_*` (four `commex` containers) and
  `SCShop_Platinumbay_small_Commodity` (named among the 28 `SCShop_Platinumbay` shops); "a
  commodity has no per-terminal list in the Archive" ([`shops-and-terminals.md` L14-L17, L112-L117](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/docs/shops-and-terminals.md#L112-L117)).
  16 registry shops are flagged `commodityExchange` on LIVE 4.10.197
  ([`catalog.md` L2405](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/docs/catalog.md#L2405)).
- **Few files reach a shop.** 62 of 119 files, among them the `mining_*` files and most city and
  station files, are named by nothing in the Archive
  ([ADR-0073 L78-L83](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/docs/adr/0073-shop-components-are-read-in-every-container-by-anchor-and-placeholder-inventories-are-no-list.md#L78-L83)).
  `inv_commex_tdd_area18` is linked, but all 21 of its item IDs "are no Record of the DataForge";
  ADR-0073 calls them "item and commodity definitions that the game removed" (L104-L108).
  `inv_admin_hurston_lorville` is named by the prison ore deposit, not by a Lorville terminal
  (L102-L103).
- **The commodity-bearing files** (starvis, 4.10.2): `Admin` for Area 18, New Babbage,
  Hurston/Lorville, Grim HEX, Levski, Port Olisar, Truckstop Base D and Small Base A–E;
  `CommEx_TDD` for Area 18 and New Babbage; `CommEx_Transfers_Lorville`; `Mining` for Area 18,
  Lorville, New Babbage, Levski, Grim HEX and Port Olisar. 21 files, 397 lines, 36 named
  commodities (ores, gases, halogens, food, medical supplies, scrap, waste and seven vice goods).
  162 lines (both TDD files, the five Small Base files) carry IDs no extractor resolves.
- **The TDD lines can be named by inference:** each of the 21 Area 18 TDD lines has exactly one
  Lorville line with the same `MaxInventory` and a price within 0.1 (Laranite 28.07 / 500,000,
  Gold 5.84 / 3,000,000, …), so the TDD files are the Lorville commodity list with old IDs. The
  New Babbage TDD file is identical to the Area 18 one.
- **No Pyro terminal and no Orison terminal has a file**; Levski is the only place in Nyx.

### What `CurrentInventory` stands for

Verified facts about the values:

- 175 of 397 values are whole numbers: the 54 zeros and the round Small Base values (454,400 of
  1,152,000). The other 222 are fractional with four decimals.
- The same fractional value recurs across commodities of one file: 14 ores at 22,926.0394 of
  50,000 in `Mining_GrimHex`, Gold and Diamond at 851,887.2776 of 3,000,000 at Lorville.
- The fill ratios spread from 0 to 1: 54 lines at 0, 11 at the maximum, the bulk between 0.1 and
  0.5. Five Small Base lines hold 454,400 against a maximum of 3,000.

Inferred: the fractional values look like the state of a running simulation written out once,
and the parameter name `offlineInventoryJSON` suggests the stock a shop shows when it gets no
inventory from the shop service. Nothing in p4kx, versetools ("static shop inventory
snapshots/defaults", "client defaults, not authoritative live server stock") or the files says the
server seeds a fresh shard with them, so "spawn stock" is unsupported and "design target" does not
fit values at 0 or above the maximum. The prices and the place set (Port Olisar, no Orison) date
the snapshot to the 3.x shop system.

### Units

The game data names no unit. Read as centi-SCU (1/100 SCU, the old per-unit trade quantity), the
Waste line matches an observed cap exactly (7,500 → 75 SCU); read as SCU or micro-SCU, no line
matches. The prices fit the same reading: 3.x prices were per unit. Inferred, not stated by any
source.

### Sample comparison with UEX

File values from starvis (4.10.2) divided by 100; UEX values for 4.10.3, `commodities_prices` and
`commodities_prices_history` (rows 2026-11-12 to 2026-11-16). "Sells" means the terminal sells to
players (UEX buy side, `scu_buy`); "buys" means it buys from players (UEX sell side, stock
`scu_sell_stock` and demand `scu_sell`). The file-to-terminal pairing is by name.

| UEX terminal ↔ file | Commodity | File: side, current → max SCU | UEX: side, highest SCU seen | Grade-7 readings |
|---|---|---|---|---|
| CBD Lorville (18) ↔ `CommEx_Transfers_Lorville` | Waste | sells, 39 → 75 | sells, 75 | 75 ×2 (65 at g6, 56 at g5) |
| same | Gold | buys, 8,519 → 30,000 | buys, stock 840, demand 1,133 | stock 840 ×2 |
| same | Laranite | buys, 2,140 → 5,000 | buys, stock 208, demand 728 | none |
| same | Titanium | buys, 3,926 → 12,000 | buys, stock 642, demand 4,494 | none |
| same | Scrap | **sells**, 15,343 → 23,040 | **buys**, stock 1,139 | none |
| TDD Area 18 (12) ↔ `CommEx_TDD_Area18`, names inferred | Processed Food | **buys**, 72,095 → 200,000 | **sells**, 12,000 | 12,000 ×5 (10,089 at g6) |
| same | Gold | buys, 11,861 → 30,000 | buys, stock 840, demand 1,117 | stock 840 ×1 |
| same | Laranite | buys, 2,326 → 5,000 | buys, stock 1,455 (g5), demand 2,037 | none |
| same | Tungsten | buys, 9,628 → 20,000 | buys, stock 7,716 (g5), demand 10,802 | none |
| Admin GrimHEX (27) ↔ `Admin_GrimHex` | Gold | buys, 5,367 → 23,040 | buys, stock 133 (g2), demand 466 | none |
| Levski (778) ↔ `Admin_Levski` | Agricium | **buys**, 995 → 6,000 | **sells**, 750 | 750 ×1 (87–116 at g3) |
| same | Hydrogen Fuel | no line (Hydrogen: sells, 4,472 → 23,040) | sells, 12,000 | 11,968–12,000 ×7 (8,572 at g5) |

- **Pairs with a grade-7 reading** pin the cap (grade 7 is 86–100 % of the cap on UEX's scale):
  Waste matches, Gold (840 SCU against 30,000) is about 35 times too large, and Processed Food and
  Agricium trade on the opposite side with file caps 17 and 8 times the observed ones.
- **Pairs without one** give lower bounds only. Under UEX's bands the grade-5 readings at TDD
  Area 18 (Laranite 1,455 SCU, Tungsten 7,716 SCU) point to caps of roughly 40–70 % of the file
  maxima; Gold at Grim HEX (133 SCU at grade 2) points to about 500–900 SCU against 23,040.
- **Waste is the only match**, and it is a common cap rather than a file value: 40 of 57
  Waste-selling terminals on UEX reach grade 7 at 75 SCU, most of them places without a file.
  Two Pyro terminals (Megumi Refueling, Jackson's Swap) cap Waste at 30,000 SCU, the value the
  Levski, Port Olisar and Grim HEX files hold (3,000,000), but Pyro has no file.
- **Observed caps of the live terminals are uniform design values the files lack:** 12,000 SCU
  for Hydrogen Fuel, Quantum Fuel, ship ammunition and countermeasures at every admin terminal
  sampled, 12,000 for Processed Food at TDD Area 18 and two station admins, 18,000 for Human Food
  Bars at Grim HEX.
- **Prices disagree by a factor of 3 to 200** read as centi-SCU (Laranite 2,807 against 8,500,
  Gold 584 against 30,000, Waste 1 against 199 aUEC per SCU at the Lorville CBD), so the files
  describe a different economy, not the same one in another unit.

### `MaxInventory` as refill cap or grade scale

- **As a refill cap:** no. It matches one commodity, is far off or on the wrong side for every
  other pinned pair, covers no Pyro terminal and a small share of the Stanton ones, and for TDD
  Area 18 is keyed to IDs that name no Record.
- **As a grade scale:** no. The game turns stock into a status through the supply and demand level
  thresholds of `GlobalShopCommodityParams` (`no`, `very_low`, `low`, `medium`, `high`,
  `very_high` for each side, f32;
  [sc-holotable L81-L103](https://github.com/VeeLume/sc-holotable/blob/353a7fac91ef577f78b5a54b835f358af64ce33c/crates/sc-extract-generated/src/generated/globalshopparams/types.rs#L81-L103)).
  p4kx v0.5.0 types only the auto-loading prices of that Record and keeps the other members in
  `additionalFields`
  ([`cargo_globals.rs` L107-L131](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/crates/p4kx-export/src/catalog_v3/cargo_globals.rs#L107-L131),
  [`catalog-cargo-loading.md` L13-L14, L39](https://github.com/p4kx-org/p4kx/blob/1f614ff41bb35f78e694b72fc5f370d16737f0f0/docs/catalog-cargo-loading.md#L13-L14)).
- **UEX's equal 14–15-point bands fit some readings and not others:** Hydrogen Fuel 8,572 of
  12,000 (71 %) at grade 5 fits; Waste 65 of 75 (87 %) at grade 6 and Agricium 116 of 750 (15 %)
  at grade 3 do not. The game's thresholds may be unequal, or the cap may differ from the highest
  reading.

## Verified and inferred

| Statement | Status |
|---|---|
| p4kx v0.5.0 exports the four numbers verbatim as `offlineDesignTime`, with no unit, for every physical file | Verified, p4kx `1f614ff4` |
| The shop link runs through `offlineInventoryJSON`; the `mining_*` and most city and station files are named by nothing; the Area 18 TDD file's 21 IDs name no Record | Verified, p4kx docs and ADR-0073 (LIVE 4.10.197 survey) |
| `GlobalShopCommodityParams` holds six supply and six demand level floats, kept in p4kx `additionalFields` | Verified, sc-holotable `353a7fac` and p4kx code; values not read |
| The file values, coverage counts and fill-ratio statistics | Read from starvis (4.10.2 LIVE extraction), a secondary source; one line cross-checked against versetools |
| UEX caps, grades and prices | Verified, UEX API, 4.10.3, 2026-11-16 |
| Inventory unit is centi-SCU | Inferred from the Waste match and the per-unit prices |
| TDD line names | Inferred from unique `MaxInventory` and price matches |
| File-to-terminal pairing | Inferred by name; the p4kx resolution of these shops was not read |
| `CurrentInventory` is a frozen offline snapshot of the 3.x economy | Inferred |

## Open points for the decision tickets

1. Whether the restock model takes its cap per terminal and commodity from UEX's highest grade-7
   reading and drops the p4kx inventory values entirely; REQ-P4K-042 already keeps prices and
   stock out of the P4K import.
2. Whether grades become SCU through the game's thresholds: read the twelve level floats from a
   v0.5.0 export (`configurations.shopCommodityGlobals.rows[].additionalFields`) and test them
   against UEX readings such as Waste 65 of 75 at grade 6. A candidate p4kx issue could type them.
3. For [#3471](https://github.com/krt-profit/basetool/issues/3471) at the 0.5.0 tag: which of the
   16 commodity-exchange shops resolve to which file, whether the inventory unit gets documented,
   and the "never exported" sentence at `catalog.md` L2514-L2515.
4. Whether the uniform observed caps (12,000 SCU for fuels, ammunition and TDD Processed Food,
   75 SCU for Waste) may seed defaults for terminal and commodity pairs without a grade-7 reading.
