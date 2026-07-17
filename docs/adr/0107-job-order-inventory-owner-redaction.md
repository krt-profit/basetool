# ADR-0107 — Requesting-side viewers of an SK-public order see redacted inventory owner/location

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`orders-item-production.md`](../specs/orders-item-production.md) `REQ-ORDERS-029`
  (defines the redaction) · [`orders-requester-access.md`](../specs/orders-requester-access.md)
  `REQ-ORDERS-023` (the requester-access model this narrows) · mirrors the responsible-only gate of
  `AccessGateService.canSeeJobOrderBlueprintOwners` · reuses the explicit-reconstruction redactor
  pattern of `MissionGuestRedactor` ([`security-and-access.md`](../specs/security-and-access.md)
  `REQ-SEC-007`)

## Context

A job order carries a **requesting** org unit (Auftraggeber) and a **responsible** org unit
(processing side). `canSeeJobOrder` gates every per-order read: for a squadron-responsible order it
requires membership of that squadron, but for a **Spezialkommando-responsible order it short-circuits
to `true` for every profit-eligible member** (the SK-public escape, ADR-0091) so the central intake
queue is broadly readable.

Four per-order read endpoints expose the **inventory owner identity and location** of the stock
linked to the order — the order-detail Item-Bestand panel
(`GET /orders/{id}/item-stock`, REQ-ORDERS-028), the material collection
(`GET /orders/{id}/material-collection`), and the two inventory pickers
(`GET /orders/{id}/materials/{matId}/inventory`, `GET /orders/{id}/inventory/orphaned`). All four are
gated on `canSeeJobOrder` alone. On an SK-public order that admits a member of the merely
**requesting** squadron, who then sees which named members of the fulfilling side own the stock and
at which Standort — data the requesting side has no need for and, by owner decision (2026-07-17),
must not see.

A pure requester-only viewer (`redacted = !canSeeJobOrder`) already receives `403` on these four
endpoints, so the gap is exactly the SK-public requesting-side member, whom the system otherwise
treats as a full viewer.

## Decision

Introduce `AccessGateService.canSeeJobOrderInventoryOwners(jobOrderId)` — **identical to
`canSeeJobOrderBlueprintOwners`**: membership of the order's **responsible** org unit (or an admin
with matching scope), **with no SK-public escape**. For a squadron-responsible order it coincides
with `canSeeJobOrder` (whose squadron branch already requires responsible-unit membership), so the
gate only ever diverges on the SK-public path.

Each of the four endpoints, after loading its projection, returns it unchanged when
`canSeeJobOrderInventoryOwners` is `true` and otherwise passes it through
`JobOrderInventoryOwnerRedactor`, which **blanks only the owner (name/id, and the `owningSquadron` +
nested `user` for the picker DTO) and the location** — every amount, the delivered marker and the
ordered/manufactured context are kept, so a requesting-side viewer still sees the order's progress.
The redactor reconstructs each record field-by-field (never a wither), so a newly added
owner/location field is a compile error until a human classifies it — the same anti-leak discipline
as `MissionGuestRedactor`. The frontend renders a blanked owner/location as `—`.

## Consequences

- A requesting-side member of an SK-public order sees the order, its linked-stock amounts and
  delivery progress, but no owner identity or Standort. Squadron-responsible orders are unaffected
  (the gate coincides with `canSeeJobOrder` there), and admins/responsible-SK members see everything
  as before.
- **Accepted trade-off:** a cross-unit LOGISTICIAN processing an SK-public order (a member of the
  requesting squadron who is not in the responsible SK) also sees `—` for owner/location in the
  per-material drill-down and the Herstellung consumption labels. Nothing breaks functionally — the
  production booking keys on `inventoryItemId` + allocation slice + version, the unlink on the item
  id, never on owner/location — but they lose owner attribution when distinguishing same-material
  entries. A follow-up could widen the picker gate to `canSeeJobOrderInventoryOwners or
  canEditJobOrder` if that degradation proves painful; it is deliberately kept simple and uniform for
  now.
- The SK-public escape (ADR-0091) is unchanged for *visibility of the order itself*; this ADR only
  carves the owner/location fields out of it, so the two decisions stay consistent.

