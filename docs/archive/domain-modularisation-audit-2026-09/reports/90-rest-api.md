# 90 — The backend REST API as the contract between the domains and their consumers

Audit agent `90-rest-api`, read-only, 2026-09-29. Repository: worktree
`versekit-client-auth-11774b` at `95e945326` (= `origin/main`). Consumers examined: the web
frontend (same repo), the Android app (local checkout `$ANDROID_REPO`, `main` at
`d86619a1`, 2026-09-25, **v0.3.1 / versionCode 16** — one release behind v0.4.0 / versionCode 17
per the vault, so every app-side statement below is about v0.3.1), the ingest exchange relay, and the
Keycloak SPI. Owner updates of 2026-09-29 applied: the `/api/v1` REST API may be re-cut; the Android
app is adapted with a **hard cut and a forced update through `GET /api/v1/app/version-policy`** (no
parallel paths, no sunset window); the exchange contract (`/exchange/v1`) is frozen.

Counting method: every number comes from a script in the scratchpad (list in appendix A12) run over
the controller sources, the committed `openapi.json`, the edge include, the app sources and the
jdeps graph. "Web consumer" counts are a **lower bound** (static scan resolved 516 of 624
`backendApiClient` call sites; the rest build their path in a variable).

---

## 1 Summary — the ten conclusions that matter most

1. **The API is cut by controller and by audience, not by domain**: 99 controllers serve 572
   documented operations for 22 domains; 11 domains are spread over more than one first path
   segment, `/api/v1/admin/**` hosts five domains and `/api/v1/users/**` carries data of six, and
   29 operations sit in the controller or prefix of a domain they do not belong to (API-01, appendix
   A1/A3).
2. **Authorization for one operation can live in five places** — SecurityConfig URL rules, controller
   `@PreAuthorize`, service `@PreAuthorize` (16 promotion methods), imperative service checks and
   imperative controller checks — and for 15 mappings the URL rule is the *only* role gate, so a
   path move silently widens them to "any authenticated account" (API-02; `SecurityConfig.java:419-428`,
   `90-rest-api-urlrules.py`).
3. **The security gates that would have to catch a bad re-cut are keyed on the `controller` package
   and on controller names**: DSL rules fail loudly only when *no* controller is left in the package
   (ArchUnit 1.5.1 `failOnEmptyShould` defaults to true), while the `permitAll` allow-list loop and
   the staffel write rule go silently partial (API-03; `ArchitectureTest.java:345-382`, `:1036`).
4. **The public api vhost admits by path, not by operation**: 259 documented operations pass the
   allow-list against 234 frozen ones, 14 unintended ones stay reachable (e.g. `GET /me/layout`,
   contrary to REQ-API-012, and `POST /bank/accounts`), and two prefix rules would expose moved
   paths automatically — `/admin/terms → /terms/admin` is admitted by `^/api/v1/terms/` (API-04;
   `api-allowlist.conf:2-3`, `90-rest-api-vhost.py`).
5. **Path strings are a contract with at least 14 independent readers** (edge rules, nightly probe,
   NoStoreApiScopes, rate-limit rules, three security filters, E2E seeder, app repositories, ingest
   relay …); in an illustrative per-domain cut, 12 paths / 15 operations would silently drop from
   `private, no-store` to storable caching unless `NoStoreApiScopes` moves in the same change (API-15,
   API-20; `90-rest-api-recut.py`).
6. **Read and write DTOs are not separated**: 13 DTOs are both request body and response type while
   the ArchUnit guard protects exactly one (`MissionDto`), `RefineryOrderDto` lets a member set
   `status` on create, 13 `@RequestBody` parameters lack `@Valid` and nothing enforces REQ-API-003
   (API-05, API-06; `ArchitectureTest.java:131-132`, `RefineryOrderMapper.java:118-122`).
7. **The error contract is not in the contract**: the documented `ProblemDetail` has no `code`,
   `correlationId` or `fieldErrors` and untyped properties, there is no code registry, and the app
   already shipped a wrong code constant (`TERMS_ACCEPTANCE_REQUIRED` vs `TERMS_NOT_ACCEPTED`)
   (API-07; `OpenApiProblemDetailsConfig.java:91-108`, vault *App Security* 2026-09-27).
8. **The committed OpenAPI document is less trustworthy than the gates around it suggest**: 96 tags
   (55 auto-generated per controller), three colliding schema names — `Op` documents the *stock*
   shape for the blueprint and ship change sets — 304/ETag claimed for 114 no-store reads, and a
   generator test that asserts nothing although REQ-API-007 says it does (API-09…API-11).
9. **The hard cut through the version gate works in one deploy, with three honest gaps**: the floor
   is a host `.env` value that `deploy.sh` renders at every tick, so it can ride the re-cut's restart
   — but the app reads the policy once per process and fails open, so already-running apps and apps
   started during the ~1-minute restart run against the new API without a wall (API-40;
   `UpdateGate.kt`, `deploy.sh:269-276`, `:1400`).
10. **The controllers are part of each domain's implementation, not an adapter over a module API**
    (283 mappings run in controller transactions, 27 controllers call entity mappers, 58 mappings read
    the JWT directly, 23 DTO types are nested in controllers); per-domain module APIs should own
    transactions and mapping, with separate REST DTOs for app-facing operations — the exchange layer
    is the in-repo template (API-14, API-43).

---

## 2 Findings

Part A describes the API as it is (API-01 … API-16). Part B is the concrete per-domain target cut the
owner asked for (API-20 … API-32). Part C covers the hard-cut transition, the contract machinery and
the security invariants (API-40 … API-43). Part D lists drift in specs and vault (API-50) and
re-evaluates the previous audits' API findings (API-51).

### Part A — The API as it is

#### API-01 — The API is organised by controller and audience, not by domain

**Evidence.** 99 `@RestController` classes, all under `backend/.../controller` (8 in
`controller.exchange`); 574 handler mappings, of which 572 are in `openapi.json` (440 paths, 489
schemas); the two undocumented ones are `/error` (`BasetoolErrorController`) and
`POST /internal/discord/account-existence` (`@Hidden`, `DiscordAccountExistenceController.java:53-58`).
Every source mapping matches a documented operation and vice versa (`90-rest-api-controllers.py`,
0 differences). Mapped to the briefing's taxonomy (22 domains, appendix A1/A2):

- **11 domains span more than one first segment**: catalogue (18 segments), orgunit (5:
  `org-units`, `org-hierarchy`, `squadrons`, `special-commands`, `kommando-groups`), identity (4:
  `users`, `me`, `terms`, `admin`), admin-system (4), blueprint (3), exchange (3), bank (2: `bank`,
  `org-units/bank`), mission (2: `missions`, `finance-entries`), materialexchange (2), notification
  (2), personalinventory (2).
- **Audience prefixes cross domains**: `/api/v1/admin/**` (51 operations) holds identity
  (registrations, deletion requests, exports, person search, roles, terms), catalogue (P4K import),
  exchange (registry, bulk undo), blueprints and personal inventory; its protection is one URL rule
  (`SecurityConfig.java:433-434`). The bank alone does it the other way round:
  `/api/v1/bank/admin/**` (`:435-436`).
- **29 operations serve another domain than their controller** (appendix A3; the three
  exchange→blueprint ones are excluded as intended): 8 identity→orgunit (memberships, active org
  unit), 2 identity→bank (`/users/search-bank[/references]`), 2 identity→mission
  (`/users/me/payout-preference`), 2 identity→blueprint (`/users/me/blueprint-sharing`), 1
  identity→dashboard (`/users/me/read-announcement/{id}`), 4 joborder→blueprint, 3
  joborder→inventory, 2 joborder/inventory→catalogue (two game-item catalogues), 1 mission→hangar
  (`/missions/{id}/unit-ship-options`), 2 refinery→mission/catalogue, 1
  materialexchange→inventory.
- **Mixed controllers**: `UserController` (27 mappings, data of 6 domains), `MeController`
  (identity + orgunit + a notification count, `/me/layout` composite), `AdminController` (role
  catalogue + user attributes), `JobOrderController` (34: orders + game-item catalogue + blueprint
  derivation + allocations), `InventoryItemController` (27: stock + item catalogue + mission view),
  `MaterialController` (catalogue + prices + job-order picker).
- **Duplicated capabilities**: `PATCH /bank/accounts/{id}/balance-target`
  (`SetBankBalanceTargetRequest` → `BankAccountDto`) vs `PUT /org-units/bank/accounts/{id}/balance-target`
  (`OrgUnitBalanceTargetRequest` → `OrgUnitBankAccountSettingsDto`); `GET /inventory/item-catalog`
  (`q`, `InventoryGameItemReferenceDto`) vs `GET /orders/item-catalog` (`search`,
  `GameItemReferenceDto`); `PUT /users/{id}/attributes` vs `PUT /admin/users/{id}/attributes`
  (the latter has no caller in frontend main, E2E or the app — grep).

**Impact on domain separation.** A domain module cannot own "its" API without splitting
controllers; the audience-first `/admin` prefix couples five domains to one URL rule and one
frontend proxy family; path prefixes cannot be used to route ownership, caching or rate limits per
domain (they are used that way today, see API-15).

**Proposed change.** The per-domain cut of Part B, preceded by the guards of API-02/03/04/15.
Pros, cons and risks are given per domain there.

**Effort** see Part B. **Prerequisites** owner decision recorded (done 2026-09-29), guards first.

#### API-02 — Authorization for one operation can live in five places

**Evidence.**

| Layer | Where | Size |
| --- | --- | --- |
| 1 URL rules | `SecurityConfig.java:362-440` | 26 `/api` path literals; first match wins |
| 2 Controller `@PreAuthorize` | 99 controllers | 413 annotations (53 class-level, 360 method-level); 214 of 574 mappings rely on the class-level one |
| 3 Service `@PreAuthorize` | `Promotion*Service`, `RankRequirementService`, `MemberEvaluationService` (16), `MissionFinanceEntryService.java:187,218` (2) | e.g. `PromotionCategoryService.java:147/182/216` `ADMIN_OR_OFFICER` behind a controller that only says `isAuthenticated()` (`PromotionCategoryController.java:57`) |
| 4 Imperative service checks | `OrgUnitBankAccessService.requireCan*` (pinned by `ArchitectureTest.java:406-424`), `AccessDeniedException` throws in promotion services | — |
| 5 Imperative controller checks | `RefineryOrderController.java:171-186` (owner override honoured only when `canManageUserRefineryOrders` passes), `:197-220` (logistician reassignment) | — |

196 mappings carry only `isAuthenticated()` at annotation level (OrgUnitBankController 29,
InventoryItemController 17, UserController 12, PersonalBlueprintController 10, …). SpEL beans used
by the annotations: `ownerScopeService` (67 mappings), `missionSecurityService` (38),
`authHelperService` (17), `exchangeGate` (14), `orgRoleManagementSecurityService` (13),
`bankSecurityService` (10), `connectedAppsGate` (7), `specialCommandSecurityService` (5).

**For 15 mappings the URL rule is the only role gate** (`90-rest-api-urlrules.py`): 13 in
`/api/v1/inventory/**` (`GET /inventory/all`, `/all/grouped`, `/aggregated`, `/mission/{id}`,
`/item-catalog`, `POST /inventory`, the four `bulk-*` writes …; rule
`hasAnyRole(ADMIN, OFFICER, LOGISTICIAN, KRT_MEMBER)` at `SecurityConfig.java:427-428`) and 2 in
`/api/v1/hangar/**` (`GET /hangar/squadron-overview`, `POST /hangar/ships/home-location`; rule
`:419-423`); the services behind them check scope, not role (e.g. `InventoryItemService.java:444-453`
checks only "on behalf of someone else"). The role hierarchy (`SecurityConfig.java:207-218`) does not make bank roles or
`MISSION_MANAGER` imply `KRT_MEMBER`, so an account holding only e.g. `BANK_EMPLOYEE` passes those
annotations and is stopped by the URL rule alone. Moving any of them to a path outside the rule
(e.g. `/inventory/item-catalog` → `/game-items`) drops the role requirement without a failing test.

**Impact.** Who may call an operation cannot be read next to the operation; a re-cut can silently
lose layer 1 (keyed on the old path) or layer 5 (logic in a controller body that gets split).

**Proposed change.**
1. **Authorization matrix golden file**: a test walks `RequestMappingHandlerMapping` and the
   security filter chain and writes one sorted line per operation — verb, path, handler, effective
   `@PreAuthorize` (method or class), the URL rule that matches — compared with a committed
   `backend/src/test/resources/api/authorization-matrix.txt` (the `frozen-contract-types.txt`
   pattern). Any re-cut PR then shows every moved mapping with old/new path and unchanged gates.
2. **One placement rule per domain**: the controller keeps a coarse gate that stands on its own
   (no reliance on a URL rule for the role — lift the 15 URL-only gates into their annotations
   first), the domain's module API enforces scope/ownership (so the exchange services and listeners
   cannot bypass it). Move the imperative controller checks of `RefineryOrderController` into the
   refinery facade.
3. Generalise the admin fence to `/api/v1/*/admin/**` → `hasRole(ADMIN)` when admin sub-trees move
   into their domains (API-20 P2).

**Pros.** Re-cuts become reviewable line by line; gates survive a move; non-HTTP callers hit the
same business gates. **Cons.** A golden file churns with every endpoint change (sorted, one line per
mapping, so the diff is exact). **Risks & guards (security).** A facade gate must read the subject
through `AuthenticatedSubject`, never the token type — the acting member of the exchange carries no
token (ADR-0129, `ArchitectureTest.java:233-250`); double gates must not contradict each other
(guard: the matrix shows both). Lifting URL-only gates into annotations is strictly additive.
**Effort** M (matrix S, lifting 15 gates S, moving controller logic M). **Prerequisites** none;
REQ-SEC text naming the placement rule.

#### API-03 — The gates that should catch a bad re-cut are keyed on package and class names

**Evidence.** DSL rules scoped by `resideInAPackage("..backend.controller..")`:
`ArchitectureTest.java:222, 272, 393, 430, 445, 681, 741, 1149, 1256` (security context, no
entities, read/write endpoints need `@PreAuthorize`, no repositories, no audit writes, PII
redaction, response-only DTOs); exchange rules scoped by `..controller.exchange..` (`:479, :509`) and
`..model.dto.exchange..` (`:525-539`). ArchUnit 1.5.1 treats an empty selection as a failure by
default — `AllowEmptyShould$3.isAllowed()` returns true only when `archRule.failOnEmptyShould`
equals `false` (javap of `archunit-1.5.1.jar`), and the repo has no `archunit.properties` — so these
rules fail loudly only when **no** controller remains in the package; a partial move silently drops
the moved controllers from every rule. Worse:

- `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` is a hand loop that skips every class whose
  package does not contain `.backend.controller` and has no floor (`:345-382`); its allow-list names
  FQNs (`:334-342`).
- `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` selects 7 controller **simple names**
  (`:1036-…`); splitting `MissionController` into two classes removes the new one from the rule.
- Only `peerReadableMissionEndpointsMustRedactPii` carries a non-vacuity floor (≥ 10, `:1129-1145`).

**Impact.** This is the most likely way a domain re-cut introduces an authorization or redaction
hole while the build stays green.

**Proposed change.** Before any controller moves: re-key controller rules on the
`@RestController` annotation (or a per-domain `web` package pattern `..backend.*.web..`); replace
name lists by an annotation or by "calls a staffel-scoped service"; give every custom loop a floor
equal to today's selection (e.g. 574 mappings, 4 `permitAll` methods); run old and new selection side
by side once and assert equal counts.

**Pros** re-cut-proof, cheap. **Cons** one more annotation to remember (guarded by a rule).
**Risks & guards.** Re-keying could narrow a rule — guarded by the equal-count assertion.
**Effort** S. **Prerequisites** none; must precede every Part B wave.

#### API-04 — The public api vhost admits by path, not by operation

**Evidence.** `docker/edge/include/api-allowlist.conf`: 172 admission rules (93 exact, 79 regex,
lines 2-173) keyed on `$uri` only; two unanchored prefix rules `^/api/v1/terms/` (line 2) and
`^/api/v1/me/` (line 3); a read-only family (line 177, 16 families) answers 405 to non-GET, reset per
path by lines 179-230; a PUT-only carve-out (207-211). Evaluating every documented operation against
it (`90-rest-api-vhost.py`, same parser semantics as `ExternalContractTest.parseAllowList`):

- **259** operations are admitted by their path; the frozen app set has **234** distinct
  verb+path pairs; 25 admitted-but-not-frozen: 11 are refused by the read-only rule, **14 stay
  reachable** (appendix A5), among them `GET /api/v1/me/layout` (`MeController.java:158`) although
  REQ-API-012 states "Neither endpoint is on the API vhost" (`api-conventions.md:961-963`),
  `DELETE /api/v1/hangar/ships` (all own ships; the exact rule meant for `POST` also resets the
  read-only family, line 179), `POST /api/v1/bank/accounts`, `POST`/`PATCH /api/v1/bank/holders…`,
  `POST /api/v1/job-types` (ADMIN), `PUT /missions/{id}/participants/{pid}/slim` (line 194's optional
  group). Every one of the 14 is gated in the backend (gates listed in A5) — **no bypass found**; the
  loss is defense in depth and the "default-deny, one app phase at a time" property.
- The app (v0.3.1) calls `POST /api/v1/operations` (`OperationRepository.kt:263-276`, `:361`),
  which no rule admits → **404 at the edge**; whether v0.4.0 still sends it is UNKNOWN (settle with a
  device test or `curl -si -X POST https://api.profit-base.online/api/v1/operations` → 404 vs 401).
  `GET /api/v1/materials/matrix` is called by the app (`MaterialCatalogRepository.kt:354`) and
  admitted (line 155) but not frozen.
- Guards today: frozen ⊆ admitted (`ExternalContractTest.java:2665-2689`), exchange ∉ admitted
  (`:2609-2641`), strict parser (`:2695-2713`), the nightly probe (145 probe lines,
  `edge-deny-probe.yml`) checked against the include by `.github/scripts/check_probe_against_allowlist.py`.
  Nothing asserts admitted ⊆ frozen.

**Impact on a re-cut.** Every moved app path must be re-expressed as regexes in two places (rules and
resets); anything moved under `/api/v1/me/` or `/api/v1/terms/` becomes public automatically — the
recut script shows `/admin/terms → /terms/admin` would be admitted by line 2 (appendix A6).

**Proposed change.** Generate the include from the contract set: one nginx `map` on
`"$request_method:$uri"` with an anchored regex per frozen operation (UUID placeholders as today),
no prefix rules, no read-only family needed; generate the probe table from the same source (200 /
401 for admitted, 404 for a sample of non-admitted, 405 where a sibling verb is admitted); a test
asserting admitted == frozen ∪ {the two anonymous reads} in both directions. The generated file stays
committed and reviewed (ADR-0135: spec + test are the source of truth).

**Pros** closes 14 over-admissions and both prefix hazards; a re-cut becomes a generator run.
**Cons** less hand-readable config; ~250 anchored regexes in one `map` (per-request cost UNKNOWN,
measure with `nginx -t` + a load probe). **Risks & guards (security).** A generator bug could admit
wholesale — the bidirectional test, `scripts/check-edge-nginx.sh` (`nginx -t`) and the nightly probe
catch it; the change is strictly narrowing. **Effort** M. **Prerequisites** ADR-0135 amendment
(allow-list generated from the contract set), REQ-SEC-037, fix or freeze the two app calls above.

#### API-05 — Read and write DTOs are not separated

**Evidence.** 188 mappings take a `@RequestBody`; body-type suffixes: `…Request` 100, `…Dto` 51,
`…RequestDto` 12, `…UpdateRequest` 9, `…CreateRequest` 8, `…WriteRequest` 2, other 6. **13 DTOs are
both request body and response type** (appendix A7): Bereich, FrequencyType, JobType, Location,
MaterialCategory, Material, Organisationsleitung, RefineryOrder, RefiningMethod, SpecialCommand,
Squadron, StarSystem, Terminal. The ArchUnit rule `responseOnlyDtosMustNotBeAcceptedAsRequestBody…`
protects exactly one type: `RESPONSE_ONLY_DTOS = Set.of(…MissionDto)` (`ArchitectureTest.java:131-132`).

Example on a member-facing write: `POST /api/v1/refinery-orders` binds the full read DTO
`RefineryOrderDto` (id, owner, profit, status, owningSquadron, version, owningOrgUnitId, …);
`RefineryOrderMapper.toEntity` ignores only `owner`, `owningOrgUnit`, `createdAt`, `updatedAt`
(`RefineryOrderMapper.java:118-122`); `createRefineryOrder` resets id, version, owner and org unit
(`RefineryOrderService.java:279-286`) but not `status`. By code reading a member can create an order
directly in `COMPLETED`, which `storeRefineryOrder` then refuses to store
(`RefineryOrderService.java:562-564`) — own data only, not executed here; confirm with a MockMvc test.
Clients do rely on setting it: the app sends `status = IN_PROGRESS` on create
(`basetool-android …/RefineryRepository.kt:976-990`), so the fix is a restricted field, not a
dropped one (API-30).

Packaging/naming: `model/dto` 342 files (238 `…Dto`, 37 `…Request`, 10 `…UpdateRequest`, 9
`…CreateRequest`, 9 `…RequestDto`, 7 `…WriteRequest`, 15 `…Response`), `model/dto/request` 39,
`model/dto/exchange` 37; **23 DTO types are nested in 9 controllers** (20 records, 3 mutable static
classes — `AnnouncementController.java:106`, `UserController.java:727`, `:742`), including frozen
contract types (`UserController.MyPayoutPreferenceResponse` `:760`, `MeController.CapabilitiesResponse`,
`LayoutResponse` `:227`). ADR-0060's target (`…Request` in, `…Response` out) covers roughly one
body in ten.

**Impact.** Per-domain API definitions need per-domain request/response types; a dual-use DTO turns
every new read field into a writable one.

**Proposed change.** Per domain, when its API is re-cut: `…<domain>.web.dto` with `XxxRequest` /
`XxxResponse` records (ADR-0060), nested controller DTOs moved there, the 3 mutable request classes
turned into records; replace `RESPONSE_ONLY_DTOS` by a structural rule "a type returned by any
mapping is never a `@RequestBody`" (documented exemptions only) and extend
`missionWriteRequestDtosMustNotCarryServerManagedFields` (`:1381-1462`) to every request record
(`id`, `owner*`, `*OrgUnit*`, `status` outside explicit transition endpoints).

**Pros** closes the mass-assignment class structurally. **Cons** ~13 new request records for
catalogue CRUD; renamed schemas rename the app's generated models (the hard cut absorbs it; keep
simple names where possible). **Risks & guards.** A new request record that re-adds a server-managed
field — the generalised ArchUnit rule; MapStruct `unmappedTargetPolicy = ERROR` keeps mappers
explicit. Security: strictly positive. The explicit full-field constructor of `MissionPeerRedactor`
is load-bearing (July audit) and is not touched. **Effort** M (S per domain). **Prerequisites**
ADR-0060 upgraded from "incremental" to "mandatory at re-cut".

#### API-06 — Validation and optimistic-lock gaps on write bodies

**Evidence** (appendix A8). 13 `@RequestBody` parameters without `@Valid`/`@Validated`:
`AdminController.java:90` (`Set<String>`), `:103` (`String`), `DiscordAccountExistenceController.java:75`,
`DiscordRegistrationAdminController.java:119`, `FrequencyTypeController.java:100,118,154`,
`MaterialCategoryController.java:83,100`, `RefiningMethodController.java:97,115`,
`StarSystemController.java:97,113`. 14 body types carry no Jakarta constraint at all (incl.
`LocationDto`, `MaterialDto`, `SquadronDto`, `TerminalDto`, `JoinMissionRequest`, `UpdateCrewRequest`,
`InventoryItemOrgUnitChangeDto`). REQ-API-003 ("`@Valid` on every `@RequestBody`") and REQ-API-002
("write DTOs carry Jakarta validation") are not enforced by any test (no rule references
`jakarta.validation.Valid`). 7 PUT/PATCH bodies have no `*version` component: `GrandAdmiralRequest`,
`MembershipDeltaRequest`, `OperationPayoutStatusUpdateDto` (by design — find-or-create retry,
`backend/CLAUDE.md`), `RefiningMethodDto`, `SetBankApprovalLimitRequest` (approval limits are
last-writer-wins; no documented reason found in `bank.md`), `TerminalDto`,
`UpdatePayoutPreferenceRequest`. Mitigation in place: `NormalizedStringDeserializer` bounds free text
globally; 12 of the 13 unvalidated bodies are ADMIN-only writes (9 catalogue, 2 role catalogue, 1
registration approval), the 13th is the shared-secret SPI endpoint.

**Proposed change.** ArchUnit: every `@RequestBody` on POST/PUT/PATCH is `@Valid`/`@Validated`;
every PUT/PATCH body type has a version component or an entry in a reasons ledger (the
`ADDRESSED_BY_NO_QUERY_PARAMETER` pattern of `ExternalContractTest`). **Pros** makes two binding
requirements true. **Cons** none material. **Risks** adding `@Valid` to a DTO with constraints
changes a 200 into a 400 for bad input — intended; check the web forms send valid data (E2E).
**Effort** S. **Prerequisites** none.

#### API-07 — The error contract is not in the contract

**Evidence.** `OpenApiProblemDetailsConfig.java:55-108` attaches the same six problem responses
(400, 401, 403, 404, 409, 500) to every operation and defines `ProblemDetail` with `type, title,
status, detail, instance, errors`; the committed document renders `detail`, `title` and `errors` as
**untyped** (`{}`), and the schema has **no** `code`, `correlationId` or `fieldErrors` — the fields
REQ-API-004 calls the contract (`api-conventions.md:69-71`). 429 is documented on 1 operation although
both rate limiters answer 429 on all of `/api/**`; 413/415 are undocumented. Codes are string
literals (~50 in `exception/`, plus `RATE_LIMIT_EXCEEDED`, `PENDING_APPROVAL`, `TERMS_NOT_ACCEPTED`,
`NO_ROLE` in filters); no registry, no uniqueness test — unlike the exchange
(`ExchangeContractTest.theErrorRegistryHoldsUniqueCodesWithErrorStatuses`,
`everyCodeTheGatewayAnswersOnItsOwnIsRegistered`). Harm already happened: the app listened for
`TERMS_ACCEPTANCE_REQUIRED`, the server sends `TERMS_NOT_ACCEPTED` (vault `10 Systems/App Security.md`,
"Corrected 2026-09-27").

**Proposed change.** A `ProblemCode` registry: per-domain enums implementing one interface (the
`AppExceptionKind` already carries `code()`), emitted into the document (`ProblemDetail.code` with the
known values as `x-enum`/description, `correlationId`, `fieldErrors[{field,message}]`), a test that
every code produced by `GlobalExceptionHandler`, the filters and `AppException` subtypes is
registered, per-operation `x-problem-codes` optional; the app generates constants from it.
**Pros** ends literal mismatches; codes become reviewable per domain. **Cons** every new code is a
document change. **Risks.** Do not make `code` a *required enum* in frozen responses —
`theContractRequiredEnumsAreFrozen` would then fail on every additive code; keep it a string with a
documented value list. No security impact (codes are already on the wire). **Effort** M.
**Prerequisites** REQ-API-004 amendment.

#### API-08 — Listing, search and pagination conventions diverge from REQ-API-005

**Evidence.** REQ-API-005 says "All list endpoints take Spring's `Pageable` and return a
`PageResponse`" (`api-conventions.md:178-179`). Actual: 1 mapping takes a `Pageable`
(`AdminTermsController.java:76`; it validates the sort by hand and throws `ResponseStatusException`
with an English detail, bypassing the `AppException` code contract), 67 take `page`/`size` request
params (63 also `sort`) via `PaginationUtil`, 75 return `PageResponse*`, 71 GET mappings return an
unpaged `List`/`Set`. The free-text parameter is spelled `q` (11 operations), `query` (7), `search`
(7), `name` (1), `filter` (1). Missions, operations, users and materials expose both `GET /{coll}`
and `GET /{coll}/search` returning the same page type, plus `/lookup` (REQ-API-012 slices).

Resource shape and naming (`90-rest-api-analyse.out.txt`, "path shape"): no upper-case or underscore
segments and no trailing slashes; 52 first segments, mostly plural nouns, with singular or
non-resource roots `/announcement`, `/promotion` (a namespace), `/leitung` (German), `/uex` (an
external source's name), `/settings` beside `/system`; 28 `…/slim` suffixes, all in
`MissionController`, which after the deletion of their full-DTO twins (#1996) no longer distinguish
anything; actions are POST sub-resources (`activate` ×5, `preview` ×5, `deactivate` ×3, `apply` ×3,
`undo`, `reopen`, `confirm`, `reject` ×2 each, `close`, `reversal`, `transfer`, `approve`, `link`,
`join`, `check-in/out`, `store`, `cancel`) — consistent in form — but reorder is `PUT …/reorder` twice
and `POST …/reorder` once, and boolean switches come in three shapes: a PATCH body
(`/squadrons/{id}/promotion-enabled`), a path segment (`/visibility/all-members/{enabled}`) and
paired verbs (`POST`/`DELETE …/interest`).

**Proposed change** (per domain, at its re-cut): one collection GET with typed filters, `q`, page,
size, sort; `/lookup` for pickers; paged for anything that grows, arrays only for closed
vocabularies (write the rule down); amend REQ-API-005 to the `PaginationUtil` reality. **App impact**
frozen query parameters change (`query:string` on missions/search, `search:string` on
materials/search, …) — hard cut. **Security** sort whitelisting stays in `PaginationUtil` (unchanged).
**Effort** S per domain. **Prerequisites** REQ-API-005 amendment.

#### API-09 — The OpenAPI document is monolithic and tagged per controller

**Evidence.** No `GroupedOpenApi`; `OpenApiConfig.java:52` sets `openapi("3.1.1")` while the
committed document says `3.1.0`; `info.version` is a constant `1.0`; `servers` is
`http://localhost` "Generated server url". 96 distinct operation tags: 41 declared by `@Tag` on 42 of
99 controllers, **55 generated from class names** (`org-unit-bank-controller`, `user-controller` …;
springdoc's `autoTagClasses`); tag names contain non-ASCII dashes. `@Operation` on 400 of 574
mappings, `@ApiResponses/@ApiResponse` on 231 — REQ-API-007 (`api-conventions.md:194`) says every
endpoint carries them; nothing enforces it. `OpenApiCachingConfig.java:58-75` adds `304` and an `ETag`
header to all 244 GETs, but **114 of them are `NoStoreApiScopes` families that never emit an ETag**
(ADR-0161 §8.3, REQ-SEC-031). 506 operations document `*/*` as response media type. The document is
2,038,990 bytes.

**Proposed change.** One tag per domain (22, ASCII), `x-domain` on every operation (an
`OperationCustomizer` reading a per-domain package or annotation), `autoTagClasses` off; keep **one**
committed document (the app vendors it, the frontend generates from it) and derive per-domain views
by filtering on the tag at build time (not springdoc groups: groups duplicate shared schemas and can
drop an operation that matches no group — guard: union of views == document); make the caching
customizer read `NoStoreApiScopes`; either enforce `@Operation` or drop the clause from REQ-API-007.
**Pros** per-domain review surface, honest caching docs. **Cons** tag renames change the app's
generated API class names if it generates APIs (it generates models only per its `README.md`).
**Risks** none for security (documentation). **Effort** S-M. **Prerequisites** REQ-API-007 wording.

#### API-10 — Schema names already collide; per-domain packages would multiply it

**Evidence.** springdoc 3.1.1 names schemas by simple class name: `SpringDocConfigProperties.useFqn`
is only assigned in its setter (javap), so it defaults to `false`, and the repo sets no `use-fqn`.
Three schema names are declared by more than one Java type (`90-rest-api-schemanames.py`): `Op`
(`ExchangeBlueprintChangeSet.java:51`, `ExchangeShipChangeSet.java:69`, `ExchangeStockChangeSet.java:57`),
`Provenance` (blueprint change set and `ExchangeBlueprintDto`), `Skipped`. The committed `Op` is the
**stock** shape (`op` pattern `^set-quantity$`, `material`, `location`, `quality`, `stolen` …) and all
three change sets reference it (`ops.items.$ref: #/components/schemas/Op`), so the backend document
describes the bodies of `POST /api/v1/exchange/me/blueprints/changes` and `…/ships/changes` wrongly.
External clients are unaffected (their contract is the hand-written `exchange-v1.openapi.json`,
REQ-XCH-011), and no in-repo generator consumes these schemas.

**Impact.** Options A/B/C put DTOs into per-domain packages, where `SettingsDto`, `SummaryDto` or
nested `Row`/`Item` records in two domains become likely — silently corrupting the document the app
compiles against.

**Proposed change.** A test that every `components.schemas` name maps to exactly one exposed Java type
(or an explicit `@Schema(name = …)`); fix the three with explicit names (`ExchangeBlueprintOp`, …). Do
**not** switch to `use-fqn` (renames all 489 schemas and every generated app model). **Effort** S.
**Security** documentation only.

#### API-11 — The backend generator test asserts nothing; REQ-API-007 says it does

**Evidence.** `backend/.../OpenApiGeneratorTest.java` requests `/v3/api-docs` as ADMIN, expects
`200` and writes the file atomically — no assertion on content. REQ-API-007 (`api-conventions.md:224-226`)
claims the generator "asserts the document's load-bearing parts (title, the `bearer-jwt` scheme, the
expected paths and request/response schemas)"; that was the **ingest** generator, removed with
springdoc in `dc549e0e0` (2026-09-28). Today a controller that silently stops being scanned shows up as
a deletion in the CI stale-diff (`ci.yml:64-69`) and passes once the shrunken file is committed;
`ExternalContractTest.theContractOperationsStillExist` covers only the 234 frozen pairs.

**Proposed change.** Assert the scheme, the two anonymous operations, a per-domain operation-count
floor (from the domain tags) and schema-name uniqueness (API-10) before writing — or correct the
spec. **Effort** S. **Security** positive (a lost controller is also a lost gate surface).

#### API-12 — What pins the contract today, and what does not

**Evidence** (guards in appendix A10).

1. The app contract — `ExternalContractTest` (2,888 lines; 235 `ContractOperation` entries = 234
   verb+path pairs, `DELETE …/units/{missionUnitId}/crew/{crewId}/slim` is listed twice; response
   fields one level deep, required request fields exactly, query
   parameters, required enums, types and nullability against
   `backend/src/test/resources/api/frozen-contract-types.txt` (1,819 lines) and against the previous
   release tag) — is **hand-maintained and has drifted from the app**: `POST /api/v1/operations` and
   `GET /api/v1/materials/matrix` are called by v0.3.1 and not frozen (API-04). The app vendors a
   manual copy of `openapi.json` (400 paths, copied 2026-09-22; now 440) and "Nothing verifies that
   this copy still matches the source" (`basetool-android/core/contract/src/main/openapi/README.md`).
2. The floor is `hasSizeGreaterThanOrEqualTo(5)` for 235 entries (`ExternalContractTest.java:2509-2511`).
3. The release baseline is fetched with `continue-on-error: true` (`ci.yml:34-60`) and the test
   `Assumptions.assumeTrue`s it (`ExternalContractTest.java:2236-2242`): a failed fetch is a green
   check that checked nothing.
4. Web: `DtoOpenApiContractTest` (mirror ⊆ schema), `FrontendDtoContractTest` (enum coverage),
   `GeneratedDtoAgreementTest` (names; 18 aliases, 12 frontend-only types, 2 known drifts);
   generated models exist only in the frontend **test** source set (`frontend/build.gradle.kts:25-58`),
   the code uses 292 hand-written mirrors; `DeprecatedBackendEndpointCallGuardTest` bans calls to
   deprecated operations.
5. Exchange (external): contract-first and the strongest — JSON Schema 2020-12 per resource with
   permanent `$id`, fixtures both ways, `SchemaCompatibility` (203 lines) comparing against the
   previous release, an error-code registry, runtime validation of relayed answers
   (`ExchangeController.java:947-955`).

**Proposed change.** (a) The app publishes, per release, a machine-readable list of (verb, path,
query parameters, response fields read) generated from its `core:data` repositories and generated
models; the backend repo commits it per app release and `ExternalContractTest` asserts the frozen set
⊇ that list. (b) Ratchet the floor to the current count. (c) Make the baseline mandatory on `main`
(fail when `-Dcontract.baseline` is configured but missing). (d) Move `SchemaCompatibility` into a
shared test helper and compare **all** backend schemas with the previous release, classified by
domain tag and contract tier (API-43): web-only breaks reported, app-tier breaks fail unless declared
(API-41). **Pros** the app contract stops being a guess. **Cons** a cross-repo artefact to maintain
(one file per app release). **Risks** a stale list under-freezes — guarded by (a)'s generation in the
app CI. **Effort** M. **Prerequisites** app repo change (REQ-APP-API-005 there).

#### API-13 — Versioning and deprecation, and what Spring Framework 7 would add

**Evidence.** All documented operations but one are `/api/v1`; the single `/api/v2` operation is a
demonstration ping (`SystemController`, v1 twin deprecated with sunset 2026-12-31, no caller in
frontend or app). Deprecated operations: 2 (the ping; `POST /hangar/import/fleetview`, sunset
2027-05-14, replacement `/hangar/import/ships`, still frozen and called by the app).
`DeprecationInterceptor.java:78,96` sends `Deprecation: true` and `Link: <replacement>; rel="alternate"`.
The resolved Spring Framework is 7.0.9 (`gradle/verification-metadata.xml:8177`); its jars contain
first-class API versioning (javap): `ApiVersionConfigurer` (`usePathSegment`, `useRequestHeader`,
`useQueryParam`, `useMediaTypeParameter`, `setDefaultVersion`, `addSupportedVersions`,
`detectSupportedVersions`, `setDeprecationHandler`), a `version()` attribute on `@RequestMapping` /
`@GetMapping`, `ApiVersionInserter` for clients, and `StandardApiVersionDeprecationHandler`, whose
constants emit `Deprecation: @<epoch-seconds>`, `Sunset: <RFC 1123>`,
`Link: <…>; rel="deprecation"; type=…` and `rel="sunset"` (javap `-v`).

**Assessment.** Path versioning is load-bearing here: the edge allow-list, the NoStore/rate-limit
path lists, logs, and ADR-0219's reason for rejecting header versioning. With the owner's hard-cut
policy there are never two versions at once, so version negotiation has nothing to negotiate;
Spring's feature would add standard deprecation headers and a `400` for unsupported versions —
useful only for parallel versions, which were excluded.

**Proposed change.** Do not adopt Spring API versioning now; delete the ping demo; keep
`@ApiDeprecation` for rare cases but, if it is used again, emit the standard header forms through
Spring's handler instead of the custom interceptor. **To verify (research agent):** the RFC 9745
`Deprecation` field syntax (`@epoch` vs `true`), springdoc 3.1.x support for Spring API versions,
`usePathSegment` semantics with `/api/{version}/…` patterns. **Effort** S. **Security** neutral.

#### API-14 — The controllers are part of the domain implementation

**Evidence.** 283 of 574 mappings run inside a controller-level `@Transactional` (175 via class, 82
read-only methods, 26 read-write methods; MissionController 47, UserController 27) because
entity→DTO mapping happens in the controller while OSIV is off (`application.yml:65`,
`open-in-view: false`). jdeps (`jdeps-backend.txt`): 31 controller→mapper edges from 27
controllers, 76 controller→`model` (entity/enum) edges from 41 controllers, 173 controller→service
edges. Two identity seams: 58 mappings in 11 controllers take
`@AuthenticationPrincipal Jwt` and call `UserService.getUserIdFromJwt` (`UserService.java:122`,
fails with `AuthenticationServiceException`), 24 use `@CurrentUserId` (REQ-API-008; fails with
`AccessDeniedException`). 23 DTO types nested in controllers; business rules in
`RefineryOrderController.java:171-220`. The in-repo template of the target shape is the exchange
layer: `exchangeControllersCallExchangeServicesOnly` (`ArchitectureTest.java:505-522`),
`exchangeDtosStayInTheExchangeLayer` (`:524-539`), and ADR-0067 ("service returns DTO") for
memberships.

**Proposed change** (with Options A/B/C of the briefing). Per domain: `…<domain>.api` — the module
API (query/command facades returning records; the transaction boundary lives here);
`…<domain>.web` — controllers, REST DTOs, REST mappers from module-API records, no `@Transactional`,
no repository, no entity. ArchUnit: `web` of domain X depends only on X.api and the shared web kernel
(`web.*`, `PageResponse`, problem types); controllers read identity only through `@CurrentUserId`
(extend `identityMustBeReadThroughTheSeamNotTheAuthenticationType` to `Jwt` parameters). For
app-tier operations the REST DTO is **not** the module-API record (internal refactors stay free, as
ADR-0219 says of the exchange); for web-only operations reuse is acceptable.

**Pros** controllers become thin and movable; module APIs are testable without HTTP. **Cons** a
mapping step per app-tier operation. **Risks & guards.** Moving transactions surfaces
`LazyInitializationException` at runtime, not compile time — guard with
`MeLayoutSingleTransactionTest`-style tests and the E2E suite; peer redaction must stay inside the use
case (`peerReadableMissionEndpointsMustRedactPii`, `MissionPeerRedactor` untouched); audit writes stay
in services (rule exists). **Effort** L-XL (domain by domain). **Prerequisites** the domain agent's
module layout decision.

#### API-15 — Path strings are a cross-module contract with at least 14 readers

**Evidence** (details appendix A9).

| Reader | Keyed on |
| --- | --- |
| Frontend main | 516 of 624 `backendApiClient` call sites resolve to a literal path; 83 classes use `backendApiClient` (family literals: missions 67, users 64, orders 57, bank 48, inventory 37, org-units 36, admin 28 …); 3 classes call the shared `WebClient` directly (`InventoryDeleteAllProxyController`, `HangarDeleteAllProxyController`, `AuditReportProxyController`); browser JS calls a frontend route that mirrors a backend path (`JobOrderHandoverReportProxyController` at `/api/v1/orders`, `orders-detail.js:1151,1211,1322`) |
| Frontend E2E | 39 files (`BackendSeeder` seeds through the backend API) |
| Android | ~35 files in `core:data`/`core:network`/`app` with hard-coded paths; vendored `openapi.json` |
| Ingest | `ExchangeController.java:134` `BACKEND = "/api/v1/exchange"` + 7 relay sites |
| Keycloak SPI | `POST /internal/discord/account-existence` |
| Edge | 172 allow-list rules, read-only family, PUT-only carve-out; 145 nightly probe lines; blackbox `https://api.profit-base.online/api/v1/terms/status` (`prometheus.yml:136,338,384`). The edge rate limit is **not** path-keyed (`krt_req_perip` 20 r/s, burst 80, per client address, `conf.d/00-maps.conf:15`, `include/limits.conf`) and is unaffected by a re-cut |
| Backend security | `SecurityConfig` (26 literals), `ActingMemberFilter` (13 exact exchange paths + prefix, `:100-115`), `TermsAcceptanceAccessFilter` (`:85-88`), `PendingApprovalAccessFilter` (`:79,86`) |
| Backend caching | `NoStoreApiScopes` (14 families, `:42-55`), `StreamAwareShallowEtagHeaderFilter` (2) |
| Backend rate limits | `application.yml:180-227` (`/api/**` + 4 path rules: mission-create, order-create, finance-entry-create, participant-mutations), `SubjectRateLimitingFilter` (2 SSE paths + `EXPORT_SEGMENTS` names) |
| Other backend | `RequestLoggingFilter`, `RequestBodyLimitProperties`, `NotificationStreamObservationPredicate`, `ExchangeInstallationInterceptor` (1 each) |
| Tests | `ExternalContractTest` (235), `frozen-contract-types.txt`, `ApiVhostAnonymousSurfaceTest`, `OpenApiAnonymousOperationsTest`, `ArchitectureTest.PERMIT_ALL_ALLOWED_METHODS` |
| Monitoring | dashboards group by the `uri` label (series renamed → history breaks at a cut), alert text naming `/api/v1/live-sync/stream` (`business.yml:895`) |

**Proposed change.** (1) Frontend: one path class per domain (constants and builders), used by
every proxy, still calling `BackendApiClient` (ADR-0032 and the July audit's "single seam" stay). (2)
Backend: derive NoStore, rate-limit and exemption patterns from per-domain declarations (e.g. a
`@NoStore` / `@RateLimited("finance-entry-create")` on controllers or a per-domain `ApiFamily`
descriptor) and test at runtime over `RequestMappingHandlerMapping` that every operation of a
no-store domain answers `private, no-store` and every rate-limit rule matches ≥ 1 documented operation.
(3) Generate the edge include and probe table (API-04).

**Pros** a re-cut stops being a scavenger hunt; silent downgrades become test failures. **Cons**
annotations are one more place to look (the test makes them self-checking). **Risks (security).** Item
(2) is the key control: today a path move downgrades caching silently (API-20: 15 operations in the
illustrative cut) and drops dedicated rate limits (`finance-entry-create`). **Effort** M.
**Prerequisites** REQ-SEC-031 text: "the list is maintained in code" stays true, but per domain.

#### API-16 — The exchange relay seam: frozen, and not pinned at build time

**Evidence.** Ingest builds relay paths by concatenation (`ExchangeController.java:134, 184, 296,
389, 536, 624, 849, 900`); the backend serves them as 14 operations in 8 exchange controllers, each
gated by `@exchangeGate` (`ArchitectureTest.java:474-503`); `ActingMemberFilter` admits the acting
member only on 13 exact paths (`:100-112`). Ingest validates relayed answers against the published
schema at runtime (502 on violation, `ExchangeController.java:947-955`). No test connects the three
lists, and no backend test validates exchange DTO JSON against
`ingest/src/main/resources/exchange/v1/schemas` (grep). Precedent for a cross-module source check:
`OnBehalfOfHeaderParityTest` (backend) reads ingest sources.

**Proposed change.** Keep `/api/v1/exchange/**` unchanged in every wave (owner: frozen). Add (a) a
parity test: ingest relay paths ⊆ documented backend operations ⊆ `ActingMemberFilter` paths, verbs
included; (b) a backend test serialising a sample of each exchange response DTO and validating it
against the published schema. **Pros** a backend DTO change can no longer become a production 502 or
an unreviewed field sent to third parties. **Cons** (b) may need the JSON-Schema validator as a backend
test dependency → `verification-metadata.xml` regeneration. **Effort** S. **Security** positive.

### Part B — Per-domain target cut (owner update 2026-09-29)

#### API-20 — Principles of the cut, the frozen tier, and the measured blast radius

**Principles.**

- **P1 One resource root per domain** under `/api/v1/`; two roots only for genuinely different
  resources of one domain (orgunit: `/squadrons`, `/special-commands`, `/org-units`).
- **P2 Domain first, audience second**: admin sub-trees at `/api/v1/<root>/admin/**` (the bank does
  this already), one SecurityConfig matcher `/api/v1/*/admin/**` → ADMIN replacing `/api/v1/admin/**`
  and `/api/v1/bank/admin/**` (`*` is one segment in a `PathPattern`; verify with a test). Never under
  `/terms/` or `/me/` while the vhost prefix rules exist (API-04).
- **P3 "Me"-scoped resources live in their owning domain** (`/api/v1/<root>/me/…`); identity keeps
  `/api/v1/me` (capabilities, layout composite) and `/api/v1/users`.
- **P4 A cross-domain read model is a filter on the owning domain's collection**
  (`/inventory/allocations?missionId=`); a sub-resource that owns a link entity stays with the link's
  owner (an order's allocations under `/orders/{id}/allocations`).
- **P5 No shape suffixes in paths** (`/slim`), one collection GET with filters plus `/lookup`, `q` for
  free text (API-08).
- **P6 Tier T0 never moves** (API-40): `GET /api/v1/app/version-policy`,
  `POST /internal/discord/account-existence`, `/api/v1/exchange/**` (owner), and — by
  recommendation, not by rule — the SSE streams `/api/v1/notifications/stream`,
  `/api/v1/live-sync/stream` and `POST /api/v1/live-sync/changed` (read by ≥ 4 path-keyed components,
  no domain value in moving them).
- **P7 Every wave is one app release + one deploy + one floor raise** (API-40), preceded by the Wave-0
  guards: API-02 (matrix, URL-only gates lifted), API-03, API-04, API-10, API-11, API-15, API-41.
- **P8 No generic CRUD base controller** for the catalogue: the July audit rejected templates because
  they hide the `auditService.record` call (vault `10 Systems/Backend.md`, "Load-bearing constructs").

**Measured blast radius** of the illustrative cut below (`90-rest-api-recut.py`, 43 rules, appendix
A6): **161 operations touched, 74 of them frozen for the app.** Security deltas the script found:

- **12 paths / 15 operations would drop from `private, no-store` to `no-cache, must-revalidate`**
  (every `/users/me/*` and `/me/*` move, refinery yields, the inventory item catalogue) unless
  `NoStoreApiScopes` gains the new roots in the same change;
- 22 paths would move up to `no-store` (12 admin identity, 8 admin blueprint / personal inventory,
  2 notification rules) — the safe direction, but a behaviour change;
- 1 rule would newly expose paths on the public vhost: `/admin/terms` → `/terms/admin` via
  `^/api/v1/terms/` — must not ship;
- 1 dedicated rate limit would stop applying: `finance-entry-create` (`POST /api/v1/finance-entries`);
- 2 of the 15 URL-only role gates (API-02) are in the moved set: `/inventory/item-catalog` →
  `/game-items` would lose its only role requirement; `/inventory/mission/{id}` →
  `/inventory/allocations?missionId=` keeps it (still under `/api/v1/inventory/**`).

The per-domain blocks below give the target, the consequences (Android frozen operations; api-vhost
allow-list lines; nightly-probe path mentions per family; E2E references per family; frontend
literal occurrences per family; backend path-keyed readers) and pros/cons/risks. "Probe" and "E2E"
figures are occurrences of the family prefix in `edge-deny-probe.yml` and `frontend/src/e2e`.

#### API-21 — identity: shrink `/users` and `/me` to identity; move foreign data to its domains

| Current | Target | Ops | App-frozen |
| --- | --- | --- | --- |
| `GET/PUT /users/me/payout-preference` | `/missions/me/payout-preference` | 2 | 2 |
| `GET/PUT /users/me/blueprint-sharing` | `/blueprints/me/sharing` | 2 | 2 |
| `PUT /users/me/read-announcement/{id}` | `PUT /announcements/{id}/read` | 1 | 1 |
| `GET /users/search-bank[/references]` | `/bank/members/search[/references]` | 2 | 1 |
| `GET /users/me/memberships`, `/pickable-org-units`, `/org-unit-ids` | `/org-units/me/{memberships,pickable,ids}` | 3 | 1 |
| `GET/PATCH /users/{id}/memberships`, `GET …/detail` | `/org-units/members/{id}/memberships[/detail]` | 3 | 1 |
| `GET /me/active-org-unit`, `GET /me/org-units` | `/org-units/me/active`, `/org-units/me/switchable` | 2 | 2 |
| `/admin/registrations/**`, `/admin/deletion-requests/**`, `/admin/person-search`, `/admin/users/{id}/export[/pdf]` | `/users/admin/…` | 12 | 0 |
| `/admin/roles/**` | `/roles/**` (ADMIN) | 3 | 0 |
| `/admin/terms/**` | stay outside `/terms/` until the vhost prefix rule is gone | 2 | 0 |
| `PUT /admin/users/{id}/attributes` | delete (duplicate, no caller found) | 1 | 0 |
| `/me/capabilities`, `/me/layout`, `/users/me`, `/users/me/registration-status`, `/users/me/rsi-handle`, `/users/me/description`, `/users/me/export`, `/users/me/deletion-request`, `/terms/*` | stay | — | — |

**Consequences.** Android: 10 frozen operations (ExternalContractTest; v0.3.1 files naming them:
`MemberPreferencesRepository`, `MissionRepository`, `AnnouncementRepository`, `BankStaffRepository`,
`IdentityRepository`, `OrgUnitRepository`, `OrgUnitViewModel`, plus the membership pickers in
`InventoryRepository` and `JobOrderRepository`); allow-list lines 3, 6, 95, 110, 120, 121, 123 change (line 3 is the `^/api/v1/me/`
prefix — keep it only for what stays under `/me/`, better replace by exact rules); probe mentions:
users 11, me 2; E2E: users 19; frontend: users 64 literals in 19 classes, me 2, admin 28 in 11
classes; backend: `SecurityConfig.java:378-406` (the users rules) must be re-expressed for
`/bank/members/**` (roles incl. BANK_EMPLOYEE/BANK_MANAGEMENT) and `/org-units/members/**`
(GET roles incl. BANK_EMPLOYEE; PATCH and `/detail` today ADMIN only through the `/api/v1/users/**`
catch-all at `:405-406` — their method gates are `hasRole('ADMIN')`, so nothing widens, but the matrix
must show it); `NoStoreApiScopes` must gain `/missions/me/**`, `/blueprints/me/**`, `/org-units/me/**`,
`/org-units/members/**`, `/announcements/*/read` (10 paths / 13 operations otherwise downgraded);
`PendingApprovalAccessFilter` keeps `/users/me/registration-status` (identity, unchanged).

**Data-model option.** `defaultPayoutPreference` and `shareBlueprintsGlobally` are columns of one
`User` row sharing one `@Version` (`api-conventions.md:309-310`). (a) API-only move: paths follow the
owning module, the columns stay on `User` and the mission/blueprint facades write through identity's
module API — cosmetic separation, cross-domain lock remains. (b) Move the columns into
mission-/blueprint-owned tables with their own versions (Flyway migration, backfill) — real separation
and the finer lock the root CLAUDE.md asks for; audit events for these writes must follow the new
owner (REQ-AUDIT-001, if audited today — to check).

**Pros** identity becomes identity; each domain owns its member settings. **Cons** 10 app operations
in one wave; two storage options with different cost. **Risks (security).** Cache downgrade (above);
URL rules lost for moved `/users/**` paths (guarded by the matrix); the `/terms/` trap. **Effort** M
(a) / L (b). **Prerequisites** Wave-0 guards; REQ-API-009 table rows, REQ-SEC-031 list.

#### API-22 — orgunit: one root for the hierarchy

| Current | Target | Ops | App-frozen |
| --- | --- | --- | --- |
| `/org-hierarchy/org-units[/{id}/parent]` | `/org-units[/{id}/parent]` | 2 | 0 |
| `/org-hierarchy/bereiche/**`, `/org-hierarchy/organisationsleitung/**` | `/org-units/bereiche/**`, `/org-units/organisationsleitung/**` | 10 | 0 |
| `/org-units/bank/**` | → bank (API-23) | 29 | 24 |
| memberships and org-unit context from `/users/**`, `/me/**` | → `/org-units/me/**`, `/org-units/members/**` (API-21) | 8 | 4 |
| `PUT/DELETE /kommando-groups/{groupId}` (vs `GET/POST /squadrons/{id}/kommando-groups`) | optional: `/squadrons/{id}/kommando-groups/{groupId}` — changes the gate from `#groupId` to path-scoped | 2 | 0 |
| `/leitung/view` | `/org-chart/leadership` (orgchart; English) | 1 | 0 |

**Consequences.** App: none for the hierarchy fold; E2E: org-hierarchy 7, squadrons 6,
special-commands 8; frontend: org-hierarchy 10 literals / 2 classes, leitung 1. No NoStore change
(`/org-units/**` is not no-store; the moved hierarchy reads were not either). **Pros** tenancy
administration in one family. **Cons** the kommando-group variant needs the squadron id on the
client. **Risks.** `OrgRoleManagementSecurityService` gates keyed on ids stay; verify with the matrix.
**Effort** S. **Prerequisites** none beyond Wave 0.

#### API-23 — bank: one root, two audiences, one semantics

| Current | Target | Ops | App-frozen |
| --- | --- | --- | --- |
| `/org-units/bank/**` (member / org-unit view) | `/bank/org-units/**` | 29 | 24 |
| `PATCH /bank/accounts/{id}/balance-target` and `PUT /org-units/bank/accounts/{id}/balance-target` | one verb (PUT) and one request shape; two audiences stay two paths | 2 | 1 |
| `/users/search-bank[/references]` | `/bank/members/search[/references]` (API-21) | 2 | 1 |

**Consequences.** Android: 24 frozen operations (`BankRepository`, `BankStaffRepository`); allow-list
lines 16-19, 48-50, 89-94, 115 (14 lines); probe mentions: bank 32, org-units 13; E2E: bank 43,
org-units 16; frontend: bank 48 literals / 10 classes, org-units 36 / 6; backend: `NoStoreApiScopes`
already has `/api/v1/bank/**`, so the move keeps `no-store` (the `/org-units/bank/**` entry can go);
`orgUnitAwareBankSeamIsContainedToOneClass` and `bankClassesMustNotConsultOrgUnitScope` (ADR-0020 /
ADR-0028) are package/class keyed — the controller move must not touch the seam class. **Pros** one
family for the edge, cache, tags and monitoring of the ledger. **Cons** the largest single app change
(24 operations). **Risks (security).** The two audiences carry different redaction
(REQ-BANK-054 `staffNote` hidden from members) — keep separate controllers and DTOs; only the prefix
moves. **Effort** M. **Prerequisites** ADR-0020/0028 unaffected (verify), Wave 0.

#### API-24 — mission: nest the finance ledger, drop `/slim`, fold `/search`, delete legacy writes

| Current | Target | Ops | App-frozen |
| --- | --- | --- | --- |
| `POST /finance-entries`, `PUT/DELETE /finance-entries/{entryId}` | `/missions/{missionId}/finance-entries[/{entryId}]` | 3 | 3 |
| 28 × `…/slim` | same path without `/slim` | 28 | 24 |
| `GET /missions/search` | `GET /missions` with filters | 1 | 1 |
| `POST /missions/{id}/participants/add` (MissionDto twin of `/participants/slim`) | delete | 1 | 0 |
| `PUT /missions/{id}` (legacy full replace, `MissionController.java:314`) | delete — no caller found in frontend, E2E or app; it is the one write that force-increments the row `@Version` (`backend/CLAUDE.md`) | 1 | 0 |
| `GET /inventory/mission/{missionId}`, `GET /refinery-orders/mission/{missionId}` | `GET /inventory/allocations?missionId=`, `GET /refinery-orders?missionId=` (owning domains) | 2 | 0 |
| `GET /missions/{id}/unit-ship-options` | stays (mission use case), served through the hangar module API | — | — |

**Consequences.** Android: 28 frozen operations (`MissionRepository`, `MissionTimelineRepository`);
allow-list lines 7, 11-14, 116, 143-153; probe mentions: missions 30, finance-entries 1; E2E:
missions 13; frontend: missions 67 literals / 7 classes, finance-entries 6; backend:
`application.yml` rule `finance-entry-create` (`POST /api/v1/finance-entries`) must be re-keyed or the
tighter budget silently stops; `participant-mutations` still matches without `/slim`;
`NoStoreApiScopes` already covers `/missions/*/finance-entries/**`; the finance gate
(`@missionSecurityService.canCreateFinanceEntry`) can take `#missionId` from the path instead of the
body — a stronger declarative gate. **Pros** the ledger is visibly a mission sub-resource; ~28 path
suffixes and two dead writes disappear; the coarse-lock write path goes. **Cons** the biggest app
change after the bank. **Risks.** `peerReadableMissionEndpointsMustRedactPii` (floor ≥ 10) must still
select the renamed methods; `/missions` GET with and without filters must keep scope rules
(REQ-MISSION-008/012). **Effort** M. **Prerequisites** Wave 0; REQ-MISSION-020 / ADR-0170 amendment
(`by-id/slim` path).

#### API-25 — joborder: allocations named as such; catalogues out

| Current | Target | Ops | App-frozen |
| --- | --- | --- | --- |
| `GET /orders/item-catalog`, `GET /inventory/item-catalog` | `GET /game-items` (catalogue; one DTO, `q`) | 2 | 1 |
| `GET /orders/item-catalog/{gameItemId}/blueprints` | `GET /game-items/{gameItemId}/blueprints` | 1 | 1 |
| `GET /orders/item-catalog/blueprints/{id}/derivation` | `GET /blueprints/{id}/derivation` | 1 | 0 |
| `GET /orders/{id}/inventory/orphaned`, `DELETE /orders/{jobOrderId}/inventory/{inventoryItemId}/unlink` | `/orders/{id}/allocations/orphaned`, `DELETE /orders/{id}/allocations/{inventoryItemId}` | 2 | 1 |
| claims, material collection, item stock, handovers, production | stay under `/orders/{id}/…` | — | — |

**Consequences.** Android: 3 frozen (`JobOrderRepository`, `JobOrderWorkRepository`); allow-list
lines 41, 42, 118; probe: orders 21 (lines 110-111 name the item catalogue); E2E: orders 42;
frontend: orders 57 literals / 10 classes. **Security.** `GET /inventory/item-catalog` is one of the
15 URL-only gates (API-02): moving it to `/game-items` drops the `KRT_MEMBER`-or-above requirement
unless the annotation is lifted first; it also leaves the no-store family. **Pros** the order screen
talks to the catalogue for catalogue data. **Cons** two DTOs merge into one (field union to decide).
**Effort** S. **Prerequisites** API-02 step "lift URL-only gates".

#### API-26 — catalogue: one game-item catalogue, yields under locations; optional common root

**Target.** `GET /game-items` (API-25); `GET /refinery-orders/locations/{id}/yields` →
`GET /locations/{id}/refinery-yields` (leaves no-store — catalogue data, acceptable, decide
explicitly); `/admin/import/p4k/**` → `/catalog/admin/import/p4k/**`; `/sync-reports` →
`/catalog/admin/sync-reports`. **Option:** one root `/api/v1/catalog/**` for the 18 catalogue
families — one read-only edge family, one cache policy, one tag; costs 14 frozen app operations
(`/materials/search`, `/locations/search`, `/ship-types`, `/job-types`, `/terminals`,
`/refining-methods`, …) and ~100 frontend literals for naming only — recommend **not** now.
**Pros/cons** as stated. **Risks.** No generic CRUD template (P8). **Effort** S (without the
option). **Prerequisites** none.

#### API-27 — blueprint and personal inventory: admin sub-trees into their domains

**Target.** `/admin/personal-blueprints/**` → `/personal-blueprints/admin/**` (8 ops),
`/admin/default-blueprints/**` → `/blueprints/admin/defaults/**` (3), `/admin/personal-inventory/**`
→ `/personal-inventory/admin/**` (4); `/users/me/blueprint-sharing` → `/blueprints/me/sharing`
(API-21). `/personal-blueprints` and `/blueprints` stay two roots (member-owned vs catalogue).
**Consequences.** App: 0 frozen for the admin moves; frontend: admin 28 literals; **caching moves up**
to `no-store` for 8 admin paths (personal inventory 2, personal blueprints 6; `/blueprints/**` is not
a no-store family, so the default-blueprint admin stays revalidate) — the safe direction; the new admin sub-trees need the
`/api/v1/*/admin/**` URL rule (P2) — their class-level `hasRole('ADMIN')` stays (all three controllers
carry it, `90-rest-api-analyse.out.txt`). **Security.** Do not place admin sub-trees under a vhost prefix rule; after API-04
there is none. **Effort** S.

#### API-28 — materialexchange: one root

**Target.** `/material-requests/**` → `/material-exchange/requests/**` (9 ops, 7 frozen);
`/material-exchange/offers/**` stays; `POST /material-exchange/items/{inventoryItemId}/deactivate` →
`POST /material-exchange/offers/deactivate?inventoryItemId=` (optional). **Consequences.** Android 7
frozen (`MaterialBoardRepository`); allow-list lines 64-65, 162, 164; probe: material-requests 2,
material-exchange 3; frontend: 9 + 12 literals. Caching unchanged (both roots are deliberately
revalidate, REQ-SEC-031). **Pros** one family and one tag for the board. **Cons** app change for
naming. **Effort** S.

#### API-29 — hangar: admin sub-tree and the deprecated import

**Target.** `/hangar/users/{userId}/ships/**` → `/hangar/admin/users/{userId}/ships/**` (4 ops, all
`hasRole('ADMIN')`); delete `POST /hangar/import/fleetview` (deprecated, sunset 2027-05-14; the app
calls it — frozen — so the new app moves to `/hangar/import/ships`). **Consequences.** Allow-list line
170 goes; probe line 247 (fleetview) and 249 (`/hangar/users/…` 404) change; `SecurityConfig.java:417-418`
goes. **Security.** `/hangar/**` URL rule still covers the admin sub-tree. **Effort** S.

#### API-30 — refinery: remove the second way to act for another member

**Target.** Keep `/refinery-orders/users/{userId}/**` (the per-target gate reads `#userId` from the
path — the stronger form) and **remove the owner override from the body** of
`POST /refinery-orders` and the logistician reassignment in `PUT /refinery-orders/{id}`
(`RefineryOrderController.java:171-220`), so one declarative path exists per case; replace the full
`RefineryOrderDto` body by a request record without `owner`, `profit`, `owningSquadron`, `id`, and
with a **restricted** initial status instead of a free `status`: the app itself sends
`status = IN_PROGRESS` on create and echoes the current status on edit
(`basetool-android …/RefineryRepository.kt:976-990`), so the field cannot simply be dropped — accept
`OPEN | IN_PROGRESS` on create and no status on edit (status moves only through `/store`).
**Consequences.** App: `POST /refinery-orders` frozen request fields stay (`goods`, `location`); the
app sends no `owner` (same file); its `status` echo on edit must stop — part of the wave's app
release. **Security** strictly narrowing. **Effort** S.

#### API-31 — notification, dashboard, settings, system: small naming fixes

**Target.** `/notification-rules/**` → `/notifications/admin/rules/**` (5 ops, web only; moves up to
no-store); `/announcement` → `/announcements/current` (4 ops, 1 frozen with its `204` semantics —
REQ-API-009 note) — optional, naming only; domain-typed settings: `GET /orders/settings`
(`ageYellowDays`, `ageRedDays` — replaces the app's frozen `GET /settings/{key}` read of the two keys
admitted by allow-list lines 172-173), `/refinery-orders/settings` (`roundingMode`), the fee rate already has
`/bank/transfer-fee-rate`; the generic `/settings/{key}` stays as the ADMIN store behind them; delete
`/api/v1/system/ping` and `/api/v2/system/ping`. **Pros** typed, validated, per-domain settings.
**Cons** two frozen app reads change. **Effort** S.

#### API-32 — What does not move

`GET /api/v1/app/version-policy` (T0, API-40); `POST /internal/discord/account-existence` (the
Keycloak SPI calls it during first-broker login and **fails open** — a 404 would silently disable the
duplicate-account check, `DiscordAccountExistenceController.java:44-52`); `/api/v1/exchange/**` (owner;
plus `ActingMemberFilter`); the SSE streams and `/live-sync/changed` (P6); `/api/v1/terms/*` (the
anonymous `terms/document` is one of the four `permitAll` names); `/api/v1/audit/**` (cross-cutting
viewer); `/api/v1/connected-apps/**` (web-only member control, `theExchangeStaysOffTheApiVhost`
probes it).

### Part C — Hard cut, contract machinery and security

#### API-40 — The forced-update path: what is frozen, how the floor is set, and the release sequence

**Tier T0 — frozen for as long as any installed build may need it** (everything an old app needs
before it can show „Update erforderlich"):

| Element | Evidence |
| --- | --- |
| `GET /api/v1/app/version-policy`, `200`, JSON body `minimumVersionCode` (int), `latestVersionCode` (int), `releasesUrl` (https string) | `AppVersionPolicyController.java:44-75`, `AndroidClientProperties`; app `AppVersionRepository.kt:117` (`PATH`), `:89` (null floor = no floor), `:97` (non-https URL → fallback `:125`), `:39` (`allows`) |
| No authentication, no required header, not refused by the pending/terms gates | `@PreAuthorize("permitAll()")` + empty `@SecurityRequirements`; `SecurityConfig.java:376-377`; `PendingApprovalAccessFilter.java:86`; `TermsAcceptanceAccessFilter.java:88`; `ExternalContractTest.theContractRequiresNoHeader` |
| The `/api/v1` prefix for this one path, even if everything else moves | app constant, `AppVersionRepository.kt:117` |
| Edge: host `api.profit-base.online`, exact admission rule | `api-allowlist.conf:53`; probes `edge-deny-probe.yml:90,92` |
| ArchUnit `permitAll` allow-list entry | `ArchitectureTest.java:336-337` |
| Guards | `AppVersionPolicyControllerTest`, `ApiVhostAnonymousSurfaceTest`, `OpenApiAnonymousOperationsTest`, `ExternalContractTest` entry |

Nothing else server-side is needed before the wall: `UpdateGate` is the outermost gate, ahead of the
app lock and the session ("Outermost on purpose", `app/…/gate/UpdateGate.kt:133`), so login, terms and
capabilities are not prerequisites. While the verdict is pending the app composes its content
("Unknown runs the app"), so its first calls race the wall — harmless for showing it.

**How the floor is configured.** `app.android.minimum-version-code` ← `APP_ANDROID_MINIMUM_VERSION_CODE`
(default 0), same for `…LATEST…` and `…RELEASES_URL` (`application.yml:172-175`); passed by
`docker-compose.yml:213-215` and `quadlet/env.d/backend.env.tmpl:11-13`; the live value exists only in
the host `.env` ("Neither is in the promoted config bundle", vault *Android App* :86-90). It is a
`@ConfigurationProperties` record read at startup, so a change needs a **backend restart**, which
through `Requires=` also restarts frontend and ingest — "about one minute of web and app outage"
(`docs/EXCHANGE_GO_LIVE_RUNBOOK.md:535-561`, step S8). Production today: 16/16 on 2026-09-25 (vault),
17 planned by S8 (current value UNKNOWN to this audit; read-only settle: `curl -s
https://api.profit-base.online/api/v1/app/version-policy`).

**What the app does** (v0.3.1): reads the policy **once per process** (`started` flag,
`UpdateGate.kt:100`, triggered by `LaunchedEffect(Unit)` `:156`), **fails open** on any failure
(`:118`; KDoc: "a failed read leaves the state `Unknown`, and `Unknown` runs the app"), treats floor 0
as "allow everything", and on `Blocked` shows a non-dismissible wall with a CTA to `releasesUrl` and an
exit; cached data survives (UpdateGate KDoc).

**Can the floor ride the re-cut deploy?** Yes. `deploy.sh` renders `env.d` from the host `.env` at
every tick (`scripts/deploy.sh:269-276`), so a floor set in `.env` before the promotion is applied by
the same backend recreate that brings the re-cut. Caveats: (1) the `.env` edit is a production write —
per-action owner approval (root CLAUDE.md); (2) the health-gate rollback restores the previous release
but **not** the `.env` value — after a failed deploy the old API would run with the new floor (old apps
walled, new app broken), so the rollback runbook must revert the floor too; (3) any unrelated backend
restart between the edit and the tick applies the floor early — edit immediately before promoting, in
one supervised window; (4) option: a repo-committed default floor per release (reviewable, rolls back
with the release) with the host `.env` as an override for emergencies.

**Release sequence per wave and what each app experiences.**

| Step | Server | Old app (≤ N) | New app (N+1) |
| --- | --- | --- | --- |
| 0 Publish app N+1 (GitHub release) | old API, floor N | works | early installers call new paths → 404 (edge/backend) until step 4 |
| 1 Owner-approved `.env`: floor/latest = N+1 (not applied yet) | unchanged | works | as step 0 |
| 2 Promotion tick: env.d rendered, backend + frontend + ingest recreated (~1 min) | edge answers 503 maintenance (`maintenance.conf`) | cold start now: policy read fails → **fail open** → runs against the new API until its next cold start | outage |
| 3 Stack healthy; edge not yet reconciled (`reconcile_edge` runs after the stack and monitoring, `deploy.sh:1400`) | new API, **old** allow-list, floor N+1 | cold start: wall; running: retired paths still admitted by the edge, backend answers 404 | new paths refused 404 at the edge |
| 4 Edge recreated with the new include (brief outage of all vhosts) | new API + new include | cold start: wall; running: 404 at the edge | works |
| 5 Steady state | | wall at every cold start until updated | works |

Publishing *after* the deploy is worse: old apps would be walled with nothing to install. Raising the
floor *after* the deploy is worse too: old apps run broken without a wall, and a second outage minute
is spent.

**Options to close the gaps (not decided here).** (a) App: re-read the policy on foreground resume and
after an unexpected 404/`NOT_FOUND` on a known path — must ship in a release *before* the first cut to
help it; (b) edge: serve a static policy file rendered from the same env value when the backend
answers 502/503/504, so the wall works during the restart (static JSON, no user data; the edge renders
it at `reconcile_edge`); (c) server: answer retired paths with a stable problem
`APP_UPDATE_REQUIRED` that future app versions map to the wall (helps from the second cut on); (d)
operations: cut at low usage and announce. **Security of the options.** (b) adds a second producer
of an anonymous response — it must carry no more than the three values and stay exact-path; (c)
creates no new reachable path (it answers only paths that were admitted before).

**Effort** S (runbook + checks) plus the chosen options. **Prerequisites** API-41.

#### API-41 — The contract rules a hard cut trips, and a declared-break ledger

**Evidence.** REQ-API-001: "Breaking changes go to a new version (`/api/v2/...`)" and retired endpoints
carry `@ApiDeprecation(sunset…)` (`api-conventions.md:14-19`); REQ-API-009: "retirement goes through
`/api/v2` + `@ApiDeprecation` with a sunset rather than a deletion" (`:695-699`); ADR-0136, Decision
bullet 4 says the same; `ExternalContractTest.theContractTypesMatchThePreviousRelease` fails a release
that drops a frozen operation by design; the root CLAUDE.md requires the requirement to be amended
**first** when a change must override it.

**Proposed change.** Record the owner's decision as an ADR ("hard cut with forced update; no parallel
paths") superseding the retirement clause of ADR-0136, amend REQ-API-001/009/010 accordingly, and add
a **declared-break ledger** (`backend/src/test/resources/api/declared-breaks.txt`: one line per removed
or changed frozen operation/field, with the app `versionCode` that absorbs it). The previous-release
comparison accepts exactly the declared breaks; anything else still fails. The app-side list (API-12
(a)) of the new app version must show that it no longer calls anything declared broken.
**Pros** a hard cut is an explicit, reviewed list instead of a disabled test. **Cons** one more file.
**Risks** a ledger entry that is too broad hides an accidental break — entries name operation + field,
no wildcards. **Effort** S. **Prerequisites** owner approval of the amendments.

#### API-42 — Security invariants for every re-cut wave

| Invariant | How it can break in a re-cut | Guard (existing → added) |
| --- | --- | --- |
| Every moved mapping keeps its gate | package/name-keyed rules go partial (API-03); URL-only gates lost (API-02, 15 mappings) | ArchUnit read/write rules → re-keyed on `@RestController` with floors; authorization matrix golden file |
| No new anonymous path | a moved `permitAll` method leaves the loop's package; SecurityConfig `permitAll` lines are path-keyed (`:364-377`) | `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints` (re-keyed + floor 4), `OpenApiAnonymousOperationsTest`, `ApiVhostAnonymousSurfaceTest`, probe 200-rows |
| No path newly reachable on the api vhost | prefix rules `^/api/v1/terms/`, `^/api/v1/me/`; path-only admission (API-04) | frozen ⊆ admitted → generated verb-aware include + admitted == frozen test |
| Rate limits still apply | path rules in `application.yml:180-227`, SSE constants and `EXPORT_SEGMENTS` names in `SubjectRateLimitingFilter` | test: every rule matches ≥ 1 documented operation; per-domain declaration (API-15) |
| Sensitive reads stay `no-store` | `NoStoreApiScopes` path list (15 operations downgraded in the illustrative cut) | runtime test per no-store domain (API-15) |
| Pending/terms/acting-member exemptions exact | exact paths in three filters | keep T0 paths; parity test for `ActingMemberFilter` (API-16) |
| CSRF exemption (ADR-0144) | keyed on `/api/**` | keep the `/api` root (all targets stay under it) |
| Redaction | renamed mission methods leave `peerReadableMissionEndpointsMustRedactPii`'s selection | its floor (≥ 10) + re-keyed selection |
| Admin fence | `/api/v1/admin/**` URL rule no longer covers moved admin trees | `/api/v1/*/admin/**` matcher + class-level `hasRole('ADMIN')` kept + matrix |
| Exchange unchanged | relay paths are strings | parity test (API-16), `theExchangeStaysOffTheApiVhost` |

**Effort** covered by the referenced findings. **Prerequisites** all before Wave 1.

#### API-43 — Per-domain API definitions: groups/tags, code- vs contract-first, stability tiers, breaking-change gate, module API relation

- **Grouping.** One domain tag per operation plus `x-domain`; one committed full document; per-domain
  views derived for review (API-09). Rejected: springdoc groups as the source of truth (duplicate
  schemas across group documents, operations matched by no group disappear).
- **Code-first stays for the backend.** The web and app API is defined by controllers + Bean
  Validation (~870 constraints per ADR-0161); a contract-first rewrite of 572 operations would
  duplicate them in JSON Schema and invite drift. Contract-first stays where a third party consumes —
  the exchange (ADR-0216/0219), already in place.
- **Stability tiers** marked per operation (e.g. `@ApiContract(Tier.APP)`), emitted as
  `x-contract-tier`: **T0** never breaks (version gate, SPI endpoint, exchange relay by owner
  decision); **T1** app contract — breaks only in a declared hard-cut wave (API-41) with a new app
  version and a floor raise; **T2** web-only — free to change with the atomic deploy, guarded by the
  frontend contract tests. Tests: T1 set == `ExternalContractTest` set == generated edge include
  (plus the two anonymous reads); T0 compared against a never-changing record.
- **Machine-checked breaking-change gate.** Generalise the ingest `SchemaCompatibility` helper to the
  backend document; compare against the previous release on every PR (baseline mandatory on `main`);
  T0 breaks fail always, T1 breaks fail unless in the ledger, T2 breaks are reported. No new external
  tool is needed (oasdiff would add an unverified binary outside Gradle's dependency verification).
- **Module API vs REST API.** Per domain, the REST layer maps from module-API records (API-14); the
  module API is the authorization and transaction boundary; REST DTOs of T1 operations are separate
  types so internal refactors stay free; ArchUnit keeps each domain's `web` package talking only to its
  own `api` package.

**Pros** each domain has a documented surface, a declared stability and an automatic break check.
**Cons** an annotation per operation (572) — generated once from the current consumer map (the 234
app-frozen pairs become T1 except the version policy, which is T0; the 14 exchange relay operations
and the undocumented SPI endpoint are T0; the rest T2). **Risks (security).** The tier annotation must not become an implicit
exposure switch: the edge include is generated from it but still committed and reviewed (ADR-0136:
opening a family to the app and freezing it are one decision). **Effort** M. **Prerequisites** API-12,
API-41.

### Part D — Drift found in specs and vault (the auditor cannot write; for the coordinator)

#### API-50 — Statements that disagree with the code (code is right)

| Where | Statement | Code / config |
| --- | --- | --- |
| `api-conventions.md:224-226` REQ-API-007 | generator asserts title, scheme, paths, schemas | backend `OpenApiGeneratorTest` asserts only `200` (API-11) |
| `api-conventions.md:194` REQ-API-007 | every endpoint carries `@Operation`, `@ApiResponses` | 400 / 231 of 574 (API-09) |
| `api-conventions.md:65` REQ-API-003 | `@Valid` on every `@RequestBody` | 13 without (API-06) |
| `api-conventions.md:178` REQ-API-005 | all lists take `Pageable`, return `PageResponse` | 1 `Pageable`, 71 unpaged lists (API-08) |
| `api-conventions.md:121` REQ-API-004 | "Document the format in OpenAPI" | no `code`/`correlationId`/`fieldErrors` (API-07) |
| `api-conventions.md:961-963` REQ-API-012 | `/me/layout` not on the API vhost | admitted by `^/api/v1/me/` (API-04) |
| `security-and-access.md:1830` REQ-SEC-031 | a member record is "the only PII the API serves" | `/api/v1/admin/users/{userId}/export[/pdf]`, `/admin/person-search`, `/admin/registrations` serve PII and are not in `NoStoreApiScopes` (revalidate bucket); not on the public vhost, so the exposure is internal — for the security agent |
| vault `10 Systems/API Conventions.md` | 412 paths / 543 operations / 417 schemas; 227 contract operations; 1,775 frozen lines; property `apiVhostRunbook`; "`check` fails when the committed file does not match"; "Eleven requirements" | 440 / 572 / 489; 235; 1,819; `apiVhostAllowList` (`backend/build.gradle.kts:118-121`); CI step `ci.yml:64-69`, not `check`; REQ-API-012 exists |
| vault `00 Maps/Basetool.md:31` | Ingest = "Two forward-only endpoints the SC Extractor posts to" | removed 2026-09-28 (the correction box below the table says so; the table row is stale) |

#### API-51 — Previous audits' API findings under the new conditions

| Finding | Status (vault `80 Plans/Improvement Audit 2026-09.md`, `10 Systems/Backend.md`) | Re-evaluation 2026-09-29 |
| --- | --- | --- |
| July 2026 (PR #1256): `BackendApiClient` stays the single frontend seam | kept | still valid; per-domain path classes sit on top of it (API-15), the Resilience4j pass is untouched |
| July: no generic CRUD/sync base template | kept | still valid (P8) — the catalogue cut must not introduce one |
| July: `MissionPeerRedactor` explicit full-field constructor | kept | still valid; API-05/API-14 leave it alone |
| July: "architecture sound, debt is size inside correct layers" | — | for the API layer the domain goal changes the verdict: layer-correct controllers are domain-mixed (API-01) |
| BE-SIMP-02/03 (deprecated mission endpoints, owner-change lock) | done (#1994, #1996) | method superseded by the owner's hard-cut policy (no sunsets any more, API-41) |
| BLD-CI-09 (stale `openapi.json`) | done (stale-diff step) | partly met: the generator still asserts nothing (API-11) and the release baseline can skip (API-12) |
| BE-PERF-06 / FE-PERF-01 (REQ-API-012 slim reads, `/me/layout`) | done (#2004, #2020) | "not on the API vhost" is false for `/me/layout` (API-04) |
| BE-SIMP-01 (`Entities.require`), BE-MOD-05b (MapStruct strict) | done (#2011, #2015) | valid; unaffected by a re-cut |
| ING-SEC-05 (ingest endpoint-surface test) | listed P1 | partly moot: the legacy `/v1` endpoints are gone (2026-09-28) and `ExchangeRoutesContractTest` pins the exchange route table |

---

## 3 Data appendix

### A1 Domains (controller-based assignment; "web" is a lower bound)

| Domain | Controllers | Ops | Writes | App-frozen | Web ≥ | Relay | Only `isAuthenticated()` | Controller tx | No `@Operation` | First segments | Tags |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| catalogue | 19 | 90 | 45 | 14 | 38 | 0 | 31 | 79 | 76 | 18 (materials, locations, terminals, …, admin, uex, sync-reports) | 19 |
| bank | 9 | 65 | 40 | 49 | 15 | 0 | 29 | 28 | 0 | bank, org-units | 9 |
| identity | 14 | 59 | 24 | 19 | 40 | 0 | 23 | 33 | 37 | admin, me, terms, users | 12 |
| mission | 2 | 53 | 43 | 38 | 47 | 0 | 3 | 47 | 6 | finance-entries, missions | 2 |
| orgunit | 8 | 40 | 30 | 1 | 35 | 0 | 5 | 28 | 7 | kommando-groups, org-hierarchy, org-units, special-commands, squadrons | 8 |
| joborder | 4 | 39 | 23 | 26 | 30 | 0 | 8 | 12 | 0 | orders | 4 |
| exchange | 11 | 35 | 21 | 0 | 17 | 14 | 0 | 0 | 0 | admin, connected-apps, exchange | 11 |
| promotion | 6 | 34 | 14 | 2 | 22 | 0 | 32 | 1 | 0 | promotion | 6 |
| inventory | 1 | 27 | 15 | 21 | 15 | 0 | 17 | 12 | 14 | inventory | 1 |
| blueprint | 6 | 25 | 16 | 12 | 16 | 0 | 11 | 1 | 0 | admin, blueprints, personal-blueprints | 6 |
| materialexchange | 2 | 21 | 13 | 16 | 17 | 0 | 0 | 0 | 0 | material-exchange, material-requests | 2 |
| hangar | 1 | 15 | 11 | 7 | 5 | 0 | 6 | 11 | 13 | hangar | 1 |
| refinery | 2 | 14 | 8 | 5 | 8 | 0 | 6 | 14 | 13 | refinery-orders | 2 |
| notification | 2 | 13 | 7 | 7 | 12 | 0 | 8 | 0 | 0 | notification-rules, notifications | 2 |
| operation | 1 | 12 | 4 | 7 | 9 | 0 | 3 | 12 | 0 | operations | 1 |
| personalinventory | 2 | 9 | 6 | 5 | 4 | 0 | 5 | 0 | 0 | admin, personal-inventory | 2 |
| admin-system | 4 | 7 | 2 | 2 | 0 | 0 | 4 | 0 | 4 | app, settings, system, v2/system | 3 |
| orgchart | 1 | 5 | 4 | 0 | 5 | 0 | 1 | 0 | 0 | org-chart | 1 |
| dashboard | 1 | 4 | 2 | 1 | 4 | 0 | 1 | 4 | 4 | announcement | 1 |
| audit | 1 | 4 | 1 | 0 | 0 | 0 | 0 | 1 | 0 | audit | 1 |
| livesync | 1 | 2 | 1 | 2 | 0 | 0 | 2 | 0 | 0 | live-sync | 1 |
| leadership | 1 | 1 | 0 | 0 | 1 | 0 | 1 | 0 | 0 | leitung | 1 |

"Ops" counts source mappings (574 incl. `/error` and `/internal`).

### A2 Controller → domain (manual map in `90-rest-api-domains.py`)

mission: MissionController, MissionFinanceEntryController · operation: OperationController ·
joborder: JobOrderController, JobOrderItemStockController, MaterialClaimController,
MaterialCollectionController · inventory: InventoryItemController · personalinventory:
PersonalInventoryController, AdminPersonalInventoryController · blueprint: PersonalBlueprint*,
Blueprint*, AdminPersonalBlueprintController, AdminDefaultBlueprintController · hangar ·
materialexchange: MaterialExchangeController, MaterialRequestController · refinery:
RefineryOrderController, RefineryImportController · bank: Bank*Controller (8), OrgUnitBankController ·
notification: NotificationController, NotificationRuleController · catalogue: Material*,
ProfitCalculation, Location, UexLocation, City, Outpost, Poi, SpaceStation, StarSystem, Terminal,
ShipType, Manufacturer, RefiningMethod, JobType, FrequencyType, AdminP4kImport, SyncReport · audit:
AuditAdminController · promotion: Promotion* (4), RankRequirement, MemberEvaluation · orgchart ·
leadership: Leitung · orgunit: OrgUnit, OrgHierarchy, Squadron*, SpecialCommand*, KommandoGroup ·
identity: User, Me, MyRegistrationStatus, DiscordRegistrationAdmin, Admin, AdminPersonSearch,
DataExport, AdminDataExport, DeletionRequest, AdminDeletionRequest, Terms, TermsDocument, AdminTerms,
DiscordAccountExistence · dashboard: Announcement · admin-system: SystemSetting, System,
AppVersionPolicy, BasetoolError · livesync: LiveSync · exchange: Exchange* (8),
AdminExchangeRegistry, AdminExchangeBulkUndo, ConnectedApps.

### A3 Operations whose path serves another domain than their controller (29)

inventory→mission `GET /inventory/mission/{missionId}`; inventory→catalogue
`GET /inventory/item-catalog`; joborder→catalogue `GET /orders/item-catalog`; joborder→blueprint
`GET /orders/item-catalog/{gameItemId}/blueprints`, `GET /orders/item-catalog/blueprints/{id}/derivation`,
`GET /orders/{id}/item-blueprint-owners`, `PATCH /orders/{id}/blueprint-variant-counting`;
joborder→inventory `GET /orders/{id}/materials/{matId}/inventory`, `GET /orders/{id}/inventory/orphaned`,
`DELETE /orders/{jobOrderId}/inventory/{inventoryItemId}/unlink`; materialexchange→inventory
`POST /material-exchange/items/{inventoryItemId}/deactivate`; identity→orgunit `GET /me/active-org-unit`,
`GET /me/org-units`, `GET /users/{id}/memberships`, `GET /users/me/pickable-org-units`,
`GET /users/me/memberships`, `GET /users/me/org-unit-ids`, `PATCH /users/{id}/memberships`,
`GET /users/{id}/memberships/detail`; mission→hangar `GET /missions/{id}/unit-ship-options`;
refinery→catalogue `GET /refinery-orders/locations/{locationId}/yields`; refinery→mission
`GET /refinery-orders/mission/{missionId}`; identity→bank `GET /users/search-bank`,
`GET /users/search-bank/references`; identity→mission `GET/PUT /users/me/payout-preference`;
identity→blueprint `GET/PUT /users/me/blueprint-sharing`; identity→dashboard
`PUT /users/me/read-announcement/{announcementId}`. (Not counted: 3 exchange→blueprint relay
operations, intended.)

### A4 Authorization building blocks (count of mappings using each)

`isAuthenticated` 248 · `hasRole` 243 · `@missionSecurityService.canManageMission` 28 · `hasAnyRole`
18 · `@authHelperService.isMemberOrAbove` 17 · `@ownerScopeService.canEditJobOrder` 12 ·
`…canSeeJobOrder` 12 · `@exchangeGate.allows` 12 · `…canEditInventoryItem` 9 ·
`@connectedAppsGate.isMemberSession` 7 · `…canSeeMission` 7 · `@bankSecurityService.canSee` 5 ·
`…canAccessParticipant` 5 · `…targetsAnotherUser` 5 · `…canEditRefineryOrder` 5 ·
`@specialCommandSecurityService.canManageMembers` 5 · `permitAll` 4 · others ≤ 3. Class-level-only
gates: `isAuthenticated()` 108, `hasRole('ADMIN')` 69, `hasRole('KRT_MEMBER')` 21,
`@connectedAppsGate…` 7, `hasRole('BANK_MANAGEMENT')` 4, `hasRole('BANK_EMPLOYEE')` 3,
`@ownerScopeService.canAccessBlueprintOverview()` 2. URL-only role gates: see API-02.

### A5 The 25 operations admitted by the api vhost but not frozen

Reachable (14) with their backend gate: `DELETE /hangar/ships` `hasAuthority('HANGAR_WRITE')`
(HangarController:200) · `DELETE /personal-blueprints` `isAuthenticated()` (own rows,
PersonalBlueprintController:205) · `DELETE /refinery-orders/{id}` `…canEditRefineryOrder(#id)` ·
`GET /hangar/ships` `hasAuthority('HANGAR_READ')` · `GET /material-requests/{id}` `hasRole('KRT_MEMBER')` ·
`GET /materials/matrix` `isAuthenticated()` (called by the app) · `GET /me/layout` `isAuthenticated()` ·
`PATCH /bank/holders/{id}` `hasRole('BANK_MANAGEMENT')` · `POST /bank/accounts` `hasRole('BANK_EMPLOYEE')` ·
`POST /bank/holders` `hasRole('BANK_MANAGEMENT')` · `POST /job-types` `hasRole('ADMIN')` ·
`POST /orders` `isAuthenticated()` · `PUT /missions/{id}/participants/{participantId}/slim`
`isMemberOrAbove() and canAccessParticipant(…)` · `PUT /refinery-orders/{id}` `…canEditRefineryOrder(#id)`.
Refused 405 by the read-only family (11): `DELETE /announcement`, `DELETE /materials/{id}`,
`DELETE /missions/{id}`, `DELETE /operations/{id}`, `DELETE /orders/{id}`,
`PATCH /users/{id}/memberships`, `POST /refining-methods`, `PUT /announcement`, `PUT /materials/{id}`,
`PUT /missions/{id}`, `PUT /settings/{key}`. Frozen but not admitted: none.

### A6 Illustrative re-cut: blast radius per rule (`90-rest-api-recut.out.txt`)

| Rule | Ops | App-frozen | Allow-list lines | Security delta |
| --- | ---: | ---: | --- | --- |
| identity→mission payout-preference | 2 | 2 | 120 | no-store → revalidate |
| identity→blueprint sharing | 2 | 2 | 121 | no-store → revalidate |
| identity→dashboard read-announcement | 1 | 1 | 123 | no-store → revalidate |
| identity→bank search-bank | 2 | 1 | 110 | stays no-store (bank) |
| identity→orgunit (me/memberships, pickable, ids, users/{id}/memberships[/detail], me/active, me/org-units) | 8 | 4 | 3, 6, 95 | 7 paths no-store → revalidate; line 3 is a prefix rule |
| identity admin → /users/admin, /roles | 15 | 0 | — | 12 paths revalidate → no-store |
| identity admin terms → /terms/admin | 2 | 0 | — | **newly admitted on the public vhost** (line 2) |
| delete duplicate admin attributes | 1 | 0 | — | — |
| bank /org-units/bank → /bank/org-units | 29 | 24 | 16-19, 48-50, 89-94, 115 | stays no-store |
| orgunit hierarchy fold | 12 | 0 | — | — |
| mission finance nesting | 3 | 3 | 13, 14 | rate rule `finance-entry-create` lost unless re-keyed |
| mission `/slim` removal | 28 | 24 | 11, 12, 116, 143-153 | `participant-mutations` still matches |
| mission search fold / delete legacy add | 2 | 1 | 7 | — |
| inventory/refinery mission views → filters | 2 | 0 | — | — |
| refinery yields → locations | 1 | 0 | — | no-store → revalidate |
| item catalogues → /game-items (+ blueprints, derivation) | 4 | 2 | 41, 42 | inventory item catalogue no-store → revalidate; URL-only gate lost (API-02) |
| joborder allocations naming | 2 | 1 | 118 | — |
| material-requests → material-exchange/requests | 9 | 7 | 64, 65, 162, 164 | — |
| hangar admin sub-tree / delete fleetview | 5 | 1 | 170 | — |
| notification rules → notifications/admin/rules | 5 | 0 | — | revalidate → no-store |
| announcement → announcements/current | 4 | 1 | 32 | — |
| leitung → org-chart/leadership | 1 | 0 | — | — |
| delete ping v1/v2 | 2 | 0 | — | — |
| P4K / personal-inventory / blueprint admin sub-trees | 19 | 0 | — | 8 paths revalidate → no-store |
| **Total** | **161** | **74** | | |

### A7 Dual-use DTOs (request body and response type)

Bereich (`POST /org-hierarchy/bereiche`), FrequencyType (`POST /frequency-types`), JobType
(`POST /job-types`), Location (`POST /locations`), MaterialCategory (`POST /material-categories`),
Material (`PUT /materials/{id}`), Organisationsleitung (`POST /org-hierarchy/organisationsleitung`),
RefineryOrder (`POST /refinery-orders`), RefiningMethod (`POST /refining-methods`), SpecialCommand
(`POST /special-commands`), Squadron (`POST /squadrons`), StarSystem (`POST /star-systems`), Terminal
(`PUT /terminals/{id}`) — each also returned by the family's GET.

### A8 Validation gaps

Without `@Valid` (13): listed in API-06. Without any Jakarta constraint (14): ApproveRegistrationRequest,
CreateDeletionRequestRequest, DiscordAccountExistenceRequest, FrequencyTypeDto,
InventoryItemOrgUnitChangeDto, JoinMissionRequest, LocationDto, MaterialCategoryDto, MaterialDto,
RefiningMethodDto, SquadronDto, StarSystemDto, TerminalDto, UpdateCrewRequest. PUT/PATCH without a
version component (7): GrandAdmiralRequest (`PUT …/organisationsleitung/{id}/grand-admiral`),
MembershipDeltaRequest (`PATCH /users/{id}/memberships`), OperationPayoutStatusUpdateDto (by design),
RefiningMethodDto, SetBankApprovalLimitRequest (three approval-limit PUTs), TerminalDto,
UpdatePayoutPreferenceRequest (`PUT …/participants/{pid}/payout-preference/slim`).

### A9 Frontend and app reach per family

Frontend main literal occurrences (classes): missions 67 (7), users 64 (19), orders 57 (10), bank 48
(10), inventory 37 (6), org-units 36 (6), admin 28 (11), promotion 23 (2), special-commands 22 (5),
operations 19 (3), squadrons 17 (5), materials 17 (6), settings 16 (4), refinery-orders 16 (5),
locations 16 (3), hangar 15 (4), personal-blueprints 14 (3), material-exchange 12 (2), org-hierarchy
10 (2), material-requests 9 (1). E2E: bank 43, orders 42, refinery-orders 27, inventory 26, users 19,
org-units 16, missions 13. Nightly probe mentions: bank 32, missions 30, orders 21, inventory 19,
org-units 13, users 11, personal-blueprints 7, materials 7, hangar 6, operations 5. Allow-list rules:
orders 22, missions 21, bank 18, org-units 15, inventory 13, users 10, personal-blueprints 9,
notifications 7, materials 7. Android files naming the family (v0.3.1): users 8, orders 6, materials 4,
bank 3, org-units 3, me 3, locations 3, missions 2, inventory 2, operations 2, notifications 2,
terms 2, announcement 2, material-exchange 2, others 1.

### A10 Contract guards inventory

| Guard | Pins | Blind spot |
| --- | --- | --- |
| CI stale-diff `ci.yml:64-69` | committed document == generated | a committed shrink passes |
| `OpenApiGeneratorTest` (backend) | writes the document | asserts nothing (API-11) |
| `OpenApiDerivedPropertyTest` | `@AssertTrue` accessors hidden | — |
| `OpenApiAnonymousOperationsTest` | exactly the two anonymous operations | — |
| `ExternalContractTest` | 234 app operations: fields, required fields, query params, required enums, types/nullability (record + previous release), vhost reachability, exchange off vhost, no required header | hand-maintained; floor 5; baseline may skip |
| `ApiVhostAnonymousSurfaceTest` | anonymous status per admitted path | — |
| `DtoOpenApiContractTest`, `FrontendDtoContractTest`, `GeneratedDtoAgreementTest` | web mirrors vs document | names/enums only; 2 known drifts |
| `DeprecatedBackendEndpointCallGuardTest` | web calls no deprecated operation | literal paths only |
| `check_probe_against_allowlist.py`, `edge-deny-probe.yml` | probe table vs include; live edge nightly | path-level only |
| ingest `ExchangeContractTest`, `ExchangeRoutesContractTest`, `ExchangeSchemasTest`, `SchemaCompatibility` | exchange v1 schemas, routes, error registry, additive-only | backend DTOs not validated at build time (API-16) |
| `OnBehalfOfHeaderParityTest` | ingest/backend header name | — |

### A11 Verified library facts (local jars, no web)

- Spring Framework 7.0.9 (`verification-metadata.xml:8177`): `ApiVersionConfigurer` methods,
  `@RequestMapping.version()`, `StandardApiVersionDeprecationHandler` header constants — `javap` on
  `~/.gradle/caches/…/spring-webmvc-7.0.9.jar`, `spring-web-7.0.9.jar`.
- ArchUnit 1.5.1: `archRule.failOnEmptyShould` default true — `javap -c` of
  `com.tngtech.archunit.lang.AllowEmptyShould$3` (located by `90-rest-api-jarscan.py`).
- springdoc 3.1.1: `SpringDocConfigProperties.useFqn` assigned only in its setter (default false),
  `isAutoTagClasses` present — `javap -c -p` on `springdoc-openapi-starter-common-3.1.1.jar`.

### A12 Scripts and outputs (scratchpad, prefix `90-rest-api-`)

| Script | Output | Purpose |
| --- | --- | --- |
| `oas-summary.py` | `operations.csv` | per-operation table of the committed document; tags, codes, media types |
| `controllers.py` | `mappings.csv`, `classes.json` | source inventory of all 574 mappings (verb, path, gates, body, `@Valid`, return, identity params, tx, `@Operation`), joined with the document |
| `analyse.py` | `analyse.out.txt` | aggregates: gates, bodies, responses, identity, tx, tags, deprecation, path shape |
| `dto.py` | stdout | dual-use DTOs, constraint-less bodies, version-less writes, suffix census |
| `consumers.py` | `consumers.csv`, `consumers.out.txt` | web (lower bound) / app (frozen set) / exchange consumers per operation |
| `domains.py` | `domain-table.json` | domain table, cross-domain operations |
| `vhost.py` | stdout | allow-list evaluation of every operation |
| `android.py` | stdout | app path literals vs document and frozen set |
| `recut.py` | `recut.out.txt`, `recut.json` | blast radius of the illustrative cut |
| `urlrules.py` | stdout | mappings whose only role gate is a URL rule |
| `schemanames.py` | stdout | schema-name collisions |
| `jarscan.py` | stdout | ArchUnit property location |
| `sept.py` | `sept.out.txt` | September audit findings touching the API (BE-SIMP-01/02/03, BLD-CI-09, BE-PERF-06, FE-PERF-01, BE-MOD-05b — all implemented per the vault; this audit found BLD-CI-09's spirit only partly met, API-11) |

Run with `PYTHONIOENCODING=utf-8 python 90-rest-api-<name>.py` from the scratchpad; greps and
`javap` calls are quoted in the findings.
