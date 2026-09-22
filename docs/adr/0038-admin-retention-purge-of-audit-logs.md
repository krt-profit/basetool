# ADR-0038 — Admin-controlled retention purge of the audit logs

- **Status:** Accepted — **amended by [ADR-0179](0179-both-audit-trails-are-swept-on-a-retention-ceiling.md)** (2026-09-15): the manual purge stays, and both audit trails are additionally swept on a 24-month retention ceiling — the automatic sweep this ADR rejected
- **Date:** 2026-06-22
- **Deciders:** @greluc
- **Related:** spec REQ-AUDIT-004 ([`audit.md`](../specs/audit.md)) · amends a consequence of [ADR-0037](0037-shared-multi-domain-activity-audit-log.md) · REQ-BANK-012 ([`bank.md`](../specs/bank.md))

## Context

[ADR-0037](0037-shared-multi-domain-activity-audit-log.md) shipped the activity audit logs as
**append-only** and explicitly accepted that the tables **grow unbounded** ("the trail is the
point"). With every mutation across seven logs (the six shared-table areas plus the bank) now
writing a row — plus per-run UEX-sync summaries — the
owner asked for a way to keep the tables bounded: let admins delete entries older than a chosen date,
**separately for each log including the bank**, with a reminder to take a backup first.

This reopens the immutability stance of ADR-0037. The options were: (a) an automatic time-based
retention sweep; (b) a manual, admin-triggered purge; (c) leave it unbounded and solve growth at the
database/ops layer (partition drop, external archival).

## Decision

We add a **manual, admin-only retention purge**: a per-log action that deletes that log's rows with
`occurredAt < cutoff`. No automatic sweep — purging is always an explicit admin action, one log at a
time (`DELETE /api/v1/audit/{domain}` for the four generic areas, `DELETE /api/v1/bank/admin/audit`
for the bank), reusing the existing admin URL gates. A single `@Modifying` bulk delete per call runs
first; the service then records a `*_AUDIT_PURGED` marker (the bank's `AUDIT_LOG_PURGED`) in the same
transaction, carrying the deleted count and the cutoff. Because the marker's timestamp is newer than
the cutoff it survives its own purge, so **a deletion always leaves a trace**. The endpoints return
the deleted count (`AuditPurgeResultDto`).

The purge is **irreversible**, so the responsibility for not losing data sits with the admin: the
delete modal carries a prominent warning recommending a **PDF/JSON backup (REQ-AUDIT-003) first**.
The backend does **not** enforce a prior export — coupling "you must export before you may delete"
would be brittle (what counts as a recent-enough export?) and the audience is a small set of trusted
admins.

## Consequences

- The "grows unbounded (no retention)" consequence of [ADR-0037](0037-shared-multi-domain-activity-audit-log.md)
  is **amended**: there is now a bounded-growth lever, but it is opt-in and manual, not a policy.
- The append-only invariant is relaxed to **append-and-admin-purge**: rows are still never *updated*,
  and the *only* delete path is this audited, admin-gated purge. The repository Javadocs and
  REQ-AUDIT-001 are updated to say so.
- A purge is self-documenting: the `*_AUDIT_PURGED` marker (count + cutoff) means "audit rows were
  deleted" is itself in the trail — you cannot silently erase history, only compact it and leave the
  receipt.
- The bulk delete is a plain `@Modifying` JPQL `DELETE` followed by one insert (the marker); it
  touches no other entity, so it is free of the project's optimistic-locking / `clearAutomatically`
  landmines (CLAUDE.md "Concurrency").
- Backup is the admin's responsibility, surfaced by a UI warning, not a backend precondition.

## Alternatives considered

- **Automatic time-based retention sweep** — rejected: it would silently destroy audit history on a
  schedule (the opposite of an audit log's purpose) and demands a policy decision (how long?) the
  owner did not want baked in. A manual purge keeps a human in the loop per deletion.
- **Enforce an export before allowing a purge** — rejected: brittle to define and easy to satisfy
  trivially; the trusted-admin audience plus the explicit warning + the audited `*_AUDIT_PURGED`
  marker give the needed safety without a fragile precondition.
- **No purge; solve growth at the DB/ops layer** — rejected: it pushes a routine product need
  (compact an oversized log) onto manual DB surgery, with no in-app trace and no per-log scoping.
