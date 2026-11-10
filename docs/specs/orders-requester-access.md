> **Doc type:** Living spec - complete MATERIAL/ITEM reading implemented; shared requester-write amendments pending. Last reviewed: 2026-11-09.
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

`canEdit` always means current responsible-side processing. `canEditAsRequester` separately
reports the current requesting-unit writer under the shipped undelivered-order restriction.
Ordinary membership alone grants reading. An observed former requesting unit retains only its
concrete order read through current legitimate unit reach; it gains no current requester write.

The producer panel retains REQ-ORDERS-016's independent viewer gate. Unreadable mission names and
planned start remain anonymous in linked stock. Unrelated Inventory, private Bank data and the
reduced external Exchange surface keep their own authority checks.

**Implemented requester writes.** A current requester writer may edit MATERIAL need/comment through
`PUT /orders/{id}/requested` while neither material nor item handovers exist. The backend ITEM
requester endpoint retains that same prerequisite. Writes require the relevant version through
`JobOrderEditor`; a stale version returns 409. Invalid responsibility blocks these writes until a
valid transfer. The MATERIAL editor uses `canEditAsRequester` rather than display reduction;
ITEM processing editing continues to require `canEdit`.

**Accepted subsequent write target.** Safe remaining MATERIAL/ITEM need edits after partial work,
cancellation/reopening rights and the ITEM requester editor follow orders-core.md and their owning
requester/lifecycle slice. They are not enabled by broader reading. Existing manufactured,
delivered and historical facts must survive that implementation.

Requester writes retain the existing `JOB_ORDER_UPDATED` / `JOB_ORDER_ITEM_UPDATED` audit events
with `byRequester=true` and requester-change notices. Read admission and retained unit relationships
do not introduce a separate user mutation or a new notification audience.

**Acceptance**

- [ ] Every currently admitted requester sees complete facts consistently across list/count,
  number lookup, detail and its existing read representations.
- [ ] A requester read grants neither processing nor assignee enrollment and preserves independent
  producer/mission/Bank/Inventory gates.
- [ ] Current unit authority governs requester edits; a former unit or plain member cannot write.
- [ ] The later post-partial requester workflow preserves booked production, delivery and history.

**Enforced by:** `JobOrderHierarchyAuthorityIntegrationTest`,
`JobOrderAssigneeGrantIntegrationTest`, `JobOrderMissionAllocationRedactorTest`,
`OrdersDetailPatternRenderTest` and `JobOrderResponsibilityRecoveryMvcTest` cover current reading
and capability boundaries; the post-partial workflow remains pending.

## Out of scope

Per-user creator ownership, general stock or Bank rights from an order read, and the independent
private blueprint view. The shared requester/lifecycle slice owns the remaining write amendments.

## Open questions

None.
