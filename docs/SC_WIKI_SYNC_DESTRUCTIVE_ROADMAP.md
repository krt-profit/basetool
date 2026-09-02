# SC Wiki Sync — R9 Destructive Cleanup Roadmap

Doc type: **historical plan — never executed.** Restored to `docs/` on 2026-09-02 because
[`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md) and
[`SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md`](SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md) reference it six times
between them.

> [!warning] The drop this document plans has not happened — checked 2026-09-02
> `material.is_manual_entry` is still on the entity and still read by `MaterialController`,
> `MaterialMapper`, `MaterialDto` and `MaterialCreateDto`. V116 (the backfill into
> `source_systems = 'MANUAL'`) landed; the irreversible column drop never did — V117 onwards went to
> unrelated work. So this is an **open** cleanup, not a completed one, and the PR numbers it cites
> describe a state from 2026-05-29 that was never carried through.
>
> It also references `R8_DESTRUCTIVE_ROADMAP.md`, which is likewise absent from the repository. That
> is a separate work stream and was left alone.

Companion document to `SC_WIKI_SYNC_PLAN.md` §11 R9 (and §6.5 / §13 #9). The plan deliberately
scopes R9 **out** of the main rollout ("Out of scope of this plan; tracked separately like
`R8_DESTRUCTIVE_ROADMAP.md` does for the SK work") because it is the only irreversible phase and it
removes two columns that user-facing code still reads. This doc is that separate track.

> **Status (2026-05-29):** Steps 1–2 (the reader migrations — code-only and individually reversible)
> are implemented in PR [#275](https://github.com/krt-iri/basetool/pull/275). Step 4 (the irreversible
> V125 column drop) is implemented as a **draft** PR stacked on #275 — held as a draft until Step 3
> (the R8 soak, PR [#271](https://github.com/krt-iri/basetool/pull/271)) has run clean for ~two weeks;
> it **must not** merge before then.

## What R9 removes

Two legacy columns, both superseded by R2–R8 work:

- **`material.is_manual_entry`** (added V94). R8's V116 backfilled its meaning into the canonical
  `material.source_systems = 'MANUAL'`. The flag is now redundant — but the admin materials UI badge
  / filter and several DTOs still read it, so it cannot just be dropped.
- **`ship_type.description`** (the synthesized multi-line text built from `nameFull` / `scu` / crew /
  `urlWiki|urlStore`). R2/R4 added the rich `description_en` / `description_de` columns; the legacy
  synthesized column is kept for back-compat only. **§13 #9 is resolved by investigation: it is
  still consumed** — `ShipTypeDto.description` exposes it and `ship-data.html` +
  `admin/mission-data.html` render it — so its readers must migrate before the drop.

## Why a separate, staged track (not one PR)

- **Irreversible.** Dropping a column is not reversible; the per-row data is gone once the soak
  passes. Each reader-migration step below is reversible on its own; the final drop is not.
- **Two-phase rule** (`db/migration/README.md`). A column may not be dropped in the same release
  that still maps it on a Java entity — Hibernate `ddl-auto=validate` crashes on boot otherwise. The
  entity-field removal (Step 4) and the `DROP COLUMN` ship together, but only **after** the
  reader/writer migrations (Steps 1–2) have soaked.
- **User-facing.** Both columns back live UI (the materials "manual" badge/filter and the ship
  description). The migration must preserve that behaviour against the replacement columns.

## Still pending — order of operations

### Step 1 — Migrate `is_manual_entry` readers → `source_systems = 'MANUAL'` (Java + frontend, no migration)

Re-point every consumer of `is_manual_entry` to the canonical `source_systems == MANUAL` (already
backfilled by V116), then stop writing the flag. Confirmed touch points (regenerate the complete set
with `git grep -i isManualEntry` before executing — the list below is the investigated baseline, not
a guarantee):

- **Backend write path:** `UexCommodityService` — the manual-entry handover (`if
  (getIsManualEntry()) … setIsManualEntry(false)` on UEX adoption) becomes "if `source_systems ==
  MANUAL` … set `BOTH`/`UEX_ONLY`". This is the only writer besides admin create.
- **Backend DTO / API:** `MaterialDto`, `MaterialCreateDto` (`backend/.../model/dto`),
  `MaterialController`, `MaterialService` (the "manual" filter + create path). Derive the
  `isManualEntry` wire field (kept for API stability for one release) from `source_systems == MANUAL`
  rather than the column.
- **Frontend mirror + UI:** `MaterialDto`, `MaterialCreateAjaxRequest`
  (`frontend/.../model/dto`), `AdminMaterialsPageController`, and `templates/admin/materials.html`
  (the badge + the "manual only" filter) — read the source-systems-derived value.

Keep the column in place. Deploy; the admin UI must behave identically.

### Step 2 — Migrate `ship_type.description` readers → `description_en` / `description_de` (Java + frontend, no migration); §13 #9

- **Stop writing the synthesized text:** remove the `buildLegacyDescription(dto)` call +
  `shipType.setDescription(...)` from `UexVehicleService` (the rich `description_en` is already
  written one line later). Keep the helper deletable in the next PR.
- **Migrate readers to the rich columns:** `ShipTypeDto.description` → expose `descriptionEn` /
  `descriptionDe` (or a locale-resolved value); update `ship-data.html` and
  `admin/mission-data.html` to render the rich field. Regenerate the reader set with `git grep` for
  the ship-type description across both modules.
- **Pre-condition:** the rich columns must be populated for the rows the UI shows. They fill on the
  first UEX vehicle sync (R2, `description_en`) + Wiki vehicle sync (R4, `description_de`). Confirm
  no production `ship_type` row that the UI lists has a null `description_en` (or have the UI fall
  back gracefully) before removing the legacy reader.

Keep the column in place. Deploy; the ship-data / mission-data pages must render identically.

### Step 3 — Soak

Deploy Steps 1–2 and confirm in production that nothing reads `material.is_manual_entry` or
`ship_type.description` any more (`git grep` clean in `main` source set; log/APM shows no NPE on the
migrated UI pages). Soak ~one release window. This is the safety gap the two-phase rule requires
between "stop using the column" and "drop the column".

### Step 4 — V125: drop the columns + remove the entity fields (destructive)

```sql
ALTER TABLE material  DROP COLUMN is_manual_entry;
ALTER TABLE ship_type DROP COLUMN description;
```

In the **same PR**, remove the Java fields so `ddl-auto=validate` stays green:

- `Material.isManualEntry` (field + Lombok getter/setter usages — all migrated in Step 1).
- `ShipType.description` (field — all migrated in Step 2). Delete `buildLegacyDescription` if still
  present.

**V-NUMBER:** V115 went to R7 (`game_item_price`) and V116 to R8 (`is_manual_entry` backfill), and
V117–V124 were then claimed by features merged to `main` while this PR was open (job-order comment,
min-quality nullable, mission party-lead, blueprint requirement-groups/modifier-segments, FK-index
round2, item job-orders, mission-participant org-units), so this destructive drop is **V125** (the
plan §7 draft called it V116 — drift, like V112–V116 before it).

**Pre-merge gates:** Steps 1–3 in production and soaked; `git grep -i "isManualEntry\|setDescription"`
returns nothing in the `main` source set for these two fields; a full DB backup is taken
immediately before the merge. Irreversible.

## Acceptance checklist per step

- [x] Step 1 (#275): the `isManualEntry` DTO wire field is derived from `source_systems == MANUAL`
  in `MaterialMapper` (and ignored on the reverse mapping); no code writes the `is_manual_entry`
  column — admin create stamps `source_systems = MANUAL` and the UEX-adoption handover flips
  `MANUAL → UEX_ONLY`. `MaterialMapperTest` / `MaterialServiceTest` / `UexCommodityServiceTest`
  updated. The column itself stays in place until Step 4.
- [x] Step 2 (#275): `ShipMapper.shipTypeToDto` sources the `description` wire field from
  `descriptionDe ?: descriptionEn` (German preferred, English fallback) instead of the legacy
  synthesised `ship_type.description`; `UexVehicleService` no longer writes that column and the
  `buildLegacyDescription` helper is deleted. `ShipMapperTest` updated. The wire field is kept
  for API stability, so `ship-data.html` renders the rich text with no template change.
  (Correction to the original plan: `admin/mission-data.html` never rendered the ship-type
  description.) The column itself stays in place until Step 4.
- [ ] Step 3: `git grep` for both columns is clean in `main`; soak window observed; APM/logs clean
  on the migrated pages.
- [ ] Step 4 PR: V125 applied against a `.env.test` snapshot of the prod schema; `Material` /
  `ShipType` entities no longer declare the fields; `ddl-auto=validate` boots green; backup
  confirmed.

## Why not all in one PR?

Same reasoning as `R8_DESTRUCTIVE_ROADMAP.md`: the destructive drop is irreversible and the two
columns back live UI. Staging the reader migrations ahead of the drop (with a soak between) keeps
every step individually reversible and shrinks the blast radius of the one step that is not. A single
mega-PR would couple a user-facing UI refactor to an irreversible schema change and forfeit the soak.
