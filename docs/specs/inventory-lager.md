> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-13.
> **Owner area:** INV · **Related ADRs:** ADR-0003, ADR-0097, ADR-0098

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

**Amendment (#1182, ADR-0097).** Append-only is now the *default*, not an absolute: a **scoped
write-time merge** re-consolidates rows that share a stock identity — automatically for a **`PIECE`**
(Stück) material and, for an **`SCU`** material, only when the caller opts a single action in. SCU
without the opt-in is unchanged (append-only). The rules live in
[REQ-INV-026](#req-inv-026--write-time-stock-merge-for-piece-auto-and-scu-per-action-opt-in) below;
REQ-INV-001 is amended accordingly.

**Amendment (#1182, ADR-0098 — Variante C / "Modell G").** An inventory→job-order and an
inventory→mission association is no longer a single scalar column each but a **to-many quantity
split**: an entry may earmark parts of its amount to **several** job orders and **several** missions
at once, each earmark carrying its own amount, split independently per dimension. The earmarks
therefore leave the stock-identity key — a row now stacks on its **physical identity only** — and
move down to the individual entry as amount chips. Per dimension the Σ of the slice amounts must stay
within the entry's amount (rule R5, HTTP 422 on breach); `delivered` becomes a per-(entry, job-order)
slice; a `SELL` books seller-chosen per-mission income attributions; and the write-time merge unions
the folded rows' allocations. The full rules live in
[REQ-INV-027](#req-inv-027--inventory-associations-are-to-many-quantity-splits-variante-c) below.

The **stock identity** ("stack key") is the inventory **physical** natural key: owner (`user`),
`material`, `location`, `quality`, the `personal` flag, and the owning org-unit pool
(`owningOrgUnit`). Since Variante C (ADR-0098, REQ-INV-027) the job-order / mission earmarks are
**no** longer part of it — they are per-entry to-many allocations, not a stack dimension.

## Requirements

### REQ-INV-001 — Inventory is append-only by default (merge is the scoped exception)

A write path does **not** fold a new or edited `InventoryItem` into a different existing row
**unless the scoped stock merge of [REQ-INV-026](#req-inv-026--write-time-stock-merge-for-piece-auto-and-scu-per-action-opt-in)
applies** — a `PIECE` material always, an `SCU` material only on the caller's per-action opt-in.
Outside that merge, each of create, update, book-out **TRANSFER** (the moved quantity at the
target), refinery store and any future inbound path inserts (or edits in place) its own row; two
rows that share the stock identity coexist as separate rows and are never summed in the database. In
particular an **`SCU`** write with no opt-in stays append-only exactly as before. The former
*unconditional* read-add-write merge — and the pessimistic lock that guarded its lost-update race —
remain removed for the append-only paths; the merge re-introduces a pessimistic lock only on its own
path (REQ-INV-026).

**Acceptance**

- [ ] Creating **`SCU`** stock (no opt-in) that matches an existing row's stock identity yields a
  second row; the existing row's amount is unchanged.
- [ ] Updating an **`SCU`** row (no opt-in) never deletes it in favour of a matching row; it is saved
  in place.
- [ ] A partial **`SCU`** TRANSFER (no opt-in) decrements the source and inserts a new row at the
  target even when an identical target stack already exists; the existing target row is unchanged.
- [ ] Storing a refinery output inserts a new row with its own note; no existing row's amount or note
  changes (refinery outputs are `SCU`).
- [ ] A `PIECE` write, or an `SCU` write with the opt-in, merges per REQ-INV-026 instead of appending.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemServiceBookOutTest`,
`InventoryStockMergeTest`, `RefineryOrderServiceTest` · **Code:** `InventoryItemService`,
`InventoryCheckoutService`, `RefineryOrderService` · **Issues:** #466, #1182

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

Both tree levels are collapsible, and their expand/collapse state is **persisted per user**
(`localStorage`, keyed by the viewer's id — `expanded_rows_lager_*` for material groups,
`expanded_stacks_lager_*` for location stacks) and re-applied on initial load **and after every
in-place grouped-table re-swap** — a filter change or a modal write (book-out, Umbuchen and
bulk-checkout all re-swap `#inventoryTable` on success, REQ-INV-003/REQ-INV-007). A fragment swap
does not re-fire `DOMContentLoaded`, so the expansion is restored explicitly after the swap; a
restored stack re-fetches its (now up-to-date) entries. A modal mutation therefore never collapses
the tree the user was working in.

**Acceptance**

- [ ] Rows sharing the stock identity appear as one stack row with the correct summed amount,
  amount-weighted mean quality and entry count.
- [ ] Rows differing in any stock-identity dimension appear as separate stacks.
- [ ] A stack whose nullable stock-identity dimensions are absent — a non-personal stack with no
  job order and no mission, or a personal stack with no owning org-unit — still appears in the
  grouped view; a `null` in a nullable dimension never hides the stack.
- [ ] The grouped response contains no per-entry rows (entries are lazy — REQ-INV-005).
- [ ] Both grouped pages render the collapsed stack rows without error.
- [ ] After an in-place grouped-table re-swap (filter change or modal write), the material groups
  and location stacks the user had expanded stay expanded and a restored stack re-loads its
  entries; a modal mutation never collapses the tree.

**Enforced by:** `InventoryItemServiceAggregateTest`, `InventoryItemStackQueryTest`,
`InventoryItemStackQueryDataTest`, `InventoryPageControllerMvcTest`, `InventoryOperationsE2eTest`
· **Code:** `InventoryItemService#buildGroupedFromStacks`,
`InventoryItemRepository#findUserStacks` / `#findGlobalStacks`, `InventoryStackAggregate`,
`InventoryStackDto`, `GroupedInventoryDto`, `inventory-my.html`, `inventory-admin.html`,
`static/js/inventory-my.js`, `static/js/inventory-admin.js` (tree expand/collapse persistence) ·
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

### REQ-INV-006 — "Mein Lager" personal- / non-personal-entries-only filters

The personal Lager view (`/inventory/my`) offers two **mutually exclusive** stock-kind filters
alongside the existing material / min-quality / job-order / mission filters: a **personal-entries-only**
toggle (query param `personalOnly=true`) narrows the grouped result to the caller's private stock
(`personal = true` rows), and a **non-personal-entries-only** toggle (query param
`nonPersonalOnly=true`) narrows it to the caller's shared stock (`personal = false` rows). When both
params are absent or `false`, the caller's shared contributions and their personal stock are both
returned — the unchanged default. The UI keeps the two toggles mutually exclusive (checking one
clears the other); the backend query intersects the two clauses, so were both ever `true` the result
is simply empty. The narrowing is applied **in SQL** by the same group-on-read query
(`findUserStacks`), so the material-group aggregates (summed amount, amount-weighted mean quality,
max quality, entry count) always reflect exactly the visible stacks rather than a client-side subset.
Both flags are URL-driven (a filtered view is shareable), compose with the other filters and the
`fragment=true` in-place swap, and are reflected back into the page URL. They are scope-**narrowing**
concerns only: they never widen visibility, and they exist solely on the owner-scoped `/my` view —
there is no equivalent on the squadron-wide `/all` view.

**Acceptance**

- [ ] With `personalOnly=true`, only the caller's `personal = true` stacks appear; the caller's
  shared (non-personal) contributions are excluded.
- [ ] With `nonPersonalOnly=true`, only the caller's `personal = false` (shared) stacks appear; the
  caller's personal stock is excluded.
- [ ] With both params absent or `false`, both shared and personal stacks appear (unchanged
  behaviour); the two toggles are mutually exclusive in the UI.
- [ ] The material-group aggregates reflect only the visible stacks under the active filter.
- [ ] Both flags compose with the material / min-quality / job-order / mission filters and are
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

### REQ-INV-026 — Write-time stock merge for PIECE (auto) and SCU (per-action opt-in)

A write that lands a row whose material's quantity type is **`PIECE`** (Stück) is merged into a
single Lager entry with every existing row that shares its stock identity; a write whose material is
**`SCU`** does the same **only when the caller opts in for that one action** — a modal checkbox that
is per-transaction and **never persisted**. The merge runs on the four inbound write paths: create
(Einbuchen), the association edit (Ändern — material / quality / location / job order / mission), the
book-out **TRANSFER** target, and the personal-rebooking / transfer (Umbuchen). An `SCU` write
without the opt-in stays append-only (REQ-INV-001); the opt-in checkbox is offered only on `SCU` rows
(a `PIECE` row always merges, so no choice is shown).

The **merge identity** is the append-only stack key *minus* `delivered`: owner · material · location
· quality · `personal` · optional `jobOrder` / `mission` · owning org-unit pool (the three nullable
dimensions match `NULL = NULL`). The just-written row is the **survivor**; every other row sharing
that identity is folded into it — `amount`s summed and **distinct notes concatenated** (first-seen
order, newline-joined, truncated to the 1000-char note column) — and then deleted. Because
`delivered` is deliberately **not** part of the merge key, the merged survivor is reset to
**not-delivered** (combining a delivered with a non-delivered contribution has no single truth; owner
decision).

**Materialbörse invariant.** A merge **never** changes a Materialbörse entry (see
[`materialboerse.md`](materialboerse.md)). A row that backs *any* `MaterialExchangeOffer` (any status)
is excluded from the merge — it is never a survivor whose amount changes and never folded away: the
`inventory_item` FK is `ON DELETE CASCADE`, so deleting such a row would silently destroy the offer,
and the offer reads its material/amount live from the row. The offered quantity is therefore never
increased by a merge.

**Concurrency.** The merge group is loaded `FOR UPDATE` (pessimistic write lock) so two racing
same-stack writers serialise instead of double-counting or losing stock — this re-introduces, **only
on the merge path**, the lost-update lock the append-only model (ADR-0003) had removed. The merge is
a read-add-write that runs inside the caller's transaction (`Propagation.MANDATORY`).

**Auditing.** Every merge that folds at least one row records an `INVENTORY_ITEM_MERGED` audit event
(REQ-AUDIT-001) carrying the folded-row count, the resulting total and the `auto` (PIECE) / `manual`
(SCU opt-in) trigger.

**Deployment.** The change ships a one-time Flyway backfill (`V216__merge_piece_inventory_rows.sql`)
that merges pre-existing `PIECE` rows under the same identity and offer-exclusion, so the deployed
dataset matches the new write behaviour. `SCU` rows and offer-backed rows are left untouched.

**Acceptance**

- [ ] A `PIECE` create / edit / transfer / rebook that matches an existing stack folds the rows into
  one: amounts summed, distinct notes combined, the survivor reset to not-delivered, the folded rows
  deleted.
- [ ] An `SCU` write merges only when the per-action opt-in is set; without it the row stays separate
  (append-only). The opt-in checkbox renders only for `SCU` materials.
- [ ] A row backing a Materialbörse offer is never merged (neither survivor nor folded), and the
  offered quantity is unchanged by any merge.
- [ ] Two concurrent same-stack writers do not double-count or lose stock (the `FOR UPDATE` group
  serialises them).
- [ ] The deployment backfill merges matching pre-existing `PIECE` rows and leaves `SCU` rows and
  offer-backed rows untouched.
- [ ] Each fold records one `INVENTORY_ITEM_MERGED` audit event; the unified viewer's Lager filter
  lists it.

**Enforced by:** `InventoryStockMergeTest`, `InventoryItemServiceTest`,
`InventoryItemServiceBookOutTest`, `InventoryItemServicePersonalRebookTest` · **Code:**
`InventoryCheckoutService#mergeStockIfRequested`, `InventoryItemRepository#findMergeGroupForUpdate`,
`MaterialExchangeOfferRepository#existsByInventoryItemId`, `InventoryItemService`,
`V216__merge_piece_inventory_rows.sql`, `inventory-input.html` / `inventory-input.js`,
`inventory-my.html` / `inventory-my.js`, `inventory-admin.html` / `inventory-admin.js` · **Issues:**

# 1182 · **ADR:** ADR-0097

### REQ-INV-027 — Inventory associations are to-many quantity splits (Variante C)

An inventory entry's job-order and mission associations are **two independent to-many quantity
splits** ("Modell G"), not a single scalar each. An entry may earmark parts of its `amount` to
**several** job orders and **several** missions at once, each earmark carrying its own amount; the
two dimensions are split independently. The earmarks are stored as per-entry **allocation** rows
(`inventory_item_job_order_allocation`, `inventory_item_mission_allocation`; V217), each with a
`UNIQUE(inventory_item_id, target_id)` so a target appears at most once per entry per dimension, and
`ON DELETE CASCADE` on both foreign keys. The former scalar `inventory_item.job_order_id` /
`mission_id` / `delivered` columns are dropped (V218).

**R5 — per-dimension coverage.** Per dimension, the Σ of the slice amounts must stay within the
entry's own `amount`. Any write that would raise a dimension's Σ above the entry amount — or lower
the entry amount below an existing Σ (book-out consume, transfer / rebook source remainder, handover)
— is rejected with **HTTP 422** (`OverAllocationException`, `code = OVER_ALLOCATION`); the amounts
are never silently shrunk, the user reduces the allocations first. An unallocated remainder
(`amount − Σ`) is allowed and shown as a muted "frei" chip.

**Stacking (physical identity only).** Since the earmarks left the stack key (see Context), an entry
stacks on its physical identity only (owner · material · location · quality · personal · owning org
unit); the group-on-read display shows the earmarks as amount chips on the individual leaf entry,
not on the stack. Stacking, filters and job-order / mission fulfilment sums all read the allocation
tables — an order is credited only its **allocated** share of a split entry, not the whole row.

**Assignment writes.** The earmarks are edited through dedicated per-allocation endpoints `POST` /
`PATCH` / `DELETE /api/v1/inventory/{id}/allocation` (add / change amount / remove), each gated by
`isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)` — the same owner-scoped
inventory-edit gate, **no new role**. They refuse a personal entry (personal stock carries no
assignment), refuse a job-order target whose material the order does not require (REQ-ORDERS-018),
reject a duplicate target, hold PIECE amounts whole, and enforce R5. Each mutation is audited
(`INVENTORY_ALLOCATION_ADDED` / `_CHANGED` / `_REMOVED`, REQ-AUDIT-001). The entry's `@Version` is the
single optimistic-lock token for its allocations (an inverse-side slice change force-increments it).

**Split at check-in (R4).** The create payload additionally accepts per-dimension allocation lists,
so a book-in can be earmarked to several orders / missions with their own amounts in one shot,
under the same guards + R5. An empty list falls back to no assignment.

**Delivered is per-(entry, job-order) slice (Variante A).** The "Geliefert" marker moved onto the
job-order allocation: an entry serving several orders can be delivered for one and open for another.
The order material-collection reads the slice's flag and shows the amount **allocated to that order**
(with the entry's total physical stock as context), while that total still backs the full-row
owner / location transfer.

**Merge unions allocations (R1).** The write-time stock merge (REQ-INV-026) folds on physical
identity and **unions** the folded rows' allocations into the survivor — summed per target, the
job-order delivered flag OR-combined. Because the survivor's amount already absorbed the folded
amounts, R5 is preserved under the fold.

**SELL books seller-chosen per-mission attributions.** A `SELL` book-out of mission-earmarked stock
distributes the sale proceeds across the row's earmarked missions the seller participates in — one
squadron-`INCOME` `MissionFinanceEntry` per chosen mission, Σ ≤ the sale proceeds, an uncredited
remainder staying the seller's personal proceeds. Only missions the seller participates in are
creditable; an empty attribution list is a fully-personal sale that credits no mission.

**Acceptance**

- [ ] An entry can hold several job-order and several mission allocations at once, each with its own
  amount; adding one via `POST /{id}/allocation` returns the updated entry with the new chip.
- [ ] Raising a dimension's Σ above the entry amount, or lowering the entry amount below an existing
  Σ, yields HTTP 422 and mutates nothing.
- [ ] The group-on-read stack key no longer contains the job-order / mission earmark; two entries
  differing only in their earmarks stack together, and the earmarks render as leaf chips.
- [ ] A personal entry rejects any allocation, and a job-order allocation whose material the order
  does not require is rejected (REQ-ORDERS-018).
- [ ] `delivered` toggled for one order leaves the entry's other orders unchanged; the order
  material-collection shows the amount allocated to that order.
- [ ] A stock merge sums the folded rows' allocations per target and OR-combines job-order delivered.
- [ ] A SELL with per-mission attributions books one INCOME entry per attribution (Σ ≤ proceeds),
  rejects an attribution to a non-earmarked mission or a mission the seller is not in, and treats an
  empty list as a fully-personal sale.
- [ ] Each allocation add / change / remove records the matching `INVENTORY_ALLOCATION_*` audit event.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemServiceBookOutTest`,
`InventoryCheckoutServiceAuditTest`, `InventoryStockMergeTest`, `JobOrderHandoverServiceTest`,
`InventoryAllocationSoakDataTest`, `InventoryItemControllerTest`, `InventoryPageControllerMvcTest`,
`DatabaseIndexMigrationTest` · **Code:** `InventoryJobOrderAllocation`, `InventoryMissionAllocation`,
`support/InventoryAllocations`, `InventoryItemController` (allocation endpoints),
`InventoryItemService#createInventoryItem`, `InventoryCheckoutService` (book-out / merge / SELL),
`InventoryAggregationService#getMaterialCollection`, `InventoryItemMapper`,
`V217__add_inventory_allocation_tables.sql`, `V218__drop_inventory_scalar_associations.sql`,
`fragments/inventory-stack-entries.html`, `inventory-my.js` / `inventory-admin.js`,
`inventory-input.html` / `inventory-input.js` · **Issues:** #1182 · **ADR:** ADR-0098

### REQ-INV-028 — Aggregated per-material overview shows average and maximum quality

The per-material Lager overview (`GET /inventory`, `AggregatedInventoryDto`) rolls the in-scope
non-personal stock up to one row per material, showing the total amount, the **amount-weighted
average** quality and the **maximum** available quality (the best single entry's quality). The three
aggregates come from one grouped query — amount-weighted average, `MAX(quality)`, `SUM(amount)` over
`GROUP BY material`; the row links through to the per-material drilldown (`/inventory/all` filtered to
the material).

**Acceptance**

- [ ] `/inventory` lists one row per material with the columns material · Ø quality · **max quality**
  · total amount, the max-quality column sitting between the average and the total.
- [ ] The max quality equals the highest `quality` of any of the material's in-scope non-personal
  entries; the average is amount-weighted; both are `0` for a material with no stock.
- [ ] The projection is scope-filtered (strict-staffel / admin-all) exactly like the rest of the
  Lager ([`org-unit-tenancy.md`](org-unit-tenancy.md) `REQ-ORG-003`) and excludes personal entries.

**Enforced by:** `InventoryItemServiceTest#getAggregatedInventory_shouldReturnPage`,
`InventoryItemControllerTest`, `InventoryPageControllerMvcTest` · **Code:**
`InventoryItemRepository#getAggregatedInventory`,
`InventoryAggregationService#getAggregatedInventory`, `AggregatedInventoryDto`,
`templates/inventory-index.html` · **Issues:** —

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

