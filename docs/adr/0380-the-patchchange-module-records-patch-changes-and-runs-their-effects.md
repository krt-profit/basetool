# ADR-0380 — The `patchchange` module records patch changes and runs their effects

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** @greluc
- **Related:** [patch-change spec](../specs/patch-change.md) (REQ-PATCH-001…009) ·
  [ADR-0381](0381-the-live-time-decides-what-a-patch-change-touches.md) ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) ·
  [ADR-0224](0224-the-change-feed-is-a-trigger-written-key-log.md) · decisions #3247, #3252, #3327

## Context

A Star Citizen patch can wipe stock and in-game ships, end refinery jobs, reset money and deliver
everything that was kept to a new home location. Before this, nothing in the Basetool recorded a
patch: the global stock wipe (REQ-INV-041) and the bank wipe reset (REQ-BANK-013) were separate
admin actions in separate modules, each scoped and confirmed on its own. Following a patch means
touching Inventory, Hangar, Refinery, the job orders, Supply, the Materialbörse and the Bank at
once, and every member afterwards.

The effects belong to modules of different ranks. If each module learned what a patch change is,
seven modules would depend on one concept, and every later effect would reopen all of them.

## Decision

1. **A new backend module `patchchange`** (rank 12, beside `exchange`) owns the record: the table
   `patch_change` with its options and effect counts, one row per patch change, triggered by an
   admin for every org unit. It drives the other modules **only through their `api` packages**;
   those modules know nothing of a patch change. Each takes an operation phrased in its own terms
   (the refinery: "cancel the running orders started before an instant, with this reason").
2. **The effects are beans of this module behind one internal seam, `PatchEffect`**: `preview(plan)`
   counts, `apply(run)` runs inside the trigger's transaction and reports counts. The trigger runs
   every bean in one transaction with the record, all or nothing; an exception of any effect rolls
   the whole patch change back. A later effect plugs in by adding one bean and the api operation of
   its module, without touching the trigger.
3. **The trigger names itself to the exchange feed.** It assigns the record's id before the
   transaction begins and runs the transaction inside `PatchChangeWrites.runFor(id, …)`; the
   exchange's change source reads that binding and writes `patch|<id>` into the transaction
   variable the feed triggers read. The exchange depends on `patchchange::api` (an `allow` row of
   the domain map) for the binding and the label lookup; `patchchange` never depends on the
   exchange.
4. **A lower module that needs patch-change facts owns an SPI** the module implements, as
   `inventory.api.StockMergeCutoff` does for the no-mixing rule (ADR-0381).
5. **The home-location phase gates hard.** When the option is on, every member whose account
   existed before the live time is asked at the next request for a new home location, like the
   terms acceptance, and their rows and ships from before the live time are locked for others until
   they choose (#3252). The gate and the lock are built with the home-location slice of epic #3328;
   this record and its option are their anchor.

## Consequences

- One place answers which patch changes exist, with which options and effects; the admin history
  and the audit area read it.
- Every effect is one bean and one api operation, testable alone and replaceable without the rest.
- The trigger is one transaction over several modules' tables. Its duration grows with the effects;
  the effects lock the rows they write and run in bean order.
- `exchange` and `patchchange` share rank 12, joined by one `allow` row in one direction.

## Alternatives considered

- **An SPI in `patchchange.api` that each affected module implements.** It inverts the dependency:
  seven lower modules would depend on `patchchange` and know the concept, against #3247.
- **Domain events after the commit.** The effects would no longer commit with the record, and a
  refused effect could not stop the patch change.
- **A key in `system_setting`.** It cannot hold the history, the options or the effect counts, and
  every effect would still need a home.
