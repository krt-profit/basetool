# 8. Cross-cutting concepts

These rules hold across modules. Each names its authority; this section exists to make them
findable from one place, not to restate them.

## 8.1 Security and access

Keycloak is the only identity provider; the applications never handle a credential. The backend is
an OAuth2 **resource server**, the frontend an OAuth2 **client**. Authorisation is centralised on
`@PreAuthorize` so the permission model can be read off the code, and ArchUnit tests enforce the
invariants that keep it that way.

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
  `report` mode, enforced in the E2E stack; production switches to `enforce` by one `.env` value.

Authority: [`security-and-access.md`](../specs/security-and-access.md) (`REQ-SEC-*`),
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md), `ArchitectureTest`.

## 8.2 Multi-org-unit tenancy

The tenant is the **OrgUnit**. Scoping happens in the service layer through `OwnerScopeService`,
and the aggregates genuinely differ: strict-Staffel scoping for most, a public escape for
non-internal Missions, and a separate responsible/requesting pair for Job Orders. Creation stamps
ownership according to a documented matrix, the admin area and promotion are explicit carve-outs,
and `orgUnitId` travels in the MDC and in a relay header so the active context is visible in logs
and across the module boundary.

Authority: [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (`REQ-ORG-*`).

## 8.3 Persistence and schema

Flyway owns the schema; Hibernate runs `ddl-auto=validate` in **every** profile, including tests, so
a drift between entity and column fails at start-up rather than at runtime. Seeding is explicit
(`DataInitializer`). N+1 queries are treated as defects, and three things fail the build on the
common shapes of one: statement-count tests over the hot reads, Hibernate refusing a paged query it
would have to page in memory, and a catalogue sweep that rejects any foreign key without a leading
index.

Authority: [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-*`),
[`db/migration/README.md`](../../backend/src/main/resources/db/migration/README.md).

An external catalogue sync never holds a transaction across an HTTP call: it fetches with none open
and writes through `SyncChunkWriter` — short chunk transactions, a failed chunk replayed row by row —
so one refused row costs only itself (`REQ-DATA-005`).

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
`Pageable`/`PageResponse` with whitelisted sort fields, UTC everywhere, and a committed
`openapi.json` per REST-serving module.

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

Authority: [`ui-design-system.md`](../specs/ui-design-system.md),
[`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (`REQ-FE-*`),
ADR-0012/0013/0031/0094.

## 8.7 Resilience of the frontend → backend call

One centrally-configured WebClient, wrapped by Resilience4j (Timeout, Retry, CircuitBreaker,
Bulkhead), with state transitions logged so a `SERVICE_UNAVAILABLE` or `BACKEND_TIMEOUT` always has
a matching log line.

**Reactor context propagation is mandatory** for anything that must be visible inside an exchange
filter: `WebClient.exchange()` runs on a Reactor-Netty worker thread and a plain `ThreadLocal` is
not copied there. The accessors that exist cover the active-OrgUnit pin and the correlation id.
Forgetting one is silent — the holder is simply empty on the worker thread.

**This is a frontend rule only.** The backend and the ingest call HTTP through blocking
`RestClient`s on the JDK HTTP client (ADR-0204): no WebFlux, no Reactor Netty, and the call runs on
the thread that holds the MDC. Each module builds its clients in one `config.RestClientConfig`,
wires the observation registry by hand (neither ships Boot's `spring-boot-restclient`), pins
HTTP/1.1 and caps the response body with a `ResponseSizeLimitInterceptor`; a new outbound call in
either module goes through those clients rather than a fresh `RestClient.builder()`, or it is
neither observed nor bounded.

## 8.8 Audit

Nine audited areas (Bank, Lager, Aufträge, Raffinerie, Mein Inventar, Missionen, Operationen,
Rollen, Beförderung) log **every** state-mutating activity to an append-only trail. Adding a
mutation to an audited area without its audit event is an incomplete change — including the event
type, the recording call, the viewer's per-area filter, the DE/EN labels and the coverage list. No
user free text and no personal data in the details payload.

Authority: [`audit.md`](../specs/audit.md) (`REQ-AUDIT-001`).

## 8.9 Observability

One access-log line per request; MDC carrying `correlationId`, `userId` and `orgUnitId`, propagated
across module boundaries; JSON logging in production. Business metrics are `basetool_*` with
bounded labels. **Never log names, e-mail addresses or tokens** — unconditionally.

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
