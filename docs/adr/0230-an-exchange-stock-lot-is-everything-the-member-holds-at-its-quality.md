# ADR-0230 — An exchange stock lot is everything the member holds, at its own quality

- **Status:** Accepted — owner decision 2026-10-02.
- **Date:** 2026-10-02
- **Deciders:** @greluc
- **Amends:** [ADR-0218](0218-exchange-sync-semantics.md), decision 2 and its consequences
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-009`,
  `REQ-XCH-011`, `REQ-XCH-013`, `REQ-XCH-016`) · [`inventory-lager.md`](../specs/inventory-lager.md)
  (`REQ-INV-028`) · [ADR-0224](0224-the-change-feed-is-a-trigger-written-key-log.md) (the change
  feed) · [ADR-0229](0229-exchange-stock-writes-take-an-advisory-lock-per-lot.md) (the lot lock)

## Context

ADR-0218 decided two things about a stock lot that turned out wrong in use, both reported by the
VerseKit author and the owner in the first days after the go-live:

1. **„Trade goods are stored at a fixed quality 0."** The rule came from VerseKit's data model, which
   keeps trade cargo without a quality apart from mined resources with one. The backend recognised
   a trade good as a material with a UEX commodity id — but UEX's commodity catalogue is where the
   Basetool's material catalogue comes from, ores, gems and refined metals included. 206 of 333
   materials in production carry such an id: every REFINED one, 32 of 33 RAW ones and 144 of 270
   NO_REFINE ones. So the rule
   stored nearly every material at quality 0, whatever the client sent: three lots of one mineral at
   qualities 561, 682 and 371 became one lot without a quality, the second and third op of such a
   batch met the first one's book-in as a `VERSION_CONFLICT`, and a lot the feed showed at its
   quality could not be written at it at all. Every client stock write in production until this
   decision — 203 journal entries over 36 lots of two members — landed at quality 0. The Lager itself
   never made the distinction: every material row carries a quality from 0 to 1000.
2. **A lot covered the member's personal rows only.** Stock the member had booked into a unit's
   shared pool was missing from the client, although the member's „Mein Lager" in the web shows it
   and the member may book it out, rebook it or note it there like any own row. In production 400 of
   475 rows are shared and 75 personal; the owner's own stock was invisible to the client
   altogether.

## Decision

1. **Every material lot keeps the quality the client sends**, whatever the material's kind. An item
   lot keeps quality 0. The server no longer classifies a material for its quality at all; a client
   that does not know a quality sends 0.
2. **`materialKind` stays a read-only classification for the client's own lists** and grows by the
   UEX flags the Basetool already stores: `mineral`, `harvestable`, `raw`, `refined`, `buyable` and
   `sellable`, each left out when UEX does not know the material. `commodity` keeps its field and
   its value and is documented as what it is: „listed in UEX's commodity catalogue". The additions
   are optional properties of `material-kind.schema.json`, so the v1 contract stays compatible.
3. **A lot is every row the member holds, personal and shared**, across org-unit pools: material or
   item + location + quality + stolen over `inventory_item.user_id = member`. Rebooking a row
   between personal and shared does not change its lot. Book-ins stay personal rows without an org
   unit. A book-out takes the personal rows first, then the rows without an org unit, then the
   oldest, and never more of a row than its job-order and mission reservations leave free.
4. **The change feed follows the lot.** The `inventory_item` trigger records a lot when a row of the
   member is inserted, deleted, changes its amount or moves to another lot; a change that touches
   none of these — personal ↔ shared, the org unit, a note — records nothing. `V260` announces every
   lot that already holds shared rows once, as a `system` change, so a client whose cursor predates
   the change still receives those lots.
5. **Stock already stored at quality 0 is not migrated.** The quality a client sent was never
   recorded, so it cannot be restored. A client sends the fall of the quality-0 lot and the rises of
   the right lots in one batch, which the guard counts as a move, or the member corrects the rows in
   the web.

## Consequences

- A client sees and can set what the member holds in the web, at the quality it really has.
- A client can now **book out** the member's shared rows, which the org's views count. It is the
  member's own stock and the member may do the same in the web; reserved stock is never taken
  (`STOCK_EARMARKED`), Materialbörse offers follow the book-out audited, and the mass-change guard,
  the journal and the undo apply as before. A client can still **book in** only personal rows, so it
  adds nothing to an org-wide view. The spec's threat model records this as accepted.
- The stock lot count the mass-change guard divides by now counts shared lots too, so the guard's
  20 % threshold is reached later for a member with much shared stock.
- ADR-0218's accepted distortion — a trade good at quality 0 lowering the Lager overview's average
  once rebooked — is gone for new writes; rows booked before this decision keep it until corrected.

## Alternatives considered

- **Keep the quality rule and classify trade goods by the UEX flags** (a commodity that is neither a
  mineral, harvestable, raw nor refined). Rejected: it keeps a distinction the Lager does not make,
  depends on UEX's flags being right, and draws an arbitrary line for materials that are both mined
  and traded at a terminal.
- **Expose shared rows read-only.** Rejected by the owner: a lot the client sees but cannot set
  would make every `set-quantity` on it a conflict between what the client counts and what it may
  change.
