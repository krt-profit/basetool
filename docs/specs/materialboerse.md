> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-09.
> **Owner area:** MARKET · **Related ADRs:** ADR-0082, ADR-0086

# Materialbörse — material-exchange trade board

## Context & goal

The Materialbörse (Flotte & Logistik, `/materialboerse`) is a central, org-wide-visible marketplace
for materials a player releases for trade. It shows **only** which player offers which material, in
which quality and quantity; negotiation and handover happen off-tool between the players. It builds
on the existing Lager (`InventoryItem`): a member releases one of their own stock rows — offering
the whole row or only a **part** of its stock (ADR-0086) — with a free-form Markdown remark ("was
suchst du im Gegenzug?"), other members register interest, and the anbieter takes the negotiation
from there. The design is fixed by the DAS KARTELL design proposal
`proposals/materialboerse-final.html` (locked master-detail layout). Data model + visibility
decisions are recorded in ADR-0082.

## Requirements

### REQ-MARKET-001 — Org-wide, member-only board

Every `ACTIVE` offer is visible to every real member (`KRT_MEMBER`) regardless of the offer's owning
org unit — the board is a single org-wide marketplace, not staffel-scoped. Authenticated-but-roleless
guests do **not** see the board. The board shows per offer: material, quality (0–1000), quantity
(SCU), the anbieter (username) + squadron badge, when it was released, and the interessenten count.

**Acceptance**
- [ ] A `KRT_MEMBER` sees offers from every squadron; a `GUEST` gets 403 on `/materialboerse`.
- [ ] The board read applies no OrgUnit scope filter.

**Enforced by:** `MaterialExchangeServiceTest`, `MaterialboersePageControllerMvcTest` · **Code:**
`MaterialExchangeService#board`, `MaterialExchangeController`, `MaterialboersePageController`

### REQ-MARKET-002 — Release a Lager row (whole or partial, with a Markdown remark)

A member releases one of **their own** Lager rows via the "Für Börse freigeben" checkbox on Mein
Lager or the "Material anbieten" CTA on the board. Release opens the offer dialog: a read-only fact
strip (material · quality as a plain number), an **editable offered-quantity field** ("Menge
anbieten" in SCU, defaulting to the row's full stock with an "Alles" shortcut) + a Markdown textarea
(≤ 20 000 characters, live counter). Material and quality are read **live** from the linked
`InventoryItem`; the **offered quantity is the owner's choice** — the whole row or only a part of it
(ADR-0086), validated server-side to be **positive and at most the item's current amount** (the
client never sets material/quality). Releasing an item that already has an active offer
re-activates/updates its offered amount and remark rather than duplicating (one active offer per
item). The board **never advertises more than is in stock**: the effective quantity shown, filtered
and sorted is `min(offeredAmount, current stock)`, so the offer shrinks as the row is partially
booked out (ADR-0086, clamp-on-read). A **fully** booked-out row is deleted by the inventory book-out
paths (delete-on-depletion), which cascade-deletes the offer (V210 `ON DELETE CASCADE`) — so a dead,
zero-stock offer never lingers; no separate "hide depleted" pass is needed.

**Acceptance**
- [ ] Releasing another member's item is rejected (403).
- [ ] The offer's quality always equals the item's current quality.
- [ ] Offering a part of the row stores that part; offering more than the item's current stock is
rejected (400).
- [ ] The board never shows more than the item's current stock: after part of the row is booked out,
the offer's shown/filtered/sorted amount drops to the remaining stock; when the row is fully booked
out it is deleted and its offer cascade-removed (no lingering zero-stock offer).
- [ ] A remark over 20 000 characters is rejected (400).

**Enforced by:** `MaterialExchangeServiceTest`, `MaterialExchangeRepositoryDataTest` · **Code:**
`MaterialExchangeService#release/updateOffer`, `MaterialExchangeReleaseRequest`,
`MaterialExchangeOfferUpdateRequest`, `V212` offered-amount column, `V210` partial-unique index

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
`MaterialExchangeService#detail/registerInterest`, `MaterialExchangeInterest`

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
business transaction, with a PII-free `key=value` details payload (ids/quality/offered amount/stock/remark
**length** only — never the remark body, never usernames). The unified audit viewer gains a Materialbörse tab.
See `docs/specs/audit.md` (REQ-AUDIT-001/002).

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
change propagates to other viewers without a manual reload, over a dedicated Materialbörse presence
relay (ADR-0082, decision D4), mirroring the REQ-FE-010 three-mirror-point contract (acting-client
broadcast ↔ server relay accept-list ↔ receiving-client apply map). Only opaque section keys cross
the socket; each peer re-pulls its own authorization-checked fragment.

**Acceptance**
- [ ] A release/deactivate/interest by one member refreshes the board of another member viewing it,
with no location or interessent identity crossing the socket.

**Enforced by:** code review, CI Playwright (e2e) · **Code:** the Materialbörse presence relay +
`materialboerse.js` receiver

## Out of scope

- Moving inventory / recording the actual handover (off-tool, REQ-MARKET-003).
- Pricing, escrow, or aUEC settlement.
- A "nur ohne Interessenten" filter (deliberately dropped from the final design).

## Open questions

- Whether to surface a lightweight per-offer "kontaktieren" shortcut (Discord handle) later — parked.

