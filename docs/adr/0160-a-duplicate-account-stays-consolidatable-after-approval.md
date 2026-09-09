# ADR-0160 — A duplicate account stays consolidatable after approval, on the member administration

- **Status:** Accepted
- **Date:** 2026-09-09
- **Deciders:** @greluc, Claude
- **Requirement:** [REQ-SEC-055](../specs/security-and-access.md)
- **Related:**
  [ADR-0111](0111-admin-mediated-discord-registration-linking.md) (the queue's link action, whose
  reach this extends past approval),
  [ADR-0140](0140-rejected-registrations-are-reopened-not-re-decided.md) (the same shape, closed once
  for rejections),
  [ADR-0142](0142-a-session-belongs-to-the-tokens-subject.md) point 5 (the merge this composes),
  REQ-SEC-022 (the fail-open precheck that lets duplicates through), REQ-SEC-026, REQ-SEC-034,
  REQ-SEC-046, REQ-DATA-008
- **Issues:** #1827 (the regression that had to be fixed first), #1828

## Context

One member, two accounts, is a case the tool already knows about. The Discord collision precheck is
deliberately fail-open (REQ-SEC-022): refusing a stranger is safe, refusing a *member* who happens to
share a name is not — so a member whose Discord handle differs from their in-app name reaches the
approval queue as a seemingly-new registration. The queue carries the remedy for exactly that:
"Verknüpfen" (REQ-SEC-026) moves the Discord identity onto the existing account and disposes of the
throwaway.

It only works while the row is in the queue. The queue serves `PENDING` and `REJECTED` only —
`ACTIVE` is refused with `400` so a small admin queue cannot degrade into an unbounded member dump
(ADR-0140) — and `linkRegistrationToExistingAccount` guards on `PENDING` in the service too, so after
approval the action is gone rather than merely hidden. There is no way back either: `decide(...)`
refuses every non-`PENDING` row *by design*, so an already-`ACTIVE` member cannot be re-decided into
a lockout, and `REOPENED` (REQ-SEC-034) covers only `REJECTED`.

So an admin who notices the duplicate one click too late has no supported remedy at all. What
remained was an eight-step hand-over across the Keycloak admin console and the member administration
— read the snowflake, delete the realm user, sync, delete the row, re-link, sync again — every step
of it a production write, and the ordering between two of them load-bearing in a way nothing on
screen says.

This is the shape ADR-0140 already closed once. Rejection used to be terminal for the same reason:
the queue's filter took the row out of view, and the only ways back were a manual production `UPDATE`
(bypassing the audit trail, violating the read-only production policy) or deleting the account —
recovery by demolition. Found the same way, too: in a support case, not in review.

## Decision

**Add an ADMIN-only consolidate action to the member administration**, not to the queue:
`POST /api/v1/users/{id}/consolidate`, surfaced on every row of `/members`.

It is the queue's link **plus** the belongings move, because an approved duplicate — unlike a pending
one, which carries zero authorities and cannot own anything — has been able to accumulate data.

Three sub-decisions carry the weight:

**The path names the account that is dissolved, the body the one that survives.** This is the
opposite way round from `POST /admin/registrations/{id}/merge`, and the inconsistency is deliberate:
there the admin acts on the surviving registration in a queue of registrations, here they act on the
duplicate's row in a list of members. The URL should name the row the admin clicked.

**It composes rather than reimplements.** `UserAccountMergeService#merge` (REQ-SEC-046) already
decides which rows follow the member and which stay with the act, and already refuses two bank
ledgers rather than guessing which postings belong to whom — and it carries no approval-status guard,
so it already worked on an active account and simply had no UI in front of it for this case.
`KeycloakService` moves the federated identity; `UserDeletionService` purges the emptied row behind
its fail-closed probe.

**The orchestrator is non-transactional and deletes the Keycloak user last**, mirroring REQ-SEC-026
exactly: identity first, database half through a self-proxy, duplicate's realm user last, so a
rolled-back database half leaves that user intact for a clean re-read on retry. Inside the
transaction the order is forced rather than chosen — `app_user.discord_user_id` is UNIQUE (V172), so
the duplicate's row has to be gone before the survivor can claim the snowflake.

## The regression this uncovered

The link path had not worked since 2026-07-31 and nothing said so.

`be65ec570` (2026-07-20) shipped it with the delete-Keycloak-user-last ordering. `dcfd1971c`
(2026-07-31, #1460) hardened `UserDeletionService` with a **live** Keycloak probe, so a stale cached
`in_keycloak` flag could no longer let an admin irreversibly purge an active member. Both changes are
right. Together they contradict: the database half calls `deleteUser` while the throwaway Keycloak
user is still present *by design*, the probe fires, and the whole link rolls back with "stored flag
was stale".

No test caught it because `UserRegistrationServiceTest` holds `UserDeletionService` as a `@Mock`, so
the real guard never ran in the only test of that path.

**Resolved by teaching the guard about the caller rather than weakening it for everyone:**
`deleteUser(UUID)` keeps the probe and stays the admin-facing entry point;
`deleteUser(UUID, KeycloakPresenceCheck)` lets a caller that removes the Keycloak user itself waive
it. Spelled as an enum whose waiving constant is named
`WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER`, so it cannot be passed by accident and states at the call
site what the caller is promising. The cached-flag guard is **not** waived: a caller that has not
marked the row absent is not in the middle of disposing of it.

## Alternatives rejected

- **Widen `decide(...)` to accept `ACTIVE`.** That guard exists so a live member cannot be re-decided
  into a lockout, and ADR-0140 rejected widening it once already. It would also put member
  administration on the registration queue, which is a different surface with a different DTO.
- **Reorder the link orchestrator so the Keycloak user is deleted before the database half.** It
  would work — `readDiscordLink` maps a 404 to empty and the local `discord_user_id` fallback exists
  precisely for the already-deleted case — but it contradicts an acceptance bullet of REQ-SEC-026 and
  trades documented retry semantics away to route *around* a guard instead of informing it.
- **Document the eight-step hand-over and leave it at that.** It is written down (the knowledge
  base's duplicate-account runbook), and that is worth having for the accounts already in this state.
  As the standing remedy it is what ADR-0140 called recovery by demolition: every step a production
  write, one ordering constraint that is invisible on screen, and a `UNIQUE` violation waiting for
  whoever does step 6 before step 5.
- **Reuse `POST /admin/registrations/{id}/merge` and tell admins to call it directly.** It moves
  belongings but never touches Keycloak or `discord_user_id`, so it solves half the problem and
  leaves the identity where it was. And an endpoint with no UI is not a remedy an admin has.

## Consequences

- An approved duplicate is repaired in one dialog instead of eight production writes.
- The member administration gains a destructive-adjacent action on every row. It is ADMIN-gated,
  optimistic-locked, audited twice (`USER_MERGED` from the merge, `LINKED` against the survivor), and
  refuses on every ambiguity rather than guessing — but it *is* the action that removes an account,
  so the dialog names what moves and what disappears.
- The queue's "Verknüpfen" stays the cheaper path for a duplicate that is still `PENDING`, and stays
  the recommendation: it is one click, it needs no belongings move, and it happens before the member
  has invested anything in the wrong account.
- `UserDeletionService` now has two entry points. The waiver is legitimate only for an orchestrator
  that has already moved the account's identity away and will delete its Keycloak user when the
  transaction commits; the enum name is the documentation, and `UserDeletionServiceTest` pins both
  halves.

