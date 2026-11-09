# ADR-0352 — One explicit bank access policy

- **Status:** Accepted
- **Date:** 2026-11-08
- **Deciders:** Repository owner (@greluc)
- **Related:** decided in [#3114](https://github.com/krt-profit/basetool/issues/3114) · ADR-0020
  (the org-unit seam) · ADR-0355 (roles in the database) · ADR-0354 (bank on the exchange) ·
  [`bank.md`](../specs/bank.md) · [`module-boundaries.md`](../specs/module-boundaries.md) ·
  REQ-XCH-009

## Context

The bank access rules were spread over `BankSecurityService` (staff gates), `OrgUnitBankAccessService`
(visibility, responsible holder, exemption, request capability, applicable limit, configuration
rights), `OrgUnitBankRequestService` (approval routing, KRT bands, foreign requests, approvers),
`BankBookingRequestService` (four-eyes, confirm, queue) and `BankBookingGuards` (the KRT direct-booking
ceiling). Every one of them read the caller implicitly through `AuthHelperService` and
`RequestScopeResolver`, so none could decide for a member who is not the request's principal. The
exchange's acting member carries no bank roles (REQ-XCH-009), so the bank exchange could not reuse
them.

## Decision

1. **One policy in `bank.api`.** `BankAccessPolicy` decides every bank access rule for an explicit
   `BankActor` and never reads the security context. Its interface is four queries returning
   decision objects:
   - `accessTo(actor, accountId, pin)` → `BankAccountAccess.Visible(rights, approvalLimit)` or
     `Hidden`; `Hidden` answers an unknown account and one without any right alike (REQ-BANK-066).
     The rights (`BankAccountRight`) cover the org-unit view, request, responsible holder, the
     three configuration rights and the four staff rights.
   - `viewableAccounts(actor, pin)` → the active accounts with `VIEW`.
   - `admit(actor, accountId, pin, type, amount)` → `Admitted(requiresApproval, applicableLimit,
     requiredApprover)`, `NOT_FOUND` or `NOT_REQUEST_CAPABLE`; a deposit is admitted on any account
     (REQ-BANK-042).
   - `staffRights(actor)` → `BankStaffRights`: see and book by grant and type, the queue's accounts,
     four-eyes, the approver classes and the holder view, read from the actor alone.
2. **The implementation holds every rule.** `bank.internal.BankAccessRules` (with the per-account
   `BankAccessJudgement`) implements the four queries and, for the web adapters of its package, the
   decisions on entities they already hold: the routing of a correction, the staff direct booking
   above `T1`, the approval of a request, the foreign-request scope, own-request ownership and the
   active context that orders the account list.
3. **The actor is read from the database.** `BankActors` builds the `BankActor` (member id, reachable
   role codes from `user_roles` with the role hierarchy, membership seats, their officer reach and
   the `bank_account_grant` rows); its implementation is `DatabaseBankActors`. `of(userId)` serves the
   web; `forExchange(userId)` drops the stored Admin role before the hierarchy, so an administrator
   acts on the exchange only through their bank roles, grants and memberships.
4. **The pin is a parameter.** The web passes the `X-Active-Org-Unit-Id` header as sent; the policy
   narrows the oversight view to it when it lies in the actor's reach, without the cascade. The
   exchange passes none.
5. **The web is a thin adapter.** `BankCaller` reads who calls (memoised per request);
   `BankSecurityService` (the `@PreAuthorize` bean), `OrgUnitBankAccessService` and the request and
   settings services build the actor and ask the policy. Parity of web and exchange holds by
   construction; the characterisation matrix (`BankAccessCharacterisationTest`) pins the web
   outcomes unchanged.
6. **Two rules guard it** (`BankAccessPolicyRulesTest`): the policy and every class it calls never
   reach `SecurityContextHolder`, `AuthHelperService` or `RequestScopeResolver`; no bank class outside
   the policy calls a role check of `AuthHelperService` or any `RequestScopeResolver` method but the
   pin read.

`BankRequestApprover` moves to `bank.api`, since `Admitted` names it.

## Consequences

- An access rule changes in one place, and the exchange slices reuse it unchanged.
- The web reads roles from `user_roles` rather than the token's authorities. Both carry the same
  roles since ADR-0355; a test that authenticates with authorities alone must store the roles.
- A direct drill-in of a closed account the actor may view still answers `Visible`, as the web did;
  `viewableAccounts` lists active accounts only.
- Building the actor costs four reads per request and member.

## Rejected

- **An actor built from the token's authorities.** The exchange principal has none (REQ-XCH-009),
  and two sources would let web and exchange drift.
- **About 25 single predicates on the interface.** Every caller would recombine them, and
  "hidden = unknown" would stay a convention of each caller instead of a property of the type.
