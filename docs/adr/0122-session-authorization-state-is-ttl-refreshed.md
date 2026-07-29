# ADR-0122 — Session-cached authorization state is TTL-refreshed, not session-pinned

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-013 · REQ-SEC-017 · REQ-SEC-025 · ADR-0030 · ADR-0088

## Context

`BackendRoleSyncFilter` cached two pieces of authorization state on the HTTP session and resolved
each **exactly once per session**: the registration's approval verdict (`BACKEND_APPROVAL_STATE`)
and the backend-derived roles/permissions it copies onto the OIDC principal
(`BACKEND_ROLES_SYNCED`). Both are decided **by an admin, mid-session** — approval in the
registration queue (REQ-SEC-017), roles and org-unit membership afterwards, since approval grants
none by itself.

An authenticated session lives 720 h (REQ-SEC-025, ADR-0088), so neither cache ever self-healed. The
observed effect on a freshly approved Discord member (2026-07): the session still carried the
`PENDING` verdict cached before the decision, so every request kept redirecting to the waiting page —
which itself told them to sign in again. That first re-login got them in as a role-less Guest;
the roles the admin assigned next were then invisible to the already-synced principal, costing a
second logout/login. Two forced re-logins for one approval, and the waiting page was the only
surface the user could see while it happened.

Refreshing this state is not free: the filter runs on every request of every session, including
each CSS/JS/font asset of a page load, and each refresh is a backend round trip.

## Decision

We will **bound the staleness of both values with a TTL instead of pinning them for the session's
lifetime**, and shape the refresh so the cost lands only where the value can actually change:

- **`ACTIVE` is terminal and stays cached for free.** The backend refuses to decide anything but a
  still-`PENDING` registration, so an approved verdict can never change back and is never re-read.
- **A non-terminal verdict (`PENDING`/`REJECTED`) expires after 15 s.** Only accounts that cannot
  use the tool anyway pay for it, and an approval reaches a waiting session within seconds.
- **The role sync repeats every 60 s**, and immediately on the observed `PENDING → ACTIVE`
  transition, so authorities granted after login reach the principal without a new session.
- **Static assets skip the filter body entirely**, keeping the refresh at roughly one read per
  interval per session rather than one per asset.
- **The waiting page polls its own status** and forwards into the tool on approval, so the fix is
  visible on the one surface a pending member can see instead of only on their next navigation.
- **The sync reconciles in both directions** — it grants what the backend now reports and
  **revokes what it no longer reports**, rather than only ever appending. Refreshing on a TTL is
  what makes revocation possible at all; an append-only sync would have left a withdrawn role
  rendering UI that 403s for the rest of the session. The removal rule is asymmetric, because the
  two kinds of authority have different owners:
  - **`ROLE_*`** — the backend response is authoritative for the whole vocabulary. Its local `role`
    catalog is where realm roles are mirrored and is exactly what its own `@PreAuthorize` gates
    read, so a role it no longer reports is one it will no longer honour. Technical realm roles
    with no catalog entry (`offline_access`, `default-roles-*`) fall away with it — nothing gates
    on them, and the backend never carried them either.
  - **Everything else** — only what a *previous* sync asserted may be dropped, recorded in
    `BACKEND_SYNCED_AUTHORITIES`. Permission strings carry no prefix and are indistinguishable
    from the login-owned `OIDC_USER` / `SCOPE_*` authorities, so keying the removal on what this
    filter itself granted makes stripping one of those structurally impossible.
  - A response that carries no role (or no permission) list asserts nothing and revokes nothing;
    silence is never read as "everything is withdrawn".

## Consequences

- An admin decision propagates to a live session on its own; no logout/login round trip is part of
  the approval flow any more, and the waiting page no longer has to instruct one. The sync reads the
  backend's local mirror, so an org-unit membership lands within the 60 s interval while a **Keycloak
  realm role** first reaches that mirror via the next access-token refresh (`accessTokenLifespan`
  300 s) — a few minutes rather than a re-login either way.
- Steady-state cost: nothing for an approved, idle-tab session (terminal verdict, no navigation);
  about one `/api/v1/users/me` read per minute per actively browsing session; one
  registration-status read per 15 s per waiting member.
- A withdrawn role or permission now leaves the session on the next re-sync, so the frontend stops
  offering actions the backend refuses. The frontend principal and the backend's authority set
  converge instead of drifting apart for up to 30 days — which is what REQ-SEC-013 wanted from the
  start.
- **Revocation is not instant, and must not be mistaken for an access boundary.** An org-unit
  membership is withdrawn within the 60 s interval; a Keycloak realm role first has to leave the
  backend's mirror via the next access-token refresh (300 s). The boundary stays the backend, which
  re-derives authorities per token under its own 30 s memoisation. Anything that must revoke
  *immediately* still needs the session killed.
- Dropping an authority is a heavier act than adding one: a wrong or partial `/api/v1/users/me`
  response now shrinks a live session's UI rather than merely failing to widen it. Mitigated by
  acting only on a successful, complete response (a fallback `null` or a throw changes nothing), by
  the null-list guards, and by the ownership rules above.
- Two session attributes changed shape: `BACKEND_ROLES_SYNCED` (boolean) → `BACKEND_ROLES_SYNCED_AT`
  (epoch-millis stamp), plus the new `BACKEND_SYNCED_AUTHORITIES`. Sessions that survive the deploy
  in Redis carry neither, which reads as "never synced, nothing asserted yet" — they re-sync once
  and can revoke a permission from the sync after that. No migration needed.
- A clock jumping backwards cannot freeze a refresh: a stamp in the future counts as due.

## Alternatives considered

- **Push the approval into the session (Redis session lookup by user, or a Keycloak event).** Exact
  and cheap at steady state, but it couples the approval transaction to the session store and adds a
  failure mode where a lost push leaves the session stranded exactly as before. The polling design
  fails safe; the push does not.
- **Keep the one-shot cache and only fix the wording** ("sign in again" on the waiting page). Cheapest
  option, and rejected: it documents the defect rather than fixing it, and does nothing for the
  second re-login caused by the pinned role sync.
- **Re-read both values on every request.** Simplest to reason about, rejected on load: every asset
  of every page load of every session would become two backend round trips.
- **Replace the principal's authority set wholesale with the backend's.** The obvious way to make
  revocation work, and wrong: `OIDC_USER` and the `SCOPE_*` authorities are granted by the login and
  are not in the backend's answer, so a wholesale replace would silently strip them. The split rule
  (`ROLE_*` backend-owned, everything else revocable only if we granted it) keeps revocation without
  that blast radius.
- **Keep the sync append-only and rely on the backend alone to enforce revocation.** Correct on
  security grounds — the backend does enforce it — but it leaves the UI offering actions that 403,
  which reads as a broken tool rather than as a withdrawn permission.
- **Invalidate the session from the backend on approval.** Would force the re-login rather than remove
  it, and a forced session kill on an unrelated admin action is a worse experience than a 15 s wait.

