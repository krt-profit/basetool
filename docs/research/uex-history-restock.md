# What UEX price history shows about restock and price movement

Doc type: research snapshot; findings for the terminal-restock map, not a binding specification.

- Ticket: [#3479](https://github.com/krt-profit/basetool/issues/3479), map
  [#3477](https://github.com/krt-profit/basetool/issues/3477); context: the
  [map's facts](https://github.com/krt-profit/basetool/issues/3477#issuecomment-6916422188) and the
  [UEX API note](https://github.com/krt-profit/basetool/blob/research/trade-routes-uex-api/docs/research/trade-routes-uex-api.md).
- Snapshot: 2026-11-16, 12:33–12:41 UTC. UEX live version `4.10.3`, PTU `5.0.0`
  (`GET /game_versions`); site footer "UEX System v2.7.80".
- Method: UEX's documentation pages for the
  [API](https://uexcorp.space/api/documentation/) and
  [`commodities_prices_history`](https://uexcorp.space/api/documentation/id/get_commodities_prices_history/),
  the [terms page](https://uexcorp.space/about/terms), and **49 token-less `GET` requests** against
  `https://api.uexcorp.uk/2.0` (the host the documentation names), at least 1.5 s apart, all `200`.
  Analysis with a local Python script; raw responses stayed outside the repository.
- Requests: `game_versions`, `game_versions_all`, `terminals`, `commodities_prices_all`, one
  `commodities_prices` call for ten hub terminals, 35 history calls without `game_version` and 9 with it.
- Labels: **verified** = read in the documentation or computed from an actual response;
  **inference** = a consequence not demonstrated; **not found** = looked for and absent.

## Question

Is UEX's price history dense and precise enough to estimate, per terminal, commodity and side, a
restock rate (how fast stock refills on the buy side, how fast demand frees up on the sell side) and
how the price moves with the stock?

## Summary

- **No per-pair restock rate can be estimated from UEX history.** Reports are sparse (median 0.83 per
  pair and day in the busiest phase, 0.09–0.53 per day over a whole version), irregular (median gap
  9.5 h, up to 300 h), carry no shard, trade amount or reporter purchase, and most refill or drain
  intervals end at the cap or floor or include unseen trades. What the data does give: lower bounds,
  the share of reports per grade, and the full-stock value of pairs that reach grade 7.
- **History is partitioned by game version.** Without `game_version` the endpoint returns only the
  current live version; with it, that version's rows (checked back to `4.9`). The 500-row cap was never
  approached: at most 22 rows in one version. A full history costs one call per pair and version.
- **The grade is ordinal, not a fill ratio.** Within a series a higher grade always meant more SCU
  (517 of 517 row pairs), but UEX's percentage bands fit no single maximum in 14 of 26 series (7 even
  with half a band of slack). `scu_sell` is computed from stock and grade (68 of 68 rows), not observed.
- **Buy prices do not move with stock; sell prices move a little against it.** Buy price was constant
  in 17 of 17 series (82 rows, stock from 0 to full). Sell price fell as the grade rose in 194 of 235
  row pairs; an empty terminal (grade 1) paid a median 3.8 % more (range +0.6 % to +12.3 %, 9 series).
- **A full pull** is about 2,587 calls per game version (2,587 live pairs at 135 terminals); polling
  `/commodities_prices_all` hourly (24 calls a day) sees nearly every new report, because the live row
  is the newest report.
- **Terms**: the API terms are silent on pulling history, storing it and publishing derived values;
  the website terms limit the website to personal, non-commercial use.

## 1. The endpoint and the `game_version` field

| Fact | Value | Label |
| --- | --- | --- |
| Call | `GET /commodities_prices_history?id_terminal=&id_commodity=` (one pair per call), optional `game_version` ("e.g. 4.10.3") | verified ([page](https://uexcorp.space/api/documentation/id/get_commodities_prices_history/)) |
| Auth, cache, update, limit | Authorization "—", cache TTL 12 h, update frequency hourly, "Maximum of 500 rows" | verified (page; response `Cache-Control: public, max-age=43200`) |
| Row fields | `price_buy/sell`, `scu_buy`, `scu_sell_stock`, `scu_sell`, `status_buy/sell`, `quality`, `game_version`, `date_added`, location ids and names | verified (page and responses; `poi_name` also served) |
| Default window | all 107 rows of the 35 default calls carry `4.10.3`; the oldest is dated 2026-11-12 21:23 UTC, the `date_added` of `4.10.3` in `/game_versions_all` | verified |
| Filter | `game_version=4.10.2` and `=4.9` returned only rows of that version, spanning the version's life (4.9: 2026-07-17 … 08-24) | verified |
| Sort order | not documented; 19 multi-row responses ascending by date, 4 mixed | verified |
| `id` | not monotone with `date_added` (8 inversions in the sample) | verified |
| `quality` | `0` on every row | verified |

- The default call therefore shows at most the days since the last patch: 3.6 days at the snapshot.
  Versions lasted 34.3 days (`4.10.2`) and 41.7 days (`4.9`) (verified, `/game_versions_all`).
- No sampled series came near 500 rows (maximum 22 in a whole version); at the densest observed rate
  (2.5 a day) a pair would need about 200 days in one version to reach it (inference).
- Retention before `4.9` was not checked. `/game_versions_all` lists 168 versions (verified).

## 2. Density and gaps

Sample: 35 pairs (verified) — buy side 18 (Stanton 8, Pyro 7, Nyx 3), sell side 17 (Stanton 8,
Pyro 5, Nyx 4); chosen as hubs (TDD Area 18, TDD New Babbage, CBD Lorville, Levski, Ruin Station,
Checkmate, Orbituary …), mid-traffic stations and outposts, and quiet pairs whose live row was
3.6–612 days old.

| Measure (current version, 3.6 days) | Value |
| --- | --- |
| Rows | 107 for 35 pairs; median 3, maximum 9 |
| Pairs with no row | 8 (7 of the 8 quiet pairs, 1 mid) |
| Reports per pair and day | median 0.83; hubs median 1.10, max 2.48; quiet median 0 |
| Gaps between reports (80) | quartiles 4.9 h / 9.5 h / 19.7 h, max 67 h; 4 under 1 h |

| Pair (previous version `4.10.2`, 34.3 days; `4.9`, 41.7 days) | Rows | Per day | Median gap | Max gap |
| --- | --- | --- | --- | --- |
| Levski, Agricium (buy) | 18 | 0.52 | 33 h | 133 h |
| TDD Area 18, Laranite (sell), 4.10.2 / 4.9 | 12 / 22 | 0.35 / 0.53 | 81 h / 33 h | 157 h / 115 h |
| TDD New Babbage, Medical Supplies (sell) | 12 | 0.35 | 45 h | 195 h |
| Ruin Station, Helium (buy) | 9 | 0.26 | 31 h | 190 h |
| HDMS-Bezdek, Titanium (buy) | 7 | 0.20 | 62 h | 299 h |
| Fallow Field, Bexalite (buy) | 5 | 0.15 | 87 h | 263 h |
| Reclamation Orinth, Copper (sell) | 3 | 0.09 | 184 h | 246 h |

- Reports arrive per terminal visit: every commodity of a terminal carries the same minute (TDD
  Area 18: 8 visits in 3.6 days) (verified). Density is a property of the terminal's popularity with
  datarunners, not of the commodity.
- The first days after a patch are 2–4 times denser than a version's average (hubs median 1.10 a day
  against 0.26–0.52 for the hub pairs over `4.10.2`) (verified for the sampled pairs; that the surge
  is patch-driven is inference).
- The `id` span across the sample suggests about 2,000 history rows a day across UEX in the current
  version and about 870 a day since July 2026 (inference: assumes one dense id sequence).

## 3. SCU values and grades

- **Grade order follows SCU.** In every series, of two reports with different grade and SCU, the higher
  grade had the higher SCU: 517 of 517 row pairs (verified).
- **The bands do not fit.** Reading each grade as UEX's band (1 = 0–14 % … 7 = 86–100 %) of a fixed
  maximum, 14 of 26 series with two or more non-zero reports admit no common maximum; with ±7 pp of
  slack, 7 still do not (verified). Examples: TDD Area 18 Laranite (sell) — 1,455 SCU at grade 5
  needs a maximum ≥ 2,049, 519 SCU at grade 3 needs ≤ 1,790; ARC-L1 Laranite (buy) — 6, 21, 23 and
  512 SCU all graded 2. Possible causes: the game's level thresholds differ from UEX's equal bands,
  the maximum differs per shard or changes, or reports are mistyped (inference; not separable here).
- **`scu_sell` is derived.** On the sell side `scu_sell` equals `scu_sell_stock × 7 / status_sell`
  within 1 SCU in 68 of 68 non-zero rows and is 0 whenever the stock is 0 (45 of 45) (verified). It
  is UEX's capacity estimate from one report, not a separate reading.
- **The full-stock value is visible where pairs fill up.** Buy-side reports at grade 7 repeat one
  round number: TDD Area 18 Processed Food 12,000 (5 times), Ruin Station Helium 36,000 (6),
  HDMS-Bezdek Titanium 8,000 (5), Stanton Gateway (Nyx) Methane 18,000 (3) (verified). These are
  likely the terminal's maximum (inference).
- Live grades (`/commodities_prices_all`, 2,587 rows): 434 of 701 buy rows at grade 7, 1,692 of 1,886
  sell rows at grade 1 (verified). Most reports find a buy pair full and a sell pair empty.

## 4. Refill and drain between reports

A buy-side refill is a rise of `scu_buy`; a sell-side "demand freeing up" is a fall of
`scu_sell_stock`. Consecutive reports of every sampled series, current and previous versions
(verified):

| Buy side (65 intervals) | Count | Sell side (95 intervals) | Count |
| --- | --- | --- | --- |
| refill ending at grade 7 (capped) | 9 | drain ending at grade 1 (floored) | 18 |
| refill below grade 7 | 18 | drain above grade 1 | 17 |
| fall (purchases) | 29 | rise (players selling) | 39 |
| unchanged | 9 | unchanged | 21 |

- A capped or floored interval gives a lower bound only; every other interval is net of trades nobody
  reported. No row carries a shard or server, the amount traded, or whether the reporter traded
  (verified: no such field).
- Lower bounds from the sample (verified arithmetic): ArcCorp 045 Quartz 12 → 5,031 SCU in 6.4 h
  (≥ 784 SCU/h); Hickes Research Stims 64 → 887 in 3.3 h (≥ 253 SCU/h); TDD Area 18 Processed Food
  10,089 → 12,000 within 9.5 h; TDD Area 18 Tungsten (sell) 7,716 → 0 within 9.5 h (≥ 815 SCU/h);
  Starlight Service Titanium (sell) 3,717 → 0 within 24.4 h.
- The same pair gives incompatible rates: Levski Agricium went 0 → 87 SCU in 3 minutes, then
  87 → 116 in 6.7 hours (verified); purchases, another shard or a mistyped report can explain it
  (inference).
- The map's 2021 figure of 38 SCU a minute (2,280 SCU/h) would refill most sampled maxima within
  hours, shorter than the median gap; that matches most reports finding buy pairs full and sell pairs
  empty (inference).

## 5. Price against grade

| Measure | Buy side | Sell side |
| --- | --- | --- |
| Series whose price varies | 0 of 17 (82 rows) | 10 of 18 |
| Row pairs with both grade and price different | none | 235: price falls as grade rises in 194, rises in 41 |
| Full record, ten hubs, current version (`/commodities_prices`, 666 rows): price min ≠ max | 3 of 90 rows (spread median 3 %, max 10 %) | 99 of 576 rows (median 5 %, max 25 %) |
| Same record: grade min ≠ max | 23 of 90 | 94 of 576 |

- Buy prices held while stock moved from empty to full: Levski Agricium stayed at 8,379 aUEC/SCU through
  34 days across both versions while stock ranged 0–750 SCU (verified).
- Sell prices step up when the terminal is empty: mean price at grade 1 against grades 2–7 was
  +0.6 % to +12.3 % above (median +3.8 %, 9 series with both) (verified). The step is small against
  the noise of a few reports; a curve per grade cannot be fitted per pair from this data (inference).
- Price changes between patches were not measured.

## 6. The live table against the history

- For 27 of 27 pairs with history, the live row's `date_modified` equals the newest report's
  `date_added`, and 21 carry the same price, SCU and grade (verified).
- The 6 others are sell-side pairs whose newest report had 0 SCU: the live `scu_sell_stock` keeps an
  older non-zero value (CBD Lorville Titanium 642, TDD Area 18 Tungsten 7,716), twice an implausible
  one (Checkmate Agricium 12,481,600; Levski Agricultural Supplies 1,248,160,000) (verified). The grade
  stays at the reported 1.
- `price_*_avg` and `scu_*_avg` equal the mean of the current version's reports only, with zero stock
  left out of the SCU mean; `4.10.2` reports from the last 15 days are not included (verified for TDD
  Area 18 Laranite, Starlight Service Titanium, TDD Area 18 Tungsten).
- `*_users_rows` (trips from user trades) were 0 on all 666 rows of the ten hubs (verified).

## 7. Cost of a full pull

| Way | Requests | Note |
| --- | --- | --- |
| History of every live pair, current version | 2,587 | one per pair; 22 min at the documented 120 a minute, 1.5 % of the daily 172,800; 43 min at one a second |
| The same for each earlier version | about 2,587 each | the pair set differs per version |
| Hourly `/commodities_prices_all` | 24 a day | about 1 MB a response; misses a second report within the hour (4 of 80 gaps) and sell-side zero stock |

- The documented quota belongs to token holders; the quota for token-less calls is undocumented and no
  rate-limit header came back (verified for the 49 calls; inference that the token quota does not
  cover token-less use).
- Terminal counts: 161 `commodity` and 23 `commodity_raw` terminals in `/terminals`, 135 with price rows
  (verified).

## 8. Terms

- Documentation: the history page lists no authorization and no usage condition; the API home says
  "UEX API data comes from community reports" and sets a daily quota of
  "172800 requests (or 120 requests per minute)" (verified,
  [API home](https://uexcorp.space/api/documentation/)).
- API terms, [§5.1–5.11](https://uexcorp.space/about/terms) ("Updated: Jul 2, 2026"): no clause on
  bulk or historical pulls, storage, derived values, redistribution or attribution. §5.6 gives no
  accuracy guarantee; §5.7 lets UEX "terminate or restrict access to the API for any reason",
  misuse included (verified).
- Website terms: §1.4 "personal, non-commercial purposes only"; §3.4 users refrain from "unauthorized
  use of content"; §3.6 "UEX data is estimated and comes with no warranty" (verified). They govern the
  website; whether they reach API data is not stated (inference).
- Not found: any term on publishing values derived from UEX data, such as a restock estimate.

## Verified and inferred

| Claim | Label |
| --- | --- |
| Default history = current game version; `game_version` selects older ones | verified |
| Density, gaps, row counts, 500-row cap far away | verified (sample of 35 pairs, 8 version pulls) |
| `scu_sell` = stock × 7 / grade | verified (68 rows) |
| Grade ordinal with SCU; bands contradict a fixed maximum | verified |
| Buy price constant; sell price slightly higher when empty | verified (sample) |
| Live row = newest report; zero-stock quirk | verified (27 pairs) |
| Shard differences cause part of the contradictions | inference, not checked |
| Grade-7 repeats are the terminal maximum | inference |
| Refill usually completes between reports | inference |
| Hourly polling reconstructs the history | inference from the live-row match |

## Open points for the decision tickets

1. **Restock model.** UEX history supports lower bounds, a per-pair share of reports per grade
   ("usually full", "usually empty") and the full-stock value of pairs that reach grade 7, but no
   rate. A rate needs another source: CIG data (none published), p4kx shop data (`MaxInventory`, no
   rate), or own readings.
2. **Grade meaning.** Whether to map grades through the game's level thresholds
   (`globalshopcommoditydata`, see the map facts) instead of UEX's equal bands; the contradictions
   above are the test case. `scu_sell` is UEX's derived capacity estimate, not an observation.
3. **Price movement.** Buy prices: treat as fixed within a version. Sell prices: a small step when
   the terminal is empty (median +3.8 %); whether a route calculation models it or ignores it.
4. **Member readings.** A restock rate needs readings UEX does not hold: same shard, short repeat
   intervals, the time from empty to full, and the amount the member bought or sold. Whether members
   record these in the Basetool, submit to UEX (`POST /data_submit`, token), or both.
5. **Pull strategy.** Per-pair history (2,587 calls per version) or an hourly snapshot of
   `/commodities_prices_all` (24 calls a day), and how to handle the sell-side zero-stock quirk and
   the implausible stock values.
6. **Terms.** The API terms are silent on storing history and publishing derived values; whether to
   ask UEX support (§5.10) before publishing restock or price estimates built on their data.
7. **Unchecked.** Shard dependence of stock, retention before `4.9`, the token-less quota, and price
   changes between patches.
