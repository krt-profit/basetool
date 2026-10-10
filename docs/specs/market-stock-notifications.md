> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** MARKET/INV · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Materialbörse and Lager notifications

## Context & goal

Issue #2414 tells the members who showed interest in a Materialbörse offer or request that it is gone,
and tells a member when somebody else discarded or sold part of their Lager stock. Each is published
after the commit, never for the actor, carries only material names, amounts, locations and the acting
member's display name, and has a seeded, admin-editable rule. Each can be muted (REQ-NOTIF-027).

## Requirements

### REQ-MARKET-021 — A gone offer tells the members who registered interest

`MaterialExchangeService#deactivateOffer` (the owner takes the offer off the board, also by
un-releasing the Lager row) and `MaterialExchangeOfferRatchet` (the offer's stock is gone: a book-out
that empties the row, a rebook, a handover, a wipe or a member's deletion) publish
`MATERIAL_EXCHANGE_OFFER_UNAVAILABLE`, one event per offer. The default rule (`EVENT_RECIPIENTS`)
notifies every member who registered interest, with the item and the coded reason `WITHDRAWN` or
`STOCK_GONE` (REQ-NOTIF-028). The event clears the owner's `MATERIAL_EXCHANGE_INTEREST_REGISTERED`
notices for the offer (REQ-NOTIF-018). A reduced offer announces nothing, and an offer already
deactivated is not announced again.

### REQ-MARKET-022 — A withdrawn request tells the members who could supply it

`MaterialRequestService#deactivate` publishes `MATERIAL_REQUEST_UNAVAILABLE`. The default rule notifies
every member who signalled „Ich kann liefern"; the event clears the owner's
`MATERIAL_REQUEST_FULFILLMENT_SIGNALLED` notices for the request.

### REQ-INV-056 — Stock booked out or sold by somebody else tells the owner

`InventoryCheckoutService#bookOut` publishes `INVENTORY_BOOKED_OUT_BY_OTHER` when a discard or a sale
is made on a row whose owner is not the acting member. One notice per action lists the lot (name,
amount, quality, location); the coded parameter `actionCode` is `DISCARDED` or `SOLD`. The default rule
notifies the owner (`EVENT_RECIPIENT`). Transfers keep their own notices (REQ-INV-055), notes,
allocations and on-behalf book-ins stay silent, and the bulk checkout accepts only the caller's own
rows, so it never notifies.

**Acceptance**

- [x] Each event reaches the members its seeded rule names and never the actor.
- [x] A gone offer or request clears the owner's earlier interest or signal notices.
- [x] An offer without interested members and an own book-out tell nobody.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `MarketAndStockNoticeIntegrationTest`, `MaterialExchangeOfferRatchetTest`,
`MaterialExchangeServiceTest`, `MaterialRequestServiceTest`, `InventoryItemServiceBookOutTest`
(`TransferNotificationTests`), `SeededNotificationRulesIntegrationTest`,
`NotificationPageControllerTest` (`targetOf_…`) · **Code:** `materialexchange/api/events/MarketNotices`,
`inventory/api/events/InventoryNotices`, `materialexchange/internal/MaterialExchangeService`,
`MaterialRequestService`, `MaterialExchangeOfferRatchet`, `service/InventoryCheckoutService`,
`V277__seed_market_and_stock_notification_rules.sql` · **Issues:** #2414
