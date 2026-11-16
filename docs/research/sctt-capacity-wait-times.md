# How SC Trade Tools estimates capacity, fill level and wait times

Doc type: research snapshot; findings for the terminal-restock map, not a binding specification.

- Ticket: [#3480](https://github.com/krt-profit/basetool/issues/3480), map
  [#3477](https://github.com/krt-profit/basetool/issues/3477).
- Snapshot: 2026-11-16. SC Trade Tools (SCTT) repository at
  [`c9ccc63`](https://github.com/EtienneLamoureux/sc-trade-tools/tree/c9ccc638d7bb8c6b81af895916234e41f12d984f)
  (CHANGELOG 11.5.1), live web client 11.5.1, API spec 11.5.0; SC Trade Companion at
  [`d1228ee`](https://github.com/EtienneLamoureux/sc-trade-companion/tree/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d)
  (release 1.6.1). Local baseline: `origin/main` at `470f3bc09`.
- Method: public sources only. SCTT's README, CHANGELOG (version dates from its git history),
  `api-docs/*.json` 10.2.2–11.5.0, the migration guide and issues #3–#164; the companion's README,
  CHANGELOG and Java source; the site's own JavaScript bundles (tooltips, FAQ, terms) and archived
  copies of older bundles on the Wayback Machine; five token-less `GET` requests to
  `sc-trade.tools/api/…` on 2026-11-16 at 12:40 UTC. No token, no account, no contact.
- Labels: **verified** = read in a spec, source, bundle or actual response; **inferred** = a
  consequence not demonstrated; **not found** = looked for and absent.

## Question

From public sources only: how does SCTT estimate a shop's capacity, fill level and refill, and turn
them into wait times? What it observes and how the companion app gets it from the game; how it
estimates maximum capacity and refill; how a wait time enters a route's duration and profit per
minute; how "Allow wait times", "Maximum wait time" and the smart filters interact; which endpoints
answer without a token; what its terms allow for reusing its data.

## Summary

- **Observation (verified).** One crowdsourced record per shop line: location, `BUYS`/`SELLS`,
  commodity, price per SCU, `quantity` in SCU, `saturation` 0–1, box sizes, a batch id and the
  observation timestamp. The companion gets them by **OCR of kiosk screenshots** (hotkey F3); the
  player picks the shop in the kiosk's "Your inventories" dropdown. `saturation` is **not
  measured**: it is the kiosk's stock label mapped to fixed values 0, 1/6 … 5/6, 1.
- **Game log (verified).** A log-reading experiment (`LoadShopInventoryData`: shop, commodity,
  largest box size; no quantity) shipped in companion 1.0.4 and is switched off since 1.1.1.
- **Capacity (verified, method not published).** The API returns `quantityInScu` and
  `maxQuantityInScu` per listing; the UI calls them "expected inventory at this time over the
  maximum capacity" and marks all values as estimates. A missing capacity shows as the sentinel
  `9999`. The default capacity was a fixed internal 9k SCU until issue #128 proposed the median of
  similar listings (closed as fixed by 8.1.5); 10.0.0 "improve[d] the shops' maximum capacity
  estimates". The estimator itself is not public.
- **Refill (verified).** SCTT has **no refill rate** today. Its FAQ says stock replenishes every
  minute but "that amount is unknown by the website". Datamined refresh rates drove the wait-time
  model until the game files stopped carrying them (issue #61, 2023-12-23); 8.0.0 (2024-03-07)
  replaced "refresh rate with current quantity".
- **Wait time (verified / inferred).** No wait duration exists in the current API: a trade carries
  `timeInSeconds`, `profit` and `profitPerMinute` only. Travel and loading time are in the duration;
  a wait is not (inferred from the missing rate). A trade whose cargo exceeds the origin's expected
  stock or the destination's free capacity is outlined in yellow with a "you may have to wait"
  warning. Before 8.0.0 the API returned `waitTimeInMinutes` per stop and the UI showed
  "> N minutes wait".
- **Filters (verified).** "Allow wait times" (default off) "filters out shops that cannot fill/buy
  your whole cargo hold at once". "Maximum wait time" and "Minimum refresh rate" left UI and API
  with 8.0.0; "Minimum inventory capacity" took their place. "Smart filters" (default on
  since 10.0.0) auto-pick the minimum inventory capacity and the box size from the ship and lock
  the capacity input.
- **No token (verified).** The crowdsourced listings feed and the location bounties answered `200`
  without a token; the spec lists shop, commodity, location and ship lists as token-less too.
  Routes, buyers and every per-shop or per-commodity transaction list require a `token` header; the
  three probed answered `400` without it. Tokens go to Patreon supporters.
- **Terms (verified).** The site licence covers personal use and forbids republishing, copying and
  redistributing its material; specs up to 11.1.1 said API use "without express written
  permission is prohibited". No open-data licence. The companion's **code** is GPL-3.0.

## 1. What SCTT observes and how the companion gets it

### 1.1 The submission record

`CrowdsourceCommodityListingsDto` in [API 11.5.0](https://github.com/EtienneLamoureux/sc-trade-tools/blob/master/api-docs/11.5.0.json).
The fields are the same since 10.2.2; 11.3.0 added the descriptions and made `price`, `quantity`
and `saturation` required next to `batchId`, `commodity`, `location`, `timestamp`, `transaction`.

| Field | Spec description (quoted) | Label |
| --- | --- | --- |
| `location` | "The full name of the location where the listing was observed." | verified |
| `transaction` | "Whether the shop buys or sells the commodity." (`BUYS`, `SELLS`) | verified |
| `price` | "The observed price per SCU in aUEC." | verified |
| `quantity` | "The observed available inventory in SCU." | verified |
| `saturation` | "The observed inventory saturation as a ratio between 0 and 1, where 0 is empty and 1 is full." | verified |
| `boxSizesInScu` | "The box sizes in SCU supported by this shop listing." | verified |
| `batchId` | "A SHA-256 hash that groups all listings submitted together in the same batch." | verified |
| `timestamp` | "The timestamp at which the listing was observed, in UTC." | verified |

`price`, `quantity` and `saturation` say "Omit for a partial submission", although the schema lists
them as required. There is no source, submitter or confidence field in the published read model.
The `POST` that receives submissions is **not** in any published spec (10.2.2–11.5.0); it is known
only from the companion's source (below). Verified.

### 1.2 How the companion reads the game

From the [companion README](https://github.com/EtienneLamoureux/sc-trade-companion/blob/main/README.md)
and source at `d1228ee`:

- **Input is a screenshot.** The player opens a commodity kiosk, selects the shop in the left
  "Your inventories" dropdown and presses F3 on the Buy tab and again on the Sell tab, scrolling
  for more lines. It requires "Star Citizen, on LIVE, in English". OCR runs locally through a
  native OCR library (`OneOcr`) loaded via JNA. Verified.
- **Per line it extracts** commodity name, stock label, `N scu`, price per SCU and the available
  box sizes, and keeps the line only when name, label, quantity and price were all read
  ([`RawCommodityListing.isComplete`](https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/commodity/RawCommodityListing.java#L66)).
  Names are spell-checked against `GET /api/commodity/items`. Verified.
- **`saturation` is a label, not a ratio.** The stock label is spell-matched to
  [`InventoryLevel`](https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/commodity/InventoryLevel.java)
  and sent as that level's fixed value. Verified:

  | Kiosk label | `saturation` sent |
  | --- | --- |
  | out of stock | 0 |
  | very low inventory | 1/6 |
  | low inventory | 2/6 |
  | medium inventory | 3/6 |
  | high inventory | 4/6 |
  | very high inventory | 5/6 |
  | no demand | 1 |
  | max inventory | 1 |

- **`timestamp`** is the moment the screenshot is processed on the player's PC
  ([`CommodityListingFactory` L284](https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/commodity/CommodityListingFactory.java#L284)),
  not a game time. **`batchId`** is a hash of the OCR result of one screenshot (L86). Verified.
- **Publishing**: listings are merged per shop and posted every 30 s
  ([`@Scheduled(fixedDelay = 30 s)`](https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/commodity/CommodityService.java#L95))
  as `POST /api/crowdsource/commodity-listings` with a body `{user: {id, label}, listings: […]}`
  and an empty `signature` header
  ([publisher L61–L80](https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/output/commodity/ScTradeToolsCommodityPublisher.java#L61-L80));
  every request carries `x-companion-version`
  ([`ScTradeToolsClient` L35](https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/output/ScTradeToolsClient.java#L35)).
  The user id is derived from Windows or hardware identifiers; the label is an optional username.
  The box sizes go out as `availableBoxSizesInScu`, not the spec's `boxSizesInScu`. Verified.
- **Version gate**: `GET /api/crowdsource/companion-versions/latest` "Returns the companion
  application version for which submissions will be accepted." Verified (spec).
- **Game log**: [`LoadShopInventoryDataLogLineProcessor`](https://github.com/EtienneLamoureux/sc-trade-companion/blob/d1228eed4b7466e9d63f72cbe989b0cc62a1c37d/src/main/java/tools/sctrade/companion/domain/gamelog/lineprocessors/LoadShopInventoryDataLogLineProcessor.java)
  matches `LoadShopInventoryData … shopId[…] shopName[…] commodityName[ResourceType.…] … boxSize[…]`
  and builds a `SELLS` listing with **no price, quantity or saturation**. Companion 1.0.4 (2024-12-05)
  read the location from the log; 1.1.1 (2025-05-26) disabled log tailing ("The experiment has
  concluded for now"). The processor classes remain, but no file tailer is wired in `AppConfig`.
  Verified.

### 1.3 Other sources and what the public feed shows

- 8.1.0: SCTT "share[s] its crowdsourced community data bidirectionally with UEX Corp"; issue
  [#116](https://github.com/EtienneLamoureux/sc-trade-tools/issues/116): "UEX API has been added as
  a data point" (2024-03-26). Verified. How UEX's 7-grade status maps to `saturation` is not found.
- Page 0 of `GET /api/crowdsource/commodity-listings` (2026-11-16): 100 of 753 listings, timestamps
  within 3.5 hours on the evening before, locations lower-cased, `boxSizesInScu` null on all
  100, `batchId` a 36-character UUID on all 100 (not the 64-character SHA-256 the companion sends),
  every non-zero `saturation` an exact multiple of 1/6, 80 of 100 at `0`. Verified (one page).
  That these rows come from a source other than the companion is **inferred** from the id format.
- The feed is documented as "unfiltered and may contain outliers" and "cached server-side for 1
  hour"; 11.4.1 added `submitedAtOrAfter`. Verified.

## 2. Capacity, fill level and refill

| Fact | Detail | Source | Label |
| --- | --- | --- | --- |
| Read model per listing | `TransactionDto`: `price`, `fees`, `quantityInScu`, `maxQuantityInScu`, `boxSizesInScu`, `itemQuantityInScu`; no description on any field; no timestamp, saturation or source | API 11.5.0 | verified |
| What the numbers mean | UI tooltip: "Expected inventory at this time over the maximum capacity. All values are estimates." | live bundle `734.*.js` | verified |
| Display | `~q/max SCU`; when `maxQuantityInScu` is `9999`, only `~q SCU` (sentinel for an unknown capacity) | live bundle | verified |
| Disclaimer | Expanded view warning: "All inventory levels and maximum capacities are estimated." | live bundle `986.*.js` | verified |
| Default capacity | Issue [#128](https://github.com/EtienneLamoureux/sc-trade-tools/issues/128): "Default inventory is always set to 9k SCU internally"; proposal: median of similar listings for the same transaction type, shop type and commodity; closed as fixed in 8.1.5 | issue #128, CHANGELOG 8.1.5, 9.0.0 | verified that the change shipped; the formula in use is not published |
| Estimator | 8.1.1 "better price and quantity estimates" from improved aggregation and data cleaning; 10.0.0 "Improve the shops' maximum capacity estimates"; FAQ: data "aggregated using data science" | CHANGELOG, live FAQ | verified wording; method **not found** |
| Refill rate | FAQ: stock replenishes slowly every minute, "That amount is unknown by the website." | live bundle `main.*.js` | verified |
| Refresh rates gone | Issue [#61](https://github.com/EtienneLamoureux/sc-trade-tools/issues/61): "Refresh rate cannot be obtained anymore since the data-mineable info has been removed from the client." | 2023-12-23 | verified |
| Current stock left LIVE | FAQ: "The website doesn't know how much stock is left LIVE, but estimates it from community reports." | live bundle | verified |
| Time dependence | "at this time" suggests the estimate is projected to the query time; how is **not found** | — | inferred |
| Implied capacity | One observation gives `quantity / saturation` as a capacity band (for example 42 SCU at 1/3 ≈ 126 SCU, but the label spans a range and `0` gives nothing) | §1.2 | inferred |
| Archived response | Wayback copy of `GET /api/commodity/shops/Pyro > Megumi Refueling/transactions` (2026-11-15): 43 lines; many `BUYS` lines have `quantityInScu` exactly one tenth of `maxQuantityInScu` (37/370, 176/1760, 340/3400); fuel and ammunition `SELLS` lines at 12,000/12,000 | [archive](http://web.archive.org/web/20261115031726/https://sc-trade.tools/api/commodity/shops/Pyro%20%3E%20Megumi%20Refueling/transactions) | verified values; that 1/10 is a default fill is **inferred** |

## 3. Wait time, route duration and profit per minute

Current model (11.x):

- A trade (`TradeDto`) carries `origin` and `destination` transactions, `profit`,
  `profitPerMinute` and `timeInSeconds`; there is no wait field. Verified.
- `profitType`: "Use 'time' to maximise profit per second (best overall efficiency), or 'pure' to
  maximise raw profit regardless of travel time." Default in the UI: `time`. Verified.
- Duration includes travel and, since 11.1.0, loading time ("Trades now always take into account
  loading times, with proper box size calculations"); `useAutoLoading` adds port fees. Verified.
- **No wait duration enters `timeInSeconds` or `profitPerMinute`.** Inferred: the site has no
  refill rate (§2), so it has nothing to compute a wait from, and no field carries one.
- Instead the client flags a trade. Verified, from the live bundle:

  ```text
  isWellStocked =
      (no commodity on either leg)
      or not (origin.itemQuantityInScu > origin.quantityInScu
              or destination.itemQuantityInScu > destination.maxQuantityInScu - destination.quantityInScu)
  ```

  A trade that is not well stocked gets a yellow border, and the list shows "It is possible you may
  have to wait for the trade runs outlined in yellow suggested below." and suggests visiting only
  high-capacity shops through "Expanded view", which reveals the minimum inventory capacity input.
  The buy side compares the cargo with the expected stock; the sell side compares it with the
  expected free capacity.

The earlier model with refresh rates and a computed wait is in the timeline (§7).

## 4. "Allow wait times", "Maximum wait time" and the smart filters

| Control | API field (11.5.0) | Default | Tooltip / description (quoted) | Label |
| --- | --- | --- | --- | --- |
| Allow wait times | `allowWaitTimes` (required) | off | Tooltip: "Filters out shops that cannot fill/buy your whole cargo hold at once." Routes: "stops where the player must wait for inventory to restock". Best buyer: "buyers where the player must wait for inventory to free up". | verified |
| Maximum wait time | none | — | Removed. It showed only when "Allow wait times" was on: "Time you are willing to wait at the trade terminal, given ideal circumstances." (minutes, empty = unlimited) | verified (bundles 2021-01, 2023-07; absent 2024-04 and live) |
| Minimum refresh rate | none | — | Removed with the above. "Shops only buy/sell so many SCU of a commodity per minute. This can cause wait times when competing with other traders." | verified (same bundles) |
| Minimum inventory capacity | `minInventorySizeInScu` | empty | "Shops have a given inventory capacity for each commodity. Usually, the larger the capacity, the more available the commodity." Spec: "The minimum inventory size in SCU that a shop listing must have to be considered." Best buyer: "minimum available inventory size". Shown only with Expanded view on. | verified |
| Smart filters | `smartFilters` | on | "Auto-picks values for the 'minimum inventory capacity' and 'supported box size' filters based on your ship." Spec: "automatically exclude low-inventory listings likely to be out of stock." Not offered on Best buyer. | verified |
| Expanded view | UI only | off | Shows security level, faction and the inventory estimates per stop. | verified |

How they interact:

- Smart filters on **disable the minimum inventory capacity input** (the form calls
  `minInventorySizeInScu.disable()`); the value is chosen server-side from the ship. Verified
  (bundle; bug #151 fixed in 10.2.1).
- Issue [#159](https://github.com/EtienneLamoureux/sc-trade-tools/issues/159): with smart filters
  on, a large-cargo ship lost low-capacity Lagrange stations; the reporter's tests point to a
  threshold keyed to the ship's maximum cargo, not the funded load. The maintainer: smart filters
  "are supposed to turn themselves off when they do not allow any results", and removing trades
  "the system doesn't think will be readily available is the goal"; closed without a change of
  design. Verified (issue text); the threshold formula is **not found**.
- 11.3.3 fixed optimal non-full-cargo routes missing "when both the 'Smart filters' and the 'Allow
  wait times' toggles are both OFF", and circular routes and itineraries "suggesting routes with
  wait times, despite wait times being disallowed". Verified (CHANGELOG, #119, #162).
- What `allowWaitTimes = false` compares against is not published. The spec text ("must wait for
  inventory to restock / free up"), the client's `isWellStocked` and the 11.3.3 fix suggest it
  drops stops whose **expected current** stock or free capacity is below the cargo, and that the
  yellow warning then appears only with wait times allowed. Inferred.
- Issue [#30](https://github.com/EtienneLamoureux/sc-trade-tools/issues/30) (cap the cargo to fit
  the maximum wait time instead of dropping the run) was closed as won't-fix on 2024-05-27.
  Verified.

## 5. Endpoints and tokens

Documented: every `/api/tools/*` route, `GET /api/commodity/shops/{name}/transactions`,
`GET /api/commodity/items/{name}/transactions`, `GET /api/commodity/transactions`,
`GET /api/commodity/reports`, `GET /api/graphs/shop/edges/{id}/alternatives` and the blueprint
reads take a required `token` header ("Requires a valid token"). The OpenAPI file declares no
security scheme; the token is a plain header. The README: "An API token is required for
authenticated endpoints"; Patreon supporters get one automatically. The MCP server
(`https://sc-trade.tools/mcp`) needs `Authorization: Bearer <API token>`. Verified.

The five permitted probes (2026-11-16, 12:40 UTC, no token, generic user agent):

| # | Request | Status | Notes |
| --- | --- | --- | --- |
| 1 | `GET /api/crowdsource/commodity-listings?page=0` | 200 | JSON, 100 per page, 753 total, `Cache-Control: max-age=600` |
| 2 | `GET /api/commodity/bounties` | 200 | 50 locations with a points multiplier 32–100 |
| 3 | `GET /api/commodity/shops/Pyro%20%3E%20Megumi%20Refueling/transactions` | 400 | Spring error body, missing required header |
| 4 | `GET /api/commodity/transactions?page=0` | 400 | same |
| 5 | `GET /api/commodity/reports` | 400 | same |

Also token-less per the spec, not probed: ships, locations, location types, security levels,
factions, commodity items and item types, commodity shops (names only), leaderboards, companion
version, item reads. A missing token answers `400`, not `401`/`403`. Verified.

The bounty multiplier is a free staleness signal: the leaderboard page states points are "Number of
reports X Hours since last report (capped at 100)", and the endpoint says "Locations with higher
multipliers have older data". Verified.

The website's own UI calls the token endpoints with a short-lived token it derives in the browser
(an `uiTokenService` with clock sync and a retry on `403`). Using that mechanism from another client
would circumvent the token requirement; it was not used and is out of bounds. Verified (bundle).
The Wayback Machine holds `200` copies of token-only transaction lists captured through the site;
they are third-party copies of SCTT material and fall under the same terms. Verified.

## 6. Terms for reusing the data

- Site terms (live bundle, "Terms and Conditions"): SCTT and its licensors own the material; access
  is for "your own personal use"; you must not republish, sell, rent or sub-license, reproduce,
  duplicate or copy, or redistribute material from SCTT. Framing the pages needs written approval.
  Verified.
- API spec 10.2.2–11.1.1: "Usage of this API without express written permission is prohibited",
  licence "All Rights Reserved". Since 11.3.0 the licence entry reads "Help us cover our
  infrastructure costs by acquiring an API licence" (Patreon). No grant to reuse data appears in
  any version. Verified.
- The crowdsourced feed is readable without a token, but no text exempts it from the site terms.
  Verified (absence).
- SC Trade Companion: GPL-3.0 (code only). Reading its source for the label mapping is fine; copying
  code would bring GPL obligations. Verified (licence file).
- Consequence: Basetool cannot import, cache or republish SCTT data without the owner's written
  permission, and the owner's public-sources-only rule rules out asking. Inferred.

## 7. Version timeline

Dates are the CHANGELOG commit dates of SCTT and the release tags of the companion.

| Date | Version | Change relevant here |
| --- | --- | --- |
| 2020-07-25 | 1.4.0 | Toggle "Show stock details": maximum inventory and refresh rate per listing (#7). |
| 2020-07-28 | 1.4.1 | "Maximum wait time" filter (#29) and "Minimum refresh rate" filter (#17). |
| 2020-07-30 | 1.4.3 | Fixed "maximum wait time" filter behaviour. |
| 2020-08-11 | 1.4.4 | Note that inventories and refresh rates are shared across servers (#46). |
| 2020-08-18 | 1.5.0 | Profit type "over time" (#26). |
| 2020-08-28 | 1.7.0 | Warning when the player may not buy/sell as much as displayed (#51). |
| 2020-10-19 | — | Maintainer in #3: almost all places refill completely "in about an hour and a half, some much quicker". |
| 2021-01 | (bundle) | Listing model `maxQuantity`, `quantityPerMinute`, `waitTimeInMinutes`; UI "Max N units + R/min" and "> W minutes wait"; well-stocked if `max/5 + tripMinutes × rate ≥ cargo` at both ends. |
| 2021-05-19 | 4.0.3 | Best buyer sorted by refresh rate as well as price. |
| 2021-11-13 | 4.2.0 | Updated "well-stocked" detection. The 2023-07 bundle also counts a rate below 5 SCU/min as not well stocked (absent in the 2021-10 bundle). |
| 2022-01-07 | 6.0.0 | API keys via Patreon. |
| 2023-11-14 | companion 0.1.0 | Kiosk OCR; stock label mapped to `saturation`. |
| 2023-11-21 | 7.3.0 | Backend prepared for crowdsourced submissions. |
| 2023-12-23 | — | #61: refresh rates no longer datamineable. |
| 2024-03-07 | 8.0.0 | "Replace refresh rate with current quantity"; companion submissions; leaderboard. Maximum wait time and minimum refresh rate leave the UI; "Minimum inventory capacity" arrives. |
| 2024-03-12 | 8.1.0 | `GET /api/crowdsource/commodity-listings`; data shared both ways with UEX. |
| 2024-03-23 | 8.1.1 | Better aggregation, "better price and quantity estimates". |
| 2024-04 | (bundle) | Well-stocked predicate switches to expected stock and free capacity; `9999` capacity sentinel. |
| 2024-06-21 | 8.1.5 | "Maximum inventories now have better realistic default values" (#128). |
| 2024-07-20 | 9.0.0 | "even better realistic default values". |
| 2024-10-18 | 10.0.0 | Smart filters (on by default), Expanded view with inventory estimates, better capacity estimates. |
| 2024-12-05 | companion 1.0.4 | Location read from the game log. |
| 2025-05-26 | companion 1.1.1 | Log reading disabled. |
| 2025-11-20 | 10.2.1 | Minimum inventory capacity greyed out correctly under smart filters (#151). |
| 2026-04-11 | 11.1.0 | Loading times always counted; auto-loading with port fees. |
| 2026-05-11 | 11.3.0 | Field descriptions in the spec; MCP server. |
| 2026-09-14 | 11.3.3 | Fixes for smart filters + allow wait times off, and wait-time routes despite disallowed (#119, #162). |
| 2026-09-18 | — | #159 closed: smart filters keep excluding low-capacity shops by design. |
| 2026-10-23 | 11.4.0 | `GET /api/commodity/transactions` (token). |
| 2026-10-31 | 11.4.1 | `submitedAtOrAfter` on the crowdsourced feed. |
| 2026-11-14 | 11.5.1 | Live client version at the snapshot. |

The 2021 and 2023 rows marked "(bundle)" come from archived site bundles:
[2021-01 form](http://web.archive.org/web/20210130141517/https://sc-trade.tools/1-es2015.b0d013a2820852f1ca09.js),
[2021-01 results](http://web.archive.org/web/20210130141518/https://sc-trade.tools/7-es2015.0a8b312102d2f60d743a.js),
[2023-07](http://web.archive.org/web/20230706062851/https://sc-trade.tools/369.f22f94a49b1c89be.js),
[2024-04](http://web.archive.org/web/20240413154604/https://sc-trade.tools/369.d636e3805e2e8337.js).
Whether `waitTimeInMinutes` was added into `timeInSeconds` or `profitPerMinute` in that era is
**not found**; issue #29 asked for the maximum wait time so that "the profit can be lowered", which
suggests it was (inferred).

## Verified vs inferred

Verified:

- The record fields and their descriptions; the companion's OCR pipeline, label-to-saturation
  mapping, timestamp and batch-id origin, publish endpoint and headers; the disabled log reader.
- The read model (`quantityInScu`, `maxQuantityInScu`, no wait field), the UI tooltips, the
  `9999` sentinel and the current and historical well-stocked predicates.
- The FAQ statements that refill is unknown and stock is estimated from community reports.
- The filter set and defaults, the smart-filter lock on minimum inventory capacity, the removal of
  maximum wait time and minimum refresh rate between the 2023-07 and 2024-04 bundles (8.0.0 is the
  only release in that window that touches them).
- Token requirements and the five probe results; the terms and licence texts.

Inferred:

- No wait duration enters route duration or profit per minute today.
- `allowWaitTimes = false` compares the cargo with the expected current stock and free capacity.
- Capacity estimates start from `quantity / saturation` and the median of similar listings.
- The 1/10 stock-to-capacity ratio in an archived response is a default fill, not an observation.
- The UUID-keyed rows in the public feed come from a source other than the companion.
- In the refresh-rate era the wait time lowered profit per minute.

Not found: the capacity estimator, the projection "at this time", the smart-filter threshold, the
mapping of UEX grades into `saturation`, the submission `POST` in any spec.

## Open points for the decision tickets

1. **Refill is the missing input everywhere.** SCTT dropped refill rates when the game files lost
   them and now computes no wait at all. A wait time in Basetool needs our own refill estimate,
   which means storing repeated observations of the same shop line over time.
2. **Fill level arrives as a band.** Kiosk labels give seven steps (sixths); SCTT stores them as
   ratios. Decide whether we store the label (honest) or a ratio (convenient), and how a capacity
   is derived from `quantity` and a band, including the `0` and `1` ends.
3. **Wait handling: filter or duration.** SCTT's current answer is binary (drop the stop or flag
   it). Decide whether Basetool shows a flag, a duration, or both, and whether a wait counts into
   profit per minute.
4. **Default filter like smart filters.** A minimum capacity tied to the ship's cargo prevents
   failed runs but hides good low-capacity stops (#159). Decide the default and the escape hatch.
5. **SCTT is a design reference, not a data source.** Its terms forbid reuse, its token-less feed
   is unfiltered, and asking for permission is outside the owner's rule. UEX shares data with
   SCTT; the UEX ticket (#3451) covers what we can take from there.
6. **Staleness signal.** SCTT exposes "hours since last report" per location (capped at 100); our
   own data needs an equivalent age per shop line.
