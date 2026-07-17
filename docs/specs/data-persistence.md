> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-21.
> **Owner area:** DB/DATA · **Migration conventions:** [`db/migration/README.md`](../../backend/src/main/resources/db/migration/README.md)

# Data & persistence

## Context & goal

The schema is owned by Flyway and validated (never auto-generated) against the entities, so
prod and test run identical, reviewed DDL. Queries avoid N+1 by construction.

> **Concurrency note.** The optimistic-locking / `…WithinTransaction` / bulk-update-in-loop
> rules are **deliberately kept inline in `CLAUDE.md` → "Concurrency"** because they are
> "read this before you touch multi-step transactions" agent guidance that must live in the
> always-read file. They are data-integrity requirements; treat that section as part of this
> spec's contract.

## Requirements

### REQ-DATA-001 — Flyway owns the schema; `ddl-auto = validate`

Every schema change is a new `V<n>__<description>.sql` in
`backend/src/main/resources/db/migration`. **Hibernate `ddl-auto` is `validate`
everywhere — never `update` or `create`.** Full conventions (destructive-ops two-phase
rule, data-migration patterns, performance/locking, test caveats, pre-merge checklist) live
in [`db/migration/README.md`](../../backend/src/main/resources/db/migration/README.md) — read
it before adding a migration.

### REQ-DATA-002 — Startup seeding

`DataInitializer` seeds roles/permissions on startup.

### REQ-DATA-003 — No N+1

Prefer `JOIN FETCH`, `@EntityGraph`, or Spring Data projections over lazy-load fan-out. Replace a
`findById`-in-a-loop with one `findAllById` keyed into a map (e.g.
`MissionService.resolveMembershipOrgUnits`, `BankLedgerService` wipe-reset / reversal holders), and
memoise a request-constant lookup or verdict on the `HttpServletRequest` — or derive both projections
from a single load — so repeated per-row / per-resolver reads collapse to one (e.g. the
`PromotionEligibilityService` evaluation set is loaded once per user across all rank transitions, and
`OwnerScopeService.currentCallerMemberships()` backs the blueprint-overview gate plus the cascading
and own-level oversight scopes from one membership read). Dead unbounded eager finders
(`SELECT i FROM InventoryItem i` with no caller) are deleted, not kept as a foot-gun.

A **paged list** must not fan a per-row query out across the page: enrich the whole page from a
single batched query keyed by the page's ids and join it in memory, mirroring
`OperationFinanceService.getOperationFinances` (`findAllByMissionIdIn` + `Collectors.groupingBy`).
The job-order list follows this — `JobOrderService.getAllJobOrders` loads every order's linked
stock via `InventoryItemRepository.findMaterialStockRowsByJobOrderIds` and every SK order's claims
via `MaterialClaimRepository.findByJobOrderIdInOrderByCreatedAtDesc` once per page, then sums each
material bucket at its own quality floor in memory, instead of one `SUM` per material per order plus
one claim query per SK order. The single-order write paths keep the per-order queries (a bounded
handful) via a shared resolver-parameterised projection.

A **request-constant verdict or lookup** consulted more than once per request (e.g. per row) must be
memoised on the `HttpServletRequest` so it resolves once: `OwnerScopeService.canViewJobOrders()` and
`currentMemberOrgUnitIds()` cache on a request attribute, and `UserMapper` memoises the per-user
Staffel-membership lookup so its three derived-field resolvers share one query (falling back to a
direct query outside an HTTP request).

A **by-id aggregate load must never fetch-join two sibling collections.** `@EntityGraph` on a `findBy`
that graphs two `@OneToMany`s produces a `|childA| × |childB|` cartesian result — every parent scalar
(including any TEXT column) repeated across the row product. Graph at most one collection and let the
other batch-load under `hibernate.default_batch_fetch_size` (`MissionRepository.findById` graphs only
`participants`; `assignedUnits` batch-loads — #1138). The **paged** list variant graphs neither (it
maps only scalars + `@ManyToOne`s, and a collection fetch-join forces in-memory pagination, `HHH000104`).

An **authorization gate must not load the aggregate its decision does not read.** A `@PreAuthorize`
gate that inspects only a couple of `@ManyToOne`s / a small association (mission `owningOrgUnit` /
`parent` chain, or `owner` / `managers`) resolves the entity through an `EntityManager.find`
repository fragment (`MissionRepository.findByIdForAuthorization`) — no collection graph, so the
roster is never loaded, and `em.find` (unlike a JPQL query) is first-level-cache aware and never
auto-flushes, so the gate never re-runs the heavy aggregate query it would otherwise pay twice per
request (gate + handler transaction, #1139). A **point write** that mutates a single child row
likewise resolves that child directly and reads the parent's scalars through the child's `@ManyToOne`
rather than loading the parent aggregate to stream it (`MissionParticipantService.checkIn` / `checkOut`
/ `updatePayoutPreference` via `getParticipant` + `participant.getMission()`, #1140).

**Acceptance**: `JobOrderServiceAssigneeAndListTest` (one batched stock query per page with no per-
material `SUM` on the list path, plus the in-memory sum reproducing the native per-bucket semantics
at each quality floor), `JobOrderMaterialStockRowQueryDataTest` (the batched projection runs on the
real Postgres schema, returns only order-linked rows, and its floor sum equals the native
`sumAmountByMaterialAndJobOrderAndMinQuality` aggregate), `OwnerScopeServiceTest` (profit-eligibility
count runs once across repeated `canViewJobOrders()`), `UserMapperTest` (one membership lookup per
user within a request, direct-query fallback without one), `MissionServicePayoutTest` (check-in/out/
payout resolve the participant without an aggregate load).

### REQ-DATA-004 — UEX duplicate companies of one brand merge onto a single manufacturer; the sync is per-company resilient

`manufacturer.abbreviation` is a short display code, **not** an identity key, and is therefore
**not UNIQUE** (dropped in `V158`). Identity lives on the UNIQUE `uex_company_id` / `scwiki_uuid`
and the UNIQUE human-canonical `name`.

UEX ships several **distinct** `/companies` records for the **same real-world brand** — different
ids, frequently different names, and the item-side vs. vehicle-side records split a brand's catalogue
across them. Observed in prod (v0.5.9): `87 "Esperia"` carried 43 items while `278 "Esperia
Incorporation"` carried the 7 ships + 15 more items; likewise `70 "Denim Manufacture Corporation"` /
`287 "DMC"` and `62 "Covalex Shipping"` / `293 "Covalex"`. `V158` stopped the original
abbreviation-`UNIQUE` crash by letting each such company keep its own row, but that **split the
brand**: the item sync resolves the manufacturer by `id_company` and the vehicle sync by
`id_company` too, yet the two surfaces reference *different* ids for the same brand, so no single row
keyed on one `uex_company_id` could serve both.

A manufacturer therefore **may own many UEX company ids** via the `manufacturer_uex_company` alias
table (one `uex_company_id` → one `manufacturer`). The item and vehicle syncs resolve the
manufacturer **through that alias table** (ADR-0023), so every id-variant of a brand reunites on one
row.

Consequences that must hold:

- The UEX manufacturer sync upserts **each company in its own `REQUIRES_NEW` transaction** (via the
  `self`-proxy `…WithinTransaction` pattern — see `CLAUDE.md` → Concurrency), so one row that fails
  rolls back only itself and the remaining companies still commit. A bad row may be counted as
  *skipped*; it may never roll back the batch.
- The feed is processed in **ascending `id` order**. The **canonical** company of a brand — the
  lowest `uex_company_id` — owns the row's display identity (`name` / `abbreviation` /
  `uex_company_id`). Every other company of the brand (matched by name or shared abbreviation)
  **merges** into that row: it registers its id in the alias table and only OR-s the
  `is_item_manufacturer` / `is_vehicle_manufacturer` flags; it **never** overwrites the canonical
  identity (this is what keeps the result ping-pong-free across runs). A row still unclaimed
  (`uex_company_id IS NULL`, a legacy hand-seeded / P4K-only row) is adopted as canonical by the
  first matching UEX company.
- Any lookup of a manufacturer by abbreviation must be duplicate-tolerant (deterministic
  `findFirst … ORDER BY created_at`), never a bare `Optional` derived query that would throw
  `IncorrectResultSizeDataAccessException` once a P4K- or hand-seeded row shares a code.
- `V162` is the one-time reconciliation: it creates the alias table, collapses the existing
  duplicate rows onto the lowest-id canonical (repointing the `ship_type` / `game_item` FKs, carrying
  the SC Wiki / P4K links over, OR-ing the flags) and seeds the alias table.

**Acceptance** (`UexManufacturerServiceTest`, `V162MigrationTest`): two companies sharing an
abbreviation merge onto one row with both ids aliased to it and the flags OR'd; the canonical
identity is not hijacked by the duplicate; a single failing company does not abort the rest of the
batch; the abbreviation fallback still adopts a legacy short-named row; the `V162` dedup repoints
child FKs and carries cross-source links onto the surviving canonical row.

### REQ-DATA-005 — the UEX item sync isolates each item in its own transaction

UEX assigns the **same in-game `uuid`** to several distinct item ids (a base item and its
skins/variants — e.g. ids `879`/`5457`/`5458` all carry the MaxLift tractor-beam uuid). `game_item`
keeps `external_uuid` `UNIQUE` (the cross-source join key), so such a row can collide with
`uk_game_item_external_uuid`. The UEX item sync therefore upserts **each item in its own
`REQUIRES_NEW` transaction** (the `self`-proxy `…WithinTransaction` pattern), so a colliding row
rolls back only itself and the rest of the catalogue still commits.

The reason this is mandatory: when the whole `syncItems()` run was a single transaction, the *first*
`external_uuid` violation poisoned the shared Hibernate session, and every subsequent item's
autoflush re-threw the dead insert — the entire run rolled back (observed in prod: 3376 cascade
failures, no `Finished` line, the item catalogue frozen for weeks).

Per-item isolation keeps the catalogue healthy but does not stop a sibling that shares a uuid from
failing *every* run on its own. So the upsert also **declines a colliding backfill**: a row resolved
by `uex_item_id` whose `external_uuid` is still null is given the incoming uuid only when no other
row already owns it; otherwise `external_uuid` stays null, the row keeps its `uex_item_id` key, and
every other UEX column still syncs. Without this guard the loser sibling (e.g. UEX item `4752`, the
"Pulse Greycat Laser Pistol" skin) logged a `uk_game_item_external_uuid` ERROR on each sync and never
updated any of its own columns.

A declined backfill is the **expected, permanent steady state** for a shared-uuid sibling, not a
fault: no admin action can make two rows own one UNIQUE `external_uuid`. It is therefore logged at
`DEBUG` (not `WARN`) so a production run at `INFO` stays clean, and the run instead reports a single
aggregate `sharedUuidDeclined` count on both the `Finished …` summary line and the
`SYNC_RUN_SUMMARY` event — one number per run rather than one warning per sibling per sync (#1205).

**Acceptance** (`UexItemSyncServiceTest`): each item upsert runs through the `self` proxy; the
per-item `catch` keeps the run going past a failure so the remaining items still persist; an incoming
uuid already owned by another row leaves the row's `external_uuid` null instead of throwing; and the
run summary carries the `sharedUuidDeclined` count of such rows.

### REQ-DATA-006 — every hot predicate and foreign key has a covering index

A query predicate on the read path, and every foreign-key column, must be backed by an index;
falling back to a sequential scan on a growing table is a defect. New indexes ship as a Flyway
`V<n>` migration (indexes are access paths only, so they do not affect `ddl-auto=validate`) and
get a spot-check assertion in `DatabaseIndexMigrationTest` (the canary that proves Flyway ran and
produced the DDL). Prefer a **partial index** when the hot set is a small slice of a large table
(status/lifecycle filters), so the index stays proportional to the live set rather than the
history.

`V175` is the round-three backfill (after the V34 / V92 / V122 sweeps) and adds:

- `idx_job_order_assignees_user_id` — the V147 composite `UNIQUE (job_order_id, user_id)` leads
  with `job_order_id` and cannot serve a `user_id`-only lookup.
- `idx_bank_posting_holder_id` — the V153 `(account_id, holder_id)` index leads with `account_id`,
  so the holder-distribution / `holderTotal` aggregate scanned the unbounded append-only ledger.
- `idx_bank_transaction_initiated_by`, `idx_app_user_approved_by_id` — foreign keys with no
  covering index (user-deletion integrity / approver back-references).
- `idx_app_user_pending_approval` — partial `WHERE approval_status = 'PENDING'` on `created_at`,
  matching `findByApprovalStatusOrderByCreatedAtAsc` (filter + oldest-first order, no sort).
- `idx_job_order_active_priority` — partial `WHERE status IN ('OPEN','IN_PROGRESS')` on
  `(priority ASC NULLS LAST, display_id DESC)`, matching `findAllActiveWithMaterials` so the active
  board stays constant-cost as terminal orders accumulate.

**Acceptance** (`DatabaseIndexMigrationTest`): each `V175` index is present in the live Postgres
test schema, and the two partial indexes additionally have their `WHERE` predicate and key ordering
pinned (via `pg_indexes.indexdef`) so a later migration cannot silently narrow the predicate or flip
a sort while keeping the index name.

### REQ-DATA-007 — Slow-changing global catalogues are served from the frontend per-domain caches

A backend list that is **global** (no per-principal variance) and **slow-changing** is fetched through
`BackendApiClient.getCached(...)` — a per-domain Caffeine cache — not a plain `get(...)` on every render.
The canonical case is the **squadron catalogue** (`GET /api/v1/squadrons?size=1000&sort=name,asc`): it is
read on **every** authenticated render by `OrgUnitContextAdvice` (`availableSquadrons()` plus the admin
switcher's `loadAdminOrgUnitCatalogue()`) and by the page controllers that cache the identical catalogue,
so it is fetched at most once per TTL app-wide.

**Type-safe allowlist (FE-CACHE-1).** `getCached` takes a `CachedCatalog` enum constant, **not** a raw
URI string — the `getCached(String, …)` overloads were removed. Each constant pins its exact request URI
and its invalidation domain; the cache key is the constant's `name()`. This makes the unsafe state
**unrepresentable**: a per-principal URI (`/api/v1/users/me`, `/api/v1/me/capabilities`,
`/api/v1/me/active-org-unit`) cannot be cached because no constant names it, and adding one is a
reviewable, spec-gated act. Every `CachedCatalog` is verified global (no `sub` / role /
`X-Active-Org-Unit-Id` / redaction variance). Each constant also declares its **fetch mode**: a
paged catalogue consumed as "the whole list" is marked `Fetch.PAGE_WALK` and assembled complete by
a page walk *inside* `getCached` before the single cache write, so the cache can never serve a
silently truncated catalogue app-wide (REQ-ADMIN-003 in
[`admin-catalog-completeness.md`](admin-catalog-completeness.md), ADR-0103).
`FrontendCacheSplitTest` pins the URIs **and** fetch modes so a refactor cannot silently change a
cache target or re-truncate a walked catalogue.

**Per-domain split (FE-CACHE-2).** Catalogues no longer share one `staticData` cache that any admin
mutation dropped wholesale (a squadron toggle cold-starting the 10 000-row terminal list, the material
lists, …). Each `CacheDomain` (`squadronCatalogue`, `orgUnitCatalogue`, `materialCatalogue`,
`terminalCatalogue`, …) is its own named Caffeine cache, routed by `CatalogCacheResolver`; a mutation
calls `backendApiClient.evict(CacheDomain…)` with the domain(s) it changed, so eviction blast-radius is
one domain, not all. The shared `AdminMissionDataPageController.okOrRelay` AJAX helper and the
settings writer keep the coarse `clearStaticDataCache()` (→ `evictAllCatalogues()`) fallback: where the
changed domain set is not cleanly known at the call site, **over-eviction is always safe, whereas a
wrong narrow evict would strand stale data**. Single-domain admin writers whose changed domain _is_
cleanly known (material, location, terminal, P4K-import apply, …) instead call the precise
`evict(CacheDomain…)`. Every domain cache keeps `recordStats()`, so the
`cache_*` Prometheus meters and monitoring panels light up per domain (REQ-OBS-005/-006). Each domain
carries its **own** `expireAfterWrite` TTL (`CacheDomain.getTtl()`): **6 h** for pure reference
catalogues (materials, locations, ship types, terminals, …) and **2 h** for the org-structure catalogues
(squadrons, org-unit pickers) and settings. The TTL is only a backstop — freshness comes from the
per-mutation evict, so the longer TTLs trade a negligible staleness risk (bounded by the TTL only if an
evict is missed, or by a future >1-replica deployment per CACHE-DIST-01) for far fewer refetches of the
large catalogues.

Invariants that must hold:

- **Cacheability is gated on eviction.** A catalogue may be cached **only** if every admin mutation of
  it evicts its domain cache — via `evict(CacheDomain…)` for the precise domain(s), or the coarse
  `clearStaticDataCache()` fallback where the domain set is not cleanly known — so no user sees a list
  more stale than the last mutation. Squadron lifecycle changes (`AdminMissionDataPageController` coarse;
  `SquadronAdminProxyController` precise `evict(SQUADRON, ORG_UNIT)`) evict, so the squadron catalogue is
  cacheable.
- **The same rule enrols the job-order age thresholds.** `GET /api/v1/settings/job_order.age_yellow_days`
  and `…age_red_days` are global, slow-changing settings read through `getCached` on the orders list and
  detail renders (`JobOrderPageController`). Their sole writer is `AdminSettingsPageController`, which
  evicts `STATIC_DATA_CACHE` on every successful save — on **both** the classic redirect handler and the
  AJAX twin, in a `finally` around the per-setting PUTs so even a **partial save** (an early setting PUT
  lands, a later one throws) still drops the cache rather than stranding the persisted threshold until
  the TTL. Caching them is therefore allowed under the eviction gate above.
- **The SpecialCommand catalogue (`/api/v1/special-commands?…`) is cached** now that every SK lifecycle
  mutation evicts `STATIC_DATA_CACHE`: `AdminSpecialCommandsPageController`'s
  create / update / soft-delete / re-activate (classic **and** AJAX twins) and
  `SpecialCommandAdminProxyController`'s profit-eligible flip all call `clearStaticDataCache()`.
  `OrgUnitContextAdvice`'s admin switcher therefore reads the SK catalogue through `getCached`, at most
  once per TTL app-wide instead of on every admin render. Member-roster mutations (add / remove / flags /
  lead) do not change the catalogue's name / shorthand / active / profit-eligible fields, so they
  deliberately do **not** evict.
- **The active-org-unit owner pickers (`/api/v1/org-units/active`, `/api/v1/org-units/active-all-kinds`)
  are cached.** Both return a **global** catalogue (all active org units, no per-principal / per-active-
  org-unit-header / redaction variance — the endpoints do no scope filtering), so the URI-keyed cache is
  safe. `/active` (Staffel + SK) is read on every mission-detail render (`MissionPageController`) and
  every Job-Order render (`JobOrderPageController.fetchActiveOrgUnitOptions`); `/active-all-kinds`
  (+ Bereich + OL) on every bank-management render (`BankManagePageController`) and the authenticated
  Job-Order requesting picker. Their eviction gate is now complete: Squadron writes
  (`AdminMissionDataPageController` / `SquadronAdminProxyController`), SK writes
  (`AdminSpecialCommandsPageController` / `SpecialCommandAdminProxyController`) **and** Bereich/OL writes
  (`AdminOrgStructurePageController` create-Bereich / create-OL / re-parent) all call
  `clearStaticDataCache()`, so a picker never shows an org unit more stale than the last mutation.
- **The reference catalogues cached for the pickers evict their own domain on every admin write.** Each
  single-purpose admin write path calls the precise `evict(CacheDomain…)` for the one domain it changes,
  so a stale list never survives the mutation: `AdminMaterialsPageController`'s create and field-edit AJAX
  writers evict `MATERIAL` **unconditionally on every `updateType`** — a former `updateType` guard skipped
  `CATEGORY` / `REFINED` / `QUANTITY_TYPE` edits and stranded those lists until the TTL;
  `AdminLocationsPageController`'s visibility / home-location toggles (classic **and** AJAX) evict
  `LOCATION`; `AdminUexPageController`'s terminal-visibility toggle (classic **and** AJAX) evicts
  `TERMINAL`. The async P4K import (`AdminP4kImportPageController.applyJob`) evicts the four domains its
  apply rewrites — `evict(MATERIAL, MANUFACTURER, SHIP_TYPE, ITEM_CATALOG)` — at **apply-enqueue** time:
  the apply runs as an async backend job, so the eviction is deliberately eager; the brief window in which
  a concurrent read could re-cache pre-apply data is bounded by the domain TTL and the action is rare and
  admin-only.
- **Per-principal calls are never URI-cached.** `/api/v1/users/me`, `/api/v1/me/capabilities`, and
  `/api/v1/me/active-org-unit` share a URI across users; a URI-keyed cache would cross-contaminate
  them, so they remain plain `get(...)`.
- **The eviction guarantee holds only under single-instance deployment (CACHE-DIST-01).** Caffeine is
  per-JVM: both `STATIC_DATA_CACHE` and the backend master-data caches evict only the local JVM's copy.
  This "no user sees a list more stale than the last mutation" guarantee is airtight **because prod runs
  exactly one frontend and one backend container** — the fixed `container_name` in `docker-compose.yml`
  structurally forbids `docker compose --scale`, and `deploy.sh` recreates in place rather than scaling
  out. **Scaling either module to more than one replica breaks this**: a peer would serve entries up to
  the TTL stale after another replica's mutation. Horizontal scale-out is therefore gated on first
  replacing the eviction-sensitive caches with a shared / broadcast eviction scheme (preferred: a Redis
  pub/sub evict-broadcast keeping Caffeine as the local read path) — see
  [ADR-0074](../adr/0074-cache-invalidation-per-instance-caffeine.md), deferred and **not** built while
  the topology is single-instance.

**Acceptance** (`OrgUnitContextAdviceTest`): both `availableSquadrons()` and the admin switcher route
the squadron catalogue through `getCached`, never a plain `get`; the admin switcher also routes the
SpecialCommand catalogue through `getCached`. (`AdminSpecialCommandsPageControllerMvcTest`,
`SpecialCommandAdminProxyControllerTest`): every SK lifecycle mutation — create / update / delete /
activate (classic and AJAX) and the profit-eligible flip — evicts `STATIC_DATA_CACHE`, while a
member-roster mutation does not. (`AdminSettingsPageControllerMvcTest`): every successful settings save —
classic and AJAX, including a partial save where a later PUT throws — evicts `STATIC_DATA_CACHE`.
(`AdminMaterialsPageControllerTest`): a material create and a field-edit AJAX write — including a
`QUANTITY_TYPE` edit that the former `updateType` guard skipped — evict `MATERIAL`, while a rejected
create does not. (`AdminLocationsPageControllerTest`): the visibility / home-location toggles evict
`LOCATION`. (`AdminUexPageControllerTest`): the terminal-visibility toggle (classic and AJAX) evicts
`TERMINAL`. (`AdminP4kImportPageControllerTest`): an apply evicts
`MATERIAL, MANUFACTURER, SHIP_TYPE, ITEM_CATALOG`.

### REQ-DATA-008 — User deletion reassigns or clears every `app_user` FK that lacks an `ON DELETE` clause

`UserService.deleteUser(userId)` removes an ex-member (only users already gone from Keycloak,
`in_keycloak = false`). Before the terminating `userRepository.delete(user)`, **every foreign key
referencing `app_user(id)` that carries no `ON DELETE` clause (Postgres default `NO ACTION`) must be
explicitly reassigned or nulled in code** — otherwise the delete FK-fails with `SQLSTATE 23503`.
Foreign keys that declare their own `ON DELETE CASCADE` / `SET NULL` are handled DB-side and need no
code. The reassignment target is a surviving admin (never the user being deleted; resolved from
`findAllAdmins`, falling back to the current admin caller).

Concrete handling, in order:

- **Reassigned to the admin** (mandatory ownership): `inventory_item.user_id`, `ship.owner_id`,
  `refinery_order.owner_id`, `mission.owner_id`, and — paired with the last — the 1:1 companion
  `mission_ownership.owner_id`. The companion (V63, FK-less `owner_id`) **must follow
  `mission.owner`**: the mission survives the delete (its owner moved to the admin), so the
  `ON DELETE CASCADE` on `mission_ownership.mission_id` never fires to clear the row, and a dangling
  `owner_id` would FK-fail. It is reassigned (not nulled / row-deleted) so the companion keeps
  mirroring `mission.owner`; the bulk update must not bump `mission_ownership.version` (the owner
  association is excluded from the parent's optimistic lock).
- **Unlinked / cleared** (reversible or audit-only references): `mission_managers` and
  `job_order_assignees` via the join-table deletes (`removeManager` / `removeAssignee`, also
  `ON DELETE CASCADE`); `mission_participant.user_id` via `unlinkUser` (set null, the participant row
  with its guest name + status survives); `material_claim.claimed_by_user_id` (V131, FK-less,
  audit-only) via `unlinkClaimedByUser` (set null — re-pointing it at the admin would falsely
  attribute the claim, and the claim is a live independent aggregate that must survive).

`mission_finance_entry` carries **no** direct `app_user` FK (the V40 `user_id`/`fk_mfe_user`
`ON DELETE RESTRICT` pair was dropped in V41 in favour of `mission_participant_id`
`ON DELETE CASCADE`); it reaches a user only through `mission_participant.user_id`, already nulled by
`unlinkUser`. The approval self/audit FKs added in V173 (`app_user.approved_by_id`,
`user_approval_event.user_id` / `decided_by_id`) are tracked and resolved by the Discord-approval
work, not here.

**Acceptance**: `UserServiceDeleteTest` (Mockito) verifies the companion reassignment and the
material-claim unlink are invoked, and that both run before `userRepository.delete`;
`UserDeletionForeignKeyIntegrityTest` (real Postgres) deletes a user who owns a mission and stamped a
material claim and asserts no `23503`, the mission + its companion are reassigned to the same admin,
and the claim survives with a null stamp.

**Enforced by:** `UserService.deleteUser` (explicit ordered reassignment),
`MissionOwnershipRepository.updateOwner`, `MaterialClaimRepository.unlinkClaimedByUser`,
`db/migration/V63` (companion table without auto-cascade on `owner_id`).

### REQ-DATA-009 — A global statement-execution timeout bounds every query (finding SEC-03)

Every Hibernate-issued query — JPQL, Spring-Data derived, `@Query` (including `nativeQuery`),
`@Modifying` bulk updates, and pessimistic-lock waits — is bounded by a global execution timeout
(`spring.jpa.properties.jakarta.persistence.query.timeout`, default **30 s**, overridable via
`APP_DB_QUERY_TIMEOUT_MS`). Without it a single heavy request can hold one of the Hikari pool's
connections for the query's full duration — the Hikari timeouts only bound pool *acquisition*, not
query *execution* — so a handful of concurrent heavy reads can exhaust the pool and stall the API
(SEC-03). This is the complement to the deliberately high `PaginationUtil` page ceiling
(`MAX_PAGE_SIZE = 100_000`): the ceiling stays high because several surfaces legitimately "load all"
in one request (the material × terminal price matrix `/api/v1/materials/matrix?size=100000`, the
admin material / member / UEX lists, the org-unit pickers), so the *time* a fetch may run — not the
*number of rows* it may ask for — is the enforceable bound.

The timeout is a **Hibernate** property, so it applies only to application queries: **Flyway
migrations use raw JDBC and are unaffected**, and a long migration is never killed. The value is
generous so legitimate load-all reads and batch syncs never trip it; environments with deliberately
long batch queries raise it via the env override.

**Acceptance**

- [x] A query that runs longer than the configured timeout is cancelled and surfaces as a
  `jakarta.persistence.QueryTimeoutException` rather than pinning the connection until it completes.
- [x] Flyway migrations are not subject to the timeout.

**Enforced by:** `QueryTimeoutConfigTest` (real Postgres `pg_sleep` exceeding a short configured
timeout) · **Code:** `spring.jpa.properties.jakarta.persistence.query.timeout` in
`backend/src/main/resources/application.yml`, `PaginationUtil.MAX_PAGE_SIZE` · **Security:** SEC-03

### REQ-DATA-010 — Shared repository abstractions for verbatim-duplicated query shapes (S5, #911)

Two query shapes were hand-copied across the repository layer rather than shared: the
case-insensitive-unique `name` exists-pair (16 declarations across 8 lookup-table repositories) and
the org-unit scope-predicate triple (the `:isAdminAllScope` / `:activeOrgUnitId` /
`:memberOrgUnitIds` clause from REQ-ORG-002, copy-pasted across the 6 aggregate repositories that
enforce it — Operation, Mission, Ship, RefineryOrder, InventoryItem, JobOrder). Both are now
centralised:

- **`repository.LookupTableRepository<T, IdT>`** (`@NoRepositoryBean`) declares
  `existsByNameIgnoreCase(String)` / `existsByNameIgnoreCaseAndIdNot(String, IdT)`; the 8 repositories
  whose exists-pair return type never varies (`StarSystem`, `ShipType`, `SpecialCommand`, `Squadron`,
  `Location`, `Manufacturer`, `JobType`, `Bereich`) extend it instead of `JpaRepository` directly and
  drop their own copies — Spring Data resolves the inherited derived query per concrete entity, so the
  generated SQL is unaffected. `findByNameIgnoreCase` stays declared per-repository (not lifted):
  its return type diverges (`Optional<T>` for a unique name, `List<GameItem>` for
  `GameItemRepository`, whose `name` is not unique), and a base interface method can only declare one
  return type.
- **`repository.ScopeSpecifications`** holds one `static final String` JPQL fragment per aggregate
  (`OPERATION_SCOPE_PREDICATE`, `MISSION_SCOPE_PREDICATE`, `SHIP_SCOPE_TRIPLE`,
  `REFINERY_ORDER_SCOPE_TRIPLE`, `INVENTORY_ITEM_SCOPE_TRIPLE`, `JOB_ORDER_SCOPE_PREDICATE`),
  referenced from each repository's `@Query` value via compile-time constant-expression concatenation
  (JLS 15.28/4.12.4 — the same technique REQ-SEC-002a's `Roles.X` constants use inside
  `@PreAuthorize`, since an annotation value cannot invoke a method). The alias is baked into each
  constant (`o`/`m`/`s`/`r`/`i`) rather than parameterized — a compile-time constant cannot accept a
  runtime substitution — so this is a handful of named per-aggregate constants, not one generic
  template. The three aggregates with an escape tail beyond the plain triple (Operation's
  ownerless-leadership + mission-participant escapes, Mission's cross-staffel public escape, JobOrder's
  SK-public-queue escape — REQ-ORG-003) carry the tail baked into their own constant; the three
  strict-staffel aggregates (Ship, RefineryOrder, InventoryItem) use the plain triple unchanged.

Both are **read-only, wire-format-preserving refactors**: every migrated `@Query` value is
byte-for-byte the same JPQL the compiler previously inlined, so the multi-tenant scope semantics of
REQ-ORG-002/003 are unchanged. Neither introduces `JpaSpecificationExecutor` — every scoped query in
this codebase is a hand-written `@Query`, not a `Specification`, and there was no behavioural reason
to switch.

**Acceptance**: `LookupTableRepository`'s exists-pair is exercised by every existing
`*RepositoryTest`/service test that already covered the 8 migrated repositories' duplicate-name
checks; `ScopeSpecifications`' fragments are exercised by the existing scope-regression suite
(`RefineryOrderRepositoryScopedTest` against the real Postgres schema, plus the Mockito-level
`OwnerScopeServiceTest` and the per-aggregate service lifecycle tests) — no new tests were needed
because the wire behaviour is unchanged.

**Code:** `backend/src/main/java/.../backend/repository/LookupTableRepository.java`,
`backend/src/main/java/.../backend/repository/ScopeSpecifications.java`

### REQ-DATA-011 — Background sync sweeps evict the master-data caches they rewrite (CACHE-SYNC-EVICT-001)

The backend serves quasi-static reference data from per-cache Caffeine caches (backend `CacheConfig`,
master-data TTL 12 h). The admin CRUD services evict their own cache on every user-facing write, but
three background writers bulk-write directly to the repositories behind those caches — bypassing the
`@CacheEvict` hooks: the periodic **UEX** (`UexScheduler`) and **SC Wiki** (`ScWikiScheduler`) sync
sweeps, and the async **P4K catalog apply** (`P4kImportJobRunner`, off the request thread on the import
executor). Before this rule a freshly-synced material / manufacturer / ship-type / star-system / city /
location / terminal / POI / outpost / refining-method / the blueprint variant-family index stayed
invisible for up to the TTL — a window the deliberately long master-data TTL makes large, which is
exactly why these writers must **evict** rather than rely on the TTL.

Each writer therefore evicts the caches it can make stale **on completion**, through
`MasterDataCacheEvictionService`:

- The eviction runs in a `finally` around the work, so every step that **did** commit is reconciled
  even when a later step aborts (UEX is fail-fast; SC Wiki swallows per step). The P4K apply gates its
  eviction on the reconcile transaction having actually committed (`appliedMasterData`), so a
  rolled-back apply and every PREVIEW run correctly evict nothing.
- The three per-writer cache-name sets (`UEX_SYNCED_CACHES`, `SCWIKI_SYNCED_CACHES`, `P4K_SYNCED_CACHES`)
  are the **single source of truth** for "this writer can make these stale". **A new cache added over a
  written table MUST be added to the matching set(s)**, or it silently lags the TTL again after a
  sync / apply. The 12 h TTL is only the backstop, never the primary freshness mechanism for synced
  data. (The UEX set covers the universe catalogues it rewrites — cities, star systems, locations,
  terminals, POIs, outposts — alongside the commodity / manufacturer / ship-type / refining-method
  caches; the P4K set covers the material / manufacturer / ship-type / blueprint-family caches its
  apply reconciles.)
- This is also the sole scheduler evict hook for `BLUEPRINT_FAMILY_INDEX_CACHE`, which has no per-write
  hook of its own (CACHE-DIST-03); it is rebuilt by the SC Wiki blueprint sync and the P4K blueprint
  apply, so evicting it on their completion is both necessary and sufficient.

Related, the material caches are split so a paged-list read and a single-entity read cannot evict each
other under a shared size budget (**CACHE-02**): `getMaterial(id)` caches in `MATERIAL_BY_ID_CACHE`
while the list reads stay in `MATERIALS_CACHE`; **every material write and every sync sweep evicts
both**, so a single-entity entry can never survive a rename/delete while the list entry is dropped.

**Acceptance**: `MasterDataCacheEvictionServiceTest` (each writer clears exactly its cache set; a missing
cache is tolerated; the three sets are pinned so a written-table cache cannot be silently dropped);
`UexSchedulerTest` / `ScWikiSchedulerTest` (the sweep evicts on completion **and** after a mid-sweep
failure, and does not evict when the cross-scheduler gate skips the sweep); `P4kImportJobRunnerTest`
(a committed APPLY evicts the P4K set, a PREVIEW and a rolled-back APPLY evict nothing);
`MaterialServiceCachingTest` (`getMaterial` populates only the by-id cache, the list reads only the list
cache, and a write evicts both); `TerminalServiceCachingTest` / `PoiServiceCachingTest` /
`OutpostServiceCachingTest` (each read populates its cache and every override mutator evicts it).

**Enforced by:** `MasterDataCacheEvictionService`, `UexScheduler.runAllSyncSteps`,
`ScWikiScheduler.runAllSyncSteps`, `P4kImportJobRunner.run` (APPLY-committed evict),
`MaterialService` / `TerminalService` / `PoiService` / `OutpostService` (`@Cacheable` reads + `@CacheEvict`
mutators).

### REQ-DATA-012 — Operation-detail + job-order read-shape hardening (operation-side ADR-0078, #1109)

The operation-detail page (`/operations/{id}`) is the operation-aggregate mirror of the mission-page
scale hardening (ADR-0078, REQ-DATA-003): under many concurrent viewers it must not scan and
materialize an operation's whole finance / refinery ledger under a held Hikari connection.

- **Finance roll-up via a grouped SQL aggregate, not a ledger load-all.** The operation-detail
  overview (the "Ergebnis je Einsatz" bars + the Gesamtergebnis) reads `GET
  /api/v1/operations/{id}/finance-summary` — one grouped finance aggregate
  (`aggregateFinanceByMissionIds`) plus one grouped refinery-profit aggregate
  (`aggregateProfitByMissionIds`), per-mission `SUM`med in the DB — instead of `findAllByMissionIdIn`
  over every child mission. The per-mission breakdown is capped (`MAX_FINANCE_SUMMARY_MISSIONS = 500`,
  `truncated` flag surfaced in the UI — never a silent cap). The full-detail endpoint `GET
  .../finances` (with the embedded per-entry lists) survives for API consumers but is off the render
  path.
- **Per-mission entry detail loads lazily.** Each finance-tab `<details>` fetches its own mission's
  entry / refinery breakdown on first expand via `GET /api/v1/operations/{id}/finances/{missionId}`
  (operation-scoped `canSeeOperation` authz), so the initial render never materializes every entry.
- **Parallel + fragment-gated operation reads.** The four independent operation-detail reads
  (operation, missions page, finance-summary, payouts) run concurrently via `ParallelPageLoader`; the
  `fragment=missions` pager swap fetches neither finance-summary nor payouts (the regression fence is a
  MockMvc `never()` guard, mirroring ADR-0078).
- **Payout toggle stays O(1).** `PUT .../payouts/paid-out` returns only the participant's paid-out
  status block (`OperationPayoutStatusDto`) and validates the key against the bounded participant
  graph — it never re-runs the full per-participant payout computation (double ledger load-all + money
  math) just to hand back one row.
- **Operation-picker reference lookup is status / recency-bounded.**
  `OperationRepository.findAllReferenceScoped` returns PLANNED / ACTIVE always but COMPLETED / CANCELED
  only within the last 3 months (by `createdAt`), and the mission-detail controller fetches it only on
  a full render (not on fragment refetches) — the operation analogue of the mission
  `findAllActiveReference` bound.
- **Job-order detail gates its uncached users read.** `JobOrderPageController.viewOrderDetail` fetches
  `/api/v1/users?size=1000` only on the full page (the assignee picker lives in the full-page-only
  `assigneesSection`), not on any in-place section swap — pre-empting the same read-amplification once
  order live-sync lands.

**Acceptance**: `OperationFinanceServiceTest` (grouped per-mission totals; lazy per-mission detail;
404 for a foreign mission); `MissionFinanceEntryRepositoryIntegrationTest` (the grouped finance /
refinery / picker JPQL execute against real Postgres); `OperationPayoutServiceTest` (the payout
toggle validates the key via the participant graph and returns the slim status DTO without
re-computing);
`OperationPageControllerMvcTest` (the missions fragment issues no finance-summary / payout read; the
per-mission finance fragment renders the breakdown and an error state).

**Enforced by:** `OperationFinanceService.getOperationFinanceSummary` / `getMissionFinanceDetail`,
`OperationController` (`/finance-summary`, `/finances/{missionId}`), `OperationPageController`
(`ParallelPageLoader` + lazy `operationMissionFinance`), `OperationPayoutService.setPayoutStatus`
(`OperationPayoutStatusDto`), `OperationRepository.findAllReferenceScoped` (status / recency bound),
`JobOrderPageController.viewOrderDetail` (users gated to the full render). See ADR-0081.

## Out of scope

**Material-amount SCU-scale storage and rounding** (the `@PrePersist`/`@PreUpdate` HALF_UP-to-three-
decimals rule on the amount entities, plus the `> 0` / `PIECE`-integer validation) lives in its own
spec — [`inv-material-quantities.md`](inv-material-quantities.md) (REQ-INV-003) — which owns the
SCU/PIECE quantity rules end-to-end. It is a persistence-boundary rule, but kept with its sibling
input rules for one place to look.

Optimistic/pessimistic locking and the `…WithinTransaction` patterns — documented inline in
`CLAUDE.md` (see the concurrency note above).
