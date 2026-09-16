# ADR-0011 — Bank authorization: coarse Keycloak roles + app-managed per-account grants

- **Status:** Accepted (epic #556 delivered, 2026-06-13)
- **Date:** 2026-06-12
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-BANK-007..011 (`docs/specs/bank.md`) · REQ-SEC-002/005/006
  (`docs/specs/security-and-access.md`) · epic
  [#556](https://github.com/krt-profit/basetool/issues/556)

## Context

Bank staff need (a) a coarse "is bank personnel" signal, (b) fine-grained per-account
capabilities — view, deposit, withdraw, transfer — assignable per employee per account.
Bank membership is **fully independent of org-unit membership** (REQ-BANK-008): a bank
employee or manager may, but need not, also belong to a Staffel or Spezialkommando, so
bank authorization must not be derived from — or be influenced by — org-unit scoping.
Admins must be able to see and edit everything in the bank; the audit log must be
admin-only.

Existing machinery:

- Keycloak realm roles map to `ROLE_<UPPER_SNAKE>` authorities in both modules
  (`CustomJwtGrantedAuthoritiesConverter`, frontend `userAuthoritiesMapper` +
  `BackendRoleSyncFilter`); a static `RoleHierarchy` bean exists in both
  `SecurityConfig`s.
- Fine-grained, per-target capabilities already exist as **app-managed flag rows**:
  `org_unit_membership.is_logistician / is_mission_manager / is_lead`, promoted to flat
  and contextual authorities and checked by `@PreAuthorize` helper beans
  (`@ownerScopeService`, `@specialCommandSecurityService`).
- Putting per-account permissions into Keycloak would mean one realm role per
  (account × capability) — unmanageable, not auditable in-app, and invisible to the
  app's optimistic-locking/versioning conventions.

## Decision

We will split bank authorization into two layers:

1. **Coarse layer — two Keycloak realm roles** (names pending owner confirmation):
   `Bank Employee` → `ROLE_BANK_EMPLOYEE`, `Bank Management` → `ROLE_BANK_MANAGEMENT`,
   with hierarchy `ROLE_ADMIN > ROLE_BANK_MANAGEMENT > ROLE_BANK_EMPLOYEE` added to
   **both** `RoleHierarchy` beans. The roles gate the bank surface as a whole and are
   seeded in `DataInitializer` (codes `BANK_EMPLOYEE`, `BANK_MANAGEMENT`).
2. **Fine layer — `bank_account_grant` table** (user, account, `can_deposit`,
   `can_withdraw`, `can_transfer`, `@Version`, audit timestamps): row existence = view
   access; flags = booking capabilities. Checked by a dedicated **`BankSecurityService`**
   `@Service` bean (shape: `SpecialCommandSecurityService`) referenced from
   `@PreAuthorize` SpEL — e.g.
   `@PreAuthorize("hasRole('BANK_EMPLOYEE') and @bankSecurityService.canDeposit(#accountId, authentication)")`.
   `BANK_MANAGEMENT` and `ADMIN` short-circuit to allow inside the bean.
3. **Org-unit independence by construction:** `BankSecurityService` evaluates **only**
   the bank roles and the grant table. It never consults `OwnerScopeService` scoping,
   contextual `ROLE_X@orgUnitId` authorities or the `X-Active-Org-Unit-Id` admin pin —
   org-unit memberships have zero influence on bank decisions, in both directions
   (they neither grant nor block anything). A test pins this independence.
4. **Audit log is role-gated twice:** URL matcher `/api/v1/bank/admin/**` →
   `hasRole('ADMIN')` plus method-level `@PreAuthorize("hasRole('ADMIN')")` — bank
   management explicitly does **not** see it.

## Consequences

- **Easier:** account-level permissions are normal app data — versioned, validated,
  auditable, manageable from the bank UI without touching Keycloak; Keycloak holds only
  two stable roles, created once per environment.
- **Easier:** the pattern is the proven membership-flag pattern; ArchUnit rules extend
  naturally (`bankSecurityService` joins the accepted `@PreAuthorize` gate set).
- **Harder / accepted:** two sources must align (role in Keycloak, grants in app); a
  user with grants but no role sees nothing — by design, the role is the kill switch.
- **Easier:** no membership lookup on bank requests — gates read only authorities plus
  one grant row; org-unit data stays entirely out of the bank's decision path.
- Follow-up: `ROLES_AND_PERMISSIONS.md` gains the bank rows; the E2E realm export gains
  the two roles plus synthetic bank users (one of them also an org-unit member, to pin
  the independence).

## Alternatives considered

- **Per-account roles in Keycloak** — rejected: role explosion, no optimistic locking,
  no in-app audit, admin UX outside the tool.
- **Reusing `org_unit_membership` with a "BANK" org unit** — rejected: the bank is not
  a tenancy org unit; a pseudo-membership would pollute every membership-driven scope
  (orders, missions, promotion, picker stamping) and entangle bank access with the
  org-unit machinery that REQ-BANK-008 explicitly keeps out of the bank.
- **One `BANK` role + management flag in the app** — rejected: management visibility
  ("sees all accounts") is a coarse, org-level distinction exactly matching a realm
  role; modeling it as app data would weaken the kill-switch property.
- **A separation-of-duties rule (bank staff must hold no org-unit membership)** —
  considered and rejected by the owner: bank work is open to org-unit members and
  non-members alike; an eligibility predicate would add a lookup per request and an
  operational trap (joining a Staffel would silently kill bank access) for no required
  control.
