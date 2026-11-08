# Refinery-order sync: traffic against the Exchange quota and budget

Doc type: research snapshot; an estimate for the map #3128, not a binding specification.

- Question: issue #3149 — how much traffic a client like VerseKit puts on the Exchange when it syncs
  refinery orders through the routes decided on the map, and whether a limit must be raised or
  documented.
- Baseline: `origin/main` at `cc47a186a`.
- Method: read-only reading of the spec, the client docs and the gateway and backend code. The
  traffic figures are **estimates** from stated assumptions; nothing was measured against a running
  gateway. Facts read from the source are marked with their file.

## The limits that apply

| Limit | Value | Source |
| --- | --- | --- |
| Requests per client and member | 120 / min (registry `requestsPerMinute` up to 1200) | REQ-XCH-023 in `docs/specs/external-exchange.md`; `ExchangeLimitProperties.memberPerMinute` (`ingest/src/main/java/.../ingest/config/ExchangeLimitProperties.java`) |
| Requests per client, all members | 1200 / min | same, `clientPerMinute` |
| Requests per source IP, all members and clients | 120 / min | REQ-INGEST-005; `app.rate-limit.ip-capacity` in `ingest/src/main/resources/application.yml` |
| Write requests per client and member | 500 per UTC day (registry `writesPerDay` up to 5000) | REQ-XCH-023; `ExchangeLimitProperties.writesPerDay`; key `ingest:xch:quota:<client>:<member>:<day>` |
| Ops counted per write | one per request; only the org-stock change set counts every further op | REQ-XCH-045; `ExchangeQuotas#countFurtherOps`, reached only through `everyOpCounts = true`, which `ExchangeController` passes for the org stock alone |
| Ops per change set | 500 (`413 BATCH_TOO_LARGE`) | REQ-XCH-023; `docs/exchange/sync-guide.md` *Batches* |
| Request body | 2 MiB (`413 PAYLOAD_TOO_LARGE`) | `app.ingest.max-payload-bytes: 2097152` in `application.yml`; sync guide *Batches* |
| Large change sets in flight | 4 sets of more than 100 ops, gateway-wide (`503 RELAY_BUSY`) | bulkhead `exchangeLargeChangeSets` in `application.yml`; REQ-XCH-023 |
| Redis byte budget | 4 MiB per client and member, 64 MiB per client, 128 MiB total | REQ-XCH-023; `ExchangeStoreProperties` (`memberBytes`, `clientBytes`, `totalBytes`) |
| Largest cached answer | 32 KiB, reserved before every write; a larger answer is not cached | REQ-XCH-020; `ExchangeStoreProperties.maxResultBytes` |
| Answer replay window | 1 h | REQ-XCH-020; `ExchangeStoreProperties.idempotencyTtl` |
| Per-entry budget overhead | 512 bytes | REQ-XCH-023 *The byte budget* |
| Live DPoP proofs per member | 600 (≈ 30 s each) | REQ-XCH-006 / REQ-XCH-023; `dpopProofsPerMember` |
| Feed page size | 500 by default, 1000 at most | `ExchangeFeedReader.DEFAULT_LIMIT` / `MAX_LIMIT` (`backend/src/main/java/.../backend/exchange/internal/ExchangeFeedReader.java`) |
| Sync cadence | on start, after a local change, timed **at most every 5 minutes**; back-off 5 s doubling to 5 min, at least `Retry-After` | sync guide *Back-off and sync cadence*; binding through `docs/exchange/client-security.md` *Sync behaviour* |

Two properties of these limits matter most here:

- **The daily quota is one counter per client and member across every write route** (the key holds
  no route). Refinery writes share the 500 with the same client's stock, ships, blueprints and draft
  writes.
- **Reads touch neither the quota nor the Redis budget.** The idempotency filter and the quota check
  return early for a route that does not write (`ExchangeIdempotencyFilter`, `!route.get().write()`;
  `ExchangeLimitFilter`, `route.get().write() && …`). Reads cost only the per-minute buckets and a
  DPoP proof.

## Assumptions of the estimate

Per refinery order, from the decisions of #3134 (one change set, partial updates, goods as row ops),
#3132 (pickup) and #3141 (store lines, receivers, job orders):

| Event | Ops | Note |
| --- | --- | --- |
| Create with 7 goods | 1 (goods inline) or 8 (create + 7 `good-add`) | both variants are carried; the contract decides |
| Timer / status updates | 3 | `startedAt`, `durationMinutes`, a correction |
| Goods correction | 1 | one `good-update` on average |
| Pickup | 1 | with up to 7 store lines inside the op |
| **Per order** | **6** (inline) or **13** (row ops) | |

- Each change is sent on its own as soon as it happens (no coalescing) — an upper bound; the sync
  guide recommends coalescing into one sync.
- Every write is preceded and followed by a feed read (pull before push, read after push), plus a
  timed poll every 5 minutes while the game runs.
- A pickup with store lines reads the receivers once per session and the job orders once per
  distinct material, up to 7.
- Typical member: 3–10 orders a day in a 4-hour session. Heavy member: 30 orders in a 10-hour
  session.
- Sizes (estimated from the stock schema in `docs/exchange/resources/stock.md` and the per-op result
  decided in #3134): an op ≈ 0.3 KiB, a create with 7 goods ≈ 1.8 KiB, an order in the feed with
  7 goods ≈ 2.5 KiB, a per-op result ≈ 0.1–0.4 KiB, a cached answer with its key ≈ 1 KiB.

## Writes per day

| Member | Orders | Write requests | Ops (inline / row ops) | Request bytes | Quota if counted per request | Quota if counted per op |
| --- | --- | --- | --- | --- | --- | --- |
| Typical, low | 3 | 18 | 18 / 39 | ≈ 8 KiB | 3.6 % | 3.6 % / 7.8 % |
| Typical, high | 10 | 60 | 60 / 130 | ≈ 25 KiB | 12 % | 12 % / 26 % |
| Heavy | 30 | 180 | 180 / 390 | ≈ 75 KiB | 36 % | 36 % / 78 % |

Every change set holds 1 to 8 ops: far below 500 ops, 2 MiB and the 100-op bulkhead threshold. A
500-op set of creates with 7 goods would still be ≈ 0.9 MiB.

**The quota is shared.** The sync guide's design load for stock and ships is a change set each per
5-minute cycle, 24 writes an hour (REQ-XCH-023 *Why these sizes*). A client that syncs all three
resources:

| Member | Session | Stock + ships, worst case | Refinery | Total | Of 500 |
| --- | --- | --- | --- | --- | --- |
| Typical | 4 h | 96 | 60 | 156 | 31 % |
| Heavy | 10 h | 240 | 180 | 420 | 84 % |
| Heavy, refinery counted per op (row ops) | 10 h | 240 | 390 | 630 | 126 % — over |

The worst case for stock and ships assumes a write in every cycle; a client that writes only on a
real change sends far fewer. Realistically a heavy member stays below half of the quota — but only
while a refinery change set counts **once per request**.

## Redis byte budget

Reads store nothing. Each write holds its cached answer for 1 h, plus a 512-byte entry overhead.

| Member, busiest hour | Writes in the 1-h window | Realistic (≈ 1.5 KiB each) | Worst case (32.5 KiB each) | Of 4 MiB (realistic / worst) |
| --- | --- | --- | --- | --- |
| Typical, refinery only | ≈ 15 | ≈ 23 KiB | ≈ 0.5 MiB | 0.6 % / 12 % |
| Heavy, refinery only | ≈ 18 | ≈ 27 KiB | ≈ 0.6 MiB | 0.7 % / 15 % |
| Heavy, refinery + stock + ships | ≈ 42 | ≈ 63 KiB | ≈ 1.4 MiB | 1.5 % / 34 % |

The client budget of 64 MiB holds about 1000 members at the realistic heavy figure in the same
hour, and about 46 at the worst case — the worst case needs every answer at the 32 KiB cap, which a
1-to-8-op refinery result does not approach. `ExchangeClientBudgetHigh` fires at 80 % of it
(REQ-XCH-028).

## Reads per day

| Member | Timed polls | Pull before / after writes | Receivers | Job orders by material | Total reads | Read bytes |
| --- | --- | --- | --- | --- | --- | --- |
| Typical (10 orders, 4 h) | 48 | 120 | 1 | ≤ 70 | ≈ 240 | ≈ 0.2 MiB |
| Heavy (30 orders, 10 h) | 120 | 360 | 1–2 | ≤ 210 | ≈ 690 | ≈ 0.6 MiB |

- The snapshot holds only `OPEN` and `IN_PROGRESS` orders (#3135): a handful, one page of the
  default 500. Each feed page announces a changed order once, so an order costs about one feed entry
  per change.
- **Per minute:** the busiest burst is a pickup — pull, receivers, up to 7 job-order lookups, the
  write, pull — about 11 requests, 9 % of the 120 per member and minute and of the 120 per IP.
- **DPoP:** 11 proofs live at once against 600.
- The job-order lookup by material is the largest read item. A lookup per distinct material, like the
  org stock's `GET /exchange/v1/org-stock/targets?unit=&material=`, keeps it within the limits;
  a client caching the answer for the session cuts it further.

## Comparison with the stock resource

| | Stock (design load of REQ-XCH-023) | Refinery, heavy member |
| --- | --- | --- |
| Writes / hour, worst case | 12 (plus 12 for ships) | ≈ 18 in the busiest hour, 18 on average over 10 h |
| Ops per change set | up to 500 | 1–8 |
| Cached answers per member in the 1-h window | ≈ 24 | ≈ 18 |
| Quota counted | per request | per request (recommended) |

A heavy refinery member writes at about the rate the stock sync was sized for, with much smaller
change sets.

## Conclusions

1. **No default limit has to be raised.** A typical member uses about 12 % of the daily write
   quota, under 1 % of the member's Redis budget and under 10 % of any per-minute limit; a heavy
   member about 36 % of the quota with refinery alone.
2. **Decide and document the quota counting of the refinery change set: once per request**, as
   stock, ships and blueprints count, not per op as the org stock does. Per-op counting with goods as
   row ops would put a heavy member at 78 % from refinery alone and over the quota together with a
   stock and ships sync. The sync guide's *Writes* row and REQ-XCH-023 name the org stock as the only
   per-op route; the refinery contract states that it is not one.
3. **Document that the quota is shared** between all resources of a client: a client syncing
   refinery, stock and ships for a heavy member approaches the 500 in its worst case. Coalesce timer
   updates into the 5-minute cycle (already the sync guide's recommendation). If VerseKit enables all
   three resources, the registry override `writesPerDay` (up to 5000) is the lever, not the default.
4. **Document in the refinery resource page** that a pickup's job-order lookups are one per
   material and may be cached for the session, and that an initial backfill of history, if a client
   ever sends one, stays at 100 ops per change set to avoid `RELAY_BUSY`.
5. **Add the refinery resource to REQ-XCH-023 *Why these sizes*** with the figures above when the
   contract is written; the monitoring (per-client write and budget metrics, REQ-XCH-028) needs no
   new limit.
