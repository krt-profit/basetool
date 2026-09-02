# SC Wiki Sync — R9 Destructive Cleanup Roadmap

Doc type: **historical plan — fully executed.** All four steps shipped; Step 4, the irreversible
column drop, landed on 2026-06-01 in commit `a0efd7ca1` (PR
[#281](https://github.com/krt-iri/basetool/pull/281)) as `V125__drop_legacy_material_and_ship_type_columns.sql`.
Restored to `docs/` on 2026-09-02 because [`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md) and
[`SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md`](SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md) reference it six times
between them.

> [!note] The cleanup this document plans is complete — done 2026-06-01
> `V125__drop_legacy_material_and_ship_type_columns.sql` (PR
> [#281](https://github.com/krt-iri/basetool/pull/281), commit `a0efd7ca1`) dropped both
> `material.is_manual_entry` and `ship_type.description`, and the same PR removed the JPA fields
> `Material.isManualEntry` and `ShipType.description`. (`buildLegacyDescription` had already gone one
> PR earlier, with the Step 2 reader migration in
> [#275](https://github.com/krt-iri/basetool/pull/275).) `V125MigrationTest` pins the applied
> migration.
>
> A `git grep` for `isManualEntry` or for a ship-type `description` still returns hits. Those are the
> **derived DTO wire fields**, not the dropped columns: `MaterialDto.isManualEntry` is computed in
> `MaterialMapper` from `sourceSystems == MANUAL`, and `ShipTypeDto.description` is computed in
> `ShipMapper` from `descriptionDe` falling back to `descriptionEn`. Neither reads a column that
> still exists.
>
> **Correction, recorded rather than silently rewritten:** on 2026-09-02 this callout asserted the
> opposite — that the drop had never happened and the cleanup was still open. That was wrong. It
> mistook the surviving DTO wire field for the entity field, took the grep hits as proof the column
> was still mapped, and did not check the migration directory past V116.
>
> It also references `R8_DESTRUCTIVE_ROADMAP.md`, which is absent from the repository. That is a
> separate work stream and was left alone.

Companion document to `SC_WIKI_SYNC_PLAN.md` §11 R9 (and §6.5 / §13 #9). The plan deliberately
scopes R9 **out** of the main rollout ("Out of scope of this plan; tracked separately like
`R8_DESTRUCTIVE_ROADMAP.md` does for the SK work") because it was the only irreversible phase and it
removed two columns that user-facing code still read at the time. This doc is that separate track.

> **Final status (2026-06-01):** all steps merged. Steps 1–2 (the reader migrations — code-only and
> individually reversible) shipped in PR [#275](https://github.com/krt-iri/basetool/pull/275). Step 3
> (the soak, alongside the R8 soak in PR
> [#271](https://github.com/krt-iri/basetool/pull/271)) ran clean. Step 4 (the irreversible V125
> column drop, stacked on #275 and held as a draft until the soak finished) merged on 2026-06-01 as
> commit `a0efd7ca1` in PR [#281](https://github.com/krt-iri/basetool/pull/281).

## What R9 removed

Two legacy columns, both superseded by R2–R8 work:

- **`material.is_manual_entry`** (added V94). R8's V116 backfilled its meaning into the canonical
  `material.source_systems = 'MANUAL'`. That made the flag redundant — but the admin materials UI
  badge and several DTOs still read it at the time, so it could not just be dropped.
- **`ship_type.description`** (the synthesized multi-line text built from `nameFull` / `scu` / crew /
  `urlWiki|urlStore`). R2/R4 added the rich `description_en` / `description_de` columns; the legacy
  synthesized column was kept for back-compat only. **§13 #9 was resolved by investigation: it was
  still consumed** — `ShipTypeDto.description` exposed it and `ship-data.html` rendered it — so its
  readers had to migrate before the drop.

## Why a separate, staged track (not one PR)

- **Irreversible.** Dropping a column is not reversible; the per-row data is gone once the soak
  passes. Each reader-migration step below is reversible on its own; the final drop is not.
- **Two-phase rule** (`db/migration/README.md`). A column may not be dropped in the same release
  that still maps it on a Java entity — Hibernate `ddl-auto=validate` crashes on boot otherwise. The
  entity-field removal (Step 4) and the `DROP COLUMN` shipped together, but only **after** the
  reader/writer migrations (Steps 1–2) had soaked.
- **User-facing.** Both columns backed live UI (the materials "manual" badge and the ship
  description). The migration had to preserve that behaviour against the replacement columns.

## Executed — order of operations

### Step 1 — Migrate `is_manual_entry` readers → `source_systems = 'MANUAL'` (Java + frontend, no migration) — merged in #275

Done in #275: every consumer of `is_manual_entry` was re-pointed to the canonical
`source_systems == MANUAL` (already backfilled by V116), and the flag stopped being written. Touch
points that were migrated:

- **Backend write path:** `UexCommodityService` — the manual-entry handover (`if
  (getIsManualEntry()) … setIsManualEntry(false)` on UEX adoption) became "if `source_systems ==
  MANUAL` … set `BOTH`/`UEX_ONLY`". This was the only writer besides admin create.
- **Backend DTO / API:** `MaterialDto`, `MaterialCreateDto` (`backend/.../model/dto`),
  `MaterialController`, `MaterialService` (the create path). `MaterialDto.isManualEntry` is now
  derived from `source_systems == MANUAL` rather than read from the column, and `MaterialCreateDto`
  carries no `isManualEntry` component at all — admin create stamps `source_systems = MANUAL`.
- **Frontend mirror + UI:** `MaterialDto` (`frontend/.../model/dto`),
  `AdminMaterialsPageController`, and `templates/admin/materials.html` (the badge) — they read the
  source-systems-derived value. `MaterialCreateAjaxRequest` needed no change: it never carried an
  `isManualEntry` component, because the backend stamps the provenance server-side on creation.

The column stayed in place for this step. Deployed; the admin UI behaved identically.

> [!note] On "kept for API stability for one release"
> The original draft of this step described the surviving `isManualEntry` wire field as a temporary
> courtesy. That note is **superseded, not expired.** Because Step 1 re-sourced the field from
> `source_systems`, and no DTO exposes `source_systems` itself, `MaterialDto.isManualEntry` is now
> the **only** wire channel for that information. It is permanent API surface; reshaping it is a
> separate decision, not an R9 leftover.

### Step 2 — Migrate `ship_type.description` readers → `description_en` / `description_de` (Java + frontend, no migration); §13 #9 — merged in #275

Done in #275:

- **Stopped writing the synthesized text:** the `buildLegacyDescription(dto)` call +
  `shipType.setDescription(...)` were removed from `UexVehicleService` (the rich `description_en` is
  written one line later anyway).
- **Migrated readers to the rich columns:** `ShipMapper` now sources the `ShipTypeDto.description`
  wire field from `descriptionDe` falling back to `descriptionEn`, so `ship-data.html` renders the
  rich text with no template change.
- **Pre-condition, checked:** the rich columns had to be populated for the rows the UI shows. They
  fill on the first UEX vehicle sync (R2, `description_en`) + Wiki vehicle sync (R4,
  `description_de`), and the German-preferred / English-fallback resolution covers rows that only
  one sync has reached.

The column stayed in place for this step. Deployed; the ship-data page rendered identically.

> [!note] On "kept for API stability for one release"
> As with `isManualEntry`, this note is **superseded, not expired.** Step 2 re-sourced
> `ShipTypeDto.description` from `description_de` / `description_en`, and no DTO exposes those two
> columns directly — so the `description` wire field is now the **only** channel through which an API
> client can read a ship-type description. It is permanent API surface; reshaping it into explicit
> `descriptionDe` / `descriptionEn` fields is a separate decision, not an R9 leftover.

### Step 3 — Soak — completed before #281 merged

Steps 1–2 were deployed and confirmed in production: nothing read `material.is_manual_entry` or
`ship_type.description` any more (`git grep` clean in the `main` source set; log/APM showed no NPE on
the migrated UI pages). The soak ran alongside the R8 soak (#271) for the release window — the safety
gap the two-phase rule requires between "stop using the column" and "drop the column".

### Step 4 — V125: drop the columns + remove the entity fields (destructive) — merged 2026-06-01

Shipped as `backend/src/main/resources/db/migration/V125__drop_legacy_material_and_ship_type_columns.sql`
in commit `a0efd7ca1` (PR [#281](https://github.com/krt-iri/basetool/pull/281)):

```sql
ALTER TABLE material  DROP COLUMN IF EXISTS is_manual_entry;
ALTER TABLE ship_type DROP COLUMN IF EXISTS description;
```

The **same PR** removed the Java fields so `ddl-auto=validate` stayed green:

- `Material.isManualEntry` (field + Lombok getter/setter usages — all migrated in Step 1).
- `ShipType.description` (field — all migrated in Step 2). The `buildLegacyDescription` helper was
  already deleted with the Step 2 reader migration in #275, so this PR only had to drop the field.

`V125MigrationTest` pins the applied migration.

**V-NUMBER:** V115 went to R7 (`game_item_price`) and V116 to R8 (`is_manual_entry` backfill), and
V117–V124 were then claimed by features merged to `main` while this PR was open (job-order comment,
min-quality nullable, mission party-lead, blueprint requirement-groups/modifier-segments, FK-index
round2, item job-orders, mission-participant org-units), so this destructive drop landed as **V125**
(the plan §7 draft called it V116 — drift, like V112–V116 before it).

**Pre-merge gates — all satisfied by #281:** Steps 1–3 were in production and soaked; `git grep -i
"isManualEntry\|setDescription"` returned nothing in the `main` source set for these two *entity*
fields (the remaining `isManualEntry` hits are the derived `MaterialDto` wire field, which is not a
column reader); a full DB backup was taken immediately before the merge. Irreversible, and done.

## Acceptance checklist per step

- [x] Step 1 (#275): the `isManualEntry` DTO wire field is derived from `source_systems == MANUAL`
  in `MaterialMapper` (and ignored on the reverse mapping); no code writes the `is_manual_entry`
  column — admin create stamps `source_systems = MANUAL` and the UEX-adoption handover flips
  `MANUAL → UEX_ONLY`. `MaterialMapperTest` / `MaterialServiceTest` / `UexCommodityServiceTest`
  updated. The column itself stayed in place until Step 4.
- [x] Step 2 (#275): `ShipMapper.shipTypeToDto` sources the `description` wire field from
  `descriptionDe ?: descriptionEn` (German preferred, English fallback) instead of the legacy
  synthesised `ship_type.description`; `UexVehicleService` no longer writes that column and the
  `buildLegacyDescription` helper is deleted. `ShipMapperTest` updated. The wire field was kept
  for API stability, so `ship-data.html` renders the rich text with no template change — and that
  "for one release" framing is **superseded, not expired**: the wire field is now the only channel
  exposing a ship-type description at all (see the note under Step 2).
  (Correction to the original plan: `admin/mission-data.html` never rendered the ship-type
  description.) The column itself stayed in place until Step 4.
- [x] Step 3: `git grep` for both columns was clean in `main`; the soak window was observed; APM/logs
  were clean on the migrated pages.
- [x] Step 4 PR ([#281](https://github.com/krt-iri/basetool/pull/281), commit `a0efd7ca1`,
  2026-06-01): `V125__drop_legacy_material_and_ship_type_columns.sql` applied; `Material` /
  `ShipType` no longer declare the fields; `ddl-auto=validate` boots green; backup confirmed;
  `V125MigrationTest` pins the applied migration.

## Why not all in one PR?

Same reasoning as `R8_DESTRUCTIVE_ROADMAP.md`: the destructive drop was irreversible and the two
columns backed live UI. Staging the reader migrations ahead of the drop (with a soak between) kept
every step individually reversible and shrank the blast radius of the one step that was not. A single
mega-PR would have coupled a user-facing UI refactor to an irreversible schema change and forfeited
the soak.
