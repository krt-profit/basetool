# ADR-0336 — A complete P4K kind sweeps vanished rows behind a mass guard and every run keeps a change report

- **Status:** Accepted (implementation pending)
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-011…016), [`docs/specs/p4k-catalogue.md`](../specs/p4k-catalogue.md) ·
  `REQ-DATA-014`, `REQ-DATA-015` ([`data-persistence.md`](../specs/data-persistence.md)) ·
  issue #2888 · map #2884 ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md) ·
  [ADR-0335](0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
  [ADR-0346](0346-catalogue-admin-activity-is-audited-in-its-own-domain.md)

## Context

A game patch removes items, ships and other records. The UEX and SC Wiki syncs stamp vanished rows
through a page-walk census (REQ-DATA-014), which does not fit a file import. The P4K catalogue is a
file: `completeness.<kind>` says per kind whether it holds every record (`complete`) or a filtered
selection (`partial`). A wrong or truncated file must not hide a large part of the catalogue, and
an admin needs to see what a patch changed, what the catalogue holds that the Basetool lacks, and
which rows need a human decision.

## Decision

1. **Sweep per kind, on a valid census only.** The sweep runs per kind (items, ships,
   manufacturers, materials, blueprints, and the commodity and blueprint categories of ADR-0339
   and ADR-0340) when the kind's `completeness` is `complete`, the run
   carries at least one record of that kind, and no GUID occurs twice within the kind. A duplicate
   voids the census: the import logs a WARN and skips the sweep for that kind. A `partial` kind is
   neither stamped nor cleared, and the report records "sweep skipped: partial".
2. **Only P4K-known rows are stamped.** The sweep sets `p4k_deleted_at` only on rows with a
   `p4k_uuid` that this run did not match. Rows the P4K never knew are never stamped.
3. **Mass guard.** The preview shows per kind how many rows the sweep would stamp. Above 5 % of the
   kind's P4K-known rows, APPLY stamps that kind only when the admin ticks a separate "apply marking
   anyway" box; otherwise the import runs and only that kind's sweep is skipped and reported. The
   threshold is a code constant.
4. **Reappearance.** A later match sets `p4k_deleted_at` back to NULL and the report lists the
   record as `REAPPEARED`.
5. **Visibility.** A P4K-stamped row drops out of every picker and search used to create a new
   reference. Existing references (inventory, hangar, job orders, missions) stay and show a "no
   longer in the game" notice. Nothing is deleted. The P4K stamp wins over UEX and the Wiki; the
   report flags a row still live there ("VANISHED, still live in UEX/Wiki"), and the other sources'
   markers stay independent. UEX stays leading for prices and terminals.
6. **Change report.** A new table `p4k_import_change` references `p4k_import_run` and is never
   pruned. Each row holds the kind, GUID, change type, field name, old value and new value. The
   change types are `ADDED`, `VANISHED` and `REAPPEARED` (by GUID); `CHANGED` with old and new
   value, only for fields the import writes, the old value read from the row before it is overwritten;
   `UNMATCHED`, one row per catalogue record with no Basetool row, holding GUID, `className`,
   `name.en` and `recordPath`; and `REVIEW`. `external_sync_report` keeps receiving only the run
   summary.
7. **Display.** `/admin/p4k-import` lists the applied runs (Build, time, admin, counts per kind);
   a run opens its report, filterable by kind and change type, paginated and live via `krtFetch`.
   The sync-reports page gets a P4K tab. A PREVIEW computes the same report and shows it on the job
   without writing `p4k_import_run` or `p4k_import_change`; it expires with the job after 7 days.
8. **Lasting review status.** Previously seeded rows a current seeding rule would not create, and
   records with unclear playability, carry a review status on the row (open / reviewed). Each run
   sets newly found cases to open and logs them as `REVIEW`; `/admin/p4k-import` lists the open
   cases. An admin resolves a case as "keep" or "hide". "Hide" works like the P4K stamp, and a later
   match never lifts it. The row records who resolved the case and when.

## Consequences

- A truncated, filtered or duplicated catalogue cannot hide rows; a large sweep needs an explicit
  admin tick.
- Rows vanished from the game stay referenced by member data, and members see that they left the
  game.
- `p4k_import_change` grows with every APPLY and is never pruned; no catalogue snapshot is kept.
- The sweep gets its own requirement in the P4K catalogue spec, `docs/specs/p4k-catalogue.md`, and
  REQ-DATA-014 points to it. The review resolutions are audited (ADR-0346); the stamped count,
  skipped sweeps and open review cases are monitored. Implementation is pending (B3).

## Alternatives considered

- **Sweep a `partial` kind too.** Rejected: a filtered selection is no census, and every record the
  filter left out would be stamped.
- **A review copy per run.** Rejected for a lasting status on the row, so a decided case stays
  decided and an open one is listed once.
