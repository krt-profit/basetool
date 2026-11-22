# ADR-0404 — Only a ship type the game files list is offered and set anew

- **Status:** Accepted
- **Date:** 2026-11-22
- **Deciders:** @greluc
- **Related:** REQ-P4K-080 · REQ-P4K-013 · REQ-P4K-031 · REQ-P4K-039 · ADR-0336 ·
  `catalogue.api.OfferedShipTypes`

## Context

The ship-type pickers (profit calculation, hangar, mission units, mission templates) offered every
`ship_type` row that was neither hidden nor retired by the P4K sweep. Rows reach that table from
three writers: the P4K import, the UEX vehicle sync and the SC Wiki vehicle sync. UEX and the Wiki
create a row for every vehicle they list, also for ships that cannot be flown yet. The P4K sweep
retires only rows the P4K once matched (REQ-P4K-013), so a row only UEX or the Wiki wrote stays in
every picker for good.

Production on 2026-11-22: 350 offered ship types, 64 of them without a P4K GUID. 43 are concept
ships by UEX's `is_concept` flag (Hull D, Hull E, Kraken, Odin, Orion, Pioneer, …), 8 are stale
second rows of game ships (Aurora CL/ES/LN/LX/MR beside "RSI Aurora Mk I …", Constellation Mk V
Centaurus, Skylark, Caterpillar Best In Show Edition), 6 are ATLS rows the current reader does not
yet export, and 7 are editions and modules the game carries as no vehicle of their own. Because the
P4K import leads the name of a matched row (REQ-P4K-031) and UEX names a row by its short name,
every one of them also showed without the manufacturer word, beside "MISC Hull C" and the other
P4K-named rows. 170 hangar ships and one mission unit reference them.

UEX's `is_concept` flag was stored but read by nothing.

## Decision

**A ship type is offered when the game files list it: it holds a P4K GUID and is neither hidden
nor retired.** The rule has one Java form, `OfferedShipTypes#isOffered`, and one JPQL form,
`OfferedShipTypeJpql.OFFERED_SHIP`, which the ship-type list and search use. Every picker reads
that list, so the rule reaches all of them at once.

**Every write that sets a ship type anew runs the guard** `OfferedShipTypes#requireOffered`, or
`requireOfferedOrCurrent` when it edits an entry: a hangar ship created or retyped, a mission unit
typed without a ship, a mission template unit that names a type the template did not hold. A
refusal answers `400 CATALOGUE_SHIP_TYPE_NOT_OFFERED`. The Exchange plans such an upsert as
`unmatched` so one op cannot fail its batch, and its undo restores a ship only with its current or
an offered type, as it already does for places (REQ-P4K-039). The hangar import resolves entries
against the offered types and the types the member's importable ships already have, and adds no
ship of a type that is not offered.

**Existing references stay.** A ship, unit or template keeps its type, stays editable while it
keeps it, and shows "Nicht im Spiel" through `ShipTypeDto.notInGame`. Nothing is deleted or
rewritten.

**The P4K catalogue alone decides.** The UEX `is_concept` flag decides nothing.

## Consequences

- The 43 concept ships, the 7 editions and modules and the stale rows leave every picker. Their
  hangar ships show "Nicht im Spiel" until a P4K import matches or merges their row.
- ATLS and ATLS GEO leave the pickers until a P4K reader that exports them (p4kx v0.5.0) has been
  imported. Their hangar ships keep their type meanwhile.
- A ship a game build adds is offered only once a P4K import of that build has run; UEX listing it
  first is not enough.
- A stale second row of a game ship is shown as not in the game until the P4K import merges it into
  the row of its record (REQ-P4K-053); a twin rule the import lacks for such a row is a catalogue
  fix, not a reason to offer the row.

## Alternatives considered

- **Filter on UEX's `is_concept`.** Rejected: it hides concept ships but keeps the stale twins, the
  editions and the modules, and depends on a community flag that lags game builds. The game files
  are the source REQ-P4K-031 already makes leading for a ship's identity.
- **Offer a type the game files list, or one UEX lists as flyable.** Rejected by @greluc: it keeps
  the same clutter, and the one real gap, ATLS, closes with the next reader version.
- **Filter only the profit calculation.** Rejected by @greluc: a concept ship is as wrong in the
  hangar, a mission or a template, and one rule for every picker keeps them consistent.
