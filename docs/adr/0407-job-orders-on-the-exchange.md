# ADR-0407 — Job orders on the exchange

- **Status:** Accepted — the read with the snapshot (#3644) and the create (#3646); the feed, the
  writes on an existing order and the caps follow with their slices
- **Date:** 2026-11-23
- **Deciders:** @greluc
- **Related:** amends [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md)
  (the exchange's resource allow-list) and narrows
  [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md) (the server-side publish hook's first
  consumer) · spec [`orders-exchange.md`](../specs/orders-exchange.md) `REQ-OXCH-*` ·
  [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-009`) ·
  [ADR-0257](0257-logisticians-read-and-write-their-units-shared-stock-through-the-exchange.md) and
  [ADR-0354](0354-bank-resources-on-the-exchange.md) (the other exceptions it sits beside) ·
  [ADR-0405](0405-job-order-commands-take-the-writer-rule-the-read-check-and-the-actor-as-parameters.md)
  (the command seam) · [ADR-0406](0406-the-job-order-feed-moves-on-a-fingerprint-of-the-carried-read.md)
  (the fingerprint) · decided in [#3608](https://github.com/krt-profit/basetool/issues/3608),
  [#3610](https://github.com/krt-profit/basetool/issues/3610),
  [#3611](https://github.com/krt-profit/basetool/issues/3611),
  [#3612](https://github.com/krt-profit/basetool/issues/3612),
  [#3616](https://github.com/krt-profit/basetool/issues/3616),
  [#3617](https://github.com/krt-profit/basetool/issues/3617),
  [#3618](https://github.com/krt-profit/basetool/issues/3618),
  [#3619](https://github.com/krt-profit/basetool/issues/3619) and
  [#3621](https://github.com/krt-profit/basetool/issues/3621)

## Context

ADR-0216 made the exchange a separate contract with its own resources, and ADR-0354 added the
Kartellbank to them. Job orders („Aufträge") were not among them, so a member could follow the
orders of their units in a connected application such as VerseKit only through the anonymous org
demand, and could raise an aUEC order („Bereitstellung von Credits") only in the browser.

Job orders hold operational information of the Kartell and, on aUEC orders, the holders of
purpose-bound money with their amounts. In the web every member of an active Profit unit reads
every order of all three kinds; a third-party client on a member's device must read less than that,
and must never become a second way to move money or to act for the responsible side.

The exchange's acting member carries no contextual authority and is never an administrator
(REQ-XCH-009). Reading and writing job orders through a client therefore need a decision from the
database, as the org stock (ADR-0257) and the bank (ADR-0354) do.

## Decision

1. **Job orders join the exchange's allow-list as their own route family**,
   `/exchange/v1/job-orders/**` on the gateway and `/api/v1/exchange/job-orders/**` on the backend.
   The rest of ADR-0216 holds unchanged: own DTOs, capabilities as the unit of approval, routes
   listed one by one.
2. **Two scopes.** `exchange.joborders.read` reads the snapshot and the feed of the member's
   job-order set; `exchange.joborders.write` reads `create-options` and creates, cancels and
   changes the target and comment of aUEC orders. A separate create scope is of no use without
   cancel, and `exchange.orders.*` would read like refinery or Ankauf orders.
3. **The fifth exception of REQ-XCH-009, decided from the database on every request.**
   - **Reading** follows the read set: the web's read set (REQ-ORDERS-052) **and** a current own
     relation. A unit relation counts through the active units the member directly belongs to
     while holding `KRT Member`: the order's requesting, responsible or actively supporting unit.
     An assignee entry counts by itself. The Profit-wide read, the leadership cascade, contextual
     grants, the administrator's scope, a pin, the aUEC holder and recipient link, retained
     historical reads and ended support never count, and no Profit gate stands in front of it. The
     relation implies the web's read, so the set is decided as the relation; a test pins that it
     never exceeds the web's read for the same member.
   - The relation predicates live once in `JobOrderScope` and are served by the port
     `JobOrderExchangeReadSet` (`joborder.api`), through which the snapshot pages. The same rule is
     the read-check value `JobOrderReadCheck.OWN_RELATION` of ADR-0405, decided by the access
     policy for the request's acting member; the exchange's writes on an existing order pass it to
     the shared status, target and information commands. A hidden, an unknown and a deleted order
     answer alike.
   - **Writing** follows the Logistician of the requesting unit (`DIRECT_LOGISTICIAN`, ADR-0405).
4. **Nothing of the responsible side, no money and no Bank effect through a client.** A client never
   accepts, processes, hands over, provides, settles, reassigns or deletes an order, never touches
   assignees or support, and no client write moves money or reaches the Bank. Every such action
   stays in the web; the read carries the order's `link` for it.
5. **The read carries no money detail.** Each order carries its header, the member's relations, the
   assignees under per-client refs without the names of members who withhold them, the member's
   own note, the section versions and its kind's requester-facing progress. Holders, amounts per
   person, provision, fees, movements, recipients, Bank state, unit commitments, claims, histories,
   other members' notes, the web's `can…` flags and the aUEC contact are never carried.
6. **Two switches**, a deliberate departure from ADR-0354's single bank switch: one for the whole
   family and one for its writes, both off at deploy, so a faulty release's writes stop while the
   reads go on (REQ-OXCH-009).
7. **No journal, no undo and no mass-change guard for job-order writes**, like ADR-0354 point 5. A
   wrong write is repaired in the web; the audit viewer's client filter finds it.
8. **Own audit types for client writes**, a deliberate departure from the refinery, org-stock and
   bank families, which write their normal types with the client id:
   `JOB_ORDER_CREATED_VIA_CLIENT`, `JOB_ORDER_CANCELLED_VIA_CLIENT`,
   `JOB_ORDER_TARGET_CHANGED_VIA_CLIENT` and `JOB_ORDER_COMMENT_CHANGED_VIA_CLIENT`, written instead
   of the web's types.
9. **The create's `Idempotency-Key` is stored on the order** with the creating client and member, so
   a retry after the gateway's one-hour cache or after a `5xx` answers the first order. The lookup
   runs under a per-member advisory lock, the one the caps count under, so two retries never both
   insert.
10. **The backend publishes the create's `orders/queue` frame** after the commit, for the web and the
    exchange alike. This narrows ADR-0094: the frontend's `LiveSyncLocalBus` no longer serves the
    job-order create.
11. **A11's third exception for other members' data**, beside the shared stock (ADR-0257) and the
    Kartellbank (ADR-0354): other members' and other units' order data on the client rests on
    Art. 6(1)(f), coordinating the order work, with the name withholding as the objection, and a
    client keeps job-order data at most 7 days, in memory only.

## Consequences

- Job orders can be shut off on the exchange alone, and their writes separately from their reads.
- A member of an eligible Profit unit reads through a client only the orders of their direct units
  and their own assignee entries, not every order of the Kartell; an administrator reads like a
  plain member.
- A route slice of the family adds its route under the family prefix; the switches cover it without a
  change to the gate.
- The snapshot holds only orders with a fingerprint (ADR-0406), so a new order reaches a client
  through the feed a few seconds after it was created.
- The write slices pass `JobOrderReadCheck.OWN_RELATION` as the read check; no second copy of the
  read rule exists for the writes.

## Alternatives considered

- **The web's read set unchanged.** Rejected in #3610: a member of an eligible Profit unit would
  carry every order of the Kartell into a third-party client.
- **A scope filter per request** ("mine", "I work on", "to process"). Rejected: one set and one
  cursor; each order carries the member's relations and the client builds the views.
- **One switch for the family.** Rejected in #3618: a faulty write release would stop the reads too.
- **The family's writes journaled and undoable like the other families.** Rejected in #3616: a cancel
  or a target change has effects (notices, the queue, the lifecycle) an undo cannot take back.
