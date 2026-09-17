# ADR-0181 — Self-service deletion is a request an admin executes, and the admin's click does both halves

- **Status:** Proposed
- **Date:** 2026-09-15
- **Deciders:** @greluc (decisions 5 and 6, plus the execution mechanics on 2026-09-15)
- **Related:** specs `REQ-SEC-061` (new) · `REQ-SEC-062` (new, the history wish) ·
  [`security-and-access.md`](../specs/security-and-access.md) (`REQ-SEC-017` the approval
  lifecycle this is modelled on, `REQ-SEC-026` the commit ordering) ·
  [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-008`, what a deletion actually
  does) · [`notifications.md`](../specs/notifications.md) (`REQ-NOTIF-007`) ·
  [ADR-0111](0111-admin-mediated-discord-registration-linking.md) (the delete-Keycloak-user-last
  ordering) · [ADR-0140](0140-rejected-registrations-are-reopened-not-re-decided.md) (a decision
  kept reversible) ·
  [ADR-0182](0182-an-unfinished-account-deletion-is-measured-from-a-recorded-absence.md) (the
  back-stop; this is the front door) ·
  [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md) · migrations
  `V242`, `V243`

## Context

The published privacy policy grants the member an Art. 17 right to erasure and describes, in
detail, what a deletion does. The application offered **no way to exercise it.** The only route was
to contact an admin out of band, and nothing in the tool said so.

Meanwhile the deletion itself already existed and is substantial (REQ-DATA-008): it removes the
Keycloak account, purges the member's warehouse stock, hangar, personal inventory with its
free-text notes, blueprints, notifications and grades, reassigns their missions and refinery orders
to an admin, and unlinks their mission participation behind a *"Gelöschter Nutzer"* placeholder.
None of it is reversible.

Two questions followed, and @greluc decided both:

1. **Does the member's click delete, or does it ask?** → It asks (decision 5).
2. **Can the member also ask for the handle snapshots that survive the deletion to go?** → Yes, as
   a checkbox that records a wish an admin decides deliberately (decision 6).

A third question only surfaced during implementation. `UserDeletionService` refuses any account
that still exists in Keycloak, and the check is **fail-closed and doubled** — the cached
`in_keycloak` flag *and* a live Admin-API probe — because a single swallowed sync error was once
enough for an admin to hard-delete an active member. So "the admin executes the request" could mean
two very different things: perform the whole deletion, or merely unlock the member list's existing
delete button once the admin has removed the Keycloak account by hand and the nightly sync has
noticed.

## Decision

**A `deletion_request` table (V242), not a status on `app_user`.** A request has its own facts —
when it was raised, whether the history wish came with it, who decided, when, and why — and a
status column carries the last of those while losing the rest. `app_user` also has exactly one row
per member, so a member who withdraws and asks again would overwrite the first request's record.

**Three states, and deliberately no executed one.** `PENDING` / `WITHDRAWN` / `DECLINED`. Carrying
the request out deletes the `app_user` row, and `user_id` is `ON DELETE CASCADE`, so the request
goes with the account — which is what an erasure means. The record of the deletion is the audit
trail (`ACCOUNT_DELETION_REQUEST_EXECUTED`, written before the delete so the request id survives in
it, alongside REQ-DATA-008's `USER_DELETED`), not a row that outlives the member and says they
asked to be forgotten.

**One open request per member, by a partial unique index on `(user_id) WHERE status = 'PENDING'`,
not by a check.** Raising is therefore idempotent under a genuine race: the pre-read answers the
common case, and the `DataIntegrityViolationException` handler resolves to the winner's row. A
declined request does not block a later one, which is why the index is partial rather than unique
on `user_id`.

**A refusal must carry a reason, and three layers enforce it** — the client, the service and a
database `CHECK`. Art. 12(4) obliges the controller to tell the requester *why* a request was
refused, together with their right to complain to a supervisory authority and their right to a
judicial remedy. A reason nobody wrote down cannot be told to them. The member is notified and
reads it on their own profile page, which is why the member-facing `GET` returns their **latest**
request rather than only a pending one — a notification saying "declined" with nowhere to read why
would satisfy the letter of nothing.

**Executing does both halves.** The admin's click deletes the local row *and* the Keycloak account:
database half first, Keycloak last (ADR-0111's ordering), with the presence probe waived on exactly
the terms `AccountConsolidationService` waives it — the caller removes the Keycloak user itself,
moments after the commit, so the probe would be refusing on account of a user the operation is
already disposing of. Decided by @greluc on 2026-09-15, over the alternative of merely unlocking the
member list.

The safety argument survives the waiver intact, and the distinction is worth stating precisely: the
probe exists so that a **stale flag** cannot be mistaken for a decision. Here there is no inference
at all — an admin has looked at one named request and chosen to delete that one account. What the
probe protects against is absent; what it would cost is a request that sits until the next nightly
sync and then depends on somebody coming back for it, which is precisely the abandoned state
[ADR-0182](0182-an-unfinished-account-deletion-is-measured-from-a-recorded-absence.md) exists to
detect.

**The history wish is never automatic, and the admin's checkbox starts unticked** even when the
member ticked theirs. Granting is a deliberate weighing of the member's right against the interest
in an auditable ledger, and a pre-ticked box would make the wish the default. What granting does is
`REQ-SEC-062` / [ADR-0183](0183-a-granted-erasure-anonymises-the-handle-snapshots-in-place.md).

**Two notifications (V243), both obligations rather than courtesies.** Raising notifies every admin
— Art. 12(3) allows one month to respond, and a queue nobody is told about is how that month
passes. Refusing notifies the member, per Art. 12(4). There is no rule for a carried-out request:
its recipient no longer exists, and `notification.recipient_user_id` is `ON DELETE CASCADE`, so the
row would be written and immediately removed.

**Two queue gauges and `DeletionRequestOverdue` at 14 days** — the one alert in this system whose
threshold comes from a statute rather than from operational taste, set well inside the month so an
admin still has two weeks to weigh a request.

## Consequences

- The privacy policy's Art. 17 promise now has an in-app route, and the route is visible on the
  profile page rather than documented somewhere a member would have to find.
- **Deleting a member is now a one-click operation for an admin, where it used to be two acts
  across two systems.** That is the intended gain and also the thing to be careful about: the
  execute button is the most destructive control in the application. It sits behind a modal that
  names the member and states what is removed, and it is ADMIN-only at the URL matcher *and* the
  method gate.
- The `deletion_request` row is itself personal data about the member, and it disappears with them.
  A question about a *past* deletion is answered from the audit trail — which REQ-AUDIT-006 now
  bounds at 24 months, so that answer is not permanent either.
- A failing Keycloak delete after a committed local half is logged and swallowed. The member's data
  is gone, which is what they asked for; the orphaned Keycloak account resurfaces through the roster
  sync as a fresh `PENDING` registration an admin can refuse. The reverse failure — Keycloak gone,
  local row kept — is the one that leaves personal data behind, and the ordering makes it
  impossible.
- `AdminDeletionRequestController` has **two** endpoints rather than one with a flag. Declining and
  executing are not variants of each other, and a parameter is a thing a mistake can flip.
- The admin queue names every member who has asked to be erased, which is itself information about
  them — a second reason for the ADMIN gate beyond the destructiveness.

## Alternatives considered

- **Immediate self-delete.** Rejected by @greluc (decision 5). It is irreversible, it removes data
  the org unit may still be relying on (reassigned missions, refinery orders), and it would put the
  application's most destructive action one mis-click away on a page every member visits.
- **A status on `app_user` instead of a table.** Rejected: it loses the raise time, the wish, the
  decider and the reason, and cannot represent a member's second request after a withdrawal.
- **Deleting withdrawn and declined rows.** Rejected: "asked and changed their mind" is a different
  fact from "never asked", and it is the difference an admin needs when a second request arrives.
- **Execute merely unlocks the member-list button** (the admin removes the Keycloak account by
  hand). Rejected by @greluc: it leaves every request dependent on somebody returning after the
  nightly sync, which is the abandoned-deletion state ADR-0182 was written because of. Its one
  advantage — not waiving the presence probe — protects against an inference this path does not
  make.
- **Grant the history wish automatically when the member ticks the box.** Rejected by @greluc
  (decision 6): it is a weighing of competing interests, and a checkbox is not a weighing.
- **Pre-tick the admin's checkbox when the member asked.** Rejected: it converts "the member asked"
  into "the system proposes", which is the same defect one step removed.
- **A free-text reason field on the member's request.** Rejected: Art. 17 does not require the data
  subject to justify anything, and the field would create one more store of personal data about
  somebody asking to be forgotten.
- **Notify the member when the deletion is carried out.** Rejected as impossible rather than
  unwanted: the recipient row cascades away with the account. Telling them is an out-of-band act by
  definition.
