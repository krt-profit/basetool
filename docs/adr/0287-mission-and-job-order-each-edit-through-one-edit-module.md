# ADR-0287 — Mission and job order each edit through one edit module

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** implements [ADR-0236](0236-each-domain-owns-an-access-policy-over-the-scope-kernel.md)
  §6 for the mission and job-order edits ·
  [ADR-0286](0286-the-mission-controller-calls-the-mission-section-services-directly.md) ·
  [ADR-0062](0062-joborder-service-split-into-facade-and-collaborators.md) · `REQ-SEC-085`,
  `REQ-ORDERS-046`, `REQ-AUDIT-001`, `REQ-ORG-018` · issue #2733

## Context

Every Mission timeline, roster and structure mutation and every Job Order edit re-implemented one
recipe by hand: load, check the version, mutate, save, audit, publish the notice, project. The
business access check lived only in `@PreAuthorize` SpEL on the controller: the SpEL gate loaded
the aggregate itself, the service loaded it again, and a caller without an HTTP request met no
gate. `MissionSecurityService` and `MissionAccessPolicy` split the mission's gates over two beans.
Each job-order method chose its own version semantics, and all of them skipped the 409 when the
client omitted the version.

## Decision

1. **One edit module per aggregate.** `MissionEditor` (`editSection` for a versioned section,
   `edit` for a row-locked or unlocked part) and `JobOrderEditor` (`edit` for a versioned edit,
   `editUnversioned` and `editWithoutProjection` for edits whose request carries no version) own
   the order load → access check on the loaded aggregate → version check → mutation → save →
   audit → notices → projection. An operation supplies only its mutation, which returns the audit
   event (`MissionEdit`, `JobOrderEdit`) or "unchanged".
2. **The access check runs on the loaded aggregate, for every caller.** The module asks its
   policy (`MissionAccessPolicy.canEdit` with a `MissionGate`, `JobOrderAccessPolicy.canEdit`
   with a `JobOrderGate`) and refuses with 403; a call without an authentication is refused. The
   controller keeps a coarse role gate that stands on its own (ADR-0236 §6). The priority reorder
   and the assignee edits keep their own gates and locks and stay outside the module. The moved handlers
   are listed as service-gated in the tenancy guard.
3. **One mission access policy.** `MissionSecurityService` is merged into `MissionAccessPolicy`;
   its method bodies move verbatim. The SpEL bean name `missionSecurityService` is gone; the
   remaining id-based gates are `@missionAccessPolicy`. `canManageManagers` was the manage rule
   under another name and is folded into it.
4. **A job-order edit that carries a version requires it** (`OptimisticLock.check`): a missing
   version is a 409 like a stale one (REQ-ORDERS-046).
5. **The section bump stays as it is.** `MissionEditor` calls
   `MissionSectionVersions.enforceSectionVersion`; the per-section counters, the DB-enforced
   atomic bump and the lock scope are unchanged.
6. **The mutation persists what it touches on the job-order side.** Job-order edits differ in
   their flush, bulk-unlink and re-fetch needs, so the module audits the order the mutation
   returns rather than saving one itself; a mission section edit saves the mission row in the
   module.

## Consequences

- The recipe is tested once per aggregate (`MissionEditorTest`, `JobOrderEditorTest`); the
  per-operation tests assert their mutation.
- An edit of an unknown mission or order answers 404 for every caller; a non-admin used to get
  403 from the SpEL gate.
- A client that sends a job-order edit without its version gets 409; the frontend always echoes
  the version. The version check runs before an edit's own preconditions, so a stale version on a
  wrong-type request answers 409 rather than 400.
- Mission notices are still published inside the mutation, before the audit event; the job-order
  notices are published by the module after it. All of them are after-commit.
- The audit label of a job order has one source, `JobOrderAuditLabel.of(JobOrder)`.

## Alternatives considered

- **Keep the SpEL gates and add the module check beside them** — two gates for one rule, which
  is the duplication ADR-0236 removes; a later change edits one and leaves the other.
- **Keep `checkOptionalClient` for job orders** — a client that drops the version silently
  overwrites a concurrent edit.
- **One generic editor for all aggregates** — the mission's section counters and the job order's
  `@Version` with flush and re-fetch needs share no version step worth abstracting.
