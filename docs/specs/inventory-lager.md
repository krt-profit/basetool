> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-04.
> **Owner area:** INV · **Related ADRs:** ADR-0003, ADR-0097, ADR-0098, ADR-0101, ADR-0104,
> ADR-0120, ADR-0124, ADR-0302

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
`material`, `location`, `quality`, the `personal` flag, the owning org-unit pool
(`owningOrgUnit`) and the „gestohlen" marker (`stolen`, REQ-INV-053). Since Variante C (ADR-0098,
REQ-INV-027) the job-order / mission earmarks are **no** longer part of it — they are per-entry
to-many allocations, not a stack dimension.

## Requirements

### REQ-INV-001 — Inventory is append-only by default (merge is the scoped exception)

A write path does **not** fold a new or edited `InventoryItem` into a different existing row
**unless the scoped stock merge of [REQ-INV-026](#req-inv-026--write-time-stock-merge-for-piece-auto-and-scu-per-action-opt-in)
applies** — a `PIECE` material always, an `SCU` material only on the caller's per-action opt-in.
Outside that merge, each of create (Einbuchen), book-out **TRANSFER** (the moved quantity at the
target), personal rebooking (REQ-INV-007), bulk rebooking (REQ-INV-036), refinery store and any
future inbound path inserts its own row, and an in-place edit (note, allocation, delivered flag)
changes only the row it targets; two rows that share the stock identity coexist as separate rows
and are never summed in the database. (A general "edit the row" write no longer exists: the former
`PUT /api/v1/inventory/{id}` association update was removed with Variante C in favour of the
per-allocation endpoints of REQ-INV-027.) In
particular an **`SCU`** write with no opt-in stays append-only exactly as before. The former
*unconditional* read-add-write merge — and the pessimistic lock that guarded its lost-update race —
remain removed for the append-only paths; the merge re-introduces a pessimistic lock only on its own
path (REQ-INV-026). Every append, transfer and split carries the row's „gestohlen" marker
(REQ-INV-053).

A stock write through the exchange (REQ-XCH-016) follows the same rules. Its book-in inserts a new
personal row without an org unit, records `INVENTORY_ITEM_CREATED` and then runs the write-time
merge without the `SCU` opt-in, so piece-counted stock joins its existing row; its book-out is the
Lager's own `DISCARD` book-out (REQ-INV-025). *Corrected 2026-09-27: the planned amendment said
both go through the same services; the book-in is built by the exchange's own stock service
(`ExchangeStockWriteService`), not by the Lager's create, and only the merge and the book-out are
the Lager's.*

**Acceptance**

- [ ] Creating **`SCU`** stock (no opt-in) that matches an existing row's stock identity yields a
  second row; the existing row's amount is unchanged.
- [ ] An in-place edit of a row (note, allocation, delivered flag) never deletes it in favour of a
  matching row; it is saved in place.
- [ ] A partial **`SCU`** TRANSFER (no opt-in) decrements the source and inserts a new row at the
  target even when an identical target stack already exists; the existing target row is unchanged.
- [ ] Storing a refinery output inserts a new row with its own note; no existing row's amount or note
  changes (refinery outputs are `SCU`).
- [ ] A `PIECE` write, or an `SCU` write with the opt-in, merges per REQ-INV-026 instead of appending.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemServiceBookOutTest`,
`InventoryStockMergeTest`, `RefineryOrderServiceTest` · **Code:** `InventoryItemService`,
`InventoryCheckoutService`, `RefineryOrderService` · **Issues:** #466, #1182

### REQ-INV-002 — Group-on-read display: Material → Stack

The grouped Lager views (the options „Mein Lager" and „Globales Lager" of the Lager tab,
`/inventory?storage=my` and `/inventory?storage=all`, REQ-UI-026) present each material as a group
whose stacks are computed **in SQL** (a `GROUP BY` over the stock identity) at read time. The
nullable to-one in the stack key — `owningOrgUnit` — (and `material`, which is null on game-item
rows) is **left-joined** and grouped on its join alias: a stack with no owning org-unit (a personal
stack, or a membershipless owner's shared stack) must still surface. (A constructor-expression
projection over a nullable to-one otherwise renders an implicit inner join that silently drops every
such stack — the regression that emptied both grouped views in v0.4.0, when `jobOrder` and `mission`
were still nullable stack dimensions; since Variante C they are per-entry allocations and no longer
part of the key, REQ-INV-027.) Each stack shows the summed amount, the amount-weighted mean
quality, the max quality and the entry count. The grouped response carries **only** the collapsed stack rows — it does **not** inline
the underlying entries (those are loaded on demand, see REQ-INV-005). The UI renders two server
levels — material group → stack row — and a stack row expands to fetch its entries. Both grouped pages present these two levels (plus the lazy
entry rows) as a single design-system **tree-table** (`das-kartell-design` → `.tree-table`):
one sticky column header for all depths, depth shown by indentation + left rails rather than a
repeated per-level header, right-aligned tabular numbers with a 0-1000 quality gauge, and a note
rendered only when an entry has one. The
per-material aggregate page (`/inventory`, `AggregatedInventoryDto`) is unchanged.

The exchange groups differently and on purpose: its **lot** is material (or game item) + location +
quality + „gestohlen" over every row the member holds, personal **and** shared, **across** their
org-unit pools (REQ-XCH-016, ADR-0218, ADR-0230). A lot can therefore span several stacks of this
view, and rebooking a row between personal and shared does not change its lot.

**Game-item rows (REQ-INV-029/030).** Since [`inventory-items.md`](inventory-items.md)
REQ-INV-029 the Lager also holds **game-item** stock rows with their own stack key —
`user · gameItem · location · personal · owningOrgUnit · stolen`, no quality dimension — rendered in
their own Items view (REQ-INV-030). The material tree and its grouping queries described here
exclude item rows **explicitly** (`material IS NOT NULL`); the item view mirrors the same
group-on-read semantics per that spec.

Both tree levels are collapsible, and their expand/collapse state is **persisted per user**
(`localStorage`, keyed by the viewer's id — `expanded_rows_lager_*` for material groups,
`expanded_stacks_lager_*` for location stacks) and re-applied on initial load **and after every
in-place grouped-table re-swap** — a filter change or a modal write (book-out, Umbuchen and
bulk-checkout all re-swap `#inventoryTable` on success, REQ-INV-044/REQ-INV-007). A fragment swap
does not re-fire `DOMContentLoaded`, so the expansion is restored explicitly after the swap; a
restored stack re-fetches its (now up-to-date) entries. A modal mutation therefore never collapses
the tree the user was working in.

**Acceptance**

- [ ] Rows sharing the stock identity appear as one stack row with the correct summed amount,
  amount-weighted mean quality and entry count.
- [ ] Rows differing in any stock-identity dimension appear as separate stacks.
- [ ] A stack with no owning org-unit, and a stack whose entries carry no job-order or mission
  allocation, still appears in the grouped view; a `null` owning org-unit never hides the stack.
- [ ] The grouped response contains no per-entry rows (entries are lazy — REQ-INV-005).
- [ ] Both grouped pages render the collapsed stack rows without error.
- [ ] After an in-place grouped-table re-swap (filter change or modal write), the material groups
  and location stacks the user had expanded stay expanded and a restored stack re-loads its
  entries; a modal mutation never collapses the tree.

**Enforced by:** `InventoryItemServiceAggregateTest`, `InventoryItemStackQueryTest`,
`InventoryItemStackQueryDataTest`, `InventoryPageControllerMvcTest`, `InventoryOperationsE2eTest`
· **Code:** `InventoryAggregationService#buildGroupedFromStacks`,
`InventoryItemRepository#findUserStacks` / `#findGlobalStacks`, `InventoryStackAggregate`,
`InventoryStackDto`, `GroupedInventoryDto`, `inventory/inventory-my.html`, `inventory/inventory-admin.html`,
`static/js/inventory/inventory-common.js` (tree expand/collapse persistence, shared by `inventory-my.js` and
`inventory-admin.js` since FE-SIMP-03) · **Issues:** #466

### REQ-INV-044 — Actions operate per entry

> **Renumbered 2026-09-22:** this requirement was `REQ-INV-003` until 2026-09-22; that id also named the server-side SCU/PIECE enforcement and storage scale in [`inv-material-quantities.md`](inv-material-quantities.md), which keeps it.

Every single-row Lager action — book-out (consume / transfer / sell), personal-marker rebooking
(Umbuchung, REQ-INV-007), note edit, delivered toggle, allocation add / change / remove
(REQ-INV-027) — targets a single `InventoryItem` by id and `version`. The grouped stack row is
display-and-expand only; it carries no aggregate mutation. Optimistic locking and the frontend
`data-version` DOM-sync therefore continue to work unchanged at the entry level.

The two **bulk** actions — bulk check-out and bulk rebooking (REQ-INV-034 / REQ-INV-036) — are the
deliberate exception: they act on a list of the caller's own entry ids, which the bulk bar and the
server-resolved select-all set hold without versions, so each listed row is loaded under a
pessimistic write lock (`InventoryItemRepository#findByIdForUpdate` / `#findByIdForRebook`) instead
of an optimistic `version` check. They are still per-entry: each listed row is checked out or moved
on its own, never a stack aggregate.

**Acceptance**

- [ ] Book-out / personal-rebook / note / delivered / allocation endpoints accept an item id +
  version and affect only that row.
- [ ] The bulk check-out / bulk rebook endpoints act only on the listed ids, each row locked
  pessimistically; they never mutate a stack as a whole.
- [ ] The expanded entry view exposes those actions per entry; the stack row exposes none.

**Enforced by:** `InventoryItemControllerTest`, `InventoryPageControllerMvcTest` · **Code:**
`InventoryItemController`, `InventoryCheckoutService#bulkCheckout` / `#bulkRebook`,
`InventoryPageController`, `InventoryWriteController` · **Issues:** #466

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
the stock-identity fields the stack already exposes (`materialId`, `locationId`, `quality`,
`personal` on `/my`, the owning `userId` on `/all`, and `owningOrgUnitId`, whose absence selects the
rows with no owning org unit), returned **oldest-first** by
`createdAt` and **paginated** (default 20, max 100 per page). A composite index
`idx_inventory_item_stack_key` on the inventory natural key backs both the grouped `GROUP BY`
and the per-stack entries lookup. The `/all` drill-down re-applies the same org-unit scope
predicate as the grouped view; the `/my` drill-down is owner-scoped from the JWT (no
impersonation). Per-entry actions (REQ-INV-044) operate on the fetched rows unchanged. Each
drill-down row shows the entry's amount, its job-order / mission allocation chips with the
`+ Zuordnen` picker (REQ-INV-027) and the per-entry actions (book-out, Umbuchen, note — plus, on
`/inventory/my`, the Materialbörse release toggle of [`materialboerse.md`](materialboerse.md)),
with the note preview rendered beside the action buttons rather than below them; `createdAt` is the
entries' ordering key, not a displayed column. The three per-entry actions are compact icon buttons
(`krt-icon-bookout`, `krt-icon-rebook`, `krt-icon-edit`; their labels carried in `aria-label` /
`title`) so the dense action column never crowds — or overlaps — the amount beside it; on
tablet-width and narrower (≤ 1024px) the amount and actions reflow onto their own line beneath the
Auftrag/Einsatz controls. The **item variant** (`catalog=ITEM`, REQ-INV-030)
addresses a stack by `gameItemId` with **no quality key** — item rows carry no quality
dimension ([`inventory-items.md`](inventory-items.md) REQ-INV-029); paging and ordering are
identical.

**Acceptance**

- [ ] A stack's entries are returned oldest-first by `createdAt`.
- [ ] A requested page size above 100 is clamped to 100; an absent page/size yields the first 20.
- [ ] The drill-down never returns rows outside the caller's org-unit / owner scope.
- [ ] Expanding a stack on either grouped page fetches and renders its entries without error.
- [ ] At any viewport width the per-entry amount and action buttons never overlap; the book-out,
  Umbuchen and note actions are icon buttons and, on tablet-width and narrower (≤ 1024px),
  amount + actions reflow onto their own line beneath the Auftrag/Einsatz controls.

**Enforced by:** `InventoryItemStackQueryTest`, `InventoryItemControllerTest`,
`InventoryPageControllerMvcTest`, `DatabaseIndexMigrationTest` · **Code:**
`InventoryItemController#getMyStackEntries` / `#getAllStackEntries`,
`InventoryItemRepository#findUserStackEntries` / `#findGlobalStackEntries`,
`InventoryPageController#viewMyStackEntries` / `#viewAllStackEntries`,
`inventory/fragments/inventory-stack-entries.html`, `static/css/kernel/styles.css` (`.tree-row--leaf`, `.btn-icon`),
`inventory/inventory-my.html` / `inventory/inventory-admin.html` (note-button DOM sync),
`V143__add_inventory_item_stack_key_index.sql` (index rebuilt without the job-order / mission
columns by `V218__drop_inventory_scalar_associations.sql`) ·
**Issues:** #466

### REQ-INV-046 — "Mein Lager" personal- / non-personal-entries-only filters

> **Renumbered 2026-09-22:** this requirement was `REQ-INV-006` until 2026-09-22; that id also named the blueprint import's match on the normalized `output_name` in [`blueprint-import-name-matching.md`](blueprint-import-name-matching.md), which keeps it.

The personal Lager view (`/inventory/my`) offers two **mutually exclusive** stock-kind filters
alongside the existing material / min-quality / job-order / mission filters: a **personal-entries-only**
toggle (query param `personalOnly=true`) narrows the grouped result to the caller's private stock
(`personal = true` rows), and a **non-personal-entries-only** toggle (query param
`nonPersonalOnly=true`) narrows it to the caller's shared stock (`personal = false` rows). When both
params are absent or `false`, the caller's shared contributions and their personal stock are both
returned — the unchanged default. The UI offers the two flags as one segment „Alle · Persönlich ·
Gemeinsam" (radio `personalScope`, values `''` / `personal` / `shared`) in the toolbar of both views,
so they are exclusive by construction; the page translates the segment into the two query params and
stores it as the same two booleans (REQ-UI-017). The backend query intersects the two clauses, so
were both ever `true` the result is simply empty.

*Amended 2026-10-03 (website overhaul phase 3): the two checkboxes became the segment „Alle ·
Persönlich · Gemeinsam"; the query params and the stored preference are unchanged.* The narrowing is applied **in SQL** by the same group-on-read query
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
  behaviour); the segment „Alle · Persönlich · Gemeinsam" can select only one of the two.
- [ ] The material-group aggregates reflect only the visible stacks under the active filter.
- [ ] Both flags compose with the material / min-quality / job-order / mission filters and are
  reflected in the page URL.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemStackQueryDataTest`,
`InventoryItemControllerTest`, `InventoryPageControllerTest`, `InventoryPageControllerMvcTest` ·
**Code:** `InventoryItemRepository#findUserStacks`,
`InventoryItemService#getMyAggregatedInventory`, `InventoryItemController#getMyGroupedInventory`,
`InventoryPageController#viewMyInventory`, `inventory/inventory-my.html` · **Issues:** #466

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
  `GET /api/v1/org-units/members/{id}/memberships?allKinds=true`, mirroring the bank counterparty picker
  (REQ-BANK-044); the resolver already accepts a Bereich/OL pool (REQ-ORG-016), so a Bereich/OL-member
  owner can book into their Bereich/OL pool, not only their Staffel/SK. The browser reaches that
  endpoint through the frontend's `/users/{id}/memberships` proxy (`UserProxyController`, which
  relays `allKinds`) — the frontend origin maps no `/api/v1/users/**` route, so a direct call to the
  backend path 404s and silently hides the picker.
- **shared → personal** (personalisieren): the moved quantity becomes the owner's private stock
  (`personal = true`), carrying the source row's existing `owningOrgUnit` over. A source row bound to
  a job order or mission is **refused** (HTTP 400) — a personal row may never carry either
  association.

A rebooking is not the only way a personal row's unit changes: the owner may set it afterwards on
its own, including to no unit (REQ-INV-052). A
personal row booked in through the exchange carries no unit (REQ-XCH-016).

The operation is an **append-only split** (REQ-INV-001), structurally identical to the book-out
`TRANSFER` branch: the moved `amount` is decremented off the source row (the source row is deleted
when it depletes below the quantity epsilon) and inserted as its own new row with the opposite
`personal` flag — it is never folded into an existing stack. It is per-entry (REQ-INV-044), guarded
by optimistic locking on the source row's `version`, and owner-scoped (`@inventoryAccessPolicy.canEditInventoryItem`;
an admin/logistician may act within scope). Every rebooking records its own audit event
(`INVENTORY_ITEM_DEPERSONALIZED` / `INVENTORY_ITEM_PERSONALIZED`, REQ-AUDIT-001).

The split itself is the Lager's row rebooking, which other modules reach by lot through
`RebookCommands` (REQ-INV-060). Two parts are the web's own extras on top of it: the pool picked by
the owner or auto-stamped for a sole membership, and the shared row without a unit for a
membershipless owner.

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
`InventoryItemPersonalRebookDto`, `inventory/inventory-my.html`, `inventory/inventory-admin.html`,
`inventory/fragments/inventory-stack-entries.html` · **Issues:** #1328

### REQ-INV-025 — Book-out validates the CheckoutType; a target-less TRANSFER is rejected

Book-out (`POST /api/v1/inventory/{id}/book-out`) resolves the `CheckoutType` before mutating the
row. An **absent** `type` is inferred — `TRANSFER` when the request carries a target user or
location, otherwise `DISCARD`. An **explicit** `type = TRANSFER` is *not* re-inferred, so a
`TRANSFER` carrying neither `targetUserId` nor `targetLocationId` has nowhere to move the stock to.
Such a request is **rejected with HTTP 400** up front — before any decrement, delete or audit
write. It must never fall through to the consume/discard tail: doing so would silently destroy the
source stock and record it as `INVENTORY_ITEM_CONSUMED` with `type = TRANSFER`, an audit lie about
a mutation the caller never requested. (The complementary no-op guard — a `TRANSFER` whose target
resolves to the source's own user *and* location — likewise 400s with the localized
`error.inventory.transfer.unchanged`; the append-only move of REQ-INV-001 is defined only for a
target that actually differs.) Both Umbuchen dialogs (`/inventory/my`, `/inventory/all`) preset the
target to the row's own user and location, so they refuse that no-op themselves: a submit whose
target user and location both still equal the source shows `error.inventory.rebook.unchanged` as a
toast, keeps the dialog open and sends nothing. A rejected book-out writes **no**
audit event, consistent with the audit contract that only committed state mutations are logged
(REQ-AUDIT-001).

An exchange book-out (REQ-XCH-016) is this book-out with `CheckoutType.DISCARD`
(`InventoryCheckoutService#bookOutForClient`), row by row; the Materialbörse offers it lowers or
removes are audited with the reason `stock`. `CheckoutType` is not part of the exchange contract,
and `ExternalContractTest` does not pin it.

**Acceptance**

- [ ] An explicit `type = TRANSFER` with both `targetUserId` and `targetLocationId` absent yields
  HTTP 400; the source row's amount is unchanged, no new row is inserted, the source is not
  deleted, and no audit event is recorded.
- [ ] A `type = TRANSFER` carrying at least one target proceeds as the append-only move
  (REQ-INV-001).
- [ ] An absent `type` with no target is still inferred as `DISCARD` (unchanged) and consumes the
  stock, logging `INVENTORY_ITEM_CONSUMED` with `type = DISCARD`.
- [ ] An Umbuchen submit with the preset (unchanged) user and location sends no request and shows
  the localized "unchanged" toast; the stack stays intact.

**Enforced by:** `InventoryItemServiceBookOutTest`,
`InventoryOperationsE2eTest#edgeCaseTransferToSameLocationLeavesStockUnchanged` · **Code:**
`InventoryCheckoutService#bookOutInventoryItem` (public façade
`InventoryItemService#bookOutInventoryItem`), `inventory-my.js` / `inventory-admin.js`
`submitUmbuchen` · **Issues:** —

### REQ-INV-026 — Write-time stock merge for PIECE (auto) and SCU (per-action opt-in)

A write that lands a row whose material's quantity type is **`PIECE`** (Stück) is merged into a
single Lager entry with every existing row that shares its stock identity; a write whose material is
**`SCU`** does the same **only when the caller opts in for that one action** — a modal checkbox that
is per-transaction and **never persisted**. The merge runs on the inbound write paths that land a
row: create (Einbuchen), the book-out **TRANSFER** target (the Umbuchen modal's location / user
transfer), the personal rebooking (Umbuchen, REQ-INV-007) and every row moved by the bulk rebooking
(REQ-INV-036); the item-production book-in ([`inventory-items.md`](inventory-items.md) REQ-INV-032)
and the exchange's book-in (REQ-XCH-016) run it with the opt-in off, so only their always-merging
rows fold. The merge key includes the „gestohlen" marker (REQ-INV-053), so stolen and legitimate
stock never merge. The refinery store does not merge. An `SCU` write without the opt-in stays
append-only (REQ-INV-001); the single-row modals offer the opt-in checkbox only on `SCU` rows (a
`PIECE` row always merges, so no choice is shown), while the bulk modal always offers it because a
selection can mix both. The **Einbuchen** form narrows it further: it shows the checkbox only when a
row to merge with exists, asking `GET /api/v1/inventory/merge-candidates` (relayed by the frontend as
`GET /inventory/merge-candidates`) with the form's owner, material, location, quality, `personal`,
`stolen` and picked org unit each time one of them changes. The probe resolves the owning org unit
exactly as the create does (auto-stamp, active-context pin), counts with the merge key and offer
exclusion below, takes no lock, answers `false` where the booking itself would be refused, and
needs the on-behalf scope of REQ-SEC-005 to probe another member. Hiding the checkbox unticks it; a
probe that fails shows it, so the opt-in is never lost to an outage.

The **merge identity** is the **physical** stack key (Variante C, REQ-INV-027): owner · material ·
location · quality · `personal` · owning org-unit pool (a `NULL` pool matches `NULL`) · `stolen`;
job-order / mission earmarks are **not** part of it. The just-written row is the **survivor**; every
other row sharing that identity is folded into it — `amount`s summed, **distinct notes
concatenated** (first-seen order, newline-joined, truncated to the 1000-char note column) and the
folded rows' allocations **unioned** into the survivor (summed per target, the per-slice job-order
`delivered` flag OR-combined — rule R1 of REQ-INV-027) — and then deleted.

**Game-item rows (REQ-INV-029).** A **game-item** stock row
([`inventory-items.md`](inventory-items.md)) follows the `PIECE` auto-merge rule — items are
whole units, so they always merge on write regardless of the client's opt-in flag (the Items
view accordingly never renders the SCU opt-in checkbox for them). The merge identity of an item row
is its item stack key (owner · gameItem · location · `personal` · owning org-unit pool ·
`stolen`), and the `FOR UPDATE` merge-group query carries NULL-safe
material **and** quality branches plus the `gameItem` key — without them the item merge would
silently degenerate to a permanent no-op.

**Materialbörse invariant.** A merge **never** changes a Materialbörse entry (see
[`materialboerse.md`](materialboerse.md)). A row that backs *any* `MaterialExchangeOffer` (any status)
is excluded from the merge — it is never a survivor whose amount changes and never folded away: the
`inventory_item` FK is `ON DELETE CASCADE` (V210), so deleting such a row would silently destroy the
offer. The offered quantity is therefore never increased by a merge.

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

- [ ] A `PIECE` create / transfer / rebook that matches an existing stack folds the rows into one:
  amounts summed, distinct notes combined, allocations unioned (REQ-INV-027 R1), the folded rows
  deleted.
- [ ] An `SCU` write merges only when the per-action opt-in is set; without it the row stays separate
  (append-only). In the single-row Einbuchen / Umbuchen modals the opt-in checkbox renders only for
  `SCU` materials.
- [ ] The Einbuchen form shows the opt-in only while a mergeable row with the form's resolved stock
  identity exists, and unticks it when it hides it.
- [ ] A row backing a Materialbörse offer is never merged (neither survivor nor folded), and the
  offered quantity is unchanged by any merge.
- [ ] Two concurrent same-stack writers do not double-count or lose stock (the `FOR UPDATE` group
  serialises them).
- [ ] The deployment backfill merges matching pre-existing `PIECE` rows and leaves `SCU` rows and
  offer-backed rows untouched.
- [ ] Each fold records one `INVENTORY_ITEM_MERGED` audit event; the unified viewer's Lager filter
  lists it.

**Enforced by:** `InventoryStockMergeTest`, `InventoryItemServiceTest`,
`InventoryItemServiceBookOutTest`, `InventoryItemServicePersonalRebookTest`,
`InventoryMergeProbeTest`, `InventoryMergeGroupDataTest`, `InventoryPageControllerMvcTest` ·
**Code:** `InventoryCheckoutService#mergeStockIfRequested`,
`InventoryItemRepository#findMergeGroupForUpdate`, `InventoryItemService#hasMergeCandidates`,
`InventoryItemRepository#countMergeCandidates`,
`MaterialExchangeOfferRepository#existsByInventoryItemId`, `InventoryItemService`,
`V216__merge_piece_inventory_rows.sql`, `inventory/inventory-input.html` / `inventory-input.js`,
`inventory/inventory-my.html` / `inventory-my.js`, `inventory/inventory-admin.html` / `inventory-admin.js`
· **Issues:** #1182 · **ADR:** ADR-0097

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
per-entry association scalar (the transitional first-allocation `jobOrderId` / `missionId` fields
have been removed). Every read-only inventory listing that shows an
entry's orders — including the mission detail page's Lagereinträge table — renders **all** of the
entry's order chips with their amounts, not just the first. Every amount the split UI shows — the
order / mission chips, the rest chip, the book-out / Umbuchen and handover deduct-from pickers and
their hints — renders **whole (no decimals) for a `PIECE` material** and to three decimals only for
`SCU`.

**Assignment writes.** The earmarks are edited through dedicated per-allocation endpoints `POST` /
`PATCH` / `DELETE /api/v1/inventory/{id}/allocation` (add / change amount / remove), each gated by
`isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)` — the same owner-scoped
inventory-edit gate, **no new role** — and, like every other per-row write, the service then refuses
a caller who neither owns the entry nor is Logistician or above (`403`,
`AuthHelperService.isLogisticianOrAbove()`); until 2026-10-04 that second gate was missing, so any
member whose scope covered the unit could earmark another member's row. They refuse a personal entry (personal stock carries no
assignment), refuse a job-order target whose material the order does not require (REQ-ORDERS-018),
reject a duplicate target, hold PIECE amounts whole, and enforce R5.

**The duplicate-target refusal is not a UI dead end.** The `+ Zuordnen` picker drops targets already
on the entry, but it does so in Thymeleaf, at fragment-**render** time: the success handler
re-renders the chips and not the `<option>` list, and the writer is excluded from their own live-sync
room (REQ-FE-015), so a target *this* viewer just allocated stays on their list until the fragment is
re-pulled. The picker therefore resolves a pick against the **chips** — which are re-rendered from
every write's own response and so are always current — and an already-allocated target opens its
existing slice in **edit** mode (amount prefilled, *Entfernen* shown, save issues the `PATCH`)
instead of arming a duplicate `POST`. A duplicate that still reaches the backend — two viewers
racing on one shared entry, which no client-side check can rule out — is refused with **400** and
its localized problem `detail` is shown to the user (the throw sites carry
`error.inventory.allocation.duplicate.{jobOrder,mission}`, resolved per `Accept-Language` by
`GlobalExceptionHandler#resolveDetail`), not swallowed into a generic failure toast. Each mutation is audited
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
entered. A targeted row left blank is **refused, not dropped**: the form marks the row and does not
submit, and the frontend write controller answers `422` with code
`INVENTORY_ALLOCATION_AMOUNT_MISSING` (classic path: a field error) without calling the backend.
*Amended 2026-10-02 by the owner: the blank row used to be dropped silently, so a member who named
two orders and typed one amount earmarked only that one without being told.* An explicitly entered
amount always wins over the
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

**A tag is labelled from the chip's `data-target-label`, never from its markup.** Every allocation
chip — the server-rendered one and the one `inventory-common.js` builds after an in-place allocation
write — carries the picker label as `data-target-label`: `#<displayId>` for a job order, the mission
name and its planned start date (`format.date`, as in the mission combobox) for a mission. A mission
the backend cannot resolve is labelled with the localized `inventory.assoc.mission.unknown`; the
raw target id is never shown.

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
- [x] Adding, changing or removing an allocation on another member's entry is refused with `403`
  for a caller below Logistician and changes nothing; the owner and a Logistician pass
  (`InventoryItemServiceAllocationTest`, `InventoryItemControllerTest`).
- [ ] `delivered` toggled for one order leaves the entry's other orders unchanged; the order
  material-collection shows the amount allocated to that order.
- [ ] A stock merge sums the folded rows' allocations per target and OR-combines job-order delivered.
- [ ] A book-out / transfer "deduct from" plan is validated against the slices (unknown / duplicate /
  over-slice / over-total → 400; an under-assigned plan the rest cannot cover → 422); a transfer
  carries the reduced tags onto the moved row; a `null` plan takes it all from the rest.
- [ ] The Ausbuchen and Umbuchen modals render the "Herkunft" picker (one input per tag per
  dimension, defaulting to 0 = from the rest), block the submit while the plan is invalid, and send
  only the non-zero inputs; an entry with no earmarks hides the picker.
- [x] Each picker tag shows `#<displayId>` for an order and the mission name with its planned date
  for a mission, also for a chip built in place after an allocation write; an unresolved mission
  shows the localized unknown-mission label, never its id (`InventoryPageControllerMvcTest`, e2e
  `InventoryOperationsE2eTest`).
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
  amount to it (per dimension); with two or more targets a blank row is refused with `422`
  `INVENTORY_ALLOCATION_AMOUNT_MISSING` and the form does not submit, an explicit amount always
  wins, and a blank-amount row still trips the personal-entry rejection.

A connected application does the same for a Logistician of the entry's pool through the org-stock
ops `allocation-add`, `allocation-change` and `allocation-remove` (REQ-XCH-045), with these rules and
events; there the entry's version advances once per op, at the write, so the ops of one batch chain.

**Enforced by:** `InventoryItemServiceTest`, `InventoryItemServiceBookOutTest`,
`InventoryItemServiceAllocationTest`, `InventoryCheckoutServiceAuditTest`, `InventoryStockMergeTest`, `JobOrderHandoverServiceTest`,
`InventoryAllocationSoakDataTest`, `InventoryItemControllerTest`, `InventoryPageControllerMvcTest`,
`DatabaseIndexMigrationTest`, `InventoryInputAjaxControllerTest` (single-target shorthand),
e2e `InventoryOperationsE2eTest` (Herkunft picker gate + deduct-from; re-picking an
already-allocated order edits its slice instead of posting a duplicate) ·
**Code:** `InventoryJobOrderAllocation`, `InventoryMissionAllocation`,
`inventory/internal/InventoryAllocations`, `InventoryItemController` (allocation endpoints),
`InventoryItemService#createInventoryItem` / `#writeAllocationForPool`, `InventoryCheckoutService`
(book-out / merge / SELL),
`JobOrderStockReadService#getMaterialCollection`, `InventoryItemMapper`,
`V217__add_inventory_allocation_tables.sql`, `V218__drop_inventory_scalar_associations.sql`,
`inventory/fragments/inventory-stack-entries.html`, `inventory-common.js` (the allocation chips both Lager
pages share), `inventory-my.js` / `inventory-admin.js`,
`inventory-herkunft.js` (deduct-from picker), `inventory/inventory-input.html` / `inventory-input.js`,
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

The overview makes no distinction for the „gestohlen" marker (owner decision, REQ-INV-053). The
exchange books every material at the quality the client sends (REQ-XCH-016), so a rebooked exchange
row counts here at its real quality. *Amended 2026-10-02 (ADR-0230): the exchange stored every UEX
commodity — nearly the whole catalogue — at quality 0, and such a row, once rebooked into the
shared pool, lowered the average here; that was accepted in ADR-0218 and is gone. Rows booked that
way before the amendment keep their quality 0 until the member corrects them.*

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
`templates/inventory/inventory-index.html` · **Issues:** —

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
  fragment (the shared `kernel/fragments/pagination.html` component); a page or size click swaps the
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
`templates/inventory/inventory-material.html`, `templates/inventory/inventory-game-item.html`,
`static/js/inventory/inventory-material.js`, `static/js/inventory/inventory-game-item.js`, `kernel/fragments/pagination.html`,
`InventoryItemController#getInventoryByMaterial` / `#getInventoryByGameItem` (backend paging) ·
**Issues:** — · **ADR:** ADR-0104

### REQ-INV-034 — "Alle markieren" selects the whole filtered view for bulk check-out (no silent cap)

"Mein Lager" (`/inventory/my`, both the Material and the Items view) offers an **"Alle
markieren"** button at the end of the toolbar that marks **every** entry of the current filtered
view for the bulk actions, so the user need not expand each stack and tick each row by
hand. Because the grouped tree lazy-loads and paginates each stack (REQ-INV-005), a client-side
"check every visible box" would silently miss collapsed stacks and any entry past a stack's first
page; select-all therefore resolves the complete id set **on the server** ([ADR-0104](../adr/0104-no-silent-caps-on-complete-list-surfaces.md)
no-silent-cap principle).

- **Server-resolved id set.** The button fetches `GET /inventory/my/entry-ids`, which relays the
  page's active filter + `view` to the backend `GET /api/v1/inventory/my-inventory/entry-ids`. That
  endpoint returns the ids of every one of the caller's own entries matching the same optional-filter
  contract as the grouped view (`catalog=MATERIAL` uses material / location / min-quality /
  job-order / mission + the mutually exclusive personal toggles; `catalog=ITEM` uses gameItem /
  location / job-order + the personal toggles, and rejects the material-only filters with 400
  exactly like the grouped endpoint, REQ-INV-029/031). It is owner-scoped from the JWT (no
  impersonation) and can never return an id outside the grouped view.
- **Selection is a decoupled set, not the DOM.** The frontend holds the selection in a Set that is
  independent of the lazily-loaded checkboxes: a loaded checkbox's checked state is derived from the
  set, a stack expanded after select-all comes up already ticked, and the selection bar's count and
  its POSTs read the set directly — so the bulk check-out spans the whole filtered view, not only
  the expanded stacks.
- **The selection bar appears only with a selection.** The bulk actions live in a sticky bar at the
  bottom of the page (`#bulkCheckoutBar`, the `.form-actions--sticky` pattern of REQ-UI-027) that is
  hidden while nothing is marked and shows „n ausgewählt", „Auswahl aufheben", the „⋯" menu with
  „Einheit ändern" (REQ-INV-052) and — for a caller who may — „Als gestohlen markieren" /
  „Markierung entfernen" (REQ-INV-053), then „Umbuchen" (REQ-INV-036) and „Ausbuchen". The page
  head's one primary action is „Einbuchen"; the tree stays as it was.
- **Toggle + safe reset.** The button toggles between "Alle markieren" and "Auswahl aufheben"
  (clear). The selection is reset whenever the grouped table re-swaps (filter change, post-write
  refresh, or a live-sync peer refresh, REQ-FE-010/015): the freshly rendered checkboxes come back
  unticked, and keeping stale ids across a re-render could aim the bulk check-out at an entry a peer
  already removed (the backend bulk-checkout 404s on any unknown id). Select-all itself does not
  re-swap the table, so a live selection survives drill-down expansion.
- **No new mutation.** Select-all is a read that feeds the existing bulk check-out
  (`POST /inventory/bulk-checkout`, REQ-INV-044); it adds no new write path and no new audit event
  (the bulk check-out's `INVENTORY_BULK_CHECKED_OUT` audit is unchanged).

*Amended 2026-10-03 (website overhaul phase 3): „Alle markieren" moved into the toolbar, and the
bulk buttons („Markierte ausbuchen", „Markierte umbuchen", „Markierte: Einheit ändern", the stolen
pair) moved into a sticky selection bar that appears only while something is marked, labelled
„Ausbuchen", „Umbuchen" and, in its „⋯" menu, „Einheit ändern" and the stolen pair.*

**Acceptance**

- [ ] `/inventory/my` (Material and Items view) renders an "Alle markieren" button in the toolbar;
  clicking it marks every entry of the current filtered view, including entries in collapsed stacks
  and beyond a stack's first page, and the selection bar's count reflects the full total.
- [ ] The selection bar is hidden while nothing is marked and appears with the first marked entry;
  „Auswahl aufheben" hides it again.
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
`InventoryPageController#myEntryIds`, `templates/inventory/inventory-my.html`, `static/js/inventory/inventory-my.js` ·
**Issues:** — · **ADR:** ADR-0104

### REQ-INV-035 — Refinery output can be stored straight into the personal pool

The refinery-order **store dialog** (`/refinery-orders/{id}`, "Einlagern") offers a per-output-row
**personal marker** — the same `inventory_item.personal` flag the Einbuchen dialog
(`InventoryItemService#createInventoryItem`) and the item-production book-in
([`inventory-items.md`](inventory-items.md) REQ-INV-032) already carry. Before this,
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
`templates/refinery/refinery-orders-details.html`, `static/js/refinery/refinery-orders-details.js` · **Issues:** — ·
**ADR:** —

### REQ-INV-036 — "Markierte umbuchen": the bulk bar moves the whole selection, skipping already-at-target rows

The "Mein Lager" selection bar (`/inventory/my`, both the Material and the Items view, REQ-INV-034)
offers **„Umbuchen"** next to „Ausbuchen", acting on the **same** marked selection
(REQ-INV-034) but *moving* the rows instead of discarding them. It is the bulk counterpart of the
single-row Umbuchen modal (REQ-INV-007 / REQ-INV-025) and covers both of its destinations: another
Ort / Nutzer / OrgUnit-Pool, or the personal marker. The same bar's „⋯" menu offers „Einheit
ändern" (REQ-INV-052) and „Als gestohlen markieren" / „Markierung entfernen" (REQ-INV-053), and
every bulk move carries a row's „gestohlen" marker. *Amended 2026-10-03 (website overhaul phase
3): the button „Markierte umbuchen" is „Umbuchen" in the selection bar, which shows only while
something is marked.*

- **Every marked row moves in full.** There is no per-row amount. The selection spans collapsed
  stacks and later pages, so a per-row quantity could not be reviewed before submitting; moving the
  whole row keeps the action predictable, lets each moved row inherit **all** of its job-order /
  mission earmarks unchanged ("Marken mitnehmen", REQ-INV-027 — a full move with no explicit
  deduct-from plan resolves to "every slice in full"), and leaves no source remainder. Each source
  row is therefore always deleted; its allocations cascade away (V217) after being copied onto the
  target, and its free-text note is carried over so a whole-row relocation cannot silently lose it.
- **Three explicit modes**, not an inferred direction. `LOCATION` moves to the picked target user /
  location / owning org unit; `PERSONALIZE` and `DEPERSONALIZE` are **separate** modes rather than
  one direction derived from the source row, because a bulk selection can mix personal and shared
  stock and a per-row flip would make the outcome depend on what happened to be marked. A `LOCATION`
  request that carries neither a target user nor a target location is rejected (REQ-INV-025 parity),
  so it can never report an all-skipped success.
- **Skip already-at-target, abort on everything else** (ADR-0124). A row already sitting in the
  requested target state — same user *and* location for `LOCATION`, already personal / already
  shared for the two personal modes — is **skipped and counted**, not failed: "Alle markieren"
  routinely marks rows that are already at the destination, and failing on them would make the
  action unusable. Every *other* obstacle aborts the whole transaction so nothing is written: an
  unknown id (404), a row owned by another user (403), an unknown target (404), and a job-order /
  mission earmark blocking a `PERSONALIZE` (400 — a personal row may never carry either association,
  and silently dropping the link would lose the assignment). The whole selection is validated before
  the first write, so the earmark rejection names how many rows block it rather than only the first.
- **The counts are reported, not rounded up.** The endpoint answers `{rebooked, skipped}` and the
  page phrases its toast from both: all-moved reads as a success, a mixed run names both numbers,
  and an all-skipped run is reported as "nothing moved" rather than as a success.
- **Owner-scoped, like the bulk checkout.** `POST /api/v1/inventory/bulk-rebook` derives the owner
  from the JWT and is deliberately **not** role-overridable — an admin must use the single-row
  endpoints to move another member's stock. The selection carries no client `@Version` (the bulk bar
  holds only ids, and the server-resolved select-all set never carries versions), so each row is
  loaded under a pessimistic write lock instead; the ids are deduplicated and locked in sorted order
  so two concurrent bulk rebookings over overlapping selections cannot deadlock.
- **Merge opt-in applies per moved row** (REQ-INV-026). The modal always offers the checkbox — a bulk
  selection can mix SCU and PIECE stock, and the flag is ignored for the rows that always merge.
- **Audit:** one summarizing `INVENTORY_BULK_REBOOKED` event per action carrying the mode and the
  moved/skipped counts, mirroring `INVENTORY_BULK_CHECKED_OUT`; the individual moves are not audited
  separately. A run that moved nothing records no event, because it mutated no state.

**Acceptance**

- [ ] `/inventory/my` (Material and Items view) renders „Umbuchen" beside „Ausbuchen" in the
  selection bar, which is shown exactly when the selection is non-empty, and the modal offers all
  three modes.
- [ ] Rebooking a selection to a target location moves every marked row in full and skips those
  already at that location; the response counts both and the toast names both numbers.
- [ ] An unknown id, a foreign row, or an earmarked row in a `PERSONALIZE` selection leaves the
  whole selection unchanged (no partial write) and surfaces the matching status.
- [ ] A moved row arrives carrying the source's job-order / mission slices at their full amounts,
  and the source row is gone.
- [ ] `POST /api/v1/inventory/bulk-rebook` never consults `isLogisticianOrAbove()`.
- [ ] A rebooking that moved at least one row records exactly one `INVENTORY_BULK_REBOOKED` audit
  event with the mode and both counts; an all-skipped run records none.

**Enforced by:** `InventoryCheckoutServiceBulkRebookTest`, `InventoryItemControllerTest`
(`bulkRebook_forwardsOwnerOnly_neverConsultsIsLogistician`), `InventoryBulkRebookAjaxControllerTest`,
`InventoryPageControllerMvcTest`
(`viewMyInventory_rendersBulkRebookButtonAndModalWithAllThreeModes`) · **Code:**
`InventoryCheckoutService#bulkRebook`, `InventoryItemService#bulkRebook`,
`InventoryItemController#bulkRebook`, `InventoryItemRepository#findByIdForRebook`,
`BulkRebookRequest` / `BulkRebookResultDto` / `BulkRebookMode`,
`InventoryWriteController#bulkRebook`, `templates/inventory/inventory-my.html`,
`static/js/inventory/inventory-my.js` · **Issues:** — · **ADR:** ADR-0124

### REQ-INV-037 — The Lager filters sit in a popover, and the toolbar says when they narrow the view

The filter widgets on `/inventory/my` and on `/inventory/all` — the multi-selects (material or game
item, location, job order, mission), min. quality and the „Gestohlen" select — are a block of
controls that would push the tree down and leave the band above it hard to scan. They therefore live
in the **filter popover** of the page toolbar (`fragments/components :: filterPopover`, REQ-UI-027),
opened by the **„Filter"** button. Both views (Material and Items) get it; only the active view's
form exists in the DOM (REQ-INV-030), so one popover serves both.

**Both Lager pages carry the same popover**, with page-local ids: "Mein Lager" (`myFilterPanel`,
`data-filter-panel="inventory-my"`) and the shared "Globales Lager" (`globalFilterPanel`,
`data-filter-panel="inventory-all"`). On "Mein Lager" the visibility segment „Alle · Persönlich ·
Gemeinsam" (REQ-INV-046) sits **in the toolbar beside it**, not in the popover; the shared Lager has
no visibility dimension. Everything else below holds verbatim on both.

> **Correction (2026-09-22).** This requirement first shipped with page-local collapse code, a
> first-visit rule "collapse only when nothing is filtered" and the preference stored inside the
> REQ-UI-017 filter object. All three were replaced when the mechanism was generalised to every list
> page by [`frontend-ajax-mutations.md`](frontend-ajax-mutations.md) REQ-FE-021.
>
> **Amended 2026-10-03 (website overhaul phase 3).** The collapsible panel below the action bar
> became the toolbar's transient filter popover: it always starts closed, stores no open/closed
> preference (the `krt.filterPanel.inventory-*` keys are no longer written), and every active filter
> is also shown as a removable chip below the toolbar. The two personal checkboxes became the
> toolbar segment, which the count leaves out.

- **A closed popover must never hide the fact that the tree is filtered.** The „Filter" button
  carries a badge with the number of filter dimensions currently narrowing the view, shown whenever
  that number is greater than zero, and each active dimension appears as a **chip** below the
  toolbar („Alle zurücksetzen" clears them all; removing a chip clears that dimension and re-runs the
  live filter). The Lager pages cannot be counted by REQ-FE-021's generic scan of the panel's own
  controls (the multi-selects hold their state in a widget, and the location dimension is derived),
  so each registers its own counter through `window.krtFilterPanel.registerCounter` — from its
  `DOMContentLoaded` handler, after the REQ-UI-017 restore — and re-states it with `refresh()` after
  every in-place filter swap. The counter is derived from the same snapshot REQ-UI-017 persists,
  minus the visibility segment, which is visible in the toolbar itself. A multi-select with **all**
  boxes ticked counts as no filter, matching what that state already means everywhere else on this
  page.
- **A multi-select says what it filters, in the same words on both pages** (owner decision
  2026-09-23). Its header reads „Alle" (`filter.all`) when no box or every box is ticked — both mean
  no filter —, the option's own name when exactly one is ticked, and „N ausgewählt"
  (`filter.selected`) otherwise. The helper lives once in `inventory-common.js`.
- **Transient.** The popover is a `data-filter-transient` panel: it starts closed on every load,
  opens from the button, focuses its first field, closes on Escape or an outside click, and flips to
  the left edge when it would leave the viewport (REQ-UI-027); on a phone it is a bottom sheet. The
  filter selection itself still persists per browser under `inventory_my_filters` /
  `inventory_admin_filters` (REQ-UI-017).
- **Works without a script.** The server emits the panel inside the filter form, so a client without
  JavaScript still submits working filters (REQ-FE-021).
- **Accessible by construction.** The toggle is a real `<button>` carrying `aria-expanded` and
  `aria-controls`, and the badge pairs its digit with a visually-hidden "Aktive Filter: N". The count
  is *not* pushed into a dynamic `aria-label` on the button — that would shadow the visible "Filter"
  text and break voice control's "click Filter".

**Acceptance criteria**

- [ ] All four of `/inventory/my`, `/inventory/my?view=items`, `/inventory/all` and
  `/inventory/all?view=items` render the „Filter" button in the toolbar, and the filter controls sit
  inside its popover; "Mein Lager" renders the visibility segment in the toolbar, outside the popover.
- [ ] The popover starts closed on every load, opens from the button, and closes on Escape; a
  reload starts closed again.
- [ ] Selecting a filter, resetting the filters, and a restored REQ-UI-017 selection all leave the
  badge's number equal to the number of active dimensions and render one chip per active dimension;
  at zero the badge and the chip bar are hidden. Choosing „Persönlich" or „Gemeinsam" narrows the
  tree without changing the badge.
- [ ] A multi-select with every box ticked leaves the badge hidden.
- [ ] On both pages a multi-select header reads „Alle" with none or every box ticked, the option's
  name with one, and „N ausgewählt" otherwise.
- [ ] Opening or closing the popover performs no navigation and no fetch — the tree below is
  untouched.

**Enforced by:** `InventoryPageControllerMvcTest`
(`viewMyInventory_rendersTheFiltersInsideTheToolbarPopover`,
`viewAllInventory_rendersTheFiltersInsideTheToolbarPopover`), `InventoryLagerPatternRenderTest`,
`InventoryMultiSelectLabelE2eTest` (the header summary on both pages),
`InventoryFilterPanelCollapseE2eTest` · **Code:** `templates/inventory/inventory-my.html`,
`templates/inventory/inventory-admin.html` (`fragments/components :: filterPopover`, `:: filterChips`,
`:: segmented`), `static/js/kernel/krt-filter-panel.js` (open/close, badge), `static/js/kernel/krt-filter-chips.js`,
`static/js/inventory/inventory-my.js` (`countActiveMyInventoryFilters`), `static/js/inventory/inventory-admin.js`
(`countActiveAdminInventoryFilters`) · **Issues:** — · **ADR:** — (extends REQ-UI-017 / ADR-0120;
mechanism generalised by REQ-FE-021 and REQ-UI-027)

### REQ-INV-039 — The allocation pickers say what each order still needs

> [!note] „Gestohlen“ — since WP 1.3 (REQ-INV-053)
> Stolen stock may be allocated to orders and missions (owner decision 2026-09-26); the pickers,
> an order's allocated stock and handover lines, and a mission's allocated stock label it with the
> „Gestohlen“ chip.

Splitting a check-in across several orders (REQ-INV-027 R4) means deciding how much each one should
get, and the picker used to identify a candidate only as `#<displayId> - <handle> (<status>)`. The
member had to open every candidate order in another tab to find out what it still needed. Both
allocation pickers therefore **state the outstanding need beside the order**.

**The figure.** For the picked material and one order:

> `noch benoetigt = max(0, outstanding requirement - order-linked stock)`

summed over that material's quality buckets. It is the per-order narrowing of
`MaterialDemandRowDto.outstandingAmount` (REQ-ORDERS-034) and is folded from the **same**
normalisation and the **same** batched stock index, so a picker label, the cross-order demand
overview and the order detail can never print three different numbers. Three properties are load
bearing, and each has a wrong-looking alternative:

- **The requirement is already the remainder.** A MATERIAL line's `amount` is decremented in place
  by a handover (`JobOrderHandoverService`); an ITEM order's blueprint-derived requirement is
  already scaled by its not-yet-manufactured share. Subtracting handovers again double-counts them.
- **Claims never shrink it.** `openAmount` is `required - claims` — a promise that has moved no
  stock (REQ-ORDERS-024). Using it would report a merely-claimed bucket as covered.
- **Both order kinds carry a figure.** An ITEM (crafting) order is offered for every material its
  blueprint consumes (REQ-ORDERS-018) and has no `job_order_material` rows at all, so a projection
  reading only `materials` would leave a blank beside exactly the orders whose need is hardest to
  look up by hand.

**Opt-in on the wire.** `GET /api/v1/orders/lookup` ships `materialNeeds` only for
`withNeeds=true`. The same lookup feeds the Lager pages' filter dropdowns, which render no figure,
and folding an ITEM order's requirements is a read they must not pay for. Visibility is unchanged:
the projection is built from the orders the lookup already returns.

**The quality marker.** An allocation is gated on the required **material** alone — never on
quality — while the order's linked stock is distributed over its quality buckets by the
quality-bucket allocation (REQ-ORDERS-037): each row counts toward at most one bucket whose floor it
meets. Grade-400 ore may therefore be earmarked to a 650-floor order and will simply not reduce
that order's need. The check-in form, which is the one surface where a grade is being chosen, marks
such an option (`benoetigt 650+`) whenever the entered grade falls below the floor. The figure is
still shown: the option is legitimate, and the marker is what keeps the number from misleading.

**Two surfaces, two rendering paths, and the reason they differ.**

|             Surface              |                Path                 |                 Rendered                 |
|----------------------------------|-------------------------------------|------------------------------------------|
| Einbuchen form's allocation rows | `/inventory/input`                  | client-side, from one embedded JSON blob |
| Per-entry `+ Zuordnen` popover   | `/inventory/{my,all}` stack entries | server-side, in the option text          |

The form's rows are **cloned from a `<template>`**, so N rows hold N copies of the option list; the
figures live in one module-level map keyed by order id and every label is computed from it, rather
than being duplicated onto every clone and rewritten on each of them. The popover's picker is a
**combobox**, which snapshots an option's text when it enhances the `<select>` — a label written
after that point would never appear — so its label is server-rendered, which it can be because the
entry's material is already fixed.

**Live-sync (REQ-FE-010).** The figure moves when a peer books stock against one of these orders,
records a handover, edits a line, or opens/closes an order. Both surfaces therefore join the global
`orders` room and re-read, reusing its existing `demand` section as **subset consumers** (the
`material-collection.js` precedent) rather than introducing a key: `demand` is published by exactly
those writers, and the room's `canViewJobOrders` gate is the same gate that decides whether the
picker has any orders at all. The Einbuchen form's refresh **re-reads JSON and re-labels in place**
through `GET /inventory/order-needs` — it never swaps a fragment, because a swap cannot reach the
cloned rows and would take a picked order and a typed amount with it. The Lager pages re-pull their
grouped table, whose collapsed stacks re-fetch their entries (and so their labels) on the next
expand. The receiver's default busy guard applies: while the member has focus inside the allocation
group, the update is held behind the "updates available" pill instead of moving a number under a
half-typed split.

**Item mode carries the figure too, from a second calculation** (#1742). Booking *finished items*
against an ITEM order is not the material question in another unit, so it is not the material
formula either:

> `noch benoetigt = max(0, ordered - delivered - earmarked)`

in whole units, summed over the order's lines naming that game item. Three notes, each of which is
where an implementation goes wrong:

- **It is stable across a handover.** `JobOrderItemHandoverService` consumes the order's earmarked
  item stock as it delivers, so the units move from `earmarked` into `delivered` and the figure does
  not twitch. That stability is the point of subtracting both rather than picking one.
- **`manufacturedAmount` plays no part.** Production books its output into the Lager *earmarked to
  the order*, so those units are already in `earmarked`; subtracting both would count them twice and
  hide a real need. A producer who books the output in **without** the earmark
  (`allocateToOrder = false`) has created stock committed to nobody — which the material side does
  not count either, so the two dimensions stay consistent.
- **The floor at 0 is not cosmetic, and the mechanism is over-earmarking.** `delivered` can never
  exceed `ordered` (the line's invariant, and the handover refuses over-delivery), so that pair
  alone cannot go negative. What can is the earmark: `assertGameItemRequiredByJobOrder` checks only
  that the order *requests* the game item, and nothing caps a slice against what the order still has
  to receive — so a member may earmark more units to an order than it wants, and the raw difference
  turns negative. (An earlier revision of this requirement blamed the handover's best-effort
  shortfall; that is wrong in the opposite direction — a shortfall leaves `allocated` *smaller*,
  which makes the difference larger, never negative. Corrected 2026-09-01.)

**No quality marker in item mode.** Item rows carry no quality dimension at all (REQ-INV-029), so
there is no floor to compare an entry against — the asymmetry with the material picker is the data
model, not an omission.

**On the wire** the item figures are a parallel projection (`gameItemNeeds`), not a unit variant of
`materialNeeds`, under the same `withNeeds` opt-in and always empty for a MATERIAL order. The page
payload is one envelope — `{"materials": {...}, "gameItems": {...}}` — so the check-in form, which
switches catalog mode in place, relabels either without a second fetch, and the live-sync refresh
re-reads exactly the shape the page was rendered with.

**Acceptance criteria**

- [ ] With a material picked, every order option in the Einbuchen form's allocation rows states what
  that order still needs of it, in the material's own unit and rounding (whole for `PIECE`, three
  decimals for SCU, REQ-INV-027).
- [ ] A fully covered order reads "gedeckt" rather than "noch 0".
- [ ] An ITEM order whose blueprint consumes the picked material carries a figure, not a blank.
- [ ] Changing the picked material re-computes every label, including the rows already added, and
  never stacks suffixes.
- [ ] An option whose order requires a higher grade than the one being entered additionally carries
  the quality marker; changing the grade re-decides it.
- [ ] The `+ Zuordnen` popover on `/inventory/my` and `/inventory/all` labels its options with the
  same figure for the entry's own material.
- [ ] The figures reconcile with `/orders/material-demand` and with the order detail for the same
  bucket.
- [ ] `withNeeds=false` (the default) ships no figures and fires no stock query.
- [ ] A peer's booking against a listed order moves the figure on both surfaces with no reload; a
  failed re-read leaves the standing labels rather than blanking them.
- [ ] In **item** mode both surfaces state the units an ITEM order still wants, in whole pieces, and
  never render a quality marker.
- [ ] Delivering earmarked item stock leaves that figure unchanged; booking production in moves it.
- [ ] Two lines of one order naming the same game item are summed, matching the order detail's
  Item-Bestand panel.

**Enforced by:** `JobOrderReferenceNeedsTest`, `InventoryPageControllerMvcTest`
(`viewInputPage_ShouldEmbedTheOutstandingNeedFigures`,
`orderNeedsAjax_ShouldAnswerTheNeedsKeyedByOrderId`,
`viewAllStackEntries_ShouldLabelOrderOptionsWithTheOutstandingNeed`),
`LiveSyncSectionMapParityTest`
(`inventoryInputOrderSeamMap_isASubsetOfTheOrdersQueueWhitelist`,
`inventoryPageOrderSeamMaps_areASubsetOfTheOrdersQueueWhitelist`) · **Code:**
`JobOrderMaterialRequirementResolver`, `JobOrderQueryService#findAllActiveReference`,
`JobOrderMaterialNeedDto`, `InventoryPageController#orderNeedsAjax`,
`templates/inventory/inventory-input.html`, `static/js/inventory/inventory-input.js` (`relabelOrderOptions` /
`refreshOrderNeeds`), `templates/inventory/fragments/inventory-stack-entries.html`,
`static/js/inventory-{my,admin}.js`, `JobOrderGameItemNeedDto`,
`InventoryItemRepository#findGameItemStockRowsByJobOrderIds` · **Issues:** #1740, #1742 ·
**ADR:** — (extends REQ-INV-027 / REQ-ORDERS-034)

### REQ-INV-040 — Both Lager pages filter by storage location

A location is part of the stock identity on both Lager pages — it is a grouping dimension of the
material stack key (REQ-INV-002) and of the quality-less item stack key
([`inventory-items.md`](inventory-items.md) REQ-INV-029) — so everything a member holds at one place
is spread across every material group of the tree. Before this, neither page could narrow by it: the
filter panel (REQ-INV-037) offered Material / quality floor / Auftrag / Einsatz / the personal flags
on "Mein Lager" and the same minus the personal flags on the shared "Globales Lager". The everyday
question *"what is still sitting at this station, and is it worth flying there"* therefore cost an
expand-and-read pass over the whole tree — several fetches, because stacks are lazy and paginated
(REQ-INV-005 / REQ-INV-033).

Both Lager pages therefore carry a **location multi-select in the filter panel, in both the Material
and the Items view** — `/inventory/my` and `/inventory/all`, four views in total. It behaves like
every other multi-select on these pages: all boxes ticked means no filter, the selection is mirrored
into the URL, persisted per browser (REQ-UI-017) and counted by the „Filter" button's active-filter
badge and shown as a chip (REQ-INV-037), which needs no extra wiring because that count is derived
from the persisted snapshot.

- **The options are in-scope stock, never the catalog.** The location catalog is the whole Star
  Citizen universe, which is why every location *picker* on these pages (the Umbuchen target, the
  book-out transfer target) searches server-side instead of rendering a list. A filter multi-select
  cannot search, so its options are built from the grouped result's own stack keys — the same rule
  the gameItem filter follows (REQ-INV-030): only locations that currently hold stock in that view's
  scope (the caller's own rows on "Mein Lager", the org-unit-scoped rows on the shared page).
- **An active filter must not narrow its own options.** Deriving the options from the filtered result
  would leave the dropdown holding only the locations already picked, and the selection could never
  be widened again — the filter would be a one-way door. So when any filter dimension is active, a
  full (non-fragment) render re-reads the view unfiltered for the option list alone. A failed re-read
  degrades to the displayed groups' locations rather than to an empty dropdown.
- **The filter is catalog-agnostic.** `locationIds` is accepted on **both** catalogs and must never
  join the material-only rejection set of REQ-INV-029/031 (`minQuality` / `missionIds` /
  `materialIds`): an item stack carries a location exactly like a material stack does. It is relayed
  on the four filter-carrying backend endpoints — `GET /my-inventory/grouped`,
  `GET /my-inventory/entry-ids`, `GET /all/grouped` and `GET /all`. The two stack-entry endpoints
  need nothing: a stack row is already location-pinned through their mandatory `locationId`, so the
  lazily loaded leaf rows follow the filter for free.
- **"Alle markieren" resolves the same set the table shows.** The select-all id query (REQ-INV-034)
  carries the location filter too. Without that parity a bulk check-out would reach past the location
  the user is looking at — the precise failure the server-resolved select-all exists to prevent.

**Acceptance criteria**

- [ ] All four of `/inventory/my`, `/inventory/my?view=items`, `/inventory/all` and
  `/inventory/all?view=items` render the location multi-select inside the filter panel.
- [ ] Its options are only the locations that currently hold stock in that view's scope — never the
  location catalog.
- [ ] With a location filter active, the option list still offers the locations the filter excludes,
  so the selection can be widened again.
- [ ] Selecting locations narrows the grouped table in place via `krtFetch` (no full page reload),
  and an expanded stack's lazily loaded entries show only rows at the selected locations.
- [ ] "Alle markieren" on "Mein Lager" resolves exactly the id set the location-filtered table shows,
  on both catalogs.
- [ ] The parameter is accepted under `catalog=ITEM` (never 400-rejected as a material-only filter)
  and narrows the item tree the same way.
- [ ] The selection survives a reload per page and per view (REQ-UI-017) and is counted by the
  active-filter badge (REQ-INV-037); every box ticked counts as no filter.
- [ ] The two pages' stored selections stay independent (`inventory_my_filters` /
  `inventory_admin_filters`).

**Enforced by:** `InventoryItemStackQueryDataTest`
(`findUserStacks_locationIds_narrowToThePickedLocation`,
`findUserItemStacks_locationIds_narrowToThePickedLocation`,
`findUserEntryIds_locationIds_matchTheFilteredStacks`), `InventoryItemControllerTest`
(`getMyGroupedInventory_catalogMaterial_forwardsLocationIds`,
`getMyGroupedInventory_catalogItem_forwardsLocationIdsInsteadOfRejectingThem`,
`getMyEntryIds_forwardsLocationIdsOnBothCatalogs`,
`getAllGroupedInventory_forwardsLocationIdsOnBothCatalogs`), `InventoryPageControllerMvcTest`
(`lagerViews_renderTheLocationFilterWithInScopeOptions`), `InventoryPageControllerTest`
(`viewMyInventory_locationFilter_forwardedToBackendAndOptionsComeFromTheStacks`,
`viewMyInventory_locationOptions_areNotNarrowedByTheActiveLocationFilter`,
`viewAllInventory_locationFilter_forwardedToBackendAndModel`,
`myEntryIds_locationFilter_forwardedOnBothViews`) · **Code:**
`InventoryItemRepository` (`hasLocations` / `locationIds` gate on the stack, entry-id and flat
queries), `InventoryAggregationService`, `InventoryItemService`, `InventoryItemController`,
`InventoryPageController` (`resolveLocationFilterOptions`, `fetchGroupedMaterialInventory`),
`templates/inventory/inventory-my.html`, `templates/inventory/inventory-admin.html`, `static/js/inventory/inventory-my.js`,
`static/js/inventory/inventory-admin.js` · **Issues:** #1879 · **ADR:** — (extends REQ-INV-030 / REQ-INV-034 /
REQ-INV-037)

### REQ-INV-041 — "Globales Lager leeren": the admin-only wipe of the shared stock in scope

The shared "Globales Lager" page (`/inventory/all`) offers an admin a **"Globales Lager leeren"**
entry in the page head's „⋯" menu (since 2026-10-03, website overhaul phase 3; it was a button in
the action bar above the table) behind a danger confirmation modal. It deletes every **non-personal** Lager row inside the
caller's current org-unit scope in one statement; personal rows (`personal = true`) are never
touched. The scope is the standard predicate triple
([`org-unit-tenancy.md`](org-unit-tenancy.md) REQ-ORG-003): an admin in all-scope wipes the shared
stock of every org unit, an admin pinned to one org unit wipes only that unit's. The rows' job-order
/ mission allocations and any Materialbörse offer they back go with them through the
`ON DELETE CASCADE` foreign keys (V217, V210).

- **Admin-only on both layers.** The backend `DELETE /api/v1/inventory/all` is gated by
  `hasRole('ADMIN')` (neither `OFFICER` nor `LOGISTICIAN` qualifies), and the frontend proxy
  `DELETE /inventory/all` (`InventoryDeleteAllProxyController`) repeats the gate so a non-admin call
  never leaves the frontend; the menu entry and its modal render only for an admin.
- **Audited once.** The wipe records one `INVENTORY_WIPED` event carrying the scope and the number of
  removed rows (REQ-AUDIT-001); the unified viewer's Lager filter lists it.
- **In place.** On success the page clears the grouped table through its existing filter swap, not
  a reload ([`frontend-ajax-mutations.md`](frontend-ajax-mutations.md) REQ-FE-005).

**Acceptance**

- [ ] An admin's wipe removes every non-personal row in the current scope and leaves every personal
  row and every out-of-scope row in place; the backend answers 204.
- [ ] A non-admin (including `OFFICER` and `LOGISTICIAN`) gets 403 from both the frontend proxy and
  the backend, and sees no button.
- [ ] The wipe records exactly one `INVENTORY_WIPED` audit event with the scope and the removed-row
  count.

**Enforced by:** `InventoryItemServiceTest` (`deleteAllGlobalInventory_*`),
`InventoryCheckoutServiceAuditTest#deleteAllGlobalInventory_recordsWipedAuditWithScopeAndCount`,
`InventoryItemControllerTest#deleteAllGlobalInventory_returns204_andDelegatesToService`,
`InventoryDeleteAllProxyControllerTest` (backend 403 propagated) — the two `hasRole('ADMIN')` gates
themselves are not yet pinned by a test · **Code:**
`InventoryItemController#deleteAllGlobalInventory`, `InventoryCheckoutService#deleteAllGlobalInventory`,
`InventoryItemRepository#deleteAllNonPersonal`, `InventoryDeleteAllProxyController`,
`templates/inventory/inventory-admin.html`, `static/js/inventory/inventory-admin.js` · **Issues:** — · **ADR:** —

### REQ-INV-052 — A member can change the org unit of their own personal row afterwards

A member can set the owning org unit of one of their **own personal** rows to one of their direct
memberships of any of the four kinds, or to **„Keine Einheit"**, after the booking — per row
(`POST /api/v1/inventory/{id}/org-unit`, optimistic `version`) and for a selection from the bulk bar
(`POST /api/v1/inventory/bulk-org-unit`, rows locked pessimistically in sorted id order, no
versions, like REQ-INV-036). Stock booked in through the exchange carries no unit (REQ-XCH-016), and
this is how it gets one; before, a personal row's unit changed only as a side effect of a rebooking,
and a `TRANSFER` may not change it alone (REQ-INV-025).

- **Personal rows of the caller only.** A shared row's unit is its pool; it moves into another
  pool through the pool change of REQ-INV-059 or by rebooking. A selection that contains a shared or
  a foreign row is refused as a whole.
- **The unit decides who else may see and edit the row** (REQ-ORG-004): without a unit only the owner
  (and an unpinned admin) reaches it; with a unit that unit's editors do too. The dialog says so
  before the member confirms.
- Unlike the rebooking pickers (#1328), this picker **has** a „Keine Einheit" option, because no
  unit is exactly what it may set; it is preset to the row's current unit.
- The row then follows the write-time merge rules (REQ-INV-026): a `PIECE` or game-item row merges
  into an existing stack of the new unit, an `SCU` row only on the per-action opt-in; rows backing a
  Materialbörse offer never merge.
- Leaving the unit unchanged writes nothing. Otherwise the row records
  `INVENTORY_ORG_UNIT_CHANGED` (from / to as `kind:id` or `none`), a selection one
  `INVENTORY_BULK_ORG_UNIT_CHANGED` summary (REQ-AUDIT-001).
- The page re-renders in place and tells peers through live sync (`inventory` / `stock`,
  REQ-FE-001).

**Acceptance**

- [x] Own personal rows move to a membership and back to no unit, singly and as a selection; shared
  or foreign rows and a unit that is not a membership are refused.
- [x] `PIECE` rows merge into the new unit's stack, `SCU` rows only with the opt-in.
- [x] An unchanged unit records nothing; a change records one event, a selection one summary.
- [x] The web action and bulk-bar button update the page in place.

**Enforced by:** `InventoryOrgUnitChangeServiceTest`, `InventoryItemControllerTest`,
`InventoryOrgUnitChangeAjaxControllerTest`, `InventoryPageControllerMvcTest`,
`InventoryOperationsE2eTest.changingAPersonalRowsOrgUnitWorksBothWaysInPlace`,
`ExternalContractTest`, `ApiVhostAnonymousSurfaceTest` · **Code:** `InventoryOrgUnitChangeService`,
`InventoryItemController`, `InventoryOrgUnitChangeProxyController`, `inventory-my.js` · **Issues:** #2107
(epic #2078).

### REQ-INV-053 — Stock can be marked „gestohlen", and the marker is part of its identity

Stolen cargo cannot be sold at ordinary terminals, so it is a property of a stack, not a note. Every
Lager row — shared or personal, material or game item — carries a **„gestohlen" marker**
(`inventory_item.stolen`, `V247`).

- **Part of the stock identity everywhere.** Every stack the Lager computes — both grouped views,
  the lazy stack entries, the select-all entry ids and the write-time merge group (REQ-INV-026) —
  keys on the marker, so stolen and legitimate stock of one material, location and quality are
  **two stacks** that never merge and never mix entries. `InventoryStackKeyCoverageTest` fails when
  a stack-key query leaves it out.
- **Carried by every move**: a book-out `TRANSFER` (REQ-INV-025), the personal ↔ shared rebooking
  (REQ-INV-007), the bulk rebooking (REQ-INV-036), the org-unit change (REQ-INV-052) and every split.
  The refinery store (REQ-INV-035) and item production (REQ-INV-032) write `false`; a job-order
  handover snapshots it on the handed-over line.
- **Book in as stolen** (a checkbox in the book-in dialog) and **mark or unmark afterwards** — a
  whole row, or a **part** that is split off as a new row with the other marker and the rest of the
  identity unchanged (`POST /api/v1/inventory/{id}/stolen`, optimistic `version`), for **whoever may
  edit the row** (no new permission): the row's scope (`canEditInventoryItem`) and then, like every
  other per-row write, the owner or a Logistician or above — a member marking another member's row
  gets `403`. *Amended 2026-10-04:* the second gate was missing until then, so any member whose
  scope covered the unit could mark or split another member's row. A selection of one's own rows is marked or unmarked whole
  (`POST /api/v1/inventory/bulk-stolen`, locked in sorted id order like REQ-INV-036). A split may
  not leave the row below the amount it offers on the Materialbörse (refused with a message naming
  the offer) or below its earmarks (`422`).
- **Behind a server switch.** `app.inventory.stolen-marking-enabled`
  (`APP_INVENTORY_STOLEN_MARKING_ENABLED`, default **on**) gates booking in as stolen and every
  mark/unmark; it is advertised as `canMarkStolen` in `/api/v1/me/capabilities`. The switch waited
  for an Android release (#2092, #2097) that never shipped; with the app discontinued (ADR-0268) it
  is a plain feature flag, on by default and switched off only by setting it to `false`. Reading,
  the chip and the filters work either way.
- **Shown everywhere stock appears**: a danger chip „Gestohlen" on stack and entry rows of both
  Lager views, in the allocation pickers (REQ-INV-039), on an order's allocated stock and handover
  lines, on a mission's allocated stock and on the Materialbörse's list, search, detail and release
  picker (owner decision 2026-09-26: allocation and trading are **allowed, visibly labelled**).
  Filters: „Alle · Ohne gestohlene · Nur gestohlene" on both Lager views (`stolenOnly` /
  `nonStolenOnly`), „ohne gestohlene" on the board (`excludeStolen`).
- **The totals do not change**: the per-material overview (REQ-INV-028, no distinction by owner
  decision), craftability (REQ-INV-048), the Materialbörse amounts and the order-coverage sums count
  stolen stock like any other.
- **Audited** (REQ-AUDIT-001): `INVENTORY_STOLEN_MARKED` / `INVENTORY_STOLEN_UNMARKED` per row or
  split (amount, whether it split, the new row), `INVENTORY_BULK_STOLEN_CHANGED` as one summary per
  selection; unchanged rows record nothing. The data export (REQ-SEC-058) lists the marker.

**Acceptance**

- [x] Stolen and legitimate stock of one identity are two stacks, never merge (PIECE auto-merge,
  SCU opt-in) and expanding a stack lists only its own entries.
- [x] Every transfer, rebooking and split keeps the marker; refinery store and production never set
  it; a split never undercuts an offer or the earmarks.
- [x] The per-material overview and the craftability sum are unchanged by mixed stock.
- [x] Booking in as stolen and every mark/unmark are refused while the switch is off.
- [x] A member marking another member's row is refused with `403` before any write; the owner and a
  Logistician may mark it.
- [x] The web shows the chip, the filters and the actions in place (REQ-FE-001), and an E2E flow
  books in or marks a part, filters and unmarks. Mein Lager offers the row and bulk actions, the
  Org-Lager the row action on the rows its actions column serves (logisticians).

**Enforced by:** `InventoryStolenMarkServiceTest`, `InventoryStolenStockDataTest`,
`InventoryStackKeyCoverageTest`, `InventoryItemServiceTest`, `InventoryItemServicePersonalRebookTest`,
`MaterialExchangeRepositoryDataTest`, `JobOrderInventoryOwnerRedactorTest`, `MeControllerTest`,
`InventoryItemControllerTest`, `ExternalContractTest`, `ApiVhostAnonymousSurfaceTest`,
`InventoryStolenMarkAjaxControllerTest`, `InventoryStolenMarkerRenderTest`,
`MaterialboersePageControllerMvcTest`, `CapabilityFlagsAdviceTest`,
`InventoryOperationsE2eTest.markingPartOfARowAsStolenSplitsItIntoItsOwnStackInPlace` · **Code:**
`InventoryStolenMarkService`, `InventoryItemRepository`, `InventoryAggregationService`,
`InventoryCheckoutService`, `MaterialExchangeBoardService`, `InventoryProperties`,
`InventoryStolenMarkProxyController`, `inventory/fragments/inventory-stolen-mark.html`, `inventory-common.js` ·
**Issues:** #2096
(epic #2078).

### REQ-INV-055 — A transfer that changes a row's owner tells the new and the previous owner

A transfer that books stock onto **another member** reaches that member through the normal
notification system (REQ-NOTIF-001), so they know about the booking and can trace the change in
their stock. When someone other than the stock's previous owner made the transfer — a Logistician
moving a member's row — the **previous owner** is told as well.

- **Trigger:** a transfer whose new row's member differs from the source row's — the single-row
  book-out `TRANSFER` (REQ-INV-025, `POST /api/v1/inventory/{id}/book-out`) and the bulk rebooking
  in mode `LOCATION` with a target member (REQ-INV-036, `POST /api/v1/inventory/bulk-rebook`). Web
  and app use the same endpoints. A move of location or org-unit pool alone, a personal ↔ shared
  rebooking and a transaction that rolls back notify nobody (published after commit, REQ-NOTIF-002).
- **Recipients:** the new owner gets `INVENTORY_TRANSFERRED_TO_USER`, the previous owner
  `INVENTORY_TRANSFERRED_FROM_USER`, each only when they are not the acting member. Both go through
  seeded, admin-editable rules (`V267`, `EVENT_RECIPIENT`, `exclude_actor`).
- **One notification per recipient per action.** A bulk transfer lists every lot moved onto the
  recipient in that action; the previous-owner notice groups by previous and new owner.
- **Content:** the acting member's name and the lots — per lot amount (SCU, or pieces as `n×`),
  material or item, quality (`Q800`) and location: the target location for the new owner, the
  source location for the previous owner, who is also told the new owner's name. At most 20 lots
  are listed, then `…`; the count names them all. No free text (notes) rides the event.
- **Link:** both notifications open „Mein Lager" (`/inventory?storage=my`).

**Acceptance**

- [x] A member's transfer onto another member notifies the new owner only.
- [x] A Logistician's transfer of a member's row onto another member notifies both; onto
  themselves, the previous owner only.
- [x] A bulk transfer onto another member produces exactly one notification listing every moved
  lot.
- [x] A location-only move, a discard and a rolled-back transfer notify nobody.

**Enforced by:** `InventoryItemServiceBookOutTest.TransferNotificationTests`,
`InventoryCheckoutServiceBulkRebookTest`, `InventoryTransferEventsTest`,
`InventoryTransferNotificationIntegrationTest`, `NotificationRuleEngineIntegrationTest`,
`NotificationPageControllerTest`, `NotificationTypeMessageCoverageTest`,
`AdminNotificationRuleOptionListsTest` · **Code:** `InventoryCheckoutService`,
`InventoryTransferredToUserEvent`, `InventoryTransferredFromUserEvent`, `TransferredLot`,
`V267__seed_inventory_transfer_notification_rules.sql`, `NotificationPageController` ·
**Issues:** #2409.

> **Amended (supply and purchasing):** a transfer into an org unit's pool for a donation
> (REQ-INV-057, `StockCommands.transferToPool`) publishes neither this notice nor the booked-out
> notice of REQ-INV-056; the donor hears about it through the donation notices (REQ-SUP-016).

### REQ-INV-057 — Pool and donation commands of the Lager

`inventory.api.StockCommands` MUST offer, alongside its existing calls, the commands a module needs to
put stock into an org unit's pool and to earmark it for a donation. Each runs in the caller's
transaction (`MANDATORY`), under the lot-lock protocol (ADR-0229), and records its `INVENTORY` audit
event with a `StockBookingReason` (`DONATION`, `PURCHASE`):

- `bookIntoPool(holder, poolUnit, lot, amount, reason)` books stock in as a shared row
  (`personal = false`) held by `holder` and stamped with the pool's org unit, merged into the holder's
  pool row of the same lot; records `INVENTORY_ITEM_CREATED`.
- `transferToPool(sourceRowId, amount, poolUnit, holder, location, reason)` moves an amount of a row
  into such a pool row. The source is lowered or deleted with its job-order and mission slices shrunk
  by the default plan and its offers kept in step; records `INVENTORY_ITEM_TRANSFERRED`. It publishes
  neither `InventoryTransferredTo/FromUserEvent` nor the booked-out-by-other notice, so a donation
  tells the donor once, through the donation notices.
- `earmarkForDonation(rowId, donationId, amount)` adds the donation's slice to a row; stolen stock
  cannot be earmarked; records `INVENTORY_ALLOCATION_ADDED` with the dimension `DONATION`.
- `releaseDonationEarmark(donationId)` removes the donation's slices from every row; records
  `INVENTORY_ALLOCATION_REMOVED`.

`inventory.api.StockReads#donatableRows(userId, materialId, gameItemId)` reads a member's own rows of
one entry that are not marked stolen, with their donation slices, for the pledge dialog's stock picker
(REQ-SUP-025); it writes nothing.

A pool is not a table: it is a shared row held by a member and stamped with `owning_org_unit_id`. The
caller authorises the action; the commands do not read the donation.

**Acceptance**

- [x] A pool book-in creates a shared row stamped with the unit, held by the holder, and merges it.
- [x] A partial transfer lowers the source and books the amount into the pool; a full transfer deletes
  the source and tells the offers.
- [x] A transfer or earmark that would leave a slice uncovered is refused (422).
- [x] A pool command publishes no event of its own.

**Enforced by:** `InventoryPoolCommandsTest` · **Code:** `inventory/api/StockCommands`,
`inventory/api/StockBookingReason`, `inventory/internal/InventoryStockCommands`,
`V284__create_inventory_donation_allocation.sql` · **ADR:**
[ADR-0252](../adr/0252-stockcommands-gains-the-pool-and-donation-commands.md)

### REQ-INV-058 — Earmarked stock

A donation earmark is a third allocation kind beside the job-order and mission slices, in the table
`inventory_item_donation_allocation`. Its slices MUST reduce the row's available amount for every path
that lowers or moves the row:

- the three slice sums are each checked against the row's amount (`InventoryAllocations.fits`) on
  every amount-lowering write, so book-out, sale, transfer, rebooking and the stolen split refuse a
  write that would leave a pledge uncovered (422);
- a row that still carries a donation slice is neither depleted nor moved in full;
- the exchange counts the donation slice in its free-stock calculation;
- a stock merge unions the slices of the folded row into the survivor;
- the row's DTO lists the slices as `donationAllocations` (the donation, the receiving unit's name,
  the earmarked amount), which the Lager reads through the `EarmarkTargets.Donations` SPI that the
  supply module implements, so inventory never names it. The personal Lager shows a pledged slice as
  a chip and offers „Spenden" on the row while the supply surface is on.

Concurrent pledge and sale of the same row cannot both succeed: both take the row's lot lock first and
the row's `@Version` guards the write.

**Enforced by:** `InventoryPoolCommandsTest`, `InventoryItemMapperTest`, `SupplyDonationEarmarksTest`,
`InventorySupplyRenderTest` · **Code:** `inventory/internal/InventoryAllocations`,
`inventory/internal/InventoryCheckoutService`, `inventory/internal/InventoryDonationAllocation`,
`exchange/internal/ExchangeStockWriteService`

### REQ-INV-059 — A Logistician moves a shared entry into another pool of its holder

A Logistician moves a shared entry, or a part of it, from its pool into the pool of another org unit
its holder directly belongs to (`POST /api/v1/inventory/{id}/pool`, optimistic `version`;
ADR-0263). In the Globales Lager each shared entry the caller may edit carries „Pool wechseln"; the
dialog offers the units of `GET /api/v1/inventory/{id}/pool-targets`, the amount (the whole entry by
default) and the merge opt-in.

- **A Logistician of both units.** The caller holds the Logistician authority of the entry's unit and
  of the receiving unit (contextual, `hasRoleInOrgUnit`; an admin passes); otherwise `403`. The
  receiving unit must be a direct membership of the holder (`400`). Naming the entry's own pool
  writes nothing.
- **The whole entry** changes only its unit and keeps its job-order and mission earmarks and its
  Materialbörse offers; an entry with a donation pledge is not moved in full (REQ-INV-058, `422`).
- **A part** is split off the free stock as a new shared entry of the receiving pool, with the
  source's material or item, location, quality and „gestohlen" marker and no note or earmark; the
  source keeps the rest, every earmark and pledge (`422` when the rest cannot carry them) and may not
  fall below its offer (`409`). Fractional parts of piece stock are refused (`400`).
- The result merges by the write-time rules (REQ-INV-026).
- **Audit and notice.** `INVENTORY_POOL_CHANGED` on the entry (from / to as `kind:id`, the amount,
  whether it split and the new row; REQ-AUDIT-001). When somebody other than the holder moved it,
  the holder gets `INVENTORY_POOL_CHANGED_BY_OTHER` (actor, both unit names, the lot; V300).
- The page re-renders in place and tells peers through live sync (`inventory` / `stock`, the
  Materialbörse board; REQ-FE-001).
- A connected application does the same through the org-stock op `move-pool` (REQ-XCH-045).

**Acceptance**

- [x] A Logistician of both units moves a whole entry and a part; the part leaves the earmarks on the
  source; the holder is told, and moving one's own entry tells nobody.
  *`InventoryPoolChangeIntegrationTest`.*
- [x] A caller who is no Logistician of the receiving unit, a unit the holder does not belong to, and
  a part the rest cannot carry the earmarks for are refused and change nothing.
  *`InventoryPoolChangeIntegrationTest`.*
- [x] The picker lists only the holder's other units the caller is a Logistician of.
  *`InventoryPoolChangeIntegrationTest`.*
- [x] The proxies forward the request and relay the targets and the backend's refusal.
  *`InventoryPoolChangeAjaxControllerTest`.*

**Enforced by:** `InventoryPoolChangeIntegrationTest`, `InventoryPoolChangeAjaxControllerTest`,
`ExchangeOrgStockWriteControllerTest`, `AuthorizationMatrixTest`, `RouteGateSnapshotTest`,
`MassAssignmentGuardTest` · **Code:** `inventory/internal/InventoryPoolChangeService`,
`InventoryItemController#changePool` / `#poolTargets`, `InventoryPoolChangeProxyController`,
`inventory-pool-change.js`, `V300` · **Issues:** #2590

### REQ-INV-060 — Other modules rebook a member's own lot between personal and a pool

The inventory module publishes the rebooking of REQ-INV-007 to other modules as
`inventory.api.RebookCommands` (ADR-0277), addressed by lot instead of by row. One call moves an
amount of the member's own stock of one lot between personal and one named org unit's pool, in either
direction. It joins the caller's transaction, after the caller has taken the member's lot lock
(ADR-0229).

- **Into the pool** the rows are taken from the lot's personal part, which the Lager defines once as
  `StockRow.countsAsPersonal` (personal rows and shared rows outside any pool); the exchange's ledger
  reads it, and its `split` query counts the same rows (REQ-XCH-016). The order: the member's personal rows stamped with the
  target unit, then the personal rows without a unit, then the other personal rows, then the
  member's shared rows without a unit, each group oldest first. Shared rows already in a unit's pool
  are never a source. A row is split where only part of it is needed.
- **Back to personal** only the member's own shared rows of that pool move, oldest first. The member
  need not belong to the unit any more.
- **Every moved row** goes through the web's row rebooking: the member stays the holder, the
  material or item, location, quality and „gestohlen" marker carry over, the new row has no earmark
  and no note, and the source is lowered or deleted. SCU stock is not merged; piece and item stock
  merges as REQ-INV-026 says.
- **Refusals are answered, not thrown**, and change nothing:
  - `NOT_A_MEMBER` — into the pool of a unit that is not one of the member's direct memberships (any of
    the four kinds); an admin role or a leadership seat adds nothing;
  - `UNIT_MISMATCH` — a fraction of piece or item stock;
  - `INSUFFICIENT_STOCK` — less stock at the source than asked;
  - `STOCK_EARMARKED` — the move would take stock reserved for a job order, mission or donation;
    back to personal, a shared row with a job-order or mission earmark does not move at all, as in the
    web;
  - `STOCK_OFFERED` — the move would leave a row below what it offers on the Materialbörse.
- **Audit.** Each moved personal row records `INVENTORY_ITEM_DEPERSONALIZED` or
  `INVENTORY_ITEM_PERSONALIZED`, and each moved shared row without a unit `INVENTORY_POOL_CHANGED`
  (from `none` into the named pool) (REQ-AUDIT-001), with the client of the request's
  authentication (REQ-AUDIT-005) and no free text or personal data in the details.
- **Report.** The call returns every row of the lot it created, changed, removed or merged away, with
  its state before and after (personal marker, unit, amount, version, job-order and mission
  earmarks), so a caller can journal the change for undo. A dry run decides and writes nothing.
- **The web's own extras stay in the web** (REQ-INV-007): the pool picked or auto-stamped for a sole
  membership, and a shared row without a unit for a member without memberships. The command never
  chooses a pool. The bulk rebooking (REQ-INV-036) keeps its own whole-row move, which carries the
  earmarks and the note.

**Acceptance**

- [x] A share takes the target unit's rows, then the unit-less rows, then the rest, oldest first,
  skipping reserved stock and splitting the last row.
- [x] A whole share and a partial unshare move the right amounts; holder, quality and stolen marker
  carry over; another pool's rows and another member's rows stay.
- [x] An unshare still works after the member left the unit.
- [x] Each refusal is answered and changes nothing.
- [x] A dry run writes nothing; each direction is audited under the calling client; the report
  carries the states before and after.
- [x] A share of more than the personal rows takes the shared rows without a unit second, oldest
  first, into the named pool with the lot total unchanged; above both it is `INSUFFICIENT_STOCK`;
  an earmark or offer on such a row limits it with the usual refusals; its part is audited as
  `INVENTORY_POOL_CHANGED` under the client. *`RebookCommandsTest`.*

**Enforced by:** `RebookCommandsTest`, `InventoryItemServicePersonalRebookTest`,
`InventoryModuleContractTest`, `ModularityTest` · **Code:** `inventory/api/RebookCommands`,
`inventory/internal/InventoryRebookCommands`, `InventoryCheckoutService#rebookRow` · **Issues:**
#2693, #2694

### REQ-INV-065 — Each stock rule of the Lager is decided once, for the web and the commands

The Lager's stock rules for a book-out, a row's Umbuchung, a split of a row (the pool change of
REQ-INV-059 and the stolen marking of REQ-INV-053) and a lot rebooking (REQ-INV-060) live in one
decision module (ADR-0284). Each decision is a pure function over the rows loaded under the
operation's lock; it reads no repository and returns a refusal value instead of throwing. The web
services and the published commands (`PoolEntryCommands`, `RebookCommands`) are adapters over it:
the web maps a refusal to its existing problem, a command to its refusal value.

- **Book-out, sale and transfer:** no more than the row holds; no fraction of whole-unit stock unless
  it is the whole row; no mission deduction on item stock.
- **Umbuchung of a row:** a positive amount, no more than the row holds, no fraction of whole-unit
  stock unless it is the whole row; a shared row earmarked to a job order or mission does not become
  personal. A donation pledge does not keep a row shared.
- **Split of a part into a new row:** a positive part, no more than the row holds, whole on
  whole-unit stock; the rest stays at or above the row's Materialbörse offer and carries every
  job-order, mission and donation earmark of the row.
- **Lot rebooking:** the rules and order of REQ-INV-060; the decision also names the amount each
  source row gives.
- **Whole units** are counted in one place: game-item stock and `PIECE` materials.
- The HTTP status, the problem detail and the refusal values stay as each surface defines them, and
  each mutation keeps its audit event (REQ-AUDIT-001). A command answers a fraction of piece or item
  stock as `UNIT_MISMATCH`, also in a dry run. Both surfaces compare an amount with the row's amount
  exactly, and a split's rest with the offer and the earmarks within `1e-6`.

**Acceptance**

- [x] Each refusal of each decision is covered on plain rows without mocks.
  *`InventoryStockDecisionsTest`.*
- [x] The web services and the commands keep their status codes, problem details and refusal
  values. *`InventoryItemServiceBookOutTest`, `InventoryItemServicePersonalRebookTest`,
  `InventoryPoolChangeIntegrationTest`, `InventoryStolenMarkServiceTest`, `RebookCommandsTest`, the
  exchange org-stock tests.*
- [x] The mock-heavy web-service tests of the book-out and the Umbuchung shrink to adapter tests
  once their refusal cases are covered by the decision tests. *One adapter test per refusal in
  `InventoryItemServiceBookOutTest` and `InventoryItemServicePersonalRebookTest`; the rule
  permutations live in `InventoryStockDecisionsTest`.*

**Enforced by:** `InventoryStockDecisionsTest`, `RebookCommandsTest`,
`InventoryItemServiceBookOutTest`, `InventoryItemServicePersonalRebookTest` · **Code:**
`inventory/internal/InventoryStockDecisions`, `InventoryItem#countsWholeUnits` · **Issues:** #2732

### REQ-INV-067 — Minimum stock levels: what a level counts and when it is low

> Built, material and game-item levels, the counting, the allocation and the requested amount.

A **minimum stock level** („Mindestbestand") states how much of one thing one org unit's pool should
hold.

- **What it belongs to.** One org unit's pool and either a material with an optional quality tier or
  a game item (no tier). A material may carry several levels in one unit, one per tier; at most one
  level exists per unit and material-and-tier, or per unit and game item.
- **Amounts.** A **minimum** (required) and a **target** (optional, the minimum when omitted), with
  target ≥ minimum > 0. Piece materials and game items take whole units.
- **Counted stock is free stock.** The unit's pool rows only — shared rows of that unit's pool,
  never personal rows and never rows marked „gestohlen" (REQ-INV-053) — minus their job-order,
  mission and donation earmarks (REQ-INV-027, REQ-INV-058), summed over every location.
- **Exclusive allocation, top tier down.** A material's levels in a unit are served from the highest
  tier floor downwards. Each level takes the not-yet-allocated free stock whose quality meets its
  floor, up to its **target**; a level without a tier has the lowest floor and takes whatever is
  left. One unit of stock therefore counts toward exactly one level (ADR-0302).
- **Low.** A level is low while its allocated stock is below its minimum. Every level shows its
  allocated stock, the **shortfall** (target − allocated, never below zero), the **fill ratio**
  (allocated ÷ minimum, so a low level is below 100 %) and „angefordert: X".
- **Requested** („angefordert") sums the outstanding amounts of the unit's `OPEN` supply needs
  (REQ-SUP-003) and of the `OPEN` and `IN_PROGRESS` job orders with the unit as requesting unit, for
  the same material or game item at the level's tier or above. It is **shown only**: it never counts
  as stock and never ends a low state.
- **Inactive tier.** A level whose tier was deactivated keeps its floor, is flagged „Stufe inaktiv"
  and stays until a Logistician changes it.

**Acceptance**

- [x] Personal, stolen and earmarked stock never counts; free stock at several locations sums.
  *`StockLevelAllocationTest`, `StockLevelIntegrationTest`.*
- [x] Two tiered levels of one material: the higher floor fills to its target first, the rest flows
  down, and the untiered level takes the remainder. *`StockLevelAllocationTest`.*
- [x] A level is low exactly while allocated < minimum; shortfall and fill ratio follow the rules
  above. *`StockLevelAllocationTest`.*
- [x] Open needs and open or in-progress requesting orders at the tier or above appear as
  requested and leave the low state unchanged. *`StockLevelIntegrationTest`.*
- [x] A game-item level counts the item's free stock in whole units and takes no tier.
  *`StockLevelIntegrationTest`.*

**Enforced by:** `StockLevelAllocationTest` (pure allocation over plain rows),
`StockLevelIntegrationTest` · **Code:** `inventory/internal/StockLevelAllocation`,
`StockLevelFigures`, `StockLevelRepository#sumPoolStock`, `inventory/api/StockLevelDemandSource`
(implemented by `supply/internal/SupplyStockLevelDemand` and
`joborder/internal/JobOrderStockLevelDemand`) · **Related:**
REQ-INV-068, REQ-NOTIF-030, REQ-SUP-003, REQ-ORDERS-036 · **Decision:** ADR-0302

### REQ-INV-068 — Minimum stock levels: maintenance, API, the „Mindestbestände" tab and the widget

> Maintenance of material and game-item levels, the API with `lowOnly` / `limit`, the tab, the „niedrig" badge,
> the shortcuts, the audit events, the live update, the lifecycle, the notice and the widget are
> built (ADR-0302).

- **Who maintains.** A Logistician of the unit, the lead included (`hasRoleInOrgUnit(unit,
  LOGISTICIAN)`, an admin passes); any other write answers `403`. **Who sees:** everyone who can see
  the unit's pool, through the Lager's `ScopePredicate`. Levels of an inactive unit are not shown.
- **API.** `GET /api/v1/inventory/stock-levels?orgUnitId=&lowOnly=&limit=` returns the levels in the
  caller's scope with the figures of REQ-INV-067, sorted by fill ratio, empty levels first; without
  `orgUnitId` it spans every unit in scope and names each level's unit. `lowOnly=true` keeps only
  the low levels, after the allocation has run over every level of a material, and `limit` (at
  least 1) caps the list; without `lowOnly` the list is by unit, material and tier floor
  descending. `GET /stock-levels/units` lists the
  active units in scope whose levels the caller maintains. `POST` creates a level;
  `PUT /{id}` changes minimum, target and tier; `DELETE /{id}?version=` removes it. The unit and the
  catalogue entry of a level are fixed. A second level for the same unit and material-and-tier
  answers `409`.
- **Locking.** Each level row carries its own `@Version`; `PUT` and `DELETE` echo it and a stale
  version answers `409`. A level write never touches a stock row, and a stock write never bumps a
  level's version.
- **The tab.** The storage page's tab „Mindestbestände" beside „Lager" (EN „Storage · Minimum
  levels", REQ-UI-026); the page has only these two tabs, and „Mein Lager" / „Globales Lager" are a
  switch inside the „Lager" tab. The tab shows one list per unit: material or item, tier, minimum,
  target, free, requested, fill ratio and status. Logisticians edit inline through `krtFetch`
  without a reload (REQ-FE-001). The aggregated Lager (`GET /inventory/aggregated` and
  `GET /inventory/all/grouped`, in the material and in the item catalog) marks a material or game
  item with a „niedrig" badge (`lowStock`) while one of its levels is low in the caller's scope.
- **Shortcuts.** For a Logistician of the unit a low row offers „Bedarf anlegen" and „Auftrag
  anlegen", both prefilled with the material or item, the tier and the shortfall. „Auftrag anlegen"
  opens the normal job-order form with the unit as requesting unit; the responsible unit is chosen
  as usual. A material level opens the material order
  (`/orders/create?materialId=&minQuality=&amount=&requestingOrgUnitId=`), a game-item level the
  item order with the item as its first line, the shortfall as a whole-unit amount and the blueprint
  chosen as in any item order
  (`/orders/create?gameItemId=&gameItemName=&amount=&requestingOrgUnitId=`). „Bedarf anlegen"
  appears only while the supply surface is on and the unit `may_raise_needs` (REQ-SUP-001,
  REQ-SUP-002); it opens the need form on `/supply/needs` prefilled
  (`?needUnit=&needMaterial=&needTier=&needAmount=` for a material,
  `?needUnit=&needItem=&needAmount=` for a game item).
- **Widget „Bestand niedrig"** (REQ-UI-034). Option „Einheit": follows the active context by default
  and shows the union, each row labelled with its unit, when nothing is pinned; it can be fixed to
  one unit. A row-count option; sorted by fill ratio, empty levels first; „alle anzeigen" opens the
  tab. It reads `lowOnly=true` without `limit`, counts the list and shows its first rows, and
  offers no action buttons.
- **Live update.** A level write announces itself on the existing `inventory_all` room under the new
  section `stock-levels` (`LiveSyncTopicClass.INVENTORY_ALL`, backend and frontend registries in
  parity); the tab also re-reads on the room's `stock` section, since a stock write changes the free
  figures.
- **Lifecycle.** Deleting a material deletes its levels with it, one audit event each. A level of
  an inactive unit or of a catalogue entry the catalogue no longer lists stays stored, is not
  shown, raises no notice and does not count in the gauge; it returns with the unit or the entry. A
  game item is hidden once every source that owns it has marked it deleted. A deactivated tier only
  flags the level (REQ-INV-067). None of these sends a notice.
- **Audit** in the Lager area (`AuditDomain.INVENTORY`, REQ-AUDIT-001): `MIN_STOCK_LEVEL_SET` on
  create, `MIN_STOCK_LEVEL_CHANGED` only when a field changed (from / to of minimum, target and tier
  code), `MIN_STOCK_LEVEL_REMOVED` on delete, `MIN_STOCK_LEVEL_REMOVED_BY_CASCADE` per level the
  deletion of its material removed. The subject is the level, labelled by the material
  or item name; the details carry ids, the unit as `kind:id`, tier codes and amounts, no free text
  and no personal data.
- **Notice.** A level falling below its minimum notifies as REQ-NOTIF-030 states; a level of an
  inactive unit or catalogue entry raises none while it is hidden.

**Acceptance**

- [x] A Logistician (the lead included) creates, changes and deletes a level; a plain member and a
  Logistician of another unit get `403`; a pool viewer reads it. *`StockLevelIntegrationTest`.*
- [x] A duplicate level and a stale version answer `409`; a stock write does not 409 a level edit.
  *`StockLevelIntegrationTest`.*
- [x] Each write records its audit event, an unchanged `PUT` records none, and a material deletion
  records one cascade event per level. *`StockLevelIntegrationTest`, `MaterialServiceTest`.*
- [x] Levels of an inactive unit or a delisted catalogue entry are hidden but stay stored and
  return on reactivation; a deactivated tier keeps and flags the level.
  *`StockLevelIntegrationTest`.*
- [x] The tab edits without a reload and a peer's level write appears without a reload.
  *`StockLevelTabRenderTest`, `LiveSyncSectionMapParityTest`.*
- [x] „Bedarf anlegen" is hidden while supply is off or the unit may not raise needs; both shortcuts
  prefill the shortfall, for a game-item level with the item. *`StockLevelTabRenderTest`,
  `JobOrderPrefillTest`, `JobOrderPageControllerCreateFormAuthMvcTest`.*
- [x] `lowOnly` lists the low levels of every unit in scope, emptiest first, capped by `limit`, and
  the aggregated Lager marks a material or game item while one of its levels is low.
  *`StockLevelIntegrationTest`, `InventoryIndexListPatternRenderTest`,
  `InventoryLowStockMarkerRenderTest`.*
- [ ] The widget follows the active context, shows the union with unit labels when nothing is
  pinned, and honours a pinned unit and the row count.

**Enforced by:** `StockLevelIntegrationTest`, `AuthorizationMatrixTest`, `RouteGateSnapshotTest`,
`MassAssignmentGuardTest`, `TenancyGuardTest`, `LiveSyncTopicRegistryParityTest`,
`LiveSyncSectionMapParityTest`, `AdminAuditLogPageControllerTest`, `StockLevelTabRenderTest`,
`StockLevelBackendClientTest` · **Code:** `inventory/internal/StockLevelService`,
`StockLevelAccessPolicy`, `inventory/web/StockLevelController`; frontend
`inventory/web/StockLevelPageController`, `StockLevelProxyController`,
`templates/inventory/stock-levels.html`, `static/js/inventory/stock-levels.js` · **Related:** REQ-INV-067, REQ-NOTIF-030, REQ-SUP-003, REQ-UI-026, REQ-UI-034, REQ-AUDIT-001,
REQ-OBS-011 · **Decision:** ADR-0302

## Out of scope

- Tenancy / visibility scope of inventory (strict-staffel Lager-View) is governed by
  [`org-unit-tenancy.md`](org-unit-tenancy.md) `REQ-ORG-003`; this spec does not change it.
  Grouping is a display concern and never widens visibility.
- The optimistic-locking, `@Version` and `*WithinTransaction` concurrency rules live in
  [`data-persistence.md`](data-persistence.md) and [`backend/CLAUDE.md`](../../backend/CLAUDE.md).

## Open questions

- A convenience "book out N from the whole stack" with automatic allocation across entries
  (FIFO) was deferred; per-entry actions were chosen for v1 (ADR-0003). Revisit if operators
  ask for it.
