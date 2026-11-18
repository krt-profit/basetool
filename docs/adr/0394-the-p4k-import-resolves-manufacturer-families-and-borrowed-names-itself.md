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
- Two rows of one brand (a UEX twin beside the P4K row) are not merged by this rule; the amendment
  on UEX twins below decides them.

## Amendment — the brand in a ship's name beats its manufacturer GUID (REQ-P4K-059)

The game files swap Esperia and Vanduul: "Esperia Stinger" carries the Vanduul GUID, "Vanduul
Scythe" and the NPC Blade and Glaive the Esperia GUID. Our GUID mapping is right, so a manual fix
was overwritten by the next APPLY. The owner decided on 2026-11-17 that the brand a ship's English
name begins with wins over a contradicting GUID, as a general rule rather than a curated list. The
word must name exactly one matched manufacturer, by its name's first word, its code or its class
name; a borrowed name names none. Checked against LIVE 4.10.200, the rule changes 17 records and
two stored ship types.

## Amendment — a manufacturer UEX names differently merges into its P4K row (REQ-DATA-045)

Production held 12 manufacturer rows only UEX created beside the P4K row of their brand, because
UEX spells the brand differently: "Musashi Industrial and Starflight Concern" beside MISC (29 ship
types hung on the UEX row, 8 on the P4K row), "Vanduul Clans" beside Vanduul, "Behring Applied
Technology" beside Behring, `Grey&apos;s Market` beside "Grey's Market", and eight more. The rules
above leave them, and no review case opens for them.

The owner decided on 2026-11-17 that such double listings merge automatically into the row holding
the game identity, that their references move, that the UEX company id stays known, and that every
merge is audited:

1. **The twin rule.** A row is a twin when it holds a UEX company and no P4K GUID, SC Wiki UUID,
   Wiki identity or P4K alias, and its folded name (HTML entities decoded, `&` as "and", every
   other run of non-alphanumerics one blank, case ignored) names exactly one row holding a P4K
   GUID, by that row's name or by one of its Wiki names. Against the production rows of 2026-11-17
   the rule finds exactly the 12 twins and no other pair; nine are named only by a Wiki name,
   three by the name.
2. **The merge.** After a UEX manufacturer sync with a complete census, each twin merges in its own
   transaction through the review-case merge, called without a case. The twin's UEX company stays
   mapped to the surviving row, so the next sync resolves it there.
3. **Prevention.** A company that no alias, name or code places joins the row its name names by
   the same rule instead of creating a twin.
4. **Audit.** Each merge records `CATALOGUE_MANUFACTURER_MERGED`.

The rows keep the P4K name, so the UEX name of a merged company is reported as a name disagreement
on each sync, like every other UEX name the P4K overrides (REQ-P4K-020).
