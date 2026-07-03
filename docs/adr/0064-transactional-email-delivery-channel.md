# ADR-0064 — Transactional e-mail as an opt-in, best-effort delivery channel

- **Status:** Accepted
- **Date:** 2026-07-02
- **Deciders:** @greluc
- **Related:** spec REQ-NOTIF-013 (reusable mail channel) · REQ-NOTIF-014 (account decision mail) · REQ-SEC-017 (PENDING approval) · epic #720

## Context

A brand-new Discord (or credential) registration lands `PENDING` and gains no access until an admin
approves it (REQ-SEC-017); the user is bounced to a waiting page. Until now the only signal the user
received was that page — they had to keep re-visiting to discover whether they were approved. The
owner asked for the user to be notified **by e-mail** when the decision is made, and for the same
e-mail transport to be reusable so the in-app notification system can later push notifications on a
second channel.

The existing in-app notification system is deliberately **in-app only**; its spec listed "Email /
Discord channels" as out of scope (v1). The backend had **no** mail capability at all: no
`spring-boot-starter-mail`, no SMTP config, no mail service. It does, however, already have the two
things a decision mail needs — the user's e-mail (`app_user.email`) and a localized `MessageSource`
(the RFC-7807 machinery) — and an after-commit, off-thread event pattern
(`NotificationEventListener`) that produces side effects without endangering the originating
transaction.

Constraints: dev/test/CI must never contact a real SMTP server; a mail failure must never fail or
delay the admin's approve/reject action; no user PII (address, name, rejection reason) may reach the
logs (REQ-OBS).

## Decision

We will add SMTP e-mail as a **reusable, channel-agnostic, best-effort** delivery seam in the
backend, enabled by default but a no-op until an SMTP host is configured, and use it first for the
account approval/rejection mail.

- A `MailService` interface (impl `SmtpMailService` over Spring's `JavaMailSender`) sends a
  domain-free `MailMessage(to, subject, body)`. It carries no notion of approval or notification, so
  any producer — including the notification engine later — can reuse it.
- **Three off-gates.** `app.mail.enabled` is an explicit kill-switch (ships `true`); a non-blank
  `spring.mail.host` is the effective switch (set it to start sending, unset outside prod); and the
  `JavaMailSender` bean is injected via `ObjectProvider` so it is *optional*, autoconfigured only when
  the host is set. Any gate closed ⇒ `send` is a logged no-op — crucially the explicit host check
  means an empty `SPRING_MAIL_HOST` env (which the curated Docker Compose env passes through) never
  fires a broken sender. So dev/test/CI never send mail even with the flag on.
- **Best-effort, off-thread.** The approve/reject path publishes a data-only
  `UserApprovalDecidedEvent`; a `@TransactionalEventListener(AFTER_COMMIT)` + `@Async(MAIL_EXECUTOR)`
  listener composes the localized mail and sends it. Any failure is caught and logged — never
  rethrown — so the committed decision is never affected. A dedicated `MAIL_EXECUTOR` keeps a slow
  SMTP relay from starving in-app notification creation.
- **Localized** via the existing backend `MessageSource` (`email.*` keys in all three bundles). No
  per-user locale is stored yet, so system-initiated mail uses a single configured default locale
  (`app.mail.default-locale`, German). The rejection mail includes the admin's free-text reason.
- **No PII in logs**: only the static localized subject is ever logged, never the address, name or
  reason.

## Consequences

- New users learn of approval/rejection without polling the waiting page; a rejection tells them why.
- The mail seam is generic: adding e-mail as a second notification channel later is wiring
  (a listener composing a `MailMessage` from a notification), not new infrastructure.
- Operators gain a new deployment surface: the app ships mail-enabled, so to actually send, prod only
  sets `SPRING_MAIL_HOST` (+ port/credentials) — with no host the feature stays a no-op, a deliberate
  fail-safe. `APP_MAIL_ENABLED=false` hard-disables it regardless.
- We accept that mail is fire-and-forget: a dropped message is logged but not retried or queued
  durably. For a courtesy notification this is acceptable; a delivery-guaranteed channel would need
  an outbox table and is explicitly not built here.
- The notification spec's "email out of scope (v1)" is amended: a **basic transactional transport**
  now exists; per-notification e-mail routing, digests and user opt-in remain out of scope.

## Alternatives considered

- **Send the mail synchronously inside `approveUser`/`rejectUser`.** Rejected: SMTP latency would be
  charged to the admin's request, and a mail/SMTP error could roll back or 500 the approval — the
  exact coupling the after-commit + swallow pattern exists to avoid.
- **Compose the mail in the frontend (it owns the UI/i18n).** Rejected: the frontend has no business
  logic and no access to `app_user.email`; the decision, the recipient data and the reusable seam
  all live in the backend. Routing mail through the frontend would invert the module boundary.
- **Extend the notification rule engine now to fan out to e-mail per notification type.** Rejected as
  premature: it needs per-user channel preferences, opt-in and a locale per recipient. We build the
  transport now (the hard, shared part) and defer the routing policy — the seam is ready for it.
- **A durable outbox/queue with retries.** Rejected for v1: overkill for a courtesy notice; best-
  effort with loud logging is proportionate. The seam does not preclude adding an outbox later.

