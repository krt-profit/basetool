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
