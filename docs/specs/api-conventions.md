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

|                          Operation                          |                                                                                                                                                                                            Response fields a client may rely on                                                                                                                                                                                             |
|-------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/terms/status`                                  | `accepted`, `currentVersion`                                                                                                                                                                                                                                                                                                                                                                                                |
| `POST /api/v1/terms/acceptance`                             | `accepted`, `currentVersion`                                                                                                                                                                                                                                                                                                                                                                                                |
| `GET /api/v1/terms/document`                                | `version`, `title`, `intro`, `sections`, `lastUpdated`                                                                                                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/me/active-org-unit`                            | `orgUnitId`                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `GET /api/v1/me/capabilities`                               | `canSeeBlueprintOverview`, `canViewJobOrders`, `canViewOwnJobOrders`, `canViewBankStaff`, `canManageBank` — the bank pair is server-derived through the role hierarchy, because the me-response carries role **display** names and the bank roles carry no permissions                                                                                                                                                      |
| `GET /api/v1/users/me/registration-status`                  | `approvalStatus`                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/users/me`                                      | `id`, `isLogistician`, `isMissionManager`                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/users/me/memberships`                          | `orgUnitId`, `orgUnitName`, `orgUnitShorthand`, `kind`                                                                                                                                                                                                                                                                                                                                                                      |
| `GET`/`PUT …/users/me/payout-preference`                    | `defaultPayoutPreference`, `version` — **request** requires `preference`, `version`. Frozen as a PAIR with the read: the two me-scoped settings are columns of one `User` row sharing one optimistic-lock version, and a client that could write but not read would echo `0`                                                                                                                                                |
| `GET`/`PUT …/users/me/blueprint-sharing`                    | `shareBlueprintsGlobally`, `version` — **request** requires both; same shared version as the row above                                                                                                                                                                                                                                                                                                                      |
| `PUT …/users/me/read-announcement/{announcementId}`         | `lastReadAnnouncementId` — the one name the app reads out of the `UserDto` it answers with, to confirm the „UNGELESEN“ band may stay down. No request body                                                                                                                                                                                                                                                                  |
| `GET /api/v1/missions/search`                               | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `status`, `meetingTime`, `plannedStartTime`, `actualStartTime`, `plannedEndTime`, `isInternal`, `operation`, `owningSquadron`, `meetingPoint`                                                                                                                                                                                                  |
| `GET /api/v1/missions/{id}`                                 | `id`, `name`, `description`, `status`, `meetingTime`, `plannedStartTime`, `actualStartTime`, `plannedEndTime`, `isInternal`, `meetingPoint`, `operation`, `owningSquadron`, `partyLeadUser`, `partyLeadGuestName`, `registeredParticipants`, `checkedInParticipants`, `participants`, `assignedUnits`, `steps`, `objectives`, `frequencies`                                                                                 |
| `GET /api/v1/missions/{missionId}/finance-entries`          | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `type`, `amount`, `note`                                                                                                                                                                                                                                                                                                                               |
| `GET /api/v1/missions/{missionId}/finance-entries/summary`  | `total`, `incomeSum`, `incomeCount`, `expenseSum`, `expenseCount`                                                                                                                                                                                                                                                                                                                                                           |
| `GET /api/v1/operations/search`                             | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `status`                                                                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/operations/{id}`                               | `id`, `name`, `description`, `status`, `payoutPreliminary`                                                                                                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/operations/{id}/finance-summary`               | `operationId`, `totalSum`, `truncated`; row `missionId`, `missionName`, `totalSum`                                                                                                                                                                                                                                                                                                                                          |
| `GET /api/v1/inventory/aggregated`                          | envelope; row `material`, `amount`, `quality`, `maxQuality`; nested `name`, `quantityType`                                                                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/inventory/all/grouped`                         | `material`, `totalAmount`, `averageQuality`, `maxQuality`, `stacks`; nested `user`, `location`, `personal`, `entryCount`                                                                                                                                                                                                                                                                                                    |
| `GET /api/v1/orders`                                        | envelope; row `id`, `displayId`, `status`, `priority`, `type`, `createdAt`, `materials`, `redacted`                                                                                                                                                                                                                                                                                                                         |
| `GET /api/v1/orders/{id}`                                   | as the row, plus `comment`, `aggregatedMaterials`, `assignees`, `handovers`, `requestingOrgUnit`, `responsibleOrgUnit`, `version`, `canEdit`; nested `user.effectiveName`, `note`, `version` (the assignee edge's own). `canEdit` is `isLogisticianOrAbove() && canEditJobOrder(id)` — the app draws three writes from it, and an **absent** flag opens the screen rather than closing it                                   |
| `GET /api/v1/org-units/bank/balances`                       | `accountId`, `accountNo`, `accountName`, `balance`, `delta30d`, `sparkline`, `orgUnitName`, `canRequest`, `approvalLimit`, `approvalExempt` — the last three are what the request sheet is gated and explained by, per caller and per account                                                                                                                                                                               |
| `GET /api/v1/org-units/bank/accounts/{id}`                  | `detail`, `delta30d`, `bookingCount`; nested `account.name`, `account.accountNo`, `account.balance`                                                                                                                                                                                                                                                                                                                         |
| `GET /api/v1/org-units/bank/accounts/{id}/transactions`     | envelope; row `postingId`, `type`, `amount`, `note`, `createdAt`, `holderHandle`                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/hangar/my-ships`                               | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `name`, `shipType`, `insurance`, `location`, `fitted`; nested `manufacturer`                                                                                                                                                                                                                                                                           |
| `GET /api/v1/hangar/squadron-overview`                      | envelope as above; row `shipType`, `count`, `fittedCount`                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/announcement`                                  | `content`, `updatedAt` — **and a `204` with no body at all when nothing is announced**                                                                                                                                                                                                                                                                                                                                      |
| `GET /api/v1/notifications`                                 | envelope `content`, `page`, `totalElements`, `totalPages`; row `id`, `type`, `params`, `entityType`, `entityId`, `read`, `createdAt`                                                                                                                                                                                                                                                                                        |
| `GET /api/v1/notifications/unread-count`                    | `count`                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/notifications/stream`                          | *(a stream, not a schema — the frozen part is the path, the verb and the event names)*                                                                                                                                                                                                                                                                                                                                      |
| `POST /api/v1/notifications/{id}/read`                      | `id`, `read`                                                                                                                                                                                                                                                                                                                                                                                                                |
| `POST /api/v1/notifications/read-all`                       | `affected`, `unreadCount` — the count settles the badge from the same response that changed it                                                                                                                                                                                                                                                                                                                              |
| `DELETE /api/v1/notifications/{id}`                         | *(204, no body — the frozen part is the path and the verb)*                                                                                                                                                                                                                                                                                                                                                                 |
| `DELETE /api/v1/notifications/read`                         | `affected`, `unreadCount`                                                                                                                                                                                                                                                                                                                                                                                                   |
| `GET /api/v1/operations/{id}/payouts`                       | `totalDonations`; row `participantId`, `participantName`, `payoutPreference`, `shareAmount`, `donatedAmount`, `payoutAmount`, `paidOut`                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/personal-inventory`                            | envelope; row `id`, `name`, `note`, `locationUexId`, `locationType`, `locationName`, `quantity`, `version`                                                                                                                                                                                                                                                                                                                  |
| `POST /api/v1/personal-inventory`                           | `id`, `name`, `quantity`, `locationUexId`, `locationType`, `version` — **request** requires `name`, `quantity`, `locationUexId`, `locationType`                                                                                                                                                                                                                                                                             |
| `GET /api/v1/personal-inventory/{id}`                       | as the list row                                                                                                                                                                                                                                                                                                                                                                                                             |
| `PUT /api/v1/personal-inventory/{id}`                       | as the create — **request** additionally requires `version`                                                                                                                                                                                                                                                                                                                                                                 |
| `DELETE /api/v1/personal-inventory/{id}`                    | *(204, no body — the frozen part is the path and the verb)*                                                                                                                                                                                                                                                                                                                                                                 |
| `GET /api/v1/uex/locations/search`                          | `uexId`, `type`, `name`, `starSystemName`, `parentName`                                                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/personal-blueprints`                           | envelope; row `id`, `productKey`, `productName`, `acquiredAt`, `note`, `removable`, `version`                                                                                                                                                                                                                                                                                                                               |
| `POST /api/v1/personal-blueprints`                          | `id`, `productKey`, `productName`, `version` — **request** requires `productKey`                                                                                                                                                                                                                                                                                                                                            |
| `PUT /api/v1/personal-blueprints/{id}`                      | as the row — **request** requires `version` only; note and date are optional                                                                                                                                                                                                                                                                                                                                                |
| `DELETE /api/v1/personal-blueprints/{id}`                   | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/personal-blueprints/{id}/recipe`               | `productName`, `variantCount`, `requirementGroups`, `ingredients`; ingredient `kind`, `name`, `quantityScu`, `quantityUnits`, `minQuality`, `quantityType` — **both** quantity scales, so a client renders the one its column is labelled for instead of converting                                                                                                                                                         |
| `GET /api/v1/personal-blueprints/craftability`              | `blueprintId`, `recipeResolved`, `craftable`, `craftableWithRefinery`, `limitingMaterialName`, `limitingMaterialNameWithRefinery`; row `materialName`, `requiredScu`, `availableScu`, `missingScu`, `quantityType`                                                                                                                                                                                                          |
| `GET /api/v1/blueprints/products/search`                    | `productKey`, `name`, `manufacturerName`, `ownedByCurrentUser`                                                                                                                                                                                                                                                                                                                                                              |
| `POST /api/v1/hangar/ships`                                 | `id`, `name`, `shipType`, `insurance`, `location`, `fitted`, `version` — **request** requires `insurance`, `shipTypeId`                                                                                                                                                                                                                                                                                                     |
| `PUT /api/v1/hangar/ships/{id}`                             | as the create; the app additionally sends `version`, which the schema does not demand                                                                                                                                                                                                                                                                                                                                       |
| `DELETE /api/v1/hangar/ships/{id}`                          | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                            |
| `GET /api/v1/ship-types`                                    | envelope; row `id`, `name`, `manufacturer` — **anonymous** (REQ-SEC-037)                                                                                                                                                                                                                                                                                                                                                    |
| `GET /api/v1/locations/home-locations`                      | `id`, `name`                                                                                                                                                                                                                                                                                                                                                                                                                |
| `POST /api/v1/inventory`                                    | `id`, `material`, `location`, `amount`, `quality`, `personal` — **request** requires `amount`, `locationId`                                                                                                                                                                                                                                                                                                                 |
| `GET /api/v1/inventory/all/stack/entries`                   | envelope; row `id`, `material`, `location`, `amount`, `quality`, `personal`, `note`, `user`                                                                                                                                                                                                                                                                                                                                 |
| `POST /api/v1/inventory/{id}/book-out`                      | as above — **request** requires `amount`, `version`; its `type` is `DISCARD` / `TRANSFER` / `SELL`                                                                                                                                                                                                                                                                                                                          |
| `POST /api/v1/inventory/{id}/personal-rebook`               | as above — **request** requires `amount`, `version`                                                                                                                                                                                                                                                                                                                                                                         |
| `PUT /api/v1/inventory/{id}/note`                           | `id`, `note` — **request** requires `version`                                                                                                                                                                                                                                                                                                                                                                               |
| `GET /api/v1/materials/search`                              | envelope; row `id`, `name`, `quantityType` — **anonymous** (REQ-SEC-037)                                                                                                                                                                                                                                                                                                                                                    |
| `GET /api/v1/locations/search`                              | envelope; row `id`, `name` — **anonymous**                                                                                                                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/users/search`                                  | envelope; row `id`, `effectiveName` — **not** anonymous                                                                                                                                                                                                                                                                                                                                                                     |
| `GET /api/v1/materials/{id}/terminals`                      | `terminalId`, `terminalName`, `priceSell` — **anonymous**                                                                                                                                                                                                                                                                                                                                                                   |
| `POST /api/v1/orders/{id}/assignees/{userId}`               | `id`, `assignees`, `version`; nested `user.effectiveName`, `note`, `version` — self-assignment is open to every member                                                                                                                                                                                                                                                                                                      |
| `DELETE /api/v1/orders/{id}/assignees/{userId}`             | as the add                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `PUT /api/v1/orders/{id}/assignees/{userId}/note`           | as the add — **request** requires nothing; the `version` it carries is the **assignee edge's**, not the order's                                                                                                                                                                                                                                                                                                             |
| `DELETE /api/v1/orders/{id}/assignees/{userId}/note`        | as the add                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/orders/{id}/material-collection`               | `inventoryEntryId`, `version`, `ownerName`, `ownerId`, `location`, `locationId`, `materialName`, `quality`, `quantity`, `allocatedQuantity`, `delivered` — the row's own `version` is the optimistic lock the delivered flag echoes; `ownerName`/`location` are redacted to null for a requesting-side viewer                                                                                                               |
| `DELETE …/orders/{id}/inventory/{entryId}/unlink`           | *(204, no body)* — the earmark goes, the stock stays                                                                                                                                                                                                                                                                                                                                                                        |
| `DELETE …/orders/{id}/materials/{materialId}`               | *(204, no body)* — removes a required material and every row that pointed at it                                                                                                                                                                                                                                                                                                                                             |
| `PATCH /api/v1/inventory/{id}/delivered`                    | *(response unread)* — **request** requires `delivered`, `jobOrderId`, `version`; the version is the **row's**, and the gate is `canEditInventoryItem`, not the order's                                                                                                                                                                                                                                                      |
| `PUT /api/v1/orders/{id}/status`                            | `id`, `status`, `version` — **request** requires `status`, `version`; `status` is `OPEN` / `IN_PROGRESS` / `REJECTED` / `COMPLETED`, and the operation needs `LOGISTICIAN` + per-order scope                                                                                                                                                                                                                                |
| `POST /api/v1/missions/{id}/join`                           | `id`, `participants`, `user`, `registeredParticipants` — self-enrolment; answers with the whole Einsatz because it creates the row. Its **request** body is optional and so is every field in it (`desiredJobTypeId`, `payoutPreference`, added 2026-09-02, ADR-0154) — a bodyless POST is what every build before that sends, and nothing here is frozen as required                                                       |
| `DELETE /api/v1/missions/{id}/participants/{pid}/slim`      | *(204, no body)* — the **slim** pair; the legacy full-DTO one is `@ApiDeprecation`-marked with a sunset                                                                                                                                                                                                                                                                                                                     |
| `DELETE …/missions/{id}/units/{unitId}/crew/{crewId}/slim`  | *(204, no body)* — same pair, same reason: the legacy sibling carries a sunset, so the app re-reads the Einsatz rather than folding an answer that does not come                                                                                                                                                                                                                                                            |
| `POST …/participants/{pid}/check-in/slim`                   | `id`, `user`, `startTime` — the row alone, which is the point of the slim variants                                                                                                                                                                                                                                                                                                                                          |
| `POST …/participants/{pid}/check-out/slim`                  | `id`, `user`, `endTime`                                                                                                                                                                                                                                                                                                                                                                                                     |
| `PUT …/participants/{pid}/payout-preference/slim`           | `id`, `payoutPreference` — **request** requires `preference`, which is `PAYOUT` / `DONATE`                                                                                                                                                                                                                                                                                                                                  |
| `POST /api/v1/finance-entries`                              | `id`, `missionId`, `participant`, `type`, `amount`, `note`, `version` — **request** requires `amount`, `missionId`, `participantId`, `type`; `type` is `INCOME` / `EXPENSE`                                                                                                                                                                                                                                                 |
| `PUT /api/v1/finance-entries/{entryId}`                     | as the create — **request** requires `amount`, `type`, `version`; the version is the entry's optimistic lock                                                                                                                                                                                                                                                                                                                |
| `DELETE /api/v1/finance-entries/{entryId}`                  | *(204, no body)*                                                                                                                                                                                                                                                                                                                                                                                                            |
| `PUT /api/v1/operations/{id}/payouts/paid-out`              | `participantKey`, `paidOut`, `paidOutAt`, `paidOutByName` — **request** requires `participantKey`; needs `MISSION_MANAGER`, and taking a confirmation back additionally needs `OFFICER` or `ADMIN`                                                                                                                                                                                                                          |
| `POST /api/v1/bank/deposits`                                | *(response unread)* — **request** requires `accountId`, `amount`, `holderId`. `holderId` is the one worth naming: custody is per org unit, so a balance without a holder is money nobody is accountable for                                                                                                                                                                                                                 |
| `POST /api/v1/bank/withdrawals`                             | `pendingRequest` — **request** requires `accountId`, `amount`, `holderId`. Over the KRT employee ceiling the server **files** the attempt instead of booking it and answers `202`; `pendingRequest` is how a client tells the two apart, and losing it makes a shipped build report a filed withdrawal as a completed one (REQ-BANK-047, ADR-0109)                                                                          |
| `POST /api/v1/bank/transfers`                               | `pendingRequest` — **request** requires `amount`, `sourceAccountId`, `sourceHolderId`, `destinationAccountId`, `destinationHolderId`; same ceiling, same 202                                                                                                                                                                                                                                                                |
| `GET /api/v1/bank/transfer-fee-rate`                        | `rate` — an absent one is read as zero, so a rename quotes „no fee“ on a transfer that charges one                                                                                                                                                                                                                                                                                                                          |
| `GET …/org-units/bank/accounts/{id}/settings`               | `accountId`, `accountName`, `balanceTarget`, `version`, `canSetTarget`, `canConfigureVisibility`, `visibilityConfigurable`, `allMembersSupported`, `availableRoleCodes`, `grantedRoleCodes`, `allMembersGranted`, `approvalLimits`, `canConfigureApprovalLimits` — the `can*` flags are what the app offers its controls from; the last two were added 2026-09-03 with the phase-P writes, and the app had always read them |
| `PUT`/`DELETE …/accounts/{id}/approval-limit/all-members`   | as the settings — **request** requires `limit` on the `PUT`; the `DELETE` is addressed entirely by its path. Both answer the whole settings object, which is what lets the section redraw from the answer                                                                                                                                                                                                                   |
| `PUT`/`DELETE …/approval-limit/area-members`                | as above                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `PUT`/`DELETE …/approval-limit/role/{roleCode}`             | as above; the bucket must be one the account actually offers (`availableRoleCodes`), else `400`                                                                                                                                                                                                                                                                                                                             |
| `PUT`/`DELETE …/approval-limit/user/{userId}`               | as above; a user limit beats the tier limit                                                                                                                                                                                                                                                                                                                                                                                 |
| `PUT …/bank/accounts/{id}/balance-target`                   | as the settings — **request** requires `version` only; sending no target clears it                                                                                                                                                                                                                                                                                                                                          |
| `POST`/`DELETE …/bank/accounts/{id}/visibility/role/{code}` | as the settings; addressed entirely by path, no body                                                                                                                                                                                                                                                                                                                                                                        |
| `PUT …/bank/accounts/{id}/visibility/all-members/{enabled}` | as the settings; the switch is a path segment                                                                                                                                                                                                                                                                                                                                                                               |
| `GET …/org-units/bank/requests` and `…/requests/foreign`    | `id`, `accountId`, `accountName`, `targetAccountId`, `type`, `amount`, `note`, `status`, `requesterHandle`, `rejectReason`, `applicableLimit`, `requiresOwnerApproval`, `requiredApprover`, `ownerApprovalGranted`, `ownerApprovalGrantedByHandle`, `createdAt`, `version` — `requiredApprover` names the approver **class**; there is no count of approvals anywhere (REQ-BANK-041/-047)                                   |
| `GET …/org-units/bank/transfer-targets`                     | `id`, `name`, `accountNo`                                                                                                                                                                                                                                                                                                                                                                                                   |
| `POST …/org-units/bank/requests`                            | `id`, `status`, `requiresOwnerApproval`, `requiredApprover`, `version` — **request** requires `sourceAccountId`, `type`, `amount`; `type` is `DEPOSIT` / `WITHDRAWAL` / `TRANSFER`                                                                                                                                                                                                                                          |
| `PUT …/org-units/bank/requests/{id}`                        | as the read — **request** requires `amount` only; the account and the kind are not editable                                                                                                                                                                                                                                                                                                                                 |
| `POST …/org-units/bank/requests/{id}/cancel`                | `id`, `status`, `version` — **request** requires `version`                                                                                                                                                                                                                                                                                                                                                                  |
| `POST`/`DELETE …/requests/{id}/owner-approval`              | `id`, `ownerApprovalGranted`, `ownerApprovalGrantedByHandle`, `version` — **no request body on either verb**, so no version is echoed                                                                                                                                                                                                                                                                                       |
| `GET /api/v1/promotion/evaluations/my`                      | `categoryName`, `topicName`, `assignedLevel` — me-scoped; the level is a **field**, not a frozen enum, since the levels are the organisation's to name                                                                                                                                                                                                                                                                      |
| `GET /api/v1/promotion/eligibility/my`                      | `fromRank`, `toRank`, `eligible`, `hasConfiguredRules`, `checks`, `topicName`, `categoryName`, `minimumLevel`, `requiredCount`, `achievedCount`, `satisfied` — `hasConfiguredRules` separates "no rules exist" from "you do not meet them"                                                                                                                                                                                  |
| `GET /api/v1/app/version-policy`                            | `minimumVersionCode`, `latestVersionCode`, `releasesUrl` — **anonymous** (REQ-SEC-037); frozen so a shipped app can be told to STOP (REQ-API-010)                                                                                                                                                                                                                                                                           |
| `GET /api/v1/refinery-orders/my-orders`                     | envelope; row `id`, `status`, `location`, `refiningMethod`, `startedAt`, `durationMinutes`, `endsAt`, `goods`, `oreSales`, `profit`, `version` — `endsAt` is frozen on the LIST only; the detail has none and the app computes it                                                                                                                                                                                           |
| `GET /api/v1/refinery-orders/{id}`                          | as the list, without `endsAt`                                                                                                                                                                                                                                                                                                                                                                                               |
| `POST /api/v1/refinery-orders/{id}/store`                   | *(no body)* — **request** requires `items`; the endpoint marks the order stored whatever that list holds, which is why the field is frozen                                                                                                                                                                                                                                                                                  |
| `GET /api/v1/material-exchange/offers`                      | envelope; row `id`, `kind`, `material.quantityType`, `itemName`, `itemQuantity`, `owner.effectiveName`, `ownerOrgUnits.shorthand`, `mine`, `quality`, `amount`, `releasedAt`, `remark`, `interestCount`, `interestedHandles`, `viewerInterested`, `version`                                                                                                                                                                 |
| `GET /api/v1/material-requests`                             | as the offers, with `requestedAmount`, `minQuality` and `postedAt` in place of `amount`, `quality` and `releasedAt`                                                                                                                                                                                                                                                                                                         |
| `POST`/`DELETE …/offers/{id}/interest`                      | `id`, `interestCount`, `viewerInterested`, `version` — the updated row, which is what lets the app replace one entry instead of re-reading the page                                                                                                                                                                                                                                                                         |
| `POST`/`DELETE …/material-requests/{id}/interest`           | as the offer's                                                                                                                                                                                                                                                                                                                                                                                                              |
| `POST …/offers/{id}/deactivate`                             | `id`, `status`                                                                                                                                                                                                                                                                                                                                                                                                              |
| `POST …/material-requests/{id}/deactivate`                  | `id`, `status`                                                                                                                                                                                                                                                                                                                                                                                                              |
| `POST /api/v1/material-exchange/offers`                     | *(no body)* — **request** requires `inventoryItemId`, `offeredAmount`                                                                                                                                                                                                                                                                                                                                                       |
| `POST /api/v1/material-requests`                            | *(no body)* — **request** requires `materialId`, `requestedAmount`                                                                                                                                                                                                                                                                                                                                                          |
| `GET /api/v1/material-exchange/releasable-items`            | `inventoryItemId`, `materialName`, `quantityType`, `quality`, `amount`, `locationName`, `alreadyReleased` — the caller's OWN stacks, which is why it is not anonymous                                                                                                                                                                                                                                                       |

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
shape) is exactly what the org-unit switcher renders; it is the one entry in the exemption ledger.
A **query parameter's enum constants** are not reached by the required-enum guard, which walks
request and response schemas only — `kind` on the offer-sheet picker is frozen by name and type
here, and its `MATERIAL` / `ITEM` vocabulary stays pinned by the offers response that carries the
same field.

|                        Operation                        |                                                        Frozen query parameters                                                         |
|---------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/missions/search`                           | `query:string`, `status:array`, `start:string`, `end:string`, `page:integer`, `size:integer`, `sort:string`                            |
| `GET /api/v1/missions/{missionId}/finance-entries`      | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/inventory/aggregated`                      | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/inventory/all/grouped`                     | `materialIds:array`                                                                                                                    |
| `GET /api/v1/orders`                                    | `status:array`, `page:integer`, `size:integer`                                                                                         |
| `DELETE /api/v1/orders/{id}/assignees/{userId}/note`    | `version:integer`                                                                                                                      |
| `GET /api/v1/org-units/bank/accounts/{id}/transactions` | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/hangar/my-ships`                           | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/hangar/squadron-overview`                  | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/notifications`                             | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/operations/search`                         | `query:string`, `status:array`, `start:string`, `end:string`, `page:integer`, `size:integer`, `sort:string`                            |
| `GET /api/v1/personal-inventory`                        | `q:string`, `page:integer`, `size:integer`                                                                                             |
| `GET /api/v1/uex/locations/search`                      | `q:string`, `limit:integer`                                                                                                            |
| `GET /api/v1/personal-blueprints`                       | `q:string`, `page:integer`, `size:integer`                                                                                             |
| `GET /api/v1/personal-blueprints/craftability`          | `includeRefinery:boolean`                                                                                                              |
| `GET /api/v1/blueprints/products/search`                | `q:string`, `limit:integer`                                                                                                            |
| `GET /api/v1/ship-types`                                | `page:integer`, `size:integer`, `sort:string`                                                                                          |
| `GET /api/v1/inventory/all/stack/entries`               | `materialId:string`, `locationId:string`, `userId:string`, `quality:integer`, `owningOrgUnitId:string`, `page:integer`, `size:integer` |
| `GET /api/v1/materials/search`                          | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/locations/search`                          | `search:string`, `page:integer`, `size:integer`                                                                                        |
| `GET /api/v1/users/search`                              | `query:string`, `page:integer`, `size:integer`                                                                                         |
| `GET /api/v1/live-sync/stream`                          | `topics:string`                                                                                                                        |
| `GET /api/v1/refinery-orders/my-orders`                 | `status:array`, `page:integer`, `size:integer`                                                                                         |
| `GET /api/v1/material-exchange/offers`                  | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/material-requests`                         | `page:integer`, `size:integer`                                                                                                         |
| `GET /api/v1/material-exchange/releasable-items`        | `q:string`, `kind:string`                                                                                                              |

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
must be mandatory goes to `/api/v2`.

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
adding it later is one more deliberate edit.

**Frozen means**, for an operation in the set: it keeps its path and verb; its response keeps every
**recorded** field; its request accepts everything it accepted before (a new **required** field is a
break); and retirement goes through `/api/v2` + `@ApiDeprecation` with a sunset rather than a
deletion. Additive change stays free — new optional response fields, new optional request fields,
new endpoints.

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
- [x] A sunset can actually retire old builds — **closed by REQ-API-010** (2026-08-24). The gate
  the first `/api/v2` was waiting on now exists: the server names a floor and the app refuses to run
  below it. What that unblocks is narrower than "old builds are gone", and the difference matters
  when planning a sunset — the floor stops a build from *running*, it does not remove it from
  anyone's phone, and a member who never opens the app never learns of it.

**Enforced by:** `ExternalContractTest` (backend) ·
**Related:** ADR-0136, ADR-0135, ADR-0003, REQ-API-001, REQ-API-007, REQ-SEC-027

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
  stance (plan Q8) and this is its single exception. A version gate that answers only after a
  successful login is silent in the one case it exists for: when the break is in the auth flow, the
  old build cannot log in, and it would show an authentication error where the design calls for an
  update wall — telling the member their credentials are wrong, which they are not. It publishes
  three integers and a public release URL; no caller identity goes in and none comes out. It is
  enumerated in REQ-SEC-037 like every other anonymous path, and its status is pinned by a test.
- **The floor and the newest build are two numbers.** Collapsing them makes every release a forced
  one, because the app could no longer tell "your build is no longer served" from "a newer build
  exists" — and it only has a wall for the first.
- **The default floor is `0`.** A server nobody has configured must not refuse every installed
  build. Locking members out is the expensive direction of a wrong default; serving an old build
  for one more day is the cheap one.

Configuration (`app.android.*`), not a table: raising the floor is what an operator does at the
moment a contract breaks, and it has to work without a migration, an admin screen or a deploy.

The operation is itself in the frozen set, for an inverted reason worth stating — every other entry
is frozen so a shipped app keeps working, this one so a shipped app can be told to stop. A renamed
`minimumVersionCode` would leave the build that most needs the answer, the one already too old,
reading "no floor" and carrying on against a contract that no longer exists.

**The CTA is a deviation from the design.** Chapter 14 points its button at a store listing;
distribution is GitHub Releases plus Obtainium (plan Q1), so `releasesUrl` names the release page
instead. Recorded here rather than left as a silent difference between design and build.

**Enforced by:** `AppVersionPolicyControllerTest`, `ExternalContractTest`,
`ApiVhostAnonymousSurfaceTest` (backend) ·
**Related:** REQ-API-009, REQ-SEC-037, ADR-0136, app issue krt-profit/basetool-android#67
