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
`POST /exchange/v1/job-orders`, within an open-order cap and a daily create cap, cancels them
before any money moved through `POST /exchange/v1/job-orders/{id}/cancel`, changes their target
above the net handover through `POST /exchange/v1/job-orders/{id}/target` and changes their comment
through `POST /exchange/v1/job-orders/{id}/comment`. Both switches are off until an admin turns them
on.

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
> ADR-0408).

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
  (ADR-0408); the exchange's writes on an existing order pass it. A hidden, an unknown and a deleted
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
`JobOrderWriterRule.DIRECT_LOGISTICIAN` of ADR-0408, decided from the database on every request.

- Mere membership makes no writer, and reach over a unit the member does not belong to counts not.
- **Requester direction only:** a client creates an order only with one of the member's writer units
  as the requesting unit; the responsible unit may be any eligible Profit unit. A writer of the
  responsible unit creates nothing on behalf of another unit through a client; that stays in the
  web (REQ-ORDERS-053).
- The requesting unit is a unit the member directly belongs to, so an order a member creates
  through a client is always in their read set.
- An administrator is a plain member: no admin pass, no all-scope, no pin.
- For the same member without a pin, every pair the rule permits is one the web's contextual rule
  permits, so a client never creates what the web would refuse (ADR-0408 point 5).

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
REQ-ORDERS-053, REQ-ORG-032, REQ-SEC-015 · ADR-0408, ADR-0407 · **Issues:** #3608, #3610, #3611,
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
> snapshot (#3644), the per-unit and per-member logs with the feed and its tombstone (#3645) and
> the nightly window run (#3649); decided in #3615, #3618, ADR-0406.

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
- A reopened order and a client's own write return as items. An order whose 7 days ended arrives
  as a `removed` entry after the nightly window run.
- The log keeps 90 days; an older cursor answers `410 CURSOR_EXPIRED` and the client takes a new
  snapshot (`docs/exchange/sync-guide.md`).
- Each page counts one `feed` read for its client (REQ-OBS-025).

**The window run.** `JobOrderWindowTask` runs nightly (`app.exchange.job-order-window.cron`,
default 04:00 UTC), whatever the job-order switches say, on one backend instance at a time
(two-key advisory lock space `OXWN`). A failed run rolls back whole; the next night retries it.

- It records every fingerprinted order whose 7 days after `closed_at` ended, and that is no aUEC
  order whose last fingerprint says `settlementOpen`, as having left the set
  (`exchange_job_order_fingerprint.left_set_at`, V449). The fingerprint row's trigger announces
  each such order to the units and members it was last announced to, so a feed page resolves it to
  a `removed` entry: a closed order stays in the feed for at most 7 days plus one run.
- A fingerprint written while the order lies outside the set records that itself: the write
  announced the order already or, as a first fingerprint, reached no client. An aUEC order whose
  settlement closes after its 7 days therefore leaves at once through its fingerprint, not through
  the run, and a rerun announces nothing.
- A fingerprint written inside the set clears `left_set_at`, so a reopened order that closes again
  leaves again after its new 7 days.
- The same run clears the creator of every order outside the set (REQ-OXCH-010).

**Monitoring:** the worker job, the recomputation counter, the backlog gauges and their three
alerts, and the window run's job are REQ-OBS-025.

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
- [x] The window run announces an order whose 7 days ended where it was last announced, leaves one
  closed just under 7 days ago in the set, announces nothing on a rerun and announces a reopened
  order again once its new 7 days ended (`JobOrderWindowRunIntegrationTest`).
- [x] An aUEC order with an open settlement stays past its 7 days and leaves through its fingerprint
  once the settlement closes, not through the run; an order first fingerprinted after its 7 days is
  never announced (`JobOrderWindowRunIntegrationTest`).
- [x] After the window run the feed answers an order whose 7 days ended as `{ key, removedAt }`
  (`ExchangeJobOrderFeedTest`).

**Enforced by:** `JobOrderExchangeReads`, `JobOrderExchangeReadProjection` (job orders) ·
`JobOrderFingerprints`, `JobOrderFingerprintRecomputation`, `JobOrderFingerprintTask`,
`ExchangeJobOrderMarkRepository`, `ExchangeJobOrderFingerprintRepository`, `ExchangeJobOrderReadService`,
`ExchangeFeedReader#jobOrderChanges`, `ExchangeChangeRepository#findAnnouncedJobOrders`,
`ExchangeJobOrderController`, `JobOrderWindowRun`, `JobOrderWindowTask` (exchange) ·
`ExchangeJobOrderController` (gateway) · V445, V447, V449 · **Related:** REQ-XCH-013,
REQ-ORDERS-054, REQ-ORDERS-055, REQ-OXCH-001, REQ-OXCH-002, REQ-OXCH-009, REQ-OXCH-010,
REQ-OBS-025 · ADR-0224, ADR-0406, ADR-0407 · **Issues:** #3615, #3618, #3643, #3644, #3645, #3649

### REQ-OXCH-004 — Create options and the create of an aUEC order

> [!note] Implemented (#3646, the caps and `limits` with #3647; decided in #3611, #3614, #3617,
> #3618, #3619, ADR-0408, ADR-0407).

**Create options.** `GET /exchange/v1/job-orders/create-options` (`exchange.joborders.write`),
relayed to `GET /api/v1/exchange/job-orders/create-options`, answers
`job-order-create-options.schema.json` from the web's create-options projection under the writer
rule of REQ-OXCH-001 (`JobOrderCreationOptions.current(DIRECT_LOGISTICIAN, actor)`, ADR-0408):

- `requestingUnits`: the member's writer units, as `{id, name, shorthand, kind}`;
- `responsibleUnits`: the eligible Profit units, in the same shape;
- `suggestedResponsible`: the canonical Profit area, the projection's aUEC suggestion;
- `writesEnabled`: whether the job-order writes switch is on (REQ-OXCH-009);
- `limits`: `{ openOrderCap, openOrders, dailyCreateCap, createdToday }`, both caps of REQ-OXCH-009
  with the member's own client-created open orders and creates of the current UTC day, so a client
  greys out the create before the member fills in the form.

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

The create runs the web's aUEC create command (`JobOrderCreateCommand.createAuec`, ADR-0408) with
the writer rule `DIRECT_LOGISTICIAN` and an actor carrying the member and the client: the same order
locks, unit resolution, eligible-Profit check, priority, created notice, `orders/queue` frame after
the commit (REQ-XCH-030) and audit event as a web create. Nothing is filled in.

**Order of checks.** Schema (the gateway's schema, then the backend's bean rules) → the idempotency
lookup → the pair → the open-order cap → the daily create cap → the insert. A known key answers
`201` with its order whatever the caps say; a cap refusal (`409 JOB_ORDER_OPEN_ORDER_CAP`,
`409 JOB_ORDER_DAILY_CREATE_CAP`, REQ-OXCH-009) creates nothing. The lookup and both counts run under
the member's client-create lock, so two racing creates never both pass a cap. A pair that is not
permitted — an unknown unit, a requesting unit
the member does not write for, a responsible unit that is not an eligible Profit unit — answers one
`400 JOB_ORDER_UNITS_NOT_OFFERED` with a fixed detail that names no side; the client reads the
create options again and lets the member choose. The command's own refusals (`400` for an unknown
or ineligible unit, `403` for a pair the rule refuses) map to this one code.

**The stored key.** A client's create stores the creating client (`job_order.created_by_client_id`),
the creating member (`created_by_member_id`, `ON DELETE SET NULL`) and the create's key
(`create_idempotency_key`), unique per member, client and key (V448). The window run clears the
member and the key once the order leaves the client set (REQ-OXCH-010).

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
`basetool_exchange_job_order_refused_total{reason="units_not_offered"}`, a cap refusal under
`open_order_cap` or `daily_create_cap`.

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
- [x] The pair is checked before the caps, the open-order cap before the daily create cap, and a
  known key answers its order with `201` at either cap; two creates racing for a cap's last slot
  insert one order (`ExchangeJobOrderCreateTest`).
- [x] The create options carry `limits` with both caps and the member's own client-created open
  orders and creates of the UTC day; web creates and another member's creates count for neither
  (`ExchangeJobOrderCreateTest`, ingest `ExchangeJobOrderWriteRouteTest`, `ExchangeContractTest`).

**Enforced by:** `JobOrderCreateCommand`, `JobOrderAuecCreation`, `JobOrderClientCreateRepository`,
`JobOrderClientCreateCaps`, `JobOrderCreationOptions`, `JobOrderResponsibilityAudit`,
`JobOrderExportSections` (job orders) ·
`ExchangeJobOrderCreateService`, `ExchangeJobOrderWriteController` (backend exchange) ·
`ExchangeJobOrderWriteController`, `ExchangeRoutes`, `ExchangeRelay.DETAILS` (gateway) ·
`job-order-write.schema.json`, `job-order-create-options.schema.json` · V448 · **Related:**
REQ-XCH-020, REQ-XCH-025, REQ-XCH-030, REQ-XCH-048, REQ-ORDERS-053, REQ-AUDIT-001, REQ-NOTIF-008,
REQ-SEC-058, REQ-OBS-025, REQ-OXCH-001, REQ-OXCH-005, REQ-OXCH-009 · ADR-0408, ADR-0407 ·
**Issues:** #3611, #3614, #3617, #3618, #3619, #3646, #3647

### REQ-OXCH-005 — Shared rules of the job-order writes

> [!note] Implemented for the create (#3646) and, with the rules for an existing order, for the
> cancel (#3650), the target change (#3651) and the comment (#3652); decided in #3613, #3614, #3616,
> ADR-0407, ADR-0408.

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
  responsibility → status → the write's own rule. The first refusal answers:
  - the gateway checks the order id as a UUID and the body against the write's schema:
    `400 SCHEMA_INVALID`;
  - an unknown, hidden or deleted order answers the same `404 NOT_FOUND`; visibility is the read set
    of REQ-OXCH-001 (`JobOrderReadCheck.OWN_RELATION`, decided for the request's acting member);
  - an order the member reads but does not write for by the writer rule of REQ-OXCH-001, and any
    MATERIAL or ITEM order: `403 NOT_PERMITTED`;
  - a stale section version: `409 VERSION_CONFLICT`;
  - an invalid responsibility (REQ-ORDERS-055): `409 JOB_ORDER_RESPONSIBILITY_INVALID`, the
    exchange's code in place of the web's `400`;
  - a status the write does not admit: `409 JOB_ORDER_CLOSED`;
  - then the write's own rule.
- **One decision under the order locks.** The rules run in the web's command (ADR-0408), before
  and again under the order locks, so a concurrent web write, money submission or hierarchy change
  yields one coherent outcome. They are one unit, `JobOrderClientWriteRules`, which every client
  write on an existing order passes with its writer decision, its section version and the statuses
  it admits; the web's commands keep their own order and codes.
- **A refusal writes nothing**: no change, no audit event and no notice.
- **One quota unit per call** (REQ-XCH-023): each write counts one unit of the daily write quota; a
  refusal by a job-order switch counts none (REQ-OXCH-009).
- **No journal, no undo and no mass-change guard** (REQ-XCH-021, REQ-XCH-022, ADR-0407 point 7): a
  wrong write is repaired in the web, found through the audit viewer's client filter.
  - The member's undo page under „Verbundene Anwendungen" (`connectedApps.undo.body`) says that job
    orders are not undone and that a cancel is reopened, and target and comment are set back, in
    the Basetool.
  - The admin's bulk-undo page (`admin.exchangeClients.undo.body`) says that bank requests and job
    orders are not undone and that a client's changes to them are found in the audit viewer with
    the client filter.
- **Fixed details:** every backend refusal reaches the client with its registry code and the fixed
  English detail of `ExchangeRelay.DETAILS` (REQ-XCH-025).

**Acceptance**

- [x] The create is a single route on the acting list that needs an `Idempotency-Key`, counts one
  quota unit and relays the key (`ExchangeRoutes`, ingest `ExchangeJobOrderWriteRouteTest`,
  `ExchangeGoldenAnswerTest`, `FilterExemptionSetsTest`, both `ExchangeRelaySeamParityTest`s).
- [x] A refusal by the writes switch comes before the quota and counts no write (ingest
  `ExchangeGateTest`); the backend gate closes the create while the writes switch is off
  (`ExchangeJobOrderCreateTest`).
- [x] The cancel is a single route on the acting list that needs an `Idempotency-Key`, relays no
  key, checks the order id and the body before the relay, and needs the write scope (ingest
  `ExchangeJobOrderWriteRouteTest`, `ExchangeGoldenAnswerTest`, `ExchangeContractTest`,
  `FilterExemptionSetsTest`, both `ExchangeRelaySeamParityTest`s).
- [x] An unknown, a hidden and a deleted order answer alike; a reader who does not write and a
  responsible-side writer, and every MATERIAL or ITEM order, are not permitted; a stale version, an
  invalid responsibility and a closed order answer their codes, in the order above, and write
  nothing (`ExchangeJobOrderCancelTest`).
- [x] The backend's refusals reach the client with the registry's codes and fixed details (ingest
  `ExchangeJobOrderWriteRouteTest`).
- [x] The target change is a single route on the acting list with its named `targetVersion`, and
  answers its refusals in the order above (`ExchangeJobOrderTargetTest`, ingest
  `ExchangeJobOrderWriteRouteTest`, `ExchangeGoldenAnswerTest`).
- [x] The comment change runs the same rules with `commentVersion` as its named section version and
  the same route checks (`ExchangeJobOrderCommentTest`, ingest `ExchangeJobOrderWriteRouteTest`,
  `ExchangeGoldenAnswerTest`).
- [x] The member's undo page and the admin's bulk-undo page say in DE and EN that job orders are
  not undone and where they are set right (`JobOrderConnectedAppsTextsTest`).

**Enforced by:** `ExchangeRoutes`, `ExchangeIdempotencyFilter`, `ExchangeLimitFilter`,
`ExchangeRelay`, `ExchangeJobOrderWriteController` (gateway) · `ActingMemberFilter`,
`ExchangeGate`, `ExchangeJobOrderChangeService` (backend exchange) · `JobOrderClientWriteRules`,
`JobOrderClientWriteRefusedException`, `JobOrderEditor` (job orders) · **Related:** REQ-XCH-020,
REQ-XCH-021, REQ-XCH-022, REQ-XCH-023, REQ-XCH-025, REQ-ORDERS-055, REQ-OXCH-001, REQ-OXCH-004,
REQ-OXCH-009 · ADR-0407, ADR-0408 · **Issues:** #3613, #3614, #3616, #3646, #3650, #3652, #3654

### REQ-OXCH-006 — Cancel an aUEC order

> [!note] Implemented (#3650; decided in #3613, #3614, #3616, #3617, ADR-0407, ADR-0408).

`POST /exchange/v1/job-orders/{id}/cancel` (`exchange.joborders.write`) with a mandatory
`Idempotency-Key`, relayed without the key to `POST /api/v1/exchange/job-orders/{id}/cancel`:

- body `job-order-write.schema.json#/$defs/cancel`: `{ lifecycleVersion }`, the order's
  `versions.lifecycle` as last read;
- answer `200` `job-order-write.schema.json#/$defs/written`: `{ id, status, versions }`, read from
  the stored order after the cancel.

**The rules.** The shared rules of REQ-OXCH-005 with the lifecycle version as the section version;
the writer is a direct Logistician of the requesting unit (a writer of the responsible side writes
nothing through a client), and only an `OPEN` or `IN_PROGRESS` order admits the cancel. Then the
cancel's own rule: once any monetary operation of the order has reached its accepted, durable
submission boundary — the hard-delete boundary of REQ-ORDERS-063
(`JobOrderRepository.hasDurableMoneySubmission`) — the cancel answers
`409 JOB_ORDER_MONEY_STARTED`, one refusal for every money fact, naming none. The guard is decided
under the order locks, so a first money submission racing the cancel either lands first and refuses
the cancel, or meets a cancelled order. Such an order holds no money, no waiting Bank attempt and
nothing to settle, so a client's cancel has no money or Bank effect.

**Through the web's status command.** The cancel runs `JobOrderStatusCommand.cancel` with the
writer rule `DIRECT_LOGISTICIAN`, the read check `OWN_RELATION` and the actor
`JobOrderActor.Client(member, clientId)` (ADR-0408): the same locks, priority release, earmark
release, end of open unit commitments (REQ-ORDERS-059), closing instant, live update of the order
page and the overview after the commit (REQ-XCH-030), and the web's finished notice to the current
writers of both units without the member, which names the application (REQ-ORDERS-042,
REQ-NOTIF-028), with its prompt cleanup. No client reopens a cancellation: a mistaken cancel is
reopened in the Basetool, and no undo exists (REQ-OXCH-005).

**Audit.** A client's cancel writes `JOB_ORDER_CANCELLED_VIA_CLIENT` with `{previousStatus}`
instead of `JOB_ORDER_STATUS_CHANGED`, and the client in `client_id` as on every row
(REQ-AUDIT-001, REQ-AUDIT-005); a refusal records nothing.

**Retries.** The backend stores no key for the cancel: a retry under the same key within the
gateway's hour replays the cached answer (REQ-XCH-020), and after it the moved lifecycle version
answers `409 VERSION_CONFLICT`; the client reads the order again.

**Monitoring** (REQ-OBS-025). Every cancel counts on
`basetool_exchange_writes_total{resource="job_order", outcome}`, each refusal also on
`basetool_exchange_job_order_refused_total` under `not_found`, `not_permitted`,
`version_conflict`, `responsibility_invalid`, `closed` or `money_started`;
`JobOrderExchangeNotFoundSpike` fires on more than 50 `not_found` refusals in an hour.

**Acceptance**

- [x] A Logistician of the requesting unit cancels an open or an in-progress aUEC order; the answer
  carries the order's id, status and versions and matches its schema (`ExchangeJobOrderCancelTest`).
- [x] An order a money operation of which was accepted answers `409 JOB_ORDER_MONEY_STARTED`
  naming no money fact and stays open, also when the first submission holds the order lock while
  the cancel arrives; a closed order answers `JOB_ORDER_CLOSED` before its money is asked about
  (`ExchangeJobOrderCancelTest`).
- [x] A retry after the cache answers `409 VERSION_CONFLICT` (`ExchangeJobOrderCancelTest`).
- [x] A client's cancel writes `JOB_ORDER_CANCELLED_VIA_CLIENT` with the client and the previous
  status, a refusal nothing; the responsible side's writer gets the finished notice naming the
  application, the member none (`ExchangeJobOrderCancelTest`).
- [x] Every cancel counts its outcome and every refusal its reason; the writes switch and the read
  scope close the route (`ExchangeJobOrderCancelTest`, ingest `ExchangeJobOrderWriteRouteTest`).
- [x] The alert fires on more than 50 `not_found` refusals in an hour and stays silent below
  (`job_order_exchange_not_found_alerts_test.yml`).

**Enforced by:** `JobOrderStatusCommand`, `JobOrderCancellation`, `JobOrderClientWriteRules`,
`JobOrderStatusAudit`, `JobOrderService#changeStatus` (job orders) ·
`ExchangeJobOrderChangeService`, `ExchangeJobOrderWriteController` (backend exchange) ·
`ExchangeJobOrderWriteController`, `ExchangeRoutes`, `ExchangeRelay.DETAILS` (gateway) ·
`job-order-write.schema.json` · `JobOrderExchangeNotFoundSpike` · **Related:** REQ-XCH-020,
REQ-XCH-025, REQ-XCH-030, REQ-ORDERS-042, REQ-ORDERS-054, REQ-ORDERS-059, REQ-ORDERS-063,
REQ-AUDIT-001, REQ-NOTIF-028, REQ-OBS-025, REQ-OXCH-001, REQ-OXCH-005 · ADR-0407, ADR-0408 ·
**Issues:** #3613, #3614, #3616, #3617, #3650

### REQ-OXCH-007 — Change an aUEC order's target

> [!note] Implemented (#3651; decided in #3613, #3614, #3617, ADR-0407, ADR-0408).

`POST /exchange/v1/job-orders/{id}/target` (`exchange.joborders.write`) with a mandatory
`Idempotency-Key`, relayed without the key to `POST /api/v1/exchange/job-orders/{id}/target`:

- body `job-order-write.schema.json#/$defs/targetChange`: `{ target, targetVersion }`, the new
  target as an integer in whole aUEC from 1 to 10¹² and the order's `versions.target` as last read;
- answer `200` `job-order-write.schema.json#/$defs/targetChanged`: `{ id, status, versions, target }`,
  read from the stored order after the change.

**The rules.** The shared rules of REQ-OXCH-005 with the target version as the section version; the
writer is a direct Logistician of the requesting unit (a writer of the responsible side writes
nothing through a client), and only an `OPEN` or `IN_PROGRESS` order admits the change, so a
completed order's target stays in the Basetool (`409 JOB_ORDER_CLOSED`). Then the target's own rule:
the new target must lie **above** the order's current net handover (`netHandedOver` of REQ-OXCH-002,
the effective handover of REQ-ORDERS-064), else `409 JOB_ORDER_TARGET_TOO_LOW`. Raising and lowering
are both admitted. Decided under the order locks, so a handover racing the change either lands first
and the change is measured against it, or meets the changed target. The change therefore never
completes or reopens the order and never touches a Bank attempt; completing an order stays in the
Basetool. A target equal to the stored one changes nothing and answers the current state.

**Through the web's target command.** The change runs `JobOrderTargetCommand.changeTarget` with the
writer rule `DIRECT_LOGISTICIAN`, the read check `OWN_RELATION` and the actor
`JobOrderActor.Client(member, clientId)` (ADR-0408): the same account and order locks, the target
history entry with its actor and its client, shown as „über <App>" (REQ-ORDERS-063,
REQ-ORDERS-078), the live update of the order page and the overview after the commit
(REQ-XCH-030), and the web's need-change notice to the responsible side without the member, which
names the application (REQ-NOTIF-017, REQ-NOTIF-028).

**Audit.** A client's target change writes `JOB_ORDER_TARGET_CHANGED_VIA_CLIENT` with
`{previousTarget, target}` instead of `JOB_ORDER_MONEY_TARGET_CHANGED`, and the client in
`client_id` as on every row (REQ-AUDIT-001, REQ-AUDIT-005); a refusal records nothing.

**Retries.** The backend stores no key for the target change: a retry under the same key within the
gateway's hour replays the cached answer (REQ-XCH-020), and after it the moved target version
answers `409 VERSION_CONFLICT`; the client reads the order again.

**Monitoring** (REQ-OBS-025). Every target change counts on
`basetool_exchange_writes_total{resource="job_order", outcome}`, each refusal also on
`basetool_exchange_job_order_refused_total` under the shared reasons of REQ-OXCH-005 or
`target_too_low`.

**Acceptance**

- [x] A Logistician of the requesting unit raises and lowers the target of an active aUEC order
  above its net handover; the answer carries the order's id, status, versions and target and
  matches its schema, and the order stays in its status (`ExchangeJobOrderTargetTest`).
- [x] A target equal to the net handover answers `409 JOB_ORDER_TARGET_TOO_LOW` and changes
  nothing, also when a handover holding the order lock lands while the change waits
  (`ExchangeJobOrderTargetTest`).
- [x] A completed order answers `409 JOB_ORDER_CLOSED`; the refusals come in the shared order, the
  target's own rule last (`ExchangeJobOrderTargetTest`).
- [x] A retry after the cache answers `409 VERSION_CONFLICT` (`ExchangeJobOrderTargetTest`).
- [x] A client's change writes `JOB_ORDER_TARGET_CHANGED_VIA_CLIENT` with the client and both
  targets, a refusal nothing; the history entry records the client; the responsible side's writer
  gets the need-change notice naming the application, the member none (`ExchangeJobOrderTargetTest`).
- [x] Every change counts its outcome and every refusal its reason; the writes switch and the read
  scope close the route (`ExchangeJobOrderTargetTest`, ingest `ExchangeJobOrderWriteRouteTest`).

**Enforced by:** `JobOrderTargetCommand`, `JobOrderMoneyOrderChanges`, `JobOrderClientWriteRules`,
`JobOrderEditor#editMoney` (job orders) · `ExchangeJobOrderChangeService`,
`ExchangeJobOrderWriteController` (backend exchange) · `ExchangeJobOrderWriteController`,
`ExchangeRoutes`, `ExchangeRelay.DETAILS` (gateway) · `job-order-write.schema.json` · **Related:**
REQ-XCH-020, REQ-XCH-025, REQ-XCH-030, REQ-ORDERS-063, REQ-ORDERS-064, REQ-ORDERS-078,
REQ-AUDIT-001, REQ-NOTIF-017, REQ-NOTIF-028, REQ-OBS-025, REQ-OXCH-001, REQ-OXCH-002,
REQ-OXCH-005 · ADR-0407, ADR-0408 · **Issues:** #3613, #3614, #3617, #3651

### REQ-OXCH-008 — Change the comment of an aUEC order

> [!note] Implemented (#3652; decided in #3613, #3614, #3617, ADR-0407, ADR-0408).

`POST /exchange/v1/job-orders/{id}/comment` (`exchange.joborders.write`) with a mandatory
`Idempotency-Key`, relayed without the key to `POST /api/v1/exchange/job-orders/{id}/comment`:

- body `job-order-write.schema.json#/$defs/comment`: `{ comment, commentVersion }`; `comment` is
  plain text of at most 1000 characters, the web's bean rules (`JobOrder.COMMENT_MAX_LENGTH`),
  trimmed, and `null` or blank clears it; `commentVersion` is the order's `versions.comment` as last
  read;
- answer `200` `job-order-write.schema.json#/$defs/written`: `{ id, status, versions }`, read from
  the stored order after the write.

**The rules.** The shared rules of REQ-OXCH-005 with the comment version as the section version;
the writer is a direct Logistician of the requesting unit (a writer of the responsible side writes
nothing through a client). The comment keeps the web's status rule: a `CANCELLED` order answers
`409 JOB_ORDER_CLOSED`, every other status admits it. The comment has no further rule of its own.

**A comment-only write.** The write runs `JobOrderInformationCommand.changeComment` with the writer
rule `DIRECT_LOGISTICIAN`, the read check `OWN_RELATION` and the actor
`JobOrderActor.Client(member, clientId)` (ADR-0408), through the web's money edit under the order
and account locks. It checks and raises only the comment version (REQ-ORDERS-066): the contact is
never written through a client after the create, and the information version is neither checked
nor moved, so a contact change in the web before or during the write never conflicts with it. A
write that leaves the stored comment as it is changes nothing and answers the current versions.
A changed comment records its client (`job_order.comment_client_id`, REQ-ORDERS-078), which shows
"über <App>" on the order until a web write of the comment clears it, publishes the web's live
update of the order page and the overview after the commit (REQ-XCH-030), and raises the web's
need-change notice to the responsible side without the member, which names the application
(REQ-NOTIF-017, REQ-NOTIF-028).

**Audit.** A changed comment writes `JOB_ORDER_COMMENT_CHANGED_VIA_CLIENT` without details, since
the text is never recorded, instead of `JOB_ORDER_UPDATED`, with the client in `client_id` as on
every row (REQ-AUDIT-001, REQ-AUDIT-005); a refusal and an unchanged comment record nothing.

**Retries.** The backend stores no key for the comment: a retry under the same key within the
gateway's hour replays the cached answer (REQ-XCH-020), and after it the moved comment version
answers `409 VERSION_CONFLICT`; the client reads the order again.

**Monitoring** (REQ-OBS-025). Every comment change counts on
`basetool_exchange_writes_total{resource="job_order", outcome}`, each refusal also on
`basetool_exchange_job_order_refused_total` under `not_found`, `not_permitted`,
`version_conflict`, `responsibility_invalid` or `closed`; `JobOrderExchangeNotFoundSpike` covers
the comment's `not_found` refusals too.

**Acceptance**

- [x] A Logistician of the requesting unit changes the comment alone: the answer carries the order's
  id, status and versions and matches its schema, the comment version moves, the contact and the
  information version do not, and the comment records the client (`ExchangeJobOrderCommentTest`).
- [x] A contact change in the web holding the order lock never conflicts with the comment write,
  nor does one through the web's information form before it, which keeps the client marker
  (`ExchangeJobOrderCommentTest`, `JobOrderClientOriginIntegrationTest`).
- [x] A stale comment version, also on a retry after the cache, answers `409 VERSION_CONFLICT`; a
  cancelled order `409 JOB_ORDER_CLOSED`, a completed one takes the comment; a blank comment clears
  it and an unchanged one writes nothing; a comment above 1000 characters is refused
  (`ExchangeJobOrderCommentTest`, ingest `ExchangeJobOrderWriteRouteTest`).
- [x] The refusals come in the order of REQ-OXCH-005 and write nothing (`ExchangeJobOrderCommentTest`,
  `JobOrderWriteRuleIntegrationTest`).
- [x] A client's comment writes `JOB_ORDER_COMMENT_CHANGED_VIA_CLIENT` without details, a refusal
  nothing; the responsible side's writer gets the need-change notice naming the application, the
  member none; a later web write of the comment clears the marker (`ExchangeJobOrderCommentTest`,
  `JobOrderClientOriginIntegrationTest`).
- [x] Every comment change counts its outcome and every refusal its reason; the writes switch and
  the read scope close the route (`ExchangeJobOrderCommentTest`, ingest
  `ExchangeJobOrderWriteRouteTest`).

**Enforced by:** `JobOrderInformationCommand`, `JobOrderMoneyOrderChanges#changeComment`,
`JobOrderClientWriteRules`, `JobOrderComment`, `JobOrderEditor` (job orders) ·
`ExchangeJobOrderChangeService`, `ExchangeJobOrderWriteController` (backend exchange) ·
`ExchangeJobOrderWriteController`, `ExchangeRoutes` (gateway) · `job-order-write.schema.json` ·
**Related:** REQ-XCH-020, REQ-XCH-025, REQ-XCH-030, REQ-ORDERS-054, REQ-ORDERS-066,
REQ-ORDERS-078, REQ-AUDIT-001, REQ-NOTIF-017, REQ-NOTIF-028, REQ-OBS-025, REQ-OXCH-001,
REQ-OXCH-005 · ADR-0407, ADR-0408 · **Issues:** #3613, #3614, #3617, #3652

### REQ-OXCH-009 — The job-order switches and caps

> [!note] Implemented for the two switches and their codes (#3642; decided in #3618, #3621), for
> `writesEnabled` in `create-options` (#3646, REQ-OXCH-004) and for the open-order cap, the daily
> create cap and `limits` in `create-options` (#3647).

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

**The caps on client creates.** Two caps bound the aUEC orders a member creates through clients,
each counted per member from the orders themselves (`created_by_member_id` and
`created_by_client_id`, V448), so orders created in the Basetool never count and are never refused:

- **Open-order cap** `app.joborder.exchange.open-order-cap` (default 20, 1 to 1000): the member's
  orders in `OPEN` or `IN_PROGRESS` created through any client. At the cap the next client create
  answers `409 JOB_ORDER_OPEN_ORDER_CAP`. Any closing frees the slot, a client cancel as well as a
  completion or rejection in the web.
- **Daily create cap** `app.joborder.exchange.daily-create-cap` (default 20, 1 to 1000): the
  orders the member created through clients since the last UTC midnight, by their creation
  instant and whatever their status since; a cancelled order still counts. At the cap the next
  client create answers `409 JOB_ORDER_DAILY_CREATE_CAP`; the gateway adds `Retry-After` with the
  seconds until the next UTC midnight, from the same day boundary as its daily write quota
  (`ExchangeRelay.retryAfterSeconds`, `ExchangeQuotas#secondsUntilTomorrow`).
- Both are checked after the pair and before the insert, the open-order cap first, under the
  member's client-create lock (REQ-OXCH-004); a create under a known key answers `201` whatever the
  caps say.
- The caps refuse creates only. Every other job-order write stays possible at either cap, and a
  cap refusal counts its unit of the daily write quota like every write attempt (REQ-XCH-023).
- `409`, not `429`: `RATE_LIMITED` and `QUOTA_EXCEEDED` tell a client that the member's whole write
  budget at that client is spent, while these caps refuse creates only.
- The bound this gives: a looping client raises at most 20 created and 20 finished notices a day per
  member for each writer of both units (REQ-NOTIF-008).
- Both codes pass the relay with their fixed detail (`ExchangeRelay.DETAILS`, REQ-XCH-025).
- **Visible in advance:** `create-options` carries `limits` (REQ-OXCH-004), the member's own
  figures only.

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
`JobOrderExchangeSwitchChanged` watches. A cap refusal counts on
`basetool_exchange_job_order_refused_total{reason=open_order_cap|daily_create_cap}`, which
`JobOrderExchangeCapHit` watches.

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
- [x] At the open-order cap a client create answers `409 JOB_ORDER_OPEN_ORDER_CAP`, an in-progress
  order holds its slot and any closing frees it; at the daily create cap it answers
  `409 JOB_ORDER_DAILY_CREATE_CAP`, cancelled orders included, counted from UTC midnight; both
  count their refusal reason and a rejected write, and orders created in the web neither count nor
  are refused (`ExchangeJobOrderCreateTest`).
- [x] Both codes pass the relay with their fixed detail, and the daily cap's answer carries
  `Retry-After` until the next UTC midnight (ingest `ExchangeRelayTest`,
  `ExchangeJobOrderWriteRouteTest`, both `ExchangeRelaySeamParityTest`s, `ExchangeContractTest`).

**Enforced by:** `ExchangeGate`, `JobOrderExchangeRoutes`, `ExchangeSwitch`,
`ExchangeRegistryService#updateSwitch`, `ExchangeRegistrySnapshot`, `ExchangeJobOrderMetrics`,
`ExchangeJobOrderCreateService` (backend) · `JobOrderClientCreateCapService`,
`JobOrderExchangeProperties`, `JobOrderClientCreateRepository` (job orders) · `ExchangeGateFilter`,
`AreaSwitch`, `ExchangeRoutes#isJobOrder`, `ExchangeRegistryReader`,
`ExchangeRelay`, `ExchangeRelayResponses` (gateway) · `ExchangeSeam.GATE_STATUSES`,
`ExchangeSeam.PASSED_THROUGH_CODES`, `ExchangeSeam.REGISTRY_DOCUMENT_FIELDS` (shared seam) · V444 ·
**Related:** REQ-XCH-003, REQ-XCH-023, REQ-XCH-025, REQ-BXCH-006, REQ-AUDIT-001, REQ-NOTIF-008,
REQ-NOTIF-028, REQ-OXCH-004, REQ-OBS-025 · **Issues:** #3618, #3621, #3642, #3647

### REQ-OXCH-010 — Retention of job-order data

> [!note] Implemented for the creating member cleared at age-out (#3649), and for the client's
> 7-day retention, the onboarding criteria, the name withholding and the privacy records (#3654);
> decided in #3619 and #3620.

**The client keeps job-order data 7 days at most.** Job-order data of every kind, the member's own
orders included, is the retention class „job-order data" of `docs/exchange/resources/job-orders.md`.
The approval holds a client to the org-demand rule of `docs/exchange/client-security.md`, with no
exception for own data: it stores job-order data for at most 7 days, outside cloud-synced folders,
never in a backup or diagnostics, and never forwards it.

**Two approval criteria** in `docs/exchange/onboarding.md`, each named by the approval
(REQ-XCH-002) and each confirmed in the client application issue template
(`.github/ISSUE_TEMPLATE/exchange-client-application.yml`, „Job-order requirements"):

- **Both scopes:** the retention above.
- **`exchange.joborders.write`:** a client creates, cancels or changes an order only on an explicit
  action of the member, never automatically or by a rule, and the member chooses both units from
  `create-options`. The client may preselect; an overlay may offer the create on the member's
  explicit action when it shows both units, each preselected from the current `create-options`.

**Name withholding.** A member who withholds their name from connected applications (REQ-XCH-044)
is left out of `assignees[]` in every read (REQ-OXCH-002); the order and the member's own relation
stay. The profile setting and the privacy notice say that the name is then missing from the
assignees of job orders.

**Privacy records.** Job orders are the third exception for other members' data in A11 of
`docs/privacy/processing-activities.md`, beside the shared stock (ADR-0257) and the Kartellbank
(ADR-0354): the member's own creates, writes and assignee note rest on Art. 6(1)(b), other members'
and other units' order data on a client on Art. 6(1)(f), coordinating the order work, with the name
withholding as the objection; the Keycloak consent stays an OAuth authorisation, not a consent
under Art. 6(1)(a). The DPIA threshold assessment carries its re-check, and the privacy notice
„Verbundene Anwendungen" names the own writes, the other members' names a client receives, the
window, what the Basetool stores and that job-order changes are not undone there (REQ-OXCH-005).

**The creator leaves with the order.** When an order leaves the client set of REQ-OXCH-003 — 7 days
after `closed_at`, and an aUEC order not before its settlement is closed — the nightly window run
sets its creating member (`job_order.created_by_member_id`) and the create's key
(`create_idempotency_key`) to `null`. The creating client (`created_by_client_id`) stays: it is an
application id, not a person.

- An order inside the set keeps both, and so does one reopened between the run's selection and its
  write, since the clearing re-checks the closing instant.
- The clearing moves neither the order's `version` nor a section version, so it never collides with
  a web edit, and it moves no fingerprint.
- An order the web reopens after the clearing no longer counts against its creator's open-order cap
  (REQ-OXCH-009). A retry of the create under the cleared key creates a new order; within the hour
  the gateway's idempotency cache still answers it (REQ-XCH-020).
- An account deletion sets the creating member to `null` at any time (`ON DELETE SET NULL`), and
  the three-year name retention (`JobOrderNameRetention`) does not need to cover it.

**Acceptance**

- [x] The run clears the creating member and the key of an order outside the set and keeps the
  creating client; an order inside its 7 days keeps both, and an aUEC order with an open settlement
  keeps both until a run after its settlement closed (`JobOrderWindowRunIntegrationTest`).
- [x] An order the web reopens after the clearing stays without its creating member and key
  (`JobOrderWindowRunIntegrationTest`).
- [x] The creator's Art. 15 export lists a client's create while the order keeps its creating
  member and no longer once the run cleared it (`JobOrderWindowRunIntegrationTest`).
- [x] The client's 7-day retention and both approval criteria, the overlay create included, are in
  `onboarding.md` and the client application template; A11, the DPIA threshold assessment and the
  privacy notice name the job-order data.
- [x] The privacy notice renders the job-order read with its window, the stored creator, the
  missing undo, the withheld name and job orders in the legal basis, in DE and EN
  (`PrivacyControllerTest`); the profile setting says a withheld name is missing from the
  assignees (`JobOrderConnectedAppsTextsTest`).
- [x] A member who withholds their name is left out of the assignees (`ExchangeJobOrderSnapshotTest`).

**Enforced by:** `JobOrderCreatorRetention`, `JobOrderClientCreateRepository#clearCreators`,
`JobOrderExportSections` (job orders) · `JobOrderWindowRun`,
`ExchangeJobOrderFingerprintRepository#findOutsideWithCreator`, `ExchangeJobOrderShaper` (exchange)
· the approval (REQ-XCH-002) · V448, V449 · **Related:** REQ-OXCH-002, REQ-OXCH-003, REQ-OXCH-004,
REQ-OXCH-005, REQ-OXCH-009, REQ-SEC-058, REQ-SEC-060, REQ-XCH-020, REQ-XCH-044 · ADR-0407 ·
**Issues:** #3619, #3620, #3649, #3654
