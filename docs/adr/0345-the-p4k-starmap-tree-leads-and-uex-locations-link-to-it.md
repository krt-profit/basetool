# ADR-0345 — The P4K starmap tree leads and UEX locations link to it

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-034…039, REQ-OBS-022) · the exchange spec · the audit spec · the observability
  spec · REQ-REFINERY-020 · ADR-0336 · ADR-0346 · #2896 · #2902 · #2827 · #2828 · #2824 · map #2884 ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

The location area holds nine UEX tables (`star_system`, `orbit`, `planet`, `moon`, `city`,
`outpost`, `space_station`, `poi`, `terminal`) with terminals, flags and prices, keyed by an
internal UUID. Storage places (`location`) hang on a city or a space station. The personal
inventory references a place by `location_uex_id` + `location_type`.

The P4K catalogue's `starmapObjects` kind carries the game's location tree: `guid`, `type`,
`parentGuid`, `hideInStarmap`, `system`, `position` (metres, in the frame of the system container)
and `name` — a game text (`x-gameText`) with its English and German text. It includes objects UEX
does not know (jump points, Lagrange points, POIs). The archive has no reliable marker for a
released object; the contract leaves the decision to the Basetool from `system`, `position`,
`hideInStarmap` and `type`.

## Decision

1. **The P4K starmap tree leads; UEX stays the trade layer underneath it.** The internal UUID stays
   the primary key of every location table and every existing FK stays.
2. **A new table `starmap_object`** holds the unique P4K GUID, type, parent FK, world position,
   `name` and `name_de`, including P4K-only objects.
3. **The nine UEX tables gain a nullable `starmap_object_id`.** Linking runs on P4K APPLY by
   normalised name + type + the already-linked parent. A unique hit links; an ambiguous hit or no
   hit goes on the review list of the change report. An admin can set or clear a link by hand; an
   import never overwrites a manual link.
4. **One-sided places.** A UEX-only place stays fully usable (terminals, prices, storage),
   unlinked, counted as unmatched in the change report and absent from the starmap. A P4K-only
   object exists only in the starmap tree and never becomes a trade or storage place by itself. A
   vanished P4K object follows the orphan sweep (ADR-0336); a linked UEX row stays usable.
5. **Storage places.** `location` gains a nullable `starmap_object_id` beside the nullable
   `city_id` and `space_station_id`; at least one of the three is set. The UEX sync still creates
   cities and stations; any other starmap node becomes a storage place only when an admin creates
   it, and the P4K import never creates storage places. The refinery rule (REQ-REFINERY-020) and
   the exchange `uex` reference keep running through city / station.
6. **Names.** The UI shows the P4K name of a linked place in the page language (`name_de` /
   `name`). `location.name` stays the curated name (from UEX for cities and stations, set by the
   admin for other nodes); it is not a game text and is the only location name that reaches the
   exchange.
7. **Exchange.** `uex:{kind,id}` stays and is not deprecated. A linked place additionally carries
   `guid`, additive in the v1 schemas. The write resolver tries `guid`, then `uex`, then the exact
   name.
8. **The personal inventory moves to a UUID FK.** `personal_inventory_item.location_uex_id` +
   `location_type` become a UUID FK; the name snapshot stays. No feature then references a place
   by an external id.
9. **Visibility of unreleased objects.** Everything is imported. An object is **visible** when its
   `system` is linked to a live-available UEX star system, `hideInStarmap` is `false` and
   `position` is set. Only a visible object appears on the starmap and can carry a new storage
   place; everything else, including objects outside any linked system, is imported and hidden. An
   admin can set an object to visible or hidden explicitly; the override wins over the rule and
   survives re-imports.
10. **Migration order.** Step A, the personal inventory's UUID FK, is independent of P4K. Step B,
    the starmap import (`starmap_object`, linking and review, the storage-place FK, the exchange
    `guid`), follows the catalogue's starmap kind and precedes the starmap (#2827) and the trade
    routes (#2824).

## Consequences

- The starmap work (#2827, #2828) gets a tree with parent FKs and world positions, P4K names in
  both languages, and from each node the linked UEX row with its terminals, prices and refinery
  flag; units on the map can reference `starmap_object` directly.
- Setting or clearing a link by hand, a visibility override and creating a storage place on a
  non-UEX node are catalogue admin mutations, audited in `AuditDomain.CATALOGUE` (ADR-0346).
- The starmap kind joins the import's last-run gauges (rows, unmatched UEX rows, open link
  reviews) in the observability spec.
- The P4K catalogue spec carries the starmap, linking and visibility rules; the exchange spec gains
  the additive `guid`; arc42 §12 gains *starmap object* (a node of the archive's location tree) and
  *storage place* (`location`, on any starmap node an admin picks).

## Amendment

- **2026-11-05, step A shipped (#3043, @greluc):** the key points at `location.id`. A referenced
  city or station without a storage place gets one marked `reference_only`, which no existing
  `location` consumer offers and the UEX sync clears once the place is live; the member picker
  offers the offered places plus the ones the owner's rows use; a row that resolves to no place
  keeps its snapshot with a `null` key (REQ-P4K-039).
- **2026-11-07, step B shipped (#3044):** `starmap_object` (V352) is imported by GUID; the
  nine UEX tables link through `starmap_object_id` and `starmap_link_manual`, columns only the
  linker and the admin's compare-and-set write, so a UEX sync never overwrites a link. A row left
  unlinked is a review case of kind `STARMAP_OBJECT` that a later run closes once the row links. A
  storage place on a node is created by an admin through `POST /api/v1/locations`, and the
  database refuses a location without a city, a station or a node (`ck_location_place`).
