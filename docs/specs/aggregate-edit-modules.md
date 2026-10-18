> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-18.
> **Owner area:** SEC/ORDERS/MISSION · **Related ADRs:** [ADR-0287](../adr/0287-mission-and-job-order-each-edit-through-one-edit-module.md),
> [ADR-0236](../adr/0236-each-domain-owns-an-access-policy-over-the-scope-kernel.md)

# Mission and job-order edit modules

## Context & goal

Every Mission timeline, roster and structure mutation and every Job Order edit used to repeat the
same recipe by hand: load the aggregate, check the version, mutate, save, record the audit event,
publish the notices, project to a DTO. The business access check lived only in the controller's
`@PreAuthorize` SpEL, so a caller that reached the service without an HTTP request met no gate at
all. This spec pins the rule that one edit module per aggregate owns that recipe and the access
check on the loaded aggregate, and the version rule of a job-order edit.

## Requirements

### REQ-SEC-085 — The edit module authorises an edit on the loaded aggregate

Every Mission timeline (steps, goals), roster (sign-up, participant row, check-in and check-out,
payout preference, removal, party lead, co-managers) and structure (units, crews) mutation runs
through `MissionEditor`, and every Job Order edit (status, blueprint counting, full and requester
edits, responsible-unit reassignment, material and stock unlinks) runs through `JobOrderEditor`.
Two order edits keep their own gate and lock and stay outside it: the priority reorder (pessimistic
lock on the priority sequence) and the assignee edits (the assignee edge's own version).
The edit module loads the aggregate, then decides the caller's access on the loaded aggregate
through the module's access policy (`MissionAccessPolicy.canEdit`, `JobOrderAccessPolicy.canEdit`)
before any version check or mutation. A refused caller gets HTTP 403, a caller without an
authentication is refused, and an unknown aggregate is HTTP 404 for every caller. The controller
keeps a coarse role gate of its own (ADR-0236 §6): `isAuthenticated()` for the mission manage
gate, member-or-above for the read and participant gates, the LOGISTICIAN role (or LOGISTICIAN,
OFFICER, ADMIN for the unlinks) for the job-order edit gate, `isAuthenticated()` for the requester
gate. Audit event types and payloads are unchanged (REQ-AUDIT-001); a mutation that changes
nothing records nothing.

**Acceptance**

- [ ] A denied caller gets 403 before the version is checked, and nothing is mutated or audited.
- [ ] A call without an authentication, as from a listener, is refused by the same gate.
- [ ] A successful edit records exactly one audit event, then publishes its notices.
- [ ] An edit of an unknown mission or order is 404, not 403.

**Enforced by:** `MissionEditorTest`, `JobOrderEditorTest`, `MissionAccessPolicyGateTest`,
`TenancyGuardTest` (the handlers listed as service-gated), `AuthorizationMatrixTest` ·
**Code:** `mission/internal/MissionEditor`, `MissionGate`, `MissionAccessPolicy`,
`joborder/internal/JobOrderEditor`, `JobOrderGate`, `JobOrderAccessPolicy`.

### REQ-ORDERS-046 — A job-order edit that carries a version requires it

A job-order edit whose request carries the order's optimistic-lock `version` — status change,
blueprint counting, the full material and item edits and the two requester edits — requires that
version: a missing version is HTTP 409 like a stale one (`OptimisticLock.check`). The decision is
made once, in `JobOrderEditor`, not per method. Edits whose request carries no version
(responsible-unit reassignment, the material and stock unlinks) are not version-checked. Because
the version is checked before the edit's own preconditions, a stale or missing version answers 409
even where the edit would otherwise be refused with 400 (a material order sent to an item edit).

**Acceptance**

- [ ] An update whose `version` is absent answers 409 and changes nothing.
- [ ] A stale `version` answers 409, no audit event is recorded and no notice is published.

**Enforced by:** `JobOrderEditorTest` · **Code:** `JobOrderEditor#edit`.
