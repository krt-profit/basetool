> **Doc type:** Living plan — the direction was decided by the owner on 2026-09-29, every
> open decision on 2026-10-01, the delivery of the remaining phases on 2026-10-04 (D-23 … D-27).
> **Status:** Phase −1 done (merged 2026-10-01, PRs #2299–#2312); Phase 0 done (2026-10-03,
> merged as one chain of pull requests, #2350 … #2353): the decision records (ADR-0231 … ADR-0239
> and the amendments of §14) and every guard of §6.1 that Phase 0 owns; the app's re-read of the
> version policy shipped with app v0.5.0 (basetool-android#209, published 2026-10-03). Phase 1:
> the error model (P1-12) is done (2026-10-04) — the first classes moved into module packages;
> the module declarations (P1-13) are in place for every module package on `main` (2026-10-04)
> and grow with each move.
> Last reviewed: 2026-10-04.
> **Owner area:** BE · FE · API · SEC · **Related ADRs:** ADR-0020, ADR-0028, ADR-0032, ADR-0047,
> ADR-0060, ADR-0065, ADR-0069, ADR-0130, ADR-0135, ADR-0136, ADR-0205, ADR-0206, ADR-0212,
> ADR-0214, ADR-0216, ADR-0219, ADR-0223, ADR-0229, ADR-0231 … ADR-0239 · **Specs:**
> REQ-API-\*, REQ-SEC-\*, REQ-AUDIT-001, REQ-DATA-\*, REQ-FE-\*, REQ-XCH-\*
> **Appendices:** [the REST API cut](modularisation/rest-api-cut.md) ·
> [previous audits re-evaluated](modularisation/previous-audits.md) ·
> [evidence and data](modularisation/evidence.md)

# Domain modularisation plan

The Basetool's backend and frontend are cut by technical layer. This plan cuts them by business
domain: every domain gets a small, published API, keeps everything else internal, and a build gate
refuses code that reaches past that API. It is the report of a whole-repository audit of
2026-09-29, and the plan the owner decided on the same day.

## 1 Status and how to read this document

- **What it is.** An audit of the whole `basetool` repository with one primary goal — separate
  the domains (Fachbereiche) so that they interact only through well-defined, complete and stable
  APIs — and two secondary ones: use modern, final language features where they pay off, and
  re-evaluate the findings of the previous audits under today's rules. It names every change with
  its implementation, pros, cons, risks and regressions, and the guard that catches a regression.
- **What it is not.** A record of finished work: Phase −1 (§7.1) and Phase 0 (§7.2) are merged,
  and no class has moved yet. Section 2 lists every decision; section 13 maps the
  former open decisions onto them.
- **Scope.** All modules of `basetool`: `backend`, `frontend`, `ingest`, `keycloak-spi`,
  `keycloak-theme`, `logging-support`, `test-support`, the build and the delivery path. Sibling
  repositories are out of scope, except the Android app's forced-update path, which the API cut
  depends on.
- **Code state.** `origin/main` at `95e945326` (2026-09-29).
- **How it was done.** Eleven read-only analyses, each writing evidence (`file:line`, counts with
  the script that produced them) — the backend domain graph from a class-level `jdeps` graph of the
  compiled classes, cross-cutting concerns and refactor safety, the frontend's Java side, its
  assets, the non-backend modules and the build, modern Java idioms, the REST API, a web research of
  the current official documentation (all sources read on 2026-09-29), and three re-evaluations of
  previous audits. A second, adversarial round of four agents then tried to refute the
  security-relevant and the load-bearing claims; §9 and §16 record what survived.
- **Vocabulary.** *Domain* is a functional area (the vault's `20 Domains` notes). *Module* means a
  package-level module inside one Gradle project unless the text says *Gradle module*. *Kernel* is
  the small shared code every module may use.

## 2 Decisions taken (owner, 2026-09-29 and 2026-10-01)

| # | Decision | What it means |
| --- | --- | --- |
| D-01 | **Target architecture C** | First a modular monolith: one package per domain inside the existing `backend` Gradle module, boundaries enforced by tests, today's violations frozen in a baseline that may only shrink. Later, once their boundary has held for some releases, `exchange` and `bank` become Gradle modules of their own, so the compiler enforces their boundary. Every other domain stays a package. Runtime, database, image and deployment are unchanged. |
| D-02 | **Enforcement with both ArchUnit and Spring Modulith** | ArchUnit keeps the security and structural rules (re-keyed so a move cannot disarm them, §6) and carries the frozen module baseline; Spring Modulith 2.1.1 runs in **test scope only** for module verification, generated module documentation and module-scoped tests. Its event publication registry is not adopted (§10). |
| D-03 | **Full per-domain cut of the REST API** | `/api/v1` is re-cut per domain, naming clean-ups included (`/slim`, `/search`, admin sub-trees, …). Details: [appendix](modularisation/rest-api-cut.md). |
| D-04 | **Hard cut with forced update for the Android app** | No parallel old and new paths and no sunset windows. Each API wave ships with a new app release and raises the minimum version through the frozen version gate `GET /api/v1/app/version-policy` (REQ-API-010). |
| D-05 | **The exchange contract is frozen** | External clients use `/exchange/v1` on the ingest gateway; that contract and the backend surface the gateway relays to (`/api/v1/exchange/**`) keep their behaviour byte-identical. |
| D-06 | **Previous audits** | The July 2026 modularity audit and the September 2026 improvement audit are re-evaluated in full; the earlier focused audits only for items that were deferred, rejected or left open, plus fixes a domain split could put at risk. Sibling-repository findings are listed, not re-verified. |
| D-07 | **Security defects found along the way are fixed first, separately** | The confirmed defects of §9 are fixed in their own pull requests before the refactor starts. |
| D-08 | **Report form** | This document and its appendices in the repository, plus a plan note in the knowledge base. |
| D-09 | **Error model** (was O-01, decided 2026-10-01) | A sealed kernel of generic kinds, one non-sealed `DomainProblem` base that module exceptions extend, one `ProblemCode` enum per module, a registry test for unique codes, and the codes documented in OpenAPI (§5.5). |
| D-10 | **Exact session allow-list** (was O-02, decided 2026-10-01) | The frontend session deserializer admits an exact, test-derived list of session-bound types instead of the `…frontend.model.` prefix, in its own release before the frontend move; ADR-0206 is amended (§5.9, F2). |
| D-11 | **Release-bound minimum app version** (was O-07, decided 2026-10-01) | The minimum version becomes a reviewed default in the release's own configuration, so it deploys and rolls back with the API it protects; the host `.env` value stays only as an emergency override. With it: the app re-reads the policy on resume and after an unexpected 404 (shipped before the first cut), retired paths answer `APP_UPDATE_REQUIRED`, and cuts are announced and made at low usage. REQ-API-010 is amended first (§5.10). |
| D-12 | **Exchange administration under `/api/v1/connected-apps/admin/**`** (was O-14, decided 2026-10-01) | The 14 exchange-administration operations move there in the web-only wave; `/api/v1/admin/**` is then retired as a URL rule in favour of `/api/v1/*/admin/**`. |
| D-13 | **Error Prone + NullAway** (was O-03, decided 2026-10-01) | A compile-time nullness gate on the JetBrains annotations, starting with the module API packages and widening from there (§8.1). |
| D-14 | **`_` for unused variables, empty catch blocks included** (was O-04, decided 2026-10-01) | ADR-0214 and Checkstyle's `EmptyCatchBlock` pattern are amended to accept `catch (… _)`; not in keycloak-spi, whose Java 21 bytecode has no unnamed variables (§8.1). |
| D-15 | **No `default` over a project enum** (was O-05, decided 2026-10-01) | Switches over project enums are exhaustive without `default` unless they handle a deliberate subset; an ADR and a line in the Java conventions (§8.1). |
| D-16 | **Browser baseline "Baseline 2025" (ES2025) and Trusted Types** (was O-06, decided 2026-10-01) | Floor at least Chrome 122, Firefox 131, Safari/iOS 18.4 (the iterator helpers; single 2025 features need newer releases); type check and ESLint raised to ES2025 once TypeScript 7 is proven to accept the `ES2025` lib; Trusted Types report-only first, then enforced (§8.2). |
| D-17 | **Member settings move with their owner** (was O-08, decided 2026-10-01) | The payout-preference and blueprint-sharing columns move from the user table into tables of the owning modules, each with its own version, by a Flyway migration with data transfer in that domain's wave. |
| D-18 | **Every API family classified; a kernel page policy** (was O-09, decided 2026-10-01) | Every family is classified explicitly as `no-store` or revalidatable, with a test that catches an unclassified one (REQ-SEC-031); a kernel page policy replaces the 100,000 ceiling with a lower default, set by measurement, and explicit, tested opt-outs. |
| D-19 | **SpEL evaluation failures stay a fail-closed 400, counted and alerted** (was O-10, decided 2026-10-01) | A metric counts them and a Prometheus rule alerts on them; the problem-code contract is unchanged. |
| D-20 | **`MissionParticipant.orgUnits` becomes lazy** (was O-11, decided 2026-10-01) | Loaded through an entity graph where it is needed, the load paths pinned by N+1 tests. |
| D-21 | **CI reuses the configuration cache** (was O-12, decided 2026-10-01) | The owner adds the encryption key as a repository secret; pull requests read the cache, `main` writes it; the workflow change follows the secret. |
| D-22 | **The raw evidence is kept** (was O-13, decided 2026-10-01) | Reports, finding data and scripts, sanitised, in [`docs/archive/domain-modularisation-audit-2026-09/`](archive/domain-modularisation-audit-2026-09/README.md). |
| D-23 | **No soak before Phase 5** (decided 2026-10-04) | `exchange` and `bank` become Gradle modules right after Phases 2–4, without waiting for their package boundary to stay green for some releases; the Phase 0 guards and the frozen module baseline hold the boundary meanwhile (§7.7). |
| D-24 | **One combined API cut** (decided 2026-10-04) | The REST waves of §7.9 land on `main` together and ship as one backend release with one app release that uses every new path, so members take one forced update instead of one per wave; the release-bound floor (D-11) rises with that release. |
| D-25 | **The terms text stays** (decided 2026-10-04) | The terms keep their wording (they still mention guests and list five areas), because any change forces every member to consent again (ADR-0127); D1981-TERMS is closed as kept. |
| D-26 | **Keycloak hardening steps 2, 11 and 12 are in scope** (decided 2026-10-04) | SMTP and forgotten password, OTP for admins, session windows (`docs/KEYCLOAK_HARDENING_RUNBOOK.md`); each realm write still needs the owner's yes per action. Production and repository-settings work (CI-SEC-16, OPS-SEC-08, the S-09 restore drill) is built in code and executed per approved action. |
| D-27 | **The 29 ADRs accepted in #1981 are ratified** (decided 2026-10-04) | The owner confirms the status changes of the documentation audit; D1981-ADR is closed. |

## 3 Summary

1. **The layers are acyclic; the domains are not.** ArchUnit keeps the layer packages free of
   cycles (ADR-0047), but on the level of domains 21 of 23 form one strongly connected component,
   across 1,565 cross-domain class edges and 41 pairs that depend on each other both ways. Even the
   business core alone — blueprint, exchange, hangar, inventory, joborder, materialexchange,
   mission, operation, refinery — is one cycle. After a target layering and behaviour-free
   re-homings, **150 class edges in 48 module pairs** point the wrong way and must be inverted;
   inventory (36), identity (34), the per-aggregate scope gates (17) and mission (14) carry most of
   them. The knot is looser than it looks: the minimum any layering needs is 136 edges, and on the
   class level only three cycles actually cross domains (the sealed exception family, the inventory ⇄
   job-order entities, the mission ⇄ operation ⇄ refinery entities); every other domain cycle breaks by
   moving or inverting single class edges.
2. **The refactor itself is the largest security risk, and it is controllable.** A package move
   silently weakens gates that are keyed on names: 30 to 33 of the 43 backend ArchUnit rules can
   pass without checking anything (whole-domain versus piecemeal moves), among them the rules that
   guard authorization, tenancy, redaction and the exchange's reduced authority; the 166 SpEL bean
   references in `@PreAuthorize` fail at request time as HTTP 400 when a bean is renamed — logged
   at WARN, not counted as an HTTP error, visible only if the web UI produces a sustained burst; 15
   inventory and hangar endpoints have a URL rule as their only role gate (harmless today because
   every account holds the member role, silently gone after a path move); the CSRF exemption,
   `no-store` caching, rate limits, the edge allow-list and the frontend session allow-list are keyed
   on paths or packages. Phase 0 (§7.2) turns every one of these into a failing build **before**
   anything moves.
3. **The hubs are known.** `OwnerScopeService` and `AccessGateService` decide access for nine
   domains and read the repositories of seven; GDPR deletion and merge touch fourteen; `UserMapper`,
   the 63-class `support` package (43 classes named after a domain), the central `event` package,
   the one `AuditEventType` enum and the sealed `AppException` couple everything to everything. The
   sealed `AppException` is a hard blocker for package-per-domain: in a class-path application a
   sealed class cannot permit a subclass in another package (proven with javac 25).
4. **Most cross-domain writes need the caller's transaction.** Twelve of sixteen write-path
   families share the caller's transaction (row and advisory locks, `MANDATORY` hops, foreign-key
   order, and 206 same-transaction audit writes). Module APIs are therefore synchronous in-process
   contracts; after-commit events stay limited to notifications, mail and a few self-healing
   reactions, and audit stays a direct call.
5. **Inventory has no command API.** Lager rows are created in three other domains (exchange,
   refinery, job-order production) and locked by three; the September finding APPSEC-01 was exactly
   such a foreign write. A `StockCommands` API owned by inventory closes that class.
6. **The frontend is a composition layer.** Its coupling is page composition, not entanglement,
   but its "single seam" is only a convention: eleven controllers bypass `BackendApiClient`'s error
   mapping. Per-domain typed clients over the one filtered `WebClient` keep the single resilience
   pass (verified in the Spring sources), which removes the July audit's reason for refusing a split.
7. **The REST API is cut by controller and audience.** 99 controllers serve 572 documented
   operations for 22 domains; `/api/v1/admin/**` holds five domains and `/api/v1/users/**` the data
   of six. The full cut the owner chose is feasible, one app release per wave, with the gaps of the
   forced update known (§5.10).
8. **The Java code is already modern.** 1,049 records, arrow-form switches only, pattern matching
   instead of casts, `Stream.toList()`. What is left is exhaustiveness (a Checkstyle rule forces dead
   `default` branches), `toString` hygiene, `_`, and one `ThreadLocal` that fits `ScopedValue`. The
   JavaScript stays at ES2015 idioms and has no documented browser baseline. JDK 25 stays until the
   next LTS (29, September 2027).
9. **Previous audits.** Of the September audit's 156 in-scope findings, 139 are done and hold, 14
   are partial, one is open, one was superseded and one regressed; 124 are confirmed as decided, 27
   adjusted to the modular target, two absorbed by it, one dropped and two re-prioritised. The July
   findings shipped with three gaps (a view assembler never built, a catalogue loader adopted once,
   the exception-handler split open); 14 of its 15 rejections still hold; the one that does not is
   "never split `BackendApiClient`".
10. **Defects found along the way**, verified in the adversarial round — a Keycloak client without
    timeouts on the authentication path, reversible username pseudonyms in logs, credentials and a
    Discord token in generated `toString()` output, a CSV formula injection, a runtime log-level
    endpoint that CSRF probably blocks in production, a refinery order that can be stored twice, an
    app call the edge refuses — are listed in §9 with their verified severity and fixed first
    (D-07). None of them lets one member read or change another member's data.

## 4 Where the system stands

### 4.1 Backend

The backend has 1,389 top-level types (174,597 lines) in layer packages: `controller` 91 + 8,
`service` 208 + 65, `repository` 115, `model` 169, `model.dto` 342, `mapper` 51, `support` 63,
`config` 47, `task` 11, `event` 19. 1,383 of the 1,389 types are `public`, so no domain can hide
anything. The domain map — the ordered rule set that assigns each of the 1,389 types — and the
coupling matrix are in the [evidence appendix](modularisation/evidence.md#backend-module-inventory).

| Fact | Value | Consequence |
| --- | --- | --- |
| Strongly connected component | 21 of 23 non-kernel domains; only `admin` and `dashboard` outside | No domain boundary exists yet; Gradle modules (which forbid cycles) are impossible today |
| Cross-domain class edges | 1,565, of them 206 `service → foreign repository` | Any class can bypass another domain's rules by using its repository directly |
| Backlog after target layering | 150 edges in 48 module pairs under the ranks of §5.1; the minimum any layering needs is 136 edges in 43 pairs | The concrete decoupling work (§7) |
| Class-level cycles crossing domains | Three: the sealed `AppException` family (14 classes), the inventory ⇄ job-order entities (12), the mission ⇄ operation ⇄ refinery entities (11) | Every other domain cycle breaks by moving or inverting single class edges |
| JPA associations | 185; 74 cross a domain (24 → `User`, 18 → org unit, 24 → catalogue, **8 business → business**); none cascades across a domain | Only the eight business associations must become id references; the rest point down the target layering |
| Cross-domain write paths | 149 non-audit write sites in 57 caller methods, plus 206 `AuditService.record` calls | Module APIs must join the caller's transaction |
| Classes over 600 lines | 36; the delegating facades of ADR-0061/0062/0063/0065 are ready-made module APIs | Do not split further before the move |
| Unique simple names | all 1,389 | Spring bean names and springdoc schema names survive a package move |

### 4.2 Cross-cutting concerns

| Concern | Today | In the way of separation because |
| --- | --- | --- |
| Authorization | 431 `@PreAuthorize` (378 method, 53 class) on 98 REST controllers, 1 MVC controller and 7 services; 166 SpEL bean references to 8 beans (`ownerScopeService` 66, `missionSecurityService` 40, `authHelperService` 17, `exchangeGate` 14, `orgRoleManagementSecurityService` 13, `bankSecurityService` 10, `specialCommandSecurityService` 5, `connectedAppsGate` 1) | `OwnerScopeService` (56 public methods, 54 distinct names) is used by 34 classes in 14 domains; `AccessGateService` holds nine repositories of seven domains |
| Tenancy scoping | Six package-private JPQL fragments in `ScopeSpecifications` spliced 29 times into seven repositories | javac folds the constants, so no dependency tool sees them; a package move breaks their visibility |
| Audit | 206 + 51 `record` calls, all `MANDATORY`; one `AuditEventType` (202 constants, 12 domains) whose names are persisted, alerted on and mirrored in the frontend | Legitimate platform dependency; the enum must stay one closed vocabulary |
| Events | 22 `publishEvent` sites in 9 classes; 16 event records in one central package; notification reads bank grants (a bank ⇄ notification cycle); at least nine event records carry names, e-mail addresses or free text, which today end up in notification parameters and on Redis pub/sub, never in logs | Events belong to their publishers; a durable event store would persist personal data |
| Live sync | Backend and frontend topic registries; the subscription authorizer switches into the scope hub | Room authorization must equal the read gate of each domain |
| Exchange | 14 foreign repositories and 22 foreign services; writes `InventoryItem` rows itself | A second write path into four domains |
| GDPR | `UserDeletionService` writes 12 foreign repositories in one transaction; merge re-points 28 columns by native SQL; three registries name tables of up to 18 domains | Identity cannot be a foundation module |
| Scheduling and metrics | 15 `@Scheduled` methods (11 in one `task` package); `BusinessMetricsCollector` polls seven domains | Every domain feature edits two central classes |

### 4.3 Frontend

The frontend (554 classes, 66,858 lines) is package-by-layer too: `controller` holds 103 files and
half the code; 292 hand-written DTO mirrors and 30 forms sit in `model`; the 62 `config` classes are
all cross-cutting. Every class maps cleanly onto a domain, and the coupling between domains is page
composition (0 cross-domain controller → controller edges, seven two-way domain pairs through shared
reference DTOs).

- **The backend seam.** Resilience (bulkhead, time limiter, idempotent retry, circuit breaker) is a
  filter on the `webClient` bean (ADR-0032), together with the OAuth2 bearer relay and the
  correlation, org-unit, locale and client-IP relays. `BackendApiClient` adds the error mapping, the
  catalogue cache and the anonymous terms client. Eleven controllers inject the filtered `webClient`
  directly and lose the error mapping: an expired refresh token becomes a 500 instead of the
  re-authentication flow, the RFC 7807 `code` is dropped and `basetool_backend_client_errors_total`
  does not see them. No ArchUnit rule protects the seam, although arc42 §4.1 said so (corrected with
  this plan).
- **URIs.** Of 624 `BackendApiClient` call sites, 356 build the backend path by string
  concatenation and 41 through URI templates.
- **Package-keyed couplings.** The session deserializer admits application classes by the prefix
  `…frontend.model.` (production enforces it since 2026-09-25; replaced by the exact list on
  2026-10-04, F2); 172 `T(…support.Roles)` references in
  22 templates resolve only at render time; three DTO contract tests are keyed on the `model.dto`
  package; the authorization gate test checks classes, not handlers.
- **Assets.** 100 scripts, 64 stylesheets and 120 templates map onto the domains (19 core + 81 domain
  scripts in 21 domains). The coupling mechanism is the classic shared global scope: 523 top-level
  names in 50 non-IIFE files.

### 4.4 REST API

- 574 handler mappings in 99 controllers, 572 of them in the committed `openapi.json`.
- Eleven domains spread over several first path segments; `/api/v1/admin/**` (51 operations of five
  domains) is protected by one URL rule; 29 operations sit in the controller or prefix of a domain
  they do not belong to; 13 DTOs are request body and response type at once.
- Authorization for one operation can live in five places (URL rule, controller annotation, service
  annotation, imperative service check, imperative controller check).
- The public `api.*` vhost admits by path, not by operation: 259 documented operations pass its
  allow-list against 234 frozen app operations; 14 more reach the backend, and the app calls ten of
  those — gaps in the frozen contract, which checks paths but neither methods nor the reverse
  direction. The backend still gates all of them. Two prefix rules (`^/api/v1/terms/`,
  `^/api/v1/me/`) would publish anything later moved beneath them.
- The error contract (`code`, `correlationId`, `fieldErrors`) is not documented in the OpenAPI
  document; three schema names collide; the backend generator test asserts nothing.

### 4.5 Ingest, keycloak-spi, libraries, build

- **Ingest** (75 classes) is package-by-kind with nine two-way package pairs; `exchange` holds 43 %
  of the code in six concerns. It relays 14 operations on 13 paths under `/api/v1/exchange/**`,
  which no build-time test treats as frozen, and two of them reuse web-import DTOs.
- **keycloak-spi** is one flat package with three concerns; one of six service registrations is
  pinned by a test; it compiles against Keycloak 26.7.4 internals while the image runs a minor tag.
- **logging-support** and **test-support** stay as they are (ADR-0205).
- **Build.** The root `subprojects { plugins.withId(…) }` convention (354 of 527 lines) is
  configuration-cache clean and enough for packages; coverage floors, test heap and mutation targets
  are keyed on the project name, so a new Gradle module would silently inherit weaker defaults.

## 5 Target architecture

### 5.1 Module cut and layering

Twenty-five backend modules, each with a rank. A module may depend on modules of lower rank and on
the modules its row allows; a dependency upward is inverted through an SPI or an observer (§5.3).

| Rank | Modules | Role |
| --- | --- | --- |
| 0 | `kernel`, `platform` | `kernel`: `AbstractEntity`, `PageResponse`, `Entities`, `OptimisticLock`, `StringNormalization`, `LikePatterns`, validation constraints, the error-model base (§5.5), handle anonymisation, `UserRef`/`OrgUnitRef` value types (`OrgUnitRef` only once `OrgUnitKind` is a kernel type, §7.3), SCU rounding. `platform`: web, logging, metrics infrastructure and the access core (`AuthHelperService`, `AuthenticatedSubject`, `ClientAttribution`); `Roles`, `Permissions`, `RequestMemo` and `ProblemResponseFactory` are kernel types (corrected 2026-10-04, P1-9). Closed to domain meaning, like `logging-support` (ADR-0205). |
| 1 | `audit`, `notification`, `livesync` | Platform services every domain may call; they depend upward only through SPIs. |
| 2 | `catalogue` | Materials, items, locations, ship types, refining methods, job and frequency types, and the UEX, SC Wiki and P4K imports — including the recipe graph (`model.scwiki.Blueprint`). |
| 3 | `identity` | Users, registration, profile, terms consent, and the GDPR orchestration, which calls the other modules through identity-owned SPIs. |
| 4 | `orgunit` | Squadrons, special commands, Bereiche, Organisationsleitung, memberships and appointments (the Leitung write paths), and the leadership cascade (`OrgUnitCascadeService`). |
| 5 | `scope` | The request-scoped scope kernel: `RequestScopeResolver`, `ScopePredicate`, `OrgUnitStampingService`, `ScopeSpecifications`, the authorities converter — and `OwnerScopeService` and `AccessGateService` until the per-domain access policies (§5.4) have taken their methods over. |
| 6 | `admin`, `dashboard` | System settings, announcements. |
| 7 | `orgchart`, `promotion`, `personalinventory`, `hangar`, `blueprint` | Org chart and the Leitung view, Beförderung, Mein Inventar, ships, personal and default blueprints. |
| 8 | `inventory` | Lager: stock, holders, allocations, checkout, rebooking, the lot-lock protocol. |
| 9 | `mission` | Einsätze, participants, finance entries. |
| 10 | `refinery`, `joborder`, `materialexchange` | Raffinerie, Aufträge, Materialbörse. |
| 11 | `operation`, `bank` | Operationen and payouts; the Kartellbank (org-unit-blind by design, ADR-0020). |
| 12 | `exchange` | The external client exchange — an adapter over the other modules' APIs. |
| 14 | `app` | The composition root: `BackendApplication`, `SecurityConfig`, `DataInitializer`, and `BusinessMetricsCollector` until each module publishes its own gauges. |

Rank 13 (`privacy`) exists only during the migration: the 31 GDPR classes are re-homed there first
and then dissolved into identity-owned SPIs (§7.6).

The ranks are a starting point, not a verdict. One order in particular is to be settled with the
first core step: under the ranks above inventory sits below mission, although mission has no class
edge into inventory and inventory has 13 into mission; swapping the two, or inverting those 13
edges through the earmark SPI of §7.5, are the two ways to resolve it, and the cheaper one wins.

### 5.2 Inside a module

```text
de.greluc.krt.profit.basetool.backend.inventory
├── api/        StockCommands, StockQueries, request and result records, events, the SPIs it owns
├── internal/   services, repositories, entities, mappers
└── web/        controllers, REST DTOs, REST mappers — no @Transactional, no repository, no entity
```

- **`api`** is the only thing another module may use. It is a Spring Modulith named interface and an
  ArchUnit rule at the same time. The facades that already delegate (`MissionService`,
  `JobOrderService`, `InventoryItemService`, the `OwnerScopeService` split) become the module APIs;
  their sub-services become package-private after the move.
- **`internal`** holds everything else. Foundation modules (`identity`, `orgunit`, `catalogue`)
  additionally publish their entities as a named interface, because 66 of the 74 cross-domain
  associations point at them and stay (§5.6).
- **`web`** owns the REST surface of the module: controllers are thin, the transaction boundary
  moves into the module API (today 283 mappings run in a controller-level transaction because
  entity-to-DTO mapping happens in the controller), identity is read only through `@CurrentUserId`,
  and REST DTOs of app-facing operations are separate types from the module-API records, so internal
  refactors stay free (the exchange layer already works this way).

### 5.3 How modules interact

Three mechanisms, chosen per interaction. The choice follows one rule: **if an invariant must hold
when the transaction commits, the reaction runs inside the transaction.**

| Mechanism | When | Examples | Guard |
| --- | --- | --- | --- |
| **Command/query API** of the called module, `@Transactional(propagation = MANDATORY)` for writes | The caller uses another module's capability | joborder, refinery and exchange → `inventory.api.StockCommands`; operation → `mission.detachFromOperation`; exchange → hangar and blueprint write APIs | Only the owner writes its aggregate (ArchUnit: no foreign constructor call, no foreign repository write) |
| **Observer SPI** owned by the lower module, implemented by the higher one, called synchronously in the same transaction | A lower module's change obliges a higher module to react atomically | `StockChangeObserver` (Materialbörse offer ratchet), `MembershipChangeObserver` (org chart mirror, inventory re-stamp, bank responsibility), ship deleted, job type designated, stock sold for a mission | Every observer implementation is `MANDATORY`; no `@TransactionalEventListener` on an in-transaction event type |
| **After-commit event** (`@TransactionalEventListener` + `@Async`, as today) | The reaction may happen later or fail on its own | Notification fan-out, registration and approval mail, the exchange departure, the default-blueprint grant (self-healed by its task), the bank holder reconciliation | Listeners never write audit rows and never rely on the request's security context |

Two fixed points:

- **Audit is a direct, synchronous call** to `audit.api.AuditRecorder` (`MANDATORY`), never an event.
  REQ-AUDIT-001 requires the audit row in the business transaction, and an audit-insert failure
  must roll the mutation back. `AuditEventType`, `AuditDomain` and `AuditDetails` move into
  `audit.api` unchanged; their names are persisted, alerted on and pinned by the frontend.
- **Cross-cutting SPIs** invert the platform's upward edges: `ActorHandleResolver` (identity, for the
  audit actor snapshot), `RetentionParticipant` (bank), `RecipientDirectory` (bank, identity,
  orgunit — breaks the bank ⇄ notification cycle), `LiveSyncTopicAuthorizer` (per module, reusing
  its read gate), `ActiveOrgUnitProvider` (scope → logging MDC), and the GDPR participants of §7.6.

The sixteen cross-domain write families, with the mechanism each gets, are listed in the
[evidence appendix](modularisation/evidence.md#cross-domain-write-families).

### 5.4 Authorization and tenancy

- **One access policy per scoped aggregate, owned by its module**, each an explicitly named bean
  (`missionAccessPolicy`, `jobOrderAccessPolicy`, `inventoryAccessPolicy`, `refineryAccessPolicy`,
  `operationAccessPolicy`, `shipAccessPolicy`, and the promotion and blueprint-overview gates), built
  on the scope kernel and the module's own repository. The policy owns **both** the per-row gate
  and the aggregate's JPQL scope fragment, so the rule "list query and per-row gate widen together"
  (vault *Scoping*) holds by construction.
- **Migration without a big bang** (ADR-0065 precedent): name `ownerScopeService` explicitly; add the
  policies as delegates; re-point the SpEL of one domain per pull request; move the method bodies
  verbatim; delete the facade methods last. A differential test evaluates the old and the new gate
  over one fixture matrix (admin pinned and unpinned; members of zero, one and two units; Bereich
  cascade; owner escape; ownerless personal row; the operation participant escape; the job-order
  SK-queue and requester escapes; SK lead) and requires identical verdicts.
- **Controllers keep a coarse role gate that stands on its own**; the module API enforces scope and
  ownership, so non-HTTP callers (the exchange, listeners) hit the same business gates. The 15
  endpoints whose only role gate is a URL rule get their gate in the annotation first (§7.2).
- **The bank seam stays one class** (ADR-0020, ADR-0028). Its two ArchUnit rules are re-keyed from
  the `Bank*` name prefix and FQCN strings to module membership and class literals before any bank
  class moves; the bridge set stays exactly `OrgUnitBankAccessService`.
- **The admin fence** becomes `/api/v1/*/admin/**` → ADMIN once admin sub-trees move into their
  domains, and every admin controller keeps its class-level `hasRole('ADMIN')`. The URL rules are
  first-match-wins, and today the `/api/v1/hangar/**` and `/api/v1/personal-inventory/**` rules
  stand before `/api/v1/admin/**`; the new matcher therefore goes **before** every domain rule, or
  it never decides for `/hangar/admin/**` and `/personal-inventory/admin/**` (nothing would widen —
  all 51 admin operations carry `hasRole('ADMIN')` themselves — but the fence would be void there).
  The 14 exchange-administration operations cannot move under the frozen `/api/v1/exchange/**`;
  they move to `/api/v1/connected-apps/admin/**` (D-12), and `/api/v1/admin/**` stays as a rule
  beside the new one only until the last of them has left.
- **Nested paths keep the gate on the child.** An operation moved under a parent path
  (`/missions/{missionId}/finance-entries/{entryId}`) is still authorized on the child — today
  `canEditFinanceEntry(#entryId, …)` — and a child of another parent answers 404 after the scope
  check; a gate re-pointed at the path's parent would let the manager of one mission edit another
  mission's entries.

### 5.5 Error model

The sealed `AppException` permits 13 subclasses, six of them domain exceptions, and
`AppExceptionKind` holds domain codes. A sealed class cannot permit a subclass in another package in
a class-path application, so today's shape cannot follow the domains.

Target (decided 2026-10-01, D-09): a **sealed kernel of generic kinds** (not found,
conflict, validation, access, unavailable) plus **one non-sealed abstract `DomainProblem` base** in
the kernel that module exceptions extend; codes become a `ProblemCode` interface implemented by one
enum per module; a registry test collects every implementation and asserts unique codes against a
committed list; the OpenAPI document gains `code`, `correlationId` and `fieldErrors` with the known
values as a documented list. `GlobalExceptionHandler` and `ErrorDisclosurePolicy` stay the single
place that decides what a response discloses. Rejected: keep every exception type in the kernel
package and seal it (no domain ownership, no code documentation).

**Implemented 2026-10-04 (P1-12).** `AppException` permits the eight existing generic kinds
(`BadRequest`, `NotFound`, `BusinessConflict`, `DuplicateEntity`, `EntityInUse`, `ExternalService`,
`ReportGeneration`, `RateLimitExceeded`) and `DomainProblem`; no new "access" kind was added,
because nothing throws one — access refusals stay Spring Security's `AccessDeniedException`, which
the handler maps. The six module exceptions moved, names unchanged, into
`backend.<module>.api` (bank, exchange, inventory, joborder, refinery, scope — the layout of §5.2),
with one `ProblemCode` enum each (`BankProblemCode`, `ExchangeProblemCode`, `InventoryProblemCode`,
`JobOrderProblemCode`, `RefineryProblemCode`, `ScopeProblemCode`); `CoreProblemCode` keeps the 27
kernel codes, wire values unchanged. `ACTING_MEMBER_REFUSED` went to the exchange enum, because its
producer `ActingMemberFilter` belongs to the exchange module in the domain map. The documented list
is now collected from every registry enum and sorted by code. Correction, 2026-10-04: the "six
kernel → domain edges" of the sealed family were never in the ArchUnit baseline — `jdeps` counts a
`PermittedSubclasses` attribute, ArchUnit does not (REQ-MOD-004 already said so); the baseline
shrank instead by the two `kernel → platform` edges to `ErrorDisclosurePolicy`, which the domain
map now assigns to the kernel as part of the exception contract (138 → 136 edges).

### 5.6 Persistence

- **One schema, one Flyway location, one global `V<n>` sequence** in `backend`, also after the
  Gradle extraction of exchange and bank (Flyway 13 merges one `classpath:` location across jars with
  content-only checksums; entity scanning spans jars under the root package). Foreign keys stay —
  they are integrity, not coupling.
- **A table-ownership map** (table → module) in `docs/specs/data-persistence.md`, with a test that
  every table in `information_schema` has exactly one owner; native SQL and triggers may touch a
  foreign table only through a listed exception (the exchange change-feed triggers of V252).
- **Only the eight business → business associations become id references**:
  `InventoryJobOrderAllocation.jobOrder`, `InventoryMissionAllocation.mission`,
  `MaterialExchangeOffer.inventoryItem`, `MissionUnit.ship`, `Mission.operation`,
  `Mission.refineryOrders`, `Operation.missions`, `RefineryOrder.mission`. Associations into
  identity, org units and the catalogue stay; they carry the scope JPQL and the fetch graphs.
- **Caching holds read models, not entities.** Cached catalogue mutators are safe today only because
  they call their own `@Cacheable` getter through self-invocation; a query/command split would make
  them edit the cached instance (§7.2, G-19).

### 5.7 Enforcement

| Layer | Tool | What it checks |
| --- | --- | --- |
| Module boundaries | Spring Modulith 2.1.1 `ApplicationModules.verify()` in test scope, `explicitly-annotated` detection, `@ApplicationModule(allowedDependencies = …)` per `package-info` (the `<module>::api` of every declared module the domain map's ranks and `allow` rows permit), `@NamedInterface("api")` on the `api` package and on each package below it; only the annotations (`spring-modulith-api`) are on the main compile classpath, `compileOnly` | No cycles between modules, no access to another module's internals, only allowed dependencies |
| Frozen baseline | ArchUnit `modules()` rule over the domain map, wrapped in `FreezingArchRule` | Today's 150 violating edges are recorded and may only shrink; security rules are never frozen |
| Security and structure | The existing ArchUnit rules, re-keyed (§6) | Gates on every endpoint, no `SecurityContextHolder` outside the seam, no entities on the wire, the bank seam, the exchange's reduced authority, audit writes |
| What neither sees | Targeted tests (§6, G-01…G-24) | SpEL bean references, spliced JPQL scope fragments, JPQL strings, Thymeleaf `T(…)`, native-SQL GDPR registries, database triggers, path-keyed security lists |

Two facts shape this. Spring Modulith 2.1.1 is compiled against ArchUnit 1.4.2 while the project
pins 1.5.1, so its `verify()` must run once under 1.5.1 in a spike before it becomes a gate. And a
module verifier alone would be a green check that checks nothing: a large share of the coupling
lives in strings (the last row), which is why the targeted tests are part of the gate, not an extra.

### 5.8 Gradle modules for exchange and bank

After Phase 4, and only once the package boundary of each has been green for some releases:

- **`backend-exchange`** — the adapter through which external clients write. Only eleven class edges
  point into it today, all fixable (row records to their owners, two blueprint edges, the stock
  path through `StockCommands`). Compile-time isolation makes its reduced authority structural.
- **`backend-bank`** — org-unit-blind by design (REQ-BANK-008). Eighteen edges point into it; after
  the platform SPIs and the GDPR participants they are gone. The compiler then guarantees what two
  ArchUnit rules guard today.

Prerequisites: an included `build-logic` build with convention plugins (the root `subprojects {}`
block and 22 cross-project `rootProject` accesses block Gradle's isolated projects and would silently
give a new module default coverage floors), typed per-module build settings, prefix rules in the
image-reuse and SBOM scripts, JaCoCo aggregation, and a context-shape test (scheduled tasks, event
listeners, controllers) so a module outside the scan root cannot lose beans silently. One image per
application stays; Flyway stays in `backend`.

### 5.9 Frontend

```text
de.greluc.krt.profit.basetool.frontend
├── kernel.backend       BackendApiClient, BackendErrorMapper, CachedCatalog, WebClientConfig, relays
├── kernel.security      SecurityConfig, access gates, Roles, CSP/CSRF
├── kernel.session       RedisSessionConfig, SessionTypeAllowList
├── kernel.layout        UsesLayoutModel, LayoutContextLoader, the layout advices
├── kernel.web           GlobalExceptionHandler, BackendErrorResponses, RelayParams, binding
├── kernel.livesync      websocket relay, presence
├── kernel.model         PageResponse, reference DTOs, PayoutPreference, handoff types
├── shell                landing, legal pages, manifest, client-error beacon
└── <domain>.web | .client | .model   controllers and view assemblers; the typed client; mirrors and forms
```

- **Typed backend clients per domain.** One client interface per domain, first as a thin class over
  `BackendApiClient` (no new framework surface, identical error path), then — when the domain's REST
  cut lands — as a Spring HTTP interface created with
  `HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))` over the **same**
  `webClient` bean, so bulkhead, time limiter, retry, circuit breaker, the OAuth2 bearer relay and
  the org-unit, correlation, locale and client-IP relays apply unchanged. `@ImportHttpServices`
  groups are allowed only with a highest-precedence configurer that supplies `webClient.mutate()`,
  because a default group builds a fresh `WebClient` without any of those filters. Client interfaces
  never take `java.net.URI`, `UriBuilderFactory` or `@CookieValue` parameters (a `URI` argument
  replaces the whole request URL, and the OAuth2 filter would send the member's bearer token there),
  never carry `@Cacheable` (responses depend on the implicit bearer and org unit), and use only
  relative `/api/` paths.
- **The July audit's rejection is honoured, not reversed.** Its reason — one resilience pass, one
  error mapping, one metric, one cache — stays true, because every client runs on the one filter
  chain and the one `BackendErrorMapper`; only the sentence "exactly one class" changes (arc42 §4.1,
  ADR-0032 amendment).
- **DTO mirrors stay hand-written** (they are an output allow-list: a new backend field does not reach
  the browser until someone mirrors it); the three contract tests key on a marker annotation instead
  of the package. The 72 untyped `Map` responses and 78 `Map` request bodies are typed per domain.
- **Session values.** Session-bound types (about eleven flashed forms and DTOs) are enumerated
  exactly, derived by a closure test, instead of admitted by the `frontend.model.` prefix — which
  narrows the admitted application classes from 325 to about 20 (decided 2026-10-01, D-10).
- **Templates and assets** move into per-domain folders; `Roles` stays in a stable kernel package or
  becomes a template bean; scripts get IIFE namespaces per domain and an ESLint
  `no-implicit-globals` ratchet; a boundary text test forbids one domain's page from linking another
  domain's stylesheet or reading its `window.*` API outside an allow-list.

### 5.10 REST API

The full per-domain cut (D-03) follows eight principles: one resource root per domain; domain first,
audience second (`/api/v1/<root>/admin/**`); member-scoped resources in their owning domain
(`/api/v1/<root>/me/…`); cross-domain read models as filters on the owner's collection; no shape
suffixes (`/slim`); one collection `GET` with typed filters plus `/lookup`; a frozen tier T0 that
never moves; every wave one app release, one deploy and one floor raise. The concrete cut per domain,
with the Android operations, edge allow-list lines, probes, E2E, frontend call sites and security
deltas of each move, is the [REST API appendix](modularisation/rest-api-cut.md).

**Contract tiers.** Every operation carries a tier: **T0** never breaks — the version gate
`GET /api/v1/app/version-policy`, `POST /internal/discord/account-existence` (the SPI fails open if
it disappears), the 14 exchange relay operations, and by recommendation the SSE streams; **T1** is
the Android contract — it breaks only in a declared hard-cut wave; **T2** is web-only — it changes
freely with the atomic deploy, guarded by the frontend contract tests.

**The forced update, step by step.** The minimum version (`app.android.minimum-version-code`) is
bound once at backend start from `APP_ANDROID_MINIMUM_VERSION_CODE`, which today exists only in the
host `.env`. `deploy.sh` renders that into the backend's environment only when the config bundle
changes, on `--reapply`, on a fresh host and on a rollback — not on every tick — and a rendering
deploy restarts the backend only when its unit or image changed. Whether every release carries a new
config bundle is not verified, so nothing guarantees that a floor written into `.env` rides a
release. The procedure is runbook step S8 of `docs/EXCHANGE_GO_LIVE_RUNBOOK.md`: set the value,
render, restart the backend — a production write with the owner's approval and about a minute of web
and app outage. The order matters: raising the floor together with the release is unsafe, because a
health-gate rollback re-renders the environment from the same `.env` and brings the old backend back
with the raised floor, and then no app version works. The safe sequence per wave is therefore:
publish app N+1 → deploy the re-cut release (the edge is reconciled with the new allow-list at the
end of the same deploy) → verify it healthy → raise the floor (S8). Its gaps: between the deploy
and the floor raise, old apps run against the new API without the wall; the app reads the policy
once per process and fails open, so apps already running keep going until their next cold start.
The owner therefore decided (2026-10-01, D-11) on a **release-bound floor** — the minimum version as
a reviewed default in the release's own configuration, so it deploys and rolls back together with
the API it protects, with the host value kept only as an emergency override — together with the
app's re-read on resume and after an unexpected 404 and the `APP_UPDATE_REQUIRED` answer of retired
paths. The S8 sequence above holds only until the release-bound floor is in place. *Implemented
2026-10-03 (REQ-API-020): the floor is a literal in the backend's `application.yml`, the host keeps
only `APP_ANDROID_*_OVERRIDE`, and retired paths answer `410 APP_UPDATE_REQUIRED`.*

**Machinery the cut needs first** (Phase 0): ADR-0136, REQ-API-001, REQ-API-009 and REQ-API-010
amended for the hard cut; a declared-break ledger that lists every removed or changed frozen
operation and field with the app version that absorbs it; an edge include generated from the
contract set, keyed on method and path, tested in both directions; one OpenAPI tag per domain with
unique schema names; the error-code registry; an app-published list of the calls each release makes.

### 5.11 Ingest and keycloak-spi

- **Ingest** is re-packaged by concern (`edge`, `auth`, `gate`, `limits`, `idempotency`, `relay`,
  `handoff`, `contract`, `web`, `observability`, `config`) with ArchUnit rules: no package cycles,
  only `relay` makes outbound HTTP calls, only four named packages touch Redis. Behaviour stays
  byte-identical (the exchange is frozen), proven by a golden-answer test per route recorded before
  the move.
- **The backend half of the frozen exchange** gets a wire-contract test against the 28 published
  schemas and 101 fixtures, a parity family for every identifier the two modules share (relay
  headers, gate codes and statuses, relay paths, capability scopes, the registry mirror document —
  whose reader today silently falls back when a field is renamed — revocation and handoff keys),
  and its own request DTOs where the draft routes reuse web-import DTOs today.
- **keycloak-spi** gets three sub-packages, a test that every service registration loads, and a
  repository check that ties its compile version to the Keycloak image tag.

## 6 Security guarantee: how the refactor avoids introducing weaknesses

The owner's condition is that no work of this plan may introduce a security weakness of any kind.
Moving code is where that could happen quietly: many of today's gates identify their subject by a
package name, a class name or a path, and after a move they still pass — because they no longer see
what they were written for. The guarantee therefore rests on four rules.

1. **Guards first.** No class, controller, template, script or path moves before the guards of the
   table below are in place and green on today's code. Every re-keyed rule is proven able to fail
   once (a planted violating fixture), the pattern the repository already uses for its log-masking
   gate.
2. **Mechanical moves only.** A move pull request changes packages, imports and the string
   references of the move checklist, nothing else. Behaviour changes ship in their own pull request.
   The authorization matrix (G-02) and the route/gate snapshot (G-13) must be byte-identical across a
   move; any diff is a reviewed, intended change.
3. **One domain per pull request**, each with the security review of the wave checklist below.
4. **Red lines** that no step crosses: audit never becomes an after-commit event; the scope check of a
   write never moves after its lookup (no existence oracle, REQ-INV-032); no event payload carries
   free text or personal data; the frontend session allow-list is never widened to a broader prefix;
   no `@Cacheable` on a per-user HTTP client; no HTTP client built outside the one filtered
   `webClient`; no module declared `OPEN` except a legacy layer package during the transition; no
   security rule is ever frozen in a baseline.

### 6.1 Guards (all in Phase 0 unless noted)

| ID | Guard | Closes |
| --- | --- | --- |
| G-01 | Re-key the backend ArchUnit rules: select by role (`@RestController`, `@Service`, Spring Data `Repository`, `@Mapper`, `@Entity`) instead of layer packages; name targets by class literal instead of FQCN strings; a meta-test that every remaining FQCN string resolves; a selection floor per rule equal to today's count; `allowEmptyShould(true)` replaced by floors; the support-leaf rule turned from a deny-list of layer packages into an allow-list; a second cycle rule for layers inside modules | 30 to 33 of 43 rules that would pass silently after a move (ArchUnit fails an emptied selection, but not a rule whose target string no longer resolves), among them the `permitAll` allow-list, the read and write gate rules, the `SecurityContextHolder` rules, the audit-write rule, the mass-assignment rule and bank org-unit blindness |
| G-02 | **Authorization matrix golden file** (`backend/src/test/resources/api/authorization-matrix.txt`): one sorted line per operation with verb, path, handler, effective `@PreAuthorize` and the matching URL rule | Every silent loss of a gate in a move or re-cut |
| G-03 | Lift the 15 URL-rule-only role gates (13 under `/api/v1/inventory/**`, 2 under `/api/v1/hangar/**`) into their annotations, with a test that isolates each URL rule | A path move silently dropping the role requirement — not exploitable today, because the default realm role gives every account the member role, but gone the day an account without it exists |
| G-04 | **SpEL bean-reference test**: parse every `@PreAuthorize`, resolve each `@bean.method(…)` against the context by name and arity, ratchet on the reference count; explicit bean names for the six beans that have none; forbid non-constant SpEL | A renamed security bean turning every gated call into HTTP 400 with no alert |
| G-05 | Replace the simple-name whitelists of the staffel-scope rules with a `@TenantScoped` marker on scoped aggregates and a test that every entity with an owning or responsible org unit carries it | A split or re-cut controller escaping the tenancy gate |
| G-06 | Structural mass-assignment rule: a type returned by any mapping is never a `@RequestBody`; server-managed fields (`id`, `owner*`, `*OrgUnit*`, `status` outside transition endpoints) never in request records; every write body `@Valid` | Dual-use DTOs turning read fields into writable ones |
| G-07 | Path-keyed controls made self-checking: every non-GET mapping inside the CSRF exemption (`/api/v1/**`, `/internal/**`); every GET of a sensitive family answering `private, no-store` (runtime test over the handler mappings); every rate-limit rule and export segment matching at least one operation; pending, terms and acting-member exemptions pinned to exact sets — the terms gate exempts `/api/v1/terms/**` as a pattern today, so a `/terms/admin` sub-tree would escape it; no mapping outside `/api/**`, `/internal/**` and `/error` | Silent loss of CSRF handling, `no-store`, rate limits and gate exemptions after a re-cut (the test profile disables CSRF, so only an E2E flow — which runs the `dev` profile with CSRF on — that happens to call a moved path would notice) |
| G-08 | Edge include generated from the contract set, keyed on method and path, no prefix rules, tested so that admitted equals frozen plus the two anonymous reads — built only after the frozen set has been completed from the app's call list (G-23), because the app calls ten operations the frozen set lacks and an include generated from today's set would break it | A moved path becoming public under `^/api/v1/me/` or `^/api/v1/terms/`; writes slipping past the read-only family |
| G-09 | Domain map plus Modulith verification plus frozen ArchUnit module baseline; "every class belongs to exactly one module"; "simple names unique" | New cross-domain coupling while the refactor runs |
| G-10 | Table-ownership test; trigger-column test for the four change-feed tables | Native SQL and triggers crossing modules unnoticed; a renamed column breaking inserts only at runtime |
| G-11 | GDPR coverage tests compare the union of registered participants with `information_schema` | A module without an eraser leaving personal data behind |
| G-12 | Observer and listener rules: observer SPI implementations are `MANDATORY`; no `@TransactionalEventListener` or `@Async` method calls the audit recorder; listeners never use the request-bound scope helpers | Atomicity and audit completeness lost by a well-meant "event" |
| G-13 | Frontend route/gate snapshot (verb, path, effective `@PreAuthorize`, layout opt-in) and a per-handler gate rule | A handler moved between controllers losing an inherited class-level gate |
| G-14 | Frontend backend-call existence test: every resolved call names an operation of the committed `openapi.json`, and every live-sync probe template a `GET` | A frontend call or a live-sync probe pointing at a retired path — the probe authorizer fails open on 400/405/5xx for non-presence topics |
| G-15 | Template `T(…)` and view-name resolution tests | A moved `Roles` class failing role-gated pages at render time |
| G-16 | Session allow-list closure test (exact list of session-bound types) and a test that no entry is broader than a model or session package | Flash values silently dropped after a move; a widened deserializer gadget surface |
| G-17 | `WebClient` confinement: only the backend kernel builds or injects clients (named exceptions: the SSE relay and the live-sync probe); every typed client is created from the `webClient` bean (MockWebServer test asserting `Authorization`, `X-Correlation-Id`, `X-Active-Org-Unit-Id`, `Accept-Language` and breaker behaviour); no `URI` or `UriBuilderFactory` parameters and no absolute URL in an `@HttpExchange` annotation; no `@CookieValue`; no `@Cacheable` on clients; and a filter on the `webClient` bean that refuses any request not addressed to the backend's own host — the OAuth2 filter attaches the member's token without a host check, which today already applies to any absolute URI passed to that bean | Lost bearer relay, lost tenancy header, lost resilience, token exfiltration to another host, cross-user cache leaks |
| G-18 | Exchange freeze: backend wire-contract test against the published schemas and fixtures; parity tests for every shared identifier; ingest filter-order test via `FilterChainProxy`; golden answers per route; the `e2e` label required for pull requests touching the exchange path | A backend refactor changing external behaviour, found today only in production as a 502 |
| G-19 | Cached-entity invariant test for every `@Cacheable` catalogue service; target state: caches hold read models and an ArchUnit rule forbids `@Cacheable` returning an entity | A query/command split letting a mutator edit the cached instance |
| G-20 | Build wiring keyed on names made explicit: per-module coverage floors, test heap and mutation targets as typed settings whose absence fails the build; PIT gated on its completion line; repository check that every `inputs.file(s)` path exists; the two non-recursive frontend tests walk recursively; `DtoMirrorConsistencyTest` fails on an unpaired mirror | Coverage floors dropping from 0.82 to 0.50, mutation testing going dark, contract tests skipping moved DTOs |
| G-21 | Deploy refuses a `:stable` set whose images do not share one `org.opencontainers.image.revision` label (compared on cosign-verified digests); promotion verifies all five and then re-tags all five | A mixed release in which every call of a re-cut domain answers 404 |
| G-22 | `toString` ratchet: record components and generated-`toString` fields named like secrets must belong to a reviewed list of redacting types | Credentials in log lines after a Lombok → record conversion |
| G-23 | Declared-break ledger and app call-list comparison (Phase 0 of the API track) | A hard-cut wave breaking more of the app contract than declared |
| G-24 | Context-shape ratchet: counts of scheduled tasks, transactional event listeners, controllers, security filter chains (before any Gradle extraction) | A module outside the scan root losing its beans silently (Phase 5) |
| G-25 | No method calls a `@PreAuthorize` method of its own class; the 18 service-level gates move to the controllers or stay pinned by this rule — for the finance-entry and promotion-category writes the service annotation is today the only real gate | A merged facade or a moved caller turning a gated call into a self-invocation that skips the check |

### 6.2 Checklist for every wave

A pull request that moves or re-cuts a domain states, and its reviewer confirms:

- the authorization matrix and the route/gate snapshot are unchanged, or every changed line is
  intended and named in the description;
- no new anonymous path, nothing newly admitted by the edge, the `no-store` and rate-limit tests
  green, CSRF exemption unchanged;
- the domain's tenancy gate and its list query moved together and the differential verdict test
  passes;
- every operation moved under a parent path is still authorized on its child resource, and a test
  per nested write proves that a child of another parent answers 404;
- every mutation of an audited area still records its event in the same transaction (REQ-AUDIT-001,
  the audit contract per command);
- redaction selection floors unchanged (`MissionPeerRedactor`, owner redaction);
- the exchange golden answers and contract tests green;
- the move checklist of string references is complete (templates, session list, SpEL, ArchUnit
  targets, build inputs, JPQL constructor expressions and enum literals, `spring.factories`);
- the `/security-review` of the branch is clean, and CodeQL, SpotBugs with FindSecBugs, the E2E
  suite and the dependency scan are green.

### 6.3 New dependencies

Every framework the plan adds passes the existing supply-chain gates: GPL-compatible licence
(Licensee), a regenerated `gradle/verification-metadata.xml` with an empty `GRADLE_USER_HOME`
(ADR-0208), the SBOM, the vulnerability scan. Spring Modulith stays in test scope, so nothing is
added to an image; Error Prone and NullAway (D-13) are compile-time only. OpenRewrite is
run from an uncommitted init script with the last freely published versions and never becomes part
of the build (§10).

## 7 Migration path

The plan runs as numbered phases for the backend and four tracks in parallel (frontend, REST API,
assets, modern language features). A phase starts when its prerequisites hold, not on a date. Every
step is its own pull request or a short series, independently shippable and reversible.

### 7.1 Phase −1 — the defects of §9, fixed separately

**Done** — merged 2026-10-01 in PRs #2299–#2312 (released with v1.13.5). C-03, a
repository-settings change, is done too: `Self-tests` and `Container checks` are required checks
on `main` (*corrected 2026-10-04*).

Small, independent pull requests before anything else (D-07). They remove traps the refactor would
otherwise trigger (the URL-rule-only gates, the dual-use refinery DTO) and close the defects the
audit found.

### 7.2 Phase 0 — decisions and guards

| Step | Content | Why first |
| --- | --- | --- |
| 0.1 ADRs — **done 2026-10-02** | New (ADR-0231 … ADR-0239): target architecture C and module rules; module interaction styles (§5.3); enforcement tooling (ArchUnit + Modulith in test scope, frozen module baseline); hard cut with forced update and the release-bound minimum version (D-11); error model (D-09). Amended: ADR-0047 (module cycles frozen, layer cycles per module), ADR-0065 (access policies per domain), ADR-0020/0028 (seam rules re-keyed — owner approval), ADR-0032 (single filter chain, typed clients), ADR-0136 (retirement by hard cut), ADR-0206 (exact session list, D-10), ADR-0223 (corrections, §8.1), and the further amendments of §14 | CLAUDE.md: a requirement is amended before code diverges from it |
| 0.2 Backend guards — **done 2026-10-03** | G-01 … G-07, G-10, G-12, G-19, G-22 | The refactor must not be able to weaken a gate unnoticed |
| 0.3 Module map — **done 2026-10-03** | Domain map as a checked-in artefact; ArchUnit `modules()` rule plus `FreezingArchRule` baseline (150 violations as jdeps counted them; ArchUnit's baseline holds 138 edges in 42 module pairs, corrected 2026-10-03); Spring Modulith spike (`verify()` under ArchUnit 1.5.1, `explicitly-annotated` detection, `Documenter` output) and then `ModularityTest` | Stops new coupling from day one; measures progress |
| 0.4 Frontend guards — **done 2026-10-03** | G-13 … G-17; `ParallelPageLoader` on `ContextSnapshotFactory` (fixes the missing locale relay); the three kernel shapes (byte download with headers and per-call timeout, multipart upload, `Flux`) so the eleven bypassing controllers return to the kernel | Typed clients and the package move inherit whatever the kernel does |
| 0.5 Exchange guards — **done 2026-10-03** | G-18; ingest re-package by concern (§5.11); keycloak-spi registrations test | The only contract that must not move |
| 0.6 Build and CI guards — **done 2026-10-03** | G-20, G-21; `@SpringBootTest` profile unification (BLD-PERF-03, 191 of 231 classes carry `@ActiveProfiles("test")` although Gradle forces it) | Test-context count and coverage floors decide how painful every later step is |
| 0.7 API machinery — **done 2026-10-03**; the app's re-read shipped with app v0.5.0 (basetool-android#209, published 2026-10-03) | G-23 (the app's call list first, then the declared-break ledger), then G-08; one OpenAPI tag per domain; unique schema names; generator assertions; error-code registry; mandatory contract baseline on `main`; the exchange fence (the relay surface in its own internal OpenAPI document, REQ-XCH-039); the app re-reads the version policy on resume and after an unexpected 404, shipped **before** the first cut (D-11) | The first hard-cut wave depends on all of it |

### 7.3 Phase 1 — behaviour-free inversions and re-homings

Cheap moves that break many cycles without changing behaviour:

- Leadership classes into `orgunit` (writes) and `orgchart` (the Leitung view); `PayoutPreference`
  into `identity`; handle anonymisation into the kernel; the exchange row records into their owners'
  repositories; a catalogue `ShipTypeMapper`; `AuthHelperService`'s four delegations to
  `OwnerScopeService` removed (callers use the scope API); `AuditService.record` returns nothing
  instead of the entity — **done 2026-10-04** (P1-7): no caller used the row; the two
  `UexRefinerySyncService` calls that handed it through `SyncChunkWriter.inNewTransaction(Supplier)`
  now use `runInNewTransaction(Runnable)`.
  The re-homings P1-1 … P1-6 are **done 2026-10-04**, and `kernel` (base package, allowed by bare
  name) and `orgunit` (`orgunit.api` its named interface, `orgunit.web` internal) are declared
  Spring Modulith modules under REQ-MOD-006; the module baseline shrank by 9 edges, from
  136 to 127 (37 module pairs):
  - P1-1: `BereichLeadershipRole` moved to `orgunit.api`, `GrandAdmiralRequest` and
    `AddBereichLeaderRequest` to `orgunit.web` (the latter had to move with the enum, or
    `model ⇄ orgunit` would have closed a package cycle). The Leitung view (`Leitung*`) is now
    assigned to `orgchart`; it was mapped to `orgunit`. The org chart sits behind the observer SPI
    `orgunit.api.MembershipChangeObserver` (`@ObserverSpi`), which `OrgChartService` implements with
    its nine `MANDATORY` mirror methods, so `OrgUnitMembershipService` and `KommandoGroupService` no
    longer know it (−2 edges). *Correction:* `AreaLeadershipDto` cannot move alone — it nests
    `OrgChartNodeDto` and is nested by `OrgChartDto`/`BereichChartDto`, so a lone move closes a
    `model ⇄ orgchart` package cycle; it moves with the org chart's DTOs in Phase 2 and stays
    assigned to `orgchart` by its `class` rule until then.
  - P1-2: `PayoutPreference` stays in `model`, assigned to `identity` by its `class` rule.
    *Correction:* a physical move to `identity.api` closes an `identity ⇄ model` package cycle
    (the `User` entity uses it, and `identity.api.events` uses `model` types), so it moves with
    `User` in Phase 4.
  - P1-3: `HandleAnonymisation` and `HandleScrubber` moved to `kernel`. *Correction:*
    `HandleSpellings` reads the `User` entity, so the kernel cannot hold it; it stays a `privacy`
    class until the GDPR participants of §7.6.
  - P1-4: the five exchange row records are nested in the repositories that produce them
    (`ShipRepository`, `BlueprintRepository`, `GameItemRepository`, `LocationRepository`,
    `InventoryItemRepository`); the JPQL constructor expressions name the binary nested-class name
    (`…Repository$ExchangeShipRow`) (−5 edges).
  - P1-5: catalogue `ShipTypeMapper`; `ShipMapper` and `MissionMapper` use it, `HangarService`
    no longer needs `ShipMapper` (−1 edge).
  - P1-6: `AuthHelperService` lost `currentSquadronId`, `canSeeSquadron`, `canEditSquadron`,
    `canEditOrgUnit` and its `ApplicationContext` lookup (−1 edge). *Correction:* none of the 18
    `@authHelperService` SpEL references used them (all are `isMemberOrAbove()`), so no SpEL and no
    authorization-matrix line changed. `JobOrderService` and `MaterialClaimService` call
    `OwnerScopeService` directly; `UserController`'s cross-squadron check moved into
    `UserService.isCrossSquadronForNonAdmin`, because a controller call would have added an
    `identity -> scope` edge.
- The platform SPIs of §5.3 (`ActorHandleResolver`, `RetentionParticipant`, `RecipientDirectory`,
  `LiveSyncTopicAuthorizer`, `ActiveOrgUnitProvider`) — they remove 21 upward edges.
  **Done 2026-10-04** (P1-8). The module baseline shrank by 15 edges, from 127 (after the error
  model and the re-homings) to **112 edges**, and from 37 to 30 module pairs (`platform -> scope`
  left entirely, its other edge having gone with the `AuthHelperService` delegations): `ActorHandleResolver` 2 (`audit -> identity`), `RetentionParticipant` 2
  (`audit -> bank`), the recipient directories 8 (`notification -> bank` 4, `-> identity` 3,
  `-> orgunit` 1), `LiveSyncTopicAuthorizer` 2 (`livesync -> scope`, `-> bank`),
  `ActiveOrgUnitProvider` 1 (`platform -> scope`). **Correction:** 15 edges, not 21. The 21 are
  all upward edges of the rank-0 and rank-1 modules in the baseline, and six of them are not SPI
  cases: `notification -> orgunit: OrgUnitRef -> OrgUnitKind` waits for a kernel `OrgUnitKind`
  (P1-11), `platform -> scope: AuthHelperService -> OwnerScopeService` leaves with the removal of
  its four delegations, the two `platform -> exchange` edges of `ClientAttribution` need their
  own move, and the two `kernel -> platform` edges left with the error model (§5.5).
  `RecipientDirectory` is three interfaces in `notification.api`, one per implementing module (`RoleRecipientDirectory` identity, `OrgUnitRecipientDirectory` orgunit,
  `AccountRecipientDirectory` bank); the role-code check of `NotificationRuleService` uses the
  first, which removed two edges §5.3 had not listed. `ActiveOrgUnitProvider` lives in the
  `service` package beside `AuthHelperService` (a `logging` package would close a
  `logging -> service -> logging` layer cycle). None of the SPIs is an observer, so the G-12 rules
  do not select them; the audit and retention calls keep their transactions (`record` stays
  `MANDATORY`, each purge its own transaction).
- `support` split into the kernel (`RequestMemo`, `StringNormalization`, `OptimisticLock`,
  `LikePatterns`, `ProblemResponseFactory`, `Roles`, `Permissions`) and per-domain internal packages;
  the ArchUnit leaf rule's message stops sending shared logic there.
  **Done 2026-10-04** (P1-9). All 61 classes left `support`; the package is gone (two test-only
  fixtures, `BoundProperties` and `QualityTierFixtures`, keep the test package `support`). The
  kernel took the seven named classes plus `Quality` (already kernel in the domain map) and
  `AppProblemProperties`, which `ProblemResponseFactory` reads — without it the factory would
  have added a `kernel -> platform` edge. A new `platform` module package took the access core
  and the request settings (`platform.api`: `AuthenticatedSubject`, `SubjectAuthentication`,
  `OrgUnitContextualAuthority`, `ClientAttribution`, `RefusedSubjectWindow`,
  `ResilientRedisMessageListenerContainer`, `AuthoritiesCacheProperties`,
  `PartialRoleScopeProperties`, `RateLimitProperties`; `platform.internal`:
  `ApiClientMetricsProperties`, `RequestBodyLimitProperties`). Every other class went to
  `<module>.internal`, or to `<module>.api` when another module uses it (inventory, livesync,
  identity, joborder, orgunit, exchange, catalogue). Two more module packages followed:
  `catalogue` (`api.QuantityTypeRounding`, internal UEX helpers) and `mission` (section versions,
  peer redaction, viewer-access SPI — all internal). Seventeen modules are declared now.
  `ClientAttribution`'s two `platform -> exchange` edges were inverted through a platform SPI,
  `platform.api.ClientDirectory`, implemented by `exchange.internal.ExchangeClientDirectory`,
  because a declared `platform` module may not reach the exchange: the module baseline shrank
  from **112 to 110 edges** and from 30 to 29 module pairs. No other class changed its module,
  so no other line moved. The ArchUnit leaf rule is re-keyed by class literal
  (`ArchitectureTest.LEAF_HELPER_CLASSES`, floor 63 = the 61 moved classes and the two SPI
  types) with the same allow-list (the helpers, the entity model, the repositories); arrays of a
  helper count as the helper, which the package-keyed rule had matched implicitly.
  **Corrections:** (1) §5.1 places `Roles`, `Permissions` and the request memo in `platform`,
  this step in the kernel; the code follows this step — they are constants and a
  request-attribute memo with no domain meaning, and every module may depend on either rank-0
  module, so the choice moved no edge. (2) `mission` has no module API yet, so it is the first
  module that publishes nothing: `ModularityTest` requires every type below its base package and
  outside an `api` package and keeps it out of every `allowedDependencies`, instead of demanding
  an empty `api` package. (3) `catalogue`, `mission` and `platform` had no package before; each
  enters the allowed dependencies of every module above it (`platform::api` everywhere,
  `catalogue::api` from rank 3 up).
- `audit.api` created (enum and recorder moved, names unchanged); event records moved into their
  publishers' `api.events` packages; the error model of §5.5 — **done 2026-10-04** (P1-12, see
  §5.5).
  `audit.api` is **done 2026-10-04** (P1-10): `AuditEventType`, `AuditDomain` and `AuditDetails`
  moved unchanged; the recorder is the interface `audit.api.AuditRecorder` (`record`,
  `recordedSince`), implemented by `AuditService`, which stays internal with the entity, repository,
  viewer and purge. Moving the class itself would have carried its two `audit -> identity` edges
  (actor handle) into the API; they leave with `ActorHandleResolver`. All 57 recording classes
  outside the audit module inject `AuditRecorder`; the listener and controller audit rules key on
  `AuditRecorder` as well, without which the move would have disarmed them. The module baseline
  stayed at 138 edges: the moved types were already assigned to `audit` by name, and audit's own
  edges sit in `AuditService` and `AuditRetentionService`. The `@ApplicationModule` declarations
  followed with P1-13 (below), once `spring-modulith-api` was a `compileOnly` dependency.
  The event records are **done 2026-10-04** (P1-11): the 19 types of the central `event` package
  moved, names unchanged, into the `api.events` package of the module of the service that publishes
  them — `identity` (`DiscordRegistrationPendingEvent`, `UserApprovalDecidedEvent`,
  `MemberDepartedEvent`), `privacy` (the three `AccountDeletionRequest…Event`s, published by
  `DeletionRequestService`), `bank` (the booking-request interface and its four records),
  `joborder`, `materialexchange` and `exchange` (two each), and `notification` (`NotificationEvent`
  and `OrgUnitRef`). The `event` package is gone. **Correction to §5.1:** `OrgUnitRef` does not go
  into the kernel yet: it carries `OrgUnitKind` (orgunit, rank 4), so a kernel `OrgUnitRef` would
  replace the frozen `notification -> orgunit` edge with a new `kernel -> orgunit` one, and an
  `orgunit.api` one would add two edges from `NotificationEvent` and `RuleEvaluationService`. It
  stays with the notification contract until `OrgUnitKind` itself is a kernel type. The domain map
  trades six class rules for one `package <module> <module>` rule per new module package; every
  type keeps its module, so the baseline stays at 138 edges. The ArchUnit event-payload rule
  (`eventLayerShouldNotDependOnServiceLayer`, floor 19) is re-keyed from the package tree of
  `NotificationEvent` to every module's `api.events` tree — left on the anchor, it would have
  selected two classes and failed its floor — and `event` left the layer-package names.
- The Spring Modulith module declarations (P1-13, §5.2, §5.7) are **done 2026-10-04 for every
  module package on `main`**, and the step grows with each later move: a move that creates a module
  package adds its declarations in the same pull request, and `ModularityTest` fails until it does.
  `spring-modulith-api` is a `compileOnly` dependency of the backend's main source set — annotations
  only, absent from the boot jar, the image and the SBOM, so D-02's "test scope only" holds for
  everything that runs. Eleven modules are declared: `audit`, `bank`, `exchange`, `identity`,
  `inventory`, `joborder`, `materialexchange`, `notification`, `privacy`, `refinery` and `scope`.
  Each root `package-info` carries `@ApplicationModule` (closed), each `api` package
  `@NamedInterface("api")`. `allowedDependencies` is exact for every module: the `<module>::api` of
  each declared module that the domain map's ranks and `allow` rows permit, so `audit` and
  `notification` (rank 1) allow nothing, and `ModularityTest` fails when a declaration differs from
  what the domain map derives. `verify()` is green on six real cross-module edges today (bank,
  exchange, identity, joborder, materialexchange and privacy → `notification::api`, the
  `NotificationEvent` contract). The module baseline is unchanged at 138 edges: no class moved.
  **Corrections to §5.2 and §5.7**, found with the planted fixture: `@NamedInterface` on a package
  covers that package only — its `propagate` attribute applies to annotated types — so every package
  below `api` (today the seven `api.events`) carries `@NamedInterface("api")` too, which Modulith
  merges into the one `api` interface; an allowed dependency written as the bare module name admits
  only the module's unnamed interface, so a module with an `api` interface is allowed as
  `<module>::api`; and an allowed dependency that names an undeclared module makes Modulith throw,
  so `kernel` and the other rank-0 modules enter the lists only once they are declared.

**Pros** many cycles gone before any domain moves; each step is small. **Cons** broad, shallow
churn. **Risks** SpEL bean names and FQCN references (guards G-01, G-04 catch them). **Effort** M.

### 7.4 Phase 2 — the leaves

Modules that almost nobody depends on move first, each taking its access policy out of the scope
hub where it has one: `dashboard` (0 inbound edges), `admin` (4), `promotion` (1),
`personalinventory` (1), `orgchart` (behind the membership observer), `operation` (11, behind the
mission detach command), then `exchange` (11) and `bank` (18) as packages. Exchange and bank enter
their "must stay green" period here (D-01).

### 7.5 Phase 3 — the business core

In dependency order, each with its command API, its observer SPIs, its access policy and its REST
wave: `materialexchange` (the offer ratchet as `StockChangeObserver`), `refinery`, `joborder`,
`mission`, `inventory` (`StockCommands`, the earmark target SPI, the lot-lock protocol of ADR-0229
moved into inventory), `hangar`, `blueprint`. The eight business associations become id references
as each pair is decoupled.

| Core step | Risk that matters most | Guard |
| --- | --- | --- |
| Inventory command API | Lock order (advisory lock first, ascending by key), bulk update after the loop, the `FORCE_INCREMENT` version echo | Existing concurrency and E2E tests (`MaterialCollectionDeliveredInPlaceE2eTest`), the ADR-0229 load-test cases, "only inventory writes `InventoryItem`", an audit contract per command, a 403 test per foreign entry point |
| Earmarks (inventory ⇄ joborder, mission) | The deliberately ungated order-linked stock query must stay callable only from joborder behind its gate | ArchUnit rule on the caller, the REQ-ORDERS-029 redaction tests |
| Mission inversions | `Mission` is `@DynamicUpdate` with per-section counters; foreign writes today bump no counter | Keep that verbatim or decide it in an ADR; mission concurrency suites; provider summaries pass `MissionPeerRedactor` |
| Access policies moving out of `AccessGateService` | A lost escape or a changed verdict leaks or hides rows silently | The differential verdict test of §5.4 per domain |

### 7.6 Phase 4 — the foundation

`scope` (the kernel that remains after the policies left), `orgunit`, `identity` with the GDPR SPIs
(`UserErasureParticipant` with ordered phases REASSIGN, UNLINK, DELETE; `UserReassignmentParticipant`;
`HandleAnonymisationParticipant`; `PersonalDataExportSection`; `PersonMentionSource`) — participants
run `MANDATORY` inside the orchestrator's one transaction, and the deletion and anonymisation audit
rows stay in the orchestrator — and `catalogue`, whose caches switch to read models. Identity's REST
surface sheds the six domains it hosts today.

### 7.7 Phase 5 — Gradle modules for exchange and bank

Right after Phases 2–4 (D-23, which drops the soak of some releases): `build-logic` convention
plugins, typed per-module settings, then `backend-exchange` and `backend-bank` (§5.8). Every other
domain stays a package.

### 7.8 Frontend track

| Step | Content |
| --- | --- |
| F0 | Guards (Phase 0.4) |
| F1 — **done 2026-10-04** | Kernel extraction: `BackendErrorMapper` (a sealed outcome type with a pattern switch), template overloads for write verbs, the eleven bypassing controllers moved onto the kernel, the `WebClient` confinement rule at zero |
| F2 — **done 2026-10-04** | Exact session allow-list in its own release (D-10): `SessionTypeAllowList.SESSION_BOUND_TYPES`, 21 exact names; the `…frontend.model.` prefix is gone, `SessionBoundTypeClosureTest` holds the list equal to the derived set in both directions. Corrections: the list lives in the security class, not in the G-16 golden file (`session-bound-types.txt` is deleted, so `-PupdateSnapshots` can no longer widen the allow-list); the admitted application classes fall from 325 to 21, not "about 20" of "about eleven" forms — the 21 are 10 flashed forms and DTOs with their nested types and enums |
| F3 | Typed client per domain, small domains first (audit, notification, settings, dashboard, exchange, orgchart), then the large four; untyped `Map` relays typed in the same step |
| F4 | Package-by-domain move in one pull request; route/gate snapshot byte-identical |
| F5 | Templates and assets per domain (§8.2), with the page chrome as one layout fragment |

*F1 as built (2026-10-04).* `service.BackendErrorMapper` classifies every failed call into one case
of its sealed `Outcome` (`Problem`, `Reauthentication`, `CircuitOpen`, `BulkheadFull`, `Timeout`,
`Unexpected`) and maps it in one exhaustive switch, writing to `BackendApiClient`'s logger so log
categories, levels, messages, statuses, problem codes and the `basetool_backend_client_errors_total`
labels are unchanged; `BackendErrorResponses` parses raw refusals through it. Every write verb has a
URI-template twin, and the 235 write calls that concatenated an id, plus the bank proxies' helper
calls, pass their values as template variables; `WriteUriTemplateTest` (with a planted fixture and
a floor of 344 write sites) keeps them there, with one reviewed exception, the UEX override's
allow-listed entity kind. The two remaining `WebClient` holders outside the kernel — the SSE relay
and the live-sync probe — now call `service.BackendSideChannels`, so `WebClientConfinementTest`
names only kernel classes and `ArchitectureTest#noControllerHoldsARawWebClient` has no allow-list.
Corrections: the eleven bypassing controllers had already returned to the kernel with Phase 0.4,
so F1 only verified it; the "zero" in this row was reached by moving the two named
exceptions into the kernel, not by removing them; the 130 concatenating GET call sites are left for
F3, where the typed clients replace them.

### 7.9 REST API track

**Delivery decided 2026-10-04 (D-24):** the waves below are built and merged one by one, but ship
together as one backend release and one app release, so the paragraph's per-wave release cadence
is replaced by a single cut. Each wave is one domain's cut ([appendix](modularisation/rest-api-cut.md)), shipped together with the
backend and frontend change of that domain in one release (frontend and backend deploy in one
restart window), with a new app release published first and the minimum version raised once the
re-cut release is verified healthy — or, once the floor is release-bound (D-11), by the release itself.
Suggested order by risk and app impact: web-only moves first (admin sub-trees, notification rules,
the demonstration ping), then identity and org units, mission, bank, job orders and the game-item
catalogue, and the small rest (Materialbörse, hangar, refinery, typed settings). The T0 tier never
moves; the exchange relay surface is fenced as its own internal OpenAPI document (done 2026-10-03,
`exchange-relay.openapi.json`, REQ-XCH-039).

### 7.10 Effort

Roughly 25 to 35 pull requests for the backend phases, each small to large; the frontend and API
tracks add about one pull request per domain each. Phase 0 is the largest single investment and the
one that makes every later step mechanical.

## 8 Modern language and platform features

JDK 25 stays the toolchain until the next LTS: JDK 27 went GA on 2026-09-15 and is not an LTS, the
next LTS is Java 29 (September 2027), and Spring Boot 4.1.1 supports Java 17 up to 26. ADR-0223 (final
features only, no preview flags) stands; every proposal below uses final features.

### 8.1 Java

| Proposal | Pros | Cons and risks | Guard | Effort |
| --- | --- | --- | --- | --- |
| **Exhaustive enum switches.** Checkstyle's `MissingSwitchDefault` forces a `default` into all 20 switch statements; on nine of them every constant is already covered, so the `default` hides the next constant. Write them as switch expressions or with `case null ->`, which javac 25 checks for exhaustiveness and Checkstyle 14.3.0 accepts (probed). Policy: no `default` on a switch over a project enum unless it handles a deliberate subset (decided, D-15) | A new `BankAccountType`, `OrgUnitKind` or `SelectorKind` breaks the build at every decision site instead of shipping a 400, an exception or a silent no-op | `case null ->` is an unfamiliar idiom | The compiler; existing service tests | S |
| **Enum predicates instead of `==` chains.** `OrgUnitKind` is compared 43 times in 19 files; add behaviour on the enum as `switch (this)` without `default` (precedent `OperationStatus.canTransitionTo`), with a `values()` test per predicate; the same for `FinanceType` in payout arithmetic | Tenancy rules stated once, as the kernel's API | Each site's current fail-open or fail-closed behaviour must be kept exactly — one predicate per rule, never two rules merged | Access-gate and scope tests | M |
| **Sealed types where they are legal.** Exhaustive `switch` over the exchange's private sealed `Planned` types (two are read with `if/else` without `else`, so a new variant is silently dropped); name the last constant instead of `default` in three exchange switches; the exchange's resource and operation vocabulary as one enum mapped at the boundary (the mass-change capability is chosen from a string with `default -> HANGAR_WRITE` today, which Bean Validation happens to shield) | No silently dropped external write; no path can default to a capability | Must keep the external contract byte-identical | Exchange contract tests; a test that an unknown value never reaches the capability choice | S |
| **Never seal across packages or entities.** Sealed hierarchies stay inside one package (class-path rule) and never cover JPA entities (a Hibernate proxy cannot subclass a sealed class) | Avoids a refactor dead end | — | javac | — |
| **Unnamed variables `_`** (final since 22) for 107 unused lambda parameters, meaningless catch parameters and unused pattern components; empty catches too, with the ADR-0214 amendment and the Checkstyle `EmptyCatchBlock` pattern change this needs (decided, D-14); descriptive names stay where the name states intent; not in keycloak-spi (Java 21 bytecode) | The compiler forbids accidental use | About 260 edits in `main` | Compile and Checkstyle | S–M |
| **`ScopedValue`** (final in 25) for the backend's `ChangeSource.ON_BEHALF` `ThreadLocal`, which attributes exchange writes in the change feed; the frontend holders stay `ThreadLocal` (ADR-0223 decision 4) | A binding cannot leak into a reused thread | ADR-0223 amendment | `ChangeSourceTransactionManager` integration test | S |
| **Records** for the last three `@Data` request classes and one hand-written carrier; defensive `List.copyOf` only for records that become cached, shared or module-API values; redaction DTOs keep their canonical constructors | Immutability where values cross a boundary | `List.copyOf` rejects `null` elements | Contract tests; `toString` ratchet (G-22) | S |
| **Small idioms**: `Math.clamp` for 11 constant bounds, `getFirst()` in frontend and ingest, `Environment.matchesProfiles`, `Optional` chains instead of `isPresent()`/`get()`, text blocks for the 47 concatenated `@Query` strings and test JSON; keep `trim()`/`strip()` and `Collections.unmodifiable*` where they are deliberate | Consistency with the 90 % that already uses the modern form | Diff churn — batch into files the refactor touches anyway | Compile, tests | S |
| **Do not adopt** Markdown documentation comments (`///`, JEP 467): Checkstyle 14.3.0 does not treat them as Javadoc; gatherers: no loop here is clearer as one | — | — | — | — |
| **ADR-0223 corrections**: JDK 26 does add a final library feature (JEP 517, HTTP/3 for the HTTP client); JEP 510 (KDF) is final in 25; Checkstyle already enforces the module-import and compact-source bans in `main` (not in `test`/`e2e`); there are 42 `super(…)` calls, not 34; a `--enable-preview` gate is still missing | The decision record stays authoritative | — | Review | S |

**Nullness.** Error Prone 2.50.0 with NullAway 0.14.2 runs on JDK 25 and accepts the JetBrains
annotations by simple name; Spring Framework builds itself with the same pair. Starting with the new
module API packages, it would turn the annotations into a checked contract and close arc42 §11.4
("derived nullity annotations have no gate"). Cost: a compile-time dependency, about ten javac
`--add-exports`, slower compilation, Lombok interplay to be proven on one module. Decided 2026-10-01 (D-13).
JSpecify stays out for now (ADR-0192).

### 8.2 JavaScript, CSS, HTML

| Proposal | Pros | Cons and risks | Guard |
| --- | --- | --- | --- |
| **Document the browser baseline — decided: "Baseline 2025", ES2025** (D-16). **Done 2026-10-04**: TypeScript 7.0.2 accepts the `ES2025` lib (proven by a planted checked file), `tsconfig.json` and `eslint.config.mjs` are at 2025, the floor is in `ui-design-system.md` and REQ-FE-018, and ESLint rejects the three ES2025 APIs above the floor, which the `ES2025` lib declares. The features already shipped imply Chrome 105, Firefox 121, Safari 16.4; the decided floor is at least Chrome 122, Firefox 131, Safari/iOS 18.4 (iterator helpers; `Promise.try`, `RegExp.escape` and `Float16Array` need newer releases), which brings Set methods, iterator helpers, popover and same-document view transitions. Raise the type check's `lib`/`target` and ESLint's `ecmaVersion` from 2023 to 2025 once TypeScript 7 is proven to accept the `ES2025` lib; write the floor into `ui-design-system.md` and REQ-FE-018 | Every other modern-feature decision needs this answer; today a newer API in an unchecked file passes every gate | Members who cannot update iOS to 18.4 or later lose functions; the rejected alternative was "Baseline widely available" (ES2024, Safari 17.4) | `typecheckJs`, `lintJs`, the E2E browser matrix |
| **ESLint core autofix rules** — **done 2026-10-04**: all five are errors for the browser and Node scripts, autofix applied (1,345 arrow callbacks, 723 template literals, 9 logical assignments, 1 `Object.hasOwn`; 5 `parseInt` radixes and 3 `||=` by hand); `?.`/`??` stay a by-hand change in `@ts-check` files (`prefer-template` for 664 concatenations, `prefer-arrow-callback`, `prefer-object-has-own`, `radix`, `logical-assignment-operators`); `?.`/`??` by hand while a file opts into `@ts-check` (the semantics differ for falsy values) | Consistent modern code with no new dependency | About 1,800 sites; conflicts with in-flight branches | `lintJs`, `typecheckJs`, E2E |
| **Type-check ratchet per domain folder**; the three largest scripts (mission detail, bank, order detail — 20 % of all JavaScript) split before they are checked | Null safety reaches the files that change most | Cast churn in DOM-heavy code | A folder is "migrated" only when fully checked |
| **Finish ADR-0069**: the 70 inline script blocks (1,860 lines, never linted) move into modules; one page puts eleven `[[#{…}]]` markers into a script without `th:inline` (a likely i18n defect) | Lintable, checkable code | Timing of inline versus module code | `InlineScriptLoadOrderTest`, E2E |
| **One read path**: `krtFetch.get`/`getJson` with re-authentication, the terms gate, refusal of redirected or non-JSON answers and `AbortController`; then forbid `fetch(` outside the transport and ban `XMLHttpRequest`. **Done 2026-10-04** (REQ-FE-031): 61 raw reads in 33 scripts plus one inline read in `members.html` migrated, `swap` reads through `get` too; ESLint bans `fetch`/`XMLHttpRequest`, and `BackgroundReadGateContractTest` pins the same for templates, which ESLint never sees | 56 raw reads behave the same way on session loss | Pickers that silently emptied now send the user to the login — the intended contract | A new E2E for a picker after session loss |
| **Trusted Types**, report-only first through the existing `csp_violation` beacon, then enforced: two named policies, a tagged-template HTML builder, no default policy | DOM-XSS becomes a browser-enforced property | Every future sink must use the helpers; under enforcement even `innerHTML = ''` throws | A violation collector in the dialog page-walk E2E |
| **CSS** — **done 2026-10-04** (ADR-0243): lint the page stylesheets with the standard config (74 by then, not 54; 66 findings fixed without visual change); a custom-property existence test (it finds an undefined token that makes the material-demand search header transparent; B-03 was already fixed in Phase −1, and the test now also reads templates and scripts); `color-mix()` for alpha variants (120 hand-written token copies replaced, `ColourTokenCopyTest`); a z-index scale (18 `--z-*` tokens with the numbers they replaced, `ZIndexScaleTest`); container queries and nesting only where a design decision asks for them (none asked) | One lint standard; tokens as the single colour source | Visual changes | `CascadeLayerOrderTest`, screenshot review; a computed-style comparison of every touched declaration against `main` |
| **One layout fragment** carrying head, sidebar, header and toast (83 pages copy the header in five variants) — native Thymeleaf, no Layout Dialect dependency | Chrome changes once | Touches 83–90 templates | Render tests, E2E |
| **ES modules** only after the global-scope and inline-script work, and only behind a server-rendered import map (unversioned asset URLs are cached immutably for a year) | Explicit dependencies, lint-enforced boundaries | Import-map and nonce plumbing | Every specifier must be in the map |
| **No bundler** | — | A compromised bundler release would be site-wide script injection the nonce CSP cannot stop; the served file would no longer be the checked file (ADR-0125) | — |

### 8.3 SQL, persistence, platform

- **PostgreSQL 18**: `RETURNING OLD/NEW` where a native bulk update needs the old value for its audit
  row; review multicolumn indexes against skip scan with `EXPLAIN`; temporal `WITHOUT OVERLAPS` only
  with the leadership redesign (needs `btree_gist`). **No `uuidv7` ids** for anything whose id
  appears in a URL or an export without a privacy decision — they reveal creation times.
- **Hibernate 7.4**: do not adopt `@Audited` (it would keep full row history, personal data included,
  outside the GDPR deletion flow) or Jakarta Data stateless repositories (they do not fit the
  `@Version` concurrency rules); write a characterisation test before touching
  `fail_on_pagination_over_collection_fetch`.
- **Spring Framework 7**: `@ConcurrencyLimit` for the backend's outbound fan-out to UEX and SC Wiki,
  which virtual threads no longer bound; keep the find-or-create retry explicit. Spring's built-in API
  versioning adds nothing under a hard cut; its standard deprecation headers only if
  `@ApiDeprecation` is ever used again.
- **Spring Boot 4.1**: an `InetAddressFilter` restricting the external-integration HTTP clients (UEX,
  SC Wiki, Discord) to external addresses — a defence against redirects into internal networks; never
  on the internal clients.
- **Jackson 3** reads unknown JSON properties tolerantly by default. That stays: the frontend and
  backend deploy together and are checked by the contract tests, and the exchange's tolerant reading
  is part of its frozen behaviour. The policy is written into the API conventions.

### 8.4 Gradle and CI

Typed per-module build settings now; an included `build-logic` with convention plugins before the
first Gradle extraction (it also prepares Gradle's isolated projects); `java-test-fixtures` for
module-owned test fixtures; the configuration cache on for local builds (done 2026-10-04, #2387); a decision whether CI reuses
configuration-cache entries (it never does today, because `setup-gradle` gets no encryption key).

## 9 Defects found along the way — fixed first, in separate pull requests

**Status:** fixed by PRs #2299–#2312, merged 2026-10-01 (§7.1); C-03 is left to the owner.

Every item below survived the adversarial round; the severity is the verified one, often lower
than the first finding claimed. None allows another member's data to be read or changed; several
are availability, privacy or integrity defects, and some are traps the refactor would trigger.
They are fixed before the refactor starts (D-07), independently of this plan.

### 9.1 Security and privacy

| ID | Defect | Verified severity | Fix | Guard |
| --- | --- | --- | --- | --- |
| S-01 | The pinned Keycloak trust client has **no connect or read timeout** (`backend/…/config/KeycloakTrustSupport.java`), and in production it backs both the Keycloak Admin API client and the internal JWKS decoder — which also discards Spring Security's own 30 s / 30 s defaults, set because the key fetch runs under a lock. If Keycloak accepts a connection and never answers, every token validation after the five-minute key cache fails after 15 s for as long as it hangs; `ConnectedAppsService.disconnectClient` holds a database connection per hung attempt | Availability of authentication, medium-low (not attacker-triggerable) | Give the pinned factory the connect and read timeouts of `RestClientConfig` (5 s / 30 s), HTTP/1.1, for both uses; the same in ingest's copy | A test asserting the factory's timeouts; `SecurityConfigInternalJwksDecoderTest`, `KeycloakServiceTest` |
| S-02 | Five frontend call sites tag users in log lines with `String.hashCode()` of the username (`BackendRoleSyncFilter`, `JobOrderPageController`, `JobOrderWriteController`, `RefineryOrderPageController`); they feed 14 log statements, six at INFO or above, and a 32-bit hash of a name from a known member list is reversible by dictionary. `PiiMasker` does not catch it | Privacy, low (REQ-OBS-004: never log names) | Log the subject id from the MDC instead, or a keyed HMAC rendered with `HexFormat` | A log-capture test asserting no username-derived token |
| S-03 | Generated `toString()` prints credentials: the frontend and ingest `MonitoringScrapeProperties` are Lombok `@Data` classes that print the scrape password (the backend twin is a redacting record); the keycloak-spi record `Brokered` prints a Discord access token, the username and the e-mail, in a JVM that has no masker. No code logs these objects today | Latent, low | Records with a redacting `toString` in frontend and ingest; a presence-only `toString` for `Brokered`; `PiiMasker` also masks `password=` and `secret=` | G-22 (`toString` ratchet); the existing scrape-security tests |
| S-04 | The promotion CSV export (`static/js/promotion-manage.js`) quotes cells but does not neutralise a leading `=`, `+`, `-`, `@`, tab or carriage return. Usernames come from Discord and cannot be edited; the realistic attacker is an admin or officer writing a topic name, the victim another officer opening the export | Low | Prefix such cells with `'` | A unit test over the escaper |
| S-05 | The documented runtime log-level change (`POST /actuator/loggers/…` with a bearer token, REQ-OBS-016) is outside the backend's CSRF exemption, so it is expected to fail with 403 in production; `ActuatorLoggersAuthorizationTest` passes only because the test profile disables CSRF. Not verified against production | Availability of an admin tool, low | Exempt the bearer-only management endpoint from CSRF (or disable CSRF on the bearer-only management chain) and test with CSRF enabled | A context test with CSRF on (part of G-07) |
| S-06 | The frontend's live-sync subscription authorizer fails open for every status other than 403 and 404 — including 401, timeouts, a missing token and a full executor — and the job-order capability probe even on 403 and 404; a member can trigger it after 300 s, because the handshake token is never refreshed and sockets have no maximum age. What leaks are `changed` frames with section keys for an id the subscriber already knows (ADR-0094 accepts this) | Low | Fail closed on 401, 5xx and timeouts for resource probes; bound the socket age or re-check on expiry; keep the documented exceptions explicit | Authorizer tests per status; G-14 |
| S-07 | Two frontend handlers (`HomeController` twice, `PromotionPageController`) bind a browser-supplied id as `String` and concatenate it into the backend URI, so a crafted value can append query parameters or path segments to a relayed call made with the caller's own token (the backend still authorizes; neither target reads query parameters) | Informational (REQ-SEC-051) | Bind as `UUID`; template variables | A MockMvc test per handler with `a?b#c/../x` expecting 400 and no backend call |
| S-08 | Three catalogue searches pass user input into `LIKE` without `LikePatterns.escape` (the blueprint product search, and the order and inventory item-catalogue searches) | Informational — wildcard broadening of shared catalogue data | Escape | Repository tests with `%` and `_` |
| S-09 | The backup helper falls back to an unpinned PostgreSQL image when the pinned unit cannot be read — while it mounts all of `/var/iri/secrets`, the compose directory with `.env`, the edge certificates and the monitoring secrets | Low (defence in depth; the shipped unit pins a digest) | Refuse to run without a digest-pinned image | `deploy.test.sh`/backup stub test; the restore drill after rollout (a production write, owner-approved) |
| S-10 | The 15 endpoints whose only role gate is a URL rule (G-03) | Not exploitable today (every account holds the member role), a trap for the path moves | Lift into the annotations | G-02, G-03 |

### 9.2 Integrity and functional defects

| ID | Defect | Severity | Fix |
| --- | --- | --- | --- |
| B-01 | A stored refinery order can be set back from COMPLETED to OPEN with `PUT` and **stored again**, which duplicates its inventory rows and its `INVENTORY_RECEIVED_FROM_REFINERY` / `REFINERY_ORDER_STORED` audit events; `store` also skips the "the job order needs this material" check that `POST /inventory` applies (REQ-ORDERS-018) | Low (own inventory only), but the audit trail records a second receipt | Refuse to reopen or re-store a stored order (a stored marker, not the status alone); apply the same material check as the inventory path |
| B-02 | The Android app (v0.3.1) creates operations with `POST /api/v1/operations`, which the edge's API allow-list does not admit, so the call answers 404; whether v0.4.0 still sends it is unknown | Functional | Admit and freeze it, or remove the call from the app — decided with the operation domain's wave |
| B-03 | `var(--color-black)` does not exist (the token is `--color-bg-black`), so the sticky search header of the material-demand page is transparent; `var(--color-text)` in `bank.css` is undefined too | UI | Correct the tokens (re-verified 2026-10-04: neither occurs any more) | A custom-property existence test |
| B-04 | `templates/members.html` puts eleven `[[#{…}]]` message markers into a script without `th:inline` — wrong-context escaping at best, literal markers in three dialogs at worst | UI/i18n, to confirm with a render | A `th:inline="javascript"` dictionary | A render test of `/members` |
| B-05 | A personal-inventory sort with a direction is double-encoded and never works (no page sends one today) | Functional, latent | Route through `RelayParams` and a template variable | MockWebServer path assertion |
| B-06 | `OrgUnitMembershipService.removeOlMember` pattern-matches on an `OrgUnit` that can be a Hibernate proxy, so the Grand Admiral pointer is not cleared in that case (display only; no authorization reads it) | Low | Unproxy, or dispatch on the kind | An integration test that reproduces the proxy case |
| B-07 | Eleven frontend controllers bypass `BackendApiClient`'s error mapping: an expired refresh token becomes a 500 instead of the re-authentication flow, the RFC 7807 `code` is lost, and `basetool_backend_client_errors_total` misses them | Low | The kernel shapes of §7.2 step 0.4 | G-17 |
| B-08 | The session allow-list's code default is still `report` while production enforces | Low | Default `enforce` in code, compose and the environment template; `report` only as an explicit opt-in | A default-mode test |

### 9.3 Operations and CI

| ID | Defect | Severity | Fix |
| --- | --- | --- | --- |
| C-01 | Production promotion re-tags each of the five artefacts to `:stable` in its own fail-fast matrix job without rollback, and `deploy.sh` resolves each tag separately every five minutes — a partial or in-progress promotion can deploy a mixed release; per-image signatures do not catch mixing | Medium (operational) — severe once APIs are re-cut | G-21 |
| C-02 | The scheduled mutation-testing run lets its gate pass on a partial report when the backend leg is cancelled at the job timeout | Low (CI signal) | Gate on PIT's completion line; raise the timeout |
| C-03 | The repo-lint jobs `Self-tests` and `Container checks` (Prometheus rule unit tests, monitoring configuration checks, deploy-seam self-tests) are not required status checks | Low–medium | Add both to the ruleset — a repository-settings write by the owner; **done** (both required on `main`, checked 2026-10-04) |
| C-04 | The only crash-loop alert has no absence guard for its Podman series | Low | An `absent()` rule or a conformance entry |

### 9.4 To investigate

- The mass-change confirmation page echoes `stagedAt` and the change set from the browser without an
  integrity check, so the expiry and revoked-since-staging checks trust a member-supplied time. Only
  the member's own data under a capability the client holds is affected; a provenance question.
- Whether the frontend session enforcement is really on in production (documented, not observed).

## 10 Frameworks and tools evaluated

| Candidate | Verdict | Reason |
| --- | --- | --- |
| **ArchUnit 1.5.1 `modules()` and `FreezingArchRule`** | Adopt | Already in the catalog; module rules and a baseline that may only shrink make an incremental start possible; the empty-selection default fails a rule whose whole selection vanishes (verified in the jar), but not one whose target string no longer resolves — hence G-01 |
| **Spring Modulith 2.1.1**, test scope | Adopt (D-02), after a spike | Released 2026-08-26 on Boot 4.1.1 and Framework 7.0.9; `explicitly-annotated` detection, nested and open modules, named interfaces, `verify()`, module-scoped tests, generated documentation for arc42 §5; nothing reaches an image. Its core is compiled against ArchUnit 1.4.2 — `verify()` must be proven under 1.5.1 first |
| Spring Modulith event publication registry | Reject for now | A real transactional outbox, but: the starters put ArchUnit and Modulith core on the production class path; JDBC schema creation is on by default (Flyway owns the schema); payloads are stored in plain text and the class name is persisted, so a moved event class makes incomplete publications disappear with only a warning; its listeners run after commit in a new transaction, so audit can never go there. The four after-commit families do not need an outbox. If one ever does: `events-jdbc` plus `events-jackson` without a starter, schema by Flyway, id-only payloads, idempotent listeners |
| jMolecules | Not needed | ArchUnit and Modulith cover the rules; a third vocabulary |
| **Spring HTTP interface clients** (Framework 7.0.9) | Adopt with rules | Built on the one `webClient` bean they keep the single resilience pass; the rules of G-17 remove the two hazards (a default service group has none of the filters; a `URI` argument redirects the bearer token) |
| Spring API versioning | Not now | Under a hard cut there are never two versions to negotiate; path versioning is load-bearing for the edge and the path lists |
| Spring Framework 7 resilience annotations | Adopt `@ConcurrencyLimit` narrowly | A bound on outbound fan-out under virtual threads; retries stay explicit |
| **OpenRewrite** `ChangePackage`/`ChangeType` | Use locally, never commit | They rewrite imports and class names in YAML and properties values, Spring XML and `META-INF/services`, which makes package moves mechanical. Newer plugin and core versions are published only to an authenticated repository and the migration recipes are under a source-available licence; a credentialed repository in `settings.gradle.kts` would break `FAIL_ON_PROJECT_REPOS` and dependency verification. Run the Apache-licensed recipes with the last freely published versions (plugin 7.41.0, core 8.90.4) from an uncommitted init script; never run `UpgradeToJava25` (it converts dangling doc comments into block comments, against ADR-0214). They do not touch SpEL, Thymeleaf, string literals or persisted ids — the move checklist does |
| **Error Prone + NullAway** | Adopt (D-13) | A compile-time nullness gate that works with the JetBrains annotations; closes arc42 §11.4 |
| JPMS (`module-info.java`) | Reject | No Spring, Boot, Modulith or Hibernate jar ships a module descriptor; the Java AOT cache forbids the `--add-opens` a module-path deployment would need |
| Separate deployable services (option D) | Reject | Twelve write families and 206 audit writes need the caller's transaction; one host, one maintainer |
| Hibernate `@Audited`, Jakarta Data repositories | Reject | Personal data outside the GDPR flow; stateless repositories do not fit the concurrency rules |
| Generated client DTOs (openapi-generator) | Reject for production code | The generator emits mutable classes, not records, and hand mirrors are an output allow-list; generation stays in the test source set for the agreement test |
| A JavaScript bundler | Reject | Supply-chain risk for every shipped asset; breaks "served equals checked" |
| Trusted Types | Adopt, report-only first | No dependency; the existing CSP-violation beacon provides the report-only phase |
| `oasdiff` or another external API-diff tool | Reject | An unverified binary outside Gradle's dependency verification; the ingest's `SchemaCompatibility` helper generalises instead |

## 11 Previous audits re-evaluated

Full tables, per finding with status, evidence and verdict:
[previous-audits appendix](modularisation/previous-audits.md).

- **September 2026 improvement audit (176 findings).** 156 are in this repository's scope (20
  concern the sibling repositories; the knowledge base records 19 of them done and one decided as
  won't-do). Status today: **139 done and holding, 14 partial, 1 open (BLD-PERF-03, test contexts),
  1 superseded (ING-PERF-01, by ING-MOD-01), 1 regressed (BE-SIMP-08, a new `trimToNull` copy in the
  exchange registry)**. Verdicts under today's rules: 124 confirmed, 27 adjusted to the modular
  target, 2 absorbed by it (BE-SIMP-05 — redaction moves into the mission read API; FE-SIMP-02 — the
  typed clients of §5.9 are its open half), 1 dropped (the shared-font half of THEME-SIMP-01), 2
  re-prioritised (ING-MOD-02 to P1: a credential in `toString`; OPS-SEC-08 to P3).
- **Work the September audit left, re-prioritised:** P1 — the Keycloak trust client without
  timeouts (BE-MOD-02 residual), `MonitoringScrapeProperties` printing its password (ING-MOD-02),
  the session allow-list default still `report` in code (APPSEC-05), PIT passing on a partial report
  (CI-03), two repo-lint jobs with the alert-rule unit tests not required (CI-SEC-10), no absence
  guard on the crash-loop alert (OPS-MON-01), the backup helper falling back to an unpinned image
  (OPS-SEC-03), test-profile unification (BLD-PERF-03). The rest is P2/P3 and mostly folds into the
  module steps.
- **September fixes that coupled domains more tightly** and are re-shaped by this plan: the refinery
  → mission participant repository edge (APPSEC-02), the third copy of an inventory rule in a
  foreign domain (APPSEC-01), the hangar → `UserMapper` edge and its priming obligation (BE-PERF-01),
  a second memo of the caller's memberships (BE-PERF-15), domain helpers accumulating in `support`.
- **July 2026 modularity audit.** Its report was never committed; the finding list was rebuilt from
  PR #1256, issues #1250–#1255 and PRs #1257–#1262. Every finding shipped and holds, all nine
  service splits included, with three gaps: `HangarPageModelLoader` was never built,
  `CachedCatalogListLoader` has one consumer, and the per-family split of `GlobalExceptionHandler`
  is open. Its diagnosis — "the architecture is sound, the debt is size inside
  correct layers" — holds for layers and not for domains (files over 600 lines grew from 49 of 1,477
  to 53 of 2,044). **14 of its 15 rejected simplifications still hold** (Mission section counters,
  `…WithinTransaction` hops, the find-or-create retry, bulk updates after loops, no generic CRUD base,
  peer redaction's explicit constructor, the one-class bank seam, …); the modular target builds on
  them. The one that does not: "never split `BackendApiClient`" — its reason holds, its conclusion
  does not (§5.9).
- **The earlier focused audits** (security 2026-05-20, 06-03, 06-21, 08-25, 08-30; performance
  05-20; caching; concurrency; permission gate; post-cutover operations; documentation; design):
  still open are the 100,000 page-size ceiling, the unescaped `LIKE` in the blueprint product
  search, `no-store` classification of the unlisted families (admin, exchange, orders,
  Materialbörse, Leitung, org chart), Keycloak hardening steps 2, 11 (OTP for admins) and 12, and the
  cached-entity invariant of the caching audit, whose only protection was a code comment removed by
  ADR-0214 (G-19). Fixes a split could put at risk are listed with their guard in the appendix.

## 12 Risk and regression register

| Risk | Where it would show | Likelihood without guards | Guard |
| --- | --- | --- | --- |
| A security rule passes vacuously after a move | Nowhere — the build stays green | High | G-01, G-02, G-09 |
| A renamed security bean turns endpoints into HTTP 400 | Users, not monitoring | Medium | G-04 |
| A moved path loses its role gate, `no-store`, CSRF handling or rate limit | Nowhere until exploited | High for a full API cut | G-02, G-03, G-07, G-08 |
| A tenancy escape lost or widened while gates move to policies | Silent data leak or loss | Medium | Differential verdict test (§5.4), G-05 |
| Audit becomes after-commit | Missing audit rows | Low, but a tempting "clean up" | G-12, red line |
| Lock order or bulk-update-after-loop broken in the inventory command API | 409s, lost lots, deadlocks | Medium | Concurrency tests, ADR-0229 load cases |
| Session values dropped after a frontend move | Lost form input after a failed save | High | G-16 |
| A typed client built on the wrong `WebClient` | 401s, lost tenancy header, lost resilience | Medium | G-17 |
| Exchange behaviour drifts | 502 at the gateway, external clients broken | Medium | G-18 |
| A mixed release during a re-cut | Every call of a domain 404s | Low per tick, real per promotion | G-21 |
| Old app versions keep running against a re-cut API | Broken screens until the next cold start | Certain for running apps | D-11: the app re-reads the policy, `APP_UPDATE_REQUIRED` |
| A raised minimum version survives a rollback of the release | No app version works | Real if the floor is raised with the deploy | Release-bound floor (D-11); until it is in place, raise the floor only after the release is verified healthy |
| Coverage and mutation gates weaken silently | Weaker quality gates | High for Gradle modules | G-20, G-24 |
| Cached catalogue entities mutated in place | Stale or wrong catalogue data after a failed write | Medium once services split | G-19 |
| Test-context explosion from module-scoped tests | CI time | Medium | BLD-PERF-03 first; security beans stay real in shared contexts |

## 13 Decisions that were open

| ID | Decision | Outcome |
| --- | --- | --- |
| O-01 | Error model (§5.5) | **Decided 2026-10-01 as recommended — D-09** |
| O-02 | Frontend session allow-list | **Decided 2026-10-01 as recommended — D-10** |
| O-03 | Error Prone + NullAway | **Decided 2026-10-01 as recommended — D-13** |
| O-04 | `_` for empty catch blocks | **Decided 2026-10-01 — D-14:** `_` also in empty catch blocks, with the ADR-0214 amendment |
| O-05 | Switch policy | **Decided 2026-10-01 as recommended — D-15** |
| O-06 | Browser baseline | **Decided 2026-10-01 — D-16:** Baseline 2025 (ES2025); Trusted Types report-only, then enforced |
| O-07 | The forced-update mechanics | **Decided 2026-10-01 as recommended — D-11.** Still open inside it: (b), a static policy file at the edge during restarts, only if the app's re-read proves insufficient |
| O-08 | Storage of member settings that move out of identity (payout preference, blueprint sharing) | **Decided 2026-10-01 as recommended — D-17** |
| O-09 | `no-store` for the unlisted families and a lower page-size ceiling | **Decided 2026-10-01 as recommended — D-18** |
| O-10 | SpEL evaluation failures | **Decided 2026-10-01 — D-19:** fail-closed 400, counted and alerted |
| O-11 | `MissionParticipant.orgUnits` is the one eager cross-domain collection | **Decided 2026-10-01 as recommended — D-20** |
| O-12 | CI configuration-cache reuse | **Decided 2026-10-01 as recommended — D-21** |
| O-13 | Keep the raw evidence of this audit | **Decided 2026-10-01 — D-22:** kept in `docs/archive/` |
| O-14 | Home of the 14 exchange-administration operations | **Decided 2026-10-01 — D-12:** `/api/v1/connected-apps/admin/**` |

## 14 Documents to write or amend

- **New ADRs — done 2026-10-02:** target architecture and module rules (D-01, ADR-0231); module
  interaction styles (ADR-0232); enforcement tooling and the frozen module baseline (D-02,
  ADR-0233); hard cut with forced update and the release-bound minimum version (D-04, D-11,
  ADR-0234); error model (D-09, ADR-0235); per-domain access policies over the scope kernel
  (ADR-0236); Error Prone and NullAway (D-13, ADR-0237); the switch policy (D-15, ADR-0238); the
  browser baseline and Trusted Types (D-16, ADR-0239).
- **Amended ADRs — done 2026-10-02:** ADR-0020/0028 (seam rules re-keyed; owner approval), ADR-0032 (single filter
  chain, typed clients), ADR-0047 (module cycles frozen, layer cycles per module), ADR-0060 (separate
  request and response types mandatory at each domain's cut), ADR-0065 (policies per domain),
  ADR-0069 (IIFE namespaces), ADR-0135 (edge include generated from the contract set), ADR-0136
  (retirement by hard cut), ADR-0206 (D-10), ADR-0212 (its instruction to add a comment contradicts
  ADR-0214), ADR-0214 (D-14: `_` in empty catch blocks), ADR-0216 (the relay surface as the
  exchange's internal published API), ADR-0223 (§8.1), ADR-0229 (the lot-lock protocol moves into inventory).
- **Requirements:** REQ-API-001/009/010 (hard cut — done 2026-10-02, implementation pending), REQ-API-002/003/004/005/007 (the conventions as
  they are enforced), REQ-SEC-031 (every family classified), REQ-SEC (bean-reference integrity,
  authorization placement), REQ-DATA (table ownership, one Flyway location), REQ-DATA-007 (cached
  read models), REQ-DATA-010 (one scope fragment per aggregate), REQ-AUDIT-001 (the audit contract
  per command), REQ-XCH-009/011/026 (domain APIs; build-time contract test), REQ-FE-018 (baseline and
  counts — done 2026-10-02), REQ-OBS (event-publication metrics, only if an outbox is ever adopted).
- **arc42:** §4.1, §4.2, §5.3 and §5.5 (corrected with this plan), §5.2 and §5.3 rewritten per
  module as the moves land, §8 (module rules, interaction styles — §8.14, done 2026-10-02), §11.9
  (the domain coupling as debt, opened with this plan; its decisions linked 2026-10-02).
- **CLAUDE.md files:** the Architecture and Java-conventions sections of the root file, and the
  backend and frontend files, as each phase changes the rules they state (2026-10-02: the root
  file's Java conventions and code-comment rule gained `_`, D-14, and the switch policy, D-15).
- **Knowledge base:** the plan note in `80 Plans`, and every system and domain note as its module
  moves.

## 15 Documentation drift found

The audit compared documents with the code; the code is right. Corrected in the knowledge base in the
same session as this plan, and in the repository where this plan touches the document (arc42 §4.1,
§4.2, §5.3, §5.5, §11); the rest is listed for the follow-up pull request:

| Document | Says | The code says |
| --- | --- | --- |
| `docs/specs/api-conventions.md` REQ-API-003 | `@Valid` on every `@RequestBody` | 13 without |
| `docs/specs/api-conventions.md` REQ-API-004 | the problem format is documented in OpenAPI | `code`, `correlationId`, `fieldErrors` are not |
| `docs/specs/api-conventions.md` REQ-API-005 | every list takes `Pageable` and returns `PageResponse` | 1 `Pageable`, 67 `page`/`size` parameters, 71 unpaged lists |
| `docs/specs/api-conventions.md` REQ-API-007 | every endpoint carries `@Operation`/`@ApiResponses`; the generator test asserts the document | 400 and 231 of 574; the backend generator test asserts only `200` |
| `docs/specs/api-conventions.md` REQ-API-012 | `/me/layout` is not on the API vhost | the `^/api/v1/me/` prefix rule admits it |
| `docs/specs/security-and-access.md` REQ-SEC-031 | a member record is the only personal data the API serves | admin export, person search and registrations serve personal data and are not in the `no-store` families |
| `docs/specs/frontend-ajax-mutations.md` REQ-FE-018, `docs/TYPESCRIPT_MIGRATION_PLAN.md` | 40 of 96 files type-checked; `type=module` would change the execution order | 44 of 100; 120 of 121 scripts are already `defer` — **corrected 2026-10-02** in both |
| ADR-0223 | see §8.1 | **corrected 2026-10-02** (amendment) |
| ADR-0069, ADR-0130, ADR-0212 | "no IIFE wrapping"; `scripts/**/*.mjs` in the lint globs; "with a comment naming what it beats" | half the scripts are IIFE-wrapped; the Gradle lint tasks do not read `scripts/`; ADR-0214 forbids the comment — **corrected 2026-10-02** (an amendment each); the `scripts/` lint gate is done too — `lintJs` reads `scripts/**/*.mjs` (`frontend/build.gradle.kts`, checked 2026-10-04) |
| backend `ArchitectureTest` messages | ask for "a code comment" in three places | ADR-0214 |
| `config/owasp/dependency-check-suppressions.xml` | its header described how a suppression is renewed | the header went with the ADR-0214 sweep; no document describes the renewal now, and all nine suppressions expire on the same day — **corrected 2026-10-04** (#2387): CONTRIBUTING → *OWASP suppressions expire* |

## 16 Method, sources and limits

- **Analyses.** Backend domain map and coupling (every class classified; `jdeps -filter:none` over the
  compiled classes, i.e. including same-package edges); cross-cutting concerns and refactor safety
  (annotations read with `javap`); frontend Java; frontend assets (parsed with espree, eslint-scope,
  postcss and htmlparser2); ingest, keycloak-spi, libraries and build; modern Java (lexer-based
  counts and javac/Checkstyle/google-java-format probes); the REST API (every mapping, the edge
  allow-list re-parsed with the test's own semantics, the Android app's local checkout at v0.3.1);
  current documentation (openjdk.org, docs.spring.io, GitHub release pages, docs.gradle.org,
  postgresql.org, web-features 3.40.0; all read 2026-09-29); three re-evaluations of the previous
  audits against the code and read-only GitHub queries.
- **Adversarial round.** Four further agents tried to refute 36 claims — the security findings and
  the load-bearing architecture claims (the strongly connected component, the sealed-package rule,
  the single filter chain of typed clients, the hard-cut mechanics, the transactional coupling of the
  write families). None fell entirely; one link was refuted (the minimum version is not rendered at
  every deploy tick, §5.10); many were narrowed — the ArchUnit exposure is larger than first counted,
  the domain knot looser, several security findings have no attacker input today — and the round
  found new defects of its own (B-01, S-05, the JWKS half of S-01, B-02). §9 lists only what
  survived, with the verified severity.
- **Limits.** No production or testing host was read; statements about production (the current
  minimum app version, whether a timer tick ever hit a partial promotion) are marked as such. The
  Android facts come from a local checkout one release behind. Counts are exact for `95e945326`; line
  numbers drift with every commit, so the appendices name files and symbols as well.
