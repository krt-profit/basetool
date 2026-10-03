# 8. Cross-cutting concepts

These rules hold across modules. Each names its authority; this section exists to make them
findable from one place, not to restate them.

## 8.1 Security and access

Keycloak is the only identity provider; the applications never handle a credential. The backend is
an OAuth2 **resource server**, the frontend an OAuth2 **client**. Authorisation is centralised on
`@PreAuthorize` so the permission model can be read off the code, and ArchUnit tests enforce the
invariants that keep it that way. Those tests select by role and class literal rather than by
package or name, assert a selection floor and are each proven able to fail on a planted fixture,
so moving or renaming a class fails the build instead of quietly leaving a gate (REQ-SEC-073).
Where a gate can still live in more than one place — a `SecurityConfig` URL rule, a controller
annotation, a service annotation — the backend's authorization matrix pins all three for every
operation in one reviewed file, so a move that drops one shows up as a diff (REQ-SEC-074).

Beyond roles there are three mechanisms that are easy to miss:

- **Contextual grants** — LOGISTICIAN and MISSION_MANAGER rights, and SK-lead grants, depend on the
  caller's relationship to the object, not only on a realm role.
- **Per-`sub` isolation** — personal data (inventory, blueprints, hangar) is keyed on the Keycloak
  subject, so it is invisible to everyone else regardless of role.
- **Field redaction for partial viewers** — some surfaces return a reduced projection rather than
  refusing, so a page can exist for a caller who may see *some* of it: a member below Logistician
  reading a peer's mission (`MissionPeerRedactor`, guarded by an ArchUnit rule), a requester-only
  viewer of a job order (`REQ-ORDERS-023`). There is no anonymous or guest tier any more — ADR-0159
  removed both audiences.
- **The session store is not a trust boundary** — a session value names its own class, so the
  frontend reads only classes on `SessionTypeAllowList` (REQ-SEC-067, ADR-0206). Shipped in
  `report` mode, enforced in the E2E stack; production switches to `enforce` by one `.env` value,
  and has run `enforce` since 2026-09-25. The types the frontend can put into a session are derived
  from its bytecode and must be admitted by that list (REQ-FE-027).
- **The frontend's security checks key on what it serves, not on packages** — a committed
  route/gate snapshot pins every mapping with its effective `@PreAuthorize`, every handler carries a
  gate of its own or its class's (nine public handlers excepted), and the template `T(…)`
  references and view names resolve statically (REQ-FE-025, REQ-FE-026). A package move must leave
  the snapshot byte-identical.
- **Each service reaches Redis as its own ACL user** — `basetool-frontend`, `-backend` and `-ingest`,
  each confined to its own keys and channels, with `default` switched off at the end of the rollout
  (REQ-SEC-068, ADR-0207). The ACL is rendered from a committed template with hashes, never
  passwords, and the E2E stack runs against it. Production completed the rollout on 2026-09-25.
- **Internal TLS: one leaf per service, one CA, the name checked** — a private CA whose key is
  destroyed at mint time signs a leaf for backend, frontend, ingest and Keycloak; clients pin the
  CA and verify the hostname (REQ-SEC-070, ADR-0211). Shipped inert behind
  `INTERNAL_TLS_VERIFY_HOSTNAME` and fallback mounts; the committed test material already has the
  shape (ADR-0139 amendment 1). Production completed the rollout on 2026-09-25: each service on
  its own leaf since v1.12.0, every anchor the CA alone since step 4 the same evening.

- **Approved external clients act with less than the member** — a client reaches only
  `/exchange/v1/**` on the ingest gateway, with consent per capability and DPoP-bound tokens;
  behind the relay (`ActingMemberFilter`, an explicit list of exchange routes, gated by
  `@exchangeGate`) the member holds a reduced exchange authentication, never their stored roles,
  and every write is journaled, undoable and bounded by a mass-change guard (§8.13).
- **A security expression is code the compiler cannot check** — every `@bean.method(…)` in a
  `@PreAuthorize` is resolved against the running context in a test, the security beans carry
  explicit names, the SpEL stays constant (REQ-SEC-075), no class calls its own gated method past
  the proxy (REQ-SEC-076), and one that still fails at runtime stays a fail-closed 400 that is
  counted and alerted (REQ-OBS-020).
- **Path-keyed controls check themselves against the real mappings** (guard G-07) — the CSRF
  exemption, the `no-store` classification of every API family, the rate-limit rules and the
  pending, terms and acting-member exemptions are all keyed on paths, so a moved path would lose
  them silently. Tests over the real handler mappings fail on a write outside the CSRF exemption, a
  mapping outside `/api`, `/internal` and the actuator, an unclassified family, a rule that names no
  operation, and an exemption that is not an exact list of real paths (REQ-SEC-031, REQ-SEC-078…080).

Authority: [`security-and-access.md`](../specs/security-and-access.md) (`REQ-SEC-*`),
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md), `ArchitectureTest`.

## 8.2 Multi-org-unit tenancy

The tenant is the **OrgUnit**. Scoping happens in the service layer through `OwnerScopeService`,
and the aggregates genuinely differ: strict-Staffel scoping for most, a public escape for
non-internal Missions, and a separate responsible/requesting pair for Job Orders. Creation stamps
ownership according to a documented matrix, the admin area and promotion are explicit carve-outs,
and `orgUnitId` travels in the MDC and in a relay header so the active context is visible in logs
and across the module boundary.

A scoped aggregate says so in its own code: it carries `@TenantScoped`, naming its owning or
responsible unit, and the tenancy guards select by that marker and by what a controller writes,
never by class names, so a split or moved controller stays under the scope-gate rule (REQ-ORG-028).
Request bodies are kept apart from what the server manages by the same kind of structural rule
(REQ-SEC-077).

Authority: [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (`REQ-ORG-*`).

## 8.3 Persistence and schema

Flyway owns the schema; Hibernate runs `ddl-auto=validate` in **every** profile, including tests, so
a drift between entity and column fails at start-up rather than at runtime. Seeding is explicit
(`DataInitializer`). N+1 queries are treated as defects, and three things fail the build on the
common shapes of one: statement-count tests over the hot reads, Hibernate refusing a paged query it
would have to page in memory, and a catalogue sweep that rejects any foreign key without a leading
index. Every to-one association is `LAZY` (an ArchUnit rule refuses an eager one); a read fetches
what it maps deliberately, and a cached entity is completed before the cache stores it
(`REQ-DATA-003`, BE-PERF-11).

Authority: [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-*`),
[`db/migration/README.md`](../../backend/src/main/resources/db/migration/README.md).

An external catalogue sync never holds a transaction across an HTTP call: it fetches with none open
and writes through `SyncChunkWriter` — short chunk transactions, a failed chunk replayed row by row —
so one refused row costs only itself (`REQ-DATA-005`).

Every writing transaction names its writer. The backend's transaction manager
(`ChangeSourceTransactionManager`) sets the transaction variable `basetool.change_source` at the
start of each transaction that is not read-only — `web`, `app`, `client|<id>|<installation key>` for
a relayed exchange client, otherwise `system` — and the exchange change feed's triggers read it, so
a change reaches the feed with its writer whatever path made it, a bulk path included (ADR-0224,
`REQ-XCH-013`).

Every table has exactly one owning module of the target cut, recorded in a table-ownership map that
a test holds against the migrated schema; a trigger or native statement reaches another module's
table only through a listed crossing, and every column a trigger function names must resolve,
because PostgreSQL would otherwise only notice a renamed column on the next insert
(`REQ-DATA-020`). Every column that references a member has a disposition in the erasure, the
Art. 15 export and the account merge (`REQ-DATA-021`). A master-data cache shares one instance
between every reader, so no mutator may edit an instance a cache handed out; caches move to read
models, and the entity-returning ones are a list that may only shrink (`REQ-DATA-022`).

## 8.4 Concurrency — the landmine field

Optimistic locking with `@Version`, surfaced as HTTP 409, with the **finest granularity the data
allows** (§4.5, §6.3). The specific traps — the `support.OptimisticLock` helper family, Mission's
manual per-section counters and their DB-enforced atomic bump, pessimistic locking for bulk
reorders, the `…WithinTransaction` pattern, bulk updates inside loops, and the find-or-create retry
— are enumerated in [`backend/CLAUDE.md`](../../backend/CLAUDE.md). **Read that before touching any
multi-step transaction.** The frontend half — propagating the new version to every DOM element that
carries it — is in [`frontend/CLAUDE.md`](../../frontend/CLAUDE.md).

## 8.5 API conventions

Versioned `/api/v1` paths with `@ApiDeprecation`, DTO-only boundaries (records + MapStruct +
Jakarta validation), `@Valid` on every write, RFC 7807 `problem+json` for every error,
`Pageable`/`PageResponse` with whitelisted sort fields and a kernel page ceiling of 1 000 (eight
reviewed catalogue lists opt out to 100 000), UTC everywhere, and a committed
`openapi.json` per REST-serving module — generated with sorted keys by each module's
`OpenApiGeneratorTest`, and CI fails a pull request whose committed document differs from the one
its build generated.

A breaking change is a **hard cut**, not a second version: every operation carries a contract tier
(T0 never breaks, T1 is what a released app calls and breaks only in a declared wave with a forced
update, T2 is web-only), and the minimum app version is to be bound to the release
([ADR-0234](../adr/0234-the-api-is-re-cut-by-hard-cut-with-a-forced-app-update.md), REQ-API-001,
-009, -010; decided 2026-10-02, implementation pending).

What the Android app calls is a contract of its own: the frozen set of `ExternalContractTest` must
cover the call list each app release publishes (committed under
`backend/src/test/resources/api/app-calls/`), and a frozen operation or field breaks only by a line
of the declared-break ledger, checked against the previous release's document, which CI must fetch
(REQ-API-016, REQ-API-017). Every operation of the document carries one domain tag (`x-domain`,
22 domains) and one contract tier (`x-contract-tier`: T0 never breaks, T1 the Android contract,
T2 web-only), and every schema name belongs to one Java type (REQ-API-018). The public API vhost
admits exactly the frozen set, by verb and path, through a map generated from it, plus the two
anonymous reads and the retired operations; everything else stops at the edge with `404`
(REQ-API-021).

Authority: [`api-conventions.md`](../specs/api-conventions.md) (`REQ-API-*`).

## 8.6 Frontend behaviour

Two binding rules shape every UI change:

- **The design system is binding** — the DAS KARTELL design system, delivered as a git submodule,
  must be populated before any UI work. UI built against an absent design system is not "no design
  system applies"; it is unreviewed.
- **Live update is binding** — every create/update/delete/toggle/reorder/filter/paginate updates
  the DOM in place via `krtFetch`, with no full-page reload on success, and on shared surfaces a
  peer's change propagates without a manual reload.
- **Two browser-side safety rules are lint-enforced, not review-enforced** (2026-09-22): a `fetch`
  write outside `krtFetch` fails `:frontend:lintJs` (REQ-FE-002), and so does an HTML sink that is
  neither escaped through `escapeHtml` / `escapeAttr` nor a server fragment inserted through
  `krtFetch.setTrustedHtml` (REQ-FE-022, `eslint-plugin-no-unsanitized`).
- **An ETag only where it pays** (FE-PERF-03, 2026-09-22; assets out 2026-09-23). The frontend's
  `ShallowEtagHeaderFilter` covers the web app manifest and `assetlinks.json` — publicly
  cacheable and not content-hashed. The static assets are hashed, `immutable` and revalidate by
  `Last-Modified`; everything else is `no-store`, where no ETag can be issued. So assets, pages,
  fragments and the SSE relay all leave unbuffered. A new publicly cacheable, non-hashed route
  joins `EtagConfig.ETAG_URL_PATTERNS` (ADR-0161).
- **The layout model costs one backend read, and only where it can be rendered** (FE-PERF-01,
  2026-09-23). The three layout advices share one `GET /api/v1/me/layout` per request through
  `LayoutContextLoader`; a handler that writes its own body and reads no `ModelAttribute` pays
  nothing, even inside a view controller. New JSON handlers go into a `@RestController`
  (REQ-FE-020, ADR-0165, ratchet in `ArchitectureTest`).
- **A page ships no developer text and no inline page CSS** (FE-PERF-02, 2026-09-23). Templates
  carry no comments (ADR-0214), page CSS lives in `static/css/pages/<page>.css`
  linked where its `<style>` block stood; the icon sprite stays inline by measurement (2.4 KB gzip).
  A page is 33–42 % smaller raw and about half the size gzipped (REQ-UI-023,
  `TemplateCommentHygieneTest`).
- **Every script is deferred; an inline script runs nothing at parse time** (FE-PERF-05,
  2026-09-23). Only `krt-client-error.js` stays synchronous and first. Page modules keep their order
  behind the head scripts; inline page scripts run their code on `DOMContentLoaded`. A head-side
  `krtEvents` watchdog throws into the client-error beacon when `event-delegation.js` never ran
  (REQ-FE-023, `InlineScriptLoadOrderTest`, `ScriptLoadOrderE2eTest`).
- **Only data forms arm the unsaved-changes guard** (2026-10-02). `unsaved-changes.js` warns before
  a link leaves a page with an edited form; a form marked `no-track` or with `method="get"` is a
  query and never arms it, and a submit triggered by the same edit clears it (REQ-FE-024,
  `UnsavedChangesGuardE2eTest`).
- **Every backend call the frontend makes names an operation the backend has** (2026-10-03). A test
  parses the frontend sources, folds every call site's URI into a template and matches verb and
  path against the committed `openapi.json`; the live-sync probe templates must be existing `GET`s.
  The few sites it cannot fold are listed by name (REQ-FE-028, `BackendCallExistenceTest`).
- **One dialog contract on native `<dialog>`s** (FE-SIMP-04/04b, 2026-09-23). Every
  `.krt-modal-overlay` is a `<dialog>` opened by `window.krtModal` with `showModal()`: top layer,
  inert page, Escape, focus in and back. Transient overlays go into the open dialog
  (`krtModal.layerRoot()`). Every dialog is rendered by `fragments/modal-wrapper :: modal` (one
  shell: `<h2>` and ✕; the page supplies the body), and `DialogA11yE2eTest` runs the contract on
  every dialog it can reach. (REQ-UI-013, ADR-0177, `SingleModalShapeTest`.)
- **Coloured text takes the accessible tints** (2026-09-25). The canonical danger, info and Grau 2
  hues fail WCAG AA as text on the dark surfaces; text uses their `-text` tints, and
  `AccessibleTextTintTest` fails the build on a stylesheet or script that sets one of the canonical
  hues as a text colour (REQ-UI-006).
- **The cascade layer decides, not the load order** (FE-MOD-02, 2026-09-23). Every stylesheet
  declares `@layer base, components, page, migration, utilities;` and keeps its rules inside its
  layer: page CSS beats the design system without specificity bumps, a migrated inline class beats
  both, and the two state classes win outright (REQ-UI-024, ADR-0212, `CascadeLayerOrderTest`).
- **One navigation chrome, rendered once** (2026-10-03). Every app page includes
  `fragments/header.html` and `fragments/sidebar.html`; the drawer is also the phone menu sheet, and
  the `Ctrl`/`⌘` + `K` quick access indexes the links the server rendered into it, so `sec:authorize`
  in that one template stays the only place that decides which pages a member is offered
  (REQ-UI-026, ADR-0240, `NavigationRenderMvcTest`, `NavigationE2eTest`).

Authority: [`ui-design-system.md`](../specs/ui-design-system.md),
[`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (`REQ-FE-*`),
ADR-0012/0013/0031/0094.

## 8.7 Resilience of the frontend → backend call

One centrally-configured WebClient, wrapped by Resilience4j (Timeout, Retry, CircuitBreaker,
Bulkhead), with state transitions logged so a `SERVICE_UNAVAILABLE` or `BACKEND_TIMEOUT` always has
a matching log line. The per-domain typed clients the frontend gets are built over that same
`webClient` bean, never beside it, so the one filter chain stays the single pass
([ADR-0032](../adr/0032-frontend-single-resilience-pass-at-webclient-filter.md) amendment).

**The backend clients stay in the kernel and address only the backend** (REQ-FE-029, 2026-10-03).
`WebClientConfig` builds four clients: `webClient` and the anonymous `termsDocumentClient` carry the
Resilience4j chain, the SSE relay's `sseWebClient` and the live-sync probe's
`liveSyncAuthWebClient` deliberately do not. Only `WebClientConfig` builds a client, and only
`BackendApiClient`, the SSE relay and the probe hold one (`WebClientConfinementTest`); every other
class calls `BackendApiClient`. The first filter of all four refuses any request whose scheme, host
and port differ from `app.backend-url`, before the OAuth2 filter can attach the member's bearer —
an absolute URL handed to a client would otherwise carry the token to that host. Future
HTTP-interface clients are created over the same `webClient` bean and take no `URI`,
`UriBuilderFactory` or `@CookieValue` parameter, name no absolute URL and carry no cache
annotation.

**Reactor context propagation is mandatory** for anything that must be visible inside an exchange
filter: `WebClient.exchange()` runs on a Reactor-Netty worker thread and a plain `ThreadLocal` is
not copied there. The accessors that exist cover the active-OrgUnit pin, the correlation id, the
user locale and the client IP. Forgetting one is silent — the holder is simply empty on the worker
thread. `ParallelPageLoader`, which runs a page's independent backend reads on virtual threads,
restores a snapshot of the same registry on each worker, so a registered accessor reaches those
sections too (REQ-FE-030, 2026-10-03; until then the loader copied the holders by hand and missed
the locale).

**This is a frontend rule only.** The backend and the ingest call HTTP through blocking
`RestClient`s on the JDK HTTP client (ADR-0204): no WebFlux, no Reactor Netty, and the call runs on
the thread that holds the MDC. Each module builds its clients in one `RestClientConfig` (backend
`config`, ingest `relay`), wires the observation registry by hand (neither ships Boot's
`spring-boot-restclient`), pins HTTP/1.1 and caps the response body with a
`ResponseSizeLimitInterceptor`; a new outbound call in either module goes through those clients
rather than a fresh `RestClient.builder()`, or it is neither observed nor bounded. In the ingest,
`ConcernPackageRulesTest` fails on an outbound HTTP client outside `relay` (REQ-INGEST-014).

In the ingest the exchange relay runs on `exchangeRestClient` (30 s) under its own breaker
`exchange` and, for change sets of more than 100 ops, a four-slot bulkhead (ADR-0204 amendment 2,
REQ-XCH-023). *(The extractor's handoff relay on `backendRestClient` with its `backend` breaker was
removed with the `/v1` routes on 2026-09-28.)*

## 8.8 Audit

Thirteen audited areas (Bank, Lager, Aufträge, Raffinerie, Mein Inventar, Missionen, Operationen,
Rollen, Beförderung, Materialbörse, Hangar, Blueprints, Verbundene Anwendungen) log **every** state-mutating activity to an append-only trail. Adding a
mutation to an audited area without its audit event is an incomplete change — including the event
type, the recording call, the viewer's per-area filter, the DE/EN labels and the coverage list. No
user free text and no personal data in the details payload.

Audit is a direct, synchronous call inside the business transaction, never an event: no
after-commit or asynchronous method records an audit row, no listener reads the request-bound scope
or security context, and an observer SPI implementation (`@ObserverSpi`) joins the caller's
transaction as `MANDATORY` (`REQ-AUDIT-007`).

Authority: [`audit.md`](../specs/audit.md) (`REQ-AUDIT-001`, `REQ-AUDIT-007`).

## 8.9 Observability

One access-log line per request; MDC carrying `correlationId`, `userId` and `orgUnitId`, propagated
across module boundaries; JSON logging in production. Business metrics are `basetool_*` with
bounded labels. **Never log names, e-mail addresses or tokens** — unconditionally.

The MDC is a `ThreadLocal`, so it is bound per **dispatch**, not per request: a servlet async
dispatch (an SSE stream's completion, a `DeferredResult`) runs on another container thread, and
the filters that own the fields re-bind there what the initial dispatch resolved, from request
attributes, without resolving anything afresh (since 2026-09-25, `REQ-OBS-001`).

Monitoring moves with every feature: a new scheduled job needs task metrics, a new audited area its
event counter, a new status enum its queue gauge, a new public surface its probe. A renamed or
removed metric that breaks a dashboard or an alert rule is an incomplete change.

Authority: [`observability.md`](../specs/observability.md) (`REQ-OBS-*`), `monitoring/`.

## 8.10 Internationalisation

Every user-visible string comes from `messages.properties` / `_de` / `_en` — labels, buttons,
tooltips, errors, flash messages, placeholders, titles. No hardcoded text in HTML, JS or Java.
Inside `.properties` files German umlauts are `\uXXXX`-escaped; everywhere else they are literal
UTF-8.

A browser script gets its wording from the page, never from a literal: a `th:inline` bootstrap
dictionary (`bookOutI18n`, `ORDER_HANDOVER_I18N`, …, declared in `types/thymeleaf-bootstrap.d.ts`
and the module's `/* global */` header), a `window.krt*I18n` object from `fragments/head.html`, or
`data-*` attributes on an element the fragment renders. The last primary literals — the book-out
terminal picker, the order handover row and file name, the special-command modal titles, the admin
chip — moved into the bundles on 2026-09-23 (FE-SIMP-03).

**A missing string fails visibly, never silently** (owner decision 2026-09-23). There are no literal
defaults after `||` any more: a script passes each string through `window.krtI18nText(value, key)`
(installed by `krt-client-error.js`, the first script of every page). A string the page did not
provide renders as its key name (`DICT.property` / `data-attribute`) and is reported once per page
view as an `i18n_missing` client error, counted in `basetool_client_error_total`. The defaults
`krtFetch` applies when a caller passes no toast or conflict text come from `window.krtFetchI18n`
in `fragments/head.html`. `I18nDictionaryCoverageTest` fails the build when a key a script names
is missing from the pages that declare its dictionary, and when a literal fallback comes back.

## 8.11 Configuration

Type-safe `@ConfigurationProperties` with `@Validated` for anything that matters, so a
misconfiguration fails at start-up rather than at first use. In the backend they are immutable
records with `@DefaultValue` (BE-MOD-04), registered by `@ConfigurationPropertiesScan`; a unit test
binds one through `BoundProperties`, so an unset key keeps its production default. On the host, `env.d` files are
*rendered* from `.env` by `render-env-d.py`; the compose environment blocks are closed allow-lists,
so a variable not named there cannot be pulled in from `.env` by accident.

## 8.12 Testing

Every feature ships with tests, and Gradle is the only sanctioned test path. **Never a production
credential in a test or a local stack** — dedicated test artifacts exist for exactly this
(`.env.test`, the committed throwaway TLS material of [ADR-0139](../adr/0139-shared-committed-tls-material-for-the-test-stack.md),
a stripped realm export). Deliberately publishing a worthless artefact and leaking a real one are
opposite acts; one is not licence for the other.

**Mutation testing runs weekly for all three applications** (`pitest.yml`: backend, frontend and
ingest, each with its own job timeout). A job passes only when PIT finished and left a non-empty
report (`scripts/check-pit-result.sh`). The backend and frontend mutate their `service` package; the
ingest mutates every concern package that holds a gate or its state — `auth`, `contract`, `edge`,
`gate`, `handoff`, `idempotency`, `limits`, `observability`, `registry`, `relay`, `store` — all but
the wiring (`assembly`, `config`), `problem` and `web` (`ingest/build-settings.properties`, REQ-OPS-037). No module declares a mutation threshold yet.

The reverse direction holds too: **nothing test-only ships.** The `test` profile lives in each
module's `src/test/resources` and the `jar`/`bootJar` tasks fail on a jar that carries one; the
shared test helpers in `test-support` reach no runtime classpath. And a test runs against what
production runs where that is cheap to arrange: the Redis integration tests start the production
image by digest (`TestImages.REDIS`, guarded against the compose file and the Quadlet unit), and
the backend's Testcontainers PostgreSQL is one container per test JVM (`TC_DAEMON=true`).

Backend module coupling is measured in tests too. An ArchUnit `modules()` rule over the domain map
lets a module depend only on lower-ranked modules and its same-rank `allow` rows; today's violations
are frozen in `backend/src/test/resources/architecture/module-baseline/` and may only shrink — a new
edge fails, a fixed one must be removed from the committed file. Spring Modulith runs beside it in
test scope only, with explicitly annotated module detection. **Only coupling is ever frozen;** a
security or structural rule stays a hard rule
([`module-boundaries.md`](../specs/module-boundaries.md), REQ-MOD-003…005).

**A quality gate never falls back to a default, and a guard never narrows in silence.** Each module
declares its test heap, coverage floors and PIT targets in its own `build-settings.properties`, and
configuration fails without them (REQ-OPS-037). Guards that find their subject by a path or a
listing fail when they stop seeing it — build-script input paths, recursive content scans, the DTO
mirror pairing, and a counted context shape per application (REQ-OPS-038) — so moving files or
packages cannot switch a gate off.

**The build sets the `test` profile, once.** Every Gradle `Test` task activates it, and no test class
repeats it with `@ActiveProfiles("test")`: the annotation is part of Spring's test-context cache key,
so it only splits contexts that are otherwise identical (REQ-OPS-039, guarded by a
`TestProfileConventionTest` per application).

**Test classes share their application contexts.** A test reuses a plain `@SpringBootTest` or, in
the backend, `@LeafServiceMockTest` with its one agreed set of leaf-service mocks, rather than
declaring a mock set or property of its own; security beans stay real in a shared context
(`LeafServiceMockSecurityTest`). `TestContextBudgetTest` computes every class's context-cache key
without starting a context and holds each application to a budget (REQ-OPS-041).

## 8.13 The external client exchange

Three rules hold for every exchange route, and each new resource or capability inherits them:

- **One write path.** An exchange write goes through the web's own service path — the Blueprints
  add and delete, the Lager's book-in, book-out and marking, the Hangar's create, update and
  delete — so validation, the audit event (naming the client), Materialbörse offer effects and live
  sync follow as for a web edit. It is never a second write logic. Each written entry is journaled
  in the same transaction, the guard is asked first, and the change feed's triggers catch every
  path, bulk ones included (§8.3; REQ-XCH-013, -021, -022, ADR-0218, ADR-0224).
- **Reduced authentication, own data only.** On `/api/v1/exchange/**` the acting member holds
  `ROLE_EXCHANGE_MEMBER` and the relayed capabilities, never their roles, contextual grants or an
  admin pin, and reads and writes only their own rows. The `X-Exchange-*` headers count only from
  the gateway's identity. The member's controls (`/api/v1/connected-apps/**`) answer only the
  member's browser session, so a client can never confirm, undo or revoke (REQ-XCH-001, -009, -010;
  ArchUnit rules in `ArchitectureTest`).
- **Tolerant reader, additive v1.** Within `/exchange/v1` a change only adds: unknown fields are
  ignored and reported as `UNKNOWN_FIELD` warnings, unknown enum values read as `UNKNOWN`, schemas
  stay open, a published `$id` never changes, and identifiers and cursors are opaque. A breaking
  change is `v2`, served beside `v1` for at least twelve months. The contract test fails a schema
  that shrank since the last release (REQ-XCH-026, ADR-0219).
- **Frozen across the relay, pinned at build time.** The backend surface the gateway relays to
  keeps its behaviour byte-identical (D-05). Every identifier the modules share — relay paths,
  headers, capability scopes, gate codes, the registry mirror document, the revocation and handoff
  keys — is declared once in `test-support`'s `ExchangeSeam` and each module asserts its side
  against it; the backend's answers are validated against the published schemas, every gateway
  route has a committed golden answer, the gateway's security filter order is pinned, and a pull
  request touching the exchange path needs the `e2e` label (REQ-XCH-036…-038).

Authority: [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-*`), ADR-0216 …
ADR-0221, ADR-0224 … ADR-0228; the third-party view is published from `docs/exchange/`.

## 8.14 Domain modules — decided, being built

The backend is being cut into domain modules inside its one Gradle module (plan
[`DOMAIN_MODULARISATION_PLAN.md`](../DOMAIN_MODULARISATION_PLAN.md); nothing has moved yet). Five
rules hold for every module as it lands:

- **One package per domain, with a rank.** A module depends only on lower ranks or on what its
  declaration allows; `kernel` and `platform` carry no domain meaning; inside a module, `api` is the
  only thing another module may use, `internal` holds the rest, and `web` holds controllers and REST
  DTOs without transactions, repositories or entities
  ([ADR-0231](../adr/0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md)).
- **Three ways to interact.** A command or query API whose writes are `MANDATORY`; an observer SPI
  owned by the lower module and called in the same transaction; an after-commit event only where the
  reaction may happen later or fail on its own. Only the owner writes its aggregate, and audit is
  always a direct synchronous call, never an event
  ([ADR-0232](../adr/0232-modules-interact-through-commands-observers-and-after-commit-events.md)).
- **Enforced by tests, not review.** ArchUnit keeps the security rules, re-keyed so a move cannot
  disarm them, and a frozen module baseline that may only shrink; security rules are never frozen;
  Spring Modulith verifies the modules in test scope only
  ([ADR-0233](../adr/0233-module-boundaries-are-enforced-by-archunit-and-spring-modulith-in-test-scope.md)).
- **Access is a policy per domain** that owns both the per-row gate and the JPQL scope fragment
  ([ADR-0236](../adr/0236-each-domain-owns-an-access-policy-over-the-scope-kernel.md)); errors are a
  sealed kernel of kinds plus per-module problem codes
  ([ADR-0235](../adr/0235-errors-are-a-sealed-kernel-of-kinds-and-per-module-problem-codes.md)).
- **Guards before moves.** No class, controller, template, script or path moves before the Phase 0
  guards are green and each is proven able to fail once; a move pull request is mechanical and
  keeps the authorization matrix byte-identical (plan §6).

The REST API follows the modules by hard cut with a forced app update
([ADR-0234](../adr/0234-the-api-is-re-cut-by-hard-cut-with-a-forced-app-update.md), §8.5). Only the
exchange and the bank later become Gradle modules of their own. The debt this closes is §11.9.
