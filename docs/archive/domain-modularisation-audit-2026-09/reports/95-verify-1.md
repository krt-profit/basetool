# 95-verify-1 — adversarial verification of 10 backend-security claims

Repository: worktree `versekit-client-auth-11774b` at `95e945326` (= origin/main). Read-only; no Gradle,
no git writes. All paths are relative to `backend/src/main/java/de/greluc/krt/profit/basetool/backend/`
unless they start with a top-level directory. Library facts were read from the sources jars in the
Gradle cache, at the versions `gradle/verification-metadata.xml` pins: spring-security 7.1.1 (:9139),
spring-expression/context/web 7.0.9 (:8013, :8046), hibernate-core 7.4.5.Final, nimbus-jose-jwt
10.9.1 (:1688).

## Verdicts

| # | Claim | Verdict | One-line reason |
|---|---|---|---|
| 1 | API-02: 15 URL-rule-only gates | **NARROWED** | The list (13 + 2), the `isAuthenticated()`-only annotations and the hierarchy are all correct. But the precondition, a bank-only or MM-only account, cannot exist by realm design (REQ-SEC-053). Informational. |
| 2 | API-05: create refinery order in COMPLETED | **NARROWED** (as a vulnerability: refuted) | The mechanics are true, but the UI's own create and edit forms offer COMPLETED and CANCELED. PUT already lets the owner set any status. COMPLETED unlocks nothing and only blocks `store`. |
| 3 | XC-02/RES-08: missing SpEL bean → 400 | **CONFIRMED** (+ nuance) | Exact chain verified in 7.1.1 source. 6/8 beans are default-named. One warning alert (`BackendCallFailureSustained`) can still see a web-side burst. |
| 4 | XC-22: CSRF exemption untested | **CONFIRMED**, 2 corrections | The E2E backend runs `dev`, so CSRF is on there. A live instance already exists: the documented `POST /actuator/loggers` bearer write. |
| 5 | PSA-02: no Keycloak timeouts | **CONFIRMED** (stronger) | The override also discards Spring Security's own 30 s/30 s JWKS defaults. Prod has used this path since 2026-09-25. |
| 6 | S783-PAGECAP: size ≤ 100 000 | **CONFIRMED** | 30 of 66 paginated handlers are allow-listed on `api.*`, plus web pass-throughs. Rate limits and timeouts bound the load. Deliberate design. Low. |
| 7 | S1672-LIKE: unescaped LIKE | **NARROWED** | :219 is confirmed. :125-126 is refuted: its caller escapes. Two unlisted unescaped sites exist (:171/179, :197/203). Informational. |
| 8 | XC-12: 18 proxy-dependent service gates | **CONFIRMED**; self-invocation today: **none** | 18 exact. Zero self-calls. For finance-entry PUT/DELETE the service gate is the only gate. |
| 9 | JAVA-04: `default -> HANGAR_WRITE` | **CONFIRMED** (not exploitable), 1 correction | `read()` (:258-270) does not validate `resource`. Only the controller `@Valid` does. Even without it, capability and execution stay consistent. |
| 10 | API-06: 13 bodies without `@Valid` | **CONFIRMED** count and gates; impact **NARROWED** | None of the body types has a single constraint, so `@Valid` alone would validate nothing. |

---

## 1 — API-02 · NARROWED · severity: informational (defence-in-depth)

**Confirmed facts**
- Inventory URL rule `hasAnyRole(ADMIN, OFFICER, LOGISTICIAN, KRT_MEMBER)` is at `config/SecurityConfig.java:427-428`. The hangar rule is at `:419-423`, and it is a *permission* rule: `hasAnyAuthority(HANGAR_READ, HANGAR_WRITE, ROLE_ADMIN)`.
- Hierarchy (`:207-218`): ADMIN/OFFICER → LOGISTICIAN, MISSION_MANAGER; ADMIN → BANK_MANAGEMENT → BANK_EMPLOYEE. Nothing implies KRT_MEMBER, not even ADMIN or OFFICER.
- The 13 inventory handlers carry only the class-level `@PreAuthorize("isAuthenticated()")` (`controller/InventoryItemController.java:95`), some with a redundant method-level `isAuthenticated()`:
  - GET `/aggregated` :144, `/material/{id}` :178, `/game-item/{id}` :202, `/all` :396, `/mission/{id}` :443, `/all/grouped` :465, `/all/stack/entries` :543, `/item-catalog` :591
  - POST `/` :698
  - `/bulk-checkout` :793, `/bulk-org-unit` :858, `/bulk-stolen` :923, `/bulk-rebook` :953
  - The count is 13, as claimed.
- The 2 hangar handlers: `/squadron-overview` (`controller/HangarController.java:139`, class-level only) and POST `/ships/home-location` (:329-330, `isAuthenticated()`). The rules at `:407-418` don't match either path (exact `/ships`, `PUT /ships/*`).
- Services: the reads use `ownerScopeService.currentScopePredicate()` (`service/InventoryAggregationService.java:107,131,156,179,360,540,782,854,904,938`; `service/InventoryItemService.java:420`; `service/HangarService.java:405`). The writes are owner-scoped from the JWT. `item-catalog` has no scope because it is non-tenant catalogue data.

**Why narrowed: the precondition population is empty by design**
- `default-roles-iri` is a composite containing `KRT Member` (`docs/keycloak/realm-config.reference.json:108-117`). REQ-SEC-053 states "no account holding only a bank role… confirmed by the repository owner, 2026-09-06" (`docs/specs/security-and-access.md:3732-3734`).
- The committed prod-reference realm has **no** Logistician or Mission Manager realm roles.
  - `ROLE_LOGISTICIAN`/`ROLE_MISSION_MANAGER` are membership-derived and *added to* the realm roles (`service/CustomJwtGrantedAuthoritiesConverter.java:383-393`). No local role row exists for them (`config/DataInitializer.java:56-82`).
  - So an MM-only account needs no realm role at all, which REQ-SEC-053 rules out.
- The Android partial-scope token carries KRT Member (`scripts/provision-keycloak-mobile-client.py:36`). The ingest acting-member path is pinned to 13 exchange paths (`config/ActingMemberFilter.java:98-112`).
- Even for such an account:
  - Bank roles carry **no** permissions (`DataInitializer.java:81-82`), so the hangar rule refuses them anyway.
  - Scoped reads without memberships return nothing (`repository/ScopeSpecifications.java:105-112`). A non-admin pin needs membership (`service/RequestScopeResolver.java:164-168`).
- The only way to create one is an admin manually unassigning `default-roles-iri` in Keycloak.

**Gap worth recording:** no test isolates these URL rules. The one test (`backend/src/test/.../SecurityHardeningIntegrationTest.java:89-95`) uses `ROLE_NO_ROLE`, which `PendingApprovalAccessFilter` (`config/PendingApprovalAccessFilter.java:213`) refuses before the URL rule. No test pairs a bank role with `/inventory` or `/hangar` (grep, 0 files).

## 2 — API-05 · NARROWED (not a vulnerability) · severity: data integrity, low

- **Confirmed mechanics:**
  - The mapper ignores only owner/owningOrgUnit/createdAt/updatedAt (`mapper/RefineryOrderMapper.java:118-122`). The generated code does `refineryOrder.setStatus(Enum.valueOf(RefineryOrderStatus.class, dto.status()))` when the status is non-null (`build/generated/.../RefineryOrderMapperImpl.java:186-187`).
  - `RefineryOrderDto.status` is an unconstrained `String` (`model/dto/RefineryOrderDto.java:50`).
  - `createRefineryOrder` resets id/version/owner/org unit but not status (`service/RefineryOrderService.java:279-286`).
- **It is a designed feature.** The create form offers OPEN / IN_PROGRESS / **COMPLETED** / **CANCELED** (`frontend/src/main/resources/templates/refinery-orders-create.html:169-174`), and so does the edit form (`refinery-orders-details.html:128-133`).
- **PUT already allows it.** A non-logistician owner may set any status via PUT (`RefineryOrderService.java:431-434`, owner escape `service/AccessGateService.java:518-524`). No transition rules exist in code or spec.
- **What COMPLETED unlocks: nothing.**
  - It only makes `store` refuse with `already_stored` (`RefineryOrderService.java:562-564`).
  - Status is read by the craftability pending-yield pool, OPEN/IN_PROGRESS only (`:142-145` → `service/BlueprintCraftabilityService.java:109`), and by the queue gauges (`task/BusinessMetricsCollector.java:135-139,206-209`).
  - There is no payout, bank or inventory effect.
- **"Own data only" holds for goods too.** The mapper copies the client's good id (`RefineryOrderMapperImpl` `refineryGood.setId(dto.id())`), but the version is dropped.
  - Hibernate 7.4.5 treats a null-version entity as transient (`VersionValue.java:51-56`).
  - `UuidGenerator.generate` ignores the preset value (`UuidGenerator.java:96-97`; `AbstractSaveEventListener.java:125-133,160-163`).
  - So a foreign good row cannot be addressed.
- **Adjacent real defect (not in the claim):** because PUT can move COMPLETED back to OPEN and `store` checks only `== COMPLETED`, a stored order can be reopened and **stored again**. That duplicates inventory rows and `INVENTORY_RECEIVED_FROM_REFINERY`/`REFINERY_ORDER_STORED` audit events.
  - For a member this is own inventory only (`:586-599`), which `POST /inventory` allows anyway.
  - `store` also skips the REQ-ORDERS-018 "material required by the job order" check (`:617-623,640-642`) that `POST /inventory` applies (`service/InventoryItemService.java:914-922`). Not traced further.

## 3 — XC-02 / RES-08 · CONFIRMED · severity: observability (fail-closed, not a bypass)

- **Chain, from source:**
  - `BeanFactoryResolver.resolve` turns a `BeansException` into an `AccessException` (spring-context 7.0.9 `BeanFactoryResolver.java:48-55`).
  - `BeanReference` wraps that in a `SpelEvaluationException` (an `EvaluationException`) (spring-expression 7.0.9 `BeanReference.java:61-75`).
  - `ExpressionUtils.evaluate` catches it and throws `IllegalArgumentException("Failed to evaluate expression …")` (spring-security-core 7.1.1 `authorization/method/ExpressionUtils.java`, catch block; called from `PreAuthorizeAuthorizationManager.java:88`).
- **Handler:** `exception/GlobalExceptionHandler.java:594-611` returns 400 `ILLEGAL_ARGUMENT`. It is logged at **WARN** without a stack trace (`:228-234,271`). `basetool_http_error_total` is **not** incremented: `countHttpError` is called only at `:307,352,380,409`.
- **SpEL beans:** 8 distinct, 6 default-named `@Service` beans.
  - Default-named: `ownerScopeService`, `missionSecurityService`, `authHelperService`, `orgRoleManagementSecurityService`, `bankSecurityService`, `specialCommandSecurityService`.
  - Explicitly named: `@Component("exchangeGate")` and `@Component("connectedAppsGate")` (`service/exchange/ExchangeGate.java:63`, `ConnectedAppsGate.java:35`).
  - No test resolves every reference generically. Endpoint security tests catch a rename only where they cover the endpoint.
- **Alerts:**
  - No 5xx alert fires (`monitoring/prometheus/alerts/apps.yml:6` is 5xx only), and no Loki rule matches (`monitoring/loki/rules/fake/basetool-log-alerts.yml`).
  - **But** the web frontend counts every non-gate backend 4xx as `basetool_backend_client_errors_total{reason="backend_4xx"}` (`frontend/.../service/BackendApiClient.java:458-463`). `BackendCallFailureSustained` fires at > 0.5/s for 10 min (`monitoring/prometheus/alerts/business.yml:680-682`), so a burst on a web-used endpoint above that rate is noticed.
  - Android/API-vhost traffic is not counted.

## 4 — XC-22 · CONFIRMED with two corrections · severity: availability of bearer writes

- **Confirmed:**
  - `CSRF_EXEMPT_PATHS = {"/api/v1/**", "/internal/**"}` (`config/SecurityConfig.java:111`). The test profile disables CSRF (`:314-317`).
  - All 191 context tests use `@ActiveProfiles("test")`, and the Gradle `Test` task forces `test` (`build.gradle.kts:186`).
  - The only CSRF test, `backend/src/test/.../config/SecurityConfigCsrfExemptionTest.java`, pins the *matcher* for 7 sample URIs plus the `/api/v1/**` entry. It would not catch a new or moved write.
- **Correction 1:** the E2E stack runs the backend with `SPRING_PROFILES_ACTIVE: dev` (`docker-compose.yml` backend-dev, ~line 533, not overridden by `docker-compose.e2e.yml:27-40`). CSRF is **on** there. A moved write that the Playwright suite exercises through the web frontend would fail E2E, which runs only on `e2e`-labelled PRs.
- **Correction 2, a live instance today:** `POST /actuator/loggers/**` (`SecurityConfig.java:370-371`, main chain on the management port) is outside the exemption.
  - The documented REQ-OBS-016 command is bearer-only (`docs/specs/observability.md:3082-3086`).
  - `backend/src/test/.../ActuatorLoggersAuthorizationTest.java:93-98` expects 204 without a CSRF token. It passes only because CSRF is off.
  - So in prod the documented runtime log-level write is expected to get **403** (missing CSRF token).
  - UNVERIFIED against prod. A single documented call, or its access log, would settle it.

## 5 — PSA-02 · CONFIRMED and stronger · severity: availability, medium-low (not attacker-triggerable)

- **Confirmed:**
  - `config/KeycloakTrustSupport.java:74-75` builds `HttpClient.newBuilder().sslContext(..)` with no connect timeout and a `JdkClientHttpRequestFactory` with no read timeout. Spring applies a timeout only when one is set (spring-web 7.0.9 `JdkClientHttpRequest.java:119-120`).
  - `service/KeycloakService.java:122-125` replaces the 5 s / 30 s factory from `config/RestClientConfig.java` with it.
  - The bundle exists only in prod (`application-prod.yml:26-32`).
- **Stronger than claimed:** `SecurityConfig.java:177-182` also replaces Spring Security's own `RestTemplateWithDefaultTimeouts`, 30 s / 30 s (`NimbusJwtDecoder.java:302,577-585`; `JwtDecoderProviderConfigurationUtils.java:88-100`). The framework comment says this default exists because the JWKS fetch runs while holding a lock.
  - Prod has used this path since 2026-09-25 (vault `10 Systems/Backend.md:13`).
- **Bounds:**
  - The Nimbus cache TTL is 5 min, and waiters give up after 15 s (`JWKSourceBuilder.java:82,89`; `CachingJWKSetSource.java:229-290`).
  - A hung refresher therefore makes every token validation after the TTL fail after 15 s, for as long as the read hangs, which has no upper bound.
  - Virtual threads are on (`application.yml:28-30`), so there is no platform-thread exhaustion.
- **Admin-API side:**
  - The nightly `UserSyncTask` runs at 05:00.
  - `ConnectedAppsService.disconnectClient` is `@Transactional` and makes 3 Admin-API calls inside it (`service/exchange/ConnectedAppsService.java:230-262`). A hang pins a Hikari connection per attempt (prod pool 100).
- **Trigger:** Keycloak accepting the connection but never answering, or a silent network drop. A Keycloak restart resets the connection.

## 6 — S783-PAGECAP · CONFIRMED · severity: low (authenticated-member resource use; deliberate design)

- `web/PaginationUtil.java:45` sets `MAX_PAGE_SIZE = 100_000`. The Javadoc (`:30-36`, `:39-44`) calls it deliberate ("load all in one request").
- Reach:
  - 70 call sites in 66 handlers across 42 controllers.
  - **30 of the 66 are allow-listed on the `api.*` vhost** (`docker/edge/include/api-allowlist.conf`, 172 allow rules; script below). Examples: bank transactions, notifications, orders, refinery `/all`, materials matrix and prices, personal blueprints.
  - Web pass-throughs also exist, e.g. `frontend/.../BankPageController.java:184→278→345` (bank bookings) and `MemberManagementController` (`/api/v1/users`). Other web pages clamp to 10/50/100 (`HangarPageController.java:163`, `BankManagePageController.java:103`).
- Bounds:
  - Edge: 20 r/s per IP with burst 80, and 500 connections (`docker/edge/conf.d/00-maps.conf:15-16`, `include/limits.conf`). Proxy read timeout 90 s (`docker/edge/nginx.conf:26`).
  - Backend: 120 req/min per subject (`application.yml:187-191`), 30 s per statement (`application.yml:74`).
  - Local lower caps: finance entries, stack entries 100, Materialbörse 500, sync reports 200.
  - There is no response-size cap.
- Amplification: min(100 000, rows in the caller's scope) per request, at most 120 requests per minute per member. Production row counts are UNKNOWN; read-only counts per table would settle them.

## 7 — S1672-LIKE · NARROWED · severity: informational

- **Confirmed:** `service/BlueprintProductService.java:86→88→315-317` passes the trimmed `q` into `repository/BlueprintRepository.java:216-220` (`LIKE LOWER(CONCAT('%', :q, '%'))`, line 219) without escaping. Any member reaches it at `GET /api/v1/blueprints/products/search` (`controller/BlueprintProductController.java:47,64-80`).
- **Refuted part:** `:125-126` (`searchActive`) is escaped by its only caller: `service/BlueprintService.java:61` `LikePatterns.escapeNullable(search.trim())`.
- **Missed by the claim:**
  - `findOrderableItems` (`:171,179`) is called unescaped from `service/JobOrderItemService.java:402-403` (`GET /orders/item-catalog`).
  - `findItemsWithActiveBlueprint` (`:197,203`) is called unescaped from `service/InventoryItemCatalogService.java:56-58` (`GET /inventory/item-catalog`).
- **Impact:** wildcard broadening only.
  - Parameters are bound, so there is no injection.
  - The data is a shared catalogue that a blank query already returns in full.
  - A trailing `\` escapes the appended `%`, which is still a valid pattern and raises no error.

## 8 — XC-12 · CONFIRMED · self-invocation today: NONE

- There are exactly 18 real annotations (`grep '^\s*@PreAuthorize('` outside `controller/`):
  - `MemberEvaluationService` :101,116,160
  - `PromotionCategoryService` :147,182,216
  - `PromotionLevelContentService` :123,154,187
  - `PromotionTopicService` :126,167,190
  - `RankRequirementService` :133,184,217
  - `PromotionEligibilityService.evaluateAllForUserAsAdmin` :163
  - `MissionFinanceEntryService.updateEntry/deleteEntry` :187,218
  - None are class-level.
- **Self-calls:** 0 unqualified or `this.` calls and 0 method references (`::name`) to any gated method inside its class. There is no `ObjectProvider`/`AopContext` self-proxy.
- **Load-bearing:** for `PUT/DELETE /api/v1/finance-entries/{entryId}` the controller has only `isAuthenticated()` (`controller/MissionFinanceEntryController.java:167-181`). Both paths are allow-listed on the `api.*` vhost. For promotion-category PUT/DELETE the controller has class-level `isAuthenticated()` only (`PromotionCategoryController.java:57,188,209`). The service proxy is the only gate there.

## 9 — JAVA-04 · CONFIRMED (not exploitable), one correction

- `service/exchange/ExchangeMassChangeService.java:278-284` has `default -> HANGAR_WRITE`. The DTO enforces `@Pattern("^(blueprints|stock|ships)$")` (`model/dto/ConnectedAppMassChangeRequestDto.java:41`).
- **Every path is validated.** The service's only callers are `controller/ConnectedAppsController.java:85-88` and `:108-111`, both `@Valid @RequestBody`.
- **Correction:** `read()` (:258-270) validates the *inner change set*, never `resource`. It also runs after `admit()` has already called `capability()` (:182). The controller `@Valid` is the sole guard.
- **Even without it there is no escalation.** `capability()` (:282) and `run()` (:239) both default an unknown resource to ships. So the client must hold HANGAR_WRITE for exactly the write that runs. The residual effect would be an unvalidated string in the audit details (:149) and the response.
- **Adjacent, not traced further:** all DTO fields, including `stagedAt` and `changeSet`, are browser-echoed with no integrity tag. The staging-window and "revoked or suspended since staging" checks (:185-208) therefore trust a member-supplied time. The effect is limited to the member's own data under a capability the client holds; provenance only.

## 10 — API-06 · count and gates CONFIRMED; impact NARROWED · severity: informational

- There are 209 `@RequestBody` parameters in total.
- The scanner flagged 19. Six were false positives after manual check:
  - `BankBookingController.java:205` and `MissionController.java:442` have `@Valid` after `@RequestBody(required = false)`.
  - Four promotion PUTs use `@Validated({Default.class, OnUpdate.class})`.
- The remaining **13** match the claim:
  - `AdminController.java:92,105` (class-level ADMIN)
  - `DiscordRegistrationAdminController.java:125`
  - `FrequencyTypeController.java:103,121,156`
  - `MaterialCategoryController.java:85,102`
  - `RefiningMethodController.java:100,118`
  - `StarSystemController.java:99,116`
  - These are the `@RequestBody` lines; the claim cites the mapping lines. Together they are 12 ADMIN-only.
  - `DiscordAccountExistenceController.java:79`: `permitAll()` plus a constant-time shared-secret check (`:85-89`).
  - `/internal/**` is not on the `api.*` allow-list, and the web vhost proxies to the frontend, so it is internal-network only.
- **Narrowing:** none of the body types has a single Bean Validation constraint. `FrequencyTypeDto`, `MaterialCategoryDto`, `RefiningMethodDto`, `StarSystemDto`, `ApproveRegistrationRequest` and `DiscordAccountExistenceRequest` all have 0; the other two bodies are a `Set<String>` and a `String`. Adding `@Valid` alone would validate nothing. The real gap is missing constraints; today the DB constraints act as the backstop.

---

## Appendix — scripts and commands (all in this scratchpad)

- `95-verify-1-realm.py`: dumps realm roles, composites, `fullScopeAllowed` and test users from the 4 committed realm JSONs.
- `95-verify-1-spelbeans.py`: finds the SpEL `@bean` references in method-security annotations and how each bean is named. Per-bean reference counts may include Javadoc mentions; the set of 8 is exact.
- `95-verify-1-pagecap.py`: finds the handlers calling `PaginationUtil`, joins them against `api-allowlist.conf`, and prints the gates.
- `95-verify-1-selfinvoke.py`: first pass on service-level gates. Its class-level flag was fooled by Javadoc and superseded by the exact `grep '^\s*@PreAuthorize('` count and a per-class self-call grep (0 hits).
- `95-verify-1-requestbody.py`: `@RequestBody` without `@Valid`. Six false positives were removed by reading each site.
- Library sources were read with `unzip -p <sources.jar> <path>` from `~/.gradle/caches/modules-2/files-2.1/…` at the versions named in the header.
