# ADR-0285 — One change-set runner for the exchange writers, with typed journal state

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** spec `REQ-XCH-015`, `REQ-XCH-021`, `REQ-XCH-022`, `REQ-XCH-046`
  ([`external-exchange.md`](../specs/external-exchange.md)) · `REQ-OBS-011` ·
  [ADR-0218](0218-exchange-sync-semantics.md) ·
  [ADR-0227](0227-an-admin-undoes-one-client-for-every-member-in-the-background.md) ·
  [ADR-0229](0229-exchange-stock-writes-take-an-advisory-lock-per-lot.md) ·
  [ADR-0267](0267-a-size-ratchet-caps-java-files-and-methods-at-their-current-length.md) ·
  [ADR-0281](0281-a-resent-held-batch-gets-the-staged-confirmation-link-and-is-not-relayed.md) · issue #2737

## Context

The four exchange write services (blueprints, stock, ships, org stock) each copied the same
change-set pipeline: plan, ask the mass-change guard, count `held` and throw, loop over the ops,
count each outcome on `basetool_exchange_writes_total`, refresh the member's pages and build the
result. Each declared its own `Skip` record, its own counter and its own outcome constants. The
copies had drifted: the org stock skipped counting a dry run inside its counter, the others at each
call site; the org stock returned an unmodifiable result list, the others the mutable one. The
`held` metric of ADR-0281 is only meaningful while every copy counts alike.

The undo read each writer's journal state back through untyped string keys (`"shipType"`,
`"orgUnit"`, `"shared"`), so the state's schema was written in one class and read in another, and
`ExchangeUndoService` needed 21 collaborators to restore all four resources itself.

## Decision

1. **`ExchangeChangeSetRunner` is the one pipeline.** It plans through the resource's adapter, asks
   the adapter whether the guard holds the change set back (a held one counts one `held` and throws
   `MASS_CHANGE_CONFIRMATION_REQUIRED` before anything is written), hands each planned change to the
   adapter's execution, counts every op's outcome under the resource's label unless the run is dry,
   refreshes the member's pages when something was applied in a real run, and builds
   `ExchangeChangeResultDto` with an unmodifiable result list.
2. **Each write service is the adapter of its resource** (`ExchangeChangeSetAdapter`): `plan` turns
   the change set into one `Skip` or `Write` per op, `holdsBack` applies the resource's removal
   rules to the guard, and `start` prepares the writes — the locks of the org stock (ADR-0229), the
   stolen-marking flips of the stock — and returns an execution that writes one change, reports the
   resource's effects (offers lowered and removed, mission units detached) and announces the commit.
   The outcome strings live once, in `ExchangeOps`.
3. **Each resource's journal state is a typed record in one class per resource**
   (`ExchangeBlueprintJournal`, `ExchangeStockJournal`, `ExchangeShipJournal`, and
   `ExchangeOrgStockJournal` around the Lager's own `PoolEntryState`). The record is written by the
   writer and read back by the undo through the same class, and it serialises to the journal's
   existing JSON shape key for key, so entries written before stay readable: an older ship state
   without `orgUnit` still records no unit.
4. **The same classes restore.** The three per-entry resources implement `ExchangeEntryRestorer`;
   the org stock keeps its one batched restore with the member's lots, under the same locks as
   before. `ExchangeUndoService` groups the journal, dispatches each group to its resource's
   restorer, counts and audits; `undoWithinRun` keeps its signature and behaviour for the bulk undo
   (ADR-0227).

## Consequences

- Counting is identical for every resource on a dry run, a real run and a held change set, and a
  runner test with a fake adapter pins it.
- Metric names and labels are unchanged (REQ-OBS-011); the dashboards and alerts need nothing.
- The external contract, the journal's stored JSON, the audit events and the journal rows per op are
  unchanged. The share and rebooking still go through `RebookCommands` (ADR-0277, ADR-0278).
- `ExchangeUndoService` takes 13 collaborators instead of 21 and picks the per-entry restorer in an
  exhaustive switch over the resource. It no longer calls a `…Commands` API itself, so the tenancy
  guard (REQ-ORG-028) no longer selects `ConnectedAppsController` through it, and the guard's two
  exceptions for that controller are gone.
- A journal state the undo cannot restore as it was — an unparsable acquisition date, ship type, location or pool id — now leaves the entry
  alone as `GONE` instead of failing the whole undo with a 500; it is never restored with the
  unreadable value dropped.
- The writers shrink: the ship writer falls under the size ratchet's global ceiling and loses its
  entry, the org-stock writer's entry is lowered (ADR-0267).

## Alternatives considered

- **A separate adapter class per resource next to an unchanged write service.** Rejected: the write
  service would keep only a delegating `apply`, and the plan's records would have to be shared
  between two classes.
- **Typed states read by Jackson data binding.** Rejected: an absent and a `null` `orgUnit` mean
  different things, and binding blurs them; a hand-written `read` keeps the old entries' meaning.
- **One restorer interface for all four resources.** Rejected: the org stock must lock and restore
  every entry of a run in one call together with the member's own lots, which a per-entry method
  cannot express.
