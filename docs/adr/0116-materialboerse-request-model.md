# ADR-0116 — Materialbörse requests (Gesuche) as a sibling aggregate

- **Status:** Accepted
- **Date:** 2026-07-21
- **Deciders:** @greluc
- **Related:** ADR-0082 (offer model) · ADR-0087 (item offers, discriminated aggregate) ·
  ADR-0108 (stock-backed item offers) · spec REQ-MARKET-015…020 (`docs/specs/materialboerse.md`) ·
  REQ-AUDIT-001 · REQ-OBS-011 · REQ-FE-010/015 · REQ-NOTIF-007

## Context

The Materialbörse was one-directional: members **offer** owned stock (Angebote). There was no way to
advertise what you **want**. We add the inverse listing — a **request (Gesuch)**: a member posts that
they want a material or a craftable item, with a free-form Markdown description, an optional minimum
quality (0–1000) and a desired quantity (SCU or Stück). Other members signal "Ich kann liefern" and
the requester is notified.

A request is the **structural inverse** of an offer. An offer is a thin overlay on a live `InventoryItem`
(material/quality/amount read live; ADR-0082/0086) or a discriminated `ITEM` kind (ADR-0087/0108). A
request has **no backing Lager row**: the member states the identity and the quantity directly (closest
to a *free-stated* item offer), so every stock-derived offer rule is inapplicable — no clamp-on-read,
no ratchet, no one-active-per-row uniqueness. A request additionally carries a field an offer never
had: a **stated minimum quality**, allowed on both kinds (per the repository owner's decision — even an
item request may state a desired quality, a pure preference since items have no intrinsic quality).

The board must stay **one page**: four tabs share one master-detail surface, and the two CTAs relabel
per mode ("Material anbieten"/"Item anbieten" ↔ "Material suchen"/"Item suchen").

## Decision

Model a request as a **sibling aggregate** `MaterialExchangeRequest` (its own table
`material_exchange_request`, its own `MaterialExchangeRequestKind` / `MaterialExchangeRequestStatus`
enums and its own interest child `MaterialExchangeRequestInterest`), **not** as a discriminator on
`MaterialExchangeOffer`.

Concrete choices:

- **New table, single discriminated row (V224).** `request_kind ∈ {MATERIAL, ITEM}`; a MATERIAL row
  carries `requested_material_id` (FK → `material`, `ON DELETE CASCADE`) + `requested_amount` (SCU
  double, `> 0`), an ITEM row carries `item_product_key` / `item_name` / `item_quantity` (`> 0`). A
  V213-style exactly-one-branch `CHECK` enforces the exclusivity. `min_quality` is **nullable on both
  kinds**, with its own `CHECK (min_quality IS NULL OR min_quality BETWEEN 0 AND 1000)` — the request
  owns the 0–1000 bound directly (an offer inherited it from the backing Lager row's constraint, which
  a request has no equivalent of). `owner_id` → `app_user` `ON DELETE CASCADE`, `owning_org_unit_id` →
  `org_unit` `ON DELETE SET NULL` (mirroring the offer). **No** partial-unique index — a member may
  post several requests for the same subject (like free-stated item offers, REQ-MARKET-015).
- **Shared MARKET domain, new audit events.** Requests reuse `AuditDomain.MARKET` (the domain doc
  already scopes it to "the material-exchange trade board") with five **new** event types
  `MARKET_REQUEST_CREATED/UPDATED/DEACTIVATED/INTEREST_SIGNALLED/INTEREST_WITHDRAWN`, listed in the
  unified viewer's Materialbörse tab (no new domain/tab). PII-free details as REQ-MARKET-008/019.
- **Shared live-sync room, new section key.** Requests ride the existing global `materialboard`
  `/ws/sync` room on a **new `requests` section key** beside the offers' `board` key
  (`LiveSyncTopicClass.MATERIALBOARD.allowedSections = {board, requests}`); the receiver refreshes only
  the visible board. All three REQ-FE-010 mirror points change together, pinned by
  `LiveSyncSectionMapParityTest`.
- **Parallel interest aggregate + notification.** `MaterialExchangeRequestInterest` mirrors
  `MaterialExchangeInterest` (unique `(request, user)`), the CLAUDE.md find-or-create retry guards the
  signal, and a new `MaterialRequestFulfillmentSignalledEvent` + seeded rule (V225) notifies the
  requester (REQ-MARKET-020), the exact mirror of REQ-MARKET-011.
- **Own gauge.** `basetool_material_request_open_count{status="ACTIVE"}` beside the offers' active-count
  gauge (REQ-OBS-011), label-frugal.

## Consequences

- The offer aggregate is **untouched** — no new discriminator forks its every kind-switch, no risk to
  the shipped clamp/ratchet/one-active-per-row logic. The request read/write services mirror the offer
  split (`MaterialRequestBoardService` / `MaterialRequestService`) so the supplier-anonymity redaction
  is reused, not re-implemented.
- Some duplication is accepted (two enums, two interest tables, two board services) in exchange for a
  clean separation: a request has different columns (no `inventory_item_id`/`offered_amount`, a
  `min_quality`), different lifecycle rules and a different unique-index story, so folding it into the
  offer would mean nullable-everywhere columns and kind-switches that mostly diverge.
- One page, two modes: the shared four-tab bar and the mode-aware CTAs live in `materialboerse.html`;
  the request board/modal are new fragments + a new `materialgesuch-modal.js`, while `materialboerse.js`
  becomes mode-aware. The offer surface keeps its existing fragments and JS entry points unchanged.

## Alternatives considered

- **A `listingType` discriminator (OFFER/REQUEST) on `MaterialExchangeOffer`.** Rejected — a request
  has no `inventory_item_id`, no `offered_amount`, no clamp/ratchet, and *does* carry a stated
  `min_quality` an offer never has; a second discriminator would fork every existing kind-switch (the
  board query, the effective-amount CASE, `updateOffer`, the release paths, the audit details) and make
  half the columns nullable-by-listing-type. ADR-0087/0108 chose "extend the aggregate" precisely
  because item offers *shared* the offer's live/stock machinery — requests do not.
- **A whole separate spec + audit domain + live-sync room.** Rejected — requests are the same board,
  the same members, the same off-tool-negotiation model; a new domain would also need its own alert
  silence-exclusion and viewer tab for no benefit. Requests extend the MARKET spec (REQ-MARKET-015…020)
  and reuse the MARKET domain and the `materialboard` room, exactly as item offers extended it.
- **Item requests over the whole game-item catalogue (not just craftable items).** Rejected for now —
  reusing the blueprint-product picker keeps the identity (`product_key`) and the "Item suchen" UX
  consistent with "Item anbieten"; a general game-item request picker can be a later, additive change.

