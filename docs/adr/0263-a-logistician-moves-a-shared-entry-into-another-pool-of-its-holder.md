# ADR-0263 — A Logistician moves a shared entry into another pool of its holder

- **Status:** Accepted
- **Date:** 2026-10-16
- **Deciders:** @greluc
- **Related:** spec [`inventory-lager.md`](../specs/inventory-lager.md) (REQ-INV-052, REQ-INV-058,
  REQ-INV-059) · [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-045) · ADR-0257

## Context

A shared Lager entry belongs to the pool of one org unit, the unit its holder booked it to. A member
who belongs to two units — a Staffel and a Spezialkommando — can hold shared stock in both pools.
Until now the web had no action that moves such an entry into the holder's other pool: the transfer
refuses "same holder, same place" (REQ-INV-025), the org-unit change only takes the owner's own
personal rows (REQ-INV-052), and the only way was two rebookings through "personal", which an
earmarked entry cannot take. The exchange's `move-holder` (REQ-XCH-045) is meant to mirror a web
action, so it refused this case.

## Decision

1. **A Lager action of its own.** `InventoryPoolChangeService` moves a shared entry, or a part of
   it, into the pool of another unit its holder directly belongs to. REQ-INV-052 keeps shared rows
   out of the org-unit change and points to the new action instead.
2. **A Logistician of both units.** The caller holds the contextual Logistician authority of the
   entry's pool and of the receiving pool (`hasRoleInOrgUnit`, an admin passes); the flat role is
   not enough, because the action decides which unit's Logisticians may then edit the stock. The
   picker lists only units that pass (`GET /api/v1/inventory/{id}/pool-targets`).
3. **The whole entry keeps its earmarks, a part comes from the free stock.** A whole move only
   changes the entry's unit, so its job-order and mission earmarks and its Materialbörse offers stay;
   an entry with a donation pledge is not moved in full (REQ-INV-058). A part is split off the free
   stock as a new entry; the source keeps the rest, all earmarks and pledges, and may not fall below
   its offer. The result merges by the write-time rules (REQ-INV-026).
4. **Own audit event, own notification.** `INVENTORY_POOL_CHANGED` (from / to as `kind:id`, the
   amount, whether it split) and, when somebody other than the holder moved it,
   `INVENTORY_POOL_CHANGED_BY_OTHER` to the holder (V300). Neither the transfer's events nor its
   owner-change notices fit: the holder stays the same.
5. **The exchange mirrors it as `move-pool`.** The op goes through the same service after the
   pool commands' per-op checks; `move-holder` to the entry's own holder stays `NOT_PERMITTED`.

Rejected: letting the transfer change the unit alone — it would reopen REQ-INV-025's "unchanged"
rule for every transfer and audit a pool change as a transfer; the flat Logistician role — a
Logistician of one unit could then pull stock into a unit they do not serve; moving earmarks with a
part — the receiving unit's Logisticians would inherit reservations for another unit's orders.
