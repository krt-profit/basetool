> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-23.
> **Owner area:** OXCH · **Related ADRs:** [ADR-0354](../adr/0354-bank-resources-on-the-exchange.md)
> (the bank switch this family follows),
> [ADR-0216](../adr/0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md),
> [ADR-0406](../adr/0406-the-job-order-feed-moves-on-a-fingerprint-of-the-carried-read.md) (the
> fingerprint the feed moves on), [ADR-0407](../adr/0407-job-orders-on-the-exchange.md) (job orders
> on the exchange)

# Job orders on the exchange

## Context & goal

Members want to follow and raise job orders („Aufträge") from a connected application such as
VerseKit without opening the Basetool. The exchange ([`external-exchange.md`](external-exchange.md))
carries job orders as their own route family, `/exchange/v1/job-orders/**` on the ingest gateway and
`/api/v1/exchange/job-orders/**` on the backend.

This spec is the single home of job orders on the exchange. The orders themselves, their kinds,
lifecycle, money and notices, stay in [`orders-core.md`](orders-core.md) and
[`orders-auec.md`](orders-auec.md); the exchange's gateway, registry, limits and error contract stay
in [`external-exchange.md`](external-exchange.md) and [`docs/exchange/errors.md`](../exchange/errors.md).
The read serves its snapshot and its change feed (`GET /exchange/v1/job-orders`), and a Logistician
of the requesting unit creates aUEC orders through `GET /exchange/v1/job-orders/create-options` and
`POST /exchange/v1/job-orders`; the writes on an existing order and the caps follow with their own
slices. Both switches are off until an admin turns them on.

The guiding rules:

- **Job orders can be shut off on the exchange alone**, and their writes separately from their
  reads: an admin stops a faulty release's writes while the reads go on, or stops the whole family,
  without stopping a client's other areas.
- **No job-order write is journaled or undone through the exchange.** The way back after a faulty
  release is the web (arc42 §6.8, „Faulty release on job orders").

## Requirements

### REQ-OXCH-001 — Scopes and the one decision point

> [!note] Implemented for the read: the scope `exchange.joborders.read` and the read set (#3644;
> decided in #3608, #3610, #3619, ADR-0407); and for the write: the scope
> `exchange.joborders.write` and the writer rule of the create (#3646; decided in #3611, #3619,
> ADR-0405).

**Scopes.** A client reads job orders under `exchange.joborders.read` and writes them under
`exchange.joborders.write`, each a capability of the registry (REQ-XCH-005) with its own consent
line:

| Scope | Consent (EN) | Consent (DE) | Chip label (DE / EN) |
|---|---|---|---|
| `exchange.joborders.read` | Read the job orders of the org units you directly belong to and the ones you work on, with progress, contact, comment and the names of those working on them | Die Aufträge der Einheiten lesen, denen du direkt angehörst, und die Aufträge, die du bearbeitest, samt Fortschritt, Kontakt, Kommentar und den Namen der Bearbeiter | „Aufträge der eigenen Einheiten und eigene Bearbeitungen lesen" / "Read the job orders of your units and those you work on" |
| `exchange.joborders.write` | As a logistician of an org unit, create aUEC orders on its behalf, cancel its aUEC orders before any money has moved and change their target and comment; money moves only in the Basetool | Als Logistiker einer Einheit aUEC-Aufträge in ihrem Namen anlegen, ihre aUEC-Aufträge vor der ersten Geldbewegung stornieren und Zielbetrag und Kommentar ändern; Geld bewegt sich nur im Basetool | „Als Logistiker aUEC-Aufträge anlegen, stornieren und ändern" / "As a logistician, create, cancel and change aUEC orders" |

The write line promises no undo: job-order writes are not undone under „Verbundene Anwendungen"
(REQ-OXCH-005). `create-options` sits under the write scope, so only a client the member consented
to create with learns whether the member is a Logistician.

**The read set — the fifth exception of REQ-XCH-009.** The acting member carries no contextual
authority and is never an administrator, so the read is decided from the database on every request:
the web's read set (REQ-ORDERS-052) **and** a current own relation. The member has a current own
relation to an order when

- a unit the member **directly** belongs to is the order's current requesting unit
  (`requesting`), its current responsible unit (`responsible`) or an **actively** supporting unit
  (`supporting`); a unit relation counts only through active units (REQ-ORG-032) and only while the
  member holds `KRT Member` (REQ-SEC-015); or
- the member holds an assignee entry on the order (`assignee`).

Not part of the set, although the web lets the member read them: the Profit-wide read of a member
of an eligible Profit unit, the leadership cascade, contextual grants, the administrator's scope
and a pin, the aUEC holder and recipient link, retained historical unit reads and ended support. No
Profit gate stands in front of the read: a member of a requesting unit outside the Profit hierarchy
follows that unit's orders. An administrator reads like a plain member.

- Every relation implies the web's read, so the set is decided as the relation, through the
  predicates of `JobOrderScope` that the snapshot and the read check share.
- The read check `JobOrderReadCheck.OWN_RELATION`, decided by the access policy for the request's
  acting member, carries the same rule to the shared status, target and information commands
  (ADR-0405); the exchange's writes on an existing order pass it. A hidden, an unknown and a deleted
  order answer alike.
- **Nothing of the responsible side, no money and no Bank effect through a client** (ADR-0407): a
  client never accepts, processes, hands over, provides, settles, reassigns or deletes an order and
  never touches assignees or support; everything else stays in the web, reached through the order's
  `link`.

**The writer rule — the Logistician of the requesting unit.** Through a client a member writes for a
unit only when they are a **direct member** of it and a **Logistician** of it by the org-stock rule
(REQ-XCH-045): the Logistician flag, a leadership rank, or the reach of a Bereich or OL seat over
it; they hold `KRT Member`, and the unit is active
(`OrgUnitMembershipQueryService.findDirectLogisticianOrgUnitIds`). It is the writer rule
`JobOrderWriterRule.DIRECT_LOGISTICIAN` of ADR-0405, decided from the database on every request.

- Mere membership makes no writer, and reach over a unit the member does not belong to counts not.
- **Requester direction only:** a client creates an order only with one of the member's writer units
  as the requesting unit; the responsible unit may be any eligible Profit unit. A writer of the
  responsible unit creates nothing on behalf of another unit through a client; that stays in the
  web (REQ-ORDERS-053).
- The requesting unit is a unit the member directly belongs to, so an order a member creates
  through a client is always in their read set.
- An administrator is a plain member: no admin pass, no all-scope, no pin.
- For the same member without a pin, every pair the rule permits is one the web's contextual rule
  permits, so a client never creates what the web would refuse (ADR-0405 point 5).

**Acceptance**

- [x] Each of the four relations puts an order into the set, and one order carries every relation
  that applies (`JobOrderExchangeReadSetIntegrationTest`).
- [x] The Profit-wide read, the leadership cascade, ended support, a retained historical read, the
  aUEC holder link and a deactivated unit relate nothing; without `KRT Member` only an assignee entry
  counts; an administrator reads like a plain member; an unknown order answers like a hidden one
  (`JobOrderExchangeReadSetIntegrationTest`).
- [x] Every order of the set is one the web lets the same member read, and the read check
  `OWN_RELATION` decides the same set for the request's member (`JobOrderExchangeReadSetIntegrationTest`).
- [x] The scope is provisioned with its consent lines and chip labels in the realm provisioner, the
  sandbox and E2E realms, the registry's capability check and the shared seam
  (`provision-keycloak-realm.test.sh`, `build-sandbox-realm.py --check`, `SandboxSeedCapabilitiesTest`,
  both `ExchangeRelaySeamParityTest`s).
- [x] The scope `exchange.joborders.write` is provisioned the same way, and the registry's capability
  check holds both job-order scopes (`provision-keycloak-realm.test.sh`,
  `build-sandbox-realm.py --check`, `SandboxSeedCapabilitiesTest`, both
  `ExchangeRelaySeamParityTest`s, V448).
- [x] The writer rule counts the flag, a leadership rank and Bereich or OL reach with direct
  membership only, an active unit and `KRT Member`, and treats an administrator as a plain member;
  every pair it permits is one the web permits (`JobOrderDirectLogisticianRuleIntegrationTest`).
- [x] Through a client, a plain member, a writer of the responsible side, leadership reach without
  membership and an administrator create nothing (`ExchangeJobOrderCreateTest`).

**Enforced by:** `JobOrderExchangeReadSet`, `JobOrderScope`, `JobOrderReadCheck.OWN_RELATION`,
`JobOrderAccessPolicy#canRead`, `JobOrderWriterRule.DIRECT_LOGISTICIAN`, `JobOrderWriters` (job
orders) · `ExchangeCapability`, `ExchangeJobOrderReadService`, `ExchangeJobOrderCreateService`
(exchange) · V446, V448 · **Related:** REQ-XCH-005, REQ-XCH-009, REQ-XCH-045, REQ-ORDERS-052,
REQ-ORDERS-053, REQ-ORG-032, REQ-SEC-015 · ADR-0405, ADR-0407 · **Issues:** #3608, #3610, #3611,
#3619, #3644, #3646

### REQ-OXCH-002 — The client read of a job order

> [!note] Implemented with the snapshot (#3644; decided in #3612, #3621, ADR-0407).

Every order of the member's set reaches a client as the read of its kind: `job-order.schema.json`
for the fields every kind carries and `job-order-material.schema.json`,
`job-order-item.schema.json` and `job-order-auec.schema.json` for each kind. The member-independent
part is the projection the fingerprint hashes (REQ-OXCH-003), served unchanged; the backend adds only
the member's own part.

- **Every order:** `id` (the feed key), `number` (a display field only; no route accepts it),
  `kind`, `status`, `priority` (`null` once closed), `requestingUnit`, `responsibleUnit` and
  `supportingUnits` (each with name, shorthand and kind), `createdAt`, `updatedAt` (the instant of
  the order's fingerprint, the last change of a carried field), `comment` (the author's plain text),
  `responsibilityInvalid` (REQ-ORDERS-055) and `versions { lifecycle, target, comment }`, the
  target version on aUEC orders only and never the information version of the aUEC contact.
- **The member's own part:** `relations` (REQ-OXCH-001, in the order `requesting`, `responsible`,
  `supporting`, `assignee`); `assignees[]` with each assignee's display name and per-client `ref`
  under the org-stock rules (REQ-XCH-043), `own` on the member's own entry, and `note` only there
  (another member's note is never carried); a member who withholds their name (REQ-XCH-044) is left
  out, the acting member included; `link`, the server-built
  `https://profit-base.online/orders/<id>`, validated like the bank queue's.
- **MATERIAL** and **ITEM** carry `contact`, the author's handle as plain text, left out when the
  order names none. An **aUEC** order carries no contact, since contact plus remaining amount shows
  who is about to receive money.
- **Progress per kind**, as the web shows it: MATERIAL per material and quality floor (`material`
  with its id as `bt`, `minQuality`, `unit`) `requested`, `handedOver`, `remaining` and `open` in the
  material's own unit; ITEM per item line (`id`, `item`) `ordered`, `manufactured`, `delivered` and
  `open`; aUEC `target`, `netHandedOver`, `remaining`, `overpaid` in whole aUEC and
  `settlementOpen`, a yes/no with no amount.
- **Never carried:** holders, holder amounts or sums, provision, holdings, fees, movements,
  recipients, purpose flows, releases, shortages, settlements, the money and target-change history,
  Bank attempts, account holdings, unit commitments, claims, linked stock, blueprints, other
  members' notes, the web's `can…` flags and `originalProfitBoundaryUnknown`. The gateway refuses
  an answer with an undeclared property, so an aUEC order carrying a contact fails closed
  (REQ-XCH-050).
- Every DTO field is under the field ratchet (REQ-XCH-050); the answer is in the relay document
  (REQ-XCH-039).

**Acceptance**

- [x] A MATERIAL order carries exactly the header, the member's part, its contact and its lines; an
  aUEC order exactly the header, the member's part and its progress, with no contact, holder or
  amount per person (`ExchangeJobOrderSnapshotTest`).
- [x] Only the member's own entry carries a note, every entry carries a per-client `ref`, and a
  member who withholds their name is left out (`ExchangeJobOrderSnapshotTest`).
- [x] Every answer matches its published schema, and the gateway turns an aUEC order with a contact
  or a foreign link into `502 BACKEND_RELAY_FAILED` (`ExchangeJobOrderSnapshotTest`, ingest
  `ExchangeJobOrderRouteTest`, `ExchangeContractTest`, `ExchangeFieldRatchetTest`).

**Enforced by:** `ExchangeJobOrderShaper`, `ExchangeJobOrderDto`, `JobOrderExchangeReads` ·
`job-order*.schema.json`, `page.schema.json#/$defs/jobOrderPage` · **Related:** REQ-XCH-043,
REQ-XCH-044, REQ-XCH-050, REQ-ORDERS-055, REQ-OXCH-003 · ADR-0406, ADR-0407 · **Issues:** #3612,
#3644

### REQ-OXCH-003 — Snapshot and feed of the job-order set

> [!note] Implemented for the fingerprint, the dirty marks and the worker (#3643), the client set's
> snapshot (#3644) and the per-unit and per-member logs with the feed and its tombstone (#3645);
> decided in #3615, #3618, ADR-0406. The window run follows with its slice.

**The snapshot.** `GET /exchange/v1/job-orders` (`exchange.joborders.read`), relayed to
`GET /api/v1/exchange/job-orders`, answers `page.schema.json#/$defs/jobOrderPage`: the client set —
the orders of the member's read set (REQ-OXCH-001) that are active, closed at most 7 days ago by
`closed_at`, or aUEC while the last fingerprint says `settlementOpen` — read in one
`REPEATABLE READ` transaction, ascending by `id`, `limit` 1 to 1000 (default 500), each item in the
read of REQ-OXCH-002.

- An order joins the snapshot with its first fingerprint, so a new order reaches a client a few
  seconds after it was created; `updatedAt` is never empty.
- The last page carries the feed cursor at the snapshot's position (REQ-XCH-013); one cursor serves
  all three kinds.
- `removed` is always empty on a snapshot page; its entries carry `{ key, removedAt }` without
  `removedBy`.
- Each page counts one `snapshot` read for its client (REQ-OBS-025).

**The fingerprint.** Every job order carries the fingerprint of its member-independent exchange
read in `exchange_job_order_fingerprint`: the SHA-256 of a canonical encoding of the projection
`JobOrderExchangeReads` serves alike to the fingerprint and to the client read (ADR-0406). The
projection holds:

- the header: `id`, `number`, `kind`, `status`, `priority` (empty while terminal), `createdAt`, the
  requesting and the responsible unit by id, the actively supporting units by id, `comment`, the
  MATERIAL and ITEM `contact` (never on aUEC) and `responsibilityInvalid` (REQ-ORDERS-055);
- `versions { lifecycle, target, comment }`, the target version on aUEC only, never the
  information version of the aUEC contact;
- the assignees by member id;
- the progress per kind: MATERIAL per material and quality tier `requested`, `handedOver`,
  `remaining` and `open`; ITEM per item line `ordered`, `manufactured`, `delivered` and `open`;
  aUEC `target`, `netHandedOver`, `remaining`, `overpaid` and `settlementOpen`, from the ledger
  figures the web shows.

Unit names, assignee names and refs, the member's relations and the member's own note are the
member's part of the read and are not fingerprinted.

**The instant.** The fingerprint's `changed_at` is the read's `updatedAt`: when the worker found a
carried field changed. It moves with the fingerprint only, so the feed moves on nothing else — not
on a provision, a holder movement, a fee, a commitment, a claim that leaves `open` unchanged or a
contact change on an aUEC order. The row also keeps the read's `settlementOpen`, so SQL can select
the client set.

**Dirty marks.** `AFTER` row triggers on every source table mark the order in
`exchange_job_order_dirty`: `job_order`, its material and item lines, assignees, support
(`job_order_participation`), earmarks (`inventory_item_job_order_allocation`, and the material and
quality of an earmarked stock row), claims, money operations, corrections, holders, Bank account
positions and the target. Bulk SQL, the patch change, account merges and the name retention reach
the fingerprint without a service.

- A change of the Profit hierarchy — a unit's activation, kind, department or parent, or an active
  Profit area added or deleted — marks every order of the client set: active, closed within 7 days
  by `closed_at`, and aUEC while its last fingerprint says `settlementOpen`.
- An order gets one mark per marking transaction; a later mark never replaces an earlier one, so
  the oldest mark keeps its age until the worker clears it.
- The triggers are named `trg_<table>_exchange_dirty`, never `trg_<table>_exchange_change`, so the
  change-feed trigger guard keeps one feed trigger per table.

**The worker.** `JobOrderFingerprintTask` runs every `app.exchange.job-order-fingerprint.interval`
(default 5 s), after the marking transactions committed and whatever the job-order switches say, on
one backend instance at a time (two-key advisory lock space `OXFP`).

- A run takes the orders marked longest ago, at most
  `app.exchange.job-order-fingerprint.batch-size` (default 200, 1 to 1000), and reads their marks
  and their projection in one `REPEATABLE READ` transaction.
- It writes a fingerprint and its instant only where the fingerprint changed, and deletes exactly
  the marks it read: a write that commits while the run is under way keeps its own mark for the
  next run.
- A failed run rolls back whole; the next run retries it.

**The log.** The family's log is `exchange_change` with `resource = 'JOB_ORDER'` and the order's
`id` as key (REQ-XCH-013, ADR-0406). Each entry names a unit or a member, never both; triggers alone
write it (V447), at most once per unit or member, key and transaction:

- **Per unit.** A changed fingerprint writes one entry each for the order's requesting unit, its
  responsible unit and every actively supporting unit. The fingerprint row keeps them as
  `announced_unit_ids`, read in the worker's snapshot, so a change of these units writes for the old
  and the new ones alike. A first fingerprint writes only for an order of the client set; a hard
  delete writes for the units and assignees the order was last announced to.
- **Per member.** The same change writes one entry for each assignee, old and new
  (`announced_member_ids`).
- **Memberships.** A membership that starts, ends (`DELETE` included) or moves, and a unit that is
  activated or deactivated, write per-member entries. A start writes the unit's orders of the client
  set; an end writes those, the orders last announced to the unit and every order the unit's log
  still names, so a client that syncs only after its member left still drops an order that left the
  unit before.
- **Announcements from outside the order.** A unit's new name or shorthand announces the orders of
  the client set it requests, is responsible for or supports; an assignee's new display name or name
  withholding announces the orders of the client set they are on; each goes to every unit and member
  the order was last announced to. A change of an assignee's own note writes an entry for that
  member alone, since no other member's read carries it.
- Nothing else writes the log: a provision, a holder movement, a fee, a commitment, a claim that
  leaves `open` unchanged or a contact change of an aUEC order leaves the fingerprint, and so the
  log, alone.

**The feed.** The snapshot's last cursor and every feed page's `nextCursor` read the feed
(REQ-XCH-013): one cursor for all three kinds, `limit` 1 to 1000, entries of finished transactions
only.

- A page reads the entries of the units the member reads through now (REQ-OXCH-001: direct, active,
  with `KRT Member`) and the member's own entries. Entries a unit received after the member left it
  are never read.
- Each key appears once, in the order of its latest entry, resolved against the member's current
  client set: an order of the set is an item in the read of REQ-OXCH-002, any other key a `removed`
  entry `{ key, removedAt }`, `removedAt` the instant of the key's latest entry the page read.
- No `removed` entry names a writer, so a lost relation, the end of the window and a deletion look
  alike; a client drops the order and ignores a key it never held. A client cancel is a status
  change, so the order stays an item.
- A reopened order and a client's own write return as items. Orders leaving at the end of the window
  come with the nightly window run.
- The log keeps 90 days; an older cursor answers `410 CURSOR_EXPIRED` and the client takes a new
  snapshot (`docs/exchange/sync-guide.md`).
- Each page counts one `feed` read for its client (REQ-OBS-025).

**Monitoring:** the worker job, the recomputation counter, the backlog gauges and their three
alerts are REQ-OBS-025.

**Acceptance**

- [x] Every carried field moves the fingerprint, and one state gives one fingerprint whatever the
  list order (`JobOrderFingerprintsTest`).
- [x] The projection carries the header, the versions, active support and assignees by id and each
  kind's progress as the web shows it, with no contact on aUEC; a claim that leaves `open`
  unchanged or a contact change on an aUEC order leaves it equal
  (`JobOrderExchangeReadProjectionIntegrationTest`).
- [x] A new order gets its first fingerprint; a carried change moves the fingerprint and its
  instant, any other write moves neither; a provision, a holder movement, a fee, a commitment and an
  aUEC contact change move nothing, a handover does (`JobOrderFingerprintWorkerIntegrationTest`).
- [x] A money write, a handover, an earmark or a hierarchy change that commits while the worker
  runs is recomputed by the next run (`JobOrderFingerprintWorkerIntegrationTest`).
- [x] A hierarchy change marks the client set only and flips `responsibilityInvalid`; a hard delete
  takes the marks and the fingerprint with the order (`JobOrderFingerprintWorkerIntegrationTest`).
- [x] The snapshot holds active orders, orders closed within 7 days and aUEC orders whose last
  fingerprint says `settlementOpen`, and leaves out an order closed longer ago, a settled aUEC
  order, an order outside the read set and an order without a fingerprint; it pages by id, ends with
  the feed cursor and counts a snapshot read (`ExchangeJobOrderSnapshotTest`).
- [x] The route is on the acting list, behind the family switch and the read scope, on the gateway
  and in the backend (`ExchangeJobOrderSnapshotTest`, ingest `ExchangeJobOrderRouteTest`,
  `FilterExemptionSetsTest`).
- [x] A changed fingerprint announces the order to its requesting, responsible and actively
  supporting units and its assignees, old and new; a first fingerprint outside the client set, an
  unchanged one and a settlement flag alone announce nothing; a hard delete announces the order
  where it was last announced (`ExchangeJobOrderFeedTriggerIntegrationTest`).
- [x] A membership that starts, ends or moves, a unit's deactivation and activation, a unit's
  rename, an assignee's rename and name withholding and an assignee's own note announce exactly the
  orders they touch, to exactly the logs they reach (`ExchangeJobOrderFeedTriggerIntegrationTest`).
- [x] A provision, a holder movement, a fee, a commitment and an aUEC contact change write no log
  entry; a money write, a handover, an earmark or a hierarchy change that commits while the worker
  runs reaches the log on the next run (`JobOrderFingerprintWorkerIntegrationTest`).
- [x] The feed answers a changed order as an item; a lost relation and a hard delete as the same
  `{ key, removedAt }`; a membership end as gone, an order that left the unit before the member did
  included, and leaves the unit's later entries unread; a reopened order and a client's own write as
  items; it never passes an announcement that commits late, counts a feed read and closes with the
  family switch (`ExchangeJobOrderFeedTest`).
- [x] The gateway relays the feed and turns a removed entry that names a writer into
  `502 BACKEND_RELAY_FAILED` (ingest `ExchangeJobOrderRouteTest`, `ExchangeGoldenAnswerTest`,
  `ExchangeContractTest`).
- [ ] The window run comes with its slice.

**Enforced by:** `JobOrderExchangeReads`, `JobOrderExchangeReadProjection` (job orders) ·
`JobOrderFingerprints`, `JobOrderFingerprintRecomputation`, `JobOrderFingerprintTask`,
`ExchangeJobOrderMarkRepository`, `ExchangeJobOrderFingerprintRepository`, `ExchangeJobOrderReadService`,
`ExchangeFeedReader#jobOrderChanges`, `ExchangeChangeRepository#findAnnouncedJobOrders`,
`ExchangeJobOrderController` (exchange) · `ExchangeJobOrderController` (gateway) · V445, V447 ·
**Related:** REQ-XCH-013, REQ-ORDERS-054, REQ-ORDERS-055, REQ-OXCH-001, REQ-OXCH-002, REQ-OXCH-009,
REQ-OBS-025 · ADR-0224, ADR-0406, ADR-0407 · **Issues:** #3615, #3618, #3643, #3644, #3645

### REQ-OXCH-004 — Create options and the create of an aUEC order

> [!note] Implemented (#3646; decided in #3611, #3614, #3617, #3619, ADR-0405, ADR-0407). The
> open-order and daily create caps and `limits` in the create options follow with their slice
> (REQ-OXCH-009).

**Create options.** `GET /exchange/v1/job-orders/create-options` (`exchange.joborders.write`),
relayed to `GET /api/v1/exchange/job-orders/create-options`, answers
`job-order-create-options.schema.json` from the web's create-options projection under the writer
rule of REQ-OXCH-001 (`JobOrderCreationOptions.current(DIRECT_LOGISTICIAN, actor)`, ADR-0405):

- `requestingUnits`: the member's writer units, as `{id, name, shorthand, kind}`;
- `responsibleUnits`: the eligible Profit units, in the same shape;
- `suggestedResponsible`: the canonical Profit area, the projection's aUEC suggestion;
- `writesEnabled`: whether the job-order writes switch is on (REQ-OXCH-009).

Without a writer unit both lists are empty and `suggestedResponsible` is `null`: the client offers
no create. The list itself is the writer flag; no other per-unit flag exists, the read names no
member, and `/me/org-units` (REQ-XCH-048) stays unchanged. The read is a `GET`, so the writes
switch never closes it; each read counts one `create_options` read (REQ-OBS-025).

**The create.** `POST /exchange/v1/job-orders` (`exchange.joborders.write`) with a mandatory
`Idempotency-Key`, relayed with the key as `X-Exchange-Idempotency-Key` to
`POST /api/v1/exchange/job-orders`:

- body `job-order-write.schema.json#/$defs/create`: `requestingUnitId`, `responsibleUnitId`,
  `contact` (required plain text, at most 200 characters), `comment` (optional plain text, at most
  1000) and `target` (an integer in whole aUEC, 1 to 10¹²);
- answer `201` `job-order-write.schema.json#/$defs/created`: `{ id, status, versions, target }`,
  read from the stored order.

The create runs the web's aUEC create command (`JobOrderCreateCommand.createAuec`, ADR-0405) with
the writer rule `DIRECT_LOGISTICIAN` and an actor carrying the member and the client: the same order
locks, unit resolution, eligible-Profit check, priority, created notice, `orders/queue` frame after
the commit (REQ-XCH-030) and audit event as a web create. Nothing is filled in.

**Order of checks.** Schema (the gateway's schema, then the backend's bean rules) → the idempotency
lookup → the pair → the insert. A pair that is not permitted — an unknown unit, a requesting unit
the member does not write for, a responsible unit that is not an eligible Profit unit — answers one
`400 JOB_ORDER_UNITS_NOT_OFFERED` with a fixed detail that names no side; the client reads the
create options again and lets the member choose. The command's own refusals (`400` for an unknown
or ineligible unit, `403` for a pair the rule refuses) map to this one code.

**The stored key.** A client's create stores the creating client (`job_order.created_by_client_id`),
the creating member (`created_by_member_id`, `ON DELETE SET NULL`) and the create's key
(`create_idempotency_key`), unique per member, client and key (V448).

- A create under a key the client already created an order of this member with answers that order
  with `201` and its current state, also after the gateway's one-hour cache (REQ-XCH-020) and after
  a `5xx` past the commit. Changed terms are never applied; the answer carries the stored `target`.
- The lookup and the insert run under the member's client-create lock, a transaction-scoped
  advisory lock in its own space (`OXCR`) keyed by the member, so two retries never both insert; the
  caps of REQ-OXCH-009 count under the same lock.
- The Art. 15 export lists the orders a member created through a client while the order keeps its
  creator (REQ-SEC-058); the person search exempts the server-written client id and key, and an
  account merge leaves the creator with the act.

**Audit.** A client's create writes `JOB_ORDER_CREATED_VIA_CLIENT` with
`{requestingOrgUnit, responsibleOrgUnit, target}` instead of `JOB_ORDER_CREATED`, and the client in
`client_id` as on every row (REQ-AUDIT-001, REQ-AUDIT-005); a replay and a refusal record nothing.

**Notices.** The create raises the web's `JOB_ORDER_CREATED` for the current writers of both units,
without the creating member (REQ-NOTIF-008).

**Monitoring** (REQ-OBS-025). Every create counts on
`basetool_exchange_writes_total{resource="job_order", outcome}` — `applied` for a create and for a
retry answered from the stored key, `rejected` for a refusal — and a refused pair also on
`basetool_exchange_job_order_refused_total{reason="units_not_offered"}`.

**Acceptance**

- [x] A Logistician of the requesting unit creates an aUEC order for an eligible Profit unit; the
  answer carries the order's id, status, versions and target and matches its schema, and the order
  stores the client and the member (`ExchangeJobOrderCreateTest`).
- [x] An unknown requesting or responsible unit, a requesting unit the member only belongs to, a
  responsible unit outside the Profit units, a responsible-side writer, leadership reach without
  membership and an administrator answer one `400 JOB_ORDER_UNITS_NOT_OFFERED` naming no unit and
  create nothing (`ExchangeJobOrderCreateTest`).
- [x] A retry under the same key answers the first order with its stored target and current state,
  after the cache and after a lost answer; another key creates another order; two creates racing
  under one key insert one order (`ExchangeJobOrderCreateTest`).
- [x] A client's create writes `JOB_ORDER_CREATED_VIA_CLIENT` with the client and no free text; a
  refusal writes nothing; the unit writers get the created notice without the member, the overview
  is refreshed, and the order lands in the member's snapshot (`ExchangeJobOrderCreateTest`).
- [x] The create options list the member's Logistician units and the eligible Profit units with the
  Profit area suggested, are empty for a member without a writer unit, tell the writes switch and
  count a read (`ExchangeJobOrderCreateTest`).
- [x] The gateway checks the body before the relay, relays the key on the create only, and needs
  the write scope for both routes (ingest `ExchangeJobOrderWriteRouteTest`,
  `ExchangeGoldenAnswerTest`, `ExchangeContractTest`, `ExchangeFieldRatchetTest`).
- [ ] The open-order and daily create caps, and `limits` in the create options, come with their
  slice.

**Enforced by:** `JobOrderCreateCommand`, `JobOrderAuecCreation`, `JobOrderClientCreateRepository`,
`JobOrderCreationOptions`, `JobOrderResponsibilityAudit`, `JobOrderExportSections` (job orders) ·
`ExchangeJobOrderCreateService`, `ExchangeJobOrderWriteController` (backend exchange) ·
`ExchangeJobOrderWriteController`, `ExchangeRoutes`, `ExchangeRelay.DETAILS` (gateway) ·
`job-order-write.schema.json`, `job-order-create-options.schema.json` · V448 · **Related:**
REQ-XCH-020, REQ-XCH-025, REQ-XCH-030, REQ-XCH-048, REQ-ORDERS-053, REQ-AUDIT-001, REQ-NOTIF-008,
REQ-SEC-058, REQ-OBS-025, REQ-OXCH-001, REQ-OXCH-005 · ADR-0405, ADR-0407 · **Issues:** #3611,
#3614, #3617, #3619, #3646

### REQ-OXCH-005 — Shared rules of the job-order writes

> [!note] Implemented for the create (#3646; decided in #3614, #3616, ADR-0407). Cancel, target and
> comment follow with their slices under the same rules.

- **Single routes** under `/exchange/v1/job-orders`, one order per call and every path on the
  acting list (`ActingMemberFilter.ACTING_PATHS`); no change set and no `dryRun`. A client pre-checks
  from what it reads.
- **A mandatory `Idempotency-Key`** on every write (REQ-XCH-020): the gateway refuses a write
  without one with `400 IDEMPOTENCY_KEY_MISSING` and caches the answer for one hour; it relays the
  key to the backend on the create only, where the order stores it (REQ-OXCH-004). The writes on an
  existing order need no stored key: a first applied write moves its section version, so a retry
  after the cache answers `409 VERSION_CONFLICT`.
- **Named section versions:** a write on an existing order echoes only its own section version,
  under a named field (`lifecycleVersion`, `targetVersion`, `commentVersion`), and every answer
  carries the new `versions`.
- **The order of checks** on an existing order: schema → visibility → writer → version →
  responsibility → status → the write's own rule.
- **One quota unit per call** (REQ-XCH-023): each write counts one unit of the daily write quota; a
  refusal by a job-order switch counts none (REQ-OXCH-009).
- **No journal, no undo and no mass-change guard** (REQ-XCH-021, REQ-XCH-022, ADR-0407 point 7): a
  wrong write is repaired in the web, found through the audit viewer's client filter.
- **Fixed details:** every backend refusal reaches the client with its registry code and the fixed
  English detail of `ExchangeRelay.DETAILS` (REQ-XCH-025).

**Acceptance**

- [x] The create is a single route on the acting list that needs an `Idempotency-Key`, counts one
  quota unit and relays the key (`ExchangeRoutes`, ingest `ExchangeJobOrderWriteRouteTest`,
  `ExchangeGoldenAnswerTest`, `FilterExemptionSetsTest`, both `ExchangeRelaySeamParityTest`s).
- [x] A refusal by the writes switch comes before the quota and counts no write (ingest
  `ExchangeGateTest`); the backend gate closes the create while the writes switch is off
  (`ExchangeJobOrderCreateTest`).
- [ ] Cancel, target and comment with their named versions come with their slices.

**Enforced by:** `ExchangeRoutes`, `ExchangeIdempotencyFilter`, `ExchangeLimitFilter`,
`ExchangeRelay` (gateway) · `ActingMemberFilter`, `ExchangeGate` (backend) · **Related:**
REQ-XCH-020, REQ-XCH-021, REQ-XCH-022, REQ-XCH-023, REQ-XCH-025, REQ-OXCH-004, REQ-OXCH-009 ·
ADR-0407 · **Issues:** #3614, #3616, #3646

### REQ-OXCH-009 — The job-order switches

> [!note] Implemented for the two switches and their codes (#3642; decided in #3618, #3621) and for
> `writesEnabled` in `create-options` (#3646, REQ-OXCH-004). The open-order cap, the daily create
> cap and `limits` in `create-options` follow with their slice.

**Two switches.** Beside the global exchange switch (REQ-XCH-003) and the bank switch
(REQ-BXCH-006), the settings row carries two job-order switches, each with its own version counter
and change time:

- `exchange_settings.job_orders_enabled` (`job_orders_version`, `job_orders_updated_at`) for the
  whole family;
- `exchange_settings.job_order_writes_enabled` (`job_order_writes_version`,
  `job_order_writes_updated_at`) for its writes.

Flipping one switch never makes another switch's version stale and never moves its change time; the
global switch keeps the row's version and `updated_at`.

- Both are **off at deploy**. Going live takes two steps: switching the family on opens the reads,
  switching the writes on opens the writes.
- **Admins only** flip them, on the „Verbundene Apps" admin page, through
  `PUT /api/v1/connected-apps/admin/settings/job-orders {enabled, version}` and
  `PUT /api/v1/connected-apps/admin/settings/job-order-writes {enabled, version}`; `GET` on the same
  paths reads them as `{enabled, updatedAt, version}`. A stale `version` answers `409`.
- **Propagation** is the bank switch's: switching off reaches the gateway's registry mirror
  **before** the commit or not at all, switching on reaches it after the commit, and the gateway
  reads the mirror through its five-second cache. The mirror document carries `jobOrdersEnabled`
  and `jobOrderWritesEnabled`; a document without one of them reads it as off.

**The answers.**

- **Family off:** every path at or below `/exchange/v1/job-orders` answers
  `503 JOB_ORDER_EXCHANGE_DISABLED` with `Retry-After: 300`, whether a route serves it or not.
- **Writes off, family on:** every `POST` at or below the family answers
  `503 JOB_ORDER_WRITES_DISABLED` with `Retry-After: 300`; every job-order write is a `POST` and
  every read a `GET`, so the reads go on.
- With both on, a job-order path no route serves is `404 NOT_FOUND`.
- Membership is judged on the **decoded** path segments, as routing matches them, so a
  percent-encoded spelling such as `/exchange/v1/job%2Dorders` is a job-order path too; gateway and
  backend judge it alike.
- **Precedence:** the global switch wins (`503 EXCHANGE_DISABLED`, `Retry-After: 30`), then the
  family switch, then the writes switch.
- Both job-order answers come **before the daily write quota** and count no write.
- The backend's exchange gate re-checks both switches for every path at or below
  `/api/v1/exchange/job-orders`, with the same codes and status (REQ-XCH-025), and the gateway passes
  that refusal on with `Retry-After: 300`.
- A client treats both answers as "wait, then retry", for the job-order area or for its writes only,
  and keeps syncing its other areas.

**Audit and notice.** Every change of either switch writes `JOB_ORDER_EXCHANGE_SWITCH_CHANGED` with
`{switch: family|writes, enabled}` in the „Verbundene Anwendungen" audit area (`CONNECTED_APPS`,
REQ-AUDIT-001), and publishes `JOB_ORDER_EXCHANGE_SWITCHED_ON` or `JOB_ORDER_EXCHANGE_SWITCHED_OFF`
with the coded render parameter `switchCode` (`family` / `writes`, REQ-NOTIF-028). The default rules
notify the admins only (`ROLE ADMIN`), never the acting admin; a notice replaces the recipient's
earlier notice about the same switch, so each switch shows its latest state only.

**Monitoring** (REQ-OBS-025): the gateway counts its switch refusals on
`basetool_ingest_exchange_refused_total{reason=job_order_exchange_disabled|job_order_writes_disabled}`,
the backend counts each committed flip on `basetool_exchange_registry_changes_total{action}`
(`job_order_switch_on` / `_off`, `job_order_writes_switch_on` / `_off`), which
`JobOrderExchangeSwitchChanged` watches.

**Acceptance**

- [x] Both switches start off, are set by an admin only and have their own version; a stale version
  is `409`, and no switch stales another's version or moves its change time
  (`AdminExchangeRegistryControllerTest`, `ExchangeRegistryMirrorIntegrationTest`).
- [x] Switching either off reaches the mirror before the commit, switching on only after it; a
  mirror document without a job-order field reads it as off (`ExchangeRegistryMirrorIntegrationTest`,
  `ExchangeRegistrySnapshotTest`, `ExchangeMirrorSeamParityTest`, ingest `ExchangeRegistryReaderTest`).
- [x] While the family is off, every job-order path answers `503 JOB_ORDER_EXCHANGE_DISABLED`, a
  path no route serves included; while only the writes are off, every job-order `POST` answers
  `503 JOB_ORDER_WRITES_DISABLED`; both with `Retry-After: 300` on the gateway and in the backend
  gate, in the order global, family, writes (ingest and backend `ExchangeGateTest`,
  `JobOrderExchangeRoutesTest`, `ExchangeRelayTest`, `ExchangeRelaySeamParityTest`).
- [x] A percent-encoded job-order path is a job-order path on both sides, and no switch refusal
  counts a write (ingest `ExchangeGateTest`, `JobOrderExchangeRoutesTest`).
- [x] Every change is audited with its switch and notifies the admins only, superseding the
  recipient's earlier notice of that switch (`ExchangeRegistryNoticeTest`).
- [x] The admin page shows both switches with their own version and relays a conflict as a conflict
  (`AdminExchangeClientsPageControllerMvcTest`).
- [x] The family's refusal counter registers every reason at zero, and the backend gate counts its
  switch refusals as `switched_off` and `writes_switched_off` (`ExchangeJobOrderMetricsTest`,
  backend `ExchangeGateTest`).
- [x] `writesEnabled` in `create-options` tells whether the writes switch is on
  (`ExchangeJobOrderCreateTest`).
- [ ] The open-order cap and the daily create cap, with `409 JOB_ORDER_OPEN_ORDER_CAP`,
  `409 JOB_ORDER_DAILY_CREATE_CAP` and `limits` in `create-options`, come with their slice.

**Enforced by:** `ExchangeGate`, `JobOrderExchangeRoutes`, `ExchangeSwitch`,
`ExchangeRegistryService#updateJobOrdersSwitch`, `#updateJobOrderWritesSwitch`,
`ExchangeRegistrySnapshot`, `ExchangeJobOrderMetrics` (backend) · `ExchangeGateFilter`,
`ExchangeRoutes#isJobOrder`, `ExchangeRegistryReader`, `ExchangeRelay` (gateway) ·
`ExchangeSeam.GATE_STATUSES`, `ExchangeSeam.REGISTRY_DOCUMENT_FIELDS` (shared seam) · V444 ·
**Related:** REQ-XCH-003, REQ-XCH-023, REQ-XCH-025, REQ-BXCH-006, REQ-AUDIT-001, REQ-NOTIF-028,
REQ-OBS-025 · **Issues:** #3618, #3621, #3642
