# ADR-0156 — The app carries the bank's direct booking, and an exclusion may not cite a fact nobody re-checks

- **Status:** Accepted
- **Date:** 2026-09-03
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [REQ-API-009](../specs/api-conventions.md) · [REQ-SEC-037](../specs/security-and-access.md) · REQ-BANK-009 · REQ-BANK-047 · app spec `REQ-APP-BANK-007` / `REQ-APP-BANK-016` (basetool-android) · [ADR-0109](0109-krt-middle-band-bankleitung-and-over-ceiling-auto-request.md) · [ADR-0135](0135-a-default-deny-allow-list-in-front-of-the-api.md) · [ADR-0136](0136-external-contract-set-for-shipped-clients.md) · **Runbook:** [`docs/API_VHOST_ROLLOUT_RUNBOOK.md`](../API_VHOST_ROLLOUT_RUNBOOK.md) → *Phase O*

## Context

The API vhost is a default-deny allow-list (ADR-0135): a path the Android app calls is refused with
`404` unless a rule names it. Phase L admitted the bank-**staff** surface and, in the same comment
block, recorded three deliberate exclusions. One of them read:

> `/bank/deposits`, `/bank/withdrawals`, `/bank/transfers`, `/bank/transfer-fee-rate` — the direct
> booking forms. No artboard draws them; a booking that had no request stays a browser act.

**The first clause was untrue when it was written, and the second was derived from it.** Design
chapter 12 **artboard 9** draws the sheet — „Direktbuchung — Verwaltung (Ein/Aus/Um)" — and design
round 8 settled the question in the artboard's own handoff note:

> die Direktbuchung **BLEIBT**, weil sie den Fall deckt, für den niemand einen Antrag stellt
> (Bargeld-Übergabe im Spiel, Korrektur einer Fremdbuchung). Sie liegt aber ausschließlich in der
> Verwaltung, nie in der Mitglieder-Sicht.

`REQ-APP-BANK-016` specifies it, the app shipped it — one sheet with three modes, holder required in
all three, live „Stand nach Buchung", the no-second-approval warning above the CTA — with unit tests
and every acceptance criterion met. So the four rules were the only thing standing between a
finished feature and the members using it, and every attempt was rendered in the app as the generic
„Konnte nicht gespeichert werden.".

Three further things were wrong in the same area, each found only because the exclusion was
questioned.

### The exclusion was self-reinforcing

`edge-deny-probe.yml` asserted `404` on all four paths nightly. A probe written from an expectation
turns a mistake into a green light: for as long as the belief held, the evidence agreed with it. The
runbook stated the same exclusion in five places and the German user wiki told members the direct
booking stays in the browser, so a reader arriving from any direction met a consistent, wrong story.

### The client had invented a stricter rule than the endpoint

All four endpoints gate on `hasRole('BANK_EMPLOYEE')`; the three bookings add a **per-account**
grant — `canDeposit` / `canWithdraw` / `canTransfer`, with management and admin unrestricted
(`BankSecurityService.hasCapability`). The web has never asked for more: its CTA appears whenever at
least one active account is visible, and the per-account half is the backend's 403.

The app instead locked its entry on `canManageBank`, following artboard 9's state list („403 (Rolle
Bank-Management fehlt) = gesperrt-antippbar schon am Einstieg"). That locked out precisely the
caller the Grants tab exists to create: a plain Bankmitarbeiter holding `can_withdraw` on one
account, who may book in the web and was refused in the app. It also contradicted
`REQ-APP-BANK-007`'s own acceptance box, which says the app derives a role flag as a hint and never
as a gate (ADR-0011).

### A `202` was being reported as a completed booking

`POST /bank/withdrawals` and `/bank/transfers` do not always book. Over the KRT employee ceiling the
server does not refuse — it files the attempt as a band-routed approval request and answers **202**
with a `pendingRequest` where a booking carries a `transaction` (REQ-BANK-047, ADR-0109). The app
sent both through a helper that treats any 2xx as success and discards the body, so on a `202` the
sheet closed, the balance had not moved, and nothing said why. Invisible while the edge answered
`404`; a live defect on a money path the moment it did not.

## Decision

**1. The four direct-booking paths are admitted** — `POST /api/v1/bank/deposits`,
`/api/v1/bank/withdrawals`, `/api/v1/bank/transfers` and `GET /api/v1/bank/transfer-fee-rate`, as
four exact allow-list lines (runbook phase O), frozen in `ExternalContractTest` per REQ-API-009 and
pinned in `ApiVhostAnonymousSurfaceTest` per REQ-SEC-037. The nightly probe asserts `401` on them
instead of `404`.

**2. The direct booking is gated by the endpoint's own rule and nothing else.** The app's entry
carries no role check: the Verwaltung scope is already unreachable without `bankEmployee`, and the
per-account grant is a fact about the account picked inside the sheet, so it is decided per call by
the backend and shown as the 403 it is. „Konto anlegen" beside it keeps its Bank-Management lock,
because *that* endpoint gates on it.

**3. Artboard 9's state list is overridden on this one point.** Its „403 (Rolle Bank-Management
fehlt)" does not match the endpoint it describes. The endpoint is the authority; the deviation is
recorded in the app's deviation register rather than followed.

**4. The `202` is a distinct outcome in the client.** The two ceiling-bearing writes read
`BankBookingOutcomeDto` instead of discarding it, and a filed attempt says so — „Zur Freigabe
eingereicht … Nichts wurde gebucht". This shipped **before** the allow-list change, and the runbook
says so: pasting phase O onto a build that predates it would make a silent money-path defect live.

**5. An exclusion in the allow-list must cite a decision, not a fact.** Where a rule is kept out
because somebody decided so — `/api/v1/bank/admin/**` is web-only by owner decision — the reason
stays true until the decision changes, and the reason names the decider. Where a rule is kept out
because of a *fact* about the product („no artboard draws it", „the app does not call it"), the fact
can change without anybody revisiting the comment, and a probe asserting the consequence will report
the stale state as healthy. Such exclusions must name the fact **and** what would falsify it, and
they are re-checked whenever the surface they describe is touched.

## Consequences

- The Verwaltung's direct booking works in production once the block is pasted — for every bank
  employee with the matching per-account grant, not only for Bank-Management.
- The over-ceiling case is now visible to the member instead of looking like a completed booking.
  This is a behaviour change on a money path and the reason point 4 precedes point 1 in delivery
  order.
- `canManageBank` still gates account creation, the KPI totals band and the Grants tab. Only the
  booking entry loses it.
- The five runbook statements, the two German wiki pages, `REQ-APP-BANK-007`'s acceptance box, the
  stale comment in `ExternalContractTest` and the knowledge base's parity table were corrected in
  the same unit of work. A single surviving copy of a retracted exclusion is what kept this one
  alive: `REQ-APP-BANK-007` retracted it in prose on 2026-08-30 and left one checked acceptance box
  standing, and that box is what the runbook and the probe still agreed with.
- **Not fixed here, found beside it:** the shipped Freigabe-Limits writes
  (`/api/v1/org-units/bank/accounts/{id}/approval-limit/**`, `REQ-APP-BANK-017`) are admitted by no
  rule and are not listed among the deliberate exclusions either — the same class of gap, a
  different feature, and its own change.

