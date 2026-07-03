# Notifications & alerting

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-16.
> **Owner area:** NOTIF · **Related ADRs:** [ADR-0014](../adr/0014-notification-system-architecture.md),
> [ADR-0015](../adr/0015-notification-data-driven-rule-engine.md),
> [ADR-0016](../adr/0016-notification-transport-polling-sse.md) · **Epic:**
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

---

### REQ-NOTIF-001 — Generic per-user notification inbox

A `notification` row is a single message addressed to exactly one recipient (`recipient_sub`,
the Keycloak `sub` = `app_user.id`). It carries a machine `type` (`@Enumerated(STRING)`, no
CHECK — the set grows), a JSON `params` map (plain `TEXT`, never queried) for i18n rendering, a
loose `entity_type` + `entity_id` back-reference (no FK, survives source deletion), and
per-user read state (`is_read` / `read_at`). Text is **never** stored in a language — the
frontend renders `type` + `params` via `notifications.type.*` messages.

**Acceptance**

- [x] A notification stores `type` + `params` + loose entity reference, not a rendered string.
- [x] The schema validates against the entity under `ddl-auto = validate` (V155).

**Enforced by:** `NotificationRepositoryIntegrationTest`, `NotificationParamsCodecTest` ·
**Code:** `model/Notification`, `model/NotificationType`, `service/NotificationParamsCodec`,
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
`JobOrderServiceTest` · **Code:** `event/NotificationEvent`, `event/NotificationEventListener`,
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
(`SquadronContextAdvice#unreadNotificationCount`, fail-soft to 0) and kept fresh by a
client-side poll and after every mutation, always sourced from the server count (so it cannot
go stale). This is **in-app only** — OS / browser push notifications are out of scope.

**Acceptance**

- [x] The bell + unread badge render on every authenticated page.
- [x] The badge reflects the server unread count after mark-read / delete / mark-all /
  clear-read without a full reload.

**Enforced by:** `MessageBundleConsistencyTest`, frontend lint gate · **Code:**
`fragments/sidebar.html`, `static/js/notifications.js`, frontend `config/SquadronContextAdvice`

### REQ-NOTIF-007 — Data-driven recipient rule engine

Recipients are decided by admin-managed `notification_rule` rows, each owning a set of
`notification_rule_selector` rows. Selector kinds: `SPECIFIC_USER` (a `sub`), `ROLE` (a global
`role.code`), `ORG_RELATIVE_ROLE` (a role — `OFFICER` / `LEAD` / `LOGISTICIAN` /
`MISSION_MANAGER` — evaluated against an org unit the event carries, by `context_role`
`RESPONSIBLE` / `REQUESTING`), `ACCOUNT_GRANT` (the bank employees holding a
`bank_account_grant` on the **bank account** the event carries — see `NotificationEvent.contextAccountId()`),
and `EVENT_RECIPIENT` (the single user the event is **directed at** — see
`NotificationEvent.contextRecipientSub()`, e.g. the officer/lead notified when their booking request is
decided). The last two were added for the bank booking-request use case (ADR-0022/REQ-NOTIF-011) and read
no selector columns — the account / recipient comes from the event.
A rule's `exclude_actor` flag drops the triggering user. The selector `kind` is an open enum so a
future `GROUP` selector slots in without reworking the engine. Rules are created, edited, enabled /
disabled and deleted at runtime via an admin-only API.

**Acceptance**

- [x] `notification_rule` + `notification_rule_selector` exist (V156) with `ON DELETE CASCADE`.
- [x] Admin CRUD at `/api/v1/notification-rules` is gated on `hasRole('ADMIN')`.
- [x] The engine unions a rule's selectors, applies `exclude_actor`, and de-duplicates
  recipients.

Admins manage rules through a dedicated admin page (list + create/edit form with a dynamic
selector editor) that relays to the rule API.

**Enforced by:** `RuleEvaluationServiceTest`, `NotificationRuleEngineIntegrationTest` ·
**Code:** `model/NotificationRule`, `model/NotificationRuleSelector`,
`service/RuleEvaluationService`, `service/NotificationRuleService`,
`controller/NotificationRuleController`, `db/migration/V156__create_notification_rule.sql`,
frontend `controller/AdminNotificationRulePageController`,
`templates/admin/notification-rules.html`, `static/js/notification-rules.js`

### REQ-NOTIF-008 — UC1: notify on job-order creation

When a job order is created, the seeded default rule resolves recipients from the **responsible
org unit**: officers (global `OFFICER` role ∩ membership of that unit), leads
(`org_unit_membership.is_lead`), logisticians (`org_unit_membership.is_logistician`), plus the
global admins (`ROLE` `ADMIN`). The creating actor is excluded. The seeded rule is
admin-editable and -deletable. Officer-ness is a Keycloak role mirrored into `user_roles`, so a
freshly-promoted-but-not-yet-logged-in officer becomes a recipient only after the next
`UserSyncTask` run (≤ 5 min) — an accepted eventual-consistency window.

**Acceptance**

- [x] Creating a job order publishes `JobOrderCreatedEvent` after commit.
- [x] The seeded rule (V156, id `62200000-0000-0000-0000-000000000001`) has the four UC1
  selectors and `exclude_actor = true`.

**Enforced by:** `RuleEvaluationServiceTest`, `NotificationRuleEngineIntegrationTest`,
`JobOrderServiceTest` · **Code:** `service/JobOrderService#publishJobOrderCreated`,
`event/JobOrderCreatedEvent`, `service/RecipientResolutionService`

### REQ-NOTIF-009 — Retention

A scheduled sweep deletes **read** notifications older than the configured max age (default
90 days), gated by `app.notifications.retention.enabled` and paced by
`app.notifications.retention.interval`. Disabled under the `test` profile. The sweep is
independent of the user-initiated delete (REQ-NOTIF-005).

**Acceptance**

- [x] Read notifications past `max-age` are removed by the sweep; unread are kept.
- [x] The sweep never tears down the scheduler thread on failure.

**Enforced by:** `NotificationRetentionTaskTest`, `NotificationRepositoryIntegrationTest`
(`deleteReadOlderThan`) · **Code:** `task/NotificationRetentionTask`,
`service/NotificationService#purgeReadOlderThan`

### REQ-NOTIF-010 — Real-time push (SSE)

Beyond the in-app polling baseline (REQ-NOTIF-006), real-time server push uses Server-Sent
Events: a backend in-memory emitter registry keyed by `sub` (`NotificationStreamService`) with a
heartbeat, exposed at `GET /api/v1/notifications/stream`; the frontend relays it to the browser
via a resilience-free streaming WebClient (`WebClientConfig#sseWebClient`) and an `EventSource`.
On a `notification` event the client refreshes its unread state immediately. Push is
**best-effort** — the polling of REQ-NOTIF-006 is the guaranteed fallback, and a failed push or
broken stream never affects correctness. The unread-count poll adapts to stream health: while the
`EventSource` is connected it backs off to a slow keepalive (≈5 min) and speeds back up (≈1 min)
the moment the stream drops, so a healthy SSE session avoids redundant count polls. The slow
cadence is deliberately frequent enough to remain the REQ-SEC-012 re-auth safety net — the poll
path (not the refresh-incapable SSE relay) is what drives frontend token refresh and 401 re-login
detection. To keep that window bounded even when a stream silently dies, the backend emits a
periodic **named** `heartbeat` event (not an SSE comment, which browsers' `EventSource` swallow)
and the client runs a liveness watchdog: if no SSE traffic (`heartbeat`/`notification`) arrives
within ~3× the heartbeat interval, the stream is treated as **half-open** (still "connected" but
dead, so it never fires `error`) and the poll falls back to the fast cadence without waiting for an
`error`; a later event re-promotes it. The registry is single-backend-instance; multi-instance
fan-out via Redis pub/sub remains a follow-up.

**Acceptance**

- [x] A created notification pushes a `notification` SSE event to the recipient's live streams.
- [x] The push is best-effort: a failed send drops the emitter and the client falls back to
  polling.
- [x] The keepalive is a **named** `heartbeat` event (not a comment) so the client can observe it.
- [x] A half-open stream (connected but silent) demotes the poll to the fast cadence via the client
  liveness watchdog, without waiting for an `error`.

**Enforced by:** `NotificationStreamServiceTest` (named `connected`/`heartbeat`/`notification`
events), full build (bean wiring), frontend lint gate · **Code:**
`service/NotificationStreamService`, `controller/NotificationController#stream`, frontend
`controller/NotificationPageController#stream`, `config/WebClientConfig#sseWebClient`,
`static/js/notifications.js`

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
(`NotificationEvent.contextRecipientSub()` = the requesting officer/lead) and is mapped by seeded
default rules (V161) to same-named notifications, each with a single `EVENT_RECIPIENT` selector that
resolves to that recipient. The rejection reason is rendered in the text.

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
`event/BankBookingRequest{Created,Confirmed,Rejected}Event`,
`service/RecipientResolutionService#resolveAccountGrantHolders`,
`service/RuleEvaluationService#resolveEventRecipient`, `model/SelectorKind#{ACCOUNT_GRANT,EVENT_RECIPIENT}`,
`model/NotificationEventType`, `model/NotificationType`,
`db/migration/V160__seed_bank_booking_request_notification_rule.sql`,
`db/migration/V161__seed_bank_booking_request_decision_notification_rules.sql` · **Issues:** #666

### REQ-NOTIF-013 — Reusable, best-effort transactional e-mail channel

The backend has a channel-agnostic e-mail seam so system events can notify a user **by e-mail** in
addition to (or instead of) the in-app inbox. `MailService.send(MailMessage)` takes a domain-free
`MailMessage(to, subject, body)` — no notion of approval or notification — so any producer can reuse
it; the first consumer is the account decision mail (REQ-NOTIF-014, [ADR-0064](../adr/0064-transactional-email-delivery-channel.md)),
and the in-app rule engine may adopt it later as a second delivery channel.

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

## Out of scope (v1)

- Per-notification e-mail routing (fan-out of in-app notification types to e-mail), user channel
  preferences/opt-in, and digest emails. A **basic transactional e-mail transport** now exists
  (REQ-NOTIF-013, first used by the account decision mail REQ-NOTIF-014); wiring it into the rule
  engine per notification type is deferred.
- Discord channel delivery.
- OS / browser push notifications.
- Multi-backend-instance push fan-out (Redis pub/sub).
- A dedicated user-group entity (the `GROUP` selector kind is reserved for it).

