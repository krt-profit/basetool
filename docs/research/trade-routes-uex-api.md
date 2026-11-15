# What the UEX API offers for trade routes

Doc type: research snapshot; findings for the trade-route map, not a binding specification.

- Ticket: [#3451](https://github.com/krt-profit/basetool/issues/3451), map
  [#3450](https://github.com/krt-profit/basetool/issues/3450).
- Snapshot: 2026-11-15, about 17:25–17:40 UTC. UEX reported "UEX System v2.7.79", Star Citizen
  LIVE `4.10.3`, PTU `5.0.0` (`GET /game_versions`).
- Local baseline: `origin/main` at `732fd614f`.
- Method: UEX's own API 2.0 documentation pages (`https://uexcorp.space/api/documentation/id/<endpoint>/`),
  its terms page, and token-less public `GET` calls against `https://api.uexcorp.uk/2.0` for
  endpoints whose documentation lists no authorization. No token, no account, no `.env` read.
- Labels: **verified** = read in the documentation, the source code or an actual response;
  **inference** = a consequence not demonstrated; **not found** = looked for and absent.

## Summary

- UEX serves per terminal and commodity: the last buy / sell price and SCU, min / max / average over
  the default 15 days, a week and a month, a 15-day average from **user trades** with its trip count,
  the reported stock, a forecast demand, the graded inventory status 1–7 with the same statistics,
  volatility coefficients, quality, container sizes and dates. Only `/commodities_prices` (≤ 10
  terminals per call) carries all of it; `/commodities_prices_all`, which we sync, carries 21 keys.
- The status scale is 1 "Out of Stock (Empty)" … 7 "Maximum Inventory"; each grade is a stock band
  of 14–15 percentage points. On the sell side 7 is "No Demand". `0` appears where a side is not
  traded. We store `status == 1` as a Boolean whose Javadoc reads "currently sells / buys"; on the
  buy side `1` means out of stock.
- No field marks a value as "probable". The signals that exist are the user-trade averages with
  their trip counts, `date_modified` against UEX's 15-day TTL, volatility and the status grade.
- History exists per terminal and commodity (`/commodities_prices_history`, ≤ 500 rows). No endpoint
  publishes maximum stock or restock speed.
- `/commodities_routes` returns precomputed **single-hop, single-commodity** routes (≤ 500 rows)
  with margin, ROI, investment, profit, distance in whole Gm, a score and place attributes. No
  multi-stop route, ship, cargo capacity or travel time.
- Distances: terminal pairs (one pair per call) and orbit-to-orbit matrices reported by
  datarunners, whole gigametres, from several game versions, no path. `/jump_points` publishes the
  system graph (six directed edges between Stanton, Pyro and Nyx).
- The API terms are silent on caching, derived results and passing them to third parties. The
  website terms limit the **website** to personal, non-commercial use.

## 1. Access, quotas and freshness

| Fact | Value | Source | Label |
| --- | --- | --- | --- |
| Base URL and envelope | `https://api.uexcorp.uk/2.0/{resource}`, `{"status":"ok","data":…}` | [docs home](https://uexcorp.space/api/documentation/) | verified |
| Authentication | Bearer token for the API as a whole; endpoints whose page shows Authorization "—" answer without one | docs home; every endpoint page | verified (all calls below ran token-less and returned `ok`) |
| No-auth statement | `/data_extract` says it "does not require API authentication" | [data_extract](https://uexcorp.space/api/documentation/id/get_data_extract/) | verified |
| Token-only endpoints read here | `/commodities_averages`, `/data_monitor`, `/data_info`, `/user_trades`, `POST /data_submit` | the endpoint pages | verified, not called |
| Daily quota | "172800 requests (or 120 requests per minute)" | docs home | verified |
| Quota for token-less calls | not documented; responses carry no rate-limit header | response headers of every call | not found |
| Over-quota answer | status `requests_limit_reached` | docs home | verified |
| Client version lock | optional `X-Client-Version` header per API key | docs home | verified |
| Conditional requests | no `ETag`, no `Last-Modified`; `Cache-Control: public, must-revalidate, max-age=1800` on prices and routes, `86400` on the status list | response headers | verified |

Cache TTL and update frequency per endpoint (from each documentation page):

| Endpoint | Cache TTL | Update frequency |
| --- | --- | --- |
| `commodities_prices`, `commodities_prices_all`, `commodities_routes`, `commodities_alerts` | 30 min | hourly |
| `commodities_prices_history` | 12 h | hourly |
| `terminals_distances` | 12 h | hourly |
| `terminals` | 12 h | patch cycle |
| `space_stations`, `cities`, `outposts`, `poi`, `jump_points`, `orbits_distances` | 1 day | patch cycle |
| `commodities_status`, `data_parameters`, `game_versions` | 1 day | daily / patch basis |

Report pipeline and freshness:

- `GET /data_parameters` (live): commodity reports accepted, `price_variation` 25, `scu_variation`
  5000, **`ttl` 15** ("days until a price is considered outdated"), new datarunners in a 90-day
  evaluation period. The docs home lists a "Default Average (Days)" of 15 and a 25 % variation
  tolerance for commodities. Verified.
- `/data_info` names the report lifecycle: `pending`, `under_review`, `queued` (PTU), `approved`,
  `consolidated` ("folded into the live prices"), `declined`, `expired`. Terms §6.2.5: UEX aims to
  process reports within 24 hours. Verified.
- Live `date_modified` across the 2 587 rows of `/commodities_prices_all`: 1 514 within a day,
  761 within 7 days, 283 within 15 days, 29 older than 15 days (21 older than 90). Verified.
- Our sync runs every 24 h (`UexScheduler`, `fixedDelayString = "${krt.uex.scheduler-delay:86400000}"`,
  [UexScheduler.java](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexScheduler.java)
  lines 71–73). Verified.

## 2. Prices and stock

### 2.1 `/commodities_prices_all` — what we sync

Documented output ([page](https://uexcorp.space/api/documentation/id/get_commodities_prices_all/)):
`id`, `id_commodity`, `id_terminal`, `price_buy` (last), `price_buy_avg`, `price_sell` (last),
`price_sell_avg`, `scu_buy` (last), `scu_buy_avg`, `scu_sell_stock` (last), `scu_sell_stock_avg`
("average reported"), `scu_sell`, `scu_sell_avg`, `status_buy`, `status_sell`, `container_sizes`
(CSV of 1|2|4|8|16|24|32), `quality` (0–1000), `date_added`, `date_modified`, names, codes and slugs.

Live payload (verified): 2 587 rows, 135 terminals, 124 commodities, exactly these 21 keys:
`commodity_name, container_sizes, date_added, date_modified, id, id_commodity, id_terminal,
price_buy, price_buy_avg, price_sell, price_sell_avg, quality, scu_buy, scu_buy_avg, scu_sell,
scu_sell_avg, scu_sell_stock, scu_sell_stock_avg, status_buy, status_sell, terminal_name`.
The documented `commodity_code/slug` and `terminal_code/slug` are **not served**; the set equals the
one captured in `ExternalCatalogueMappingTest` (`UEX_COMMODITY_PRICE_KEYS`, lines 374–396).

Live shape (verified): no row has both a buy and a sell price (701 buy rows, 1 886 sell rows);
`quality` is `0` on every row; `container_sizes` is set on 2 569 rows; 1 575 of 1 886 sell rows carry
a positive `scu_sell_stock`, only 301 a positive `scu_sell`.

### 2.2 `/commodities_prices` — the full record

[Page](https://uexcorp.space/api/documentation/id/get_commodities_prices/). Input: at least one of
`id_terminal` (up to 10 ids, comma-separated), `id_commodity`, terminal or commodity name / code /
slug. No authorization. Verified live for `id_terminal=12` (TDD Area 18, 85 rows).

| Family | Fields | Meaning per UEX |
| --- | --- | --- |
| Buy price | `price_buy`, `_min`, `_max`, `_avg`, each also `_week` and `_month` | last value and statistics |
| Sell price | `price_sell` with the same ten variants | as above |
| User trades | `price_buy_users`, `price_sell_users`, `scu_buy_users`, `scu_sell_users` | "last 15d, average from user trades" |
| User trade counts | `*_users_rows` for the four above | "trips in the last 15d, coming from user trades" |
| Stock offered | `scu_buy` with the ten variants | SCU the terminal sells |
| Reported inventory (sell side) | `scu_sell_stock`, `_avg`, `_avg_week`, `_avg_month` | "last amount of SCU reported at location" |
| Demand (sell side) | `scu_sell` with the ten variants | "scu sell demand (forecasted)" |
| Inventory grade | `status_buy`, `status_sell`, each with the ten variants | see 2.3 |
| Volatility | `volatility_price_buy/sell`, `volatility_scu_buy/sell` | coefficient of variation, "higher is worse" |
| Reputation | `faction_affinity` | datarunners' average affinity, −100…100 |
| Quality | `quality` with the ten variants | 0–1000 |
| Other | `container_sizes`, `game_version`, `date_added`, `date_modified`, location ids and names, `terminal_is_player_owned`, `terminal_mcs` (deprecated) | |

Live extras not in the documentation: `poi_name`, `faction_name`. At TDD Area 18 every
`*_users_rows` was `0` on all 84 sell rows (verified); how sparse user-trade data is elsewhere was
not measured.

The unsuffixed `_min / _max / _avg` window is not stated on the page; the docs home's default
average of 15 days and the 15-day description of `/commodities_averages` suggest 15 days
(inference).

Related endpoints:

- `/commodities_averages?id_commodity=` (token): the commodity across all terminals over 15 days,
  adding `scu_buy_total` ("cumulative stock available"), `scu_sell_total` ("cumulative amount
  demanded") and `cax_score`. Read, not called.
- `/commodities_raw_prices`, `_raw_prices_all`, `_raw_averages`: the same for raw ore, prices only;
  the SCU and status lines of `_raw_averages` sit inside HTML comments on the page. Verified.
- `/commodities_alerts`: latest alerts with price, SCU and status per terminal; the trigger is not
  documented. Verified (page).
- `/commodities_ranking`: deprecated in favour of `cax_score`. Verified (page).
- `/fuel_prices`: hydrogen and quantum fuel prices per terminal, same statistics as the buy side.
  Verified (page).

Computing the full record for every commodity terminal takes 14 calls of `/commodities_prices`
(135 terminals, 10 per call) instead of one `/commodities_prices_all` (inference, arithmetic).

### 2.3 The graded status scale

`GET /commodities_status` (live, verified) returns one list per side:

| Code | Name (buy side) | Abbr. | Stock band | Buy colour | Sell colour |
| --- | --- | --- | --- | --- | --- |
| 1 | Out of Stock (Empty) | OS | 0–14 % | red | green |
| 2 | Very Low Inventory | VL | 15–28 % | red | green |
| 3 | Low Inventory | LO | 29–43 % | orange | blue |
| 4 | Medium Inventory | ME | 44–57 % | blue | blue |
| 5 | High Inventory | HI | 58–71 % | blue | orange |
| 6 | Very High Inventory | VH | 72–85 % | green | red |
| 7 | Maximum Inventory (Full) | MA | 86–100 % | green | red |

On the sell side code 7 is named "Maximum Inventory (No Demand)" (abbr. `ND`); the other names are
the same. `POST /data_submit` documents the reported value as "1 - out of stock, 7 - maximum".
`/commodities_routes` reads the status as "higher is better" at the origin and "lower is better" at
the destination. The bands are a share of an unpublished terminal maximum (inference from
`percentage_start` / `percentage_end`).

Live distribution (verified): `status_buy` on the 701 buy rows — 1: 6, 2: 102, 3: 61, 4: 30,
5: 29, 6: 23, 7: 450. `status_sell` on the 1 886 sell rows — 1: 1 686, 2: 45, 3: 44, 4: 49, 5: 35,
6: 16, 7: 11. `0` appears on 1 871 rows for `status_buy` and 700 for `status_sell`, never on a row
with a price on that side; the documentation types the field `int|null` and does not list `0`.

### 2.4 What we store, against the scale

- `UexCommodityPriceDto.isStatusBuy()` / `isStatusSell()` return `status == 1`
  ([UexCommodityPriceDto.java](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/uex/UexCommodityPriceDto.java)
  lines 58–64), written by `UexCommodityService` (lines 388–389) into `BOOLEAN` columns
  (`V1__initial_schema.sql` lines 369–370). Verified.
- `MaterialPriceDto` documents them as "whether the terminal currently sells / buys the material"
  ([MaterialPriceDto.java](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/MaterialPriceDto.java)
  lines 37–38). Verified.
- Against UEX's scale, `status_buy == 1` is "Out of Stock (Empty)": 695 of the 701 rows where a
  terminal sells a commodity store `statusBuy = false`, and 10 rows with no buy price but
  `status_buy = 1` store `true`. Grades 2–7 are lost on both sides. Verified against the live
  payload.
- Every query that reads the Boolean ORs it with a positive price
  (`MaterialPriceRepository` lines 109–110, 129, 173–174) and no template shows it, so the visible
  effect today is small (inference). A follow-up issue for the mapping is a candidate; this note
  files none.

## 3. Reports, history, inventory and restock

| Need | What UEX offers | Auth | Label |
| --- | --- | --- | --- |
| Averages from user reports | `*_users` 15-day averages from **user trades** with `*_users_rows` trip counts (`/commodities_prices`, `/commodities_averages`, `/commodities_routes`) | none / token | verified |
| Datarunner reports | `GET /data_info`: any report by id, type, terminal, status, user; ≤ 100 rows | token + datarunner `secret-key` | verified (page) |
| Report freshness per terminal | `GET /data_monitor`: `prices_total`, `prices_updated`, `prices_updated_percentage`, `last_update_days`, `last_update_days_limit` (TTL), pending report ids | token | verified (page) |
| Recent reports, plain text | `GET /data_extract?data=last_commodity_data_reports` (last 30) | none | verified (page) |
| One's own trades | `GET /user_trades` | token + `secret-key` | verified (page) |
| Price and stock history | `GET /commodities_prices_history?id_terminal=&id_commodity=` (both required, optional `game_version`): `price_buy/sell`, `scu_buy`, `scu_sell_stock`, `scu_sell`, `status_buy/sell`, `quality`, `game_version`, `date_added`; ≤ 500 rows | none | verified |
| Current stock | `scu_buy` (offered), `scu_sell_stock` (reported inventory), `scu_sell` (forecast demand, "based on reported level") and the grade band | none | verified |
| Maximum stock | no field | — | not found |
| Restock speed | no endpoint or field | — | not found |

- **"Probable" marking: not found.** No API field is named or documented as probable, likely or
  confidence. UEX's route page on the website shows labels "Expectation (Based on Recent Values)",
  "(Less Predictable)" and "User Activity: Low", and a footer saying all values are estimates from
  datarunner reports ([example](https://uexcorp.space/trade/route?code=073e1d16)); no API field
  matches these labels and their rule is not published.
- History sample (verified): Aluminium at TDD Area 18 had 6 rows over three days; `scu_sell_stock`
  went 162 → 87 → 0 while the status stayed 1. Rows are report-driven, so sampling is irregular.
- A maximum could be estimated as stock ÷ grade band, and restock from successive history rows; both
  are rough (± 7 percentage points per band, irregular samples). Inference.
- Prices depend on reputation: `terminals.is_affinity_influenceable` ("reputation affects prices")
  and `faction_affinity` per price row. Verified (pages).

## 4. Routes and distances

### 4.1 `/commodities_routes`

[Page](https://uexcorp.space/api/documentation/id/get_commodities_routes/): "a list of common routes
calculated based on data reports". No authorization, 30 min TTL, hourly, at most 500 rows.

- Input: at least one of `id_terminal_origin`, `id_planet_origin`, `id_orbit_origin`,
  `id_commodity`; optional destination terminal / planet / orbit, origin and destination faction,
  `investment`.
- Output per row: **one commodity from one origin terminal to one destination terminal**;
  `price_origin` / `price_destination` with `_users` and `_users_rows`; `price_margin`,
  `price_roi`; `scu_origin` / `scu_destination` with user values; `scu_margin`; `status_origin`
  (higher is better), `status_destination` (lower is better); `investment` ("scu_buy_avg *
  price_buy_avg"); `profit` ("maximum profit expected"); `distance` (Gm); `score` ("UEX score
  level, higher is better"); container sizes and game versions per end; per end
  `has_docking_port`, `has_freight_elevator`, `has_loading_dock`, `has_refuel`, `has_cargo_center`,
  `has_quantum_marker`, `is_monitored`, `is_space_station`, `is_on_ground`; names, codes, a route
  `code` for the website.
- Live (verified): origin ArcCorp 048 returned 15 Waste routes, 9 in Stanton, 3 in Pyro, 3 in Nyx;
  `distance` integers 0–120, `0` between two terminals of the ArcCorp orbit; routes with negative
  profit included; an undocumented field `scu_reachable`. `id_commodity=79` returned exactly 500
  rows (the cap) from 34 origins, 119 with negative profit, not ordered by score.
- `investment=5000` on the same origin returned no rows, while its routes need 17 175 UEC; the input
  behaves as a maximum-investment filter (inference).
- Not offered: several commodities per stop, more than two stops, round trips, ship or cargo
  capacity, travel or loading time. The score formula is not documented. The website's route page
  shows an ETA ("3m 19s" for ~23 Gm); no API field carries it. Not found.

### 4.2 Distances and the system graph

| Endpoint | What it returns | Live observation (verified) |
| --- | --- | --- |
| `/terminals_distances?id_terminal_origin=&id_terminal_destination=` | "Estimate" of the distance between two terminals in Gm; one pair per call | Area 18 → ARC-L1: `"distance":"3"` (a string, documented as float) |
| `/orbits_distances?id_star_system_origin=&id_star_system_destination=` (optional orbit ids) | "the last orbital distances reported by Datarunners", Gm, `game_version` per row | Stanton: 810 rows for 29 orbits (812 ordered pairs), 1–120 Gm, symmetric in the sample, strings, versions 3.24.2 … 4.7.1; Stanton → Pyro: 564 rows |
| `/jump_points` | directed system edges with gateway orbit ids | 6 edges: Stanton ↔ Pyro, Pyro ↔ Nyx, Nyx ↔ Stanton |

- Granularity is the orbit: two terminals on one planet are 0 Gm apart in `/commodities_routes`
  (verified); terminal pairs cost one call each, 18 090 ordered pairs for 135 commodity terminals
  (inference, arithmetic).
- A cross-system distance is one number with no path, so it does not say which jump point it
  crosses (verified: no path field). Excluding a jump point therefore needs our own graph; UEX's
  `/jump_points` supplies its edges (inference).
- Travel time, quantum-drive or loading-time data: not found in any of the 89 endpoint pages listed
  on the documentation home.

## 5. Place attributes for filters

| Attribute | `/terminals` | `/space_stations`, `/cities`, `/outposts`, `/poi` | `/commodities_routes` | Stored today |
| --- | --- | --- | --- | --- |
| Loading dock | `has_loading_dock` | `has_loading_dock` | `has_loading_dock_*` ("external freight elevator / autoload area") | terminal + locations |
| Freight elevator | `has_freight_elevator` | `has_freight_elevator` | `has_freight_elevator_*` | terminal + locations |
| Auto-load | `is_auto_load` | — | — | terminal |
| Docking port | `has_docking_port` | `has_docking_port` | `has_docking_port_*` | terminal + locations |
| Max container size | `max_container_size` (SCU); per price row `container_sizes` | — | `container_sizes_*` | terminal; price row |
| Refuel | `is_refuel` | `has_refuel` | `has_refuel_*` | terminal + locations |
| Cargo centre | `is_cargo_center` | `has_cargo_center` | `has_cargo_center_*` | locations |
| Armistice | — | `is_armistice` | — | locations |
| Monitored space | — | `is_monitored` | `is_monitored_*` ("uee commlink") | locations |
| Pad sizes | — | `pad_types` (XS, S, M, L, XL) | — | locations |
| Kind | parent ids `id_space_station`, `id_city`, `id_outpost`, `id_poi` (+ names); `type` is the terminal kind (`commodity`, `commodity_raw`, `item`, `fuel`, …) | the table itself | `is_space_station_*`, `is_on_ground_*` | names only |
| Jump point | `is_jump_point` | `is_jump_point` (stations), `is_lagrange` | — | terminal + stations |
| Other | `is_player_owned`, `is_nqa`, `is_available_live`, `is_affinity_influenceable` | `is_landable`, `is_decommissioned`, `has_quantum_marker`, `is_available_live` | `*_terminal_is_player_owned` | partly |

Sources: the five documentation pages and the live key sets in `ExternalCatalogueMappingTest`
(lines 426–625). `UexTerminalDto` binds the parent **names** only, while the payload also carries the
parent **ids** (`id_space_station`, `id_city`, `id_outpost`, `id_poi`, `id_orbit`, `id_moon`,
`id_planet`, `id_star_system`). Verified.

## 6. Terms of use

Terms page: <https://uexcorp.space/about/terms> ("Updated: Jul 2, 2026").

- Website, §1.4: "You may use the UEX website for personal, non-commercial purposes only."
- Website registration, §3.2: "created for personal entertainment and usage only."
- API terms, §5.1–5.11, cover: work in progress, 98–99 % uptime goal, the right to "modify, update,
  or remove API endpoints and resources at any time" (§5.4), user responsibility to follow the
  documentation, no accuracy guarantee (§5.6), the right to "terminate or restrict access to the API
  for any reason" (§5.7), liability, contact and versioning.
- **Silent** (not found in §1–§10): caching or storing API data, computing derived results such as
  routes, passing data or derived results to third parties, attribution, commercial use of the API.
- The API page states "UEX API data comes from community reports."
  ([docs home](https://uexcorp.space/api/documentation/)); §3.6 calls UEX data estimated, with no
  warranty.
- UEX lists third-party apps built on its data on its
  [Community Tools page](https://uexcorp.space/api/community_made/), among them tools offering
  trade and price features "powered by UEX data". That shows UEX promotes such integrations; it is
  not a licence (inference).
- The per-app terms behind "My Apps" and the Webhooks page require a UEX sign-in and were not read.

Reading against the three questions (all inference, decisions belong to the owner): computing and
showing routes, caching, and passing derived results to exchange clients are neither allowed nor
forbidden by the API terms; the documented per-endpoint cache TTLs presume clients cache; §5.7 lets
UEX cut access at will. §5.10 names UEX support (email or contact form) as the contact for API terms.

## 7. What the Basetool calls today

- [UexProperties.java](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/internal/UexProperties.java)
  lines 64–88: base `https://api.uexcorp.uk/2.0`; endpoints `commodities`, `commodities_prices_all`,
  `star_systems`, `companies`, `vehicles`, the universe tables, `terminals`, refinery methods and
  yields, `items`, `items_prices_all`, `categories`. No token property. Not called:
  `commodities_prices`, `commodities_status`, `commodities_prices_history`, `commodities_routes`,
  `terminals_distances`, `orbits_distances`, `jump_points`, `fuel_prices`, `data_parameters`.
- `UexClient` sends plain `GET`s without an `Authorization` header, at most 4 in flight, bodies
  capped at 16 MiB; fail-soft to empty rows. The live `/commodities_prices_all` body was 1 070 618
  bytes. Verified.
- Spec pointers: REQ-DATA-005 (row / chunk transactions), REQ-DATA-014 (sweep only on a complete
  census), REQ-DATA-015 (bind only served keys, captured live), REQ-DATA-030 (averages, container
  sizes, terminal services), REQ-DATA-031 (commodity locations) in
  [data-persistence.md](../specs/data-persistence.md).

## Open points for the decision tickets

1. **Terms.** Whether the owner wants written confirmation from UEX (support contact, §5.10) before
   routes computed from UEX data are cached and handed to exchange clients; the API terms are
   silent and the website terms say personal, non-commercial.
2. **Quota and token.** The quota for token-less calls is undocumented; whether the Basetool
   registers a UEX app and token (needed for `/commodities_averages` and `/data_monitor`) is open.
3. **Storing the grade.** Whether `material_price` keeps the full 0–7 grade per side (and which of
   its min / max / avg variants); the current Boolean reads `1` as "sells" where UEX means "out of
   stock" — a follow-up issue candidate.
4. **"Probably available."** UEX has no such flag; the criterion has to be defined from
   `date_modified` vs the 15-day TTL, `*_users_rows`, volatility and the status grade, and the
   sparseness of user-trade data across all terminals was not measured.
5. **Source of distances.** UEX distances are whole gigametres at orbit granularity, from reports
   across older game versions, without a path; whether routing uses them, the P4K starmap positions,
   or both, and how jump-point exclusion uses `/jump_points`.
6. **Maximum stock and restock.** Not published; whether an estimate from grade bands and history
   (≤ 500 rows, one call per terminal and commodity) is acceptable, or the p4kx shop data has to
   supply it.
7. **Role of `/commodities_routes`.** It is single-hop, single-commodity and capped at 500 rows; whether
   it serves as a cross-check, a seed, or not at all.
8. **Cadence.** Upstream prices change hourly and our sync runs daily; showing "sold out at this stop"
   needs fresher data, either a shorter sync or on-demand calls within the quota.
9. **Documentation drift.** `/commodities_prices_all` documents four keys it does not serve, the
   routes payload carries the undocumented `scu_reachable`, and distances arrive as strings; any new
   binding needs a live key capture (REQ-DATA-015).
