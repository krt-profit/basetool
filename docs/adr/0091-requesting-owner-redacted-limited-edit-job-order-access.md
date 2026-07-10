# ADR-0091 — Requesting-owner redacted view + limited edit of job orders

- **Status:** Accepted
- **Date:** 2026-07-10
- **Deciders:** @greluc
- **Related:** `AccessGateService.canSeeJobOrderAsRequester` / `canEditJobOrderAsRequester` · `RequestScopeResolver.isOrderRequester` / `currentDirectMembershipOrgUnitIds` / `canViewOwnJobOrders` · `JobOrderService.updateJobOrderAsRequester` / `updateItemJobOrderAsRequester` / `getRequestedJobOrders` / `replaceMaterialsWithinTransaction` · `JobOrderController` (`GET /requested`, `PUT /{id}/requested`, `PUT /{id}/items/requested`, `cleanupJobOrderForRequester`) · `JobOrderUpdatedByRequesterEvent` · V214 · REQ-ORDERS-023 · REQ-NOTIF-017 · REQ-ORG-003 · REQ-SEC-009 · ADR-0029 · #1186

## Context

A job order carries a **responsible** (processing) org unit and a **requesting** (Auftraggeber /
customer) org unit. Since Phase 3 (#343) only the responsible org unit granted visibility, and even
that was folded behind the profit-eligibility viewer gate `canViewJobOrders` — "the requester does
**not** grant visibility" (REQ-ORG-003, REQ-SEC-009). Consequently a member of a purely non-profit
ordering unit could **create** an order (the create endpoints are `permitAll()`) but could neither
list, view nor edit it afterwards — the same "submit but don't track" posture as an anonymous guest.

Issue #1186 asks to let such ordering-squad members view and edit their **own** orders within limits.
Three modelling decisions were open, all resolved by the repo owner (@greluc):

1. **What is "own"?** A job order has **no** per-user creator column and may be created anonymously,
   so there is no durable per-user ownership signal. Options: (a) org-unit-level — membership in the
   requesting org unit; (b) add a `created_by_sub` column (schema change, breaks anonymous creation).
2. **When is an order still editable?** A material order's `amount` is decremented in place by
   handovers (no delivered/original split), so per-material "delivered" state is lossy. Options: (a)
   a coarse **whole-order freeze** (frozen once *any* delivery exists, matching the existing item-
   order behaviour); (b) a per-material handover-existence check (finer, subtler under partial
   deliveries).
3. **Who gets the limited view/edit?** (a) any member of the requesting unit who cannot already see
   the order fully; (b) strictly non-profit members.

The general access model must not weaken the existing responsible-scoped queue or the profit gate.

## Decision

Add a **requester escape** as an *additive, second* access dimension, distinct from both the profit
gate and the LOGISTICIAN full-edit path:

- **Ownership = membership in the order's `requestingOrgUnit`** (decision 1a), keyed on the caller's
  **direct** memberships (`currentUserIsMemberOfOrgUnit` / `currentDirectMembershipOrgUnitIds`, not
  the leadership cascade, so an OL/Bereich seat does not turn every unit's orders into "theirs"). No
  schema change.
- **Whole-order freeze** (decision 2a): a requester may edit only while the order has no material and
  no item handover; once any delivery exists the order is frozen for the requester (HTTP 400),
  re-checked in the service to close the gate→commit TOCTOU window.
- **General requester escape** (decision 3a): the gate admits any direct member of the requesting
  unit; the redaction (drop Bearbeiter, materials summary, collection progress, handovers, blueprint
  coverage) is applied whenever the caller is not already a full viewer, so a profit member who only
  *requested* an order processed elsewhere also gets the redacted view.
- New gates `canSeeJobOrderAsRequester` / `canEditJobOrderAsRequester` are ORed into the read
  endpoint and back **dedicated** requester endpoints (`PUT /{id}/requested`,
  `PUT /{id}/items/requested`, `GET /requested`) that carry **no** `hasRole('LOGISTICIAN')`
  requirement — leaving the processing-side endpoints and their semantics untouched.
- Requester edits reuse the existing audit events (`JOB_ORDER_UPDATED` / `_ITEM_UPDATED` /
  `_MATERIAL_UNLINKED`) with a bounded `byRequester=true` details flag (no new `AuditEventType`), and
  publish a new `JobOrderUpdatedByRequesterEvent` notifying the responsible unit's officers + leads
  (seeded rule V214, `NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER`).

The shared material full-replace was hardened to the canonical `createHandover` unlink ordering
(mutate + `saveAndFlush` first, then the `clearAutomatically` inventory unlinks, then re-fetch) and
extracted into `replaceMaterialsWithinTransaction`, reused by both the logistician and requester
paths — closing the pre-existing in-loop-before-save detach fragility on `updateJobOrder`.

## Consequences

- The Phase-3 invariant "the requester does not grant visibility" is deliberately superseded for a
  **redacted, limited** surface only; REQ-ORG-003 and REQ-SEC-009 are amended with a carve-out
  pointer to REQ-ORDERS-023. The responsible-scoped queue and the profit gate are unchanged.
- Ownership is org-unit-level: every member of a requesting unit sees/edits every order that unit
  placed. A true per-user "own" notion would need a new `created_by_sub` column and is out of scope.
- A profit member who only requested an order processed by a foreign squadron gets a redacted view;
  the item-order requester **edit UI** ships later (the backend endpoint already enforces the limits).
- The whole-order freeze means a partially-delivered order is fully frozen for the requester — simpler
  and safe under the lossy material-amount model, at the cost of not allowing edits to the
  still-undelivered remainder once any delivery has started.

