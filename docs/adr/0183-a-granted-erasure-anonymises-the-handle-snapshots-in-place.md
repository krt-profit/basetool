# ADR-0183 — A granted erasure anonymises the handle snapshots in place, with a sentinel rather than NULL

- **Status:** Proposed
- **Date:** 2026-09-15
- **Deciders:** @greluc (scope and placeholder decided 2026-09-15)
- **Related:** spec `REQ-SEC-062` (new) · `REQ-SEC-061`
  ([ADR-0181](0181-self-service-deletion-is-a-request-an-admin-executes.md), the request this
  executes) · [`audit.md`](../specs/audit.md) (`REQ-AUDIT-001`, the append-only invariant and the
  denormalised snapshot) · **contrasts with**
  [ADR-0179](0179-both-audit-trails-are-swept-on-a-retention-ceiling.md) (which rejected
  anonymisation) · [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-008`) ·
  `REQ-SEC-060` (the Personensuche that finds what an id cannot) ·
  [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

## Context

Deleting an account removes or reassigns everything it owned, and deliberately leaves the **handle
snapshots** behind. The user foreign keys are `ON DELETE SET NULL`, so without the copied handle a
row would record "an action by nobody, to nothing" — which is exactly why REQ-AUDIT-001 added the
snapshot. The privacy policy says so and rests it on Art. 6(1)(f).

That interest is real but **not automatically overriding**, and the policy says that too: a member
may ask for those entries to go as well. Until now that sentence described nothing. There was no
code path that could remove one person's name from the trail; the audit purge is time-based, not
personal.

@greluc decided to build one, and to build it as **anonymisation rather than deletion**: the rows
stay, the person goes. Then the scope question: the handle snapshot lives in more places than the
two audit trails, and the policy's Art. 17 sentence named only "Protokoll- und Buchungseinträge".
The full set that survives a deletion is six columns across five tables, including two the policy
did not mention at all. @greluc chose **all six, and to add the two missing ones to the policy**.

## Decision

**Six places, one act, one transaction.**

|                                   Where                                   |              Matched by               |
|---------------------------------------------------------------------------|---------------------------------------|
| `audit_event.actor_handle`                                                | `actor_user_id`                       |
| `bank_audit_event.actor_handle`                                           | `actor_user_id`                       |
| `bank_transaction.counterparty_handle`                                    | `counterparty_user_id`                |
| `bank_booking_request` — requester, decider, counterparty, owner approver | the four id columns, in one statement |
| `job_order_handover.recipient_handle`                                     | the text, **case-insensitively**      |
| `job_order_item_handover.recipient_handle`                                | the text, **case-insensitively**      |

A partial anonymisation is the one outcome that must not be possible: five of six erased reads as a
completed erasure while the sixth still names the person. Hence one transaction, and hence the
`bank_booking_request` update covers all four of its columns in one statement — a member can appear
on the same request as requester, decider, counterparty *and* over-limit approver.

**The two handover columns have no user id beside them at all.** A recipient is typed in by hand
and may name somebody with no account, so the only possible match is on the text — and it has to
ignore case, because whoever typed it was not copying from a roster. Two consequences follow, both
accepted: these two are the *only* targets reachable for an **already deleted** account (the other
four need the FK, which has already nulled out), and they can **over-match**, since a handle is not
a unique key. Over-matching is the correct direction of error for an erasure request, and the admin
reviews the Personensuche hits (`REQ-SEC-060`) before granting.

**A sentinel, not `NULL`.** Four of the six columns are `NOT NULL`, and that constraint is the
guarantee that a row always says who acted. Relaxing it to make room for an erasure would weaken
the invariant for every row written afterwards, so a future bug could insert `NULL` silently. A
sentinel keeps the constraint and makes "erased on request" a *distinguishable state* rather than an
absence.

**The stored value is `#ANONYMISED#`, which is not a word in any language.** It is written once and
read in two, and the project's i18n rule admits no hardcoded user-visible text. Every human-facing
surface maps it to `general.anonymisedHandle`: the audit viewer and four bank screens via the
Thymeleaf-visible `handles` bean, and the audit, statement, management and two handover PDFs via
`HandleAnonymisation#humanise`. **Machine-readable JSON exports keep the raw token** — a translated
placeholder there would vary with the exporting admin's locale and stop two exports of the same rows
from being comparable.

**It leaves a receipt.** A `HANDLE_SNAPSHOTS_ANONYMISED` marker goes into **both** trails, written
*after* the updates so the update cannot scrub the record of itself, carrying the per-table row
counts and **never** the handle that was removed — writing the value back would undo the erasure in
the very row that records it.

### Why this is not a reversal of ADR-0179

[ADR-0179](0179-both-audit-trails-are-swept-on-a-retention-ceiling.md) considered anonymisation for
the retention ceiling and rejected it, calling it the closest call it had. Both decisions stand,
because they are about different acts:

|                       |              ADR-0179's sweep               |                  This                   |
|-----------------------|---------------------------------------------|-----------------------------------------|
| Scope                 | **every** row past an age                   | the rows naming **one person**          |
| Trigger               | a schedule                                  | a member's request, granted by an admin |
| Effect of anonymising | every old row becomes "an action by nobody" | one person stops being named            |
| Chosen                | delete the row                              | keep the row, drop the name             |

A blanket scrub produces exactly the defect the snapshot was added to prevent, across the whole
trail, forever. A targeted scrub produces what was asked for, and the alternative — deleting the
rows — would take a counterparty's own evidence with it.

## Consequences

- **The append-only invariant is relaxed a second way.** ADR-0038 relaxed it to
  append-and-admin-purge; this adds append-and-admin-anonymise. Both are admin-gated, both are
  audit-logged, and neither can happen without leaving a marker. Stated plainly so the next reader
  of REQ-AUDIT-001 is not surprised: the trail is no longer literally immutable, and it has not
  been since 2026-06-22.
- **[ADR-0010](0010-bank-double-entry-append-only-ledger.md)'s insert-only ledger is amended, by one
  named method.** `ArchitectureTest.bankLedgerRepositoriesMustStayInsertOnly` forbids *any*
  `@Modifying` method on the ledger repositories, and
  `BankTransactionRepository#anonymiseCounterpartyHandle` is one. The guard now exempts **that
  method by name** — every other `@Modifying` method on those repositories still fails the build,
  including a second anonymisation-shaped one added later.

  The exemption is admissible because of what the rule protects: a ledger **correction** must not be
  made by update (corrections are reversal transactions, REQ-BANK-004). This changes no booking fact
  — not an amount, not an account, not a date, not a posting row — only a denormalised display
  column carrying a name. **Approved by @greluc on 2026-09-16**, over the two alternatives: leaving
  the booking history out of the erasure, which would contradict the privacy policy's own naming of
  „Buchungseinträge" as something an Art. 17 request can reach; and a reversal-plus-rebooking pair,
  which would double every affected member's ledger rows and disturb balance history for a name
  change.

- **The bulk updates do not bump `@Version`.** `bank_booking_request` carries an optimistic lock and
  a bulk statement bypasses it, so a request being edited in another session at that exact moment
  could write its own row back with the handle restored. Accepted: the operation is a deliberate
  one-off act by one admin on a leaver's rows, and the alternative — loading and saving every
  request a member ever touched — would 409 against unrelated concurrent bank work. Documented on
  the repository method.

- **Ordering matters and is fixed:** anonymise, then delete. Four of the six updates match on the
  foreign key, which the deletion nulls out; running them afterwards would silently match nothing.

- The sentinel now appears at ten render sites plus the JSON exports. A new surface that prints a
  handle snapshot must route it through `handles.display(...)` or `HandleAnonymisation#humanise`,
  or it will print the raw token. The token is deliberately conspicuous so that failure is visible
  rather than subtle.

- Two columns the privacy policy never mentioned — the handover recipients — are now named in it,
  because they survive a deletion and nothing said so.

- The token is duplicated between the backend `HandleAnonymisation` and the frontend
  `HandleDisplay`, a **mirror pair** with the same cause as `CLIENT_IDS` mirroring
  `ClientAttribution`: the frontend module holds no backend beans. Changing it means changing both.

## Corrected after review — 2026-09-16

This decision shipped describing its set of columns as closed. It was not, and the ADR said so more
confidently than the code justified. Five further columns survived a granted erasure:

| Column                                            | Why it survived                                                                                                                                                                                                                                                      |
|:--------------------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `bank_holder.handle`                              | The custodian registry, whose `user_id` is `ON DELETE SET NULL` and whose `handle` is `NOT NULL` — so it becomes **more** visible after the account is gone, not less. The most conspicuous omission of the five.                                                    |
| `job_order.handle`                                | The order's contact person. Its two sibling *handover* columns were text-matched from the start; this one was not, so the name stayed in the order list and in the audit labels built from it.                                                                       |
| `notification.params`                             | `AccountDeletionRequestedEvent` writes `{"handle":"<name>"}` into a row for **every** administrator, and the deletion only clears the departing member's own inbox. The name sat in every admin's bell for up to the 180-day unread window.                          |
| `audit_event.details`, `bank_audit_event.details` | `details` is a bare `CharSequence`, so nothing forces the `AuditDetails` builder — and the bank services concatenate a handle into it on every booking. `PersonSearchTargets` had exempted both columns *on the strength of* the REQ-AUDIT-001 rule that forbids it. |

And `audit_event.subject_label` was worse than missed: the erasure rewrote `actor_handle` and then
the execution's own audit row put the member's effective name back into `subject_label` on the same
row, six lines later. All four deletion-request events did the same, so a granted erasure left rows
literally half-anonymised for the full 24-month retention.

Three things changed, and only the third of them is a fix rather than a lesson:

1. **The set is eleven columns, and text-matched columns run once per spelling.** `getEffectiveName()`
   is `displayName ?: username`, so a handover typed with the member's username or Discord nickname
   was never reached. All three spellings are passed now — the same correction `HandleScrubber`
   needed, for the same reason.
2. **The four deletion-request events carry a `null` subject label.** REQ-AUDIT-001 limits it to a
   non-personal display label; the member is identified by `actor_user_id` and `target_user_id`,
   which the viewer resolves against the live roster.
3. **The set is gate-enforced.** `HandleErasureCoverage` classifies every column
   `PersonSearchTargets` registers as a place a person is named, and `HandleErasureCoverageTest`
   fails the build unless each one is anonymised, removed with the account, structurally about
   somebody else, or recorded as an admin's manual step with a reason.

The third is the load-bearing one, and the asymmetry it removes is the actual lesson: the person
search had `PersonSearchCoverageTest` from the day it was written and never drifted. This set had a
comment, and drifted before the branch was even merged. A registry without a gate is a comment.

**The registry is honest about how much stays manual.** Rather more than half the columns are
prose somebody else typed, where the name sits inside a sentence and no mechanical rule can rewrite
it without either corrupting the sentence or missing the mention. That residue is why the admin
Personensuche exists and why `docs/privacy/data-subject-requests.md` requires an admin to walk its
hits — but it was previously implicit, and an erasure whose manual half is implicit is an erasure
somebody will believe is complete.

## Alternatives considered

- **Substring-replace the handle inside `audit_event.subject_label` rather than matching the whole
  value.** Rejected: a label that merely *contains* the handle is a job-order title naming that
  order's contact person, which is a different person on a row about a different act. Rewriting it
  would erase somebody who did not ask.
- **Fix the two bank services and keep the `details` columns exempt from the search.** Rejected as
  the *whole* answer, though the writers should indeed be fixed: the rows already written carry the
  names, and a registry that assumes a rule nothing enforces is how the exemption came to be false
  in the first place.
- **Delete the notification rows instead of rewriting their payload.** Rejected: the rows are other
  administrators' inboxes, and removing an entry an admin has not read yet loses the fact that a
  request was raised at all. Replacing the name inside the payload leaves the notification
  readable and the JSON parseable.
- **Delete the rows that name the member.** Rejected by @greluc: it tears holes in a record whose
  worth rests on being complete, and a bank posting's counterparty losing their own evidence line is
  a cost to somebody who did not make the request.
- **Do nothing in code; handle a granted wish by hand.** The option @greluc was offered first and
  declined. It would have left the policy promising something only a manual database write could
  deliver — the same shape of gap this whole piece of work exists to close.
- **`NULL` instead of a sentinel, relaxing the four `NOT NULL` constraints.** Rejected: it trades a
  permanent structural guarantee for a one-off convenience, and afterwards an absent handle would be
  indistinguishable from a bug.
- **A German or English word as the stored value.** Rejected: it breaks the i18n rule at the one
  place a value is written once and read in two languages.
- **Translate the sentinel in JSON exports too.** Rejected: an export is evidence, and two exports
  of the same rows taken by admins with different locales must not differ.
- **Restrict the scope to the two audit trails** (the literal reading of the earlier decision).
  Rejected by @greluc: it would leave the member named in the booking history and on the handover
  receipts while the record says their request was granted.
- **Include the org-unit name beside an anonymised counterparty.** Kept, deliberately: an org unit
  is not a natural person, and removing it would degrade the booking's meaning for no gain.

