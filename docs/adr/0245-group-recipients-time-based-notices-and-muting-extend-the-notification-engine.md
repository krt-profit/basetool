# ADR-0245 — Group recipients, time-based notices, per-recipient supersede and muting extend the notification engine

- **Status:** Accepted
- **Date:** 2026-10-10
- **Deciders:** @greluc
- **Related:** spec [`notifications.md`](../specs/notifications.md) (`REQ-NOTIF-007`,
  `REQ-NOTIF-018`, `REQ-NOTIF-024`…`REQ-NOTIF-027`) ·
  [ADR-0096](0096-notification-supersede-on-lifecycle-close.md) (supersede on lifecycle close) ·
  [ADR-0244](0244-a-notification-event-can-reconcile-a-notice-for-named-members.md) (reconcile) ·
  issue #2414

## Context

An event-derived rule reaches at most one person (`EVENT_RECIPIENT`), by an org unit
(`ORG_RELATIVE_ROLE`) or by a bank account. Issue #2414 adds notices to the participants of a
mission, the owners of reset ships, the holders of a connected app and the leadership of an org
unit. Several of them have no user action behind them (a reminder before a mission, a refinery
order that is ready), several must be cleared for one member only (the check-in notice of the
participant who checked in), and the number of types roughly doubles while the Android app raises
an OS notification for each one.

## Decision

1. **Four group selector kinds read only the event**, like `EVENT_RECIPIENT`: `MISSION_PARTICIPANTS`
   (optionally only those not checked in), `MISSION_LEADERSHIP` (owner and co-managers),
   `EXCHANGE_CLIENT_HOLDERS` (one client's, or every client's, non-revoked installations) and
   `EVENT_RECIPIENTS` (the set of affected users the event lists). A selector row stores no column
   for them. The event carries the mission id, the client id or the user set; the notification module
   owns two new SPIs, `MissionRecipientDirectory` and `ExchangeRecipientDirectory`, which the mission
   and exchange modules implement, so the platform module still names no domain class.
2. **`OrgRelativeRole.UNIT_LEADERSHIP`** resolves to the members whose `MembershipRole` confers
   oversight of the org unit (`confersOwnLevelOversight()`), independent of the global `OFFICER`
   role; `OrgUnitRecipientDirectory` gains `leadershipOf`.
3. **Supersede can be scoped to recipients.** `NotificationEvent` gains
   `resolvesNotificationTypesForRecipients()` and `supersedeRecipients()`: the notices of those types
   about the event's entity are deleted for the named members only. The existing per-entity
   `resolvesNotificationTypes()` is unchanged and may be combined with it in one event.
4. **Time-based notices come from one scheduled producer.** A `TimedNoticeProducer` SPI in the
   notification module is implemented by the domain that owns the time (mission, refinery). One
   `@Scheduled` task runs the producers inside a transaction that first takes a Postgres
   transaction-scoped advisory lock with `pg_try_advisory_xact_lock`; an instance that does not get
   the lock skips the run, so only one instance produces. A producer writes the entity's
   „already notified" marker and publishes the notification event in the same transaction, so the
   notice fires at most once and the event follows the commit like every other. Editing the underlying
   time resets the marker. The task reports run count, duration, failures and notices produced
   through `TaskMetrics`.
5. **Members mute notification types.** A `notification_mute` row (member, type) suppresses the type:
   `NotificationCreationService` drops a muted member from the resolved recipients before it stores
   or pushes anything, so a muted type is neither in the inbox nor on the Android channel. A type
   declares whether it is mutable; the account-deletion types (legal deadlines) and
   `EXCHANGE_INSTALLATION_CONNECTED` (the phishing signal) are not. A mute never changes what the
   rules resolve and never clears notices already stored. The profile page and the Android app edit
   the list through `/api/v1/notifications/preferences`.

## Consequences

- A rule written for the new kinds on an event that carries no mission, client or user set resolves
  to nobody; the engine logs it at debug level, as it does for the other event-derived kinds.
- A guest participant has no inbox and is never a `MISSION_PARTICIPANTS` recipient.
- The advisory lock costs one extra statement per run and keeps the producer free of a lock table; a
  crashed instance releases it with its connection.
- Rejected: one selector kind per use case (the set grows with every notification); resolving
  groups inside the domain event (the event would carry an unbounded list); a leader-election
  library or a lock table for the producer (a Postgres advisory lock already exists in the codebase
  for the exchange lots); a mute that deletes the rule instead of the recipient (a rule is shared by
  every member).
