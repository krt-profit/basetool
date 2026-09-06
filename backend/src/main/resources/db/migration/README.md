# Flyway Migration Conventions

This directory holds every schema change the backend has ever shipped. Flyway
applies the files in lexical order by version on every Spring Boot start in the
`dev` and `prod` profiles. Hibernate's `ddl-auto` is **`validate`** in those
profiles, so a schema that doesn't match the JPA entities fails the app
boot — there is no "auto-fix" path in production.

Read this file before you add a migration. It encodes promises we made to
ourselves after each production incident; the conventions below exist because
something broke once.

## Migration timeline — feature releases

- **V80–V93** — Multi-Squadron-Umbau (see [`MULTI_SQUADRON_PLAN.md`](../../../../../../../../MULTI_SQUADRON_PLAN.md)).
  Introduced `owning_squadron_id` on every staffel-scoped aggregate; tightened
  the NOT NULL constraint in V89; dropped the legacy `job_order.squadron`
  VARCHAR in V90.
- **V94–V96** — Main-line additions unrelated to the Spezialkommando work:
  - **V94** — `add_is_manual_entry_to_material` (admin-created manual materials). The
    column no longer exists: V116 moved the provenance into `material.source_systems`
    (`MaterialSourceSystem.MANUAL`) and V125 dropped `is_manual_entry` on 2026-06-01.
    Provenance is read from `source_systems` now.
  - **V95** — `backfill_material_quantity_type`.
  - **V96** — `add_mission_participant_user_unique_index` (DB-backstop against
    duplicate Einsatz-Anmeldungen).
- **V97–V105** — Spezialkommando-Erweiterung (see `SPEZIALKOMMANDO_PLAN.md`).
  Introduces the `org_unit` parent table with a `kind` discriminator so SKs
  can coexist with Staffel as a second tenant kind. The releases land in
  stages:
  - **V97** — create `org_unit` with `kind IN ('SQUADRON','SPECIAL_COMMAND')`,
    copy every existing `squadron` row in as `kind='SQUADRON'`, add the
    promotion-only-for-Squadron CHECK.
  - **V98** — create `org_unit_membership` (composite PK, denormalised `kind`
    column kept in sync by a trigger, partial unique index `one Staffel per
    user`, `is_lead` CHECK that pins the Lead role to SK rows), backfill one
    Staffel membership per `app_user.squadron_id`.
  - **V99** — add nullable `owning_org_unit_id` columns + FKs + indexes on
    every staffel-scoped aggregate (`mission`, `operation`, `ship`,
    `inventory_item`, `refinery_order`) and `job_order` (`creating_org_unit_id`,
    `requesting_org_unit_id`); copy from the legacy columns. `promotion_topic`
    is intentionally **not** touched — promotion data may only be owned by
    Squadron rows per `SPEZIALKOMMANDO_PLAN.md` §3.3.
  - **V100** — one-way sync trigger that mirrors INSERT/UPDATE/DELETE on
    `org_unit` (for `kind='SQUADRON'` rows) into the legacy `squadron` table.
    Lets the application write through `org_unit` exclusively while the
    legacy FK constraints (still pointing at `squadron(id)`) keep resolving.
  - **V101** — promotion-topic SK-reject trigger (`guard_promotion_topic_owner_kind`)
    that refuses any INSERT/UPDATE of `promotion_topic.owning_squadron_id`
    pointing at a non-SQUADRON org_unit. DB-level defence-in-depth on top of
    the Java-typed Squadron field and ArchUnit rule §8.2.
- **V102–V105 (destructive cleanup, ships after dual-write soaks)** — final
  step of the SK rollout, irreversible without DB backup:
  - **V102** — tighten `owning_org_unit_id` to NOT NULL on every aggregate,
    drop NOT NULL on the legacy `owning_squadron_id` columns to unlock SK
    ownership (R8).
  - **V103** — drop the legacy `owning_squadron_id` / `creating_squadron_id` /
    `requesting_squadron_id` columns on every staffel-scoped aggregate +
    JobOrder (R9 step 2-3).
  - **V104** — drop `app_user.squadron_id` / `is_logistician` /
    `is_mission_manager` (the per-membership row is authoritative since R6.d
    + R6.e) (R9 step 4-5).
  - **V105** — retarget the three remaining FKs that still reference
    `squadron(id)` (`promotion_topic`, `mission_participant`,
    `job_order_handover`) to `org_unit(id)`, drop the V100 sync trigger and
    drop the legacy `squadron` table (R9 step 6).
- **V106–V116** — SC Wiki + UEX-Items sync (see `SC_WIKI_SYNC_PLAN.md`). Adds the
  Star Citizen Wiki API as a second catalogue source alongside UEX, joined on the
  shared in-game asset UUID. Additive throughout, one release phase at a time:
  - **V106–V109** (R1, foundation) — Wiki/UEX cross-ref columns plus
    `source_systems` and `is_visible` on `material`; cross-ref columns on
    `manufacturer`; the curated `material_external_alias` table (6 seeded rows);
    the `uex_category` reference table.
  - **V110–V112** (R2, UEX items) — `game_item`, the joint UEX + Wiki item entity
    keyed on `external_uuid`; the rich UEX/Wiki field set on `ship_type`; and the
    `created_at` / `updated_at` columns V109 had omitted. V112 exists as its own
    file precisely because V109 had already shipped — see hard rule 1.
  - **V113** (R3) — `external_sync_report`, the append-only per-run audit log both
    syncs write their findings to.
  - **V114** (R4) — the blueprint recipe graph: `blueprint`, `blueprint_ingredient`,
    `blueprint_dismantle_return`.
  - **V115** (R7) — `game_item_price`, the per-(item, terminal) UEX price matrix.
  - **V116** (R8) — data-only backfill, no schema change: `source_systems = 'MANUAL'`
    for every row that carried `is_manual_entry = TRUE`, so the provenance outlives
    the column drop in V125.
- **V117–V124** — main-line additions interleaved with the sync work:
  - **V117** — `add_comment_to_job_order` (optional free-text comment at creation).
  - **V118** — `make_job_order_material_min_quality_nullable`; NULL means "no
    quality floor".
  - **V119** — `add_mission_party_lead`, with its own `party_lead_version`
    section-scoped counter in the V77 family.
  - **V120–V121** — blueprint requirement groups and their stat modifiers, plus the
    value-segment table describing the non-linear curve a modifier interpolates over.
  - **V122** — `add_missing_fk_indexes_round2`, the second backfill of indexes on FK /
    filter columns after the V34 blanket sweep and the V92 first backfill.
  - **V123** — item job orders: the `job_order.type` discriminator (backfilled to
    `'MATERIAL'`, CHECK-constrained to `MATERIAL`/`ITEM`) plus the item, requirement
    and item-handover child tables.
  - **V124** — `mission_participant_org_unit`, replacing the single
    `mission_participant.squadron_id` FK with a join table over `org_unit`. Phase 1
    of a two-phase drop: the entity stops writing the column, the column stays.
- **V125 (destructive)** — SC Wiki sync R9 step 4 (see
  `SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md`), shipped 2026-06-01. Drops
  `material.is_manual_entry` and `ship_type.description`, whose readers had moved to
  `source_systems` and `description_en` / `description_de` one release earlier. It is
  the worked example for the two-phase DROP rule below.

This timeline is curated, not exhaustive, and it stops at V125 — later releases have
shipped many more migrations. `ls | sort -V | tail -1` in this directory is the only
reliable answer to "what is the current tip?".

## Hard rules

1. **One file per change, `V<n>__<snake_case_description>.sql`.** Pick the next
   integer; never reuse a number that's already in `main`. Two developers
   racing to `V73` should rebase, not double-pick. Flyway treats the version
   tuple as an immutable identifier — once `V73__foo.sql` has run anywhere
   (a teammate's laptop, CI, staging) the file content **must not** change.
2. **One logical change per migration.** Splitting a "rename + backfill + add
   constraint" sequence across three migrations is fine; bundling unrelated
   changes ("add column X to table Y, drop column Z from table W") into one
   file is not.
3. **No `ddl-auto=update`. Never.** Schema changes go here, in a Flyway file,
   even if they're "obvious". `application*.yml` is set to `validate`
   specifically so a missing migration breaks the app boot loudly instead of
   silently drifting at runtime.
4. **Up-only.** We do not maintain undo scripts. A migration that turns out to
   be wrong is corrected by a *new* `V<n+1>__<description>.sql` that reverses
   the bad change. Rolling a database back across multiple environments is
   strictly more expensive than rolling forward, every time.
5. **PostgreSQL syntax only.** This project does not target any other database
   in production — and the tests run on the same engine: the test profile boots
   a Testcontainers PostgreSQL container and applies every Flyway migration to
   it (see the section "Tests" below). Postgres-specific syntax (JSONB, GIN,
   `ON CONFLICT`, `gen_random_uuid()`, …) is therefore fine *and* exercised by
   the test suite.

## Destructive operations (`DROP TABLE` / `DROP COLUMN`)

Dropping things in a live database is the most expensive class of migration.
Rules:

* **Two-phase drop.** Never delete a column or table in the same migration that
  removes the last reader. The first migration stops *writing* the column and
  removes it from the entity; only the *next* migration, ideally one release
  later, runs the `DROP COLUMN`. The grace period gives time to roll back the
  app deployment without losing the column.
  - Phase 1 (`V<n>__stop_writing_old_column.sql`): drop NOT NULL on the
    column, drop any constraints referencing it, leave the data in place.
    Update the entity at the same time so it no longer touches the column.
  - Phase 2 (`V<n+k>__drop_old_column.sql`): the actual `ALTER TABLE ... DROP
    COLUMN`. Land this in a *separate* release, after at least one production
    deploy with phase 1.
  - **Worked example: V125** (`drop_legacy_material_and_ship_type_columns`).
    Phase 1 was code-only (#275): every reader of `material.is_manual_entry` moved
    to `source_systems` (which V116 had backfilled) and every reader of
    `ship_type.description` moved to `description_en` / `description_de`, while both
    columns stayed in place through a soak. Phase 2 (#281, 2026-06-01) ran the two
    `DROP COLUMN`s **and** deleted the JPA fields `Material.isManualEntry` /
    `ShipType.description` in the same change — which is what keeps
    `ddl-auto=validate` green: during the soak both the field and the column existed,
    after V125 neither did, and at no point was there a field without its column.
  - **Owner-approved single-phase exception: V239** (`drop_guest_role_and_guest_edit_token`,
    2026-09-06, decision D10 of `MEMBERS_ONLY_PLAN.md`). The `mission_participant`
    `guest_edit_token_hash` column is dropped in the same unit of work that removes its last
    reader, deliberately, because the column stored the **hash of a capability token**: leaving it
    through a soak would leave a credential-shaped artefact in the database with no code able to
    mint, rotate or check it, and the whole point of the change is that the capability no longer
    exists. The rule's own reason — room to roll the app back — is what the file's header states
    is being given up, and it names the forward fix (re-add nullable, let `DataInitializer`
    re-seed the role, replay the assignments from the migration's INFO line) rather than a
    backward one. **This is the shape of exception the rule admits: a column whose *content* is
    the risk, approved by the repository owner in the plan, with the rollback written down.** It
    is not a precedent for dropping ordinary data a release early.
* **Drop-then-add in one file is allowed only on tables that did not yet ship
  to production**, i.e. for migrations younger than the most recently
  deployed version. Use sparingly and only during the very first iteration of
  a feature.
* **`DROP CONSTRAINT IF EXISTS`** before dropping the column it references, so
  the migration is robust against partially-applied state on developer
  databases. See [`V21__update_refinery_good.sql`](V21__update_refinery_good.sql)
  for the pattern.

If you are about to drop something and you are not 100 % sure no other code
path reads it, search the whole repository for the column / table name
(`Grep` across `backend/`, `frontend/`, `keycloak-theme/`, `realm-export.json`)
before merging.

## Data migrations

Schema-only migrations (pure `CREATE TABLE`/`ALTER TABLE`) are easy. Data
migrations are where things go subtly wrong.

* **Backfill in SQL inside the migration file.** Use `UPDATE ... WHERE ...`
  statements between the `ALTER TABLE ADD COLUMN` and the
  `ALTER TABLE ALTER COLUMN ... SET NOT NULL`. See
  [`V72__add_role_code.sql`](V72__add_role_code.sql) — it shows the canonical
  add-column → seed-known-rows → derive-the-rest → tighten-NOT-NULL sequence.
* **Don't backfill from Java.** A `DataInitializer`-style backfill happens
  *after* Spring's `EntityManagerFactory` validates the schema; if the column
  is `NOT NULL` at that point, validation has already failed. Putting the
  backfill in SQL inside the migration makes the constraint-tightening step
  atomic with the data fix.
* **Idempotent default values.** When a backfill sets a default ("everyone
  who joined before 2025 gets `is_active = true`"), the SQL must be
  idempotent so re-running on a partially-applied DB does not change the
  result. Prefer `WHERE column IS NULL` clauses, not `UPDATE ... SET ... ;`
  unconditional rewrites.
* **No side-effects to other tables.** A migration named
  `V73__add_join_date_to_user.sql` must not touch the `mission` table; if a
  cross-table change is needed, that is a separate migration in the same PR.

## Performance / locking

* `ALTER TABLE` on PostgreSQL acquires an `ACCESS EXCLUSIVE` lock for the
  duration of the statement. For tables with significant row count
  (`inventory_item`, `mission`, `job_order`, `material`) prefer
  `ADD COLUMN <name> <type>` *without* a default — that's an `O(1)` metadata
  update. If a default is required, ship it in two phases (add as NULL,
  backfill, set default + NOT NULL) to keep each lock short.
* `CREATE INDEX CONCURRENTLY` is **not** available inside a migration because
  Flyway runs each file in a transaction by default. Long-running index
  creation has to either:
  - run outside Flyway (manual DBA step, documented in the PR), or
  - be added with `-- ${flyway:noTransaction}` at the top of the file plus an
    explicit `CREATE INDEX CONCURRENTLY` statement.
    Currently the codebase does not need this; if you do, see
    [`db.DatabaseIndexMigrationTest`](../../../../test/java/de/greluc/krt/profit/basetool/backend/db/DatabaseIndexMigrationTest.java)
    for how the existing test enforces what indexes ship.

## Tests

The test profile (`application-test.yml`) runs every `@SpringBootTest` against
a real PostgreSQL 18 container started on demand by Testcontainers' JDBC-URL
trick (`jdbc:tc:postgresql:18-alpine:///testdb`), with Flyway enabled
(`spring.flyway.enabled: true`) and `ddl-auto: validate`. The backend has no
H2 dependency at all. That
means the standard test suite **does** execute every migration in this
directory and then validates the resulting schema against the JPA entities —
the same contract as prod:

* `./gradlew :backend:test` is the primary check for a new migration. A
  migration with broken SQL, or one that drifts from the entity annotations,
  fails the suite at context startup with a Flyway or `ddl-auto=validate`
  error. (Pure Mockito unit tests don't load the Spring context and start no
  container, so they neither slow down nor validate anything here.)

* Optionally, you can additionally boot the dev Compose stack to see the
  migration run against a long-lived database (useful for eyeballing
  backfilled data or testing against pre-existing rows that the throwaway
  test container never has):

  ```bash
  docker compose --profile dev up -d db-backend-dev keycloak-dev redis-dev
  ./gradlew :backend:bootRun
  ```

  This is extra verification, not the gate — the test suite already covers
  the apply-and-validate path.

* If you change the schema in a way that affects Hibernate's entity mapping
  (rename a column, change a type, add a constraint), update the entity in
  the **same** commit. A migration that ships without the entity change will
  break every `bootRun` on the next pull.

* If you add a column that is used by `DataInitializer`, also extend
  `BackendApplicationTests` (or a more focused integration test) to verify
  the seeded state.

## File header convention

Every migration file should open with a short SQL comment explaining *why*
the change is happening, not just what. Future-you reading `V42__...sql`
in five years will not remember the bug or feature request that motivated
it. Example:

```sql
-- Stable, machine-readable identifier for roles. The display name (`name`) can
-- be renamed by an admin without changing the role's identity. `code` is what
-- the DataInitializer matches against on startup so a renamed role is no
-- longer silently re-created with default permissions on the next boot.
ALTER TABLE role ADD COLUMN code VARCHAR(64);
```

A reader should be able to grep `git log -- V42__*` and find the PR; the
comment should answer the obvious "why now?" question before they need to.

## Checklist before merging a migration

- [ ] Filename is `V<next-unused-integer>__<snake_case>.sql`.
- [ ] Top-of-file comment explains *why*, not just what.
- [ ] No `DROP TABLE` / `DROP COLUMN` on data that's already in production
  without a phase-1 stop-writing predecessor in an earlier release.
- [ ] Backfill (if any) is idempotent and inside the same migration file.
- [ ] The matching JPA entity was updated in the same commit.
- [ ] `./gradlew :backend:test` passed — the suite applies the migration to a
  Testcontainers Postgres and validates the schema against the entities.
- [ ] If indexes were added, [`DatabaseIndexMigrationTest`](../../../../test/java/de/greluc/krt/profit/basetool/backend/db/DatabaseIndexMigrationTest.java)
  knows about them.
- [ ] The change is mentioned in `CHANGELOG.md` under the right `### Added`
  / `### Changed` / `### Migration` heading.
- [ ] Version numbering passes the `Flyway Migrations` CI check (no duplicate
  `V<n>`, new files numbered after the current tip on `main`). Run it
  locally before pushing:
  `FLYWAY_BASE_REF=origin/main scripts/check-flyway-migrations.sh`.

