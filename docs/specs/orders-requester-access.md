> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-10.
> **Owner area:** ORDERS · **Related ADRs:** ADR-0091

# Requesting-owner (Auftraggeber) job-order access

## Context & goal

Before this feature a job order's **requesting** org unit (the Auftraggeber / customer that placed
the order) granted **no** visibility — only the **responsible** (processing) org unit did, and even
that was gated behind the profit-eligibility viewer gate `canViewJobOrders`
([`security-and-access.md`](security-and-access.md), REQ-SEC-009;
[`org-unit-tenancy.md`](org-unit-tenancy.md), REQ-ORG-003). A member of a purely non-profit ordering
unit could therefore **create** an order but could neither see nor track it afterwards — the same
"submit but don't track" posture as an anonymous guest (#1186).

This spec adds a **requester escape**: a member of an order's requesting org unit may view that order
and, while it is still fully undelivered, edit it within limits — independent of the profit gate and
independent of the LOGISTICIAN full-edit path used by the processing side. Ownership is defined at the
**org-unit level** (membership in the requesting org unit), because a job order carries no per-user
creator column and may be created anonymously; there is no schema change (ADR-0091).

## Requirements

### REQ-ORDERS-023 — Requesting-owner may view and edit their own order within limits

A member of a job order's **requesting org unit** (Auftraggeber) MAY:

- **View** the order — reached via a dedicated `GET /api/v1/orders/requested` list (the "Meine
  Aufträge" surface) and the order-detail page. The response is **redacted**: it drops the
  **Bearbeiter** (assignee) section, the **materials summary** (aggregated materials + the per-line
  collection-progress fields `currentStock` / `claims` / `openAmount`), the delivery events
  (handovers / item-handovers) and the blueprint-coverage view. The ordered lines the requester
  placed (materials with quantity + min-quality, or ordered items), the comment, the status and the
  optimistic-lock version are preserved. The redacted `JobOrderDto` carries a **per-order `redacted`
  flag** (`true` on this view, `false` on the full view), stamped server-side from the loaded entity;
  the client keys its limited rendering off that flag rather than a global capability, so a member who
  is both profit-eligible **and** the requester of a foreign-processed order sees the limited template
  that matches the redacted data.
- **Edit** the order — **only while it is still fully undelivered** (the *whole-order freeze*: the
  order has no material handover and no item handover). Within that window the requester may change
  quantities, add or remove material/item lines, change a material's min-quality within the fixed
  choices (Keine / 650), and edit the comment. Removing a material/item **unlinks** the corresponding
  linked inventory entries. The requester may **not** change the handle, the requesting/responsible
  org unit, the status or the priority — those inputs are ignored server-side. Once any delivery is
  recorded the order is frozen for the requester (HTTP 400).

The requester escape is **additive**: it never widens the general order queue (which stays
responsible-scoped) and never grants the requester the blueprint-coverage view. A caller who is *also*
a full viewer (responsible-side member / admin) keeps the complete, unredacted view and the
LOGISTICIAN edit path. A stale write surfaces as HTTP 409; the edit is audited as `JOB_ORDER_UPDATED`
/ `JOB_ORDER_ITEM_UPDATED` with a `byRequester=true` discriminator (REQ-AUDIT-001), and on commit the
processing unit's officers/leads are notified (REQ-NOTIF-017).

A related, narrower carve-out applies even to a caller who *is* a full viewer of an **SK-public**
order purely because their org unit **requested** it: the linked-inventory **owner identity and
location** on the order's four inventory reads (Item-Bestand panel, material collection, the two
pickers) are redacted for them, since the SK-public escape would otherwise expose the fulfilling
side's owners. See [`orders-item-production.md`](orders-item-production.md) `REQ-ORDERS-029` /
[ADR-0107](../adr/0107-job-order-inventory-owner-redaction.md).

**Acceptance**

- [ ] A non-profit member sees the orders their own org unit requested under "Meine Aufträge" and can
  open their detail; the detail hides the Bearbeiter section and the materials summary.
- [ ] The same member cannot see or edit an order requested by a different org unit (HTTP 403 on
  read, 403 on write).
- [ ] While the order has no delivery, the requester can change a quantity, add/remove a material,
  and edit the comment; removing a material clears its linked inventory (`InventoryItem.jobOrder`).
- [ ] Once a handover exists, a requester edit is rejected (HTTP 400).
- [ ] A requester edit records a `byRequester=true` audit event and publishes a
  `JobOrderUpdatedByRequesterEvent`.

**Enforced by:** `OwnerScopeServiceTest` (RequesterEscapeGateTests), `JobOrderServiceTest`
(updateJobOrderAsRequester\_\*) · **Code:** `AccessGateService.canSeeJobOrderAsRequester` /
`canEditJobOrderAsRequester`, `RequestScopeResolver.isOrderRequester` (via
`currentUserIsMemberOfOrgUnit`) + `currentDirectMembershipOrgUnitIds` / `canViewOwnJobOrders`,
`JobOrderService.updateJobOrderAsRequester` / `updateItemJobOrderAsRequester` /
`getRequestedJobOrders`, `JobOrderController` (`GET /requested`, `PUT /{id}/requested`,
`PUT /{id}/items/requested`, `cleanupJobOrderForRequester`) · **Issues:** #1186

## Out of scope

- Per-user ("only the person who created it") ownership — ownership is org-unit-level by design
  (ADR-0091); a job order has no `created_by` column and may be created anonymously.
- Changing the responsible org unit, status, priority or handle from the requester side — those stay
  processing-side concerns (the reassignment endpoint owns the responsible unit).
- The item-order requester **edit UI** ships in a later increment; the backend endpoint
  (`PUT /{id}/items/requested`) already enforces the same limits, and the requester can already view
  item orders. Material-order requester editing is the shipped UI surface.

## Open questions

None.
