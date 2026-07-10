> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-27.
> **Owner area:** INGEST · **Related ADRs:** [ADR-0018](../adr/0018-desktop-ingest-gateway-device-grant.md) · **Related:** epic [#639](https://github.com/krt-profit/basetool/issues/639), runbook [`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md), [`refinery-screenshot-import.md`](refinery-screenshot-import.md) (`REQ-REFINERY-018`), [`security-and-access.md`](security-and-access.md), [`api-conventions.md`](api-conventions.md), [ADR-0007](../adr/0007-client-side-vlm-screenshot-extraction.md), [ADR-0008](../adr/0008-refinery-extract-json-contract.md)

# Desktop one-click ingest (send-to-basetool)

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
scope, #641); the **same** bearer is forwarded to and accepted by the backend. No separate
ingest audience is provisioned — the gateway only relays to the backend, so it accepts the
same `basetool-backend` audience the backend requires. All data is scoped to the token's
`sub`; the gateway never acts for a different user.

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
entropy). The entry has a short TTL (~5 minutes) and is **single-use**: the first successful
read for the correct `sub` consumes (deletes) it. A second read, a wrong `sub`, an expired
entry, or an unknown id all return "not found" with no draft. No screenshots and no raw
image bytes are ever staged — only the already-matched draft DTO (ADR-0007/0008: images
never leave the machine).

**Acceptance**

- [x] A handoff id is unguessable and bound to the creating `sub`; reading under another
  `sub` returns not-found.
- [x] A second read of the same id after a successful first read returns not-found
  (single-use).
- [x] An entry past its TTL is gone; no draft is returned and no error leaks its prior
  existence.

**Enforced by:** `HandoffStagingServiceTest` (Testcontainers Redis: stage + consume-once, a
foreign-`sub` read returns empty without deleting, an unknown id returns empty), frontend
`IngestHandoffServiceTest` (single-use consume, per-`sub` scoping, kind match) · **Code:**
`HandoffStagingService`, `StagedHandoff`, `IngestProperties#handoffTtl` · **Issues:** #642

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

**Acceptance**

- [x] Opening `…/create?handoff=<valid id>` while logged in renders the pre-filled review
  form; the user must still click Save to persist.
- [x] Opening it without a session triggers login and lands back on the pre-filled form.
- [x] An expired/consumed/foreign/unknown handoff renders the normal empty form with an
  inline notice — no stack trace, no persisted data.

**Enforced by:** `IngestHandoffServiceTest` (graceful degradation on miss / expired / foreign-`sub` /
wrong-kind), `IngestHandoffE2eTest` (end-to-end pre-fill + login-replay landing) · **Code:**
`RefineryOrderPageController#applyRefineryHandoff`, `PersonalBlueprintImportProxyController`,
`IngestHandoffService`, `ingest.handoff.notFound` (DE/EN inline notice) · **Issues:** #644

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

