# ADR-0022 — Bank booking-request notifications via `ACCOUNT_GRANT` + `EVENT_RECIPIENT` selector kinds

- **Status:** Accepted
- **Date:** 2026-06-17
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-BANK-026 · REQ-NOTIF-011 · REQ-NOTIF-007 · ADR-0015 · issue #666

## Context

Epic #666 (owner-requested addition) needs the bank staff who can act on a new booking request
to be notified: the **bank management** and the **employees granted on the target account**.
The notification system already provides a data-driven recipient rule engine (ADR-0015,
REQ-NOTIF-007) with `SPECIFIC_USER`, `ROLE` and `ORG_RELATIVE_ROLE` selectors resolved by
`RecipientResolutionService`. "Bank management" maps cleanly onto a `ROLE` selector, but
"employees granted on *this* account" cannot be expressed: the account is **per-event** (it
differs for every request) and no existing selector reads it, mirroring how a `JobOrderCreated`
event's org unit is per-event. The owner subsequently asked to also notify the **requesting
officer/lead** when their request is **confirmed or rejected** — again a per-event recipient (the
requester differs for every request) that no existing selector addresses; the actor of a decision
event is the deciding employee, not the recipient.

## Decision

We will add one new selector kind, **`ACCOUNT_GRANT`**, that resolves to every user holding a
`bank_account_grant` on the **account carried by the event**. The event contract gains a
`default UUID contextAccountId()` (returning `null` for non-bank producers), exactly paralleling
the existing `contextOrgUnits()` used by `ORG_RELATIVE_ROLE`. `RecipientResolutionService` grows
a `resolveAccountGrantHolders(accountId)` backed by `BankAccountGrantRepository.findByAccountId`.
A seeded default rule (V160) maps `BANK_BOOKING_REQUEST_CREATED` to a notification with a
`ROLE` selector (`BANK_MANAGEMENT`) and an `ACCOUNT_GRANT` selector, excluding the requester.

For the decision direction we add a second selector kind, **`EVENT_RECIPIENT`**, that resolves to
the single user the event is directed at. The event contract gains a `default UUID
contextRecipientSub()` (returning `null` by default); the confirm/reject events return the
requester's `sub` while their `actorSub()` is the deciding employee. Seeded rules (V161) map
`BANK_BOOKING_REQUEST_CONFIRMED` / `BANK_BOOKING_REQUEST_REJECTED` to same-named notifications, each
with a single `EVENT_RECIPIENT` selector. No schema migration is needed for the selector table —
both kinds read their context from the event, not from a selector column, and the type/event-type
columns carry no CHECK (open enums).

## Consequences

- The new use case ships as **rule data + one selector kind**, with the existing
  after-commit/async pipeline, SSE push and admin rule editor reused unchanged.
- The rule stays admin-editable at runtime, like UC1.
- Cost: the generic `RecipientResolutionService` now depends on `BankAccountGrantRepository` — a
  deliberate, narrow coupling consistent with its existing dependencies on `UserRepository` /
  `OrgUnitMembershipRepository`. (The notification engine is not a `Bank*` class and does not
  touch `OwnerScopeService`, so no bank ArchUnit rule is affected.) "Appropriately authorized for
  the account" is interpreted as *holds a grant on it*; the per-action capability flag still gates
  who may actually confirm (REQ-BANK-023).
- `EVENT_RECIPIENT` is a **general** "notify the subject of this event" primitive (it reads only the
  generic `contextRecipientSub()`), reusable by any future event with a single directed recipient —
  no bank coupling, unlike `ACCOUNT_GRANT`.
- The admin notification-rule editor (`admin/notification-rules.html`) lists both new selector kinds
  and the three `BANK_BOOKING_REQUEST_*` event/notification types, so a seeded rule edits correctly
  and the kinds are selectable; both read their context from the event, so they need no extra
  per-selector fields in the editor.

## Alternatives considered

- **Resolve recipients inside the bank service and create notifications directly** — rejected:
  bypasses the data-driven engine (REQ-NOTIF-007), hardcoding recipients and losing the admin
  editability and the shared push/inbox pipeline.
- **A static account id stored on the selector** — rejected: the account is per-event, so a
  static selector value cannot work; reading it from the event matches the `ORG_RELATIVE_ROLE`
  precedent.
- **Filter grant holders by the request's capability (deposit→`can_deposit`)** — deferred:
  bakes bank-capability semantics into the generic engine; notifying all grant holders is simpler
  and the confirm gate already enforces capability.
