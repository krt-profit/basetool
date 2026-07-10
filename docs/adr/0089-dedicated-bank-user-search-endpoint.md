# ADR-0089 — Dedicated bank-audience user-search endpoint for the remoteSource picker switch

- **Status:** Accepted
- **Date:** 2026-07-10
- **Deciders:** @greluc
- **Related:** ADR-0053 (searchable-combobox standard) · ADR-0085 (5000-account scaling, which deferred
  this) · REQ-FE-011 · REQ-BANK-008/009/044 · `UserController.searchUsersForBank` ·
  `UserProxyController` · `krt-user-search.js` · #1193

## Context

ADR-0053 standardised user selection on the shared searchable combobox and explicitly kept the
component's `remoteSource` (server-side search behind `/api/v1/users/search`) as the opt-in scaling
path *"if the list grows into the thousands … without changing the contract."* ADR-0085 reached that
trigger (5000 accounts / 200 concurrent) and deferred the UI switch to a follow-up. #1193 executes it:
every picker that preloaded the full or admin-"all-squadrons" user roster now fetches matching users
on demand as the user types, so no converted picker ships thousands of `<option>`s.

Most converted pickers sit on pages that require an org role, so the existing squadron-scope-aware
`/api/v1/users/search` (`@PreAuthorize` ADMIN/OFFICER/KRT_MEMBER) covers them unchanged. The
exception is the **bank** pickers — register a holder, grant the Bank-Employee role, set an approval
limit — which today resolve candidates through `/api/v1/users/lookup`. That lookup is deliberately
widened to bank staff (`BANK_EMPLOYEE`, which covers `BANK_MANAGEMENT` via the role hierarchy) because
a bank manager/employee **need not hold any org role** (REQ-BANK-008/009/044). The regular `/search`
would 403 such a caller.

Crucially, `/search` and `/lookup` already resolve the **identical** scope:
`UserService.searchByUsername(...)` and `findAllReference()` both go through
`OwnerScopeService.currentUserListScopeSquadronIds()`, which for an org-role-less bank manager returns
the unfiltered all-users set and for an org member returns their Staffel union. So the *only* thing
that separates the bank audience from the regular one is the **role gate**, not the query or the
scope.

## Decision

Add a **dedicated** paged search endpoint `GET /api/v1/users/search-bank` that is byte-for-byte
identical to `/search` in query, scope and projection (it delegates to the same
`UserService.searchByUsername` + peer redaction), differing only in its `@PreAuthorize` — it mirrors
`/lookup`'s gate (adds `BANK_EMPLOYEE`). The frontend exposes it through the existing
`UserProxyController` (`/users/search-bank`), and the combobox wires it declaratively via the
`remote-bank-users` marker in the shared `window.krtComboboxRemoteSources` registry
(`krt-user-search.js`); the ordinary pickers use `remote-users` → `/search`.

We rejected **widening `/search` itself** (or adding an `?audience=bank` param): folding a second
authorization regime onto one path makes the ordinary picker's gate harder to reason about and to
test, and there is no scope difference to justify the coupling. A separate, self-documenting path
keeps `/search`'s regime untouched and each endpoint's access trivially testable.

The URL-layer matcher in `SecurityConfig` lists `/api/v1/users/search-bank` explicitly (before the
`/api/v1/users/**` → ADMIN catch-all) with the full role set, so the filter-layer gate does not depend
on role-hierarchy evaluation — same defensive pattern as `/lookup` and `/search`.

## Consequences

- The bank pickers scale like every other converted picker: server-side, debounced, paginated search;
  no roster preload. Bank staff without an org role keep working exactly as they did through
  `/lookup` (same widened access, same all-users scope).
- No new exposure: `/search-bank` returns the same peer-redacted projection as `/search`, and grants
  bank staff nothing they could not already read via the broadly-accessible `/lookup`.
- `/search`'s authorization is unchanged; a bank employee still gets 403 there (asserted by
  `UserAccessControlTest`), so the two audiences stay cleanly separated.
- One extra endpoint + frontend proxy + a `remote-bank-users` registry entry. It is an authenticated
  read surface, so no blackbox probe is warranted (blackbox monitoring covers public surfaces only)
  and no new business metric is required.
- REQ-FE-011 and ADR-0053 are amended to record that the large pickers now run in `remoteSource` mode;
  ADR-0085's deferred "async server-side user pickers" follow-up is closed by #1193.

## Alternatives considered

- **Widen `/api/v1/users/search` to `BANK_EMPLOYEE`.** Fewer moving parts, and the scope is already
  correct — but it merges the bank audience into the ordinary picker's endpoint, so every future
  reader/test of `/search` must remember it is also the bank path. Rejected for clarity.
- **`?audience=bank` query param on `/search`.** Same coupling as above plus per-param role branching
  on one handler — the hardest of the three to reason about. Rejected.
- **Leave the bank pickers on `/lookup` (unconverted).** They would still ship the full roster at 5000
  accounts, defeating the point of #1193 for exactly the audience that resolves the *whole* user base.
  Rejected.

