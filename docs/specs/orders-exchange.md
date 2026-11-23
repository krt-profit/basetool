> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-23.
> **Owner area:** OXCH · **Related ADRs:** [ADR-0354](../adr/0354-bank-resources-on-the-exchange.md)
> (the bank switch this family follows),
> [ADR-0216](../adr/0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md),
> [ADR-0406](../adr/0406-the-job-order-feed-moves-on-a-fingerprint-of-the-carried-read.md) (the
> fingerprint the feed moves on)

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
The routes, the read, the feed, the writes and the caps follow with their own slices; until then the
family serves no route, and both of its switches are off.

The guiding rules:

- **Job orders can be shut off on the exchange alone**, and their writes separately from their
  reads: an admin stops a faulty release's writes while the reads go on, or stops the whole family,
  without stopping a client's other areas.
- **No job-order write is journaled or undone through the exchange.** The way back after a faulty
  release is the web (arc42 §6.8, „Faulty release on job orders").

## Requirements

### REQ-OXCH-003 — Snapshot and feed of the job-order set

> [!note] Implemented for the fingerprint, the dirty marks and the worker (#3643; decided in #3615,
> #3618, ADR-0406). The client set's snapshot, the per-unit and per-member logs, the tombstone and
> the window run follow with their slices.

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
- [ ] The snapshot, the per-unit and per-member logs, the tombstone `{ key, removedAt }` and the
  window run come with their slices.

**Enforced by:** `JobOrderExchangeReads`, `JobOrderExchangeReadProjection` (job orders) ·
`JobOrderFingerprints`, `JobOrderFingerprintRecomputation`, `JobOrderFingerprintTask`,
`ExchangeJobOrderMarkRepository`, `ExchangeJobOrderFingerprintRepository` (exchange) · V445 ·
**Related:** REQ-XCH-013, REQ-ORDERS-054, REQ-ORDERS-055, REQ-OXCH-009, REQ-OBS-025 · ADR-0224,
ADR-0406 · **Issues:** #3615, #3618, #3643

### REQ-OXCH-009 — The job-order switches

> [!note] Implemented for the two switches and their codes (#3642; decided in #3618, #3621). The
> open-order cap, the daily create cap and `limits` and `writesEnabled` in `create-options` follow
> with their slice.

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
- [ ] The open-order cap and the daily create cap, with `409 JOB_ORDER_OPEN_ORDER_CAP`,
  `409 JOB_ORDER_DAILY_CREATE_CAP` and `limits` and `writesEnabled` in `create-options`, come with
  their slice.

**Enforced by:** `ExchangeGate`, `JobOrderExchangeRoutes`, `ExchangeSwitch`,
`ExchangeRegistryService#updateJobOrdersSwitch`, `#updateJobOrderWritesSwitch`,
`ExchangeRegistrySnapshot`, `ExchangeJobOrderMetrics` (backend) · `ExchangeGateFilter`,
`ExchangeRoutes#isJobOrder`, `ExchangeRegistryReader`, `ExchangeRelay` (gateway) ·
`ExchangeSeam.GATE_STATUSES`, `ExchangeSeam.REGISTRY_DOCUMENT_FIELDS` (shared seam) · V444 ·
**Related:** REQ-XCH-003, REQ-XCH-023, REQ-XCH-025, REQ-BXCH-006, REQ-AUDIT-001, REQ-NOTIF-028,
REQ-OBS-025 · **Issues:** #3618, #3621, #3642
