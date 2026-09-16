# ADR-0175 — JVM garbage collectors are set explicitly, never left to container ergonomics

- **Status:** Proposed
- **Date:** 2026-09-13
- **Deciders:** @greluc (pending)
- **Related:** specs `REQ-OPS-028` (new) · [`deployment-delivery.md`](../specs/deployment-delivery.md) ·
  [ADR-0085](0085-scale-user-sync-and-stack-capacity-for-5000-accounts.md) (the limit scheme this corrects a blind spot in) ·
  [ADR-0174](0174-the-authorities-cache-ttl-is-an-operational-knob.md) (the other half of the same
  2026-09-13 production investigation) · `docker-compose.yml` (the `JVM CONTAINER SIZING` block)

## Context

The `frontend`, `backend` and `ingest` containers each pass
`-XX:+UseContainerSupport -XX:MaxRAMPercentage=… -XX:InitialRAMPercentage=…` and **no collector
flag**. Which garbage collector each JVM runs was therefore decided by HotSpot's ergonomics.

HotSpot selects G1 only on a *server-class machine*: **at least 2 CPUs and at least 1792 MB of
memory**. Under `-XX:+UseContainerSupport` the memory figure is the **cgroup limit**, not the host's
16 GB. Below either bound it falls back silently to SerialGC — no log line, no warning, nothing in
any metric that names the collector.

|  Service   | Limit | >= 1792 MB |                Collector actually running                 |
|------------|-------|------------|-----------------------------------------------------------|
| `frontend` | 1280M | no         | **Serial**                                                |
| `ingest`   | 512M  | no         | **Serial**                                                |
| `backend`  | 2048M | yes        | G1 — by accident of the limit                             |
| `keycloak` | 2560M | yes        | G1 — and the only one that says so, from its vendor image |

Read on production on 2026-09-13, the frontend's consequences were concrete. Its young generation
was a 179 MB Eden behind a **22.3 MB survivor space**, so anything surviving a young collection in
quantity was promoted straight into Tenured, which only a single-threaded full compaction reclaims.
Tenured stood at **298 MB of a 447 MB ceiling** and had climbed since container start. All **four**
full GCs in 12.9 hours were triggered by `CodeCache GC Threshold` or `Metadata GC Threshold` —
**not one by heap pressure**. The 730 minor GCs averaged **13.4 ms** each, single-threaded, on the
tier that server-renders every page.

That monotonic climb is what a resident-memory graph shows, and it reads exactly like a leak. It is
not one: cgroup `anon` was 63 % of the limit and `memory.events` recorded zero pressure events of
any kind. The cost is latency, not stability — and `jvm_gc_overhead` at `6.8e-4` is why no alert
ever noticed, because overhead measures the *fraction of time* spent collecting and cannot see that
the pauses are serialised and avoidable.

The deeper problem is that the `JVM CONTAINER SIZING` block in `docker-compose.yml` is a careful,
**measured** derivation — and every figure in it was taken with the collector as an unnamed free
variable: Serial for the frontend and ingest, G1 for the backend. Heap layout and native overhead
differ between the two, so the table compares numbers that were never comparable. The frontend's
limit history is `768M -> 1024M -> 1280M`; all three are below 1792, so it has never once run G1
under the limit scheme, and the 2026-07-25 re-budget that fixed a real working-set problem left this
untouched because nobody was looking for it.

## Decision

**Every JVM container names its collector in `JAVA_TOOL_OPTIONS`.** The flag is mandatory, not
advisory, and a service that omits it is a defect regardless of whether ergonomics currently happens
to pick the intended collector.

- `frontend`: `-XX:+UseG1GC`, and the limit moves **1280M -> 1792M** so ergonomics and intent agree
  rather than contradict each other. At 1792M × 50 % the ceiling is 896 MB, comfortably above the
  529 MB this JVM commits; 896 + ~420 MB overhead is 73 % of the limit, inside ADR-0085's <= 80 %
  sizing rule with room for G1's larger native structures.
- `ingest`: `-XX:+UseSerialGC`. It keeps the collector it already had, but as a **choice**. For a
  relay that served 7 requests in 13 hours and is idle between bursts, Serial is right on the
  merits — lower native overhead, no concurrent GC threads to schedule — and its 512M limit stays.
- `backend`: unchanged at 2048M and G1 by ergonomics today; the flag is still added, so a future
  limit reduction cannot silently switch it.

The sizing rule gains a clause: **a limit change that crosses 1792 MB changes the collector**, and
must be made deliberately rather than discovered afterwards.

## Alternatives rejected

- **Raise the frontend limit past 1792 MB and rely on ergonomics.** Fixes today's symptom and
  leaves the mechanism armed: the next person trimming the memory budget — exactly what #937 did —
  re-introduces the defect with no signal that anything changed.
- **Set `-XX:+UseG1GC` on the frontend and leave the limit at 1280M.** Free, and it works: G1 runs
  fine at a 640 MB ceiling. Rejected because ergonomics and the explicit flag would then disagree,
  which is a trap for the next reader, and because G1's remembered sets are larger than Serial's —
  at 1280M the measured floor plus that growth sits uncomfortably close to the 80 % rule.
- **Put ingest on G1 too, for uniformity.** Uniformity is not the goal; naming the collector is.
  G1 on a 512M limit would need more memory to be worth having, and the budget is better spent
  where requests are actually served.
- **Alert on the collector instead.** A `jvm_gc_pause_seconds_count{gc="Copy"}` rule would detect
  it, but detection after deploy is strictly worse than a flag that cannot be wrong.

## Consequences

- The frontend's old generation is collected concurrently, so the monotonic climb stops. Resident
  memory should settle into a sawtooth well under the ceiling instead of approaching it.
- The frontend's minor-GC pauses stop being single-threaded, removing a 13.4 ms tax that fell on
  page renders.
- `+512 MiB` of declared limits. Summed over every prod-profile service the stack becomes
  **9792 MiB app + 4368 MiB monitoring = 14 160 MiB (13.83 GiB)**, still under ADR-0085's ~14 GB
  review trigger and ~1.4 GiB under the host's 15.24 GiB.
- **Every heap figure recorded before 2026-09-13 for the frontend and ingest was measured on
  SerialGC** and does not carry over. The `JVM CONTAINER SIZING` table must be re-measured under G1
  before it is used to justify another change; this ADR deliberately does not pre-empt those numbers.
- The collector becomes greppable. `jvm_gc_pause_seconds_count` identifies it with no flag to read:
  `gc="Copy"` + `gc="MarkSweepCompact"` is Serial, `gc="G1 Young Generation"` is G1.
