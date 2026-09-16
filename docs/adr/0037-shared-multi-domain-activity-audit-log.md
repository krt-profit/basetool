# ADR-0037 — Shared multi-domain activity audit log

- **Status:** Accepted
- **Date:** 2026-06-22
- **Deciders:** @greluc
- **Related:** spec REQ-AUDIT-001/-002/-003 ([`audit.md`](../specs/audit.md)) · REQ-BANK-012 ([`bank.md`](../specs/bank.md))

## Context

The bank shipped an immutable, admin-only audit trail (`bank_audit_event`, REQ-BANK-012): one row
per mutation, written in the same transaction as the business write, with a denormalized actor
snapshot so the trail survives user deletion. The owner asked for the same guarantee in four more
areas — Lagerverwaltung (`InventoryItem`), Auftragsverwaltung (`JobOrder`), Raffinerieverwaltung
(`RefineryOrder`) and the personal stash (`PersonalInventoryItem`) — with the logs kept logically
**separate** (a tab per area, a separate PDF per area) but read on **one** admin page, and each
exportable as a period PDF.

Two questions had to be settled: (1) one shared table for the new areas vs. a Bank-style table per
area, and (2) whether the existing bank trail is folded into the same store or stays separate. (The
initial scope was four areas — Lager, Aufträge, Raffinerie, Mein Inventar; Missionen and Operationen
were added in the same change and dropped straight into the shared table, validating the decision.)

The instrumentation is the risky part: these services are the ones the project's optimistic-locking
rules (CLAUDE.md "Concurrency") exist for — bulk `@Modifying(clearAutomatically=true)` unlinks,
`*WithinTransaction` completion funnels, and multi-item loops that would 409 if an audit insert were
placed naïvely.

## Decision

We will store the new areas' events in **one shared `audit_event` table** with a `domain`
discriminator (`INVENTORY`, `JOB_ORDER`, `REFINERY`, `PERSONAL_INVENTORY`, `MISSION`, `OPERATION`)
and a single `AuditEventType` enum whose every constant carries its domain. The **bank keeps its own
`bank_audit_event` table** (it has bank-specific reference columns — account/transaction — and
shipped first); it is surfaced as a further tab on the same unified `/admin/audit-log` page rather
than migrated.

One passive `AuditService.record(eventType, subjectId, subjectLabel, targetUserId, details)`
(`@Transactional(propagation = MANDATORY)`, actor resolved via `AuthHelperService`, never
`SecurityContextHolder`) is called inline from every mutating service method, snapshotting scalar
labels **before** any delete/bulk-clear and emitting **after** the bulk-clear / re-fetch on the
landmine paths. Completion is funneled to a single `JOB_ORDER_COMPLETED` event in
`completeJobOrderWithinTransaction`. Per-area period PDFs reuse the existing `KrtPdfSupport` corporate
layer through a shared `AuditLogPdfFormat` renderer; the bank export feeds the same renderer.

## Consequences

- One migration (V179), one entity/service/repository/mapper/DTO and one controller serve all four
  new areas; adding a fifth area is an enum addition, not a new table. The unified viewer and the
  "switch which log you see" + "export the selected log" requirements fall out naturally. *(Borne
  out since: Missionen, Operationen, Rollen & Mitglieder (epic #800) and Beförderung (the promotion
  catalogue + member gradings) were each added as a pure `AuditDomain` / `AuditEventType` enum
  extension — no new table, no viewer rewrite — for nine logs total incl. the bank's.)*
- The audit table is **business data**, admin-only, insert-only; the log-stream PII rule
  (`observability.md`) is unaffected and user free text never enters the details payload.
- The `audit_event` table grows unbounded (no retention) and the scheduled UEX sync writes a summary
  event per run — accepted, matching the bank trail's "the trail is the point" stance. *(Amended by
  [ADR-0038](0038-admin-retention-purge-of-audit-logs.md): admins may now purge a log's entries
  older than a chosen cutoff — a manual, opt-in, itself-audited bounded-growth lever, not an
  automatic sweep.)*
- The audit PDF prints raw event codes (not localized labels) so the backend message bundle is not
  duplicated; the on-screen viewer carries the rich German/English labels.
- The bank trail stays on its own table, so a query "everything that happened" spans two tables —
  accepted: the bank's reference shape and its shipped tests differ enough that merging them would be
  churn for no user-visible gain.

## Alternatives considered

- **Four separate Bank-style tables (one per area)** — rejected: ~4× the schema/entity/service/test
  surface and the unified viewer would still have to fan out across four shapes; the `domain` column
  gives the same logical separation in one store.
- **Fold the bank trail into the shared table** — rejected: the bank rows carry account/transaction
  references and an established test suite; migrating live data and rewriting those tests is risk
  without user benefit. The unified page already presents them together.
- **A single generic event type (string) with no per-domain enum** — rejected: the typed enum with a
  `domain()` accessor keeps the persisted `domain` column and the event type from ever disagreeing
  and lets the viewer derive each tab's filter list from the enum.
