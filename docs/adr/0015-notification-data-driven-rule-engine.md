# ADR-0015 — Notification recipients via a data-driven rule engine

- **Status:** Accepted
- **Date:** 2026-06-16
- **Deciders:** @greluc
- **Related:** spec REQ-NOTIF-007, REQ-NOTIF-008 ([`notifications.md`](../specs/notifications.md)) ·
  ADR-0014 · epic [#622](https://github.com/krt-profit/basetool/issues/622)

## Context

"Who is notified for what" must be configurable by admins at runtime — individual users, roles,
and org-relative roles ("officers of the responsible unit") — and must accommodate selector
kinds we have not built yet (e.g. a future user-group entity) without reworking the engine. The
first use case alone needs four different recipient sources combined into one rule.

## Decision

We will resolve recipients from **admin-managed data**, not code: a `notification_rule` (matched
by `event_type`) owns a set of `notification_rule_selector` rows. A selector's `kind` chooses how
it resolves:

- `SPECIFIC_USER` → a single `user_sub`.
- `ROLE` → every holder of a global `role.code` (matched on the stable `code`, not the
  admin-renameable `name`).
- `ORG_RELATIVE_ROLE` → an `org_relative_role` (`OFFICER`/`LEAD`/`LOGISTICIAN`/`MISSION_MANAGER`)
  evaluated against the org unit the event exposes for a `context_role` (`RESPONSIBLE` /
  `REQUESTING`). Officer = global `OFFICER` role ∩ membership of that unit; the others are
  per-membership flags.

The engine unions a rule's selectors, drops the actor when `exclude_actor` is set, and
de-duplicates. `kind` is an open enum (a `GROUP` kind is reserved). UC1 ships as a **seeded,
admin-editable** rule, not hardcoded logic.

## Consequences

- **Easier:** new recipient policies are configuration, not deploys; UC1 and future use cases
  share one resolution path; the seeded rule documents the default and can be tuned or removed.
- **Harder / accepted:** the rule data must stay coherent (selector fields validated per kind);
  officer-ness depends on the Keycloak→`user_roles` mirror, so a freshly-promoted officer is a
  recipient only after the next `UserSyncTask` (≤ 5 min) — an accepted eventual-consistency
  window, called out in the spec.

## Alternatives considered

- **Hardcoded recipient resolvers per event type** — rejected: every change is a deploy; admins
  cannot tune who is notified.
- **A full rules DSL / scripting** — rejected as over-engineered for the foreseeable selector
  set; the typed selector table covers the needs with far less surface.
- **A dedicated user-group entity now** — deferred: the `GROUP` selector kind is reserved so the
  entity can be added later without an engine change.
