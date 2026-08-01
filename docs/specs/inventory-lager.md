> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-25.
> **Owner area:** INV · **Related ADRs:** ADR-0003, ADR-0097, ADR-0098, ADR-0104

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
slice; a book-out / transfer chooses per dimension which earmarks (or the rest) its quantity is
deducted from, with a `SELL` crediting each mission proportionally to the SCU it sourced from that
mission; and the write-time merge unions the folded rows' allocations. The full rules live in
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

**Game-item rows (REQ-INV-029/030).** Since [`inventory-items.md`](inventory-items.md)
REQ-INV-029 the Lager also holds **game-item** stock rows with their own stack key —
`user · gameItem · location · personal · owningOrgUnit`, no quality dimension — rendered in
their own Items view (REQ-INV-030). The material tree and its grouping queries described here
exclude item rows **explicitly** (`material IS NOT NULL`); the item view mirrors the same
group-on-read semantics per that spec.

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
beneath the Auftrag/Einsatz controls. The **item variant** (`catalog=ITEM`, REQ-INV-030)
addresses a stack by `gameItemId` with **no quality key** — item rows carry no quality
dimension ([`inventory-items.md`](inventory-items.md) REQ-INV-029); paging and ordering are
identical.

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
  ownerless shared row the reconciler promotes later, REQ-INV-004). The owner's picker lists their
  direct memberships across **all four org-unit kinds** (Staffel + SK + Bereich + OL) via
  `GET /api/v1/users/{id}/memberships?allKinds=true`, mirroring the bank counterparty picker
  (REQ-BANK-044); the resolver already accepts a Bereich/OL pool (REQ-ORG-016), so a Bereich/OL-member
  owner can book into their Bereich/OL pool, not only their Staffel/SK. The browser reaches that
  endpoint through the frontend's `/users/{id}/memberships` proxy (`UserProxyController`, which
  relays `allKinds`) — the frontend origin maps no `/api/v1/users/**` route, so a direct call to the
  backend path 404s and silently hides the picker.
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
personal↔shared toggle is owner-scoped and lives on `/my`). Both owning-org-unit pickers — the
de-personalize picker (keyed on the row owner) and the location/user transfer picker (keyed on the
destination user) — offer the selected owner's direct memberships across all four org-unit kinds
(Staffel + SK + Bereich + OL). Each picker is **shown whenever that owner has at least one
membership** and is **preset to the row's current owning org unit** (or the owner's primary unit
when the current unit is not one of the owner's memberships, e.g. a cross-user transfer), so a submit
that does not touch the picker keeps the stock in its current unit rather than silently reassigning
it. A picker is hidden only for a membershipless owner — the moved/shared row is then ownerless.
There is no "keep home unit" placeholder: the picker always carries a concrete preselected unit
(#1328).

**Acceptance**

- [ ] Rebooking part of a personal row decrements the source and inserts a new `personal = false`
  row for the moved quantity; the source's other contributions are unchanged (append-only).
- [ ] Rebooking the whole row deletes the depleted source and leaves exactly the new row.
- [ ] A de-personalize stamps the new shared row on the picked org-unit pool (or the owner's sole
  membership, or `null` when membershipless), consistent with REQ-ORG-004.
- [ ] The owning-org-unit picker (de-personalize and location/user transfer) lists the selected
  owner's direct memberships across all four kinds (Staffel + SK + Bereich + OL, via
  `?allKinds=true`); a Bereich/OL-member owner can book into their Bereich/OL pool.
- [ ] The picker is visible whenever the owner has ≥1 membership (not only ≥2) and is preset to the
  row's current owning org unit, so submitting without changing it keeps the current unit; it is
  hidden only for a membershipless owner.
- [ ] A personalize carries the source row's `owningOrgUnit` over and refuses a source bound to a
  job order or mission with HTTP 400.
- [ ] A stale `version` yields HTTP 409; a non-owner without an admin/logistician grant yields 403.
- [ ] Each direction records its own audit event; the unified viewer's Lager filter lists both.

**Enforced by:** `InventoryItemServicePersonalRebookTest`, `InventoryItemControllerTest` ·
**Code:** `InventoryItemService#rebookPersonal`, `InventoryItemController#rebookPersonal`,
`InventoryItemPersonalRebookDto`, `inventory-my.html`, `inventory-admin.html`,
`fragments/inventory-stack-entries.html` · **Issues:** #1328

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

**Game-item rows (REQ-INV-029).** A **game-item** stock row
([`inventory-items.md`](inventory-items.md)) follows the `PIECE` auto-merge rule — items are
whole units, so they always merge on write regardless of the client's opt-in flag (the item
UI, PR 3, accordingly never renders the SCU opt-in checkbox for them). The merge identity of an item row is its item stack key (owner · gameItem · location
· `personal` · owning org-unit pool), and the `FOR UPDATE` merge-group query carries NULL-safe
material **and** quality branches plus the `gameItem` key — without them the item merge would
silently degenerate to a permanent no-op.

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

**Projection (chips only).** The outbound `InventoryItemDto` carries the two allocation lists
(`jobOrderAllocations` / `missionAllocations`) with their per-dimension unallocated rest, and **no**
per-entry association scalar (the transitional first-allocation `jobOrderId` / `missionId` fields are
gone once every reader consumes the allocations). Every read-only inventory listing that shows an
entry's orders — including the mission detail page's Lagereinträge table — renders **all** of the
entry's order chips with their amounts, not just the first. Every amount the split UI shows — the
order / mission chips, the rest chip, the book-out / Umbuchen and handover deduct-from pickers and
their hints — renders **whole (no decimals) for a `PIECE` material** and to three decimals only for
`SCU`.

**Assignment writes.** The earmarks are edited through dedicated per-allocation endpoints `POST` /
`PATCH` / `DELETE /api/v1/inventory/{id}/allocation` (add / change amount / remove), each gated by
`isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)` — the same owner-scoped
inventory-edit gate, **no new role**. They refuse a personal entry (personal stock carries no
assignment), refuse a job-order target whose material the order does not require (REQ-ORDERS-018),
reject a duplicate target, hold PIECE amounts whole, and enforce R5. Each mutation is audited
(`INVENTORY_ALLOCATION_ADDED` / `_CHANGED` / `_REMOVED`, REQ-AUDIT-001). The entry's `@Version` is the
single optimistic-lock token for its allocations (an inverse-side slice change force-increments it).

**Game-item rows (REQ-INV-031).** A **game-item** stock row
([`inventory-items.md`](inventory-items.md)) allocates only to qualifying `ITEM` orders whose
lines request that gameItem (the gameItem sibling of the REQ-ORDERS-018 material gate) and has
**no mission dimension** — a mission allocation on an item row is rejected with 400. Everything
else in this requirement (R5, chips, deduct-from plans, merge union) applies to item rows
unchanged on the job-order dimension.

**Split at check-in (R4).** The create payload additionally accepts per-dimension allocation lists,
so a book-in can be earmarked to several orders / missions with their own amounts in one shot,
under the same guards + R5. An empty list falls back to no assignment.

**Single-target shorthand at check-in.** Assigning a book-in to *one* order or *one* mission is the
common case, so the Einbuchen form does not make the user type the amount twice: when a dimension
names **exactly one** target and leaves its amount **blank**, the entry's **whole `amount`** is
earmarked to that target. The shorthand is resolved by the frontend write controller while mapping
the form to the create payload (the backend allocation input keeps its `@NotNull @Positive` amount —
the API contract is unchanged), applies to both dimensions independently, and counts as an
assignment for the "a personal entry carries no earmark" rule. It deliberately does **not** extend to
several targets: with two or more target rows there is no unambiguous split, so every amount must be
entered and a blank row is dropped as before. An explicitly entered amount always wins over the
shorthand, and a not-yet-picked row (no target) is neither counted as a target nor sent. The create
form states the rule as a per-dimension hint and hides that hint as soon as a second target is named.

**Delivered is per-(entry, job-order) slice (Variante A).** The "Geliefert" marker moved onto the
job-order allocation: an entry serving several orders can be delivered for one and open for another.
The order material-collection reads the slice's flag and shows the amount **allocated to that order**
(with the entry's total physical stock as context), while that total still backs the full-row
owner / location transfer.

**Merge unions allocations (R1).** The write-time stock merge (REQ-INV-026) folds on physical
identity and **unions** the folded rows' allocations into the survivor — summed per target, the
job-order delivered flag OR-combined. Because the survivor's amount already absorbed the folded
amounts, R5 is preserved under the fold.

**A book-out / transfer chooses which earmarks it deducts from.** Because the two dimensions are
independent, a book-out or transfer of quantity X carries a per-dimension **"deduct from" plan**
(`jobOrderReductions` / `missionReductions` on `InventoryItemBookOutDto`, each an
`AllocationReductionDto{targetId, amount}`): each dimension's plan names how much of X comes out of
which earmark slice, and whatever it leaves uncovered is taken from that dimension's not-yet-assigned
rest. A plan is validated against the pre-decrement slices — an unknown / duplicate / over-slice
target or a Σ over X is a 400, and an under-assigned plan whose rest cannot cover the remainder is a
422 — and a `null` list defaults to "take it all from the rest" (a full move then inherits every
earmark, a partial move leaves the tags intact). On a `TRANSFER` the reduced tags **move onto the new
row** (the moved stock stays earmarked to the same order / mission for the deducted amount).

The Ausbuchen and Umbuchen (Ort / Nutzer) modals render an interactive **"Herkunft" picker**
(`inventory-herkunft.js`, shared by the personal and global pages): one amount input per earmark tag
per dimension, read straight from the source leaf row's chips. The inputs default to `0` — the whole
deduction is taken from the not-yet-assigned rest ("Rest zuerst, Rest leer lassen") — and the picker
mirrors the backend rules client-side, disabling the submit and stating the minimum that must be
assigned to tags when the deduction exceeds a dimension's rest. For a `SELL` it also shows the coupled
per-mission proceeds estimate. A submit sends only the non-zero inputs as the plan; an entry with no
earmarks hides the picker and submits the legacy `null` plan.

**A determined dimension is prefilled, not demanded.** A dimension the entry splits across exactly
one tag with **no not-yet-assigned rest** admits a single applyable plan: the deduction has to come
out of that one tag in full, so every other value trips the "assign at least X" gate. The picker
therefore fills that field from the deducted amount itself, keeps it in step with every later edit of
the amount, locks it (`readOnly`) and labels it as auto-filled, so the modal is submittable the
moment it opens. This is a client-side affordance only — the resulting explicit plan is exactly the
one the backend's own `null`-plan default would have derived for that shape, so the write is
unchanged. A dimension with a rest, or with two or more tags, stays a free choice defaulting to `0`.

**A job-order handover draws only from its own order's slice, and clamps the mission dimension.** A
partial handover of quantity X to an order shrinks **that order's own slice** by X and lowers the
entry amount by X. X is capped at that order's slice on the entry — a handover fulfils only its own
order, so it may never draw from a sibling order's slice or from the free rest; the frontend sets the
amount field's max to that slice and the backend rejects an over-slice amount with **HTTP 400**.
Because only the fulfilled order's slice and the entry amount drop by the same X, R5 holds on the
job-order dimension with no sibling slice or rest touched. The same physical SCU leave the entry's
mission earmarks too, so the mission dimension is reduced by exactly the same X — resolved through the
shared `AllocationReductions` resolver (rest-first, then proportional by default, so a dual-tagged
partial handover no longer 422s). The handover item DTO carries an optional
`missionReductions` plan, and the handover modal renders the mission picker **only for the ambiguous
case** — the entry is earmarked to two or more missions and X exceeds the mission rest, so more than
one distribution is possible; otherwise the auto-clamp applies with no prompt.

**SELL proceeds are coupled to the mission deduct-from plan.** A `SELL` credits each mission a share
of the sale proceeds **proportional to the SCU deducted from its earmark** — `sellAmount ×
amount_j / X`, one squadron-`INCOME` `MissionFinanceEntry` per credited mission — with the rest (SCU
taken from the mission rest, plus SCU deducted from a mission the seller does not participate in)
staying the seller's personal proceeds. Only missions the seller participates in receive an entry;
there is no separate income-attribution input.

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
- [ ] A book-out / transfer "deduct from" plan is validated against the slices (unknown / duplicate /
  over-slice / over-total → 400; an under-assigned plan the rest cannot cover → 422); a transfer
  carries the reduced tags onto the moved row; a `null` plan takes it all from the rest.
- [ ] The Ausbuchen and Umbuchen modals render the "Herkunft" picker (one input per tag per
  dimension, defaulting to 0 = from the rest), block the submit while the plan is invalid, and send
  only the non-zero inputs; an entry with no earmarks hides the picker.
- [ ] A dimension with exactly one tag and no rest is prefilled with the deducted amount, locked and
  labelled as auto-filled: the modal is submittable without touching the picker, the field follows
  every later change of the amount, and the submitted plan deducts the full amount from that tag.
- [ ] A partial handover of a dual-tagged (order + mission) entry clamps the mission dimension by the
  handed amount instead of 422-ing (rest-first, then proportional by default); the handover modal
  shows the mission picker only when two or more missions and the handed amount exceeds the mission
  rest (the ambiguous case), and an explicit `missionReductions` plan is honoured/validated.
- [ ] A handover cannot exceed the order's own slice on the entry: the amount field's max is that
  slice, and an over-slice amount is rejected with HTTP 400 — a sibling order's slice and the free
  rest are never drawn from, so a multi-order entry cannot be silently over-allocated by a handover.
- [ ] A SELL credits each mission proportionally to the SCU deducted from its earmark
  (`sellAmount × scu/sold`), leaves the rest (unassigned + non-participated) personal, and books no
  entry when nothing is deducted from a mission earmark.
- [ ] Each allocation add / change / remove records the matching `INVENTORY_ALLOCATION_*` audit event.
- [ ] A book-in naming exactly one order / mission with a blank amount earmarks the entry's full
  amount to it (per dimension); with two or more targets a blank row is dropped, an explicit amount
  always wins, and a blank-amount row still trips the personal-entry rejection.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemServiceBookOutTest`,
`InventoryCheckoutServiceAuditTest`, `InventoryStockMergeTest`, `JobOrderHandoverServiceTest`,
`InventoryAllocationSoakDataTest`, `InventoryItemControllerTest`, `InventoryPageControllerMvcTest`,
`DatabaseIndexMigrationTest`, `InventoryInputAjaxControllerTest` (single-target shorthand),
e2e `InventoryOperationsE2eTest` (Herkunft picker gate + deduct-from) ·
**Code:** `InventoryJobOrderAllocation`, `InventoryMissionAllocation`,
`support/InventoryAllocations`, `InventoryItemController` (allocation endpoints),
`InventoryItemService#createInventoryItem`, `InventoryCheckoutService` (book-out / merge / SELL),
`InventoryAggregationService#getMaterialCollection`, `InventoryItemMapper`,
`V217__add_inventory_allocation_tables.sql`, `V218__drop_inventory_scalar_associations.sql`,
`fragments/inventory-stack-entries.html`, `inventory-my.js` / `inventory-admin.js`,
`inventory-herkunft.js` (deduct-from picker), `inventory-input.html` / `inventory-input.js`,
`InventoryWriteController#toAllocationInputs` (single-target shorthand) ·
**Issues:** #1182 · **ADR:** ADR-0098

### REQ-INV-028 — Aggregated per-material overview shows average and maximum quality

The per-material Lager overview (`GET /inventory`, `AggregatedInventoryDto`) rolls the in-scope
non-personal stock up to one row per material, showing the total amount, the **amount-weighted
average** quality and the **maximum** available quality (the best single entry's quality). The three
aggregates come from one grouped query — amount-weighted average, `MAX(quality)`, `SUM(amount)` over
`GROUP BY material`; the row links through to the per-material drilldown (`/inventory/all` filtered to
the material). Since REQ-INV-030 ([`inventory-items.md`](inventory-items.md)) the `/inventory`
page also offers an **item-variant** aggregate — one row per gameItem with the total amount but
**without** the quality columns (item rows carry no quality); the material variant described
here is unchanged and excludes item rows.

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

### REQ-INV-033 — Per-material and per-game-item drilldowns are paginated server-side (no silent cap)

The two per-catalog drilldowns — the per-material page (`GET /inventory/material/{materialId}`) and
its item sibling the per-game-item page (`GET /inventory/game-item/{gameItemId}`,
[`inventory-items.md`](inventory-items.md) REQ-INV-030) — each list the individual in-scope
inventory rows of one catalog entry. Both lists are **paginated server-side** and every row is
reachable page by page — a drilldown must never render only a fixed-size prefix of its rows without
saying so ([ADR-0104](../adr/0104-no-silent-caps-on-complete-list-surfaces.md); before this
requirement each frontend page fetched a single `size=1000` slice and silently hid the rest).

- **URL-driven paging.** `page` (zero-based) and `size` ride in the query string; the frontend
  forwards them to the backend's paginated `/api/v1/inventory/material/{id}` and
  `/api/v1/inventory/game-item/{id}` endpoints. A negative page clamps to `0`; a `size` outside the
  whitelist `50 / 100 / 200` snaps back to the default `50`, so a crafted URL cannot request an
  unbounded page.
- **Overrun pages clamp to the last page.** The page index rides in the URL (history) and a
  live-sync peer refresh re-fetches it verbatim, so a stale bookmark/deep-link or a peer's stock
  reduction can leave the URL pointing past the last page. Because an empty out-of-range page would
  collapse `totalPages` to `1` and hide the whole pager — stranding the viewer on an empty table
  while rows still exist on page 0 — the controller detects a non-empty result whose requested page
  overran and re-fetches the last page once, so the viewer always lands on real rows with a usable
  pager (the item drilldown's title, resolved from the page's first row, likewise re-appears). A
  genuinely empty catalog entry (`totalElements == 0`) is not re-fetched.
- **In-place pager.** The pager and the page-size picker render **inside** the swapped results
  fragment (the shared `fragments/pagination.html` component); a page or size click swaps the
  fragment in place with history (REQ-FE-005, `krtFetch.bindSwap`), so the URL always names the
  visible page and a live-sync peer refresh (REQ-FE-010/015) re-fetches exactly that page.
- **Fragment fetches stay lean.** A `fragment=results` render fetches only the items page — the
  material page's material-switcher catalog is full-render-only (the REQ-DATA-012 fragment-gating
  rule); the item page carries no navigate catalog at all, and neither drilldown fetches a job-order
  catalog (inline order re-assignment lives on the Lager stack-entry allocation chips, REQ-INV-027,
  not on these read-only pages).

**Acceptance**

- [ ] With more rows than one page holds, each drilldown shows a pager; every row is reachable by
  paging — no silently invisible remainder.
- [ ] `page`/`size` are forwarded to the backend; an out-of-whitelist `size` (e.g. the legacy
  `1000`) snaps back to the default `50` and a negative page clamps to `0`.
- [ ] A request for a page past the end of a non-empty entry re-fetches the last page (real rows +
  a usable pager), never an empty stranded table; a genuinely empty entry is not re-fetched.
- [ ] Page and size clicks swap the results fragment in place (no full reload); the material
  fragment render performs no material-catalog lookup.

**Enforced by:** `InventoryPageControllerTest`
(`viewMaterialInventory_forwardsPageAndWhitelistedSizeToBackend`,
`viewMaterialInventory_snapsOutOfListSizeBackToDefault`,
`viewMaterialInventory_withFragmentResults_returnsFragmentWithoutCatalogFetch`,
`viewMaterialInventory_clampsOutOfRangePageToLastPage`,
`viewMaterialInventory_doesNotClampAGenuinelyEmptyMaterial`,
`viewGameItemInventory_shouldReturnItemPageAndForwardPaging`,
`viewGameItemInventory_snapsOutOfListSizeAndReturnsResultsFragment`,
`viewGameItemInventory_clampsOverrunPageAndResolvesTitleFromLastPage`),
`InventoryPageControllerMvcTest` (`viewMaterialInventory_ShouldRenderPaginationControls`,
`viewMaterialInventory_FragmentSwap_RendersPagerWithoutCatalogFetch`,
`viewGameItemInventory_ShouldRenderPaginationControls`) · **Code:**
`InventoryPageController#viewMaterialInventory` / `#viewGameItemInventory`,
`templates/inventory-material.html`, `templates/inventory-game-item.html`,
`static/js/inventory-material.js`, `static/js/inventory-game-item.js`, `fragments/pagination.html`,
`InventoryItemController#getInventoryByMaterial` / `#getInventoryByGameItem` (backend paging) ·
**Issues:** — · **ADR:** ADR-0104

### REQ-INV-034 — "Alle markieren" selects the whole filtered view for bulk check-out (no silent cap)

The "Mein Lager" bulk bar (`/inventory/my`, both the Material and the Items view) offers an **"Alle
markieren"** button before **"Markierte ausbuchen"** that marks **every** entry of the current
filtered view for the bulk check-out, so the user need not expand each stack and tick each row by
hand. Because the grouped tree lazy-loads and paginates each stack (REQ-INV-005), a client-side
"check every visible box" would silently miss collapsed stacks and any entry past a stack's first
page; select-all therefore resolves the complete id set **on the server** ([ADR-0104](../adr/0104-no-silent-caps-on-complete-list-surfaces.md)
no-silent-cap principle).

- **Server-resolved id set.** The button fetches `GET /inventory/my/entry-ids`, which relays the
  page's active filter + `view` to the backend `GET /api/v1/inventory/my-inventory/entry-ids`. That
  endpoint returns the ids of every one of the caller's own entries matching the same optional-filter
  contract as the grouped view (`catalog=MATERIAL` uses material / min-quality / job-order / mission
  + the mutually exclusive personal toggles; `catalog=ITEM` uses gameItem / job-order + personal, and
    rejects the material-only filters with 400 exactly like the grouped endpoint, REQ-INV-029/031). It
    is owner-scoped from the JWT (no impersonation) and can never return an id outside the grouped
    view.
- **Selection is a decoupled set, not the DOM.** The frontend holds the selection in a Set that is
  independent of the lazily-loaded checkboxes: a loaded checkbox's checked state is derived from the
  set, a stack expanded after select-all comes up already ticked, and the "Markierte ausbuchen"
  count + POST read the set directly — so the bulk check-out spans the whole filtered view, not only
  the expanded stacks.
- **Toggle + safe reset.** The button toggles between "Alle markieren" and "Auswahl aufheben"
  (clear). The selection is reset whenever the grouped table re-swaps (filter change, post-write
  refresh, or a live-sync peer refresh, REQ-FE-010/015): the freshly rendered checkboxes come back
  unticked, and keeping stale ids across a re-render could aim the bulk check-out at an entry a peer
  already removed (the backend bulk-checkout 404s on any unknown id). Select-all itself does not
  re-swap the table, so a live selection survives drill-down expansion.
- **No new mutation.** Select-all is a read that feeds the existing bulk check-out
  (`POST /inventory/bulk-checkout`, REQ-INV-003); it adds no new write path and no new audit event
  (the bulk check-out's `INVENTORY_BULK_CHECKED_OUT` audit is unchanged).

**Acceptance**

- [ ] `/inventory/my` (Material and Items view) renders an "Alle markieren" button before "Markierte
  ausbuchen"; clicking it marks every entry of the current filtered view, including entries in
  collapsed stacks and beyond a stack's first page, and the count reflects the full total.
- [ ] `GET /api/v1/inventory/my-inventory/entry-ids` returns exactly the ids of the caller's own
  entries matching the given filter + catalog, is owner-scoped, and rejects catalog-mismatched
  filters with 400 like `…/my-inventory/grouped`.
- [ ] Changing a filter, a modal write, or a peer refresh clears the selection (no stale id reaches
  the bulk check-out); expanding a stack after select-all shows its rows already ticked.

**Enforced by:** `InventoryItemControllerTest`, `InventoryPageControllerTest`,
`InventoryPageControllerMvcTest`, `InventoryAggregationServiceTest` · **Code:**
`InventoryItemController#getMyEntryIds`, `InventoryAggregationService#getMyEntryIds` /
`#getMyItemEntryIds`, `InventoryItemService#getMyEntryIds`,
`InventoryItemRepository#findUserEntryIds` / `#findUserItemEntryIds`,
`InventoryPageController#myEntryIds`, `templates/inventory-my.html`, `static/js/inventory-my.js` ·
**Issues:** — · **ADR:** ADR-0104

### REQ-INV-035 — Refinery output can be stored straight into the personal pool

The refinery-order **store dialog** (`/refinery-orders/{id}`, "Einlagern") offers a per-output-row
**personal marker** — the same `inventory_item.personal` flag the Einbuchen dialog
(`InventoryItemService#createInventoryItem`) and the item-production book-in
([`orders-item-production.md`](orders-item-production.md) REQ-INV-032) already carry. Before this,
refinery output was **always** shared squadron stock: a member who refined their own ore had to
store it shared and then run the personal-rebooking split of
[REQ-INV-007](#req-inv-007--personal-marker-rebooking-umbuchung-is-an-append-only-split) on
`/inventory/my` to get it into their private pool — a second, easily-forgotten step whose intent was
already known at storage time.

- **Per row, not per order.** The marker is a field of `RefineryOrderStoreItemDto` (optional; `null`
  means `false`), so a split store (the dialog's "+" duplicate) can send one part of an output good
  to the shared pool and another to the receiver's private pool in the same call. Everything else
  about the row is unchanged: it is still append-only (REQ-INV-001), still stamped with the
  receiver's resolved owning org unit, and `personal` is part of its stack identity
  (REQ-INV-002/026), so a personal and a shared row never group or merge together.
- **A personal row carries no earmark.** This is the standing `assertNotPersonal` invariant
  (REQ-INV-027), and the store flow honours it per allocation dimension according to where the
  earmark comes from:
  - the **job order** is a per-item pick in the same dialog, so combining it with the marker is a
    contradictory *user choice* and is **refused (HTTP 400)** rather than silently dropped —
    mirroring the Einbuchen and production-book-in guards. The dialog additionally disables and
    clears a row's job-order picker while its box is ticked, so the rejection is only reachable
    without JS or with a tampered payload.
  - the **mission** is derived from the refinery order, never picked per item, so it is simply
    **not applied** to a personal row: marking the batch personal *is* the act of taking it out of
    the mission pool. A shared row of the same store call keeps the order's mission earmark
    unchanged.
- **Audit.** The existing `INVENTORY_RECEIVED_FROM_REFINERY` event gains a `personal` detail (no new
  event type, no new viewer filter entry); REQ-AUDIT-001's coverage of the Raffinerie area is
  unchanged.

**Acceptance**

- [ ] The store dialog renders a personal checkbox per output row, bound to `items[i].personal`, and
  a split (duplicated) row carries its own independent marker.
- [ ] Storing with the marker set creates the row with `personal = true`; omitting the field (older
  client) or leaving it unticked still creates shared squadron stock.
- [ ] An item combining the marker with a job order is rejected with 400 and writes **no** inventory
  row and **no** order status change; ticking the box in the dialog disables and clears that row's
  job-order picker.
- [ ] On a mission-linked refinery order, a personal row carries no mission allocation while a
  shared row of the same call still does.

**Enforced by:** `RefineryOrderServiceTest` (`PersonalMarkerTests`), `RefineryOrderTest`,
`RefineryStorePersonalMarkerTest` · **Code:** `RefineryOrderService#storeRefineryOrder`,
`RefineryOrderStoreItemDto`, `RefineryOrderWriteController#storeOrder` / `#storeOrderAjax` /
`#storePersonalWithJobOrder`, `RefineryOrderStoreItemForm`,
`templates/refinery-orders-details.html`, `static/js/refinery-orders-details.js` · **Issues:** — ·
**ADR:** —

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

