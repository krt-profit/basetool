# How long quantum travel and cargo loading take — the model and its inputs

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-15 for [#3453](https://github.com/krt-profit/basetool/issues/3453) (map
> [#3450](https://github.com/krt-profit/basetool/issues/3450)). Nothing here is binding; the
> trade-route spec and its ADRs decide.
>
> **Sources:** the game's own DataForge records and type definitions of build **4.10.0**
> (`sc-alpha-4.10.0`, 4.10.193.11644, built 2026-09-15) as published raw by the data-mining
> repository [`x3nnnonn/StarCitizenDiff`](https://github.com/x3nnnonn/StarCitizenDiff) at commit
> `908b76a0` (cited as `SCD:<path>`), the game's English localization from the same commit, CIG's
> patch notes and posts on Spectrum (read verbatim through the public Spectrum thread API) and
> Comm-Links, and the source code of community tools. Two measurement studies stand in for CIG's
> unpublished quantum-travel code; they are **empirical** and marked so. Secondary sources
> (guides, wikis written by players) are marked **(secondary)** where a claim rests on them.
> Live is 4.10.3 (2026-11-12); no 4.10.1–4.10.3 record dump was available, so values may have
> moved since 4.10.0.

## Summary

- **A quantum jump's in-flight time follows from four drive fields and the distance.** The
  fields are `SCItemQuantumDriveParams.params.driveSpeed` (m/s), `.stageOneAccelRate`,
  `.stageTwoAccelRate` (m/s²) and `.engageSpeed` (m/s). The drive accelerates at `a1` up to
  `engageSpeed`, then its acceleration ramps linearly to `a2` as speed reaches `driveSpeed`,
  cruises, and decelerates as the mirror image; a short jump turns round at half distance. This
  is an empirical model (SC Wiki, measured in 4.10.0), refining a 3.12 study that measured an
  error below ±0.2 s. Closed form for the long case, a bisection for the short case (§2.2).
- **Everything around the jump is less certain than the jump.** Spool-up (`spoolUpTime`, 4–7 s),
  calibration (`calibrationRate`, `min/maxCalibrationRequirement`, distance-dependent by CIG's
  3.2.0 notes) and cooldown (`cooldownTime`, 12–16 s) are in the data, but how they overlap has
  never been measured; obstruction detours, orbital ("spline") jumps and the in-game route
  plotter's waypoint choice are not documented anywhere (§2.4, §2.5).
- **A jump-tunnel crossing has no model, only a CIG figure:** about three minutes end to end for
  Stanton–Pyro (4.0 EPTU notes), plus approach, alignment, tuning and any ATC queue. The tunnel
  record (110 km plus 15 km entrance and 8 km exit, 1300 m/s in-tunnel speed) does not reproduce
  that figure (§3).
- **Take-off, approach and landing have no model in the data.** The data gives the quantum
  drop-out radius per destination (`quantumTravelData.arrivalRadius`, 18–24 km at the stations,
  landing zone, outpost and jump point read) and ship speeds (`IFCSParams.scmSpeed`,
  `maxSpeed`), but pilot, ATC and atmosphere dominate; tools use flat per-stop constants (§4).
- **Auto-load times are in the data:** one global record holds a base of 120 s for loading and
  for unloading plus seconds per box by size (1 SCU: 2 s … 32 SCU: 18 s), unchanged since at
  least May 2025. CIG says the time depends on the number and size of boxes and the kiosk shows
  an estimated loading time, but nowhere states how base and boxes combine; `120 s + Σ boxes` is the
  reading a community tool uses. Nothing depends on place or commodity (§5.1).
- **Manual loading has no model at all**, and the Hull-C cargo-deck rate (`0.024` per SCU) has no
  stated unit (§5.2, §5.3).
- **Existing tools:** sc-trade.tools computes a route time and profit per minute including
  auto-loading, but is closed source; UEX publishes distances only; Erkul has a closed quantum
  calculator; FleetYards and the SC Wiki's data dumper are open and use the older one-ramp model;
  hobby tools fall back to user-set constants (§6).

## 1. A route leg, end to end

The time from buying at stop A to selling at stop B splits into parts that differ greatly in how
well they can be computed:

| Part | Computable from game data? | Where |
|---|---|---|
| Auto-load at A, auto-unload at B | Yes, with one inferred combination rule | §5.1 |
| Manual loading / unloading | No | §5.3 |
| Retrieve ship, take-off, leave atmosphere, landing | No (constants) | §4 |
| Spool, calibrate, align before each jump; cooldown between jumps | Inputs yes, composition no | §2.4 |
| Quantum jump in flight | Yes, empirical model | §2.2 |
| Extra legs around an obstructing body | Inputs partly, routing rule no | §2.5 |
| Jump-tunnel crossing between systems | No (CIG figure only) | §3 |

## 2. Quantum travel

### 2.1 The drive parameters

The legacy quantum drive component `SCItemQuantumDriveParams`
(`SCD:DataCoreTypes/SCItemQuantumDriveParams.xml`) holds `params` and `splineJumpParams` (both of
type `SQuantumDriveParams`), `heatParams`, `quantumFuelRequirement`, `jumpRange` and
`disconnectRange`. `SQuantumDriveParams` (`SCD:DataCoreTypes/SQuantumDriveParams.xml`) holds the
timing fields. Values of three drives in 4.10.0
(`SCD:DataCore/libs/foundry/records/entities/scitem/ships/quantumdrive/qdrv_*_scitem.xml`):

| Field (`params.`) | Beacon (S1) | Huracan (S2) | TS-2 (S3) | Meaning |
|---|---|---|---|---|
| `driveSpeed` | 161 410 900 | 303 537 200 | 509 717 800 | cruise speed, m/s (measured: §2.3) |
| `stageOneAccelRate` | 9 393 000 | 7 927 542 | 5 075 606 | acceleration at start, m/s² (measured) |
| `stageTwoAccelRate` | 22 045 500 | 10 993 710 | 6 349 736 | acceleration on reaching cruise, m/s² (measured) |
| `engageSpeed` | 1 500 | 84 000 000 | 190 000 000 | end of the constant-acceleration stage, m/s (fitted, §2.2) |
| `spoolUpTime` | 4.4 | 7.2 | 6.3 | seconds; drive spool before a jump |
| `cooldownTime` | 11.9 | 16.2 | 13.9 | seconds; after arrival before the next jump |
| `calibrationRate` | 1000 | 1000 | 1000 | calibration progress per second (inferred) |
| `minCalibrationRequirement` / `max…` | 5000 / 10000 | 5000 / 10000 | 5000 / 10000 | calibration needed (inferred) |
| `calibrationDelayInSeconds` | 1.5 | 1.5 | 1.5 | delay before calibration starts (inferred) |

The `splineJumpParams` block of the three drives is much slower (`driveSpeed` 400 000–500 000,
`stageOneAccelRate` 250, `stageTwoAccelRate` 50 000, `engageSpeed` 1500). The data carries no
units; metres and seconds follow from the magnitudes and from the measurements below. The units
and meanings marked "inferred" are readings of the field names only.

p4kx already exports `driveSpeed`, `cooldownTime`, `spoolUpTime`, `stageOneAccelRate`,
`stageTwoAccelRate` and `engageSpeed` of `params` (contract `docs/basetool-catalog.md`, "Item
stats" row `quantumDrive`, at `f43f12f3cd9a`), but not the calibration fields, not
`splineJumpParams` and not the global records below; ticket #3452 owns the detail.

### 2.2 The model of the in-flight time

**Source.** CIG has never published the quantum-travel motion law. Two independent measurement
studies derived it from position-versus-time recordings:

- Erec, *A study on travel time and the underlying physical model of Quantum Drives in Star
  Citizen*, Alpha 3.12, 2021-04-07 (corrigenda to 2022-02-09),
  [gitlab.com/Erecco/a-study-on-quantum-travel-time](https://gitlab.com/Erecco/a-study-on-quantum-travel-time)
  (PDF in the repository). Video-recorded jumps at 60 fps, distances read every 5 s. Finding: the
  acceleration rises **linearly in time** from `stageOneAccelRate` to `stageTwoAccelRate` until
  `driveSpeed` is reached; cruise at `driveSpeed`; deceleration point-symmetric. Closed-form
  times for both the long and the short case (a cubic, solved trigonometrically).
- Wernstrom, [User:Wernstrom/Quantum Travel](https://starcitizen.tools/User:Wernstrom/Quantum_Travel)
  (revision 402610), summarised in
  [Guide:Quantum travel mechanics](https://starcitizen.tools/Guide:Quantum_travel_mechanics)
  (revision 405205), Alpha 4.10.0, September 2026. Positions OCR'd from the `r_displayInfo 4`
  overlay at 1 s intervals. It confirms Erec's ramp, then (change of 2026-09-26) adds a first
  stage: **constant acceleration `a1` until `engageSpeed`, then the linear ramp to `a2` at
  `driveSpeed`.** The refinement was proposed by another player with an LLM and accepted because
  it fitted the S3 TS-2 data much better (**secondary**, empirical).

Both studies found the deceleration to mirror the acceleration in time and distance, and a short
jump (shorter than twice the acceleration distance) to follow the same acceleration curve up to
half the distance and then mirror it. Wernstrom found no effect of the ship (Huracan in a RAFT and
in a Cutlass Red); cargo mass was not tested.

**Algorithm.** With `vmax = driveSpeed`, `a1 = stageOneAccelRate`, `a2 = stageTwoAccelRate`,
`ve = engageSpeed` and the straight-line distance `d` in metres:

```
T1 = ve / a1                                   # constant-acceleration stage
T2 = 2 (vmax - ve) / (a1 + a2)                 # linear-ramp stage
T  = T1 + T2                                   # time to reach vmax
x(t) = 1/2 a1 t^2                                            for t <= T1
x(t) = 1/2 a1 T1^2 + ve s + 1/2 a1 s^2 + (a2 - a1) s^3 / (6 T2),  s = t - T1,  T1 < t <= T
D  = x(T)                                      # distance to reach vmax
if d >= 2 D:  t_qt = 2 T + (d - 2 D) / vmax    # accelerate, cruise, decelerate
else:         t_qt = 2 tau, with x(tau) = d / 2 # x is monotonic: bisection on [0, T]
```

With `ve = 0` this is Erec's model, whose closed forms are

```
long:  t = 4 vmax / (a1 + a2) + d / vmax - 4 vmax (2 a1 + a2) / (3 (a1 + a2)^2)
short: z = 3 (a2 - a1)^2 (a1 + a2)^2 d / (8 a1^3 vmax^2) - 1
       t = 4 a1 vmax / (a2^2 - a1^2) * (2 cos(acos(z) / 3) - 1)            for z <= 1
       t = 4 a1 vmax / (a2^2 - a1^2) * (2 cosh(-ln(z - sqrt(z^2 - 1)) / 3) - 1)  for z > 1
```

The closed short form divides by `a2² − a1²` and fails for a drive with `a1 = a2`; the bisection
has no such case. A guard is needed if a record ever has `engageSpeed ≥ driveSpeed`.

**Worked values** (4.10.0 data, computed for this note with the algorithm above; "one-ramp" is
`ve = 0`):

| Distance | Beacon | Huracan one-ramp / two-stage | TS-2 one-ramp / two-stage |
|---|---|---|---|
| 1 Gm | 17.5 s | 22.0 / 22.5 s | 27.9 / 28.1 s |
| 5 Gm | 42.6 s | 48.0 / 49.6 s | 61.9 / 62.8 s |
| 16 Gm | 110.8 s | 86.5 / 88.7 s | 109.5 / 112.1 s |
| 40 Gm | 259.5 s | 165.6 / 167.7 s | 170.8 / 175.4 s |
| 120 Gm | 755.1 s | 429.2 / 431.3 s | 328.0 / 332.7 s |
| Acceleration phase | 10.3 s, 0.72 Gm | 33.8 s, 4.80 Gm | 93.4 s, 22.8 Gm |

The Huracan acceleration phase matches the wiki's data table (33.8 s, 4.8 Gm). The Beacon's
`engageSpeed` is negligible, so both models agree there.

### 2.3 Accuracy and validity

- **3.12 (Erec):** eight measured jumps from 146 km to 38.5 Gm with Atlas, Beacon and Bolon
  drives; every error below ±0.2 s, not growing with distance. Valid only for straight-line
  jumps; orbital jumps were not studied. Spool-up is not part of the measured time.
- **4.10.0 (Wernstrom):** validated against Huracan, Colossus (Prospector, 16 Gm) and TS-2
  (Perseus, 120 Gm) profiles; the two-stage version removed most of the TS-2 error. No numeric
  error bound is given; the page calls the times approximate and reports that the server
  occasionally shifts the player's position backwards at the start of deceleration, lengthening
  a jump. The recorded 16 Gm Huracan profile spans about 90 s (0–35 s acceleration, 35–55 s
  cruise, 55–90 s deceleration, read from the page's text); the two-stage model gives 88.7 s.
- **One-ramp versus two-stage** differs by about 2 s (Huracan) to 5 s (TS-2) per jump in 4.10.0
  data; for S1 drives with `engageSpeed` 1500 it is zero.
- **The values change between patches:** the 3.12 Beacon had 253 252 km/s, 610 and 3575 km/s²
  (Erec, table 1); 4.10.0 has 161 410.9 km/s, 9393 and 22 045.5 km/s². CIG reduced QT times
  overall in 3.24.2 and rebalanced speed, acceleration and fuel per grade and class in 4.0
  (Spectrum, *Alpha 4.0 Patch Watch | The Balancing Act*, 2024-11-12). The model's shape held
  across that rebalance (the 4.10.0 measurements); the inputs must come from the current export.

### 2.4 Before and between jumps

- **CIG, Alpha 3.2.0 notes** (Comm-Link 16638, 2018-06-30): a jump needs a calibration time that
  depends on the distance to the destination, and a separate spool time; aligning the nose with
  the destination starts calibration.
- **CIG, 4.0 Patch Watch** (2024-11-12): spool and calibration changes were deferred to a later
  update; CIG describes spool time as the switch from SCM to NAV mode and says it stays mainly
  set by the ship's archetype. The ship entity carries master-mode spool times
  (`SSCItemControllableManagerParams.masterModes[].spoolTime`, e.g. 1 s and 0.2 s on the Cutlass
  Black and Hull C) besides the drive's own `spoolUpTime`; which of them gates a jump was not
  found.
- **Player guide (secondary, marked outdated, 3.12 screenshots):**
  [Guide:Quantum Travel](https://starcitizen.tools/Guide:Quantum_Travel) lists the conditions for
  a jump: fuel, unobstructed waypoint, spool 100 %, calibration 100 %, cooldown complete; spool
  and calibration can run during the cooldown.
- **Not found anywhere:** how `minCalibrationRequirement`…`maxCalibrationRequirement` map to
  distance, whether spool and calibration run in parallel, and how long alignment takes.

A plausible per-leg overhead from the data, as an inference only: before the first jump about
`max(spoolUpTime, calibrationDelayInSeconds + requirement / calibrationRate)` = 6.5–11.5 s plus
alignment; between legs `max(cooldownTime, that overhead)` = 12–16 s for the drives above.

### 2.5 Obstruction, multi-leg travel and orbital jumps

- **Straight lines only.** Quantum jumps are straight; an obstructed route needs several jumps
  (Guide:Quantum Travel, secondary). The in-game starmap plots such routes with waypoints and
  shows a "time en route"; how it picks waypoints is not documented.
- **Obstruction geometry is per starmap object:** `StarMapObject.quantumTravelData`
  (`StarMapQuantumTravelDataParams`: `obstructionRadius`, `arrivalRadius`,
  `arrivalPointDetectionOffset`, `adoptionRadius`, `subPointRadiusMultiplier`). Values in
  4.10.0: Hurston obstruction 1 008 900, arrival 1 050 000; Lorville arrival 20 000; HDMS-Edmond
  arrival 20 000; Everus Harbor obstruction 8000, arrival 24 000; Stanton jump point obstruction
  10 500, arrival 18 000 (`SCD:DataCore/libs/foundry/records/starmap/pu/...`). Reading
  `obstructionRadius` as the sphere a jump line must not cross and `arrivalRadius` as the
  drop-out distance from the target is an inference from the names. Starmap object types carry
  `validQuantumTravelDestination` (`octfx/ScDataDumper`, `StarMapObject.php`), i.e. which objects
  can be waypoints. Further obstacle shapes exist (`QuantumObstacleParams`) but were not traced.
- **Global limits** (`SCD:.../globalquantumdriveparams/quantumdriveglobalparams.xml`):
  `minimumAltitudeForQuantum` 2000, `maximumAtmosphericPressureForQuantum` 0.4,
  `maxRaycastDist` 9000 — a jump can only start above a planet's dense atmosphere.
- **Orbital ("spline") jumps** curve round the nearby body to near-surface destinations (added in
  3.2.0 per its notes). They use `splineJumpParams`, about 500 km/s. When the game chooses one,
  and its time, is unstudied.
- **Planets rotate**, so a surface location's line of sight from space, and with it whether the
  planet itself obstructs, depends on the time of day (inference; the Basetool's `starmap_object`
  has no motion). Distance changes from rotation are negligible against Gm legs.

A tool can approximate a leg as: if the segment start–target crosses no body's obstruction
sphere, one jump; else route over QT-valid waypoints (orbital markers, Lagrange points) with a
shortest-path search, adding one overhead per extra leg. This is a design idea, not a game rule.

### 2.6 What else changes the inputs

- **Crafted drives** have a higher `driveSpeed` than the base record (Wernstrom, from the
  crafting tooltips: HP, fuel consumption and maximum velocity change; the accelerations appear
  unchanged). A stored hangar loadout has to carry the crafted value, not the item record's.
- **A new quantum system is dormant in 4.10.0:** a second struct `SCItemQuantumDriveParams_NEW`
  with `travelling` (`maxCoastSpeed`, `timeToMaxCoastSpeed`, `stabilizationTime`,
  `minTravelTime`, `stopTime`) and `boosting` (`minSpeed`, `maxSpeed`, `timeToFullSpeed`,
  `maxUseTime`) exists, but no record uses it, and the default game mode has
  `quantumParams.enableQuantumTravelling` and `enableQuantumBoosting` set to `False`
  (`SCD:.../gamemode/sc_default.xml`). If CIG switches to it, the model above is void.
- **Fuel** is a range limit, not a time input, but a refuel stop adds a leg. Since the resource
  network, consumption is per second in the drive's `Travelling` resource state
  (`ItemResourceComponentParams.states[Travelling]` … `QuantumFuel`), with a dynamic override.
  Out of scope here.

## 3. Jump-tunnel crossings

- **Procedure, CIG 4.0 EPTU notes** (Spectrum, 2024-11-14): enter quantum navigation mode, fly to
  about 8 km from the tunnel, align to start tuning, hold to open the jump point, fly in and get
  pulled down the tunnel spline. CIG puts the crossing of the first (Stanton–Pyro) tunnel at
  about three minutes end to end, during which the pilot steers clear of walls and obstacles or
  is ejected.
- **Data** (`SCD:.../jumppoints/`): one global tunnel record for every tunnel —
  `tunnelLength` 110 000, `entranceLength` 15 000, `exitLength` 8000, radius 1000;
  `globaljumppointparams.xml`: `alignmentRange` 15 000, `requiredTuningAmount` 20, opening
  `revealTime` 10, `openingTime` 3, `shipPullInDelayTime` 1.5, `startClosingTime` 70. The S1 jump
  drive template (`SCItemJumpDriveParams`, `jdrv_s01_template.xml`) carries `alignmentRate` 0.33
  and `tuningRate` 0.2; the in-tunnel flight tuning (`jumpdriveflighttuning_*.xml`) has
  `linear.maxSpeed` 1300 m/s for every size, `respoolTime` 3.
- **The data does not reproduce CIG's figure:** 133 km at 1300 m/s is about 100 s, not 180 s; the
  generated path, its speed control and the tuning units are unknown. Nyx tunnels (4.4) were not
  measured anywhere I found. A queue before the jump (ATC clearance) adds an unbounded wait.

A tool can only use a constant per crossing (CIG: about 3 minutes for the tunnel, plus approach
and tuning), ideally measured per jump point.

## 4. Non-quantum segments

- **No game-data model exists** for retrieving a ship (ASOP, ship elevator), ATC clearance,
  hangar doors, flying from the quantum drop-out to the pad, leaving or entering an atmosphere,
  or landing.
- **Inputs that exist:** the drop-out radius above (`arrivalRadius`, 18–24 km at stations and
  landing zones, 1050 km for Hurston itself), ship speeds in `IFCSParams` (`scmSpeed`,
  `boostSpeedForward`, `maxSpeed`; e.g. Hull C 110 / 290 / 890 m/s, Cutlass Black 217 / 465 /
  1150 m/s in the flight controller records), and the quantum start limits of §2.5. A 20 km
  approach at 200–900 m/s is 22–100 s before any landing; atmosphere descent and ascent at
  planets are far longer and not derivable.
- **What tools use:** flat constants. Example (**secondary**, a hobby tool):
  `benefvctr/stanton-trade-optimizer`, `config.default.toml`: `overhead_min = 8` (minutes per
  run for landing, loading, unloading and take-off), `jump_min = 5` per system jump,
  `qt_spool_s = 10`, and a user-calibrated effective QT speed. sc-trade.tools publishes no
  constants.

## 5. Cargo loading and unloading

### 5.1 Auto-load at the commodity kiosk

**What CIG says.** Introduced in 3.24: after buying at the commodity kiosk, a player can have the
goods loaded onto a stored ship for an aUEC fee; loading takes a while, during which the ship
cannot be used, and its progress shows at the ASOP terminal (Spectrum, *[Evocati PTU] Star
Citizen Alpha 3.24.0 PTU.9221646 Patch Notes*, 2024-06-21). The SC Wiki's consolidated 3.24.0
notes add that the time depends on the number and size of the boxes
([Update:Star Citizen Alpha 3.24.0](https://starcitizen.tools/Update:Star_Citizen_Alpha_3.24.0));
the Spectrum build with that wording was not found. The game's UI strings (4.10.0
`global.ini`) include `shop_ui_CommodityKiosk_EstLoadingTime` (Est. Loading Time),
`…EstUnloadingTime` and `…StowShipToAutoLoad` (the ship must be stowed first). So the game
computes an estimate and shows it at the kiosk.

**What the data holds** — one global record,
`SCD:DataCore/libs/foundry/records/globalcargoloadingparams/globalcargoloadingdata.xml`
(type `GlobalCargoLoadingParams`); its loading times are identical in every dump from 2025-05-24
to 4.10.0 (six commits compared). The fees are as of 4.10.0:

| Box size | `autoLoadingBoxSizeLoadingTime` | `…UnloadingTime` | Fee (`autoLoadingBoxSizePrices`) |
|---|---|---|---|
| 1/8, 1/4, 1/2 SCU | 0 | 0 | 0 |
| 1 SCU | 2 | 2 | 30 |
| 2 SCU | 3 | 3 | 55 |
| 4 SCU | 5 | 5 | 100 |
| 8 SCU | 8 | 8 | 190 |
| 16 SCU | 12 | 12 | 360 |
| 24 SCU | 15 | 15 | 495 |
| 32 SCU | 18 | 18 | 680 |
| Base | `autoLoadingBaseLoadingTime` 120 | `autoLoadingBaseUnloadingTime` 120 | `autoLoadingBasePrice` 150 |

The fees are in `GlobalShopCommodityParams`
(`SCD:.../globalshopparams/globalshopcommoditydata.xml`). Times carry no unit; seconds is the
reading every community parser uses.

**The combination rule is an inference.** No CIG source says how base and per-box values
combine. The natural reading, used by `SubliminalsTV-Projects/sc-overlay`
(`src/hauling-autoload.ts`), is

```
t_autoload = autoLoadingBaseLoadingTime + Σ over boxes autoLoadingBoxSizeLoadingTime[size]
fee        = autoLoadingBasePrice        + Σ over boxes autoLoadingBoxSizePrices[size]
```

Example under that reading: 576 SCU as 18 × 32-SCU boxes take 120 + 324 = 444 s (7.4 min) and
cost 12 390 aUEC; as 576 × 1-SCU boxes, 1272 s (21.2 min) and 17 430 aUEC. Per SCU a 32-SCU box
loads 3.6 times faster than a 1-SCU box. The only outside data point is one player report of
about 28 minutes for about 2000 SCU (LTI Hangar Ironclad guide, May 2026, **secondary**, box mix
not stated); the reading gives 21–35 minutes for 32- to 8-SCU boxes.

**Dependencies.** The record has no place, commodity or ship field, and no second record of the
type exists in the dump: auto-load time depends only on the number of boxes of each size. Place
and ship enter indirectly — the terminal's largest box (`max_container_size`, UEX
`container_sizes`) and the ship grid's largest box set the box mix. Whether a place offers
auto-load at all is a terminal flag (UEX `is_auto_load` / `has_loading_dock`; the UEX API docs
describe a loading dock as an external freight elevator or auto-load area).

### 5.2 Hull-C cargo deck

Since 3.20 a Hull C (spindles) loads at a station's loading area after Cargo Services assigns
one; CIG gave the transfer as roughly five minutes, depending on the size of the load (Spectrum,
*Star Citizen Alpha 3.20.0 LIVE.8701927 Patch Notes*, 2023-09-18). The same global record holds
`cargoDeckLoadingTimePerSCU` = 0.024, unchanged since 2025; its unit is not stated (as seconds,
a full 4608 SCU would take 111 s, short of CIG's five minutes). The record also holds the
loading-area timers (`revokeTimeDelay` 60, `forfeitTimeBuffer` 300), which bound waits, not
loading.

### 5.3 Freight elevator and manual loading

No data and no CIG statement gives a rate. The elevator itself has movement parameters
(`SCItemElevatorParams`: `MaxSpeed`, `EasingDistance`, `OpenWaitTime`, …) but the time is the
player's tractor-beam work. Players report that box count, not SCU, dominates (a player quoted
in `sc-overlay` rates six small boxes as about the same work as six large ones, **secondary**),
and one
guide gives 3–5 minutes for a 150 SCU Hull-C contract (ggwtb.com 4.9 guide, **secondary**).
There is no reliable model; tools either ignore it or use a constant.

## 6. Existing tools

| Tool | Travel time | Loading time | Source | What is known about accuracy |
|---|---|---|---|---|
| sc-trade.tools | Yes: `TradeDto.timeInSeconds`, `profitPerMinute`; distances across several jump points (changelog 10.2.1) | Yes since 11.1.0: loading time always counted, by box size; `useAutoLoading` adds auto-load time and fee | Closed; only API schema and changelog public (`EtienneLamoureux/sc-trade-tools`) | None published; 11.1.1 fixed a wrong route-time calculation |
| UEX | No: `commodities_routes.distance` and `terminals_distances.distance` in Gm, method undocumented | No | API docs only | — |
| Erkul (erkul.games) | Quantum travel calculator; publishes drive data incl. "Engage" | No | Closed | None published |
| SC Wiki | Model, derivation and per-drive tables (§2.2) | No | Wiki pages | Approximate, 4.10.0 measured |
| FleetYards | One-ramp model (`app/frontend/frontend/utils/travelTimes.ts`, GPL-3.0); no spool, no `engageSpeed` | No | Open | Erec's 3.12 model; a few seconds short for 4.10.0 drives with a large `engageSpeed` |
| ScDataDumper (SC Wiki API data) | One-ramp model, `TravelTime10GM` per drive (`src/Formats/ScUnpacked/QuantumDrive.php`) | No | Open | As above |
| Regolith | Nothing found | Nothing found | No public code or docs found | — |
| Hobby tools | Constant QT speed, flat per-run and per-jump minutes (§4) | In the flat overhead | Open | Calibrated by the user |

## 7. Putting it together

```
route_time = Σ stops   [ load_or_unload(stop) + stop_overhead(stop type) ]
           + Σ legs    [ prep(leg) + t_qt(d_leg, drive) ]          # prep: §2.4, t_qt: §2.2
           + Σ crossings tunnel_constant(jump point)                # §3
load_or_unload = 120 + Σ box_seconds  (auto-load, inferred rule)   # §5.1
               | constant / none      (manual)                     # §5.3
```

| Part | Expected error |
|---|---|
| `t_qt` | ±0.2 s measured in 3.12; seconds in 4.10.0 plus server hiccups |
| `prep` and cooldown | unmeasured; roughly 5–15 s per leg |
| Obstruction detours | one unmodelled leg each where the rule guesses wrong |
| Tunnel | CIG's three minutes plus approach, tuning and queue |
| Stop overhead | minutes; pilot, ATC and atmosphere dominate |
| Auto-load | exact if the inferred rule holds; unverified |

Quantum travel itself is the best-known part and, for typical Stanton legs of 5–60 Gm, takes
roughly 0.5–6 minutes; the stop overheads and loading, which are known worst, are of the same
size or larger. A "fastest route" objective therefore rests mostly on the constants.

## Open points for the decision tickets

1. **Which quantum model**: the two-stage 4.10.0 model (needs `engageSpeed`, which p4kx exports)
   or the one-ramp model; both rest on player measurements, not CIG documentation.
2. **Pre-jump and cooldown overhead**: no measurement of how spool, calibration (distance-
   dependent per CIG), alignment and cooldown overlap; choose a constant or measure, and decide
   whether p4kx should export the calibration fields.
3. **Obstruction and waypoints**: the in-game plotter's rule is unknown; decide whether to model
   obstruction (needs `quantumTravelData` and `validQuantumTravelDestination` from p4kx, not in
   the v0.4.2 contract) or to rely on straight-line distances with a detour allowance.
4. **Orbital (spline) jumps** to surface locations: when used and how long; unstudied.
5. **Jump tunnels**: a per-jump-point constant (CIG: about 3 minutes for Stanton–Pyro, 2024);
   Nyx tunnels unmeasured; the tunnel record does not reproduce the figure; ATC queue unbounded.
6. **Stop overheads**: constants per stop type (station hangar, docking port, city, outpost,
   with or without atmosphere), their values, and whether members can adjust them.
7. **Auto-load rule**: verify `120 s + Σ box seconds` against the kiosk's estimated loading time
   for a few box mixes; settle whether loading and unloading at one stop add, and whether the fee
   follows the same rule. p4kx does not export `GlobalCargoLoadingParams` or
   `GlobalShopCommodityParams` yet.
8. **Hull-C cargo deck**: the unit and use of `cargoDeckLoadingTimePerSCU`.
9. **Manual loading**: a per-box constant, or leave it out of a "fastest route" objective.
10. **Crafted drives and stored loadouts**: a crafted drive's `driveSpeed` differs from its record;
    the hangar-loadout hook has to carry it.
11. **Patch drift**: inputs change every major patch, and a dormant replacement quantum system
    (`SCItemQuantumDriveParams_NEW`) would void the model; decide how a patch change re-validates
    the travel-time model.

## Sources

- Game data 4.10.0, `x3nnnonn/StarCitizenDiff` at `908b76a0485036161ba700d369c7d92aca1c847b`:
  `DataCoreTypes/{SCItemQuantumDriveParams,SQuantumDriveParams,SCItemQuantumDriveParams_NEW,
  SQuantumDriveTravelParams,SQuantumDriveBoostParams,QuantumDriveGlobalParams,QuantumParams,
  StarMapQuantumTravelDataParams,GlobalCargoLoadingParams,SAutoLoadingBoxSizeLoadingTime,
  GlobalShopCommodityParams,SCItemJumpDriveParams,SJumpTunnelGenerationParams,
  GlobalJumpPointParams,SMasterModeParams,IFCSParams,SCItemElevatorParams}.xml`; records under
  `DataCore/libs/foundry/records/`: `entities/scitem/ships/quantumdrive/`,
  `entities/scitem/ships/jumpdrive/`, `entities/scitem/ships/controller/`,
  `entities/spaceships/`, `globalcargoloadingparams/`, `globalshopparams/`, `jumppoints/`,
  `globalquantumdriveparams/`, `gamemode/sc_default.xml`, `starmap/pu/`; `Localization/global.ini`;
  `build_manifest.json`.
- CIG: Spectrum patch notes 3.20.0 LIVE.8701927, 3.24.0 PTU.9221646, 3.24.0 LIVE 9296942,
  4.0 EPTU.9418963; *Alpha 4.0 Patch Watch | The Balancing Act* (2024-11-12); Comm-Link 16638
  (Alpha 3.2.0) and 19985 (*Alpha 3.24: Cargo Empires*) via api.star-citizen.wiki.
- Measurements: Erec (GitLab `Erecco/a-study-on-quantum-travel-time`, PDF, Alpha 3.12);
  Wernstrom (starcitizen.tools `User:Wernstrom/Quantum_Travel`, `Guide:Quantum_travel_mechanics`,
  `Guide:Quantum_drive_performance`, Alpha 4.10.0).
- Tools: `fleetyards/fleetyards` (`travelTimes.ts`, `travelTimes.spec.ts`);
  `octfx/ScDataDumper` (`QuantumDrive.php`, `JumpPerformance.php`, `StarMapObject.php`);
  `hutli/scal` (`quantum_drives/utils.py`); `SubliminalsTV-Projects/sc-overlay`
  (`src/hauling-autoload.ts`); `voidput/sc-gamedata-extractor` (`src/domains/cargo.ts`);
  `EtienneLamoureux/sc-trade-tools` (`CHANGELOG.md`, `api-docs/11.5.0.json`); UEX API docs
  `get_commodities_routes`, `get_terminals_distances`; `benefvctr/stanton-trade-optimizer`
  (`config.default.toml`); `p4kx-org/p4kx` (`docs/basetool-catalog.md` at `f43f12f3cd9a`).
- Secondary: starcitizen.tools `Guide:Quantum_Travel`, `Quantum_drive`, `Jump_point`; LTI Hangar
  Ironclad cargo guide; ggwtb.com Hull C 4.9 guide.
