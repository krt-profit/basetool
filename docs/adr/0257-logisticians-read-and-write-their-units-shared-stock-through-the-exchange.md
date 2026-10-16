# ADR-0257 — Logisticians read and write their units' shared stock through the exchange, by name

- **Status:** Accepted — the read, the details, the roster and the ops `book-in`, `book-out`, `sell`,
  the moves, `note` and `stolen` built; the earmark ops, the undo, the guard and the demand details
  pending (REQ-XCH-042…047).
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

## Amendment 1 (2026-10-16) — how the org-stock feed is keyed and when it re-announces a pool

1. **One log, keyed by unit.** The org-stock feed shares `exchange_change` with the member feeds of
   ADR-0224. Its rows carry `resource = 'ORG_STOCK'` and the unit in `org_unit_id`, and no member;
   a check constraint keeps the two kinds apart. Sequence, watermark, attribution, horizon and the
   nightly purge stay one mechanism, so no second retention job and no second cursor format exist.
   A feed page reads the units the member belongs to at that request.
2. **A join re-announces the pool.** Entries are keyed by unit, so the feed cannot tell which units
   a cursor already covered. Inserting a membership announces every lot of the unit's pool, and a
   member whose cursor predates the join receives the pool through the feed instead of a 410 or a
   new snapshot. The other members of the unit receive those lots once more, unchanged; joins are
   rare, so the cost is accepted. A rename re-announces the pool the same way, for the lots'
   `unit.name`.
3. **A tombstone names only the reader's installations.** A removal by another member's client
   carries the channel and the client id, never that member's installation id.

Rejected: a cursor that encodes the units it covered — it would change the cursor format clients
already store, and the snapshot cursors issued before the feed existed would not carry it.

## Amendment 2 (2026-10-16) — how the named details are served

1. **An additive capability.** `exchange.orgstock.details` gates no route: the org-stock route
   checks it after the gate admitted `.read` and answers without entries when it is missing, never
   with `403`. Like every capability it opens the routes any capability serves.
2. **The salt lives only in the database.** `exchange_client.holder_ref_salt` is filled by a column
   default when the row is inserted (and `V297` gave every existing client one); the entity does not
   map it, so neither the registry DTOs, the mirror nor a log line can carry it. The `ref` is the
   unpadded base64url HMAC-SHA256 of the user id's 16 bytes.
3. **Details reach the feed through triggers too.** A holder's rename, a change of their name
   withholding and the rename of an earmarked mission announce the affected lots, like every other
   org-stock change; so does a membership that starts or stops making its member a Logistician of
   the unit, for the whole pool. The client never has to re-read a snapshot to see them; only a
   change of the client's own scope needs one.

Rejected: a salt column on the entity with `@ToString.Exclude` — one forgotten mapping or log
statement would leak it; a separate `/entries` route — the client would need a second request per
lot and a second cursor.

## Amendment 3 (2026-10-16) — how the writes are applied

1. **Per op, like the ships.** An org-stock change set runs its ops in order, each on its own, in
   one transaction: a refused op is reported `rejected` with its reason and changes nothing, the rest
   applies. "A stale version answers `409 VERSION_CONFLICT`" is the per-op reason of that code, as
   for stock and ships; only an optimistic-lock failure that slips past the checks answers the whole
   request with it.
2. **The Lager decides its own refusals.** The exchange checks what is its own — the writer is a
   Logistician and direct member of the named pool, the holder is on the unit's roster, the material
   and place resolve. The Lager's pool-entry commands (`PoolEntryCommands`) check the entry, its
   pool and version, the amount, the earmarks and the deduct-from plan by the Lager's rules and
   answer a refusal instead of throwing, then write through the web's own create and book-out. A
   book-out without a plan takes only the free part: the exchange never lets the Lager pick whose
   earmark shrinks.
3. **Locks.** Every holder lot a batch touches — the book-ins' and the lot of every named entry, read
   without a row lock — takes its advisory lock first, in one ascending pass; then the rows of those
   lots and the named entries are locked together in ascending id order, so no Lager path that
   locks rows can deadlock with the batch on its own row order (ADR-0229).
4. **The roster is a query parameter.** `GET /exchange/v1/org-stock/members?unit=` instead of
   `…/units/{unit}/members`, so every path of the acting-member filter stays a literal
   (REQ-SEC-080); owner decision of the same day. The targets route follows the same shape.
5. **Journal.** One row per Lager entry an op changed, keyed by the entry, under the writer; a
   merge journals each entry it folded away too, so the change can be reversed. The holder's note is
   not journaled, and the writer's data export shows the rows without the holder's id, since both
   belong to another member. Until the undo is built (#2592),
   the member's and the admin's undo leave org-stock rows untouched and do not count them.
6. **Every op counts.** The gateway counts an org-stock change set once at its limit filter and, unless
   it is a dry run, once more for every further op after the schema check; a set that does not fit into the day's rest is
   refused `429 QUOTA_EXCEEDED` with the further ops given back.

Rejected: an all-or-nothing batch that answers the first refusal for the whole request — the client
could not tell which op failed, and the existing change results already carry per-op reasons; a
path template on the acting list — it would loosen REQ-SEC-080 and every seam test for one route.

## Amendment 4 (2026-10-16) — sale, moves, note and stolen

1. **The web's services, the web's effects.** `sell` goes through the Lager's sale, so the mission
   proceeds are split and booked exactly as in the web (only for missions the writer takes part
   in); the moves go through the Lager's transfer, which carries the planned earmarks along, merges
   and tells both holders; `note` and `stolen` go through the note edit and the stolen marking. The
   pool commands check first and answer `STOCK_EARMARKED`, `STOCK_OFFERED` or `INSUFFICIENT_STOCK`
   where those services would throw.
2. **The receiving pool is the op's, not the web's pick.** A transfer for a pool stamps the
   receiving pool directly — the entry's for `move-location`, `toUnit` for `move-holder` — instead
   of the web's org-unit picker, which reads the web caller's own memberships. The new holder must
   be on the roster of `toUnit`, a unit the writer is a Logistician of.
3. **A pool change of one holder is not a move.** A `move-holder` to the entry's own holder into
   another pool is refused `NOT_PERMITTED`: the web does that as an org-unit change with its own
   audit event, which is not one of these ops. The same holder at the same place is `unchanged`, as
   are a `note` and a `stolen` that find the entry as asked; they write nothing.
4. **Relocations lock their target lot.** A move or stolen marking names the lot the stock lands
   in by the entry's lot with holder, location or marker replaced; its advisory lock and rows join
   the batch's single ascending pass (ADR-0229).
5. **What counts as a removal.** The source entry of a book-out, a sale and a `move-holder` into
   another pool; a move within the pool, a note and a marking do not.
