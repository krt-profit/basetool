# ADR-0379 — The coarse ship origin is exchanged; purchase data still is not

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** @greluc
- **Related:** spec [`personal-hangar-overview.md`](../specs/personal-hangar-overview.md)
  (REQ-HANGAR-017, REQ-HANGAR-018) · spec [`external-exchange.md`](../specs/external-exchange.md)
  (REQ-XCH-017, REQ-XCH-057) ·
  [ADR-0295](0295-a-hangar-import-matches-ships-by-pledge-and-keeps-pledge-data-private.md) ·
  decisions #3250 and #3255 (map #3246, epic #3328)

## Context

A Star Citizen patch can wipe what a player bought with in-game currency, ships included, while a
ship pledged with real money survives. The admin-triggered patch change (epic #3328) wipes only
ships bought in-game, so the Basetool must know each ship's origin: pledged, bought in-game, or not
known.

The hangar imports read only pledged ships, and some of them carry the RSI pledge id. ADR-0295
keeps everything an import learns about a purchase — pledge id, date, cost, melt and gift flags —
private to the owner and admins, and the Exchange contract says "purchase data is never exchanged"
(REQ-XCH-017). Exchange clients such as VerseKit keep their own copy of the member's hangar, and
their RSI hangar reader knows which ships it read from a pledge.

## Decision

1. **Every ship carries an origin: `PLEDGE`, `IN_GAME` or `UNKNOWN`.** It is part of the ship's
   `version`, so a concurrent edit 409s. A wipe treats `UNKNOWN` like `PLEDGE`.
2. **A ship from a file import, or with a pledge id, is locked to `PLEDGE`.** The lock is a column
   of its own, written only by the import and outside the `version`, as ADR-0295 does for the
   imported data: an import never 409s an edit. A locked ship reads `PLEDGE` everywhere, whatever
   origin it stores, and nobody can change it. An import never matches a ship marked `IN_GAME`.
3. **The origin is private like the pledge data.** Only the owner's hangar endpoints and the admin
   member hangar carry it; the unit overview, `/ships` and the mission views never do.
4. **The member's own client reads and writes this one value through the Exchange.** The ship
   resource gains `origin`; a create without it is `UNKNOWN`, an update without it keeps the stored
   one, and a locked ship refuses any value but `PLEDGE` with `ORIGIN_LOCKED`. This narrows "purchase
   data is never exchanged" to an exception for the coarse origin: a pledge id, a date or a price
   still never leaves the Basetool.

## Consequences

- A client that reads ships from the RSI hangar marks them `PLEDGE`, and a member's in-game
  purchases can be marked once, in the web or in the client, before a patch wipes them.
- The origin tells a third party whether the member paid real money for a ship, a coarse purchase
  fact. It goes only to the member's own client, under the member's consent to
  `exchange.hangar.read`, like the rest of the member's hangar.
- The lock changes what a ship reads as without moving its `version`; the import's write brings the
  ship into the exchange feed, so a client learns the new `PLEDGE` without a new snapshot.
- The stored origin of a locked ship is kept but has no effect; every reader goes through the
  effective origin (`Ship#effectiveOrigin`, the repository's origin filter, the exchange feed).
