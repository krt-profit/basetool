> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-25.
> **Owner area:** AUTH/SEC · **Related ADRs:** [ADR-0001](../adr/0001-frontend-confidential-oauth2-client.md) · **Role matrix:** [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)

# Security & access control

## Context & goal

Both modules run Spring Security on Keycloak OIDC. Authorization is centralised and
enforced architecturally so business logic never carries ad-hoc checks, and every
read/write is isolated to the calling user unless the caller is privileged.

## Requirements

> [!note] Where the rest of the `REQ-SEC` namespace lives — checked 2026-09-25
> Six `REQ-SEC` ids are specified in [`discord-integration.md`](discord-integration.md), not here:
> **REQ-SEC-016** (fail-closed guild + membership gate), **REQ-SEC-017** (a `PENDING` registration
> holds no authority), **REQ-SEC-019** (Discord-link indicator in member management),
> **REQ-SEC-022** (colliding Discord first-login precheck), **REQ-SEC-026** (admin-mediated
> linking of a registration) and **REQ-SEC-071** (a Discord login that returns to the wrong browser
> ends on a page with a way back). The mission finance-entry scope below shared `REQ-SEC-019` with the
> Discord-link indicator until 2026-09-22, when it was renumbered to **REQ-SEC-065** on the owner's
> decision (see the renumbering table in [`INDEX.md`](INDEX.md)). **REQ-SEC-054** was never
> allocated. The next free id is **REQ-SEC-072** (corrected 2026-09-25: this note still said
> REQ-SEC-070 after REQ-SEC-070 had been allocated below) — re-check `origin/main` and open PRs before
> claiming it. Requirements are grouped by subject, not strictly by number.

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
(backend) and its [frontend](../../frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/ArchitectureTest.java)
and [ingest](../../ingest/src/test/java/de/greluc/krt/profit/basetool/ingest/ArchitectureTest.java)
equivalents — a new violation fails `./gradlew test`:

- No `SecurityContextHolder` use outside `AuthHelperService` — in the service, controller and mapper
  layers alike (`serviceLayerShouldNotReachIntoSecurityContext`,
  `controllerLayerShouldNotReachIntoSecurityContext`, `mapperLayerShouldNotReachIntoSecurityContext`),
  and the caller's identity is read through that seam rather than by testing for a
  `JwtAuthenticationToken` (`identityMustBeReadThroughTheSeamNotTheAuthenticationType`, ADR-0129).
- Every `@RestController` carries at least one `@PreAuthorize`
  (`everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation`), and every read **and** every
  write endpoint carries a gate of its own, class- or method-level
  (`readEndpointsMustDeclareAnAuthorisationAnnotation`,
  `writeEndpointsMustDeclareAnAuthorisationAnnotation`). The frontend holds every controller but its
  public-by-design pages to the same floor (`everyControllerCarriesAGateOfItsOwn`, allow-list pinned
  by `thePublicByDesignAllowListNamesOnlyControllersThatExist`); the ingest module holds every
  controller and every `@PostMapping` to it (`everyRestControllerShouldBeAuthorisationAnnotated`,
  `everyPostMappingShouldBeAuthorisationAnnotated`).
- `permitAll()` appears on exactly four backend handlers — the two anonymous reads, the Keycloak SPI
  precheck and `/error` (`permitAllIsDeclaredOnlyOnTheFourPublicEndpoints`, REQ-SEC-052).
- Staffel-scoped write endpoints gate on `OwnerScopeService`
  (`staffelScopedWriteEndpointsMustGateOnOwnerScopeService`,
  `staffelScopedServicesMustWireOwnerScopeOrAuthHelper`), and peer-readable mission endpoints run
  the peer redaction (`peerReadableMissionEndpointsMustRedactPii`, REQ-SEC-007).
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
- The frontend does not depend on Spring Data JPA or JDBC (`frontendShouldNotDependOnSpringDataJpa`,
  `frontendShouldNotUseJdbcDirectly`); ingest depends on no persistence at all.

### REQ-SEC-004 — Roles & hierarchy

Roles: `ADMIN`, `OFFICER`, `LOGISTICIAN`, `MISSION_MANAGER`, `KRT_MEMBER` — plus the bank roles,
which the matrix carries. **`GUEST` is not one of them since `V239`** (ADR-0159): there is no role
below member, and a token that maps to none of these is refused with `403 NO_ROLE` (REQ-SEC-053).
Hierarchy (`SecurityConfig#roleHierarchy`): `ADMIN > LOGISTICIAN`, `ADMIN > MISSION_MANAGER`,
`OFFICER > LOGISTICIAN`, `OFFICER > MISSION_MANAGER`, and on the bank side
`ADMIN > BANK_MANAGEMENT > BANK_EMPLOYEE` — and deliberately **never** `MISSION_MANAGER > LOGISTICIAN`, which is
why a mission manager is inside the peer-redaction tier (REQ-SEC-007). The full matrix is
authoritative in [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md).

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

> [!important] The mission surface asks for membership, not merely for a login — 2026-09-07
> `getAllMissions` / `searchMissions` gained `isMemberOrAbove()` with this change, and
> `LiveSyncSubscriptionAuthorizer` has always asked for it, but the **detail** read and the
> participant paths were left on bare `isAuthenticated()` — so they were the outlier, not the rule.
> A caller who is authenticated without being a member (the bank roles reach no `KRT_MEMBER` edge in
> the hierarchy) would have read the full peer view of any non-internal mission: the roster, the
> assigned units, the organisation and every participant's payout preference and free-text comment.
> Before ADR-0159 that caller fell into the stricter *outsider* tier, which withheld all of them;
> removing the tier widened what was left.
>
> All four `canSeeMission` gates and all ten `canAccessParticipant` gates now carry
> `@authHelperService.isMemberOrAbove()`. It locks nobody out — `default-roles-iri` grants
> `KRT Member` to every account Keycloak creates — and it means the mission surface asks one
> question consistently instead of two different ones on adjacent endpoints.
>
> **`createMission` joined them on 2026-09-07**, which makes fifteen. It was the one mission
> endpoint with no scope predicate to carry, so it asked only for a login and was the single
> exception to the invariant this callout states — reachable by `ROLE_INGEST_GATEWAY`, the
> authenticated non-member ADR-0129 introduced. That gateway calls only the two import endpoints, so
> the gate closes a hole rather than a path.

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

**The mission's owner and managers are withheld from a peer who is only reading, and kept for one
who may manage the mission** (`canEdit` or `canManageManagers`). The exemption exists because the
pass now runs for callers who hold those rights: a bare `MISSION_MANAGER` is below Logistician — the
hierarchy puts ADMIN/OFFICER above both roles but never MISSION_MANAGER above LOGISTICIAN — so the
response asserted `canManageManagers: true` and handed over an empty manager list in the same
breath, and the Verwaltung panel offered add/remove controls over a list nobody could see. It
discloses nothing: `UserReferenceDto` is id, username, display name, effective name and rank, the
same public callsign tuple every participant on that mission already carries, and it travels only to
a caller who may rewrite the list it names.

> The rule that selects the endpoints was rewritten rather than renamed, and that is the part worth
> reading. It used to select gates carrying **no** `isAuthenticated()` clause — the shape that made
> an endpoint anonymously reachable. Every such gate now has one, so the old predicate would select
> *nothing* and the rule would pass by checking an empty set. It now keys on *which* gate. The
> rewrite immediately found a real leak the old rule could not see —
> `MissionController.joinMission` returned the whole Einsatz, roster included, to the member who had
> just joined.
>
> [!bug] Corrected 2026-09-06 — `canManageMission` **does** admit an ordinary member
> The rewrite above first exempted `canManageMission`, `canManageManagers` and `canChangeOwner`,
> on the premise that a gate requiring leadership returns the unredacted aggregate on purpose. That
> premise is false. All three fall through to `isOwnerOrManager`, which grants on
> `mission.getOwner()` or membership of `getManagers()` **without consulting a role**, and the
> hierarchy declares no `MISSION_MANAGER > LOGISTICIAN` edge either — so creating a mission (which
> only needs `isAuthenticated()`, and makes you its owner) is enough to pass them. The exemption
> covered exactly the audience the peer tier exists for, which is why the guard stayed green over
> twenty-three write handlers that returned the unredacted aggregate to a member below Logistician
> and over the slim unit endpoints and ship picker, where `ShipDto.owner` re-opened REQ-SEC-040 one
> tier up. Only a gate naming a **role or authority** is genuinely out of a peer's reach; those are
> the exemptions the rule keeps.

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
> surface" shared by two cohorts — anonymous callers and the `GUEST` role — under the name *mission
> outsider*, and enumerated what they could do: create a job order, browse non-internal missions,
> sign up as a named guest, check in and out, set a payout preference. None of it is true any more.
> The term *outsider* is retired; what replaced it is REQ-SEC-052 (the public surface as a list) and
> REQ-SEC-053 (nothing below member).

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
trust boundary and is not a change to the CSRF repository/handler (ADR-0012). Since 2026-09-06 it
also carries a class-level `@PreAuthorize("isAuthenticated()")`: the catch-all is still what
refuses, but REQ-SEC-052's rule is that no controller's only protection is a matcher two folders
away, and `ArchitectureTest.everyControllerCarriesAGateOfItsOwn` now holds every controller to it.

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
frontend relay and false of any appending proxy: the edge's shared `docker/edge/include/proxy.conf`
uses `$proxy_add_x_forwarded_for`, so the real peer lands on the **right** and everything left of it
is client-supplied. (The API vhost and the Keycloak upstream overwrite the header with
`$remote_addr` instead — `conf.d/50-api.conf.template`, `include/upstream-keycloak.conf`.) The resolver MUST therefore honour the header only when the immediate peer is a
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
`app.client-ip.trusted-proxies` (prod: `172.28.0.0/16`, the range every pinned container network is
carved from — the edge sits on `net-proxy-frontend`) and then walks the chain right-to-left, skipping
trusted hops and taking the first untrusted address — the RemoteIpValve algorithm. Because the edge
appends the true peer on the right, a client-supplied (leftmost) forged entry is never reached, so
rotating a forged `X-Forwarded-For` can no longer mint a fresh per-IP bucket per request; a direct
(untrusted-peer) connection never has its `X-Forwarded-For` honoured. The trusted range MUST match the
real network range (override via `APP_CLIENT_IP_TRUSTED_PROXIES`); a mismatch collapses every bucket
onto the edge's address — no leak, but the limiter is ineffective. The edge's own `$remote_addr` is
the real client only because the host-level haproxy hands it over by PROXY protocol (ADR-0187); the
edge rewrites it from that header alone, never from a client-supplied HTTP header.

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
so they hairpin **out through the public edge** (then NPM, now the native nginx edge; on the rootless
host ADR-0196 aliases the public name to the container gateway). Spring Security 7's default `RestClient`
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
  Authentication token and false for a missing context. The anonymous and role-less-`GUEST` cohorts
  it also excluded no longer reach it: neither can hold a session (REQ-SEC-052, REQ-SEC-053).
- [ ] A member's `GET /missions/{id}` triggers the member-only finance-entries fetch.
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
shared bind-mounted `keystore.p12` (its own leaf from the internal CA once REQ-SEC-070 is rolled
out) — so neither edge that reaches it is cleartext:

- **edge &rarr; Keycloak:** the native nginx edge (ADR-0162) terminates the public Let's Encrypt
  cert and re-encrypts to `https://keycloak:18443`, serving Keycloak under `/auth` on the app origin
  (ADR-0166). Since the edge moved into the repository it **verifies** the upstream certificate
  against the shared CA (`docker/edge/include/upstream-tls.conf`: `proxy_ssl_verify on`,
  `proxy_ssl_name keycloak`, included by `include/upstream-keycloak.conf`); NPM did not.
- **backend &rarr; Keycloak (admin/user-sync):** `KeycloakService` calls `https://keycloak:18443`
  directly over the isolated `net-backend-keycloak` network, pinning the self-signed cert via the
  `keycloak-trust` Spring SSL bundle (mirrors the frontend/ingest `backend-trust` approach, audit
  finding M-13). Unlike the relay clients that pin `backend-trust` without a hostname check, the
  admin client keeps **hostname verification ON**, so the cert's SAN MUST include `dns:keycloak`.
  *(Corrected 2026-09-22, ADR-0204: this used to say the JDK `HttpClient` cannot disable hostname
  verification per client. Its API cannot, but a trust manager can — JSSE does endpoint
  identification inside an `X509ExtendedTrustManager` — and that is how the ingest relay, now on the
  same JDK client, keeps its no-hostname-check `backend-trust` posture. The Keycloak admin client
  does not use it: verifying the name here is the intent, not a limitation.)*

The management/health interface (port 9000) is exempt: it stays HTTP via
`--http-management-scheme=http` because the Quarkus image ships no TLS-capable CLI client for the
container healthcheck. The port is never published on the host and never on an edge proxy network,
and the edge answers `/auth/health` and `/auth/metrics` with `404` (`conf.d/10-frontend.conf.template`);
since the monitoring rollout (epic #936, ADR-0072) the **prod** Keycloak additionally joins the
isolated `net-monitoring-scrape` network so **Prometheus scrapes `http://keycloak:9000/metrics` in
plain HTTP** there. Dev/test are exempt (Keycloak stays HTTP; the admin URL is plain HTTP and no
`keycloak-trust` bundle is defined, so `KeycloakService` falls back to the default client).

Since **ADR-0090** the two internet-facing Spring Boot modules (`frontend`, `ingest`) — and since
**ADR-0134** the backend too — adopt the same "management interface on an internal-only port, never
host-published nor edge-proxied" pattern in prod: `/actuator/**` moves to a dedicated
`management.server.port` (backend `11271`, frontend `18091`, ingest `11272`, HTTPS via the shared
keystore) reachable only from the internal networks that need it (`net-monitoring-scrape`, and
`net-backend-frontend` for the frontend's backend-health probe) and the container-local health
check (the Quadlet `HealthCmd`), so their public connectors expose no Actuator at all; the edge
additionally answers `/actuator` with `404`. See `REQ-OBS-005` (amended) for the authoritative rule.

**Monitoring-plane cleartext carve-out (owner-approved amendment, 2026-07-02).** The HTTPS-only edge
posture above still holds for every app/Keycloak edge. It is deliberately amended for traffic that
stays **inside the isolated monitoring networks** (`net-monitoring-scrape` /
`net-monitoring-core`; on the Compose-based local stacks also `net-docker-proxy`, which production
no longer has since the Podman cutover): Prometheus→exporters, Grafana→datasources,
Alloy→Loki/Tempo, the app/Keycloak OTLP span push to Alloy, and the `keycloak:9000` metrics scrape run
in plain HTTP. These networks carry no host ports, no public route and no user payload; Prometheus
still scrapes the three **apps** over HTTPS with the pinned public CA (no `insecure_skip_verify`), and
Grafana gets its own self-signed certificate so the shared `keystore.p12` private key never leaves the
four existing services. Approval of epic #936 by @greluc is the owner approval this amendment requires;
the rationale, residual risk and the binding rules live in `REQ-OBS-008` (`observability.md`) and
ADR-0072.

**Acceptance**

- [ ] In prod, Keycloak exposes no plain-HTTP listener; the public `/auth` path works through the
  edge over verified TLS.
- [ ] The backend user sync succeeds against `https://keycloak:18443` with hostname verification on
  (cert SAN carries `dns:keycloak`).
- [ ] Keycloak reports `healthy` (the HTTP management healthcheck still passes after the HTTPS flip).
- [ ] With no `keycloak-trust` bundle (dev/test), `KeycloakService` builds and talks plain HTTP.

**Enforced by:** `KeycloakServiceTest` · **Code:** `KeycloakService`, `application-prod.yml`
(`spring.ssl.bundle.jks.keycloak-trust`), `docker-compose.yml` (`keycloak` command, backend
`KEYCLOAK_ADMIN_URL` — rendered into `quadlet/systemd/keycloak.container` and
`quadlet/env.d/*.env.tmpl` by `scripts/generate-quadlet.py`), `docker/edge/include/upstream-keycloak.conf`
· **Runbook:** [`deployment.md` &rarr; Internal keystore and certificate rotation](../deployment.md#internal-keystore-and-certificate-rotation)

### REQ-SEC-043 — The Keycloak user sync MUST page the full user list before reconciling deletions

`UserSyncTask` reconciles local users against Keycloak through `UserSyncService.syncFromKeycloak`:
after syncing every fetched user it calls `UserReconciliationService.markMissingUsers(presentInKeycloak)`,
which flags every local user whose Keycloak id did **not**
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

**The realm's default-role composite is folded in (REQ-SEC-053).** `default-roles-<realm>` grants
`KRT Member` to every account Keycloak creates, and `GET /roles/{name}/users` does not list members
who hold a role only through that composite. `fetchDefaultRoleGrants` therefore resolves what the
composite grants and credits it to every member of the default role, or a default-only member would
come back with no role at all. And **a run in which none of the mappable app roles matches a realm
role is aborted**, not written: that is a realm-side rename or a broken query, and persisting it would
strip every account of every role and refuse the whole organisation with `NO_ROLE` at once. A single
account resolving to no role is legitimate and is written through.

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
role-stripped set would misclassify holders — creating a brand-new admin `PENDING` instead of
`ACTIVE` (and, before REQ-SEC-053, mapping it to the since-removed `Guest` fallback), or
mass-downgrading existing admins — a transient failure of
any role read (5xx, 401/403, timeout, malformed body) propagates to `fetchUsers`' top-level catch and
skips the whole run (empty roster → "skip", never a wipe, never a degraded persist), exactly like a
roster-page failure. Only a clean `404` on a single role's member read (a benign TOCTOU: the role
vanished after the `GET /roles` listing named it) is swallowed — that role contributes no members and
the run continues.

**The reconciled set is what the fetch reported, never what reconciled successfully.** The run
swallows a per-user `syncUser` failure so one bad row cannot abort the roster -- correct, and it must
not also cost that row its presence. `UserSyncService` therefore collects each fetched id **before**
attempting to reconcile it, and hands that set to `markMissingUsers`. Collecting the ids only on
success made a transient failure indistinguishable from an upstream deletion, and soft-deleted a
member who was present and enabled in the realm; because `in_keycloak` is read as presence by the
member administration, an admin was then offered the hard-delete action on an active member's row
(#1825, found in production 2026-09-09 -- the delete itself was still refused by REQ-DATA-008's
fail-closed probe, so the cost was misdirection rather than data loss). Every such failure is counted
(`basetool_user_sync_failures_total`, untagged per REQ-OBS-011) and alerted
(`UserSyncPerUserFailure`), because a row that throws once throws every run and never self-heals.

**Acceptance** (`KeycloakServiceTest`): with a page size of 2 and three users across two pages,
`fetchUsers` returns all three, the first request binds `first=0&max=2`, and the second advances to
`first=2`; with realm role `ADMIN` listing user A, A's DTO carries `ADMIN` resolved from
`/roles/ADMIN/users`; a realm role spelled `admin` still resolves the app's `ADMIN` (case-insensitive);
a 5xx on a role's member read yields an empty result (run skipped, no degraded roles); a clean 404
on a role's member read keeps the roster with that role simply absent; and a `403` on the realm-role
listing (a service account missing `view-realm`) yields an empty result and increments the
`basetool_keycloak_sync_fetch_failures_total` counter.

**Acceptance** (`UserSyncServiceTest`): a user whose `syncUser` throws is still carried in the set
handed to `markMissingUsers`; a user the fetch never reported is not; the returned count stays the
number of users that reconciled successfully; and each failure increments
`basetool_user_sync_failures_total`.

**Enforced by:** `KeycloakServiceTest` · `UserSyncServiceTest` · **Code:**
`KeycloakService.fetchAllUsers`, `KeycloakService.fetchRealmRoleNames`,
`KeycloakService.fetchRoleMemberships`, `KeycloakService.fetchDefaultRoleGrants`,
`KeycloakService.logFetchFailure`,
`KeycloakSyncProperties.pageSize`, `UserSyncService.syncFromKeycloak`,
`UserReconciliationService.markMissingUsers`, `UserSyncTask` · **Issues:** #1825

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

- On creation of a guest sign-up the backend **minted** an unguessable 256-bit **capability
  token**, persisted only its SHA-256 hash on `mission_participant.guest_edit_token_hash`, and
  returned the plaintext **once** in the create response (`MissionParticipantDto.guestEditToken`).
  `V239` dropped the column; the DTO field is gone from both modules.
- Every subsequent guest-row mutate/delete **was** authorised by
  `MissionSecurityService.canAccessParticipant` iff the caller (a) presented a token (header
  `X-Guest-Edit-Token`) that hashed to the stored hash, OR (b) held a mission-management role in
  scope (`canManageMission`). Only branch (b) survives, and it is now unconditional.
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
- The frontend **stored** the token client-side (localStorage, keyed by participant id) and
  **replayed** it via the `X-Guest-Edit-Token` header, relayed browser→frontend→backend by the
  `GuestEditTokenContext`/`GuestEditTokenContextFilter`/`GuestEditTokenRelayFilter` trio (Reactor
  context propagation, mirroring the client-IP relay). The token is intentionally lost when the user
  clears site data — an anonymous caller has no durable server-verifiable identity, so a cleared token
  degrades to "a mission manager edits it", never to "anyone can edit it".

**Acceptance**

**Retired with the mechanism (ADR-0159, 2026-09-06).** The four criteria below all describe the
capability token, and none of them can be evaluated any more: there is no anonymous creator to mint
one for, `V239` dropped the column that stored its hash, and `MissionParticipantDto.guestEditToken`
is gone from both modules. They are struck rather than deleted so the requirement still reads as a
record of what was once true and why.

- [x] ~~An anonymous caller without the token is denied (403) on a guest-row mutate/delete/payout.~~
- [x] ~~The anonymous creator presenting the minted token may edit/withdraw their own guest row.~~
- [x] ~~Only the create response ever carries the plaintext token; reads/edits return `null`.~~
- [x] A mission manager / officer / admin in scope manages participant rows — the branch that
  survived, and the only one there is now. It is what an external participant's row hangs off
  (REQ-SEC-052, D4).

**Enforced by:** `MissionSecurityServiceTest` (the surviving `canAccessParticipant` cases) and
`MissionAccessControlTest`. `GuestParticipantTokenServiceTest` and `MissionGuestAccessTest` went
with the service and the flow they tested; the token cases inside `MissionSecurityServiceTest` went
with the token. **Migration:** V177, undone by `V239`. **Security audit:** finding M1.

### REQ-SEC-065 — Mission finance-entry writes are owning-OrgUnit-scoped for officers

> **Renumbered 2026-09-22:** this requirement was `REQ-SEC-019` until 2026-09-22; that id also named the Discord-link indicator in member management in [`discord-integration.md`](discord-integration.md), which keeps it.

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

The anonymous / role-less-`GUEST` ("outsider") view of a public (non-internal) mission **was** an
**operational-coordination surface**: by deliberate product decision (ADR-0034) it exposed the
participant roster's public callsign tuple (`username`/`displayName`/`rank`), org-unit affiliation,
job type, assigned ship/unit, mission frequencies, owning organisation and schedule/status, so a
prospective sign-up could decide whether and how to join. It withheld PII (email / real name) and,
additionally, the two per-participant fields with low public-coordination value and higher
sensitivity:

- **`payoutPreference`** — a participant's financial intent.
- the free-text **`comment`** — uncontrolled text that may carry incidental PII.

Both fields stayed on the authenticated member-peer view; only the outsider paths stripped them, via
`MissionGuestRedactor.stripOutsiderParticipantFields`. **All of that is gone** — the tier, the
methods and the audience — and `MissionPeerRedactor` has one level left. The reasoning is kept
because it is the argument for what a peer *does* see: a member is part of the organisation, so the
two fields are not withheld from them.

**Acceptance** — retired with the tier (ADR-0159); listed so the record shows what was asserted.

- [x] ~~An anonymous / role-less-`GUEST` read of a public mission returns every participant with
  `payoutPreference` and `comment` `null`.~~ No such reader exists; both are visible to a peer.
- [x] An authenticated member (peer view and above) still sees both fields — the one that survives,
  pinned by `MissionPeerRedactorTest`.
- [x] PII (email / real name) remains redacted below Logistician (REQ-SEC-007).

**Enforced by (the surviving criteria):** `MissionControllerLifecycleTest`
(`getMissionById_peer_keepsRosterButStripsPii` — a peer below Logistician keeps the roster, payout
preference and comment while PII is stripped; `getMissionById_logisticianCaller_returnsFullDtoUnchanged`)
and `MissionControllerSlimEndpointsTest` for the `addParticipantSlim` roster returned to a peer. The
outsider-tier tests went with the tier.
**ADR:** [ADR-0034](../adr/0034-anonymous-outsider-mission-visibility.md). **Security audit:**
finding L3.

### REQ-SEC-023 — Edge per-IP rate limiting (version-controlled safety net)

(REQ-SEC-022 — the Discord account-existence precheck — lives in
[`discord-integration.md`](discord-integration.md); this requirement continues the series at the
next free number.)

> [!important] Since ADR-0162 the whole edge is version-controlled, not just this snippet
> The word "version-controlled" in this requirement was written when it was the exception: the
> limiter lived in `docker/maintenance/nginx/`, while Force SSL, HSTS, Block-Common-Exploits and the
> API allow-list lived in Nginx Proxy Manager's SQLite database and in a runbook block pasted into a
> web form. The edge is now native nginx configured entirely from `docker/edge/` in this repository,
> validated by `nginx -t` in CI (`scripts/check-edge-nginx.sh`) and applied by the deploy reconcile.
> The values below are unchanged and were carried across verbatim. `docker/maintenance/nginx/`, the
> two NPM snippets that held them before, was deleted on 2026-09-22; nothing had read it since the
> native edge replaced NPM.

Every public vhost at the edge carries a **version-controlled** per-IP safety
net: `limit_req` (20 r/s sustained, burst 80, `nodelay`) and `limit_conn` (500
concurrent connections) keyed on the real client IP (`$krt_limit_key`: the full IPv4 address, or an
IPv6 client's `/64` network prefix). `docker/edge/conf.d/00-maps.conf` defines the zones and
`docker/edge/include/limits.conf` applies them; the frontend, ingest, Grafana and API vhosts each
include it. The values are
flood/brute-force **ceilings**, not fairness limits — a legitimate worst-case page load fits
inside the burst. Two invariants:

- **Rejections answer 429, never 503.** The nginx defaults would route rejected requests into the
  maintenance-page `error_page 502 503 504` intercept, serving a flooding client the maintenance
  page with `Retry-After` semantics that are wrong for rate limiting.
- **Engagement is observable.** Rejected requests land in the per-host access logs (plus
  `limit_req_log_level warn` in the error log) and a sustained 429 rate raises the
  `EdgeRateLimitSpike` Loki alert.

**Real client IP — how it reaches the edge today (ADR-0187).** Production runs rootless Podman, whose
port forwarder would hand the edge its own address for every client. A host-level haproxy therefore
binds the public `:80`/`:443` (v4 and v6 separately) in pure TCP mode and passes the client address to
the edge by **PROXY protocol v2**; the edge publishes only on loopback (`127.0.0.1` / `[::1]`, ports
8080/8443), trusts only a list of **literal** addresses — its own pinned address on each of its
networks, because rootless Podman presents the forwarder's connection from the edge's own address on
one of them (`EDGE_TRUSTED_PROXY`; the renderer refuses any prefix or wildcard) — via
`set_real_ip_from`, and takes `$remote_addr` from `real_ip_header proxy_protocol`, never from a
client-supplied HTTP header (`docker/edge/render-and-run.sh`, `quadlet/systemd/edge.container`). TLS
still terminates at the edge, which selects the vhost by SNI, so every vhost keys on the real client
IP. The paragraph below is the history of the Docker-era fix; the `/64` keying it introduced is
unchanged.

**Real client IP restored on the Docker host (ADR-0112, historical).** The masking was IPv6-specific:
`:443` was published on
`[::]:443` while the container network was IPv4-only, so Docker installed no `ip6tables` DNAT and the
userland `docker-proxy` relayed every IPv6 client through the bridge gateway (`$binary_remote_addr` =
`172.28.3.1`); dual-stack browsers prefer IPv6, so almost all real traffic collapsed onto one bucket,
and a 60-connection cap on it caused the 2026-07-20 outage (long-lived `/notifications/stream` (SSE)
and `/ws/sync` (WebSocket) connections crossed 60, the edge 429'd legitimate users, the frontend
degraded into the maintenance page). ADR-0112 made `net-proxy-frontend` dual-stack (`fd00:28:3::/64`)
so the kernel DNAT preserved the client IPv6 on that host; real v4 and v6 client addresses reached
nginx, so the per-IP limit was meaningful again and `limit_conn` was tightened from the 10000 stopgap
to **500** concurrent connections per client. Two follow-ups landed on top and are still current.
**(1) IPv6 is keyed on its `/64`.** The limiter key is `$krt_limit_key` (now in `00-maps.conf`) — the
full IPv4 address, or an IPv6 client's `/64` network prefix — so a subscriber's rotating SLAAC privacy
addresses (which vary only in the low 64 bits) share one bucket instead of each minting a fresh one
and diluting the cap. **(2) No vhost needs its own ingress.** There is a single public ingress and the
vhost is selected by SNI only after the connection is accepted, so every vhost keys on the real
client IP. The bridge-gateway addresses that dominate the non-frontend logs are internal hairpin
traffic (blackbox probes + the apps' OIDC hairpins, which since ADR-0166 go to
`profit-base.online/auth` rather than to a Keycloak host of its own; on the rootless host ADR-0196
aliases those public names to the container gateway), not masked external clients.

The Keycloak token endpoint carries a stricter, versioned limit on the same zone (`burst=10`,
status 429 — `location ^~ /auth/realms/iri/protocol/openid-connect/token` in
`conf.d/10-frontend.conf.template`), the one anonymous POST worth brute-forcing. The backend's
application-level Bucket4j limiter (REQ-SEC-011, REQ-SEC-032, REQ-SEC-033) is unchanged and remains
the precise, per-subject layer behind this coarse edge net.

**Acceptance**

- [ ] A burst above rate+burst from one client IP receives 429 responses (not the maintenance page,
  not 503) while other client IPs are unaffected (real per-client IPs on every vhost via the
  PROXY-protocol front end, ADR-0187; IPv6 keyed on the `/64`).
- [ ] Legitimate concurrency (many members each holding the mission page's SSE + WebSocket) does not
  exhaust the 500 per-IP `limit_conn` and is never 429'd.
- [ ] A normal page load (asset fan-out within the burst) is never limited.
- [ ] A sustained 429 rate at the edge raises `EdgeRateLimitSpike`.

**Enforced by:** `docker/edge/conf.d/00-maps.conf` (zones, `$krt_limit_key`) ·
`docker/edge/include/limits.conf` (per-vhost application, 429 statuses) ·
`scripts/check-edge-nginx.sh` (CI renders and starts the edge) ·
`monitoring/loki/rules/fake/basetool-log-alerts.yml` (`EdgeRateLimitSpike`) · **Decisions:**
ADR-0112, ADR-0162, ADR-0187 · **Runbook:** [`deployment.md` → Edge rate limiting](../deployment.md#edge-rate-limiting)

### REQ-SEC-024 — Keycloak resilience: internal JWKS + retryable 503 on IdP outage

Both JWT resource servers — the backend and the ingest gateway (REQ-SEC-001) — fetch Keycloak's
JWKS to validate every token. Two hardening rules keep a transient Keycloak / edge / container-DNS blip
from masquerading as an application outage (the failure mode that drove the frontend
`Http5xxRateHigh` incident: a slow / unreachable Keycloak turned JWKS retrieval into an
`AuthenticationServiceException` → `500` on every authenticated endpoint):

- **Internal JWKS (opt-in).** Setting `app.security.jwt.jwk-set-uri` (env `KEYCLOAK_JWK_SET_URI`)
  points key retrieval at the **internal** Keycloak connector
  (`https://keycloak:18443/auth/realms/iri/protocol/openid-connect/certs` — Keycloak is mounted under
  `/auth`, `KC_HTTP_RELATIVE_PATH`), reusing the `keycloak-trust`
  SSL bundle (REQ-SEC-014 — so the cert's SAN must carry `dns:keycloak`) instead of hairpinning
  through the public edge. The `iss` claim is still validated against the **public** issuer
  Keycloak stamps into tokens, so the split-horizon (public `iss`, internal key fetch) is
  transparent. Because `NimbusJwtDecoder.withJwkSetUri` defaults to **RS256-only** (unlike
  issuer-location discovery, which derives the accepted algorithms from the live JWKS), the
  internal-JWKS path explicitly widens the accepted set to the full asymmetric `SignatureAlgorithm`
  set, so enabling it never 401s a PS\*/ES\*-signed token (no HMAC is added, so no algorithm
  confusion). Empty (the default) preserves the auto-configured, issuer-derived decoder
  byte-for-byte — the knob is off until an operator opts in, and the `test` profile's placeholder
  issuer is unaffected.
  *(Corrected 2026-09-23: the backend's production environment never carried the variable —
  neither `docker-compose.yml` nor the Quadlet `env.d` template passed it — so the opt-in could not
  be taken in production. It now reaches the backend as `KEYCLOAK_JWK_SET_URI` from the host's
  `IRI_BACKEND_KEYCLOAK_JWK_SET_URI`, empty by default; the runbook is
  [`deployment.md` → *Internal JWKS for the backend*](../deployment.md#internal-jwks-for-the-backend).
  The ingest gateway reads the same property but sits on no network that reaches Keycloak, so it is
  not wired.)*
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
  problem (timeout, connection error, `UnresolvedAddressException` on a container-DNS strand, or a
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
- [x] The backend's production environment passes `KEYCLOAK_JWK_SET_URI`, empty unless the host
  sets `IRI_BACKEND_KEYCLOAK_JWK_SET_URI` (`JwkSetUriNamespaceTest`).
- [x] Production fetches the JWKS internally. _(2026-09-25 ~15:38 UTC, owner-approved, per
  [`deployment.md` → *Internal JWKS for the backend*](../deployment.md#internal-jwks-for-the-backend):
  only `env.d/backend.env` changed, the backend was healthy in 11 s, no JWKS/PKIX/SAN line, an
  authenticated `/api/v1/users/me` answered `200` and no `401` followed.)_
- [ ] A JWKS timeout / 5xx / DNS failure yields `503` + `Retry-After` (not `500`), logged at WARN
  and counted on `basetool_http_error_total{code="SERVICE_UNAVAILABLE"}`.
- [ ] An expired/invalid bearer token still yields `401`; a caller lacking the required role still
  yields `403` (the 503 re-map never swallows an auth decision).
- [ ] Optional `aud` enforcement (audit L-1) is available on both resource servers via
  `app.security.jwt.expected-audiences` (wired to `IRI_BACKEND_EXPECTED_AUDIENCES` /
  `IRI_INGEST_EXPECTED_AUDIENCES`), sharing the same `resourceServerJwtDecoder` bean. It is **empty
  by default** (off) so a stack whose realm does not stamp the audience is unaffected; enabling it
  in prod requires the deployed realm to stamp the right audience, or every token is rejected — and
  the two values differ: the backend expects `aud=basetool-backend` (the `extractor-ingest` default
  client scope), the ingest gateway `aud=basetool-ingest` (the `extractor-ingest-only` scope,
  REQ-INGEST-011). Setting the gateway to `basetool-backend` would pass exactly the frontend session
  tokens that interface must refuse.
- [ ] **The backend's `prod` profile requires it** (APPSEC-08, 2026-09-22): with a blank
  `app.security.jwt.expected-audiences` the prod context refuses to start
  (`JwtAudienceStartupCheck`), because blank means "no `aud` check" and a lost `.env` line used to
  switch enforcement off in silence. The effective audiences are logged at INFO on every start; any
  other profile keeps "blank = off" and logs it at WARN. The ingest gateway is unchanged (its value
  stays optional).
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
`JwtAudienceStartupCheck` + `JwtAudienceStartupCheckTest` (required under `prod`) ·
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

**The cookie is `__Host-SESSION` (FE-SEC-06, 2026-09-22).** The session cookie was called
`SESSION`; it now carries the `__Host-` prefix (`server.servlet.session.cookie.name`). A browser
accepts a `__Host-` cookie only when it is `Secure`, has `Path=/` and carries **no `Domain`** — so no
sibling subdomain and no plain-http response can plant or overwrite the session cookie (cookie tossing
into a session-fixation). All three conditions already held; the prefix makes the browser enforce
them. It also makes them load-bearing: a `domain:`, a non-root `path:` or `secure: false` in any
profile would not weaken the cookie quietly — the browser would drop it and every login would fail.
The rename drops every live session once, at the deploy that ships it — release **1.11.0**, not yet
on production as of 2026-09-25: the old `SESSION` cookie names nothing the app reads any more, so
each member signs in again exactly once (the Redis entries behind the old cookies simply age out;
no flush). The owner approved that trade.

**Acceptance**

- [ ] The session cookie is named `__Host-SESSION`, is `Secure`, has `Path=/` and no `Domain`, and
  no profile file overrides any of the four.
- [ ] A new session's default idle window is `app.session.anonymous-timeout` (the repository
  default), not the 30-day window.
- [ ] A successful OAuth2 login promotes its session's idle window to
  `app.session.authenticated-timeout`.
- [ ] No throwaway session is created merely to bump the timeout when none exists at login success.
- [ ] Members keep the 30-day "stay logged in" behaviour (cookie `max-age` + authenticated window).

**Enforced by:** `SessionCookiePrefixTest` (the `__Host-` name and its three conditions, in
`application.yml` and in no profile override), `LoginSmokeE2eTest` (a real browser holds
`__Host-SESSION` after the OIDC login), `RedisSessionConfigTest` (anonymous window is the repository
default),
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
- [ ] `terms.last_updated` moved when the obligation took effect (2026-08-03); it has moved with
  every later wording change since.

**Enforced by:** `TermsDocumentStructureTest` (every `terms.*` clause is reachable by the numbering
walk of `TermsDocumentService` and every translation has the German shape — a renumbered section
cannot silently drop a bullet; it replaced the frontend's `TermsTemplateBundleParityTest` when the
wording moved server-side, ADR-0138), `MessageBundleConsistencyTest` (DE/EN key parity, so the clause
cannot exist in one locale only) · **Text:** `terms.list_4_1_5` in the **backend's**
`messages_de.properties` / `messages.properties` / `messages_en.properties`, served by `GET
/api/v1/terms/document` (REQ-SEC-028) and rendered by the frontend's `templates/terms.html` via
`fragments/terms-body.html` · **Technical counterpart:** REQ-INGEST-011
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
`AdminTermsPageControllerTest`, `TermsDocumentStructureTest`,
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
not less. The deny-list bot filters and the backend access log's skip list therefore keep their raw
string tests; the boundary in each of those cases is the backend gate, which does match on the
decoded path.

> [!important] Amended 2026-09-13 — the frontend's gate-exemption list is no longer carved out
> This requirement used to name two more deliberate exceptions: `BackendRoleSyncFilter`
> (waiting-page redirect exemptions, static-asset skip) and `TermsAcceptanceGateFilter`
> (consent-page redirect exemptions), on the ground that decoding an exemption list only *widens*
> it. Two things undid that. Both filters now read one list, `frontend/config/PublicPaths`
> (REQ-SEC-052), so the exception no longer described where the code was; and the widening argument
> proved too coarse to be right.
>
> `PublicPaths` answers the same question `SecurityConfig`'s `permitAll` list answers, and Spring
> Security matches that list with a `PathPatternRequestMatcher` — on the **decoded** segment.
> Matching raw therefore made the two layers disagree about the same path:
> `/.well-known/assetlink%73.json` (`%73` is `s`) was `permitAll` and gate-exempt in neither filter,
> so the URL layer admitted the request and the gate below it redirected. Fail-closed, and a
> redirect rather than an exposure — but `PublicPaths` exists to be the *one* answer to "may a
> session gate redirect this path", and it was not the one answer for encoded spellings. That is the
> same defect as the two drifted `isStaticAsset` copies the class was extracted to replace, one
> layer down.
>
> **The widening is bounded, and that is what makes it safe.** A `PathPattern` decodes per segment
> and never re-joins them, so a spelling can only reach an entry `SecurityConfig` already
> `permitAll`s under that same spelling: the set of exempt **resources** is unchanged, and only the
> set of spellings that map onto them grows. Decoding the whole string instead — the shape this
> paragraph was right to fear — would have been fail-**open**: `/css%2f../missions` decodes to
> `/css/../missions`, which passes a `startsWith("/css/")` that Spring Security's own `/css/**`
> refuses, because a decoded `%2F` stays inside its segment.
>
> Found in the review of PR #1870 and fixed as its own change. Owner-approved 2026-09-13.

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
- [x] The frontend's gate-exemption list decides on the decoded path, so `permitAll` and both
  session gates accept the same spellings of a public document (REQ-SEC-052). The exempt
  **resource** set is unchanged; an encoded slash cannot manufacture an asset prefix, and a
  double-encoded spelling is not decoded a second time.

**Enforced by:** `PendingApprovalAccessFilterTest`, `TermsAcceptanceAccessFilterTest`,
`ActingMemberFilterPathMatchingTest`, `IngestPathScopeTest`, `ClientIdentityFilterTest`,
`FiltersTest`, `RequestLoggingFilterTest` (ingest), `RequestBodySizeLimitFilterTest`,
`ApiCacheControlFilterTest`, `PublicPathsTest` · **Code:** `IngestPathScope`,
`PendingApprovalAccessFilter`, `TermsAcceptanceAccessFilter`, `ActingMemberFilter`,
`RequestBodySizeLimitFilter`, `ApiCacheControlFilter`, `RateLimitingFilter` (backend + ingest),
`PayloadSizeLimitFilter`, `RequestLoggingFilter` (ingest), `PublicPaths` (frontend)

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

**Enforced by:** `provision-keycloak-mobile-client.test.sh` and, for the whole realm,
`provision-keycloak-realm.test.sh` section 4 (configuration and write order) and
`scripts/verify-dpop-binding.py` (behaviour) · **Code:**
`scripts/provision-keycloak-mobile-client.py`, which `scripts/provision-keycloak-realm.py` imports
for the client, profile and policy (`REQ-OPS-033`) · **Decision:**
[ADR-0131](../adr/0131-mobile-auth-refresh-only-dpop-binding.md) · **Measurements:**
[`ANDROID_API_EXPOSURE_PLAN.md`](../archive/ANDROID_API_EXPOSURE_PLAN.md) section 7

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
sensitive GET family means adding it to `NoStoreApiScopes` in the same change — which since
2026-09-10 also takes it out of the ETag buffer, because both filters read that one list.

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

**The list has a second reader since 2026-09-10** (ADR-0161 §8.3), and therefore lives in its own
type, `NoStoreApiScopes`, rather than privately inside `ApiCacheControlFilter`.
`StreamAwareShallowEtagHeaderFilter` skips these families too, because Spring's `isEligibleForEtag`
already refuses to generate an ETag once `Cache-Control` carries `no-store` — so those responses
were being buffered in full, in memory, for a header the framework had already decided not to emit.
Skipping them changes no response header at all.

What this adds to REQ-SEC-031 is an obligation in the other direction: **a family added to the list
now also leaves the ETag buffer, and a family removed from it re-enters one.** A second copy of the
list would let the two drift — ADR-0135's argument about a second copy of an authorisation rule,
applied to a rule about caching — so there is exactly one, and a test asserts that both filters
answer from it identically for seventeen paths.

**Acceptance**

- [x] Every listed family answers with `private, no-store`, including the notification SSE stream.
- [x] The member bank, mission-finance, personal-inventory, personal-blueprint, inventory, hangar,
  refinery-order and promotion reads in the frozen external contract are covered — asserted at the
  bare collection path as well as a child, since the bare path is itself an endpoint a client calls.
- [x] An encoded spelling of a sensitive path still gets `no-store`.
- [x] Other `/api/**` GETs are unchanged (the shared listings keep `must-revalidate`), and non-API
  paths and writes are untouched.
- [x] `Vary: Accept, Accept-Encoding` is set on the sensitive families. `Accept` joined it when
  §8.5 gave every `/api` path a second encoding (REQ-API-011): two representations at one URL means
  a cache keyed on the URL alone could hand a CBOR body to a JSON client. It buys nothing on *these*
  families — `no-store` keeps them out of every store to begin with — and is asserted here anyway,
  because a filter that emitted the header on one bucket and not the other would be a difference
  nobody chose.
- [x] The ETag filter skips exactly the same families, and no response header changes when it does
  (`StreamAwareShallowEtagHeaderFilterTest`, which asserts the premise against Spring's own filter
  rather than against a reading of its source).

**Enforced by:** `ApiCacheControlFilterTest`, `NoStoreApiScopesTest`,
`StreamAwareShallowEtagHeaderFilterTest` ·
**Code:** `ApiCacheControlFilter`, `NoStoreApiScopes`, `StreamAwareShallowEtagHeaderFilter`

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

The anonymous page-size ceiling used to be evaluated with **the same parser Spring's binder uses**
(`NumberUtils#parseNumber`), refusing what that parser rejects rather than letting it through:
`Integer.parseInt` is stricter and throws on `0x186A0`, `#186A0` and embedded whitespace, all of
which the binder accepts, so treating "unparseable" as "within the limit" made the ceiling
bypassable with three characters. **Retained as the lesson, not as a rule** — the ceiling and its
filter are gone with the anonymous surface (see the amendment above). The general form still holds
anywhere a guard and a binder read the same string: *parse it the way the thing you are guarding
will parse it.*

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
([`API_VHOST_ROLLOUT_RUNBOOK.md`](../archive/API_VHOST_ROLLOUT_RUNBOOK.md), phase W — archived). That ordering is the lesson worth keeping: **a path is
measured anonymously before it is admitted, and what the measurement says is acted on first.**
Admitting these as they stood would have published trade prices to the internet — the vhost would
have let them through and the backend would not have stopped them.

`GET /api/v1/materials/{id}` used to stay anonymous deliberately — `MaterialDto` is catalogue only
(name, quantity type, category, flags, **no price**), and `/api/v1/materials/search` had published
those same fields anonymously since the vhost's phase 2. **REQ-SEC-052 closed it with everything
else**: the public surface is now an enumerated list of four backend paths and this is not one of
them. The paragraph is kept because the reasoning is still the reason it was the *last* catalogue
read to go, not because the exemption survives.

*Retired 2026-09-06 with `AnonymousPageSizeFilter` (ADR-0159) — kept for the reasoning:* **an
unauthenticated caller MUST NOT request more than 1000 entries per page**, and the refusal MUST
be an explicit `400` naming the limit rather than a silent reduction. Silently clamping is the defect
ADR-0104 forbids: the caller gets fewer rows than it asked for, cannot tell, and any surface built on
a single large page then presents an incomplete list as complete. A page-walking consumer is
unaffected either way, since it asks for pages until they run out. The ceiling is 1000 because that is
exactly what the existing anonymous callers request — the guest order form's pickers and the catalogue
page-walks — so it costs the legitimate flows nothing.

Authenticated callers keep the 100 000 clamp. The scope is matched on the **decoded** path
(REQ-SEC-029).

**Acceptance**

- [x] `GET /api/v1/materials/matrix` answers 401 without a token — and since REQ-SEC-052 so does the
  rest of the material catalogue.
- [x] `GET /api/v1/materials/{id}/terminals` answers 401 without a token (`SecurityTest`).
- [x] `GET /api/v1/materials/prices-overview`, `GET /api/v1/materials/{id}/prices` and `GET
  /api/v1/materials/profit-calculation` answer 401 without a token
  (`ApiVhostAnonymousSurfaceTest`), and the nightly `edge-deny-probe` asserts it.
- [x] `GET /api/v1/materials/{id}` answers `401` without a token like every other catalogue read
  (REQ-SEC-052); it was the last one to close and is swept rather than pinned as an exemption.
- [x] ~~An anonymous request with `size=50000` is refused with `400` and the stable code
  `PAGE_SIZE_TOO_LARGE`.~~ Retired with `AnonymousPageSizeFilter` (ADR-0159): no paginated endpoint
  answers an unauthenticated caller, so there is no anonymous page to bound.
- [x] ~~An anonymous request with `size=1000` still succeeds.~~ Same.
- [x] An authenticated caller with `size=50000` is unaffected — the 100 000 `PaginationUtil` clamp
  is unchanged.

**Enforced by:** `SecurityTest`, `AnonymousSurfaceSweepTest` (the `HEAD`-per-`GET` pass that carries
the verb-agnostic lesson forward) · **Code:** `SecurityConfig`

### REQ-SEC-033 — An authenticated account MUST have a budget of its own

Every `/api/**` write (`POST`, `PUT`, `PATCH`, `DELETE`) and the notification SSE connect MUST be
bounded by a per-authenticated-subject token bucket, keyed on the JWT `sub`, in addition to the
per-IP buckets of REQ-SEC-011.

The per-IP limiter bounds a **network position**, which is the wrong unit in both directions. Behind
CGNAT many unrelated members share one IPv4, so a budget tight enough to matter throttles innocents;
and a caller with a pool of addresses is not bounded by it at all. The `sub` is bound to a Keycloak
identity and cannot be chosen by the client, so it is the only key that bounds an *account*. The
ingest gateway reached the same conclusion for the same reason (REQ-INGEST-005).

Reads other than the SSE connect — and the export carve-out below — are deliberately left to the
per-IP budget: they are cheap and are what a legitimate client hits most. Writes cost database work and produce audit rows, and an SSE
connect holds a server-side emitter open, so a reconnect loop is worth bounding by identity. Both
share one bucket on purpose — they come from the same client, and splitting them would let one
starve the server while the other stayed within its own budget.

Anonymous requests MUST pass through: they carry no subject to key on, and the per-IP limiter is
their bound (the anonymous page-size ceiling of REQ-SEC-032 went with the anonymous surface; the few
anonymous paths REQ-SEC-052 leaves are unpaginated). The budget MUST be enforced after
the pending-approval and terms gates, so a caller refused there does not spend a token first. The
scope MUST be decided on the **decoded** path (REQ-SEC-029). The rejection MUST reuse the per-IP
limiter's contract: `429`, the stable code `RATE_LIMIT_EXCEEDED`, and the
`X-Rate-Limit-*` headers including a retry hint.

The subject MUST NOT appear in a metric label or a log message — it is unbounded and it is PII. The
bucket map MUST be bounded so a flood of distinct subjects cannot grow it without limit.

> [!note] Amended 2026-09-22 — the export carve-out (APPSEC-10, owner decision of 2026-09-22)
> "Reads stay on the per-IP budget" did not hold up for the reads that render a whole document. Every
> `/api/**` path with an `export`, `export.json`, `statement`, `report`, `pdf` or `three-month-report`
> segment — the Art. 15 exports and their admin twin, the audit and bank-audit exports, both bank
> statements, the three-month bank report, the job-order handover reports and their preview — MUST
> spend from a **second, separate** per-subject bucket, `app.rate-limit.subject.export`: **10 per
> minute** by default, overridable per host through `APP_RATE_LIMIT_SUBJECT_EXPORT_CAPACITY` and
> `APP_RATE_LIMIT_SUBJECT_EXPORT_REFILL_PERIOD`, a capacity under 1 refusing to start. It is
> recognised by path segment, not by an endpoint list, so an export added later is covered without
> anyone remembering this filter. It is separate from the write bucket so downloads never cost an
> account its writes; an export that is also a write (the preview is a `POST`) spends from both, the
> export bucket first. The rejection is the same `429` / `RATE_LIMIT_EXCEEDED` contract, counted under
> the bounded label `bucket="subject_export"`, which the existing per-bucket alert
> `RateLimitRejectionRatioHigh` and the operations dashboard pick up without a change.

**Acceptance**

- [x] A second write beyond the budget from the same subject is refused with `429` and a retry hint.
- [x] Two different subjects do not share a bucket.
- [x] Ordinary reads spend no tokens; the SSE connect does.
- [x] An encoded spelling of an API write cannot shed the budget.
- [x] Anonymous callers pass through untouched.
- [x] Rejections and attempts are counted under the bounded `bucket=subject` label.
- [x] An export beyond the export budget is refused with `429` while an ordinary read is not, and
  the export bucket is separate from the write bucket; counted under `bucket=subject_export`.
- [x] Every mapped endpoint with an export-like final segment falls under the export budget (the
  endpoint sweep in `SecurityFilterChainOrderTest`).

**Enforced by:** `SubjectRateLimitingFilterTest`, `SecurityFilterChainOrderTest` (the export sweep),
`BackendPropertiesValidationTest` · **Code:** `SubjectRateLimitingFilter`,
`RateLimitProperties.Subject`, `RateLimitProperties.Export`, `SecurityConfig`

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
set from `realm_access.roles` on every authentication (an account left with no role is refused
`403 NO_ROLE` since REQ-SEC-053; it used to fall back to `Guest`), and
`CustomJwtGrantedAuthoritiesConverter` then derives the request's authorities from
that stored set. A client with no scope mappings therefore does not merely narrow what the app may
do — it rewrites the member's row in the database, for the web app too. Measured on the test stack
before this requirement existed: an account holding `Admin` + `Officer` + `KRT Member` was left
holding the `Guest` role of the time alone after one app login — today the same misconfiguration
would lock the member out with `NO_ROLE`.

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

**What this now depends on.** With `Admin` in the token, the 106 `@PreAuthorize(Roles.HAS_ROLE_ADMIN)`
sites and the nine `hasRole(Roles.ADMIN)` URL matchers in the backend `SecurityConfig` (counted
2026-09-22) are satisfied by an app-issued token. The app calls none of those paths, but a token is
not a client — the remaining boundary is the **default-deny vhost allow-list** of
[REQ-SEC-037](#req-sec-037--the-public-api-vhosts-anonymous-surface-is-enumerated-not-incidental).
When this was written that list lived in the edge proxy's database, out of reach of any test; since
2026-09-12 it is `docker/edge/include/api-allowlist.conf` in this repository, read by
`ExternalContractTest`, checked against the nightly probe by the `probe-vs-allowlist` check and
validated by `scripts/check-edge-nginx.sh` in `repo-lint.yml`. Two consequences, both load-bearing:

- `POST /api/v1/refining-methods` was the one allow-listed path carrying a bare `hasRole('ADMIN')`
  write. `refining-methods` is therefore in the read-only family of `api-allowlist.conf` (it was
  first added to the runbook block, now archived at
  [`API_VHOST_ROLLOUT_RUNBOOK.md`](../archive/API_VHOST_ROLLOUT_RUNBOOK.md)). Nothing loses a
  capability: the app only reads it (the refinery form's method picker) and the web admin does not
  traverse this vhost at all.
- The allow-list is now load-bearing for what this requirement itself used to guarantee. Anyone
  widening it is widening what a stolen mobile token can reach.

**The scope stays partial, and the client stays on the partial-role-scope list.** Adding `Admin`
covers every realm role that is actually assigned to people, but the realm also holds
`Logistician` and `Mission Manager` (and held `Guest` until ADR-0159) — so `basetool-android` remains a partial-scope client and
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

**Enforced by:** `provision-keycloak-mobile-client.test.sh` section 7 and
`provision-keycloak-realm.test.sh` sections 2 and 4 · **Code:**
`scripts/provision-keycloak-mobile-client.py` (`MEMBER_REALM_ROLES`, `FORBIDDEN_REALM_ROLES`,
`upsert_realm_role_scope`), `scripts/provision-keycloak-realm.py` (`_converge_role_scope`, which
converges the same list in both directions), `UserReconciliationService#syncUser`,
`CustomJwtGrantedAuthoritiesConverter`, `OrgUnitViewModel` (app) · **Decision:**
[ADR-0131](../adr/0131-mobile-auth-refresh-only-dpop-binding.md), and the 2026-09-02 reversal above

### REQ-SEC-036 — A client's role claim is authoritative only if its scope is complete

`UserReconciliationService#syncUser(Jwt)` mirrors a member's realm roles into `app_user` on **every**
authentication, and it does so by **replacement**: `user.setRoles(mapRoles(realm_access.roles))`.
That is correct only while every client's token carries the member's whole role list. It stopped
being correct the moment one client was deliberately given less.

A client whose Keycloak scope is narrowed — `fullScopeAllowed: false` plus a partial scope mapping,
which the mobile client of REQ-SEC-035 still is (its scope names five of the realm's seven
application roles) —
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
  would make the scope mapping stop deciding anything at all: `Logistician` and `Mission Manager`
  are still absent from it, so the row and the token still describe different members, and
  the token is the one the client was actually issued.

**Matched on `azp`**, a claim inside a Keycloak-signed token that a client cannot set — the same
handle `IngestGatewayProperties` already uses for the far more dangerous on-behalf-of decision, so
this adds no new trust. Configured under `app.security.partial-role-scope.client-ids`.

**Empty is the unsafe end here**, the reverse of the ingest gateway's list. There, empty means
"nobody may act for another member". Here, empty resumes overwriting stored roles from partial
tokens. The default therefore names the client known to be partial rather than shipping blank.

**A brand-new row is the one exception**, in the safe direction: there is no stored set to protect,
and the alternative is persisting a member with no roles at all, which REQ-SEC-053 would then refuse
with `NO_ROLE` (before ADR-0159 the `Guest` fallback stood in for it).

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
the link in the app, and the browser follows it instead. Nothing in either build can see it: the app
is correct, the server is correct, and only their agreement is missing. This shipped in the app's
v0.1.0 and was found by a member on a phone.

**Serving the file correctly does not end that failure, and the frontend MUST carry a fallback
route.** This paragraph used to say the member "lands on the 404 page", as though the 404 were
purely a symptom of the file answering `302`. It is not. Verified on 2026-09-15 — the file answers
`200` as `application/json` with zero redirects and the fingerprint matching both the app's README
and `application.yml`, the prod manifest carries `autoVerify` on the right host and path — and
requests still arrive at `/app/callback`. Two ways in survive a correct file, and **neither can be
closed from the server**:

- **A device whose domain verification already failed keeps that state.** On Android 12+ it is
  sticky: the member has to re-enable the link by hand under *Einstellungen → Apps → Standardmäßig
  öffnen*. Every phone that installed the app while the file still redirected is in that state.
- **A desktop browser has no app to hand the link to at all.**

Both appeared in one 24-hour window (5 requests, 3 distinct clients), so `/app/callback` is a route
the frontend answers by design rather than by accident:

|                  |                                                                               |
|------------------|-------------------------------------------------------------------------------|
| `/app/callback`  | `303` to `/app/link-help`, **anonymous**, and the query is dropped            |
| `/app/link-help` | a page, **anonymous**, explaining what happened and how to re-enable the link |

**The redirect is the security half, not a convenience.** The URL carries a live authorization
code. Rendering a page at `/app/callback` leaves that code in the address bar, in the history entry
and — the response being `Referrer-Policy: strict-origin-when-cross-origin` — in the `Referer` of
every same-origin subresource the page pulls. A redirect leaves none of it: the intermediate URL
does not become a history entry, and the page the member reaches has a clean address. The code is
single-use and PKCE-bound, so what this prevents is exposure rather than an exploit, which is
exactly the reasoning RFC 9700 applies to codes in URLs. It was not theoretical: on 2026-09-15 a
`Google-Read-Aloud` fetch from `66.249.0.0/16` requested the full callback URL, code included,
within one second of each attempt.

**Anonymous, for two separate reasons.** The member is mid-login and may hold no session, so behind
the authenticated catch-all the page would redirect into the OAuth2 entry point — the loop it
exists to break. And the catch-all would put the callback URL into `HttpSessionRequestCache`, to be
replayed by `SavedRequestAwareAuthenticationSuccessHandler` after the next login, landing the member
back on a dead code.

Both paths stay behind the pending-approval and consent gates (they are **not** in `PublicPaths`).
The precedent is `/`: `permitAll` but still gate-eligible, because it is a page rather than a
machine-fetched document. A member who owes consent is told that instead, and their login could not
have completed anyway.

**The digest list is a list, and that is load-bearing.** A signing-key rotation must publish the
new digest **before** the rotated APK ships, while the old key is still installed on every device;
both must therefore be servable at once. Configuration:
`app.android-app-link.sha256-cert-fingerprints`, overridable per environment. A rotation that
replaces rather than appends breaks every installed copy for the length of the rollout.

**Acceptance**

- [x] `/.well-known/assetlinks.json` answers `200`, `application/json`, with no redirect, to a
  caller with no session (`AssetLinksControllerTest`).
- [x] The digests are published as an array, so a rotation can name two at once (same test).
- [x] `/app/callback` answers `303` to `/app/link-help` anonymously, and the target carries no part
  of the query (`AppLinkControllerTest`).
- [x] `/app/link-help` renders anonymously and resolves every `appLink.*` key rather than printing
  them (same test).
- [x] Neither path can quietly lose its `permitAll`: the anonymous sweep asserts the redirect
  target rather than skipping the route (`AnonymousSurfaceSweepMvcTest`), and the fallback page is
  swept at every device class (`FrontendPageRoutes.PAGES`).
- [x] Both are asserted **from outside**, because every test above runs in-process against MockMvc
  and stays green through an edge rule, a cache, or a `permitAll` entry lost in a merge:
  `/app/link-help` is a target of the `blackbox-public-surface` job (`200`, no redirect) and
  `/app/callback` of the `blackbox-app-link` job (`303` with a `Location` naming the help page),
  alerting as `EdgePublicSurfaceNot200` and `EdgeAppLinkFallbackBroken` (REQ-OBS-012).

> [!note] Added 2026-09-16 after a log triage
> The two routes shipped on 2026-09-15 with in-process tests only. The triage that found the last
> two `No mapping for GET /app/callback` lines — both timestamped **before** the fallback deployed
> that same day, so the log signature was already closed — found the probes missing instead, which
> is the half of this requirement that can regress in production without any test noticing. The
> fallback is not vestigial: it was used from the wild on 2026-09-16, the day after it shipped.

**Code:** `frontend/…/controller/AssetLinksController.java`,
`frontend/…/controller/AppLinkController.java`,
`frontend/…/config/AndroidAppLinkProperties.java`, `SecurityConfig` (anonymous matchers),
`frontend/…/templates/app-link-help.html`.
**Test:** `AssetLinksControllerTest` — asserts the three response conditions through the real
security chain, and that the digests are an array. `AppLinkControllerTest` — the fallback's redirect,
its dropped query and the page behind it. `AnonymousSurfaceSweepMvcTest` — that both stay anonymous.

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

> [!note] Amended 2026-09-12 (ADR-0162) — the allow-list lives in this repository
> Until 2026-09-12 the allow-list was a block in the rollout runbook that a human pasted into Nginx
> Proxy Manager, where no PR and no test could read it — several statements below were written in
> that world. It is now [`docker/edge/include/api-allowlist.conf`](../../docker/edge/include/api-allowlist.conf),
> included by `conf.d/50-api.conf.template` and applied by the deploy reconcile. Three things read
> it in CI: `ExternalContractTest`, the `probe-vs-allowlist` check in `repo-lint.yml` (the nightly
> probe's rows against the list) and `scripts/check-edge-nginx.sh` (renders and starts the edge).
> The runbook is archived at [`API_VHOST_ROLLOUT_RUNBOOK.md`](../archive/API_VHOST_ROLLOUT_RUNBOOK.md)
> and still explains *why* each family was admitted in which phase; it no longer carries the list.

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
> Every refused row is pinned in `ApiVhostAnonymousSurfaceTest`; the two `200` rows are pinned by
> `AnonymousSurfaceSweepTest` and `OpenApiAnonymousOperationsTest`.

The vhost is a default-deny allow-list (ADR-0135), and every path on it **inherits whatever
authentication the backend requires of that path**. Since ADR-0159 that is almost uniform — every
path but the two below is refused without a token (REQ-SEC-052). Allow-listing therefore decides
*reachability*, never *authorisation* — and the two are easy to conflate, because the list looks like
a security control and is only half of one.

**Each path admitted to the allow-list MUST have its anonymous status stated when it is added.** A
path that turns out anonymous when nobody intended it is the failure this exists to prevent, and the
list alone cannot show it: CI can read which paths are admitted, but not what the backend answers
behind them, and an unauthenticated endpoint answers exactly as cheerfully as an authenticated one.

The anonymous surface, complete:

|                 Operation                  |                                                                                                                     Why it is anonymous                                                                                                                      |                                                                             What an anonymous caller gets                                                                              |
|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/terms/document`               | ADR-0138 — wording everyone must read *before* agreeing cannot require having agreed                                                                                                                                                                         | the same text already world-readable at `/terms`                                                                                                                                       |
| `GET /api/v1/app/version-policy`           | REQ-API-010 — an app too old to authenticate must still be able to learn that it is too old; a token-gated gate is silent in the one case it exists for                                                                                                      | three integers and the public GitHub release URL. No caller identity goes in and none comes out — the rare `/api` path with nothing to redact                                          |

Until ADR-0159 (2026-09-06) the table also listed the mission search and detail (the anonymous home
page's Einsatz tiles) and four catalogue reads (`ship-types`, `materials/search`,
`locations/search`, `refining-methods`) that were `permitAll` in the backend chain. All six answer
`401` now; the landing page makes no backend call at all. (`materials/{id}/terminals` had left the
table earlier, carved out with `materials/matrix` under REQ-SEC-032.)

**`version-policy` was decided, not inherited.** The terms document is anonymous because something already
public depends on it; `version-policy` is anonymous because the owner chose it on
2026-08-24 against the standing stance that the vhost opens no anonymous paths (plan Q8). The
alternative was considered and rejected on the merits: a token-gated policy endpoint cannot answer
an app whose *login* is what the new contract broke, and that app would then show an authentication
error — telling a member their credentials are wrong when they are not. The exception is one path,
one verb, and a body with nothing in it worth protecting.

**Feature slices add paths, not anonymous ones.** Phase 4's Raffinerie and Materialbörse slices,
and every phase since, sit behind a member gate and answer `401` without a token; their statuses are
pinned in `ApiVhostAnonymousSurfaceTest` like every other allow-listed path. Families with a wide
controller behind them are admitted **by name rather than by stem** (the refinery family path by
path, for example), which is why the rest of those surfaces stays unreachable.

Everything else on the list is refused without a token — the Finanzen endpoints among them
(`isAuthenticated() and isMemberOrAbove() and canSeeMission`), and since ADR-0159 the mission itself
as well.

**A status is read off the layer that refuses the caller.** Before ADR-0159 an admitted path under
a `permitAll` stem was dispatched and refused at the method seam, which `GlobalExceptionHandler`
rendered as `403`, while a path the chain refused got `401` from the entry point — so neighbouring
paths answered different numbers for the same closure. `GET /api/v1/locations/refineries` was
recorded as `401` when it was admitted, answered `403`, and was corrected on 2026-08-31 after the
nightly probe had reported the difference for three nights; `refining-methods` was mis-recorded in
the same stroke. Neither had been pinned in `ApiVhostAnonymousSurfaceTest`, which is the step that
would have caught both at review time and is why the requirement names it: **a path admitted
without its pin is admitted without its status stated.** With every stem closed, an anonymous
caller now meets `401` on every refused admitted path; the role gates still decide what an
*authenticated* caller gets (`PUT /api/v1/orders/{id}/status` answers a member without
`LOGISTICIAN` with `403` at the method seam).

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

**Why the number used to split, kept because the mechanism still holds for authenticated
callers.** A request dispatched to a controller and refused by `@PreAuthorize` is rendered by
`GlobalExceptionHandler` as `403`; nothing upgrades it to `401`, because
`ExceptionTranslationFilter` — the component that would substitute the entry point for an anonymous
caller — never sees an exception the MVC advice has already handled. Only a request the filter chain
refuses gets the entry point's `401`. Before ADR-0159 the Finanzen paths sat under the `permitAll`
stem `GET /api/v1/missions/**` and answered an anonymous caller `403`; with the stem gone they answer
`401` like the me-scoped paths, and `ApiVhostAnonymousSurfaceTest` pins that.

**The missions and operations families are additionally read-only on this vhost.**
`/api/v1/missions/<uuid>` and `/api/v1/operations/<uuid>` answer `PUT` and `DELETE` as well as
`GET`, and an allow-list that matches on the path cannot tell them apart, so the vhost refuses
every non-`GET`/`HEAD` under a read-only family (`$krt_readonly_family` in `api-allowlist.conf`,
which covers more prefixes than these two) with `405` before the request reaches the backend —
except where a write is named explicitly. `@PreAuthorize` would refuse them too; the point is that it
does not have to be the only thing that does. Under operations the write that matters is `PUT
/api/v1/operations/<uuid>/payouts/paid-out`, which marks a member as paid — phase 3 opened it by
naming that one path rather than widening the family, because the guard is verb-blind by design.
Later phases opened more writes the same way (the participation writes, the Einsatz planning set,
and a **method-scoped** `PUT` on `/operations/<uuid>` and `/orders/<uuid>` that keeps `DELETE` on
both shut).

**A refusal by this vhost is `404` or `405`, and which one is decided by order, not by family.** The
allow-list's default deny runs first; the read-only guard runs after it. A path that is on no
allow-list line is therefore `404` for **every** verb — its family membership in the read-only guard
is never consulted, because the request is already refused. Only an *admitted* path can answer
`405`, and that is what `PUT` and `DELETE /api/v1/missions/<uuid>` do: the detail is on the list as a
read, so the verb is the only thing left to refuse (`POST /api/v1/orders`, the example that stood
here, has since been admitted and answers `401`). `POST /api/v1/hangar/import/fleetview` used to read the
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
half of the family (`POST /read-all`, `DELETE /read`, `DELETE /{id}`, `POST /{id}/read`) was opened
by name in phase 5, each authenticated and me-scoped, and answers `401` anonymously too.

**The mission search is no longer the exception it was.** It used to be accepted as anonymous and
larger than the home page it fed (arbitrary `start`/`end`, paged, JSON rather than HTML), bounded
only by the edge limiter. ADR-0159 closed it with the rest: the landing page makes no backend call,
and the search answers `401` without a token.

**Both layers have to be read together.** This project puts authorization at the method seam by
design, so a matcher list alone over-reports what is open (a path under a `permitAll` stem may still
carry a method gate) — which is why the table above lists operations rather than matchers, and why
REQ-SEC-052 requires a method gate on every controller as well.

**Acceptance**

- [x] Every allow-listed path has a recorded expected status without a token — today in the nightly
  `edge-deny-probe.yml`, checked against `api-allowlist.conf` by `probe-vs-allowlist` (the archived
  runbook's § D.3a was its first form). The previous single-number check raised a false alarm the
  first time it was run against a `permitAll` path.
- [x] Both anonymous operations are named, each with the already-public surface it mirrors.
- [x] The two live-sync paths were checked specifically: `isAuthenticated()` at the controller, so
  neither is anonymous; the stream's partial-authorization answer (`403` only when *no* topic was
  accepted) and the publish path's `429` are pinned in `LiveSyncControllerTest`, and both paths get
  their row in `ApiVhostAnonymousSurfaceTest` and in the nightly probe.
- [x] `POST /api/v1/missions` was checked specifically: `isAuthenticated()` at the method (and,
  since ADR-0159, at the chain), so an anonymous create is refused — and it is not on the allow-list
  either.
- [x] The edge rate limiter covers this host without a per-host entry: `50-api.conf.template`
  includes `docker/edge/include/limits.conf` like every other vhost (REQ-SEC-023).
- [x] A check that fails when an allow-listed path becomes anonymous without the table moving.
  The nightly `edge-deny-probe` workflow asserts the whole table from outside — the only vantage
  point that sees what the deployed vhost and backend actually answer together. It catches both
  directions: a `2xx` where the table names a refusal is an unauthenticated read of member data,
  and a `404` where it names a status means the admission never reached the host — the failure
  that otherwise has no signal at all, and that shipped a blank mission-detail screen once already.
  The id-dependent rows use a fixed nil UUID: authorisation is decided before the row is looked up,
  and the old discovery of a real id from the anonymous search was itself the finding REQ-SEC-052
  closed.
- [x] The backend half is pinned in CI too: `ApiVhostAnonymousSurfaceTest` asserts the status each
  allow-listed path gives an anonymous caller, so the table cannot drift from the code even between
  nightly runs.

#### What the probe leaves in the backend log

> [!note] Rewritten 2026-09-22 — the four `403` lines are gone
> This subsection used to describe a signature of two to four anonymous `403 ACCESS_DENIED` WARN
> lines (refineries, home-locations and the two Finanzen paths, the last two on a live mission id
> discovered from the anonymous search). ADR-0159 turned all four into `401`s and the probe now uses
> a nil UUID, so a passing run leaves no anonymous `403` at all.

The probe's refusals are the *expected output of a passing assertion*. Every row it asserts `401`
is refused by the entry point, which logs at **DEBUG** (the REQ-OBS-001 `401` carve-out), and the
edge-level `404`/`405` rows never reach the backend — so a passing run leaves **no** backend WARN
line. The schedule is `17 5 * * *` UTC, but match on paths, not the clock: a burst at another hour
is equally a `workflow_dispatch`, a delayed schedule or an unrelated anonymous caller.

That makes an anonymous `403 ACCESS_DENIED` WARN on an allow-listed path a finding, not noise: it
means a path the chain should refuse was dispatched to a controller — a `permitAll` stem has come
back. Keep that line at WARN and out of the `401`→DEBUG carve-out; `AccessDeniedSpike` (`0.2/s for
10m`) is a spike detector, not a drift detector.

**Enforced by:** review at the moment a path is added, backed by the probe and the pin below ·
**Code:** `SecurityConfig` (filter chain), `docker/edge/include/api-allowlist.conf`,
`docker/edge/conf.d/50-api.conf.template` · **Tests:** `ApiVhostAnonymousSurfaceTest` (the
anonymous status of every admitted path), `edge-deny-probe.yml`, `probe-vs-allowlist` ·
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
> behalf of *this* somebody."**

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

> [!note] Amended 2026-09-22 — the rule outlived the guest audience (ADR-0159)
> The heading keeps its original wording so the id stays searchable. Since ADR-0159 the redactor is
> `MissionPeerRedactor`, its passes are `cleanup…ForPeer` (`cleanupUserForPeer`,
> `cleanupUnitForPeer`, `cleanupShipForPeer`), the reader it protects against is a **member below
> Logistician** rather than an unauthenticated outsider, and the ArchUnit rule is
> `peerReadableMissionEndpointsMustRedactPii`. The requirement — every nested user record gets its
> own pass — is unchanged. The text below keeps the guest-era names where it tells the history.

The mission guest redactor's compiler-enforced exhaustiveness (the explicit full-field record
reconstruction) only holds for records it actually **descends into**. A nested collection forwarded
by reference is a hole in it, and MUST NOT contain a record that reaches a user, a squadron or free
text without its own `cleanup…ForGuest` (now `cleanup…ForPeer`) pass.

Concretely: `assignedUnits` is forwarded to outsiders as mission planning data, and each unit's
`ship` carries a full `UserDto` owner. `UserMapper` nulls only `email`, so an un-redacted
pass-through handed an **unauthenticated** caller of the public mission detail the ship owner's
`roles` and `permissions` — i.e. who holds `ADMIN`/`OFFICER` — plus their free-text `description`,
org-unit memberships, `joinDate` and `discordLinked`. `Ship.owner` is `nullable = false`, so any
unit with an assigned ship always carried one. The owner is now reduced to the public callsign
tuple by the same `cleanupUserForGuest` (now `cleanupUserForPeer`) pass every other nested user
goes through.

Note what did **not** catch this, because the same blind spots apply to the next nested record:
`anonymousReadableMissionEndpointsMustRedactGuestPii` (now `peerReadableMissionEndpointsMustRedactPii`)
asserts that a redaction method is *called*, never that the redaction is *complete*; and `ExternalContractTest` freezes
`assignedUnits` only as a top-level field name and never inspects the nested shape.

**Acceptance**

- [x] A peer's `GET /api/v1/missions/{id}` (written for the outsider, who no longer exists) returns
  `assignedUnits[].ship.owner` with `roles`, `permissions`, `description`, `email`, `squadron`,
  `squadrons`, `joinDate` and `discordLinked` all null, and the callsign tuple (`username`,
  `displayName`, `effectiveName`, `rank`) intact.
- [x] ~~The strict outsider level inherits the pass from the member-peer level.~~ Retired with the
  outsider tier (ADR-0159); there is one level left.
- [x] A unit with no assigned ship redacts without error.

**Enforced by:** `MissionPeerRedactorTest` · **Code:** `MissionPeerRedactor#cleanupUnitForPeer`,
`#cleanupShipForPeer` · **Related:** REQ-SEC-007, REQ-SEC-009, ADR-0159 (supersedes ADR-0034)

### REQ-SEC-041 — The mission description is gated on membership, not on authentication

`MissionMapper#resolveDescription` MUST return the free-text mission description only to a caller
who is a member or above (`AuthHelperService#isMemberOrAbove`), never merely to an authenticated
one.

> [!note] Amended 2026-09-06 (ADR-0159) — the cohort is gone, the rule is kept
> The gap this closed was a role-less `GUEST`: authenticated, yet a mission outsider by
> REQ-SEC-009. There is no such caller left — a token that maps to no application role is refused
> `403 NO_ROLE` before any handler runs (REQ-SEC-053). The requirement stands anyway, and
> deliberately: `isMemberOrAbove()` and `isAuthenticated()` are still different questions
> (REQ-SEC-009), and a gate that asks the narrower one must not be relaxed to the wider one because
> the difference happens to be empty today.

The gate had been bare `isAuthenticated()`, which the *detail* endpoint compensated for by nulling
the description in `cleanupOutsiderMissionForGuest`. The list and search projections run through no
redactor, so a `GUEST` token read on `GET /api/v1/missions/search` — an operation in the frozen
external contract, reachable over the public API vhost — returned the planning notes the detail
endpoint deliberately withheld from the very same caller. Gating at the single source fixes both
projections instead of bolting a second redactor onto the list path. Anonymous callers were
protected only incidentally (`isAuthenticated()` was false for them), which is why the gap was
invisible from the anonymous surface.

**Acceptance**

- [x] ~~A role-less `GUEST` token gets `description == null` from `/api/v1/missions/search` and from
  the mission detail alike.~~ Such a token is refused before the mapper runs (REQ-SEC-053); the
  gate itself is unchanged and still pinned.
- [x] A member still receives the description on both.

**Enforced by:** `MissionViewerAccessServiceTest` · **Code:** `MissionMapper#resolveDescription`,
`MissionViewerAccess#isMemberOrAbove` · **Related:** REQ-SEC-009, ADR-0159

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
act on the mission ([`MULTI_SQUADRON_PLAN.md`](../archive/MULTI_SQUADRON_PLAN.md) § 1, archived:
editing is the owning OrgUnit's prerogative).

The self-booking branch resolves the caller's participant row by `(missionId, userId)` and compares
it to the requested id, so it enforces three conditions at once — the row exists, it belongs to this
mission, and it is the caller's — mirroring `canEditFinanceEntry`'s "owner **and** still a
participant" rule.

**Acceptance**

- [x] A member who may only *see* the mission gets `403`, and the service is never invoked.
- [x] A member naming another participant's row gets `403`.
- [x] A member booking against their own row succeeds.
- [x] A mission manager in scope may book for any participant.
- [x] An anonymous caller still gets `401`, and a token that maps to no application role still
  `403` — the marker behind that refusal is `ROLE_NO_ROLE` since `V239`, not `GUEST`
  (REQ-SEC-053).

**Refinery orders linked to a mission (added 2026-09-22).** A refinery order's `mission` link is the
same kind of write: `OperationPayoutCalculator` adds every linked order's result to the operation's
payout pool and credits its expenses to the order's owner. `RefineryOrderService` used to check only
that the mission existed, so any member could attach an order to any mission whose id they knew —
another Staffel's included — and move that pool, while the order itself did not even appear in that
mission's Staffel-scoped refinery list.

The link therefore MUST only be set to a mission the order's **owner** takes part in, i.e. holds a
participant row resolved by `(missionId, ownerId)`. The rule binds the owner, not the caller, and
has **no exception for a caller who manages the mission** (owner decision, 2026-09-22): a
logistician booking on someone's behalf, or a mission manager, is held to it too. It is checked on
create, and on update **only when the mission changes** — an unchanged link is not re-checked, so an
order linked before the rule existed, or whose owner has since left the mission, can still be edited.
Clearing the link is always allowed. A refusal answers `400` with the stable problem code
`MISSION_PARTICIPANT_REQUIRED` (`MissionParticipantRequiredException`), which the refinery create
and detail pages map to a message naming the mission field.

**Acceptance (refinery link)**

- [x] Creating an order linked to a mission its owner takes part in succeeds.
- [x] Creating one linked to a mission its owner does not take part in answers `400`
  `MISSION_PARTICIPANT_REQUIRED` and persists nothing.
- [x] Changing an order's mission to one its owner is not on is refused — also for a logistician.
- [x] Saving an order with its mission unchanged does not re-check participation.
- [x] Clearing the mission is always allowed.

**Enforced by:** `MissionSecurityServiceTest`, `MissionFinanceEntryControllerSecurityTest`,
`RefineryOrderServiceLifecycleTest`, `RefineryOrderTest`, `RefineryOrderFailureToastTest` ·
**Code:** `MissionSecurityService#canCreateFinanceEntry`,
`MissionFinanceEntryController#createFinanceEntry`, `RefineryOrderService#resolveMissionForOwner` ·
**Related:** REQ-SEC-006, REQ-SEC-009

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
  `GET /api/v1/me/layout` carries the identical `capabilities` object, from the same resolver, beside
  the other layout reads (REQ-API-012); a client that gets no answer from either treats every flag as
  `false`.
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
`JobOrderMapper` · **Related:** REQ-SEC-046, ADR-0047, and the Android counterpart REQ-APP-AUTH-014
(`basetool-android` `docs/specs/auth.md`)

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
> switcher offers". **That was wrong**, and it hid a second empty switcher behind the first. A
> member may hold a seat on a Bereich or on the Organisationsleitung *and on nothing else*
> (`V165` forbids an OL member a Staffel row at all), and those units own aggregates in their own
> right — `OrgUnitStampingService` applies no kind filter, REQ-ORG-016. Listing only Staffeln and
> SKs therefore left exactly those members with a switcher that had nothing in it but „Alle
> Org-Einheiten", while the scope predicate would have honoured a pin to any of them
> (`RequestScopeResolver#currentScopePredicate` accepts a pin inside the caller's expanded reach).
> The web's own `orgunit-select.html` had grouped all four kinds since epic #692 Phase 5; the
> restriction lived only in the backend.

The membership branch is the epic #692 Phase 5 drill-down reach
(`OrgUnitMembershipQueryService#listPickerOptionsWithDescendants`) rather than the direct-membership
list (`#listOptionsForUser`), which is what makes a leadership seat resolve to the units below it.
The admin branch is `#listAllPinnableOptions`, kept separate from `#listAllActiveOptions` because
that one is also the Job-Order request form's requesting/responsible-unit picker and must stay
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
- [ ] Both clients read this endpoint rather than branching themselves. The web half is done —
  `OrgUnitContextAdvice` makes the one call (checked 2026-09-22); the Android half is tracked in
  `basetool-android`.

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
- **A `BindingResult` never enters a flash attribute** (added 2026-09-23). The session can *write*
  one — `BindingResultMixin` hides its self-referencing model — but cannot *read* it back:
  `BeanPropertyBindingResult` and `FieldError` have no constructor Jackson can use, so the
  redirect's GET dropped the **whole flash map** — form input, field errors and every toast flashed
  beside them. The admin personal-inventory form did that from the day it was written: an invalid
  submission came back as a closed modal with no errors. A form that fails validation re-renders its
  view inline, which every other form in the frontend already did.

Tomcat 11.0.25 added `org.apache.tomcat.websocket.server.WsHttpSessionBindingListener`, a `record`
that `WsServerContainer#registerAuthenticatedSession` writes whenever an authenticated WebSocket
handshake finds the attribute unset. It fired `SessionValueDropsSustained` at ~70 drops per 15 min on
2026-09-03 against an otherwise healthy application, and the rate could not decay: a dropped value
leaves the attribute unset, which is exactly the condition under which Tomcat writes it again. It is
the first and so far only entry on the allow-list.

> [!warning] Corrected 2026-09-03 — the handshake is **not** on every logged-in page
> This paragraph previously read "for this application, every logged-in page, because live sync opens
> `/ws/sync`", and ADR-0154 built its self-healing argument on that. It is false:
> `krt-live-sync.js` connects **lazily** — `ensureSocket()` is reached only from `subscribe()`,
> `sendChanged()` and `sendPresence()` — so a page that subscribes to no live-sync room never
> handshakes. Getting the type id right therefore stops new poisoning but repairs nothing that is
> already stored, which is what REQ-SEC-050 exists for
> ([ADR-0157](../adr/0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md)).

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
- [x] No controller flashes a `BindingResult`; an invalid admin personal-inventory create or update
  re-renders inline with the modal open and its field errors, and flashes nothing.

**Enforced by:** `SessionSerializerRoundTripTest` (the required keys and scalars still read back;
the Tomcat listener round-trips and carries `@class`; a plain record and `List.of`/`Map.of` still do
not; a `BindingResult` writes but does not read) · `FlashAttributeTypesTest` (no
`addFlashAttribute` in the main sources names a binding result) ·
`AdminPersonalInventoryPageControllerMvcTest` (the inline re-render) · `FaultTolerantSessionSerializerTest`, `SessionAttributeDiagnosticMapperTest` (the survivable
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

### REQ-SEC-063 — A session that cannot be read is no session, never a 500

REQ-SEC-049 and REQ-SEC-050 are about an unreadable **value** inside a session. This requirement is
about the session **hash itself**, and it exists because the two are not the same failure and only
one of them was survivable.

`RedisSessionMapper` requires three hash fields — `creationTime`, `lastAccessedTime`,
`maxInactiveInterval` — and throws `IllegalStateException` when the hash it is handed is non-empty
and carries none of one of them. Nothing on Spring Session's read path catches that. It leaves
`SessionRepositoryFilter` and reaches the container, so **every** request carrying that cookie
answers HTTP 500 — and the cookie is never cleared on that path
(`SessionRepositoryFilter#commitSession` expires it only after an explicit `invalidate()`), so the
member is locked out of the whole application, for up to the 720-hour authenticated window
(REQ-SEC-025), until they delete the cookie by hand.

**Spring Session produces that hash on its own.** `RedisSession#saveDelta` writes the session back
with a plain `HSET` of the changed fields only, and `creationTime` is put into that delta solely
`if (isNew)`. A request that reads a live session and commits after the hash has vanished — a Redis
restart, an AOF truncation (`appendfsync everysec`), the hash's own TTL, a purge run against live
traffic — therefore **re-creates** the key holding `lastAccessedTime` alone, and `saveDelta` then
sets `expire(maxInactiveInterval + 5 min)` on it. The half-written state is not a blip; it carries
the full session TTL.

This ran in production unseen for months: 286 such 500s on 2026-09-14 (the burst opens in the hour
Redis restarted), 18 on 2026-09-16, 8–10 a day on nine scattered days back to July — with **zero**
`basetool_session_value_dropped_total` increments beside them, which is what rules out REQ-SEC-049's
failure mode and proves the key was never written rather than dropped.

**The rule:** a session hash the mapper cannot map reads as *no session*, not as an exception.
`SessionAttributeDiagnosticMapper` catches the `IllegalStateException`, counts it, logs it once per
distinct missing key, and returns `null` — a contract both upstream call sites already honour:
`RedisIndexedSessionRepository#getSession` null-checks the mapper's result and returns `null` to the
filter, which mints a fresh session, and `#onMessage` skips the `SessionCreatedEvent`. The member is
signed out and a login fixes it, which is the same bargain REQ-SEC-049 struck for an unreadable
value.

**The hash is deliberately not repaired.** Deleting or rewriting it would be a Redis write on the
session *read* path, which ADR-0157 rules out for the subsystem that took the whole application down
twice inside two releases. The orphan is left to expire with its TTL; nothing reads it again, because
the browser is now carrying a different session id.

> [!warning] The degradation is only allowed to be quiet because it is measured
> If every session lost a required key at once — a genuine wire-format break — a silent `null` would
> sign the whole organisation out with no signal anywhere. `basetool_session_unmappable_total` and
> `SessionUnmappableSustained` are what make the quiet safe, and the alert sums **across** the
> `missing_key` tag so a break that loses all three fields cannot hide below a per-series threshold.
> Widening the catch beyond `IllegalStateException`, or dropping the counter as noise, re-opens
> exactly that hole.

**Acceptance**

- [ ] A session hash missing any one of the three required fields yields `null` from the mapper, not
  a thrown exception — for each of the three, not just the first one read.
- [ ] Such a request is served as if it carried no session; the member is signed out rather than
  answered 500, and a login restores them.
- [ ] Every give-up increments `basetool_session_unmappable_total`, tagged with the field that was
  absent; the tag is resolved from the hash and can only take four literals.
- [ ] The mapper writes nothing to Redis on that path — the half-written hash is left untouched.
- [ ] A poisoned *attribute* (REQ-SEC-049) still yields a usable session with that attribute unset;
  the two failure modes do not collapse into each other.
- [ ] The required-key literals are pinned against the real upstream mapper, so a rename upstream
  fails a test instead of silently reporting every failure as `other`.

**Enforced by:** `HalfWrittenSessionHashIntegrationTest` (against a real Redis and the real
`RedisIndexedSessionRepository`: a delta write after the hash vanishes re-creates it half-written and
re-TTLs it; the half-written hash reads as no session and is counted; it is not repaired; a healthy
session is unaffected) · `SessionAttributeDiagnosticMapperTest` (each required key parameterised, the
`other` bucket, the upstream-literal pin, and the two failure modes staying apart) · **Code:**
`SessionAttributeDiagnosticMapper#apply`, `RedisSessionConfig#sessionRepositoryCustomizer` ·
**Monitoring:** `SessionUnmappableSustained`, `basetool_session_unmappable_total`
([`observability.md`](observability.md)) · **ADR:**
[ADR-0186](../adr/0186-an-unmappable-session-hash-reads-as-no-session.md), and
[ADR-0157](../adr/0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md) for why
the repair does not live here · **Related:** REQ-SEC-025, REQ-SEC-049, REQ-SEC-050, REQ-OBS-006

### REQ-SEC-067 — A session value may name only an allow-listed class

Every non-final session value is written with an `@class` type id (REQ-SEC-049), and the reader
instantiates whatever class that id names. Until 2026-09-23 the session serializer's type validator
was `allowIfBaseType(Object.class)` — every class on the frontend's classpath — so the session store
was a deserialization sink: whoever can write one field of one `basetool:session:*` hash can have
Jackson build any class and call its setters on the next request carrying that cookie. That store is
Redis, which three services reach (APPSEC-05, improvement audit 2026-09-22).

**The rule:** a session value's type id must name a class on `SessionTypeAllowList`, matched by
**name** before the class is loaded:

| Entry | Why it is in a session |
| --- | --- |
| `java.util.*`, `java.time.*` — direct members only | collections, dates and durations written by Spring Session, Spring Security and our filters; `java.util.logging` / `java.util.concurrent` are **not** covered |
| the boxed scalars of `java.lang` (`Boolean` … `String`), `java.math.BigDecimal` / `BigInteger`, `java.net.URL` / `URI` — exact names | a final type in an `Object` slot of a container is written with a type id after all: the ID token's `iss` claim (`URL`), numeric claims and the session-created event's timestamps (`Long`), a flashed count |
| `com.nimbusds.jose.shaded.gson.internal.LinkedTreeMap` — exact name | a nested ID-token claim (`realm_access`) as Nimbus decodes it |
| `com.nimbusds.oauth2.sdk.util.OrderedJSONObject` — exact name | a token-response JSON object as the Nimbus OAuth 2.0 SDK parses it, inside the stored authorized client (added 2026-09-23: under `enforce` its refusal dropped `AUTHORIZED_CLIENTS` and looped every E2E login) |
| `org.springframework.security.*` | security context, OAuth2 login and authorized-client state, CSRF token, saved request (the Security Jackson modules add their own exact types on top) |
| `org.springframework.web.servlet.FlashMap`, `org.springframework.util.LinkedMultiValueMap`, direct members of `org.springframework.validation` | a redirect's flash attributes; `validation.beanvalidation` is **not** covered |
| `de.greluc.krt.profit.basetool.frontend.model.*` | the application's own forms and DTOs, flashed across a redirect |
| `CONTAINER_WRITTEN_FINAL_SESSION_TYPES` | Tomcat's WebSocket binding listener (REQ-SEC-049) |

A new session attribute of a type outside the list is a change to this table, in the same PR.

> [!warning] Corrected 2026-09-23 — the first list refused every signed-in member under `enforce`
> The list as merged (PR #2018) had no `java.lang`, `java.math` or `java.net` entry and not the
> Nimbus map, on the belief that final types never carry a type id. Inside a container's
> `Object` slot they do. A real login's ID token carries `iss` as `java.net.URL`, so `enforce` would
> have made the security context unreadable for every member, and the session-created event's
> `Long` timestamps unreadable for the active-sessions gauge. Production ran `report` and was never
> exposed; the E2E stack, which runs `enforce`, was. The parity sample now takes its ID token from
> the real Nimbus decoder and Spring's OIDC claim conversion rather than a hand-built map, which is
> how the gap was missed.

**Three modes**, `app.session.type-allow-list` / `APP_SESSION_TYPE_ALLOW_LIST`:

- `off` — the permissive validator of before, byte for byte; the escape hatch.
- `report` (**default**, and what a merge deploys) — every value is read exactly as before; a class
  outside the list is counted on `basetool_session_type_refused_total{mode="report"}` and named once
  in a `WARN`.
- `enforce` — a class outside the list is refused; `FaultTolerantSessionSerializer` drops that one
  attribute (REQ-SEC-049/050 — it is repaired on the same request), the member keeps the rest of the
  session. Production is switched to it by the owner once the report counter has stayed at zero
  ([`deployment.md` → *Session type allow-list*](../deployment.md#session-type-allow-list-report-then-enforce)).

The mode governs **reading** only: what is written is identical in all three, so a mode switch
touches no stored session and needs no migration.

**Acceptance**

- [ ] Every value a production session holds — the OIDC security context with an ID token decoded by
  the real Nimbus decoder and Spring's claim conversion, the session-created event payload, the
  authorized client
  with both tokens, the pre-login authorization request, the CSRF token, the saved request, the flash
  maps, our filters' attributes, Tomcat's listener — reads back under `enforce` byte-identically to
  the permissive validator.
- [ ] A gadget-shaped class outside the list is refused under `enforce` **before** it is
  instantiated, dropped as an unreadable attribute and counted on both counters.
- [ ] Under `report` that same value is read and reported; under `off` it is read and not reported.
- [ ] A subpackage of an allowed JDK or Spring package is not allowed by the parent entry.
- [ ] A mistyped mode falls back to `report`, never to a failed startup.
- [ ] The E2E stack runs with `enforce`, so every login, refresh, flash redirect and live-sync
  handshake in the suite is a session read under the strictest mode.

**Enforced by:** `SessionTypeAllowListTest` (parity over a realistic session, gadget refusal before
construction, report/off reading, package boundaries, metrics, mode parsing) ·
`SessionSerializerRoundTripTest`, `RedisSessionImportFlashRoundTripTest`,
`FaultTolerantSessionSerializerTest`, `HalfWrittenSessionHashIntegrationTest`,
`SessionAttributeRepairIntegrationTest` (all built on the enforcing mapper) · the E2E suite
(`docker-compose.e2e.yml` sets `enforce`) · `monitoring/prometheus/tests/session_type_allow_list_alert_test.yml`
· **Code:** `SessionTypeAllowList`, `RedisSessionConfig#springSessionDefaultRedisSerializer` ·
**Monitoring:** `SessionTypeOutsideAllowList`, `basetool_session_type_refused_total{mode}`
([`observability.md`](observability.md)) · **ADR:** [ADR-0206](../adr/0206-a-session-value-may-name-only-an-allow-listed-class.md) · **Related:** REQ-SEC-049,
REQ-SEC-050, REQ-SEC-063

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
| free text (`q`, the list pages' `search`)               | a `WebClient` URI-template variable            | REQ-FE-016                                                                                                             |
| the mission / operation list period (`start`, `end`)    | `Instant` + `@DateTimeFormat(iso = DATE_TIME)` | `MissionController#searchMissions`, `OperationController`                                                              |
| the mission list `status`                               | narrowed to `PLANNED`/`ACTIVE`/`COMPLETED`/`CANCELLED` | the backend's mission status vocabulary                                                                          |
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
- [ ] The mission and operation list pages relay `search`, `start` and `end` as `WebClient`
  URI-template variables, never concatenated into the URI: a search carrying `&`, `#`, `+`, `{…}` or
  `%` reaches the backend as one decoded `query`, and a period reaches it decoded exactly once so it
  parses as an `Instant`. (FE-SEC-01, 2026-09-22: the mission list concatenated all four filters —
  `&` opened a second backend parameter, `#` cut the query, `{x}` threw — and the operation list
  URL-encoded its dates into the template, so the WebClient encoded them twice and the operation
  date filter never worked.)

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
`MaterialboersePageController`, `MissionPageController#listMissions`,
`OperationPageController#listOperations` · **Enforced also by:** `ListSearchRelayParamsTest` (exact
template + variables, and the query a `MockWebServer` backend actually receives) · **ADR:**
[ADR-0158](../adr/0158-a-relayed-request-parameter-is-bound-to-the-backends-own-type.md)

**The active-OrgUnit switcher redirects only on-site (FE-SEC-02, 2026-09-22).** `POST
/me/active-org-unit` returns to the page it was posted from via the form's `_referer` field, which
went into a `RedirectView` unchecked: a crafted form could send a signed-in member from the app to any
site. `MeFrontendController.safeRedirectTarget` now honours `_referer` only as a path with exactly one
leading `/` — no scheme, no `//host`, no `/\host` (browsers read that backslash as a slash), no
control character — and falls back to `/` otherwise. `orgUnitId` is bound as a `UUID`, so a malformed
one is a `400` instead of the `500` an unguarded `UUID.fromString` produced. **Enforced by:**
`MeFrontendControllerTest`.

### REQ-SEC-052 — No anonymous surface beyond the landing page and the enumerated infrastructure paths

The public surface is a **list**, not a policy. Everything on it is here because somebody argued for
it and the argument is written down; everything else requires authentication at the URL layer **and**
a method gate.

**Frontend** — the only `permitAll()` matchers:

|                                      Path                                       |                                                                                                                                                       Why it stays public                                                                                                                                                       |
|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/`                                                                             | The landing page. Product name, one paragraph, the two login entries, the legal links, the Fan Kit band. **No backend call, no data, no session.**                                                                                                                                                                              |
| `/impressum`, `/privacy`, `/terms`                                              | Legal obligation: Impressumspflicht, DSGVO information duties, terms readable before agreeing.                                                                                                                                                                                                                                  |
| `/licenses`                                                                     | The third-party licence notice (REQ-UI-021, ADR-0197): owed to whoever receives the software, and the landing page already serves the bundled font to a visitor with no session. No backend call, no data. |
| `/error`, `/error/**`                                                           | Error pages carry no data, and an error view that needs a session cannot render the outage that broke it.                                                                                                                                                                                                                       |
| the asset trees, `/favicon.ico`, `/robots.txt`, `/sm/**`, `/**/*.map`           | Assets. The three mechanical entries keep the OAuth2 saved-request replay off a 404 (REQ-SEC-025, ADR-0088).                                                                                                                                                                                                                    |
| `/.well-known/assetlinks.json`                                                  | Android App Links verification is fetched by the platform with no session (REQ-SEC-038).                                                                                                                                                                                                                                        |
| `/manifest.webmanifest`                                                         | The web app manifest, read by a browser on the landing page before any login (REQ-UI-020, ADR-0164). Three localised strings, two colours and the path of an already-public icon; behind the catch-all it would answer `302` into OAuth and installs would name the app after the login page. Same class as the entry above it. |
| `/app/callback`, `/app/link-help`                                               | The Android App Link fallback (REQ-SEC-038): a member mid-login may hold no session, and behind the catch-all the callback would loop into OAuth and park its authorization code in the saved-request cache. `/app/callback` only answers `303` to the help page, dropping the query.                                          |
| `/actuator/health`, `/actuator/health/**`                                       | The container health check (the image's `HEALTHCHECK`, rendered into the Quadlet `HealthCmd` in prod); in prod Actuator lives on the internal management port (ADR-0090).                                                                                                                                                     |
| `/oauth2/authorization/keycloak`, `/login/oauth2/code/keycloak`, `POST /logout` | Spring Security's own login and logout endpoints — filters, not matrix entries.                                                                                                                                                                                                                                                 |

> **Amended 2026-09-22 (ADR-0197, approved by @greluc):** `/licenses` joined the table. It is the
> third-party licence notice of REQ-UI-021 — a legal page like the three above it, with no backend
> call and no data — and it is a `LEGAL_PAGES` entry of `PublicPaths`, so neither session gate
> redirects a member away from it.

> [!note] `/auth/**` is on this origin and is **not** in the table above — it never reaches Spring
> Since [ADR-0166](../adr/0166-identity-moves-onto-the-app-origin.md) Keycloak answers at `/auth` on
> the web host, so that the installed web app's sign-in stays inside its manifest `scope`
> (`REQ-UI-020`). The edge routes the prefix to the Keycloak container before the frontend sees it,
> so there is no `permitAll` entry to add and no session gate to exempt — the requests are not this
> application's at all. Two properties travel with the move and are worth stating where the public
> surface is enumerated:
>
> - **The admin console did not become public.** It moved from `keycloak.profit-base.online/admin`
>   to `/auth/admin` and kept the same bridge-gateway allow-list with its closing `deny all`, which
>   the nightly external deny probe asserts from a GitHub runner — the only vantage point that can,
>   since an internal probe shares the network position the rule tests.
> - **Keycloak now receives this application's session cookie** (`SESSION`, renamed
>   `__Host-SESSION` on 2026-09-22 — REQ-SEC-025), because a cookie scoped to `/` is
>   sent to every path on the origin. Recorded rather than mitigated: Keycloak already holds every
>   member's credentials and mints their tokens, so an identifier it ignores adds nothing to what a
>   compromised Keycloak could already do, and filtering a `Cookie` header in nginx would put a
>   fragile hand-written rule in front of the login path for no reduction in blast radius.
>
> [!important] `permitAll` is only half of "public" — the session gates are the other half
> `permitAll` decides **authorisation**. It does not stop a filter further down the chain from
> redirecting an *authenticated* caller away from the path, and two do: `TermsAcceptanceGateFilter`
> sends an unconsented member to the consent page, and `BackendRoleSyncFilter` sends an unapproved
> one to the waiting page and reconciles roles against the backend on the way.
>
> For a **page** that is correct. For a **document** — one an external verifier or a browser fetches
> on its own schedule — it turns the document into a login page. `/.well-known/assetlinks.json`
> shipped exactly that way: `permitAll` here, exempt in neither gate, so a signed-in member was
> answered with the consent page and each hit paid a `/api/v1/users/me` round trip.
>
> The exemption therefore lives in **one** place, `frontend/config/PublicPaths`, which both gates
> read: `isStaticAsset` (the asset trees, the favicon, `/sm/`, `*.map`), `isPublicDocument`
> (`/robots.txt`, `/.well-known/assetlinks.json`, `/manifest.webmanifest`), `isAuthInfrastructure`
> (login, logout, OAuth, error, actuator) and `isLegalPage` (`/impressum`, `/privacy`, `/terms` — the
> pages a gate may never hold a member away from, REQ-SEC-028). **A new public document goes in both
> this table and `isPublicDocument`.** Pinned by `PublicPathsTest` plus a case in each gate's own test.
> The App Link fallback pages are `permitAll` but deliberately in none of these sets: they are pages
> for a member, not documents a verifier fetches, so a signed-in member still meets the gates there.
>
> **Both halves match the same way, so both accept the same spellings.** Spring Security matches
> `permitAll` with a `PathPatternRequestMatcher`, which decides on the percent-**decoded**
> segment. `PublicPaths` compared the raw `getRequestURI()`, so `/.well-known/assetlink%73.json`
> was `permitAll` here and exempt in neither gate — admitted by the URL layer, then redirected by
> the one below it. Fail-closed, so a consistency defect rather than a hole, but the same defect
> as the drifted copies above, one layer down. Every predicate in `PublicPaths` matches a parsed
> `PathPattern` now: **Spring Security is the reference and `PublicPaths` follows it.**
>
> Decoding widens the *spellings*, never the set of exempt *resources* — nothing becomes exempt
> that this table does not already list. Decoding the whole string instead would have been
> fail-**open**: `/css%2f../missions` decodes to `/css/../missions` and passes a
> `startsWith("/css/")` that Spring Security's own `/css/**` refuses, because a decoded `%2F`
> stays inside its segment. Matching per segment cannot make that mistake. `PublicPathsTest` pins
> `%73`, `%2E`, `%2e%2e`, `%252e` and `%2f`; the default `StrictHttpFirewall` refuses all but the
> first of those with a `400` before any of it is reached. Same defect, same fix and same
> reasoning as the backend's `TermsAcceptanceAccessFilter`, `PendingApprovalAccessFilter` and
> `RequestBodySizeLimitFilter`.

**Backend** — the only `permitAll()` matchers on the main chain:

|                   Path                    |                                                                                                                             Why it stays                                                                                                                              |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/app/version-policy`          | The forced-update gate (REQ-API-010). A version gate that only answers after a login is silent in exactly the case it exists for: an app too old to log in must still learn that it is too old. Three integers and a public release URL.                              |
| `GET /api/v1/terms/document`              | The Terms-of-Use wording (ADR-0138 / REQ-SEC-028). A document everyone must read before agreeing to anything cannot require having agreed, and the same text is already on the public `/terms` page.                                                                  |
| `/internal/**`                            | The Keycloak SPI's account-existence precheck (REQ-SEC-022) — machine-to-machine behind a constant-time shared-secret header, `401` without it. Keycloak sits outside the resource server's trust boundary and carries no JWT to gate on. Not an anonymous data path. |
| `/actuator/health`, `/actuator/health/**` | The container health check on the local stacks; in prod Actuator lives on the internal management port (ADR-0134).                                                                                                                                                    |
| `/error`                                  | Spring's error dispatch.                                                                                                                                                                                                                                              |

**Both anonymous reads are `GET`-scoped**, so a `HEAD` on either falls to the authenticated
catch-all and answers `401`. That is deliberate and it is the REQ-SEC-032 lesson: Spring Security
compares the verb with `String.equals`, so a method-scoped tightening placed above an all-verb
`permitAll` grants every verb it does not claim — which is how a `HEAD` once ran the material price
query anonymously and returned its `Content-Length`.

The OpenAPI document is **not** on either list. It enumerates every path, parameter and DTO field
the API has — the most efficient description of the attack surface the project can produce — and
being a 404 in prod is a deployment property, not an access rule. It requires `ROLE_ADMIN` under
**both** spellings springdoc registers: `/v3/api-docs*` and `/v3/api-docs/**`. The second pattern
alone, which is what stood here, does not cover `/v3/api-docs.yaml` — `**` spans whole segments — so
that spelling fell through to the catch-all and was merely authenticated, on a path that carries the
whole surface. Corrected 2026-09-06; the sweep excluded it too, by prefix, and now excludes nothing
of the kind.

**Ingest** — the gateway's chain `permitAll`s only `/actuator/health(/**)` and `/v3/api-docs/**`;
the springdoc document is disabled in prod (`springdoc.api-docs.enabled: false`, so it answers
`404`), and in prod Actuator lives on the internal management port `11272` (ADR-0090). Its two
`/v1/**` endpoints require a bearer token and the client-identity gate (REQ-INGEST-011, ADR-0129).

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
- [x] Exactly four methods declare `@PreAuthorize("permitAll()")` — the two anonymous reads, the SPI
  precheck and `BasetoolErrorController#handleError` (added 2026-09-07, when the rule learned to see
  `@RequestMapping`); a fifth fails the build.
- [x] Exactly two OpenAPI operations declare `security: []`, and no operation references a security
  scheme the document does not define.
- [x] The landing page mints no session and makes no backend call: `getSession(false) == null`, no
  `__Host-SESSION` (formerly `SESSION`) cookie, `verifyNoInteractions(backendApiClient)`.
- [x] A read endpoint without a method- or class-level `@PreAuthorize` fails the build, like a write.

**Enforced by:** `AnonymousSurfaceSweepTest` (three passes over every mapping, plus `HEAD`) ·
`AnonymousSurfaceSweepMvcTest` (the frontend, navigation and background shapes) ·
`EndpointEnumerationTest` (the enumeration both sweeps ask the dispatcher with, shared since #1804 —
a defect there would turn both guards green at once, so it is tested on its own rather than only
through them) ·
`ArchitectureTest#permitAllIsDeclaredOnlyOnTheFourPublicEndpoints`,
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
IdP. **There is therefore no account holding only a bank role, or only any other role** (confirmed
by the repository owner, 2026-09-06) — which is what makes `isMemberOrAbove()` safe as a gate on
the member surface. The E2E realm contradicted this until then: `test-bank-employee` and
`test-bank-management` carried their bank role alone, so those two accounts, and no real one, met a
refusal on the mission list. A fixture that models an impossible account shape produces findings
about a cohort that does not exist. That is the structural half of this requirement, and it is why
the roster sync had to be fixed in the same change:

> [!warning] The composite-blind sync was the precondition, not a detail
> `KeycloakService` indexes **directly-assigned** realm roles (`GET /roles/{name}/users`). A member
> holding `KRT Member` only through the composite came back with an empty set on every nightly run.
> While that mapped to the authority-less `GUEST` it was invisible and healed at the next web login;
> with this requirement live it would be an overnight lockout of everyone who never had the role
> assigned directly. The sync now folds in what the realm's default-role composite grants.
>
> A run in which the realm matches **none** of the app's roles aborts rather than writing, because
> that is a rename or a broken query and never a legitimate state. A **single** account resolving to
> no role is still written through.
>
> [!bug] Corrected 2026-09-07 — stripping roles is not how a member is offboarded
> The callout above used to end "removing someone's roles in Keycloak still removes their access".
> It does not, and the refutation is the fold-in it names two sentences earlier:
> `default-roles-<realm>` is assigned to every account Keycloak creates and is **not** removed when
> an admin clears the user's other role mappings, so the composite credits `KRT Member` back on the
> next nightly run. The two statements cannot both hold, and the fold-in is the one this
> requirement needs.
>
> **Offboarding is disabling or deleting the account in Keycloak** (owner decision, 2026-09-07), and
> the enforcement is the IdP's own: neither account is issued a token, so no request reaches this
> requirement. The local row mirrors both facts anyway — `in_keycloak` for presence,
> `enabled_in_keycloak` for the `enabled` flag (V230) — because the ingest gateway's acting-member
> path (ADR-0129) installs an authentication with no token to refuse, and its liveness guard is the
> only reader of either. An account that reaches the sync with no role is therefore one the realm
> never granted anything, not one somebody meant to remove; the `basetool_norole_refused_subjects`
> gauge and `NoRoleBlockSpike` are what make that population visible.

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
  **The lockout signal counts subjects, never requests.** `basetool_norole_refused_subjects` is a
  gauge of the distinct subjects `PendingApprovalAccessFilter` refused with `NO_ROLE` in a rolling
  15-minute window, and `NoRoleBlockSpike` alerts on it with `max()` (per process). The refusal
  *rate* answers a different question and fails in both directions: one member's open tab polling in
  the background sustains it on its own, while the event the alert exists for — a realm-side role
  rename — locks the whole membership out at 03:00, when nobody is making requests, so the rate stays
  near zero until morning. This is the same lesson `basetool_terms_refused_subjects` was built on
  after `TermsConsentRolloutStalled` fired twice overnight on a single looping tab (2026-08-03), and
  the two windows are separate instances because one member can be in both populations.
- [x] That routing takes effect on the request that **discovers** the state, not the one after it.
  The role read is where the frontend first meets a role-less account, and it runs *after* the
  approval gate — so a filter that only caches the verdict serves the discovering request, which
  renders the dashboard with every fragment on it refused `403 NO_ROLE` and defers the copy
  explaining why to the next click.

**Enforced by:** `AnonymousSurfaceSweepTest` (the role-less pass over every mapping) ·
`CustomJwtGrantedAuthoritiesConverterTest` · `PendingApprovalAccessFilterTest` ·
`UserReconciliationServiceTest` · `KeycloakServiceTest` (the composite fold-in and the aborted run) ·
`V239MigrationTest` · `BackendRoleSyncFilterTest` · `NotificationRuleServiceTest` ·
`PendingApprovalPageControllerTest` (the account-status routing and its redirect loop) ·
`BackendApiClientProblemJsonTest` (the refusal is not a backend-call failure) ·
`norole_block_subjects_test.yml` (the alert counts subjects) ·
**Code:** `CustomJwtGrantedAuthoritiesConverter#assembleFor`, `Roles.NO_ROLE_MARKER`,
`PendingApprovalAccessFilter`, `MetricNames.NO_ROLE_REFUSED_SUBJECTS`, `UserReconciliationService`,
`KeycloakService#fetchDefaultRoleGrants`,
`NotificationRuleService#validateSelector`, `frontend/…/config/BackendRoleSyncFilter`,
`V239__drop_guest_role_and_guest_edit_token.sql` · **ADR:**
[ADR-0159](../adr/0159-the-basetool-has-no-anonymous-or-guest-surface.md)

### REQ-SEC-055 — A duplicate account stays consolidatable after it has been approved

A member who already has an account and signs in via Discord lands in the approval queue as a
seemingly-new registration whenever their Discord handle differs from their in-app name — the
collision precheck (`REQ-SEC-022`) is fail-open on purpose. The queue carries the remedy: „Verknüpfen“
(`REQ-SEC-026`) moves the Discord identity onto the existing account and disposes of a registration
that cannot yet own anything.

**Approving the duplicate used to take that remedy away.** The queue serves `PENDING` and `REJECTED`
only — `ACTIVE` is refused with `400` so a small admin queue cannot degrade into a member dump
(ADR-0140) — and `linkRegistrationToExistingAccount` guards on `PENDING` in the service as well, so
the action was gone rather than merely hidden. `decide(...)` refuses every non-`PENDING` row by
design, and `REOPENED` (`REQ-SEC-034`) covers only `REJECTED`. What was left for an admin who noticed
one click too late was an eight-step hand-over across the Keycloak admin console and the member
administration, every step of it a production write. This is the same shape ADR-0140 already closed
once for rejections.

`POST /api/v1/users/{id}/consolidate` (ADMIN) is the remedy, surfaced as an action on **every** row
of the member administration. It is the queue's link plus the belongings move, because an approved
duplicate — unlike a pending one — has been able to accumulate data.

**The path names the account that is dissolved and the body the one that survives**, the opposite way
round from `POST /admin/registrations/{id}/merge`. Deliberate: the admin acts on the duplicate's row
in the member list, so the URL names the row they clicked.

It composes rather than reimplements. `UserAccountMergeService#merge` (`REQ-SEC-046`) decides which
rows follow the member and which stay with the act, and refuses two bank ledgers rather than guessing;
`KeycloakService` moves the federated identity and removes the duplicate's realm user;
`UserDeletionService` purges the emptied row. The orchestrator is **non-transactional**, mirroring
`REQ-SEC-026`: the Keycloak writes cannot roll back with the database, so the identity is linked
first, the database half commits through a self-proxied method, and the duplicate's Keycloak user is
deleted **last** — a rolled-back database half then leaves it intact for a clean re-read on retry.

**The ordering inside the transaction is not free choice.** `app_user.discord_user_id` is UNIQUE
(V172), so the duplicate's row must be gone before the survivor can claim the snowflake: belongings
move, the duplicate is purged, and only then is the survivor stamped. Same reason the `PENDING` path
deletes the throwaway row first and stamps the survivor second.

**Guards refuse rather than guess.** Self-consolidation; the acting admin's own account (the purge
reassigns shared aggregates to „some other admin“, and that fallback must not be reasoning about the
row being removed); a target that is not `ACTIVE`; a target carrying a **different** Discord identity
— the *same* one is the ordinary half-finished case, where someone linked the survivor before
disposing of the duplicate, and is allowed; two bank ledgers (`REQ-SEC-046`); and a stale
optimistic-lock version. Each is a `409`.

**Acceptance**

- [x] An ADMIN can consolidate a duplicate into an existing account from `/members`, choosing the
  survivor through the server-searched `remote-users` picker; the action is on every row, not only on
  rows the sync believes Keycloak no longer holds.
- [x] What the duplicate owns lands on the survivor; what records an act stays where it happened.
- [x] The Discord identity moves onto the survivor in Keycloak and onto its `discord_user_id`; a
  duplicate with no Discord identity consolidates the same way, minus the identity move.
- [x] Both the duplicate's Keycloak user and its `app_user` row are gone afterwards, the Keycloak
  user **last**, and a retry after a database failure completes instead of throwing on a row that is
  no longer there.
- [x] Self / own-account / non-active target / different-identity target / stale version are each
  refused with `409` before anything is written.
- [x] A `LINKED` `UserApprovalEvent` is recorded against the survivor, and `merge` records its own
  `USER_MERGED` audit event (`REQ-AUDIT-001`). No PII in either.
- [x] The roster updates in place via `krtFetch`, with no full-page reload (`REQ-FE-001`), and the
  three message bundles carry every new string.

**Enforced by:** `AccountConsolidationServiceTest` (ordering, the retry case, and every guard) ·
`UserDeletionServiceTest` (the waived presence check, and that the admin-facing form still enforces
it) · `UserRegistrationServiceTest` (the `PENDING` path passes the waiver) ·
`MembersPageDiscordColumnRenderTest` (the action on every row + the dialog) · `DtoOpenApiContractTest`
· `MessageBundleConsistencyTest` · **Code:** `AccountConsolidationService`,
`UserController#consolidateAccount`, `ConsolidateAccountRequest` (backend + frontend),
`UserDeletionService.KeycloakPresenceCheck`, `MemberManagementController#consolidateMemberAjax`,
`members.html`, `messages*.properties` · **Issues:** #1827, #1828 · **Decision:**
[ADR-0160](../adr/0160-a-duplicate-account-stays-consolidatable-after-approval.md)

### REQ-SEC-056 — The authorities cache TTL is configuration, bounded at both ends

`CustomJwtGrantedAuthoritiesConverter` memoises the assembled authority collection per `(sub,
Keycloak session, azp, claims fingerprint)` — the session is the token's `sid`, falling back to its
`issuedAt` for a token without one (amended 2026-09-23, BE-PERF-08; until then the key was `(sub,
token issuedAt, azp)`, so every five-minute token refresh was a miss and a TTL above the
access-token lifespan bought nothing). That memoisation is the single control on how much load the authorization path puts
on [`PostgreSQL`](data-persistence.md): the converter runs on **every** authenticated call, and a
**miss** costs a write-capable `syncUser` transaction plus five to eight SELECTs — user load,
`user_roles`, **one role lookup per realm role**, and the membership read.

Measured on production 2026-09-13, at the original hard-coded 30-second window that miss traffic
came to **84.1 million sequential scans** across `role`, `org_unit`, `role_permissions`,
`org_unit_membership`, `app_user` and `user_roles` — tables holding five to sixty rows each — and
was the dominant driver of CFS throttling on `db-backend` (161 s in 29 h, a 229 ms average stall per
event, at 1.6 % of that container's own CPU quota). The ratios identify the source rather than
merely correlating with it: `role`/`user_roles` at **3.6** is the per-realm-role N+1 the Javadoc
describes, `app_user`/`user_roles` at **1.1** is one load per miss, and
`org_unit`/`org_unit_membership` at **2.0** is the cascade walk.

The TTL is therefore configuration — `app.security.authorities-cache.ttl`, reaching the container as
`APP_SECURITY_AUTHORITIES_CACHE_TTL` — defaulting to **`PT5M`**.

It is **bounded at both ends, and the context refuses to start outside them**. Zero or negative
would disable the cache and silently restore the storm. Above `PT15M` widens something else: the TTL
is also **the window in which a revoked role, a withdrawn permission, a reversed approval or a
removed org-unit membership stays effective within one Keycloak session**. A fresh login always
misses, because it opens a new session, so re-authentication picks up new authorities at once. A
refreshed token whose **claims** changed — a realm role granted or revoked in Keycloak, a renamed
account, a new e-mail address — also misses: every claim except `iat`, `exp`, `nbf` and `jti` is
hashed into the key, so a Keycloak-side role change bites on the next refresh, as it did while
`issuedAt` was in the key. What the TTL alone bounds is a change the token cannot carry: an approval,
a local role's permissions, an org-unit membership flag. At the default `PT5M` that window equals
the five-minute access-token lifespan that bounded it before; raising the TTL now really widens it,
which is what the `PT15M` ceiling is for.

The cache publishes its hits, misses, evictions and size as `cache_gets_total{cache="jwt-authorities"}`
and siblings, so the Spring-apps dashboard's cache panels and the `CacheHitRatioLow` /
`CacheSizeEvictionsHigh` alerts cover it like every `CacheConfig` cache.

**Acceptance**

- [x] The TTL binds from `app.security.authorities-cache.ttl` and defaults to `PT5M` when unset, in
  every profile.
- [x] A zero, negative or greater-than-`PT15M` value fails the application context at startup rather
  than degrading at run time; `PT15M` exactly is accepted.
- [x] A fresh login misses the cache regardless of the configured TTL, so re-authentication applies
  new authorities immediately.
- [x] A refreshed token of the same session and with unchanged claims is a hit; one with changed
  claims (e.g. a revoked realm role) is a miss and carries the change; two clients (`azp`) of one
  session never share an entry; a token without `sid` falls back to the `issuedAt` key.
- [x] Hits and misses are published under `cache="jwt-authorities"`.
- [x] The properties class sits in `support`, keeping `ArchitectureTest`'s package-cycle and
  `support`-is-a-leaf invariants green.
- [x] The operator's `IRI_AUTHORITIES_CACHE_TTL` in the host `.env` reaches the container as
  `APP_SECURITY_AUTHORITIES_CACHE_TTL`: `docker-compose.yml`'s backend `environment:` allow-list names
  it, and `scripts/generate-quadlet.py` carries it into `quadlet/env.d/backend.env.tmpl` for the
  production Quadlet unit.

**Enforced by:** `BackendPropertiesValidationTest` (default, both bounds, and the ceiling accepted
exactly) · `CustomJwtGrantedAuthoritiesConverterTest` (the converter builds against the real
properties; the session key, the claims fingerprint, the `azp` split, the `iat` fallback and the
cache meters) · `FirstLoginAuthoritiesIntegrationTest` · `ArchitectureTest` (`supportPackageMustStayADependencyLeaf`,
`backendPackagesShouldBeFreeOfDependencyCycles`) · **Code:** `AuthoritiesCacheProperties`,
`CustomJwtGrantedAuthoritiesConverter`, `application.yml`, `docker-compose.yml`,
`quadlet/env.d/backend.env.tmpl` · **Decision:**
[ADR-0174](../adr/0174-the-authorities-cache-ttl-is-an-operational-knob.md)

### REQ-SEC-057 — A refused registration is purged once its retention window expires

A registration in `REJECTED` MUST be removed automatically — the `app_user` row, its
`user_approval_event` rows and its Keycloak user — once the rejection is older than a configured
retention window. The window is configuration (`app.registrations.rejected-retention.max-age`,
default `P90D`), the sweep runs daily, and the whole job is disableable
(`app.registrations.rejected-retention.enabled`).

Deciding on an application is the only purpose a rejected registration ever served, and the
rejection fulfils it. What the row holds afterwards is not incidental: an e-mail address, a handle,
a Discord snowflake, a guild nickname, and — in `user_approval_event.reason`, a free-text `TEXT`
column — an admin's written assessment of a natural person who never became a member and has no
account to see it with. Nothing removed any of that. The member list's delete action offers itself
only for a user already gone from Keycloak (`!user.inKeycloak`), and a rejection deliberately leaves
the Keycloak user in place, so a refused registration was unreachable by every deletion affordance
the application had; the only remedy was a manual Keycloak-console deletion followed by a second
click nobody was prompted to make. Retaining it indefinitely also has no basis to rest on once the
decision is made.

**The window is not zero on purpose.** REQ-SEC-034 makes a rejection reversible because approval is
fallible, and purging the row ends that possibility — so the retention period is simultaneously the
period in which an erroneous rejection can still be reopened. Shortening one shortens the other.
Since 2026-09-22 (BE-MOD-03) "not zero" is a startup check: the keys bind through the validated
`RejectedRegistrationRetentionProperties` record, and a `max-age` under **`P1D`** (a `P0D` or
negative value would have purged every rejection on the next run, one decided a minute ago
included) or an `interval` under **`PT1M`** refuses to start the context.

**The purge reuses the account-deletion path** (`UserDeletionService`, REQ-DATA-008) rather than
issuing its own deletes, so it cannot drift from the foreign-key ordering that path owns. `decide`
admits `REJECTED` only from `PENDING`, so such an account never held authorities and owns nothing —
but should one arrive holding data anyway, that service reassigns the shared aggregates instead of
destroying them.

**Ordering is load-bearing.** Per row the database half commits *first* and the Keycloak user is
deleted *last*, the ordering REQ-SEC-026 / [ADR-0111](../adr/0111-admin-mediated-discord-registration-linking.md)
established for the same reason: a rolled-back database half leaves the Keycloak user intact, so the
next run re-reads a whole registration. The reverse order would strand the exact thing this sweep
exists to delete — an `app_user` row whose Keycloak account is already gone. Each row commits in its
own transaction, so one unpurgeable registration cannot roll back the rows already swept.

**No new audit event type.** The purge records `USER_DELETED` through `AuditService` like any other
deletion (REQ-AUDIT-001); running without a security context, the actor resolves to `null` /
`"system"`, which is what distinguishes a retention purge from an admin's. One real-world act keeps
one event type.

**Acceptance**

- [x] A registration rejected longer ago than `max-age` is removed from `app_user`,
  `user_approval_event` and Keycloak by the daily sweep.
- [x] A registration reopened (REQ-SEC-034) between the candidate query and the purge transaction
  survives: the re-read inside the transaction re-asserts `REJECTED` and the cutoff.
- [x] A rejection inside the window is untouched.
- [x] The Keycloak user is deleted only after the database half has committed.
- [x] One failing registration does not abort the run; an unreachable Keycloak does not undo or mask
  the committed local purge.
- [x] The sweep publishes `basetool_scheduled_job_*{task="rejected_registration_retention"}` and is
  covered by `ScheduledJobStale`.
- [x] A `max-age` under `P1D` or an `interval` under `PT1M` refuses to start the context.

**Enforced by:** `RejectedRegistrationRetentionServiceTest`, `RejectedRegistrationRetentionTaskTest`,
`BackendPropertiesValidationTest` (the floor) · **Code:** `RejectedRegistrationRetentionService`,
`RejectedRegistrationRetentionTask`, `RejectedRegistrationRetentionProperties`,
`UserRepository.findRejectedDecidedBefore`, `ScheduledJob`, `application.yml` · **Decision:**
[ADR-0178](../adr/0178-a-refused-registration-is-purged-on-a-retention-window.md)

### REQ-SEC-058 — Art. 15 / Art. 20 data export

Every member MUST be able to export their own data from the application, and an admin MUST be able
to export another account's for a request from somebody who cannot sign in.

**Two formats, not two alternatives.** JSON is the full disclosure and the Art. 20 portable copy.
PDF is the readable answer: master data in full, plus an **inventory naming every section with its
row count and legal basis**, so the document is complete about *what* is held even where it does not
print it. A PDF of several thousand warehouse movements and audit rows serves the right of access
worse than a short document that says exactly what exists and points at the machine-readable file
(decision by @greluc, 2026-09-15).

> [!warning] Corrected 2026-09-17 — the PDF printed raw column names to the member
> The four sections the document prints in full render one row per column as a FELD/WERT pair,
> and the FELD cell was the projection's own alias. A member exercising their right of access
> read `discord_guild_nickname`, `user_rank`, `join_date` and `share_blueprints_globally`.
> Every other string in the document comes from the bundle; these twenty-six did not, and no
> `pdf.export.field.*` key existed at all.
>
> In a document answering a legal request a schema identifier is wrong twice over: it is
> untranslated user-visible text, which the i18n rule admits no exception for, and it is not
> intelligible to the person it is addressed to. The keys exist in all three backend bundles
> now, and `DataExportPdfFieldLabelCoverageTest` fails the build when a projection selects a
> column the bundle cannot name — necessary because a missing key resolves to the key
> itself by design, which is exactly how the aliases got through.
>
> **Corrected with it:** both PDF endpoints recorded `rows: -1` in their `PERSONAL_DATA_EXPORTED`
> payload, on a sentinel documented as "when the format does not report one". The count was never
> unavailable — the renderer assembled the very export the JSON path reports `totalRows()` from and
> then discarded it. The caller assembles the export and hands it to the renderer now, so the
> audited count is the count of what was actually served.

> [!note] What the export costs, and which parts of that were changed (2026-09-17)
> The download now carries **its own response timeout** (`app.http.export-response-timeout`,
> default 120 s, per request). It is the one frontend→backend call expected to take a long time,
> and the shared 5 s bound turned a working export into a read timeout and a 500 for a member
> with years of history. It is deliberately **not** routed through the Resilience4j chain:
> retrying a minute-long export on a timeout multiplies the work that timed out. The admin
> erasure queue also looked each member's handle up per row; it is one query for the page now
> (REQ-DATA-003).
>
> Two further costs were weighed and **left as they are**, with the reasoning beside the code.
> The scrubber reads the whole roster per export rather than a projection of the three name
> columns, because a projection would route it around `HandleSpellings` — the single source
> the export and the erasure share, and the roster is in the hundreds. And the PDF is assembled
> from rows it reduces to counts, because a `COUNT(*)` variant per section would double the
> statement registry, put the PDF's counts on a different query and moment from the JSON's, and
> hand the two export coverage gates statements they do not check.

**Every section is marked with its legal basis**, so the portable subset is identifiable without
re-deriving it:

|   Marker    |                                          Meaning                                           |
|-------------|--------------------------------------------------------------------------------------------|
| `ART_15`    | the right of access                                                                        |
| `ART_15_20` | access **and** portable under Art. 20 — data the member *provided*, on consent or contract |

**Third-party data is excluded by the projections, not scrubbed afterwards.** Each section is a
written statement that lists the columns it returns, and no statement selects another member's id or
handle. A counterparty leak would therefore have to be written into a visible `SELECT` list. Four
places where that is the whole point:

- a **mission participation** returns the requester's own row and the mission it belongs to, and
  nothing about who else was there;
- a **booking** returns the member's side; `initiated_by` — the bank employee — is not selected;
- an **audit row where the member is the target** does not select `actor_handle`, because the acting
  person is somebody else. This is the section where the distinction matters most;
- **neither audit section selects `subject_label`**, in either direction. See the warning below.

> [!warning] `audit_event.subject_label` is not the non-personal label it looks like — 2026-09-16
> REQ-AUDIT-001 describes `subject_label` as a non-personal snapshot, and for most domains it is
> one: a material name, a rank step, an org-unit shorthand. For two domains it is a **person**. The
> job-order trails write `#<displayId> '<handle>'`, where the handle is the order's *contact*
> ("Handle des Ansprechpartners") and is frequently somebody outside the organisation with no
> account at all; and the account-deletion trail writes the member's own effective name. Both
> sections therefore shipped selecting a third party's name, against the acceptance criterion
> below.
>
> **Scrubbing could not have fixed it, and that is the generalisable lesson.** `HandleScrubber` is
> built from the roster, so it recognises registered members only: an external contact is invisible
> to it, and a deleted member has already left the roster it is built from. Adding the sections to
> `FREE_TEXT_SECTIONS` would therefore have produced an export that still carried the name *and*
> reported `thirdPartyHandlesRemoved = false` — telling the Art. 15(4) reviewer there was nothing
> to read through. **Where the scrubber cannot see the name, the column must not be selected.**

**Free text is the one place scrubbing is unavoidable**, and it is handled separately. A note the
member wrote is *their* data and belongs in the export, and it may name somebody else mid-sentence
where no `SELECT` list can reach. Six columns in five sections are scrubbed for the mirror-image
reason — they
carry a **name somebody gave a thing**, which can be a person's: `hangar.name` (`ship.name`),
`missionsManaged.mission` (`mission.name`, already scrubbed in the two sibling sections that select
it), `notificationRuleTargets.rule` (`notification_rule.description`), `bankAccountGrants.account`
(`bank_account.name`) and the two `orgChartPositions` name columns. Each is a person-name surface in
`PersonSearchTargets` (REQ-SEC-060), which is the registry that settles the question rather than a
per-section judgement call.

`HandleScrubber` replaces the names of other members, case-insensitively, longest match first (or
"Val" would leave "kyrie" behind from "Valkyrie"), in **one forward pass over the original text**,
and skips names under three characters because a two-character handle occurs inside ordinary words
and replacing it would shred every note. The replacement is the locale-free token
`#OTHER_MEMBER#`, for the same reason the erasure sentinel is a token: the export has no language
of its own, and the localised surfaces (`pdf.export.note.thirdParty`, the JSON's
`thirdPartyHandlesRemoved` flag) are what explain it.

> [!important] The scrub gate is the **column**, not the section — corrected 2026-09-16
> It used to be the section: every `String` value of a listed section went through the scrubber.
> That is fine for a section whose columns are all prose and wrong for every section that mixes
> prose with structured values. The `account` projection selects `username`, `email`,
> `approval_status` and six more identity fields, all `String` on the wire, and another member's
> three-character handle occurring inside the subject's **own e-mail address** was replaced — so the
> export handed the member a corrupted copy of their own identity while telling them third-party
> names had been removed.
>
> `DataExportSections.FREE_TEXT_COLUMNS` names the prose columns per section, and
> `UNSCRUBBED_PERSON_COLUMNS` records, with a reason, every selected column that *is* a person-name
> surface and is still deliberately not scrubbed. `DataExportScrubCoverageTest` walks each section's
> `SELECT` list and **fails the build** unless every such column appears in one map or the other —
> and unless every entry in either map names a section and a column that exist. Without that gate
> `notificationRuleTargets` shipped selecting an administrator's free text unscrubbed, and a renamed
> key would have dropped out of the scrub set while the export kept reporting success.
>
> **Widened 2026-09-17.** The gate asked `PersonSearchTargets.TARGETS` — the columns the
> Personensuche *searches* — which is a different question from whether a column can hold
> somebody's name. A column may instead be `EXEMPT_COLUMNS`, and the exemptions for technical
> payloads rest on reachability rather than absence: `notification.params` holds the handle
> `AccountDeletionRequestedEvent` writes into one row per administrator, and it is skipped by the
> search because searching it returns one hit per admin inbox for the same event. So the export
> scrubbed it while nothing required the scrub, and `notifications.params` was the one entry in
> the whole registry whose deletion no test would have caught. `PersonSearchTargets`
> `.EXEMPT_BUT_MAY_HOLD_A_NAME` names that class beside the reasons it is drawn from, and the gate
> asks for it too. Asking all of `EXEMPT_COLUMNS` instead would flag some forty status codes and
> identifiers and bury the one that matters.

> [!note] The spelling list is shared, and gate-enforced, since 2026-09-17
> Both data-protection surfaces need **every** name a member is stored under, not their
> effective name: the export scrubs other members' handles out of the subject's free text, and
> the erasure matches the text-only snapshots that carry no foreign key to the account. Each
> wrote the three columns out for itself, and a `DataExportService` Javadoc claimed a
> `HandleSpellingCoverageTest` held them together while no such class existed.
>
> `support.HandleSpellings` is that list now, and the test exists: every searched `app_user`
> name column is a spelling or is declared `NOT_A_SPELLING` with a reason, and the projection
> yields exactly one value per declared column. Since `PersonSearchCoverageTest` sweeps
> `information_schema`, a new name column cannot reach the schema without being registered for
> the search, and cannot be registered without being classified here.

The scrubbing half had four defects of its own, and it is the half where a defect is a leak rather
than a gap.

> [!warning] Four scrubber defects, all member-reachable — corrected 2026-09-16/-17
> Each of these was reachable by any member from their own profile, and the first two reached an
> export the member requested for themselves.
>
> - **Offsets from a lower-cased copy.** `String.toLowerCase` is not length-preserving (U+0130
>   lowercases to two characters), and the splice used indices found in the lower-cased text. A
>   match after such a character **leaked a prefix of the third party's handle** while
>   `thirdPartyHandlesRemoved` still reported success; a match near the end threw
>   `IndexOutOfBoundsException` out of both export endpoints. Matching is `String.regionMatches`
>   now, which compares character for character and cannot drift.
> - **The placeholder was scrubbed by later passes.** Scrubbing once per handle meant each pass read
>   the previous pass's output, so a three-character name that is a substring of the replacement was
>   substituted *inside* a placeholder — eleven such names expanded a 30-character note to 1780
>   characters. `display_name` is self-service, so any member could pick one and corrupt the free
>   text in **every other member's** export. The single forward pass cannot re-read what it emitted.
> - **One spelling per member.** The dictionary was built from `getEffectiveName()`
>   (`displayName ?: username`), so a third party's `username` — whenever they had a display name —
>   and everybody's `discord_guild_nickname` were never scrubbed. All three columns are registered
>   as person-name surfaces for the search; the scrubber now loads all three, and the granted
>   erasure (REQ-SEC-062) matches on all three for the same reason.
> - **A first-character index narrower than the comparison** (added 2026-09-17). The matcher
>   buckets terms by their first character to avoid testing every handle at every position, and
>   `regionMatches(true, …)` accepts pairs a single case folding does not: `K` (U+212A) matches
>   `k`, `ı` matches `I`, `İ` matches `i`, `ς` matches `σ`, `ẞ` matches `ß`. The index
>   now folds **both** sides, because folding one is not symmetric: a handle `Kelvin` sits under
>   `K` and `k`, and a note written with the Kelvin sign looked up a character no bucket held.
>   Both directions are pinned, and the fix is verified by mutation rather than by observing a
>   green test.

None of that closes the gap between what a rule can do and what the article asks for.

> [!warning] The residue is real and is covered by a human, not by code
> The scrubber cannot recognise somebody who has no account, a nickname or a misspelling — nothing
> can, from text alone. That is why Art. 15(4) and
> [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md) require an admin to
> **read** the free text before releasing an export, and why the export reports whether it removed
> anything: it tells the reviewer whether there is something to look for. The code makes that review
> short; it does not replace it.

**The subject's own handle is never scrubbed.** It is the one name the export is about, and removing
it from the entries they wrote themselves would be absurd.

**Out of scope, named in the document itself** so the export and the privacy record agree: platform
logs, metrics and traces (not retrievable per person by design; logs are kept 31 days, traces 14 and
metrics 180 — the document itself names the log and trace figures); backups
(not searched or altered — the data ceases to exist when the backup expires); and Keycloak's own
account record (visible in the account console).

**Bounded**: 5000 rows per section, and a truncated section is marked as truncated.

**Both paths are audited** (`PERSONAL_DATA_EXPORTED`) with `bySelf` distinguishing them — a member
reading their own record is unremarkable, an admin reading somebody else's is what an audit exists to
make answerable. The payload carries the format and the row count, never the content. The **subject's
id, not their handle**, appears in the log line and in the PDF filename: a filename reaches shells,
logs and mail clients, and a name there is a leak nobody chose.

**The admin export is not a fuller export.** Same projections, same anonymisation. A third party's
data is no more disclosable to an admin serving somebody's Art. 15 request than to the member.

**Acceptance**

- [x] A member exports their own data as JSON and as PDF; no endpoint on the self-service path
  accepts a user id.
- [x] **No other member's handle appears anywhere in an export** — asserted across the whole
  document, not per section.
- [x] **No audit `subject_label` appears in an export**, asserted both as data (a seeded row whose
  label names an *unregistered* contact, which no scrubber could catch) and structurally (no
  section's SQL contains the column, so a future section cannot select it back in).
- [x] The member's own free text survives with the other name replaced, rather than being dropped.
- [x] The subject's own handle is not scrubbed from their own entries.
- [x] Every section runs against the real schema, and every section carries a legal-basis marker.
- [x] The export reports whether third-party names were removed.
- [x] The admin variant is ADMIN-only; a member, an officer and bank management are refused.
- [x] Both paths write `PERSONAL_DATA_EXPORTED`, with `bySelf` telling them apart.

**Enforced by:** `DataExportIntegrationTest`, `HandleScrubberTest`,
`DataExportControllerSecurityTest` · **Code:** `support/DataExportSections`,
`support/HandleScrubber`, `service/DataExportService`, `service/DataExportReportService`,
`service/pdf/DataExportPdfFormat`, `controller/DataExportController`,
`controller/AdminDataExportController`, frontend `controller/DataExportProxyController`,
`templates/profile.html` (`#profile-export-card` — the member's export buttons are plain `GET`
links, beside the separate deletion card) · **Decision:**
[ADR-0185](../adr/0185-the-data-export-excludes-third-parties-by-projection.md) ·
**Record:** [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

### REQ-SEC-059 — A half-finished account deletion is observable

Deleting a member is two acts, and the application MUST notice when only the first has happened.
An admin removes the account in the Keycloak console; the nightly roster sync then flips
`app_user.in_keycloak` to `false`; and only then does the member list render its delete action
(`members.html` gates on `!user.inKeycloak`, and `UserDeletionService` refuses an account the flag
still claims is present). The second act is therefore **not offered until the first is done, and not
performed unless somebody comes back for it.**

When nobody comes back, the row keeps the e-mail address, the display name / handle, the Discord
snowflake, the guild nickname and the free-text profile description of a person who has already
left, indefinitely — and until now nothing in the system said so. The data is not *exposed* by this
(the account cannot sign in), it is simply *retained with no basis*, which is a retention defect
rather than an access one, and the reason this is a hygiene signal rather than an incident.

**Two gauges, sampled by `BusinessMetricsCollector` (REQ-OBS-011):**

|                        Gauge                         |                                             Reads                                             |
|------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `basetool_users_pending_deletion_count`              | `COUNT(*) WHERE in_keycloak = false`, **excluding the configured gateways' service accounts** |
| `basetool_users_pending_deletion_oldest_age_seconds` | `now() - MIN(keycloak_absent_since)`, same exclusion                                          |

> [!important] The exclusion is not tidying — without it the alert fires on day one, forever
> Corrected 2026-09-16. An unfiltered `GET /users` **omits service accounts**, so the roster sync
> never reports one and `markMissingUsers` flags it; nothing can ever clear the flag, because
> `syncUser` only runs for a user the roster reports. Production holds exactly such a row — the
> ingest gateway's service account, created when the gateway's first call ran the registration flow
> on itself before the machine-identity carve-out existed (ADR-0129). Counted, the gauge was
> permanently non-zero, V241 backfilled its age with the deploy timestamp, and
> `UserDeletionUnfinished` fired at T+7d and never resolved — telling an admin to finish a deletion
> for a machine row that holds no e-mail address, no handle and no description at all.
>
> The exclusion matches the username convention `service-account-%`, lower-cased and
> **unconditionally**. That is a display convention rather than a reserved namespace and must never
> be the basis of a security decision — `UserDeletionService` still asks Keycloak which user backs
> a configured client before it waives its delete guard. For a gauge it is proportionate: excluding
> a hand-made lookalike from a monitoring count is a nuisance, not a hole, and the alternative is a
> Keycloak round trip on every metrics tick.
>
>> [!bug] Corrected 2026-09-17 — the exclusion was empty exactly when the row exists
>> It first excluded `service-account-<clientId>` for the **configured** gateway clients. That
>> list defaults empty, and the machine-identity carve-out in
>> `CustomJwtGrantedAuthoritiesConverter` is gated on the same property — so with the property
>> unset the carve-out does not fire, the gateway's first call runs the registration flow on
>> itself and provisions the row, and the exclusion is empty. It could only ever protect a
>> deployment that, by having the property set, would never have created the row: it fixed the
>> legacy production row and was structurally unable to fix a fresh occurrence.
>>
>> The comparison was also case-sensitive, against the convention this repository states twenty
>> lines away in the same file — "Keycloak treats usernames that way".

**The age needs a column, and V241 adds it.** No existing timestamp carries "when did this account
stop being present": `created_at` is when the account was created — for a member who joined in April
and left in September it overstates the wait by five months — and `updated_at` is `@UpdateTimestamp`,
which a **bulk JPQL update** does not even write (Hibernate skips the entity lifecycle), besides
moving on every unrelated profile edit. So `app_user.keycloak_absent_since` is assigned explicitly by
`UserRepository#markMissingUsers` and cleared by `UserReconciliationService` when the account
reappears — an account that comes back is not waiting for deletion, and a stamp left behind would
alert forever.

> [!note] `in_keycloak` is now derivable from the stamp, and stays anyway
> Observed in review, 2026-09-16. The two are written and cleared together, so
> `keycloak_absent_since IS NOT NULL` and `in_keycloak = false` mean the same thing, and the boolean
> carries no information the timestamp does not. It stays because it is the column a dozen queries
> and the member list's render gate already read, and because the pair fails **safe**: a row with
> the flag and no stamp still hides the delete action and still counts, it simply reports no age.
> Collapsing them would be a schema change for tidiness, on a column whose whole purpose is that
> existing code reads it the same way it always did.

**It is a first-observation stamp, not a last-seen one.** The update's existing `in_keycloak = true`
predicate restricts it to rows that actually flip, so a row already flagged is never rewritten.
Without that the value would be refreshed every nightly run and the age would report the sync's
cadence — never older than a day — instead of how long the account has been waiting.

**Rows flagged before V241 are backfilled with the migration's own timestamp.** Nothing recorded when
they disappeared, which is the defect being fixed, so there is no value to recover; the deploy time
is the only honest stand-in and is documented as a lower bound in the migration and in the alert's
comment.

**The alert is on age, not on count** (`UserDeletionUnfinished`, > 7 days, `for: 30m`). A count above
zero held for seven days would also fire on a stream of accounts each cleared within a day, because
the count never reaches zero in between. Seven days rather than 48 hours because the wait is
legitimate while an admin is mid-task and the roster sync is nightly.

**Acceptance**

- [x] An account removed from Keycloak is counted by `basetool_users_pending_deletion_count` after
  the next roster sync, and stops being counted when its local row is deleted.
- [x] The absence stamp is written by the sweep that flips the flag, and a later sweep does not move
  it forward.
- [x] An account that reappears in Keycloak has both the flag and the stamp cleared, so it leaves
  both gauges.
- [x] The age gauge reports the oldest waiting account, not the newest.
- [x] `UserDeletionUnfinished` fires past seven days and not on a count that merely stays non-zero.

**Enforced by:** `OrphanedAccountRepositoryIntegrationTest`, `UserReconciliationServiceTest`,
`BusinessMetricsCollectorTest` · **Code:** `model/User#keycloakAbsentSince`,
`repository/UserRepository#markMissingUsers` / `#countOrphanedMemberAccounts` /
`#findOldestOrphanedMemberAbsenceStamp`, `service/UserReconciliationService#syncUser`,
`task/BusinessMetricsCollector`, `metrics/MetricNames#USERS_PENDING_DELETION`,
`db/migration/V241`, `monitoring/prometheus/alerts/business.yml`,
`monitoring/grafana/dashboards/07-basetool-operations.json` · **Decision:**
[ADR-0182](../adr/0182-an-unfinished-account-deletion-is-measured-from-a-recorded-absence.md) ·
**Record:** [`docs/privacy/processing-activities.md`](../privacy/processing-activities.md)

### REQ-SEC-060 — Admin Personensuche across every free-text surface

An admin MUST be able to find **every** place a given name appears, case-insensitively, across every
free-text surface of the application.

**Why.** A name can sit where no foreign key points: an external mission participant
(`mission_participant.guest_name`), a party lead with no account, a job-order handover recipient, an
org-chart placeholder, a booking reason, an admin's note on a refused registration. Before this, an
Art. 16 rectification or Art. 17 erasure request from such a person **could not be served at all** —
nothing could find the entries. And for a member it was no better than partial: a rectification that
fixes one of four occurrences is not a rectification.
[`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md) therefore tells the
reader to run this for **every** Art. 16/17 request, members included.

**ADMIN only**, and the reason is not only cost: a query returning every place a name appears is a
profile of that person assembled across the whole system.

**Case-insensitive, always.** Whoever typed the name was not copying it from a roster, so a
case-sensitive match would miss the very entries the search exists to find. `ILIKE`, with the
term's `%`, `_` and `\` escaped — an unescaped pasted `%` would match every row of every searched
column, an accident indistinguishable from a deliberate dump. It holds in **both** directions —
the casing the admin types and the casing the row happens to hold are independent — and
`PersonSearchIntegrationTest` seeds the same name in three casings to prove it, because a
case-sensitive match would answer "no further mentions" and nothing downstream would reveal that
the answer was wrong.

**A written registry, checked against the schema.** `PersonSearchTargets.TARGETS` names each
searched `(area, table, column, idColumn, linkKind)`. It is a list rather than a reflection sweep
because "every column of type text" would include ~200 columns of catalogue data synced from UEX and
the SC wiki — planet names, manufacturer nicknames, brochure URLs — which cannot name a member in
any sense that matters and would bury the real hits.

> [!important] The registry is gate-enforced, and that is what makes the claim true
> `PersonSearchCoverageTest` sweeps `information_schema` for every text column of every base table
> and **fails the build** unless each one is either searched or recorded in `EXEMPT_COLUMNS` /
> `EXEMPT_TABLES` with a reason. So a new free-text column cannot be added silently — its author
> has to decide which it is. Without that gate, the instruction in the privacy record would go
> quietly false the first time somebody added a notes field, and the person whose data it is would
> have no way to know. The test also asserts that every registered column and id column still
> exists, so a rename fails here rather than during a real request.

**Bounded by construction**, because a multi-table `ILIKE` sweep with no ceiling is a
denial-of-service waiting for a one-character term: at least 3 characters, 25 hits per column, 300
in total, and **the response says which of the two caps it reached**. A capped list that looked
complete would make an erasure look complete when it is not, so the page states it as a warning
rather than a footnote — and for the per-column cap it **names the columns**, because the two need
different remedies: the overall cap means the term is too broad, a per-column cap means one area has
more than the list can show.

> [!warning] Corrected 2026-09-17 — the per-column cap was silent
> Each `UNION ALL` branch carried `LIMIT 25` with no `ORDER BY` inside it, and `truncated` compared
> the union total against 300 while the registry holds 75 targets. So a name occurring 40 times in
> one column yielded 25 hits, a total far below 300, and `truncated == false`: the page reported a
> complete list with 15 occurrences dropped. The overall cap — the one that almost never fires —
> was the only one being reported, and three documents including the privacy record claimed
> otherwise in as many words.
>
> Each branch now orders by its id column and asks for `PER_TARGET_LIMIT + 1` rows. The extra row
> is the probe: it is counted, it names the column as capped, and it is never shown. The ordering
> matters independently — without it, which 25 of 40 matches came back was unspecified, so two runs
> of the same search could return different rows.

**One statement.** A `UNION ALL` over the registry with a per-branch `LIMIT`: one plan, one round
trip, and no single column able to crowd out the rest. Table and column names cannot be bound as
parameters, so they are interpolated — and validated against `^[a-z_][a-z0-9_]{0,62}$` first, even
though they come from a compile-time constant, as defence against a future edit pasting something
else into the registry.

**The search is audit-logged, and the term is not.** A read in an otherwise mutation-only trail,
recorded because its misuse would leave no other trace. The payload carries the **length** of the
term, the hit count and whether the overall 300-hit cap truncated the result (`truncated`; the
per-column cap is not recorded) — never the term, which is somebody's name
(REQ-AUDIT-001 keeps user free text out of the payload). A trail recording every name an admin
searched for would be a second store of exactly the data the search exists to help remove. The term
is likewise kept out of every log line.

**Hits are listed, not edited.** Each carries its area, the `table.column` it came from, the matched
text clipped to 200 characters, and a link where the row has a page of its own. Rectification
happens on the record's own screen; an edit-in-place here would be a second write path to a dozen
aggregates.

**Acceptance**

- [x] A name typed into any registered free-text column is found, whatever the case.
- [x] A text column that is neither searched nor exempted fails the build.
- [x] A registered column or id column that no longer exists fails the build.
- [x] The assembled statement runs against the real schema (not only against mocks).
- [x] A term under 3 characters is refused; `%` and `_` in a term match literally.
- [x] A capped result is reported as capped.
- [x] The search is ADMIN-only; a member, an officer and bank management are refused.
- [x] The audit event records the term's length and never the term.

**Enforced by:** `PersonSearchCoverageTest`, `AdminPersonSearchControllerSecurityTest` · **Code:**
`support/PersonSearchTargets`, `service/PersonSearchService`,
`controller/AdminPersonSearchController`, `model/dto/PersonSearchHitDto`,
`frontend/controller/AdminPersonSearchPageController`, `templates/admin/person-search.html`,
`static/js/admin-person-search.js` · **Decision:**
[ADR-0184](../adr/0184-the-person-search-is-a-checked-registry-not-a-schema-sweep.md) ·
**Record:** [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

### REQ-SEC-061 — Self-service deletion is a request an admin decides

A member MUST be able to ask, in the application, for their account to be erased (Art. 17 GDPR).
The request lands in an admin queue; it is **never** carried out by the member's own click.

**Why a request and not a self-delete.** The deletion removes the Keycloak account, purges the
member's warehouse stock, hangar, personal inventory, blueprints, notifications and grades, and
reassigns their missions and refinery orders to an admin (REQ-DATA-008). None of that is
reversible. A control on one's own profile page that did all of it on one click would be the most
destructive button in the application, placed where a mis-click is cheapest.

**The lifecycle** — `deletion_request` (V242), modelled on the registration-approval queue rather
than a second pattern:

|    State    |        Reached by        |                                             Notes                                             |
|-------------|--------------------------|-----------------------------------------------------------------------------------------------|
| `PENDING`   | the member raises it     | at most one per member, a **partial unique index** on `(user_id)` where the status is pending |
| `WITHDRAWN` | the member takes it back | kept, not deleted — "asked and changed their mind" is a different fact from "never asked"     |
| `DECLINED`  | an admin refuses it      | `decision_note` is **mandatory**, DB-enforced                                                 |

**There is deliberately no executed state.** Carrying the request out deletes the `app_user` row,
and `deletion_request.user_id` is `ON DELETE CASCADE`, so the request goes with the account — which
is the point of an erasure. The record of the deletion is the audit trail
(`ACCOUNT_DELETION_REQUEST_EXECUTED` written before the delete, plus REQ-DATA-008's `USER_DELETED`),
not a surviving row about a member who asked to be forgotten.

**A refusal must carry a reason, in three places.** Art. 12(4) obliges the controller to tell the
requester *why* a request is refused, together with their right to complain to a supervisory
authority and their right to a judicial remedy. So the note is required by the client, by the
service, and by a database `CHECK` — and the member is notified and reads it on their own profile
page, which is why the member-facing `GET` returns their **latest** request rather than only a
pending one.

**Carrying it out does both halves.** The admin's click deletes the local row *and* the Keycloak
account: database half first, Keycloak last, the ordering of REQ-SEC-026 /
[ADR-0111](../adr/0111-admin-mediated-discord-registration-linking.md), with the live presence probe
waived exactly as `AccountConsolidationService` waives it (the caller removes the Keycloak user
itself). Leaving the second act to a human is the state REQ-SEC-059 exists to detect; this path does
not create it.

> [!warning] Corrected 2026-09-17 — a failing Keycloak delete is not self-resolving
> This paragraph used to say the failure "is logged and swallowed — the member's data is gone,
> which is what they asked for, and the leftover Keycloak account resurfaces as a fresh pending
> registration an admin can refuse". The first half is true; the second is not, in two ways.
>
> The recreated row is only PENDING for an ordinary member. `UserRegistrationService`'s
> `stampNewPendingRegistration` carves ADMIN-realm-role holders out for bootstrap safety, so an
> admin's row lands on the `ACTIVE` entity default with full authority and no approval step. And
> nothing watched the failure: the account has no local row left, so it cannot appear in the
> REQ-SEC-059 orphan gauge, which counts the opposite direction.
>
> Since 2026-09-17 the path bumps `basetool_account_deletion_keycloak_failures_total` and writes
> an `ACCOUNT_DELETION_KEYCLOAK_DELETE_FAILED` audit row in its own transaction (the business
> transaction has already committed), carrying the account id the Keycloak console needs.
> `AccountErasureKeycloakDeleteFailed` alerts on any occurrence. The remedy is manual and stays
> manual: delete that account in Keycloak.
>
> The carve-out itself stays — the first admin must never be lockable out by the
> approval gate — but it no longer fires silently: a brand-new row that is `ACTIVE` on
> arrival bumps `basetool_admin_registration_auto_activated_total` on whichever path inserts
> it, and `AdminAccountAutoActivated` alerts on any occurrence.

**The history checkbox is a wish, not an instruction** (REQ-SEC-062). The member may additionally
ask for the handle snapshots that survive a deletion to be anonymised. Nothing acts on that
automatically: the admin's own checkbox starts **unticked** even when the member asked, because
granting it is a deliberate act and a pre-ticked box would make the wish the default.

**Notifications** (V243): raising notifies every admin — Art. 12(3) allows one month to respond and
a queue nobody is told about is how that month passes; refusing notifies the member. There is no
rule for a carried-out request, because its recipient no longer exists.

**Observability** — `basetool_deletion_request_pending_count` and
`basetool_deletion_request_pending_oldest_age_seconds` (REQ-OBS-011), with
`DeletionRequestOverdue` at **14 days**: the one queue alert in this system whose threshold comes
from a statute rather than from operational taste.

**Acceptance**

- [x] A member raises a request from their profile; nothing is deleted, the request appears in the
  admin queue, and every admin is notified.
- [x] Raising twice yields one request, under a genuine race as well — the partial unique index
  decides, not a pre-read.
- [x] The member can withdraw a pending request; the row survives as `WITHDRAWN`.
- [x] A refusal without a reason is rejected by the client, the service and the database; a refused
  member is notified and can read the reason on their profile.
- [x] Carrying a request out deletes the local row and the Keycloak account, in that order, and
  anonymises the handle snapshots first **only** when the admin granted it.
- [x] Every state change is audit-logged, and no audit payload carries the member's free text.
- [x] The queue is ADMIN-only at the URL matcher and the method gate; a member, an officer and bank
  management are all refused.

**Enforced by:** `DeletionRequestServiceTest`, `DeletionRequestControllerSecurityTest`,
`BusinessMetricsCollectorTest` · **Code:** `model/DeletionRequest`,
`model/DeletionRequestStatus`, `repository/DeletionRequestRepository`,
`service/DeletionRequestService`, `controller/DeletionRequestController`,
`controller/AdminDeletionRequestController`, `event/AccountDeletionRequestedEvent`,
`event/AccountDeletionRequestDeclinedEvent`, `db/migration/V242`, `db/migration/V243`,
`frontend/controller/DeletionRequestProxyController`,
`frontend/controller/AdminDeletionRequestsPageController`,
`templates/fragments/profile-deletion-card.html`, `templates/admin/deletion-requests.html`,
`static/js/profile.js`, `static/js/admin-deletion-requests.js` · **Decision:**
[ADR-0181](../adr/0181-self-service-deletion-is-a-request-an-admin-executes.md) ·
**Record:** [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

### REQ-SEC-062 — A granted Art. 17 request anonymises the surviving handle snapshots

When an admin grants the member's wish, the member's handle MUST be replaced by a sentinel in
**every** place a handle snapshot survives an account deletion. Rows are **not** removed and no fact
about what happened is altered — only the name goes.

**Twelve columns in eight statements**, and the reason it is all of them: erasing eleven reads as a
completed erasure while still naming the person on the twelfth. `HandleAnonymisationService.ANONYMISED_COLUMNS`
is the authoritative list.

|                                   Where                                   |                         Matched by                          |
|---------------------------------------------------------------------------|-------------------------------------------------------------|
| `audit_event.actor_handle`                                                | `actor_user_id`                                             |
| `audit_event.subject_label`                                               | the whole text, case-insensitively                          |
| `bank_audit_event.actor_handle`                                           | `actor_user_id`                                             |
| `bank_transaction.counterparty_handle`                                    | `counterparty_user_id`                                      |
| `bank_booking_request` — requester, decider, counterparty, owner approver | the four id columns, in one statement                       |
| `bank_holder.handle`                                                      | `user_id`                                                   |
| `job_order.handle`                                                        | **the whole text, case-insensitively** — no id beside it    |
| `job_order_handover.recipient_handle`                                     | likewise                                                    |
| `job_order_item_handover.recipient_handle`                                | likewise                                                    |

**Every statement is a whole-value comparison or an id match — never a substring rewrite.** Three
substring `REPLACE` statements over `audit_event.details`, `bank_audit_event.details` and
`notification.params` were removed on 2026-09-17: they rewrote every row whose text contained the
needle, with no owner predicate, and the needle is the member's own self-service `display_name` — a
member who called themselves `aUEC` could have had an admin, acting through the intended workflow,
irreversibly rewrite the amount annotation on the whole financial trail. A name inside those
payloads is now an admin's manual step, found through the Personensuche (REQ-SEC-060) and classified
as such in `HandleErasureCoverage`.

**The text-matched columns have no user id at all**: a handover recipient or a job-order contact is
typed in by hand and may name somebody with no account. So the match is on the text and must ignore
case, because whoever typed it was not copying from a roster. That also makes them the only targets
reachable for an *already deleted* account — and it can over-match, since a handle is not a unique
key, which is why the admin reviews the Personensuche hits (REQ-SEC-060) before granting.

**Every spelling, not the effective name alone.** `getEffectiveName()` is `displayName ?: username`,
so each text-matched update runs once per stored spelling — username, display name and Discord guild
nickname. A handover typed with the member's nickname is as likely as one typed with their display
name, and the search registry already treats all three as places a person is named.

> [!important] The set was widened after review, and it is gate-enforced now
> Corrected 2026-09-16 (and again 2026-09-17, when the three payload columns named below were taken
> back out — see above). The set was documented as closed and was not: `bank_holder.handle` (the
> custodian registry, which becomes **more** visible after the account is gone, because its
> `user_id` is `ON DELETE SET NULL` and the display name falls back to the snapshot),
> `job_order.handle`, `notification.params` (one row per **administrator**, kept for up to the
> 180-day unread window) and both `details` payloads all survived a granted erasure. And
> `audit_event.subject_label` was worse than missed: the execution's own audit row put the member's
> effective name back into it six lines after `actor_handle` had been scrubbed, on the same row. All
> four deletion-request events did the same, so a granted erasure left rows literally
> half-anonymised for the full 24-month retention. They carry a `null` label now.
>
> `HandleErasureCoverage` classifies **every** column `PersonSearchTargets` registers as a place a
> person is named, and `HandleErasureCoverageTest` fails the build unless each one is anonymised
> here, removed with the account, structurally about somebody else, or recorded as an admin's manual
> step with a reason. The person search had that gate from the day it was written and never drifted;
> this set had a comment, and drifted before the branch merged.
>
> **The registry also makes the manual half visible.** Rather more than half of those columns are
> prose somebody else typed, where the name sits inside a sentence and no mechanical rule can
> rewrite it safely. That residue is why the Personensuche exists and why
> [`data-subject-requests.md`](../privacy/data-subject-requests.md) requires an admin to walk its
> hits — but it was implicit before, and an erasure whose manual half is implicit is one somebody
> will believe is complete.

One more thing the same review found in the same place, about the token rather than the columns.

> [!note] The sentinel's uniqueness is enforced, not assumed
> `HandleAnonymisation`'s comment claimed a real handle could not equal the token while
> `display_name` was self-service free text with only a length limit — so any member could set
> theirs to `#ANONYMISED#` and have it written into the trail as their own actor handle. Forging it
> was never an escalation (nothing branches on the value, every erasure update is matched by id or
> by the member's own spelling, and `actor_user_id` still attributes the row), but a comment
> asserting an invariant the code does not have is worse than no comment.
> `HandleAnonymisation.isReserved` is the check and `UserService` applies it to both write paths.

**A sentinel, not `NULL`.** Six of the columns are `NOT NULL` (both `actor_handle`s,
`bank_holder.handle`, `bank_booking_request.requester_handle` and the two handover recipients), and
that constraint is the
guarantee that a row always says who acted (REQ-AUDIT-001). Relaxing it to make room for an erasure
would weaken the invariant for every row ever written afterwards. The stored value is
`#ANONYMISED#` — deliberately not a word in any language, because it is rendered in two and the
i18n rule admits no hardcoded user-visible text; every human-facing surface maps it to
`general.anonymisedHandle`, while machine-readable exports keep the raw token so two exports of the
same rows stay comparable.

**It leaves a receipt.** A `HANDLE_SNAPSHOTS_ANONYMISED` marker is written to **both** trails, after
the updates so it is not scrubbed by them, carrying the per-table row counts and the number of spellings matched, and **never** the
handle that was removed — writing the value back would undo the erasure in the very row that records
it.

**One transaction.** A partial anonymisation is the one outcome that must not be possible.

> [!note] Why this is not a contradiction of REQ-AUDIT-006
> The retention sweep deliberately **deletes** rather than anonymises, and
> [ADR-0179](../adr/0179-both-audit-trails-are-swept-on-a-retention-ceiling.md) rejected
> anonymisation for it. That was a decision about *every row on a schedule*: a blanket scrub turns
> each old row into the "action by nobody" the snapshot exists to prevent. This is a *targeted act
> on request*, where removing the name is exactly what was asked for and the alternative is
> deleting rows a counterparty still needs.

**Acceptance**

- [x] All twelve columns are reached in one act, and the totals are reported.
- [x] The marker events are written after the updates, and their payload does not contain the
  erased handle.
- [x] The four text-matched updates (`subject_label`, `job_order.handle`, both handover recipients)
  are skipped, not matched against an empty string, when no handle is known.
- [x] The match on the handover columns is case-insensitive.
- [x] Nothing is deleted: row counts, timestamps, event types, amounts and subjects are unchanged.

**Enforced by:** `HandleAnonymisationServiceTest`, `HandleErasureCoverageTest` · **Code:**
`service/HandleAnonymisationService`, `support/HandleAnonymisation`, `support/HandleErasureCoverage`,
`repository/AuditEventRepository#anonymiseActorHandle`,
`repository/BankAuditEventRepository#anonymiseActorHandle`,
`repository/BankTransactionRepository#anonymiseCounterpartyHandle`,
`repository/BankBookingRequestRepository#anonymiseHandles`,
`repository/JobOrderHandoverRepository#anonymiseRecipientHandle`,
`repository/JobOrderItemHandoverRepository#anonymiseRecipientHandle`,
`frontend/support/HandleDisplay` · **Decision:**
[ADR-0183](../adr/0183-a-granted-erasure-anonymises-the-handle-snapshots-in-place.md)

### REQ-SEC-064 — Every response carries the security-header policy of its module

*Added 2026-09-22 by the documentation audit: the control existed in code and in ADR-0093, but no
requirement stated it.*

Each module emits a fixed set of security response headers from its Spring Security chain, and the
set is shaped by what the module serves.

- **Frontend (HTML)** — `SecurityHeaders.frontend(issuerUri)`: a per-request
  `Content-Security-Policy` whose `script-src` is `'nonce-…' 'strict-dynamic'` and whose `style-src`
  is `'self' 'nonce-…'`, with `style-src-attr 'none'` (no inline `style=""` attributes, ADR-0093 /
  REQ-UI in [`ui-design-system.md`](ui-design-system.md)), `object-src 'none'`, `base-uri 'self'`,
  `frame-ancestors 'none'` and `form-action 'self'` plus the Keycloak issuer's origin (which since
  ADR-0166 is normally the app's own); the nonce is minted per request from `SecureRandom` by
  `CspNonceFilter`. Beside it: `X-Frame-Options: DENY`, `Referrer-Policy:
  strict-origin-when-cross-origin`, `Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy`
  `same-origin`, HSTS (one year, `includeSubDomains`, `preload`), a `Permissions-Policy` that denies
  every listed feature, and `X-Content-Type-Options: nosniff`.
- **Backend and ingest (JSON only)** — `Content-Security-Policy: default-src 'none';
  frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, because neither serves a document,
  plus `X-Frame-Options: DENY` and HSTS; the backend additionally sends the frontend's
  referrer, cross-origin, permissions and `nosniff` headers.
- **Edge** — every public vhost adds `Strict-Transport-Security: max-age=63072000;
  includeSubDomains; preload` with `always` (`$hsts_header` in `docker/edge/conf.d/00-maps.conf`),
  so an error page the edge produces itself carries it too.

A new inline script or style in a template MUST carry the request nonce rather than widening the
policy; `'unsafe-inline'` and `'unsafe-eval'` are never added. Widening any directive is a change to
this requirement first.

**Acceptance**

- [x] A frontend page carries the nonce-gated CSP with `style-src-attr 'none'` and the Keycloak
  `form-action` origin, and every static header above.
- [x] An API response carries the `default-src 'none'` policy and the static headers.
- [x] The public edge sends HSTS on its first response; its absence raises `EdgeHstsHeaderMissing`.

**Enforced by:** `SecurityHeadersTest` (frontend and backend), ingest `SecurityConfigTest`, the
`blackbox-hsts*` probes behind `EdgeHstsHeaderMissing` · **Code:** `frontend/…/config/SecurityHeaders`,
`frontend/…/config/CspNonceFilter`, the `headers(...)` blocks of the backend and ingest
`SecurityConfig`, `docker/edge/conf.d/00-maps.conf` · **ADR:**
[ADR-0093](../adr/0093-eliminate-inline-style-attributes-csp-style-src-attr-none.md)

### REQ-SEC-066 — The Keycloak login form works with password managers, and "remember me" is opt-in

The credential form of the `krt-theme` login theme (`keycloak-theme/krt-theme/login/login.ftl`) MUST
keep the contract of the Keycloak base template it overrides:

- **Autocomplete tokens.** The username field carries `autocomplete="username"` and the password
  field `autocomplete="current-password"`. Both used to carry `autocomplete="off"`, which stops a
  password manager from filling and saving the credential and so pushes members towards short,
  memorable, reused passwords — the opposite of what the form is for. (The update-password form
  already used `new-password`, and the OTP form `one-time-code`.)
- **A known username is shown read-only.** When Keycloak already knows who is signing in
  (`usernameHidden` — a re-authentication or an identity-first step), the name is rendered
  `readonly` and the password field takes the focus, as the base theme does. Keycloak 26.7 no
  longer sets the older `usernameEditDisabled` flag (verified against `FreeMarkerLoginFormsProvider`
  on 2026-09-22), so `usernameHidden` is the one to honour.
- **The selected credential travels.** The hidden `credentialId` input the base template carries
  (`auth.selectedCredential`) is present, so a flow that offers a choice of credentials does not lose
  the member's pick on this page.
- **"Angemeldet bleiben" is not pre-ticked** (owner decision 2026-09-22). It is checked only when the
  member ticked it on a previous attempt of the same login (`login.rememberMe`), exactly as the base
  theme does. A pre-ticked box made the long remember-me session (30 days idle, 180 days max in the
  realm) the default for every login, including one on a shared or borrowed machine.
- **No inline event handler.** The three krt-theme forms carry no `onsubmit` attribute; the
  double-submit guard it held is not worth inline script on the page that handles a password.

**Acceptance**

- [x] `login.ftl`: `autocomplete="username"` / `"current-password"`, the hidden `credentialId`
  input, `readonly` username under `usernameHidden`, and `rememberMe` checked only via
  `login.rememberMe`.
- [x] No krt-theme login template carries an inline `onsubmit` handler.
- [ ] A real login in a browser with a password manager offers to save and later fills the
  credential. _(manual; no theme test harness exists.)_

**Enforced by:** review of the templates (the theme has no automated test harness; the Keycloak base
template is the reference) · **Code:** `keycloak-theme/krt-theme/login/login.ftl`,
`login-otp.ftl`, `login-update-password.ftl` · **Related:** THEME-SEC-01 / THEME-SIMP-01 of the
2026-09 improvement audit

### REQ-SEC-068 — Each service reaches Redis as its own least-privilege ACL user

Redis holds the frontend's sessions — OAuth2 access **and refresh** tokens included — the live-sync
and notification fan-out, and the ingest handoff. Backend, frontend and ingest used to reach it as
one user, `default` (`~* &* +@all`), with one shared password, so a compromise of any one of them —
the internet-facing ingest gateway above all — was a compromise of every member's session. The
2026-07-10 incident (a `default` user left `nopass`) showed how quietly that user can go wrong
(APPSEC-04, improvement audit 2026-09-22).

**The rule:** every service authenticates as its own ACL user, and each user can reach exactly what
its service does:

| User | Keys | Channels | Commands beyond `@connection` |
| --- | --- | --- | --- |
| `basetool-frontend` | `basetool:session:*`, `ingest:handoff:*` | `basetool:session:*`, the created-event pattern, `__keyevent@0__:del` / `:expired`, `basetool:livesync:changed` / `:presence` | read, write, keyspace, hash, set, sorted set, string, pub/sub, transaction, `INFO`; no dangerous command, no `CONFIG` |
| `basetool-backend` | none | `basetool:livesync:changed`, `basetool:notify:published` | `PUBLISH`, `SUBSCRIBE`, `UNSUBSCRIBE`, `INFO` |
| `basetool-ingest` | `ingest:*` | none | `SET`/`SETEX`/`PSETEX`, `RPUSH`, `LPOP`, `EXPIRE`/`PEXPIRE`, `DEL`/`UNLINK`, `INFO` |
| `monitoring` | none | none | introspection; **not** `SCAN` or `RANDOMKEY`, which are not key-checked and would list every session id |
| `admin` | all | all | all — the operator's, never in an application's environment |
| `default` | — | — | switched **off** at the end of the rollout |

- **The rules are code.** `scripts/redis-users.acl.tmpl` is the single source; `render-redis-acl.py`
  renders it on the host with SHA-256 hashes (never a clear-text password) and refuses a partial
  render or a file without exactly one `default` line. The Testcontainers suites and the E2E stack
  load the same template.
- **Merging changes nothing for the applications.** A service uses its own user only when its
  `REDIS_<SVC>_USERNAME` is set; empty, it sends a password-only `AUTH` with the shared
  `REDIS_PASSWORD`, which is `default`, exactly as before.
- **Nothing depends on the ACL state that must not.** The server carries `--notify-keyspace-events
  Egx`, and the health probe is an unauthenticated `PING` accepting `NOAUTH`.
- **A correctly configured service sends no command its user is refused — at startup included.**
  The frontend picks Spring Session's startup step from `spring.data.redis.username`: with none (or
  `default`) it runs the `CONFIG GET` / `CONFIG SET` of before (`TolerantKeyspaceNotificationsAction`,
  which still only logs a `NOPERM`); with its own user it sends a `PING` and **no `CONFIG`**
  (`ServerConfiguredKeyspaceNotificationsAction`). Either way an unreachable store or a refused
  credential fails the start (ADR-0084), and with
  `app.session.configure-keyspace-notifications=false` (the image build's AOT run) nothing is sent.
  So a refusal counted by `RedisAclDenials` is always a finding, never a restart. *(Added
  2026-09-25: until then the frontend's `CONFIG GET` was refused on every start under its own user,
  which production's `ACL LOG` showed as `config|get` for `basetool-frontend` after the rollout.)*
- A new key prefix or channel is a template change, re-rendered and `ACL LOAD`ed on the host.

**Acceptance**

- [ ] Under the template with `default` off, the frontend's real Spring Session repository stores,
  indexes, renames and deletes a session and receives its created and deleted events; live sync
  publishes and receives on both channels; a handoff is consumed; `SCAN` and `INFO` answer.
- [ ] The backend's notification fan-out and live-sync channel work under its user; the gateway's
  real staging, cap eviction included, works under its user.
- [ ] The frontend's startup step under its own user leaves `acl_access_denied_cmd` unchanged and
  sends no `CONFIG`; under `default` it still sends `CONFIG GET`; switched off it sends nothing; a
  wrong password still fails it.
- [ ] `ACL DRYRUN` refuses, per user, every foreign key, foreign channel, `CONFIG`, `KEYS`,
  `FLUSHALL`/`FLUSHDB`, `SCAN` for backend, ingest and monitoring, and `ACL` for every non-admin user.
- [ ] A password-only `AUTH` works while `default` is on and fails once it is off.
- [ ] The committed E2E ACL equals the template rendered with the E2E passwords, and the E2E stack
  runs every application on its own user with `default` off.
- [ ] Refusals are alerted on (`RedisAclDenials`).
- [x] Production runs every application on its own user with `default` off. _(2026-09-25, rollout
  steps 2–5 at ~15:47–15:51 UTC, owner-approved: six users; backend and frontend connected as their
  own users, ingest connects on demand; `REDIS_DEFAULT_USER=off` — an unauthenticated `PING` gets
  `NOAUTH`, a password-only `AUTH` gets `WRONGPASS … user is disabled`, the health check is
  healthy, `redis_up 1`.)_

**Known and expected:** the frontend's `TolerantKeyspaceNotificationsAction` still issues `CONFIG
GET` at every start, and the ACL refuses it — two `ACL LOG` entries per start (`reason=command`,
`config|get`, user `basetool-frontend`), counted in `redis_acl_access_denied_cmd_total`.
`RedisAclDenials` did not fire for it on 2026-09-25. A release rollback to 1.10.0 or older now
needs `REDIS_DEFAULT_USER=on` first.

**Enforced by:** `RedisAclFrontendIntegrationTest` (real Spring Session, live sync, handoff, the
`ACL DRYRUN` matrix for all users, the committed E2E ACL, the startup step's refusal count),
`RedisSessionConfigTest`, `ServerConfiguredKeyspaceNotificationsActionTest`,
`RedisAclBackendIntegrationTest`,
`RedisAclIngestIntegrationTest`, `render-redis-acl.py --selftest` (`repo-lint.yml`), the E2E suite
(`docker-compose.e2e.yml`, `E2eStackExtension`, `IngestHandoffE2eTest`) ·
`monitoring/prometheus/tests/redis_acl_denials_test.yml` · **Code:** `scripts/redis-users.acl.tmpl`,
`scripts/render-redis-acl.py`, `RedisSessionConfig.selectConfigureRedisAction`,
`ServerConfiguredKeyspaceNotificationsAction`, `TolerantKeyspaceNotificationsAction`, the three apps'
`spring.data.redis.username` · **Monitoring:** `RedisAclDenials` · **Runbook:**
[`deployment.md` → *The Redis ACL*](../deployment.md#the-redis-acl) · **ADR:** [ADR-0207](../adr/0207-each-service-reaches-redis-as-its-own-acl-user.md) ·
**Related:** REQ-SEC-025, REQ-OPS-018

### REQ-SEC-069 — The frontend is a confidential OAuth2 client whenever it holds a secret

The frontend is a server-side application and can keep a secret, so a captured authorization code
must not be redeemable without one (ADR-0001, audit findings M-6 and APPSEC-07). PKCE stays on: the
pattern is **PKCE + client secret**, never one instead of the other.

- **The secret decides the client type.** With `KEYCLOAK_FRONTEND_CLIENT_SECRET` set, the `keycloak`
  registration authenticates with `client_secret_basic`; without it (unset or blank) with `none`.
  Nothing else configures the method, so the two cannot disagree.
- **PKCE in both modes** (`ClientSettings.requireProofKey`), because Keycloak requires `S256` on
  `basetool-frontend`.
- **The switch signs nobody out.** A stored authorized client is refreshed with the registration the
  frontend runs with **now**, not the one it was stored under.
- **The secret never reaches the session store.** The stored authorized client carries an empty
  secret; the current one is put back on read.
- **The realm changes only on purpose.** The provisioner converges `basetool-frontend`'s client type
  only when `--frontend-client` names it, and when it switches the client to confidential it sets
  Keycloak's secret to the operator's value in the same update, never printing it.

**Acceptance**

- [ ] With a secret, the registration is `client_secret_basic` with that secret and PKCE required;
  without one, or with a blank one, it is `none` with PKCE required.
- [ ] A client stored under the public registration loads with the current confidential one, keeping
  its principal and tokens.
- [ ] The serialized session attribute holding the authorized client does not contain the secret.
- [ ] A provisioner run without `--frontend-client` changes neither type; `confidential` without the
  variable is refused and writes nothing; `confidential` with it switches once and never rewrites the
  secret; `public` switches back without sending one.
- [ ] Every E2E login goes through the confidential client.
- [x] Production rollout step 1: the frontend holds the secret. _(2026-09-25, owner-approved:
  `KEYCLOAK_FRONTEND_CLIENT_SECRET` generated on the host, frontend restarted, log `OAuth2 client
  'keycloak' is CONFIDENTIAL`.)_
- [x] Production rollout step 2: Keycloak's `basetool-frontend` is confidential with the same
  secret. _(2026-09-25 16:15 UTC, owner-approved: the provisioner's `--frontend-client confidential`
  dry run planned only `publicClient: true -> false` and the secret, `--apply` succeeded and a
  second run planned nothing; no `invalid_client` since and a fresh login works —
  [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](../OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md).)_

**Enforced by:** `FrontendClientAuthenticationConfigTest` (Boot's real OAuth2 client
auto-configuration, both modes, blank secret) · `CurrentRegistrationAuthorizedClientRepositoryTest`
(the swap, the stripped secret, the serialized attribute) · `scripts/provision-keycloak-realm.test.sh`
cases 9–12 · the E2E suite (`realm-export.e2e.json`, `E2eStackExtension`, `BackendSeeder`) · **Code:**
`FrontendClientAuthenticationConfig`, `CurrentRegistrationAuthorizedClientRepository`,
`RedisSessionConfig#authorizedClientRepository`, `scripts/provision-keycloak-realm.py` ·
**Monitoring:** `FrontendLoginBroken` (`basetool_login_total{reason="provider_error"}` —
`invalid_client` at the token endpoint lands there) and `KeycloakLoginErrorSpike` catch a secret
mismatch · **Runbook:** [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](../OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md)
· **ADR:** [ADR-0001](../adr/0001-frontend-confidential-oauth2-client.md),
[ADR-0202](../adr/0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md)
amendment 2 · **Related:** REQ-SEC-001, REQ-SEC-012

### REQ-SEC-070 — Each internal service holds its own certificate, and clients trust only the CA

No internal service may hold another service's private key, and no internal client may trust a
certificate merely for being pinned (audit finding ING-SEC-04). The target shape, which the
production rollout reaches step by step:

- **One leaf per service** — backend, frontend, ingest, Keycloak — signed by a private CA and naming
  only that service's docker alias plus `localhost` / `127.0.0.1`. Minted by
  `scripts/mint-internal-tls.sh`, which **destroys the CA key** at the end of the run: nothing can be
  added to the set afterwards, and a rotation re-mints the whole set.
- **Clients pin the CA, not a certificate.** Every internal SSL bundle (`backend-trust`,
  `keycloak-trust`) reads `INTERNAL_TLS_TRUSTSTORE`, a CA-only truststore mounted at
  `/run/secrets/internal-truststore.p12`; the edge, Prometheus and the blackbox exporter trust the CA
  through `basetool-ca.crt`.
- **Clients check the name.** With `INTERNAL_TLS_VERIFY_HOSTNAME=true` the frontend's backend client
  (`WebClientConfig`), its backend readiness probe (`BackendHealthIndicator`) and the ingest relay
  (`RestClientConfig`) verify the backend's hostname, which ADR-0204 §6 had switched off for the
  relay. The backend's Keycloak client, the edge, Prometheus and the blackbox exporter verified
  already. `dev` and `test` are unaffected.
- **Shipped in two releases.** The first was inert: every per-service mount fell back to the shared
  keystore and `INTERNAL_TLS_VERIFY_HOSTNAME` defaulted to `false`. The second — rollout step 3 —
  bakes the Quadlet units to `/var/iri/secrets/tls/<service>.p12` and the CA-only truststore and
  defaults the switch to `true`; it may only be promoted to a host where step 2 has run (runbook
  below). Compose stacks keep the fallbacks.
- **The committed test material has the same shape** (ADR-0139 amendment 1).

**Acceptance**

- [ ] The mint produces the CA certificate, a CA-only truststore and one keystore per service, and
  leaves no CA key anywhere; each leaf verifies for each of its own names and for no other service's.
- [ ] With verification on, the relay and the frontend refuse a backend certificate that chains to
  the pinned anchor but does not name the host they dialled, and accept one that does; with it off
  (the default) both behave exactly as before; `dev` is unaffected either way.
- [ ] The frontend's backend readiness probe follows the same switch.
- [ ] With nothing configured, every Quadlet unit mounts the shared keystore at every new mount
  point; the deploy pre-flight refuses a release whose units mount any PKCS#12 the host lacks.
- [ ] The E2E stack serves each app on its own leaf, and the seeder reaches the backend through the
  CA-only truststore with the hostname checked.

**Production rollout (2026-09-25, owner-approved):** step 1 (`INTERNAL_TLS_VERIFY_HOSTNAME=true`,
all four services `Verification: OK` beforehand) ~15:40 UTC; step 2 (mint, the widened
truststore and `basetool-ca.crt` with two anchors) ~15:52 UTC, and with it the Keycloak SPI
truststore, which turned out never to have existed (REQ-SEC-022's production note). **Open:** step 3,
the release that flips `PATH_VARS` (#2036, merged the same day, not yet promoted), and step 4. Until step 3 every service still serves the
shared certificate.

**Enforced by:** `scripts/mint-internal-tls.test.sh` (`repo-lint.yml`) · `RestClientConfigTest`
(ingest) · `BackendHostnameVerificationTest` · `BackendHealthIndicatorHostnameTest` ·
`scripts/deploy.test.sh` (`scenario_podman_missing_per_service_keystore_refuses`) ·
`generate-quadlet.py --check` · the E2E suite (`docker-compose.e2e.yml`, `BackendSeeder`) ·
**Code:** `scripts/mint-internal-tls.sh`, `WebClientConfig`, `BackendHealthIndicator`,
`AppHttpProperties#verifyBackendHostname`, `RestClientConfig` (ingest),
`IngestProperties#verifyBackendHostname`, the `application-prod.yml` SSL bundles of all three apps,
`docker-compose.yml`, `scripts/generate-quadlet.py` (`PATH_VARS`), `scripts/deploy.sh`
(`keystore_mount_sources`), `scripts/backup.sh` · **Monitoring:** unchanged series — the blackbox
`https_internal` probes and the three app scrapes already verify each service's name against
`basetool-ca.crt`, and `iri-cert-expiry` reports the CA's expiry once that file is the CA ·
**Runbook:** [`deployment.md` &rarr; Internal TLS](../deployment.md#internal-tls-per-service-certificates-from-a-private-ca)
· **ADR:** [ADR-0211](../adr/0211-each-internal-service-holds-its-own-leaf-from-a-private-ca.md) · **Related:** REQ-SEC-014, REQ-OPS-016, REQ-OBS-008, REQ-INGEST-001

## Out of scope

OrgUnit scoping/visibility rules (see [`org-unit-tenancy.md`](org-unit-tenancy.md)); the
confidential-client migration decision (see ADR-0001); the Discord federation gates and the Keycloak
SPI (see [`discord-integration.md`](discord-integration.md)); the ingest gateway's client-identity
and acting-member rules (see [`desktop-ingest.md`](desktop-ingest.md), ADR-0129); the Actuator
management-port isolation (see [`observability.md`](observability.md) REQ-OBS-005, ADR-0090/0134).
