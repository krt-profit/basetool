> **Doc type:** Appendix of the living plan
> [Domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) — a snapshot of 2026-09-29
> against `origin/main` `95e945326`; statuses change as work lands.

# Previous audits re-evaluated

This appendix holds the full tables behind §11 of the
[domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md). As decided by the owner (D-06), it
re-evaluates the September 2026 improvement audit and the July 2026 modularity audit in full, and
the earlier focused audits only for the items they deferred, rejected or left open, plus the fixes a
domain split could put at risk. Findings about the sibling repositories are listed, not re-verified.

**How.** Every status was checked against the code at `95e945326` and, for repository settings,
rulesets, workflow runs, the Actions cache, pull requests and issues, through read-only GitHub
queries (all on 2026-09-29). Implementing pull requests were resolved to their merge commits on
`main`. No production or testing host was read; where a finding has a production half, the code
decides the status and the entry says so. Each item was then re-evaluated under the rules of the
plan: domain separation first (owner decisions D-01 … D-08, §2), only final Java features
(ADR-0223), no comments besides Javadoc (ADR-0214), and no change that weakens a security control.

**Vocabulary.**

- *Status* — what the code, workflows and settings show today: DONE, PARTIAL, OPEN, SUPERSEDED (made
  moot by another change), REGRESSED (done, then undone by later code), OUT-OF-SCOPE (sibling
  repositories). A dash means no implementation was due: a diagnosis, a rejection, an accepted risk
  or an item whose content is unknown.
- *Verdict* — the re-evaluation: CONFIRMED (valid as decided), ADJUSTED (valid, but its place or
  shape changes for the modular target), SUPERSEDED-BY-MODULARISATION (the domain refactor replaces
  it), DROPPED (done, moot or not worth it), REPRIORITISED (the priority is what changes). Rejected
  proposals get REJECTION-HOLDS or REJECTION-REVISIT. UNKNOWN marks the four items whose evidence
  was out of reach; each names what would settle it.
- *Priority* — P0 (most urgent) to P3 as the September audit graded them. "Then → now" gives the
  audit's grade and the grade of the work that remains; "closed" means nothing remains.
- *References* — § numbers are sections of the plan; G-, D- and O- identifiers are its guards
  (§6.1), decisions (§2) and the former open decisions (§13, all decided on 2026-10-01). Paths are
  repository-relative. In Java source
  paths `…/` stands for the package root of the module the path starts with:
  `de/greluc/krt/profit/basetool/` followed by `backend/`, `frontend/`, `ingest/`, `keycloak/spi/`
  or `testsupport/`. Line numbers are exact for `95e945326` and drift with later commits.

## September 2026 improvement audit

The improvement audit of 2026-09-22 raised 176 findings. 156 concern this repository; the other 20
concern the Android app, the SC extractor and the P4K reader and are listed at the end of this
section. Every implementing pull request is on `main`.

### Counts

| Area | Findings | DONE | PARTIAL | OPEN | SUPERSEDED | REGRESSED |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Backend | 37 | 32 | 4 | 0 | 0 | 1 |
| Frontend | 30 | 27 | 3 | 0 | 0 | 0 |
| Ingest | 12 | 10 | 1 | 0 | 1 | 0 |
| Keycloak | 5 | 4 | 1 | 0 | 0 | 0 |
| Build | 22 | 20 | 1 | 1 | 0 | 0 |
| CI | 29 | 26 | 3 | 0 | 0 | 0 |
| Operations | 21 | 20 | 1 | 0 | 0 | 0 |
| **Total** | **156** | **139** | **14** | **1** | **1** | **1** |

| Area | CONFIRMED | ADJUSTED | SUPERSEDED-BY-MODULARISATION | DROPPED | REPRIORITISED |
| --- | ---: | ---: | ---: | ---: | ---: |
| Backend | 27 | 9 | 1 | 0 | 0 |
| Frontend | 25 | 4 | 1 | 0 | 0 |
| Ingest | 10 | 1 | 0 | 0 | 1 |
| Keycloak | 4 | 0 | 0 | 1 | 0 |
| Build | 17 | 5 | 0 | 0 | 0 |
| CI | 23 | 6 | 0 | 0 | 0 |
| Operations | 18 | 2 | 0 | 0 | 1 |
| **Total** | **124** | **27** | **2** | **1** | **2** |

The remaining work carries priority P1 in 8 findings, P2 in 16 and P3 in 9; 123 findings are closed.
The tables are the 2026-09-29 snapshot; since then #2387 closed BLD-PERF-04 and CI-SEC-12 and the
leftovers of CI-01, SEC-16, TST-18, ING-SEC-04 and OPS-SEC-08 (PSB-11, PSB-12, PSB-14), and moved
CI-SEC-16 (PSB-13) as far as a workflow change could; #2390 closed CI-SEC-16 (PSB-13) in code, with
the owner's environment step and secret deletion left.
Ten DONE findings have a production half that only a host or Prometheus read could confirm —
APPSEC-04, APPSEC-05, APPSEC-07, CI-SEC-01, ING-SEC-03, ING-SEC-04, OPS-PRIV-01, OPS-SEC-01,
OPS-SEC-05 and OPS-SIMP-03; the knowledge base records the state of their rollout. "Operations" is
the audit's area „Betrieb“.

### All 156 findings

Ordered by area, then by the audit's priority.

| ID | Area | Priority | Title | Status | Verdict | Why | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| APPSEC-01 | Backend | P0 → P2 | Production book-in writes into foreign „Lager“ and personal stock | DONE | ADJUSTED | The fix holds but is the third copy of an inventory rule in a foreign domain; it belongs in an inventory booking command. | #1989; `backend/src/main/java/…/service/JobOrderItemProductionService.java:321-334` |
| APPSEC-02 | Backend | P0 → P2/P3 | Refinery order on a foreign mission changes that mission's payout pot | PARTIAL | ADJUSTED | Server rule done; the mission picker still lists every mission, and the fix added a refinery → mission repository edge. | #1985; `backend/src/main/java/…/service/RefineryOrderService.java:349-359`; `frontend/src/main/java/…/controller/RefineryOrderPageController.java:814` |
| BE-SIMP-02 | Backend | P0 → closed | 17 legacy mission endpoints: sunset 2026-10-20, the frontend still uses 12 | DONE | CONFIRMED | Frontend migrated, all 17 handlers deleted. | #1994, #1996 |
| BE-SIMP-03 | Backend | P0 → closed | Owner change without optimistic lock — last writer wins | DONE | CONFIRMED | Ownership version kept inside the mission domain. | #1994; `backend/src/main/java/…/model/dto/MissionDto.java:74` |
| APPSEC-08 | Backend | P1 → closed | Audience check in prod silently disappears when the variable is empty | DONE | CONFIRMED | Fail-closed start-up check. | #1989; `backend/src/main/java/…/config/JwtAudienceStartupCheck.java:44-76` |
| APPSEC-09 | Backend | P1 → closed | Security comments claim the backend has no management port | DONE | CONFIRMED | Comments corrected, then removed by ADR-0214; the fact lives in Javadoc and ADR-0134. | #1989, #2074; `backend/src/main/java/…/config/ManagementPortSecurityConfig.java:34` |
| BE-MOD-02 | Backend | P1 → P1 | Keycloak admin calls unobserved, a new client per call | PARTIAL | ADJUSTED | One observed client, but the pinned-trust factory used in production has no timeouts — also on the JWKS path. | #2008, #2038; `backend/src/main/java/…/config/KeycloakTrustSupport.java:74-75` |
| BE-MOD-03 | Backend | P1 → closed | `P0D` as retention period deletes the whole audit trail | DONE | CONFIRMED | Validated properties records with minimums; they move with their modules. | #1989; `backend/src/main/java/…/support/AuditRetentionProperties.java:43-48` |
| BE-MOD-05 | Backend | P1 → closed | MapStruct mappers inject by field | DONE | CONFIRMED | Constructor injection makes mapper edges visible to module checks. | #2011; `backend/src/main/java/…/mapper/CentralMapperConfig.java:33-36` |
| BE-MOD-06 | Backend | P1 → closed | Small idiom leftovers | DONE | CONFIRMED | All final Java features. | #2011; `backend/src/main/java/…/config/SecurityConfig.java:308` |
| BE-PERF-02 | Backend | P1 → closed | Seven paged queries fetch collections by entity graph — paging in memory | DONE | CONFIRMED | Global Hibernate guard; layout-independent. | #2004; `backend/src/main/resources/application.yml:72` |
| BE-PERF-03 | Backend | P1 → closed | Full replace of a mission loads participants × units as a Cartesian product | DONE | CONFIRMED | Mission-internal. | #2004; `backend/src/main/java/…/repository/MissionRepository.java:335-338` |
| BE-PERF-10 | Backend | P1 → closed | Foreign keys without an index and one unindexed integrity check | DONE | CONFIRMED | Schema-level gate, independent of the code layout. | #2004; `backend/src/main/resources/db/migration/V245__index_every_uncovered_foreign_key.sql` |
| BE-SIMP-06 | Backend | P1 → closed | Five public methods without callers, one of them cited in two specs | DONE | CONFIRMED | Deleted; the `getAllMissions` tripwire stays until a module boundary replaces it. | #2011; `backend/src/main/java/…/service/MissionService.java:115-118` |
| BE-SIMP-07 | Backend | P1 → closed | Four controllers check `"ROLE_ADMIN"` past the role hierarchy | DONE | CONFIRMED | The role hierarchy is honoured. | #2011 |
| BE-SIMP-08 | Backend | P1 → P3 | `trimToNull` re-implemented eight times | REGRESSED | CONFIRMED | A new copy appeared in the exchange registry. | #2011; `backend/src/main/java/…/service/exchange/ExchangeRegistryService.java:413-416` (`a2e82ff34`) |
| BE-SIMP-10 | Backend | P1 → P3 | About 970 inline fully qualified class names (backend 551, frontend 420) | PARTIAL | ADJUSTED | Backend half done; the frontend half never ran. | #2011 |
| APPSEC-06 | Backend | P2 → closed | Every `IllegalStateException` becomes a 400 with the raw message | DONE | CONFIRMED | Generic 500, message only logged. | #1989; `backend/src/main/java/…/exception/GlobalExceptionHandler.java:614-626` |
| BE-MOD-01 | Backend | P2 → closed | The backend uses WebClient only blocking — remove WebFlux | DONE | CONFIRMED | WebFlux removed (ADR-0204). | #2008; `backend/src/main/java/…/config/RestClientConfig.java:73-97` |
| BE-MOD-04 | Backend | P2 → closed | 18 of 20 properties classes are mutable JavaBeans | DONE | CONFIRMED | All 27 are records; 18 sit in `support` and move with their modules. | #2011 |
| BE-MOD-05b | Backend | P2 → closed | MapStruct silently ignores unmapped targets | DONE | CONFIRMED | An unmapped target is a compile error. | #2011, #2015; `backend/src/main/java/…/mapper/CentralMapperConfig.java:36` |
| BE-PERF-01 | Backend | P2 → P2 | UserMapper: about three queries per mapped user | DONE | ADJUSTED | Fast now, but other domains must prime an identity mapper's memo. | #2004; `backend/src/main/java/…/mapper/UserMapper.java:70-78` |
| BE-PERF-04 | Backend | P2 → closed | JDBC batching is off | DONE | CONFIRMED | `order_inserts` stays off — a constraint for the id-reference migration. | #2009; `backend/src/main/resources/application.yml:63-73` |
| BE-PERF-09 | Backend | P2 → closed | UEX syncs hold a transaction across the HTTP fetch and check every row separately | DONE | CONFIRMED | Catalogue-internal. | #2009; `backend/src/main/java/…/service/SyncChunkWriter.java` |
| BE-PERF-12 | Backend | P2 → P2 | `UserRepository.findById` always loads roles and permissions | PARTIAL | ADJUSTED | The graphed variant keeps the default name; three reference-only callers use it. | #2004; `backend/src/main/java/…/repository/UserRepository.java:302-305` |
| BE-PERF-15 | Backend | P2 → P3 | Small N+1 loops on rare paths | DONE | ADJUSTED | Loops gone, but a second memo of the caller's memberships appeared. | #2004; `backend/src/main/java/…/service/OrgRoleManagementSecurityService.java:60-61` |
| BE-SIMP-01 | Backend | P2 → closed | REQ-API-004 prescribes `Entities.require` — 247 sites write it by hand | DONE | CONFIRMED | Ratchet at zero; its scan root must grow with any Gradle extraction. | #2011, #2015; `backend/src/test/java/…/exception/EntitiesRequireRatchetTest.java:48` |
| BE-SIMP-04 | Backend | P2 → closed | Participant resolution written four times, with two different queries | DONE | CONFIRMED | One resolver — already the seam the mission module wants. | #1994; `backend/src/main/java/…/service/ParticipantTargetResolver.java:76-96` |
| BE-SIMP-05 | Backend | P2 → P2 | 14 inline redaction blocks despite `redactForPeer` | DONE | SUPERSEDED-BY-MODULARISATION | Helper done; redaction moves into the mission read API. | #1994, #1996; `backend/src/main/java/…/controller/MissionController.java:1446-1512` |
| BE-SIMP-09 | Backend | P2 → P2 | Two request-memo idioms, one with three unchecked casts | DONE | ADJUSTED | Typed memo done; the caller's memberships are memoised three times by different owners. | #2011; `backend/src/main/java/…/support/RequestMemo.java` |
| BE-SIMP-11 | Backend | P2 → closed | Two Redis fan-outs share one scaffold | DONE | CONFIRMED | Transport-only extraction; re-homed when notification and live sync separate. | #2011; `backend/src/main/java/…/service/RedisJsonFanout.java:42` |
| APPSEC-04 | Backend | P3 → closed | Backend, frontend and ingest share one all-powerful Redis user | DONE | CONFIRMED | One ACL user per service (ADR-0207); host state not re-read. | #2023; `scripts/redis-users.acl.tmpl:1-6` |
| APPSEC-10 | Backend | P3 → closed | Expensive export and report GETs have only the per-IP budget | DONE | CONFIRMED | Per-subject export budget, swept by response type. | #1989; `backend/src/main/java/…/config/SubjectRateLimitingFilter.java:95` |
| BE-PERF-08 | Backend | P3 → closed | Authorities cache: a TTL above 5 min is useless because `iat` is part of the key | DONE | CONFIRMED | Keyed on session and claim fingerprint (ADR-0174 amendment). | #2017; `backend/src/main/java/…/service/CustomJwtGrantedAuthoritiesConverter.java:172-191` |
| BE-PERF-11 | Backend | P3 → P2 | 37 `@ManyToOne` still EAGER | DONE | ADJUSTED | All lazy; the catalogue helper `CachedEntityGraphs` in `support` goes with cached read models. | #2030; `backend/src/main/java/…/support/CachedEntityGraphs.java` |
| BE-PERF-13 | Backend | P3 → closed | Live sync writes synchronously to every subscriber on the request thread | DONE | CONFIRMED | Bounded queue per stream on virtual threads. | #2024; `backend/src/main/java/…/service/LiveSyncStreamService.java:84,106` |
| BE-PERF-14 | Backend | P3 → closed | Measure gzip on the internal frontend → backend hop | DONE | CONFIRMED | Measured; no gzip on the internal hop (ADR-0161). | #2027 |
| APPSEC-03 | Frontend | P1 → closed | Hangar import buffers up to 64 MB per upload in the frontend heap | DONE | CONFIRMED | 8 MiB cap before any read; the upload is streamed. | #2002; `frontend/src/main/java/…/controller/HangarImportProxyController.java:64` |
| APPSEC-11 | Frontend | P1 → closed | Twelve relays turn a backend 4xx into a 500 error page | DONE | CONFIRMED | One handler keeps the backend status. | `frontend/src/main/java/…/exception/GlobalExceptionHandler.java:415-454` |
| APPSEC-12 | Frontend | P1 → closed | Session IDs end up in the logs | DONE | CONFIRMED | Logs carry a 12-hex SHA-256 fingerprint. | #2002; `frontend/src/main/java/…/support/SessionIdFingerprint.java:39-62` |
| BE-PERF-05 | Frontend | P1 → closed | Every assignee action loads the whole roster, which the page no longer uses | DONE | CONFIRMED | Roster read removed. | `25430fd7e` (in #2004) |
| CI-SEC-18 | Frontend | P1 → closed | Frontend lint toolchain installed with `npm install` and active lifecycle scripts | DONE | CONFIRMED | `npm ci` with `ignore-scripts`. | #2002; `frontend/build.gradle.kts:472`; `frontend/.npmrc:1` |
| FE-MOD-04 | Frontend | P1 → closed | MissionWriteController still uses Jackson 2 | DONE | CONFIRMED | Jackson 3 only; Thymeleaf's serializer is the last Jackson-2 user. | #1996; `frontend/src/main/java/…/controller/MissionWriteController.java:86-89` |
| FE-PERF-04 | Frontend | P1 → closed | About 570 KB of unused, publicly served assets | DONE | CONFIRMED | Seven `basetool-*` logos left, pinned by a test. | #2002 |
| FE-PERF-06 | Frontend | P1 → closed | Three pages reload after a successful write, three after a 409 without asking | DONE | CONFIRMED | The six sites update in place; 409 goes through the conflict dialog. | no `location.reload` left in the six named scripts |
| FE-SEC-01 | Frontend | P1 → closed | Mission and operation search build backend URLs by string concatenation | DONE | CONFIRMED | Template variables, `Instant` binding, status allow-list; the rest of the class is FE-SIMP-02. | `frontend/src/main/java/…/controller/MissionPageController.java:263-321` |
| FE-SEC-02 | Frontend | P1 → closed | `_referer` redirect unchecked when switching the active unit | DONE | CONFIRMED | Single-slash path check and UUID binding. | #2002; `frontend/src/main/java/…/controller/MeFrontendController.java:86-98` |
| FE-SEC-04 | Frontend | P1 → closed | CSP violations are invisible | DONE | CONFIRMED | `csp_violation` beacon kind, metric, panel and alert text. | `frontend/src/main/resources/static/js/krt-client-error.js:232` |
| BE-PERF-06 | Frontend | P2 → closed | User search loads up to 1,000 full users per keystroke | DONE | CONFIRMED | Paged reference search behind the same gates; `UserReferenceDto` is the kind of small reference other domains should use. | #2004; `backend/src/main/java/…/controller/UserController.java:230-239` |
| FE-MOD-01 | Frontend | P2 → closed | 133 redundant `var(--token, #hex)` fallbacks hide renamed tokens | DONE | CONFIRMED | None left; Stylelint refuses them. | #2010, #2014; `frontend/.stylelintrc.json:19-28` |
| FE-MOD-03 | Frontend | P2 → P2 | Minimal ESLint, 60 of 95 scripts without type checking | PARTIAL | ADJUSTED | Rules done; 44 of 100 scripts are checked, none of the three largest. | `frontend/eslint.config.mjs:37-39` |
| FE-PERF-01 | Frontend | P2 → P2 | Layout advices make 3–4 backend calls before every response, fragments and JSON included | DONE | ADJUSTED | One `/me/layout` read; the endpoint composes four domains' rules. | #2004, #2020; `backend/src/main/java/…/controller/MeController.java:104-115` |
| FE-PERF-02 | Frontend | P2 → closed | Every HTML response carries ~25 KB of comments, up to 25 KB of inline CSS and a 16 KB sprite | DONE | CONFIRMED | No comments or `<style>` in templates; the optional sprite file was not done. | #2020; `TemplateCommentHygieneTest` |
| FE-PERF-03 | Frontend | P2 → closed | `ShallowEtagHeaderFilter` buffers every response for almost no benefit | DONE | CONFIRMED | Filter limited to the manifest and assetlinks. | #2010, #2014; `frontend/src/main/java/…/config/EtagConfig.java:46-47` |
| FE-PERF-05 | Frontend | P2 → closed | 257 KB of unminified JS per page, eight blocking head scripts | DONE | CONFIRMED | Every script `defer` except the error beacon; minification dropped as not worth it. | #2020; `frontend/src/main/resources/templates/fragments/head.html:70-76` |
| FE-PERF-07 | Frontend | P2 → closed | Tomcat thread settings have no effect under virtual threads | DONE | CONFIRMED | Keys removed; the panel reads `http_server_requests_active`. | `monitoring/grafana/dashboards/03-spring-apps.json:281` |
| FE-SEC-03 | Frontend | P2 → closed | 23 raw `fetch` writes bypass `krtFetch` and `krtCsrf` | DONE | CONFIRMED | Lint refuses non-GET raw fetches; none left. | `frontend/eslint.config.mjs:5-12,49` |
| FE-SEC-05 | Frontend | P2 → closed | 96 `innerHTML` sinks guarded only by review, seven local escape copies | DONE | CONFIRMED | `no-unsanitized` lint, one escape helper, one sanctioned sink. | `frontend/eslint.config.mjs:52-65` |
| FE-SIMP-01 | Frontend | P2 → closed | 50 hand-written relay-or-500 blocks despite `relay()` | DONE | CONFIRMED | 111 `relay(` uses in 28 controllers. | #2010; `frontend/src/main/java/…/support/BackendErrorResponses.java:72` |
| FE-SIMP-02 | Frontend | P2 → P2 | BackendApiClient: eight copied verb methods, 290 URIs built by concatenation | PARTIAL | SUPERSEDED-BY-MODULARISATION | One private `exchange(...)` done; the typed clients are the frontend half of the domain split. | `frontend/src/main/java/…/service/BackendApiClient.java:395-407` |
| FE-SIMP-03 | Frontend | P2 → P2 | About 700 lines copied between the „Lager“ scripts, plus hard-coded German strings | PARTIAL | ADJUSTED | Shared „Lager“ script done; `mission-detail.js` keeps seven German fallbacks the gate cannot see. | `frontend/src/main/resources/static/js/inventory-common.js`; `frontend/src/main/resources/static/js/mission-detail.js:2088` |
| FE-SIMP-04 | Frontend | P2 → closed | 90 of 96 dialogs copy the scaffold; 97 sites set `style.display` | DONE | CONFIRMED | `window.krtModal` and one modal wrapper. | #2042, #2051; `frontend/src/main/resources/static/js/krt-modal.js:246` |
| APPSEC-05 | Frontend | P3 → P1 | Session deserializer admits any class | DONE | ADJUSTED | Allow-list enforced in production, but the code default is `report` and the package prefix blocks per-domain packages. | `frontend/src/main/java/…/config/RedisSessionConfig.java:89`; `frontend/src/main/java/…/config/SessionTypeAllowList.java:86-87` |
| APPSEC-07 | Frontend | P3 → closed | ADR-0001 (confidential frontend client) "pending" for four months | DONE | CONFIRMED | Confidential client with PKCE whenever the secret is set; realm and host state not re-read. | #2028; `frontend/src/main/java/…/config/FrontendClientAuthenticationConfig.java:74-93` |
| FE-MOD-02 | Frontend | P3 → closed | CSS cascade layers instead of five load-order traps | DONE | CONFIRMED | All 64 stylesheets layered (ADR-0212). | #2049, #2052; `CascadeLayerOrderTest` |
| FE-SEC-06 | Frontend | P3 → closed | Session cookie without the `__Host-` prefix next to four subdomains | DONE | CONFIRMED | `__Host-SESSION`. | `frontend/src/main/resources/application.yml:26-31` |
| FE-SIMP-04b | Frontend | P3 → closed | Evaluate native `<dialog>` for all dialogs | DONE | CONFIRMED | Native `<dialog>` in the one wrapper; ADR-0177 amended. | #2042; `frontend/src/main/resources/templates/fragments/modal-wrapper.html:4-5` |
| ING-PERF-01 | Ingest | P1 → closed | Ingest WebClients use the global Netty pool without eviction | SUPERSEDED | CONFIRMED | Moot since ING-MOD-01 removed Reactor Netty. | #2008; `RelayIdleConnectionBoundTest` |
| ING-PERF-02 | Ingest | P1 → closed | Handoff trim with a superfluous Redis round trip; `consume()` is dead code | DONE | CONFIRMED | Uses the RPUSH answer; `consume()` moved into a test. | #1990; `ingest/src/main/java/…/service/HandoffStagingService.java:207-214` |
| ING-SEC-01 | Ingest | P1 → closed | Ingest configuration recommends the audience that caused the incident of 2026-08-21 | DONE | CONFIRMED | A lint outlives the removed comment; every exchange route checks its audience itself. | #1990, #2074; `scripts/check-ingest-audience.py` |
| ING-SEC-02 | Ingest | P1 → closed | Backend 401/403 reported to the extractor as a login failure; the gateway token is never discarded | DONE | CONFIRMED | The invariant was carried into the exchange relay. | #1990, #2270; `ingest/src/main/java/…/exchange/ExchangeRelay.java:395-437` |
| ING-SEC-05 | Ingest | P1 → closed | Nothing enforces that every ingest endpoint sits under `/v1` | DONE | CONFIRMED | A surface test pins the 16 exchange routes. | #1990, #2270; `ingest/src/test/java/…/filter/IngestEndpointSurfaceTest.java:50-66` |
| ING-SIMP-01 | Ingest | P1 → closed | Filter order: a tie at +15, body buffering before the rate limit | DONE | CONFIRMED | Unique orders; the rate limit runs before buffering. | #1990; `FilterOrderTest` |
| ING-SIMP-03 | Ingest | P1 → closed | Problem body built twice, MDC key hard-coded | DONE | CONFIRMED | One builder reads the configured key. | #1990; `ingest/src/main/java/…/web/Problems.java:57-63` |
| ING-MOD-02 | Ingest | P2 → P1 | Mutable properties beans and `@Value` field injection | PARTIAL | REPRIORITISED | Seven of eight are records; the scrape-credential class is `@Data` and prints its password in `toString`. | #1990; `ingest/src/main/java/…/config/MonitoringScrapeProperties.java:32-50` |
| ING-SEC-03 | Ingest | P2 → closed | Whether the gates apply in prod can only be settled with host access | DONE | CONFIRMED | Audience-gate gauge, alert and panel; the production value was not re-read. | #1990, #2270; `ingest/src/main/java/…/metrics/IngestGatePostureMetric.java:44` |
| ING-SIMP-02 | Ingest | P2 → closed | Bucket factory duplicated; per-IP and per-subject limits share one budget | DONE | CONFIRMED | One factory, a separate per-IP capacity. | #1990; `ingest/src/main/java/…/ratelimit/RateLimitBuckets.java:61` |
| ING-MOD-01 | Ingest | P3 → closed | Remove WebFlux from the internet-facing gateway | DONE | CONFIRMED | `RestClient` on the JDK client (ADR-0204). | #2008; `ingest/src/main/java/…/config/RestClientConfig.java:102-144` |
| ING-SEC-04 | Ingest | P3 → P3 | Every ingest container holds the key that also identifies backend and Keycloak | DONE | ADJUSTED | Per-service leaves done (ADR-0211); the jar's hostname-check default is still off. **Closed 2026-10-04 (#2387):** the ingest jar defaults to `true`, `dev`/`test` opt out. | #2034, #2036; `ingest/src/main/resources/application.yml:50` |
| KC-CI-01 | Keycloak | P1 → closed | Login-gate module without SpotBugs/FindSecBugs and without coverage | DONE | CONFIRMED | SpotBugs, FindSecBugs and coverage floors wired. | #1990; `keycloak-spi/build.gradle.kts:4,7` |
| KC-PERF-01 | Keycloak | P1 → closed | The first Discord login queries the same guild endpoint three times | DONE | CONFIRMED | One member lookup per first login. | #1990; `keycloak-spi/src/main/java/…/DiscordGuildRoleGateAuthenticator.java:117-125` |
| THEME-SEC-01 | Keycloak | P1 → closed | Login form blocks password managers and always ticks "remember me" | DONE | CONFIRMED | Autocomplete hints; remember-me only when set (REQ-SEC-066). | #1990; `keycloak-theme/krt-theme/login/login.ftl:13-27` |
| KC-SIMP-01 | Keycloak | P2 → closed | Four custom HttpClients, three of them identical | DONE | CONFIRMED | One shared Discord client. | #1990; `keycloak-spi/src/main/java/…/DiscordHttp.java:35` |
| THEME-SIMP-01 | Keycloak | P2 → closed | Inline `onsubmit` and superfluous TTF fonts | PARTIAL | DROPPED | Handlers and TTFs gone; sharing the fonts across theme types is not worth the risk. | #1990 |
| BLD-CI-09 | Build | P1 → closed | A stale openapi.json goes unnoticed in every PR | DONE | CONFIRMED | A diff gate on the reviewed contract file. | #2019; `.github/workflows/ci.yml:65-69` |
| BLD-PERF-01 | Build | P1 → closed | Image builds run `:X:build` with an exclusion list instead of `:X:bootJar` | DONE | CONFIRMED | One Dockerfile runs `bootJar`; test sources stay out of the context. | #2029; `docker/app/Dockerfile:30` |
| BLD-PERF-02 | Build | P1 → closed | The backend builds unused distributions and a sources jar | DONE | CONFIRMED | Both removed. | #2019; `backend/build.gradle.kts:3-15` |
| BLD-PERF-07 | Build | P1 → closed | `:frontend:test` never comes from the build cache | DONE | CONFIRMED | Build info normalised out of the runtime classpath. | #2019; `frontend/build.gradle.kts:94` |
| BLD-PERF-10 | Build | P1 → closed | Mockito agent resolved at configuration time and written into the cache key as an absolute path | DONE | CONFIRMED | Configuration-cache-clean argument provider. | #2019; `build.gradle.kts:138-148` |
| DOC-20 | Build | P1 → closed | Stale build comments and the `versions.properties` question | DONE | CONFIRMED | File removed; build scripts carry no comments (ADR-0214). | #2019, #2074 |
| DOC-21 | Build | P1 → closed | The project CLAUDE.md names the owner's retired e-mail address | DONE | CONFIRMED | Replaced; only a historical CHANGELOG entry mentions the switch. | `674e55bd7` |
| SEC-16 | Build | P1 → P3 | OWASP suppressions never expire | DONE | ADJUSTED | All nine expire on one date; the renewal procedure went with the removed header comment. **Closed 2026-10-04 (#2387):** CONTRIBUTING → *OWASP suppressions expire*. | #2019, #2074; `config/owasp/dependency-check-suppressions.xml` |
| SEC-17 | Build | P1 → closed | Test profile ships in the production jars | DONE | CONFIRMED | The jar gate is applied by plugin id, so any new subproject inherits it. | #2019; `build.gradle.kts:154-175` |
| TS-SIMP-01 | Build | P1 → closed | test-support uses the JUnit pin meant only for keycloak-spi | DONE | CONFIRMED | Pin removed; the Boot BOM supplies JUnit. | #1990; `test-support/build.gradle.kts:22-24` |
| TST-18 | Build | P1 → P3 | Redis tests run against Redis 7, production runs Redis 8 | DONE | ADJUSTED | Redis parity done; the same drift exists for PostgreSQL. **Closed 2026-10-04 (#2387):** `TestImages.POSTGRES` through the backend's `PinnedPostgresImageSubstitutor`. | #2019; `test-support/src/main/java/…/containers/TestImages.java:39-40` |
| TST-19 | Build | P1 → closed | Testcontainers JDBC without `TC_DAEMON` | DONE | CONFIRMED | Set in the test profile. | #2019; `backend/src/test/resources/application-test.yml:7` |
| BLD-PERF-03 | Build | P2 → P1/P2 | Spring test-context cache fragmented — 38 contexts at a cache size of 32 | OPEN | ADJUSTED | Untouched; profile unification now, module-scoped test slices with the modularisation. | no commit; `build.gradle.kts:186` |
| BLD-PERF-04 | Build | P2 → P3 | Configuration cache off because refreshVersions runs in every build | DONE (2026-10-04) | CONFIRMED | CI builds with the cache; developer builds still default to off. **Closed 2026-10-04 (#2387):** `org.gradle.configuration-cache=true` in `gradle.properties`. | #2019; `settings.gradle.kts:1-5` |
| BLD-PERF-08 | Build | P2 → closed | `minifyStaticCss` overwrites the output of `processResources` | DONE | CONFIRMED | The minified copy has its own directory. | #2019; `frontend/build.gradle.kts:333-385` |
| BLD-SIMP-06 | Build | P2 → P2 | About 250 duplicated lines across the module builds | DONE | ADJUSTED | Plugin-id conventions help a split; name-keyed floors and test heap would silently drop. | #2019; `build.gradle.kts:184,228-241` |
| IMG-CI-13 | Build | P2 → closed | Nothing checks that the CDS archive is created and accepted | DONE | CONFIRMED | AOT-cache verification at build time plus a Loki rule. | #2029, #2050; `docker/app/Dockerfile:105-117` |
| IMG-PERF-12 | Build | P2 → closed | CDS training with lazy initialisation loads hardly any application classes | DONE | CONFIRMED | Eager training with stubs; a bean that needs a live database at refresh fails the image build. | #2029, #2050; `docker/app/Dockerfile:63-101` |
| IMG-MOD-11 | Build | P3 → closed | Replace dynamic AppCDS with the Java 25 AOT cache | DONE | CONFIRMED | Final JDK features (ADR-0209). | #2029, #2050; `docker/app/Dockerfile:96,125` |
| IMG-SIMP-14 | Build | P3 → P2 | Three nearly identical app Dockerfiles | DONE | ADJUSTED | One Dockerfile, but it enumerates today's subprojects. | #2029; `docker/app/Dockerfile:12-27` |
| SEC-15 | Build | P3 → closed | No Gradle dependency verification for artefacts that end up in signed images | DONE | CONFIRMED | Strict SHA-256 verification (ADR-0208); every new framework regenerates it. | #2025, #2040; `gradle/verification-metadata.xml` |
| XMOD-SIMP-01 | Build | P3 → closed | LogSafe/PiiMasker three times, held together by a mirror test | DONE | CONFIRMED | One `logging-support` module (ADR-0205); other platform mirrors remain (PSA-03). | #2016 |
| CI-01 | CI | P0 → P2 | CodeQL default setup crowds out the NVD and Playwright caches | DONE | ADJUSTED | Advanced CodeQL only, but the cache stands at 9.39 of 10 GiB, above the audit's guard. **Closed 2026-10-04 (#2387):** newest `gradle-home-` entry per family plus its bundles; warning above 8 GiB. | #1982, #1986; `.github/workflows/cache-janitor.yml:64-70` |
| CI-02 | CI | P0 → closed | Weekly OWASP run without a result since 2026-07-20 | DONE | CONFIRMED | Runs finish again since the NVD JSON 2.0 feeds; a failed run opens an issue. | #1993; `.github/workflows/dependency-check.yml:118-141` |
| CI-03 | CI | P0 → P1 | Backend mutation tests have failed for eight weeks while the job shows green | PARTIAL | ADJUSTED | Gate added, but the only CI run since timed out and passed on a partial report. | #1993; `.github/workflows/pitest.yml:19,45-66` |
| CI-09 | CI | P1 → closed | JDK and Gradle setup copied nine times, ineffective chmods | DONE | CONFIRMED | One setup composite. | `.github/actions/setup-jdk-gradle/action.yml` |
| CI-11 | CI | P1 → closed | The only gate without a running self-test | DONE | CONFIRMED | The self-test runs before the check. | `.github/workflows/repo-lint.yml:232-240` |
| CI-12 | CI | P1 → closed | Dead workflows are maintained and documented as active | DONE | CONFIRMED | `e2e-smoke` dispatch-only; CodeQL active. | `.github/workflows/e2e-smoke.yml:3-4` |
| CI-13 | CI | P1 → closed | Dependabot groups nothing | DONE | CONFIRMED | Actions updates grouped. | `.github/dependabot.yml:10-13` |
| CI-16 | CI | P1 → closed | Scheduled workflows start 4–5 h after their cron time | DONE | CONFIRMED | All eight schedules off the full hour; late starts documented. | cron lines of the eight scheduled workflows |
| CI-SEC-01 | CI | P1 → closed | cosign signature regex is not anchored | DONE | CONFIRMED | Anchored everywhere, with a gate; the host copy of `deploy.sh` was not re-read. | #1987; `scripts/check-cosign-identity.py` |
| CI-SEC-02 | CI | P1 → closed | Tag ruleset lets any write token create a release tag | DONE | CONFIRMED | Only the owner and the release App may create tags; a ref guard runs before signing. | `.github/workflows/release-images.yml:20-60` |
| CI-SEC-03 | CI | P1 → closed | Environments production and testing without branch restriction | DONE | CONFIRMED | `main`-only policies plus a workflow refusal. | `.github/workflows/promote.yml:37` |
| CI-SEC-05 | CI | P1 → closed | Unused write permissions in `ci.yml` and `refresh-versions.yml` | DONE | CONFIRMED | `contents: read`. | `.github/workflows/ci.yml:14-15` |
| CI-SEC-06 | CI | P1 → closed | 44 checkouts leave the token in `.git/config` | DONE | CONFIRMED | 36 of 36 checkouts with `persist-credentials: false`. | `.github/workflows/`, `.github/actions/` |
| CI-SEC-08 | CI | P1 → closed | Dependabot alerts see not a single Maven dependency | DONE | CONFIRMED | Dependency submission on `main`; 944 Maven entries in the graph. | `.github/workflows/dependency-submission.yml` |
| CI-SEC-10 | CI | P1 → P1 | Only five required checks — gitleaks, wrapper validation and actionlint are not among them | DONE | ADJUSTED | Nine required checks now, but `Self-tests` and `Container checks` are not required. | `.github/workflows/repo-lint.yml:306,379` |
| CI-SEC-11 | CI | P1 → closed | gitleaks reads its allow-list from the PR head | DONE | CONFIRMED | Configuration loaded from the base commit. | `.github/workflows/gitleaks.yml:57-75` |
| CI-SEC-12 | CI | P1 → P2 | CI tools without checksums, pip packages unpinned | DONE (2026-10-04) | ADJUSTED | Binaries hashed; PyYAML, Ansible and one `npx` fetch are not. **Closed 2026-10-04 (#2387):** `--require-hashes` files, markdownlint lockfile, exact collections. | `.github/workflows/repo-lint.yml:108,277-278`; `.github/workflows/exchange-docs.yml:58` |
| CI-SEC-14 | CI | P1 → closed | Actions policy does not enforce SHA pins | DONE | CONFIRMED | SHA pinning required, selected owners only. | repository Actions policy |
| CI-SEC-15 | CI | P1 → closed | No workflow security linter in CI | DONE | CONFIRMED | Hash-pinned zizmor in the required Linters job. | `.github/workflows/repo-lint.yml:61-75` |
| CI-SEC-17 | CI | P1 → closed | e2e-smoke would upload a real staging session cookie as a public artefact | DONE | CONFIRMED | Session material deleted and excluded before upload. | `.github/workflows/e2e-smoke.yml:50-69` |
| OPS-CI-01 | CI | P1 → closed | CI checks only the alert rules, not the monitoring configurations | DONE | CONFIRMED | Config checks for Prometheus, Alertmanager, Alloy and Loki — in a non-required job (CI-SEC-10). | `scripts/check-monitoring-configs.sh:62-132` |
| OPS-CI-02 | CI | P1 → closed | `deploy-script.yml` does not run on changes to the runtime library | DONE | CONFIRMED | The path filter covers `scripts/lib/**`. | `.github/workflows/deploy-script.yml:8-17` |
| CI-04 | CI | P2 → closed | repo-lint consists of 25 jobs and clogs the runners at peaks | DONE | CONFIRMED | Four jobs. | `.github/workflows/repo-lint.yml` |
| CI-06 | CI | P2 → closed | Each of the 15 E2E jobs builds all images and installs all three browsers | DONE | CONFIRMED | Images built once, one browser per cell (ADR-0200). | `.github/workflows/e2e.yml:25-90` |
| CI-08 | CI | P2 → closed | The promote job exists twice, almost identical | DONE | CONFIRMED | One `retag-verified-digest` composite. | `.github/actions/retag-verified-digest/action.yml` |
| CI-SEC-04 | CI | P2 → closed | Release and promote workflows give every job all permissions | DONE | CONFIRMED | Per-job permissions; the SPI is compiled without `id-token`. | `.github/workflows/release-images.yml:13` |
| CI-SEC-13 | CI | P2 → closed | BuildKit builder floats on a tag and may come from a mirror | DONE | CONFIRMED | Pinned by digest through a carrier Dockerfile. | `.github/actions/setup-buildx/Dockerfile:1` |
| CI-SEC-16 | CI | P2 → P2 | Long-lived PATs as repository-wide secrets | DONE (2026-10-04) | ADJUSTED | App tokens replace the PATs; the App key is not confined to a `main`-only environment. **2026-10-04 (#2387):** `release-prepare` and `refresh-versions` run in the `release` environment (owner creates it); `release-publish` cannot until its `pull_request` trigger changes (ADR-0201 amendment 3). **Closed 2026-10-04 (#2390):** `release-publish` runs on the `main` push, recognises the release merge itself and publishes in `release` (ADR-0201 amendment 4). Owner step: create `release` (branch rule `main`) with the secret, then delete the repository secret. | `.github/workflows/release-publish.yml` |
| CI-07 | CI | P3 → P3 | release-images rebuilds everything on 19 of 30 main commits without an image change | DONE | ADJUSTED | Per-module reuse (ADR-0210); its input derivation must change before any Gradle extraction. | #2031, #2033, #2046; `.github/scripts/image_reuse_plan.py:44-112` |
| OPS-MON-01 | Operations | P0 → P1 | Critical alert ContainerRestartLoop cannot fire | PARTIAL | ADJUSTED | The alert reads the podman series now, but nothing notices when those series vanish. | #1984; `monitoring/prometheus/alerts/infrastructure.yml:60-61`; `scripts/check-conformance.py:74-81` |
| OPS-PERF-01 | Operations | P0 → closed | Podman kills every container after 10 s | DONE | CONFIRMED | `StopTimeout` and `TimeoutStopSec` for the nine units with a grace period. | `scripts/generate-quadlet.py:783-787` |
| OPS-REL-01 | Operations | P0 → closed | Changed monitoring and acme units are never applied | DONE | CONFIRMED | Changed units restarted; acme applied. | #1984; `scripts/lib/container-runtime.sh:374-395` |
| OPS-SEC-01 | Operations | P0 → closed | No automatic security updates on the production host | DONE | CONFIRMED | Security-only unattended updates with alerts (ADR-0199); host state not re-read. | `ansible/roles/basetool_host/tasks/45-updates.yml:17-29` |
| OPS-MON-02 | Operations | P1 → closed | No alert when the deploy timer stops | DONE | CONFIRMED | `DeployHeartbeatStale` with an `absent()` leg. | `monitoring/prometheus/alerts/ops-automation.yml:84-87` |
| OPS-MON-03 | Operations | P1 → closed | No alert on a PostgreSQL or Redis outage or an unhealthy container | DONE | CONFIRMED | Three alerts with `for:` windows. | `monitoring/prometheus/alerts/infrastructure.yml:80-90` |
| OPS-MON-04 | Operations | P1 → closed | The cgroup collector's staleness alert misses a collector that never ran | DONE | CONFIRMED | `absent()` leg added. | `monitoring/prometheus/alerts/containers-runtime.yml:73-76` |
| OPS-MON-05 | Operations | P1 → closed | The weekly restore drill alerts only after 35 days | DONE | CONFIRMED | Eight days. | `monitoring/prometheus/alerts/ops-automation.yml:55-56` |
| OPS-PRIV-01 | Operations | P1 → closed | The journal keeps edge access logs with client IPs without a time limit | DONE | CONFIRMED | 31 days and 4 GB; REQ-OBS-010 amended; host state not re-read. | `ansible/roles/basetool_host/tasks/27-observability.yml:135-136` |
| OPS-SEC-02 | Operations | P1 → closed | The weekly Prometheus snapshot fails under Podman and shows the password in argv | DONE | CONFIRMED | Snapshot through `wget` inside the Prometheus container; a small argv residual accepted. | `scripts/lib/container-runtime.sh:438-442` |
| OPS-SEC-03 | Operations | P1 → P1 | Backup helper with access to every secret runs from a floating tag | DONE | ADJUSTED | The pinned image is used, but an unreadable unit file falls back to an unpinned one. | `scripts/backup.sh:29,91-97`; `scripts/restore-drill.sh:19,74-80` |
| OPS-SEC-04 | Operations | P1 → closed | Keycloak mounts the unused realm export, and its theme and providers writable | DONE | CONFIRMED | No realm mount; theme and providers read-only. | #1992; `quadlet/systemd/keycloak.container:21-24` |
| OPS-SEC-07 | Operations | P1 → closed | Prometheus lifecycle API enabled, nobody uses it | DONE | CONFIRMED | Flag removed. | `quadlet/systemd/prometheus.container:22` |
| OPS-MOD-01 | Operations | P2 → closed | Generator uses raw podman flags instead of Quadlet keys | DONE | CONFIRMED | Quadlet keys wherever podman offers one. | `scripts/generate-quadlet.py:750` |
| OPS-SEC-06 | Operations | P2 → closed | Host Alloy and node_exporter are never updated | DONE | CONFIRMED | The conformance check compares host agent versions with the compose pins. | `scripts/check-conformance.py:1290-1330` |
| OPS-SEC-08 | Operations | P2 → P3 | sudoers comment overstates the boundary between `deploy` and `iri` | DONE | REPRIORITISED | The statement is corrected in the docs; the optional sudo wrapper is low urgency. **Closed 2026-10-04 (#2387):** sudoers allowlist of the 15 sub-commands the scripts run, kept in step by `check-deploy-podman-allowlist.py`; host rollout is the owner's. | #1992; `ansible/README.md:43` |
| OPS-SIMP-01 | Operations | P2 → closed | The Docker half of the runtime seam is dead code after the cutover | DONE | CONFIRMED | No Docker branch left (ADR-0203). | #2001; `scripts/lib/container-runtime.sh` |
| OPS-SIMP-02 | Operations | P2 → closed | Docker and NPM leftovers in compose, rules, `docker/` and alert texts | DONE | CONFIRMED | cAdvisor and NPM leftovers gone (ADR-0203). | `monitoring/prometheus/alerts/containers-runtime.yml` |
| OPS-SIMP-03 | Operations | P2 → closed | Four operations units repeat 60 lines of hardening — and drift | DONE | CONFIRMED | One sandbox drop-in, drill paths narrowed; no host run evidenced. | #2001; `scripts/iri-deploy-account-sandbox.conf` |
| OPS-SIMP-04 | Operations | P2 → closed | Shell helpers four times, eight hand-written textfile writers | DONE | CONFIRMED | One shared library for four scripts. | `scripts/lib/common.sh` |
| OPS-SEC-05 | Operations | P3 → closed | Database and Redis networks allow internet egress | DONE | CONFIRMED | `Internal=true` on the five data networks; the testing rollout is pending per the knowledge base. | `quadlet/systemd/net-db-backend.network:6` |

### Items that need work

Every finding that is not DONE or not CONFIRMED, grouped by the priority of the work that remains.

#### Remaining priority P1

- **BE-MOD-02** (Backend · PARTIAL · ADJUSTED · P1 → P1). #2008 builds one observed admin client
  with a 5 s connect and a 30 s read timeout
  (`backend/src/main/java/…/config/RestClientConfig.java:73-97`). Where the pinned Keycloak trust
  bundle exists — in production — `KeycloakService` swaps in the factory of
  `backend/src/main/java/…/config/KeycloakTrustSupport.java:74-75`, which sets no timeout, and the
  same factory backs the internal JWKS fetch on the authentication path, on since #2038. A hung
  Keycloak therefore blocks the user sync and every JWKS refresh; the pinned factory gets the same
  timeouts and HTTP/1.1, trust pinning and hostname verification stay, and a test pins the timeouts.
  The plan lists this among the defects of §9 that are fixed first (D-07).
- **ING-MOD-02** (Ingest · PARTIAL · REPRIORITISED · P2 → P1). Seven of the eight ingest
  `@ConfigurationProperties` are records and `expectedAudiences` is a bean-method parameter, as
  asked. `ingest/src/main/java/…/config/MonitoringScrapeProperties.java:32-50` is still a Lombok
  `@Data` class holding the scrape password, so its generated `toString` prints it; the frontend
  twin is the same, while the backend record redacts it. No log call prints the bean today, so the
  exposure is latent — but a small change that protects a credential is a P1 by the audit's own
  definition. Both become records with a redacting `toString`; G-22 keeps the class of defect out.
- **APPSEC-05** (Frontend · DONE · ADJUSTED · P3 → P1). The session deserializer admits only
  allow-listed types, and production enforces the list since 2026-09-25 (ADR-0206; the host value
  was not re-read). The code and compose default is still `report`
  (`frontend/src/main/java/…/config/RedisSessionConfig.java:89`), so a new host or a lost `.env`
  line silently accepts every class again: the default becomes `enforce`, with `report` as an
  explicit opt-in. The list admits application classes by the prefix `…frontend.model.`
  (`frontend/src/main/java/…/config/SessionTypeAllowList.java:86-87`); moving session-held forms
  into per-domain packages would make `enforce` drop them, and widening the prefix would weaken the
  control — the plan replaces the prefix with an exact, test-derived list (O-02, F2, G-16).
- **CI-03** (CI · PARTIAL · ADJUSTED · P0 → P1). The PIT gate now fails on `PitHelpError` or a
  missing or empty report (`.github/workflows/pitest.yml:45-66`), and the root cause — no test
  profile in PIT's JVMs — is fixed. The only scheduled run since, on 2026-09-23, cancelled the
  backend leg at the 60-minute job timeout after 13 minion timeouts, and the gate still passed on
  the partial report. The leg needs room or a split, and the gate must read PIT's completion line;
  `targetClasses` are keyed on `…service.*` (`build.gradle.kts:267-268`), so a package-by-domain
  backend needs per-domain shards (G-20).
- **CI-SEC-10** (CI · DONE · ADJUSTED · P1 → P1). The ruleset on `main` requires nine checks,
  gitleaks, wrapper validation and the Linters job (which runs actionlint) among them. Folding
  repo-lint into four jobs left two outside the required set: `Self-tests` and `Container checks`
  (`.github/workflows/repo-lint.yml:306,379`), which hold the promtool rule unit tests, the
  monitoring-config validation and the deploy-seam self-tests. A pull request can therefore merge
  with a broken alert rule, which the monitoring-sync rule (REQ-OBS-005 … 011) relies on; both jobs
  belong in the ruleset — a repository setting for the owner (PSB-06).
- **OPS-MON-01** (Operations · PARTIAL · ADJUSTED · P0 → P1). `ContainerRestartLoop` reads the
  podman exporter's series now and has a promtool test (#1984,
  `monitoring/prometheus/alerts/infrastructure.yml:60-61`). Neither series is in
  `REQUIRED_CONTAINER_SERIES` (`scripts/check-conformance.py:74-81`) or behind an `absent()` rule,
  so the only crash-loop alert goes blind again without notice if the exporter stops emitting them,
  and `ContainerMetricsMissing` watches the cgroup collector while its text claims to cover this
  alert. An absence guard (or the conformance entries) and a corrected description close it
  (PSB-08).
- **OPS-SEC-03** (Operations · DONE · ADJUSTED · P1 → P1). Backup and restore drill use
  `db-backend`'s own digest-pinned image. When the unit file cannot be read, both fall back to the
  unpinned PostgreSQL 18 Alpine tag and only log a warning (`scripts/backup.sh:29,91-97`,
  `scripts/restore-drill.sh:19,74-80`) — in exactly the helper that reads the keystore, the internal
  TLS directory and the Redis ACL file. The helper must fail closed and let `BackupStaleOrMissing`
  page, and `rt_unit_image` must require a digest (PSB-07); the host rollout needs the owner's
  approval.
- **BLD-PERF-03** (Build · OPEN · ADJUSTED · P2 → P1/P2). Nothing has been done. Of 231 backend
  `@SpringBootTest` classes, 191 declare `@ActiveProfiles("test")` and 40 do not, although every
  Gradle `Test` task sets the profile (`build.gradle.kts:186`); the frontend has 42 with and 119
  without, and a static approximation finds about 49 distinct backend context keys (the audit
  counted 38 against a cache of 32). Unifying the profile convention and sharing one mock set for
  the controller-security tests is P1 (Phase 0.6), measured with the context-cache debug log;
  module-scoped test slices follow the modularisation (P2), with the cache sized to the module
  count. Security tests keep the real filter chain and the real `@PreAuthorize` beans — a
  `@MockitoBean` on a guarded bean strips its annotations.

#### Remaining priority P2

- **APPSEC-01** (Backend · DONE · ADJUSTED · P0 → P2, inventory module step). #1989 refuses a
  book-in for a foreign owner before any lookup unless `canManageUserInventory` allows it, and
  refuses `personal = true` for someone else
  (`backend/src/main/java/…/service/JobOrderItemProductionService.java:321-334`,
  `JobOrderProductionBookInSecurityTest`). The rule is right, but it is an inventory rule that now
  lives in three domains — `backend/src/main/java/…/service/InventoryItemService.java:446-453`, the
  job-order service above and `backend/src/main/java/…/service/RefineryOrderService.java:592` — and
  the job-order service writes `InventoryItem` rows through the inventory repository itself.
  Inventory's `StockCommands` (§5.3) performs the check once, before any lookup (REQ-INV-032), and
  job order, refinery and exchange call it.
- **APPSEC-02** (Backend · PARTIAL · ADJUSTED · P0 → P2, picker P3). The server rule is complete: a
  refinery order's owner must take part in the linked mission
  (`backend/src/main/java/…/service/RefineryOrderService.java:349-359`, REQ-SEC-042). The mission
  picker still lists every recent mission
  (`frontend/src/main/java/…/controller/RefineryOrderPageController.java:814` reads 1,000 missions
  unfiltered), deferred by #1985. The fix added a refinery → `MissionParticipantRepository` edge and
  one entry each to the sealed `AppException` and `AppExceptionKind`, the shape that blocks
  package-per-domain (PSA-01). Target: a mission query port (`isParticipant`,
  `participatingMissions`) that also serves the picker filter; the server check stays authoritative.
- **BE-PERF-01** (Backend · DONE · ADJUSTED · P2 → P2, identity module step). Request memos in
  `UserMapper` removed the N+1 (`backend/src/main/java/…/mapper/UserMapper.java:70-78`,
  `UserMappingNoNPlusOneTest`). The mechanism spreads identity internals: 16 classes depend on the
  mapper, #2004 added the hangar → `UserMapper` edge, controllers of other domains must prime the
  identity mapper's memo, and the mapper resolves squadron memberships. Domains embed a small member
  reference instead (the kernel's `UserRef`, §5.1), resolved by one identity batch query that
  memoises internally; the semantics of `UserDtoRedaction` and `MissionPeerRedactor` stay.
- **BE-PERF-11** (Backend · DONE · ADJUSTED · P3 → P2, catalogue module step). All 148 to-one
  associations are lazy, guarded by the annotation-keyed rule `toOneAssociationsAreDeclaredLazy` and
  by `LazyToOneReadPathsTest` (#2030). `CachedEntityGraphs` is a catalogue helper in the shared
  `support` package; it exists because catalogue services cache JPA entities that other domains hold
  as `@ManyToOne` targets, and it disappears when the catalogue caches read models (§5.6, G-19).
  `LazyToOneReadPathsTest` stays green through every move.
- **BE-PERF-12** (Backend · PARTIAL · ADJUSTED · P2 → P2). #2004 added `findPlainById` (22 call
  sites), but the graphed `findById` keeps the default name
  (`backend/src/main/java/…/repository/UserRepository.java:302-305`), so new code regresses by
  default: three reference-only callers use it —
  `backend/src/main/java/…/service/JobOrderItemProductionService.java:371` and, added later by the
  exchange epic, `backend/src/main/java/…/service/exchange/ExchangeAccountCheckService.java:68` and
  `backend/src/main/java/…/service/exchange/ExchangeStockWriteService.java:798`. The names are
  inverted — a plain `findById` and an explicitly named graphed lookup for authentication and
  `/users/me` — and other domains move to an identity member-reference port (47 classes outside the
  repository layer use `UserRepository`). Authentication keeps loading roles and permissions inside
  its transaction.
- **BE-SIMP-05** (Backend · DONE · SUPERSEDED-BY-MODULARISATION · P2 → P2, mission module step). Six
  private `redactForPeer` overloads replace the inline blocks
  (`backend/src/main/java/…/controller/MissionController.java:1446-1512`), guarded by
  `peerReadableMissionEndpointsMustRedactPii` with a floor of ten. Redaction still happens per
  handler in the web layer, and the rule selects by package and hard-coded DTO names, so a move can
  de-select handlers silently while ten remain. In the target the mission read API returns
  viewer-specific views, redacted inside the module through `MissionPeerRedactor`'s explicit
  constructors; until then the rule is re-keyed in the same commit as any mission move (G-01).
- **BE-SIMP-09** (Backend · DONE · ADJUSTED · P2 → P2, org-unit module step). The typed
  `RequestMemo` with owner-private keys is right and created no central key registry. The caller's
  org-unit memberships are memoised three times under different keys (`RequestScopeResolver`,
  `OrgRoleManagementSecurityService`, `UserMapper`), so one request can read them three times, and
  `RequestScopeResolver` memoises a job-order decision that SpEL reaches as
  `@ownerScopeService.canViewJobOrders()`. The org-unit module owns one memoised caller-memberships
  query and the job-order module owns `canViewJobOrders`; both stay request-scoped and keep the
  admin-pin semantics.
- **BLD-SIMP-06** (Build · DONE · ADJUSTED · P2 → P2, before any Gradle extraction). Conventions
  keyed on plugin id (`build.gradle.kts:122-442`) are inherited by a new subproject. Test heap and
  coverage floors are keyed on `project.name` (`build.gradle.kts:184,228-241`), so code moved out of
  `backend` would silently fall from the 0.82/0.65 floors to 0.50/0.40. Before the Gradle extraction
  of Phase 5 the logic moves into convention plugins, and a missing per-module floor fails the build
  (G-20, §5.8).
- **IMG-SIMP-14** (Build · DONE · ADJUSTED · P3 → P2, only for a Gradle extraction). One
  `docker/app/Dockerfile` replaced the three, but its build stage copies each module's build file by
  name, copies only the module's and `logging-support`'s main sources, and admits only the three
  application modules (`docker/app/Dockerfile:12-27`). A `backend-exchange` or `backend-bank`
  subproject needs COPY lines or a list derived from `settings.gradle.kts`, and `.dockerignore` must
  keep excluding test sources and secrets (§5.8). Package-level modules are unaffected.
- **FE-MOD-03** (Frontend · PARTIAL · ADJUSTED · P2 → P2). The ESLint rules are done
  (`frontend/eslint.config.mjs:37-39`). 44 of 100 scripts carry `// @ts-check`; the 56 unchecked
  files hold 24,621 of 39,577 lines, including the three largest (`mission-detail.js` 3,249,
  `bank.js` 2,400, `orders-detail.js` 2,325). "Opt in when you touch it" becomes a ratchet per
  domain folder, a folder counts as migrated only when fully checked, and the three largest files
  are split by section first (§8.2).
- **FE-PERF-01** (Frontend · DONE · ADJUSTED · P2 → P2). One `GET /api/v1/me/layout` per request
  replaced the three or four advice calls (#2004, #2020). The backend endpoint composes four
  domains: `backend/src/main/java/…/controller/MeController.java:104-115` computes blueprint,
  job-order, bank and inventory capability flags from `OwnerScopeService`, `AuthHelperService` and
  the inventory properties. The single read stays, but each domain publishes its capability query in
  its module API and a composing module calls them (PSB-03); the flags steer menus only, and every
  endpoint keeps its `@PreAuthorize`.
- **FE-SIMP-02** (Frontend · PARTIAL · SUPERSEDED-BY-MODULARISATION · P2 → P2). The first part is
  done: every verb goes through one private `exchange(...)`
  (`frontend/src/main/java/…/service/BackendApiClient.java:395-407`). The typed clients are the
  frontend half of the domain split — 623 call sites in 81 files, 362 of them with a concatenated
  path (§4.3 of the plan, from a separate scan, counts 624 and 356). The plan adopts them as
  per-domain typed clients over the one filtered `webClient` bean (§5.9, F1–F3, G-17), with the
  error mapping and `basetool_backend_client_errors_total` placed where no client can bypass them.
- **FE-SIMP-03** (Frontend · PARTIAL · ADJUSTED · P2 → P2). The shared „Lager“ script exists
  (`frontend/src/main/resources/static/js/inventory-common.js`, loaded only by the two „Lager“
  pages), and `orders-detail.js` has no German literal left. `mission-detail.js` keeps seven
  `typeof MSG_X !== 'undefined' ? MSG_X : '…'` fallbacks with German text and two German console
  messages, and `I18nDictionaryCoverageTest` sees neither shape and lists its directory
  non-recursively (`frontend/src/test/java/…/i18n/I18nDictionaryCoverageTest.java:47,70`). The
  script moves onto `krtI18nText`, the test learns that shape and walks recursively (G-20); it also
  removes a raw `err.message` from two toasts (PSB-09).
- **CI-01** (CI · DONE · ADJUSTED · P0 → P2). The CodeQL decision holds: the default setup is off
  and the advanced workflow runs with dependency caching disabled, next to a cache janitor. The
  audit's guard is not met: the Actions cache held 9.39 of 10 GiB on 2026-09-29, driven by 17 Gradle
  dependency caches (5,535 MiB, all younger than 24 h, which the janitor keeps) and one 1,199 MiB
  CodeQL cache read daily by GitHub's code-quality workflow. Keeping only the newest Gradle entry
  per key family (or letting only `ci.yml` write `main` caches) and warning above 8 GiB closes it
  (PSB-11).
- **CI-SEC-12** (CI · PARTIAL · ADJUSTED · P1 → P2). actionlint, hadolint, gitleaks and zizmor are
  hash-pinned. PyYAML and ansible-core/ansible-lint are pinned by version only, the Ansible
  collections by ranges, and a newer `npx --yes markdownlint-cli2` fetch
  (`.github/workflows/exchange-docs.yml:58`, added 2026-09-27) resolves without a lockfile.
  Requirements files with `--require-hashes`, exact collection versions and markdownlint from a
  lockfile finish the class (PSB-12); the jobs hold no write token, which limits the impact.
- **CI-SEC-16** (CI · PARTIAL · ADJUSTED · P2 → P2). The personal access tokens are gone; release
  and refresh jobs mint one-hour GitHub App tokens. The App's private key is still a repository-wide
  secret, because no `release` environment restricted to `main` exists. The key moves into one and
  the three token-minting jobs declare it — after one dry run shows whether a `main`-only policy
  admits the `pull_request: closed` trigger of `release-publish.yml`, or publishing moves to a push
  on `main` (PSB-13). **Closed in code 2026-10-04 (#2387, #2390):** all three jobs declare
  `environment: release`, `release-publish` now on the `main` push; the owner's environment step and
  the deletion of the repository secret remain.

#### Remaining priority P3

- **BE-SIMP-08** (Backend · REGRESSED · CONFIRMED · P1 → P3). #2011 left one `trimToNull`
  (`backend/src/main/java/…/support/StringNormalization.java:82`); commit `a2e82ff34` of the
  exchange registry added a new copy, `blankToNull`
  (`backend/src/main/java/…/service/exchange/ExchangeRegistryService.java:413-416`). The copy folds
  into `StringNormalization`, which becomes part of the kernel; a dedicated gate is not worth it.
  **Done 2026-10-04:** the registry calls `StringNormalization.trimToNull`, the copy is gone.
- **BE-SIMP-10** (Backend · PARTIAL · ADJUSTED · P1 → P3). The backend half is done (#2011, 659
  names; 19 deliberate ones remain, mostly clashes between the Jakarta and JetBrains `@NotNull`).
  The frontend half never ran: 482 inline fully qualified names in 42 files today (the audit counted
  420). They are shortened per frontend package right before it moves into its domain package, as a
  separate commit, so the move diff stays reviewable.
- **BE-PERF-15** (Backend · DONE · ADJUSTED · P2 → P3). The N+1 loops are gone (#2004), but the fix
  added `OrgRoleManagementSecurityService`'s caller-membership memo beside the one
  `RequestScopeResolver` already kept. Both fold into one org-unit-owned query (with BE-SIMP-09);
  the memo feeds appointment authorization, so it stays request-scoped.
- **SEC-16** (Build · DONE · ADJUSTED · P1 → P3). All nine suppressions in
  `config/owasp/dependency-check-suppressions.xml` expire on 2026-12-22, so the weekly scan turns
  red that day by design. The header comment that described the renewal went with the ADR-0214 sweep
  (#2074), and no document describes it now; the procedure belongs in CONTRIBUTING (§15) — renewal
  re-verifies each false positive and never bumps the date blindly.
- **TST-18** (Build · DONE · ADJUSTED · P1 → P3). Redis tests use the production digest through
  `test-support/src/main/java/…/containers/TestImages.java:39-40`, checked against compose and the
  Quadlet unit. The same drift exists for PostgreSQL: the Testcontainers JDBC URL uses a floating
  tag (`backend/src/test/resources/application-test.yml:7`) while compose pins a digest.
  `TestImages` extends to PostgreSQL through a container bean or a JDBC URL that accepts a digest —
  which of the two works is to be settled with the Testcontainers documentation.
- **BLD-PERF-04** (Build · PARTIAL · CONFIRMED · P2 → P3). refreshVersions runs only with
  `-PrefreshVersions` (`settings.gradle.kts:1-5`), and CI builds strictly with the configuration
  cache since 2026-09-23. `gradle.properties` still has no configuration-cache default for developer
  builds ("left for after a soak", #2019); it can be switched on. More subprojects after a Gradle
  extraction raise the configuration time, which the cache offsets (§8.4).
- **ING-SEC-04** (Ingest · DONE · ADJUSTED · P3 → P3). Per-service leaf certificates and the relay's
  hostname check are in place (ADR-0211, #2034, #2036). The jar default of
  `app.ingest.verify-backend-hostname` is `false` (`ingest/src/main/resources/application.yml:50`)
  and only the deployment switches it on, so a jar started outside compose or Quadlet under a
  non-dev profile skips the check. The default flips to `true`, dev and test opt out, and the E2E
  stack verifies it.
- **CI-07** (CI · DONE · ADJUSTED · P3 → P3). The plan job reuses per-module images from the newest
  verified ancestor (ADR-0210; #2031, #2033, #2046). `.github/scripts/image_reuse_plan.py:44-112`
  derives each module's inputs from the Dockerfile's COPY lines, so a new `backend-*` subproject
  would count as shared input and rebuild all three images; the SBOM-coverage gate, the sandbox path
  filter, the Flyway numbering check and PIT carry the same assumption. Module ownership is derived
  from the Gradle project graph before the Phase 5 extraction, and every reuse gate stays (PSB-05).
- **OPS-SEC-08** (Operations · DONE · REPRIORITISED · P2 → P3). #1992 replaced the false sudoers
  comment and, after the ADR-0214 sweep, the plain statement lives in `ansible/README.md:43`:
  `podman *` as `iri` lets `deploy` run any code as `iri`, not as root. A wrapper that admits only
  the podman sub-commands the `rt_*` seam uses is the only way to narrow `deploy` further — worth it
  now that the seam is Podman-only, but not urgent (PSB-14).

#### Nothing left to do

- **ING-PERF-01** (Ingest · SUPERSEDED · CONFIRMED · P1 → closed). ING-MOD-01 (#2008) removed
  Reactor Netty from ingest; the JDK client's keep-alive is pinned below Tomcat's by
  `RelayIdleConnectionBoundTest`. Skipping the finding in #1990 was right.
- **THEME-SIMP-01** (Keycloak · PARTIAL · DROPPED · P2 → closed). No inline `onsubmit` and no TTF
  file is left (#1990), so the CSP benefit is realised. Serving the byte-identical Lato fonts once
  from a shared theme would save about 84 KB in one image with no runtime effect, and depends on
  Keycloak's theme-import mechanics, which would need a test-stack check; dropped.

### New observations made while verifying

- **PSA-01 — The error kernel enumerates domain errors, and Java forbids it to follow them.** The
  sealed `AppException` permits 13 subclasses, six of them domain exceptions, and `AppExceptionKind`
  holds domain codes (`backend/src/main/java/…/exception/AppException.java:37-50`); #1985 added one
  of each. javac 25 refuses a sealed class in the unnamed module that permits a subclass in another
  package, while the same classes in one package compile. Proposed: a sealed kernel of generic
  kinds, one non-sealed domain-problem base, per-domain code enums and a registry test — the
  recommendation of O-01 (§5.5).
- **PSA-02 — The pinned Keycloak client has no timeouts on the production authentication path.** See
  BE-MOD-02. Proposed: the timeouts and HTTP version of `RestClientConfig`, trust unchanged, a test
  that asserts them; fixed first under D-07 (§9).
- **PSA-03 — Hand-mirrored platform classes have diverged across the three applications.**
  `ManagementPortSecurityConfig`, `MonitoringScrapeProperties` (the backend record redacts the
  password, the frontend and ingest classes print it), `TracingEnabledMetric`, `CorrelationIdFilter`
  and `StartupBannerListener` differ in every app; `KeycloakTrustSupport` differs only in Javadoc.
  Proposed: ING-MOD-02 and parity tests for the security-relevant mirrors now, a scope-closed
  platform module under its own ADR later (ADR-0205 closes `logging-support` to log hygiene). The
  plan adopts the `toString` half as G-22 and keeps `logging-support` and `test-support` as they are
  (§4.5).
- **PSA-04 — September fixes grew `support` into a domain-helper hub.** 63 classes, among them
  mission, inventory and job-order helpers and 18 of the 27 properties records; September added the
  generic `RequestMemo`, the catalogue-specific `CachedEntityGraphs` and three retention records.
  The leaf rule's messages tell authors to put shared logic there
  (`backend/src/test/java/…/ArchitectureTest.java:596-598,609-611,624-626`). Proposed and adopted in
  Phase 1 (§7.3): a small kernel plus per-domain internal packages, with the rules of the redaction
  and viewer-access helpers re-keyed in the same commit.
- **PSA-05 — Knowledge-base drift.** The knowledge base's note on the September audit omits
  BE-SIMP-04, BE-SIMP-05 and BLD-PERF-03 and marks APPSEC-02, THEME-SIMP-01 and BE-SIMP-10 as done
  without their open halves; its system notes still claim 16 properties records, a single
  `trimToNull`, a `findPlainById` caller list without the three reference-only callers, an OWASP
  header that explains the renewal, and that all ingest properties are records. Proposed: correct
  each note, dated — the knowledge-base half of §15.
- **PSB-01 — Per-domain typed backend clients over the single filter chain** (FE-SIMP-02). Found:
  `BackendApiClient` is one 539-line class; 13 other classes inject a `WebClient` bean directly — 11
  the filtered `webClient`, plus the SSE relay and the live-sync probe on their own beans — against
  arc42 §4.1's "exactly one class"; Spring Framework 7.0.9 already ships HTTP interface clients.
  Proposed: move the failure semantics into a filter or adapter, build proxies from
  `WebClientAdapter.create(webClient)`, one interface per domain, a rule confining `WebClient`
  injection, and amend ADR-0032 and arc42 §4.1. Adopted in §5.9 and F1–F3, guarded by G-17.
- **PSB-02 — Session allow-list: `enforce` by default, per-domain packages designed before any
  move** (APPSEC-05). Adopted in stricter form: an exact, test-derived list of session-bound types
  instead of package names (O-02, F2, G-16).
- **PSB-03 — The layout read becomes a composition of domain capability queries** (FE-PERF-01). A
  composing module owns the endpoint and calls capability queries that each domain publishes; module
  rules allow composer → domain API and forbid the reverse; the response shape stays. The frontend
  keeps its one read in `kernel.layout` (§5.9); the plan does not yet name the backend's composing
  module.
- **PSB-04 — PIT must finish, and later run per domain** (CI-03). Gate on the completion line, raise
  the leg's timeout, shard per domain after the move. Adopted in G-20.
- **PSB-05 — CI structures keyed on today's modules and folders** (CI-07, CI-06, CI-03). The app
  Dockerfile, the image-reuse plan, the SBOM-coverage gate, the sandbox path filter, the Flyway
  numbering check and PIT assume today's six Gradle modules — nothing to do for package-level
  modules, a to-do list for any Gradle extraction; `I18nDictionaryCoverageTest` and
  `TemplateCommentHygieneTest` list their directories non-recursively and would silently narrow
  under per-domain folders. Adopted in G-20 and the prerequisites of §5.8.
- **PSB-06 — Make `Self-tests` and `Container checks` required** (CI-SEC-10), and give
  `deploy-script.yml` an always-running leg so it can be required too. A repository setting for the
  owner; listed as P1 in §11.
- **PSB-07 — The backup helper fails closed** (OPS-SEC-03): refuse to run without a digest-pinned
  image and let the backup alert page. P1 in §11.
- **PSB-08 — An absence guard for the crash-loop alert** (OPS-MON-01), with a promtool case for the
  missing series and a corrected `ContainerMetricsMissing` description. P1 in §11.
- **PSB-09 — Finish the literal-fallback class and fix the i18n gate's blind spots** (FE-SIMP-03).
  The recursive walk is part of G-20.
- **PSB-10 — A per-domain type-check ratchet** (FE-MOD-03); raising `ecmaVersion` is a separate
  question that needs a browser baseline first. Adopted in §8.2, with the baseline decided as D-16
  (Baseline 2025, ES2025).
- **PSB-11 — Actions cache budget** (CI-01): keep the newest Gradle entry per key family and warn
  above 8 GiB, keeping the janitor's fork guard. Done (#2387).
- **PSB-12 — Hash the remaining CI tool fetches** (CI-SEC-12). Done (#2387).
- **PSB-13 — Confine the release App key to `main`** (CI-SEC-16), after a dry run of the trigger
  question. Partly done (#2387): the trigger question is answered by GitHub's documentation — a
  `pull_request` run is evaluated as `refs/pull/<n>/merge`, which a `main`-only rule refuses — so
  only the `main`-ref jobs moved; `release-publish` needs a trigger decision (ADR-0201 amendment 3).
  Done in code (#2390) after the owner decided on 2026-10-04 for a push to `main`: a `detect` job
  recognises the release merge through `commits/{sha}/pulls`, and only the `publish` job enters the
  `release` environment (ADR-0201 amendment 4). The owner creates `release` (branch rule `main`)
  with the secret, then deletes the repository secret.
- **PSB-14 — Optional sudo wrapper for `deploy`** (OPS-SEC-08, now P3); every new `rt_*` call would
  have to be added to it. Done in code (#2387), as a sudoers `Cmnd_Alias` rather than a wrapper
  script; repo-lint fails when a new call is missing from it. The host rollout is the owner's.
- **PSB-15 — Name the frontend shared kernel the September work created.** In scripts: `krtFetch`
  (used by 68 scripts), `krtModal` (35), `krtEvents` (32), `krtLiveSync` (29), `krtI18nText` (27)
  and the escape helpers; in Java: `BackendErrorResponses`, `GlobalExceptionHandler`,
  `SessionIdFingerprint`, `LayoutContextLoader` and the advices, `WebClientConfig`. Domain globals
  stay inside their domain, with one deliberate embed (`krtMaterialRelease` of the „Materialbörse“
  on the „Lager“ page). Proposed: declare the kernel, let domains use it but not each other, publish
  cross-domain UI embeds explicitly, enforce with ArchUnit and a per-folder ESLint rule, and move
  `krtI18nText` out of the error beacon. Adopted in §5.9 (kernel packages) and §8.2 (namespaces, a
  `no-implicit-globals` ratchet, a boundary test).

Documentation drift noted along the way, for §15: the knowledge base calls CI-03 fixed and states
that no script keeps a literal default; its note on the September audit marks CI-01 done although
the cache guard is not met; arc42 §4.1 claims "exactly one class" reaches the backend (corrected
with the plan); the description of `ContainerMetricsMissing`
(`monitoring/prometheus/alerts/infrastructure.yml:78`) names the restart-loop alert as blinded by
the cgroup collector, which that alert no longer reads.

### Sibling repositories (out of scope)

Listed with the status the knowledge base records; not re-verified (D-06).

| ID | Repository | Priority | Title | Status | Recorded status |
| --- | --- | --- | --- | --- | --- |
| SIB-SEC-06 | basetool-android | P1 | The release workflow's `tag` input is unchecked | OUT-OF-SCOPE | Done (basetool-android #178–#181) |
| SIB-SEC-07 | basetool-android | P1 | Release writes secrets into the script, keystore password in argv | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-SEC-08 | basetool-android | P1 | OkHttp can retry a POST that was already sent | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-SIMP-03 | basetool-android | P1 | `fullBackupContent` and `backup_rules.xml` without effect since minSdk 31 | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-CI-01 | basetool-android | P2 | Three instrumented tests run in no CI job | OUT-OF-SCOPE | Done (#178–#181); runs on a path filter and nightly, as no label exists |
| SIB-CI-02 | basetool-android | P2 | CI builds a `devRelease` variant nobody ships | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-CI-05 | basetool-android | P2 | The CodeQL job also discards the dependency cache | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-MOD-01 | basetool-android | P2 | 606 `x.value = x.value.copy(...)` instead of `update {}` | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-SIMP-01 | basetool-android | P2 | 69 hand-written `when (result)` blocks instead of `ApiResult.map` | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-SIMP-02 | basetool-android | P2 | 17 nearly identical `*Page` classes | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-SIMP-04 | basetool-android | P2 | Five workflows repeat the same five setup steps | OUT-OF-SCOPE | Done (#178–#181) |
| SIB-CI-03 | basetool-sc-extractor | P1 | Extractor and reader without Dependabot, workflow lint, timeouts and Kotlin analysis | OUT-OF-SCOPE | Done for the extractor (basetool-sc-extractor #58, #59); the reader half does not apply |
| SIB-MOD-02 | basetool-sc-extractor | P1 | Extra JetBrains Space repository and an outdated Material3 accessor | OUT-OF-SCOPE | Done for the extractor (#58, #59); the reader half does not apply |
| SIB-SEC-02 | basetool-sc-extractor | P1 | Extractor and reader releases run with tag-pinned actions and write and OIDC permissions | OUT-OF-SCOPE | Done for the extractor (#58, #59); the reader half does not apply |
| SIB-SEC-05 | basetool-sc-extractor | P1 | The self-updater installs without a hash when the digest is missing | OUT-OF-SCOPE | Done (#58, #59) |
| SIB-SEC-09 | basetool-sc-extractor | P1 | Ingest URL check by string prefix | OUT-OF-SCOPE | Done (#58, #59) |
| SIB-PERF-01 | basetool-sc-extractor | P2 | Thumbnails by full decoding, one after another | OUT-OF-SCOPE | Done (#58, #59) |
| SIB-SEC-04 | basetool-sc-extractor | P3 | The DPoP key sits in the same credential record as the refresh token | OUT-OF-SCOPE | Decided 2026-09-22 (non-exportable CNG key) and done (#58, #59); the spec wording is basetool #1998 |
| SIB-CI-04 | P4K reader | P2 | MSI packaging depends on an ignored `tools\wix3` | OUT-OF-SCOPE | Done 2026-09-23 locally (the reader stays unversioned by decision) |
| SIB-SEC-01 | P4K reader | P3 | The P4K reader stays local — decided 2026-09-22 | OUT-OF-SCOPE | Decided as won't-do: no repository; its CI recommendations do not apply |

## July 2026 modularity audit

### What it was

A whole-application modularity and maintainability audit ran on 2026-07-11. Its actionable findings
shipped the same day as PR #1256 (159 files: 26 behaviour-preserving commits, a merge of `main` and
a code-quality fix); six more service splits became issues #1250–#1255 and were merged the same
afternoon as PRs #1257–#1262. The report itself never entered the repository or the knowledge base.
The finding list below is reconstructed from the 28 commits of #1256, the issues and PRs, and the
knowledge base: commit messages cite items QW1–QW4, #10, #14 (topic 7, service splits), #15 and #16
(topic 11, the import engine); anything the report numbered otherwise is unknown. The review of
#1256 holds only two code-quality notes about unread locals, fixed in `df7b7d33`. At the audit's
base commit `f5703ab1` the repository had 1,477 main Java files, 49 of them over 600 lines; at
`95e945326` it has 2,044, 53 of them over 600 lines.

Every finding shipped and is still in the code, with three gaps: `HangarPageModelLoader` was never
built, `CachedCatalogListLoader` has one consumer, and the split of `GlobalExceptionHandler` is
open.

### Findings

| ID | Finding | Delivered | Status | Evidence today | Verdict | Why |
| --- | --- | --- | --- | --- | --- | --- |
| J-DIAG | Diagnosis: the architecture is sound; the debt is size and duplication inside correct layers | — | — | 49 of 1,477 main files over 600 lines at `f5703ab1`, 53 of 2,044 today; `OwnerScopeService` used by 34 classes, `AuthHelperService` by 46; `AuditService.record` called from 57 files | SUPERSEDED-BY-MODULARISATION | True for layers, not for domains: layer-internal splits do not reduce cross-domain coupling, which sits in a few hub beans and in the package-by-layer layout. |
| J-QW1 | `PageResponse.of` at list endpoints | #1256 (`ed65cfec`) | DONE | one `new PageResponse<` left, in the factory (`backend/src/main/java/…/model/dto/PageResponse.java:49`); `.of` used 78 times in 46 files | CONFIRMED | `PageResponse` becomes a kernel type. |
| J-QW2 | AJAX error relay consolidated on `BackendErrorResponses` | #1256 (`288e50f1`) | DONE | extended by FE-SIMP-01: `relay` in 28 files; 125 bespoke `catch (BackendServiceException` blocks remain in 40 controllers | CONFIRMED | The frontend's web kernel. |
| J-QW3 | Leaf de-duplications: `InventoryAuditLabels`, `OrgUnitLabels`, `UexValues`, `QuantityTypeRounding`, `MapPayloadValues`, material alias guards, `@EvictAllMaterialCaches` | #1256 | DONE | all in use (`UexValues` 149 times); they sit in `support`, where 43 of 63 classes carry a domain name | ADJUSTED | Re-home per domain or kernel; the `InventoryAuditLabels` output stays byte-identical (audit viewer, exports). |
| J-QW4 | `ObservationPrivacyFilter` parity guard across the three apps | #1256 (`1e4b3720`) | DONE | three 106-line copies plus `ObservationPrivacyFilterMirrorParityTest` | CONFIRMED | `logging-support` holds no beans (ADR-0205), so the parity test stays the guard. |
| J-S10 | `ProblemResponseFactory` (item #10) | #1256 (`d4c3860e`) | DONE | `backend/src/main/java/…/support/ProblemResponseFactory.java:40,60,86`; 9 users | CONFIRMED | One sanitised assembly point for problem bodies; kernel. |
| J-S10b | Split `GlobalExceptionHandler` per exception family (prerequisite laid in July) | — | DONE 2026-10-10 | 1,181 lines, 23 `@ExceptionHandler` methods, now five family classes and the advice | REPRIORITISED | By family yes, never per domain: one advice keeps one handler order and one disclosure path (§5.5). |
| J-RELAY | `BackendErrorResponses.relay` wrapper (July: adopted in one controller only) | #1256 (`210b6569`) | DONE | adopted widely by #2010 and #2014 | DROPPED | Finished by FE-SIMP-01. |
| J-REDACT | Peer-redactor extraction, explicit full-field reconstruction kept | #1256 (`ee34c23c`) | DONE | now `MissionPeerRedactor` (`backend/src/main/java/…/support/MissionPeerRedactor.java:56-213`); rule in `backend/src/test/java/…/ArchitectureTest.java:1129-1162` | ADJUSTED | Moves into the mission module; completeness for nested records is untested (PRV-10). |
| J-ROLES | `Roles.HAS_ROLE_*` constants for `@PreAuthorize` | #1256 (`425c08e7`) | DONE | `backend/src/main/java/…/support/Roles.java:83-110`; 126 uses in 51 files | CONFIRMED | Part of the platform's access core. |
| J-S15 | Four view assemblers (item #15) | #1256 | PARTIAL | `BankDashboardViewAssembler`, `MissionDetailModelBuilder` and `SecurityHeaders` exist; `HangarPageModelLoader` was never built | ADJUSTED | Assemblers move with their domain; `SecurityHeaders` stays the one CSP policy. |
| J-S16 | Import-engine split (item #16, topic 11) | #1256 | DONE | `HangarImportService` 851 → 208 lines; `BlueprintFuzzyMatcher.topMatches` (`backend/src/main/java/…/service/BlueprintFuzzyMatcher.java:97`) serves the refinery import | ADJUSTED | Rename and relocate the now generic matcher (PRV-14); the parsers keep their size caps. **Done 2026-10-04:** `kernel.FuzzyNameMatcher` (`topMatches`); the blueprint-only `topSuggestions` wrapper moved into `BlueprintImportService`, its only caller. |
| J-SCWIKI | `ScWikiOrphanSweep` gates the tombstone sweep on a non-empty seen set | #1256 (`8a64acb3`) | DONE | `backend/src/main/java/…/service/scwiki/ScWikiOrphanSweep.java:58` | CONFIRMED | Catalogue internal. |
| J-CACHEDCAT | `CachedCatalogListLoader`, with its adoption left as a "mechanical follow-up" | #1256 (hangar only) | PARTIAL | one consumer (`frontend/src/main/java/…/controller/HangarPageController.java:88`); 28 `getCached` sites in 10 controllers | REPRIORITISED | Folds into the per-domain typed clients (PRV-07, §5.9). |
| J-T7-1 | `OrgUnitMembershipQueryService` split | #1256 | DONE | 463 lines, used by 12 classes of 7 domains | CONFIRMED | Seed of the org-unit read API. |
| J-T7-2 | `JobOrderQueryService` split | #1256 | DONE | 367 lines; whitelisted at `backend/src/test/java/…/ArchitectureTest.java:989` | CONFIRMED | The job order's internal read side. |
| J-T7-3 | `OrgChartReadService` split | #1256 | DONE | 333 lines; `OrgChartService` keeps nine `MANDATORY` mirror hooks (`backend/src/main/java/…/service/OrgChartService.java:272-437`) | CONFIRMED | The hooks are the org unit → org chart coupling (J-R02). |
| J-T7-4 | Three layout advices instead of `SquadronContextAdvice` | #1257 | DONE | complemented by one `LayoutContextLoader` read (FE-PERF-01, #2020) | ADJUSTED | The frontend's layout kernel (`kernel.layout`, §5.9). |
| J-T7-5 | `OperationPayoutService` and `OperationPayoutCalculator` split | #1259 | DONE | now org-unit aware (`backend/src/main/java/…/service/OperationPayoutService.java:103,245`, ADR-0150) but missing from the scoped-service whitelist (`backend/src/test/java/…/ArchitectureTest.java:980-992`) | ADJUSTED | Needs a guard: G-05 replaces the whitelist. |
| J-T7-6 | `UserDeletionService`, `UserRegistrationService` and `UserReconciliationService` split | #1261 | DONE | 369, 432 and 573 lines; `UserDeletionService` depends on 28 classes across all domains | CONFIRMED | Erasure needs ordered per-module participants, not events (§7.6). |
| J-T7-7 | `BankPostingWriter` and `BankBookingGuards` split | #1260 | DONE | `backend/src/main/java/…/service/BankPostingWriter.java:57` is `MANDATORY`; `BankLedgerService` 794 lines | CONFIRMED | Bank internal. |
| J-T7-8 | `OrgUnitBankResponsibilityService`, free of `OwnerScopeService` | #1262 | DONE | 256 lines, no scope dependency | CONFIRMED | The bank module's published read of responsible holders. |
| J-T7-9 | `MaterialExchangeBoardService` for reads and redaction | #1258 | DONE | `detailDto` (`backend/src/main/java/…/service/MaterialExchangeBoardService.java:305`) also shapes the write responses | CONFIRMED | One redaction path for „Materialbörse“ reads and writes. |

### Rejected proposals

The knowledge base records eight constructs that were proposed for simplification and rejected as
load-bearing; the commits of #1256 add seven narrower rejections. 14 of the 15 hold under the
modular target; the one that does not is J-R05.

| ID | Rejected proposal | Evidence today | Verdict | Why |
| --- | --- | --- | --- | --- |
| J-R01 | One aggregate lock instead of the mission's per-section version counters | `backend/src/main/java/…/model/Mission.java:64` (`@DynamicUpdate`, 36 excluded fields); `backend/src/main/java/…/repository/MissionRepository.java:240-324`; `backend/src/main/java/…/support/MissionSectionVersions.java:49,166,176` | REJECTION-HOLDS | Coarse locking is a defect by project rule, and every use site is mission-internal; `MissionSectionVersions` moves from `support` into the mission module. |
| J-R02 | Simplify the `…WithinTransaction` / `MANDATORY` hops | 31 `MANDATORY` methods in 15 classes; 21 hops cross the proposed module boundaries (next table) | REJECTION-HOLDS | Atomicity is the point; the plan keeps it and fixes the direction with command APIs and observer SPIs in the caller's transaction (§5.3). |
| J-R03 | Remove the find-or-create `REQUIRES_NEW` self-proxy retry | 17 `REQUIRES_NEW` methods, 9 self `ObjectProvider`s, all intra-class (e.g. `backend/src/main/java/…/service/OperationPayoutService.java:112,302`, `backend/src/main/java/…/service/MaterialClaimService.java:96,102,262`) | REJECTION-HOLDS | PostgreSQL aborts the transaction, so only a new one can retry; no hop crosses a module, but a class split must retype the provider, as #1259 did. |
| J-R04 | Simplify bulk-update-after-loop and the lock taken before summing material claims | `backend/src/main/java/…/service/JobOrderHandoverService.java:154,252,264`; `backend/src/main/java/…/repository/JobOrderRepository.java:271`, used by `backend/src/main/java/…/service/MaterialClaimService.java:280` | REJECTION-HOLDS | Both prevent shipped bugs; the handover's bulk delete of inventory allocation rows is a boundary the inventory command API formalises. |
| J-R05 | Split `BackendApiClient` ("it fragments the one Resilience4j pass") | resilience is a filter on the `webClient` bean (ADR-0032; `frontend/src/main/java/…/config/WebClientConfig.java:311-345`, applied at `:483-485`); 83 classes depend on `BackendApiClient`; 11 controllers inject `webClient` directly | REJECTION-REVISIT | The reason holds for per-domain `WebClient` beans or resilience instances, not for per-domain clients built on the one bean; the plan keeps the reason and drops "exactly one class" (§5.9). |
| J-R06 | A generic CRUD or sync base template | no base service exists; `auditService.record` 205 calls in 57 files, `bankAuditService.record` 51 in 14 | REJECTION-HOLDS | A template would couple every module and hide the audit call; per-module audit contracts guard better (PRV-11). |
| J-R07 | Wither DTOs in peer redaction | `backend/src/main/java/…/support/MissionPeerRedactor.java:72,163,194` construct explicitly; `MissionDto` has no `@With` or `@Builder` | REJECTION-HOLDS | The explicit constructor forces a redaction decision for every new field; nested records need a reflective test (PRV-10). |
| J-R08 | Refactor the ADR-0020 bank seam at will | `OrgUnitBankAccessService` 1,753 lines, 56 dependencies, used by three classes; rules at `backend/src/test/java/…/ArchitectureTest.java:406,1794,1853` | REJECTION-HOLDS | Modules strengthen the seam: its rules are re-keyed on module membership and class literals instead of the `Bank*` prefix (§5.4). |
| J-R09 | Coarser locks | project rule; per-account bank locks (`backend/src/main/java/…/repository/BankAccountRepository.java:54,65`) | REJECTION-HOLDS | Unrelated to the package structure. |
| J-R10 | `@PreAuthorize` meta-annotations instead of inline SpEL constants | ArchUnit reads the direct annotation value (`backend/src/test/java/…/ArchitectureTest.java:355-360,1090-1093`); 155 SpEL bean references | REJECTION-HOLDS | Meta-annotations would hide the SpEL from the rules; what is missing is a test that resolves the bean references (G-04). |
| J-R11 | Extract an `OrgUnitBankSettingsAssembler` (#1262) | `backend/src/main/java/…/service/OrgUnitBankAccessService.java:241-244` is scope-backed | REJECTION-HOLDS | Moving it would leak scope logic out of the seam. |
| J-R12 | Parity assertions for the other cross-module twins | `MonitoringScrapeProperties` 60/61/67 lines, `NotificationStreamObservationPredicate` 62/59, `StringNormalization` 103/107 | REJECTION-HOLDS | The differences are real; what must not differ — no credential in `toString` — is G-22's job (PSA-03). |
| J-R13 | Route the commodity and item SC Wiki sweeps through `ScWikiOrphanSweep` | the sweep is used by three classes | REJECTION-HOLDS | Different sweep shapes; parameterising would hide the gate. |
| J-R14 | Collapse the two UEX flag semantics | `backend/src/main/java/…/support/UexValues.java:84` (null → false), `:98` (null → null) | REJECTION-HOLDS | The fork is semantic. |
| J-R15 | Move fee resolution into `OperationPayoutCalculator` | `backend/src/main/java/…/service/OperationPayoutService.java:147,359` reads system settings | REJECTION-HOLDS | Not a pure function. |

Two narrower July decisions also stand: the „Materialbörse“ write service projects its answers
through the board service, so there is one redaction path (#1258), and the one-way `…Query` splits
keep writes depending on reads, never the reverse.

### The 21 cross-domain `MANDATORY` hops

Method-level hops into a `@Transactional(propagation = MANDATORY)` method of another domain. All
targets are in `backend/src/main/java/…/service/`.

| Target method | Caller domain → target domain | Hops |
| --- | --- | ---: |
| `InventoryCheckoutService.bookOutForClient` (`:154`) | exchange → inventory | 1 |
| `InventoryCheckoutService.mergeStockIfRequested` (`:668`) | exchange, job order → inventory | 2 |
| `InventoryOrgUnitReconciler.onUserGainedFirstOrgUnit` / `onUserLostLastOrgUnit` (`:64`, `:85`) | org unit → inventory | 2 |
| `MaterialExchangeOfferRatchet.lower` / `beforeDelete` (`:62`, `:94`) | inventory, job order → „Materialbörse“ | 4 |
| `MaterialExchangeOfferRatchet.beforeWipe` (`:109`) | inventory → „Materialbörse“ | 1 |
| `MaterialExchangeOfferRatchet.beforeUserPurge` (`:124`) | identity → „Materialbörse“ | 1 |
| `OrgChartService.mirror…`, six methods (`:272-391`) | org unit (`OrgUnitMembershipService`) → org chart | 6 |
| `OrgChartService.mirror…KommandoGroup`, three methods (`:402-437`) | org unit (`KommandoGroupService`) → org chart | 3 |
| `BankAuditService.record` (`:86`) | identity (`HandleAnonymisationService`) → bank audit | 1 |
| *Kernel, not counted:* `AuditService.record` (`:80`) | every domain → audit (57 files) | — |

The re-evaluation proposed synchronous in-transaction reactions for the upstream → downstream hops,
because an after-commit listener — as used today for notifications and mail
(`NotificationEventListener`, the registration and approval mail listeners) — cannot keep the offer
ratchet, the chart mirror or an audit row atomic with the change that triggered it. The plan adopts
these semantics as observer SPIs owned by the lower module (`StockChangeObserver`,
`MembershipChangeObserver`), as command APIs for exchange and job order → inventory, and as
identity's GDPR participants (§5.3, §7.6); `AuditService.record` stays a direct call
(REQ-AUDIT-001).

## Earlier focused audits

Re-evaluated only for what they deferred, rejected or left open, and for fixes a split could put at
risk (D-06). "Fixed; at risk" marks a fixed item listed for its guard.

### Project analysis and review (2026-05-11 and 2026-05-12)

`ANALYSIS.md` and `PROJECT_REVIEW.md` were removed by #103 (squash commit `552ae0e19e`) and read
from its parent. `ANALYSIS.md`: 13 findings and 8 suggestions; all findings are resolved,
suggestions 5.2 to 5.8 resolved or superseded. `PROJECT_REVIEW.md`: 28 items (4 high, 9 medium, 9
low, 6 suggestions); 23 are resolved and five open, partial or unknown, of which the re-evaluation
itemises four (the `P-` rows below). Resolved and verified, among others: the
`SecurityContextHolder` rules, a test profile on Testcontainers PostgreSQL 18 with Flyway and
`validate`, the OWASP gate at CVSS 7.0, no `script-src-attr 'unsafe-inline'`, the time limiter on
every verb (ADR-0032), backend JaCoCo, container memory limits and a digest-pinned runtime image.
Fixes 1.1 and 1.4 are package-keyed ArchUnit rules (see the risk table below).

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| A-5.1 | Static analysis beyond SpotBugs and Checkstyle (PMD, Error Prone) | OPEN | REPRIORITISED | Not adopted; NullAway would gate the JetBrains nullness annotations nothing checks today — the plan recommends Error Prone with NullAway (O-03, §8.1). |
| P-2.8 | Magic numbers: the quantity epsilon | PARTIAL | ADJUSTED | `QUANTITY_EPSILON = 1e-4` is copied four times across inventory and job order (`backend/src/main/java/…/service/InventoryCheckoutService.java:98` and three job-order services); one kernel constant beside the SCU rounding (§5.1). |
| P-2.9 | `Optional.get()` after `isPresent()` | OPEN | REPRIORITISED | Exact count unknown (64 `isPresent()` calls in 27 files as a proxy); part of the small-idiom clean-up of §8.1, low priority. |
| P-3.7 | `System.out` in tests | — | DROPPED | 58 deliberate diagnostics in 15 test and E2E files; the logging-facade gate excludes test sources by design. |
| P-5.2 | Pre-commit hooks | SUPERSEDED | DROPPED | The CI gates cover it. |

### Security audit of 2026-05-20 (#150)

43 findings (4 critical, 11 high, 17 medium, 11 low): 38 fixed, 5 deferred; all five deferred items
are resolved or moot today. Of the fixed ones, C-1 (peer redaction), C-3 (response DTO as request
body) and C-4 (request-record fields) are ArchUnit rules and appear in the risk table; H-6, H-7 and
H-8 (CSRF, WebSocket origins, rate limit) are path- or configuration-based and unaffected by a
package move.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| S150-M11 | Swagger UI in production (M-11, L-9) | DONE | DROPPED | No UI dependency; API docs off in production (`backend/src/main/resources/application-prod.yml:36-38`); `/v3/api-docs` requires ADMIN. |
| S150-L4 | `ConstraintViolation` message echoed into the problem body | — | CONFIRMED | Accepted risk while no bundle interpolates `${validatedValue}` (`backend/src/main/java/…/exception/GlobalExceptionHandler.java:497-505`); more modules own more bundles, so a test that forbids it is cheap (PRV-14). |
| S150-L6 | Major-version pinning of first-party Actions | SUPERSEDED | DROPPED | Every `uses:` is SHA-pinned (109, none by tag). |
| S150-L10 | CSRF-ignore consistency for the announcement endpoint | SUPERSEDED | DROPPED | The backend is stateless and exempts `/api/v1/**`; the frontend keeps CSRF on everything. |

### Security audit of 2026-06-03 (#393)

21 findings (3 high, 8 medium, 10 low): 16 fixed, 3 verified as needing no change, 2 documented as
by design. The report was kept local and never committed.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| S393-L2L6 | L-2 and L-6, "documented as by design" | — | UNKNOWN | Their content exists only in the uncommitted report; reading it settles them. |
| S393-M678 | M-6, M-7 and M-8, verified as needing no change | — | CONFIRMED | M-8 still holds: pessimistic book-out locks (`backend/src/main/java/…/repository/InventoryItemRepository.java:851,959,971,1000,1126,1160`) and the payout uniqueness retry. |
| S393-L1 | Opt-in JWT audience validator | SUPERSEDED | DROPPED | Fail-closed start-up check in production (`JwtAudienceStartupCheck`, `1df02fa31`). |
| S393-H1 | E-mail is a profile-only field (fixed; at risk) | DONE | ADJUSTED | `backend/src/main/java/…/mapper/UserMapper.java:83-90` ignores `email`, but `UserDto` keeps the component, so a module-local projection could map it; a peer type without the field makes the leak unrepresentable (PRV-10). |

### Security review of 2026-06-21 (#783)

Two exploitable findings (H1, M1) and about ten low or informational hardening items fixed, plus
ADR-0034; two deferred. M1's guest token and ADR-0034 are moot since ADR-0159 removed the guest
tier. H1 (`MissionSecurityService.canEditFinanceEntry`) and the member-evaluation gate live behind
SpEL bean names, a failure mode that shows only at runtime (G-04).

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| S783-PAGECAP | Lower the 100,000 page-size ceiling | OPEN | REPRIORITISED | Still `MAX_PAGE_SIZE = 100_000` (`backend/src/main/java/…/web/PaginationUtil.java:45`) on an internet-reachable API (ADR-0135), and module-local ceilings drift (500, 200); a kernel page policy with a lower default and tested opt-outs (D-18). |
| S783-AUD | JWT audience validation stays opt-in | SUPERSEDED | DROPPED | Replaced by the fail-closed start-up check. |

### API security audit of 2026-08-25 (#1672)

Five findings confirmed and fixed (REQ-SEC-039 … 042, REQ-SEC-031 amended), two refuted, one
consistency item left.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| S1672-AUDASSERT | Fail-closed start-up invariant for the expected audiences | DONE | DROPPED | Implemented as APPSEC-08. |
| S1672-BUDGET | Per-subject GET budget for aggregation reads | PARTIAL | CONFIRMED | Per-subject buckets cover writes, SSE and live-sync connects and export segments (`backend/src/main/java/…/config/SubjectRateLimitingFilter.java:57-63`); plain reads stay per IP, an accepted risk. Expensive new module reads must carry an export path segment. |
| S1672-LIKE | Route `BlueprintProductService.searchProducts` through `LikePatterns.escape` | OPEN | CONFIRMED | The query reaches `LIKE` unescaped (`backend/src/main/java/…/repository/BlueprintRepository.java:125-126,219`); informational — a wildcard only broadens matches in the global catalogue. The verification round found the same gap in the order and inventory item-catalogue searches; all three are defect S-08 of §9. |
| S1672-031 | `no-store` families of REQ-SEC-031 (fixed; at risk) | PARTIAL | CONFIRMED | 14 path patterns (`backend/src/main/java/…/filter/NoStoreApiScopes.java:42-55`); `/api/v1/admin`, `/exchange`, `/orders`, `/material-exchange`, `/material-requests`, `/leitung` and `/org-chart` are unclassified and new paths default to storable; their sensitivity needs a review (G-07, D-18). |
| S1672-040 | Redaction of nested user records, REQ-SEC-040 (fixed; at risk) | DONE | ADJUSTED | Fixed for the ship owner inside assigned units (`backend/src/main/java/…/support/MissionPeerRedactor.java:118-160`); the tests go case by case, with no reflective walk (PRV-10). |

### API security audit of 2026-08-30 (#1724)

28 raw findings: 17 confirmed and fixed, 13 refuted; the report was not committed, so the refuted
list is unknown.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| S1724-MEMBERTIER | Members received the full nested `UserDto` on `GET /missions/{id}` | DONE | DROPPED | Peer redaction since 2026-09-06 (`backend/src/main/java/…/controller/MissionController.java:248`). |
| S1724-INGESTAUD | Ingest ran with the backend's audience | DONE | DROPPED | Corrected by hand in production, per the knowledge base (host not re-read). |
| S1724-SCOPE | `REQUIRED_SCOPE` and `ALLOWED_TOOLS` unset on ingest | SUPERSEDED | DROPPED | The `/v1` routes and those gates were removed (#2270, step 9 of #2092). |
| S1724-PRED | One on-behalf predicate for four write paths, plus `canSeeOperationLedger` (fixed; at risk) | DONE | CONFIRMED | Central in `AccessGateService` and `OwnerScopeService`, used by inventory, job-order production, operation payout and refinery; per-module copies would recreate "a fix never generalised to its siblings". |

### Performance audit of 2026-05-20 (#155–#160, #175)

At least 24 labelled findings (H-1 … 7, M-1 … 10, L-1 … 7): 17 fixed in #175 and six parts in
#155–#160; H-2 was already in place; M-2 and M-8 were false positives; which pull request fixed H-4,
L-3, L-5 and L-6 is not recorded.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| PERF-V91 | Drop the V91 two-column index "in a future cleanup pass" | DONE | DROPPED | Dropped by `backend/src/main/resources/db/migration/V103__drop_legacy_owning_squadron_columns.sql:39`; V209 restored the status index. |
| PERF-CONC | `CREATE INDEX CONCURRENTLY` for large tables "if needed" | OPEN | REPRIORITISED | Conditional; no table size demands it yet. |
| PERF-FORKS | `maxParallelForks` reverted; move `WebClientResilienceTest` to virtual time | DONE (2026-10-04, #2387) | REPRIORITISED | Still a real 400 ms limiter (`frontend/src/test/java/…/WebClientResilienceTest.java:61`); a prerequisite only if Gradle modules multiply parallel test tasks (Phase 5). |
| PERF-M6 | Rejected: `loading=lazy` on images | — | REJECTION-HOLDS | The images are above the fold, mostly the header logo. |
| PERF-M7 | Redis persistence off | SUPERSEDED | DROPPED | Reversed later: Redis persists again (`quadlet/systemd/redis.container:20`), as it also holds live-sync and ingest state. |
| PERF-L7 | Presence heartbeat (60 s) and TTL (120 s) changed in lockstep (fixed; at risk) | DONE | ADJUSTED | Holds (`frontend/src/main/resources/static/js/mission-presence.js:4`, `frontend/src/main/java/…/service/LiveSyncPresenceService.java:58`), but nothing pins the pair, and the Javadoc names the wrong script; a parity test (PRV-14). |

### Caching audit (#1002, 2026-07-05)

4 latent findings; 11 expansion candidates (5 adopted, 6 rejected); 22 architecture suggestions (9
adopted, 10 rejected, the rest folded in); a missed-eviction audit with 9 gaps, 6 of them real and
fixed. The adopted items shipped in #1004, #1007, #1009, #1011 and #1014.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| C1002-L4 | `@Version` safety of cached catalogue mutators holds only through self-invocation | OPEN | ADJUSTED | The audit's fix was a code comment, which ADR-0214 removed; no test, not in REQ-DATA-007. After a query/command split the mutator edits the cached instance, and a failed write leaves it in the cache (`backend/src/main/java/…/service/CityService.java:69,84`; 33 `@Cacheable` in 16 services). High priority before any catalogue restructuring: G-19. |
| C1002-R-SPACESTATION | Rejected: cache the space-station catalogue | — | REJECTION-HOLDS | `@Version` collision; the sync writer does not evict. |
| C1002-R-ALIAS | Rejected: cache material external aliases | — | REJECTION-HOLDS | `@Version` collision and lazy-initialisation failures. |
| C1002-R-SYSSET | Rejected: cache system-setting values | — | REJECTION-HOLDS | Money-affecting settings must take effect at once; a single primary-key lookup. |
| C1002-R-RULES | Rejected: cache notification rules | — | REJECTION-HOLDS | The fan-out uses its own query; low value. |
| C1002-R-S3 | Rejected: key-scoped backend eviction | — | REJECTION-HOLDS | Coarse eviction is safer. |
| C1002-R-STAMPEDE | Rejected: `refreshAfterWrite` in backend and frontend | — | REJECTION-HOLDS | No thundering herd at one replica; the frontend's `getCached` already uses `sync = true`. |
| C1002-R-WARM | Rejected: warm the caches at start-up | — | REJECTION-HOLDS | Boot fragility for a one-time miss. |
| C1002-R-HTTP | Rejected: a frontend `If-None-Match` shim, a real `max-age`, a static-asset change | — | REJECTION-HOLDS | The only consumer ignores `Cache-Control`; assets are already immutable. |
| C1002-R-ORGCHART | Rejected: cache the org chart and hierarchy (eviction scattered over three services) | — | REJECTION-REVISIT | Revisit once the org-unit module notifies its dependants for its own reasons — the membership observer of §5.3 would be that hook; never build events just for caching. |
| C1002-R-CACHE03 | Rejected as "about zero impact": an entity-aliasing guard | — | REJECTION-REVISIT | Its impact rises with the modularisation (C1002-L4); it comes before any split of catalogue services (G-19). |
| C1002-DIST02 | ADR-0074: defer Redis pub/sub cache eviction until there is more than one replica | — | REJECTION-HOLDS | The chosen target keeps one process per application (D-01); only separate deployable services would break the single-instance precondition of REQ-DATA-007. |
| C1002-FECACHE1 | The `CachedCatalog` allow-list makes per-principal cache keys unrepresentable (fixed; at risk) | DONE | CONFIRMED | `frontend/src/main/java/…/service/BackendApiClient.java:182-209` takes only the enum; typed clients must not add their own `@Cacheable` (G-17). |

### Concurrency hardening (#1109, 2026-07-07)

All 48 sub-issues (#1110–#1128, #1130–#1158) are closed as completed; seven candidates were refuted.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| K1109-REFUTED | Seven refuted candidates (SSE fan-out starvation, a refresh stripe-lock timeout, an N+1 on operation finances, a shared NAT/VPN budget, double reads in the role-sync filter, …) | — | CONFIRMED | No new evidence against them. |
| K1109-AT-RISK | `ParallelPageLoader` context relay (#1130), the authorization fragment (#1139), the `saveAndFlush` version echo (#1135), push after commit (#1152) (fixed; at risk) | DONE | ADJUSTED | All hold, but a gap of the #1130 class is visible: parallel reads do not relay the user locale (`frontend/src/main/java/…/service/ParallelPageLoader.java:68-73` against `frontend/src/main/java/…/config/ReactorContextPropagationConfig.java:106-119`); impact unknown. Phase 0.4 captures the context through `ContextSnapshotFactory` (PRV-05). |

### Permission-gate audit (#548, 2026-06-11)

Three documentation gaps and two precision fixes in `ROLES_AND_PERMISSIONS.md`, all fixed.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| G548-COUNT | The role matrix | DONE | ADJUSTED | #1981 rebuilt the matrix from 88 controllers; the backend has 98 `@RestController` classes today — new controllers or a counting difference, settled by diffing matrix and controllers. The matrix should get per-domain sections. |

### Post-cutover operations audit (#1984, 2026-09-22)

Nine code and configuration gaps, all fixed (O1984-COUNT: DONE, DROPPED). Host-side parts reach
production only through the Ansible role; the knowledge base records a production role run with the
owner's approval, and whether every host item is live was not re-read. Two of its items reappear in
the September audit (OPS-MON-01, OPS-REL-01).

### Documentation audit (#1981, 2026-09-22): open decisions

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| D1981-ADR | 29 ADRs flipped from Proposed to Accepted, pending the owner's ratification | DONE | CONFIRMED | Ratified by the owner on 2026-10-04 (D-27). |
| D1981-TERMS | The terms still mention guests and list five areas | DONE | DROPPED | The owner keeps the text (D-25, 2026-10-04), because a change forces re-consent (ADR-0127). |
| D1981-PRIVACY | `privacy.p_3_8_1` describes IP logging | — | UNKNOWN | Whether it was revisited is not recorded (`frontend/src/main/resources/messages_de.properties:1411`). |
| D1981-INGEST007 | REQ-INGEST-007 promises a "remember me" opt-in the extractor lacks | — | UNKNOWN | The spec is unchanged (`docs/specs/desktop-ingest.md:515-522`); to be settled in the extractor repository. |
| D1981-REQIDS | Duplicated requirement ids | DONE | DROPPED | 18 ids renumbered (`d9a7d53bb3`). |
| D1981-RESTORE | The restore-drill alert used a 35-day window | DONE | DROPPED | Eight days (`monitoring/prometheus/alerts/ops-automation.yml:56`). |
| D1981-KCHARDEN | Keycloak hardening steps 2 (SMTP, forgotten password), 11 (OTP for admins) and 12 (session windows) | OPEN | CONFIRMED | Adopted on 2026-10-04 (D-26); each realm write needs the owner's yes. `docs/KEYCLOAK_HARDENING_RUNBOOK.md`; independent of the domain split — step 11 matters most, as admin accounts have no second factor. |
| D1981-OAUTH | The confidential frontend OAuth2 client not yet migrated | DONE | DROPPED | Live in production since 2026-09-25. |
| D1981-TS | TypeScript migration plan | — | REJECTION-HOLDS | Deliberately unscheduled (ADR-0125); per-domain script folders work with `checkJs`. |

### Design audits (#174, #177, 2026-05-21)

18 findings: 17 implemented, of which #177 reverted two (the body flex layout, the modal inner
maximum height); C6 was deferred.

| ID | Item | Status | Verdict | Why and evidence |
| --- | --- | --- | --- | --- |
| UI174-C6 | `--color-gray-4/-5` naming asymmetry | SUPERSEDED | DROPPED | Only `--color-gray-1` to `-4` exist (`frontend/src/main/resources/static/css/styles.css:38-41`). |
| UI174-MODALS | Three modal patterns harmonised | SUPERSEDED | DROPPED | One dialog shape since ADR-0177 (`SingleModalShapeTest`). |
| UI177-FOOTER | The footer is moved by JavaScript instead of a flex body | OPEN | CONFIRMED | Still `frontend/src/main/resources/static/js/sidebar.js:102-103`; low relevance, part of the layout kernel. |

### Fixes a domain split could put at risk

Many security fixes of the earlier audits are enforced by
`backend/src/test/java/…/ArchitectureTest.java`. ArchUnit 1.5.1 fails a rule whose *selection* comes
out empty (the `failOnEmptyShould` default is true), but a rule whose *target* is a fully qualified
name that no longer exists simply passes; two rules also set `allowEmptyShould(true)` (`:718`,
`:1693`). The re-evaluation classified all 43 backend rules for a package-by-domain move (X-ARCH,
CONFIRMED as an open risk):

| Behaviour on a move | Rules | Examples |
| --- | ---: | --- |
| Safe — the key does not depend on the package | 3 | `toOneAssociationsAreDeclaredLazy`, `everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation`, `bankClassesMustStaySeasonAndProfitIndependent` |
| Loud — the move fails the build | 8 | `peerReadableMissionEndpointsMustRedactPii` (size floor), `orgUnitAwareBankSeamIsContainedToOneClass`, the mission request-record rule |
| Stronger — slices become domain slices and expose cycles | 1 | `backendPackagesShouldBeFreeOfDependencyCycles` |
| Partial — partly loud, partly silent | 6 | `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` (silent for split or renamed services), the three exchange-layer rules, `bankLedgerRepositoriesMustStayInsertOnly` |
| Silent — passes while checking less | 25 | `permitAllIsDeclaredOnlyOnTheFourPublicEndpoints`, the read and write gate rules, the three `SecurityContextHolder` rules, `controllerLayerMustNotWriteAuditRowsDirectly`, `responseOnlyDtosMustNotBeAcceptedAsRequestBodyOnWriteEndpoints`, `bankClassesMustNotConsultOrgUnitScope`, `supportPackageMustStayADependencyLeaf` |

`supportPackageMustStayADependencyLeaf` is silent for a special reason: it lists forbidden target
packages, and a new `…backend.mission` package is not on the list. The ingest and frontend rules are
annotation-based and unaffected. §3 of the plan, after its verification round, counts 30 rules that
pass without checking anything when a domain moves whole and 33 when it moves piecemeal; the table
above differs from the first count in one rule, `exchangeDtosStayInTheExchangeLayer`, which a
whole-domain move makes loud. G-01 re-keys all of them before anything moves. The consolidated list,
the September fixes included:

| Fix (audit) | Where it lives | How a split could weaken it | Guard |
| --- | --- | --- | --- |
| No `permitAll` beyond the four public endpoints (REQ-SEC-052; #150, project review 1.4) | `backend/src/test/java/…/ArchitectureTest.java:345`, selecting `.backend.controller` at `:348` | Silent: moved controllers are no longer scanned | G-01, G-02 |
| Every read and write endpoint gated (#150, project review 1.4) | `backend/src/test/java/…/ArchitectureTest.java:389,737` | Silent, for the same reason; 15 endpoints rely on a URL rule alone | G-01, G-02, G-03 |
| No `SecurityContextHolder` outside the auth seam (project review 1.1) | `backend/src/test/java/…/ArchitectureTest.java:202,219,253` | Silent for services, controllers and mappers outside the old layer packages | G-01 |
| Response DTOs never accepted as request bodies (#150 C-3) | `backend/src/test/java/…/ArchitectureTest.java:1252`, FQCN string `MissionDto` at `:131-132` | Silent: package filter and FQCN string | G-01, G-06 |
| Request records without server-managed fields (#150 C-4) | `backend/src/test/java/…/ArchitectureTest.java:1381` | Loud on a move, but it covers the mission only | G-06 |
| Peer PII redaction (#150 C-1, #1672 REQ-SEC-040, BE-SIMP-05) | `MissionPeerRedactor`; `backend/src/test/java/…/ArchitectureTest.java:1129`, floor of ten at `:1144` | Handlers are de-selected silently while ten remain; nested records from other modules are not walked | G-01 (floor at today's count), checklist §6.2, PRV-10 |
| The bank stays org-unit-blind (ADR-0020, REQ-BANK-008) | `backend/src/test/java/…/ArchitectureTest.java:1794,1853` | `:1794` passes vacuously once `OwnerScopeService` moves (FQCN target, `Bank*` name prefix) | G-01; the seam rules are re-keyed before any bank class moves (§5.4) |
| Tenant services wired to the scope gate (#783 era) | `backend/src/test/java/…/ArchitectureTest.java:978,1036` | Simple-name whitelists miss split or renamed services; `OperationPayoutService` is already missing | G-05 |
| One on-behalf predicate for four write paths (#1724) | `AccessGateService`, `OwnerScopeService` | Per-module copies recreate "a fix never generalised to its siblings" | Access policies on the scope kernel with the differential verdict test (§5.4) |
| Book-in check before any lookup (APPSEC-01, REQ-INV-032) | `backend/src/main/java/…/service/JobOrderItemProductionService.java:321-334` | Moving the check behind a lookup in an inventory API opens an existence oracle | Red line (§6); a 403 test per foreign entry point (§7.5) |
| `no-store` for the listed API families (#1672, REQ-SEC-031) | `backend/src/main/java/…/filter/NoStoreApiScopes.java:42-55` | New or moved paths default to storable | G-07, D-18 |
| Per-subject budget for exports (APPSEC-10, REQ-SEC-033) | `backend/src/main/java/…/config/SubjectRateLimitingFilter.java:95` | A re-cut path loses its export segment | G-07 |
| Audit row in the business transaction (REQ-AUDIT-001) | `AuditService.record`, `MANDATORY` | Conversion to an after-commit event | G-12; red line (§6) |
| SpEL gates on security beans (all audits; #783 H1) | 155 bean references in `@PreAuthorize` (§4.2 of the plan recounts 166) | A renamed or moved bean answers every gated call with HTTP 400, only at runtime | G-04; D-19 |
| Session allow-list (APPSEC-05, ADR-0206) | `frontend/src/main/java/…/config/SessionTypeAllowList.java:86-87` | Moved session-held classes are dropped under `enforce`; a wider prefix weakens the control | G-16; O-02 |
| Client-IP and locale relay on parallel reads (#1109, #1130) | `frontend/src/main/java/…/service/ParallelPageLoader.java:68-73` | A new `ThreadLocal` is not captured — the locale already is not | Phase 0.4 (`ContextSnapshotFactory`); G-17 |
| One resilience and relay chain (ADR-0032) | the filtered `webClient` bean | A module builds its own client and loses the bearer, org-unit, IP and locale relays and resilience | G-17 |
| Per-principal-safe frontend cache (#1002 FE-CACHE-1) | `frontend/src/main/java/…/service/BackendApiClient.java:182-209` | A typed client adds a URI-keyed `@Cacheable` | G-17 |
| The terms-document client used exactly once (REQ-SEC-052) | `TermsDocumentClientUsageTest`, keyed on a field name | Injection under another name is invisible | G-17 (keyed on the bean) |
| Cached catalogue writes (#1002 L4) | `backend/src/main/java/…/service/CityService.java:69,84` and 15 other cached services | A query/command split lets the mutator edit the cached instance | G-19 |
| No credential in `toString` (BE-MOD-04, ING-MOD-02) | the properties records, e.g. `backend/src/main/java/…/config/MonitoringScrapeProperties.java` | A new or converted properties class prints its secret | G-22 |
| Coverage floors, mutation targets and two directory-listing tests (KC-CI-01, BLD-SIMP-06, CI-03, FE-PERF-02, FE-SIMP-03) | `build.gradle.kts:228-241,267-268`; `I18nDictionaryCoverageTest`; `TemplateCommentHygieneTest` | A new module gets default floors, PIT's scope empties, per-domain folders narrow the two tests | G-20 |
| The `Entities.require` ratchet (BE-SIMP-01) | `EntitiesRequireRatchetTest`, scanning `backend/src/main/java` | Code in a new Gradle module escapes it | No G-ID; widen the scan roots with Phase 5 |
| The exchange gateway's surface and filter order (ING-SEC-05, ING-SIMP-01) | `IngestEndpointSurfaceTest`, `FilterOrderTest` | The ingest re-package (§5.11) changes both | G-18 |
| Image and SBOM inputs (IMG-SIMP-14, CI-07) | `docker/app/Dockerfile:12-27`; `.github/scripts/image_reuse_plan.py:44-112` | A Gradle subproject is missing from the image, or every change rebuilds all three images | Prerequisites of §5.8 (Phase 5) |
| E-mail only in the self profile (#393 H-1) | `backend/src/main/java/…/mapper/UserMapper.java:83-90` | A module-local user projection maps `email` | The kernel's `UserRef` (§5.1); PRV-10 |
| Presence heartbeat and TTL in lockstep (performance audit L-7) | `frontend/src/main/resources/static/js/mission-presence.js:4`; `frontend/src/main/java/…/service/LiveSyncPresenceService.java:58` | Moving the script breaks the pairing unnoticed | No G-ID; a parity test (PRV-14) |
| Validation messages never echo input (#150 L-4, an accepted risk) | `backend/src/main/java/…/exception/GlobalExceptionHandler.java:497-505` | More modules own message bundles | No G-ID; a `${validatedValue}` test (PRV-14) |

## Derived proposals

The re-evaluation of the July audit and the earlier focused audits derived fourteen proposals. Each
is listed with where the plan adopts it.

- **PRV-01 — Re-key the ArchUnit guards before the first package move (blocking).** Select by role
  instead of package, class literals instead of FQCN strings, a meta-test that every remaining FQCN
  string resolves, minimum selection sizes, no `allowEmptyShould(true)` where a move could empty a
  selection; per-module rules later. *Adopted* as G-01 (Phase 0.2), with the module rules as G-09
  and Spring Modulith in test scope (D-02).
- **PRV-02 — Resolve every `@PreAuthorize` SpEL bean reference in a context test.** Parse each
  expression and assert that the bean exists and has the method with that arity. *Adopted* as G-04;
  an evaluation failure stays a counted and alerted 400 (D-19).
- **PRV-03 — Make the 21 cross-domain `MANDATORY` hops explicit module ports.** Methods of the
  target module are documented as joining the caller's transaction, reactions run in that
  transaction, and audit stays a direct call. *Adopted in changed form*: command APIs with
  `MANDATORY` and observer SPIs owned by the lower module instead of in-transaction events (§5.3),
  guarded by G-12 and the red line that audit never becomes an after-commit event (§6).
- **PRV-04 — Guard the cached-entity invariant before restructuring catalogue services.** Interim: a
  test per cached service; target: caches hold read models and a rule forbids `@Cacheable` returning
  an entity. *Adopted* as G-19 and §5.6; REQ-DATA-007 is amended (§14).
- **PRV-05 — One context snapshot for parallel page loads.** Capture the locale as well, with a
  parity test against the registered context accessors; the holders stay `ThreadLocal` (ADR-0223).
  *Adopted* in Phase 0.4 (`ParallelPageLoader` on `ContextSnapshotFactory`).
- **PRV-06 — A completeness test for REQ-SEC-031.** Every backend `GET` path matches the `no-store`
  list or a reviewed list of revalidatable families. *Adopted* in G-07; the classification of the
  unlisted families is decided as D-18.
- **PRV-07 — Per-domain frontend facades on the one transport core** (J-R05 revisited). Controllers
  of a domain use only its facade; only `WebClientConfig` builds clients; streaming proxies go
  behind a core method or on an allow-list; `TermsDocumentClientUsageTest` is keyed on the bean; no
  `@Cacheable` outside the core. *Adopted and extended*: typed clients per domain, first thin
  classes over `BackendApiClient`, then HTTP interface clients on the same bean (§5.9, F1–F3),
  guarded by G-17; ADR-0032 and arc42 §4.1 are amended.
- **PRV-08 — A shared page policy.** A kernel policy with a lower default ceiling and explicit,
  tested opt-outs replaces the 100,000 ceiling and the module-local caps. *Adopted* as D-18.
- **PRV-09 — Split `support` into a shared kernel and domain-internal code.** *Adopted* in Phase 1
  (§7.3); the leaf rule's message stops sending shared logic there. **Done 2026-10-04** (P1-9):
  `support` is gone — kernel, a `platform` module and the modules' `api`/`internal` packages;
  the leaf rule is keyed by class literal.
- **PRV-10 — Make PII leaks unrepresentable.** A reflective test that fills every nested `UserDto`
  in `MissionDto` with sentinel values and asserts that peer redaction removes them; a user summary
  without an `email` component. *Partly adopted*: the kernel's `UserRef` (§5.1) carries cross-domain
  member references and the wave checklist keeps the redaction selection floors (§6.2); the
  reflective test is not yet a named guard and belongs to the mission step (§7.5).
- **PRV-11 — An audit-completeness guard per audited module** (in place of the rejected base
  template). Every mutating public service method of an audited module reaches an audit call or sits
  on a reviewed exemption list. *Adopted in changed form*: an audit contract per command (§6.2,
  §7.5; REQ-AUDIT-001 in §14).
- **PRV-12 — Replace the scoped-service whitelists with a module-scoped rule.** Services of
  tenant-scoped modules depend on the scope API; the #1724 predicates stay in the scope kernel.
  *Adopted* as G-05 (a `@TenantScoped` marker) and the per-domain access policies of §5.4.
- **PRV-13 — Correct the documentation drift.** arc42 §4.1 and the knowledge base ("exactly one
  class"), stale line counts in the knowledge base, the `LiveSyncPresenceService` Javadoc pointer,
  ArchUnit messages that ask for a code comment. *Adopted* through §15 of the plan, which corrects
  arc42 §4.1 and lists the ArchUnit messages; the Javadoc pointer goes with the presence parity test
  (PRV-14).
- **PRV-14 — Small leftovers.** Rename and relocate `BlueprintFuzzyMatcher` (done 2026-10-04:
  `kernel.FuzzyNameMatcher`); split
  `GlobalExceptionHandler` by exception family, keeping one advice; `LikePatterns.escape` in the
  blueprint search; a presence heartbeat parity test; a test forbidding `${validatedValue}`;
  Keycloak hardening step 11 on the owner's list. *Not adopted as plan steps*: they are independent
  of the domain split; §11 keeps the `LIKE` escape and the Keycloak steps on the open list, and §5.5
  keeps one exception handler.
