# ADR-0347 — P4K shops join UEX terminals by place

- **Status:** Accepted (implementation pending)
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-042…044) · ADR-0336 · ADR-0345 · ADR-0346 · #3028 · map #2884 ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

UEX is the Basetool's only source for terminals, with prices, stock and demand. The P4K catalogue
of p4kx v0.4.2 (`schemaVersion` 2) adds a `shops` kind: one entry per placement of a shop entity,
facts only, no price, no stock, no description. Its identity is `key` (the CryGUID chain from the
system root down to the shop entity); `entityGuid` is not unique, because a shop module placed in
several places keeps it. An entry carries `system`, `kind`, `brand`, `commodityExchange` and
`starmapObjectGuid`, and `itemGuids` where the shop has an item list (294 of 1,184 shops on LIVE);
a placeholder inventory is `null`, not empty.

## Decision

1. **Shops are imported** into a new `shop` table keyed by the catalogue's `key`, with `system`,
   `kind`, `brand`, `commodityExchange` and the `starmapObjectGuid` reference. A shop that
   vanishes from a complete `shops` kind is stamped like any other P4K row; a filtered (partial)
   kind never sweeps (ADR-0336).
2. **UEX stays the price key.** Prices, stock and demand never come from the P4K.
3. **A shop links to a UEX terminal by place.** On APPLY a shop is linked to a UEX terminal by its
   starmap object (through the starmap link of ADR-0345), then by name within that place. An
   ambiguous match goes to the review list; a manual link by an admin wins and survives re-imports
   and is an event of `AuditDomain.CATALOGUE` (ADR-0346). An unlinked shop stays usable on the P4K
   side.
4. **Item lists.** A shop's `itemGuids` are stored as shop-to-`game_item` rows and shown to members
   on the item as „available at". A `null` list means unknown, never "sells nothing".
5. **Exchange.** Shop identity and item lists are fact values; nothing is exposed by this decision.

## Consequences

- Terminals gain a second source beside UEX for where a shop is and what it sells, while prices
  stay with UEX.
- The P4K catalogue spec carries the shop, link and item-list requirements; the audit spec covers
  the manual link through the catalogue domain.
- Pending: the `shop` table and its import, the terminal link and its review, the item-list rows
  and the „available at" display.
