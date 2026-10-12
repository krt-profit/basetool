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
| D-20 | **`MissionParticipant.orgUnits` becomes lazy** (was O-11, decided 2026-10-01) | Loaded through an entity graph where it is needed, the load paths pinned by N+1 tests. **Done 2026-10-11 (§7.6).** |
| D-21 | **CI reuses the configuration cache** (was O-12, decided 2026-10-01) | The owner adds the encryption key as a repository secret; pull requests read the cache, `main` writes it; the workflow change follows the secret. |
| D-22 | **The raw evidence is kept** (was O-13, decided 2026-10-01) | Reports, finding data and scripts, sanitised, in [`docs/archive/domain-modularisation-audit-2026-09/`](archive/domain-modularisation-audit-2026-09/README.md). |
| D-23 | **No soak before Phase 5** (decided 2026-10-04) | `exchange` and `bank` become Gradle modules right after Phases 2–4, without waiting for their package boundary to stay green for some releases; the Phase 0 guards and the frozen module baseline hold the boundary meanwhile (§7.7). |
| D-24 | **One combined API cut** (decided 2026-10-04) | The REST waves of §7.9 land on `main` together and ship as one backend release with one app release that uses every new path, so members take one forced update instead of one per wave; the release-bound floor (D-11) rises with that release. |
| D-25 | **The terms text stays** (decided 2026-10-04) | The terms keep their wording (they still mention guests and list five areas), because any change forces every member to consent again (ADR-0127); D1981-TERMS is closed as kept. |
| D-26 | **Keycloak hardening steps 2, 11 and 12 are in scope** (decided 2026-10-04) | SMTP and forgotten password, OTP for admins, session windows (`docs/KEYCLOAK_HARDENING_RUNBOOK.md`); each realm write still needs the owner's yes per action. **Prepared 2026-10-10:** `scripts/harden-keycloak-realm.py` (REQ-SEC-082), its integration test on a throwaway Keycloak, and every owner-only step with command, expected output, rollback and approval in [`OWNER_STEPS_2026-10.md`](OWNER_STEPS_2026-10.md); nothing was executed against a host or realm. Production and repository-settings work (CI-SEC-16, OPS-SEC-08, the S-09 restore drill) is built in code and executed per approved action. |
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
  package (four, keyed on the `@DtoMirror` marker since F4, 2026-10-10); the authorization gate
  test checks classes, not handlers.
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
| 2 | `catalogue` | Materials, items, locations, ship types, refining methods, job and frequency types, and the UEX, SC Wiki and P4K imports — including the recipe graph (`catalogue.api.Blueprint`). |
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
- **Caching holds read models, not entities.** Cached catalogue mutators were safe only because
  they called their own `@Cacheable` getter through self-invocation; a query/command split would make
  them edit the cached instance (§7.2, G-19). Done for the catalogue 2026-10-12 (P4-5, §7.6).

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
block and the `rootProject` accesses block Gradle's isolated projects and would silently give a new
module default coverage floors; the block is gone since 2026-10-11, §7.7 P5-a, and the 32
`rootProject` reads of the module scripts since 2026-10-12, P5-b), typed per-module build settings
(done, REQ-OPS-037), prefix rules in the image-reuse and SBOM scripts (done, P5-b, ADR-0249), JaCoCo
aggregation (done, P5-b), and a context-shape test (scheduled tasks, event listeners, controllers,
filter chains) so a module outside the scan root cannot lose beans silently (G-24, done in Phase 0,
REQ-OPS-038). One image per application stays; Flyway stays in `backend`.

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
├── kernel.observability logging, tracing and metric infrastructure (added with F4)
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
- **Templates and assets** move into per-domain folders (decided and built 2026-10-11, F5: `templates/<domain>/`, `static/js/<domain>/`, `static/css/<domain>/`, `static/css/pages/<domain>/`, the shared kernel in `kernel/`); `Roles` stays in a stable kernel package or
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
| G-19 | Cached-entity invariant test for every `@Cacheable` catalogue service; target state: caches hold read models and an ArchUnit rule forbids `@Cacheable` returning an entity (the catalogue's caches switched 2026-10-12, P4-5; the list holds the identity and org-unit caches) | A query/command split letting a mutator edit the cached instance |
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

- `dashboard` — **done 2026-10-05** (P2-1). The announcement banner moved out of the layer
  packages: `dashboard.web` holds `AnnouncementController`, `AnnouncementDto` and
  `AnnouncementMapper`, `dashboard.internal` the `Announcement` entity, its repository and
  `AnnouncementService`. The module publishes nothing (no other class uses it), so it is declared
  like `mission`: closed, no named interface, in no other module's `allowedDependencies`. The
  domain map's `name ^Announcement` rule became the `package dashboard` rule; the module baseline is
  unchanged at 110 edges, because dashboard had no edge in it. Entity name, table, bean names, REST
  paths, schema names, the authorization matrix and the OpenAPI document are unchanged; the one
  visible difference is the persistent class name in the log context of an announcement 409,
  which is now `dashboard.internal.Announcement`. The module has no access policy: its gates are
  the role annotations `isAuthenticated()` and `hasRole('ADMIN')`, nothing of the scope hub.
  **Correction to §5.2:** a mechanical move cannot satisfy `web`'s "no `@Transactional`, no
  entity" — `AnnouncementController` keeps its class-level transaction and maps the entity in the
  controller, as before. Those two rules are the target of the module's REST wave, which moves the
  transaction boundary and the mapping into the module; a move keeps both where they are, because
  either change alters the transaction boundary.
- `orgchart` — **done 2026-10-05.** All 24 classes moved: the controllers `OrgChartController` and
  `LeitungController` into `orgchart.web`, the rest (entity `OrgChartPosition`, its repository, the
  enums, `OrgChartService`, `OrgChartReadService`, `LeitungViewService`, `OrgChartPositionMapper`
  and the 14 DTOs, `AreaLeadershipDto` with them as §7.3 P1-1 deferred) into `orgchart.internal`.
  The module is declared (floor 19, after `dashboard`) and publishes nothing: no other module uses
  it, and orgunit reaches it only through `MembershipChangeObserver`. The domain map trades its two `name` rules
  and the `AreaLeadershipDto` class rule for one `package` rule. No access policy left the scope
  hub — the org chart has none: its reads are `isAuthenticated()`, its writes `hasRole('ADMIN')`,
  and neither touches `OwnerScopeService` or `AccessGateService`. The module baseline is unchanged
  at 110 edges: the org chart had no frozen edge left after P1-1. `OrgChartModuleContractTest`
  pins its endpoints and the observer before and after the move. **Correction to §5.2:** the
  `web` package cannot hold the REST DTOs yet — the services return them, so `internal -> web`
  would close a layer cycle inside the module (ADR-0047); they stay in `internal` until the module
  API returns its own records.
- `admin` — **done 2026-10-05** (P2-2). The admin building blocks of §5.1 — the runtime system
  settings, the Android version policy and the liveness probe — moved out of the layer packages:
  `admin.web` holds `SystemSettingController`, `AppVersionPolicyController`, `SystemController`,
  `AppVersionPolicyDto` and `PingResponse`; `admin.internal` the `SystemSetting` entity, its
  repository, `SystemSettingService`, `SystemSettingMapper`, the setting records the service returns,
  `AndroidClientProperties` and `AndroidVersionPolicyReport`. The `/admin` endpoints of other
  domains are not part of it; they move with their domains. The module's four inbound edges (bank
  transfer fee, operation payout, the default-blueprint bootstrap's entity and repository) now go
  through one API type, `admin.api.SystemSettings` (`getSettingValue`, and `putSettingValue` for
  the bootstrap's seed flag, the same find-or-create-and-save inside the caller's transaction),
  implemented by `SystemSettingService`; every declared module of a higher rank allows
  `admin::api`. The module baseline is unchanged at 110 edges (none of the four was a violation);
  the authorization matrix and the OpenAPI document are unchanged. No access policy: every gate is
  a role annotation. The setting records stay in `internal` beside the service that returns them, as
  for `orgchart`. **Finding:**
  the selection floor of `ArchitectureTest`'s `squadron_id` join-column rule counts the legacy
  `model` package tree as well as every entity by role, so each move of `model.dto` records lowers
  it (by 4 here) without the rule checking any entity less.
- `operation` — **done 2026-10-05.** Three steps, each green on its own:
  1. *Access policy out of the scope hub* (§5.4, ADR-0236): `OperationAccessPolicy`, bean
     `operationAccessPolicy`, holds `canSeeOperation`, `canSeeOperationLedger` and
     `canEditOperation` verbatim, on the scope kernel's `canSeeSquadron`/`canEditSquadron`. The
     eight operation endpoints, the operation live-sync room and the payout ledger check ask it;
     `OwnerScopeService` and `AccessGateService` lost the three methods and `AccessGateService` its
     `OperationRepository`. The authorization matrix changed on exactly those eight lines, in
     expression text only. `OperationAccessPolicyDifferentialTest` compared the old and the new
     gate over 22 callers (admin unpinned and pinned to either unit; members of zero, one and two
     units; a member pinned to an own and to a foreign unit; a Bereich lead reaching a child Staffel
     by cascade; guests below member — each with and without the participant escape), five
     operations (either unit, a child unit, ownerless, unknown) and the three gates, 330 verdicts,
     before the old methods went; it keeps comparing against a verbatim copy of them. Proven able
     to fail by dropping the participant escape. `TenancyGuardTest` counts the policy as a scope
     gate type.
  2. *The mission detach command* (§5.3, write family 10): `mission.api.MissionCommands#
     detachFromOperation`, implemented by `MissionService` with `MANDATORY`; the operation delete
     calls it instead of writing `Mission` rows. Same transaction, same order, no counter moves,
     `OPERATION_DELETED` still recorded after the delete. `mission` now publishes an `api` package,
     so every declared module above rank 9 allows `mission::api`.
  3. *Move*: `OperationController` into `operation.web`; the five services, the policy,
     `OperationPayoutStatus` with its repository and eight DTOs into `operation.internal`. The
     module is declared (floor 21) and publishes nothing.
  The module baseline shrank by the two `scope -> operation` edges, **110 → 108**.
  `OperationModuleContractTest` pins the endpoints per caller, the delete, the command's
  transaction rule and that the scoped list and the per-row gate admit the same operations.
  **Corrections:** (1) the `Operation` entity, `OperationStatus`, `OperationRepository`,
  `OperationMapper` and `OperationDto`, `OperationCreateDto`, `OperationReferenceDto` cannot leave
  the layer packages yet: `Mission`, `MissionDto`/`MissionListDto`, `MissionMapper`,
  `MissionService` and `BusinessMetricsCollector` use them, so a move closes a package cycle with
  `model`, `mapper`, `repository`, `service` or `task`. They stay assigned to `operation` by the
  `^Operation` name rule and move with P3-8, when `Mission.operation` becomes an id. (2) So the
  policy does not yet own the operation's JPQL scope fragment (§5.4): `OPERATION_SCOPE_PREDICATE`
  stays in `ScopeSpecifications`, used by `OperationRepository`; that the list query and the gate
  still widen together is now tested end to end instead. (3) The detach command removes the
  foreign write, not the `mission -> operation` edges: those are the association and the DTO
  embedding of (1), and leave with P3-8 and the `OperationSummaryProvider` SPI.
  *(P4-4, 2026-10-11: the types moved into `operation.internal` and the policy owns the fragment,
  §7.6.)*
- `promotion` — **done 2026-10-05** (P2-3), the second module, after `operation`, to take its access
  policy out of the scope hub. `PromotionAccessPolicy` (bean `promotionAccessPolicy`, `promotion.internal`) owns
  the per-Staffel feature flag, the read gate and the feature assertion; the three methods left
  `OwnerScopeService` and `RequestScopeResolver`. The generic Staffel gates the promotion services
  also use (`currentSquadronId`, `canSeeSquadron`, `canEditSquadron`, `hasAmbiguousStaffelContext`)
  stay in the scope kernel, because other modules use them. No SpEL referenced the moved methods,
  so the authorization matrix did not change. The differential verdict test
  (`PromotionAccessPolicyVerdictTest`, 17 cases) ran the policy and the hub side by side over the
  rows of §5.4 that apply to this gate and now keeps the recorded verdicts; the per-row escapes of
  §5.4 do not apply, because the per-row checks are the unchanged kernel `canSeeSquadron` /
  `canEditSquadron`. Controllers moved to `promotion.web`, everything else to `promotion.internal`.
  The GDPR deletion reaches the module through `MemberEvaluationErasure` (`MANDATORY`), which sits
  in the `service` package beside `UserDeletionService`, assigned to `promotion` by a `class` rule
  (replaced by the module's `UserErasureParticipant` in P4 slice a, §7.6).
  **Correction to §5.2:** a module cannot publish a type in `<module>.api` while the caller sits in
  a layer package the module itself depends on — `service → promotion.api` plus `promotion →
  service` closes a top-level package cycle; the type is carried into Phase 4 and replaced by the GDPR
  participant SPIs of §7.6.
  With that, `promotion` publishes nothing. The move removes no baseline edge (108).
- `personalinventory` — **done 2026-10-05** (P2-4). Mein Inventar moved out of the layer
  packages: the member, admin and UEX location-picker controllers into `personalinventory.web`, the
  item entity, the location-type enum, the repository, the service, the mapper and the records into
  `personalinventory.internal`. The module never consulted the scope hub — its gate is the owner
  (`@CurrentUserId`) and the role annotations — so no access policy moved. Its one inbound edge,
  the GDPR deletion's bulk delete, goes through `personalinventory.api.PersonalInventoryErasure`
  (`MANDATORY`), which the module publishes as its `api`; unlike promotion the module depends on
  nothing in `service`, so no package cycle forces the type out of its module. It is carried into
  Phase 4 like `MemberEvaluationErasure` (§7.6). Every declared module of a higher rank allows
  `personalinventory::api`. The move removes no baseline edge (108). (P4 slice a, §7.6, replaced the
  command by the module's `UserErasureParticipant`; the module now publishes nothing and the nine
  declarations that allowed `personalinventory::api` dropped it.)
- `exchange` (as a package) — **done 2026-10-10.** Its "must stay green" period starts here
  (D-01); Phase 5 extracts it next (D-23).
  1. *Characterise*: the relay contract was already pinned (G-18 wire contract and golden answers,
     `ExchangeRelaySeamParityTest`, the relay OpenAPI document, the authorization matrix);
     `BlueprintUploadPreviewServiceTest` was added for the one exchange path a lower module used.
  2. *Inversions* (5 baseline edges, **108 → 103**; the `blueprint -> exchange` and
     `scope -> exchange` pairs are gone): the blueprint upload preview hands the envelope to the
     blueprint-owned SPI `service.BlueprintEnvelopeReader` (since P3-7a in `blueprint.api`; implemented by
     `ExchangeBlueprintEnvelopeReader`, same binding, validation and draft resolution in the
     caller's transaction); `PersonalBlueprintService`, `CustomJwtGrantedAuthoritiesConverter` and
     `UserDeletionService` ask `platform.api.ClientDirectory` (`displayNames`, `isGatewayClient`,
     `isGatewayServiceAccount`); the display-name projection is nested in
     `ExchangeClientRepository` (the P1-4 pattern).
  3. *Move*: 152 classes. `exchange.web` holds the eleven controllers, `exchange.internal.dto` the
     34 relay wire records, `exchange.internal` everything else — including `ActingMemberFilter`,
     `SandboxProfileGuard` (its `spring.factories` entry follows), the change-source transaction
     manager and its configuration from `config`, and `DatabaseActingMemberAuthorities`.
  No access policy left the scope hub: the exchange has none there. Its gates are its own
  (`exchangeGate`, `connectedAppsGate`); the one scope-hub call, `canViewJobOrders` in
  `ExchangeDemandService`, is the job-order gate the exchange asks as a caller.
  **Corrections:** (1) the move needed two more inversions than the eleven inbound edges show,
  because once the exchange depends on the service layer any `config -> exchange` edge closes a
  `config -> exchange -> service -> config` package cycle: `SecurityConfig` takes the filter from
  the new SPI `platform.api.ActingMemberFilterProvider` (same constructor arguments, same place
  in the chain, the approval filter anchored on the filter's runtime class), `ActingMemberHeader`
  moved to `platform.api` (the metrics filter reads the client header) and `KeycloakSyncProperties`
  to `identity.api` (`FirstPartyClientIds` reads it). (2) The exchange's ArchUnit rules were keyed
  on the package segment `exchange` and on the service package tree; both would have changed
  meaning. The relay-controller rules now select controllers mapped under `/api/v1/exchange/`
  (still 8 classes, 14 methods), the reduced-authority rule every non-web exchange class
  (52 → 160), and the service-layer `SecurityContextHolder` and read-only-transaction rules also
  select the exchange's non-web classes, so the 28 helpers that left the service package tree stay
  checked. (3) §5.2's `web` cannot hold the relay DTOs, as with the org chart; they sit in
  `internal.dto`, which keeps the `exchangeDtosStayInTheExchangeLayer` selection (34) unchanged.
- `bank` as a package — **done 2026-10-10** (P2-5). All 126 bank classes of the layer packages moved:
  the nine controllers (eight Kartellbank, `OrgUnitBankController`) into `bank.web`, everything else
  — entities, repositories, services, mappers, records, the PDF formats, the ledger-integrity task
  and the org-unit side behind `OrgUnitBankAccessService` — into `bank.internal`; `BankAmounts` and
  `BankBookingRequestType` joined `bank.api`, because the published events and
  `BankConflictException` carry them. Three steps, each green on its own:
  1. *Access out of the scope hub* (§5.4): the bank's gates were already the bank's —
     `bankSecurityService` for the Kartellbank and the `OrgUnitBankAccessService` seam for the org-unit
     side. What the hub still held for the bank left it: the `AREA_MEMBERS` cascade
     (`currentUserIsMemberOfAreaCascade`, REQ-BANK-048) is decided in the seam from the kernel's direct
     memberships; `OrgUnitBankAreaCascadeDifferentialTest` compared it with the hub method over 480
     verdicts (authenticated or anonymous, admin or not, unpinned or pinned, eight membership sets,
     five target units) and keeps a verbatim copy; dropping the child-unit clause fails it.
     `currentOwnLevelOversightScope` (REQ-BANK-022) and `currentUserHasAreaOrOlOversight`
     (REQ-BANK-028) had no production caller left after REQ-BANK-039 and REQ-BANK-037 and were
     removed. `AccessGateService` held no bank gate. No SpEL changed.
  2. *Inversions* (§5.3): `OrgUnitMembershipService` and `UserDeletionService` snapshot and record
     the responsible holders through `orgunit.api.ResponsibleHolderTracker`; `UserSyncService` runs
     the holder reconciliation through `identity.api.UserSyncFollowUp`; the GDPR handle anonymisation
     reaches the bank tables and the bank audit marker through `BankHandleSnapshots` (`MANDATORY`).
     The module baseline shrank by `identity -> bank` and `orgunit -> bank`, **103 → 101** (re-derived after the exchange move).
  3. *Move*, with the seam rules re-keyed to the module (§5.4): `BANK_DOMAIN` is the `bank`
     package tree minus `ORG_UNIT_BANK_SIDE` (class literals), plus `BANK_CLASSES`, which now names
     only the bank types outside the package; the bridge set is still exactly
     `OrgUnitBankAccessService`. Like `EXCHANGE_NON_WEB`, `BANK_NON_WEB` keeps the bank's
     non-controller classes in the security-context and read-only-transaction rules after they left
     the `service` tree (floors 495 and 498). `BankAuditService`, `bank_audit_event`, the lock order
     and the money arithmetic are untouched.
  **Corrections:** (1) §5.4 expected bank gates in `OwnerScopeService`/`AccessGateService`; there was
  one membership query and two dead methods. (2) Like `MemberEvaluationErasure`,
  `BankHandleSnapshots` cannot sit in `bank.api` while its caller lives in the `service` package the
  bank depends on; it stays there, registered in `BANK_CLASSES` and assigned by a `class` rule, and
  is carried into Phase 4 with the GDPR participants (§7.6; still open after P4 slice a, it went
  with the anonymisation slice, P4 slice b). (3) `BusinessMetricsCollector` (`app`)
  still reads the booking-request repository directly; it leaves with the per-module gauges of §5.1.

### 7.5 Phase 3 — the business core

In dependency order, each with its command API, its observer SPIs, its access policy and its REST
wave: `materialexchange` (the offer ratchet as `StockChangeObserver`), `refinery`, `joborder`,
`mission`, `inventory` (`StockCommands`, the earmark target SPI, the lot-lock protocol of ADR-0229
moved into inventory, and `MaterialExchangeOffer.inventoryItem` as an id), `hangar`, `blueprint`. The eight business associations become id references
as each pair is decoupled.

- `materialexchange` — **done 2026-10-10.**
  1. *Characterise*: the ratchet's audit rows were pinned (`MaterialExchangeOfferRatchetDataTest`,
     `ExchangeStockWriteControllerTest`); `MaterialExchangeStockLinksDataTest` adds the two other
     reads the Lager made of the Materialbörse — the stolen-split floor and the merge refusal.
  2. *Observer SPI* (§5.3, write family 3): `inventory.api.StockChangeObserver` (`@ObserverSpi`:
     `lower`, `beforeDelete`, `beforeWipe`, `beforeUserPurge`), implemented unchanged by
     `MaterialExchangeOfferRatchet` (every method `MANDATORY`, same clamps, same
     `MARKET_OFFER_REDUCED`/`REMOVED` rows before the cascading delete); the reason codes and the
     effects record moved with it as `StockChangeReason` and `StockChangeEffects` (codes
     unchanged). A second inventory-owned SPI, `StockOfferLookup` (`isOffered`,
     `activeOfferedAmount`), answers the two reads. The Lager, the three job-order handover and
     production services, the exchange's stock writes and the account deletion use only these.
     Baseline **103 → 98** (the `inventory -> materialexchange` pair is gone).
  3. *Move*: the two controllers into `materialexchange.web`, the 8 entities and enums, 13 DTOs,
     4 repositories, 5 services and the lookup into `materialexchange.internal`.
  No access policy left the scope hub: the boards are gated by `hasRole('KRT_MEMBER')` and decide
  ownership inside their services; their only scope-hub call is `currentOrgUnit` for stamping.
  No command API is published yet: no other module writes an offer or a request. **Corrections:**
  (1) `beforeWipe` takes the three scope components instead of `ScopePredicate`, which lives in
  the `service` package, so `inventory.api` depends on no layer package; `beforeUserPurge` (left
  the observer in P4 slice a, §7.6) stays on
  the stock observer rather than a GDPR participant until §7.6 introduces those. (2)
  `MaterialExchangeOffer.inventoryItem` stays an association for now: converting it rewrites the
  fetch plan of eight queries and two entity graphs, and nothing needs the id until `InventoryItem`
  leaves `model` with the inventory move (P3-5), where it belongs. **Tracked for P3-5:** convert
  `MaterialExchangeOffer.inventoryItem` to the item id when `InventoryItem` moves — done in
  P3-5d-4 (2026-10-10), ahead of the move. *Correction:* it was eight board queries, one entity
  graph and two Lager queries (the releasable-row reads' `NOT EXISTS`), not two entity graphs. (3)
  `ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE` is public, because the offer board's JPQL
  splices it from another package.
- `refinery` — **done 2026-10-10.**
  1. *Characterise*: `RefineryModuleContractTest` pins that create, update and cancel each record
     their audit event in the command's transaction, and that a member of another org unit is
     refused on every per-order entry point (read, update, cancel, store) and a logistician of
     another org unit on every on-behalf entry point (the "403 per foreign entry point" of the
     risk table).
  2. *Access policy out of the scope hub* (§5.4, ADR-0236): `RefineryAccessPolicy` (bean
     `refineryAccessPolicy`) holds `canSeeRefineryOrder` (id and entity), `canEditRefineryOrder`,
     `canViewUserRefineryOrders` and `canManageUserRefineryOrders`. The scope kernel publishes the
     two blocks those gates shared privately, unchanged: `permitsOwnedRow` (owner escape, ownerless
     rule, org-unit scope) and `canActOnTargetUser`. The eight refinery-order endpoints, the
     controller's on-behalf owner check and the live-sync room ask the policy; the authorization
     matrix changed on exactly those eight lines, in expression text. The differential verdict
     test compared old and new over 9 callers × (7 orders × 2 gates + 5 target members × 2
     gates) = 216 verdicts before the old methods went (proven able to fail by dropping the owner
     from the edit gate) and keeps comparing against a restatement on the kernel primitives.
  3. *Inversions*: the blueprint craftability asks its own SPI `CraftabilityYieldSource` (with its
     own `YieldSlice` record, so the SPI adds no `blueprint -> inventory` edge), implemented by
     `RefineryOrderService`; the exchange's refinery draft asks the published
     `refinery.api.RefineryDraftBuilder`.
  4. *Move*: controllers into `refinery.web`; the extract and draft records into `refinery.api`
     (the exchange uses them); the two services, the policy, the live-sync authorizer, the import
     settings and the store requests into `refinery.internal`.
  Baseline **96 → 92** (`scope -> refinery` 3, `blueprint -> refinery` 1).
  **Corrections:** (1) like the operation, `RefineryOrder`, `RefineryGood`, `RefineryOrderStatus`,
  `RefineryOrderRepository` (with `RefineryMissionProfitAggregate`), `RefineryOrderMapper` and
  `RefineryOrderDto`/`RefineryGoodDto`/`RefineryOrderListDto` stay in the layer packages: `Mission`,
  `MissionFinanceSummaryDto`, `MissionService`, `MissionFinanceEntryService`, `LocationService` and
  `UserDeletionService` use them, so a move closes a package cycle. They move with P3-8 (the
  `mission -> refinery` association and the `MissionFinanceContributor` SPI), where
  `RefineryOrder.mission` becomes an id. So the policy does not own the refinery's JPQL scope
  fragment yet either. *(P3-8b, 2026-10-11: the association and the SPI are done; two imports still
  block the move, now tracked as P4-3; done 2026-10-11, §7.6.)* (2) No command API: no other module writes a refinery order. The
  store still creates Lager rows itself (write family 2 of the evidence appendix); that write
  becomes a call of the Lager's `StockCommands` with P3-5.
- `joborder` — **done 2026-10-10.**
  1. *Characterise*: `JobOrderModuleContractTest` pins that create, priority change, assignee add,
     status change and delete each record their audit event in the command's transaction, and that
     a member of another org unit is refused on every per-order read and write entry point (the
     "403 per foreign entry point" of the risk table).
  2. *Access policy out of the scope hub* (§5.4, ADR-0236): `JobOrderAccessPolicy` (bean
     `jobOrderAccessPolicy`) holds `canSeeJobOrder` (id and entity), `canEditJobOrder`,
     `canSeeJobOrderBlueprintOwners`, `canSeeJobOrderInventoryOwners`, `canSeeJobOrderAsRequester`
     and `canEditJobOrderAsRequester`, on the kernel's `canViewJobOrders`, `canSeeSquadron`,
     `canEditSquadron` and `currentUserIsMemberOfOrgUnit`; the queue capabilities
     (`canViewJobOrders`, `canViewOwnJobOrders`) stay in the kernel, which identity and the
     exchange ask too. The Lager's row flag for earmarked stock asks its own SPI
     `inventory.api.EarmarkTargetPolicy` (`mayEditJobOrderEarmarks`), which the policy implements,
     so the earmark target SPI of P3-5 starts here. The order, claim, item-stock and
     material-collection endpoints, the order query and the live-sync room ask the policy; the
     authorization matrix changed on exactly 28 lines, in expression text. The differential verdict
     test compared old and new over 28 callers × 21 orders × 7 gates = 4116 verdicts before the old
     methods went (proven able to fail by dropping the Spezialkommando escape) and keeps comparing
     against a restatement on the kernel primitives. Baseline **92 → 87**
     (`scope -> joborder` is gone).
  3. *Inversion*: the order's stock projection asks `service.ClaimBucketSource` for the
     Spezialkommando claim view, implemented by `MaterialClaimService`, so the claim service moves.
  4. *Move*: the four controllers into `joborder.web`; the order, assignee, priority, item
     production, handover, handover-report, blueprint-owner, integrity, demand and claim services,
     the policy, the live-sync authorizer, the org-unit resolver, the integrity task, the item,
     material and handover-item repositories and the request DTOs into `joborder.internal`.
  The move leaves the baseline at **87**: the 18 edges still into the job order are the Lager's
  (17, the earmark and its reads, P3-5) and the assignee redaction in identity (1).
  **Corrections:** (1) `JobOrder` and its items, materials, handovers and claims, their
  repositories, `JobOrderMapper`, `JobOrderHandoverMapper`, `JobOrderItemHandoverMapper`, the
  response DTOs, `JobOrderItemService`, `JobOrderStockProjectionService`,
  `JobOrderMaterialRequirementResolver` and `QualityBucketAllocator` stay in the layer packages:
  the Lager's earmark (`InventoryJobOrderAllocation`) holds the job-order association, and the Lager, the refinery, the exchange's
  demand board, the GDPR services and the business metrics read them, so a move closes a package
  cycle. They move once the earmark becomes an id with P3-5. (2) No command API: no other module
  writes a job order; the exchange only reads the demand. (3) The earmark caller rule of the risk
  table (the ungated order-linked stock query callable only from joborder) is P3-5's: that query
  is the Lager's and still has no module to be guarded from.
- `mission` — **done 2026-10-10.**
  1. *Characterise*: `MissionModuleContractTest` pins that create, step add, core patch, join and
     delete each record their audit event in the command's transaction; that a member of another
     org unit is refused on every per-mission read entry point of an internal mission (detail, the
     three finance reads, join); and that a mission manager of another org unit is refused on the
     write entry points of a public mission while still reading it.
  2. *Access policy out of the scope hub* (§5.4, ADR-0236): `MissionAccessPolicy` (bean
     `missionAccessPolicy`) holds `canSeeMission` (the parent-chain walk, the public escape, the
     ownerless rule) and `canEditMission`, on the kernel's `canSeeSquadron`/`canEditSquadron` and
     `isMemberOrAbove`. `MissionSecurityService` keeps the finer write gates (manage, owner change,
     participant access, finance entries) and asks the policy for the edit scope. The seven read
     gates, the live-sync room and those five checks switched; the authorization matrix changed
     on exactly seven lines. The differential verdict test compared the policy, the live scope hub
     and a restatement over 18 callers × 25 missions × 2 gates = 900 verdicts before the old
     methods went (proven able to fail by stopping the parent walk at the mission itself) and
     keeps comparing against the restatement. Baseline **87 → 85** (`scope -> mission` is gone).
  3. *Inversions* (write families 8 and 9): `hangar.api.ShipDeletionObserver`, implemented by
     `MissionUnitShipRelease` (the unit drops the ship and records `MISSION_UNIT_UPDATED`, as
     before), and `catalogue.api.JobTypeDesignationObserver`, implemented by
     `MissionLeadDesignationRelease` (the participants' lead flags are cleared with the same
     query). `hangar` becomes a declared module with only its `api` package. Baseline **85 → 81**.
  4. *Move*: the two controllers into `mission.web`; the mission, structure, participant,
     timeline and finance services, the security service, the policy, the viewer-access service,
     the participant-target resolver, the live-sync authorizer, the crew, frequency, objective,
     step and unit repositories and the 30 request DTOs into `mission.internal`.
  **Corrections:** (1) `Mission` and its children, `MissionRepository` with its authorization
  fragment, the participant, finance-entry and ownership repositories, the finance aggregates,
  `MissionMapper` and the response DTOs stay in the layer packages: the Lager (earmarks and the
  sale entries, write family 7), the refinery, the operation's finance and payout services and the
  GDPR services use them. They follow with P3-5 (`StockSoldForTarget`, the earmark id) and P3-8
  (the `Mission.operation`, `Mission.refineryOrders` and `MissionUnit.ship` associations, the
  `MissionFinanceContributor` and `OperationSummaryProvider` SPIs). (2) The mapper's viewer-access
  seam `MissionViewerAccess` moves from `mission.internal` to `mapper`, beside `MissionMapper`,
  because a `mapper -> mission` edge would close a package cycle with the moved services. (3) No
  new command API: no other module writes a mission beyond `detachFromOperation`. (4) Peer
  redaction (BE-SIMP-05) already lived in `mission.internal.MissionPeerRedactor`; the section
  counters are kept verbatim (`@DynamicUpdate`, no ADR needed). (5) `TenancyGuardRules` counts a
  caller of an `@ObserverSpi` whose implementation writes tenant data as a writer, so moving a
  write behind an observer does not shrink what the guard checks.
- `inventory` — in five parts, each its own pull request and releasable on its own (owner
  decision 2026-10-10): **5a** the contract test and the access policy; **5b**
  `inventory.api.StockCommands` with the ADR-0229 lot-lock protocol moved in, for the exchange's
  stock write and undo; **5c** the job-order and refinery Lager writes through `StockCommands`,
  with the APPSEC-01 book-in check once, before any lookup; **5d** the earmarks
  (`InventoryJobOrderAllocation.jobOrder`, `InventoryMissionAllocation.mission`) and
  `MaterialExchangeOffer.inventoryItem` as ids, and `StockSoldForTarget` for the sale entries
  (write family 7), which also settles P3-13 by inversion instead of a rank swap; **5e** the move
  (deferred to Phase 4, see 5e below),
  NullAway and the idioms.
  - **5a — done 2026-10-10.** `InventoryModuleContractTest` pins that create, note update,
    personal rebook and book-out each record their audit event in the command's transaction, and
    that a logistician of another org unit is refused on every per-row write entry point (book-out,
    personal rebook, note, org unit, stolen, delivered, the three allocation verbs), each with a
    body that passes validation. `InventoryAccessPolicy` (bean `inventoryAccessPolicy`) holds
    `canSeeInventoryItem` and `canEditInventoryItem` on the kernel's `permitsOwnedRow`, and
    `canManageUserInventory` on `canActOnTargetUser`; it implements `inventory.api.BookInPolicy`
    (`mayBookInFor`), which the job-order production and the refinery store ask instead of the
    scope hub. The nine row-write gates switched (the authorization matrix changed on exactly
    those nine lines), and so did the Lager row flag of `StockViewerAccessService` and the two
    on-behalf checks of `InventoryItemService`. The differential verdict test compared the policy,
    the live scope hub and a restatement over 9 callers × (7 rows × 2 gates + 5 target members)
    = 171 verdicts before the old methods went (proven able to fail by dropping the owner from the
    edit gate) and keeps comparing against the restatement. Baseline **81 → 79**
    (`scope -> inventory` is gone). *Correction:* the read and edit scopes coincide over this
    matrix, as for the refinery, so swapping the edit gate's scope for the read scope is not
    caught; the owner escape and the ownerless rule are.
  - **5b — done 2026-10-10.** `inventory.api.StockCommands` (`lockLots`, `lockLotRows`, `bookIn`,
    `bookOutRow`, `markStolen`, all `MANDATORY`), implemented by `InventoryStockCommands`, holds
    the ADR-0229 lot-lock protocol unchanged (key, prefix, ascending order, row-lock order) and the
    exchange's Lager writes; `ExchangeStockWriteService` and `ExchangeUndoService` go through it
    (ADR-0229 *Implementation of the amendment*). `InventoryStockCommandsTest` pins the key
    derivation, the lock order and the book-in's `INVENTORY_ITEM_CREATED`; the four
    `ExchangeStockWriteConcurrencyIntegrationTest` cases stay green. `TenancyGuardRules` counts a
    caller of a published `…Commands` interface whose implementation writes tenant data as a
    writer, as it does for observers; the connected-apps controller is selected through the undo
    since, and its two installation handlers are listed as no tenant write. *Correction:* an
    incremental compile after this change crashed Error Prone (`invalid end position` in
    `UnnecessaryParentheses`/`ThreadJoinLoop` on a record of `ExchangeStockWriteService`); a full
    recompile passes, so it is a stale end-position table of the incremental build, not a code
    fault.
  - **5c — done 2026-10-10.** The job order and the refinery write no Lager row any more:
    `StockCommands` gains `takeFromEarmarkedRow` (the material handover and the production
    consumption: delete a depleted row after its offers are told, else lower the order slice and
    the mission slices), `consumeEarmarkedItems` (the item handover, oldest row first),
    `lowered` (the observers' notice after the order is saved, in the old order),
    `releaseJobOrderEarmarks` / `…MaterialEarmarks` / `…GameItemEarmarks` (the bulk earmark
    releases, after the loop as before), `bookInFromProduction` and `bookInFromRefinery` (row,
    earmarks, merge and audit verbatim; the job order and the mission go in by id, so
    `StockCommands` names no foreign entity and the baseline gains no edge), all `MANDATORY`, and `requireBookIn`, the one book-in rule
    the Einbuchen, the production and the refinery store share (APPSEC-01). The production still
    checks before any lookup and the refinery store where it did. *Behaviour unchanged by owner
    decision:* the refinery store keeps its two differences — a personal book-in for another
    member passes the owner check alone, an ownerless order needs Logistician+ — as the named
    `BookInRule.refineryStore()`, pinned by `InventoryStockCommandsTest` and noted in REQ-INV-032 and
    REQ-SEC-005 until the owner decides. `StockWriteOwnershipTest` makes "only inventory writes
    Lager rows" a gate (a `save*`/`delete*`/`@Modifying` call on the Lager repository, a row or
    earmark setter, an earmark mutator), proven able to fail on a planted writer; the GDPR
    orchestrator was its one listed exception until Phase 4 (gone with P4 slice a, §7.6). The job-order and refinery unit tests
    run against a real `InventoryStockCommands` over their mocks, so they still verify the writes.
  - **5d-1 — done 2026-10-10.** Write family 7: `inventory.api.StockSoldForTarget`
    (`soldForMissions`, an `@ObserverSpi`, `MANDATORY`), implemented by
    `mission.internal.MissionSaleIncome`, books the income of stock sold out of a mission's earmark;
    the Lager still computes each share and skips a non-positive one, the mission still books
    nothing for a mission the seller does not take part in and saves after the loop. Baseline
    **79 → 75**.
  - **5d-2 — done 2026-10-10.** `InventoryMissionAllocation.mission` becomes `missionId` (the same
    `mission_id` column and foreign key; no migration), the first of the eight business associations
    of §5.6 turned into an id. What the Lager shows of the mission (name, planned start) comes from
    the read SPI `inventory.api.EarmarkTargets.Missions`, implemented by `MissionEarmarkTargets` with
    one projection query, through `InventoryEarmarkTargetCache` (`EarmarkTargets.Lookup`): a
    per-transaction memo whose first miss loads every mission the Lager rows of the persistence
    context are earmarked to, so a page costs one lookup whatever its size. The mapper, the
    allocation commands and the check-in's existence check (same `Mission not found`) ask it; the
    DTOs and audit labels are byte-identical. `InventoryEarmarkReadNoNPlusOneTest`, added before
    the change, keeps six Lager reads at the same statement count for 25 rows as for 3 (proven
    able to fail: without the context scan the by-material read rises from 9 to 31 statements).
    Baseline **75 → 67** (`inventory -> mission` is gone).
  - **5d-3 — done 2026-10-10.** `InventoryJobOrderAllocation.jobOrder` becomes `jobOrderId` (same
    column and foreign key). The order's running number comes through
    `EarmarkTargets.JobOrders` (`JobOrderEarmarkTargets`, one projection query) and the same
    per-transaction memo, which now scans the context for both earmark kinds; the mapper's
    `JobOrderAllocationDto`, the delivered toggle's and the check-in's audit labels are
    byte-identical. The job order no longer reaches into a row's earmark collections: the handover
    asks `InventoryAllocations.jobOrderSlice`, and unlinking a row goes through
    `StockCommands.releaseJobOrderEarmark`. `StockWriteOwnershipTest` now also refuses a reach into
    the earmark collections outside the Lager, proven on a planted drain.
    `InventoryEarmarkReadNoNPlusOneTest` stays green (proven able to fail: without the context
    scan the by-material read rises from 8 to 30 statements). Baseline **67 → 61**; the remaining
    `inventory -> joborder` edges are the requirement checks of the allocation and the order-facing
    stock reads (`JobOrderItemStockGroupDto`, the stock row projections), which move with 5e.
  - **5d-4 — done 2026-10-10.** `MaterialExchangeOffer.inventoryItem` becomes `inventoryItemId`
    (the same `inventory_item_id` column, foreign key and `ON DELETE CASCADE`; no migration). The
    board's JPQL joins the row as an entity join (`LEFT JOIN InventoryItem ii ON ii.id =
    o.inventoryItemId`), so its filters, sort and clamp stay in SQL; the detail's entity graph
    drops the row. The board then loads the page's rows with their material in one read
    (`InventoryItemRepository.findWithMaterialByIdIn`) and hands each row to the DTO mapping; the
    detail, the update's clamp and the audit label load the one row by id. The DTOs and the audit
    labels and details are byte-identical. `MaterialExchangeBoardNoNPlusOneTest`, added before
    the change, keeps the "mein" board at the same count for 25 offers as for 3 (5 statements)
    and the detail within the bound (proven able to fail: loading the rows per offer gives 10
    against 54). The Materialbörse no longer holds an association into the Lager; its remaining
    use of `InventoryItem` is the type, which stays in `model` like every entity. Baseline
    unchanged.
  - **5e — done 2026-10-10, without the move.** The last eleven `inventory -> joborder` edges
    go: the two order-facing Lager reads (the material collection and the item stock of an
    order, REQ-ORDERS-028) move into `joborder.internal.JobOrderStockReadService`, which reads
    each row's slice through `InventoryAllocations.jobOrderSlice`; the Lager's link checks
    (REQ-ORDERS-018, REQ-INV-031) ask `EarmarkTargets.JobOrders` for what an order requires
    (`requiredMaterialIds`, `requiredGameItemIds`); the two earmarked-stock projections
    (`JobOrderMaterialStockRow`, `JobOrderGameItemStockRow`) move to `inventory.api`. No
    behaviour changes: the 404s, the messages and the DTOs are the same. Baseline **61 → 50**.
    The tenancy guard's controller selection drops from 25 to 23 (floor 24 → 23): the
    material-collection and item-stock controllers were selected only because they held
    `InventoryItemService`, and they have GET handlers only. The model layer's join-column
    selection floor drops to 354 with the two projections. NullAway takes in the whole
    `inventory` package tree. The §8.1 idioms have no site left in the inventory code: its
    concatenated `@Query` strings splice the scope constant or split a token, and its one
    `isPresent()` is a plain boolean.
    *Correction — the move waits for Phase 4:* moving any inventory implementation class into
    `inventory.internal` closes a package cycle (probed: moving `StockViewerAccessService`
    alone gives 15 cycles, among them `admin -> mapper -> inventory -> service -> admin`). Layer
    classes consume `inventory.api`, while the inventory implementation needs the layers
    (`OwnerScopeService`, `AuthHelperService`, the mappers, the repositories). The layer
    consumers are: `UserDeletionService` (privacy, Phase 4), `BlueprintCraftabilityService`
    (P3-7), `MeController` (identity, Phase 4), `OrgUnitMembershipService` and
    `OrgUnitMembershipRepository` (`InventoryOrgUnitReconciler`, Phase 4),
    `JobOrderStockProjectionService` and `JobOrderMapper` (joborder),
    `JobOrderRepository.findEarmarks` and `MissionRepository.findEarmarks`, and the Lager's
    own `InventoryItemMapper`, `InventoryItemRepository` and services. Mission and job order
    could move because no layer class used their packages. **Tracked for Phase 4:** move the
    inventory into `inventory.web` / `inventory.internal` once those consumers have left the
    layer packages (P4-1, §7.6), then NullAway covers it without a further change. *Update
    2026-10-11:* part 1 of P4-1 moved the services, the controller and the participants (§7.6);
    *Update 2026-10-12:* part 2 added the read API and moved the repositories, the mapper and the DTOs;
    the entities stay in `model` until the row is a record (§7.6).

- `hangar` — **done 2026-10-10** (P3-6).
  1. *Characterise*: `HangarModuleContractTest` pins that creating, updating and deleting a ship
     record `HANGAR_SHIP_CREATED`, `HANGAR_SHIP_UPDATED` and `HANGAR_SHIP_DELETED` in the
     command's transaction, and that a member of another org unit is refused on both per-ship
     write entry points (PUT and DELETE `/api/v1/hangar/ships/{id}`) with nothing recorded.
  2. *Access policy out of the scope hub* (§5.4, ADR-0236): `HangarAccessPolicy` (bean
     `hangarAccessPolicy`) holds `canSeeShip` and `canEditShip` on the kernel's `permitsOwnedRow`.
     The differential verdict test compared the policy, the live scope hub and a restatement over
     9 callers × 7 ships × 2 gates = 126 verdicts before the old methods went (proven able to fail:
     dropping the owner from the edit gate) and keeps comparing against the restatement. The two
     write gates switched; the authorization matrix changed on exactly two lines;
     `AccessGateService` lost `ShipRepository`. Baseline **50 → 48** (`scope -> hangar` is gone).
  3. *Command API*: `hangar.api.ShipCommands` (`addShipForClient`, `updateShip`, `deleteShip`,
     all `MANDATORY`), implemented by `HangarShipCommands` over `HangarService`; the exchange's
     ship write and undo call it instead of `HangarService`. Each command keeps its audit event.
  4. *Move*: `HangarController` into `hangar.web`; `HangarService`, `HangarImportService`,
     `FleetExportParser`, the policy and the ten hangar-only DTOs (home location, the import
     formats and response, the squadron overview) into `hangar.internal`. NullAway takes in the
     whole module: three findings, the two optional components of the squadron overview DTOs now
     `@Nullable` and one map lookup that cannot miss wrapped in `Objects.requireNonNull`. The §8.1
     idioms have no site in the hangar code (its concatenated queries splice a constant; its one
     `Math.max` has a single bound). The model layer's join-column selection floor drops 354 → 344
     with the ten DTOs.
  **Corrections:** (1) `Ship`, `ShipRepository`, `ShipMapper`, `ShipDto`, `ShipRequestDto` and
  `ShipTypeMatcher` stay in the layer packages: the exchange, the mission, the catalogue's
  location service and the GDPR services use them. (2) The squadron-overview DTOs carried no
  nullness; the overview has always sent `details` as `null` without the owner details and
  `locationName` as `null` for a ship without a location, which the annotations now state. No
  N+1 test was added: the move changes no query or fetch plan.

- `blueprint` — **in two parts** (P3-7a, P3-7b), each releasable on its own.
  - **P3-7a — done 2026-10-10.**
    1. *Characterise*: `BlueprintModuleContractTest` pins that adding, updating and removing a
       personal blueprint record `BLUEPRINT_ADDED`, `BLUEPRINT_UPDATED` and `BLUEPRINT_REMOVED` in
       the command's transaction. It also pins that another member reaches none of the owner's
       blueprints (404, nothing recorded) and that a plain member is refused on both overview
       reads.
    2. *Access policy* (§5.4): `BlueprintAccessPolicy` (bean `blueprintAccessPolicy`) gates the
       org-unit blueprint overview. Two matrix lines changed, in expression text only.
       `BlueprintAccessPolicyDifferentialTest` compares the policy, the live hub and a restatement
       for 14 callers (admin, officer without a seat, member of none, and each of the eleven
       membership roles). It is proven able to fail.
    3. *Inversions* (baseline **48 → 45**):
       - `blueprint -> inventory` is gone. The craftability asks its own read SPI
         `blueprint.api.CraftableStockSource`, which inventory implements in
         `InventoryCraftableStock`. Stock and pending refinery yield now share one record,
         `blueprint.api.CraftabilitySlice`, which replaces `CraftabilityYieldSource.YieldSlice`;
         `InventoryItemService#getOwnedStockSlices` is gone.
       - `identity -> blueprint` is gone. Creating a member account calls
         `identity.api.MemberCreatedObserver` (`@ObserverSpi`, `MANDATORY`), which
         `DefaultBlueprintGrantOnCreation` implements by granting the default blueprints, as
         before, in the creating transaction.
       - `blueprint` is a declared module with an `api` package. `CraftabilityYieldSource` and
         `BlueprintEnvelopeReader` moved there from `service`, and every higher-ranked module
         allows `blueprint::api`.
    **Corrections:**
    (1) §5.3 lists the default-blueprint grant as an after-commit event. It stays a same-transaction
    observer instead, because the grant records `BLUEPRINT_DEFAULTS_GRANTED` with the request's
    actor, and listeners never write audit rows. A failed grant still rolls the account creation
    back, exactly as before. The provisioning task still heals a missed grant.
    (2) The overview gate's predicate stays in the scope kernel (`RequestScopeResolver`). Identity's
    `/me` capability view reads it too, and an `identity -> blueprint` edge would break the ranks.
    The policy builds on it, and the differential test keeps both live copies in step.
  - **P3-7b — done 2026-10-10, without the move.** The exchange, the Materialbörse and the job
    order reach no blueprint implementation class any more. They go through five published
    interfaces:
    - `BlueprintProducts`, the product resolution: by key, by game item, all products, and the
      `scwiki_key` index;
    - `BlueprintImports`, the import preview and name resolution;
    - `BlueprintFamilies`, the variant families;
    - `DefaultBlueprintKeys`;
    - `PersonalBlueprintCommands` (`add`, `delete`, all `MANDATORY`), implemented by
      `PersonalBlueprintCommandService` over `PersonalBlueprintService`.

    Their records `ResolvedProduct`, `NameResolution` and `ParsedEntry` moved out of the services
    into `blueprint.api`. `ResolvedProduct.outputItemId` is now marked `@Nullable`, which it always
    was. The existing services implement the interfaces, so no behaviour changes. NullAway takes in
    the whole `blueprint` tree, which today is its `api` package. The §8.1 idioms were not swept,
    because the implementation does not move.

    *Correction — the move waits for P4-1:* `CraftableStockSource` is implemented in the layer
    package `service` (`InventoryCraftableStock`), because the inventory has not moved. So `service`
    depends on `blueprint.api`. Any blueprint implementation class in `blueprint.internal` needs
    `service` (the scope hub, the audit recorder), which closes a `service <-> blueprint` package
    cycle, as it did for the inventory. **Tracked as P4-2 (§7.6).** *Update 2026-10-12:* part 1 of
    P4-2 moved 55 types (§7.6); with `InventoryCraftableStock` in `inventory.internal` the move closes
    no cycle.

- `operation` ⇄ `mission` — **P3-8a done 2026-10-11.** `Mission.operation` becomes `Mission.operationId`
  (the same `operation_id` column and foreign key, still outside the row version and every section
  counter; no migration), the fourth of the eight business associations of §5.6 turned into an id.
  1. *Characterise*: `MissionOperationReadNoNPlusOneTest`, added before the change and green on the
     unchanged code, keeps the mission list at the same statement count for 25 linked missions as
     for 3 and pins every field of the `OperationDto` a mission DTO embeds (the owning squadron
     reference, version, timestamps, the `null` payout flag) and the `null` for a mission without an
     operation.
  2. *Read SPI* (§5.3): what a mission shows of its operation comes from
     `mission.api.OperationSummaryProvider`, implemented by `operation.internal.OperationSummaryReader`
     with one projection query (`OperationRepository.findSummaries`), through the per-transaction
     memo `mission.internal.MissionOperationSummaryCache` (`mapper.MissionOperationLookup`): its first
     miss loads every operation the missions of the persistence context belong to, so a page costs
     one statement whatever its size. `MissionMapper` builds the embedded `OperationDto` with the new
     `OperationMapper#toDtoFromSummary`, which `OperationMapperTest` pins equal to `toDto` of the
     entity. The existence checks of the create and the core patch ask the provider (same
     `Operation not found`). DTOs, audit labels and the 404/403 behaviour are unchanged. The list
     reads 4 statements for 3 and for 25 missions (proven able to fail: without the context scan it
     is 26 against 4).
  3. *JPQL*: `MissionRepository` (search, the detach finder, the unfinished-time check),
     `OperationRepository` (the time-range search, the participant check) and
     `ScopeSpecifications.OPERATION_SCOPE_PREDICATE` read `m.operationId`; the search's entity graph
     drops `operation`. `Operation.missions` stays as a read-only `@OneToMany` over the same column
     (`insertable = false, updatable = false`), so the finance, payout and delete code is unchanged
     and the delete's `missions.clear()` writes nothing.
  Baseline **45 → 43** (`Mission -> Operation`, `MissionService -> OperationRepository`); the
  architecture test's to-one association floor drops 148 → 147 with the association.
  **Corrections:** (1) The SPI is owned by the lower module, `mission.api`, and not `operation.api` as
  the step list read: `mission` (rank 9) may not depend on `operation` (rank 11), and the read SPI of
  every earlier pair is owned by its consumer. Its record `MissionOperationSummary` (`model.dto`)
  and the memo's interface (`mapper`) sit in layer packages, because the mapper reads them and a
  `mapper -> mission` edge closes a package cycle with the moved mission services (the reason
  `MissionViewerAccess` sits in `mapper` too). (2) The mission DTOs embed the full `OperationDto`,
  not `OperationReferenceDto`, so the wire shape and the OpenAPI schema stay as they are and three
  `mission -> operation` edges remain in the baseline: `MissionDto` and `MissionListDto` to
  `OperationDto`, and `MissionMapper` to `OperationMapper`. The baseline may only shrink, which is
  why the summary carries the status as its name instead of an `OperationStatus` and why the
  mapper keeps `OperationMapper`; the three leave when the mission DTOs stop embedding the
  operation's DTO (the REST DTO separation of §5.2). (3) The `Operation` entity, `OperationStatus`,
  `OperationRepository`, `OperationMapper` and the operation DTOs still do not move: read from the
  imports, `MissionDto`/`MissionListDto` (`model.dto`) and `MissionMapper`/`OperationFinanceService`
  (`mapper`) would close `model.dto ⇄ operation` and `mapper ⇄ operation` cycles, and
  `BusinessMetricsCollector` (`task`) reads the repository. They move once (2) is done. *(P4-4,
  2026-10-11: done, §7.6.)* The refinery
  association (`Mission.refineryOrders`, `RefineryOrder.mission`, the `MissionFinanceContributor` SPI)
  is the other half of P3-8 and still blocks the refinery's entities, repository and mapper (done
  as P3-8b below).

- `refinery` ⇄ `mission` — **P3-8b done 2026-10-11, without the move.** `RefineryOrder.mission` becomes
  `RefineryOrder.missionId` (the same `mission_id` column and foreign key, no migration) and the
  inverse `Mission.refineryOrders` is dropped, the fifth and sixth of the eight business
  associations of §5.6 turned into ids; only `Operation.missions`, kept as a read-only view, and
  `MissionUnit.ship` remain.
  1. *Characterise*: `RefineryOrderReadNoNPlusOneTest` (added before the change, green on the
     unchanged code) pins that the my-orders page and an order's detail issue a bounded number of
     statements whether a member's orders belong to 3 or to 25 missions, and that every row carries
     its mission's reference. `RefineryMissionLinkContractTest` (also green on the unchanged code,
     since it reads and writes the link through SQL) pins the mission's finance totals with a linked
     order, the three operation finance views embedding the order with its mission reference, and
     that deleting a mission keeps the order, clears its link and moves its row version.
  2. *Read API* (§5.3): what an order shows of its mission comes from `mission.api.MissionReferences`
     (the refinery sits above the mission, so this is a plain call down, not an inversion),
     implemented by `mission.internal.MissionReferenceReader` with one projection query
     (`MissionRepository.findReferencesByIdIn`), through the per-transaction memo
     `refinery.internal.RefineryOrderMissionCache` (`mapper.RefineryOrderMissionLookup`): its first
     miss loads every mission the orders of the persistence context are linked to, so a page costs
     one statement whatever its size. `RefineryOrderMapper` builds the embedded
     `MissionReferenceDto` through the lookup. The create and update paths keep their checks
     (`MissionRepository.findById`, the participant requirement) and write the id. The my-orders page
     reads 3 statements for 3 and for 25 orders (it was 2: the mission was a join fetch of the page
     query); proven able to fail: without the context scan it is 27 against 5.
  3. *Finance SPI* (§5.3): `mission.api.MissionFinanceContributor` (`contributionTo`) returns the
     profit, the expense sum and the expense-line count other modules add to a mission's totals
     (`MissionFinanceContribution`); `refinery.internal.RefineryMissionFinance` implements it over
     the same `findByMissionId` read and the same arithmetic as before (a zero profit adds nothing, an
     order is an expense line when its expenses are positive). `MissionFinanceEntryService` asks it
     instead of reading `RefineryOrderRepository`; `RefineryMissionFinanceTest` carries the
     arithmetic cases that were in `MissionFinanceEntryServiceTest`.
  4. *Observer* (§5.3): deleting a mission calls `mission.api.MissionDeletionObserver`
     (`@ObserverSpi`, `MANDATORY`), implemented by `refinery.internal.MissionRefineryOrderRelease`,
     which loads the linked orders and clears their link as entities, so each order's row version and
     update timestamp move exactly as when `Mission.refineryOrders` was walked. No audit row, as before.
  5. *Types*: `MissionFinanceSummaryDto` moves from `model.dto` to `operation.internal`. Only
     `OperationFinanceService` builds it and `OperationFinanceDto` and `OperationController` carry it,
     and the name rule `^Mission` had filed it under the mission module although it embeds
     `RefineryOrderDto`. The OpenAPI schema keeps its name.
  Baseline **43 → 38** (`Mission -> RefineryOrder`, `MissionService -> RefineryOrder`,
  `MissionFinanceEntryService -> RefineryOrder` and `-> RefineryOrderRepository`,
  `MissionFinanceSummaryDto -> RefineryOrderDto`); the architecture test's to-one association floor
  drops 147 → 146.
  **Corrections:** (1) The finance SPI serves the mission module only. The operation module
  (rank 11) sits above the refinery (rank 10), so `OperationFinanceService`,
  `OperationPayoutCalculator` and `OperationPayoutService` keep reading orders through the repository
  and mapper without an inversion; they go behind a `refinery.api` view in the same step as the
  entity move (P4-3), because they are three of its consumers. (2) The twelve refinery types of the
  layer packages do not move, and the probe (a class-dependency graph over the compiled `main`
  classes with the twelve reassigned to `refinery`) names exactly two imports that close a package
  cycle: `LocationService -> RefineryOrderRepository` (`existsByLocationId`, the in-use check of a
  location delete) and `UserDeletionService -> RefineryOrderRepository` (`updateOwner`, the GDPR
  reassignment), both `service -> refinery` against the refinery's own `refinery -> service`
  (`OwnerScopeService`, `UserService`, `ScopePredicate`). With those two edges taken out the probe
  finds no cycle for `RefineryOrder`, `RefineryGood`, `RefineryOrderStatus`,
  `RefineryOrderRepository` with `RefineryMissionProfitAggregate`, `RefineryOrderMapper`,
  `RefineryOrderDto`, `RefineryGoodDto`, `RefineryOrderListDto`, the create and update requests and
  the lookup. Inverting the first is the catalogue's job and the second a GDPR participant
  (`UserReassignmentParticipant`, §7.6), so neither belongs here. `BusinessMetricsCollector` (`app`)
  and the operation's three finance classes need no inversion for the cycle, and
  `REFINERY_ORDER_SCOPE_TRIPLE` is package-private to `repository` (it becomes the policy's
  fragment with the move), so `RefineryAccessPolicy` does not own the JPQL scope fragment yet.
  *(P4-3, 2026-10-11: the move is done and the policy owns the fragment, §7.6.)*
  (3) The `Operation` entity, `OperationStatus`, `OperationRepository`, `OperationMapper` and the
  operation DTOs still do not move. The refinery half is gone, but the probe still finds the three
  edges of P3-8a plus one: `MissionDto` and `MissionListDto` (`model`) and `MissionMapper`
  (`mapper`, with its generated implementation) embed `OperationDto` and `OperationMapper`, and
  `MissionPeerRedactor` (`mission.internal`) reads `OperationDto`. They move once the mission DTOs
  stop embedding the operation's DTO (§5.2). Tracked as P4-4 (§7.6). *(Done 2026-10-11, §7.6.)* (4) The six cases of a request's
  mission reference keep their behaviour. On create, an absent reference and one without an id
  leave the order unlinked, and one with an id links it after the participant check. On update, an
  absent reference unlinks, one without an id leaves the link as it was, and one with an id
  re-links. Because `RefineryOrder` holds only the id now, the update mapping marks the transient
  order with the `@Transient` flag `missionReferenceWithoutId` and the service reads it.
  `RefineryOrderMissionReferenceContractTest` pins all six, green on `afbeb41ef` first. (5) The list read costs one statement more than
  before, as the mission left the page query's join fetch for the read API above; the count
  stays flat in the page size, which is what REQ-DATA-003 asks.

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
Carried into this phase from Phase 2: `service.MemberEvaluationErasure` (promotion) and
`personalinventory.api.PersonalInventoryErasure` were the interim erasure commands `UserDeletionService`
called; both were replaced by `UserErasureParticipant` implementations in P4 slice a (below), and
`MemberEvaluationErasure` left the `service` package with that step (P2-3); `service.BankHandleSnapshots`
(bank) went the same way with the anonymisation slice (P4 slice b below, P2-5).

**Slices of the GDPR participants.** The five SPIs are one family each and ship as stacked pull
requests, each green on its own: (a) `UserErasureParticipant` with the orchestrator — **done
2026-10-11**; (b) `HandleAnonymisationParticipant`, which replaces `service.BankHandleSnapshots`
and the audit, job-order and handover repositories `HandleAnonymisationService` writes — **done
2026-10-11**; (c)
`PersonalDataExportSection`, one per module for the sections of `DataExportSections` — **done
2026-10-11**; (d)
`PersonMentionSource`, for the targets of `PersonSearchTargets` — **done 2026-10-11**; (e)
`UserReassignmentParticipant`,
for the `FOLLOWS_THE_MEMBER` / `STAYS_WITH_THE_ACT` lists of `UserAccountMergeService` — **done
2026-10-11**. Each rewrites its live-schema coverage test to compare the union of the registered
participants with `information_schema` (the erasure's, the merge's and the other three are done), so
an unclaimed member column still fails the build.

**P4 slice a — done 2026-10-11 (the erasure participants).** `UserDeletionService` no longer names a
foreign repository: it runs the registered `UserErasureParticipant`s and writes the deletion's audit
rows. Its constructor falls from 22 collaborators to 9.
1. *Characterise*: `UserDeletionEndStateTest` (real Postgres, added before the change and green on
   the unchanged code) deletes a member who owns rows in every store — Lager rows with a
   Materialbörse offer, ships, "Mein Inventar", personal blueprints, notifications, a rule selector,
   an evaluation, a mission with companion, manager and participant rows, refinery orders, a job-order
   assignee and a claim stamp — beside a second member whose rows must stay, and pins every table's
   end state and the audit rows with their order and exact details. Proven able to fail: moving the
   offers' audit behind the Lager purge, or dropping the participant unlink, turns it red.
2. *SPI*: `platform.api.UserErasureParticipant` (`@ObserverSpi`, every implementation `MANDATORY`)
   with `phase()` (`DELETE`, `REASSIGN`, `UNLINK`, in the order they run), `position()` (ascending
   within the phase) and `erase(userId, fallbackAdminId)`, which returns the counts the audit rows
   report (`Erased`: Lager rows, ships, "Mein Inventar", blueprints, notifications, rule selectors,
   evaluations, refinery orders reassigned). Eleven implementations, one per module and phase:
   - `DELETE`: `MaterialExchangeOfferErasure` (10; audits the offers on the member's Lager rows
     before their cascading delete, through `MaterialExchangeOfferRatchet.removeOffersOnRowsOf`,
     the former `StockChangeObserver.beforeUserPurge`), `InventoryUserErasure` (20),
     `HangarUserErasure` (30), `PersonalInventoryErasureService` (40), `PersonalBlueprintUserErasure`
     (50), `NotificationUserErasure` (60; notifications, then rule selectors),
     `MemberEvaluationErasureService` (70);
   - `REASSIGN`: `MissionOwnerReassignment` (10; missions, then the ownership companions) and
     `RefineryOwnerReassignment` (20);
   - `UNLINK`: `MissionMemberUnlink` (10; manager seats, then participant links) and
     `JobOrderMemberUnlink` (20; assignee seats, then claim stamps).
   The reassigning participants read the two accounts through `UserRepository.getReferenceById`, which
   returns the instances the orchestrator already loaded, so the bulk updates bind the same entities.
3. *Orchestrator*: `UserDeletionService.inRunOrder()` sorts by phase, then position; the audit rows
   (`INVENTORY_PURGED_ON_USER_DELETION`, `PERSONAL_DATA_PURGED_ON_USER_DELETION`,
   `REFINERY_ORDERS_REASSIGNED`, `USER_DELETED`) are written from the summed counts after the
   participants, in the old order and with the old details. The approval trail, the responsible-holder
   bracket and the delete of the row stay where they were.
4. *Gates*: `UserErasureOrderTest` pins the eleven participants in run order and the uniqueness of
   `(phase, position)`; `GdprParticipantCoverageTest` now reads the calls of the orchestrator and of
   every participant, so an unclaimed `app_user` column still fails (a planted step only a
   participant performs is found); `StockWriteOwnershipTest` lost its one exception;
   `ModularityTest` lists `personalinventory` among the modules that publish nothing; the domain map
   lost the `MemberEvaluationErasure` rule. The participants of the unmoved inventory and blueprint
   and the notification services sit in `service`, assigned by their names.
Baseline **43 → 43**: no edge was in play, since the orchestrator already reached every module from
rank 13.
**Corrections:** (1) The SPI is owned by `platform.api`, not by identity as §7.6 and ADR-0232 first
read: `notification` is a participant, sits at rank 1 and may not depend on identity (rank 3); the
same holds for `audit` in slice (b). (2) The phases are not "REASSIGN, UNLINK, DELETE": the deletion
has always purged first, then reassigned, then unlinked, and the contract is that statement order
(the offers must be audited before their rows go), so the enum runs `DELETE`, `REASSIGN`, `UNLINK`.
(3) Statements of independent tables swapped places inside a phase because one participant per
module and phase runs them together: the mission ownership companions follow the missions directly
(before the refinery orders), and the mission participants are unlinked before the job-order seats;
no foreign key relates the pairs, and `UserDeletionEndStateTest` pins the result. (4) The three
audit rows are now written after all participants, not between two of them; their order and details
are unchanged. (5) `PersonalInventoryErasure` was the only type of `personalinventory.api`, so the
module now publishes nothing, as `promotion` does, and nine package declarations dropped
`personalinventory::api`. (6) The inventory, blueprint and notification participants sit in
`service` for now, assigned by their names: the Lager and the blueprint moves wait for P4-1 and
P4-2 (§7.5), and the notification services still live in the layer packages; each participant
moves with its module (the inventory's with P4-1, the blueprint's with P4-2). (7) The plan's remark that the inventory
observer's `beforeUserPurge` waits for §7.6 is settled here: it left `StockChangeObserver`, which keeps
`lower`, `beforeDelete` and `beforeWipe`.
**P4 slice b — done 2026-10-11 (the handle-anonymisation participants).** `HandleAnonymisationService`
no longer names a foreign repository or `BankHandleSnapshots`: it normalises the spellings, runs the
registered `HandleAnonymisationParticipant`s and writes the activity trail's marker row. Its
collaborators fall from six to two (the participants and the audit recorder).
1. *Characterise*: `HandleAnonymisationEndStateTest` (real Postgres, added before the change and
   green on the unchanged code) gives a member a handle snapshot in each of the twelve covered
   columns, matched by id or by spelling, beside a second member and unrelated names whose rows of
   the same tables must stay, and pins every rewritten and every untouched value, the eight counts
   and the two marker rows with their exact details. Proven able to fail: dropping the item-handover
   rewrite or the bank-holder rewrite turns it red.
2. *SPI*: `platform.api.HandleAnonymisationParticipant` (`@ObserverSpi`, every implementation
   `MANDATORY`) with `position()`, `columns()` (the `table.column` set it rewrites),
   `anonymise(userId, spellings)`, which returns the rows rewritten per table (`Rewritten`: activity
   audit, bank audit, bank transactions, booking requests, bank holders, job orders, material and
   item handovers), and `recordAnonymised(userId, rewritten)`, which lets a participant write its own
   marker. Three implementations, one per module:
   - `AuditHandleAnonymisation` (10, `service`): the actor snapshot by id, then the subject label per
     spelling;
   - `BankHandleAnonymisation` (20, `bank.internal`, the former `BankHandleSnapshotService`): the
     four bank tables by id, and the bank trail's own `HANDLE_SNAPSHOTS_ANONYMISED` marker;
   - `JobOrderHandleAnonymisation` (30, `joborder.internal`): per spelling the order contact, the
     material-handover recipient and the item-handover recipient.
3. *Orchestrator*: `HandleAnonymisationService.inRunOrder()` sorts by position; the activity trail's
   marker is written from the summed counts after the participants, then each participant records
   its own marker, so the activity row still precedes the bank row. `ANONYMISED_COLUMNS` is gone.
4. *Gates*: `HandleAnonymisationCoverageTest` holds the union of the participants' `columns()` equal
   to the `ANONYMISED` dispositions of `HandleErasureCoverage`, every such column to a text column of
   the live schema and to a `PersonSearchTargets` column, and pins the run order and the uniqueness
   of the positions (a planted drift in the registry is found); `HandleErasureCoverageTest` lost the
   two checks against the constant; `ArchitectureTest.BANK_CLASSES` is empty and the domain map lost
   the `BankHandleSnapshots` rule.
Baseline **38 → 38**: the orchestrator reached the bank and the job order from rank 13, so no edge
was in play.
**Corrections:** (1) The SPI is owned by `platform.api`, as in slice a: `audit` (rank 1) is a
participant and may not depend on `privacy`/`identity`. (2) The baseline file holds 38 edges since
P3-8b; slice a's line "43 → 43" quoted the number before that step and is corrected here. (3) Statements
of independent tables swapped places because one participant per module runs its statements
together: the audit subject-label rewrite now runs before the bank tables, the job-order rewrites
per spelling follow them; no foreign key relates the tables and `HandleAnonymisationEndStateTest`
pins the result. (4) The audit participant sits in `service`, not in `audit.internal`: the audit
repository stays in the layer package `repository`, and a class in `audit` using it would close a
package cycle `audit` ⇄ `repository` (the lesson of P3-8a); it moves with the audit module's
repository. (5) The sentinel is no parameter: every participant reads `kernel.HandleAnonymisation`.
(6) The spellings reach the participants as the distinct, trimmed, non-blank list, longest first,
that the orchestrator always built; a participant with no text-matched column ignores it.
**P4 slice c — done 2026-10-11 (the export sections).** `DataExportService` no longer reads a
central list: it runs the sections the modules declare, and `DataExportSections` is gone.
1. *Characterise*: `PersonalDataExportCatalogueTest` (reading order, key, legal basis, a digest of
   every statement, the scrubbed and the exempted columns of the 45 sections against a committed
   catalogue) and `DataExportEndStateTest` (real Postgres: a member with a row in every one of the
   45 sections next to a second member whose rows and handle must not appear; the JSON compared with
   a committed document after ids, instants and the job-order display id are replaced), both added
   before the change in their own commit and green on the unchanged code. Proven able to fail:
   dropping the scrub of the market offers' remark and changing the legal basis of the realm roles
   turn both red.
2. *SPI*: `platform.api.PersonalDataExportSection` with `sections()` and the nested `Section`
   record (position, key, `LegalBasis`, rationale, statement, `scrubbedColumns`,
   `unscrubbedPersonColumns`; `scrubbing(...)` and `keeping(...)` extend one). It is a pure
   declaration, so it is not an `@ObserverSpi` and no participant is transactional. Sixteen
   implementations, one per owning module: identity (5 sections), orgunit (2), orgchart (1),
   promotion (1), inventory (1), hangar (1), exchange (6), personalinventory (1), blueprint (1),
   notification (3), mission (5), refinery (1), joborder (2), materialexchange (4), bank (9: seven
   bank sections and the two bank audit trails), audit (2). The inventory's and the blueprint's sat
   in `service`, assigned by their names, until their modules moved (P4-1, P4-2; both now in
   `<module>.internal`).
3. *Registry*: `privacy.internal.PersonalDataExportRegistry` collects the beans, orders them by
   position and refuses a duplicate key or position at startup. The two maps of the old class,
   `FREE_TEXT_COLUMNS` and `UNSCRUBBED_PERSON_COLUMNS`, became declarations on the section that
   selects the column.
4. *Gates*: the tests read the union of the participants, found by scanning
   (`ExportSectionDeclarations`), not a list: `GdprParticipantCoverageTest` derives the exported
   member columns from it (a section a participant declares closes its gap, shown by a planted
   participant; the floor stays 44), `DataExportScrubCoverageTest`, the two PDF label tests and the
   integration tests use it, and the new `PersonalDataExportOwnershipTest` holds every section to
   the module that owns the table its `:userId` condition filters.
The export JSON and PDF are unchanged. Baseline **36 → 36** (the file holds 36 since P4-3): the declarations are SQL text and name
no class.
**Corrections:** (1) The plan and the evidence counted 36 sections; there are 45. (2) The reading
order is global and a module's sections are not contiguous in it (identity's `realmRoles` follows
the org-chart placement, the bank's audit trails follow the activity trail), so the position sits on
each section (10, 20, ... in the old order) and not on the participant, as the erasure's does.
(3) The legal basis is an enum; the export and the PDF still carry its name. (4) The leaf-helper
floor of `ArchitectureTest` fell from 63 to 62 with the class. (5) The characterisation first
asserted that the external contact is absent from the whole document, which is wrong for the
member's own prose; it now separates a name in a column no section selects (must be absent) from
one in the member's own text (stays).
**P4 slice d — done 2026-10-11 (the person-mention sources).** `PersonSearchService` no longer reads a
central registry: it builds its one statement from the columns the modules declare, and
`PersonSearchTargets` is gone.
1. *Characterise*: `PersonMentionCatalogueTest` (the 79 searched columns with area, id column and
   link kind, and the exempt columns, name-bearing exemptions and exempt tables, against a
   committed catalogue) and `PersonSearchEndStateTest` (real Postgres: one row in every table that
   holds a searched column, filled generically from the schema with the synthetic name in each
   searched column; the 79 hits compared with a committed list of area, table, column, link kind
   and snippet), both added before the change in their own commit and green on the unchanged code.
   Proven able to fail: dropping the `rsi_handle` target and changing the link kind of the bank
   holder's handle turn both red.
2. *SPI*: `platform.api.PersonMentionSource` with `targets()` (`Target`: area, table, column, id
   column, `LinkKind`) and three default sets, `exemptColumns()`, `exemptButMayHoldNames()` and
   `exemptTables()`. A pure declaration, so not an `@ObserverSpi`. Twenty-one implementations: one
   per module that owns a text column (admin, audit, bank, blueprint, catalogue, dashboard,
   exchange, hangar, identity, inventory, joborder, materialexchange, mission, notification,
   operation, orgchart, orgunit, personalinventory, promotion, refinery) and
   `platform.api.SchemaHistoryMentionSource` for Flyway's schema history. The inventory's and the
   blueprint's sit in `service`, assigned by their names.
3. *Registry*: `privacy.internal.PersonMentionRegistry` collects the beans, orders the targets by
   area, table and column and refuses a column declared twice. The link kinds that were string
   constants are the enum `LinkKind`; the hit still carries its name.
4. *Gates*: the tests read the union of the sources, found by scanning
   (`MentionSourceDeclarations`): `PersonSearchCoverageTest` (every text column searched or
   exempt, now also: no stale exemption and no column both searched and exempt),
   `HandleErasureCoverageTest`, `HandleSpellingCoverageTest`, `HandleAnonymisationCoverageTest` and
   `DataExportScrubCoverageTest`; the new `PersonMentionOwnershipTest` holds every source to the
   module that owns the tables it names.
The search results are unchanged. Baseline **36 → 36**: the declarations are text and name no class.
**Corrections:** (1) There are 79 targets, not the 80 the evidence counted. (2) The old javadoc
promised the results in registry order, "the member record first, the audit trails last"; the
statement has always ended in `ORDER BY area, src_table, src_column, row_id`, so the order of the
registry never reached a result, and the registry now sorts by area, table and column. (3) The new
stale-exemption check found four entries for things that no longer exist, an exempt column
(`kommando_group.sort_key`) and three exempt tables (`blueprint_component`, `uex_commodity`,
`uex_vehicle`); they are dropped, which changes no search, and the catalogue lost the same four
lines. (4) The schema history is the one table no module owns; its exemption sits in
`platform.api` because an implementation in `platform.internal` would close an `api` ⇄ `internal`
cycle of the platform. (5) `exemptButMayHoldNames`, not `...AName`: Checkstyle reads the
abbreviation. (6) The leaf-helper floor of `ArchitectureTest` fell from 62 to 61 with the class.

**P4 slice e — done 2026-10-11 (the account-merge participants).** `UserAccountMergeService` no
longer holds the two lists of member columns or any SQL: it asks the registered
`UserReassignmentParticipant`s to refuse the merge, runs them by position, writes the `USER_MERGED`
audit row from the rows they report and keeps the RSI-handle carry-over.
1. *Characterise*: `UserAccountMergeEndStateTest` (real Postgres, added before the change and green
   on the unchanged code) gives the source rows in all 29 followed columns, the target the same key in
   every table with a unique constraint, a bystander a row of each kind and three staying columns the
   source, and pins the rows each table moves, the duplicates dropped, the bystander's rows, the rows
   that stay and the `USER_MERGED` details with their key order, plus the zero-row merge and the
   refusal of two ledgers before anything moves. Proven able to fail: dropping a followed column or a
   conflict key turns it red.
2. *SPI*: `platform.api.UserReassignmentParticipant` (`@ObserverSpi`, every implementation
   `MANDATORY`) with `position()`, `followsTheMember()` (`OwnedRows`: table, column and the remainder
   of the unique constraint), `staysWithTheAct()`, `assertMergeable(source, target)` and
   `reassign(source, target)`, which returns the rows moved per column. The statements run in one
   engine, `platform.api.OwnedRowsMover` (delete the source rows the target already holds, then
   re-point), and `OwnedRowsReassignment` is the base of a participant that only declares columns.
   Eighteen implementations in seventeen modules, in the order the old list read:
   - follow: `InventoryUserReassignment` (10), `HangarUserReassignment` (20),
     `RefineryUserReassignment` (30), `PersonalInventoryUserReassignment` (40),
     `PersonalBlueprintUserReassignment` (50), `OrgUnitMembershipUserReassignment` (60),
     `OrgChartUserReassignment` (70), `OrgUnitGrandAdmiralReassignment` (80),
     `MissionUserReassignment` (90), `JobOrderUserReassignment` (100),
     `MaterialExchangeUserReassignment` (110), `BankUserReassignment` (120, also refuses two
     ledgers), `NotificationUserReassignment` (130), `PromotionUserReassignment` (140),
     `ExchangeUserReassignment` (150);
   - stay only: `AuditUserReassignment` (160), `OperationUserReassignment` (170),
     `IdentityUserReassignment` (180).
   The 29 followed and 27 staying columns are the old lists, redistributed to their owning modules.
3. *Orchestrator*: `UserAccountMergeService.inRunOrder()` sorts by position; the ledger check runs
   first, as before, the audit row lists the moved columns in participant order, which is the old
   list order, and `followedColumns()` / `staysWithTheAct()` are now the union of the declarations.
4. *Gates*: `UserAccountMergeCoverageTest` and `GdprParticipantCoverageTest` compare the union of the
   participants with the live schema (an unclaimed member column still fails; the planted-table proof
   still finds it); `UserReassignmentOrderTest` pins the eighteen participants, their order, columns
   and conflict keys and that no column is declared twice (56 in all); `TableOwnershipTest` lists
   `OwnedRowsMover` where it listed `UserAccountMergeService` as the one class running the merge's SQL.
Baseline **36 → 36**: the service already reached every module by table name only, and a participant
sits in its own module. (The slice-a line "43 → 43" quoted the number before P3-8b; the file held 38 until P4-3 took it to 36.)
**Corrections:** (1) The SPI is owned by `platform.api`: `audit` (rank 1) declares staying columns and
may not depend on identity or privacy. (2) The org-unit module has two participants, not one: the
old list put the org-chart position between the memberships and the Grand-Admiral office, and the
audit row's key order is part of the observable result, so the three are separate participants at 60,
70 and 80. (3) Three modules, `audit`, `operation` and `identity`, own staying columns only; they
need a participant for the coverage test to find their columns. (4) The participants are declarative
and share one engine instead of each running SQL; a module that later needs its own reassignment
overrides `reassign`. (5) The inventory and blueprint participants sat in `service`, assigned by their
names, until their modules moved (P4-1, P4-2); the others sit in `<module>.internal`, since they use no
layer repository and close no package cycle. (6) The ledger check reads `BankHolderRepository`
(`existsByUserId` on both accounts) instead of counting rows with a native query; the message and the
409 are unchanged. (7) The two coverage tests that read the old static lists now run in a transaction,
because a participant is `MANDATORY` even for its declarations.
**P4-1 — move the inventory into `inventory.web` / `inventory.internal` — part 1 done 2026-10-11, part 2
done 2026-10-12** (carried from P3-5e, §7.5). Part 1: the Lager's controller, services, access policy and
participants move; its data layer (entities, repositories, mapper, DTOs, projections) stays in the layer
packages, for the reasons below. Part 2 (after the corrections of part 1) moves the data layer except the
entities.
1. *Probe* (not a guess): a class-dependency graph over the compiled `main` classes (`jdeps
   -verbose:class`, nested and generated classes folded onto their source type, one node per first
   package segment), the inventory types reassigned to `inventory`. Moving all 58 inventory types
   put 14 nodes into one strongly connected set (`admin`, `blueprint`, `catalogue`, `config`,
   `identity`, `integration`, `inventory`, `mapper`, `model`, `notification`, `orgunit`, `privacy`,
   `repository`, `service`). Moving only the controller, the services, the policy, the stock commands,
   the earmark cache, the craftable-stock source and the four GDPR participants (17 types) left
   `config`, `integration`, `mapper`, `orgunit`, `repository` and `service` in the set, closed by
   exactly these layer-to-inventory imports:
   - `OrgUnitMembershipService -> InventoryOrgUnitReconciler`;
   - `InventoryItemMapper -> inventory.api.StockViewerAccess`, `EarmarkTargets.Lookup`,
     `JobOrderEarmark`, `MissionEarmark`, and `JobOrderMapper -> StockViewerAccess`;
   - `InventoryItemRepository -> JobOrderMaterialStockRow`, `JobOrderGameItemStockRow`;
     `JobOrderRepository -> JobOrderEarmark`; `MissionRepository -> MissionEarmark`;
   - `JobOrderStockProjectionService -> JobOrderMaterialStockRow`.
   With them gone the set is empty (re-probed on the compiled result).
2. *Inversion*: `orgunit.api.FirstMembershipObserver` (`@ObserverSpi`, `onUserGainedFirstOrgUnit`,
   `onUserLostLastOrgUnit`, both `MANDATORY`), owned by the org-unit module and implemented by
   `InventoryOrgUnitReconciler`; `OrgUnitMembershipService` asks it at the same four sites, in the same
   transaction. The mapper's two seams follow `MissionViewerAccess` and `MissionOperationLookup`:
   `mapper.StockViewerAccess` (was `inventory.api`) and `mapper.InventoryEarmarkLookup` (was
   `EarmarkTargets.Lookup`, which only the Lager used), implemented by `StockViewerAccessService` and
   `InventoryEarmarkTargetCache`. The records the layer reads, `JobOrderEarmark`, `MissionEarmark`,
   `JobOrderMaterialStockRow` and `JobOrderGameItemStockRow`, moved from `inventory.api` to
   `model.projection` (the domain map assigns the four to `inventory` by class rule); the read SPIs
   `EarmarkTargets.Missions` / `JobOrders` stay in `inventory.api` and return them.
3. *Move*: `InventoryItemController` into `inventory.web`; `InventoryItemService`,
   `InventoryAggregationService`, `InventoryCheckoutService`, `InventoryOrgUnitChangeService`,
   `InventoryStolenMarkService`, `InventoryAccessPolicy`, `InventoryStockCommands`,
   `InventoryEarmarkTargetCache`, `StockViewerAccessService`, `AllocationReductions`,
   `InventoryCraftableStock` (the blueprint's `CraftableStockSource`, so P4-2 is unblocked) and the four
   GDPR participants (`InventoryUserErasure`, `InventoryUserReassignment`, `InventoryExportSections`,
   `InventoryMentionSource`) into `inventory.internal`. NullAway took in the moved tree: 19 findings,
   all optional parameters (`@Nullable` on the filters of the aggregation methods, the excluded target of
   the slice sums, the audit amount and the reduction plan, the pool of a rebooked row), two slice
   lookups and the book-out façade that return `null`, one `Objects.requireNonNull` where the check-in's
   invariant (exactly one of material and game item) holds, and the same call on the earmark memo's
   entry, which the loop above it always fills. The 19 tests of the moved classes moved with them.
4. *Stays, and the import that blocks it*:
   - `InventoryItemRepository` (and the two allocation repositories): twelve of its finders are read by
     ten classes of the exchange, the job order and the Materialbörse and by the layer's
     `JobOrderStockProjectionService`, which is itself read by the exchange. In `inventory.internal`
     they would sit behind Spring Modulith's named interface, and the projection service would close
     `service ⇄ inventory` (probed). Needs a published read API for the twelve finders, or the
     projection service in the job order's `api`.
   - `InventoryItemMapper`: `JobOrderQueryService` (`toDto`), `JobOrderStockReadService`
     (`gameItemToReferenceDto`) and `JobOrderMapper` (`uses`) read it. Same question.
   - `InventoryItemCatalogService`: `joborder.web.GameItemController` calls it.
   - The entities `InventoryItem`, `InventoryJobOrderAllocation`, `InventoryMissionAllocation`,
     `CheckoutType` and `BulkRebookMode`: `JobOrderMaterial`, `JobOrderHandoverItem` and `MaterialClaim`
     hold `InventoryItem`, and the repositories and the mapper import them (11 import edges that put
     `model`, `repository` and `mapper` into the set).
   - The Lager DTOs and `model.projection` aggregates: the mapper and the repository return them, and
     `JobOrderItemStockGroupDto` embeds `InventoryGameItemReferenceDto`.
   **Part 2** (below) moves them once those readers go through `inventory.api`.
5. *Gates*: baseline **36 → 35** (`orgunit -> inventory: OrgUnitMembershipService ->
   InventoryOrgUnitReconciler`; the file holds 35 lines in 7 module pairs), written by
   `ModuleBaselineTest`'s store. `ModularityTest` stays green, so no foreign module reaches an
   `inventory.internal` type. `ArchitectureTest` and the tenancy guard needed no new floor: their
   selections are annotation-based and still select the controller and the services (re-measured, no
   selection shrank). The mapper-seam names are `StockViewerAccess` (leaf helper, as before) and
   `InventoryEarmarkLookup`, matched by the inventory name rule.
**Corrections:** (1) The wait list was too long: a layer class that consumes `inventory.api` closes a
cycle only when the moved implementation also reaches its package. `MeController` (it reads
`InventoryProperties`) and `BlueprintCraftabilityService` (it asks `blueprint.api.CraftableStockSource`)
stay where they are: nothing in the moved tree depends on the `controller` package, and the
craftability service names no inventory type. (2) `OrgUnitMembershipRepository` never called the reconciler,
only its Javadoc named it; only the service did. (3) The package-info of nine modules allowed
`hangar/hangar::api` since F5 part 2 (`07ecf4061`), a bulk path rewrite that reached the Java sources;
`ModularityTest` failed on `main` and is restored to `hangar::api` here. (4) The GDPR slices a, c, d
and e recorded "the inventory participants sit in `service` until P4-1": since this step they sit in
`inventory.internal`; the blueprint's and the notification's still sit in `service`.
**P4-1, part 2 — done 2026-10-12.** The Lager's repositories, mapper, catalogue service, request and read
DTOs and projections move into `inventory.internal` / `inventory.api`; the three entities stay.
1. *Enumerate* every use of the Lager repositories outside the module (class, finder, what it needs):
   - `ExchangeDemandService`: `findGameItemStockRowsByJobOrderIds` (the item earmarks of many orders).
   - `ExchangeStockFeedService`: `findExchangeLots`, `findExchangeLotsByKeys` (the lot rows);
     `ExchangeStockWriteService`: `countExchangeLots`.
   - `JobOrderQueryService`: `findGameItemStockRowsByJobOrderIds`, `findByJobOrderIdAndMaterialId`,
     `findByJobOrderIdOrdered`, `findGameItemRowsByJobOrderIdOrdered`, with the mapper's `toDto`;
     `JobOrderStockReadService`: the last two, with `gameItemToReferenceDto`.
   - `JobOrderHandoverService`, `JobOrderItemProductionService`: `findByIdForUpdate` (the locked row that
     `takeFromEarmarkedRow` then lowers); `JobOrderService`: `findById` (the unlink check).
   - `MaterialExchangeBoardService`: `findReleasableForUser`, `findWithMaterialByIdIn`, `findById`;
     `MaterialExchangeService`: `findById`.
   - the layer's `JobOrderStockProjectionService`: `findMaterialStockRowsByJobOrderIds`.
   Twelve finders in all. The two allocation repositories have no reader outside the module.
2. *Characterise* (green on the unchanged code, before any change): `JobOrderLinkedStockReadNoNPlusOneTest`
   (real Postgres) reads the stock earmarked to an order with 3 and with 25 rows of each kind (the material
   collection, the item stock, the linked rows of a material and the orphaned rows as Lager DTOs, the batched
   stock index): every read issues the same number of statements for both sizes, within a bound of 15. The
   DTO bytes were already pinned by `OpenApiGeneratorTest` (`openapi.json` stays untouched),
   `InventoryEarmarkReadNoNPlusOneTest` and `MaterialExchangeBoardNoNPlusOneTest`; all stayed green
   unchanged.
3. *The three associations* (the premise of part 1 was wrong, see Corrections): `JobOrderMaterial`,
   `JobOrderHandoverItem` and `MaterialClaim` hold no association to `InventoryItem`. What they import is
   the static `InventoryItem.roundToScuScale` in their `@PrePersist`/`@PreUpdate` hook, as do the Lager's
   own code and the Materialbörse. It is `kernel.ScuAmounts.roundToScuScale` now (three decimals half up,
   `null` stays `null`), and `ScuAmountsTest` carries the three rounding tests.
4. *Read API*: `inventory.api.StockReads`, implemented by the read-only bean
   `inventory.internal.InventoryStockReads`, one method per use above: `exchangeLots`, `exchangeLotsByKeys`,
   `countExchangeLots`, `gameItemStockOfOrders`, `materialRowsOfOrder` and `gameItemRowsOfOrder` (the
   `LinkedStockRow` record: owner, location, material or item, stock, the order's slice, delivered),
   `rowsOfOrderForMaterial` and `orphanedRowsOfOrder` (Lager DTOs, so the job order needs neither the
   repository nor the mapper), `findRow`, `findRowsWithMaterial` and `releasableRows` (the Materialbörse;
   these three still return the entity, which it writes through). `StockCommands.lockRow` is the
   handover's and the production's `findByIdForUpdate` under the caller's transaction. The repository
   calls, arguments and transactions are the same, so the statement counts are the same.
   `JobOrderStockProjectionService` reads its material rows through `service.OrderLinkedStockRows`, a seam
   the layer owns and `InventoryStockReads` implements (the pattern of `mapper.StockViewerAccess`): the
   projection belongs to the job order and is read by the exchange, and `service -> inventory.api` would
   close `service ⇄ inventory` (probed again, 2 class edges). `joborder.web.GameItemController` asks
   `inventory.api.StockItemCatalogue`, which `InventoryItemCatalogService` implements.
5. *Probe and move* (class graph over the compiled `main` classes with `jdeps -verbose:class`, nested and
   generated classes folded onto their source type, one node per first package segment): with the foreign
   readers routed, moving the repositories, the mapper, the catalogue service, the enums, the stack
   projections and the DTOs closes no cycle once `JobOrderMapper` stops listing `InventoryItemMapper` in
   `uses` (the mapping never needed it; it was the one import of `mapper -> inventory`) and the remaining
   edges below stay out. Moved to `inventory.internal`: the three repositories, `InventoryItemMapper` with
   `InventoryEarmarkLookup` (no layer class asks it now), `InventoryItemCatalogService`, `CheckoutType`,
   `BulkRebookMode`, `InventoryItemStackAggregate`, `InventoryStackAggregate`, `OwnedStockSlice` and 22
   request, result and read DTOs. Moved to `inventory.api`, because the job order and the exchange read
   them: `InventoryItemDto` with `JobOrderAllocationDto` and `MissionAllocationDto`, `AllocationReductionDto`,
   `JobOrderGameItemStockRow` and `ExchangeStockLotRow` (the repository's nested projection, now top-level).
   Ten tests moved with their classes.
6. *NullAway* covers the moved tree: the optional finder parameters (the filter lists, the minimum
   quality, the pinned org unit, the stack's quality and owning unit, the picker's query), the optional
   components of the DTOs (`InventoryItemDto`'s user, material, game item, location, quality, note and
   owning unit; the aggregate, group and stack DTOs; the book-out request's optional fields; the two
   allocation DTOs' names) and the mapper's two slice methods carry `@Nullable`; two group sorts use
   `Objects.requireNonNull` on a component their builders always set.
7. *Gates*: baseline **32 → 32** (6 module pairs, unchanged: no edge was in play). The model-code floor of
   `noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities` drops 340 → 312 with the 28 types that
   left the model tree; `ArchitectureTest.LEAF_HELPER_CLASSES` gains `ScuAmounts`, `InventoryItemDto` and the
   two allocation DTOs (the owner redactor builds them); eight class rules left the domain map and the name
   rule is `^(Inventory|StockViewerAccess)`; `OrderLinkedStockRows` is assigned to `inventory`.
   `ModularityTest` stays green, so no foreign module reaches an `inventory.internal` type.
**What stays, and the import that blocks it:**
   - The entities `InventoryItem`, `InventoryJobOrderAllocation`, `InventoryMissionAllocation` (`model`):
     moving the three together closes no package cycle (probed: moving one alone does, through the other
     two). Seven foreign classes hold the row, so it could only go to `inventory.api` as a published
     entity: `ExchangeStockWriteService` (the rows `StockCommands.lockLotRows` returns, `bookOutRow`,
     `markStolen`), `JobOrderHandoverService` and `JobOrderItemProductionService` (`lockRow`, then the
     material, quality, location and slice of the row), `JobOrderService` (`findRow`, then
     `releaseJobOrderEarmark`), `MaterialExchangeBoardService` and `MaterialExchangeService`
     (`findRow`, `findRowsWithMaterial`, `releasableRows`; the offer takes the row's owner and org unit),
     and in `inventory.api` itself `StockCommands`, `InventoryAllocations` and `InventoryAuditLabels`.
     Part 3 turns the row into a record plus id commands, then the three entities move into
     `inventory.internal`. Every other module's entity that a foreign module still holds (`Mission`,
     `Ship`, ...) also stays in `model`.
   - `InventoryGameItemReferenceDto` (`model.dto`): `JobOrderItemStockGroupDto` embeds it; in `inventory`
     it would close `model ⇄ inventory` (1 class edge).
   - `JobOrderEarmark`, `MissionEarmark` (`model.projection`): `JobOrderRepository.findEarmarks` and
     `MissionRepository.findEarmarks` return them (1 class edge each, closing a 7-node cycle).
   - `JobOrderMaterialStockRow` (`model.projection`): the seam `service.OrderLinkedStockRows` and
     `JobOrderStockProjectionService` (2 class edges, `service ⇄ inventory`).
   - `StockViewerAccess` (`mapper`): `JobOrderMapper` (1 class edge, `mapper ⇄ inventory ⇄ service`).
**Corrections:** (1) Part 1 named three entity associations as the blocker. None exists: `JobOrderMaterial`,
`JobOrderHandoverItem` and `MaterialClaim` import `InventoryItem` for one static rounding helper, which is
`kernel.ScuAmounts` now; there was no id conversion, no migration and no memo to add. (2) The real blocker
of the entities is the other side: the published API and its seven foreign holders take or return the row
itself (`StockCommands.lockLotRows`, `lockRow`, `bookOutRow`, `markStolen`, `takeFromEarmarkedRow`,
`releaseJobOrderEarmark`; `StockReads.findRow`, `findRowsWithMaterial`, `releasableRows`). (3) The projection
service could not use `inventory.api` directly, and the plan's alternative (moving it into the job order's
`api`) needs a published type for the exchange's `OrderLinkedStockIndex` first; the layer seam is the
interim, and goes with the projection service when the job order's services move. (4) Part 1 quoted the
baseline as 36 → 35 in 7 pairs; the file held 32 in 6 (the P4-2 entry says so too) and still does.
(5) `JobOrderMapper` listed `InventoryItemMapper` in `uses` without needing it. (6) `ExchangeStockLotRow`
had to leave the repository with the move: a published type must not be nested in an internal repository.
**P4-2 — move the blueprint into `blueprint.web` / `blueprint.internal` — part 1 done 2026-10-12**
(carried from P3-7b, §7.5). 55 of the 65 types the domain map assigns to `blueprint` move; the ten that
other modules read directly stay in the layer packages (**part 2**, below).
1. *Probe* (not a guess): a class-dependency graph over the compiled `main` classes (`jdeps
   -verbose:class`, nested and generated classes folded onto their source type, one node per first
   package segment), the 65 blueprint types reassigned to `blueprint`. The strongly connected set was
   **empty** with all 65 moved, and is empty on the compiled result of the 55. `InventoryCraftableStock`
   in `inventory.internal` was the only edge P3-7b feared, and no layer class names a blueprint
   implementation: the exchange, the job order and the Materialbörse reach the blueprint through the
   five published interfaces, the participants are found by their SPIs, and the two query-side users
   below read data types. So no SPI was needed and none was added; the work is the move itself.
2. *Move*: the five controllers (`AdminDefaultBlueprintController`, `AdminPersonalBlueprintController`,
   `BlueprintProductController`, `PersonalBlueprintController`, `PersonalBlueprintOverviewController`)
   into `blueprint.web`; into `blueprint.internal`: the services (`PersonalBlueprintService`,
   `PersonalBlueprintCommandService`, `PersonalBlueprintOverviewService`, `DefaultBlueprintService`,
   `DefaultBlueprintKeyService`, `DefaultBlueprintBootstrap`, `DefaultBlueprintCatalog`,
   `DefaultBlueprintProvisioningService`, `BlueprintImportService`, `BlueprintExportParser`,
   `BlueprintUploadPreviewService`, `BlueprintProductService`, `BlueprintCraftabilityService`,
   `BlueprintModifierMath`, `BlueprintPackTags`, the three variant-family classes),
   `BlueprintAccessPolicy`, `DefaultBlueprintGrantOnCreation`, `DefaultBlueprintProvisioningTask`, the
   four GDPR participants (`PersonalBlueprintUserErasure`, `PersonalBlueprintUserReassignment`,
   `PersonalBlueprintExportSections`, `PersonalBlueprintMentionSource`), `PersonalBlueprintMapper`,
   `DefaultBlueprintMapper`, the entities `DefaultBlueprint` and `BlueprintExternalAlias` with
   `BlueprintExternalAliasSource`, their two repositories, and the 18 DTOs only the blueprint
   controllers and services use. 26 tests moved with their classes (package-private access follows
   the class). The name rule for the two admin controllers left the domain map (it assigns no class
   now, the package rule takes them); nothing else in it changed.
3. *Stays, and the import that blocks it* (ten types; moving any of them gives the exchange or the job
   order a reference into `blueprint.internal`, which `ModularityTest` forbids):
   - `PersonalBlueprintRepository`: seven finders (`findAllByOwnerUserIdOrderByIdAsc`,
     `…AndIdGreaterThan…`, `findAllByOwnerUserIdAndProductKeyIn`, `countByOwnerUserId`,
     `existsByOwnerUserIdAndProductKey`, `findByOwnerUserIdAndProductKey`,
     `findOwnerProductByOwnerUserIdIn`) are called by
     `ExchangeBlueprintFeedService`, `ExchangeBlueprintWriteService`, `ExchangeUndoService`,
     `ExchangeDemandService` and the job order's `JobOrderItemBlueprintOwnersService`.
   - The entity `PersonalBlueprint` and `BlueprintSource`: the exchange's feed, write and undo
     services hold and build them.
   - `BlueprintOwnerProduct` (the finder's projection, read by `ExchangeDemandService` and
     `JobOrderItemBlueprintOwnersService`).
   - `PersonalBlueprintCreateRequest` and `PersonalBlueprintResponse` (the argument and the result of
     `PersonalBlueprintCommands`), `BlueprintImportPreviewDto` with `BlueprintImportEntryDto`,
     `BlueprintImportSuggestionDto` and `BlueprintImportStatus` (the exchange's draft and resolve
     services and its envelope reader).
   **Part 2** publishes a read API for the seven finders (`blueprint.api`, records instead of the
   entity) and moves the data types above that the exchange needs by name into `blueprint.api`, then moves the rest.
4. *NullAway*: the whole `blueprint` tree was already checked; the moved types produced 18 findings,
   all optional values: the craftability DTOs' limiting-material names and effective qualities, the
   product DTO's manufacturer and example key and the three nullable columns of `DefaultBlueprint`
   carry `@Nullable`, as do the optional parameters of `PersonalBlueprintService#newOwned` and
   `PersonalBlueprintMapper#toResponse`; two map lookups that cannot miss are wrapped in
   `Objects.requireNonNull`. The §8.1 idioms have no site (no two-bound clamp, no concatenated
   `@Query`, no `isPresent()`/`get()` pair that `Optional.filter` fits).
5. *Gates*: baseline **32 → 32** (the file holds 32 lines in 6 module pairs; no blueprint edge was in
   it and none was added). `ModularityTest` stays green, so no foreign module reaches a
   `blueprint.internal` type. The model layer's join-column selection floor drops 340 → 325 (the entity,
   the DTOs and the repositories left the layer packages). `ArchitectureTest`, the tenancy guard and
   the stock-write test needed no other change. 576 tests of the touched areas ran green (the moved
   tests, the module and architecture gates, the GDPR coverage and order tests, `OpenApiGeneratorTest`
   with the frozen contract, the exchange tests that name blueprint types).
**Corrections:** (1) The plan expected the five published interfaces to cover every foreign caller;
the exchange and the job order also read `PersonalBlueprintRepository`, the entity and eight data
types directly, so ten types stay until a read API replaces those reads (part 2). (2) P3-7b predicted
that a blueprint class in `blueprint.internal` would close `service ⇄ blueprint`; with
`InventoryCraftableStock` in `inventory.internal` (P4-1) it does not. (3) The GDPR slices a, c, d and e
recorded "the blueprint participants sit in `service` until P4-2": since this step they sit in
`blueprint.internal`; only the notification's still sit in `service`. (4) The earlier slices quote the
baseline as 35 lines; the file holds 32 (6 pairs), as `module-boundaries.md` says.
**P4-3 — the refinery's entities move into `refinery.internal` / `refinery.api` — done
2026-10-11** (carried from P3-8b, §7.5).
1. *Characterise*: `LocationDeleteInUseIntegrationTest` (real Postgres) pins the in-use check of a
   location delete: an unused location is deleted; one a ship references is refused with the ships'
   message, one a refinery order references with the orders' message, and one both reference with
   the ships' message first. Green on `c042014ee` before the change, and proven able to fail:
   swapping the two checks turns two of the four red. The refinery side was already pinned by
   `RefineryAccessPolicyDifferentialTest`, `RefineryOrderRepositoryScopedTest`,
   `RefineryOrderRepositoryListFilterTest`, `RefineryOrderReadNoNPlusOneTest`,
   `RefineryOrderMissionReferenceContractTest` and `RefineryMissionLinkContractTest`, which reads
   the operation's finance views against the database; they stayed green unchanged in behaviour.
2. *Inversion*: `catalogue.api.LocationUsage` (`position()`, `users()`, `isUsed(locationId)`), a
   read SPI the catalogue owns and every module whose rows point at a location implements:
   `hangar.internal.ShipLocationUsage` (position 10) and `refinery.internal.RefineryLocationUsage`
   (position 20), both `MANDATORY` in the delete's transaction. `LocationService.deleteLocation`
   asks the registered usages in position order and the first one that reports the location in use
   names the refusal: `Cannot delete location: It is currently used by one or more <users>.` — the
   two messages, their order and the `EntityInUseException` (409) are byte-identical.
3. *Probe* (not a guess): a class-dependency graph over the compiled `main` classes, ArchUnit-imported,
   nested and generated classes folded onto their source type, one node per first package segment,
   the twelve types reassigned to `refinery`. Before the SPI exactly one edge closed a cycle,
   `LocationService -> RefineryOrderRepository` (`service ↔ refinery`, which also put `hangar` and
   `mission` into the strongly connected set); with it taken out the set was empty. The second edge
   of P3-8b, `UserDeletionService -> RefineryOrderRepository`, was already gone with P4 slice a
   (`RefineryOwnerReassignment`), so no GDPR participant was needed here. The refinery slice's
   predecessors after the move are `exchange` (the extract and draft records of `refinery.api`),
   `operation` (below) and `task` (`BusinessMetricsCollector`, which is `app` and reads the
   repository and the status the way it reads `bank.internal` and `materialexchange.internal`).
4. *Move*: `RefineryOrder`, `RefineryGood`, `RefineryOrderStatus`, `RefineryOrderRepository` with
   `RefineryMissionProfitAggregate`, `RefineryOrderMapper` with `RefineryOrderMissionLookup` and
   the two request records go to `refinery.internal`; `RefineryOrderDto`, `RefineryGoodDto` and
   `RefineryOrderListDto` go to `refinery.api` (`RefineryImportDraftDto` embeds the first two).
   `REFINERY_ORDER_SCOPE_TRIPLE` left `ScopeSpecifications` and is now a package-private constant of
   `RefineryAccessPolicy`, spliced into the four scoped queries of the repository with the same
   text; `RefineryAccessPolicyDifferentialTest` is unchanged.
5. *Finance view*: the operation module ranks above the refinery, so its three finance classes read
   the orders through the refinery-owned `refinery.api.RefineryOrderFinanceReader`
   (`linesOfMissions`, `linesOfMission`, `figuresOfMissions`, `profitByMission`), implemented by
   `refinery.internal.RefineryOrderFinanceReads` with the same repository calls and the same
   mapper as before, in the caller's transaction. A `RefineryOrderFigures` record (mission, owner
   id and the three amounts, an absent amount as zero) replaces the entity in
   `OperationPayoutCalculator`, and a `RefineryOrderLine` pairs the figures with the order's DTO for
   the two finance views; the payout path takes the figures only, so it builds no DTO and costs no
   statement more. `OperationFinanceService` and `OperationPayoutService` no longer name a refinery
   entity, repository or mapper.
6. *NullAway*: the `refinery` tree is checked, so the moved types are now covered. The entity's
   nullable columns, the DTO components the draft and the mapper leave out, the repository's search
   pattern and the id setter's parameter (a new order is persisted without an id) carry
   `@Nullable`; the generated MapStruct implementation is the first one inside a checked tree, so
   the compile gets `NullAway:TreatGeneratedAsUnannotated`.
7. *Gates*: the architecture test's model-code floor drops 344 → 343 (the DTOs, the request records
   and the status left the model tree), the domain map lost the class rule of
   `RefineryMissionProfitAggregate` and the name rule `^(Refinery|ImportIssue|ImportSuggestion)`,
   which now assign no class (the package rule takes them all). The tests of the moved types moved
   into the matching packages of the test tree.
Baseline **38 → 36** (`catalogue -> hangar: LocationService -> ShipRepository`,
`catalogue -> refinery: LocationService -> RefineryOrderRepository`); the file holds 36 lines in 8
module pairs. P4 slice a recorded "43 → 43", but the file at `c042014ee` already held 38 lines.
**Corrections:** (1) Only the first of the two imports of P3-8b was left; the second went with P4
slice a. (2) The usage SPI removed a second baseline edge nobody had asked for: the hangar's ship
check was the same kind of import. (3) The delete has no deactivate counterpart: `hidden` is a flag
the update sets without a check, so only the delete asks. (4) Four tables point at `location`:
`ship`, `refinery_order`, `inventory_item` and `material_price`. Only the first two are checked
today, so deleting a location that a Lager row or a price references fails at the foreign key; that
behaviour is unchanged and is not an omission to fix here. `inventory` (P4-1) and the catalogue
itself can implement `LocationUsage` when someone decides the refusal should name them; the SPI
fits both without a change. (5) `RefineryOrderStatus` and the request records stay internal: only
`BusinessMetricsCollector`, which sits in `app`, and the controller of the same module read them. (6)
`RefineryOrderDto` keeps its `@NotNull` on `location` and `goods`; `location` also carries
`@Nullable` because the import draft leaves it unset when the screenshot names no known location.
**P4-4 — the operation's entity, repository, mapper and DTOs move into `operation.internal` — done
2026-10-11** (carried from P3-8b, §7.5).
1. *Characterise*: `MissionEmbeddedOperationWireShapeTest` pins the wire shape of the operation a
   mission DTO embeds: the JSON of `MissionListDto.operation` field by field, in order, with the
   `null` payout flag, and that the embedded type of `MissionDto` and `MissionListDto` has the
   components of the operation endpoint's `OperationDto` in the same order. Green on `21aa62c98`
   before the change. The rest was already pinned and stayed green: `MissionOperationReadNoNPlusOneTest`
   (the statement count for 3 and 25 missions, and every field of the embedded operation),
   `OperationAccessPolicyDifferentialTest`, `OperationModuleContractTest`, `OperationMapperTest`, and
   `OpenApiGeneratorTest` with the frozen contract types, which leave `openapi.json` untouched.
2. *Inversion*: the mission owns what it embeds. `model.dto.MissionOperationDto` has the nine
   components of `OperationDto` in the same order, with `status` as the status name (the summary of
   P3-8a already carries it so); `MissionOperationDto.of(MissionOperationSummary)` builds it, and
   `MissionMapper.resolveOperation` fills it from the `MissionOperationLookup` memo. `MissionMapper`
   no longer touches `OperationMapper`, and `OperationMapper#toDtoFromSummary` is gone.
   `OperationMapperTest` pins instead that the JSON of the embedded record equals the JSON of
   `toDto` of the entity. The OpenAPI document keeps the `OperationDto` reference: the `operation`
   component of both mission DTOs carries `@Schema(ref = "#/components/schemas/OperationDto")`, so no
   second schema appears and the wire contract is the one of `OperationDto`.
3. *Probe*: after the move no class outside the `operation` module names the seven types except
   `BusinessMetricsCollector` (`task`), which reads the repository and the status the way it reads
   `refinery.internal` and `bank.internal`. The architecture test's own cycle rules run over the
   compiled classes (the top-level slices and the layers inside each module) and pass with the seven
   types reassigned, so neither `model <-> operation` nor `mapper <-> operation` nor
   `repository <-> operation` closes a package cycle. Nothing else needed an inversion.
4. *Move*: `Operation`, `OperationStatus`, `OperationRepository`, `OperationMapper`, `OperationDto`,
   `OperationCreateDto` and `OperationReferenceDto` go to `operation.internal`. No other module
   reads an operation DTO, so `operation.api` keeps only the events; the module's published surface
   is unchanged. The constructor expression of `findAllReferenceScoped` names the new package.
5. *Scope fragment*: `OPERATION_SCOPE_PREDICATE` left `ScopeSpecifications` and is now a
   package-private constant of `OperationAccessPolicy`, spliced into the three scoped queries of the
   repository with the same text; `OperationAccessPolicyDifferentialTest` is unchanged.
6. *NullAway*: the whole `operation` tree is checked now (the check is by package, so the services
   joined the moved types). The compile named 14 sites, fixed by `@Nullable` where the code allows
   null: the entity's `description`, `owningOrgUnit` and, on the payout status, `paidOutAt` and
   `paidOutByUser`; the DTO components `description`, `owningSquadron`, `payoutPreliminary`,
   `paidOutAt`, `paidOutByName`; the repository's optional filter parameters, the oldest-created
   finder and `memberOf`; the three optional sums of the mission total.
7. *Gates*: the domain map loses `name ^Operation operation`, which assigns no class once the package
   rule takes the tree; the architecture test's model-code floor drops 343 to 340; `OperationMapperTest`
   and `OperationStatusTest` moved into `operation.internal` of the test tree.
Baseline **36 → 33** (`mission -> operation: MissionDto -> OperationDto`,
`MissionListDto -> OperationDto`, `MissionMapper -> OperationMapper`), rewritten by
`ModuleBaselineTest`; the file holds 33 lines in 7 module pairs.
**Corrections:** (1) P4-3 and P3-8a named the DTO separation of §5.2 as the condition; it needed one
record, not a separate DTO layer: the mission keeps the wire shape through a record of its own and the
operation module keeps `OperationDto` for its endpoints. (2) A second type named `OperationDto`
would have been the obvious way to keep the schema name, and `OpenApiGeneratorTest` refuses it
(`ExposedTypes.collisions`, REQ-API-018): two exposed types with one schema name document only one
of them. The reference on the component avoids that and defines nothing twice. (3) `MissionPeerRedactor`
copies `operation()` through without reading its type, so it needed no change. (4) The status of the
embedded operation is a string in the mission's record, as in the summary; the schema still lists the
four constants because the reference points at `OperationDto`, and the constants are
`OperationStatus`'s. A fifth constant would reach the mission as its name unchanged. (5) Nothing
stayed behind: the seven types and the scope fragment all moved. (6) `main` carried a defect from
the asset move F5 (2 of 5): its path rewrite turned `hangar::api` into `hangar/hangar::api` in the
`allowedDependencies` of nine backend modules, so `ModularityTest` failed with "no module found";
the nine declarations read `hangar::api` again, in a commit of their own.
**D-20 — `MissionParticipant.orgUnits` becomes lazy — done 2026-10-11** (O-11, §13).
1. *Characterise*: `MissionParticipantOrgUnitsReadNoNPlusOneTest` drives the `MissionController`
   bean with no test transaction, as in production with `open-in-view` off, on a mission of 3 and of
   25 guest participants that carry one or two org units each. It pins the mission detail, the
   unassigned roster, the peer-redacted detail, a participant update and the single-participant
   payout endpoint: the same statement count for both sizes, and every participant DTO listing exactly
   its stamped org units, Staffel first. Green on `53179e6e5` with the collection still `EAGER`:
   **9, 4, 8, 10 and 7 statements**, flat in size.
2. *Readers*: one. `MissionMapper.toDto(MissionParticipant)` is the only dereference of `getOrgUnits()`
   in the code (`existsByOrgUnitId` is a JPQL join and needs no loaded collection); the GDPR export
   sections, the viewer-access and policy classes and `MissionPeerRedactor` (which copies the DTO list)
   never touch it. Every caller of the mapper is `MissionController`, which is `@Transactional` for
   the class, so no read path maps outside a transaction.
3. *Change*: the association is `fetch = LAZY`, still with `@BatchSize(50)`, and `@ToString.Exclude`
   (the generated `toString` would otherwise initialise it). `MissionRepository.findById` adds
   `participants.orgUnits` to its entity graph, so the collection arrives with the participants in
   the one statement, and `MissionParticipantRepository.findById` graphs it for the check-in,
   check-out and payout endpoints that load one participant. After: **8, 3, 7, 9 and 7 statements**,
   still flat in size, and the DTOs are unchanged. No migration; `openapi.json`, audit rows and the
   403/404 results are untouched.
4. *Gate*: `ArchitectureTest.toManyAssociationsAreNeverFetchedEagerly` (floor 34) fails on any
   `@OneToMany` / `@ManyToMany` declared `EAGER`, with a planted violation in
   `ArchitectureRuleFailureTest`. `toOneAssociationsAreDeclaredLazy` and its floor of 146 are
   unchanged. REQ-DATA-003 records the rule.
**Corrections:** (1) The decision speaks of a collection that is "cross-domain"; it is an `OrgUnit`
collection of a mission row, read by the mission module only, so the change needed no SPI. (2) The
single-participant endpoints (`check-in`, `check-out`, `payout-preference`) do not load the mission
through `MissionRepository.findById`: they find the participant by id and reach the roster through the
lazy `Mission.participants`. The participant's own `findById` carries the graph so its org units
stay part of that one join, as the eager mapping made them. (3) The mission-level graph made four paths one
statement cheaper than the eager mapping had been, because the batch select that followed the
participants' join is part of the join now.

**P4-5 — the catalogue's caches hold read models, and its sync infrastructure moves into `catalogue.internal` / `catalogue.api` — part 1 done 2026-10-12**
(carried from §7.6: "`catalogue`, whose caches switch to read models").
1. *Characterise*: `CatalogueCachedReadsTest` (real Postgres, through the controllers) pins the 29
   cached reads of the thirteen services (city, outpost, point of interest, terminal, star system,
   refining method, manufacturer, ship type, location, material category, material, job type,
   frequency type). Cold, each shows its seeded row field by field, nested manufacturer, category
   and refined material included; warm, it loads no entity, prepares nothing beyond the change-source
   stamp of the controller's read-write transaction and answers the same JSON; thirteen mutators
   then evict their cache and the next read is cold again and shows the change. Green on `11f346769`
   with the caches still holding entities, and still green after the switch.
2. *Switch*: a cached method maps the entity to its existing wire record with the mapper it always
   used, inside its own read-only transaction, and caches that record: `Page<CityDto>`,
   `LocationDto`, `MaterialDto` and so on. Cache names, keys, TTLs and every evict trigger are
   unchanged, the JSON is byte-identical. A mutator loads the entity through a private `require…`
   method, no longer through its own cached getter, and still returns the entity; the controllers
   return the cached record on the read paths and keep the mapper for mutators and uncached reads.
   `CachedEntityGraphs` is deleted, because the mapper walks the lazy graph while the transaction
   is open. `CacheableEntityRulesTest.ENTITY_CACHES` shrinks from 32 to 3 (`RoleService`,
   `SquadronService`), and `CachedCatalogueEntityInvariantTest` now watches records as well as
   entities.
3. *Probe* (not a guess): a class-dependency graph over the compiled `main` classes (`jdeps`), nested
   and generated classes folded onto their source type, one node per first package segment, run
   to a fixpoint for the largest set of the 268 catalogue classes in layer packages that closes
   no cycle. With the kernel types where they lie it is 89; the architecture test, which also reads
   annotation members, took three of those back (below). What blocks the other 182: the catalogue's
   entities extend `model.AbstractEntity` and its controllers return `model.dto.PageResponse`, both
   assigned to the kernel by the domain map and both still in `model`; `model` reaches the
   catalogue (`Ship`, `InventoryItem`, `MissionUnit` and eleven more entities, 22 DTOs and two
   projections of other domains), so any catalogue entity or controller in `catalogue.*` closes `catalogue ↔ model`.
   `QuantityTypeRounding` (`catalogue.api`) already reaches `model`, which is why the seven
   read-model records that `model.dto` embeds (`LocationDto`, `MaterialDto`, `MaterialCategoryDto`,
   `ManufacturerDto`, `ShipTypeDto`, `JobTypeDto`, `FrequencyTypeDto`) stay in `model.dto` (the
   lesson of P3-8a).
4. *Move* (86 types and 7 tests): to `catalogue.api` the six read-model records no `model` class
   embeds (`CityDto`, `OutpostDto`, `PoiDto`, `TerminalDto`, `StarSystemDto`, `RefiningMethodDto`)
   and the two name helpers the exchange and the refinery call (`BlueprintNameNormalizer`,
   `MaterialNameCanonicalizer`); to `catalogue.internal` the sync infrastructure
   (`SyncCoordinator`, `SyncChunkWriter`, `SyncReportService`, `UexMatrixLookups`,
   `BlueprintOutputNameOverrides`, `ScWikiOrphanSweep`), the sync report (entity, repository, DTOs),
   `P4kImportJobPayload` with its repository, `SyncEventType`, `SyncSourceSystem`, the key
   projections (`PairKeyRef`, `UexKeyRef`, `LookupTableRepository`), the integration properties
   (`UexProperties`, `ScWikiProperties`), `EvictAllMaterialCaches`, the web records of the material,
   alias, quality-tier, profit and space-station endpoints, the two blueprint repository rows, and to
   `catalogue.internal.uex`, `.scwiki` and `.p4k` the 48 feed records of the three imports. The
   `dto` package no longer holds a catalogue class, so its domain-map rule is gone.
5. *NullAway*: the whole `catalogue` tree is checked now, not only `catalogue.api`. The compile named
   three sites: `SyncCoordinator.activeLabel` is `@Nullable`, and
   `BlueprintOutputNameOverrides.correct` null-checks the lookup instead of dereferencing it.
6. *Gates*: the architecture test's floors fall 340 → 324 (the model selection), 115 → 114 (the
   repository selection) and 61 → 60 (the leaf helpers, since `CachedEntityGraphs` is gone). The
   moved tests followed their subjects; `RecordingTransactionManager` became public for them.
Baseline **32 → 32**: the file holds 32 lines in 6 module pairs, none of them with the catalogue as
source, so nothing could shrink; `ModuleBaselineTest` passes without a rewrite and the moved
classes added no edge.
**What remains of P4-5** (part 2): 182 catalogue classes still lie in the layer packages. 159 of
them follow once `AbstractEntity` and `PageResponse` have moved to the `kernel` package (102 and
91 files import them, tests included): the remaining entities, enums and DTOs of `model` (68),
repositories (29), mappers (19), controllers (21) and the services in `service` (39), into
`catalogue.internal` and `catalogue.web`, with the `api` surface the foreign modules use. The other 23 (the UEX and SC Wiki clients, syncs and schedulers,
`MaterialExternalAliasService`, `P4kImportJobRunner`, `MasterDataCacheEvictionService`) also wait for
`AuthHelperService`, `CacheConfig` and `ResponseSizeLimitInterceptor`, platform types that lie in
layer packages.
**Corrections:** (1) The assignment named the consumers of the cached methods as job order,
inventory, exchange, mission, blueprint, refinery and hangar; none of them calls one. Every cached
method has exactly one caller, its own controller (the two other service references are Javadoc), and
the other modules reach the catalogue through repositories and entities, which nothing caches. The
switch therefore changed the catalogue's services, controllers and tests only. (2) The read models
are the existing wire records, not new types: they are immutable already, they carry exactly what the
controllers answer, and a second record per entity would only copy them. (3) `jdeps` does not see
class literals in annotation members; the architecture test named `SyncReportMapper`
(`@Mapper(config = CentralMapperConfig.class)`, whose class lies in `mapper`),
`ValidQuantityAmountValidator` and `MaterialPieceTypeLookup` (`@Constraint(validatedBy = …)` on an
annotation that stays in `validation`), which went back to their old packages. (4) The warm path
costs one statement through a controller, not none: the backend's transaction manager stamps the
change source in every transaction that is not read-only (ADR-0224), and the controllers are
read-write. The test therefore pins "no entity load and at most the stamp" and a cold read that
costs more; a read-only service call prepares nothing. (5) `RefiningMethodDto` lies in
`catalogue.api` because `refinery.api` embeds it. (6) `UexMatrixLookups` is public now: the UEX
services that stay in `service` build it.

**P4-5, part 1b — `AbstractEntity` and `PageResponse` move to the kernel — done 2026-10-12**
(the precondition recorded by part 1).
1. *Move*: `model.AbstractEntity` and `model.dto.PageResponse` went to the `kernel` package with
   `git mv`, same class names. §5.1 gives the backend kernel no sub-package, and `Roles`,
   `Permissions` and `ScuAmounts` already lie in it. A script rewrote the imports of 188 files
   (126 imported one of the two types, 62 same-package users gained an import). Nothing else
   changed: `@MappedSuperclass`, `equals`/`hashCode`, the record components and
   `PageResponse.of` are as they were, and `openapi.json` is byte-identical because the schema
   names derive from the simple class name.
2. *Gates*: the domain map lost its two `class … kernel` rules, which the `kernel` package rule
   covers. The `model` layer of `ArchitectureTest` took `User` as its legacy-package anchor
   instead of `AbstractEntity`; the mapped superclass is still selected by its role. The
   join-column floor falls 273 → 272, set from the failing assertion. No module baseline line
   changed (32 → 32), and the module declarations needed nothing: every module may use `kernel`.
3. *Probe*: `jdeps`-style class graph over the compiled `main` classes (ArchUnit dependencies,
   nested classes folded onto their source type, one node per first package segment), then the
   catalogue classes in layer packages moved to `catalogue` in the model and a fixpoint removed
   every class with an edge into a package of the cycle. Before this step the catalogue's
   entities and controllers reached `model` through the two kernel types; they no longer do, and
   **101 classes can move cycle-free now**: the 68 entities, enums and DTOs of `model`, the 29
   repositories and the 4 validation types. The 23 classes of the UEX and SC Wiki side stay out
   as before.
4. *What blocks the mappers, controllers and services*: moving every remaining catalogue class
   except the 23 together would close the cycle through exactly two targets and nothing else: `mapper.CentralMapperConfig`,
   a platform class in the layer package `mapper` that the 18 catalogue mappers name (and `mapper`
   is part of the cycle, because the other modules' mappers reach the catalogue), and
   `service.P4kImportJobRunner`, which `AdminP4kImportController` uses and which waits with the
   platform types. A controller or service is also held by the mapper it calls.
**Corrections:** (1) Part 1 said 159 classes "follow once" the two types moved. They do not follow
all at once: the mappers, and with them the controllers and services that call them, need
`CentralMapperConfig` in `platform` first (the domain map assigns it there already), and
`AdminP4kImportController` needs the runner. The entities, enums, DTOs and repositories do follow.
(2) The join-column floor fell by one, not because `AbstractEntity` left `model` (its role still
selects it) but because `PageResponse` left the `model.dto` package tree. (3) The probe counts 200
classes in layer packages against the plan's 182, because it counts the 18 generated MapStruct
`*Impl` classes as types of their own.

**P4-5, part 2 — the catalogue's entities, enums, DTOs, repositories and validation types move into `catalogue.api` / `catalogue.internal` — done 2026-10-12**
1. *Re-probe* on the fresh main (b9bbb6234): the 101 classes the part 1b probe found (68 in `model`,
   29 repositories, 4 validation types) still close no package cycle when they join `catalogue`.
   `@EntityScan` / `@EnableJpaRepositories` are not configured anywhere: Spring Boot scans from the
   application's package, so the new packages are found. `LikeEscapeCatalogRepositoriesDataTest`,
   `LocationRepositoryTest` and `CatalogueCachedReadsTest` fail at injection if an entity or
   repository is not found.
2. *Placement*: a class another module (or the layer packages that still stand in for one) uses goes
   to `catalogue.api` (§5.2: a foundation module publishes its entities); the rest to
   `catalogue.internal`; and everything an `api` type depends on goes to `api` too, because
   `layersInsideEachModuleShouldBeFreeOfDependencyCycles` refuses `api ⇄ internal` (it caught 16 of
   the 101 and three part-1 projection types, `LookupTableRepository`, `PairKeyRef`, `UexKeyRef`,
   which the `api` repositories extend or return). Result: **69 types in `api`, 32 in `internal`**,
   plus the three projection types. `git mv` and a script rewrote the imports (533 files in the first pass, 120 in the second), added the
   imports that the former same-package and wildcard use needed, and rewrote every fully qualified
   name in code, Javadoc and strings.
3. *Behaviour*: entity mappings, table names, queries, cache wire records and `openapi.json` are
   unchanged (`OpenApiGeneratorTest` green, `openapi.json` byte-identical). NullAway checks the
   moved classes now; six sites needed an annotation, not code: `category` of
   `MaterialPriceOverviewDto` and `MaterialMatrixItemDto`, `BlueprintModifierSegment.modifier`
   (`clearSegments` sets it to `null`), and the two parameters of
   `RefineryYieldRepository.findAllForLocation` (the query tests `IS NOT NULL`).
4. *Gates*, each set from the failing assertion: the join-column floor 272 → **239** and the
   validation-leaf floor 8 → **7** (non-entity types left the `model` and `validation` package
   trees); the leaf-helper floor 60 → **69** after `LocationReferenceDto`, `MaterialCategoryDto`,
   `MaterialDto`, `MaterialReferenceDto` and `QuantityType` joined `LEAF_HELPER_CLASSES` (the leaf
   rule allows model code, and these left the `model` package tree); the domain map lost
   `name ^(Blueprint) catalogue`, which assigned no class any more
   (`everyRuleOfTheDomainMapAssignsAClass`). Module baseline 32 → 32.
5. *Left out*: `CentralMapperConfig` stays in `mapper`. It is a platform type, but 54 files name it
   (the 35 mappers in `mapper`, 19 mappers in 15 modules, the mapper-layer anchor of
   `ArchitectureTest`), and a move into `platform.api` needs `platform::api` in the declaration of
   every module that has a mapper, a move into the kernel a change of the domain map. That is not
   the small step part 3 asked for. Part 3 therefore starts with it, together with
   `P4kImportJobRunner`.
**Corrections:** (1) The `$` of a nested class ends a fully qualified name, and the script's word
boundary stopped at it: three `@Query` constructor expressions
(`BlueprintRepository$ExchangeBlueprintKeyRow`, `GameItemRepository$ExchangeItemKeyRow`,
`LocationRepository$ExchangeLocationRow`) kept the old package and failed the context load with
"Could not resolve class … named for instantiation". A string concatenation that splits a name
(the `MaterialMapper` expression, split for the line length) is not found either. (2) The 16 types
and three projections that moved from `internal` to `api` were a second pass; part 1 had put the
projection types in `internal` because no `api` type used them yet. (3) The tests of the moved
classes stay in their old test packages; they compile against the new packages. 81 catalogue
classes remain in the layer packages: the mappers, controllers, services and the 23 of the UEX and
SC Wiki side.

**P4-5, part 3 — the catalogue's controllers, services, mappers and clients move into the module; three platform types move into `platform.api` — done 2026-10-12**
1. *Re-probe* (ArchUnit dependencies over the compiled classes, one node per first package segment,
   fixpoint; throwaway, deleted): of the 98 catalogue classes in the layer packages (80 sources and
   the 18 generated `*Impl` mappers) 78 could move at once, the 20 UEX / SC Wiki classes could not.
   The blockers were exactly three platform types in layer packages: `mapper.CentralMapperConfig`,
   `config.ResponseSizeLimitInterceptor` (only the two clients use it) and
   `service.AuthHelperService` (only `MaterialExternalAliasService` among the catalogue classes
   uses it). `CacheConfig` is no blocker any more, and `P4kImportJobRunner` needed no SPI: it and
   `AdminP4kImportController` both belong to the catalogue and move together. With the three types
   in `platform` the probe moved all 98.
2. *`CentralMapperConfig`: measured, platform.api.* Kernel: 54 files rewrite, one domain-map rule
   changes (`class CentralMapperConfig platform` to `kernel`), no declaration changes. Platform.api:
   the same 54 files, **no** domain-map change (the map assigns it to `platform` already) and no
   declaration change either, because the 11 modules with a mapper of their own (admin, bank,
   blueprint, dashboard, exchange, inventory, operation, orgchart, personalinventory, promotion,
   refinery) and the catalogue already allow `platform::api`; the mappers still in `mapper` belong
   to no declared module. Platform.api is smaller by the map rule and keeps the class where the map says it
   belongs. `ResponseSizeLimitInterceptor` (4 files) and `AuthHelperService` (127 files, 57 main and
   70 test; the bean name `authHelperService` the SpEL gates use is unchanged) went to `platform.api`
   the same way. `ArchitectureTest` re-anchored its mapper layer on `UserMapper` and its service
   layer on `AuditService`, both classes that stay in their layer packages.
3. *Move*: 81 classes by `git mv` and script (371 files rewritten in the main pass, 190 imports
   added): 21 controllers to `catalogue.web` (new `package-info`), 10 mappers and services that
   other modules call (`MaterialMapper`, `LocationMapper`, `QualityTierService`, …) and everything an
   `api` type uses to `catalogue.api` (six types moved over from `internal`), the rest, 50 classes,
   to `catalogue.internal`. The `*Impl` mappers follow their interfaces. Nine tests followed their
   subjects (the two clients' tests, `P4kImportServiceTest`, `UexItemSyncServiceTest`, …) because
   they reach package-private members; `UexRefs`, their shared fixture, is public now.
4. *Unchanged*: REST paths, `@PreAuthorize`, `@Transactional` boundaries, cache names, wire records,
   schedules and metric names; `openapi.json` is byte-identical. The logger names of the moved
   classes follow their packages (`…catalogue.internal.ScWikiClient` instead of
   `…integration.scwiki`); `docs/specs/observability.md` shows the new one.
5. *NullAway*: the moved sync, import and client code was never checked and raised 159 findings on
   the first compile. Resolved without suppressions in code paths: about 100 `@Nullable`
   annotations on entity fields and setter parameters (`@Setter(onParam_ = @__(@Nullable))` for the
   `id` fields, so `getId()` stays non-null), on parameters, returns and record components, four
   null-flow restructurings (`if (isNew)` became `if (x == null)` so the analysis sees the
   assignment), and a `Objects.requireNonNull` in `MaterialClaimService`. One
   `@SuppressWarnings("NullAway")`, on `ScWikiClient.fetchOne`: the `RestClient.exchange` lambda
   returns `null` for 404 and 304, and Spring's generic return nullness is invisible to the
   analysis. `P4kImportJobRunner.describe` falls back to the generic summary when a
   `BadRequestException` carries no message, instead of returning `null`.
6. *Gates*, set from the failing assertions or measured with a floor of 9999: the integration layer
   is now the two clients and their nested classes (2 elements), the SC Wiki wiring rule selects the
   five `ScWiki*SyncService` classes (5), and the layer floors rose to what the selections hold now:
   controllers 99 to 104, mappers 100 to 106, services 495 to 510 (the old numbers were lower
   bounds). The domain map lost five rules that assign no class any more (`package integration`
   and four `name` rules). Module baseline 32 to 32.
**Corrections:** (1) Part 1b's „159 follow once the two types are moved" and part 2's „part 3 needs
`CentralMapperConfig` and `P4kImportJobRunner`" were both incomplete: `AuthHelperService` and
`ResponseSizeLimitInterceptor` were the third and fourth blockers, and `P4kImportJobRunner` was none.
(2) Annotating the `id` fields `@Nullable` first, as the setter check suggested, made `getId()`
nullable in some twenty classes of other modules; the setter-only form is what `RefineryOrder` already used.
(3) The script's name boundary now accepts `$`, so nested-class names in `@Query` strings were
rewritten at once; the one string that is concatenated over two lines (`MaterialMapper`) was
corrected by hand again. **Open:** `backend/build-settings.properties` points the mutation tests at
`backend.service.*`, which holds fewer classes with every module move; not touched here.

**P4-6 — the scope kernel moves into `scope.api` / `scope.internal`; the leadership cascade into `orgunit.api`; the logging SPI into `platform.api` — part 1 done 2026-10-12**
(carried from §5.1: "the request-scoped scope kernel … and `OwnerScopeService` and `AccessGateService` until the per-domain access policies have taken their methods over").
1. *Probe* (not a guess): ArchUnit dependencies over the compiled `main` classes, nested and generated
   classes folded onto their source type, one node per first package segment, the candidates
   reassigned; throwaway, deleted. The candidates are the eight classes the domain map assigns to
   `scope` (`OwnerScopeService`, `AccessGateService`, `OrgUnitStampingService`,
   `RequestScopeResolver`, `ScopePredicate`, `ScopeSpecifications`, `ScopeActiveOrgUnitProvider`,
   `CustomJwtGrantedAuthoritiesConverter`) and the platform SPI `ActiveOrgUnitProvider`.
   - *All nine at once* close exactly one cycle, `service ⇄ scope`. The `scope -> service` edges are
     `RequestScopeResolver -> OrgUnitCascadeService` and `CustomJwtGrantedAuthoritiesConverter ->
     OrgUnitCascadeService` and `-> UserReconciliationService`; the edge that closes the cycle is
     `UserService -> OwnerScopeService` (`identity -> scope`, already in the module baseline). Because
     the four other classes call `RequestScopeResolver`, a fixpoint that takes out the classes with an
     edge into the cycle leaves only `ScopePredicate`, `ScopeSpecifications` and
     `ActiveOrgUnitProvider`.
   - *`OrgUnitCascadeService` is a pure leaf* (the kernel, three orgunit types and
     `OrgUnitRepository`) and belongs to `orgunit` (§5.1), so it moved to `orgunit.api` as the
     prerequisite: with it there, only the converter's edge to `UserReconciliationService` is left, and
     **without the converter nothing closes a cycle** (top-level slices and the layers inside each
     module).
   - *`UserReconciliationService` cannot follow it yet.* Moved to `identity.api` it closes
     `{scope, config, service, identity}`: it calls `UserRegistrationService` and `UserService`, which
     stay in `service`, and `service` and `config` already reach identity types. The converter
     therefore waits for the identity slice (below).
2. *Move* (9 types, 5 tests, 152 files touched; `git mv` and a script for the imports and the
   fully qualified names in code, Javadoc and strings, then Spotless): to `scope.api`
   `OwnerScopeService`, `AccessGateService`, `OrgUnitStampingService`, `RequestScopeResolver`,
   `ScopePredicate` and `ScopeSpecifications`; to `scope.internal` (new `package-info`)
   `ScopeActiveOrgUnitProvider`; to `platform.api` `ActiveOrgUnitProvider`; to `orgunit.api`
   `OrgUnitCascadeService`. Placement follows the rule of P4-5: what other modules use goes to `api`,
   and everything an `api` type depends on goes there too, because `layersInsideEachModuleShould…`
   refuses `api ⇄ internal`; `OwnerScopeService` calls the other three services, so they lie in `api`
   beside it, and only the provider, which nothing in `api` names, is internal. The five tests of the
   moved types followed them (`OwnerScopeServiceTest`, `RequestScopeResolverScopeTest`,
   `OrgUnitStampingServiceOwnerRequiredTest`, `ScopeActiveOrgUnitProviderTest`,
   `OrgUnitCascadeServiceTest`).
3. *Fragments*: `ScopeSpecifications` keeps its four JPQL texts byte-for-byte. `MissionRepository`,
   `JobOrderRepository` and `ShipRepository` still lie in `repository`, so `MISSION_SCOPE_PREDICATE`,
   `SHIP_SCOPE_TRIPLE` and `JOB_ORDER_SCOPE_PREDICATE` lost their package-private visibility and are
   `public` like `INVENTORY_ITEM_SCOPE_TRIPLE` (spliced by `inventory.internal` and
   `materialexchange.internal`) already was. javac folds the constants, so no dependency tool sees the
   repositories' reference. `scope.api` does read `OrgUnitMembershipRepository` and `OrgUnitRepository`,
   so the pair is a source-level cycle that no tool reports until the three repositories move into
   their modules, whose declarations allow `scope::api` already.
4. *Unchanged*: SpEL bean names (`ownerScopeService` is explicit, `accessGateService` is the default
   name of the unchanged class name), REST paths, `@PreAuthorize`, `@Transactional` boundaries, cache
   names, metrics, and the logger names (none of the moved classes logs); `openapi.json` is
   byte-identical. The domain map assigned all of them already, by class rule; the package rules
   `scope`, `platform` and `orgunit` take them now, so the eight class rules of the moved classes left
   the map. No `allowedDependencies` changed: `scope` allows `orgunit::api` and `platform::api`
   already, and the one foreign user of `OrgUnitCascadeService` among the modules, `orgchart`, allows
   `orgunit::api`.
5. *NullAway*: the whole `scope` tree is checked now (`orgunit.api` and `platform.api` were). The
   first compile named 13 sites, all callers in modules that were checked already, and the cause was
   one thing: the moved code took `null` where its Javadoc said so without saying it in the type. Fixed
   by `@Nullable` on the optional `owningOrgUnitId` of the three picker-resolution methods in
   `OrgUnitStampingService` and their three delegates in `OwnerScopeService`, on the private
   `resolveStampedOrgUnit`, on the four `activeOrgUnitId` parameters of `RefineryOrderRepository`
   (the queries test `IS NULL`), and on `MissionService.formatOrgUnitRef`. No suppression.
6. *Gates*, set from the failing assertions: the service layer's selection floor 510 → **508** (the
   record `ScopePredicate` and the interface `ActiveOrgUnitProvider` were selected by the legacy
   package alone) and the repository layer's target floor 114 → **113** (`ScopeSpecifications` lay in
   the package). The repository layer's legacy-package anchor was `ScopeSpecifications`; it is
   `UserRepository` now. Module baseline **32 → 32** (6 module pairs): the file holds the same lines,
   among them the two `identity -> scope` ones.
   `ModularityTest` and `ModuleBaselineTest` need no change. 419 tests ran, and only those two floors
   failed; the rerun of `ArchitectureTest` (45 tests) is green. The 419: the architecture package (the tenancy guard, the domain map, the module gates, the
   context shape), `ArchitectureTest`, the OpenAPI tests with the frozen contract, the scope tests, the
   access-policy differential tests, `JobOrderScopeQueryIntegrationTest` and
   `RefineryOrderRepositoryScopedTest` against Postgres, and the web slices that use the hub through
   SpEL.
**What remains of the scope module** (part 2): `CustomJwtGrantedAuthoritiesConverter` stays in
`service`. It is the only blocked class: it calls `UserReconciliationService.syncUser(jwt)`, an
identity service of rank 3, so the direction is right and no SPI belongs here. *Corrected
2026-10-12:* the identity slice does not simply move the three services; four of their calls must
be inverted first (P4-7, slices a to c below), and the converter moves in slice c. After that it goes to
`scope.internal` (its callers in `config` and `exchange.internal` use it by type, so it may need
`scope.api`). `OwnerScopeService` and `AccessGateService` stay until the per-domain access policies
have taken over their methods (§5.4).
**Corrections:** (1) §5.1 lists the authorities converter as part of the scope kernel, and the
domain map assigns it there; it is the one scope class that cannot move yet. (2) The plan counted
`OrgUnitCascadeService` among the orgunit classes only; it is also a blocker of the scope kernel, and
moving it was not an SPI case but a plain move, because nothing in it points upward. (3) A fixpoint
over the nine candidates alone moves three classes; the obstacle is not in the scope set but in two
classes of other modules that still lie in `service`.

**P4-7 — the identity module's call seams, in three slices; slice a: the Keycloak client and the identity leaves move into `identity.api` / `identity.internal` / `identity.web`, `RestClientConfig` into `platform.api` — part 1a done 2026-10-12**
(owner decision of 2026-10-12: option 1, identity-owned SPIs in slices a, b and c, each green and merged before the next starts).
1. *Probe on `e6c94b4c8`* (ArchUnit dependencies over the compiled classes, nested and generated classes
   folded, one node per first package segment; throwaway, deleted). 65 classes of the domain map's
   `identity` rules lie in layer packages.
   - `UserReconciliationService`, `UserService` and `UserRegistrationService` moved alone close
     `{config, service, identity}`. The closing `identity -> service` edges are exactly four:
     `UserService -> OrgUnitMembershipQueryService` (`findStaffelMembershipOrgUnitIds`,
     `findAllMembershipsForUser`), `UserService -> OrgUnitMembershipService`
     (`reconcileStaffelMemberships`, `addMember`, `removeMember`, `patchFlags`),
     `UserRegistrationService -> UserDeletionService` (`deleteUser`) and
     `UserRegistrationService -> KeycloakService` (`unlinkDiscordIdentity`, `linkDiscordIdentity`,
     `deleteUser`). `UserReconciliationService` is held only by the other two.
   - Pulling the targets along does not converge: `OrgUnitMembership*Service` to `orgunit` drags its
     mapper, `SpecialCommandService` and `OrgUnitSeatNotifier`, and makes `identity -> orgunit` a real
     module edge (rank 3 to rank 4); `UserDeletionService` to `privacy` gives `identity -> privacy`
     against `privacy -> identity`; `KeycloakService` with `KeycloakTrustSupport` adds
     `identity -> config` (`RestClientConfig`) against `config -> identity` (`SecurityConfig`). All 65
     at once close a cycle through 13 top-level packages: `model.User` is named by every entity of
     `model`, and the identity events name `model`'s notification enums back.
   - Hence the three slices: **a** (this one) takes the Keycloak edge away and moves what moves freely;
     **b** and **c** invert the other three edges through identity-owned SPIs.
2. *`RestClientConfig` to `platform.api`* (five files named it, plus `RestClientConfigTest`, which
   followed): the one platform type `KeycloakTrustSupport` needed. Bean names (`restClientBuilder`,
   `externalRestClientBuilder`) and behaviour are unchanged; `CONNECT_TIMEOUT` and `READ_TIMEOUT` became
   `public`, because the trust support lies in another package now.
3. *Probe after it*: `KeycloakService` and `KeycloakTrustSupport` alone close no cycle. A greedy
   addition of the other candidates found 43 classes that can join; this slice takes 33 of them and
   leaves out the rest to stay reviewable and free of entities (no `DATA` change): `UserRepository`
   with its two fragment types (279 files name it), `RoleRepository` (58 files), the entities
   `TermsAcceptance` and `UserApprovalEvent` with their repositories, and `ApprovalDecision`
   (named by `UserApprovalEvent`); the first draft also moved `TermsAcceptanceStatusDto` and
   `ApprovalDecision` and closed `{repository, notification, identity, model}` through
   `TermsAcceptanceRepository -> TermsAcceptanceStatusDto`, so both stayed.
4. *Move* (33 types, 14 tests, 108 files; `git mv` and the import script of P4-6):
   - to `identity.api` (what other classes use): `KeycloakService`, `KeycloakTrustSupport`,
     `KeycloakUserDto`, `PendingApprovalAccessFilter`, `TermsAcceptanceAccessFilter` (the two filters
     that `SecurityConfig` registers), the registration requests and results the remaining controllers
     use (`ApproveRegistrationRequest`, `LinkRegistrationRequest`, `RejectRegistrationRequest`,
     `ReopenRegistrationRequest`, `PendingRegistrationDto`, `RegistrationStatusDto`,
     `UserSyncResultDto`);
   - to `identity.web` (new): `AdminController`, `AdminTermsController`,
     `DiscordAccountExistenceController`, `TermsController`, `TermsDocumentController`;
   - to `identity.internal`: the role, terms and Discord-existence services, mapper and DTOs,
     `DiscordSpiPrecheckProperties`, `TermsVersionProvider`, `UserActorHandleResolver` and
     `UserRoleRecipientDirectory`.
   The constants `PendingApprovalAccessFilter.SELF_STATUS_PATH`, `ANONYMOUS_READ_PATHS` and
   `TermsAcceptanceAccessFilter.EXEMPT_PATHS` are `public` now, because `FilterExemptionSetsTest`
   stays in `config` beside its package-private `PathControlInventory`.
5. *Unchanged*: bean names, REST paths, `@PreAuthorize`, `@Transactional`, cache names, metrics,
   `openapi.json` byte-identical. Ten of the moved classes log (`KeycloakService`, both filters, the
   Discord-existence and terms services, `RoleService`, `TermsVersionProvider` and three controllers);
   their logger names follow the packages (`…identity.api.KeycloakService`), and neither `monitoring/`
   nor `observability.md` matches a logger name of these classes. `KeycloakTrustSupport` is a pure
   move: the missing connect and read timeout of S-01 is untouched, and `PlatformMirrorParityTest`
   reads the new path of the file and still holds it equal to the ingest copy.
6. *NullAway*: the whole `identity` tree is checked now (`identity.api` was). One finding:
   `TermsAcceptanceService.withAliasedSort` wraps the sort it builds in a loop in
   `Objects.requireNonNull`; the pageable is sorted there, so the loop runs at least once. No suppression.
7. *Gates*, measured from the failing assertions: the service layer's selection floor 508 to **507**
   and the join-column floor 239 to **224** (DTOs left the `model` tree); the domain map lost the four
   class rules of the moved filters, trust support and precheck properties, which the package rule
   takes now. Module baseline **32 to 32**, unchanged: `KeycloakTrustSupport -> RestClientConfig` is
   an `identity -> platform` edge, and the filters are used by `config` (rank 14). `ModularityTest`
   needed no change: `identity` already allows `platform::api`.
**What remains of P4-7** (designed here so the next prompts are cheap):
   - *Slice b — the membership-operations SPI.* An identity-owned interface in `identity.api` with the
     six calls `UserService` makes (`findStaffelMembershipOrgUnitIds`, `findAllMembershipsForUser`,
     `reconcileStaffelMemberships`, `addMember`, `removeMember`, `patchFlags`), implemented by a thin
     bean of the orgunit classes that stay in `service` and delegating to `OrgUnitMembershipService`
     and `OrgUnitMembershipQueryService` with the same transaction propagation (`MANDATORY` for the
     writes), so `UserService` names neither. A differential test drives the membership-delta paths
     of `UserService` through the old and the new seam over one fixture and requires the same
     rows, audit entries and exceptions. Baseline: the lines `UserService -> OrgUnitMembershipQueryService`
     and `-> OrgUnitMembershipService` go; the others name request types and the entity.
   - *Slice c — the account-erasure SPI and the move.* An identity-owned interface for
     `UserRegistrationService`'s `deleteUser(UUID, KeycloakPresenceCheck)`, implemented by
     `UserDeletionService` (the privacy orchestrator), then the three services move together
     (`identity.api` for what other modules call, the rest `identity.internal`), and so does
     `CustomJwtGrantedAuthoritiesConverter`, into `scope.internal` (its users in `config` and
     `exchange.internal` need it public in `scope.api`). `UserService -> OwnerScopeService` is the
     baselined `identity -> scope` edge and stays; it is measured through `ModuleBaselineTest`, never
     edited by hand.
   - *Later*: the `User` entity, `UserRepository` (279 files), `Role` and the remaining identity
     classes wait for the orgunit entities to leave `model` (`model -> identity` and
     `identity -> model` close the cycle today); identity's REST surface shedding, D-17 and the
     access policies of section 5.4 are separate steps.
**Corrections:** (1) The P4-6 entry said the identity slice moves the three services together and
that nothing else is needed; the probe above shows four calls that must be inverted first, so
the move is slice c. (2) The 32 leaf classes of the first probe were not free of the `User`/`Role`
edge in every case: `Role` and `UserReferenceDto` are named by `User` and the model DTOs and stay;
`RoleDto`, `RoleMapper` and `RoleService` moved because they point at `Role`, not the other way.

**P4-7, slice b — the membership-operations SPI — done 2026-10-12**
1. *Callee annotations, read from the code*: the four writes (`reconcileStaffelMemberships`, `addMember`,
   `removeMember`, `patchFlags`) are plain `@Transactional` (REQUIRED, not `MANDATORY`) on a class
   that is `readOnly = true` otherwise; the two reads inherit the class-level read-only transaction.
   `UserService.applyMembershipDelta` is `@Transactional` and `UserService` is read-only otherwise, so
   the callees join whatever transaction `UserService` holds. The delegate declares no transaction at
   all, so each callee's propagation applies exactly as before (`OrgUnitMembershipOperationsTest` fails on any
   `@Transactional` on the class or a method).
2. *SPI*: `identity.api.MembershipOperations` with the six calls (first built as
   `MembershipOperations<M>`; the type parameter is gone, see the follow-up below), implemented by
   `service.OrgUnitMembershipOperations` (bean `orgUnitMembershipOperations`, assigned to `orgunit`
   by the name rule, `orgunit -> identity` is a lower-rank edge) delegating verbatim to
   `OrgUnitMembershipService` and `OrgUnitMembershipQueryService`. The rows come back as the
   managed instances: `UserService` sets the initial flags on the row `addMember` returns, which
   works only by dirty checking inside the same transaction.
3. *Types, measured*: a first version named the org-unit request records and the entity in the
   interface and the baseline test refused it with three new `identity -> orgunit` lines
   (`MembershipOperations -> MembershipDeltaRequest`, `-> MembershipFlagsPatchRequest`,
   `-> OrgUnitMembership`), because the baseline may only shrink. The final interface names no
   org-unit type: the Staffel changes travel as the identity-owned record `StaffelAssignment`, the
   patch as its three components (the delegate rebuilds `MembershipDeltaRequest.StaffelChange` and
   `MembershipFlagsPatchRequest` with equal values), and the membership row is the type parameter
   `M`, bound to `OrgUnitMembership` by `UserService` and by the implementation. *Status:* `M` was a
   design choice of this slice that nobody had decided, not an accepted one; the owner decided on
   2026-10-12 to remove it by changing the return contract (follow-up below).
4. *Differential test* `UserServiceMembershipSeamDifferentialTest` (real schema, one rolled-back
   transaction): eight test methods run scenarios once through `UserService.applyMembershipDelta` and once as the
   direct calls it made before the seam, on two fresh users over the same units: a Staffel set that
   grows and shrinks, a Spezialkommando added with flags, patched and removed, a mixed delta in
   request order, an empty delta, a duplicate and a third Staffel, an unknown Staffel and
   Spezialkommando, a second add, a remove of a non-member, a stale patch, and an unknown user. Every
   step must leave the same membership rows (unit, kind, role, flags, row version) and the same
   Staffel ids, the audit rows about the user must be equal in order, and the exceptions must have the
   same type and message (ids masked). Proven able to fail: swapping the two flags in the delegate
   turns the add-patch-remove scenario red. `OrgUnitMembershipOperationsTest` pins arguments, return
   instances, exception identity and the absence of any other call.
5. *Gates*: baseline **32 → 29**, measured by `ModuleBaselineTest`: `UserService ->
   OrgUnitMembershipQueryService`, `-> OrgUnitMembershipService` and `-> MembershipFlagsPatchRequest`
   left. `UserService -> MembershipDeltaRequest` and `-> OrgUnitMembership` stay (the public
   method signature). No floor, domain-map rule or declaration changed; no NullAway finding; no
   compiler warning. Six `UserService` unit tests mock the seam now.
**Corrections:** (1) The slice design said the write calls are `MANDATORY` ("same `MANDATORY`
semantics"); they are plain `@Transactional`, and the seam keeps that. (2) The design named six
baseline lines; three left, because the third (`MembershipFlagsPatchRequest`) went with the flat
patch arguments, and `MembershipDeltaRequest` stays in `UserService`'s signature. (3) A `null`
element in `staffeln` (the request validates the elements but not their presence) now fails with a
`NullPointerException` in `UserService` instead of in the reconcile; both are the same type. The
owner decided on 2026-10-12 to leave that as it is.

**P4-7, slice b-fix — the type parameter `M` is removed — done 2026-10-12**
1. *Callers, read from the code*: `applyMembershipDelta` has one production caller,
   `UserController.patchMemberships`, and it ignores the returned list; it builds its response from
   `findAllMembershipDtosForUser` in the same class-level transaction. Only two tests read the
   return value. So the method returns `void`, and no field of the entity needs to cross the seam.
2. *The flags and the flush, the two things the return value carried*: `addMember(specialCommandId,
   userId, isLogistician, isMissionManager)` takes the initial flags, and the implementation sets them
   on the managed row the org-unit service returned, with the same three statements `UserService` used:
   same row, same dirty checking, same single `MEMBERSHIP_GRANTED` audit row. `patchFlags` returns
   nothing (nobody read the row). The trailing `findAllMembershipsForUser` of `applyMembershipDelta` was
   a JPQL query whose automatic flush ended the delta, so a constraint or optimistic-lock failure
   surfaced from `UserService`; the SPI keeps that point as `flushMembershipChanges(userId)`, which
   runs the same query and returns nothing. Owner decision 2026-10-12: keep the flush point.
3. *Tests*: the differential test now also reads the user's rows over the transaction's JDBC
   connection straight after the call, before any explicit flush, and requires them equal to the
   direct path's; with the flush call turned into a no-op four of the eight methods go red.
   `UserServiceMembershipDeltaTest` pins that the flush is the last call after every mutation and is
   skipped when a mutation fails; `OrgUnitMembershipOperationsTest` pins the flag logic on the very
   row the service returned, for every flag combination.
4. *Gates*: baseline **29 → 28**, measured by `ModuleBaselineTest`: `UserService -> OrgUnitMembership`
   left (the return type is gone). `UserService -> MembershipDeltaRequest` stays, because the request is
   still the parameter of the public method. No floor, domain-map rule or declaration changed.

**P4-7, slice c — the account-erasure SPI, the three user services and the JWT converter move — done 2026-10-12**
1. *Account-erasure SPI*: `identity.api.AccountErasure#deleteAccount(UUID, KeycloakPresence)` with the
   identity-owned enum `KeycloakPresence` (`ENFORCED`, `WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER`).
   `UserDeletionService` implements it, `@Transactional` like the method it forwards to (REQUIRED, read
   from the code), and maps the enum with an exhaustive switch without a `default`. The method is named
   `deleteAccount` because a second `deleteUser` overload made `any(), any()` ambiguous in existing tests.
   `UserRegistrationService` depends on the SPI only. `UserAccountErasureSeamTest` (real schema, one
   rolled-back transaction) pins the mapping of every constant, that the seam is the orchestrator bean, and
   that unknown user, still-in-Keycloak, waived and enforced probe leave the same rows and audit rows and
   fail with the same exception as the orchestrator's own method.
2. *Fresh probe on `affe46d76`*: the three services moved together close no cycle at top level and none
   inside the module. Placement: all three are named by other modules or layer classes, so all three are
   in `identity.api` (`UserService` has 29 foreign users).
3. *A finding the plan did not have*: `ModularityTest` refused `identity.api.UserService -> OwnerScopeService`,
   because identity may not declare `scope::api` (rank 3 against 5). While `UserService` lay in a layer
   package, Spring Modulith did not see the baselined `identity -> scope` edge. It is gone now: an
   identity-owned `identity.api.SquadronVisibility` with the two calls `UserService` made
   (`canSeeSquadron`, `currentUserListScopeSquadronIds`), which `OwnerScopeService` implements with the
   same method bodies (bean name unchanged; the `scope -> identity` edge is a lower-rank one). **The shape of
   this SPI is the two verbatim calls; the plan did not name it. Confirmed by the owner on 2026-10-12.** It also removes the cycle the converter's move
   would have closed (`scope -> identity: converter -> UserReconciliationService` against `identity -> scope`).
4. *The converter*: `CustomJwtGrantedAuthoritiesConverter` moved to `scope.api`, not `scope.internal`:
   `exchange.internal.DatabaseActingMemberAuthorities` calls `assembleFor(User)`, and a class another
   module calls belongs to `api`. Its logger name follows the package.
5. *NullAway* (the whole `identity` tree was already checked): `PartialRoleScopeProperties.isPartialRoleScopeClient`
   takes a `@Nullable` claim (its Javadoc always said so), one `discordUserId` null check in
   `UserReconciliationService`, and the converter's final `AuthenticationServiceException` is built with the
   cause only when there is one. No suppression.
6. *Gates*: baseline **28 → 26**, measured by `ModuleBaselineTest`: `identity -> privacy: UserRegistrationService ->
   UserDeletionService` and `identity -> scope: UserService -> OwnerScopeService` left; `UserService ->
   MembershipDeltaRequest` stays (public parameter). One domain-map class rule (the converter) left. No floor
   changed. The tests of the moved classes stay in their packages and compile against the new ones.
   Four logger names follow the packages (`UserService`, `UserReconciliationService`, `UserRegistrationService`, the converter); nothing in `monitoring/` or the observability spec matches them.

**Umbrella step 5a — the kernel's exception and validation types move into the `kernel` package — done 2026-10-12**
1. *Probe*: the 15 types of `exception` (`AppException` and its eight generic kinds, `DomainProblem`, `ProblemCode`, `CoreProblemCode`,
   `ErrorDisclosurePolicy`, `Entities`) and the five of `validation` (`DtoConstraints`, `OnUpdate`, `QualityValue`, `WholeNumber`,
   `WholeNumberValidator`) that the domain map assigns to `kernel`, moved together with the 65 platform types of step 5b, close no
   cycle at top level.
2. *Move*: all 20 into `kernel` (§5.1 gives the kernel no sub-package), same class names; `git mv` and the import script rewrote 340
   files (imports, qualified names in code, Javadoc and strings). Nothing else changed: the sealed `AppException` still permits
   its subclasses from one package, error codes and `openapi.json` are untouched.
3. *Gates*: `ArchitectureTest` anchored the validation layer on `DtoConstraints`, whose package is `kernel` now, so the layer would
   have selected the whole kernel; the layer takes no legacy package any more (the `validation` package is empty), and its
   selection floor falls 7 → **5**, measured from the failing assertion. No other floor, baseline line or declaration changed.
4. *Text scan* of all tracked files for `backend.exception.<kernel type>` and `backend.validation.<type>`: none left in code or
   resources; the source-path link of `whole-number-amounts.md` is corrected; the two remaining `backend.exception.…` strings name
   `ProblemSupport` and `GlobalExceptionHandler`, which are platform types (step 5b).

**Umbrella step 5b — the platform's 65 types leave the layer packages for `platform.api` / `platform.internal` — done 2026-10-12**
Owner decision 2026-10-12: yes, `kernel` and `platform` physically leave their layer packages, mechanically.
1. *Probe*: the 65 types the domain map assigns to `platform` (`config` 33, `web` 6, `exception` 6, `filter` 5, `logging` 4,
   `metrics` 4, `service` 3, `annotation` 2, `controller` 1, `health` 1), together with the kernel's 20, close no cycle at top level.
2. *Placement* (§5.2): a class another module or a layer class uses goes to `api`, and so does everything an `api` type
   depends on, because the layers inside a module must stay acyclic: **35 in `api`, 30 in `internal`**. The two existing
   `internal` records `ApiClientMetricsProperties` and `RequestBodyLimitProperties` moved to `api`, because `ClientAttribution`
   and `RequestBodySizeLimitFilter` take them. `BasetoolErrorController` lies in `platform.api` (no `platform.web`).
   Members the new foreign users reach became `public` (`RedisJsonFanout`, `SseSendFailureCause`,
   `BackpressureRejectedExecutionHandler`); 374 files were rewritten by `git mv` and the import script.
3. *Tests*: 47 tests moved with their subjects (they reach package-private members); the test fixture `PathControlInventory` is
   public now so `RateLimitRuleCoverageTest` and `FilterExemptionSetsTest` can stay in `config` beside their other fixtures.
4. *NullAway* checks the moved `platform.api` classes (`platform.internal` stays unchecked until step 16): eight sites, fixed by
   `@Nullable` (`ThrowingItemWork`, the argument-resolver parameters, `maskQuotedValues`, `emitJobMetrics`) and by `Objects.requireNonNull` where
   a default message makes the result non-null; no suppression and no behaviour change.
5. *Gates*: the service layer's selection floor 507 → **504**; the controller layer anchored on `BasetoolErrorController`, so it
   takes `UserController` now; the six `layer … platform` rules of the domain map assign no class any more and left;
   `ModularityTest` caught the package-private `BackpressureRejectedExecutionHandler` (now public) and the layers rule the
   two properties records. Baseline unchanged. `compileJava --rerun` shows 0 warnings.
6. *Text scan* of all tracked files: the path-reading tests `PlatformMirrorParityTest` and `ObservationPrivacyFilterMirrorParityTest`, the
   SpotBugs exclude for `BasetoolErrorController`, and the source paths in nine docs were rewritten; no logger name of a moved
   class appears in `monitoring/`, the logging configuration or the observability spec; the historical audit appendices were left.

**Umbrella step 2 — the audit module's 12 classes move into `audit.api` / `audit.internal` / `audit.web` — done 2026-10-12**
1. *Probe*: `audit`, `livesync` and `notification` moved together close no cycle at top level; the audit repository leaving `repository` is
   what lets the handle-anonymisation participant leave `service` (the P4 slice-b note).
2. *Move* (103 files by `git mv` and the import script): `AuditEvent`, `AuditEventDto`, `AuditPurgeResultDto`, `AuditEventMapper`,
   `AuditEventRepository`, `AuditService`, `AuditReportService`, `AuditRetentionService`, `AuditRetentionTask`, `AuditLogPdfFormat` to `audit.api`
   (what other modules use, plus what those depend on), `AuditHandleAnonymisation` and two more to `audit.internal`, `AuditAdminController` to
   `audit.web`. Entity name, table, bean names, REST paths and `openapi.json` are unchanged.
3. *NullAway*: `AuditEventRepository.findFiltered` takes five optional filters (the JPQL tests `IS NULL`); they carry `@Nullable`.
4. *Gates*: the layers of `ArchitectureTest` were anchored on a class (`AuditService`, `UserMapper`, `UserRepository`, `User`, `UserController`), so a
   move of the anchor re-aimed the layer. They are anchored on the legacy package name now (`service`, `mapper`, `repository`, `model`,
   `controller`) and need no re-anchoring again. Floors from the failing assertions: service layer 504 → **495**, join-column selection 224 → **222**;
   the domain-map rule `name ^Audit audit` assigns no class any more and left. Baseline unchanged.
5. *Text scan*: no code or resource names the old packages; nine source paths in `audit.md` and `security-and-access.md` follow.

**Umbrella step 3 — the live-sync module's nine classes move into `livesync.api` / `livesync.internal` / `livesync.web` — done 2026-10-12**
1. *Move* (19 files, six tests follow because they read package-private limits): the request record, the fan-out SPI, the relay, stream and
   subscription-authorizer services and the Redis fan-out to `livesync.api`, the two Redis/local transport classes to `livesync.internal`, `LiveSyncController` to
   `livesync.web`. Paths, bean names and metrics are unchanged.
2. *NullAway*: `RedisLiveSyncFanout.onMessage` takes a `@Nullable` pattern (the Spring listener contract), the authorizer map lookup is wrapped in
   `Objects.requireNonNull` inside the existing `try`, so a missing authorizer still refuses the subscription as a failed check. No suppression.
3. *Gates*: the domain-map rule `name ^(LiveSync|LocalLiveSync|RedisLiveSync)` assigns no class any more and left; no floor or baseline line changed.
4. *Text scan*: source paths in `frontend-ajax-mutations.md`; nothing in code or resources.

**Umbrella step 4 — the notification module's 47 classes move into `notification.api` / `notification.internal` / `notification.web` — done 2026-10-12**
1. *Move* (161 files by script; 21 tests and the test fixtures `StubNotificationEvent` and the recipient-directory test follow, because they read
   package-private members): the entities and enums (`Notification`, the rule and selector entities, `NotificationType`, `NotificationEventType`,
   `NotificationContextRole`, `OrgRelativeRole`, `SelectorKind`), their repositories, mappers and DTOs, the mail types (`MailService`, `MailMessage`,
   `MailProperties`), the notification, mute, rule and stream services to `notification.api` (33 types: what other modules and layer classes use, plus what
   those depend on); the creation, event-listener, recipient, rule-evaluation and timed-runner services, the Redis wiring, the retention and timed tasks,
   the SMTP implementation and the erasure participant to `notification.internal` (12); the two controllers to `notification.web`. The existing
   `NotificationParamsCodec` moved from `internal` to `api`, because `NotificationMapper` names it in an annotation member.
2. *NullAway*: `RedisNotificationFanout.onMessage` takes a `@Nullable` pattern (the Spring listener contract); nothing else.
3. *Gates* from the failing assertions: service layer 495 → **489**, join-column selection 222 → **208**, `mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional`
   498 → **492**; the domain-map name rule `^(Notification|LocalNotification|…)` assigns no class any more and left. Baseline unchanged.
4. *Text scan*: source paths in `notifications.md`, `bank.md` and `materialboerse.md` rewritten; nothing in code or resources named the old packages.
   Paths, bean names, cache names and metrics are unchanged.

**Umbrella steps 6 and 7 — the org-unit module and the identity remainder leave the layer packages — done 2026-10-12**
1. *Probe*: assigning the whole `orgunit` and `identity` census to its module closes cycles through the layer packages that still hold other domains
   (`model`, `mapper`, `repository`, `service`). Four things had to be placed differently from the domain map before the block closed no cycle at
   top level (**derived from the existing calls, not individually reviewed by the owner**): `OrgUnitKind` and `SquadronReferenceDto` go to `kernel`
   (§7.3 already names `OrgUnitKind` as the precondition of a kernel `OrgUnitRef`; `SquadronReferenceDto` is a three-field record `UserDto` embeds);
   `MembershipDeltaRequest` goes to `identity.api`, because `UserService` takes it and `orgunit` may depend on `identity` but not the reverse;
   `UserMapper` goes to `orgunit.api`, because it reads memberships, squadrons and `StaffelMembershipResolver`. Each is a class rule in the domain map.
2. *Move* (636 files by `git mv` and the import script, plus a pass for wildcard imports in 14 tests): `orgunit.api` 48 types (entities, enums, repositories,
   DTOs, mappers and the services other modules call), `orgunit.internal` 3 (`OrgUnitDepartureNotifier`, `OrgUnitMembershipOperations`,
   `OrgUnitMembershipRecipientDirectory`), `orgunit.web` the eight controllers; `identity.api` 14 (`User`, `Role`, `UserApprovalEvent`, `ApprovalStatus`,
   `ApprovalDecision`, `PayoutPreference`, the repositories, `UserDto`, `UserReferenceDto`, `MembershipDeltaRequest`, `UserSyncService`),
   `identity.internal` 9 (terms-acceptance data, the two mail services and listeners, the sync task, the plain-lookup fragment), `identity.web`
   `MyRegistrationStatusController`. `OrgUnitLabels` moved from `orgunit.internal` to `orgunit.api` because the services call it. 29 tests followed their subjects.
   Entity and table names, bean names, SpEL bean references, REST paths and `openapi.json` are unchanged.
3. *Left behind on purpose*: `UserController`, `MeController`, `DiscordRegistrationAdminController`, `RejectedRegistrationRetentionService` and its task stay in
   `controller` / `service`. They call the GDPR services (`UserDeletionService`, `AccountConsolidationService`, `UserAccountMergeService`), which are rank 13, and
   `MeController` reaches the inventory properties and `OwnerScopeService`; their host module is the REST-surface and GDPR step. The domain map assigns the five to the
   transitional `privacy` module (class rules), so their 14 upward edges leave the baseline **by reclassification, not by being resolved** (the other 12 of the 26 were resolved by the moves).
4. *Gates*: the module baseline went **26 → 0** and `ModuleBaselineTest` no longer asserts a non-empty selection (the planted-fixture tests still prove the rule fires);
   the two domain-map name rules for `orgunit` and the one for `identity` assign no class any more and left. `UserDto`, `UserReferenceDto` and
   `SquadronReferenceDto` joined the leaf helpers (the rule let leaf helpers depend on the legacy `model` package, which they left). Floors from the failing
   assertions: service layer 489 → **486**, join-column selection 208 → **180**, `mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional` 492 → **489**, controller targets 113 → **111**.
5. *NullAway* (the `api` packages and the whole `identity` tree are checked): `User.displayName`, `User.email`, `UserApprovalEvent.id`, `OrgUnit.description` / `department` / `parent`,
   `Organisationsleitung.grandAdmiral*`, `OrgUnitMembership.kommandoGroup` and five `UserDto` components carry `@Nullable`; `User.username` takes a nullable setter argument only
   (the column is mandatory); the seven scope parameters of `UserRepository`, `JobOrderReferenceDto.requestingOrgUnit`, `UserMapper.resolveSquadron`
   and the version parameter of `assertVersionMatches` too; the plain-lookup fragment takes its `EntityManager` by constructor; `OrgUnitMembershipService` unwraps the group with
   `Objects.requireNonNull` (the same `NullPointerException` as before). No suppression. `UserDtoRedaction.toPeerShapedAssignees` went to `JobOrderController`, its only caller.
6. *Text scan* of all tracked files: source paths in `bank.md`, `discord-integration.md`, `org-notifications.md` and `security-and-access.md` rewritten; nothing in code, resources
   or `monitoring/` names the old packages. `compileJava --rerun` shows 0 warnings.
7. *Correction (found by `MissionUnitFrequencyTest` on the pull request's CI)*: the move put `UserRepositoryPlainLookupFragmentImpl` in `identity.internal` and its
   interface in `identity.api`. Spring Data finds a fragment implementation beside its interface, so it did not find it and derived `findPlainById` from the method name as a
   query, which auto-flushes; nine tests that persist a ship without insurance went red. The implementation lies in `identity.api` now, and `RepositoryFragmentPlacementTest`
   pins that every `*Fragment` interface has its `*FragmentImpl` in the same package. The move script keeps fragment implementations beside their interface from here on.

**Umbrella step 8 — the transitional privacy module leaves the layer packages — done 2026-10-12**
1. *Probe*: the 28 classes the domain map assigns to `privacy` (the 23 of the census plus the five identity classes step 7 left behind) close no cycle at top level.
2. *Move* (82 files; 16 tests follow): `privacy.api` 18 types (the deletion-request, data-export, person-search, merge and consolidation services and their
   DTOs, the entity and its repository), `privacy.web` the eight controllers (`UserController`, `MeController` and `DiscordRegistrationAdminController` among them, paths unchanged),
   `privacy.internal` the rejected-registration retention service and its task. The layer rule and `ModularityTest` then moved three existing `privacy.internal` types the api services take
   (`PersonMentionRegistry`, `PersonalDataExportRegistry`, `HandleSpellings`) to `privacy.api` and `RejectedRegistrationRetentionProperties` from `identity.internal` to `identity.api`
   (the task reads it). `DataExportPdfFormat` lost its `service.pdf` subpackage.
3. *NullAway*: `DeletionRequest.id` carries `@Nullable` (the constructor does not set it), `DataExportService` and `PersonSearchService` take their `EntityManager` by constructor
   instead of `@PersistenceContext` (`PersonSearchServiceAuditTest` passes a mock), `DataExportReportService.label` wraps the nullable message lookup in `Objects.requireNonNullElse`
   with the key as the fallback it already used. No suppression.
4. *Gates* from the failing assertions: service layer 486 -> **485**, join-column selection 180 -> **173**, read-only mutation rule 489 -> **488**. The module baseline stays empty.
   `compileJava --rerun` shows 0 warnings.
5. *Text scan*: source paths in `bank.md`, `personal-inventory-blueprints.md` and `security-and-access.md` rewritten; one test javadoc named the old package of a moved test.
   The module stays transitional (§5.1 rank 13); dissolving it into `identity` is the REST-surface and GDPR-SPI work, not this step.

**Umbrella step 10 — the blueprint module's last ten types move into `blueprint.api` — done 2026-10-12**
1. *Probe*: the ten types close no cycle at top level, alone or together with the inventory's ten.
2. *Move* (61 files; `PersonalBlueprintRepositoryTest` follows): `PersonalBlueprint`, `PersonalBlueprintRepository`, `BlueprintSource`, `BlueprintOwnerProduct`, the import
   preview, entry, suggestion and status DTOs, the create request and `PersonalBlueprintResponse` to `blueprint.api`. **Derived, not individually reviewed:** the plan names "a read API for
   the seven finders"; the repository is published in `blueprint.api` instead, as the catalogue and identity do, because the exchange's feed, write, undo and demand services and the
   job order's blueprint-owner service already call its finders and `blueprint` ranks below both. A read API can replace the published repository later without a path or data change.
   `PersonalBlueprintResponse` first went to `internal` and moved to `api` when the layer rule showed `PersonalBlueprintCommands` returns it.
3. *NullAway* (`blueprint` is checked as a whole tree): `PersonalBlueprint.acquiredAt`, `note` and `sourceClientId` and the four optional components of `BlueprintImportEntryDto` carry
   `@Nullable`; no suppression, no behaviour change.
4. *Gates*: the domain-map name rule `^(PersonalBlueprint|DefaultBlueprint|…)` assigns no class any more and left; join-column selection 173 -> **165**. The module baseline stays empty.
   `compileJava --rerun` shows 0 warnings.
5. *Text scan*: source paths in `hangar-notifications.md` and `personal-inventory-blueprints.md`; nothing in code or resources.

**Umbrella step 9 — the inventory's last ten types move into `inventory.api` — done 2026-10-12**
1. *Probe*: the ten types close no cycle at top level once the blueprint's ten have moved (`InventoryCraftableStock -> CraftabilitySlice` needed the blueprint first).
2. *Move* (122 files by script; `InventoryItemTest` follows): `InventoryItem`, `InventoryJobOrderAllocation`, `InventoryMissionAllocation`, `InventoryGameItemReferenceDto`, `JobOrderEarmark`,
   `MissionEarmark`, `JobOrderMaterialStockRow`, `MaterialCollectionEntryDto`, `OrderLinkedStockRows` and `StockViewerAccess` to `inventory.api`. **Not as planned, derived, not individually
   reviewed:** the plan converts the Lager row to a record plus id commands and then moves `InventoryItem` into `inventory.internal`. That conversion is **not done**: the entity is published in
   `inventory.api` because the exchange, the job order and the Materialbörse (rank above the inventory) still read the row. `StockWriteOwnershipTest` passes, so no foreign module writes it. The
   record-plus-commands conversion stays open as the remainder of P4-1 part 3.
3. *NullAway* (`inventory` is checked as a whole tree, and the entity now reaches `joborder` and `materialexchange`): `InventoryItem.material`, `gameItem`, `quality`, `note` and `owningOrgUnit` carry
   `@Nullable` (a game-item row has no material and no quality); so do the optional components of `InventoryGameItemReferenceDto`, `MaterialCollectionEntryDto` (the redacted owner and location) and
   `JobOrderMaterialStockRow`, the owner id of `StockViewerAccess.mayEditInventoryItem`, the audit reference of `recordAllocation` and `collectNote`. Eleven dereferences of a getter that is
   null only for the other kind of row are wrapped in `Objects.requireNonNull`, which throws the same `NullPointerException` the dereference threw. No suppression.
4. *Gates* from the failing assertions: mapper layer (two rules) 106 -> **105**, service layer 485 -> **484**, join-column selection 165 -> **160**, read-only mutation rule 488 -> **487**;
   `InventoryGameItemReferenceDto` and `MaterialCollectionEntryDto` joined the leaf helpers (the rule let them depend on the legacy `model` package); the domain-map name rule
   `^(Inventory|StockViewerAccess)` assigns no class any more and left. The module baseline stays empty. `compileJava --rerun` shows 0 warnings.
5. *Text scan*: no code, resource or doc named the old packages.

**Umbrella step 11a — the hangar module's six types move into `hangar.api` — done 2026-10-12**
1. *Probe*: the six types close no cycle at top level; hangar, mission and job order are independent of each other in the probe.
2. *Move* (62 files; `ShipMapperTest` and `ShipRepositoryPersonalHangarTest` follow): `Ship`, `ShipDto`, `ShipRequestDto`, `ShipMapper`, `ShipRepository` and `ShipTypeMatcher` to `hangar.api`
   (the mission module, the exchange and the job order use all of them). Entity and table names, bean names, paths and `openapi.json` are unchanged.
3. *NullAway* (`hangar` is checked as a whole tree): `Ship.name`, `location` and `owningOrgUnit`, the optional `location`, `owner` and `owningSquadron` of `ShipDto` and the scope, search
   and fitted parameters of `ShipRepository` carry `@Nullable`; no suppression.
4. *Gates* from the failing assertions: service layer 484 -> **483**, join-column selection 160 -> **158**, read-only mutation rule 487 -> **486**; `ShipDto`, `ShipTypeDto`, `LocationDto` and
   `ManufacturerDto` joined the leaf helpers (pure records the mission peer redaction builds); the domain-map name rule for the hangar classes assigns no class any more and left.
   The module baseline stays empty. `compileJava --rerun` shows 0 warnings.
5. *Text scan*: source path in `hangar-notifications.md`. `MissionUnit.ship` is still an entity association (the eighth conversion of §5.6); it did not block the move, because `mission` ranks above `hangar`.

**Umbrella step 11b — the mission module's 36 types move into `mission.api` / `mission.internal` — done 2026-10-12**
1. *Probe*: the 36 types close no cycle at top level once the hangar's six have moved.
2. *Move* (192 files; six tests follow): the mission, structure, participant, finance and frequency entities and enums, their repositories, mappers and DTOs, `MissionViewerAccess`,
   `MissionOperationLookup` and the repository fragment pair to `mission.api` (33), the ownership entity and repository and `MissionRecipientDirectoryImpl` to `mission.internal` (3).
   `MissionRepositoryAuthorizationFragmentImpl` stays beside its interface (the step-7 correction). Entity and table names, bean names, paths and `openapi.json` are unchanged.
3. *NullAway* (`mission` is checked as a whole tree): `@Nullable` on the optional fields of `Mission`, `MissionParticipant`, `MissionUnit`, `MissionStep` and `MissionObjective`, on the matching
   components of `MissionDto`, `MissionUnitDto`, `MissionParticipantDto` and `MissionOperationDto`, on `referenceTime()` and on the optional parameters of `MissionRepository.searchMissions`;
   `MissionRepositoryAuthorizationFragmentImpl` takes its `EntityManager` by constructor. `MissionMapper.rosterUserMapper` is injected through a package-private `@Autowired` setter, because
   `ModularityTest` rejects field injection of a type of another module (`UserMapper` is in `orgunit`). No suppression, no behaviour change.
4. *Gates* from the failing assertions: mapper layer (two rules) 105 -> **103**, join-column selection 158 -> **143**, controller targets 111 -> **107**; the mission DTOs, `JobTypeDto`,
   `FrequencyTypeDto`, `JobTypeArchetype`, `OrgUnitReferenceDto`, `OrgUnitKind`, `PayoutPreference` and `MissionOperationSummary` joined the leaf helpers (the mission peer redaction builds the DTOs);
   the domain-map name rule for the mission classes assigns no class any more and left. The module baseline stays empty. `compileJava --rerun` shows 0 warnings.
5. *Text scan*: nothing in code, resources or docs named the old packages. `MissionUnit.ship` is still an entity association (hangar ranks below mission); the eighth §5.6 conversion did not block the move.

**Umbrella step 11c — the job-order module's 45 types move into `joborder.api` / `joborder.internal` — done 2026-10-12**
1. *Probe*: the 45 types close no cycle at top level once the hangar's and the mission's types have moved.
2. *Move* (150 files; six tests follow, among them `MaterialAmountRoundingTest`, which reads the package-private rounding of three entities): the order, material, item, assignee and handover
   entities and enums, their repositories, mappers and DTOs, the material-claim entity, `ClaimBucketSource`, the item service, the material-requirement resolver, the stock projection service and
   `QualityBucketAllocator` to `joborder.api` (39), the claim-create request, the two handover repositories, the claim repository and the stock-entry and stock-group DTOs to `joborder.internal` (6).
   Entity and table names, bean names, paths and `openapi.json` are unchanged.
3. *NullAway* (`joborder` is checked as a whole tree): `@Nullable` on `JobOrder.priority`, `JobOrderHandoverItem.quality` and `locationName`, and on the optional components of `LinkedStockAttributionDto`,
   `AggregatedMaterialDto`, `JobOrderMaterialDto`, `JobOrderItemDto`, `ClaimDto`, `JobOrderItemStockEntryDto`, `JobOrderItemStockGroupDto` and the catalogue's `GameItemReferenceDto.kind`, and on the squadron
   parameters of `JobOrderRepository`; three dereferences of a map lookup and one unboxing are wrapped in `Objects.requireNonNull`, which throws the same `NullPointerException`.
   `JobOrderMapper` gets its `UserMapper` and `StockViewerAccess` through `@Autowired` setters, because `ModularityTest` rejects field injection of a type of another module. No suppression.
4. *Gates* from the failing assertions: service layer 483 -> **481**, join-column selection 143 -> **120**, read-only mutation rule 486 -> **484**; the stock DTOs joined the leaf helpers; the two
   domain-map name rules for the job-order classes assign no class any more and left. The module baseline stays empty. `compileJava --rerun` shows 0 warnings.
5. *Text scan*: source paths in `security-and-access.md` and `whole-number-amounts.md`.

**Umbrella step 11d — the composition root `app` is declared and takes two of its three classes — done 2026-10-12**
1. *Probe*: the three classes the domain map assigns to `app` close no cycle. The census had listed them but the checklist named no step.
2. *Move* (16 files; five `SecurityConfig*Test` follow): `SecurityConfig` to `app.api`, `DataInitializer` to `app.internal`, plus the `app` module declaration. `allowedDependencies` is every other
   module's `api` and the kernel, which is what `ModularityTest` derives from the rank-14 row. `ModularityTest` lists `app` among the 26 declared modules. Bean names, the filter chains and paths are unchanged.
3. *Not moved — open question for the plan*: `BusinessMetricsCollector` stays in `task`. It reads the internal repositories of five modules (bank, operation, refinery, materialexchange, material exchange requests), and
   `ModularityTest` rejects that from `app`. Moving it needs either each module publishing its own gauges (the plan's "until each module publishes its own gauges") or five repositories published in `api`.
   Neither is a pure move, so the class waits; the domain map still assigns it to `app`, and no baseline line is involved.
4. *Gates*: no floor changed; the `Javadoc` link of `MonitoringScrapeSecurityConfig` to `SecurityConfig` is the only reference from `platform` and creates no bytecode dependency.
   `compileJava --rerun` shows 0 warnings.
5. *Text scan*: source paths in the role matrix, ADR-0034, the REST-cut appendix and four specs.

**Umbrella step 12 — design of the org-unit and user gates (§5.4), not built; waits for the owner's decision**
What the code says (2026-10-12): `canSeeOrgUnit` and `canEditOrgUnit` are pure aliases of `canSeeSquadron` and `canEditSquadron` (`scopePredicate.permits(unitId)` for both); `canActOnTargetUser(target, edit)`
passes for an admin, for the member themselves, or when the read or edit scope accepts any membership of the target. Ten call sites, no `@PreAuthorize` expression: `JobOrderService` and `MaterialClaimService`
(`canEditOrgUnit`), `InventoryAccessPolicy` and `RefineryAccessPolicy` (`canActOnTargetUser`, twice for refinery), `OrgUnitStampingService` (`canEditOrgUnit`, twice). Tests that name them: `OwnerScopeServiceTest`,
`JobOrderServiceTest` (four), `MaterialClaimServiceTest` (two).
*Why the plan's rule does not place them.* §5.4 makes the policy of an aggregate a bean of the module that owns the aggregate. The aggregates are the org unit (`orgunit`, rank 4) and the user (`identity`, rank 3), but both
gates need `RequestScopeResolver` (`scope`, rank 5), so a policy in the owning module would depend upward. A policy that fits the ranks has to live in `scope`.
*Options.*
- **A. Two named policy beans in `scope.api`** (recommended, the `OperationAccessPolicy` precedent): `OrgUnitAccessPolicy` (bean `orgUnitAccessPolicy`, methods `canSeeOrgUnit(UUID)` and `canEditOrgUnit(UUID)`, delegating to the
  scope kernel's `canSeeSquadron` / `canEditSquadron`) and `UserAccessPolicy` (bean `userAccessPolicy`, method `canActOnTargetUser(UUID, boolean)`, the body of `AccessGateService` verbatim). `OwnerScopeService` and
  `AccessGateService` lose the three methods and the pass-through; the ten call sites change their receiver. Because no expression names the methods, no SpEL bean name changes and the authorization matrix does not change.
  Pinned by a differential test that keeps a verbatim copy of the old methods and compares the verdicts over the same caller and unit matrix as `OperationAccessPolicyDifferentialTest` (admin unpinned and pinned, members of zero,
  one and two units, a member pinned to an own and a foreign unit, a Bereich lead reaching a child by cascade, guests, self, an unknown target), before the old methods go. Gains little: a rename of receivers, and a smaller hub.
- **B. Keep them and say so.** Declare the three as part of the scope kernel (they are its on-behalf and org-unit gates, rank 5 by construction), delete only the pass-through duplicates in `OwnerScopeService` so each
  gate has one home (`AccessGateService`), and close step 12 with that sentence in §5.4. No behaviour and no test changes beyond the ten receivers.
- **C. Policies in the aggregate modules behind an SPI.** `orgunit` owns `OrgUnitAccessPolicy`, `identity` owns `UserAccessPolicy`, each asking a scope-owned SPI for "does the caller's scope accept this unit". Follows §5.4
  to the letter, adds two SPIs for a one-line delegation and an inverted dependency; not recommended.
The question for the owner: A, B or C.

**Umbrella step 16 — NullAway checks the whole tree of eleven more modules — done 2026-10-12, partly**
1. *Census with `-Xmaxerrs` raised*: `audit`, `livesync`, `notification` and `dashboard` had no finding; `admin` 1, `personalinventory` 1, `platform` 3, `privacy` 4, `orgunit` 11, `promotion` 27 and `orgchart` 40 were fixed;
   `bank` (44) and `exchange` (69) were counted and left; adding `kernel` and `app` produced a cascade (the kernel's `ScuAmounts`, `StringNormalization` and `AppException` return nullable values every module uses).
2. *Enabled* (`nullAwayPackages` in `backend/build.gradle.kts`): `admin`, `audit`, `dashboard`, `livesync`, `notification`, `orgchart`, `orgunit`, `personalinventory`, `platform`, `privacy`, `promotion` join the eleven already checked, 22 in all.
3. *Fixes by annotation or restructuring, no suppression*: `@Nullable` on the optional fields of `OrgUnit`, `Organisationsleitung`, `OrgChartPosition`, `RankRequirement` and the optional components of 17 DTOs and records; the `owningSquadronId` parameter of
   the promotion repositories; `HandleErasureCoverage.of` returns `@Nullable`; `AndroidClientProperties.releasesUrl` reads the override once; eleven dereferences of a value that is non-null by the surrounding rule are wrapped in `Objects.requireNonNull`
   (the same `NullPointerException`). `OrgUnitMembershipService.patchFlags` got an overload on the plain values, which the `MembershipOperations` SPI calls, because the SPI's version may be `null` (an ADD or REMOVE carries none) and the request record
   cannot say so without two conflicting nullness annotations; a `null` version still ends in the same 409.
4. *Open*: `bank`, `exchange`, `kernel` and `app` stay on the `api`-only list; `bank` and `exchange` get their own conventions when P5-c and P5-d extract them.

**Umbrella step 17 — requirement status, arc42 and the CLAUDE.md files — done 2026-10-12, partly**
1. *Plan §14 status*: REQ-XCH-009/011 need nothing but the rewritten source paths; REQ-DATA-010 waits for the access-policy decision of step 12 (the six scope fragments are still `ScopeSpecifications`);
   REQ-AUDIT-001 waits for P3-11, which the plan names and nowhere defines (question to the owner).
2. *arc42 §5*: the layer-package table is marked as the roles the layers had; the audit paragraph no longer says its classes sit in the layer packages; the per-module paragraphs were extended with every step.
3. *CLAUDE.md*: the root file describes the backend as a modular monolith with `api` / `internal` / `web`; `backend/CLAUDE.md` gained three landmines found in this pull request (fragment implementation beside its
   interface, no field injection of another module's type, tests that read backend sources by path).

**Umbrella steps 13, 1, 18 and 19 — designs and reasons, not built; they wait for the owner**
*Step 13 — D-17, the member settings leave `app_user` (design, nothing written).*
1. *What moves.* `app_user.default_payout_preference` (VARCHAR 50, nullable, V142) and `app_user.share_blueprints_globally` (BOOLEAN NOT NULL DEFAULT FALSE, V163). Readers and writers: `User`, `UserService`
   (`updateUserDefaultPayoutPreference`, `updateUserShareBlueprintsGlobally`, which also records `BLUEPRINT_SHARING_CHANGED`), `UserRepository.findIdsBySharingBlueprintsGlobally` (read by `PersonalBlueprintOverviewService` and
   `JobOrderItemBlueprintOwnersService`), `MissionParticipantService` (pre-fills the sign-up), `UserController` (the four endpoints, now in `privacy.web`), `IdentityExportSections` and `IdentityMentionSource`.
2. *Target.* Two tables with their own `@Version`: `member_payout_setting` (owner `mission`) and `member_blueprint_sharing` (owner `blueprint`), one row per member, `user_id` the primary key and a foreign key to `app_user` `ON DELETE CASCADE`.
   A member with no row has the default (no preference; sharing off), so the sharing table holds only rows that were ever written.
3. *Migration, phase 1 (additive, `V283`; the number is claimed by re-grepping `origin/main` and the open pull requests at push time; today `origin/main` ends at V282 and no other pull request is open):*

```sql
CREATE TABLE member_payout_setting (
    user_id UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    default_payout_preference VARCHAR(50) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE TABLE member_blueprint_sharing (
    user_id UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    share_globally BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
INSERT INTO member_payout_setting (user_id, default_payout_preference)
SELECT id, default_payout_preference FROM app_user WHERE default_payout_preference IS NOT NULL;
INSERT INTO member_blueprint_sharing (user_id, share_globally)
SELECT id, TRUE FROM app_user WHERE share_blueprints_globally;
```

   The old columns stay (the repository's two-phase drop rule); the entities stop mapping them. Phase 2, a later release, is
   `ALTER TABLE app_user DROP COLUMN default_payout_preference, DROP COLUMN share_blueprints_globally`. The types of `created_at` and `updated_at` follow what `AbstractEntity` maps; the final text is checked against
   `ddl-auto: validate` before anything is pushed.
4. *Data check, run read-only on the host before and after the release:*

```sql
SELECT (SELECT count(*) FROM app_user WHERE default_payout_preference IS NOT NULL) AS payout_users,
       (SELECT count(*) FROM member_payout_setting) AS payout_rows,
       (SELECT count(*) FROM app_user WHERE share_blueprints_globally) AS sharing_users,
       (SELECT count(*) FROM member_blueprint_sharing WHERE share_globally) AS sharing_rows;
```

   `payout_users = payout_rows` and `sharing_users = sharing_rows` immediately after the migration.
5. *Rollback.* Deploying the previous release is enough while phase 2 has not run: it reads the old columns, which still hold the values of the migration day. Changes made in the new tables after the upgrade are not in them;
   to carry them back first:

```sql
UPDATE app_user u SET default_payout_preference = s.default_payout_preference
FROM member_payout_setting s WHERE s.user_id = u.id;
UPDATE app_user u SET share_blueprints_globally =
    COALESCE((SELECT b.share_globally FROM member_blueprint_sharing b WHERE b.user_id = u.id), FALSE);
```

   The new tables can stay or be dropped afterwards; nothing in the old release reads them.
6. *Consequences the owner should see.* (a) The `version` of the four endpoints (`/users/me/payout-preference` and `/users/me/blueprint-sharing` with their moved paths) becomes the setting's own counter, 0 for a member with no row.
   The web profile page seeds both forms from the user-row version of `/users/me`, so it changes with this step (`ProfileController`, `profile.js`, the two forms). The Android app (`MemberPreferencesRepository`, T1 app contract)
   reads `version` in the same GET it later PUTs, but whether it ever combines it with the user version cannot be proved from the call list, so an app release may be needed. (b) Fine-grained locking is the point: saving the payout
   card no longer makes the profile and RSI-handle cards stale. (c) The account merge keeps the old outcome: the settings stay with the emptied source account (`staysWithTheAct`), because the old columns did.
   (d) The GDPR registries gain the two tables: erasure by the cascade, an Art. 15 export section in each owning module, the two columns removed from the identity export, the mention-source exemption for the payout column,
   `table-ownership.txt` and the coverage floors. (e) The audit event `BLUEPRINT_SHARING_CHANGED` moves with the sharing write into the blueprint module; its type, domain and payload do not change.

*Step 1 — the mutation target.* `backend/build-settings.properties` aims PIT at `backend.service.*` and `backend.service.*Test`. No main class lies in `service` any more, so the target matches nothing. Options:
A) the module internals, `de.greluc.krt.profit.basetool.backend.*.internal.*Service` with the tests of the same names (most of the old target's content); B) `*.internal.*` and `*.api.*Service` together (many more classes, a much longer run);
C) retire the target until a module extraction gives it a natural place. Not decided here.

*Steps 18 and 19 — `backend-exchange` and `backend-bank`.* §5.8 starts them "only once the package boundary of each has been green for some releases". Both packages became real modules, with the Modulith and rank gates, on this branch within days,
and NullAway does not cover them yet (69 and 44 findings). Extracting them now would also move their tests, their JaCoCo and PIT settings and the OpenAPI generator's classpath in the same change. Not started.

*Step 15 — P3-11.* The plan names the per-command audit contract and nowhere defines it; nothing was invented.

**Umbrella step 12 — built: the org-unit and user gates are named policies in `scope.api` — done 2026-10-12 (option A, confirmed by the owner 2026-10-12)**
1. *Built as designed above.* `OrgUnitAccessPolicy` (bean `orgUnitAccessPolicy`: `canSeeOrgUnit`, `canEditOrgUnit`, both delegating to the scope kernel's `canSeeSquadron` / `canEditSquadron`) and `UserAccessPolicy` (bean `userAccessPolicy`:
   `canActOnTargetUser`, the body of the former `AccessGateService` method). `OwnerScopeService` and `AccessGateService` lost the three methods; `AccessGateService` lost its `OrgUnitMembershipRepository`. The ten call sites changed receiver:
   `JobOrderService` and `MaterialClaimService` ask `orgUnitAccessPolicy`, `InventoryAccessPolicy` and `RefineryAccessPolicy` ask `userAccessPolicy`, `OrgUnitStampingService` calls `canEditSquadron` directly. No expression names the methods,
   so no SpEL bean name changed and the authorization matrix did not change. `JobOrderService` no longer holds an `OwnerScopeService`.
2. *Proof.* `OrgUnitAndUserAccessPolicyDifferentialTest` compares the policies with a verbatim copy of the old methods over nine callers (admin unpinned and pinned to either unit; members of zero, one and two units; a member pinned to an own and to a
   foreign unit; a Bereich lead reaching a child Staffel by cascade) against four org units and seven targets (members of one unit, of both, of the child, of none, an unknown account and the caller themselves), 189 verdicts. It was proven able to fail:
   dropping the self shortcut from `UserAccessPolicy` turned it red. The existing policy differential tests, `OwnerScopeServiceTest`, `JobOrderService*Test` and `MaterialClaimServiceTest` follow the new constructors; `TenancyGuardTest` and the
   architecture gates pass unchanged. `compileJava --rerun` shows 0 warnings.

**Umbrella step 13 — built: D-17, the member settings leave `app_user` — done 2026-10-12 (design approved by the owner 2026-10-12), not pushed until the final SQL is reported**
1. *Migration* `V283__create_member_payout_setting_and_blueprint_sharing.sql` as designed, with named foreign keys, `ON CONFLICT (user_id) DO NOTHING` on the two copies (so a re-run changes nothing) and `COMMENT ON` for the tables and their key
   columns; the old `app_user` columns stay (phase 2 is a later release). The version number is claimed at push time by re-grepping `origin/main` and the open pull requests. Applied to a real PostgreSQL by the suite and validated against the
   entities (`ddl-auto: validate`).
2. *Code.* `mission.api`: `MemberPayoutSetting`, its repository and `MemberPayoutSettings` (`snapshotOf`, `defaultFor`, `update`). `blueprint.api`: `MemberBlueprintSharing`, its repository and `MemberBlueprintSharings` (`snapshotOf`,
   `userIdsSharingGlobally`, `update`, which records `BLUEPRINT_SHARING_CHANGED` on a change). Both read a member without a row at version 0 and accept version 0 for the first write. `User`, `UserService` and `UserRepository` lost the two
   fields, the two update methods and the sharers query; `MissionParticipantService`, `PersonalBlueprintOverviewService`, `JobOrderItemBlueprintOwnersService` and `UserController` use the new services. The account export no longer selects the
   two columns; each owning module has an Art. 15 section (positions 460 and 470), the payout column is exempt in the mission mention source, the account merge leaves both rows with the emptied source account (`staysWithTheAct`, as the columns did),
   erasure is the foreign key's cascade, and `table-ownership.txt` names the owners.
3. *Frontend.* The profile page seeds the payout and sharing forms with the version of their own endpoint (it seeded all four from `/users/me`); the client returns the PUT answer, the two AJAX endpoints answer with that version, and `profile.js`
   writes a fresh user-row version only into the description and RSI-handle forms and a setting's own version only into its own form. The REST paths, the field names and the OpenAPI document are unchanged.
4. *Proof.* `V283MemberSettingsMigrationTest` runs the data-transfer statements read from the migration file over members the old columns describe: the runbook's counts agree, nobody else gets a row, a second run changes nothing, and the rollback
   statements restore the old columns from the new tables. `MemberPayoutSettingsTest` and `MemberBlueprintSharingsTest` pin the absent row, the own version, the lock and the audit event. The GDPR gates (`GdprParticipantCoverageTest`,
   `UserAccountMergeCoverageTest`, `PersonalDataExportCatalogueTest`, `PersonMentionCatalogueTest`, `DataExportEndStateTest`, `TableOwnershipTest`) hold with the two new tables; the committed export catalogue, the mention catalogue and the end-state document
   changed for them. Whether the Android app needs a release is for the owner to settle with the app repository.

**The rest of the plan — one umbrella pull request (#2552), checklist — started 2026-10-12**
Owner instruction 2026-10-12: all remaining steps in one pull request, one commit per step, each green
on its targeted tests, the full suite being the pull request's CI. Built from this plan and the code
after step 0 (census of the classes the domain map assigns to a module but that still lie in a layer
package: `orgunit` 61, `platform` 65, `notification` 47, `joborder` 45, `mission` 36, `identity` 29,
`privacy` 23, `kernel` 20, `audit` 12, `inventory` 10, `blueprint` 10, `livesync` 9, `hangar` 6, `app` 3).
Every backend step begins with a throwaway probe on the current tree; a cycle that needs an SPI
whose shape the plan does not give, a behaviour or contract change, or a production-data question
ends the step with a question to the owner. Ticked as the steps land; `Q` marks a step that cannot start
before a decision.

- [x] **0.** P4-7 slice c (this pull request's first commit).
- [ ] **1.** `Q` options in the entry above — Housekeeping: `backend/build-settings.properties` aims the mutation tests at
      `backend.service.*`, which shrinks with every move (P4-5 "Open").
- Rank 0–1 modules leave the layer packages (nothing below depends on them being late; **5a, the `kernel` half of step 5, is done**):
  - [x] **2.** done — `audit` (12): `AuditEvent`, `AuditEventRepository`, `AuditService` and its report,
        retention and handle-anonymisation participant, mapper, controller, task. The P4 slice-b entry
        records that the participant waits for the repository.
  - [x] **3.** done — `livesync` (9: controller, DTO, seven services).
  - [x] **4.** done — `notification` (47: entities and enums, rules, mail, recipient services, the GDPR
        participants that sit in `service`).
  - [x] **5.** done (5a `kernel`, 5b `platform`; owner decision 2026-10-12) — `kernel` (20 types in the layer packages `exception` and `validation`) and `platform`
        (65 in `config`, `filter`, `logging`, `metrics`, `web`, `exception`, `annotation`, `health`):
        the domain map assigns whole layer packages to them and no plan step says they must move.
- Foundation modules (§5.2 publishes their entities in `api`):
  - [x] **6.** done — `orgunit` (61: the org-unit entities and enums, 7 repositories, 12 services, 4 mappers,
        8 controllers). Prerequisite for the `User` entity (`OrgUnitMembership.user`).
  - [x] **7.** done except five classes — `identity` remainder (29): `User`, `Role`, `TermsAcceptance`, `UserApprovalEvent`,
        `PayoutPreference` (deferred "with `User`", §7.3), `UserRepository` (279 files name it),
        `UserMapper`/`UserDto`/`UserReferenceDto`, `UserController`, `MeController`, the mail services,
        `UserSyncService`/`UserSyncTask`, the rejected-registration retention.
        `UserController`, `MeController`, `DiscordRegistrationAdminController`, `RejectedRegistrationRetentionService` and its task moved into the transitional
        `privacy` module in step 8, until their host module exists; `UserMapper` went to `orgunit.api` (see the entry above).
  - [x] **8.** done — `privacy` (23, 28 with the identity leftovers): the GDPR orchestration classes (§5.1 rank 13) into `privacy`, then
        dissolved into identity as §5.1 and §7.6 say.
- Business core leftovers (the entities other modules still hold):
  - [x] **9.** moved, conversion open — P4-1 part 3, inventory: the Lager row becomes a record plus id commands, then
        `InventoryItem` and the two allocations move into `inventory.internal`; with them
        `InventoryGameItemReferenceDto`, `JobOrderEarmark`/`MissionEarmark`, `JobOrderMaterialStockRow`
        and `StockViewerAccess`.
  - [x] **10.** done — P4-2 part 2, blueprint: a read API for the seven `PersonalBlueprintRepository` finders,
        the data types the exchange names into `blueprint.api`, then the ten remaining types.
  - [x] **11.** done — `hangar` (6), `joborder` (45), `mission` (36): what the foreign modules no longer
        hold after steps 6, 7 and 9; of the eight association-to-id conversions of §5.6 seven are done,
        `MissionUnit.ship` is still an entity association.
  - [x] **12.** done, option A confirmed by the owner 2026-10-12 — Access policies left in the scope hub (§5.4): `OwnerScopeService` and
        `AccessGateService` keep the org-unit and user gates; `Q` the plan names no policy for them.
- Persistence:
  - [x] **13.** built, approved by the owner 2026-10-12, held back until the final SQL is reported — D-17, member settings move with their owner: the payout-preference and
        blueprint-sharing columns into tables of the owning modules, each with its own version, by a
        Flyway migration with data transfer. The api-cut notes say the columns stay in the cut, so this
        step comes after the path moves; its SQL and rollback go to the owner before they are pushed.
  - [x] **14.** Table-ownership map (§5.6): done before this branch (`table-ownership.txt`,
        `TableOwnershipTest`); each move updates the owner of the tables it moves, nothing else.
  - [ ] **15.** `Q` P3-11, the per-command audit contract (REQ-AUDIT-001): the moves it waited for are done, but the plan names it and does not define it; waiting for the owner to say what the contract is.
- Quality gates:
  - [x] **16.** partly done, `bank`, `exchange`, `kernel`, `app` open — NullAway to the whole tree of every module as it becomes whole (today: `blueprint`,
        `catalogue`, `hangar`, `identity`, `inventory`, `joborder`, `materialexchange`, `mission`,
        `operation`, `refinery`, `scope`; the other modules' `api` packages only).
  - [x] **17.** partly done, REQ-DATA-010 and REQ-AUDIT-001 wait — Requirements still marked "waiting for a later move" in §14: REQ-DATA-010, REQ-AUDIT-001,
        REQ-XCH-009/011; arc42 §5.2/§5.3 per module; CLAUDE.md files.
- Gradle extraction, last (they move whole source trees):
  - [ ] **18.** `Q` P5-c `backend-exchange`, then **19.** P5-d `backend-bank` (§5.8; P5-a and P5-b are done): not started, §5.8 asks for the package boundary to be green for some releases first (entry above).
- Not in this branch:
  - [x] The ES-module step of F5 (§7.8) was built on its own branch, `claude/mod-f5-es-modules`, and merged into this branch on 2026-10-12 (ADR-0250, REQ-FE-035).
  - `Q` The REST API cut (§7.9, waves on `claude/api-cut` and `claude/api-cut-w1` … `w6`, the
    forced app update, T0/T1 breaks) and with it "identity's REST surface sheds the six domains" (wave 2):
    these are path changes that need the owner's release decision and an app release.
  - Phase −1 defects (§9): S-07 is done (#2299, verified in the code); C-03 is the owner's repository
    setting; the two items of §9.4 are open investigations without a code change.

### 7.7 Phase 5 — Gradle modules for exchange and bank

Right after Phases 2–4 (D-23, which drops the soak of some releases): `build-logic` convention
plugins, typed per-module settings, then `backend-exchange` and `backend-bank` (§5.8). Every other
domain stays a package.

| Step | Content |
| --- | --- |
| P5-a — **done 2026-10-11** | `build-logic` as an included build of precompiled convention plugins, the typed `basetool { }` settings, and the verification metadata for it; no module is extracted yet |
| P5-b — **done 2026-10-12** | The remaining prerequisites of §5.8: the 32 `rootProject` references of the module scripts, prefix rules in the image-reuse and SBOM scripts, JaCoCo aggregation, the context-shape test (already in place since Phase 0) |
| P5-c, P5-d | `backend-exchange`, then `backend-bank` |

*P5-a as built (2026-10-11).* The included build `build-logic/` (`pluginManagement {
includeBuild("build-logic") }`; ADR-0248, REQ-OPS-046) carries everything the root
`subprojects { plugins.withId(…) }` blocks and the repeated per-module blocks carried, as sixteen
precompiled script plugins, one per concern, applied by id from the module scripts:
`basetool.java-conventions` (toolchain 25, Lombok and JetBrains annotations, the Mockito agent, the
SEC-17 jar guard, `Test` and `JavaCompile` conventions, the `basetool { }` extension),
`checkstyle-`, `spotless-`, `coverage-` (JaCoCo) and `spotbugs-conventions` with their aggregate
`quality-conventions`, `mutation-` (PIT), `sbom-` and `licensee-conventions` with the aggregate
`supply-chain-conventions`, `dependency-management-`, `spring-library-` and `spring-app-conventions`,
`errorprone-` (Error Prone with NullAway), `javadoc-` and, for the root project only,
`repository-format-conventions` (the Spotless formats of the non-Java files). A module script now
lists its plugins and its dependencies and keeps only its own tasks; the root script falls from 620
to 72 lines (group and version, the terms-version task, OWASP). The licence tables became the class
`OssLicensePolicy`, which the frontend's `generateOssLicenses` reads in place of
`rootProject.extra`. The catalog is imported by `build-logic/settings.gradle.kts`; plugin versions
stay the catalog's `[plugins]` entries, put on the included build's classpath through their markers,
and the security floors for their transitive jars moved from four `buildscript` blocks into
`build-logic/build.gradle.kts`.
1. *Proof of no behaviour change.* An init script (not committed) dumped, for each of the seven
   projects, every task (type, enabled, group, description, dependencies, finalizers, ordering,
   input property names, outputs), every configuration (flags, extends, declared dependencies,
   attributes, constraints), the resolved `compileClasspath`, `runtimeClasspath`, annotation
   processor, test, Error Prone, SpotBugs and Mockito-agent classpaths, the `JavaCompile` options
   including Error Prone's and NullAway's package list, the `Test` JVM arguments and system
   properties, and the Checkstyle, SpotBugs, JaCoCo, PIT and Spring Boot settings, once on the
   parent commit and once on the branch. The two dumps differ in the `basetool` extension of each
   module and in the two `ossLicense*` extras the root no longer carries. `build -m` (175 tasks),
   `tasks --all` and `:backend:compileJava --dry-run` are identical; `help` and `build -m` run with the
   configuration cache reuse their entry on the second run; the regenerated CycloneDX BOMs list the
   components of the committed ones.
2. *Verification.* The one `gradle/verification-metadata.xml` covers the included build (Gradle ignores
   the metadata of an included build): 423 lines were added, all for the Kotlin DSL plugin, the Kotlin
   compiler and their own transitive jars and parent POMs; nothing was removed. A cold Gradle user home
   resolved `help`, `spotlessCheck`, every `compileJava` and `compileTestJava`, `compileE2eJava`,
   `:backend:checkstyleMain`, `:backend:spotbugsMain`, `licensee` and a test under the strict file.
3. *Around the build.* `check_sbom_coverage.py` reads the SBOM convention and each module's
   application of it instead of the root block; both image Dockerfiles copy `build-logic/`, which the
   image-reuse plan derives as a shared input (its self-test now names it); `build-logic/**` is a path
   filter of the dependency-check and sandbox-image workflows; `CODEOWNERS` lists it.
**Corrections:** (1) §5.8 and §8.4 spoke of typed per-module settings as a step still to take;
they already existed (`build-settings.properties`, REQ-OPS-037), so this step moved their reader into
the included build and added the `basetool { }` extension for the two values still passed inline
(`javaRelease` of `keycloak-spi`, the NullAway packages of the backend). (2) Precompiled scripts
cannot use the type-safe `libs.…` accessors; they read the catalog by alias (`Catalog.kt`). (3) A
precompiled script's top-level `val` belongs to the script object, so a task action or provider
lambda that captured one (the Licensee export's `rename`, the SBOM check) failed the configuration
cache where the former closure's locals had not; seven sites copy the value into a local. (4) The
root no longer declares Spotless, pitest, Licensee, SpotBugs or CycloneDX with `apply false`: a second
copy on the root classpath would load every plugin twice, so the third-party plugins are applied only by
their convention and the root applies Spotless through `repository-format-conventions`. (5) A few
transitive jars of the plugins resolve to other versions now, because the plugins no longer share one
classpath per module (`slf4j-api` is 2.0.18 in the included build where the backend and ingest had
1.7.36; `httpclient5` is 5.6.4 on the root, `commons-codec` 1.11 on the frontend); all of it is
build-time only, and the four security floors hold in the included build. (6) The plan
counted 22 cross-project `rootProject` accesses; there are 32 in three module scripts, and they are
P5-b. (7) A `.kt` file of `build-logic` is formatted by the same Spotless format as the `*.gradle.kts`
files (ktfmt, Google style), so no new Spotless task appeared. (8) The helper package is
`…basetool.conventions`, not `…basetool.build`: a directory named `build` is excluded by
`.dockerignore` (`**/build`), which failed the first image builds with unresolved references, and by
Spotless's vendored globs (`**/build/**`), which had left those files unformatted.

*P5-b as built (2026-10-12).* ADR-0249, REQ-OPS-047. A library module of an application is named
`<app>-<name>`, and the build reads the name; no module was extracted, and no task, input or
artifact of the shipped build changed.
1. *The 32 `rootProject` reads.* They were 17 in `backend/build.gradle.kts`, 11 in
   `frontend/build.gradle.kts` and 4 in `test-support/build.gradle.kts`; each is now
   `layout.settingsDirectory.file|dir(…)`, as is the one relative `../docs/exchange` of `ingest`. The
   frontend's DTO-mirror input no longer names `backend/src/main/java` but every `backend` and
   `backend-*` directory, through `backendModuleDirectories()` in `build-logic`, and
   `DtoMirrorConsistencyTest` looks for a record's twin in all of them. `check-gradle-input-paths.py`
   reads the new shape (its parse of the 83 input paths is unchanged but for the two intended ones) and
   fails a `rootProject` in any tracked `*.gradle.kts` except the root script and the settings files.
2. *Prefix rules.* `image_reuse_plan.py`: a Dockerfile `COPY` source `<app>-<name>/src/main[/…]` is an own
   input of `<app>`, its build script and settings stay shared. `check_sbom_coverage.py`: a module
   `<app>-<name>` is shipped inside `<app>` with no entry, and `<app>` must declare it as
   `implementation(project(…))`. Both scripts got self-test cases, and the SBOM script a `--selftest`
   that `repo-lint.yml` runs.
3. *JaCoCo aggregation.* `basetool.coverage-conventions` derives the application's `<app>-*` project
   dependencies from `implementation` and adds their classes (without the generated mapper and
   application classes), execution data and sources to `jacocoTestReport` and
   `jacocoTestCoverageVerification` through three resolvable, non-transitive configurations selected by
   the variants the JaCoCo and Java plugins already publish. An application with no such dependency is
   measured as before. The consequence to remember: `:backend:jacocoTestReport` then depends on the
   libraries' `test` tasks.
4. *The context-shape test.* It exists since Phase 0 (G-24, REQ-OPS-038: `ContextShapeTest` in each
   application over `test-support`'s `ContextShape`); nothing was added. It was run once on this branch:
   scheduled methods / transactional event listeners / controllers / security filter chains are
   backend 6 / 5 / 104 / 2, frontend 0 / 0 / 102 / 2, ingest 1 / 0 / 2 / 2. A library module whose
   package leaves `de.greluc.krt.profit.basetool.backend` changes a count and fails there.
5. *Proof of no behaviour change.* An init script (not committed) dumped, for every project, each task
   with its type, dependencies and finalizers, the file inputs below the repository of every `Test`
   task, `openApiGenerate`, `generateApiTypes`, `generateEdgeAdmission` and `spotbugsMain`, the JaCoCo
   class, execution-data and source sets, and the arguments of those tasks, once on the parent commit and
   once on the branch: the two dumps (326 tasks) are byte-identical. A throwaway `backend-probe` module
   that the backend depended on (not committed) showed the aggregation: the probe's classes, execution
   data and sources joined the backend's report, and the report depended on the probe's compile and `test`
   tasks. `help` and `build -m` run with the configuration cache reuse their entry on the second run.

**Corrections:** (1) The P5-b row listed the context-shape test as open; it was done in Phase 0, so this
step verified it. (2) The `rootProject` uses of the root `build.gradle.kts` (the terms-version task and
the OWASP suppression file) are the root project's own and stay; `rootDir` in `workingDir = rootDir` and
`args(rootDir…)` stays too, since it is the build's directory and no access to another project.
(3) Gradle's `jacoco-report-aggregation` plugin was rejected: it takes every project dependency of the
application, `logging-support` included, whose classes have no tests and would enter the floors at
zero. (4) Still bound to the extraction itself, and so left to P5-c: the Dockerfile lists each module's
build script and `src/main` by name; the `sandbox-images.yml` path filter, the `docker-compose.build.yml`
watch paths, `check_exchange_e2e_label.py`'s backend prefix, the `pitest.yml` module matrix and
`CODEOWNERS`; the PIT target patterns of a library; and the test classes that read a backend source file
by path (`NotificationTypeMessageCoverageTest`, the app-call lists under `backend/src/test/resources`),
which break only if their file moves.

### 7.8 Frontend track

| Step | Content |
| --- | --- |
| F0 | Guards (Phase 0.4) |
| F1 — **done 2026-10-04** | Kernel extraction: `BackendErrorMapper` (a sealed outcome type with a pattern switch), template overloads for write verbs, the eleven bypassing controllers moved onto the kernel, the `WebClient` confinement rule at zero |
| F2 — **done 2026-10-04** | Exact session allow-list in its own release (D-10): `SessionTypeAllowList.SESSION_BOUND_TYPES`, 21 exact names; the `…frontend.model.` prefix is gone, `SessionBoundTypeClosureTest` holds the list equal to the derived set in both directions. Corrections: the list lives in the security class, not in the G-16 golden file (`session-bound-types.txt` is deleted, so `-PupdateSnapshots` can no longer widen the allow-list); the admitted application classes fall from 325 to 21, not "about 20" of "about eleven" forms — the 21 are 10 flashed forms and DTOs with their nested types and enums |
| F3 — **done 2026-10-05** | Typed client per domain, small domains first (audit, notification, settings, dashboard, exchange, orgchart), then the large four; untyped `Map` relays typed in the same step |
| F4 — **done 2026-10-10** | Package-by-domain move in one pull request; route/gate snapshot byte-identical. Released only after a release carrying F2 is in production (D-10) |
| F5 — **started 2026-10-11** | Templates and assets per domain (§8.2), with the page chrome as one layout fragment. Done so far: the type-check ratchet and the layout fragment (both below). The per-domain template and asset layout is done too (below). **The ES modules, with which `bank.js` is split, are done 2026-10-12** (below); what is left of F5 is listed in that entry |

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

*F3 as built, part 1 (2026-10-05).* The six small domains call typed clients:
`audit.client.AuditBackendClient`, `notification.client.NotificationBackendClient`,
`settings.client.SettingsBackendClient`, `dashboard.client.DashboardBackendClient`,
`exchange.client.ExchangeBackendClient` and `orgchart.client.OrgChartBackendClient` — `@Service`
classes over `BackendApiClient` in the `<domain>.client` packages of §5.9, so F4 moves only the
controllers. A client owns its domain's paths and returns typed records; the announcement and the
org-chart position relays, untyped `Map`s before, are `AnnouncementDto`/`AnnouncementRequest` and
`OrgChartPosition{Create,Update}Request`/`OrgChartPositionDto`. Catalogue evictions go through the
kernel's `CatalogueCacheEviction`. `TypedBackendClientTest` confines `BackendApiClient` to the
kernel packages and the client packages (the controllers not yet moved are an exact list that
only shrinks) and checks the client rules (no `URI`/`UriBuilderFactory` parameter, no cache
annotation, `@Service`, `*BackendClient`) against planted fixtures. Every `get` and `execute(…)`
passes its runtime values as template variables — `ReadUriTemplateTest` with a floor and a
planted fixture, so with `WriteUriTemplateTest` no verb concatenates; `BackendApiClient` gained the
`Class`-typed template `get`. Each client's requests are pinned against a MockWebServer through
`BackendClientHarness`. Corrections: the "130 GET call sites" were 114 methods with a
concatenated `get` (113 seen by the F1 scanner) and 8 with a concatenated `execute(…)` when the
guard was introduced, all converted without a reviewed exception and without changing the bytes
sent (a repeated placeholder takes the next value, pinned in `BackendApiClientHappyPathTest`); the F1 scanner under-reported, because a `String` parameter without an in-class caller
folded to nothing inside a conditional, so `/api/v1/audit/" + (domain or "BANK")` read as the
literal `BANK` branch only (`AdminAuditLogPageController#auditLog`), and a builder chain longer
than ten links did not fold at all — both fixed, and a switch expression now folds too. A
controller's typed client is the one of its own domain, page-composition reads of other backend
roots included (the audit page reads the exchange registry, the settings page the squadron and
Spezialkommando lists), so a page never needs a second domain's client; the duplicated reads are
two lines each. Typed clients consolidate repeated call sites, so the write-site floor of
`WriteUriTemplateTest` and the resolved-site floor of `BackendCallExistenceTest` fall with each
domain (the four settings PUTs, written twice, are one `write` now).

*F3 as built, parts 2–4 (2026-10-05).* Fifteen more clients bring the count to 21, one per frontend
domain of the evidence table (`identity`, `orgunit`, `leadership`, `hangar`, `promotion`,
`catalogue`, `blueprint`, `personalinventory`, `materialexchange`, `mission`, `operation`, `bank`,
`joborder`, `inventory`, `refinery`); every controller calls only its own domain's client and
`TypedBackendClientTest` holds no allow-list. Each call moved verbatim (verb, template,
variables, body, response type), so the controller tests kept stubbing `BackendApiClient` around
the real client; each client's requests are pinned against a MockWebServer. The UEX override's
concatenated entity kind became an exhaustive switch over the allow-list, so `WriteUriTemplateTest`
has no reviewed exception left and no backend call concatenates for any verb. The untyped relays
became records mirrored from the backend: the identity, org-unit, Leitung, promotion, hangar,
catalogue, material-exchange, job-order, bank and mission bodies and answers. Corrections and
what stays open: the "large four" were not a fixed set — the domains moved in four stacked
parts by size; the call-site floors fell from 611 resolved / 344 write sites to 524 / 289 as the
clients merged duplicate calls; a typed relay now refuses a malformed browser value at the
frontend's own binding (400) instead of relaying it for the backend's 400, and its answer is
re-serialised from the record (additive keys, `BigDecimal` scale kept). The cached `TERMINALS`
catalogue is read as `TerminalDto` rows. Two relays stay untyped by design, not as open work: the
org-unit bank path-only writes, because the backend endpoint binds no request body (the browser
body is relayed as sent, only the answer is typed), and the refinery import's extract, a
`JsonNode` passed through unchanged because the extractor's file is its own JSON contract
(ADR-0008) that the backend validates. The blueprint
import's file part goes out as `application/json` for a `.json` file although the builder names
`application/octet-stream` — unchanged by the move, now pinned. The next step for the clients is
F4 (the controllers move next to them) and, per domain with its REST cut, the HTTP interface of
§5.9.

*F4 as built (2026-10-10).* The layer packages are gone, by script, in one pull request. 546
domain classes left `controller`, `view`, `model`, `model.dto`, `model.form` and `oss` for
`frontend.<domain>.web` (controllers, view rows, view helpers such as `BankRunningBalance`) and
`frontend.<domain>.model` (mirrors, forms, view models); 147 kernel classes left `config`,
`service`, `websocket`, `support`, `logging`, `exception`, `health`, `metrics`, `validation`, `view`
and `model` for the kernel packages of §5.9 — `kernel.backend`, `kernel.security`,
`kernel.session`, `kernel.layout`, `kernel.web`, `kernel.livesync`, `kernel.model` — plus
`kernel.observability`; 379 tests followed their classes, and a test of several domains' pages
went to the test-only package `frontend.web`. `shell` took the legal and manifest controllers,
`ScLink`, the licence report and `AndroidAppLinkProperties`; `AuditDomains` went to `audit.model`
and `GrafanaLinkProperties` to `exchange.web`, domain code that had sat in the kernel.
`kernel.model` holds `PageResponse`, `PayoutPreference`, `HandoffKind`, `StagedHandoff`,
`BackendEnumAsString`, the new marker `DtoMirror` and ten reference DTOs several domains share. The
route/gate snapshot, `BackendCallExistenceTest`, the URI-template guards, the static paths and every
bean name are unchanged; the 177 `T(…Roles)` references of 23 templates name
`kernel.security.Roles`, and `SESSION_BOUND_TYPES` names the 21 session-bound types (all domain
types) by their new names. The four DTO contract tests went red on the move, keyed on `model.dto`
as they were, and now select by `@DtoMirror` (`DtoMirrorScan`) with unchanged floors.
`DomainPackageLayoutTest` keeps every main class in a kernel package of §5.9 or a domain's `web`,
`model` or `client` package, controllers in `<domain>.web`, marked types in a model package and
every `*Dto`/`*Request`/`*Response` of a model package marked; `TypedBackendClientTest` admits
`kernel.backend`, `kernel.layout` and `kernel.security` only (before: all of `config`, `service`
and `websocket`); `KernelDomainReachTest` freezes the kernel's reaches into domain packages in a
list that may only shrink — each rule proven against a planted violation (REQ-FE-032).
BE-SIMP-10's frontend half ran in the same pull request: 410 inline fully qualified names in `main`
became imports, eight stay (the JetBrains `@NotNull` beside an imported Jakarta one).
Corrections: §5.9 has no kernel package for logging, tracing and metrics, so `kernel.observability`
was added beside the seven it draws. The kernel reached into domains in twelve places, not eleven
(`QualityTierCatalog` reads `catalogue.model.QualityTierDto` since the quality tiers landed); two
were the active-org-unit session key read from `orgunit.web.MeFrontendController`, which moved into
`kernel.web.ActiveSquadronContext` (same attribute name). The other ten are frozen rather than
inverted: §5.1 makes the kernel rank 0 and closed to domain meaning, and the backend handles such
edges the same way, a shrink-only baseline inverted one by one (the layout's
`OrgUnitMembershipOptionDto`/`SquadronDto`, the gates' `UserDto`/`RegistrationStatusDto`/`TermsStatusDto`,
`TermsDocumentDto`, `QualityTierDto`). The contract tests keyed on `model.dto` were four, not three
(`DtoMirrorConsistencyTest` read the folder). Not every reference DTO moved to the kernel:
`JobOrderReferenceDto` embeds the order's need lists and stays in `joborder.model`. FQN shortening
ran after the move in its own commit, not before it as `previous-audits.md` planned, because the
move script had to rewrite those names anyway. A session written by the release before F4 holds the
old class names in its flash maps; they resolve to no class, so the value fails at type resolution
before the allow-list is consulted — dropped on `basetool_session_value_dropped_total`, not refused
on `basetool_session_type_refused_total` — once per session, never the login (deployment.md).

*F5 type-check ratchet as built (2026-10-11).* Design: `frontend/typecheck-ratchet.json` assigns
every script of `static/js` to exactly one domain — the 21 frontend domains of §5.9 that have
scripts (`audit` … `settings`) plus `kernel` for the shared infrastructure, 22 names in all — and
lists the domains that are *migrated*. Every domain of the manifest must be migrated (a new domain
cannot enter unchecked), and a migrated domain holds only scripts whose leading comments carry
`// @ts-check`; the scan includes subfolders of `static/js`, `vendor/` excepted. `frontend/scripts/typecheck-ratchet.mjs` is the guard and
`typecheck-ratchet.test.mjs` its test, wired as `:frontend:testTypecheckRatchet` into `check` (zero
dependencies, like `testTrustedTypesJs`). It fails on: a script in no domain, in two, or a listed
script that does not exist; an unchecked script in a migrated domain; a domain dropped from the
migrated list (`MIGRATED_FLOOR`, which only grows); fewer checked scripts than
`CHECKED_FILES_FLOOR`; `@ts-nocheck`, `@ts-ignore` or `@ts-expect-error` anywhere; a `tsconfig.json`
that excludes anything beyond vendor, `node_modules` and `build`, that stops including the scripts,
or that loses `strict`. The test proves each rule on planted trees (among them an unchecked
file in a migrated domain, a stray file, a stray file in a subfolder, a new unmigrated domain, a
dropped domain, each suppression pragma) and runs the real tree. *Review corrections (same day):*
the first version scanned only the top level of `static/js` although `tsconfig.json` includes
`static/js/**`, so a script in a subfolder escaped every rule, and a new domain missing from
`"migrated"` passed; both are closed and tested. Numbers: before, 113 scripts, 65 checked (58 %), 48 unchecked, 3 domains
(`exchange`, `leadership`, `notification`) fully checked; after, 124 scripts, 124 checked, all 22
domains migrated. The 48 scripts carried 2,393 type errors under `checkJs` (the three giants 538,
221 and 185 of them); all are fixed by casts at the query, `@type`/`@param` JSDoc, and null guards,
with no `any` element handle and no suppression. `types/*.d.ts` gained the `Window` members the
scripts read, `KrtLiveSyncHandlers` and corrected bootstrap dictionaries; `safeSameOriginUrl` became
non-optional because `head.html` loads it on every page.
*The three giants* were split first, as byte-exact cuts at top-level statement boundaries (each
proven by concatenating the parts against `git show` of the original) into parts that load by
consecutive `defer` tags in the original order, the first part keeping the file name:
`mission-detail.js` (3,283 lines) into 5 parts of 541 to 918 lines, `orders-detail.js`
(2,650) into 6 parts of 293 to 944 lines, `bank.js` (2,211) into 3 parts, loaded by all nine bank
pages. Corrections: **there are no domain folders yet** — the 113 scripts sat in one flat folder, and
moving them would rewrite 113 template references and ~35 tests that F5's template track is
changing at the same time, so the "folder" of the ratchet is the manifest's domain name today and
the asset move turns each name into a folder (the keys are chosen to be the folder names). `bank.js`
keeps 2,081 of its lines in one immediately invoked function whose sections call each other (modal
priming, previews, movement rows); cutting inside it would have meant a shared object and about 60
rewritten call sites, a refactor and not a split, so only its four trailing immediately invoked functions left it —
the 20 % claim holds for the folder, not for that file. The owner decided on 2026-10-11 to leave the rest of `bank.js` as it is and to split it with the ES-module step, behind the import map, where its sections get explicit imports instead of a shared object. Every part lives in its domain, so the
parts of a giant add to the domain's list in the same change. A load-time throw in one part now
skips only the rest of that part, not the rest of the former file.

*F5 layout fragment as built (2026-10-11).* `fragments/layout :: page` is the one place that
composes a page: the `<html>` element, `fragments/head`, `fragments/sidebar` and `fragments/header`,
the page's content, `fragments/toast` and the page's tail, in that order (REQ-FE-033). A page hands
it `pageTitle`, `pageLinks`, `pageContent` and `pageTail` as `th:fragment` blocks of its own file and
names its variant as a parameter: `pageToast`, `pageBare` (no sidebar and header: the five error
pages, pending approval, terms acceptance) and `pageShowModal` (the hangar's body attribute). It is
native Thymeleaf with no signature, like `modal-wrapper`; no dependency was added. 90 of the 91
non-fragment templates were converted, in five stacked commits, 83 with the app shell and 7 without
it; `organisation/unit-detail.html` stays, because it has no head and no chrome (it is a roster
fragment library). The 90 pages and the new layout together lost 137 lines. Before the first conversion
`PageChromeSnapshotTest` captured the chrome of every page into a golden file (`<html>` tag, head,
`<body>` tag and the order of chrome, content, toast and tail) by rendering each page through the
real fragments for an administrator with the page's own markup replaced by markers
(`ChromeNeutraliser`, which reads both the old and the new shape of a page); it stayed unchanged
through every commit, it fails on six planted page changes and three planted layout changes, and
`PageLayoutCompositionTest` keeps any page from calling the chrome fragments again. Corrections: the
plan's "83 pages copy the header in five variants" counted the pages with the app shell; the head,
sidebar, header and toast were already fragments, so what the pages copied was the skeleton — the
`<html>` and `<body>` tags and four fragment calls, about a dozen lines each — and the "five
variants" were really the toast (39 pages), the shell-less pages (7), one body attribute (the
hangar), four pages that passed an empty `additionalLinks` and a cosmetic `xmlns:sec` on some
`<html>` tags; the fan-kit band is page content, not chrome. "Chrome changes once" was already true
of the markup; what changes once now is the composition, that is the order, the toast placement and
the body attributes. A snapshot of the fully rendered pages was not possible, because most pages
need a model, so the oracle pins the chrome and the position of the page's own parts, and the
content and tail blocks were moved by script without a byte changed inside them (the full
frontend suite, 3,957 tests with the existing render and MVC tests of the pages, is the check on that). The frontend's Spring-context
budget rose from 21 to 22, the snapshot test's template resolver and controller being a context of
their own (REQ-OPS-041). Open in F5: the ES modules.

*F5 templates and assets per domain as built (2026-10-11).* Section 5.9 only said "per-domain folders", so the layout was decided here: the folder is the domain and carries the Java package name (`kernel`, `shell`, 21 domains), under each asset root: `templates/<domain>/<old path>` (the old `admin/`, `fragments/` and `organisation/` sub-folders stay below the domain, which keeps `fragments/bank-account-detail` and the page of the same name apart), `static/js/<domain>/<name>.js` (the domain `typecheck-ratchet.json` already listed), `static/css/<domain>/<name>.css` for area stylesheets and `static/css/pages/<domain>/<page>.css` for page stylesheets (REQ-OPS-038 and `TemplateCommentHygieneTest` already assumed `css/pages/<domain>/`). Page templates follow the controller that renders them, a fragment follows the domain that includes it (the ones every domain uses, and `styles.css`, are `kernel`), an area stylesheet follows its owner. Moved with `git mv`, in five stacked commits by group of domains: 124 templates, 124 scripts and 88 stylesheets (kernel 16/24/2, shell 6/0/8, settings 1/1/1, bank 16/9/8, audit 1/1/1, exchange 3/3/3, dashboard 3/2/2, orgchart 2/1/1, notification 3/3/2, hangar 1/2/2, catalogue 15/13/13, blueprint 5/3/4, personalinventory 2/5/2, inventory 8/10/6, materialexchange 4/3/2, joborder 9/12/6, refinery 3/4/4, mission 2/7/1, operation 2/3/2, promotion 5/4/6, identity 12/10/7, leadership 1/1/1, orgunit 4/3/4; templates/scripts/stylesheets). The mapping and the rewrite of every reference (about 700 th:replace paths, 290 asset links, the controllers' view names, Gradle input paths, ESLint per-file settings, node and Java tests, docs) were a script over the ratchet manifest; no file's content changed other than a path (and the three font urls of `styles.css`, now `../../fonts/`). `AssetReferenceResolutionTest` (every `th:src`/`th:href` resolves, every script and stylesheet is linked, written first on the unchanged tree) and `StaticResourceHandlerMappingTest` (every linked asset resolves to a content-hashed URL and is served) stayed green through every commit; `AssetLayoutTest` fails on a flat file, proven with planted trees; the ratchet fails a script listed under another domain than its folder. The route and gate snapshot did not change; the chrome golden changed in its page keys and asset paths only. Corrections: five templates stay flat, because Spring Boot's error view resolver finds them by name (`error.html`, `error/403|404|500.html`, and `error/error.html` beside them); six area stylesheets and two script sets have a user in another domain (`bank.css` also on the audit log, `legal.css` on the terms, `materialboerse.css` on the inventory pages, `organisation-unit-detail.css` on the leadership page, `personal-inventory.css` on the blueprint pages, `promotion-admin.css` on the price page; the blueprint pages load the personal-inventory scripts and the kernel chrome loads `notifications.js`), each stays in its owner's folder and is the allow-list of the later boundary test; the served URLs changed (`/js/bank/bank.js`), the content hash does not depend on them. Not done here, by instruction: ES modules, the import map, the `bank.js` split, the boundary text test, and any E2E run.

*F5 ES modules as built (2026-10-12).* ADR-0250, REQ-FE-035. **The preconditions held**, so nothing was forced: every inline script is a data bootstrap, the CSP is `script-src 'nonce-…' 'strict-dynamic'` (a nonced module's static imports and a nonced inline import map are admitted), the assets are served at content-hashed URLs from one `ResourceUrlProvider`, and the cross-file name coupling of the 140 scripts was four small clusters, found with a scope analysis of every file (63 sites: `krtAutocomplete` in 5 consumers, the inventory note dialog in 2, the six parts of the order detail page, and `window.filterTable`, which was published explicitly already). Built: (1) the `moduleImportMap` bean renders, in the head before the first module script, an inline `<script type="importmap">` with the request nonce for the page being rendered: the page template, the layout and the fragments they include by a literal reference are scanned for `type="module"` scripts, their static imports are followed, and each is mapped from its absolute path to the hashed URL the `@{…}` links use, so an entry script and an import of one module are one instance and a changed module is a new URL; (2) a script is a module unless `frontend/module-ratchet.json` lists it (only `kernel/krt-client-error.js`, which must stay synchronous and first; the list only shrinks, `ModuleScriptsTest`); (3) the boundary: ESLint `no-restricted-imports` per domain folder (own domain and kernel, absolute `/js/…` paths only, no dynamic `import()`; no dependency was added), the same in the Java test with a cycle check, and `tsconfig.json` `paths` so `tsc` follows the imports; (4) 139 of 140 scripts converted domain by domain with `type="module"` in place of `defer`, 153 script tags in 82 templates; (5) `bank.js` (2,081 lines in one function) became an entry plus 16 modules by responsibility (messages, form errors, controls, live sync, previews, counterparty, request rows, movement rows, confirm modal, modal priming, form submit, grant matrix, account type, downloads, type-to-confirm, account list), each registering its document listeners in an `init…` function that the entry calls in the order the one function registered them; (6) the explicit imports that replace globals: `krtAutocomplete` (kernel), the inventory note dialog (6 names), and the six order detail parts (63 names, the three mutable `let`s behind one exported `resetInventoryCache`, written once). Verification: a recorded comparison of the document listener registrations (type, normalised handler body, order) and the live-sync receivers of the split `bank.js` against the original is identical (28 records); `lintJs`, `lintProbeJs`, `lintCss`, `lintHtml`, `prettierCheck`, `typecheckJs`, the node tests and the template, asset, import-map and page tests are green; two rendered pages (`org-unit-bank`, `inventory-admin`) loaded in a browser under the real CSP, with Trusted Types reporting, without a violation or script error, and the bank modal priming and request-type sync worked. Type-check ratchet: files carrying `// @ts-check` 124 → 140 (the floor moved with them), all 22 domains still migrated; ESLint findings 0 before and after. Corrections: (a) the plan's "about 60 rewritten call sites and a shared object" for `bank.js` did not materialise: the sections called each other along a DAG, so imports were enough and no shared state was needed; (b) `#execInfo.templateName` names the fragment being processed (`kernel/fragments/head`), the page is `#execInfo.processedTemplateName`; and a `@bean` expression is "external access" that Thymeleaf forbids in `th:utext`, so the head evaluates it in `th:with`; (c) 62 of the 124 scripts then existing had no `'use strict'` anywhere and run strictly now; ESLint as a module and the type check found nothing, the rest is for the end-to-end suite; (d) the node tests that run `krt-fetch.js`, `krt-live-sync.js` and `krt-html.js` in a `vm` context needed no change, because a module without `import`/`export` is also a valid script. **What the first pass left open is closed in the next entry.**

*F5 ES modules, the open items closed (2026-10-12).* (1) **The kernel API is imported**, in commits per API: the toasts and the confirm dialog (226 reads in 57 modules), `safeSameOriginUrl`, `krtScuInput`, `resetUnsavedChanges` and `krtSyncDatetimeSplitGroup` (104 reads), `krtModal` (158 in 46), `krtFetch` with `krtCsrf`, `krtReauth` and `krtTermsGate` (532 in 100), `krtLiveSync` and `krtEvents` (467 in 49), `krtHtml` (16 modules; it was a bare global and an ESLint global), the filter panel, filter chips, overflow menu and combobox enhancer (64 in 16), `krtI18nText` (200 in 33, through a one-line module facade over the classic beacon, which owns it), and the Herkunft picker, the Lager factory and the yield badge inside their domains (38 in 5). About 1,800 reads; the drawer and the palette, which are optional to each other, meet in `krt-chrome.js`, the combobox sources in `krt-combobox-sources.js`. A kernel name keeps its `window` property only where a reader outside the module graph needs it, recorded in ADR-0250: `krtFetch`, `krtCsrf`, `krtLiveSync`, `krtModal`, `krtSearchableSelect`, `krtHtml`, `krtComboboxRemoteSources` (Playwright sources and node tests), `krtEvents` (the head stub), `krtI18nText` (the beacon). The node harnesses that run `krt-fetch.js`, `krt-live-sync.js` and `krt-html.js` follow the new imports through `scripts/flatten-module.mjs` (a module graph as one classic script, with stand-ins for the two DOM-touching modules), and the chrome golden file masks the import map, whose hashed URLs would otherwise move with every script edit. (2) **IIFE wrappers:** 48 were removed by a script that proved each neutral (no `return`, `await`, `this`, `arguments` or `new.target` of its own, no colliding top-level name, and the same set of global names read before and after), and every `'use strict'` but the beacon's went; **49 stay**, 46 because they open with an early `return` (a module has no top-level `return`) and 3 because two wrappers of one file declare the same names (`bank-grants-chart-prefs`, `bank-layout-prefs`, `mission-detail`). The message of the unwrapping commit says 41 and 7; the count is 45 and 3 for that commit and 46 and 3 now. (3) **`modulepreload` was measured and not added.** Over HTTP/1.1 with 50 ms per script, the bank page (28 module scripts, a graph five levels deep) reached `DOMContentLoaded` in 518, 547 and 1,035 ms (first, cold load) with preload links carrying the nonce and 516, 517 and 570 ms without, because Chromium's six connections per host, not the depth, set the time; without the nonce on the link the preload is blocked and every module is fetched twice (971 ms), and a preload before the import map makes the browser ignore the map. HTTP/2, where depth would matter on a cold cache, could not be measured: the browser pane does not trust a local certificate. (4) **A script under its plain URL answers `no-cache`**, a content-hashed name `immutable`: two resource handlers over one resource chain, the hashed pattern being the more specific (`WebMvcConfig`, `StaticResourceHandlerMappingTest`); no edge-proxy rule touches `/js`. (5) **`moduleDetection: force`** in `tsconfig.json` reports nothing: every script is checked as a module. Corrections: (a) a `modulepreload` link or any other module fetch placed before the import map is not remapped; (b) a bare `krtHtml` needed its ESLint global and `declare const` removed with the import, and the ban test's planted source imports it; (c) the combined load order of the head modules changed by one edge (`common-handlers` now evaluates after `krt-modal`, `krt-fetch` after `toast`), checked as touching different event types or only making a collaborator available earlier. Type-check ratchet: files carrying `// @ts-check` 124 → 143 over the whole of F5's ES-module step (the floor moved with them); ESLint findings 0 and `tsc` errors 0 throughout. **What this entry left is closed in the next one.**

*F5 ES modules, the last items (2026-10-12).* (1) **The 49 remaining wrappers are named functions.** A module has no top-level `return`, so the 46 scripts that open with an early-return guard and the 3 files that paired names (`bank-grants-chart-prefs`, `bank-layout-prefs`, `mission-detail`) became `function initBankGrants() { … }` and one call at the same place; neutral by construction (a plain call of a strict function sees the same `this` and `arguments` as the immediately invoked one, nothing is hoisted that was not, the name is checked free in the file, two functions of one file keep separate scopes). Three commits by domain. No script but the classic beacon is wrapped any more. (2) **The 23 page seams are hook registries** (one commit): `mission/mission-hooks.js` (9: write, refresh, notify, modalOpen, modalClose, segSet, localizeDates, openEditCrewModal, presence), `joborder/joborder-hooks.js` (5), `operation/operation-hooks.js` (3), `inventory/inventory-hooks.js` (1) and `kernel/krt-hooks.js` (filterTable, materialRelease, materialRequest: the catalogue pages offer the table filter to the kernel's `common-handlers.js`, the Materialbörse dialogs to the Lager page of another domain). 182 window reads and writes in 26 modules; `materialRowToDelete` became a local variable, `krtRefreshMembersResults` a direct call, the unread `window.MissionPresence` is gone. A registry is a leaf module with no effect, so a publisher assigns where it assigned the window property and a reader reads where it read it: every publication and read has the old timing. **Evaluation order is unchanged, verified:** a script reads the import statements of every module at the revision before and after and compares the graph with the registries left out: identical for all 143 modules (the only differences are the 5 new files); because a module's position in the evaluation order is a function of the tag order and that graph, the order of the page parts is the old tag order. `window.krtMaterialRelease` and `window.krtMaterialRequest` stay published next to the registry members, since five and one Playwright sources evaluate them in the page. Corrections: the first pass listed the seams as "late-bound on purpose, an import would reorder the parts"; that holds for imports between the parts, which the registries avoid, not for a registry. Type-check ratchet: files carrying `// @ts-check` 143 → 148 (the five registries), ESLint findings 0, `tsc` errors 0. Browser check under the real CSP on the rendered mission detail, order detail, order list, operation detail, Lager (own and global), org-unit bank, catalogue material admin and home pages: no script error or violation; the participant dialog opens through `missionHooks.modalOpen`, a second material row of the order is removed through `confirmRemoveMaterial`, the catalogue table filter runs through `krtHooks.filterTable`. Not browser-checked: the Materialbörse page, the operation list and the member list (no render test produces them); their changes are the same mechanical substitution and the Java tests that read their sources pass.

### 7.9 REST API track

**Delivery decided 2026-10-04 (D-24):** the waves below are built and merged one by one, but ship
together as one backend release and one app release, so the paragraph's per-wave release cadence
is replaced by a single cut. Each wave is one domain's cut ([appendix](modularisation/rest-api-cut.md)), shipped together with the
backend and frontend change of that domain in one release (frontend and backend deploy in one
restart window), with a new app release published first and the minimum version raised once the
re-cut release is verified healthy — or, once the floor is release-bound (D-11), by the release itself.
The executable wave plan (counts re-measured on 2026-10-10, the machine-readable map, the
integration branch `claude/api-cut`, the shape changes, the forced-update mechanics, the owner
questions and the app work split) is [`modularisation/api-cut-waves.md`](modularisation/api-cut-waves.md).
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
| **Exhaustive enum switches** — **done 2026-10-10** (nine dead `default` arms became `case null`; the deliberate subsets keep theirs; ADR-0238 *Implementation*). *Correction:* the 20 statements of the audit are 51 `default` arms across `main` today, nine of them dead. Checkstyle's `MissingSwitchDefault` forces a `default` into all 20 switch statements; on nine of them every constant is already covered, so the `default` hides the next constant. Write them as switch expressions or with `case null ->`, which javac 25 checks for exhaustiveness and Checkstyle 14.3.0 accepts (probed). Policy: no `default` on a switch over a project enum unless it handles a deliberate subset (decided, D-15) | A new `BankAccountType`, `OrgUnitKind` or `SelectorKind` breaks the build at every decision site instead of shipping a 400, an exception or a silent no-op | `case null ->` is an unfamiliar idiom | The compiler; existing service tests | S |
| **Enum predicates instead of `==` chains** — **done 2026-10-10** for the multi-constant rules: `OrgUnitKind.isTenantUnit()` (three sites) and `FinanceType.signed` (payout totals). *Correction:* of the 43 `OrgUnitKind` comparisons only three are chains; the rest compare one constant, which a predicate would only rename. `OrgUnitKind` is compared 43 times in 19 files; add behaviour on the enum as `switch (this)` without `default` (precedent `OperationStatus.canTransitionTo`), with a `values()` test per predicate; the same for `FinanceType` in payout arithmetic | Tenancy rules stated once, as the kernel's API | Each site's current fail-open or fail-closed behaviour must be kept exactly — one predicate per rule, never two rules merged | Access-gate and scope tests | M |
| **Sealed types where they are legal** — **done 2026-10-10**: the blueprint and stock write loops switch over `Planned` (the ship loop already did); the resolve status, the journal labels and the undo refresh name their last constant; `ExchangeResource` carries the mass-change name, the write capability and the metric tag, and the mass-change service maps the request's name to it before the capability is chosen (`ExchangeMassChangeServiceResourceTest`); the ship operation names `remove` and refuses any other value instead of defaulting to a removal. Exhaustive `switch` over the exchange's private sealed `Planned` types (two are read with `if/else` without `else`, so a new variant is silently dropped); name the last constant instead of `default` in three exchange switches; the exchange's resource and operation vocabulary as one enum mapped at the boundary (the mass-change capability is chosen from a string with `default -> HANGAR_WRITE` today, which Bean Validation happens to shield) | No silently dropped external write; no path can default to a capability | Must keep the external contract byte-identical | Exchange contract tests; a test that an unknown value never reaches the capability choice | S |
| **Never seal across packages or entities.** Sealed hierarchies stay inside one package (class-path rule) and never cover JPA entities (a Hibernate proxy cannot subclass a sealed class) | Avoids a refactor dead end | — | javac | — |
| **Unnamed variables `_`** — **done 2026-10-10** in `main` (225 sites: 114 catch, 110 lambda, 1 pattern; descriptive names kept; ADR-0214 amendment) (final since 22) for 107 unused lambda parameters, meaningless catch parameters and unused pattern components; empty catches too, with the ADR-0214 amendment and the Checkstyle `EmptyCatchBlock` pattern change this needs (decided, D-14); descriptive names stay where the name states intent; not in keycloak-spi (Java 21 bytecode) | The compiler forbids accidental use | About 260 edits in `main` | Compile and Checkstyle | S–M |
| **`ScopedValue`** — **done 2026-10-10** (final in 25) for the backend's `ChangeSource.ON_BEHALF` `ThreadLocal`, which attributes exchange writes in the change feed; the frontend holders stay `ThreadLocal` (ADR-0223 decision 4) | A binding cannot leak into a reused thread | ADR-0223 amendment | `ChangeSourceTransactionManager` integration test | S |
| **Records** — **done 2026-10-10** (`AnnouncementRequest`, `UserAttributesRequest`, `UserDescriptionRequest`, `ShipTypeMatcher.TokenView`; their frontend mirrors are now paired by `DtoMirrorConsistencyTest`) — for the last three `@Data` request classes and one hand-written carrier; defensive `List.copyOf` only for records that become cached, shared or module-API values; redaction DTOs keep their canonical constructors | Immutability where values cross a boundary | `List.copyOf` rejects `null` elements | Contract tests; `toString` ratchet (G-22) | S |
| **Small idioms** — **done 2026-10-10**, the job-order, refinery and Materialbörse code with the job-order move (2 more `Math.clamp`, 9 more `@Query` text blocks; the two Materialbörse `max(0, min(offered, stock))` stay, because their bounds can cross); elsewhere (`Math.clamp` 9 sites, `getFirst()` 9, `Environment.matchesProfiles` 4, 27 `@Query` text blocks, 6 `isPresent() && get()` pairs as `Optional.filter`; a query that mixes literals with a constant or splits a token keeps its concatenation; `isPresent()` used as a plain boolean stays). *Correction:* of the 68 `isPresent()` calls only these few are followed by `get()`: `Math.clamp` for 11 constant bounds, `getFirst()` in frontend and ingest, `Environment.matchesProfiles`, `Optional` chains instead of `isPresent()`/`get()`, text blocks for the 47 concatenated `@Query` strings and test JSON; keep `trim()`/`strip()` and `Collections.unmodifiable*` where they are deliberate | Consistency with the 90 % that already uses the modern form | Diff churn — batch into files the refactor touches anyway | Compile, tests | S |
| **Do not adopt** Markdown documentation comments (`///`, JEP 467): Checkstyle 14.3.0 does not treat them as Javadoc; gatherers: no loop here is clearer as one | — | — | — | — |
| **ADR-0223 corrections** — **done 2026-10-10** (the corrections were recorded in the ADR on 2026-10-02; the missing gate is `scripts/check-final-java-only.py`, REQ-OPS-043): JDK 26 does add a final library feature (JEP 517, HTTP/3 for the HTTP client); JEP 510 (KDF) is final in 25; Checkstyle already enforces the module-import and compact-source bans in `main` (not in `test`/`e2e`); there are 42 `super(…)` calls, not 34; a `--enable-preview` gate is still missing | The decision record stays authoritative | — | Review | S |

**Nullness** — **started 2026-10-10** (ADR-0237 *Implementation*: the backend's 18 module `api` packages are checked; seven annotations were wrong or missing; the whole `joborder`, `refinery` and `materialexchange` modules followed with the job-order move, `mission` with its own, the `inventory` tree with P3-5e and `hangar` with P3-6). Error Prone 2.50.0 with NullAway 0.14.2 runs on JDK 25 and accepts the JetBrains
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
| **Type-check ratchet per domain folder** — **done 2026-10-11** (§7.8, F5; 124 of 124 scripts checked, 22 of 22 domains migrated; the domain is a manifest name until the asset move makes it a folder); the three largest scripts (mission detail, bank, order detail — 20 % of all JavaScript) split before they are checked (mission detail into 5 parts, order detail into 6, bank into 3 with its first function still 2,081 lines) | Null safety reaches the files that change most | Cast churn in DOM-heavy code | A folder is "migrated" only when fully checked; `:frontend:testTypecheckRatchet`, proven on planted trees |
| **Finish ADR-0069**: the 70 inline script blocks (1,860 lines, never linted) move into modules; one page puts eleven `[[#{…}]]` markers into a script without `th:inline` (a likely i18n defect). **Done 2026-10-04** (ADR-0069 amendment): on that day 67 blocks with 1,645 lines remained, 55 of them already pure data bootstraps; the eleven blocks with code moved into their modules (one new, `org-unit-bank.js`), five empty error-page blocks were deleted. Every inline script is now a `th:inline="javascript"` bootstrap of literals, checked against a grammar by `InlineScriptDataOnlyTest` (planted fixture, floors of 100 templates / 55 blocks); the head's `krtEvents` stub is its one listed exception. *Correction:* the eleven-marker defect had already been fixed (B-04); what was still broken was the `'[[#{…}]]'` form inside inlined string literals on the mission-data and special-commands admin pages, which rendered the texts in quotes — fixed by the move | Lintable, checkable code | Timing of inline versus module code | `InlineScriptLoadOrderTest`, `InlineScriptDataOnlyTest`, E2E |
| **One read path**: `krtFetch.get`/`getJson` with re-authentication, the terms gate, refusal of redirected or non-JSON answers and `AbortController`; then forbid `fetch(` outside the transport and ban `XMLHttpRequest`. **Done 2026-10-04** (REQ-FE-031): 61 raw reads in 33 scripts plus one inline read in `members.html` migrated, `swap` reads through `get` too; ESLint bans `fetch`/`XMLHttpRequest`, and `BackgroundReadGateContractTest` pins the same for templates, which ESLint never sees | 56 raw reads behave the same way on session loss | Pickers that silently emptied now send the user to the login — the intended contract | A new E2E for a picker after session loss |
| **Trusted Types**, report-only first through the existing `csp_violation` beacon, then enforced: two named policies, a tagged-template HTML builder, no default policy. **Done 2026-10-04 except the production switch** (ADR-0239): `krt-html` behind the `krtHtml` builder, `krt-fragment` inside `krt-fetch.js`; 42 builder sites, 26 clears and one `DOMParser` read migrated, `escape-html.js` removed; ESLint bans every sink and policy outside the two helpers; the report-only header is the default of `APP_SECURITY_TRUSTED_TYPES` (`report` / `enforce`), with the gauge `basetool_trusted_types_mode`. **The only remaining step** is switching production to `enforce`, a production configuration change the owner approves after a quiet period (`deployment.md` → *Trusted Types: report, then enforce*) | DOM-XSS becomes a browser-enforced property | Every future sink must use the helpers; under enforcement even `innerHTML = ''` throws | A violation collector in the dialog page-walk E2E (proven by a planted violation), `:frontend:testEslintBans`, `:frontend:testTrustedTypesJs`, `TrustedTypesModeTest` |
| **CSS** — **done 2026-10-04** (ADR-0243): lint the page stylesheets with the standard config (74 by then, not 54; 66 findings fixed without visual change); a custom-property existence test (it finds an undefined token that makes the material-demand search header transparent; B-03 was already fixed in Phase −1, and the test now also reads templates and scripts); `color-mix()` for alpha variants (120 hand-written token copies replaced, `ColourTokenCopyTest`); a z-index scale (18 `--z-*` tokens with the numbers they replaced, `ZIndexScaleTest`); container queries and nesting only where a design decision asks for them (none asked) | One lint standard; tokens as the single colour source | Visual changes | `CascadeLayerOrderTest`, screenshot review; a computed-style comparison of every touched declaration against `main` |
| **One layout fragment** carrying head, sidebar, header and toast (83 pages copy the header in five variants) — native Thymeleaf, no Layout Dialect dependency. **Done 2026-10-11** (REQ-FE-033, §7.8): `fragments/layout :: page`, 90 templates converted, one left (no chrome); *Correction:* what the pages copied was the skeleton around fragments that already existed, and the variants are parameters | Chrome changes once | Touches 83–90 templates | `PageChromeSnapshotTest` (golden file, planted changes), `PageLayoutCompositionTest`, render tests, E2E |
| **Templates and assets per domain** — **done 2026-10-11** (REQ-FE-034, §7.8, F5): `templates/<domain>/`, `static/js/<domain>/`, `static/css/<domain>/`, `static/css/pages/<domain>/`; 124 templates, 124 scripts and 88 stylesheets moved with their references | A domain's page, script and stylesheet sit together; the ratchet's domain names are the folders | Touches every template reference; served asset URLs change | `AssetLayoutTest`, `AssetReferenceResolutionTest`, `StaticResourceHandlerMappingTest`, `ViewNameResolutionTest`, the ratchet |
| **ES modules** only after the global-scope and inline-script work, and only behind a server-rendered import map (unversioned asset URLs are cached immutably for a year). **Done 2026-10-12** (ADR-0250, REQ-FE-035, §7.8, F5): 147 of 148 scripts are modules; the head renders each page's import map of content-hashed URLs; imports are absolute, static and bounded to the own domain and the kernel; `bank.js` is split into 16 modules; the kernel API is imported, a script under its plain URL is revalidated, `moduleDetection` is `force`. `modulepreload` measured, not added; the 49 early-return wrappers are named functions and the 23 page seams hook registries | Explicit dependencies, lint-enforced boundaries | Import-map and nonce plumbing | Every specifier must be in the map: `ModuleScriptsTest`, `ModuleImportMapTest`, `StaticResourceHandlerMappingTest`, `:frontend:testEslintBans`, `:frontend:lintJs` |
| **No bundler** | — | A compromised bundler release would be site-wide script injection the nonce CSP cannot stop; the served file would no longer be the checked file (ADR-0125) | — |

### 8.3 SQL, persistence, platform

- **PostgreSQL 18**: `RETURNING OLD/NEW` where a native bulk update needs the old value for its audit
  row (**checked 2026-10-10, nothing to adopt**: the backend has no native bulk `UPDATE`; its bulk
  updates are JPQL and its native statements are retention `DELETE`s that write no audit row); review multicolumn indexes against skip scan with `EXPLAIN`; temporal `WITHOUT OVERLAPS` only
  with the leadership redesign (needs `btree_gist`). **No `uuidv7` ids** for anything whose id
  appears in a URL or an export without a privacy decision — they reveal creation times.
- **Hibernate 7.4**: do not adopt `@Audited` (it would keep full row history, personal data included,
  outside the GDPR deletion flow) or Jakarta Data stateless repositories (they do not fit the
  `@Version` concurrency rules); write a characterisation test before touching
  `fail_on_pagination_over_collection_fetch` (**done 2026-10-10**: `PagedFindersNoCollectionFetchTest`
  pins both halves, the shape the gate refuses and the shape it lets through).
- **Spring Framework 7**: `@ConcurrencyLimit` for the backend's outbound fan-out to UEX and SC Wiki,
  which virtual threads no longer bound; keep the find-or-create retry explicit (**done 2026-10-10**,
  REQ-SEC-081: 4 for UEX, 2 for SC Wiki, enabled by `AsyncConfig`; nothing else changed). Spring's built-in API
  versioning adds nothing under a hard cut; its standard deprecation headers only if
  `@ApiDeprecation` is ever used again.
- **Spring Boot 4.1**: an `InetAddressFilter` restricting the external-integration HTTP clients (UEX,
  SC Wiki, Discord) to external addresses — a defence against redirects into internal networks; never
  on the internal clients (**done 2026-10-10**, REQ-SEC-081, ADR-0204 amendment 3: UEX and SC Wiki use
  the external builder; the backend has no Discord client, that federation lives in `keycloak-spi`).
- **Jackson 3** reads unknown JSON properties tolerantly by default. That stays: the frontend and
  backend deploy together and are checked by the contract tests, and the exchange's tolerant reading
  is part of its frozen behaviour. The policy is written into the API conventions (**done 2026-10-10**, REQ-API-022,
  `JacksonTolerantReadingTest`).

### 8.4 Gradle and CI

Typed per-module build settings now (done: `build-settings.properties` and the `basetool { }`
extension); an included `build-logic` with convention plugins before the
first Gradle extraction (done 2026-10-11, §7.7 P5-a; it also prepares Gradle's isolated projects); `java-test-fixtures` for
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
| S-01 | The pinned Keycloak trust client has **no connect or read timeout** (`backend/…/identity/api/KeycloakTrustSupport.java`), and in production it backs both the Keycloak Admin API client and the internal JWKS decoder — which also discards Spring Security's own 30 s / 30 s defaults, set because the key fetch runs under a lock. If Keycloak accepts a connection and never answers, every token validation after the five-minute key cache fails after 15 s for as long as it hangs; `ConnectedAppsService.disconnectClient` holds a database connection per hung attempt | Availability of authentication, medium-low (not attacker-triggerable) | Give the pinned factory the connect and read timeouts of `RestClientConfig` (5 s / 30 s), HTTP/1.1, for both uses; the same in ingest's copy | A test asserting the factory's timeouts; `SecurityConfigInternalJwksDecoderTest`, `KeycloakServiceTest` |
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
| Trusted Types | Adopt, report-only first — **report-only since 2026-10-04**; enforcing in production waits for the owner | No dependency; the existing CSP-violation beacon provides the report-only phase |
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
  was open (done 2026-10-10: a chain of family classes under the one advice). Its diagnosis — "the architecture is sound, the debt is size inside
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
| O-11 | `MissionParticipant.orgUnits` is the one eager cross-domain collection | **Decided 2026-10-01 as recommended — D-20;** done 2026-10-11 (§7.6) |
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
  **Status 2026-10-10:** done — REQ-API-002 (the enforced rules, in place of the convention),
  REQ-API-007 (the annotation sentence corrected to what the build requires), REQ-DATA-001 (one
  location, one sequence; it keeps the plan's "REQ-DATA" row). Waiting for a later move — REQ-DATA-010
  (the fragment per aggregate moves into each module with its repository, Phase 3), REQ-AUDIT-001
  (the per-command contract, P3-11), REQ-XCH-009/011 (they name the domain APIs the exchange calls,
  which exist when blueprint, inventory and hangar publish theirs, Phase 3).
  **Status 2026-10-12 (umbrella step 17):** the three modules publish their entities and repositories in `api` since steps 9, 10 and 11a; that did not change what REQ-XCH-009/011 say, only the
  source paths in the specs, which the text scans rewrote. REQ-DATA-010 stays waiting: `ScopeSpecifications` still holds the six scope fragments in `scope.api` (§5.4, step 12). REQ-AUDIT-001 stays waiting for P3-11,
  which the plan does not define beyond its name (question to the owner).
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
