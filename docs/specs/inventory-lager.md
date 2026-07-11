> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-29.
> **Owner area:** INV · **Related ADRs:** ADR-0003

# Inventory Lager — append-only entries & group-on-read

## Context & goal

The squadron Lager holds every contribution of refined and raw material (manual entry,
refinery store, transfer, hand-over). Historically the Lager **merged** stock at write
time: a new row whose stock identity matched an existing row had its amount summed into
that row, and the separate contribution was discarded. The merge was irreversible, so the
provenance of each drop-off (who, when, the per-entry note) was lost — a "100 SCU" row
could really be four separate contributions.

This spec replaces that with **append-only persistence + group-on-read display**: every
contribution is its own row, and the UI collapses rows that share a stock identity into one
display *stack* that expands to the individual entries. Tracked by issue #466 (milestone
v0.4.0); the persistence-model decision is recorded in ADR-0003.

The **stock identity** ("stack key") is the inventory natural key: owner (`user`),
`material`, `location`, `quality`, the optional `mission` / `jobOrder` association, the
`personal` flag, and the owning org-unit pool (`owningOrgUnit`).

## Requirements

### REQ-INV-001 — Inventory is append-only; no write path merges

No write path may fold a new or edited `InventoryItem` into a different existing row. Each
of create, update, book-out **TRANSFER** (the moved quantity at the target), refinery store
and any future inbound path inserts (or edits in place) its own row. Two rows that share the
stock identity coexist as separate rows; they are never summed in the database. The former
read-add-write merge — and the pessimistic lock that guarded its lost-update race — are
removed.

**Acceptance**

- [ ] Creating stock that matches an existing row's stock identity yields a second row; the
  existing row's amount is unchanged.
- [ ] Updating a row never deletes it in favour of a matching row; it is saved in place.
- [ ] A partial TRANSFER decrements the source and inserts a new row at the target even when
  an identical target stack already exists; the existing target row is unchanged.
- [ ] Storing a refinery output inserts a new row with its own note; no existing row's amount
  or note changes.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemServiceBookOutTest`,
`RefineryOrderServiceTest` · **Code:** `InventoryItemService`, `RefineryOrderService` ·
**Issues:** #466

### REQ-INV-002 — Group-on-read display: Material → Stack

The grouped Lager views (`/inventory/my`, `/inventory/all`) present each material as a group
whose stacks are computed **in SQL** (a `GROUP BY` over the stock identity) at read time. Three
stock-identity dimensions — `jobOrder`, `mission` and `owningOrgUnit` — are nullable, so the
grouping query **left-joins** them and groups on the join aliases: a stack whose rows carry no job
order and no mission (the common case for plain Lager stock), or a personal stack with no owning
org-unit, must still surface. (A constructor-expression projection over a nullable to-one otherwise
renders an implicit inner join that silently drops every such stack — the regression that emptied
both grouped views in v0.4.0.) Each stack shows the summed amount, the amount-weighted mean
quality, the max quality and the entry count. The grouped response carries **only** the collapsed stack rows — it does **not** inline
the underlying entries (those are loaded on demand, see REQ-INV-005). The UI renders two server
levels — material group → stack row — and a stack row expands to fetch its entries. Both grouped pages present these two levels (plus the lazy
entry rows) as a single design-system **tree-table** (`das-kartell-design` → `.tree-table`):
one sticky column header for all depths, depth shown by indentation + left rails rather than a
repeated per-level header, right-aligned tabular numbers with a 0-1000 quality gauge, and a note
rendered only when an entry has one. The
per-material aggregate page (`/inventory`, `AggregatedInventoryDto`) is unchanged.

**Acceptance**

- [ ] Rows sharing the stock identity appear as one stack row with the correct summed amount,
  amount-weighted mean quality and entry count.
- [ ] Rows differing in any stock-identity dimension appear as separate stacks.
- [ ] A stack whose nullable stock-identity dimensions are absent — a non-personal stack with no
  job order and no mission, or a personal stack with no owning org-unit — still appears in the
  grouped view; a `null` in a nullable dimension never hides the stack.
- [ ] The grouped response contains no per-entry rows (entries are lazy — REQ-INV-005).
- [ ] Both grouped pages render the collapsed stack rows without error.

**Enforced by:** `InventoryItemServiceAggregateTest`, `InventoryItemStackQueryTest`,
`InventoryItemStackQueryDataTest`, `InventoryPageControllerMvcTest` · **Code:**
`InventoryItemService#buildGroupedFromStacks`,
`InventoryItemRepository#findUserStacks` / `#findGlobalStacks`, `InventoryStackAggregate`,
`InventoryStackDto`, `GroupedInventoryDto`, `inventory-my.html`, `inventory-admin.html` ·
**Issues:** #466

### REQ-INV-003 — Actions operate per entry

Every mutating Lager action — book-out (consume / transfer / sell), personal-marker rebooking
(Umbuchung, REQ-INV-007), note edit, delivered toggle, association change, bulk check-out — targets
a single `InventoryItem` by id and `version`. The grouped stack row is display-and-expand only; it carries no aggregate
mutation. Optimistic locking and the frontend `data-version` DOM-sync therefore continue to
work unchanged at the entry level.

**Acceptance**

- [ ] Book-out / note / delivered / association endpoints accept an item id + version and
  affect only that row.
- [ ] The expanded entry view exposes those actions per entry; the stack row exposes none.

**Enforced by:** `InventoryItemControllerTest`, `InventoryPageControllerMvcTest` · **Code:**
`InventoryItemController`, `InventoryPageController` · **Issues:** #466

### REQ-INV-004 — Org-unit reconcile re-stamps without merging

When a user gains their first or loses their last org-unit membership, the reconciler
re-stamps `owning_org_unit` on their non-personal rows (promote `NULL` → the joined unit, or
demote all → `NULL`). It must **not** merge rows that become identical after the re-stamp;
they remain separate and are collapsed only for display.

**Acceptance**

- [ ] Demoting a user's rows to `NULL` leaves every row present with its amount unchanged; no
  row is deleted.

**Enforced by:** `InventoryOrgUnitReconcilerTest` · **Code:** `InventoryOrgUnitReconciler` ·
**Issues:** #466

### REQ-INV-005 — Entries are lazy-loaded, paginated and index-backed

A stack does not inline its entries: an append-only stack grows unboundedly as contributions
accumulate, so materialising every entry on each grouped read does not scale. Entries are
fetched on expand from `GET /api/v1/inventory/{my-inventory|all}/stack/entries`, addressed by
the stock-identity fields the stack already exposes (a `null` job-order / mission / owning
org-unit selects the rows where that association is itself absent), returned **oldest-first** by
`createdAt` and **paginated** (default 20, max 100 per page). A composite index
`idx_inventory_item_stack_key` on the inventory natural key backs both the grouped `GROUP BY`
and the per-stack entries lookup. The `/all` drill-down re-applies the same org-unit scope
predicate as the grouped view; the `/my` drill-down is owner-scoped from the JWT (no
impersonation). Per-entry actions (REQ-INV-003) operate on the fetched rows unchanged. Each
drill-down row shows the entry's amount, its job-order / mission association and the per-entry
actions (book-out, note), with the note preview rendered beside the action buttons rather than
below them; `createdAt` is the entries' ordering key, not a displayed column. Both per-entry
actions are compact icon buttons (book-out = outbound arrow, note = pencil; their labels carried in
`aria-label` / `title`) so the dense action column never crowds — or overlaps — the amount beside
it; on tablet-width and narrower (≤ 1024px) the amount and actions reflow onto their own line
beneath the Auftrag/Einsatz controls.

**Acceptance**

- [ ] A stack's entries are returned oldest-first by `createdAt`.
- [ ] A requested page size above 100 is clamped to 100; an absent page/size yields the first 20.
- [ ] The drill-down never returns rows outside the caller's org-unit / owner scope.
- [ ] Expanding a stack on either grouped page fetches and renders its entries without error.
- [ ] At any viewport width the per-entry amount and action buttons never overlap; the book-out and
  note actions are icon buttons and, on tablet-width and narrower (≤ 1024px), amount + actions reflow
  onto their own line beneath the Auftrag/Einsatz controls.

**Enforced by:** `InventoryItemStackQueryTest`, `InventoryItemControllerTest`,
`InventoryPageControllerMvcTest`, `DatabaseIndexMigrationTest` · **Code:**
`InventoryItemController#getMyStackEntries` / `#getAllStackEntries`,
`InventoryItemRepository#findUserStackEntries` / `#findGlobalStackEntries`,
`InventoryPageController#viewMyStackEntries` / `#viewAllStackEntries`,
`fragments/inventory-stack-entries.html`, `static/css/styles.css` (`.tree-row--leaf`, `.btn-icon`),
`inventory-my.html` / `inventory-admin.html` (note-button DOM sync), `V143__add_inventory_item_stack_key_index.sql` ·
**Issues:** #466

### REQ-INV-006 — "Mein Lager" personal-entries-only filter

The personal Lager view (`/inventory/my`) offers a **personal-entries-only** filter alongside the
existing material / min-quality / job-order / mission filters. When enabled (query param
`personalOnly=true`), the grouped result is narrowed to the caller's private stock (`personal =
true` rows); when the param is absent or `false`, both the caller's shared contributions and their
personal stock are returned — the unchanged default. The narrowing is applied **in SQL** by the
same group-on-read query (`findUserStacks`), so the material-group aggregates (summed amount,
amount-weighted mean quality, max quality, entry count) always reflect exactly the visible stacks
rather than a client-side subset. The flag is URL-driven (a filtered view is shareable), composes
with the other filters and the `fragment=true` in-place swap, and is reflected back into the page
URL. It is a scope-**narrowing** concern only: it never widens visibility, and it exists solely on
the owner-scoped `/my` view — there is no equivalent on the squadron-wide `/all` view.

**Acceptance**

- [ ] With `personalOnly=true`, only the caller's `personal = true` stacks appear; the caller's
  shared (non-personal) contributions are excluded.
- [ ] With `personalOnly` absent or `false`, both shared and personal stacks appear (unchanged
  behaviour).
- [ ] The material-group aggregates reflect only the visible stacks under the active filter.
- [ ] The flag composes with the material / min-quality / job-order / mission filters and is
  reflected in the page URL.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemStackQueryDataTest`,
`InventoryItemControllerTest`, `InventoryPageControllerTest`, `InventoryPageControllerMvcTest` ·
**Code:** `InventoryItemRepository#findUserStacks`,
`InventoryItemService#getMyAggregatedInventory`, `InventoryItemController#getMyGroupedInventory`,
`InventoryPageController#viewMyInventory`, `inventory-my.html` · **Issues:** #466

### REQ-INV-007 — Personal-marker rebooking (Umbuchung) is an append-only split

A user may **rebook** (Umbuchung) part or all of one of their inventory rows between their personal
pool and the shared squadron pool by toggling its `personal` marker. The direction is derived from
the source row's current flag, never from the client:

- **personal → shared** (entpersonalisieren): the moved quantity becomes shared squadron stock
  (`personal = false`) stamped on an org-unit pool resolved through
  `OwnerScopeService.resolveOrgUnitForPickerOutputNullable` — the same create-time stamping matrix as
  [`org-unit-tenancy.md`](org-unit-tenancy.md) REQ-ORG-004 (the owner picks the pool when they belong
  to more than one org unit; a sole membership auto-stamps; a membershipless owner yields an
  ownerless shared row the reconciler promotes later, REQ-INV-004).
- **shared → personal** (personalisieren): the moved quantity becomes the owner's private stock
  (`personal = true`), carrying the source row's existing `owningOrgUnit` over. A source row bound to
  a job order or mission is **refused** (HTTP 400) — a personal row may never carry either
  association.

The operation is an **append-only split** (REQ-INV-001), structurally identical to the book-out
`TRANSFER` branch: the moved `amount` is decremented off the source row (the source row is deleted
when it depletes below the quantity epsilon) and inserted as its own new row with the opposite
`personal` flag — it is never folded into an existing stack. It is per-entry (REQ-INV-003), guarded
by optimistic locking on the source row's `version`, and owner-scoped (`@ownerScopeService.canEditInventoryItem`;
an admin/logistician may act within scope). Every rebooking records its own audit event
(`INVENTORY_ITEM_DEPERSONALIZED` / `INVENTORY_ITEM_PERSONALIZED`, REQ-AUDIT-001).

In the UI the action is the per-entry **Umbuchen** row action (`krt-icon-rebook`) on `/inventory/my`;
its modal also hosts the relocated location/user transfer (the former book-out `TRANSFER` mode), so
**Ausbuchen** is now consume/sell only and **Umbuchen** owns every rebooking. The squadron-wide
`/inventory/all` view exposes the Umbuchen action for the location/user transfer only (the
personal↔shared toggle is owner-scoped and lives on `/my`).

**Acceptance**

- [ ] Rebooking part of a personal row decrements the source and inserts a new `personal = false`
  row for the moved quantity; the source's other contributions are unchanged (append-only).
- [ ] Rebooking the whole row deletes the depleted source and leaves exactly the new row.
- [ ] A de-personalize stamps the new shared row on the picked org-unit pool (or the owner's sole
  membership, or `null` when membershipless), consistent with REQ-ORG-004.
- [ ] A personalize carries the source row's `owningOrgUnit` over and refuses a source bound to a
  job order or mission with HTTP 400.
- [ ] A stale `version` yields HTTP 409; a non-owner without an admin/logistician grant yields 403.
- [ ] Each direction records its own audit event; the unified viewer's Lager filter lists both.

**Enforced by:** `InventoryItemServicePersonalRebookTest`, `InventoryItemControllerTest` ·
**Code:** `InventoryItemService#rebookPersonal`, `InventoryItemController#rebookPersonal`,
`InventoryItemPersonalRebookDto`, `inventory-my.html`, `inventory-admin.html`,
`fragments/inventory-stack-entries.html` · **Issues:** —

### REQ-INV-025 — Book-out validates the CheckoutType; a target-less TRANSFER is rejected

Book-out (`POST /api/v1/inventory/{id}/book-out`) resolves the `CheckoutType` before mutating the
row. An **absent** `type` is inferred — `TRANSFER` when the request carries a target user or
location, otherwise `DISCARD`. An **explicit** `type = TRANSFER` is *not* re-inferred, so a
`TRANSFER` carrying neither `targetUserId` nor `targetLocationId` has nowhere to move the stock to.
Such a request is **rejected with HTTP 400** up front — before any decrement, delete or audit
write. It must never fall through to the consume/discard tail: doing so would silently destroy the
source stock and record it as `INVENTORY_ITEM_CONSUMED` with `type = TRANSFER`, an audit lie about
a mutation the caller never requested. (The complementary no-op guard — a `TRANSFER` whose target
resolves to the source's own user *and* location — likewise 400s; the append-only move of
REQ-INV-001 is defined only for a target that actually differs.) A rejected book-out writes **no**
audit event, consistent with the audit contract that only committed state mutations are logged
(REQ-AUDIT-001).

**Acceptance**

- [ ] An explicit `type = TRANSFER` with both `targetUserId` and `targetLocationId` absent yields
  HTTP 400; the source row's amount is unchanged, no new row is inserted, the source is not
  deleted, and no audit event is recorded.
- [ ] A `type = TRANSFER` carrying at least one target proceeds as the append-only move
  (REQ-INV-001).
- [ ] An absent `type` with no target is still inferred as `DISCARD` (unchanged) and consumes the
  stock, logging `INVENTORY_ITEM_CONSUMED` with `type = DISCARD`.

**Enforced by:** `InventoryItemServiceBookOutTest` · **Code:**
`InventoryCheckoutService#bookOutInventoryItem` (public façade
`InventoryItemService#bookOutInventoryItem`) · **Issues:** —

## Out of scope

- Tenancy / visibility scope of inventory (strict-staffel Lager-View) is governed by
  [`org-unit-tenancy.md`](org-unit-tenancy.md) `REQ-ORG-003`; this spec does not change it.
  Grouping is a display concern and never widens visibility.
- The optimistic-locking, `@Version` and `*WithinTransaction` concurrency rules live in
  [`data-persistence.md`](data-persistence.md) and `CLAUDE.md`.

## Open questions

- A convenience "book out N from the whole stack" with automatic allocation across entries
  (FIFO) was deferred; per-entry actions were chosen for v1 (ADR-0003). Revisit if operators
  ask for it.

