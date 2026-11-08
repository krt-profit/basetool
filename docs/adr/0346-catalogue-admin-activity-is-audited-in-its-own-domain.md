# ADR-0346 — Catalogue admin activity is audited in its own domain

- **Status:** Accepted (partly implemented)
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the audit spec (REQ-AUDIT-001, REQ-AUDIT-002) · the P4K catalogue spec (REQ-P4K-046) · the
  observability spec · ADR-0335 · ADR-0336 · #2901 · map #2884

## Context

The catalogue admin area — master data, the scheduled UEX and Wiki syncs and the coming P4K import
with its mass guard and review list — changes shared state, but writes no audit event. Its sync
detail lives in `external_sync_report`, the P4K change detail in `p4k_import_change`. Refining
methods and the UEX refinery sync are already audited in `REFINERY`, quality tiers in `JOB_ORDER`.

## Decision

1. **A new `AuditDomain.CATALOGUE`** gets its own „Katalog" tab in the unified viewer,
   `CATALOGUE_AUDIT_EXPORTED` / `CATALOGUE_AUDIT_PURGED`, a case in both exhaustive domain switches
   (purge marker, export marker), DE/EN labels (`admin.audit.domain.CATALOGUE`,
   `admin.audit.event.*`, `pdf.audit.title.CATALOGUE`) and an entry in the REQ-AUDIT-001 coverage
   list. Every state-mutating activity in the area writes an event. Refining methods and the UEX
   refinery sync stay in `REFINERY`; quality tiers stay in `JOB_ORDER`.
2. **One event type per entity kind and verb** (about 30 types), each in the viewer's per-area
   event-type filter:
   - P4K preview enqueued and P4K APPLY: one event per run, with build, run id and counts per kind.
   - `CATALOGUE_P4K_MASS_GUARD_OVERRIDDEN`: one event per kind whose sweep ran on the admin's
     "apply marking anyway" override, with kind, rows to stamp and share in percent.
   - Review resolution: one event per case, keep or hide.
   - Manual sync-report delete: one event with source, `olderThanDays` and the deleted count. The
     automatic `pruneRuns` stays a plain log line.
   - Every catalogue admin mutation that exists: materials, material categories, material external
     aliases, manufacturer and ship-type visibility, locations, terminals (including loading-dock
     and auto-load overrides), loading-dock overrides on cities, outposts, POIs and space stations,
     star systems, frequency types (including activate and reorder), job types (including
     activate).
   - Scheduled UEX and Wiki syncs: one summary event per run and source, with counts (added,
     updated, stamped), actor `system`. The detail stays in `external_sync_report`.
3. **Payload.** `subjectId` is the row's UUID, or the run id for P4K runs and syncs;
   `subjectLabel` is the row's display name at event time; `targetUserId` stays null. An update
   event lists only the names of the changed fields (`changed=name,category`). Values appear only
   for enums and switches (`visible=false`, `autoLoad=true`). Admin-entered free text never enters
   the details payload.
4. **Read access and retention as in every domain.** Read, export and purge are admin-only;
   retention is the global 730 days through `AuditRetentionTask`. `p4k_import_change` stays the
   never-pruned detail record.
5. **Monitoring.** `CATALOGUE` joins the low-traffic exclusion regex of
   `AuditDomainSilenceAnomaly`; sync outages stay with the existing sync monitoring. The
   `audit_events` counter gains the domain tag on its own.
6. **Delivery.** The whole catalogue audit, including the events for the existing master-data
   mutations, is built with the implementation of the orphan sweep and change report (ADR-0336),
   not ahead of the first migration step.

## Consequences

- Every catalogue change, by an admin, a sync or the P4K import, can be traced in the viewer
  without exposing values beyond enums and switches.
- REQ-AUDIT-001 gains the catalogue bullet; REQ-AUDIT-002 gains the tab and its correct tab count;
  the P4K catalogue spec points to the catalogue audit for review resolutions and APPLY. The wiki
  is unaffected, because the area is admin-only.
- Later catalogue mutations — manual starmap and shop links, visibility overrides (ADR-0345,
  ADR-0347) — are events of this domain.
- Built: the domain, its event types and labels, the viewer tab and filter, the alert exclusion,
  the P4K preview and APPLY events, the manual sync-report delete and every existing master-data
  mutation. The mass-guard override and review resolution types exist for their flows.
- Pending: the UEX and Wiki sync summaries, recorded when the durable sync run finishes, and the
  starmap, shop-link and visibility-override events of the flows that do not exist yet.
