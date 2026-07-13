# ADR-0003 — Inventory: append-only entries with group-on-read display

- **Status:** Accepted — **amended by [ADR-0097](0097-inventory-piece-scu-stock-merge.md)** (write-time merge for `PIECE` automatically and `SCU` on a per-action opt-in; append-only stays the default and the SCU behaviour)
- **Date:** 2026-06-06
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`inventory-lager.md`](../specs/inventory-lager.md) `REQ-INV-001..005` · [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) `REQ-ORG-003` · issue #466

## Context

The Lager merged stock at write time: any new or edited `InventoryItem` whose eight-dimension
natural key (owner · material · location · quality · mission · jobOrder · personal · owning
org-unit) matched an existing row had its amount summed into that row, and the separate
contribution was discarded. Four write paths did this (create, update, book-out TRANSFER,
refinery store), plus the org-unit reconciler deduped after a mass re-stamp.

The merge was **irreversible and lossy**. Operators could not see the individual
contributions a stack was made of — who dropped off what, when, with which note — which a
squadron logistician repeatedly needs for accounting and trust. The merge also forced a
read-add-write step on a shared row, guarded by a pessimistic `SELECT … FOR UPDATE` to avoid
lost updates under concurrency.

## Decision

We will make inventory **append-only** and form stacks only at read time (group-on-read).
Every write path inserts (or edits in place) its own row and never folds into a different
row. The grouped Lager views compute display *stacks* by the natural key **in SQL**, summing
amount and aggregating quality; a stack's underlying entries are **not** inlined but fetched
lazily and paginated on expand (oldest-first). The UI renders Material → Stack and expands a
stack to load its entries. Mutating actions stay **per entry** (by id + version); the stack
row is display-and-expand only — there is no aggregate book-out with automatic allocation in
this version.

## Consequences

- Provenance is preserved: each contribution keeps its own amount, note and creation instant.
- The lost-update race disappears: no shared row is mutated, so the pessimistic merge lock and
  the `findMatchingInventoryItem(ForUpdate)` queries are deleted (a net concurrency
  simplification — the `*WithinTransaction` / bulk-update-after-loop rules are unaffected).
- More physical rows and read-time grouping. The grouped read returns all stacks (sorted,
  client-filtered); only the per-stack entries drill-down is paginated. Job-order completion
  and material-collection are sum-based, so totals are unchanged — only row counts grow.
- Stacks are aggregated in SQL (a `GROUP BY` over the natural key into an
  `InventoryStackAggregate` projection); the grouped response carries only the collapsed stack
  rows. A stack's entries are **not** inlined — append-only growth is unbounded per stack — but
  fetched lazily and paginated on expand via `/api/v1/inventory/{my-inventory|all}/stack/entries`,
  oldest-first, backed by the composite `idx_inventory_item_stack_key` index (Flyway `V143`).
  This keeps the grouped read O(stacks) rather than O(rows) and bounds the per-expand load
  (REQ-INV-005).
- The natural key spans three **nullable** dimensions (`jobOrder`, `mission`, `owningOrgUnit`), so
  the grouping query must `LEFT JOIN` them and group on the join aliases. A constructor-expression
  projection that selects/groups a nullable to-one *directly* renders an implicit **inner** join,
  which silently drops every stack where that association is `null` — i.e. almost all plain Lager
  stock (most items belong to no job order and no mission). That regression emptied both grouped
  views in v0.4.0; it is pinned against the real schema by `InventoryItemStackQueryDataTest`.
- A new response DTO (`InventoryStackDto`, entries not inlined), a `createdAt` field on
  `InventoryItemDto`, a two-level grouped page plus a lazy entries fragment
  (`fragments/inventory-stack-entries.html`) were introduced; the frontend mirrors and
  `openapi.json` move in lock step (the `DtoOpenApiContractTest` enforces it).
- No data migration: previously-merged rows remain valid; the change is forward-only.

## Alternatives considered

- **Keep merge-on-write, add a separate provenance/audit table.** Rejected: duplicates stock
  data and leaves the primary rows lossy and authoritative-but-incomplete.
- **Append-only with no grouping (show every raw row).** Rejected: the Lager becomes unreadable
  for high-volume materials; the dynamic grouping is what keeps the overview usable.
- **Aggregate book-out with FIFO allocation across a stack.** Deferred: it reintroduces
  multi-row allocation logic and obscures which contribution was consumed; per-entry actions
  match the existing id+version architecture and ship the provenance benefit with less risk.

