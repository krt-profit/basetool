# ADR-0361 — A Wiki manufacturer follows its UUID change, and a Wiki duplicate becomes an alias

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** @greluc
- **Related:** spec [`data-persistence.md`](../specs/data-persistence.md) (REQ-DATA-033) ·
  [`audit.md`](../specs/audit.md) (REQ-AUDIT-001) ·
  [`observability.md`](../specs/observability.md) (`CatalogueAttemptIncomplete`) ·
  [ADR-0338](0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md)

## Context

The SC Wiki manufacturer sync links a local `manufacturer` row to one Wiki UUID
(`scwiki_uuid`) and never moves an existing link: a Wiki row that matches a row linked to another
UUID is a `MANUFACTURER_MISMATCH`, which marks the census incomplete and refuses the
`scwiki_deleted_at` sweep. In production every run since 2026-11-09 stopped there with six
mismatches and `CatalogueAttemptIncomplete` firing for `SCWIKI / MANUFACTURER`:

- **Five were duplicates inside the Wiki.** The Wiki lists the same company twice under two UUIDs
  (CureLife `CRLF` and Curelife `CUR`, Banu and Banu Souli, Casaba Outlet and Trade & Development
  Division, …). The local row is linked to one of them, and the other row reaches it through its
  P4K GUID, a name or a code that is unique in the census. The rule that a row held by a sibling is
  not contested only applied to keys several census rows share.
- **One was a UUID change.** The Wiki carries Esperia (`ESPR`) under a new UUID; the UUID the local
  row is linked to is no longer in the census. Nothing could ever clear the mismatch.

A Wiki duplicate also matters beyond the alert: the Wiki item sync assigns an item's manufacturer
by the Wiki manufacturer UUID the item names. An item that names the unlinked duplicate finds its
manufacturer only when its name happens to match. Which of the duplicates is right is a judgement
the Wiki data cannot make; usually one of them is partly or wholly wrong.

## Decision

1. **Every Wiki row that resolves to a manufacturer is one of its identities**
   (`manufacturer_scwiki_identity`: Wiki UUID, manufacturer, the Wiki's name and code). The row
   whose UUID is `manufacturer.scwiki_uuid` is the primary link; every other one is an alias. The
   Wiki item sync resolves an item's manufacturer through all of them.
2. **A row held by a sibling is an alias, never a conflict.** When the matched local row's Wiki or
   P4K UUID names another row of the same census, or the Wiki row is already a known alias, it is
   stored as an alias (`MANUFACTURER_DUPLICATE` on first sight) and does not mark the census
   incomplete — whatever key matched it.
3. **An admin chooses the primary link** among a manufacturer's identities on the SC Wiki tab of the
   sync reports. The choice checks the manufacturer's `@Version`, is audited
   (`CATALOGUE_MANUFACTURER_WIKI_PRIMARY_CHANGED` with the old and new Wiki UUID) and updates open
   pages in place. The sync keeps it: it never moves a link whose UUID the Wiki still carries.
4. **A link moves only when the Wiki changed nothing but the UUID.** The row moves to the new UUID,
   reported as `MANUFACTURER_RELINKED`, when the census is complete (every page, every row with a
   distinct UUID), the old `scwiki_uuid` is not in it, and the new Wiki row is a known alias of the
   row or repeats its name and stored `scwiki_code`, each unique in the census.
5. **Everything else stays a conflict.** A link absent from a partial census, a different name or
   code, or a key several census rows share keeps `MANUFACTURER_MISMATCH` and blocks the sweep.
6. A complete census deletes the identities it no longer carries, together with the existing
   `scwiki_deleted_at` sweep.

## Consequences

- The manufacturer census completes again; the sweep marks Wiki manufacturers that really vanished.
- Wiki items naming either duplicate land on the same manufacturer; an admin picks which entry's
  code the manufacturer keeps as `scwiki_code`. What members see — name, abbreviation,
  description — still comes from the P4K catalogue and UEX (REQ-P4K-020).
- A future Wiki UUID change heals itself on the next complete run, and the sync report names it.
- A row the Wiki renames *and* re-keys at once still needs an admin's look; the relink never guesses.

## Alternatives considered

- **Correct Esperia once in the production database.** Rejected: it fixes one row and leaves the
  next UUID change to the same dead end.
- **Discard the duplicate instead of keeping it as an alias.** Rejected: Wiki items naming the
  discarded UUID would lose their manufacturer unless their name matched.
- **Leave the mismatches.** Rejected: the alert would fire on every run with no action that clears
  it.
- **Relink on the name alone.** Rejected: a name is shared too often (CureLife) to prove identity
  on its own; the stored Wiki code is the Wiki's own second key.
