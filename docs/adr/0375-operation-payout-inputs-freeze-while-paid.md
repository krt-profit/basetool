# ADR-0375 - Operation payout inputs freeze while any participant is marked paid

- **Status:** Accepted
- **Date:** 2026-11-12
- **Deciders:** greluc
- **Related:** [REQ-MISSION-034](../specs/mission-detail-tabs.md#req-mission-034--a-missions-operation-link-follows-the-operations-rules), [REQ-MISSION-028](../specs/mission-notifications.md#req-mission-028--a-payout-was-paid-out), [#3359](https://github.com/krt-profit/basetool/issues/3359)

## Context

An operation pays out from the combined accounts and attendance of its linked missions. The first
paid-out mark establishes a shared basis for the remaining payments. Operation lifecycle status
alone does not indicate whether participants have been paid.

## Decision

An operation with a current paid-out mark refuses changes to its mission links and payout inputs.
Every mark and reversal serialises with calculation changes through the operation's transaction
lock. The database guards the affected columns and associations, including writes that enter
through inventory sales or refinery workflows. Mission data outside the payout calculation remains
editable. The existing payout-clear permissions control reversal.

The first paid mark of a settlement records the effective transfer-fee rate and attendance
reference time. Additional marks share this basis. A correction after authorized reversal of all
marks starts a new basis. Required account erasure can still unlink identities or reassign
ownership under REQ-DATA-008. The existing reversal gate exposes an opaque status-row key when a
paid mark has no current payout row. The key can only clear that mark within its operation.

## Consequences

If amounts need correction after settlement starts, the authorized reviewer must clear all current
marks before correcting and confirming the payouts. In-flight conflicting calculation writes can
return a conflict. Existing paid flags take the migration-time fee and attendance reference because
the prior marks did not record those values.

## Alternatives considered

- Freeze every completed or cancelled operation. Closing the operation can precede the
  reconciliation of its payout inputs.
- Reset paid-out marks when inputs change. A mission edit could then reverse a settled payout
  outside the established authorization and review path.
