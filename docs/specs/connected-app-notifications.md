> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** XCH · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Connected application notifications

## Context & goal

Issue #2414 tells a member what an admin did to a connected application they use: suspended it, let it
work again, demanded a newer version, took a capability away, or switched the whole exchange off.
Until now they found out when a call failed. Each notice is published after the commit, never for the
admin, names the **registry's display name** (never the application's own label), and has a seeded,
admin-editable rule. Each can be muted (REQ-NOTIF-027). The recipients are the holders of the
application's installations (`EXCHANGE_CLIENT_HOLDERS`, REQ-NOTIF-024).

## Requirements

### REQ-XCH-040 — A change to a connected application tells its holders

`ExchangeRegistryService` publishes, after the change is saved:

- `EXCHANGE_CLIENT_SUSPENDED` / `EXCHANGE_CLIENT_ACTIVATED` from `suspendClient` / `activateClient`
  (only when the status actually changed). Each replaces the other for the client, so a member sees the
  latest state only.
- `EXCHANGE_CLIENT_UPDATE_REQUIRED` from `updateClient` when the minimum client version went **up**
  (any floor over none, a numerically greater dotted version, or a different floor that cannot be
  compared); lowering or clearing it announces nothing. The notice names the new version.
- `EXCHANGE_CLIENT_CAPABILITY_REMOVED` from `updateClient` when a capability was removed, naming the
  removed scopes; adding capabilities announces nothing.

The default rules notify the holders of that client's installations (`EXCHANGE_CLIENT_HOLDERS` with the
registry client id) and exclude the admin.

### REQ-XCH-041 — Switching the exchange off tells every holder

`updateSettings` publishes `EXCHANGE_SWITCHED_OFF` when the global switch goes off; the default rule
notifies the holders of any installation of any client (`contextAllExchangeClients`). Switching it on
publishes `EXCHANGE_SWITCHED_ON`, which creates no notice and clears the switched-off notices.

**Acceptance**

- [x] Each event reaches the holders of the right installations and never the admin.
- [x] An activation replaces a suspension; a switch-on clears the switched-off notice.
- [x] A repeated suspension, a lowered or cleared version floor and an added capability announce nothing.
- [x] The notices name the registry's display name, not the application's own label.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `ExchangeNoticeIntegrationTest`, `ExchangeRegistryNoticeTest`,
`SeededNotificationRulesIntegrationTest`, `NotificationPageControllerTest` (`targetOf_…`) ·
**Code:** `exchange/api/events/ExchangeNotices`, `exchange/internal/ExchangeRegistryService`,
`V281__seed_connected_app_notification_rules.sql` · **Issues:** #2414
