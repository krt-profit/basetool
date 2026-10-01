# 50 — Non-backend modules, build and delivery under a domain split (prefix MB)

Scope: `ingest`, `keycloak-spi`, `logging-support`, `test-support`, the Gradle build logic, and what
options A–D (briefing) cost in build, CI, images and delivery. Includes the owner update of
2026-09-29 (backend `/api/v1` may be re-cut, `/exchange/v1` is frozen) as MB-01.

All paths are relative to the worktree `$REPO`
(HEAD `95e945326`) unless they start with `vault:` (= `$VAULT`).
Scripts and raw outputs named `50-modules-build-*` are in the scratchpad. Nothing was built or run
with Gradle; every count comes from a named script or command (Data appendix, §3).

---

## 1. Summary — the ten most important conclusions

1. **The ingest relay calls exactly 14 backend operations on 13 paths under `/api/v1/exchange/**`**:
   URL-wise inside `/api/v1` and inside `backend/src/main/resources/api/openapi.json`, access-wise a
   separate internal surface (gateway identity plus `X-Ingest-On-Behalf-Of`, the exhaustive
   13-pattern `ActingMemberFilter.EXCHANGE_PATHS`, never on the `api.*` allow-list). A re-cut may
   move the code behind them but must keep method, path, JSON, problem codes and statuses, the five
   relay headers and the identity model identical (MB-01; `ActingMemberFilter.java:98-115`,
   `ExchangeController.java:134`, `50-modules-build-xchroutes.out.txt`).
2. **The backend half of the frozen exchange contract has no build-time guard.** The 28 schemas and
   101 conformance fixtures are read only by ingest tests; a drifting backend answer is caught at run
   time by the gateway and turned into `502 BACKEND_RELAY_FAILED` (`ExchangeController.java:948-955`).
   A backend wire-contract test must exist before any re-cut (MB-02).
3. **Two exchange draft routes reuse web-import DTOs** (`RefineryExtractDto`,
   `BlueprintImportPreviewDto`, `RefineryImportDraftDto`), so re-cutting the refinery or blueprint
   web API would silently change frozen exchange behaviour; decouple first (MB-04).
4. **Wire identifiers shared across modules are duplicated with sparse parity pins**: 1 of 5 relay
   headers, 0 of 7 gate code/status pairs, 0 of 14 relay paths, 0 for the Redis registry mirror,
   the handoff keys, and the SPI↔backend precheck and admin extension (MB-03).
5. **Ingest is package-by-kind with 9 two-way package dependencies**; `exchange` holds 26 classes /
   5,416 lines (43 % of the module) spanning six concerns, and `ExchangeController` (1,076 lines)
   orchestrates validation, relay, budgets and staging. Re-packaging by concern with ArchUnit
   cycle/confinement rules is a low-risk clarity gain for the internet-facing module (MB-05); the
   order of the four exchange filters inside the Spring Security chain is untested (MB-06) and PIT
   never mutates the gates (MB-07).
6. **keycloak-spi** (16 classes, one flat package, Java-21 bytecode) pins 1 of its 6 service
   registrations in a test, and nothing ties its compile version (catalog `keycloak = 26.7.4`) to the
   runtime image tag `26.7@sha256:…` although it compiles against private Keycloak APIs (MB-08, MB-09).
7. **The root `subprojects { plugins.withId(…) }` convention (354 of 527 lines) is
   configuration-cache clean** (CI runs `build --configuration-cache`, `ci.yml:63`) and adequate for
   option A; its project-name-keyed maps (heap, JaCoCo floors, PIT targets) default silently for any
   new module, so option B/C needs typed per-module configuration and an included `build-logic` first
   (MB-11).
8. **Option B would add ~45 backend modules**, each needing up to 11 registrations beyond its own
   build script (settings, two Dockerfiles, image-reuse and SBOM scripts, a path filter, floors, PIT,
   heap, Flyway check), 4 of which fail silently when forgotten, and the compiler would still
   not see 113 SpEL bean references in `@PreAuthorize`, 187 JPA associations (23 to `User`, 14 to
   `OrgUnit`) or the entity-passing `MANDATORY` hops. Recommendation: **A now, C per proven leaf
   domain, no B-wholesale, no D** (MB-14, MB-18).
9. **Test-context fragmentation and the ~8.5-min `:backend:test` critical path are not solved by B**:
   231 `@SpringBootTest` classes, a static proxy of 49 candidate context keys (September measurement:
   38 against the default cache of 32); consolidation (BLD-PERF-03, still open) is the lever (MB-15).
10. **Package- and path-keyed build wiring must move in the same PR as any package-by-domain
    refactor** — PIT `…service.*`, SpotBugs `backend.model.*`, 13 cross-module test inputs, and
    `DtoMirrorConsistencyTest`, which silently skips a DTO whose backend twin moved (MB-12, MB-13).
    JPMS is not recommended (MB-17); three vault notes carry five stale statements (MB-20).

---

## 2. Findings

### MB-01 — The ingest↔backend relay surface under a re-cut `/api/v1` (owner update 2026-09-29)

**Evidence.**

*Which backend endpoints the relay calls.* `ExchangeController` builds every target as
`BACKEND + …` with `BACKEND = "/api/v1/exchange"` (`ingest/…/web/ExchangeController.java:134`;
call sites `:184, :296, :389, :536, :624, :839, :900`). The backend serves exactly these 14
operations (script `50-modules-build-xchroutes.py` over `backend/…/controller/exchange/`):

| # | Public route (frozen) | Backend operation | Backend request DTO | Backend response DTO | Backend check | Public response schema | Body reaches the client |
|---|---|---|---|---|---|---|---|
| 1 | `GET /exchange/v1` | `GET /api/v1/exchange/me/installation` | – | `ExchangeInstallationDto` | `@exchangeGate.allows('exchange.connect')` | `service-document` (built by gateway) | only `installationId` |
| 2 | `POST …/me/installation` | `POST /api/v1/exchange/me/installation` | `ExchangeInstallationLabelRequest` | `ExchangeInstallationDto` | `exchange.connect` | `installation` | yes |
| 3 | `POST …/me/account-check` | `POST /api/v1/exchange/me/account-check` | `ExchangeAccountCheckRequest` | `ExchangeAccountCheckDto` | `exchange.connect` | `account-check-response` | yes |
| 4 | `POST …/catalog/resolve` | `POST /api/v1/exchange/catalog/resolve` | `ExchangeResolveRequest` | `ExchangeResolveResponse` | `allowsAny` | `resolve-response` (+ `warnings`) | yes |
| 5 | `GET …/catalog/locations` | `GET /api/v1/exchange/catalog/locations` | – | `ExchangeLocationListDto` | `allowsAny` | `location-list` | yes |
| 6 | `GET …/me/blueprints?cursor&limit` | `GET /api/v1/exchange/me/blueprints?cursor&limit` | – | `ExchangeBlueprintPageDto` | `exchange.blueprints.read` | `page#/$defs/blueprintPage` | yes |
| 7 | `POST …/me/blueprints/changes` | `POST /api/v1/exchange/me/blueprints/changes` | `ExchangeBlueprintChangeSet` | `ExchangeChangeResultDto` | `exchange.blueprints.write` | `change-result` (+ `warnings`) | yes |
| 8 | `GET …/me/stock` | `GET /api/v1/exchange/me/stock` | – | `ExchangeStockPageDto` | `exchange.stock.read` | `page#/$defs/stockPage` | yes |
| 9 | `POST …/me/stock/changes` | `POST /api/v1/exchange/me/stock/changes` | `ExchangeStockChangeSet` | `ExchangeChangeResultDto` | `exchange.stock.write` | `change-result` | yes |
| 10 | `GET …/me/ships` | `GET /api/v1/exchange/me/ships` | – | `ExchangeShipPageDto` | `exchange.hangar.read` | `page#/$defs/shipPage` | yes |
| 11 | `POST …/me/ships/changes` | `POST /api/v1/exchange/me/ships/changes` | `ExchangeShipChangeSet` | `ExchangeChangeResultDto` | `exchange.hangar.write` | `change-result` | yes |
| 12 | `GET …/me/org-demand` | `GET /api/v1/exchange/me/org-demand` | – | `ExchangeOrgDemandDto` | `exchange.demand.read` | `org-demand` | yes |
| 13 | `POST …/me/drafts/blueprints` | `POST /api/v1/exchange/me/drafts/blueprints` | `ExchangeBlueprintDraftDto` | **`BlueprintImportPreviewDto` (web DTO)** | `exchange.drafts.blueprints` | `draft-result` (built by gateway) | no — staged in Redis for the frontend |
| 14 | `POST …/me/drafts/refinery-orders` | `POST /api/v1/exchange/me/drafts/refinery-orders` | **`RefineryExtractDto` (web DTO)** | **`RefineryImportDraftDto` (web DTO)** | `exchange.drafts.refinery` | `draft-result` (built by gateway) | no — staged in Redis |

`GET /exchange/v1/openapi.json` and `GET /exchange/v1/schemas/{name}` never reach the backend.

*Service identity.* The relay calls with the gateway's own client-credentials token
(`app.ingest.service-account.*`, `ServiceAccountProperties.java`, cached by
`ServiceAccountTokenProvider`), never with the member's token (ADR-0129). Headers
(`ExchangeRelay.java:337-373`): `Authorization: Bearer <gateway token>`, `X-Ingest-On-Behalf-Of`
(member `sub`), `X-Exchange-Client`, `X-Exchange-Capabilities` (sorted, comma-joined),
`X-Exchange-Installation` (DPoP key thumbprint), optional `X-Exchange-Connected-At`, `Accept:
application/json, application/problem+json`, a sanitised `Accept-Language` (≤ 100 chars,
`:80-84`), the correlation id. The backend honours the on-behalf-of header only for a caller whose
`azp` is in `app.security.ingest-gateway.client-ids` (`backend/src/main/resources/application.yml:94-96`)
and only on the 13 parsed patterns of `ActingMemberFilter.EXCHANGE_PATHS` (`ActingMemberFilter.java:98-112`),
then swaps the security context to the member's reduced exchange authentication (REQ-XCH-009,
`docs/specs/desktop-ingest.md:86-125`); every handler re-checks with `@exchangeGate.allows(…)`
(14/14, table above). Transport: `exchangeRestClient` on the JDK client, HTTP/1.1, 5 s connect,
30 s read, body cap `app.ingest.max-payload-bytes`, breaker `exchange`, bulkhead
`exchangeLargeChangeSets` for change sets above 100 ops (ADR-0204 Amendment 2,
`ExchangeController.java:626-628`).

*How answers become the external behaviour.* A 2xx body is validated against the public response
schema and passed on (rows 2–12), else `502` (`ExchangeController.java:948-955`). A refusal passes
only if its `code` is in the gateway's tables (`ExchangeRelay.java:122-191`): 4 translated codes
(`ACCESS_DENIED→NOT_PERMITTED`, `VALIDATION_FAILED`/`BAD_REQUEST→SCHEMA_INVALID`,
`OPTIMISTIC_LOCK→VERSION_CONFLICT`), 16 pass-through codes with fixed details, of which 7 gate
codes must arrive with one exact status; everything else becomes `502 BACKEND_RELAY_FAILED`
(`:404-440`). The codes are emitted by **backend-wide** components, not only by the exchange layer:
`GlobalExceptionHandler` (`ACCESS_DENIED`, `VALIDATION_FAILED`, `BAD_REQUEST`, `OPTIMISTIC_LOCK`;
`ACCESS_DENIED` and `BAD_REQUEST` also from `BasetoolErrorController` on the `/error` dispatch),
`TermsAcceptanceAccessFilter` (`TERMS_NOT_ACCEPTED`), `PendingApprovalAccessFilter`
(`PENDING_APPROVAL`, `NO_ROLE`), `ActingMemberFilter` (`ACTING_MEMBER_REFUSED`),
`ExchangeProblemException` (7 gate codes, `CURSOR_EXPIRED` 410, `MASS_CHANGE_CONFIRMATION_REQUIRED`
409; `ExchangeProblemException.java:39-64, 104-194`) — command in §3.

*Non-REST contracts the external behaviour also depends on.*
- Redis registry mirror `exchange:registry` (`schemaVersion` 1: backend
  `ExchangeRegistryMirrorDocument.java:43`, ingest `ExchangeRegistryReader.java:49, 159-202`) and
  revocation keys `exchange:deny:` / `exchange:revoked:` (backend `RedisExchangeRevocationMirror.java:40,43`,
  ingest `ExchangeRevocationReader.java:39,42`).
- Handoffs: ingest writes `ingest:handoff:` (`HandoffStagingService.java:49`), the frontend consumes
  it (`frontend/…/service/IngestHandoffService.java:50`); `HandoffKind` and `StagedHandoff` exist in
  both modules (`ingest/…/model/dto/`, `frontend/…/model/dto/`, enum constants identical today).
- The answers carry frontend URLs clients open: `/refinery-orders/create?handoff=…`,
  `/personal-inventory/blueprints?handoff=…` (`IngestProperties.java:57-58`,
  `ingest/src/main/resources/application.yml:57-58`) and `/connected-apps/confirm?handoff=…`
  (`ExchangeController.java:116, 705-707`). The confirm page posts the frozen change-set JSON to
  `/api/v1/connected-apps/mass-changes/preview|confirm` (`ConnectedAppsController.java:76-109`,
  member session only).

*Is it `/api/v1` or internal?* Both, and that is the risk: the 14 operations sit in the `/api/v1`
URL space and in the backend OpenAPI document (13 of its 440 paths; command in §3), next to the web's
and the app's operations, while ADR-0216 decisions 4–6 make them a gateway-only surface
(`ExternalContractTest.theExchangeStaysOffTheApiVhost`, `ExternalContractTest.java:2611-2655`;
`ConnectedAppsControllerTest`/`ExchangeCatalogControllerTest` per REQ-XCH-001). They are **not** in
the frozen app contract set that `ExternalContractTest` compares with the previous release
(`:2169-2248`), so no build-time gate treats them as frozen.

*Android.* The app never calls the gateway (it uses the `api.*` allow-list, ADR-0135; its Keycloak
client lost both ingest scopes — `vault:80 Plans/Improvement Audit 2026-09.md` row "Testing realm to
production shape"). A re-cut of `/api/v1` has no ingest-side Android impact.

**What a backend re-cut may touch.** The package, module and controller class that serves each of
the 14 operations; every internal service behind them; DTO class names and packages as long as the
JSON is identical; every other `/api/v1` endpoint, including the web import endpoints — **after**
MB-04 has given the draft routes their own DTOs; `/api/v1/connected-apps/**` together with the
frontend, provided the mass-change confirm still accepts the frozen change-set JSON.

**What it must not touch without a coordinated, proven-identical change on both sides.** The 14
method/path pairs and the `cursor`/`limit` query parameters; request and response field names,
types, optionality, enum values, and tolerant reading of unknown fields (ADR-0219 §2; only 3 of 37
`model/dto/exchange` files carry `@JsonIgnoreProperties(ignoreUnknown = true)`, the rest rely on the
global mapper — command in §3); the RFC 7807 body with a `code` member; every relayed code and its
status (list above, including those from `GlobalExceptionHandler` and the two access filters); the
five header names and their trust rule; the `azp` allow-list identity model; the Redis mirror,
revocation and handoff formats; the three landing URLs; own-data-only, journaling/undo and the
mass-change guard semantics; and the **global JSON input handling** the exchange DTOs inherit —
`JacksonConfig` registers `NormalizedStringDeserializer` for every string (trim, NFC, length cap,
blank→null; `NormalizedStringDeserializer.java:30-48`) and sets `FAIL_ON_NULL_FOR_PRIMITIVES=false`
(`JacksonConfig.java`), while unknown-property handling is left at Spring Boot's default (not set in
the backend; the effective Boot 4 / Jackson 3 default is to verify). A re-cut that moves this
configuration per domain must keep it for the exchange layer: the length cap is an input-size guard.

**Proposed change.**
1. Declare `/api/v1/exchange/**` the exchange domain's **internal published API**, frozen in
   behaviour like `/exchange/v1`, in `docs/specs/external-exchange.md` and ADR-0216 (amendment).
2. Fence it from the re-cut tooling: publish it as its own springdoc group / document (e.g.
   `backend/…/api/exchange-internal.openapi.json`) instead of inside the web/app `openapi.json`, so
   per-domain API diffs, the frontend's generated types and `DtoOpenApiContractTest` no longer see
   it. Keep the path. If the owner wants it out of `/api/v1` (e.g. `/internal/exchange/v1`), do it as
   its own two-release step: backend serves both, `ActingMemberFilter` lists both (exhaustive,
   never a prefix), ingest switches `BACKEND`, old path removed. Touch list (grep in §3): 1 ingest
   constant, `ActingMemberFilter` (13 patterns + prefix), `ExchangeInstallationInterceptor.java:55`,
   8 controller `@RequestMapping`s, 24 backend and 7 ingest test files, `desktop-ingest.md`,
   `external-exchange.md`, `security-and-access.md`, ADR-0216, arc42 §8. No monitoring rule or
   dashboard names the path (grep over `monitoring/` empty).
3. Add the guards of MB-02 and MB-03 **before** the first re-cut PR, and require the `e2e` label on
   every PR that touches `controller/exchange`, `service/exchange`, `model/dto/exchange`,
   `ActingMemberFilter`, `GlobalExceptionHandler`, `TermsAcceptanceAccessFilter` or
   `PendingApprovalAccessFilter` (a repo-lint step that fails on such a diff without the label).

**Pros.** Makes the freeze explicit and testable; lets the domain re-cut of `/api/v1` proceed
without an accidental external change; the fenced document removes 14 operations from the web/app
contract surface.
**Cons.** One more OpenAPI document to keep current (its CI staleness check must be extended,
`ci.yml:65-70`); a path move, if chosen, costs two releases.
**Risks and regressions (security).** Moving the path wrongly could widen `ActingMemberFilter` to a
prefix — the on-behalf-of header would then select an identity on paths it was never meant for
(REQ-SEC-029). Guard: `ActingMemberFilterPathMatchingTest` and `ActingMemberFilterChainTest` must be
extended to both paths during a transition; `ExternalContractTest.theExchangeStaysOffTheApiVhost`
keeps both off `api.*`. A re-cut that renames a generic code in `GlobalExceptionHandler` would turn
a `403 NOT_PERMITTED` into `502`, i.e. fail closed but visibly change behaviour — guard MB-03 (code
parity) and MB-02.
**Effort.** S (spec/ADR, label gate) + M (separate document) + L (optional path move).
**Prerequisites.** Owner decision on fencing vs moving; ADR-0216 amendment; MB-02, MB-03, MB-04 first.

---

### MB-02 — No build-time test holds the backend's exchange DTOs to the frozen schemas

**Evidence.** The contract lives in `ingest/src/main/resources/exchange/v1/schemas/` (28 files) and
`ingest/src/main/resources/api/exchange-v1.openapi.json` (16 operations), with 101 fixtures under
`docs/exchange/examples/v1/` (26 directories; counts in §3). Only ingest reads them
(`ExchangeContractTest`, `ExchangeSchemasTest`, `ExchangeChangeRouteTest`, `ExchangeDraftRouteTest`;
`grep -rln 'docs/exchange|examples/v1'` finds no backend or frontend test). The backend's producer
side — 37 files in `backend/…/model/dto/exchange/` plus the three web DTOs of MB-04 — is tested by
MockMvc controller tests (`backend/src/test/…/controller/exchange/*Test`, 16 classes) that assert
chosen fields, not the schema. `grep -rln 'schema\.json|networknt'` over `backend/src/test` returns
nothing. The only other check is the gateway at run time (`ExchangeController.java:948-955`, `502`)
and the label-gated E2E (`ExchangeRoundTripE2eTest`, `ExchangeSyncE2eTest`,
`ExchangeConnectionsE2eTest`, `ExchangeDepartureE2eTest`: 8 `@Test`; `e2e.yml:14-17`).

**Impact on domain separation.** Under the owner update the exchange layer becomes the one backend
API that must not move while everything around it is re-cut; without this test the freeze is
enforced in production by 502s.

**Proposed change.** A backend `ExchangeWireContractTest` (test scope only):
- For each of the 8 body-bearing operations (rows 2, 3, 4, 7, 9, 11, 13, 14): every `valid`
  fixture deserialises into the backend request DTO with the application's own mapper and passes
  Bean Validation; the same fixture with an extra unknown property and an `extensions` member
  still does (tolerant reader); the 3 paged reads accept every `cursor`/`limit` the gateway admits.
- For each of the 11 operations whose backend body reaches the client (rows 2–12): run the existing
  controller tests' representative answers (or a MockMvc call per route with seeded data) and
  validate the JSON against the public response schema with
  `com.networknt:json-schema-validator` (already in the catalog, `libs.versions.toml:29, 74`, used by
  ingest).
- Read the schemas and fixtures by path from `ingest/src/main/resources/exchange/v1/schemas/` and
  `docs/exchange/examples/v1/`, declared as `:backend:test` inputs like `crossModuleParitySources`
  (`backend/build.gradle.kts:103-116`) — the repo's established pattern; no shared module needed.

**Pros.** Moves contract drift detection from production to the PR; makes the re-cut safe for the
14 operations; cheap because fixtures exist.
**Cons.** Adds a test dependency to the backend; representative response instances need seeding.
**Risks and regressions (security).** None to authorization — test scope only. Positive: a
re-cut that loosens a request DTO (e.g. drops a size limit) is still refused by the gateway schema,
but one that tightens it (a new `@NotNull`) would silently start refusing valid client writes; this
test catches both. Guard for the test itself: assert that the number of validated fixtures equals
the number on disk (no vacuous pass).
**Effort.** M.
**Prerequisites.** None; before MB-04 and any re-cut. REQ-XCH-011/-026 "Enforced by" amended.

---

### MB-03 — Cross-module wire identifiers are duplicated; most have no parity test

**Evidence** (commands in §3).

| Identifier | Declared in | Pinned by a build-time test? |
|---|---|---|
| 5 relay headers | ingest `ExchangeRelay.java:78-100`; backend `support/ActingMemberHeader.java:31-57` | **1 of 5** — `OnBehalfOfHeaderParityTest` (on-behalf-of only) |
| 7 gate codes + exact statuses | ingest `ExchangeRefusals.java:57-75` + `ExchangeRelay.GATE_STATUSES` `:137-152`; backend `ExchangeProblemException.java:46-64,104-171` | **0** |
| 16 pass-through / 4 translated codes | ingest `ExchangeRelay.java:122-191`; backend 6 classes (MB-01) | **0** |
| 14 relay targets | ingest `ExchangeController.java` literals; backend `ActingMemberFilter.java:98-112` + 8 `@RequestMapping`s | **0** (E2E only) |
| 10 capability scopes | backend `model/ExchangeCapability.java`; ingest `ExchangeRoutes.java:46-64`; `exchange-v1.openapi.json` | ingest↔OpenAPI yes (`ExchangeRoutesContractTest`); **backend enum: no** |
| Registry mirror document (`schemaVersion`, client fields) | backend `ExchangeRegistryMirrorDocument` / `ExchangeRegistrySnapshot.Client`; ingest `ExchangeRegistry` + `ExchangeRegistryReader.java:159-202` | **0** |
| Revocation key prefixes | backend `RedisExchangeRevocationMirror.java:40,43`; ingest `ExchangeRevocationReader.java:39,42` | **0** |
| Handoff key prefix, `StagedHandoff`, `HandoffKind` | ingest `HandoffStagingService.java:49`, `model/dto/*`; frontend `IngestHandoffService.java:50`, `model/dto/*` | **0** (E2E `IngestHandoffE2eTest`) |
| Landing pages in exchange answers | ingest `IngestProperties.java:57-58`, `ExchangeController.java:116` | catalogued in `FrontendPageRoutes.java:57,76,82`, but no test links ingest to that list |
| SPI precheck: path, `X-KRT-SPI-Secret`, JSON fields | backend `DiscordAccountExistenceController.java:54,61`, `DiscordAccountExistenceRequest/Response`; SPI `BackendAccountChecker.java:51, 132-199` | **0** |
| SPI admin extension `basetool-exchange` + path | backend `KeycloakService.java:88, 726`; SPI `ExchangeClientSessionResourceProviderFactory.java:37`, `ExchangeClientSessionResource.java:65-66` | **0** (backend test pins only its own string) |

Why it matters, concretely: the registry reader fails closed on `enabled`/`status`/`capabilities`
but **falls back silently** on `minClientVersion`, `requestsPerMinute` and `writesPerDay`
(`ExchangeRegistryReader.java:195-201`) — a renamed field on the backend would drop a client's
minimum version (REQ-XCH-024) and its tighter limits without an error.

**Proposed change.** One small parity family in the repo's existing style (source- or file-reading
tests with Gradle input declarations, like `OnBehalfOfHeaderParityTest`):
`RelayHeaderParityTest` (all 5), `ExchangeGateCodeParityTest` (codes + statuses both ways,
backend `ExchangeProblemException` ↔ ingest `GATE_STATUSES`/`DETAILS`), `ExchangeRelayPathParityTest`
(every ingest relay target is in `EXCHANGE_PATHS` and mapped by a backend controller, and vice
versa), `ExchangeCapabilityParityTest` (backend enum = ingest `ExchangeRoutes` = OpenAPI scopes), a
committed registry-mirror fixture written by a backend test and parsed by an ingest test, a
handoff fixture shared by ingest and frontend tests, an ingest test that asserts its three landing
paths are in `FrontendPageRoutes.PAGES` (test-support is already on ingest's test classpath,
`ingest/build.gradle.kts:52`), and SPI↔backend parity for the precheck and the extension path.

**Pros.** Closes the gaps the ingest split and the exchange epic opened; all S-sized; each test
fails loudly (existence asserts, as `LiveSyncTopicRegistryParityTest` already does).
**Cons.** Source-reading tests are coupled to file paths — they must move with MB-05/MB-08 (that is
their point: `OnBehalfOfHeaderParityTest` fails if `ExchangeRelay.java` moves).
**Risks (security).** None negative. Positive: prevents silent loss of the minimum-client-version
gate and of per-client limits; keeps the gate codes' statuses aligned so a backend refusal cannot
reach a client as a different status.
**Effort.** S each, M for the family.
**Prerequisites.** None.

---

### MB-04 — The exchange draft routes reuse the web import DTOs

**Evidence.** `ExchangeDraftController` accepts `RefineryExtractDto` and returns
`RefineryImportDraftDto` and `BlueprintImportPreviewDto` (xchroutes table rows 13–14).
`RefineryExtractDto` is also the request DTO of the web's `RefineryImportController`;
`BlueprintImportPreviewDto` is returned by `PersonalBlueprintController` and
`AdminPersonalBlueprintController`; `RefineryImportDraftDto` is mirrored in the frontend
(`frontend/…/model/dto/RefineryImportDraftDto.java`) — `grep -rln` in §3.

**Impact.** The frozen request schema `refinery-draft.schema.json` is validated at the gateway and
the same JSON is then bound to a **web** DTO. Re-cutting the refinery import API (allowed by the
owner update) would change what the frozen route accepts. The response side is internal (staged in
Redis, read by the frontend) and may change together with the frontend.

**Proposed change.** Give the draft routes their own request DTOs in `model/dto/exchange`
(`ExchangeRefineryDraftRequest`, mapped onto the import service's internal command), exactly as the
blueprint draft already has `ExchangeBlueprintDraftDto`; keep the staged response type internal and
document it as a backend↔frontend contract (MB-03 handoff fixture).

**Pros.** Anti-corruption layer where the frozen and the re-cuttable APIs meet; lets the refinery
and blueprint domains re-cut freely. **Cons.** One mapping more; duplicate-looking records.
**Risks (security).** The new DTO must keep every Bean Validation constraint and size bound of
`RefineryExtractDto` (import caps are a DoS guard); guard: MB-02 fixtures plus a test that the new
DTO's constraints are at least as strict as the gateway schema's `maxItems`/`maxLength`.
**Effort.** S–M. **Prerequisites.** MB-02.

---

### MB-05 — Ingest is package-by-kind; re-package by concern and gate it with ArchUnit

**Evidence.** 75 main classes, 12,553 lines (`find ingest/src/main/java -name '*.java'`). Packages
(`50-modules-build-ingest-pkg.out.txt`): `exchange` 26 classes / 5,416 lines, `config` 21 / 2,295,
`web` 8 / 2,122, `filter` 8 / 1,060, `service` 3 / 674, `metrics` 3 / 527, `logging` 2, `ratelimit` 1,
`model.dto` 2. Class-level jdeps (`jdeps-ingest.txt`) yields **9 two-way package pairs**:
`config⇄exchange` (13/11 class pairs), `config⇄filter`, `config⇄logging`, `config⇄metrics`,
`config⇄web`, `exchange⇄filter`, `exchange⇄web` (4/17), `filter⇄web`, `service⇄web`. `exchange`
mixes six concerns (`50-modules-build-ingest-classes.txt`): DPoP/token gate (8 classes), registry
gate (8, incl. the mirror-age gauge), limits/budget (3), idempotency (2), relay and request context
(2), contract (1), plus observability (2: `ExchangeRefusals`, `ExchangeLogContext`).
Relay concerns sit in `config` (`RestClientConfig`, `ResponseSizeLimitInterceptor`) and `service`
(`ServiceAccountTokenProvider`); `ExchangeController` has 1,076 lines and 11 collaborators
(`:140-150`) and performs schema check → relay (with bulkhead choice) → answer check → budget-bounded
staging → quota refund (`:513-711`). Redis is touched by 6 classes, `RestClient` by 4 (command §3).
The module's ArchUnit test has 4 rules (no JPA, `@PreAuthorize` on controllers/GET/POST;
`ingest/src/test/…/ArchitectureTest.java:48-93`) — no cycle rule, although ADR-0047 introduced one
for the backend for exactly this situation.

**Proposed change.** Target packages (pure moves, same root package, so component and
`@ConfigurationPropertiesScan` scanning is unchanged): `edge` (servlet filters, per-IP buckets),
`auth` (token gate, DPoP nonces/proof validation/replay stores, htu converter, IdP-unavailable
filter), `gate` (registry reader/mirror age, revocations, routes, client versions, request
context), `limits` (limit filter, quotas, budget), `idempotency`, `relay` (`ExchangeRelay`, rest
client config, size interceptor, service-account token), `handoff` (staging, `HandoffKind`,
`StagedHandoff`), `contract` (schemas, documents), `web` (controllers, problem rendering),
`observability` (metric names, refusal counter, log context, privacy filter, banner), `config`
(assembly and guards). Split `ExchangeController` into route controllers (service document,
reads, changes, drafts, member) over one `ExchangePipeline` (validate → relay → check answer →
problem) and one `ExchangeStaging` (budget-bounded draft and mass-change staging). New ArchUnit
rules: slices free of cycles (config as assembly exempt); only `relay` (and `config`) may depend on
`org.springframework.web.client`; only `gate`, `limits`, `idempotency`, `handoff` may depend on
`org.springframework.data.redis`; `edge` must not depend on `relay`, `handoff` or `web`
controllers.

**Pros.** The internet-facing module's security invariants ("who can call the backend", "who can
write Redis", "what runs before authentication") become structural and reviewable per package;
removes the 9 cycles; the controller shrinks to routing.
**Cons.** ~75 moved files and ~78 test files; source-path parity tests move with them
(`backend/build.gradle.kts:106` names `ingest/…/exchange/ExchangeRelay.java`).
**Risks and regressions (security).** Behaviour must stay byte-identical (the exchange is frozen).
Guards: `IngestEndpointSurfaceTest`, `ExchangeRoutesContractTest`, `FilterOrderTest`, the 7
`web/*RouteTest`s, `ExchangeControllerTest`, JaCoCo floor 0.93/0.85 (`build.gradle.kts:228-241`),
plus a golden-answer test per route (fixture in, status/headers/body out) recorded **before** the
move. A moved `ExchangeRelay.java` fails `OnBehalfOfHeaderParityTest` loudly (asserts existence) —
update the path in the same PR. No change to `SecurityConfig`'s chain order (MB-06 pins it).
**Effort.** M. **Prerequisites.** MB-06 (pin first), MB-03 (parity tests that will move).

---

### MB-06 — The order of the exchange filters inside the Spring Security chain is untested

**Evidence.** `SecurityConfig.filterChain` adds `IdentityProviderUnavailableFilter` before the bearer
filter, then `UserIdMdcFilter` → `ExchangeTokenGateFilter` → `ExchangeGateFilter` →
`ExchangeLimitFilter` → `ExchangeIdempotencyFilter` (`ingest/…/config/SecurityConfig.java:271-310`).
`FilterOrderTest` pins only the five servlet filters ahead of `springSecurityFilterChain`
(`FilterOrderTest.java:66-96`); `grep -rln 'FilterChainProxy|getFilterChains'` over
`ingest/src/test` is empty.
**Impact.** The order is the gateway's security argument: limits and idempotency must only ever see
an authenticated, registry-admitted request (their keys use client and member from the gate's
context). A reorder during MB-05 or a Spring Security upgrade would be caught only indirectly.
**Proposed change.** Extend `FilterOrderTest`: fetch `FilterChainProxy`, take the chain matching
`/exchange/v1/me/stock`, and assert the subsequence of the six filters.
**Pros/cons.** Cheap, precise; couples the test to Spring Security's internal filter list (stable
public API `getFilterChains()`).
**Security.** Positive only. **Effort.** S. **Prerequisites.** None.

---

### MB-07 — Mutation testing never reaches the gateway's gates (and is package-keyed everywhere)

**Evidence.** Root PIT config: `targetClasses = de.greluc.krt.profit.basetool.${project.name}.service.*`
(`build.gradle.kts:267-268`). For ingest that is 3 classes (`HandoffStagingService`,
`ServiceAccountTokenProvider`, `ExchangeDocuments`); the gates live in `exchange.*` and `filter.*`.
`pitest.yml:23` runs `matrix.module: [backend, frontend]` — ingest applies the plugin
(`ingest/build.gradle.kts:12`) but is never run.
**Impact.** Also a domain-split landmine: a package-by-domain backend (option A or B) moves
`…backend.service.*` away, and the target pattern then matches nothing. `pitest.yml` fails on a
missing or empty `mutations.xml` (CI-03 fix, `vault:10 Systems/Testing.md:101-109`), so this one
fails loudly — but the pattern must be re-keyed in the refactor PR.
**Proposed change.** Per-module PIT targets as data (MB-11 extension), ingest targets
`…ingest.exchange.*`, `…ingest.filter.*` (after MB-05: `auth`, `gate`, `limits`, `idempotency`,
`edge`); add ingest to the weekly matrix.
**Pros/cons.** Measures test strength where the attack surface is; weekly job gets longer (UNKNOWN
by how much — measure with one run).
**Security.** Positive. **Effort.** S. **Prerequisites.** None (or with MB-05).

---

### MB-08 — keycloak-spi: one flat package, three concerns, one of six registrations pinned

**Evidence.** 16 classes, one package `…keycloak.spi` (≈2,000 lines; `wc -l` in §3): Discord
federation and gate (10 classes), backend precheck (`BackendAccountChecker`, `BackendTrustSupport`),
exchange/consent (`ExchangeClientSession*` ×3, `DeviceConsentLoginForms*` ×2). Six service files
under `keycloak-spi/src/main/resources/META-INF/services/`; only
`LoginFormsProviderFactory` is read back by a test (`DeviceConsentLoginFormsProviderTest.java:128-138`).
Java-21 bytecode by `options.release.set(21)` (`keycloak-spi/build.gradle.kts:13`); logging via
`@JBossLog` enforced by `keycloak-spi/lombok.config` (inverts the root's
`lombok.log.jbosslog.flagUsage = ERROR`). The module depends on no project (`build.gradle.kts:17-38`)
and calls the backend over the JDK `HttpClient` with a pinned PKCS#12 truststore
(`BackendTrustSupport.java:60-107`), a shared secret header and fail-open semantics
(`BackendAccountChecker.java:83-120`).
**Impact.** The SPI is already a clean, separate deployable with one inbound (Keycloak SPI
interfaces) and two outbound seams (Discord, backend precheck) plus one inbound HTTP extension; a
domain split of the backend touches it only through the `identity` domain's precheck endpoint and
the exchange disconnect call (MB-03). Its Java-21 bytecode limits it to language features
available at release 21; ADR-0223 decision 2 excludes JEP 513 there explicitly
(`docs/adr/0223-only-final-java-features-and-no-preview-flags.md:14-15, 73-76`), and
`--release 21` makes javac refuse anything newer, so no extra gate is needed.
**Proposed change.** Sub-packages `spi.discord`, `spi.backend`, `spi.exchange`; one
`ServiceRegistrationsTest` that reads all six service files, loads every class, asserts it
implements the SPI interface and is found by `ServiceLoader`; keep the module free of project
dependencies (no shared kernel may reach it: it would have to be Java-21 bytecode and
Spring-free).
**Pros.** A renamed package cannot silently unregister the membership gate (REQ-SEC-016) or the
identity provider. **Cons.** Six service files change in the same PR.
**Risks (security).** A missing authenticator registration makes the first-broker-login flow fail
(fail closed) — availability, not bypass; the test prevents both. **Effort.** S.
**Prerequisites.** None.

---

### MB-09 — The SPI compiles against Keycloak 26.7.4 internals; nothing ties that to the image

**Evidence.** Catalog `keycloak = "26.7.4"` (`gradle/libs.versions.toml:48`) feeds `compileOnly`
`keycloak-services` and `-server-spi-private` (`keycloak-spi/build.gradle.kts:18-21`). The runtime
image is `quay.io/keycloak/keycloak:26.7@sha256:82a7…` (`docker-compose.yml:98`,
`quadlet/systemd/keycloak.container:8`, `docker/sandbox/keycloak/Dockerfile:27`), a minor tag pinned
by digest and bumped by Dependabot (`.github/dependabot.yml:34`). `grep` for any script or test
comparing the two found none (§3). The vault records the image as 26.7.4 on 2026-09-27 via `javap`
(`vault:10 Systems/Keycloak SPI.md:167-173`). `ExchangeClientSessionResource` and
`DeviceConsentLoginFormsProvider` use internal classes (`AuthenticationManager`,
`UserSessionManager`, `FreeMarkerLoginFormsProvider` fields; vault note `:150-151, 186-189`).
**Impact.** Not a domain-separation issue but a module-boundary one: the SPI's real contract is with
Keycloak internals, and today it is checked only by the label-gated E2E with the sandbox image and
by the deploy health gate (which catches a load failure, not a behaviour change, ADR-0055).
**Proposed change.** Pin the image tag to the patch (`26.7.4@sha256:…`) in the three files and add a
repo-lint step that fails when it differs from the catalog; Dependabot's digest bumps then surface
as a PR that must also bump the catalog (and rebuild/re-test the SPI).
**Pros.** The unit tests and the compile run against what production loads. **Cons.** Dependabot
PRs need a second edit.
**Security.** The SPI holds the fail-closed membership gate; testing it against another Keycloak
than production weakens that assurance. Positive only. **Effort.** S. **Prerequisites.** None.

---

### MB-10 — Shared libraries: what a split needs, and what must stay out

**Evidence.** `logging-support`: 4 classes (`LogSafe`, `PiiMasker`, two Logback wrappers), no beans,
no Spring Boot plugin, `api` logback + logstash encoder (`logging-support/build.gradle.kts:18-21`);
shipped inside the three boot jars and listed as `SHIPPED_INSIDE` (`check_sbom_coverage.py:39-45`);
scope closed to domain meaning (ADR-0205 decision 4). `test-support`: 6 classes; test-only
(`NOT_SHIPPED`, `check_sbom_coverage.py:31-37`); consumers per class (command §3):
`EndpointEnumeration` backend/frontend/ingest, `RedisAclTemplate` 4/3/2 files, `TestImages` 5/4/3,
`ProfiledLogbackConfig` 1/1/1 — but **`FrontendPageRoutes` is used by 7 frontend files and nobody
else** (it lives there so the frontend's `e2e` source set and `check` share it,
`vault:10 Systems/Testing.md:362-370`). No module uses `java-test-fixtures` (`grep` §3).
**Assessment per option.**
- **A** needs no new Gradle module: the shared kernel is a backend package (and, separately, a
  frontend package). ADR-0205 is untouched.
- **B/C** need a backend-internal kernel module (ids and value types, `OptimisticLock` helpers,
  problem/exception base types, the `Roles` constants that 145 `@PreAuthorize` annotations
  reference). ADR-0205 rejected "a general `common` module"; a kernel must be narrowly named, have
  its own ADR, and be consumed by backend modules only.
- **Must stay out of every shared library:** DTOs shared between backend and frontend (ADR-0205 §4,
  ADR-0161, arc42 §11.2 — the frontend keeps its mirrors); Spring components in `logging-support`;
  any authorization or tenancy decision (`OwnerScopeService`, `*SecurityService`, `@exchangeGate`
  beans) in a module that frontend or ingest could depend on; anything the SPI would need (it must
  stay Java-21 and Spring-free, MB-08); test code in a runtime module (the SEC-17 jar guard only
  checks `application-test.*`, `build.gradle.kts:154-178`).
**Proposed change.** Keep `logging-support` as is. Move `FrontendPageRoutes` into a frontend
`testFixtures` source set (`java-test-fixtures`; the frontend's `test` and `e2e` suites can both
consume it) so `test-support` stays cross-module-only; use `testFixtures` per domain module in C.
**Pros.** Keeps every shared artifact single-purpose. **Cons.** A small build change in the frontend;
`java-test-fixtures` behaviour with the hand-made `e2e` source set is to verify.
**Security.** `testFixtures` never reach `runtimeClasspath`/`bootJar` (Gradle documentation — to
verify); keep the SEC-17 guard. **Effort.** S. **Prerequisites.** For B/C: a new ADR for the kernel.

---

### MB-11 — Build logic: keep the root convention for A, move to `build-logic` before B/C

**Evidence.** Root script 527 lines; `subprojects {}` spans lines 122–475 (354 lines) with nine
`plugins.withId` blocks: `java` 71 lines, Spring Boot 14, JaCoCo 53, PIT 23, Checkstyle 12, Spotless
35, CycloneDX 81, SpotBugs 22, Licensee 33 (`grep -n '^  plugins.withId'`, §3). Module scripts:
backend 136, frontend 684, ingest 78, keycloak-spi 39, logging-support 25, test-support 35.
Project-name-keyed data: test heap map (`:183-185`, 3 names, default 1024m), JaCoCo instruction and
branch floors (`:228-241`, 4 names, default **0.50/0.40**), PIT target package derived from
`project.name` (`:267`), SBOM output path from `project.name` (`:352-353`). Cross-project state:
the frontend reads `rootProject.extra["ossLicenseUrlAliases"]` (`frontend/build.gradle.kts:168-172`).
CI builds with the configuration cache (`ci.yml:63, 76`); no `buildSrc` or `build-logic` exists.
`gradle.properties` holds caching, parallel, daemon and heap only (5 lines) — `configureondemand` is
gone (see MB-20).
**Assessment.** For **A** the root pattern is sufficient and CC-clean. Its weakness is that a new
module gets conventions only for the plugins it remembers to apply, and silently gets default
floors: a module split out of `backend` (0.82/0.65) would be held to 0.50/0.40 without anyone
deciding it. For **B/C** (dozens of modules) an included build is the standard shape.
**Proposed change.**
1. Now (A): replace the name-keyed maps with a typed extension registered in `subprojects`
   (`basetool { testHeap = …; coverage { instruction = …; branch = … }; mutationTargets = … }`),
   set per module; fail configuration when a `java` project lacks Checkstyle, SpotBugs, Spotless or
   JaCoCo (a check in the `java` block).
2. Before B/C: an included `build-logic` with precompiled convention plugins
   (`basetool.java-conventions`, `basetool.boot-application`, `basetool.library`,
   `basetool.backend-domain-module`) that apply the full plugin set at once; move the
   `buildscript { constraints }` floors (`build.gradle.kts:1`, `backend/build.gradle.kts:1`,
   `ingest/build.gradle.kts:1`, `frontend/build.gradle.kts:4`) into `build-logic`'s dependencies.
**Pros.** Impossible to forget FindSecBugs or a floor; readable module scripts; prepares Isolated
Projects (which rejects cross-project configuration such as `subprojects {}` and `rootProject.extra`
— to verify).
**Cons (cost list for step 2).** `verification-metadata.xml` regenerated for the `kotlin-dsl`
plugin and its Kotlin artifacts (ADR-0208 command); whether the root file covers included builds is
to verify. `docker/app/Dockerfile` and `docker/sandbox/keycloak/Dockerfile` need
`COPY build-logic/` (then automatically a shared image input, `image_reuse_plan.py:92-109`);
`dependency-check.yml:10-26` and `sandbox-images.yml:16` filter on `**/build.gradle.kts`, which does
not match precompiled `*.gradle.kts` under `build-logic/src/main/kotlin` — add `build-logic/**`;
Kotlin compilation of `build-logic` on a cold image build (UNKNOWN seconds — measure); access to the
version catalog from precompiled plugins needs a workaround (to verify).
**Risks and regressions (security).** A lost convention silently removes a gate (FindSecBugs,
Checkstyle, a coverage floor). Guards: before/after diff of `./gradlew build --dry-run` task lists
per project; `./gradlew help --configuration-cache` twice (second must reuse); identical SBOMs
(`verifyCyclonedxBom`); SpotBugs XML with `total_classes > 0` per module; printed floors.
**Effort.** Step 1 S, step 2 M. **Prerequisites.** Step 2 only if the owner chooses C or B.

---

### MB-12 — Path-keyed cross-module test wiring must move with any package or module move

**Evidence.** Test inputs declared by file path: backend `crossModuleParitySources` (ingest
`ExchangeRelay.java`, ingest and frontend `ObservationPrivacyFilter.java`),
`apiVhostAllowList`, `liveSyncTopicRegistrySource` (frontend `LiveSyncTopicClass.java`),
`backendQuadletEnvTemplate` (`backend/build.gradle.kts:89-136`); frontend `backendDtoMirrorSources`
(backend `model/dto/*.java`), `backendOpenApiDocument`, `backendProdConfig`, `testTlsKeystore`,
`e2eAudienceParitySources`, `ossBundledComponentSources` (`frontend/build.gradle.kts:291-330`);
ingest `exchangeContractFixtures`, `exchangeContractBaseline` (`ingest/build.gradle.kts:63-78`);
test-support `productionRedisImageSources` (`test-support/build.gradle.kts:27-35`) — 13 inputs.
Most readers fail loudly on a missing file (`LiveSyncTopicRegistryParityTest.java:130-135`,
`OnBehalfOfHeaderParityTest` existence assert). **`DtoMirrorConsistencyTest` does not:** it skips
every frontend DTO whose backend twin is not in `backend/…/model/dto` (`:97-101`) and fails only
when **no** pair is left (`:132`). A package-by-domain move of backend DTOs (option A or B) would
drop DTOs out of the mirror check one by one with a green build.
**Impact.** Every domain-separation step moves files; these guards are keyed on today's layout, so
the refactor itself is what switches them off unless each move carries its path updates.
**Proposed change.** Make the mirror test locate backend twins by class name across the backend
source tree (or from `openapi.json` schemas), and fail on an unpaired frontend DTO unless it is on
an explicit allow-list; update the Gradle input to the new roots in the same PR as each move. Add a
refactor checklist item: "every path in a `inputs.file(s)` of a build script still exists"
(a small repo-lint script can assert it).
**Pros.** Keeps the recurring "Property or field cannot be found" guard alive through the refactor.
**Cons.** Test gets a class-name index. **Security.** Indirect: DTO drift has caused redaction-relevant
field mismatches before (`MissionPeerRedactor` relies on explicit fields); keeping mirrors honest
protects them. **Effort.** S. **Prerequisites.** Before the first backend DTO move.

---

### MB-13 — Package-keyed analysis configuration must be re-keyed by the refactor PR

**Evidence.** PIT targets `…${project.name}.service.*` (MB-07). SpotBugs exclusions:
`~de\.greluc\.krt\.profit\.basetool\.backend\.model\..*` for `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` and a
FQCN for `BasetoolErrorController` (`config/spotbugs/exclude.xml:7-18`); whether these bugs are
reported at `reportLevel = HIGH` (`build.gradle.kts:431`) is UNKNOWN without a run — moving
entities out of `backend.model` may surface findings or may change nothing. ArchUnit rules keyed on
`..controller..`/`..service..`/`..support..` (briefing; other agents). The backend ArchUnit import
uses `importPackages("de.greluc.krt.profit.basetool.backend")` with only `DO_NOT_INCLUDE_TESTS`
(`ArchitectureTest.java:61-63`), so classes in other jars on the test classpath **are** imported —
option B keeps ArchUnit coverage only as long as the test runs in a module that has every domain
module on its test runtime classpath.
**Impact.** Same as MB-12, for analysis rather than tests: package-by-domain (A) changes every
`..service..`/`..model..` predicate at once.
**Proposed change.** In every package-move PR: re-key PIT targets, SpotBugs filters and ArchUnit
package predicates in the same diff; add a "the rule saw classes" assertion to each rule family
(e.g. `allowEmptyShould(false)`, ArchUnit's default `archRule.failOnEmptyShould` — to verify in
ArchUnit 1.5.1), so a re-keyed predicate that matches nothing fails.
**Pros/cons.** Prevents vacuous rules; small overhead per PR.
**Security.** The `@PreAuthorize`-on-every-controller rules are security gates; a vacuous version
would let an unannotated controller through. **Effort.** S per PR. **Prerequisites.** None.

---

### MB-14 — The cost of option B, and what compile-time enforcement does and does not add

**Evidence and counts.**
- *Module count.* 21 business domains in the briefing taxonomy → with `-api`/`-impl` pairs 42, plus a
  kernel, a security/cross-cutting module and the application = **~45 backend modules** (6 Gradle
  modules today, `settings.gradle.kts:23-33`). Without pairs ~24. The frontend (684-line build with
  Node lint tasks over `src/main/resources/static` and `templates`, `frontend/build.gradle.kts:475-684`)
  is not a sensible candidate for per-domain Gradle modules at all — its asset linters and Prettier
  scopes are path-based.
- *Per-module registrations outside Gradle* (today's pipeline; §3 inventory):

| Where | What a new module needs | If forgotten |
|---|---|---|
| `settings.gradle.kts` | `include` | not built |
| `docker/app/Dockerfile:13-18` | `COPY <m>/build.gradle.kts` (all three image builds configure every project) | image build fails ("Configuring project … without an existing directory"; vault Testing `:273-277`) — loud |
| `docker/app/Dockerfile:26-27` | `COPY <m>/src/main/` for backend-carried modules (`${MODULE}/src/main` covers only `backend/`) | backend image fails to compile — loud |
| `docker/sandbox/keycloak/Dockerfile:6-11` | `COPY <m>/build.gradle.kts` | sandbox image fails — loud |
| `.github/scripts/image_reuse_plan.py:54-56` | `MODULE_OWN` entry (or a prefix rule) | every domain-module change rebuilds **all three** images — safe but ~3× build jobs |
| `.github/scripts/check_sbom_coverage.py:31-45` | `SHIPPED_INSIDE` with carrier `backend` | repo-lint fails (demands CycloneDX + published BOM) — loud |
| `.github/workflows/sandbox-images.yml:8-14` | path entry | sandbox images not rebuilt on that module's change — **silent** |
| JaCoCo floors (`build.gradle.kts:228-241`) | entry | defaults 0.50/0.40 — **silent** floor drop |
| PIT (`build.gradle.kts:267`, `pitest.yml:23`) | target pattern + matrix | not mutated — **silent** (or loud if empty) |
| Test heap (`build.gradle.kts:183-185`) | entry | 1024 m default |
| Flyway check (`scripts/check-flyway-migrations.sh:5`) | only if migrations are split | numbering not checked — **silent** |

  Plugins applied per module (Checkstyle, SpotBugs+FindSecBugs, Spotless, JaCoCo, mockito agent
  dependency) are automatic only if the module script applies them (MB-11). CodeQL
  (`codeql.yml:73`, `compileJava compileTestJava`), OWASP (`dependencyCheckAggregate`), Javadoc
  position (all non-main Checkstyle tasks, `build.gradle.kts:295-298`) and
  `verification-metadata.xml` need nothing (project modules are not verified; 1,308 components /
  2,331 SHA-256 artifacts today, `50-modules-build-verif.out.txt`).
- *SBOM.* Internal modules appear as components (`pkg:maven/de.greluc.krt.profit.basetool/logging-support@0.0.1-SNAPSHOT?project_path=…`,
  `backend/docs/backend-bom.json:1394-1398`); `verifyCyclonedxBom` already handles project
  components (`build.gradle.kts:361-416`). All carry `0.0.1-SNAPSHOT` (`build.gradle.kts:14-17`).
- *Coverage.* 231 backend `@SpringBootTest` classes exercise code across domains; JaCoCo's
  verification uses the project's own class directories (`build.gradle.kts:225, 244`), so code moved
  into domain modules loses the coverage the application-level tests give it unless
  `jacoco-report-aggregation` or equivalent is added — floors fail or get lowered.
- *Tests and resources.* Each domain module's test task is its own JVM (heap default 1 GiB) and, if
  it boots Spring with the database, its own Testcontainers PostgreSQL (`TC_DAEMON=true` per JVM,
  `backend/src/test/resources/application-test.yml:7`). With `org.gradle.parallel=true`
  (`gradle.properties:2`) several run at once on one CI runner (runner size to verify) — the
  maintainer's workstation notes already record a dozen backend `@SpringBootTest`s failing together
  on a 30 s Hikari timeout under Testcontainers contention in a full run (auto-memory entry
  "Backend-Tests floppen im Vollauf"; anecdotal, not a CI measurement).

**What compile-time enforcement adds over option A.** Internal classes of one domain are absent from
another's compile classpath: no IDE completion, no accidental import, no ArchUnit freeze file to
erode, and module cycles are impossible. The security value is real where it prevents a domain from
reaching into another's repositories and bypassing that domain's scoping (`OwnerScopeService`),
redaction (`MissionPeerRedactor`) and audit (`auditService.record`).

**What it does not add.** (1) **SpEL bean references**: 113 of the backend's method-security
annotations name beans by string — `@ownerScopeService` 43, `@missionSecurityService` 40,
`@authHelperService` 17, `@exchangeGate` 14, `@bankSecurityService` 7,
`@specialCommandSecurityService` 5, `@connectedAppsGate` 1 (`50-modules-build-spel.out.txt`; 432
`@PreAuthorize(` occurrences in total, 145 via `Roles.*` constants). No compiler sees them in any
option; a structural test that resolves each referenced bean is the guard (none exists today,
`grep` §3). (2) **JPA associations**: 115 entities, 187 association annotations; the most common
`@ManyToOne/@OneToOne` targets are `User` 23, `OrgUnit` 14, `Material` 14, `Mission` 10 (§3). An
`-impl` module whose entity references another domain's entity forces that entity into an `-api`
module (visible to every dependent) until the association becomes an id — so B cannot hide what A
also cannot hide, it only fails to compile earlier. (3) **The `MANDATORY` entity-passing hops**
(`…WithinTransaction(entity)`, `backend/CLAUDE.md`) need the managed entity type across the
boundary — the same exposure.

**Proposed change.** Option **A** as the target now (other agents: packages, ArchUnit/Modulith);
option **C** only for a domain whose A boundary has held for some releases and which has **no
entity associations into other domains' `-impl`** and no `MANDATORY` hops across it (leaf domains
such as `audit` read side, `catalogue` imports, `notification` delivery are candidates to measure,
not conclusions). Preconditions for the first C extraction: MB-11 step 2, MB-12, MB-13, prefix
rules in `image_reuse_plan.py`/`check_sbom_coverage.py`/`sandbox-images.yml` (so a new module is one
line plus its build script, not eleven edits), JaCoCo aggregation, and a spike measuring
configuration and test time.

**Pros of B.** Strongest boundary; Gradle compile avoidance and build-cache hits per module.
**Cons of B.** ~45 modules × the table above; coverage/PIT re-plumbing; more JVMs and PostgreSQL
containers in CI; the JPA/`MANDATORY` redesign must be done before, not by, the split.
**Risks (security).** Silent floor drops and silent path-filter misses (table) weaken gates; a
per-module test that cannot boot the whole context may replace a full-chain MockMvc security test
with a slice that skips the real filter chain (`ActingMemberFilter`, access filters) — keep full-chain
security tests in the application module.
**Effort.** B: XL. C per domain: L. **Prerequisites.** ADR (new) choosing A/C; ADR-0047 extended;
MB-10 kernel ADR for C.

---

### MB-15 — Test-context caching and the CI critical path are orthogonal to Gradle modules

**Evidence.** `@SpringBootTest` classes: backend 231, frontend 161, ingest 21; `@ActiveProfiles`
still in 191 backend test files although the test task forces the profile
(`build.gradle.kts:186`); no meta-annotation (`grep '@interface'` empty in backend tests); no
`spring.test.context.cache.maxSize` override (§3). Static proxy of distinct context keys
(`50-modules-build-ctxkeys.py`): backend 49 (40 used by exactly one class; the two largest groups,
94 and 16 classes, differ only by `@ActiveProfiles("test")`), frontend 26, ingest 13. The
September audit measured 38 keys against Spring's default cache of 32 and `:backend:test` at
~8.5 min as the CI critical path (BLD-PERF-03 / CI-14, `sept_audit_findings.json`); BLD-PERF-03 has
no "Done" row in `vault:80 Plans/Improvement Audit 2026-09.md` (only the P2 list, `:275-276`).
CI is one job running everything (`ci.yml:18-63`); no `maxParallelForks` (§3).
**Assessment.** Under B the 231 full-context tests either stay in the application module (critical
path unchanged) or need per-module contexts, which cannot boot while entities reference other
modules' entities (MB-14). Under A, BLD-PERF-03 (one meta-annotation, drop redundant profiles) and,
if adopted, module-scoped test bootstraps (Spring Modulith `@ApplicationModuleTest` — capability and
Boot 4 support to verify by the research agent) shrink contexts inside one JVM.
**Proposed change.** Do BLD-PERF-03 before any structural move; measure with the context-cache
debug log; only then consider `maxParallelForks = 2` (doubles contexts and PostgreSQL containers —
measure).
**Security.** Consolidating `@MockitoBean` sets must not replace a real security bean with a mock in
a shared context (auto-memory entry "@MockitoBean strips the bean's own annotations": a mocked bean
loses its `@PreAuthorize`/`MANDATORY` behaviour and the test stays green) — keep security
beans real in the shared meta-annotation. **Effort.** M. **Prerequisites.** None.

---

### MB-16 — Images, AOT cache, SBOMs and re-tagging under B or C

**Evidence.** One Dockerfile for three images, `MODULE ∈ {backend, frontend, ingest}`
(`docker/app/Dockerfile:20-24`), copying all module build scripts and `${MODULE}/src/main` plus
`logging-support/src/main` (`:13-28`); AOT training per image (`:62-121`, ADR-0209); release matrix
`[backend, frontend, ingest]` (`release-images.yml:451`); per-image reuse derived from the
Dockerfile's `COPY` sources (`image_reuse_plan.py:1-12, 92-109`, ADR-0210 Amendment 1).
**Assessment.** B/C keep **one image per application**: domain modules are jars in `BOOT-INF/lib`;
AOT training and its stubs are unchanged (the backend trains with Flyway off and JDBC metadata off,
`:72-80`). What changes: Dockerfile `COPY` lines (sibling layout: two per module; nested layout
`backend/<domain>/`: `COPY backend/ backend/` relying on `.dockerignore`'s `**/src/test`, but then
`image_reuse_plan.py` treats every `backend/**` change, tests and BOMs included, as an own input —
it errs towards building, which ADR-0210 accepts); `MODULE_OWN`/prefix rules so a domain-module
change does not rebuild frontend and ingest; SBOM components (MB-14).
**Proposed change.** If C happens: sibling directories `backend-<domain>/`, a
`MODULE_PREFIX_OWN = {"backend-": "backend"}` rule with a self-test case in `image_reuse_plan.py`,
and `SHIPPED_INSIDE` generalised to a prefix in `check_sbom_coverage.py`.
**Security.** Reuse must never keep a stale image: a missed own-input mapping only over-builds
(safe); a wrongly broad mapping to one image could under-build — keep the "unknown COPY source ⇒
shared" default. **Effort.** S per rule, M overall. **Prerequisites.** C decided.

---

### MB-17 — JPMS (`module-info.java`) is not worth it here

**Evidence.** No `module-info.java` anywhere (`find`, §3); ADR-0223 already reasons about the
applications as class-path applications, where only the JDK's own modules are named modules
(`docs/adr/0223-only-final-java-features-and-no-preview-flags.md:42-46`). Images run `java … -jar /app/app.jar`
(`docker/app/Dockerfile:125`); tests and `bootRun` pass `--enable-native-access=ALL-UNNAMED`
(`build.gradle.kts:182, 197`) and attach Mockito as `-javaagent` (`:144-151`) — both assume the
unnamed module. Annotation processors (Lombok, MapStruct, `backend/build.gradle.kts:46-54`),
Hibernate proxies over 115 entities, and Jackson 3 records need reflective access. keycloak-spi is
loaded by Keycloak's own class loading, so JPMS is irrelevant there.
**Assessment.** JPMS would add package-level export control **between Gradle modules** (hide
`…internal` packages of a module from other modules at compile time) — i.e. it only adds anything
on top of B. Costs: `opens` for every entity and DTO package to Hibernate, Jackson and Spring;
white-box tests need `--patch-module`; runtime would stay on the class path in a Boot fat jar.
**To verify (research agent):** whether Spring Boot 4 executable jars can run on the module path at
all; whether Spring Framework 7 / Boot 4 jars carry real `module-info` or only
`Automatic-Module-Name`; Hibernate ORM 7's JPMS support; whether JEP 483/514 AOT caches support the
module path; Gradle 9 test execution on the module path with a Java agent.
**Recommendation.** Do not adopt. Use ArchUnit/Modulith (A) and, for C, Gradle
`api`/`implementation` separation. **Security.** Neutral (no change proposed). **Effort.** —.

---

### MB-18 — Option D (separate services): the ingest shows what one process boundary costs

**Evidence.** To front 14 backend operations, ingest carries 75 classes / 12,553 lines; the boundary
needs a service identity with an `azp` allow-list, five trusted headers, an acting-member filter
with four guards and eight counted refusal reasons (`docs/specs/desktop-ingest.md:101-124`),
capability re-checks on both sides (`@exchangeGate` on 14/14 handlers), schema checks in both
directions, a fixed error-code translation with a 502 fallback, three Redis contracts (MB-03), and a
coordinated deploy (Quadlet `Requires=` chains, `07-deployment-view.md:100-103`). Much of that is
internet-facing hardening, but the identity/header/translation half recurs for any internal
service pair. REQ-AUDIT-001 requires the audit event in the same transaction as the mutation — not
possible across services. Production is one host with one maintainer by design
(`07-deployment-view.md:10-12`).
**Pros of D.** Independent deploys and scaling per domain. **Cons.** Everything above per service
pair, distributed transactions for cross-domain writes, several images/units/networks on a
single-host Quadlet deployment.
**Recommendation.** Reject D for domain separation. **Security.** D would multiply the
header-trust surface (REQ-SEC-029) per pair and split the audit trail from its mutation.
**Effort.** —.

---

### MB-19 — Flyway and JPA scanning across modules: keep the schema in the application module

**Evidence.** `@SpringBootApplication` in `…backend` with default entity/repository scanning
(`BackendApplication.java:33-35`, no `@EntityScan`/`@EnableJpaRepositories`, `grep` §3); Flyway
`classpath:db/migration` (`application-prod.yml:15-17`), 256 versioned migrations in one directory,
checked by a single-directory script (`scripts/check-flyway-migrations.sh:5`); `ddl-auto: validate`.
**Assessment.** Entities and repositories in other jars under the same root package are found by
the default scanning (class-path scanning across jars — to verify for Boot 4's
`AutoConfigurationPackages` with JPA). Flyway can read one location from several jars (to verify),
but versions stay one global sequence in one history table, and foreign keys cross every domain.
**Recommendation.** For A and C, keep every migration in `backend` (the application module); express
domain ownership of tables in naming and the migration README, not in jars. A split of migrations
buys nothing and would need the numbering check extended to several directories.
**Pros.** No change to the applied-migration checksums or the history table; one numbering gate.
**Cons.** A C-extracted module does not own its DDL physically. **Risks.** A migration moved
between jars keeps its classpath-relative name, so its checksum should hold — to verify before
anyone tries; the rule avoids the question.
**Security.** Migrations include grants/constraints backing tenancy; one ordered history keeps
them reviewable. **Effort.** — (a rule). **Prerequisites.** None.

---

### MB-20 — Vault drift found while reading (the vault is read-only for this audit)

The code is right; these notes need correcting, dated, by whoever owns the vault commit:

| Note | Says | Code says |
|---|---|---|
| `vault:10 Systems/Testing.md:270-300` | `backend/`, `frontend/`, `ingest/Dockerfile` copy every build script; a `testImplementation` on test-support needs `COPY test-support/src/main/` | one `docker/app/Dockerfile` since 2026-09-23; it runs `bootJar` and copies no test-support sources (`docker/app/Dockerfile:13-30`; ADR-0210 Amendment 1 point 1) |
| `vault:10 Systems/Testing.md:302-310` | `org.gradle.configureondemand=true` and `evaluationDependsOn(":test-support")` are load-bearing | `gradle.properties` has neither (5 lines); no module script calls `evaluationDependsOn` |
| `vault:10 Systems/Testing.md:63` | test files: backend 579, frontend 319, ingest 48, keycloak-spi 7, test-support 1 | backend 674, frontend 368, ingest 78, keycloak-spi 9, test-support 3, logging-support 4 (`find`, §3) |
| `vault:10 Systems/Ingest.md:702-711` | "What else lives in the module: `PiiMasker` … `LogSafe`" | both live in `logging-support` since ADR-0205 (#2016) |
| `vault:10 Systems/Keycloak SPI.md:57-58` | "Five service files … six with the device consent login forms" | six on `main` (`keycloak-spi/src/main/resources/META-INF/services/`) |

---

### MB-21 — Modern Gradle features worth adopting (goal 2, build side)

- **Typed per-module extension** instead of name-keyed maps (MB-11 step 1) — S.
- **`java-test-fixtures`** for module-owned fixtures (MB-10) — S.
- **`jvm-test-suite`** for the frontend's hand-wired `e2e` source set and its two `Test` tasks
  (`frontend/build.gradle.kts:388-466`) — S–M; plugin status in Gradle 9.8 to verify.
- **Configuration cache on locally** (`org.gradle.configuration-cache=true` in `gradle.properties`)
  so a CC regression shows before CI; `refreshVersions -PrefreshVersions` then needs
  `--no-configuration-cache` (it is not CC-compatible, root `CLAUDE.md`) — S.
- **`jacoco-report-aggregation`** only if C/B (MB-14) — M.
- Security impact of all five: neutral to positive (more gates run locally; none changes what ships).

---

## 3. Data appendix

### 3.1 Module inventory

| Module | Main classes | Main lines | Test files | Build script lines | Plugins (module script) | JaCoCo floor instr/branch |
|---|---:|---:|---:|---:|---|---|
| backend | 1389 (briefing) | ~175k (briefing) | 674 | 136 | java, checkstyle, jacoco, idea, boot, dep-mgmt, cyclonedx, licensee, spotbugs-base, pitest, spotless | 0.82 / 0.65 |
| frontend | 554 (briefing) | ~66k (briefing) | 368 | 684 | as backend minus idea, plus node, openapi-generator | 0.60 / 0.46 |
| ingest | 75 | 12,553 | 78 | 78 | as backend minus idea | 0.93 / 0.85 |
| keycloak-spi | 16 | ~2,000 | 9 | 39 | java, checkstyle, jacoco, cyclonedx, licensee, spotbugs-base, spotless; `release 21` | 0.66 / 0.60 |
| logging-support | 4 | 275 | 4 | 25 | java-library, checkstyle, jacoco, dep-mgmt, spotbugs-base, spotless | default 0.50 / 0.40 |
| test-support | 6 | 886 | 3 | 35 | java-library, checkstyle, spotless, dep-mgmt | none |

Commands: `find <m>/src/main/java -name '*.java' | wc -l`; `find <m>/src/test -name '*.java' | wc -l`;
`wc -l */build.gradle.kts`; floors from `build.gradle.kts:228-241`; SPI and library line counts
from `wc -l` of each file.

### 3.2 Ingest packages and dependency cycles

Script `50-modules-build-ingest-pkg.py` (reads `ingest/src/main/java` and `jdeps-ingest.txt`,
drops same-class and inner-class edges, aggregates distinct class pairs per package pair). Output
`50-modules-build-ingest-pkg.out.txt`:

| Package | Classes | Lines |
|---|---:|---:|
| exchange | 26 | 5,416 |
| config | 21 | 2,295 |
| web | 8 | 2,122 |
| filter | 8 | 1,060 |
| service | 3 | 674 |
| metrics | 3 | 527 |
| logging | 2 | 254 |
| ratelimit | 1 | 84 |
| model.dto | 2 | 68 |
| (root) | 1 | 53 |

Two-way package pairs (class pairs each way): config⇄exchange 13/11, config⇄filter 1/8,
config⇄logging 1/4, config⇄metrics 1/1, config⇄web 2/8, exchange⇄filter 2/1, exchange⇄web 4/17,
filter⇄web 4/2, service⇄web 1/3. Largest classes: `ExchangeController` 1,076, `ExchangeRelay` 596,
`ExchangeBudget` 561, `DpopProofReplayStore` 478, `ExchangeIdempotencyFilter` 429.
Per-class summaries: `50-modules-build-classdoc.py ingest` → `50-modules-build-ingest-classes.txt`.
Redis users (`grep -rl 'RedisTemplate|RedisConnectionFactory|RedisScript'`): `ExchangeBudget`,
`ExchangeIdempotency`, `ExchangeQuotas`, `ExchangeRegistryReader`, `ExchangeRevocationReader`,
`HandoffStagingService`. `RestClient` users: `ResponseSizeLimitInterceptor`, `RestClientConfig`,
`ExchangeRelay`, `ServiceAccountTokenProvider`.

### 3.3 Backend exchange layer (MB-01)

Script `50-modules-build-xchroutes.py` → `50-modules-build-xchroutes.out.txt` (14 endpoints).
Backend OpenAPI: `python -c` over `backend/src/main/resources/api/openapi.json` → 440 paths; 13
`/api/v1/exchange/**` paths (14 operations) and 7 `/api/v1/connected-apps/**` paths. Exchange
document: `exchange-v1.openapi.json` 16 paths / 16 operations, OpenAPI 3.1.0, 27,842 bytes; schemas
`ls ingest/src/main/resources/exchange/v1/schemas | wc -l` = 28; fixtures
`find docs/exchange/examples/v1 -name '*.json' | wc -l` = 101.
Relayed codes: `grep -rl "\"<CODE>\"" --include=*.java backend/src/main` per code (MB-01 list).
Files naming `/api/v1/exchange`: `grep -rln '/api/v1/exchange' frontend/src ingest/src backend/src/test docs/specs docs/adr docs/arc42` →
2 ingest main, 7 ingest test, 24 backend test, 3 specs, 1 ADR, 1 arc42 chapter; backend main:
`ActingMemberFilter`, `ExchangeInstallationInterceptor`, 8 controllers; `monitoring/`: none.
Tolerant-reader annotations: `grep -rln JsonIgnoreProperties backend/…/model/dto/exchange` → 3 of
37 files (`ExchangeBlueprintDraftDto`, `ExchangeItemRef`, `ExchangeResolveRequest`).
Backend tests reading schemas/fixtures: `grep -rln 'schema\.json|json-schema|networknt' backend/src/test` → none.

### 3.4 Cross-module path references in CI and scripts

Script `50-modules-build-paths.py` → `50-modules-build-paths.out.txt`: 22 files, 144 lines name a
module directory or `:project`. Workflow path filters naming modules: `sandbox-images.yml` 7
entries (`backend/src/main/**`, `frontend/src/main/**`, `frontend/oss-bundled-components.json`,
`ingest/src/main/**`, `logging-support/src/main/**`, `keycloak-spi/src/main/**`,
`keycloak-spi/lombok.config`), `exchange-docs.yml` 4 per trigger (ingest OpenAPI + schemas,
frontend fonts + logos), `codeql.yml` 1 `paths-ignore` (frontend vendor JS). `ci.yml`,
`release-images.yml` and `e2e.yml` have no path filter (reuse is decided by `image_reuse_plan.py`,
E2E by label). Module lists in scripts/workflows: `image_reuse_plan.py:44` (`MODULES`),
`check_sbom_coverage.py:31-45`, `pitest.yml:23`, `release-images.yml:451`, `promote.yml:67,181`,
`promote-testing.yml:73`, `ci.yml:74-75`, `release-prepare.yml:82-83,110`,
`release-publish.yml:99-119`, `check-keycloak-issuer.py:52-58`, `check-ingest-audience.py:36-37`.
`check-logging-facade.sh:24` uses `git ls-files '*/src/main/java/**/*.java'` (covers new modules).

### 3.5 Build logic

`grep -n '^  plugins.withId\|^subprojects\|^}' build.gradle.kts` → blocks at 123, 195, 210, 264,
288, 301, 337, 419, 442; `subprojects` 122–475. `grep -rn 'maxParallelForks|forkEvery|junit.jupiter.execution.parallel'`
→ none. `grep -rn 'java-test-fixtures|testFixtures' --include=*.kts` → none.
`find . -name module-info.java` → none. Version catalog (`python -c` with `tomllib`): 10 plugins,
42 versions, 42 libraries; the library aliases `checkstyle`, `google-java-format` and
`pitest-junit5-plugin` are never referenced from a build script (the root reads only their
`versions.*`) — presumably kept so the opt-in refreshVersions run can resolve their coordinates
(not asserted). `settings.gradle.kts` (33 lines) declares repositories once with
`FAIL_ON_PROJECT_REPOS` plus the Node.js Ivy repository; `gradle.properties` (5 lines): build cache,
parallel, tooling parallel, daemon, `-Xmx2g`. Gradle wrapper 9.8.0
(`gradle/wrapper/gradle-wrapper.properties`).

### 3.6 Dependency verification

Script `50-modules-build-verif.py` → `50-modules-build-verif.out.txt`: 608,414 bytes, 9,622 lines,
`verify-metadata` true, `verify-signatures` false, two trusted-artifact rules (sources, javadoc),
1,308 components, 2,331 artifacts, 2,331 SHA-256 entries (POM 1,220, JAR 767, Gradle module 342,
gz 1, zip 1), 303 groups, 11 `org.keycloak*` components (ADR-0208 recorded 1,255 components /
2,216 files at introduction).

### 3.7 Spring test contexts

Counts: `grep -rl '@SpringBootTest' <m>/src/test | wc -l` (231/161/21), `@ActiveProfiles` 191/42/0,
`@MockitoBean` files 95/160/21, `@DirtiesContext` 0. Script `50-modules-build-ctxkeys.py` →
`50-modules-build-ctxkeys.out.txt` (static proxy: class-level test annotations + `@MockitoBean`
field types + `@DynamicPropertySource`, one level of inheritance; not Spring's real
`MergedContextConfiguration`): backend 49 keys (40 singletons), frontend 26 (17), ingest 13 (9).

### 3.8 SpEL bean references and JPA associations

Script `50-modules-build-spel.py` → `50-modules-build-spel.out.txt` (string-literal expressions
only; `@PreAuthorize(Roles.…)` forms, 145 in total by `grep -rhoE '@PreAuthorize\(\s*[^"[:space:]][A-Za-z_.]*'`,
are not counted there): backend 287 literal expressions, 113 with a bean reference in 25 files;
frontend 214/0; ingest 18/0. Total `@PreAuthorize(` in backend main: 432. No test resolves the
referenced beans: `grep -rln 'SpelExpressionParser|BeanReference' backend/src/test` finds only
`JacksonRecordTest`, which evaluates DTO properties, not security expressions.
JPA: `grep -rl '^@Entity' backend/…/model | wc -l` = 115; association annotations 187;
`@ManyToOne/@OneToOne` target types (grep of the next four lines): User 23, OrgUnit 14,
Material 14, Mission 10, GameItem 8, JobOrder 7, BankAccount 6, Blueprint 5.

### 3.9 September-audit items in this area (status as read today)

| ID | Topic | Status evidence |
|---|---|---|
| BLD-PERF-03 | test-context consolidation | open — only in the P2 list of `vault:80 Plans/Improvement Audit 2026-09.md:275-276`; no meta-annotation in backend tests |
| BLD-SIMP-06 | move duplicated build lines into root `plugins.withId` | done (#2019); under B/C superseded by convention plugins (MB-11) |
| BLD-PERF-04 / BLD-SIMP-05 | configuration cache, drop `configureondemand` | done (#2019); `ci.yml:63`; `gradle.properties` |
| XMOD-SIMP-01 | one `logging-support` | done (#2016, ADR-0205) |
| ING-SEC-*/ING-SIMP-*/ING-MOD-*, KC-* | ingest and SPI hardening | done (#1990, #2008) per the vault plan's status table |

### 3.10 To verify (for the research agent)

1. Gradle Isolated Projects: rejection of `subprojects {}` / `rootProject.extra` access; status in Gradle 9.8.
2. Whether `gradle/verification-metadata.xml` of the root build covers an included `build-logic`.
3. Version-catalog access from precompiled script plugins in an included build (Gradle 9.8).
4. `gradle/actions/setup-gradle`: whether configuration-cache entries are restored across CI runs
   without `cache-encryption-key`.
5. GitHub-hosted `ubuntu-latest` runner size for public repositories (CPU/RAM), for MB-14/MB-15.
6. Spring Boot 4 and JPMS (MB-17 list).
7. Flyway multi-jar scanning of one `classpath:` location; Boot 4 JPA entity scanning across jars.
8. `java-test-fixtures` never reaching `bootJar`; `jvm-test-suite` status in Gradle 9.8.
9. Spring Modulith `@ApplicationModuleTest` on Spring Boot 4.1 (context size, JPA associations).
10. ArchUnit 1.5.1 default for failing on empty `should` (MB-13).
11. The effective `FAIL_ON_UNKNOWN_PROPERTIES` of Spring Boot 4.1's auto-configured Jackson 3
    `JsonMapper` (the backend does not set it; the exchange's tolerant reader depends on it, MB-01).
