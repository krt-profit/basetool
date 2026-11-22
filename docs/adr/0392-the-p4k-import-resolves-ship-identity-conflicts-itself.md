# ADR-0392 — The P4K import resolves ship identity conflicts itself

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** @greluc
- **Related:** spec [`p4k-catalogue.md`](../specs/p4k-catalogue.md) (REQ-P4K-007, REQ-P4K-010,
  REQ-P4K-016, REQ-P4K-032, REQ-P4K-050) · [ADR-0335](0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
  [ADR-0341](0341-the-p4k-catalogue-leads-a-ship-types-base-data-and-ship-types-match-by-class-name-before-name.md) ·
  [ADR-0379](0379-a-wiki-vehicle-follows-its-p4k-guid-across-a-game-uuid-change.md)

## Context

ADR-0335 lets the P4K import match and stamp, but never merge: every ambiguity becomes a review
case. For ships this did not hold up. After the 4.10.2 APPLY, 363 ship cases were open. Checked
against the LIVE 4.10.200 catalogue:

- 328 were copies of a ship that already has a ship type: NPC crews (`*_PU_AI_*`, `*_AI_Super`),
  mission targets (`*_Unmanned_*`), wrecks, tutorial and event editions. They carry the ship's
  in-game name and usually its variant group. No admin decision can turn them into anything but
  "this is that ship".
- 22 belonged to records the catalogue no longer lists. Ship cases were never closed by a run, unlike
  those of manufacturers and of rows without a GUID.
- Six ship types held the GUID of a same-name edition of their ship (`ANVL_Carrack_BIS2950`,
  `RSI_Polaris_Collector_Military`, …) while the base record was unclaimed.
- Five rows the P4K once seeded are NPC variants nobody references.
- The 4.10.200 build drops `AEGS_Hammerhead` and the old Constellation Mk IV records in favour of
  `_GS` records. The sweep would retire the stored ship types, 35 hangar and mission references on
  the Andromeda alone.

The catalogue carries the facts that decide all of these: `variantOf` groups structurally
identical ships, `playability` says which ships a player can own, and the in-game name says what a
member sees.

## Decision

The P4K import decides ship identity itself wherever the catalogue's facts decide it, and leaves a
case only where they do not.

1. **Covered copies (REQ-P4K-050).** A ship record is covered by the stored ship type the class or
   name step finds when both carry the same in-game name and the record is a variant of a group
   that ship type holds a member of, or a ship no player owns. It gets no row and no case. The base
   of a group is never covered by a ship type holding one of its variants.
2. **Self-settling ship cases (REQ-P4K-050).** An APPLY closes the open ship cases it no longer
   finds and reopens closed ones it finds again, as it does for manufacturers; an admin's decision
   is never touched.
3. **Identity follows the base and the successor.** A ship type stamped with a same-name variant
   whose base is unclaimed moves to the base GUID; a ship type whose GUID left a complete catalogue
   follows the single unclaimed player record carrying its in-game name.
4. **Seeded NPC rows retire themselves.** A row an earlier seeding created whose record no rule
   would create any more, and which nothing references, is hidden instead of reviewed.
5. **Twins converge.** Two ship types that the catalogue shows to be the same ship are merged by a
   ship-type merge that moves every reference through the owning modules and keeps the retired id
   resolvable.

## Consequences

- The review list holds only what the catalogue cannot decide; the next APPLY of 4.10.200 closes
  the copies and the cases of vanished records.
- A covered record is visible in the change report as `COVERED`, so nothing disappears silently.
- REQ-P4K-007's "the import never merges" stays true for rows two GUIDs could claim; decisions 3–5
  are bounded by unique candidates and fall back to a case otherwise.
- Decisions 3–5 need the ship-type merge and the reference probe across modules; they land after
  decisions 1 and 2.

## Amendment — a `_GS` record supersedes the old record of its ship (2026-11-17)

LIVE 4.10.200 lists `RSI_Constellation_Phoenix` and `RSI_Constellation_Phoenix_GS`, and the same
pair for the Taurus. Each pair consists of two player ships heading no variant group under one
in-game name, and each record has a row of its own. Decision 5 alone would keep both rows until the
old record leaves the files. @greluc decided that the `_GS` record supersedes the old one at once:
the row of the old record merges into the row of the `_GS` record, and the old record is covered
from then on (REQ-P4K-053, REQ-P4K-050). This is the one exception to "two rows a listed GUID each
holds stay apart"; it applies only to the exact `<class>` / `<class>_GS` pair of one in-game name.

## Amendment — a vanished edition belongs to its ship (2026-11-17)

Three rows held records LIVE 4.10.200 no longer lists, with no player base of their name: the old
Taurus Wikelo War Special (its successor is a variant of the same name), the Hammerhead 2949 Best
In Show Edition (now a livery of the Hammerhead) and the Phoenix Emerald (now only a shop display).
@greluc decided to merge all three: a departed row also follows the single player record of its
name, a variant included, and a vanished edition — class name extending a base's by a suffix, name
beginning with the base's in-game name — merges into its ship. The UEX vehicle id a merge leaves
behind stays known, so UEX keeping such an edition as a listing of its own creates no new ship type
(REQ-DATA-043).

## Amendment — the UEX vehicle sync settles a merged ship itself (2026-11-22)

The apply of 18 Nov merged the Phoenix Emerald edition into the new `_GS` row of the Phoenix before
the old Phoenix row. The survivor, holding no UEX listing yet, took the edition's, and the
Phoenix's own listing was left behind. From then on every UEX run refused that listing: its stale
UEX game UUID contradicted the row's P4K GUID, which outranked it only on a row found by the
listing's own id. Because the UEX sweep stopped at the first incomplete slice, the item, price and
refinery slices stopped with it. @greluc decided on 2026-11-22:

- A vanished edition never hands its UEX listing to the row it merges into; the merge always leaves
  it behind, whatever order the merges of one run take.
- A listing whose id a merge into the row it finds left behind is that row's own listing.
- A P4K-confirmed GUID outranks a differing UEX game UUID on every row a UEX vehicle reaches, unless
  another listing of the same fetch holds the row and no merge left this vehicle's id behind.
- A partially applied vehicle slice no longer stops the UEX sweep; the sweep still fails naming it.
