# Refinery work order → Basetool refinery order: field mapping

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-08 for wayfinder ticket #3130 (map #3128, direct refinery-order exchange for desktop
> clients such as VerseKit). Nothing here is binding; a feature built on it gets its own `REQ-…`
> and, where it decides something, its own ADR.

## Question

Which data does an in-game refinery work order expose to a client reading the running game, and how
does each piece map onto `RefineryOrder` / `RefineryGood`? Which Basetool fields have no in-game
source, and which in-game data has no Basetool field?

## Sources

All paths are relative to the repository root at `origin/main` (`1834223b3`).

| Id | Source |
|---|---|
| S1 | `backend/…/refinery/internal/RefineryOrder.java`, `RefineryGood.java`, `RefineryOrderStatus.java` |
| S2 | `backend/…/refinery/internal/RefineryOrderCreateRequest.java`, `RefineryOrderUpdateRequest.java`; `refinery/api/RefineryOrderDto.java`, `RefineryGoodDto.java` |
| S3 | `backend/…/refinery/internal/RefineryOrderService.java` (create/update, `resolveGoodMaterials`, `validateLocationHasRefinery`) |
| S4 | `backend/…/refinery/internal/RefineryImportService.java` (draft builder, matching) |
| S5 | `backend/…/refinery/api/RefineryExtract*Dto.java`, `ImportIssueCode.java`, `RefineryImportDraftDto.java` |
| S6 | `ingest/src/main/resources/exchange/v1/schemas/refinery-draft.schema.json`; `docs/exchange/examples/v1/refinery-draft/`; `docs/exchange/resources/drafts.md`, `catalog.md` |
| S7 | `docs/specs/refinery-screenshot-import.md` (REQ-REFINERY-004…008, 017, 018) |
| S8 | `docs/specs/refinery-orders-overview.md` (REQ-REFINERY-020, 022, 025, write requests) |
| S9 | `docs/specs/refinery-notifications.md` (REQ-REFINERY-023) |
| S10 | `docs/adr/0008-refinery-extract-json-contract.md`; `docs/adr/0002-…` and `docs/specs/whole-number-amounts.md` |
| S11 | `docs/research/game-data-and-game-log-findings.md` §5 table, §6.6, §8 |
| S12 | `backend/…/catalogue/api/Material.java`, `RefiningMethod.java`, `MaterialExternalAliasSource.java`; `kernel/QualityValue.java` |
| S13 | `backend/…/refinery/internal/RefineryOrderQueryService.java` (`/ 100.0d`), `RefineryOrderStoreService.java` (`× 100`) |
| S14 | `frontend/…/RefineryImportProxyController#toForm` |

## What the game exposes

The Basetool knows the in-game work order only through two channels; neither reads game memory.

1. **The refinery terminal UI, read by OCR/VLM** — the shape the SC Extractor already produces
   (`RefineryExtract` v1, S5, S6, S10). From the **SETUP** panel after GET QUOTE: terminal header
   (location), refining method, per material row `QTY`, `QUALITY`, `YIELD`, `REFINE` toggle, the
   header totals `IN MANIFEST` / `TO REFINE`, the total cost and the processing time. The
   **PROCESSING** panel (a running order) only appears as `totalYieldScu` ("PROCESSING-only figure;
   ignored in v1") and as a `panelType` the backend rejects (S4, S5). The materials list is a
   scrolling ~6-row viewport, and the header totals freeze at GET QUOTE while rows stay live (S7,
   REQ-REFINERY-007).
2. **Game.log** — only the notification "Refinery Work Order(s) … Completed at <station>": station
   and number of finished orders, but no ore, quantity or yield (S11).

A VerseKit-style reader of a running work order (location, remaining timer, up to ~7 materials with
qualities) therefore sits between the two: richer than the log, but on a different panel than the
one the existing contract is built for.

## Field-by-field mapping

`RefineryOrder` (S1, S2):

| In-game datum | Contract field today (`refinery-draft` v1) | Basetool field | Conversion | Validation | Notes |
|---|---|---|---|---|---|
| Terminal header / station name | `rawLocationName` (verbatim) | `location` (`Location`, required) | Server-side: unique canonical-name match against `findLocationsWithRefinery()` (S4 `matchRefineryLocation`) | Location must host a live UEX `type = refinery` terminal and not be hidden (REQ-REFINERY-020); unmatched or ambiguous → `UNRESOLVED_LOCATION` (WARNING), field `null`; save then fails `error.refinery_order.location_required` (S3) | Absent on pre-cropped captures. No id-based reference exists. |
| Refining method | `rawMethodName` | `refiningMethod` (nullable) | Exact name → canonical fold → fuzzy ≥ 0.6 over the nine-method `refining_method` table (REQ-REFINERY-008) | Unresolved → `UNRESOLVED_METHOD`, field `null` (allowed) | `RefiningMethod.code` exists (S12) but the contract cannot send it. |
| Total cost | `expenses` (number) | `expenses` (`Double`, ≥ 0, ≤ 1e9) | Copied verbatim | `@PositiveOrZero`, `@DecimalMax` | Stored as `Double`; ADR-0002 "whole at the edges" is a display/edge rule for mission finance, refinery figures fold in fractionally (S10). Draft form defaults `0` when absent (S14). |
| Processing time (SETUP quote) | `durationMinutes` (integer) | `durationMinutes` (`Long`, ≥ 0, nullable) | Verbatim | `@PositiveOrZero` | Form splits into hours + minutes (S14). |
| **Remaining timer** (running order) | — | — (no field) | Would need `startedAt = capture + remaining − duration` | — | **Gap.** `endsAt` is a read-only DB `@Formula` (`started_at + duration_minutes`), never writable (S1). |
| Capture time | `sourceImages[].capturedAt` | `startedAt` (nullable) | Latest `capturedAt` across images (REQ-REFINERY-017) | Not checked against "now" | `null` → "now" at save time (S3). Correct only when the capture is taken at order start, which a running-order read is not. |
| Order state (setup / processing / done) | `panelType` (`SETUP` only), `quoted` | `status` | Draft always `OPEN` | Create ignores client status, always `OPEN` (REQ-REFINERY-022) | `IN_PROGRESS` exists but only a later `PUT` can set it. A running order cannot arrive as `IN_PROGRESS`. |
| Total yield SCU (PROCESSING) | `totalYieldScu` | — | Ignored | — | Gap: no order-level field. |
| `IN MANIFEST` / `TO REFINE` | `rawInManifestTotal`, `rawToRefineTotal` | — | Checksum only: `SUM_MISMATCH` when refine-ON rows exceed `TO REFINE` (REQ-REFINERY-007) | `IN MANIFEST` never validated | Not persisted. |
| Work-order number / identity | — | — | — | — | **Gap both ways:** the game identity of a work order is not in the contract, and `RefineryOrder` has no external key, so re-sending one order yields a second draft; only the member's review prevents a duplicate. |
| — | — | `owner` | Uploading user (draft) / caller | Logistician path for others (S8) | No in-game source (player name must not be sent). |
| — | — | `missionId` | — | Optional | No in-game source. |
| — | — | `otherExpenses`, `oreSales` | — | Optional, ≥ 0 | No in-game source at order time (sale happens later). `profit` is computed. |
| — | — | `owningOrgUnit` | Picker / stamping on create | REQ-ORG-016 | No in-game source. |
| — | — | `storedAt`, `readyNotifiedAt`, `version` | Server-managed | REQ-REFINERY-022/023 | No in-game source by design. |
| Provenance | `tool`, `toolVersion`, `model`, `generatedAt`, `clientLanguage`, `layoutConfidence`, image `name/width/height/cropMode` | — | Display/debug only | — | Not persisted (ADR-0008). |

`RefineryGood`, one per material row (S1, S2):

| In-game datum | Contract field | Basetool field | Conversion | Validation | Notes |
|---|---|---|---|---|---|
| Material name (possibly truncated) | `rawMaterialName` | `inputMaterial` (required) | See *Material resolution* | Must be `RAW` or `isManualRawMaterial` (S3) | Unmatched → `UNMATCHED_MATERIAL` with suggestions, field `null`, save blocked. |
| — (derived) | — | `outputMaterial` (required on entity) | `inputMaterial.refinedMaterial`, else the input itself (S3) | A sent output must equal the input's `refinedMaterial` | Missing curated link → `NO_REFINED_MATERIAL` (INFO). |
| `QTY` | `inputQuantity` (integer ≥ 0, required) | `inputQuantity` (`Integer`, ≥ 0) | Verbatim, integer **units** (100 units = 1 SCU, S13) | `0` = "not entered" (REQ-REFINERY-025); the import skips rows `< 1` (`SKIPPED_ZERO_QTY`) | — |
| `YIELD` | `outputQuantity` (nullable) | `outputQuantity` (`Integer`, ≥ 1) | Verbatim, integer units; converted to SCU only at display/store (`/ 100`, rounding mode setting `UP`) | `null` → `UNQUOTED_ROW`; all `null` → `UNQUOTED_ORDER` (BLOCKING) | A running order shows the yield already fixed. |
| `QUALITY` | `quality` (integer, unbounded) | `quality` (`Integer`, 0…1000, `@QualityValue`) | `null` → `0` | Outside 0…1000 → `OUT_OF_RANGE_QUALITY`, kept un-clamped, save blocked (REQ-REFINERY-006) | Duplicate material rows with different qualities are expected and kept separate (REQ-REFINERY-005). |
| `REFINE` toggle | `refine` (required) | — | `false` → row skipped (`SKIPPED_REFINE_OFF`) | — | On a running order every listed row is being refined, so the toggle has no meaning there. |
| Row position | `rowIndex` | goods order | Sort key | — | `RefineryGood` is a `Set`; order is not persisted. |
| Read confidence | `confidence` | — | Echoed in issues | — | Not persisted. |
| Per-row source image | `sourceImage` | — | Provenance | — | — |
| — | — | `yieldBonusPercent` (read DTO) | UEX enrichment, ignored on write | — | No in-game source needed. |

## Material resolution

Two resolution paths exist, and the refinery draft uses only the name-based one.

- **Refinery draft (`POST /exchange/v1/me/drafts/refinery-orders`)**: the contract carries only the
  verbatim `rawMaterialName` (ADR-0008: the producer never resolves master data). The backend runs
  REQ-REFINERY-004 over the visible `RAW || isManualRawMaterial` candidates, stopping at the first
  hit: (1) unique canonical-name match (`MaterialNameCanonicalizer`: lowercase, parentheticals and
  `raw/ore/refined/pure/r` dropped), (2) curated `MaterialExternalAlias` of source
  `REFINERY_SCREEN`, (3) unique containment match of a ≥ 5-character fragment against names and
  aliases (for UI-truncated names), (4) fuzzy match, applied at ≥ 0.9 and flagged
  `LOW_CONFIDENCE_MATERIAL`, otherwise unmatched with ranked suggestions. Materials are never created
  from an external name. The output material follows from the admin-curated
  `Material.refinedMaterial` link (S4, S7).
- **Catalogue resolve (`POST /exchange/v1/catalog/resolve`, kind `MATERIAL`)**: accepts a reference
  by `bt` (material id), `scRecord` (Wiki key), `scGuid` (Wiki or game-file UUID — `scwikiUuid` /
  `p4kUuid`), `uexId` (`idCommodity`), `locKey` (`nameKey`) or `name` (S6, S12). A client could
  resolve once and cache the `bt` id, but the refinery draft has no field to send it in.

There is no resolve kind for a **location** or a **refining method**; both resolve only from verbatim
names inside the draft.

## Gaps

**In-game data with no Basetool field or no contract field**

1. Running-order panel: the contract accepts only `SETUP`; a PROCESSING read is a 400 (S4, S6).
2. Remaining timer: no field; `endsAt` is computed and read-only. To make the ready notification
   (REQ-REFINERY-023) fire at the right time, a client must send `startedAt` and `durationMinutes`
   whose sum equals capture time + remaining time. Without the total duration only
   `startedAt = capture`, `durationMinutes = remaining` is possible (correct end, wrong start).
3. In-game work-order identity: not carried and not stored, so no de-duplication.
4. Order state: arrives as `OPEN` only; `IN_PROGRESS` or "completed" cannot be set on create.
5. Total yield SCU, header totals: not persisted.
6. Game.log "Completed at <station>" has no matching key to a stored order (station + count only).

**Basetool fields with no in-game source**

`owner`, `missionId`, `otherExpenses`, `oreSales`, `owningOrgUnit`, `storedAt`, `version`, and
`outputMaterial` (derived from master data, not read). `refiningMethod` and `expenses` are shown on
SETUP; whether a running-order panel shows them is not established by any source in this
repository.

**Validation a client must expect**

Location must be an offered refinery; every good needs a resolved RAW input material, `quality` in
0…1000 and `outputQuantity ≥ 1`; at least one good; `expenses ≤ 1e9`. The draft surfaces every
violation as an issue instead of a 400; only envelope problems (`schemaVersion ≠ 1`, no order,
non-`SETUP` first order) are 400s (REQ-REFINERY-003, `drafts.md`). Nothing is written until the
member saves the reviewed form (REQ-REFINERY-002/018).

## Open points for a decision

- Accept a running-order panel (new `panelType` and a `remainingMinutes` or `endsAt` field) within
  v1 as an optional field, or as `schemaVersion: 2` (ADR-0008 evolution rule).
- Allow id-based references (`bt` / `scGuid` from `catalog/resolve`) next to `rawMaterialName`, and
  a location/method reference, so a client that already resolved does not re-run fuzzy matching.
- Carry and store an external work-order key to prevent duplicates and to link the Game.log
  completion notice.
- Decide whether an imported running order may be created `IN_PROGRESS` (today REQ-REFINERY-022
  forces `OPEN`).
