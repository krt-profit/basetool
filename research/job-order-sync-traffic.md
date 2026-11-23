# Job-order sync: traffic on the exchange budgets

Doc type: research snapshot for the wayfinder ticket #3634. **Facts only, nothing decided.**

- **Question:** how much load a client's job-order sync puts on the budgets of REQ-XCH-023 (the
  per-minute buckets, the daily write quota, the Redis byte budget), and whether VerseKit's
  `writesPerDay` would have to grow.
- **Basetool:** read at `main` `d1b3165`; the branch is cut from `origin/main` `6166a9e`, which
  differs from `d1b3165` only in `.github/dependabot.yml`, `docs/deployment.md` and
  `docs/specs/deployment-delivery.md`. Line numbers below hold for both.
- **VerseKit:** `main` at `8a22d5839d2d6924aca9727cb65d1d168b292b67` (VerseKit 5.5.0,
  2026-11-21), files under `crates/versekit-core/src/basetool/`. The same commit the earlier
  research `research/versekit-job-orders.md` (commit `14f51e3`) read.
- **Production:** read-only aggregates from the production Loki (counts per route and per day, no
  log line, no member id printed), 2026-10-25 to 2026-11-23 (Loki keeps 744 h,
  `monitoring/loki/loki-config.yml` L50; a query may span 30 d 1 h). No database, no Prometheus, no
  Redis was read.
- **Decided inputs:** the read set of #3610, the feed of #3615, the writes of #3614, the
  create options of #3611, the notices of #3617 (no traffic of its own) and VerseKit's sync of
  #3609.

## 1. The budgets

| Budget | Value | Source |
| --- | --- | --- |
| Requests per client and member | 120 / min; registry `requestsPerMinute` up to 1200 | `docs/specs/external-exchange.md` L1737, L238-239; `ingest/.../config/ExchangeLimitProperties.java` L43; `ExchangeLimitFilter.java` L128-129 |
| Requests per client, all members | 1200 / min | `external-exchange.md` L1738; `ExchangeLimitProperties.java` L44; `ExchangeLimitFilter.java` L141 |
| Requests per source address | 120 / min, before the token is read, shared by everyone behind the address | `external-exchange.md` L1778-1783; `ingest/src/main/resources/application.yml` L78-79 |
| Writes per client and member | 500 per UTC day; registry `writesPerDay` up to 5000 | `external-exchange.md` L1740, L239; `ExchangeLimitProperties.java` L45; `ExchangeLimitFilter.java` L168, L189-193 |
| VerseKit's write quota | 2000 per member and day | `docs/legal/approved-clients.md` L28 |
| What a write costs | one unit per request on every write route but the org stock's change set; every attempt counts, except a `503 EXCHANGE_BUDGET_EXHAUSTED` | `external-exchange.md` L1745-1756; `ingest/.../contract/ExchangeRoutes.java` L87-127, L171-184 |
| Job-order writes | four single routes, one unit each (#3614) | #3614 resolution |
| Redis byte budget | 4 MiB per client and member, 64 MiB per client, 128 MiB total | `external-exchange.md` L1723-1730; `ingest/.../config/ExchangeStoreProperties.java` L46-48 |
| Charge per stored value | its bytes plus 512 bytes | `external-exchange.md` L1799-1800; `ingest/.../store/ExchangeBudget.java` L75 |
| Largest cached answer, reserved before every write | 32 KiB (32 768 B) | `ExchangeStoreProperties.java` L49; `external-exchange.md` L1813-1817 |
| Replay window of a cached answer | 1 h | `ExchangeStoreProperties.java` L50; `external-exchange.md` L1463-1465 |
| Lock of a write in flight | 2 min | `ExchangeStoreProperties.java` L51; `external-exchange.md` L1483-1484 |
| Feed page size | 500 by default, 1000 at most | `backend/.../exchange/internal/ExchangeFeedReader.java` L51, L54 |
| Live DPoP proofs per member | 600 | `ExchangeLimitProperties.java` L48; `docs/exchange/sync-guide.md` L247 |
| Client cadence | on start, after a local change, otherwise at most every 5 min; back-off 5 s doubling to 5 min, at least `Retry-After` | `docs/exchange/sync-guide.md` L278-292 |

Two properties carry the estimate:

- **A read touches neither the quota nor Redis.** The idempotency filter passes every route that
  does not write straight on (`ingest/.../idempotency/ExchangeIdempotencyFilter.java` L146), and
  the limit filter counts the quota only for a write route (`ExchangeLimitFilter.java` L168). A read
  costs one token of the per-minute buckets and one DPoP proof.
- **The quota is one counter per client and member over every write route**
  (`ingest:xch:quota:<client>:<member>:<day>`, `ingest/.../store/ExchangeQuotas.java` L123-130).
  Job-order writes share it with the client's stock, ship, blueprint, refinery, org-stock and bank
  writes.

## 2. What the decided design puts on the wire

- **One read route** under `/exchange/v1/job-orders` (#3614): a snapshot of the set, then the feed
  from one cursor for all three kinds (#3615). The set is the member's orders with a current own
  relation (#3610): active orders, terminal orders for 7 days after closing plus one nightly run,
  aUEC orders while `settlementOpen` (#3615).
- **A feed entry** per changed fingerprint of the carried fields, recomputed by a worker, plus
  announcements on a unit's name or shorthand, a Bearbeiter's display name or name withholding, a
  membership start or end, a unit's (de)activation, and the member's own Bearbeiter note; "gone"
  entries `{ key, removedAt }` for a lost relation, a delete and the end of the window; a client's
  own write returns as an ordinary item (#3615).
- **One more read**, `GET /exchange/v1/job-orders/create-options`, under the create capability
  (#3611, #3614).
- **Four writes**, each one request and one quota unit: create an aUEC order, cancel, target,
  comment, each with a mandatory `Idempotency-Key` (#3614). The create's key is stored **on the
  order in PostgreSQL**, not in Redis (#3614).
- **Nothing else in Redis for this family:** no mass-change staging, journal or undo (#3616, as
  recorded on #3618), no drafts.

## 3. The client: VerseKit at `8a22d58`

- **Cadence** (`schedule.rs` L21-25, L29-32): a pass at the start, 20 s after an own change
  (`AFTER_CHANGE = 20.0`), otherwise at most every 300 s (`CADENCE = 300.0`); back-off 5 s doubling
  to 5 min, never shorter than `Retry-After`; a stop code halts until a button or the next start.
- **A pass** (`sync.rs` L372-509) reads the service document (L381), then runs every switched-on
  area in turn (L440-486). An isolated area told `Retry-After` waits alone (L446-452).
- **Paging** (`feed.rs` L32, L77-102): `limit=500`, read until `hasMore` is false;
  `CURSOR_EXPIRED` falls back to the snapshot (L106-114).
- **The pattern for other members' data** is the bank area's (`bank.rs` L22-25, L46-47): in
  memory only, the cursor too, at most seven days; **after a start the snapshot is read anew**. A
  pass reads the accounts whole and the own requests from the cursor (L519-527).
- **The unit list** is fetched anew with every sync (`orgunits.rs` L22).
- **No job-order area exists** (#3609). Its shape is VerseKit's choice (#3620); the estimate
  assumes the bank pattern (assumption A1).
- `pacing.rs` L18-20 says the Basetool keeps a change set's answer 24 h, and the Javadoc of
  `ingest/.../store/ExchangeIdempotency.java` L43-48 says "kept for a day"; the configured window
  is 1 h (`ExchangeStoreProperties.java` L50, REQ-XCH-020 at `external-exchange.md` L1463). The
  Redis figures below hold for either (section 7.5).

## 4. What the gateway stores in Redis per job-order request

Computed from the code; `versekit` (8 characters) as the client id and a 36-character subject as
the member are assumptions about the key lengths.

| Entry | When | Bytes | Charge with 512 B | Lifetime | Source |
| --- | --- | --- | --- | --- | --- |
| a read | never | 0 | 0 | — | `ExchangeIdempotencyFilter.java` L146 |
| the claim on the key | while the write runs | 21 + 110 + 22 = 153 | inside the reservation | ≤ 2 min | `ExchangeIdempotency.java` L59, L86-89, L161-169, L206-208 |
| the reservation for the answer | while the write runs | 153 + 32 768 = 32 921 | 33 433 (32.6 KiB) | until the answer is cached or freed | `ExchangeIdempotencyFilter.java` L222-231, L249-259 |
| the cached create answer `201 { id, status, versions, target }` | after the write | ≈ 284 JSON + 126 key = 410 | ≈ 922 (0.9 KiB) | 1 h | `ExchangeIdempotency.java` L217-221, L245-252; `ExchangeIdempotencyFilter.java` L281-307 |
| a cached refusal (`409`, `400`, `404`, problem+json) | after the write | ≈ 419 JSON + 126 key = 545 | ≈ 1 057 (1.0 KiB) | 1 h | same; problem bodies 228-294 B in `docs/exchange/examples/v1/problem/valid/` |
| the day's quota counter | first write of the day | 73 + 20 = 93 | ≈ 605 | to the end of the next UTC day | `ExchangeQuotas.java` L67-68, L123-130; shared with every family, so a job-order write adds none when the client already wrote that day |

The cached JSON is `{"fingerprint":…,"status":…,"contentType":…,"body":…}` with the 64-character
SHA-256 fingerprint and the escaped body (`ExchangeIdempotency.java` L245-252); its fixed part is
138 bytes. The key is `ingest:xch:idem:` plus `<client>:<member>:<sha256>` (L56, L86-89).

## 5. Production observations (read-only, Loki)

### 5.1 Job-order writes in the web, 2026-10-25 to 2026-11-23

Backend access lines `<METHOD> <path> -> <status> in <n> ms`
(`backend/.../platform/internal/RequestLoggingFilter.java` L36-37, L68-70) on the routes of
`backend/.../joborder/web/` (`JobOrderController.java` L91, L108, L254, L284; `JobOrderMoneyController.java`
L90, L284, L300), grouped with the order ids cut out:

| Route | 2xx | 4xx |
| --- | --- | --- |
| `POST /api/v1/orders` (MATERIAL create) | 0 | 0 |
| `POST /api/v1/orders/items` (ITEM create) | 2 | 0 |
| `POST /api/v1/orders/auec` (aUEC create) | 0 | 0 |
| `PUT …/{id}/status` | 4 | 0 |
| `PUT …/{id}/priority` | 2 | 0 |
| `PUT …/{id}/items`, `…/items/requested` | 2, 4 | 0 |
| `POST …/{id}/items/{item}/production` | 25 | 10 |
| `POST …/{id}/item-handovers` | 6 | 0 |
| `POST`/`DELETE …/assignees/{user}`, `PUT …/note` | 1, 1, 1 | 0 |
| `PUT`/`DELETE …/participations/{unit}` | 2, 1 | 0 |
| `PATCH …/blueprint-variant-counting` | 4 | 0 |
| `DELETE …/allocations/{lot}` | 1 | 0 |
| every money route (target, information, movements, settlement, corrections, provisions) | 0 | 0 |
| **all job-order writes** | **56** | **10** |

- aUEC orders shipped on 2026-11-13 (v1.24.0, `CHANGELOG.md` L647 ff.), ten days of the window.
- Per 24-hour window, lines naming a job-order write (the 66 access lines plus 10 other lines): at
  most 24 (window ending 2026-11-21); 21 of the 30 windows had none. The frontend's `/orders/`
  write lines fall on the same days.
- Stock earmarks (`POST`/`PATCH`/`DELETE /api/v1/inventory/{id}/allocation`,
  `InventoryItemController.java` L946, L982, L1015, shared by job-order and mission earmarks): 31
  in 2xx (24, 5, 2), 6 in 4xx.
- Org-unit writes (sources of announcements): 52 membership edits
  (`PATCH /api/v1/org-units/members/{id}/memberships`), 1 parent change (a hierarchy change), 1
  unit update, 1 unit and 1 Bereich created, 12 rank changes. Memberships that the identity sync
  writes leave no access line and are not counted.
- **No job-order metric counts creations or status changes.** The module's gauges are the open and
  in-progress counts, the oldest open order's age, the invalid-responsibility count, the integrity
  violations and the linked-money gauges (`backend/.../joborder/internal/JobOrderBusinessGauges.java`
  L48-71; `joborder/api/JobOrderMetricNames.java` L30-65).

### 5.2 VerseKit on the exchange today

Gateway access lines carry the route template and the client (`ingest/.../observability/ExchangeLogContext.java`
L38-62).

- **Seven days to 2026-11-23:** 58 165 VerseKit requests, 5 865 of them the service document
  (one per pass), so **9.9 requests per pass**. One request per area and pass: `/me/blueprints`
  5 932, `/me/ships` 5 904, `/me/stock` 5 870, `/me/org-units` 5 858, `/org-stock` 5 854,
  `/me/refinery-orders` 4 614, `/bank/accounts` 4 610, `/bank/requests` 4 610, `/org-stock/members`
  2 996, `/bank/queue` 2 964, `/me/org-demand` 2 146, `/catalog/locations` 586, writes 160.
- **Members:** 9 distinct in the seven days, 4 to 8 per day.
- **Passes per member in the seven days:** 4, 4, 26, 122, 137, 352, 760, 1 508, 2 952 — the median
  member about 20 a day, the busiest 422 a day on average; the busiest single 24-hour window was
  578 passes, about twice the 288 of one installation open all day.
- **Busiest member-minute in the last 24 h:** 52 requests; 99th percentile of the 471 minutes with
  traffic: 26.
- **Writes in 29 days:** 5 474 attempts — ships 3 418, stock 1 892 (8 × `409`, 28 × `503`),
  blueprints 152, bank 8, refinery 4. Busiest member-day: **862** (window ending 2026-11-07), 838
  and 598 in the same week; since 2026-11-09 at most 48.

## 6. Assumptions

| # | Assumption | Typical | Heavy | Stress |
| --- | --- | --- | --- | --- |
| A1 | The job-order area follows VerseKit's bank pattern: isolated, in memory, a snapshot at each start, then one feed read per pass from the in-memory cursor | | | |
| A2 | `create-options` is read when the member opens the create picker (0-2 a day); variant: on every pass, to decide whether to show create | | | |
| A3 | Passes per member and day | 55 (4 h open: 48 timed, 1 start, ~6 after own changes) | 578 (observed busiest window, two installations) | 8 640 (two installations, 24 h, an own change before every pass: a pass every 20 s each) |
| A4 | Set size S (orders in the member's set) | 25 | 300 | 1 500 |
| A5 | Item size in a page | 1.5 KiB | 1.5 KiB | 6 KiB (20 lines, 5 Bearbeiter, a 1000-character comment) |
| A6 | Fingerprint changes per day touching the member's set (upper bound: all of the Basetool's) | 3 (observed: 87 job-order and earmark writes in 29 days) | 30 (ten times) | 300 (hundred times) |
| A7 | Client job-order writes per member and day | 2 | 20 | the whole quota |
| A8 | Of these in the busiest hour | 2 | 10 (20 in the worst case) | 120 a minute until the quota is spent |
| A9 | An empty feed page | 0.2 KiB | 0.2 KiB | 0.2 KiB |

A4 and A5 are the weakest: the set size per member needs the database (section 8). The item size
is estimated from the field list of #3612 against the demand-details example
(`docs/exchange/examples/v1/org-demand/valid/with-orders.json`, 1 340 B compact for two orders with
one line each).

## 7. The figures

### 7.1 Feed entries

| | Typical | Heavy | Stress |
| --- | --- | --- | --- |
| Entries a day from changes (A6) plus the member's own client writes (A7) | ≤ 5 | ≤ 50 | ≤ 300 + quota |
| Entries per 5-minute pass | < 1 | < 1 | ≈ 1 |
| Feed pages per pass | 1 | 1 | 1 |
| A mass announcement (unit rename, a hierarchy change flipping `responsibilityInvalid`, name retention): up to S entries once | 1 page | 1 page | 3 pages |

- A feed read costs one request until more than 500 entries have gathered since the last pass;
  below that the number of entries changes only the bytes, not the requests.
- "Gone" entries from the nightly window run follow the closings 7 days later; the window saw 4
  status changes in 29 days.
- Server side, a changed order writes one log row per related unit (requesting, responsible, each
  actively supporting) and one per Bearbeiter (#3615), about 3 to 5 rows: about 10 to 15 rows a day
  at the observed rate. A membership start or end writes one per-member row per order of that unit
  in the set (52 web edits in 29 days).

### 7.2 Read requests of the job-order family

| | Typical | Heavy | Stress |
| --- | --- | --- | --- |
| Requests a day (feed or snapshot, one per pass) | 55 | 578 | 8 640 |
| With `create-options` on every pass (A2 variant) | 110 | 1 156 | 17 280 |
| Snapshot pages at a start, `ceil(S / 500)` | 1 | 1 | 3 |
| Steady-state requests a minute (timed passes) | 0.2 | 0.4 | 6 |
| Burst a minute (passes 20 s apart) | 3 | 6 | 6 (+2 at a start) |
| Read bytes a day (A5, A9: pages plus one snapshot per start) | ≈ 50 KiB | ≈ 1 MiB | ≈ 1.7 MiB plus 9 MiB per start |

The observed median VerseKit member (about 20 passes a day) would add about 20 job-order reads a
day.

### 7.3 Per-minute buckets

- **Per client and member (120):** the job-order family adds one request per pass, two with the
  A2 variant: 9.9 → 10.9 (11.9) requests per pass, +10 % (+20 %). The busiest member-minute
  observed (52) holds about five passes; with job orders it becomes about 57 (62), 48 % (52 %) of
  120. A snapshot of S = 1 500 adds two more pages once.
- **Per client (1200):** members whose clients pass every 5 minutes at the same time before the
  bucket binds: 6 000 / 9.9 ≈ 606 today, 6 000 / 10.9 ≈ 550 with the job-order feed, 6 000 / 11.9 ≈
  504 with the A2 variant. VerseKit had 4 to 8 members a day in the observed week.
- **Per source address (120):** unchanged in kind; every member behind one address shares it, now
  with one more request per pass each.
- **DPoP proofs (600 per member):** a pass keeps about 11 to 12 proofs live for 30 s.
- A job-order write takes one token of the member's bucket as well; at A7 that is at most 20 a day.

### 7.4 Daily write quota

| | Job-order writes a day | Of 500 | Of 2000 (VerseKit) |
| --- | --- | --- | --- |
| Typical | 2 | 0.4 % | 0.1 % |
| Heavy | 20 | 4 % | 1 % |
| 100 a day | 100 | 20 % | 5 % |
| Stress: a looping client | until refused | 100 % | 100 % |

- **Shared with the other families:** VerseKit's busiest observed member-day (862 writes, mostly
  ship and stock change sets) plus a heavy member's 20 is 882 of 2000 (44 %). The busiest member-day
  since 2026-11-09 (48) plus 20 is 68: 3 % of 2000, 14 % of 500. REQ-XCH-023's heavy worst case for
  stock, ships and refinery under the default (about 420 of 500, `external-exchange.md` L1846-1848)
  plus 20 is 440 of 500 (88 %).
- **For scale:** every job-order write in the web over 29 days was 66 attempts, fewer than the
  20-a-day heavy member makes in four days.
- Every attempt counts, a retry under the same key included (`external-exchange.md` L1752-1756);
  the back-off of `schedule.rs` keeps retries to a handful.

### 7.5 Redis byte budget

Reads store nothing. A job-order write holds a 32.6 KiB reservation while it runs, then a cached
answer of about 0.9 KiB (create) to 1.0 KiB (refusal) for 1 h (section 4).

| | Busiest hour | Of 4 MiB per member |
| --- | --- | --- |
| Typical: 2 answers | ≈ 2 KiB | 0.05 % |
| Heavy: 10 answers | ≈ 10 KiB | 0.3 % |
| Heavy: 20 answers plus one reservation in flight | ≈ 21 KiB + 32.6 KiB ≈ 54 KiB | 1.3 % |
| Stress: VerseKit's 2000 writes within one hour (120 a minute spends them in about 17 min) | ≈ 2.0 MiB + 32.6 KiB | 51 % |
| Stress under the default 500 | ≈ 0.5 MiB | 13 % |

- **Per client (64 MiB):** about 3 100 heavy members (21 KiB each) or about 31 000 typical members
  (2 KiB) in the same hour; the total budget (128 MiB) holds twice that.
- **The worst case is bounded by the quota, not by the 32 KiB answer cap:** a job-order answer is
  terse, so even the whole day's quota spent in one hour stays below the member's 4 MiB. With a 24 h
  window, as the two stale comments of section 3 say, the figures are the same, because the quota
  ends the day's writes.
- Beside the existing families: REQ-XCH-023 sizes a member at 24 cached answers, at most about
  0.8 MiB (`external-exchange.md` L1830-1835), and a heavy refinery member at most about 0.6 MiB
  (L1842-1843); a heavy job-order member adds about 0.02 MiB to either.
- The open-order cap and the create-and-cancel bound are the limits ticket's (#3618) and not yet
  numbers; they can only lower the stress rows.

## 8. Gaps and the reads that close them

Production figures that need the database or Prometheus were not read. Each `SELECT` runs in a
session opened with `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` and prints aggregates
only. Table and column names are from the entities (`JobOrder.java` L60-146 with `AbstractEntity`'s
`created_at`/`updated_at`, `JobOrderParticipation.java` L42-60, `JobOrderAssignee.java` L55-83,
`OrgUnitMembership.java` L55-125, `AuditEvent.java` L49-126).

**G1 — orders per kind and status** (the size of the set's pool):

```sql
SELECT type, status, count(*) AS orders
FROM job_order
GROUP BY type, status
ORDER BY type, status;
```

**G2 — creations and lifecycle changes per day and kind over 90 days** (the audit outlives Loki's
30 days; assumes `subject_id` names the order on job-order events):

```sql
SELECT a.occurred_at::date AS day, a.event_type, j.type, count(*) AS events
FROM audit_event a
LEFT JOIN job_order j ON j.id = a.subject_id
WHERE a.domain = 'JOB_ORDER'
  AND a.occurred_at > now() - interval '90 days'
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;
```

**G3 — set size per member** (A4). The closing instant does not exist yet, so terminal orders count
by `updated_at` (an upper bound, since it moves on every write); `settlementOpen` and the web gates
(`KRT Member`, active units) are left out, which also makes it an upper bound:

```sql
WITH s AS (
  SELECT id, requesting_org_unit_id, responsible_org_unit_id
  FROM job_order
  WHERE status IN ('OPEN', 'IN_PROGRESS')
     OR (status IN ('COMPLETED', 'CANCELLED', 'REJECTED')
         AND updated_at > now() - interval '7 days')
), rel AS (
  SELECT m.user_id, s.id
  FROM s
  JOIN org_unit_membership m
    ON m.org_unit_id IN (s.requesting_org_unit_id, s.responsible_org_unit_id)
  UNION
  SELECT m.user_id, s.id
  FROM s
  JOIN job_order_participation p ON p.job_order_id = s.id AND p.active
  JOIN org_unit_membership m ON m.org_unit_id = p.claiming_org_unit_id
  UNION
  SELECT a.user_id, s.id
  FROM s
  JOIN job_order_assignees a ON a.job_order_id = s.id
), per_member AS (
  SELECT user_id, count(*) AS n FROM rel GROUP BY user_id
)
SELECT count(*) AS members,
       percentile_disc(0.5) WITHIN GROUP (ORDER BY n) AS p50,
       percentile_disc(0.9) WITHIN GROUP (ORDER BY n) AS p90,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n) AS p99,
       max(n) AS max
FROM per_member;
```

**G4 — orders per unit** (the size of a unit rename's announcement), same `s` as G3:

```sql
WITH s AS (
  SELECT id, requesting_org_unit_id, responsible_org_unit_id
  FROM job_order
  WHERE status IN ('OPEN', 'IN_PROGRESS')
     OR (status IN ('COMPLETED', 'CANCELLED', 'REJECTED')
         AND updated_at > now() - interval '7 days')
), per_unit AS (
  SELECT u, count(*) AS n
  FROM (
    SELECT requesting_org_unit_id AS u, id FROM s
    UNION
    SELECT responsible_org_unit_id, id FROM s
    UNION
    SELECT p.claiming_org_unit_id, p.job_order_id
    FROM job_order_participation p
    JOIN s ON s.id = p.job_order_id AND p.active
  ) x
  GROUP BY u
)
SELECT count(*) AS units,
       percentile_disc(0.9) WITHIN GROUP (ORDER BY n) AS p90,
       max(n) AS max
FROM per_unit;
```

**G5 — membership starts per day** (ends leave no row, so they stay uncounted):

```sql
SELECT created_at::date AS day, count(*) AS memberships
FROM org_unit_membership
WHERE created_at > now() - interval '30 days'
GROUP BY 1
ORDER BY 1;
```

**G6 — Redis use today** is a Prometheus read, not a `SELECT`:
`basetool_ingest_exchange_client_budget_used_ratio{client_id="versekit"}` and
`basetool_ingest_exchange_budget_used_ratio` (`external-exchange.md` L1823-1828), through the
read-only Grafana service account of `monitoring/README.md`.

**G7 — production overrides** of `app.exchange.store.*` and `app.exchange.limits.*` would sit in the
host's rendered environment, which holds credentials and was not read. The repository sets none
outside the sandbox load compose file (`docker-compose.sandbox-load.yml` L27-30).

**G8 — how many members would switch a job-order area on** is VerseKit's and its members' choice
and unknown.

## 9. Where this deviates from the refinery estimate

The method is that of `docs/research/refinery-exchange-traffic.md` (branch
`research/refinery-exchange-traffic`, commit `0a10412`), the basis of REQ-XCH-023's refinery
paragraph (`external-exchange.md` L1837-1851). Deviations:

- **Reads follow passes, not writes.** That estimate counted a pull before and after each write
  plus a timed poll. VerseKit at `8a22d58` reads every switched-on area on every pass
  (`sync.rs` L440-486), so the job-order reads are one per pass whatever the member writes; the
  heavy member's passes are taken from production, not from a session model.
- **Volumes are measured, not assumed,** where Loki has them (section 5); the set size stays an
  assumption (G3).
- **A job-order answer is terse** (#3614), about 1 KiB cached, so the Redis worst case is bounded
  by the quota, not by the 32 KiB answer cap.
- **The snapshot holds closed orders too** (7 days, `settlementOpen`), unlike the refinery's
  active-only snapshot, and an in-memory client takes it at every start (A1).

## 10. What the figures say

- **Per-minute buckets.** The job-order family adds one read per pass (two with `create-options`
  on every pass), about +10 % (+20 %) of VerseKit's 9.9 requests per pass. The busiest observed
  member-minute goes from 52 to about 57 (62) of 120. The per-client bucket's ceiling for members
  passing at the same time falls from about 606 to about 550 (504); VerseKit had 4 to 8 members a
  day. Feed volume does not move these figures until more than 500 entries gather between two
  passes; at the observed rate of about 3 changes a day across the Basetool, and even at a hundred
  times that, a pass reads one page. **The buckets hold** in every row of section 7.3.
- **The 500 a day.** Job-order writes alone: 2 (typical) to 20 (heavy) a day, 0.4 % to 4 % of 500.
  Together with REQ-XCH-023's heavy worst case for stock, ships and refinery: about 440 of 500. Only
  a client that loops reaches the quota, and the quota then stops it. **The default holds** for
  job orders on their own and with the refinery worst case of REQ-XCH-023.
- **The Redis budget.** Reads store nothing; a job-order write leaves about 1 KiB for 1 h. A heavy
  member's busiest hour is about 54 KiB of 4 MiB with a write in flight, and VerseKit's whole
  2000-write quota spent in one hour is about 2 MiB, 51 %. **The member, client and total budgets
  hold**, whichever of the 1 h and the 24 h windows applies.
- **VerseKit's `writesPerDay`.** At 2000, a heavy member's job-order writes take 1 %. The busiest
  observed VerseKit member-day (862) plus 20 is 882, 44 %; since 2026-11-09 the busiest member-day
  plus 20 is 68, 3 %. By this arithmetic the job-order family does not need the 2000 to grow; what
  fills it is the ship and stock change sets of the other areas. The limits ticket's open-order cap
  (#3618) can lower the stress rows further.
- **What the figures rest on:** the set size per member (A4, gap G3) and the client's area shape
  (A1, A2, #3620). The production write rates are a quiet month (aUEC orders ten days old); a ten-
  or hundredfold rise changes the bytes, not the request counts or the conclusions above.
