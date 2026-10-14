# ADR-0255 — Trade delegates are an in-app appointment

- **Status:** Accepted
- **Date:** 2026-10-14
- **Deciders:** @greluc
- **Related:** spec [`purchasing.md`](../specs/purchasing.md) (REQ-PUR-003) ·
  [ADR-0253](0253-material-supply-and-purchasing-are-two-modules.md) ·
  [ADR-0254](0254-a-bank-booking-request-may-carry-an-opaque-purpose.md) ·
  [`SUPPLY_AND_PURCHASING_PLAN.md`](../SUPPLY_AND_PURCHASING_PLAN.md) (E-01)

## Context

A Handelsbeauftragter (HB) may request money from the Kartellbank for a deal and trade with external
partners. The capability is personal, time-limited, carries a limit and is revoked as soon as trust ends.
The roles of the tool are Keycloak realm roles, granted by a Keycloak admin and read from the token; a
role change takes effect at the next login and leaves no record of who appointed whom, until when and
with which limit.

## Decision

The HB capability is an **in-app appointment**, a row in `purchasing_delegate`, not a Keycloak role:

- The Bereichsleiter of the governing Bereich, the members of the Organisationsleitung and an admin
  appoint and revoke it in the tool, with an optional end date and an optional personal monthly limit.
- The capability is the row itself: not revoked and not past its end date. The check runs on every
  request, so a revocation or an expiry takes effect at once, with no re-login.
- The appointment, its limit changes and its revocation are audited and noticed to the member.
- A revoked HB keeps the read access to and the settlement duty for the running deals; creating deals and
  requesting money need an active appointment.
- The access policy `PurchasingAccessPolicy` answers every question about the capability; no
  `@PreAuthorize` names a role for it, and `NeedPriceAudience` lets the supply module ask without naming
  the purchasing module.

## Consequences

- The appointment, its author, its end and its limit are data the governing Bereich can read and audit;
  the Keycloak realm needs no change.
- A second place grants a capability next to the realm roles. The role matrix lists it
  ([`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)) and the policy is the one place that
  decides.
- The data of an appointment is member data: it joins export, erasure and account merge.

## Alternatives considered

- **A Keycloak realm role `TRADE_DELEGATE`.** Rejected: no end date, no limit, no author, and a change
  needs a Keycloak admin and a new login.
- **A flag on the user.** Rejected: it keeps no history of appointments and revocations.
