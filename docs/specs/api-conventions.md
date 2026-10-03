> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-03.
> **Owner area:** API · **Related:** [`security-and-access.md`](security-and-access.md), [`observability.md`](observability.md)

# API conventions

## Context & goal

Uniform, versioned, well-documented REST contracts with DTO boundaries, RFC 7807 errors,
safe pagination, and UTC time — so clients (the frontend especially) integrate against a
stable, predictable surface.

## Requirements

### REQ-API-001 — Versioned URI paths

Paths are `/api/v1/...`. A breaking change is made by **hard cut** (ADR-0234): the operation
moves or changes in place in one release, with no parallel old path, no deprecation alias and no
sunset window. Every operation belongs to one contract tier — **T0** never breaks (the version gate
`GET /api/v1/app/version-policy`, `POST /internal/discord/account-existence`, the 14 exchange relay
operations under `/api/v1/exchange/**`, the two SSE streams and `POST /api/v1/live-sync/changed`);
**T1**, what a released Android build calls, breaks only in a declared hard-cut wave under
REQ-API-009 and REQ-API-010; **T2**, web-only, changes freely under the carve-out below.
`@ApiDeprecation(sunset = "YYYY-MM-DD", replacement = "…")` stays available for a deliberate
deprecation: `DeprecationInterceptor` emits `Deprecation` / `Sunset` / `Link` headers and
`OpenApiDeprecationConfig` reflects it in the spec.

> [!note] Amended 2026-10-02 — the hard cut (owner decisions D-03, D-04, D-11; ADR-0234)
> Breaking changes used to go to `/api/v2/...` beside `/api/v1`. **Decided, implementation
> pending:** the tier marker (`x-contract-tier`) and the T0 record do not exist yet; until they do,
> the tiers are the lists named here and in REQ-API-009.

**The web frontend never calls a deprecated operation** (since 2026-09-22, BE-SIMP-02). The headers
are read only by a human, so a deprecation the in-repo client kept using used to surface on its
sunset day as a broken page — the frontend was still calling twelve of the seventeen deprecated
mission endpoints weeks before theirs. `DeprecatedBackendEndpointCallGuardTest` (frontend) reads
every `deprecated: true` operation out of the committed `openapi.json` and fails the build when a
frontend call matches one, verb included. It reads the call sites from the same scan as the
existence guard of REQ-FE-028, so `backendApiClient.execute(…)` and concatenated, constant or
builder-built URIs are seen too. A new deprecation is guarded as soon as the document is
regenerated; move the caller to the named replacement in the same change. The one deprecated
operation the frontend still relays on purpose, `POST /api/v1/hangar/import/fleetview` (sunset
2027-05-14), is named in the guard's list of reviewed relays.

**The seventeen deprecated mission endpoints are deleted — early** (owner decision 2026-09-22,
#1996): the MissionDto-returning unit, crew, participant, check-in/-out, payout-preference,
frequency and manager writes and the unversioned `PUT …/owner/{userId}`. Their announced sunset was
2026-10-20; the owner shortened it to the day of the decision, so they left production with that
release rather than on the announced date, and app builds older than 2026-09-07 (the move to the
slim paths) must be updated. Their `/slim` twins and the versioned `PUT …/owner` replace them.
`POST …/participants` left REQ-API-009's frozen set and the API-vhost allow-list with them, and the
nightly probe asserts 404 for it. Its manager-only successor for the app is
`POST …/participants/by-id/slim` (REQ-MISSION-020, ADR-0170 amendment), admitted and frozen.

**Carve-out — internal-only endpoints (T2):** a `/api/v1` endpoint consumed solely by the in-repo
frontend may change its response *shape* or its path in place when frontend and backend deploy
atomically and `DtoOpenApiContractTest` guards the frontend mirror against
`openapi.json` — e.g. the inventory `/grouped` move from `items` to `stacks` (ADR-0003).

**The carve-out stops at the external contract set.** Its whole justification is the atomic
deploy, which a released native client does not have. Operations listed in REQ-API-009 are
therefore frozen against in-place shape change even though they live under `/api/v1`, and change
only in a declared hard-cut wave (REQ-API-009).

### REQ-API-002 — DTOs only at boundaries

Never expose JPA entities at controller boundaries (also ArchUnit-enforced — see
[`security-and-access.md`](security-and-access.md) REQ-SEC-003). DTOs are records; write DTOs
carry Jakarta validation (`@NotBlank`, `@NotNull`, `@Min`, `@Max`, …). Use a MapStruct
mapper (`@Mapper(config = CentralMapperConfig.class)`) for Entity↔DTO; break circular refs with
`@Mapping(ignore = true)`. `CentralMapperConfig` injects used mappers through the constructor and
sets **`unmappedTargetPolicy = ERROR`** (BE-MOD-05/05b, 2026-09-23): a target property no source
feeds fails the build, so a DTO field added later can no longer ship silently `null`. Every
intended gap is an explicit `@Mapping(target = "…", ignore = true)`. There is no method-level
exemption: `MaterialMapper.toEntity`, whose DTO is the admin-edit subset of a ~40-column catalogue
row, uses `@BeanMapping(ignoreByDefault = true)` and names each of the fields it carries. The switch found
one real gap — the job type a mission embeds never carried `isMissionLead`.

### REQ-API-003 — Validation on writes

`@Valid` on every `@RequestBody` for write operations (POST/PUT/PATCH). Enforced since REQ-API-015.

### REQ-API-004 — RFC 7807 error format

Errors are `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, a
stable machine-readable `code`, and a per-request `correlationId`; validation errors add an
`errors` object (field → message) **and** a structured `fieldErrors` array (`{field, message}`).
Titles and details are localized via `MessageSource`. Extend `GlobalExceptionHandler` rather than
throwing into the void; problem-type URIs come from `AppProblemProperties`, not hardcoded strings.

**`GlobalExceptionHandler` must outrank Spring's own problem-details advice (ADR-0132).**
`spring.mvc.problemdetails.enabled: true` makes Spring Boot register a competing
`ProblemDetailsExceptionHandler` `@ControllerAdvice` at `@Order(0)`; an unordered advice sits at
`LOWEST_PRECEDENCE` and **loses** for every exception type both declare
(`MethodArgumentNotValidException`, `HttpMessageNotReadableException`,
`MethodArgumentTypeMismatchException`, `HttpRequestMethodNotSupportedException`,
`NoResourceFoundException`, `ErrorResponseException`). Those responses then carry Spring's bare
`ProblemDetail` — no `code`, no `correlationId`, no `fieldErrors`, untranslated English `detail` —
which silently breaks the contract above and, because the frontend needs `fieldErrors` to place an
inline message at the offending field, degrades every 400 to a generic "some fields are invalid"
toast with nothing in the server log either. The `@Order(Ordered.HIGHEST_PRECEDENCE)` on
`GlobalExceptionHandler` is therefore **load-bearing**; `GlobalExceptionHandlerAdviceOrderTest`
guards it by driving Spring's own advice discovery and first-advice-wins resolution. A unit test that
calls the handler methods directly cannot detect this class of break.

**Sanctioned producers outside `GlobalExceptionHandler`.** Some errors are raised before the
`DispatcherServlet` (in a filter or the security chain) and cannot reach the `@ControllerAdvice`, so
they produce the equivalent problem+json themselves — every one carries the same `code` +
`correlationId` contract:

- `SecurityProblemResponseHandler` — the shared `AuthenticationEntryPoint` + `AccessDeniedHandler`
  wired into `SecurityConfig` (globally and on the resource server). It does **not** hand-build a
  body: it delegates the `AuthenticationException` / `AccessDeniedException` to the MVC
  `handlerExceptionResolver`, so `GlobalExceptionHandler` renders the 401 (`UNAUTHENTICATED`) / 403
  (`ACCESS_DENIED`). It mints the `correlationId` into the MDC first (security runs before
  `CorrelationIdFilter`) so body, log line and the echoed `X-Correlation-Id` header share one id.
- `RateLimitingFilter` — hand-builds the 429 body (`code = RATE_LIMIT_EXCEEDED`), localized
  `title`/`detail`, minted+logged+header-echoed `correlationId`.
- `PendingApprovalAccessFilter` — hand-builds the 403 body (`code = PENDING_APPROVAL`), localized,
  minted+logged+header-echoed `correlationId`, serialized via the shared Jackson `ObjectMapper`.
- `BasetoolErrorController` — replaces Boot's `BasicErrorController` at `/error` so servlet-container
  error dispatches (an error escaping a filter, a `sendError`) render problem+json with a
  status-derived `code` and a `correlationId` (body + header) instead of Boot's plain-JSON map.

**The ingest gateway has the same rule and its own producers**, all writing through the shared
`ProblemResponseWriter` (which stamps `code` + `correlationId`) rather than hand-building a body:
`PayloadSizeLimitFilter` (413, `PAYLOAD_TOO_LARGE`), `RateLimitingFilter` (429, `RATE_LIMITED`),
`IdentityProviderUnavailableFilter` (503, `SERVICE_UNAVAILABLE`) and — closing the last gap —
`SecurityProblemResponseHandler` for the filter-level **401 / 403** (`UNAUTHENTICATED` /
`ACCESS_DENIED`). Those two previously fell through to Spring Security's defaults, which answer with
an **empty body**: a desktop client had nothing to branch on and a user had nothing to quote. The
handler delegates to `BearerTokenAuthenticationEntryPoint` first so the RFC 6750 `WWW-Authenticate`
challenge is preserved, then writes the problem body on top. Unlike the backend's handler of the same
name it mints no correlation id: the gateway's `CorrelationIdFilter` runs *outside* the security
chain, so the MDC is already populated and the header already echoed.

Document the format in OpenAPI and keep frontend error display in sync. **Every `code` value comes
from the error-code registry (REQ-API-019)**, and the document's `ProblemDetail` schema carries
`code` (with the registered values), `correlationId`, `errors` and `fieldErrors`, plus a `429` on
every `/api/**` operation, since both rate limiters answer it there.

> [!note] Corrected 2026-10-03
> Until REQ-API-019 the document's `ProblemDetail` listed none of `code`, `correlationId` or
> `fieldErrors` — the fields this requirement calls the contract — and its properties carried no
> `type`, because the OpenAPI 3.1 writer ignores the 3.0 setter the customizer used; `429` was
> documented on one operation. Five producers are missing from the list above and are sanctioned
> the same way: `TermsAcceptanceAccessFilter` (403 `TERMS_NOT_ACCEPTED`), `ActingMemberFilter`
> (403 `ACTING_MEMBER_REFUSED`), `IdentityProviderUnavailableFilter` (503 `SERVICE_UNAVAILABLE`),
> `SubjectRateLimitingFilter` (429 `RATE_LIMIT_EXCEEDED`) and `RequestBodySizeLimitFilter` (413
> `REQUEST_BODY_TOO_LARGE`); `PendingApprovalAccessFilter` also answers `NO_ROLE`.

**Spring MVC's client errors answer their own 4xx, never the catch-all 500.** A missing request
parameter, header, cookie or matrix variable, a mapping's unsatisfied parameter condition
(`ServletRequestBindingException` and its subtypes) and a missing multipart part
(`MissingServletRequestPartException`) are `400 BAD_REQUEST`, the detail naming the missing value; a
response no accepted media type can carry (`HttpMediaTypeNotAcceptableException`) is
`406 NOT_ACCEPTABLE`; an upload over the multipart limit (`MaxUploadSizeExceededException`) is
`413 REQUEST_BODY_TOO_LARGE`. A binding exception Spring itself classifies as a server error — a path
variable the mapping does not declare (`MissingPathVariableException`) — stays `500 INTERNAL_ERROR`.
The status-derived fallback (`codeForStatus` in the handler, `mappingFor` in `BasetoolErrorController`)
maps `429` to `RATE_LIMIT_EXCEEDED`.

**Every `429` carries `code = RATE_LIMIT_EXCEEDED`, a `correlationId` and `Retry-After`** (whole
seconds), beside the limiters' `X-Rate-Limit-*` headers: the per-IP `RateLimitingFilter`, the
per-subject `SubjectRateLimitingFilter` (REQ-SEC-033) and `POST /api/v1/live-sync/changed`
(`RateLimitExceededException`, REQ-FE-019). The document declares `Retry-After` and the three
`X-Rate-Limit-*` headers on every `/api/**` operation's `429`.

> [!note] Corrected 2026-10-03
> Until then the five exception types above answered `500 INTERNAL_ERROR` through the catch-all;
> `SubjectRateLimitingFilter`'s `429` had a `null` `correlationId` and no `Retry-After`;
> `POST /api/v1/live-sync/changed` answered its `400` and `429` with an empty body although the
> document promised a problem; the status fallback mapped `429` to `BAD_REQUEST`; and four bank codes
> (`BANK_REQUEST_NOT_PENDING`, `BANK_REQUEST_ALREADY_APPROVED`, `BANK_ACCOUNT_HAS_PENDING_REQUESTS`,
> `BANK_OWNER_APPROVAL_REQUIRED`) had no `title` key, so the client was shown the bundle key.

**A raw `IllegalStateException` is a 500, never a 400 (APPSEC-06, 2026-09-22).** The handler used to
answer every `IllegalStateException` with a 400 and echo its message as `detail`, on the assumption
that only the application's own guards threw it. The JDK, Spring, Hibernate and every library throw
it too — for server defects, with messages that can carry data values or internal names — so a defect
reached the client as "your request was wrong" with an internal message attached, and escaped every
5xx alert. `handleIllegalState` now delegates to the generic 500 (message and stack trace logged under
the `correlationId`, generic localized `detail` to the client), exactly like `IllegalArgumentException`
never echoes its message. **A client-side guard throws `BadRequestException` (or another
`AppException`) with an i18n key** — never `IllegalStateException`. The four guards that relied on the
old mapping were converted: `error.job_order.inventory_item_not_linked` (handover and production,
an entry not earmarked to the order), `error.refinery_order.already_stored` and
`error.user.still_in_keycloak` (both Keycloak-presence checks of the account deletion); their status
stays 400. `GlobalExceptionHandlerTest` pins both halves.

Service-layer repository lookups raise their 404 through the fetch-or-throw helper
`exception.Entities.require(optional, message)` (S1, #907) rather than a hand-written
`find*(id).orElseThrow(() -> new NotFoundException(…))`. The not-found `detail` stays
**caller-supplied, never auto-derived from the type** — `GlobalExceptionHandler.resolveDetail`
treats the message as a translation key (sentinel-guarded), so an auto-derived message would change
the wire `detail` and break the future i18n-key migration seam. A constant message uses the
`String` overload; a message that interpolates a value uses the `Supplier<String>` overload, so it
is still only built on a miss. **Enforced by `EntitiesRequireRatchetTest`**, a source scan of
`backend/src/main/java` whose ceiling on hand-written sites is **zero** since the 312 remaining ones (25 of them threw JPA's
`EntityNotFoundException`, which `handleNotFound` answers identically)
were migrated message by message (BE-SIMP-01, 2026-09-23); `Entities` itself is the only exemption.

**Domain exceptions carry their own error-code contract (S4, #910).** `BadRequestException`,
`NotFoundException`, `BusinessConflictException`, `DuplicateEntityException`,
`EntityInUseException`, `ExternalServiceException`, `ReportGenerationException`,
`OverAllocationException`, `ProductionAllocationException`, `OwnerOrgUnitRequiredException`,
`MissionParticipantRequiredException`, `RateLimitExceededException` and `BankConflictException` —
thirteen in all, beside the exchange's own `ExchangeProblemException` — extend the sealed `exception.AppException`, exposing `status()`,
`code()`, `titleKey()`, `detailKey()`, `typeSuffix()` and `logLabel()` on the type itself instead of
leaving that identity scattered across `GlobalExceptionHandler`'s `CODE_*` constants and per-type
`@ExceptionHandler` methods. A single `handleAppException` dispatch handler reads those accessors
for every subtype except `NotFoundException`, whose handler stays dedicated because it also covers
three non-`AppException` JPA/JDK "not found" flavors (`EntityNotFoundException`,
`NoSuchElementException`, `NoResourceFoundException`) that cannot be sealed under this hierarchy.
Every subtype but `BankConflictException` passes its fixed
`exception.AppExceptionKind` constant to the `AppException(AppExceptionKind, String)` /
`AppException(AppExceptionKind, String, Throwable)` superclass constructor and inherit every
accessor from `AppException`, which delegates to that stored kind; the one addition is
`RateLimitExceededException`, which also overrides `responseHeaders()` so the dispatch handler sends
its `Retry-After`. `BankConflictException` is the
one exception that overrides every accessor directly, computing them per-instance from its own
`code` field (it has no single fixed identity — each throw site picks one of its `CODE_BANK_*`
constants) via the legacy kind-less `AppException(String)` / `AppException(String, Throwable)`
constructors. The one behavioural fork — `ExternalServiceException` / `ReportGenerationException`
suppressing `getMessage()` from the client and logging at ERROR instead of WARN, an
info-leak-protection constraint (CWE-209) — is the `ErrorDisclosurePolicy` strategy enum on
`AppExceptionKind`, likewise inherited automatically via the stored kind. A new domain exception
joins this hierarchy by extending `AppException` and either passing a new `AppExceptionKind`
constant to the superclass constructor (the common case, requiring zero accessor overrides) or
implementing the accessors directly (only if its identity is genuinely per-instance, as
`BankConflictException`'s is) — never by hand-rolling a new `@ExceptionHandler` method.

### REQ-API-019 — Every problem code is registered once and documented

A client branches on `code`, never on the localized `title` or `detail`, so the set of codes is a
contract — and until 2026-10-03 it lived as about fifty string literals across the exception package,
the handler, six filters and the error controller, with no list, no uniqueness check and no
documentation. The app once listened for `TERMS_ACCEPTANCE_REQUIRED` while the server sends
`TERMS_NOT_ACCEPTED`. This is the registry and documentation half of ADR-0235; the exception
hierarchy (a sealed kernel of kinds plus one `ProblemCode` enum per module) follows in Phase 1.

- **`exception.ProblemCode`** — a code's wire value (`code()`) and its HTTP status (`status()`). The
  code string is the contract; the Java name is not.
- **`exception.CoreProblemCode`** — one kernel enum listing every code the backend emits outside the
  exchange: the handler's, the twelve `AppExceptionKind` codes, the 18 bank codes, the filters'
  (`TERMS_NOT_ACCEPTED`, `PENDING_APPROVAL`, `NO_ROLE`, `ACTING_MEMBER_REFUSED`,
  `SERVICE_UNAVAILABLE`, `RATE_LIMIT_EXCEEDED`, `REQUEST_BODY_TOO_LARGE`) and `NOT_ACCEPTABLE`
  (`406`) — 50 in all. Three are
  **reserved**, registered but not emitted: `BANK_HOLDER_OVERDRAFT` (ADR-0039),
  `BANK_CARTEL_APPROVAL_REQUIRED` (ADR-0109) and `APP_UPDATE_REQUIRED`, which retired paths will
  answer (ADR-0234, D-11; registered with `410`). Every producer references the enum; no code is a
  literal any more.
- **The committed list** `backend/src/test/resources/api/problem-codes.txt`, one `<CODE> <status>`
  line per code, sorted. A new, renamed or removed code changes it in the same PR, so the change is
  reviewed.
- **The exchange's own registry stays separate.** Its nine codes are constants of
  `ExchangeProblemException`, frozen with the exchange contract (REQ-XCH, ADR-0216); the registry
  only asserts that no kernel code clashes with one of them.
- **The document lists the codes.** `ProblemDetail.code` is a string whose `x-problem-codes`
  extension and description list the registered values — never a required enum, because
  `theContractRequiredEnumsAreFrozen` would then freeze the list against every addition — beside
  `correlationId`, `errors` and `fieldErrors` (`{field, message}`); every `/api/**` operation
  documents `429` (`OpenApiProblemDetailsConfig`).

**Acceptance**

- [x] Every registered code is unique, also against the exchange's; the registry equals the committed
  list, code and status (`ProblemCodeRegistryTest`).
- [x] Every code `AppExceptionKind`, `GlobalExceptionHandler`, `BankConflictException` and the six
  filters declare is registered; no main source writes a code as a literal (a source scan whose four
  site shapes are each proven on a planted line); runtime probes through the real filter chain — an
  anonymous read, a wrong verb, an unreadable body, a path no controller serves — answer
  registered codes (`ProblemCodeRuntimeProbeTest`).
- [x] A planted enum repeating `NOT_FOUND` is caught.
- [x] Every registered code has a non-blank title and detail in every backend bundle
  (`messages`, `messages_de`, `messages_en`) under `problem.<code in lower case>`, or under the
  key its producer reads (`problem.data_integrity`, `problem.external_service`), and so has every key
  an `AppExceptionKind` or a `BankConflictException` reads; a planted bundle missing or blanking a
  key is caught (`ProblemCodeRegistryTest`).
- [x] Spring MVC's client-error exceptions answer their registered 4xx code, one test per type
  (`GlobalExceptionHandlerClientErrorTest`), and a missing parameter and an unacceptable media type
  do so through the real chain (`ProblemCodeRuntimeProbeTest`).
- [x] The committed document's `ProblemDetail` lists exactly the registered codes, keeps `code`
  optional and not an enum.
- [ ] One `ProblemCode` enum per module and the app generating its constants from the document —
  **open**, plan Phase 1 (ADR-0235) and the app.

**Enforced by:** `ProblemCodeRegistryTest`, `ProblemCodeRuntimeProbeTest`,
`GlobalExceptionHandlerClientErrorTest` (backend) ·
**Related:** REQ-API-004, REQ-API-007, REQ-API-009, ADR-0234, ADR-0235

### REQ-API-005 — Pagination & sorting

All list endpoints take Spring's `Pageable` and return a `PageResponse` wrapper (total
elements, pages, current page). **Whitelist allowed sort fields in the service** — never
pass user input directly to `Sort` (unstable sorting + information-disclosure risk). Build the
`Pageable` through `PaginationUtil`, which whitelists the sort field, appends `id` as a stable
tiebreaker, and clamps `size` to a **page ceiling**. The clamp bounds the result-set size; the
global query-execution timeout (REQ-DATA-009, finding SEC-03) bounds how long a heavy fetch may hold
a database connection.

**The kernel page policy** (plan D-18, amended 2026-10-03). The default ceiling is
`MAX_PAGE_SIZE` = **1 000**. A list opts out only explicitly, by passing `PageCeiling.LOAD_ALL`
(`LOAD_ALL_MAX_PAGE_SIZE` = 100 000), and only for a caller that requests a larger page. The value
was set from the callers in the code, not guessed: every frontend, Android-app and ingest call
requests at most 1 000 except eight, which are the reviewed opt-outs —

| Opt-out | Caller | Requested `size` |
| --- | --- | ---: |
| `GET /api/v1/materials/matrix` | frontend price matrix (`CachedCatalog.MATERIALS_MATRIX`) | 100 000 |
| `GET /api/v1/materials/prices-overview` | frontend materials page, one page | 10 000 |
| `GET /api/v1/materials/{id}/prices` | frontend materials page | 10 000 |
| `GET /api/v1/terminals` | frontend terminal catalogue, admin UEX page | 10 000 |
| `GET /api/v1/cities`, `/space-stations`, `/outposts`, `/pois` | frontend admin UEX page | 10 000 |

A caller that asks a non-opted-out list for more than 1 000 gets a page of 1 000 and the true
`totalPages`, so a page walk still reaches every row. `PageCeilingTest` pins the opt-outs to exactly
these eight handlers and fails on a new one; adding one is a reviewed decision that names its caller.

### REQ-API-006 — All times in UTC

Store/process as `Instant` or `OffsetDateTime`; convert to the user's local timezone in the
display layer only. Write serialization tests for timezone behaviour.

### REQ-API-007 — OpenAPI documentation

Every backend REST endpoint carries SpringDoc annotations (`@Operation`, `@ApiResponses`). **The
backend ships a committed OpenAPI document, the single API-documentation artifact for the
module** — kept in sync with controller changes and regenerated by its `OpenApiGeneratorTest`:

| Module  |              Committed document               |         Root document bean         |
|---------|-----------------------------------------------|------------------------------------|
| backend | `backend/src/main/resources/api/openapi.json` | `backend/.../config/OpenApiConfig` |

Beside it the generator writes the exchange's **internal relay document**,
`backend/src/main/resources/api/exchange-relay.openapi.json`: the 14 operations under
`/api/v1/exchange/**` that the ingest gateway relays to, split off the same generated model and
never served to a client (REQ-XCH-039, ADR-0216). `openapi.json` does not contain them.

The frontend serves HTML, not an API, and therefore has no document of its own. The ingest gateway
has no generated document either: its only API is the exchange, whose hand-written contract
`ingest/src/main/resources/api/exchange-v1.openapi.json` it serves at `/exchange/v1/openapi.json`
(REQ-XCH-011). *Amended 2026-09-28 (#2092 step 9, owner decision of the same day):* the ingest
module's springdoc document described the desktop extractor's two `/v1` endpoints; with them removed
it listed nothing, and springdoc, `OpenApiConfig`, the generated `openapi.json` and its generator
test were removed from the module.

The backend depends on springdoc **`-api`** (not `-ui`): the document is generated at
`/v3/api-docs`, no Swagger UI webjar is bundled, and `springdoc.api-docs.enabled=false` in its
`application-prod.yml` keeps the endpoint unreachable from a deployed environment. Its root
document declares the `bearer-jwt` security scheme, so a generated client knows every endpoint
expects a Keycloak JWT.

That regeneration MUST be **atomic** — serialize to a temporary sibling file and move it into place,
never write the document in place. `org.gradle.parallel=true` runs `:backend:test` alongside
`:frontend:test`, and four frontend contract tests (`DtoOpenApiContractTest`,
`FrontendDtoContractTest`, …) read this file with `Files.readString`. An in-place write truncates the
1.8 MB document and streams it back over hundreds of milliseconds, so a reader landing in that window
parses a cut-off document and fails with `UnexpectedEndOfInputException` — an intermittent red build
whose cause is nowhere near the test that reports it.

The generator also **asserts** the document's load-bearing parts before writing, so a controller
that silently stops being scanned fails the build instead of quietly shrinking the committed spec:
the `bearer-jwt` scheme and the document-wide requirement on it, exactly the two anonymous
operations (`GET /api/v1/app/version-policy`, `GET /api/v1/terms/document`), one domain tag and
one contract tier on every operation, a per-domain operation-count floor, no exchange relay path, no
dangling `$ref`, and unique schema names (REQ-API-018; `OpenApiDocumentAssertions`, `ExposedTypes`);
of the relay document exactly the 14 relay operations, all `exchange` and `T0` (REQ-XCH-039).

> [!note] Corrected 2026-10-03
> This paragraph described assertions (title, scheme, paths, schemas) that belonged to the removed
> ingest generator; the backend's `OpenApiGeneratorTest` asserted only a `200` until the
> assertions above were added with REQ-API-018 (rest-api-cut.md, *Findings*).

Regeneration MUST also be **reproducible**: the same tree must produce the same bytes, so a
`openapi.json` diff always means a real API change. The one thing that broke this was
accessor-derived schema properties. springdoc harvests bean accessors, and a Jakarta `@AssertTrue`
cross-field guard looks exactly like a boolean getter — so `InventoryItemCreateDto` published
`catalogReferenceValid`, `missionFreeForGameItem` and `qualityConsistentWithCatalog`, and the two
bank request records published `splitConfigConsistent`. Accessors are harvested in
`Class#getDeclaredMethods()` order, which the JVM does not guarantee (declared *fields* are stable in
practice, methods are not), so those properties permuted between JVM runs and `./gradlew check`
rewrote the 1.8 MB document on a tree with no API change at all — measured 2026-08-03: the ordering
changed in 16 of 24 consecutive commits touching the file, and four regenerations from one tree gave
three different orderings. Such churn is corrosive precisely because this document is a meaningful
signal: it is never hand-edited, it must track the controllers, and the PR template gates on it.

**Therefore: a validation guard or any other derived accessor that is not part of the payload MUST
carry `@Schema(hidden = true)`.** It documents a field no client may send, and it is the only part of
the document whose order is unstable. `OpenApiDerivedPropertyTest` enforces this from both ends —
every `@AssertTrue` method on a type published under `components.schemas` must be hidden, and the
committed document must contain no such property. Prefer `@Schema(hidden = true)` over `@JsonIgnore`
here: it removes the property from the document without touching Jackson or Bean Validation.

**And the committed document MUST be the one the build generates — CI enforces it** (2026-09-23,
audit item BLD-CI-09). Until then nothing did: `OpenApiGeneratorTest` rewrote the file during
`test` and carried no assertion that the rewrite changed nothing, so a controller change committed
without its regenerated document passed every check, and `ExternalContractTest` and the frontend
contract tests then compared against a stale contract. Two parts close it:

- both test profiles set `springdoc.writer-with-order-by-keys: true`, so every object in the
  document is written in key order — a byte-stable output that does not depend on reflection order
  (the `@Schema(hidden = true)` rule above stays: it keeps derived properties out of the document at
  all, which ordering cannot do);
- after a green `./gradlew build`, `ci.yml` runs `git diff --exit-code` on `openapi.json` and the
  relay document and fails the job on any difference. `.gitattributes` pins `*.json` to LF, so the
  comparison is on normalised content and a generator writing CRLF on Windows cannot produce a diff
  by itself;
- the generator compares each document with its committed file itself, line ends ignored: outside
  CI it rewrites a stale file, in CI (`CI=true`) it fails naming it (`CommittedOpenApi.refresh`).
  It writes LF on every platform.

### REQ-API-018 — Every operation names its domain and its contract tier; every schema name one type

The committed `openapi.json` is the review surface of the domain cut (ADR-0234, plan §5.10), so it
says, per operation, which domain owns it and how frozen it is, and it describes every exposed type
under a name of its own.

- **One tag per domain.** `OpenApiDomainConfig.domainTagCustomizer` (an `OperationCustomizer`)
  replaces every operation's tags with exactly one, its controller's domain, and mirrors it as
  `x-domain`; the document's `tags` list holds those domains and nothing else, and springdoc's
  class-name tags are off (`springdoc.auto-tag-classes: false`). The domains are the 22 of the REST
  API cut's *Today's surface* (`admin-system`, `audit`, `bank`, `blueprint`, `catalogue`,
  `dashboard`, `exchange`, `hangar`, `identity`, `inventory`, `joborder`, `leadership`, `livesync`,
  `materialexchange`, `mission`, `notification`, `operation`, `orgchart`, `orgunit`,
  `personalinventory`, `promotion`, `refinery`). The controller-to-domain table is explicit in main
  code, `config/ApiDomains`: the backend domain map of plan guard G-09
  (`backend/src/test/resources/architecture/domain-map.txt`, REQ-MOD-001) lives in test scope, where
  the running application cannot read it, and the REST cut's 22 domains are not the map's 26 modules.
  `ApiDomainsMatchTheDomainMapTest` therefore holds the table to the map: a controller's tag is its
  module, or the API domain one of five reviewed entries names — the `privacy` module's endpoints
  are tagged `identity`, the `admin` module's `admin-system`, and `LeitungController`,
  `BlueprintController` and `UexLocationController` keep the domain the REST cut gives them —, and
  an entry no controller needs fails. One published document stays — the app vendors it and the
  frontend generates its test types from it; per-domain views are filtered from it. The exchange
  relay surface alone is fenced into its own internal document (REQ-XCH-039), so the `exchange`
  domain's 14 relay operations are counted there and its 21 member and admin operations here.
- **One contract tier per operation**, as `x-contract-tier` (REQ-API-001 names the tiers):
  `T0` never breaks, `T1` is the Android contract, `T2` is web-only. The single source is
  `backend/src/main/resources/api/contract-tiers.txt`, one `<T0|T1> <VERB> <path>` line per frozen
  operation; every operation it does not name is `T2`. `ExternalContractTest` holds it to the record
  that never changes (`T0`: the version gate, the 14 exchange relay operations, the two streams and
  `POST /api/v1/live-sync/changed` — the SPI endpoint is T0 too but not in the document) and to the
  frozen set (`T1` = frozen set minus `T0`, both directions), checks the committed documents — the
  published one and the relay document — carry each tier, and refuses a declared-break ledger line
  on a `T0` operation (REQ-API-017). The previous-release comparison also compares every operation
  the previous documents mark `T0` or `T1`, reading a release's relay document together with its
  `openapi.json`. The tier is data, not an exposure switch: the edge allow-list stays a reviewed file.
- **Unique schema names.** Every name under `components.schemas` belongs to exactly one exposed Java
  type or an explicit `@Schema(name = …)`. `ExposedTypes` walks every main-source controller's
  handlers — return types, `@RequestBody` / `@RequestPart` parameters, generic arguments, record
  components and fields — and groups the reached own types by the name springdoc gives them. The
  collisions found (2026-10-03) are named apart: the nested `Op` of the stock, blueprint and ship
  change sets, and `Provenance` of the blueprint change set and the blueprint DTO. `Skipped` was
  named as a third collision by the audit, but its namesake is a service record no controller
  exposes; the exposed one gets its name explicitly all the same. The name the document already
  showed — and that the app's vendored copy and its generated models carry — stays on the type that
  had it (`Op` the stock op, `Provenance` the change-set provenance, `Skipped` the undo result); the
  others became
  `ExchangeBlueprintOp`, `ExchangeShipOp` and `ExchangeBlueprintProvenance`, so the blueprint and
  ship change sets are documented correctly for the first time (and the ship op's `Insurance`
  appears). `use-fqn` stays off: it would rename every schema and every generated app model.

**Acceptance**

- [x] Every operation carries exactly one tag, equal to `x-domain`, from the 22 domains; the tag list
  is the domains; every operation carries the tier its list entry or `T2` gives it — asserted by the
  generator before it writes and on the committed document (`OpenApiDocumentAssertionsTest`).
- [x] Each domain's operation count has a floor at today's count (573 operations: 559 in
  `openapi.json`, the 14 relay operations in the relay document, REQ-XCH-039).
- [x] `T0` equals its record, `T1` equals the frozen set minus `T0`, no ledger line breaks `T0`
  (`ExternalContractTest.theContractTiersMatchTheFrozenSet`).
- [x] No two exposed types share a schema name; the scan reaches at least 441 names, and the domain
  table names exactly the 99 controllers (`OpenApiSchemaNamesTest`).
- [x] Each guard proven able to fail: a planted document with every fault, planted tier lines, planted
  fixture controllers with two `Op` records, the real ship op without its explicit name, and a dropped
  `T1` line.

**Enforced by:** `OpenApiGeneratorTest`, `OpenApiDocumentAssertionsTest`, `OpenApiSchemaNamesTest`,
`ExternalContractTest` (backend) ·
**Related:** REQ-API-001, REQ-API-007, REQ-API-009, REQ-API-017, ADR-0234

### REQ-API-008 — Shared controller boilerplate (argument resolvers & response helpers)

Cross-cutting controller boilerplate is factored into `backend/.../web` rather than re-hand-rolled
per controller (S11, #917). Use the shared seams; do not re-derive them inline:

- **`@CurrentUserId UUID`** — resolved by `CurrentUserArgumentResolver` from the authenticated
  caller's JWT `sub` claim (read via `NativeWebRequest#getUserPrincipal()`, so no
  `SecurityContextHolder` coupling is introduced). A missing/non-JWT principal, a missing or blank
  subject, or a non-UUID subject each raise `AccessDeniedException` → HTTP 403. It replaces the
  per-controller `requireSub(JwtAuthenticationToken)` guards, and — since ADR-0142 point 2 (#1640)
  — its String-typed twin `@CurrentUserSub`, which handed the same value out unparsed under the
  identity provider's name for it. A controller must not read `jwt.getSubject()` itself.
- **`@UserZone ZoneId`** — resolved by `UserZoneArgumentResolver` from the `X-User-Time-Zone`
  header, tolerating an absent/blank/invalid IANA zone as `null` (the report services fall back to
  UTC). Each site re-declares the header for the OpenAPI document via a method-level `@Parameter`.
- **`PdfResponses.pdfAttachment(byte[], filename)`** — builds the `application/pdf` +
  attachment `Content-Disposition` download response the PDF-export endpoints shared.

The two resolvers are wired in `WebMvcConfig#addArgumentResolvers`; the JWT-subject annotations are
hidden from the generated OpenAPI document via `SpringDocUtils.addAnnotationsToIgnore` in
`OpenApiConfig` (they are as invisible as the `JwtAuthenticationToken` parameters they replaced).

### REQ-API-009 — The external contract set is frozen against in-place change

Endpoints a **shipped client** consumes are a contract, because the client cannot be redeployed
with the server. A released Android build sits on a member's phone for weeks — distribution is
GitHub Releases plus Obtainium, so nothing pushes a silent update — and a field the server stops
sending is a crash in a version the operator cannot fix forward. REQ-API-001's internal-only
carve-out rests on frontend and backend deploying atomically; that premise does not hold here, so
the carve-out does not apply to this set (ADR-0136).

**The set** is enumerated in `ExternalContractTest` and grows **one app phase at a time**, in the
same change as the vhost allow-list that exposes those paths — and **only as an operation is
actually consumed**. Freezing an endpoint the client does not yet read would buy the backend a
constraint for nothing and record a guess about which fields matter.

|                                                 Operation                                                  |                                                                                                                                                                                                                                                                          Response fields a client may rely on                                                                                                                                                                                                                                                                         |
|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/terms/status`                                                                                 | `accepted`, `currentVersion`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `POST /api/v1/terms/acceptance`                                                                            | `accepted`, `currentVersion`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `GET /api/v1/terms/document`                                                                               | `version`, `title`, `intro`, `sections`, `lastUpdated`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `GET /api/v1/me/active-org-unit`                                                                           | `orgUnitId`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/me/capabilities`                                                                              | `canSeeBlueprintOverview`, `canViewJobOrders`, `canViewOwnJobOrders`, `canViewBankStaff`, `canManageBank` — the bank pair is server-derived through the role hierarchy, because the me-response carries role **display** names and the bank roles carry no permissions                                                                                                                                                                                                                                                                                                                |
| `GET /api/v1/users/me/registration-status`                                                                 | `approvalStatus`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/users/me`                                                                                     | `id`, `isLogistician`, `isMissionManager`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `GET /api/v1/users/me/memberships`                                                                         | `orgUnitId`, `orgUnitName`, `orgUnitShorthand`, `kind`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `GET`/`PUT …/users/me/payout-preference`                                                                   | `defaultPayoutPreference`, `version` — **request** requires `preference`, `version`. Frozen as a PAIR with the read: the two me-scoped settings are columns of one `User` row sharing one optimistic-lock version, and a client that could write but not read would echo `0`                                                                                                                                                                                                                                                                                                          |
| `GET`/`PUT …/users/me/blueprint-sharing`                                                                   | `shareBlueprintsGlobally`, `version` — **request** requires both; same shared version as the row above                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `GET`/`PUT …/users/me/rsi-handle` | `rsiHandle`, `version` — **request** requires `version` only; a `null` or blank handle clears it |
| `PUT …/users/me/read-announcement/{announcementId}`                                                        | `lastReadAnnouncementId` — the one name the app reads out of the `UserDto` it answers with, to confirm the „UNGELESEN“ band may stay down. No request body                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/missions/search`                                                                              | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `status`, `meetingTime`, `plannedStartTime`, `actualStartTime`, `plannedEndTime`, `isInternal`, `operation`, `owningSquadron`, `meetingPoint`                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/missions/{id}`                                                                                | `id`, `name`, `description`, `status`, `meetingTime`, `plannedStartTime`, `actualStartTime`, `plannedEndTime`, `isInternal`, `meetingPoint`, `operation`, `owningSquadron`, `partyLeadUser`, `partyLeadGuestName`, `registeredParticipants`, `checkedInParticipants`, `participants`, `assignedUnits`, `steps`, `objectives`, `frequencies`; participant `user`, `startTime`, `payoutPreference`                                                                                                                                                                                      |
| `GET /api/v1/missions/{missionId}/finance-entries`                                                         | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `type`, `amount`, `note`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `GET /api/v1/missions/{missionId}/finance-entries/summary`                                                 | `total`, `incomeSum`, `incomeCount`, `expenseSum`, `expenseCount`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/operations/search`                                                                            | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `status`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `GET /api/v1/operations/{id}`                                                                              | `id`, `name`, `description`, `status`, `payoutPreliminary`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/operations/{id}/finance-summary`                                                              | `operationId`, `totalSum`, `truncated`, `missions`; row `missionId`, `missionName`, `totalSum`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `GET /api/v1/inventory/aggregated`                                                                         | envelope; row `material`, `amount`, `quality`, `maxQuality`; nested `name`, `quantityType`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/inventory/all/grouped`                                                                        | `material`, `totalAmount`, `averageQuality`, `maxQuality`, `stacks`; nested `user`, `location`, `personal`, `entryCount`                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GET /api/v1/orders`                                                                                       | envelope; row `id`, `displayId`, `status`, `priority`, `type`, `createdAt`, `materials`, `redacted`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/orders/{id}`                                                                                  | as the row, plus `comment`, `aggregatedMaterials`, `assignees`, `handovers`, `requestingOrgUnit`, `responsibleOrgUnit`, `version`, `canEdit`; nested `user.effectiveName`, `note`, `version` (the assignee edge's own). `canEdit` is `isLogisticianOrAbove() && canEditJobOrder(id)` — the app draws three writes from it, and an **absent** flag opens the screen rather than closing it                                                                                                                                                                                             |
| `GET /api/v1/org-units/bank/balances`                                                                      | `accountId`, `accountNo`, `accountName`, `balance`, `delta30d`, `sparkline`, `orgUnitName`, `canRequest`, `approvalLimit`, `approvalExempt` — the last three are what the request sheet is gated and explained by, per caller and per account                                                                                                                                                                                                                                                                                                                                         |
| `GET /api/v1/org-units/bank/accounts/{id}`                                                                 | `detail`, `delta30d`, `bookingCount`; nested `account.name`, `account.accountNo`, `account.balance`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/org-units/bank/accounts/{id}/transactions`                                                    | envelope; row `postingId`, `transactionId`, `type`, `amount`, `note`, `createdAt`, `holderHandle`, `counterpartyHandle`, `transferFee`, `reversedTransactionId`                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/hangar/my-ships`                                                                              | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `shipType`, `insurance`, `location`, `fitted`, `version`; nested `manufacturer`                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `GET /api/v1/hangar/squadron-overview`                                                                     | envelope as above; row `shipType`, `count`, `fittedCount`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `GET /api/v1/announcement`                                                                                 | `content`, `updatedAt` — **and a `204` with no body at all when nothing is announced**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `GET /api/v1/notifications`                                                                                | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `type`, `params`, `entityType`, `entityId`, `read`, `createdAt`                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/notifications/unread-count`                                                                   | `count`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `GET /api/v1/notifications/stream`                                                                         | *(a stream, not a schema — the frozen part is the path, the verb and the event names)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `POST /api/v1/notifications/{id}/read`                                                                     | `id`, `read`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `POST /api/v1/notifications/read-all`                                                                      | `affected`, `unreadCount` — the count settles the badge from the same response that changed it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `DELETE /api/v1/notifications/{id}`                                                                        | *(204, no body — the frozen part is the path and the verb)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `DELETE /api/v1/notifications/read`                                                                        | `affected`, `unreadCount`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `GET /api/v1/operations/{id}/payouts`                                                                      | `totalDonations`, `payouts`; row `participantId`, `participantName`, `payoutPreference`, `shareAmount`, `donatedAmount`, `payoutAmount`, `paidOut`                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `GET /api/v1/personal-inventory`                                                                           | envelope; row `id`, `name`, `note`, `locationUexId`, `locationType`, `locationName`, `quantity`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `POST /api/v1/personal-inventory`                                                                          | `id`, `name`, `quantity`, `locationUexId`, `locationType`, `version` — **request** requires `name`, `quantity`, `locationUexId`, `locationType`                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/personal-inventory/{id}`                                                                      | as the list row                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `PUT /api/v1/personal-inventory/{id}`                                                                      | as the create — **request** additionally requires `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `DELETE /api/v1/personal-inventory/{id}`                                                                   | *(204, no body — the frozen part is the path and the verb)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/uex/locations/search`                                                                         | `uexId`, `type`, `name`, `starSystemName`, `parentName`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `GET /api/v1/personal-blueprints`                                                                          | envelope; row `id`, `productKey`, `productName`, `acquiredAt`, `note`, `removable`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `POST /api/v1/personal-blueprints`                                                                         | `id`, `productKey`, `productName`, `version` — **request** requires `productKey`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `PUT /api/v1/personal-blueprints/{id}`                                                                     | as the row — **request** requires `version` only; note and date are optional                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `DELETE /api/v1/personal-blueprints/{id}`                                                                  | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/personal-blueprints/{id}/recipe`                                                              | `productName`, `variantCount`, `requirementGroups`, `ingredients`; ingredient `kind`, `name`, `quantityScu`, `quantityUnits`, `minQuality`, `quantityType` — **both** quantity scales, so a client renders the one its column is labelled for instead of converting                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/personal-blueprints/craftability`                                                             | `blueprintId`, `recipeResolved`, `craftable`, `craftableWithRefinery`, `limitingMaterialName`, `limitingMaterialNameWithRefinery`, `materials`; row `materialName`, `requiredScu`, `availableScu`, `missingScu`, `quantityType`                                                                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/blueprints/products/search`                                                                   | `productKey`, `name`, `manufacturerName`, `ownedByCurrentUser`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `POST /api/v1/hangar/ships`                                                                                | `id`, `name`, `shipType`, `insurance`, `location`, `fitted`, `version` — **request** requires `insurance`, `shipTypeId`                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `PUT /api/v1/hangar/ships/{id}`                                                                            | as the create; the app additionally sends `version`, which the schema does not demand                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `DELETE /api/v1/hangar/ships/{id}`                                                                         | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/ship-types`                                                                                   | envelope; row `id`, `name`, `manufacturer` — **401** since 2026-09-06 (REQ-SEC-052)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/locations/home-locations`                                                                     | `id`, `name`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `POST /api/v1/inventory`                                                                                   | `id`, `material`, `location`, `amount`, `quality`, `personal` — **request** requires `amount`, `locationId`                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/inventory/all/stack/entries`                                                                  | envelope; row `id`, `material`, `location`, `amount`, `quality`, `personal`, `note`, `user`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/inventory/my-inventory/grouped` | `material` or `gameItem`, `totalAmount`, `averageQuality`, `maxQuality`, `stacks`; nested `user`, `location`, `quality`, `personal`, `stolen`, `owningSquadron`, `entryCount` — „Mein Lager", the caller's own rows only; `personal`, `stolen` and `owningSquadron` are part of a stack's key and are echoed back to address its entries |
| `GET /api/v1/inventory/my-inventory/stack/entries` | envelope; row `id`, `material` or `gameItem`, `location`, `amount`, `quality`, `personal`, `stolen`, `owningSquadron`, `canEdit`, `note`, `version` — `version` is what every write on the row echoes |
| `GET /api/v1/inventory/my-inventory/entry-ids` | a bare array of entry ids — the select-all of the selection mode, filtered like the grouped read |
| `POST /api/v1/inventory/{id}/book-out`                                                                     | as above — **request** requires `amount`, `version`; its `type` is `DISCARD` / `TRANSFER` / `SELL`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `POST /api/v1/inventory/{id}/personal-rebook`                                                              | as above — **request** requires `amount`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `PUT /api/v1/inventory/{id}/note`                                                                          | `id`, `note` — **request** requires `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `GET /api/v1/materials/search`                                                                             | envelope; row `id`, `name`, `quantityType` — **401** since 2026-09-06 (REQ-SEC-052)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/locations/search`                                                                             | envelope; row `id`, `name` — **401** since 2026-09-06 (REQ-SEC-052)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/users/search`                                                                                 | envelope; row `id`, `effectiveName` — authenticated, and always was                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/orders/lookup`                                                                                | `id`, `displayId`, `handle`, `requiredMaterialIds`, `requiredGameItemIds` — the Auftrag picker in the booking sheet. Swallowed on failure by design, so a lost field empties the picker rather than failing anything                                                                                                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/missions/lookup`                                                                              | `id`, `name`, `status` — **401** since 2026-09-06. It answered **403** while `GET /missions/**` was `permitAll` and the request was dispatched to a method guard; with the URL rule gone (REQ-SEC-052) it is turned away at the entry point                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/operations/lookup`                                                                            | `id`, `name` — the Operation picker on the Einsatz's Kern section                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/job-types`                                                                                    | envelope `content`; row `id`, `name`, `active` — **401** since 2026-09-06 (REQ-SEC-052), a role-name catalogue like `/ship-types`. `active` is read as a FILTER, so losing it returns every retired Funktion to both pickers with nothing failing                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/materials/{id}/terminals`                                                                     | `terminalId`, `terminalName`, `priceSell` — **401** since 2026-09-06 (REQ-SEC-052)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `POST /api/v1/orders/{id}/assignees/{userId}`                                                              | `id`, `assignees`, `version`; nested `user.effectiveName`, `note`, `version` — self-assignment is open to every member                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `DELETE /api/v1/orders/{id}/assignees/{userId}`                                                            | as the add                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `PUT /api/v1/orders/{id}/assignees/{userId}/note`                                                          | as the add — **request** requires nothing; the `version` it carries is the **assignee edge's**, not the order's                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `DELETE /api/v1/orders/{id}/assignees/{userId}/note`                                                       | as the add                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/orders/{id}/material-collection`                                                              | `inventoryEntryId`, `version`, `ownerName`, `ownerId`, `location`, `locationId`, `materialName`, `quality`, `quantity`, `allocatedQuantity`, `delivered` — the row's own `version` is the optimistic lock the delivered flag echoes; `ownerName`/`location` are redacted to null for a requesting-side viewer                                                                                                                                                                                                                                                                         |
| `DELETE …/orders/{id}/inventory/{entryId}/unlink`                                                          | *(204, no body)* — the earmark goes, the stock stays                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `DELETE …/orders/{id}/materials/{materialId}`                                                              | *(204, no body)* — removes a required material and every row that pointed at it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/orders/material-demand`                                                                       | `groups`; group `orgUnit.id`/`name`/`shorthand`, `materials`; row `material`, `qualityRequirement`, `requiredAmount`, `bookedAmount`, `claimedAmount`, `outstandingAmount`, `orders` — the per-order share nests a third level, past the guard's two, so its `jobOrderId`/`displayId`/`status` are deliberately not recorded                                                                                                                                                                                                                                                          |
| `GET /api/v1/orders/{id}/item-stock`                                                                       | `gameItem.id`/`name`, `orderedAmount`, `manufacturedAmount`, `allocatedTotal` — a group without `gameItem.id` is dropped, so the chip does not blank, the row disappears                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GET /api/v1/orders/{id}/claims`                                                                           | `material.id`/`name`/`quantityType`, `qualityRequirement`, `requiredAmount`, `claimedAmount`, `openRemaining`, `claims`; claim `id`, `claimingOrgUnit.shorthand`/`name`, `amount` — `openRemaining` is what the tab is for; `material.id` keys the upsert                                                                                                                                                                                                                                                                                                                             |
| `POST /api/v1/orders/{id}/claims`                                                                          | *(answer discarded; the tab is re-read)* — **request** requires `amount`, `claimingOrgUnitId`, `materialId`, `qualityRequirement`, the last a frozen enum (`GOOD`, `NONE`)                                                                                                                                                                                                                                                                                                                                                                                                            |
| `DELETE /api/v1/orders/{id}/claims/{claimId}`                                                              | *(204, no body)* — withdrawing a Zusage                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `GET /api/v1/orders/{id}/materials/{materialId}/inventory`                                                 | `id`, `user.effectiveName`/`displayName`, `location.name`, `quality`, `amount`, `jobOrderAllocations.jobOrderId`, `version` — the allocation slice is the ceiling the Übergabe-Sheet caps against; without it a row offers its whole stack                                                                                                                                                                                                                                                                                                                                            |
| `POST /api/v1/orders/{id}/handovers`                                                                       | *(answer discarded)* — **request** requires `handoverTime`, `items`, `recipientHandle`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `POST /api/v1/orders/{id}/item-handovers`                                                                  | *(answer discarded)* — **request** requires `entries`, `handoverTime`, `recipientHandle`. The wire name is `entries`; the app's generated property is `propertyEntries`, and the wire name is what is frozen                                                                                                                                                                                                                                                                                                                                                                          |
| `PUT /api/v1/orders/{id}/items`                                                                            | *(answer discarded)* — **request** requires `items`: an edit that omits it is not an edit, it is an emptying                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `POST /api/v1/orders/{id}/items/{itemId}/production`                                                       | *(answer discarded)* — **request** requires `amount`, `bookIn`, `consumption`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `PUT /api/v1/orders/{id}/priority`                                                                         | the whole Auftrag, same set as the detail read — **no body at all**: the position is the query parameter `priority`, and no `version` is sent because the service reorders the queue under a pessimistic lock                                                                                                                                                                                                                                                                                                                                                                         |
| `POST /api/v1/inventory/bulk-checkout`                                                                     | *(answer discarded; the list is re-read)* — **request** requires `itemIds`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `POST /api/v1/inventory/bulk-rebook`                                                                       | `rebooked`, `skipped` — the two counters the screen reports; lose one and a bulk move that worked reads as one that did nothing. **request** requires `itemIds`, `mode`, the latter a frozen enum (`LOCATION`, `PERSONALIZE`, `DEPERSONALIZE`) of which the app sends only `LOCATION`                                                                                                                                                                                                                                                                                                 |
| `POST /api/v1/inventory/bulk-org-unit` | `changed`, `skipped` — **request** requires `itemIds` |
| `POST /api/v1/inventory/{id}/org-unit` | `id`, `material`, `location`, `amount`, `personal` — no required request field |
| `POST /api/v1/inventory/bulk-stolen` | `changed`, `skipped` — **request** requires `itemIds`, `stolen` |
| `POST /api/v1/inventory/{id}/stolen` | `id`, `material`, `location`, `amount`, `personal`, `stolen` — **request** requires `stolen` |
| `POST` · `PATCH` · `DELETE /api/v1/inventory/{id}/allocation`                                              | the whole row, one shared set: `id`, `material.name`/`id`/`quantityType`, `location.name`/`id`, `user.effectiveName`/`id`, `amount`, `quality`, `personal`, `owningSquadron`, `canEdit`, `note`, `version`, `jobOrderAllocations.jobOrderId`/`jobOrderDisplayId`/`amount`, `jobOrderRest`, `missionAllocations.missionId`/`missionName`/`missionPlannedStartTime`/`amount`, `missionRest` — the two `…Rest` fields are what a new earmark is capped against. **request** requires `field` (frozen enum `JOB_ORDER`, `MISSION`) and `targetId`                                         |
| `PATCH /api/v1/missions/{id}/core`                                                                         | the whole Einsatz, same set as the detail read — **request** requires `name`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `PATCH /api/v1/missions/{id}/schedule`                                                                     | as `core` — **request** requires `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `PATCH /api/v1/missions/{id}/flags`                                                                        | as `core` — **request** requires `isInternal`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `PUT /api/v1/missions/{id}/party-lead`                                                                     | as `core` — **request** requires `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `POST …/missions/{id}/participants/by-id/slim`                                                             | *(answers discarded; the Einsatz is re-read)* — the manager-only add-by-id (REQ-MISSION-020, ADR-0170 amendment 2026-09-22); **request** requires `userId` and carries nothing else |
| `GET /api/v1/missions/{id}/unit-ship-options`                                                              | `id`, `name`, `shipType` — **401** since 2026-09-06; it answered **403** while `GET /missions/**` was `permitAll` (REQ-SEC-052). A ship without an id is dropped; the type is what tells two Carracks apart                                                                                                                                                                                                                                                                                                                                                                           |
| `POST` · `PUT` · `DELETE …/missions/{id}/units/…/slim`                                                     | *(answers discarded; the Einsatz is re-read)* — **request** requires `name` on the create and the edit, `participantId` on the crew add, nothing on the rest. The full-DTO twins were deprecated (announced sunset 2026-10-20) and deleted early on 2026-09-22 by owner decision, never frozen                                                                                                                                                                                                                                                                                                                                    |
| `POST /api/v1/missions/{id}/frequencies/custom/slim`                                                       | `id`, `frequencyType`, `name`, `value` — the one write in this group whose answer IS read, because it has no plain twin and never went through the re-read. **request** requires `name`, `value`                                                                                                                                                                                                                                                                                                                                                                                      |
| `DELETE …/frequencies/{frequencyId}/slim`                                                                  | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `POST` · `DELETE …/managers/{userId}/slim`                                                                 | *(answers discarded)* — the member is named in the path and the POST carries no body at all                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `…/missions/{id}/steps/…/slim` (5 operations)                                                              | row `id`, `title`, `meta`, `done` — the section is spliced onto the Einsatz as last read, because the server decides the order. **request** requires `stepsVersion` throughout, plus `title` on create/edit, `done` on the tick and `stepIds` on the reorder. The **delete** carries `stepsVersion` as a QUERY parameter — lose it and the delete becomes unconditional rather than failing                                                                                                                                                                                           |
| `…/missions/{id}/objectives/…/slim` (4 operations)                                                         | row `id`, `title`, `kind` — `kind` separates a Primärziel from a Nicht-Ziel, so it is the structure of the section and not a label on it. **request** requires `objectivesVersion` throughout, plus `kind` (frozen enum `PRIMARY`, `SECONDARY`, `NON_GOAL`) and `title` on create/edit, `objectiveIds` on the reorder; the delete carries `objectivesVersion` as a QUERY parameter                                                                                                                                                                                                    |
| `GET /api/v1/materials/prices-overview`                                                                    | envelope `content`; row `id`, `name`, `category`, `minPriceBuy`, `maxPriceSell`, `isIllegal` — **401** since 2026-09-06; it answered 200 anonymously until REQ-SEC-032 was widened to cover it                                                                                                                                                                                                                                                                                                                                                                                        |
| `GET /api/v1/materials/{id}`                                                                               | `id`, `name`, `type`, `quantityType`, `category`, `isIllegal` — **401** since 2026-09-06. It was anonymous by decision (catalogue only, no price, the same fields `/materials/search` published) until REQ-SEC-052 made the public surface an enumerated list of four backend paths, and no catalogue is on it                                                                                                                                                                                                                                                                        |
| `GET /api/v1/materials/{id}/prices`                                                                        | envelope `content`; row `id`, `terminalName`, `priceBuy`, `priceSell` — **401** since 2026-09-06, same reason                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `GET /api/v1/materials/profit-calculation`                                                                 | `materialName`, `minBuyPrice`, `maxSellPrice`, `profitPerScu`, `fullLoadCost`, `maxProfitFullLoad`, `marginPercent` — **401** since 2026-09-06; it answered **500** anonymously before, which is dispatch rather than a gate. `starSystemNames` is a repeated parameter and an absent list means every system, so losing it narrows the answer instead of failing it                                                                                                                                                                                                                  |
| `GET /api/v1/terminals`                                                                                    | envelope `content`, `totalPages`; row `starSystemName` — the app page-walks the whole catalogue for that one field, which is what its star-system filter offers                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/material-exchange/released-item-ids`                                                          | *(a list of ids)* — the request is the `ids` parameter and the answer is its subset                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `POST /api/v1/material-exchange/item-offers`                                                               | *(answer discarded)* — **request** requires `productKey`, `quantity`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `POST /api/v1/material-requests/item`                                                                      | as the offer — **request** requires `productKey`, `quantity`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `PUT /api/v1/material-exchange/offers/{id}/remark`                                                         | *(answer discarded)* — **request** requires `offeredAmount`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `PUT /api/v1/material-requests/{id}`                                                                       | *(answer discarded)* — **request** requires `desiredAmount`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `POST /api/v1/personal-blueprints/import/preview`                                                          | `entries`; entry `externalName`, `status`, `productKey`, `productName`, `suggestedAcquiredAt` — **request** requires the `file` part. The member edits this answer and sends it back to `/import/apply`, so a lost field silently drops a resolution                                                                                                                                                                                                                                                                                                                                  |
| `POST /api/v1/personal-blueprints/import/apply`                                                            | `added`, `skipped`, `alreadyOwned` — **request** requires `resolutions`. Frozen WITH the preview: the audit filed it as latent and then work-destroying                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `POST /api/v1/personal-blueprints/batch`                                                                   | `added`, `skippedAlreadyOwned`, `skippedUnresolved` — **request** requires `productKeys`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GET /api/v1/personal-blueprints/overview`                                                                 | envelope `content`, `page`, `totalPages`, `totalElements`; row `productKey`, `productName`, `ownerCount`                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GET /api/v1/personal-blueprints/overview/owners`                                                          | `ownerName`, `orgUnitMember` — addressed by `productKey`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `POST /api/v1/hangar/import/fleetview`                                                                     | `importedCount`, `skippedCount`, `duplicateCount` — the three counters the screen reports; lose one and a successful import reads as one that did nothing. **request** requires the `file` part                                                                                                                                                                                                                                                                                                                                                                                       |
| `POST /api/v1/hangar/ships/home-location`                                                                  | *(answer discarded)* — **request** requires `locationId`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GET /api/v1/settings/{key}`                                                                               | `value` — **401** since 2026-09-06 (REQ-SEC-052); two integers that were anonymous by design in the same `permitAll` block as `/locations`. The app parses it as a number and falls back to a built-in default when the read fails, which is exactly why the failure went unnoticed. The `PUT` on the same path is not admitted on the API vhost (`404`, REQ-API-021)                                                                                                                                                                                                                 |
| `PATCH /api/v1/inventory/{id}/delivered`                                                                   | *(response unread)* — **request** requires `delivered`, `jobOrderId`, `version`; the version is the **row's**, and the gate is `canEditInventoryItem`, not the order's                                                                                                                                                                                                                                                                                                                                                                                                                |
| `PUT /api/v1/orders/{id}/status`                                                                           | `id`, `status`, `version` — **request** requires `status`, `version`; `status` is `OPEN` / `IN_PROGRESS` / `REJECTED` / `COMPLETED`, and the operation needs `LOGISTICIAN` + per-order scope                                                                                                                                                                                                                                                                                                                                                                                          |
| `PUT /api/v1/orders/{id}`                                                                                  | as the detail read, through the **same** mapper — **request** requires `materials`. Its carve-out is method-scoped: the backend serves `DELETE` on this path too, the app sends none, and the edge keeps that at `405`                                                                                                                                                                                                                                                                                                                                                                |
| `PUT /api/v1/operations/{id}`                                                                              | *(response unread)* — **request** requires `name`, `status`, `version`; `status` is `PLANNED` / `ACTIVE` / `COMPLETED` / `CANCELED`. Same method-scoped carve-out, same reason                                                                                                                                                                                                                                                                                                                                                                                                        |
| `POST /api/v1/operations` | `id` (the app opens the new Operation by it) — **request** requires `name`, `status`; `description` and `owningOrgUnitId` are optional, and the operation needs `MISSION_MANAGER`. Method-scoped carve-out: the edge admits exactly this verb on exactly this path (since 2026-10-01); `GET`, `PUT`, `DELETE` and any near-miss path stay `404` |
| `PUT /api/v1/orders/{id}/requested` | *(response unread)* — **request** requires `materials`; the requester's edit of a material order before its first delivery. Frozen 2026-10-03 from the app's call list (REQ-API-016), and admitted by the edge the same day: method-scoped like `POST /api/v1/operations`, so every other verb and any near-miss path stays `404` |
| `POST /api/v1/orders` | as the detail read, through the same mapper — **request** requires `materials`. Frozen 2026-10-03 from the app's call list |
| `GET /api/v1/materials/matrix` | envelope; row `materialId`, `materialName`, `terminalId`, `terminalName`, `starSystemName`, `priceBuy`, `priceSell` — addressed by `page`, `size`, `sort`. Frozen 2026-10-03 from the app's call list |
| `POST /api/v1/bank/accounts` · `POST /api/v1/bank/holders` · `PATCH /api/v1/bank/holders/{id}` | the account (`id`, `accountNo`, `name`, `type`, `status`, `balance`, `version`) and the holder (`id`, `userId`, `handle`, `active`, `totalHeld`, `version`) — **requests** require `name`, `type` (`type` a frozen required enum); `userId`; `active`, `version`. Frozen 2026-10-03 from the app's call list |
| `PUT` · `DELETE /api/v1/refinery-orders/{id}` | *(response unread)* — the `PUT` **request** requires `goods`, `location`. Frozen 2026-10-03 from the app's call list |
| `DELETE /api/v1/hangar/ships` · `DELETE /api/v1/personal-blueprints` | clear-all of the caller's own rows; the blueprint one answers `deleted`. Frozen 2026-10-03 from the app's call list |
| `PUT /api/v1/missions/{id}/participants/{participantId}/slim` | `id`, `user`, `guestName`, `startTime`, `endTime`, `payoutPreference` — **request** requires `version`. Frozen 2026-10-03 from the app's call list |
| `POST /api/v1/missions/{id}/join`                                                                          | `id`, `participants`, `user`, `registeredParticipants` — self-enrolment; answers with the whole Einsatz because it creates the row. Its **request** body is optional and so is every field in it (`desiredJobTypeId`, `payoutPreference`, added 2026-09-02, ADR-0170) — a bodyless POST is what every build before that sends, and nothing here is frozen as required                                                                                                                                                                                                                 |
| `DELETE /api/v1/missions/{id}/participants/{pid}/slim`                                                     | *(204, no body)* — the **slim** pair; the legacy full-DTO one was deleted on 2026-09-22 after its deprecation                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `DELETE …/missions/{id}/units/{unitId}/crew/{crewId}/slim`                                                 | *(204, no body)* — same pair, same reason: the legacy sibling is gone (deleted 2026-09-22), so the app re-reads the Einsatz rather than folding an answer that does not come                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `POST …/participants/{pid}/check-in/slim`                                                                  | `id`, `user`, `startTime` — the row alone, which is the point of the slim variants                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `POST …/participants/{pid}/check-out/slim`                                                                 | `id`, `user`, `endTime`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `PUT …/participants/{pid}/payout-preference/slim`                                                          | `id`, `payoutPreference` — **request** requires `preference`, which is `PAYOUT` / `DONATE`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `POST /api/v1/finance-entries`                                                                             | `id`, `missionId`, `participant`, `type`, `amount`, `note`, `version` — **request** requires `amount`, `missionId`, `participantId`, `type`; `type` is `INCOME` / `EXPENSE`                                                                                                                                                                                                                                                                                                                                                                                                           |
| `PUT /api/v1/finance-entries/{entryId}`                                                                    | as the create — **request** requires `amount`, `type`, `version`; the version is the entry's optimistic lock                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `DELETE /api/v1/finance-entries/{entryId}`                                                                 | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `PUT /api/v1/operations/{id}/payouts/paid-out`                                                             | `participantKey`, `paidOut`, `paidOutAt`, `paidOutByName` — **request** requires `participantKey`; needs `MISSION_MANAGER`, and taking a confirmation back additionally needs `OFFICER` or `ADMIN`                                                                                                                                                                                                                                                                                                                                                                                    |
| `POST /api/v1/bank/deposits`                                                                               | *(response unread)* — **request** requires `accountId`, `amount`, `holderId`. `holderId` is the one worth naming: custody is per org unit, so a balance without a holder is money nobody is accountable for                                                                                                                                                                                                                                                                                                                                                                           |
| `POST /api/v1/bank/withdrawals`                                                                            | `pendingRequest` — **request** requires `accountId`, `amount`, `holderId`. Over the KRT employee ceiling the server **files** the attempt instead of booking it and answers `202`; `pendingRequest` is how a client tells the two apart, and losing it makes a shipped build report a filed withdrawal as a completed one (REQ-BANK-047, ADR-0109)                                                                                                                                                                                                                                    |
| `POST /api/v1/bank/transfers`                                                                              | `pendingRequest` — **request** requires `amount`, `sourceAccountId`, `sourceHolderId`, `destinationAccountId`, `destinationHolderId`; same ceiling, same 202                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `GET /api/v1/bank/transfer-fee-rate`                                                                       | `rate` — an absent one is read as zero, so a rename quotes „no fee“ on a transfer that charges one                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `GET …/org-units/bank/accounts/{id}/settings`                                                              | `accountId`, `accountName`, `balanceTarget`, `version`, `canSetTarget`, `canConfigureVisibility`, `visibilityConfigurable`, `allMembersSupported`, `availableRoleCodes`, `grantedRoleCodes`, `allMembersGranted`, `approvalLimits`, `canConfigureApprovalLimits`; nested `configurable`, `areaMembersSupported`, `allMembersLimit`, `areaMembersLimit`, `roleLimits`, `userLimits`, `userId`, `displayName`, `limitAmount` — the `can*` flags are what the app offers its controls from; the last two were added 2026-09-03 with the phase-P writes, and the app had always read them |
| `PUT`/`DELETE …/accounts/{id}/approval-limit/all-members`                                                  | as the settings — **request** requires `limit` on the `PUT`; the `DELETE` is addressed entirely by its path. Both answer the whole settings object, which is what lets the section redraw from the answer                                                                                                                                                                                                                                                                                                                                                                             |
| `PUT`/`DELETE …/approval-limit/area-members`                                                               | as above                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `PUT`/`DELETE …/approval-limit/role/{roleCode}`                                                            | as above; the bucket must be one the account actually offers (`availableRoleCodes`), else `400`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `PUT`/`DELETE …/approval-limit/user/{userId}`                                                              | as above; a user limit beats the tier limit                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `PUT …/bank/accounts/{id}/balance-target`                                                                  | as the settings — **request** requires `version` only; sending no target clears it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `POST`/`DELETE …/bank/accounts/{id}/visibility/role/{code}`                                                | as the settings; addressed entirely by path, no body                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `PUT …/bank/accounts/{id}/visibility/all-members/{enabled}`                                                | as the settings; the switch is a path segment                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `GET …/org-units/bank/requests` and `…/requests/foreign`                                                   | `id`, `accountId`, `accountName`, `targetAccountId`, `type`, `amount`, `note`, `status`, `requesterHandle`, `rejectReason`, `applicableLimit`, `requiresOwnerApproval`, `requiredApprover`, `ownerApprovalGranted`, `ownerApprovalGrantedByHandle`, `createdAt`, `version`, `justification` — `…/foreign` without `targetAccountId`, `rejectReason`, `applicableLimit`. `requiredApprover` names the approver **class**; there is no count of approvals anywhere (REQ-BANK-041/-047)                                                                                                  |
| `GET …/org-units/bank/transfer-targets`                                                                    | `id`, `name`, `accountNo`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `POST …/org-units/bank/requests`                                                                           | `id`, `status`, `requiresOwnerApproval`, `requiredApprover`, `version` — **request** requires `sourceAccountId`, `type`, `amount`; `type` is `DEPOSIT` / `WITHDRAWAL` / `TRANSFER`                                                                                                                                                                                                                                                                                                                                                                                                    |
| `PUT …/org-units/bank/requests/{id}`                                                                       | `id`, `amount`, `note`, `targetAccountId`, `version` — **request** requires `amount` only; the account and the kind are not editable                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `POST …/org-units/bank/requests/{id}/cancel`                                                               | `id`, `status`, `version` — **request** requires `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `POST`/`DELETE …/requests/{id}/owner-approval`                                                             | `id`, `ownerApprovalGranted`, `version`, plus `ownerApprovalGrantedByHandle` on the `POST` — **no request body on either verb**, so no version is echoed                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GET /api/v1/promotion/evaluations/my`                                                                     | `categoryName`, `topicName`, `assignedLevel` — me-scoped; the level is a **field**, not a frozen enum, since the levels are the organisation's to name                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `GET /api/v1/promotion/eligibility/my`                                                                     | `fromRank`, `toRank`, `eligible`, `hasConfiguredRules`, `checks`, `topicName`, `categoryName`, `minimumLevel`, `requiredCount`, `achievedCount`, `satisfied` — `hasConfiguredRules` separates "no rules exist" from "you do not meet them"                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/app/version-policy`                                                                           | `minimumVersionCode`, `latestVersionCode`, `releasesUrl` — **anonymous**, one of the four paths REQ-SEC-052 enumerates; frozen so a shipped app can be told to STOP (REQ-API-010)                                                                                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/refinery-orders/my-orders`                                                                    | envelope; row `id`, `status`, `location`, `refiningMethod`, `startedAt`, `durationMinutes`, `endsAt`, `goods`, `oreSales`, `profit`, `version` — `endsAt` is frozen on the LIST only; the detail has none and the app computes it                                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/refinery-orders/{id}`                                                                         | as the list, without `endsAt`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `POST /api/v1/refinery-orders/{id}/store`                                                                  | *(no body)* — **request** requires `items`; the endpoint marks the order stored whatever that list holds, which is why the field is frozen                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/material-exchange/offers`                                                                     | envelope; row `id`, `kind`, `material.quantityType`, `itemName`, `itemQuantity`, `owner.effectiveName`, `ownerOrgUnits.shorthand`, `mine`, `quality`, `amount`, `releasedAt`, `remark`, `interestCount`, `interestedHandles`, `viewerInterested`, `version`                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/material-requests`                                                                            | as the offers, with `requestedAmount`, `minQuality` and `postedAt` in place of `amount`, `quality` and `releasedAt`                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `POST`/`DELETE …/offers/{id}/interest`                                                                     | `id`, `interestCount`, `viewerInterested`, `version` — the updated row, which is what lets the app replace one entry instead of re-reading the page                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `POST`/`DELETE …/material-requests/{id}/interest`                                                          | as the offer's                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `POST …/offers/{id}/deactivate`                                                                            | `id`, `status`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `POST …/material-requests/{id}/deactivate`                                                                 | `id`, `status`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `POST /api/v1/material-exchange/offers`                                                                    | *(no body)* — **request** requires `inventoryItemId`, `offeredAmount`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `POST /api/v1/material-requests`                                                                           | *(no body)* — **request** requires `materialId`, `requestedAmount`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `GET /api/v1/material-exchange/releasable-items`                                                           | `inventoryItemId`, `materialName`, `quantityType`, `quality`, `amount`, `locationName`, `alreadyReleased` — the caller's OWN stacks, which is why it is not anonymous                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `GET /api/v1/me/org-units`, `GET /api/v1/org-units/active-all-kinds`, `GET /api/v1/users/{id}/memberships` | `orgUnitId`, `orgUnitName`, `orgUnitShorthand`, `isProfitEligible`, `kind` — one DTO, three callers: the switcher (what may be pinned, ADR-0151), the order form (what exists) and the Lager's Umbuchen picker (the **destination** member's units, asked with `allKinds=true`)                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/inventory/material/{materialId}`                                                              | envelope; row `id`, `material`, `location`, `amount`, `quality`, `personal`, `note`, `user`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/live-sync/stream`                                                                             | *(a stream — the frozen part is the path, the verb and `topics`)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `POST /api/v1/live-sync/changed`                                                                           | *(no body)* — **request** requires `topic`, `sections`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `GET /api/v1/refinery-orders/all`                                                                          | envelope `content`, `totalElements`, `totalPages`; row as `my-orders`, plus `owner`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `POST /api/v1/refinery-orders`                                                                             | `id` — **request** requires `goods`, `location`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/locations/refineries`                                                                         | `id`, `name`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `GET /api/v1/refining-methods`                                                                             | `content`; row `id`, `name`, `ratingYield`, `ratingCost`, `ratingSpeed` — a page, not a bare array like the refinery list beside it                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/orders/item-catalog`                                                                          | envelope `content`; row `id`, `name`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `GET …/orders/item-catalog/{gameItemId}/blueprints`                                                        | `id`, `outputName`, `scwikiKey`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `POST /api/v1/orders/items`                                                                                | `id` — **request** requires `items`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/users/search-bank`                                                                            | envelope `content`, `totalElements`; row `id`, `effectiveName`, `displayName`, `username`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `GET /api/v1/bank/accounts`                                                                                | envelope `content`; row `id`, `accountNo`, `name`, `type`, `status`, `balance`, `orgUnit`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `PATCH /api/v1/bank/accounts/{id}`                                                                         | as the list row — **request** requires `name`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `POST …/bank/accounts/{id}/close` · `…/reopen`                                                             | as the list row — **request** requires `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/bank/accounts/{id}`                                                                           | `account`, `delta30d`, `bookingCount`; nested `id`, `accountNo`, `name`, `balance`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `GET /api/v1/bank/accounts/{id}/transactions`                                                              | envelope; row `postingId`, `transactionId`, `type`, `amount`, `note`, `holderHandle`, `createdAt`, `reversedTransactionId`, `transferFee`, `counterpartyHandle`                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `GET …/bank/accounts/{id}/statement`, `GET /api/v1/bank/export/three-month-report`                         | *(a file — the frozen part is the path, the verb and the statement's `from` / `to`)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/bank/dashboard`                                                                               | `management`, `accounts`, `totals`, `totalBalance`, `activeAccounts`, `closedAccounts`; row `id`, `accountNo`, `name`, `type`, `status`, `balance`, `delta30d`, `sparkline`                                                                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/bank/holders` and `…/holders/{id}`                                                            | `id`, `handle`, `active`, `totalHeld`, `version`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `GET …/bank/holders/{id}/transactions`                                                                     | envelope; row `postingId`, `transactionId`, `type`, `amount`, `note`, `createdAt`, `counterAccountNo`, `counterAccountName`, `counterHolderHandle`, `reversedTransactionId`                                                                                                                                                                                                                                                                                                                                                                                                           |
| `POST /api/v1/bank/holders/transfer`                                                                       | *(response unread)* — **request** requires `sourceHolderId`, `destinationHolderId`, `amount`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `GET`/`POST /api/v1/bank/grants`, `PATCH …/grants/{userId}/{accountId}`                                    | `userId`, `userHandle`, `accountId`, `canDeposit`, `canWithdraw`, `canTransfer`, `version` — **request** requires `userId`, `accountId` on the `POST`; the three flags and `version` on the `PATCH`                                                                                                                                                                                                                                                                                                                                                                                   |
| `DELETE …/bank/grants/{userId}/{accountId}`                                                                | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/bank/requests`                                                                                | envelope; row as the org-unit request read                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `POST …/bank/requests/{id}/confirm` · `…/reject`                                                           | as the request row — **request** requires `holderId`, `version` on the confirm and `reason`, `version` on the reject                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `POST …/bank/transactions/{id}/reversal`                                                                   | *(response unread; no required field)*                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |

**Frozen has three more sides than the response body, and phase 3 is where each starts to bite.**

*The query parameters* the app addresses an operation by are frozen as `name:type`, as a **fifth
component of the `ContractOperation` record** (`addressedBy(...)`). A renamed parameter is silently
ignored and the member gets the wrong rows; a retyped one comes back `400` and the screen says it
could not load. Both were seen inside one afternoon on the Lager slice, and neither had failed a
build. The assertion is a subset one, so adding an optional parameter stays free.

**The parameters live on the entry, and an operation may not stay silent about them.** They were
first held in a side map keyed by `"method path"`, and the shape of that map was the defect: adding
an operation to the set did not oblige anyone to say how the app addresses it. Five operations that
take query parameters therefore reached the set with none recorded — the Einsatz Finanzen tab and
the Hangar org overview (both paged, both frozen down to their `content` envelope, neither able to
prove it could still ask for page two), the Materialbörse offer sheet's picker, and
`DELETE /api/v1/orders/{id}/assignees/{userId}/note`, whose `version` **is** the optimistic lock and
whose rename would not have failed anything: a `null` version skips the check server-side, so every
note deletion in the field would quietly stop being locked and take the last write over a
colleague's edit. A second guard now fails the build when an operation declares query parameters and
freezes none, so the omission has to become a decision — either `addressedBy(...)` or a named entry
in `ADDRESSED_BY_NO_QUERY_PARAMETER` saying why the app sends nothing.

Only the parameters the app **sends** are frozen, for the same reason only the response fields it
reads are. `sort` is generally absent: the app takes the server's default order as it comes. So is
`allKinds` on `GET /api/v1/users/me/memberships`, whose default (`false` — the Staffel/SK-only
shape) is exactly what the org-unit switcher renders. It was the first entry in the exemption
ledger, which now names every operation that declares query parameters the app never sends.
A **query parameter's enum constants** are not reached by the required-enum guard, which walks
request and response schemas only — `kind` on the offer-sheet picker is frozen by name and type
here, and its `MATERIAL` / `ITEM` vocabulary stays pinned by the offers response that carries the
same field.

|                          Operation                           |                                                        Frozen query parameters                                                         |
|--------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/missions/search`                                | `query:string`, `status:array`, `start:string`, `end:string`, `page:integer`, `size:integer`, `sort:string`                            |
| `GET /api/v1/missions/{missionId}/finance-entries`           | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/inventory/aggregated`                           | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/inventory/all/grouped` | `materialIds:array`, `stolenOnly:boolean`, `nonStolenOnly:boolean` |
| `GET /api/v1/orders`                                         | `status:array`, `page:integer`, `size:integer`                                                                                         |
| `DELETE /api/v1/orders/{id}/assignees/{userId}/note`         | `version:integer`                                                                                                                      |
| `GET /api/v1/org-units/bank/accounts/{id}/transactions`      | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/hangar/my-ships`                                | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/hangar/squadron-overview`                       | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/notifications`                                  | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/operations/search`                              | `query:string`, `status:array`, `start:string`, `end:string`, `page:integer`, `size:integer`, `sort:string`                            |
| `GET /api/v1/personal-inventory`                             | `q:string`, `page:integer`, `size:integer`                                                                                             |
| `GET /api/v1/uex/locations/search`                           | `q:string`, `limit:integer`                                                                                                            |
| `GET /api/v1/personal-blueprints`                            | `q:string`, `page:integer`, `size:integer`                                                                                             |
| `GET /api/v1/personal-blueprints/craftability`               | `includeRefinery:boolean`                                                                                                              |
| `GET /api/v1/blueprints/products/search`                     | `q:string`, `limit:integer`                                                                                                            |
| `GET /api/v1/ship-types`                                     | `page:integer`, `size:integer`, `sort:string`                                                                                          |
| `GET /api/v1/bank/accounts`                                  | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/orders/item-catalog`                            | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/users/search-bank`                              | `query:string`, `page:integer`, `size:integer`                                                                                         |
| `GET /api/v1/users/{id}/memberships`                         | `allKinds:boolean`                                                                                                                     |
| `GET /api/v1/inventory/material/{materialId}`                | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/inventory/all/stack/entries` | `materialId:string`, `locationId:string`, `userId:string`, `quality:integer`, `stolen:boolean`, `owningOrgUnitId:string`, `page:integer`, `size:integer` |
| `GET /api/v1/inventory/my-inventory/grouped` | `locationIds:array`, `personalOnly:boolean`, `nonPersonalOnly:boolean`, `stolenOnly:boolean`, `nonStolenOnly:boolean`, `catalog:string` |
| `GET /api/v1/inventory/my-inventory/stack/entries` | `materialId:string`, `gameItemId:string`, `locationId:string`, `quality:integer`, `personal:boolean`, `stolen:boolean`, `owningOrgUnitId:string`, `catalog:string`, `page:integer`, `size:integer` |
| `GET /api/v1/inventory/my-inventory/entry-ids` | `locationIds:array`, `personalOnly:boolean`, `nonPersonalOnly:boolean`, `stolenOnly:boolean`, `nonStolenOnly:boolean`, `catalog:string` |
| `GET /api/v1/materials/search`                               | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/locations/search`                               | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/users/search`                                   | `query:string`, `page:integer`, `size:integer`                                                                                         |
| `GET /api/v1/live-sync/stream`                               | `topics:string`                                                                                                                        |
| `GET /api/v1/refinery-orders/my-orders`                      | `status:array`, `page:integer`, `size:integer`                                                                                         |
| `GET /api/v1/refinery-orders/all`                            | `status:array`, `page:integer`, `size:integer`                                                                                         |
| `GET /api/v1/material-exchange/offers`                       | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/material-requests`                              | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/material-exchange/releasable-items`             | `q:string`, `kind:string`                                                                                                              |
| `GET /api/v1/bank/accounts/{id}/transactions`                | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/bank/accounts/{id}/statement`                   | `from:string`, `to:string`                                                                                                             |
| `GET /api/v1/bank/grants`                                    | `accountId:string`                                                                                                                     |
| `GET /api/v1/bank/holders/{id}/transactions`                 | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/bank/requests`                                  | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/job-types`                                      | `archetype:string`, `page:integer`, `size:integer`                                                                                     |
| `PUT /api/v1/orders/{id}/priority`                           | `priority:integer`                                                                                                                     |
| `DELETE /api/v1/missions/{id}/steps/{stepId}/slim`           | `stepsVersion:integer`                                                                                                                 |
| `DELETE /api/v1/missions/{id}/objectives/{objectiveId}/slim` | `objectivesVersion:integer`                                                                                                            |
| `GET /api/v1/materials/prices-overview`                      | `name:string`, `page:integer`, `size:integer`, `sort:string`                                                                           |
| `GET /api/v1/materials/{id}/prices`                          | `page:integer`, `size:integer`, `sort:string`                                                                                          |
| `GET /api/v1/materials/profit-calculation`                   | `shipId:string`, `starSystemNames:array`                                                                                               |
| `GET /api/v1/terminals`                                      | `page:integer`, `size:integer`, `sort:string`                                                                                          |
| `GET /api/v1/material-exchange/released-item-ids`            | `ids:array`                                                                                                                            |
| `GET /api/v1/personal-blueprints/overview`                   | `page:integer`, `size:integer`, `sort:string`, `search:string`                                                                         |
| `GET /api/v1/personal-blueprints/overview/owners`            | `productKey:string`                                                                                                                    |

*Required enums on the request* are frozen alongside the response ones. A shipped build sends
`status=IN_PROGRESS` and `locationType=CITY` as literal strings, so renaming a constant turns every
one of those writes into a `400` while the screen keeps loading — quieter than the response break
and just as unfixable without a new APK.

**Frozen has a request side, and phase 3 is where it starts to bite.** A write operation in the set
may not gain a **required** request field. An old build sends the payload it was written against, so
a new `required` entry turns every one of its saves into a `400` — the same class of break as a
dropped response field, arriving through the other direction. Making a required field optional is
safe (the old build keeps sending it), which is why `ExternalContractTest` asserts the `required`
list exactly rather than as a subset: adding is the break, removing is not. A field that genuinely
must be mandatory is a declared break of a hard-cut wave (below).

`PUT /api/v1/personal-inventory/{id}` requires `version` and is the first entry to record that: it
is the optimistic lock, echoed from the read, and a concurrent edit answers `409 OPTIMISTIC_LOCK`
instead of overwriting. `POST` has no `version` because there is nothing yet to conflict with.

`quantityType` on a material is frozen because it is the unit every amount on the Lager screen is
expressed in — SCU or units — and a number without its unit is not a quantity. `effectiveName` on a
member is frozen instead of `username`: it is what the web app renders and what a member recognises,
and the rest of that record — email, roles, permissions — is deliberately left unfrozen because the
picker must not read it.

The book-out's `type` (`DISCARD` / `TRANSFER` / `SELL`) is an enum on the **request**, which the
required-enum guard does not reach: that guard walks responses. It is pinned in this table and in
the app's spec instead.

`GET /api/v1/hangar/my-ships` gained `version` in phase 3. A read-only client had no use for it; a
writing one cannot save without it, and adding a field to a frozen set is the direction that is
always safe.

The Hangar's write path is `/hangar/ships`, **not** `/hangar/users/{id}/ships`. The second one names
a member and is the admin surface; this contract set has no reason to carry it, and the vhost never
admits it.

`removable` on an owned blueprint is frozen for the same reason as `redacted` and `truncated`: it
qualifies the row rather than describing it. A row the server will not release must not be offered a
delete action that then answers `409`.

`limitingMaterialName` is what turns the craftability chip from a boolean into a sentence a member
can act on — "es fehlt X" rather than "nicht baubar" — and its `WithRefinery` twin is the same
question answered once refining is allowed for. `ownedByCurrentUser` on the product picker is what
keeps it from offering a duplicate the server would refuse.

`GET /api/v1/uex/locations/search` is in the set as the picker behind that editor, and `type` is
frozen for a reason worth naming: it is not decoration but the `locationType` half of what the write
body sends, so the row carries both halves of the saved value.

`GET /api/v1/users/me` is frozen for a single field. An Operation's payout rows are keyed by the
**backend user id** — not the Keycloak `sub` the app holds, and not a display name, which is
`displayName` when set and `username` otherwise and therefore cannot be matched reliably. Without
`id` the app cannot tell a member which of eighteen payout rows is theirs. The rest of the
response — email, roles, rank, memberships — stays unfrozen because the app does not read it.

`redacted` on a job order is frozen for the same class of reason as `truncated` and
`payoutPreliminary`: it qualifies the rest of the payload rather than carrying content. A requester
sees their own order with the parts that are not theirs removed (REQ-ORDERS-023), and a client that
stopped seeing the flag would present the gaps as the whole order.

`GET /api/v1/orders` is on the list as an **exact** path. It used to be the one entry where the verb
was the only thing separating two different surfaces — the same path answered a `POST` that was
`permitAll` by design (the public request form) — and the vhost's read-only guard refused that verb
before it arrived. Since ADR-0149 the create requires a login, so the two surfaces no longer differ
in kind and the guard's job there is ordinary.

`GET /api/v1/hangar/my-ships` freezes the row and the **names inside its nested objects**, because
`shipType.name` and `location.name` are what the card shows. `owner` is deliberately left out: it is
a full user record — email, roles, rank — always the caller's own on this endpoint, so the app has no
reason to read it, and freezing it would oblige the backend to keep sending a payload nobody wants.

`GET /api/v1/announcement` answers **`204 No Content`** when there is nothing to announce, and
that is part of its contract even though no schema can say so. A client that treated the empty body
as a parse failure would show an error where the correct rendering is "no banner"; the app reads it
through a dedicated optional-read path for that reason. Changing the endpoint to answer `200` with
an empty object instead would break every client that already special-cases the `204`.

`GET /api/v1/notifications/stream` is in the set for what the *other* guard proves: the path and
verb must keep existing. Its body is a Server-Sent-Event stream, so the response-field assertion is
vacuous by nature, and the real contract is the **event names** — `connected`, `notification`,
`heartbeat`, `replaced`. Nothing in `openapi.json` describes them, so they are pinned in the app's
own spec instead of being left to a schema check that cannot see them. The `notification` event's
**data** carries a signal since REQ-NOTIF-021 — kind, entity and render params — and the event name
is still the frozen part: a client that ignores the payload behaves exactly as before, and a
recipient whose inbox was only cleared still receives the historic `new`. Its response carries
`X-Accel-Buffering: no`, which is what keeps an nginx from holding a trickling stream in a buffer;
the guarantee travels with the endpoint rather than depending on a vhost's defaults.

`params` on a notification is frozen as a **field**, and its content deliberately is not. The app
renders each notification from `notifications.type.<TYPE>` with those named placeholders
substituted, so a renamed placeholder changes a sentence no schema check can see. The client's
defence is to fall back to the generic wording when a placeholder cannot be filled — which belongs
there, not here.

The Operationen entries freeze `truncated` and `payoutPreliminary` deliberately. Both are fields
that qualify a number rather than carry one: `truncated` says the per-mission roll-up is capped
(ADR-0104), `payoutPreliminary` says the payout figures may still rebalance because a mission of
the operation has no `actualEndTime` yet. A screen that silently stopped showing either would
present a partial list as complete and a provisional figure as final — the failure mode is a member
trusting a number, which is worse than a missing field.

`GET /api/v1/users/me/memberships` is the app's org-unit switcher (phase 2) and is a **me-scoped
twin** of `GET /api/v1/users/{id}/memberships`, added rather than reusing the sibling: the vhost is
a default-deny allow-list, and a path able to name *another* user should never need to be on it.
`isProfitEligible` is deliberately absent from the frozen fields — the app does not read it, and
adding it later is one more deliberate edit. Phase 5 did admit `GET /api/v1/users/{id}/memberships`
after all: the Lager's Umbuchen picker needs the **destination** member's units, which no me-scoped
path can answer. The switcher itself now reads `GET /api/v1/me/org-units` (ADR-0151).

**Frozen means**, for an operation in the set: it keeps its path and verb; its response keeps every
**recorded** field; its request accepts everything it accepted before (a new **required** field is a
break); and it is retired or changed only in a **declared hard-cut wave** (ADR-0234). Additive
change stays free — new optional response fields, new optional request fields, new endpoints.

**A hard-cut wave** removes or changes frozen operations and fields in one release, with no
parallel `/api/v2` path and no sunset window. It ships together with:

- **a line per break in the declared-break ledger** (`backend/src/test/resources/api/declared-breaks.txt`):
  the operation, the field where one is meant — never a wildcard — and the app `versionCode` that
  absorbs it; the previous-release comparison accepts exactly the declared breaks;
- **the app's call list**: each app release publishes the calls it makes (verb, path, query
  parameters, response fields read); the backend commits it per app release, the frozen set must
  cover it, and the list of the absorbing app release calls nothing the ledger declares broken;
- **a new app release published first and the minimum version raised for it** (REQ-API-010);
- **`APP_UPDATE_REQUIRED` on the retired paths**: an operation the ledger retired answers one stable
  RFC 7807 problem with that code, which later app versions map to the update wall.

> [!note] Amended 2026-10-02 — retirement by hard cut (owner decisions D-03, D-04, D-11; ADR-0234)
> This used to read: retirement goes through `/api/v2` + `@ApiDeprecation` with a sunset rather
> than a deletion. **Implemented 2026-10-03:** the declared-break ledger (REQ-API-017), the committed
> app call list (REQ-API-016) and the `APP_UPDATE_REQUIRED` answer (REQ-API-020) exist (plan guard
> G-23, Phase 0 step 0.7); a frozen operation is retired only through them.

> [!warning] Amended 2026-09-02 (owner-approved) — this sentence used to say every field it had
> The wording was stricter than the rest of its own requirement and stricter than the gate that
> enforces it, and the three disagreed silently. Three places already meant the recorded set:
>
> - **The gate.** `ExternalContractTest` asserts `containsAll` over the recorded field set, so a
>   response field the table never listed can be removed with the build green.
> - **This requirement's own acceptance criterion**, below, has always read: no *recorded* response
>   field has disappeared.
> - **The freeze table's heading** — the fields a client *may rely on* — and the reasoning above it,
>   that freezing what the client does not read would buy the backend a constraint for nothing.
>   `isProfitEligible`, and a member's email, roles and permissions, are named above as deliberately
>   unfrozen; that only holds under the narrower reading.
>
> The gap was not academic. `GET /api/v1/ship-types` records seven names, while `ShipTypeDto` also
> serialises `description`, `scu` and `hidden`. Under the old sentence those three were frozen by
> accident and could never be removed; under the amended one they are what the table always meant
> them to be. Whether a field is frozen is now decided in one place — the table — rather than by
> whichever sentence a reader reaches first.

**The spec and the test are the source of truth for what is frozen, and what is frozen is what the
API vhost admits.** Since 2026-10-03 the vhost's admission is a map generated from the frozen set
([`docker/edge/include/api-admission.conf`](../../docker/edge/include/api-admission.conf),
REQ-API-021), committed and reviewed in the same PR as the contract change; the two cannot drift,
because the build fails when they differ.

**Acceptance**

- [x] Every listed operation exists in the committed `openapi.json` with its recorded verb, and no
  recorded response field has disappeared (`ExternalContractTest`).
- [x] The set cannot be emptied to make the guard pass — its floor is asserted, since 2026-10-03 at
  the exact count (246 operations, each named once) instead of 5, and the app's own call list must
  be covered by it (REQ-API-016).
- [x] **Every frozen operation is reachable through the edge, and nothing else is** — since
  2026-10-03 the admission map is generated from this set and the build fails when the committed map
  admits more or less than the frozen set plus the two anonymous reads plus the retired operations
  (REQ-API-021, `EdgeAdmissionTest`). Before that, `theFrozenSetIsReachableThroughTheEdge` parsed the
  hand-written include and checked only one direction. The files are declared inputs of
  `:backend:test` (`backend/build.gradle.kts`, `apiVhostAdmission`), so an edit to them alone re-runs
  the guard — until 2026-09-06 an allow-list-only edit left `:backend:test` up-to-date and the guard
  silently did not execute.
- [x] **The edge's verb gating is tested against a live nginx** — `EdgeAdmissionNginxTest` drives the
  committed map through the edge's own image: every admitted operation passes, every other verb on
  every admitted path, near-miss spellings and non-uuid ids answer `404`. It replaced
  `scripts/check-edge-allowlist-behaviour.sh`, which had asserted the same for
  `POST /api/v1/operations` (2026-10-01) and `PUT /api/v1/orders/<uuid>/requested` (2026-10-03)
  alone.
- [x] An entry freezes every level a client parses: the guard descends **one level** into every
  referenced schema — an array's items and a plain nested object alike. That covers a page's
  `content` rows, an embedded list such as an operation's `payouts`, and a nested object such as a
  ship's `shipType`, whose `name` is the whole point of the row. Freezing only the container name
  would freeze the container and nothing in it — a renamed `shareAmount` would reach a device with
  the guard green. Verified by removing the descent (three failures) and by recording a nested
  field that does not exist (one). One level, not transitive: a deeper walk would let a recorded
  name be satisfied by an unrelated schema and the guard would read as stronger than it is.
- [x] **Enum** changes are caught, for the ones that can actually break a shipped client: every
  **required** enum property reachable from a contract response is frozen constant-by-constant
  (`theContractRequiredEnumsAreFrozen`). Adding one fails this build, which forces the release
  order — an app build that knows the constant ships *before* the server starts sending it.
  Nullable enums are deliberately not frozen: a strict client coerces an unknown one to `null`, so
  an objective loses its kind badge rather than its screen, and freezing them would make the guard
  fire on harmless additions until it means nothing. Verified by adding a constant: three failures.
- [x] Type and nullability changes are caught — **closed by ADR-0161 §8.4** (2026-09-10). 1,479
  properties across 253 schemas when it closed, 1,775 across 258 on 2026-09-22, reached from the contract set by the same transitive walk the enum
  guard uses, each recorded as its JSON type, its format and whether the schema requires it.
  <br>**"Nullability" here means `required`, and there is nothing else it could mean**: this
  document carries no `nullable` keyword and no `["string","null"]` union — springdoc emits neither
  at OpenAPI 3.1 — so membership of a schema's `required` list is the entire signal, and a property
  leaving it is a field an installed build reads unconditionally and now gets `null` for.
  <br>Two guards, because they fail on different things.
  `theContractTypesAndNullabilityAreFrozen` compares against a committed record
  (`backend/src/test/resources/api/frozen-contract-types.txt`) and runs everywhere, including
  locally; a pull request could in principle edit the record and the document together.
  `theContractTypesMatchThePreviousRelease` compares against the previous release tag's own
  `openapi.json`, which no pull request can edit — ADR-0136's wording taken literally. Verified by
  flipping one property from optional to required (the first fails, naming the field) and by
  running the second against `v1.7.7`. **Since 2026-10-03 the second no longer skips in CI**: a
  missing baseline fails there, it compares operation by operation, and it accepts a break only
  when the declared-break ledger names it (REQ-API-017).
- [x] A sunset can actually retire old builds — **closed by REQ-API-010** (2026-08-24). The gate
  the first `/api/v2` was waiting on now exists: the server names a floor and the app refuses to run
  below it. What that unblocks is narrower than "old builds are gone", and the difference matters
  when planning a sunset — the floor stops a build from *running*, it does not remove it from
  anyone's phone, and a member who never opens the app never learns of it.
- [x] **A break is declared before it ships** — the declared-break ledger, accepted exactly by the
  previous-release comparison. **Closed by REQ-API-017** (2026-10-03).
- [x] **The frozen set covers what the app calls** — the app's call list committed per release and
  asserted against the set. **Closed by REQ-API-016** (2026-10-03) for the app's next build; the
  list per released build follows with the app release.
- [x] **A retired path answers `APP_UPDATE_REQUIRED`.** **Closed by REQ-API-020** (2026-10-03):
  `RetiredOperationFilter` answers every operation of `retired-operations.txt` with `410
  APP_UPDATE_REQUIRED` ahead of authentication, and the API vhost's generated admission admits
  every retired operation so the answer reaches the app there (REQ-API-021).

**Enforced by:** `ExternalContractTest` (backend) · the *Fail if a committed openapi.json is stale*
step in `ci.yml` (since 2026-09-23), which is what keeps the document `ExternalContractTest` reads
equal to the one the controllers produce (REQ-API-007) ·
**Related:** ADR-0136, ADR-0234, ADR-0135, ADR-0003, REQ-API-001, REQ-API-007, REQ-SEC-027

---

### REQ-API-010 — The server states which app builds it still serves

A frozen contract (REQ-API-009) keeps a shipped build working. It cannot make one **stop**: when an
operation is genuinely retired, or a defect makes a build unsafe to keep using, something has to
tell the device. Nothing did — the sunset checkbox of REQ-API-009 sat open for exactly this reason,
and it is why the first `/api/v2` was blocked on a gate that did not exist.

`GET /api/v1/app/version-policy` answers three values: `minimumVersionCode` (the oldest build still
served; `0` means no floor), `latestVersionCode` (the newest published, or `0` when unknown) and
`releasesUrl`. The app compares its own `versionCode` against the floor and, below it, shows the
non-dismissible „Update erforderlich" screen of design chapter 14.

**Three properties, each of which is the requirement rather than an implementation note.**

- **Anonymous** (owner decision, 2026-08-24). The API vhost opens no anonymous paths as a matter of
  stance (plan Q8); this and `GET /api/v1/terms/document` are the two exceptions REQ-SEC-052
  enumerates. A version gate that answers only after a
  successful login is silent in the one case it exists for: when the break is in the auth flow, the
  old build cannot log in, and it would show an authentication error where the design calls for an
  update wall — telling the member their credentials are wrong, which they are not. It publishes
  three integers and a public release URL; no caller identity goes in and none comes out. It is
  enumerated in REQ-SEC-052 — which is where the public surface is now a list rather than a
  policy — and its status is pinned by a test.
- **The floor and the newest build are two numbers.** Collapsing them makes every release a forced
  one, because the app could no longer tell "your build is no longer served" from "a newer build
  exists" — and it only has a wall for the first.
- **The default floor is `0`.** A server nobody has configured must not refuse every installed
  build. Locking members out is the expensive direction of a wrong default; serving an old build
  for one more day is the cheap one.

**The floor is bound to the release** (owner decision D-11, ADR-0234). The minimum version is a
reviewed default in the release's own configuration, so it deploys and rolls back together with the
API it protects; the host value `APP_ANDROID_MINIMUM_VERSION_CODE` stays only as an emergency
override. A floor raised on the host alone survives a rollback of the release it was meant for,
and the old backend then walls old apps while new apps find their paths gone. Configuration
(`app.android.*`), not a table: no migration and no admin screen.

**Around a hard-cut wave (REQ-API-009) three more properties hold:**

- **The app re-reads the policy on foreground resume and after an unexpected 404** (or `NOT_FOUND`
  on a known path), so an app that is already running meets the wall; this ships in an app release
  before the first wave that breaks an Android operation.
- **Retired paths answer `APP_UPDATE_REQUIRED`**, which app versions that know it map to the wall.
- **Cuts are announced and made at low usage.**

> [!note] Amended 2026-10-02, implemented 2026-10-03 — the release-bound floor (owner decision D-11;
> ADR-0234; REQ-API-020)
> This used to read: configuration, not a table, because raising the floor has to work without a
> migration, an admin screen or a deploy. Until 2026-10-03 the floor was bound at backend start from
> `APP_ANDROID_MINIMUM_VERSION_CODE` (default `0`) in the host `.env` and raised by runbook step S8
> of [`EXCHANGE_GO_LIVE_RUNBOOK.md`](../EXCHANGE_GO_LIVE_RUNBOOK.md), after the re-cut release was
> verified healthy and reverted first on a rollback. Now the floor and the newest build are reviewed
> literals in the backend's `application.yml` (`app.android.version-policy.release.*`), so they
> deploy and roll back with the release; the host keeps only an emergency override under new names
> (`APP_ANDROID_*_OVERRIDE`), and the retired `APP_ANDROID_MINIMUM_VERSION_CODE` /
> `…_LATEST_VERSION_CODE` / `…_RELEASES_URL` reach no container. A raised floor is a change to that
> literal in the wave's pull request, not runbook step S8. The code-level default stays `0`, so the
> third property above still holds for a context without the committed block. Retired paths answer
> `APP_UPDATE_REQUIRED`. The app's re-read on resume and after an unexpected 404 is not released yet
> (basetool-android#209).

The operation is itself in the frozen set, for an inverted reason worth stating — every other entry
is frozen so a shipped app keeps working, this one so a shipped app can be told to stop. A renamed
`minimumVersionCode` would leave the build that most needs the answer, the one already too old,
reading "no floor" and carrying on against a contract that no longer exists.

**The CTA is a deviation from the design.** Chapter 14 points its button at a store listing;
distribution is GitHub Releases plus Obtainium (plan Q1), so `releasesUrl` names the release page
instead. Recorded here rather than left as a silent difference between design and build.

**Enforced by:** `AppVersionPolicyControllerTest`, `ExternalContractTest`,
`ApiVhostAnonymousSurfaceTest` (backend) ·
**Related:** REQ-API-009, REQ-SEC-037, ADR-0136, ADR-0234, app issue krt-profit/basetool-android#67

---

### REQ-API-011 — JSON is the contract; CBOR is a second encoding of it

Every `/api/**` response is available as **JSON** and, to a caller that asks for it, as **CBOR**
(`application/cbor`, RFC 8949). Same object model, same DTOs, same field names, same Bean Validation,
same RFC 7807 handling — only the bytes differ.

> [!important] "Only the bytes differ" is a requirement, not an observation — it is false by default
> Jackson's `UUIDSerializer` asks the generator `canWriteBinaryNatively()` and writes sixteen raw
> bytes when the answer is yes. JSON answers no and emits a string; CBOR answers yes. Left alone,
> **all 209 `string/uuid` properties of the frozen contract stop being strings** under the second
> encoding, and anything that treats an id as text renders base64. That is exactly the in-place shape
> change REQ-API-009 forbids, so `CborFidelityConfig` overrides the serializer and
> `CborJsonFidelityTest` states the requirement over a value carrying every type whose wire form
> could diverge.
>
> One known, accepted difference: a `BigDecimal` decodes to a decimal node from CBOR and to a double
> from JSON — same value, different scale, CBOR the more faithful of the two. It changes nothing for
> a caller that binds to a declared type, which is every caller, and it is pinned by name so it stays
> a known difference rather than becoming a surprise in a ledger. The choice is content negotiation and nothing else:
> a caller that sends `Accept: application/json`, or no `Accept` at all, is served exactly what it was
> served before CBOR existed.

**JSON remains the contract.** `openapi.json` documents one representation, the frozen contract set
of REQ-API-009 is expressed in it, and the shipped clients — the Android app and the SC extractor —
ask for it. CBOR is an encoding of the same document, not a second API, and nothing may be reachable
in one and not the other.

Three consequences that are load-bearing rather than incidental:

- **RFC 7807 problems are always JSON.** `GlobalExceptionHandler` presets
  `application/problem+json` on the response, and Spring skips content negotiation entirely for a
  preset concrete content type. This is what keeps the stable machine-readable `code` readable by a
  client that asked for CBOR — REQ-API-004's guarantee does not become conditional on an `Accept`
  header.
- **Request bodies stay JSON.** Only the response direction negotiates. Spring registers the JSON
  encoder ahead of the CBOR one, so a write goes out as JSON without being told to — pinned by a
  test, because it is a framework ordering rather than a decision in this repository, and a write
  path that silently turned binary would meet every `consumes = APPLICATION_JSON_VALUE` endpoint as
  a 415.
- **`Vary` names `Accept`.** Two representations at one URL, on families an intermediary is
  permitted to store (`no-cache, must-revalidate`), means a cache keyed on the URL alone could hand
  a CBOR body to a JSON client. `ApiCacheControlFilter` emits `Vary: Accept, Accept-Encoding`.

**Configuration, not a table.** Which encoding the frontend asks for is `app.http.codec`
(`CBOR` → `Accept: application/cbor, application/json`; `JSON` → the pre-2026-09-10 header). The
backend serves whatever is asked for either way — there is no server-side switch, because a
representation that exists for one caller and not another is a contract that depends on
configuration. Its sibling `app.http.backend-protocol` (`H2` by default, `HTTP11` as the way back;
ADR-0161 §8.1) picks the transport of the same hop and changes no byte of the payload; the SSE relay
stays on HTTP/1.1 either way.

> [!note] Corrected 2026-09-23 (BE-PERF-14, ADR-0161 amendment)
> The paragraph below describes the configuration, not what the hop carries. Measured on a real
> Tomcat 11: the ETagged `no-cache` families (missions, materials, the catalogue behind the 23x
> figure) are **never** gzipped — Tomcat refuses compression for a strong ETag — and on the no-store
> families, where it did happen, gzip made the internal request 1.6–3.2 ms slower for a byte saving
> that is worth nothing on a single-host bridge. The frontend therefore no longer sends
> `Accept-Encoding` on the backend hop; `server.compression` (CBOR included) stays for the callers
> behind the edge that ask for it.

**Compressed, like the JSON it stands beside.** `application/cbor` is on
`server.compression.mime-types`. The first revision of this requirement left it off and argued that
skipping gzip was the point — bytes for CPU on an internal hop. **That was wrong on the numbers.**
Measured on a representative document from this repository: 1 873 986 B raw against 80 285 B
gzipped, a **23x** ratio. CBOR does not dictionary-compress field names, so raw CBOR lands near raw
JSON — several times *more* bytes than the gzipped JSON it replaced, on the one hop the change
exists to make cheaper, and on the catalogue whose 16 MB tipped a buffer. Compressing both keeps the
byte axis at parity and leaves CBOR's actual claim, a cheaper parse, as the only variable the
still-owed measurement has to weigh.

**Requests are refused, not merely un-negotiated.** The CBOR converter is registered write-only
(`CborFidelityConfig`). `JacksonCborHttpMessageConverter` inherits `canRead`, so simply adding the
dependency also made the backend *accept* `Content-Type: application/cbor` on the 229 of 233 write
mappings that declare no `consumes` — parsed by a mapper that never sees `JacksonConfig`'s
customizer, since that is a `JsonMapperBuilderCustomizer` and reaches the `JsonMapper` alone. Such a
body would skip `NormalizedStringDeserializer` entirely: no trim, no NFC normalisation, no
`MAX_FREE_TEXT_LENGTH`. A CBOR request body now answers `415` through the ordinary RFC 7807 path.

**Acceptance**

- [x] A caller asking for CBOR gets CBOR, and it decodes to the same document as the JSON
  (`ApiCborNegotiationTest` — which **aborts** rather than passing when the endpoint it samples has
  no rows, because that is how the UUID divergence below survived a green build).
- [x] **A UUID is a string in both encodings**, and every other type whose wire form could diverge is
  compared explicitly (`CborJsonFidelityTest`, which needs no seeded data). Verified by the failure
  it was written for: five E2E write flows and ids rendering as `AAAAAAAAAAAAAAAAAAAAAQ==`.
- [x] A caller that does not ask for it is unaffected, byte for byte.
- [x] An RFC 7807 problem stays `application/problem+json` under a CBOR `Accept`.
- [x] A write still goes out as JSON with CBOR enabled (`WebClientCborNegotiationTest`).
- [x] `Vary` names `Accept` as well as `Accept-Encoding` (`ApiCacheControlFilterTest`).
- [x] A CBOR **request body** is refused with `415`, so only the response direction negotiates
  (`ApiCborNegotiationTest`, `CborJsonFidelityTest`).
- [x] The rollback lever reaches a deployed container: `APP_HTTP_CODEC` is named in the frontend's
  compose environment, a closed allow-list with no `env_file`, and therefore in the generated
  `quadlet/env.d/frontend.env.tmpl` the production unit reads.
- [x] `openapi.json` is unchanged by the second representation, so the generated Android models are
  too — springdoc already emits `*/*` for these responses.
- [ ] The serialization cost is actually measured. **Open** — ADR-0161 §8.5 makes this its own
  precondition, and `app.http.codec=JSON` is the way back while it is pending.

**Enforced by:** `ApiCborNegotiationTest`, `ApiCacheControlFilterTest` (backend) ·
`WebClientCborNegotiationTest` (frontend) ·
**Related:** REQ-API-004, REQ-API-007, REQ-API-009, REQ-SEC-031, ADR-0161

---

### REQ-API-012 — A consumer that reads a slice gets a slice-shaped read

A read that a hot consumer calls often, and of which it uses a small part, has a shape sized to that
consumer instead of reusing the heaviest projection that happens to contain the part. Two exist
(2026-09-22, BE-PERF-06 / BE-PERF-07):

- **The user pickers search references, not users.** `GET /api/v1/users/search/references` and
  `GET /api/v1/users/search-bank/references` run the same squadron-scoped username / display-name
  search as `/search` and `/search-bank`, but project each hit in SQL to `UserReferenceDto` (`id`,
  `username`, `displayName`, `effectiveName`, `rank`) — one statement plus its count, no entity, no
  role collection, no membership lookup. The full-DTO search hydrated every match with its roles and
  three membership queries, on every keystroke of every `remote-users` combobox, to read two fields.
  **The gates are the twins' gates**, URL matcher and `@PreAuthorize` alike (`/references` of
  `/search`: `ADMIN`/`OFFICER`/`KRT_MEMBER`; of `/search-bank`: those plus `BANK_EMPLOYEE` /
  `BANK_MANAGEMENT`). The projection is exactly the field set the peer view keeps
  (`UserDtoRedaction.toPeerShape`), so there is nothing to redact and no caller learns more than from
  the twin. `/search` keeps the full DTO for member management. The frontend `/users/search` and
  `/users/search-bank` proxies forward to the reference endpoints.
- **The page layout reads the caller once.** `GET /api/v1/me/layout` returns the effective org unit,
  the pinnable org units, the capability flags and the unread-notification count in one read-only
  transaction, through the same resolvers as `/me/active-org-unit`, `/me/org-units`,
  `/me/capabilities` and `/notifications/unread-count` — so a client may use either shape and gets
  the same answers. It answers all four parts or fails as a whole; ADR-0151's fail-closed handling of
  the capabilities stays the client's.

Neither endpoint is on the API vhost: both serve the web frontend over the internal hop. Offering them
to the Android app is an admission change (REQ-API-021) in the same change as the app starts using
them, per REQ-API-009.

> [!note] Corrected 2026-10-03 — `/me/layout` *was* on the API vhost until now
> The sentence above was not true when it was written: the edge's `^/api/v1/me/` prefix rule admitted
> `GET /api/v1/me/layout` (and every other verb under `/me/`). The generated, verb-aware admission of
> REQ-API-021 removed the prefix rule, and since then the sentence holds; the nightly probe and
> `EdgeAdmissionNginxTest` assert the `404`.

**Acceptance**

- [x] The reference searches carry their twins' gates, anonymous is `401`, and a hit carries no
  e-mail, role or Staffel field (`UserReferenceSearchTest`).
- [x] The frontend proxies forward to them (`UserProxyControllerTest`).
- [x] `/me/layout` answers the four parts from the same resolvers (`MeControllerTest`) in exactly one
  transaction, where the separate calls open several (`MeLayoutSingleTransactionTest`).
- [x] The web layout uses `/me/layout` instead of the separate calls — the frontend half of
  BE-PERF-07 / FE-PERF-01, done 2026-09-23 (`LayoutContextLoader`, REQ-FE-020).

**Enforced by:** `UserReferenceSearchTest`, `MeLayoutSingleTransactionTest`, `MeControllerTest` ·
`UserProxyControllerTest`, `LayoutModelScopeMvcTest`, `LayoutContextLoaderTest` (frontend) ·
**Related:** REQ-API-005, REQ-API-009, REQ-DATA-003, REQ-FE-016, REQ-SEC-037, REQ-SEC-047, ADR-0089,
ADR-0151

---

### REQ-API-015 — Every request body is `@Valid`, and a test refuses one that is not

Every `@RequestBody` parameter of every handler carries `@Valid` (or `@Validated`), whatever the verb
and whatever the body type — a record, a collection or a plain `String`. REQ-API-003 stated it; until
this requirement nothing checked it, and thirteen bodies lacked it: nine catalogue writes
(frequency types, material categories, refining methods, star systems), the two role-catalogue
writes, the registration approval, and the Discord account-existence pre-check the Keycloak SPI
calls with its shared secret.

The thirteen now carry `@Valid`. None of their body types declares a Jakarta constraint, so no
request that passed before is refused now; the annotation makes a constraint added later effective
instead of silently ignored. A body type that has no constraint is a REQ-API-002 gap, not a reason
to leave the annotation off.

**Acceptance**

- [x] Every `@RequestBody` of the backend carries `@Valid` (208 bodies today, a floor).
- [x] The rule fails on a planted fixture body without it.

**Enforced by:** `MassAssignmentGuardTest#everyRequestBodyIsValidated`,
`MassAssignmentGuardRules#unvalidatedBodies` · **Related:** REQ-API-002, REQ-API-003, REQ-SEC-077

---

### REQ-API-016 — The frozen set covers every call the app publishes

The frozen set of REQ-API-009 was assembled by hand and missed operations the app calls: eleven, one
of them refused by the edge (2026-10-03, below). The Android app therefore publishes, with every
release, the calls it makes — `core/contract/app-calls.txt` in `krt-profit/basetool-android`
(REQ-APP-API-011 there, generated and kept exact by its `AppCallListTest`) — and this repository
commits that list and holds the frozen set to it.

**Where the lists live.** `backend/src/test/resources/api/app-calls/`, one file per app build the
server still serves, named `<versionCode>.txt`, plus `unreleased.txt` for the build under
development. One line per operation, in the app's format: `<VERB> <path> q=<names>|- f=<names>|-
s=<sites>`, the path template exactly as `openapi.json` writes it, `q=` the query parameters the app
sends, `f=` the response fields it may read (flat, two levels deep, over-stated rather than
under-stated), `s=` the app's own call sites, which the server ignores. No comment lines.

**What the guard asserts** (`ExternalContractTest`):

- **every call is in the frozen set** — verb and path — and **every query parameter it sends is
  frozen** on that entry (`theFrozenSetCoversEveryCallOfEveryServedAppBuild`);
- **every response field it may read is still served** by the document at the depth
  `theContractResponsesKeepTheirFields` reads (`theFieldsEveryServedAppBuildReadsAreStillServed`).
  The list itself is the field freeze for the app: the hand-recorded `responseFields` of an entry
  stay as the reviewed minimum, and the list adds what the app's code may touch, so nobody copies
  2,231 over-stated names into a Java literal. Each field's type and `required`-ness is frozen as
  before through `frozen-contract-types.txt`, which records every property reachable from a frozen
  operation;
- **the newest list is not emptied** — a floor on its call count (243 when it was committed).

**Refreshing it, at each app release** (the owner, or whoever cuts the app release):

1. Copy the released tag's `core/contract/app-calls.txt` to `app-calls/<versionCode>.txt`, LF line
   endings, unchanged.
2. Replace `unreleased.txt` with the list on the app's `main` once it differs from the release (or
   delete it while nothing is in development).
3. Delete the list of every build the minimum version (REQ-API-010) no longer serves.
4. Run `ExternalContractTest`; a new call fails until it is frozen — added to `CONTRACT`, its entry
   in `frozen-contract-types.txt`, and its edge admission (REQ-SEC-037) in the same change.

> [!note] Recorded 2026-10-03 — what the first list found
> Against the list of the app's next build (243 operations, `unreleased.txt`), the frozen set lacked
> eleven operations: `GET /materials/matrix`, `POST /orders`, `PUT /orders/{id}/requested`,
> `POST /bank/accounts`, `POST /bank/holders`, `PATCH /bank/holders/{id}`, `PUT` and
> `DELETE /refinery-orders/{id}`, `DELETE /hangar/ships`, `DELETE /personal-blueprints` and
> `PUT /missions/{id}/participants/{participantId}/slim`; and seven query parameters on frozen
> entries. All are frozen now. `PUT /orders/{id}/requested` — the requester's order edit — was also
> refused by the edge, so the app's edit answered `404` since app v0.2.0; the vhost now admits that
> `PUT` and nothing else on the path. Three frozen operations are no longer called by the app
> (`GET /personal-inventory/{id}`, `GET /refinery-orders/my-orders`, `GET /users/me/memberships`);
> they stay frozen, because removing one is a declared break (REQ-API-017).

**Acceptance**

- [x] The list of the app's next build is committed and the frozen set covers it — 246 operations,
  each named once, floor asserted at that count (2026-10-03).
- [x] A planted list fails the coverage, the parse and the ledger agreement (`AppCallListTest`).
- [ ] A list per released app build — **open**: the first released list arrives with the app release
  that ships `app-calls.txt` (versionCode 18).

**Enforced by:** `ExternalContractTest`, `AppCallListTest` (backend) · `AppCallListTest` (app) ·
**Related:** REQ-API-009, REQ-API-010, REQ-API-017, REQ-SEC-037, ADR-0136

---

### REQ-API-017 — A frozen operation breaks only by a declared line, against a mandatory baseline

A break of a frozen operation or field is either declared or a defect. The declaration is a line in
**the declared-break ledger**, `backend/src/test/resources/api/declared-breaks.txt`, one line per
break:

```text
<VERB> <path> <field> <versionCode>
```

- `field` is `-` when the operation itself is gone (path or verb), `Schema.property` for a property
  of a schema the operation reaches, `query:<name>` for a query parameter, or the body key the
  comparison prints (`request[application/json]`, `response[200][*/*]`, …);
- `versionCode` is the Android build that absorbs the break — the one the minimum version is raised
  to (REQ-API-010);
- no wildcard, no comment line, no repeated break; a malformed line fails the build.

It is empty until the first hard-cut wave.

**The comparison.** `theContractTypesMatchThePreviousRelease` compares the committed `openapi.json`
with the previous release tag's, operation by operation, over the frozen set, every committed app
call list and every ledger line: an operation the release served and the document no longer does,
and every property, body or query parameter of a served operation that is gone or changed its type,
format or `required`-ness, is a break. It **fails on every break no ledger line names exactly**
(operation and field, verb included). A line that matches no break is spent and stays as the record.

**The ledger and the call lists agree** (`theLedgerAgreesWithTheCommittedCallLists`): a list of a
build older than a line's `versionCode` must be deleted with the break, because the raised floor walls
that build off; a list of the absorbing or a newer build must not call an operation the ledger
declares gone.

**The baseline is mandatory in CI.** The *Fetch the previous release's API contract* step of
`ci.yml` fails when it cannot fetch the newest `vX.Y.Z` tag's `openapi.json`, and the build step sets
`CONTRACT_BASELINE_REQUIRED=true`, which `backend/build.gradle.kts` passes on as
`-Dcontract.baseline.required=true`; the test then fails instead of skipping on a missing baseline.
This holds on pull requests as well as on `main`: the tag is read anonymously from the public base
repository, which works for a fork's pull request too, so a failed fetch is a transient error to
re-run, never a reason to skip. Locally nothing is required and the comparison skips; the committed
type record (`theContractTypesAndNullabilityAreFrozen`) still runs.

The same step also fetches the tag's exchange relay document
(`backend/src/main/resources/api/exchange-relay.openapi.json`, REQ-XCH-039) when the tag has one;
the comparison joins it with that release's `openapi.json` and compares against the current two
documents joined. A release before the fence carries the relay operations in `openapi.json`
itself. Either way the joined baseline must document every `T0` operation, or the comparison fails
(`aPreviousReleaseWithoutItsRelayDocumentFails`).

**Acceptance**

- [x] The ledger exists and is empty; a planted wildcard, malformed or repeated line fails the parse
  (`DeclaredBreaksTest`).
- [x] A planted previous document with a retired operation, a retyped field and a dropped query
  parameter yields exactly those breaks; each is accepted only by its own line (`DeclaredBreaksTest`).
- [x] A required but missing baseline fails (`aMissingBaselineFailsWhereOneIsRequired`), and CI
  requires it (2026-10-03).
- [x] T0 operations refuse every ledger line, and the comparison also covers every operation the
  previous document marks `T0` or `T1` (REQ-API-018, 2026-10-03), the relay operations in the
  release's relay document included (REQ-XCH-039).

**Enforced by:** `ExternalContractTest`, `DeclaredBreaksTest`, `AppCallListTest` (backend) · the
*Fetch the previous release's API contract* step of `ci.yml` ·
**Related:** REQ-API-009, REQ-API-010, REQ-API-016, ADR-0136

---

### REQ-API-020 — The app floor rides the release, and a retired path tells the app to update

Owner decision D-11 (ADR-0234): the minimum app version deploys and rolls back together with the API
it protects, and an operation a hard-cut wave retired answers the app's update wall rather than a
bare error.

**The release-bound floor.** The three values of `GET /api/v1/app/version-policy` (REQ-API-010) are
reviewed literals in `backend/src/main/resources/application.yml`, under
`app.android.version-policy.release.*` — `minimum-version-code`, `latest-version-code` and
`releases-url` (`https` only). They are packaged into the backend image, so a promotion applies them
and a rollback — the health gate's or a promotion of an older version — restores the previous
release's values with its API, with no host step. **Raising the floor is a change to these literals
in the wave's pull request**, reviewed like the API change it protects; no profile file may
redeclare them. The committed values are **17 / 17** (app v0.4.0, `versionCode` 17), the floor
runbook step S8 of `EXCHANGE_GO_LIVE_RUNBOOK.md` set on 2026-09-28, so the release that introduces
this requirement changes nothing on production. Without the committed block the record's own
defaults apply — floor `0` — which keeps REQ-API-010's "an unconfigured server walls nobody".

**The emergency override.** `APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE`,
`APP_ANDROID_LATEST_VERSION_CODE_OVERRIDE` and `APP_ANDROID_RELEASES_URL_OVERRIDE` — empty by
default — replace the release default field by field (`app.android.version-policy.emergency-override.*`).
They are break-glass: a floor that walls a working build, or one that must rise before a release can
ship. An override may lower the floor to `0`. A negative number, a non-number or a non-`https` URL
fails startup (`@Validated`). The backend logs every value with its source at startup — `WARN` while
an override is in force — publishes `basetool_android_version_policy_override{field}`, and
`AndroidVersionPolicyOverrideActive` fires after a day: an override does **not** roll back with a
release, so it is folded into the next release's literal and emptied again.

**A stale host value cannot pin the floor.** The variables the floor used to be read from —
`APP_ANDROID_MINIMUM_VERSION_CODE`, `APP_ANDROID_LATEST_VERSION_CODE`, `APP_ANDROID_RELEASES_URL` —
are passed by no compose file and no `env.d` template, which are closed allow-lists, and the
properties moved under a prefix that relaxed binding cannot reach from those names, so a value left
in a host `.env` binds to nothing even if it were passed. On production that line stays the floor of
a rollback to a release older than this requirement (1.13.x and before still read it), so it is left
in place until no such release is a rollback target, and never edited again.

**`APP_UPDATE_REQUIRED` for retired paths** (ADR-0234 decision 7, plan option c). The committed list
`backend/src/main/resources/api/retired-operations.txt` holds one retired operation per line,
`VERB /api/v1/path`, a segment being a literal or a `{name}` placeholder for exactly one segment. A
wildcard, a path outside `/api/`, a T0 operation (`/api/v1/app/version-policy`,
`/api/v1/exchange/**`, `/api/v1/live-sync/**`, `/api/v1/notifications/stream`), an unsupported verb
or a duplicate fails startup. A request whose verb and full path match an entry is answered
`410 Gone` with an RFC 7807 problem — `code` `APP_UPDATE_REQUIRED`, type suffix
`app-update-required`, the localised `problem.app_update_required.*` title and detail and the
`correlationId` — counted as `basetool_http_error_total{code="APP_UPDATE_REQUIRED"}`. App releases
that know the code map it to the update wall at any status (basetool-android#209); `410` and not
`426`, because `426` is a protocol upgrade that requires an `Upgrade` header, while `410` says what
is true — the operation is gone for good. **The list is empty today**; while it is, the filter is
skipped for every request.

- **Placement: ahead of authentication.** `RetiredOperationFilter` runs in the API chain before CSRF
  and the bearer-token filter, so an old app whose login is what the cut broke, or whose token no
  longer validates, still meets the wall instead of an authentication error — the reason
  REQ-API-010's version gate is anonymous.
- **No bypass.** A matched request is answered and never forwarded; another verb on the same path, a
  longer path and every unlisted path continue to authentication unchanged. The retired operation
  has no handler any more, and an entry that matches an operation the backend still documents fails
  the build (`RetiredOperationsContractTest`), so the answer can never stand in front of a live one.
- **No oracle.** The answer is the same for every value of a placeholder, reads nothing and names no
  resource; all it says is that the operation was once part of the published API, which the public
  repository's ledger already says.
- **Only previously admitted paths.** Every entry is a whole-operation break
  (`<VERB> <path> - <versionCode>`) of the ledger `backend/src/test/resources/api/declared-breaks.txt`
  (REQ-API-017, plan guard G-23); the build fails on an entry the ledger does not declare that way —
  a field-level line does not retire its operation — and when the ledger is missing.
- **The edge comes first.** On the public API vhost the edge refuses an operation it does not admit
  with a bare `404` before the backend sees it. Its generated admission (REQ-API-021) admits every
  operation of `retired-operations.txt`, so an app on the API vhost meets this answer and not the
  edge's `404`.

**A wave's pull request** therefore carries, together: the re-cut operations; one ledger line per
break; one line here per retired operation; and the release floor and newest build raised to the
absorbing app release. The app release is published first; the promotion then applies API, floor
and retired answers at once, and a rollback takes all three back.

**Acceptance**

- [x] The floor, the newest build and the release page are committed literals; a stale value under
  the retired names cannot reach them; an override replaces them field by field; an invalid override
  fails startup (`AndroidClientPropertiesTest`, `AppVersionPolicyControllerTest`).
- [x] No compose file, `env.d` template or profile passes a retired name; the backend template passes
  the three overrides, empty by default (`AppVersionPolicyDeploySeamTest`, `render-env-d.test.sh`).
- [x] The source of every value is logged and gauged; a lingering override alerts
  (`AndroidVersionPolicyReportTest`, `android_version_policy_override_test.yml`).
- [x] A retired operation answers `410 APP_UPDATE_REQUIRED` ahead of authentication, without a token
  and with an invalid one, and nothing else does (`RetiredOperationFilterTest`,
  `RetiredOperationChainTest`).
- [x] The list refuses wildcards, T0 operations and duplicates, is dormant while empty, never shadows
  a documented operation, and is tied to the ledger (`RetiredOperationsTest`,
  `RetiredOperationsContractTest`).
- [x] The edge admits the retired operations, so the answer reaches the app on the API vhost —
  closed by REQ-API-021 (2026-10-03): `EdgeAdmission` reads `retired-operations.txt` into the
  generated map.

**Enforced by:** `AndroidClientPropertiesTest`, `AppVersionPolicyDeploySeamTest`,
`AndroidVersionPolicyReportTest`, `AppVersionPolicyControllerTest`, `RetiredOperationsTest`,
`RetiredOperationFilterTest`, `RetiredOperationsContractTest`, `RetiredOperationChainTest` (backend) ·
`scripts/render-env-d.test.sh` · `monitoring/prometheus/tests/android_version_policy_override_test.yml` ·
**Related:** REQ-API-009, REQ-API-010, REQ-SEC-037, REQ-SEC-052, REQ-OBS-011, ADR-0135, ADR-0234

---

### REQ-API-021 — The API vhost admits exactly the frozen operations, by verb and path, from a generated map

The public API vhost admits **exactly** these operations, each by its verb and its whole path:

- every operation of the frozen set (`ExternalContractTest`, REQ-API-009) — T1 plus the T0 members
  the app uses, which covers the app's own call list (REQ-API-016);
- the two anonymous reads, `GET /api/v1/app/version-policy` and `GET /api/v1/terms/document`
  (REQ-SEC-037), whether or not the frozen set lists them;
- every retired operation in `backend/src/main/resources/api/retired-operations.txt` (REQ-API-020),
  so that its `410 APP_UPDATE_REQUIRED` reaches the app instead of the edge's `404`. A retired
  operation that is still frozen fails the generator.

**Everything else is refused by the edge with `404`**: another verb on an admitted path (`HEAD` and
`OPTIONS` included), a trailing slash, another case, a non-uuid where a uuid stands, an extra
segment, and every path no operation names.

**The shape.** One nginx `map` on `"$request_method:$uri"` with one anchored, case-sensitive regular
expression per operation, `docker/edge/include/api-admission.conf`, included at `http` level by
`conf.d/50-api.conf.template`. The vhost's only admission statement is
`if ($krt_api_admitted = 0) { return 404; }` in `include/api-allowlist.conf`. There is no prefix
rule, no read-only family, no method-scoping variable and no per-path reset: a verb is admitted or
it is not. A path placeholder takes the shape its parameter has in `openapi.json` — a uuid the uuid
class, a boolean `(true|false)` — and any other placeholder only a shape reviewed in
`EdgeAdmission.NAMED_SHAPES` (`{roleCode}`, and `{key}` as exactly the two readable settings keys);
a placeholder without one fails the generator. A retired operation's placeholders, which the
document no longer describes, take the reviewed shape of the same name, else the uuid class for
`id` and every `…Id`.

**Generated, committed, reviewed (ADR-0135).** `EdgeAdmission` (backend test scope) renders the map,
the include and the API vhost table of the nightly probe from one model; `./gradlew
:backend:generateEdgeAdmission` writes all three. The files stay committed and an admission change
is reviewed as their diff — opening an operation to the app and freezing it remain one decision
(ADR-0136): the only way to admit something is to freeze it, retire it, or name it here as
anonymous.

**Why every refusal is `404`, never `405`** (decided 2026-10-03, against the plan's sketch of `405`
where a sibling verb is admitted). An operation the vhost does not admit is not on the vhost,
whether or not its path has an admitted sibling. A `405` must carry an `Allow` header (RFC 9110
§15.5.6), which the old read-only family never sent. One refusal shape needs one map, where `405`
would need a second per-path table. And the old include already answered `404` for every other verb
on its two method-scoped admissions (`POST /api/v1/operations`, `PUT /api/v1/orders/{id}/requested`)
while answering `405` elsewhere — two semantics for one question.

**What the cut changed (2026-10-03),** simulated over all 573 documented operations and checked by
the live-nginx test:

- four documented operations the old include admitted are refused: `GET /api/v1/me/layout` (through
  the `^/api/v1/me/` prefix — REQ-API-012 said it was not on the vhost, which was wrong until now),
  `POST /api/v1/job-types` (an admin write outside the read-only family), `GET /api/v1/hangar/ships`
  and `GET /api/v1/material-requests/{id}` (the app calls only other verbs on both paths);
- every undocumented path under the two prefix rules `^/api/v1/me/` and `^/api/v1/terms/` — which
  would have admitted `/terms/admin` the day wave 1 moved it there — and every `HEAD` and `OPTIONS`;
- eleven `405` answers of the read-only family are `404` now (`PUT`/`DELETE` on missions, operations
  and orders, `PUT /settings/{key}`, `POST /refining-methods`, among them);
- nothing is admitted that was refused before.

**Cost.** One `map` lookup per request on the API vhost, evaluated in order until an entry matches;
a refused request evaluates all 246 expressions. Measured on 2026-10-03 with the edge's own image
(`pcre_jit on`, one worker, 30,000 keep-alive requests per case, two runs, worker CPU time per
request): a configuration without any admission costs about 52 µs, the old include 61–74 µs on every
request, the map 51–52 µs for an operation early in the list and 68–72 µs for the last entry and for
any refusal, which scans the whole list. The worst case of the map is therefore the old include's
ordinary case, about 18 µs of CPU, invisible against the 0.5 ms the client waited per request and
the backend's own milliseconds. `nginx -t` took 6–14 ms with the map, 9–12 ms with the old include
and 4–5 ms without either, within the noise of `docker exec`.

**Acceptance**

- [x] The committed map, the include and the probe table are the generator's output
  (`EdgeAdmissionTest`).
- [x] Admitted equals frozen plus the two anonymous reads plus retired, in both directions: the
  model's operations equal the union exactly, and every committed map entry admits exactly one
  operation's samples while every operation is admitted by exactly one entry
  (`theAdmittedSetIsExactlyTheFrozenSetTheAnonymousReadsAndTheRetired`). Proven able to fail: a
  frozen operation dropped from the model, an entry dropped from the map, and a planted
  `GET /api/v1/me/layout` entry are each reported (`aDroppedFrozenOperationOrAnUnfrozenAdmissionIsReported`).
- [x] The map parser is strict: an unanchored, prefix, case-insensitive, exact-string, `if` or
  widened-default line fails instead of being skipped (`aMapLineTheParserCannotReadFails`).
- [x] Every call of every committed app call list is admitted
  (`everyCallOfEveryServedAppBuildIsAdmitted`).
- [x] No exchange or connected-apps path is admitted under any verb (`theExchangeStaysOffTheApiVhost`).
- [x] The real nginx image runs the committed files: every sample of every admitted operation, bare
  and with a query string, reaches the upstream, and the refusal matrix — every other verb on every
  admitted path, near-miss spellings, non-uuid ids, `/me/layout`, `/terms/admin`, the exchange — is
  refused with `404` (`EdgeAdmissionNginxTest`, Testcontainers); a planted wrong expectation is
  reported.
- [x] The backend answers every admitted probe row as the table says — `200` for the two anonymous
  reads, `401` for every frozen operation (`EdgeProbeBackendStatusTest`).
- [x] The per-request cost was measured against the old include and against no admission at all
  (2026-10-03): see *Cost*.

**Enforced by:** `EdgeAdmissionTest`, `EdgeAdmissionNginxTest`, `EdgeProbeBackendStatusTest`
(backend tests, guard G-08) · `scripts/check-edge-nginx.sh` (the whole edge renders and starts) ·
`edge-deny-probe.yml` (nightly, against production) · **Code:** `EdgeAdmission`,
`docker/edge/include/api-admission.conf`, `docker/edge/include/api-allowlist.conf`,
`docker/edge/conf.d/50-api.conf.template` · **Related:** REQ-API-009, REQ-API-016, REQ-API-020,
REQ-OPS-042, REQ-SEC-037, ADR-0135, ADR-0136

---
