# Notifications & alerting

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** NOTIF · **Related ADRs:** [ADR-0014](../adr/0014-notification-system-architecture.md),
> [ADR-0015](../adr/0015-notification-data-driven-rule-engine.md),
> [ADR-0016](../adr/0016-notification-transport-polling-sse.md),
> [ADR-0022](../adr/0022-bank-booking-request-notifications-account-grant-selector.md),
> [ADR-0064](../adr/0064-transactional-email-delivery-channel.md),
> [ADR-0078](../adr/0078-mission-page-fragment-gated-reads-scale-hardening.md),
> [ADR-0094](../adr/0094-tool-wide-topic-room-live-sync-relay.md) (Redis fan-out),
> [ADR-0096](../adr/0096-notification-supersede-on-lifecycle-close.md),
> [ADR-0113](../adr/0113-frontend-sse-relay-request-thread-commit.md),
> [ADR-0146](../adr/0146-the-notification-push-carries-what-arrived.md) · **Epic:**
> [#622](https://github.com/krt-profit/basetool/issues/622)
> **Status:** Implemented — all phases (0–8) delivered (epic
> [#622](https://github.com/krt-profit/basetool/issues/622)). Real-time SSE push is best-effort with
> in-app polling as the guaranteed fallback.

## Context & goal

A generic, extensible notification substrate so **any action in the tool can notify a
configurable set of users**. Notifications form a **per-user inbox** isolated by Keycloak
`sub`, produced by **typed domain events**, with recipients resolved by a **data-driven rule
engine** that admins configure at runtime. A new producer plugs in without a schema change.

First wired use case (UC1): when a **new job order** is created, notify the **officers of the
responsible Squadron / leads of the responsible Special Command**, plus the **logisticians of
that responsible unit** and the **global admins**; the creating actor is excluded.

**Requirement numbering.** `REQ-NOTIF-012` (admins notified on a pending registration),
`REQ-NOTIF-014` (account decision e-mail) and `REQ-NOTIF-015` (pending-registration admin e-mail)
live in [`discord-integration.md`](discord-integration.md), beside the registration flow they belong
to. `REQ-NOTIF-020` was never allocated.

## Wired use cases (registry)

Every producer in the code, reconciled against `NotificationEventType`, `NotificationType` and the
seeded default rules (all admin-editable at runtime). A row whose requirement lives in another spec
is the notification-engine view of it; the linked requirement is canonical.

| Event (`NotificationEventType`) | Notification type(s) raised | Seed | Recipients (selectors) | Requirement |
|---|---|---|---|---|
| `JOB_ORDER_CREATED` | `JOB_ORDER_CREATED` | V156 | `ORG_RELATIVE_ROLE` OFFICER / LEAD / LOGISTICIAN on `RESPONSIBLE` + `ROLE` ADMIN | REQ-NOTIF-008 |
| `JOB_ORDER_UPDATED_BY_REQUESTER` | `JOB_ORDER_UPDATED_BY_REQUESTER` | V214 | `ORG_RELATIVE_ROLE` OFFICER / LEAD on `RESPONSIBLE` | REQ-NOTIF-017 |
| `BANK_BOOKING_REQUEST_CREATED` | `BANK_BOOKING_REQUEST_CREATED` | V160 + V194 | `ROLE` BANK_MANAGEMENT, `ACCOUNT_GRANT`, `ACCOUNT_RESPONSIBLE` | REQ-NOTIF-011, [REQ-BANK-026](bank.md) |
| `BANK_BOOKING_REQUEST_CONFIRMED` | `BANK_BOOKING_REQUEST_CONFIRMED` · `BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED` | V161 · V194 | `EVENT_RECIPIENT` (requester) · `ACCOUNT_RESPONSIBLE`; supersedes `…_CREATED` and `…_UPDATED` | REQ-NOTIF-011/-018, REQ-BANK-026 |
| `BANK_BOOKING_REQUEST_REJECTED` | `BANK_BOOKING_REQUEST_REJECTED` · `BANK_BOOKING_REQUEST_RESPONSIBLE_REJECTED` | V161 · V194 | `EVENT_RECIPIENT` (requester) · `ACCOUNT_RESPONSIBLE`; supersedes `…_CREATED` and `…_UPDATED` | REQ-NOTIF-011/-018, REQ-BANK-026 |
| `BANK_BOOKING_REQUEST_CANCELLED` | none (no rule) | — | supersedes `…_CREATED` and `…_UPDATED` only | REQ-NOTIF-018 |
| `BANK_BOOKING_REQUEST_UPDATED_BY_REQUESTER` | `BANK_BOOKING_REQUEST_UPDATED` | V269 | `ROLE` BANK_MANAGEMENT, `ACCOUNT_GRANT`, `ACCOUNT_RESPONSIBLE`; supersedes `…_CREATED` and `…_UPDATED` first, so the new notice replaces the old one | REQ-NOTIF-018, [REQ-BANK-056](bank.md) |
| `BANK_ACCOUNT_RESPONSIBLE_ASSIGNED` | `BANK_ACCOUNT_RESPONSIBLE_ASSIGNED` | V269 | `EVENT_RECIPIENT` (each member who became a responsible holder of the account); rendered with `accountNo` and the number of requests `pending` their approval | [REQ-BANK-034](bank.md) |
| `BANK_BOOKING_REQUEST_CREATED` (reconcile, `BankBookingRequestNoticesReconciledEvent`) | `BANK_BOOKING_REQUEST_CREATED` | V160 + V194 | the rules of `…_CREATED`, applied only to the members who became or stopped being responsible holders of the request's account | REQ-NOTIF-023, [REQ-BANK-034](bank.md) |
| `DISCORD_REGISTRATION_PENDING` | `DISCORD_REGISTRATION_PENDING` | V174 | `ROLE` ADMIN (`exclude_actor = false`) | REQ-NOTIF-012 ([discord-integration.md](discord-integration.md)) |
| `DISCORD_REGISTRATION_DECIDED` | none (no rule) | — | supersedes `DISCORD_REGISTRATION_PENDING` only (approve, reject, deletion while pending) | REQ-NOTIF-012, REQ-NOTIF-018 |
| `JOB_ORDER_CLOSED` | none (no rule) | — | supersedes `JOB_ORDER_CREATED` and `JOB_ORDER_UPDATED_BY_REQUESTER` only (completed, rejected, deleted) | REQ-NOTIF-008, REQ-NOTIF-018 |
| `MATERIAL_EXCHANGE_INTEREST_REGISTERED` | `MATERIAL_EXCHANGE_INTEREST_REGISTERED` | V211 | `EVENT_RECIPIENT` (offer owner) | REQ-NOTIF-016, [REQ-MARKET-011](materialboerse.md) |
| `MATERIAL_REQUEST_FULFILLMENT_SIGNALLED` | `MATERIAL_REQUEST_FULFILLMENT_SIGNALLED` | V225 | `EVENT_RECIPIENT` (requester) | [REQ-MARKET-020](materialboerse.md) |
| `ACCOUNT_DELETION_REQUESTED` | `ACCOUNT_DELETION_REQUESTED` | V243 | `ROLE` ADMIN (`exclude_actor = false`) | [REQ-SEC-061](security-and-access.md) |
| `ACCOUNT_DELETION_REQUEST_DECLINED` | `ACCOUNT_DELETION_REQUEST_DECLINED` | V243 | `EVENT_RECIPIENT` (requesting member); supersedes `ACCOUNT_DELETION_REQUESTED` | REQ-SEC-061 |
| `ACCOUNT_DELETION_REQUEST_RESOLVED` | none (no rule) | — | supersedes `ACCOUNT_DELETION_REQUESTED` only | REQ-SEC-061, REQ-NOTIF-018 |
| `EXCHANGE_INSTALLATION_CONNECTED` | `EXCHANGE_INSTALLATION_CONNECTED` | V251 | `EVENT_RECIPIENT` (the connected member); rendered with the registry's `client` name only, never the client-supplied label; read by opening „Verbundene Anwendungen" | [REQ-XCH-032](external-exchange.md) |
| `EXCHANGE_BULK_UNDO_APPLIED` | `EXCHANGE_BULK_UNDO_APPLIED` | V257 | `EVENT_RECIPIENT` (the member whose entries an admin's bulk undo restored; one per member and run); rendered with the registry's `client` name and the restored `count` only | [REQ-XCH-034](external-exchange.md) |
| `INVENTORY_TRANSFERRED_TO_USER` | `INVENTORY_TRANSFERRED_TO_USER` | V267 | `EVENT_RECIPIENT` (the member a Lager transfer booked stock onto; one per action and new owner); rendered with `actor`, `count` and `lots` | [REQ-INV-055](inventory-lager.md) |
| `INVENTORY_TRANSFERRED_FROM_USER` | `INVENTORY_TRANSFERRED_FROM_USER` | V267 | `EVENT_RECIPIENT` (the member someone else moved stock away from; one per action, previous and new owner); rendered with `actor`, `newOwner`, `count` and `lots` | [REQ-INV-055](inventory-lager.md) |
| `MISSION_RESCHEDULED` | `MISSION_RESCHEDULED` | V273 | `MISSION_PARTICIPANTS`; supersedes the earlier reschedule and reminder notices | REQ-MISSION-021 |
| `MISSION_CANCELLED` | `MISSION_CANCELLED` | V273 | `MISSION_PARTICIPANTS`; supersedes every open notice of the mission | REQ-MISSION-021 |
| `MISSION_DELETED` | `MISSION_DELETED` | V273 | `EVENT_RECIPIENTS` (the participants captured before the delete); supersedes every open notice of the mission | REQ-MISSION-021 |
| `MISSION_REMINDER_DUE` | `MISSION_REMINDER` | V273 | `EVENT_RECIPIENT` (one event per participant, raised by the time-based producer) | REQ-MISSION-022 |
| `MISSION_STARTED` | `MISSION_CHECKIN_OPEN` | V273 | `MISSION_PARTICIPANTS` not yet checked in; supersedes the reminders | REQ-MISSION-023 |
| `MISSION_CHECKED_IN` | none (no rule) | — | supersedes that participant's `MISSION_CHECKIN_OPEN` only (REQ-NOTIF-025) | REQ-MISSION-023 |
| `MISSION_PARTICIPANT_ADDED` | `MISSION_PARTICIPANT_ADDED_BY_OTHER` | V273 | `EVENT_RECIPIENT` (the added member) | REQ-MISSION-024 |
| `MISSION_PARTICIPANT_REMOVED` | `MISSION_PARTICIPANT_REMOVED_BY_OTHER` | V273 | `EVENT_RECIPIENT` (the removed member); supersedes their add, reminder and check-in notices | REQ-MISSION-024 |
| `MISSION_PARTICIPANT_LEFT` | `MISSION_PARTICIPANT_LEFT` | V273 | `MISSION_LEADERSHIP` | REQ-MISSION-027 |
| `MISSION_NEVER_ENDED` | `MISSION_NEVER_ENDED` | V273 | `MISSION_LEADERSHIP` | REQ-MISSION-025 |
| `MISSION_END_RECORDED` | none (no rule) | — | supersedes `MISSION_NEVER_ENDED` and `MISSION_CHECKIN_OPEN` | REQ-MISSION-025 |
| `MISSION_RESPONSIBILITY_ASSIGNED` | `MISSION_RESPONSIBILITY_ASSIGNED` | V273 | `EVENT_RECIPIENT` (the new responsible member) | REQ-MISSION-026 |
| `OPERATION_PAYOUT_MARKED` | `OPERATION_PAYOUT_PAID_OUT` | V273 | `EVENT_RECIPIENT` (the participant); the last open payout supersedes `OPERATION_COMPLETED` | REQ-MISSION-028 |
| `OPERATION_PAYOUT_UNMARKED` | none (no rule) | — | supersedes that participant's `OPERATION_PAYOUT_PAID_OUT` | REQ-MISSION-028 |
| `OPERATION_COMPLETED` | `OPERATION_COMPLETED` | V273 | `ORG_RELATIVE_ROLE` `MISSION_MANAGER` and `OFFICER` on `RESPONSIBLE` | REQ-MISSION-029 |
| `OPERATION_COMPLETED_UNOWNED` | `OPERATION_COMPLETED` | V273 | `ROLE` `OFFICER` (an operation without an owning unit) | REQ-MISSION-029 |
| `JOB_ORDER_REASSIGNED` | `JOB_ORDER_REASSIGNED` | V274 | `ORG_RELATIVE_ROLE` OFFICER / LEAD / LOGISTICIAN on `RESPONSIBLE` (the new unit); supersedes `JOB_ORDER_CREATED`, `…_UPDATED_BY_REQUESTER` and itself | REQ-ORDERS-041 |
| `JOB_ORDER_FINISHED` | `JOB_ORDER_FINISHED` | V274 | `ORG_RELATIVE_ROLE` OFFICER / LEAD / LOGISTICIAN on `REQUESTING`; the status word is the coded parameter `statusCode` | REQ-ORDERS-042 |
| `JOB_ORDER_ASSIGNEE_ADDED` | `JOB_ORDER_ASSIGNED` | V274 | `EVENT_RECIPIENT` (the assignee); clears a removal | REQ-ORDERS-043 |
| `JOB_ORDER_ASSIGNEE_REMOVED` | none (no rule) | — | supersedes that member's `JOB_ORDER_ASSIGNED` (REQ-NOTIF-025) | REQ-ORDERS-043 |
| `JOB_ORDER_CLAIM_WITHDRAWN` | `JOB_ORDER_CLAIM_WITHDRAWN` | V274 | `EVENT_RECIPIENT` (the member who made the claim); coded reason `reasonCode` | REQ-ORDERS-044 |
| `REFINERY_ORDER_READY` | `REFINERY_ORDER_READY` | V276 | `EVENT_RECIPIENT` (the owner); raised once by the `refinery_ready` timed producer | REQ-REFINERY-023 |
| `REFINERY_ORDER_READY_CLEARED` | none (no rule) | — | supersedes the order's `REFINERY_ORDER_READY` | REQ-REFINERY-023 |
| `REFINERY_ORDER_CHANGED_BY_OTHER` | `REFINERY_ORDER_CHANGED_BY_OTHER` | V276 | `EVENT_RECIPIENT` (the owner, or the member the yield was booked onto); coded `changeCode` | REQ-REFINERY-024 |
| `MATERIAL_EXCHANGE_OFFER_UNAVAILABLE` | `MATERIAL_EXCHANGE_OFFER_UNAVAILABLE` | V277 | `EVENT_RECIPIENTS` (the members who registered interest); coded `reasonCode`; supersedes `MATERIAL_EXCHANGE_INTEREST_REGISTERED` | REQ-MARKET-021 |
| `MATERIAL_REQUEST_UNAVAILABLE` | `MATERIAL_REQUEST_UNAVAILABLE` | V277 | `EVENT_RECIPIENTS` (the members who signalled they can supply); supersedes `MATERIAL_REQUEST_FULFILLMENT_SIGNALLED` | REQ-MARKET-022 |
| `INVENTORY_BOOKED_OUT_BY_OTHER` | `INVENTORY_BOOKED_OUT_BY_OTHER` | V277 | `EVENT_RECIPIENT` (the row's owner); coded `actionCode`, one notice per action | REQ-INV-056 |

Every notification type renders through `notifications.type.<TYPE>` in all three frontend bundles.
The e-mail consumers of REQ-NOTIF-013 (`UserApprovalMailService`, `PendingRegistrationMailService`)
are hand-wired after-commit listeners, not rule-engine rows.

---

### REQ-NOTIF-001 — Generic per-user notification inbox

A `notification` row is a single message addressed to exactly one recipient (`recipient_user_id`,
the Keycloak `sub` = `app_user.id`). It carries a machine `type` (`@Enumerated(STRING)`, no
CHECK — the set grows), a JSON `params` map (plain `TEXT`, never queried) for i18n rendering, a
loose `entity_type` + `entity_id` back-reference (no FK, survives source deletion), and
per-user read state (`is_read` / `read_at`). Text is **never** stored in a language — the
frontend renders `type` + `params` via `notifications.type.*` messages.

**Acceptance**

- [x] A notification stores `type` + `params` + loose entity reference, not a rendered string.
- [x] The schema validates against the entity under `ddl-auto = validate` (V155).

**Enforced by:** `NotificationRepositoryIntegrationTest`, `NotificationParamsCodecTest` ·
**Code:** `model/Notification`, `model/NotificationType`, `notification/internal/NotificationParamsCodec`,
`db/migration/V155__create_notification.sql`

### REQ-NOTIF-002 — Event-driven, after-commit production

Producers publish a `NotificationEvent` via `ApplicationEventPublisher` inside their own
`@Transactional` method. A `@TransactionalEventListener(phase = AFTER_COMMIT)` consumes it on a
dedicated MDC-decorated async executor (`AsyncConfig.NOTIFICATION_EXECUTOR`) in a fresh
transaction. A rolled-back business action produces **no** phantom notifications; notification
work never adds latency to, or fails, the originating transaction; and it never re-saves the
source aggregate (no second `@Version` bump).

**Acceptance**

- [x] Notifications are created only after the producing transaction commits.
- [x] The producer path (`createJobOrder` / `createItemJobOrder`) gains no second `@Version`
  write on the order.

**Enforced by:** `NotificationCreationServiceTest`, `NotificationRuleEngineIntegrationTest`,
`JobOrderServiceTest` · **Code:** `notification/api/events/NotificationEvent`, `service/NotificationEventListener`,
`config/AsyncConfig`, `service/NotificationCreationService`

### REQ-NOTIF-003 — Extensibility without schema changes

A new notification source adds: a `NotificationEvent` implementation, a `NotificationType`
constant, the matching `notifications.type.<TYPE>` i18n keys, and (optionally) a seeded rule.
No migration is required — `type` and the rule `event_type` / `notification_type` columns carry
no CHECK constraint and the engine is data-driven.

**Acceptance**

- [x] Adding a producer needs no DDL change to `notification` or `notification_rule`.

**Enforced by:** spec review · **Code:** `event/*`, `model/NotificationEventType`

### REQ-NOTIF-004 — Per-user isolation (not org-unit scoped)

The inbox is isolated by the JWT `sub` only; it is **not** org-unit scoped. Every read and
mutation is keyed by the caller's `sub`; an id that is unknown **or** owned by someone else
yields HTTP 404 (never 403), so a caller can neither read, mark, nor delete a peer's
notification, nor probe foreign ids. The notification service therefore wires neither
`OwnerScopeService` nor `AuthHelperService` and is excluded from the ArchUnit staffel-scoped
service whitelist (bank `REQ-BANK-008` precedent).

**Acceptance**

- [x] `GET` / `POST` / `DELETE` on a notification owned by another user returns 404.
- [x] `NotificationService` is absent from `ArchitectureTest`'s `staffelScopedServiceNames`.

**Enforced by:** `NotificationServiceTest`, `NotificationRepositoryIntegrationTest`,
`ArchitectureTest` · **Code:** `service/NotificationService`,
`controller/NotificationController`

### REQ-NOTIF-005 — User actions: read & delete

A recipient may mark a single notification read, mark all read, **delete any single
notification of their own — read or unread**, and clear all already-read notifications. Delete
is sub-scoped (404 on a foreign/unknown id) and is independent of the retention sweep
(REQ-NOTIF-009): a user may remove any of their own notifications at any time regardless of age
or read state.

Deleting a **single** notification is a low-stakes action and fires **immediately with no
confirmation dialog** (the success toast is the only feedback) — in both the bell dropdown and
the `/notifications` page. Only the **bulk** clear-read still confirms through the
design-system `showKrtConfirm` modal, since it removes many rows at once.

**Acceptance**

- [x] `POST /{id}/read`, `POST /read-all`, `DELETE /{id}`, `DELETE /read` under
  `/api/v1/notifications` exist and are sub-scoped.
- [x] Deleting another user's notification returns 404 and removes nothing.

**Enforced by:** `NotificationServiceTest`, `NotificationRepositoryIntegrationTest` ·
**Code:** `controller/NotificationController`, `service/NotificationService`

### REQ-NOTIF-006 — Always-on unread indicator

A bell sits top-right on **every** authenticated page; whenever the caller has unread
notifications a badge / attention cue is shown. The initial count is rendered server-side
(`LayoutMiscAdvice#unreadNotificationCount`, fail-soft to 0) and kept fresh by a
client-side poll and after every mutation, always sourced from the server count (so it cannot
go stale). This is **in-app only** — OS / browser push notifications are out of scope.

**Acceptance**

- [x] The bell + unread badge render on every authenticated page.
- [x] The badge reflects the server unread count after mark-read / delete / mark-all /
  clear-read without a full reload.

**Enforced by:** `MessageBundleConsistencyTest`, frontend lint gate · **Code:**
`fragments/sidebar.html`, `static/js/notifications.js`, frontend `config/LayoutMiscAdvice`

### REQ-NOTIF-007 — Data-driven recipient rule engine

Recipients are decided by admin-managed `notification_rule` rows, each owning a set of
`notification_rule_selector` rows. Selector kinds: `SPECIFIC_USER` (a `sub`), `ROLE` (a global
`role.code`), `ORG_RELATIVE_ROLE` (a role — `OFFICER` / `LEAD` / `LOGISTICIAN` /
`MISSION_MANAGER` — evaluated against an org unit the event carries, by `context_role`
`RESPONSIBLE` / `REQUESTING`), `ACCOUNT_GRANT` (the bank employees holding a
`bank_account_grant` on the **bank account** the event carries — see `NotificationEvent.contextAccountId()`),
`EVENT_RECIPIENT` (the single user the event is **directed at** — see
`NotificationEvent.contextRecipientUserId()`, e.g. the officer/lead notified when their booking request is
decided) and `ACCOUNT_RESPONSIBLE` (the derived responsible holder(s) of the bank account the event
carries, REQ-BANK-034 — resolved by `OrgUnitBankResponsibilityService` so the bank stays
org-unit-blind). The last three were added for the bank booking-request use case
(ADR-0022/REQ-NOTIF-011, REQ-BANK-026) and read no selector columns — the account / recipient comes
from the event.
Four group kinds were added for the wider notifications of issue #2414 and read no selector
column either: `MISSION_PARTICIPANTS`, `MISSION_LEADERSHIP`, `EXCHANGE_CLIENT_HOLDERS` and
`EVENT_RECIPIENTS` (REQ-NOTIF-024), and `ORG_RELATIVE_ROLE` gained the role `UNIT_LEADERSHIP`.
A rule's `exclude_actor` flag drops the triggering user. The selector `kind` is an open enum so a
future `GROUP` selector slots in without reworking the engine. Rules are created, edited, enabled /
disabled and deleted at runtime via an admin-only API — **every** rule, including the seeded ones:
every selector kind is accepted on create and update. The event-derived kinds are stored
with every selector column `null`, whatever the request carried. (Until 2026-09-22 the service
refused those three as "seed-only", so no seeded bank, Materialbörse or account-deletion rule could be
saved from the editor — not even to disable it.)

**Acceptance**

- [x] `notification_rule` + `notification_rule_selector` exist (V156) with `ON DELETE CASCADE`.
- [x] Admin CRUD at `/api/v1/notification-rules` is gated on `hasRole('ADMIN')`.
- [x] The engine unions a rule's selectors, applies `exclude_actor`, and de-duplicates
  recipients.
- [x] Every selector kind is admin-manageable; a seeded rule round-trips through the editor
  unchanged.

Admins manage rules through a dedicated admin page (list + create/edit form with a dynamic
selector editor) that relays to the rule API. The page offers **every** event type, notification
type and selector kind, each under a localized label (`admin.notificationRules.*`) rather than its
enum code; an event-derived selector kind shows a hint instead of further fields. A save or delete
re-swaps the rule table in place (REQ-FE-001) instead of reloading the page.

**Enforced by:** `RuleEvaluationServiceTest`, `NotificationRuleEngineIntegrationTest`, `NotificationRuleServiceTest` ·
**Code:** `model/NotificationRule`, `model/NotificationRuleSelector`,
`service/RuleEvaluationService`, `service/NotificationRuleService`,
`controller/NotificationRuleController`, `db/migration/V156__create_notification_rule.sql`,
frontend `controller/AdminNotificationRulePageController`,
`templates/admin/notification-rules.html`, `static/js/notification-rules.js`

### REQ-NOTIF-008 — UC1: notify on job-order creation

When a job order is created, the seeded default rule resolves recipients from the **responsible
org unit**: officers (global `OFFICER` role ∩ membership of that unit), leads
(`org_unit_membership.role = SK_LEAD`), logisticians (`org_unit_membership.is_logistician`), plus the
global admins (`ROLE` `ADMIN`). The creating actor is excluded. The seeded rule is
admin-editable and -deletable. Officer-ness is a Keycloak role mirrored into `user_roles`, so a
freshly-promoted-but-not-yet-logged-in officer becomes a recipient only after the next
Keycloak reconciliation (`UserSyncTask`, daily at 05:00 Europe/Berlin by default, or an admin's
manual sync) — an accepted eventual-consistency window. A login re-syncs the roles at once.

The notice leaves the inbox when the order closes: completing it (status change or the last
handover), rejecting it or deleting it publishes `JOB_ORDER_CLOSED`, which supersedes the order's
`JOB_ORDER_CREATED` and `JOB_ORDER_UPDATED_BY_REQUESTER` notices (REQ-NOTIF-018).

**Acceptance**

- [x] Creating a job order publishes `JobOrderCreatedEvent` after commit.
- [x] The seeded rule (V156, id `62200000-0000-0000-0000-000000000001`) has the four UC1
  selectors and `exclude_actor = true`.
- [x] Completing, rejecting or deleting the order publishes `JobOrderClosedEvent`; reopening it or
  moving it between two closed states does not.

**Enforced by:** `RuleEvaluationServiceTest`, `NotificationRuleEngineIntegrationTest`,
`JobOrderServiceTest`, `JobOrderServicePriorityAndStatusTest` · **Code:**
`service/JobOrderService#publishJobOrderCreated`, `service/JobOrderService#publishJobOrderClosed`,
`joborder/api/events/JobOrderCreatedEvent`, `joborder/api/events/JobOrderClosedEvent`,
`service/RecipientResolutionService`

### REQ-NOTIF-009 — Retention

A scheduled sweep bounds **every** notification, on two windows swept in one run:

- **read** notifications older than `app.notifications.retention.max-age` (default 90 days),
  measured from `readAt`;
- **unread** notifications older than `app.notifications.retention.unread-max-age` (default
  180 days), measured from `createdAt` — an unread row has no read timestamp to age from.

Gated by `app.notifications.retention.enabled` and paced by
`app.notifications.retention.interval`. Disabled under the `test` profile. The sweep is
independent of the user-initiated delete (REQ-NOTIF-005).

**Lower bounds (2026-09-22, BE-MOD-03).** The keys bind through the validated
`NotificationRetentionProperties` record: each window must be at least **`P1D`**, `unread-max-age`
must not be shorter than `max-age`, and `interval` must be at least **`PT1M`** — otherwise the context
refuses to start. As plain `@Value` durations a `P0D` or negative window would have emptied every
inbox on the next run, and "never reaped sooner for being unread" held only for the defaults.

**The unread half is not an optimisation.** Until it existed the sweep reached read rows only, so an
inbox nobody opened retained its notifications — including the triggering member's handle —
indefinitely. The retention period the privacy policy states therefore held for attentive members
and not for absent ones, which is the opposite of how a retention promise has to work. The unread
window is deliberately the longer of the two (a notification still waiting to be seen is worth more
than one already consumed), but it is finite.

**The two halves are isolated from each other** (2026-09-17). They were two sequential
statements, so a read purge that threw — a lock timeout on a large batch, a constraint the
inbox fanout writes — returned before the unread purge was reached: the half that exists
because an unopened inbox kept its rows forever would have silently stopped running, behind a
plain job failure that said nothing about which half failed. `AuditRetentionService` isolates each
audit domain for the same reason. Both halves are now attempted and the first failure is
**rethrown**, so the run still records `outcome=failure`: isolating the halves buys the other half
a run, it does not turn a broken sweep green.

The halves are also counted apart, under
`basetool_notification_retention_deleted_total{kind="read"|"unread"}` beside the job's own
`items` total — a sum of two windows cannot answer "did the unread half delete anything",
which is the question a half that has quietly stopped raises (REQ-OBS-011).

**Acceptance**

- [x] Read notifications past `max-age` are removed by the sweep.
- [x] Unread notifications past `unread-max-age` are removed by the sweep, measured from
  `createdAt`; a read row of the same age is left to the read window.
- [x] The unread cutoff is strictly older than the read cutoff, so a notification is never reaped
  sooner for being unread than it would have been for being read.
- [x] The sweep never tears down the scheduler thread on failure.
- [x] A failure in one half still lets the other half run, and the run is still recorded as
  failed.
- [x] The two halves are counted separately as well as together.
- [x] A window under `P1D`, an unread window shorter than the read one, or an interval under `PT1M`
  refuses to start the context.

**Enforced by:** `NotificationRetentionTaskTest`, `NotificationRepositoryIntegrationTest`
(`deleteReadOlderThan`, `deleteUnreadOlderThan`), `BackendPropertiesValidationTest` (the bounds) ·
**Code:** `task/NotificationRetentionTask`, `notification/internal/NotificationRetentionProperties`,
`service/NotificationService#purgeReadOlderThan` / `#purgeUnreadOlderThan`,
`metrics/ScheduledJob#NOTIFICATION_RETENTION`, `metrics/MetricNames#NOTIFICATION_RETENTION_DELETED`

### REQ-NOTIF-021 — The `notification` event says what arrived

The push carried the literal string `new`. That is enough for the web app, whose handler takes no
argument and refetches the unread count, and not enough for the Android app: without a kind it
cannot file the shade entry under the right notification channel, and without an entity it cannot
open the screen the message is about. Both are requirements of its own design specification
(`REQ-APP-UI-007` there), and neither is answerable from a bare ping.

**The event name does not change.** `notification` is what the frozen contract pins
([api-conventions.md](api-conventions.md)); only its `data` grows. The web client is unaffected by
construction — it never read the payload.

**The payload is a signal, and a signal is per notification type — not per event.** One event
resolves to a `Map<NotificationType, Set<UUID>>`: the same trigger raises different kinds for
different audiences. Two recipients of one event can therefore be told two different things, and a
payload describing the *event* would be wrong for at least one of them. `createFromEvent` returns
its result keyed by signal, and the listener publishes once per signal.

```json
{ "type": "…", "entityType": "JOB_ORDER", "entityId": "…", "params": { "…": "…" } }
```

**A recipient whose inbox was only *cleared* still gets `new`.** When an event supersedes stale
items (REQ-NOTIF-018) the affected recipients receive nothing new — their badge must move, but there
is no message to file or open. That case keeps the historic payload exactly, so the wire is
unchanged for the situation it already covered.

**The render parameters travel.** They are already returned to the same recipient by their own
inbox over the same authenticated connection, so nothing is exposed to anyone the notification was
not addressed to. What a client *does* with them on a lock screen is the client's rule, not this
one — the Android app's chapter 14 obligations are unaffected by the payload existing.

**Degrading is always toward the old behaviour.** A signal that cannot be serialised, a Redis peer
running an older build, a notification type this instance does not know: each falls back to the bare
`new`. A client that cannot be told *what* arrived is still told *that* something did, which is what
it had before.

**Acceptance**

- [x] A refresh-only signal renders the historic `new`; a typed one carries kind, entity and params
  (`NotificationStreamServiceTest`).
- [x] One event with two audiences publishes once per signal, each to its own recipients
  (`NotificationEventListenerTest`).
- [x] The Redis message carries the signal as an **optional** field at the unchanged payload
  version, so neither direction of a rolling deploy depends on the other having landed.

---

### REQ-NOTIF-010 — Real-time push (SSE)

Beyond the in-app polling baseline (REQ-NOTIF-006), real-time server push uses Server-Sent
Events: a backend in-memory emitter registry keyed by `sub` (`NotificationStreamService`) with a
heartbeat, exposed at `GET /api/v1/notifications/stream`; the frontend relays it to the browser
via a resilience-free streaming WebClient (`WebClientConfig#sseWebClient`) and an `EventSource`.
Because each viewing browser holds one long-lived frontend→backend relay connection for its whole
page lifetime, that streaming WebClient uses a **dedicated, generously-sized Netty connection pool**
(`frontend-sse-pool`, `maxConnections=1000`, no `maxLifeTime`) separate from the request path's
100-slot `frontend-pool` — sharing the request pool's ceiling would cap concurrent live viewers and
the surplus would silently lose push (ADR-0078). On a `notification` event the client refreshes its
unread state immediately. Push is
**best-effort** — the polling of REQ-NOTIF-006 is the guaranteed fallback, and a failed push or
broken stream never affects correctness. The unread-count poll adapts to stream health: while the
`EventSource` is connected it backs off to a slow keepalive (≈5 min) and speeds back up (≈1 min)
the moment the stream drops, so a healthy SSE session avoids redundant count polls. The slow
cadence is deliberately frequent enough to remain the REQ-SEC-012 re-auth safety net — the poll
path is what drives 401 re-login detection. The relay obtains its bearer once, at stream open,
through the single-flight authorized-client manager, so a stream opened after the member was idle
refreshes the lapsed token first instead of being refused; it never refreshes mid-stream, and when
no token can be obtained it completes without calling the backend. To keep the slow poll window
bounded even when a stream silently dies, the backend emits a periodic **named** `heartbeat` event (not an SSE comment, which browsers' `EventSource` swallow)
and the client runs a liveness watchdog: if no SSE traffic (`heartbeat`/`notification`) arrives
within ~3× the heartbeat interval, the stream is treated as **half-open** (still "connected" but
dead, so it never fires `error`) and the poll falls back to the fast cadence without waiting for an
`error`; a later event re-promotes it. The emitter registry is per backend instance; delivery across
replicas goes through the `NotificationFanout` seam — `RedisNotificationFanout` delivers to the local
emitters first and then publishes the signal on the `basetool:notify:published` Redis channel
(`app.notifications.redis-fanout.*`, on by default in the `prod` profile and off elsewhere, where
`LocalNotificationFanout` stands in), so a Redis outage degrades to single-instance behaviour
(ADR-0094, discharging ADR-0016's follow-up). When the 30-minute emitter timeout elapses the
backend **completes** the emitter rather than leaving Spring MVC to raise
`AsyncRequestTimeoutException` — which Micrometer would otherwise book as a phantom `503` on
`http.server.requests` even though the client received a clean stream and simply reconnects. A
normal 30-minute stream is thus recorded as a `200` completion, keeping best-effort push off the 5xx
rate. On the client side the frontend relay likewise completes its browser-facing emitter **cleanly**
on any dropped/unavailable backend stream rather than `completeWithError` — which would re-dispatch
the error through the MVC `@ExceptionHandler` and log a spurious ERROR per drop — so a best-effort
stream failure never inflates the frontend error log (the dominant frontend ERROR source during a
backend/Keycloak blip); the browser reconnects and the poll keeps the badge fresh.

**A browser that leaves an open stream is not an error either (2026-09-25).** When the relay
completes an emitter whose client has already gone — a navigation, a closed tab, a window handed to
the login — Spring flushes a response that already failed, raises `AsyncRequestNotUsableException`
and dispatches it as the async result. The frontend `GlobalExceptionHandler.handleDisconnectedClient`
takes it (and Tomcat's `ClientAbortException`) at `DEBUG` with a `void` return, so nothing is written
into the dead response; before it existed the exception reached the `Exception` catch-all, which
logged `ERROR` and then made Tomcat log a second line when the error page could not be rendered
(33 lines in two minutes right after the v1.11.0 deploy). Those lines read `userId=anonymous`
because the async dispatch then ran without the request's MDC, not because the caller was; since
2026-09-25 the async dispatch carries the request's `correlationId`, `userId` and `orgUnitId`
([`observability.md`](observability.md) REQ-OBS-001).

**The relay never completes an emitter the container has already ended (2026-10-05).** The relay
writes from a Reactor-Netty thread, so a browser that disconnects can race it: Tomcat runs its
async error handling, and a `complete()` from the Netty thread afterwards throws
`IllegalStateException` ("A non-container (application) thread attempted to use the AsyncContext
after an error"). Thrown inside the subscriber, it went to Reactor's `onErrorDropped` and logged
`ERROR`. Now a write that fails with an `IOException` only cancels the backend subscription and
leaves the emitter to the container's error dispatch, as Spring's `ResponseBodyEmitter` contract
asks; every other completion from the Netty thread (upstream end, upstream error, a non-I/O write
failure) tolerates an already-ended request at `DEBUG`.

**A stream refused for a missing session stops reconnecting.** An anonymous `GET
/notifications/stream` meets the entry point like every background call: `401` + `X-Reauthenticate`,
never a login redirect (REQ-SEC-012; `Accept: text/event-stream` and `Sec-Fetch-Mode: cors` are both
background signals). An `EventSource` cannot read that status — it fires the same `error` as for a
network blip — so a tab whose session ended used to reconnect every 3–6 s for as long as it stayed
open, hidden tabs included. `notifications.js` now treats an `error` before `open` as a refused
connect: it probes the session once through the unread-count read, and a `401` there stops the stream
for the page (no open source, no pending reconnect) before the shared re-auth helper takes the window
to the login. Consecutive refusals double the jittered reconnect delay up to 24–48 s; the next `open`
resets it.

**The frontend relay commits its response on the request thread (ADR-0113).** Right after resolving
the bearer and before wiring the reactor `sseWebClient` subscription, the relay sends an immediate
initial SSE **comment** from the request thread. This is load-bearing, not decoration: the relay's
forwarded writes (`forward()`) run on a reactor-netty event-loop thread, and Spring Web 7 + Tomcat 11
do **not** commit an async `SseEmitter` response whose first write lands on a non-container thread
(spring-ai #6169) — so without the initial request-thread commit the status line + headers never
reach the browser/proxy and every stream header-times-out (the 2026-07-20 100%-dead-SSE incident,
best-effort so the poll masked it). Spring replays the pre-initialize send on the request (dispatch)
thread when it initializes the emitter, committing the response there; the comment is invisible to
`EventSource`, so it only flushes headers and the forwarded events (including the backend's own
`connected`) follow. Mirrors the backend's already-working request-thread first write
(`NotificationStreamService.subscribe()`).

**Registry consistency & bounds (#1109 Wave 6).** The per-`sub` emitter registry is a FIFO `Queue`
mutated atomically under the map entry's bin lock (`ConcurrentHashMap.compute`), so a new
subscription and an old stream completing concurrently for the same `sub` can no longer orphan a live
emitter in an unmapped set (silently dead for up to the 30-min timeout) — the same check-then-act
race class fixed on the frontend presence registry (#1157 / #1150). The registry caps streams per
`sub` (`MAX_EMITTERS_PER_SUB`); a subscription past the cap retires the OLDEST with a terminal named
`replaced` event the client treats as **do-not-reconnect**, so a user's many tabs / devices cannot
multiply against the org-wide `frontend-sse-pool` sized on one stream per viewer (#1156). And the
real-time push fires from the `AFTER_COMMIT` listener (`NotificationEventListener`), **after** the
notification-creation transaction commits — so the client's unread-count refetch reads committed rows
(not a pre-commit stale count) and the blocking SSE fan-out never pins the creation transaction's
Hikari connection (#1152).

**Acceptance**

- [x] A created notification pushes a `notification` SSE event to the recipient's live streams.
- [x] The push is best-effort: a failed send drops the emitter and the client falls back to
  polling.
- [x] The keepalive is a **named** `heartbeat` event (not a comment) so the client can observe it.
- [x] A half-open stream (connected but silent) demotes the poll to the fast cadence via the client
  liveness watchdog, without waiting for an `error`.
- [x] The 30-minute emitter timeout completes the emitter cleanly, so the stream is recorded as a
  normal completion and never as a phantom `503` on `http.server.requests`.
- [x] The per-`sub` emitter registry is mutated atomically (`compute`), so a concurrent
  subscribe/complete cannot strand a live emitter in an unmapped set (#1157).
- [x] Streams per `sub` are capped; the oldest over the cap is retired with a terminal `replaced`
  event and the client does not reconnect it (#1156).
- [x] The real-time push fires after the notification-creation transaction commits (post-commit
  listener), so the refetch reads committed rows and no DB connection is pinned across the SSE
  fan-out (#1152).
- [x] The frontend SSE relay uses a dedicated connection pool (`frontend-sse-pool`) sized well above
  the expected concurrent-viewer count, so many simultaneous viewers (200+) each keep their live push
  instead of the surplus blocking on the request pool's connection ceiling (ADR-0078).
- [x] A client that leaves an open stream logs no `ERROR` on the frontend and gets nothing written
  into the dead response.
- [x] An anonymous `EventSource` request to the stream answers `401` + `X-Reauthenticate`, never a
  login redirect, and logs no `ERROR`.
- [x] A connect refused before `open` probes the session; a `401` stops the stream for the page, and
  consecutive refusals back off.

**Enforced by:** `NotificationStreamServiceTest` (named `connected`/`heartbeat`/`notification`
events + clean timeout completion), `DisconnectedClientHandlingTest`,
`AnonymousSurfaceSweepMvcTest#anonymousEventSourceGets401`,
`NotificationStreamReconnectContractTest`, full build (bean wiring), frontend lint gate · **Code:**
`service/NotificationStreamService`, `service/NotificationFanout` / `RedisNotificationFanout` /
`LocalNotificationFanout`, `notification/internal/NotificationFanoutProperties`,
`controller/NotificationController#stream`, frontend
`controller/NotificationPageController#stream`, `service/BackendSideChannels#notificationStream`,
`config/WebClientConfig#sseWebClient`,
`exception/GlobalExceptionHandler#handleDisconnectedClient`, `static/js/notifications.js`

### REQ-NOTIF-011 — UC2/UC3: notify on the bank booking-request lifecycle

The bank booking-request lifecycle (REQ-BANK-026) is notified through the engine in two directions:

**UC2 — on creation (→ bank staff).** A `BANK_BOOKING_REQUEST_CREATED` event carries the target
**account id** (`NotificationEvent.contextAccountId()`) and is mapped by a seeded default rule
(V160) to a same-named notification with two selectors: a `ROLE` selector for `BANK_MANAGEMENT` and
an `ACCOUNT_GRANT` selector resolving every employee granted on that account. The `ACCOUNT_GRANT`
selector kind couples recipient resolution to `bank_account_grant` without any schema change — the
account comes from the event, mirroring how `ORG_RELATIVE_ROLE` reads the org unit.

**UC3 — on decision (→ the requester).** A `BANK_BOOKING_REQUEST_CONFIRMED` /
`BANK_BOOKING_REQUEST_REJECTED` event carries the **directed recipient**
(`NotificationEvent.contextRecipientUserId()` = the requesting officer/lead) and is mapped by seeded
default rules (V161) to same-named notifications, each with a single `EVENT_RECIPIENT` selector that
resolves to that recipient. The rejection reason is rendered in the text.

**Responsible holder (V194).** The account's derived responsible holder (REQ-BANK-034) is notified
too: an `ACCOUNT_RESPONSIBLE` selector joins the UC2 creation rule, and two further rules map the
confirm/reject events to the account-centric `BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED` /
`…_RESPONSIBLE_REJECTED` types, so one event raises a requester-directed and a holder-directed
notification (REQ-NOTIF-021's per-type signal).

In both use cases the triggering actor is excluded (`exclude_actor = TRUE`) and every rule stays
admin-editable at runtime.

**Acceptance**

- [x] Creating a booking request (after commit) notifies bank management + the account's grant
  holders, excluding the requester (`RuleEvaluationServiceTest`, `BankBookingRequestServiceTest`).
- [x] Confirming/rejecting a request (after commit) notifies the requesting officer/lead via the
  `EVENT_RECIPIENT` selector, excluding the deciding employee (`RuleEvaluationServiceTest`,
  `BankBookingRequestServiceTest`).
- [x] Adding the three `BANK_BOOKING_REQUEST_*` event/notification types and the `ACCOUNT_GRANT` /
  `EVENT_RECIPIENT` selector kinds needs no schema migration (open enums; the seed rules are V160 /
  V161 data).
- [x] The notifications render via `notifications.type.BANK_BOOKING_REQUEST_*` (i18n keys in all
  three bundles, named placeholders `{accountNo}`/`{amount}`/`{requester}`/`{reason}`).

**Enforced by:** `RuleEvaluationServiceTest`, `BankBookingRequestServiceTest` · **Code:**
`bank/api/events/BankBookingRequest{Created,Confirmed,Rejected}Event`,
`service/RecipientResolutionService#resolveAccountGrantHolders`,
`service/RuleEvaluationService#resolveEventRecipient`,
`bank/internal/OrgUnitBankResponsibilityService#resolveResponsibleHolderUserIds`,
`model/SelectorKind#{ACCOUNT_GRANT,EVENT_RECIPIENT,ACCOUNT_RESPONSIBLE}`,
`model/NotificationEventType`, `model/NotificationType`,
`db/migration/V160__seed_bank_booking_request_notification_rule.sql`,
`db/migration/V161__seed_bank_booking_request_decision_notification_rules.sql`,
`db/migration/V194__seed_bank_booking_request_responsible_holder_notifications.sql` · **Issues:** #666

### REQ-NOTIF-013 — Reusable, best-effort transactional e-mail channel

The backend has a channel-agnostic e-mail seam so system events can notify a user **by e-mail** in
addition to (or instead of) the in-app inbox. `MailService.send(MailMessage)` takes a domain-free
`MailMessage(to, subject, body)` — no notion of approval or notification — so any producer can reuse
it; its consumers are the account decision mail (REQ-NOTIF-014, [ADR-0064](../adr/0064-transactional-email-delivery-channel.md))
and the pending-registration admin mail (REQ-NOTIF-015, in [`discord-integration.md`](discord-integration.md)),
and the in-app rule engine may adopt it later as a generic second delivery channel.

Sending is **three-gated** and **best-effort**: the `SmtpMailService` implementation sends only when
`app.mail.enabled` is on (an explicit kill-switch that ships `true`), a non-blank `spring.mail.host`
is configured (the effective switch, unset outside prod), **and** a `JavaMailSender` bean exists
(Spring Boot autoconfigures it only when the host is set). Any gate closed makes `send` a logged
no-op — the explicit host check means an empty `SPRING_MAIL_HOST` env never fires a broken sender —
so dev/test/CI never contact SMTP. A delivery failure is caught
and logged, never rethrown, so mail can never fail or roll back the caller. Producers publish an
after-commit event handled by an `@Async(MAIL_EXECUTOR)` `@TransactionalEventListener(AFTER_COMMIT)`
so SMTP latency stays off the request thread and a rolled-back action sends nothing. Bodies are
localized via the backend `MessageSource`; the recipient address, name and any free-text are **never
logged** (REQ-OBS).

**Acceptance**

- [x] `MailService`/`MailMessage` carry no domain concept; `SmtpMailService` no-ops (with a log) when
  disabled, when `spring.mail.host` is blank, or when no `JavaMailSender` is configured, and swallows
  a send failure (`SmtpMailServiceTest`).
- [x] Mail composition/sending runs off-thread after commit on a dedicated `MAIL_EXECUTOR`, distinct
  from the notification executor, so a stalled relay cannot starve in-app notification creation.
- [x] Only the static localized subject is ever logged — never the address, name or reason.
- [ ] Operator: the channel ships enabled; prod sets `SPRING_MAIL_HOST` (+ port/credentials) to start
  sending. With no host it stays a no-op; `APP_MAIL_ENABLED=false` hard-disables it.

**Enforced by:** `SmtpMailServiceTest` · **Code:** `service/MailService`, `service/MailMessage`,
`service/SmtpMailService`, `config/MailProperties`, `config/AsyncConfig#MAIL_EXECUTOR`,
`application.yml` (`spring.mail.*` / `app.mail.*`) · **Decision:** ADR-0064 · **Issues:** #720

### REQ-NOTIF-016 — UC4: notify the Materialbörse offer owner on an interest registration

When a member registers interest in a Materialbörse offer, the offer owner (the Anbieter) is notified
through the engine (#1187). A `MATERIAL_EXCHANGE_INTEREST_REGISTERED` event carries the **directed
recipient** (`NotificationEvent.contextRecipientUserId()` = the offer owner) and is mapped by a seeded
default rule (V211) to a same-named notification with a single `EVENT_RECIPIENT` selector — the same
directed-recipient mechanism as the bank decision notifications (REQ-NOTIF-011, UC3). The registering
member is excluded (`exclude_actor = TRUE`; moot because a member can never register interest in their
own offer). The primary requirement, its anonymity reasoning, and the "new registration only /
after-commit" semantics live in [`materialboerse.md`](materialboerse.md) (REQ-MARKET-011); this entry
records the notification-engine consumer.

**Acceptance**

- [x] Registering interest (after commit) notifies the offer owner via the `EVENT_RECIPIENT` selector,
  excluding the registering member (`MaterialExchangeServiceTest`, `RuleEvaluationServiceTest`).
- [x] Adding the `MATERIAL_EXCHANGE_INTEREST_REGISTERED` event/notification types needs no schema
  migration (open enums; the seed rule is V211 data).
- [x] The notification renders via `notifications.type.MATERIAL_EXCHANGE_INTEREST_REGISTERED` (DE + EN
  + base bundles, named placeholders `{interessent}`/`{material}`).

**Enforced by:** `MaterialExchangeServiceTest`, `RuleEvaluationServiceTest`,
`MessageBundleConsistencyTest` · **Code:**
`materialexchange/api/events/MaterialExchangeInterestRegisteredEvent`,
`service/MaterialExchangeService#registerInterestInNewTransaction`, `model/NotificationEventType`,
`model/NotificationType`,
`db/migration/V211__seed_material_exchange_interest_notification_rule.sql` · **Issues:** #1187

### REQ-NOTIF-017 — UC5: notify the processing unit on a requester edit

When the requesting owner (Auftraggeber) edits one of their own job orders (REQ-ORDERS-023 — change
quantities, add/remove not-yet-delivered items or materials, edit the comment), the processing
(responsible) org unit's **officers and leads** are notified through the engine (#1186). A
`JOB_ORDER_UPDATED_BY_REQUESTER` event carries the responsible org unit as its `RESPONSIBLE` context
and is mapped by a seeded default rule (V214) to a same-named notification with two `ORG_RELATIVE_ROLE`
selectors (`OFFICER` + `LEAD`, both against `RESPONSIBLE`). Unlike the job-order-created rule (UC1,
V156) it deliberately omits the LOGISTICIAN and global-ADMIN recipients — the issue scopes it to
officers and leads — but stays admin-editable at runtime. The editing member is excluded
(`exclude_actor = TRUE`; moot because recipients resolve from the responsible unit while the actor is
in the requesting unit). The message names the requesting org unit (`{requester}` = its shorthand),
never the editing member's personal name (no PII in params).

**Acceptance**

- [x] A requester edit (after commit) notifies the responsible unit's officers + leads, excluding the
  actor (`JobOrderServiceTest`, `RuleEvaluationServiceTest`).
- [x] The new event/notification types need no schema migration (open enums; the seed rule is V214
  data).
- [x] The notification renders via `notifications.type.JOB_ORDER_UPDATED_BY_REQUESTER` (DE + EN + base
  bundles, named placeholders `{displayId}`/`{orgUnit}`/`{requester}`).

**Enforced by:** `JobOrderServiceTest`, `MessageBundleConsistencyTest` · **Code:**
`joborder/api/events/JobOrderUpdatedByRequesterEvent`, `service/JobOrderService#publishJobOrderUpdatedByRequester`,
`model/NotificationEventType`, `model/NotificationType`,
`db/migration/V214__seed_job_order_requester_update_notification_rule.sql` · **Issues:** #1186

### REQ-NOTIF-018 — Superseding: clear stale "action needed" notifications on lifecycle close

An event may declare, via `NotificationEvent.resolvesNotificationTypes()`, notification **types it
marks obsolete for its own entity**. When such an event is processed, the creation pipeline
(`NotificationCreationService`) — **before** creating any new notification — deletes every
outstanding notification of one of those types tagged with the event's `entity_type` + `entity_id`,
across **all** recipients, in one atomic statement. This lets a lifecycle-terminating event clear the
now-stale "action needed" items an earlier event in the same lifecycle produced. The removal runs
**regardless of whether the event itself resolves any recipients**, so a purely-terminating event
(one that notifies nobody) still clears the stale items. The removed-notification holders are unioned
into the recipient set the after-commit listener pushes to, so their unread badge and open bell
dropdown refresh **live** (REQ-NOTIF-010) the moment the item is cleared — the in-app poll
(REQ-NOTIF-006) remains the guaranteed fallback. Removal and creation touch disjoint rows (different
type, different recipients), so no notification is created and immediately deleted.

**First wired use case:** the bank booking-request lifecycle (REQ-BANK-026, REQ-NOTIF-011). The three
lifecycle-terminating events — `BANK_BOOKING_REQUEST_CONFIRMED`, `BANK_BOOKING_REQUEST_REJECTED`
(both decided by a bank employee) and the new `BANK_BOOKING_REQUEST_CANCELLED` (the requester
withdraws their own still-pending request) — each resolve `BANK_BOOKING_REQUEST_CREATED`, so once a
request is decided or withdrawn the "new booking request" items shown to the bank management + the
account's grant holders disappear from their inboxes. `BANK_BOOKING_REQUEST_CANCELLED` notifies
nobody (the requester is the actor and seeds no rule); its sole pipeline effect is the removal.

Since #2413 the open-request notices are `BANK_BOOKING_REQUEST_CREATED` **and**
`BANK_BOOKING_REQUEST_UPDATED` (`BankBookingRequestEvent.OPEN_REQUEST_NOTICES`): a requester's
correction (REQ-BANK-056) clears both and raises `BANK_BOOKING_REQUEST_UPDATED` for the same
recipients, and confirm / reject / cancel clear both.

**Second wired use case:** the Art. 17 deletion request (REQ-SEC-061).
`ACCOUNT_DELETION_REQUEST_DECLINED` and the notify-nobody `ACCOUNT_DELETION_REQUEST_RESOLVED` each
resolve `ACCOUNT_DELETION_REQUESTED`, so the admins' "erasure requested" items disappear once the
request is closed.

**Third wired use case:** the pending registration (REQ-NOTIF-012). The notify-nobody
`DISCORD_REGISTRATION_DECIDED` resolves `DISCORD_REGISTRATION_PENDING`; it is published when an admin
approves or rejects the registration (`UserRegistrationService#decide`) and when a still-pending
registration is deleted (`UserDeletionService#deleteUser`, which also covers linking it onto an
existing account). Merging an older account into a pending registration decides nothing — the
registration stays pending — and clears nothing.

**Fourth wired use case:** the job order (REQ-NOTIF-008, REQ-NOTIF-017). The notify-nobody
`JOB_ORDER_CLOSED` resolves `JOB_ORDER_CREATED` and `JOB_ORDER_UPDATED_BY_REQUESTER`; it is published
when an order becomes `COMPLETED` or `REJECTED` (by status change or by the last handover) and when it
is deleted, so no inbox keeps an order that is settled or a link that leads nowhere.

**Acceptance**

- [x] A confirm / reject / cancel of a booking request (after commit) deletes the
  `BANK_BOOKING_REQUEST_CREATED` and `BANK_BOOKING_REQUEST_UPDATED` notifications for that request
  across all recipients, and only those (other types and other entities are untouched).
- [x] One admin's decision on a registration, or its deletion while pending, clears every admin's
  `DISCORD_REGISTRATION_PENDING` item for it.
- [x] Completing, rejecting or deleting a job order clears its `JOB_ORDER_CREATED` and
  `JOB_ORDER_UPDATED_BY_REQUESTER` items; a move between two closed states or back to open clears
  nothing.
- [x] The affected staff are included in the pushed recipient set so their badge/dropdown refresh
  live; the removal runs even when the event resolves no new recipients (cancel).
- [x] Adding the `BANK_BOOKING_REQUEST_CANCELLED` event type and the `resolvesNotificationTypes()`
  hook needs no schema migration (open enum; behaviour is code + event-driven).

**Enforced by:** `NotificationCreationServiceTest`, `NotificationRepositoryIntegrationTest`,
`BankBookingRequestServiceTest` · **Code:** `notification/api/events/NotificationEvent#resolvesNotificationTypes`,
`bank/api/events/BankBookingRequest{Confirmed,Rejected,Cancelled}Event`,
`service/NotificationCreationService#removeSupersededNotifications`,
`repository/NotificationRepository#{findRecipientUserIdsByTypeInAndEntity,deleteByTypeInAndEntity}`,
`bank/internal/BankBookingRequestService#cancelOwn`, `privacy/api/events/AccountDeletionRequest{Declined,Resolved}Event`,
`identity/api/events/DiscordRegistrationDecidedEvent`, `joborder/api/events/JobOrderClosedEvent`,
`model/NotificationEventType` · **Also enforced by:** `NotificationLifecycleEventsTest`,
`NotificationRuleEngineIntegrationTest`, `UserRegistrationServiceTest`, `UserDeletionServiceTest`,
`JobOrderServicePriorityAndStatusTest` · **Decision:**
[ADR-0096](../adr/0096-notification-supersede-on-lifecycle-close.md) · **Issues:** #1252, #2413

### REQ-NOTIF-019 — The inbox page shows its full history (hint + load-more), never a silent cap

The `/notifications` page renders the newest **50** notifications. That cap MUST NOT be silent: an
inbox holding more than one page MUST show a truthful "showing the latest N of M" hint and a
**load-more** control that appends the next server page in place, so the older tail stays reachable
— rather than presenting the latest 50 as if they were the whole inbox (the ADR-0100
silent-truncation defect class, on the notifications surface). The bell dropdown keeps its lighter
latest-10 `/recent` view unchanged; this requirement governs the full page only.

The page and the load-more relay read the caller's own notifications from the already-paginated
backend listing (`GET /api/v1/notifications`, `NotificationService#listOwn`, sub-scoped per
REQ-NOTIF-004, `createdAt,desc`) — no new backend endpoint. The sort carries a stable **`id`
tiebreaker** (`NotificationService.SORTABLE_FIELDS` includes `id`, appended by `PaginationUtil`),
so notifications sharing a `createdAt` instant keep a deterministic total order across page fetches
and the boundary between page *n* and *n+1* never silently drops a tied row. The initial render
carries the total count and a more-pages flag; the load-more control fetches page *n* via a
header-gated relay (`GET /notifications/page-items`) returning the same server-localized
`NotificationViewDto`s as the initial render (identical text + relative time), appends only rows
not already in the DOM (a notification arriving since page 0 pushes rows down, so an offset fetch
may re-return an already-shown row — that duplicate is skipped), keeps the hint truthful after each
append, and removes itself once the last page is loaded. A load-more failure leaves the control
usable for a retry. The existing mark-read / delete / mark-all / clear-read handlers
(REQ-NOTIF-005) are event-delegated, so they drive appended rows too.

**Acceptance**

- [ ] With more than 50 notifications, the page renders the "latest N of M" hint and a load-more
  control; with ≤ 50 it renders neither.
- [ ] Load-more appends the next page's localized rows in place (no full reload), updates the hint,
  and de-duplicates against rows already present.
- [ ] The control disappears once the final page has been appended; a relay failure keeps it
  clickable for a retry.
- [ ] Appended rows honour mark-read / delete without a reload (delegated handlers), and the
  unread badge stays server-sourced (REQ-NOTIF-006).
- [ ] The inbox-list sort resolves to a total order (`createdAt,desc` + `id` tiebreaker), so a
  page boundary never silently drops a notification that shares a `createdAt` instant with another.

The delete-shift edge inherent to offset pagination (deleting an already-shown row pushes an
unseen row above the current offset, so the next fetch skips it) is **out of scope** — it needs
keyset/cursor pagination, affects every offset-paginated list in the app equally, and a manual
reload recovers it. This requirement bounds only the *silent-at-the-cap* and *tie-instability*
truncation, not that inherent-to-offset edge.

**Enforced by:** `NotificationPageControllerTest` (page total + has-more flags; `/page-items`
slice), `NotificationServiceTest` (sort whitelist yields the stable `id` tiebreaker),
`NotificationPageRenderMvcTest` (hint + load-more render only past one page),
`MessageBundleConsistencyTest` (`notifications.loadMore` / `notifications.showingLatest` in every
bundle) · **Code:** `frontend controller/NotificationPageController#page` / `#pageItems` /
`#loadPage`, `model/dto/NotificationPageSliceDto`, `backend service/NotificationService`
(`SORTABLE_FIELDS` with `id`), `templates/notifications.html`, `static/js/notifications.js`
(`loadMorePage` / `updatePageHint`) · **Issues:** — (ADR-0100 silent-truncation audit follow-up)

### REQ-NOTIF-022 — The inbox page filters unread/all and links each notification to its subject

The `/notifications` page follows the list pattern of REQ-UI-027. A segmented control „Ungelesen ·
Alle" (`name=filter`, default „Ungelesen") hides read rows client-side — `data-notif-filter` on
`#notification-inbox` and a CSS rule, so a row marked read, deleted or appended by load-more
(REQ-NOTIF-019) obeys it with no extra code. The choice persists per browser
(`notifications_filter`, REQ-UI-017). When rows are loaded but none is unread, an empty state says so
(„Alle geladenen Benachrichtigungen sind gelesen"); the load-more control stays, because older pages
may still hold unread rows.

Every row whose subject has a page is a link to it. `NotificationViewDto` carries an `href`, set by
`NotificationPageController.targetOf(type, entityType, entityId)`:

| Notification types | Link |
| --- | --- |
| `JOB_ORDER_CREATED`, `JOB_ORDER_UPDATED_BY_REQUESTER` | `/orders/{id}` |
| `MATERIAL_EXCHANGE_INTEREST_REGISTERED`, `MATERIAL_REQUEST_FULFILLMENT_SIGNALLED` | `/materialboerse` |
| `EXCHANGE_INSTALLATION_CONNECTED`, `EXCHANGE_BULK_UNDO_APPLIED` | `/connected-apps` |
| `INVENTORY_TRANSFERRED_TO_USER`, `INVENTORY_TRANSFERRED_FROM_USER` | `/inventory/my` |
| `ACCOUNT_DELETION_REQUEST_DECLINED` | `/profile` |
| `ACCOUNT_DELETION_REQUESTED` | `/admin/deletion-requests` |
| `DISCORD_REGISTRATION_PENDING` | `/admin/discord-registrations` |
| bank booking types | none — a recipient may lack access to the request queue |

The link passes `safeSameOriginUrl` before it is rendered; following it does not mark the row read.
„Alle als gelesen markieren" is a ghost action in the page head, „Gelesene löschen" a danger entry of
the overflow menu. The bell dropdown keeps its rows without links.

**Enforced by:** `NotificationPageRenderMvcTest`, `NotificationPageControllerTest` (`targetOf`,
`href` on `/page-items`) · `NotificationCenterE2eTest` · **Code:** `notifications.html`,
`static/js/notifications-page.js`, `static/js/notifications.js` (`buildItem(item, linkable)`),
`NotificationPageController`, `NotificationViewDto` · **Related:** REQ-NOTIF-019, REQ-UI-027

### REQ-NOTIF-023 — Reconciling a notice for named members

Some changes do not create a new subject; they change **who should hold** an existing notice — the
responsible holders of a bank account change while requests on it are open (REQ-BANK-034). Clearing
the notice for everyone and raising it again would re-notify members whose standing did not change,
including any who deleted the notice on purpose. Superseding (REQ-NOTIF-018) cannot narrow to
individual recipients.

An event may therefore name the members to reconcile, via
`NotificationEvent.reconcileRecipients()`. When the set is non-empty, `NotificationCreationService`
processes the event in reconcile mode instead of the normal one:

- The rules of the event's `eventType` are evaluated as usual, giving the members **entitled** to the
  notice now (including the actor exclusion).
- A named member who is entitled and holds **no** notification of `resolvesNotificationTypes()` for
  the event's entity gets one, of the type the rule produces.
- A named member who holds such a notification and is **no longer** entitled loses it
  (`NotificationRepository#deleteByTypeInAndEntityForRecipients`).
- Everyone else — every member the event does not name — is neither notified nor cleared.
- Both kinds of change are pushed live (REQ-NOTIF-010): new rows with their signal, cleared ones as a
  refresh-only signal.

**Wired use case:** `BankBookingRequestNoticesReconciledEvent`, published per open request when an
account's responsible holders change, names the members who became or stopped being holders and is
evaluated against the `BANK_BOOKING_REQUEST_CREATED` rules: the new holder gets the request's notice,
a former holder loses it unless another selector (bank management, an account grant) still reaches
them, and the bank staff keep theirs untouched.

**Acceptance**

- [x] A former holder loses the open request's notice and a new holder receives one; a staff member
  holding the notice is left alone.
- [x] A former holder whom another selector still reaches keeps the notice.
- [x] An entitled member the event does not name is never notified, even without a notice.
- [x] The per-recipient deletion touches only the named recipients' rows of the given types and
  entity.

**Enforced by:** `NotificationCreationServiceTest` (`reconcile…`),
`NotificationRepositoryIntegrationTest` (`supersedeForRecipients…`),
`NotificationLifecycleEventsTest`, `OrgUnitBankResponsibilityServiceTest` · **Code:**
`notification/api/events/NotificationEvent#reconcileRecipients`,
`service/NotificationCreationService#reconcile`,
`repository/NotificationRepository#deleteByTypeInAndEntityForRecipients`,
`bank/api/events/BankBookingRequestNoticesReconciledEvent` · **Decision:**
[ADR-0244](../adr/0244-a-notification-event-can-reconcile-a-notice-for-named-members.md) ·
**Issues:** #2413

### REQ-NOTIF-024 — Group recipients and unit leadership

An event-derived rule can reach a **group** the event's entity defines, not only one person. Four
selector kinds read no selector column; the event carries what they need and each is offered in
the rule editor like the others:

| Kind | Resolves to | The event carries |
| --- | --- | --- |
| `MISSION_PARTICIPANTS` | the registered participants of the mission — guests have no inbox and are left out; only those not checked in when the event says so | `contextMissionId()`, `contextMissionOnlyNotCheckedIn()` |
| `MISSION_LEADERSHIP` | the mission's owner and its co-managers | `contextMissionId()` |
| `EXCHANGE_CLIENT_HOLDERS` | every member with a non-revoked installation of the client; of any client when the event is exchange-wide | `contextExchangeClientId()` or `contextAllExchangeClients()` |
| `EVENT_RECIPIENTS` | the set of affected users the event lists (bulk actions) | `contextRecipientUserIds()` |

`OrgRelativeRole.UNIT_LEADERSHIP` resolves to the members whose rank confers oversight of the context
org unit (`MembershipRole#confersOwnLevelOversight()`: Staffelleiter, Bereichsleiter, …), whether or not
they hold the global `OFFICER` role.

An event that carries nothing a kind needs resolves to nobody for that kind. The plain
`excludeActor` rule and the de-duplication apply to the new kinds exactly as to the old ones, and
every existing rule keeps working unchanged. The notification module reaches the mission and exchange
data only through its SPIs `MissionRecipientDirectory` and `ExchangeRecipientDirectory`.

**Acceptance**

- [x] Each new kind resolves through the event's context and drops the actor when the rule says so.
- [x] A mission or exchange event without its context resolves to nobody.
- [x] `UNIT_LEADERSHIP` reaches every seat holder of the unit and no plain member.
- [x] The rule editor offers every new kind and the new role, under a localized label, and stores a
  group kind with every selector column `null`.

**Enforced by:** `RuleEvaluationServiceTest`, `RecipientDirectoriesTest`,
`NotificationRecipientQueriesIntegrationTest`, `NotificationRuleServiceTest`,
`AdminNotificationRuleOptionListsTest` · **Code:** `model/SelectorKind`, `model/OrgRelativeRole`,
`notification/api/events/NotificationEvent`, `notification/api/MissionRecipientDirectory`,
`notification/api/ExchangeRecipientDirectory`, `service/RuleEvaluationService`,
`service/RecipientResolutionService` · **Decision:**
[ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md) ·
**Issues:** #2414

### REQ-NOTIF-025 — Supersede for named recipients

Superseding (REQ-NOTIF-018) deletes the notices of its types for **everyone**. Some notices must
disappear for one member only — the „check-in open" notice of the participant who checked in, the
„added to a mission" notice of the participant who was removed again.

An event may therefore name the notice types and the members: `resolvesNotificationTypesForRecipients()`
and `supersedeRecipients()`. When both are non-empty the engine deletes the notices of those types
about the event's entity for those members only (`NotificationRepository#deleteByTypeInAndEntityForRecipients`),
before it creates anything, and pushes a refresh-only signal to them. Every other member's notice
stays. With either set empty nothing is deleted. An event may combine this with the per-entity
`resolvesNotificationTypes()`.

**Acceptance**

- [x] Only the named members' notices of the named types are deleted; a notice of another member
  survives.
- [x] A named member who holds no such notice causes no delete and no push.
- [x] The per-entity and the per-recipient supersede can run in one event.

**Enforced by:** `NotificationCreationServiceTest` (`perRecipientSupersede…`) · **Code:**
`notification/api/events/NotificationEvent#supersedeRecipients`,
`service/NotificationCreationService#removeForRecipients` · **Decision:**
[ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md) ·
**Issues:** #2414

### REQ-NOTIF-026 — Time-based notices

Some notices have no user action behind them: a reminder before a mission, a refinery order that is
ready, a mission that was never ended. They come from **one scheduled producer**.

- Every module that owns such a time implements `TimedNoticeProducer` (`kind()`, `produce(now)`).
  `NotificationTimedTask` runs them every `app.notifications.timed.interval` (default one minute,
  `app.notifications.timed.enabled`), through `NotificationTimedRunner`.
- **At most once.** A producer sets the entity's „already notified" marker and publishes the event in
  the **same** transaction, so a notice cannot fire twice and is delivered after the commit like every
  other (REQ-NOTIF-002). Editing the underlying time resets the marker.
- **One instance produces.** The run takes the transaction-scoped Postgres advisory lock
  `pg_try_advisory_xact_lock`; an instance that does not get it skips the run. Each producer runs in
  its own transaction, so one failing producer neither blocks the others nor rolls back what they
  marked; the first failure is rethrown after all have run, so the job is recorded as failed.
- **Observable.** The run is the `notification_timed` scheduled job (executions, duration,
  last-success, enabled, items = notices raised) and each producer counts its notices in
  `basetool_notification_timed_produced_total{kind}`. `NotificationTimedStale` fires when the job has
  not succeeded for 15 minutes (REQ-OBS-011).

**Acceptance**

- [x] Every registered producer runs once per run and the notices it raised are counted by kind.
- [x] An instance that cannot take the advisory lock produces nothing; once the lock is free the next
  run produces.
- [x] A failing producer does not stop the others, and the run is recorded as a failure.
- [x] The task records the notices raised as the job's item count, survives a failure and publishes
  its enabled gauge.

**Enforced by:** `NotificationTimedRunnerTest`, `NotificationTimedRunnerIntegrationTest`,
`NotificationTimedTaskTest`, `notification_timed_stale_test.yml` · **Code:**
`notification/api/TimedNoticeProducer`, `service/NotificationTimedRunner`,
`task/NotificationTimedTask`, `notification/internal/NotificationTimedProperties`,
`repository/NotificationRepository#tryTimedProducerLock` · **Decision:**
[ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md) ·
**Issues:** #2414

### REQ-NOTIF-027 — Members mute notification types

A member MUST be able to choose which notification types they receive. Muting a type is the
member's own decision; it changes nothing for anybody else and nothing about what the rules resolve.

- **Stored.** A `notification_mute` row (member, notification type; unique; `ON DELETE CASCADE` on
  the member) is the mute. Present = muted.
- **Applied before anything is written.** `NotificationCreationService` removes a member who muted
  the type from the recipients the rules resolved (`NotificationMuteService#withoutMuted`), in the
  normal and in the reconcile path. A muted type is therefore **neither stored in the inbox nor
  pushed**, so the Android app raises no OS notification for it. Notices already stored stay.
- **Not mutable.** `NotificationType#isMutable()` is `false` for `ACCOUNT_DELETION_REQUESTED` and
  `ACCOUNT_DELETION_REQUEST_DECLINED` (legal deadlines, REQ-SEC-061) and
  `EXCHANGE_INSTALLATION_CONNECTED` (the phishing signal, REQ-XCH-032). Such a type is never
  filtered, whatever rows exist, and muting it is refused. The method is an exhaustive switch, so a
  new type fails the build until its mutability is decided.
- **API.** `GET /api/v1/notifications/preferences` lists every type with `mutable` and `muted`;
  `PUT /api/v1/notifications/preferences/{type}` with `{muted}` stores the choice. Both are
  `isAuthenticated()` and keyed on the caller; the write is idempotent and carries no version (a
  boolean toggle, last writer wins) and answers `400` for a type that cannot be muted. Both
  operations are in the frozen contract set as `T1` (REQ-API-009, REQ-API-016) and admitted by the API
  vhost, because the Android app calls them; from the first released app build on they can no longer
  change incompatibly.
- **Web.** The profile page has the card „Benachrichtigungen": one checkbox per type, grouped by the
  area the type name's prefix names, a locked row for a non-mutable type, saved per click through
  `krtFetch` without a reload; a failed write re-renders the card from the server (REQ-FE-001). The
  Android app offers the same list in Einstellungen.
- **Privacy.** The Art. 15 export has the section `notificationMutes`; the account merge moves the
  rows and deduplicates on the type.
- **Observable.** `basetool_notification_muted_total{notification_type}` counts the recipients
  dropped.

**Acceptance**

- [x] A muted member gets no notification of the muted type, an unmuted one does, and a member who
  muted nothing is unaffected.
- [x] A non-mutable type is delivered even with a stray mute row, and muting it is refused.
- [x] Muting twice changes nothing; unmuting removes the row.
- [x] One member's mutes never show in or affect another's preferences.
- [x] Every notification type has an area and a label in all three web bundles.

**Enforced by:** `NotificationMuteServiceTest`, `NotificationPreferencesControllerTest`,
`NotificationCreationServiceTest`, `NotificationPreferenceGroupsTest`,
`NotificationPreferenceWriteControllerTest`, `NotificationPreferenceProxyControllerTest`,
`ProfileControllerMvcTest`, `GdprParticipantCoverageTest`, `UserAccountMergeCoverageTest` ·
**Code:** `model/NotificationMute`, `model/NotificationType#isMutable`,
`service/NotificationMuteService`, `controller/NotificationController#preferences`,
`V271__create_notification_mute.sql`, frontend `NotificationPreferenceWriteController`,
`NotificationPreferenceProxyController`, `fragments/profile-notification-prefs.html`,
`static/js/profile-notification-prefs.js` · **Decision:**
[ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md) ·
**Issues:** #2414

### REQ-NOTIF-028 — Coded render parameters

A notification stores no rendered text, so a word that differs by language — „verschoben" or
„abgesagt", „Auszahlung" or „Spende" — cannot be a parameter value. A render parameter whose name
ends in `Code` therefore gives the placeholder of the same name without the suffix its localized
word: with `changeCode = cancelled`, `{change}` renders as `notifications.value.change.cancelled`
in the member's language; a code without a word renders as itself. The type's own template names
the placeholder (`{mission}: {change} durch {actor}`). A parameter named just `Code` is an ordinary
parameter. Every client applies the rule when it fills a template: the web inbox and bell
(`NotificationPageController#render`) and the Android app (`NotificationText`).

**Acceptance**

- [x] A `…Code` parameter renders as the word of the member's language and as itself without one.
- [x] A parameter named `code` is not special.

**Enforced by:** `NotificationRenderCodesTest` · **Code:**
`NotificationPageController#render` · **Issues:** #2414

## Out of scope (v1)

- Per-notification e-mail routing (generic fan-out of in-app notification types to e-mail), per-channel
  preferences (a member can only mute a whole notification type, REQ-NOTIF-027), and digest emails. A **basic transactional e-mail transport** now
  exists (REQ-NOTIF-013, used so far by two hand-wired consumers — the account decision mail
  REQ-NOTIF-014 and the pending-registration admin mail REQ-NOTIF-015); wiring it into the rule
  engine per notification type is deferred.
- Discord channel delivery.
- Browser (Web Push) and push-service (FCM) notifications. The Android app raises its own OS
  notifications from its authenticated SSE stream (REQ-NOTIF-021); no push service is involved.
- A dedicated user-group entity (the `GROUP` selector kind is reserved for it).
