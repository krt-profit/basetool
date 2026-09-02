# Agent Prompt — SC Wiki + UEX-Items Sync Implementation

Doc type: **historical plan** — the briefing handed to the agent that implemented
[`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md) in 2026-05. Restored to `docs/` on 2026-09-02: the
deployment runbook references it twice and `V106MigrationTest` cites its "§5 pitfall #5".

It is kept as a record of how the work was commissioned, **not** as instructions to follow now — the
plan it briefs has shipped, and the deviations from it are listed in that document's header.

> [!important] R9 has landed — the *column* is gone, the *wire field* is not
> Verified against `main` on 2026-09-02. §2.1 below still says "The existing `isManualEntry` field
>
>> stays; the new `sourceSystems` enum coexists", and §9 still pencils the destructive cleanup in as
>> "V116". Neither holds any more. R9's cleanup landed on **2026-06-01** (#281):
>> [`V125__drop_legacy_material_and_ship_type_columns.sql`](../backend/src/main/resources/db/migration/V125__drop_legacy_material_and_ship_type_columns.sql)
>> dropped `material.is_manual_entry` and `ship_type.description`, and the JPA fields
>> `Material.isManualEntry` / `ShipType.description` were removed in the same change. (§2.1's other
>> caveat — "R9 cleanup may have landed by then" — is about the *Spezialkommando* R9 chain V100–V105
>> and which V-number is free next, not about these two columns; §5 pitfall #6 says so explicitly.)
>
> What survives is a **derived DTO wire field**, not the column: `MaterialDto.isManualEntry` is
> derived in `MaterialMapper` from `sourceSystems == MANUAL`, and `ShipTypeDto.description` is
> derived in `ShipMapper` from `descriptionDe` falling back to `descriptionEn`. Seeing either name
> on the API is not evidence that its column still exists — that confusion is the whole reason this
> banner is here.
>
> Nothing below this banner has been rewritten. It is the 2026-05 briefing as it was handed over.

This file is the **briefing for a Claude Code agent** that will implement the
plan in [`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md). The agent starts with
no memory of how the plan was authored — everything it needs to do its job is
either in this file or in the plan it points at.

Hand the agent the entire content of this file as the initial prompt.

---

## 1. Mission

You are implementing a **two-source external-data sync** for the Profit
Basetool (squadron-management web app for the "DAS KARTELL" / IRIDIUM
organization). The repo is a Spring Boot 4 backend + Thymeleaf frontend on
Java 25, PostgreSQL 18, Gradle 9 with the Kotlin DSL, refreshVersions for
dependency management.

Your authoritative blueprint is [`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md)
at the repo root. **Read it cover-to-cover before writing a single line of
code.** It defines:

- the two data sources (UEX `/items`, `/vehicles`, `/items_prices_all`,
  `/categories`, `/companies` plus the existing `/commodities` chain; and the
  Star Citizen Wiki API at `https://api.star-citizen.wiki`),
- the joint schema (one `game_item` row per in-game UUID; one `ship_type` row
  per vehicle UUID; one `material` row per commodity),
- the resolution chain (`byExternalUuid` → `bySlug` → `byName`), with the
  measured ~30% empty-UEX-uuid rate that justifies the slug fallback,
- 6 release phases (R1 → R9) with explicit migration numbers (V106 → V116).

The plan is verification-backed: §3.6 documents 241 cross-ref tests with 0
UUID mismatches; §4 documents 165 paired commodity verifications and the
fuzzy / alias / catalog-granularity decisions. Trust those numbers — do not
re-litigate them.

**Your scope for this engagement: ship R1 only**, plus the deployment runbook
that will carry every subsequent phase through production. Stop and request
review before starting R2.

---

## 2. Repository context

### 2.0 Where this prompt and the plan live

At the time of writing, both
[`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md) and this prompt live on the
feature branch **`claude/vigorous-feistel-4b6d60`** — *not* on `main`. They
were authored in a research session and may or may not have been merged
yet by the time you start.

Before you do anything else:

```bash
git rev-parse --abbrev-ref HEAD                       # which branch am I on?
git ls-files --error-unmatch SC_WIKI_SYNC_PLAN.md     # is the plan on this branch?
```

- **If both files exist on your current branch** (likely because the planning
  PR has already merged to main, *or* because you were spawned on the
  `claude/vigorous-feistel-4b6d60` branch directly): proceed.
- **If they don't exist on your branch** (you're on a fresh main checkout
  and the planning PR has not merged yet): you have two options. Pick
  whichever fits the user's intent better, and confirm with the user if
  unsure:
  1. **Recommended:** ask the user to merge the planning PR first. R1
     implementation work should land on top of a clean `main` that already
     carries the plan + this prompt + the runbook stub.
  2. Cherry-pick or fetch the two files directly:

     ```bash
     git fetch origin claude/vigorous-feistel-4b6d60
     git checkout origin/claude/vigorous-feistel-4b6d60 -- \
         SC_WIKI_SYNC_PLAN.md SC_WIKI_SYNC_AGENT_PROMPT.md
     ```

     Commit them on your R1 branch as the first commit, with a clear
     `docs(planning): bring SC Wiki sync plan + prompt onto R1 branch`
     message + DCO + Co-Authored-By.

The plan + prompt do **not** need to be on `main` for you to work — they
just need to be readable on the branch you're implementing R1 on, so future
reviewers see the context next to the code.

### 2.1 Repo layout

- **Repo root**: the working directory you start in. After §2.0 is settled,
  the plan, this prompt, `CLAUDE.md`, `CONTRIBUTING.md` and the existing
  UEX integration all live under there.
- **You are in a git worktree off `main`** (or you should be — verify with
  `git rev-parse --show-toplevel` and `git worktree list`). Treat `main` as
  the integration branch you'll target with the PR.
- **Existing UEX integration** to mirror:
  - [`UexClient.java`](backend/src/main/java/de/greluc/krt/iri/basetool/backend/integration/UexClient.java)
    — WebClient with ETag + 16 MB buffer + 30 s timeout, fail-soft `List<>`
    returns. Read this file *first* before writing `ScWikiClient`.
  - [`UexProperties.java`](backend/src/main/java/de/greluc/krt/iri/basetool/backend/config/UexProperties.java)
    — `@ConfigurationProperties("krt.uex")` with `@Validated` constraints.
    Mirror exactly for `ScWikiProperties`.
  - [`UexScheduler.java`](backend/src/main/java/de/greluc/krt/iri/basetool/backend/service/UexScheduler.java)
    — `@Async("uexExecutor")` + `@Scheduled(fixedDelayString=...)`,
    per-service exception swallow.
  - [`UexCommodityService.java`](backend/src/main/java/de/greluc/krt/iri/basetool/backend/service/UexCommodityService.java)
    — the upsert + orphan-handling pattern. Mirror its `findByIdCommodity` →
    `findByName` fallback chain, and the `clearStalePrices(seenIds)`
    orphan-mark gated on non-empty seen set.
  - [`UexVehicleService.java`](backend/src/main/java/de/greluc/krt/iri/basetool/backend/service/UexVehicleService.java)
    — currently name-only match. Plan §8.5 hardens it to UUID-first; that's a
    **R2** concern, not R1.
- **Migration directory**: `backend/src/main/resources/db/migration/`. The
  next free V-number is **V106** (verify with
  `ls backend/src/main/resources/db/migration/ | sort -V | tail -5` before
  committing — R9 cleanup may have landed by then).
- **Material entity**:
  [`Material.java`](backend/src/main/java/de/greluc/krt/iri/basetool/backend/model/Material.java)
  — already 28 columns. R1 adds nine more (see plan §6.1). The existing
  `isManualEntry` field stays; the new `sourceSystems` enum coexists.
- **Manufacturer entity**:
  [`Manufacturer.java`](backend/src/main/java/de/greluc/krt/iri/basetool/backend/model/Manufacturer.java)
  — already 7 fields. R1 adds `uex_company_id` + `scwiki_uuid` + 6 more.

---

## 3. R1 scope — what to build in this PR

From plan §11, R1 is the **foundation slice**: additive only, no behavior
changes, scheduler defaults `enabled = false`. Concretely:

### 3.1 Migrations

- **V106** `add_scwiki_columns_to_material.sql` — exactly the columns listed
  in plan §6.1 (including the `is_visible` column added during the §4.3
  revision), plus `source_systems` and the backfill UPDATE to set
  `source_systems='UEX_ONLY'`, `is_visible=true` for existing rows.
- **V107** `add_cross_ref_columns_to_manufacturer.sql` — exactly the columns
  in plan §6.4.
- **V108** `create_material_external_alias.sql` — table per plan §6.2, **plus
  the §4.2 seed inserts** for *only* the 6 verified rows (4 fuzzy auto-seed
  + Lastaprene + Lunes). **Do NOT seed the Construction-* triplet or Combat
    Supplies** — plan §4.2.1 explains why; an admin will add them manually
    after in-game verification.
- **V109** `create_uex_category.sql` — table per plan §6.6.

For each migration, write a `V<n>Test` integration test under
`backend/src/test/.../migration/` that uses the existing TestContainers
config to apply migrations in sequence and asserts the schema delta. Don't
use any test stack with production credentials —
[CLAUDE.md](CLAUDE.md#testing) is explicit: `.env.test` and the
isolated `docker-compose.test.yml` stack only.

### 3.2 New `integration/scwiki` package

- `ScWikiProperties.java` — exactly the property block in plan §5.2. All
  Bean Validation constraints (`@NotBlank`, `@Min`, `@Max`, `@NotNull`).
  `schedulerEnabled` defaults to **`false`** in R1 (the plan defaults to
  `true`, but for R1 we don't want it running until R2 ships the services).
- `ScWikiClient.java` — clone of `UexClient` with the three behavioral
  differences in plan §5.3 (paginated `fetchAllPages`, rate-limit pacing,
  `include=` support). Re-use the same ETag pattern verbatim.
- `dto/scwiki/ScWikiResponseDto.java`, `ScWikiCommodityDto.java`,
  `ScWikiMetaDto.java`, `ScWikiPaginationLinksDto.java`. Records. Add the
  `@JsonIgnoreProperties(ignoreUnknown = true)` annotation on each.
- A skeleton `ScWikiScheduler.java` with the `@Async("scWikiExecutor")` +
  `@Scheduled(fixedDelayString = "${krt.scwiki.scheduler-delay:86400000}")`
  declarations. Body is just a guard `if (!properties.getSchedulerEnabled())
  return;` and a `log.info("ScWikiScheduler invoked but disabled")`. The real
  services come in R2/R3.
- `AsyncConfig.SCWIKI_EXECUTOR` constant + bean — size 2, queue 0, declared
  in the existing `AsyncConfig` class. Re-use the MDC-propagating decorator
  that the `uexExecutor` already uses (see CHANGELOG 2026-05-25 entry — the
  decorator is already in place; just wire the new pool through it).

### 3.3 Admin CRUD for `material_external_alias`

- `MaterialExternalAlias` entity + `MaterialExternalAliasRepository` +
  `MaterialExternalAliasService` + `MaterialExternalAliasController` under
  the existing `controller/admin/` (or wherever the existing admin
  controllers live — check before deciding). All write endpoints
  `@PreAuthorize("hasRole('ADMIN')")`.
- Thymeleaf admin page `/admin/material-aliases` with a list view and an
  add/edit form. Use the existing admin-page styling (look at one of the
  existing admin pages such as `/admin/materials` or `/admin/settings` to
  match conventions exactly — no new fonts, no new component patterns).
- i18n keys under `admin.materialAlias.*` in both
  `messages_en.properties` and `messages_de.properties`. German umlauts as
  `\uXXXX` per CLAUDE.md.

### 3.4 ArchUnit rule

Add to
[`ArchitectureTest.java`](backend/src/test/java/de/greluc/krt/iri/basetool/backend/ArchitectureTest.java):

> *Any class in `integration.scwiki` must inject `ScWikiClient`.*

Mirror the existing UEX rule if there is one (search for "UexClient" in
ArchitectureTest); otherwise model on the
`staffelScopedServicesMustWireOwnerScopeOrAuthHelper` rule's shape.

### 3.5 Tests

- `ScWikiClientTest` — WireMock-backed: pagination loop, ETag 304
  short-circuit, rate-limit pacing assertion (count `Thread.sleep`-ish
  durations or use the `Mockito` clock), empty-response idempotence.
- `MaterialExternalAliasServiceTest` — Mockito; CRUD + seed integrity check.
- `MaterialExternalAliasControllerTest` — MockMvc; ADMIN-gate enforcement.
- `V106MigrationTest`, `V107MigrationTest`, `V108MigrationTest`,
  `V109MigrationTest` — TestContainers; assert column presence, defaults,
  seed rows.
- The ArchUnit rule from §3.4 lands as a test method, no separate file.

### 3.6 Deliverable: deployment runbook

After the R1 code is green, write
[`SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md`](SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md)
at the repo root. **Outline below in §6.** This single runbook covers every
deploy from R1 through R9 — you write it once, and each subsequent phase
extends it with its phase-specific section.

---

## 4. Hard rules (taken from CLAUDE.md — non-negotiable)

These will be checked in CI; if you skip them the PR will fail.

1. **Gradle wrapper only.** Every test invocation:
   `./gradlew :backend:test --tests "ClassName.methodName"`.
   Never use the IDE test runner. Never use the harness `run_test` tool.
2. **`./gradlew spotlessApply` before every push.** Spotless is part of
   `check`; Checkstyle runs with `maxWarnings=0`. CI will reject unformatted
   Java instantly.
3. **JavaDoc is gate-enforced** on every new `public`/`protected` member.
   Boilerplate is forbidden ("Gets the value", "Helper method", restating
   the method name in prose). Read the implementation before writing the
   Javadoc; if you can't write a concrete sentence about behavior /
   parameters / return / side effects, you don't understand the method yet.
4. **Every commit needs a DCO sign-off** — use `git commit -s`. The
   `Signed-off-by:` trailer's email must match the author email
   case-insensitive. If you forget, fix locally before pushing:
   `git commit --amend --signoff --no-edit`.
5. **Every commit Claude composes needs a `Co-Authored-By:` trailer**
   naming the model. Use the exact form
   `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`
   (substitute the actual model name from your system prompt; the email
   stays `noreply@anthropic.com` regardless). Apply to every commit, even
   one-line typo fixes.
6. **Git + GitHub content in English**, even though the user prompts you in
   German. Commit messages, PR titles + bodies, issue comments — all
   English. CHANGELOG.md entries stay German (matching existing style).
7. **No production credentials in tests or local stacks.** `.env.test` and
   `docker-compose.test.yml` only. Generate a fresh `keystore.p12` locally,
   use a stripped `realm-export.json`. README's "Running the Local Test
   Stack" section has the exact commands.
8. **No backwards-compat shims for unused things.** Don't add `@Deprecated`
   on methods that don't have any caller yet. Don't add fallback paths for
   data that doesn't exist.
9. **Don't introduce new ArchUnit violations.** Pre-existing ones in
   untouched code are out of scope, but never *add* one. The existing rules
   are listed in CLAUDE.md §Security model.

---

## 5. Pitfalls and decisions already made — do not re-litigate

The plan is the result of live verification (241 cross-ref tests, 165
commodity paired comparisons, ~40 detail-endpoint fetches). The following
*specific* decisions are settled — implement them as-is:

1. **UEX UUIDs are identical to Wiki UUIDs when both have one.** Verified on
   241 paired tests; 0 mismatches. The resolution chain trusts UUID
   identity. (R2 concern, not R1; just don't second-guess it in R1.)
2. **~30% of UEX rows have empty `uuid`** — heavy in Avionics/FlightBlade
   (100%), Decorations (88%), Liveries (42%), Armor (~33%). Use the slug
   fallback. (R2 concern.)
3. **The §4.1 fuzzy seed has 4 entries; the §4.2 manual seed has 2.**
   Construction-* and Combat Supplies were removed after verification —
   don't add them back. The V108 seed insert must contain exactly 6 rows.
4. **The `kind==""` junk filter is unreliable** — it drops real harvestables
   like Blue Bilva and Uncut SLAM. Use only name-pattern hard drops + a
   maintained `HARDCODED_ATMOSPHERE_SET`. The plan §8.9 has the final Java
   code. R3 concern.
5. **The `is_visible` column on `material` is new and defaults to `true`**
   to preserve existing UEX-sourced catalog visibility. Wiki-only writes set
   it to `false` until admin review. Don't accidentally default to `false`
   on the existing rows. The V106 migration must explicitly
   `UPDATE material SET is_visible = TRUE WHERE is_visible IS NULL;` (or
   set the column DEFAULT to TRUE before the data load).
6. **The plan numbers migrations from V106**, assuming the SK-R9 destructive
   cleanup chain (V100–V105) has landed on main. **Verify the next free V-
   number is actually 106** before writing the files — if main has moved,
   renumber. Same rule for migration-test class names.
7. **The plan defaults `schedulerEnabled = true`** for the Wiki scheduler.
   **In R1 only**, default to `false`. We don't want the scheduler firing
   against an empty implementation. The R2 PR flips it to `true`.

---

## 6. Deployment runbook — what to produce

Write [`SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md`](SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md)
at the repo root, modeled on
[`SK_ROLLOUT_RUNBOOK.md`](SK_ROLLOUT_RUNBOOK.md) but adapted to *this*
work. The runbook must answer: "What does the on-call engineer do, in
order, to safely deploy R1 to production, and what's the rollback if it
goes wrong?" Plus the same question for every later phase as we ship it.

### 6.1 Runbook structure

```
# Deployment Runbook — SC Wiki + UEX-Items Sync

## Status table — which phases have shipped to which environment

| Phase | Dev | Staging | Prod | Date | PR | Notes |
|---|---|---|---|---|---|---|
| R1 — Foundation | TBD | TBD | TBD | TBD | TBD | TBD |
| R2 — UEX items | … |
…

## 0. Conventions
- Cite every env var by name and where it's set.
- For every "run X" step, give the exact command, the expected output, and
  what to do if it diverges.
- Every irreversible step gets a Rollback subsection.

## 1. R1 — Foundation deployment

### 1.1 Pre-deployment checks
…

### 1.2 Deployment steps (production)
…

### 1.3 Smoke tests (post-deploy)
…

### 1.4 Rollback
…

### 1.5 Monitoring during the soak window
…

## 2. R2 — UEX item catalogue
( same shape as §1, written when R2 lands )

## 3. R3 — Wiki commodity merge
…

## 4. R4 — Wiki items + Blueprints
…

## 5. R5 — Full Wiki item backfill
…

## 6. R6 — Manufacturer Wiki reconciliation
…

## 7. R7 — UEX item prices
…

## 8. R8 — Soak + V115 cleanup
…

## 9. R9 — V116 destructive cleanup
( cross-link to the to-be-written R9 roadmap doc, modeled on
  R8_DESTRUCTIVE_ROADMAP.md )

## Appendix A — Common operations
- How to manually re-run a sync from the admin UI / a shell
- How to read the sync report (`/admin/sync-reports/scwiki` and `/uex`)
- How to add a `material_external_alias` row by hand
- How to flip `material.is_visible` on a Wiki-only row after review
…

## Appendix B — Failure modes catalog
- "Wiki API returns 5xx for an entire sync run"
- "UEX `/items` schema drift introduces a new field"
- "Sync race conflict spikes" (the `SYNC_RACE_CONFLICT` event)
- "Slug-fallback runs blow the per-cycle budget"
- "ArchUnit rule fails on a freshly-renamed class"
…

## Appendix C — Pre-cutover checklist
A printable checklist the on-call engineer signs off on before approving
the production push. Items include:
- [ ] Migration test green on a copy of the prod schema
- [ ] `./gradlew check` green
- [ ] Schema-validate mode confirms `ddl-auto=validate` still works
- [ ] Sync-report admin pages open without 500
- [ ] Feature flags set as expected for this phase
- [ ] Backup taken (link to the backup runbook)
- [ ] CHANGELOG.md entry merged
- [ ] All translation keys present in DE and EN
…
```

### 6.2 R1-specific content the runbook must include in §1

- Migration order: V106 → V107 → V108 → V109. All additive; rollback is
  a column drop or table drop only.
- `krt.scwiki.scheduler-enabled` env var must be `false` (or unset — the
  default is `false` in R1). State this explicitly so the on-call doesn't
  switch it on prematurely.
- Smoke test commands:
  - `psql -d basetool -c "\d material"` → expect new columns visible.
  - `psql -d basetool -c "SELECT count(*) FROM material_external_alias"` →
    expect 6 (the V108 seed).
  - `curl -fsS https://<host>/api/admin/material-aliases` (with an admin
    JWT) → expect a non-empty list.
  - Open `/admin/material-aliases` in a browser → page renders, list shows
    the 6 seed rows.
- Verify the existing UEX scheduler still runs unchanged. Hourly log line
  pattern: `Scheduled task for UEX data` (search backend's structured logs
  at `logs/backend.json`).
- Rollback: drop the columns / table in reverse order. The plan migrations
  are all additive so this is a clean reversal. Test the rollback on
  staging first; the migrations don't carry data so rollback is fast.

### 6.3 Use the existing conventions

- `SK_ROLLOUT_RUNBOOK.md` exists and is the template you're matching. Open
  it first. Don't invent a new format.
- The handover PDF / Maintenance page references in CLAUDE.md are
  orthogonal; you don't touch them. Just match the tone and depth of the
  SK runbook.
- Use Markdown headings — no rendered HTML, no PDF artifacts.

---

## 7. Workflow

0. **Resolve §2.0 first.** Confirm the plan + this prompt are on your
   working branch. If they aren't, do the cherry-pick (or pause and ask the
   user to merge the planning PR). Do **not** start step 1 with missing
   inputs.
1. **Read the plan and this prompt fully.** Cross-check the §5 "pitfalls"
   list — do not start coding until each pitfall makes sense to you.
2. **Inspect the existing UEX integration files** named in §2 of this
   prompt. Understand the patterns. Don't write `ScWikiClient` until you've
   read `UexClient.java` and `UexCommodityService.java` end to end.
3. **Plan task list** with `TaskCreate` — 5–8 items, one per substantive
   subsection of §3 (migrations, properties + client, scheduler skeleton,
   alias CRUD, ArchUnit, tests). Mark each `in_progress` as you start it;
   `completed` only when *its* tests are green.
4. **Implement and test phase R1 in a single PR** on a new branch off
   `main`. Branch naming: `claude/sc-wiki-sync-r1-foundation` (or pick a
   short slug — match the repo's existing branch conventions).
5. **Write the deployment runbook** (§6) as a single commit at the end of
   the R1 work. Don't push it incrementally — finish the code, then write
   the runbook once you know exactly what shipped.
6. **Run the full local CI:** `./gradlew spotlessApply check`. Iterate until
   green. If a test fails, read the failure carefully — don't `@Disabled`
   anything to make the suite pass.
7. **Open a PR against `main`**. PR title `feat(integration): R1 — SC Wiki
   sync foundation + alias table + runbook` (under 70 chars). PR body
   follows the format in CLAUDE.md (Summary + Test plan). Link to the plan
   and this prompt.
8. **Stop**, report to the user what shipped, and **wait for explicit
   approval** before starting R2. Do not auto-continue.

---

## 8. Stop conditions — when to pause and ask

Pause and request the user's input if any of these happen:

- **The plan and reality disagree.** If you find that a UEX endpoint
  returns a different schema, or that an existing repository method you
  expected isn't there, surface the conflict in plain language — don't
  silently work around it.
- **A migration would touch existing rows in a non-obvious way.** All R1
  migrations are additive; if you find yourself writing a destructive
  `ALTER`, you're off the plan.
- **A test fails for a reason you can't quickly diagnose.** Don't skip it.
  Read the stack, read the surrounding code, ask if you're unsure.
- **An ArchUnit rule that already exists fails on your change.**
  Investigate the rule before considering relaxation.
- **The next free V-number is not 106.** If main has moved (R9 destructive
  work, other Flyway additions), confirm the renumbering plan before
  writing the SQL files.
- **You think a part of the plan is wrong.** The plan is well-verified but
  not infallible. If you've found evidence it's wrong on a specific point,
  document the evidence and ask before changing course.

---

## 9. Out of scope for R1

To prevent scope creep — these are R2+ concerns; do not touch them in R1:

- The actual sync logic for commodities / items / blueprints / vehicles.
  R1 is plumbing only.
- The `game_item`, `blueprint`, `blueprint_ingredient`,
  `blueprint_dismantle_return`, `game_item_price` tables. R3+.
- Changes to `UexVehicleService` (UUID-first match hardening). R2.
- The full Wiki item backfill. R5.
- Anything that requires running a live sync against the real Wiki or UEX.
  R1 has scheduler **disabled by default**; no live traffic.
- The CHANGELOG entry — write it when the PR opens, not before. Follow the
  existing style (short, terse, German, what+why).

---

## 10. Definition of done for this engagement

- [ ] Plan + this prompt are committed on the R1 implementation branch
  (or already present via main). §2.0 resolved.
- [ ] V106 → V109 migrations exist, are formatted, and pass migration tests.
- [ ] `ScWikiProperties`, `ScWikiClient`, `ScWikiScheduler` skeleton exist
  and pass unit tests.
- [ ] `MaterialExternalAlias` CRUD (entity, repository, service,
  controller, Thymeleaf page) exists, is `@PreAuthorize`-gated, and
  passes tests.
- [ ] V108 seeds exactly 6 alias rows (matching plan §4.1 + §4.2 verified
  entries; *not* Construction-* / Combat Supplies).
- [ ] ArchUnit rule for `integration.scwiki` lands and passes.
- [ ] `./gradlew check` is green from a clean clone.
- [ ] [`SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md`](SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md)
  exists with §1 (R1) fully written and §2-§9 stubbed with their
  headers.
- [ ] PR opened against `main` with a complete description and a
  checklist-style test plan.
- [ ] Every commit carries DCO `Signed-off-by:` + `Co-Authored-By:`
  trailers.
- [ ] CHANGELOG.md "Unreleased" section has a German entry following the
  repo's style.
- [ ] No live sync traffic was sent during R1 (scheduler stays disabled).
- [ ] You stop and report back. No R2 work without explicit go-ahead.

