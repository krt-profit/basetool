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

The same rule binds every **create-stock-for-another-member** path. `POST /api/v1/inventory`, `POST
/api/v1/refinery-orders` (its `owner` override) and the per-item receiver of `POST
/api/v1/refinery-orders/{id}/store` MUST each authorise the **target**, through
`OwnerScopeService.canManageUserInventory(...)` / `canManageUserRefineryOrders(...)`, and MUST NOT
substitute a bare `AuthHelperService.isLogisticianOrAbove()` for it. All three did until the
2026-08-30 audit, which made them cross-tenant writes by construction; the fourth entry point,
`POST /api/v1/refinery-orders/users/{userId}`, had been closed in PR #808 and is the shape the other
three now follow. `POST /api/v1/inventory` additionally refuses `personal = true` for a foreign
target: the write-time stock merge (REQ-INV-026) keys on `personal` and returns the surviving row,
so a personal on-behalf create folded the target's own private rows — amounts, notes, earmarks —
into the response. The specified personal-for-someone-else capability lives in the refinery store
dialog (REQ-INV-035) and is unaffected.

**Authority resolution is memoised per token, not per request (#1141).** `CustomJwtGrantedAuthoritiesConverter`
runs on *every* authenticated API call, and each miss pays `UserService.syncUser` (a write-capable
transaction) plus ~5–8 SELECTs (user load, `user_roles`, one role lookup per realm role, and the
membership read). The memoisation key MUST include the token's `azp` alongside `(sub, iat)`. Since REQ-SEC-036 the
assembly branches on the authorized party twice — the ingest-gateway short-circuit and the
partial-role-scope client list — so the set is no longer a pure function of `(sub, iat)`, and `iat`
is a NumericDate in **seconds**: two tokens for the same person minted by different clients inside
one wall-clock second collided on the key and the first arrival decided the authorities for both.
That is exactly the admin demotion (and, mirrored, elevation) REQ-SEC-036 exists to prevent. A
memoisation key must be a superset of the inputs the memoised computation reads.

The assembled authority collection is memoised in-process keyed on `(sub, token
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

### REQ-SEC-007 — Peer minimisation & field redaction

For a **member below Logistician**, return only the minimum required data. Sensitive fields
(e-mail, real name, internal orders/items) MUST be explicitly cleared in the controller via a
`cleanup…ForPeer`-style helper to prevent information disclosure. (E-mail is shown only in a user's
own profile — never elsewhere.) The naming convention is enforced structurally by the ArchUnit rule
`peerReadableMissionEndpointsMustRedactPii`.

> **Amended 2026-09-06 (ADR-0159).** This requirement had **two** tiers: the member-peer one above,
> and a stricter *outsider* tier (`cleanupOutsiderMissionForGuest`) that additionally hid the
> free-text description and each participant's payout preference and comment, for anonymous and
> role-less `GUEST` callers. Both audiences are gone (REQ-SEC-052, REQ-SEC-053), so the tier went
> with them and `MissionGuestRedactor` became `MissionPeerRedactor`.
>
> The rule that selects the endpoints was rewritten rather than renamed, and that is the part worth
> reading. It used to select gates carrying **no** `isAuthenticated()` clause — the shape that made
> an endpoint anonymously reachable. Every such gate now has one, so the old predicate would select
> *nothing* and the rule would pass by checking an empty set. It now keys on *which* gate:
> `canSeeMission` and `canAccessParticipant` admit an ordinary member, `canManageMission` and its
> siblings do not. The rewrite immediately found a real leak the old rule could not see —
> `MissionController.joinMission` returned the whole Einsatz, roster included, to the member who had
> just joined.

### REQ-SEC-009 — The member surface

Every caller of the tool is a **member**. There is no cohort below that: an anonymous caller reaches
only the paths REQ-SEC-052 enumerates, and an authenticated token that maps to no application role is
refused with `403 NO_ROLE` (REQ-SEC-053).

`AuthHelperService.isMemberOrAbove()` remains the membership predicate (true for
`ADMIN`/`OFFICER`/`MISSION_MANAGER`/`LOGISTICIAN`/`KRT_MEMBER`/`MEMBER`). It is deliberately still a
different question from `isAuthenticated()`: the gap between the two has shrunk to the
PENDING/REJECTED registration and to whatever authority set a future integration introduces, and the
questions that ask about membership — the mission description (REQ-SEC-041), the live-sync rooms —
should keep asking it rather than depending on a refusal happening earlier in the chain.

> **Rewritten 2026-09-06 (ADR-0159).** This requirement used to describe a "deliberately public
>
>> surface" shared by two cohorts — anonymous callers and the `GUEST` role — under the name *mission
>> outsider*, and enumerated what they could do: create a job order, browse non-internal missions,
>> sign up as a named guest, check in and out, set a payout preference. None of it is true any more.
>> The term *outsider* is retired; what replaced it is REQ-SEC-052 (the public surface as a list) and
>> REQ-SEC-053 (nothing below member).

### REQ-SEC-008 — Frontend bot protection & silent re-auth

The frontend's `BotProtectionFilter` returns 404 directly for known scanner paths and a bare 400
for a syntactically invalid query string (an empty-named chunk such as `/?=phpinfo()`, which Tomcat's
parameter parser refuses — see REQ-OBS-001 for why that reject must not use `sendError`);
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
frontend container). Without the relay every per-IP bucket collapses onto the single frontend
container IP, so one caller can trip a public endpoint's budget for the whole organisation. The relay
never overwrites an existing header and degrades silently to "frontend IP" for background tasks with
no bound request.

**Attribution MUST resolve the chain right-to-left, ahead of `ForwardedHeaderFilter`.** Reading the
*first* hop is only safe while exactly one trusted hop writes the header, which is true of the
frontend relay and false of any appending proxy: nginx-proxy-manager uses
`$proxy_add_x_forwarded_for`, so the real peer lands on the **right** and everything left of it is
client-supplied. The resolver MUST therefore honour the header only when the immediate peer is a
trusted proxy, then walk the chain from the right, skip trusted hops, and take the first untrusted
address.

It MUST run **before** Spring's `ForwardedHeaderFilter`. That filter rewrites `getRemoteAddr()` to the
leftmost — client-chosen — entry and hides the header from everything downstream, so a later filter
can neither see the real peer nor re-derive it. Because `server.forward-headers-strategy: framework`
pins it to `Integer.MIN_VALUE`, which nothing can precede, the backend sets the strategy to `none` and
`ForwardedHeaderConfig` re-registers it at `HIGHEST_PRECEDENCE + 1`, one slot behind
`ClientIpContextFilter`. Scheme/host rewriting for problem-detail `instance` URIs, `Location` headers
and HSTS is unchanged. The resolved address and its provenance are published as request attributes;
`RateLimitingFilter` consumes them and MUST NOT re-derive the address itself, so there is one
implementation of the walk rather than one per consumer.

**Acceptance**

- [x] With peer = a trusted proxy and chain `9.9.9.9, 203.0.113.7`, the resolved client is
  `203.0.113.7`; rotating the leading entry does not yield a second rate-limit bucket.
- [x] An untrusted peer's `X-Forwarded-For` is ignored entirely.
- [x] A chain consisting only of trusted hops falls back to the peer and is reported as `peer`.
- [x] Empty elements are skipped. A non-IP token (`unknown`) does **not** throw and is **not**
  trusted: it terminates the walk like any untrusted hop and becomes the resolved client, which
  is the safe direction — treating an unparseable hop as one of our own proxies would let it be
  skipped over.
- [x] The `key_source` label distinguishes a resolved client from a peer fallback, so a collapse
  stays visible without logging an address.
- [x] The framework behaviour the ordering depends on is pinned by a test, so a Spring upgrade that
  changed it would fail rather than silently regress attribution.

**Enforced by:** `ClientIpContextFilterTest`, `ForwardedHeaderRewriteTest`, `RateLimitingFilterTest`
· **Code:** `ClientIpContextFilter`, `ForwardedHeaderConfig`, `RateLimitingFilter` (backend),
`ClientIpContextFilter`, `ClientIpRelayFilter`, `ForwardedHeaderConfig` (frontend)

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
- [x] Both order-create variants — `POST /api/v1/orders` and the heavier item-order
  `POST /api/v1/orders/items` (which derives materials from blueprints and takes a table-wide
  pessimistic lock) — share the tight per-endpoint `order-create` budget. The rule lists both paths
  explicitly because `RateLimitingFilter`'s `PathPattern` uses exact-segment matching, so the parent
  `/api/v1/orders` alone does not cover the `/items` child. Both required a login since ADR-0149;
  the per-IP budget is kept rather than deferred to the subject bucket, because an authenticated
  caller still arrives from an IP and the endpoint's cost is what the tight budget is about.

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

**2026-07-20 — a third, rotation-independent cause: the token backchannel reused an edge-reaped
keep-alive socket.** With rotation off and the `scope` leak severed, an intermittent forced re-login
remained; its cause is transport, not protocol. The frontend's `authorization_code` and
`refresh_token` grants call Keycloak's token endpoint, whose URL derives from the public `issuer-uri`,
so they hairpin **out through the public NPM edge**. Spring Security 7's default `RestClient`
token-response clients run on reactor-netty's **global** connection pool which — unlike the app's own
`frontend-pool` / `frontend-sse-pool` (ADR-0078) — has **no idle eviction** (verified from the shipped
bytecode). The edge reaps idle keep-alive sockets after ~60–75 s, so a refresh grant reusing one fails
with reactor-netty's `PrematureCloseException`, surfacing as an intermittent auth-path 5xx / forced
re-login (the `refresh_token` grant is the hot, exposed path — every active session refreshes on
access-token expiry). Both token-response clients are given a dedicated, idle-evicting pool
(`frontend-oauth-pool`, `maxIdleTime` 20 s below the edge keep-alive, `evictInBackground` 10 s,
`metrics(true)`) by replacing **only** the token client's `RestClient` transport — its
`FormHttpMessageConverter` + `OAuth2AccessTokenResponseHttpMessageConverter` and the
`OAuth2ErrorResponseErrorHandler` are preserved, and **no** retry is added (a retry would replay the
refresh token and trip reuse detection). It is a transport-only change, orthogonal and neutral to
rotation, single-flight and the scope-leak fix (ADR-0115).

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
- [ ] The frontend OAuth2 token backchannel (login `authorization_code` and recurring `refresh_token`
  grants) runs on a dedicated reactor-netty pool (`frontend-oauth-pool`) whose idle eviction is below
  the edge keep-alive, so a refresh does not reuse an edge-reaped socket and fail with
  `PrematureCloseException`; the `RestClient` transport swap preserves the form-request +
  token-response converters and the OAuth2 error handler (a real `refresh_token` grant still
  round-trips) and adds no retry that would replay the refresh token (ADR-0115).

**Enforced by:** `SingleFlightAuthorizedClientManagerTest`, `OAuth2ScopeRequestParamLeakTest`,
`WebClientConfigOauthTokenPoolTest`, `NotificationPageControllerStreamTest`,
`GlobalExceptionHandlerTest`, `BackendApiClientResilienceTest`, `SsoReAuthenticationEntryPointTest`
· **Code:** `SingleFlightAuthorizedClientManager`, `WebClientConfig`,
`ReauthenticationRequiredException`, `BackendApiClient`, `GlobalExceptionHandler`,
`NotificationPageController`, `SsoReAuthenticationEntryPoint`, `krt-fetch.js` · **Issues:**
ingest-rollout regression, #1137 · **ADR:** ADR-0019, ADR-0115

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

`BackendRoleSyncFilter` (which enriches the principal with backend-DB roles/permissions) MUST stamp a
session as synced (`BACKEND_ROLES_SYNCED_AT`) only when the `/api/v1/users/me` read genuinely
succeeded. A Resilience4j fallback (`null`, no exception) or a thrown error MUST leave the stamp
unset so the next request retries, rather than poisoning the session with an under-privileged
principal.

**Session-cached authorization state MUST be TTL-refreshed, never pinned for the session's
lifetime** (ADR-0122). Both values the filter caches are decided by an admin *mid-session*, and an
authenticated session lives 720 h (REQ-SEC-025), so a one-shot resolve can never observe the
decision:

- The **approval verdict** (`BACKEND_APPROVAL_STATE`) is cached with its read time
  (`BACKEND_APPROVAL_CHECKED_AT`). `ACTIVE` is terminal — the backend only ever decides a still-
  `PENDING` registration — and stays cached; a non-terminal `PENDING`/`REJECTED` verdict expires
  after 15 s, so an approval reaches a live session without a re-login (REQ-SEC-017).
- The **role sync** repeats every 60 s, and immediately when the filter observes the
  `PENDING → ACTIVE` transition, so a role, permission or org-unit membership granted after login
  reaches the principal without a new session. It reads `/api/v1/users/me`, i.e. the backend's local
  mirror: an org-unit membership (written locally) therefore lands within the interval, while a
  **Keycloak realm role** first has to reach that mirror through the next access-token refresh
  (`accessTokenLifespan` 300 s), so it takes up to ~5 min longer.
- **Static assets skip the filter body**, so the two refreshes cost roughly one backend read per
  interval per session rather than one per page asset.
- A backend read that fails MUST leave the cached verdict and its stamp untouched (retry on the next
  request), never downgrade a known verdict.

The sync **reconciles in both directions** — it grants what the backend reports and revokes what it
no longer reports — so the frontend principal converges on the backend's authority set instead of
drifting from it. The removal rule is asymmetric by ownership (ADR-0122):

- **`ROLE_*`** — the backend response is authoritative for the whole vocabulary; a role it no longer
  reports is dropped. Its local `role` catalog is where realm roles are mirrored and is what its own
  `@PreAuthorize` gates read, so keeping such a role only renders UI that 403s. Technical realm roles
  with no catalog entry (`offline_access`, `default-roles-*`) are dropped with it.
- **Every other authority** — revocable only when a *previous* sync asserted it, tracked in
  `BACKEND_SYNCED_AUTHORITIES`. Permission strings carry no prefix and are indistinguishable from the
  login-owned `OIDC_USER` / `SCOPE_*` authorities, so this rule makes stripping one of those
  structurally impossible.
- A response carrying no role (or no permission) list asserts nothing and MUST revoke nothing —
  silence is never "everything withdrawn". Likewise a failed read changes no authority at all.

Revocation is **not** immediate and is not the access boundary: an org-unit membership leaves within
the re-sync interval, a Keycloak realm role only after it has left the backend's mirror via the next
access-token refresh. The boundary remains the backend, which re-derives authorities per token under
its own 30 s memoisation; anything that must revoke instantly needs the session terminated.

**Acceptance**

- [ ] `FrontendAuthHelperService.isMemberOrAbove()` is true for any member/elevated `ROLE_*` on the
  Authentication token and false for anonymous, missing-context and role-less GUEST callers.
- [ ] A member's `GET /missions/{id}` triggers the member-only finance-entries fetch; an anonymous
  visitor's does not.
- [x] `BackendRoleSyncFilter` does not stamp `BACKEND_ROLES_SYNCED_AT` when `/api/v1/users/me`
  returns `null` or throws; it does stamp it when the read succeeds.
- [x] A cached `ACTIVE` verdict is never re-read; a cached `PENDING` verdict is re-read once its
  15 s interval has elapsed and not before, and observing `→ ACTIVE` drops the role-sync stamp so
  the unlocked authorities are pulled on that same request.
- [x] The role sync re-runs once its 60 s interval has elapsed and is skipped inside it.
- [x] A static-asset request performs no backend read at all.
- [x] A `ROLE_*` the backend no longer reports is removed from the principal; a permission is
  removed only when a previous sync asserted it; `OIDC_USER` / `SCOPE_*` survive every sync; and a
  response with a `null` role/permission list revokes nothing.

**Enforced by:** `FrontendAuthHelperServiceTest`, `BackendRoleSyncFilterTest`,
`MissionPageControllerMvcTest` · **Code:** `FrontendAuthHelperService`, `MissionPageController`,
`BackendRoleSyncFilter` · **Issues:** mission-finance-panel regression, post-approval double
re-login · **ADR:** ADR-0122

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

### REQ-SEC-043 — The Keycloak user sync MUST page the full user list before reconciling deletions

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

**The service account needs `view-realm` on top of `view-users`.** The roster page (`GET /users`) and
its per-user reads need only `view-users`, but the role-indexed resolution lists realm roles (`GET
/admin/realms/{realm}/roles`) and reads their members (`GET /roles/{name}/users`), both of which the
Keycloak Admin API gates behind the `view-realm` realm-management role. The `backend-service` service
account MUST therefore hold **both** `view-users` and `view-realm`. A service account carrying only
`view-users` (the pre-role-indexing requirement) fails closed on the `GET /roles` listing with a `403`
every run: the run skips (no wipe), but the sync never reconciles — departed members keep their local
roles and role changes propagate only via interactive login. `KeycloakService.fetchUsers` logs the
401/403 case with an explicit "missing `view-realm`" hint (naming the offending service account) rather
than the generic fetch-failure message, so the daily failure is diagnosable from one log line. See
[`docs/keycloak/README.md`](../keycloak/README.md) for the exact grant command.

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
a 5xx on a role's member read yields an empty result (run skipped, no degraded roles); a clean 404
on a role's member read keeps the roster with that role simply absent; and a `403` on the realm-role
listing (a service account missing `view-realm`) yields an empty result and increments the
`basetool_keycloak_sync_fetch_failures_total` counter.

**Enforced by:** `KeycloakServiceTest` · **Code:** `KeycloakService.fetchAllUsers`,
`KeycloakService.fetchRealmRoleNames`, `KeycloakService.fetchRoleMemberships`,
`KeycloakService.logFetchFailure`, `KeycloakSyncProperties.pageSize`, `UserSyncTask`

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

### REQ-SEC-018 — Anonymous guest sign-up edits require a per-row capability token *(superseded)*

> [!warning] Superseded 2026-09-06 by ADR-0159 / REQ-SEC-052 — kept for the reasoning
> There is no anonymous sign-up left to mint a token for, and `V239` dropped the column that stored
> its hash. An **external** participant row — a named person without an account — is the mission
> leadership's to edit, because it carries no creator to bind a self-edit to (decision D4).
>
> The text below is kept because its second half is the part that generalises: the token proved
> *which row*, never *whether the mission was still open*, and without a `canSeeMission` re-check
> beside it the capability outlived the surface that granted it — a guest who signed up while a
> mission was public kept `PUT`/`DELETE`/check-in after it was flipped to internal or reached
> `COMPLETED`, and back-dating a settled operation moved real money away from every other
> participant. A credential that needs a second gate to be safe is one nobody is holding correctly.
> `canManageMission` carries that scope check inherently, so nothing was lost by the removal.

*(Everything from here to the end of this requirement is in the past tense on purpose: it describes
the state until 2026-09-06.)*

Mission participant write endpoints (`PUT`/`DELETE`/check-in/out/payout on
`/api/v1/missions/*/participants/*` and the `…/slim` twins) **were** `permitAll` so the public
mission sign-up flow worked without an account. A **guest** (unlinked) participant row MUST NOT be
mutable by a caller who merely knows its id — the anonymous-readable roster exposed participant ids,
so a bare id was not an authorization secret.

- On creation of a guest sign-up the backend mints an unguessable 256-bit **capability token**,
  persists only its SHA-256 hash on `mission_participant.guest_edit_token_hash`, and returns the
  plaintext **once** in the create response (`MissionParticipantDto.guestEditToken`).
- Every subsequent guest-row mutate/delete is authorised by
  `MissionSecurityService.canAccessParticipant` iff the caller (a) presents a token (header
  `X-Guest-Edit-Token`) that hashes to the stored hash, OR (b) holds a mission-management role in scope
  (`canManageMission`). A guest row with no stored hash (pre-V177) is editable only via (b) — the gate
  **fails closed**.
- **The token proves *which row*, never *whether the mission is still open*.** Branch (a) MUST
  additionally require `OwnerScopeService.canSeeMission(missionId)`. Without it the capability
  outlived the surface that granted it: a guest who signed up while the mission was public kept
  `PUT` / `DELETE` / check-in on their row after the mission was flipped to `isInternal = true` and
  after it reached `COMPLETED` / `CANCELLED`. Because `OperationPayoutService` recomputes the time
  split on **every read**, back-dating `startTime` / `endTime` on a settled operation moved aUEC away
  from every other participant. `canSeeMission` covers both halves — it denies an internal mission
  to a non-member, and (audit hardening M-2) a terminal mission to an unauthenticated caller, which
  is what a token-only guest is. It is the same gate the read path already applied for this reason.
- **A token-only caller owns their row, not the organisation's fields on it.** Such a caller MAY
  edit `desiredMissionJobTypeId`, `comment`, `payoutPreference` and `guestName`. They MUST NOT set
  *or clear* `plannedMissionJobTypeId` — that is the Einsatzleiter designation, and setting it made
  the single-lead rule (REQ-MISSION-013) work against the organisation: once a guest held the lead,
  naming the real leader failed with `409` until somebody cleared the guest row. Clearing it by
  omission was the symmetric half, silently undoing a manager's assignment on an ordinary edit. The
  UI gates the planned-job select on `mission.canEdit`, which is presentation, not the boundary.
- **A rename MUST pass the same checks as the create.** A `guestName` change by a token-only caller
  is refused when it resolves to a registered member (`findMatchesByExactName`) or collides with
  another guest on the same mission. Both anonymous *create* paths already refused exactly that; the
  update path did not, so signing up under a throwaway name and renaming to a member's byte-exact
  callsign was the loophole around both — and because the payout key is `"guest_" + guestName`, the
  rename also merged two guest rows into one payout bucket and orphaned an already-settled
  `OperationPayoutStatus`, flipping a "Bezahlt" back to unpaid.
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

### REQ-SEC-021 — Anonymous outsider mission view withholds payout intent and free-text comments *(superseded)*

> [!warning] Superseded 2026-09-06 by ADR-0159 / REQ-SEC-052 — kept for the reasoning
> The audience this requirement was written for does not exist. A member below Logistician still
> gets a redacted mission detail (REQ-SEC-007), but payout preference and the free-text comment stay
> visible to them: a member is part of the organisation, and the two fields were withheld from
> people who were not.

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
net at the edge: `limit_req` (20 r/s sustained, burst 80, `nodelay`) and `limit_conn` (500
concurrent connections) keyed on the real client IP (`$krt_limit_key`: the full IPv4 address, or an
IPv6 client's `/64` network prefix), delivered through the custom snippets the
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

**Real client IP restored (ADR-0112).** The masking was IPv6-specific: `:443` is published on
`[::]:443` while the container network was IPv4-only, so Docker installed no `ip6tables` DNAT and the
userland `docker-proxy` relayed every IPv6 client through the bridge gateway (`$binary_remote_addr` =
`172.28.3.1`); dual-stack browsers prefer IPv6, so almost all real traffic collapsed onto one bucket,
and a 60-connection cap on it caused the 2026-07-20 outage (long-lived `/notifications/stream` (SSE)
and `/ws/sync` (WebSocket) connections crossed 60, the edge 429'd legitimate users, the frontend
degraded into the maintenance page). ADR-0112 made `net-proxy-frontend` dual-stack (`fd00:28:3::/64`)
so the kernel DNAT preserves the client IPv6 for **this host** (`profit-base.online`); real v4 and v6
client addresses now reach nginx, so the per-IP limit is meaningful again and `limit_conn` was
tightened from the 10000 stopgap to **500** concurrent connections per client. Two follow-ups have
since landed. **(1) IPv6 is keyed on its `/64`.** `http.conf` maps the limiter key to
`$krt_limit_key` — the full IPv4 address, or an IPv6 client's `/64` network prefix — so a
subscriber's rotating SLAAC privacy addresses (which vary only in the low 64 bits) share one bucket
instead of each minting a fresh one and diluting the cap. **(2) The other proxy hosts need no IPv6
subnet.** There is a single public `:443` ingress: the published-port DNAT targets NPM's dual-stack
leg on `net-proxy-frontend` (`[fd00:28:3::2]` / `172.28.3.2`) and NPM selects the vhost by SNI only
after accepting the connection, so keycloak/ingest/grafana already key on the real client IP too. The
bridge-gateway addresses that dominate those hosts' logs are internal hairpin traffic (blackbox
probes + the apps' OIDC hairpins to `keycloak.profit-base.online`), not masked external clients
(verified 2026-07-20). Do **not** disable userland-proxy: it deletes the only IPv6 datapath.

Stricter per-endpoint limits (e.g. the Keycloak login/token paths) may reference the same zones
from a proxy host's Advanced tab in the NPM UI; that is unversioned host state and out of this
requirement's scope. The backend's application-level Bucket4j limiter (REQ-SEC-009 family) is
unchanged and remains the precise, per-subject layer behind this coarse edge net.

**Acceptance**

- [ ] A burst above rate+burst from one client IP receives 429 responses (not the maintenance page,
  not 503) while other client IPs are unaffected (real per-client IPs on every vhost via the shared
  `net-proxy-frontend` v6 ingress, ADR-0112; IPv6 keyed on the `/64`).
- [ ] Legitimate concurrency (many members each holding the mission page's SSE + WebSocket) does not
  exhaust the 500 per-IP `limit_conn` and is never 429'd.
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
- **The knob lives on `app.security.jwt.*` and MUST NOT be declared under Spring's
  `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.** The two keys look interchangeable and
  behave oppositely when blank. The application's own key is read by
  `SecurityConfig#resourceServerJwtDecoder` behind an `@ConditionalOnExpression` that leaves the
  bean absent while the value is blank, which is what makes "empty = off" work. Boot's key has no
  such tolerance: a `${VAR:}` default binds as **present-but-empty**, the resource-server
  auto-configuration takes its jwk-set-uri branch and the context fails to refresh with `jwkSetUri
  cannot be empty`. Declaring it there took the whole E2E gate down repo-wide for a day
  (2026-08-19, #1597 → #1604): the backend container never became healthy, so every test class
  reported a stack bring-up error instead of its own result. `JwkSetUriNamespaceTest` now fails the
  build if any profile re-declares Boot's key.
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
- [ ] No profile declares `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`; the override is
  only ever `app.security.jwt.jwk-set-uri`, so an unset `KEYCLOAK_JWK_SET_URI` leaves the
  auto-configured decoder in place instead of failing the context at boot.
- [ ] A JWKS timeout / 5xx / DNS failure yields `503` + `Retry-After` (not `500`), logged at WARN
  and counted on `basetool_http_error_total{code="SERVICE_UNAVAILABLE"}`.
- [ ] An expired/invalid bearer token still yields `401`; a caller lacking the required role still
  yields `403` (the 503 re-map never swallows an auth decision).
- [ ] Optional `aud` enforcement (audit L-1) is available on both resource servers via
  `app.security.jwt.expected-audiences` (wired to `IRI_BACKEND_EXPECTED_AUDIENCES` /
  `IRI_INGEST_EXPECTED_AUDIENCES`), sharing the same `resourceServerJwtDecoder` bean. It is **empty
  by default** (off) so a stack whose realm does not stamp the audience is unaffected; enabling it
  in prod requires the deployed realm to stamp `aud=basetool-backend` (the `extractor-ingest`
  default client scope), or every token is rejected.
- [x] The **backend's** enforced path is exercised end to end, not only in prod: the E2E realm's
  `basetool-frontend` client carries an `aud-basetool-backend` audience mapper (access token only,
  mirroring the prod scope's mapper) and `E2eStackExtension` arms the stack with
  `IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend`, so every e2e-labelled PR runs the whole suite
  through the audience validator against real Keycloak-minted tokens. `E2eAudienceEnforcementParityTest`
  pins the enforced constant to the realm's mapper so the two cannot drift into a suite-wide 401.
  The **ingest gateway** is not part of the E2E stack and keeps unit coverage only.

**Enforced by (both resource servers):** backend `SecurityConfig#resourceServerJwtDecoder` +
`KeycloakTrustSupport` + `IdentityProviderUnavailableFilter` (tests:
`IdentityProviderUnavailableFilterTest`, `SecurityConfigInternalJwksDecoderTest`) ·
`SecurityConfigAudienceValidatorTest` + `E2eAudienceEnforcementParityTest` (the `aud` knob) ·
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

### REQ-SEC-027 — Approved client software is a contractual obligation, not only a gate

(REQ-SEC-026 — linking a pending Discord registration onto an existing account — is carried by
[`discord-integration.md`](discord-integration.md); this requirement continues the series at the
next free number.)

The rule that **only client software expressly approved by the operator may access the platform's
interfaces** is binding on users through the Terms of Use, not merely enforced at the ingest
gateway. Until 2026-08-03 it existed solely as operator documentation (`README.md`,
[`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md),
[`desktop-ingest.md`](desktop-ingest.md)) — text a user never sees and that is no part of the
agreement. Blocking the author of an unapproved client was therefore only possible under the
no-reason clause of terms section 8, which reads as arbitrary rather than as a named breach.

Terms section 4 now carries the obligation as its own bullet (`terms.list_4_1_5`), and it is
deliberately **broader than the ingest path**: it names the platform's interfaces generally — "in
particular the ingest interface and the HTTP APIs" — so the next interface inherits it without a
terms amendment. Three properties are load-bearing and must survive any rewording:

- **Develop, distribute *and* use are all covered.** Prohibiting only "use" leaves the author of an
  unapproved client untouched while their users carry the breach.
- **Own credentials are no defence.** Section 3(2) prohibits handing credentials to third parties
  and therefore does not reach the actual case: a member running a foreign tool under their *own*
  account. The bullet says so explicitly.
- **Approval is operator-granted, in text form, and revocable.** Approval by conduct (a tolerated
  client) or by a third party would hollow out the allowlist that
  [`desktop-ingest.md`](desktop-ingest.md) REQ-INGEST-011 enforces technically.

The technical gate and the contractual clause are **independent layers with different reach**: the
gate stops any unapproved client for everyone and is the operative control (REQ-INGEST-011); the
clause is what makes an individual's conduct a breach and thus supports a sanction under section 8.
Neither substitutes for the other — removing the gate does not become acceptable because a clause
exists.

**Acceptance**

- [ ] The obligation is rendered on `/terms` in both locales, not only declared in the bundle.
- [ ] The obligation names interfaces generally, not the ingest path alone.
- [ ] `terms.last_updated` reflects the date the obligation took effect (2026-08-03).

**Enforced by:** `TermsTemplateBundleParityTest` (every `terms.*` clause is both declared and
rendered — a renumbered section cannot silently drop a bullet), `MessageBundleConsistencyTest` (DE/EN
key parity, so the clause cannot exist in one locale only) · **Text:** `terms.list_4_1_5` in
`messages_de.properties` / `messages.properties` / `messages_en.properties`, rendered by
`templates/terms.html` · **Technical counterpart:** REQ-INGEST-011
([`desktop-ingest.md`](desktop-ingest.md)), ADR-0018

### REQ-SEC-028 — Terms-of-Use consent is recorded, versioned and enforced

Using the platform requires **recorded consent** to the Terms-of-Use wording currently in force.
Before this, the terms took effect merely on access (section intro) and section 12 treated
continued use as acceptance — which leaves no evidence of who agreed to which wording, the thing
actually needed when a clause is enforced against someone (REQ-SEC-027).

**The version is derived from the wording, never declared.** The root Gradle task
`generateTermsVersion` hashes every `terms.*` entry of the **backend's** German bundle into the
**committed** `backend/src/main/resources/terms-version.properties`, which the backend reads at
startup. It hashed the *frontend* bundle until the wording moved server-side (ADR-0138); the digest
must hash the text that is actually served, or a wording change ships with an unchanged version and
the gate quietly stops re-prompting.
Committed rather than build-generated because generating it made the backend build read a frontend
source file, which the backend Docker image's context does not carry (ADR-0127); drift is caught by
`TermsVersionParityTest`, so forgetting to regenerate fails CI rather than shipping a stale
version. Any wording change therefore
produces a new version and re-prompts everyone, with no number a human has to remember to bump.
`-PtermsVersion=<value>` pins it for one build when an edit was purely cosmetic, which leaves every
existing acceptance valid. It is generated for the **backend only**: the backend is the single
authority on consent, and a second copy in the frontend could disagree with the first.
`TermsVersionProvider` refuses to start when the resource is missing or blank — an empty version
would either block every user out of the whole API or, made lenient, wave everyone through a gate
that only looks armed.

**Consent is append-only.** `terms_acceptance` (V229) holds one row per user and version, never
updated; re-consent after a change adds history instead of overwriting it. The unique constraint
`uq_terms_acceptance_user_version` does double duty as the index behind the per-request lookup and
as the idempotence guard for a double submit.

**Enforced in the backend, surfaced in the frontend.** `TermsAcceptanceAccessFilter` refuses
`/api/**` with `403 TERMS_NOT_ACCEPTED`; `TermsAcceptanceGateFilter` redirects the web UI to
`/terms/accept`. The backend is the boundary because it is the one place every caller passes
through — the web UI and the desktop extractor. The extractor is covered because
`ActingMemberFilter` makes the sending member the security identity of a gateway call before this
filter runs (ADR-0129); it is **not** covered by bearer relaying, which that ADR removed. The
distinction is not academic: the first cut of the identity swap left this filter testing for a
`JwtAuthenticationToken`, the acting member carries none, and the gate returned "no user" — which
here means *let through*. The gateway needs no copy of the rule: it already relays a backend 4xx
with the backend's own `detail`.

**The frontend gate answers in the caller's own idiom — four shapes, not one.** A browser
navigation gets the `302`. An XHR gets `403` plus `X-Terms-Acceptance-Required`, because a redirect
fails silently there (`krtFetch` bails on `res.redirected` and the section just stops updating). An
`EventSource` gets a single `terms-gate` SSE event naming the consent page, then the stream closes,
because it can read neither a status nor a header — a redirect hands it the consent page as
`text/html`, the stream errors, and `notifications.js` reconnects on its jittered timer forever.
Exempting the stream path instead would only move the loop one hop: the relay would reach the
backend boundary and take the `403` there. And a WebSocket handshake gets no HTTP answer at all
— it is let through and the socket is then closed with `4003`, because a refused upgrade
reaches the client as a bare `1006` it can only read as "connection dropped" (see below).

**The `terms-gate` event has two emitters, because the gate has two holes it cannot cover itself.**
`TermsAcceptanceGateFilter` emits it for a stream it intercepts; `NotificationPageController`'s
`handleStreamError` emits the same event when the *backend* refuses a stream the frontend gate let
through. That happens in exactly two windows — the frontend verdict cache still holds a fresh
`true` from the 60 s before a wording change took effect, and the fail-open path when the status
read itself failed. Both are narrow, and both would otherwise reconnect forever. The event name and
the consent path are shared constants (`TermsAcceptanceGateFilter.SSE_GATE_EVENT` / `CONSENT_PATH`)
so the two emitters and the one client listener cannot drift apart.

**The verdict cache is read back on both sides.** Caching the negative but never honouring it made
every gated request a blocking backend round trip; during the 2026-08-03 rollout that turned 491
stream attempts plus 483 consent-page renders into 973 reads of `/api/v1/terms/status`. Honouring it
is safe because recording consent calls `clearCachedVerdict`, so nobody is held behind a stale "no",
and a backend failure never caches a negative in the first place (it fails open without writing).

**A WebSocket handshake is answered with a terminal close code, never a redirect.** A refused
upgrade — `302` to the consent page included — reaches the browser's `WebSocket` as `close` with
code `1006` and no reason, which is byte-for-byte what a dropped connection looks like. The client
therefore does the only correct thing for *that* and reconnects; `krt-live-sync.js` does so on
full-jitter backoff capped at 30 s for as long as any topic is registered, and consent can never be
given from a background socket, so the loop has no exit. The gate consequently **lets the upgrade
complete and marks it** (`support.TermsGateHandoff`, a request attribute the handshake interceptor
copies onto the session), and `LiveSyncWebSocketHandler` closes the socket at connect with **`4003`
carrying the consent-page URL as the close reason** — the first point at which a close code exists
at all. `krt-live-sync.js` treats `4003` as terminal: it stops reconnecting permanently and
navigates. The code mirrors HTTP `403` exactly as the socket cap's `4029` mirrors `429`, and the two
are guarded against drift by `LiveSyncCloseCodeWireParityTest` because a wrong number falls through
to the generic reconnect path silently. Marking rather than exempting keeps one owner of the
verdict: the gate's own 60 s-bounded read decides, and the relay only relays it — an exemption would
force a second consent read per handshake plus a second copy of the `test`-profile and
authentication carve-outs. Detection is keyed on the `Upgrade` header rather than the path, so an
encoded spelling of `/ws/sync` cannot slip back into the redirect (REQ-SEC-029).

**The rollout signal counts subjects, never requests.** `basetool_terms_refused_subjects` is a gauge
of the distinct subjects the gate refused in the last 15 minutes, and `TermsConsentRolloutStalled`
reads it with `max()` — per process, so `sum()` would double-count a subject that hit two instances.
A refusal *rate* cannot express the thing the alert is for: at 0.01/s one automated caller sustains
it indefinitely, so a single looping browser tab fired it twice overnight on 2026-08-03 with nobody
awake. A retrying client, a member reading the terms slowly and one straggler are each **one**
subject; three distinct people locked out at once is the shape of a broken consent path. The window
is bounded by construction (entries expire, and a hard cap drops *new* subjects so the gauge can
only ever under-report) because the feed is an internet-reachable refusal path.

Six invariants that must survive any rewrite:

- **The consent endpoints are never refused.** Refusing `/api/v1/terms/**` makes the block
  permanent for everyone, because no request would be left that could record consent.
- **No gated answer may be one the caller can only retry.** Every refusal has to carry something
  the specific client acts on. This is not cosmetic: a background channel that cannot distinguish
  "you must consent" from "the connection dropped" retries indefinitely, and one open tab is enough
  to sustain it — measured on 2026-08-03 as 491 stream attempts against 483 consent-page loads in
  ten minutes.
- **The terms, the privacy policy and the imprint stay reachable.** A gate that redirects those
  asks a person to agree to what it prevents them from reading.
- **No cache may outlive a wording change.** An authenticated session lives 30 days (ADR-0088), so
  the frontend verdict is re-read every 60 s; the backend caches only *positive* answers, which are
  monotonic within a process because the version in force is a build artifact.
- **The positive cache follows the commit, never precedes it.** `TermsAcceptance` carries an
  assigned `@Id` and no `@Version`, so `save()` issues no SQL of its own — the insert, and any
  constraint violation it trips, surfaces at *commit*, after the service method has returned. The
  in-memory "has accepted" verdict was written inline, i.e. on the failure path as well as the
  success path, so a caller whose insert never committed was remembered as consenting for the
  process lifetime with no row to show for it, and this gate then waved them through until the next
  deploy. The foreign key to `app_user` makes that reachable rather than theoretical. The verdict is
  therefore recorded in an `afterCommit` synchronization, the concurrent-race branch caches nothing
  at all (from an aborted transaction it cannot be established *which* constraint fired), and the
  cache carries a TTL so that even a wrong positive cannot outlive it.
- **A machine cannot consent.** `/api/v1/terms/**` is exempt from the gate — it has to be, or
  nobody could ever accept — so an authenticated non-person could otherwise clear the gate for
  itself and reach every `isAuthenticated()`-only read behind `anyRequest().authenticated()`. That
  is not hypothetical: the ingest gateway's own `app_user` row exists in production, created by the
  registration flow on its first call before the machine-identity carve-out (ADR-0129), and the
  `terms_acceptance` foreign key would have been satisfied by it. Recording consent is therefore
  refused for `ROLE_INGEST_GATEWAY` outright, which closes it in the mechanism rather than by
  deleting one row per environment — a cleanup migration would additionally risk aborting a deploy
  on one of the many non-cascading foreign keys into `app_user`.
- **The gate is armed by default.** It is stood down only under the `test` profile (MockMvc callers
  are synthetic subjects that cannot consent). A property that must be set to switch it on was
  rejected: it ships a gate that looks armed and is not. The stand-down had a cost that only
  surfaced later — no test could observe the gate at all, so a fail-open on the ingest path stayed
  green — so `app.security.terms.armed-in-test` re-arms it for a single test class. It is read
  **only** when the `test` profile is active and can only arm, never disarm; outside that profile
  the gate is armed unconditionally and the property is never consulted. The E2E profile is `dev`,
  so the gate is live there and `E2eSupport#acceptTermsIfPrompted` clicks through it on every login
  rather than pre-seeding a row — which keeps the suite exercising the real path.
- **A background caller identifies itself, checks for the gate, and never reads `res.ok` as
  success.** Writes get this from `krtFetch`; the reads that bypass it must do it themselves, and
  both halves are load-bearing. Without the `X-Requested-With` marker the gate answers a `302` that
  `fetch` follows transparently, so the consent page arrives as a `200 text/html` for which `res.ok`
  is **true** and the refusal is read as the payload. With the marker but without a
  `krtTermsGate.check`, the `403` merely falls through the `res.ok` test and the surface freezes on
  its last value with nothing on screen saying why. On a *polling* caller the first failure is worse
  still: if the timer is re-evaluated only after a successful parse, the refusal leaves it armed and
  the page re-fetches and re-renders the consent page every tick for as long as the tab is open.
  Both shapes were found while triaging the 2026-08-03 rollout, neither of them part of the measured
  traffic — the P4K import poll (3 s cadence) and the notification badge / bell reads, whose three
  call sites now share one gate-aware reader so they cannot drift apart again one at a time. Every
  hand-rolled `fetch` outside `krtFetch`
  therefore sends the marker, offers its response to `krtTermsGate.check` before touching the body,
  rejects `res.redirected` explicitly, and disarms its own timer on any answer that is not the
  payload it asked for.

**The wording itself is a backend resource, and it is readable without a token** (ADR-0138). `GET
/api/v1/terms/document` returns the text as structured data — title, intro, ordered sections, each
with its paragraphs and bullets — together with the version an acceptance would be recorded against.
Both clients render from it: the web frontend's public `/terms` page and its consent gate, and the
Android app's terms screen. Before it existed the wording lived in the frontend's message bundle and
the app could not reach it at all, leaving only a copy in the APK (which drifts from the version
being accepted) or a trip to a browser mid-consent.

Anonymous, deliberately: **a text everybody must read before agreeing to anything cannot require
having agreed.** The public `/terms` page is reachable with no session and must stay so, and the
identical wording is already served to the world there — this publishes the same bytes through a
different door. The *record* of consent is not opened with it: `/status` and `/acceptance` stay
authenticated, and the two live in separate controllers so the split is visible rather than hidden
as a method-level override. A `permitAll` written one segment short — `/api/v1/terms/**` — would
open the record too, so `SecurityTest` pins both halves.

**The key convention is the schema.** `terms.h1_4` *is* the declaration that a fourth section
exists; `TermsDocumentService` walks the numbering and stops at the first gap. A clause added to the
bundle therefore reaches both clients with no code change — and a gap truncates the document
silently, which is why `TermsDocumentStructureTest` fails the build when a `terms.*` key is not
reachable by the walk, and when a translation's shape differs from the German original.

The endpoint is part of the frozen external contract set (REQ-API-009, ADR-0136): a field dropped
from this response blanks a legal document on a build nobody can redeploy.

**Acceptance**

- [ ] A user without consent cannot reach any `/api/**` endpoint but the consent ones.
- [ ] A wording change re-prompts every user, without anyone editing a version number.
- [ ] Consent history survives re-consent; a double submit adds no second row.
- [ ] An admin can see who has and has not accepted.
- [x] No background channel is left with an answer it can only retry: the `/ws/sync` handshake is
  refused with a terminal close code the client stops reconnecting on.
- [ ] A gated background read navigates to the consent page and disarms its timer, instead of
  re-fetching the refusal on every tick or freezing on its last value.

**Enforced by:** `TermsAcceptanceAccessFilterTest` (refusal, both exemptions, non-UUID subjects),
`TermsAcceptanceGateFilterTest` (redirect, the AJAX header, the SSE `terms-gate` handoff and that it
fires only while the gate is closed, the WebSocket mark and that a plain request to the same
path is still redirected, the readable-documents exemption, fail-open, cache bound),
`HandRolledFetchGateContractTest` (the client half of every read that bypasses `krtFetch`: the XHR
marker, the `krtTermsGate` handoff, no `res.ok` shortcut, self-disarm — pinned against the shipped
JS), `TermsAcceptanceQueryDataTest` + `TermsAcceptanceServiceTest` (append-only history,
version scoping, one-sided cache, sort translation), `TermsAcceptancePageControllerTest`, `TermsVersionParityTest`,
`AdminTermsPageControllerTest`, `TermsTemplateBundleParityTest`,
`LiveSyncSyncHandshakeInterceptorTest` + `LiveSyncWebSocketHandlerTest` +
`LiveSyncCloseCodeWireParityTest` (the WebSocket handoff: the mark is relayed, the socket is closed
with `4003` and the consent URL, the refusal costs no per-user socket slot, and the code cannot
drift from the client's) · **Code:** `TermsVersionProvider`, `TermsAcceptanceService`,
`support.TermsConsentCheck` (the leaf interface that keeps `config` and `service` acyclic per
ADR-0047), `support.TermsGateHandoff` (the leaf that does the same for the frontend's `config` →
`websocket` handoff), `TermsController`, `AdminTermsController` · **Monitoring:**
`basetool_terms_acceptances_total`, `basetool_terms_accepted_users`,
`basetool_terms_refused_subjects`, `TermsConsentRolloutStalled`,
`basetool_livesync_socket_rejected_total{reason="terms_gate"}` · **Decision:** ADR-0128

### REQ-SEC-029 — A path-scoped filter matches the DECODED path, never the raw request URI

Any servlet filter whose scope is a path — "apply to `/api/**`", "skip unless `/v1/**`", "cap these
configured paths" — MUST decide that on the **decoded** path, by matching a parsed `PathPattern`
against `PathContainer.parsePath(...)`. It MUST NOT use `HttpServletRequest#getRequestURI()` in a
`startsWith` / `equals` / `List#contains` test.

`getRequestURI()` is the **raw, still percent-encoded** URI per the servlet spec, while Spring MVC
routes on the **decoded** path. The two therefore disagree, and the disagreement is exploitable in
one direction only: `GET /%61pi/v1/missions` fails a raw prefix test — the filter skips it — and
`RequestMappingHandlerMapping` then decodes `%61pi` back to `api` and dispatches it to the handler
anyway. The default `StrictHttpFirewall` does not close this: it blocklists `%2e`, `%2f`, `%5c`,
`%25`, `%00`, `;` and `//`, but not ordinary letter escapes, and no custom `HttpFirewall` is
installed. `ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication().value()`
is **not** the fix either — `PathContainer.Element#value()` is contractually the unmodified
original; `PathSegment#valueToMatch()`, which `PathPattern` matches on, is the decoded one.

**Direction matters, and only one direction is a defect.** A raw test that decides *inclusion in a
protective scope* fails **open** — this is the defect. A raw test that decides an *exemption from* a
gate fails **closed**: encoding can only break such a match, so the caller gets more enforcement,
not less. Converting an exemption list to decoded matching would *widen* it, so the two frontend
UX-routing filters deliberately keep their raw string tests — `BackendRoleSyncFilter` (waiting-page
redirect exemptions, static-asset skip) and `TermsAcceptanceGateFilter` (consent-page redirect
exemptions) — as do the deny-list bot filters and the backend access log's skip list. The boundary
in both those cases is the backend gate, which does match on the decoded path.

The rule is enforced by tests, not by review: each converted site carries a **direct filter test**
driving an encoded spelling. It cannot be a MockMvc test — MockMvc normalises the path before the
filter runs, so such a test passes against the broken code.

**Acceptance**

- [x] The two `/api/**` access gates refuse `/%61pi/…`: the pending-approval gate (REQ-SEC-017) and
  the consent gate (REQ-SEC-028).
- [x] The four ingest-scoped filters — client identity (REQ-INGEST-011), payload cap, rate limit and
  the access log — share one `IngestPathScope` decision made on the decoded path, so an encoded
  spelling can neither shed a gate nor go unlogged.
- [x] The backend body-size cap matches its configured paths as patterns against the decoded path;
  the scope stays exact, so a path merely prefixed with a configured one is still uncapped.
- [x] API GET responses keep their revalidation headers under an encoded spelling.
- [x] The acting-member bound is matched on the decoded path: the ingest gateway may name another
  member only on the two import endpoints, in both directions — an encoded spelling of an unbound
  path stays refused, and an encoded spelling of a bound one still acts (ADR-0129).
- [x] Every converted site has a direct filter regression test that fails against the raw idiom.

**Enforced by:** `PendingApprovalAccessFilterTest`, `TermsAcceptanceAccessFilterTest`,
`ActingMemberFilterPathMatchingTest`, `IngestPathScopeTest`, `ClientIdentityFilterTest`,
`FiltersTest`, `RequestLoggingFilterTest` (ingest), `RequestBodySizeLimitFilterTest`,
`ApiCacheControlFilterTest` · **Code:** `IngestPathScope`, `PendingApprovalAccessFilter`,
`TermsAcceptanceAccessFilter`, `ActingMemberFilter`, `RequestBodySizeLimitFilter`,
`ApiCacheControlFilter`, `RateLimitingFilter` (backend + ingest), `PayloadSizeLimitFilter`,
`RequestLoggingFilter` (ingest)

### REQ-SEC-030 — The native mobile client's refresh token MUST be sender-constrained, its access token MUST NOT be

The public Android client (`basetool-android`) MUST hold a **DPoP-bound refresh token** and MUST
receive **plain Bearer access tokens** carrying no `cnf` claim. RFC 9700 §2.2.2 requires a public
client's refresh token to be sender-constrained or rotated; rotation is unavailable because
`revokeRefreshToken` is a realm setting deliberately left off (REQ-SEC-012), so binding is the only
remaining path. The access token must stay unbound because the backend's resource server refuses a
`cnf.jkt`-bearing token on the plain Bearer path.

The binding MUST be produced by a **client policy**, not by the per-client "Require DPoP bound
tokens" switch: a client profile whose only executor is `dpop-bind-enforcer` with
`allow-only-refresh-token-binding` enabled, attached by a policy scoped through the `client-roles`
condition naming a marker client role that only this client carries. The per-client attribute
`dpop.bound.access.tokens` MUST remain `false` — setting it overrides the profile and re-binds the
access token.

The client MUST keep direct access grants disabled. Beyond the ordinary reason, the password grant
**mis-reports this very requirement**: it binds the access token on the initial grant and narrows it
only from the first refresh, so a verification run through it draws the opposite conclusion to the
authorization-code flow the app uses.

The app MUST send DPoP proofs on token-endpoint calls only, never on an API call, and MUST take
profile claims from the ID token rather than `/userinfo`, which answers HTTP 500 for a client under
this policy.

Realm changes implementing this MUST merge into the two client-policy lists rather than replacing
them — both endpoints replace the realm-global list wholesale — and MUST apply the client
configuration **before** attaching the policy, because Keycloak refuses every write to the client
**representation** (`PUT clients/{id}`) while the policy is attached. The lock stops there: the
client's sub-resources, role scope mappings among them, stay writable with the policy in place
(measured, Keycloak 26.7, 2026-08-21), so a REQ-SEC-035 scope change needs neither the detach dance
nor the provisioning service account.

**Acceptance**

- [x] In the authorization-code flow the token response is `token_type: Bearer`, the access token
  carries no `cnf`, and the refresh token carries `cnf.jkt` (verified against Keycloak 26.7,
  2026-08-17).
- [x] A refresh presented without a proof, or with a different key, is refused — re-measurable with
  `scripts/verify-dpop-binding.py`, which drives the authorization-code flow the app uses and fails
  the run unless the refusals carry `invalid_dpop_proof` and `invalid_grant` respectively
  (2026-08-19, Keycloak 26.7).
- [x] The binding survives refresh-token rotation.
- [x] A client in the same realm without the marker role is unaffected.
- [x] The provisioning script merges by name, preserving foreign client policies, and applies
  detach → configure → attach in that order.
- [x] Verification fails loudly when `dpop.bound.access.tokens` is flipped on, when the marker role
  is missing, or when the policy is present but unscoped.

**Enforced by:** `provision-keycloak-mobile-client.test.sh` (configuration) and
`scripts/verify-dpop-binding.py` (behaviour) · **Code:**
`scripts/provision-keycloak-mobile-client.py` · **Decision:**
[ADR-0131](../adr/0131-mobile-auth-refresh-only-dpop-binding.md) · **Measurements:**
[`ANDROID_API_EXPOSURE_PLAN.md`](../ANDROID_API_EXPOSURE_PLAN.md) section 7

### REQ-SEC-031 — Sensitive GET families MUST be uncacheable, not merely revalidatable

API GET responses of every sensitive family MUST carry `Cache-Control: private, no-store`. Every
other `/api/**` GET keeps `no-cache, must-revalidate`. The families are the bank surfaces
(`/api/v1/bank/**` **and** `/api/v1/org-units/bank/**`), `/api/v1/users/**`, `/api/v1/me/**`,
`/api/v1/notifications/**`, the ledgers (`/api/v1/finance-entries/**`,
`/api/v1/missions/*/finance-entries/**`, `/api/v1/operations/**`), the holdings
(`/api/v1/personal-inventory/**`, `/api/v1/personal-blueprints/**`, `/api/v1/inventory/**`,
`/api/v1/hangar/**`, `/api/v1/refinery-orders/**`) and `/api/v1/promotion/**`.

**Both bank spellings, because they are different surfaces.** `/api/v1/bank/**` is the
bank-employee one; the member-facing account a shipped client actually reads lives under
`/api/v1/org-units/bank/**`, and its transaction rows carry a `holderHandle`. Listing only the
former — as this requirement originally did — left the member bank ledger, along with the mission
finance reads, the personal holdings and the refinery profit figures, on the storable directive:
exactly the families the public API vhost added, and exactly the data the rule exists for.

**A missing family is actively downgraded, not merely un-opted-in.** `ApiCacheControlFilter` runs at
`HIGHEST_PRECEDENCE + 20`, ahead of the Spring Security chain, and sets `Cache-Control` before
`CacheControlHeadersWriter` would — and that writer only acts when the header is unset. So a
sensitive family absent from the list loses the framework's own default `no-store`. Adding a
sensitive GET family means adding it to `NO_STORE_SCOPES` in the same change.

The Materialbörse (`/api/v1/material-exchange/**`, `/api/v1/material-requests/**`) is deliberately
excluded: it is an org-wide shared board whose handles are the same public callsign tuple the public
mission roster already serves, so it belongs in the revalidate bucket with the other shared
listings.

The distinction is not cosmetic. `no-cache, must-revalidate` permits an intermediary to **store** the
body and reuse it after a successful revalidation; only `no-store` forbids the copy existing at all.
For master data and mission lists the weaker directive is the right trade. For a bank ledger, a
member record — the only PII the API serves — or one person's notification feed it is not: a
corporate middlebox, a shared proxy or a browser disk cache would be holding data that must not
outlive the response.

While the backend was reachable only from the frontend across an internal network there was no
intermediary for the header to talk to. A public API vhost makes one plausible, which is what turns
this from theory into a control.

The scope MUST be matched against the **decoded** path (REQ-SEC-029), so an encoded spelling such as
`/api/v1/%62ank/accounts` cannot fall back into the weaker directive. The list is maintained in code
rather than configuration: which data is sensitive is a property of the domain, not of a deployment.

**Acceptance**

- [x] Every listed family answers with `private, no-store`, including the notification SSE stream.
- [x] The member bank, mission-finance, personal-inventory, personal-blueprint, inventory, hangar,
  refinery-order and promotion reads in the frozen external contract are covered — asserted at the
  bare collection path as well as a child, since the bare path is itself an endpoint a client calls.
- [x] An encoded spelling of a sensitive path still gets `no-store`.
- [x] Other `/api/**` GETs are unchanged (the shared listings keep `must-revalidate`), and non-API
  paths and writes are untouched.
- [x] `Vary: Accept-Encoding` is still set on the sensitive families.

**Enforced by:** `ApiCacheControlFilterTest` · **Code:** `ApiCacheControlFilter`

### REQ-SEC-032 — The anonymous surface MUST NOT be an amplification lever

> [!note] Amended 2026-09-06 (ADR-0159) — the lesson outlived the surface
> There is no anonymous surface left to bound: `AnonymousPageSizeFilter` and its page-size ceiling
> are gone, and no paginated endpoint answers an unauthenticated caller. The two that do
> (REQ-SEC-052) are unpaginated, and the per-IP limiter bounds them.
>
> **The verb-agnostic lesson below is the part that stays load-bearing.** A tightening placed above
> an all-verb `permitAll` must itself be all-verb, or the rule underneath grants every verb the
> tightening does not claim — that is how a `HEAD` once ran the material price matrix query
> anonymously and returned its `Content-Length`. The two remaining anonymous reads are `GET`-scoped
> on purpose, and `AnonymousSurfaceSweepTest` issues a `HEAD` against every `GET` mapping for
> exactly this reason.
>
> The `basetool_http_error_total{code="PAGE_SIZE_TOO_LARGE"}` label value disappears with the
> filter (REQ-OBS-011's bounded-label review).

`PaginationUtil` clamps `size` at 100 000. That is correct for the authenticated consumers that
page-walk large catalogues and far too generous for endpoints anyone on the internet can reach once
the API vhost is live: one request would return an entire catalogue, and repeating it is the cheapest
amplification the surface offers.

Two rules follow.

**The material x terminal price matrix MUST require authentication — both of its shapes.** `GET
/api/v1/materials/matrix` is the largest single response the API can produce and used to fall into
the catalog `permitAll` through `/api/v1/materials/**`. It is operating data rather than guest
content, and its only consumer is a page controller annotated `@PreAuthorize("isAuthenticated()")`,
so the carve-out costs nothing. Because Spring Security takes the **first** matching rule, the
authenticated matcher MUST stay above the catalog block; moving it below re-opens the surface with
no other symptom.

The matcher MUST be **verb-agnostic**, not scoped to `GET`. Spring Security compares the method with
`String.equals`, so a `HttpMethod.GET`-scoped tightening does not claim `HEAD` — which then falls
through to the all-verb catalog `permitAll` underneath, and Spring MVC answers `HEAD` from the
`@GetMapping` handler. The query therefore ran anonymously and the response's `Content-Length` came
back. The general rule: **a method-scoped tightening placed above an all-verb `permitAll` grants
every verb it does not claim.**

The anonymous page-size ceiling MUST be evaluated with **the same parser Spring's binder uses**
(`NumberUtils#parseNumber`), and a value that parser rejects MUST be refused rather than allowed.
`Integer.parseInt` is stricter: it throws on `0x186A0`, `#186A0` and on embedded whitespace, all of
which the binder accepts (it strips whitespace and honours the hex spellings). Treating
"unparseable" as "within the limit" therefore made the ceiling bypassable with three characters. An
**empty** `size=` stays exempt — it binds to `null` and is a legal request for the default page.

`GET /api/v1/materials/{id}/terminals` — the per-material slice of that same matrix, which the
inventory page uses to suggest where to sell — is covered by the identical reasoning and was
**missing from the rule until it was caught in production**. Its only consumer is authenticated, so
a token costs nothing there either, while leaving it out published UEX trade prices per material to
anyone who could reach the API vhost. The nightly `edge-deny-probe` asserted `401` for it from the
day the phase-3 paste landed and got `200` every night: the expectation was right and the matcher
was simply absent. Size is not the argument for this one — one material's terminal list is small —
which is why it has to be stated rather than inferred from the amplification rule above.

**The same data had three more doors, found on 2026-09-06.** `GET /api/v1/materials/prices-overview`
answered `200` anonymously, `GET /api/v1/materials/{id}/prices` answered `200`, and `GET
/api/v1/materials/profit-calculation` answered `500` — dispatched anonymously and crashing, which
is not a gate either. `MaterialPriceOverviewDto` carries `minPriceBuy` and `maxPriceSell`,
`MaterialPriceDto` carries `priceBuy`, `priceSell`, `scu*` and `terminalName`, and the profit
calculation is the route arithmetic over both. **All three MUST require authentication**, for the
identical reason as `matrix` and `{id}/terminals`: this is UEX trade data, not guest content, and
every consumer of it is an authenticated screen.

They were found while the Android app's Handel screens were being admitted at the API vhost
(`API_VHOST_ROLLOUT_RUNBOOK.md`, phase W). That ordering is the lesson worth keeping: **a path is
measured anonymously before it is admitted, and what the measurement says is acted on first.**
Admitting these as they stood would have published trade prices to the internet — the vhost would
have let them through and the backend would not have stopped them.

`GET /api/v1/materials/{id}` deliberately stays anonymous. `MaterialDto` is catalogue only — name,
quantity type, category, flags, **no price** — and `/api/v1/materials/search` has published those
same fields anonymously since the vhost's phase 2. Closing it is a different change with a different
reason, and it is pinned in `ApiVhostAnonymousSurfaceTest` so it stays a decision.

**An unauthenticated caller MUST NOT request more than 1000 entries per page**, and the refusal MUST
be an explicit `400` naming the limit rather than a silent reduction. Silently clamping is the defect
ADR-0104 forbids: the caller gets fewer rows than it asked for, cannot tell, and any surface built on
a single large page then presents an incomplete list as complete. A page-walking consumer is
unaffected either way, since it asks for pages until they run out. The ceiling is 1000 because that is
exactly what the existing anonymous callers request — the guest order form's pickers and the catalogue
page-walks — so it costs the legitimate flows nothing.

Authenticated callers keep the 100 000 clamp. The scope is matched on the **decoded** path
(REQ-SEC-029).

**Acceptance**

- [x] `GET /api/v1/materials/matrix` answers 401 without a token; the rest of the material catalogue
  stays anonymously readable.
- [x] `GET /api/v1/materials/{id}/terminals` answers 401 without a token (`SecurityTest`).
- [x] `GET /api/v1/materials/prices-overview`, `GET /api/v1/materials/{id}/prices` and `GET
  /api/v1/materials/profit-calculation` answer 401 without a token
  (`ApiVhostAnonymousSurfaceTest`), and the nightly `edge-deny-probe` asserts it.
- [x] `GET /api/v1/materials/{id}` stays anonymous — catalogue only, no price — and is pinned as a
  decision rather than left to drift.
- [x] An anonymous request with `size=50000` is refused with `400` and the stable code
  `PAGE_SIZE_TOO_LARGE`.
- [x] An anonymous request with `size=1000` still succeeds.
- [x] An authenticated caller with `size=50000` is unaffected.

**Enforced by:** `SecurityTest` · **Code:** `AnonymousPageSizeFilter`, `SecurityConfig`

### REQ-SEC-033 — An authenticated account MUST have a budget of its own

Every `/api/**` write (`POST`, `PUT`, `PATCH`, `DELETE`) and the notification SSE connect MUST be
bounded by a per-authenticated-subject token bucket, keyed on the JWT `sub`, in addition to the
per-IP buckets of REQ-SEC-011.

The per-IP limiter bounds a **network position**, which is the wrong unit in both directions. Behind
CGNAT many unrelated members share one IPv4, so a budget tight enough to matter throttles innocents;
and a caller with a pool of addresses is not bounded by it at all. The `sub` is bound to a Keycloak
identity and cannot be chosen by the client, so it is the only key that bounds an *account*. The
ingest gateway reached the same conclusion for the same reason (REQ-INGEST-005).

Reads other than the SSE connect are deliberately left to the per-IP budget: they are cheap and are
what a legitimate client hits most. Writes cost database work and produce audit rows, and an SSE
connect holds a server-side emitter open, so a reconnect loop is worth bounding by identity. Both
share one bucket on purpose — they come from the same client, and splitting them would let one
starve the server while the other stayed within its own budget.

Anonymous requests MUST pass through: they carry no subject to key on, and the per-IP limiter plus
the anonymous page-size ceiling (REQ-SEC-032) are their bounds. The budget MUST be enforced after
the pending-approval and terms gates, so a caller refused there does not spend a token first. The
scope MUST be decided on the **decoded** path (REQ-SEC-029). The rejection MUST reuse the per-IP
limiter's contract: `429`, the stable code `RATE_LIMIT_EXCEEDED`, and the
`X-Rate-Limit-*` headers including a retry hint.

The subject MUST NOT appear in a metric label or a log message — it is unbounded and it is PII. The
bucket map MUST be bounded so a flood of distinct subjects cannot grow it without limit.

**Acceptance**

- [x] A second write beyond the budget from the same subject is refused with `429` and a retry hint.
- [x] Two different subjects do not share a bucket.
- [x] Ordinary reads spend no tokens; the SSE connect does.
- [x] An encoded spelling of an API write cannot shed the budget.
- [x] Anonymous callers pass through untouched.
- [x] Rejections and attempts are counted under the bounded `bucket=subject` label.

**Enforced by:** `SubjectRateLimitingFilterTest` · **Code:** `SubjectRateLimitingFilter`,
`RateLimitProperties.Subject`, `SecurityConfig`

### REQ-SEC-034 — A rejected registration MUST be recoverable through a supported admin action

Every approval state an account can be put into MUST have a way back out of it through the
application. A `REJECTED` registration MUST be visible to an admin and MUST be returnable to the
approval queue by an audited, admin-only action — without a manual database write and without
destroying the account.

Approval is fallible: an admin decides from a Discord handle and an optional server nickname, and a
member whose handle does not resemble their in-game name is exactly the case the automatic collision
check already fails to recognise (REQ-SEC-026). A wrong verdict is therefore expected, not
exceptional. Before this requirement it was also **terminal**: the queue read serves `PENDING` only,
so the row disappeared from the admin's view entirely, and the shared approve/reject body refuses
every non-`PENDING` row, so even an admin who knew the id was answered with a `409`. The account was
left permanently on the waiting page with no in-app remedy — the two supposed escapes both being
worse than the disease: a manual `UPDATE` against production bypasses the audit trail and violates
the read-only production policy, and deleting the account destroys its data and its history to
recover the person.

The reversal MUST move the account `REJECTED → PENDING` and MUST NOT be a `REJECTED → ACTIVE`
shortcut. The "only a still-`PENDING` registration may be decided" invariant exists to stop an
already-`ACTIVE` member from being silently stripped of their authorities by a re-decision, and
widening it to admit `REJECTED` would put the guard one editing mistake away from admitting `ACTIVE`
too. Routing the reversal through the queue keeps the invariant exactly as narrow as it is and makes
the re-approval travel the same audited path as any other approval.

The reversal MUST be refused for any account that is not `REJECTED`. A `PENDING` row needs no
reopening, and an `ACTIVE` member pushed back into the queue would lose their access — the very
failure the decision guard protects against.

The reversal MUST write its own audit row with its own decision value (`REOPENED`), not reuse
`APPROVED`. A reopen grants no access: the account lands `PENDING`, not `ACTIVE`. Recording it as an
approval would make the audit trail assert an access grant that never happened, and would make the
reversal indistinguishable from the re-approval that typically follows it moments later. The account
row's decision stamp (`approvedAt` / `approvedById`) MUST be cleared on reopen so a queued row never
displays a decision time; the history is not lost, because `user_approval_event` is the record.

The reversal MUST NOT notify. The approve/reject mail (REQ-NOTIF-014) announces a verdict and a
reopen is not one; the new-registration admin mail (REQ-NOTIF-012) would page the whole admin body
about an old registration the acting admin is already looking at.

The rejected-list read MUST serve `PENDING` and `REJECTED` only. `ACTIVE` is refused rather than
served — it would turn a small admin queue into an unbounded dump of every member, which is the user
administration surface's job and carries a different DTO.

**Monitoring interaction (deliberate).** `basetool_registration_pending_oldest_age_seconds` measures
from the registration's creation time, so reopening a months-old rejection makes the gauge jump to
that full age and can fire `RegistrationApprovalOverdue` (>48 h, `for: 10m`). This is accepted rather
than papered over: an admin who reopens and then decides within minutes never trips the `for` window,
and one who reopens and walks away has left a genuinely overdue item in the queue — which is what the
alert is for. Anyone triaging that alert immediately after a reopen should expect an age measured
from the original registration.

**Acceptance**

- [x] An admin can list the rejected registrations; a non-admin cannot.
- [x] An admin can reopen a rejected registration, which lands `PENDING` and re-enters the queue.
- [x] Reopening a `PENDING` or an `ACTIVE` account is refused with `409`, leaving its status and its
  access untouched.
- [x] A stale `version` on the reopen is refused with `409` and writes no audit row.
- [x] The reopen writes a `REOPENED` audit row carrying the acting admin and the optional note, and
  clears the account's stale decision stamp.
- [x] The reopen publishes no notification event.
- [x] `REJECTED → reopened → approved` reaches `ACTIVE` through supported actions only, leaving two
  audit rows.
- [x] `?status=ACTIVE` on the queue endpoint is refused with `400`.
- [x] The admin page renders the rejected table and moves a reopened row into the queue in place, with
  no full-page reload (REQ-FE-001).

**Enforced by:** `UserRegistrationServiceTest.ReopenRegistrationTests`,
`DiscordRegistrationAdminControllerSecurityTest`, `AdminDiscordRegistrationsNicknameRenderTest` ·
**Code:** `UserRegistrationService#reopenRegistration`,
`UserRegistrationService#findRejectedRegistrations`, `DiscordRegistrationAdminController`,
`ApprovalDecision#REOPENED`, `AdminDiscordRegistrationsPageController`, `discord-registrations.js`,
`V233__allow_reopened_user_approval_decision.sql` · **Decision:** ADR-0140

### REQ-SEC-035 — The mobile client's scope MUST carry the member realm roles, `Admin` included

The Keycloak client `basetool-android` runs with `fullScopeAllowed: false`, so the realm roles its
tokens carry are exactly the ones mapped onto its client scope. That list MUST be
`KRT Member`, `Officer`, `Bank Employee`, `Bank Management`, `Admin` — and the provisioning script
MUST converge the scope to exactly that list, taking back anything else.

> [!warning] Reversed 2026-09-02 — this requirement used to forbid `Admin`, and the ban was wrong
> The original text read *"and it MUST NOT contain `Admin`"*. The repository owner reversed it after
> using the app as an administrator. The reversal is written here beside the original reasoning
> rather than replacing it: the ban was a deliberate decision, and a reader deserves to see both why
> it was made and why it did not survive contact with the person it applied to.
>
> **What the ban actually did.** An administrator holds no Staffel membership by design. With
> `Admin` stripped from the token the app offered them no org unit to pin, no way to widen a list,
> and — the part that matters — „Alle Org-Einheiten" resolved to *their own empty reach* rather than
> to everything, because `RequestScopeResolver#currentScopePredicate` grants `adminAllScope` only to
> a caller the token says is an admin. Measured on the test stack 2026-09-01: the same account read
> **784.8 SCU** of Lager through the app's scope and **1403.4 SCU** with `Admin` present.
>
> **What the ban bought, stated exactly.** Not containment of an administrator's rights on the
> device: the same unlocked phone holds a 720-hour web session (`app.session.authenticated-timeout`)
> carrying the full role set, against a UI genuinely built for admin work. What it bought is that a
> **stolen access token, replayed from another machine inside its 300-second lifetime**, was
> member-scoped rather than admin-scoped. That is the whole of what is given up, and it is real —
> the access token is a plain Bearer with no `cnf`, because ADR-0131 binds the *refresh* token only.
>
> **Where the original reasoning was wrong.** It said the app "has no screen designed around" admin
> scope. Two of the three surfaces it meant already existed: the switcher's „Alle Org-Einheiten" row
> *is* the unpinned all-scope state, and the bank staff surface is gated on `bankEmployee`, which
> the mobile scope already carried — so an admin holding a bank role already reached it, and the app
> reads `bankManagement` on no screen at all. What was genuinely missing was smaller, and is fixed
> in the same unit of work: the app auto-pinned the first unit of the catalogue on a cold start
> (`OrgUnitViewModel`), and the switcher sheet could not scroll, which put „Alle Org-Einheiten" out
> of reach once the list held every unit.

Both halves of the list are load-bearing, and neither is a matter of taste.

**Why the member roles must be there.** The backend does not read the token's roles for
authorization directly: `UserReconciliationService#syncUser` **replaces** the account's local role
set from `realm_access.roles` on every authentication, falling back to `Guest` when the claim
carries none, and `CustomJwtGrantedAuthoritiesConverter` then derives the request's authorities from
that stored set. A client with no scope mappings therefore does not merely narrow what the app may
do — it rewrites the member's row in the database, for the web app too. Measured on the test stack
before this requirement existed: an account holding `Admin` + `Officer` + `KRT Member` was left
holding `Guest` alone after one app login.

**Why `Admin` must be there.** For the same reason as the rest, one tier up. `ADMIN` is not a menu —
the admin area stays permanently web-only and no app screen renders it — it is a **scope rule**.
`RequestScopeResolver#currentScopePredicate` grants an admin without an active-org-unit header
`adminAllScope`, every org unit at once, and honours an admin's pin to a unit they do not belong to.
Withholding the role did not keep that rule out of a client that could not express it. It took the
rule away from the one member whose work needs it, on the one client they carry: an administrator
using the app was, in effect, a member with no memberships.

> [!note] The admin area staying web-only is unaffected
> That is a product decision about which screens exist (app owner, 2026-08-17), and nothing here
> builds one. The role changes what the *existing* screens are scoped to, not which screens there
> are.

**What this now depends on, and it is not in this repository.** With `Admin` in the token, the 105
`@PreAuthorize(HAS_ROLE_ADMIN)` sites and the eight admin URL matchers in `SecurityConfig` are
satisfied by an app-issued token. The app calls none of those paths, but a token is not a client —
the remaining boundary is the **default-deny vhost allow-list** of
[REQ-SEC-037](#req-sec-037--the-api-vhost-denies-by-default), which lives in the edge proxy's
database and therefore cannot be gated by any test in any repository here. Two consequences, both
load-bearing:

- `POST /api/v1/refining-methods` was the one allow-listed path carrying a bare `hasRole('ADMIN')`
  write. `refining-methods` is therefore added to the read-only family in
  [`API_VHOST_ROLLOUT_RUNBOOK.md`](../API_VHOST_ROLLOUT_RUNBOOK.md). Nothing loses a capability: the
  app only reads it (the refinery form's method picker) and the web admin does not traverse this
  vhost at all. **The runbook is the version-controlled copy of host state — this change is not live
  until the vhost config is applied on the host.**
- The allow-list is now load-bearing for what this requirement itself used to guarantee. Anyone
  widening it is widening what a stolen mobile token can reach.

**The scope stays partial, and the client stays on the partial-role-scope list.** Adding `Admin`
covers every realm role that is actually assigned to people, but the realm also holds `Guest`,
`Logistician` and `Mission Manager` — so `basetool-android` remains a partial-scope client and
remains named in `app.security.partial-role-scope.client-ids`. That keeps
[REQ-SEC-036](#req-sec-036--a-clients-role-claim-is-authoritative-only-if-its-scope-is-complete)'s
two properties in force, and the second of them is what makes this requirement work at all: the
app's claim never rewrites the stored role set, and the request is **authorised from the token**.
Were the client taken off that list, authorisation would move back to the database row and the
whole scope mapping would stop deciding anything.

**What the scope list does not decide.** Which realm roles ride on a client's scope decides what the
app *may* do; it does not decide whether the record of what was done can name the client. Those are
separate, and only the second one is a property of the trail: `Bank Employee` and `Bank Management`
are already on this scope, so mutations reachable from two clients are not hypothetical, and a
stolen access token replayed inside its 300-second lifetime acts with its member's authority
whatever the list says. `audit_event` therefore records the originating client on every row —
the same bounded `azp` mapping this file's `azp`-matched rules already rely on
([REQ-AUDIT-005](audit.md), ADR-0152). Changing the scope list is then a decision about authority,
not one that also silently changes what a post-incident review can reconstruct.

**Acceptance**

- [x] The provisioning script grants exactly `MEMBER_REALM_ROLES` and takes back anything else on
  the client scope, converging in both directions, so a role added by hand in the Admin Console does
  not survive the next run.
- [x] `--verify-only` fails when a listed role is missing **and** when a role the list does not name
  is present, and each message names the consequence rather than the symptom.
- [x] Keycloak's own scope evaluation for the client returns `['KRT Member']` for a plain member and
  `['Admin', 'Bank Employee', 'KRT Member', 'Officer']` for an administrator (measured against the
  test stack's `iri` realm 2026-09-02, after applying the provisioning script). The pre-reversal
  measurement (`['KRT Member', 'Officer']` for the same admin account, Keycloak 26.7, 2026-08-21) is
  exactly the behaviour that changed and is kept as the before-picture. **The member's token did not
  widen** — that is the non-regression this pair is measured for.
- [x] The realm is checked for the role names before granting: a rename upstream fails the run
  loudly instead of silently leaving the scope empty.
- [x] Walked on a device with an administrator's account (2026-09-02, `Pixel_10a` against the test
  stack): the switcher offers all eight org units of all four kinds, ordered OL → Bereich → Staffel
  → SK with „Alle Org-Einheiten" as the first row; a cold start lands on it rather than on a pinned
  unit; and the Lager under it reads **1403.4 SCU** (102.93 + 700 + 180 + 420.5), the org-wide
  total, against the 784.8 the same screen shows a member of one Staffel.

**Enforced by:** `provision-keycloak-mobile-client.test.sh` section 7 · **Code:**
`scripts/provision-keycloak-mobile-client.py` (`MEMBER_REALM_ROLES`, `FORBIDDEN_REALM_ROLES`,
`upsert_realm_role_scope`), `UserReconciliationService#syncUser`,
`CustomJwtGrantedAuthoritiesConverter`, `OrgUnitViewModel` (app) · **Decision:**
[ADR-0131](../adr/0131-mobile-auth-refresh-only-dpop-binding.md), and the 2026-09-02 reversal above

### REQ-SEC-036 — A client's role claim is authoritative only if its scope is complete

`UserReconciliationService#syncUser(Jwt)` mirrors a member's realm roles into `app_user` on **every**
authentication, and it does so by **replacement**: `user.setRoles(mapRoles(realm_access.roles))`.
That is correct only while every client's token carries the member's whole role list. It stopped
being correct the moment one client was deliberately given less.

A client whose Keycloak scope is narrowed — `fullScopeAllowed: false` plus a partial scope mapping,
which the mobile client of REQ-SEC-035 still is (its scope names five of the realm's eight roles) —
mints a token describing a **smaller member than the real one**. Persisting that description lets whichever client a member happened to use last
decide what the database says they are.

**The rule.** A token from a configured *partial-scope client* MUST NOT write the account's role set.
The stored set is left exactly as it was, and the request is authorised from the **token's** roles
rather than from the row.

Both halves are load-bearing and each is a defect without the other:

- Persisting the partial claim is the data loss this requirement exists to stop.
- Authorising from the row instead of the token would be **worse than the original defect**, and
  the 2026-09-02 reversal of REQ-SEC-035 did not soften that — it inverted which way the damage
  runs. While `Admin` was withheld, reading roles off the row would have handed the app an authority
  its token deliberately lacked. Now that the scope carries `Admin`, reading off the row is what
  would make the scope mapping stop deciding anything at all: `Logistician`, `Mission Manager` and
  `Guest` are still absent from it, so the row and the token still describe different members, and
  the token is the one the client was actually issued.

**Matched on `azp`**, a claim inside a Keycloak-signed token that a client cannot set — the same
handle `IngestGatewayProperties` already uses for the far more dangerous on-behalf-of decision, so
this adds no new trust. Configured under `app.security.partial-role-scope.client-ids`.

**Empty is the unsafe end here**, the reverse of the ingest gateway's list. There, empty means
"nobody may act for another member". Here, empty resumes overwriting stored roles from partial
tokens. The default therefore names the client known to be partial rather than shipping blank.

**A brand-new row is the one exception**, in the safe direction: there is no stored set to protect,
and the alternative is persisting a member with no roles at all, which the `Guest` fallback would
then stand in for permanently.

**The stored set still converges.** It is maintained by every client whose claim is complete and by
the daily Admin-API pass (`syncUser(KeycloakUserDto)`), which reads the realm directly and is
unaffected by any client's scope — so even a member who only ever uses the app has their row
corrected within a day.

**Monitoring.** Deliberately no new metric. The only quantity that varies is how much the
partial-scope client is used, and `basetool_api_client_requests_total{client_id="basetool-android"}`
already carries it (REQ-OBS-018); the guard itself is a pure function of static configuration, so a
counter would restate the config rather than observe anything. The non-persisted claim is logged at
DEBUG with the account's UUID and the two set sizes — never the username (REQ-OBS-004).

**Acceptance**

- [x] A partial-scope client's token leaves the stored role set untouched, `Admin` included
  (`UserReconciliationServiceTest.PartialRoleScopeTests`).
- [x] The same request is authorised with the token's roles, not the row's — a row holding `Admin`
  plus a token carrying only `KRT Member` yields no `ROLE_ADMIN`
  (`CustomJwtGrantedAuthoritiesConverterTest`).
- [x] An ordinary client still **replaces** the set, shrinking included: a guard that blocked every
  role removal would mean a Keycloak demotion never reached the database, which is its own privilege
  defect.
- [x] A first login through a partial-scope client persists its roles rather than creating a
  role-less row.
- [x] A token with no `azp` is treated as an ordinary client — an absent claim fails towards the
  established behaviour, not towards the exception.
- [x] The database-only `assembleFor(User)` keeps its contract for the ingest gateway's
  acting-member path (ADR-0129), which has no token to read.
- [x] Each half was verified by removing it: the persistence guard and the authorisation source each
  have a test that fails without them.

**Enforced by:** `UserReconciliationServiceTest.PartialRoleScopeTests`,
`CustomJwtGrantedAuthoritiesConverterTest` · **Code:** `PartialRoleScopeProperties`,
`UserReconciliationService#syncUser(Jwt)` (`ReconciledUser`),
`CustomJwtGrantedAuthoritiesConverter#assembleFor(User, Collection)` · **Configuration:**
`app.security.partial-role-scope.client-ids`

### REQ-SEC-038 — The Android App Link is verifiable, or the login is broken

The Android app's production redirect URI is an **App Link** —
`https://profit-base.online/app/callback` — rather than a custom scheme, so that no other installed
app can claim the end of a login. Android honours that claim only after fetching
`https://profit-base.online/.well-known/assetlinks.json` and finding the app's package name and
signing-certificate digest in it.

**The frontend MUST serve that file** at exactly that path, and the response MUST satisfy all three
of Android's conditions:

|              |                                                                  |
|--------------|------------------------------------------------------------------|
| status       | `200` — **no redirect**, not even one that ends at `200`         |
| content type | `application/json`                                               |
| access       | anonymous; it is fetched by the platform, which holds no session |

`AssetLinksController` serves it and `SecurityConfig` lists the path in the anonymous matcher set.
A static file under `static/` would not do: behind this application's chain the path fell through
to `anyRequest().authenticated()` and answered `302` into the OAuth entry point — the same trap the
`/sm/**` and `/**/*.map` entries beside it were added for.

**Failure is silent and looks like a server fault.** Verification fails, Android declines to open
the link in the app, the browser follows it instead, the frontend has no such route, and the member
lands on the 404 page in the middle of signing in. Nothing in either build can see it: the app is
correct, the server is correct, and only their agreement is missing. This shipped in the app's
v0.1.0 and was found by a member on a phone.

**The digest list is a list, and that is load-bearing.** A signing-key rotation must publish the
new digest **before** the rotated APK ships, while the old key is still installed on every device;
both must therefore be servable at once. Configuration:
`app.android-app-link.sha256-cert-fingerprints`, overridable per environment. A rotation that
replaces rather than appends breaks every installed copy for the length of the rollout.

**Code:** `frontend/…/controller/AssetLinksController.java`,
`frontend/…/config/AndroidAppLinkProperties.java`, `SecurityConfig` (anonymous matchers).
**Test:** `AssetLinksControllerTest` — asserts the three response conditions through the real
security chain, and that the digests are an array.

---

### REQ-SEC-044 — Cookie CSRF MUST NOT gate the bearer-only API

The backend's filter chain is `SessionCreationPolicy.STATELESS` and authenticates with exactly one
mechanism: a bearer JWT. No form login, no HTTP basic, no session cookie — **no ambient credential
of any kind**. CSRF exists to stop a cross-site request riding a credential the browser attaches by
itself, so on this surface the check cannot prevent an attack. It can only refuse a legitimate
client, and it did.

**The exemption MUST name the surface, not individual endpoints.** `SecurityConfig.CSRF_EXEMPT_PATHS`
is `/api/v1/**` plus `/internal/**` (machine-to-machine, its own shared-secret header, REQ-SEC-022).
It used to name five paths, and every write outside them answered `403 MissingCsrfToken` to any
caller without a CSRF cookie — which is every bearer client, i.e. the whole native app. Booking stock
out of the Lager, taking an Auftrag and moving its status, and a bank account's balance target were
refused in production, while `/api/v1/missions/**` and `/api/v1/operations/**` worked because they
happened to be on the list. A per-endpoint list fails this way once per endpoint nobody remembers to
add, in production, with a status that names CSRF and misdirects the reader (ADR-0144).

**The exemption MUST stay scoped.** Anything outside those two patterns keeps CSRF. Nothing
browser-facing lives on this backend today — it serves no HTML, ArchUnit-enforced — and the point of
scoping is that adding something browser-facing later does not arrive pre-exempted. **The precondition
travels with the rule:** if this chain ever gains a session cookie, a form login or a browser-facing
endpoint, the exemption has to be revisited rather than inherited.

**Nothing in the test suite observes the production branch.** The `test` profile disables CSRF
outright so MockMvc can post without first fetching a token, so every `@SpringBootTest` here runs the
branch that has no CSRF. That blind spot is why the gap shipped and it is **not** closed:
`SecurityConfigCsrfExemptionTest` pins the pattern list without a Spring context, and the nightly
`edge-deny-probe` remains the only end-to-end check — which is how this was found, by asserting `401`
for an anonymous write and getting `403`, because the CSRF filter runs ahead of authorization.

**Acceptance**

- [x] Every write path the native app uses is CSRF-exempt (`SecurityConfigCsrfExemptionTest`).
- [x] The exemption is expressed as `/api/v1/**`, not as a list of endpoints (same test).
- [x] A path outside the bearer API is not exempt (same test).
- [ ] `edge-deny-probe` answers `401` for the four anonymous writes. **Open** — verified only by the
  nightly run after the next deploy.

---

### REQ-SEC-037 — The public API vhost's anonymous surface is enumerated, not incidental

> [!note] Amended 2026-09-06 (ADR-0159) — the enumeration stands, the statuses changed
> The allow-list is unchanged: no rule was added, removed or reordered, and every path the app sends
> is still admitted. What changed is the **backend**, which now refuses the caller behind them. The
> expected-status table in `API_VHOST_ROLLOUT_RUNBOOK.md` § D.3a therefore reads `401` almost
> throughout, with two `200` rows — `/api/v1/terms/document` and `/api/v1/app/version-policy` — and
> the `404`/`405` rows untouched.
>
> The `403` rows are worth a second look rather than a search-and-replace. They said `403` because
> their path sat under a `permitAll` stem: the request was dispatched and refused at the method
> seam, and the MVC advice rendered that. With the stem gone they are turned away at the entry point,
> which writes `401`. Same closure, different number — and the number is what the rollout check
> reads, which is why this requirement is about numbers at all.
>
> The requirement's discipline is unchanged and was followed here: **no pin, no stated status.**
> Every row is pinned in `ApiVhostAnonymousSurfaceTest` before the runbook says it.

The vhost is a default-deny allow-list (ADR-0135), and every path on it **inherits whatever
authentication the backend requires of that path**. That is deliberately not uniform: most of the
API is `authenticated()`, and a few operations are `permitAll` because something already public
depends on them. Allow-listing therefore decides *reachability*, never *authorisation* — and the two
are easy to conflate, because the list looks like a security control and is only half of one.

**Each path admitted to the allow-list MUST have its anonymous status stated when it is added.** A
path that turns out anonymous when nobody intended it is the failure this exists to prevent, and it
is not catchable afterwards: nothing in CI can see what the vhost serves, and an unauthenticated
endpoint answers exactly as cheerfully as an authenticated one.

The anonymous surface, complete:

|                 Operation                  |                                                                                                                     Why it is anonymous                                                                                                                      |                                                                             What an anonymous caller gets                                                                              |
|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/terms/document`               | ADR-0138 — wording everyone must read *before* agreeing cannot require having agreed                                                                                                                                                                         | the same text already world-readable at `/terms`                                                                                                                                       |
| `GET /api/v1/missions/search`              | the public home page (`/`, `permitAll`) renders its upcoming-Einsatz tiles from this very endpoint                                                                                                                                                           | `PLANNED` + `ACTIVE`, **non-internal** rows only, through the outsider redaction in `MissionController#searchMissions`                                                                 |
| `GET /api/v1/missions/{id}`                | the same public surface, one Einsatz deep                                                                                                                                                                                                                    | the redacted DTO of ADR-0034 — no description, no owner, no managers, participants without payout preference or comment; an **internal** or **terminal** Einsatz is refused with `403` |
| `GET /api/v1/ship-types`                   | phase 3's Hangar editor needs the hull catalogue before a member has picked anything, and `/api/v1/ship-types/**` is `permitAll` in the chain                                                                                                                | game data — hull names, manufacturers, SCU — already rendered without a session by the public web frontend; no member, org unit or ship of anyone's is reachable through it            |
| `GET /api/v1/materials/search`             | phase 3's Lager form needs the material catalogue, and `/api/v1/materials/**` is `permitAll` in the chain                                                                                                                                                    | material names, their unit and their category — the same catalogue the public web frontend renders; no stock figure and no member is reachable through it                              |
| `GET /api/v1/locations/search`             | the same form needs the place catalogue, under the same `permitAll` prefix                                                                                                                                                                                   | place names and ids; what is *stored* at a place needs a token                                                                                                                         |
| `GET /api/v1/refining-methods`             | phase M's Methoden-Picker needs the refining catalogue before a member has picked anything, and `/api/v1/refining-methods/**` is `permitAll` in the chain with no method gate beneath it                                                                     | the refining methods by name with their UEX yield/cost ratings — game data with no member, org unit or order in it; the admin CRUD on the same stem stays `hasRole(ADMIN)`             |
| ~~`GET /api/v1/materials/{id}/terminals`~~ | **No longer anonymous.** Carved out with `/api/v1/materials/matrix` under REQ-SEC-032 (verb-agnostic): its only consumer is the authenticated inventory page, and leaving it open published UEX trade prices per material to the internet from the API vhost | n/a — the path answers `401` without a token, which the nightly edge-deny probe had (correctly) been asserting all along                                                               |
| `GET /api/v1/app/version-policy`           | REQ-API-010 — an app too old to authenticate must still be able to learn that it is too old; a token-gated gate is silent in the one case it exists for                                                                                                      | three integers and the public GitHub release URL. No caller identity goes in and none comes out — the rare `/api` path with nothing to redact                                          |

**This one was decided, not inherited.** Every other row above is anonymous because something
already public depends on it; `version-policy` is anonymous because the owner chose it on
2026-08-24 against the standing stance that the vhost opens no anonymous paths (plan Q8). The
alternative was considered and rejected on the merits: a token-gated policy endpoint cannot answer
an app whose *login* is what the new contract broke, and that app would then show an authentication
error — telling a member their credentials are wrong when they are not. The exception is one path,
one verb, and a body with nothing in it worth protecting.

**Phase 4's two feature slices add nine paths and not one anonymous one.** The Raffinerie's three
(`my-orders`, one order, its booking) and the Materialbörse's six all sit behind
`hasRole(KRT_MEMBER)` and answer `401` without a token; their statuses are pinned in
`ApiVhostAnonymousSurfaceTest` like every other allow-listed path. Two families are admitted **by
name rather than by stem**, which is why the wider surfaces behind them stay unreachable: the app
touches three of the refinery controller's eleven paths, and neither item-create of the board.

Everything else on the list is refused without a token — the Finanzen endpoints among them
(`isAuthenticated() and isMemberOrAbove() and canSeeMission`), which is why a mission's money is
reachable from the app and not from the internet even though the mission itself is.

**Refused is not one status.** `PUT /api/v1/orders/{id}/status`, added in phase 3, is the first
allow-listed path whose chain rule is a *role* rather than a session: anonymous gets `401`, and an
authenticated member without `LOGISTICIAN` gets `403`. `GET /api/v1/locations/home-locations`
behaves the same way for the same reason. Both are pinned in `ApiVhostAnonymousSurfaceTest` with
the status they actually answer, because both were first written down with the wrong one.

So was `GET /api/v1/locations/refineries`, phase M's Raffinerie-Picker — the same `permitAll`
chain, the same method-level `isAuthenticated()`, the same `403`, recorded as `401` when it was
admitted and corrected on 2026-08-31 after the nightly probe had reported the difference for three
nights. Phase M's other picker, `GET /api/v1/refining-methods`, was recorded as `401` in the same
stroke and is anonymous (the row above). Neither had been pinned in
`ApiVhostAnonymousSurfaceTest`, which is the step that would have caught both at review time and
is why the requirement names it: **a path admitted without its pin is admitted without its status
stated**, whatever the runbook says next to it. The rule the two misses share is that a status is
read off the layer that refuses the caller, never off the form the field belongs to.

**One family on the list used to be reachable anonymously by design, without being *anonymous*.**
The four participant writes — `…/participants/{id}/slim` and its `check-in`, `check-out` and
`payout-preference` siblings — are guarded by `canAccessParticipant`, which **resolved the row
before it judged the caller**: a guest sign-up was editable by its anonymous creator presenting the
per-row capability token minted at sign-up (REQ-SEC-018, header `X-Guest-Edit-Token`). An anonymous
caller was therefore a legitimate one on these paths, and an unknown row answered `404` to
everybody.

Both halves are gone. `V239` dropped the column that stored the token's hash and REQ-SEC-052 the
anonymous caller, so the request no longer reaches the lookup: it is turned away at the entry point
with `401`, and the row's existence is not part of the answer. This was the one entry on the list
that was not authenticated-only; there is no longer one. Pinned in
`ApiVhostAnonymousSurfaceTest.shouldRefuseAnonymousParticipationWritesOnAnAbsentRow`.

**The refusal is not one status, and the split follows the layer that produces it.** The me-scoped
paths are `authenticated()` in the filter chain, so they never reach a controller and the entry
point answers `401`. The Finanzen paths sit under `GET /api/v1/missions/**`, which is `permitAll`
in that chain — the request is dispatched, `@PreAuthorize` refuses it at the method seam, and
`GlobalExceptionHandler` renders that refusal as `403`. Nothing upgrades it to `401`:
`ExceptionTranslationFilter`, the component that would substitute the entry point for an anonymous
caller, never sees an exception the MVC advice has already handled. Both are closed to the
internet; only the number differs, and it differs *because* authorization lives at the method seam
in this project rather than in the matcher list. `ApiVhostAnonymousSurfaceTest` pins both, since
the statuses are what an operator verifies the vhost against
([`API_VHOST_ROLLOUT_RUNBOOK.md`](../API_VHOST_ROLLOUT_RUNBOOK.md) § D.3a).

**The missions and operations families are additionally read-only on this vhost.**
`/api/v1/missions/<uuid>` and `/api/v1/operations/<uuid>` answer `PUT` and `DELETE` as well as
`GET`, and an allow-list that matches on the path cannot tell them apart, so the vhost refuses
every non-`GET` under either prefix with `405` before the request reaches the backend.
`@PreAuthorize` would refuse them too; the point is that it does not have to be the only thing
that does. Under operations the write that matters is `PUT
/api/v1/operations/<uuid>/payouts/paid-out`, which marks a member as paid — phase 3 opens it, and
opening it means naming that one path rather than widening the family, because the guard is
verb-blind by design.

**A refusal by this vhost is `404` or `405`, and which one is decided by order, not by family.** The
allow-list's default deny runs first; the read-only guard runs after it. A path that is on no
allow-list line is therefore `404` for **every** verb — its family membership in the read-only guard
is never consulted, because the request is already refused. Only an *admitted* path can answer
`405`, and that is what `POST /api/v1/orders` does: the queue is on the list as a phase-2 read, so
the verb is the only thing left to refuse. `POST /api/v1/hangar/import/fleetview` used to read the
other way round: `404`, not `405`, because it was on no allow-list line and the `/hangar` prefix
sitting in the read-only family only decided what would happen on the day it was admitted. **Phase X
was that day** — it added both the allow-list line and the `$krt_readonly_family` clearing, so the
path answers `401` now, and the sentence that once ended „phase 4 has not admitted it" is corrected
here rather than deleted, because the reasoning it carried is still the reasoning that decides every
other row.

Stating this is worth the paragraph because the two are trivially confused when reading the block
top-down, and the confusion is silent in the direction that matters least and loud in a nightly
probe. It has now cost two episodes. REQ-OBS-012's run asserted `405` on that path for three nights
against a vhost that had always answered `404`; then phase X admitted the path, added its `401` row
to the probe and left the old `404` row standing, so the probe demanded both answers of one path and
went red on the first scheduled run after the merge (2026-09-06). **An admission has to delete the
refusal row, not only add an admitted one.**

**Phase 4's Beförderung reads are the least remarkable entries on the list, and that is the point
of naming them.** `GET /api/v1/promotion/evaluations/my` and `…/eligibility/my` are
`isAuthenticated()` and me-scoped by construction: both end in `/my`, the member is resolved from
the token, and there is no id an anonymous caller could substitute. They answer `401`. The rule
here is that every admitted path has its status recorded when it is added — not that the obvious
ones may be assumed, because "obvious" is exactly what the one wrong entry always looked like
beforehand. The officers' matrix (`/promotion/manage`, `/evaluations/all`, `/evaluations/members`)
is **not** on the list at all: the admin area is web-only permanently.

**The live-sync bridge adds a second stream and the vhost's first client-driven publish**
(ADR-0143, REQ-FE-019). Both are `isAuthenticated()` on the controller, so both answer `401`, and
neither is anonymous. They are worth stating rather than filing under "everything else" because
each is unusual for a different reason.

`GET /api/v1/live-sync/stream` is the second long-lived SSE endpoint on this vhost, and it carries
the notification stream's hazard in a wider shape: an untokened stream would not leak one response
but hold a connection open and feed it *other members' rooms* for as long as it lived. What crosses
it is only a room name and opaque section keys — never data — so the leak would be "resource X
changed", which is exactly why each room is gated on the read it provokes rather than on membership
alone. A stream naming several topics opens with the ones the caller may join and **drops the
rest**, so a partially-authorized request is a partially-populated stream and never an accidentally
complete one. When nothing is accepted it is `403`, not `401`: the caller authenticated fine, they
simply may not enter any of the rooms they asked for.

`POST /api/v1/live-sync/changed` is the vhost's first path where an ordinary member makes *other*
members re-fetch. That reads worse than it is, and the reason is the same one ADR-0094 accepted for
browser tabs: the frame carries nothing, and every receiver re-fetches through its own authorized
read, so the whole reachable effect is making people reload things they are already allowed to
load. The bound is not authorization but rate — per-subject and per-room token buckets, both
answering `429`, both to be dropped rather than retried by a client. The path is on the allow-list
by name, and the `live-sync` family is **not** given a read-only exception wholesale: `/stream` is a
`GET` and `/changed` is the one `POST` admitted.

**The notification stream is on the allow-list, and it is the entry that would cost the most if it
were wrong.** An SSE endpoint reachable without a token would not leak one response but hold a
connection open and feed it another member's events for as long as it lived. It is me-scoped
(`@PreAuthorize("isAuthenticated()")` on the controller, recipient resolved from the JWT `sub`), so
it answers `401` — asserted, like every other row, by `ApiVhostAnonymousSurfaceTest`. The mutating
half of the family (`POST /read-all`, `DELETE /read`, `DELETE /{id}`, `POST /{id}/read`) is off the
list *and* refused by the read-only guard, which covers `notifications` as well.

**The four Operationen reads answer `401`, not the `403` their Einsatz neighbours give.** Nothing
in the chain's matcher list names `/api/v1/operations/**`, so they fall through to
`anyRequest().authenticated()` and are refused before the dispatch, where the entry point writes
`401`. Same family of screen, same phase, different number — which is why the rollout table is per
path and `ApiVhostAnonymousSurfaceTest` pins each one.

**The mission search publishes more than the home page does, and that is accepted rather than
overlooked.** Each row is redacted identically, but the page caps itself at seven days and fifty
rows while the API takes an arbitrary `start`/`end` and pages through the whole result — so the
reachable *window* is larger, and JSON is a friendlier scraping target than rendered HTML.
Requiring a token was considered and rejected: the home page consumes this endpoint
**anonymously** through the frontend's own client, so authenticating it would blank the tiles for
every visitor who is not logged in. The rows carry no member identity, and the edge per-IP limiter
(REQ-SEC-023, 20 r/s burst 80, keyed per IPv4 and per IPv6 `/64`) applies to this host like every
other, which bounds enumeration without adding a control.

**`permitAll` in the filter chain is not the whole rule.** `/api/v1/missions/**` is `permitAll`
there, and `POST /api/v1/missions` is nonetheless refused to an anonymous caller by
`@PreAuthorize("isAuthenticated()")` on the method — this project puts authorization at the method
seam by design. Auditing the filter chain alone therefore *over*-reports the anonymous surface;
both layers have to be read together, which is why the table above lists operations rather than
matchers.

**Acceptance**

- [x] Every allow-listed path has a recorded expected status without a token, and the rollout
  runbook's verification step reads from that table rather than assuming one answer for all of them
  (§ D.3a). The previous single-number check raised a false alarm the first time it was run against
  a `permitAll` path.
- [x] Both anonymous operations are named, each with the already-public surface it mirrors.
- [x] The two live-sync paths were checked specifically: `isAuthenticated()` at the controller, so
  neither is anonymous; the stream's partial-authorization answer (`403` only when *no* topic was
  accepted) and the publish path's `429` are pinned in `LiveSyncControllerTest`, and both paths get
  their row in `ApiVhostAnonymousSurfaceTest` and in the nightly probe.
- [x] `POST /api/v1/missions` was checked specifically: `permitAll` in the chain,
  `isAuthenticated()` at the method, so an anonymous create is refused — and it is not on the
  allow-list either.
- [x] The edge rate limiter covers this host without a per-host entry:
  `docker/maintenance/nginx/server_proxy.conf` is included into every proxy host's server block.
- [x] A check that fails when an allow-listed path becomes anonymous without the table moving.
  The nightly `edge-deny-probe` workflow asserts the whole table from outside — the only vantage
  point that can, since the allow-list lives in NPM's database where no PR and no in-repo test can
  read it. It catches both directions: a `2xx` where the table names a refusal is an
  unauthenticated read of member data, and a `404` where it names a status means the block was
  never pasted — the failure that otherwise has no signal at all, and that shipped a blank
  mission-detail screen once already. The id-dependent rows take their id from the anonymous search
  rather than a hardcoded UUID, and say so loudly when there is no row to take.
- [x] The backend half is pinned in CI too: `ApiVhostAnonymousSurfaceTest` asserts the status each
  allow-listed path gives an anonymous caller, so the table cannot drift from the code even between
  nightly runs.

#### What the probe leaves in the backend log

The probe's own refusals surface to an operator as backend WARN lines, and they are the *expected
output of a passing assertion*. Recognising them matters: on 2026-09-02 they cost a log triage
twenty minutes, because four anonymous `403`s against real endpoints read exactly like a broken
relay.

The signature is **the path set, not the clock**. A run leaves two to four lines of the shape
`Access denied for GET … [status=403, code=ACCESS_DENIED, …]` with `userId=anonymous` and
`orgUnitId=anonymous` (`CorrelationIdFilter`), on exactly these paths and in this order:

1. `/api/v1/locations/refineries`
2. `/api/v1/locations/home-locations`
3. `/api/v1/missions/{id}/finance-entries`
4. `/api/v1/missions/{id}/finance-entries/summary`

— the only four `403` assertions in the workflow. The two Finanzen rows carry a **live** mission
UUID, because the probe discovers the id from the anonymous search rather than hardcoding one, and
they are skipped altogether when that search returns no row (hence "two to four").

The negatives are what close the triage:

- The run's ~100 `401` assertions log at **DEBUG** and its one app-level `404` likewise, so
  "four WARN lines and nothing else" *is* the signature. A burst of anonymous `403`s on *other*
  paths is not this probe.
- The schedule is `17 5 * * *` UTC, but a burst at another hour proves nothing — it is equally a
  `workflow_dispatch`, a delayed schedule, the manual runbook check, or an unrelated anonymous
  caller. Match on the paths.
- These count toward `basetool_http_error_total{code="ACCESS_DENIED"}` but sit roughly two orders
  below `AccessDeniedSpike`'s `0.2/s for 10m`, so a firing `AccessDeniedSpike` is never this.

The WARN level is deliberate and must not be lowered for these, nor folded into the REQ-OBS-001
`401`→DEBUG carve-out. That line is the only human-readable signal that a deliberately-anonymous
catalogue (`/ship-types`, `/materials/search`, `/locations/search`, `/refining-methods`) has
accidentally grown a method gate and is now `403`ing the public pickers — drift this probe has
already caught once. `AccessDeniedSpike` is a spike detector, not a drift detector.

**Enforced by:** review at the moment a path is added — deliberately a documented obligation rather
than a test, for the reason in the open item above · **Code:** `SecurityConfig` (filter chain),
`MissionController#searchMissions` (outsider redaction), `HomeController#home` (the anonymous
consumer) · **Tests:** `ApiVhostAnonymousSurfaceTest` (the four statuses above) ·
**Decision:** [ADR-0135](../adr/0135-public-api-vhost-not-a-gateway.md),
[ADR-0138](../adr/0138-terms-wording-is-a-backend-resource.md)

### REQ-SEC-039 — A per-item receiver id is an authorization input, not a routing hint

`POST /api/v1/refinery-orders/{id}/store` takes a `userId` per stored item that names the
**receiving stock owner**. Because it decides whose ledger the output lands in, it MUST be
authorized against **the caller and that target together**: naming somebody else requires
`@ownerScopeService.canManageUserInventory(<receiver>)` — admin, self, or at least one shared
**editable** org unit with the receiver — and any other value is refused with `403`. The check runs
on the **requested** id and **before** the user is loaded, so an unauthorised caller cannot
distinguish an existing member id from an unknown one.

> [!warning] Amended 2026-08-30 — the role was never an answer about the target
> This requirement originally read "a caller who is not a `LOGISTICIAN` may only name themselves",
> and that closed only the **caller-vs-owner** axis. `ROLE_LOGISTICIAN` is the OR-union over *all*
> of a caller's memberships and carries no org-unit context whatsoever, so the org-unit axis stayed
> wide open: a logistician of any Staffel could fabricate stock — shared, or with `personal`
> private — in the ledger of a member of any *other* Staffel, which REQ-SEC-005 forbids. The same
> hole existed in all three on-behalf entry points (`POST /api/v1/inventory`, `POST
> /api/v1/refinery-orders`, and this one) while the fourth, `POST
> /api/v1/refinery-orders/users/{userId}`, had already been closed with `canManageUserRefineryOrders`
> in PR #808. **A role that says "may act on behalf of somebody" is not an answer to "may act on
>
>> behalf of *this* somebody."**

The order-ownership check that already guarded this endpoint does **not** cover it: it constrains
*which order* may be stored, not *who the stock is booked for*. Until this requirement, a member
storing their own refinery order could name any other member and fabricate arbitrary inventory rows
(any material, quality up to 1000, unbounded amount) as that member's shared squadron stock — or,
with `personal`, their private stock — leaving `INVENTORY_RECEIVED_FROM_REFINERY` audit rows
attributed to the victim. The identical operation on the Einbuchen path
(`InventoryItemService#createInventoryItem`) has always required this privilege; the two entry
points into "create an `InventoryItem` for another user" MUST NOT diverge.

The frontend follows the gate rather than relying on it: the store dialog's receiver picker is
rendered only for a logistician, and a plain member sees their own name in a disabled field
(REQ-FE — a control whose every foreign choice answers `403` must not be offered). The disabled
field submits nothing, so the server falls back to the order's owner, which for a non-logistician is
the caller.

**Acceptance**

- [x] A non-logistician storing their own order with a foreign `items[].userId` → `403`, and no
  inventory row and no order completion are written.
- [x] The refusal happens before the user lookup (no user-existence oracle).
- [x] A non-logistician naming their own id explicitly is accepted, exactly like omitting it.
- [x] A logistician may still book onto another member.
- [x] The receiver picker is absent for a non-logistician, including on a split row and on the
  flash-attribute re-render after a validation error.

**Enforced by:** `RefineryOrderServiceTest` · **Code:** `RefineryOrderService#storeRefineryOrder`,
`RefineryOrderPageController#viewOrderDetail`, `refinery-orders-details.html` · **Mirrors:**
`InventoryItemService#createInventoryItem`

### REQ-SEC-040 — Guest redaction MUST reach every nested user, not only the participants

The mission guest redactor's compiler-enforced exhaustiveness (the explicit full-field record
reconstruction) only holds for records it actually **descends into**. A nested collection forwarded
by reference is a hole in it, and MUST NOT contain a record that reaches a user, a squadron or free
text without its own `cleanup…ForGuest` pass.

Concretely: `assignedUnits` is forwarded to outsiders as mission planning data, and each unit's
`ship` carries a full `UserDto` owner. `UserMapper` nulls only `email`, so an un-redacted
pass-through handed an **unauthenticated** caller of the public mission detail the ship owner's
`roles` and `permissions` — i.e. who holds `ADMIN`/`OFFICER` — plus their free-text `description`,
org-unit memberships, `joinDate` and `discordLinked`. `Ship.owner` is `nullable = false`, so any
unit with an assigned ship always carried one. The owner is now reduced to the public callsign
tuple by the same `cleanupUserForGuest` pass every other nested user goes through.

Note what did **not** catch this, because the same blind spots apply to the next nested record:
`anonymousReadableMissionEndpointsMustRedactGuestPii` asserts that a `cleanup…ForGuest` method is
*called*, never that the redaction is *complete*; and `ExternalContractTest` freezes
`assignedUnits` only as a top-level field name and never inspects the nested shape.

**Acceptance**

- [x] An outsider's `GET /api/v1/missions/{id}` returns `assignedUnits[].ship.owner` with `roles`,
  `permissions`, `description`, `email`, `squadron`, `squadrons`, `joinDate` and `discordLinked`
  all null, and the callsign tuple (`username`, `displayName`, `effectiveName`, `rank`) intact.
- [x] The strict outsider level inherits the pass from the member-peer level.
- [x] A unit with no assigned ship redacts without error.

**Enforced by:** `MissionGuestRedactorTest` · **Code:** `MissionGuestRedactor#cleanupUnitForGuest`,
`#cleanupShipForGuest` · **Related:** REQ-SEC-007, REQ-SEC-009, ADR-0034

### REQ-SEC-041 — The mission description is gated on membership, not on authentication

`MissionMapper#resolveDescription` MUST return the free-text mission description only to a caller
who is a member or above (`AuthHelperService#isMemberOrAbove`), never merely to an authenticated
one. A role-less `GUEST` is authenticated yet is a **mission outsider** by REQ-SEC-009, and ADR-0034
withholds the description from that tier.

The gate had been bare `isAuthenticated()`, which the *detail* endpoint compensated for by nulling
the description in `cleanupOutsiderMissionForGuest`. The list and search projections run through no
redactor, so a `GUEST` token read on `GET /api/v1/missions/search` — an operation in the frozen
external contract, reachable over the public API vhost — returned the planning notes the detail
endpoint deliberately withheld from the very same caller. Gating at the single source fixes both
projections instead of bolting a second redactor onto the list path. Anonymous callers were
protected only incidentally (`isAuthenticated()` was false for them), which is why the gap was
invisible from the anonymous surface.

**Acceptance**

- [x] A role-less `GUEST` token gets `description == null` from `/api/v1/missions/search` and from
  the mission detail alike.
- [x] A member still receives the description on both.

**Enforced by:** `MissionViewerAccessServiceTest` · **Code:** `MissionMapper#resolveDescription`,
`MissionViewerAccess#isMemberOrAbove` · **Related:** REQ-SEC-009, ADR-0034

### REQ-SEC-042 — Booking into a mission ledger is a write, and is gated like one

`POST /api/v1/finance-entries` MUST be authorized by `MissionSecurityService#canCreateFinanceEntry`:
a caller who may manage the mission (ADMIN; an OFFICER / MISSION_MANAGER whose owning-OrgUnit scope
covers it; the owner or a co-manager) may book for any of its participants, and every other member
may book **only against their own participant row** on that mission.

It MUST NOT be gated on `OwnerScopeService#canSeeMission`, which deliberately grants the
cross-squadron **public escape** on a non-internal mission. That is the correct rule for a read and
the wrong one for a write: combined with a service that checked only that the participant belonged
to the mission, any member could post income/expense rows into another squadron's payout ledger and
attribute them to a member of that squadron — while editing or deleting that same row required being
its owner or an officer in scope (`canEditFinanceEntry`). A create strictly weaker than the edit of
what it creates is a broken-object-level-authorization asymmetry, and booking money is a management
act on the mission (MULTI_SQUADRON_PLAN.md § 1: editing is the owning OrgUnit's prerogative).

The self-booking branch resolves the caller's participant row by `(missionId, userId)` and compares
it to the requested id, so it enforces three conditions at once — the row exists, it belongs to this
mission, and it is the caller's — mirroring `canEditFinanceEntry`'s "owner **and** still a
participant" rule.

**Acceptance**

- [x] A member who may only *see* the mission gets `403`, and the service is never invoked.
- [x] A member naming another participant's row gets `403`.
- [x] A member booking against their own row succeeds.
- [x] A mission manager in scope may book for any participant.
- [x] An anonymous caller still gets `401` and a role-less `GUEST` still `403`, unchanged.

**Enforced by:** `MissionSecurityServiceTest`, `MissionFinanceEntryControllerSecurityTest` ·
**Code:** `MissionSecurityService#canCreateFinanceEntry`,
`MissionFinanceEntryController#createFinanceEntry` · **Related:** REQ-SEC-006, REQ-SEC-009

### REQ-SEC-045 — A login binds a session to the token's own subject, never to a callsign

`UserReconciliationService#syncUser(Jwt)` looks the caller up by `app_user.id` = the token's `sub`
and by **nothing else**. A subject that matches no row is a new registration — never an account
matched on `preferred_username`.

Until this rule was written the lookup fell back to `findByUsername` and associated the session with
whatever row that returned. Both consequences were silent:

1. **The invariant ended for that member.** Everything else in the system — 39 foreign keys, the
   frontend's own `userId` comparisons, `/users/me`, the audit trail — rests on `app_user.id` being
   the caller's subject. After a name-matched login it was not, for one row, with no way to notice
   until something compared the two.
2. **A callsign decided which account a token acted as.** Keycloak usernames are neither immutable
   nor unique, and are reusable after a deletion, so a recreated account with a previous member's
   callsign inherited their inventory, their bank grants and their notifications.

**The rule.** No implicit inheritance, ever. The caller is provisioned through the ordinary
first-login path, which stamps `PENDING` and notifies every admin (REQ-SEC-017, REQ-NOTIF-012), so a
collision surfaces as a **decision** rather than as an adoption. `app_user.username` carries no
unique constraint, so two accounts may legitimately hold one callsign until an admin resolves it.

**What a person actually sees.** The registration queue marks such a row (`callsignCollision` on
`PendingRegistrationDto`, resolved for the whole page in one query) so the admin knows that approving
it creates a *second* account for a callsign rather than admitting a new member. The log line names
both account ids and **never the callsign** (REQ-OBS-004), and
`basetool_user_callsign_collisions_total` counts the event — untagged, because a username is both
unbounded and PII (REQ-OBS-011). The `UserCallsignCollision` alert is the second pair of eyes.

**Acceptance**

- [x] A token whose `sub` matches no row never returns a row found by username, whatever that row
  contains.
- [x] The entity-loading by-name lookup is not consulted at all on the login path, so it cannot come
  back as an optimisation that restores the adoption.
- [x] The collision is counted; an ordinary first login counts nothing.
- [x] Exactly the colliding row carries the queue marker.
- [x] An admin can merge the two accounts explicitly, moving the member's data — REQ-SEC-046.

**Enforced by:** `UserReconciliationServiceTest`, `AdminDiscordRegistrationsNicknameRenderTest` ·
**Code:** `UserReconciliationService#syncUser`, `UserRegistrationService#findCollidingCallsigns` ·
**Related:** ADR-0142 point 5, REQ-SEC-017, REQ-DATA-006

### REQ-SEC-046 — Merging two accounts of one member is an admin decision, and it moves belongings only

A login whose subject matches no row must never adopt an account found by callsign (ADR-0142
point 5, #1639). Removing that silent inheritance is right — but the outcome it produced was not
always *wrong*, only unsafe because nobody chose it. Left without a remedy, a member who ends up
with two accounts has their data stranded on the one they can no longer reach, and the admin has
nothing to do about it.

`UserAccountMergeService`, reached at `POST /api/v1/admin/registrations/{id}/merge` (ADMIN only),
is that remedy. The registration in the path is the account that **survives**; the body names the
older account to empty.

**One rule decides every table: ownership follows the member, attribution stays with the act.** A
row saying "this belongs to X" moves. A row saying "X did this, then" does not — re-pointing it
would not repair an identity, it would falsify history.

|                                               Follows                                               |                                                         Stays                                                         |
|-----------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| Stock, hangar, refinery orders, personal inventory and blueprints                                   | The audit trail, in both its forms                                                                                    |
| Org-unit memberships, org-chart positions, the Grand-Admiral office                                 | Who granted, requested, decided, initiated, executed, paid out                                                        |
| Missions owned, party-lead and unit responsibility, manager and participant rows, order assignments | The account's own approval history, and who decided it                                                                |
| Exchange offers, requests and interest                                                              | `user_roles` — re-derived from the token and the roster sync, not owned (REQ-SEC-013, REQ-SEC-036)                    |
| Bank grants, view grants, approval limits, the holder row                                           | `terms_acceptance` — consent is recorded per account; the member is asked once more rather than having one back-dated |
| Notifications, rule selectors, promotion evaluations                                                |                                                                                                                       |

**The classification is exhaustive by construction.** `UserAccountMergeCoverageTest` reads every
foreign key into `app_user` out of the live schema, adds the two deliberately FK-less audit target
columns, and fails the build unless each appears in exactly one of the two lists — and unless every
listed column still exists. A new user-referencing column cannot be forgotten here; it can only be
classified, by someone who had to decide which side it belongs on.

**Conflicts are deduplicated where they are duplicates and refused where they are not.** Thirteen
moved tables carry a unique constraint over the user column, so one member may legitimately hold a
row on both accounts (two sign-ups for one Einsatz, the same blueprint owned twice). The source's
row is dropped where the target already has an equivalent — safe precisely because the two accounts
are one person, so the duplicate carries nothing the survivor lacks. `bank_holder` is unique on the
user **alone**, so a holder on both means two ledgers; that is an accounting decision with money in
it, and the merge aborts with `409` rather than guessing which postings belong to whom.

**What it deliberately does not do.** It does not approve the registration — repairing the data must
not imply admitting the member — and it does not delete the emptied source row, which is the
user-deletion flow's job and carries its own fail-closed Keycloak probe (REQ-DATA-008).

**Acceptance**

- [x] What the source owns lands on the target; a row only the source has moves rather than being
  dropped.
- [x] A row the target already has is dropped instead of violating the unique constraint.
- [x] A row recording an act stays on the source.
- [x] Two bank ledgers refuse the merge; an account cannot be merged into itself.
- [x] Every column referencing a member is classified, and no classification names a column that no
  longer exists.
- [x] One `USER_MERGED` audit event names both ids and the per-table counts, never the callsign.

**Enforced by:** `UserAccountMergeServiceTest`, `UserAccountMergeCoverageTest` ·
**Code:** `UserAccountMergeService`, `DiscordRegistrationAdminController#merge` ·
**Related:** REQ-SEC-045, ADR-0142 point 5, REQ-DATA-008, REQ-AUDIT-001

### REQ-SEC-047 — A client is told its authorisation, never left to derive it

The API answers "may this caller do this" itself. Two shapes carry it:

- **`GET /api/v1/me/capabilities`** reports the caller's standing resolved **through the role
  hierarchy** — `isLogisticianOrAbove`, `isMissionManagerOrAbove`, `isAdmin` alongside the existing
  bank and blueprint flags.
- **`canEdit` on the row** — `MissionDto`, `InventoryItemDto` and `JobOrderDto` each carry the
  server's own answer for that row, computed by the same `AccessGateService` the endpoint's
  `@PreAuthorize` reaches.

**Because `UserDto.isLogistician` answers a different question and reads like this one.** It is a
Staffel-membership projection — `UserMapper.resolveLogistician` reads the membership rows and
nothing else — and an **admin holds no Staffel membership by design**. The same is true of
`isMissionManager`. A client gating on them is not approximating the rule; it is applying a
different rule that happens to agree for one role and disagree for the two above it. The Android
client did exactly that and hid the Lager write actions, the Auftrag write actions and the
Operation's payout confirmation from admins and officers the server would have permitted — a
defect no server test could see, because the server was answering correctly the whole time.

**A row flag carries the endpoint's whole rule, not half of it.** `JobOrderController`'s writes gate
on `hasRole('LOGISTICIAN') and canEditJobOrder(#id)`; a flag built on the scope half alone would
offer editing to a plain member whose own Staffel owns the order, which the endpoint refuses.
`StockViewerAccess#mayEditJobOrder` therefore answers with both halves.

**The mapper reaches the gate through a leaf interface** (`StockViewerAccess` in `support`,
implemented in `service`), for the ADR-0047 reason `MissionViewerAccess` already exists: a
`mapper → service` edge would close a package cycle, and mappers may touch neither
`SecurityContextHolder` (ArchUnit `mapperLayerShouldNotReachIntoSecurityContext`) nor the service
layer directly.

**Acceptance**

- [x] The three capability flags resolve through the hierarchy, so an admin reads `true` for all
  three and an officer for the first two (`MeControllerTest`).
- [x] The job-order row flag is false for a caller who passes the scope check but holds no
  Logistician-or-above role.
- [ ] Walked on a device with an admin account: outstanding.

**Enforced by:** `MeControllerTest` · **Code:** `MeController`, `StockViewerAccess`,
`StockViewerAccessService`, `AccessGateService#mayEditJobOrder`, `InventoryItemMapper`,
`JobOrderMapper` · **Related:** REQ-SEC-046, ADR-0047, REQ-APP-SEC-001

### REQ-SEC-048 — One endpoint answers which org units a caller may pin

`GET /api/v1/me/org-units` returns every active org unit an **admin** may pin, and for **everyone
else** the units they belong to plus the ones a Bereich or OL seat reaches (`OrgUnitCascadeService`,
REQ-ORG-015 — a materialised id set, never an admin-all marker). All four kinds in both branches,
ordered top-down: OL → Bereich → Staffel → SK, then by name.

**Because the branch was duplicated.** The web frontend's `OrgUnitContextAdvice` had it
(`isAdmin()` → the catalogue, else the memberships); the Android client read
`/users/me/memberships` for everybody. A rule that two clients must each know is a rule one of them
will get wrong, and the endpoint removes the duplication — for the web it also collapses up to four
round-trips into one.

> **Correction, 2026-09-01, itself superseded 2026-09-02.** An earlier revision claimed the missing
> branch was *why* an admin using the Android app saw nothing but „Alle Org-Einheiten". That was
> wrong, and the 2026-09-01 correction said so: REQ-SEC-035 withheld `Admin` from the mobile
> client's scope on purpose, so an app caller was never an admin to this endpoint and the admin
> branch could not fire there. Measured on a device: an account holding `Admin` reached the backend
> as `KRT Member` alone, exactly as REQ-SEC-036 prescribes for a partial-scope client.
>
> **That reading was accurate and the design behind it did not survive.** The owner reversed
> REQ-SEC-035 on 2026-09-02 and the mobile scope now carries `Admin`, so the admin branch fires for
> an app caller too and this endpoint's two branches finally mean the same thing on both clients —
> which is what it was written for. The 2026-09-01 text is kept because it is the reason the
> reversal was needed: the symptom was never this endpoint's defect, and fixing it here would have
> been fixing the wrong thing.
>
> **Correction, 2026-09-01.** This requirement first said "Staffel and SK only, matching what the
>
>> switcher offers". **That was wrong**, and it hid a second empty switcher behind the first. A
>> member may hold a seat on a Bereich or on the Organisationsleitung *and on nothing else*
>> (`V165` forbids an OL member a Staffel row at all), and those units own aggregates in their own
>> right — `OrgUnitStampingService` applies no kind filter, REQ-ORG-016. Listing only Staffeln and
>> SKs therefore left exactly those members with a switcher that had nothing in it but „Alle
>> Org-Einheiten", while the scope predicate would have honoured a pin to any of them
>> (`RequestScopeResolver#currentScopePredicate` accepts a pin inside the caller's expanded reach).
>> The web's own `orgunit-select.html` had grouped all four kinds since epic #692 Phase 5; the
>> restriction lived only in the backend.

The membership branch is the epic #692 Phase 5 drill-down reach
(`OrgUnitMembershipQueryService#listPickerOptionsWithDescendants`) rather than the direct-membership
list (`#listOptionsForUser`), which is what makes a leadership seat resolve to the units below it.
The admin branch is `#listAllPinnableOptions`, kept separate from `#listAllActiveOptions` because
that one is also the public Job-Order form's requesting/responsible-unit picker and must stay
Staffel/SK-only — only those process orders.

**An admin must reach at least as far as an OL member.** The OL cascade names every org unit, so an
admin branch restricted to two kinds would have been the narrower of the two — an inversion of the
hierarchy that no gate would have caught, because both answers are individually plausible.

**Acceptance**

- [x] An admin gets every active org unit of all four kinds and never the membership list
  (`MeControllerTest`, `OrgUnitMembershipQueryServiceTest#listAllPinnableOptions_…`).
- [x] A non-admin gets their reach and never the catalogue — and specifically the drill-down reach,
  not the direct-membership list (`MeControllerTest`).
- [x] A member whose only seat is a plain `MEMBER` row on a Bereich is offered that Bereich
  (`OrgUnitMembershipQueryServiceTest#listPickerOptionsWithDescendants_plainBereichMember_…`);
  verified against the test stack 2026-09-01.
- [x] An OL seat is offered every unit the cascade names, top-down
  (`…_olSeat_reachesEveryUnitTheCascadeNames`).
- [ ] Both clients read this endpoint rather than branching themselves: outstanding.

**Enforced by:** `MeControllerTest`, `OrgUnitMembershipQueryServiceTest` · **Code:**
`MeController#getPinnableOrgUnits`, `OrgUnitMembershipQueryService#listAllPinnableOptions` /
`#listPickerOptionsWithDescendants` · **Related:** REQ-SEC-047, REQ-ORG-015, REQ-ORG-016,
REQ-ORG-017

### REQ-SEC-049 — Every session value must survive a round trip through the session serializer

A value stored in the HTTP session must be readable again on the next request. The frontend's
session store is Redis + Jackson, and the default typing `SecurityJacksonModules` activates is
`NON_FINAL`: a **non-final** runtime type is written with an `@class` type id and reads back, a
**final** one is written without and the reader then demands the id it was never given. A `record`,
a `List.of(...)`, a `Map.of(...)` or any other final class therefore writes without complaint and is
unreadable on the very next request.

`FaultTolerantSessionSerializer` makes that survivable — the value reads as absent rather than
throwing, which is what stops it being the total outage of 2026-09-02 — but survivable is not
correct. A dropped value is never written back, so a poisoned session re-drops on **every** request
for up to its 720-hour window (REQ-SEC-025), and every drop bumps
`basetool_session_value_dropped_total`. The meter's whole purpose is to detect a genuine poisoning,
so a permanently non-zero rate does not merely annoy: it hides the next one.

Two rules, and which applies depends on who writes the value:

- **Code in this repository never stores a final type in a session.** Wrap it in a non-final
  container instead — `BackendRoleSyncFilter`'s `new ArrayList<>(backend.asserted())` is not a
  stylistic flourish, it is what makes that attribute readable. Two silent variants belong to the
  same rule: a `UUID` comes back a `String` and an `Instant` a `Double`, with no exception and no
  log line.
- **A final type written by a dependency is named in `RedisSessionConfig`'s
  `CONTAINER_WRITTEN_FINAL_SESSION_TYPES` allow-list**, which gives it a forced `@class` through
  `ForcedTypeIdMixin`. There is no seam to wrap a value the servlet container writes itself. The
  list is resolved by name and skipped when absent, so it cannot break the build on a container that
  does not carry the class. It must stay short, and it must never carry one of our own classes —
  putting one there converts a two-character fix into a permanent exception.

Tomcat 11.0.25 added `org.apache.tomcat.websocket.server.WsHttpSessionBindingListener`, a `record`
that `WsServerContainer#registerAuthenticatedSession` writes whenever an authenticated WebSocket
handshake finds the attribute unset. It fired `SessionValueDropsSustained` at ~70 drops per 15 min on
2026-09-03 against an otherwise healthy application, and the rate could not decay: a dropped value
leaves the attribute unset, which is exactly the condition under which Tomcat writes it again. It is
the first and so far only entry on the allow-list.

> [!warning] Corrected 2026-09-03 — the handshake is **not** on every logged-in page
> This paragraph previously read "for this application, every logged-in page, because live sync opens
>
>> `/ws/sync`", and ADR-0154 built its self-healing argument on that. It is false:
>> `krt-live-sync.js` connects **lazily** — `ensureSocket()` is reached only from `subscribe()`,
>> `sendChanged()` and `sendPresence()` — so a page that subscribes to no live-sync room never
>> handshakes. Getting the type id right therefore stops new poisoning but repairs nothing that is
>> already stored, which is what REQ-SEC-050 exists for
>> ([ADR-0157](../adr/0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md)).

Widening the default typing to cover final types is **not** the fix and must not be proposed as a
simplification: `creationTime`, `lastAccessedTime` and `maxInactiveInterval` are `Long`/`Integer`
read as bare scalars, and changing their wire format breaks every live session at once with
`IllegalStateException: creationTime key must not be null` (ADR-0154).

**Acceptance**

- [ ] Every value this repository's code puts in a session round-trips through the configured
  session serializer.
- [ ] A final type written by a dependency either round-trips or is on the allow-list; nothing else
  is on the allow-list.
- [ ] An allow-list entry that is not on the classpath is skipped rather than failing startup or the
  build.
- [ ] A bare `record` / `List.of(...)` / `Map.of(...)` still does **not** round-trip — the allow-list
  is an exception per named class, never a policy change.
- [ ] `basetool_session_value_dropped_total` is zero in steady state, so a non-zero rate is a real
  poisoning.

**Enforced by:** `SessionSerializerRoundTripTest` (the required keys and scalars still read back;
the Tomcat listener round-trips and carries `@class`; a plain record and `List.of`/`Map.of` still do
not) · `FaultTolerantSessionSerializerTest`, `SessionAttributeDiagnosticMapperTest` (the survivable
path and the attribute-naming WARN) · **Code:** `RedisSessionConfig#buildSessionJsonMapper`,
`CONTAINER_WRITTEN_FINAL_SESSION_TYPES`, `ForcedTypeIdMixin`, `FaultTolerantSessionSerializer`,
`SessionAttributeDiagnosticMapper` · **Monitoring:** `SessionValueDropsSustained`,
`basetool_session_value_dropped_total` ([`observability.md`](observability.md)) · **ADR:**
[ADR-0154](../adr/0154-a-container-written-final-session-value-gets-a-forced-type-id.md)

### REQ-SEC-050 — A dropped session value must be repaired, not re-read

REQ-SEC-049 keeps unreadable values from being *written*. This requirement is about the ones already
in Redis, and it exists because getting the first half right did not clear the alert.

An unreadable value is dropped by `FaultTolerantSessionSerializer` and named by
`SessionAttributeDiagnosticMapper`, and **neither writes anything back**. The bytes therefore stay in
the session hash and are re-read, re-dropped and re-counted on *every* subsequent request that
session makes — for up to the 720-hour authenticated window (REQ-SEC-025). One poisoning is
permanent for the life of the session, and `basetool_session_value_dropped_total` cannot reach zero
while any poisoned session is still being used, so the meter cannot see the next real poisoning
(REQ-OBS-011).

**Nothing else clears it.** For the Tomcat listener of REQ-SEC-049 the only writer is
`WsServerContainer#registerAuthenticatedSession`, which runs on a WebSocket handshake — and
`krt-live-sync.js` opens `/ws/sync` **lazily**, only from `subscribe()` / `sendChanged()` /
`sendPresence()`, so a page that subscribes to no live-sync room never handshakes. Meanwhile
`notifications.js` polls from every page (60 s, or 300 s while SSE is healthy) and each poll reads
the session again. That combination held production at 2-6 drops per minute for hours after the
REQ-SEC-049 fix was live, and fired `SessionValueDropsSustained` a second time on 2026-09-03 at
13:33Z.

**The rule:** a session value that cannot be read is removed from the session before the request that
discovered it ends.

- The mapper hands the attribute name to `SessionAttributeRepairQueue`, a **bounded** thread-local,
  and still writes nothing itself.
- `SessionAttributeRepairFilter` — ordered `SessionRepositoryFilter.DEFAULT_ORDER + 10`, i.e.
  immediately inside Spring Session's own filter — drains the queue in a `finally` and calls
  `HttpSession#removeAttribute`. That is the same public API `BackendRoleSyncFilter` and
  `TermsAcceptanceGateFilter` already use, so **no Redis write is added to the session read path**;
  repairing from inside the serializer or the mapper is forbidden for that reason (ADR-0154,
  ADR-0157).
- The queue is cleared on the way **into** the chain as well as drained on the way out. Tomcat pools
  request threads, and a name left behind by an earlier request would remove an attribute from a
  different member's session.
- The filter runs on async dispatches too, so the notification SSE stream cannot strand a queued
  name on a pooled thread.

**Acceptance**

- [ ] A value that cannot be read is dropped **once** per session, not once per request.
- [ ] The repair uses `HttpSession#removeAttribute`; neither the serializer nor the session mapper
  writes to Redis.
- [ ] A repair name never crosses from one request to the next on the same thread.
- [ ] A session invalidated during the request (a logout) does not turn the repair into an error.
- [ ] The repaired hash field reads back as absent rather than becoming a second kind of unreadable
  value.
- [ ] `basetool_session_value_dropped_total` falls to zero after a poisoning stops, so a rate that
  stays up means a value is still being written unreadably.

**Enforced by:** `SessionAttributeRepairIntegrationTest` (against a real Redis and the real
`RedisIndexedSessionRepository`: the container record is written with its `@class`; a pre-fix value is
dropped once and queued; the repair ends the drop; the repaired field reads back absent) ·
`SessionAttributeRepairFilterTest` (drain, clear-on-entry, no session, invalidation race, repair on a
throwing chain, filter order) · `SessionAttributeDiagnosticMapperTest` · **Code:**
`SessionAttributeRepairFilter`, `SessionAttributeRepairQueue`, `SessionAttributeDiagnosticMapper`,
`FaultTolerantSessionSerializer` · **Monitoring:** `SessionValueDropsSustained`,
`basetool_session_value_dropped_total` ([`observability.md`](observability.md)) · **ADR:**
[ADR-0157](../adr/0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md),
[ADR-0154](../adr/0154-a-container-written-final-session-value-gets-a-forced-type-id.md)

### REQ-SEC-051 — A relayed request parameter is bound to the backend's own type

The frontend is a proxy: a page or proxy controller binds a request parameter, drops it into a
`UriComponentsBuilder` or a URI template, and hands the result to `WebClient`. A relayed value that
carries URI syntax — `&`, `=`, `#`, `?`, `/` — can reshape the backend request even where it cannot
redirect it to another host, because the path prefix is always a literal `/api/v1/…`.

**The rule:** a request parameter the frontend relays into a backend URI is bound to the type the
backend's own controller declares for it. Where no type expresses the constraint, the value is
narrowed against the allowlist the page itself renders, before it is relayed.

|                      Relayed value                      |                    Bound as                    |                                                        Mirrors                                                         |
|---------------------------------------------------------|------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `from`, `to`, `before`                                  | `Instant` + `@DateTimeFormat(iso = DATE_TIME)` | `AuditAdminController`, `BankAccountController`, `OrgUnitBankController`                                               |
| `userSub`, `actorUserId`, `userId`                      | `UUID`                                         | `AdminPersonalInventoryController`, `AdminPersonalBlueprintController`, `MemberEvaluationController`, `UserController` |
| `eventType`, `clientId`, `source`, the board `sort` key | narrowed to the rendered option list           | the page's own `<select>`                                                                                              |
| a Spring sort specification                             | `RelayParams.sortSpecOrNull`                   | REQ-API-005's backend field whitelist                                                                                  |
| free text (`q`)                                         | a `WebClient` URI-template variable            | REQ-FE-016                                                                                                             |
| a star-system name (`starSystemNames`)                  | a `WebClient` URI-template variable            | REQ-UI-014's materials-matrix relay                                                                                    |

Free text is the one relayed value that may legitimately contain arbitrary characters, so it is
escaped exactly once across the hop rather than narrowed. Everything else has a shape, and the
backend signature is where that shape is already written down.

A value drawn from an *external* catalogue is treated the same way: a star-system name comes from
UEX, not from a vocabulary either controller declares, and it may legitimately carry spaces. The
frontend relays it as a URI-template variable exactly as `MaterialsPageController` already does on
the materials matrix (REQ-UI-014) rather than narrowing it against a catalogue snapshot the page
would have to re-fetch to validate against.

Where the value selects something on a page, an unparseable one degrades — no member selected, no
filter applied — the way an unknown tab already falls back to the default tab. Where it addresses a
proxy seam, it is a `400` from Spring's type conversion, handled by `GlobalExceptionHandler`.

> [!warning] Only `UriComponentsBuilder#toUriString()` encodes — `build()` and
> `buildAndExpand(...)` do not
> `UriComponentsBuilder#toUriString()` is `build().encode().toUriString()` and **does** encode its
> query values. Every other route to a string goes through raw `UriComponents`:
> `UriComponentsBuilder#build()` and `#buildAndExpand(...)` both return `UriComponents` in the RAW
> encode state, and `UriComponents#toUriString()` emits raw components verbatim. All three
> spellings differ by one call and by whether the value is encoded at all.
>
> Both non-encoding spellings have shipped here under a comment asserting the encoding one:
> `PromotionProxyController` used `buildAndExpand(...).toUriString()` (fixed 2026-09-04, ADR-0158)
> and `MaterialProxyController#getProfitCalculation` used `build().toUriString()` (fixed
> 2026-09-04, CodeQL alert 877). Do not read an encoding claim in a comment as evidence that
> encoding happens — read the call.

**Acceptance**

- [ ] Every request parameter the frontend relays into a backend URI is bound to the backend's
  declared type, or narrowed against a rendered allowlist, or is the free-text term.
- [ ] A period or member id carrying URI syntax is rejected at the frontend seam, with no backend
  call made.
- [ ] An unknown vocabulary filter is dropped rather than relayed, and the page reports it as no
  filter rather than echoing the crafted value.
- [ ] A source tab is canonicalized once, so the redirect and the relayed query cannot disagree
  about which catalogue was meant.
- [ ] No relayed identifier is `URLEncoder`-form-encoded into a path segment.
- [ ] No relayed value reaches `WebClient` through `UriComponents#toUriString()` in the raw encode
  state; a catalogue name carrying `&` or `=` opens no second query parameter on the backend call.
- [ ] The audit tab list has exactly one definition in the frontend, so the page and its
  export/purge proxy cannot disagree about which tabs exist.

**Enforced by:** `RelayParamsTest` (the checks, hostile inputs included) · `RelayParamBindingMvcTest`
(the four proxy seams answer 400 on a period carrying URI syntax; the admin page degrades without
calling the backend) · `AdminSyncReportsPageControllerTest` (canonicalization, and a crafted tab
appending no second query parameter) · `AdminAuditLogPageControllerTest` (per-tab event-type and
client-id narrowing; the canonical period/actor relay) · `AuditReportProxyControllerTest` (the tab
allowlist, `MARKET` included) · `MaterialProxyControllerTest` (a star-system name carrying
`&`/`=` leaves the controller as a URI variable, opening no second query parameter) ·
**Code:** `RelayParams`, `AuditDomains`, `AuditReportProxyController`,
`BankReportProxyController`, `OrgUnitBankProxyController`, `PromotionProxyController`,
`AdminAuditLogPageController`, `AdminPersonalInventoryPageController`,
`AdminPersonalBlueprintsPageController`, `AdminSyncReportsPageController`,
`MaterialboersePageController` · **ADR:**
[ADR-0158](../adr/0158-a-relayed-request-parameter-is-bound-to-the-backends-own-type.md)

### REQ-SEC-052 — No anonymous surface beyond the landing page and the enumerated infrastructure paths

The public surface is a **list**, not a policy. Everything on it is here because somebody argued for
it and the argument is written down; everything else requires authentication at the URL layer **and**
a method gate.

**Frontend** — the only `permitAll()` matchers:

|                                      Path                                       |                                                                Why it stays public                                                                 |
|---------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `/`                                                                             | The landing page. Product name, one paragraph, the two login entries, the legal links, the Fan Kit band. **No backend call, no data, no session.** |
| `/impressum`, `/privacy`, `/terms`                                              | Legal obligation: Impressumspflicht, DSGVO information duties, terms readable before agreeing.                                                     |
| `/error`, `/error/**`                                                           | Error pages carry no data, and an error view that needs a session cannot render the outage that broke it.                                          |
| the asset trees, `/favicon.ico`, `/robots.txt`, `/sm/**`, `/**/*.map`           | Assets. The three mechanical entries keep the OAuth2 saved-request replay off a 404 (REQ-SEC-025, ADR-0088).                                       |
| `/.well-known/assetlinks.json`                                                  | Android App Links verification is fetched by the platform with no session (REQ-SEC-038).                                                           |
| `/actuator/health`, `/actuator/health/**`                                       | Docker `HEALTHCHECK`; in prod Actuator lives on the internal management port (ADR-0134).                                                           |
| `/oauth2/authorization/keycloak`, `/login/oauth2/code/keycloak`, `POST /logout` | Spring Security's own login and logout endpoints — filters, not matrix entries.                                                                    |

**Backend** — the only `permitAll()` matchers on the main chain:

|                   Path                    |                                                                                                                             Why it stays                                                                                                                              |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/app/version-policy`          | The forced-update gate (REQ-API-010). A version gate that only answers after a login is silent in exactly the case it exists for: an app too old to log in must still learn that it is too old. Three integers and a public release URL.                              |
| `GET /api/v1/terms/document`              | The Terms-of-Use wording (ADR-0138 / REQ-SEC-028). A document everyone must read before agreeing to anything cannot require having agreed, and the same text is already on the public `/terms` page.                                                                  |
| `/internal/**`                            | The Keycloak SPI's account-existence precheck (REQ-SEC-022) — machine-to-machine behind a constant-time shared-secret header, `401` without it. Keycloak sits outside the resource server's trust boundary and carries no JWT to gate on. Not an anonymous data path. |
| `/actuator/health`, `/actuator/health/**` | Docker `HEALTHCHECK`.                                                                                                                                                                                                                                                 |
| `/error`                                  | Spring's error dispatch.                                                                                                                                                                                                                                              |

**Both anonymous reads are `GET`-scoped**, so a `HEAD` on either falls to the authenticated
catch-all and answers `401`. That is deliberate and it is the REQ-SEC-032 lesson: Spring Security
compares the verb with `String.equals`, so a method-scoped tightening placed above an all-verb
`permitAll` grants every verb it does not claim — which is how a `HEAD` once ran the material price
query anonymously and returned its `Content-Length`.

`/v3/api-docs/**` is **not** on either list. The document enumerates every path, parameter and DTO
field the API has — the most efficient description of the attack surface the project can produce —
and being a 404 in prod is a deployment property, not an access rule. It requires `ROLE_ADMIN`.

The prod-only **management-port chain** (`ManagementPortSecurityConfig`,
`@ConditionalOnProperty("management.server.port")`, port `11271`) is `permitAll` on
`/actuator/health(/**)`, `/actuator/prometheus` and `/actuator/info` **on the internal connector
only** (ADR-0134), with basic auth added on `/actuator/prometheus` by
`MonitoringScrapeSecurityConfig`. It is listed here so this requirement is exhaustive, not because
it is anonymous access in the brief's sense.

**Acceptance**

- [x] Every mapping the dispatcher knows refuses a caller with no token, except the four paths above
  — asserted by enumerating `RequestMappingHandlerMapping`, not by listing paths somebody thought of.
- [x] Every `GET` is additionally issued as `HEAD`, and the two `GET`-scoped reads answer `401` to it.
- [x] Exactly three methods declare `@PreAuthorize("permitAll()")`; a fourth fails the build.
- [x] Exactly two OpenAPI operations declare `security: []`, and no operation references a security
  scheme the document does not define.
- [x] The landing page mints no session and makes no backend call: `getSession(false) == null`, no
  `SESSION` cookie, `verifyNoInteractions(backendApiClient)`.
- [x] A read endpoint without a method- or class-level `@PreAuthorize` fails the build, like a write.

**Enforced by:** `AnonymousSurfaceSweepTest` (three passes over every mapping, plus `HEAD`) ·
`AnonymousSurfaceSweepMvcTest` (the frontend, navigation and background shapes) ·
`ArchitectureTest#permitAllIsDeclaredOnlyOnTheThreePublicEndpoints`,
`#readEndpointsMustDeclareAnAuthorisationAnnotation` · `OpenApiAnonymousOperationsTest` ·
`HomeControllerMvcTest#anonymousRootRendersTheLandingPageWithoutDataOrSession` ·
`SecurityConfigStaticAssetPermitAllTest` · `ManagementPortIsolationTest` ·
`ApiVhostAnonymousSurfaceTest` and the nightly `edge-deny-probe.yml` (the same statuses from outside
the host) · **Code:** `backend/…/config/SecurityConfig`, `frontend/…/config/SecurityConfig`,
`frontend/…/controller/HomeController`, `frontend/…/config/SafeCsrfAdvice`,
`templates/landing.html` · **ADR:**
[ADR-0159](../adr/0159-the-basetool-has-no-anonymous-or-guest-surface.md)

---

### REQ-SEC-053 — Every account is at least a member; a role-less token is refused

There is no role below member. The seeded `GUEST` role is gone (`V239`), and with it the state it
represented — an authenticated caller with an **empty authority set**.

**The rule:** an authenticated token whose realm roles map to no application role is refused with
`403 NO_ROLE` on every `/api/**` path, before a handler runs. Three paths are exempt, for the same
reasons they are exempt from the pending gate: `/api/v1/users/me/registration-status` (or the
refusal has no way to explain itself), and the two REQ-SEC-052 reads (the Android app attaches its
bearer to every call once a session exists, so gating them would refuse the version policy and the
terms text to exactly the callers most likely to need both).

**Why a refusal and not an empty set.** An empty authority set passes every `isAuthenticated()`
gate and fails only the ones that name a role, so whether such a caller is admitted depends on which
endpoint they happen to hit — a per-endpoint accident rather than a decision. Until ADR-0159 the
empty set was worse than that: it was mapped onto `GUEST`, which the URL matrix's anonymous families
let through, so "we could not resolve this account's roles" silently meant "give them the guest
surface".

**The refusal lives in `assembleFor(User, Collection<Role>)`**, not on the JWT path, so both callers
inherit it: the resource-server conversion and `DatabaseActingMemberAuthorities` on the ingest
gateway's acting-member path (ADR-0129), which installs an authentication without inspecting it.
`Roles.NO_ROLE_MARKER` is a marker, never a permission — nothing grants on it, and
`PendingApprovalAccessFilter` is the only reader.

**`default-roles-iri` carries `KRT Member`**, so every account Keycloak creates is a member at the
IdP. That is the structural half of this requirement, and it is why the roster sync had to be fixed
in the same change:

> [!warning] The composite-blind sync was the precondition, not a detail
> `KeycloakService` indexes **directly-assigned** realm roles (`GET /roles/{name}/users`). A member
> holding `KRT Member` only through the composite came back with an empty set on every nightly run.
> While that mapped to the authority-less `GUEST` it was invisible and healed at the next web login;
> with this requirement live it would be an overnight lockout of everyone who never had the role
> assigned directly. The sync now folds in what the realm's default-role composite grants.
>
> A run in which the realm matches **none** of the app's roles aborts rather than writing, because
> that is a rename or a broken query and never a legitimate state. A **single** account resolving to
> no role is still written through — removing someone's roles in Keycloak still removes their access.

**Acceptance**

- [x] A token carrying no known realm role is refused `403 NO_ROLE` on `/api/v1/users/me`, on the
  JWT path and through `ActingMemberFilter`.
- [x] The three exempt paths still answer such a token.
- [x] A member holding `KRT Member` only through `default-roles-iri` keeps the role after a sync run.
- [x] A sync run that resolves no app role at all writes nothing and logs it.
- [x] `V239` leaves no `GUEST` role, no `user_roles` row for it, and logs the affected user ids
  before deleting — identifiers, not identities.
- [x] A `ROLE` notification selector naming a code the catalogue does not know is rejected, so a
  rule cannot address a role that no longer exists.
- [x] The frontend routes the state to the account-status page with its own words, not the waiting
  copy: a role-less member has already been approved, so "wait for an administrator to approve you"
  describes a wait with no end.

**Enforced by:** `AnonymousSurfaceSweepTest` (the role-less pass over every mapping) ·
`CustomJwtGrantedAuthoritiesConverterTest` · `PendingApprovalAccessFilterTest` ·
`UserReconciliationServiceTest` · `KeycloakServiceTest` (the composite fold-in and the aborted run) ·
`V239MigrationTest` · `BackendRoleSyncFilterTest` · `NotificationRuleServiceTest` ·
**Code:** `CustomJwtGrantedAuthoritiesConverter#assembleFor`, `Roles.NO_ROLE_MARKER`,
`PendingApprovalAccessFilter`, `UserReconciliationService`, `KeycloakService#fetchDefaultRoleGrants`,
`NotificationRuleService#validateSelector`, `frontend/…/config/BackendRoleSyncFilter`,
`V239__drop_guest_role_and_guest_edit_token.sql` · **ADR:**
[ADR-0159](../adr/0159-the-basetool-has-no-anonymous-or-guest-surface.md)

## Out of scope

OrgUnit scoping/visibility rules (see [`org-unit-tenancy.md`](org-unit-tenancy.md)); the
confidential-client migration decision (see ADR-0001).
