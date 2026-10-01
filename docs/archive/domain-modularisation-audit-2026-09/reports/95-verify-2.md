# 95-verify-2 — Adversarial verification, frontend and edge (2026-09-29)

Read-only. Repo = worktree at `95e945326`. All paths below are relative to it unless prefixed.
Helper scripts (scratchpad): `95-verify-2-UriProbe.java` (empirical URI encoding against the
resolved `spring-web-7.0.9.jar`, run with JDK 25 single-file launch, no Gradle),
`95-verify-2-edge-sim.py` (two-gate simulation of the nginx allow-list over `openapi.json`),
`95-verify-2-openapi-params.py`, `95-verify-2-android-usage.py` (read-only `git grep`/`git show` on
`$ANDROID_REPO` tag `v0.3.1`, commit `e5864bb0`, 2026-09-25). Spring/Netty sources
read from the Gradle cache sources jars (`spring-web/webflux/webmvc 7.0.9`,
`spring-security-web 7.1.1`, `reactor-netty-http 1.3.7` — the versions pinned in
`gradle/verification-metadata.xml`), extracted to `95-verify-2-src/`.

## Verdicts

| # | Claim | Verdict | Correction in one line | Honest severity |
|---|---|---|---|---|
| 1 | FE-03 relayed String ids reshape backend URIs | **NARROWED** | Only 2 of 4 sites (Home ×2, Promotion) can append `?query`/`/segments`; DefaultBlueprints and PersonalInventory cannot (their `URLEncoder` output is then *double*-encoded). No site leaves its path prefix; `..` is refused 400 by the backend firewall. | Informational (REQ-SEC-051 deviation) |
| 2a | CSV formula injection in promotion export | **CONFIRMED (code) / NARROWED (reach)** | Usernames are the Discord `username` (not user-editable); the realistic injector is an ADMIN/OFFICER via topic name (header cell starts with it); victim = another ADMIN/OFFICER who exports. | Low |
| 2b | reauth/terms redirect helpers accept `//host`, `/\host` | **CONFIRMED (code) / no attacker input** | All four inputs are server constants (`contextPath + fixed path`); backend SSE event names are fixed; CSP `default-src 'self'` keeps fetch same-origin. | None today (hardening) |
| 2c | four `targetUrl` navigations unchecked | **CONFIRMED (code) / no attacker input** | Every `targetUrl` is server-built: constants, an allow-list, a `UUID`, or the fixed prefix `/orders/create?source=` + raw `source`. | None today (hardening) |
| 3 | LiveSync authorizer fails open | **CONFIRMED, and broader than stated** | Fail-open covers every non-403/404 status incl. **401**, plus timeouts, missing token, executor saturation; the capability class `orders` fails open even on 403/404. A member can trigger it at will (keep a socket >300 s, then subscribe). Payload = section keys only. Documented as accepted (ADR-0094). | Low (metadata) |
| 4 | Edge allow-list admits by `$uri`; 259/234/14; POST /operations | **CONFIRMED numbers / NARROWED "unintended"** | 259 pass the `$uri` gate, 11 of them are then 405'd, 248 reach the backend = 234 frozen + the same 14. But **10 of the 14 are called by the shipped app v0.3.1** — they are unfrozen contract gaps, not unintended. Truly unused: 4. POST /operations 404 confirmed (app v0.3.1 calls it). | Low (surface) / functional gap |
| 5 | 11 controllers bypass BackendApiClient error mapping | **CONFIRMED** (3/3 sampled) | `GlobalExceptionHandler` would *not* rescue an escaped `ClientAuthorizationException` (catch-all 500). Practical frequency reduced: the terms gate discovers a dead token first when its 60 s cache is stale. The calls still show in `http_client_requests_seconds`, not in the dedicated counter/alert. | Low (UX/observability) |
| 6 | username `hashCode` tags in logs | **CONFIRMED, count understated** | 5 hashing *sites*, but `maskPrincipal` feeds 10 log statements → 14 statements; 6 emit at INFO+ in prod. `principal.getName()` = `preferred_username` (application.yml:77). PiiMasker does not catch it. | Low (REQ-OBS-004 deviation) |
| 7 | ParallelPageLoader drops the locale | **CONFIRMED (mechanism) / NARROWED (impact)** | Backend locale only affects RFC 7807 title/detail texts; the 8 controllers never render backend `detail` from loader reads. User-visible effect today ≈ none. | None (latent) |
| 8 | Session allow-list prefix; refusal drops the attribute | **CONFIRMED + nuance** | Drop unit is the *whole* session attribute; flashed forms live in the single `SessionFlashMapManager.FLASH_MAPS` list, so one refused class drops every pending flash map (form, BindingResult, toasts). No 500, no sign-out. Prod `enforce` is documented, not observable from the repo. | n/a (refactor regression risk) |

---

## 1. FE-03 — String-bound ids concatenated into backend URIs — NARROWED

**Mechanism (empirically settled).** `BackendApiClient.put/get` pass the string to
`webClient.X().uri(String)` (`frontend/.../service/BackendApiClient.java:297-315, 329-335`). The
`webClient` bean sets only `baseUrl` (`WebClientConfig.java:473-488`), so WebClient builds
`new DefaultUriBuilderFactory(baseUrl)` (`DefaultWebClientBuilder.java:354-359`, webflux 7.0.9),
whose default is `EncodingMode.TEMPLATE_AND_VALUES` (`DefaultUriBuilderFactory.java:50`, `:298`
`result.encode()`). That mode pre-encodes only *illegal* characters of the template; reserved
delimiters survive. Probe output (spring-web 7.0.9):

```
…/read-announcement/<uuid>?foo=bar&size=5000   -> sent unchanged (query appended)
…/read-announcement/x/../../../bank/accounts   -> sent unchanged (dot segments kept)
…/read-announcement/{x}                        -> IllegalArgumentException (template var)
…/default-blueprints/ + URLEncoder("a?b=c")    -> …/a%253Fb%253Dc   (double-encoded)
…/personal-inventory?…&sort= + URLEncoder("name,asc") -> sort=name%252Casc (double-encoded)
```

Reactor Netty sends `url.getRawPath()` + query, strips only the fragment, no normalisation
(`reactor-netty-http 1.3.7 UriEndpointFactory.java:82-84, 103-120`). The backend then refuses any
non-normalised URI with 400: no module customises the firewall (grep `HttpFirewall` = 0 hits), so
`FilterChainProxy` uses `StrictHttpFirewall` + `HttpStatusRequestRejectedHandler`
(`FilterChainProxy.java:159-161`; `StrictHttpFirewall.java:520-521, 587-599`, security-web 7.1.1).

| Site | Verdict | What is reachable | Who / CSRF |
|---|---|---|---|
| `HomeController.java:190-199, 208-221` (`@RequestParam String id` → `"/api/v1/users/me/read-announcement/" + id`) | CONFIRMED | `PUT /api/v1/users/me/read-announcement/<anything>[?anything]` only. Handler takes `@PathVariable UUID`, reads no query (`backend/.../UserController.java:621-627`); no deeper mapping; `..` → 400. | Member, own token. POST + CSRF on by default (`frontend/.../SecurityConfig.java:177`, no `ignoringRequestMatchers`) → no cross-site trigger. |
| `PromotionPageController.java:261-273` → `:551-561` (concat at 555; raw `userId` logged at 558) | CONFIRMED | `GET /api/v1/promotion/eligibility/user/<anything>[?…]`; handler `@PathVariable UUID`, no query (`PromotionEligibilityController.java:106-117`). | ADMIN/OFFICER (`:262`). GET → a link can fire it in an officer's browser, but it is a read whose result renders back to the officer (SOP). Contradicts REQ-SEC-051's table row `userId → UUID` (`docs/specs/security-and-access.md:3470`). |
| `AdminDefaultBlueprintsPageController.java:203-215` (`@PathVariable String id`, `URLEncoder` at 208) | **REFUTED as injection** | `URLEncoder` encodes `?&=/#%{`, then TEMPLATE_AND_VALUES re-encodes `%` → junk segment → UUID 400. `.`/`..` pass URLEncoder, but cannot arrive as a path variable (browser normalisation; `%2E` refused by the frontend's default firewall, `StrictHttpFirewall.java:94,164`). Real defect: form-encoding in a path + double encoding (correctness only). The AJAX twin binds `UUID` (`:227`). | ADMIN only (`:69-71`), POST + CSRF. |
| `PersonalInventoryPageController.java:372-392` (`URLEncoder` sort at 381) | **REFUTED as injection; new functional bug** | `&` → `%2526`: no injection. But any directional sort is double-encoded (`sort=name%252Casc`) → backend sees literal `name%2Casc` → sort cannot work. Latent: no UI emits `sort` (0 hits in `personal-inventory.html`/`.js`/fragments). Deviates from REQ-SEC-051 row "Spring sort spec → `RelayParams.sortSpecOrNull`" (`security-and-access.md:3472`). The claim's "around RelayParams" is wrong: this controller does not use `RelayParams` at all (used only in 4 other controllers). | Member, own data. |

Side effect worth one line: a `{`/`}` in the String id makes URI expansion throw inside
`BackendApiClient.exchange` → `log.error` + `basetool_backend_client_errors_total{reason="unknown"}`
(`BackendApiClient.java:404-405, 517-519`) → a member can generate ERROR lines / feed the
`> 0.5/s` client-error alert (`monitoring/prometheus/alerts/business.yml:681`) from their own session.
A UUID-typed binding stops this at the frontend (400, no backend call). **Gain: none.**

## 2. FEA-10

**(a) CSV — CONFIRMED gap, NARROWED reach.** `pmCsvEscape` quotes on `" , \n \r` only
(`static/js/promotion-manage.js:505-517`); a quoted `=…` is still a formula in a spreadsheet. It is
the only CSV export in the codebase (grep `text/csv` = this file only). Cells:
`data-pm-username` (`promotion-manage.html:175`), rank, eligibility, date, level labels; header =
`topicName + ' / ' + catName` (`promotion-manage.js:537-539`, template `:164`).
- Username = backend `username` = JWT `preferred_username`
  (`backend/.../UserReconciliationService.java:156-157, 270-271`) = Discord `username`
  (`keycloak-spi/.../DiscordIdentityProvider.java:166-170`), `editUsernameAllowed: false`
  (`docs/keycloak/realm-config.reference.json:38`). Discord's username charset would exclude a
  leading `= + - @` — **external rule, not verified today (UNKNOWN in repo)**.
- Topic/category names: no `@Pattern` (`PromotionTopicWriteRequest.java:38`,
  `PromotionCategoryWriteRequest.java:41`); writable by ADMIN/OFFICER only, squadron-scoped
  (`PromotionTopicService.java:126,167`; `PromotionCategoryService.java:147,182`).
- Opened by: the exporter, an ADMIN/OFFICER (`PromotionPageController.java:261-262`).
- Severity: Low — officer → officer/admin, needs the victim to open the file in a spreadsheet and,
  for exfiltration, usually accept a warning/click.

**(b) Redirect helpers — CONFIRMED code, no attacker-controlled input found.**
`reauthRedirect` (`krt-fetch.js:119-131`) and `termsGateRedirect` (`:171-177`) accept any leading
`/`; `safeSameOriginUrl` rejects `//` and `/\` (`safe-url.js:3-15`). Inputs:
- `X-Reauthenticate`: `contextPath + "/oauth2/authorization/keycloak"`
  (`GlobalExceptionHandler.java:156,172`; `TermsAcceptanceGateFilter.java:162-178`;
  `SsoReAuthenticationEntryPoint.java:144-159`).
- `X-Terms-Acceptance-Required`: `contextPath + "/terms/accept"` (`TermsAcceptanceGateFilter.java:128-147`).
- SSE `reauth`/`terms-gate` data (`notifications.js:637, 653`): written by the frontend with the same
  constants (`NotificationPageController.java:470-497`); relayed backend events keep their names
  (`:521-539`) but the backend only emits `connected`/`notification`/`heartbeat`/`replaced`
  (`NotificationStreamService.java:134,159,205,257`).
- WS close 4003 reason (`krt-live-sync.js:146`): the same consentUrl
  (`LiveSyncSyncHandshakeInterceptor.java:145-147`; `LiveSyncWebSocketHandler.java:451-455, 489-491`).
- CSP `default-src 'self'`, no `connect-src` override (`SecurityHeaders.java:43-49`) → no foreign
  response can reach `krtFetch`. Severity: none today; aligning with `safeSameOriginUrl` is hardening.

**(c) `targetUrl` — CONFIRMED code, server-built values only.** Sites exact:
`inventory-input.js:484-487`, `orders-create.js:524-525`, `refinery-orders-create.js:406`,
`refinery-orders-details.js:754`. Producers: `InventoryWriteController.java:165` via
`inventorySourceTarget` allow-list (`:282-290`); `JobOrderWriteController.java:246,455` via
`postCreateTarget` = `"/orders/create" + "?source=" + <raw source>` or `"/orders"` (`:397-403`) and
`"/orders/" + id` with `@PathVariable UUID` (`:304, 323`); `RefineryOrderWriteController.java:478,
515, 541, 578` constant `/refinery-orders`. The raw `source` can only add query parameters to the
submitter's own same-origin navigation. Severity: none today.

## 3. FE-05 / XC-08 — LiveSync fail-open — CONFIRMED, broader than stated

Per status, resource probes (`LiveSyncSubscriptionAuthorizer.java:245-276`): 2xx ALLOW; 403/404
DENY; **every other status** (400, 401, 405, 409, 429, 5xx) and any `RuntimeException`
(timeout 3 s, transport) → `failOpen` = ALLOW for non-presence classes, `DENY_INDETERMINATE` for
presence (`:91-93`). Also fail-open: no captured token (`:193-195`), executor saturated
(`LiveSyncWebSocketHandler.java:653-662`). Capability probe (`:289-316`) catches every
`RuntimeException` **before any status check**, so for `orders` a 403/404 also fails open.

| Class (`LiveSyncTopicClass.java`) | Authorization | Fails open |
|---|---|---|
| `mission` (40-55) | probe `/missions/{id}`, presence | never (fails closed) |
| `operation`, `order`, `refinery-order`, `bank` account (62-136) | probe per resource (bank: fallback only on 403/404) | any non-403/404 status, timeout, no token, saturation |
| `orders` queue (100-107) | capability `canViewJobOrders` | **any** error incl. 403/404 |
| `bank` staff, `orgunit-bank`, `members`, `exchange-clients` | local role | only if authorities null — not reachable: `/ws/sync` is `authenticated()` (`SecurityConfig.java:205-206`) |
| `hangar`, `blueprints` (own) | selfOnly | never |
| `materialboard`, `inventory`, `missions`, `refinery`, `org-structure` | socket auth only | n/a (allowed by design) |

**Deterministic trigger:** the bearer is captured once at handshake
(`LiveSyncSyncHandshakeInterceptor.java:109-121`), never refreshed; no socket max-age (30 s
keepalive, `LiveSyncWebSocketHandler.java:87-90`; no expiry check in the package); reference realm
`accessTokenLifespan: 300` (`docs/keycloak/realm-config.reference.json:9`). A member who keeps a
socket open >5 min and then subscribes gets 401 → ALLOW on every non-presence resource topic.

**What an ALLOW yields:** a `subscribed` ack, then only
`{"type":"changed","topic":"<class>:<uuid>","sections":[…]}` (`LiveSyncWebSocketHandler.java:720-751,
1229-1259`); snapshots only for presence classes (`:747-749`). No payload; receivers re-fetch
fragments that are authorized per viewer. Leak = timing + whitelisted section keys of a resource
whose UUID the subscriber must already know. Documented, accepted design
(`docs/specs/frontend-ajax-mutations.md:1292-1300`, ADR-0094). Severity: Low. API re-cut note:
probe paths are hard-coded; a renamed path yields 404 → DENY (fails closed; live updates silently
stop), a GET-less path yields 405 → fails open.

## 4. API-04 — Edge allow-list — CONFIRMED numbers, NARROWED "unintended"

Own parse (`95-verify-2-edge-sim.py`): 172 admission rules (lines 2-173), family rule line 177,
47 resets, 2 PUT-only rules (208-209); 440 paths / 572 operations in
`backend/src/main/resources/api/openapi.json`; contract parsed from `ExternalContractTest.java`
(234 unique; one duplicate literal: `DELETE …/crew/{crewId}/slim`).

- Gate 1 (`$uri` only, 404): **259 pass**. Gate 2 (read-only families: GET/HEAD only unless the
  URI is reset, lines 176-231): **11 refused 405**, all on frozen paths (e.g. `PUT/DELETE
  /missions/{id}`, `PUT /settings/{key}`). **248 reach the backend = 234 frozen + 14.** 0 frozen
  operations blocked. So "by `$uri` only" holds for admission; the method gate exists but resets are
  per path, not per (path, method) — that is how e.g. `DELETE /hangar/ships` rides on the reset for
  frozen `POST /hangar/ships` (line 179).
- Prefix rules: line 2 `^/api/v1/terms/`, line 3 `^/api/v1/me/` — CONFIRMED.
- The 14 against app **v0.3.1**:

| Operation | Admitting rule | App v0.3.1 | Evidence (basetool-android @v0.3.1) |
|---|---|---|---|
| DELETE /hangar/ships | 76 + reset 179 | **used** (clearHangar) | `HangarRepository.kt:355` |
| DELETE /personal-blueprints | 71 | **used** (bulk delete) | `PersonalBlueprintRepository.kt:626` |
| DELETE /refinery-orders/{id} | 59 | **used** | `RefineryRepository.kt:819` |
| PUT /refinery-orders/{id} | 59 | **used** | `RefineryRepository.kt:811-812` |
| PATCH /bank/holders/{id} | 106 | **used** | `BankStaffRepository.kt:299-301` |
| POST /bank/accounts | 97 | **used** | `BankStaffRepository.kt:239-240` |
| POST /bank/holders | 104 | **used** | `BankStaffRepository.kt:285-286` |
| POST /orders | 39 + reset 185 | **used** | `JobOrderRepository.kt:840` |
| GET /materials/matrix | 155 | **used** | `MaterialCatalogRepository.kt:353-354` |
| PUT /missions/{id}/participants/{pid}/slim | 11 + reset 194 | **used** | `MissionRepository.kt:1020-1021, 1052-1053` |
| GET /hangar/ships | 76 | unused (reads `/my-ships`, `:284`) | backend `HANGAR_READ`, all users' ships (`HangarController.java:114-115`) |
| GET /material-requests/{id} | 164 | unused (PUT only, `MaterialBoardRepository.kt:502`) | backend `KRT_MEMBER` (`MaterialRequestController.java:60,113`) |
| GET /me/layout | **3 (prefix)** | unused (0 refs) | same resolvers as frozen `/me/*` |
| POST /job-types | 127 | unused (GET only, `MissionRepository.kt:1270`, `MissionTimelineRepository.kt:455-456`) | backend `ADMIN` (`JobTypeController.java:92-93`) |

  Two of the claim's three examples (`DELETE /hangar/ships`, `POST /bank/accounts`) are app-used,
  so "unintended" is **refuted for them**. The real defect for the 10 is that the frozen contract
  omits operations the installed app calls. The guard cannot see it: `theFrozenSetIsReachableThroughTheEdge`
  checks frozen **paths** only, ignores methods and the 405 gate, and never checks the reverse
  direction (`ExternalContractTest.java:2665-2689, 2804-2810`).
- REQ-API-012: "Neither endpoint is on the API vhost … Offering them to the Android app is an
  allow-list change" (`docs/specs/api-conventions.md:961-963`) — contradicted by rule 3 for
  `/me/layout` (the `/users/search*/references` twins are *not* admitted: exact rules 87, 110).
  Spec drift, no data gain.
- POST /api/v1/operations: documented (`openapi.json` `/api/v1/operations` = get, post); no rule
  admits the bare path (rules 15, 21-24, 126 are sub-paths) → edge 404; app v0.3.1 calls it from
  the operation form (`OperationFormViewModel.kt:154` → `OperationRepository.kt:264-277`); it is not
  in the frozen set. CONFIRMED.
- Severity: every reachable operation stays backend-gated; the 4 unused ones are surface only.

## 5. FE-02 — direct `webClient` injection — CONFIRMED

Exactly 11 controllers inject `WebClient webClient` (grep `private final WebClient`):
AdminPersonalBlueprintsPage, AdminP4kImportPage, AuditReportProxy, BankReportProxy, DataExportProxy,
HangarImportProxy, HangarDeleteAllProxy, InventoryDeleteAllProxy, JobOrderHandoverReportProxy,
OrgUnitBankProxy, PersonalBlueprintImportProxy (NotificationPageController uses `sseWebClient`).
Sampled: `HangarImportProxyController.java:131-151`, `DataExportProxyController.java:118-148`,
`BankReportProxyController.java:104-136` — identical shape: `WebClientResponseException` →
`ResponseStatusException(status, e.getMessage())` (status kept, backend RFC 7807 `code` replaced by
`GlobalExceptionHandler.handleResponseStatus`'s generic code, `:415-454`); any other exception,
including `ClientAuthorizationException`, → `ResponseStatusException(500)`; no call to
`countBackendError`. `BackendApiClient` would instead map it via `isReauthSignal` →
`ReauthenticationRequiredException` → 401 + `X-Reauthenticate` (`BackendApiClient.java:468-477`;
`GlobalExceptionHandler.java:154-176`).

If it escaped: **no rescue** — `GlobalExceptionHandler` has no handler for it; the `Exception.class`
catch-all renders 500 and never calls `isReauthSignal` (`:482-513`).

Narrowing: the terms gate runs first and calls the backend through `BackendApiClient` whenever its
cached verdict is older than 60 s (`TermsAcceptanceGateFilter.java:95, 288-306`), answering a dead
token with 401 + `X-Reauthenticate` (`:114-123, 159-178`). The 500 path therefore needs a verdict
checked in the last 60 s. The calls are still observed: the `webClient` carries the
`observationRegistry` (`WebClientConfig.java:476`) and `http_client_requests_seconds_count` is
exported for the frontend (`monitoring/prometheus/alerts/apps.yml:179-182`); they are missing from
`basetool_backend_client_errors_total` and its alert (`business.yml:681`).

## 6. JAVA-14 — username hash tags — CONFIRMED, count understated

Sites exact: `BackendRoleSyncFilter.java:146-151` (`String.format("u-%08x", name.hashCode())`),
`JobOrderPageController.java:1113-1118`, `JobOrderWriteController.java:1433-1438`,
`RefineryOrderPageController.java:1004-1007, 1024-1026` (`Integer.toHexString(Objects.hashCode(...))`).
`principal.getName()` is the username: `user-name-attribute: preferred_username`
(`frontend/src/main/resources/application.yml:77`). `maskPrincipal` feeds 10 log statements
(`BackendRoleSyncFilter.java:181, 351, 358, 374, 401, 413-414, 431-432, 440, 454, 458`), so 14
statements tag users this way. Root level is Boot's default INFO (no override,
`application.yml:163-169`; `application-prod.yml` sets none), so 6 emit in prod: warn 355-358,
info 371-374, warn 410-414, info 429-432, error 458, info `RefineryOrderPageController:1025-1026`.
Formats differ (`%08x` pads, `toHexString` does not), so one user can carry two tags.
32-bit, unsalted, deterministic → reversible by hashing the member roster. REQ-OBS-004 names
`preferred_username` and prescribes the `sub` UUID instead (`docs/specs/observability.md:526-530`).
`PiiMasker` only matches JWTs, e-mails and token keywords (`logging-support/.../PiiMasker.java:32-35,
50-51`), so the tag passes. Severity: Low (internal logs, readers already hold the roster).

## 7. FE-12 — ParallelPageLoader and the locale — CONFIRMED mechanism, impact ≈ none today

`ParallelPageLoader.java:66-115` captures and restores six contexts; no `LocaleContextHolder`.
`UserLocaleRelayFilter.java:49-62` reads only `LocaleContextHolder.getLocaleContext()` and adds no
`Accept-Language` when it is null. Used by exactly 8 controllers (JobOrderPage, InventoryPage,
HangarPage, RefineryOrderPage, OrgUnitBankPage, OperationPage, MissionPage, AdminMissionDataPage).
No test covers it (`*ParallelPageLoader*` tests: 0 locale hits).

Backend locale dependence: RFC 7807 title/detail texts only — `GlobalExceptionHandler.java:143-144,
169-170`, `BasetoolErrorController.java:77`, six filters via `request.getLocale()` (ActingMember 353,
RateLimiting 414, IdentityProviderUnavailable 159, PendingApprovalAccess 302, SubjectRateLimiting
357, TermsAcceptanceAccess 198). All PDF reports are pinned to `Locale.GERMAN` (e.g.
`BankStatementReportService.java:334`). `spring.messages.fallback-to-system-locale: false`
(backend `application.yml:36-37`); the base bundle is German. The language without the header is
the container JVM default locale — **UNKNOWN** (settle by reading the image's `LANG`/`user.language`).
The only place the frontend renders a backend `detail` is `RefineryOrderPageController.java:414`, in
the import POST handler (`:377`), outside every `loadAsync` (`:329-342`). Severity: none (latent
inconsistency).

## 8. FE-08 / XC-16 — session type allow-list — CONFIRMED with a larger blast radius

Prefix: `ALLOWED_PREFIXES = ["org.springframework.security.", "de.greluc.krt.profit.basetool.frontend.model."]`
(`SessionTypeAllowList.java:86-87`), applied as Jackson name-prefix matchers (`:193-195`). ENFORCE
returns `DENIED` for anything else (`:314-322`); default mode is REPORT when unset (`:131-144`).
Refusal handling: `FaultTolerantSessionSerializer.java:106-118` turns the read failure into an
`UnreadableSessionValue`; `SessionAttributeDiagnosticMapper.java:132-158` sets that one attribute to
`null`, queues a repair, builds the session normally — no 500, and the security context (a separate
attribute) survives. Only a missing required hash key yields "no session" (`:152-157`).

**Nuance the claim misses:** the dropped unit is the whole attribute. Spring stores *all* pending
flash maps in one attribute, `SessionFlashMapManager.FLASH_MAPS` (spring-webmvc 7.0.9
`SessionFlashMapManager.java:38, 48, 56`). One refused form class therefore drops every pending
flash map of that session: form, BindingResult and success/error toasts all vanish, silently.
Exposure: all 30 `*Form` classes live in `frontend/model/form`; 29 `addFlashAttribute` sites flash a
form or BindingResult. Production `enforce` is **documented, not observed**
(`docs/specs/security-and-access.md:3438`, dated 2026-09-25); the repo default is `report`
(`frontend/src/main/resources/application.yml:103`, `quadlet/env.d/frontend.env.tmpl:16`,
`docker-compose.yml:303`); E2E runs `enforce` (`docker-compose.e2e.yml:51`). Guards: the
`SessionTypeOutsideAllowList` alert on `basetool_session_type_refused_total`
(`monitoring/prometheus/alerts/business.yml:567`) and E2E flows that assert flashed content.
Refactor implication: per-domain form/DTO packages must extend `ALLOWED_PREFIXES` by explicit
per-package entries; widening to `…frontend.` would re-admit config/service classes as
deserialization gadgets (APPSEC-05, ADR-0206).
