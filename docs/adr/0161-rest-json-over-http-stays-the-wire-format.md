# ADR-0161 — REST/JSON over HTTP stays the wire format; gRPC is rejected as a migration

- **Status:** Proposed
- **Date:** 2026-09-10
- **Deciders:** @greluc (pending)
- **Related:** [`WIRE_PROTOCOL_EVALUATION.md`](../WIRE_PROTOCOL_EVALUATION.md) (the full analysis) ·
  specs `REQ-API-001`, `REQ-API-003`, `REQ-API-004`, `REQ-API-007`, `REQ-API-009`, `REQ-SEC-011`,
  `REQ-SEC-031`, `REQ-OBS-005…012`, `REQ-INGEST-012` ·
  [ADR-0012](0012-frontend-krtfetch-json-mutations-csrf-retry.md) ·
  [ADR-0132](0132-global-exception-handler-outranks-springs-problem-details-advice.md) ·
  [ADR-0135](0135-public-api-vhost-not-a-gateway.md) ·
  [ADR-0136](0136-external-contract-set-for-shipped-clients.md) ·
  [ADR-0131](0131-mobile-auth-refresh-only-dpop-binding.md) ·
  [ADR-0144](0144-csrf-is-exempted-for-the-whole-bearer-only-api.md)

## Context

The question was put directly: would gRPC — or a comparably modern format — improve performance,
maintainability and security, given that none of the three may get worse and no regression is
acceptable?

It is a fair question to ask now rather than later. Spring Boot 4.1, which this project runs, ships
**first-party** gRPC support (`spring-boot-grpc-server` / `-client` / `-test`, Spring gRPC 1.1.0,
grpc-java 1.80.0, with server, client, SSL, security and health indicators auto-configured). The
argument "gRPC is exotic in Spring" is no longer available, so the decision has to be made on this
system's own facts.

Those facts are unusually well documented, and they point in one direction.

**There are six wire seams, not one.** Browser→frontend pages (Thymeleaf HTML), browser→frontend
mutations (`krtFetch` JSON + HTML fragment swaps), browser↔frontend live sync (WebSocket),
frontend→backend (585 REST/JSON call sites), Android→backend (REST/JSON + SSE, DTOs generated from the
committed `openapi.json`), and extractor→ingest (2 endpoints, DPoP-bound). Browsers cannot speak gRPC,
so three of the six are out by construction; two face clients that cannot be redeployed with the
server, which is why ADR-0136 exists at all. Exactly **one** seam — frontend→backend — is even a
candidate.

**The measured bottleneck is not the one a binary protocol removes.** ADR-0085's re-measurement is the
only capacity evidence in the repository: the whole basetool database is **107.7 MB** with a **99.990 %**
cache-hit ratio and **zero** temp files in seven days; the backend's GC overhead peaks at **0.14 %**;
the 21 containers together average **0.256 cores (3.2 %)** of 8 vCPU. Every container runs on one 16 GB
host on one Docker network, so the frontend→backend round trip is a loopback-class hop, and that hop is
already gzip-compressed with `@Cacheable(sync = true)` single-flighting the heavy catalogues. The
latency alert is p95 > 2 s — a "something is badly wrong" threshold, not an objective being missed.

The one real pressure signal is **CPU throttling on the frontend** (1 506 s over 7 days at its old 1.0
quota, the worst absolute stall in the stack). What that CPU is spent on — Thymeleaf rendering, JSON
decoding, TLS, gzip — has **not** been profiled, and betting a protocol migration on an unmeasured
share of one container's CPU is not a decision, it is a guess.

**Two things a binary format cannot fix, and one it makes worse.** The measured read-amplification
signal (`AppFanoutRatioHigh`, >10 backend calls per inbound request) is a *call-count* cost; protobuf
shrinks payloads, it does not reduce call count. The heavy 16 MB materials matrix is already contained
by gzip and single-flight caching, and its residual cost is the decoded object graph, which protobuf
does not shrink. Meanwhile protobuf actively collides with this domain: no decimal type against 105
`BigDecimal`-carrying files including every bank request DTO on an append-only double-entry ledger; no
UUID type against 702 files; proto3's erasure of the null/zero distinction against 57 `@Nullable` DTOs,
where `version = null` and `version = 0` are the difference between a 409 and a lost update; and no
place to hang the **~870 Jakarta Bean Validation constraints** that `REQ-API-003` requires on every write body,
because generated protobuf classes are builders (protovalidate is the accepted answer, and it means
re-authoring all of them in a second language).

**And the guarantees at risk are HTTP-shaped by design.** RFC 7807 problem+json with its stable `code`,
`correlationId` and `fieldErrors` (REQ-API-004, ADR-0132 — pinned at `HIGHEST_PRECEDENCE` precisely
because losing it degrades every 400 to a useless toast); `private, no-store` as the only thing that
tells an intermediary not to keep a bank ledger (REQ-SEC-031); the default-deny **path** allow-list at
the edge that ADR-0135 chose over a gateway; per-IP rate-limit attribution via `X-Forwarded-For`
(REQ-SEC-011), which collapses into one org-wide bucket when it breaks; DPoP proofs bound to the HTTP
method and URI (`htm`/`htu`, REQ-INGEST-012); and an observability stack — `HttpLatencyP95High`,
`AppFanoutRatioHigh`, the RED dashboards, the `blackbox-http` probes — keyed entirely on HTTP series
and HTTP probes, which `CLAUDE.md` obliges us to keep in sync in the same PR.

## Decision

**We will keep REST/JSON over HTTP as the wire format on all six seams. gRPC is rejected as a
migration — for the browser seams by construction, for the frontend→backend seam on cost/benefit, and
for the two shipped-client seams because the clients gain almost nothing and the coordination cost is
real.**

Instead we will pursue the transport and tooling improvements that address the *measured* problems and
preserve every contract above. In priority order, each as its own change with its own verification:

1. **Negotiate HTTP/2 on the frontend→backend hop.** Both apps set `server.http2.enabled: true`, but
   `WebClientConfig` builds its `SslContext` with no `applicationProtocolConfig` and never calls
   `.protocol(HttpProtocol.H2)` — so Reactor Netty runs **HTTP/1.1** against a server that has offered
   HTTP/2 all along. This captures the multiplexing benefit that is gRPC's most valuable property, and
   it removes the documented connection-pool ceiling (*"~25 concurrent users can exhaust a 50-slot
   pool"*, against a 200-concurrent-user target) without touching one DTO, endpoint or test contract.
2. **Generate the frontend's 261 mirror DTOs from the committed `openapi.json`**, with the generator the
   Android module already runs. This is gRPC's headline maintainability benefit — drift as a compile
   error rather than a test failure — at a small fraction of the cost and with no wire change.
3. **Stop paying for an ETag nobody can use — the backend one.**
   `StreamAwareShallowEtagHeaderFilter` buffers and MD5-hashes every `/api/**` response except two
   stream paths, while **no** first-party client sends `If-None-Match`: `BackendApiClient` never does,
   and the Android client installs no HTTP cache by deliberate security decision. On the `no-store`
   families `ApiCacheControlFilter` says outright that nothing relies on conditional requests — and
   they are buffered and hashed anyway.

   **Scope this to the backend bean.** The frontend has its own `EtagConfig` registering Spring's
   `ShallowEtagHeaderFilter` on `/*`, and *that* one serves browsers, which do send `If-None-Match`.
   Removing it would be a regression. §8.3 spells the difference out, because "remove the ETag filter"
   is exactly the instruction a reader would carry out on the wrong bean.

4. **Add an `openapi.json` schema diff against the previous release tag in CI**, closing the gap
   ADR-0136 documents about itself (`ExternalContractTest` *"does not compare types, nullability or enum
   values"*). This is the evolution discipline protobuf field numbers would have provided, as a test
   rather than a migration. It is **`REQ-API-009`'s own open acceptance box** — *"Type and nullability
   changes are caught. Open"* — not a new item; whoever ships it ticks that box.

5. **Only if frontend CPU is still the constraint after 1 and 3**, and only on evidence: a Jackson binary
   backend (CBOR or Smile) negotiated by `Content-Type` on the frontend→backend seam. Same records, same
   ~870 constraints, same RFC 7807 handling, same filters, same metrics — only the bytes change, and
   content negotiation makes it revertable per endpoint.

Items 1–4 are proposals in this ADR, not work done by it. **No runtime behaviour was changed.**

## Consequences

**We accept that four genuine gRPC benefits go unclaimed** — smaller payloads, generated clients on
every seam at once, schema-first evolution everywhere, and built-in bidirectional streaming — on the
grounds that the first is already largely bought by gzip, the second and third are obtainable from the
`openapi.json` generator we already run, and the fourth does not reach the browser, which is where both
streaming needs live.

**We accept that the frontend↔backend DTO mirror stays hand-maintained until item 2 ships**, and that
four contract tests over a 1.87 MB spec keep carrying the load a schema compiler would have carried.

**The evaluation is written down so it need not be re-litigated from scratch.**
[`WIRE_PROTOCOL_EVALUATION.md`](../WIRE_PROTOCOL_EVALUATION.md) records the seam inventory, the measured
evidence, the eight candidates, the per-seam verdicts and — importantly — the five triggers that would
flip this answer: a real service split across a network, a third or fourth non-browser client, profiling
that shows serialization dominating *after* items 1 and 3, a high-frequency non-browser streaming need,
or the deployment leaving one host. User growth alone is deliberately **not** a trigger.

**This ADR is explicit about what it could not verify**, so nobody mistakes an assumption for a
measurement: there was no production profiler run (so the frontend's CPU split is unknown), no benchmark
of JSON vs. protobuf on this workload, and no production access. The knowledge-base vault was
unavailable in the session that produced it (owner-approved to proceed code-only); it has since
been read and updated, and `40 Decisions/Decisions.md` now carries this decision, its four
findings and the two corrections that review produced. Every estimate in the analysis is marked
as one.

**Item 1 carries real risk and must not be waved through.** Under HTTP/2 a `ConnectionProvider`'s
`maxConnections` bounds connections rather than in-flight calls, so the 100/1000 pool sizings and the
deliberate 5 s alignment with the Resilience4j `TimeLimiter` must be re-derived rather than carried over;
Tomcat's per-connection stream ceilings must be confirmed against the version in the image; the SSE
relay is the risk case and may want to stay on HTTP/1.1 initially (it is already a separate connector);
and the pinned-trust hostname-verification behaviour must be preserved exactly. It needs a load test on
mission-detail and the materials matrix, and `http_client_requests_seconds` p95 must not regress — a
"modern transport" that regresses p95 fails the brief exactly as a format migration would.

## Alternatives considered

- **gRPC on the frontend→backend hop only.** The strongest candidate, and still rejected: 533 endpoint
  mappings and 585 call sites cannot move atomically, so both protocols would run side by side with each
  of the 14 backend servlet filters needing a working interceptor twin — and the interesting bugs live
  exactly in the difference between them, which is ADR-0135's own reason for refusing a second path.
  The maintainability prize is obtainable without touching the wire (decision item 2), and the premise
  does not hold on one host at 3.2 % average CPU.
- **gRPC everywhere, including Android and ingest.** Adds two clients that cannot be redeployed with
  the server. The coordination this needs now exists — `REQ-API-010` closed it on 2026-08-24: the
  server names a floor in `GET /api/v1/app/version-policy` and the app refuses to run below it. So the
  honest objection is not "unshippable" but "expensive for nothing": the floor is a **staged**
  migration's prerequisite, not a free pass, and every member below it is locked out until they update.
  The win it would buy is near zero anyway — the Android client already has HTTP/2 via OkHttp and
  already generates its DTOs from the same `openapi.json`; the extractor's value is in its
  DPoP/rate-limit/payload filters, and DPoP binds to the HTTP method and URI.

  > [!note] ADR-0136 says the gate does not exist. That was true when it was written
  > Its *"nothing today can tell an old build to stop"* is dated, and an earlier draft of this ADR
  > carried it forward as the current state — the same mistake as reading ADR-0001 as a description of
  > the frontend client. The living answer is `docs/specs/api-conventions.md`, whose acceptance box
  > reads *"A sunset can actually retire old builds — **closed by REQ-API-010**"*. The floor is set to
  > `0` today, so it gates nothing yet; it is a configuration value away from doing so.

- **gRPC-Web / Connect RPC for the browser.** Would require turning a server-rendered, CSP-hardened
  Thymeleaf + fragment-swap frontend into a SPA: ADR-0012, ADR-0013, ADR-0069, ADR-0093, ADR-0125 and
  ADR-0130 all undone, plus REQ-FE-001…010, 110 templates, 43 k lines of JS and 94 E2E tests. That is a
  UI rewrite wearing a protocol's clothes, and it would trade a mature security posture for one built
  from scratch.
- **Protobuf payloads over the existing REST endpoints** (`ProtobufHttpMessageConverter`, keeping URLs,
  verbs and status codes). Tempting as a halfway house, and it keeps items 1–3 of the §7 regression
  ledger intact — but it still imports the whole protobuf type-system collision: no decimal in a ledger,
  no UUID, proto3 presence semantics under optimistic locking, and ~870 Bean Validation constraints
  with nowhere to live. If a binary payload is ever wanted, CBOR/Smile buys most of the same bytes with
  none of that.
- **GraphQL for the read paths.** Addresses over-fetching (14 page-walked catalogues) but adds a second
  authorization surface beside `OwnerScopeService`'s scope triple, per-`sub` isolation and guest field
  redaction. ADR-0135 already refused a second copy of authorization rules — *"a second copy of an
  authorisation rule is a divergence waiting to happen"* — and server-side filtering (ADR-0100,
  ADR-0105) is the cheaper answer the project is already pursuing.
- **zstd instead of gzip.** A real ratio/CPU improvement at the same layer as decision item 1, for a
  fraction of the benefit, on an internal hop where bandwidth is not scarce. Revisit only if payload
  bytes ever become the measured constraint.
- **Change nothing at all.** Rejected: it would leave HTTP/1.1 on the busiest hop, 261 mirror DTOs
  hand-maintained, and an ETag buffer being paid for on every API response with no client able to
  redeem it. The question was worth asking; the answer is not "everything is fine", it is "the wins are
  elsewhere, and cheaper".

