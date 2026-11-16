# Restock and price mechanics of the 4.x economy

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-16 for [#3478](https://github.com/krt-profit/basetool/issues/3478) on the map
> [#3477 Wayfinder: terminal restock rates](https://github.com/krt-profit/basetool/issues/3477).
> Nothing here is binding.

## Question

How do commodity terminals in Star Citizen 4.x change stock, demand and price over time?

- Does stock refill linearly (SCU per minute) or in proportion to the gap to a target, and does
  demand free up the same way on the sell side?
- Is stock shared across servers or held per server; what do server restarts, new shards and
  patches do to it?
- Does the price follow the stock in steps by supply / demand level or along a curve, with a lag,
  and does it recover over time without trades?

## Evidence classes

| Mark | Meaning |
|---|---|
| **CIG** | Statement by Cloud Imperium staff: patch notes, comm-links, Spectrum posts by `*-CIG` accounts |
| **Game text** | Text shipped in the game client (`global.ini`), written by CIG for players |
| **Datamined** | Values or field names read from the game files by community extractors |
| **Tool owner** | Statement by the maintainer of a community trade tool about its data |
| **Player** | Player observation or claim, with or without measurement |
| *verified* / *inferred* | Read in the source / concluded from one or more sources |

## Summary

- **Each shop line has a stock cap and a refill rate, set per shop and commodity on the server.**
  CIG, 4.0 EPTU (2024-12) and 4.0.2 (2025-03); 4.5 (2025-12) adds that smaller crate brackets
  refill more slowly and cap lower. *Verified.* No 4.x game file carries the rate. *Verified.*
- **The refill shape in 4.x is not published.** Until the data left the client in December 2023
  (3.22), every shop line carried `RefreshRatePercentagePerMinute`: a constant share of the cap per
  minute, which is a linear refill in SCU per minute up to the cap, applied in ticks of about two
  minutes. Buying shops drained their stock (`AutoConsume`) the same way. *Datamined, verified for
  3.17; tick length is a 3.6 player measurement.* That 4.x keeps this shape is *inferred*: CIG still
  names a refill rate per shop line, and no source supports a proportional refill.
- **Stock and prices are shared across servers, not held per server.** CIG designed it so (2021),
  the SC Trade Tools owner confirmed it for 3.10 (2020), and CIG says for 4.5 (2025-12) that server
  hopping does not change a location's price state. *Verified for prices in 4.5; strongly implied
  for stock in 4.0 EPTU.* Whether all regions share one pool in 4.x rests on player claims only.
- **Restarts and new shards** do not reset a shared pool (*inferred*; no 4.x source). **Patches**
  retune caps, rates and prices, CIG adjusts them mid-version (4.0.2 event), and the 4.0 and 4.8
  wipes reset the economy. *Verified.*
- **Price = base price shifted by a per-location discount (where the player buys) or premium (where
  the player sells) that trades erode towards 0.** CIG 4.4 / 4.5: up to about 20 % discount and
  35 % premium under ideal conditions, location modifiers on top, and heavy trading of one commodity
  drops its ROI from about 33 % to 7–8 %. *Verified (CIG).* The 3.x files held the same structure
  per shop line (`MaxDiscountPercentage`, `MaxPremiumPercentage`, `OptimalInventoryLevel`).
  *Datamined, verified.*
- **Steps or curve, lag and recovery are not published.** The six level thresholds
  (0 / 10 / 25 / 50 / 75 / 95 %) drive the kiosk labels and the in-game price alerts; whether the
  price steps at them is unknown. 3.x players saw prices move by cents per tick and lag the stock.
  Recovery without trades follows if the price tracks stock state, which is *inferred*, not stated.
- **Observed in 4.10.3** ([#3479](https://github.com/krt-profit/basetool/issues/3479)): the price
  a terminal asks stayed flat from empty to full; the price a terminal pays fell as its stock rose,
  an empty terminal paying a median +3.8 % (up to +12.3 %). This fits the premium band; the flat
  asking price does not show the discount band CIG describes.

## 1. What the server keeps per shop line

### 4.x

- **CIG, 4.0 EPTU, 2024-12-12** — Highlander-CIG (Econ team) defines "supply and demand" as the
  maximum amount of each commodity a shop can store plus the rate at which that stock is refilled,
  and says both were retuned for 4.0. Stanton mining sites should have a larger maximum with slower
  restock, Pyro the inverse
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/4-0-eptu-feedback-commodity-re-balancing/7543186)).
- **CIG, 4.0.2, 2025-03-24** — for the Supply or Die event, supply and refresh rate of tin, ice,
  copper and corundum were cut sharply together with large temporary price changes, to be reverted
  after the event
  ([Hotfix Central](https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-0-2-live-hotfix-central)).
- **CIG, 4.5 RC2 PTU, 2025-12-17** — commodities sit in back-end "brackets" named after the largest
  purchasable crate (32/24, 16, 8, 2 SCU); smaller brackets are restocked more slowly and have lower
  stock limits
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-5-ptu-patch-notes-11/8537301)).
- **Game files 4.x** — no field holds a refill rate. `GlobalShopCommodityParams` holds only the
  supply and demand level thresholds, auto-loading fees and UI keys (record
  [`globalshopcommoditydata.xml`](https://github.com/x3nnnonn/StarCitizenDiff/blob/main/DataCore/libs/foundry/records/globalshopparams/globalshopcommoditydata.xml),
  types in [sc-holotable](https://github.com/VeeLume/sc-holotable/blob/main/crates/sc-extract-generated/src/generated/globalshopparams/types.rs),
  parsed by [sc-gamedata-extractor](https://github.com/voidput/sc-gamedata-extractor/blob/main/src/domains/shops.ts),
  which notes that prices are set per shop by the server). `Data/Scripts/ShopInventories/*.json`
  hold only offline `CurrentInventory` / `MaxInventory`
  ([versetools catalogue](https://github.com/versetools/versetools/blob/main/apps/sc-data-extractor/STAR_CITIZEN_DATA_CATALOG.md),
  [p4kx](https://github.com/p4kx-org/p4kx/blob/main/docs/shops-and-terminals.md),
  [#3481](https://github.com/krt-profit/basetool/issues/3481)). The 4.x p4k listing has no
  `Data/Libs/Subsumption/Shops` folder, which held the 3.x shop layouts
  ([StarCitizenDiff `P4kContents`](https://github.com/x3nnnonn/StarCitizenDiff/tree/main/P4kContents/Data/Libs/Subsumption)).
  *Verified.*
- **Kiosk text** — each line shows a level label: supply side No / Very Low / Low / Medium / High /
  Very High / Max Inventory, demand side the same scale read as the shop's own stock, plus a
  transient "Inventory Updating" state (`shop_ui_CommodityKiosk_Supply_*` / `_Demand_*` in
  [`global.ini`](https://github.com/x3nnnonn/StarCitizenDiff/blob/main/Localization/global.ini);
  detail in [#3482](https://github.com/krt-profit/basetool/issues/3482)). *Verified.*

### 3.x, for comparison

The 3.x client shipped every shop line in `Data/Libs/Subsumption/Shops/ShopLayouts.xml` with
`Inventory`, `MaxInventory`, `OptimalInventoryLevel`, `AutoRestock`, `AutoConsume`,
`RefreshRatePercentagePerMinute`, `BasePriceOffsetPercentage`, `MaxDiscountPercentage` and
`MaxPremiumPercentage`, prices from `RetailProductPrices.xml`
([scunpacked `ShopLoader.cs`](https://github.com/richardthombs/scunpacked/blob/master/Loader/ShopLoader.cs),
export [`shops.json`](https://github.com/richardthombs/scunpacked/blob/master/api/dist/json/shops.json)
of 3.17.4d, 2022-11-16). *Datamined, verified.* SC Trade Tools reported on 2023-12-23 that the
refresh rate could no longer be read because the data had been removed from the client
([issue #61](https://github.com/EtienneLamoureux/sc-trade-tools/issues/61), via
[#3480](https://github.com/krt-profit/basetool/issues/3480)). *Tool owner, verified.*

At the trading terminals of the 3.17.4d export (checked at Kudre Ore and ArcCorp Mining Area 157),
a line is either a selling line (`AutoRestock`, optimal level 100, a discount band) or a buying line
(`AutoConsume`, optimal level 0, a premium band), with rare exceptions such as Helium at 157
(neither flag, optimal level 50). *Datamined, verified.*

## 2. Linear or proportional refill

**3.x: linear.** The rate is a fixed percentage of the cap per minute, so the SCU per minute is
constant and does not depend on the gap. The numbers players quoted from SC Trade Tools in 2021
equal `rate × MaxInventory` from the files:

| Shop line (3.17.4d) | Cap (units) | Rate %/min | Units/min | Empty → full | Quoted by players (2021) |
|---|---|---|---|---|---|
| Kudre Ore, Agricium | 2,800 | 1.371 | 38.4 | 73 min | 38/min, cap 2,800, at least 74 min |
| ArcCorp 157, Astatine | 40,000 | 0.375 | 150 | 267 min | 150/min, cap 40,000, about 4.5 h |

Sources: 3.14 PTU, 2021-07-20
([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/commodities-demand-is-self-deleting-still)),
3.13, 2021-05-17
([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/possible-explanation-to-why-trade-is-bad-right-now)).
The SC Trade Tools owner checked the 3.10 PTU rates against the game in August 2020 and found them
accurate ([#37](https://github.com/EtienneLamoureux/sc-trade-tools/issues/37)). *Datamined and tool
owner, verified; the match is exact for both examples.*

**Applied in ticks.** Players see stock change in discrete steps. A 3.6 log (2019-07-24) shows a
tick of about two minutes, a 150 SCU cap refilled by about 5 SCU per tick and a 500 SCU cap by
15–60 SCU per tick, with unexplained drops
([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/laranite-is-deleting-its-own-stock-and-bottlenecki)).
3.10 players measured uneven amounts per refresh at one terminal, which the tool owner put down to
trades on other servers ([#43](https://github.com/EtienneLamoureux/sc-trade-tools/issues/43)).
*Player; tick length not confirmed by CIG.*

**4.x: shape unpublished.** CIG speaks of a refill *rate* per shop line (§1) and never of a target
the stock approaches. No source, CIG or player, describes a proportional refill. UEX history is too
sparse to fit a curve ([#3479](https://github.com/krt-profit/basetool/issues/3479)). That 4.x
keeps the 3.x linear rate is *inferred*.

**Sell side.** In 3.x, buying shops reduced their own stock at their rate (`AutoConsume`), so
demand freed up linearly as well. In 4.x the "Local Market Value" tab shows the shop's own stock and
the demand label reads it ([#3482](https://github.com/krt-profit/basetool/issues/3482)); the same
drain mechanism is *inferred*.

## 3. Shared across servers or per server

- **CIG design, late 2021** (Server Meshing and Persistent Streaming Q&A; questions collected from
  2021-10-25): the economy is global and reflected in each shard; shops restock from a global
  inventory shared across all shards; heavy buying raises the price at that shop on all shards
  ([comm-link 18397](https://robertsspaceindustries.com/en/comm-link/transmission/18397-Server-Meshing-And-Persistent-Streaming-Q-A),
  [mirror](https://api.star-citizen.wiki/comm-links/18397)). *CIG, design intent before 4.0.*
- **Tool owner, 3.10, 2020-08-10**: inventories and refresh rates are shared across all servers,
  so all players compete for the same stock
  ([#43](https://github.com/EtienneLamoureux/sc-trade-tools/issues/43),
  [#46](https://github.com/EtienneLamoureux/sc-trade-tools/issues/46)). *Tool owner.*
- **Player, 2021-05-17**: since about 3.11 one economy spans all servers in all regions; fresh
  servers spawn with low stock and drag the shared supply down (a theory, labelled as such by its
  author; [Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/possible-explanation-to-why-trade-is-bad-right-now)).
  *Player, not confirmed by CIG.*
- **CIG, 4.0 EPTU, 2024-12-12**: answering a player who says supply and demand is shared across
  all regions, Highlander-CIG says Server Meshing should help with players not seeing the buyer who
  just emptied a location's gold, and that kiosk feedback about it is unsatisfactory
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/4-0-eptu-feedback-commodity-re-balancing/7543186)).
  *CIG; implies stock shared beyond one server, does not name the scope.*
- **CIG, 4.5 RC2 PTU, 2025-12-17**: discounts and premiums move with trades at a location, and
  changing server does not reset them (§5)
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-5-ptu-patch-notes-11/8537301)).
  *CIG; verified for the price state.*
- **Players, 4.0 EPTU and 2025-01**: stock is shared within the regional shard
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/4-0-eptu-feedback-commodity-re-balancing/7532925))
  or in one universal database
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/shop-stock-between-servers-question)).
  *Player claims without measurement; they disagree on the scope.*

## 4. Server restarts, new shards and patches

- **Restarts and new shards.** With a shared pool a restart or a new shard does not refill or
  empty a terminal. *Inferred; no 4.x source.* That a fresh 3.x server drained the shared stock is
  a 2021 player theory that CIG never confirmed. Offline `CurrentInventory` values are not spawn
  stock ([#3481](https://github.com/krt-profit/basetool/issues/3481)).
- **Patches retune the economy.** 4.0 reworked prices, availability and price elasticity of every
  commodity ([Patch Watch, 2024-11-12](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/alpha-4-0-patch-watch-the-balancing-act));
  4.4 raised ROI bands and cut crate sizes
  ([Spectrum, 2025-11-17](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/don-t-nerf-high-risk-high-reward-gameplay-please/8422038));
  4.5 introduced the brackets (§1); CIG named a tenfold price outlier of earlier 4.x patches an
  input error ([Spectrum, 2026-07-24](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/see-how-easy-it-is-to-get-engaging-and-meaningful-/9048331)).
  *CIG, verified.*
- **Mid-version changes.** The 4.0.2 event changed supply, refresh rate and price for a few
  commodities and announced the reversal (§1). In 3.6 a player reported CIG adjusting prices and
  caps on the live servers (2019, *player*). *CIG, verified for 4.0.2.*
- **Wipes.** 4.0 (2024-12-19) brought a full wipe
  ([patch notes](https://starcitizen.tools/Update:Star_Citizen_Alpha_4.0.0)); 4.8 (2026-05) is a full
  reset of economy, characters, reputation and earned ships
  ([patch notes, comm-link 21168](https://robertsspaceindustries.com/en/comm-link/Patch-Notes/21168-Star-Citizen-Alpha-48)).
  UEX keeps price history per game version
  ([#3479](https://github.com/krt-profit/basetool/issues/3479)). *Verified.*

## 5. Price against stock

### What CIG says

- **4.0, 2024-11-12**: every commodity was reviewed for price, crate sizes, sale locations, price
  elasticity and availability ([Patch Watch](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/alpha-4-0-patch-watch-the-balancing-act)).
- **4.4, 2025-11-17**: most commodities have a pair of locations offering up to about a 20 %
  discount on purchase and a 35 % premium on sale under ideal conditions; each location has further
  modifiers; many layers of dynamic calculation shape the final price; the more players trade one
  commodity at a location, the worse its prices for all players
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/don-t-nerf-high-risk-high-reward-gameplay-please/8422038)).
- **4.5, 2025-12-17**: prices at most shops are only partly dynamic: a location sells or buys a
  commodity at a discount or premium that trends towards 0 the more players trade it there, and
  "server hopping will not change that"; CIG's systems favour soft limits over hard ones
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-5-ptu-patch-notes-11/8537301)).
- **4.9, 2026-07-24**: players need a better in-game way to see current prices and how far supply
  and demand has moved them
  ([Spectrum](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/see-how-easy-it-is-to-get-engaging-and-meaningful-/9048331)).

### What the game tells players

- The journal entry "A Guide to Trading in the Empire" (`Journal_General_Trading_*`, present from
  the 4.4.0 PTU, 2025-11, to the 2026-06 localisation snapshot, absent in 4.3.2 PTU) says each
  commodity's price is driven dynamically: the price of goods on offer rises with trading volume at
  that location, and a broker receiving much of one commodity pays significantly less for it.
  *Game text, verified.*
- "Commodity Price Alerts" list locations that adjusted prices based on their current supply
  levels, under the headings Overstock and Understock (`journal_shopAlerts_*`). *Game text,
  verified.*

### What the files held in 3.x

Selling lines carry a discount band, buying lines a premium band, relative to an optimal level of
100 % and 0 % stock. At full stock a 3.17 selling line's price equals `base × (1 − MaxDiscount)`:
Astatine at ArcCorp 157, base 8 with 12.5 % discount, gives 7.0, the full-stock price a player
reported as its base price in 2021; Fluorine there gives 2.65 × (1 − 11.32 %) = 2.35, the player's
figure. *Datamined, verified; the full-stock reading of the band is inferred from these two
matches.*

The 4.x description (discount / premium per location, eroded by trades) has the same structure.
*Inferred.*

### What is observed in 4.10.3

[#3479](https://github.com/krt-profit/basetool/issues/3479), 35 pairs: the price a terminal asks
was constant in 17 of 17 series from empty to full; the price a terminal pays fell as its stock
grade rose in 194 of 235 row pairs, an empty terminal paying a median +3.8 % (range +0.6 % to
+12.3 %). *Verified on that sample.* The paying side fits a premium band that shrinks as the shop
fills; the flat asking side does not show the discount band CIG describes.

### Steps or curve, lag, recovery

- **Thresholds.** Supply levels 0 / 10 / 25 / 50 / 75 / 95 % and demand levels
  100 / 95 / 75 / 50 / 25 / 10 %, unchanged from the 4.2.0 PTU (2025-05-28) to the 2026-09-22
  snapshot ([record history](https://github.com/x3nnnonn/StarCitizenDiff/commits/main/DataCore/libs/foundry/records/globalshopparams/globalshopcommoditydata.xml)).
  *Datamined, verified.* They name the labels and the alerts; that they are fill ratios of
  stock / cap is *inferred* (#3482); whether the price steps at them is *unknown*.
- **Lag.** 3.6 players logged prices moving by a few cents per tick while stock jumped by hundreds
  of SCU (2019, link in §2); a 3.13 player saw prices fall only after a location sat at full stock
  for several ticks (2021). CIG's description of bands trending towards 0 fits a gradual move.
  *Player; unverified for 4.x.*
- **Recovery without trades.** No CIG statement. If the band tracks stock state, the price returns
  as the stock refills (selling lines) or drains (buying lines). *Inferred.* The 4.4 statement and
  the in-game guide describe the effect in terms of how many players trade a commodity, which could
  also be a volume effect that decays on its own clock. *Open.*
- **Tick cadence.** The wiki's trading page says prices change about every 8–10 minutes
  ([starcitizen.tools](https://starcitizen.tools/Trading), written for 3.18). No 4.x measurement
  found. *Unverified.*

## Sources

| Source | Type | Date | Version | What it says |
|---|---|---|---|---|
| [The Star Citizen Economy](https://api.star-citizen.wiki/comm-links/13128) | CIG comm-link | 2013-07-05 | design | Nodes with storage limits; shortages raise prices, surplus lowers them; no numbers |
| [Laranite is deleting its own stock](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/laranite-is-deleting-its-own-stock-and-bottlenecki) | Player | 2019-07-24 | 3.6 | Tick about 2 min; refill per tick 5 SCU (cap 150), 15–60 SCU (cap 500); price moves by cents |
| [SCTT #17](https://github.com/EtienneLamoureux/sc-trade-tools/issues/17), [#29](https://github.com/EtienneLamoureux/sc-trade-tools/issues/29), [#37](https://github.com/EtienneLamoureux/sc-trade-tools/issues/37), [#43](https://github.com/EtienneLamoureux/sc-trade-tools/issues/43), [#44](https://github.com/EtienneLamoureux/sc-trade-tools/issues/44), [#46](https://github.com/EtienneLamoureux/sc-trade-tools/issues/46) | Tool owner, player | 2020-07/08 | 3.10 | Refresh rates per minute from game files, verified in game; stock and rates shared across all servers |
| [Alpha 3.12.1 patch notes](https://starcitizen.tools/Update:Star_Citizen_Alpha_3.12.1) | CIG | 2021-02-04 | 3.12.1 | Lower starting inventory at TDDs; higher inventory and refresh rate for shop items |
| [Possible explanation to why trade is bad](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/possible-explanation-to-why-trade-is-bad-right-now) | Player | 2021-05-17 | 3.13 | One economy across regions since ~3.11; Astatine 150/min, cap 40,000; price lags full stock |
| [Commodities/demand is self deleting](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/commodities-demand-is-self-deleting-still) | Player | 2021-07-20 | 3.14 PTU | Agricium 38/min to cap 2,800, 74 min; fresh-server theory |
| [Server Meshing and Persistent Streaming Q&A](https://robertsspaceindustries.com/en/comm-link/transmission/18397-Server-Meshing-And-Persistent-Streaming-Q-A) | CIG comm-link | late 2021 | design | Global economy; shops restock from a global inventory across shards; prices rise on all shards |
| [scunpacked `ShopLoader.cs`, `shops.json`](https://github.com/richardthombs/scunpacked) | Datamined | 2022-11-16 | 3.17.4d | Per-line cap, optimal level, auto-restock / consume, refresh %/min, discount and premium bands |
| [SCTT #61](https://github.com/EtienneLamoureux/sc-trade-tools/issues/61) | Tool owner | 2023-12-23 | 3.22 | Refresh rate data removed from the client |
| [Alpha 4.0 Patch Watch: The Balancing Act](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/alpha-4-0-patch-watch-the-balancing-act) | CIG | 2024-11-12 | 4.0 | All commodities reworked: prices, crates, sale points, price elasticity, availability |
| [4.0 EPTU Commodity Re-balancing, reply](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/4-0-eptu-feedback-commodity-re-balancing/7543186) | CIG | 2024-12-12 | 4.0 EPTU | Per shop and commodity: max storage and refill rate; Stanton large and slow, Pyro small and fast; unseen buyers on other servers |
| [same thread, player reply](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/4-0-eptu-feedback-commodity-re-balancing/7532925) | Player | 2024-12 | 4.0 EPTU | Claims stock shared within the regional shard |
| [4.0 Preview Feedback: Economy Overhaul](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/4-0-preview-feedback-economy-overhaul) | CIG | 2024-12-18 | 4.0 | Same summary for the preview |
| [Alpha 4.0.0 patch notes](https://starcitizen.tools/Update:Star_Citizen_Alpha_4.0.0) | CIG (wiki mirror) | 2024-12-19 | 4.0.0 | Full wipe and reset, long-term persistence disabled |
| [shop stock between servers question](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/shop-stock-between-servers-question) | Player | 2025-01-24 | 3.24 | Claims one universal database and tick-based stock updates |
| [4.0.2 Hotfix Central](https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-0-2-live-hotfix-central) | CIG | 2025-03-24 | 4.0.2 | Event: supply and refresh rate lowered, prices raised, temporary |
| [`globalshopcommoditydata.xml`](https://github.com/x3nnnonn/StarCitizenDiff/blob/main/DataCore/libs/foundry/records/globalshopparams/globalshopcommoditydata.xml) | Datamined | 2025-05-28 to 2026-09-22 | 4.2–4.10 | Level thresholds 0/10/25/50/75/95 and 100/95/75/50/25/10; no rate |
| [`global.ini`](https://github.com/x3nnnonn/StarCitizenDiff/blob/main/Localization/global.ini) | Game text | 2025-11 to 2026-06 | 4.4–4.8 | Trading guide: prices driven by trading volume; price alerts by supply level; kiosk labels |
| [Don't nerf high risk, high reward, reply](https://robertsspaceindustries.com/spectrum/community/SC/forum/4/thread/don-t-nerf-high-risk-high-reward-gameplay-please/8422038) | CIG | 2025-11-17 | 4.4 | Bands up to ~20 % discount / ~35 % premium; location modifiers; crowding erodes ROI 33 % → 7–8 % |
| [4.5 RC2 PTU patch notes, reply](https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-5-ptu-patch-notes-11/8537301) | CIG | 2025-12-17 | 4.5 | Brackets; small brackets refill slower and cap lower; partly dynamic bands trend to 0; server hopping does not reset them |
| [Alpha 4.8 patch notes](https://robertsspaceindustries.com/en/comm-link/Patch-Notes/21168-Star-Citizen-Alpha-48) | CIG | 2026-05 | 4.8 | Full reset of economy, characters, reputation, earned ships and loot |
| [See how easy it is to get engaging PvP, reply](https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/see-how-easy-it-is-to-get-engaging-and-meaningful-/9048331) | CIG | 2026-07-24 | 4.9 | Routes hit diminishing returns; prices shift via supply and demand; better in-game visibility needed |
| [sc-holotable types](https://github.com/VeeLume/sc-holotable/blob/main/crates/sc-extract-generated/src/generated/globalshopparams/types.rs), [sc-gamedata-extractor](https://github.com/voidput/sc-gamedata-extractor/blob/main/src/domains/shops.ts), [versetools](https://github.com/versetools/versetools/blob/main/apps/sc-data-extractor/STAR_CITIZEN_DATA_CATALOG.md), [p4kx](https://github.com/p4kx-org/p4kx/blob/main/docs/shops-and-terminals.md) | Datamined | 2026 | 4.x | No rate field; prices and stock are server-side; offline inventory only |
| [starcitizen.tools Trading](https://starcitizen.tools/Trading) | Wiki | n/a | 3.18 | Prices change about every 8–10 minutes |
| [#3479](https://github.com/krt-profit/basetool/issues/3479), [#3480](https://github.com/krt-profit/basetool/issues/3480), [#3481](https://github.com/krt-profit/basetool/issues/3481), [#3482](https://github.com/krt-profit/basetool/issues/3482) | Sibling notes | 2026-11-16 | 4.10.3 | UEX history, SCTT method, p4kx start values, kiosk readings |

## Verified and inferred

**Verified**

- 4.x shop lines have a server-side cap and refill rate per shop and commodity, retuned per patch
  and mid-version; bracket size sets slower refill and lower caps (CIG).
- No 4.x game file carries a refill rate; the 3.x fields left the client in December 2023.
- 3.x refill: a fixed percentage of the cap per minute, linear in SCU per minute, matching player
  numbers exactly.
- Prices are a base price with a per-location discount / premium that trades erode, and that state
  is not reset by changing server (CIG 4.5).
- Level thresholds and kiosk labels as listed; unchanged across 4.2–4.10.

**Inferred**

- 4.x refill is still linear; buying shops drain demand linearly.
- Stock, not only price, is shared across all servers and shards of a region or beyond.
- Restarts and new shards do not change a terminal's stock.
- The band is a function of stock state, so prices recover as stock recovers.
- Level thresholds are fill ratios of stock / cap.

**Not found or unread**

- No CIG number for any 4.x refill rate, tick length or price function.
- Reddit (blocked in the browser pane, not indexed by the search used) and Star Citizen Live /
  Inside Star Citizen transcripts on the 4.x economy (none found); Issue Council reports not read.
- No 4.x measurement of tick length, refill per tick or price steps at level boundaries.

## Open points for the decision tickets

1. **Restock model** ([#3484](https://github.com/krt-profit/basetool/issues/3484)): take the
   linear model (cap and SCU per minute per shop line) as the working hypothesis; it is the only
   shape with a source. Defaults would vary by bracket and by system (Stanton large and slow, Pyro
   small and fast), not one global rate.
2. **Measuring a rate**: stock is shared beyond the member's server, so every reading pair is net
   of other players' trades and gives a lower bound; readings taken right after a member's own
   purchase emptied a line are the cleanest. A short member test can settle tick length and refill
   per tick in 4.x.
3. **Version scope**: estimates are keyed by game version and must expect mid-version backend
   changes and wipes; decide how an estimate is invalidated.
4. **Price movement** ([#3485](https://github.com/krt-profit/basetool/issues/3485)): model the
   price a terminal pays as `base × (1 + premium × f(stock))`, with a premium of up to about 12 %
   observed; whether the price a terminal asks moves at all in 4.10 needs data (CIG describes a
   discount band, #3479 saw none).
5. **Steps or curve and lag**: unknown; a member test selling one load in several parts and reading
   price and level each time would show both.
6. **Recovery**: whether a price recovers with stock or with time since the last trades decides
   whether the route planner can expect a price to come back between runs.
7. **Shard scope**: whether all regions share one pool in 4.x is unverified; it decides whether
   readings from members in other regions are comparable.
