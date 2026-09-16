# ADR-0014 — Notification system: per-user inbox produced by after-commit domain events

- **Status:** Accepted
- **Date:** 2026-06-16
- **Deciders:** @greluc
- **Related:** spec REQ-NOTIF-001..004, REQ-NOTIF-009 ([`notifications.md`](../specs/notifications.md)) ·
  ADR-0015 · ADR-0016 · epic [#622](https://github.com/krt-profit/basetool/issues/622)

## Context

The tool needs to notify specific users when things happen (first: a new job order). The
substrate must be generic (any action can notify), extensible (new sources without a schema
change), and must not jeopardise the originating business transaction. Two cross-cutting
forces shaped it:

- **Isolation model.** A notification belongs to one person, not an org unit. The Personal
  Inventory / personal-blueprint features already establish a per-`sub` model; the bank
  (ADR-0011, REQ-BANK-008) establishes that some aggregates are deliberately org-unit
  independent.
- **Transaction safety.** This codebase has shipped optimistic-locking 409s when a
  `@Transactional` method touched an aggregate a second time. Producing notifications inside
  the create transaction would risk exactly that, and would also couple notification failure
  to business-action success.

## Decision

We will model notifications as a **per-user inbox** keyed by the JWT `sub` (`recipient_sub`),
isolated like Personal Inventory: every query filters by `sub`, foreign ids 404, and the
service is **excluded from the org-unit-scoped ArchUnit whitelist** (it wires neither
`OwnerScopeService` nor `AuthHelperService`).

Production is **event-driven and after-commit**: a producer publishes a `NotificationEvent`
(scalars only — ids, kinds, render params; never a managed entity) via
`ApplicationEventPublisher`; a `@TransactionalEventListener(phase = AFTER_COMMIT)` runs on a
dedicated MDC-decorated executor (`AsyncConfig.NOTIFICATION_EXECUTOR`) in a fresh transaction
and writes the rows. The notification `type` + a JSON `params` map are stored language-neutral
(plain `TEXT`, never queried — the `P4kImportJob.result_json` precedent); the frontend renders
the text.

A scheduled, property-gated sweep deletes read notifications past a max age (default 90 days),
independent of user-initiated deletes.

## Consequences

- **Easier:** a rolled-back action produces no phantom notifications; notification work adds
  no latency to (and cannot fail) the business transaction; no second `@Version` bump on the
  source aggregate; a new producer is a small, self-contained addition.
- **Harder / accepted:** notifications are eventually delivered (after commit, off-thread), not
  synchronously; a single-instance in-memory async executor (acceptable on the single-VM
  deployment).
- Follow-up: real-time push (ADR-0016) and the admin rule UI build on this substrate.

## Alternatives considered

- **Synchronous creation inside the producer transaction** — rejected: couples notification
  failure to the business action and risks the documented double-`@Version` 409.
- **Org-unit-scoped notifications** — rejected: a notification is addressed to a person; the
  per-`sub` model is simpler and matches Personal Inventory.
- **A message broker (Kafka/RabbitMQ)** — rejected for v1: the single-VM deployment (ADR-0009)
  has no broker; Spring's transactional events + an in-memory executor are sufficient at this
  scale.
- **Storing rendered text** — rejected: it would freeze language and locale at creation time;
  storing `type` + `params` keeps rendering a frontend concern.
