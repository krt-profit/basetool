# ADR-0353 — An over-limit confirm stays an attestation that names the approver

- **Status:** Accepted
- **Date:** 2026-11-08
- **Deciders:** @greluc (also the Bankleitung)
- **Amends:** [ADR-0045](0045-bank-user-transfers-and-per-account-approval-limits.md) — the
  employee's confirm of a flagged request
- **Related:** spec [`bank.md`](../specs/bank.md) (REQ-BANK-041, REQ-BANK-054) ·
  [`bank-notifications.md`](../specs/bank-notifications.md) (the REQ-NOTIF bank rules) ·
  [ADR-0015](0015-notification-data-driven-rule-engine.md) ·
  [ADR-0109](0109-krt-middle-band-bankleitung-and-over-ceiling-auto-request.md) ·
  issues #3115, #3116, #3152, #3153

## Context

A withdrawal or transfer request above the requester's approval limit is flagged
`requires_owner_approval` and names its `required_approver`: the account's responsible holders, the
Bankleitung on the middle KRT band, or the Organisationsleitung above T2 (REQ-BANK-041/-047). The
approver may grant the approval in the app (`owner_approval_granted`). The bank employee then
confirms with the mandatory checkbox „Freigabe durch Kontoverantwortlichen erfolgt".

The confirm checks only that checkbox. An employee can therefore confirm a flagged request whose
approval was given outside the app, for example in a voice call, and nothing records who gave it.
The queue keeps the muted „Über Limit" marker on such a request forever.

The approvers also learn about a request only through the generic „new request" notice
(`BANK_BOOKING_REQUEST_CREATED`). It goes to the Bankleitung, the employees granted on the account
and the account's responsible holders. It never says that an approval is needed. On the KRT account
it reaches every OL member, also for requests the OL never has to approve.

The bank exchange (#2980) will show members a bank-staff queue with „waiting for your approval". That
needs a clear meaning for an open approval.

## Decision

1. **The confirm of a flagged request stays an attestation.** It does not need an approval recorded
   in the app.
2. **An attested confirm names the approver.** When `requires_owner_approval` is set,
   `owner_approval_granted` is not, and the employee confirms on the checkbox, the bank-internal
   note `staffNote` must be non-blank after trimming. It is free text that names who gave the
   approval. Otherwise the confirm answers `400 BANK_APPROVER_NOTE_REQUIRED` and books nothing. With
   a recorded approval the note stays optional.
3. **The confirm dialog asks for it.** On the attested path the note field becomes mandatory and
   shows the hint „Wer hat freigegeben?" (DE/EN), updated in place when the dialog opens.
4. **The audit is unchanged.** `BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED` carries no free text
   (REQ-AUDIT-001). The note stays on the request and the booked transaction (REQ-BANK-054) and
   never leaves through the exchange.
5. **A new notice, „approval needed", goes only to the required approver.** It is a data-driven
   rule (ADR-0015) with a new `REQUIRED_APPROVER` selector that resolves from `required_approver`:
   the responsible holders when the request is over their limit, the Bankleitung on the middle KRT
   band (T1 < amount ≤ T2), the Organisationsleitung above T2.
   - It clears on approval, rejection, cancellation and confirmation.
   - A correction by the requester (REQ-BANK-056) decides it again, including a change of the
     approver. A change of responsible holder moves it, as open notices move today (REQ-NOTIF-023).
   - One notice per person and request: for the required approver it replaces the generic „new
     request" notice on that request. The granted employees and the Bankleitung keep the generic
     notice on requests they do not have to approve. After the approval nothing replaces it, because
     confirming belongs to the bank staff.
   - KRT-account requests no longer raise the generic notice for the OL; the OL gets only „approval
     needed" above T2. The generic notice for other accounts' responsible holders is unchanged.
   - Notifications are not audited, so the notice has no audit event.
6. **„Waiting for your approval" means an open in-app approval.** The bank-staff queue of the
   exchange lists exactly the requests that carry the „approval needed" notice for the member.
   Nothing more is enforced: such a request may still be confirmed attested in the browser, with
   the approver named. The exchange neither approves nor books.

## Consequences

- Every confirmed over-limit request names its approver: in the app as `owner_approval_granted_by`,
  or in the employee's note.
- A client that confirms a flagged request without in-app approval must send a non-blank
  `staffNote`, or it gets `BANK_APPROVER_NOTE_REQUIRED`.
- The notice of point 5 is built in its own slice (#3153). It amends REQ-BANK-041 and the REQ-NOTIF
  bank rules there.
- An approver sees which requests wait for them without reading every new request. The OL no longer
  gets the KRT-account requests below T2.
