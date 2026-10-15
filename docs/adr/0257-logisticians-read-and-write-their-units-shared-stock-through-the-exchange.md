# ADR-0257 — Logisticians read and write their units' shared stock through the exchange, by name

- **Status:** Accepted — implementation pending (PR 2–4 of the org-stock work, REQ-XCH-042…047).
  Amends [ADR-0220](0220-external-clients-see-only-anonymised-org-demand-and-the-location-list.md)
  (decision 1, "no requester, no order title, no free text") and narrows the own-data-only rule of
  REQ-XCH-009.
- **Date:** 2026-10-15
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-009, -018,
  -042…-047) · [`inventory-lager.md`](../specs/inventory-lager.md) ·
  [ADR-0218](0218-exchange-sync-semantics.md) · [ADR-0224](0224-the-change-feed-is-a-trigger-written-key-log.md) ·
  [ADR-0230](0230-an-exchange-stock-lot-is-everything-the-member-holds-at-its-quality.md) ·
  [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)

## Context

A supply officer wants to see and keep their unit's shared Lager stock in VerseKit, the program
they farm and trade with. The exchange so far serves a member's own rows only, and the one piece
of org data it serves, the demand, is anonymised (ADR-0220). Coordinating a unit's stock needs
more: who holds what, which orders and missions it is earmarked to, and the notes on it — exactly
what a Logistician sees in the web's Globales Lager. Over the exchange the member holds no stored
role (REQ-XCH-009), so a web role cannot decide anything there by itself.

## Decision

1. **Org stock.** A new read surface serves the shared (non-personal) Lager stock of the units the
   member is a **direct** member of, one pool per unit; personal rows of other members never leave.
   Oversight reach — a Bereich rank over its child units, the Organisationsleitung over everything —
   does **not** widen it, although it does in the web. Lots are keyed by unit + material or item +
   location + quality + „gestohlen", and change through their own feed resource keyed by unit.
2. **Named details for Logisticians of the pool.** A second capability adds the single entries of a
   lot: holder, note, and the job-order and mission earmarks with the order's number and the
   mission's name. It is served only for
   a pool in which the member is a **Logistician of that unit**: the membership carries
   `is_logistician` or a leadership rank (SK lead, a Staffel rank, a Bereich rank, OL) — the rule the
   web's authority converter applies, computed from the database by one shared method and without the
   cascade. The `OFFICER` realm role alone does not count. The holder is named by the name the web
   shows and identified by a pseudonym per client: an HMAC of the user id under a random salt stored
   with the client.
3. **Writes with web parity.** A third capability lets a Logistician of the pool do what the web
   lets them do there: book in for any member of the unit, book out, sell, move to another location,
   move to another holder who is a member of one of the writer's Logistician units, add, change and
   remove earmarks, edit the note and the „gestohlen" marker. Writes go through the Lager's services,
   one entry per op with its `version`, as a batch under an idempotency key; they notify, lower
   Materialbörse offers and protect reservations as the web does. They are journaled under the
   writer, undoable by the writer and by the admin's bulk undo, and counted by the mass-change guard
   per writer and pool.
4. **Named demand details.** The demand feed gains the same split: a details capability adds, for the
   orders whose responsible unit is one of the member's Logistician units, a per-order breakdown with
   the order's number, requesting unit, Auftraggeber (`handle`) and comment; assignees stay unnamed.
   Without it the feed stays as anonymised as ADR-0220 made it.
5. **Members can withhold their name.** A profile setting, off by default, lets a member keep their
   name away from connected applications. Their entries then carry no holder and no note and they
   are left out of the member roster a client books for; their entries stay writable by id. An
   order's `handle` and comment are free text, not a member account, and are not covered.
6. **Caching** follows ADR-0220: a client may keep any org data for up to 7 days; a departure
   revokes access at once.

## Consequences

- A Logistician can coordinate their unit's stock from an approved client without the web; the
  client sees what the web shows them, for their own units only.
- Names of other members now reach a third-party program on a member's PC. The privacy notice and
  the records of processing name it, and each member can withdraw their name.
- The exchange gains its first writes to other members' rows. The journal, the undo, the guard and
  the audit, which name the writer and the client, are what keep a faulty client release
  recoverable.
- Two rules now decide "is a Logistician of this unit": the web's converter and the exchange. They
  share one method so a new rank cannot drift between them; the exchange deliberately omits the
  cascade.
- A new feed resource and triggers on the Lager's allocation tables add write cost to every Lager
  mutation.

## Alternatives considered

- **Mirror the web's reach, oversight included.** Rejected by the owner: org data leaves only for
  units the member belongs to.
- **Names for everyone with the read capability.** Rejected: names go only to those who coordinate
  the pool.
- **The `OFFICER` realm role as the detail gate.** Rejected: it is not readable over the exchange and
  carries no unit; every officer of a unit holds a leadership rank, which the Logistician rule covers.
- **Details tied to the role alone, without a capability.** Rejected by the owner: a client is
  approved for named data separately.
- **Opt-in for names.** Rejected: the details would start out empty where coordination needs them.
- **Set the quantity of an org lot, as `/me/stock` does.** Rejected: the server would choose whose
  entries shrink, which the holders cannot follow.
- **A snapshot with an ETag instead of a feed.** Rejected: every change would reload the whole pool.
