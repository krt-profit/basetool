> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-07.
> **Owner area:** P4K · **Related ADRs:** ADR-0335, ADR-0336, ADR-0337, ADR-0338, ADR-0339,
> ADR-0340, ADR-0341, ADR-0342, ADR-0343, ADR-0344, ADR-0345, ADR-0346, ADR-0347

# P4K catalogue import

## Context & goal

The P4K catalogue is the JSON export p4kx writes from the game archive (`Data.p4k`). This spec
governs how the Basetool imports it and which source leads which attribute: the catalogue leads
everything the game archive holds (identity by DataForge GUID, class names, names, type, size,
grade, class, mass, crew, recipes, categories, the starmap, loadouts, paints, shops); UEX stays
leading for what the archive does not hold (prices, stock, terminals, refinery yields, store URLs);
the SC Wiki fills gaps and cross-checks, and disagreements are reported, never overwritten in
silence (REQ-DATA-015's `external_sync_report`).

The catalogue's shape is its contract, read and not restated here:
[`docs/basetool-catalog.md`](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)
of p4kx, `schemaVersion` 2. Field names below are the contract's.

The decisions come from the wayfinder map #2884 and its tickets (#2885–#2897, #2901, #2902,
#2927, #3027–#3029).

> [!note] Partly implemented
> REQ-P4K-001…016 and REQ-P4K-045 are implemented (#3031, #3033; the sweep's category tables
> join with their areas), REQ-P4K-017…019 are implemented (#3035),
> REQ-P4K-020…023 are implemented (#3036), REQ-P4K-029 is implemented (#3039), REQ-P4K-031 and
> REQ-P4K-032 are implemented (#3042), REQ-P4K-033 is implemented (#3041), REQ-P4K-039 is
> implemented (#3043), REQ-P4K-034…038 are implemented (#3044), REQ-P4K-040 and REQ-P4K-041 are
> implemented (#3045), REQ-P4K-042…044 are implemented (#3046), REQ-P4K-047 is implemented
> (#3034); every other
> requirement is **planned**, its open acceptance boxes and "Enforced by (planned)" test classes
> included. The order of delivery is the map's migration order: the gate, the run record and the
> UUID clean-up first (REQ-P4K-001…007), then the sweep and the change report, then the areas one
> by one. The import is `P4kImportService` with one reconciler per kind (`P4kManufacturerReconciler`,
> `P4kItemReconciler`, `P4kShipReconciler`, `P4kCommodityReconciler`, `P4kBlueprintReconciler`)
> sharing `P4kRowMatcher`, then `P4kPaintReconciler` and `P4kLoadoutReconciler`, which link to the
> rows the base kinds matched, the starmap tree and its UEX links (`P4kStarmapReconciler`,
> `P4kStarmapLinker`), and the shops and their UEX terminal links (`P4kShopReconciler`,
> `P4kShopLinker`); the wire format is `catalogue.internal.p4k`.

Related requirements in other specs: the monitoring of the import (REQ-OBS-022 and REQ-OBS-023 in [`observability.md`](observability.md)), the catalogue audit domain (REQ-AUDIT-001 in
[`audit.md`](audit.md)), the exchange guard for game texts and media (REQ-XCH-050 in
[`external-exchange.md`](external-exchange.md)), the `SHIP_TYPE` reference resolution (REQ-XCH-012)
and the personal-blueprint name matching (REQ-INV-006, REQ-INV-019 in
[`blueprint-import-name-matching.md`](blueprint-import-name-matching.md)). The tombstone census of
the page-walking syncs stays REQ-DATA-014; the P4K sweep has its own rule here (REQ-P4K-011).

## Upload and import runs

### REQ-P4K-001 — the upload gate accepts only a LIVE catalogue of version 2 that names its Build

The upload endpoint reads only the head of the file with a streaming parser (`schemaVersion` and
`source` come first) and answers `400` problem+json, creating no job and storing no payload, when
`schemaVersion` is not `2`, `source.channel` is not `LIVE`, or `source.build` is `null`. The Channel
rule is hard-wired in every profile: no property, no allow-list; tests use LIVE fixtures. A
catalogue of `PTU`, `EPTU`, `TECH-PREVIEW` or `HOTFIX` is never read in any form, not even as a
preview. Each refusal is logged and counted in `basetool_p4k_import_rejected_total{reason}`
(`schema_version`, `channel`, `build_missing`; see REQ-OBS-022 and REQ-OBS-023).

**Acceptance**

- [x] A catalogue with `schemaVersion` 1, 3 or none is a `400`; no `p4k_import_job` row exists.
- [x] A `PTU` catalogue is a `400` under the `dev`, `test` and `prod` profiles alike.
- [x] A LIVE catalogue whose `source.build` is `null` is a `400`.
- [x] Each refusal increments the counter with its reason and logs one line.

The refusal is the problem code `P4K_UPLOAD_REJECTED` with the extension property `reason`. A
head that cannot be read (not JSON, a broken compressed stream) counts as `schema_version`, and
a `changeNumber` that is no decimal integer as `build_missing`.

**Enforced by:** `P4kUploadGateTest`, `P4kImportControllerMvcTest` · **Code:** `P4kUploadGate`,
`P4kRejections`, `P4kUploadRejectedException` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
**Issues:** #2885

### REQ-P4K-002 — the upload accepts plain, gzip and zstd JSON within two size limits

The upload accepts `.json`, `.json.gz` and `.json.zst`, recognised by their magic bytes, not by the
file name. The 64 MB limit applies to the uploaded (compressed) file; the decompressed content is
capped at 1 GB, an `@ConfigurationProperties` default that also guards against decompression bombs.
The parser reads the decompressed stream without holding it in memory, and the payload is stored
compressed. zstd is decoded in the backend by zstd-jni, whose jar bundles the native library for
every image platform (`linux/amd64`, `linux/arm64`, glibc and musl), with a window of at most
2^27 = 128 MiB, the window p4kx writes (`--long=27`); a larger window is unreadable and refused as
`schema_version`. A file above either limit is refused by the gate with reason `too_large`. In practice a
LIVE catalogue arrives as `.json.zst` (p4kx writes level 19 with long-distance matching): the LIVE
export of p4kx 0.4.2 is 214 MB as JSON and 3.9 MB as `.json.zst`.

The upload limit is the contract's `UPLOAD_LIMIT`, 64,000,000 bytes (`P4kUploadGate`); the
decompressed limit is `krt.p4k-import.decompressed-limit-bytes` (`P4kImportProperties`,
1,000,000,000 bytes). The gate drains the decompressed stream once to enforce that limit. A plain
upload is stored gzipped, a compressed one as it came. zstd-jni extracts its library to
`java.io.tmpdir` on first use; the backend's `/tmp` is the container runtime's tmpfs, writable
and without `noexec`. A host that mounts it `noexec` sets `-DZstdTempFolder=<exec-allowed dir>`.

**Acceptance**

- [x] The same catalogue as `.json`, `.json.gz` and `.json.zst`, each under a wrong extension,
  imports to the same result.
- [x] A compressed file above 64 MB, and one whose decompressed content passes 1 GB, is refused with
  `too_large` before the content is read to the end.
- [x] A LIVE-sized `.json.zst` fixture imports with a heap bound well below its decompressed size.

**Enforced by:** `P4kCatalogueStreamTest`, `P4kUploadGateTest` · **Code:** `P4kCompression`,
`P4kCatalogueReader` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
**Issues:** #2887

### REQ-P4K-003 — the import reads the contract's additive rules and nothing of the old format

- **Unknown kinds and fields are ignored**, as the contract's additive rule allows.
- **An unknown value of a closed value set reads as `null`** (a new `playability`, `itemClass`,
  `vehicleKind`, shop `kind`, …). For an item or a ship that means the review list (REQ-P4K-009),
  never seeding. The change report counts the unknown values per field.
- **The old flat format of the KRT P4K Reader is gone:** its DTOs and code paths are removed; such a
  file fails the gate for lack of `schemaVersion`.

**Acceptance**

- [x] A catalogue with an extra kind and extra fields on every entity imports like one without.
- [x] An item whose `playability` is `"crew"` (unknown) is treated as `null` and counted.
- [x] No class of the old format remains (`P4kCatalogDto` and its siblings read version 2 only).

An unknown value is an `UNKNOWN_VALUE` report row (field and value) and counts in the run's
`unknownValues`. The fixture `p4k/catalogue-v2-live-trimmed.json` (a trimmed LIVE export) validates
against the checked-in schema `p4k/basetool-catalog.schema.json` of p4kx 0.4.2.

**Enforced by:** `P4kCatalogueContractTest`, `P4kImportServiceSeedingTest` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) · **Issues:** #2885,
#2887

### REQ-P4K-004 — an APPLY never rolls the catalogue back to an older Build

APPLY refuses a catalogue whose `source.build.changeNumber`, compared as a number, is lower than
that of the last applied run, with reason `older_build` (counted like the gate's refusals).
Re-applying the same Build is allowed; the import is idempotent. Only LIVE runs exist, so the
comparison is always within one Channel, where the contract guarantees the number is monotonic.

**Acceptance**

- [x] After a run of change `12913470`, an APPLY of change `12900000` is refused and writes nothing.
- [x] Re-applying change `12913470` succeeds and changes no row a second time.
- [x] `"9"` is lower than `"10"` (numeric, not text, comparison).

The refusal fails the APPLY job with a message; a re-applied run moves `p4k_seen_run_id` to the
new run and writes no other value.

An APPLY runs under the `SyncCoordinator` lock the Wiki and UEX syncs share, so it never runs
beside their duplicate merges (ADR-0349); it waits up to `krt.sync.coordinator.max-wait-ms`, and
when that elapses the job fails with a message and writes nothing. A PREVIEW takes no lock.

**Enforced by:** `P4kImportServiceBuildOrderTest`, `P4kImportJobRunnerTest` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) · **Issues:** #2885

### REQ-P4K-005 — every successful APPLY is recorded as a run, and every matched row names it

- **`p4k_import_run`:** one row per successful APPLY, holding the Channel, the Build (`branch`,
  `version`, `changeNumber`, `buildId`), the patch version and its origin (below), `toolVersion`,
  `exportedAt`, `completeness` per kind, the result counts per kind (matched, unmatched, added,
  vanished, reappeared, changed), the uploading admin and the apply time. A PREVIEW writes no run.
  Runs are never pruned; the reference to `p4k_import_job` is `ON DELETE SET NULL`, since jobs are
  pruned by age.
- **Patch version:** `source.build.publicPatch` when it is set; p4kx reads it from the RSI
  Launcher's install log and writes `null` until the launcher logs this build. The archive holds no
  public patch number (p4kx ADR-0053), and a branch serves several public patches (4.7.0, 4.7.1 and
  4.7.2 all came from `sc-alpha-4.7.0`). When `publicPatch` is `null`, the run keeps the version
  of `source.build.branch` as a **lower bound**, only when the branch matches exactly
  `sc-alpha-<major>.<minor>.<patch>` (`sc-alpha-4.10.2` → at least `4.10.2`); otherwise the
  version is unknown and the run is no comparison basis for "import due" (REQ-P4K-047). The run
  records whether its version is a public patch or a branch lower bound, and the UI shows a lower
  bound marked as the branch, never as a public patch.
- **`p4k_seen_run_id`:** a foreign key to `p4k_import_run` on every P4K-keyed table (the five base
  kinds, the commodity and blueprint categories, starmap objects, shops), set on every row the
  catalogue matched in that run, changed or not. The Build of a row is read through the join; no
  text column copies it.

**Acceptance**

- [x] An APPLY writes one run with the Build, completeness and counts; a PREVIEW writes none.
- [x] A run with `publicPatch` `4.10.2` records the public patch `4.10.2`; one with `publicPatch`
  `null` and branch `sc-alpha-4.10.2` records the branch lower bound `4.10.2` and shows it as a
  branch; one with branch `sc-alpha-4.10` or `sc-beta-4.10.2` records no version.
- [x] Every matched row carries the run's id, also when no value changed.
- [x] Pruning the job leaves the run with a `null` job reference.

The run is `p4k_import_run` (V342) with one `p4k_import_run_kind` row per kind (completeness, its
Filter and the six counts); `p4k_seen_run_id` exists on the five base kinds, `starmap_object` and
`shop`; the category tables add it with their areas.

**Enforced by:** `P4kImportRunTest` · **ADR:**
[ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) · **Issues:** #2885

### REQ-P4K-006 — a partial catalogue is imported, and only its complete kinds are swept

A catalogue whose `completeness.<kind>` is `partial` (written with a p4kx Filter) is accepted.
Matching runs as usual; the run records the completeness per kind, and the orphan sweep acts only on
a `complete` kind (REQ-P4K-011).

**Acceptance**

- [x] A catalogue with `items` partial and `ships` complete updates matched items and ships, and
  sweeps ships only.

**Enforced by:** `P4kImportServiceSweepTest` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md), [ADR-0336](../adr/0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) ·
**Issues:** #2885

### REQ-P4K-007 — the first version-2 APPLY makes the P4K GUID the unique identity of every kind

The clean-up of diverging canonical UUIDs happens at the first `schemaVersion` 2 APPLY, not in a
separate migration. That run sets the P4K GUID (`p4k_uuid`) as the identity of each matched row,
keeps the Wiki UUID in `external_uuid` (`game_item`, `ship_type`) or `scwiki_uuid` (the other
kinds) and records each mismatch in the change report (`IDENTITY_MISMATCH`). Matching runs per
kind: the P4K GUID, then the Wiki UUID, then a class name and a name that each identify exactly
one row and one record of the catalogue. A row that two P4K GUIDs would claim, or a P4K GUID that
two rows would claim (also two catalogue records sharing a name, as three commodities named
"SLAM" do), is not resolved by the import: the conflict goes to the review list (REQ-P4K-016) and
the admin merges or splits; the import never merges. The Wiki/UEX duplicate merge
(`ItemReconciliationService.mergeExisting`, ADR-0349) keeps the same rule: it never joins two items
with different P4K GUIDs but opens a `GUID_CONFLICT` case for each GUID and leaves both rows as they
are; a GUID only the retired row holds moves with its P4K columns onto the survivor before the
retired row is archived; and a survivor with a P4K GUID, like a P4K-seeded one, keeps its name and
manufacturer against the UEX values. `p4k_uuid` becomes unique per kind in the
same release (V343): the migration first clears `p4k_uuid` on every row of a duplicate group, then
adds the unique index, and the next APPLY matches those rows again.

**Acceptance**

- [x] A row matched by class name whose Wiki UUID differs from the P4K GUID ends with the P4K GUID
  as `p4k_uuid`, the Wiki UUID in `external_uuid` / `scwiki_uuid` and a report row of the
  mismatch.
- [x] Two rows claiming one P4K GUID produce a review case and keep their values.
- [x] The unique-constraint migration clears every duplicate group's `p4k_uuid`, then enforces
  uniqueness.
- [x] The Wiki/UEX duplicate merge refuses two different P4K GUIDs with a review case per GUID,
  moves a GUID only the retired row holds, and keeps a P4K-matched survivor's name.

**Enforced by:** `P4kIdentityCleanupTest`, `V343MigrationTest`, `ItemReconciliationServiceTest`,
`P4kMergeConflictRecorderTest` · **Code:** `P4kRowMatcher`, `P4kMergeConflictRecorder` ·
**ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) · **Issues:** #3029

## Matching and seeding

### REQ-P4K-008 — the import matches and enriches; a record without a match creates nothing

For every kind the import enriches and reconciles the rows it matches; a catalogue record without a
match creates no row unless a kind's seeding rule below says so. The `seedNew` request parameter,
the `seed_new` column of `p4k_import_job`, the admin checkbox and the seeding code paths of the old
import are removed. Each run records the number of unmatched records per kind, and the change report
lists them by GUID (`UNMATCHED`, REQ-P4K-014), so an admin can add a missing row by hand.

The kind-specific seeding rules are REQ-P4K-009 (items, ships), REQ-P4K-021 (manufacturers),
REQ-P4K-026 (commodities) and REQ-P4K-028 (blueprints).

**Acceptance**

- [x] An APPLY without the seeding checkbox creates no row of any kind and lists every unmatched
  record in the report.
- [x] The run's unmatched count per kind equals the number of `UNMATCHED` report rows.

**Enforced by:** `P4kImportServiceMatchOnlyTest` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) · **Issues:** #2886

### REQ-P4K-009 — items and ships are seeded only when playable, behind a per-run checkbox

APPLY offers a per-run admin checkbox "create new items and ships", **off by default**; the preview
shows how many rows it would create. With it ticked, an unmatched item or ship is created only when
its `playability` is `player`:

- `ai` and `mission` are never created and only counted;
- `null` and `special` are **unclear**: the record goes on the review list (REQ-P4K-016) instead of
  being created; an unknown value reads as `null` (REQ-P4K-003).

A created row is visible to members at once; items and ships carry no review flag of their own.

**Acceptance**

- [x] With the box ticked, a `player` ship is created; an `ai` and a `mission` ship are counted and
  not created; a `special` and a `null` ship each open a review case.
- [x] Without the box, nothing is created and the preview still shows the would-be count.

The box is the APPLY parameter `createItemsAndShips`; a preview counts as if it were ticked. A
created row has `source_systems = P4K`, and a created ship needs a name no ship holds yet.

**Enforced by:** `P4kImportServiceSeedingTest` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) · **Issues:** #2886,
#2897

### REQ-P4K-010 — rows an earlier seeding created are kept, and those no rule would create are reviewed

No row created by an earlier `seedNew` run is deleted or soft-deleted automatically, for any kind:
members may reference it in inventory, hangar or orders. Where the current seeding rule of its kind
would not create such a row, it goes on the review list and the admins decide (REQ-P4K-016).

**Acceptance**

- [x] A previously seeded `ai` ship is not stamped and opens a review case on the next APPLY.

An earlier seeding is recognised by `source_systems = P4K` on `game_item`, `ship_type` and
`material`; `manufacturer` and `blueprint` carry no such marker, so their seeded rows are matched
like any other.

**Enforced by:** `P4kImportServiceSeedingTest` · **ADR:** [ADR-0335](../adr/0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) · **Issues:** #2886

## Orphan sweep, change report and review

### REQ-P4K-011 — a complete kind sweeps the rows it no longer lists, on a valid census only

The sweep runs per kind on a **valid census**: the kind's `completeness` is `complete`, the run
carries at least one record of it, and no GUID occurs twice within it (a duplicate voids the census:
a `WARN`, and the sweep of that kind is skipped). It sets `p4k_deleted_at` only on rows with a
`p4k_uuid` that this run did not match; rows the P4K never knew (UEX-only, Wiki-only) are never
stamped. A `partial` kind is neither stamped nor cleared, and the report records "sweep skipped:
partial". A later match sets `p4k_deleted_at` back to `null` and the report lists the row as
`REAPPEARED`; the GUID stays, so no new row is created.

The sweep covers the five base kinds (items, ships, manufacturers, commodities, blueprints), the
two category tables (commodity categories from `resourceGroups`, REQ-P4K-025, and blueprint
categories from `blueprintCategories`, REQ-P4K-027), the starmap objects (REQ-P4K-034) and the shops
(REQ-P4K-042).

**Acceptance**

- [x] A complete kind stamps a P4K-known row it no longer lists and leaves a UEX-only row alone.
- [x] A kind with a repeated GUID, an empty kind and a partial kind each stamp nothing, and the
  report says why.
- [x] A stamped row matched again is cleared and reported `REAPPEARED`.

`P4kSweeper` runs after the reconcilers, in the APPLY's transaction; a preview computes the same
outcome and writes nothing. A row whose GUID the catalogue lists is never stamped, also when the
import held it for review. The stamp goes through the entity, so the row's version and update time
move. Each kind's outcome is `p4k_import_run_kind.sweep` (`SWEPT`, `SKIPPED_PARTIAL`,
`SKIPPED_EMPTY`, `SKIPPED_DUPLICATE_GUID`, `SKIPPED_MASS_GUARD`) and, when skipped, a
`SWEEP_SKIPPED` report row with the kind's P4K-known rows and the rows it would have stamped.

The starmap objects are the target `STARMAP_OBJECT`; their name is a game text, so a report row
names them by class name, and no other source lists them.

The shops are the target `SHOP`, keyed by the name-based UUID of their `key`; a report row names a
shop by its entity name, and no other source lists them.

> [!note] Planned — the category tables
> The commodity and blueprint category tables (REQ-P4K-025, REQ-P4K-027) do not exist yet; each
> joins the sweep as one `P4kSweepTarget` when its area adds the table with `p4k_uuid`,
> `p4k_seen_run_id`, `p4k_deleted_at` and `p4k_hidden_at`.

**Enforced by:** `P4kImportServiceSweepTest` · **Code:** `P4kSweeper`, `P4kSweepTarget`,
`P4kSweepOutcome` · **ADR:** [ADR-0336](../adr/0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) · **Issues:** #2888

### REQ-P4K-012 — a sweep that would stamp more than 5 % of a kind needs the admin's confirmation

The preview shows per kind how many rows the sweep would stamp. When that exceeds **5 %** of the
kind's P4K-known rows (a code constant, not a property), APPLY stamps that kind only when the admin
ticks a separate "apply marking anyway" box. Without it the import runs normally, only that kind's
sweep is skipped, and the report records it. Each override is an audit event
(`CATALOGUE_P4K_MASS_GUARD_OVERRIDDEN`, REQ-P4K-046).

**Acceptance**

- [x] With 6 % to stamp and no box, the kind is matched and updated but not swept; the report says
  "sweep skipped: mass guard".
- [x] With the box, the kind is swept and one audit event names the kind, the count and the share.
- [x] 5 % exactly sweeps without the box.

The box is the APPLY parameter `overrideMassGuard`, stored on the job (`override_mass_guard`); the
audit event's subject is the run, its details `kind`, `count` and `sharePercent`.

**Enforced by:** `P4kImportServiceSweepTest`, `P4kImportReportControllerMvcTest` · **ADR:**
[ADR-0336](../adr/0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) · **Issues:** #2888, #2901

### REQ-P4K-013 — a row the P4K stamped leaves every picker and keeps its references

A row with `p4k_deleted_at` set drops out of every picker and search used to create a new
reference. Existing references (inventory, hangar, job orders, missions) stay and show a "no longer
in the game" notice; nothing is deleted. The P4K stamp wins over UEX and the Wiki: a stamped row is
hidden even while UEX or the Wiki still list it as live, and the report flags that case ("VANISHED,
still live in UEX/Wiki"). The other sources' markers stay independent, and UEX stays leading for
prices and terminals.

**Acceptance**

- [x] A stamped item is absent from the item pickers and searches; an inventory row referencing it
  still renders, with the notice.
- [x] A stamped ship that UEX still lists is hidden and reported with the "still live" flag.

A row is retired when `p4k_deleted_at` (the sweep) or `p4k_hidden_at` (a hide, REQ-P4K-016) is
set. The pickers that filter it: the Lager and item-order item pickers
(`BlueprintRepository.findItemsWithActiveBlueprint`, `findOrderableItems`), the blueprint pickers,
the ship-type, manufacturer and material pickers and searches. The notice is `noLongerInGame` on
`InventoryGameItemReferenceDto`, `GameItemReferenceDto` and `ShipTypeDto`, shown in the Lager, the
hangar, the job-order items, handovers and item collection, and the mission units.

**Enforced by:** `P4kStampedRowVisibilityTest`, `JobOrderItemDetailRenderTest`
· **ADR:** [ADR-0336](../adr/0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) · **Issues:** #2888

### REQ-P4K-014 — every run keeps a change report, and the report is never pruned

`p4k_import_change` references `p4k_import_run` and holds one row per change: kind, GUID, change
type, field name, old value, new value, and the record's `class_name`, `name_en` and
`record_path`. The change types:

- `ADDED`, `VANISHED`, `REAPPEARED`, compared by GUID;
- `CHANGED`, old → new, only for the fields the import writes; the old value is read from the row
  before it is overwritten, so no catalogue snapshot is kept;
- `UNMATCHED`: one row per catalogue record with no Basetool row, with GUID, `className`, `name.en`
  and `recordPath`;
- `REVIEW`: a review case this run found (REQ-P4K-016).

The report also records skipped sweeps (partial, void census, mass guard), the identity mismatches
of REQ-P4K-007, the unknown closed-set values per field (REQ-P4K-003) and each disagreement the area
rules route to it. `external_sync_report` receives only the run summary. Report rows are never
pruned; they hold identifiers, short names and fact values only, never a game text.

**Acceptance**

- [x] A run that renames an item, adds a ship and vanishes a blueprint writes one `CHANGED`, one
  `ADDED` and one `VANISHED` row with the right old and new values.
- [x] No report row holds a `description` or `displayType` value.

A `CHANGED` row names the column the import wrote (`class_name`, `manufacturer_id`, `mass`,
`name_key`, …); a game-text column (`description_en`, `description_de`, `description`) is logged
by name with both values `null`. An item's rename shows as its `name` and its `name_key`.
`position` keeps the order the run found its rows in, and `still_live_elsewhere` carries the
"still live in UEX/Wiki" flag of a `VANISHED` row.

**Enforced by:** `P4kChangeReportTest` · **Code:** `P4kImportReport`, `P4kRowWriter` · **ADR:** [ADR-0336](../adr/0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) · **Issues:** #2888

### REQ-P4K-015 — the admin page lists the runs and shows each report, the preview included

`/admin/p4k-import` lists the applied runs (Build, time, admin, counts per kind). Opening a run shows
its report, filterable by kind and change type, paginated and updated in place via `krtFetch`
(REQ-FE-001…010). The sync-reports page gets a P4K tab. A PREVIEW computes the same report and shows
it on the job without writing `p4k_import_run` or `p4k_import_change`; the preview expires with the
job after 7 days.

**Acceptance**

- [x] Filtering a run's report by kind and change type, and paging it, swaps the list without a
  page reload.
- [x] A preview job shows the report it would write; after APPLY the run shows the same rows.

The run list is `GET /api/v1/catalog/admin/import/p4k/runs`, a run's report
`GET …/runs/{id}/changes` and a preview's `GET …/jobs/{id}/changes`, both filtered by `kind` and
`type`; the page proxies them under `/admin/p4k-import` and names each run's admin. A preview's
report is stored gzipped beside its upload (`p4k_import_job_payload.preview_report`). A preview
computes as if "create new items and ships" were ticked and without the mass-guard override, so
an APPLY with other choices writes other rows. The P4K tab of the sync-reports page comes with the
Wiki/UEX reconciliation hardening (#3053).

**Enforced by:** `AdminP4kImportPageControllerMvcTest`, `P4kImportReportControllerMvcTest`,
`P4kChangeReportTest`, `P4kImportReportE2eTest` · **ADR:** [ADR-0336](../adr/0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) · **Issues:** #2888

### REQ-P4K-016 — a review case is a lasting status on the row, resolved as keep or hide

The review list is a status on the case (`open` / `reviewed`), not a per-run copy: `p4k_review_case`
holds one case per kind, GUID and reason, with the run that found it first and last. Each run sets newly
found cases to `open` and logs them as `REVIEW` in its report; `/admin/p4k-import` lists the open
cases. A case arises from:

- a row an earlier seeding created that no current rule would create (REQ-P4K-010);
- an unmatched item or ship of unclear playability (REQ-P4K-009);
- a manufacturer name conflict (REQ-P4K-020) and a GUID conflict (REQ-P4K-007);
- a starmap link and a shop link that is ambiguous or not found (REQ-P4K-035, REQ-P4K-043);
- a blueprint ingredient whose `resourceGuid` matches several commodities (REQ-P4K-024);
- a personal-blueprint row that resolves to no produced-item GUID or to several (REQ-P4K-029).

An admin resolves a case as **keep** or **hide**. Hide works like the P4K stamp (REQ-P4K-013): the
row leaves the pickers and its references stay. A hide is an admin decision a later match never
lifts. The row records who resolved the case and when, and each resolution is an audit event
(REQ-P4K-046).

**Acceptance**

- [x] A case found by two runs is one open case, logged as `REVIEW` in both reports.
- [x] "Hide" removes the row from the pickers; the next APPLY that matches it leaves it hidden.
- [x] The resolution stores the admin and the time and writes one audit event.

`p4k_review_case` holds `status` (`OPEN`, `REVIEWED`), `resolution` (`KEEP`, `HIDE`),
`resolved_by` and `resolved_at`; a resolution echoes the case version (`409` when stale), and a
resolved case is never resolved again. A hide stamps `p4k_hidden_at` on the row. A GUID conflict
between two rows, such as one the SC Wiki / UEX duplicate merge refuses (`P4kMergeConflictRecorder`),
is one case per GUID about the row holding it, naming the row it collides with in `other_row_id`.

**Enforced by:** `P4kReviewServiceTest`, `AdminP4kImportPageControllerMvcTest`,
`P4kImportReportControllerMvcTest`, `P4kImportReportE2eTest` · **Code:** `P4kReviewService` · **ADR:**
[ADR-0336](../adr/0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md) · **Issues:** #2886, #2888

## Items

### REQ-P4K-017 — the P4K leads a game item's attributes, and the SC Wiki fills only its gaps

On a matched `game_item` the import writes `type`, `sub_type`, `size`, `grade` (REQ-P4K-018),
`item_class` (REQ-P4K-019), `mass`, the manufacturer (by `manufacturerGuid`), `name`, `name_de` and
the name keys. The SC Wiki item sync writes these columns only where the P4K value is `null`; a Wiki
value that differs from a P4K value goes to `external_sync_report` and the P4K value stays. Columns
the catalogue does not carry keep their source: the dimensions stay with the Wiki, the UEX category
and every price or terminal field with UEX. The admin acts on a disagreement at its source; there is
no override column and no review status for item disagreements.

**Acceptance**

- [x] A P4K `size` 2 survives a Wiki sync that serves `size` 3, and the disagreement is reported.
- [x] A Wiki `mass` is written where the P4K `mass` is `null`.
- [x] A P4K APPLY leaves `dimension_x/y/z` and the UEX category alone.

The P4K's own value of each attribute is kept in a column of its own (`p4k_name`,
`p4k_manufacturer_id`, `p4k_type`, `p4k_sub_type`, `p4k_size`, `p4k_grade_raw`, `p4k_item_class`,
`p4k_mass`; V346), mirrored by every APPLY, a `null` included. The displayed columns are `name`,
`manufacturer_id`, `wiki_type`, `wiki_sub_type`, `size_class`, `grade`, `item_class` and `mass`; an
APPLY overwrites one only with a non-null P4K value, so a P4K value that turns `null` leaves the
displayed value for the Wiki to refresh. "The P4K value is `null`" means the `p4k_*` column is
`null`; for the grade it is the letter `p4k_type` and `p4k_grade_raw` map to. UEX writes `name` and
the manufacturer only while `p4k_name` / `p4k_manufacturer_id` are `null`, and the Wiki/UEX
duplicate merge carries the `p4k_*` columns with the GUID onto the survivor, where they lead. A
disagreement is the sync-report event `P4K_DISAGREEMENT` with `column=…;stored=…;source=…`; strings
compare ignoring case. A matched row is not rewritten when its values already agree, and a preview
writes nothing. The import writes `name_de` by REQ-P4K-023.

**Enforced by:** `P4kItemPrecedenceTest`, `ScWikiItemFieldWriterTest`, `UexItemSyncServiceTest`,
`ItemReconciliationServiceTest`, `V346MigrationTest` · **Code:** `P4kItemReconciler`,
`P4kItemValues`, `ScWikiItemFieldWriter` · **ADR:** [ADR-0337](../adr/0337-the-p4k-catalogue-leads-a-game-items-attributes-and-the-sc-wiki-fills-gaps.md)
(supersedes [ADR-0283](../adr/0283-a-game-items-size-grade-and-class-come-from-the-sc-wiki-only.md); replaces REQ-DATA-024) · **Issues:** #2889

### REQ-P4K-018 — the grade letter comes from the integer for five component types only, and the raw integer is kept

The catalogue carries the raw DataForge `grade` integer. The import maps **1 → A, 2 → B, 3 → C,
4 → D** only for the item `type`s `Cooler`, `PowerPlant`, `QuantumDrive`, `Radar` and `Shield`.
Everywhere else the P4K grade counts as `null` for the letter column, so the Wiki fills it. The
import never reads the `Grade:` line of a description (a game text).

The contract names 19 grade-1 items whose `Grade:` line disagrees with the integer only by type,
not by GUID or class name, so the import cannot single them out. Seven are of an unmapped type
(`JumpDrive`, `QuantumInterdictionGenerator`, `Misc`, `WeaponMining`) and get no letter from the
P4K. The others (a `Radar`, two `Cooler`s, and those of the nine ship-specific `Bespoke`
components whose type is one of the five) map like their type, to `A`; a differing Wiki letter is
reported as a disagreement (REQ-P4K-017).

A nullable `p4k_grade_raw` stores the integer for every matched item, whatever its type. It is a
fact value, may reach the exchange, and has no UI. A value outside 1–4 (5 to 7 on
`WeaponAttachment`, anything higher) leaves the letter `null`, keeps the raw value and counts in
`basetool_catalogue_item_grade_unknown_total`.

**Acceptance**

- [x] A `Shield` of grade 3 stores `C` and `p4k_grade_raw` 3; a `WeaponPersonal` of grade 1 stores
  no letter from the P4K and `p4k_grade_raw` 1.
- [x] A grade-1 item of an unmapped type the contract lists among the 19 (`JumpDrive`,
  `QuantumInterdictionGenerator`, `Misc`, `WeaponMining`) stores no letter from the P4K.
- [x] A grade 6 counts once in the unknown-grade counter and keeps `p4k_grade_raw` 6.

An unknown grade is also an `UNKNOWN_VALUE` row of the change report (field `grade`); the counter
moves on an APPLY only, once per matched item.

**Enforced by:** `P4kGradeMappingTest`, `P4kItemPrecedenceTest`, `ScWikiItemFieldWriterTest` ·
**Code:** `P4kGradeMapping` · **ADR:** [ADR-0337](../adr/0337-the-p4k-catalogue-leads-a-game-items-attributes-and-the-sc-wiki-fills-gaps.md) · **Issues:** #2889

### REQ-P4K-019 — an item's class comes from the P4K, a `null` never clears it, and an unknown value is counted

`itemClass` maps to `ShipComponentClass` as REQ-DATA-024 described for the Wiki (trimmed,
case-insensitive, the CHECK constraint `ck_game_item_item_class`). A `null` `itemClass` (an FPS
weapon, an item without the line) never clears a stored class; the Wiki fills it. An unknown value is
stored as no class, logged once per value and counted in
`basetool_catalogue_item_class_unknown_total`. The share of `null` per run is a gauge of the P4K
import monitoring.

**Acceptance**

- [x] `"Military"` stores `MILITARY`; a `null` leaves a stored `STEALTH` in place.
- [x] An unknown value stores no class and increments the counter once per item.

"Stored as no class" means `p4k_item_class` stays `null`, so a stored class stays and the Wiki
fills it. An unknown class is also an `UNKNOWN_VALUE` row of the change report (field
`itemClass`); the counter and the log line move on an APPLY only. `ItemClassResolver` serves both
sources. `p4k_item_class` carries the CHECK `ck_game_item_p4k_item_class`.

**Enforced by:** `P4kItemPrecedenceTest`, `ShipComponentClassTest`, `ScWikiItemFieldWriterTest` ·
**Code:** `ItemClassResolver` · **ADR:** [ADR-0337](../adr/0337-the-p4k-catalogue-leads-a-game-items-attributes-and-the-sc-wiki-fills-gaps.md) ·
**Issues:** #2889

## Manufacturers

### REQ-P4K-020 — the P4K leads a manufacturer's identity, name, abbreviation and description

- **Identity and match order:** `manufacturer.p4k_uuid` is unique and the first match key, then the
  UEX alias (`manufacturer_uex_company`, still the join key for UEX items and vehicles), then the
  name. The abbreviation is never an identity (REQ-DATA-004). `scwiki_uuid` stays a cross-reference.
  The catalogue carries no UEX id, so the import matches by P4K GUID, then by SC Wiki UUID
  (REQ-P4K-007), then by name; the UEX alias is the key the UEX sync matches by, and the import
  adopts a row UEX created by its name. The SC Wiki sync matches a row by its P4K GUID before its
  own UUID.
- **Name and abbreviation:** on a row with a P4K GUID only the import writes `name` and
  `abbreviation` (from `code`); UEX writes them only on rows the P4K does not know. A UEX/P4K
  disagreement goes to `external_sync_report` as `P4K_DISAGREEMENT` with
  `column=…;stored=…;source=…`, the source being UEX or the SC Wiki; a value equal ignoring
  case is no disagreement.
- **Name conflict:** when another row already holds the P4K name (`name` is unique), the import does
  not rename; it records the conflict in the report and opens a review case (`NAME_CONFLICT`, about
  the row the import would have renamed). Merging two rows is an admin action; the import never
  merges.
- **Description:** the P4K leads `description` and a new `description_de`, both game texts
  (REQ-P4K-033). UEX no longer writes the description; rows without a P4K match keep theirs.
- **UEX keeps** `nickname`, `industry`, `wiki`, `is_item_manufacturer`, `is_vehicle_manufacturer` and
  the alias table.
- **The SC Wiki manufacturer sync** is a cross-check: it writes only its `scwiki_*` columns, never the
  name or abbreviation, and is never an identity source; a name or code that differs from the P4K
  goes to `external_sync_report`.

**Acceptance**

- [x] A UEX sync leaves the name of a P4K-known manufacturer as the P4K wrote it and reports the
  difference.
- [x] A P4K name already held by another row leaves both rows unchanged and opens a review case.
- [x] A UEX sync no longer writes `description`.

**Enforced by:** `P4kManufacturerPrecedenceTest`, `UexManufacturerServiceTest`,
`ScWikiManufacturerSyncServiceTest`, `SyncReportServiceTest` · **Code:**
`P4kManufacturerReconciler`, `UexManufacturerService`, `ScWikiManufacturerSyncService`,
`SyncReportService#logP4kDisagreement` · **ADR:** [ADR-0338](../adr/0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md) · **Issues:** #2890

### REQ-P4K-021 — a manufacturer is created only for an item or ship seeded in the same run

The import creates a manufacturer only when an item or ship seeded in the same run (REQ-P4K-009)
references it. Pseudo-manufacturers are never referenced by anything playable and so are never
created; unmatched records appear in the change report only. UEX keeps creating manufacturers, and
the next P4K import adopts such a row by UEX alias or name.

**Acceptance**

- [x] A seeded ship with an unknown `manufacturerGuid` creates that manufacturer; an unreferenced
  unmatched manufacturer creates nothing and is reported `UNMATCHED`.

The import holds an unmatched manufacturer until the items and ships of the run are reconciled; the
first seeded row that references it creates it (`ADDED`, with its name, `code`, German name and
descriptions), and the rest are reported `UNMATCHED` afterwards. A preview reports the same rows.

**Enforced by:** `P4kManufacturerPrecedenceTest` · **Code:** `P4kManufacturerReconciler` · **ADR:** [ADR-0338](../adr/0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md) · **Issues:** #2890

### REQ-P4K-022 — manufacturer logos are unmodified Fan Kit images mapped by P4K GUID

Unmodified Fan Kit PNGs ship as static frontend resources, mapped from the P4K GUID to the file name
by a versioned mapping file; there is no database column and no upload. Logos show in the Ship-Data
manufacturer list, the personal and squadron hangar, the ship-type form picker, and the item and
inventory views. A manufacturer without a logo shows an abbreviation badge. Logos never reach the
exchange. They come from the Fan Kit, not from the game archive, so the no-media scope of this spec
does not apply to them; the Fan Kit compliance test covers the PNGs.

**Acceptance**

- [x] Every mapping entry names an existing PNG and a well-formed GUID; every PNG is mapped.
- [x] A manufacturer without a mapping renders the abbreviation badge.
- [x] No exchange DTO or schema carries a logo field.

The mapping is `frontend/src/main/resources/catalogue/manufacturer-logos.json`, a JSON object of
lower-case P4K GUID to PNG file name; the PNGs ship under `static/images/manufacturers/`. The
mapping ships empty until the Fan Kit PNGs are added, so every manufacturer shows its badge.
`ManufacturerDto` carries the `p4kUuid` the page looks the logo up by; the exchange schemas carry
no logo field, which `ExchangeFieldRatchetTest` holds. The logo or badge shows on Ship-Data (the
manufacturer list and the ship types), in the personal and squadron hangar lists, beside the
ship-type pickers of both hangar dialogs (the mark of the chosen type's manufacturer, else of the
chosen manufacturer), and in the inventory item views (the item list, the item groups and the item
page), whose item references carry a `ManufacturerRefDto` with the GUID.

**Enforced by:** `ManufacturerLogoMappingTest`, `ShipDataPageControllerMvcTest`,
`ExchangeFieldRatchetTest`, the Fan Kit compliance test · **Code:** `ManufacturerLogos`,
`catalogue/fragments/manufacturer-mark.html` · **ADR:**
[ADR-0338](../adr/0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md) · **Issues:** #2890

## Names

### REQ-P4K-023 — a German name is stored beside the name and shown on German pages

`manufacturer`, `game_item`, `ship_type` and `material` gain `name_de`, written by the P4K import
only (`name.de`); the commodity categories and starmap objects carry it as well (REQ-P4K-025,
REQ-P4K-034). The UI shows `name_de` when the page language is German and
falls back to `name`. Search and matching keep running on `name`.

**Acceptance**

- [x] A German page shows the German name of an item; an English page, and a German page for a row
  without `name_de`, show `name`.
- [x] A search for the German name alone finds nothing it would not find by `name`.

The import writes `name.de` on every matched row and every row it creates; a blank German text or
the game's missing-translation marker (a text starting with `!`) is no German name and leaves the
stored one. The item merge of the SC Wiki / UEX reconciliation moves `name_de` with the P4K GUID.
The DTOs of manufacturers, ship types, materials and item references carry `nameDe`, and a page
picks the name by its language (`LocalisedNames` on server-rendered pages, `localisedName` of
`krt-localised-name.js` in scripts). Every page that names a manufacturer, item, ship or material
shows it: Ship-Data, the hangars and missions with their pickers, the materials list, overview,
matrix, profit calculation and detail, the material and item pickers, job orders, supply,
purchasing, the dashboard widgets, inventory and stock levels, refinery, the material exchange and
the blueprint recipe pane. The page-specific DTOs carry the German name beside the name they
denormalise (`nameDe`, `materialNameDe`, `manufacturerNameDe`). Search, filters, sorting, matching
and every value sent back to the server stay on `name`. Admin pages where an admin edits or maps a
name, and the file exports (the handover report PDF, the purchasing CSV), show `name`.

**Enforced by:** `P4kNamesTest`, `LocalisedNamesTest`, `ShipDataPageControllerMvcTest`,
`ItemPickerLabelsTest`, `ItemReconciliationServiceTest` ·
**ADR:** [ADR-0338](../adr/0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md) · **Issues:** #2890

## Commodities and categories

### REQ-P4K-024 — the P4K leads a commodity's identity, name, group, density and refined-from link

- **Identity:** the commodity's own `guid` in a unique `material.p4k_uuid`; match order P4K GUID,
  then the UEX id, then the name. `subtypeGuid` is an n:1, nullable link to the `ResourceType` of
  the commodity's first composition entry, not a key (p4kx #1044: on LIVE 4.10.2, 170 commodities
  hold 167 distinct values; the three SLAM packagings share one, MedPens and the counterfeit Redfin
  Energy Modulators share one, and `Special_HolidayLetter` has none). It is stored in a non-unique
  column and serves only as the join for a blueprint ingredient's `resourceGuid` and for
  `refinedSubtypeGuid`, which therefore yields zero, one or several commodities. Zero or several
  open a review case (REQ-P4K-016); the commodity an admin assigns survives re-imports. Should
  p4kx add a kind of its own for the `ResourceType` records (p4kx #1044), the join runs through it
  and the identity stays the commodity's `guid`.
- **The P4K leads** `name`, `name_de`, the group (`typeGuid`), the density
  (`densityGramsPerCubicCentimetre`) and the one-step refined-from link (`refinedSubtypeGuid`). The
  Wiki and UEX write these only on rows without a P4K GUID; a disagreement goes to
  `external_sync_report`.
- **UEX keeps** trade flags, prices, terminals, stock, refinery yields and the RAW / REFINED type. On
  a row UEX does not know, the import sets RAW on a commodity that has a `refinedSubtypeGuid` and
  REFINED on its target; otherwise the type stays `null`.
- **Not imported:** instability (it lives on `mineableElements`, with no consumer) and refining
  methods (`refiningProcesses`); the existing refinery data stays.

**Acceptance**

- [ ] Three commodities sharing one `subtypeGuid` import as three rows keyed by their `guid`.
- [ ] An ingredient whose `resourceGuid` matches two commodities, or none, opens a review case; the
  admin's assignment survives the next APPLY.
- [ ] A UEX commodity sync leaves the P4K name and density of a P4K-known material and reports a
  different name.
- [ ] A material UEX does not know becomes RAW when it has `refinedSubtypeGuid` and its target
  REFINED; a material UEX knows keeps the UEX type.

**Enforced by (planned):** `P4kCommodityPrecedenceTest`, `UexCommodityServiceTest` · **ADR:**
[ADR-0339](../adr/0339-the-p4k-catalogue-leads-commodities-and-its-resource-groups-become-the-commodity-categories.md) · **Issues:** #2891

### REQ-P4K-025 — the resource groups are the commodity categories

The `resourceGroups` kind becomes the commodity categories: P4K GUID as identity, `name` /
`name_de` from the P4K (the `className` when the name is a placeholder and `en` is `null`),
`parentGuid` as the parent with a cycle guard (a group never becomes its own ancestor). A one-off
migration maps every admin-maintained category onto a group and removes the admin categories;
admins no longer create or rename categories. A group that vanishes from a complete kind is stamped
like any other P4K row (REQ-P4K-011).

**Acceptance**

- [ ] A group with a placeholder name shows its `className`.
- [ ] Two groups listing each other are imported without a cycle.
- [ ] After the migration every material that had a category has its mapped group, and no admin
  endpoint creates or renames a category.

**Enforced by (planned):** `P4kResourceGroupImportTest`, the category migration test · **ADR:**
[ADR-0339](../adr/0339-the-p4k-catalogue-leads-commodities-and-its-resource-groups-become-the-commodity-categories.md) · **Issues:** #2891, #2825

### REQ-P4K-026 — a commodity is created only when a record of the same run references it

The import creates a commodity only when a record imported in the same run references it: a
blueprint ingredient (`resourceGuid`) or a `refinedSubtypeGuid`, both joined by `subtypeGuid`
(REQ-P4K-024). Such a row is visible at once. A reference that joins several commodity records
creates none of them and opens the review case of REQ-P4K-024 instead.
Every other unmatched commodity stays match-only and appears in the report's unmatched list.

**Acceptance**

- [ ] An ingredient naming an unknown `ResourceType` creates that material; an unreferenced
  unmatched commodity creates nothing.

**Enforced by (planned):** `P4kImportServiceSeedingTest` · **ADR:** [ADR-0339](../adr/0339-the-p4k-catalogue-leads-commodities-and-its-resource-groups-become-the-commodity-categories.md) · **Issues:** #2891

## Blueprints

### REQ-P4K-027 — the P4K leads every recipe attribute, and the SC Wiki blueprint sync fills gaps

- **The P4K leads:** the produced item (`producedItemGuid`), `tiers`, mandatory and optional cost
  lines, choose-n selections (`count`, `options`), quantities (the Basetool applies
  `quantityMultiplier`), minimum quality (clamped as REQ-DATA-023 says), craft time, modifiers
  (`propertyGuid`, linear `ranges`), category (`categoryGuid`), `defaultAvailable` and dismantle
  (`{processGuid, tierIndex}` with the exclusions of `craftingGlobal`).
- **Naming:** a blueprint is named through its produced item, never through its own `name`.
- **Categories:** `blueprintCategories` become the blueprint categories, P4K GUID as identity; they
  do not nest. They carry the run stamp and are swept like the five kinds (REQ-P4K-011). A category
  whose `name` is `null` shows its `className`, as a resource group does (REQ-P4K-025).
- **The SC Wiki blueprint sync** writes only fields the P4K delivers as `null` on a row, and keeps
  writing rows the P4K does not know. Where both have a value and they differ, the P4K value stays
  and the difference goes to `external_sync_report`.
- **Not imported:** `blueprintPools`, `qualityDistributions`, `qualityQuantizations` and `crafters`.

**Acceptance**

- [ ] A line with `quantityScu` 2 and `quantityMultiplier` 1.15 stores 2.3 SCU.
- [ ] A choose-2-of-3 selection is stored as one selection with three options.
- [ ] A Wiki craft time that differs from the P4K value leaves the P4K value and is reported; on a
  P4K-unknown blueprint the Wiki writes it.

**Enforced by (planned):** `P4kBlueprintImportTest`, `ScWikiBlueprintSyncServiceTest` · **ADR:**
[ADR-0340](../adr/0340-the-p4k-catalogue-leads-blueprints-and-the-sc-wiki-blueprint-sync-fills-gaps.md) · **Issues:** #2892

### REQ-P4K-028 — a blueprint is created when its produced item exists, behind the per-run checkbox

With the per-run checkbox of REQ-P4K-009 ticked, the import creates an unmatched blueprint when its
`producedItemGuid` resolves to an item that exists or is created in the same run; the preview counts
them. Other unmatched blueprints stay match-only and are listed as `UNMATCHED`.

**Acceptance**

- [ ] With the box ticked, a blueprint of an existing item is created; one whose item is unknown and
  not seeded is reported `UNMATCHED`.

**Enforced by (planned):** `P4kImportServiceSeedingTest` · **ADR:** [ADR-0340](../adr/0340-the-p4k-catalogue-leads-blueprints-and-the-sc-wiki-blueprint-sync-fills-gaps.md) · **Issues:** #2892

### REQ-P4K-029 — the import stores the produced item's GUID that keys a member's blueprints

The import stores `producedItemGuid` on every matched `blueprint` row. It is the key of a member's
personal blueprints (a product, keyed by the produced item's DataForge GUID, never a Wiki UUID), so
the key does not depend on a matched `game_item`. A personal-blueprint row that resolves to no GUID
or to several keeps its name key as a fallback, opens a review case (REQ-P4K-016), and is upgraded
by a later run that resolves it. The name resolution, the merge of a fallback row into a GUID row
and the exchange's `guid` field are the personal-blueprint and exchange requirements' (REQ-INV-006,
REQ-INV-019, REQ-XCH-012's blueprint reference).

**Acceptance**

- [x] A matched blueprint stores its `producedItemGuid`, also when no `game_item` carries that GUID.
- [x] A fallback row whose product resolves in a later run is upgraded to the GUID key and its
  review case closed.

The import writes `blueprint.produced_item_guid` as a P4K-owned column. A fallback name key's
case is a `BLUEPRINT` case with the reason `PRODUCT_KEY_UNRESOLVED` (no GUID) or
`PRODUCT_KEY_AMBIGUOUS` (several), keyed by a name-based UUID of the name key; its report row
names the key as class name and the product as English name. A run that resolves the key
closes the case as `RESOLVED`, which only a run sets. Only an APPLY moves keys and opens or
closes cases; a preview reports the same `REVIEW` rows, read against the GUIDs already stored.

**Enforced by:** `PersonalBlueprintKeyBackfillTest` · **ADR:**
[ADR-0343](../adr/0343-a-personal-blueprint-is-keyed-by-its-produced-items-guid.md) · **Issues:** #2893

### REQ-P4K-030 — a crafted item's blueprint stat base values come from the P4K, and the SC Wiki fills gaps

A blueprint modifier names a gameplay property (`propertyGuid`), joined to the `className` of its
`gameplayProperties` entry; `BlueprintStatProperty` maps that `className` to the stat field by the
contract's fixed table "From a gameplay property to a stat field". `game_item_stat` gains a `source`
column (`P4K`, `SCWIKI`):

- the import writes the base value from `stats` for every item the catalogue covers;
- the SC Wiki writes only where no P4K value exists; a Wiki value that differs from the P4K value by
  more than **1 % relative** goes to `external_sync_report`;
- the three recoil keys stay without a source (p4kx maps recoil and reload to no stat field);
- the catalogue carries the raw damage multiplier; the `1 − multiplier` mitigation conversion stays
  in the Basetool for both sources;
- the recipe view shows every value as REQ-INV-069 says, and its tooltip names the source (P4K or SC
  Wiki); no Build stamp is shown;
- `basetool_catalogue_items_with_stats` gains a bounded `source` label, and the
  `ScWikiItemStatsMissing` alert fires only for items neither source covers.

**Acceptance**

- [ ] An item with a P4K shield `maxHealth` stores it with source `P4K`; a Wiki value 0.5 % off is
  not reported, 2 % off is.
- [ ] An item the catalogue does not cover takes the Wiki value with source `SCWIKI`.
- [ ] The armour mitigation is `1 − multiplier` for both sources.
- [ ] Every `className` of the contract's table maps to a `BlueprintStatProperty`.

**Enforced by (planned):** `P4kItemStatImportTest`, `BlueprintStatPropertyTest`,
`GameItemStatWriterTest` · **ADR:** [ADR-0342](../adr/0342-blueprint-stat-base-values-come-from-the-p4k-catalogue-and-the-sc-wiki-fills-gaps.md) (supersedes [ADR-0309](../adr/0309-blueprint-stat-base-values-come-from-the-sc-wiki-item-payload.md)'s source decision; replaces
REQ-DATA-027) · **Issues:** #2927

## Ships

### REQ-P4K-031 — the P4K leads a ship type's base data; cargo only where every grid was read

- **The P4K leads** a matched `ship_type`'s identity (unique `p4k_uuid`), `class_name`,
  manufacturer (`manufacturerGuid`), `name`, `name_de`, `crewSize`, `role`, `career`, `mass`,
  `hullHealth`, `size`, `vehicleKind` and the `insurance` facts. The Wiki and UEX write these only on
  rows without a P4K GUID; disagreements go to `external_sync_report`.
- **Cargo:** the P4K leads `cargoScu` only when `cargoGridsWithIssue` is 0. Above 0 the value is a
  lower bound: the stored value stays, and a difference goes to `external_sync_report`.
- **UEX keeps** store URLs, purchasability and prices. `shipStoreSlugs` is curated, not extracted,
  and only cross-checks the UEX store URL into `external_sync_report`. Dimensions stay with the Wiki.
- **Seeding** follows REQ-P4K-009.

**Acceptance**

- [x] A ship with `cargoGridsWithIssue` 0 takes the P4K `cargoScu`; one with 2 keeps the stored
  value and reports a difference.
- [x] A UEX sync leaves the P4K crew and role of a P4K-known ship.
- [x] A store slug that maps to another ship than the UEX URL is reported, nothing is written.

The columns: `crewSize` → `crew_max` (the game has no minimum crew, so `crew_min` stays with UEX),
`role` and `career` → `role` and `career` (the English label), `mass` → `mass`, `hullHealth` →
`health`, `size` → `size_class`, `vehicleKind` → `vehicle_kind` (`SPACESHIP`, `GROUND_VEHICLE`;
another value is an unknown closed-set value of REQ-P4K-003), `insurance` → `insurance_*`,
`cargoScu` → `scu`; `name_de` comes with REQ-P4K-023. On a matched row the import writes a non-null
catalogue value over a different stored one and logs it as `CHANGED`; a `null` never clears. A
name another ship type already holds is not taken (`name` is unique) and is reported instead. The
Wiki and UEX write a led column on a P4K-known row only while it is empty (`P4kLedColumns`); every
disagreement is one `P4K_DISAGREEMENT` event of the writing source's run, its `detail` holding
`column=…;stored=…;source=…` entries.

**Enforced by:** `P4kShipPrecedenceTest`, `UexVehicleServiceUuidMatchTest`,
`ScWikiVehicleSyncServiceTest` · **Code:** `P4kShipBaseData`, `P4kShipStoreSlugCheck`,
`P4kLedColumns` · **ADR:** [ADR-0341](../adr/0341-the-p4k-catalogue-leads-a-ship-types-base-data-and-ship-types-match-by-class-name-before-name.md) · **Issues:** #2897

### REQ-P4K-032 — ship types match by GUID and class name first, by name last, and never pick a duplicate

The hangar import (`ShipTypeMatcher`) and the exchange's `SHIP_TYPE` reference (REQ-XCH-012) match
in this order: P4K GUID / class name, then the UEX id and the curated alias of [ADR-0307](../adr/0307-uex-vehicles-reach-their-ship-type-through-a-curated-class-name-alias.md)
(REQ-DATA-026), then the name as the last step. A name that matches several ship types answers
`ambiguous` with at most ten `{bt, name}` candidates, never the first duplicate.

**Variants:** a class name resolves to the `ship_type` that carries exactly that class name; if none
does and the record has `variantOf`, it resolves to the ship type of the group's base. Variants are
never merged automatically.

**Acceptance**

- [x] Two ship types with the same normalised name answer `ambiguous` with both candidates.
- [x] A variant's class name resolves to its own ship type when one exists, else to its base's.
- [x] A class-name match wins over a different ship type of the same name.

An APPLY keeps the variant groups in `ship_type_p4k_variant` (variant GUID, class name, base GUID);
a complete ships kind deletes the variants it no longer lists. The hangar import's uploads carry
only names and slugs, so it takes the name stages and then the slug; a name or slug that finds
several ship types is skipped as unmatched.

**Enforced by:** `ShipTypeMatcherTest`, `ExchangeResolveServiceTest`, `P4kShipPrecedenceTest` ·
**Code:** `ShipTypeMatcher`, `P4kShipVariants` · **ADR:** [ADR-0341](../adr/0341-the-p4k-catalogue-leads-a-ship-types-base-data-and-ship-types-match-by-class-name-before-name.md) ·
**Issues:** #2897

## Game texts

### REQ-P4K-033 — every column holding a game text is classified, and game texts and media stay with members

A column is classified by its content, not its source, and has exactly one class:

- **`@GameText`** sits on every description column, whichever writer fills it (Wiki, UEX, P4K,
  admin): `GameItem.description_en/_de`, `ShipType.description_en/_de`, `Manufacturer.description`
  and `description_de`, `Material.description`, and on every column filled from a catalogue field
  the contract marks `"x-gameText": true` (`displayType` should it be persisted, the starmap
  object's `name` and `name_de`).
- A catalogue description column that no game-data source fills, only an admin in the Basetool,
  holds no game text and stays unmarked: `Location.description`, `StarSystem.description`,
  `FrequencyType.description`, `RefiningMethod.description` and `JobType.description`. Should a
  game-data source ever fill one, it becomes `@GameText`.
- A shop's names (`Shop.entityName`, `registryName`, `locationName`, `brand`) are names of
  containers and entities, which the contract marks as no game text; they stay unmarked.
- **`@GameMedia`** is defined and sits on no column: the import stores no archive media, and the UEX
  link and image columns stay unmarked UEX data.

Both annotations live in the backend's `kernel` package (`GameText`, `GameMedia`).

A `@GameText` or `@GameMedia` column is shown to logged-in members only and never leaves the
Basetool to a third party; the exchange enforces this with the guards of REQ-XCH-050.
The import never reads a game text to derive a fact (the `Grade:` and `Class:` lines are p4kx's or
nobody's).

**Acceptance**

- [x] Every entity field filled from an `x-gameText` field of the checked-in schema carries
  `@GameText`. *The schema is vendored at
  `backend/src/test/resources/p4k/basetool-catalog.schema.json` (p4kx `v0.4.2`, provenance in the
  `README.md` beside it); every `x-gameText` property is mapped to the fields it fills or listed
  as not imported, so a new one fails the test until it is classified.*
- [x] Every catalogue description column carries `@GameText` unless it is one of the admin-authored
  columns above; no column carries both annotations, and none carries `@GameMedia`.

**Enforced by:** `GameTextClassificationTest` · **ADR:** [ADR-0344](../adr/0344-game-texts-and-game-media-never-leave-the-basetool-to-third-parties.md) · **Issues:** #2894,
#2887

## Starmap and locations

### REQ-P4K-034 — the starmap objects form the leading location tree

The `starmapObjects` kind is imported into `starmap_object`: unique P4K GUID, `type`, a parent
foreign key (`parentGuid`), `position` (metres, in the star system's frame), `system`,
`hideInStarmap`, `name` and `name_de`. It holds P4K-only objects too (jump points, Lagrange points,
POIs without UEX). A P4K-only object never becomes a trade or storage place by itself. A vanished
object follows the sweep (REQ-P4K-011); a UEX row linked to it stays usable.

**Acceptance**

- [x] An object whose parent is imported in the same run gets its parent link, whatever the order.
- [x] A jump point without a UEX counterpart is imported and is no trade place.

Every record with a GUID is matched by that GUID or added (`ADDED`); the P4K writes every column
but the admin's visibility override, a `null` included, and a changed column is a `CHANGED` report
row. The parent is set once the whole kind is read; a `parentGuid` the catalogue does not list
leaves the parent `null`. A GUID the catalogue repeats is read once. `name` and `name_de` are game
texts (`@GameText`): a report row names the object by its class name only. A catalogue without
starmap objects leaves the tree and the links as they are. The result's `starmapObjects` counts the
objects (`matched`, `enriched`, `created`) and the UEX rows left unlinked (`unmatched`, `review`).

**Enforced by:** `P4kStarmapImportTest`, `GameTextClassificationTest` · **Code:**
`P4kStarmapReconciler`, `StarmapObject` (V352) · **ADR:** [ADR-0345](../adr/0345-the-p4k-starmap-tree-leads-and-uex-locations-link-to-it.md) · **Issues:** #2896, #3044

### REQ-P4K-035 — the UEX locations link to the starmap tree; ambiguity goes to review, manual links win

The nine UEX tables (`star_system`, `orbit`, `planet`, `moon`, `city`, `outpost`, `space_station`,
`poi`, `terminal`) stay the trade layer and gain a nullable `starmap_object_id`. On APPLY the import
links a row by normalised name + type + the already-linked parent: a unique hit links; an ambiguous
hit or no hit opens a review case. A UEX-only row stays fully usable (terminals, prices, storage),
unlinked, counted as unmatched in the report and absent from the starmap. An admin can set or clear
a link by hand; a manual link is never overwritten by an import, and each manual change is an audit
event (REQ-P4K-046).

**Acceptance**

- [x] A city whose name, type and linked planet match one object is linked.
- [x] Two candidates open one review case and link nothing.
- [x] A manual link survives an APPLY that would link elsewhere.

The tables are linked parents first, in the order above. A name is normalised to lower case without
accents, every other run of characters one space, and compared with the object's English name. The
type is compared as a family, the type's lower-case name up to its first `_` (`Outpost_InvalidQT` is
`outpost`), taken from the types of the LIVE catalogue: `star_system` takes `star` (the star
carries the system's name, `Stanton`) and `solarsystem`; `orbit` `planet`, `jumppoint` and
`anomaly` (the jump points of Pyro and Nyx); `planet` `planet`; `moon` `moon`; `city`
`landingzone` and `manmade` (Levski); `space_station` `manmade` (Everus Harbor) and
`manmade_visibleoninteraction`'s family `manmade` (the Lagrange stations), and `landingzone`;
`outpost` `outpost`, `manmade`; `poi` `pointofinterest`, `outpost`, `manmade`, `asteroid`,
`navpoint`, `anomaly`; `terminal` `manmade`, `pointofinterest`, `outpost`, `landingzone`. The parent is the nearest parent the row names
(UEX's `*_name` columns: city, space station, outpost, moon, planet, orbit, then the star system)
that is linked; a candidate must lie below it at any depth, and with no linked parent every object of
the name and type is a candidate. A stamped or hidden object links nothing new. A row left unlinked
is one `UNMATCHED` row and one case `STARMAP_LINK_AMBIGUOUS` or `STARMAP_LINK_NOT_FOUND` of kind
`STARMAP_OBJECT`, whose GUID is a name-based UUID of the table and the row; the report names the row
by its table and its UEX name, and a run that links the row closes the case as `RESOLVED`. A link
the run changes is a `CHANGED` row (`<table>.starmap_object_id`, old and new GUID).

The link columns are not mapped on the UEX entities, so a UEX sync never writes them. An admin sets
(`SET`), clears (`CLEAR`) or hands back to the import (`RELEASE`) a link at
`PUT /api/v1/catalog/admin/starmap/places/{kind}/{id}/link`; the request echoes the link and the
manual flag the admin saw and is a `409` when either changed meanwhile, so it never collides with an
edit of the row's UEX data. `SET` and `CLEAR` mark the link manual (`starmap_link_manual`), which
every APPLY leaves alone.

**Enforced by:** `P4kStarmapLinkTest`, `StarmapPlaceKindTest` · **Code:** `P4kStarmapLinker`,
`StarmapPlaceKind`,
`StarmapPlaceRows`, `StarmapAdminService`, `P4kRunResolvedCases` · **ADR:** [ADR-0345](../adr/0345-the-p4k-starmap-tree-leads-and-uex-locations-link-to-it.md) · **Issues:** #2896, #3044

### REQ-P4K-036 — a storage place can sit on any visible starmap node an admin picks

`location` gains a nullable `starmap_object_id` beside `city_id` and `space_station_id`; at least one
of the three is set. Cities and stations are still created by the UEX sync. Any other starmap node
becomes a storage place only when an admin creates it on a visible object (REQ-P4K-038), as an audit
event; the import never creates storage places. The refinery rule (REQ-REFINERY-020) and the
exchange `uex` reference keep running through city and station.

**Acceptance**

- [x] A `location` with none of the three references is refused by the database.
- [x] An admin creates a storage place on a visible outpost; a hidden object is refused.
- [x] An APPLY creates no `location` row.

`POST /api/v1/locations` creates a storage place only on a starmap object (`starmapObjectId`, `400`
without one or on an object that is not visible) that carries none yet and that no city or space
station links to (`409`), audited `CATALOGUE_STORAGE_PLACE_CREATED`; one node carries at most one
place (`uq_location_starmap_object`). The node is set on create only. The database refuses a row
with none of the three (`ck_location_place`, V352).

**Enforced by:** `LocationStarmapNodeTest`, `LocationServiceTest`, `P4kStarmapImportTest` · **Code:**
`LocationService` · **ADR:** [ADR-0345](../adr/0345-the-p4k-starmap-tree-leads-and-uex-locations-link-to-it.md) · **Issues:** #2896, #3044

### REQ-P4K-037 — a linked place shows its P4K name, and only the curated name reaches the exchange

The UI shows the P4K name of a linked place in the page language (`name_de` / `name`).
`location.name` stays the curated name (from UEX for cities and stations, set by the admin for other
nodes); it is no game text and the only location name that reaches the exchange. A linked place
additionally carries `guid` in the exchange, additive in `v1`; `uex:{kind,id}` stays, and the
write resolver tries `guid`, then `uex`, then the exact name (specified in
[`external-exchange.md`](external-exchange.md)).

**Acceptance**

- [x] A German page shows the German P4K name of a linked city; the exchange serves its
  `location.name`.

A place is linked through its own node, else through its city's, else its space station's object.
`LocationDto` carries the object's `p4kName` and `p4kNameDe` (read-only); the admin locations page
shows the one of the page language under the curated name.

**Enforced by:** `LocationNameDisplayTest`, `LocationStarmapNodeTest`,
`ExchangeLocationGuidIntegrationTest`, `ExchangeCatalogControllerTest` · **ADR:**
[ADR-0345](../adr/0345-the-p4k-starmap-tree-leads-and-uex-locations-link-to-it.md) · **Issues:** #2896, #3044

### REQ-P4K-038 — a starmap object is visible only inside a linked live star system

An object is **visible** when its `system` is linked to a UEX star system that is live-available,
`hideInStarmap` is `false` and `position` is set. Only a visible object appears on the starmap and
can carry a new storage place. Everything else is imported and hidden, including objects outside any
linked system. An admin can set an object to visible or hidden explicitly; the override wins over the
rule, survives re-imports and is an audit event (REQ-P4K-046).

**Acceptance**

- [x] An object in a linked live system with a position and `hideInStarmap` `false` is visible; the
  same object with `hideInStarmap` `null` or `true`, or without a position, is hidden.
- [x] An admin override to visible survives the next APPLY.

The object's `system` is the folder of its star system (`stanton`); it is linked to the UEX star
system whose normalised name equals it. An object the sweep stamped or an admin hid through a review
case is never visible. The override is `starmap_object.visibility_override` (`VISIBLE`, `HIDDEN`, or
`null` for the rule), set at `PUT /api/v1/catalog/admin/starmap/objects/{id}/visibility` with the
object's version and audited `CATALOGUE_STARMAP_VISIBILITY_OVERRIDDEN` (`visibility=VISIBLE|HIDDEN|RULE`).
The admin page `/admin/starmap` lists the objects with their visibility and the UEX rows with their
links.

**Enforced by:** `StarmapVisibilityTest`, `AdminStarmapControllerMvcTest` · **Code:**
`StarmapVisibility`, `StarmapAdminService` · **ADR:** [ADR-0345](../adr/0345-the-p4k-starmap-tree-leads-and-uex-locations-link-to-it.md) · **Issues:** #2902, #3044

### REQ-P4K-039 — no feature references a location by an external id

`personal_inventory_item.location_uex_id` and `location_type` move to a UUID foreign key on the
storage place, `personal_inventory_item.location_id` → `location.id` (`ON DELETE SET NULL`); the name
snapshot stays. This step is independent of the P4K import and may ship at any time.

The owner's decision (@greluc, 2026-11-05) fixes the edges:

- **Every resolvable row gets a key.** A city or space station a row references that has no
  `location` row yet gets one, linked to it and marked `location.reference_only`. A name already
  taken falls back to `<name> (UEX city|space station <id>)`.
- **A reference-only row is offered nowhere else.** The storage-place lists and pickers, the hangar
  and stock pickers, the home locations, the refinery locations and the exchange catalogue and
  resolver skip it; only a lookup by id or by city / station reaches it. The UEX universe sync keeps
  its name in step with the place and clears the marker once the place is live; a live place adopts
  the reference-only row of its city or station instead of creating a second one.
- **The member picker** (`GET /api/v1/personal-inventory/locations`, the admin twin
  `…/admin/{userId}/locations`) offers every non-hidden, non-reference-only location plus every
  location one of the owner's own items already references. A write accepts exactly those, so every
  existing row stays editable, including one on a reference-only or hidden place.
- **An unresolvable row stays.** A row whose UEX id resolves to no city or station keeps its
  snapshot with a `null` key; an update may keep it so, while dropping the key of a row that has one
  is a `400`. The API shows such a row with `locationId` and `locationType` `null`.
- `locationType` (`CITY`, `SPACE_STATION`, `OTHER`) is derived from the location's link for the
  marker only and is no longer stored.

**Acceptance**

- [x] The migration maps every personal-inventory row with a resolvable UEX id onto the location's
  UUID and keeps the name snapshot; the test covers an existing location, a created reference-only
  one shared by two rows, a taken name, and two unresolvable rows (a city and a station id).
- [x] No storage-place list, picker or exchange read returns a reference-only row; the sync clears
  the marker of a place that becomes live.
- [x] The member picker and the write accept an offered location or one the owner already uses, and
  nothing else.

**Enforced by:** `V344MigrationTest`, `LocationRepositoryTest`, `UexUniverseSyncServiceTest`,
`PersonalInventoryItemRepositoryTest`, `PersonalInventoryItemServiceTest` · **ADR:**
[ADR-0345](../adr/0345-the-p4k-starmap-tree-leads-and-uex-locations-link-to-it.md) · **Issues:** #2896, #3043

## Loadouts and paints

### REQ-P4K-040 — a ship type's default loadout is stored as its port tree

The P4K is the only source. The `loadouts` kind is stored as a port tree per `ship_type`
(`entityKind = ship`): port `name`, `minSize`, `maxSize`, `types`, `flags`, `requiredTags`, the
default item (`itemGuid`, a nullable reference to `game_item`), `source`, and nested ports through a
parent reference. Item loadouts (`entityKind = item`) are not stored. `displayNameKey` is kept as a
key only. APPLY replaces the tree of every ship the `loadouts` kind lists; a `partial` kind leaves
the trees of unlisted ships as they are, a `complete` kind also removes the trees of ships it no
longer lists. Ports with `issues` and
`unresolved` ports are counted in the change report. `ship.fitted` stays as it is: the tree
describes the type's default, not a member's ship. Ports, sizes, flags and the default item are fact
values; no exchange route serves them yet (#2821 decides).

The tree is `ship_type_loadout_port` (`V353`): one row per port with its ship type, its parent, its
place among its siblings, `types` as `Type` or `Type.SubType` per entry, `item_guid` (the
catalogue's GUID, kept for an entity without a row, a docked vehicle included) and `game_item_id`.
An unknown `source` reads as `null` and counts as an `UNKNOWN_VALUE` (REQ-P4K-003). A ship loadout
with `issues` or unresolved ports is one `LOADOUT_ISSUES` report row (`old_value` the issue codes,
`new_value` the unresolved ports). A complete but empty kind removes nothing, as the sweep skips an
empty kind. The import streams the kind in a second pass over the upload, one loadout at a time,
because it is most of the file. An item merge moves the default item of a port onto the survivor.

**Acceptance**

- [x] A ship's tree is stored with nested ports and default items; a second APPLY replaces it.
- [x] An `itemGuid` without a `game_item` row stores the port with a `null` item reference.

**Enforced by:** `P4kLoadoutImportTest` · **Issues:** #3027, #3045

### REQ-P4K-041 — a paint is an item, and its ships are a link table

A paint is a `game_item` like any other item: the same matching, precedence and seeding. The link
table `ship_type_paint` holds the `shipGuids` assignment. APPLY replaces the links of every paint
the `paints` kind lists; a `partial` kind leaves unlisted paints as they are, a `complete` kind also
removes the links of paints it no longer lists.

A link needs both rows: a paint whose item or a ship whose ship type the Basetool lacks links
nothing. A complete but empty kind removes nothing, and an item merge moves the paint's links onto
the survivor.

**Acceptance**

- [x] A paint fitting three ship types yields three link rows; one fitting none yields none.

**Enforced by:** `P4kPaintImportTest` · **Issues:** #3027, #3045

## Shops

### REQ-P4K-042 — shops are imported by their unique key, and UEX stays the price key

The `shops` kind is imported into `shop`, keyed by the catalogue's unique `key` (`entityGuid` is not
unique), with `system`, `kind`, `brand`, `commodityExchange` and the `starmapObjectGuid` reference.
A shop that vanishes from a complete `shops` kind is stamped (REQ-P4K-011); a partial kind never
sweeps. Prices, stock and demand never come from the P4K.

**Acceptance**

- [x] Two shops sharing an `entityGuid` with different keys are two rows.

The table is `shop` (V354), unique on `shop_key`; its `p4k_uuid` is a name-based UUID of the key, so
the run stamp, the sweep, the report and the review list key a shop like any other P4K row. Every
record with a key is matched by it or added (`ADDED`), and the P4K writes every column but the
terminal link, a `null` included; a changed column is a `CHANGED` report row. `kind` is a closed
set read through `P4kShopKind` (the 20 values of LIVE); an unknown one reads as `null` and is an
`UNKNOWN_VALUE`. `starmapObjectGuid` resolves to `starmap_object_id`, `null` when the tree lacks
the object. A key the catalogue repeats is read once. A catalogue without shops leaves the shops,
their lists and their links as they are. The result's `shops` counts the shops (`matched`,
`enriched`, `created`) and the shops left without a terminal (`unmatched`, `review`).

**Enforced by:** `P4kShopImportTest` · **Code:** `P4kShopReconciler`, `Shop`, `P4kShopDto` ·
**ADR:** [ADR-0347](../adr/0347-p4k-shops-join-uex-terminals-by-place.md) · **Issues:** #3028, #3046

### REQ-P4K-043 — a shop links to a UEX terminal by place, then by name

On APPLY a shop links to a UEX terminal through its starmap object (REQ-P4K-035), then by name within
that place. An ambiguous match opens a review case; a manual link by an admin wins, survives
re-imports and is an audit event (REQ-P4K-046). An unlinked shop stays usable on the P4K side.

**Acceptance**

- [x] A shop whose place holds one terminal of the matching name is linked; two candidates open a
  review case.
- [x] A manual link survives the next APPLY.

A terminal's place is its own starmap link, else the link of its nearest linked UEX parent
(REQ-P4K-035); the candidates are the terminals whose place is the shop's starmap object. The name
match reads UEX's `<shop> - <place>` naming: a terminal's part before its first ` - `, normalised
like a starmap name and without spaces, at least three characters, matches when the shop's
registry name, entity name or brand, compacted the same way, contains it; the longest match wins
(`ShopTerminalNames`). One candidate links; several or none (no place included) leave the shop
unlinked, one `UNMATCHED` row and one case `SHOP_LINK_AMBIGUOUS` or `SHOP_LINK_NOT_FOUND` of kind
`SHOP` with the shop's GUID; a run that links the shop closes the case as `RESOLVED`. A link the
run changes is a `CHANGED` row (`terminal_id`). The links are read as stored, so a preview links
against the starmap links of the last APPLY.

The link columns `shop.terminal_id` and `terminal_link_manual` are mapped read only and written
only by `ShopRepository`'s statements. An admin sets (`SET`), clears (`CLEAR`) or hands back to the
import (`RELEASE`) a link at `PUT /api/v1/catalog/admin/shops/{id}/terminal-link`, echoing the link
and the manual flag seen (`409` when either changed); `SET` and `CLEAR` mark the link manual, which
every APPLY leaves alone. The admin page `/admin/shops` lists the shops with their links and finds
terminals by name (`GET …/admin/shops/terminals`).

**Enforced by:** `P4kShopTerminalLinkTest`, `ShopTerminalNamesTest`, `AdminShopsControllerMvcTest`
· **Code:** `P4kShopLinker`, `ShopTerminalNames`, `ShopAdminService` · **ADR:**
[ADR-0347](../adr/0347-p4k-shops-join-uex-terminals-by-place.md) · **Issues:** #3028, #3046

### REQ-P4K-044 — a shop's item list shows on the item as "available at"

Where a shop has an item list (`itemGuids`), it is stored as shop-to-`game_item` rows and shown on
the item to members as "available at". A `null` list means unknown, never "sells nothing"; an empty
list means the file lists no item.

**Acceptance**

- [x] An item listed by two shops shows both; a shop with a `null` list contributes nothing and is
  not shown as empty.

The rows are `shop_item` (V354); `shop.inventory` is `null` exactly while the list is unknown. APPLY
replaces the rows of every listed shop with the GUIDs that resolve to a `game_item`; an unknown list
leaves none. `GET /api/v1/game-items/{id}/catalogue` carries `availableAt`: the shops that are
neither stamped nor hidden and whose list names the item, each with its linked terminal's name,
its registry and entity name, its star system and its place's names, one per name and place. The
item page names a shop by its terminal, else its registry name, and shows an empty list as
unknown, never as "not available". An item merge moves the rows onto the survivor.

**Enforced by:** `P4kShopImportTest`, `InventoryPageControllerMvcTest` · **Code:**
`ItemCatalogueDetailsService`, `catalogue/fragments/item-catalogue.html` · **ADR:**
[ADR-0347](../adr/0347-p4k-shops-join-uex-terminals-by-place.md) · **Issues:** #3028, #3046

## Texts, audit and monitoring

### REQ-P4K-045 — a P4K description is stored with real line breaks

The game's localisation writes a line break inside a text as backslash and `n`. Every description
the import stores (`material.description`, `manufacturer.description` and `description_de`, the
`description_en` / `description_de` of `game_item` and `ship_type`) holds real line breaks, never
the escaped form: the import decodes every remaining escaped pair before it matches, fills or seeds
anything. V308 decoded the rows stored before. The material detail page shows the line breaks
(`white-space: pre-line`).

**Acceptance**

- [x] An imported description `NDR: 08\nEffect: Toxic` (escaped) is stored with a real line break.
- [x] V308 turns a stored escaped line break into a real one and leaves other text unchanged.
- [x] The material detail page renders each line of the description on its own line.
- [x] A version-2 description that already holds a line feed is stored unchanged.

**Enforced by:** `P4kTextTest`, `V308MigrationTest` · **Code:** `P4kText`, `P4kLocalisedText`,
V308, `catalogue/material-detail.css` · **Moved from:**
REQ-DATA-025

### REQ-P4K-046 — catalogue admin activity is audited

The catalogue admin area is the audited area `AuditDomain.CATALOGUE` (REQ-AUDIT-001). Within this
spec that covers: a P4K preview enqueued (one event per job, with the Build) and an APPLY (one event
per run, with Build, run id and counts per kind; actor the admin who enqueued it), each mass-guard override (REQ-P4K-012), each review resolution (REQ-P4K-016), each
manual starmap link (REQ-P4K-035), shop link (REQ-P4K-043) and visibility override (REQ-P4K-038),
and each storage place an admin creates on a non-UEX node (REQ-P4K-036). `p4k_import_change` stays
the never-pruned detail record; the audit event carries no free text.

**Acceptance**

- [x] A P4K preview enqueued and an APPLY each write exactly one `CATALOGUE` event with the job or
  run id as subject, the Build and, for the APPLY, the counts per kind.
- [ ] Each mass-guard override, review resolution, manual starmap or shop link, visibility
  override and storage place writes exactly one `CATALOGUE` event with the row UUID as subject;
  the event types of the override and the review resolution exist.

The starmap's events are recorded: `CATALOGUE_STARMAP_LINK_SET`, `…_LINK_CLEARED` and
`…_LINK_RELEASED` (subject the UEX row, details `kind`, `starmapObjectId`,
`previousStarmapObjectId`), `CATALOGUE_STARMAP_VISIBILITY_OVERRIDDEN` and
`CATALOGUE_STORAGE_PLACE_CREATED`; the shop's are `CATALOGUE_SHOP_LINK_SET`, `…_LINK_CLEARED` and
`…_LINK_RELEASED` (subject the shop, details `terminalId`, `previousTerminalId`).

**Enforced by:** `P4kImportAuditTest`, `P4kImportJobServiceTest`, `P4kImportJobRunnerTest`,
`P4kStarmapLinkTest`, `StarmapVisibilityTest`, `LocationStarmapNodeTest`, `P4kShopTerminalLinkTest` ·
**Enforced by (planned):** further cases in `CatalogueAuditTest` · **ADR:** [ADR-0346](../adr/0346-catalogue-admin-activity-is-audited-in-its-own-domain.md) · **Issues:** #2901

### REQ-P4K-047 — the import is monitored and says when it is due

The P4K import's metrics, alerts and dashboard are REQ-OBS-022 and REQ-OBS-023 in
[`observability.md`](observability.md): the upload rejections by reason, the last-run gauges per kind
and outcome, unknown grade and class values, the `itemClass` null share, stamped rows, open review
cases, and the "P4K import due" signal. `/admin/p4k-import` shows a "P4K import due" banner as soon
as the UEX or the Wiki game version is newer than the patch version (REQ-P4K-005) of the last
successful APPLY whose version is known, or no such APPLY exists. Every APPLY run also records
the share of the catalogue's items without `itemClass` (`p4k_import_run.item_class_null_share`).

**Acceptance**

- [x] With no run, and with a Wiki version newer than the last run's patch, the banner shows; with
  equal versions it does not.
- [x] A later run with an unknown version does not replace the earlier run as the comparison basis.

**Enforced by:** `P4kImportDueTest`, `P4kItemClassShareTest`, `AdminP4kImportDueMvcTest` ·
**Issues:** #2895, #3034

## Out of scope

- **Media** (3D models, icons, textures, UI renders; #2826): the import reads and stores no media
  from the game archive. Fan Kit manufacturer logos (REQ-P4K-022) are not archive media.
- **PTU preview:** no catalogue of `PTU`, `EPTU`, `TECH-PREVIEW` or `HOTFIX` is read in any form.
- **Further catalogue kinds without a consumer** (contracts, missions, reputation, loot, mining,
  crafters, blueprint pools, quality distributions and quantizations, mineable elements, refining
  processes, item recovery): ignored by the import.
- **Item stats beyond blueprints:** only the stat base values blueprints need are imported
  (REQ-P4K-030).
- **Trade-route calculation and the starmap view** (#2824, #2827, #2828): this spec supplies their
  data basis (starmap objects with positions and visibility, ship cargo); the route calculation and
  the map view are designed there.
- **Features on top of loadouts and paints** (hangar configuration, Erkul import, VerseKit sync;
  #2822, #2820, #2821).
