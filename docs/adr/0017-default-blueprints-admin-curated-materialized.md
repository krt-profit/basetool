# ADR-0017 — default-blueprints-admin-curated-materialized

- **Status:** Accepted
- **Date:** 2026-06-16
- **Deciders:** @greluc
- **Related:** [REQ-INV-016/017](../specs/personal-inventory-blueprints.md) · [ADR-0014](0014-notification-system-architecture.md)

## Context

A handful of crafting blueprints are unlocked by default on every Star Citizen account (the starter
pistol/rifle, their magazines, the Field Recon Suit pieces). They can no longer be earned in-game
and therefore never appear in an SCMDB / Basetool Blueprint Extractor import, so a user's blueprint
collection in the basetool is permanently missing them even though every player owns them. The
basetool must grant them itself.

Two design axes had to be decided:

1. **How ownership is represented.** A survey of the blueprint feature found 27 independent read
   paths that ask "does user X own product Y" by reading `personal_blueprint` rows directly (the
   owned list, the product-search "owned" flag, the leadership availability overview, the job-order
   blueprint coverage, the import "already-owned" check). The availability overview and coverage
   views additionally group those rows by variant family in memory. A "virtual ownership" scheme
   (treat defaults as owned at read time without rows) would require touching every one of those
   paths and re-working the in-memory grouping — and default rows carry no acquisition metadata to
   represent anyway.

2. **What defines the default set.** The catalog already carries an `isAvailableByDefault` flag
   synced from SC Wiki, but it is write-only (never read), the P4K importer never populates it, and
   it is not validated — so it is not a trustworthy trigger. The authoritative list is the external
   blueprint manager's (scmbd.net) curated starter set.

## Decision

**Materialise** the defaults as real `personal_blueprint` rows for every user, and source them from
an **admin-curated** `default_blueprint` table (not the unreliable flag, not a hard-coded list).

- The default set is a small admin-managed table (REQ-INV-017). It is seeded once on first boot from
  a curated starter list, guarded by a `SystemSetting` flag so an admin's later removal is not
  resurrected; each starter name is resolved against the live catalog for the canonical key, with a
  degraded fall-back so a default is granted even if the catalog lacks it.
- Provisioning is an idempotent bulk `INSERT … SELECT … ON CONFLICT (owner_sub, product_key) DO
  NOTHING`. It is driven from three triggers so the "always present" guarantee holds for any user:
  a synchronous grant inside `UserService.syncUser` when an `app_user` row is first created (so a new
  user has the rows committed before their first request returns — chosen over an after-commit event
  because the grant must be immediate and is small), a grant-to-everyone step when an admin adds a
  default, and a startup backfill + periodic sweep.
- Defaults are non-removable: the personal-blueprint response carries a non-visible `removable` flag
  that hides the delete control, and the delete endpoint refuses a default server-side (409). The
  user chose to hide the control rather than show a "Default" badge, so the list stays visually
  unchanged.

## Consequences

- Every read path keeps working unchanged — defaults are ordinary owned rows. In particular,
  defaults now show as universally owned in the availability overview and as always-covered in
  job-order coverage. This is intended (every member genuinely owns them); if it becomes noise, a
  follow-up can exclude default keys from those leadership views without touching provisioning.
- Materialisation costs one row per (user × default). The set is tiny and the inserts are
  idempotent and bulk, so storage and write cost are negligible.
- Removing a product from the default set does **not** revoke rows users already hold (avoids
  destroying user notes / acquisition data); those rows simply become removable again.
- A first-boot catalog that has not yet synced may seed degraded rows (key = normalized name, no
  output item, recipe view empty); an admin can re-resolve via the picker. The one-time seed flag
  means this is not self-healing on its own — acceptable for the rare empty-catalog deploy.

## Alternatives considered

- **Virtual ownership (no rows).** Rejected: infeasible without reworking all 27 read paths and the
  in-memory family grouping; no place to store the (absent) acquisition metadata.
- **Drive off `isAvailableByDefault`.** Rejected: write-only, unpopulated by the P4K importer, and
  unvalidated — it does not reliably match the real starter set.
- **Hard-coded list in code.** Rejected in favour of an admin table so the set can follow CIG's
  starter loadout without a deploy; the hard-coded list survives only as the one-time seed source.
- **Revoke on default removal / show a "Default" badge.** Rejected by the owner: keep existing rows
  on removal, and hide the delete control instead of adding a badge.
