# ADR-0393 — P4K item cases open only where an admin can decide something

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** @greluc
- **Related:** spec [`p4k-catalogue.md`](../specs/p4k-catalogue.md) (REQ-P4K-007, REQ-P4K-009,
  REQ-P4K-016, REQ-P4K-054) · [ADR-0335](0335-the-p4k-import-reads-catalogue-version-2-behind-a-synchronous-live-only-gate-and-records-every-run.md) ·
  [ADR-0392](0392-the-p4k-import-resolves-ship-identity-conflicts-itself.md)

## Context

After the 4.10.2 APPLY, 1,836 item cases were open: 1,424 `GUID_CONFLICT` and 412
`SEEDED_NOT_CREATABLE`. Checked against the LIVE 4.10.200 catalogue:

- 1,381 conflicts came from 14 rows an earlier seeding created for one non-player record each
  ("Door Control", "Door", "Corpse", "Standard Container" …). Every other record of that name found
  the row in the name step and became a conflict, although the row holds its own listed GUID and can
  never be theirs.
- 22 were copies of a stored item (`_tow`, `_ai_exclusive`, `_LowPoly`, `invisible_…`) whose name
  step hit the item's own row.
- Item cases were never closed by a run, unlike ship, starmap and shop cases, so a case stayed open
  after its cause was gone.
- With "create new items and ships" ticked, the seeding rule would open `UNCLEAR_PLAYABILITY` for
  about 8,700 unmatched `special` records. None of them has a usable name, and nothing an admin could
  decide turns them into an item.

The owner decided on 2026-11-17 that the import opens no case where the game offers nothing to
decide: a record without a name, or one no player can obtain.

## Decision

1. **A row holding another listed item is no candidate (REQ-P4K-054).** The class-name and name steps
   skip a stored item that holds the GUID of another item the catalogue lists. When the two records
   share name, type, sub-type and size, the row covers the record (`COVERED`, no row, no case);
   otherwise the record is unmatched and the seeding rule applies. The matcher gains a per-kind
   candidate predicate; ships, manufacturers and the other kinds keep considering every row.
2. **Item cases settle themselves (REQ-P4K-016).** After the item pass an APPLY closes the open item
   cases of the reasons `GUID_CONFLICT`, `SEEDED_NOT_CREATABLE` and `UNCLEAR_PLAYABILITY` that the run
   no longer raises and reopens the ones it raises again, the way ship cases settle; a partial items
   kind settles only the GUIDs it lists, and an admin decision is never touched. A conflict the SC
   Wiki / UEX duplicate merge raises outside a run reopens on its next refusal.
3. **Unclear items need evidence (REQ-P4K-009).** A `special`, `null` or unknown item goes to review
   only when a shop's item list names it or a blueprint produces it; otherwise it is only counted. An
   item without a usable name — placeholders such as `PH - …` and `PLACEHOLDER - …` included — is
   never created and never reviewed. Ships keep their rule.

## Consequences

- The 1,381 + 22 conflicts close with the next APPLY and do not come back with new namesakes; the
  412 seeded rows stay cases until a later rule decides them.
- A ticked "create new items and ships" run reviews only the few unclear items a player can obtain.
- Covered copies show in the change report as `COVERED`; they no longer reach the review list.
- A Wiki-raised conflict can close at an APPLY and reopen at the next Wiki sync, so it is visible
  again within a day.

## Amendment — an item's identity follows its own facts (REQ-P4K-055)

35 further conflicts came from rows stamped with a GUID their own facts do not describe: 14 rows
whose SC Wiki UUID and class name name the base record while the row holds a same-name copy's GUID
(the run's 14 `IDENTITY_MISMATCH` rows), 7 rows holding a GUID the catalogue no longer lists while
their SC Wiki UUID names a listed record, and 14 seeded rows whose class name names another record.

A stored item now belongs to the record its SC Wiki UUID, else its unique class name, names — when
no stored item holds that GUID and the facts do not also name the GUID the row holds. The matcher
asks the kind's rules for that GUID (`P4kMatchRules.followedGuid`): the held GUID no longer finds
the row, and the named record takes it, reported as `CHANGED p4k_uuid`. This is the item form of
ADR-0379's "identity follows the GUID the Wiki names"; ships keep their own moves (REQ-P4K-051).

## Amendment — unreferenced seeded junk hides itself (REQ-P4K-056)

The 412 `SEEDED_NOT_CREATABLE` item cases are rows an earlier seeding created for records that are
now `null` (371) or `mission` (41): world objects (consoles, elevator controls, screens), loadout
parts, turret parts, character appearance. Only two of their records are obtainable by the
catalogue's facts. The owner decided on 2026-11-17 that unused leftovers hide themselves.

A seeded item whose record the seeding rule would only count, and that no member row references,
is hidden before matching, the item form of REQ-P4K-052; a referenced one stays a case. The member
modules answer through a new observer SPI, `GameItemReferenceParticipant`, which the six modules
holding member item references implement next to their `GameItemReassignmentParticipant`. The SC
exchange journal is not asked: a hidden item still resolves by id, so hiding breaks no journal
entry.

## Amendment — an item every source dropped leaves the pickers (REQ-P4K-057)

597 visible items had no P4K GUID, a Wiki deletion stamp and no live UEX id: 528 ship records an
older Wiki served as items and 69 debris and AI templates. The pickers offered them beside the real
ship names. Such an item is now not selectable, the same way a retired one is not, and selectable
again once a source lists it.

## Amendment — an item UEX lists twice keeps one row (REQ-DATA-044)

Production held 995 `UEX_ONLY` items; 301 visible ones stood beside the game row of the same item
because UEX lists the item twice: one listing sits on the game row, the second created a row of its
own. Members saw both, and the SC Wiki item sync reported about 88 `MULTI_MATCH_AMBIGUOUS`
events a day for them. The uuid-less merge of REQ-DATA-034 never took them, since their game row
already holds a UEX listing. The owner decided on 2026-11-17 that such double listings merge
automatically into the row holding the game identity, the way REQ-DATA-043 keeps one ship.

After every complete UEX item fetch the UEX item sync merges such a twin into the game row its
listing's game UUID names (only a row of the same name; a UUID on a row of another name merges
nothing), else into the single visible game row carrying its name, else into the `_GS` row of a
`<class>` / `<class>_GS` pair of that name. References and the twin's other prices move to the game
row, which keeps its own listing and its own price at a shared terminal. The twin's UEX item id
stays on the redirect, so the UEX item sync never creates the row again and the item-price sync
reads its prices for the game row. Every merge is audited as `CATALOGUE_ITEM_MERGED`.

A twin whose game row's own listing left UEX while the twin's listing stays is not merged by a
name rule; with a game UUID its listing takes the row over instead.
