# ADR-0081 — Materialbörse offer model, visibility and live-read facts

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** @greluc
- **Related:** spec REQ-MARKET-001…010 (`docs/specs/materialboerse.md`) · REQ-AUDIT-001/002 · REQ-OBS-011 · REQ-FE-010

## Context

The Materialbörse is a central, org-wide trade board over the existing Lager (`InventoryItem`): a
member releases a stock row for trade with a Markdown remark, others register interest. Several
design questions had to be settled before implementation (decisions D1–D4, confirmed by @greluc):
how the board's facts relate to the underlying stock, who may see the board, how far it is visible,
and how a peer's change reaches other viewers. The board must never expose the item's location and
must keep interessenten identities owner-only.

## Decision

We will model an offer as a **thin, signal-only overlay entity** `MaterialExchangeOffer` referencing
the source `InventoryItem`, plus an independent `MaterialExchangeInterest` aggregate — not new
columns on `InventoryItem`, and not a mapped collection.

- **D1 — Live-read facts.** Material, quality and amount are read **live** from the linked
  `InventoryItem`; the offer stores only the remark, status, released-at and the denormalised
  owner/org-unit. The item's `location` is never read into any board query, DTO, log, audit payload
  or broadcast (Standort stays private). On item delete, `ON DELETE CASCADE` removes the offer.
- **D2 — Member-only reads.** The board is gated on `KRT_MEMBER`; authenticated-but-roleless guests
  do not see the internal trade board.
- **D3 — Org-wide board.** Every `ACTIVE` offer is visible to every member regardless of owning org
  unit — no OrgUnit scope filter. A `foreign` flag (offer squadron ≠ viewer squadron) drives the
  foreign badge; interessenten names stay owner-only.
- **D4 — Dedicated live-sync relay.** Peer sync uses a dedicated Materialbörse presence relay
  mirroring the REQ-FE-010 three-mirror-point contract, with opaque section keys; each peer re-pulls
  its own authorization-checked fragment.

Interest is an independent aggregate with a unique `(offer_id, interested_user_id)` constraint, so
"Interesse anmelden" is an idempotent upsert and never bumps the offer's `@Version`. The remark edit
is guarded by the `support.OptimisticLock` family; a concurrent duplicate-interest race is handled
by the non-transactional-orchestrator + `REQUIRES_NEW` retry pattern (CLAUDE.md find-or-create rule).
One `ACTIVE` offer per item is enforced by a partial-unique index (V210).

## Consequences

- The board never drifts from real stock and never leaks a location; the offer stays a lightweight
  overlay with its own lifecycle and audit trail (`AuditDomain.MARKET`).
- Org-wide visibility means the read path is simpler (no scope predicate) but the marketplace is
  intentionally not staffel-isolated — an accepted trade-off for a shared trade board.
- Live-read facts mean an offer's displayed amount tracks book-outs of the underlying row; a fully
  booked-out item shows 0 SCU until deactivated. Accepted as truthful.
- The dedicated relay is extra infrastructure (a WebSocket endpoint/handler + client receiver);
  justified by REQ-FE-010 applying to a shared surface.

## Alternatives considered

- **Columns on `InventoryItem` (`releasedToExchange`, `remark`).** Rejected — couples the marketplace
  lifecycle to stock rows, bloats the hot inventory entity, and complicates the "one offer, own
  remark/interest list" model.
- **Snapshot quality/amount at release.** Rejected (D1) — drifts from reality; live-read is the single
  source of truth.
- **Staffel-scoped board.** Rejected (D3) — the design is an org-wide marketplace ("für alle sichtbar").
- **No peer sync (acting-user live update only) + amended REQ-FE-010.** Rejected (D4) — the board is a
  shared surface, so REQ-FE-010 applies as written.

