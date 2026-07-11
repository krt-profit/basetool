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
path (not the refresh-incapable SSE relay) is what drives frontend token refresh and 401 re-login
detection. To keep that window bounded even when a stream silently dies, the backend emits a
periodic **named** `heartbeat` event (not an SSE comment, which browsers' `EventSource` swallow)
and the client runs a liveness watchdog: if no SSE traffic (`heartbeat`/`notification`) arrives
within ~3× the heartbeat interval, the stream is treated as **half-open** (still "connected" but
dead, so it never fires `error`) and the poll falls back to the fast cadence without waiting for an
`error`; a later event re-promotes it. The registry is single-backend-instance; multi-instance
fan-out via Redis pub/sub remains a follow-up. When the 30-minute emitter timeout elapses the
backend **completes** the emitter rather than leaving Spring MVC to raise
`AsyncRequestTimeoutException` — which Micrometer would otherwise book as a phantom `503` on
`http.server.requests` even though the client received a clean stream and simply reconnects. A
normal 30-minute stream is thus recorded as a `200` completion, keeping best-effort push off the 5xx
rate. On the client side the frontend relay likewise completes its browser-facing emitter **cleanly**
on any dropped/unavailable backend stream rather than `completeWithError` — which would re-dispatch
the error through the MVC `@ExceptionHandler` and log a spurious ERROR per drop — so a best-effort
stream failure never inflates the frontend error log (the dominant frontend ERROR source during a
backend/Keycloak blip); the browser reconnects and the poll keeps the badge fresh.

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

**Enforced by:** `NotificationStreamServiceTest` (named `connected`/`heartbeat`/`notification`
events + clean timeout completion), full build (bean wiring), frontend lint gate · **Code:**
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
recipient** (`NotificationEvent.contextRecipientSub()` = the offer owner) and is mapped by a seeded
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
`event/MaterialExchangeInterestRegisteredEvent`,
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

- [ ] A requester edit (after commit) notifies the responsible unit's officers + leads, excluding the
  actor (`JobOrderServiceTest`, `RuleEvaluationServiceTest`).
- [ ] The new event/notification types need no schema migration (open enums; the seed rule is V214
  data).
- [ ] The notification renders via `notifications.type.JOB_ORDER_UPDATED_BY_REQUESTER` (DE + EN + base
  bundles, named placeholders `{displayId}`/`{orgUnit}`/`{requester}`).

**Enforced by:** `JobOrderServiceTest`, `MessageBundleConsistencyTest` · **Code:**
`event/JobOrderUpdatedByRequesterEvent`, `service/JobOrderService#publishJobOrderUpdatedByRequester`,
`model/NotificationEventType`, `model/NotificationType`,
`db/migration/V214__seed_job_order_requester_update_notification_rule.sql` · **Issues:** #1186

## Out of scope (v1)

- Per-notification e-mail routing (generic fan-out of in-app notification types to e-mail), user
  channel preferences/opt-in, and digest emails. A **basic transactional e-mail transport** now
  exists (REQ-NOTIF-013, used so far by two hand-wired consumers — the account decision mail
  REQ-NOTIF-014 and the pending-registration admin mail REQ-NOTIF-015); wiring it into the rule
  engine per notification type is deferred.
- Discord channel delivery.
- OS / browser push notifications.
- Multi-backend-instance push fan-out (Redis pub/sub).
- A dedicated user-group entity (the `GROUP` selector kind is reserved for it).

