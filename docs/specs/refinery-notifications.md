> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** REFINERY · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Refinery order notifications

## Context & goal

Issue #2414 tells the owner of a refinery order when the run has ended and when somebody else touched
the order, instead of leaving them to open the overview. Both are published after the commit, carry no
free text beyond the refinery's location name, a material name and the acting member's display name,
and have a seeded, admin-editable rule. Each can be muted (REQ-NOTIF-027).

## Requirements

### REQ-REFINERY-023 — An order whose run has ended tells its owner

`RefineryReadyNoticeProducer` is a `TimedNoticeProducer` (REQ-NOTIF-026, kind `refinery_ready`). Every
run it announces the open (`OPEN`, `IN_PROGRESS`) orders whose end (`startedAt + durationMinutes`, the database-computed
`endsAt`) has passed, that are not stored and have not been announced: it sets `ready_notified_at` with
an atomic update that does not touch the order's version, and only the call whose update changed the row
publishes `REFINERY_ORDER_READY`. The default rule notifies the owner (`EVENT_RECIPIENT`) with the
location and the outputs (`<amount> SCU <material>`, or `<amount>x <material>` for piece goods).

- An order with no start or no duration, a stored order and a cancelled order are never announced.
- Changing the start or the duration through `updateRefineryOrder` resets `ready_notified_at`, so the
  order is announced again at its new end. That change, a store, a cancellation and a status change to
  `COMPLETED` or `CANCELED` publish `REFINERY_ORDER_READY_CLEARED`, which clears the order's collect-me
  notice (REQ-NOTIF-018).
- Migration `V275` adds the nullable column `refinery_order.ready_notified_at`.

### REQ-REFINERY-024 — A change by somebody else tells the owner

`updateRefineryOrder`, `deleteRefineryOrder` (cancel) and `storeRefineryOrder` publish
`REFINERY_ORDER_CHANGED_BY_OTHER` when the acting member is not the owner. The coded parameter
`changeCode` (REQ-NOTIF-028) is `UPDATED`, `CANCELED`, `STORED` or `STORED_TO_YOU`. A store also tells
every other member whose stock received a row the actor booked onto them (`STORED_TO_YOU`); the owner
hears `STORED_TO_YOU` when the yield went to their own stock and `STORED` when it all went elsewhere.
The default rule notifies the recipient (`EVENT_RECIPIENT`) and excludes the actor.

**Acceptance**

- [x] A run that has ended is announced exactly once; one still running, stored, cancelled or without a
  run time is not.
- [x] A new start or duration makes the order announceable again and clears the earlier notice.
- [x] A change by the owner tells nobody; a change by somebody else tells the owner and, for a store,
  the members the yield was booked onto.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `RefineryNoticeIntegrationTest`, `RefineryOrderServiceLifecycleTest`
(`UpdateRefineryOrderTests`, `DeleteRefineryOrderTests`), `RefineryOrderServiceTest` (`storing…`),
`SeededNotificationRulesIntegrationTest`, `NotificationPageControllerTest` (`targetOf_…`) · **Code:**
`refinery/api/events/RefineryNotices`, `service/RefineryReadyNoticeProducer`,
`service/RefineryOrderService`, `repository/RefineryOrderRepository#markReadyNotified`,
`V275__add_refinery_order_ready_marker.sql`, `V276__seed_refinery_notification_rules.sql` ·
**Issues:** #2414
