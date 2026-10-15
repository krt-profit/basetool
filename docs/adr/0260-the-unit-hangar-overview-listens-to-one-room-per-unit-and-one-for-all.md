# ADR-0260 — The unit hangar overview listens to one room per unit and one for all

- **Status:** Accepted
- **Date:** 2026-10-15
- **Deciders:** @greluc
- **Related:** spec [`hangar-squadron-overview.md`](../specs/hangar-squadron-overview.md)
  (REQ-HANGAR-003, REQ-HANGAR-013) · [`personal-hangar-overview.md`](../specs/personal-hangar-overview.md)
  (REQ-HANGAR-009…012) · [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)
  (REQ-FE-015) · [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md) ·
  [ADR-0143](0143-app-live-sync-bridge-over-backend-sse.md) · issue #2606 (epic #2595, #2581)

## Context

The org-unit tab of the hangar (`/hangar/squadron`) had no live-sync room. A member who moved ships
into a unit, an admin who changed a member's ships, and a connected application that wrote ships
all left every open unit overview stale until its next load.

The tab shows one of two scopes (REQ-HANGAR-003). With a pinned unit it shows exactly that unit.
Without a pin it shows every unit the viewer can see: all their own units, a Bereich's subordinate
units, every ship for an OL member, every ship for an admin. The second scope can span many units,
and its OL and admin forms include ships that belong to no unit at all.

A browser socket carries at most sixteen topics, and the page has no list of the units its unpinned
view spans; the backend computes the scope. The admin member hangar (REQ-HANGAR-011) has the same
gap in the other direction: its writes did not reach the member's open „Meine Schiffe", and the
member's own writes did not reach it.

## Decision

1. **Two room classes.** `hangar-unit:{orgUnitId}` is one unit's room. `hangar-units` is one global
   room for the unpinned view. Both carry the single section `ships`. The tab listens to the pinned
   unit's room, or to `hangar-units` when nothing is pinned. The unpinned view therefore holds
   **one** subscription however many units it spans; it does not open one per unit.
2. **The aggregate room carries no data.** A frame says only "ships changed somewhere". Every
   viewer re-pulls its own scoped page, as in the shared Lager room. The room admits any signed-in
   member. A viewer outside the changed unit pays one coalesced re-read and learns nothing but the
   timing of a change.
3. **A unit's room asks the read gate.** It opens for whoever sees that unit's ships in the
   overview: `currentUnitOverviewScope().permits(orgUnitId)`. The web relay asks it through
   `GET /api/v1/hangar/units/{orgUnitId}/overview-access` with the pin captured at the handshake;
   the app bridge asks it through the hangar module's `LiveSyncTopicAuthorizer`.
4. **The backend announces, after the commit.** Every ship change that alters a unit's overview
   publishes `ships` to the room of each unit it touched, a move to both units, and once to
   `hangar-units`. One transaction publishes each room once, however many ships it touched. A
   rolled-back write publishes nothing. A ship without a unit publishes only to `hangar-units`. The
   pages send nothing on these rooms, because the Exchange and the account deletion write ships
   without a browser.
5. **`hangar:{userId}` also admits an admin on the web.** The relay admits the member, or a socket
   holding `ROLE_ADMIN`, and refuses everyone else, failing closed. The admin member hangar listens
   on the member's room and sends `ships` after each of its writes. The backend's app bridge keeps
   the room self-only: the app has no admin surface that would listen, and an unneeded opening is
   one more thing to get wrong.

## Consequences

- An open unit overview follows moves, edits, deletes, imports, the fitted reset, the home location
  and Exchange writes without a reload, and the admin member hangar and „Meine Schiffe" follow each
  other.
- The all-units view refreshes on any ship change in the organisation, including changes it does
  not display. The receiver coalesces over 1.5 s, so a burst costs one re-read.
- A non-admin whose stored pin is not one of their units sees the unpinned overview, but listens to
  the pinned unit's room and is refused it. The tab then has no live sync until the pin is fixed in
  the switcher. This is the same edge the overview itself already has.
- The two classes carry the bounded metric labels `hangar_unit` and `hangar_units`. The dashboards
  group by `topic_class` and need no change.

## Alternatives considered

- **A bounded set of unit rooms for the unpinned view.** The frontend would have to learn the scope
  it does not compute, an OL or admin view would exceed any useful bound, and ships without a unit
  have no room at all.
- **Only the aggregate room.** Simpler, but every pinned tab would re-read on every change in the
  organisation, and the unit-keyed room is the one a scoped read gate can actually guard.
