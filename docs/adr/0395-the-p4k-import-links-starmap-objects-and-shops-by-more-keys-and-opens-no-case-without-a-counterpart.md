# ADR-0395 — The P4K import links starmap objects and shops by more keys and opens no case without a counterpart

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** @greluc
- **Related:** spec [`p4k-catalogue.md`](../specs/p4k-catalogue.md) (REQ-P4K-016, REQ-P4K-035,
  REQ-P4K-043, REQ-P4K-060) ·
  [ADR-0345](0345-the-p4k-starmap-tree-leads-and-uex-locations-link-to-it.md) ·
  [ADR-0347](0347-p4k-shops-join-uex-terminals-by-place.md) ·
  [ADR-0392](0392-the-p4k-import-resolves-ship-identity-conflicts-itself.md)

## Context

ADR-0345 links a UEX location row to the starmap object of its normalised name, type family and
nearest linked parent; ADR-0347 links a shop to the terminal of its exact place by name. Both open a
review case for every row they leave unlinked. After the 4.10.197 APPLY, 1,722
`STARMAP_LINK_NOT_FOUND` and 6 `STARMAP_LINK_AMBIGUOUS` cases were open, and 876
`SHOP_LINK_NOT_FOUND` and 14 `SHOP_LINK_AMBIGUOUS`. A replay against the LIVE 4.10.200 catalogue
and the public UEX lists of the same day found:

- 768 starmap cases belong to UEX star systems the game does not have, and 824 to terminals, which
  are never starmap objects. 60 are orbits that are no planet. No admin decision settles these.
- The rest differ only in naming (`Area 18` / `Area18`, `Pyro Gateway (Stanton)`, the nickname
  `Grim HEX`, `#4RO-NVX` / `#4R0-NVX`, `Deakins Research` / `Deakins Research Outpost`), in a
  parent UEX names but the game tree lacks (the Lagrange stations hang below the star), or in an
  unplaced duplicate record beside the placed one.
- 251 shop cases follow from unlinked stations and outposts; others sit one container away from
  their terminal (a plaza inside a city), between an item and a commodity terminal of one name, in
  one of two districts, or behind UEX's own vocabulary (`Medical Shop` for a pharmacy).
- 302 shops have no terminal around them at all, and 148 of the cases left after these rules
  belong to shops without an item list, whose link would show on no item page.

The catalogue carries the facts for most of this: the position of every placed object, the
`entityName` that places it and the `containers` that hold a shop. The contract of catalogue
version 2 requires both fields.

## Decision

The import links by more keys, and a link case opens only where an admin's decision could settle
something.

1. **More keys for the starmap link (REQ-P4K-035).** A row's names are its UEX name, the name
   without a trailing `(<star system>)` group and its UEX nickname, each matched by its normalised
   and then its compact form. Its objects are looked for below its nearest linked parent, then
   below each further linked parent up to the star system; else the one object of the name in the
   star system of any type; else the one object whose name differs by `o` read as `0`, or by whole
   trailing words. Of several candidates the only placed one wins.
2. **Terminals and orbits are not matched (REQ-P4K-035).** A terminal's place is its nearest
   linked parent, so it never takes a name match of its own. An orbit that shares its UEX id with
   a planet takes the planet's link; any other orbit links nothing.
3. **More places and names for the shop link (REQ-P4K-043).** A shop's place is refined by the
   containers that place a deeper object, and its terminal is looked for there and at the
   ancestors up to the first celestial body. A tie between equal names is broken by the UEX
   terminal type the shop's commodity facts call for, then by the district its containers name;
   without a name match, a fixed alias table of UEX's vocabulary applies.
4. **No case without a counterpart (REQ-P4K-060).** No case opens for a terminal, an orbit, a row
   of a star system that links to no object, a row UEX does not report live without a candidate, a
   POI or outpost that no terminal names and that holds no storage place, a shop without a place
   or item list, and a shop around which no terminal is free. Such a row stays an `UNMATCHED` row
   of the change report, and the next APPLY closes its open case as `RESOLVED`.

## Consequences

- The replay leaves no starmap case and 16 shop cases of 892, six of which the `landingservices`
  alias settles; 529 of the 1,000 placed shops link instead of 108, so far more shops show a
  terminal name under "available at".
- The rules only add links and drop cases; an admin's link, clear and case resolution stay as they
  are, and a manual link still wins.
- The alias table is curated: a new UEX shop name that does not match is a case until it is added.
- `entityName` comes from the run's catalogue and is not stored, so a catalogue without starmap
  objects refines no shop place.
- The review workspace still proposes terminals of a shop's exact place only.
