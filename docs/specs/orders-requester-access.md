> **Doc type:** Living spec - complete MATERIAL/ITEM reading and requester need editing. Last reviewed: 2026-11-09.
> **Owner area:** ORDERS · **Related ADRs:** ADR-0091, ADR-0356

# Requesting-unit JobOrder access

## Context & goal

The requesting unit is the customer, separate from the current responsible Profit unit.
[The shared JobOrder contract](orders-core.md) owns the complete reader audience, current unit
writers and the accepted requester-write target. Reading does not depend on creation or processing
permission, and no order stores a per-user creator identity as an authority relationship.

## Requirements

### REQ-ORDERS-023 - Requesting-unit reading and current requester capability

Requesting-unit members and existing leadership/contextual reach read complete permitted order
facts through the shared queue, requested scope, number search, detail and existing linked reads.
They see collection progress, assignees and notes, material requirements, handovers and persisted
reports, and the linked-stock owner/location. The DTO has no reduced-requester flag.

`canEdit` reports current responsible-side processing authority. `canEditAsRequester` reports
the current requesting-unit writer's capability to edit remaining need. Ordinary membership
grants reading. An observed former requesting unit retains concrete order read through current
legitimate unit reach.

The producer panel retains REQ-ORDERS-016's independent viewer gate. Unreadable mission names and
planned start remain anonymous in linked stock. Unrelated Inventory, private Bank data and the
reduced external Exchange surface keep their own authority checks.

A current requester writer edits remaining MATERIAL need and comment through
`PUT /orders/{id}/requested`. For ITEM, `PUT /orders/{id}/items/requested` reconciles persistent
line identities in place. Recorded handovers and manufactured and delivered quantities survive.
A produced line remains present with the same item and an amount that covers booked quantities.
Both edits use the observed need version, reconcile material promises atomically, and preserve
the current lifecycle. The central detail exposes the appropriate existing editor.
A valid responsibility transfer is required before editing an order with invalid responsibility.

Requester writes retain the existing `JOB_ORDER_UPDATED` / `JOB_ORDER_ITEM_UPDATED` audit events
with `byRequester=true` and requester-change notices. Read admission and retained unit relationships
do not introduce a separate user mutation or a new notification audience.

**Acceptance**

- [ ] Every currently admitted requester sees complete facts consistently across list/count,
  number lookup, detail and its existing read representations.
- [ ] A requester read grants neither processing nor assignee enrollment and preserves independent
  producer/mission/Bank/Inventory gates.
- [ ] Current unit authority governs requester edits; a former unit or plain member cannot write.
- [ ] Post-partial requester edits preserve booked production, delivery and history.

**Enforced by:** `JobOrderHierarchyAuthorityIntegrationTest`,
`JobOrderAssigneeGrantIntegrationTest`, `JobOrderMissionAllocationRedactorTest`,
`OrdersDetailPatternRenderTest` and `JobOrderResponsibilityRecoveryMvcTest` cover current reading
and capability boundaries, including safe post-partial requester edits.

## Out of scope

Per-user creator ownership, general stock or Bank rights from an order read, and the independent
private blueprint view.

## Open questions

None.
