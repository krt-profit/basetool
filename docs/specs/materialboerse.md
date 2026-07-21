> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-21.
> **Owner area:** MARKET · **Related ADRs:** ADR-0082, ADR-0086, ADR-0087, ADR-0101, ADR-0108, ADR-0116

# Materialbörse — material-exchange trade board

## Context & goal

The Materialbörse (Flotte & Logistik, `/materialboerse`) is a central, org-wide-visible marketplace
for things a player releases for trade. It shows **only** which player offers what, in which quality
and quantity; negotiation and handover happen off-tool between the players. It carries two kinds of
offer:

- a **material offer** — a thin overlay on the existing Lager (`InventoryItem`): a member releases one
  of their own stock rows — offering the whole row or only a **part** of its stock (ADR-0086) — with a
  free-form Markdown remark ("was suchst du im Gegenzug?"); material and quality are read **live** from
  the item, and the offered amount is the owner's stored, clamped-to-stock choice (REQ-MARKET-002);
- an **item offer** (#1185, REQ-MARKET-012) — a **craftable item** ("an item for which a blueprint
  exists"). It comes in two flavours: a **free-stated** offer (no Lager row, the member states the
  quantity, craft-on-demand) and a **stock-backed** offer (REQ-MARKET-014, ADR-0108) released from a
  game-item Lager row, whose quantity is read/clamped against that row's stock exactly like a material
  offer. Either way an item offer has no quality.

Either way other members register interest and the anbieter takes the negotiation from there. The
design is fixed by the DAS KARTELL design proposal `proposals/materialboerse-final.html` (locked
master-detail layout). Data model + visibility decisions are recorded in ADR-0082 (offer model),
ADR-0086 (partial offers) and ADR-0087 (item offers).

The board also carries the **inverse** listing — a **request (Gesuch)**: instead of releasing owned
stock, a member advertises what they **want** — a material or a craftable item, with a free-form
Markdown description, an optional minimum quality (0–1000, for both kinds) and a desired quantity
(SCU or Stück). A request has **no backing Lager row** (it is closest to a free-stated item offer),
so none of the offer's stock-derived rules apply (no clamp-on-read, no ratchet, no
one-active-per-row). Other members signal "Ich kann liefern" and the requester is notified; the
supplier names stay owner-only exactly like the interessenten of an offer. Requests live on the same
`/materialboerse` page under two extra tabs ("Alle Gesuche" / "Meine Gesuche"), and the two CTAs
relabel from "Material anbieten" / "Item anbieten" to "Material suchen" / "Item suchen" when a
Gesuche tab is active. The request model is a **sibling aggregate** (`MaterialExchangeRequest`, its
own table) rather than a discriminator on the offer, recorded in ADR-0116; the requirements are
REQ-MARKET-015…020.

## Requirements

### REQ-MARKET-001 — Org-wide, member-only board

Every `ACTIVE` offer is visible to every real member (`KRT_MEMBER`) regardless of the offer's owning
org unit — the board is a single org-wide marketplace, not staffel-scoped. Authenticated-but-roleless
guests do **not** see the board. The board shows per offer: material, quality (0–1000), quantity in
the material's own unit (SCU for bulk materials, Stück/piece for `PIECE` materials — never a
hardcoded SCU), the anbieter (username) followed by their org-unit affiliation badges, when it was
released, and the interessenten count.

The affiliation badges are derived from the **anbieter's own memberships**, not from the offer's
stored owning org unit (which is `null` for an ownerless-personal Lager row and would leave the
badge blank): there is **no "primary" Staffel** — a member who belongs to several Staffeln, Spezial-
kommandos and/or Bereiche surfaces **all** of them, rendered **after** the username, Staffel(n) first
(brand badge) then Spezialkommando(s) and Bereich(e) (neutral `squadron-badge-sk` badge), each group
name-sorted. Only the three badge kinds `SQUADRON` / `SPECIAL_COMMAND` / `BEREICH` are surfaced — the
Organisationsleitung is deliberately not shown as a badge. The badges are batch-resolved (one
membership query + one org-unit query per board page) so the board stays free of the per-offer N+1
(REQ-DATA-003).

**Acceptance**
- [ ] A `KRT_MEMBER` sees offers from every squadron; a `GUEST` gets 403 on `/materialboerse`.
- [ ] The board read applies no OrgUnit scope filter.
- [ ] The anbieter's every Staffel, Spezialkommando and Bereich membership renders as a badge after
the username (Staffel first, then SK, then Bereich, each name-sorted); an Organisationsleitung
membership is not badged; an anbieter with no such membership shows no badge, and a
legacy/ownerless-stamped offer still shows the anbieter's badges.
- [ ] A `PIECE` material's quantity renders as an integer count in the piece unit, an SCU material's
with the SCU unit — the amount unit follows `Material.quantityType`, matching the Lager
(#1182).

**Enforced by:** `MaterialExchangeServiceTest`, `MaterialboersePageControllerMvcTest` · **Code:**
`MaterialExchangeBoardService#board`, `MaterialExchangeController`, `MaterialboersePageController`

### REQ-MARKET-002 — Release a Lager row (whole or partial, with a Markdown remark)

A member releases one of **their own** Lager rows via the "Für Börse freigeben" checkbox on Mein
Lager or the "Material anbieten" CTA on the board. Release opens the offer dialog: a read-only fact
strip (material · quality as a plain number), an **editable offered-quantity field** ("Menge
anbieten" in the material's own unit — SCU or Stück/piece, #1182 — defaulting to the row's full stock
with an "Alles" shortcut) + a Markdown textarea (≤ 20 000 characters, live counter). When started
from the board CTA ("Material anbieten") rather than a specific Lager checkbox, the dialog leads with
a **searchable material/item picker** whose dropdown list is **closed on open** and reveals itself
only when the member clicks into or types in the picker input — it is never force-opened as the modal
appears (its absolutely-positioned list would otherwise cover the fields below it) — and closes again
on a selection, an outside click, or Escape. The blueprint-product picker of the "Item anbieten"
dialog (REQ-MARKET-012) behaves identically. Above that picker a **Material/Item radio** (default
Material) narrows the combobox to one row kind — the release picker otherwise lists **both** the
caller's material rows and their stock-backed game-item rows (REQ-MARKET-014). The chosen kind is
applied **server-side** (`/releasable-items?kind=MATERIAL|ITEM`, gated inside the DB query
<em>before</em> its row cap) rather than by filtering the returned list, so a row of the wanted kind
past the cap is never silently hidden — the same reachability guarantee the picker's server-side name
search protects. Switching kind clears any row already picked. Material and
quality are read **live** from the linked `InventoryItem`; the **offered quantity is the owner's
choice** — the whole row or only a part of it (ADR-0086), validated server-side to be **positive and
at most the item's current amount** (the client never sets material/quality). Releasing an item that
already has an active offer re-activates/updates its offered amount and remark rather than duplicating
(one active offer per item). The board **never advertises more than is in stock**: the effective
quantity shown, filtered and sorted is `min(offeredAmount, current stock)`, so the offer shrinks as
the row is partially booked out (ADR-0086, clamp-on-read). A **fully** booked-out row is deleted by
the inventory book-out paths (delete-on-depletion), which cascade-deletes the offer (V210 `ON DELETE
CASCADE`) — so a dead, zero-stock offer never lingers; no separate "hide depleted" pass is needed.

The Lager **stock merge** (REQ-INV-026, ADR-0097) **never** touches an offer: a row that backs any
offer is excluded from the merge — it is never folded away (which would cascade-delete the offer) and
never a survivor whose amount changes — so a merge never alters the offered quantity or the offer's
live-read material/quality.

**Acceptance**
- [ ] Releasing another member's item is rejected (403).
- [ ] The offer's quality always equals the item's current quality.
- [ ] A stock merge never changes an offer: a row backing an offer is left out of the merge and its
offered quantity is unchanged.
- [ ] Offering a part of the row stores that part; offering more than the item's current stock is
rejected (400).
- [ ] The board never shows more than the item's current stock: after part of the row is booked out,
the offer's shown/filtered/sorted amount drops to the remaining stock; when the row is fully booked
out it is deleted and its offer cascade-removed (no lingering zero-stock offer).
- [ ] A remark over 20 000 characters is rejected (400).
- [ ] The board-CTA release dialog opens with the material/item picker dropdown **closed**; the list
appears only when the picker input is clicked or typed into, and dismisses on a selection, an outside
click, or Escape.
- [ ] The board-CTA release dialog carries a Material/Item radio (default Material); picking Item
lists only the caller's stock-backed game-item rows, Material only material rows. The filter is
applied server-side (`releasable-items?kind=…`) inside the DB query, so a matching row past the
picker's row cap is still reachable; switching kind clears any already-picked row.

**Enforced by:** `MaterialExchangeServiceTest`, `MaterialExchangeRepositoryDataTest`,
`InventoryItemCatalogQueryDataTest`, `MaterialExchangeControllerTest`,
`MaterialboardReleaseModalOpensE2eTest`, `MaterialboardItemStockOfferE2eTest` · **Code:**
`MaterialExchangeService#release/updateOffer`, `MaterialExchangeBoardService#myReleasableItems`,
`InventoryItemRepository#findReleasableForUser`, `MaterialExchangeReleaseRequest`,
`MaterialExchangeOfferUpdateRequest`, `V212` offered-amount column, `V210` partial-unique index

### REQ-MARKET-013 — Stock decrease ratchets the offer down (persisted); an increase never changes it

When the Lager row backing an **active** offer is **reduced** — book-out (consume / sell / transfer),
personal rebooking, amount edit, or job-order handover — and the stored offered quantity is no longer
covered, it is **persisted down** to the row's new stock **in the same transaction as the decrement**,
via an atomic conditional update (`ACTIVE` offers only; only when the stored value `> newStock`). This
is **kind-aware** (REQ-MARKET-014, ADR-0108): a **material** offer clamps its `offeredAmount`
(`MaterialExchangeOfferRepository.clampOfferedAmountToStock`), a **stock-backed item** offer clamps its
whole-unit `itemQuantity` (`clampItemQuantityToStock`); both run at the book-out / transfer / rebooking
decrement sites in `InventoryCheckoutService`. The job-order handover sites decrement one row kind each
and clamp only that kind: a **material** handover reduces material rows (`JobOrderHandoverService`,
`clampOfferedAmountToStock`), an **item** delivery reduces game-item rows (`JobOrderItemHandoverService`,
REQ-ORDERS-030, `clampItemQuantityToStock`). This is the *persisting* counterpart to the display-time
clamp-on-read
(REQ-MARKET-002/014, ADR-0086): the board already never *shows* more than is in stock, but without
persisting the reduction the stored value would silently **recover** on a later stock increase.

An **increase** of the backing row never changes the offer — the conditional update is a no-op when
stock rises, so offering more stays an explicit owner decision (the owner raises the offered amount
themselves). A **full** book-out deletes the row and cascade-removes the offer (V210), so no clamp is
needed there. The offer's `@Version` is intentionally left untouched by the ratchet; a concurrent
owner edit is guarded independently by the release/edit `offeredAmount <= current stock` validation.

**Acceptance**
- [ ] Reducing a backing row below its active offer's `offeredAmount` persists `offeredAmount` down to
the new stock (book-out, transfer, rebooking, update, handover).
- [ ] Increasing the backing row leaves the offer's `offeredAmount` unchanged (no auto-expand).
- [ ] A deactivated offer is not touched by the ratchet.
- [ ] A fully booked-out row's offer is cascade-removed (unchanged from REQ-MARKET-002).

**Enforced by:** `MaterialExchangeOfferClampDataTest`, `InventoryItemServiceBookOutTest` · **Code:**
`MaterialExchangeOfferRepository#clampOfferedAmountToStock` / `#clampItemQuantityToStock`,
`InventoryCheckoutService` (book-out / transfer / rebooking + `ratchetBoardOffersToStock` /
`clampOffersToStock`), `InventoryItemService#updateInventoryItem`,
`JobOrderHandoverService#createHandover`, `JobOrderItemHandoverService#createItemHandover`
(item-delivery decrement, REQ-ORDERS-030) · **Issues:** #1182

### REQ-MARKET-003 — Signal-only

Releasing, deactivating, or registering interest **never** moves inventory. The board is a discovery
+ signalling surface; the trade itself is off-tool.

**Acceptance**
- [ ] No Materialbörse write path mutates an `InventoryItem` amount/owner.

**Enforced by:** `MaterialExchangeServiceTest` · **Code:** `MaterialExchangeService`

### REQ-MARKET-004 — Location is never exposed

The source item's `location` (Standort/Übergabeort) is **never** read into any board query, DTO, log
line, audit payload, or live-sync broadcast. It stays private on the owner's Lager only.

**Acceptance**
- [ ] No board query joins `InventoryItem.location`; no Materialbörse DTO carries a location field.

**Enforced by:** `MaterialExchangeRepositoryDataTest`, code review · **Code:**
`MaterialExchangeOfferRepository`, `MaterialExchangeOfferDto`

### REQ-MARKET-005 — No category; quality as a plain number

The board and detail show quality as a **plain 0–1000 number** (no "/ 1000" gauge suffix in the
facts) and carry **no "Kategorie" field**.

**Acceptance**
- [ ] The detail facts show `Qualität` as a bare integer and no category field exists.

**Enforced by:** `MaterialboersePageControllerMvcTest` · **Code:** `materialboerse.html`,
`MaterialExchangeOfferDto`

### REQ-MARKET-006 — Interessenten anonymity

The interessenten names are disclosed **only** to the offer's owner. Every other viewer sees only the
count ("N Interessenten" / "Keine Interessenten"). Registering interest lets the anbieter open the
negotiation; a member cannot register interest in their own offer. Registration is idempotent.

**Acceptance**
- [ ] A non-owner detail response never contains interessent names, only the count.
- [ ] An owner detail response contains the names.
- [ ] A duplicate interest registration is a no-op (unique `(offer, user)`).

**Enforced by:** `MaterialExchangeServiceTest`, `MaterialExchangeRepositoryDataTest` · **Code:**
`MaterialExchangeBoardService#detail`, `MaterialExchangeService#registerInterest`,
`MaterialExchangeInterest`

### REQ-MARKET-007 — Offer lifecycle (edit / deactivate), owner-only, optimistic-locked

Only the owner may edit an offer's **offered amount and remark** ("Angebot bearbeiten",
version-guarded via `support.OptimisticLock`, 409 on mismatch; a raised amount is re-validated
against the item's current stock, 400 if it exceeds it) or deactivate it (from the board detail or
by un-checking the Lager checkbox). A deactivated offer is retained for the audit trail but never
listed.

**Acceptance**
- [ ] A non-owner edit/deactivate is rejected (403); a stale-version edit is a 409.
- [ ] Editing the offered amount above the item's current stock is rejected (400).

**Enforced by:** `MaterialExchangeServiceTest` · **Code:** `MaterialExchangeService`

### REQ-MARKET-008 — Audited area

Every state-mutating Materialbörse activity (offer release, offer edit, offer deactivate, interest
register, interest withdraw) writes exactly one `audit_event` row under `AuditDomain.MARKET`, in the
business transaction, with a PII-free `key=value` details payload (kind/ids/quality/offered
amount/stock/remark **length** only — never the remark body, never usernames). Item offers
(REQ-MARKET-012) **reuse** the same five `MARKET_*` event types with a kind-aware details payload
(`kind`/`product` key/`qty` instead of the material `q`/`amt`/`stock`); the subject label is the
material name for a material offer or the item's display name for an item offer (both non-personal
game-asset names). The unified audit viewer gains a Materialbörse tab. See `docs/specs/audit.md`
(REQ-AUDIT-001/002).

**Acceptance**
- [ ] Each mutation records its `MARKET_*` event; no name or remark body appears in `details`.

**Enforced by:** `docs/specs/audit.md` coverage · **Code:** `MaterialExchangeService`,
`AuditEventType`, `AuditDomain`, `AdminAuditLogPageController`

### REQ-MARKET-009 — UI: locked master-detail, live update, DS-only

`/materialboerse` renders the locked master-detail layout of the design proposal (lean list left,
full offer right) using only design-system classes/tokens; `materialboerse.css` is page composition
only. Tabs "Alle Angebote" / "Meine Angebote" carry counts (board totals, filter-independent). Filters:
search (material or player), min. quality, min. quantity (on the **effective** amount = offered,
capped at current stock), sort (Qualität ↓ · Menge ↓ · Material A–Z · Neueste zuerst — no "nur ohne
Interessenten"). The release/edit modal
carries the editable "Menge anbieten" field (default = full stock, "Alles" shortcut, client-bounded
by the item's amount and server-validated) so a member can offer only a part of a row. Every
interaction updates the DOM in place through `krtFetch` (no full-page reload on success), the remark
renders server-side via the `@markdown` bean into `.markdown-content`, and there are no native
dialogs (KRT modal + `showKrtConfirm` + toasts).
Complies with `docs/specs/frontend-ajax-mutations.md` (REQ-FE-001…014).

**Acceptance**
- [ ] Filter/tab/sort changes and writes never trigger a full-page reload.
- [ ] The remark is server-rendered Markdown; the CTA/modal use no `confirm/alert/prompt`.
- [ ] Master-list rows are native `<button>`s stripped of UA button chrome — no light `buttonface` fill on unselected rows and no beveled/white border around entries (#1184).

**Enforced by:** `MaterialboersePageControllerMvcTest`, CI Playwright (e2e) · **Code:**
`materialboerse.html`, `materialboerse.js`, `materialboerse-release.js`, `materialboerse.css`

### REQ-MARKET-010 — Live multi-user board sync

Because the board is a surface several members see at once, a peer's release / deactivate / interest
change propagates to other viewers without a manual reload, over the shared tool-wide multiplexed
live-sync relay — the global `materialboard` topic room on `/ws/sync` (REQ-FE-015, ADR-0094), into
which the former dedicated Materialbörse presence relay (ADR-0082, decision D4) was folded so the
board no longer forks its own single-instance socket. It mirrors the REQ-FE-010 three-mirror-point
contract (acting-client broadcast ↔ server relay accept-list ↔ receiving-client apply map): the
`board` section key is broadcast by `krtLiveSync.sendChanged('materialboard', ['board'])`, whitelisted
by `LiveSyncTopicClass.MATERIALBOARD`, and applied by the `materialboerse.js` `krtLiveSync.subscribe`
receiver. Only opaque section keys cross the socket; each peer re-pulls its own authorization-checked
fragment. Behaviour is unchanged from the dedicated relay — the same debounced, modal-skipping list
refresh (no deferred-refresh pill) — only the transport is now shared. The pre-rollout
`/ws/materialboerse/board` alias (kept one release for tabs opened before the migration) was
removed in #1236; every board tab now rides the `materialboard` room on `/ws/sync`.

**Acceptance**
- [ ] A release/deactivate/interest by one member refreshes the board of another member viewing it,
with no location or interessent identity crossing the socket.

**Enforced by:** code review, CI Playwright (e2e) · **Code:** the shared `LiveSyncWebSocketHandler`
(topic `materialboard`) + `materialboerse.js` receiver (`window.krtLiveSync`)

### REQ-MARKET-011 — Notify the owner when a member registers interest

When a member registers interest in an offer (REQ-MARKET-006), the offer's owner (the Anbieter)
receives an in-app notification so they learn about the interested party without polling the board
(#1187). This reuses the data-driven notification engine (REQ-NOTIF-007, ADR-0015) exactly like the
bank booking-request decision (REQ-NOTIF-011): the release path publishes a
`MaterialExchangeInterestRegisteredEvent` carrying the owner as the directed recipient
(`contextRecipientSub`), and a seeded default rule (V211) resolves it through a single
`EVENT_RECIPIENT` selector with `exclude_actor = TRUE`. The notification is emitted **only** on a
genuinely new registration — a duplicate/idempotent registration (REQ-MARKET-006) emits nothing — and
after the registration transaction commits (REQ-NOTIF-002), so a rolled-back registration produces no
phantom notification. The interessent's name is carried as a render parameter: this is a permitted
disclosure because the notification reaches **only** the owner, consistent with REQ-MARKET-006's
owner-only interessenten anonymity, and no location or interessent identity crosses the board
live-sync socket (REQ-MARKET-010). The rule stays admin-editable/-deletable at runtime; adding the
`MATERIAL_EXCHANGE_INTEREST_REGISTERED` event/notification types needs no schema migration (open
enums, REQ-NOTIF-003).

**Acceptance**
- [ ] Registering interest in an active offer notifies the offer owner (after commit), excluding the
registering member.
- [ ] A duplicate registration emits no second notification; withdrawing and re-registering emits a
fresh one.
- [ ] The notification renders via `notifications.type.MATERIAL_EXCHANGE_INTEREST_REGISTERED` (DE +
EN + base bundles, `{interessent}`/`{material}` placeholders).

**Enforced by:** `MaterialExchangeServiceTest`, `RuleEvaluationServiceTest`,
`MessageBundleConsistencyTest` · **Code:**
`MaterialExchangeService#registerInterestInNewTransaction`,
`event/MaterialExchangeInterestRegisteredEvent`, `model/NotificationEventType`,
`model/NotificationType`, `db/migration/V211__seed_material_exchange_interest_notification_rule.sql`

### REQ-MARKET-012 — Offer a craftable item (blueprint product) with a stated quantity

> This requirement describes the **free-stated** item offer (no backing Lager row). Since design §8
> shipped, a member may instead release an item offer **from item stock** — a **stock-backed** item
> offer whose quantity is read/clamped against a game-item Lager row exactly like a material offer
> (REQ-MARKET-014, ADR-0108). The two are flavours of the one `ITEM` kind; everything below still holds
> for the free-stated flavour, and the free-stated flavour remains fully supported.

A member may list a **craftable item** on the board via the "Item anbieten" CTA (a second CTA beside
"Material anbieten"). Only items **an active blueprint produces** are offerable: the item picker is
the blueprint-product type-ahead, and the release is rejected server-side unless the chosen
normalized `productKey` resolves through `BlueprintProductService.resolveByProductKey(...)` (#1185).
Because a free-stated item offer has **no** backing Lager row, the member **states the quantity** (a
whole number ≥ 1) — unlike a material offer, whose amount is read live — and there is **no quality**
and **no location**. The offer is a discriminated kind of the same `MaterialExchangeOffer` aggregate
(`kind ∈ {MATERIAL, ITEM}`, ADR-0087): it stores the resolved `productKey`, the canonical display
name snapshotted at release, and the quantity; owner + squadron badge are stamped from the acting
member. Free-stated item offers are **not** de-duplicated (a member may list the same item several
times) — their `inventory_item_id` is `NULL`, distinct under the V210 one-active-offer-per-Lager-row
partial-unique index, which governs material offers and stock-backed item offers (REQ-MARKET-014). On
the board, an item offer
renders its item name, an "Item" marker and its quantity (Stück) — no quality; a non-zero
min-quality filter therefore excludes item offers. Every state-mutating item-offer activity reuses
the `MARKET_*` audit events with a kind-aware, PII-free `kind`/`product`/`qty` details payload
(REQ-MARKET-008), lands on the same live-synced board (REQ-MARKET-010, reusing the `board` section
key), and honours the location-never-exposed / interessenten-anonymity rules unchanged
(REQ-MARKET-004/006).

The shared release/edit modal carries the item-quantity field (`[data-mb-qty-block]`, "Menge
(Stück)") and the material offered-amount field (`[data-mb-amount-block]`, "Menge anbieten",
REQ-MARKET-002) as **mutually exclusive** inputs — exactly one is shown per offer kind, toggled via
the `hidden` attribute by `materialboerse-release.js` (mode `'item'` → item field, otherwise → amount
field). Because `.mb-modal-qty` sets an author `display:flex`, the item field **must** carry the
`.mb-modal-qty[hidden]` CSS guard, or the UA `[hidden] { display:none }` rule loses to the class and
the item-quantity field leaks into every material release (the shipped #1252 defect).

**Acceptance**
- [ ] "Item anbieten" lists an item with a stated quantity; a `productKey` no active blueprint
produces is rejected (404), and nothing is persisted.
- [ ] An item offer carries no quality and no location; the board renders its name + quantity, and a
min-quality filter excludes it.
- [ ] The same member may hold several active offers for the same item.
- [ ] Item-offer release/deactivate/remark/interest record `MARKET_*` events whose details never
contain the display name or remark body.
- [ ] The shared modal shows exactly one quantity field per kind: a material release shows only "Menge
anbieten" (item field hidden), an item offer only "Menge (Stück)" (amount field hidden) — never both.

**Enforced by:** `MaterialExchangeServiceTest`, `MaterialExchangeRepositoryDataTest`,
`MaterialboersePageControllerMvcTest`, `MaterialboardQuantityFieldExclusivityE2eTest` · **Code:**
`MaterialExchangeService#releaseItem`,
`MaterialExchangeItemReleaseRequest`, `MaterialExchangeOffer` (`kind`/`itemProductKey`/`itemName`/
`itemQuantity`), `MaterialExchangeOfferRepository#findBoard`,
`db/migration/V213__add_material_exchange_item_offers.sql`, `materialboerse.html`,
`materialboerse-release.js`, `materialboerse.css`

### REQ-MARKET-014 — Release an item offer from item stock (stock-backed item offer)

Once game items are trackable as Lager stock rows (REQ-INV-029, ADR-0101), an item offer can be
released **from item stock, analogous to a material offer** (design §8, ADR-0108) — the second flavour
of the `ITEM` kind beside the free-stated offer (REQ-MARKET-012). The Materialbörse release picker
("Material anbieten") returns **both** the caller's material rows and their game-item rows; picking a
game-item row releases a **stock-backed item offer**: a `MaterialExchangeOffer` of `kind = ITEM` that
**carries** the `inventory_item_id` (the physical stock), with:

- **Quantity `<=` stock, at release and edit.** The offered `itemQuantity` is a whole number, positive
  and at most the row's current stock — the item sibling of the material `requireOfferableAmount`
  (REQ-MARKET-002). It is clamped to the row's stock **on read** (the board never advertises more than
  is in stock, mirroring ADR-0086) and **ratcheted down** on every stock decrement (REQ-MARKET-013).
- **Identity derived from the row's game item.** A stock row keys on a `GameItem`, an offer on the
  blueprint `product_key` (ADR-0087). The release derives `itemProductKey`/`itemName` from the row's
  game item via its blueprint product (`BlueprintProductService.resolveByGameItem`) — the same identity
  a free-stated offer of that item carries; the catalog predicate (REQ-INV-029) guarantees it resolves.
  The `inventory_item_id` is the physical truth, so the product-key↔item fuzziness stays a display
  concern only.
- **One active offer per row.** The V210 partial-unique index applies as-is (the row's non-null FK), so
  re-releasing a game-item row re-activates its offer instead of duplicating; a full book-out deletes
  the row and cascade-removes the offer (V210 `ON DELETE CASCADE`), so no zero-stock item offer lingers.
- **No new schema table.** Migration V221 only **relaxes** the V213 branch-exclusivity `CHECK` so the
  `ITEM` branch may carry `inventory_item_id` (only that half loosens; `offered_amount IS NULL` on
  `ITEM` and the `MATERIAL` branch are unchanged) — a two-phase-safe loosening.
- **No quality, no location.** Like every item offer, the board shows the item name + quantity (Stück),
  no quality; a non-zero min-quality filter excludes it (a stock-backed row carries no quality either).

**Two entry points.** A stock-backed item offer is released either from the board's "Material
anbieten" picker (which lists the caller's game-item rows alongside their material rows) or from the
**Mein-Lager Items view**, whose item leaf carries the same per-row "Für Börse freigeben" toggle as
the material leaf (REQ-MARKET-002) — the item sibling of the material Lager checkbox. Both post the
same `release` (the backend detects the game-item row and creates the `ITEM` offer); un-checking the
toggle deactivates the row's active offer (`/items/{inventoryItemId}/deactivate`). The item leaf's
checked / "Auf Börse" state comes from the batch `released-item-ids` lookup, which is kind-agnostic
(it keys on the inventory row, not its catalog kind). The toggle is **owner-only** and lives on the
personal Lager only — the global (`/inventory/all`) item view has none, exactly as the material leaf.

`MaterialExchangeService.updateOffer` is **kind-aware** (fixing the pre-existing defect where it
dereferenced a null `inventoryItem` on any item offer): a material offer validates `offeredAmount` vs
stock, a stock-backed item offer `itemQuantity` vs stock, a free-stated item offer `itemQuantity ≥ 1`.
The board's edit CTA is enabled for stock-backed item offers; free-stated offers stay edit-remark-only
in the UI. Every state-mutating stock-backed item-offer activity reuses the `MARKET_*` audit events
with a kind-aware, PII-free `kind`/`item`/`product`/`qty`/`stock` details payload (REQ-MARKET-008), and
the location-never-exposed / interessenten-anonymity / live-sync rules are unchanged
(REQ-MARKET-004/006/010).

**Acceptance**
- [ ] The release picker returns the caller's game-item rows as well as material rows; releasing a
game-item row creates an `ITEM` offer with `inventory_item_id` set, `itemQuantity` ≤ the row's stock,
and `itemProductKey`/`itemName` derived from the row's game item.
- [ ] Releasing a quantity above the row's stock is rejected (400); a whole-number rule is enforced.
- [ ] A second active release for the same game-item row re-activates the one offer (V210), never a
duplicate.
- [ ] Reducing a game-item row below its active offer's quantity ratchets `itemQuantity` down
(book-out / transfer / rebooking); a full book-out cascade-removes the offer.
- [ ] The board shows a stock-backed item offer's name + quantity clamped to current stock, no quality.
- [ ] Editing an item offer works (was impossible — the pre-fix `updateOffer` NPEd on the null Lager
row); a stock-backed edit re-validates the new quantity against current stock (400 if it exceeds it).
- [ ] The free-stated item offer (REQ-MARKET-012) remains fully supported.
- [ ] The Mein-Lager Items-view item leaf carries the "Für Börse freigeben" toggle; checking it
releases the row's stock-backed item offer and un-checking deactivates it, exactly like the material
leaf (owner-only; the global item view has no toggle). Its checked state comes from the kind-agnostic
`released-item-ids` lookup the item stack-entries endpoint now runs.

**Enforced by:** `MaterialExchangeServiceTest`, `MaterialExchangeOfferClampDataTest`,
`InventoryItemCatalogQueryDataTest`, `MaterialboersePageControllerMvcTest`,
`InventoryPageControllerMvcTest` (item-leaf toggle) · **Code:**
`MaterialExchangeService#release`/`#releaseFromItemStock`/`#updateOffer`,
`BlueprintProductService#resolveByGameItem`,
`MaterialExchangeOfferRepository#clampItemQuantityToStock`/`#findBoard`,
`InventoryItemRepository#findReleasableForUser`, `MaterialExchangeBoardService` (effective item
quantity + releasable picker), `InventoryCheckoutService#ratchetBoardOffersToStock`,
`InventoryPageController#viewMyGameItemStackEntries` (item-leaf `releasedItemIds`),
`db/migration/V221__relax_material_exchange_item_offer_stock_link.sql`, `materialboerse.html`,
`inventory-stack-entries.html`, `inventory-materialboerse.js`, `materialboerse-release.js`

### REQ-MARKET-015 — Post a request (Gesuch) for a material or item

A `KRT_MEMBER` posts a wanted-listing via the "Material suchen" / "Item suchen" CTAs (shown when a
Gesuche tab is active). A request is one of two kinds (`MaterialExchangeRequestKind` ∈
`{MATERIAL, ITEM}`, a **sibling aggregate** to the offer, ADR-0116): a **material** request names a
catalogue `Material` (picked from the material-catalogue type-ahead, not the caller's Lager) and a
desired quantity in the material's own unit (SCU or Stück per `Material.quantityType`); an **item**
request names a craftable item (blueprint product, validated through
`BlueprintProductService.resolveByProductKey(...)` exactly like an item offer, REQ-MARKET-012) and a
whole-piece quantity. **Either kind** may carry an optional **minimum quality** (0–1000) — a stated
requester preference, kept even for an item request although items have no intrinsic quality — plus a
free-form Markdown description (≤ 20 000 chars). There is **no backing Lager row**: owner + org unit
are stamped from the acting member (like a free-stated item offer), so none of the offer's
stock-derived rules apply (no clamp-on-read, no ratchet, no one-active-per-row — a member may post
several requests for the same subject). The board is org-wide and member-only, and renders each
request's subject, the requester's affiliation badges, the desired quantity, the min quality when
set, when it was posted, and the supplier count — the same shape as REQ-MARKET-001. `Standort` is
never exposed (REQ-MARKET-004 holds verbatim), and posting is signal-only (REQ-MARKET-003).

**Acceptance**
- [ ] A material request stores its material id, desired amount and optional min quality; an item
request resolves + snapshots the blueprint product (404 if the key resolves to no active blueprint)
and stores the whole-piece quantity + optional min quality. Neither carries a Lager row.
- [ ] A min quality outside 0–1000 is rejected (DB `CHECK`); a non-positive amount / non-whole item
quantity is rejected (400).
- [ ] A `GUEST` gets 403; a `KRT_MEMBER` sees requests from every squadron.

**Enforced by:** `MaterialRequestServiceTest`, `MaterialExchangeRequestRepositoryDataTest`,
`MaterialRequestControllerTest`, `MaterialgesuchPageControllerMvcTest` · **Code:**
`MaterialRequestService#createMaterialRequest/#createItemRequest`, `MaterialExchangeRequest`,
`MaterialRequestCreateRequest`, `MaterialItemRequestCreateRequest`, `MaterialRequestController`,
`db/migration/V224__add_material_exchange_request.sql`

### REQ-MARKET-016 — Request lifecycle (edit / deactivate), owner-only, optimistic-locked

Only the owner may edit a request's **desired quantity, minimum quality and description** ("Gesuch
bearbeiten", version-guarded via `support.OptimisticLock`, 409 on mismatch; a material request
re-validates the amount as positive, an item request as a positive whole number) or deactivate it
("Gesuch zurückziehen"). A deactivated request is retained for the audit trail but never listed. The
subject (material / item) itself is fixed once posted — an edit changes only quantity/quality/remark.

**Acceptance**
- [ ] A non-owner edit/deactivate is rejected (403); a stale-version edit is a 409.
- [ ] Deactivating an already-deactivated request records no second audit event.

**Enforced by:** `MaterialRequestServiceTest` · **Code:** `MaterialRequestService#updateRequest`/
`#deactivate`, `MaterialRequestUpdateRequest`

### REQ-MARKET-017 — Fulfilment interest ("Ich kann liefern"), owner-only supplier anonymity

Any member who can supply a request signals it ("Ich kann liefern"); the requester (owner) alone
sees the supplier names, every other viewer sees only the count — the request-side mirror of
REQ-MARKET-006. A member cannot signal on their own request; the signal is idempotent and guarded by
a unique `(request, user)` constraint through the CLAUDE.md non-transactional find-or-create retry
(`ObjectProvider<Self>` self-proxy, `REQUIRES_NEW` inner method, catch `DataIntegrityViolationException`).
Withdrawing removes the signal (idempotent). Supplier identities never cross the live-sync socket.

**Acceptance**
- [ ] A non-owner request-detail response carries only the supplier count, never names; an owner
response carries the names.
- [ ] Signalling on one's own request is rejected (403); a duplicate signal is a no-op; a concurrent
duplicate is an idempotent success, never a 500.

**Enforced by:** `MaterialRequestServiceTest`, `MaterialExchangeRequestRepositoryDataTest` · **Code:**
`MaterialRequestService#signalFulfillment`/`#withdrawFulfillment`, `MaterialRequestBoardService#detailDto`,
`MaterialExchangeRequestInterest`

### REQ-MARKET-018 — UI: one board, four tabs, mode-aware CTAs, live update, live multi-user sync

`/materialboerse` renders offers and requests as two modes of **one** master-detail board with a
shared four-tab bar ("Alle Angebote" / "Meine Angebote" / "Alle Gesuche" / "Meine Gesuche", each with
a filter-independent count). The two CTAs relabel to "Material suchen" / "Item suchen" in requests
mode. Filters mirror the offers board (search, min quality on the request's stated floor, min desired
quantity, sort); the create/edit modal carries the Material/Item radio, the catalogue pickers
(material search + blueprint product), the optional min-quality field (both kinds) and the desired
quantity (SCU/Stück, no stock cap). Every interaction updates the DOM in place through `krtFetch`
(REQ-FE-001…014, no full-page reload); the description renders server-side via `@markdown`; no native
dialogs. Because several members see the board at once, a peer's request create / deactivate /
fulfilment change propagates over the shared `materialboard` `/ws/sync` room (REQ-FE-015, ADR-0094)
on a **new `requests` section key** beside the offers' `board` key — wired at all three
mirror points at once (acting-client broadcast ↔ `LiveSyncTopicClass.MATERIALBOARD` accept-list ↔
receiving-client apply map, REQ-FE-010) and pinned by `LiveSyncSectionMapParityTest`. The receiver
refreshes only the visible board (the `requests` key never re-pulls the offers list and vice-versa).

**Acceptance**
- [ ] Switching tabs, filters, sort and every write never triggers a full-page reload; the CTAs
relabel per mode.
- [ ] A request create/deactivate/fulfilment by one member refreshes the Gesuche board of another
member viewing it, with no description body, supplier identity or location crossing the socket.
- [ ] The `MATERIALBOARD` whitelist is exactly `{board, requests}` and every `sendChanged` key across
the materialboerse modules is whitelisted (parity test).

**Enforced by:** `MaterialgesuchPageControllerMvcTest`, `LiveSyncSectionMapParityTest`,
`MaterialboardRequestModalE2eTest`, CI Playwright · **Code:** `materialboerse.html`,
`fragments/materialgesuch-board.html`, `fragments/materialgesuch-modal.html`, `materialboerse.js`,
`materialgesuch-modal.js`, `LiveSyncTopicClass.MATERIALBOARD`

### REQ-MARKET-019 — Audited area (requests)

Every state-mutating request activity (create, edit, deactivate, fulfilment signal, fulfilment
withdraw) writes exactly one `audit_event` row under `AuditDomain.MARKET`, in the business
transaction, with a PII-free `key=value` details payload (`kind` / material-id or product-key /
`minQuality` / `amt` or `qty` / description **length** only — never the description body, never
usernames, never location). The subject label is the material name or the item's display name. The
new event types are `MARKET_REQUEST_CREATED` / `MARKET_REQUEST_UPDATED` / `MARKET_REQUEST_DEACTIVATED`
/ `MARKET_REQUEST_INTEREST_SIGNALLED` / `MARKET_REQUEST_INTEREST_WITHDRAWN`; the unified audit viewer's
Materialbörse tab lists them (no new domain). See `docs/specs/audit.md` (REQ-AUDIT-001/002).

**Acceptance**
- [ ] Each request mutation records its `MARKET_REQUEST_*` event; no description body or handle
appears in `details`.

**Enforced by:** `MaterialRequestServiceTest`, `docs/specs/audit.md` coverage · **Code:**
`MaterialRequestService`, `AuditEventType`, `AdminAuditLogPageController`

### REQ-MARKET-020 — Notify the requester when a member signals fulfilment

When a member signals they can supply a request (REQ-MARKET-017), the request's owner receives an
in-app notification (the request-side mirror of REQ-MARKET-011). This reuses the data-driven engine
(REQ-NOTIF-007, ADR-0015): the signal path publishes a `MaterialRequestFulfillmentSignalledEvent`
carrying the owner as the directed recipient (`contextRecipientSub`), and a seeded default rule (V225)
resolves it through a single `EVENT_RECIPIENT` selector with `exclude_actor = TRUE`. The notification
is emitted only on a genuinely new signal, after commit (REQ-NOTIF-002). The supplier's name is a
render parameter — a permitted disclosure because the notification reaches only the owner
(REQ-MARKET-019 owner-only anonymity). Adding the `MATERIAL_REQUEST_FULFILLMENT_SIGNALLED` event /
notification types needs no schema migration (open enums, REQ-NOTIF-003).

**Acceptance**
- [ ] Signalling fulfilment on an active request notifies the owner (after commit), excluding the
signalling member; a duplicate signal emits nothing.
- [ ] The notification renders via `notifications.type.MATERIAL_REQUEST_FULFILLMENT_SIGNALLED` (DE +
EN + base bundles, `{lieferant}`/`{material}` placeholders).

**Enforced by:** `MaterialRequestServiceTest`, `MessageBundleConsistencyTest` · **Code:**
`MaterialRequestService#signalFulfillmentInNewTransaction`,
`event/MaterialRequestFulfillmentSignalledEvent`, `model/NotificationEventType`,
`model/NotificationType`,
`db/migration/V225__seed_material_exchange_request_fulfillment_notification_rule.sql`

## Out of scope

- Moving inventory / recording the actual handover (off-tool, REQ-MARKET-003).
- Pricing, escrow, or aUEC settlement.
- A "nur ohne Interessenten" filter (deliberately dropped from the final design).

## Open questions

- Whether to surface a lightweight per-offer "kontaktieren" shortcut (Discord handle) later — parked.

