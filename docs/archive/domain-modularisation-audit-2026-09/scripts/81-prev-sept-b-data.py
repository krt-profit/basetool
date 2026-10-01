"""Per-finding evaluation data for the 81-prev-sept-b audit (Frontend, CI, Betrieb + siblings)."""

E = {}

def ev(fid, status, evidence, verdict, prio_new, reasoning, domain_effect, security_note):
    E[fid] = dict(status=status, evidence=evidence, verdict=verdict, prio_new=prio_new,
                  reasoning=reasoning, domain_effect=domain_effect, security_note=security_note)

ev("CI-SEC-18", "DONE",
   "frontend/build.gradle.kts:472 npmInstallCommand.set(\"ci\"); frontend/.npmrc:1 ignore-scripts=true; landed in 0ac813048 (#2002).",
   "CONFIRMED", "closed",
   "The lint toolchain installs from the lockfile with lifecycle scripts off. Nothing left; updates arrive through the npm entry of .github/dependabot.yml (directory /frontend).",
   "neutral - build tooling, no domain coupling.",
   "Closes the postinstall code-execution path of the lint toolchain. Invariant: a package that needs an install script is refused, never admitted by dropping ignore-scripts.")

ev("APPSEC-03", "DONE",
   "HangarImportProxyController.java:64 MAX_IMPORT_BYTES = 8 MiB; :115-121 refuses before reading (413 body :161-176); :128 and :182-229 stream the part through StreamedUpload instead of getBytes(); HangarImportProxyControllerTest extended in 0ac813048 (#2002).",
   "CONFIRMED", "closed",
   "Heap buffering is gone and the cap equals the backend parser's. Residual worth knowing: spring.servlet.multipart max-file-size 64MB / max-request-size 72MB (frontend application.yml:36-37) still let Tomcat spool up to 64 MB to disk before the 8 MiB check; lowering them globally would hit other uploads, so leave it unless a per-endpoint limit is introduced.",
   "neutral - a hangar-domain relay; it moves with the hangar package in a per-domain frontend layout.",
   "DoS bound kept: 8 MiB before any backend request. Keep the check on the container's recorded part size, not on a client header.")

ev("APPSEC-11", "DONE",
   "GlobalExceptionHandler.java:415-454 handleResponseStatus answers a ResponseStatusException with its own status (4xx DEBUG, 5xx ERROR, reason never echoed); the 12 relays that throw new ResponseStatusException(e.getStatusCode(), ...) (grep: 12 sites in 9 files) now keep the backend status; RelayedBackendStatusMvcTest per vault Frontend.md:1417-1421.",
   "CONFIRMED", "closed",
   "The recommended alternative - a status-keeping handler - was chosen instead of rewriting each relay; REQ-OBS-001 (4xx not at ERROR) holds.",
   "neutral - one handler in the shared web kernel; per-domain controller packages keep using it.",
   "The handler neither shows nor logs the exception reason, which may name an internal backend URL; keep that when touching it.")

ev("APPSEC-12", "DONE",
   "frontend support/SessionIdFingerprint.java:39-62 (first 12 hex characters of SHA-256); used at SessionDebugFilter.java:91,124,142 and SsoReAuthenticationEntryPoint.java:95; SessionIdFingerprintTest added in 0ac813048 (#2002).",
   "CONFIRMED", "closed",
   "No raw session id reaches a log line; the fingerprint keeps correlation.",
   "neutral.",
   "A 48-bit fingerprint is for correlation only and cannot be replayed as a session id; do not log the full hash or the id at TRACE.")

ev("FE-SEC-01", "DONE",
   "MissionPageController.java:263-321 passes query/start/end as URI-template variables, binds start/end as @DateTimeFormat(ISO.DATE_TIME) Instant (:265-268) and narrows status via RelayParams.oneOfOrNull (:298-304); OperationPageController.java:125-165 likewise; guards ListSearchRelayParamsTest, OperationPageControllerMvcTest.",
   "CONFIRMED", "closed",
   "Both named relays are correct by construction. The same defect class still exists structurally in the other concatenated backend URIs (362 call sites) - that remainder belongs to FE-SIMP-02, not here.",
   "neutral.",
   "No privilege was ever at stake (the backend authorises each relayed call with the caller's token); parameter smuggling into these backend URLs is closed.")

ev("FE-SEC-02", "DONE",
   "MeFrontendController.java:69 binds orgUnitId as UUID; :86-98 safeRedirectTarget accepts only a single-slash path without control characters; :125 uses it; MeFrontendControllerTest (#2002).",
   "CONFIRMED", "closed",
   "Exactly the recommended guard.",
   "neutral - the org-unit context switch is a cross-cutting shell feature.",
   "Open redirect closed; keep the server-side check even though the POST is CSRF- and SameSite=Strict-protected.")

ev("FE-SEC-04", "DONE",
   "krt-client-error.js:9 KIND_CSP_VIOLATION and :232 securitypolicyviolation listener; MetricNames.java:438 CLIENT_ERROR_CSP_VIOLATION; dashboard 07-basetool-operations.json:2331; ClientErrorSpike text business.yml:621; ClientErrorReportControllerTest pins the JS and Java kind lists.",
   "CONFIRMED", "closed",
   "CSP violations are observable through the existing beacon; the CSP has no report-uri, so this is the only signal.",
   "neutral - cross-cutting observability.",
   "Beacon payload is limited to directive and origin; keep URLs with query strings and user data out of it.")

ev("FE-PERF-04", "DONE",
   "ls frontend/src/main/resources/META-INF/resources/logos/ -> 7 basetool-* files, 24,987 bytes; sc.png, gplv3.svg, java.svg, postgres.svg, spring.svg and two webp deleted in 0ac813048 (#2002); a test pins the directory to the basetool-* family (vault Frontend.md:1446-1447).",
   "CONFIRMED", "closed", "-", "neutral.", "Smaller public surface; nothing else.")

ev("FE-PERF-06", "DONE",
   "grep location.reload over static/js: none left in admin-materials.js, notification-rules.js, inventory-admin.js, inventory-my.js, orders-detail.js. 19 calls remain elsewhere: krtFetch-absent fallbacks (e.g. bank.js:710,1917, leitung.js:15,28, org-chart.js:38, promotion-manage.js:62), the sanctioned conflict confirm (krt-fetch.js:362, mission-detail.js:2730) and three success fallbacks when the server returns no targetUrl (orders-create.js:527, refinery-orders-create.js:407, refinery-orders-details.js:755).",
   "CONFIRMED", "closed",
   "The six named sites follow REQ-FE-001/003. The three targetUrl-less fallbacks only run when a create/cancel answers without a target; a controller test asserting targetUrl is always present would pin them (optional).",
   "neutral.",
   "None; the 409 path now goes through krtFetch's conflict confirm, which keeps optimistic-lock conflicts visible.")

ev("FE-MOD-04", "DONE",
   "MissionWriteController.java:86-89 imports tools.jackson.* only (the Jackson-2 path left with the deleted deprecated endpoints, 5ab7ff01e / #1996). The only Jackson-2 user left in frontend main is ThymeleafJavaScriptSerializerConfig.java:22-28 (Thymeleaf's JavaScript serializer SPI).",
   "CONFIRMED", "closed",
   "Done as recommended. The Thymeleaf serializer residual is outside the finding; UNKNOWN whether the thymeleaf-spring6 version in use offers a Jackson-3 serializer - settle by inspecting that jar before planning it.",
   "neutral.",
   "The custom serializer keeps Thymeleaf's HTML-safe escape set including U+2028/U+2029 (script breakout); any replacement must keep it.")

ev("BE-PERF-05", "DONE",
   "grep 'fetchUsers|\"users\"' JobOrderWriteController.java -> 0 hits; removed by 25430fd7e, merged with #2004 (ccc886d28); orders-detail.html has no ${users}.",
   "CONFIRMED", "closed", "-",
   "helps slightly - the job-order write path no longer pulls the identity domain's whole roster.",
   "Less member data per request in the model.")

ev("BE-PERF-06", "DONE",
   "backend UserController.java:230-239 GET /api/v1/users/search/references -> PageResponse<UserReferenceDto> under the same @PreAuthorize (:231), bank variant :253-265; frontend UserProxyController.java:78-81 and :91-95 forward with size = PickerSearch.PAGE_SIZE and sort username (:111-119); #2004.",
   "CONFIRMED", "closed",
   "Paged, slim, same role gates.",
   "helps - UserReferenceDto is the kind of small published reference type other domains should use instead of the full UserDto (Option A: cross-domain references by id plus a reference DTO).",
   "The reference DTO carries fewer fields than UserDto (less redaction surface); keep it minimal and the gate identical to /users/search.")

ev("FE-PERF-01", "DONE",
   "LayoutContextLoader.java:48-64,109 reads GET /api/v1/me/layout at most once per request; 70 classes carry @UsesLayoutModel; frontend ArchitectureTest.java:131-166 (every @Controller opts in, no @RestController does, body-writing handlers in layout controllers only decrease); backend MeController.java:158-178 composes the four answers in one read-only transaction (#2004 backend, #2020 frontend).",
   "ADJUSTED", "P2",
   "Keep the one-read design, but in the domain-modular target this read is a composition of several domains and belongs in an explicit shell/composition module. Today MeController.java:104-115 computes the capability flags itself from OwnerScopeService (canAccessBlueprintOverview, canViewJobOrders, canViewOwnJobOrders), AuthHelperService role checks (bank, logistics, mission) and InventoryProperties.stolenMarkingEnabled() - the identity/shell controller knows blueprint, job-order, bank and inventory rules. Proposed: each domain publishes a small capability query in its API; the shell composes them; module rules let the shell depend on domain APIs and forbid the reverse.",
   "hub by design - acceptable only as a named composition module; hinders while it is a controller that encodes every domain's access rules.",
   "The flags only steer menus; @PreAuthorize on every endpoint stays the authorization. Add a guard that no server-side decision reads a layout flag.")

ev("FE-SEC-03", "DONE",
   "eslint.config.mjs:5-12 RAW_FETCH_WRITE selectors, :49 no-restricted-syntax error, :66-74 exemptions for krt-fetch.js and krt-client-error.js. Scan 81-prev-sept-b-rawfetch.py: 55 raw fetch calls outside those files, 0 with a non-GET method; 3 pass a helper-built init (notifications.js:104,181,367 -> csrfRequestInit() :55-59, GET headers only).",
   "CONFIRMED", "closed",
   "Every write goes through krtFetch. The selector inspects only an object-literal init, so fetch(url, init) with a variable init would pass; none exists today. Optional P3 hardening: also flag a fetch whose second argument is not an ObjectExpression.",
   "neutral - cross-cutting rule; lint globs are recursive (frontend/build.gradle.kts:570-572), so per-domain JS folders stay covered.",
   "Enforces CSRF token, 403 retry, re-auth and double-submit guard (REQ-FE-002/004) mechanically.")

ev("FE-SEC-05", "DONE",
   "eslint.config.mjs:3,52-65 eslint-plugin-no-unsanitized (method + property) with escapeHtml/escapeAttr as escapers; one helper escape-html.js:12-20; the only disable directive is krt-fetch.js:627 inside setTrustedHtml (server-rendered fragments); vault Frontend.md:1439-1442 (48 flagged sinks fixed).",
   "CONFIRMED", "closed", "-", "neutral.",
   "HTML sinks are lint-gated. The one sanctioned sink (setTrustedHtml) must only receive same-origin Thymeleaf fragment text - keep its contract and its single disable directive.")

ev("FE-PERF-02", "DONE",
   "grep over templates: 0 <style elements, 0 plain <!-- comments; 54 page stylesheets in static/css/pages/; TemplateCommentHygieneTest; #2020. The icon sprite still renders inline (fragments/sidebar.html:222 -> fragments/icons :: sprite) - the optional part was not done.",
   "CONFIRMED", "closed",
   "No developer text or inline page CSS is sent any more (REQ-UI-023).",
   "helps - per-page stylesheets are the unit a per-domain folder can own. Caveat: TemplateCommentHygieneTest.java:124 lists the pages directory with Files.list (non-recursive); moving page CSS into per-domain subfolders would silently narrow that gate unless it walks the tree.",
   "Removed comments that disclosed test names and security reasoning to every visitor.")

ev("FE-PERF-03", "DONE",
   "EtagConfig.java:46-47 ETAG_URL_PATTERNS = /manifest.webmanifest and /.well-known/assetlinks.json only, and the filter is registered on those patterns; ADR-0161 amended (vault Frontend.md:1469-1474); #2010, #2014.",
   "CONFIRMED", "closed", "-", "neutral.", "Pages, fragments and SSE are no longer buffered by the filter; no security effect.")

ev("FE-PERF-05", "DONE",
   "fragments/head.html:70-76 and :142-146 every external script is defer; only krt-client-error.js stays synchronous (:20, REQ-FE-023); InlineScriptLoadOrderTest and ScriptLoadOrderE2eTest (frontend/CLAUDE.md, Script load order); #2020.",
   "CONFIRMED", "closed",
   "The optional minification is not worth it any more: ADR-0214 removed prose comments from the scripts, the edge compresses JS since #2021, and renaming minification would conflict with ADR-0125's no-bundling stance - treat that optional part as dropped.",
   "neutral.", "None; the CSP nonce stays on every script tag.")

ev("FE-PERF-07", "DONE",
   "No server.tomcat.threads key in any application.yml (backend :28-30, frontend :38-40, ingest :16-18 show only spring.threads.virtual.enabled); dashboard 03-spring-apps.json:281,294 uses http_server_requests_active_seconds_count.",
   "CONFIRMED", "closed", "-", "neutral.",
   "None; accept-count 200 and max-connections 10000 remain as connection bounds (frontend application.yml:5-6).")

ev("FE-SIMP-01", "DONE",
   "BackendErrorResponses.java:72 relay(); 111 relay( call sites in 28 controllers (81-prev-sept-b-relay.py); one bespoke block kept because it has a 422 validation branch (AdminSettingsPageController.java:345-352).",
   "CONFIRMED", "closed", "-",
   "neutral - a shared web-kernel helper, not a domain hub; it stays in frontend.support when controllers move into per-domain packages.",
   "Unexpected errors answer an empty 500; relayed problem+json carries only status, code, detail and correlationId (BackendErrorResponses.java:95-107) - no stack, no internal URL.")

ev("FE-SIMP-02", "PARTIAL",
   "Now-part done: every verb goes through one private exchange(...) (BackendApiClient.java:395-407). Later-part open: 0 frontend classes use @HttpExchange or HttpServiceProxyFactory; 623 backendApiClient call sites in 81 files, 362 with a concatenated first argument, 17 with a URI template (81-prev-sept-b-apicalls.py). spring-web 7.0.9 and spring-webflux 7.0.9 (gradle/verification-metadata.xml:8155,8166) already contain HttpExchange, HttpServiceProxyFactory, ImportHttpServices, WebClientAdapter and WebClientHttpServiceGroupConfigurer (unzip -l of the cached jars).",
   "SUPERSEDED-BY-MODULARISATION", "P2",
   "The remaining typed-client work is the frontend half of the domain split: one @HttpExchange interface per backend domain API, in that domain's frontend package, all built from the existing authenticated webClient bean through WebClientAdapter so ADR-0032's single resilience pass stays untouched. It should follow the backend domain APIs (so the interfaces mirror stable per-domain controllers) and needs arc42 4.1 / ADR-0032 wording amended: the seam is the WebClient filter chain, not one class - which is already true, since 13 classes besides BackendApiClient and WebClientConfig inject a WebClient bean directly (streaming, multipart, SSE and live-sync relays).",
   "hinders today - BackendApiClient is the one class every frontend domain depends on (81 files); per-domain typed clients remove that hub.",
   "Encoding by construction removes the FE-SEC-01 class from the remaining concatenations (a type heuristic flags 30 sites with a String variable; seven spot checks were validated, template-bound or backend UUIDs). Keep: bearer relay and X-Active-Org-Unit-Id in the filter chain, the terms-document client isolation (REQ-SEC-052, TermsDocumentClientUsageTest), retry only on idempotent verbs, and the problem+json -> BackendServiceException / ReauthenticationRequiredException mapping plus basetool_backend_client_errors_total (BackendApiClient.java:106-123, :423-468) moved into a shared filter or adapter so no typed client bypasses them.")

ev("FE-SIMP-03", "PARTIAL",
   "Done: static/js/inventory-common.js (1,600 lines) exports window.krtInventory.createLager (:1599), declared in frontend/types/globals.d.ts:705, loaded only by inventory-admin.html and inventory-my.html; orders-detail.js carries no German literal any more. Same defect class left: mission-detail.js has 7 'typeof MSG_X !== undefined ? MSG_X : <German literal>' fallbacks (e.g. :2088, :2150, :2240, :2249, :2334) and two German console messages (:2026, :2035). I18nDictionaryCoverageTest parses only krtI18nText(...) and sectionWrite keys and lists static/js non-recursively (I18nDictionaryCoverageTest.java:47,70), so it does not see them; the vault's 'No literal defaults in scripts' (Frontend.md:1478-1484) overstates the gate.",
   "ADJUSTED", "P2",
   "Finish the class, not only the named sites: move mission-detail.js onto window.krtI18nText with its keys in the page dictionary, extend I18nDictionaryCoverageTest to fail on the 'typeof MSG_... ? ... : literal' shape, and make it walk static/js recursively before any per-domain JS folder exists.",
   "helps - the shared Lager script is domain-local (only the two Lager pages load it), the right granularity for a per-domain frontend folder.",
   "The same fallbacks append raw err.message to a toast (mission-detail.js:2150,2240); dictionary texts remove that small information leak.")

ev("FE-SIMP-04", "DONE",
   "krt-modal.js:246 window.krtModal = {open, close, isOpen, topmost, layerRoot}; 106 calls of fragments/modal-wrapper :: modal; the only <dialog> element is fragments/modal-wrapper.html:4-5; none of the 100 style.display uses targets an overlay (inner elements only, e.g. hangar.js:102); SingleModalShapeTest; #2042, #2051.",
   "CONFIRMED", "closed", "-",
   "neutral - a UI-kernel contract every domain page uses, no domain coupling.",
   "None directly; the inert background while a dialog is open prevents interaction with hidden controls.")

ev("FE-MOD-01", "DONE",
   "0 'var(--x, #hex)' across the 64 non-minified stylesheets (grep); .stylelintrc.json:19-28 declaration-property-value-disallowed-list refuses a hex fallback; #2010/#2014 plus design-system #4.",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("FE-MOD-03", "PARTIAL",
   "Done: eslint.config.mjs:37-39 prefer-const, object-shorthand, eqeqeq plus the FE-SEC-03/05 rules. Open: 44 of 100 scripts carry '// @ts-check'; the 56 unchecked files hold 24,621 of 39,577 lines, including the three largest - mission-detail.js 3,249 lines, bank.js 2,400, orders-detail.js 2,325 (grep '^// @ts-check', wc -l).",
   "ADJUSTED", "P2",
   "Keep the opt-in model (ADR-0125) but turn 'opt in when you touch it' into a ratchet: a count of unchecked files that may only fall, and once scripts live in per-domain folders a rule that a domain folder is fully checked before it is declared migrated. Split the big three by section first; checking a 3,000-line file at once is where the TS-7 null-handling cost lands (frontend/CLAUDE.md).",
   "neutral now; helps when tied to per-domain JS folders (typecheck globs are recursive: frontend/tsconfig.json:40-43).",
   "Typed element handles close silent null-dereference paths that stop live update; no security weakening.")

ev("APPSEC-05", "DONE",
   "SessionTypeAllowList.java:44-47,86-87 (application prefix de.greluc.krt.profit.basetool.frontend.model.); RedisSessionConfig.java:89 default report; docker-compose.yml:303 default report; docker-compose.e2e.yml:51 enforce; ADR-0206 registry line: enforce on production since 2026-09-25 (docs/adr/README.md:276, docs/deployment.md:905-932). The production value itself is NOT-VERIFIABLE here (needs a read of APP_SESSION_TYPE_ALLOW_LIST in the host .env / frontend.env).",
   "ADJUSTED", "P1",
   "Two follow-ups. (1) Production enforces, but the code and compose default is still report, so a new host, the testing host or a lost .env line silently reads every class again: flip the default to enforce and keep report as an explicit opt-in (ADR-0206 amendment). (2) The allow-list names session-held types by the package prefix frontend.model. A per-domain frontend layout that moves forms and DTO mirrors into frontend.<domain>... would be refused in enforce mode (attribute dropped and repaired, ADR-0157); the tempting fix - widening the prefix to frontend. - would admit every frontend class. Design the per-domain model packages so the allow-list enumerates them, or matches a marker type, before any session-held class moves.",
   "hinders the frontend modularisation unless the allow-list is adapted first.",
   "Flipping the default strengthens deserialization safety; widening the prefix would weaken it and is ruled out by this re-evaluation.")

ev("APPSEC-07", "DONE",
   "FrontendClientAuthenticationConfig.java:74-93 selects client_secret_basic + PKCE when KEYCLOAK_FRONTEND_CLIENT_SECRET is set, public + PKCE otherwise; application.yml:69-71; quadlet/env.d/frontend.env.tmpl:22; ADR-0001 status 'implemented 2026-09-23, rolled out on production 2026-09-25' (docs/adr/0001-*.md:3-5). Realm and host state NOT-VERIFIABLE here (Keycloak admin read, host env read).",
   "CONFIRMED", "closed",
   "The empty-secret fallback to a public client stays on purpose for rollback; it fails closed in practice because a confidential Keycloak client refuses a secret-less token request.",
   "neutral.",
   "Keep PKCE in both modes; the secret never enters the session store (vault Frontend.md:1511-1516).")

ev("FE-SEC-06", "DONE",
   "frontend application.yml:26-31 server.servlet.session.cookie name __Host-SESSION, secure, http-only, same-site strict; live since the 1.11.0 deploy (vault Improvement Audit 2026-09.md:129).",
   "CONFIRMED", "closed", "-", "neutral.",
   "The __Host- prefix pins the cookie to the host (no Domain, Path=/, Secure) - no injection from sibling subdomains.")

ev("FE-MOD-02", "DONE",
   "All 64 non-minified stylesheets start with '@layer base, components, page, migration, utilities;' (grep); ADR-0212; CascadeLayerOrderTest; 21 !important remain (the audit counted 24); #2049 with the fix #2052.",
   "CONFIRMED", "closed", "-",
   "neutral - layers are by kind, not by domain; per-domain page CSS stays in the page layer.", "None.")

ev("FE-SIMP-04b", "DONE",
   "fragments/modal-wrapper.html:4-5 <dialog class=\"krt-modal-overlay\">; krtModal opens through showModal(); ADR-0177 amended 2026-09-23 (docs/adr/0177-*.md:3); #2042.",
   "CONFIRMED", "closed", "-", "neutral.", "Native modality makes the page behind inert.")

ev("CI-01", "DONE",
   "gh api code-scanning/default-setup -> state not-configured (default-setup workflow 276305301 last ran 2026-09-22T18:18Z); codeql.yml:1-78 active on push, PR and schedule with actions, java-kotlin, javascript-typescript, python (:31-39) and dependency-caching false (:59); cache-janitor.yml:29-104. Guard not met today: gh api actions/cache/usage -> 10,085,177,219 bytes (9.39 GiB) on 2026-09-29, above the audit's 8 GB target; 17 gradle-dependencies caches hold 5,535 MiB (all younger than 24 h, which cache-janitor.yml:64-70 keeps) and one 1,199 MiB codeql-dependencies cache is read daily by the 'CodeQL - Code Quality' dynamic workflow (run 2026-09-29T12:04:53Z, cache last accessed 12:05:18Z).",
   "ADJUSTED", "P2",
   "The CodeQL decision stands; what is left is the cache budget. NVD and Playwright caches survive today (nvd-feed-v1-Linux-2026-W40 present, 3 playwright caches), but at 94 % of the 10 GiB quota one busy day can evict them. Keep only the newest gradle-dependencies entry per key family instead of 'anything younger than 24 h', or let only ci.yml write main caches.",
   "neutral. UNKNOWN whether a Gradle-subproject layout (Option B) adds cache keys (setup-gradle keys per job, not per project) - settle by comparing cache keys on a trial branch.",
   "The janitor runs with a job-scoped actions: write token (cache-janitor.yml:25-26); the one accepted zizmor finding (dangerous-triggers, .github/zizmor.yml) depends on the fork guard at cache-janitor.yml:24 - keep it.")

ev("CI-02", "DONE",
   "dependency-check.yml:60-67 restore, :108-116 save only with a SARIF on non-PR runs, :69-78 step timeout, :118-141 opens or updates an issue after a failed or cancelled scheduled run. First scheduled run after #1993 (36417643623, 2026-09-28) stalled in the NVD update, was cancelled and opened issue #2262 (closed the same day); the NVD load then moved to the JSON 2.0 data feeds (1d4199cde, 13ccd55b5, 9215d49d9, 2026-09-28) and every run since finished with a SARIF upload (push runs 36451973606, 36467610283, 36530415779; the last took 2 min 36 s).",
   "CONFIRMED", "closed",
   "Verdicts are back; the next scheduled Monday run (2026-10-05) is the last open confirmation.",
   "neutral.", "The weekly vulnerability verdict exists again, and a failed scan now opens an issue instead of cancelling silently.")

ev("CI-03", "PARTIAL",
   "Done: pitest.yml:45-66 fails on PitHelpError or a missing/empty mutations.xml; the root cause (no test profile in PIT's JVMs) is fixed by build.gradle.kts:274-283. Not working on CI yet: the only scheduled run since (35827836688, 2026-09-23) had PIT (backend) cancelled by the 60-minute job timeout (pitest.yml:19) after 13 'Minion exited abnormally due to TIMED_OUT' lines, and its gate step still passed on the partial report ('PIT (backend): 9481 mutations', job log line 983). Next scheduled run 2026-09-30.",
   "ADJUSTED", "P1",
   "Make green mean complete. (a) Give the backend leg room or split it: PIT targetClasses are keyed on the service layer (build.gradle.kts:267-268, ...${project.name}.service.*), so once the backend is package-by-domain the natural split is a matrix of per-domain shards. (b) Gate on PIT's completion statistics line, not on a non-empty XML that PIT writes incrementally. The vault's 'Fixed 2026-09-22' (Testing.md:101-109) rests on a local 30-minute run and is not yet confirmed by CI.",
   "hinders until re-keyed (a package-by-domain move changes PIT's scope from service.* to nothing - the gate would then fail loudly, which is good); helps afterwards (per-domain shards and per-domain kill rates).",
   "None (test signal only).")

ev("CI-SEC-01", "DONE",
   "Anchored identity regexps at .github/actions/retag-verified-digest/action.yml:72, release-images.yml:169, scripts/deploy.sh:35; gate scripts/check-cosign-identity.py with its self-test (repo-lint.yml:262-268); #1987. The host copy of deploy.sh arrives through the Ansible scripts tag - NOT-VERIFIABLE here (read of /var/iri/code/scripts/deploy.sh on the hosts).",
   "CONFIRMED", "closed",
   "Optional P3 lever: IRI_COSIGN_IDENTITY_REGEXP (deploy.sh:35,121) still overrides the trusted identity from the host environment; refusing an unanchored override at run time would make the gate independent of host configuration.",
   "neutral.", "The deploy trust anchor (REQ-OPS-015) now rejects main-x, maintenance and vfoo refs.")

ev("CI-SEC-02", "DONE",
   "gh api rulesets/16482938 (Version, active): rules deletion, non_fast_forward, creation, update; bypass actors User 3463303 and Integration 5037610 (basetool-release App). release-images.yml:20-60 ref-guard refuses any ref but main or a vX.Y.Z tag whose commit is an ancestor of main (:50).",
   "CONFIRMED", "closed", "-", "neutral.", "Only the owner and the App can create a release tag, and only reviewed code is signed.")

ev("CI-SEC-03", "DONE",
   "gh api environments: production (required_reviewers + branch_policy) and testing (branch_policy), each with the single deployment-branch policy main; promote.yml:37 and promote-testing.yml:29 refuse a non-main ref.",
   "CONFIRMED", "closed", "-", "neutral.", "A promotion from a feature branch is refused twice (environment and workflow).")

ev("CI-SEC-05", "DONE",
   "ci.yml:14-15 and refresh-versions.yml:12-13 permissions contents: read; refresh-versions writes through a minted App token (:37-44).",
   "CONFIRMED", "closed", "-", "neutral.", "The GITHUB_TOKEN of both workflows cannot write.")

ev("CI-SEC-06", "DONE",
   "36 actions/checkout uses in .github/workflows and .github/actions, 36 persist-credentials: false (grep counts).",
   "CONFIRMED", "closed", "-", "neutral.", "No token stays in .git/config for later steps.")

ev("CI-SEC-10", "DONE",
   "gh api rulesets/16482244 (main): 9 required checks - Build, Test & Lint; Analyze (java-kotlin); Analyze (javascript-typescript); Verify Signed-off-by on every commit; Check migration version numbering; Linters; gitleaks; Validate Gradle Wrapper; Repository gates. actionlint runs in Linters (repo-lint.yml:57-59), quadlet-drift in Repository gates (:136-146); ADR-0155 corrected (docs/adr/0155-*.md:34-44).",
   "ADJUSTED", "P1",
   "The four named checks are required. The consolidation into four repo-lint jobs left two jobs non-required: Self-tests (repo-lint.yml:306-377 - conformance, container-runtime, render-env-d, redis-acl, internal-tls and image-reuse-plan self-tests) and Container checks (:379-456 - promtool rule syntax and unit tests, monitoring-config validation, keycloak-issuer, edge-nginx, Loki rules). A PR can merge with a failing alert-rule unit test, which the monitoring-sync rule (REQ-OBS-005...011) relies on. Add both job names to the ruleset; deploy-script.yml's self-test would need an always-running shim before it can be required.",
   "neutral.",
   "Strengthening: alert rules and the deploy seam are security controls; making their tests required closes a silent-merge path.")

ev("CI-SEC-11", "DONE",
   "gitleaks.yml:57-75 loads .gitleaks.toml from the PR base commit. gh api repos/krt-profit/basetool: secret_scanning_non_provider_patterns disabled, secret_scanning_validity_checks disabled - not available on this plan (the API accepts the request and leaves them off, vault Improvement Audit 2026-09.md:101-103); push protection enabled.",
   "CONFIRMED", "closed", "The settings half is not obtainable on the current plan; nothing actionable.", "neutral.",
   "A PR can no longer allow-list its own secret.")

ev("CI-SEC-12", "PARTIAL",
   "Done: actionlint and hadolint with SHA-256 (repo-lint.yml:23-26,52,83), gitleaks (gitleaks.yml:17-18,39), zizmor from a hash-pinned requirements file (repo-lint.yml:65-66, .github/requirements/zizmor.txt). Open: PyYAML pinned by version only (repo-lint.yml:108,393; dependabot-compose.yml:39), ansible-core / ansible-lint by version only (repo-lint.yml:277-278), Ansible collections as ranges (ansible/requirements.yml:3-6), and a newer unhashed fetch: exchange-docs.yml:58 'npx --yes markdownlint-cli2@0.23.3' (added 2026-09-27, 6e59b2c79) resolves its dependency tree without a lockfile.",
   "ADJUSTED", "P2",
   "Finish the class: pip requirements files with --require-hashes for PyYAML and Ansible, exact collection versions, and markdownlint run from a lockfile (the frontend's npm ci toolchain or a dedicated package-lock) instead of npx.",
   "neutral.",
   "Supply-chain hardening of CI tools. The affected jobs hold no write permission, which limits but does not remove the impact (they can still alter artifacts and reports).")

ev("CI-SEC-14", "DONE",
   "gh api actions/permissions -> allowed_actions selected, sha_pinning_required true; selected-actions -> github_owned_allowed true, patterns docker/*, gradle/*, sigstore/*, aquasecurity/*, peter-evans/*; fork-pr-contributor-approval -> all_external_contributors.",
   "CONFIRMED", "closed", "-", "neutral.", "An unpinned or foreign action cannot run.")

ev("CI-SEC-15", "DONE",
   "repo-lint.yml:61-75 zizmor 1.30.1 (hash-pinned) with --persona=regular --min-severity=medium over .github/workflows and .github/actions; .github/zizmor.yml ignores only dangerous-triggers on cache-janitor.yml.",
   "CONFIRMED", "closed", "-", "neutral.", "Workflow misconfigurations are gated inside the required Linters job.")

ev("CI-SEC-17", "DONE",
   "e2e-smoke.yml:50-58 deletes auth-state.json, storageState.json and *.zip before upload; :65-69 excludes them from the artifact paths; the workflow is dispatch-only (:3-4).",
   "CONFIRMED", "closed", "-", "neutral.", "A staging session cookie cannot end up in a public artifact.")

ev("CI-SEC-08", "DONE",
   ".github/workflows/dependency-submission.yml (push to main, job-level contents: write); gh api dependency-graph/sbom -> 944 pkg:maven entries (plus 227 npm, 24 githubactions) on 2026-09-29.",
   "CONFIRMED", "closed", "-", "neutral.",
   "Dependabot alerts cover the Maven graph. The submission resolves with --dependency-verification lenient (dependency-submission.yml:37); that affects only this resolve-only job - builds stay strict (ADR-0208).")

ev("OPS-CI-01", "DONE",
   "scripts/check-monitoring-configs.sh:62-132 runs promtool check config, amtool check-config on the rendered template, alloy fmt --test and validate (empty output), loki -verify-config, each from the digest-pinned compose image (:32-43); wired with its self-test at repo-lint.yml:438-444.",
   "CONFIRMED", "closed", "It lives in the non-required Container checks job - see CI-SEC-10.", "neutral.",
   "Broken monitoring configuration cannot ship unnoticed once that job is required.")

ev("OPS-CI-02", "DONE",
   "deploy-script.yml:8-9 and :16-17 include scripts/lib/** and scripts/render-env-d.py.",
   "CONFIRMED", "closed", "-", "neutral.", "The deploy seam's self-tests run on every change of its library (the job itself is not required - CI-SEC-10).")

ev("CI-09", "DONE",
   ".github/actions/setup-jdk-gradle/action.yml used by 9 workflows (grep -l); 0 'chmod +x gradlew' left (gradlew is 100755 in the index); ci.yml:98-114 Checkstyle and SpotBugs uploads only on failure().",
   "CONFIRMED", "closed", "-", "neutral - CI kernel; a Gradle-subproject layout reuses it unchanged.",
   "One place for the setup-java and setup-gradle pins; its cache-disabled input keeps released builds off caches earlier runs wrote.")

ev("CI-11", "DONE",
   "repo-lint.yml:232-234 runs scripts/check-monitoring-image-pins.test.sh before the check (:236-240).",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("CI-12", "DONE",
   "e2e-smoke.yml:3-4 workflow_dispatch only; codeql.yml active again (CI-01).",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("CI-13", "DONE",
   ".github/dependabot.yml:10-13 groups every github-actions update; the docker ecosystems stay ungrouped, which the audit allowed.",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("CI-16", "DONE",
   "All 8 schedules are off the full hour (grep cron: e2e 37 2, pitest 47 1, codeql 17 6, dependency-check 23 5, refresh-versions 11 4, cache-janitor 41 4, edge-deny-probe 17 5, sandbox-smoke 17 4); late starts documented in vault Delivery.md:991-993 and Testing.md:77-83.",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("CI-04", "DONE",
   "repo-lint.yml has four jobs - Linters (:18), Repository gates (:93), Self-tests (:306), Container checks (:379) - each check a named step with if: ${{ !cancelled() }}.",
   "CONFIRMED", "closed", "These are repository-level gates, not module-level; a per-domain layout does not touch them.",
   "neutral.", "Two of the four jobs are not required - CI-SEC-10.")

ev("CI-06", "DONE",
   "e2e.yml:25-90 build-stack builds the backend, frontend, ingest and sandbox-Keycloak images once, docker save | zstd, one artifact kept 1 day (:89); matrix cells load it (:127-139) and run with -Pe2e.prebuilt=true (:152) and one browser each (:105,:150); ADR-0200.",
   "CONFIRMED", "closed",
   "Neutral for Option A. Under Option B the shared docker/app/Dockerfile (:13-18 copy each module's build.gradle.kts by name, :26-27 copy ${MODULE}/src/main and logging-support/src/main) must learn every new subproject, and this job inherits that change.",
   "neutral (A) / Dockerfile change needed (B).",
   "The E2E stack keeps the committed throwaway TLS and synthetic realm; nothing sensitive is in the image artifact.")

ev("CI-08", "DONE",
   ".github/actions/retag-verified-digest/action.yml (resolve digest, cosign verify with the anchored identity :72, imagetools re-tag) is used by promote.yml and promote-testing.yml.",
   "CONFIRMED", "closed", "-", "neutral.", "The signature regexp exists once for both promotions - the byte-equality risk the audit named is gone.")

ev("CI-SEC-04", "DONE",
   "release-images.yml:13 permissions {} with per-job grants on all 8 jobs; the SPI JAR is compiled in keycloak-spi-jar with contents: read and cache-disabled (:748-790) and signed in build-keycloak-spi (:791-800, id-token / attestations); promote.yml and promote-testing.yml carry no id-token. Across all 26 workflows every multi-job workflow sets top-level {} and 27 of 48 jobs declare their own permissions (81-prev-sept-b-wfperms.py).",
   "CONFIRMED", "closed", "-", "neutral.", "A Gradle dependency in the SPI build can no longer request a Fulcio token.")

ev("CI-SEC-13", "DONE",
   ".github/actions/setup-buildx/Dockerfile:1 moby/buildkit:buildx-stable-1@sha256:28a89871...; action.yml:53-55 refuses an undigested image; the mirror fallback pulls the same digest and aliases it (:98-103); Dependabot watches /.github/actions/setup-buildx.",
   "CONFIRMED", "closed", "-", "neutral.", "The builder of signed images is content-addressed even when it comes from the mirror.")

ev("CI-SEC-16", "PARTIAL",
   "Done: gh api actions/secrets -> only NVD_API_KEY and RELEASE_APP_PRIVATE_KEY (both PATs deleted); App tokens are minted per job (refresh-versions.yml:37-44, release-publish.yml:60-66, release-prepare.yml); the App (Integration 5037610) is the Version ruleset's bypass actor. Open: no main-restricted release environment - gh api environments lists only github-pages, production and testing - so the App key is a repository-wide secret.",
   "ADJUSTED", "P2",
   "Move RELEASE_APP_PRIVATE_KEY into a release environment restricted to main and let the three token-minting jobs declare it. Constraint first: release-publish.yml is triggered by pull_request: closed (:3-6), whose ref is the PR merge ref, not main - UNKNOWN whether a main-only deployment branch policy admits it; settle with one dry run, or move publishing to a push-on-main trigger that detects the release commit.",
   "neutral.",
   "Today a workflow change on a same-repo branch could read the App key in a pull_request run; an environment gate limits it to main. Only the owner can push branches, so the exposure is currently low.")

ev("CI-07", "DONE",
   "release-images.yml:62-281 plan job: skips superseded main pushes (:210-236) and reuses per module from the newest ancestor with a complete, dual-arch, main-signed image set younger than 168 h (:150-188, :238-278); .github/scripts/image_reuse_plan.py:44-112 derives each module's inputs from docker/app/Dockerfile's COPY sources; ADR-0210 and amendments; #2031, #2033, #2046.",
   "ADJUSTED", "P3",
   "Neutral for Option A (packages inside backend). For Option B/C it is the most module-name-sensitive CI piece: new backend-* subproject COPY sources contain no ${MODULE}, so input_sets() (:92-112) would classify them as shared and every domain change would rebuild all three images unless MODULE_OWN (:54-56) maps them. Gates with the same assumption: .github/scripts/check_sbom_coverage.py:31-45 (every settings.gradle.kts module SBOM-wired or listed SHIPPED_INSIDE), sandbox-images.yml:8-11 (backend/src/main/** etc.), scripts/check-flyway-migrations.sh:5 (one migration directory), pitest.yml:23 plus build.gradle.kts:267-268. Before any subproject split, derive module ownership from the Gradle project graph instead of Dockerfile text. Only needed if B or C is chosen.",
   "neutral (A); hinders B/C unless adapted.",
   "The reuse gate checks signature, architectures and age before re-tagging; keep every gate when the input derivation changes - a wrong reuse decision ships an image built from older code under a new tag.")

ev("OPS-MON-01", "PARTIAL",
   "Fixed: infrastructure.yml:60-61 ContainerRestartLoop = changes(basetool:container:start_time_seconds[15m]) > 3; recording rule containers-runtime.yml:53-57 (podman_container_started_seconds * on(id) group_left(name) podman_container_info); promtool test containerrestartloop_changes_test.yml; #1984. Not done: REQUIRED_CONTAINER_SERIES (scripts/check-conformance.py:74-81) still lists only the cgroup collector's series; no rule has an absent() leg for podman_container_started_seconds or podman_container_info (grep monitoring/prometheus/alerts); ContainerMetricsMissing (infrastructure.yml:69-78) guards basetool:container:present - the cgroup collector - while its text names the restart-loop alert as blinded.",
   "ADJUSTED", "P1",
   "The only crash-loop alert (vault Container Runtime.md:469-475) is correct now but goes blind again without notice if the podman exporter stops emitting either series (TargetDown covers only a failed scrape). Add both series to the conformance check or an absent() alert, and correct the ContainerMetricsMissing description.",
   "neutral.", "A silent crash loop of backend/frontend/ingest is an availability risk; the guard is cheap.")

ev("OPS-PERF-01", "DONE",
   "scripts/generate-quadlet.py:783-787 emits StopTimeout=<grace> and TimeoutStopSec=<grace+15>, e.g. backend.container 30/45, tempo.container 45/60, db-backend.container 60/75; 9 of 18 container units carry StopTimeout - the other 9 have no stop_grace_period in compose and keep podman's 10 s default (acme, alertmanager, blackbox-exporter, edge, grafana, both postgres-exporters, prometheus, redis-exporter).",
   "CONFIRMED", "closed",
   "As recommended. Prometheus has no grace period and gets 10 s for its TSDB shutdown - a measured stop_grace_period in compose is an optional P3, not a regression.",
   "neutral.", "Clean database shutdowns avoid crash recovery; no security effect.")

ev("OPS-REL-01", "DONE",
   "scripts/lib/container-runtime.sh:374-395 rt_monitoring_up restarts units named in RT_CHANGED_SERVICES and starts the rest; scripts/deploy.sh:571 RT_STACK_SERVICES ends with acme; docker/acme is mirrored (:206,245,319); #1984 (0076546ec).",
   "CONFIRMED", "closed", "-", "neutral.",
   "A changed image digest (for example a security patch) of a monitoring unit or acme now takes effect at deploy, not at the next reboot.")

ev("OPS-SEC-01", "DONE",
   "ansible/roles/basetool_host/tasks/45-updates.yml:17-29 dnf-automatic upgrade_type = security, apply_updates = yes, exclude list; alerts HostSecurityUpdatesFailing, HostSecurityUpdatesStale, HostRebootRequired (infrastructure.yml:200-228); ADR-0199, REQ-OPS-032. Host state NOT-VERIFIABLE here (dnf-automatic.timer read on the hosts); the vault records a read-only verification on testing (Host Provisioning.md:172-174).",
   "CONFIRMED", "closed", "-", "neutral.",
   "Unattended security patching with the container runtime excluded and updated through the role; keep the exclusion list in step with the runtime packages.")

ev("OPS-SEC-02", "DONE",
   "scripts/lib/container-runtime.sh:438-442 rt_prometheus_snapshot runs BusyBox wget inside the prometheus container, password read from the container's mounted secret; removal :444-448; backup.sh:279-292.",
   "CONFIRMED", "closed",
   "Residual accepted: the Basic header (base64 of grafana:<password>) is on wget's argv inside the prometheus container for the call's duration (:441), and the admin API is reached with the Grafana web user. Prometheus' web config has no per-user authorization, so a separate user would not reduce privileges.",
   "neutral.", "No host-level argv exposure and no extra image any more.")

ev("OPS-SEC-03", "DONE",
   "backup.sh:91-97 and restore-drill.sh:74-80 use db-backend's own digest-pinned image through rt_unit_image (container-runtime.sh:423-435; db-backend.container:6 carries @sha256). But when the unit file cannot be read, both continue with the unpinned docker.io/library/postgres:18-alpine (backup.sh:29,95-96; restore-drill.sh:19,78-79) and only log a WARN; rt_unit_image does not require @sha256.",
   "ADJUSTED", "P1",
   "The helper reads the keystore, the internal TLS directory and the Redis ACL file (backup.sh:177-219); a fail-open fallback to a floating tag in exactly that situation defeats the pin. Fail closed (refuse, and let BackupStaleOrMissing page) and require a digest in rt_unit_image.",
   "neutral.", "Strengthening: removes the one path by which a mutable image handles every backup secret.")

ev("OPS-SEC-04", "DONE",
   "quadlet/systemd/keycloak.container:21-24: theme and providers :ro, keystore :ro, no realm-export mount; the generator refuses a writable config-tree mount (#1992, fbd154f82).",
   "CONFIRMED", "closed", "-", "neutral.", "A compromised Keycloak process cannot rewrite its own providers or theme.")

ev("OPS-MON-02", "DONE",
   "monitoring/prometheus/alerts/ops-automation.yml:84-87 DeployHeartbeatStale (time() - basetool_deploy_last_stack_healthy_timestamp > 3600 or absent); tests in monitoring/prometheus/tests/ops_audit_2026_09_alerts_test.yml.",
   "CONFIRMED", "closed", "-", "neutral.", "A stopped deploy timer - no patched images reach the host - now alerts.")

ev("OPS-MON-03", "DONE",
   "infrastructure.yml:80-81 PostgresDown (pg_up == 0, for 2m), :89-90 RedisDown (redis_up == 0, for 2m), :188-191 ContainerUnhealthy (podman_container_health, for 10m); the 04:15 quiesce stops only frontend, backend and ingest (backup.sh:22), so the 2-minute windows do not fire nightly.",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("OPS-MON-04", "DONE",
   "monitoring/prometheus/alerts/containers-runtime.yml:73-76 ContainerCgroupCollectorStale with 'or absent(basetool_container_metrics_timestamp_seconds)'.",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("OPS-MON-05", "DONE",
   "monitoring/prometheus/alerts/ops-automation.yml:55-56 RestoreDrillStaleOrMissing at 8 * 24 * 3600 or absent.",
   "CONFIRMED", "closed", "-", "neutral.", "Backup restorability is re-proven weekly with an 8-day alert window.")

ev("OPS-PRIV-01", "DONE",
   "ansible/roles/basetool_host/tasks/27-observability.yml:135-136 MaxRetentionSec / SystemMaxUse from defaults/main.yml:95-96 (31d, 4G); REQ-OBS-010 amended (docs/specs/observability.md:1104-1109). Host state NOT-VERIFIABLE here (journald config read on the hosts); vault Host Provisioning.md:174 records it for testing.",
   "CONFIRMED", "closed", "-", "neutral.", "Client IPs in the journal follow the approved 31-day retention.")

ev("OPS-SEC-07", "DONE",
   "quadlet/systemd/prometheus.container:22 carries --web.enable-admin-api (weekly snapshot) but no --web.enable-lifecycle.",
   "CONFIRMED", "closed", "-", "neutral.", "The remote reload/quit endpoints are gone; the admin API stays behind web.yml basic auth.")

ev("OPS-SIMP-01", "DONE",
   "grep -c 'docker)' scripts/lib/container-runtime.sh -> 0 (the audit counted 19); ADR-0203; ADR-0194 amended; #2001 (450653df0).",
   "CONFIRMED", "closed", "-", "neutral - operational scripts, not business domains.", "Less code in the most privileged scripts.")

ev("OPS-SIMP-02", "DONE",
   "No cadvisor reference left in monitoring/prometheus/alerts/containers-runtime.yml or docker-compose.monitoring.yml (grep); docker/maintenance holds only static/; ADR-0203 records that the compose files stay the source of the Quadlet units; alert texts name systemctl --user (e.g. infrastructure.yml:87,96).",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("OPS-SIMP-03", "DONE",
   "scripts/iri-deploy-account-sandbox.conf installed as 10-deploy-account-sandbox.conf for the four deploy-account units (ansible/roles/basetool_host/tasks/25-scripts.yml:87-104); restore-drill ReadWritePaths narrowed to /var/lib/iri /var/iri/backup /var/iri/monitoring/textfile /var/log (scripts/iri-restore-drill.service:17). A drill or cleanup run under the new sandbox is NOT-VERIFIABLE here; the vault records #2001 as merged without a host test.",
   "CONFIRMED", "closed", "-", "neutral.",
   "A per-unit drop-in rather than a prefix drop-in keeps the root units' ProtectHome=true intact (vault Container Runtime.md:432-434).")

ev("OPS-SIMP-04", "DONE",
   "scripts/lib/common.sh (log, fail, read_env, write_textfile) is sourced by backup.sh, container-cleanup.sh, deploy.sh and restore-drill.sh and installed by ansible 25-scripts.yml:23.",
   "CONFIRMED", "closed", "-", "neutral - an operations shared library, not a business-domain hub.",
   "read_env validates the key name and write_textfile the file name - keep both; they sit on the secret-bearing .env.")

ev("OPS-SEC-06", "DONE",
   "scripts/check-conformance.py:1290-1330 compares node_exporter_build_info and alloy_build_info on the host with the compose pins; the RPMs themselves stay unpinned (ansible defaults/main.yml:80-83).",
   "CONFIRMED", "closed", "-", "neutral.", "Version drift of the host agents is detected; patching stays dnf-driven.")

ev("OPS-MOD-01", "DONE",
   "RunInit= and Ulimit= keys in 15 unit lines; the remaining PodmanArgs are only --cpus (9 units) and --oom-score-adj=500 (9 units), for which podman 5.8 has no Quadlet key (docs/arc42/07-deployment-view.md:104-106); generate-quadlet.py:750.",
   "CONFIRMED", "closed", "-", "neutral.", "None.")

ev("OPS-SEC-08", "DONE",
   "#1992 (fbd154f82) replaced the false 'no shell as another user' comment with the plain statement that podman * as iri lets deploy run any code as iri; the ADR-0214 sweep (#2074) then removed all YAML comments, and the fact now lives in ansible/README.md:43, docs/deployment.md:100 and docs/specs/deployment-delivery.md:876; the sudoers rule is unchanged (22-deploy-user.yml:37-39).",
   "REPRIORITISED", "P3",
   "The documentation half is done. The optional wrapper that admits only the podman sub-commands the rt_* seam uses is still the only way to make deploy narrower than iri; worth it now that the seam is Podman-only (OPS-SIMP-01), but low urgency.",
   "neutral.", "Today deploy is effectively iri, not root; a wrapper would narrow it - a strengthening either way.")

ev("OPS-SEC-05", "DONE",
   "Internal=true at line 6 of net-db-backend, net-db-keycloak, net-redis-backend, net-redis-frontend and net-redis-ingest (quadlet/systemd/*.network); ADR-0162 amended. Production recreated on 2026-09-25 per vault Improvement Audit 2026-09.md:125, testing pending - both NOT-VERIFIABLE here (podman network inspect on the hosts).",
   "CONFIRMED", "closed", "Testing-host rollout still pending per the vault.", "neutral.",
   "Database and Redis containers have no internet egress; exfiltration from a compromised data container needs another hop.")

SIB = {
    "SIB-SEC-01": "Decided 2026-09-22: the P4K Reader stays local and gets no repository; its CI recommendations do not apply (vault Improvement Audit 2026-09.md:67-68, :321).",
    "SIB-CI-04": "Done 2026-09-23 in sc-file-reader (local, unversioned, no PR by design): the extractor's package-msi.ps1 ported with a checksum-pinned WiX 7 bootstrap and a JDK preflight; install, upgrade and uninstall verified on the workstation (vault :140).",
    "SIB-SEC-02": "Done for the extractor - basetool-sc-extractor #58, #59; the reader half does not apply (vault :141).",
    "SIB-SEC-04": "Decided 2026-09-22 (non-exportable CNG key, members sign in once) and done for the extractor - basetool-sc-extractor #58, #59; the spec wording is basetool #1998 (08c27e1ce, on main) (vault :93, :141).",
    "SIB-SEC-05": "Done for the extractor - basetool-sc-extractor #58, #59 (vault :141).",
    "SIB-SEC-09": "Done for the extractor - basetool-sc-extractor #58, #59 (vault :141).",
    "SIB-CI-03": "Done for the extractor - basetool-sc-extractor #58, #59; the reader half does not apply (vault :141).",
    "SIB-MOD-02": "Done for the extractor - basetool-sc-extractor #58, #59; the reader half does not apply (vault :141).",
    "SIB-PERF-01": "Done for the extractor - basetool-sc-extractor #58, #59 (vault :141).",
    "SIB-SEC-06": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-SEC-07": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-SEC-08": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-SIMP-03": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-CI-01": "Done - basetool-android #178-#181; runs on a path filter and nightly because no instrumented label exists (vault :142; open owner choice at :327-328).",
    "SIB-CI-02": "Done - basetool-android #178, #179, #180, #181 (vault :142; devRelease disabled per Testing.md:111-113).",
    "SIB-CI-05": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-SIMP-01": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-SIMP-02": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-MOD-01": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
    "SIB-SIMP-04": "Done - basetool-android #178, #179, #180, #181 (vault :142).",
}

HOST_UNVERIFIED = ["APPSEC-05", "APPSEC-07", "CI-SEC-01", "OPS-SEC-01", "OPS-PRIV-01", "OPS-SIMP-03", "OPS-SEC-05"]

NOTE = {
    "APPSEC-03": "8 MiB cap before any read, upload streamed (HangarImportProxyController.java:64,115,128)",
    "APPSEC-11": "ResponseStatusException handler keeps the backend status (GlobalExceptionHandler.java:415-454)",
    "APPSEC-12": "Logs carry a 12-hex SHA-256 fingerprint, never the session id",
    "BE-PERF-05": "Roster read removed (25430fd7e, in #2004)",
    "CI-SEC-18": "npm ci + ignore-scripts (build.gradle.kts:472, frontend/.npmrc:1)",
    "FE-MOD-04": "Jackson 3 in MissionWriteController; Thymeleaf serializer config is the last Jackson-2 user",
    "FE-PERF-04": "Only 7 basetool-* logos left (24,987 bytes)",
    "FE-PERF-06": "Six named reloads gone; 19 left are fallbacks or the sanctioned conflict confirm",
    "FE-SEC-01": "Template variables, Instant binding, status allow-list; the remaining concatenation class goes to FE-SIMP-02",
    "FE-SEC-02": "Same-origin path check and UUID binding (MeFrontendController.java:86-98)",
    "FE-SEC-04": "csp_violation beacon kind, metric, dashboard and alert text",
    "BE-PERF-06": "Paged /users/search/references with UserReferenceDto, same gates",
    "FE-MOD-01": "0 hex fallbacks; a Stylelint rule forbids them",
    "FE-MOD-03": "ESLint rules done; 44 of 100 scripts type-checked, none of the 3 largest",
    "FE-PERF-01": "One /me/layout read; the endpoint is a cross-domain composition that belongs in a shell module",
    "FE-PERF-02": "No comments or `<style>` in templates; 54 page stylesheets; sprite still inline",
    "FE-PERF-03": "ETag filter only on the manifest and assetlinks",
    "FE-PERF-05": "Every script defer except krt-client-error.js; optional minification dropped",
    "FE-PERF-07": "Tomcat thread keys gone; panel reads http_server_requests_active",
    "FE-SEC-03": "Lint bans non-GET raw fetch; 0 raw writes left (55 raw GETs)",
    "FE-SEC-05": "no-unsanitized lint, one escape helper, one sanctioned HTML sink",
    "FE-SIMP-01": "111 relay() uses in 28 controllers; one deliberate bespoke block",
    "FE-SIMP-02": "exchange() done; typed @HttpExchange clients open = the frontend half of the domain split",
    "FE-SIMP-03": "Shared Lager script done; mission-detail.js keeps 7 German literal fallbacks the gate cannot see",
    "FE-SIMP-04": "window.krtModal and the modal wrapper everywhere",
    "APPSEC-05": "Allow-list done, prod enforce per docs; code default still report; package-prefix hazard for per-domain packages",
    "APPSEC-07": "Confidential client whenever the secret is set; PKCE always",
    "FE-MOD-02": "All 64 stylesheets in cascade layers (ADR-0212)",
    "FE-SEC-06": "`__Host-SESSION` cookie",
    "FE-SIMP-04b": "Native `<dialog>` in the one wrapper; ADR-0177 amended",
    "CI-01": "Advanced CodeQL only; Actions cache still 9.39 of 10 GiB (Gradle and Code Quality caches)",
    "CI-02": "Scan finishes again since the NVD JSON 2.0 switch; the failed scheduled run opened #2262",
    "CI-03": "Gate added, but the backend PIT leg timed out at 60 min and passed on a partial report",
    "CI-09": "One setup composite in 9 workflows; no chmod",
    "CI-11": "Image-pin self-test wired before the check",
    "CI-12": "e2e-smoke dispatch-only; CodeQL active",
    "CI-13": "github-actions updates grouped",
    "CI-16": "Crons off the hour; late starts documented",
    "CI-SEC-01": "All identity regexps anchored plus a gate; host copy not verifiable",
    "CI-SEC-02": "Tag ruleset creation/update (owner + App bypass); ref-guard with ancestor check",
    "CI-SEC-03": "production/testing main-only; promotions refuse other refs",
    "CI-SEC-05": "contents: read in ci.yml and refresh-versions.yml",
    "CI-SEC-06": "36 of 36 checkouts persist-credentials: false",
    "CI-SEC-08": "Dependency graph holds 944 pkg:maven entries",
    "CI-SEC-10": "9 required checks; the Self-tests and Container checks jobs are still not required",
    "CI-SEC-11": "gitleaks config from the base commit; non-provider/validity checks unavailable on the plan",
    "CI-SEC-12": "Binaries hashed; PyYAML, Ansible and an npx fetch still unhashed",
    "CI-SEC-14": "SHA pinning required, selected owners, approval for external contributors",
    "CI-SEC-15": "zizmor (hash-pinned) inside the required Linters job",
    "CI-SEC-17": "Session material stripped from the smoke artifacts",
    "OPS-CI-01": "promtool/amtool/alloy/loki config checks in CI (non-required job)",
    "OPS-CI-02": "Path filter covers scripts/lib/** and render-env-d.py",
    "CI-04": "repo-lint is four jobs",
    "CI-06": "E2E images built once, one browser per cell (ADR-0200)",
    "CI-08": "retag-verified-digest composite shared by both promotions",
    "CI-SEC-04": "Per-job permissions; the SPI is compiled without id-token",
    "CI-SEC-13": "BuildKit pinned by digest through a carrier Dockerfile",
    "CI-SEC-16": "PATs deleted, App tokens; the App key is not confined to a main-only environment",
    "CI-07": "Per-module re-tag (ADR-0210); input derivation must change before any Gradle-subproject split",
    "OPS-MON-01": "Alert reads the podman series now; no absence guard or conformance entry for them",
    "OPS-PERF-01": "StopTimeout and TimeoutStopSec for all 9 units that declare a grace",
    "OPS-REL-01": "Changed monitoring units restarted; acme applied",
    "OPS-SEC-01": "dnf-automatic security-only, runtime excluded, three alerts",
    "OPS-MON-02": "DeployHeartbeatStale with an absent() leg",
    "OPS-MON-03": "PostgresDown, RedisDown, ContainerUnhealthy",
    "OPS-MON-04": "absent() leg for the cgroup collector",
    "OPS-MON-05": "Restore-drill threshold 8 days",
    "OPS-PRIV-01": "Journal 31d / 4G; REQ-OBS-010 amended",
    "OPS-SEC-02": "Snapshot via wget inside prometheus; small argv residual accepted",
    "OPS-SEC-03": "Pinned helper image, but the fail-open fallback to postgres:18-alpine remains",
    "OPS-SEC-04": "No realm mount; theme and providers :ro",
    "OPS-SEC-07": "--web.enable-lifecycle removed",
    "OPS-MOD-01": "Quadlet keys where they exist; --cpus and --oom-score-adj remain",
    "OPS-SEC-06": "Conformance compares host agent versions with the compose pins",
    "OPS-SEC-08": "Plain statement now in ansible/README.md:43; optional sudo wrapper still open",
    "OPS-SIMP-01": "No Docker branch left in the runtime seam",
    "OPS-SIMP-02": "cAdvisor and NPM leftovers gone; ADR-0203",
    "OPS-SIMP-03": "One sandbox drop-in, drill paths narrowed; no host run evidenced",
    "OPS-SIMP-04": "scripts/lib/common.sh shared by four scripts",
    "OPS-SEC-05": "Internal=true on the five data networks; testing rollout pending",
}
