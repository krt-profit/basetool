# ADR-0295 — A hangar import matches ships by pledge and keeps pledge data private

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** spec [`personal-hangar-overview.md`](../specs/personal-hangar-overview.md)
  (REQ-HANGAR-015, REQ-HANGAR-004) ·
  [ADR-0258](0258-a-ships-org-unit-has-its-own-optimistic-counter.md)

## Context

The browser extension „Star Citizen: Hangar Extension" exports a member's RSI hangar twice: a fleet
JSON with one record per ship (model, production status, insurance) and a hangar CSV with one row
per pledge item (pledge id, date, cost, melt and gift flags, content type). Neither file carries
what the other has, so a member imports both, one after the other.

The import so far only created `max(0, upload - hangar)` ships per type. It had no notion of
which hangar ship an entry stands for, so it could neither fill the second file's data into the
ships the first file created nor tell a re-import from new ships.

The pledge cost is what a member paid for the pledge. The `ShipDto` that carries a ship also goes
to unit officers (`/ships`), to mission peers (assigned units) and to everyone who sees a mission.

## Decision

1. **An import matches each entry to one hangar ship before it creates any.** Per ship type, an
   entry first takes the ship with the same pledge id, then a ship without a pledge id (one with the
   same insurance first); only the entries left over create ships. A matched ship takes the entry's
   production status and pledge data and keeps everything a member edits. Ships the upload does not
   list are never deleted. The other formats run through the same matching and carry no such data.
2. **The import locks the importer's ships in ascending id order** for its transaction
   (`ShipRepository#lockAllOwnedByOrderById`), so two concurrent imports of one member queue
   instead of matching the same ship twice, and they cannot deadlock with the bulk unit change of
   REQ-HANGAR-010, which locks in the same order.
3. **Status and pledge columns lie outside the ship's `version`.** They carry
   `@OptimisticLock(excluded = true)` on the `@DynamicUpdate` entity, as the unit does
   (ADR-0258). Only the import writes them and the edit form never sends them, so neither 409s or
   overwrites the other. They have no counter of their own: the import is the single,
   last-writer-wins source.
4. **Pledge data leave the backend only to the owner and to admins.** `ShipMapper#toDto` leaves
   `pledge` empty; `ShipMapper#toOwnerDto` fills it and is used only by the owner's hangar endpoints
   and the admin member hangar. Every other `ShipDto` — `/ships`, missions, the mission peer
   redaction — carries no pledge. The production status is not personal and goes everywhere.
5. **The production status is stored per ship, not on the catalogue.** It is what the member's
   export said at import time; `ShipType` is a shared catalogue that member uploads must not write.

## Consequences

- JSON then CSV, CSV then JSON, or the same file twice, all end with one ship per pledged ship.
- A pledge-less ship of a type is filled by the first CSV that lists that type, even if the member
  meant a different pledge; with several, the same insurance decides first. Members cannot edit
  pledge data by hand: the import is their only writer.
- A melted pledge's ship stays in the hangar with its old pledge id until the member deletes it.
- A later reader of `ShipDto` must pick the mapper by audience; the default is the safe one.
