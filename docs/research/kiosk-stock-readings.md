# What a member can read at a commodity kiosk

Doc type: research snapshot; findings for the terminal-restock map, not a binding specification.

- Ticket: [#3482](https://github.com/krt-profit/basetool/issues/3482), map
  [#3477](https://github.com/krt-profit/basetool/issues/3477) (trade-route map
  [#3450](https://github.com/krt-profit/basetool/issues/3450)).
- Snapshot: 2026-11-16. Game UI data and localisation: LIVE build `4.10.193.11644`
  (`sc-alpha-4.10.0`) as tracked by [StarCitizenDiff][scdiff] at `908b76a`; UEX reports LIVE
  `4.10.3`. Kiosk screenshots: the test fixtures of [SC Trade Companion][stc] (added 2026-04-13
  with the 4.7 number format).
- Local baseline: `origin/main` at `470f3bc09`.
- Method: public sources only — the game's UI records (`BuildingBlocks` canvases of the commodity
  kiosk) and `global.ini`, the record `globalshopcommoditydata`, the source code and test fixtures
  of SC Trade Companion, the API description and changelog of SC Trade Tools, UEX's
  `POST /data_submit` documentation, the UEX Datarunner client, the SC Wiki, RSI patch notes, and
  the resolutions of [#3461][r3461], [#3475][r3475] and [#3465][r3465]. Nobody was contacted;
  nothing was submitted anywhere.
- Labels: **verified** = read in game data, source code, documentation or a screenshot;
  **inference** = a consequence not demonstrated in game; **not found** = looked for and absent.

## Summary

- Per commodity line, both kiosk tabs show the same three stock elements: the shop's **current
  quantity** as a whole number (SCU, or cSCU / µSCU / units for some goods), a **level name** out
  of seven (plus a transient "Inventory Updating"), and a **vertical bar** driven by the same
  level value. Price per unit and the accepted box sizes sit beside them. **verified**
- **No maximum** is shown anywhere: the shop list items bind no maximum, and the screenshots show
  none. **verified** (not found in the 4.10 UI records)
- The sell tab ("Local Market Value") shows the **shop's own stock** of the commodity, not the
  room left: "0 SCU · Out of Stock" is the state of highest demand. The room left is
  maximum − current and cannot be read off the kiosk. **verified** (screenshot) / room: **inference**
- The level names map to fill bands of the shop line with cut points at 0, 10, 25, 50, 75 and
  95 % (`globalshopcommoditydata`); the bands are unequal, unlike UEX's 1–7 grades and the
  companion's k/6 saturation. **verified** (values) / band reading: **inference**
- A member reads quantity and level **exactly**, by hand or from a screenshot; the bar adds
  nothing. Quantity plus level bound the line's maximum to an interval; "Max Inventory" pins it
  within about 5 %. **inference**
- SC Trade Companion OCRs English kiosk screenshots on a hotkey and submits per line: side,
  commodity, price, quantity, `saturation` = level as k/6, box sizes, time. SC Trade Tools turns
  this into an estimated `maxQuantityInScu` with an unpublished method. UEX takes the same
  quantity and a 1–7 status, with a screenshot for new datarunners. **verified**
- Of the planned Basetool signals, only the sold-out / no-demand **mark** carries a stock-like
  quantity (route-local, unshared); **ticks** record what was actually traded, a lower bound;
  **availability reports** are a three-step ordinal against UEX's figure. None records the
  kiosk's quantity and level as a shared, timestamped reading. **verified** (resolutions)

## 1. What the kiosk shows (4.10, verified against 4.7 screenshots)

### Layout

The kiosk ("Commodities") has a left column **Your Inventories** (the player's cargo at the
selected location) and a right column **Shop Inventory** with two tabs:

| Tab label | Localisation key | Side | Shop list sections |
| --- | --- | --- | --- |
| Buy | `shop_ui_CommodityKiosk_ShopTab_Supply` | shop sells, player buys | In Stock / Out Of Stock (`shopInStockList`, `shopNoStockList`) |
| Local Market Value | `shop_ui_CommodityKiosk_ShopTab_Demand` | shop buys, player sells | In Demand / No Demand (`shopInDemandList`, `shopNoDemandList`) |

The player column groups the player's own cargo as **In Demand**, **No Demand** and **Cannot
Sell** (`…_InDemand`, `…_NoDemand`, `…_NoMarket`). Source: `ic_ter_commodity_shoplist.xml` and
`global.ini` in [StarCitizenDiff][scdiff]; the three screenshots in
[SC Trade Companion's fixtures][stc-img]. **verified**; which sections belong to the Local Market
Value tab is read from the list names, as no sell-tab screenshot shows a section header
(**inference**).

### One commodity line

The records `ic_ter_commodity_shoplist_listitem_shopsupply.xml` (Buy) and
`…_shopdemand.xml` (Local Market Value) bind these values per line:

| Element | Binding | Shown as | Label |
| --- | --- | --- | --- |
| Quantity | `commodityAmountAvailable` (integer) | whole number with thousands separators, unit from `scuLabelSwitch`: SCU, cSCU, µSCU or Units | verified |
| Level | `commodityLevelAbstract` (integer) | a level name under the commodity name, coloured | verified |
| Bar | `commodityLevelAbstract` (same integer) | vertical bar beside the name, one widget per value 0–7 | verified |
| Price | `commodityPricePerUnit` (number) | "¤2,767/SCU" | verified |
| Box sizes | `cargoBoxSize` | "Available Cargo Size (SCU)" with 1 2 4 8 16 24 32 | verified |
| Discount | `itemPercentDiscount`, `itemShowDiscount` | percentage; status line "A recent event has affected this item." | verified (binding) |
| Maximum | — | nothing | not found |

The Buy tab captions the quantity **"Shop Quantity"** (`shop_ui_CommodityKiosk_ShopQuantity`);
the Local Market Value tab shows the number without a caption. **verified**

Screenshot readings (4.7, HUR-L3 Thundering Express Station):

| Tab | Commodity | Quantity | Level (colour) | Bar | Price |
| --- | --- | --- | --- | --- | --- |
| Buy | Waste | 75 SCU | Max Inventory (green) | full | ¤229/SCU |
| Buy | Nitrogen | 230 SCU | Low Inventory (orange) | about one third | ¤2,767/SCU |
| Local Market Value | Hydrogen | 0 SCU | Out of Stock (green) | empty | ¤1,100/SCU |
| Local Market Value | Stims | 702 SCU | Medium Inventory (blue) | about half | ¤5,400/SCU |
| Local Market Value | Dolivine, Compboard, Degnous Root, Construction Materials, Beryl | 0 SCU | Out of Stock (green) | empty | — |

Colour reads as "good for the player": a full shop is green on Buy, an empty shop is green on
Local Market Value. **inference** (two screenshots)

### Level names per tab

Both tabs name the **shop's inventory**; the keys behind them are supply levels on Buy and
inverted demand levels on Local Market Value (`…_listitem_shopsupply_statustext.xml`,
`…_shopdemand_statustext.xml`, `global.ini`). **verified**

| Fill band of the line (inference) | Buy: key → text | Local Market Value: key → text | UEX status | Companion `saturation` |
| --- | --- | --- | --- | --- |
| 0 % | `NoStock` → Out Of Stock | `Demand_Critical` → Out of Stock | 1 | 0 |
| up to 10 % | `Supply_VeryLow` → Very Low Inventory | `Demand_VeryHigh` → Very Low Inventory | 2 | 1/6 |
| up to 25 % | `Supply_Low` → Low Inventory | `Demand_High` → Low Inventory | 3 | 2/6 |
| up to 50 % | `Supply_Medium` → Medium Inventory | `Demand_Medium` → Medium Inventory | 4 | 3/6 |
| up to 75 % | `Supply_High` → High Inventory | `Demand_Low` → High Inventory | 5 | 4/6 |
| up to 95 % | `Supply_VeryHigh` → Very High Inventory | `Demand_VeryLow` → Very High Inventory | 6 | 5/6 |
| above 95 % | `Supply_Critical` → Max Inventory | `Demand_None` → Max Inventory | 7 ("No Demand" on the sell side) | 1 |
| — | `Supply_Emergent` → Inventory Updating | `Demand_Emergent` → Inventory Updating | — | — |

- The cut points come from `globalshopcommoditydata`: `noSupplyLevel` 0, `VeryLowSupplyLevel` 10,
  `LowSupplyLevel` 25, `MediumSupplyLevel` 50, `HighSupplyLevel` 75, `VeryHighSupplyLevel` 95,
  and the demand levels mirrored (100, 95, 75, 50, 25, 10). **verified**
- That a line's level is its current / maximum against these cut points, and on which side a
  boundary value falls, is not in the data. **inference**
- `Supply_None` → "No Inventory" exists in `global.ini`, but the Buy status record uses
  `NoStock` instead. **verified**
- UEX documents its grades as stock bands of 14–15 points each
  ([#3451 note](https://github.com/krt-profit/basetool/issues/3451#issuecomment-6898489196));
  the companion sends k/6. Neither matches the game's bands. **verified** (values)

### The bar

Both bar records (`…_listitem_shopsupply_inventorybar.xml`, `…_shopdemand_inventorybar.xml`)
switch eight widgets on the integer `commodityLevelAbstract` (values 0–7). The bar is a stepped
picture of the level name and carries no finer fill value. **verified** (UI record); the
screenshots agree (Low ≈ ⅓, Medium ≈ ½, Max full, Out of Stock empty).

### Messages that touch stock

From `global.ini`: "The shop is full of this item and cannot buy more"
(`shop_ui_tooltip_text_shopinventoryfull`, bound in the player list item), "The shop doesn't
accept the container size", "Insufficient stock to complete this purchase.", "Item Max Stock
Error", "Quick Buy Restocking Error", and the disabled-button reason "Out of Stock". **verified**
Whether the sell slider's "Max" stops at the shop's free room is server logic and not in the UI
records. **not found**

### Versions

| Version | Change relevant here | Source | Label |
| --- | --- | --- | --- |
| 3.18.0 (LIVE 2023-03-10) | Commodity kiosk UI rebuilt in Building Blocks | [patch notes][p318] | verified |
| 3.24 | Kiosk layout changed (companion 1.0.3 "Handle 3.24 kiosk layout") | [companion changelog][stc-cl] | verified (second-hand) |
| 4.7 | Price number format changed; full numbers with separators in the fixtures | [companion changelog][stc-cl], fixtures | verified |
| 4.10.0 | The strings and bindings above | [StarCitizenDiff][scdiff] | verified |

The SC Wiki has no description of the 4.x kiosk: "Commodity terminal" redirects to the stub
"Trading Console", and "Trading" refers to 3.18. **verified**

## 2. How precisely a member can read and report it

| Value | By hand | From a screenshot | Label |
| --- | --- | --- | --- |
| Quantity | exact whole number in the shown unit; typos are the error | exact; machine-readable with OCR | verified (format) |
| Level | one of 7 names, or "Inventory Updating" | exact; the label text is the reliable part, the bar repeats it | verified |
| Bar | adds nothing beyond the level | adds nothing | verified |
| Maximum | not shown | not shown | verified |
| Room left on the sell side | not shown | not shown | verified |
| Time | the member's clock | file time of the screenshot; nothing on screen | inference |
| Terminal | the location in "Your Inventories"; the shop name is not on the list | same | verified (screenshot) |

- A reading is a **snapshot**: other players trade and the shop restocks or consumes between
  readings. "Inventory Updating" is a state in which the shown value is in flux. **inference**
- **Maximum from one reading** (inference, band reading above): quantity / band gives an interval.
  Waste 75 SCU at Max Inventory → maximum about 75–79 SCU; Nitrogen 230 SCU at Low → 920–2,300
  SCU; Stims 702 SCU at Medium → 1,404–2,808 SCU. "Out of Stock" bounds nothing. Readings of the
  same line at different levels narrow the interval by intersection.
- The tool authors confirm quantity and level are independent across lines: the UEX Datarunner
  prompt notes that "a quantity of 1 SCU can still be status 7" and "1973 SCU can be status 5".
  **verified** (their observation)
- **Restock from readings** (inference): two readings of a line at known times give the net change,
  which is the restock minus other players' trades; only a reading that a member's own purchase
  emptied, followed by a later reading, isolates the restock for a quiet terminal.
- Kiosk text follows the client language (**inference**). The companion requires the English
  client; the UEX Datarunner prompt also maps simplified Chinese labels (**verified**). Which
  labels a German member sees, through a client language or a translation pack, is **not found**.

## 3. How other tools capture it

### SC Trade Companion ([repo][stc], `d1228ee`, release 1.6.1)

- A hotkey (F3) screenshots the kiosk; local OCR (`OneOcr`) reads it; the player selects the shop
  in "Your Inventories" first. "Play Star Citizen, on LIVE, in English." ([README][stc-readme])
- The side is decided by the presence of the text "shop quantity": present → `SELLS` (Buy tab),
  absent → `BUYS` ([`TransactionTypeExtractor`][stc-tte]).
- Per line it parses: commodity name (spell-checked against SC Trade Tools' list), level name
  (spell-checked against "out of stock", "very low inventory" … "max inventory", "no demand"),
  quantity from "`<n> scu`", price from "`¤<n>/scu`", box sizes ([`RawCommodityListing`][stc-raw],
  [`InventoryLevel`][stc-lvl]). A line missing any of the first four is dropped.
- Submission `POST /api/crowdsource/commodity-listings`: `location`, `transaction`, `commodity`,
  `price`, `quantity`, `saturation` (level → 0, 1/6 … 5/6, 1), `boxSizesInScu`, `batchId`,
  `timestamp` ([`ScTradeToolsCommodityPublisher`][stc-pub]). Data also goes to local CSV files.
- A Game.log reader matches `LoadShopInventoryData … AddingCommodityBox` lines (shop id, shop name,
  commodity, box sizes; no quantity) ([`LoadShopInventoryDataLogLineProcessor`][stc-log]).
  Whether 4.10 still writes that line is **not found**.

### SC Trade Tools ([API 11.5.0][stt-api], [changelog][stt-cl])

- `saturation` is "the observed inventory saturation as a ratio between 0 and 1, where 0 is empty
  and 1 is full"; `quantity` is "the observed available inventory in SCU". **verified**
- Results carry `quantityInScu` and **`maxQuantityInScu`**; 8.1.5 and 9.0.0 improved "realistic
  default values" for maximum inventories, 10.0.0 "the shops' maximum capacity estimates". The
  estimation method is **not found**. Crowdsourced listings are readable through
  `GET /api/crowdsource/commodity-listings` and shared with UEX (8.1.0). **verified**

### UEX ([`POST /data_submit`][uex-submit])

- Per commodity line: `price_buy` or `price_sell`, `scu_buy` or `scu_sell` ("inventory amount
  displayed at the terminal"), `status_buy` or `status_sell` (1 "Out of Stock (Empty)" … 7
  "Maximum Inventory (Full)"), `quality` 0–1000, `container_sizes`, optional `is_missing`,
  `faction_affinity`, `game_version` (LIVE or PTU), `date_added` (within 30 days). Buy and sell go
  in separate reports. **verified**
- `screenshot` (base64, ≤ 10 MB) is "required for new datarunners (90-day evaluation period)";
  the same entry at the same terminal within 5 minutes is rejected as a duplicate. **verified**
- [UEX Datarunner][uexdr] (`965b10d`, a one-person client): a local vision model reads Windows
  screenshots into name, label, status 1–7, SCU, price and box sizes, which the member reviews
  before submitting. **verified**

### Game files and Game.log

- `Data/Scripts/ShopInventories/*.json` hold offline `CurrentInventory` and `MaxInventory` per
  shop line (map facts, [#3477][facts]); whether those maxima match the live kiosk is
  **not found**.
- The Basetool's own [Game.log note](game-data-and-game-log-findings.md) records commodity buy and
  sell lines with shop, price per cSCU and quantity in cSCU; it lists no stock or level field.
  **verified** (note)

## 4. Planned Basetool signals that observe stock

| Signal | What it records | Precision | Kept and shared | Label |
| --- | --- | --- | --- | --- |
| Sold-out / no-demand mark ([#3461][r3461]) | per buy or sell line of a route: closed offer with an optional quantity, default 0 — on Buy the quantity still available, on sell the quantity the terminal took | an SCU number the member types; on Buy it can be the kiosk's "Shop Quantity" read exactly; on sell it is the room the member measured by selling (inference) | working state (browser tab) or flight; never in a saved route; not shared, not audited; in a convoy the terminal's total | verified |
| Tick with actual quantity ([#3475][r3475]) | per loading job: actual quantity (prefilled with plan, may exceed it) and optional actual price | exact traded quantity; a lower bound on stock (buy) or room (sell), capped by plan, hold and cash; a purchase below plan offers the sold-out mark | server-held flight; history keeps the final plan with actual quantities for 90 days, no stop times; mission flights audited in `MISSION` with quantities | verified |
| Availability report ([#3465][r3465]) | per commodity, terminal and side: as shown or more · less than shown · nothing there, relative to UEX's figure | three-step ordinal; no quantity, no level; aggregated as reliability %, 7-day half-life, cut at a patch change | organisation-wide anonymous pool; the sold-out mark offers a prefilled "nothing there" | verified |

What none of them records: the kiosk's level name, the shop's quantity on lines the member did
not trade, a reading time precise enough for a rate (the flight history drops stop times), and
any maximum. **verified** (resolutions) / consequence: **inference**

## 5. Verified and inferred at a glance

- Verified: tab names and sections; quantity as an integer with a unit switch; seven level names
  plus "Inventory Updating" per tab and their keys; the bar driven by the level integer; no
  maximum binding; the level cut points 0/10/25/50/75/95; the sell tab shows the shop's stock; the
  companion's parsing and payload; SC Trade Tools' `saturation` and `maxQuantityInScu`; UEX's
  submit fields and screenshot rule; the three planned signals as resolved.
- Inferred: level = fill band of current / maximum and its boundary side; colour meaning; maximum
  intervals from a reading; room left = maximum − current; restock estimation from paired
  readings; screenshot time from the file.
- Not found: SC Trade Tools' maximum estimation method; whether the sell slider caps at the free
  room; the live meaning of offline `MaxInventory`; the companion's log line in 4.10; German
  kiosk labels; whether shop inventories are per shard in 4.x (SC Trade Tools noted them shared
  across servers in 2020, [issue #46][stt-46]).

## 6. Open points for "Member stock readings as a data source"

1. **Content of a reading:** terminal, commodity, side, quantity with its unit, level (7 names or
   "Inventory Updating"), time to the minute, game version; with or without the price on the same
   line, which overlaps UEX.
2. **Entry path:** a hand form at the route stop, flight view or stop ratings page; a screenshot
   upload with OCR; or the desktop extractor through `ingest`. OCR must cope with the client
   language and the unit switch (cSCU, µSCU, Units).
3. **Reuse of planned signals:** whether the mark's remaining quantity and a below-plan tick
   offer to send a reading, with the member's click as for availability reports.
4. **Sell side:** store the shop's current quantity and level; the room left needs a maximum.
5. **Maximum:** whether the Basetool estimates a line's maximum from quantity and level intervals,
   seeds it from p4kx's offline `MaxInventory`, or leaves it out; the band boundaries need a
   member test at a threshold.
6. **Restock rate:** paired readings of one line at known times, preferably right after the
   member's own purchase emptied it; what to do with readings confounded by other players.
7. **Scope and freshness:** per shard or global inventories in 4.x; decay and the patch-change
   cutoff, as for availability reports.
8. **Privacy and audit:** a timestamped reading places a member at a terminal; the flight history
   deliberately keeps no stop times. Anonymous pool as for ratings, or not stored per member.
9. **"Inventory Updating":** reject, or store as a level of its own.

[scdiff]: https://github.com/x3nnnonn/StarCitizenDiff/tree/908b76a0485036161ba700d369c7d92aca1c847b
[stc]: https://github.com/EtienneLamoureux/sc-trade-companion/tree/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d
[stc-img]: https://github.com/EtienneLamoureux/sc-trade-companion/tree/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/test/resources/kiosks/commodity/images
[stc-readme]: https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/README.md
[stc-cl]: https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/CHANGELOG.md
[stc-tte]: https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/commodity/TransactionTypeExtractor.java
[stc-raw]: https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/commodity/RawCommodityListing.java
[stc-lvl]: https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/commodity/InventoryLevel.java
[stc-pub]: https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/output/commodity/ScTradeToolsCommodityPublisher.java
[stc-log]: https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/gamelog/lineprocessors/LoadShopInventoryDataLogLineProcessor.java
[stt-api]: https://github.com/EtienneLamoureux/sc-trade-tools/blob/c9ccc638d7bb8c6b81af895916234e41f12d984f/api-docs/11.5.0.json
[stt-cl]: https://github.com/EtienneLamoureux/sc-trade-tools/blob/c9ccc638d7bb8c6b81af895916234e41f12d984f/CHANGELOG.md
[stt-46]: https://github.com/EtienneLamoureux/sc-trade-tools/issues/46
[uex-submit]: https://uexcorp.space/api/documentation/id/post_data_submit/
[uexdr]: https://github.com/oovz/uex-datarunner/tree/965b10d7c0f9dde9c0d892944f60203985522641
[p318]: https://starcitizen.tools/Update:Star_Citizen_Alpha_3.18.0
[facts]: https://github.com/krt-profit/basetool/issues/3477#issuecomment-6916422188
[r3461]: https://github.com/krt-profit/basetool/issues/3461#issuecomment-6912252815
[r3475]: https://github.com/krt-profit/basetool/issues/3475#issuecomment-6913858722
[r3465]: https://github.com/krt-profit/basetool/issues/3465#issuecomment-6901991240
