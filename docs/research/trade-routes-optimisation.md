# Algorithms and existing tools for multi-stop trade routes

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-15 for the wayfinder research ticket #3454 of the map #3450 (trade-route tool). Nothing
> here is binding; engine, library and feature decisions belong to the grilling tickets.
>
> **Question:** which optimisation approaches fit a multi-stop trade-route engine (ordered stops,
> per-stop buy and sell under capacity, budget, stock and demand, cargo carried across stops;
> profit, profit per hour or duration; forced and excluded stops, round trips, convoys, several
> alternatives, recomputing with fixed stops), which JVM libraries could carry it, and what do the
> existing Star Citizen trade tools offer today?

Sources are primary: the papers (every DOI checked against Crossref), the solvers' repositories,
release metadata on Maven Central and GitHub, solver documentation, the tools' own pages, APIs and
changelogs, and this repository at `origin/main` 732fd614f. **Run-time figures marked
"estimate" are mine**, derived from the stated sizes; none was measured. Where a source was silent
or could not be read, the note says so.

## 1. Short answer

- The full problem generalises the orienteering problem and a many-to-many pickup-and-delivery
  problem, so it is NP-hard ([Golden 1987][golden87], [Berbeglia 2007][berbeglia07]). The data is
  small and sparse, though: 161 commodity terminals, 701 buy offers, 1,886 sell offers and 4,258
  ordered terminal pairs with at least one profitable commodity (UEX snapshot, §2.1).
- **With the stop order fixed, the cargo plan is easy**: a min-cost flow (cargo carried across
  stops included), or a small LP once a reinvested budget is added (§2.3, my derivation). The hard
  part is choosing and ordering the stops.
- That suggests "outer search over stop orders, exact inner cargo plan per order". DP over stops is
  exact only for "sell everything at the next stop"; beam search and local search handle cargo
  carried across stops; MILP / CP-SAT is exact but its run time is not predictable enough for an
  interactive request (estimate) and fits as an offline reference and for small re-solves.
- The editing operations of the owner's brief (remove, insert, reorder a stop) are the standard
  local-search moves, and reordering is one inner re-solve whose cargo-plan diff names the
  commodities that can no longer be sold as planned.
- JVM: OR-Tools (Apache-2.0) has the strongest solvers but ships JNI libraries built on glibc
  (`manylinux_2_28`) while the Basetool image is Alpine (musl), pulls ~107 MiB of native jars by
  default, and likely bundles COIN-OR code under EPL-2.0 without a GPL secondary licence (§4.1).
  Timefold (Apache-2.0, pure Java, Spring Boot 4 starter) is a local-search framework with list
  pinning only as a prefix. ojAlgo (MIT, pure Java) suits the inner LP, not the full MILP.
- UEX added multi-stop planning with loop-back on 2026-05-17 (a "Stops" selector from None to 4);
  its public API still returns single-commodity A→B routes only. SC Trade Tools (Java / Spring Boot) offers A→B,
  circular routes and origin→destination itineraries with up to 5 stops, profit or profit over
  time, loading and restock wait times, and an API / MCP server for supporters.
- Neither tool offers, as far as their own pages, changelogs and API specs show: editing a
  generated route, forced intermediate stops, jump-point avoidance, member ratings of danger or
  bugginess, convoys, or org-level planning. That is the "beyond UEX" gap.

## 2. Problem shape

### 2.1 Decision and data scale

The decision is an ordered sequence of `n` terminals (`n` small) and, per stop, how many SCU of
each commodity to buy and to sell, with cargo allowed to stay aboard. Constraints: the hold
capacity `C` on every leg; stock per (commodity, terminal) for buying and demand for selling; the
budget (cash never negative if sales are reinvested along the route); container sizes as a
compatibility filter between terminal and ship; forced and excluded stops, systems and jump points;
optionally start = end.

Scale, from the public UEX API on 2026-11-15 (`/2.0/terminals?type=commodity`,
`/2.0/commodities_prices_all`; the Basetool's `material_price` is synced from the same source):

| Quantity | Value |
|---|---|
| Commodity terminals | 161 (Stanton 117, Pyro 35, Nyx 9) |
| Price rows | 2,587, covering 124 commodities at 135 terminals |
| Buy offers (`price_buy > 0`) | 701 at 129 terminals, 89 commodities |
| Sell offers (`price_sell > 0`) | 1,886 at 109 terminals |
| Profitable (commodity, buy terminal, sell terminal) triples | 8,026 |
| Ordered terminal pairs with ≥ 1 profitable commodity | 4,258 of 25,921 (161²) |
| Commodities bought per buying terminal | average 5.4, maximum 22 |
| Commodities sold per selling terminal | average 17.3, maximum 89 |

"Profitable" means the same commodity sells for more at another terminal than it costs to buy; the
counts ignore stock, reachability and data age. The instance is smaller than the ticket's working
assumption (150–300 terminals, 200 commodities).

### 2.2 Known problem classes

| Class | Shared with the trade route | Differs | Source |
|---|---|---|---|
| Orienteering problem (OP) | choose a subset of places and an order, maximise collected prize under a time budget; NP-hard | a stop's prize is fixed there; here it depends on what is aboard and where it is sold | [Golden 1987][golden87], [survey][op-survey] |
| TSPs with profits | three shapes: OP (max profit, time bound), prize-collecting TSP (min cost, profit quota), profitable tour (profit − cost) | map to "max profit", "shortest duration with a profit floor" and "profit minus time cost" | [Feillet 2005][feillet05], [Balas 1989][balas89] |
| Team orienteering | several vehicles, one tour each | a convoy whose ships split up | [Chao 1996][chao96] |
| Many-to-many pickup and delivery | a commodity picked up at several places, delivered to several, capacity shared along the route | the cargo-carried-across-stops part | [Berbeglia 2007][berbeglia07] |
| Selective / profitable PDP | pickups and deliveries optional, profit-driven; multi-vehicle variant | pairs are given there; here the pairing is part of the decision | [Ting 2013][ting13], [Gansterer 2017][gansterer17] |
| Traveling purchaser problem | tour through markets, choose where to buy which product, supply limits | the buy half only | [Manerba 2017][manerba17] |
| Travelling thief problem | tour and knapsack interdependent (load slows travel) | analogue of loading time per SCU feeding back into leg time | [Bonyadi 2013][bonyadi13], [Polyakovskiy 2014][polyakovskiy14] |
| Bounded / two-constraint knapsack | one leg with capacity and budget | — | [Kellerer 2004][kellerer04] |
| Interval scheduling on k machines | weighted intervals on `k` identical machines solved as min-cost flow | the fixed-order cargo plan (§2.3) | [Arkin 1987][arkin87] |
| Unsplittable flow on a path | the same with indivisible lots of different size: NP-hard | only if lots must not be split | [Bonsma 2014][bonsma14] |
| Fractional programming | ratio objective | profit per hour, solved as a sequence of `profit − λ·time` problems | [Dinkelbach 1967][dinkelbach67] |
| Arc routing | — | services edges (streets), not nodes; trade happens at terminals, legs are only travel; no fit beyond shortest paths for travel time | [Corberán 2015][arc15] |

The OP is NP-hard ([Golden 1987][golden87]); branch-and-cut solved OP instances "involving up to
500 nodes" to optimality ([Fischetti 1998][fischetti98], abstract). No polynomial exact algorithm
should be expected for the full problem; what helps is that `n` is small and the pair graph sparse.

### 2.3 The fixed-order cargo plan is a min-cost flow (derivation)

This is my own derivation, not taken from a source; the prototype should confirm it. With the order
of stops `1..n` fixed and a constant price per SCU:

- chain nodes `1..n`, one per stop; chain arc `k → k+1` with capacity `C` and cost 0 (empty hold);
- for each commodity `c` sold to the player at stop `a`: arc `chain a → B(c,a)`, capacity = stock,
  cost = buy price;
- for each commodity `c` bought from the player at stop `b`: arc `S(c,b) → chain b`, capacity =
  demand, cost = − sell price;
- arcs `B(c,a) → S(c,b)` for every `b > a`, uncapacitated, cost 0;
- send `C` units from `chain 1` to `chain n` at minimum cost.

Every cut between stops `k` and `k+1` carries exactly `C` units, as empty space or as cargo bought
at or before `k` and sold after `k`, so the hold is never overfull and cargo may stay aboard for any
number of stops. It is [Arkin & Silverberg's][arkin87] interval-scheduling flow with stock and
demand as arc capacities. Network matrices are totally unimodular, so whole-SCU quantities come
for free. For `n = 8` and this data the network has a few hundred arcs.

What changes it:

- **Budget with reinvestment** (cash ≥ 0 after every stop) is one side constraint per stop: a
  plain LP, still small, no longer guaranteed integral (round to SCU).
- **Container sizes** stay a compatibility filter on arcs (terminal maximum container size versus
  ship); real grid packing is a different problem (UEX links SC-Cargo for it).
- **Prices that move with the quantity traded** make arc costs non-linear; whether the engine
  models that is open (§6).
- **Loading time per SCU** (for profit per hour) adds a linear time term per unit and stays linear
  inside one Dinkelbach step.

## 3. Approaches

### 3.1 Comparison

| Approach | Cargo across stops | Optimality | Several alternatives | Fixed stops, editing | Run time here (estimate) |
|---|---|---|---|---|---|
| MILP, position-indexed (§3.2) | native (inventory balance) | exact, given time | solution pool of the solver, or re-solve with a no-good cut on the previous stop sequence | fix `x[k,t] = 1`; with most stops fixed only a few binaries remain | seconds to minutes for good solutions; proof of optimality unpredictable |
| CP-SAT, same model with integer SCU | native | exact, given time | keeps a top-`n` pool (`solution_pool_size`, default 3, "mainly" used as the LNS base); `enumerate_all_solutions` only for models **without** an objective ([sat_parameters.proto][cpsat-params] lines 1416–1425, 1444–1454, 1488–1491); otherwise re-solve with no-good cuts | fix variables, or hints with `fix_variables_to_their_hinted_value` (lines 1205–1207); `max_time_in_seconds` limit (line 331) | as MILP; anytime, so a time limit returns the best found |
| DP over stops, per-leg knapsack (§3.3) | **no** (sell everything at the next stop) | exact for that restriction with a non-binding budget | `k`-best per state, or Yen / Eppstein on the layered graph | restrict a layer to the fixed terminal | milliseconds |
| Beam search, exact inner plan (§3.4) | yes, through the inner flow | heuristic | final beam after de-duplication | constrain layers; insert = evaluate all terminals at one position | 0.2–20 s single-threaded for 20,000 inner solves; needs pre-filtering, to be measured |
| Local search / metaheuristics (§3.5) | yes, through the inner flow | heuristic, anytime | elite pool of distinct routes | freeze user-fixed stops; moves = edit operations | time-boxed (the limit is the answer time) |

### 3.2 MILP sketch and size

Binary `x[k,t]` (stop `k` is terminal `t`, `Σ_t x[k,t] = 1`, or `≤ 1` for optional stops);
continuous `y[k,c,t]` bought and `z[k,c,t]` sold with `y ≤ stock·x[k,t]`, `z ≤ demand·x[k,t]`;
inventory `I[k,c] = I[k−1,c] + Σ_t y − Σ_t z ≥ 0` and `Σ_c I[k,c] ≤ C`; cash
`cash[k] = cash[k−1] − Σ buy·y + Σ sell·z ≥ 0`; objective sales minus purchases. Travel time
needs leg variables `w[k,i,j] ≥ x[k,i] + x[k+1,j] − 1`. With the snapshot and `n = 8`:

| Part | Count |
|---|---|
| `x` binaries | 8 × 161 = 1,288 |
| `y` (buy offers) | 8 × 701 = 5,608 |
| `z` (sell offers) | 8 × 1,886 = 15,088 |
| `I` (buyable commodities) | 8 × 89 = 712 |
| `w` leg variables, if travel time enters the model | 7 × 161² = 181,447 (fewer after reachability pruning) |

The big-M links `y ≤ stock·x` give a weak LP relaxation and positions are symmetric, so the
estimate is: a good solution within seconds with CP-SAT, SCIP or HiGHS, proof of optimality
possibly much later. Two pieces of evidence on solver strength: in ojAlgo's own MIPLIB benchmark
the pure-Java ojAlgo solved 3 of 13 models with 750–1,000 variables within 300 s while SCIP and
HiGHS solved all 103 test models ([ojAlgo 2026][ojalgo-miplib]); OR-Tools CP-SAT won gold in the
Fixed, Free, Parallel and Local Search categories of the MiniZinc Challenge 2025, Choco silver in
Fixed and bronze in Free ([MiniZinc 2025][minizinc25]). Use: an offline reference to measure a
heuristic engine's gap on sampled instances, and small re-solves when most stops are fixed.

### 3.3 Dynamic programming over stops

Precompute `w(i,j)`, the best one-leg cargo profit for an ordered pair: a bounded knapsack over the
commodities bought at `i` and sold at `j` under capacity and budget (greedy by margin per SCU is
exact when the budget does not bind). Only 4,258 pairs are non-zero. Then
`V[k](j) = max_i V[k−1](i) + w(i,j) − λ·t(i,j)`, about 8 × 4,258 ≈ 34,000 relaxations for `n = 8`.

- `k` alternatives: keep the top `k` per (layer, terminal), cost × `k`; or run Yen's or Eppstein's
  algorithm on the layered graph ([Yen 1971][yen71], [Eppstein 1998][eppstein98]; JGraphT ships
  `YenKShortestPath` and `EppsteinKShortestPath`).
- Round trip: fix the last layer to the start. Forced stop at a given position: restrict that
  layer; forced stop at a free position: one "visited" bit per forced stop (2^f states, f small).
- Limits: no cargo across stops, budget growth across legs needs cash in the state, and forbidding
  repeated terminals needs a visited set. Exact for the brief's A→B and A→B→A route types; a seed
  and a bound for the others.

### 3.4 Beam search with an exact inner plan

Precedent: Trade Dangerous, an Elite Dangerous trade planner (MPL-2.0, Python, pushed 2026-11-02),
at commit d3dc5673e:

- multi-hop search is a beam with frontier width 50 and expansion width 50 per node
  ([`planner/route_common.py`][td-common] lines 98–106);
- each hop's cargo is an exact knapsack under capacity and credits, solved by branch and bound
  whose bound is the smaller of the two single-constraint relaxations ([`planner/cargo.py`][td-cargo]
  lines 111–136, 173–196);
- among chains reaching the same station only the best survives ("per-station coalescing",
  `route_common.py` lines 953–956);
- expansion is credit-optimistic, then a forward pass re-fits each hop against the real running
  budget (`route_common.py` lines 945–951).

Its cargo plan is per hop, bought at one station and sold at the next; carrying cargo across hops
is not modelled (inferred from the per-hop `CargoPlan`; nothing in the planner carries cargo on).

For the Basetool variant each partial order would be scored with the inner flow of §2.3, which
covers cargo across stops. With TD's widths and `n = 8`: 50 × 50 × 8 = 20,000 inner solves per
request; at an estimated 0.01–1 ms per solve on a few hundred arcs that is 0.2–20 s single-threaded,
so successors would be pre-filtered by `w(i,j)`, reachability and the filters before the exact
solve, and evaluated in parallel. **Estimate, to be measured.**

- Alternatives: the final beam, de-duplicated by stop set or order. Diversity is not guaranteed;
  coalescing per terminal is one remedy.
- Fixed stops: constrain layer `k`; excluded terminals leave the candidate set.
- Insert at a position: evaluate the 161 candidates at that position with the inner flow.
- Reorder: one inner solve on the new order; the diff against the old cargo plan lists the
  commodities that can no longer be sold as planned, as the owner's brief asks.

### 3.5 Local search and metaheuristics

Moves: insert a stop, remove a stop, relocate or swap stops, replace a terminal; standard for the
OP and the PDP ([iterated local search for the TOPTW][vansteenwegen09], [ALNS for the
PDPTW][ropke06]). Each move is scored by the inner flow; the search stops at a time limit. The
moves are exactly the editing operations of the brief, so an edit is a local search started from
the member's route with the member-fixed stops frozen. Alternatives come from an elite pool of
distinct routes seen during the search. Timefold Solver packages this machinery: a construction
heuristic phase followed by local search (hill climbing, tabu search, simulated annealing, late
acceptance, great deluge, …) by default ([Timefold: optimisation algorithms][tf-algos]).

### 3.6 Objectives and features

- **Max profit**: every approach.
- **Profit per hour**: a ratio. Beam and local search can score it directly; DP and MILP use
  Dinkelbach's method: maximise `profit − λ·time`, set `λ` to the ratio found, repeat
  ([Dinkelbach 1967][dinkelbach67]). Leg time depends on the cargo when loading time per SCU counts.
- **Shortest duration**: needs a floor (minimum profit, forced stops), or the empty route wins: the
  prize-collecting shape of [Feillet 2005][feillet05].
- **Forced and excluded stops**: layer restrictions or fixed variables. Excluded systems and jump
  points change the travel-time graph (shortest paths between terminals), not the optimiser.
- **Round trips**: start = end.
- **Convoy**: if the ships fly together, one route with the combined hold, the stop constraints of
  every ship (a Hull-C needs a loading dock), the time of the slowest ship and shared stock; if they
  split, a team OP / multi-vehicle profitable PDP with shared stock ([Chao 1996][chao96],
  [Gansterer 2017][gansterer17]). Which one is meant is open.
- **Restock and repeatability**: flying a route repeatedly depletes stock; needs restock rates
  (SC Trade Tools has "allow wait times" and a low-refresh-rate filter, §5.2). A data question for
  the UEX ticket.

## 4. JVM libraries

Activity is the last push to the default branch on 2026-11-15; jar size is the main jar on Maven
Central. The Basetool is GPL-3.0-only and its shipped modules pass the Licensee gate of
[ADR-0197](../adr/0197-shipped-dependencies-pass-a-gpl-compatible-licence-gate-and-are-listed-on-a-public-page.md),
which allows Apache-2.0, BSD-2/3-Clause, CC0-1.0, GPL-2.0-with-classpath-exception, LGPL-2.1-only,
MIT, MIT-0 and MPL-2.0 (`OssLicensePolicy.kt` lines 11–19), not EPL-2.0 on its own.

| Library | What | Licence (repo) | Latest release | Activity | Jar | Native | Fit |
|---|---|---|---|---|---|---|---|
| [OR-Tools][ortools] Java | CP-SAT; routing with optional visits by penalty ([dropping visits][ortools-penalties]) and pickup-delivery; linear solvers GLOP, PDLP, SCIP, HiGHS, CBC/CLP | Apache-2.0 | v9.15 (`9.15.6755`), 2026-01-12; v9.14 2025-06-19; v10.0.0 release tooling in progress ([#5526][ortools-5526]) | 2026-11-15 | `ortools-java` 2.24 MiB plus native jars: linux-x86-64 25.2, linux-aarch64 23.4, win32-x86-64 20.1, darwin-x86-64 20.0, darwin-aarch64 18.3 MiB | yes, JNI (§4.1) | strongest solvers; native cost |
| [Timefold Solver][timefold] | local-search planning engine; list variables for routing | Apache-2.0 (Community); commercial edition adds nearby selection, multithreaded solving, partitioned search, Recommendation API, score analysis ([editions][tf-editions]) | 2.8.0, 2026-11-03 (also 1.35.0, 2026-10-15) | 2026-11-14 | core 3.62 MiB; deps gizmo2, micrometer-core, jaxb-runtime, jspecify | no; Java 21+ ([2.0.0 notes][tf-200]) | Spring Boot starter 2.8.0 builds against Spring Boot 4.1.1 (Basetool: 4.1.2); see below |
| [ojAlgo][ojalgo] | LP, QP, MIP, "pure Java with zero dependencies" | MIT | 58.0.0, 2026-11-11 | 2026-11-15 | 2.87 MiB, no deps | no | inner LP and small MIPs; not the full MILP (§3.2) |
| [Choco Solver][choco] | CP solver | BSD-3-Clause | 6.0.2, 2026-10-06 | 2026-11-11 | 2.12 MiB; deps automaton, **jgrapht-core**, sizeof | no | a CP model is possible; no advantage over CP-SAT for a linear profit objective |
| [JGraphT][jgrapht] | graphs: shortest and k-shortest paths (Yen, Eppstein) | EPL-2.0 or LGPL-2.1 (POM) | 1.5.3 on Maven Central, 2026-04-11 | 2026-11-09 | 1.25 MiB; deps jheaps, apfloat | no | travel-time graph and k-best DP; the gate needs a URL alias for the POM's LGPL URL, and the policy holds `LGPL-2.1-only` |
| [Jenetics][jenetics] | genetic algorithms | Apache-2.0 | 9.1.0, 2026-09-02 | 2026-10-20 | 0.40 MiB, no deps | no | generic GA, no routing support |
| [jMetal][jmetal] | multi-objective metaheuristics | MIT | `jmetal-core` 7.11, 2026-11-05 | 2026-11-14 | 2.72 MiB; the POM declares junit, mockito, assertj, weka, xchart as non-test deps | no | would put test libraries on the runtime classpath |
| [OptaPlanner][optaplanner] | Timefold's predecessor | Apache-2.0 | 10.2.0, 2026-04-28 | repository archived | — | no | not a candidate |

Timefold specifics ([modelling docs][tf-modeling]): `@PlanningPin` freezes a whole entity;
`@PlanningPinToIndex` pins only a **prefix** of a list variable ("indexes less than
firstUnpinnedIndex"); `allowsUnassignedValues` makes visits optional; "each planning value is
assigned to exactly one planning entity", so a terminal visited twice needs two visit objects.
A fixed stop in the middle of a route needs a custom constraint or move filter, and the cargo
plan would be computed inside a custom score calculator.

Own code: the DP, the beam and the inner min-cost flow are each a few hundred lines; the core needs
no library, and ojAlgo covers the LP variant with a reinvested budget.

### 4.1 Native-library implications of OR-Tools

- **libc.** The runtime image is `eclipse-temurin:25-jre-alpine` (`docker/app/Dockerfile` line 56),
  i.e. musl. OR-Tools builds its Linux delivery, Java included, in
  `quay.io/pypa/manylinux_2_28_x86_64` ([`tools/release/amd64.Dockerfile`][ortools-amd64] lines 5
  and 74), i.e. glibc. Inference, not tested: the Maven native library will not load on the Alpine
  image as it is; the options are a glibc base image for the backend, a compatibility layer, or an
  own musl build. OR-Tools has open issues on Alpine Java builds ([#5526][ortools-5526],
  [#5528][ortools-5528], [#5429][ortools-5429]); its install page lists "Alpine Edge" among tested
  systems ([Java on Linux][ortools-install]).
- **Extraction.** The loader copies the library to `Files.createTempDirectory("ortools-java")` and
  calls `System.load` ([`Loader.java`][ortools-loader] lines 69 and 129): the container needs a
  writable temp directory.
- **JNI on Java 25.** [JEP 472][jep472] (JDK 24) warns on JNI use unless native access is enabled
  (`--enable-native-access=ALL-UNNAMED`, or `Enable-Native-Access: ALL-UNNAMED` in an executable
  JAR's manifest). The build sets the flag for tests and `bootRun`
  (`basetool.java-conventions.gradle.kts` line 72, `basetool.spring-app-conventions.gradle.kts`
  line 8); the image `ENTRYPOINT` (`Dockerfile` line 134) does not.
- **Size and verification.** `ortools-java` 9.15.6755 declares all five platform jars as runtime
  dependencies (≈ 107 MiB) plus `protobuf-java` 4.33.1 and `jna-platform` 5.14.0; unused platforms
  would be excluded, and every remaining jar needs its checksum in
  `gradle/verification-metadata.xml` (ADR-0208).
- **Licences inside the native library.** The Java delivery runs CMake with defaults
  (`build_delivery_linux.sh` lines 177–178), under which `USE_COINOR` is ON (`CMakeLists.txt`
  line 222) and wrapper builds force `BUILD_DEPS` ON (lines 162–167). Inference, not verified by
  inspecting the jar: the native library likely contains COIN-OR Clp/Cbc, whose
  [LICENSE][cbc-license] is EPL-2.0 without the Exhibit A secondary-licence notice, the class of
  licence ADR-0197 excluded AspectJ for. Licensee reads only the POM (Apache-2.0) and would pass.

## 5. Existing Star Citizen trade tools

### 5.1 UEX

Web page [Trade → Routes][uex-routes], read without sign-in on 2026-11-15 (game version shown:
4.10.3); labels as rendered:

- **Inputs:** Vehicle, Investment (UEC), SCU, Commodity, Distance (Gm), Container Size (1–32 SCU),
  Faction, Orbit / Terminal / Star System for start and end. Several star systems at once since
  2026-11-08 (release note 241).
- **Stops:** "None, 1, 2, 3, 4". Release note 214 (2026-05-17): "Trade Routes: now with multi-stop
  planning. Loop-back support included." How a multi-stop result is presented was not verified
  (the default view lists A→B rows).
- **Toggles:** Loop, Load Dock, Docking Port, Autoload, Legal, N.Q.A., Monitored, Space, Ground,
  Refuel, Predicted, Player, Averages, Favorite. The page does not explain them.
- **Result columns:** commodity, Buy At (with "Set as start location"), container sizes,
  inventory, SCU / SCU-U / SCU-C, investment, Sell at, Gm, ETA, ROI, Income, a loop-route count,
  and a link to routes departing from the destination. How ETA is computed is not stated.
- **Route detail** ([example][uex-route-detail]): one commodity A→B with profit, ROI, investment,
  distance, ETA, per-terminal inventory level, facilities, the datarunners and report age, a
  "User Activity" level, a "Share" heading, and the note that all values are estimates from
  datarunner reports.
- **API** ([`commodities_routes`][uex-api-routes]): "common routes calculated based on data
  reports", one commodity and one origin–destination pair per row with prices, user-trade
  averages, inventory status, `investment`, `profit`, `distance` (Gm), `score`, container sizes
  and facility flags; at most 500 rows, updated hourly, cached 30 minutes. No multi-stop route in
  the API. [`terminals_distances`][uex-api-dist] estimates terminal-to-terminal distances in Gm
  (method not stated).

### 5.2 SC Trade Tools

From its [README][sctt-readme], [CHANGELOG][sctt-changelog] (current version 11.5.1) and the
OpenAPI spec [`api-docs/11.5.0.json`][sctt-api]; the site itself is a single-page app whose content
could not be read directly.

- **Route types:** `/trade-routes` (A→B trades ranked by profit or profit over time), circular
  routes (`POST /api/tools/circuits/{tradeId}`, "a circular multi-stop trade route … seeded by the
  given trade route ID"), itineraries (`POST /api/tools/itinerary`, origin to destination with
  intermediate stops within `allowableDetour` 0–100 % of the direct travel time); `maxStops` 1–5.
  Alternatives for a trade: `GET /api/graphs/shop/edges/{id}/alternatives`.
- **Filters** (`TradeFormDto`): white- or blacklists of locations (individual shops), location
  types, factions and commodities / commodity types; minimum security level; supported box size;
  avoid hidden locations; minimum inventory; investment; ship; `profitType` `time` or `pure`;
  `allowWaitTimes` (restock waits); `useAutoLoading` (loading time and port fees); smart filters
  (drop low-inventory listings); starting location.
- **Travel time:** "travel times and routes are computed using in-game starmap data" (README);
  loading times with box sizes since 11.1.0; distances through several jump points since 10.2.1.
- **Saving and sharing:** user preferences saved between visits (1.4.2), bookmarkable links
  (2.0.0), shortened result URLs (10.2.0).
- **Data:** crowdsourced through the SC Trade Companion app, shared both ways with UEX (changelog).
- **API:** REST and an MCP server; a token is required and granted to Patreon supporters.
- **Stack:** Java, Spring Boot, Angular, AWS, per the [author's project page][sctt-author], which
  does not name the optimisation algorithm ("fancy optimization algorithms").

### 5.3 Other tools

- **[Gallog trading][gallog]:** Stanton trade-route calculator with "Max Stops" 1–6 under "Stops to
  Return", "Exclude Tradeports", inventory limits, refresh rates, hidden locations, QT distance and
  time. Pyro is marked "In Works" on its starmap; whether it is maintained is unclear.
- **[TradeIn.Space][tis]:** v2.2.1 on game data 3.18.0; route categories (Highest UEC/m, Best
  Profit %, Most Profit Per-Run, Fastest, Convenient, Long Haul) and a "Saved" section. Its data
  is several game versions old, so it is not maintained for the current game; source on Bitbucket.
- **[FleetYards][fleetyards] (GPL-3.0):** mirrors UEX `commodities_routes` per commodity
  ([`app/lib/uex/trade_route_syncer.rb`][fy-syncer]); single hop.
- **Hauling-contract planners** (AstroCargo route planner, SC Hauling Tools, Star Citizen Hauler):
  order pickups and drop-offs of missions; out of scope per the map.

Found through the Star Citizen Wiki's [resources megalist][megalist] (a secondary list, used only
for discovery).

### 5.4 Baseline against the owner's brief

| Feature | UEX | SC Trade Tools |
|---|---|---|
| A→B, one commodity | yes | yes |
| Several commodities on one leg | not found (rows and API records are per commodity) | not found (alternatives are offered per trade) |
| Round trip / loop | Loop toggle, loop-back since 2026-05-17 | circular routes |
| More than two stops | "Stops" None to 4 since 2026-05-17 | up to 5; itinerary with detour limit |
| Cargo kept aboard across stops | not documented | not documented |
| System / location-type filters | systems start and end, Space, Ground | location types, factions, security level |
| Dock, auto-load, refuel filters | Load Dock, Docking Port, Autoload, Refuel | box size, auto-loading |
| Exclude a place | not found beyond start / end selection | blacklist of locations |
| Force an intermediate stop | not found | not found (origin and destination only) |
| Exclude a jump point | not found | not found |
| Travel time | ETA and Gm (method not stated) | starmap travel time, loading time, wait times |
| Objective choice | sortable columns (not confirmed) | profit or profit over time |
| Edit a generated route | not found ("Set as start location", "routes departing from") | not found ("Set as new starting location", alternatives) |
| Save | "Favorite" toggle (meaning not stated) | preferences saved |
| Share | route code URL | bookmarkable, shortened URLs |
| Member ratings (danger, bugs) | not found ("User Activity", monitored flag) | not found (security level) |
| Probable availability | Predicted, Averages, Player toggles; SCU-U / SCU-C | smart filters, minimum inventory, low-refresh filter |
| Several ships / org planning | not found | not found |
| API for routes | single hop, public, 500 rows | trades, itineraries, circuits; token |

## 6. Open points for the decision tickets

- **Price model:** constant price per SCU, or a price that moves with the quantity traded. UEX
  delivers point prices and inventory levels; a moving price makes the inner plan non-linear.
- **Budget semantics:** budget only at the start, or sales reinvested along the route (cash ≥ 0 at
  every stop). Decides between pure min-cost flow and LP.
- **Stock and demand:** hard caps from UEX `scu_buy` / `scu_sell_stock`, or soft "probably
  available" limits (ties into the UEX-data ticket).
- **Maximum stops and revisits:** the upper bound for `n` (UEX 4, SC Trade Tools 5, Gallog 6) and
  whether a terminal may appear twice (A→B→A→B).
- **Convoy semantics:** ships fly together (one route, combined hold) or split up (team OP).
- **Answer-time budget:** what an interactive request may take, and whether a slower exact pass
  may run asynchronously. Decides exact versus heuristic.
- **Alternatives:** how many, and whether they must differ in their stop set or only rank next.
- **Native code:** whether a JNI dependency is acceptable at all (glibc base image or own build,
  COIN-OR licence check, ~107 MiB of jars, native-access flag) against pure-Java options.
- **Travel-time model:** profit per hour and duration need it (separate ticket); loading time per
  SCU makes leg time depend on the cargo.
- **Restock rates** for repeatable routes: no source found in UEX data for this note.
- **Not verified here:** how UEX presents multi-stop results; SC Trade Tools' algorithm (not
  published); the run-time estimates of §3, which a prototype benchmark on the real data should
  replace.

## References

[golden87]: https://doi.org/10.1002/1520-6750(198706)34:3%3C307::AID-NAV3220340302%3E3.0.CO;2-D
[op-survey]: https://doi.org/10.1016/j.ejor.2010.03.045
[feillet05]: https://doi.org/10.1287/trsc.1030.0079
[balas89]: https://doi.org/10.1002/net.3230190602
[chao96]: https://doi.org/10.1016/0377-2217(94)00289-4
[berbeglia07]: https://doi.org/10.1007/s11750-007-0009-0
[ting13]: https://doi.org/10.1016/j.ijpe.2012.06.009
[gansterer17]: https://doi.org/10.1007/s00291-016-0454-y
[manerba17]: https://doi.org/10.1016/j.ejor.2016.12.017
[bonyadi13]: https://doi.org/10.1109/CEC.2013.6557681
[polyakovskiy14]: https://doi.org/10.1145/2576768.2598249
[kellerer04]: https://doi.org/10.1007/978-3-540-24777-7
[arkin87]: https://doi.org/10.1016/0166-218X(87)90037-0
[bonsma14]: https://doi.org/10.1137/120868360
[dinkelbach67]: https://doi.org/10.1287/mnsc.13.7.492
[arc15]: https://doi.org/10.1137/1.9781611973679
[fischetti98]: https://doi.org/10.1287/ijoc.10.2.133
[yen71]: https://doi.org/10.1287/mnsc.17.11.712
[eppstein98]: https://doi.org/10.1137/S0097539795290477
[vansteenwegen09]: https://doi.org/10.1016/j.cor.2009.03.008
[ropke06]: https://doi.org/10.1287/trsc.1050.0135
[cpsat-params]: https://github.com/google/or-tools/blob/v9.15/ortools/sat/sat_parameters.proto
[ortools]: https://github.com/google/or-tools
[ortools-penalties]: https://developers.google.com/optimization/routing/penalties
[ortools-5526]: https://github.com/google/or-tools/issues/5526
[ortools-5528]: https://github.com/google/or-tools/issues/5528
[ortools-5429]: https://github.com/google/or-tools/issues/5429
[ortools-install]: https://developers.google.com/optimization/install/java/pkg_linux
[ortools-amd64]: https://github.com/google/or-tools/blob/v9.15/tools/release/amd64.Dockerfile
[ortools-loader]: https://github.com/google/or-tools/blob/v9.15/ortools/java/com/google/ortools/Loader.java
[cbc-license]: https://github.com/coin-or/Cbc/blob/master/LICENSE
[jep472]: https://openjdk.org/jeps/472
[timefold]: https://github.com/TimefoldAI/timefold-solver
[tf-editions]: https://docs.timefold.ai/timefold-solver/latest/commercial-editions/commercial-editions
[tf-200]: https://github.com/TimefoldAI/timefold-solver/releases/tag/v2.0.0
[tf-modeling]: https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems
[tf-algos]: https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview
[ojalgo]: https://www.ojalgo.org/
[ojalgo-miplib]: https://www.ojalgo.org/2026/09/mip-benchmark-miplib/
[minizinc25]: https://www.minizinc.org/challenge/2025/results/
[choco]: https://github.com/chocoteam/choco-solver
[jgrapht]: https://github.com/jgrapht/jgrapht
[jenetics]: https://github.com/jenetics/jenetics
[jmetal]: https://github.com/jMetal/jMetal
[optaplanner]: https://github.com/apache/incubator-kie-optaplanner
[td-common]: https://github.com/eyeonus/Trade-Dangerous/blob/d3dc5673e72380ee365c8db3db2b06b834d65a34/tradedangerous/planner/route_common.py
[td-cargo]: https://github.com/eyeonus/Trade-Dangerous/blob/d3dc5673e72380ee365c8db3db2b06b834d65a34/tradedangerous/planner/cargo.py
[uex-routes]: https://uexcorp.space/routes
[uex-route-detail]: https://uexcorp.space/trade/route?code=58025cec
[uex-api-routes]: https://uexcorp.space/api/documentation/id/get_commodities_routes/
[uex-api-dist]: https://uexcorp.space/api/documentation/id/get_terminals_distances/
[sctt-readme]: https://github.com/EtienneLamoureux/sc-trade-tools/blob/master/README.md
[sctt-changelog]: https://github.com/EtienneLamoureux/sc-trade-tools/blob/master/CHANGELOG.md
[sctt-api]: https://github.com/EtienneLamoureux/sc-trade-tools/blob/master/api-docs/11.5.0.json
[sctt-author]: https://www.etiennelamoureux.com/projects/sc-trade-tools
[gallog]: https://www.gallog.co/trading
[tis]: https://tradein.space/
[fleetyards]: https://github.com/fleetyards/fleetyards
[fy-syncer]: https://github.com/fleetyards/fleetyards/blob/main/app/lib/uex/trade_route_syncer.rb
[megalist]: https://starcitizen.tools/Star_Citizen_resources_megalist
