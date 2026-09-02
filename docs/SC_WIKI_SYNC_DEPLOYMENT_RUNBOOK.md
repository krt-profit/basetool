# Deployment Runbook - SC Wiki + UEX-Items Sync

Doc type: **historical plan** — the phase-by-phase deployment procedure for the rollout described in
[`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md). Restored to `docs/` on 2026-09-02 alongside it; four
Javadoc comments still send an operator here ("flip on per the deployment runbook §3/§4/§6").

> [!warning] Every phase has shipped, and every flag it tells you to flip is already on
> Verified against `main` on 2026-09-02: `application-prod.yml` sets `scheduler-enabled`,
> `commodity-sync-enabled`, `vehicle-sync-enabled`, `item-sync-enabled`, `blueprint-sync-enabled`,
> `manufacturer-sync-enabled` **and** `sync-all-items` to `true`. The enable-and-soak instructions
> below are therefore a record of how the rollout was done, not a procedure with anything left to do.
> The status table in the next section reflects the rollout as it stood when this document was
> written and has not been maintained since.
>
> What stays useful is the **verification** half: the per-phase `psql` queries that assert the sync
> is behaving (Wiki-only rows invisible, `source_systems` flipping `UEX_ONLY` to `BOTH`, the audit
> events a healthy run produces). Those are still the fastest way to check a suspect run — subject
> to the repository's production-access rule, which permits read-only inspection **only** after
> explicit per-command approval by @greluc.

For what the sync does today, read `10 Systems/SC Wiki.md` in the Basetool Knowledge Base. Do not
edit this document to track new changes.

Operational runbook for the staged production rollout of the SC Wiki + UEX-Items
sync (see [`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md) for the design and
[`SC_WIKI_SYNC_AGENT_PROMPT.md`](SC_WIKI_SYNC_AGENT_PROMPT.md) for the implementation
briefing). The work lands across **nine consolidated PRs** (R1 -> R9), deployed
in order with soak windows between phase transitions. This document is the
step-by-step guide for executing that rollout safely.

The structure mirrors [`SK_ROLLOUT_RUNBOOK.md`](SK_ROLLOUT_RUNBOOK.md). R1 is
fully written below; R2 - R9 are stubbed with their headers so the runbook
grows in lockstep with each phase PR. **Each phase PR extends its section
in this file as part of the same commit that ships the code.**

---

## Status table - which phase has shipped to which environment

Updated as each phase deploys. The "PR" column points to the merged Pull
Request; "Date" is the production deploy timestamp.

|                 Phase                 | Dev | Staging | Prod | Date | PR  |                                                Notes                                                 |
|---------------------------------------|-----|---------|------|------|-----|------------------------------------------------------------------------------------------------------|
| R1 - Foundation                       | TBD | TBD     | TBD  | TBD  | TBD | additive only; scheduler default off                                                                 |
| R2 - UEX items + vehicle hardening    | TBD | TBD     | TBD  | TBD  | TBD | game_item table + 47 ship_type flags; no Wiki traffic                                                |
| R3 - Wiki commodity merge             | TBD | TBD     | TBD  | TBD  | TBD | ships dark; commodity-sync-enabled flag turns Wiki traffic on                                        |
| R4 - Wiki items + Blueprints          | TBD | TBD     | TBD  | TBD  | TBD | blueprint graph + closure item fill + vehicle fill; 3 dark flags                                     |
| R5 - Full Wiki item backfill          | TBD | TBD     | TBD  | TBD  | TBD | needs item-sync-enabled + sync-all-items both on; ~12 700 rows; NO migration                         |
| R6 - Manufacturer Wiki reconciliation | TBD | TBD     | TBD  | TBD  | TBD | needs manufacturer-sync-enabled; enriches scwiki_uuid/code on UEX rows; NO migration                 |
| R7 - UEX item prices                  | TBD | TBD     | TBD  | TBD  | TBD | needs item-price-sync-enabled; V115 game_item_price (~24k rows); display-only                        |
| R8 - Soak + V116 cleanup              | TBD | TBD     | TBD  | TBD  | TBD | flips is_manual_entry -> source_systems=MANUAL (was V115, shifted by R7)                             |
| R9 - V125 destructive cleanup         | TBD | TBD     | TBD  | TBD  | TBD | dropped is_manual_entry + ship_type.description as V125 on 2026-06-01 (was V116/V117, shifted by R7) |

---

## 0. Conventions

- Cite every env var by name and where it is set (Docker Compose env file,
  GitHub Actions secrets, runtime override).
- For every "run X" step, give the exact command, the expected output, and
  what to do if it diverges.
- Every irreversible step gets its own **Rollback** subsection so the on-call
  engineer never has to derive the reversal under pressure.
- All paths in this runbook are relative to the repo root unless prefixed
  with `prod:` (the production host filesystem).
- Times are UTC. Backups carry a `YYYYMMDD-HHMM` UTC timestamp suffix.

### 0.1 Tools / prerequisites

- `./gradlew` (project wrapper - never the IDE / global gradle).
- `docker` + `docker compose` plugin v2+ for the local test stack.
- `psql` 18+ client.
- `gh` CLI (authenticated as a repo collaborator) for the PR / status checks.
- `.env.test` at the repo root with throwaway credentials and a locally
  generated `keystore.p12` (README's "Running the Local Test Stack" has the
  exact commands). **NEVER use the production `.env` for staging.**

### 0.2 Stage on `.env.test` first

Before every prod deploy in this rollout, replay the migration stack against
a locally restored prod snapshot:

```bash
docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml \
    --profile dev up -d db-backend-dev
# restore the prod snapshot into the throwaway DB
psql -h localhost -p 15432 -U test -d basetool < prod-snapshot.sql
# run Flyway via the app boot (Ctrl-C after migrations apply)
./gradlew :backend:bootRun
```

Confirm the migrations apply cleanly and `ddl-auto: validate` does not log
any drift. Tear the test stack down with
`docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml --profile dev down --volumes`
afterwards.

### 0.3 Backup posture per phase

- **R1, R2, R3, R4, R6, R7** - additive migrations only; **logical backup
  (`pg_dump --format=custom`) is sufficient** as defence against an app-layer
  regression that corrupts existing rows during a dual-write window.
- **R5** - large data backfill (~12 700 Wiki item rows); take a logical
  backup of `game_item` + `manufacturer` immediately before the deploy so
  the rollback is a simple table re-load.
- **R8** - data-only migration (V116 backfills `source_systems = 'MANUAL'`
  from `is_manual_entry`); logical backup of `material` only.
- **R9** - destructive (drops `material.is_manual_entry` and
  `ship_type.description`); requires **both** a logical dump AND a
  `pg_basebackup` for point-in-time recovery.

Verification is mandatory: restore the dump into a throwaway PostgreSQL
container and run a count check against the aggregate tables that this phase
touches (e.g. R3 verifies `material`, `material_external_alias`). If the
counts diverge from prod, the dump is suspect and the deploy must be aborted.

---

## 1. R1 - Foundation deployment

### 1.1 Scope summary

R1 ships **plumbing only** with zero behaviour change to existing users:

- Flyway migrations **V106 - V109**:
  - **V106** - 10 additive columns on `material` (Wiki cross-ref, density,
    `is_visible`, `source_systems`) + `UNIQUE(scwiki_uuid)` + indexes on
    `source_systems` / `is_visible`. Defaults `is_visible = TRUE`,
    `source_systems = 'UEX_ONLY'` to preserve the pre-Wiki catalogue's
    visibility.
  - **V107** - 10 additive columns on `manufacturer` (UEX company id, Wiki
    UUID, industry, `is_item_manufacturer`, sync timestamps) + 2 UNIQUE
    constraints.
  - **V108** - new `material_external_alias` table + index + unique
    constraint, plus 6 SC Wiki -> UEX seed aliases (conditional on the
    target material existing; on a fresh DB without UEX sync data the
    seed is a no-op).
  - **V109** - new `uex_category` reference table (empty; populated by the
    R2 `UexCategoryRefService`).
- New `integration/scwiki/` package: `ScWikiClient` (paginated, ETag-cached,
  rate-limit-paced), `ScWikiScheduler` skeleton (no-op while
  `krt.scwiki.scheduler-enabled` is `false`).
- `ScWikiProperties` (`krt.scwiki.*`) with R1 default `scheduler-enabled = false`.
- `AsyncConfig.SCWIKI_EXECUTOR` thread pool (size 2, queue 0, MDC-propagating).
- Admin CRUD at `/admin/material-aliases` (Thymeleaf page + REST at
  `/api/v1/material-external-aliases`).
- ArchUnit guard: every class in `integration.scwiki` must inject
  `ScWikiClient`.
- Tests: WireMock-driven `ScWikiClientTest`, Mockito
  `MaterialExternalAliasServiceTest`, MockMvc
  `MaterialExternalAliasControllerTest`, TestContainers
  `V106 / V107 / V108 / V109 MigrationTest`.

**No live SC Wiki traffic** is generated by R1 - the scheduler is disabled
by default and no sync services consume its tick.

### 1.2 Pre-deployment checks (R1)

- [ ] Production is on `main` (commit ada0477c or later).
- [ ] `SELECT version FROM flyway_schema_history ORDER BY version DESC LIMIT 5`
  on prod shows V105 as the latest applied migration; no V106+ entries.
- [ ] `./gradlew spotlessApply check` is green from a clean clone of the R1 PR
  branch (Checkstyle 0 warnings, SpotBugs 0 findings, every test passes).
- [ ] The local test stack reaches startup with the R1 branch:
  `docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml --profile dev up -d`
  then check `docker compose logs backend-dev | grep "Started BackendApplication"`.
- [ ] `psql` against the local test stack confirms Flyway applied V106 - V109
  (`SELECT version FROM flyway_schema_history WHERE version IN ('106','107','108','109')`).
- [ ] `.env` on prod does NOT set `KRT_SCWIKI_SCHEDULER_ENABLED=true` (or any
  variant). Leaving it unset keeps R1's safe default.

### 1.3 Deployment steps (production, R1)

1. **Take the logical backup.**

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r1-$(date -u +%Y%m%d-%H%M).dump basetool
   ```

   Verify the dump opens:

   ```bash
   pg_restore --list basetool-pre-r1-*.dump | head -20
   ```
2. **Merge the R1 PR to `main`.**
3. **Watch the prod deploy logs** for the Flyway lines applying V106 - V109.
   Expected sequence in `logs/backend.json`:

   ```
   Successfully validated 105 migrations (execution time 00:00.NNN)
   Current version of schema "public": 105
   Migrating schema "public" to version "106 - add scwiki columns to material"
   Migrating schema "public" to version "107 - add cross ref columns to manufacturer"
   Migrating schema "public" to version "108 - create material external alias"
   Migrating schema "public" to version "109 - create uex category"
   Successfully applied 4 migrations to schema "public", now at version v109
   ```
4. **Confirm the application started** without `ddl-auto: validate` failures:

   ```
   Started BackendApplication in N.NNN seconds
   ```

   A schema-validation failure would show as
   `Schema-validation: missing column [scwiki_uuid] in table [material]` -
   that would mean V106 did not apply, abort and roll back per §1.5.

### 1.4 Smoke tests (R1, post-deploy)

Run these on the prod host immediately after the deploy finishes.

```bash
# 1. New columns are visible on material.
psql -d basetool -c "\d material" | grep -E 'scwiki_uuid|source_systems|is_visible'
# Expect: three rows, one per column.

# 2. material_external_alias table exists; seed row count (depends on whether
#    UEX-sourced materials were present at Flyway-run time).
psql -d basetool -c "SELECT count(*) FROM material_external_alias WHERE created_by='system'"
# Expect: 6 on a healthy prod (UEX commodity sync has populated the materials).
#         0 on a fresh staging DB - add aliases via the admin UI.

# 3. uex_category table exists, empty.
psql -d basetool -c "SELECT count(*) FROM uex_category"
# Expect: 0 (R2 populates it).

# 4. Admin API accepts a list call (with an admin JWT).
curl -fsS -H "Authorization: Bearer <admin-jwt>" \
  https://<host>/api/v1/material-external-aliases | head -c 200
# Expect: JSON array (may be empty).

# 5. Admin page renders.
curl -fsS -H "Cookie: <session-cookie>" \
  https://<host>/admin/material-aliases | grep -c 'admin.materialAlias.title'
# Expect: >= 1 (the i18n key resolves in the rendered HTML).

# 6. UEX scheduler is still alive.
docker compose logs backend | grep "Scheduled task for UEX data" | tail -1
# Expect: a recent timestamp within the past hour.

# 7. SC Wiki scheduler is alive but disabled (logged INFO line).
docker compose logs backend | grep -E "ScWikiScheduler.*disabled"
# Expect: at least one match (the scheduler fires immediately at boot, then
#         again every 24h; the disabled guard short-circuits with the log).
```

### 1.5 Rollback (R1)

R1 migrations are all additive and the application code carries no `ddl-auto:
update` machinery, so the rollback is a clean column / table drop in reverse
order.

1. **Revert the merge** on `main` to restore the pre-R1 code.
2. Drop the new tables and columns:

   ```sql
   DROP TABLE IF EXISTS uex_category;            -- V109
   DROP TABLE IF EXISTS material_external_alias; -- V108
   ALTER TABLE manufacturer
     DROP COLUMN IF EXISTS uex_company_id,
     DROP COLUMN IF EXISTS scwiki_uuid,
     DROP COLUMN IF EXISTS scwiki_code,
     DROP COLUMN IF EXISTS industry,
     DROP COLUMN IF EXISTS is_item_manufacturer,
     DROP COLUMN IF EXISTS is_vehicle_manufacturer,
     DROP COLUMN IF EXISTS uex_synced_at,
     DROP COLUMN IF EXISTS scwiki_synced_at,
     DROP COLUMN IF EXISTS uex_deleted_at,
     DROP COLUMN IF EXISTS scwiki_deleted_at;     -- V107
   ALTER TABLE material
     DROP COLUMN IF EXISTS scwiki_uuid,
     DROP COLUMN IF EXISTS scwiki_key,
     DROP COLUMN IF EXISTS scwiki_slug,
     DROP COLUMN IF EXISTS scwiki_synced_at,
     DROP COLUMN IF EXISTS scwiki_deleted_at,
     DROP COLUMN IF EXISTS density_g_per_cc,
     DROP COLUMN IF EXISTS instability,
     DROP COLUMN IF EXISTS resistance,
     DROP COLUMN IF EXISTS is_visible,
     DROP COLUMN IF EXISTS source_systems;       -- V106
   DELETE FROM flyway_schema_history WHERE version IN ('106','107','108','109');
   ```
3. Restart the backend. `ddl-auto: validate` should pass against the pre-R1
   entity shape (which is the code that just re-deployed).

If the restart fails to validate, restore the pre-R1 `pg_dump` instead.

### 1.6 Monitoring during the soak window (R1)

The R1 soak is **>= 1 release** before R2 merges. Watch for:

- `logs/backend.json` `level=ERROR` count - baseline before R1 vs. baseline
  after. The R1 add is plumbing only; an error-rate climb means an
  unexpected interaction with `ddl-auto: validate` or the new entity fields.
- Hibernate validation log lines on every restart - none expected.
- `ScWikiScheduler invoked but disabled` INFO lines once per 24 h - if these
  vanish, the scheduler bean is not loading; check
  `@ConditionalOnProperty` wiring (R1 has none, but R2+ may add some).
- Admin pages: `/admin/material-aliases` opens and lists the 6 seed rows
  (or accepts a manual add when the seed was a no-op).

---

## 2. R2 - UEX item catalogue + UEX manufacturer / vehicle hardening

### 2.1 Scope summary (R2)

R2 ships the UEX side of the sync end-to-end with **no Wiki traffic** —
the Wiki scheduler stays at the R1 default (`krt.scwiki.scheduler-enabled
= false`).

- Flyway migrations **V110 - V112**:
  - **V110** - new `game_item` table keyed by `external_uuid` UNIQUE (R2
    relaxed to NULLABLE — ~30 % of UEX items ship with an empty uuid)
    + `uex_item_id` UNIQUE. Wiki-sourced columns (`scwiki_*`,
      `classification`, `mass`, dimensions, descriptions) land in the
      schema but stay NULL until R4 writes them. FKs to `manufacturer`,
      `uex_category`, `ship_type` (for vehicle-bound items).
  - **V111** - extends `ship_type` with `external_uuid` UNIQUE,
    `uex_vehicle_id` UNIQUE, 36 capability `is_*` flags, dimensions,
    fuel, urls, English description, R2 `source_systems` column. The
    legacy synthesized `description` column stayed for back-compat and
    was dropped in R9 (V125, 2026-06-01).
  - **V112** - tiny fix-up that adds `created_at` / `updated_at`
    columns missed by R1's V109 on `uex_category` (Hibernate
    `ddl-auto: validate` requires them since `UexCategory` extends
    `AbstractEntity`).
- `UexCategoryRefService` populates the 98+ rows of `uex_category` once
  per scheduler tick.
- `UexItemSyncService` iterates the game-related categories, calls
  `/items?id_category=<n>` per category, and upserts rows into
  `game_item`. Resolution chain: `findByUexItemId` →
  `findByExternalUuid` → create-new. Kind derivation from the row's
  category section per Plan §6.3.1 (Armor → ARMOR, "Vehicle Weapons"
  → VEHICLE_WEAPON, …). Orphan sweep marks rows whose `uex_item_id`
  no longer appears as `uex_deleted_at`.
- `UexManufacturerService` rewritten to persist **both** item and
  vehicle manufacturers (item catalog needs them too). Match by
  `uex_company_id` first, name-fallback for legacy rows.
- `UexVehicleService` rewritten for the UUID-first chain
  (`findByExternalUuid` → `findByUexVehicleId` →
  `findByNameIgnoreCase` fallback). The name-fallback hit
  **backfills** `external_uuid` + `uex_vehicle_id` on the matched row
  — R2's substitute for the planned standalone V112 data migration.
  All 36 capability flags + dims + fuel + urls + English description
  land on `ship_type`.
- `UexScheduler` calls in topological order:
  universe → commodities → manufacturers → vehicles → categories
  → items → refineries.

### 2.2 Pre-deployment checks (R2)

- [ ] R1 (PR [#261](https://github.com/krt-iri/basetool/pull/261))
  deployed to prod and stable through one full UEX scheduler tick.
- [ ] `SELECT version FROM flyway_schema_history ORDER BY version DESC
  LIMIT 5` on prod shows V109 as the latest applied migration; no
  V110+ entries.
- [ ] `./gradlew spotlessApply check` is green from a clean clone of
  the R2 branch.
- [ ] `psql` on prod confirms `material_external_alias` has the 6
  R1 seed rows (UEX sync has populated the target materials).
- [ ] `.env` on prod still has `KRT_SCWIKI_SCHEDULER_ENABLED=false`
  (R2 does not flip it).
- [ ] `.env` on prod has `KRT_UEX_SCHEDULER_ENABLED=true` (R2 expands
  the UEX work; the hourly sync must continue).

### 2.3 Deployment steps (production, R2)

1. **Take the logical backup.**

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r2-$(date -u +%Y%m%d-%H%M).dump basetool
   ```
2. **Merge the R2 PR.** Watch the prod deploy logs for the Flyway
   lines applying V110 - V112.

   ```
   Migrating schema "public" to version "110 - create game item"
   Migrating schema "public" to version "111 - extend ship type with uex and wiki fields"
   Migrating schema "public" to version "112 - add audit columns to uex category"
   ```
3. **Watch the first UEX scheduler tick** (~within an hour of deploy).
   Expected log lines under one tick:

   ```
   Starting synchronization of UEX manufacturers...
   Finished UEX manufacturer sync: NNN added, NNN updated, ...
   Starting synchronization of UEX vehicles (ships)...
   Finished UEX vehicle sync: NNN added, NNN updated, ...
   Starting synchronization of UEX categories...
   Finished UEX category sync: NNN added, NNN updated
   Starting synchronization of UEX items...
   Finished UEX item sync: NN categories visited, NNNN items upserted (NNNN new, N updated)
   ```

### 2.4 Smoke tests (R2, post-deploy)

```bash
# 1. New tables / columns visible.
psql -d basetool -c "\d game_item" | grep -E 'external_uuid|uex_item_id|kind'
psql -d basetool -c "\d ship_type" | grep -E 'external_uuid|is_bomber|fuel_quantum'

# 2. uex_category populated.
psql -d basetool -c "SELECT count(*) FROM uex_category"
# Expect: >= 98 after the first UEX scheduler tick.

# 3. game_item populated.
psql -d basetool -c "SELECT count(*) FROM game_item WHERE source_systems='UEX_ONLY'"
# Expect: thousands (one row per UEX item, ~3500-5000 depending on
# how many UEX categories are game-related).

# 4. ship_type external_uuid backfilled on most rows.
psql -d basetool -c "SELECT count(*) FILTER (WHERE external_uuid IS NOT NULL) AS with_uuid, \
                              count(*) FILTER (WHERE external_uuid IS NULL)     AS without_uuid \
                          FROM ship_type"
# Expect: most rows have external_uuid (UEX UUIDs are stable). The
# without_uuid count is the ~31% UEX-empty-uuid set + admin-created
# entries.

# 5. UEX manufacturer cross-ref populated.
psql -d basetool -c "SELECT count(*) FROM manufacturer WHERE uex_company_id IS NOT NULL"
# Expect: matches the company count UEX returned on the latest sync.

# 6. SC Wiki scheduler still disabled (R2 ships zero Wiki traffic).
docker compose logs backend | grep -E "ScWikiScheduler.*disabled"
# Expect: at least one match per 24h tick.
```

### 2.5 Rollback (R2)

1. **Revert the merge** on `main` to restore the R1 code.
2. Drop the new tables / columns in reverse order:

   ```sql
   ALTER TABLE uex_category
     DROP COLUMN IF EXISTS created_at,
     DROP COLUMN IF EXISTS updated_at;
   ALTER TABLE ship_type
     DROP COLUMN IF EXISTS external_uuid,
     DROP COLUMN IF EXISTS uex_vehicle_id,
     DROP COLUMN IF EXISTS uex_slug,
     DROP COLUMN IF EXISTS scwiki_slug,
     -- … repeat for every column V111 added; see V111__*.sql for the list.
     DROP COLUMN IF EXISTS source_systems;
   DROP TABLE IF EXISTS game_item;
   DELETE FROM flyway_schema_history WHERE version IN ('110','111','112');
   ```
3. Restart the backend; `ddl-auto: validate` must pass against the
   R1 schema.

### 2.6 Monitoring during the soak window (R2)

- `game_item` row count should be stable after the first sync; it
  grows only when UEX adds new items (rare, every patch).
- `ship_type.external_uuid` coverage should hold at ~70 % (the rest is
  the UEX-empty-uuid set + admin entries).
- No new `ERROR` log lines in the UEX sync chain — the orphan sweep is
  gated on a non-empty seen-id set, so a transient UEX outage produces
  WARNs but no errors.
- The new `uex_synced_at` column on `manufacturer` should be within
  the last hour for every row that had a recent UEX sync.

---

## 3. R3 - Wiki commodity merge

### 3.1 Scope summary (R3)

R3 ships the first SC Wiki sync — the commodity merge — but **dark**: the
code, audit table and admin page all land, yet **no live Wiki traffic is
generated** until an operator flips `krt.scwiki.commodity-sync-enabled` to
`true` (default `false`, mirroring R7's price flag). The master switch
`krt.scwiki.scheduler-enabled` default flips to `true` in R3 so the
scheduler bean ticks; each tick still no-ops while the per-sync flag is off.

- Flyway migration **V113** — new append-only `external_sync_report`
  audit table (`run_id`, `ran_at`, `source_system` CHECK, `event_type`,
  `aggregate`, external ref columns, `detail`) + two indexes.
- `SyncReportService` — collects findings into `external_sync_report`,
  keeps the last 30 runs per source.
- `ScWikiCommoditySyncService` — pulls `/api/commodities`, applies the
  §8.9 hard-junk name filter, and merges the rest into `material` via the
  §8.1.1 resolution chain (`scwiki_uuid` → alias table → exact name →
  canonical name with multi-match rejection). Conflict policy (§4.6): UEX
  stays canonical for `name` / `code` / `kind` / prices / flags; the Wiki
  sync only writes `scwiki_uuid` / `scwiki_key` / `scwiki_slug` /
  `density_g_per_cc` and flips `source_systems` `UEX_ONLY → BOTH`.
  Wiki-only commodities become fresh `WIKI_ONLY` rows **inserted invisible**
  (`is_visible = false`) so they never reach the trading UI until reviewed.
- Admin sync-report pages at `/admin/sync-reports`, `/scwiki`, `/uex`
  (read-only, ADMIN-gated) backed by `GET /api/v1/sync-reports`.
- **Deviation from plan §8.1 pseudocode (documented in the service):** a
  canonical *multi-match* skips the row (logs `MULTI_MATCH_AMBIGUOUS`)
  instead of creating a `WIKI_ONLY` row, because stamping the Wiki UUID on
  a new row would let step 1 shadow the admin's later alias fix forever.

### 3.2 Pre-deployment checks (R3)

- [ ] R2 (PR [#263](https://github.com/krt-iri/basetool/pull/263)) deployed
  and stable; `game_item` + `material` cross-ref columns populated.
- [ ] `SELECT version FROM flyway_schema_history ORDER BY version DESC
  LIMIT 5` shows V112 as the latest applied migration; no V113.
- [ ] `./gradlew spotlessApply check` green from a clean clone of the R3
  branch.
- [ ] Decide the soak cadence: leave `krt.scwiki.scheduler-delay` at the
  24h default, or set it to weekly (`604800000`) for the first runs
  per plan §11 R3 ("weekly first, escalate to 24h once the report is
  clean").
- [ ] Confirm `krt.scwiki.commodity-sync-enabled` is **unset / false** in
  prod `.env` for the initial deploy — R3 ships dark.

### 3.3 Deployment steps (production, R3)

1. **Take the logical backup** (commodity merge mutates `material`):

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r3-$(date -u +%Y%m%d-%H%M).dump basetool
   ```
2. **Merge the R3 PR.** Watch the deploy log for V113:

   ```
   Migrating schema "public" to version "113 - create external sync report"
   ```
3. **Confirm the dark state.** Within 24h the SC Wiki scheduler ticks and
   logs (commodity sync is still gated off):

   ```
   SC Wiki commodity sync invoked but disabled (krt.scwiki.commodity-sync-enabled=false) — skipping.
   ```
4. **Enable the merge when ready.** Set `KRT_SCWIKI_COMMODITY_SYNC_ENABLED=true`
   in the prod `.env` and restart the backend (or wait for the next tick if
   the runtime re-reads properties). The next tick runs the real merge:

   ```
   Starting SC Wiki commodity merge...
   Finished SC Wiki commodity merge: NNN linked, NN created WIKI_ONLY, NN junk-skipped, N ambiguous-skipped.
   ```

### 3.4 Smoke tests (R3, post-deploy)

```bash
# 1. Audit table exists.
psql -d basetool -c "\d external_sync_report" | grep -E 'run_id|event_type|source_system'

# 2. Admin sync-report page renders (ADMIN session).
curl -fsS -H "Cookie: <session-cookie>" https://<host>/admin/sync-reports/scwiki \
  | grep -c 'admin.syncReports.title'   # expect >= 1

# --- after enabling commodity-sync-enabled and one tick: ---

# 3. Events were recorded.
psql -d basetool -c "SELECT event_type, count(*) FROM external_sync_report \
                       WHERE source_system='SCWIKI' GROUP BY event_type ORDER BY 1"
# Expect a mix of LINKED_VIA_ALIAS / CREATED_WIKI_ONLY / SKIP_JUNK; possibly
# LOOKS_LIKE_ITEM / MULTI_MATCH_AMBIGUOUS.

# 4. Existing UEX commodities gained Wiki cross-refs (UEX_ONLY -> BOTH).
psql -d basetool -c "SELECT source_systems, count(*) FROM material GROUP BY source_systems"
# Expect BOTH > 0 and the bulk of pre-R3 rows still UEX_ONLY (Wiki-only gaps).

# 5. CRITICAL — Wiki-only rows are invisible (must NOT pollute trading).
psql -d basetool -c "SELECT count(*) FILTER (WHERE is_visible) AS visible, \
                            count(*) FILTER (WHERE NOT is_visible) AS hidden \
                       FROM material WHERE source_systems='WIKI_ONLY'"
# Expect visible = 0 : every WIKI_ONLY row ships invisible until admin review.

# 6. Existing UEX-sourced catalogue stayed visible.
psql -d basetool -c "SELECT count(*) FROM material WHERE source_systems IN ('UEX_ONLY','BOTH') AND NOT is_visible"
# Expect 0 : the merge never hides a UEX row.
```

### 3.5 Rollback (R3)

The merge only adds Wiki-owned columns + new invisible rows, so the
fastest rollback is to **disable the flag** (`KRT_SCWIKI_COMMODITY_SYNC_ENABLED=false`)
— the trading UI is unaffected because Wiki-only rows were never visible.
For a full revert:

1. Set `KRT_SCWIKI_COMMODITY_SYNC_ENABLED=false`, revert the merge on `main`.
2. Optionally clean up Wiki-only rows + cross-refs:

   ```sql
   DELETE FROM material WHERE source_systems = 'WIKI_ONLY';
   UPDATE material SET source_systems = 'UEX_ONLY', scwiki_uuid = NULL,
          scwiki_key = NULL, scwiki_slug = NULL, scwiki_synced_at = NULL,
          scwiki_deleted_at = NULL, density_g_per_cc = NULL
     WHERE source_systems = 'BOTH';
   DROP TABLE IF EXISTS external_sync_report;   -- V113
   DELETE FROM flyway_schema_history WHERE version = '113';
   ```

   Restart; `ddl-auto: validate` must pass against the R2 schema. The
   logical backup is the fallback if the in-place cleanup misbehaves.

### 3.6 Monitoring during the soak window (R3)

- Review `/admin/sync-reports/scwiki` after the first enabled run:
  `MULTI_MATCH_AMBIGUOUS` events are the action items — add a
  `material_external_alias` row for each, then the next run links them.
- `CREATED_WIKI_ONLY` / `LOOKS_LIKE_ITEM` rows are invisible by design;
  flip `is_visible` only after confirming the entry is a real commodity.
- Watch for `SKIP_JUNK` spikes — a sudden jump means the Wiki added a new
  junk-name variant; extend `HARDCODED_ATMOSPHERE_SET` via PR.
- Confirm the trading / refinery UI is unchanged (Wiki-only rows invisible,
  UEX names untouched). Escalate the schedule from weekly to 24h once the
  report is clean (per plan §11 R3).

---

## 4. R4 - Wiki items + Blueprints + Wiki vehicles

### 4.1 Scope summary (R4)

R4 adds the SC Wiki blueprint graph, the closure-mode Wiki item fill, and the
Wiki vehicle fill. Like R3 it ships **dark**: three independent per-sync flags
default `false`, so no R4 Wiki traffic is generated until an operator flips
each on. (Note the migration is **V114**, not the plan's draft V113 — V113 was
consumed by R3's `external_sync_report`.)

- Migration **V114** — `blueprint` + `blueprint_ingredient` +
  `blueprint_dismantle_return`. Ingredient CHECK constraints are **relaxed**
  vs. the plan's §6.3.3 draft: they enforce kind/FK and kind/quantity
  exclusivity but permit a NULL matching FK so an *unresolved* ingredient
  line persists its Wiki snapshot for later re-resolution (§8.2).
- `ScWikiBlueprintSyncService` (flag `krt.scwiki.blueprint-sync-enabled`) —
  pulls `/api/blueprints`, upserts the recipe graph. RESOURCE ingredients
  resolve to `material` (scwiki_uuid → alias → name), ITEM ingredients to
  `game_item` (external_uuid). Unresolved lines persist the Wiki snapshot and
  log `UNRESOLVED_INGREDIENT`.
- `ScWikiItemSyncService` closure mode (flag `krt.scwiki.item-sync-enabled`) —
  fetches `GET /api/items/{uuid}` for every `game_item.external_uuid` (the UEX
  catalogue) plus blueprint-referenced item uuids, filling Wiki columns
  (classification / mass / dimensions / descriptions) and flipping
  `source_systems` `UEX_ONLY → BOTH`. A 404 logs `WIKI_MISSING`. This is the
  expensive one: ~5000 single fetches at the configured rate (~17 min at
  5 req/s).
- `ScWikiVehicleSyncService` (flag `krt.scwiki.vehicle-sync-enabled`) —
  paginates `/api/vehicles`, fills `scwiki_slug` / `game_name` /
  `description_de` on `ship_type` (and `description_en` / `class_name` /
  `vehicle_inventory_scu` only where UEX left them blank), flips
  `source_systems` `UEX_ONLY → BOTH`. UEX-owned `is_*` flags / dims / fuel are
  never touched.
- The scheduler runs the four syncs (commodity, vehicle, item, blueprint) in
  dependency order, each behind its own flag, each in its own try/catch.

### 4.2 Pre-deployment checks (R4)

- [ ] R3 (PR [#266](https://github.com/krt-iri/basetool/pull/266)) deployed;
  its commodity merge has run at least once so `material.scwiki_uuid` is
  populated (RESOURCE ingredient resolution leans on it).
- [ ] `SELECT version FROM flyway_schema_history ORDER BY version DESC LIMIT 5`
  shows V113 as the latest; no V114.
- [ ] `./gradlew spotlessApply check` green from a clean clone of the R4 branch.
- [ ] All three R4 flags unset / `false` in prod `.env` for the initial deploy.
- [ ] `game_item` is populated (R2 ran) — the closure item fill iterates it.

### 4.3 Deployment steps (production, R4)

1. **Backup** (the syncs mutate `game_item` + `ship_type` + create blueprint
   rows):

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r4-$(date -u +%Y%m%d-%H%M).dump basetool
   ```
2. **Merge the R4 PR.** Watch for V114:

   ```
   Migrating schema "public" to version "114 - create blueprint tables"
   ```
3. **Confirm the dark state** — the next scheduler tick logs three skips:

   ```
   SC Wiki vehicle sync invoked but disabled … skipping.
   SC Wiki item sync invoked but disabled … skipping.
   SC Wiki blueprint sync invoked but disabled … skipping.
   ```
4. **Enable in order, one at a time, soaking between each:**
   `KRT_SCWIKI_VEHICLE_SYNC_ENABLED=true` → review → then
   `KRT_SCWIKI_ITEM_SYNC_ENABLED=true` (watch runtime — the slow one) → review →
   then `KRT_SCWIKI_BLUEPRINT_SYNC_ENABLED=true` (last, since its ingredient
   resolution benefits from items being filled first). Restart after each
   `.env` change.

### 4.4 Smoke tests (R4, post-deploy)

```bash
# 1. Blueprint tables exist.
psql -d basetool -c "\d blueprint" | grep -E 'scwiki_uuid|output_item_id'

# --- after enabling vehicle sync + one tick: ---
psql -d basetool -c "SELECT count(*) FILTER (WHERE scwiki_synced_at IS NOT NULL) FROM ship_type"
#   expect > 0; description_de / game_name populated on matched rows.

# --- after enabling item sync + one tick (allow ~20 min): ---
psql -d basetool -c "SELECT count(*) FILTER (WHERE scwiki_synced_at IS NOT NULL) AS filled, \
                            count(*) FILTER (WHERE source_systems='BOTH') AS both FROM game_item"
#   expect filled close to the count that exist on the Wiki; WIKI_MISSING
#   events on /admin/sync-reports/scwiki for the ~3% the Wiki lacks.

# --- after enabling blueprint sync + one tick: ---
psql -d basetool -c "SELECT count(*) FROM blueprint"          # ~1559
psql -d basetool -c "SELECT count(*) FROM blueprint_ingredient WHERE material_id IS NULL AND game_item_id IS NULL"
#   the unresolved-line count — should shrink on subsequent runs as items fill;
#   each is visible as an UNRESOLVED_INGREDIENT event for admin follow-up.

# 5. Trading / refinery UI unaffected (Wiki fill only touches Wiki columns +
#    invisible WIKI_ONLY rows).
```

### 4.5 Rollback (R4)

Fastest: set all three flags to `false`. For a full revert:

1. Flags off, revert the merge on `main`.
2. Drop the blueprint graph and clear Wiki fill (optional):

   ```sql
   DROP TABLE IF EXISTS blueprint_dismantle_return;
   DROP TABLE IF EXISTS blueprint_ingredient;
   DROP TABLE IF EXISTS blueprint;
   DELETE FROM flyway_schema_history WHERE version = '114';
   -- game_item / ship_type Wiki columns added in R2 remain; clear if desired:
   UPDATE game_item SET scwiki_synced_at = NULL, classification = NULL, mass = NULL,
          source_systems = 'UEX_ONLY' WHERE source_systems = 'BOTH';
   DELETE FROM game_item WHERE source_systems = 'WIKI_ONLY';
   ```

   Restart; `ddl-auto: validate` must pass against the R3 schema (blueprint
   entities removed with the revert). The backup is the fallback.

### 4.6 Monitoring during the soak window (R4)

- `/admin/sync-reports/scwiki`: `UNRESOLVED_INGREDIENT` and `WIKI_MISSING` are
  the action items. Unresolved ingredients shrink as the item fill + admin
  aliases land; persistent ones flag a genuine catalogue gap.
- Watch the item-sync runtime — at 5 req/s, ~5000 items ≈ 17 min/cycle. If it
  overruns the scheduler delay, lower `krt.scwiki.scheduler-delay` headroom or
  raise `krt.scwiki.requests-per-second` cautiously.
- Confirm `blueprint_ingredient` CHECK constraints never reject a write (no
  constraint-violation errors in `backend.json`).

---

## 5. R5 - Full Wiki item backfill

### 5.1 Scope summary

R5 adds **Mode B (full backfill)** to `ScWikiItemSyncService`. It is **purely
additive sync logic — there is NO Flyway migration** (it writes the same
`game_item` columns R2/R4 already created). The mode is selected when BOTH
`krt.scwiki.item-sync-enabled=true` AND `krt.scwiki.sync-all-items=true`; with
`sync-all-items=false` (the default) the R4 closure mode runs unchanged, so R5
ships dark — merging it generates no new Wiki traffic on its own.

- Mode B pages the seven per-kind Wiki list endpoints, deriving `GameItemKind`
  from the source endpoint (plan §6.3.1), then a residual `/api/items`
  `GENERIC` catch-all so the whole ~12 700-row pool is covered (the §6.3.1
  "everything else → GENERIC" row; the seven kind endpoints alone cover
  ~8 200). New items are inserted `WIKI_ONLY`; existing rows have their Wiki
  columns filled and `source_systems` flipped `UEX_ONLY → BOTH`. The
  UEX-canonical `name` / capability flags are never overwritten; `manufacturer`
  is resolved for new rows against the existing manufacturer table only (no
  stubs — R6 reconciles) and stays sticky on existing rows.
- **Filter values (plan §13 open question #3, resolved by live probe of
  `GET /api/items/filters` on game 4.8.0).** The probe confirmed `/api/armor`
  is the one endpoint that returns the full ~12 700-row pool with no filter
  (§3.4 quirk #1) — the other six already return their kind. `filter[...]` is
  configurable per endpoint; defaults: `krt.scwiki.armor-filter=FPS.Armor`
  (required; `filter[classification]` prefix-matches, → 2 318),
  `clothes-filter=FPS.Clothing` (1 826), `food-filter=FPS.Consumable.Food`
  (221), the rest blank (endpoint-native: weapons 391, weapon-attachments 104,
  vehicle-items 3 211 incl. 947 paints, vehicle-weapons 168). A
  `krt.scwiki.backfill-kind-sanity-cap` (default 9 000) refuses any kind pass
  that still comes back pool-sized (e.g. a cleared `armor-filter`) — that kind
  is skipped and the orphan sweep is suppressed for the run.
- **Orphan handling (§8.4 / §8.7).** One cross-kind seen set; the soft-delete
  sweep (`game_item.scwiki_deleted_at`, Wiki-written rows only) fires **only**
  when every pass — all seven kinds and the residual — returned data. A partial
  failure, a 304, an empty feed or a sanity-cap trip suppresses the sweep so a
  transient outage never wipes Wiki-side state.

### 5.2 Pre-deployment checks (R5)

- [ ] R4 (PR [#267](https://github.com/krt-iri/basetool/pull/267)) deployed and
  its closure item fill (`item-sync-enabled`) has run at least once.
- [ ] `krt.scwiki.sync-all-items` unset / `false` in prod `.env` for the merge.
- [ ] `./gradlew spotlessApply check` green from a clean clone of the R5 branch.
- [ ] Confirm the probed filter values still hold (Wiki schema can drift between
  game patches):

  ```bash
  curl -gs "https://api.star-citizen.wiki/api/armor?filter[classification]=FPS.Armor&page[size]=1" \
  | python3 -c "import sys,json;print('armor filtered total =', json.load(sys.stdin)['meta']['total'])"
  # Expect ~2300, NOT ~12700. A ~12700 here means the filter no longer works —
  # re-probe /api/items/filters and update krt.scwiki.armor-filter before enabling.
  ```
- [ ] Logical backup taken (this is a large data change — see §0.3 R5 note).

### 5.3 Deployment steps (production, R5)

1. **Backup** (Mode B inserts ~7 000 rows and flips ~5 000):

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r5-$(date -u +%Y%m%d-%H%M).dump basetool
   ```
2. **Merge the R5 PR.** There is **no migration** — do NOT expect a Flyway
   `Migrating schema …` line; `flyway_schema_history` stays at V114.
3. **Confirm the dark state** — with `sync-all-items` still `false`, the next
   tick logs the unchanged closure-mode line (`Starting SC Wiki item sync
   (closure mode) …`), not the backfill line.
4. **Enable on ONE environment first** (staging, or a low-traffic window):
   set `KRT_SCWIKI_SYNC_ALL_ITEMS=true` (leave `item-sync-enabled=true`),
   restart, let one tick run, then **measure runtime and DB growth before
   promoting** to the other environments. Look for the start/finish lines:

   ```
   Starting SC Wiki item sync (FULL BACKFILL mode) …
   Finished SC Wiki item sync (full backfill): N created WIKI_ONLY, M linked …
   ```

### 5.4 Smoke tests (R5, post-deploy)

```bash
# --- after enabling sync-all-items + one tick: ---

# 1. game_item grew toward the full pool (~12 700 Wiki-known rows).
psql -d basetool -c "SELECT count(*) FILTER (WHERE scwiki_synced_at IS NOT NULL) AS wiki_filled, \
                            count(*) AS total FROM game_item"
#   expect wiki_filled in the ~12 000-12 700 range.

# 2. source_systems distribution: a big WIKI_ONLY block appeared.
psql -d basetool -c "SELECT source_systems, count(*) FROM game_item GROUP BY source_systems ORDER BY 1"
#   expect BOTH ~5 000 (UEX∩Wiki), WIKI_ONLY ~7 000 (paints/variants/cargo UEX
#   never tracked), UEX_ONLY small (items the Wiki lacks).

# 3. per-kind counts look sane (kind derived from the source endpoint).
psql -d basetool -c "SELECT kind, count(*) FROM game_item WHERE scwiki_synced_at IS NOT NULL \
                     GROUP BY kind ORDER BY kind"
#   expect roughly ARMOR ~2300, CLOTHING ~1800, FOOD ~220, WEAPON ~390,
#   WEAPON_ATTACHMENT ~100, VEHICLE_ITEM ~3200, VEHICLE_WEAPON ~170,
#   GENERIC the residual ~4400. ARMOR near 12 700 means the filter broke
#   (see §5.2) — roll the flag back.

# 4. CRITICAL — Wiki-only rows did NOT leak into trading/refinery UI.
#    game_item is a static catalogue (not OrgUnit-scoped); confirm no trading
#    surface began listing the new rows. Spot-check a Lager / refinery page.

# 5. sync-report recorded the backfill deltas (ADMIN session).
psql -d basetool -c "SELECT event_type, count(*) FROM external_sync_report \
                     WHERE source='SCWIKI' GROUP BY event_type ORDER BY 2 DESC"
#   expect a large CREATED_WIKI_ONLY count (one per new row, first run only) and
#   some SKIP_JUNK (placeholder/markup/NOITEM_Vehicle names dropped).
```

### 5.5 Rollback (R5)

Fastest: set `KRT_SCWIKI_SYNC_ALL_ITEMS=false` and restart — the item sync
reverts to R4 closure mode immediately; no data is removed (the backfilled
`WIKI_ONLY` rows are invisible to trading anyway, being a static catalogue).
To also remove the backfilled data:

```sql
-- Drop the Wiki-only rows Mode B created …
DELETE FROM game_item WHERE source_systems = 'WIKI_ONLY';
-- … and revert the BOTH flips back to UEX_ONLY (closure mode will re-fill the
-- closure subset on its next run):
UPDATE game_item SET source_systems = 'UEX_ONLY', scwiki_synced_at = NULL,
       scwiki_deleted_at = NULL
 WHERE source_systems = 'BOTH';
```

No migration to revert. The backup is the fallback.

### 5.6 Monitoring during the soak window (R5)

- **Runtime.** Mode B is page-based (page-size 200), so it is fast despite the
  pool size — ~106 pages across the eight passes at 5 req/s. If a run overruns
  the scheduler delay, raise `krt.scwiki.requests-per-second` cautiously or
  lower the page count via filters.
- **DB growth.** `game_item` grows by ~7 000 rows on the first backfill, then
  stays flat (steady-state runs create ~0). `external_sync_report` spikes by
  ~#new-rows on the first run (one `CREATED_WIKI_ONLY` each); `pruneRuns` trims
  it to the last few runs over subsequent cycles.
- **Orphan sweep.** A `Skipping cross-kind orphan sweep …` WARN line means at
  least one pass returned empty/304/capped — expected on unchanged feeds, but a
  persistent skip plus a missing kind points at a broken filter or endpoint.
- **Sanity cap.** An `exceeding the sanity cap` ERROR line is the §3.4 quirk
  firing — re-probe the filter (§5.2) and correct the offending `*-filter`.

---

## 6. R6 - Manufacturer Wiki reconciliation

### 6.1 Scope summary

R6 adds `ScWikiManufacturerSyncService` — an **enrichment-only** pass that stamps the Wiki
cross-reference columns onto the manufacturer rows the UEX sync already created. Like every prior
phase it ships **dark** behind `krt.scwiki.manufacturer-sync-enabled` (default `false`), and like R5
it carries **no Flyway migration** (the `scwiki_uuid` / `scwiki_code` columns landed in R1's V107;
R6 finally writes them).

- Walks `/api/manufacturers` (~130 rows, one page at the 200 page-size) and resolves each Wiki
  manufacturer to a local row via the §6.4 chain: `scwiki_uuid` → case-insensitive `name` →
  case-insensitive `abbreviation == code`. The abbreviation/code fallback is an addition over the
  plan's literal `industry+name` third step (the Wiki manufacturer payload exposes no industry; the
  UNIQUE local `abbreviation` matched against the Wiki `code` lifts the link rate for companies
  whose full name differs between catalogues).
- On a match it stamps `scwiki_uuid` / `scwiki_code` / `scwiki_synced_at` (and clears
  `scwiki_deleted_at`). It **never inserts a row** (a Wiki manufacturer with no UEX counterpart is
  skipped — `manufacturer.name` / `abbreviation` are `NOT NULL UNIQUE` and there is no `WIKI_ONLY`
  concept here) and **never overwrites** the UEX-canonical `name` / `abbreviation` / `industry`.
- A candidate already linked to a *different* Wiki UUID is left untouched and logged
  `MANUFACTURER_MISMATCH` rather than hijacked. A first-time link logs `MANUFACTURER_LINKED`; a
  refresh of an already-linked row logs nothing (so steady-state runs are quiet).
- Gated cross-kind orphan sweep (`Manufacturer.markScwikiDeletedExcept`, Wiki-linked rows only)
  fires only on a non-empty seen set (§8.7). Expected on ~50 local manufacturers: ~50 linked, the
  remaining ~80 Wiki-only companies unmatched (no UEX row to enrich).
- The scheduler now runs the manufacturer reconciliation as a fifth step after the blueprint sync,
  behind its own flag.

### 6.2 Pre-deployment checks (R6)

- [ ] R5 (PR [#268](https://github.com/krt-iri/basetool/pull/268)) deployed.
- [ ] The UEX manufacturer sync has run at least once so `manufacturer` rows carry
  `uex_company_id` (R6 only enriches existing rows):

  ```bash
  psql -d basetool -c "SELECT count(*) FROM manufacturer WHERE uex_company_id IS NOT NULL"
  # Expect the UEX company count (~50); 0 means nothing to reconcile yet.
  ```
- [ ] `krt.scwiki.manufacturer-sync-enabled` unset / `false` in prod `.env` for the merge.
- [ ] `./gradlew spotlessApply check` green from a clean clone of the R6 branch.

### 6.3 Deployment steps (production, R6)

1. **Backup** (the sync mutates a few `manufacturer` columns):

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r6-$(date -u +%Y%m%d-%H%M).dump basetool
   ```
2. **Merge the R6 PR.** There is **no migration** — `flyway_schema_history` stays at V114.
3. **Confirm the dark state** — the next tick logs the skip line:

   ```
   SC Wiki manufacturer sync invoked but disabled … skipping.
   ```
4. **Enable** with `KRT_SCWIKI_MANUFACTURER_SYNC_ENABLED=true`, restart, let one tick run, and watch
   the summary line:

   ```
   Finished SC Wiki manufacturer reconciliation: N newly linked, M refreshed, C conflicts, U unmatched.
   ```

### 6.4 Smoke tests (R6, post-deploy)

```bash
# --- after enabling manufacturer-sync + one tick: ---

# 1. Wiki cross-refs are now populated on the UEX rows.
psql -d basetool -c "SELECT count(*) FILTER (WHERE scwiki_uuid IS NOT NULL) AS linked, \
                            count(*) FILTER (WHERE uex_company_id IS NOT NULL) AS uex FROM manufacturer"
#   expect linked close to (but <=) uex; the gap is UEX companies the Wiki spells differently.

# 2. UEX-canonical fields were NOT touched (spot-check a known manufacturer).
psql -d basetool -c "SELECT name, abbreviation, scwiki_code FROM manufacturer WHERE abbreviation = 'AEGS'"
#   name/abbreviation unchanged; scwiki_code now set.

# 3. sync-report recorded the reconciliation (ADMIN session, /admin/sync-reports/scwiki).
psql -d basetool -c "SELECT event_type, count(*) FROM external_sync_report \
                     WHERE source='SCWIKI' AND aggregate='manufacturer' GROUP BY event_type"
#   expect MANUFACTURER_LINKED on the first run; MANUFACTURER_MISMATCH only on genuine conflicts.

# 4. Ship picker / item manufacturer labels unaffected (R6 only adds cross-ref columns).
```

### 6.5 Rollback (R6)

Fastest: set `KRT_SCWIKI_MANUFACTURER_SYNC_ENABLED=false` and restart — no data is removed (the
`scwiki_*` columns are additive cross-refs that nothing reads yet). To also clear the stamps:

```sql
UPDATE manufacturer
   SET scwiki_uuid = NULL, scwiki_code = NULL, scwiki_synced_at = NULL, scwiki_deleted_at = NULL
 WHERE scwiki_synced_at IS NOT NULL;
```

No migration to revert. The backup is the fallback.

### 6.6 Monitoring during the soak window (R6)

- **Link rate.** The `… N newly linked, M refreshed, C conflicts, U unmatched.` line is the health
  signal. A high `unmatched` is normal (the Wiki lists more companies than UEX); a high `conflicts`
  is not — it points at a name/code collision and warrants a look at the `MANUFACTURER_MISMATCH`
  events.
- **DB growth.** Negligible — no new rows, a handful of column writes; `external_sync_report` gains
  ~one `MANUFACTURER_LINKED` row per first-time link, then nothing on steady-state runs.
- **Orphan sweep.** A `Skipping … (no sweep)` / empty-feed WARN means the Wiki returned nothing —
  transient; the sweep is correctly suppressed.

---

## 7. R7 - UEX item prices

### 7.1 Scope summary

R7 adds the UEX item-price matrix: a new `game_item_price` table (migration **V115**) and a
`UexItemPriceSyncService` that fills it from UEX `/items_prices_all`. Display-only and gated behind
`krt.uex.item-price-sync-enabled` (default `false`), so the table stays inert until an operator opts
in. This is the only R5-R7 phase that **carries a migration**.

- **V-NUMBER DRIFT.** The plan §7 table pencilled `game_item_price` in as V114, but V113 went to
  R3's `external_sync_report` and V114 to R4's blueprint tables, so R7 takes the next free number,
  **V115**. The R8 `is_manual_entry` cleanup is **V116**; the R9 destructive drop, after V117–V124 were
  claimed by other features merged to `main`, finally lands at **V125**.
- `UexItemPriceSyncService` (UEX scheduler, fifth step after the item catalogue) walks
  `/items_prices_all` (~24 000 rows — the largest UEX payload, covered by the 16 MB WebClient
  buffer) and upserts one `game_item_price` per (item, terminal) pair: `id_item → game_item` via
  `uex_item_id`, `id_terminal → terminal` via `id_terminal`. A price for an **item not yet in
  `game_item` is skipped** (the item catalogue owns row creation and runs earlier in the same tick;
  it resolves next cycle); unknown terminals are skipped too.
- Same `clearStalePrices` orphan handling as `material_price`: touched-row ids are collected and
  pairs UEX no longer returns have their price columns nulled — gated on a non-empty touched-set so
  a total-failure run never wipes the matrix. An empty feed short-circuits before the sweep.
- **Live-feed note (probed `/items_prices_all`, game 4.8.0):** the endpoint returns only
  `id_item` / `id_terminal` / `price_buy` / `price_sell` / `date_modified`. The §6.7 columns
  `price_rent`, `status_buy`, `status_sell`, `game_version` are created but stay `NULL` under this
  feed (reserved for a future richer source).

### 7.2 Pre-deployment checks (R7)

- [ ] R6 (PR [#269](https://github.com/krt-iri/basetool/pull/269)) deployed.
- [ ] `game_item` is populated and terminals are synced (the price upsert resolves both):

  ```bash
  psql -d basetool -c "SELECT (SELECT count(*) FROM game_item WHERE uex_item_id IS NOT NULL) AS items, \
  (SELECT count(*) FROM terminal) AS terminals"
  # Expect items in the thousands and terminals > 0; 0 items means every price row would be skipped.
  ```
- [ ] `krt.uex.item-price-sync-enabled` unset / `false` in prod `.env` for the merge.
- [ ] `./gradlew spotlessApply check` green from a clean clone of the R7 branch.
- [ ] Logical backup taken (a new table + a large data load).

### 7.3 Deployment steps (production, R7)

1. **Backup**:

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r7-$(date -u +%Y%m%d-%H%M).dump basetool
   ```
2. **Merge the R7 PR.** Watch for V115:

   ```
   Migrating schema "public" to version "115 - create game item price"
   ```
3. **Confirm the dark state** — the next UEX tick logs the skip line:

   ```
   UEX item-price sync invoked but disabled … skipping.
   ```
4. **Enable** with `KRT_UEX_ITEM_PRICE_SYNC_ENABLED=true`, restart, and watch the first run — it is
   the heaviest UEX feed, so confirm it completes inside the hourly cadence:

   ```
   Starting synchronization of UEX item prices...
   Finished UEX item-price sync: N processed, M skipped (unknown item / terminal).
   ```

### 7.4 Smoke tests (R7, post-deploy)

```bash
# 1. Table exists (V115).
psql -d basetool -c "\d game_item_price" | grep -E 'game_item_id|terminal_id|price_buy'

# --- after enabling item-price-sync + one tick: ---

# 2. Rows landed (one per item×terminal pair UEX returned; up to ~24 000).
psql -d basetool -c "SELECT count(*) AS rows, count(DISTINCT game_item_id) AS items, \
                            count(DISTINCT terminal_id) AS terminals FROM game_item_price"
#   rows in the tens of thousands; a count of 0 with a non-empty feed means every item was skipped
#   (game_item not populated — re-check the item catalogue sync).

# 3. A known item has prices (spot-check, e.g. Omnisky III Cannon).
psql -d basetool -c "SELECT t.name, p.price_buy, p.price_sell FROM game_item_price p \
                     JOIN game_item g ON g.id = p.game_item_id JOIN terminal t ON t.id = p.terminal_id \
                     WHERE g.uex_item_id = 1 LIMIT 5"

# 4. Trading / refinery / hangar UI unaffected — game_item_price is display-only and nothing reads
#    it yet, so no surface should change.
```

### 7.5 Rollback (R7)

Fastest: set `KRT_UEX_ITEM_PRICE_SYNC_ENABLED=false` and restart — the sync stops; the table simply
stops being updated (nothing reads it). To clear the data without dropping the table:

```sql
TRUNCATE game_item_price;
```

For a full revert (drop the table), flags off, revert the merge on `main`, then:

```sql
DROP TABLE IF EXISTS game_item_price;
DELETE FROM flyway_schema_history WHERE version = '115';
```

Restart; `ddl-auto: validate` must pass against the R6 schema (the `GameItemPrice` entity is removed
with the revert). The backup is the fallback.

### 7.6 Monitoring during the soak window (R7)

- **Runtime.** `/items_prices_all` is the largest UEX feed (~24 000 rows). Confirm the
  `Finished UEX item-price sync …` line lands well inside the 1 h scheduler delay; if it crowds the
  window, this sync is the first candidate to move to a slower cadence.
- **Buffer headroom.** The payload is >1 MB; the shared 16 MB WebClient buffer covers it.
  A decode error in the logs (truncation) means the catalogue outgrew the buffer — raise
  `maxInMemorySize` in `UexClient`.
- **Skipped count.** A high `skipped` count = many prices reference items not yet in `game_item`.
  Expected right after enabling (before the item catalogue has fully populated); it should fall to a
  small residual once the item sync has caught up.
- **DB growth.** `game_item_price` grows to ~the feed size on the first full run, then stays flat
  (steady-state upserts in place; `clearStalePrices` nulls dropped pairs without inserting).

---

## 8. R8 - Soak + V116 cleanup

### 8.1 Scope summary

R8 has two parts: an **operational soak** (turn every sync on, watch it for ~two weeks) and one
small **data migration** that prepares the destructive R9 cleanup.

- **Migration V116** (`update_material_source_systems_for_is_manual_entry.sql`) — a one-shot,
  idempotent backfill: `UPDATE material SET source_systems = 'MANUAL' WHERE is_manual_entry = TRUE
  AND source_systems <> 'MANUAL'`. Admin-created commodities were marked by `is_manual_entry = TRUE`
  while `source_systems` kept the V106 default `'UEX_ONLY'`; R9 drops `is_manual_entry`, so the
  MANUAL provenance moves into the canonical `MaterialSourceSystem.MANUAL` enum value (which already
  exists) first. **No schema change**, no entity change beyond a doc fix.
- **V-NUMBER DRIFT.** Plan §7 pencilled this as V115, but R7 took V115 for `game_item_price`, so the
  backfill is **V116** and the R9 destructive drop (`material.is_manual_entry` +
  `ship_type.description`) shifts to **V125** (V117–V124 went to features merged to `main` while the
  R9 PR was open).
- **Soak (operational, no code).** With R1-R7 deployed, turn every sync flag on and run the full
  cadence (UEX and Wiki both 24 h) for ~two weeks, watching the signals in §8.6 before committing to
  the R9 destructive drop. The flags stay operator-controlled — R8 ships **no** default-on change.

### 8.2 Pre-deployment checks (R8)

- [ ] R7 (PR [#270](https://github.com/krt-iri/basetool/pull/270)) deployed.
- [ ] `./gradlew spotlessApply check` green from a clean clone of the R8 branch.
- [ ] Logical backup taken (V116 overwrites `source_systems` on manual rows and has no clean
  automatic inverse).
- [ ] Note the pre-migration manual-row count for the smoke check:

  ```bash
  psql -d basetool -c "SELECT count(*) FROM material WHERE is_manual_entry = TRUE"
  ```

### 8.3 Deployment steps (production, R8)

1. **Backup**:

   ```bash
   pg_dump --host=<prod-host> --username=<prod-user> --format=custom \
     --no-owner --no-acl \
     --file=basetool-pre-r8-$(date -u +%Y%m%d-%H%M).dump basetool
   ```
2. **Merge the R8 PR.** Watch for V116 (a fast data UPDATE, no DDL):

   ```
   Migrating schema "public" to version "116 - update material source systems for is manual entry"
   ```
3. **Begin the soak.** Ensure every sync flag is on for the soak window and confirm the cadence:
   `krt.scwiki.scheduler-enabled`, `commodity-sync-enabled`, `vehicle-sync-enabled`,
   `item-sync-enabled` (+ `sync-all-items` if running the full backfill), `blueprint-sync-enabled`,
   `manufacturer-sync-enabled`, and `krt.uex.item-price-sync-enabled`. Restart after `.env` changes.

### 8.4 Smoke tests (R8, post-deploy)

```bash
# 1. The backfill ran and the invariant holds (no manual row kept a non-MANUAL provenance).
psql -d basetool -c "SELECT count(*) FROM material WHERE is_manual_entry = TRUE AND source_systems <> 'MANUAL'"
#   Expect 0.

# 2. MANUAL now appears in the provenance distribution; the count matches the pre-migration
#    manual-row count noted in §8.2 (minus any UEX has since adopted).
psql -d basetool -c "SELECT source_systems, count(*) FROM material GROUP BY source_systems ORDER BY 1"

# 3. The admin "manual" badge still resolves (it now reads source_systems = 'MANUAL' as well as the
#    legacy is_manual_entry flag) — spot-check a known manual commodity in the materials admin UI.
```

### 8.5 Rollback (R8)

V116 is a data migration with **no clean automatic inverse** — the pre-flip per-row value (the
incorrect `'UEX_ONLY'` default) is not preserved. `is_manual_entry` is left untouched (R9 drops it),
so it remains the source of truth for a manual reclassification:

```sql
-- Best-effort manual revert (restores the pre-R8 — arguably wrong — default):
UPDATE material SET source_systems = 'UEX_ONLY'
 WHERE is_manual_entry = TRUE AND source_systems = 'MANUAL';
DELETE FROM flyway_schema_history WHERE version = '116';
```

Prefer restoring the pre-R8 backup. The soak itself rolls back by turning the sync flags off.

### 8.6 Monitoring during the soak window (R8)

The point of R8 is to prove the whole sync is healthy before the irreversible R9 drop. Over the
~two-week window watch:

- **DB growth.** `game_item`, `game_item_price`, `blueprint` / `blueprint_ingredient`, `material`
  should plateau after the first full cycle of each sync — continued growth means an upsert key is
  missing and rows are duplicating.
- **Scheduler runtimes.** Every `Finished … sync` line must land inside its cadence (UEX and Wiki
  both 24 h). The item-price sync (~24 k rows) and the full Wiki backfill (~12 700 rows) are the two
  to watch.
- **Sync-report event mix** (`/admin/sync-reports`). `UNRESOLVED_INGREDIENT`, `WIKI_MISSING`,
  `MANUFACTURER_MISMATCH` and `MULTI_MATCH_AMBIGUOUS` are the action items; a steady or shrinking
  count is healthy, a growing one points at a catalogue gap.
- **Log volume.** Establish a baseline for the new services so a future regression (e.g. an error
  loop) is visible against it.
- **Exit criterion.** After ~two weeks with the signals above clean, proceed to **R9** (the V125
  destructive drop of `material.is_manual_entry` + `ship_type.description`), tracked in its own
  roadmap doc.

---

## 9. R9 - V125 destructive cleanup

### 9.1 Scope summary

R9 was the only **irreversible** phase: it dropped `material.is_manual_entry` and the synthesized
`ship_type.description` column. It shipped on **2026-06-01** as
`V125__drop_legacy_material_and_ship_type_columns.sql`. It was tracked as a separate, staged
roadmap — [`SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md`](SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md) — which is
the authoritative source; this section is the deploy-time summary.

- **Both columns were still UI-consumed when R9 was planned** (§13 #9): `is_manual_entry` backed the
  admin materials "manual" badge; `ship_type.description` was rendered by `ship-data.html` via
  `ShipTypeDto`. So R9 was staged: the readers were re-sourced onto the replacements
  (`source_systems = 'MANUAL'` and `description_de` / `description_en`) first, soaked, and only then
  were the columns dropped. Note what the drop did **not** remove: the DTO wire fields survive it —
  `MaterialDto.isManualEntry` is now derived in `MaterialMapper` from `source_systems == MANUAL`, and
  `ShipTypeDto.description` in `ShipMapper` from `description_de` falling back to `description_en`.
  The *columns* are gone; the *wire fields* are not.
- **V-NUMBER:** V115 went to R7 (`game_item_price`), V116 to R8 (`is_manual_entry` backfill); V117–V124
  were then claimed by features merged to `main` while this PR was open (job-order comment, min-quality
  nullable, mission party-lead, blueprint requirement-groups/modifier-segments, FK-index round2, item
  job-orders, mission-participant org-units), so the destructive drop landed at **V125**.

### 9.2 Pre-deployment checks (R9)

- [x] R8 (PR [#271](https://github.com/krt-iri/basetool/pull/271)) merged **2026-05-29**. The §8.6
  exit criterion asked for a ~two-week soak; the drop followed on **2026-06-01**, so the soak that
  actually happened was ~2.5 days. The box records that R8 shipped first, not that the two-week
  criterion was met as written.
- [x] Roadmap Steps 1–2 (reader migrations) shipped **2026-05-29** in PR
  [#275](https://github.com/krt-iri/basetool/pull/275) — every reader now reads
  `source_systems` / `description_de` / `description_en`. They were in production ~2.5 days before
  the drop, not the "~one release window" V125's own header comment names. A bare `git grep -i
  "isManualEntry\|ship_type.*description"` is **not** empty and never will be: it still hits the
  surviving DTO wire fields `MaterialDto.isManualEntry` and `ShipTypeDto.description`, which are
  derived from the new columns rather than mapped to the dropped ones.
- [x] `./gradlew spotlessApply check` green from a clean clone of the R9 (V125) branch.
- [x] Full DB backup taken immediately before merge — the drop is irreversible.

### 9.3 Deployment steps (production, R9)

This followed `SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md` Step 4. In brief, as executed on 2026-06-01:

1. **Backup** taken (`pg_dump … basetool-pre-r9-…dump`).
2. **The R9 (V125) PR was merged** — it removed the `Material.isManualEntry` and
   `ShipType.description` JPA fields and ran `ALTER TABLE … DROP COLUMN`. The deploy log carried:

   ```
   Migrating schema "public" to version "125 - drop legacy material and ship type columns"
   ```
3. **`ddl-auto=validate` booted green** (the entity fields were removed in the same PR).

### 9.4 Smoke tests (R9, post-deploy)

```bash
# 1. Columns are gone.
psql -d basetool -c "SELECT column_name FROM information_schema.columns \
  WHERE (table_name='material' AND column_name='is_manual_entry') \
     OR (table_name='ship_type' AND column_name='description')"
#   Expected zero rows.

# 2. The admin materials "manual" badge still resolved (MaterialDto.isManualEntry is now derived
#    from source_systems = 'MANUAL').
# 3. ship-data still rendered a description (ShipTypeDto.description is now derived from
#    description_de, falling back to description_en).
```

### 9.5 Rollback (R9)

**Irreversible at the DB layer** — restore the pre-R9 backup. There is no `ADD COLUMN` that brings
back the dropped per-row data. (Reverting the merge restores the Java fields, but they would then map
columns that no longer exist; a code-only revert is not sufficient.)

---

## Appendix A - Common operations

### A.1 Manually re-run an SC Wiki sync

Once R3+ ships, the Wiki sync can be triggered out-of-band by flipping the
property at runtime:

```bash
# Tell the backend to re-read its properties (or restart the container).
docker compose restart backend
# Or expose an admin endpoint in R3+: POST /api/v1/admin/sync/scwiki/trigger
```

In R1 the scheduler bean exists but is gated by
`krt.scwiki.scheduler-enabled = false`; flipping it via env var
`KRT_SCWIKI_SCHEDULER_ENABLED=true` makes the tick fire (the body logs a
warning that no sync services are wired in yet - that is expected for R1).

### A.2 Read the sync report (R3+)

R3 ships `/admin/sync-reports/scwiki` and `/admin/sync-reports/uex`. R1 has
no sync report yet - the admin reviews the alias table directly at
`/admin/material-aliases`.

### A.3 Add a `material_external_alias` row by hand

Two paths:

1. **Admin UI** (preferred):
   - Open `/admin/material-aliases` as an `ADMIN` user.
   - Use the "Neuen Alias anlegen" form. Pick the local material, set the
     source (UEX / SCWIKI), enter the external name, optionally provide
     external key / UUID / code and a note explaining the verification
     provenance.
   - The submit creates the row, refreshes the list view.
2. **Direct SQL** (incident-only):

   ```sql
   INSERT INTO material_external_alias
     (id, material_id, source_system, external_name, note, created_by)
   VALUES (gen_random_uuid(),
           (SELECT id FROM material WHERE name = 'Construction Material Pebbles'),
           'SCWIKI',
           'Construction Pieces',
           'Manual alias after in-game grade verification on YYYY-MM-DD',
           '<your-jwt-sub>');
   ```

### A.4 Flip `material.is_visible` on a Wiki-only row (R3+)

```sql
UPDATE material SET is_visible = TRUE
 WHERE source_systems = 'WIKI_ONLY'
   AND name = '<verified-name>';
```

R3+ exposes the same toggle in the material edit page; the SQL form is for
batch flips during the post-Wiki-merge review.

### A.5 Force the V108 seed to apply on a DB where it was a no-op

If R1 deployed against a fresh DB where the UEX commodity sync had not run
yet, the V108 seed inserted zero alias rows (the
`INSERT ... SELECT ... WHERE m.name = ...` clause found no target). After
the next UEX hourly sync populates the six target materials
(`Silicon (Raw)`, `Stileron (Raw)`, `Ouratite (Raw)`, `Hephaestanite (Raw)`,
`Lastaphrene`, `Lunes`), re-apply the seed manually:

```sql
\i backend/src/main/resources/db/migration/V108__create_material_external_alias.sql
```

The `INSERT ... ON CONFLICT DO NOTHING` clauses keep the re-run idempotent
against rows the admin already added by hand.

---

## Appendix B - Failure modes catalog

### B.1 SC Wiki API returns 5xx for an entire sync run

**Symptom.** `ScWikiScheduler` log lines show
`Failed to fetch <resource> from SC Wiki API (...)` for every endpoint in
the cycle; the merged data list is empty.

**Behaviour.** Sync services consume the empty list as "skip this run" and
do NOT mark local rows as deleted (the orphan-handling sweep is gated on a
non-empty seen-id set, mirroring the UEX `clearStalePrices` pattern).

**Recovery.** Nothing to do. The next scheduled tick (default 24 h) retries.
If the outage extends beyond a release cycle, set the `Wiki API outage`
banner via `/admin/announcement` so users know catalog data is stale.

### B.2 UEX `/items` schema drift introduces a new field

R1 does not yet read `/items`. R2 ships
`@JsonIgnoreProperties(ignoreUnknown = true)` on every UEX item DTO so an
upstream field add is transparent. A field rename / removal breaks the
mapping - the sync logs a Jackson-level deserialisation failure and skips
the affected rows.

### B.3 SC Wiki API rate-limits the sync (HTTP 429)

R1 paces at `krt.scwiki.requests-per-second = 5` (default). If 429s start
firing in R3+, drop the rate to 2 - 3 via an env override:

```bash
KRT_SCWIKI_REQUESTS_PER_SECOND=2 docker compose up -d backend
```

The setting is hot-reloadable only via a container restart in R1; R3+ may
ship a runtime hot-reload via `@RefreshScope`.

### B.4 Migration test fails on V108 seed integrity

If `V108MigrationTest.v108SeedInsertsCreateSixAliasRowsWhenTargetMaterialsExist`
fails with a different row count, the V108 SQL has been edited in a way that
either added a seed row (and the `SEED_PAIRS` constant in the test was not
updated) or dropped one. Update both in the same commit and re-run the test.

### B.5 ArchUnit rule fails on a freshly-renamed class

`sCWikiIntegrationClassesMustWireScWikiClient` fires when a class moves into
`integration.scwiki` without depending on `ScWikiClient`. Two fixes:

- The new class IS a sync service - inject `ScWikiClient` as a field.
- The new class is unrelated (utility / DTO holder) - move it out of the
  `integration.scwiki` package (most likely under `service.scwiki` or
  `dto.scwiki`).

---

## Appendix C - Pre-cutover checklist

Print this section before the prod merge. The on-call engineer signs each
item before approving the deploy.

- [ ] Migration test green on a copy of the prod schema
  (`./gradlew :backend:test --tests "V*MigrationTest"`).
- [ ] `./gradlew check` green from a clean clone.
- [ ] Schema-validate mode confirms `ddl-auto: validate` boots without
  drift (run `./gradlew :backend:bootRun` against the staged DB and
  `Ctrl-C` after the "Started BackendApplication" log line).
- [ ] Sync-report admin pages open without 500 (R3+ only; R1 has none).
- [ ] Feature flags set as expected for this phase
  (`krt.scwiki.scheduler-enabled = false` in R1; `true` from R3 on).
- [ ] Backup taken with `pg_dump --format=custom` and verified by listing
  its contents (`pg_restore --list`).
- [ ] CHANGELOG.md entry merged with the same PR.
- [ ] All translation keys present in DE and EN. For R1: spot-check that
  `grep '^admin.materialAlias.' frontend/src/main/resources/messages_en.properties | wc -l`
  and
  `grep '^admin.materialAlias.' frontend/src/main/resources/messages_de.properties | wc -l`
  match.
- [ ] On-call rotation knows the rollback playbook for this phase
  (link the §X.5 subsection in the Slack / e-mail notification).

