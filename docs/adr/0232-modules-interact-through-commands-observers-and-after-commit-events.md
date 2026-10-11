# ADR-0232 — Modules interact through commands, observers and after-commit events

- **Status:** Accepted — implementation pending (plan Phases 1 to 4)
- **Date:** 2026-09-29
- **Deciders:** @greluc (the plan decided on 2026-09-29)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §5.3, §6 (red lines),
  §7.3 · [evidence: cross-domain write families](../modularisation/evidence.md#cross-domain-write-families) ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) ·
  [ADR-0233](0233-module-boundaries-are-enforced-by-archunit-and-spring-modulith-in-test-scope.md) ·
  spec REQ-AUDIT-001 · arc42 §8.8, §8.14

## Context

Once the backend is cut into modules (ADR-0231), every cross-domain call needs a defined shape. The
audit of 2026-09-29 found sixteen cross-domain write families; twelve of them share the caller's
transaction — row and advisory locks, `MANDATORY` hops, foreign-key order — and every audited
mutation writes its audit row in the business transaction (206 same-transaction `record` calls).
REQ-AUDIT-001 requires that an audit-insert failure rolls the mutation back. An event-first design
would break exactly these invariants: an after-commit listener runs after the locks are gone and
cannot roll anything back.

## Decision

We will use **three interaction mechanisms, chosen per interaction by one rule: if an invariant must
hold when the transaction commits, the reaction runs inside the transaction.**

1. **Command/query API.** A module that uses another module's capability calls that module's `api`
   interfaces. Writes are `@Transactional(propagation = MANDATORY)`, so they join the caller's
   transaction and fail loudly without one. Only the owning module constructs, writes or deletes
   its aggregates; no foreign constructor call and no foreign repository write. Examples: job
   order, refinery and exchange → `inventory.api.StockCommands`; operation →
   `mission.detachFromOperation`; exchange → the hangar and blueprint write APIs.
2. **Observer SPI owned by the lower module.** When a lower module's change obliges a higher module
   to react atomically, the lower module declares an observer interface in its `api`, the higher
   module implements it, and the lower module calls every implementation synchronously in the same
   transaction. Every implementation is `MANDATORY`. Examples: `StockChangeObserver` (the
   Materialbörse offer ratchet), `MembershipChangeObserver` (org chart mirror, inventory re-stamp,
   bank responsibility), ship deleted, job type designated, stock sold for a mission.
3. **After-commit event** (`@TransactionalEventListener` plus `@Async`, as today) only where the
   reaction may happen later or fail on its own: notification fan-out, registration and approval
   mail, the exchange departure, the default-blueprint grant (self-healed by its task), the bank
   holder reconciliation. Listeners never write audit rows and never rely on the request's security
   context. An event type delivered in-transaction to an observer is never also consumed by a
   `@TransactionalEventListener`.

Two fixed points:

- **Audit is always a direct, synchronous call** to `audit.api.AuditRecorder` (`MANDATORY`), never
  an event. `AuditEventType`, `AuditDomain` and `AuditDetails` move into `audit.api` with their
  names unchanged, because the names are persisted, alerted on and pinned by the frontend.
- **Cross-cutting SPIs invert the platform's upward edges:** `ActorHandleResolver` (identity, the
  audit actor snapshot), `RetentionParticipant` (bank), `RecipientDirectory` (bank, identity,
  orgunit — breaks the bank ⇄ notification cycle), `LiveSyncTopicAuthorizer` (per module, reusing
  its read gate), `ActiveOrgUnitProvider` (scope → logging MDC), and the GDPR participants
  (`UserErasureParticipant` with ordered phases, `UserReassignmentParticipant`,
  `HandleAnonymisationParticipant`, `PersonalDataExportSection`, `PersonMentionSource`) that run
  `MANDATORY` inside the orchestrator's one transaction, while the deletion and anonymisation audit
  rows stay in the orchestrator.

Red lines no step crosses: audit never becomes an after-commit event; the scope check of a write
never moves after its lookup (no existence oracle, REQ-INV-032); no event payload carries free text
or personal data; events belong to their publishers' `api.events` packages.

## Consequences

- Module APIs are synchronous in-process contracts; lock order, `FORCE_INCREMENT` version echoes
  and bulk-update-after-loop rules keep working because the callee runs in the caller's
  transaction.
- The interaction style is checkable: plan guard G-12 requires every observer implementation to be
  `MANDATORY`, forbids an `@TransactionalEventListener` or `@Async` method from calling the audit
  recorder, and forbids listeners from using the request-bound scope helpers.
- A module that grows a new reaction to a lower module's change adds an observer implementation,
  not a dependency of the lower module on it.
- Event records that carry names, e-mail addresses or free text today are reduced to ids when they
  move to their publishers.

## Implementation

- **2026-10-11, GDPR participants, slice a (erasure).** `UserErasureParticipant` is owned by
  `platform.api`, not by identity as the plan first wrote: `notification` and `audit` (rank 1)
  are participants and may not depend on identity (rank 3). It is an `@ObserverSpi`; its
  phases run in the order `DELETE`, `REASSIGN`, `UNLINK`, which is the order the deletion has
  always issued its statements in. Eleven implementations in ten modules replace the
  orchestrator's direct repository calls, `MemberEvaluationErasure`, `PersonalInventoryErasure`
  and `StockChangeObserver.beforeUserPurge`; the deletion audit rows stay in
  `UserDeletionService`. See plan §7.6.
- **2026-10-11, GDPR participants, slice b (handle anonymisation).** `HandleAnonymisationParticipant`
  is owned by `platform.api` for the same reason (audit is rank 1). It is an `@ObserverSpi`; three
  implementations (audit, bank, job order) replace the orchestrator's direct repository calls and
  `service.BankHandleSnapshots`. The activity trail's marker row stays in
  `HandleAnonymisationService`; a participant records its own marker afterwards, which keeps the
  bank trail's. See plan §7.6.
- **2026-10-11, GDPR participants, slice c (export).** `PersonalDataExportSection` is owned by
  `platform.api` for the same reason and is a plain declaration SPI, not an `@ObserverSpi`: a
  participant returns `Section` records (position, key, legal basis, statement, scrubbed and
  exempted columns) and runs nothing. Sixteen implementations, one per owning module, replace
  `DataExportSections`; the reading order is a global `position` on each section, because a
  module's sections are not contiguous in it. `privacy.internal.PersonalDataExportRegistry`
  orders them and refuses a duplicate key or position. See plan §7.6.

- **2026-10-11, GDPR participants, slice e (account merge).** `UserReassignmentParticipant` is
  owned by `platform.api` for the same reason. It is an `@ObserverSpi`; eighteen implementations in
  seventeen modules replace the two lists of `UserAccountMergeService`: each declares the columns
  that follow the member, with their unique-key remainder, and those that stay with the act. The
  statements of the merge run in one engine, `platform.api.OwnedRowsMover`, and the bank refuses
  two ledgers through the SPI's `assertMergeable`. The `USER_MERGED` audit row stays in the
  orchestrator. See plan §7.6.

## Alternatives considered

- **Events for every cross-module reaction** — breaks atomicity and audit completeness for the
  twelve families that need the caller's transaction.
- **Audit through events** — REQ-AUDIT-001 forbids a mutation without its audit row, and an
  after-commit listener cannot roll the mutation back.
- **A durable outbox (Spring Modulith's event publication registry)** — rejected in ADR-0233; the
  four after-commit families do not need one.
- **Direct calls into another module's services and repositories, as today** — the coupling this
  plan exists to remove.
