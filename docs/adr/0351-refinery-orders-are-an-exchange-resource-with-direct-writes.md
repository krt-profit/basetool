# ADR-0351 — Refinery orders are an Exchange resource with direct writes

- **Status:** Accepted (implementation pending, epic [#3163](https://github.com/krt-profit/basetool/issues/3163))
- **Date:** 2026-11-08
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-051…056,
  -009, -021, -022, -023, -030) · [`refinery-orders-overview.md`](../specs/refinery-orders-overview.md)
  (REQ-REFINERY-022, -027…029) · [`security-and-access.md`](../specs/security-and-access.md)
  (REQ-SEC-039) · [`audit.md`](../specs/audit.md) (REQ-AUDIT-001) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md) ·
  [ADR-0218](0218-exchange-sync-semantics.md) ·
  [ADR-0219](0219-the-exchange-contract-grows-additively-under-a-path-major-version.md) ·
  [ADR-0224](0224-the-change-feed-is-a-trigger-written-key-log.md) ·
  [ADR-0257](0257-logisticians-read-and-write-their-units-shared-stock-through-the-exchange.md) ·
  [ADR-0285](0285-one-change-set-runner-for-the-exchange-writers-with-typed-journal-state.md) · decision map
  [#3128](https://github.com/krt-profit/basetool/issues/3128)

## Context

A desktop client that reads the running game — VerseKit first — sees a member's refinery work
orders as they are set up, while they run and when they are collected. The only refinery surface of
the exchange is the draft (`POST /exchange/v1/me/drafts/refinery-orders`, REQ-XCH-019): it stages
an order for review in the browser and writes nothing, so a client cannot keep an order in step
with the game, cannot learn of a change made in the web, and re-sending one work order makes a
second draft.

The refinery module has neither a hard delete nor a record of the client behind an order, one
`@Version` locks the whole order, a create always starts `OPEN` (REQ-REFINERY-022), and no refinery
table feeds the change log. The exchange admits only `GET` and `POST` on literal paths (REQ-XCH-001,
REQ-SEC-080), and an exchange request carries no contextual grants (REQ-XCH-009), so the web's
store rule (`BookInPolicy.mayBookInFor`, REQ-SEC-039) cannot answer on one.

## Decision

1. **A resource beside the draft.** Refinery orders become an exchange resource with direct writes
   and no browser review. The draft stays as it is, for the SC Extractor and for clients that want a
   review. Only the member's own orders (`/me`) are served and written; orders for other members stay
   web-only.
2. **Three scopes.** `exchange.refinery.read` (the feed), `exchange.refinery.write` (the change
   set) and `exchange.refinery.store` (a pickup that books stock, and the two reads it needs).
   `exchange.drafts.refinery` stays with the draft.
3. **Feed.** `GET /exchange/v1/me/refinery-orders` serves every order the member owns, whatever
   created it, keyed by the order id and fed by triggers on the order and its goods (ADR-0224). The
   snapshot holds only `OPEN` and `IN_PROGRESS` orders; the feed carries every change. A hard delete
   and a move to another owner leave a tombstone; a cancel is a status change.
4. **One change set.** `POST /exchange/v1/me/refinery-orders/changes` carries the ops `create`,
   `update`, `good-add`, `good-update`, `good-remove`, `pickup`, `cancel` and `delete`, run through
   the shared runner (ADR-0285) and decided in order with one result per op. Cancel and delete are
   ops, not `DELETE` routes. An op names its order by `id` or by the client's own `clientKey`.
   References resolve exactly, never fuzzily, because no member reviews the write.
5. **Client key and client marker.** A `create` carries a `clientKey`, unique per owner and client;
   a second create with a known key is refused `ALREADY_EXISTS` and names the existing order. The
   order records the client that created it and the source and time of its last change, and the web
   shows the client's name as a chip, generically for every approved client.
6. **Two section counters.** An order's optimistic lock splits into **game data** (location, method,
   timer, status, goods) and **finances** (`expenses`, `otherExpenses`, `oreSales`). An exchange op
   echoes the counter of each section it touches, the web's edit form uses the same counters, and a
   mission link change moves neither. Within one change set an op expects the counters the earlier
   ops left.
7. **Pickup closes without booking unless store lines come with it.** A `pickup` sets `COMPLETED`
   with a `pickedUpAt` marker and the reporting client. Without store lines nothing is booked, and the
   order's status stays editable as an order completed without a store is today. With store lines it
   stores exactly as the web's store dialog does, all or nothing, and sets `storedAt`. Store lines
   need `exchange.refinery.store`; their receivers and org units are decided from the database
   (REQ-SEC-039, as ADR-0257 decides the org stock), and their job orders are those the member can
   see.
8. **Hard delete through the API only.** A `delete` removes an own order without `storedAt`, its
   goods with it, and leaves booked stock untouched; it is audited as `REFINERY_ORDER_DELETED`. The
   web keeps cancel as its only removal.
9. **Guard and undo.** Only `delete` counts as a removal for the mass-change guard (REQ-XCH-021).
   An undo recreates a deleted order under its old ids (`REFINERY_ORDER_RESTORED`), removes a create
   and rolls back an update, a goods op, a cancel and a pickup without store lines while the touched
   section's counter is unchanged, and skips a pickup with store lines: that stock is the member's to
   take back in the Lager.
10. **Counted once.** A refinery change set counts once per request against the daily write quota,
    as the stock, ship and blueprint sets do; the org stock's per-op counting does not apply.
11. **Live and additive.** A client write reaches the open refinery pages live through the rooms the
    frontend already has (REQ-XCH-030). The resource is additive within `v1` (ADR-0219).

## Consequences

- A client keeps the member's orders in step with the game both ways, and a change made in the web
  is never overwritten silently: the client sees it in the feed and its next write meets
  `VERSION_CONFLICT` only for the section that changed.
- The refinery module gains a hard delete, provenance and pickup markers, a second lock counter and
  feed triggers; every web write of an order now announces it in the change log.
- A pickup without store lines may be followed by a store in the web, which can book the yield a
  second time beside the client's own stock sync. The member accepts that risk; an order picked up
  with store lines is locked through `storedAt` like any stored order.
- The store-choice read of receivers names other members to a third-party program, as the org-stock
  roster does; it leaves out members who withhold their name (REQ-XCH-044).
- A delete of an order linked to a mission drops it out of that mission; the undo brings the link
  back only while the mission still exists.

## Alternatives considered

- **Only the draft.** Rejected: a review per order defeats a client that follows running orders,
  and the draft has no identity, no feed and no conflict check.
- **Resource-style routes** (`PUT`/`DELETE /me/refinery-orders/{id}`). Rejected: the bot filter
  admits only `GET` and `POST` on literal paths.
- **One order version.** Rejected: a timer update from the game would collide with every finance
  edit in the web, which the repository's fine-grained locking rule forbids.
- **A pickup that always books.** Rejected: a client that syncs stock itself would book the yield
  twice.
- **Counting cancel and goods removals for the guard.** Rejected: a cancel keeps the order and a
  goods removal is a correction; only a hard delete loses data.
