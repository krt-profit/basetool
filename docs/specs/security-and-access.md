> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-02.
> **Owner area:** AUTH/SEC · **Related ADRs:** [ADR-0001](../adr/0001-frontend-confidential-oauth2-client.md) · **Role matrix:** [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)

# Security & access control

## Context & goal

Both modules run Spring Security on Keycloak OIDC. Authorization is centralised and
enforced architecturally so business logic never carries ad-hoc checks, and every
read/write is isolated to the calling user unless the caller is privileged.

## Requirements

### REQ-SEC-001 — OIDC topology

The backend is a **resource server** (validates the JWT); the frontend is an **OAuth2
client** (browser SSO + bearer-token relay to the backend).

### REQ-SEC-002 — Centralised authorization

Authorization lives in `@PreAuthorize` annotations on services/controllers — never in
business logic. Roles mapped from the JWT are prefixed `ROLE_` and uppercased.

Filter-level rejections (a missing/invalid bearer token, an access-denied verdict at the
authorization filter) render the same RFC 7807 `application/problem+json` as method-security
denials: `SecurityConfig` wires `SecurityProblemResponseHandler` as both the
`AuthenticationEntryPoint` and the `AccessDeniedHandler` (globally and on the resource server),
which delegates to the MVC `handlerExceptionResolver` so `GlobalExceptionHandler` emits the 401
(`UNAUTHENTICATED`) / 403 (`ACCESS_DENIED`) with a `code` and a `correlationId` — never Spring's
default bare `WWW-Authenticate`-only 401 or empty-body 403 (see
[`api-conventions.md`](api-conventions.md) REQ-API-004).

### REQ-SEC-002a — Central role/permission constants (backend)

Role codes (`Role.code`, matching the Keycloak realm role names minus their `ROLE_` prefix) and
the fine-grained permission strings a role's `permissions` collection carries are centralised in
`support.Roles` / `support.Permissions` (S3, #909) rather than repeated as raw string literals.
`SecurityConfig` (the `roleHierarchy()` chain and every `hasRole`/`hasAnyRole`/`hasAuthority`/
`hasAnyAuthority` call in the `authorizeHttpRequests` matrix — these are plain Java method calls,
not SpEL, so passing a `String` constant is a zero-risk substitution) and `DataInitializer` (the
seeded role/permission values) are migrated. `Roles.authority(String)` derives the `ROLE_`-prefixed
Spring-authority form for the few call sites that need it (the hierarchy chain,
`hasAnyAuthority(...)` mixing a role into a permission list) instead of a duplicated `ROLE_*`
constant per role. `LOGISTICIAN` / `MISSION_MANAGER` are hierarchy-derived only — never seeded in
`Role` — and stay documented as such on `Roles`. Both constant holders live in the dependency-leaf
`support` package (ADR-0047): plain `String` constants with no dependency on the security API.

Every literal-role `@PreAuthorize` expression is migrated too — **266 sites across 96 backend +
frontend controller/service files** (153 backend, 113 frontend; a ground-truth grep sweep, not the
issue's original ~158/~322 estimates). Each `hasRole('X')` / `hasAnyRole('X','Y',…)` /
`hasAuthority('X')` / `hasAnyAuthority('X','Y',…)` single-quoted literal is spliced into a
compile-time-constant string-concatenation expression, e.g. `hasRole('ADMIN')` becomes `hasRole('" +
Roles.ADMIN + "')`. This is safe by construction: `"literal" + Roles.X + "literal"` is itself a Java
compile-time constant (JLS 4.12.4 / 15.28 — a `public static final String` field initialized from a
literal, referenced from another compilation unit, is a constant expression), so javac folds it to
the byte-identical original string before the annotation is even written to the class file — the
wire behavior, and everything ArchUnit's `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` /
`writeEndpointsMustDeclareAnAuthorisationAnnotation` inspect via the resolved annotation value, is
unchanged. Bean-method-only expressions (`@ownerScopeService.canEdit*`, `isAuthenticated()`,
`permitAll()`) are untouched — only the literal-role/permission subset was in scope, matching the
`@PreAuthorize` value's constant-concatenation seam so a role literal can sit alongside an untouched
bean-method call in the same string (e.g. `hasRole('" + Roles.LOGISTICIAN + "') and
@ownerScopeService.canEditJobOrder(#id)`).

On the frontend, `frontend.support.Roles` mirrors the backend's bare role codes (the frontend cannot
depend on the backend's Java classes — separate Gradle module, bearer-token relay only — so the
values are intentionally duplicated and must stay byte-identical). `FrontendAuthHelperService` and
every frontend `@PreAuthorize` with a literal role are migrated the same way.

**Follow-up completed (originally deliberately out of scope in PR #931):**

- **Programmatic authority-string comparisons** outside `@PreAuthorize` — backend
  `AuthHelperService.hasReachableRole(...)` call sites (`BankSecurityService`,
  `MissionSecurityService`, `OwnerScopeService`, `OrgUnitBankAccessService`) and frontend raw
  `getAuthority().equals("ROLE_X")` / `"ROLE_X".equals(...)` checks (`BackendRoleSyncFilter`,
  `InventoryPageController`, `JobOrderPageController`, `OperationPageController`,
  `RefineryOrderPageController`) now reference `Roles`/`Permissions` constants and the
  `Roles.authority(String)` helper (including the two dynamic `"ROLE_" + grant.getRoleCode()` sites
  in `OrgUnitBankAccessService`, which became `Roles.authority(grant.getRoleCode())`). Same shape
  as the `@PreAuthorize` sweep, without the annotation compile-time-constant constraint.
- **Thymeleaf `sec:authorize="hasRole('X')"` template attributes** (134 occurrences across 27
  templates at time of migration) are migrated to reference `frontend.support.Roles` via the SpEL
  `T()` type-reference operator, e.g. `hasRole('ADMIN')` →
  `hasRole(T(de.greluc.krt.profit.basetool.frontend.support.Roles).ADMIN)`. `sec:authorize`
  evaluates through the same unrestricted `StandardEvaluationContext` as `@PreAuthorize` (verified
  against the resolved `thymeleaf-extras-springsecurity6` / `spring-security-web` sources), so no
  new Thymeleaf expression-utility object or model binding was needed — see
  [ADR-0059](../adr/0059-thymeleaf-sec-authorize-role-constants-via-spel-type-operator.md) for the
  full rationale and the rejected bound-expression-object alternative.
- **Missed mixed-clause `@PreAuthorize` expressions closed (code-review follow-up).** The initial
  266-site sweep's mechanical splice reliably handled a `@PreAuthorize` body that was *only* a role
  check, but was inconsistent on a mixed expression combining a role with a bean-method call —
  exactly the `hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditJobOrder(#id)`
  shape this same section cites as in scope. A review pass found the raw-literal form still present
  in `BankBookingController` (deposit/withdraw/transfer), `JobOrderController` (7 handover/report/
  unlink endpoints), the org-role delegation cluster (`KommandoGroupController`,
  `OrgHierarchyController`, `SquadronRoleController`, `SpecialCommandMembershipController`),
  `RefineryOrderController`, `OperationController` and its frontend mirror
  `OperationPageController`, and the frontend `OrgUnitBankPageController`'s
  `MEMBER_OR_ABOVE` constant — all now migrated. The same pass also closed four service-layer raw
  role-code comparisons that sit outside `@PreAuthorize` entirely and were never covered by the
  original sweep or the programmatic-comparison follow-up above: `UserService` (including the
  Keycloak first-admin bootstrap auto-activation check), `BankGrantService`,
  `BankHolderReconciliationService` and `RecipientResolutionService`. Finally, the ~35 sites sharing
  the identical `hasAnyRole('ADMIN','OFFICER')` splice across the promotion/rank/evaluation surface
  now reference one pre-built compile-time-constant expression, `Roles.ADMIN_OR_OFFICER` /
  `frontend.support.Roles.ADMIN_OR_OFFICER`, instead of repeating the splice per call site.

### REQ-SEC-003 — Architectural invariants (ArchUnit-enforced)

The following must always hold and are enforced as ArchUnit rules in
[`ArchitectureTest`](../../backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java)
(backend) and the frontend equivalent — a new violation fails `./gradlew test`:

- No `SecurityContextHolder` use outside the auth-helper service.
- Every `@RestController` carries at least one `@PreAuthorize`.
- Controllers never return JPA entities (DTOs only — see [`api-conventions.md`](api-conventions.md)).
- No controller depends on `OrgUnitMembershipMapper` — the membership entity→DTO projection runs
  inside `OrgUnitMembershipService`'s own transactions, never controller-side after commit
  (`controllersMustNotInjectTheLazyMembershipMapper`, ADR-0067).
- Every org-unit bank **settings mutation** (`OrgUnitBankAccessService` public `set*`/`add*`/`remove*`/
  `clear*` method returning `OrgUnitBankAccountSettingsDto` — balance target, view-visibility grants,
  per-tier approval limits) invokes a `requireCan*` authorization helper
  (`orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper`). Those mutations are gated only in-body
  (the controller and frontend proxy require merely `isAuthenticated()`), so this rule fails a future
  mutation that drops the check — which would otherwise ship reachable by any authenticated member —
  at build time rather than in production (security review, INFO regression guard).
- The frontend does not depend on Spring Data JPA.

### REQ-SEC-004 — Roles & hierarchy

Roles: `ADMIN`, `OFFICER`, `LOGISTICIAN`, `MISSION_MANAGER`, `KRT_MEMBER`, `GUEST`.
Hierarchy: `ADMIN > LOGISTICIAN`, `ADMIN > MISSION_MANAGER`, `OFFICER > LOGISTICIAN`,
`OFFICER > MISSION_MANAGER`. The full matrix is authoritative in
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md).

### REQ-SEC-005 — Contextual LOGISTICIAN / MISSION_MANAGER grants

`LOGISTICIAN` and `MISSION_MANAGER` are granted contextually via `is_logistician` /
`is_mission_manager` flags on `org_unit_membership` rows; the flag on *any* membership
yields the flat authority via `CustomJwtGrantedAuthoritiesConverter`, with per-OrgUnit
scoping enforced by `@PreAuthorize` SpEL against `OwnerScopeService.canEditOrgUnit(...)`.
An SK `is_lead` membership additionally grants both roles (flat + contextual
`LOGISTICIAN@skId` / `MISSION_MANAGER@skId`) on that SK. Legacy `app_user.is_logistician`
/ `is_mission_manager` are read only as a fallback for users with no membership row;
dropped in the destructive cleanup release.

The flat authority is the OR-union over *all* of a caller's memberships and therefore carries
**no** OrgUnit context on its own — it MUST never authorise a write against another OrgUnit's
aggregate by itself. Every elevated-mission-role write path (mission edit, manager/owner change,
**and participant management** — check-in/out, attribute edit, payout-preference, removal) gates the
flat role behind `OwnerScopeService.canEditMission(...)`; `MissionSecurityService.canAccessParticipant`
does so by delegating its management branch to `canManageMission` rather than short-circuiting on the
bare `ROLE_MISSION_MANAGER` authority. (Unlinked **guest** participants stay openly editable per
REQ-SEC-009; this scope gate applies only to *user-linked* participants.)

**Authority resolution is memoised per token, not per request (#1141).** `CustomJwtGrantedAuthoritiesConverter`
runs on *every* authenticated API call, and each miss pays `UserService.syncUser` (a write-capable
transaction) plus ~5–8 SELECTs (user load, `user_roles`, one role lookup per realm role, and the
membership read). The assembled authority collection is memoised in-process keyed on `(sub, token
issuedAt)` for a short window (~30&nbsp;s), so that work is paid once per token issuance instead of on
every fragment refetch / live-sync burst / check-in. Keying on `issuedAt` makes a freshly issued token
(re-login, refresh) a distinct key and therefore a miss, so an authority change takes effect on
re-authentication; within one token's life a mid-token role / permission / approval / membership
change reflects after at most the cache TTL, and never longer than the token lifetime — the
login-path reconciliation is independent of, and far fresher than, the periodic drift-correction
`app.keycloak.sync` (daily 05:00), which only covers users who are not currently logging in. Only
successful results are cached; a token missing `sub`/`issuedAt` bypasses the cache and is always
recomputed.

> **Amended by epic #692 (REQ-SEC-015 / REQ-ORG-015):** Bereich/OL leadership memberships
> (`is_bereichsleiter`/`koordinator`/`operator`, `is_ol_member`) also mint the flat
> `LOGISTICIAN`/`MISSION_MANAGER` authorities **and** contextual `…@orgUnitId` authorities — but one per
> **descendant** Staffel/SK of the leader's scope (Bereich → its children; OL → all), so existing
> per-OrgUnit `@PreAuthorize` SpEL resolves for area leaders without change. This reach is concrete and
> scoped; it grants **no** admin rights.

### REQ-SEC-006 — Multi-user data isolation

Every read/write filters by JWT `sub` unless the caller has an elevated role (`ADMIN`,
`OFFICER`, …). **Enforce this in the service layer, not the controller.**

### REQ-SEC-007 — Guest minimisation & field redaction

For unauthenticated guests, return only the minimum required data. Sensitive fields
(email, real name, internal orders/items) MUST be explicitly cleared in the controller via
a `cleanup…ForGuest`-style helper to prevent information disclosure. (E-mail is shown only in
a user's own profile — never elsewhere.) Mission reads have **two** redaction tiers: a
member-peer tier (`cleanupMissionForGuest`, strips owner/managers/PII but keeps the roster
for a fellow member) and the **outsider** tier (`cleanupOutsiderMissionForGuest` = the
member-peer redaction plus the free-text description hidden) used for anonymous and GUEST
callers — see REQ-SEC-009. The naming convention
(`cleanup…ForGuest`) is enforced structurally by the ArchUnit rule
`anonymousReadableMissionEndpointsMustRedactGuestPii`.

### REQ-SEC-009 — Anonymous & guest-role access surface

The application has a deliberately public surface so requesters and visitors can interact
without a login. That surface is **minimal and identical for two cohorts** — *anonymous*
callers (no JWT) and the *GUEST role* (an authenticated Keycloak user with no member or
elevated authority). The discriminator is `AuthHelperService.isMemberOrAbove()` (true for
`ADMIN`/`OFFICER`/`MISSION_MANAGER`/`LOGISTICIAN`/`KRT_MEMBER`/`MEMBER`); its negation
is the **"mission outsider"** predicate. A GUEST is treated exactly like an anonymous
visitor on the mission surface — *behandle guest wie anonym bei den Einsätzen*.

What a mission outsider (anonymous OR GUEST) **may** do — and nothing more:

- **Orders:** create a job order only (`POST /api/v1/orders`, `/api/v1/orders/items`, plus
  the supporting `permitAll` catalog reads). They may **not** list, view, edit or delete
  orders. (This holds for GUEST too: a memberless account fails the profit-eligibility gate
  `canViewJobOrders`, exactly like an anonymous caller — see `org-unit-tenancy.md`.) A non-profit
  **member** (not a memberless guest) is the exception: they may view and limitedly edit the orders
  their own org unit requested — the requesting-owner escape (REQ-ORDERS-023, ADR-0091) — but still
  cannot browse the general queue or see other units' orders.
- **Missions (non-internal only):** see the mission detail in its **redacted** form, sign up
  as a participant, and edit / check-in / check-out / delete / change-payout-preference on
  **unlinked guest participants** (`participant.user == null`, which includes their own
  guest entry) via `MissionSecurityService.canAccessParticipant`. Internal and past
  (`COMPLETED`/`CANCELLED`) missions are not visible to outsiders.

The outsider mission detail (`MissionGuestRedactor.cleanupOutsiderMissionForGuest`) applies the
member-peer redaction (participant PII stripped to the public callsign tuple
username/displayName/rank; owner and managers cleared) and **additionally hides only the free-text
`description`**. The mission **economy** (inventory entries / refinery orders) is no longer part of
the `MissionDto` at all (#1138) — it is served member-gated at its own endpoints
(`/api/v1/inventory/mission/{id}`, `/api/v1/refinery-orders/mission/{id}`, both behind the member-role
filter) — so there is nothing economy-related on the outsider surface to redact. By explicit product decision an
outsider **does** see, on a non-internal mission, the owning **organisation**
(`owningSquadron`), the **participant roster** (PII-stripped) with each participant's
**payout preference**, the assigned **units** and the mission **frequencies**. PII (email,
real name) is never included — that is a non-negotiable invariant regardless of which fields
are shown.

The mission **finance ledger** (`GET`/`POST /api/v1/.../finance-entries`) is a separate
surface — the per-participant payout *preference* above is not the ledger — and stays
restricted to **registered members and above**: anonymous AND GUEST are blocked (create +
read). Finance-entry creation is therefore no longer anonymous.

**Acceptance**

- [ ] Anonymous and GUEST callers can `POST /api/v1/orders` (+`/items`) but receive empty
  list / 403 on every order read/edit/delete path.
- [ ] A mission outsider's `GET /api/v1/missions/{id}` on a non-internal mission returns a DTO
  with `description`, `owner`, `managers` null and no `inventoryEntries`/`refineryOrders` fields at
  all (#1138), but WITH the participant roster (PII stripped — no email/roles), `owningSquadron`,
  `assignedUnits` and `frequencies` present; internal/past → 403.
- [ ] An outsider can add and edit an unlinked guest participant; editing a *linked*
  participant they do not own → 403.
- [ ] Anonymous create on `POST /api/v1/finance-entries` → 401; GUEST → 403; member → 201.
  GUEST `GET /api/v1/missions/{id}/finance-entries` → 403.
- [ ] `AuthHelperService.isMemberOrAbove()` is false for anonymous and GUEST, true for every
  member/elevated role.

**Enforced by:** `MissionControllerLifecycleTest`, `MissionDataLeakTest`,
`MissionGuestAccessTest`, `MissionFinanceEntryControllerSecurityTest`, `AuthHelperServiceTest`,
`ArchitectureTest#anonymousReadableMissionEndpointsMustRedactGuestPii` · **Code:**
`MissionController`, `MissionFinanceEntryController`, `AuthHelperService`,
`SecurityConfig` · **Role matrix:** [`ROLES_AND_PERMISSIONS.md` §1](../../ROLES_AND_PERMISSIONS.md)

### REQ-SEC-008 — Frontend bot protection & silent re-auth

The frontend's `BotProtectionFilter` returns 404 directly for known scanner paths;
`SsoReAuthenticationEntryPoint` gives legitimate paths with expired sessions a silent
`prompt=none` Keycloak redirect.

### REQ-SEC-010 — AJAX CSRF token refresh endpoint

The frontend's session/meta CSRF setup is unchanged (`HttpSessionCsrfTokenRepository` +
`XorCsrfTokenRequestAttributeHandler`). An additive authenticated endpoint `GET /csrf` returns
`{headerName, token}` so the shared `krtCsrf` client (REQ-FE-004,
[`frontend-ajax-mutations.md`](frontend-ajax-mutations.md)) can self-heal a bare-403 write with a
single transparent token refresh + retry. The endpoint sits under the `authenticated()` catch-all —
an anonymous caller is redirected to the OIDC entry point, never handed a token — so it widens no
trust boundary and is not a change to the CSRF repository/handler (ADR-0012).

**Acceptance**

- [ ] `GET /csrf` returns the active header name + token for an authenticated session.
- [ ] `GET /csrf` does not serve a token to an anonymous caller.

**Enforced by:** `CsrfTokenControllerMvcTest` · **Code:** `CsrfTokenController` · **Issues:** #572

### REQ-SEC-011 — Rate-limit client-IP attribution

The backend's per-IP / per-endpoint rate limiter (`RateLimitingFilter`) is only meaningful if it can
tell clients apart. Because the backend is a pure resource server reached **only** server-side by the
frontend (no browser hits it directly), the frontend MUST relay the originating client IP on every
outbound backend call as `X-Forwarded-For` (`ClientIpRelayFilter`, snapshotted per request by
`ClientIpContextFilter` and carried across the Reactor hop via the registered `ThreadLocalAccessor`).
The backend honours `X-Forwarded-For` only from its configured `app.rate-limit.trusted-proxies` (the
frontend container) and reads the first hop as the client. Without the relay every per-IP bucket
collapses onto the single frontend container IP, so one caller can trip a public endpoint's budget for
the whole organisation. The relay never overwrites an existing header and degrades silently to
"frontend IP" for background tasks with no bound request.

**Spoofing-resistant attribution at the frontend edge (finding SEC-02).** The relay is only as
trustworthy as the IP the frontend *resolves*. The frontend keeps `server.forward-headers-strategy:
none` and registers `ForwardedHeaderFilter` explicitly (`ForwardedHeaderConfig`, ordered one slot
after `ClientIpContextFilter`) so scheme/host are still rebuilt for the OAuth2 redirect URI and HSTS,
**but** the client IP is resolved on the *raw* headers before that filter runs. `ClientIpContextFilter`
(at `HIGHEST_PRECEDENCE`) honours `X-Forwarded-For` only when the immediate TCP peer matches
`app.client-ip.trusted-proxies` (the NPM Docker range) and then walks the chain right-to-left, skipping
trusted hops and taking the first untrusted address — the RemoteIpValve algorithm. Because NPM appends
the true peer on the right, a client-supplied (leftmost) forged entry is never reached, so rotating a
forged `X-Forwarded-For` can no longer mint a fresh per-IP bucket per request; a direct
(untrusted-peer) connection never has its `X-Forwarded-For` honoured. The trusted range MUST match the
real NPM subnet (override via `APP_CLIENT_IP_TRUSTED_PROXIES`); a mismatch collapses every bucket onto
NPM's address — no leak, but the limiter is ineffective.

**Off-servlet-thread coverage (#1130 / #1110).** The relay reads `ClientIpContext` at WebClient
filter-assembly time, so it only fires when that thread-local is present on the thread the exchange
subscribes on. Two paths subscribe off the servlet thread and MUST therefore re-establish the holder:
`ParallelPageLoader` runs a page's independent backend reads on virtual-thread workers and captures /
restores `ClientIpContext` alongside the other relay thread-locals — without it every parallelized
read (missions, hangar, inventory, refinery, bank, job orders) silently re-collapsed onto the single
frontend-container bucket on the async path; and the notification SSE relay's `sseWebClient` carries
`ClientIpRelayFilter` like the request / public clients, so a browser reconnect burst after a redeploy
is attributed per user instead of tripping the shared bucket. A missing capture on either path is the
DOS-1 collapse re-introduced on a code path the request-scoped filters do not reach.

**Acceptance**

- [x] A backend call issued for a browser request carries `X-Forwarded-For` with the real client IP.
- [x] Two distinct clients hitting the same anonymous endpoint consume separate per-IP buckets.
- [x] A client-supplied `X-Forwarded-For` cannot change the resolved/relayed client IP (SEC-02): the
  frontend ignores it unless it arrives from a trusted proxy and always takes the proxy-appended peer.
- [x] A backend read fired through `ParallelPageLoader`'s virtual-thread worker, and the notification
  SSE relay, both carry the real client IP rather than the frontend-container IP (#1130 / #1110).
- [x] Both anonymous order-create variants — `POST /api/v1/orders` and the heavier item-order
  `POST /api/v1/orders/items` (which derives materials from blueprints and takes a table-wide
  pessimistic lock) — share the tight per-endpoint `order-create` budget. The rule lists both paths
  explicitly because `RateLimitingFilter`'s `PathPattern` uses exact-segment matching, so the parent
  `/api/v1/orders` alone does not cover the `/items` child.

**Enforced by:** `ClientIpRelayFilterTest`, `ClientIpContextFilterTest`, `ParallelPageLoaderTest`,
`RateLimitingFilterTest` (`order-create` rule covers the `/orders/items` child path)
· **Code:** `ClientIpRelayFilter` / `ClientIpContextFilter` / `ClientIpProperties` /
`ForwardedHeaderConfig` / `RateLimitingFilter.resolveClientIp` / `ParallelPageLoader` /
`WebClientConfig.sseWebClient` · **Issues:** security audit DOS-1, SEC-02, #1130, #1110

### REQ-SEC-012 — Re-authentication on lost frontend OAuth2 token

When a frontend &rarr; backend call fails with Spring Security's `ClientAuthorizationException`
(`client_authorization_required`, or a refresh-grant `invalid_grant` after a revoked/rotated refresh
token) the user MUST be bounced through a fresh Keycloak login rather than shown an empty page / 500
or a stack-trace log flood. Because the exception is a `RuntimeException` (not an
`AuthenticationException`) it bypasses `SsoReAuthenticationEntryPoint` (REQ-SEC-008), so it is handled
explicitly at the MVC boundary: `BackendApiClient` rethrows it as `ReauthenticationRequiredException`
(logged at DEBUG, no stack trace) and `GlobalExceptionHandler` answers an HTML navigation with a
`302` to `/oauth2/authorization/keycloak` and an AJAX caller with a `401` carrying the
`X-Reauthenticate` header (mirrored in the JSON body) so the shared `krtFetch`/`krtReauth` client
redirects the window; the notification SSE relay pushes a `reauth` event for the same effect. The
exception is added to the Resilience4j `ignoreExceptions` so it is neither retried nor counted toward
the circuit breaker. This — and all other backend-call resilience (bulkhead, time limiter, retry,
circuit breaker) — is applied in a **single pass at the WebClient exchange filter** on the
`backendApi` instance; the formerly redundant method-level `@Retry`/`@CircuitBreaker` AOP layer on
`BackendApiClient` (a separate `backend` instance) was removed (ADR-0032).

**2026-07-07 — the entry point must not clobber the saved authorization request on background
requests (#1137).** `SsoReAuthenticationEntryPoint` (REQ-SEC-008) is the entry point for *genuinely
unauthenticated* requests (an `AuthenticationException`, distinct from the
`ClientAuthorizationException` token-loss path above). It MUST discriminate request type: only a
**top-level navigation** (`Sec-Fetch-Mode: navigate`, or — for pre-fetch-metadata clients — a request
with no XHR/JSON/SSE marker) commences the `prompt=none` silent-SSO `302`. Every **background** request
— a `fetch`/XHR write, the `EventSource` stream, a WebSocket handshake (`Sec-Fetch-Mode` ≠ `navigate`,
or the `X-Requested-With: XMLHttpRequest` / `Accept: application/json` / `Accept: text/event-stream`
fallback) — MUST instead receive a `401` carrying the `X-Reauthenticate` header (mirroring the
token-loss AJAX contract) and MUST NOT be redirected. Rationale:
`HttpSessionOAuth2AuthorizationRequestRepository` stores exactly one saved authorization request per
session, so redirecting a background call overwrites the slot the user's genuine navigation needs, and
the interactive re-login then fails with `authorization_request_not_found` — a nondeterministic
mid-session lockout whenever any app tab (its SSE auto-reconnect in particular) stays open after
session loss. The JS side needs no change: `krtFetch.maybeReauthenticate` and `notifications.js`
already turn a `401 + X-Reauthenticate` into one controlled window redirect.

To stop the in-session refresh race that produces this (parallel page + SSE + poll requests each
replaying the same refresh token, which Keycloak's rotation + reuse detection then revokes — see
`INGEST_KEYCLOAK_SETUP.md` step 4), the `OAuth2AuthorizedClientManager` is wrapped in a
`SingleFlightAuthorizedClientManager` that serialises refreshes per session and serves a
short-lived freshness cache, issuing at most one refresh-token grant per expiry window. The
single-flight key MUST resolve consistently per session — the session id is recovered from
`RequestContextHolder` when the OAuth2 filter did not attach the servlet request, so the same
session never splits across stripes and the principal fallback is reserved for request-less calls.
The long-lived notification SSE relay (`/notifications/stream`, a 30-minute `SseEmitter`) MUST NOT
drive a refresh. Resolving the bearer **read-only**
(`OAuth2AuthorizedClientRepository.loadAuthorizedClient`) is necessary but **not sufficient** on its
own: attaching an authorized client to a WebClient that still carries the OAuth2 exchange filter
routes the call through `ServletOAuth2AuthorizedClientExchangeFilterFunction.reauthorizeClient`, which
calls `OAuth2AuthorizedClientManager.authorize(...)` *unconditionally* and can therefore refresh (and
write the rotated client back) on a stale/empty single-flight cache. The relay therefore uses a
dedicated `sseWebClient` built **without** the `oauth2Configuration()` filter and sets the read-only
bearer as a plain `Authorization` header, so it is structurally incapable of reaching `authorize` — a
stale online refresh token can neither be replayed nor written back to the session (which would
otherwise trip Keycloak's reuse detection and revoke the SSO session). The snapshot token is relayed
verbatim even when expired; the backend rejects it and the always-on unread-count poll, not the relay,
drives re-authentication. The relay fails soft when no token is bound. The single-flight freshness
margin (`EXPIRY_SKEW`) MUST be ≥ the `RefreshTokenOAuth2AuthorizedClientProvider` clock skew (Spring's
default is 60s), so a freshness-cache hit never serves a token the provider would itself refresh.
Single-flight is JVM-local; horizontally-scaled deployments previously relied on `Refresh Token Max
Reuse > 0` on Keycloak for the residual cross-instance race. `offline_access` MUST NOT be re-added to
paper over this (audit finding L-4). See ADR-0019 (and its 2026-06-18 amendments).

**2026-06-18 — the root mitigation is on Keycloak, not in code.** Three iterations of the code-side
single-flight / relay hardening above did not stop the cascade in production. The failing
refresh-token grant surfaces on the **ordinary** page-render and unread-count-poll path (frontend
stack: `HomeController` → `BackendApiClient` → `ReauthenticationRequiredException` →
`ClientAuthorizationException` → `SingleFlightAuthorizedClientManager.authorize`), not only on the SSE
relay, while the backend stays healthy (every `GET /api/v1/missions/next` that reaches it returns
`200`). Keycloak logged the full reuse-detection chain on one SSO session —
`REFRESH_TOKEN_ERROR reason="Stale token"` → `"Session doesn't have required client"` →
`"refresh token issued before the client session started"`. (The event field
`client_auth_method="client-secret"` reflects the client's default `clientAuthenticatorType`
attribute, not secret-based authentication — `basetool-frontend` is a **public** client,
`publicClient: true`; the public→confidential migration is ADR-0001, implementation pending.) Because
the frontend is a **server-rendered Spring BFF** whose refresh token is held only in the Redis-backed
Spring Session and never reaches the browser, refresh-token rotation + reuse detection — whose purpose
is to bound the damage of a refresh token leaking from an *untrusted* client environment (browser /
SPA / native) — adds little here while being the **direct cause of the session revocations** under the
BFF's unavoidable concurrent-refresh race. The realm-wide control is therefore turned **off**
(`Revoke Refresh Token = Off`; realm-export `"revokeRefreshToken": false`): a replayed or duplicate
online refresh token is no longer treated as stale-token reuse, so the SSO session is not revoked and
the homepage no longer shows "Fehler beim Laden der Einsätze". The `SingleFlightAuthorizedClientManager`,
the structurally-refresh-free SSE relay and the `EXPIRY_SKEW ≥ 60s` invariant above are **retained as
defense-in-depth** but are no longer load-bearing. Consequence to weigh: rotation/reuse-detection no
longer protects the persisted desktop-extractor refresh token — the runbook already records this as a
reversible, ingest-independent operator lever (`INGEST_KEYCLOAK_SETUP.md`). See ADR-0019
(2026-06-18 amendment #4).

**2026-06-20 — a second, rotation-independent root cause: the `scope` request param leaking into
the grant.** Production still showed "Fehler beim Laden" after rotation was turned off, but the
Keycloak event was a different one: `REFRESH_TOKEN_ERROR error="invalid_request" reason="Invalid
scopes: all"` (and `"Invalid scopes: mine"`), surfacing in the frontend as
`ClientAuthorizationException [invalid_scope]` out of `RefreshTokenOAuth2AuthorizedClientProvider`.
The values `all` / `mine` are the job-orders **"Staffel" filter** (`orders-index.html`, radio
buttons `name="scope"` `value="mine|all"` — "Eigene Staffel" / "Alle Staffeln"), not OAuth scopes.
The cause is a Spring footgun: `DefaultOAuth2AuthorizedClientManager`'s default
`contextAttributesMapper` copies a request parameter literally named `scope` into
`OAuth2AuthorizationContext.REQUEST_SCOPE_ATTRIBUTE_NAME`, which the refresh provider then sends to
Keycloak as the requested scope of the **refresh-token grant**. So any token refresh that coincides
with a request carrying `?scope=all|mine` is rejected with `invalid_scope`, and the SSO session is
bounced into re-authentication — intermittently, because only a refresh that lands on such a request
is poisoned (a no-`scope` background poll refreshes cleanly, which is why a reload "after a bit"
recovers). The frontend never requests scopes dynamically (they are fixed on the `keycloak` client
registration), so the manager's `contextAttributesMapper` is overridden to return an empty map
(`WebClientConfig.NO_REQUEST_DERIVED_ATTRIBUTES`), severing the request-parameter → OAuth-scope path
entirely. This is orthogonal to the rotation/reuse-detection mitigation above and to single-flight;
it MUST hold regardless of either.

**Acceptance**

- [ ] A `client_authorization_required` on an HTML navigation redirects (302) to the Keycloak login
  flow instead of rendering an empty page / 500.
- [ ] The same failure on an AJAX call returns `401` with an `X-Reauthenticate` header and the
  client redirects the window.
- [ ] A burst of concurrent same-session authorize calls issues exactly one refresh-token grant,
  including when some callers lack the attached servlet request (session id recovered from context).
- [ ] The notification SSE relay never issues a refresh-token grant: it relays a read-only bearer as
  a plain `Authorization` header over a WebClient with no OAuth2 exchange filter (verbatim even when
  the token is already expired) and fails soft when no token is bound.
- [ ] `EXPIRY_SKEW` ≥ the refresh provider's clock skew (60s) so a freshness-cache hit never serves a
  token the provider would refresh.
- [ ] `ClientAuthorizationException` is not retried and does not open the backend circuit breaker.
- [ ] The `iri` realm has `Revoke Refresh Token = Off` (`"revokeRefreshToken": false`), so a replayed
  or duplicate online refresh token does not trip reuse detection and does not revoke the SSO session.
- [ ] An application request parameter named `scope` (e.g. the job-orders Staffel filter's
  `scope=all|mine`) never reaches Keycloak as the refresh-token grant scope: the
  `DefaultOAuth2AuthorizedClientManager` `contextAttributesMapper` returns an empty map, so a token
  refresh coinciding with such a request is not rejected with `invalid_scope`.
- [ ] An unauthenticated **background** request (`Sec-Fetch-Mode` ≠ `navigate`, or an
  `X-Requested-With: XMLHttpRequest` / `Accept: application/json` / `Accept: text/event-stream`
  fallback) to `SsoReAuthenticationEntryPoint` returns `401 + X-Reauthenticate` and is NOT redirected,
  leaving the session's single saved OAuth2 authorization request untouched; a top-level navigation
  still commences the `prompt=none` silent SSO and sets the `SSO_ATTEMPTED` loop-guard cookie.

**Enforced by:** `SingleFlightAuthorizedClientManagerTest`, `OAuth2ScopeRequestParamLeakTest`,
`NotificationPageControllerStreamTest`, `GlobalExceptionHandlerTest`,
`BackendApiClientResilienceTest`, `SsoReAuthenticationEntryPointTest` · **Code:**
`SingleFlightAuthorizedClientManager`, `WebClientConfig`, `ReauthenticationRequiredException`,
`BackendApiClient`, `GlobalExceptionHandler`, `NotificationPageController`,
`SsoReAuthenticationEntryPoint`, `krt-fetch.js` · **Issues:** ingest-rollout
regression, #1137 · **ADR:** ADR-0019

### REQ-SEC-013 — Frontend role checks read the Authentication token, not the OidcUser principal

Frontend membership/role predicates (e.g. the member-only mission finance/refinery gate) MUST read
the request `Authentication`'s authorities — the same source `sec:authorize` and `@PreAuthorize`
consult — via `FrontendAuthHelperService`, never the `@AuthenticationPrincipal OidcUser`'s own
`getAuthorities()`. Spring's `userAuthoritiesMapper` maps the Keycloak `realm_access.roles` onto the
`OAuth2AuthenticationToken`, not onto the `OidcUser` principal object, so a check that reads the
principal sees none of the mapped `ROLE_*` unless `BackendRoleSyncFilter` happened to rebuild the
principal that session. The mission-detail finance gate (`MissionPageController.isMemberOrAbove`)
regressed exactly this way: it read the principal, so the "Finanzen" panel silently collapsed
(database rows intact, backend returning `200`) for any session whose one-shot role sync had been
skipped, while the panel chrome still rendered because the template's `sec:authorize` correctly read
the token.

`BackendRoleSyncFilter` (which enriches the principal with backend-DB roles/permissions) MUST mark a
session synced (`BACKEND_ROLES_SYNCED`) only when the `/api/v1/users/me` read genuinely succeeded. A
Resilience4j fallback (`null`, no exception) or a thrown error MUST leave the flag unset so the next
request retries, rather than poisoning the whole session with an under-privileged principal until the
user re-logs in.

**Acceptance**

- [ ] `FrontendAuthHelperService.isMemberOrAbove()` is true for any member/elevated `ROLE_*` on the
  Authentication token and false for anonymous, missing-context and role-less GUEST callers.
- [ ] A member's `GET /missions/{id}` triggers the member-only finance-entries fetch; an anonymous
  visitor's does not.
- [ ] `BackendRoleSyncFilter` does not set `BACKEND_ROLES_SYNCED` when `/api/v1/users/me` returns
  `null` or throws; it does set it when the read succeeds.

**Enforced by:** `FrontendAuthHelperServiceTest`, `BackendRoleSyncFilterTest`,
`MissionPageControllerMvcTest` · **Code:** `FrontendAuthHelperService`, `MissionPageController`,
`BackendRoleSyncFilter` · **Issues:** mission-finance-panel regression

### REQ-SEC-014 — Encrypted transport to Keycloak (no cleartext edge)

Production Keycloak MUST serve **HTTPS only** — `--http-enabled=false --https-port=18443`, with the
shared bind-mounted `keystore.p12` — so neither edge that reaches it is cleartext:

- **NPM &rarr; Keycloak:** `nginx-proxy-manager` terminates the public Let's Encrypt cert and
  re-encrypts to `https://keycloak:18443` (nginx does not verify the self-signed upstream cert).
- **backend &rarr; Keycloak (admin/user-sync):** `KeycloakService` calls `https://keycloak:18443`
  directly over the isolated `net-backend-keycloak` network, pinning the self-signed cert via the
  `keycloak-trust` Spring SSL bundle (mirrors the frontend/ingest `backend-trust` approach, audit
  finding M-13). Unlike those reactive WebClients, the synchronous JDK `HttpClient` keeps **hostname
  verification ON** (it cannot be disabled reliably per-client), so the cert's SAN MUST include
  `dns:keycloak`.

The management/health interface (port 9000) is exempt: it stays HTTP via
`--http-management-scheme=http` because the Quarkus image ships no TLS-capable CLI client for the
container healthcheck. The port is never published on the host and never on an NPM proxy network;
since the monitoring rollout (epic #936, ADR-0072) the **prod** Keycloak additionally joins the
isolated `net-monitoring-scrape` network so **Prometheus scrapes `http://keycloak:9000/metrics` in
plain HTTP** there. Dev/test are exempt (Keycloak stays HTTP; the admin URL is plain HTTP and no
`keycloak-trust` bundle is defined, so `KeycloakService` falls back to the default client).

Since **ADR-0090** the two internet-facing Spring Boot modules (`frontend`, `ingest`) adopt the same
"management interface on an internal-only port, never host-published nor NPM-proxied" pattern in
prod: `/actuator/**` moves to a dedicated `management.server.port` (frontend `18091`, ingest `11272`,
HTTPS via the shared keystore) reachable only from `net-monitoring-scrape` and the container-local
`HEALTHCHECK`, so their public connectors expose no Actuator at all. See `REQ-OBS-005` (amended) for
the authoritative rule.

**Monitoring-plane cleartext carve-out (owner-approved amendment, 2026-07-02).** The HTTPS-only edge
posture above still holds for every app/Keycloak/NPM edge. It is deliberately amended for traffic that
stays **inside the isolated monitoring Docker networks** (`net-monitoring-scrape` /
`net-monitoring-core` / `net-docker-proxy`): Prometheus→exporters, Grafana→datasources,
Alloy→Loki/Tempo, the app/Keycloak OTLP span push to Alloy, and the `keycloak:9000` metrics scrape run
in plain HTTP. These networks carry no host ports, no public route and no user payload; Prometheus
still scrapes the three **apps** over HTTPS with the pinned public CA (no `insecure_skip_verify`), and
Grafana gets its own self-signed certificate so the shared `keystore.p12` private key never leaves the
four existing services. Approval of epic #936 by @greluc is the owner approval this amendment requires;
the rationale, residual risk and the binding rules live in `REQ-OBS-008` (`observability.md`) and
ADR-0072.

**Acceptance**

- [ ] In prod, Keycloak exposes no plain-HTTP listener; the public host works through NPM over TLS.
- [ ] The backend user sync succeeds against `https://keycloak:18443` with hostname verification on
  (cert SAN carries `dns:keycloak`).
- [ ] Keycloak reports `healthy` (the HTTP management healthcheck still passes after the HTTPS flip).
- [ ] With no `keycloak-trust` bundle (dev/test), `KeycloakService` builds and talks plain HTTP.

**Enforced by:** `KeycloakServiceTest` · **Code:** `KeycloakService`, `application-prod.yml`
(`spring.ssl.bundle.jks.keycloak-trust`), `docker-compose.yml` (`keycloak` command, backend
`KEYCLOAK_ADMIN_URL`) · **Runbook:** [`deployment.md` &rarr; Keycloak behind NPM over HTTPS](../deployment.md#keycloak-behind-npm-over-https)

### REQ-SEC-018 — The Keycloak user sync MUST page the full user list before reconciling deletions

`UserSyncTask` reconciles local users against Keycloak: after syncing every fetched user it calls
`UserService.markMissingUsers(currentIds)`, which flags every local user whose Keycloak id did **not**
appear in the run as no-longer-in-Keycloak. That reconciliation is only safe if the fetched set is the
**complete** Keycloak user list. The Admin API `GET /users` endpoint caps each response at a server-side
maximum (~100 by default), so `KeycloakService.fetchUsers(appRoleNames, knownDiscordLinkedIds)` MUST
page through `first`/`max` (`app.keycloak.sync.page-size`, default 100, bounded 1–1000) until a
short/empty page signals the end.

A single unpaged call returns only the first page, so every user beyond the cap would be wrongly marked
missing — a silent soft-delete of real members. Fetching the page is a prerequisite of the
reconciliation, not an optimisation; the two must never diverge.

**Role resolution is role-indexed, not per-user (5000-account scaling, ADR-0085).** The sync resolves
roles by listing the members of each app-relevant realm role once (`GET /roles/{name}/users`, paged),
driven by the local role catalog (`getMappableRoleNames`), rather than reading each user's role mapping
individually. Both views report directly-assigned realm roles, so the reconstructed sets are equivalent
to the old per-user path — but the Admin-API call count is bounded by the (small) number of mappable
roles instead of the user count. The completeness invariant above is unchanged: the roster still comes
from the paged `GET /users`; role-indexing only changes how the roster is *annotated* with roles, never
which users are considered present.

**The local catalog is matched case-insensitively against the realm's actual role names.** Keycloak's
`GET /roles/{name}/users` lookup is case-sensitive, so the sync first lists the realm's real role names
(paged `GET /roles`) and matches the local mappable names against them ignoring case (mirroring the
interactive JWT path's `findByNameIgnoreCase`), then queries members under Keycloak's own casing. This
removes a scheduled-vs-interactive casing asymmetry: a role whose Keycloak name differs only in case
from the local name is still resolved, not silently dropped.

**A role-membership read failure is fail-safe: it skips the run, never degrades the write.** Because a
role-stripped set would misclassify holders — mapping a brand-new admin to the `Guest` fallback and
creating it `PENDING` instead of `ACTIVE`, or mass-downgrading existing admins — a transient failure of
any role read (5xx, 401/403, timeout, malformed body) propagates to `fetchUsers`' top-level catch and
skips the whole run (empty roster → "skip", never a wipe, never a degraded persist), exactly like a
roster-page failure. Only a clean `404` on a single role's member read (a benign TOCTOU: the role
vanished after the `GET /roles` listing named it) is swallowed — that role contributes no members and
the run continues.

**Acceptance** (`KeycloakServiceTest`): with a page size of 2 and three users across two pages,
`fetchUsers` returns all three, the first request binds `first=0&max=2`, and the second advances to
`first=2`; with realm role `ADMIN` listing user A, A's DTO carries `ADMIN` resolved from
`/roles/ADMIN/users`; a realm role spelled `admin` still resolves the app's `ADMIN` (case-insensitive);
a 5xx on a role's member read yields an empty result (run skipped, no degraded roles); and a clean 404
on a role's member read keeps the roster with that role simply absent.

**Enforced by:** `KeycloakServiceTest` · **Code:** `KeycloakService.fetchAllUsers`,
`KeycloakService.fetchRealmRoleNames`, `KeycloakService.fetchRoleMemberships`,
`KeycloakSyncProperties.pageSize`, `UserSyncTask`

### REQ-SEC-015 — Bereich/OL leadership grants officer-equivalent reach, never admin rights

The org hierarchy (REQ-ORG-014) introduces Bereich and OL leadership whose access **cascades** down to
subordinate units (REQ-ORG-015). This is a security-load-bearing carve-out, so the invariant is pinned
here:

- A Bereich/OL leadership principal gets **officer-equivalent reach** over its scope as a **concrete
  `memberOrgUnitIds` union** (and matching contextual authorities) — it MUST **never** route through the
  `adminAllScope=true` branch and MUST **never satisfy `isAdmin()`**. Every `hasRole('ADMIN')` gate
  (admin area, SK lifecycle, system settings, stammdaten, promotion-topic guards, bank admin/audit) stays
  closed to it.
- **Strict silo:** a Bereichsleitung sees/edits only its own Bereich's descendants; only the OL crosses
  Bereiche. No peer-Bereich access, even read-only.
- **ArchUnit-whitelist obligation:** the name-keyed rules `staffelScopedServicesMustWireOwnerScopeOrAuthHelper`,
  `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` and `orgUnitAwareBankSeamIsContainedToOneClass`
  silently skip classes not in their set; **every** new scoped controller/service added by the
  restructure MUST be added to the relevant whitelist in the same PR (or covered by an
  annotation/package-based rule), so no new write endpoint ships ungated.

**Acceptance**

- [x] An OL/Bereich principal fails `isAdmin()` and is rejected by every `hasRole('ADMIN')` gate.
- [x] A Bereichsleitung is denied another Bereich's data (lists and detail gates).
- [x] A new scoped controller/service is caught by the ArchUnit scope/whitelist rules (a deliberately
  ungated one fails the build).

**Enforced by:** `OwnerScopeServiceTest` (`CascadingScopeTests`: `cascade_neverSetsAdminAllScope`,
strict-silo foreign-unit denial, OL concrete-union) and `OrgUnitCascadeServiceTest`;
`ArchitectureTest` — `cascadeServiceMustNotConsultTheSecurityContext` (the cascade can never branch on
admin status, so it can never grant admin), the `staffelScopedServicesMustWireOwnerScopeOrAuthHelper`
whitelist (incl. the new `OrgUnitBankAccessService`) and `staffelScopedWriteEndpointsMustGateOnOwnerScopeService`
(a new ungated scoped endpoint fails the build); and the `OrgHierarchyVisibilityMatrixE2eTest` cross-Bereich
matrix on the ephemeral stack (Phase 7, `e2e`-label-gated) · **ADR:**
[ADR-0026](../adr/0026-cascading-scope-without-admin.md) · **Issues:** #692, #696, #700.

### REQ-SEC-018 — Anonymous guest sign-up edits require a per-row capability token

Mission participant write endpoints (`PUT`/`DELETE`/check-in/out/payout on
`/api/v1/missions/*/participants/*` and the `…/slim` twins) are `permitAll` so the public mission
sign-up flow works without an account. A **guest** (unlinked) participant row MUST NOT be mutable by a
caller who merely knows its id — the anonymous-readable roster exposes participant ids, so a bare id is
not an authorization secret.

- On creation of a guest sign-up the backend mints an unguessable 256-bit **capability token**,
  persists only its SHA-256 hash on `mission_participant.guest_edit_token_hash`, and returns the
  plaintext **once** in the create response (`MissionParticipantDto.guestEditToken`).
- Every subsequent guest-row mutate/delete is authorised by
  `MissionSecurityService.canAccessParticipant` iff the caller (a) presents a token (header
  `X-Guest-Edit-Token`) that hashes to the stored hash, OR (b) holds a mission-management role in scope
  (`canManageMission`). A guest row with no stored hash (pre-V177) is editable only via (b) — the gate
  **fails closed**.
- The frontend stores the token client-side (localStorage, keyed by participant id) and replays it via
  the `X-Guest-Edit-Token` header, relayed browser→frontend→backend by the
  `GuestEditTokenContext`/`GuestEditTokenContextFilter`/`GuestEditTokenRelayFilter` trio (Reactor
  context propagation, mirroring the client-IP relay). The token is intentionally lost when the user
  clears site data — an anonymous caller has no durable server-verifiable identity, so a cleared token
  degrades to "a mission manager edits it", never to "anyone can edit it".

**Acceptance**

- [x] An anonymous caller without the token is denied (403) on a guest-row mutate/delete/payout.
- [x] The anonymous creator presenting the minted token may edit/withdraw their own guest row.
- [x] A mission manager / officer / admin in scope may still manage guest rows without a token.
- [x] Only the create response ever carries the plaintext token; reads/edits return `null`.

**Enforced by:** `MissionSecurityServiceTest` (`canAccessParticipant_GuestWithValidToken_*`,
`…_GuestWrongTokenNotManager_ShouldReturnFalse`, `…_GuestNoTokenButManager_*`),
`GuestParticipantTokenServiceTest`, and the MockMvc integration tests `MissionGuestAccessTest`
(without-token-forbidden + with-token-allowed) and `MissionAccessControlTest`. **Migration:** V177.
**Security audit:** finding M1.

### REQ-SEC-019 — Mission finance-entry writes are owning-OrgUnit-scoped for officers

`MissionFinanceEntry` edit/delete (`PUT`/`DELETE /api/v1/finance-entries/{entryId}`) gates on
`MissionSecurityService.canEditFinanceEntry`. `ROLE_OFFICER` is a flat, cross-squadron realm
authority, so — like every other mission write (`canManageMission`, `canManageManagers`,
`canChangeOwner`, hardened under audit AUTHZ-1) — an officer may edit/delete a finance entry only when
the entry's mission is within their owning-OrgUnit scope (`OwnerScopeService.canEditMission`). Only
`ROLE_ADMIN` bypasses the scope check (system-wide oversight). The entry owner's self-edit path (linked
participant who is still a registered participant) is unchanged.

**Acceptance**

- [x] An officer scoped to OrgUnit A is denied (403) editing/deleting a finance entry of an OrgUnit-B
  mission (even a B-internal mission they cannot read).
- [x] An officer in scope, an admin, and the entry owner-participant are allowed.

**Enforced by:** `MissionSecurityServiceTest`
(`canEditFinanceEntry_OfficerForeignOrgUnit_ShouldReturnFalse`, `…_OfficerInScope_*`, `…_Admin_*`,
`…_OwnerStillParticipant_*`). **Security audit:** finding H1.

### REQ-SEC-020 — Member-evaluation writes are scoped to the evaluated member's squadron

MemberEvaluation create/update/delete (`PUT`/`DELETE /api/v1/promotion/evaluations/...`) gates on
`MemberEvaluationService` with `@PreAuthorize("hasAnyRole('ADMIN','OFFICER')")`. A non-admin write
must satisfy TWO squadron-scope checks, not one: the evaluation's **category** must belong to a
squadron the caller may edit (`assertCallerMayEditCategory`) AND the **evaluated member** must belong
to a Staffel the caller may edit (`assertCallerMayEvaluateUser` →
`OwnerScopeService.canEditSquadron(member's home Staffel)`). Without the member check an officer of
squadron X could create/overwrite/delete an evaluation row for a member of squadron Y by pairing the
victim's id with a category owned by X (a cross-tenant write of member-evaluation data). ADMIN spans
every squadron and short-circuits; the check fails closed on a malformed member id or a member with
no Staffel the caller can edit.

**Acceptance**

- [x] An officer is denied (`AccessDeniedException`) upserting/deleting an evaluation for a member
  outside their editable Staffel scope, even with an in-scope category.
- [x] An officer may evaluate a member of a Staffel within their scope; an admin may evaluate anyone.

**Enforced by:** `MemberEvaluationServiceTest`
(`upsert_shouldDenyOfficer_evaluatingForeignSquadronMember`,
`upsert_shouldAllowOfficer_evaluatingOwnSquadronMember`). **Security audit:** gap-fill finding
(member-evaluation cross-tenant write).

### REQ-SEC-021 — Anonymous outsider mission view withholds payout intent and free-text comments

The anonymous / role-less-`GUEST` ("outsider") view of a public (non-internal) mission is an
**operational-coordination surface**: by deliberate product decision (ADR-0034) it exposes the
participant roster's public callsign tuple (`username`/`displayName`/`rank`), org-unit affiliation,
job type, assigned ship/unit, mission frequencies, owning organisation and schedule/status, so a
prospective sign-up can decide whether and how to join. It MUST continue to withhold PII (email /
real name — enforced by the C-1 ArchUnit rule `anonymousReadableMissionEndpointsMustRedactGuestPii`)
and, additionally, the two per-participant fields with low public-coordination value and higher
sensitivity:

- **`payoutPreference`** — a participant's financial intent.
- the free-text **`comment`** — uncontrolled text that may carry incidental PII.

Both fields stay on the authenticated member-peer view; only the outsider paths strip them, via
`MissionGuestRedactor.stripOutsiderParticipantFields` applied in `cleanupOutsiderMissionForGuest` (the
pass every outsider full-mission response routes through — `getMissionById` and the participant write
endpoints) and the `addParticipantSlim` outsider branch. The shared `cleanupParticipantForGuest` is
deliberately unchanged. The full residual decision (and the rejected alternatives) is recorded in
ADR-0034.

**Acceptance**

- [x] An anonymous / role-less-`GUEST` read of a public mission (`GET /api/v1/missions/{id}`, the
  participant endpoints, `addParticipantSlim`) returns every participant with `payoutPreference` and
  `comment` `null`.
- [x] An authenticated member (peer view and above) still sees both fields.
- [x] PII (email / real name) remains redacted for outsiders (C-1 unchanged).

**Enforced by:** `MissionControllerLifecycleTest`
(`getMissionById_outsider_planned_keepsRosterButHidesDescriptionAndPii` asserts payout + comment are
`null` for outsiders; `getMissionById_authenticatedCaller_returnsFullDtoUnchanged` keeps them for
members) and `MissionControllerSlimEndpointsTest` for the `addParticipantSlim` outsider roster.
**ADR:** [ADR-0034](../adr/0034-anonymous-outsider-mission-visibility.md). **Security audit:**
finding L3.

### REQ-SEC-023 — Edge per-IP rate limiting (version-controlled safety net)

(REQ-SEC-022 — the Discord account-existence precheck — lives in
[`discord-integration.md`](discord-integration.md); this requirement continues the series at the
next free number.)

Every public proxy host behind nginx-proxy-manager carries a **version-controlled** per-IP safety
net at the edge: `limit_req` (20 r/s sustained, burst 80, `nodelay`) and `limit_conn` (60
concurrent connections) keyed on `$binary_remote_addr`, delivered through the custom snippets the
repo already injects into NPM (`docker/maintenance/nginx/http.conf` defines the zones,
`server_proxy.conf` applies them in every proxy host's `server` block). The values are
flood/brute-force **ceilings**, not fairness limits — a legitimate worst-case page load fits
inside the burst. Two invariants:

- **Rejections answer 429, never 503.** The nginx defaults would route rejected requests into the
  maintenance-page `error_page 502 503 504` intercept, serving a flooding client the maintenance
  page with `Retry-After` semantics that are wrong for rate limiting.
- **Engagement is observable.** Rejected requests land in the per-host access logs (plus
  `limit_req_log_level warn` in the error log) and a sustained 429 rate raises the
  `EdgeRateLimitSpike` Loki alert.

Stricter per-endpoint limits (e.g. the Keycloak login/token paths) may reference the same zones
from a proxy host's Advanced tab in the NPM UI; that is unversioned host state and out of this
requirement's scope. The backend's application-level Bucket4j limiter (REQ-SEC-009 family) is
unchanged and remains the precise, per-subject layer behind this coarse edge net.

**Acceptance**

- [ ] A burst above rate+burst from one IP receives 429 responses (not the maintenance page, not
  503) while other client IPs are unaffected.
- [ ] A normal page load (asset fan-out within the burst) is never limited.
- [ ] A sustained 429 rate at the edge raises `EdgeRateLimitSpike`.

**Enforced by:** `docker/maintenance/nginx/http.conf` (zones) ·
`docker/maintenance/nginx/server_proxy.conf` (per-host application, 429 statuses) ·
`monitoring/loki/rules/fake/basetool-log-alerts.yml` (`EdgeRateLimitSpike`) · **Runbook:**
`docs/deployment.md` → *Edge rate limiting*

### REQ-SEC-024 — Keycloak resilience: internal JWKS + retryable 503 on IdP outage

Both JWT resource servers — the backend and the ingest gateway (REQ-SEC-001) — fetch Keycloak's
JWKS to validate every token. Two hardening rules keep a transient Keycloak / edge / Docker-DNS blip
from masquerading as an application outage (the failure mode that drove the frontend
`Http5xxRateHigh` incident: a slow / unreachable Keycloak turned JWKS retrieval into an
`AuthenticationServiceException` → `500` on every authenticated endpoint):

- **Internal JWKS (opt-in).** Setting `app.security.jwt.jwk-set-uri` (env `KEYCLOAK_JWK_SET_URI`)
  points key retrieval at the **internal** Keycloak connector
  (`https://keycloak:18443/realms/iri/protocol/openid-connect/certs`), reusing the `keycloak-trust`
  SSL bundle (REQ-SEC-014 — so the cert's SAN must carry `dns:keycloak`) instead of hairpinning
  through the public edge (NPM). The `iss` claim is still validated against the **public** issuer
  Keycloak stamps into tokens, so the split-horizon (public `iss`, internal key fetch) is
  transparent. Because `NimbusJwtDecoder.withJwkSetUri` defaults to **RS256-only** (unlike
  issuer-location discovery, which derives the accepted algorithms from the live JWKS), the
  internal-JWKS path explicitly widens the accepted set to the full asymmetric `SignatureAlgorithm`
  set, so enabling it never 401s a PS\*/ES\*-signed token (no HMAC is added, so no algorithm
  confusion). Empty (the default) preserves the auto-configured, issuer-derived decoder
  byte-for-byte — the knob is off until an operator opts in, and the `test` profile's placeholder
  issuer is unaffected.
- **IdP unavailable → retryable 503, not 500.** When the JWKS fetch fails on a transport / upstream
  problem (timeout, connection error, `UnresolvedAddressException` on a Docker-DNS strand, or a
  Keycloak 5xx), the re-thrown `AuthenticationServiceException` — which otherwise escapes as an
  opaque `500` on every authenticated endpoint (Micrometer `uri="UNKNOWN"`) — is re-mapped to a
  retryable `503 Service Unavailable` (RFC-7807 problem+json, `Retry-After`, code
  `SERVICE_UNAVAILABLE`), logged at WARN (not ERROR) and counted on
  `basetool_http_error_total{code="SERVICE_UNAVAILABLE"}`. Genuine token rejections are untouched —
  bad/expired tokens stay `401`, missing roles stay `403`.

**Acceptance**

- [ ] With `jwk-set-uri` set, tokens validate against keys fetched from the internal Keycloak over
  the pinned bundle while the public `iss` still validates; with it empty, decoder behaviour is
  unchanged.
- [ ] The internal-JWKS decoder accepts an `ES256` (and `PS*`) token, not just `RS256`.
- [ ] A JWKS timeout / 5xx / DNS failure yields `503` + `Retry-After` (not `500`), logged at WARN
  and counted on `basetool_http_error_total{code="SERVICE_UNAVAILABLE"}`.
- [ ] An expired/invalid bearer token still yields `401`; a caller lacking the required role still
  yields `403` (the 503 re-map never swallows an auth decision).
- [ ] Optional `aud` enforcement (audit L-1) is available on both resource servers via
  `app.security.jwt.expected-audiences` (wired to `IRI_BACKEND_EXPECTED_AUDIENCES` /
  `IRI_INGEST_EXPECTED_AUDIENCES`), sharing the same `resourceServerJwtDecoder` bean. It is **empty
  by default** (off) so dev / e2e realms — which do not stamp the audience — are unaffected;
  enabling it in prod requires the realm to stamp `aud=basetool-backend` (the `extractor-ingest`
  default client scope), or every token is rejected.

**Enforced by (both resource servers):** backend `SecurityConfig#resourceServerJwtDecoder` +
`KeycloakTrustSupport` + `IdentityProviderUnavailableFilter` (tests:
`IdentityProviderUnavailableFilterTest`, `SecurityConfigInternalJwksDecoderTest`) ·
`BasetoolErrorController` (503 problem mapping) · the **ingest gateway's** own package-local
`KeycloakTrustSupport` / `IdentityProviderUnavailableFilter` + matching tests (it cannot depend on
backend classes across the module boundary, so the pattern is duplicated) · `application-prod.yml`
in both modules (`app.security.jwt.jwk-set-uri` + the `keycloak-trust` bundle).

### REQ-SEC-025 — Two-tier session idle timeout (anonymous vs authenticated)

The frontend's Redis-backed Spring Session store applies a **short** idle window to un-authenticated
sessions and the long "stay logged in" window **only after a successful login**. Rationale: with
`@EnableRedisIndexedHttpSession` a single 30-day default let every throwaway session Spring Security
mints for anonymous traffic — chiefly the CSRF-token session created when an anonymous client renders
a form-bearing permit-all page, plus pre-login OAuth2 `authorizationRequest` state — live for 30
days. Anonymous probe / crawler traffic thereby accreted **>16 000 orphan CSRF-only sessions against
~30 real principals** (the `basetool_active_sessions` runaway), on a collision course with the
Redis `maxmemory noeviction` ceiling, where login / token-refresh writes start failing.

`RedisSessionConfig`'s `SessionRepositoryCustomizer` applies `app.session.anonymous-timeout` (default
`30m`) as the repository's default `maxInactiveInterval`, so every new session starts short.
`SessionLifetimeUpgradeSuccessHandler` promotes the session to `app.session.authenticated-timeout`
(default `720h`) on OAuth2 login success — it runs after Spring Security's session-fixation
`changeSessionId` (which preserves the interval) and never mints a session when none exists. The
30-day cookie `max-age`, the `maximumSessions(10)` principal cap, and the CSRF repository/handler
(`HttpSessionCsrfTokenRepository`, REQ-SEC-010) are all **unchanged** — this is a TTL policy, not a
CSRF-transport change. Cookie-based CSRF (`CookieCsrfTokenRepository`) was considered as a way to
stop anonymous CSRF-token sessions at the source and **rejected**: an unsigned double-submit cookie
is a weaker CSRF model than the retained server-side synchronizer token + `SameSite=Strict` (see
ADR-0088). Runaway regression is caught by `ActiveSessionsRunaway`
([`observability.md`](observability.md)).

**Acceptance**

- [ ] A new session's default idle window is `app.session.anonymous-timeout` (the repository
  default), not the 30-day window.
- [ ] A successful OAuth2 login promotes its session's idle window to
  `app.session.authenticated-timeout`.
- [ ] No throwaway session is created merely to bump the timeout when none exists at login success.
- [ ] Members keep the 30-day "stay logged in" behaviour (cookie `max-age` + authenticated window).

**Enforced by:** `RedisSessionConfigTest` (anonymous window is the repository default),
`SessionLifetimeUpgradeSuccessHandlerTest` (login promotes to the authenticated window; no session
minted when absent) · **Code:** `RedisSessionConfig#sessionRepositoryCustomizer`,
`SessionLifetimeUpgradeSuccessHandler`, `SecurityConfig#oauth2LoginSuccessHandler` · **Monitoring:**
`ActiveSessionsRunaway` · **ADR:** [ADR-0088](../adr/0088-two-tier-session-idle-timeout.md)

## Out of scope

OrgUnit scoping/visibility rules (see [`org-unit-tenancy.md`](org-unit-tenancy.md)); the
confidential-client migration decision (see ADR-0001).
