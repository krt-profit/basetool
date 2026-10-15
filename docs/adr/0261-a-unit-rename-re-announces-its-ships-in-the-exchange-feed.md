# ADR-0261 — A unit rename re-announces its ships in the Exchange feed

- **Status:** Accepted
- **Date:** 2026-10-15
- **Deciders:** @greluc
- **Related:** [ADR-0258](0258-a-ships-org-unit-has-its-own-optimistic-counter.md) (amended) ·
  [ADR-0224](0224-the-change-feed-is-a-trigger-written-key-log.md) (the change feed) · spec
  [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-017) ·
  [`data-persistence.md`](../specs/data-persistence.md) (the trigger crossings) · epic #2595,
  issue #2600

## Context

The Exchange ship feed now carries each ship's org unit with its name, shorthand and kind, and the
unit's counter `orgUnitVersion` (ADR-0258). A client keeps what it last pulled and asks only for
the changes after its cursor.

ADR-0258 named as a consequence that a unit change does not move the ship's change-feed position.
That does not hold: the V252 ship trigger records every update of a ship row, and a unit change is
one. Only the ship's `version` stays.

A unit's name and shorthand live on `org_unit`, not on the ship. Renaming a unit changes no ship
row, so without more the feed would never tell a client, and it would show the old name for as long
as it keeps the ship.

## Decision

1. **A unit change is a ship change in the feed.** The ship arrives in the next delta pull with
   its new `orgUnit` and `orgUnitVersion`; its `version` does not move (ADR-0258 still holds for
   the lock).
2. **A unit rename re-announces its ships.** The trigger `trg_org_unit_exchange_change` (V293) on
   `org_unit` records a `SHIP` change for every ship of a unit whose `name` or `shorthand` changes.
   An update that changes neither records nothing, so a save of the unit's other settings does not
   flood the feed. The `kind` is not watched: it is the entity's discriminator and never changes.
3. **The same mechanism as the default set.** Like the default-blueprint trigger of V252, a
   change in another module's table announces the affected keys of every member in the same
   transaction, attributed to the writer of that transaction. The crossing from `org_unit` into
   `ship` and `exchange_change` is listed in REQ-DATA and `TableOwnershipTest`.

## Consequences

- A client never keeps a stale unit name or shorthand longer than until its next pull.
- A rename of a unit with many ships adds one feed entry per ship, attributed to the person who
  renamed it; a client sees that writer on ships the member did not touch.
- A further display field of the unit that the feed carries must join the trigger's column list.

## Alternatives considered

- **An application event from the org-unit service.** The org-unit module would have to report to
  the exchange module, which it may not depend on; it would need a new observer SPI, and a write
  that bypasses the service would be missed. The trigger catches every write path, as the rest of
  the change feed does.
- **No re-announcement; clients re-read `GET /me/org-units`.** That route lists only the member's
  memberships, while a ship's unit need not be one of them, and a client would have to poll it.
