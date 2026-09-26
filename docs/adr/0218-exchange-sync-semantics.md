# ADR-0218 — Exchange sync: direct writes, lots, tombstones, a journal with undo, and a mass-change guard

- **Status:** Proposed — epic [#2078](https://github.com/krt-profit/basetool/issues/2078); nothing
  built yet.
- **Date:** 2026-09-26
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) ·
  [`inventory-lager.md`](../specs/inventory-lager.md) (`REQ-INV-001`, `-002`, `-026`) ·
  [`desktop-ingest.md`](../specs/desktop-ingest.md) (`REQ-INGEST-003`, `-004`, `-011`) ·
  [ADR-0003](0003-inventory-append-only-group-on-read.md) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md)

## Context

Two tools that each hold a member's blueprints, stock and ships must converge without either one
silently destroying what the member did in the other. The ingest gateway's containment argument so
far was „it persists nothing; every upload becomes a draft the member reviews in the browser"
(REQ-INGEST-004, REQ-INGEST-011). A sync that asks the member to review every change would not be
used.

The Lager does not store a „lot": it stores append-only entries (ADR-0003) grouped on read into
stacks keyed by material, location, quality, personal marker **and owning org unit**, and it merges
and splits rows. A client-side `externalId` mapped to one row would not survive a merge. Bulk paths
bypass the services, and a blueprint's `isDefault` is computed, not stored.

## Decision

We will let approved clients **write directly** to the member's own data, and make every such write
visible, bounded and reversible.

1. **Blueprints** are a set per member: `add` and `remove` of products; default-granted blueprints
   cannot be removed.
2. **Stock is exchanged as lots**: material + location + quality + stolen, over the member's
   personal rows, **across org-unit pools**. A client sends `set-quantity` with the
   `expectedQuantity` it last saw; the server compares under row locks and answers
   `409 VERSION_CONFLICT` on a difference, otherwise books the delta in or out like the web does.
   Book-ins get **no org unit** through their own stamping path; book-outs take rows without a unit
   first, then the oldest. Linked Materialbörse offers follow a book-out as in the web, audited, and
   are reported in the result. Trade goods are stored at a fixed quality 0.
3. **Ships** are versioned; `upsert` and `remove` carry the ship's `version`. Before the first
   create, a client **links** its ships to existing server ships (by type and name, asking the
   member when several share a type), so a Fleetview import is never duplicated. An external-ref map
   remembers the link.
4. **Change feed.** Each resource has a snapshot and a feed with an opaque cursor, sequenced at the
   database level so bulk paths are not missed. Removals leave **tombstones for 90 days** that say
   who removed the entry (`web`, `app`, `client` + client id + installation id). While a tombstone
   lives the server refuses a client's re-add unless the client sends an explicit override the
   member confirmed. A cursor older than the tombstones answers `410 CURSOR_EXPIRED`; the client
   then reconciles against its last baseline.
5. **Journal and undo.** Every exchange write is journaled for 90 days; the member can undo a
   client's writes from „Verbundene Anwendungen". Undo is version-checked and skips rows the member
   changed afterwards. Materialbörse offers removed by a sync book-out are not restored.
6. **Mass-change guard.** Per client, member and resource over a **rolling 24 h**, a batch that
   would take the window above **25 removals, or above 20 % of (current count + entries removed in
   the window) with at least 5**, is staged and confirmed by the member in the browser. Counted as
   removals: a quantity set to 0, a lot's reductions accumulated to ≥ 90 % within the window, and a
   ship update that changes name and type. A move — one lot down, another lot of the same material up
   in the same batch — is not a removal.
7. **Idempotency.** Writes carry an `Idempotency-Key` (24 h), keyed per (client, member, key). The
   gateway runs every gate first and caches only results produced after them — never `401`, `403`,
   `429`, `503`, a guard staging or a `5xx`; a parallel duplicate waits on a lock. Batches hold at
   most 500 ops, and results are compact (≤ 32 KiB).
8. **Drafts stay drafts.** The review-in-browser flows (`exchange.drafts.*`) keep REQ-INGEST-004's
   review-before-commit; only the „me" resources are written directly.

## Consequences

- REQ-INGEST-011's „persists nothing" containment is replaced by journal, undo and guard;
  REQ-INGEST-003's staging also carries staged mass changes.
- Synced stock without an org unit is invisible to unit editors until the member re-stamps it
  (web and app); re-stamping gives that unit's editors access, and the dialog says so.
- **Accepted:** a sync book-out can lower or remove Materialbörse offers, and undo does not bring
  them back; a fixed quality 0 for trade goods distorts average and maximum quality and quality
  floors.
- The journal, tombstones, external refs and installations are new member-linked tables: GDPR
  export and erasure, account merge and person-search coverage must serve each of them.

## Alternatives considered

- **Drafts only, reviewed in the browser.** Rejected by the owner: a sync nobody confirms is no
  sync.
- **A lot as a client `externalId` mapped to one row.** Rejected: it does not survive the Lager's
  merges and splits.
- **Protect the offered amount from a sync book-out.** Recommended, rejected by the owner: the
  exchange follows the web.
- **Infer deletes from an absent entry.** Rejected: an incomplete client snapshot would wipe data.
