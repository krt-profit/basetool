> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** INV / ORDERS · **Related ADRs:** ADR-0101 (builds on ADR-0098/0099); design
> context in [`docs/archive/DESIGN_ITEM_INVENTORY.md`](../archive/DESIGN_ITEM_INVENTORY.md).

# Inventory — game-item stock rows (Items im Lager)

## Context & goal

The Lager historically tracked **materials** only, while game items (`GameItem` — ship
components, weapons; initially those with at least one active blueprint) existed solely
as catalog entries and ITEM-order lines. Manufacturing consumed material stock but the
produced item vanished — no stock row anywhere. This spec makes items first-class
warehouse stock: catalog-discriminated rows on the existing `InventoryItem` aggregate,
separate Material/Items views, material-equal booking flows, ITEM-order-only
allocations, and production booking that books the produced units in.

## Requirements

### REQ-INV-029 — Catalog-discriminated stock rows (gameItem XOR material)

An `InventoryItem` row references **exactly one** catalog entry: a `Material` (with
`quality` 0–1000) or a `GameItem` (with `quality IS NULL`). Game-item rows hold
**positive whole-unit** amounts, follow the PIECE auto-merge rule of REQ-INV-026, and
their stack identity is `user · gameItem · location · personal · owningOrgUnit` (no
quality dimension). Only items that are the output of ≥ 1 active blueprint are bookable:
the Lager item picker (`GET /api/v1/game-items`, `InventoryItemCatalogService`)
offers only those (the catalog predicate is deliberately a superset of the order picker's
RESOURCE-ingredient requirement, which the same read serves with `orderable=true`); the create path itself does not re-check it. Tenancy, owner escape, audit and append-only
semantics are identical to material rows.

**Acceptance**

- [ ] DB CHECKs enforce the XOR and the quality-by-kind pairing (V220); a payload
  violating either is a 400 validation error, never a 500.
- [ ] A gameItem-only payload with a zero/negative/fractional amount is rejected 400
  (the validator hole closed — `materialId == null` no longer skips amount rules).
- [ ] Material-only read surfaces (stacks, flat lists incl. their default
  `material.name` sort, Materialsammlung, Materialbörse picker, order stock index,
  blueprint availability) exclude item rows explicitly — verified per query with an
  item row present.
- [ ] Item rows merge on write (PIECE rule); the merge FOR-UPDATE group query matches
  NULL material/quality + the gameItem key (no silent no-op).

**Enforced by:** `InventoryItemServiceTest` / `InventoryStockMergeTest` item cases,
`ValidQuantityAmountValidatorTest` · **Code:** `InventoryItem`, V220,
`ValidQuantityAmountValidator`, `InventoryItemRepository`, `InventoryItemCatalogService` ·
**Issues:** —

### REQ-INV-030 — Separate Material / Items views on the Lager surfaces

`/inventory`, `/inventory/my` and `/inventory/all` offer a Material ↔ Items view switch
(`view=items` riding the page's query state); the item view mirrors the material tree
(GameItem → stack → lazy entries, REQ-INV-002/005 semantics) without quality or mission
columns and with view-scoped expansion persistence. Drilldowns are per-catalog pages
(`/inventory/material/{id}`, `/inventory/game-item/{id}`) without a switch, both paginated
server-side ([`inventory-lager.md`](inventory-lager.md) REQ-INV-033 — no silent cap). The item
view's gameItem filter is populated only with gameItems that currently have stock rows
in the viewer's scope — never the full catalog. The API read family carries a
`catalog=MATERIAL|ITEM` discriminator (default `MATERIAL` — existing clients
unaffected); a catalog-mismatched filter is rejected with 400 and never silently
ignored (`minQuality`/`missionIds`/`materialIds` under `ITEM`, `gameItemIds` under
`MATERIAL`).

**Acceptance**

- [ ] Both views render independently with persisted expansion state; fragment swaps
  preserve the active view (REQ-FE-005/008 patterns).
- [ ] `catalog=ITEM` grouped/stack/aggregated/flat reads return only item rows, sorted
  by `gameItem.name` where the material default was `material.name`.
- [ ] Peer live-sync refreshes whichever view is active (single `inventory`/`stock`
  seam, REQ-FE-015 — no new section keys).
- [ ] Each Mein-Lager (`/inventory/my`) item leaf carries the "Für Börse freigeben" toggle —
  releasing a stock-backed item offer (REQ-MARKET-002/014); its checked / "Auf Börse" state comes
  from the `releasedItemIds` lookup the item stack-entries endpoint now runs. The global
  (`/inventory/all`) item view has no toggle (owner-only, personal Lager), exactly as the material
  leaf.

**Enforced by:** `InventoryItemControllerTest` catalog cases, `InventoryPageControllerMvcTest`
item-toggle cases, item-view e2e · **Code:** `InventoryItemController`,
`InventoryAggregationService`, `InventoryPageController` · **Issues:** —

### REQ-INV-031 — Item allocations only to qualifying ITEM orders; no mission dimension

A game-item row's Variante-C slices (REQ-INV-027) may target **only** `JobOrderType.ITEM`
orders whose lines request that `GameItem` (`requiredGameItemIds` — the gameItem sibling
of REQ-ORDERS-018's material gate, enforced at the same two write seams: check-in slice
loop and allocation-add; deliberately not on amount-only edits). The mission dimension is
rejected (400, code `BAD_REQUEST`; 422 stays reserved for over-allocation). Material
rows remain allocatable to ITEM orders through their blueprint-derived material
requirements — the gates are parallel, not a replacement. The orphaned-link warning
(REQ-ORDERS-019) and the requester line-edit cleanup cover gameItem slices too.

**Acceptance**

- [ ] Allocating an item row to a MATERIAL order, to an ITEM order not requesting the
  gameItem, or to a mission → 400; to a qualifying ITEM order → slice created, R5
  Σ ≤ amount enforced (422 on violation).
- [ ] An item earmark whose order no longer requests the gameItem is flagged orphaned.

**Enforced by:** `InventoryItemServiceAllocationTest` item cases · **Code:**
`InventoryItemService`, `EarmarkTargets.JobOrders` (`JobOrderEarmarkTargets`),
`JobOrderItemService.requiredGameItemIds`,
`JobOrderQueryService` · **Issues:** —

### REQ-INV-032 — Production booking books the produced stock in

`POST /api/v1/orders/{id}/items/{itemId}/production` (REQ-ORDERS-025) additionally
creates the produced units as item stock: the request's `bookIn` block names the
location ("wo"), owner user ("bei wem", default: actor), owning org unit (create-on-
behalf stamping semantics, REQ-ORG-004/016) and personal flag, and auto-earmarks the
produced units to the producing order by default (`allocateToOrder`, deselectable;
mutually exclusive with `personal = true` — personal stock never carries allocations).
Book-in runs in the same transaction as the consumption (slice-first-then-merge
composition over the REQ-INV-026 merge helper), and is audited as
`INVENTORY_RECEIVED_FROM_PRODUCTION`. **Rollout:** flipped — `bookIn` is required
(`@NotNull`); the production modal always sends it, and the transitional
null-tolerant window (null ⇒ legacy no-stock behaviour) closed when the modal's
book-in section shipped.

**On-behalf gate (2026-09-22, APPSEC-01).** The endpoint's own gate
(`canEditJobOrder`) answers for the *order*, not for the member whose ledger receives
the stock. An explicit `bookIn.ownerUserId` other than the caller therefore needs the
same user-level gate as Einbuchen (REQ-SEC-005 / REQ-ORG-016,
`BookInPolicy.mayBookInFor`, the inventory access policy's `canManageUserInventory`), evaluated on the requested id **before**
the owner lookup and before anything is loaded or consumed — so the refusal is no
existence oracle — and a `personal = true` book-in on behalf of someone else is
refused outright. Before this, a logistician could book produced stock into any
member's ledger, another Staffel's and a private pool included.

> [!note] One book-in rule, one deliberate difference (2026-10-10, P3-5c)
> The Lager's Einbuchen, the production book-in and the refinery store decide whose Lager they may
> book into through one rule, `StockCommands#requireBookIn` with the shared owner check of
> `BookInPolicy`. The refinery store differs on two points, by design until the owner decides,
> named as `BookInRule.refineryStore()` and pinned by `InventoryStockCommandsTest`: a personal
> book-in for another member passes the owner check alone (REQ-INV-035), and a book-in without a
> named owner (an ownerless refinery order) needs Logistician+. The two other paths refuse the
> first and do not ask for the second.

**Acceptance**

- [ ] A production booking with `bookIn` creates (or merges into) the matching item
  stack, earmarked to the order unless deselected; `personal + allocateToOrder` →
  400.
- [ ] A payload without `bookIn` (or without `bookIn.locationId`) is rejected as a
  400 validation error at the API boundary.
- [ ] A `bookIn.ownerUserId` other than the caller whose inventory the caller may not
  write (`canManageUserInventory` false — e.g. a member of another Staffel) → 403,
  before the owner lookup and with no stock written; naming oneself needs no check.
- [ ] `personal = true` on behalf of another member → 403, even when that member is in
  the caller's scope.
- [ ] Both audit events (`JOB_ORDER_PRODUCTION_BOOKED`,
  `INVENTORY_RECEIVED_FROM_PRODUCTION`) are written in the same transaction; the
  inventory event carries `jobOrderId`, `gameItemId`, `amount`, `locationId` keys.

**Enforced by:** `JobOrderItemProductionServiceTest` book-in cases,
`JobOrderItemProductionCreateDtoValidationTest`, `JobOrderProductionBookInSecurityTest`
(the 403 through the HTTP chain) · **Code:**
`JobOrderItemProductionService`, `JobOrderItemProductionCreateDto`,
`InventoryStockCommands#requireBookIn`, `BookInRule` · **Issues:** —

### REQ-INV-061 — Component type, size and grade beside an item's name

In **Mein Lager**, the **Globales Lager** and the aggregated Lager items table (`/inventory`,
`view=items`), a ship component shows its component type and its size and grade next to its name,
e.g. `FR-66 [SCHILD] [S1 · A]`.

- A **ship component** is a `GameItem` whose SC Wiki type is one of `ShipComponentType` (shield,
  cooler, power plant, quantum and jump drive, radar, ship gun, mining laser, missile and bomb with
  their racks, turret, EMP, quantum interdiction generator, tractor and towing beam, salvage head,
  life support). Paints, flair and every other wiki type are no component: they carry a default
  grade "A" that means nothing, so they show neither chip.
- The **component type chip** replaces the category chip (`GameItemKind`, e.g.
  "Schiffskomponente") for a component; any other item keeps its category chip. The type label
  comes from the DE/EN bundles (`inventory.items.componentType.*`); a type without a label falls
  back to the category chip, never to raw upstream text.
- The **size/grade chip** is a neutral `.chip.chip--data` reading `S{size} · {grade}`; a missing
  value is left out, and with neither there is no chip.
- `InventoryGameItemReferenceDto` carries `componentType`, `size` and `grade`, set only for a
  component. They are columns of the `game_item` row the read already loads, so the read path adds
  no query per row.
- The Mein Lager and Globales Lager item group rows render from one shared fragment
  (`inventory/fragments/inventory-item-group :: itemGroupRow`), and all three surfaces render the
  chips from `kernel/fragments/item-chips :: itemChips`, which the job orders reuse (REQ-ORDERS-045).
- Up to tablet width (≤ 1024 px), where the tree takes its content width, the chips move to a
  second line under the name, so they do not widen the name column; the aggregated table stacks
  its cells on a phone as before.

**Acceptance**

- [x] A shield with size 1 and grade A shows a "Schild" chip and an `S1 · A` chip in Mein Lager,
  the Globales Lager and the aggregated items table.
- [x] A paint or flair item shows neither chip and keeps its category chip.
- [x] A component without size and grade shows no data chip.
- [x] Every `ShipComponentType` has a label in all three bundles.

**Enforced by:** `ShipComponentTypeTest`, `InventoryItemMapperTest` reference cases,
`InventoryLagerPatternRenderTest` / `InventoryIndexListPatternRenderTest` chip cases,
`ShipComponentTypeMessageCoverageTest` · **Code:** `ShipComponentType`,
`InventoryItemMapper#gameItemToReferenceDto`, `inventory-item-group.html`, `item-chips.html` · **Issues:** #2719

### REQ-INV-062 — Component class as a coloured chip

On the same three surfaces as REQ-INV-061, a ship component with a known class shows a class chip
after its size/grade chip, e.g. `FR-66 [SCHILD] [S1 · A] [MILITÄR]`.

- The class comes from the SC Wiki (REQ-DATA-024). `InventoryGameItemReferenceDto` carries it as
  `itemClass`, the `ShipComponentClass` name, set only for a component — the same rule as size and
  grade.
- The chip shows the translated class name (`inventory.items.itemClass.*`: Militär, Zivil,
  Industrie, Stealth, Wettkampf). A class without a label, an absent class and every non-component
  get no class chip, never raw upstream text.
- The chip is `.chip.chip--item-class-<class>` from the design system. Its colours are the ten
  `--krt-item-class-*` tokens declared in `styles.css`: the hue is the border and, mixed to 15 %,
  the fill; the `-text` tint is the label. The colour is a second cue beside the label, never the
  only one, and the label keeps WCAG AA on its fill over the card surfaces.

**Acceptance**

- [x] A military shield shows a "Militär" chip after `S1 · A` in Mein Lager, the Globales Lager and
  the aggregated items table.
- [x] A component whose class has no label, and every paint or flair item, shows no class chip.
- [x] Every `ShipComponentClass` has a label in all three bundles.
- [x] Each chip variant uses the design-system tokens, and its label reaches 4.5:1 on its fill.

**Enforced by:** `InventoryItemMapperTest` class cases, `InventoryLagerPatternRenderTest` /
`InventoryIndexListPatternRenderTest` class cases, `ShipComponentTypeMessageCoverageTest`,
`ItemClassChipContrastTest` · **Code:** `InventoryItemMapper#gameItemToReferenceDto`,
`item-chips.html`, `styles.css` · **Issues:** #2720

### REQ-INV-063 — The Lager items view filters by category, component type, size, grade and class

In the items view of Mein Lager and the Globales Lager, the filter popover (REQ-INV-037) offers five
more multi-selects beside item, location, Auftrag and „Gestohlen": **Kategorie** (`GameItemKind`),
**Komponententyp** (`ShipComponentType`), **Größe**, **Grad** and **Klasse**
(`ShipComponentClass`, REQ-INV-062). A member can ask for "only S2 quantum drives, grade A or B" or
"only military S2 quantum drives".

- The options are only values present in the viewer's current stock, drawn from the same
  unfiltered read as the item and location options (REQ-INV-030, REQ-INV-040). An active filter
  never narrows its own or another filter's options.
- Category, component type and class show their translated names (`inventory.items.kind.*`,
  `inventory.items.componentType.*`, `inventory.items.itemClass.*`); sizes read `S0 … S10`, grades
  the bare letter.
- Filtering is server-side. The backend accepts `kinds`, `componentTypes`, `sizes`, `grades` and
  `itemClasses` on `GET /my-inventory/grouped`, `GET /my-inventory/entry-ids` and
  `GET /all/grouped` under `catalog=ITEM`, and rejects them with 400 under `catalog=MATERIAL`, like
  `gameItemIds`.
- A component type is a set of SC Wiki types. A size, grade or class filter narrows to ship
  components only, the items whose size, grade and class the Lager shows (REQ-INV-061,
  REQ-INV-062): a paint carrying grade A never matches "Grad A". Items without the value never
  match, so a class filter leaves out every item without a class.
- None or every box of a filter ticked means no filter: nothing is sent, counted or stored.
- The selection updates the table in place, is mirrored into the address, persisted per page and
  view (REQ-UI-017), counted by the toggle's badge, shown as filter chips, cleared by „Alle
  zurücksetzen", and applied by „Alle markieren" (REQ-INV-034).
- The two Lager scripts share the dimension list (`ITEM_ATTRIBUTE_FILTERS` in
  `inventory-common.js`) and the markup (`inventory-item-attribute-filters.html`). A further
  dimension is one entry in each plus its backend parameter.

**Acceptance**

- [x] Component type „Quantenantrieb" and size S2 show only S2 quantum drives in both Lager views,
  without a page reload.
- [x] The options only offer values present in stock.
- [x] Filter chips, badge count, reset, localStorage restore and „Alle markieren" honour the
  filters.
- [x] The backend rejects the filters for the material view.
- [x] Class „Militär" shows only military components in both Lager views, without a page reload,
  and combines with the other filters (e.g. military S2 quantum drives).

**Enforced by:** `ItemAttributeFilterTest`, `InventoryItemAttributeFilterDataTest`,
`InventoryAggregationServiceTest` attribute cases, `InventoryItemControllerTest` attribute cases,
`InventoryBackendClientTest#itemListingsCarryTheAttributeFilters`, `InventoryPageControllerTest`
attribute cases, `InventoryLagerPatternRenderTest#lagerItemsViewsRenderTheAttributeFiltersFromTheStock`,
`LagerItemAttributeFilterE2eTest` · **Code:** `ItemAttributeFilter`,
`InventoryItemViewRepository`, `InventoryItemController`, `CatalogFilterGuard`,
the frontend `ItemAttributeFilter`, `InventoryPageController#addItemViewModel`,
`ItemAttributeOptions`, `inventory-item-attribute-filters.html`, `inventory-common.js` · **Issues:**
#2721, #2722

### REQ-INV-064 — The item pickers show size, grade and class

The game-item picker of the Lager book-in (Einbuchen, `remote-game-items`) and the item picker of
the job-order form label a ship component with its size, grade and class, so a member tells
look-alike variants apart, e.g. `FR-66 · S1 · A · Militär`.

- The label uses the Lager chips' rules and messages (REQ-INV-061, REQ-INV-062): only a ship
  component gets the extra parts, a missing value is left out, and a class without a label is
  never shown as raw upstream text. Every other item keeps its plain name.
- The search still matches by name only (REQ-FE-016); the extra parts are display.
- The frontend proxies `GET /inventory/item-search` and `GET /orders/item-search` return
  `{id, name, label}` per match, the label built in the request's locale by `ItemPickerLabels`.
  Both pickers show `label`.
- The job-order form labels every item it shows the same way: an existing line when an item order
  is edited, and each adoptable sub-assembly of the derivation preview with the line it adds. The
  backend `GameItemReferenceDto` of order lines and sub-assemblies carries `componentType`,
  `size`, `grade` and `itemClass`, set only for a component (`GameItemReferenceDto.of`); the
  frontend relay `GET /orders/item-derivation/{blueprintId}` adds a `label` to each
  sub-assembly.

**Acceptance**

- [x] Searching „FR-66" while booking in shows `FR-66 · S1 · A · Militär` as the option label.
- [x] The job-order item search shows the same label.
- [x] A non-component item shows its plain name.
- [x] Editing an item order shows an existing FR-66 line as `FR-66 · S1 · A · Militär`.
- [x] A sub-assembly suggestion and the line it adopts show the same label.

**Enforced by:** `ItemPickerLabelsTest`, `InventoryPageControllerMvcTest` item-search cases,
`JobOrderItemPickerControllerMvcTest`, `JobOrderItemLinesTest`,
`JobOrderItemServiceTest#toItemDtosCarryAComponentsTypeSizeGradeAndClass` · **Code:**
`ItemPickerLabels`, `ItemPickerOption`, `InventoryPageController#itemSearch`,
`JobOrderItemPickerController`, `JobOrderItemLines`, `GameItemReferenceDto`,
`krt-catalog-search.js`, `orders-create.js` · **Issues:** #2723

## Out of scope

Non-blueprint items and the "Mein Inventar" boundary (design §11). Materialbörse stock-backed
item offers (design §8) shipped as REQ-MARKET-014 ([`materialboerse.md`](materialboerse.md)) —
including the Mein-Lager Items-view "Für Börse freigeben" leaf toggle (see REQ-INV-030). The
order-detail item-stock panel shipped as REQ-ORDERS-028 and delivery-consumes-earmarked-stock
(best-effort) as REQ-ORDERS-030 (both [`orders-item-production.md`](orders-item-production.md)).

## Open questions

None — owner decisions of 2026-07-16 are recorded in `docs/archive/DESIGN_ITEM_INVENTORY.md`
§10/§11.
