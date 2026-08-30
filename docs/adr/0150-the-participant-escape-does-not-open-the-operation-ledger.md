# ADR-0150 — The participant escape opens the operation, not its ledger

> **Status:** Accepted · **Date:** 2026-08-30 · **Deciders:** @greluc
> **Related:** ADR-0006 (the participant visibility escape it narrows), `REQ-ORG-003` (Operation's
> two read-only escapes), `REQ-ORG-021` (this decision, as a requirement), `REQ-SEC-009` (the
> anonymous mission surface that makes the escape self-issuable), `docs/specs/org-unit-tenancy.md`

## Context

`OwnerScopeService.canSeeOperation` admits two very different callers:

1. somebody inside the operation's org-unit scope (or the ownerless-leadership case), and
2. **any authenticated user who participated in one of the operation's linked missions.**

ADR-0006 granted the second escape and rejected the broader alternative — "make every operation
visible to all organisation members" — on the grounds that *participation is the right, minimal
key*. That reasoning holds only while participation is **granted**. It is not: `POST
/api/v1/missions/{id}/join` is gated on `isAuthenticated() and canSeeMission(#id)`, and
`canSeeMission` is `true` for **any non-internal mission of any org unit** — the public sign-up
surface of REQ-SEC-009 is the product, not an oversight. So a member of Staffel A mints the key for
a public mission of Staffel B in one request.

The 2026-08-30 API security audit followed that through to what the key then unlocks. Five endpoints
hung off `canSeeOperation`, and `GET /api/v1/operations/{id}/payouts` returns, per participant across
every mission of the operation, the callsign, participation percentage, `personalExpenses`,
`shareAmount`, `donatedAmount`, `transferFee`, `payoutAmount` and the confirming officer — with no
per-caller filter behind the gate. `/finances`, `/finances/{missionId}` and `/finance-summary` add
the ledger itself, including the names and financial results of the operation's **internal** child
missions, which `canSeeMission` would refuse the same caller directly.

Two knobs were available: narrow the escape, or narrow what the escape carries.

## Decision

**Narrow what it carries. The escape stays; the ledger does not follow it.**

- A second predicate, `canSeeOperationLedger`, answers `canSeeOperation` **without** the participant
  branch — scope, or the ownerless-leadership case, and nothing else.
- `GET /{id}/finances`, `/{id}/finances/{missionId}` and `/{id}/finance-summary` gate on it. These
  are org-unit financial records with no meaningful per-caller projection.
- `GET /{id}/payouts` keeps `canSeeOperation`, and a caller who passes **only** through the escape
  receives **their own row and nothing else**; `totalDonations` is recomputed over that reduced list,
  so the operation-wide aggregate does not leak either.

## Consequences

- A participant of a foreign unit's operation keeps exactly what ADR-0006 meant to give them: they
  can see the operation, and they can see their own payout. That was the sentence the vault's
  `Scoping` note already used — "participants can view the operation and **their** payout" — and it
  is now true.
- A cross-unit participant loses the operation's finance tab. That is the intended tightening, and
  it is the only user-visible behaviour change here.
- Self-enrolment stays open. Closing it would break anonymous mission sign-up, which is a product
  decision of its own; this ADR deliberately does not touch it.
- The cost of the split is a second predicate that must stay in step with the first. It is defined
  in terms of the same two branches, immediately above `canSeeOperation` in `AccessGateService`, so
  the pair reads as one decision rather than two.

## Alternatives considered

- **Remove the participant escape for the money endpoints entirely** (403 rather than a reduced
  payload). Simpler, and it costs a legitimate participant sight of their own payout — the one thing
  ADR-0006 granted the escape for.
- **Require an invitation for the escape** (participation must be added by a manager, not claimed).
  This is the honest fix for the escape's semantics, but it collides head-on with anonymous and
  self-service sign-up, which is a far larger product change than the leak justifies.
- **Leave it and document it.** Rejected: the payload includes other members' callsigns paired with
  aUEC amounts, and the key costs one request.

