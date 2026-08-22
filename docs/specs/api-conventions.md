> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-06.
> **Owner area:** API · **Related:** [`security-and-access.md`](security-and-access.md), [`observability.md`](observability.md)

# API conventions

## Context & goal

Uniform, versioned, well-documented REST contracts with DTO boundaries, RFC 7807 errors,
safe pagination, and UTC time — so clients (the frontend especially) integrate against a
stable, predictable surface.

## Requirements

### REQ-API-001 — Versioned URI paths

Paths are `/api/v1/...`. Breaking changes go to a new version (`/api/v2/...`). Retired
endpoints carry `@ApiDeprecation(sunset = "YYYY-MM-DD", replacement = "/api/v2/...")`;
`DeprecationInterceptor` emits `Deprecation` / `Sunset` / `Link` headers and
`OpenApiDeprecationConfig` reflects it in the spec.

**Carve-out — internal-only endpoints:** a `/api/v1` endpoint consumed solely by the in-repo
frontend may change its response *shape* in place (no `/api/v2` bump) when frontend and
backend deploy atomically and `DtoOpenApiContractTest` guards the frontend mirror against
`openapi.json` — e.g. the inventory `/grouped` move from `items` to `stacks` (ADR-0003).

**The carve-out stops at the external contract set.** Its whole justification is the atomic
deploy, which a released native client does not have. Operations listed in REQ-API-009 are
therefore frozen against in-place shape change even though they live under `/api/v1`.

### REQ-API-002 — DTOs only at boundaries

Never expose JPA entities at controller boundaries (also ArchUnit-enforced — see
[`security-and-access.md`](security-and-access.md) REQ-SEC-003). DTOs are records; write DTOs
carry Jakarta validation (`@NotBlank`, `@NotNull`, `@Min`, `@Max`, …). Use a MapStruct
mapper (`@Mapper(componentModel = "spring")`) for Entity↔DTO; break circular refs with
`@Mapping(ignore = true)`.

### REQ-API-003 — Validation on writes

`@Valid` on every `@RequestBody` for write operations (POST/PUT/PATCH).

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

Document the format in OpenAPI and keep frontend error display in sync.

Service-layer repository lookups raise their 404 through the fetch-or-throw helper
`exception.Entities.require(optional, message)` (S1, #907) rather than a hand-written
`find*(id).orElseThrow(() -> new NotFoundException(…))`. The not-found `detail` stays
**caller-supplied, never auto-derived from the type** — `GlobalExceptionHandler.resolveDetail`
treats the message as a translation key (sentinel-guarded), so an auto-derived message would change
the wire `detail` and break the future i18n-key migration seam.

**Domain exceptions carry their own error-code contract (S4, #910).** `BadRequestException`,
`NotFoundException`, `BusinessConflictException`, `DuplicateEntityException`,
`EntityInUseException`, `ExternalServiceException`, `ReportGenerationException` and
`BankConflictException` all extend the sealed `exception.AppException`, exposing `status()`,
`code()`, `titleKey()`, `detailKey()`, `typeSuffix()` and `logLabel()` on the type itself instead of
leaving that identity scattered across `GlobalExceptionHandler`'s `CODE_*` constants and per-type
`@ExceptionHandler` methods. A single `handleAppException` dispatch handler reads those accessors
for every subtype except `NotFoundException`, whose handler stays dedicated because it also covers
three non-`AppException` JPA/JDK "not found" flavors (`EntityNotFoundException`,
`NoSuchElementException`, `NoResourceFoundException`) that cannot be sealed under this hierarchy.
Six of the eight subtypes never override an accessor: they pass their fixed
`exception.AppExceptionKind` constant to the `AppException(AppExceptionKind, String)` /
`AppException(AppExceptionKind, String, Throwable)` superclass constructor and inherit every
accessor from `AppException`, which delegates to that stored kind. `BankConflictException` is the
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

### REQ-API-005 — Pagination & sorting

All list endpoints take Spring's `Pageable` and return a `PageResponse` wrapper (total
elements, pages, current page). **Whitelist allowed sort fields in the service** — never
pass user input directly to `Sort` (unstable sorting + information-disclosure risk). Build the
`Pageable` through `PaginationUtil`, which whitelists the sort field, appends `id` as a stable
tiebreaker, and clamps `size` to `MAX_PAGE_SIZE` (100 000 — high on purpose so the "load all in one
request" surfaces are not truncated). The clamp bounds the result-set size; the global
query-execution timeout (REQ-DATA-009, finding SEC-03) bounds how long a heavy fetch may hold a
database connection.

### REQ-API-006 — All times in UTC

Store/process as `Instant` or `OffsetDateTime`; convert to the user's local timezone in the
display layer only. Write serialization tests for timezone behaviour.

### REQ-API-007 — OpenAPI documentation

Every REST endpoint carries SpringDoc annotations (`@Operation`, `@ApiResponses`). **Both
REST-serving modules ship a committed OpenAPI document, and each is the single
API-documentation artifact for its module** — kept in sync with controller changes and
regenerated by that module's `OpenApiGeneratorTest`:

| Module  |              Committed document               |         Root document bean         |
|---------|-----------------------------------------------|------------------------------------|
| backend | `backend/src/main/resources/api/openapi.json` | `backend/.../config/OpenApiConfig` |
| ingest  | `ingest/src/main/resources/api/openapi.json`  | `ingest/.../config/OpenApiConfig`  |

The frontend serves HTML, not an API, and therefore has no document of its own.

Both modules depend on springdoc **`-api`** (not `-ui`): the document is generated at
`/v3/api-docs`, no Swagger UI webjar is bundled, and `springdoc.api-docs.enabled=false` in each
module's `application-prod.yml` keeps the endpoint unreachable from a deployed environment. Both
root documents declare the `bearer-jwt` security scheme, so a generated client — for ingest, the
desktop extractor of epic #639 — knows every endpoint expects a Keycloak JWT.

That regeneration MUST be **atomic** — serialize to a temporary sibling file and move it into place,
never write the document in place. `org.gradle.parallel=true` runs `:backend:test` alongside
`:frontend:test`, and four frontend contract tests (`DtoOpenApiContractTest`,
`FrontendDtoContractTest`, …) read this file with `Files.readString`. An in-place write truncates the
1.8 MB document and streams it back over hundreds of milliseconds, so a reader landing in that window
parses a cut-off document and fails with `UnexpectedEndOfInputException` — an intermittent red build
whose cause is nowhere near the test that reports it. The ingest generator carries the same guard.

Both generators also **assert** the document's load-bearing parts (title, `bearer-jwt` scheme, the
expected paths and request/response schemas) before writing, so a controller that silently stops
being scanned fails the build instead of quietly shrinking the committed spec.

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

### REQ-API-008 — Shared controller boilerplate (argument resolvers & response helpers)

Cross-cutting controller boilerplate is factored into `backend/.../web` rather than re-hand-rolled
per controller (S11, #917). Use the shared seams; do not re-derive them inline:

- **`@CurrentUserSub String` / `@CurrentUserId UUID`** — resolved by `CurrentUserArgumentResolver`
  from the authenticated caller's JWT `sub` claim (read via `NativeWebRequest#getUserPrincipal()`,
  so no `SecurityContextHolder` coupling is introduced). A missing/non-JWT principal, a missing or
  blank subject, or (for `@CurrentUserId`) a non-UUID subject each raise `AccessDeniedException` →
  HTTP 403. These replace the per-controller `requireSub(JwtAuthenticationToken)` guards.
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

|                         Operation                          |                                                                                                                                                    Response fields a client may rely on                                                                                                                                                     |
|------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/terms/status`                                 | `accepted`, `currentVersion`                                                                                                                                                                                                                                                                                                                |
| `POST /api/v1/terms/acceptance`                            | `accepted`, `currentVersion`                                                                                                                                                                                                                                                                                                                |
| `GET /api/v1/terms/document`                               | `version`, `title`, `intro`, `sections`, `lastUpdated`                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/me/active-org-unit`                           | `orgUnitId`                                                                                                                                                                                                                                                                                                                                 |
| `GET /api/v1/me/capabilities`                              | `canSeeBlueprintOverview`, `canViewJobOrders`, `canViewOwnJobOrders`                                                                                                                                                                                                                                                                        |
| `GET /api/v1/users/me/registration-status`                 | `approvalStatus`                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/users/me`                                     | `id`                                                                                                                                                                                                                                                                                                                                        |
| `GET /api/v1/users/me/memberships`                         | `orgUnitId`, `orgUnitName`, `orgUnitShorthand`, `kind`                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/missions/search`                              | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `status`, `meetingTime`, `plannedStartTime`, `actualStartTime`, `plannedEndTime`, `isInternal`, `operation`, `owningSquadron`, `meetingPoint`                                                                                                                  |
| `GET /api/v1/missions/{id}`                                | `id`, `name`, `description`, `status`, `meetingTime`, `plannedStartTime`, `actualStartTime`, `plannedEndTime`, `isInternal`, `meetingPoint`, `operation`, `owningSquadron`, `partyLeadUser`, `partyLeadGuestName`, `registeredParticipants`, `checkedInParticipants`, `participants`, `assignedUnits`, `steps`, `objectives`, `frequencies` |
| `GET /api/v1/missions/{missionId}/finance-entries`         | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `type`, `amount`, `note`                                                                                                                                                                                                                                               |
| `GET /api/v1/missions/{missionId}/finance-entries/summary` | `total`, `incomeSum`, `incomeCount`, `expenseSum`, `expenseCount`                                                                                                                                                                                                                                                                           |
| `GET /api/v1/operations/search`                            | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `status`                                                                                                                                                                                                                                                       |
| `GET /api/v1/operations/{id}`                              | `id`, `name`, `description`, `status`, `payoutPreliminary`                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/operations/{id}/finance-summary`              | `operationId`, `totalSum`, `truncated`; row `missionId`, `missionName`, `totalSum`                                                                                                                                                                                                                                                          |
| `GET /api/v1/inventory/aggregated`                         | envelope; row `material`, `amount`, `quality`, `maxQuality`; nested `name`, `quantityType` |
| `GET /api/v1/inventory/all/grouped`                        | `material`, `totalAmount`, `averageQuality`, `maxQuality`, `stacks`; nested `user`, `location`, `personal`, `entryCount` |
| `GET /api/v1/orders`                                       | envelope; row `id`, `displayId`, `status`, `priority`, `type`, `createdAt`, `materials`, `redacted` |
| `GET /api/v1/orders/{id}`                                  | as the row, plus `comment`, `aggregatedMaterials`, `assignees`, `handovers`, `requestingOrgUnit`, `responsibleOrgUnit` |
| `GET /api/v1/org-units/bank/balances`                      | `accountId`, `accountNo`, `accountName`, `balance`, `delta30d`, `sparkline`, `orgUnitName` |
| `GET /api/v1/org-units/bank/accounts/{id}`                 | `detail`, `delta30d`, `bookingCount`; nested `account.name`, `account.accountNo`, `account.balance` |
| `GET /api/v1/org-units/bank/accounts/{id}/transactions`    | envelope; row `postingId`, `type`, `amount`, `note`, `createdAt`, `holderHandle` |
| `GET /api/v1/hangar/my-ships`                              | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `shipType`, `insurance`, `location`, `fitted`; nested `manufacturer` |
| `GET /api/v1/hangar/squadron-overview`                     | envelope as above; row `shipType`, `count`, `fittedCount` |
| `GET /api/v1/announcement`                                 | `content`, `updatedAt` — **and a `204` with no body at all when nothing is announced**                                                                                                                                                                                                                                                      |
| `GET /api/v1/notifications`                                | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `type`, `params`, `entityType`, `entityId`, `read`, `createdAt`                                                                                                                                                                                                        |
| `GET /api/v1/notifications/unread-count`                   | `count`                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/notifications/stream`                         | *(a stream, not a schema — the frozen part is the path, the verb and the event names)*                                                                                                                                                                                                                                                      |
| `GET /api/v1/operations/{id}/payouts`                      | `totalDonations`; row `participantId`, `participantName`, `payoutPreference`, `shareAmount`, `donatedAmount`, `payoutAmount`, `paidOut`                                                                                                                                                                                                     |

`GET /api/v1/users/me` is frozen for a single field. An Operation's payout rows are keyed by the
**backend user id** — not the Keycloak `sub` the app holds, and not a display name, which is
`displayName` when set and `username` otherwise and therefore cannot be matched reliably. Without
`id` the app cannot tell a member which of eighteen payout rows is theirs. The rest of the
response — email, roles, rank, memberships — stays unfrozen because the app does not read it.

`redacted` on a job order is frozen for the same class of reason as `truncated` and
`payoutPreliminary`: it qualifies the rest of the payload rather than carrying content. A requester
sees their own order with the parts that are not theirs removed (REQ-ORDERS-023), and a client that
stopped seeing the flag would present the gaps as the whole order.

`GET /api/v1/orders` is on the list as an **exact** path, and it is the one entry where the verb is
the only thing separating two different surfaces: the same path answers a `POST` that is `permitAll`
by design (the public request form). The vhost's read-only guard refuses that verb before it
arrives; the allow-list never names it.

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
own spec instead of being left to a schema check that cannot see them. Its response carries
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
adding it later is one more deliberate edit.

**Frozen means**, for an operation in the set: it keeps its path and verb; its response keeps every
field it had; its request accepts everything it accepted before (a new **required** field is a
break); and retirement goes through `/api/v2` + `@ApiDeprecation` with a sunset rather than a
deletion. Additive change stays free — new optional response fields, new optional request fields,
new endpoints.

**The spec and the test are the source of truth, not the allow-list.** The API vhost's allow-list
decides what is *reachable* and lives in the NPM admin database, which no PR can review. It must be
a subset of this set, and the two move together.

**Acceptance**

- [x] Every listed operation exists in the committed `openapi.json` with its recorded verb, and no
  recorded response field has disappeared (`ExternalContractTest`).
- [x] The set cannot be emptied to make the guard pass — its floor is asserted.
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
- [ ] Type and nullability changes are caught. **Open** — needs a schema diff of the contract
- [ ] A sunset can actually retire old builds. **Open** — depends on the minimum-app-version gate
  (exposure plan item A5), which is therefore a prerequisite for the first `/api/v2`.

**Enforced by:** `ExternalContractTest` (backend) ·
**Related:** ADR-0136, ADR-0135, ADR-0003, REQ-API-001, REQ-API-007, REQ-SEC-027
