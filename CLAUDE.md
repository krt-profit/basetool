# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Profit Basetool — a squadron-management web app (mission planning, hangar, inventory, refinery, user admin) for the "DAS KARTELL" / IRIDIUM organization. Two Spring Boot 4 modules (`backend`, `frontend`) on Java 25, PostgreSQL 18, Keycloak 26 OAuth2, Redis-backed Spring Sessions. Gradle 9 with Kotlin DSL. Dependencies are managed by [refreshVersions](https://jmfayard.github.io/refreshVersions/) — **edit `versions.properties`, not `build.gradle.kts`**. Run `./gradlew refreshVersions` to discover updates.

## Requirements, specs & decisions (binding)

Durable requirements are **first-class and binding** — they live as docs-as-code, not in
this file: canonical specs in [`docs/specs/`](docs/specs/INDEX.md) (registry + conventions
in its [`INDEX.md`](docs/specs/INDEX.md)), and architecture/design decisions in
[`docs/adr/`](docs/adr/README.md). The role matrix stays in
[`ROLES_AND_PERMISSIONS.md`](ROLES_AND_PERMISSIONS.md).

- **Every change to the project updates the requirements in the same PR** — add a new
  `REQ-<AREA>-NNN` or adapt the existing one(s) it touches. Code and spec move together; a
  behaviour change with no matching spec change is incomplete.
- **Every change to an audited area keeps its audit log in sync** — the audited areas (Bank,
  Lager, Aufträge, Raffinerie, Mein Inventar, Missionen, Operationen, Rollen, Beförderung) log
  **every** state-mutating
  activity (REQ-AUDIT-001, [`docs/specs/audit.md`](docs/specs/audit.md)). When you add, change or
  remove such an activity, adapt its audit logging in the **same PR**: add or adjust the
  `AuditEventType` and the `auditService.record(...)` call (honouring the optimistic-locking
  landmines below and the "no user free text / no PII in the details payload" rule), extend the
  unified viewer's per-area event-type filter and the DE/EN i18n labels, and reconcile the
  REQ-AUDIT-001 coverage list. A new mutation in an audited area with no matching audit event is
  incomplete.
- **Every change to the tool keeps the monitoring in sync.** Whenever you add, change, or remove a
  feature, endpoint, scheduled job, status enum, security gate, or external integration, evaluate the
  monitoring impact **in the same PR**: extend or adjust the business metrics (`basetool_*`, naming +
  bounded-label rules per REQ-OBS-011), the Prometheus alert rules and Grafana dashboards under
  [`monitoring/`](monitoring/), the Alloy/Loki pipelines when a new log stream or format appears, the
  blackbox targets when a new public endpoint ships, and the tracing instrumentation for new outbound
  calls. A new scheduled job without task metrics, a new audited area without its audit-event counter,
  a new status enum without its queue gauge, a renamed/removed metric that breaks a dashboard or alert
  rule, or a new public surface without a probe is **incomplete**
  ([`docs/specs/observability.md`](docs/specs/observability.md), REQ-OBS-005…011).
- **Every architecturally significant or design decision is recorded as an ADR**
  ([`docs/adr/README.md`](docs/adr/README.md)) before or with the change that implements it.
- **README, the role matrix and the user wiki move with the change.** Whenever a change affects
  user-facing behaviour, architecture, env vars, the access/permission model, or any feature the
  handbook documents, keep these in sync **as part of the same unit of work**: the
  [`README.md`](README.md) overview, the [`ROLES_AND_PERMISSIONS.md`](ROLES_AND_PERMISSIONS.md)
  role/permission matrix, and the **German end-user wiki** (the separate `basetool.wiki` repo —
  one page per feature area: Kartellbank, Benachrichtigungen, Rollen-und-Berechtigungen, …). The
  README and the role matrix ship in the **same PR** as the code; the wiki, being a separate git
  repo, is committed and pushed there alongside (German content, English commit messages — see the
  Git section's wiki carve-out). A change that touches one of these surfaces but leaves the README,
  the role matrix, or the relevant wiki page stale is **incomplete**.
- **Requirements must always be honoured.** Code must not silently contradict a
  requirement. If a change *must* violate or override one, it needs **prior approval by the
  repository owner (@greluc)** AND the requirement must be **amended first** — never diverge
  from a spec and leave it stale. When in doubt, stop and ask.
- **Plan documents** (`docs/*_PLAN.md`, `docs/DESIGN_*.md`) carry a `Doc type:` header
  marking them *living spec* or *historical plan*; freeze a plan and point it at the living
  truth once it ships.

## Frontend / UI & design system

The UI is a **binding requirement**: follow the DAS KARTELL design system. The rules —
brand colours, Lato-only typography (headlines = Lato Bold + uppercase), the authoritative department colours, the
square-first sci-fi HUD style, "no native browser dialogs", and the four responsive device
classes — live in [`docs/specs/ui-design-system.md`](docs/specs/ui-design-system.md). The
visual source of truth is the design skill at
[`.claude/skills/das-kartell-design/README.md`](.claude/skills/das-kartell-design/README.md)
(README.md = Quelle der Wahrheit für Farben, Typografie, Komponenten).

**Live update is a binding requirement: every part of the frontend must support live update to
the current standard.** Every create / update / delete / toggle / reorder / filter / paginate
interaction updates the DOM **in place** through the shared `krtFetch` / `krtCsrf` / fragment-swap
foundation — **no full-page reload on success** (the only two sanctioned reloads are the
optimistic-lock conflict confirm and the bfcache history-restore of `REQ-FE-008`) — derived UI
outside the swapped fragment is refreshed too, and on any surface where several users can see the
same state a peer's change propagates to the others without a manual reload. The current standard,
its full `krtFetch`/fragment-swap contract and the live multi-user sync live in
[`docs/specs/frontend-ajax-mutations.md`](docs/specs/frontend-ajax-mutations.md)
(`REQ-FE-001…010`, ADR-0012/0013/0031). A new or changed frontend surface that reloads the page on
success, leaves a sibling/peer view stale, or hand-rolls a `fetch`/CSRF write outside `krtFetch` is
incomplete — extend the standard to cover it, don't fall back to a reload.

**Live update and multi-user sync move with every feature — added, changed *or* removed.** Whenever
you add, change or remove a frontend surface that participates in live update or live multi-user
sync (a new editable section, a renamed/retired one, a new mutation on an existing section), you
**must** update its live-update and peer-sync wiring in the **same change** — never defer it to a
follow-up. For the multi-user sync in particular, a section key must stay consistent across **all**
its mirror points at once: the acting client's broadcast (the page's section/seam map), the server
relay's accept-list (`BROADCASTABLE_SECTIONS`), and the receiving client's apply map. A key present
in one but missing from another **silently** leaves other viewers stale with no error — the
REQ-FE-010 defect that shipped when `objectives`/`frequencies` were added to the write seam but not
the receiver/relay. Prefer deriving these maps from a single source of truth so they cannot diverge;
where they can't share one, changing one **requires** changing the others in the same PR, and the
change is incomplete otherwise.

## Build, run, test

Always use the Gradle wrapper. **Never** use the IDE test runner or the harness `run_test` tool — Gradle is the only sanctioned test path. This is a hard project rule and applies even when iterating on a single test.

```bash
./gradlew :backend:test                                    # backend tests
./gradlew :frontend:test                                   # frontend tests (also produces JaCoCo report)
./gradlew test                                             # all tests
./gradlew :backend:test --tests "FullyQualifiedClassName"  # single test class
./gradlew :backend:test --tests "ClassName.methodName"     # single test method
./gradlew :backend:bootRun                                 # backend on https://localhost:11261 (dev profile)
./gradlew :frontend:bootRun                                # frontend on http://localhost:18081 (dev profile)
./gradlew :backend:cyclonedxBom                            # SBOM into backend/docs/
./gradlew :frontend:cyclonedxBom                           # SBOM into frontend/docs/
./gradlew check                                            # full static analysis: Checkstyle (Google Java Style) + SpotBugs + tests
./gradlew :backend:checkstyleMain :backend:spotbugsMain    # backend lint only
./gradlew :frontend:checkstyleMain :frontend:spotbugsMain  # frontend lint only
```

Tests force `spring.profiles.active=test`; `bootRun` forces `dev`. Both `Test` and `BootRun` set `--enable-native-access=ALL-UNNAMED` and a Mockito agent JVM arg.

## Linting / static analysis

- **Checkstyle** (Google Java Style, `config/checkstyle/google_checks.xml`) and **SpotBugs** (`spotbugsMain`, wired into `check`) run against the `main` source set of both modules. Reports land under `<module>/build/reports/{checkstyle,spotbugs}/main.{html,xml}`.
- **Every new or modified piece of code must be linted before the task is considered done.** Run at least `./gradlew :<module>:checkstyleMain :<module>:spotbugsMain` (or `./gradlew check` for the full sweep) and read the reports.
- **All Checkstyle and SpotBugs errors *and* warnings introduced or touched by your change must be fixed.** Do not silence findings with `@SuppressWarnings`, `@SuppressFBWarnings`, or Checkstyle suppression files unless the rule is genuinely wrong for that specific call site — and in that case leave a one-line comment explaining why.
- Pre-existing findings in code you did not touch are out of scope; do not opportunistically clean them up in an unrelated change. But never *add* a new finding on top of them.
- **Run `./gradlew spotlessApply` (whole repo) locally before *every* push — no exceptions, even for a one-line test or comment edit.** It formats **all** source sets (incl. `e2e`) and the `.properties` / Markdown / Gradle files; running a narrower task (`:<module>:checkstyleMain`, `compileE2eJava`, `checkstyleE2e`, …) is **not** a substitute and will let a formatting violation slip through to CI (e.g. an over-long Javadoc line in an `e2e` test that `checkstyleE2e` does not catch). Spotless is wired into `check` via `isEnforceCheck = true`, and Checkstyle runs with `isIgnoreFailures = false` + `maxWarnings = 0` — any unformatted file or new Checkstyle warning fails CI immediately.
- **ALL lint tasks must be green locally before *every* push — no exceptions.** Formatting (`spotlessApply` + `:frontend:prettierApply`) is necessary but **not sufficient**: the frontend also runs three *strict* asset linters that fail CI independently and are **not** covered by Spotless/Prettier/Checkstyle — **`:frontend:lintCss`** (Stylelint: e.g. media-query *range* notation `(width <= Npx)` not `(max-width: Npx)`, and modern `rgb(r g b / a%)` not `rgba(...)`), **`:frontend:lintJs`** (ESLint: `no-var` → use `let`/`const`, unused caught errors must be `_`-prefixed, etc.), and **`:frontend:lintHtml`** (HTMLHint). Before pushing any change that touches `src/main/resources/static/**` (CSS/JS) or `templates/**`, run — and get to **zero findings** — the full local gate for both modules: `./gradlew :backend:check :frontend:lintCss :frontend:lintJs :frontend:lintHtml :frontend:prettierCheck` (or the whole `./gradlew check`, which wires them all in). Stylelint/ESLint auto-fix most findings — the Gradle Node plugin's private Node lives under `frontend/.gradle/nodejs/…/node.exe`; put it on `PATH` and run `node_modules/.bin/stylelint --fix <file.css>` / `node_modules/.bin/eslint --fix <file.js>`, then re-run the Gradle lint task to confirm. Never push relying only on the tests + Spotless being green.

## Local stack

Use Docker Compose profiles:

```bash
docker compose --profile dev up -d db-backend-dev db-keycloak-dev keycloak-dev redis-dev   # deps only, run apps locally
docker compose --profile dev up -d                                                          # full dev stack with host port exposure
docker compose --profile prod up -d                                                         # prod-equivalent stack behind nginx-proxy-manager
docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml \
    --profile dev up -d                                                                     # isolated test stack with throwaway credentials
```

Host ports (dev profile only): backend `11261`, frontend `18081`, Keycloak `18080`, backend DB `15432`, Keycloak DB `15433`, Redis `6379`, NPM admin `10081`. A `.env` at repo root is required for the regular dev/prod profiles (see README for keys). The isolated test stack instead reads `.env.test` plus a locally generated `keystore.p12` and a stripped `realm-export.json` — see the README's `Running the Local Test Stack` section for setup, and never substitute production artifacts for those.

The backend serves HTTPS with a self-signed cert (`keystore.p12`, password `changeit`); the frontend talks to `https://backend:11261` in prod and `http://localhost:11261` (overridable via `BACKEND_URL`) in dev. There is no Swagger UI — the OpenAPI document is served at `https://localhost:11261/v3/api-docs` in the `dev`/`test` profiles only (disabled in `prod`); the committed `backend/src/main/resources/api/openapi.json` is the single API-documentation artifact.

## Architecture

### Module split

- **`backend`** — REST API only. Layered: `controller` → `service` → `repository` → `model` (JPA entities), with `dto` records, MapStruct `mapper`s, `config` (security, caching, OpenAPI, rate limiting, WebClient), `integration` (UEX external API), `task` (scheduled jobs), `filter`/`interceptor` (correlation ID, deprecation headers), `annotation` (`@ApiDeprecation`).
- **`frontend`** — Thymeleaf server-rendered UI that calls the backend via WebClient. No business logic of its own; `service.BackendApiClient` is the single seam. Persistent state across frontend restarts goes in Redis (Spring Session).

The frontend never talks to PostgreSQL or Keycloak Admin API directly. The backend never serves HTML.

### Security & access control

Moved to [`docs/specs/security-and-access.md`](docs/specs/security-and-access.md) (`REQ-SEC-*`): Keycloak OIDC topology (backend resource server, frontend OAuth2 client), `@PreAuthorize`-centralised authorization, the ArchUnit-enforced invariants ([`ArchitectureTest`](backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java)), the role hierarchy ([`ROLES_AND_PERMISSIONS.md`](ROLES_AND_PERMISSIONS.md)), contextual LOGISTICIAN/MISSION_MANAGER + SK-lead grants, per-`sub` multi-user data isolation, and guest field redaction.

### Multi-org-unit tenancy (CRITICAL)

Moved to [`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md) (`REQ-ORG-*`): the two OrgUnit kinds (`SQUADRON` / `SPECIAL_COMMAND`) + dual-write soak, service-layer scope via [`OwnerScopeService`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/OwnerScopeService.java) (the `ScopePredicate` triple + admin-pin semantics), the aggregate scope kinds (strict-staffel / `Mission` public-escape / `JobOrder` SK-public queue), the create-time stamping matrix, the admin-area + promotion carve-outs, the ArchUnit guards, the `orgUnitId` MDC field, and the active-context relay headers.

### Database

Moved to [`docs/specs/data-persistence.md`](docs/specs/data-persistence.md) (`REQ-DATA-*`): Flyway owns the schema (`V<n>__*.sql`, `ddl-auto = validate` everywhere — conventions in [`db/migration/README.md`](backend/src/main/resources/db/migration/README.md)), `DataInitializer` seeding, and the no-N+1 rule. **The concurrency / optimistic-locking rules stay inline below — they are agent-critical.**

### Concurrency — read this before touching multi-step transactions

The codebase has been bitten by optimistic-locking traps several times. The rules below exist because of real bugs that shipped.

- **Optimistic locking via `@Version`** — every write DTO carries the `version` field; the frontend echoes it back; concurrent modifications surface as `ObjectOptimisticLockingFailureException` → HTTP 409. Don't strip the version from DTOs to "make it simpler." The version-mismatch **check** goes through the `support.OptimisticLock` helper family (S2, #908) — `check` (skip when the persisted version is null, else 409 unless equal), `checkOptionalClient` (also skip when the client omits the version — admin force-save), `checkRequired` (an absent persisted version is itself a 409) — rather than a hand-rolled `if (…) throw new ObjectOptimisticLockingFailureException(…)`. Pick the method by the site's null-semantics; never re-derive the guard inline. **Exception:** `Mission`'s manual `coreVersion`/`scheduleVersion`/`flagsVersion`/`partyLeadVersion`/`stepsVersion`/`objectivesVersion`/`owningOrgUnitVersion` counters are plain business `Long`s (not JPA `@Version`); since #1112/#1114/#1147 each section's check-and-bump is a **single DB-enforced atomic conditional** `UPDATE Mission … SET xVersion = xVersion + 1 WHERE id = ? AND xVersion = ?` (`MissionRepository.bump*VersionIfMatches`, dispatched by the private `MissionSection` enum) driven through `MissionSectionVersions.enforceSectionVersion(...)` — 0 rows affected → 409 — which row-locks the mission so two racing same-section writers actually serialise (the earlier in-memory `assertSectionVersion` check-then-bump had a TOCTOU window that let both commit). This is only safe because **every mutable `Mission` scalar/association is `@OptimisticLock(excluded = true)` and the entity is `@DynamicUpdate`**: a section edit dirties only its own columns, so it never bumps the row `@Version` (no cross-section 409) and the column-narrowed flush never clobbers a concurrent other-section change. The in-memory `bumpSectionVersion` survives only for the two unconditional cross-section pokes with no client echo — the legacy full-replace `updateMission` (which force-increments the row `@Version` via `OPTIMISTIC_FORCE_INCREMENT`, its remaining guard once the scalars are excluded) and the activation auto-stamp of `actualStartTime`. Steps/objectives additionally carry a deferrable unique `(mission_id, order_index)` DB backstop (V208). Keep the deliberate `null → 0L` semantics; do **not** route these through the `support.OptimisticLock` family, and do **not** re-expand `enforceSectionVersion` into per-section helpers.
- **Lock as fine-grained as the data allows.** Every part of the frontend must be locked as narrowly as possible: an edit to one part of a screen must **not** 409 a concurrent edit to an unrelated part. Prefer the smallest optimistic-lock scope an aggregate's parts can carry — split a large aggregate's single coarse lock into **independent per-section version counters**, each bumped and echoed on its own, so editing one section never collides with a concurrent edit of another. The canonical precedent is `Mission`'s manual `coreVersion` / `scheduleVersion` / `flagsVersion` / `partyLeadVersion` / … counters — plain business `Long`s independent of the row's Hibernate `@Version`, **DB-enforced** via an atomic conditional bump (see the Exception above) and decoupled at the row level by `@DynamicUpdate` plus per-scalar `@OptimisticLock(excluded = true)`, so an edit to one section never bumps `@Version` and never 409s a concurrent edit of another (note: these manual counters are NOT `@Version`, so the `saveAndFlush` writeback caveat of `REQ-FE-003` does **not** apply to them). Each form / fragment should write the smallest entity that owns the data it touches rather than re-saving the whole aggregate. A coarse, screen-wide lock that forces unrelated concurrent edits to collide is a defect, not a simplification.
- **Frontend DOM version sync** — when an entity is updated via AJAX (dropdown change, row reorder, etc.), the new `version` must propagate to **every** related DOM element in the same context (edit/action buttons, modals inside the same `<tr>` or container). A missed `data-version` attribute → 409 on the user's next click. If targeted updates are too tangled, just `window.location.reload()` on success.
- **Pessimistic locking for bulk reorders** — use `@Lock(LockModeType.PESSIMISTIC_WRITE)` (or atomic SQL) for priority shifts and reorder operations to avoid races.
- **Intra-transaction service calls — `…WithinTransaction` pattern.** When a `@Transactional` service method modifies an entity (directly or via cascaded `repository.save()`) and then calls another service that operates on the **same entity**, the inner method's own `findById()` + `save()` + `flush()` will collide with the already-incremented `@Version` field, causing 409. Fix: expose a dedicated `completeSomethingWithinTransaction(Entity entity)` method annotated `@Transactional(propagation = MANDATORY)` that operates on the already-managed entity and relies on dirty-checking — no `save()`/`flush()` of its own. Canonical example: `JobOrderService.completeJobOrderWithinTransaction()`. Apply this consistently to handover, booking, transfer, and any similar flow.
- **Bulk updates inside loops.** A `@Modifying` repository query with `clearAutomatically = true` (e.g. `unlinkJobOrderMaterial`) detaches the **entire** persistence context — including all sibling entities of the aggregate currently being processed. NEVER execute such a bulk update inside a loop that mutates more than one item of the same aggregate (e.g. multiple `JobOrderMaterial`s of the same `JobOrder`): subsequent iterations will operate on detached entities, and any `repository.save(entity)` call on a detached entity silently does `EntityManager.merge()`, producing a second `@Version` bump on rows already updated in the same transaction → 409. The fix (extension of the `*WithinTransaction` pattern, see `JobOrderHandoverService.createHandover()`):
  1. Inside the loop, mutate only managed entities and rely on Hibernate dirty-checking — do NOT call `repository.save(child)` explicitly.
  2. Collect the IDs of items that need a clearing bulk update in a `Set<UUID>` and run those bulk updates exactly once **after** the loop AND **after** persisting any new aggregate root.
  3. If the completion check needs the freshly persisted state, re-fetch the aggregate root once via `findById(id)` to get a managed instance with up-to-date `@Version`, then hand it to the dedicated `…WithinTransaction(...)` method.
     Apply this rule to every bulk-update + multi-item flow (handover, booking, refinery, transfer).
- **Find-or-create races on a "last-writer-wins" toggle — retry in a *fresh* transaction, never in place.** A find-or-create-then-save on a row carrying a unique constraint AND a `@Version` (e.g. `OperationPayoutStatus` on `(operation_id, participant_key)`) is not idempotent under concurrency: two callers both INSERT (second violates the constraint → `DataIntegrityViolationException`) or both load `version=N` and the second flush matches 0 rows (`ObjectOptimisticLockingFailureException`). Catching either **inside the same `@Transactional` method and continuing is impossible** — Postgres marks the transaction aborted, so every subsequent statement fails. The fix (canonical example: `OperationService.setPayoutStatus` / `setPayoutStatusWithinTransaction`, #1111): make the public method a **non-transactional orchestrator** that retries a `@Transactional(propagation = REQUIRES_NEW)` inner method through a self-proxy (`ObjectProvider<Self>`), catching both exceptions, up to a small bounded attempt count — by the retry the winner has committed the row, so the loser reloads and UPDATEs in place and last-writer-wins actually holds. Do NOT add a client `version` to a boolean toggle; the retry, not a version echo, is what makes it idempotent. Let a persistent race propagate after the bound so the proxy still maps it to a truthful 409, never a 500 (the frontend proxy MUST have an explicit 409 branch). The same pattern also guards `MaterialClaimService.upsertClaim` / `upsertClaimWithinTransaction` (the squadron material-claim sign-up on the `@Version`ed, uniquely-indexed `material_claim` bucket row — `uq_material_claim_bucket_org_unit`, V131). One contrast to the boolean toggle: because a claim carries a meaningful payload (`amount`), its `@Version` is **kept** and echoed via `ClaimDto`, so a genuine concurrent same-squadron *edit* that outlasts the retry bound still 409s; the claim's frontend proxy satisfies the explicit-409 rule by relaying the backend status verbatim through `propagateBackendError`, which additionally preserves the RFC 7807 `code` so `krt-fetch.js` keeps its reload-vs-toast distinction.

### API conventions

Moved to [`docs/specs/api-conventions.md`](docs/specs/api-conventions.md) (`REQ-API-*`): versioned `/api/v1` paths + `@ApiDeprecation`, DTO-only boundaries with MapStruct + Jakarta validation, `@Valid` on writes, RFC 7807 `problem+json` errors, `Pageable`/`PageResponse` with whitelisted sort fields, UTC time, and SpringDoc/`openapi.json` upkeep.

### Frontend resilience & config

- **WebClient** is centrally configured (base URL, default headers, connect/read/write timeouts).
- **Resilience4j** wraps every backend call (Timeout, Retry, CircuitBreaker, Bulkhead). State transitions are logged via `ResilienceEventLogger` so `SERVICE_UNAVAILABLE` / `BACKEND_TIMEOUT` always have a matching log line.
- **Reactor context propagation is mandatory for any new `ThreadLocal` you want to see inside `WebClient` exchange filters.** `WebClient.exchange()` runs on a Reactor-Netty worker thread, not the servlet thread; classic `ThreadLocal` values are not copied across threads. Register a `ThreadLocalAccessor` on `ContextRegistry.getInstance()` in [`ReactorContextPropagationConfig`](frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/config/ReactorContextPropagationConfig.java) (which also enables `Hooks.enableAutomaticContextPropagation()` at startup). The existing accessors cover `ActiveSquadronContext` (active-OrgUnit pin → `X-Active-Org-Unit-Id` outbound header) and `CorrelationContext` (correlation id propagation). Forgetting the accessor means the holder is invisible on the worker thread and the outbound call silently drops whatever it carried.
- Use `MockWebServer` / WireMock to test error paths.
- **Type-safe configuration** — relevant `application-*.yml` settings live in `@ConfigurationProperties` classes with `@Validated` (Keycloak URIs, backend URLs, limits). Constraints: `@NotBlank`, `@URL`, `@Min`/`@Max`. Test misconfiguration during startup (`test` profile). See `*Properties` classes under `config/`.

### Logging

Moved to [`docs/specs/observability.md`](docs/specs/observability.md) (`REQ-OBS-*`): one access-log line per request, MDC enrichment (`correlationId`, `userId`, `orgUnitId`) with cross-module correlation-id propagation, the prod `LogstashEncoder` JSON appender, and the unconditional **never log names, emails, or tokens** rule.

## i18n

- **Every** user-visible string comes from `messages.properties` (`messages_de.properties` / `messages_en.properties`). No exceptions — labels, buttons, tooltips, error messages, flash messages, alerts, placeholders, titles. No hardcoded text in HTML, JS, or Java. Translation keys for the personal-inventory feature live under `personalInventory.*` and `admin.personalInventory.*`.
- **In `.properties` files**, German umlauts (`ä ö ü Ä Ö Ü ß`) MUST be encoded as `\uXXXX` (e.g. `ä`).
- **In Markdown files** (`CHANGELOG.md`, `README.md`, …), German umlauts MUST be literal UTF-8 characters. Never use `\uXXXX` outside `.properties`.

## Testing

- Tests live in the same package structure under `src/test/java/` mirroring `src/main/java/`.
- Naming: `*Test` suffix (e.g., `UserServiceTest`).
- Structure: Given/When/Then (or Arrange/Act/Assert).
- Mock external/complex dependencies with Mockito (`@Mock`, `@InjectMocks`).
- **Every new feature ships with tests.** No exceptions.
- **Never use production / real credentials in tests or local test stacks.** This is a hard rule. It applies to every kind of test (Mockito unit tests, MockMvc, `@SpringBootTest`, TestContainers integration tests) and to every manually-started local stack used to verify a change. Forbidden inputs include — non-exhaustively — the production `.env` at the repo root, the shared `keystore.p12` at `backend/src/main/resources/keystore.p12`, the shared `realm-export.json` Keycloak dump, real OIDC client secrets, real database passwords, real SMTP credentials, real Keycloak admin passwords, real JWT signing keys. Use dedicated test artifacts instead: `.env.test` (gitignored via `.env.*`), a `keystore.p12` generated locally with a throwaway password, a stripped `realm-export.json` with rotated client secrets and a synthetic test user, the `docker-compose.test.yml` override. The README has a `Running the Local Test Stack` section with the exact `keytool` / Python-rewrite commands. Reason: anything that enters a worktree, a CI log, a container volume, a screenshot artifact, an MCP-preview snapshot or an editor backup has to be assumed leaked and rotated — and the recovery is cheaper if it never had to happen in the first place. When you spin a local stack up to verify a UI change, always source `.env.test` (never `.env`), point Docker Compose at `--env-file .env.test`, and tear the stack down with `down --volumes` after the verification.

Minimal example:

```java
package de.greluc.krt.profit.basetool.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidelinesExampleTest {
    @Test
    void shouldPassExampleTest() {
        // Given
        boolean condition = true;
        // When
        // Execute the method under test
        // Then
        assertTrue(condition, "demonstration test");
    }
}
```

## Java conventions

- **Constructor injection only** (favor Lombok `@RequiredArgsConstructor`). No field `@Autowired`.
- **Records** for DTOs and immutable config wrappers.
- **Modern Java**: switch expressions, pattern matching (`instanceof`, `switch`), sealed classes where they help with exhaustiveness.
- **Lombok** — maximize it (`@Slf4j`, `@Getter`, `@Setter`, `@Builder`, `@RequiredArgsConstructor`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Data`) to avoid boilerplate.
- **JetBrains annotations** (`@NotNull`, `@Nullable`, `@Contract`) wherever they communicate a real contract.
- **Logging**: `@Slf4j` — never instantiate loggers manually.

## Documentation

- **Maintain `CHANGELOG.md`** for every user-visible change (features, fixes, env-var additions). No exceptions.
- **CHANGELOG entries must be short, terse and to the point — only the essentials.** One to three sentences per bullet covering *what* changed and *why it matters to the user*. No multi-paragraph design rationales, no exhaustive file lists, no copy-pasted commit messages, no architectural reasoning that belongs in the PR description or Javadoc. Mention the area affected (controller / migration / config) and the user-visible effect — anything beyond that is noise. If a bullet grows past ~3 sentences, cut it.
- Keep `README.md`, the [`ROLES_AND_PERMISSIONS.md`](ROLES_AND_PERMISSIONS.md) role matrix and the German `basetool.wiki` handbook current whenever a change affects them — this is the binding *"README, the role matrix and the user wiki move with the change"* rule from the Requirements section above, restated here so it is not forgotten at documentation time.
- **Javadoc is mandatory** on every class, interface, enum, record, and public/protected method — no exceptions, including trivial getters/setters and Lombok-generated members documented at the field level. Javadoc must describe the *actual* behavior, parameters, return values, side effects, thrown exceptions, and non-obvious invariants of the specific code it annotates. **Generic boilerplate is forbidden** — phrases like "Gets the value", "Returns the result", "Does something", "Helper method", or restating the method name in prose are not acceptable. If you cannot write a concrete, code-specific sentence, read the implementation again until you can.
- **Javadoc is gate-enforced.** Missing Javadoc on a new `public`/`protected` member fails the build via Checkstyle's `MissingJavadocType` / `MissingJavadocMethod` checks — there is no warn-only grace period. Same gate covers summary period (`SummaryJavadoc`), placement between annotations and the declaration (`InvalidJavadocPosition`), `<p>` after blank lines (`JavadocParagraph`), and `@param`/`@return`/`@throws` order (`AtclauseOrder`).

## Git

Do not run destructive Git commands without explicit user instruction: `git reset --hard`, `git clean -fd`, `git push --force[-with-lease]`, `git rebase` on shared/remote branches, `git branch -D`, `git tag -d`, `git stash drop`, or anything that rewrites/discards commits or remote history. Read-only and additive operations (`status`, `log`, `diff`, `add`, `commit`, non-force `push`) are fine when the task needs them.

**Before every `push`, run `./gradlew spotlessApply` (whole repo) — no exceptions, even for a one-line edit.** Spotless is gate-enforced in CI; a narrower local check (`compileE2eJava`, `checkstyleMain`, …) does not cover every source set and will let a formatting violation reach CI. See [Linting / static analysis](#linting--static-analysis).

**Every commit MUST carry a DCO `Signed-off-by:` trailer — always use `git commit -s` (or `-S -s` when GPG-signing).** No exceptions, including AI-generated commits. The trailer's `Name <email>` must match the commit's author identity case-insensitively on the email; the [`.github/workflows/dco.yml`](.github/workflows/dco.yml) check rejects any PR commit lacking a matching sign-off. Bot exemptions (Dependabot / Renovate / GitHub Actions) do NOT apply to commits authored under a real user identity, even if Claude generated the body. If you forget the `-s` flag and the commit is still local (not yet pushed), fix it before pushing: `git commit --amend --signoff --no-edit` for the last commit, `git rebase --signoff main` for the whole branch. For already-pushed commits, ask the user before force-pushing the rewrite — `git push --force-with-lease` falls under the destructive-ops rule above. Full policy and the DCO 1.1 text: [`CONTRIBUTING.md → Developer Certificate of Origin (DCO) sign-off`](CONTRIBUTING.md#developer-certificate-of-origin-dco-sign-off).

**Every commit Claude authors MUST include a `Co-Authored-By:` trailer naming the model — no exceptions, no inconsistency.** This is a transparency requirement that is independent of the DCO sign-off: `Signed-off-by:` attests the human contributor's legal grant; `Co-Authored-By:` discloses AI involvement so reviewers, auditors, and future archaeologists can see which commits had AI in the loop. Use the exact form below, with the human-readable model identifier from this session's system prompt:

```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

If the model identifier in your system prompt is different (e.g. `Claude Sonnet 4.6`, `Claude Haiku 4.5`), substitute that — the rule is "name the model that actually wrote the commit", not "always write Opus 4.7". The email stays `noreply@anthropic.com` regardless of model. Apply this rule to **every** commit Claude composes the message for or makes substantive edits to, including one-line typo fixes, CHANGELOG-only commits, and commits where the diff originated from a verbatim user instruction. The bar is "Claude touched this commit", not "Claude wrote most of the diff". If Claude is only running `git` commands the user typed and not authoring anything, the trailer is not required — but when in doubt, include it. Forgetting the trailer is fixed the same way as forgetting `-s`: `git commit --amend --no-edit` (after appending the trailer to the message) for the last commit, locally only, before pushing.

**Always write Git, GitHub, and in-code prose in English — no exceptions.** This covers every piece of text you author for Git or GitHub, regardless of the language the user speaks to you in: commit messages, branch names, tag names and tag messages, PR titles, PR descriptions/bodies, PR review comments and replies, issue titles and bodies, issue comments, GitHub Discussions posts, release notes, and any inline comment you write on someone else's behalf via `gh`. It **equally** covers all prose inside the code itself — Javadoc, inline `//` and block comments, TODO/FIXME notes, developer-facing log messages, and any other comment or annotation text in source files. If the user prompts you in German (or any other language), translate the substance into English before committing, posting, or writing it into the code. There are exactly two carve-outs: (1) verbatim quoting of existing non-English content (e.g. quoting a user-reported error message in an issue) — the surrounding prose you author stays English; and (2) the **Basetool wiki** (the `basetool.wiki` git repo), which is authored in German by design and is the single exception to the English-on-GitHub rule. This rule does **not** change the i18n contract above: user-visible UI strings still live in the DE + EN `messages*.properties` bundles — "code in English" governs developer-facing prose, not the localized end-user text.

**Every PR you open MUST be assigned and labelled.** Set the assignee to the repo owner: `gh pr create --assignee greluc ...` (`@me` resolves to the same account, since `gh` runs under it). Then apply the labels that match the PR's content, picking **only** from labels that already exist (`gh label list` — `gh` errors on an unknown label, so never invent one inline). Map the Conventional-Commit type of the change to a label: `feat` → `enhancement`, `fix` → `bug`, `docs` → `documentation`; and add one or more UPPERCASE functional-area labels drawn from the `REQ-<AREA>-*` vocabulary (e.g. `BANK`, `NOTIF`, `MISSION`, `ORG`, `SEC`, `DATA`, `UI`, `BE`, `FE`) when one exists and fits. Apply the **`e2e`** label whenever the change touches end-to-end-relevant surface (frontend flows, auth/session, controllers, migrations) — CI gates the full Playwright suite on that label in [`e2e.yml`](.github/workflows/e2e.yml), so without it the E2E run is skipped on the PR. Do **not** hand-apply the bot-owned labels `dependencies`, `github-actions`, `docker`, `automated`, or `release` — Dependabot, the `refreshVersions` job, and the release workflow manage those.
