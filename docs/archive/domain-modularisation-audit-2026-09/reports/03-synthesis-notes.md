# Synthesis notes (coordinator) — key points per agent report, for the plan

## 50-modules-build (MB, 21 findings) — read fully 2026-09-29
- MB-01 exchange internal surface: ingest relays 14 ops on 13 paths `/api/v1/exchange/**`, gateway
  client-credentials identity + 5 relay headers (`X-Ingest-On-Behalf-Of`, `X-Exchange-Client`,
  `-Capabilities`, `-Installation`, `-Connected-At`), backend honours only for azp in
  `app.security.ingest-gateway.client-ids` and only on the 13 patterns of
  `ActingMemberFilter.EXCHANGE_PATHS` (exhaustive, never a prefix); `@exchangeGate.allows` 14/14.
  In /api/v1 URL space and backend openapi.json (13 of 440 paths) but NOT in the frozen app contract
  set of ExternalContractTest -> nothing treats it as frozen. Proposal: declare it the exchange
  domain's internal published API (spec + ADR-0216 amendment), fence as own springdoc group/document,
  keep path (optional move = own two-release step), e2e label required for PRs touching
  controller/exchange, service/exchange, model/dto/exchange, ActingMemberFilter,
  GlobalExceptionHandler, TermsAcceptanceAccessFilter, PendingApprovalAccessFilter.
  Relayed codes come from backend-WIDE components (GlobalExceptionHandler ACCESS_DENIED,
  VALIDATION_FAILED, BAD_REQUEST, OPTIMISTIC_LOCK; TermsAcceptanceAccessFilter TERMS_NOT_ACCEPTED;
  PendingApprovalAccessFilter PENDING_APPROVAL/NO_ROLE; ActingMemberFilter ACTING_MEMBER_REFUSED;
  ExchangeProblemException 7 gate codes, CURSOR_EXPIRED 410, MASS_CHANGE_CONFIRMATION_REQUIRED 409);
  anything else -> 502 BACKEND_RELAY_FAILED. Global JSON input handling (NormalizedStringDeserializer
  trim/NFC/length cap; FAIL_ON_NULL_FOR_PRIMITIVES=false) is part of the frozen behaviour.
  Android never calls the gateway.
- MB-02 no build-time test holds backend exchange DTOs to the 28 schemas / 101 fixtures (only ingest
  reads them; drift = prod 502). -> backend ExchangeWireContractTest (networknt validator already in
  catalog), assert fixture count = files on disk. BEFORE any re-cut. M.
- MB-03 duplicated wire identifiers mostly unpinned: headers 1/5, gate codes+status 0/7, relay paths
  0/14, capability enum backend no, registry mirror doc 0 (reader SILENTLY falls back on
  minClientVersion, requestsPerMinute, writesPerDay -> renamed field drops REQ-XCH-024 min version),
  revocation prefixes 0, handoff prefix/StagedHandoff/HandoffKind 0 (E2E only), landing pages,
  SPI precheck + admin extension 0. -> parity test family. S each.
- MB-04 exchange draft routes reuse web DTOs RefineryExtractDto (request), RefineryImportDraftDto,
  BlueprintImportPreviewDto -> own exchange DTOs (anti-corruption) before refinery/blueprint re-cut;
  keep all size caps (DoS guard).
- MB-05 ingest package-by-kind, 9 two-way package pairs, `exchange` 26 classes/5,416 lines (43%),
  ExchangeController 1,076 lines/11 collaborators. Re-package by concern (edge, auth, gate, limits,
  idempotency, relay, handoff, contract, web, observability, config) + ArchUnit (no cycles; only relay
  may use web.client; only gate/limits/idempotency/handoff may use Redis; edge must not use relay...).
  Golden-answer test per route recorded before the move. M.
- MB-06 exchange filter order inside Spring Security chain untested -> FilterOrderTest via
  FilterChainProxy.getFilterChains(). S.
- MB-07 PIT targets `${project.name}.service.*` -> ingest gates never mutated; ingest not in pitest
  matrix; also a package-move landmine (fails loudly after CI-03 fix). S.
- MB-08 keycloak-spi flat package, 3 concerns; 1 of 6 META-INF/services registrations tested ->
  sub-packages + ServiceRegistrationsTest. S.
- MB-09 SPI compiles against Keycloak 26.7.4 internals; runtime image tag `26.7@sha256` minor tag;
  nothing ties them -> pin patch tag + repo-lint parity. S.
- MB-10 shared libs: A needs no new module; B/C need a narrowly named backend-internal kernel module
  with own ADR (ADR-0205 rejected a general common module); never share DTOs backend<->frontend;
  FrontendPageRoutes -> frontend testFixtures.
- MB-11 build logic: root subprojects{} 354/527 lines, CC-clean; name-keyed maps (heap, JaCoCo floors
  default 0.50/0.40, PIT targets, SBOM path) default SILENTLY for new modules -> typed per-module
  extension now (S); included build-logic only before B/C (M; verification-metadata, Dockerfile COPY,
  path filters `**/build.gradle.kts` miss precompiled plugins).
- MB-12 13 path-keyed cross-module test inputs; DtoMirrorConsistencyTest SILENTLY skips a frontend
  DTO whose backend twin moved (fails only when no pair left) -> index by class name / openapi,
  fail on unpaired; repo-lint "every inputs.file path exists". Before first DTO move.
- MB-13 package-keyed analysis config: PIT, SpotBugs exclude `backend.model..*` EI_EXPOSE_REP,
  ArchUnit predicates; ArchUnit imports whole root package incl. other jars -> add non-empty asserts.
- MB-14 option B cost: ~45 backend modules (21 domains x api/impl + kernel + security + app);
  11 registrations each, 4 silent if forgotten (sandbox-images path filter, JaCoCo floor default,
  PIT target, Flyway check). Compiler still blind to 113 SpEL bean refs in @PreAuthorize
  (@ownerScopeService 43, @missionSecurityService 40, @authHelperService 17, @exchangeGate 14,
  @bankSecurityService 7, @specialCommandSecurityService 5, @connectedAppsGate 1; 432 @PreAuthorize
  total, 145 via Roles constants), 187 JPA association annotations / 115 entities (@ManyToOne/@OneToOne
  targets: User 23, OrgUnit 14, Material 14, Mission 10, GameItem 8, JobOrder 7, BankAccount 6,
  Blueprint 5), MANDATORY entity hops. Recommendation: A now, C per proven leaf domain, no B, no D.
  Keep full-chain security tests in the application module.
- MB-15 test contexts: @SpringBootTest backend 231 / frontend 161 / ingest 21; @ActiveProfiles in 191
  backend files though task forces profile; static proxy 49 keys backend (40 singletons; groups of 94
  and 16 differ only by @ActiveProfiles("test")); BLD-PERF-03 still open -> do before structural
  moves; keep security beans real (MockitoBean strips annotations).
- MB-16 one image per app stays in B/C; prefix rules in image_reuse_plan.py/check_sbom_coverage.py.
- MB-17 JPMS rejected. MB-18 option D rejected (ingest shows boundary cost: 75 classes to front 14
  ops; audit must be same-transaction). MB-19 all Flyway migrations stay in backend (256 in one dir).
- MB-20 VAULT DRIFT to fix: Testing.md:270-300 (three Dockerfiles -> one docker/app/Dockerfile since
  2026-09-23, bootJar, no test-support sources), Testing.md:302-310 (configureondemand and
  evaluationDependsOn gone), Testing.md:63 (test file counts: backend 674, frontend 368, ingest 78,
  keycloak-spi 9, test-support 3, logging-support 4), Ingest.md:702-711 (LogSafe/PiiMasker in
  logging-support since ADR-0205/#2016), Keycloak SPI.md:57-58 (six service files).
- MB-21 modern Gradle: typed extension, java-test-fixtures, jvm-test-suite for e2e, CC locally,
  jacoco-report-aggregation only for C/B.
## 80-prev-sept-a (Sept audit, Backend 37 / Build 22 / Ingest 12 / Keycloak 5 = 76) — read fully
- Status: DONE 66, PARTIAL 7 (APPSEC-02 picker, BE-MOD-02 timeouts, BE-PERF-12, BE-SIMP-10 frontend
  482 FQNs, BLD-PERF-04 CC default for devs, ING-MOD-02 MonitoringScrapeProperties @Data,
  THEME-SIMP-01 fonts), OPEN 1 (BLD-PERF-03), SUPERSEDED 1 (ING-PERF-01 by ING-MOD-01),
  REGRESSED 1 (BE-SIMP-08 new blankToNull copy ExchangeRegistryService:413-416, a2e82ff34).
- Verdicts: CONFIRMED 58, ADJUSTED 15, SUPERSEDED-BY-MODULARISATION 1 (BE-SIMP-05 -> mission read
  API returns viewer-specific views), DROPPED 1 (THEME-SIMP-01 shared fonts), REPRIORITISED 1
  (ING-MOD-02 -> P1, credential in toString).
- New priority items: BLD-PERF-03 P1 (profile unification: 191 with @ActiveProfiles, 40 without) /
  P2 module test slices; BE-MOD-02 residual P1 = PSA-02; ING-MOD-02 P1; BE-PERF-12 P2 (invert: plain
  findById, named graphed lookup; 47 non-repository classes use UserRepository); TST-18 P3 (Postgres
  test image floating tag postgres:18-alpine vs digest in compose); ING-SEC-04 P3 (jar default
  verify-backend-hostname=false -> flip to true); SEC-16 P3 (renewal procedure lost in ADR-0214 sweep
  -> document in CONTRIBUTING; all 9 suppressions expire 2026-12-22).
- ADJUSTED to module steps: APPSEC-01 (inventory rule copied in 3 domains: InventoryItemService,
  JobOrderItemProductionService, RefineryOrderService; + ExchangeStockWriteService writes personal
  rows directly -> one inventory booking command API); APPSEC-02 (refinery -> MissionParticipant
  Repository new edge; 4 non-mission classes use that repo -> mission query port isParticipant /
  participatingMissions, also serves open picker filter); BE-SIMP-09 (caller memberships memoised 3x:
  RequestScopeResolver, OrgRoleManagementSecurityService, UserMapper -> one org-unit-owned query;
  canViewJobOrders memo in RequestScopeResolver belongs to joborder); BE-PERF-01 (UserMapper hub, 16
  dependants, callers must prime memo -> member reference via identity batch query); BE-PERF-11
  (CachedEntityGraphs = catalogue helper in support -> catalogue caches read models, id refs);
  BLD-SIMP-06 (name-keyed floors trap); IMG-SIMP-14 (Dockerfile enumerates modules); BE-PERF-15
  (duplicate memo).
- NEW PSA-01 (BLOCKER for option A): sealed AppException permits 13 subclasses incl. domain ones
  (BankConflictException, ExchangeProblemException, MissionParticipantRequiredException,
  OverAllocationException, OwnerOrgUnitRequiredException, ProductionAllocationException);
  AppExceptionKind 11 constants incl. domain ones. javac 25 proof: sealed class in the unnamed module
  cannot permit a subclass in another package
  (compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package); same package compiles.
  Problem codes are an unschematised wire contract (frontend BackendServiceException mirrors, ingest
  ExchangeRelay translates, openapi.json lists none). Proposal: sealed kernel of generic kinds + one
  non-sealed abstract domain-problem base; ProblemKind interface implemented by per-domain enums;
  registry test with unique codes vs committed list. New ADR; before first domain package move.
- PSA-02 KeycloakTrustSupport (be config:74-75) builds HttpClient without connect/read timeout, used by
  KeycloakService admin client (prod) and the internal JWKS decoder (SecurityConfig:177-181, on in
  prod since 2026-09-25) -> hung Keycloak blocks JWKS refresh on auth path. P1 fix S.
- PSA-03 hand-mirrored platform classes diverged across apps: ManagementPortSecurityConfig,
  MonitoringScrapeProperties (frontend+ingest @Data print password), TracingEnabledMetric,
  CorrelationIdFilter, StartupBannerListener, KeycloakTrustSupport. Short: records + parity tests;
  medium: evaluate a scope-closed platform module (own ADR; ADR-0205 closes logging-support).
- PSA-04 support hub: 63 classes incl. domain helpers (MissionPeerRedactor, MissionSectionVersions,
  MissionViewerAccess, InventoryAllocations, InventoryAuditLabels, JobOrderAuditLabel,
  JobOrderInventoryOwnerRedactor, StockViewerAccess, StaffelMembershipResolver) + 18 of 27 properties
  records; ArchUnit leaf rule message tells authors to put shared logic there. Split into kernel
  (RequestMemo, StringNormalization, OptimisticLock, LikePatterns, ProblemResponseFactory,
  Roles/Permissions) + per-domain internal packages.
- Guards keyed on layout: peerReadableMissionEndpointsMustRedactPii keyed on package
  '.backend.controller' + hard-coded DTO names, floor >= 10 (ArchitectureTest:1129-1174) -> a move can
  silently de-select handlers while >= 10 remain; Entities.require ratchet scans only
  backend/src/main/java.
- Constraint: AOT cache training (IMG-PERF-12) fails the image build if a new bean needs DB/Redis at
  refresh (e.g. an event publication registry querying at startup) -> plan stubs before adding such a
  framework. BE-PERF-04: order_inserts must stay off (id refs strengthen the reason) -> config assertion.
- VAULT DRIFT to fix (PSA-05): Improvement Audit note Done table omits BE-SIMP-04/-05 (#1994/#1996) and
  BLD-PERF-03 (open); APPSEC-02 marked done though picker open (RefineryOrderPageController:814
  /api/v1/missions?size=1000); THEME-SIMP-01 fonts not done; BE-SIMP-10 frontend half (482 FQNs) open;
  Security.md:880-881 suppression-header renewal claim (header gone since #2074); Backend.md:368-369
  (27 records, not 16), :373-374 (second trimToNull copy), :344-347 (3 reference-only callers use graphed
  findById); Ingest.md:713-714 (MonitoringScrapeProperties is @Data).

## 30-frontend-java (FE-01..16) — read fully
- FE-01 package-by-layer; controller pkg 103 files, 33,630 of 66,858 lines; 70 @Controller + 26
  @RestController + 7 helpers; model.dto 292 (279 records), model.form 30, config 62 (all cross-cutting:
  20 security, 12 session, 8 web, 7 obs, 6 backend hop, 6 layout, 3 livesync); view shaping sits in
  controller pkg (arc42 §5.3 says service). Every class maps cleanly to a domain.
- FE-02 single seam NOT gated: 14 classes inject WebClient; 11 controllers bypass BackendApiClient error
  mapping (AdminP4kImport, AdminPersonalBlueprints, AuditReportProxy, BankReportProxy, DataExportProxy,
  HangarDeleteAllProxy, HangarImportProxy, InventoryDeleteAllProxy, JobOrderHandoverReportProxy,
  OrgUnitBankProxy, PersonalBlueprintImportProxy) -> lose reauth mapping (ClientAuthorizationException
  becomes 500 not 401 X-Reauthenticate), drop RFC 7807 code, invisible to
  basetool_backend_client_errors_total, invisible to DeprecatedBackendEndpointCallGuardTest (deprecated
  POST /api/v1/hangar/import/fleetview still relayed, sunset 2027-05-14, dead route). arc42 §4.1 claims
  ArchUnit enforces the seam — no such rule (doc drift). SSE relay + live-sync probe are deliberate
  exceptions (no resilience, no OAuth2 filter). Fix: kernel shapes exchangeForEntity(bytes)+headers+
  per-call timeout, postMultipart(Resource), getFlux; ArchUnit R3; delete dead route; fix docs.
- FE-03 624 HTTP call sites: 356 concatenated, 148 literals, 41 templated/UriComponentsBuilder; only
  get() takes URI vars. REQ-SEC-051 gaps: 4 handlers bind browser id as String where backend takes UUID
  (HomeController:192-194, :213-215 read-announcement; PromotionPageController:266,271 -> :551-554;
  AdminDefaultBlueprintsPageController:205-208 URLEncoder in path); PersonalInventoryPageController:
  380-384 sort bypasses RelayParams. Crafted id can append query/path segments to a relayed call with the
  caller's own token (backend still authorizes; no privilege gain). Fix S; test a?b#c/../x -> 400, zero
  backend calls.
- FE-04 typed clients per domain keep the single Resilience4j pass IF built on the one webClient bean
  (Spring 7.0.9 sources: WebClientAdapter.newRequest -> this.webClient.method). Resilience =
  WebClientConfig#resilienceFilter (bulkhead->time limiter->retry idempotent->breaker 5xx only); OAuth2
  bearer relay, correlation/org-unit/locale/client-IP relays, logging, Accept list, TLS, HTTP/2 pool are
  properties of that bean. BackendApiClient adds: error mapping, catalogue cache (@Cacheable getCached),
  anonymous terms client. T1 = thin per-domain class delegating to BackendApiClient; T2 = @HttpExchange
  interface via HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)) +
  exchangeAdapterDecorator (HttpExchangeAdapterDecorator, since 7.0) reusing extracted BackendErrorMapper.
  BAN @ImportHttpServices / HttpServiceGroupConfigurer (WebClientHttpServiceGroupAdapter creates fresh
  WebClient.builder() per group = no filters/pool/TLS/bearer). SECURITY: forbid java.net.URI /
  UriBuilderFactory params (URI arg overrides whole URL -> OAuth2 filter sends member bearer to that host);
  forbid @CookieValue; every path relative /api/. July rejection's core kept: one error mapper, one metric,
  one cache. Recommend: one client interface per domain, start T1, switch to T2 at the domain's re-cut;
  new ADR amending arc42 §4.1 ("one filter chain, one error mapper, typed clients per domain").
- FE-05 atomic deploy: host applies release in one restart window, frontend.container Requires=backend,
  rollback of all pins together (docs/deployment.md:566-619); frontend reaches backend internally
  (BACKEND_URL=https://backend:11261), not via api.*. Gap: no gate fails when a frontend call names a
  path the backend no longer serves (DeprecatedBackendEndpointCallGuardTest only deprecated ops, floor 150).
  LIVE-SYNC PROBES FAIL OPEN on 400/405/5xx for non-presence topics (LiveSyncSubscriptionAuthorizer
  :91-93,236-276) -> a probe left pointing at a re-shaped path can admit subscribers. Guards G4 (every
  frontend call matches an operation in openapi.json), G6 (every LiveSyncTopicClass probe template matches
  a GET). Per-domain frontend touch table (catalogue 70 own calls, bank 73, identity 52 + 23 from others,
  mission 68, joborder 52, orgunit 26 + 23 from others...). Identity + orgunit re-cut last.
  NOTE: the agent proposed expand/contract for Android — SUPERSEDED by owner decision "hard cut + forced
  update". Also claims api.* allow-list is in NPM not git (ADR-0136) — VERIFY (edge is native nginx since
  ADR-0162).
  Keep frontend routes stable during backend re-cut (browser tab of release N hits frontend N+1).
- FE-06 keep hand mirrors (generator test-only: 420 mutable classes, 6 with Jackson-2 annotations;
  MissionDto 1,254 lines vs 72-line record); mirrors = OUTPUT ALLOW-LIST. Key the three contract tests on a
  marker annotation instead of package model.dto.
- FE-07 72 raw-Map responses (MissionWriteController 23, MaterialboersePageController 13,
  LeitungPageController 12, PromotionProxyController 9 returns backend Map unchanged to browser), 78
  @RequestBody Map handlers in 15 controllers -> no contract; a new backend field (PII) flows to browser.
- FE-08 session allow-list prefix `frontend.model.` + org.springframework.security.; prod enforce since
  2026-09-25; flashed app types ≈11 (≈20 with nested): RefineryOrderForm, JobOrderItemForm,
  PersonalInventoryForm, ShipForm, InventoryForm, ParticipantForm, BankWipeResetResultDto, ImportIssueDto.
  A move out of frontend.model silently drops flash attributes (user loses input after failed save).
  Options: keep session types under frontend.model.<domain>, or (security-improving) exact test-derived
  list via SessionValue marker + closure test: 325 admitted classes -> ≈20. ADR-0206 amendment +
  REQ-SEC-067; own release before the move. Never widen to `frontend.` prefix.
- FE-09 172 T(de.greluc...frontend.support.Roles) in 22 templates, all in sec:authorize; no static test
  (G5 TemplateTypeReferenceTest; G8 view names resolve). Templates flat: 62 root, admin/ 22,
  fragments/ 30, organisation/ 2, error/ 4.
- FE-10 coupling = page composition: 0 cross-domain controller->controller edges; 173 cross-domain class
  edges, 7 two-way pairs (joborder<->inventory, mission<->operation, mission<->refinery,
  mission<->identity, mission<->inventory, orgchart<->leadership, catalogue<->personalinventory); 11
  kernel->domain edges. Don't demand acyclicity between frontend domains; demand no dependency on another
  domain's web package. Merge orgchart+leadership = "organisation".
- FE-11 gate checked per class not per handler; 535 handlers, 13 without own/class gate (11 public by
  design + /org-chart + /ship-data member pages). No route/gate snapshot. -> G2 route/gate snapshot
  (RequestMappingHandlerMapping: verb, path, consumes/produces, effective @PreAuthorize, @UsesLayoutModel)
  byte-identical across moves; G3 per-handler gate rule.
- FE-12 ParallelPageLoader copies org unit, correlation, client IP, security ctx, request attrs, MDC but
  NOT locale -> backend calls from 8 controllers go out in default locale. Fix S.
- FE-13 BackendApiClient.handleException instanceof chain -> sealed outcome + pattern switch with `_`.
- FE-14 doc drift: arc42 §4.1 (no ArchUnit seam rule), §5.3 (view pkg = 1 class MoneyFormat; view shaping
  in controller), frontend/CLAUDE.md:228 ("Resilience4j wraps every backend call" false for sseWebClient,
  liveSyncAuthWebClient), vault Frontend.md:66-69, Backend.md:312-313 single-seam statements.
- FE-15 target: kernel.backend/security/session/layout/web/livesync/model/observability + shell +
  <domain>.web/.client/.model; domains incl. "organisation". Guards R1 (no dep on another domain's web),
  R2 (/api/ literals only in client/kernel, ratchet), R3 (WebClient only kernel.backend + SSE relay +
  live-sync probe), R4 (every client bean from webClient bean; MockWebServer test asserting
  Authorization, X-Correlation-Id, X-Active-Org-Unit-Id, Accept-Language, breaker short-circuit), R5 (no
  URI/UriBuilderFactory/@CookieValue params; relative /api/ paths; no @ImportHttpServices), R6 (kernel
  depends on no domain) + G2..G8. Sequence: 0 guards/fixes; 1 kernel extraction; 2 session allow-list
  decision own release; 3 typed clients per domain (small first: audit, leadership, orgchart,
  notification, settings, dashboard, exchange; then big four); 4 package move one PR, snapshot identical;
  5 optional template/JS folders. Move checklist: SessionTypeAllowList:86-87; DTO_PACKAGE in 3 contract
  tests; 172 T(Roles); PUBLIC_BY_DESIGN simple names; TermsDocumentClientUsageTest field name;
  spring.factories -> config.SandboxProfileGuard; frontend/build.gradle.kts:315,512; generator package.
  Spring Modulith not needed for the frontend.
- FE-16 :stable promoted per module by 5 matrix jobs (promote.yml:170-202) and deploy.sh resolves each
  :stable independently (deploy.sh:929-938) -> timer tick during/failed promotion can deploy a MIXED
  release (e.g. backend N+1 + frontend N). Under a per-domain re-cut a mixed set = every call of the domain
  404s. Fix: deploy.sh refuses targets whose app images don't share one org.opencontainers.image.revision
  label (compare labels of cosign-verified digests only), promote with one job verify-all-then-retag like
  the :testing sync. Owner decision; host change only via normal release.

## 81-prev-sept-b (Sept audit, Frontend 30 / CI 29 / Betrieb 21 = 80 + 20 SIB out of scope) — read
- Status: DONE 73 (7 with host-side part not verifiable: APPSEC-05, APPSEC-07, CI-SEC-01, OPS-SEC-01,
  OPS-PRIV-01, OPS-SIMP-03, OPS-SEC-05), PARTIAL 7 (FE-SIMP-02, FE-SIMP-03, FE-MOD-03, CI-03,
  CI-SEC-12, CI-SEC-16, OPS-MON-01), OPEN/REGRESSED 0. SIB: 19 done per vault, SIB-SEC-01 won't-do.
- Verdicts: CONFIRMED 66, ADJUSTED 12, SUPERSEDED-BY-MODULARISATION 1 (FE-SIMP-02 = frontend half of
  the split), REPRIORITISED 1 (OPS-SEC-08 -> P3). Remaining: P1 5, P2 7, P3 2.
- P1: APPSEC-05 (code default still `report` RedisSessionConfig:89 + compose; flip to enforce; package
  hazard for per-domain packages; never widen prefix); CI-03 (PIT backend run 35827836688 cancelled at
  60 min after 13 minion timeouts, gate PASSED on partial mutations.xml 9,481 mutations -> gate on PIT
  completion line, raise timeout; targetClasses service.* re-key; later per-domain shards); CI-SEC-10
  (repo-lint jobs `Self-tests` and `Container checks` not required: promtool unit tests, monitoring
  config validation, keycloak-issuer, edge nginx check, runtime self-tests -> a PR can merge with a
  broken alert rule; owner's repo-setting write); OPS-MON-01 (ContainerRestartLoop podman series not in
  REQUIRED_CONTAINER_SERIES nor absent() guarded); OPS-SEC-03 (backup helper falls back to unpinned
  postgres:18-alpine while mounting keystore/internal TLS/Redis ACL -> fail closed).
- P2: FE-MOD-03 (44 of 100 scripts @ts-check; 3 largest unchecked: mission-detail.js 3,249,
  bank.js 2,400, orders-detail.js 2,325; ESLint ecmaVersion 2023 -> per-domain ratchet);
  FE-PERF-01 -> PSB-03 (/api/v1/me/layout: MeController:104-115 computes blueprint/job-order/bank/
  inventory capability flags from OwnerScopeService/AuthHelperService/InventoryProperties -> shell module
  composing per-domain capability queries); FE-SIMP-02 -> PSB-01 (move failure semantics into an
  ExchangeFilterFunction or reusable adapter; one HttpServiceProxyFactory from WebClientAdapter.create(
  webClient); per-domain interfaces; keep getCached in a catalogue facade; ArchUnit; amend ADR-0032 +
  arc42 §4.1); FE-SIMP-03 (mission-detail.js 7 German literal fallbacks + 2 console messages invisible to
  I18nDictionaryCoverageTest; err.message in 2 toasts); CI-01 (Actions cache 9.39 of 10 GiB: 17
  gradle-dependencies entries 5,535 MiB, CodeQL Code Quality 1,199 MiB); CI-SEC-12 (PyYAML, ansible,
  ansible collections ranges, npx markdownlint-cli2 unhashed); CI-SEC-16 (release App key not confined
  to main-only environment). P3: CI-07 (input derivation before any Gradle split), OPS-SEC-08.
- PSB-05 CI structures keyed on 6 modules/flat folders: Dockerfile, image_reuse_plan.py, SBOM gate,
  sandbox path filter, Flyway check, PIT; two frontend tests list files NON-RECURSIVELY
  (I18nDictionaryCoverageTest:47,70 static/js; TemplateCommentHygieneTest:124 pages dir) -> per-domain
  folders silently narrow them -> Files.walk.
- PSB-15 frontend JS kernel: krtFetch (68 scripts), krtModal 35, krtEvents 32, krtLiveSync 29,
  krtI18nText 27 (defined in krt-client-error.js -> move out), escapeHtml/escapeAttr 10; domain globals
  stay in domain; one cross-domain UI link krtMaterialRelease (Materialbörse used by Lager page). Declare
  kernel (static/js/kernel/), ESLint per-folder restriction.
- DRIFT: vault Testing.md:101-109 (CI-03 "fixed"), Frontend.md:1478-1484 (no literal defaults - wrong),
  Improvement Audit :117 (CI-01 guard not met), arc42 04-solution-strategy.md:9 ("exactly one class"),
  monitoring/prometheus/alerts/infrastructure.yml:78 (ContainerMetricsMissing text wrong).

## 20-crosscutting (XC-01..24) — read fully
- XC-01 scope hub: backend 431 @PreAuthorize (378 method, 53 class) on 98 @RestController + 1 @Controller
  + 7 services, 72 distinct expressions; 166 SpEL bean refs to 8 beans: ownerScopeService 66 (18 methods;
  joborder 31, inventory 9, operation 8, refinery 8, mission 7, hangar 2, blueprint 1),
  missionSecurityService 40, authHelperService 17, exchangeGate 14, orgRoleManagementSecurityService 13,
  bankSecurityService 10, specialCommandSecurityService 5, connectedAppsGate 1. (MB counted 113 in
  string literals only — XC is the authoritative javap count.) OwnerScopeService 55 public names over 9
  domains, injected by 34 classes in 14 domains; AccessGateService holds 9 repositories of 7 domains
  (Mission, JobOrder, JobOrderHandover, JobOrderItemHandover, InventoryItem, RefineryOrder, Operation,
  Ship, OrgUnitMembership) -> THE main cycle-maker. AuthHelperService used by 46 classes in 17 domains.
  Proposal: kernel (AuthHelperService, Roles, Permissions, RequestScopeResolver, ScopePredicate,
  OrgUnitCascadeService, OrgUnitStampingService — RequestScopeResolver already has only orgunit+security
  out-edges) + one explicitly named access-policy bean per scoped domain (missionAccess, jobOrderAccess,
  inventoryAccess, refineryAccess, shipAccess, operationAccess, promotionGate, blueprintOverviewAccess);
  transition: name ownerScopeService explicitly -> policy beans as delegates -> re-point SpEL one domain
  per PR -> move bodies -> delete facade methods. Differential verdict-parity test per domain over a
  fixture matrix (admin pinned/unpinned, 0/1/2 units, Bereich cascade, owner escape, ownerless personal
  row, operation participant escape, JobOrder SK/requester escapes, SK lead). Each policy owns its JPQL
  fragment AND per-row predicate. Amend ADR-0065 + new ADR.
- XC-02 SpEL bean names default to class name for 6 of 8 beans; rename/move -> Spring Security 7.1.1
  ExpressionUtils wraps EvaluationException in IllegalArgumentException -> GlobalExceptionHandler:594 ->
  HTTP 400 ILLEGAL_ARGUMENT on every gated endpoint: fail-closed but NO 5xx/ERROR alert fires. Fix FIRST:
  explicit bean names + PreAuthorizeBeanReferenceTest (enumerate handler + annotated methods, parse
  @name.method(, assert containsBean + public method with that arity, ratchet >= 166). Optional: map
  SpEL evaluation failure to 500 + ERROR (owner decision; behaviour change).
- XC-03 ScopeSpecifications: 6 package-private static final JPQL fragments spliced 29x into 7 repos
  (InventoryItem 12, Ship 5, RefineryOrder 4, Operation 3, Mission 2, JobOrder 2, MaterialExchangeOffer
  1 reusing inventory triple); javac constant folding -> 0 jdeps edges (invisible). Operation fragment
  embeds MissionParticipant; JobOrder fragment embeds TYPE(...)=SpecialCommand. Per-domain public
  ...ScopeQueries holders; list/gate agreement integration test per aggregate (only
  JobOrderScopeQueryIntegrationTest exists); test that every @Query on a tenant-scoped root binds
  :isAdminAllScope or :activeOrgUnitId or carries @Unscoped("reason"). REQ-DATA-010 wording.
- XC-04 bank seam rules keyed on FQN of OwnerScopeService + Bank* prefix -> vacuous once XC-01 lands or a
  bank class drops the prefix -> re-key BEFORE (module membership/@BankModule marker; kernel classes via
  class literals; bridge set exactly {OrgUnitBankAccessService}; size assertion). ADR-0020 amendment =
  OWNER APPROVAL.
- XC-05 audit: AuditService.record 206 calls in 57 classes, BankAuditService.record 51 in 14, both
  MANDATORY; AuditEventType 202 constants over 12 AuditDomains (MISSION 35, INVENTORY 30, JOB_ORDER 23,
  ROLE 22, PROMOTION 17, CONNECTED_APPS 15, MARKET 14, BLUEPRINT 13, REFINERY 12, HANGAR 9,
  PERSONAL_INVENTORY 6, OPERATION 6); fan-in 63; names persisted (audit_event.event_type, not CHECKed),
  metric label domain, alert exemptions, frontend pins, PDF keys. KEEP direct in-tx call; move enum etc.
  into audit.api (AuditRecorder interface, BankAuditRecorder, AuditEventType, AuditDomain, AuditDetails);
  do NOT make audit async/after-commit. Cross-domain writes (orgunit->ROLE 19, identity->ROLE 12,
  catalogue->REFINERY 5, joborder->INVENTORY 4, hangar->MISSION 1 e.g. HangarService.deleteShip records
  MISSION_UNIT_UPDATED) -> call the owning domain's API which records its own event. Guard: ArchUnit
  ratchet (FreezingArchRule) that mutating public methods writing an audited repo reach record().
- XC-06 events: 22 publishEvent sites in 9 classes (UserReconciliationService 6, BankBookingRequestService 4,
  DeletionRequestService 4, JobOrderService 2, UserRegistrationService 2, MaterialExchangeService 1,
  MaterialRequestService 1, ExchangeInstallationService 1, ExchangeBulkUndoStep 1); 16 event records in
  central `event` pkg; 14 implement NotificationEvent SPI. Listeners: NotificationEventListener @Async +
  AFTER_COMMIT; 2 mail listeners @Async + AFTER_COMMIT; ExchangeDepartureService sync AFTER_COMMIT
  fallbackExecution + REQUIRES_NEW writes. 3 hand-rolled TransactionSynchronization afterCommit hooks
  (ExchangeLiveSync, TermsAcceptanceService, ExchangeJournalService). HIDDEN CYCLE bank<->notification:
  RecipientResolutionService reads BankAccountGrantRepository + OrgUnitBankResponsibilityService ->
  RecipientDirectory port implemented by bank. PII in event payloads: UserApprovalDecidedEvent(email,
  name, reason), AccountDeletionRequestedEvent(handle), DiscordRegistrationPendingEvent(username),
  JobOrderCreatedEvent(handle, often outsider). SPRING MODULITH 2.1.1 (compiles vs Boot 4.1.1/SF 7.0.9;
  depends on ArchUnit 1.4.2 vs our 1.5.1 -> compat UNKNOWN, spike) JpaEventPublication stores
  serializedEvent + eventType class in EVENT_PUBLICATION -> NEW PII STORE + class name persisted (moves =
  data migration). @ApplicationModuleListener = @Async + @Transactional(REQUIRES_NEW) +
  @TransactionalEventListener. -> before any durable registry: id-only payloads; resolve email at send.
- XC-07 synchronous cross-domain reactions MUST stay in-tx: MaterialExchangeOfferRatchet (called from
  ExchangeStockWriteService, InventoryCheckoutService, JobOrderHandoverService, JobOrderItemHandoverService,
  JobOrderItemProductionService, UserDeletionService; offer clamp + audit in same tx, PR #2162),
  InventoryOrgUnitReconciler (from OrgUnitMembershipService), OrgUnitBankResponsibilityService (from
  OrgUnitMembershipService, RecipientResolutionService, UserDeletionService), MasterDataCacheEviction.
  -> published APIs of the owning domain (materialexchange.api.OfferStockGuard, inventory.api.
  OrgUnitRestamp, bank.api.ResponsibilityCleanup), MANDATORY; never AFTER_COMMIT.
- XC-08 live sync: backend LiveSyncTopicClass 13, frontend 17 (7 hardcoded probe paths),
  LiveSyncSubscriptionAuthorizer switches into ownerScopeService/OrgUnitBankAccessService/authHelper;
  only backend publisher ExchangeLiveSync; frontend LiveSyncLocalBus 24 publish sites. -> livesync.api
  LiveSyncPublisher.afterCommit + per-domain LiveSyncTopicAuthorizer SPI; keep fail-closed rule
  (presenceEnabled ? DENY : ALLOW; only MISSION presence-enabled).
- XC-09 exchange: 14 foreign repositories + 22 foreign services; ExchangeStockWriteService:798-818 saves
  InventoryItem + INVENTORY audit itself; lot-lock queries on InventoryItemRepository (ADR-0229); ArchUnit
  only on controller side (predicate contains(".backend.service") -> would miss backend.inventory.service).
  Per-domain APIs the exchange needs: hangar OwnShips, inventory PersonalStock (lockLots in ADR-0229
  order, calls offer guard), blueprint OwnBlueprints, joborder DemandQuery, catalogue CatalogueLookup,
  refinery RefineryDraft, identity MemberStatus, notification MarkInstallationSeen; acting member always
  from the exchange caller. L-XL; contract frozen; REQ-XCH-009 amendment; sequencing after go-live.
- XC-10 GDPR: registries DataExportSections 36, PersonSearchTargets 80 entries/19 areas/39 tables,
  HandleErasureCoverage 79 keys/42 tables, UserAccountMergeService native SQL -> keep central in
  identity.privacy; UserDeletionService depends on 15 repos of 10 domains -> MemberDataEraser SPI
  (eraseOrDetach(userId) in orchestrator's order inside one tx); guard: every FK to app_user (53) owned by
  exactly one eraser. Keep 7 schema sweeps (PersonSearchCoverageTest etc.).
- XC-11 15 @Scheduled (11 in task pkg); ScheduledJob 14 values = alert label vocabulary;
  BusinessMetricsCollector polls 9 repos of 7 domains; MetricNames 182 constants/59 dependents; no
  @Timed/@Observed/@Counted; no rule uses code_namespace. -> jobs + MeterBinder per module, names identical;
  context test asserting ScheduledTaskHolder set.
- XC-12 18 service-level @PreAuthorize in 7 classes (promotion services, MemberEvaluationService,
  PromotionEligibilityService, MissionFinanceEntryService.updateEntry/deleteEntry) -> self-invocation
  bypass on merge/split -> move to controllers or ArchUnit "no call to own @PreAuthorize method".
- XC-13 ArchUnit: backend 43 rules, frontend 7, ingest 4; 47 layer-package patterns, 4 contains filters,
  35 FQN literals, 8 simple names, 2 simple-name sets. No archunit.properties; ArchUnit 1.5.1
  failOnEmptyShould default TRUE (bytecode) but only catches fully empty selection; 2 rules
  allowEmptyShould(true) (:718, :1693). Under package-by-domain: 20 can turn VACUOUS, 14 WEAKER, 1 changes
  MEANING (slices backend.(*)), 8 robust; 18 of the 34 are security rules (appendix F table with guard
  per rule). Examples: permitAllIsDeclaredOnlyOnTheFourPublicEndpoints (contains(".backend.controller"), no
  floor), controllerLayerShouldNotDependOnRepositoryLayer, cascadeService..., delegatedRoleAuthoriser...;
  peerReadable... floor 10 vs ~23 selected today. Proposal BEFORE any move: select by role annotation
  (@RestController, @Service, Repository assignable, @Mapper, @Entity), class literals instead of FQN
  strings, selection ratchets (size >= today), replace allowEmptyShould with floors, add
  backend.(*).(*).. slices rule, FreezingArchRule only for baselining new rules (never security); prove
  each rewritten rule can fail (planted violator fixture).
- XC-14 simple-name whitelists: staffelScopedWriteEndpointsMustGateOnOwnerScopeService (7 controllers,
  SpEL substring), staffelScopedServicesMustWireOwnerScopeOrAuthHelper (12 services) -> a re-cut/split
  controller (e.g. MissionCrewController) is NOT selected -> {id} write without scope gate passes ->
  cross-Staffel write. -> @TenantScoped marker on aggregates + select by module; accept registered policy
  bean names; test every entity with owningOrgUnit/responsibleOrgUnit carries @TenantScoped.
- XC-15 invisible coupling (jdeps/ArchUnit/Modulith blind): 29 JPQL splices; 166 SpEL refs; 93 FQCNs in
  68 @Query (47 constructor expressions, 42 enum literals); 18 cross-domain JPQL entity names; 29 native
  queries; 172 T(); GDPR registries 36+80+79; 4 change-feed + 6 guard triggers; Roles constants 339 refs
  vs 20 jdeps edges. A Modulith-only gate = green check that checks nothing -> pair verifier with targeted
  tests; document in the module ADR.
- XC-16/17 session allow-list + T() (same as FE-08/FE-09): 28 flash sites with forms.
- XC-18 build keys: PIT service.* (silent partial loss), JaCoCo project.name, SpotBugs excludes (loud),
  spring.factories SandboxProfileGuard (loud), frontend contract tests keyed on frontend.model.dto (silent
  above floor).
- XC-19 JPQL FQCNs loud but expensive -> interface projections, bind enums as params.
- XC-20 monitoring: 0 selectors on Java names; enum names are the contract (ScheduledJob task, AuditDomain
  domain, LiveSyncTopicClass topic_class, NotificationEventType) -> parity test alert label values subset
  of enum values.
- XC-21 DB: 117 tables, 195 FKs, 105 cross heuristic domains: app_user 46 (53 incl identity), org_unit 22,
  catalogue 29, business->business only 8 (refinery_order.mission_id, mission.operation_id,
  mission_unit.ship_id, job_order_item.blueprint_id, material_exchange_offer.inventory_item_id,
  inventory_item_job_order_allocation.job_order_id, inventory_item_mission_allocation.mission_id,
  exchange_ship_link.ship_id). 18 triggers: 4 V252 change-feed (personal_blueprint, default_blueprint,
  inventory_item, ship) -> column rename breaks inserts at RUNTIME only (plpgsql). 183 JPA association
  fields, 96 crossing domains; 56 of 101 repositories used from another domain (230 foreign user classes);
  InventoryItemRepository used by 14 foreign classes. -> schema ownership map (table -> module) in
  data-persistence spec + test; keep FKs; trigger-column test.
- XC-22 API re-cut: 20 path-keyed controls; SILENT + security: (1) CSRF_EXEMPT_PATHS {"/api/v1/**",
  "/internal/**"} SecurityConfig:111 (test profile disables CSRF :315-317 -> no test sees it; /api/v2 would
  403 in prod); (2) NoStoreApiScopes 14 patterns (REQ-SEC-031 silently violated); (3) URL-matrix prefix
  gates SecurityConfig:364-440 (/api/v1/admin/**, /bank/admin/**, /audit/** ADMIN, /users/** ADMIN
  catch-all, /inventory/** roles, /hangar/** authorities) -> layer 2 lost; (4) EDGE allow-list IS IN GIT:
  docker/edge/include/api-allowlist.conf (172 allow rules) + read-only-family regex (16 families) -> 405
  for non-GET unless excepted -> a write moved into an allow-listed read family passes edge silently;
  (5) pending/terms allow-lists (PendingApprovalAccessFilter:79,86; TermsAcceptanceAccessFilter:85-88
  /api/v1/terms/**) -> new endpoint under /terms reachable unconsented; (6) SubjectRateLimitingFilter:78-95
  export segments (export, export.json, statement, report, pdf, three-month-report) + stream paths;
  (7) everything outside /api/** loses pending/terms/subject limiter/cache-control/API metrics/ETag;
  (8) SSE special cases; (9) ExternalContractTest pins 235 operations in 30 families (ADR-0136 requires
  /api/v2 + deprecation + sunset) -> CONFLICTS WITH OWNER'S HARD-CUT DECISION -> ADR-0136 amendment needed;
  (10) frontend 259 distinct /api/v1 literals in 87 files, 7 probe paths, TermsAcceptanceGateFilter fails
  open (backend enforces), E2E 63 paths/38 files; (11) exchange relay routes frozen; (12) monitoring: one
  API probe prometheus.yml:136 /api/v1/terms/status, edge-deny-probe 151 rows synced by
  check_probe_against_allowlist.py. Guards (a)-(g) before first re-cut. "Stay under /api/v1/ where
  possible."
- XC-23 option B hazards: modules outside root package lose non-injected beans silently; Modulith core
  2.1.1 depends on ArchUnit 1.4.2 vs 1.5.1; context-shape ratchet test (15 @Scheduled, 4 tx listeners,
  99 controllers...).
- XC-24 previous audits re-eval (cross-cutting): ADR-0047 follow-up (frontend/ingest/SPI not cycle-gated)
  STILL OPEN -> add frontend slices rule before frontend packaging (NOTE: FE agent says don't demand
  acyclicity between frontend domains — reconcile: slices cycle rule on kernel vs domains, allow
  domain<->domain composition? decide).
- VAULT DRIFT (appendix K): Request Authorization.md Layer 3 says 88 @RestController / 440 @PreAuthorize ->
  code 431 on 98 @RestController + 1 @Controller + 7 services (18 service-level gates unmentioned);
  "some forty-five distinct @ownerScopeService.* predicates" -> 18 distinct methods referenced (55 public
  names); Security.md § Invariants lacks the package-keyed caveat.

## 40-frontend-assets (FEA-01..15) — read
- FEA-01 assets by page/audience: static/js flat (100 files), css root 10 + pages/ 54, templates root +
  admin/ 22 (10 domains) + fragments/ 30 flat; maps cleanly: 19 core + 81 domain scripts in 21 domains;
  cross-domain couplings few: 1 JS API (inventory-materialboerse.js -> window.krtMaterialRelease),
  6 stylesheet links (audit-log links bank.css; admin/materials links promotion-admin.css; 3 blueprint
  pages link personal-inventory.css), 1 inverted core->page call (common-handlers.js -> window.filterTable
  defined 3x), krt-bank-account-search.js loaded on EVERY page via head.html:144, admin/mission-data
  mixes orgunit/job types/frequency types. Proposal: static/js/core/, static/js/<domain>/,
  static/css/<domain>/, templates/<domain>/; AssetDomainBoundaryTest (JUnit text test). Cost: 121 th:src,
  ~74 stylesheet links, ~164 view names, ~102 fragment selectors, 93 asset paths in 25 test classes,
  path-based lint config. ADR "assets organised by domain".
- FEA-02 classic global scope = module system: 50 non-IIFE files declare 523 top-level names (443 fns);
  23 names declared in 2-3 files; 276 /* global */ imports; 213 bootstrap constants; 185 typeof window.X,
  133 krtFetch presence guards. Stage 1: IIFE + one namespace per domain API + ESLint no-implicit-globals
  ratchet; ADR-0069 "no IIFE" amendment.
- FEA-03 duplication: JS 7.7% (refinery create<->details 112, materialboerse-release<->gesuch 85,
  item<->material collection 84/98), CSS 15.5% (mission<->operation detail 169, 5 error sheets),
  templates 20% (inventory-stack-entries.html 608 of 688 duplicated; inventory-admin<->my 194; header 83x);
  three pickers (krt-searchable-select, legacy autocomplete.js used by 6 pages, bespoke mission
  participant search) -> consolidate; duplication ratchet test.
- FEA-04 modern JS: 0 var, but 3,007 function forms vs 305 arrows, 664 concatenations vs 64 template
  literals, 14 ?. vs 245 a&&a.b, 43 ?? , 0 classes/modules, 0 ES2022+ APIs, 190 .then vs 87 await, 52
  parseInt without radix, 57 global isNaN. ESLint core autofix rules (prefer-template,
  prefer-arrow-callback, prefer-object-has-own, radix, logical-assignment-operators) — zero new deps;
  ?./?? by hand (semantic traps).
- FEA-05 NO browser-support baseline documented anywhere (implicit: ESLint ecmaVersion 2023, tsconfig
  ES2023, @layer/:has/range MQ, <dialog>); installable web app = iPhone/iPad client -> state baseline
  (REQ-FE) first.
- FEA-06 @ts-check 44/100 files but 37.8% of lines; mission 0%, bank 2%, promotion 9%, joborder 14%;
  noImplicitAny false; KrtLiveSyncApi.createReceiver untyped; REQ-FE-018 + TYPESCRIPT_MIGRATION_PLAN stale
  (and its "type=module changes execution order" objection is moot since 120/121 scripts defer).
- FEA-07 ADR-0069 unfinished: 70 inline script blocks in 60 templates (1,860 lines, 142 var, never
  linted); 17 templates keep logic (5 bank pages, members, member-edit, mission-detail...);
  members.html:215-225 11 const MSG_... = "[[#{...}]]" WITHOUT th:inline -> likely i18n defect / wrong
  escaping context (verify with MockMvc render). krtScuInput stub duplicated in head.html. Vault
  Frontend.md:1010-1015 wrong. Optional JSON data islands.
- FEA-08 CSS: 64/64 layered; :has 29, :where 18, range MQ 73/74; unused: nesting, container queries,
  @scope, color-mix, clamp, @property, view transitions, subgrid, popover; logical props 10 vs 1,611
  physical (no RTL). Page sheets (54 files, 5,208 lines) excluded from stylelint-config-standard (only 2
  rules). BUGS: var(--color-black) undefined at styles.css:2999,3006 (token is --color-bg-black) ->
  sticky .demand-material-search header transparent; var(--color-text) undefined bank.css:1240. 6 unused
  tokens, 8 dead krtm-* classes, 45 z-index declarations 32 values. -> CustomPropertyExistenceTest.
- FEA-09 no layout fragment: head 90x, sidebar 83x, header copied 83x in 5 variants (~1,000 lines);
  native Thymeleaf layout fragment (no Layout Dialect); enable 3 HTMLHint rules; 37 <button> without type.
- FEA-10 security: CSP strong (nonce + strict-dynamic, style-src-attr none), 0 third-party browser JS,
  npm ci + ignore-scripts. GAPS: 56 raw GET fetch in 32 files: only 3 honour X-Reauthenticate, >=15 trust
  res.ok after redirect (defect shape HandRolledFetchGateContractTest pins for 2 modules only), ~35 send
  no X-Requested-With (terms gate isAjax) -> krtFetch.get/getJson (reauth + terms gate + refuse
  redirected/non-JSON + AbortController), then forbid fetch( outside krt-fetch.js + ban XHR; redirect
  helpers reauthRedirect/termsGateRedirect accept //host and /\host (safe-url.js rejects both) + 4
  unchecked targetUrl navigations (inventory-input.js:484, orders-create.js:525,
  refinery-orders-create.js:406, refinery-orders-details.js:754) -> safeSameOriginUrl (defence in depth;
  script-src nonce blocks javascript:); CSP: unused style nonce, font-src data: unneeded -> style-src
  'self', font-src 'self', base-uri 'none'; CSV FORMULA INJECTION pmCsvEscape
  (promotion-manage.js:505-517) doesn't neutralise leading = + - @ TAB CR (cells carry member usernames).
- FEA-11 Trusted Types feasible now, no deps: 72 innerHTML (23 clears, 6 static, 22 escaped builders, 21
  local markup vars), 0 eval/Function/string timers/script sinks; two policies krt-fragment + krt-html
  (tagged template krtHtml); report-only first via existing csp_violation beacon + ClientErrorSpike;
  E2E listener in DialogA11yE2eTest page walk. ADR + REQ-FE-022 + CSP requirement amendment.
- FEA-12 ES modules only behind a server-rendered import map (unversioned asset URLs cached immutable 1
  year -> relative imports would pin stale code); after FEA-02 + FEA-07; NO bundler (supply-chain:
  compromised bundler = site-wide XSS the nonce CSP can't stop; served != checked breaks ADR-0125).
- FEA-13 lint gaps: no-unused-vars only warn, no --max-warnings 0; scripts/**/*.mjs not in Gradle lint
  tasks (gen-api-types.mjs ungated); tsconfig.json 17-line "//" prose field (comment in all but syntax);
  ADR-0212:79-80 instructs comments (contradicts ADR-0214); CspNonceFilter Javadoc names wrong class.
- FEA-14 event delegation: 237 document listeners, 291 data-trigger values; 3 window.onclick backdrop
  handlers overwrite each other.
- FEA-15 DRIFT: REQ-FE-018 (40/96 -> 44/100), TYPESCRIPT_MIGRATION_PLAN numbers + execution-order argument,
  vault Frontend.md:1010-1015, ADR-0069:41-42 no-IIFE, ADR-0212:79-80, ADR-0130:56, CspNonceFilter.java:37.
- Suggested order: FEA-13 -> FEA-08(1-3) -> FEA-10(2-4) -> FEA-07(1-4) -> FEA-10(1) -> FEA-02 stage 1 ->
  FEA-06 -> FEA-01+03+09 -> FEA-11 -> (own ADR) FEA-12.

## 82-prev-july-other (July audit + focused audits) — read
- July report NEVER committed; reconstructed from PR #1256 (26 commits), issues #1250-#1255, PRs
  #1257-#1262, vault. Items beyond QW1-QW4, #10, #14/Thema 7, #15, #16/Thema 11 UNKNOWN.
- Every July finding shipped and holds, incl. all 9 Thema-7 splits (OrgUnitMembershipQueryService,
  JobOrderQueryService, OrgChartReadService, SquadronContextAdvice split, OperationPayoutService +
  Calculator, UserDeletion/Registration/Reconciliation, BankPostingWriter + BankBookingGuards,
  OrgUnitBankResponsibilityService, MaterialExchangeBoardService). Never happened: CachedCatalogListLoader
  has 1 consumer (28 getCached sites in 10 controllers), HangarPageModelLoader never built,
  GlobalExceptionHandler still one class (1005 lines, 20 handlers) -> split by exception FAMILY, never per
  domain. J-DIAG "sound layers, debt = size" SUPERSEDED: files > 600 lines 49/1477 -> 53/2044.
- Rejections: 14 of 15 HOLD (Mission section counters; WithinTransaction/MANDATORY; REQUIRES_NEW retry
  intra-class 17 methods/9 self-providers; bulk-after-loop; no CRUD base template; explicit redaction
  constructor; ADR-0020 seam (modules STRENGTHEN it); coarser locks; @PreAuthorize meta-annotations;
  OrgUnitBankSettingsAssembler; parity for other twins; ScWiki sweeps; UEX flag semantics; fee
  resolution). REVISIT: J-R05 BackendApiClient split (holds only for per-domain WebClient beans or
  resilience instances; not for facades/@HttpExchange on the one webClient bean).
- 21 cross-domain MANDATORY hops (31 MANDATORY methods in 15 classes): exchange->inventory
  (bookOutForClient, mergeStockIfRequested), joborder->inventory, orgunit->inventory
  (InventoryOrgUnitReconciler x2), inventory/joborder->materialexchange (OfferRatchet lower/beforeDelete
  x4), inventory->materialexchange (beforeWipe), identity->materialexchange (beforeUserPurge),
  orgunit->orgchart (OrgChartService.mirror* x6, mirror*KommandoGroup x3), identity->bank audit (1).
  -> first in-transaction module ports; synchronous in-tx events (listener @Transactional(MANDATORY), NOT
  @TransactionalEventListener, NOT @Async) or published in-tx APIs; never after-commit.
- ArchUnit (independent count): 25 SILENT + 6 PARTIAL + 8 LOUD + 3 SAFE + 1 STRONGER of 43 (XC: 20 V +
  14 W + 1 S + 8 L). Key insight: ArchUnit fails an empty SELECTION but NOT a rule whose TARGET is an FQN
  string that no longer exists -> passes silently. supportPackageMustStayADependencyLeaf is a DENY-list of
  layer packages -> a new ..backend.mission.. package is not on it -> support could depend on it.
- Security items still OPEN: page-size ceiling 100000 (PaginationUtil.java:45, #783; module-local caps
  500/200 drift) -> shared page policy; blueprint search LIKE unescaped (BlueprintProductService:86,
  BlueprintRepository:125-126,219; #1672; low); REQ-SEC-031 no-store only 14 families — unclassified
  /api/v1/admin (12 controllers incl. audit log), /exchange (8), /orders (4), /material-exchange,
  /material-requests, /leitung, /org-chart -> completeness test (owner decision + REQ amendment);
  per-subject GET budget absent (accepted risk); Keycloak hardening steps 2, 11 (OTP for admin — most
  important), 12 open; S150-L4 ConstraintViolation message echo (accepted; guard test forbidding
  ${validatedValue}); S393-H1 UserDto still has email component (mapper ignores) -> split self vs peer DTO.
- CACHING L4 invariant (#1002): cached catalogue mutators safe only because they self-invoke their own
  @Cacheable getter (bypasses cache via AOP self-invocation); protection was a code COMMENT removed by
  ADR-0214; no test; e.g. CityService.java:69 @Cacheable getCity returns entity, :84 self-invokes in
  mutator; 33 @Cacheable in 16 services -> query/command split would make the mutator edit the CACHED
  instance; failed write leaves it in cache. HIGH PRIORITY before any catalogue restructuring: interim test
  + REQ-DATA-007; target cache DTOs/ids not entities + ArchUnit rule no @Cacheable returning @Entity.
- Other: QUANTITY_EPSILON 1e-4 duplicated 4x; Error Prone/NullAway never adopted (A-5.1) -> frameworks
  evaluation; PERF-FORKS WebClientResilienceTest real 400 ms limiter (virtual time) prerequisite if tests
  parallelise; presence heartbeat 60s/TTL 120s lockstep without parity test (mission-presence.js:4 vs
  LiveSyncPresenceService:58) + Javadoc names wrong file; D1981-TERMS terms mention guests (owner,
  re-consent ADR-0127); OperationPayoutService scoped but NOT in whitelist (AT:980-992).
- PRV-01..14 proposals (PRV-01 re-key ArchUnit blocking; PRV-02 SpEL resolution test; PRV-03 MANDATORY
  hops as ports; PRV-04 cache invariant; PRV-05 context snapshot; PRV-06 no-store completeness; PRV-07
  frontend facades; PRV-08 page policy; PRV-09 split support; PRV-10 PII unrepresentable (reflective
  nested redaction test, UserDto without email for peers); PRV-11 audit-completeness guard per module
  (replaces rejected base template); PRV-12 module-scoped scope rule; PRV-13 doc drift; PRV-14 leftovers).
- DRIFT: arc42 04:9 + vault Frontend.md:67 ("exactly one class"); vault Bank.md:639 (921 LOC -> 794),
  :681 (~2030 -> 1753); LiveSyncPresenceService.java:54-56 Javadoc wrong file; ArchUnit messages asking
  for "a code comment" (AT:1458, 1521-1522, 1747-1748) contradict ADR-0214.

## 10-backend-domains (DOM-01..21) — read (core facts)
- Scope: 1389 top-level types, 174,597 LOC (87,227 non-comment non-blank); 5696 class edges (folded);
  2232 cross-category; 1565 between non-kernel/non-infra domains. 1383 of 1389 types public.
- DOM-01 domain graph = ONE SCC of 21 of 23 non-kernel categories (only admin, dashboard outside);
  41 two-way pairs; Eades ordering still needs 211 back edges in 52 pairs. Cycle gate slices layers only.
  206 cross-domain service->repository edges. Proposal: ArchUnit 1.5.1 `modules()` rules
  (ModuleRuleDefinition.modules().definedBy(...)/definedByPackages/definedByAnnotation;
  ModulesShould.respectTheirAllowedDependencies, onlyDependOnEachOtherThroughClassesThat, beFreeOfCycles
  — verified via javap in cached jar) reading a checked-in domain map + FreezingArchRule baseline that
  may only shrink (today 150 violations) + "every class belongs to exactly one module" rule. Needs new
  ADR amending ADR-0047 (which rejected freezing for layer cycles) — owner approval.
- DOM-02 taxonomy refinements: leadership merges into orgunit (0 entities); access splits into
  access-core (AuthHelperService, AuthenticatedSubject, SubjectAuthentication, Roles, Permissions,
  OrgUnitContextualAuthority + 2 props) and scope (RequestScopeResolver, ScopePredicate,
  OrgUnitStampingService, OwnerScopeService, AccessGateService, ScopeSpecifications,
  CustomJwtGrantedAuthoritiesConverter, OwnerOrgUnitRequiredException); identity hides a `privacy` layer
  (31 of 101 classes); recipe graph (model.scwiki.Blueprint + 7 children) is catalogue not blueprint;
  platform services (audit, notification, livesync) below business; composition root `app`
  (SecurityConfig, DataInitializer, BusinessMetricsCollector, BackendApplication). 25 target modules:
  platform: shared-kernel, platform (infra + access-core), app; platform services: audit, notification,
  livesync; foundation: catalogue, identity, orgunit(+leadership), scope; business: admin, dashboard,
  orgchart, promotion, personalinventory, hangar, blueprint, inventory, mission, refinery, joborder,
  materialexchange, operation, bank; adapter: exchange. (privacy dissolves into identity-owned SPIs.)
- Module inventory (classes/LOC/endpoints/entities/Ce->/Ca<-): catalogue 263/28952/90/36/15->6/337<-10;
  exchange 169/19185/35/10/197->13/11<-6; bank 129/17667/65/11/104->6/18<-5; identity 101/14642/59/5/
  130->15/204<-17; joborder 95/11761/39/10/263->9/39<-5; mission 81/10089/53/9/131->8/56<-7; inventory
  54/9168/27/3/156->9/70<-8; blueprint 59/6951/24/3/71->9/37<-5; orgunit 56/6855/38/8/27->8/279<-16;
  materialexchange 34/5001/21/4/87->8/11<-4; promotion 41/4717/34/5/39->4/1<-1; notification 44/4321/13/3
  /10->4/70<-5; access 16/3798/0/0/52->9/126<-18; refinery 26/3497/14/2/99->9/23<-7; operation 22/2631/12/
  2/56->7/11<-2; hangar 20/2578/15/1/56->7/29<-5; orgchart 19/2425/5/1/21->3/2<-1; audit 15/2377/4/1/6->3/
  227<-15; livesync 13/2002/2/0/4->2/3<-1; personalinventory 12/1172/10/1/8->2/1<-1; leadership 12/1039/3/
  0/33->3/6<-2; admin 12/623/6/1/0/4<-3; dashboard 6/364/4/1/0/0; infrastructure 71; shared-kernel 19.
- Target layering ranks: 0 shared-kernel, infrastructure, access-core; 1 audit, notification, livesync;
  2 catalogue; 3 identity; 4 orgunit(+leadership); 5 scope; 6 admin, dashboard; 7 orgchart, promotion,
  personalinventory, hangar, blueprint; 8 inventory; 9 mission; 10 refinery, joborder, materialexchange;
  11 operation, bank; 12 exchange; 13 privacy (to dissolve); 14 app. Result: 2088 downward edges OK,
  150 violating edges in 48 pairs: inventory 36, identity 34, scope 17, mission 14, notification 9,
  blueprint 7, catalogue 7, shared-kernel 6 (sealed AppException), infrastructure 4, orgunit 4, hangar 4,
  audit 4, livesync 3, access-core 1. Heaviest: identity->orgunit 21 (UserMapper etc.),
  inventory->joborder 17, inventory->mission 13, mission->operation 9, identity->privacy 7,
  inventory->materialexchange 5, mission->refinery 5, scope->6 business domains 16.
  Heaviest two-way pairs: inventory<->joborder 17/38, mission<->operation 9/22, bank<->identity 27/9,
  identity<->mission 6/31, identity<->joborder 6/35. No single cheap move breaks the SCC.
- DOM-03 AccessGateService 677 LOC, 28 public methods, 11 deps; OwnerScopeService facade 723 LOC, 56
  public methods; per-aggregate policy beans (missionAccessPolicy, jobOrderAccessPolicy,
  inventoryAccessPolicy, refineryAccessPolicy, operationAccessPolicy, shipAccessPolicy) on the scope
  kernel; OwnerScopeService keeps kernel + deprecated one-line delegations so SpEL moves one module per
  PR; JPQL fragment moves next to policy; LiveSyncTopicAuthorizer SPI. Owner approval (endorsed
  security pattern).
- DOM-04 GDPR: UserDeletionService 21 deps, writes 12 foreign repos + ratchet in one @Transactional;
  HandleAnonymisationService @Modifying on bank/joborder/audit tables; UserAccountMergeService 28
  FOLLOWS_THE_MEMBER + 27 STAYS_WITH_THE_ACT table.columns, native UPDATE bypassing @Version; registries
  name tables of up to 18 modules. -> identity-owned SPIs: UserErasureParticipant (ordered phases
  REASSIGN, UNLINK, DELETE), UserReassignmentParticipant (OwnedRows), HandleAnonymisationParticipant,
  PersonalDataExportSection, PersonMentionSource; participants MANDATORY in one outer tx; coverage tests
  compare UNION of registered participants vs information_schema. L-XL.
- DOM-05 inventory no command API: new InventoryItem() in ExchangeStockWriteService:799 (+ records
  INVENTORY_ITEM_CREATED itself :811), JobOrderItemProductionService:383, RefineryOrderService:631;
  foreign PESSIMISTIC_WRITE: JobOrderHandoverService:162, JobOrderItemHandoverService:237,
  JobOrderItemProductionService:205, ExchangeStockWriteService:521,527; advisory lot lock query in
  inventory, protocol in exchange; foreign @Modifying allocation deletes JobOrderService (6 sites).
  InventoryItemRepository 1188 LOC used by 6 domains. -> inventory.api.StockCommands (all MANDATORY):
  bookIn(source in {REFINERY, PRODUCTION, EXCHANGE, MANUAL}, owner, lot, amount), consume(..., reason),
  lockRowsForUpdate, releaseEarmarks, PersonalStockLots.lock(member, lotKeys) owning ADR-0229; each
  records its audit event + owner/scope check. Preserve lock order, bulk-after-loop, FORCE_INCREMENT echo.
  ArchUnit "only inventory writes InventoryItem". ADR-0229 amendment.
- DOM-06 earmarks: (targetKind, targetId) + EarmarkTargetPolicy SPI in inventory implemented by joborder,
  mission; job-order stock panels assembled in joborder; StockSoldForTarget observer by mission; ungated
  order-linked query (findByJobOrderIdOrdered, REQ-ORDERS-029) callable only from joborder.
- DOM-07 mission cycles: direction operation->mission, refinery->mission, mission->hangar; mission keeps
  operation_id UUID + OperationSummaryProvider SPI; MissionFinanceContributor SPI by refinery; ship
  deletion & job-type designation as synchronous observers; operation deletion calls
  mission.detachFromOperation. Foreign writes today don't bump section counters — keep verbatim or ADR.
  Provider summaries must pass MissionPeerRedactor.
- DOM-08 write paths: 363 sites = 205 AuditService.record + 149 others in 57 caller methods (62 foreign
  service writes, 43 foreign repo writes, 37 foreign entity mutations, 7 foreign locks). 16 families: 12
  need caller's tx (audit; joborder/refinery/exchange->inventory; ->offer ratchet; orgunit->orgchart
  mirror (REQ-ROLE-006); orgunit->inventory restamp; orgunit->bank responsibility audit;
  inventory->mission sale entries; hangar->mission detach; catalogue->mission designation;
  operation->mission detach; privacy->all FK order; exchange->hangar/blueprint journal+feed), 4
  after-commit OK (identity->blueprint default grant (self-healed), identity->bank holder reconciliation
  (nightly), exchange departure, notification fan-out). Three mechanisms: (a) module command API,
  (b) observer SPI owned by lower module, called synchronously (StockChangeObserver,
  MembershipChangeObserver, ship-deleted, job-type-designated, stock-sold), (c) after-commit events only
  for the 4. Guard: observer impls MANDATORY; no @TransactionalEventListener on in-tx event types.
  -> RULES OUT OPTION D and async-first design.
- DOM-09 JPA: 185 associations; 74 cross a domain (69 @ManyToOne, 2 inverse @OneToMany, 3 @ManyToMany):
  User 24, orgunit 18, catalogue 24, business 8; NO cross-domain cascade; all 147 @ManyToOne LAZY; ONE
  EAGER @ManyToMany MissionParticipant.orgUnits (MissionParticipant.java:86) outside
  toOneAssociationsAreDeclaredLazy. Convert only the 8 business->business (InventoryJobOrderAllocation.
  jobOrder, InventoryMissionAllocation.mission, MaterialExchangeOffer.inventoryItem, MissionUnit.ship,
  Mission.operation, Mission.refineryOrders inverse, Operation.missions inverse, RefineryOrder.mission) to
  ids; keep associations into identity/orgunit/catalogue (carry scope JPQL + fetch graphs).
- DOM-10 hubs: User (16 domains, 93 classes), AuthHelperService 16, AuditDetails 15, AuditEvent/
  AuditEventType/AuditService 14 each, UserRepository 13, OwnerScopeService 13, OrgUnit 12,
  OrgUnitMembershipRepository 9, UserMapper 7, InventoryItemRepository 6; AbstractEntity & OptimisticLock
  19 each; MetricNames 1015 LOC/182 constants. Publish: AuthHelperService, AuditService, AuditDetails,
  AuditEventType (keep central), ScopePredicate, OrgUnitMembershipQueryService, reference DTOs. Hide:
  AuditEvent (record() returns entity -> return nothing/id). Split: UserRepository behind UserDirectory;
  UserMapper into reference mapper + Staffel-enriched view; MetricNames per module (values identical).
- DOM-11 invisible coupling: 165 SpEL refs; Roles in 247 endpoints; ScopeSpecifications isolated jdeps
  node; native SQL; V252 triggers (exchange migration installs triggers on blueprint/inventory/hangar
  tables); path lists (30 requestMatchers, 14 NoStore families, import path body limit). -> table-ownership
  registry test; ONE Flyway location, linear history (257 migrations).
- DOM-12 traps: 44 "..backend.<layer>.." selectors; 36 FQCN string literals; 6 invariants go vacuous
  (controllersMustNotInjectTheLazyMembershipMapper ADR-0067, bankClassesMustNotConsultOrgUnitScope
  REQ-BANK-008, cascadeServiceMustNotConsultTheSecurityContext REQ-ORG-015,
  delegatedRoleAuthoriserMustNotConsultOwnerScope REQ-ROLE-004, orgUnitAwareBankSeamIsContainedToOneClass
  ADR-0020, delete-half of bankLedgerRepositoriesMustStayInsertOnly REQ-BANK-004); cycle rule would fail on
  domains; sealed AppException. POSITIVE: all 1389 simple names unique -> bean names + springdoc schema
  names (openapi.json, frontend contract tests) survive package moves. -> preparatory PR: class
  literals, FQCN meta-test (Class.forName), layer selectors "..controller.." inside modules, split cycle
  rule (layer cycles per module + frozen module cycles), keep AppException family in shared kernel OR
  non-sealed domain-exception base by ADR, rule "simple names unique". Vault Backend.md says 38 rules ->
  43.
- DOM-13 exchange = best first Gradle extraction (Ca only 11 edges from 6 modules: exchange row records
  in other modules' repositories BlueprintRepository->ExchangeBlueprintKeyRow, GameItemRepository->
  ExchangeItemKeyRow, LocationRepository->ExchangeLocationRow, ShipRepository->ExchangeShipRow,
  InventoryItemRepository->ExchangeStockLotRow; PersonalBlueprintService->ExchangeClientRepository;
  BlueprintUploadPreviewService->ExchangeDraftService; IngestGatewayProperties read by
  CustomJwtGrantedAuthoritiesConverter & UserDeletionService). arc42 §5.5:142 "write through the domain's
  own services" FALSE for stock (drift). ChangeSourceTransactionManager is app-wide tx manager -> stays
  platform.
- DOM-14 bank second candidate (Ca 18: identity 9, notification 4, livesync 2, audit 2, orgunit 1; all
  outbound point down); invert 18 inbound edges then package + Gradle; seam rules re-keyed.
- DOM-15 identity->orgunit 21 edges (UserMapper Staffel data); User.defaultPayoutPreference typed with
  mission's PayoutPreference; UserReconciliationService grants default blueprints inside syncUser ON THE
  AUTH PATH (-> after-commit UserRegistered event; self-heal task); /api/v1/users/** hosts memberships (6),
  bank counterparty search (2), dashboard read marker, blueprint-sharing preference (2); /me/layout &
  /me/capabilities = web-shell composites. NOTE: agent suggested deprecated aliases for Android —
  SUPERSEDED by owner's hard-cut decision.
- DOM-16 platform depends upward (21 edges): AuditService reads UserRepository for actor display name;
  AuditRetentionService->BankAudit*; RecipientResolutionService->bank grants; NotificationRuleService->Role;
  LiveSyncSubscriptionAuthorizer->scope gates + OrgUnitBankAccessService; CorrelationIdFilter->
  OwnerScopeService (MDC orgUnitId); AuthHelperService delegates 4 gates to OwnerScopeService via getBean
  "to avoid a bean cycle"; ObjectProvider<OrgUnitBankResponsibilityService> papers over cycles. -> SPIs:
  ActorHandleResolver, RetentionParticipant, RecipientSelectorProvider, LiveSyncTopicAuthorizer,
  ActiveOrgUnitProvider. EARLY, cheap, breaks many cycles.
- DOM-17 catalogue almost clean foundation (Ce 15): ShipTypeController->ShipMapper (hangar),
  LocationService in-use checks via ShipRepository/RefineryOrderRepository, JobTypeService->
  MissionParticipantRepository. Sub-packages per area (universe 66, import 49, material 48, recipe 35,
  reference 24, ship 21, item 18); P4kImportService 1531 LOC.
- DOM-18 36 classes > 600 LOC; facades (MissionService, JobOrderService, InventoryItemService,
  OwnerScopeService) = ready-made module command APIs; sub-services package-private after move.
- DOM-19 574 endpoints in 99 controllers, 55 path families; /api/v1/admin/** 51 endpoints of 5 modules
  (identity 18, exchange 14, blueprint 11, catalogue 4, personalinventory 4); 41 bodies call 2+ modules.
  Re-cut only where a path hides its owner (memberships under /users, bank search under /users,
  announcement read marker, web-shell composites under /me); decide admin placement once.
- DOM-20 RECOMMENDATION C: A = packages in backend gated by ArchUnit modules() + freeze (no new
  dependency), leaves first; then Gradle modules for exchange and bank; B alone impossible (Gradle forbids
  cyclic project deps; 21-node SCC); D rejected (205 in-tx audit writes, 12 sync families, locks).
  Shared kernel: AbstractEntity, PageResponse, AppException family, Entities, OptimisticLock,
  StringNormalization, LikePatterns, DtoConstraints/OnUpdate/WholeNumber, HandleAnonymisation + new
  UserRef/OrgUnitRef value types + SCU rounding (InventoryItem.roundToScuScale used by 5 modules).
  Phases: 0 guards (DOM-12 PR, SpEL resolution test, domain-map module rule + freeze, table ownership);
  1 cheap inversions/re-homings (leadership->orgunit, PayoutPreference, HandleAnonymisation, exchange row
  records, ShipTypeMapper, AuthHelperService delegations, AuditService.record return type, platform SPIs);
  2 top leaves: dashboard [0 inbound], admin [4], promotion [1], personalinventory [1], orgchart [mirror],
  operation [11], exchange [11] -> Gradle, bank [18] -> Gradle; 3 core: materialexchange, refinery [23],
  joborder [39], mission [56], inventory [70] with StockCommands + earmark SPI, hangar [29], blueprint [37],
  each taking its access policy out of AccessGateService; 4 foundation: scope kernel, orgunit, identity with
  GDPR SPIs, catalogue; 5 further Gradle modules where green for some releases. Guards G1..G11. XL overall,
  ~25-35 PRs. Spring Modulith/jMolecules not needed for enforcement.
- DOM-21 July diagnosis holds for layers, not for domains; Sept audit had no modularity finding.
- Vault/doc drift: Backend.md 38 rules -> 43; arc42 §5.5 exchange-through-domain-services false for stock.

## 60-modern-java (JAVA-01..17) — read
- Already modern: main 1,049 records (backend 663, frontend 357, ingest 26, spi 2, test-support 1); 89/89
  switches arrow form; 164 type patterns vs 4 instanceof+cast; 310 Stream.toList vs 2 Collectors.toList;
  0 legacy date/collection APIs; 0 prose comments; 180 of 363 @Query already text blocks.
- JAVA-01 SECURITY: frontend + ingest MonitoringScrapeProperties @Data -> toString prints scrape password
  (backend twin is redacting record); keycloak-spi record Brokered(accessToken, username, email)
  (DiscordGuildRoleGateAuthenticator.java:280) prints Discord access token + PII (sibling MemberLookup
  redacts; no current log prints it; spi has NO masker); PiiMasker masks JWT/email/bearer/token/session-id/
  authorization but NOT password=/secret=/usernames. 112 records with PII-named components, 12 with
  credential-looking names (5 override toString). Fix: records with redacting toString + ratchet test
  (component/field names matching secret|password|passphrase|token|credential|apikey|privatekey must be on
  a reviewed redacting list). S.
- JAVA-02 Checkstyle MissingSwitchDefault (google_checks.xml:172, maxWarnings 0) forces default into all 20
  switch STATEMENTS; 9 enum statements already cover every constant and keep a dead default (BankAccountService
  :247 BankAccountType; BankApprovalLimitService:167 {} silently; BlueprintImportService:148;
  InventoryItemService:589/657/706; NotificationRuleService:182 SelectorKind; OrgHierarchyService:206
  OrgUnitKind; UserService:506). Probe: `case null ->` or switch expression gives compile-time
  exhaustiveness AND passes Checkstyle 14.3.0 (also on --release 21). Policy ADR: no default over a
  project enum unless deliberate subset. 35 expressions already exhaustive.
- JAVA-03 exchange: sealed Planned (ExchangeStockWriteService:1076, ExchangeBlueprintWriteService:422)
  consumed by if/else-if WITHOUT else (a new variant silently dropped: no result row, no write);
  default standing in for last constant (ExchangeUndoService:431-435 default=SHIP refreshes hangar topic;
  ExchangeEntryLabels:74; ExchangeShipWriteService:557-561); bank report label switch duplicated in
  BankManagementReportService:290 & BankStatementReportService:290 (3 of 6 types + "").
- JAVA-04 exchange mass-change capability decided on a STRING: ExchangeMassChangeService.capability(String)
  :278-284 `default -> ExchangeCapability.HANGAR_WRITE`; today Bean Validation @Pattern
  ^(blueprints|stock|ships)$ refuses unknowns first, so not exploitable; but typed ExchangeResource enum
  exists with other spelling (BLUEPRINT, STOCK, SHIP) + third string set in ExchangeLiveSync -> map wire
  spelling once at boundary, exhaustive switch; no path may default to a capability (fail-closed).
- JAVA-05 OrgUnitKind ==/!= 43x in 19 files (tenancy rules re-derived); FinanceType if/else-if without
  else in payout arithmetic -> enum predicates via switch(this) (precedent OperationStatus.canTransitionTo)
  + values() tests; one predicate per rule (never merge two rules). M.
- JAVA-06 5 type patterns on lazily proxied abstract @Entity OrgUnit (SINGLE_TABLE) without
  Hibernate.unproxy (OrgHierarchyController:366, LeitungViewService:199, OrgChartReadService:101,
  OrgUnitMembershipService:340 (clears Grand Admiral appointment), :462); 6 other sites unproxy. Hibernate
  7.4.5 @ConcreteProxy (@Incubating) exists. Whether proxies reach these flows UNKNOWN -> reproduce test.
  Proxy can never subclass a sealed class -> never seal entities. Impact: data integrity of top-level
  appointment, not privilege.
- JAVA-07 sealed + package-by-domain conflict (probe): AppException + AppExceptionKind + disclosure
  contract stay shared kernel (one package, sealed); domains throw kernel subtypes; don't seal
  cross-domain contracts (NotificationEvent); seal only closed sets inside one domain package; don't seal
  interfaces tests implement or proxied beans. (Alternative PSA-01: non-sealed domain-problem base +
  ProblemKind per domain + registry test.) -> decision in module ADR.
- JAVA-08 backend ChangeSource.ON_BEHALF ThreadLocal (added 71a5ae641 after ADR-0223) = clean ScopedValue
  fit (JEP 506 final in 25; backend has no WebFlux); one caller ExchangeMassChangeService:137; read by
  ChangeSourceTransactionManager:69 (exchange attribution in change feed ADR-0224) -> ADR-0223 amendment;
  exact API to verify.
- JAVA-09 ADR-0223 drift: AvoidModuleImport + CompactSourceFileNotAllowed enforced by Checkstyle since
  49a3dcd4e (main only; test/e2e javadoc_position.xml lacks it); 42 not 34 super(args); vault Decisions.md
  :618 "Neither rule is gated in CI" stale; no gate for --enable-preview -> add.
- JAVA-10 `_`: used once; candidates main: 107 unused lambda params, 133 unused catch params, 20 empty
  catches named ignored; javac 25/Checkstyle/gjf OK except EmptyCatchBlock (exceptionVariableName
  ^(ignored|expected)$ -> widen + ADR-0214 amendment + note vendored config edit); not in spi (--release
  21). Keep descriptive names (name = only in-code intent statement since comments banned). 5 rethrows drop
  the cause -> chain (check ErrorDisclosurePolicy first).
- JAVA-11 records: convert AnnouncementController.AnnouncementRequest, UserController.
  UserAttributesRequest, UserDescriptionRequest, ShipTypeMatcher.TokenView; keep Spring MVC *Form JavaBeans;
  no blanket List.copyOf (0 of 262 collection components copied) — only for cached/shared/domain-API
  values; keep redaction DTOs on canonical constructors; never a record as top-level frontend session
  attribute.
- JAVA-12 Markdown /// (JEP 467) FAILS Checkstyle 14.3.0 (MissingJavadocType/Method) -> don't adopt; recheck
  per Checkstyle bump.
- JAVA-13 JetBrains annotations in 997 files (@NotNull 7,410, @Nullable 1,756...); Spring 7.0.9 is
  JSpecify @NullMarked, org.springframework.lang.Nullable deprecated since 7.0 -> keep JetBrains now;
  decide with NullAway evaluation (ADR-0192 amendment); if ever, do it during package re-cut
  (package-info @NullMarked).
- JAVA-14 PRIVACY: 5 frontend log lines tag users with String.hashCode() of preferred_username
  (BackendRoleSyncFilter:150 u-%08x; JobOrderPageController:1116; JobOrderWriteController:1436;
  RefineryOrderPageController:1006,1026) -> dictionary-reversible pseudonym (REQ-OBS-004 never log names)
  -> drop / MDC user id / keyed HMAC with HexFormat. S.
- JAVA-15 small idioms: Math.clamp 11 of 15 candidates; get(0)->getFirst 9 (frontend 5, ingest 4);
  matchesProfiles 3; isPresent+get 13; 47 @Query concatenations -> text blocks; 631 test JSON literals ->
  text blocks; keep trim/strip (StringNormalization deliberate), keep Collections.unmodifiable* views over
  lazy entity collections, keep Arrays.asList where nulls; @Serial on 3; frontend FQNs 482; no gatherers.
- JAVA-16 sound: spring.threads.virtual.enabled true in all 3 apps; pinning probe: no pinning on JDK 25
  (8 VTs x 200 ms in monitors finished in 205 ms); Thread.sleep 3 in main (one on request path
  CustomJwtGrantedAuthoritiesConverter:289); CompletableFuture.allOf in 8 page controllers stays
  (structured concurrency preview); JEP 500: all 36 setField calls target non-final fields.
- JAVA-17 keycloak-spi --release 21: loses `_` (5 catches), JEP 513, module imports, Gatherers, KDF,
  ScopedValue; keeps records, record patterns, pattern switch, sequenced collections, Math.clamp.

## 70-research (RES-01..22) — read (sources read 2026-09-29; URLs in 70-research.md §7 A)
- JDK: 24 GA 2025-03-18 (final incl. 485 Gatherers, 491 no pinning, 483 AOT, 484 Class-File, 496/497
  ML-KEM/ML-DSA); 25 LTS 2025-09-16 (final 506 ScopedValues, 510 KDF, 511 module imports, 512 compact
  source, 513 flexible ctor, 514/515 AOT, 519 compact headers...; preview 470 PEM, 502 Stable Values,
  505 SC, 507 primitive patterns, 508 Vector); 26 GA 2026-03-17 (final 500 final-means-final, 504 remove
  Applet, 516 AOT any GC, 517 HTTP/3 HttpClient, 522 G1); 27 GA 2026-09-15 non-LTS (final 523 G1 default
  everywhere, 527 PQ hybrid TLS, 534 compact headers default, 536 JFR redaction); 28 in development (542
  PEM final, 544 AOT code compilation proposed). Next LTS Java 29 Sept 2027 (Oracle roadmap; subject to
  change); Java 25 premier support to Sept 2030. Boot 4.1.1 supports Java 17 up to and including 26.
  -> STAY ON 25. RES-01: ADR-0223 wrong: "JDK 26/27 add no final library feature" — JEP 517 adds
  HttpClient.Version.HTTP_3 etc.; JDK 25 table omits JEP 510 (final) and 470 (preview); tooling line
  names Checkstyle 14.1.0 vs pin 14.3.0 -> amend. RES-02: before any JDK 26+ trial run tests with
  --illegal-final-field-mutation=deny (37 setField lines; JAVA agent says all non-final); JEP 544 at move
  to 29 for ADR-0209.
- RES-03 Spring Modulith 2.1.1 (2026-08-26) on Boot 4.1.1/SF 7.0.9; 2.2.0-M2 for Boot 4.2; supports
  explicitly-annotated detection, nested modules, Type.OPEN, @NamedInterface, allowedDependencies,
  verify(), @ApplicationModuleTest(STANDALONE), Documenter (C4/Canvas). spring-modulith-core depends on
  ArchUnit 1.4.2 (compile) vs our 1.5.1 -> binary compat UNKNOWN until verify() runs. Test scope only
  = zero runtime footprint. Cons: 2nd architecture-testing vocabulary; verification metadata regen.
- RES-04 Modulith event registry = real transactional outbox but traps: starters pull
  spring-modulith-core + ArchUnit 1.4.2 onto PRODUCTION classpath (use events-jdbc + events-jackson,
  not starter); JDBC schema auto-creation ON by default in 2.1 (disable; Flyway migration from v2 PG
  DDL); serialized_event TEXT plain + event_type class name persisted; on ClassNotFoundException the
  publication is DROPPED with only a WARN (moving an event class loses incomplete events); at-least-once
  (listeners idempotent); @ApplicationModuleListener = @Async + REQUIRES_NEW + AFTER_COMMIT -> NEVER
  audit there (REQ-AUDIT-001). Also: listeners have no SecurityContext/org-unit -> scope by ids in event;
  Modulith metric names not basetool_*; add basetool_event_publications_incomplete gauge + alert.
- RES-05 per-domain frontend clients: HttpServiceProxyFactory.builderFor(WebClientAdapter.create(
  webClient)).build().createClient(X.class) in ONE BackendClientsConfig; or @ImportHttpServices groups only
  with a highest-precedence WebClientHttpServiceGroupConfigurer using groups.forEachClient(
  InitializingClientCallback -> webClient.mutate()) (mutate copies filter list). Default group = fresh
  builder with only spring.http.serviceclient.* props + WebClientCustomizer beans (frontend has none) ->
  missing OAuth2/org-unit/correlation/resilience. NEVER @Cacheable on proxy interfaces (key = args,
  response depends on implicit bearer + X-Active-Org-Unit-Id -> cross-user/tenant leak). Guards:
  MockWebServer test per client (Authorization, X-Correlation-Id, X-Active-Org-Unit-Id, breaker name);
  ArchUnit: HttpServiceProxyFactory only in BackendClientsConfig; @ImportHttpServices only next to
  initializing configurer; no @Cacheable on @HttpExchange. Amend ADR-0032 + arc42 §4.1.
- RES-06 Spring 7 API versioning (ApiVersionConfigurer, usePathSegment) + StandardApiVersionDeprecation
  Handler (RFC 9745 Deprecation date header + RFC 8594 Sunset) could replace custom @ApiDeprecation +
  DeprecationInterceptor (emits Deprecation: true). Header value change is client-visible. [Coordinator:
  with owner's hard-cut decision + frozen exchange, low value -> P3/optional.]
- RES-07 SF7 resilience: @EnableResilientMethods + @ConcurrencyLimit (blocking) for backend outbound
  fan-out (UEX, SC Wiki) where virtual threads no longer bound concurrency; keep find-or-create
  REQUIRES_NEW retry explicit (advice ordering @Retryable vs @Transactional undocumented).
- RES-08 @PreAuthorize bean refs resolved per call; ExpressionUtils (spring-security 7.0.9/7.1.1) throws
  IllegalArgumentException -> GlobalExceptionHandler:594-604 -> 400; PreAuthorizeBeanReferenceTest via
  ClassFileImporter + SpelExpressionParser AST BeanReference.getName() — needs no Spring context; forbid
  non-constant SpEL; optional templated meta-annotations (AnnotationTemplateExpressionDefaults).
- RES-09 OpenRewrite: ChangePackage/ChangeType (Apache 2.0) also rewrite class names in
  application*.yml/.properties VALUES, Spring XML, META-INF/services; plugin > 7.41.0 and core > 8.90.4
  ONLY from authenticated "Code Genome Project" repo; migration recipes (UpgradeToJava25,
  UpgradeSpringBoot_4_0, JUnit5to6Migration) under Moderne Source Available License; UpgradeToJava25
  includes DanglingDocCommentToBlockComment (violates ADR-0214). -> run ChangePackage/ChangeType from a
  THROWAWAY non-committed init script with plugin 7.41.0 + rewrite-java 8.90.4 (Portal/Central, no
  creds); never commit (credentialed repo would break FAIL_ON_PROJECT_REPOS + verification + supply
  chain); no MSAL recipes; doesn't handle YAML keys, string literals, SpEL, Thymeleaf, persisted ids.
- RES-10 Error Prone 2.50.0 (JDK 21+) + NullAway 0.14.2 (JSpecify mode needs JDK 22+) recognise any
  @Nullable/@NotNull by simple name -> works with JetBrains annotations; Spring builds with them via
  io.spring.nullability (its explicit-marking default contradicts ADR-0192 -> use plain NullAway with
  AnnotatedPackages). Plugins net.ltgt.errorprone 5.1.1, net.ltgt.nullaway 3.2.0; start with new domain
  API packages; UNKNOWN: Lombok interplay with addNullAnnotations=jetbrains, CC compatibility; ~10
  --add-exports/--add-opens javac flags; closes arc42 §11.4 debt.
- RES-11 Gradle 9.8.0 current; Isolated Projects incubating since 9.7.0, incompatible with allprojects
  (build.gradle.kts:14) / subprojects (:122) + 22 rootProject.(file|fileTree|layout|extra) accesses
  (backend 7, frontend 13, test-support 2); CC default in Gradle 10; included build-logic covered by root
  verification-metadata.xml; catalog in precompiled plugins only via VersionCatalogsExtension string API.
  -> build-logic convention plugins (basetool.java-conventions, spring-app, quality, sbom) before B/C.
- RES-12 Hibernate 7.4.5 (Boot-managed): reject incubating @Audited (would persist full row history incl.
  PII outside GDPR flow) and Jakarta Data stateless repos (don't fit @Version/managed concurrency);
  7.4 says limits with collection fetch joins now safe via subqueries -> characterisation test before
  touching fail_on_pagination_over_collection_fetch (REQ-DATA-003).
- RES-13 PG 18.6 (supported to 2030-11-14): RETURNING OLD/NEW for native bulk updates needing old value
  for audit; skip scan (review multicolumn indexes); WITHOUT OVERLAPS (needs btree_gist) for appointment/
  membership validity with leadership redesign; uuidv7 reveals creation time -> only with privacy
  decision (104 GenerationType.UUID, 44 gen_random_uuid); MD5 deprecated -> confirm SCRAM (infra check).
- RES-14 Baseline (web-features 3.40.0, 2026-09-24): widely available: Object.groupBy,
  Promise.withResolvers, Array.fromAsync, CSS nesting, container size queries, :has(), color-mix(),
  <dialog>; newly available: Set methods, iterator helpers, popover, same-document view transitions,
  Trusted Types, @scope. Implied floor of shipped features: Chrome 105, Firefox 121, Safari 16.4. ->
  raise tsconfig lib/target + ESLint ecmaVersion to ES2024 (TS7 lib name UNKNOWN); Trusted Types
  report-only experiment; under enforcement innerHTML = '' throws; forbid permissive default policy.
- RES-15 JPMS rejected: no Spring/Boot/Modulith/Hibernate jar ships module-info (Automatic-Module-Name
  only; spring-framework #18079 open since 2015); JEP 483 AOT cache forbids --add-opens/--add-exports.
- RES-16 ArchUnit Modules API (modules().definedByAnnotation, respectTheirAllowedDependenciesDeclaredIn,
  onlyDependOnEachOtherThroughPackagesDeclaredIn) + FreezingArchRule (text ViolationStore, line-number
  insensitive); empty-should default fails; never freeze security rules; test listing frozen rules vs
  allow-list; choose ONE primary tool (Modulith or plain ArchUnit).
- RES-17 string-bound names: 172 T(Roles) in 22 templates; session prefix; 47 ArchUnit "..x.." patterns
  + 54 FQ package strings in tests (37 in ArchitectureTest); SpEL bean names; future event_type rows.
  -> keep Roles stable or expose to templates as bean/dialect (@roles...); session types stay in
  frontend.model or frontend.session, if moved add EXACT prefix and keep old one for one session lifetime;
  test ALLOWED_PREFIXES entries end in a model/session package; class-literal anchors
  resideInAPackage(X.class.getPackageName() + "..").
- RES-18 Boot 4.1 InetAddressFilter.externalAddresses() / HttpClientSettings.withInetAddressFilter for
  EXTERNAL integration clients (UEX, SC Wiki, Discord) = SSRF/DNS-rebinding defence; applies to
  auto-configured builders only — backend/ingest build RestClient manually -> apply explicitly; never on
  internal clients (Keycloak admin, ingest->backend). S.
- RES-19 Jackson 3.1.5 FAIL_ON_UNKNOWN_PROPERTIES false by default (tolerant reader everywhere); one strict
  local mapper MissionWriteController:148-149. Agent proposes strict external inputs incl. exchange —
  [Coordinator: CONFLICTS with frozen exchange contract (MB-01: tolerant reading of unknown fields is
  part of frozen behaviour, ADR-0219 §2) -> REJECT for exchange; just document policy in REQ-API.]
- RES-20 ContextSnapshotFactory.captureAll().wrapExecutor(executor) (context-propagation 1.2.1) instead of
  ParallelPageLoader hand copy (fixes locale gap FE-12 structurally); register
  RequestAttributesThreadLocalAccessor; keep explicit MDC copy or small MDC accessor; test all values
  arrive and none survives.
- RES-21 CI never reuses configuration cache: setup-gradle v6.3.0 without cache-encryption-key -> add
  GRADLE_ENCRYPTION_KEY secret (PR runs cache-read-only) or record deliberately off; v6.4.0 exists.
  Public repo runners 4 vCPU / 16 GB.
- RES-22 option B mechanics hold: Flyway 13.8.0 merges one classpath location across jars, content-only
  checksums, duplicates rejected; Boot scans entities/repos across jars under auto-config package; root
  verification file covers included builds; testFixtures stay out of bootJar unless main-scope
  (jvm-test-suite incubating). Keep root package + one V<n> sequence.
- Frontend follow-ups (§5): @Cacheable on proxy beans applies (-> leak risk, forbid); openapi-generator
  7.25 records/Jackson 3 (see file); ContextSnapshot API; metadata-action revision label; skopeo multi-arch
  labels; JLS unnamed patterns in one case label (see file §5 (s)).

## 90-rest-api (API-01..51) — read
- API-01: 99 @RestController (8 in controller.exchange), 574 mappings, 572 in openapi.json (440 paths,
  489 schemas); undocumented: /error, POST /internal/discord/account-existence (@Hidden). 22 domains; 11
  span several first segments (catalogue 18, orgunit 5, identity 4, admin-system 4...); /api/v1/admin/**
  51 ops of 5 domains protected by ONE URL rule (SecurityConfig:433-434); bank uses /bank/admin/**; 29
  operations in another domain's controller/prefix; mixed controllers UserController (27 mappings, 6
  domains), MeController, AdminController, JobOrderController (34), InventoryItemController (27),
  MaterialController; duplicated capabilities (balance-target twice; two game-item catalogues; PUT
  /admin/users/{id}/attributes no caller).
- API-02 SECURITY: authorization in 5 layers (URL rules SecurityConfig:362-440 first-match; controller
  @PreAuthorize 413 (53 class, 360 method; 214 mappings rely on class-level); service @PreAuthorize 18;
  imperative service checks; imperative controller checks RefineryOrderController:171-220). 196 mappings
  only isAuthenticated() at annotation level. FOR 15 MAPPINGS THE URL RULE IS THE ONLY ROLE GATE: 13 under
  /api/v1/inventory/** (GET /inventory/all, /all/grouped, /aggregated, /mission/{id}, /item-catalog, POST
  /inventory, four bulk-* writes...; rule hasAnyRole(ADMIN, OFFICER, LOGISTICIAN, KRT_MEMBER)
  SecurityConfig:427-428) + 2 under /api/v1/hangar/** (GET /hangar/squadron-overview, POST
  /hangar/ships/home-location; :419-423); services check scope not role; role hierarchy doesn't make bank
  roles or MISSION_MANAGER imply KRT_MEMBER -> a BANK_EMPLOYEE-only account is stopped by URL rule alone.
  Moving such a path (e.g. /inventory/item-catalog -> /game-items) silently widens to any authenticated.
  -> authorization-matrix golden file (verb, path, handler, effective @PreAuthorize, matching URL rule)
  committed backend/src/test/resources/api/authorization-matrix.txt; lift 15 URL-only gates into
  annotations FIRST (strictly additive); move RefineryOrderController imperative checks into facade;
  generalise admin fence /api/v1/*/admin/** -> ADMIN.
- API-03 ArchUnit controller rules keyed on ..backend.controller.. (fail only when NO controller left;
  partial move silently drops); permitAll loop no floor; staffel write rule 7 simple names; only
  peerReadable... has floor >= 10 -> re-key on @RestController + floors (574 mappings, 4 permitAll) +
  side-by-side equal-count assertion once.
- API-04 SECURITY (defence in depth): edge api-allowlist.conf 172 rules (93 exact, 79 regex) keyed on
  $uri only; prefix rules ^/api/v1/terms/ (line 2) and ^/api/v1/me/ (line 3); read-only family line 177
  (16 families) -> 405 non-GET, reset per path lines 179-230; PUT-only carve-out 207-211. 259 documented
  ops admitted vs 234 frozen; 25 admitted-not-frozen: 11 refused by read-only rule, 14 REACHABLE (e.g.
  GET /me/layout contrary to REQ-API-012, DELETE /hangar/ships (all own ships), POST /bank/accounts,
  POST/PATCH /bank/holders..., POST /job-types (ADMIN), PUT .../participants/{pid}/slim) — all gated in
  backend, NO bypass; app v0.3.1 calls POST /api/v1/operations which NO rule admits -> 404 at edge
  (v0.4.0 UNKNOWN; check curl -si -X POST https://api.profit-base.online/api/v1/operations 404 vs 401);
  GET /materials/matrix called+admitted but not frozen. Nothing asserts admitted ⊆ frozen. Re-cut hazard:
  anything moved under /me/ or /terms/ becomes public on the vhost (/admin/terms -> /terms/admin admitted
  by line 2). -> generate include from contract set: nginx map on "$request_method:$uri", anchored regex per
  frozen op, no prefix rules; generate probe table; test admitted == frozen ∪ {2 anonymous reads}.
  ADR-0135 amendment.
- API-05 13 dual-use DTOs (Bereich, FrequencyType, JobType, Location, MaterialCategory, Material,
  Organisationsleitung, RefineryOrder, RefiningMethod, SpecialCommand, Squadron, StarSystem, Terminal);
  RESPONSE_ONLY_DTOS protects only MissionDto. POST /refinery-orders binds full RefineryOrderDto;
  mapper ignores owner/owningOrgUnit/createdAt/updatedAt; service resets id/version/owner/org unit but NOT
  status -> member can create order directly in COMPLETED (storeRefineryOrder then refuses to store; own
  data only; confirm with MockMvc). App sends status=IN_PROGRESS on create -> restricted field (OPEN |
  IN_PROGRESS), not dropped. 23 DTO types nested in 9 controllers (3 mutable). -> per-domain
  ...web.dto Request/Response records; structural rule "a type returned by any mapping is never a
  @RequestBody"; extend missionWriteRequestDtosMustNotCarryServerManagedFields to every request record.
- API-06 13 @RequestBody without @Valid (AdminController:90,103; DiscordAccountExistenceController:75;
  DiscordRegistrationAdminController:119; FrequencyTypeController:100,118,154; MaterialCategoryController
  :83,100; RefiningMethodController:97,115; StarSystemController:97,113) — 12 ADMIN-only, 1 SPI
  shared-secret; 14 body types without Jakarta constraints; REQ-API-002/003 unenforced; 7 PUT/PATCH bodies
  without version (some by design). -> ArchUnit @Valid rule + version-or-reason ledger.
- API-07 error contract not documented (no code/correlationId/fieldErrors; untyped); no code registry;
  app shipped wrong constant TERMS_ACCEPTANCE_REQUIRED vs TERMS_NOT_ACCEPTED -> ProblemCode registry
  (per-domain enums implementing one interface; AppExceptionKind has code()); keep code a string (not
  required enum) in frozen responses.
- API-08 REQ-API-005 says Pageable+PageResponse everywhere; reality 1 Pageable, 67 page/size params via
  PaginationUtil, 75 PageResponse, 71 unpaged lists; free text q/query/search/name/filter; 28 /slim
  suffixes (meaningless since #1996); roots /announcement, /promotion, /leitung (German), /uex, /settings
  vs /system; reorder PUT/POST inconsistent.
- API-09 OpenAPI monolithic, 96 tags (55 auto from class names), openapi 3.1.1 config vs 3.1.0 doc,
  304/ETag claimed for 114 no-store reads (OpenApiCachingConfig), 2 MB -> one tag per domain + x-domain,
  autoTagClasses off, derive per-domain views; caching customizer reads NoStoreApiScopes.
- API-10 schema name collisions (springdoc use-fqn false): Op (3 exchange change sets; doc shows STOCK
  shape for blueprint & ship change sets), Provenance, Skipped -> uniqueness test + explicit @Schema names;
  NOT use-fqn (renames 489 schemas).
- API-11 backend OpenApiGeneratorTest asserts nothing (REQ-API-007 claims it does — was the removed ingest
  generator).
- API-12 app contract ExternalContractTest (2,888 lines, 235 entries = 234 pairs, one duplicate) hand-
  maintained and drifted from app (POST /operations, GET /materials/matrix); app vendors manual copy of
  openapi.json (400 paths vs 440 now); floor >= 5 for 235 entries; release baseline fetched
  continue-on-error + Assumptions.assumeTrue -> failed fetch = green check that checked nothing. ->
  app publishes machine-readable call list per release; ratchet floor; mandatory baseline on main;
  generalise ingest SchemaCompatibility to backend doc.
- API-13 Spring API versioning adds little under hard cut (path versioning load-bearing; never two
  versions). Delete /api/v2/system/ping demo.
- API-14 controllers are part of domain implementation: 283 mappings in controller @Transactional (OSIV
  off), 31 controller->mapper edges, 76 controller->model edges, 58 mappings read @AuthenticationPrincipal
  Jwt directly (UserService.getUserIdFromJwt) vs 24 @CurrentUserId; business rules in
  RefineryOrderController. -> <domain>.api (facades, tx boundary, records) + <domain>.web (controllers,
  REST DTOs, no @Transactional, no repo, no entity); identity only via @CurrentUserId; T1 REST DTOs separate
  from module-API records. Exchange layer = in-repo template.
- API-15 14+ path readers (frontend 516 literal call sites; E2E 39 files; Android ~35 files + vendored
  openapi; ingest; SPI; edge 172 rules + 145 probe lines + blackbox; SecurityConfig 26 literals;
  ActingMemberFilter; Terms/Pending filters; NoStoreApiScopes; ETag filter; application.yml rate-limit
  rules (/api/** + mission-create, order-create, finance-entry-create, participant-mutations);
  SubjectRateLimitingFilter; logging/body-limit/observation; tests; dashboards group by uri label (history
  breaks)). -> per-domain path class in frontend; backend per-domain declarations (@NoStore,
  @RateLimited) + runtime test over RequestMappingHandlerMapping; generate edge include.
- API-16 exchange relay seam: parity test ingest relay paths ⊆ documented backend ops ⊆ ActingMemberFilter
  paths; backend schema validation of exchange DTOs.
- PART B per-domain cut (API-20..32): principles P1 one root per domain, P2 admin sub-trees
  /api/v1/<root>/admin/**, P3 me-scoped in owning domain /<root>/me/..., P4 cross-domain read model =
  filter on owner's collection, P5 no /slim, q, /lookup, P6 T0 never moves (version-policy, SPI endpoint,
  /api/v1/exchange/**, SSE streams + /live-sync/changed recommended), P7 every wave = one app release + one
  deploy + one floor raise after Wave-0 guards, P8 no generic CRUD base controller. Illustrative cut: 161
  ops touched, 74 app-frozen; security deltas: 12 paths/15 ops private,no-store -> revalidate unless
  NoStoreApiScopes updated; 22 paths up to no-store; /admin/terms -> /terms/admin exposed; finance-entry-
  create rate limit lost; /inventory/item-catalog -> /game-items loses its only role gate.
  identity (API-21): payout-preference -> /missions/me/payout-preference; blueprint-sharing ->
  /blueprints/me/sharing; read-announcement -> PUT /announcements/{id}/read; search-bank ->
  /bank/members/search; memberships -> /org-units/me/..., /org-units/members/{id}/memberships; admin
  registrations/deletion-requests/person-search/export -> /users/admin/...; /admin/roles -> /roles (ADMIN);
  delete dup attributes; 10 frozen ops; data-model option (a) API-only vs (b) move columns
  defaultPayoutPreference/shareBlueprintsGlobally out of User (own @Version; finer lock).
  orgunit (API-22): fold /org-hierarchy into /org-units; /leitung/view -> /org-chart/leadership.
  bank (API-23): /org-units/bank/** -> /bank/org-units/** (29 ops, 24 frozen — largest app change);
  unify balance-target verb; keep two audiences' controllers/DTOs (REQ-BANK-054 staffNote).
  mission (API-24): /finance-entries -> /missions/{missionId}/finance-entries (gate reads #missionId from
  path — stronger); drop 28 /slim; fold /search into GET /missions; delete POST /participants/add and legacy
  PUT /missions/{id} (no caller; the one force-increment write); re-key finance-entry-create rate limit.
  joborder (API-25): /orders/item-catalog + /inventory/item-catalog -> GET /game-items (lift URL-only gate
  first!); allocations naming. catalogue (API-26): yields under /locations; p4k admin under catalog; one
  /catalog root NOT now. blueprint/personal inventory (API-27) admin sub-trees into domains.
  materialexchange (API-28) /material-requests -> /material-exchange/requests. hangar (API-29) admin
  sub-tree; delete deprecated fleetview import (app moves to /hangar/import/ships). refinery (API-30)
  remove owner override from POST body + logistician reassignment from PUT (keep /users/{userId}/** path
  variant), restricted status. notification/dashboard/settings (API-31) typed per-domain settings; delete
  ping. What does not move (API-32): version-policy, SPI endpoint (fails OPEN — 404 would silently disable
  duplicate-account check), exchange, SSE streams, /terms/*, /audit/**, /connected-apps/**.
- API-40 HARD CUT mechanics: T0 = GET /api/v1/app/version-policy (200, minimumVersionCode, latestVersionCode,
  releasesUrl https; permitAll; not refused by pending/terms gates; /api/v1 prefix; edge exact rule line 53;
  ArchUnit permitAll entry). Floor config: app.android.minimum-version-code <- APP_ANDROID_MINIMUM_VERSION_
  CODE (default 0) in host .env only (not in promoted bundle); @ConfigurationProperties record read at
  startup -> needs backend restart (Requires= restarts frontend + ingest, ~1 min outage). deploy.sh renders
  env.d from host .env at every tick -> floor CAN ride the re-cut deploy. Caveats: .env edit = prod write
  (per-action approval); health-gate rollback restores release but NOT the .env floor (-> runbook must
  revert floor); unrelated restart applies floor early; option: repo-committed default floor per release.
  App (v0.3.1 UpdateGate.kt): reads policy ONCE PER PROCESS, FAILS OPEN on failure, floor 0 = allow all,
  Blocked = non-dismissible wall + releasesUrl. Sequence: 0 publish app N+1; 1 owner-approved .env floor =
  N+1; 2 promotion tick (~1 min outage, edge 503 maintenance; cold-starting old apps fail open);
  3 stack healthy, edge not yet reconciled (reconcile_edge after stack+monitoring, deploy.sh:1400); 4 edge
  recreated with new include (brief outage all vhosts); 5 steady. Gap options: (a) app re-reads policy on
  resume + after unexpected 404 (must ship BEFORE first cut); (b) edge serves static policy file when backend
  502/503/504; (c) retired paths answer APP_UPDATE_REQUIRED problem; (d) cut at low usage + announce.
  Current prod floor UNKNOWN (16/16 on 2026-09-25 per vault; 17 planned) — read-only curl settles.
- API-41 hard cut trips REQ-API-001, REQ-API-009, ADR-0136 decision bullet 4, ExternalContractTest
  previous-release comparison -> ADR "hard cut with forced update" superseding ADR-0136 retirement clause;
  amend REQ-API-001/009/010; declared-break ledger backend/src/test/resources/api/declared-breaks.txt
  (operation + field + absorbing app versionCode; no wildcards).
- API-42 security invariants table per re-cut wave (gates, no new anonymous path, no newly reachable vhost
  path, rate limits, no-store, exemptions, CSRF /api/**, redaction floor, admin fence, exchange unchanged).
- API-43 per-domain API definitions: one tag + x-domain; code-first for backend (contract-first stays for
  exchange); stability tiers @ApiContract(Tier) -> x-contract-tier: T0 never breaks, T1 app contract
  (breaks only in declared hard-cut wave), T2 web-only (free with atomic deploy); breaking-change gate =
  generalised ingest SchemaCompatibility vs previous release (no oasdiff binary — outside dependency
  verification).
- API-50 DRIFT: api-conventions.md REQ-API-007 (generator asserts; @Operation everywhere), REQ-API-003
  (@Valid), REQ-API-005 (Pageable), REQ-API-004 (format documented), REQ-API-012 (/me/layout not on vhost);
  security-and-access.md:1830 REQ-SEC-031 "member record only PII" vs admin export/person-search/
  registrations not in NoStoreApiScopes; vault API Conventions.md counts (412/543/417 -> 440/572/489; 227
  -> 235 contract ops; 1,775 -> 1,819 frozen lines; property apiVhostRunbook -> apiVhostAllowList; "check
  fails" -> CI step; "Eleven requirements" -> REQ-API-012 exists); vault 00 Maps/Basetool.md:31 Ingest row
  stale ("Two forward-only endpoints").
- API-51: API agent keeps July "BackendApiClient single seam" as valid with per-domain PATH CLASSES on top —
  compatible with FE T1 (thin per-domain clients delegating to BackendApiClient). Synthesis: BackendApiClient
  stays transport/error/cache core; per-domain typed clients on top; July reason honoured; "single class"
  wording revised to "single filter chain + single error mapper".

## 95-verify-2 (frontend/edge) — verdicts
- FE-03 NARROWED: only HomeController (2 read-announcement sites) and PromotionPageController can append
  ?query or /segments (tested vs spring-web 7.0.9); DefaultBlueprints + PersonalInventory cannot
  (URLEncoder output double-encoded by WebClient); no site leaves its path prefix; `..` -> 400 by backend
  default firewall; neither target endpoint reads query params -> INFORMATIONAL (REQ-SEC-051 breach).
  NEW functional bug: PersonalInventory sort with direction sent as name%252Casc -> never works (no UI
  sends sort); controller doesn't use RelayParams at all.
- FEA-10 CSV formula injection CONFIRMED code; reach NARROWED: usernames come from Discord (not
  editable); realistic attacker = ADMIN/OFFICER writing a promotion topic name (no @Pattern); victim =
  another officer opening the export -> LOW. Redirect helpers //host + 4 targetUrl: code CONFIRMED, NO
  attacker input today (fixed server paths/constants/allow-list/UUID) -> NONE today (defence in depth).
- Live-sync fail-open CONFIRMED and BROADER: any status other than 403/404 fails open incl. 401,
  timeouts, missing token, full executor; the `orders` capability probe fails open even on 403/404; a
  member can trigger at will (handshake token never refreshed, sockets no max age: after 300 s a
  subscribe gets 401 -> ALLOW). Leak = only `changed` frames with section keys for a UUID the subscriber
  already knows. ADR-0094 documents it as accepted -> LOW.
- API-04 numbers CONFIRMED (259 pass $uri gate, 11 get 405, 248 reach backend = 234 frozen + 14);
  "unintended" NARROWED: app v0.3.1 calls 10 of the 14 (incl. DELETE /hangar/ships, POST /bank/accounts)
  -> gaps in the FROZEN CONTRACT (ExternalContractTest checks paths, not methods, not the other direction);
  only 4 truly unused: GET /hangar/ships, GET /material-requests/{id}, GET /me/layout (contradicts
  REQ-API-012), POST /job-types. POST /api/v1/operations 404 at edge CONFIRMED; app calls it from
  OperationFormViewModel.kt:154 (app bug: operation creation from the app fails). LOW (backend gates all).
- FE-02 CONFIRMED exactly 11; ClientAuthorizationException -> 500, RFC 7807 code lost;
  GlobalExceptionHandler catch-all returns 500; less frequent than implied (terms gate re-checks backend when
  60 s cache stale -> correct 401 + X-Reauthenticate); calls still in http_client_requests_seconds, not in
  basetool_backend_client_errors_total or its alert -> LOW.
- JAVA-14 CONFIRMED + undercounted: 5 hashing sites feed 14 log statements, 6 at INFO+ reaching prod logs;
  PiiMasker doesn't catch; REQ-OBS-004 says log sub UUID -> LOW (privacy).
- FE-12 locale: mechanism CONFIRMED, impact NONE today (backend localizes only RFC 7807 error texts; none
  of these reads shows a backend detail).
- FE-08 session CONFIRMED + BIGGER: refusal drops the whole ATTRIBUTE, and Spring keeps ALL pending flash
  data in ONE attribute (SessionFlashMapManager.FLASH_MAPS) -> one refused form class wipes every pending
  form, BindingResult and toast of the session; all 30 form classes in frontend/model/form; 29 sites flash
  a form or BindingResult; prod enforce documented (security-and-access.md:3438) not observed; repo default
  report. -> refactor risk.

## 95-verify-4 (architecture) — verdicts
- DOM-01 NARROWED: domain-level facts reproduce (5696 edges, 1565 cross, 41 two-way pairs, SCC 21, 17
  without platform, 9-core); robust to dropping 42 ambiguous classes and edge-kind filters. BUT at CLASS
  level only THREE cycles cross domains: sealed AppException family (14 classes), inventory<->joborder
  entities (12), mission<->operation<->refinery entities (11); every other domain cycle breaks by moving
  or inverting single class edges. EXACT minimum feedback: layering all 21 needs >= 136 class edges in 43
  pairs (DOM heuristic 211); core needs >= 46; DOM's §5.5 ranks cost 61 in the core mostly because they rank
  inventory BELOW mission although mission has 0 edges into inventory and inventory 13 into mission ->
  revisit inventory/mission order. 9-core kept alive by as few as 9 class edges (one Hamiltonian cycle);
  cutting 4/6/7 edges detaches hangar/exchange/blueprint.
- PSA-01/JAVA-07 CONFIRMED (javac 25, class + interface); same code compiles in one package or a named
  module; nothing relies on the sealing (no pattern switch over it, no reflection) -> restructuring cheap.
- FE-04/RES-05 CONFIRMED: WebClientAdapter uses the given instance; only `webClient` bean has the full
  chain (WebClientConfig:456-489; terms/SSE/live-sync beans each lack some filters); group registry calls
  WebClient.builder() per group unless a configurer supplies one; spring-boot-webclient is NOT resolved
  (not in verification-metadata) -> Boot customizers irrelevant unless that module is added.
- URI param CONFIRMED + WIDER: UrlArgumentResolver registered by default; DefaultWebClient uses the URI as
  given ignoring base URL; OAuth2 filter attaches member token with NO host check; also via UriBuilderFactory
  param or an ABSOLUTE URL in the annotation; same exposure exists TODAY for any absolute URI passed to
  webClient -> guards: ArchUnit ban (URI, UriBuilderFactory params, absolute annotation URLs) + a FILTER on
  the webClient that refuses any request not addressed to the backend host.
- MB-01 CONFIRMED: none of 198 contract paths in ExternalContractTest under /api/v1/exchange/**; the two
  draft ops reach 12 web DTOs frozen incidentally via Android ops (bodies never reach the client); no
  backend/frontend test uses a JSON schema validator; all 10 schema tests in ingest.
- ArchUnit counts NARROWED: default fails empty selection (bytecode), no archunit.properties. Consistent
  totals: whole-domain moves 30 silent / 12 loud / 1 stronger; partial moves 33 / 9 / 1. July count closer
  (off by one); XC off by four (misclassified :1763 not package-dependent, :1853 fails when a named class
  moves = never vacuous). peerReadable selects 22 endpoints today (not ~23) -> 12 can move unnoticed.
  -> plan: "30 to 33 of 43 rules can pass silently".
- API-40 NARROWED + one link REFUTED: binding chain confirmed (application.yml:172-175; compose
  :213-215; backend.env.tmpl:12; Quadlet loads rendered file at start; record bound at startup). REFUTED
  "deploy.sh renders env.d every tick": only when config bundle changes, on --reapply, or on a host with
  no units; a no-change tick exits before rendering. NARROWED: a rendering deploy restarts backend only if
  its unit file or image pin changed; a health restart runs on the stale file. -> Real procedure = runbook
  step S8 (docs/EXCHANGE_GO_LIVE_RUNBOOK.md:533-559): set value, render, restart backend (prod write,
  owner approval, ~1 min web+app outage). RAISE THE FLOOR ONLY AFTER the re-cut backend is verified
  healthy: if the floor rode the release deploy and it rolled back, the old backend would come back with
  the raised floor -> no app version works. [Coordinator: better option = release-bound floor (default in
  the release's config, rolls back with it; .env only as emergency override) -> O-07.]
- DOM-08 CONFIRMED 3/3: job-order handover (pessimistic row lock + validates vs earmarked amount; single
  bulk delete after loop; completion MANDATORY; audit same tx; after-commit would allow double
  consumption); org-chart mirror (9 methods MANDATORY, 12 call sites; REQ-ROLE-006 same tx; no reconcile
  job -> permanent chart drift after commit); offer ratchet (4 methods MANDATORY; beforeDelete must run
  before ON DELETE CASCADE removes offers; after-commit loses removal audit rows, offers above stock).

- To-verify list for research: isolated projects, verification-metadata + included builds, catalog in
  precompiled plugins, runner size, JPMS/Boot4, Flyway multi-jar, testFixtures not in bootJar,
  Modulith @ApplicationModuleTest on Boot 4.1, ArchUnit failOnEmptyShould default, Jackson 3
  FAIL_ON_UNKNOWN_PROPERTIES default in Boot 4.1.
