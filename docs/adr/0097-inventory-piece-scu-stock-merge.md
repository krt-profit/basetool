# ADR-0097 — Inventory: write-time stock merge for PIECE (auto) and SCU (per-action opt-in)

- **Status:** Accepted
- **Date:** 2026-07-13
- **Deciders:** Repository owner (@greluc)
- **Related:** amends [ADR-0003](0003-inventory-append-only-group-on-read.md) · spec
  [`inventory-lager.md`](../specs/inventory-lager.md) `REQ-INV-001` / `REQ-INV-026` ·
  [`materialboerse.md`](../specs/materialboerse.md) `REQ-MARKET-*` · issue #1182

## Context

[ADR-0003](0003-inventory-append-only-group-on-read.md) made the Lager **append-only**: every
contribution is its own row and matching rows are collapsed only for display (group-on-read). That is
the right model for **`SCU`** stock, where the provenance of each drop-off (who, when, which note)
matters for accounting and where fractional quantities accumulate meaningfully.

For **`PIECE`** (Stück) materials the append-only model is noise: identical whole-unit items (ship
components, weapons, boxes) pile up as many one-piece rows that a member has to scroll through in the
per-stack drill-down, with no provenance value the operator actually uses. The repository owner asked
to bring back a **merge** for PIECE stock, and to let a user *optionally* merge an SCU write when they
want to — without changing the default SCU behaviour and **without ever touching the Materialbörse**.

## Decision

Re-introduce a **scoped write-time merge**, amending ADR-0003 for PIECE and adding an SCU opt-in:

- A write whose material is **`PIECE`** merges the just-written row into a single Lager entry with
  every existing row that shares its stock identity — **automatically**, on every inbound write path
  (create, association edit, book-out TRANSFER target, personal-rebooking/transfer).
- A write whose material is **`SCU`** merges the same way **only when the caller opts in** for that
  one action (a modal checkbox, per-transaction, never persisted). Without the opt-in, SCU stays
  append-only exactly as ADR-0003 defined.

The **merge identity** is the append-only stack key **minus `delivered`** (owner · material ·
location · quality · `personal` · optional `jobOrder` / `mission` · owning org-unit pool). The
just-written row survives; matching rows are folded into it (amounts summed, distinct notes
concatenated and truncated to the note column) and deleted, and the survivor is reset to
**not-delivered** (the "Geliefert" marker is not part of the key and has no unambiguous combined
value).

Two invariants are non-negotiable:

- **Materialbörse untouched.** A row backing *any* `MaterialExchangeOffer` is excluded from the merge
  (neither survivor-that-changes nor folded-away). The `inventory_item` FK is `ON DELETE CASCADE`, so
  folding such a row would destroy the offer; and the offer reads material/amount live from the row,
  so a merge must never change its offered quantity.
- **Concurrency.** The merge group is loaded `FOR UPDATE`, re-introducing — only on the merge path —
  the pessimistic lost-update lock ADR-0003 removed, so two racing same-stack writers serialise.

A one-time Flyway backfill (`V216`) merges pre-existing PIECE rows under the same identity and
offer-exclusion so the deployed dataset matches the new write behaviour.

## Consequences

- PIECE Lager stacks collapse to one row per identity; the drill-down is readable again and notes are
  consolidated. Provenance is *intentionally* discarded for PIECE (the point of the merge) but
  preserved for SCU unless the user opts in.
- The lost-update race ADR-0003 eliminated returns **only** on the merge path, guarded by a
  `FOR UPDATE` group lock; the append-only paths keep their race-free behaviour. A rare tail race
  (two brand-new rows created in the sub-commit window) leaves a duplicate that self-heals on the
  next write and is invisible behind group-on-read — accepted, not fixed with a unique constraint
  (which would clash with SCU append-only rows).
- The four write DTOs gain a per-action `mergeStock` flag (request-only, mirrored front/back and in
  `openapi.json`); the create page and the Umbuchen modals gain an SCU-only checkbox.
- A new `INVENTORY_ITEM_MERGED` audit event (Lager domain) records each fold (count, total,
  auto/manual trigger), keeping REQ-AUDIT-001 coverage complete.
- `delivered` is reset on merge, so a merged PIECE stack that was partly marked delivered loses that
  marker — acceptable because the marker is not stock-moving (the real handover is a separate flow)
  and the owner chose this over splitting the key.

## Alternatives considered

- **Keep everything append-only; only merge PIECE for display.** Rejected: the display already groups
  (group-on-read); the owner explicitly wanted the physical rows consolidated for PIECE.
- **Merge SCU automatically too.** Rejected: SCU provenance (per-drop notes, fractional history) is
  the reason ADR-0003 exists; SCU merge stays an explicit, per-action opt-in.
- **Include `delivered` in the merge key.** Rejected by the owner: it would leave a delivered and a
  non-delivered PIECE row un-merged (still two physical rows), defeating the consolidation; resetting
  to not-delivered on merge was chosen instead.
- **Persist the SCU merge preference.** Rejected: the opt-in is a one-shot decision for a single
  action, not a standing setting.
- **A unique constraint on the PIECE stock identity to make merge race-free.** Rejected: it cannot
  coexist with the append-only SCU rows on the same table, and the group-on-read display already
  masks the rare tail race.

