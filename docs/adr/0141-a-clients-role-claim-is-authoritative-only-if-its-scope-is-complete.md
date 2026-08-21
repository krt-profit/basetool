# ADR-0141 — A client's role claim is authoritative only if its scope is complete

- **Status:** Accepted
- **Date:** 2026-08-21
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-036](../specs/security-and-access.md)
- **Related:** [ADR-0131](0131-mobile-auth-refresh-only-dpop-binding.md) (the mobile client),
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the `azp` handle, the database-only authority
  assembly), REQ-SEC-035 (what the mobile client's scope carries)

## Context

`UserReconciliationService#syncUser(Jwt)` mirrors a member's realm roles into `app_user` on **every**
authentication, and it does so by replacement:

```java
user.setRoles(mapRoles(extractRolesFromJwt(jwt)));
```

`CustomJwtGrantedAuthoritiesConverter` then derives the request's authorities from that stored set.
The arrangement was sound for as long as it held one implicit assumption: **every client's token
carries the member's whole role list.** With a single confidential web client that was simply true,
so nothing had to say it.

REQ-SEC-035 broke it deliberately. The mobile client runs with `fullScopeAllowed: false` and a scope
mapping that withholds `Admin`, because the backend gives an administrator without an
active-org-unit header `adminAllScope` — every org unit at once — and the app has no screen designed
around that. Its tokens therefore describe a member who is intentionally smaller than the real one.

Replacing a stored role set from such a token is not a narrowing of what the app may do. It is a
write, to shared state, of a description that was never meant to be the truth — and because both
clients keep re-running the same replacement, the row ends up reflecting whichever client the member
used last. Per-request authorization stays correct either way; every consumer that reads roles
*outside* a request does not: scheduled tasks, notification targeting, roster and admin views.

This was measured before it was reasoned about. On the test stack, an account holding
`Admin` + `Officer` + `KRT Member` was left holding `Guest` alone after one login through the app —
that was the empty-scope form of the same bug, closed by REQ-SEC-035. Filling the scope shrank the
blast radius to `Admin` specifically; it did not remove the mechanism.

## Decision

**A token from a configured *partial-scope client* does not write the account's role set.** The
stored set is left exactly as it was, and the request is authorised from the **token's** roles
instead of from the row.

- Partial-scope clients are matched on the token's `azp` and configured under
  `app.security.partial-role-scope.client-ids`.
- `syncUser(Jwt)` returns a `ReconciledUser(user, effectiveRoles)` rather than a bare `User`, which
  is what makes "what is persisted" and "what authorises this request" nameable as two things.
- `assembleFor(User, Collection<Role>)` takes the roles explicitly;
  `assembleFor(User)` keeps its database-only contract, because the ingest gateway's acting-member
  path (ADR-0129) has no token to read.
- A brand-new row is the single exception: with nothing stored to protect, persisting the partial
  claim beats persisting a member with no roles at all.

Both halves are load-bearing. Skipping the write while still authorising from the row would be
**worse than the defect it replaces**: the row keeps `Admin` precisely *because* the app path no
longer overwrites it, so the app would silently gain the authority its client scope was configured
to withhold — and only for administrators, which is the population where a silent grant matters
most.

## Alternatives considered

**Give the mobile client full scope and refuse admin behaviour in the backend instead** (gate
`RequestScopeResolver` on `azp`). This keeps the database honest by construction and is arguably the
cleaner model. Rejected because it inverts the failure direction: the app's token would then really
carry `ADMIN`, and every endpoint gated by `hasRole('ADMIN')` alone would be reachable from it. The
protection would live in one service that must remember to ask; here it lives in the token, which
cannot forget.

**Union instead of replacement** (`stored ∪ token`). Rejected outright: a demotion in Keycloak would
then never reach the database. Trading a role that lingers for a role that cannot be removed is not
an improvement.

**Merge selectively** — replace the roles the client *can* carry, keep the rest. This is the exactly
correct rule, and it requires the backend to know each client's scope mapping, which is Keycloak-side
configuration it has no business mirroring. The all-or-nothing skip is coarser and needs one list of
client ids instead of a per-client role matrix.

**Stop persisting roles from tokens entirely** and let the daily Admin-API pass own the set. Honest,
and closest to WoltLab being the roster's source of truth — but it delays every ordinary role change
by up to a day for web users too. Too large a behaviour change for the defect at hand.

## Consequences

- The default configuration is **non-empty**, the reverse of the ingest gateway's allowlist. There,
  empty means "nobody may act for another member" and is the safe end. Here, empty resumes
  overwriting stored roles from partial tokens, so shipping blank would ship the bug.
- A member who *only* ever uses the app has their stored roles maintained by the daily Admin-API
  pass rather than at login. That pass reads the realm directly and is unaffected by any client's
  scope, so the row still converges — within a day rather than within a request.
- The admin bootstrap carve-out in `syncUser` (an existing `PENDING` admin is promoted to `ACTIVE`)
  is evaluated from the token's roles and therefore does not fire on the app path. Harmless: it only
  ever promotes, never demotes, and a real administrator is already `ACTIVE`.
- No new metric. The only varying quantity is how much the partial-scope client is used, which
  `basetool_api_client_requests_total{client_id}` already carries (REQ-OBS-018); the guard is a pure
  function of static configuration.
- Adding a second narrowed client in future is a configuration change, not a code change — but it is
  also a decision that has to be made deliberately, which is why the list is explicit rather than
  inferred from the token.

