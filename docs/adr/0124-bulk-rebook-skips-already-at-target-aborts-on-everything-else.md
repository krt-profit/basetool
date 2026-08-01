# ADR-0124 — Bulk rebooking skips already-at-target rows and aborts on everything else

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** @greluc
- **Related:** REQ-INV-036 · REQ-INV-034 (server-resolved select-all) · REQ-INV-025 (target-less
  transfer) · REQ-INV-007 (personal rebooking) · REQ-INV-027 ("Marken mitnehmen") ·
  REQ-INV-026 (stock merge) · REQ-AUDIT-001 (`docs/specs/audit.md`) · ADR-0003 (append-only
  inventory) · ADR-0104 (no silent caps)

## Context

"Mein Lager" already had a bulk bar: mark entries with checkboxes, then **Markierte ausbuchen**
(bulk checkout), which discards every marked row. The selection itself is not a DOM scan — since
REQ-INV-034 the "Alle markieren" button resolves the **complete** id set of the current filtered
view on the server, so it routinely spans hundreds of rows across collapsed stacks and later pages.

The request was to add the second half of the pair: **Markierte umbuchen** — the same selection
*moved* rather than discarded, covering both destinations the single-row Umbuchen modal offers (Ort
/ Nutzer / OrgUnit-Pool, and the personal marker).

Two properties of the existing pieces make this more than a copy of `bulkCheckout`.

**(1) The existing bulk checkout is strictly all-or-nothing.** It loads each row under a pessimistic
lock, throws `NotFoundException` / `AccessDeniedException` on the first problem, and lets the
transaction roll back. That is exactly right for a *destructive* action: a bulk delete that silently
skipped part of the selection would be a data-loss trap.

**(2) A move has a legitimate no-op case that a delete does not.** The single-row transfer rejects a
move that changes neither user nor location (`"Transfer must change either the user or the
location"`). Applied per row in bulk, that rule fires constantly: "Alle markieren" + "move everything
to Lorville" marks every row of the view, and any row **already at Lorville** hits it. With
all-or-nothing semantics the overwhelmingly common case — consolidating scattered stock into one
location — would fail outright, and the user would have to hand-deselect exactly the rows they cannot
see (they are in collapsed stacks). The feature would be unusable for its main purpose.

The same asymmetry appears in the personal modes. The single-row modal *infers* the direction from
the source row's own `personal` flag, because a single row has exactly one. A bulk selection can mix
personal and shared stock, so an inferred per-row flip would make the outcome depend on what happened
to be marked — "umbuchen" would personalize some rows and de-personalize others in the same click.

### Alternatives considered

**(A) Strict all-or-nothing, mirroring `bulkCheckout`.** Simplest code and perfectly consistent with
the neighbouring action. Rejected: as shown above it fails on the primary use case, and the failure
is unfixable from the UI because the offending rows are not visible.

**(B) Skip every failure, report a summary.** Symmetrical and never fails. Rejected: it hides genuine
problems. A selection containing another member's row, or a stale id a peer already removed, would be
silently dropped and reported as a partial success — turning a permission error into a rounding
difference. It also conflicts with the no-silent-caps principle of ADR-0104.

**(C) Pre-filter client-side** — drop already-at-target rows in the browser before posting.
Rejected: the client does not know the state of rows it never loaded (that is the whole point of the
server-resolved select-all), so the filter would be wrong precisely for the large selections that
need it.

**(D) Infer the personal direction per row** (as the single-row modal does). Rejected: see above —
one click would move rows in two opposite directions.

## Decision

**Bulk rebooking skips the no-op and aborts on everything else.**

1. A row that already sits in the **requested target state** is skipped and counted — same user *and*
   location for `LOCATION`, already personal for `PERSONALIZE`, already shared for `DEPERSONALIZE`.
   This is the only tolerated outcome, and it is tolerated because the requested end state already
   holds: nothing is lost by not acting.
2. **Every other obstacle aborts the whole transaction**, so nothing is written: an unknown id (404),
   a row owned by someone else (403), an unknown target user/location (404), and a job-order /
   mission earmark blocking a `PERSONALIZE` (400). The selection is fully loaded and validated before
   the first write, which keeps the abort independent of loop order and lets the earmark rejection
   name how many rows block it instead of only the first.
3. A `LOCATION` request carrying **neither** a target user nor a target location is rejected up front
   (REQ-INV-025 parity) rather than reported as an all-skipped success — otherwise the one request
   that provably moves nothing would look like the benign no-op case.
4. The two personal directions are **explicit modes** (`PERSONALIZE` / `DEPERSONALIZE`), never
   inferred, so the end state is a property of the request and not of the selection.
5. The endpoint returns `{rebooked, skipped}` and the page phrases its toast from both counts. An
   all-skipped run is surfaced as "nothing moved", not as a success. The two counts always sum to the
   number of **distinct** ids in the request, so a returned result can never hide a failure.
6. Every marked row moves **in full**. There is no per-row amount: the selection spans rows the user
   cannot see, so a per-row quantity could not be reviewed. A full move also makes the earmark
   handling trivially correct — the default deduct-from plan resolves to "every slice in full", so
   the moved row inherits all tags and the emptied source is deleted (ADR-0003 append-only is
   preserved: a new row is inserted, never folded, and only then optionally merged per REQ-INV-026).

Concurrency follows `bulkCheckout`: no client `@Version` (the bulk bar holds only ids), one
pessimistic row lock per entry. The ids are additionally **deduplicated and locked in sorted order**
so two concurrent bulk rebookings over overlapping selections cannot deadlock by taking the same rows
in opposite orders.

Audit follows the bulk-checkout precedent: **one** summarizing `INVENTORY_BULK_REBOOKED` event per
action carrying the mode and both counts, rather than one event per moved row — a select-all over
hundreds of rows would otherwise bury the log. A run that moved nothing records no event, because it
mutated no state.

## Consequences

**Positive.** The primary use case — consolidating scattered stock into one location — works in one
click over the whole filtered view. Permission and staleness errors stay loud. The reported counts
are honest, so the user can tell "moved everything" from "moved almost nothing" without opening the
audit log.

**Negative / accepted.**

- The two bulk actions in the same bar now have **different** failure semantics: checkout is
  all-or-nothing, rebooking skips no-ops. This is deliberate and rests on the destructive/
  non-destructive split, but it is a real inconsistency a future reader must not "clean up".
- An `INVENTORY_BULK_REBOOKED` event does not name the individual rows, so reconstructing exactly
  which stock moved needs the surrounding row-level history. Accepted for the same reason as
  `INVENTORY_BULK_CHECKED_OUT`.
- A source row backing a Materialbörse offer loses that offer when it moves, because the row is
  deleted and the FK cascades (V210). This matches the existing single-row full-amount transfer and
  is not made worse here; a bulk selection simply hits it more often.
- The action is owner-scoped and **not** role-overridable, so a logistician cannot use it to
  reorganize another member's stock — they must use the single-row endpoints.

