"""Per-finding re-evaluation data for the 76 Backend/Ingest/Keycloak/Build findings of the
2026-09-22 Improvement Audit. Path shorthand: 'be:' = backend/src/main/java/de/greluc/krt/profit/basetool/backend/,
'be-test:' = backend/src/test/java/de/greluc/krt/profit/basetool/backend/, 'in:' = ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/,
'in-test:' = ingest/src/test/java/de/greluc/krt/profit/basetool/ingest/, 'fe:' = frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/,
'kc:' = keycloak-spi/src/main/java/de/greluc/krt/profit/basetool/keycloak/spi/. Other paths are repository-relative.
"""

E = {}

E["APPSEC-01"] = dict(
    status="DONE",
    evidence="#1989 (3340c1211). be:service/JobOrderItemProductionService.java:139 calls assertMayBookInFor, :321-334 (foreign owner needs OwnerScopeService.canManageUserInventory before any lookup; personal=true for someone else refused). Guards be-test:.../JobOrderItemProductionServiceTest, JobOrderProductionBookInSecurityTest (MockMvc 403).",
    verdict="ADJUSTED", prio_new="P2 (inventory module step)",
    reasoning="The rule is right and stays. It is an inventory rule (who may write into whose ledger) that now lives as a copy in three domains: be:service/InventoryItemService.java:446-453, be:service/JobOrderItemProductionService.java:327-334 and be:service/RefineryOrderService.java:592; the job-order service also writes InventoryItem rows through repository.InventoryItemRepository itself (jdeps-backend.txt). #1989 created no new edge (JobOrderItemProductionService already referenced OwnerScopeService 3 times before, git show 3340c1211^1), but it added a third copy of the rule. Target: the inventory module exposes one booking command that performs the on-behalf and personal checks itself; job order, refinery and exchange call it and drop their copies.",
    domain_effect="hinders (foreign-domain rule duplicated in 3 services; cross-domain repository writes pre-existing)",
    security_note="Moving the check into an inventory API must keep it before any lookup (no existence oracle, REQ-INV-032) and keep JobOrderProductionBookInSecurityTest; add a rule that only the inventory module writes InventoryItemRepository. be:service/exchange/ExchangeStockWriteService.java:797-808 also writes personal rows directly (self-only per REQ-XCH-009) and should use the same API.",
)

E["APPSEC-02"] = dict(
    status="PARTIAL",
    evidence="#1985 (160e0dba4): be:service/RefineryOrderService.java:349-359 resolveMissionForOwner throws MissionParticipantRequiredException; REQ-SEC-042 extended; tests RefineryOrderServiceLifecycleTest, RefineryOrderTest. Remaining: the mission picker still lists every recent mission, fe:controller/RefineryOrderPageController.java:814 fetches /api/v1/missions?size=1000 unfiltered (deferred in #1985's 'Not in this PR').",
    verdict="ADJUSTED", prio_new="P2 (re-home, mission module step); picker P3",
    reasoning="Server-side rule complete; the security gap is closed. The implementation added a new cross-domain repository edge, RefineryOrderService -> MissionParticipantRepository (0 references before #1985; 4 non-mission classes now use that repository: InventoryCheckoutService, JobTypeService, RefineryOrderService, UserDeletionService). It also grew the central error kernel: MissionParticipantRequiredException joined the sealed AppException permits (be:exception/AppException.java:37-50) and MISSION_PARTICIPANT_REQUIRED the shared AppExceptionKind enum (be:exception/AppExceptionKind.java:121). A sealed class in the unnamed module cannot permit a class in another package (javac 25: compiler.err.class.in.unnamed.module.cant.extend.sealed.in.diff.package, scratchpad 80-prev-sept-a-sealed), so this shape blocks package-per-domain (see PSA-01). Target: a mission query port (isParticipant(missionId, userId); participatingMissions(userId, since)) that also serves the still-open picker filter.",
    domain_effect="hinders (new refinery -> mission repository edge; central error enum grew)",
    security_note="The server check stays authoritative (create, changed mission, on-behalf variants; unlink free). The picker filter is UX only and must not replace it.",
)

E["BE-SIMP-02"] = dict(
    status="DONE",
    evidence="#1994 (df111eb1a) migrated the frontend, #1996 (5ab7ff01e) deleted the 17 handlers; no @ApiDeprecation left in MissionController (remaining: be:controller/HangarController.java:295 sunset 2027-05-14, be:controller/SystemController.java:59 sunset 2026-12-31). Guard frontend/src/test/.../contract/DeprecatedBackendEndpointCallGuardTest.java; 553600bac added the manager-only add-by-id path for the app.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Correct and complete. MissionController is still the largest backend controller (1516 lines, 47 handler mappings, wc/grep); splitting it by sub-resource belongs to the mission module step, not to this finding. The deprecation guard reads openapi.json, so it survives package moves.",
    domain_effect="helps (smaller mission API surface to carve out)",
    security_note="Surface shrank; edge allow-list updated in #1996. Next sunset /system/ping 2026-12-31 is covered by the same guard.",
)

E["BE-SIMP-03"] = dict(
    status="DONE",
    evidence="#1994: be:model/dto/MissionDto.java:74 ownershipVersion; be:model/Mission.java:264 (@Formula); be:service/MissionService.java:1172-1174; frontend mission-detail.html:930 data-ownership-version, mission-detail.js:2124-2141; tests MissionOwnershipVersionIntegrationTest, MissionOwnerChangeControllerTest, MissionOwnerChangeE2eTest.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Implements the per-section lock of ADR-0080 entirely inside the mission domain. be:support/MissionPeerRedactor.java:108 passes the new field through its explicit constructor, i.e. the July audit's exhaustiveness safety net did its job.",
    domain_effect="neutral",
    security_note="No authorization change; REQ-MISSION-017 holds.",
)

E["SEC-16"] = dict(
    status="DONE",
    evidence="#2019 (b49547283): config/owasp/dependency-check-suppressions.xml, 9 of 9 <suppress> carry until=\"2026-12-22Z\" (grep); reasons in <notes> elements. The header comment that described the renewal was removed by the ADR-0214 sweep (#2074, e929a3a81): 0 '<!--' in the file, and no doc names the renewal procedure (grep docs/, CONTRIBUTING.md).",
    verdict="ADJUSTED", prio_new="P3",
    reasoning="Expiry is right. All nine expire on one day, so the weekly scan turns red at once on 2026-12-22 by design. Under ADR-0214 the renewal procedure must live in a document (CONTRIBUTING, dependency-check section) instead of the deleted header; the vault still claims the header describes it (Security.md:880-881).",
    domain_effect="neutral",
    security_note="Renewal must re-verify each false positive, never bump the date blindly.",
)

E["SEC-17"] = dict(
    status="DONE",
    evidence="#2019: application-test.yml only under {backend,frontend,ingest}/src/test/resources; jar/bootJar gate build.gradle.kts:154-175 inside subprojects { plugins.withId(\"java\") }.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="The gate is applied by plugin id in the root build, so every future domain subproject (option B/C) inherits it.",
    domain_effect="helps (inherited by new subprojects)",
    security_note="Keeps test profiles and test credentials out of shipped jars; keep the gate when build logic moves to convention plugins.",
)

E["APPSEC-08"] = dict(
    status="DONE",
    evidence="#1989: be:config/JwtAudienceStartupCheck.java:44-76 (prod profile + blank expected-audiences -> IllegalStateException at startup; INFO log of accepted audiences); be-test:config/JwtAudienceStartupCheckTest.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Fail-closed startup gate. The property is still read twice (be:config/SecurityConfig.java:124 @Value field and the startup check); a typed JWT properties record would give one source, cosmetic. Ingest has no startup gate, but every remaining authenticated ingest route checks aud=basetool-ingest itself (in:exchange/ExchangeTokenGateFilter.java:59,116-117).",
    domain_effect="neutral",
    security_note="Must stay; any configuration refactor keeps the prod fail-closed behaviour and its test.",
)

E["APPSEC-09"] = dict(
    status="DONE",
    evidence="#1989 rewrote the comments; the ADR-0214 sweep (#2074) then removed all comments: backend/src/main/resources/application.yml has 0 comment lines; the facts live in be:config/ManagementPortSecurityConfig.java:34 Javadoc and ADR-0134; guard ManagementPortIsolationTest.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="The class of misleading security comments is structurally gone under ADR-0214.",
    domain_effect="neutral",
    security_note="ManagementPortIsolationTest remains the guard for the @Order(0) management chain.",
)

E["BE-MOD-03"] = dict(
    status="DONE",
    evidence="#1989: be:support/AuditRetentionProperties.java:43-48 (@DurationMin days=30), be:support/NotificationRetentionProperties.java:42-56 (+ @AssertTrue unread >= read), be:support/RejectedRegistrationRetentionProperties.java:41-46; be-test:config/BackendPropertiesValidationTest.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Correct fix. Placement note: the three records are domain-specific (audit, notification, identity) yet sit in the shared support package, where 18 of the 27 backend @ConfigurationProperties records live (9 in config). They move with their modules; a pure move, no behaviour change.",
    domain_effect="neutral (adds to the support hub, see PSA-04)",
    security_note="The floors protect REQ-AUDIT-006/REQ-NOTIF-009/REQ-SEC-057; a move keeps @Validated, property names and env vars.",
)

E["ING-SEC-01"] = dict(
    status="DONE",
    evidence="#1990: scripts/check-ingest-audience.py + .github/workflows/repo-lint.yml:152-158 (self-test and check); SecurityConfigTest asserts basetool-ingest. The config comment itself was later removed by ADR-0214 (#2074).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="The lint outlives the comment. Since #2270 removed the legacy /v1 routes, every authenticated ingest route is an exchange route whose token gate hard-codes aud=basetool-ingest (in:exchange/ExchangeTokenGateFilter.java:59,116-117), so a wrong env value is far less dangerous than on 2026-08-21.",
    domain_effect="neutral",
    security_note="Keep the lint; it also covers env templates and runbooks.",
)

E["ING-SEC-02"] = dict(
    status="DONE",
    evidence="#1990 (handler branch + CachedToken). After #2270 removed BackendImportClient the invariant lives in in:exchange/ExchangeRelay.java:395-437 (401/403 without a client-visible code -> tokenProvider.invalidate(), 502 BACKEND_RELAY_FAILED) and in:service/ServiceAccountTokenProvider.java:59,85,88,264 (volatile record CachedToken, 5 s failure backoff).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Carried correctly into the exchange relay: the gateway's own identity failure is told apart from a member or client refusal.",
    domain_effect="neutral",
    security_note="No token or backend detail reaches the client; codes the client may see keep the cached token.",
)

E["ING-PERF-01"] = dict(
    status="SUPERSEDED",
    evidence="By ING-MOD-01 (#2008, cc3c5d090; skipped on owner decision in #1990): ingest has no Reactor Netty client; in:config/RestClientConfig.java:142-144 JDK HttpClient HTTP/1.1; in-test:config/RelayIdleConnectionBoundTest pins the JDK keep-alive (30 s) below Tomcat's 60 s.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="The problem class disappeared with the reactive stack; the decision to skip it was right.",
    domain_effect="neutral",
    security_note="None.",
)

E["ING-SEC-05"] = dict(
    status="DONE",
    evidence="#1990 introduced in-test:filter/IngestEndpointSurfaceTest; after #2270 it pins exactly 16 exchange routes and asserts no /v1 mapping (IngestEndpointSurfaceTest.java:50-66, 123-124). The ClientIdentityFilter non-JWT refusal left with the legacy routes; on exchange routes a non-JWT principal gets no ExchangeRequestContext (in:exchange/ExchangeGateFilter.java:118-121) and every controller answers failed() without one (in:web/ExchangeController.java:175-177).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Pinning the routed surface is the right invariant for an internet-facing gateway and survived the exchange rework. The non-JWT case is still fail-closed, only no longer counted.",
    domain_effect="neutral",
    security_note="Optional: count 'authenticated but not a JWT' on exchange routes again, to keep the detection signal the vault describes (Ingest.md:297-300, now historical).",
)

E["ING-SIMP-01"] = dict(
    status="DONE",
    evidence="#1990: in:filter/CorrelationIdFilter.java:51 (+10), BotProtectionFilter.java:67 (+12), RequestLoggingFilter.java:54 (+15), RateLimitingFilter.java:66 (+20), PayloadSizeLimitFilter.java:62 (+30); in:filter/CachedBodyRequest.java:63 read(byte[],int,int); in-test:filter/FilterOrderTest.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Rate limit runs before body buffering; unaffected by the exchange rework (the filters are scoped to /exchange since #2270).",
    domain_effect="neutral",
    security_note="The order is a DoS control; FilterOrderTest reads Boot's real registration order.",
)

E["ING-SIMP-03"] = dict(
    status="DONE",
    evidence="#1990: in:web/Problems.java:57-63 single builder reading the MDC key from in:config/LoggingProperties.java:46; the only literal 'correlationId' left is the JSON member name (Problems.java:42).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["ING-PERF-02"] = dict(
    status="DONE",
    evidence="in:service/HandoffStagingService.java:207-214 uses the RPUSH answer; consume() lives in in-test:service/HandoffStagingServiceTest (#1990).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["KC-CI-01"] = dict(
    status="DONE",
    evidence="#1990: keycloak-spi/build.gradle.kts:4,7 (jacoco, spotbugs.base) -> spotbugsMain registered by build.gradle.kts:419-439; floors build.gradle.kts:233,240 (0.66/0.60); --release 21 kept (keycloak-spi/build.gradle.kts:13).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete. The floor map is keyed on project.name (build.gradle.kts:228-241), which matters for a later Gradle split (see BLD-SIMP-06), not for this module.",
    domain_effect="neutral",
    security_note="FindSecBugs now covers the login gate.",
)

E["KC-PERF-01"] = dict(
    status="DONE",
    evidence="kc:DiscordGuildRoleGateAuthenticator.java:117-125,160 (one member lookup, nickname from the same body); keycloak-spi test DiscordGuildRoleGateAuthenticatorTest#makesExactlyOneDiscordCallPerFirstLogin_andTakesTheNicknameFromIt. A first login costs 2 Discord calls (the IdP's own read is kept deliberately, #1990).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral",
    security_note="Fewer calls lower the chance that the fail-closed gate denies a login on HTTP 429.",
)

E["THEME-SEC-01"] = dict(
    status="DONE",
    evidence="keycloak-theme/krt-theme/login/login.ftl:13,15 autocomplete=\"username\", :21 autocomplete=\"current-password\", :27 remember-me ticked only when login.rememberMe is set, :40 credentialId; REQ-SEC-066 (#1990).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; the remember-me default was an owner decision (2026-09-22).",
    domain_effect="neutral",
    security_note="Password managers work again; a shared device is no longer remembered by default.",
)

E["BLD-PERF-01"] = dict(
    status="DONE",
    evidence="#2029 (6f419c55e): docker/app/Dockerfile:30 runs ./gradlew :${MODULE}:bootJar; .dockerignore:47 **/src/test; the three per-module Dockerfiles and the lint-exclusion guard are gone.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete. For a Gradle split see IMG-SIMP-14: the builder stage copies only ${MODULE}/src/main and logging-support/src/main (Dockerfile:26-27).",
    domain_effect="neutral",
    security_note="Test sources no longer enter the build context.",
)

E["BLD-PERF-02"] = dict(
    status="DONE",
    evidence="#2019: backend/build.gradle.kts:3-15 plugins without 'application'; no withSourcesJar (grep).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["BLD-PERF-07"] = dict(
    status="DONE",
    evidence="#2019: frontend/build.gradle.kts:94 normalization { runtimeClasspath { ignore(\"META-INF/build-info.properties\") } }.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["BLD-CI-09"] = dict(
    status="DONE",
    evidence="#2019: .github/workflows/ci.yml:65-69 git diff --exit-code on */src/main/resources/api/openapi.json; backend/src/test/resources/application-test.yml:85 writer-with-order-by-keys. Only the backend still has an openapi.json (ingest springdoc removed in dc549e0e0).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="A reviewed, byte-stable contract file is the API-stability gate the modular target needs; any future per-module API document should join the same gate.",
    domain_effect="helps (contract stability)",
    security_note="An unreviewed endpoint appearing in the contract becomes visible in review.",
)

E["BLD-PERF-10"] = dict(
    status="DONE",
    evidence="#2019: build.gradle.kts:138-148 mockitoAgent configuration + CommandLineArgumentProvider; PIT uses it (:273-280).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; configuration-cache clean.", domain_effect="neutral", security_note="None.",
)

E["TST-18"] = dict(
    status="DONE",
    evidence="#2019: test-support/src/main/java/.../containers/TestImages.java:39-40 equals docker-compose.yml:243 (redis:8-alpine@sha256:3811...); 12 test classes use TestImages.REDIS; TestImagesTest asserts parity with compose and the Quadlet unit.",
    verdict="ADJUSTED", prio_new="P3",
    reasoning="The same drift class exists for PostgreSQL: the Testcontainers JDBC URL uses the floating tag postgres:18-alpine (backend/src/test/resources/application-test.yml:7) while compose pins a digest (docker-compose.yml:8,55). Extending TestImages to Postgres needs either a tc: URL that accepts a digest (UNKNOWN, settle with the Testcontainers JDBC-URL documentation) or a PostgreSQLContainer bean.",
    domain_effect="neutral",
    security_note="Tests exercise the same image digest that production runs.",
)

E["TST-19"] = dict(
    status="DONE",
    evidence="#2019: backend/src/test/resources/application-test.yml:7 jdbc:tc:postgresql:18-alpine:///testdb?TC_DAEMON=true.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["TS-SIMP-01"] = dict(
    status="DONE",
    evidence="#1990: test-support/build.gradle.kts:22-24 without platform(libs.junit.bom); only keycloak-spi keeps the pin (keycloak-spi/build.gradle.kts:26).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["BE-PERF-02"] = dict(
    status="DONE",
    evidence="#2004 (ccc886d28): backend/src/main/resources/application.yml:72 and backend/src/test/resources/application-test.yml:20 fail_on_pagination_over_collection_fetch: true; be-test PagedFindersNoCollectionFetchTest.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="A global Hibernate guard; survives any package move.", domain_effect="neutral", security_note="None.",
)

E["BE-PERF-03"] = dict(
    status="DONE",
    evidence="#2004: be:repository/MissionRepository.java:335-338 (@Lock OPTIMISTIC_FORCE_INCREMENT + @EntityGraph participants only); units via fetchAssignedUnitGraph (:120).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Mission-internal; keeps the force-increment replacement guard.", domain_effect="neutral",
    security_note="None; the exactly-one-winner lock is kept.",
)

E["BE-PERF-10"] = dict(
    status="DONE",
    evidence="#2004: backend/src/main/resources/db/migration/V245__index_every_uncovered_foreign_key.sql (38 FKs); be-test:db/ForeignKeyIndexCoverageTest sweeps pg_constraint/pg_index and also covers the later V246-V258.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="A schema-level invariant independent of the code layout. If cross-domain JPA associations become plain UUID columns but keep their database FK constraints, the gate still applies.",
    domain_effect="helps (layout-independent gate)",
    security_note="None.",
)

E["BE-SIMP-06"] = dict(
    status="DONE",
    evidence="#2011 (plus 2d9b49f11/674e55bd7 for clampOffersToStock and the spec lines): none of the five names in backend main (grep); be:service/MissionService.java:115-118 getAllMissions tripwire kept deliberately.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete. The getAllMissions tripwire becomes unnecessary once module boundaries forbid foreign access to the mission repository; revisit in the mission module step.",
    domain_effect="neutral",
    security_note="The tripwire protects the staffel scope filter until a boundary rule does.",
)

E["BE-MOD-02"] = dict(
    status="PARTIAL",
    evidence="#2008: be:service/KeycloakService.java:100,112-127 builds one adminClient from the observed prototype builder (be:config/RestClientConfig.java:73-97: 5 s connect, 30 s read, HTTP/1.1). But where the keycloak-trust bundle exists (prod) KeycloakService.java:122-125 swaps in be:config/KeycloakTrustSupport.java:74-75, HttpClient.newBuilder().sslContext(...).build() + new JdkClientHttpRequestFactory(httpClient) with no connect or read timeout. The same factory backs the internal JWKS fetch (be:config/SecurityConfig.java:177-181), on in production since 2026-09-25 (#2038, vault Backend.md:398-411). The vault notes the missing read timeout (Backend.md:362-363).",
    verdict="ADJUSTED", prio_new="P1",
    reasoning="The observation half is done. The pinned client silently drops the builder's timeouts, so a hung Keycloak blocks the sync job and every JWKS refresh on the authentication path until the JDK/OS defaults (UNKNOWN exact value; settle with the java.net.http.HttpClient Javadoc). Fix: KeycloakTrustSupport takes the same connect/read timeouts and HTTP/1.1 as RestClientConfig (the ingest copy, in:config/KeycloakTrustSupport.java:73-74, has the same shape but is not used for JWKS in prod).",
    domain_effect="neutral (platform)",
    security_note="Availability on the authentication path; keep the pinned trust and hostname verification unchanged; add a test that asserts the pinned factory's timeouts.",
)

E["BE-MOD-05"] = dict(
    status="DONE",
    evidence="#2011: be:mapper/CentralMapperConfig.java:33-36 injectionStrategy CONSTRUCTOR; 49 of 51 mapper files use the config (P4kImportJobMapper is hand-written by design).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Constructor injection turns cross-domain mapper 'uses' into explicit constructor edges that jdeps, ArchUnit and a module verifier can see.",
    domain_effect="helps (explicit edges)",
    security_note="None.",
)

E["BE-SIMP-07"] = dict(
    status="DONE",
    evidence="#2011: be:controller/HangarController.java:146 isAdminOrOfficer(), OperationController.java:454, SquadronController.java:83, SpecialCommandController.java:105 isAdmin(); no 'ROLE_ADMIN' literal outside AuthHelperService Javadoc and support/Roles.java; be-test:controller/RoleGateFixture-driven tests.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; AuthHelperService is shared security kernel.",
    domain_effect="neutral",
    security_note="The role hierarchy is honoured; RoleGateFixture proves equivalence for every caller shape.",
)

E["BE-SIMP-08"] = dict(
    status="REGRESSED",
    evidence="#2011 left one implementation (be:support/StringNormalization.java:82); a new copy blankToNull appeared in be:service/exchange/ExchangeRegistryService.java:413-416 (a2e82ff34, 2026-09-27).",
    verdict="CONFIRMED", prio_new="P3",
    reasoning="Trivial; fold the copy into StringNormalization. A dedicated gate is not worth it. StringNormalization belongs to the shared kernel of the target.",
    domain_effect="neutral",
    security_note="None.",
)

E["BE-MOD-06"] = dict(
    status="DONE",
    evidence="#2011: 0 '.get(0)' in backend main (grep); be:config/SecurityConfig.java:308 env.matchesProfiles(\"test\"); be:service/SseSendFailureCause (pattern-matching switch) used by LiveSyncStreamService and NotificationStreamService.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; every idiom used is a final Java feature (ADR-0223).", domain_effect="neutral", security_note="None.",
)

E["BE-SIMP-10"] = dict(
    status="PARTIAL",
    evidence="Script 80-prev-sept-a-fqn.py: backend main 19 inline FQNs left in 10 files, mostly deliberate jakarta vs JetBrains @NotNull clashes (be:controller/UserController.java:728-815); frontend main 482 in 42 files (InventoryPageController 82, JobOrderWriteController 71, InventoryWriteController 62); tests 1214 (backend) + 1051 (frontend), excluded by #2011.",
    verdict="ADJUSTED", prio_new="P3",
    reasoning="Backend half done (#2011, 659 names). The frontend half never ran. Run OpenRewrite ShortenFullyQualifiedTypeReferences on each frontend package right before it moves into its domain package, as a separate commit, so the move diff stays reviewable.",
    domain_effect="neutral",
    security_note="None.",
)

E["DOC-20"] = dict(
    status="DONE",
    evidence="#2019 removed versions.properties and the Dockerfile COPYs; build scripts and the catalog carry 0 comment lines after ADR-0214 (#2074; grep).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; stale build comments cannot recur under ADR-0214.", domain_effect="neutral", security_note="None.",
)

E["DOC-21"] = dict(
    status="DONE",
    evidence="674e55bd7 (2026-09-22); no pm.me in any CLAUDE.md, SECURITY.md or CONTRIBUTING.md; the only hit is the historical CHANGELOG.md:2625 entry recording the switch.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="The public security contact is the current address.",
)

E["BE-PERF-01"] = dict(
    status="DONE",
    evidence="#2004: be:mapper/UserMapper.java:70-78 request memos, :155 primeStaffelMemberships; called from be:controller/HangarController.java:125, MemberEvaluationController.java:183, UserController.java:144,308, be:mapper/JobOrderMapper.java:115,134, MissionMapper.java:99; be-test:mapper/UserMappingNoNPlusOneTest.",
    verdict="ADJUSTED", prio_new="P2 (identity module step)",
    reasoning="Performance goal met. The mechanism spreads identity internals: 16 classes depend on UserMapper, #2004 added the edge HangarController -> UserMapper (0 references before), and controllers of other domains must remember to prime an identity mapper's request memo before mapping. The identity mapper also resolves Staffel memberships (org-unit knowledge, support/StaffelMembershipResolver). Target: domains embed a small member reference (id, display name) resolved through one identity batch query that memoises internally, so no caller primes anything.",
    domain_effect="hinders (identity mapper as a cross-domain hub; new hangar edge)",
    security_note="UserDto carries PII; a thinner member reference reduces what other domains can leak. UserDtoRedaction and MissionPeerRedactor semantics must be preserved.",
)

E["BE-PERF-04"] = dict(
    status="DONE",
    evidence="#2009 (d90dc295c): backend/src/main/resources/application.yml:63 reWriteBatchedInserts, :70 batch_size 50, :73 in_clause_parameter_padding; order_inserts deliberately off (docs/specs/data-persistence.md:67-69); NotificationFanOutBatchingTest.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="The reason order_inserts stays off (Hibernate orders inserts only by associations it knows; org_unit_membership references its org unit through a plain id) grows stronger once cross-domain references become ids. Nothing pins it off except the spec; a one-line configuration assertion would guard it before the id-reference migration.",
    domain_effect="neutral (a constraint the id-reference migration must respect)",
    security_note="None.",
)

E["BE-PERF-09"] = dict(
    status="DONE",
    evidence="#2009: Propagation.NOT_SUPPORTED on the UEX sync entry points (e.g. be:service/UexCommodityService.java:81, UexItemSyncService.java:104, UexUniverseSyncService.java:106); be:service/SyncChunkWriter.java (REQUIRES_NEW chunks, row replay), be:service/UexMatrixLookups.java.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Catalogue-internal. SyncChunkWriter is generic and can move to the shared kernel.", domain_effect="neutral",
    security_note="None.",
)

E["BE-PERF-12"] = dict(
    status="PARTIAL",
    evidence="#2004 added UserRepository.findPlainById (22 call sites) but the default findById stays graphed (be:repository/UserRepository.java:302-305, roles + roles.permissions). Reference-only callers still use it: be:service/JobOrderItemProductionService.java:371 and, added after the fix by the exchange epic, be:service/exchange/ExchangeAccountCheckService.java:68 (reads rsiHandle) and be:service/exchange/ExchangeStockWriteService.java:798 (FK target). 44 findById call sites in total.",
    verdict="ADJUSTED", prio_new="P2",
    reasoning="The expensive variant carries the default name, so new code regresses by default. Invert it: a plain findById and an explicitly named graphed lookup for authentication and /users/me. The identity module step completes it: other domains get a member-reference port, never UserRepository (47 non-repository classes use it today, grep).",
    domain_effect="hinders (47 cross-domain users of UserRepository)",
    security_note="Authentication must keep loading roles and permissions inside its transaction (FirstLoginAuthoritiesIntegrationTest).",
)

E["BE-PERF-15"] = dict(
    status="DONE",
    evidence="#2004: be:service/HangarImportService.java:129 grouped per-type count; be:service/OrgRoleManagementSecurityService.java:60-61,277-282 caller-membership memo; OrgUnitBankResponsibilityService single statement.",
    verdict="ADJUSTED", prio_new="P3",
    reasoning="The N+1s are gone, but the fix added a second memo of a fact RequestScopeResolver already memoised: OrgRoleManagementSecurityService.CALLER_MEMBERSHIPS (0 references before #2004) next to RequestScopeResolver.CACHE_KEY_CALLER_MEMBERSHIPS (present before #2004). Fold into one org-unit-owned query (see BE-SIMP-09).",
    domain_effect="hinders slightly (duplicate memo of an org-unit fact)",
    security_note="The memo feeds appointment authorization; consolidation must stay request-scoped.",
)

E["BE-SIMP-01"] = dict(
    status="DONE",
    evidence="#2011 + #2015 (ba54a2c31): 337 Entities.require calls; be-test:exception/EntitiesRequireRatchetTest.java:48 CEILING 0, :77 floor > 200; only be:exception/Entities.java:49,64 still write orElseThrow(NotFoundException).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete. The ratchet scans only backend/src/main/java; under a Gradle split (option B/C) every new backend subproject must join the scan or its code escapes it.",
    domain_effect="neutral",
    security_note="Uniform 404 without leaking entity detail.",
)

E["BE-SIMP-04"] = dict(
    status="DONE",
    evidence="#1994: be:service/ParticipantTargetResolver.java (resolve() :76-96) used by be:controller/MissionController.java:143 and be:service/MissionParticipantService.java:98,144; be-test ParticipantTargetResolverTest; the ambiguous-name 500 became a 409.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Already the seam the target wants. At the mission module step it moves into mission internals; its identity dependency (UserService.findMatchesByExactName returning List<User> entities) should narrow to an id-returning identity query.",
    domain_effect="helps (one mission-internal component)",
    security_note="The self-vs-manager AccessDenied check stays in the controller (H-1).",
)

E["BE-SIMP-05"] = dict(
    status="DONE",
    evidence="#1994 + #1996: six private redactForPeer overloads at be:controller/MissionController.java:1446-1512 with 21 call sites; no inline isLogisticianOrAbove block in a handler; be-test ArchitectureTest.java:1129-1162 peerReadableMissionEndpointsMustRedactPii (floor >= 10 at :1144), selection keyed on package '.backend.controller' (:1132, :1149) and hard-coded DTO names (:1170-1174).",
    verdict="SUPERSEDED-BY-MODULARISATION", prio_new="P2 (mission module step)",
    reasoning="The helper is done, but redaction still happens per handler in the web layer, guarded by a structural rule. A package move can silently de-select handlers as long as ten remain selected. In the target the mission module's read API returns viewer-specific views (redaction applied inside the module, still through MissionPeerRedactor's explicit full-field constructor), so no web adapter can forget it, and the rule becomes a module-internal check.",
    domain_effect="neutral now; regression risk during the move",
    security_note="REQ-SEC-007: re-key the ArchUnit selection (package and DTO names) in the same commit as any mission package move; keep MissionDataLeakTest; keep the explicit constructor (July audit).",
)

E["BE-SIMP-09"] = dict(
    status="DONE",
    evidence="#2011: be:support/RequestMemo.java typed Key<T>; keys are private to their owners (be:mapper/UserMapper.java:70-78, be:service/OrgRoleManagementSecurityService.java:60-61, be:service/OrgUnitCascadeService.java:64-66, be:service/RequestScopeResolver.java:72-102); RequestMemoTest.",
    verdict="ADJUSTED", prio_new="P2 (org-unit module step)",
    reasoning="The mechanism is right and created no central key registry. Ownership is not: the caller's org-unit memberships are memoised three times under different keys (RequestScopeResolver.java:94-95/282-286, OrgRoleManagementSecurityService.java:60-61/277-282, UserMapper.java:70-71), each calling membershipRepository.findAllByIdUserId, so one request can read them up to three times. RequestScopeResolver also memoises a job-order decision (canViewJobOrders, :101-102, 312-331) that reaches SpEL as @ownerScopeService.canViewJobOrders(). Target: the org-unit module owns one memoised caller-memberships query; the job-order module owns canViewJobOrders.",
    domain_effect="neutral (mechanism); hinders (ownership)",
    security_note="These memos feed scope predicates and authorization; consolidation must stay request-scoped, keep the admin-pin semantics, and keep OwnerScopeServiceTest and the RequestScopeResolver tests green.",
)

E["BE-SIMP-11"] = dict(
    status="DONE",
    evidence="#2011: be:service/RedisJsonFanout.java:42 (package-private final class, composition) used by be:service/RedisLiveSyncFanout.java:49,69 and be:service/RedisNotificationFanout.java:56,76.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Correct extraction (transport only, no template). Package-private placement works only while both fan-outs share the service package; when notification (a domain) and live sync (a platform concern) separate, it becomes a public class in shared infrastructure. A pure re-home at that step.",
    domain_effect="neutral",
    security_note="Echo-origin filter and the backend's Redis ACL channels (scripts/redis-users.acl.tmpl:5) unchanged.",
)

E["BE-MOD-01"] = dict(
    status="DONE",
    evidence="#2008 (ADR-0204): no webflux/reactor in backend/build.gradle.kts (grep); be:config/RestClientConfig.java:73-97; UexClient/ScWikiClient use exchange() (304/ETag unchanged).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; SBOM 209 -> 188 components (#2008).", domain_effect="neutral",
    security_note="ResponseSizeLimitInterceptor keeps the 16 MiB cap; see BE-MOD-02 for the pinned-client timeout gap.",
)

E["BE-MOD-04"] = dict(
    status="DONE",
    evidence="#2011: all 27 backend @ConfigurationProperties classes are records (script over grep -rl @ConfigurationProperties); credential holders redact toString (e.g. be:config/MonitoringScrapeProperties.java:53-59).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete and held through the exchange epic (16 -> 27 records). Placement: 18 of 27 sit in support and move with their modules. Seven @Value fields remain (e.g. be:config/SecurityConfig.java:117,124; be:service/UserRegistrationService.java:84), outside this finding's scope.",
    domain_effect="neutral (placement, see PSA-04)",
    security_note="Credential-bearing records redact toString.",
)

E["BE-MOD-05b"] = dict(
    status="DONE",
    evidence="be:mapper/CentralMapperConfig.java:36 unmappedTargetPolicy ERROR; #2015 removed the last method-level exemption (MaterialMapper.toEntity); the stricter policy surfaced the missing isMissionLead mapping (#2011).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="When domain DTOs are split into module API records, every unmapped field becomes a compile error instead of a silent null.",
    domain_effect="helps",
    security_note="Also prevents a new sensitive field from being copied by accident without a decision.",
)

E["BLD-PERF-03"] = dict(
    status="OPEN",
    evidence="No commit or vault row (git log --grep BLD-PERF-03: none). Today: backend 231 @SpringBootTest classes, 191 with @ActiveProfiles(\"test\") and 40 without, although every Gradle Test task sets spring.profiles.active=test (build.gradle.kts:186); frontend 161 classes, 42 with / 119 without; no shared meta-annotation, no spring.test.context.cache.maxSize (grep). Static approximation (80-prev-sept-a-contexts.py): about 49 distinct backend context keys, 35 of them with @MockitoBean sets; the September audit counted 38 contexts against a cache of 32.",
    verdict="ADJUSTED", prio_new="P1 (profile unification) / P2 (module-scoped test slices)",
    reasoning="Do the mechanical half now: one profile convention per module (add the annotation to the 40 backend classes that lack it, or drop it from the 191; the key must be uniform) and a shared mock set for the controller-security tests; measure before/after with the context-cache DEBUG log. Design the meta-annotations with the modularisation: module-scoped bootstraps (e.g. a Modulith-style module test) create one context per module by design, so size the cache to the module count instead of chasing a single shared context.",
    domain_effect="interacts (module-scoped tests reshape the context landscape)",
    security_note="Security tests must keep the real filter chain and the real @PreAuthorize beans; a @MockitoBean on a guarded bean strips its annotations.",
)

E["BLD-PERF-04"] = dict(
    status="PARTIAL",
    evidence="#2019: refreshVersions applied only with -PrefreshVersions (settings.gradle.kts:1-5); CI runs --configuration-cache (3 workflow invocations, grep); configureondemand and evaluationDependsOn gone. gradle.properties has no org.gradle.configuration-cache default ('left for after a soak', #2019).",
    verdict="CONFIRMED", prio_new="P3",
    reasoning="CI has run strictly with the cache since 2026-09-23; switch the default on for developer builds. More subprojects under option B/C raise configuration time, which the cache offsets.",
    domain_effect="helps option B/C",
    security_note="None.",
)

E["BLD-SIMP-06"] = dict(
    status="DONE",
    evidence="#2019: build.gradle.kts:122-442 subprojects { plugins.withId(...) } for toolchain, Lombok/JetBrains, test JVM, JaCoCo, PIT, Checkstyle, Spotless, CycloneDX, SpotBugs, licensee; settings.gradle.kts:8 FAIL_ON_PROJECT_REPOS.",
    verdict="ADJUSTED", prio_new="P2 (before any Gradle split)",
    reasoning="Conventions keyed on plugin id are inherited by a new domain subproject, which helps option B/C. But per-module values are keyed on project.name: test heap (build.gradle.kts:184) and JaCoCo floors (228-241: backend 0.82/0.65, anything unnamed 0.50/0.40). Code moved from backend into a new subproject would silently fall to the default floors. Before a split, move the logic into convention plugins in an included build (the Gradle-recommended structure: UNKNOWN here without web access, settle with docs.gradle.org 'Sharing build logic between subprojects') and make the floor an explicit per-project value whose absence fails the build.",
    domain_effect="helps, with one trap (silent floor drop)",
    security_note="A silently lowered coverage floor weakens a quality gate; make a missing value an error.",
)

E["BLD-PERF-08"] = dict(
    status="DONE",
    evidence="#2019: frontend/build.gradle.kts:333-385 (minifyStaticCss writes build/generated/minified-css, processResources copies it into static/css).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["IMG-PERF-12"] = dict(
    status="DONE",
    evidence="#2029/#2050: docker/app/Dockerfile:63-101 eager training refresh with per-module stubs (no lazy-initialization, grep), -Dspring.context.exit=onRefresh.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Constraint for the modularisation: any new bean that needs a live database or Redis at refresh (for example an event-publication registry that queries at startup; UNKNOWN for Spring Modulith's defaults) fails the image build with '[AOT] FAILED'. That is a guard, but plan stubs before introducing such a framework.",
    domain_effect="neutral (constraint)",
    security_note="Training runs with stubs only; no credential enters the image.",
)

E["IMG-CI-13"] = dict(
    status="DONE",
    evidence="#2029/#2050: docker/app/Dockerfile:105-117 AOTMode=on verification and the no-machine-code check; Loki rule JvmStartupCacheRejected (monitoring/loki/rules/fake/basetool-log-alerts.yml:34); scripts/check-object-layout-parity.py, scripts/check-loki-rule-signatures.py.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; both detection halves exist.", domain_effect="neutral", security_note="None.",
)

E["ING-SIMP-02"] = dict(
    status="DONE",
    evidence="#1990: in:ratelimit/RateLimitBuckets.java:61 newBucket used by in:filter/RateLimitingFilter.java:95 and in:exchange/ExchangeLimitFilter.java:234; in:config/RateLimitProperties.java:44-45 ip-capacity/ip-refill 120. The per-subject limiter left with the legacy routes (#2270); exchange budgets are per client and member.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; the shared factory also serves the exchange limiter.", domain_effect="neutral",
    security_note="CGNAT households no longer share one member's budget.",
)

E["ING-MOD-02"] = dict(
    status="PARTIAL",
    evidence="#1990: 7 of 8 ingest @ConfigurationProperties are records (IngestProperties, ServiceAccountProperties masking its secret, RateLimitProperties, LoggingProperties, three Exchange*Properties). in:config/MonitoringScrapeProperties.java:32-50 is still a Lombok @Data class holding the scrape password, so its generated toString prints it; the backend twin is a record that redacts it (be:config/MonitoringScrapeProperties.java:53-59); the frontend twin is @Data too. @Value field injection remains in in:logging/StartupBannerListener.java:61-71 and in:metrics/TracingEnabledMetric.java:52. expectedAudiences is a bean-method parameter (in:config/SecurityConfig.java:126), as asked.",
    verdict="REPRIORITISED", prio_new="P1",
    reasoning="The leftover is small and carries a credential, which makes it a P1 by the audit's own definition (small, low risk, clear value). No current log call prints the bean (grep), so the exposure is latent. It is also an instance of the hand-mirrored platform-class drift the July audit warned about (PSA-03).",
    domain_effect="neutral",
    security_note="Record with a redacting toString in ingest and frontend; no behaviour change.",
)

E["ING-SEC-03"] = dict(
    status="DONE",
    evidence="#1990: in:metrics/IngestGatePostureMetric.java:44,73-81 (now only gate=\"audience\": the azp/scope/tool gates left with #2270, and the owner decided on 2026-09-27 not to extend the gauge to the always-on exchange gates, vault Ingest.md:1192-1193); alert IngestAudienceGateOff monitoring/prometheus/alerts/business.yml:716 + promtool test; panel monitoring/grafana/dashboards/07-basetool-operations.json:3376. The production value itself is NOT-VERIFIABLE here (needs a Prometheus read).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Cheap, keep. Its value dropped since every authenticated route checks the audience itself (in:exchange/ExchangeTokenGateFilter.java:116-117).",
    domain_effect="neutral",
    security_note="The gauge says 'configured', not 'configured right' (vault Ingest.md:136-138); the audience lint (ING-SEC-01) covers the committed side.",
)

E["KC-SIMP-01"] = dict(
    status="DONE",
    evidence="#1990: kc:DiscordHttp.java:35 CLIENT shared by kc:DiscordGuildRoleGateAuthenticatorFactory.java:69 and kc:DiscordIdentityProvider.java:91; kc:BackendTrustSupport.java:64 keeps its own client (different trust); kc:DiscordFederatedIdentityMapper.java:69,98 List.copyOf.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete.", domain_effect="neutral", security_note="None.",
)

E["THEME-SIMP-01"] = dict(
    status="PARTIAL",
    evidence="No onsubmit left in keycloak-theme and 0 .ttf files (grep, #1990). Not done: 'fonts once from a shared theme' - byte-identical Lato woff2 x3 in both keycloak-theme/krt-theme/account/resources/fonts and .../login/resources/fonts (167,912 bytes together, cmp identical) with @font-face in both CSS files.",
    verdict="DROPPED", prio_new="—",
    reasoning="The remaining half saves about 84 KB inside one provider image and has no runtime effect. Sharing resources across Keycloak theme types depends on theme import mechanics that would need a test-stack check (UNKNOWN for custom common resources in Keycloak 26.7). Not worth the risk.",
    domain_effect="neutral",
    security_note="The CSP benefit (no inline handlers) is already realised.",
)

E["APPSEC-06"] = dict(
    status="DONE",
    evidence="#1989: be:exception/GlobalExceptionHandler.java:614-626 maps a raw IllegalStateException to the generic 500 (message logged, never echoed). 37 'throw new IllegalStateException(' in backend main today; all reviewed are server defects or startup guards, none client validation.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="The error-mapping kernel is the right home. Related hub for the target: the sealed AppException enumerates domain exceptions and AppExceptionKind has 11 constants including domain ones (OVER_ALLOCATION, PRODUCTION_ALLOCATION, OWNER_ORG_UNIT_REQUIRED, MISSION_PARTICIPANT_REQUIRED), see PSA-01.",
    domain_effect="neutral",
    security_note="No raw messages echoed; 5xx alerting sees server defects again.",
)

E["APPSEC-04"] = dict(
    status="DONE",
    evidence="#2023 (ADR-0207): scripts/redis-users.acl.tmpl:1-6 (default, admin, monitoring, one user per app), scripts/render-redis-acl.py; docker-compose.yml:207,304,352 REDIS_USERNAME per app; RedisAclDenials alert; in:config/RedisUsernameGuard. The ACL grew with the exchange: backend ~exchange:*, ingest read-only %R~exchange:*. Production rollout is recorded in the vault (2026-09-25) and NOT-VERIFIABLE here (needs a host read of ACL LIST).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Least privilege per service; the exchange mirror is an explicit read-only contract between backend and ingest.",
    domain_effect="neutral (service isolation)",
    security_note="default stays off in production; a rollback to <= 1.10.0 must turn it on first (vault).",
)

E["APPSEC-10"] = dict(
    status="DONE",
    evidence="#1989: be:config/SubjectRateLimitingFilter.java:95 EXPORT_SEGMENTS, :106,:133 export buckets (10/min default); be-test:config/SecurityFilterChainOrderTest.java:99-133 sweeps every byte[] handler into the export budget.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="A cross-cutting HTTP policy keyed on path segments plus a type-based sweep; both survive domain moves.",
    domain_effect="neutral",
    security_note="REQ-SEC-033 amended; the sweep keeps new document endpoints under the budget.",
)

E["ING-SEC-04"] = dict(
    status="DONE",
    evidence="#2034/#2036 (ADR-0211): per-service leaves; relay hostname check via app.ingest.verify-backend-hostname (in:config/IngestProperties.java:62, ingest/src/main/resources/application.yml:50 default false), switched on by the deployment default INTERNAL_TLS_VERIFY_HOSTNAME:-true (docker-compose.yml:315,368; quadlet/env.d/ingest.env.tmpl:24). Production state (all four steps, 2026-09-25) per vault; NOT-VERIFIABLE here.",
    verdict="ADJUSTED", prio_new="P3",
    reasoning="Done as designed. Fail-safe hardening: the jar default is off and only the deployment turns it on, so a jar started outside compose/Quadlet under a non-dev profile skips hostname verification. Flip the jar default to true and let dev/test opt out (they trust all anyway); verify on the E2E stack, whose committed TLS material must carry the backend name.",
    domain_effect="neutral",
    security_note="Strengthens the default; no change for the running deployments.",
)

E["ING-MOD-01"] = dict(
    status="DONE",
    evidence="#2008 (ADR-0204): ingest/build.gradle.kts without webflux/reactor (resilience4j-spring-boot3 stays for the ExchangeRelay breaker); in:config/RestClientConfig.java:102-144.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete; about a dozen Netty/Reactor artefacts fewer on the internet-facing gateway.", domain_effect="neutral",
    security_note="Smaller attack surface; the TLS matrix is tested against a real HTTPS MockWebServer (#2008).",
)

E["XMOD-SIMP-01"] = dict(
    status="DONE",
    evidence="#2016 (ADR-0205): logging-support holds LogSafe, PiiMasker, PiiMaskingPatternLayout, PiiMaskingLogstashEncoder; used by backend/build.gradle.kts:44, frontend/build.gradle.kts:122, ingest/build.gradle.kts:37; no copies left (grep); ProdLogMaskingTest per app.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Complete, and a precedent that a scope-closed shared Gradle module is acceptable, useful for domain API modules under option B/C. The mirror pattern persists for platform classes (PSA-03): KeycloakTrustSupport x2 (same code), ManagementPortSecurityConfig, MonitoringScrapeProperties, TracingEnabledMetric, CorrelationIdFilter, StartupBannerListener x3 each, all diverged. ADR-0205 closes logging-support to log hygiene, so a platform module needs its own ADR.",
    domain_effect="helps (precedent)",
    security_note="One masking implementation; ProdLogMaskingTest proves the prod appenders mask.",
)

E["IMG-MOD-11"] = dict(
    status="DONE",
    evidence="#2029/#2050 (ADR-0209, which names JEP 483/514/515 at :40-41): docker/app/Dockerfile:96 -XX:AOTCacheOutput, :105 AOTMode=on verification, :125 exec-form ENTRYPOINT with -XX:AOTCache; readiness -16/-24/-23 percent (#2029 measurement).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Final JDK features with no preview flag, consistent with ADR-0223. The eager-training constraint for new frameworks is noted under IMG-PERF-12.",
    domain_effect="neutral",
    security_note="None beyond the image: exec-form entrypoint, PID-1 reaping checked by .github/scripts/check_pid1_reaping.py; the cache holds no machine code (#2050 gate).",
)

E["SEC-15"] = dict(
    status="DONE",
    evidence="#2025 (135f4c9bb) + #2040: gradle/verification-metadata.xml:4-5 (verify-metadata true, verify-signatures false), 1308 components today; ADR-0208, REQ-OPS-034, CONTRIBUTING 'Dependency verification'.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Any framework added for the modularisation (Spring Modulith, jMolecules, an OpenRewrite plugin) means regenerating the metadata with an empty GRADLE_USER_HOME, a documented procedure.",
    domain_effect="neutral",
    security_note="Supply-chain gate; new modules or plugins must not switch it to lenient.",
)

E["BE-PERF-08"] = dict(
    status="DONE",
    evidence="#2017 (28e6113d5, ADR-0174 amendment 1): be:service/CustomJwtGrantedAuthoritiesConverter.java:93 cache name, :142 recordStats, :172-191 key sub | sid (fallback iat) | azp | fingerprint, :203-220 SHA-256 over all claims except iat/exp/nbf/jti.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Platform concern; unaffected by domain moves.", domain_effect="neutral",
    security_note="A realm-role change still misses on the next refreshed token (fingerprint); local permission changes are bounded by the TTL (default PT5M, ceiling PT15M) - do not raise the ceiling.",
)

E["BE-PERF-11"] = dict(
    status="DONE",
    evidence="#2030 (c3f522f0c): all 148 @ManyToOne/@OneToOne declarations in be:model are LAZY (grep); be-test ArchitectureTest.java:284 toOneAssociationsAreDeclaredLazy (annotation-keyed); LazyToOneReadPathsTest; be:support/CachedEntityGraphs.java.",
    verdict="ADJUSTED", prio_new="P2 (catalogue module step)",
    reasoning="Lazy by default is the prerequisite for cutting cross-domain entity graphs, and the rule survives package moves. But CachedEntityGraphs is a catalogue helper (Material, Location, ShipType, JobType) in the shared support package; it exists because catalogue services cache JPA entities that other domains then hold as @ManyToOne targets. In the target the catalogue module caches immutable read models and other domains reference catalogue rows by id; CachedEntityGraphs then disappears.",
    domain_effect="helps (lazy) / hinders (catalogue hub in support)",
    security_note="LazyInitializationException has hit production before (open-in-view=false); keep LazyToOneReadPathsTest in every move.",
)

E["BE-PERF-13"] = dict(
    status="DONE",
    evidence="#2024 (df3fe12df): be:service/LiveSyncStreamService.java:84 MAX_QUEUED_FRAMES 64, :106 virtual-thread executor, :293-321 single-drain flag; basetool_livesync_frames_dropped_total; alert AppLiveSyncFramesDropped (monitoring/prometheus/alerts/business.yml:888).",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Virtual threads are final (ADR-0223). Live sync is platform infrastructure that domains publish to; it should not become a domain.",
    domain_effect="neutral",
    security_note="Bounded queue per stream; a stalled subscriber cannot block publishers.",
)

E["BE-PERF-14"] = dict(
    status="DONE",
    evidence="#2027 (4af1a2065): no compress( in fe:config/WebClientConfig.java (grep); frontend WebClientCompressionTest pins that neither connector asks for gzip; backend server.compression unchanged (application.yml:18-21); ADR-0161 amendment.",
    verdict="CONFIRMED", prio_new="—",
    reasoning="Measured, then decided.", domain_effect="neutral",
    security_note="No compression on the internal hop removes a compression side channel there.",
)

E["IMG-SIMP-14"] = dict(
    status="DONE",
    evidence="#2029: docker/app/Dockerfile (ARG MODULE; tail stages runtime-backend/-frontend/-ingest :127-143); the per-module Dockerfiles are gone.",
    verdict="ADJUSTED", prio_new="P2 (only for option B/C)",
    reasoning="The build stage enumerates every subproject's build file (Dockerfile:12-17), copies only ${MODULE}/src/main and logging-support/src/main (:26-27), and admits only backend|frontend|ingest (:19-22). Under option B/C each new backend subproject needs COPY lines, or a repo-lint check that derives the list from settings.gradle.kts, in the style of check_sbom_coverage.py's SHIPPED_INSIDE map.",
    domain_effect="hinders option B/C (build coupling); neutral for option A",
    security_note="Keep .dockerignore excluding **/src/test and secrets; do not replace the enumerated COPY with a whole-tree copy without re-checking .dockerignore (keystores, .env).",
)
