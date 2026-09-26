# ADR-0220 — External clients see only the anonymised demand of the member's own units and the Lager's location list

- **Status:** Proposed — epic [#2078](https://github.com/krt-profit/basetool/issues/2078); nothing
  built yet.
- **Date:** 2026-09-26
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) ·
  [`orders-material-demand.md`](../specs/orders-material-demand.md) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md)

## Context

VerseKit keeps a list of what the member wants to farm. The org's open orders already know what the
member's units need. Handing that to a foreign program is org data leaving the Basetool, onto a PC
we do not control, in files the program suggests syncing to cloud drives.

## Decision

We will expose exactly two pieces of non-personal data to external clients.

1. **Org demand** (`exchange.demand.read`, read-only): the open demand of the units the member is a
   member of — never units the member merely oversees or administers. It lists materials (with
   minimum quality, the raw ores that refine into a refined material, and whether each line comes
   from a material order or from what an item order resolves to) and the demanded items with
   `craftableByMe`. It carries no requester, no assignee, no order title, no free text, no unit
   name beyond the member's own membership, and no per-order breakdown. There is **no low-count
   suppression**, and a client may cache the feed for up to **7 days**.
2. **Locations**: the Lager's non-hidden locations with their UEX link, so clients offer only places
   that will match.

## Consequences

- A member can see what their own units need in the tool they farm with.
- Without suppression a single open order is recognisable to anyone who knows the unit; that is
  accepted — the member could read the same order in the web.
- A left member's cached copy ages out within 7 days at most; departure revokes access at once.

## Alternatives considered

- **Suppress lines below k = 2.** Recommended, rejected by the owner.
- **A 24 h client cache.** Recommended, rejected by the owner in favour of 7 days.
- **No org data at all.** Rejected: the demand is the main thing VerseKit's farming list lacks.
