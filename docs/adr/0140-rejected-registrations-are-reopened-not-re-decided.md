# ADR-0140 — A rejected registration is reopened, not re-decided

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** @greluc
- **Related:** ADR-0111 (admin-mediated Discord registration linking), `REQ-SEC-034`, `REQ-SEC-017`, `REQ-SEC-026`

## Context

A Discord registration that an admin rejects had no way back. Three independent guards closed
every door, each of them individually correct:

- `listPendingRegistrations()` reads `ApprovalStatus.PENDING` only, so a rejected row vanished
  from the admin's view entirely — an admin could not even *see* whom they had rejected.
- The shared `decide(...)` body refuses every non-`PENDING` row with a `409`, so
  `POST /api/v1/admin/registrations/{id}/approve` failed even for an admin who knew the id. That
  guard was added deliberately in PR review #3: re-deciding an already-`ACTIVE` member would
  silently strip their authorities and trap them on the waiting page.
- `linkPendingToExisting` needs a `PENDING` source and an `ACTIVE` target, so it was no escape
  either.

The only other writer that sets `ACTIVE` is the admin-bootstrap carve-out in
`UserReconciliationService`, which fires solely for holders of the Keycloak `ADMIN` realm role on an
interactive login — no help to an ordinary member.

Found while investigating a support case: a member rejected on 2026-06-29 sat on the waiting page
with no in-app remedy. The two available workarounds were both worse than the problem. A manual
`UPDATE` against the production database bypasses the audit trail and violates the repository's
read-only production policy outright. Deleting the account (`DELETE /api/v1/users/{id}`) does let
the person re-register, but destroys their data and their history to recover them — recovery by
demolition.

The rejection itself is not a rare mistake to design around; it is an expected one. An admin decides
from a Discord handle plus an optional server nickname, and a member whose handle does not resemble
their in-game name is precisely the case that ADR-0111 exists to repair.

## Decision

**Reversal is its own admin action — `POST /api/v1/admin/registrations/{id}/reopen`, moving the
account `REJECTED → PENDING` — and the decision guard is left exactly as narrow as it is.**

The rejected rows also become visible: the queue endpoint takes an optional
`?status=PENDING|REJECTED`, and the admin page renders a second table for them.

The reopen:

- refuses any account that is not `REJECTED` (a `PENDING` row needs no reopening; an `ACTIVE`
  member pushed back into the queue would lose their access);
- is admin-only, optimistic-locked on the echoed `version`, and audited;
- writes a **new** `ApprovalDecision` value, `REOPENED`, widened into
  `chk_user_approval_event_decision` by `V233` — the same widening `V223` had to apply for `LINKED`;
- clears the account's `approvedAt` / `approvedById` so a queued row never shows a decision time;
- publishes **no** notification.

`REJECTED → reopened → approved` therefore reaches `ACTIVE` through supported, audited actions only,
leaving two audit rows that say what actually happened.

## Consequences

**The decision guard keeps its exact shape.** Nothing about "only a still-`PENDING` registration may
be decided" changes, so the protection it was written for — an `ACTIVE` member cannot be re-decided
into a lockout — cannot be weakened by a later edit to a widened predicate.

**The reversal costs two clicks, and that is the point.** An admin reopens, then approves. The
audit records both, and the approval travels the ordinary path rather than a second, parallel one.

**`REOPENED` is not `APPROVED`, and the distinction is load-bearing.** A reopen grants no access:
the account lands `PENDING`. Recording it as an approval would make the audit trail assert an access
grant that never happened, and would make the reversal indistinguishable from the re-approval that
usually follows it minutes later. Reusing `APPROVED` would have avoided a migration; a migration is
the cheaper of the two costs.

**The account row loses its old decision stamp; the audit table does not.** `approvedAt` /
`approvedById` are cleared so the reopened row is indistinguishable from a fresh registration in the
queue. Who rejected it and when survives in `user_approval_event`, which is the record of history —
the account row only ever held the *latest* decision.

**A reopen can fire `RegistrationApprovalOverdue`, and this is accepted.**
`basetool_registration_pending_oldest_age_seconds` measures from the registration's creation time, so
reopening a months-old rejection makes the gauge jump to that full age (>48 h threshold, `for: 10m`).
An admin who reopens and decides within minutes never trips the window; one who reopens and walks
away has left a genuinely overdue queue item, which is what the alert is for. The alternative —
a `pending_since` column — would change the metric's meaning for every pending row to remove a brief
cosmetic spike on one panel. Anyone triaging that alert right after a reopen should expect an age
measured from the original registration.

**The rejected list is unpaged**, like the pending queue it sits beside. Rejections are rare in a
squadron-sized realm. If that ever stops being true, both tables need paging together rather than one
of them growing a second contract.

## Alternatives considered

**A `rejected` filter plus a direct `REJECTED → ACTIVE` approval.** One click instead of two, and no
new endpoint. Rejected because it widens the `decide(...)` guard that PR review #3 deliberately
narrowed: the predicate becomes "`PENDING` or `REJECTED`", and every future reader has to re-derive
why `ACTIVE` is excluded but `REJECTED` is not. It also collapses two genuinely different admin acts
— undoing a mistake, and granting access — into one audit row.

**Reusing `APPROVED` for the reversal's audit row.** No enum change, no migration. Rejected: see
above — the audit would claim an access grant that did not occur.

**Deleting and re-registering as the documented remedy.** Already possible today, and rejected as
the answer: it destroys the account's data and history, depends on the member noticing and acting,
and leaves the audit trail with a deletion where a correction belongs.

**Firing the new-pending admin notification on reopen.** Considered for symmetry with a genuine new
registration. Rejected: it would page the whole admin body about a months-old registration that the
acting admin is already looking at, and a reopen is not a new arrival.

**Leaving it to a manual production `UPDATE`.** The status quo. Rejected on the terms the repository
already sets: production writes by hand are forbidden, and a state the application can enter but not
leave is an application defect, not an operations task.
