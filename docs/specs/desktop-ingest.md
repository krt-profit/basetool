> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-19.
> **Owner area:** INGEST · **Related ADRs:** [ADR-0018](../adr/0018-desktop-ingest-gateway-device-grant.md) · **Related:** epic [#639](https://github.com/krt-profit/basetool/issues/639), runbook [`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md), [`refinery-screenshot-import.md`](refinery-screenshot-import.md) (`REQ-REFINERY-018`), [`security-and-access.md`](security-and-access.md), [`api-conventions.md`](api-conventions.md), [ADR-0007](../adr/0007-client-side-vlm-screenshot-extraction.md), [ADR-0008](../adr/0008-refinery-extract-json-contract.md)

# Desktop one-click ingest (send-to-basetool)

> ## ⚠️ Restricted interface — approved clients only
>
> **The ingest interface may be used exclusively by client software that the basetool developer
> (@greluc) has explicitly approved.** It is published (`REQ-INGEST-010`) so that the official
> desktop extractor can be developed against a stable contract — it is **not** an open integration
> API.
>
> Approval means two independent things, and both are required: a dedicated **Keycloak client
> registration**, and an entry on the gateway's **client allowlist**
> (`APP_INGEST_CLIENT_IDENTITY_ALLOWED_CLIENT_IDS`). Neither a stray Keycloak registration nor a
> configuration slip grants access on its own, and removing the allowlist entry revokes a client
> immediately, without a release.
>
> Any other tool is rejected with `403 CLIENT_NOT_ALLOWED` (`REQ-INGEST-011`), is unsupported, and
> may break without notice. **Building or distributing an unapproved client is not permitted.** If
> you want to integrate, ask first.
>
> Be precise about what enforcement can and cannot achieve — see the honesty note in
> `REQ-INGEST-011`: these controls segment *registered* clients from one another and make a foreign
> caller *visible*; they are not native-client attestation, which is not achievable on Windows.

## Context & goal

The desktop extractor (`basetool-bp-extractor`) produces refinery-extract and
personal-blueprint JSON entirely on the user's machine. This spec governs the path that
lets the user send that JSON into the basetool with **one click** at the end of the
extractor workflow — landing on the matching basetool page with the data already
pre-filled, logging in first if there is no session — instead of saving a file and
uploading it by hand.

The transport, auth and handoff design — a dedicated minimal-surface `ingest` gateway, a
Keycloak Device-Authorization public client, and a short-lived single-use Redis handoff —
is recorded in [ADR-0018](../adr/0018-desktop-ingest-gateway-device-grant.md). This spec
holds the binding requirements that design must satisfy. It is the #640 decision and
requirements gate of epic [#639](https://github.com/krt-profit/basetool/issues/639), the
first of sub-issues #640–#648 to land.

The non-negotiable property: **the one-click path may pre-fill, never persist.** Squadron
data is only written when the user reviews the pre-filled draft and saves it through the
unchanged create path. Direct ingest is an alternative *transport* for the import draft,
not a new write path.

## Requirements

### REQ-INGEST-001 — Dedicated gateway, minimal forward-only surface

A new standalone service (the `ingest` gateway) is the only new internet-reachable
surface. It exposes **exactly two** endpoints, one per existing import draft:
refinery-extract and blueprint-preview. Each endpoint validates the caller's JWT, forwards
the **same bearer** to the corresponding internal backend import endpoint
(`POST /api/v1/refinery-orders/import-extract`, `POST /api/v1/personal-blueprints/import/preview`),
stages the returned draft for browser pickup, and returns a handoff id. The gateway has
**no database and no Flyway migration**, serves **no HTML**, holds **no business logic**
(matching/validation stay backend-side, ADR-0008), and persists **nothing** durable of its
own. The backend remains internet-unreachable — the gateway reaches it over the internal
network only.

**Acceptance**

- [x] The gateway exposes only the two documented ingest endpoints plus the actuator
  health endpoint; every other path is 404/401. (Since **ADR-0090**, in prod the actuator
  endpoints move to a dedicated internal-only `management.server.port` — port `11272`, reachable
  only from the scrape network and the container-local healthcheck — so the **public** connector
  exposes only the two `/v1` ingest endpoints; `/actuator/**` there answers 404.)
- [x] An ingest call results in exactly one forwarded call to the matching backend import
  endpoint, carrying the caller's bearer, and no backend write.
- [x] The gateway declares no `DataSource`/JPA and runs no schema migration (architecture
  test / startup assertion).
- [x] The gateway serves **HTTPS** on 11262 (`server.ssl.enabled=true`), mirroring backend/frontend.
  nginx-proxy-manager terminates the public TLS and **re-encrypts** to the gateway over
  `https://…:11262` (NPM upstream scheme `https`, upstream-certificate verification **off** for the
  shared self-signed cert). The shared `SERVER_SSL_KEY_STORE` env vars feed **both** the server
  connector and the `backend-trust` truststore. Both the Docker `HEALTHCHECK` and the NPM upstream
  address the gateway over `https://…:11262` (the healthcheck skips cert verification). The connector
  scheme, the NPM upstream scheme and the healthcheck scheme must stay aligned — a mismatch makes the
  proxy return a bare 400 and keeps the container `unhealthy`.

**Enforced by:** `ArchitectureTest` (no JPA / no relational persistence; every controller +
`@PostMapping` is `@PreAuthorize`-annotated), `IngestControllerTest` (exactly the two endpoints,
forward-only relay, backend 4xx relayed verbatim, 502 on backend-unreachable), `BackendImportClientTest`
(the caller's bearer is forwarded) · **Code:** `IngestController`, `IngestService`, `BackendImportClient`,
`IngestApplication`, `application.yml` (`server.port: 11262`, `server.ssl.enabled: true`) · **Issues:** #642

### REQ-INGEST-002 — Authentication & authorization

The gateway is a Keycloak JWT resource server in the existing realm. Tokens are issued to a
new **public** Keycloak client (`basetool-sc-extractor`) via the **Device Authorization
Grant** (RFC 8628) with PKCE and **no client secret**. The gateway requires
`isAuthenticated()` — no elevated role; any member may ingest, mirroring `REQ-REFINERY-011`.
The token carries `aud=basetool-backend` (stamped by the dedicated `extractor-ingest` client
scope, #641); the **same** bearer is forwarded to and accepted by the backend. All data is scoped to
the token's `sub`; the gateway never acts for a different user.

> **Amended (ADR-0018 amendment 1, `REQ-INGEST-011`).** This requirement originally stated that *no
> separate ingest audience is provisioned*, on the grounds that the gateway only relays. That is
> **superseded**: a dedicated `aud=basetool-ingest` is provisioned and the gateway requires it, so
> that a `basetool-frontend` session token — which necessarily carries `basetool-backend` — cannot
> drive the ingest ingress. The token carries **both** audiences; the backend keeps requiring
> `basetool-backend`. `isAuthenticated()` for the *user* is unchanged; what was added is a gate on
> the *client software*.

**Acceptance**

- [x] A request without a valid signed realm token (carrying `aud=basetool-backend`) is
  rejected 401/403; no forward happens.
- [x] The device-grant client is public (no secret) and the secret is never embedded in the
  desktop binary or in any committed config.
- [x] The handoff staged by an ingest call is readable only under the same `sub`.

**Enforced by:** `SecurityConfigTest` (audience validator accepts a token carrying `basetool-backend`,
rejects one without it), `IngestControllerTest` (an unauthenticated caller is 401, no forward),
`ArchitectureTest` (every REST surface is authorization-annotated) · **Code:** `SecurityConfig`,
`IngestController`, `HandoffStagingService` (per-`sub` Redis key); the public `basetool-sc-extractor`
device-grant client per [`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md) · **Issues:** #641, #642

### REQ-INGEST-003 — Short-lived single-use Redis handoff

The non-persisted draft returned by the backend is staged in Redis under a key derived from
`(sub, handoffId)`. The `handoffId` is cryptographically unguessable (≥ 128 bits of
entropy). The entry has a short TTL (~30 minutes) and is **single-use**: the first successful
read for the correct `sub` consumes (deletes) it. A second read, a wrong `sub`, an expired
entry, or an unknown id all return "not found" with no draft. No screenshots and no raw
image bytes are ever staged — only the already-matched draft DTO (ADR-0007/0008: images
never leave the machine). The single-use consume is triggered off an explicit `POST`, never the
navigational pre-fill GET, so a browser prefetch or a duplicate page load cannot burn the token
before the real pickup (REQ-INGEST-004, ADR-0110).

The TTL is deliberately longer than the "picked up within seconds" happy path would suggest:
staging happens the moment the user clicks Send, but opening the pre-filled page is a **separate
manual click** in the extractor (plus a possible full browser login) *after* staging, so the
original ~5-minute window expired before pickup for slower users and surfaced as the
`ingest.handoff.notFound` notice ("Import-Link abgelaufen oder ungültig") on **every** send. The
value is env-overridable via `APP_INGEST_HANDOFF_TTL`; the entry stays single-use and per-`sub`
scoped, so the longer window does not relax the replay / IDOR guarantees below. To keep a future
miss diagnosable without leaking secrets, the stage (gateway) and the consume (frontend) each log a
**non-reversible hash** of the `sub` and the `handoffId` — never the raw subject (pseudonymous PII)
or the raw id (a bearer-grade secret) (REQ-OBS-004); the two lines line up by the `handoffId` hash,
so a **matching** `sub` hash with an absent key indicates expiry/consumption while a **differing**
`sub` hash indicates a subject mismatch between the device-grant token and the browser session. The
same line carries the staged draft's **length** — the cheapest possible answer to "the pre-filled
form came up empty", since a two-byte draft is an empty backend response while a plausible size
moves the search to the consume side. The draft itself is never logged.

**An unreachable Redis is an availability event, not an application fault.** Any `DataAccessException`
from the staging write is answered with a retryable **`503`** carrying `Retry-After` (code
`SERVICE_UNAVAILABLE`), logged at `WARN` with the exception class only — never the Lettuce message,
which names the configured endpoint — and counted on
`basetool_ingest_handoff_errors_total{reason="staging_unavailable"}` plus
`basetool_http_error_total{code="SERVICE_UNAVAILABLE"}`. It previously fell through to the generic
catch-all, which handed the caller a non-retryable `500` for an outage that self-heals in seconds and
logged `ERROR "Unexpected ingest failure"` with a stack trace — indistinguishable from a genuine code
defect, and enough to trip `LogbackErrorSpike` (REQ-OBS-013). The separate `reason` matters
operationally: the backend relay has already **succeeded** at that point, so the fix is Redis, not the
backend — which is why it gets its own `IngestStagingUnavailable` alert rather than only the
by-reason `IngestHandoffErrors` threshold. This mirrors the treatment
`IdentityProviderUnavailableFilter` gives an unreachable Keycloak (REQ-SEC-024).

**Acceptance**

- [x] A handoff id is unguessable and bound to the creating `sub`; reading under another
  `sub` returns not-found.
- [x] A second read of the same id after a successful first read returns not-found
  (single-use).
- [x] An entry past its TTL is gone; no draft is returned and no error leaks its prior
  existence.
- [x] A Redis outage during staging yields a `503` with `Retry-After` and a `WARN`, not a `500` with
  an `ERROR` stack trace, and is counted under its own `staging_unavailable` reason.

**Enforced by:** `HandoffStagingServiceTest` (Testcontainers Redis: stage + consume-once, a
foreign-`sub` read returns empty without deleting, an unknown id returns empty, the log line carries
`draftLen` but neither the draft nor the raw ids), `GlobalExceptionHandlerTest`
(`DataAccessException` → 503 + `Retry-After` + both counters, `WARN` without the endpoint), frontend
`IngestHandoffServiceTest` (single-use consume, per-`sub` scoping, kind match) · **Code:**
`HandoffStagingService`, `StagedHandoff`, `IngestProperties#handoffTtl`,
`GlobalExceptionHandler#handleStagingUnavailable`, `IngestStagingUnavailable` alert · **Issues:** #642

### REQ-INGEST-004 — Browser pre-fill, review-before-commit preserved

The extractor opens the matching basetool page with `?handoff=<id>`
(`/refinery-orders/create?handoff=<id>` and the blueprint equivalent). If the user has no
frontend session, the existing OAuth2 login + saved-request replay returns them to that URL
after authenticating. The frontend reads the staged draft for `(session sub, handoffId)`
exactly once and pre-fills the **existing** review form (REQ-REFINERY-014/-015 for
refinery; the blueprint preview surface for blueprints). Saving goes **exclusively** through
the unchanged create path — the ingest path adds no new persistence and does not alter the
create flow. A missing, expired, consumed, or foreign-`sub` handoff degrades to the normal
empty create form plus a localized, KRT-styled inline notice (no native dialog,
REQ-UI-008); it never errors the page out.

**The navigational pre-fill GET is a _safe_ request and MUST NOT consume the handoff.** Consuming
the single-use pickup is a state-changing operation, so it may not ride the cacheable/prefetchable
page navigation. The pre-fill page GET renders the empty owner-prefilled form and merely carries the
pending handoff id to its page module; the module then performs the one-time consume via an
**explicit, script-initiated request that a page prefetch never issues** —
`POST /refinery-orders/import-handoff` for refinery (swaps the `refineryImportFormBody` fragment in
place, REQ-FE-005), `POST /personal-inventory/blueprints/import/staged` for blueprints (renders the
import modal) — and swaps the result in without a reload. This makes the flow robust to a browser
that speculatively **prefetches** or issues a **duplicate top-level GET** of the pre-fill URL: a
prefetch fetches inert HTML and runs no script, so it cannot consume, and only the real navigation's
one consume request burns the token. This closes the **2026-07-19 incident**: a member's Firefox
loaded `…/create?handoff=<id>` twice ~250&nbsp;ms apart (empty referer, identical UA — a client-side
prefetch/duplicate-load), the first destructive GET consumed the token and the second rendered
`ingest.handoff.notFound` on **every** send, while the manual `POST` upload (never duplicated) always
worked; a Chrome client that issued a single GET succeeded. The two-page-GET fragility was the
navigational-GET consume, not the TTL (REQ-INGEST-003) or a subject mismatch — both ruled out by the
masked stage/consume correlator (matching `sub` hash, miss ~2&nbsp;s after staging).

**Acceptance**

- [x] Opening `…/create?handoff=<valid id>` while logged in renders the pre-filled review
  form; the user must still click Save to persist.
- [x] Opening it without a session triggers login and lands back on the pre-filled form.
- [x] An expired/consumed/foreign/unknown handoff renders the normal empty form with an
  inline notice — no stack trace, no persisted data.
- [x] The navigational pre-fill GET (`GET …/create?handoff=<id>`, `GET …/blueprints?handoff=<id>`)
  does **not** consume the handoff — a speculative prefetch or a duplicate top-level load of the URL
  leaves the token intact; only the page's explicit `POST` consume request (`…/import-handoff`,
  `…/import/staged`) deletes it, exactly once.

**Enforced by:** `IngestHandoffServiceTest` (graceful degradation on miss / expired / foreign-`sub` /
wrong-kind), `RefineryOrderHandoffMvcTest` (the GET carries `pendingHandoffId` and never invokes
`IngestHandoffService.consume`; the `POST /refinery-orders/import-handoff` consume + not-found
fragment), `PersonalBlueprintImportProxyControllerTest` (the `POST …/staged` hit + 404 miss),
`IngestHandoffE2eTest` (end-to-end pre-fill via the in-place consume + login-replay landing +
single-use) · **Code:** `RefineryOrderPageController#viewCreateForm` (GET carries `pendingHandoffId`,
never consumes) + `#importHandoff` (the `POST` consume + swap), `refinery-orders-create.js`
(`_loadRefineryHandoff`), `PersonalBlueprintImportProxyController#staged` (now `POST`),
`personal-inventory-blueprints-import.js` (`loadHandoff`), `IngestHandoffService`,
`ingest.handoff.notFound` (DE/EN inline notice) · **ADR:** [ADR-0110](../adr/0110-ingest-handoff-consume-off-navigational-get.md)
· **Issues:** #644

### REQ-INGEST-005 — Size and rate limits

The gateway caps each ingest payload at the same ceiling the existing frontend proxy uses
(2 MB — a real extract is a few KB) and rejects larger bodies before forwarding. The cap is
enforced on the **real** body size, not just a declared `Content-Length`: a chunked request
(no `Content-Length`) is counted while reading and rejected the moment it crosses the cap, so
it cannot be used to slip an oversized body past the guard (`PayloadSizeLimitFilter`).

Ingest calls are rate-limited **per `sub` and per source IP** so the new ingress cannot be
used to hammer the backend import endpoints. The per-`sub` limit (`SubjectRateLimiter`,
invoked from `IngestService` inside the authenticated context) is the enforceable control —
it keys on the unforgeable JWT subject. The per-IP limit (`RateLimitingFilter`) is a coarse
pre-auth front line; the source IP is resolved through Tomcat's `RemoteIpValve`
(`forward-headers-strategy: native`), which honours `X-Forwarded-For` only from a trusted
internal proxy, so an external client cannot trivially mint a fresh budget by spoofing the
header. Both bucket maps are bounded (LRU, capped key count) so neither grows without limit
under key churn. Defensive payload caps inherited from the backend DTOs
(`REQ-REFINERY-001` envelope limits) still apply at the backend; the gateway does not relax
them.

**Acceptance**

- [x] A body over the size cap is rejected by the gateway with a localized problem response
  and is never forwarded — including a chunked body with no `Content-Length`.
- [x] A burst of ingest calls from one `sub` is throttled with a `Retry-After`, not passed
  straight through; rotating the source IP does not defeat the per-`sub` limit.

**Enforced by:** `FiltersTest`, `SubjectRateLimiterTest` · **Code:** `PayloadSizeLimitFilter`,
`SubjectRateLimiter`, `RateLimitingFilter` · **Issues:** #642, security audit
INGEST-DOS-1 / INGEST-RATELIMIT-1

### REQ-INGEST-006 — Egress is opt-in; the CLI stays offline

Data leaves the user's machine **only** when the user explicitly clicks Send in the
extractor GUI. There is no background sync, no auto-send, and no telemetry. The extractor's
CLI / offline mode never transmits. The "nothing leaves your machine" promise in the
extractor's documentation is reconciled to state precisely that the locally-produced JSON is
transmitted to the basetool **only on an explicit Send**, and that screenshots/images never
leave the machine (ADR-0007).

**Acceptance**

- [x] No extractor code path transmits the extract without an explicit user Send action.
- [x] The CLI path performs no network egress of extract data.
- [x] The extractor docs describe the egress accurately (no remaining absolute
  "nothing-leaves" claim).

**Enforced by:** verified in the `basetool-bp-extractor` repo (the extractor internals are out of
scope here — see *Out of scope*) · **Code:** the extractor's explicit Send action (#645) and the
"nothing-leaves" docs reconciliation (#646), both in the extractor repo · **Issues:** #645, #646

### REQ-INGEST-007 — "Remember me" token storage & revocation

If the user opts into "remember me", the extractor persists the device-grant **refresh
token** in the Windows Credential Manager (DPAPI) — never in plaintext on disk, never in a
log. Refresh-token rotation is used with reuse-detection (a replayed old refresh token
invalidates the session). The extractor offers an in-app "Vom Basetool trennen" action that
revokes the token at Keycloak and clears the stored credential. Tokens, refresh tokens and
the user's name/email are never logged (project-wide logging rule).

**Acceptance**

- [x] With "remember me" off, no refresh token is persisted; a new send re-runs the device
  approval.
- [x] With it on, the refresh token is stored via DPAPI and a second send needs no
  re-approval until expiry/revocation.
- [x] "Vom Basetool trennen" revokes at Keycloak and removes the stored credential; a
  subsequent send requires re-approval.
- [x] No token or refresh token appears in any log line.

**Enforced by:** verified in the `basetool-bp-extractor` repo (extractor internals out of scope here)
· **Code:** the extractor's DPAPI refresh-token store with rotation/reuse-detection and the "Vom
Basetool trennen" revoke action (#648), in the extractor repo · **Issues:** #648

### REQ-INGEST-008 — No new role; backend stays internal; audience sequencing

Direct ingest introduces **no new Keycloak role or Spring authority** — it is
`isAuthenticated()` end to end (ROLES_AND_PERMISSIONS.md unchanged). The backend remains
internet-unreachable; only the gateway is published. If/when the backend's opt-in audience
check (`app.security.jwt.expected-audiences`) is enabled, the `aud=basetool-backend` audience
mapper must already be emitting on **both** token sets — the new client's `extractor-ingest`
scope **and** the existing frontend client's scope — or enabling it rejects every frontend
token. Adding the mappers (and verifying both token sets carry the claim) must therefore
precede turning the check on, in that order.

**Acceptance**

- [x] No new role/authority appears in `ROLES_AND_PERMISSIONS.md` for ingest.
- [x] Enabling `app.security.jwt.expected-audiences` is gated on both clients already
  emitting `aud=basetool-backend` (documented runbook step, #641).
- [x] The sequencing is rehearsed rather than first attempted in prod: the E2E realm stamps
  `aud=basetool-backend` on its frontend client and the E2E backend runs with the check ON, so
  "mapper present → tokens accepted" is proven on every e2e-labelled PR (#1247). This covers the
  backend half only — the gateway is not in the E2E stack, and the deployed realm's mappers still
  have to be confirmed on the host before the prod flip.

**Enforced by:** `SecurityConfigTest` (the audience validator), `ArchitectureTest` (every surface is
authorization-annotated; the gateway adds no new authority) · **Code:** ingest `SecurityConfig`, the
backend `SecurityConfig` audience knob; Keycloak realm config per
[`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md); `ROLES_AND_PERMISSIONS.md` unchanged ·
**Issues:** #641

### REQ-INGEST-009 — Bot / scanner hardening at the edge

The gateway is the only new internet-reachable surface (`REQ-INGEST-001`), so it is a constant
target of automated scanners probing for WordPress, PHP, Actuator, WebDAV and hidden-config paths.
A pre-security `BotProtectionFilter` (the gateway mirror of the frontend filter) rejects these
before they reach Spring Security — so a scan never spins up the resource-server bearer-token filter
or an identity-provider round-trip — using three fixed, case-insensitive strategies:

- **Disallowed HTTP method → 405.** The gateway only ever uses `GET` (actuator / api-docs), `POST`
  (the two `/v1` ingest endpoints), `HEAD` (health probes) and `OPTIONS` (CORS preflight). Every
  other method — `PUT`/`DELETE`/`PATCH` verb-tampering, `TRACE`/`CONNECT`, WebDAV `PROPFIND`/`MKCOL`/…
  — is refused. This method set is deliberately narrower than the frontend's.
- **Known bot/scanner path prefix → 404** (`/wp-*`, `/.env`, `/phpmyadmin`, `/actuator/env`, …).
- **Never-served file extension → 404** (`.php`, `.asp`, `.sql`, `.env`, …).

The filter runs after `CorrelationIdFilter` (a blocked request is still correlation-tagged) and
before the size-cap, rate-limit and Spring Security filters. The gateway's real surface — `/v1/**`,
`/actuator/health` (+ liveness/readiness), `/actuator/prometheus` (exact match → the fail-closed
scrape chain still runs) and `/v3/api-docs` (non-prod) — is never blocked. Each reject bumps
`basetool_bot_blocked_total{rule}` (bounded `rule` ∈ {`method`, `path_prefix`, `file_extension`};
never the URI or method — `REQ-OBS-006/-011`), shared with the frontend counter and distinguished by
the `application` common tag; it makes the otherwise `log.debug`-only rejects visible and surfaces a
self-inflicted false positive if a future legit route matches a blocked prefix.

**Acceptance**

- [x] A request for a known bot path (`/wp-admin/…`, `/.env`, `/actuator/env`, …) or a never-served
  file extension is answered 404 without reaching the security chain; a disallowed method is
  answered 405.
- [x] The gateway's real surface (`/v1/**`, `/actuator/health*`, `/actuator/prometheus`,
  `/v3/api-docs*`) passes the filter unchanged.
- [x] Every reject increments `basetool_bot_blocked_total` under its bounded `rule` tag and nothing
  else (no URI/method label).

**Enforced by:** `BotProtectionFilterTest` (path-prefix / file-extension 404, disallowed-method 405,
real-surface pass-through incl. `/v3/api-docs` and the exact-match prometheus whitelist, per-`rule`
counter) · **Code:** `BotProtectionFilter`, `MetricNames` (`BOT_BLOCKED` + `rule` values);
`observability.md` (`REQ-OBS-011`), the "Bot-blocked/hour by rule" panel in
[`07-basetool-operations.json`](../../monitoring/grafana/dashboards/07-basetool-operations.json) ·
**Issues:** #1202

### REQ-INGEST-010 — Published API contract for the extractor

The gateway's two endpoints are the contract a **separately developed, separately released** client
(the `basetool-bp-extractor` desktop app) codes against, so that contract is published as a
committed OpenAPI document — `ingest/src/main/resources/api/openapi.json`, the module's single
API-documentation artifact, regenerated by `OpenApiGeneratorTest` exactly like the backend's
(`api-conventions.md`, `REQ-API-007`). Without it the extractor's authors had only the source to
read, and a breaking change to the envelope was invisible until the next release.

The document declares the `bearer-jwt` security scheme (`REQ-INGEST-002`), the full `RefineryExtract`
request schema with its bean-validation bounds, the opaque JSON-object body of the blueprint
preview, the `IngestResponseDto` handoff, and every status the gateway can answer with — including
the `413` size cap and `429` throttle of `REQ-INGEST-005`, which a client has to handle and which no
generated client would otherwise know about. As in the backend, springdoc `-api` is used (no Swagger
UI webjar) and `springdoc.api-docs.enabled=false` in `application-prod.yml` keeps `/v3/api-docs`
unreachable from a deployed environment; the committed file is the contract, not a live endpoint.

**Acceptance**

- [x] `ingest/src/main/resources/api/openapi.json` is committed and matches the live SpringDoc
  output for the current controllers.
- [x] The document carries the `bearer-jwt` scheme, both `/v1` paths, and the request/response
  schemas; a controller that stops being scanned fails the generator rather than shrinking the spec.
- [x] `/v3/api-docs` is reachable without authentication in non-prod and 404s in prod.

**Enforced by:** `OpenApiGeneratorTest` (regeneration + structural assertions), `IngestControllerTest`
(`/v3/api-docs` is permitted and titled) · **Code:** `ingest/.../config/OpenApiConfig`,
`IngestController` (`@Operation`/`@ApiResponses`/`@Tag`), `IngestResponseDto` (`@Schema`),
`application-prod.yml`

### REQ-INGEST-011 — Client-identity gate: approved clients only

The ingest interface is restricted to client software the basetool developer (@greluc) has
explicitly approved. This is a control over **which program** calls the gateway; it does **not**
change who may use it — that stays `isAuthenticated()` for every member (`REQ-INGEST-002`/`-008`,
unchanged). Every member may upload blueprints and refinery jobs **with the approved extractor**.

Four checks, each **inert until configured** and each **fail-closed** once it is:

|     Check     |                                                                                                                          Source                                                                                                                           |                     Config                      |
|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|
| Client id     | JWT `azp` against an allowlist                                                                                                                                                                                                                            | `app.ingest.client-identity.allowed-client-ids` |
| Capability    | `SCOPE_<value>` authority from the `scope` claim — must name a scope **only** the extractor carries **and** one that is emitted into the claim; the shipped `extractor-ingest` fails both (shared with the frontend, and `include.in.token.scope: false`) | `…required-scope`                               |
| Producer      | payload `tool` field against an allowlist                                                                                                                                                                                                                 | `…allowed-tools`                                |
| Token binding | DPoP scheme required (`REQ-INGEST-012`)                                                                                                                                                                                                                   | `…dpop-required`                                |

A missing claim is refused exactly like an unknown one: treating "no `azp`" as "nothing to check"
would silently disable the gate the moment a realm change stopped stamping it. The reject reasons
stay distinct (`unknown_client`, `missing_azp`, `missing_scope`, `bad_provenance`, `dpop_required`)
because they split into two operationally opposite causes — a foreign tool calling, versus a Keycloak
mapper regression locking the legitimate extractor out.

**Nothing carries a default.** The allowlist contents are operational configuration, not source: the
code shows *that* a gate exists, the environment decides *who* passes it. Empty-means-inert is
load-bearing, not laziness — the Keycloak-side mappers and scopes are an operator step that cannot be
done by a PR, so shipping these pre-enabled would reject every real extractor token on deploy. The
`audit-only` flag runs every configured check but never rejects, so the operator can measure the real
client population (`basetool_ingest_client_rejected_total` staying at zero) before enforcing — the
same sequencing discipline `REQ-INGEST-008` imposes on the audience validator.

**Honesty about what this achieves.** These gates **segment registered clients from one another**: a
frontend session token cannot drive the gateway, an approved integration is individually scoped and
individually revocable, and a non-member is excluded entirely. They are **not** anti-tamper. The
extractor is a public OAuth client (`REQ-INGEST-002`) whose client id is readable both from the
distributed binary and from the wire during the device flow, so a member who deliberately reproduces
it obtains a token that passes every check here. Native-client attestation is not achievable on
Windows — there is no App Attest / Play Integrity equivalent for a Win32 binary — and any secret
embedded to compensate would be extractable, which is why none is used. The design therefore leans on
**containment** (the ingest path persists nothing, `REQ-INGEST-004`, so a foreign caller can do
nothing a browser upload could not) and on **visibility** (`IngestUnknownClient`) rather than on a
prevention claim that would not hold.

The payload `tool` check is the weakest of the four and is telemetry with a reject attached, never
authentication: the field is client-supplied and the contract that documents it is published.

**Acceptance**

- [x] With nothing configured the gate is a no-op; a build that ships it does not reject any token.
- [x] A token whose `azp` is not on the allowlist is refused `403 CLIENT_NOT_ALLOWED` and never
  reaches the backend relay.
- [x] A token with **no** `azp` at all is refused under its own `missing_azp` reason.
- [x] A token lacking the configured ingest scope is refused.
- [x] A payload whose `tool` is absent or unknown is refused under `bad_provenance`.
- [x] Under `audit-only` every one of the above is served but still counted and logged at `WARN`.
- [x] The `client_id` metric label never carries a raw token claim — it is an allowlist entry or the
  bounded `other` literal.
- [x] The rejected `tool` is `LogSafe`-sanitized before logging and is never echoed to the caller.

**Enforced by:** `ClientIdentityFilterTest` (all four checks, fail-closed on absent claims, audit-only,
bounded label, unauthenticated pass-through), `ProvenanceGuardTest` (allowlist, absent producer,
audit-only, log sanitisation, no echo-back) · **Code:** `ClientIdentityFilter`,
`ClientIdentityProperties`, `ProvenanceGuard`, `Provenance`, `ClientNotAllowedException`,
`MetricNames` · **Monitoring:** `basetool_ingest_client_total{client_id}`,
`basetool_ingest_client_rejected_total{reason}`, alert `IngestUnknownClient`

### REQ-INGEST-012 — DPoP: sender-constrained tokens

The gateway validates DPoP-bound access tokens (RFC 9449) via Spring Security's
`DPoPAuthenticationProvider`, enabled explicitly through the resource-server DSL (`dPoP(...)` — it is
**not** implied by `jwt(...)`). Keycloak has supported DPoP as a non-preview feature since 26.4.

The motivation is not impersonation — a DPoP key is generated by the client, so a hand-rolled tool
makes its own. It is **token theft**: the extractor persists a refresh token on the user's machine
(`REQ-INGEST-007`), and sender-constraining makes a leaked token useless without the matching private
key. That is the concrete risk when data flows through unvetted software.

**Dual-mode is mandatory, not a convenience.** The desktop extractor sends
`Authorization: Bearer` today, so `dpop-required` **must** stay `false` until it ships DPoP support;
enabling it earlier breaks every send on deploy. Validation of a presented DPoP token is active
regardless, so the migration window costs nothing — the flag only decides whether a plain bearer is
still *accepted*, i.e. it closes the downgrade path once the client population has migrated.

Independently of the flag, a **downgrade** is always reported: a token carrying the RFC 7800
`cnf.jkt` confirmation presented under the plain bearer scheme is logged at `WARN`, because a client
holding a bound token demonstrably has the key, so falling back to bearer is either a client bug or a
replay of a token lifted from elsewhere.

#### What actually validates a proof, and what protects against replay

Spring composes the proof check in `DPoPProofJwtDecoderFactory` out of `JwtClaimValidator`s for
`htm` / `htu` / `ath` / `jkt` plus a `JtiClaimValidator`. **Replay protection rests entirely on that
`jti` validator**, and its properties are worth knowing before anyone reasons about the guarantee:

- the seen-`jti` store is a **static** (JVM-wide) `LinkedHashMap` LRU capped at **`MAX_SIZE = 1000`**,
- with a freshness window on a **`ChronoUnit.HOURS`** scale — wide by RFC standards.

Neither is a problem here, but for a specific reason worth writing down rather than rediscovering: a
replayed proof is only useful together with the access token it is bound to, and that token lives
~5 minutes. The access token's lifetime, not the proof's window or the cache's depth, is what bounds
the exposure. The cache being per-JVM and cleared on restart is likewise harmless for the same reason
(one `ingest` container, and a restart inside a 5-minute token window changes nothing an attacker
could already do with the token itself).

#### Verified negative: no nonce on either side (2026-08-03)

RFC 9449 §8/§9 let an authorization server *or* a resource server demand a `DPoP-Nonce` and signal it
with `use_dpop_nonce`. **Neither happens here**, and this is recorded because the natural assumption
is the opposite:

- **Spring Security 7.1.0 has no nonce implementation at all** — verified by scanning every
  `spring-security-*-7.1.0` jar for `use_dpop_nonce` / nonce handling: zero occurrences. The gateway
  therefore can never issue a nonce challenge.
- **Keycloak does not appear to emit `use_dpop_nonce`** either (reported from the extractor side; not
  independently verified here, so treat this half as a strong indication rather than proof).

Consequence for clients: a nonce-retry path is **unreachable against this gateway**. Keeping one is
defensible as forward compatibility, but it is then untested code that would execute for the first
time during an incident — so it should carry a test that drives a synthetic `400`/`401` +
`DPoP-Nonce`, or be dropped in favour of surfacing the error loudly. What must *not* happen is the
middle option: retained, never exercised, and assumed to work.

**Trigger to revisit:** Spring Security gaining resource-server nonce support. Until then this
paragraph is the answer, and re-deriving it costs an afternoon.

The client-side failure modes that *are* reachable, and that deserve the attention the nonce does
not: a **`jti` must be unique per proof** (a cached or reused proof is refused by the validator
above), and the **client clock must fall inside the `iat` window** — a badly skewed machine fails
every send.

**Acceptance**

- [x] A plain bearer request is unaffected while `dpop-required` is false (dual mode).
- [x] With `dpop-required` enabled, a plain bearer request is refused under `dpop_required`.
- [x] A DPoP-scheme request is accepted, with the scheme compared case-insensitively (RFC 9110).
- [x] A `cnf.jkt` token presented as a plain bearer is logged as a downgrade.

**Enforced by:** `DpopResourceServerTest` — the protocol-level proof: a **real** ES256-signed
`dpop+jwt` with `htm`/`htu`/`iat`/`jti`/`ath` and a JWK thumbprint matching the token's `cnf.jkt` is
accepted, while a proof signed by a different key, one bound to a different URL, and a DPoP-scheme
request with no proof are each refused. The acceptance case cannot pass vacuously: with DPoP
inactive, a `DPoP`-scheme request is never authenticated at all and would answer 401, so a 200 can
only mean the proof was really validated. Additionally `ClientIdentityFilterTest` (dual mode,
`dpop-required` enforcement, case-insensitive scheme, downgrade warning) and `IngestControllerTest`
(the suite runs through the real filter chain with `dPoP(...)` enabled, so bearer behaviour is proven
unchanged — wiring evidence, with the JWT decoder mocked and no Keycloak involved) ·
**Code:** ingest `SecurityConfig`, `ClientIdentityFilter` · **Open cross-repo item:** the extractor
must send DPoP proofs before `dpop-required` can be enabled (`basetool-sc-extractor` repo).

## Out of scope

- The desktop extractor's internals (device-flow UI, token store implementation) — they live
  in the `basetool-bp-extractor` repo; this spec governs the basetool-side contract and the
  cross-repo expectations (#645/#646/#648 track the extractor work).
- Server-to-server / unattended ingest (no user in the loop) — explicitly excluded: ingest is
  always tied to a `sub` and a browser review step. There is no `client_credentials` path
  (ADR-0018).
- New import *semantics*. Matching, validation, draft shape and the create path are
  unchanged (ADR-0008, `REQ-REFINERY-002`); ingest only changes how the draft request is
  delivered.

## Open questions

None outstanding — epic #639 has shipped. The two questions from the decision gate were both
resolved during implementation: the blueprint-preview forwarding shape in #642 (the gateway
forwards, it does not reshape the contract, ADR-0008) and the hostname / NPM proxy entry + CI
deployment shape in #643.

