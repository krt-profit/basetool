# Refinery Screenshot Import — Master Implementation Plan

> **Doc type:** Historical plan — frozen after implementation (2026-06-10). Do not edit to track new changes.
> Current behaviour: the living spec [`docs/specs/refinery-screenshot-import.md`](specs/refinery-screenshot-import.md) (`REQ-REFINERY-001…016`), [ADR-0007](adr/0007-client-side-vlm-screenshot-extraction.md) / [ADR-0008](adr/0008-refinery-extract-json-contract.md), and — for the desktop tool — the `basetool-bp-extractor` repo (its `CLAUDE.md` guardrails, `docs/refinery-extractor/PHASE0_FINDINGS.md` frozen strategy and test suite).
>
> **Status:** **v1 shipped 2026-06-10.** Phase 0 (extractor PR #4), Phase 1 (basetool #515), Phase 2 (basetool #518) and Phase 3 (extractor PR #5) are merged; Phases 4 (#437) and 5 (#438) remain **deferred by owner decision** and are governed by their GitHub issues, not this document.
> **GitHub epic:** [krt-profit/basetool#439](https://github.com/krt-profit/basetool/issues/439) · **Sub-issues:** #433 (Phase 0), #434 (Phase 1), #435 (Phase 2), #436 (Phase 3), #437 (Phase 4 — deferred), #438 (Phase 5 — deferred).
> **Last updated:** 2026-06-10 — plan-review amendments applied (matching algorithm, stitch/dedupe key, quoted/un-quoted panel state, classical-CV Locate, derived confidence, model pins incl. June-2026 challengers, REQ/ADR process gates). See §3.1 for the amendment summary. No resolved decision from §3 was re-opened.
> **Example screenshots:** the owner's example refinery-order SETUP screenshots are at <https://nc.greluc.me/s/bbzFYL4PT4jkBKK> — the source material for the Phase 0 golden set and the test fixtures of Phases 1–3.
> **Design (binding):** the extractor GUI **and** the frontend review surface follow the Claude Design spec in [`docs/DESIGN_SC_EXTRACTOR.md`](DESIGN_SC_EXTRACTOR.md) — canonical prototype/bundle at <https://api.anthropic.com/v1/design/h/mM6v29vZctN6kiBsF62_eA>, with an **offline-capable mirror** vendored as the zip [`docs/design/basetool-sc-extractor.offline.zip`](design/basetool-sc-extractor.offline.zip) (extract and open the HTML in any browser, no network). Frozen UI decisions: Top-Tabs navigation, comfortable density, honeycomb texture on, confidence as percent + status dot, custom KRT title bar, DE default with full EN parity. Build the rebuilt tool **screen-for-screen** against the prototype.

---

## 0. How to use this document (read first if you are the implementing AI)

This document is written to be executed by an AI coding agent, one phase per session.

1. **Pick exactly one phase** (the owner tells you which). Read this whole document once, then work only that phase's section plus the cross-cutting rules in §8.
2. **Before touching code, read the target repo's `CLAUDE.md`** (basetool) or `CLAUDE.md` (basetool-sc-extractor). Those rules **override** anything here if they ever conflict — but flag the conflict in your summary.
3. **Honour the frozen contract in §5 verbatim.** The `RefineryExtract` JSON is the single hand-off between the desktop tool (Phase 3) and the backend (Phase 1). Changing it breaks the other phase. If you believe it must change, stop and raise it with the owner first; do not silently diverge.
4. **Every phase ships with tests** (project hard rule) and must pass the exact build/lint/test commands listed in that phase's "Definition of Done".
5. **Do not commit, branch, or push unless the owner explicitly asks.** "Verify" means build + test, not git operations. When you do commit, every commit needs a DCO `Signed-off-by:` trailer (`git commit -s`) **and** a `Co-Authored-By: Claude <model> <noreply@anthropic.com>` trailer. All Git/GitHub text is English.
6. **Scope discipline:** build only what the phase section lists under "Deliverables". Anything under "Out of scope (this phase)" is deliberately deferred — do not pull it forward.

---

## 1. Goal & user story

**As a** Star Citizen refinery operator in the org, **I want** the basetool to read my in-game **refinery SETUP screenshots** and pre-fill a new Refinery Order — **above all the materials table** (material, quality, input qty, projected yield, refine on/off), plus location, refining method, total cost and processing time — **so that** I do not have to retype a 10–20 row order by hand.

**Acceptance from the user's point of view:**
- I run a small desktop app on my own PC, point it at my screenshot(s), and get a JSON file.
- I open the basetool refinery page, upload that JSON, and the create form is **already filled in**.
- Anything the tool could not read or match is **clearly flagged** so I can complete it by hand.
- I review, fix if needed, and save. **Nothing is saved without my confirmation.**

---

## 2. Hard constraints (non-negotiable)

| #  |                                                                                                          Constraint                                                                                                           |                                                                                                                   Consequence for the design                                                                                                                   |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| C1 | **Only free / open-source tools.** No paid API, no API key, no third-party SaaS.                                                                                                                                              | Vision reading runs through a **local** VLM (Ollama).                                                                                                                                                                                                          |
| C2 | **The production server must not be overloaded.** It is a single Hetzner VM: **4 vCPU / 8 GB RAM / 160 GB disk, no GPU**. The basetool must stay usable and the disk must not fill.                                           | The VM does **no** image handling and **no** inference. It only ingests a small JSON and matches against master data. No screenshot is ever uploaded to or stored on the server.                                                                               |
| C3 | **v1 = manual JSON upload through the frontend only.** There is currently **no** way for the desktop tool to authenticate to and push data into the backend directly.                                                         | The desktop tool writes a JSON **file**; the user uploads it via the existing frontend, exactly like the hangar ship-list import. **No desktop→backend channel in v1** (that is Phase 4, deferred).                                                            |
| C4 | **Screenshots can be any resolution 1080p → 8K, including ultrawide 5K (21:9 / 32:9).**                                                                                                                                       | A fixed-pixel crop is impossible. The desktop pipeline is resolution-agnostic (§9 Phase 3).                                                                                                                                                                    |
| C5 | **Newest/best free VLM via Ollama.**                                                                                                                                                                                          | Default model `qwen3-vl:8b-instruct` (configurable; never a `-thinking` variant), Phase 0 bake-off vs `qwen3.5:9b` + `gemma4:12b`; low-VRAM/CPU fallback identified in Phase 0 (candidates: `glm-ocr` two-stage, `qwen3-vl:4b-instruct`, `gemma4:e4b-it-qat`). |
| C6 | **Client-side resource safety.** Running the VLM while SC is active is unsafe (GPU/RAM contention → SC stutter/crash or very slow extraction). The user must be warned of minimum PC requirements and warned when below them. | "Close SC" guidance + SC-process detection + preflight hardware check + throttling (§9 Phase 3, §6 resource safety).                                                                                                                                           |

---

## 3. Resolved design decisions (owner-answered 2026-06-05 — do not re-open)

1. **Desktop tool placement — integrate & rebrand.** Build the refinery extractor **inside the existing `basetool-bp-extractor` repo, rebranded to `basetool-sc-extractor`**. A **central launcher GUI** lets the user choose a workflow: the existing **Blueprint** extraction or the new **Refinery** extraction. The repo's scope discipline widens from "blueprints only" to a focused **multi-workflow SC extractor**. *(Rejected: a separate repo; a shared-core monorepo.)*
2. **Panel types in v1 — SETUP only.** v1 reads only the refinement **SETUP** screen. The **PROCESSING** screen and "update an existing order from a PROCESSING screenshot" are deferred. The contract keeps the `panelType` field for forward-compatibility, but the v1 producer emits `SETUP` only and the v1 backend accepts `SETUP` only.
3. **SC client language — English only.** Master-data matching targets the **English** SC client; the golden set is English. No German synonym table in v1. *(Frontend UI i18n DE/EN is a separate, unchanged requirement.)*
4. **Ollama — guided prerequisite, not bundled.** The user installs Ollama themselves (documented). On launch the app verifies Ollama is reachable and the configured model is present, and offers to fetch it via `ollama pull` with a progress indicator. *(Rejected: auto-installing Ollama; doc-only hard fail.)*
5. **GUI design — frozen, build to the prototype.** The desktop rebuild **and** the frontend review surface follow the binding Claude Design spec ([`docs/DESIGN_SC_EXTRACTOR.md`](DESIGN_SC_EXTRACTOR.md) + the prototype at <https://api.anthropic.com/v1/design/h/mM6v29vZctN6kiBsF62_eA>, mirrored **offline** as the vendored zip [`docs/design/basetool-sc-extractor.offline.zip`](design/basetool-sc-extractor.offline.zip)). Locked: **Top-Tabs** navigation (Start · Blueprints · Refinery + per-workflow stepper), **comfortable** density (44 px targets), **honeycomb** texture (~0.32 opacity), **confidence as percent + status dot**, custom **KRT title bar**, **German default with full EN parity**. Reuse the DAS KARTELL / KRT tokens — do not invent any. *(Rejected for v1: rail/launcher nav models; bar/flag confidence styles — kept in the prototype for reference only.)*

### 3.1 Review amendments (2026-06-10 — owner-approved; integrated throughout this document)

A plan review against the **real example screenshots** (inspected at native resolution), **both codebases**, the **pinned design system** and the **June-2026 VLM landscape** produced the following amendments. They are already folded into §§5–10 below; this list is the changelog.

1. **Matching (F1):** master data stores raw ores as `"<Name> (Raw)"` (UEX convention) while the SC screen shows `"<NAME> (ORE)"` — the original step 1 (`findByNameIgnoreCase` on a one-sided normalization) would have missed nearly every ore. §7.3 now normalizes **both sides** (canonical-name folding), restricts candidates to the create-path gate (`type == RAW || isManualRawMaterial`), reuses `MaterialExternalAlias` + `BlueprintFuzzyMatcher`, and returns ranked suggestions.
2. **Stitch/dedupe (F2):** the key `(rawMaterialName, inputQuantity)` was wrong — real orders carry the same material at up to **4 different qualities** (verified in Auftrag 1), and quoted/un-quoted variants of the same row exist. New rule in §9 Phase 3: full-row identity, no same-image merging, scroll-overlap ordering, `rowIndex` provenance.
3. **Un-quoted panel state (F3):** screenshots taken **before GET QUOTE** (YIELD = `--`, no cost/time — 3 of 4 shots in Auftrag 2!) would have produced a silently-empty draft. New: desktop-side detection + warning, `quoted` contract flag, nullable `outputQuantity`, backend issues `UNQUOTED_ROW`/`UNQUOTED_ORDER`.
4. **Locate strategy (F4):** VLM bounding boxes via Ollama are currently unreliable (open llama.cpp grounding bugs; coordinates not mappable back). Locate is now **classical CV first** (template/edge detection on a downscaled frame, crop from the native original), manual-crop fallback kept, VLM bbox demoted to an experimental hint. A **second read region** captures the refinery location from the terminal header (outside the work-order panel; absent on pre-cropped inputs). Pre-cropped panel images (~500 px wide — Aufträge 3/4/6 in the example set) are a recognized input class (`cropMode: "precropped"`, skip Locate, upscale in Normalize).
5. **Confidence semantics:** verbalized VLM self-confidence is measurably uncalibrated for OCR (≈59% abstention accuracy vs ≈68% for two-pass agreement — arXiv 2511.19806). `confidence` is **derived**, never the model's self-estimate. **Phase 0 outcome (frozen 2026-06-10):** the originally planned two-pass agreement was *rejected by the golden-set data* — it caught 0/5 systematic errors across both perturbation configs, and the perturbed pass itself introduced ~2 digit flips per 12 images. The shipped derivation is **deterministic validation only**: numeric plausibility per cell, the REFINE-toggle closed-enum fallback, and the **one-sided** checksum against the panel's `TO REFINE` total (±1 display rounding per row; a shortfall is legal — the table is a scrolling viewport). See `docs/refinery-extractor/PHASE0_FINDINGS.md` §6/§7 in the extractor repo. The §5.4 UI thresholds are unchanged — they just receive a meaningful number.
6. **Model pins (C5):** default `qwen3-vl:8b-instruct` (q4_K_M; `-q8_0` on ≥16 GB — mitigates the documented table-repetition-loop bug); Phase 0 bake-off vs `qwen3.5:9b` and `gemma4:12b`; low-VRAM/CPU fallback bake-off `glm-ocr` (0.9B table specialist, CPU-viable) vs `qwen3-vl:4b-instruct` vs `gemma4:e4b-it-qat`.
7. **Process gates:** every phase PR updates `docs/specs/` in the same PR — a new living spec `docs/specs/refinery-screenshot-import.md` mints `REQ-REFINERY-001+`; ADRs for (a) the client-side-VLM architecture, (b) the cross-repo `RefineryExtract` contract, (c) new UI components. See §8.
8. **Fact corrections:** multipart caps are 64 MB/72 MB (not 2 MB); the plan/design docs are already committed (#445) — only implementation is approval-gated; the extractor's "179/`greluc`" check is a **manual characterization rule**, not an automated test.

---

## 4. Architecture overview

```
┌─ DESKTOP (user's PC, has a GPU) ──────────────────────────────┐
│ basetool-sc-extractor  ──launcher──►  [Blueprint] / [Refinery] │
│                                                                │
│ Refinery workflow:                                             │
│   1. user selects screenshot(s)  (1 selection = 1 order)       │
│   2. preflight: hardware check + "is SC running?" warning      │
│   3. local VLM via Ollama (qwen3-vl:8b-instruct)               │
│      Locate (CV) → Normalize → Read   (per image)              │
│   4. stitch + dedupe rows across the scrolled screenshots      │
│   5. write  RefineryExtract.json   to a user-chosen path       │
└───────────────────────────────┬───────────────────────────────┘
                                 │  user manually uploads the JSON file
                                 │  (browser → frontend; ~KB)
┌─ BROWSER ──────────────────────▼───────────────────────────────┐
│ basetool frontend  /refinery-orders/import  (multipart upload)  │
└───────────────────────────────┬───────────────────────────────┘
                                 │  WebClient relay (JSON body)
┌─ BASETOOL BACKEND (Hetzner VM — light work only) ──────────────▼┐
│ POST /api/v1/refinery-orders/import-extract   (application/json) │
│   • parse + validate RefineryExtract                            │
│   • fuzzy-match Material / Location / RefiningMethod → master    │
│   • derive outputMaterial via Material.refinedMaterial          │
│   • build a best-effort RefineryOrderDto draft + issue list     │
│   ► returns RefineryImportDraftDto (NOT persisted)              │
└───────────────────────────────┬───────────────────────────────┘
                                 │  draft
┌─ BROWSER ──────────────────────▼───────────────────────────────┐
│ existing refinery create form, PRE-FILLED + unmatched flagged   │
│ user reviews / fixes / confirms                                 │
│   ► POST /api/v1/refinery-orders   (the existing create path)   │
└─────────────────────────────────────────────────────────────────┘
```

**Why this satisfies the constraints:** all vision inference is on the player's GPU (C1, C2); the server only parses a tiny JSON and does DB lookups (C2 — negligible CPU/disk, no image storage); the hand-off is a file the user uploads manually (C3).

---

## 5. The frozen contract — `RefineryExtract` JSON (v1)

This is the **single** integration point. Phase 1 (backend) and Phase 3 (desktop) both implement it; Phase 2 (frontend) only relays it. **Freeze it before 1/2/3 run in parallel.**

```jsonc
{
  "schemaVersion": 1,                     // integer; bump on any breaking shape change
  "tool": "basetool-sc-extractor",        // producer name (provenance)
  "toolVersion": "1.4.0",                 // producer version (provenance)
  "model": "qwen3-vl:8b-instruct",        // which VLM produced this (provenance)
  "generatedAt": "2026-06-05T20:00:00Z",  // UTC ISO-8601 instant
  "clientLanguage": "en",                 // v1 always "en" (see decision 3)
  "orders": [                             // v1 producer emits exactly ONE order
    {
      "panelType": "SETUP",               // SETUP | PROCESSING | UNKNOWN; v1 accepts SETUP only
      "quoted": true,                     // false = GET-QUOTE state captured (YIELD/cost/time still "--")
      "layoutConfidence": 0.92,           // 0..1, derived layout-parse confidence
      "rawLocationName": "LEVSKI",         // verbatim, read from the TERMINAL HEADER (outside the panel); nullable — always null on pre-cropped input
      "rawMethodName": "FERRON EXCHANGE",  // verbatim as read; nullable
      "rawInManifestTotal": 32295,        // panel header "IN MANIFEST"; nullable — completeness checksum
      "rawToRefineTotal": 32295,          // panel header "TO REFINE"; nullable
      "expenses": 48928.00,               // aUEC total cost; nullable (null when quoted == false)
      "durationMinutes": 1258,            // processing time in minutes (from "20h 58m"); nullable
      "totalYieldScu": null,              // PROCESSING only; null on SETUP
      "sourceImages": [
        { "name": "frame_213823.png", "width": 3840, "height": 2160, "cropMode": "vlm", "capturedAt": "2026-06-05T19:38:23Z" }  // cropMode: vlm | manual | precropped; capturedAt: UTC capture instant from file metadata (additive v1 field, 2026-06-11, REQ-REFINERY-017) — nullable
      ],
      "goods": [
        {
          "rowIndex": 0,                  // stitched on-screen order, top row = 0
          "rawMaterialName": "LINDINIUM (ORE)", // verbatim, including any "(ORE)"/"(RAW)" suffix; may be UI-truncated (e.g. "UCTION SALVAGE")
          "quality": 618,                 // integer; SC QUALITY column; expected 0..1000 (0 is valid — inert AND some refine-ON salvage rows)
          "inputQuantity": 957,           // integer ≥ 0; SC QTY column
          "outputQuantity": 448,          // integer ≥ 0; SC YIELD column (projected); NULL when quoted == false (read as "--")
          "refine": true,                 // SC REFINE toggle (true=ON, false=OFF/inert)
          "confidence": 0.95,             // 0..1, DERIVED per-row confidence (deterministic validation + header checksum, Phase 0 policy) — never the model's verbalized self-estimate
          "sourceImage": "frame_213823.png"
        }
      ]
    }
  ]
}
```

**Field rules the backend enforces (Phase 1):**
- `schemaVersion` must equal `1` → else `400` with a clear message.
- `orders` must be non-empty. v1 processes `orders[0]`; if `orders.size() > 1`, process the first and add an `INFO` issue `MULTIPLE_ORDERS_TRUNCATED`.
- `panelType` must be `SETUP` → a `PROCESSING`/`UNKNOWN` order yields a `400` in v1 (envelope-level reject via `BadRequestException` + i18n key — see §8 error semantics). Content-level problems always produce a **draft + issues**, never a 400.
- A good with `refine == false` **or** (quoted) `outputQuantity < 1` **or** `inputQuantity < 1` is **not** added to the draft as a `RefineryGoodDto` (it would violate `@Min(1)`); instead it is reported as a `SKIPPED_REFINE_OFF` / `SKIPPED_ZERO_QTY` issue so the user sees it was intentionally dropped.
- **Un-quoted rows** (`outputQuantity == null`, `quoted == false`): reported as `UNQUOTED_ROW` (WARNING) — distinct from `SKIPPED_ZERO_QTY`, because the fix is "re-capture after GET QUOTE", not "row was empty". If **every** row is un-quoted → `UNQUOTED_ORDER` (BLOCKING).
- **Checksum:** when `rawInManifestTotal`/`rawToRefineTotal` are present and the row quantities do not reconcile with them (exact semantics frozen by Phase 0), add `SUM_MISMATCH` (WARNING: "a scrolled screenshot may be missing").
- `quality` outside `0..1000` → clamp is **not** applied; keep the value but add an `OUT_OF_RANGE_QUALITY` warning so the user notices a likely mis-read. Note: the entity column is `NOT NULL` with `@Min(0) @Max(1000)` — an out-of-range value can be **drafted but never saved**; the review form forces correction. A null `quality` defaults to `0` (existing create-form convention).
- Defensive caps on the authenticated endpoint: `orders` `@Size(max = 5)`, `goods` `@Size(max = 100)`, sane `@Size` limits on all raw strings.

---

## 6. Star Citizen refinement SETUP screen — reference

The REFINEMENT CENTER **SETUP** tab (where an order is placed). Columns, left→right:

|         Column         |                                           Meaning                                           |      Contract field       |
|------------------------|---------------------------------------------------------------------------------------------|---------------------------|
| **MATERIALS SELECTED** | the raw ore, e.g. `LINDINIUM (ORE)`; an `INERT MATERIALS` row aggregates non-refinable slag | `goods[].rawMaterialName` |
| **QUALITY**            | composition/quality figure                                                                  | `goods[].quality`         |
| **QTY**                | raw input quantity                                                                          | `goods[].inputQuantity`   |
| **YIELD**              | projected refined output                                                                    | `goods[].outputQuantity`  |
| **REFINE**             | per-row ON/OFF toggle (inert rows are OFF)                                                  | `goods[].refine`          |

Header/footer fields: refinery **location** (e.g. `LEVSKI`), **method** (e.g. `FERRON EXCHANGE`), **TOTAL COST** (aUEC), **PROCESSING TIME** (e.g. `20h 58m`).

**Reference rows** (English client, from the owner's example "Auftrag 1"; full screenshot set at <https://nc.greluc.me/s/bbzFYL4PT4jkBKK>):

```
LINDINIUM (ORE)   QUALITY 618   QTY 957    YIELD 448   REFINE ON
TUNGSTEN (ORE)    QUALITY 530   QTY 1431   YIELD 695   REFINE ON
INERT MATERIALS   QUALITY 0     QTY 5449   YIELD 0     REFINE OFF   ← reported as skipped, not a good
```

One order frequently spans **several scrolled screenshots**; the desktop tool stitches and dedupes the rows into one `order.goods[]` (§9 Phase 3). **Phase 0 verifies the exact column semantics against the golden set** (e.g. whether QUALITY is a 0–1000 grade) and freezes the prompt accordingly.

**Verified golden-set facts (2026-06-10, from the example set inspected at native resolution — these are normal cases, not edge cases):**
- **Duplicate material rows are normal.** Auftrag 1 contains LINDINIUM at **four** different qualities (385/585/618/729), ALUMINUM ×2, BEXALITE (RAW) ×3, TORITE (ORE) ×3 — across its scrolled screenshots. Both `(ORE)` and `(RAW)` suffixes occur. (Backend side verified: `refinery_good` has no unique constraint on `(order, input_material)` — duplicate rows are fully legal.)
- **Un-quoted panel state.** Before the user presses **GET QUOTE**, the YIELD column shows `--` and TOTAL COST / PROCESSING TIME are empty (CTA = "GET QUOTE" instead of "CONFIRM"). In Auftrag 2, **3 of 4 screenshots** are in this state, mixed with a quoted shot of the same order.
- **Scroll overlap ≈ 1 row; capture order ≠ scroll order.** File timestamps do not reflect top-to-bottom order; consecutive shots overlap by about one row. INERT MATERIALS appears mid-list or at the top (Auftrag 4), not only at the bottom.
- **The location (e.g. `LEVSKI`) sits in the terminal header, OUTSIDE the work-order panel** — a panel-only crop loses it; pre-cropped inputs never contain it.
- **Game-UI-truncated names exist:** Auftrag 1 renders a row literally as `UCTION SALVAGE` (≈ clipped "…Construction Salvage"; the V108 migration documents the Construction-* triplet as known external names) with quality 0, refine **ON**, yield > 0.
- **In-world AR markers can overlap the panel** (Auftrag 2: `XPBX 2.3km`-style markers across the table header) — the prompt must instruct the model to ignore them.
- **Pre-cropped panel images are a real input class:** Aufträge 3/4/6 are ~480–520 px wide panel crops (no full frame, no location, digits at the edge of legibility).
- **Header totals as checksum:** `IN MANIFEST` and `TO REFINE` can differ (Auftrag 4: 2564 vs 1948). Hypothesis: Σ QTY of (all? selected?) rows reconciles with these totals — **Phase 0 verifies the exact semantics**; once confirmed they power the `SUM_MISMATCH` completeness check.
- **Golden-set gap:** the example set is 4K-16:9 + tiny pre-crops only — **native 1080p, 1440p and ultrawide captures are still needed** (owner/squadron action; downscaling 4K only approximates lower 16:9 resolutions, and ultrawide changes panel placement).

---

## 7. Master-data mapping & matching (backend, Phase 1)

### 7.1 Order-level mapping

|                 Contract                  |                       basetool field (`RefineryOrderDto`)                       |                                                                                                                                                                      How                                                                                                                                                                      |
|-------------------------------------------|---------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `rawLocationName`                         | `location` (`LocationDto`)                                                      | match a `Location` **that has a refinery** (candidate source: `LocationRepository.findLocationsWithRefinery()` — the `hasRefinery` flag lives on the linked `City`/`SpaceStation`, not on `Location` itself), by normalized name; unresolved/null → `UNRESOLVED_LOCATION` issue, `location` left null (the normal case for pre-cropped input) |
| `rawMethodName`                           | `refiningMethod` (`RefiningMethodDto`)                                          | case-insensitive match against `refining_method.name` (add a `findByNameIgnoreCase` variant — UEX stores title case like "Ferron Exchange"/"Dinyx Solventation", the screen shows uppercase); unresolved → `UNRESOLVED_METHOD` issue                                                                                                          |
| `quoted == false`                         | —                                                                               | all-rows-unquoted → `UNQUOTED_ORDER` (BLOCKING); see §5                                                                                                                                                                                                                                                                                       |
| `rawInManifestTotal` / `rawToRefineTotal` | —                                                                               | checksum only (never copied): mismatch vs Σ row quantities → `SUM_MISMATCH` (WARNING)                                                                                                                                                                                                                                                         |
| `expenses`                                | `expenses` (`Double`, `@PositiveOrZero @DecimalMax 1e9`)                        | copy; null stays null                                                                                                                                                                                                                                                                                                                         |
| `durationMinutes`                         | `durationMinutes` (`Long`, `@PositiveOrZero`)                                   | copy; null stays null                                                                                                                                                                                                                                                                                                                         |
| —                                         | `status` (`String`)                                                             | default to the open/not-started constant in `RefineryOrderStatus` (e.g. `OPEN`)                                                                                                                                                                                                                                                               |
| —                                         | `owner`, `startedAt`, `otherExpenses`, `oreSales`, `mission`, `owningOrgUnitId` | **not** filled from the screenshot — left for the user/normal create flow (see §7.4)                                                                                                                                                                                                                                                          |

### 7.2 Per-good mapping (`RefineryGoodDto`)

|     Contract      |                        DTO field                        |                                                                                                                            How                                                                                                                            |
|-------------------|---------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `rawMaterialName` | `inputMaterial` (`MaterialDto`, `@NotNull`)             | normalize → match a `Material` (§7.3); unresolved → `UNMATCHED_MATERIAL` issue **with ranked `suggestions`**, row kept with null material so the user can pick it                                                                                         |
| (derived)         | `outputMaterial` (`MaterialDto`, nullable)              | `matchedInputMaterial.getRefinedMaterial()` if present; else null + `NO_REFINED_MATERIAL` **INFO** (the existing create path gracefully falls back to the input material itself when `refinedMaterial` is unset — it is admin-curated, gaps are expected) |
| `inputQuantity`   | `inputQuantity` (`Integer`, `@NotNull @Min(1)`)         | copy                                                                                                                                                                                                                                                      |
| `outputQuantity`  | `outputQuantity` (`Integer`, `@NotNull @Min(1)`)        | copy; null (un-quoted row) → row not added, `UNQUOTED_ROW` issue (§5)                                                                                                                                                                                     |
| `quality`         | `quality` (`Integer`, entity `NOT NULL`, range 0..1000) | copy; null → default `0` (existing form convention); out-of-range → warning (§5) — draftable but never savable, the review form forces correction                                                                                                         |
| `rowIndex`        | —                                                       | drives the display order of the pre-filled goods rows (match the in-game screen)                                                                                                                                                                          |
| —                 | `yieldBonusPercent`                                     | leave null — it is a read-only UEX enrichment the backend fills on read, never on import                                                                                                                                                                  |

### 7.3 Material matching algorithm (deterministic, ordered; stop at first hit) — REWRITTEN 2026-06-10

**Why the rewrite:** master data stores raw ores under the **UEX convention `"<Name> (Raw)"`** (e.g. `Stileron (Raw)`, verified in the V108 migration notes) while the SC screen shows `"<NAME> (ORE)"`. A one-sided normalization + `findByNameIgnoreCase` therefore **never matches** — both sides must be folded to a canonical form.

**Canonicalization (both sides):** reuse the folding already proven in `ScWikiCommoditySyncService.canonicalName()` — uppercase-fold, strip parentheticals, strip the qualifier words `{raw, ore, refined, pure, r}`, fold non-alphanumerics, collapse whitespace, trim.

**Candidate set — must mirror the create-path gate** (otherwise the draft pre-selects materials the create endpoint rejects with "input must be of type RAW"): `type == MaterialType.RAW || isManualRawMaterial == true`, and `isVisible == true`.

1. **Unique canonical-name match** within the candidate set (canonical(raw) == canonical(master)).
2. **`MaterialExternalAlias` lookup** — reuse the existing V108 alias table (admin-curated at `/admin/material-aliases`) with a new `source_system` value (e.g. `REFINERY_SCREEN`); requires a small migration widening the V108 CHECK constraint. Seed the aliases from the Phase 0 golden set. *(Fallback if the owner prefers zero migrations: an in-repo resource file — but then alias updates need a release instead of an admin click.)*
3. **Suffix/contains stage** for game-UI-truncated names: if the raw name is a unique suffix or substring of exactly one candidate's canonical name (e.g. `UCTION SALVAGE` → `…Construction Salvage`), match it and record the mechanism.
4. **Fuzzy fallback via the existing `BlueprintFuzzyMatcher`** (blended Levenshtein ratio + token-set Jaccard — already in-repo, dependency-free; do **not** write new fuzzy code) over the candidate set; accept only above a configurable threshold (default ~0.9) and record the score; below threshold → leave unmatched **but attach the top-N suggestions** (id, name, score) to the `UNMATCHED_MATERIAL` issue so the review UI offers a ranked pick list (precedent: `BlueprintImportSuggestionDto`).

Never silently accept a low-confidence fuzzy match — flag it (`LOW_CONFIDENCE_MATERIAL`) so the review step surfaces it. Never auto-create materials from external names (the UEX sync's Javadoc records that auto-creating placeholders "has shipped bugs in the past").

### 7.4 What stays manual (always flagged "please complete")

`owner` (defaults to the uploading user), `mission`, `otherExpenses` / `oreSales` (profit tracking — not in the SC UI), exact `startedAt` (defaults to now), storage/job-order links, and **any field/row the VLM read with low confidence or whose name did not match master data**. The draft's `issues[]` enumerates every one of these so the frontend can highlight them.

### 7.5 Draft DTOs (backend, new)

- `RefineryImportDraftDto(RefineryOrderDto order, List<ImportIssueDto> issues, int goodsMatched, int goodsTotal, int rowsSkipped)` — the order is a **best-effort, NOT persisted** pre-fill (nulls where unmatched).
- `ImportIssueDto(String field, String rawValue, ImportIssueCode code, ImportIssueSeverity severity, Double confidence, List<ImportSuggestionDto> suggestions)` where:
  - `field` is a dotted path, e.g. `goods[0].inputMaterial`, `location`, `refiningMethod`.
  - `ImportIssueCode` ∈ `{UNMATCHED_MATERIAL, LOW_CONFIDENCE_MATERIAL, NO_REFINED_MATERIAL, OUT_OF_RANGE_QUALITY, UNRESOLVED_LOCATION, UNRESOLVED_METHOD, SKIPPED_REFINE_OFF, SKIPPED_ZERO_QTY, UNQUOTED_ROW, UNQUOTED_ORDER, SUM_MISMATCH, MULTIPLE_ORDERS_TRUNCATED, UNSUPPORTED_PANEL_TYPE}`.
  - `ImportIssueSeverity` ∈ `{BLOCKING, WARNING, INFO}` (BLOCKING = cannot pre-fill a required field, e.g. unsupported panel type / all rows un-quoted).
  - `suggestions` (nullable; populated for `UNMATCHED_MATERIAL`/`LOW_CONFIDENCE_MATERIAL`): top-N ranked candidates `ImportSuggestionDto(UUID id, String name, double score)` from the fuzzy stage — the review UI renders them as a pick list on the `zuordnen` action (precedent: `BlueprintImportSuggestionDto`).

---

## 8. Cross-cutting engineering rules (apply to every basetool phase)

These come from the basetool `CLAUDE.md`; the build **fails** if you break them.

- **DTOs only at controller boundaries** — never expose JPA entities; DTOs are records with Jakarta validation; use a **MapStruct** mapper for Entity↔DTO. **`@Valid`** on every write `@RequestBody`.
- **Backend DTO change ⇒ frontend mirror in the same change.** Any new/renamed/removed field on a backend boundary DTO needs the matching frontend mirror record + template update in the same commit (the build pipeline does not catch the asymmetry; it 500s at render time in prod).
- **Security/scope** — every `@RestController` carries `@PreAuthorize`; controllers must not return entities; staffel-scoped reads/writes go through `OwnerScopeService`. Refinery is a **strict-staffel** aggregate. The import endpoint is authenticated (`isAuthenticated()`); creating the order still goes through the existing, already-scoped create path.
- **Flyway** owns the schema (`V<n>__*.sql`); `ddl-auto=validate` everywhere. This feature **needs no new tables** if matching reuses existing master data (confirm before adding any migration). If you do add one, fetch `main` first and pick `max(version)+1` to avoid a merge collision, and read `backend/.../db/migration/README.md`.
- **i18n** — every user-visible string comes from `messages.properties` / `_de` / `_en`. New keys under `refineryImport.*` and `admin.refineryImport.*`. German umlauts in `.properties` are `\uXXXX`; in Markdown they are literal UTF-8.
- **Errors** — RFC 7807 `application/problem+json` via `GlobalExceptionHandler`; validation errors add the `errors` map. Do not hardcode problem-type URIs.
- **API docs** — SpringDoc `@Operation` / `@ApiResponses` on the new endpoint; keep `backend/src/main/resources/api/openapi.json` in sync (it auto-regenerates from `OpenApiGeneratorTest` — run the test, do not hand-edit, and `git checkout` the incidental SBOM churn).
- **Javadoc is gate-enforced** on every new `public`/`protected` type and method — concrete, code-specific sentences; generic boilerplate fails Checkstyle.
- **Times in UTC** (`Instant`/`OffsetDateTime`); convert in the display layer only.
- **Tests via Gradle only** (never the IDE runner). Lint every touched file: `./gradlew :<module>:checkstyleMain :<module>:spotbugsMain` and fix **all** new Checkstyle/SpotBugs findings; run `./gradlew spotlessApply` before any push.
- **No native `confirm()`/`alert()`** in the frontend — KRT-styled modals/toasts only. Follow the DAS KARTELL design system; mobile→ultrawide responsive; 44px min touch target.
- **Logging** — `@Slf4j`; never log names, emails, or tokens.
- **Error semantics for the import endpoint:** envelope-level rejects (wrong `schemaVersion`, unsupported `panelType`, empty `orders`) use **`BadRequestException` with an i18n key** (e.g. `error.refineryImport.unsupportedPanel`) so the client sees a real message — `IllegalArgumentException` details are deliberately **never echoed** by the `GlobalExceptionHandler`. Content-level problems (unmatched names, skipped/un-quoted rows, checksum mismatch) always produce a **draft + issues**, never a 400.
- **Spec gate (binding, basetool CLAUDE.md):** every phase PR **updates `docs/specs/` in the same PR**. This feature mints the first `REQ-REFINERY-NNN` ids in a new living spec `docs/specs/refinery-screenshot-import.md` (+ registry row in `docs/specs/INDEX.md`); on v1 ship, this plan document is frozen to *historical plan* pointing at the living spec. Phases 1/2 also touch REQ-API-001..007, REQ-SEC-002/003 and REQ-UI-006/008/009 acceptance where applicable.
- **ADR gate:** record as ADRs (MADR, `docs/adr/`): (a) the client-side-VLM + manual-JSON-upload architecture (hard to reverse, cross-repo), (b) the `RefineryExtract` contract as a cross-repo interface, (c) any new UI component introduced for the review surface (toggle/switch, confidence percent+dot pattern) — ui-design-system.md's preamble requires new visual decisions to be ADR-recorded and reflected in skill + spec.
- **Worktree note:** the design-system submodule (`.claude/skills/das-kartell-design`) is **not initialized in Claude worktrees** — run `git submodule update --init` as step zero of any implementation session.
- **Multipart fact:** backend and frontend caps are **64 MB file / 72 MB request** (audit M-8/L-4) — a screenshot-extract JSON (~KB) is far below them; validate content length client-side anyway.

For the desktop repo (`basetool-sc-extractor`), the analogous rules live in its `CLAUDE.md`: Kotlin official style; pure, unit-testable parser/transform functions; **`game-log/` stays private** (extend the same guardrail to real screenshots — they contain the player handle + account balance; gitignore a local screenshots dir, commit only redacted/synthetic fixtures); OFL-only fonts; ship the **MSI via `package-msi.ps1`** (WiX-3 workaround); JDK 25 pinned for compile **and** the bundled runtime; SC Fankit footer/notice stay intact; **keep the existing MSI `upgradeUuid` through the rebrand** (a regenerated UUID would install a second app instead of upgrading).

---

## 9. Phases

> Dependency spine: **Phase 0 + the frozen contract (§5) unblock everything.** Phase 1 freezes the draft DTOs that Phase 2 consumes; Phase 2 needs Phase 1's endpoint; Phase 3 needs Phase 0's model/prompt + hardware tiers and emits the contract. Phases 1, 2 and 3 can otherwise proceed in **independent sessions** once §5 is frozen.

---

### Phase 0 — Spike: model, prompt, resolution pipeline, golden set, hardware tiers · #433

**Repo:** `basetool-sc-extractor` (currently `basetool-bp-extractor`). **Depends on:** nothing. **Unblocks:** 1, 2, 3.

**Objective.** De-risk the unknowns and produce the artifacts the build phases consume. No production code — a spike branch / scratch module + a written report.

**Inputs.** Real **English SETUP** screenshots at several resolutions. The owner's example set is at <https://nc.greluc.me/s/bbzFYL4PT4jkBKK> — currently **4K 16:9 + tiny pre-cropped panels only**; **native 1080p, 1440p and ultrawide 5K captures must still be collected** (owner/squadron action; downscaling 4K only approximates lower 16:9 resolutions, ultrawide moves the panel). Treat all real captures as **private** (they contain the player handle + account balance; do not commit them; synthesize/redact for any committed fixture).

**Deliverables.**
1. **Model decision (bake-off, exact-match per cell on the golden set)** — primary: `qwen3-vl:8b-instruct` (q4_K_M; also measure `-q8_0` — it mitigates the documented qwen-vl table-repetition-loop bug); newer-generation challengers: `qwen3.5:9b` (Feb/Mar 2026) and `gemma4:12b` (Jun 2026); **low-VRAM/CPU fallback bake-off:** `glm-ocr` (0.9B table-OCR specialist, 2.2 GB, CPU-viable — two-stage: Table-Recognition markdown → deterministic code parse → contract JSON) vs `qwen3-vl:4b-instruct` vs `gemma4:e4b-it-qat`. Never a `-thinking` variant. Record accuracy per model/size.
2. **Frozen prompt + read strategy** — an externalized, versioned prompt; **A/B-test two read strategies** and freeze the winner by golden-set accuracy: (a) single-pass with Ollama `format` = JSON Schema vs (b) freeform markdown-table read + deterministic reformat ("Format Tax", arXiv 2604.03616: schema-forcing during the vision pass measurably degrades open-model accuracy). Either way: temperature 0, generous `num_predict`, client-side validate+retry on truncated JSON, numeric cells read **as strings** and parsed in code, prompt hardened to ignore overlapping in-world AR markers. Store as a resource, not inline, so it is updatable without a new MSI.
3. **Resolution-normalization POC** — demonstrate **Locate → Normalize → Read** across 1080p–8K incl. ultrawide AND pre-cropped panel inputs: locate the panel via **classical CV** (template/edge detection on a downscaled frame — the panel chrome is fixed and high-contrast; crop from the native original; VLM bbox only as an experimental hint — open Ollama/llama.cpp grounding bugs make it unreliable), or a manual crop cached per resolution/aspect; detect pre-cropped input (panel fills the frame → skip Locate); read the **location from a second region** (terminal header, outside the panel); normalize **client-side, always** (Ollama silently downscales above ~3.2 MP — never feed full 4K/8K frames; crop native → up/downscale so row text lands in the model's sweet spot, target long edge ~1280–1600 px, row height ~32–48 px, digits ≥20–30 px, dimensions ideally multiples of 32), read.
4. **Golden test set** — the screenshots + the **expected** `RefineryExtract` JSON for each, at multiple resolutions, as a regression baseline for Phase 3. Must cover: duplicate-material orders (4× LINDINIUM), the **un-quoted GET-QUOTE state**, AR-marker overlap, a game-UI-truncated name (`UCTION SALVAGE`), pre-cropped panels, `(ORE)` + `(RAW)` suffixes, INERT rows at top and bottom.
5. **Hardware tiers** — measured **VRAM/RAM/CPU** per model size and CPU-only speed (minutes/image); a **minimum vs. recommended** table; the "auto-select model size per detected VRAM, else warn + offer low-VRAM/CPU" policy; the "close SC before extracting" policy. Include the vendor-neutral probe: load the model, read `ollama ps`'s GPU/CPU split ("does it actually fit") — don't rely on `nvidia-smi` alone (AMD/Intel users exist).
6. **Confidence-derivation policy** — define and validate the derived per-row confidence: exact-match agreement across **two passes** (vary crop padding/scale between passes — at temperature 0 an identical input repeats the same systematic misread) blended with the header-total checksum; verbalized self-confidence is uncalibrated for OCR and must not be used.
7. **Header-total semantics** — verify against the golden set what `IN MANIFEST` / `TO REFINE` mean exactly (sum over all rows? selected rows? they differ in Auftrag 4: 2564 vs 1948) and freeze the `SUM_MISMATCH` reconciliation rule.
8. **Spike report** (`docs/refinery-extractor/PHASE0_FINDINGS.md` in the desktop repo) capturing all of the above + the column-semantics confirmation from §6.

**Design feed.** The measured hardware tiers feed the preflight UI in [`docs/DESIGN_SC_EXTRACTOR.md`](DESIGN_SC_EXTRACTOR.md) §5.1 — the min/recommended tier bar, the auto-selected-model chip, and the low-VRAM/CPU fallback copy. Record **GB-per-model-size** and **CPU minutes/image** as exact numbers so the preflight thresholds are not guesses.

**Definition of Done.** Report committed (when owner approves committing); the prompt + read strategy + golden set + hardware table + confidence-derivation policy + header-total semantics exist and are referenced by #436; `qwen3-vl:8b-instruct` (or a justified bake-off winner) is confirmed with numbers; the low-VRAM/CPU fallback is named with numbers. No regression to the blueprint parser: the 24 automated tests stay green (`.\gradlew.bat test`) **and** the manual 179/`greluc` characterization rule still holds (it is a manual check against the private `game-log/`, not an automated test).

**Out of scope (this phase).** Production GUI, the launcher, packaging, the backend, the frontend.

---

### Phase 1 — Backend import endpoint + master-data matching · #434

**Repo:** `basetool`. **Depends on:** frozen contract §5. **Unblocks:** Phase 2.

**Objective.** Accept a `RefineryExtract` JSON, match it to master data, and return a non-persisted `RefineryImportDraftDto`. **No persistence, no new tables** (one tiny CHECK-constraint migration for the alias source — see below).

**Deliverables (files & signatures).**
- **Inbound DTOs** (`backend/.../model/dto/`), records with Jakarta validation mirroring §5 (incl. `quoted`, `rowIndex`, `rawInManifestTotal`/`rawToRefineTotal`, nullable `outputQuantity`): `RefineryExtractDto`, `RefineryExtractOrderDto`, `RefineryExtractGoodDto`, `RefineryExtractImageDto`. (`schemaVersion @NotNull`, `orders @NotEmpty @Size(max=5) @Valid`, `goods @Size(max=100)`, per-good `@NotNull` where the contract requires it, `@Size` caps on raw strings.)
- **Draft DTOs** (§7.5): `RefineryImportDraftDto`, `ImportIssueDto` (incl. `suggestions`), `ImportSuggestionDto`, enums `ImportIssueCode` (incl. `UNQUOTED_ROW`, `UNQUOTED_ORDER`, `SUM_MISMATCH`), `ImportIssueSeverity`.
- **Service** `RefineryImportService` (constructor-injected `MaterialRepository`, `RefiningMethodRepository`, `LocationRepository`/location lookup, `MaterialExternalAliasService`, `BlueprintFuzzyMatcher` (reused), `AuthHelperService`/`OwnerScopeService` as needed). Methods: `RefineryImportDraftDto buildDraft(RefineryExtractDto extract, UUID callerId)`; `Optional<Material> matchMaterial(String rawName)` (§7.3 — canonical both sides, candidate set `RAW || isManualRawMaterial`, alias, suffix/contains, fuzzy + suggestions); `Optional<RefiningMethod> matchMethod(String rawName)` (case-insensitive); `Optional<Location> matchRefineryLocation(String rawName)` (over `findLocationsWithRefinery()`). Keep matching **pure and unit-testable** (no `SecurityContextHolder` here).
- **Endpoint** on `RefineryOrderController` (or a new `RefineryImportController`):
`POST /api/v1/refinery-orders/import-extract`, `consumes = application/json`, `@PreAuthorize("isAuthenticated()")`, `@RequestBody @Valid RefineryExtractDto`, returns `RefineryImportDraftDto`. SpringDoc `@Operation`/`@ApiResponses` (200 draft, 400 invalid/unsupported panel, 401). Envelope rejects via `BadRequestException` + i18n key (§8 error semantics). **Does not** create anything — the existing `POST /api/v1/refinery-orders` still does that, untouched.
- **Alias storage** — extend the existing `MaterialExternalAlias` table with a new `source_system` value (e.g. `REFINERY_SCREEN`): one migration `V<max+1>__extend_material_alias_source.sql` widening the V108 CHECK constraint (fetch `main` first, pick `max(version)+1`, read the migration README). Seed the English aliases from the Phase 0 golden set; admins curate at `/admin/material-aliases`. *(Owner-confirmed alternative if zero migrations are preferred: an in-repo resource file — alias updates then require a release.)*
- **i18n** keys `refineryImport.issue.*` (one per `ImportIssueCode`) in all three `messages*.properties`, plus `error.refineryImport.*` for the envelope rejects.

**Design note (no UI in this phase).** The draft DTO must carry everything the review surface ([`docs/DESIGN_SC_EXTRACTOR.md`](DESIGN_SC_EXTRACTOR.md) §5.4) renders: per-row / per-field match state + confidence, unmatched / low-confidence flags, and the provenance fields (`tool`, `model`, `schemaVersion`, `panelType`, `generatedAt`) the export screen mirrors. `ImportIssueDto.confidence` + `code`/`severity` and the draft counts already cover this — keep them populated for every issue so the frontend can colour the confidence dots and count flagged fields without re-deriving anything.

**Algorithm.** Validate (§5 rules) → take `orders[0]` → for each good (in `rowIndex` order): skip-or-map per §5/§7.2, matching per §7.3, derive `outputMaterial` via `Material.refinedMaterial` → map order-level fields (§7.1) → reconcile header totals (`SUM_MISMATCH`) → assemble draft + issues. All times UTC.

**Tests (Gradle only).** Unit: `RefineryImportServiceTest` (canonical-name match incl. `"STILERON (ORE)"` → `"Stileron (Raw)"`, case-insensitive, alias, suffix/contains for truncated names (`"UCTION SALVAGE"`), fuzzy threshold accept/reject + suggestions populated, duplicate-material rows preserved (4× same material at different qualities), unmatched, refine-off skip, zero-qty skip, **un-quoted row → `UNQUOTED_ROW`**, **all-unquoted → `UNQUOTED_ORDER` BLOCKING**, **header-total mismatch → `SUM_MISMATCH`**, out-of-range quality, null quality → 0, refinedMaterial derivation + INFO fallback, multiple-orders truncation, unsupported panel, candidate-set gate = `RAW || isManualRawMaterial`). Controller `@WebMvcTest`/MockMvc: 200 happy path, 400 on `schemaVersion != 1`, 400 on `PROCESSING` (with i18n detail), 401 unauthenticated, 400 on `@Size` cap violations. JSON binding test deserializing the §5 example verbatim. ArchUnit stays green.

**Definition of Done.** `./gradlew :backend:test` green; `./gradlew :backend:checkstyleMain :backend:spotbugsMain` clean for touched files; `openapi.json` regenerated via `OpenApiGeneratorTest`; CHANGELOG entry; Javadoc on every new type/method; **spec gate satisfied** — `docs/specs/refinery-screenshot-import.md` created/updated with the `REQ-REFINERY-NNN` ids this phase implements (+ INDEX row) and the architecture/contract ADRs exist (§8).

**Out of scope.** Persisting the order; the upload UI; any desktop code; PROCESSING.

---

### Phase 2 — Frontend upload + pre-filled review form · #435

**Repo:** `basetool` (frontend). **Depends on:** Phase 1 endpoint + draft DTOs.

**Objective.** Let the user upload the JSON, relay it to Phase 1, and render the **existing** refinery create form **pre-filled**, with every unmatched/low-confidence field visibly flagged for review before save.

**Deliverables.**
- **Frontend mirror DTOs** for `RefineryImportDraftDto` / `ImportIssueDto` / `ImportSuggestionDto` (+ enums) under `frontend/.../model/dto/` — same fields (cross-cutting rule: mirror in the same change).
- **Upload proxy** `RefineryImportProxyController` — primary reference is **`PersonalBlueprintImportProxyController`** (the typed preview/apply two-stage import — the architectural twin of this flow); `HangarImportProxyController` is the simpler single-shot cousin. `POST /refinery-orders/import` `consumes = multipart/form-data`, `@PreAuthorize("isAuthenticated()")`, `@RequestParam("file") MultipartFile`; read bytes, relay as **`application/json`** to `POST /api/v1/refinery-orders/import-extract` via the authenticated `WebClient` (typed response DTO, not a raw `Map`); translate `WebClientResponseException` → `ResponseStatusException` (so the page JS can read the RFC-7807 `detail`); no `@Retry` on the POST (writes are never retried by design). (Multipart caps are 64 MB/72 MB — the extract JSON is ~KB; still validate content length client-side.)
- **UI on the refinery create page** (`/refinery-orders`, `RefineryOrderPageController` + its Thymeleaf template): an "Import from screenshot extract (JSON)" control (KRT-styled — hidden `<input type="file">` + styled trigger button, the established pattern; **no** native dialogs for confirmations). **Pre-fill server-side via the existing flash-attribute mechanism**: the import handler builds a populated `RefineryOrderForm` from `draft.order` (goods in `rowIndex` order), does `redirectAttributes.addFlashAttribute("refineryOrderForm", form)` (+ a flashed issues list) and redirects to `/refinery-orders/create` — `viewCreateForm` already renders a flashed form including N goods rows. This reuses the template loop, is CSP-safe and leaves the existing create-page JS untouched. Then render each `issues[]` entry as an inline KRT warning/badge on the relevant field (highlight unmatched material rows — empty-but-required selects naturally force completion; `suggestions` feed a ranked pick list, type-ahead via the existing `krt-searchable-select.js`). A summary banner: "X of Y rows matched, Z skipped — please review highlighted fields." Optimistic-locking note: this is a fresh create (no `@Version` yet), so no version-sync concern until first save.
- **Error/empty states (KRT toasts/inline alerts, each with a recovery hint):** not-a-JSON file · wrong `schemaVersion` ("please update the extractor") · zero matched rows · **all rows un-quoted** ("press GET QUOTE in game and re-capture").
- **Component budget (verified gaps):** no toggle/switch component exists in the design system or the frontend (the Refine column → styled checkbox or a new component **with ADR + design-skill update**); no global `.alert-info` variant (add it or use warning); promote the hidden-input file-upload pattern to a shared fragment. The confidence dot reuses the `.status-pill` square-dot pattern.
- **i18n** keys `refineryImport.*` (button, help text, banner, per-issue messages) in all three `messages*.properties` (DE umlauts `\uXXXX`).
- Responsive (smartphone→ultrawide); 44 px touch targets.

**Design / UI acceptance (binding — [`docs/DESIGN_SC_EXTRACTOR.md`](DESIGN_SC_EXTRACTOR.md) §5.4).** The pre-filled review uses the §5.4 visual language: four **order-header cards** (Standort / Methode / Gesamtkosten / Dauer), each in a matched ✔ or needs-attention ⚠ state with a left status bar; a **goods table** with columns Material (raw, mono) · **Match** (green ✔ + master-data name, or a red "kein Treffer" chip + a `zuordnen` action) · Qualität · Input · Ausbeute (green `+`) · **Refine** toggle · **Konfidenz = percent + status dot** coloured ≥90 % success / 75–90 % warning / <75 % danger; unmatched / below-threshold rows get a coloured 3 px left border; a banner counts flagged fields; the "stays manual — please complete" chip row (owner, mission, otherExpenses, oreSales, startedAt) marks the empty/uncertain ones amber; `SETUP` + `layout NN%` badges sit in the header. Exactly **one** orange CTA = confirm (the binding rule is one CTA **per context/panel**, REQ-UI-002). **Confidence percent rendered as coloured text must use the accessible text tints** `--color-danger-text`/`--color-info-text`/`--color-success-text` (REQ-UI-006 — the canonical hues fail WCAG as small text on black; dot fills may carry the canonical hue). This is the **same** visual language the desktop Review screen uses (#436 §5.4) — keep them identical.

**Tests.** Frontend controller test for the proxy (WireMock/MockWebServer: backend 200 → draft passthrough; backend 400 → surfaced error). A `@SpringBootTest` MockMvc **full template render** test of the create page with a pre-filled draft (the project has been bitten by render-time 500s that pure controller tests miss). E2E (`e2e` label): upload a fixture JSON → form pre-filled → user completes an unmatched row → save succeeds. Note: this is the **first file-upload e2e in the repo** — `Locator.setInputFiles(Path)` against the hidden input, fixture under `frontend/src/e2e/resources/`; remember `:frontend:e2eTest` is input-cached on the e2e source set only (`--rerun-tasks` after main-code changes) and the e2e compose disables rate limiting. Align with `docs/e2e-test/UC-04-refinery-order-anlegen.md`.

**Definition of Done.** `./gradlew :frontend:test` green; frontend lint clean (Checkstyle/SpotBugs + the Node ESLint/Stylelint/HTMLHint linters); CHANGELOG; PR carries the `e2e` label (touches a frontend flow); **spec gate satisfied** — the `REQ-REFINERY-NNN` ids this phase implements are updated in the living spec, and the UI-component ADR exists if a new component was introduced (§8).

**Out of scope.** Direct desktop→backend upload (Phase 4); changing the create/save endpoint; PROCESSING fields (TO DO / DONE / total YIELD SCU / time remaining).

---

### Phase 3 — Desktop screenshot extraction (rebrand + launcher + Ollama + resource safety) · #436

**Repo:** `basetool-sc-extractor` (rebrand of `basetool-bp-extractor`). **Depends on:** Phase 0 (model, prompt, golden set, hardware tiers) + frozen contract §5.

**Objective.** Produce a `RefineryExtract.json` from SETUP screenshot(s), safely, on the user's PC.

**Deliverables.**
- **Rebrand & launcher.** Rename the app to `basetool-sc-extractor`; add a **central launcher GUI** that routes to the **Blueprint** workflow (existing) or the new **Refinery** workflow. Reality check: the app currently has exactly **one** screen and no navigation layer — the Top-Tabs + stepper shell, the restyled blueprint workflow and the five refinery screens are **net-new UI**, the bulk of this phase. Keep `Main.kt`'s CLI contract (`args ⇒ runCli`, no args ⇒ GUI) and the always-visible `CommunityDisclaimerFooter` on every screen. **Widen the repo's `CLAUDE.md` scope** from "blueprints only" to "multi-workflow SC extractor" and add a screenshots-privacy guardrail next to `game-log/` (real captures contain the player handle + balance). **Keep the 24 automated blueprint tests green and re-verify the manual 179 / `greluc` characterization** through the restructure. Keep packaging via `package-msi.ps1` (WiX-3), the JDK-25 `javaHome` pin, and **the existing MSI `upgradeUuid`** (a regenerated UUID = side-by-side double install instead of an upgrade); `packageVersion` stays strictly numeric.
- **i18n infrastructure (net-new).** The repo currently hardcodes German strings; the frozen design demands **DE default with full EN parity** + title-bar toggle. Introduce a lightweight string-resource layer (keyed map / resource bundles) and migrate the existing blueprint-workflow strings to it in the same phase.
- **Refinery pipeline** (pure, side-effect-free where possible, mirroring `BlueprintParser`'s testable style): `Locate (classical CV; precropped detection; manual fallback) → Normalize (client-side crop+scale; bicubic/Catmull-Rom — true Lanczos exists in neither AWT nor Skia, hand-roll a Lanczos-3 kernel only if the golden set proves it matters) → Read (single pass — the planned second agreement pass was rejected by the Phase 0 data, see §3.1 item 5) → Stitch/dedupe`. Read step calls Ollama with the Phase 0 frozen read strategy (`/api/chat`; freeform-markdown + deterministic reformat — the A/B winner over schema-forcing; temperature 0; validate+retry on truncated output; numeric cells as strings, parsed in code). The location is read from the **second region** (terminal header) when a full frame is available. **Stitch/dedupe (corrected):** merge rows across scrolled screenshots by **full row identity `(rawMaterialName, quality, inputQuantity)`** — duplicate materials at different qualities are normal; never merge rows co-visible in the same screenshot; prefer the **quoted** variant (`outputQuantity` present) over an un-quoted duplicate; reconstruct on-screen order from scroll overlap (file timestamps are unreliable) and emit `rowIndex`. Detect the **un-quoted GET-QUOTE state** (YIELD `--` / GET QUOTE CTA) and warn before export. Compute the derived per-row `confidence` (deterministic validation + the one-sided header-total checksum — the frozen Phase 0 policy, §3.1 item 5). Emit the contract via a `@Serializable` model (bump its own `schemaVersion`). HTTP via the JDK built-in `java.net.http.HttpClient` — add the `java.net.http` module to the jlink `modules(...)`, re-run `suggestRuntimeModules`, and re-launch the GUI on the slim runtime (the documented breakage mode).
- **Ollama integration (guided prerequisite).** On launch: check Ollama reachable (`GET /api/tags`); check the configured model present; if missing, offer `ollama pull <model>` with a **progress indicator**; clear, KRT-styled messaging if Ollama is absent (link to install docs). Endpoint + model name are **configurable**. Pin the model with `keep_alive` for the batch run and release it (`keep_alive: 0`) on the final request. **No bundling, no auto-install.**
- **Resource-safety guardrails (client side).** Preflight **hardware check**: `StarCitizen.exe` via `ProcessHandle.allProcesses()` (dependency-free); system RAM via `OperatingSystemMXBean.getTotalMemorySize()` (needs the `jdk.management` module in the slim runtime); GPU/VRAM via `nvidia-smi` with the registry `HardwareInformation.qwMemorySize` fallback — **never `wmic`** (removed from current Win 11) and never trust `Win32_VideoController.AdapterRAM` (32-bit field, lies above 4 GB); the authoritative, vendor-neutral "does it fit" signal is `ollama ps`'s GPU/CPU split after a probe load (AMD/Intel GPUs exist). Show **minimum vs. recommended** and **auto-select** a fitting model size, else warn and offer the **low-VRAM** model or **CPU mode** (works, slow — show the measured ETA per image); **soft, non-blocking** "close SC for safe extraction — continue anyway?" warning; **throttle** to one image through the model at a time (aligned with Ollama's default `OLLAMA_NUM_PARALLEL=1`); **never silently overload** — warn clearly when below minimum. All behind interfaces so they are unit-testable.
- **README** (German UI per repo convention) documenting Ollama install + `ollama pull`, the minimum/recommended hardware, the "close SC first" guidance, and the "capture AFTER pressing GET QUOTE" guidance.

**Design / UI acceptance (binding — [`docs/DESIGN_SC_EXTRACTOR.md`](DESIGN_SC_EXTRACTOR.md); build screen-for-screen to the prototype).**
- Custom KRT **title bar** (logo + "BASETOOL SC EXTRACTOR" + DE/EN toggle + min/max/close + orange hairline + resize grip).
- **Top-Tabs** launcher (Start · Blueprints · Refinery) + a per-workflow **step stepper**; Start = greeting + two workflow cards + fan-kit footer (Made-by-the-Community logo unaltered + verbatim CIG notice).
- Blueprint workflow restyled (Konfiguration · Extraktion · Zusammenfassung), one orange CTA per context/panel, neutral-grey labels, KRT tables.
- Refinery **Vorprüfung** implements every §5.1 state: Ollama reachable / model-present / model-missing (+ inline `ollama pull` progress) / unreachable (+ 2-step install hint); hardware GPU/VRAM/RAM + auto-selected-model chip + min/recommended tier bar + below-recommended fallback radio (low-VRAM model | CPU mode); SC-running soft warning + "trotzdem fortfahren" acknowledge checkbox; "one image at a time" throttle note.
- **Bilder laden** (1 folder = 1 order; thumbnail grid with resolution + crop tags), **Extraktion** (per-image Locate→Normalize→Read tracks, one active at a time, orange-accent console, model chip), **Review** (§5.4 — identical to #435), **Export** (written-path success + manual-upload instructions + provenance panel).
- Comfortable density, honeycomb texture (~0.32), DE/EN parity, **no native dialogs** (KRT modals/toasts only).

**Tests.** Pure-function unit tests (Kotlin, Gradle): stitch/dedupe across multi-image inputs (incl. duplicate materials at different qualities, quoted-over-unquoted preference, same-image-no-merge, overlap ordering), normalization math (resolution → crop/scale targets, precropped detection), un-quoted-state detection, deterministic confidence derivation (cell plausibility, REFINE-toggle fallback, one-sided header checksum), contract serialization shape (incl. `quoted`/`rowIndex`/nullable `outputQuantity`), the SC-process-detection and hardware-tier **decision logic** (mock the probes), Ollama-response → contract mapping (mock the HTTP call). Golden-set **regression** using Phase 0's recorded model outputs (do not require a live GPU in CI). Launch the GUI to confirm the slim runtime still boots (Skiko init — mandatory after adding `java.net.http`/`jdk.management` modules).

**Definition of Done.** `.\gradlew.bat test` green (24 preserved blueprint tests + the new suites) **and** the manual 179/`greluc` characterization re-verified against the private `game-log/`; GUI launches on the slim runtime; MSI builds via `package-msi.ps1` and upgrades an existing install in place (same `upgradeUuid`); README updated; DE/EN parity verified via the title-bar toggle; the emitted JSON validates against §5 and is accepted by the Phase 1 endpoint end-to-end with a golden fixture.

**Out of scope.** Direct upload to the backend (Phase 4); PROCESSING parsing; log parsing (Phase 5).

---

### Phase 4 — Direct authenticated upload desktop → basetool · #437 · **DEFERRED (post-v1)**

**Deferred per owner decision (2026-06-05):** there is currently no way for the desktop tool to authenticate to and push to the backend, so v1 stays manual-upload-only (C3). Keep this issue open as a future option; do **not** implement it as part of the first delivery. When revisited it would add an OAuth2 device-code / PAT flow from the desktop app to a dedicated authenticated ingest endpoint, reusing the Phase 1 matching. Nothing in Phases 0–3 may assume this channel exists.

---

### Phase 5 — Log-derived location + timestamp hint · #438 · **DEFERRED (optional)**

The SC `Game.log` cannot provide the materials breakdown (verified: the only refine event `OnRefineryRequest` logs an empty `request[]`; the breakdown lives server-side at CIG). The log can at best contribute a **location + timestamp** hint to reduce manual entry. Optional, low priority, independent of Phases 0–3. Do not re-investigate the log-for-materials dead end.

---

## 10. Definition of Done — whole feature (v1)

- A user with a supported PC can: install Ollama + pull the model (guided), run `basetool-sc-extractor` → Refinery workflow, extract SETUP screenshot(s) to `RefineryExtract.json`, upload it on `/refinery-orders`, see the create form pre-filled with materials/quality/qty/yield/location/method/cost/duration, see unmatched/low-confidence items flagged, complete them, and save via the normal create path.
- The Hetzner VM never receives an image and never runs inference; disk/CPU impact is a small JSON parse + DB lookups.
- Every phase's tests pass via Gradle; all new Checkstyle/SpotBugs findings fixed; Javadoc/i18n/CHANGELOG/openapi gates satisfied; the blueprint tool's behavior and `game-log/` privacy are preserved.
- **Docs-as-code gates satisfied:** `docs/specs/refinery-screenshot-import.md` exists with the implemented `REQ-REFINERY-NNN` ids (+ INDEX registry row); the architecture/contract/UI-component ADRs exist; this plan document is frozen to *historical plan* pointing at the living spec.
- The rebuilt `basetool-sc-extractor` GUI and the frontend review surface match the binding Claude Design spec ([`docs/DESIGN_SC_EXTRACTOR.md`](DESIGN_SC_EXTRACTOR.md) + the prototype) screen-for-screen: Top-Tabs launcher, the §5.1 preflight states, the §5.4 review language (confidence = percent + dot), comfortable density, honeycomb texture, DE/EN parity, no native dialogs.

---

## 11. Glossary

- **SETUP screen** — the REFINEMENT CENTER tab where an order is configured (materials, quality, qty, yield, refine toggle, location, method, cost, time). v1 reads this only.
- **PROCESSING screen** — the in-progress/finished tab (TO DO / DONE / total YIELD SCU / time remaining). Deferred.
- **VLM** — vision-language model (here `qwen3-vl:8b-instruct` via Ollama), run locally on the user's GPU.
- **RefineryExtract** — the frozen JSON contract (§5), the single desktop→frontend→backend hand-off.
- **Draft** — `RefineryImportDraftDto`: a non-persisted, best-effort pre-fill + issue list returned by the backend import endpoint.
- **Quoted / un-quoted** — the SETUP panel state after / before the in-game **GET QUOTE** action; un-quoted panels show `--` yields and no cost/time and cannot produce a complete order.
- **Derived confidence** — per-row confidence computed from deterministic validation (numeric cell plausibility, REFINE-toggle fallback) + the one-sided header-total checksum (never the model's verbalized self-estimate; the originally planned two-pass agreement was rejected by the Phase 0 data).
- **Strict-staffel aggregate** — a basetool org-unit-scoped entity (Refinery Order is one); scope is enforced in the service layer via `OwnerScopeService`.
