# ADR-0350 — Every viewer of a job order sees the owner and location of its linked stock

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** Repository owner (@greluc)
- **Related:** supersedes [ADR-0107](0107-job-order-inventory-owner-redaction.md) · spec
  [`orders-item-production.md`](../specs/orders-item-production.md) `REQ-ORDERS-028`,
  `REQ-ORDERS-029`, `REQ-ORDERS-031` · [`orders-requester-access.md`](../specs/orders-requester-access.md)
  `REQ-ORDERS-023` · ADR-0091 (the SK-public order visibility)

## Context

ADR-0107 blanked the owner and Standort of the stock linked to a job order for every caller outside
the order's responsible org unit, on four reads: the inline item stock (`GET /orders/{id}/item-stock`),
the material collection (`GET /orders/{id}/material-collection`) and the two inventory pickers
(`GET /orders/{id}/materials/{matId}/inventory`, `GET /orders/{id}/allocations/orphaned`).

Its stated target was the requesting side of a Spezialkommando-responsible order. The gate
(`canSeeJobOrderInventoryOwners`, membership of the responsible unit) catches far more callers who
may read the order: every profit-eligible member of an uninvolved squadron on an SK order, assignees
(REQ-ORDERS-047) and cross-unit LOGISTICIAN grantees. They see the order, its amounts and its
progress, but every owner and location as `—`, so they cannot tell where the earmarked stock is
or whom to ask for it. A member of the IRIDIUM squadron opening an AGN (Spezialkommando) order hit
exactly this.

## Decision

Whoever may read a job order (`canSeeJobOrder`) sees the owner and location of every stock row
linked to it, on all four reads. `canSeeJobOrderInventoryOwners` and
`JobOrderInventoryOwnerRedactor` are removed; the four endpoints return their projection unchanged.

Unchanged:

- A requester-only viewer (`redacted == true`, `!canSeeJobOrder`) still gets `403` on all four reads
  (REQ-ORDERS-023).
- The mission slices on the picker rows stay anonymised for a mission the caller may not read
  (`JobOrderMissionAllocationRedactor`, REQ-ORDERS-029): access to an order never widens access to a
  mission.
- The blueprint-owner panel keeps its responsible-unit gate (`canSeeJobOrderBlueprintOwners`); it lists
  the members who could craft, which is a different fact from where earmarked stock lies.

## Consequences

- On an SK-public order every profit-eligible member, the requesting side included, sees who holds the
  earmarked stock and at which Standort. This is the intended visibility, not a leak.
- The cross-unit LOGISTICIAN trade-off of ADR-0107 disappears: the pickers and the Herstellung labels
  show the owner again.
- The frontend keeps rendering a missing owner or location as `—`; that now only happens when the row
  itself has none.
