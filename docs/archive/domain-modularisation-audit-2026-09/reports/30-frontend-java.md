# 30 — Frontend Java: structure by domain and the seam to the backend

Audit agent `30-frontend-java`, 2026-09-29. Read-only. Worktree
`$REPO` at `95e945326`. All paths below
are relative to that worktree unless marked *(vault)* (`$MAIN_CHECKOUT-knowledge\Basetool
Knowledge`) or *(gradle cache)* (the Spring sources jars of the exact versions the build resolves,
`~/.gradle/caches/modules-2/files-2.1/org.springframework/spring-web|spring-webflux/7.0.9/…-sources.jar`).
Counts come from the scripts listed in appendix A14 (all under the scratchpad, prefix `30-frontend-java-`).
The owner update of 2026-09-29 (backend `/api/v1` may be re-cut per domain; Android adaptable; exchange
API frozen) is taken into account in FE-04, FE-05 and FE-15.

## 1. Summary — the ten most important conclusions

1. **The frontend is package-by-layer and its `controller` package is the de-facto domain container:**
   103 files, 33,630 of 66,858 lines (50 %), 70 `@Controller` + 26 `@RestController` + 7 helpers, none
   of them grouped by domain (A1, A3; `30-frontend-java-scan.py`).
2. **"BackendApiClient is the single seam" is a convention, not a gate:** 14 classes inject a
   `WebClient`; 11 controllers bypass `BackendApiClient`'s error mapping, so a lost refresh token there
   becomes a 500 instead of the reauth flow, and the deprecated-call guard cannot see them (FE-02;
   `ArchitectureTest.java` has no such rule; `HangarImportProxyController.java:96-151`).
3. **Backend URIs are still hand-built strings:** of 624 `BackendApiClient` HTTP call sites, 356 are
   built by concatenation, 148 are fixed literals, and only 41 (6.6 %) go through URI-template
   variables (26) or a `UriComponentsBuilder` (15); FE-SIMP-02's typed-client half was never done, four handlers
   in three controllers still bind a browser-supplied id as `String` where the backend takes a `UUID`,
   and one sort spec is `URLEncoder`-encoded around `RelayParams` — REQ-SEC-051 gaps (FE-03, A6).
4. **Per-domain typed clients keep the single Resilience4j pass** when they are built on the one
   `webClient` bean — proven from the Spring 7.0.9 sources (`WebClientAdapter.newRequest` calls
   `this.webClient.method(…)`), so the July audit's reason for rejecting a split does not apply to
   them; the naive Boot/Framework "HTTP service group" path would fragment it and must be banned (FE-04).
5. **A per-domain backend re-cut can ship frontend and backend in one release,** because the host
   applies a release in one restart window with `frontend.container` `Requires=backend` and rolls back
   all pins together (`docs/deployment.md:566-619`, `quadlet/systemd/frontend.container:3-4`); two
   gaps remain: no gate fails when a frontend call names a path the backend no longer serves
   (`DeprecatedBackendEndpointCallGuardTest` checks only *deprecated* operations, FE-05), and `:stable`
   is re-tagged per module by five matrix jobs while `deploy.sh` resolves each tag independently, so
   a tick during — or a failure in — a promotion can deploy a mixed release (FE-16;
   `.github/workflows/promote.yml:170-202`, `scripts/deploy.sh:929-938`).
6. **Hand-mirrored DTOs stay the better choice over generated ones:** the generator already runs
   (openapi-generator 7.25.0, models only, test source set) and its output is 420 mutable classes,
   6 of them on Jackson-2 `databind` annotations, 1,254 lines for `MissionDto` versus a 72-line mirror
   record; the mirrors also act as an output allow-list for relays, which 72 raw-`Map` responses and
   78 raw-`Map` request bodies bypass today (FE-06, FE-07).
7. **The session allow-list is keyed on the package `frontend.model.`** (`SessionTypeAllowList.java:86-87`,
   production on `enforce`), so a package-by-domain move of the ≈11 flashed types (≈20 with nested
   members) would silently drop flash attributes unless the list moves in the same change; the move is
   also a chance to narrow the list from 325 classes to the flashed ones (FE-08).
8. **Templates hold 172 `T(de.greluc…frontend.support.Roles)` references in 22 files, every one inside
   `sec:authorize`, and no test resolves them statically** — `Roles` must not move without one (FE-09).
9. **Cross-domain coupling in the frontend is composition, not entanglement:** 0 cross-domain
   controller→controller edges, 30 domain controllers reading other domains' DTOs, 173 cross-domain
   class edges forming 7 two-way domain pairs, and 11 kernel→domain edges from the layout model and
   the access gates (FE-10; `jdeps-frontend.txt`).
10. **The authorization gate is checked per class, not per handler, and no route/gate snapshot
    exists,** so moving handlers between controllers during the refactor could drop an inherited
    class-level `@PreAuthorize` with every test green (FE-11; `ArchitectureTest.java:95-111`); and
    `ParallelPageLoader` propagates 3 of the 4 relay ThreadLocals, dropping the user locale (FE-12).

## 2. Findings

### FE-01 — The frontend is organised by technical layer; one package holds half the code

**Evidence.** 554 main classes, 66,858 lines (34,716 non-blank non-comment) in 16 packages (A1).
`controller` has 103 files and 33,630 lines; `model.dto` 292 files (279 records, 12 enums, 1
annotation), `model.form` 30 (17 records, 13 classes), `config` 62, `service` 11, `support` 13,
`websocket` 9, `logging` 14. Every one of the 62 `config` classes is cross-cutting (A2: 20 security,
12 session, 8 web/MVC, 7 observability, 6 backend hop, 6 layout model, 3 live sync). Domain-specific
view shaping lives in the `controller` package, not in `service` as arc42 §5.3 says
(`MissionDetailModelBuilder`, `BankDashboardViewAssembler`, `BankBalanceChart`, `BankSparkline`,
`BankAccountDetailSupport`, `BankAccountOrder`, `PlanetColorResolver`). The domain assignment of every
class (A3, A4) needed no guesswork except `AdminMissionDataPageController` (job types, frequency types
**and** squadrons) and `ShipDataPageController` (catalogue plus the hangar-wide fitted reset), both
placed in `catalogue`.

**Impact.** A domain's frontend lives in five places (a controller or three, DTOs, forms, templates,
JS); nothing but naming groups them, and no gate can talk about "the mission module" of the frontend.

**Proposed change.** Package-by-domain with a small kernel — see FE-15 for the target and the move
order. The move itself is route-neutral and mechanical; its risks are the string-keyed couplings
listed in FE-15 (session allow-list, three contract tests, `T(...)`, `PUBLIC_BY_DESIGN`).

**Pros.** One place per domain; gates become expressible (FE-15 R1–R6); the frontend mirrors a re-cut
backend 1:1. **Cons.** Churn in 368 test files that live in the same packages (204 in `controller`);
review load. **Risks and regressions (security).** Authorization, CSRF, CSP and the layout advices are
selected by annotation or URL, never by package (`@ControllerAdvice(annotations = UsesLayoutModel.class)`
on five advices, `GlobalBindingAdvice`/`GlobalExceptionHandler` global; `SecurityConfig.java:177-208`),
so a pure move changes none of them; the exceptions are FE-08 (session) and FE-09 (templates). Guards:
FE-11 route/gate snapshot must stay byte-identical. **Effort.** M for the move, L with test churn.
**Prerequisites.** FE-11 and FE-08 guards first; arc42 §5.3 table and `frontend/CLAUDE.md` updated in
the same PR.

### FE-02 — The single seam is not gated, and eleven controllers bypass its error mapping

**Evidence.** `WebClient` is injected in 14 classes (A9): `BackendApiClient` (`webClient`,
`termsDocumentClient`), the SSE relay `NotificationPageController` (`sseWebClient`), the live-sync
probe `LiveSyncSubscriptionAuthorizer` (`liveSyncAuthWebClient`), and **11 controllers** on the main
`webClient`: `AdminP4kImportPageController`, `AdminPersonalBlueprintsPageController`,
`AuditReportProxyController`, `BankReportProxyController`, `DataExportProxyController`,
`HangarDeleteAllProxyController`, `HangarImportProxyController`, `InventoryDeleteAllProxyController`,
`JobOrderHandoverReportProxyController`, `OrgUnitBankProxyController`,
`PersonalBlueprintImportProxyController`. Their reasons: multipart upload (4 controllers; the P4K one
also reads a `Flux`), binary download with a custom header or a per-request timeout (5), and two
body-less DELETEs with no visible reason (`HangarDeleteAllProxyController.java:57`,
`InventoryDeleteAllProxyController.java:55`).
The frontend `ArchitectureTest` has 7 tests and none restricts `WebClient`
(`frontend/src/test/java/…/ArchitectureTest.java:54-186`); only `TermsDocumentClientUsageTest` guards
one field, by name. arc42 §4.1 nevertheless says "ArchUnit tests enforce the direction so the seam
cannot quietly grow a second one" (`docs/arc42/04-solution-strategy.md:14`).

What the bypass loses, all verified in code:
- **Reauth mapping.** `BackendApiClient.handleException` turns a `ClientAuthorizationException` into
  `ReauthenticationRequiredException` (`BackendApiClient.java:468-477`), which
  `GlobalExceptionHandler` answers with `401` + `X-Reauthenticate` or a login redirect
  (`GlobalExceptionHandler.java:154-177`). None of the 11 controllers checks the reauth signal (grep
  count 0 each); they catch `Exception` and throw `ResponseStatusException(500)`, e.g.
  `HangarImportProxyController.java:147-150`.
- **Problem-code relay.** They rethrow `new ResponseStatusException(e.getStatusCode(), …)`, which drops
  the RFC 7807 `code`, so `krt-fetch.js:321-326` cannot tell `OPTIMISTIC_LOCK` from another 409 there.
- **Metrics.** `basetool_backend_client_errors_total{reason,method}` is incremented only in
  `BackendApiClient.countBackendError` (`BackendApiClient.java:114-123`); these 11 are invisible to it
  (REQ-OBS-011).
- **Guards.** `DeprecatedBackendEndpointCallGuardTest` parses only `backendApiClient.<verb>(` calls.
  The frontend still relays the deprecated `POST /api/v1/hangar/import/fleetview`
  (`HangarImportProxyController.java:96-101`; backend `@ApiDeprecation(sunset = "2027-05-14")`,
  `HangarController.java:292-295`) with the guard green; no template or script calls the frontend
  route (grep: only `HangarImportProxyControllerTest.java:134`).

The SSE relay and the live-sync probe are *deliberate* exceptions (no Resilience4j chain, no OAuth2
filter, bearer set by the caller — `WebClientConfig.java:537-579`), so `frontend/CLAUDE.md:228`
("Resilience4j wraps every backend call") is too broad for 2 of the 4 `WebClient` beans.

**Proposed change.** (1) Give the kernel the three missing shapes — `exchangeForEntity(bytes)` with
optional headers and a per-call timeout, `postMultipart(Resource part)`, `getFlux` — all going through
the same `exchange()` mapping; move the 11 controllers onto them (or onto their domain's typed client,
FE-04). (2) ArchUnit rule R3 (FE-15): only the kernel backend package may depend on `WebClient`,
with a named allow-list for the SSE relay and the live-sync probe. (3) Delete the dead deprecated
fleetview route. (4) Correct arc42 §4.1 and `frontend/CLAUDE.md` (FE-14).

**Pros.** Uniform reauth, error codes and metrics; the deprecated-call and existence guards (FE-05) see
every call. **Cons.** Three new kernel methods; the P4K `Flux` list needs a `List` or `Flux` variant.
**Risks and regressions (security).** Multipart must keep the 8 MiB refusal *before* reading
(`HangarImportProxyController.java:115-121`, APPSEC-03) and the streamed `Resource` part; downloads
must keep their attachment headers and the no-retry/extended-timeout behaviour of
`DataExportProxyController.java:112-131`. A write routed through `exchange()` is still not retried
(retry is GET/HEAD/OPTIONS/TRACE only, `WebClientConfig.java:333-343`). Guards: the existing
`*ProxyControllerTest` suites plus a new test per migrated controller asserting that a
`ClientAuthorizationException` yields `401 X-Reauthenticate`. **Effort.** M. **Prerequisites.** None;
can precede FE-04.

### FE-03 — Backend URIs are concatenated strings; encoding correctness depends on each call site

**Evidence** (`30-frontend-java-calls.py`, `30-frontend-java-concat-types.py`, A6). 624
`BackendApiClient` HTTP call sites (253 `get`, 134 `post`, 99 `put`, 84 `delete`, 23 `patch`, 30
`getCached`, 1 anonymous terms read): 350 pure concatenation, 6 through a concatenated local, 16 mix a
concatenated prefix with template variables, 148 plain literals, 25 constants, 30 cached catalogues,
26 URI templates with variables, 15 `UriComponentsBuilder` (6 inline, 9 via a local), 22 helper or
other. Only the `get` overload takes URI variables (`BackendApiClient.java:158-165`); `post`, `put`,
`patch`, `delete` take a finished string (`:320-377`), so every write with a variable path is
concatenated by construction. A line-based grep gives 348 lines with `"/api/v1/…" +`. The September
figure was ~290 (FE-SIMP-02); the method behind it is not recorded, so "grew" versus "counted
differently" is UNKNOWN — what is certain is that the number did not fall. FE-SIMP-02's first half
(one private `exchange`, `BackendApiClient.java:395-407`) shipped in #2010; the second half ("later
`@HttpExchange` interfaces") did not (vault `80 Plans/Improvement Audit 2026-09.md:135,271`).

Operand types concatenated into URIs: 366 `UUID`, 23 number/boolean, 36 `String`, 9 unresolved,
13 other expressions or parser artefacts. Most `String` operands are internal (`AdminUexPageController`
path constants, `ConnectedAppsRelayController` ids validated by `CLIENT_ID`,
`ConnectedAppsRelayController.java:57`). Four handlers in three controllers bind a
**browser-supplied id as `String`** where the backend declares `UUID`, against REQ-SEC-051:
`HomeController.java:192-194` and `:213-215` (`@RequestParam String id` into
`/api/v1/users/me/read-announcement/`; backend `@PathVariable UUID announcementId`,
`UserController.java:621-624`); `PromotionPageController.java:266,271` → `:551-554`
(`@RequestParam String userId` into `/api/v1/promotion/eligibility/user/`); and
`AdminDefaultBlueprintsPageController.java:205-208` (`@PathVariable String id` wrapped in
`URLEncoder.encode` — form encoding in a path segment, the wrapper shape the vault records as removed
on 2026-09-04, *(vault)* `10 Systems/Frontend.md:736-739`; backend `@PathVariable UUID id`,
`AdminDefaultBlueprintController.java:110-118`). `PersonalInventoryPageController.java:380-384`
appends `URLEncoder.encode(sort)` to a string that is then used as a URI template, so a `,` arrives
double-encoded, and it bypasses `RelayParams.sortSpecOrNull` (`RelayParams.java:93`, used by one
controller); no template or script sends `sort` there (grep), so only a hand-edited URL reaches it.

**Impact.** Encoding and path shape are re-decided at 624 sites; the vault records three spellings of
`UriComponentsBuilder` with opposite encoding behaviour and eight CodeQL `java/ssrf` findings in this
layer (*(vault)* `10 Systems/Frontend.md:717-800`). Tests pin URL strings rather than behaviour
(A13: 665 `eq("/api/v1…")` matchers).

**Proposed change.** Short term: bind the four ids as `UUID`, drop the `URLEncoder` wrapper, route
the sort through `RelayParams.sortSpecOrNull` and a template variable (S). Structural: FE-04 typed clients, where
`@PathVariable UUID` and `@RequestParam` become URI variables by construction (verified: query params
are appended as `{queryParam-…}` variables, `HttpRequestValues.java:597-607` *(gradle cache)*).
Add template overloads for the write verbs in the meantime.

**Pros.** Closes the FE-SEC-01 defect class structurally; the `uri` observation tag becomes the
template. **Cons.** None material. **Risks (security).** Today a crafted id can append query
parameters or path segments to a relayed call made with the caller's own token (all four handlers are
gated: authenticated, `ADMIN_OR_OFFICER`, `ADMIN`); the backend still authorizes, so there is no
privilege gain, but the relay reaches paths the page never meant to call. The fixes turn a malformed
id into a `400` before any backend call. Guard: MockMvc test per handler with `a?b#c/../x` expecting
`400` and zero `BackendApiClient` interactions. **Effort.** S (ids),
L (FE-04). **Prerequisites.** None.

### FE-04 — Per-domain typed backend clients over the one filter chain: evaluation

**Question.** Does a split of the call surface into per-domain typed clients keep the single
Resilience4j pass (ADR-0032) and thereby remove the July audit's reason for rejecting it (*(vault)*
`10 Systems/Backend.md:312-313`: "splitting it fragments the one Resilience4j pass")?

**Where resilience actually lives.** Not in `BackendApiClient`: since ADR-0032 (2026-06-21, before the
July audit of 2026-07-11) it is `WebClientConfig#resilienceFilter`, a filter on the `webClient` bean
(bulkhead → time limiter → retry for idempotent verbs → circuit breaker, 5xx only per ADR-0077;
`WebClientConfig.java:311-345`, registered at `:483-485`). The OAuth2 bearer relay
(`ServletOAuth2AuthorizedClientExchangeFilterFunction` over `SingleFlightAuthorizedClientManager`,
`:463-466`), the correlation-id, org-unit, locale and client-IP relays (`:478-481`), the call logging
(`:482`), the CBOR/JSON `Accept` list (`:486`), TLS trust and the HTTP/2 pool (`:185-309`) are all
properties of that bean. What `BackendApiClient` holds on top is the **error mapping** (Problem+JSON
parse, `BackendServiceException`, 503/504 codes, access-gate log levels, reauth,
`basetool_backend_client_errors_total`), the **catalogue cache** (`@Cacheable getCached`,
`BackendApiClient.java:182-212`) and the **anonymous terms client** (`:72-93`).

**Two ways to build typed clients, both on the same bean.**

| | T1 — thin per-domain class over `BackendApiClient` | T2 — Spring HTTP interface over the `webClient` bean |
|---|---|---|
| Shape | `MissionBackendClient` class, methods like `MissionDto get(UUID id)` delegating to `backendApiClient.get("/api/v1/missions/{id}", MissionDto.class, id)` | `@HttpExchange("/api/v1/missions") interface MissionBackendClient { @GetExchange("/{id}") MissionDto get(@PathVariable UUID id); }` created by `HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))` |
| Resilience pass | Unchanged — same `exchange()` | Unchanged — `WebClientAdapter.newRequest` calls `this.webClient.method(httpMethod)` on the injected instance, then `.retrieve()` *(gradle cache: `WebClientAdapter.java`, spring-webflux 7.0.9)* |
| Error mapping | Unchanged | Needs `HttpServiceProxyFactory.Builder.exchangeAdapterDecorator(…)` with an `HttpExchangeAdapterDecorator` subclass (both exist since 7.0: `HttpServiceProxyFactory.java:127,178`, `HttpExchangeAdapterDecorator.java`) that wraps each sync exchange in the same try/catch as `BackendApiClient.exchange`, reusing an extracted `BackendErrorMapper` |
| Encoding | Needs template overloads for write verbs (new) | By construction: path and query values become URI variables (`HttpRequestValues.java:551-607`) |
| Blocking | `.block()` | `.block()` unless `blockTimeout` is set (`AbstractReactorHttpExchangeAdapter.java`, default `null`); the filter's `TimeLimiter` (5 s) bounds both |
| Caching | `getCached` stays | Keep cached reads on `BackendApiClient.getCached`; whether `@Cacheable` applies to an HTTP-interface proxy bean is **to verify in the docs** — avoid it |
| Multipart | New kernel method (FE-02) | `@RequestPart` with `Resource`, `MultipartFile`, `HttpEntity` or `Publisher` (`RequestPartArgumentResolver.java:41-47`); whether a `MultipartFile` part is streamed rather than buffered is **to verify in the docs** — keep the explicit streamed `Resource` |
| Streaming / SSE | Not applicable | `Flux<T>` via `exchangeForBodyFlux`; but the SSE relay must stay on `sseWebClient` (no resilience, no OAuth2 filter, REQ-SEC-012) |
| Per-call headers / timeout | Kernel method parameters | `@RequestHeader`; `@RequestAttribute` becomes a `WebClient` request attribute (`supportsRequestAttributes() == true`) that a filter could read for a per-call timeout — **to verify in the docs** |
| Test style | Controllers mock the typed class | Controllers mock the interface; one `MockWebServer` test per interface pins paths |

**Verdict.** Yes: both keep one breaker window, one retry budget, one bulkhead, because every call still
enters the same `webClient` bean. The July rejection holds only for a split that builds new
`WebClient`s — and that is exactly what the Framework's **HTTP service group registry** does by
default: `WebClientHttpServiceGroupAdapter.createClientBuilder()` returns a fresh `WebClient.builder()`
per group *(gradle cache: `WebClientHttpServiceGroupAdapter.java`)*, i.e. no filters, no pool, no TLS
trust, no bearer. `@ImportHttpServices` and `HttpServiceGroupConfigurer` must therefore not be used
in the frontend (no Boot `spring-boot-webclient` module is on the frontend classpath either — the
Gradle cache holds none, A12; Boot's own HTTP-service auto-configuration for 4.1 is **to verify in the
docs**). The rejection's legitimate core — keep **one** error mapping, **one** metric, **one** cache —
is kept by T1 by delegation and by T2 through the shared mapper in the decorator.

**Semantics that must hold, and how.**
- *Error mapping and 409.* Typed clients throw the same `BackendServiceException`; controllers keep
  `BackendErrorResponses.relay()` (112 uses in 29 files) and `propagateBackendError` (36 in 13), which
  relay status + `code` verbatim (`BackendErrorResponses.java:95-108`), so `krt-fetch.js`'s
  `OPTIMISTIC_LOCK`/`PESSIMISTIC_LOCK` reload-vs-toast split and the backend's "explicit 409 branch"
  rule (`backend/CLAUDE.md:45`) are unchanged. A client must never translate a 409 into a default
  value; guard: parameterised test per client that a 409 with `code` reaches the controller intact.
- *Context propagation.* The relays read ThreadLocals inside filters of the same bean, so nothing
  changes; calls made inside `ParallelPageLoader` get whatever it copies (see FE-12).
- *CSRF.* Not applicable to the hop (bearer, no cookies). Forbid `@CookieValue` parameters on client
  interfaces so the session cookie can never be forwarded.
- *SSRF / token exfiltration.* A `java.net.URI` parameter on an HTTP-interface method overrides the
  whole request URL (`UrlArgumentResolver.java`: `requestValues.setUri((URI) argument)`), and the
  OAuth2 filter would then send the member's bearer to that host. Forbid `URI` and
  `UriBuilderFactory` parameters; require every `@HttpExchange` path to start with `/api/`.
- *ETag / If-None-Match.* Not used on the hop today: the backend registers `ShallowEtagHeaderFilter`
  on `/*` (`backend/…/config/EtagConfig.java`), the frontend never sends `If-None-Match` (grep: 0);
  typed clients change nothing here.
- *Observability.* `ObservationPrivacyFilter` already collapses UUID and numeric segments in the
  `uri` tag (`config/ObservationPrivacyFilter.java:38-46`); with URI templates the tag is bounded by
  construction. No alert or dashboard groups `http_client_requests` by `uri` (A12).

**Recommendation.** Define one client interface per domain (`frontend.<domain>.client`), start each
with T1 (no new framework surface, zero change on the error path), and switch a domain to T2 when its
backend part is re-cut (FE-05), reusing the same interface so controllers and their tests do not
change twice. Record the decision in a new ADR amending arc42 §4.1 ("one class" → "one filter chain,
one error mapper, typed clients per domain").

**Pros.** Encoding by construction; URLs of a domain in one file; controller tests mock domain methods
instead of 1,758 URL-keyed `when`/`verify` lines in 194 files (A13); an exact contract test becomes
possible (reflection over `@HttpExchange`, FE-05). **Cons.** T2 adds a proxy layer and a decorator on
the error path; two call styles coexist during the migration. **Risks (security).** Fragmentation
(guard R4: every client bean is created from the `webClient` bean — test that sends one call per
client to `MockWebServer` and asserts `Authorization`, `X-Correlation-Id`, `X-Active-Org-Unit-Id`,
`Accept-Language` and that an open breaker short-circuits); URL override (guard R5: ArchUnit on client
interfaces — no `URI`/`UriBuilderFactory`/`@CookieValue` parameters, no absolute paths); error-mapping
drift (run `BackendApiClientProblemJsonTest`, `…WriteProblemJsonTest`, `…ResilienceTest`,
`…HappyPathTest` against the T2 path too). **Effort.** L in total; S–M per domain. **Prerequisites.**
FE-02 (kernel shapes), extracted `BackendErrorMapper`, new ADR; `TermsDocumentClientUsageTest`
unchanged (the anonymous client stays in the kernel).

### FE-05 — A per-domain backend re-cut: what the frontend changes, and how to ship it in step

**How frontend and backend ship today (verified).** One commit builds all images (ADR-0137); the
production promotion re-tags each of `backend frontend ingest config keycloak-spi` to `:stable` in
its own matrix job (`.github/workflows/promote.yml:170-202`, `fail-fast: true`), while the `:testing`
sync resolves all five digests first and then re-tags (`:287-321`); on the host `deploy.sh` resolves
each `:stable` independently (`scripts/deploy.sh:929-938`), compares a five-field marker and
applies a release in **one restart window** — one `systemctl --user stop` of every re-defined unit,
then `start` in dependency order, one health gate — and on failure restores the previous config,
units, pins and provider JAR **together** (`docs/deployment.md:566-619`). `frontend.container` has
`After=` and `Requires=backend.service` (`quadlet/systemd/frontend.container:3-4`) and is a single
unit, so a frontend never serves traffic against a backend from a *different apply* — but whether the
five digests of one apply are one release is not checked (FE-16). The frontend
reaches the backend on the internal network, `BACKEND_URL=https://backend:11261`
(`quadlet/env.d/frontend.env.tmpl:17`), not through the `api.*` edge. This is the "atomic deploy"
REQ-API-001's carve-out and ADR-0136 rely on (`docs/adr/0136-…:21-24`).

**What the frontend touches per domain** (A7; counts are `/api/v1` literals in main, then frontend
test and E2E references):

| Backend domain | own-domain | other domains | kernel | tests | E2E | kernel touch points that must move with it |
|---|---:|---:|---:|---:|---:|---|
| catalogue | 70 | 2 | 16 | 106 | 9 | 16 `CachedCatalog` URIs |
| bank | 73 | 4 | 2 | 88 | 46 | `LiveSyncTopicClass` probes `BANK_ACCOUNT` (bank + org-unit bank) |
| identity | 52 | 23 | 4 | 158 | 12 | `BackendRoleSyncFilter.java:271,352`, `TermsAcceptanceGateFilter.java:98`, `BackendApiClient.java:81` (anonymous terms), blackbox probe `api.*/api/v1/terms/status` (`monitoring/prometheus/prometheus.yml:136,338,384`) |
| mission | 68 | 4 | 1 | 158 | 7 | `LiveSyncTopicClass.MISSION` probe |
| joborder | 52 | 3 | 2 | 113 | 25 | `CachedCatalog.ITEM_CATALOG`, `LiveSyncTopicClass.ORDER`; frontend-origin mirror route `@RequestMapping("/api/v1/orders")` (`JobOrderHandoverReportProxyController.java:50`) |
| orgunit | 26 | 23 | 5 | 50 | 11 | 5 `CachedCatalog` URIs; layout via `/api/v1/me/layout` (`LayoutContextLoader.java:64`) |
| inventory | 36 | 1 | 0 | 48 | 20 | — |
| blueprint | 26 | 1 | 0 | 24 | 0 | — |
| promotion | 23 | 0 | 0 | 83 | 0 | — |
| materialexchange | 20 | 1 | 0 | 9 | 0 | — |
| operation | 17 | 1 | 1 | 35 | 2 | `LiveSyncTopicClass.OPERATION` probe |
| refinery | 14 | 1 | 1 | 52 | 15 | `LiveSyncTopicClass.REFINERY_ORDER` probe |
| settings | 12 | 2 | 2 | 11 | 1 | 2 `CachedCatalog` settings |
| hangar | 13 | 2 | 0 | 28 | 1 | — |
| personalinventory, dashboard, exchange, orgchart, audit, leadership, notification | 11, 7, 5, 5, 4, 3, 2 | ≤1 | 0 | 7, 3, 35, 23, 4, 6, 6 | ≤5 | — |

Per domain the frontend change is: its typed client (or its call sites), the kernel touch points in
the last column, its DTO mirrors when resource shapes change (plus `ALIASES`, `FRONTEND_ONLY`,
`KNOWN_DRIFT` in `GeneratedDtoAgreementTest.java:55-109`), the generated `ApiDto<'…'>` names the JS
type check reads (`:frontend:typecheckJs` fails loudly on a renamed schema), its unit tests, its E2E
seeders, and the `LiveSyncTopicClass` probe templates (17 topic classes). Identity and orgunit are
called from 11 and 5 other domains' classes respectively — re-cut them last or behind stable
"reference" reads.

**The gap.** Nothing fails today when a frontend call names a path the backend no longer serves:
unit tests mock the old string and stay green; `DeprecatedBackendEndpointCallGuardTest` matches only
*deprecated* operations and skips calls whose URI is a constant, variable or builder
(`…/contract/DeprecatedBackendEndpointCallGuardTest.java:41-57`, floor 150 resolved calls); E2E covers
only its flows. For live sync the gap is security-relevant: a probe answered `404` denies, but any
other non-2xx (`400`, `405`, `5xx`) **fails open** for non-presence topic classes
(`LiveSyncSubscriptionAuthorizer.java:91-93,236-276`), so a probe left pointing at a re-shaped path can
admit subscribers to section-change pings.

**Proposed sequence** (per domain, one PR, one release — possible because of the atomic deploy):
1. Add the guards first: **G4** "every frontend backend call matches an existing operation in the
   committed `openapi.json` (verb + template)" — extend the deprecated-call parser with an existence
   check now, and replace it by reflection over `@HttpExchange` once a domain is on T2; **G6** "every
   `LiveSyncTopicClass` probe template matches an existing `GET` operation".
2. In the re-cut PR: backend new paths + `openapi.json` regenerated + the domain's typed client
   switched + kernel touch points + mirrors + tests + E2E, all together. Old backend paths are deleted
   in the same PR **only if** they are outside ADR-0136's contract set, not called by `ingest`, not
   probed by blackbox and not on the `api.*` allow-list; otherwise they stay with
   `@ApiDeprecation(sunset=…)` and `DeprecatedBackendEndpointCallGuardTest` proves the frontend left
   them.
3. Android-consumed operations follow expand/contract: `/api/v2` or a parallel path, deprecation with
   sunset, removal only after the minimum-app-version gate (ADR-0136 "Retirement needs A5"). The NPM
   `api.*` allow-list is not in git (ADR-0136) and changing it is a production write needing the
   owner's explicit approval.
4. Keep **frontend** routes stable during the backend re-cut (route identity, the ADR-0068 precedent):
   a browser tab from release N calls the frontend of N+1 after a deploy; frontend route changes are a
   separate, deliberate step with their own snapshot diff (FE-11).
5. `ingest` relays to the backend over the same internal network; the exchange contract is frozen, so
   backend paths `ingest` calls must keep behaviour byte-identical (owner update).

**Pros.** No two-release dance for the web frontend; each domain's calls move to typed clients in the
same stroke. **Cons.** Large PRs per domain (backend + frontend + tests). **Risks (security).**
Authorization and tenancy move with backend endpoints (backend agents' scope); on the frontend side:
live-sync probes (G6), the anonymous terms read must stay on a `permitAll` backend path and keep
`TermsDocumentClientUsageTest` green, and relays that pass `true`-style anonymous reads no longer
exist (ADR-0159) — keep it so. **Effort.** M per domain for the frontend half. **Prerequisites.** G4,
G6, FE-16; FE-04 client per domain; ADR for the re-cut (backend side).

### FE-06 — DTO mirrors: keep the hand mirrors, make the contract tests exact

**Evidence.** Three gates hold 292 mirrors against the committed `openapi.json`
(489 schemas, 572 operations, 96 tags, 0 `allOf`/`oneOf`/`anyOf`/`discriminator` — A12):
- `FrontendDtoContractTest` (`…/model/dto/FrontendDtoContractTest.java:53-105`): every record
  component must exist in the same-named schema; an enum mirrored as `String` needs
  `@BackendEnumAsString`; a typed enum must contain every backend value; floors >100 records checked,
  frontend-only records < half.
- `GeneratedDtoAgreementTest` (`…/contract/GeneratedDtoAgreementTest.java:45-177`): property-name
  equality against models **generated** from `openapi.json`; 12 `FRONTEND_ONLY`, 18 `ALIASES`,
  2 frozen `KNOWN_DRIFT`, all size-pinned.
- `DtoOpenApiContractTest` (`…/model/dto/DtoOpenApiContractTest.java:57`) — component and enum
  coverage.
The openapi-generator plugin (catalog `openapiGenerator = "7.25.0"`, `gradle/libs.versions.toml:12,16`)
is used **only** for that: `generatorName java`, `library native`, models only, package
`…frontend.contract.model`, output added to the **test** source set (`frontend/build.gradle.kts:23-58`).
Its output: 420 files; generated `MissionDto` is a 1,254-line mutable class versus the 72-line mirror
record; 6 of 420 import Jackson-2 `com.fasterxml.jackson.databind.annotation.JsonDeserialize`
(`frontend/build/generated/openapi/…/MissionDto.java:24,586`), while the app runs Jackson 3
(`tools.jackson`).

**Evaluation — generate per-domain client DTOs vs hand mirrors (arc42 §4.1 trade-off).**

| | Hand mirrors (today) | Generated from `openapi.json` |
|---|---|---|
| Drift | Caught by three tests; a re-cut regenerates `openapi.json` and the tests name every mirror to fix | None by construction |
| Code quality | Records, JetBrains nullness, Lombok, Javadoc (checkstyle-enforced) | Mutable POJOs, no nullness, generated Javadoc; would be excluded from Checkstyle/SpotBugs; record output of the `java` generator is **to verify in the docs** |
| Jackson | Jackson 3 | Jackson-2 `databind` annotations on 6 models, silently ignored by the Jackson 3 codec — **to verify in the docs** how 7.25 targets Jackson 3 |
| Output allow-list | A relay serialises only mirrored fields, so a new backend field (e.g. PII) does not reach the browser until someone mirrors it | Every field the backend adds is forwarded by every relay that returns the DTO |
| Tolerance | Mirrors may be looser (`String` for enums, `@BackendEnumAsString`) | Strict to the spec |
| Review | Mirrors are reviewed like code | Generated code is not reviewed; a generator bump changes 420 files |

**Recommendation.** Keep hand mirrors; keep generation test-only. After the package move, key the
three tests on a marker (e.g. a `@BackendMirror` annotation) instead of the package
`…frontend.model.dto`, which all three hard-code today (FE-15 checklist). Unknown-field strictness:
whether the frontend codec fails on unknown properties is **to verify in the docs** (Jackson 3 +
Spring 7 defaults); the mirrors must stay tolerant because the backend adds fields additively.

**Pros.** Keeps the output allow-list property and code quality. **Cons.** A contract change is still
made twice. **Risks (security).** Generation would widen what relays forward (see FE-07). **Effort.**
S. **Prerequisites.** None.

### FE-07 — Untyped relays bypass the mirror contract

**Evidence** (`30-frontend-java-resptypes.py`, grep). 72 of 623 call sites decode the backend answer as
raw `Map`/`Object`/`JsonNode` — `MissionWriteController` 23, `MaterialboersePageController` 13,
`LeitungPageController` 12, `PromotionProxyController` 9, others 15 — and several return it to the
browser unchanged, e.g. `PromotionProxyController.java:58,72,97,111,136,150`
(`return backendApiClient.post(…, body, Map.class)`). 78 handlers in 15 controllers accept
`@RequestBody Map<…>` and forward it (53 are typed). Contract tests cannot see any of these.

**Impact.** For these domains the frontend has no contract with the backend at all, and a field the
backend adds flows to the browser without review. **Proposed change.** Type them while introducing the
domain's client (FE-04): request records with `@Valid` where the page has a form, response mirrors
where the page renders or relays. **Pros.** Exact contract tests; output allow-list restored. **Cons.**
About 150 signatures. **Risks (security).** Frontend validation must never be stricter than the
backend's for a field the backend accepts (it would 400 a legitimate edit); the backend stays
authoritative. Guard: per-domain relay test asserting the JSON the browser receives equals the mirror's
field set. **Effort.** M. **Prerequisites.** FE-04 for the domain.

### FE-08 — The session allow-list is keyed on the package that a domain move would empty

**Evidence.** Sessions are Redis JSON with `NON_FINAL` default typing; a value names its class and the
validator admits `ALLOWED_PREFIXES = "org.springframework.security.", "de.greluc.krt.profit.basetool.frontend.model."`
plus exact names (`SessionTypeAllowList.java:86-105`); production runs `enforce` since 2026-09-25 17:58
UTC (`docs/adr/0206-…md:3`, *(vault)* `10 Systems/Frontend.md:1504-1510`), the E2E stack too
(`docker-compose.e2e.yml:51`). Application classes that actually enter the session are flash values:
305 `addFlashAttribute` calls, 248 `String`, 5 `Boolean`, and the typed ones `RefineryOrderForm` (12 +1
via `toForm`), `JobOrderItemForm` 8, `PersonalInventoryForm` 2, `ShipForm` 2, `InventoryForm` 2,
`ParticipantForm` 2, `BankWipeResetResultDto` 1, `ImportIssueDto` lists/maps 2
(`30-frontend-java-flash.py`) — ≈11 types, ≈20 with nested members (`RefineryGoodForm`,
`RefineryOrderStatus`, the `JobOrderItemForm` line/material forms, `InventoryForm.AllocationRow`,
`PersonalInventoryLocationType`, `ImportIssueCode`, `ImportIssueSeverity`, `ImportSuggestionDto`). The
other `session.setAttribute` writes hold JDK types only (`BackendRoleSyncFilter.java:183-448`,
`TermsAcceptanceGateFilter.java:309-310`, `MeFrontendController.java:122`,
`ConnectedAppsConfirmRelayController.java:182,199`, `HomeController.java:126`). Staged ingest handoffs
are read monomorphically (`IngestHandoffService.java:99`) and are unaffected.

**What a move or rename does.** (a) Under `enforce`, a flashed form moved to e.g.
`frontend.refinery.model` is **refused on read** and its attribute dropped
(`FaultTolerantSessionSerializer`, ADR-0157) — the redirect after a failed save loses the user's input,
silently in the UI, loudly only as `SessionTypeOutsideAllowList`. (b) Flash maps written by the old
release name the old class; after the deploy they fail to resolve and are dropped the same way — only
redirects in flight during the ~2-minute restart window. (c) Security contexts and tokens are Spring
types and unaffected.

**Proposed change.** Either keep session-bound types under `frontend.model.<domain>` (no list change;
the three contract tests keep working because Spring's classpath scan includes subpackages —
`DEFAULT_RESOURCE_PATTERN = "**/*.class"`, `ClassPathScanningCandidateComponentProvider.java:95`
*(gradle cache, spring-context 7.0.9)*), or — the
security-improving option — replace the `frontend.model.` prefix by an **exact, test-derived list**:
a marker (`SessionValue`) on the ≈11 flashed types, and a test that computes the transitive closure of
their record components/fields and asserts the allow-list equals it. That narrows the admitted
application classes from 325 to ≈20. **Pros.** Move-safe; smaller gadget surface. **Cons.** A new
flashed type is a list change (as ADR-0206 already says for types outside `frontend.model`).
**Risks (security).** Widening instead (e.g. prefix `…frontend.`) would admit every frontend class,
including configuration beans with setters — do not. A missed type is caught by E2E (enforce) only if
the flow is covered; hence the closure test. **Effort.** S (keep prefix) / M (exact list).
**Prerequisites.** ADR-0206 amendment and REQ-SEC-067 update for the exact list; ship it in its own
release, before the package move.

### FE-09 — Templates: 172 `T(...)` class references, a flat folder layout, and no static check

**Evidence.** 172 `T(de.greluc.krt.profit.basetool.frontend.support.Roles)` in 22 templates, every one
inside `sec:authorize` (ADMIN 91, LOGISTICIAN 32, OFFICER 29, MISSION_MANAGER 6, KRT_MEMBER 6,
BANK_MANAGEMENT 6, BANK_EMPLOYEE 2); no other FQCN in templates; SpEL bean references `@handles` 42,
`@markdown` 6, `@moneyFormat` 2 (bean names, not packages). No test resolves `T(...)` statically
(grep over `frontend/src/test`: 0). Template tree: 62 root templates named by prefix, `admin/` 22
(cross-domain), `fragments/` 30 (shell and domain mixed), `organisation/` 2, `error/` 4 — 120 files.
486 fragment references in templates (shell: `modal-wrapper` 106, `head` 90, `sidebar` 83, `toast` 39,
`scu-hint` 32, `components` 29, `pagination` 28) and 105 `"view :: fragment"` strings in Java.

**Impact.** Moving `Roles` breaks those checks at render time only; rendering fails with an SpEL
error (fail closed, a 500), but only on the templates a test renders. View names and fragment paths are
strings, so a per-domain template folder move is unchecked by the compiler.

**Proposed change.** Keep `Roles` in the kernel (or replace `T(...)` by a template utility object);
add **G5** `TemplateTypeReferenceTest`: every `T(fqcn)` resolves and every referenced constant exists;
and **G8**: every view name a controller returns resolves to a template. Then move domain templates
into `templates/<domain>/` (mapping in A11), leaving shell fragments in `fragments/`. Template tests
already walk the tree recursively (`Files.walk`, `…/template/*Test.java`).
**Pros.** Domain templates next to their domain; checks for today's blind spot. **Cons.** 62+24 files
and their view-name strings move. **Risks (security).** A `sec:authorize` evaluation error must stay a
failure, never a silent "show" — G5 keeps it out of production. **Effort.** S (G5/G8), M (folders).
**Prerequisites.** None.

### FE-10 — Cross-domain coupling inside the frontend is page composition

**Evidence** (`30-frontend-java-domains.py` over `jdeps-frontend.txt`, inner classes folded; A8).
1,177 frontend-internal class edges. 30 domain controllers depend on another domain's types, led by
`MissionPageController` (7 foreign domains), `JobOrderPageController` (6),
`RefineryOrderPageController` (6), `InventoryPageController` (5), `RefineryOrderWriteController` (4).
There are **0** cross-domain controller→controller edges (all 18 controller→controller edges stay
inside mission, bank, inventory, exchange, hangar, refinery, catalogue). 173 cross-domain class edges;
80 of them are DTO→DTO edges that mirror the backend's graph and point mostly at reference types
(`SquadronReferenceDto`, `UserReferenceDto`, `MaterialReferenceDto`, `LocationReferenceDto`,
`OrgUnitReferenceDto`, `GameItemReferenceDto`, `BlueprintReferenceDto`, `MissionReferenceDto`). Seven
two-way domain pairs: joborder↔inventory (5/5), mission↔operation (5/3), mission↔refinery (2/5),
mission↔identity (7/2), mission↔inventory (1/2), orgchart↔leadership (2/1),
catalogue↔personalinventory (1/1). 11 kernel→domain edges: the layout advices and loader need
`OrgUnitMembershipOptionDto`/`SquadronDto`, `OrgUnitContextAdvice` and `ActiveSquadronContextFilter`
read a constant from `MeFrontendController` (`MeFrontendController.java:54`), the access gates need
`UserDto`, `RegistrationStatusDto`, `TermsStatusDto`, and `BackendApiClient` needs `TermsDocumentDto`.
`LiveSyncTopicClass` (kernel) holds probe paths of 5 domains plus `/api/v1/me/capabilities`;
`CachedCatalog` (kernel) holds paths of 4 domains.

Layout model (item 3 of the brief): 7 `@ControllerAdvice` — 5 scoped to `@UsesLayoutModel`
(`AppVersionAdvice`, `CapabilityFlagsAdvice`, `LayoutMiscAdvice`, `OrgUnitContextAdvice`,
`SafeCsrfAdvice`; 30 `@ModelAttribute` methods), 2 global (`GlobalBindingAdvice`,
`GlobalExceptionHandler`); one backend read per page, `GET /api/v1/me/layout`
(`LayoutContextLoader.java:64`, ADR-0165 amendment).

**Impact.** The frontend is a composition layer: cross-domain *reads* are legitimate, and nothing
entangles domain logic. The pairs show where shared vocabulary sits in the wrong domain
(`PayoutPreference` in `model` root, used by identity, operation and mission; `AllocationReductionDto`
used by job-order handovers; `AreaLeadershipDto`↔`OrgChartNodeDto`).

**Proposed change.** Kernel = identity and org-unit **context** (who am I, which unit is active, what
may I see) + the reference ("published language") DTOs; move the session-key constant into
`ActiveSquadronContext`; move `PayoutPreference` into the kernel; treat orgchart+leadership as one
"organisation" domain (the frontend already routes `/organisation/leitung` and `/org-chart`);
cross-domain access only through a domain's `client` and published `model` (R1, R2 in FE-15). Do not
demand acyclicity between frontend domains — page composition makes cycles legitimate; demand "no
dependency on another domain's `web` package" instead. **Pros.** Expressible gates with today's
structure nearly compliant. **Cons.** A handful of type moves. **Risks (security).** None; the gates
make a future relay into another domain's backend area visible in review. **Effort.** M.
**Prerequisites.** FE-15 move.

### FE-11 — The authorization gate is checked per class; there is no route snapshot

**Evidence.** `everyControllerCarriesAGateOfItsOwn` passes if the class **or any** handler carries
`@PreAuthorize` (`ArchitectureTest.java:95-111,244-247`). Per-handler scan (`30-frontend-java-gates.py`):
535 handlers, 13 without their own or a class gate — 11 in the 8 `PUBLIC_BY_DESIGN` controllers and 2
member-visible pages that are documented as such: `GET /org-chart` (`OrgChartPageController`) and
`GET /ship-data` (`ShipDataPageController`; `ROLES_AND_PERMISSIONS.md:1145-1150`, *(vault)*
`20 Domains/Admin.md:88-89`); both still need a login (`SecurityConfig.java:207-208`). There is no test
that snapshots the route table with effective gates (grep for `RequestMappingHandlerMapping` in
frontend tests: 0). ADR-0068's controller split proved route identity with a one-off 118-route diff.

**Impact on the refactor.** Moving a handler out of a class with a class-level gate into a class whose
other handlers are gated leaves it "authenticated only", and every test stays green.

**Proposed change.** **G3** per-handler rule: every handler has an effective gate (method ∪ class) or
is on an explicit handler allow-list (the 11 public + the 2 member pages). **G2** committed route/gate
snapshot built from `RequestMappingHandlerMapping` in a Spring test: verb, path, consumes/produces,
headers, effective `@PreAuthorize` expression, `@UsesLayoutModel`, body-writing — the package move
must leave it byte-identical. **Pros.** Makes the moves provably behaviour-neutral. **Cons.** Snapshot
maintenance on every intended route change. **Risks (security).** None; closes a regression path.
**Effort.** S (G3), M (G2). **Prerequisites.** Before any move (FE-15 step 0).

### FE-12 — `ParallelPageLoader` drops the user-locale relay

**Evidence.** Four relay ThreadLocals are registered for Reactor
(`ReactorContextPropagationConfig.java:82-131`: org unit, correlation id, `LocaleContextHolder`, client
IP). `ParallelPageLoader.loadAsync` copies the org unit, correlation id, client IP, security context,
request attributes and MDC to its virtual thread, **not** the locale (`ParallelPageLoader.java:67-125`);
nothing sets an inheritable locale context (grep `setLocaleContext|threadContextInheritable`: only the
accessor itself). `UserLocaleRelayFilter` sends no `Accept-Language` when the context is empty
(`logging/UserLocaleRelayFilter.java`), so backend calls made from the 8 controllers that use the
loader go out in the backend's default locale. `ParallelPageLoaderTest.loadAsyncPropagatesEveryRelayThreadLocalTogether`
covers three of the four. Observable effect not measured — **verify with a test** asserting
`Accept-Language` on a call made through `loadAsync`.

**Proposed change.** One source of truth for the relay context: capture and restore through the same
registered accessors (Micrometer's context-snapshot API — exact API **to verify in the docs**), or at
least copy the locale and assert "every registered accessor is propagated". Typed clients (FE-04)
inherit whatever this does, so fix it first. **Risks (security).** None (locale only); do not let the
fix start propagating anything a worker must not carry beyond the request. **Effort.** S.
**Prerequisites.** None.

### FE-13 — The error mapper can be a sealed type with a pattern-matching switch (modern Java)

**Evidence.** `BackendApiClient.handleException` is an `instanceof` chain over
`CallNotPermittedException`, `BulkheadFullException`, `TimeoutException`/`WebClientRequestException`/
`IOException` with near-identical `BackendServiceException` constructions
(`BackendApiClient.java:478-519`); `unwrap` repeats the same list (`:522-538`).

**Proposed change.** When extracting `BackendErrorMapper` (FE-04), express the outcome as a sealed
interface (`Problem`, `Unavailable`, `Timeout`, `Reauth`, `Unexpected`) and classify with a `switch`
using type patterns with unnamed pattern variables (`case CallNotPermittedException _ ->`,
`case TimeoutException _ ->`, …; whether several unnamed patterns may share one `case` label is
**to verify in the JLS** — one label per type works either way). Both constructs already compile in this repository under the same toolchain and ADR-0223: the
backend's `exception.AppException` hierarchy is sealed (`docs/specs/api-conventions.md:153`) and
`backend/…/service/SseSendFailureCause.java:50-51` switches with `case IOException _ ->`. The compiler
then forces a mapping for every outcome, which is what the T2 decorator and T1 share. **Pros.** Exhaustiveness instead of a trailing catch-all. **Cons.**
Small. **Risks.** Must keep the order "reauth first, then transport" and the 5xx/4xx metric reasons;
guard: the four `BackendApiClient*Test` suites unchanged. **Effort.** S. **Prerequisites.** FE-04
extraction.

### FE-14 — Documentation claims that the code does not back

**Evidence and fix list.**
- arc42 §4.1: "ArchUnit tests enforce the direction so the seam cannot quietly grow a second one"
  (`docs/arc42/04-solution-strategy.md:14`) — no such rule exists (FE-02). Fix the text or add R3.
- arc42 §5.3 lists `view / model` as "View models and the hand-mirrored DTO records" and `service` as
  "`BackendApiClient` plus view-shaping services" (`docs/arc42/05-building-block-view.md:78-85`) —
  `view` holds one class (`MoneyFormat`); the view shaping lives in `controller` (FE-01).
- `frontend/CLAUDE.md:228` "Resilience4j wraps every backend call" — true for `webClient` and
  `termsDocumentClient`, deliberately false for `sseWebClient` and `liveSyncAuthWebClient`
  (`WebClientConfig.java:537-579`).
- The vault's "single seam" statements (*(vault)* `10 Systems/Frontend.md:66-69`,
  `10 Systems/Backend.md:312-313`) need the 11 bypassing controllers and the corrected reason.
**Effort.** S. **Security.** None (documentation), but the arc42 sentence currently tells a reviewer
that a gate exists which does not.

### FE-15 — Target structure, migration sequence and guards for the frontend

**Target (Option A, frontend counterpart).**

```
de.greluc.krt.profit.basetool.frontend
  kernel.backend      BackendApiClient, BackendErrorMapper, BackendServiceException, CachedCatalog,
                      CacheDomain, CatalogCacheResolver, ParallelPageLoader, WebClientConfig,
                      relay filters and context holders, ReactorContextPropagationConfig
  kernel.security     SecurityConfig and handlers, PublicPaths, access gates (role sync, terms),
                      Roles, CurrentUser, FrontendAuthHelperService, CSP/CSRF
  kernel.session      RedisSessionConfig, SessionTypeAllowList, repair and diagnostics
  kernel.layout       UsesLayoutModel, LayoutContextLoader, the five layout advices, org-unit context
  kernel.web          GlobalExceptionHandler, BackendErrorResponses, GlobalBindingAdvice,
                      NormalizedStringEditor, MutationResponseHelper, RelayParams, CatalogPages,
                      PickerSearch, WebMvcConfig, EtagConfig
  kernel.livesync     websocket.*, LiveSyncPresenceService (the topic registry stays one closed enum)
  kernel.model        PageResponse, reference DTOs, PayoutPreference, handoff types
  kernel.observability  logging/metrics/health
  shell               landing, legal pages, manifest, asset links, CSRF token, client-error beacon
  <domain>.web        page, write and REST controllers, view assemblers
  <domain>.client     the domain's typed backend client (FE-04)
  <domain>.model      mirrors and forms (or model.<domain> — see FE-08)
```
Domains: mission, operation, joborder, inventory, personalinventory, blueprint, hangar,
materialexchange, refinery, bank, notification, catalogue, audit, promotion, organisation
(orgchart + leadership), orgunit, identity, dashboard, settings, exchange (A3 sizes them; catalogue,
mission, bank, joborder are the largest by call sites and code).

**Guards** (ArchUnit unless noted; all annotation- or name-based, none on the old layer packages):
- R1 no class outside `..<domain>..` depends on `..<domain>.web..`;
- R2 backend access only through `..client..` types or `kernel.backend`; `/api/` string literals only
  in `..client..`, `kernel..` (ratchet on the count while migrating);
- R3 `WebClient` only in `kernel.backend`, plus the named SSE relay and live-sync probe;
- R4 every typed-client bean is created from the `webClient` bean (Spring test, FE-04);
- R5 client interfaces declare no `URI`/`UriBuilderFactory`/`@CookieValue` parameter and only
  relative `/api/` paths; no `@ImportHttpServices`/`HttpServiceGroupConfigurer` in the module;
- R6 `kernel` depends on no domain package;
- G2 route/gate snapshot, G3 per-handler gate (FE-11); G4 backend-operation existence, G6 live-sync
  probe existence (FE-05); G5 template `T(...)`, G8 view names (FE-09); session closure test (FE-08);
  locale propagation test (FE-12).
Spring Modulith is not needed for the frontend; if the backend adopts it (other agents), its cycle
check would currently flag the 7 two-way domain pairs (FE-10), so allowed dependencies would have to
be declared — version fit with Boot 4.1.1 is **to verify in the docs**.

**Sequence.**
0. Guards and fixes that need no move: G2, G3, G4 (existence on today's parser), G5, G6, R3 as a
   ratchet (allow-list the 11), FE-12 locale, FE-03 id and sort binding, dead fleetview route
   (FE-02); before the first backend re-cut also FE-16 (one-release check on the host).
1. Kernel extraction in place: `BackendErrorMapper`, template overloads for write verbs, the three
   missing shapes; move the 11 direct-`WebClient` controllers onto them; R3 ratchet to 0.
2. Session allow-list decision (FE-08) in its own release.
3. Per-domain typed clients, one domain per PR, small domains first (audit, leadership, orgchart,
   notification, settings, dashboard, exchange), then the big four; T1 first, T2 when the domain's
   backend part is re-cut (FE-05); type the raw-`Map` relays of that domain (FE-07).
4. Package-by-domain move, one PR, route/gate snapshot byte-identical, contract tests re-keyed on a
   marker, `TermsDocumentClientUsageTest` and `PUBLIC_BY_DESIGN` unchanged (both use simple names).
5. Optional: template folders per domain (FE-09), JS folders per domain (JS agent).

**Move checklist — every string-keyed coupling a move must carry** (all verified): session prefix
`SessionTypeAllowList.java:86-87`; `DTO_PACKAGE`/`MIRROR_PACKAGE` in `FrontendDtoContractTest.java:56`,
`DtoOpenApiContractTest.java:57`, `GeneratedDtoAgreementTest.java:48` (their floors fail loudly);
172 `T(…support.Roles)`; `PUBLIC_BY_DESIGN` simple names (`ArchitectureTest.java:213-222`);
`TermsDocumentClientUsageTest` field name `termsDocumentClient`; `spring.factories` →
`config.SandboxProfileGuard`; `frontend/build.gradle.kts:315,512` (two E2E source paths); the
generator's package `…frontend.contract.model`. Not keyed on packages: `logback-spring.xml` (only
`logging-support` classes), `application.yml` logger levels (framework packages only), SpotBugs
`exclude.xml` (no frontend package), Checkstyle config.

**Pros.** Every step is independently shippable and reversible; security gates come first. **Cons.**
XL in total, dominated by test churn (368 test files; 1,758 URL-keyed mock lines). **Risks (security).**
Summarised per property: authorization — G2/G3 keep every handler's effective gate; tenancy — the
org-unit pin travels in the same filter (`ActiveSquadronRelayFilter`) for every client, R4 proves it;
redaction — mirrors keep the output allow-list, FE-07 extends it; audit — the frontend records nothing
itself, relays keep the backend's status and code; CSRF/CSP — URL/annotation-based, unchanged by
moves; session deserialisation — FE-08; secrets — clients never see the bearer (the OAuth2 filter
adds it), R5 blocks URL override; input validation — typed path variables (FE-03); rate limits — the
client-IP relay is a filter of the same bean. **Effort.** XL. **Prerequisites.** New ADR (typed
clients, amending arc42 §4.1 and superseding the July rejection note in the vault), ADR-0206
amendment if FE-08's exact list is chosen, arc42 §5.3, `frontend/CLAUDE.md`, the vault's Frontend
note.

### FE-16 — `:stable` is promoted per module, and the host does not check that it deploys one release

**Evidence.** The production promotion runs one job per module (`strategy.matrix.module: [backend,
frontend, ingest, config, keycloak-spi]`, `fail-fast: true`), each verifying the cosign signature
and re-tagging its own image to `:stable` (`.github/workflows/promote.yml:170-202`,
`.github/actions/retag-verified-digest/action.yml`). `iri-deploy.timer` fires every 5 minutes
(`docs/deployment.md:116`); `deploy.sh` resolves `backend`, `frontend`, `ingest`, `config` and
`keycloak-spi` at `:stable` one by one (`scripts/deploy.sh:929-938`, `rt_resolve_digest` =
`skopeo inspect`, `scripts/lib/container-runtime.sh:147-153`) and applies whatever set it finds. No
check ties the five digests to one version or commit (grep for a version/revision/label comparison
in `deploy.sh`: none), and `docs/deployment.md` does not discuss a partially promoted `:stable`
(grep `partial|mixed|matrix`: none). The `:testing` sync already uses the safe shape — resolve all
five, then re-tag (`promote.yml:287-321`).

**Impact.** A timer tick inside the promotion window deploys e.g. backend N+1 with frontend N until
the next tick; a failing matrix job (fail-fast cancels the rest) can leave `:stable` mixed until a
re-run. REQ-API-001's in-place shape carve-out and ADR-0136 both rest on "frontend and backend deploy
atomically" (`docs/adr/0136-…:18-24`); a per-domain re-cut (FE-05) raises the cost of a mixed set from
"a stale field" to "every call of the domain 404s". Whether the window was ever hit is UNKNOWN —
`last-deployed.digests` history or the `release parts:` log line (`deploy.sh:1356`) on the host would
settle it (a read-only check).

**Proposed change.** (1) In `deploy.sh`, refuse a target whose app images do not share one
`org.opencontainers.image.revision` (or version) label — `deploy.sh` already runs `skopeo inspect`
per image, whose JSON carries a `Labels` field (for the multi-arch index, which platform's labels it
returns is **to verify in the docs**); a refusal keeps the running release (fail closed) and logs why. Whether `docker/metadata-action` adds
`revision` by default next to the custom labels in `release-images.yml:329-335` is **to verify in the
docs**; if not, add it explicitly. (2) Promote with one job that verifies all five and then re-tags
all five, like the testing sync. **Pros.** Makes the atomic-deploy premise true end to end. **Cons.**
One more pre-flight check; a mislabelled image blocks deploys until fixed. **Risks (security).** The
check must never be bypassable by the image content itself (compare labels of cosign-verified
digests only, after step 3's verification). Guard: `scripts/deploy.test.sh` case with mixed labels →
no apply. **Effort.** S–M. **Prerequisites.** Owner decision; `docs/deployment.md` and arc42 §7
updated; the host change reaches production only through a normal release (no manual host write).

## 3. Data appendix

### A1 — Packages of `frontend/src/main/java` (`30-frontend-java-scan.py`)

| Package | Classes | Lines | Non-blank, non-comment |
|---|---:|---:|---:|
| controller | 103 | 33,630 | 21,659 |
| model.dto | 292 | 11,906 | 2,648 |
| config | 62 | 8,994 | 4,551 |
| websocket | 9 | 3,226 | 1,813 |
| service | 11 | 2,047 | 1,039 |
| model.form | 30 | 1,611 | 515 |
| logging | 14 | 1,540 | 810 |
| support | 13 | 1,118 | 400 |
| exception | 2 | 740 | 489 |
| metrics | 3 | 627 | 148 |
| health | 3 | 535 | 282 |
| oss | 5 | 413 | 138 |
| model (root) | 3 | 246 | 151 |
| validation | 2 | 113 | 32 |
| view | 1 | 67 | 25 |
| (root) | 1 | 45 | 16 |
| **total** | **554** | **66,858** | **34,716** |

`service`: `BackendApiClient`, `BackendServiceException`, `CacheDomain`, `CachedCatalog`,
`CachedCatalogListLoader`, `CatalogCacheResolver`, `ParallelPageLoader` (backend hop),
`FrontendAuthHelperService` (security), `IngestHandoffService` (ingest draft handoff),
`LiveSyncPresenceService` (live sync), `MarkdownRenderer` (view). `support`: `AuditDomains`,
`BackendErrorResponses`, `CatalogPages`, `CurrentUser`, `HandleDisplay`, `MapPayloadValues`,
`MutationResponseHelper`, `PickerSearch`, `RelayParams`, `Roles`, `SessionIdFingerprint`,
`StringNormalization`, `TermsGateHandoff`. `websocket`: `LiveSyncFanout`, `LiveSyncLocalBus`,
`LiveSyncSubscriptionAuthorizer`, `LiveSyncSyncHandshakeInterceptor`, `LiveSyncTopic`,
`LiveSyncTopicClass` (17 topic classes), `LiveSyncWebSocketHandler` (1,658 lines), `NoopLiveSyncFanout`,
`RedisLiveSyncFanout`.

### A2 — The 62 `config` classes by concern

| Concern | n | Classes |
|---|---:|---|
| Security and auth flow | 20 | AssetAwareAuthenticationSuccessHandler, BackendRoleSyncFilter, BotProtectionFilter, CspNonceFilter, CsrfMetricsAccessDeniedHandler, CurrentRegistrationAuthorizedClientRepository, FrontendClientAuthenticationConfig, LoginFailureMetricsHandler, LoginSuccessMetricsHandler, ManagementPortSecurityConfig, MonitoringScrapeSecurityConfig, PublicPaths, SafeCsrfAdvice, SandboxProfileGuard, SecurityConfig, SecurityHeaders, SingleFlightAuthorizedClientManager, SmartOidcLogoutSuccessHandler, SsoReAuthenticationEntryPoint, TermsAcceptanceGateFilter |
| Session (Spring Session / Redis) | 12 | FaultTolerantSessionSerializer, RedisSessionConfig, ServerConfiguredKeyspaceNotificationsAction, SessionAttributeDiagnosticMapper, SessionAttributeRepairFilter, SessionAttributeRepairQueue, SessionDebugFilter, SessionLifetimeUpgradeSuccessHandler, SessionMetricsConfig, SessionTypeAllowList, TolerantKeyspaceNotificationsAction, UnreadableSessionValue |
| Web / MVC / i18n / binding | 8 | AndroidAppLinkProperties, EtagConfig, ForwardedHeaderConfig, GlobalBindingAdvice, LocaleConfig, NormalizedStringEditor, ThymeleafJavaScriptSerializerConfig, WebMvcConfig |
| Observability | 7 | ClientIpProperties, GrafanaLinkProperties, LoggingProperties, MonitoringScrapeProperties, NotificationStreamObservationPredicate, ObservationPrivacyFilter, RequestLoggingFilter |
| Backend hop | 6 | AppBackendProperties, AppHttpProperties, CacheConfig (Caffeine), ReactorContextPropagationConfig, Resilience4jMetricsConfig, WebClientConfig |
| Layout model | 6 | AppVersionAdvice, CapabilityFlagsAdvice, LayoutContextLoader, LayoutMiscAdvice, OrgUnitContextAdvice, UsesLayoutModel |
| Live sync | 3 | LiveSyncProperties, LiveSyncRedisConfig, LiveSyncWebSocketConfig |

### A3 — Controllers by domain (`30-frontend-java-domains.py`, `30-frontend-java-urls.py`)

| Domain | View | REST | Helper | Controller lines | HTTP call sites | DTOs | Forms |
|---|---:|---:|---:|---:|---:|---:|---:|
| catalogue | 10 | 2 | 1 | 4,508 | 92 | 32 | 4 |
| mission | 3 | 0 | 1 | 3,262 | 81 | 20 | 5 |
| bank | 6 | 3 | 5 | 3,203 | 42 | 25 | 0 |
| joborder | 5 | 1 | 0 | 3,132 | 61 | 41 | 4 |
| inventory | 2 | 3 | 0 | 2,771 | 33 | 24 | 2 |
| identity | 10 | 3 | 0 | 2,747 | 54 | 19 | 5 |
| refinery | 3 | 0 | 0 | 1,925 | 25 | 11 | 4 |
| blueprint | 5 | 1 | 0 | 1,746 | 33 | 31 | 0 |
| orgunit | 4 | 2 | 0 | 1,455 | 26 | 13 | 3 |
| hangar | 1 | 2 | 0 | 1,043 | 12 | 5 | 1 |
| exchange | 3 | 3 | 0 | 1,003 | 21 | 19 | 0 |
| audit | 1 | 1 | 0 | 832 | 3 | 2 | 0 |
| notification | 2 | 0 | 0 | 793 | 12 | 9 | 0 |
| promotion | 1 | 1 | 0 | 790 | 24 | 7 | 0 |
| materialexchange | 1 | 0 | 0 | 767 | 21 | 4 | 0 |
| personalinventory | 2 | 0 | 0 | 714 | 15 | 4 | 1 |
| operation | 1 | 0 | 0 | 708 | 20 | 8 | 1 |
| dashboard | 2 | 0 | 0 | 423 | 12 | 0 | 0 |
| settings | 1 | 0 | 0 | 395 | 13 | 2 | 0 |
| leadership | 1 | 0 | 0 | 371 | 13 | 5 | 0 |
| orgchart | 1 | 0 | 0 | 191 | 5 | 7 | 0 |
| shell | 5 | 4 | 0 | 851 | 0 | 0 | 0 |
| kernel (config/service) | — | — | — | — | 6 | 4 | 0 |
| **total** | **70** | **26** | **7** | **33,630** | **624** | **292** | **30** |

Controller membership: mission — MissionPageController, MissionWriteController,
MissionFinancePageController, MissionDetailModelBuilder; operation — OperationPageController; joborder
— JobOrderPageController, JobOrderWriteController, JobOrderHandoverReportProxyController,
JobOrderMaterialDemandPageController, ItemCollectionPageController, MaterialCollectionPageController;
inventory — InventoryPageController, InventoryWriteController, InventoryDeleteAllProxyController,
InventoryOrgUnitChangeProxyController, InventoryStolenMarkProxyController; personalinventory —
PersonalInventoryPageController, AdminPersonalInventoryPageController; blueprint —
PersonalInventoryBlueprintsPageController, PersonalBlueprintImportProxyController,
BlueprintOverviewPageController, AdminBlueprintsPageController, AdminDefaultBlueprintsPageController,
AdminPersonalBlueprintsPageController; hangar — HangarPageController, HangarImportProxyController,
HangarDeleteAllProxyController; materialexchange — MaterialboersePageController; refinery —
RefineryOrderPageController, RefineryOrderWriteController, RefineryImportProxyController; bank —
BankPageController, BankProxyController, BankReportProxyController, BankGrantsPageController,
BankManagePageController, BankRequestQueuePageController, AdminBankPageController,
OrgUnitBankPageController, OrgUnitBankProxyController + helpers BankAccountDetailSupport,
BankAccountOrder, BankBalanceChart, BankDashboardViewAssembler, BankSparkline; notification —
NotificationPageController, AdminNotificationRulePageController; catalogue — MaterialsPageController,
MaterialProxyController, ProfitCalculationPageController, CatalogSearchController,
AdminMaterialsPageController, AdminMaterialAliasesPageController, AdminLocationsPageController,
AdminUexPageController, AdminP4kImportPageController, AdminSyncReportsPageController,
AdminMissionDataPageController, ShipDataPageController + helper PlanetColorResolver; audit —
AdminAuditLogPageController, AuditReportProxyController; promotion — PromotionPageController,
PromotionProxyController; orgchart — OrgChartPageController; leadership — LeitungPageController;
orgunit — AdminOrgStructurePageController, AdminSpecialCommandsPageController,
SpecialCommandMembersPageController, SpecialCommandAdminProxyController, SquadronAdminProxyController,
MeFrontendController; identity — ProfileController, ProfileRsiHandleProxyController,
DeletionRequestProxyController, DataExportProxyController, MemberManagementController,
UserProxyController, AdminDiscordRegistrationsPageController, AdminDeletionRequestsPageController,
AdminPersonSearchPageController, PendingApprovalPageController, TermsAcceptancePageController,
TermsController, AdminTermsPageController; dashboard — HomeController, AdminAnnouncementPageController;
settings — AdminSettingsPageController; exchange — AdminExchangeClientsPageController,
AdminExchangeClientsRelayController, ConnectedAppsPageController, ConnectedAppsRelayController,
ConnectedAppsConfirmController, ConnectedAppsConfirmRelayController; shell — CsrfTokenController,
ClientErrorReportController, AppLinkController, AssetLinksController, WebAppManifestController,
ImpressumController, PrivacyController, OssLicensesController, ScLinksPageController.
Handlers: 533 mapping annotations (535 by the gate scan), 202 `@ResponseBody` in `@Controller`
classes (ratchet 215 incl. `HttpEntity` returns, `ArchitectureTest.java:192`); 56 class-level and 189
method-level `@PreAuthorize`.

### A4 — DTO mirrors and forms by domain

- audit (2): AuditEventDto, AuditRowView.
- bank (25): BankAccountDetailDto, BankAccountDto, BankAccountRefDto, BankApprovalLimitUserDto,
  BankApprovalLimitsDto, BankAuditEventDto, BankBalancePointDto, BankBalanceSeriesDto, BankBookingDto,
  BankBookingRequestDto, BankCapabilitiesDto, BankDashboardAccountDto, BankDashboardDto,
  BankDashboardTotalsDto, BankGrantDto, BankHolderBookingDto, BankHolderDto, BankTransferFeeRateDto,
  BankWipeResetResultDto, ConsolidateAccountRequest, MergeAccountRequest, OrgUnitBankAccountDetailDto,
  OrgUnitBankAccountSettingsDto, OrgUnitBankBalanceDto, OrgUnitBankViewUserDto.
- blueprint (31): Blueprint* (19), CraftabilityGroupDto, CraftabilityMaterialDto, DefaultBlueprint* (4),
  PersonalBlueprint* (7 incl. requests/results).
- catalogue (32): CityDto, DerivedMaterialDto, GameItemReferenceDto, ItemDerivationDto, JobTypeDto,
  LocationDto, LocationReferenceDto, ManufacturerDto, MaterialCategoryDto, MaterialCreateAjaxRequest,
  MaterialDto, MaterialExternalAliasDto, MaterialExternalAliasWriteRequest, MaterialMatrixItemDto,
  MaterialPriceDto, MaterialPriceOverviewDto, MaterialReferenceDto, MaterialUpdateAjaxRequest,
  MatrixGridDto, OutpostDto, P4kImportJobDto, P4kImportResultDto, PoiDto, RefiningMethodDto,
  ShipTypeDto, SpaceStationDto, StarSystemDto, SubAssemblySuggestionDto, SyncReportDto,
  SyncReportPurgeResultDto, TerminalDto, UexLocationDto.
- exchange (19): ConnectedApp* (4), ConnectedInstallationDto, ExchangeBulkUndo* (5), ExchangeClient*
  (5), ExchangeSettings* (2), ExchangeUndoRequestDto, ExchangeUndoResultDto.
- hangar (5): SetHomeLocationRequestDto, ShipDto, ShipRequestDto, SquadronShipDetailDto,
  SquadronShipOverviewDto.
- identity (19): AdminDeletionRequestDto, ApproveRegistrationRequest, LinkRegistrationRequest,
  MyRsiHandleResponse, PendingRegistrationDto, PersonSearchHitDto, PersonSearchResultDto,
  RegistrationStatusDto, RejectRegistrationRequest, ReopenRegistrationRequest, TermsAcceptanceStatusDto,
  TermsClauseDto, TermsDocumentDto, TermsSectionDto, TermsStatusDto, UserAttributesUpdateDto, UserDto,
  UserReferenceDto, UserSyncResultDto.
- inventory (24): AggregatedInventoryDto, AllocationReductionDto, Bulk* (9), CheckoutType,
  GroupedInventoryDto, InventoryAllocation* (3), InventoryGameItemReferenceDto, InventoryItem* (7),
  InventoryStackDto.
- joborder (41): AggregatedMaterialDto, ClaimBucketDto, ClaimDto, CreateClaimDto, CreateJobOrder* (5),
  JobOrder* (25), MaterialCollectionEntryDto, MaterialDemand* (4), UpdateDeliveredRequest,
  UpdateJobOrderBlueprintCountingDto, UpdateJobOrderStatusDto.
- leadership (5): AreaLeadershipDto, KommandoGroupDto, LeitungMemberDto, LeitungUnitDto, LeitungViewDto.
- materialexchange (4): MaterialExchangeCountsDto, MaterialExchangeOfferDto,
  MaterialExchangeReleasableItemDto, MaterialRequestDto.
- mission (20): CreateMissionRequest, FinanceType, MissionActualTimeUpdateRequest, Mission* (16),
  UpdatePayoutPreferenceRequest.
- notification (9): NotificationBulkResultDto, NotificationCountResponse, NotificationDto,
  NotificationPageSliceDto, NotificationRule* (4), NotificationViewDto.
- operation (8): Operation* (8). orgchart (7): BereichChartDto, CommandChartDto, OlChartDto,
  OrgChartDto, OrgChartNodeDto, SpecialCommandChartDto, SquadronChartDto.
- orgunit (13): BereichCreateRequest, MembershipDeltaRequest, MembershipDeltaResponse, OrgUnitKind,
  OrgUnitMembershipDto, OrgUnitMembershipOptionDto, OrgUnitNodeDto, OrgUnitParentUpdateRequest,
  OrgUnitReferenceDto, OrganisationsleitungCreateRequest, SpecialCommandDto, SquadronDto,
  SquadronReferenceDto.
- personalinventory (4), promotion (7: MemberEvaluationDto, Promotion* (5), RankRequirementDto),
  refinery (11: ImportIssue* (3), ImportSuggestionDto, Refinery* (7)), settings (2: SystemSetting*),
  kernel (4: PageResponse, BackendEnumAsString, HandoffKind, StagedHandoff).
- Forms (30): mission 5 (CrewForm, MissionFinanceEntryForm, MissionForm, ParticipantForm, UnitForm);
  operation 1; catalogue 4 (FrequencyTypeForm, JobTypeForm, ManufacturerForm, ShipTypeForm); inventory
  2; joborder 4; identity 5 (MemberEditForm, Profile* (4)); orgunit 3 (MembershipFlagsForm,
  SpecialCommandForm, SquadronForm); personalinventory 1; refinery 4; hangar 1 (ShipForm).
- `model` root (3): PayoutPreference (used by mission, operation and identity), ScLink, ScLinkCategory.

### A5 — The backend seam

`BackendApiClient` public API (14): `get(String, ParameterizedTypeReference)`,
`get(String, ParameterizedTypeReference, Object...)` (the only template overload), `get(String, Class)`,
`getCached(CachedCatalog, ParameterizedTypeReference)`, `getCached(CachedCatalog, Class)`,
`evict(CacheDomain...)`, `evictAllCatalogues()`, `clearStaticDataCache()`, `post`, `put`,
`delete(String, Class)`, `delete(String, T, Class)`, `patch`, `getTermsDocumentAnonymously()`
(`BackendApiClient.java:91-377`). No multipart, bytes-with-headers, streaming, per-call header or
timeout method.

`WebClient` beans (`WebClientConfig.java`):

| Bean | Pool | OAuth2 filter | Relays | Resilience4j | Used by |
|---|---|---|---|---|---|
| `webClient` | `frontend-pool`, HTTP/2 default | yes | correlation, org unit, locale, client IP | `backendApi` | BackendApiClient + 11 controllers |
| `termsDocumentClient` | `frontend-terms-pool` | no | correlation, locale, client IP | `backendApi` | BackendApiClient only (guarded) |
| `sseWebClient` | `frontend-sse-pool`, HTTP/1.1 | no (bearer set by caller) | all four | none | NotificationPageController |
| `liveSyncAuthWebClient` | `frontend-livesync-probe-pool` | no (bearer set by caller) | correlation, locale, client IP | none | LiveSyncSubscriptionAuthorizer |

Error mapping: `exchange` (`:395-407`) → `handleWebClientException` (non-error status → transport
branch; Problem+JSON parse; 5xx ERROR, access-gate codes DEBUG, other 4xx WARN; metric reasons
`backend_5xx`/`backend_4xx`, `:423-466`) / `handleException` (reauth; `CallNotPermittedException` →
503 `SERVICE_UNAVAILABLE`; `BulkheadFullException` → 503; timeout/transport/`IOException` → 504
`BACKEND_TIMEOUT`; else 500, `:468-520`). Relay to the browser: `BackendErrorResponses.relay` and
`propagateBackendError` (`support/BackendErrorResponses.java:71-122`); pages go through
`GlobalExceptionHandler.handleBackendServiceException` (`:88-139`). Spring versions resolved:
spring-web/webflux 7.0.9, Boot 4.1.1, Security 7.1.1, Resilience4j 2.4.0, reactor-netty-http 1.3.7,
context-propagation 1.2.1 (`gradle/verification-metadata.xml:2282,2424,3263,8155,8166,8235,9161`).

### A6 — Construction style of the URI argument (624 HTTP call sites)

| Style | Sites |
|---|---:|
| concatenation | 350 |
| plain literal | 148 |
| cached catalogue (`getCached`) | 30 |
| URI template + variables (`get` 3-arg; 16 of them with a concatenated prefix) | 26 |
| constant | 25 |
| helper call (`uri.toString()`, `…build().toUriString()`) | 16 |
| `UriComponentsBuilder` inline / via local | 6 / 9 |
| local variable built by concatenation / helper | 6 / 6 |
| other (ternary), no-arg anonymous terms read | 1 / 1 |

Per domain (call sites: concatenated share): catalogue 92 (65), mission 81 (66), joborder 61 (41),
identity 54 (20), bank 42 (9), blueprint 33 (14), inventory 33 (14), orgunit 26 (21), refinery 25
(11), promotion 24 (13), exchange 21 (14), materialexchange 21 (11), operation 20 (15),
personalinventory 15 (8), settings 13 (1), leadership 13 (12), dashboard 12 (3), notification 12 (8),
hangar 12 (4), orgchart 5 (3), audit 3 (0), kernel 6 (0). The per-domain pass counts a 3-argument
`get` as a template call even when its first argument is a concatenated local (3 sites), so its
concatenated total is 353 against 356 in the table above.

### A7 — `/api/v1` literals by backend-resource domain

646 literals in main: 539 in the owning domain's classes, 71 in other domains' classes, 36 in kernel
classes. Full table in FE-05; frontend tests hold 1,150 recognisable domain references, `src/e2e` 156.

### A8 — Cross-domain class edges (folded jdeps, non-kernel), top 20

refinery→catalogue 15, joborder→catalogue 14, joborder→orgunit 9, mission→identity 7,
inventory→catalogue 7, refinery→identity 6, hangar→catalogue 6, mission→orgunit 6, identity→orgunit 6,
inventory→identity 5, inventory→joborder 5, joborder→inventory 5, mission→operation 5,
refinery→mission 5, mission→catalogue 5, joborder→identity 5, refinery→orgunit 4, bank→orgunit 4,
inventory→orgunit 3, mission→hangar 3 (total 173; full list in `30-frontend-java-domains.out.txt`).

### A9 — Direct `WebClient` users

| Class | Bean | Why |
|---|---|---|
| AdminP4kImportPageController | webClient | multipart upload, `bodyToFlux` job list, job read |
| AdminPersonalBlueprintsPageController | webClient | multipart import |
| AuditReportProxyController | webClient | report downloads, `DELETE` returning bytes |
| BankReportProxyController | webClient | PDF download with `X-User-Time-Zone` |
| DataExportProxyController | webClient | export download, extended response timeout, no retry |
| HangarDeleteAllProxyController | webClient | body-less DELETE (no visible reason) |
| HangarImportProxyController | webClient | streamed multipart, 8 MiB cap; deprecated fleetview relay |
| InventoryDeleteAllProxyController | webClient | body-less DELETE (no visible reason) |
| JobOrderHandoverReportProxyController | webClient | PDF downloads, preview; frontend route `/api/v1/orders` |
| OrgUnitBankProxyController | webClient | statement PDF with `X-User-Time-Zone` (one handler) |
| PersonalBlueprintImportProxyController | webClient | multipart import |
| NotificationPageController | sseWebClient | SSE relay (by design) |
| LiveSyncSubscriptionAuthorizer | liveSyncAuthWebClient | subscribe probes (by design) |
| BackendApiClient | webClient, termsDocumentClient | the seam |

### A10 — Session-bound application types (flash values)

305 `addFlashAttribute` calls: String 248 (+5 via `String` locals, +11 via `String`-returning
helpers `classifyError`/`failureToastKey`), Boolean 5, RefineryOrderForm 12 (+1 `toForm`),
JobOrderItemForm 8, PersonalInventoryForm 2, ShipForm 2, InventoryForm 2, ParticipantForm 2,
BankWipeResetResultDto 1, `ImportIssueDto` list/map 2, number/count expressions 4 (sum 305).
`FlashAttributeTypesTest` rejects `BindingResult`s.

### A11 — Templates

Root 62 (15,924 lines), `admin/` 22 (4,095), `fragments/` 30 (3,458), `organisation/` 2 (450),
`error/` 4 (146). Proposed domain folders for root templates: mission 2, operation 2, joborder 6,
inventory 6, personalinventory 1, blueprint 2, hangar 2, materialexchange 1, refinery 3, bank 8,
notification 1, catalogue 5, promotion 5, orgchart 1, identity 6, exchange 2, settings 1, shell 8.
Domain fragments: bank 6 (5 `bank-*`, `org-unit-bank-views`), inventory 2, materialexchange 3,
catalogue 3 (`admin-uex`, `material-amount`, `material-card`), orgchart 1, identity 2
(`profile-deletion-card`, `terms-body`); the rest is shell.

### A12 — Contract and tooling facts

`openapi.json`: OpenAPI 3.1.0, 440 paths, 572 operations, 2 deprecated (`POST
/api/v1/hangar/import/fleetview`, `GET /api/v1/system/ping`), 96 tags, 489 schemas, 0 polymorphism
constructs, 35 of 366 directly referenced schemas shared by more than one tag
(`30-frontend-java-openapi.py`). Generated test models: 420 files, 6 with Jackson-2 `databind`
imports. Monitoring: `http_client_requests` appears in `monitoring/grafana/dashboards/03-spring-apps.json:643,860`
and `monitoring/prometheus/alerts/apps.yml:181`, none grouped by `uri`. Spring HTTP-interface classes
present in spring-web 7.0.9: `HttpServiceProxyFactory`, `HttpExchangeAdapterDecorator`,
`ReactorHttpExchangeAdapterDecorator`, `RequestPartArgumentResolver`,
`RequestAttributeArgumentResolver`, `UrlArgumentResolver`, `UriBuilderFactoryArgumentResolver`,
registry `ImportHttpServices`, `HttpServiceGroupConfigurer` (`unzip -l spring-web-7.0.9.jar`). No
`spring-boot-webclient`/`-restclient` module in the Gradle cache.

### A13 — Test coupling to URL strings

368 frontend test files (82,225 lines; 204 in `controller`); 194 files mock `BackendApiClient`; 1,758
`when(`/`verify(` lines on it; matchers `eq("/api/v1…")` 665, `contains("/api/v1…")` 130,
`startsWith("/api/v1…")` 35; 23 `MockWebServer` tests.

### A14 — Commands and scripts (scratchpad, prefix `30-frontend-java-`)

- `30-frontend-java-scan.py` → `30-frontend-java-scan.json`: class inventory, controller facts,
  classification of every `backendApiClient.<method>(` call (comment-stripped, paren-aware parser).
- `30-frontend-java-calls.py` → call styles, `30-frontend-java-callstyles.json`.
- `30-frontend-java-concat-types.py` → declared types of concatenated operands.
- `30-frontend-java-domains.py` → domain map (`30-frontend-java-domainmap.json`), per-domain counts,
  folded jdeps coupling; output saved in `30-frontend-java-domains.out.txt`.
- `30-frontend-java-urls.py`, `30-frontend-java-recut.py` → `/api/v1` literals per domain, main/test/e2e.
- `30-frontend-java-gates.py` → per-handler `@PreAuthorize` coverage.
- `30-frontend-java-flash.py` → flash-attribute value types.
- `30-frontend-java-resptypes.py` → response-type arguments (raw `Map` sites).
- `30-frontend-java-openapi.py` → `openapi.json` statistics.
- `30-frontend-java-sept.py <IDs>` → September findings (UTF-8 safe).
- Shell (Git Bash, read-only): `grep -rhoE 'T\([a-zA-Z0-9_.$]*\)' templates | sort | uniq -c`;
  `grep -rhoE '~\{[a-zA-Z/_-]+ ::' templates`; `grep -rhE '"/api/v1/[^"]*"\s*\+' frontend/src/main/java | wc -l`
  (348); `grep -rlE 'private final WebClient' …` ; `unzip -p <spring-*-7.0.9-sources.jar> <path>` for
  `WebClientAdapter`, `WebClientHttpServiceGroupAdapter`, `HttpExchangeAdapterDecorator`,
  `AbstractReactorHttpExchangeAdapter`, `HttpRequestValues`, `UrlArgumentResolver`,
  `RequestPartArgumentResolver`, `HttpServiceProxyFactory`; `git log --grep '#1256'`
  (July audit commit `415e97297`).
