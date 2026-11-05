# ADR-0335 — The P4K import reads catalogue version 2 behind a synchronous LIVE-only gate and records every run

- **Status:** Accepted
- **Amended:** 2026-11-05, §11: the unique-`p4k_uuid` migration clears every duplicate group's
  `p4k_uuid` and then adds the constraint, instead of failing while duplicates remain (@greluc).
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-001…010), [`docs/specs/p4k-catalogue.md`](../specs/p4k-catalogue.md) ·
  issues #2885, #2886, #3029 · map #2884 ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md) ·
  [ADR-0336](0336-a-complete-p4k-kind-sweeps-vanished-rows-behind-a-mass-guard-and-every-run-keeps-a-change-report.md)

## Context

The admin P4K import reads the flat format of the old KRT P4K Reader. Its successor, p4kx, writes
a versioned catalogue whose head carries `schemaVersion` (now `2`) and `source`: `source.channel`
(the Channel, e.g. `LIVE`) and `source.build` (`branch`, `version`, `changeNumber`, `buildId`, or
`null`). `completeness.<kind>` is `{"status": "complete"}` or `{"status": "partial", "filter": …}`.
Additive changes keep `schemaVersion`, and a consumer ignores kinds and fields it does not know.
Within a Channel `changeNumber` is monotonic. Only `LIVE` may be imported in production; `PTU`,
`EPTU`, `TECH-PREVIEW` and `HOTFIX` are confidential test Channels. The LIVE catalogue of p4kx
0.4.2 is 214 MB uncompressed, above the 64 MB upload limit, so the upload is in practice
`.json.zst` (3.9 MB). Its `source.build.publicPatch` is `null`: p4kx reads the public patch only
from the RSI Launcher's install log, the archive holds none (p4kx ADR-0053), and
`source.build.branch` (`sc-alpha-4.10.2`) serves several public patches (4.7.0, 4.7.1 and 4.7.2
all came from `sc-alpha-4.7.0`).

The import today seeds new rows on request (`seedNew`), keeps no history of applied runs, and the
P4K GUID (`p4k_uuid`) of a row can differ from the in-game UUID the SC Wiki wrote.

## Decision

1. **Synchronous upload gate.** The upload endpoint reads only the head of the file with a
   streaming parser and answers 400 problem+json, creating no job and storing no payload, when
   `schemaVersion` is not `2`, `source.channel` is not `LIVE`, or `source.build` is `null`. Each
   refusal is logged. Unknown fields are ignored.
2. **Hard cut of the old format.** The DTOs and code paths of the old KRT P4K Reader format are
   removed in the same change; such a file fails the gate for lack of `schemaVersion`.
3. **LIVE only, hard-wired, in every profile.** No property and no allow-list; tests use LIVE
   fixtures.
4. **Run history.** A new table `p4k_import_run` gets one row per successful APPLY; a PREVIEW
   writes none. A run records the Channel, the Build (`branch`, `version`, `changeNumber`,
   `buildId`), the patch version and its origin, `toolVersion`, `exportedAt`, `completeness` per
   kind, the result counts per kind,
   the number of unmatched records per kind, the uploading admin and the apply time. Runs are
   never pruned; the reference to `p4k_import_job` is `ON DELETE SET NULL`.
5. **Row marker.** `p4k_seen_run_id`, a foreign key to `p4k_import_run` on `game_item`,
   `ship_type`, `manufacturer`, `material`, `blueprint` and the two category tables taken from
   `resourceGroups` and `blueprintCategories`, is set on every row the catalogue matched in that
   run, changed or not. The Build is read through the join.
6. **Patch version.** A run's patch version is `source.build.publicPatch` when present. When it is
   `null`, the version of `source.build.branch` is kept as a lower bound, only if the branch
   matches exactly `sc-alpha-<major>.<minor>.<patch>` (`sc-alpha-4.10.2` gives at least `4.10.2`);
   otherwise it stays unknown. The run records whether its version is a public patch or a branch
   lower bound; the UI shows a lower bound marked as the branch. "Import due" compares a lower
   bound like a patch version, so it never misses a patch and may read as due for a later public
   patch of the same branch until a catalogue with `publicPatch` is applied.
7. **Build order.** APPLY refuses a catalogue whose `source.build.changeNumber` is lower than that
   of the last applied run. Re-applying the same Build is allowed; the import is idempotent.
8. **Partial catalogues are accepted.** Matching runs as usual and the run records `completeness`
   per kind; the sweep acts only on a `complete` kind (ADR-0336).
9. **Match first, seed only by rule.** A P4K import enriches and reconciles the rows it matches.
   `seedNew` is removed: the request parameter, the `seed_new` column of `p4k_import_job`, the
   admin checkbox and the seeding code paths. Items and ships are created only behind a new
   per-run APPLY checkbox, off by default, whose preview shows how many rows would be created, and
   only for `playability = player`; `ai` and `mission` are never created, `null` and `special` go
   on the review list. Created rows are visible to members at once. Manufacturers, commodities and
   blueprints follow their own precedence ADR (ADR-0338, ADR-0339, ADR-0340).
10. **Seeded rows are kept.** No row an earlier `seedNew` run created is deleted or soft-deleted
   automatically. Where the current seeding rule would not create it, it goes on the review list.
11. **Canonical UUIDs are cleaned up at the first APPLY.** `p4k_uuid` becomes unique per kind. The
    first `schemaVersion` 2 APPLY sets the P4K GUID as the identity of each matched row, keeps the
    Wiki UUID in `scwiki_uuid` and records the mismatch in the change report. A row two P4K GUIDs
    would claim, or a P4K GUID two rows would claim, goes to the review list; the admin merges or
    splits, the import never does. The unique constraint is added after the clean-up in the same
    release by a migration that clears `p4k_uuid` on every row of a duplicate group and then adds
    the constraint; the next APPLY matches those rows again and sends an ambiguous match to review.

## Consequences

- An unusable file costs a 400 and a log line, never a job or a stored payload.
- A test-Channel catalogue cannot be imported in any profile, and an older Build never rolls the
  data back.
- The run table and the row marker give the change report (ADR-0336) and the "import due" monitoring
  their history; both grow with every APPLY and are never pruned.
- New records no longer appear automatically: items and ships need the per-run checkbox, other
  kinds their own rule, and everything else shows up as unmatched in the change report for an
  admin to add by hand.
- The requirements are in the P4K catalogue spec, `docs/specs/p4k-catalogue.md`; the sweep of
  §8 is ADR-0336's.

## Alternatives considered

- **Keep reading the old flat format beside version 2.** Rejected for a hard cut: one format, one
  set of DTOs, and an old file fails the gate.
- **A Channel property or allow-list.** Rejected: no test-Channel catalogue is read in any form, so
  there is nothing to configure.
- **Resolve UUID conflicts in the import.** Rejected: the import never merges rows; an admin
  decides from the review list.
- **Read the branch as the public patch.** Rejected: a branch serves several public patches, so
  it can only be a lower bound.
- **Only `publicPatch` counts for "import due".** Rejected: a run without it would be no basis at
  all; a lower bound never misses a patch, at the cost of a due signal for a later patch of the
  same branch.
