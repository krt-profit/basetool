# ADR-0394 — The P4K import resolves manufacturer families and borrowed names itself

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** @greluc
- **Related:** spec [`p4k-catalogue.md`](../specs/p4k-catalogue.md) (REQ-P4K-016, REQ-P4K-020,
  REQ-P4K-058) · [ADR-0338](0338-the-p4k-catalogue-leads-a-manufacturers-identity-name-and-description.md) ·
  [ADR-0392](0392-the-p4k-import-resolves-ship-identity-conflicts-itself.md) ·
  [ADR-0393](0393-p4k-item-cases-open-only-where-an-admin-can-decide.md)

## Context

After the 4.10.2 APPLY, 1,013 manufacturer cases were open. 993 are paint-logo and shop records
the import already treats as auxiliary; they close with the next APPLY. The other 20 never closed:

- **Brand siblings (10).** The catalogue lists nine brands twice under one name key with the same
  or a blank code (`RSI` / `RSI_2025`, `CRUS` / `CRUS_ENV`, `MISC` / `MISC_ENV`, `MRAI` / `MRAI_2025`,
  `XNAA` / `XIAN`, …) and `ASD` / `ASD_LO` under a key no record owns. The name step saw the shared
  name as repeated, and rows held the minor sibling while the primary stayed unclaimed. As a
  result, 12 canonical GUIDs were never indexed: 2,249 items and 313 ship records named no
  manufacturer, and ship base repairs for RSI, MISC, Crusader, Mirai, Greycat, Gatac and Aopoa were
  blocked.
- **Borrowed names (6).** `FSKI`, `MXOX`, `PRAR` and `TRAS` carry "Aegis Dynamics" as name and
  description, `ROO` "Sakura Sun", and `GHEX` "ArcCorp". The import opened name conflicts on their
  own rows and wrote Aegis's description onto them; the ArcCorp row held `GHEX` and its code.
- **Departed records (4).** Their cases stayed open because only auxiliary GUIDs were settled.

## Decision

1. **A brand's sibling records are one brand.** Canonical records sharing a `@manufacturer_Name…`
   key with the same or a blank code form a family. Its primary is the record whose class name is
   the key's suffix, else the shortest class name. Siblings are covered by the brand's row and
   indexed to it. A row holding a sibling moves to the free primary.
2. **A borrowed name is never led.** A record sharing a brand's key under another code does not
   match by name, lead the name or descriptions, or get created. A row holding it that carries the
   brand's name moves to the free brand.
3. **Manufacturer cases settle per APPLY**, after items and ships, like ship and item cases.

The matcher gains one hook for the moves: `P4kMatchRules.follower`, the row that follows a GUID it
does not hold. Covered records hand their GUID to the caller, so the manufacturer reconciler
indexes them.

## Consequences

- The 20 remaining cases close with the next APPLY, and the 12 held GUIDs resolve. Items, ships
  and ship base repairs of those brands get their manufacturer; Aopoa's abbreviation becomes
  `XNAA`, and ArcCorp's becomes `ARCC`.
- Two rows of one brand (a UEX twin beside the P4K row) are not merged by this rule; that is a
  separate decision.
