# ADR-0365 — Domain operations and HTTP families have focused owners

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** Lucas Greuloch
- **Related:** REQ-MOD-001, REQ-DATA-022, REQ-XCH-045; issues #3297–#3299, #3306–#3311, #3314

## Context

Inventory checkout, org-stock writes and mission HTTP handlers combine independent operations.
Duplicated construction and formatting rules require parallel edits. Shared entity caches expose
mutable state across consumers, and separate architecture imports retain equivalent bytecode graphs.

## Decision

Keep domain modules and public contracts while narrowing their internal owners. Inventory has
separate merge, bulk-rebooking and transfer-notice services. Org-stock planning and execution depend
on one sealed plan model below the change-set adapter. Its wire-name enum owns supported operations;
wire validation retains the public string field. Mission controllers own core, schedule, roster,
unit/crew or step/objective operations and share caller-specific response redaction.

Personal blueprint construction and registered Bank counterparty resolution each have one owner.
Bank reports share counterparty cell formatting. Role and squadron caches contain detached immutable
read records and preserve their existing write invalidation. Equivalent architecture imports share
one class-loader-local graph, while planted fixtures remain independent. Test graphs use constructors.

## Consequences

Existing transaction boundaries, lock order, audit, notices, live sync, field versions, tenancy and
permissions remain at their domain seams. Route/gate parity, domain regressions and architecture
selection floors verify the moves. No module rank, public route or operation spelling changes.
The additional internal classes make each responsibility independently replaceable.

## Alternatives considered

- Keeping broad owners retains unrelated dependencies and duplicated maintenance.
- A second operation hierarchy duplicates the existing sealed plan model.
- Changing domain boundaries or coarse locking adds unrelated behavioral risk.
