> **Doc type:** Historical analysis — a decision input, frozen at its date. The decision it produced
> is [ADR-0161](adr/0161-rest-json-over-http-stays-the-wire-format.md); the living rules stay in
> [`docs/specs/api-conventions.md`](specs/api-conventions.md).
> **Date:** 2026-09-10 · **Owner area:** API · BE · FE · SEC · OBS
>
> **Amended the same day, once.** §8.1–§8.5 were implemented, and each now ends in an **Outcome**
> paragraph. Three of the five proposals were wrong about something and are corrected next to the
> claim they replace — see the callout at the head of §8. Nothing else in the analysis was
> rewritten: the argument for the refusal is what it was, and a document that quietly improved its
> own predictions after the fact would be worth less than one that records where they missed.

# Wire-protocol evaluation — gRPC and the alternatives

## 0. The question

> Would moving to gRPC — or a comparably modern format — buy us anything? Performance,
> maintainability and security must improve, and must under no circumstances get worse. No
> regressions.

That last clause is not decoration. It is the hardest constraint in the question, and it is what
most of this document is about: a protocol change is not a library swap here, it is a change of the
substrate that six of this project's cross-cutting guarantees are built on.

The short answer, argued below: **no, not as a migration.** The measured bottlenecks are not the
ones a binary RPC protocol removes, and the guarantees at risk are exactly the ones the project has
spent 162 ADRs building. But the question surfaced four concrete, independently valuable transport
findings — one of them a live, unnoticed cost — and those are in §8.

## 1. Method and evidence base

### 1.1 What this analysis read

Static analysis of the three repositories in the workspace (`basetool`, `basetool-android`,
`basetool-sc-extractor`) plus the committed operational evidence:

|                                    Source                                    |                                       What was taken from it                                       |
|------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `backend/`, `frontend/`, `ingest/`, `keycloak-spi`                           | 240 k lines of production Java in 1 714 files (+ 902 test-source files); seams, filters, DTO shape |
| `docs/specs/` (42 specs), `docs/adr/` (162 ADRs)                             | the binding contracts a protocol change would have to keep                                         |
| `monitoring/`                                                                | alert rules, dashboards, probe jobs — the observability substrate                                  |
| [ADR-0085](adr/0085-scale-user-sync-and-stack-capacity-for-5000-accounts.md) | the only **measured** capacity data in the repository                                              |
| `basetool-android` `core/network`, `core/contract`                           | the second client's transport and its code-generation seam                                         |
| `basetool-sc-extractor` `net/`, `auth/`                                      | the third client's transport and its DPoP binding                                                  |

### 1.2 What this analysis did **not** have — declared assumptions

Being explicit, because several tempting conclusions would need these and cannot be drawn without
them:

1. **No production profiler run.** ADR-0085 measures container CPU throttling, heap, GC and database
   counters. It does **not** break the frontend's CPU down into Thymeleaf rendering vs. JSON decoding
   vs. TLS vs. gzip. Wherever this document attributes frontend CPU to a specific cause it says so as
   an *assumption*, never as a measurement.
2. **No benchmark of JSON vs. protobuf on this workload.** Published protobuf-vs-JSON figures are not
   transferable: they are usually measured on small, flat, numeric messages, whereas the heavy paths
   here are large, string-dominated, deeply nested catalogues. Any number in §6 marked *(estimate)* is
   reasoning from message shape, not a measurement.
3. **No production access.** Per the production-host approval gate in `CLAUDE.md`, nothing was read
   from the live host. Current p95 latency, real request mix and actual payload sizes in flight are
   therefore unknown to this document; only what the repository records is used.
4. **The knowledge base was unavailable** in the session that wrote this (the vault was not beside
   the repos). The owner approved proceeding code-only, so nothing it records about past protocol
   incidents or rejected experiments shaped the analysis below. **It was read on 2026-09-10 during
   review** and holds nothing that contradicts this document; the decision and its findings are now
   recorded there (`40 Decisions/Decisions.md`).

## 2. The system as it actually is — six wire seams

A "migration to gRPC" is not one decision. There are six distinct hops, each with different traffic,
different clients and different constraints. Treating them as one is the first way this question goes
wrong.

| # |              Seam              |                      Today                       |                   Volume / shape                    |          Who cannot be redeployed with the server          |
|---|--------------------------------|--------------------------------------------------|-----------------------------------------------------|------------------------------------------------------------|
| 1 | Browser → frontend (pages)     | Thymeleaf server-rendered HTML over HTTPS (edge) | 110 templates, 28 fragments                         | every browser, always                                      |
| 2 | Browser → frontend (mutations) | `krtFetch` JSON writes + HTML-fragment swaps     | 73 `write` + 22 `submitForm` + 83 `swap` call sites | every browser, always                                      |
| 3 | Browser ↔ frontend (live)      | WebSocket relay (`LiveSyncWebSocketHandler`)     | topic rooms, coalesced 400 ms/1500 ms               | every browser, always                                      |
| 4 | **Frontend → backend**         | **REST/JSON over HTTP/1.1 + TLS, gzip**          | **585 call sites**, 533 endpoint mappings           | nobody — atomic deploy (ADR-0136's premise)                |
| 5 | Android → backend              | REST/JSON over HTTP/2 (OkHttp 5) + SSE           | contract-frozen subset (REQ-API-009)                | **released APKs, for weeks** (GitHub Releases + Obtainium) |
| 6 | SC extractor → ingest          | REST/JSON + DPoP (RFC 9449)                      | **2 endpoints**                                     | released MSI installs                                      |

Two structural facts follow immediately, and they do most of the work in this evaluation:

- **Seam 4 is the only one where a protocol change is even cheap to *deploy*.** It is internal, both
  ends ship from one repository, and `DtoOpenApiContractTest` already guards the mirror. Seams 5 and 6
  face clients that cannot be redeployed with the server — [ADR-0136](adr/0136-external-contract-set-for-shipped-clients.md)
  exists precisely because of that — and seams 1–3 face browsers, which cannot speak gRPC at all.
- **Seam 4 is also the seam where the network is least likely to be the problem.** Every container
  runs on **one 16 GB Hetzner host** on one Docker network. The frontend→backend round trip is a
  loopback-class hop. gRPC's headline wins — fewer bytes, fewer round trips, header compression — are
  wins against *network*, and there is barely any network here.

## 3. Where the time and the CPU actually go

This is the part that decides the answer, so it is worth being precise about what is measured.

### 3.1 The database is not the bottleneck

From ADR-0085's re-measurement (the numbers that caused `db-backend` to be sized **down**):

- Basetool database: **107.7 MB**. Keycloak database: 39.4 MB.
- Buffer-cache hit ratio: **99.990 %**.
- Temp files written in seven days: **0**, at a connection peak of 31.

The entire working set is in RAM and no query has ever spilled to disk. Serialization cannot be hidden
behind a slow database here, but neither is there a database problem for a protocol to relieve.

### 3.2 The host is mostly idle; CPU throttling is the one real pressure signal

- 21 containers together average **0.256 cores (3.2 %)** on 8 physical vCPU.
- Backend: heap 647 MB used of a 1 167 MB ceiling, **GC overhead 0.14 % peak**.
- Frontend: **1 506 s throttled over 7 days** at a 1.0-CPU quota — *the worst absolute stall in the
  stack*, which is why its quota was raised to 2.0.
- `npm` (the TLS-terminating edge): **20.0 % peak throttle ratio**.
- `ingest`: 76.7 % peak throttle ratio — but on two endpoints whose work is dominated by DPoP
  verification and payload handling, not by JSON.

So: the system is not bandwidth-bound, not memory-bound, not GC-bound and not database-bound. The
single place where a cheaper codec could pay is **frontend CPU**. And there, an honest reading has to
concede that the frontend's CPU is shared between Thymeleaf rendering, JSON decoding, TLS and gzip —
**and this analysis cannot say in what proportion** (assumption 1). Betting a protocol migration on an
unmeasured share of one container's CPU is not engineering.

### 3.3 The latency budget is loose

The alert that guards request latency is `HttpLatencyP95High`: p95 `http_server_requests_seconds`
**> 2 s for 10 minutes**. That is a "something is badly wrong" threshold, not a tight SLO being
missed. Nothing in the repository records a latency objective that the current transport fails.

### 3.4 The read-amplification signal is the interesting one

`AppFanoutRatioHigh` fires when the frontend issues **more than 10 backend calls per inbound
request**. Its description names the failure mode exactly: *"a page/fragment that lost its
read-gating"*. `ParallelPageLoader` is used across 10 controllers with 33 fan-out sites, and the
connection-pool comment in `WebClientConfig` records that a single mission-detail render fans out to
four parallel backend calls.

**This is a real cost, and it is a *call-count* cost, not a *byte* cost.** That distinction matters:
protobuf makes each call's payload smaller; it does not make there be fewer calls. HTTP/2 multiplexing
makes many concurrent calls cheap; that *is* the matching remedy — and it needs no format change at
all (§8.1).

### 3.5 The heavy payload is real, and it is a caching problem

`WebClientConfig.MAX_IN_MEMORY_BYTES` is 64 MB, and its Javadoc records why: the materials trade
matrix (`/api/v1/materials/matrix?size=100000`) *"tipped the buffer"* at 16 MB. Fourteen of the 24
cached catalogues are assembled by a complete page walk (`CachedCatalog`, `Fetch.PAGE_WALK`).

Protobuf would shrink that response meaningfully — matrix rows are numeric and repetitive, the shape
protobuf is genuinely good at. But note what already contains it: the hop is **gzip-compressed**, and
every `getCached` overload is `@Cacheable(sync = true)`, so a cold-cache stampede collapses to one
in-flight fetch. The remaining cost is the decoded object graph, which protobuf does **not** remove —
a decoded protobuf message graph is not materially smaller in heap than a decoded record graph.

## 4. The candidates

"gRPC or something similarly modern" is a family, not one option. Eight were evaluated. They are
listed cheapest-first, because the ordering itself is a finding: the cheapest options address the
measured problems and the most expensive ones do not.

|                              Candidate                              |     Format      |   Transport    |                              What it actually changes                              |
|---------------------------------------------------------------------|-----------------|----------------|------------------------------------------------------------------------------------|
| **G — HTTP/2 on the frontend→backend hop**                          | JSON, unchanged | HTTP/2         | ~15 lines of `WebClientConfig`. No contract change at all.                         |
| **H — zstd instead of gzip**                                        | JSON, unchanged | HTTP/1.1 or /2 | one `Content-Encoding` negotiation; needs a codec on both ends.                    |
| **E — binary JSON (CBOR / Smile / MessagePack)**                    | binary          | unchanged      | a Jackson factory swap + content negotiation. Same object model, same annotations. |
| **D — protobuf payloads over the existing REST endpoints**          | protobuf        | unchanged      | `.proto` schemas + `ProtobufHttpMessageConverter`; URLs, verbs, status codes stay. |
| **F — GraphQL for the read paths**                                  | JSON            | HTTP/1.1 or /2 | a second API surface with its own authorization story.                             |
| **B — gRPC on the frontend→backend hop only**                       | protobuf        | HTTP/2         | 533 endpoint mappings → services; 14 filters → interceptors.                       |
| **A — gRPC everywhere the API is consumed** (incl. Android, ingest) | protobuf        | HTTP/2         | B, plus two shipped clients and the whole external contract set.                   |
| **C — gRPC-Web / Connect RPC for the browser**                      | protobuf/JSON   | HTTP/1.1 or /2 | requires the frontend to become a SPA. Deletes seams 1–3 as they exist.            |

### 4.1 One fact that changed recently and must be acknowledged

**Spring Boot 4.1 — which this project runs (4.1.1) — ships first-party gRPC support**:
`spring-boot-grpc-server`, `spring-boot-grpc-client` and `spring-boot-grpc-test`, backed by Spring gRPC
1.1.0 and grpc-java 1.80.0, with server, client, SSL, security and health indicators auto-configured
([Spring gRPC reference](https://docs.spring.io/spring-grpc/reference/whats-new.html),
[Spring Boot 4.1 release notes](https://versionlog.com/spring-boot/4.1/)).

This genuinely lowers the *plumbing* cost of options A and B, and it would be dishonest to argue
against gRPC on "it is exotic in Spring" grounds. It is not exotic any more. What it does **not**
lower is the cost this project would actually pay, which is not plumbing but the re-implementation of
project-specific cross-cutting behaviour (§7).

### 4.2 The protobuf type-system problem (applies to A, B, C, D)

Four properties of this domain collide with protobuf, and each is a correctness risk rather than an
inconvenience:

1. **There is no decimal type in protobuf.** 105 files use `BigDecimal`, including every bank request
   DTO — and the bank is a **double-entry, append-only ledger** ([ADR-0010](adr/0010-bank-double-entry-append-only-ledger.md)).
   The options are `string` (lossless, but every constraint moves into hand-written code) or a scaled
   `int64` (fast, and a silent rounding bug the first time someone picks the wrong scale). Neither is
   an improvement on `BigDecimal` + `@DecimalMin` + `@WholeNumber`. A money format that can round is
   a regression in a ledger, full stop.
2. **proto3 erases the null/zero distinction unless every optional field says `optional`.** 57 DTOs in
   `model/dto` carry `@Nullable`, and the semantics matter: `version = null` means *"no version
   supplied"*, `version = 0` means *"version zero"*. Under optimistic locking that difference is the
   difference between a **409** and a **silently lost update**. `optional` fixes it per field — which
   means the guarantee now depends on nobody ever forgetting the keyword on a new field, where today
   it depends on the type system.
3. **No UUID type.** 702 files touch `UUID`; every one becomes a `string` (or `bytes`) whose validity is
   checked by hand rather than by the parameter type.
4. **Bean Validation does not apply to generated protobuf classes.** This is the big one. The project
   has **~870 Jakarta Bean Validation constraints** at its boundaries — ~710 in the backend across 156
   files (139 `@Size`, 111 `@NotBlank`, 49 `@Min`, 29 `@DecimalMax`, 26 `@DecimalMin`, 17 `@WholeNumber`,
   17 `@PositiveOrZero`, … plus `@NotNull`, whose exact count is blurred because 37 of those files also
   import JetBrains `@NotNull`) and ~160 more in the frontend's mirror DTOs across 41 files — plus 170
   `@Valid` sites and two custom `ConstraintValidator`s — and `REQ-API-003` **requires** `@Valid` on
   every write body.
   Protobuf's generated Java classes are builders; there is nowhere to hang an annotation
   ([protovalidate-java](https://github.com/bufbuild/protovalidate-java) is the accepted answer, and it
   means re-authoring every one of those ~870 constraints as proto options / CEL expressions).

Point 4 alone is the decisive one for the "security must not get worse" clause: input validation is a
security control, and the migration path for it is *"re-write ~870 constraints in a second
language"*. There is no mechanical translation, and every mistranslation is an input-validation hole
that no test currently in the repository would notice.

## 5. Seam-by-seam verdict

### Seam 1–3: browser ↔ frontend — **excluded, not merely rejected**

Browsers cannot speak gRPC. gRPC-Web needs a translating proxy and still has no client streaming;
Connect RPC is friendlier but is still an RPC-over-HTTP shape for a surface that is currently
**hypermedia**: server-rendered Thymeleaf plus HTML-fragment swaps (`krtFetch.swap`, 83 sites).

Adopting C would mean deleting the frontend's whole architecture — [ADR-0012](adr/0012-frontend-krtfetch-json-mutations-csrf-retry.md)
(krtFetch), [ADR-0013](adr/0013-frontend-bfcache-history-restore-reload.md) (bfcache), [ADR-0069](adr/0069-inline-js-page-module-extraction.md)
(page modules), [ADR-0093](adr/0093-eliminate-inline-style-attributes-csp-style-src-attr-none.md) (CSP
`style-src-attr: none`), [ADR-0125](adr/0125-typed-javascript-via-checkjs-not-typescript.md) /
[ADR-0130](adr/0130-own-openapi-dts-emitter-and-typescript-7.md) (typed JS via `checkJs`) — and
rebuilding it as a SPA, together with the entire REQ-FE-001…010 live-update contract, 110 templates,
43 k lines of JS and 94 E2E tests.

That is not a protocol migration. It is a rewrite of the product's UI layer, and it would trade a
mature CSP-hardened server-rendered surface for a client-rendered one whose security posture would
have to be rebuilt from scratch. **Verdict: excluded.**

### Seam 4: frontend → backend — the only genuine candidate, and still a no

This is where gRPC would be most defensible: internal, atomic deploy, 585 call sites on one client.
The honest case *for* it:

- 261 hand-maintained frontend mirror DTOs (against 401 backend DTOs) would collapse into generated
  code from one `.proto` set. That is a **real** maintainability win — today the mirror is kept honest
  by four contract tests (`DtoOpenApiContractTest`, `FrontendDtoContractTest`, `ExternalContractTest`,
  `HandRolledFetchGateContractTest`) reading a **1.87 MB** committed `openapi.json`.
- Contract drift would become a compile error instead of a test failure.
- The 16 MB materials matrix would shrink, and server-streaming would remove the buffer entirely.

And the case against, which is stronger:

- **The mirror-DTO duplication is solvable without gRPC, and the project already knows how.** The
  Android app generates its DTOs from the very same committed `openapi.json` via
  `openapi-generator 7.25.0` (`core/contract/build.gradle.kts`). Applying that generator to the
  frontend module deletes the same duplication with none of the protocol risk. gRPC would be a very
  expensive way to obtain a code generator the repository already runs.
- **Two protocols would coexist for the whole migration.** 533 endpoint mappings cannot move
  atomically. During the transition every one of the 14 backend servlet filters needs a working
  `ServerInterceptor` twin, and the interesting bugs live exactly in the difference between them —
  which is [ADR-0135](adr/0135-public-api-vhost-not-a-gateway.md)'s own reason for refusing a gateway,
  applied to a protocol instead of a hop.
- **The performance premise does not hold.** Same host, same Docker network, already gzip-compressed,
  already caching with single-flight, no measured latency objective being missed, host at 3.2 % average
  CPU. What *is* measured — connection-pool pressure and call fan-out — is fixed by HTTP/2 (§8.1),
  not by protobuf.

**Verdict: rejected. The maintainability win is real, and it is obtainable without touching the wire
at all (§8.2).**

### Seam 5: Android → backend — **rejected, and the reasoning inverts interestingly**

This is the one seam where protobuf's *evolution* model is genuinely attractive. Field numbers plus
`optional` are a better answer to "a released APK sits on a phone for weeks" than
`ExternalContractTest`'s `containsAll` check, which [ADR-0136](adr/0136-external-contract-set-for-shipped-clients.md)
itself admits *"does not compare types, nullability or enum values"*. A protobuf schema would catch
exactly the class of break that ADR names as its own open gap.

It is still a no, for four reasons:

1. **The client already has HTTP/2** (OkHttp 5 negotiates it over ALPN), so the transport win is zero.
2. **APK size and the privacy gate.** `grpc-okhttp` + `protobuf-javalite` is a new dependency set on a
   project whose `CLAUDE.md` requires that *every* new dependency's data flows be re-checked and the
   §7 inventory extended in the same PR. That is the correct process, and it is not free.
3. **DTOs are already generated** from `openapi.json` — the duplication gRPC would remove does not
   exist on this seam.
4. **Every RFC 7807 code is a first-class UI state in the app** (`UNAUTHENTICATED`, `PENDING_APPROVAL`,
   the terms-gate 403, `RATE_LIMIT_EXCEEDED` + `Retry-After`, `SERVICE_UNAVAILABLE`,
   `OPTIMISTIC_LOCK`). gRPC has 17 status codes and no problem-detail standard; all six would move into
   hand-rolled trailer metadata, on the client that is hardest to fix when it is wrong.

**Verdict: rejected. But adopt the *idea* — see §8.4.**

### Seam 6: SC extractor → ingest — **rejected as pure downside**

Two endpoints. Everything valuable about this seam is in its **filters**: `PayloadSizeLimitFilter`,
`RateLimitingFilter`, `IdentityProviderUnavailableFilter`, `BotProtectionFilter`,
`ClientIdentityFilter`, `SecurityProblemResponseHandler` and the DPoP converter
(`PublicUriDpopAuthenticationConverter`) implementing sender-constrained tokens per RFC 9449
([ADR-0131](adr/0131-mobile-auth-refresh-only-dpop-binding.md), REQ-INGEST-012). DPoP proofs bind to
the **HTTP method and URI** (`htm`/`htu` claims) — the binding is defined *in terms of HTTP*.

Re-expressing that stack in gRPC interceptors would mean re-deriving a working security control, for
two endpoints, with no benefit whatsoever. The extractor also deliberately avoids a JOSE dependency
because `SunEC` is in `java.base` — adding gRPC would add the jlink modules that decision avoided.

**Verdict: rejected, without qualification.**

## 6. Consolidated pro / contra

### 6.1 What gRPC would genuinely buy

|                             Benefit                             | Real?  |                                            Assessment for *this* system                                            |
|-----------------------------------------------------------------|--------|--------------------------------------------------------------------------------------------------------------------|
| Smaller payloads (no field names, varint, no base-64)           | yes    | but the heavy hop is already gzip-compressed, and the residual win is *(estimate)* modest on string-dominated rows |
| HTTP/2 multiplexing — no head-of-line blocking, no pool ceiling | yes    | **the single most valuable item — and obtainable without gRPC (§8.1)**                                             |
| Generated clients; contract drift becomes a compile error       | yes    | **real win — and obtainable from the existing `openapi.json` generator (§8.2)**                                    |
| Schema-first evolution via field numbers                        | yes    | genuinely better than `ExternalContractTest`'s `containsAll`; see §8.4 for the cheap version                       |
| Bidirectional streaming                                         | yes    | but the two streaming needs (notifications, live sync) are **browser-facing**, where gRPC streaming does not reach |
| Cheaper serialization CPU                                       | partly | unquantified here (assumption 2); the host averages 3.2 % CPU, so the headroom is not needed                       |
| Deadline propagation built in                                   | yes    | already covered end-to-end by Resilience4j `TimeLimiter` + `responseTimeout` + the JPA query timeout               |

### 6.2 What it would cost — and this is the part that decides it

|                      Cost                       |                                                                       Magnitude in this repository                                                                        |
|-------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Endpoints to re-express as services             | **533** mapping annotations across **83** controllers                                                                                                                     |
| Client call sites to rewrite                    | **585** `backendApiClient.*` calls                                                                                                                                        |
| DTO/schema definitions to author                | **401** backend DTOs (+ 49 MapStruct mappers whose target types change)                                                                                                   |
| Validation constraints to re-author             | **~870** Jakarta constraints + 170 `@Valid` sites + 2 custom validators → protobuf options / CEL                                                                          |
| Cross-cutting filters needing interceptor twins | **14** backend servlet filters — 13 project classes plus Spring's `ForwardedHeaderFilter`, registered in `ForwardedHeaderConfig` — + 1 interceptor (+ 15 on the frontend) |
| Tests at risk                                   | **7 278** `@Test` methods, **94** E2E tests                                                                                                                               |
| Specs / ADRs to amend                           | REQ-API-001…009, REQ-SEC-011/023/027/030/031/032/033/037/044, REQ-OBS-005…012 — and ADRs 0012, 0132, 0135, 0136, 0144 at minimum                                          |
| Monitoring to rebuild                           | every dashboard panel and alert keyed on `http_server_requests_seconds` / `http_client_requests_seconds`, plus the `blackbox-http` probe jobs                             |

## 7. The regression ledger — what would actually break

The brief says security and performance may not get worse and there may be no regressions. These are
the concrete things that *would* get worse, each tied to the artefact that enforces it today. This is
the list that makes the answer a no rather than a "maybe later".

1. **RFC 7807 problem+json disappears.** `REQ-API-004` mandates `type`, `title`, `status`, `detail`,
   `instance`, a stable `code`, a per-request `correlationId`, plus `errors` and `fieldErrors`.
   `GlobalExceptionHandler` is pinned at `HIGHEST_PRECEDENCE` and guarded by
   `GlobalExceptionHandlerAdviceOrderTest` because losing this contract *"degrades every 400 to a
   generic 'some fields are invalid' toast with nothing in the server log either"*
   ([ADR-0132](adr/0132-global-exception-handler-outranks-springs-problem-details-advice.md)). gRPC has
   17 status codes and no problem-detail standard. Every one of the eight sanctioned problem producers
   — including the ones that run *before* the DispatcherServlet — would be re-implemented over trailer
   metadata. **Regression: certain, in error fidelity and in `fieldErrors`-driven inline form errors.**
2. **HTTP cache semantics vanish.** `ApiCacheControlFilter` writes `private, no-store` on the families
   whose bodies must never be written down anywhere (`REQ-SEC-031`), explicitly because *"a public API
   vhost makes proxies, corporate middleboxes and browser disk caches plausible, and the header is the
   only thing that tells them no"*. gRPC has no `Cache-Control`. **Regression: a security control is
   deleted, not replaced.**
3. **The edge allow-list becomes hand-written nginx.** [ADR-0135](adr/0135-public-api-vhost-not-a-gateway.md)
   rests on a default-deny path allow-list at nginx-proxy-manager, and already flags that this list
   *"lives in the NPM admin database, which is not version-controlled and cannot be reviewed in a PR"*.
   gRPC needs `grpc_pass` location blocks; NPM's UI exposes no gRPC mode, so they would go into that
   same unreviewable store as hand-written advanced config *(assumption: NPM has not gained native gRPC
   support — verify before relying on this point)*. **Regression: the most exposed access-control
   surface gets harder to review.**
4. **Per-IP rate limiting loses its input.** `REQ-SEC-011` and `ClientIpContextFilter` were built
   because *"behind nginx-proxy-manager the immediate peer is NPM's docker-internal IP — every client
   looks the same to the rate-limit filter and the global bucket collapses into a single org-wide
   budget"*. The fix is `X-Forwarded-For` handling ordered ahead of `ForwardedHeaderFilter`. `grpc_pass`
   would need that relay re-established and re-verified, and getting it wrong reproduces exactly the
   collapse the requirement exists to prevent. **Regression risk: high, and silent when it happens.**
5. **Observability has to be rebuilt.** `HttpLatencyP95High`, `AppFanoutRatioHigh`, the RED panels in
   `03-spring-apps.json` and `14-tracing.json`, and the `blackbox-http` / `blackbox-http-auth` probe
   jobs are all keyed on HTTP series and HTTP probes. `CLAUDE.md` makes monitoring parity a
   same-PR obligation. **Regression: guaranteed during the transition, by construction.**
6. **Optimistic locking gets a new silent-failure mode.** §4.2 point 2: a proto3 `int64 version` without
   `optional` decodes an absent value as `0`. The concurrency section of `CLAUDE.md` warns against
   stripping the version *"to make it simpler"*; this would not strip it, it would make it lie.
   **Regression risk: lost updates, the failure class this codebase has already been bitten by.**
7. **Money precision becomes a choice instead of a type.** §4.2 point 1. In an append-only
   double-entry ledger. **Not acceptable.**
8. **Input validation moves from declarative to hand-written.** §4.2 point 4. ~870 constraints,
   no mechanical translation, each mistranslation an input-validation hole.
9. **DPoP's `htm`/`htu` binding is defined over HTTP.** Seam 6. **Regression: a working
   sender-constraint would have to be re-derived.**
10. **A shipped client could break in the field.** The Android APK and the extractor MSI cannot be
    redeployed with the server; [ADR-0136](adr/0136-external-contract-set-for-shipped-clients.md) exists
    for that reason. Its own text says the min-version gate *"does not exist yet"* — **that sentence is
    dated**: `REQ-API-010` closed it on 2026-08-24 (`GET /api/v1/app/version-policy`; the app's
    `UpdateGate` refuses to run below the floor), and `docs/specs/api-conventions.md` records the
    closure. The floor is `0` today, so it gates nothing *yet*. **So a protocol change on seams 5–6 is
    not impossible — it is a staged migration behind a floor that locks every un-updated member out
    until they act. That is a price, not a blocker, and §8 buys the same wins without it.**

Items 1, 2, 7 and 8 are not mitigable by careful engineering. They are consequences of the format.

## 8. What to do instead — ranked by value per unit of risk

Everything below preserves every contract in §7, needs no client change, and is individually
revertable.

> [!important] §8.1 to §8.5 were implemented on 2026-09-10, in this pull request
> The section was written as five proposals and is kept in that voice, because the reasoning is
> what makes each one reviewable. What changed is that each now ends in an **Outcome** paragraph
> saying what shipping it actually proved — and three of the five proposals turned out to be
> wrong about something.
>
> The three, in order of how badly they would have misled someone who trusted them:
>
> 1. **§8.1's sketch would have negotiated HTTP/2 and saved nothing.** Reactor Netty's HTTP/2 pool
>    defaults `strictConnectionReuse` to `false`, so it keeps opening one connection per concurrent
>    call. Forty concurrent calls, measured: forty sockets. The flag is the change; the protocol is
>    the prerequisite.
> 2. **§8.1's Tomcat numbers were wrong**, and the correct ones point the other way. The embedded
>    Tomcat is 11.0.25 and its code says `maxConcurrentStreams = 100`, `maxConcurrentStreamExecution
>    = 20` — not 200/200 as the reference page states. The second is never advertised to a client,
>    so an unconfigured H2 pool would have turned a hundred concurrent calls into twenty executing.
> 3. **§8.3 overstated the cost.** Spring already refuses to generate an ETag for a `no-store`
>    response, so those families were being buffered but never hashed. The buffer is real; the MD5
>    was already not being paid.
>
> And a fourth, found after the first implementation shipped rather than while writing it:
>
> 4. **A per-connection read timeout becomes an outage under multiplexing.** §8.1's own "watch this"
>    note predicted the channel-level `ReadTimeoutHandler` would show up as *handshake cost after a
>    lull*. It showed up as `PrematureCloseException` on five E2E write flows, because the connection
>    it closed in idle was the one connection everything was riding. The prediction that it mattered
>    was right; the prediction that it would degrade gradually was not.
> 5. **§8.5's central claim — "same object model, only the bytes change" — is false out of the box.**
>    Jackson writes a `UUID` as sixteen raw bytes on any format that can hold binary, so all 209
>    `string/uuid` properties of the frozen contract stop being strings under CBOR. It reached CI,
>    and the unit test that should have caught it compared two **empty** arrays.
>
> Each is corrected in place below, next to the claim it replaces, rather than only here.

### 8.1 Enable HTTP/2 on the frontend→backend hop  ·  *highest value, lowest risk*

**Finding.** Both Spring apps set `server.http2.enabled: true` (`backend/src/main/resources/application.yml:37`,
`frontend/.../application.yml:32`) — but the frontend's outbound client never negotiates it.
`WebClientConfig.connector()` builds its `SslContext` with `SslContextBuilder.forClient()` and **no
`applicationProtocolConfig`**, and the `HttpClient` never calls `.protocol(HttpProtocol.H2)`. Reactor
Netty's default is HTTP/1.1 and it advertises no ALPN protocol, so **the busiest hop in the system runs
HTTP/1.1 against a server that has offered HTTP/2 all along.**

**Why it matters more than protobuf would.** The HTTP/1.1 pool is the documented constraint:
`WebClientConfig` records that *"a single mission-detail render now fans out to four parallel backend
calls, so ~25 concurrent users can exhaust a 50-slot pool"* — hence the raise to 100. Against a target
of **200 concurrent users**, that ceiling is one connection per in-flight call. Under HTTP/2 the calls
become concurrent streams on a handful of connections, which is precisely the pressure `AppFanoutRatioHigh`
watches — captured without touching one DTO, one endpoint or one test contract.

**Sketch** (~15 lines, `frontend/.../config/WebClientConfig.java`):

```java
SslContextBuilder builder = SslContextBuilder.forClient()
    .applicationProtocolConfig(new ApplicationProtocolConfig(
        Protocol.ALPN,
        SelectorFailureBehavior.NO_ADVERTISE,
        SelectedListenerFailureBehavior.ACCEPT,
        ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1));
// …
HttpClient.create(provider).protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
```

**Must be verified before merging — do not take any of these on trust:**

- **Pool semantics change.** Under H2 a `ConnectionProvider`'s `maxConnections` bounds *connections*,
  each multiplexing many streams; `pendingAcquireTimeout` then means something different. The 100/1000
  sizings and the deliberate 5 s alignment with the Resilience4j `TimeLimiter` must be re-derived, not
  carried over.
- **Server-side stream ceiling.** Tomcat's `maxConcurrentStreams` / `maxConcurrentStreamExecution` bound
  per-connection concurrency; the documented default is **200** for both
  ([Tomcat 11 HTTP/2 reference](https://tomcat.apache.org/tomcat-11.0-doc/config/http2.html)) — confirm
  against the exact Tomcat version in the image before sizing anything on it.

  > [!warning] Confirmed, and the reference page is wrong for the version we ship
  > Read out of the bytecode of the embedded Tomcat actually on the classpath, **11.0.25**:
  > `maxConcurrentStreams = 100` and **`maxConcurrentStreamExecution = 20`**. The second is the
  > dangerous one and it is **never sent in the SETTINGS frame** — it is a server-side
  > thread-allocation limit, and past twenty streams `Http2UpgradeHandler` stops dispatching and
  > queues the rest of that connection's work.
  >
  > So a client that let its pool collapse onto one or two connections would have turned a hundred
  > concurrent calls into twenty executing ones and eighty waiting, on a change sold as a
  > modernisation. This paragraph asked for the numbers to be confirmed rather than assumed; they
  > were, and confirming them changed the design.

- **The SSE relay is the risk case.** Long-lived streams multiplexed onto few connections behave
  differently under flow control than one-per-connection. Consider keeping the streaming connector on
  HTTP/1.1 initially — it is a separate connector already, so this is a one-line divergence.
- **Hostname verification and the `backend-trust` bundle** must keep behaving exactly as documented,
  including the deliberate `setEndpointIdentificationAlgorithm("")` on the pinned-trust paths.
- **Load-test both shapes** (mission detail, materials matrix) before and after, and confirm
  `http_client_requests_seconds` p95 does not regress. A "modern transport" that regresses p95 fails the
  brief exactly as a format migration would.

> [!warning] “Disable HTTP/2” is already a known wrong answer here — from the knowledge base
> The vault records six CI cycles lost to exactly that theory: a seed call `403`ed, and the
> investigation went through re-minting tokens, backoff retries and **disabling HTTP/2** before the
> cause turned out to be a seed running as the wrong user. The reason it was so attractive is
> structural and has not changed: `CorrelationIdFilter` sets the userId MDC **after** the security
> chain, so a request logged inside the chain reads `anonymous` even when authenticated.
>
> Once this hop actually speaks HTTP/2, the same transport theory becomes available again and will
> look better than it is. **Read the endpoint's auth gate and the `Granted Authorities` DEBUG line
> first**; reach for the transport only after those are clean.

**Outcome (2026-09-10).** Shipped, and the sketch above was not enough on its own.

The ALPN config and `.protocol(H2, HTTP11)` are exactly right and exactly insufficient: with them
alone, `WebClientHttp2NegotiationTest` — a real TLS handshake against a Reactor Netty HTTP/2 server
using the committed test material — reported `h2` negotiated **and forty distinct sockets for forty
concurrent calls**. Reactor Netty's `Http2AllocationStrategy` defaults `strictConnectionReuse` to
`false`, which means "while a connection permit is available, open a new connection rather than add
a stream to an open one". HTTP/2 at HTTP/1.1's connection count, at HTTP/2's framing cost, with the
pool metrics reading the same as before. **That is the failure mode this whole section was written
to avoid, and it would have looked like a success.**

What shipped is therefore an explicit `Http2AllocationStrategy` — `strictConnectionReuse(true)`,
`maxConcurrentStreams` pinned to `app.http.max-concurrent-streams` (default **20**, mirroring the
Tomcat limit above so the pool opens another connection instead of over-subscribing one), and
`maxConnections(100)` so the aggregate can never be worse than HTTP/1.1 was. The same test now
measures two sockets for those forty calls, and a control case on the SSE client measures more than
eight, so the number means something.

Three further decisions, each narrower than the paragraph that asked for it:

- **The SSE relay stays on HTTP/1.1**, as suggested. Multiplexing a thousand long-lived viewer
  streams onto a handful of connections puts every viewer behind one flow-control window and one
  twenty-stream execution limit, and the ceiling being removed is a ceiling on *concurrent
  requests* — which a stream is not. Asserted, not assumed: the same test drives both clients
  against the same server and reads the protocol off the server's `SslHandler`.
- **The pool sizing is re-derived, not carried over.** Under HTTP/2 `maxConnections` bounds
  connections rather than in-flight calls, so the deliberate 1:1 alignment with the `backendApi`
  bulkhead no longer holds; the bulkhead's `maxConcurrentCalls: 100` is now the only gate on
  concurrency, and it is unchanged. `pendingAcquireTimeout` keeps its 5 s alignment with the
  `TimeLimiter` and simply stops mattering as often.
- **`app.http.backend-protocol=HTTP11` puts it back**, byte for byte, without a redeploy — which is
  what makes the load test this section asks for something that can safely be run on production.

> [!tip] Confirmed against the real stack, not only against a test double
> The unit-level test drives the production connector against a Reactor Netty server, which leaves
> one thing unproven: whether **Tomcat** offers `h2` by ALPN with the keystore this project actually
> serves. It does. Measured on the local test stack (`docker-compose.test.yml`, committed test TLS,
> images built from this branch):
>
> |                    Probe                     |                                  Result                                   |
> |----------------------------------------------|---------------------------------------------------------------------------|
> | `curl --http2` → backend                     | `http_version=2`, `200`                                                   |
> | `Accept: application/cbor`                   | `200`, `content-type: application/cbor`, 117 B vs 125 B as JSON           |
> | Response headers                             | `vary: Accept`, `vary: Accept-Encoding`, an `ETag` on a revalidate family |
> | 120 concurrent page loads, HTTP/2            | **2** established frontend→backend connections                            |
> | The same, `APP_HTTP_BACKEND_PROTOCOL=HTTP11` | **7**                                                                     |
>
> The last two are the same control the unit test runs, one layer up, and the flag demonstrably
> works from the container's environment — which is what the load test below depends on.

**Still owed:** the load test itself — p95 on the two shapes, under production-like load. The table
above shows the connection count moved in the intended direction; it says nothing about latency,
which is the number the brief actually constrains.

> [!bug] That question had an answer, and the guess about its shape was wrong
> The paragraph here used to flag the channel-level `ReadTimeoutHandler` as something to *watch*
> during the load test, and predicted it would surface as **TLS handshake cost after a lull rather
> than as an error**. It surfaced as an error, and the E2E suite found it before any load test could.
>
> The mechanism is the same collapse that makes HTTP/2 worth having. A channel-level read timeout
> bounds silence on a **connection**. Under HTTP/1.1 that fired on an idle pooled connection and
> cost one spare out of a hundred. Under HTTP/2 with strict connection reuse, one or two connections
> carry everything and are idle between bursts *by design* — so the 3 s timeout closed the
> connection the application was riding, and whatever was in flight died with
> `PrematureCloseException: Connection prematurely closed BEFORE response`. `maxIdleTime(20s)` never
> got a say, because 3 s comes first.
>
> **169 such log lines and five failed E2E write flows**, on three browsers, deterministically. And
> it did not look like a transport problem from the outside: `krtFetch` falls back to a full page
> reload when a call fails, so the symptom was *"the page reloaded"* on five unrelated screens, each
> failing its `window.__krtNoReload` assertion. None of the 169 lines carried a correlation id —
> which is the tell that the timeout fired with **no request in flight**.
>
> **Fixed by not arming it under HTTP/2.** The per-request bound is unaffected: `responseTimeout`
> adds a read timeout when the request is sent and removes it when the response completes, which is
> the HTTP/2-correct unit, and the `backendApi` TimeLimiter closes the outer bound at 5 s. The
> *write* timeout is kept, because it arms per write promise rather than on idle. Pinned by
> `WebClientHttp2IdleConnectionTest`, which asserts that the **same socket** serves a call before and
> after an idle window longer than the read timeout — not merely that the second call succeeds,
> because a silently replaced connection would satisfy that and leave the race intact. Verified by
> restoring the old behaviour: two peer addresses instead of one.
>
> **The lesson is about the shape of the guess, not the guess itself.** Flagging the handler was
> right. Predicting it would degrade *gradually*, as cost, was the error — a connection that carries
> everything does not degrade when it dies, it fails. Under multiplexing, "one of many" resources
> quietly becomes "the one", and every per-connection bound has to be re-read in that light.

### 8.2 Generate the frontend's mirror DTOs from `openapi.json`  ·  *the maintainability win, without the protocol*

261 hand-maintained mirror DTOs and four contract tests over a 1.87 MB spec exist to detect drift that a
generator makes impossible. The generator is **already in the workspace**: `basetool-android`'s
`core/contract` runs `openapi-generator 7.25.0` against this exact file.

Doing the same in `frontend` turns drift from a test failure into a compile failure — gRPC's headline
maintainability benefit, at a fraction of the cost and with zero wire change. Caveats to work through:
the generated types must satisfy Checkstyle/Javadoc gates or be excluded from them; `PageResponse`
generics and the Jackson 2 → 3 migration in flight (96 `com.fasterxml.jackson` vs 45 `tools.jackson`
imports in the backend) both need a decision; and the four contract tests should be *kept* initially,
proving the generator agrees with them before anything is deleted.

**Outcome (2026-09-10).** The generator is wired and its output is checked against the mirrors;
nothing in `main` imports a generated type yet.

`openapi-generator 7.25.0` — the same version the app's `core/contract` runs, deliberately — emits
**411 Java models** from this document, and they compile. They land in the **test** source set: in
`main` they would ship 261 unused classes in the jar and put generated code under Checkstyle's
Javadoc gate, which is the caveat above answered by placement rather than by configuration. Two
traps were worth the hour they cost: models-only generation still imports an `ApiClient` it never
emits, for a `toUrlQueryString()` helper nothing calls (`supportUrlQuery=false` removes both), and
the generator takes its spec as a URI **string**, so Gradle never sees the file and the task's cache
key would not have included the contract at all.

`GeneratedDtoAgreementTest` then compares the two sets field by field. It needs two lists, and both
are written down rather than inferred: **sixteen aliases**, where a mirror and its schema simply
carry different names (`PromotionTopicDto` against `PromotionTopicResponse`, and fourteen more —
each established by an identical property set, not by the name), and **ten frontend-only types** that
mirror nothing at all. Without the alias list, fifteen real mirrors would have been skipped as
"frontend-only" and the guard would have covered the easy 94 % while missing promotion and personal
inventory, the two youngest families.

> [!bug] The first run found two live instances of the drift this item was proposed to prevent
> Neither is fixed here — each needs a decision belonging to the area it touches, not to the change
> that introduced the guard — and both are frozen in `KNOWN_DRIFT` with the date and the reason, so
> anything **new** fails.
>
> - **`PromotionTopicDto` is missing `owningSquadron`.** The backend sends it; the mirror does not
>   declare it; Spring Boot disables `FAIL_ON_UNKNOWN_PROPERTIES`, so it is dropped in silence and
>   the promotion UI cannot render a topic's owning squadron even though the data arrives. This is
>   the failure mode in its pure form.
> - **`RefineryOrderListDto` is missing `endsAt`** — and *recomputes* it, in `getEndsAt()`, as
>   `startedAt + durationMinutes`. Not a dropped field so much as a second implementation of one,
>   which would diverge silently the day the server's answer stops being that sum.

The swap itself stays an epic, and the reason is now concrete rather than estimated: the generator
emits classes with getters where the mirrors are records, `List` where they use `Set`, and
`OffsetDateTime` where they use `Instant`. Every accessor call site moves, and the Javadoc that
explains *why* a field exists — `MissionDto`'s slimming note is the clearest example — is not
reproducible from a schema.

### 8.3 Stop paying for an ETag nobody uses  ·  *a live, unnoticed cost*

**Finding.** `StreamAwareShallowEtagHeaderFilter` extends `ShallowEtagHeaderFilter`, which **buffers the
entire response in memory and MD5-hashes it** to compute a shallow ETag. It exempts exactly two paths
(`/api/v1/notifications/stream`, `/api/v1/live-sync/stream`). Every other `/api/**` response is buffered
and hashed — including the materials matrix that *"tipped the buffer"* at 16 MB.

> [!note] Half of that sentence is wrong, and the half that survives is the expensive half
> Spring's `isEligibleForEtag` returns `false` as soon as the response carries `Cache-Control:
> no-store`, and `ApiCacheControlFilter` sets exactly that on fourteen path families from
> `HIGHEST_PRECEDENCE + 20` — ahead of this filter's write-back. So those families were **buffered
> but never hashed**: the MD5 was already not being paid, and `HttpCachingTest` had said so since
> REQ-SEC-031 (*"deliberately emits no ETag for them at all"*).
>
> The buffer is the real cost and it is unconditional: `ConditionalContentCachingResponseWrapper`
> wraps the response on the way out and `copyBodyToResponse()` copies it back, in full, in memory,
> for a header the framework has already decided not to emit.

**No first-party client can ever benefit.** `BackendApiClient` never sends `If-None-Match` (the only
conditional GETs in the repo are the *outbound* `UexClient` / `ScWikiClient` integrations against
third-party APIs). The Android client installs **no HTTP cache, deliberately** (`KrtHttpClient`:
*"none is asked for deliberately (security concept §4)"*). And `ApiCacheControlFilter` states outright
that on the `no-store` families *"nothing relies on conditional requests"* — while the ETag filter
buffers and hashes them anyway.

So every `/api/**` response pays a full buffer plus an MD5 for a revalidation that cannot happen — on
the **backend's** CPU, which is where that filter runs.

> [!warning] There are **two** ETag filters, and only one of them is inert
> An earlier draft of this section charged the cost to the frontend's CPU, calling it the worst
> throttle in the stack. That conflates two filters. `StreamAwareShallowEtagHeaderFilter` is a
> **backend** bean on `/api/**`; the frontend has its own `EtagConfig`, which registers Spring's
> plain `ShallowEtagHeaderFilter` on **`/*`** at `HIGHEST_PRECEDENCE + 10`.
>
> The distinction decides what may be touched. On the frontend's filter the client is a **browser**,
> browsers do send `If-None-Match`, and those 304s are real — **removing it would be a regression, not
> a saving.** Only the backend's is provably inert, because its only callers are `BackendApiClient`
> and the Android client, and neither sends a conditional request.
>
> The irony is that the frontend *is* the throttled container (1 506 s / 7 d, ADR-0085) and it *does*
> buffer every response including HTML — so if buffering cost is ever measured, measure it there too.
> But that is a separate question with the opposite answer, and it is why this section names the bean
> rather than saying "the ETag filter".

**Options, in order of preference:**

1. Measure it first (`http_server_requests` before/after on the matrix and a large list) — the fix is
   only worth shipping if the cost is real.
2. Extend `StreamAwareShallowEtagHeaderFilter#shouldNotFilter` — the **backend** bean, not the
   frontend's — to skip the `no-store` families and the known-large catalogue paths, where the ETag is
   *provably* inert. Cheapest, most defensible.
3. Or make the value real: have the Android client store the ETag next to its own read cache and send
   `If-None-Match` on catalogue reads. Genuinely valuable on mobile data — but it stores server metadata
   on the device and therefore needs the privacy-gate review, not a drive-by.

Keep `HttpCachingTest` green either way; it encodes the intended behaviour, including that a fabricated
`If-None-Match` must never short-circuit authorization.

**Outcome (2026-09-10).** Option 2, scoped to the `no-store` families and nothing else.

The correction above is what makes this the safe option rather than merely the cheap one: on those
fourteen families **no response header changes at all**, because there was no ETag to lose. What is
removed is the buffer, and only the buffer. The premise is asserted against Spring itself rather
than read out of its source — `StreamAwareShallowEtagHeaderFilterTest` runs the *plain*
`ShallowEtagHeaderFilter` over a `no-store` response and asserts no ETag appears, so a future Spring
that changed this fails the build here instead of quietly starting to cost 304s.

**The catalogue paths were deliberately not taken**, although they are the larger saving — the
materials matrix revalidates rather than `no-store`s, so it *does* get an ETag. That ETag is inert
only for as long as no client sends `If-None-Match`, which is a fact about today's clients and not
about the response; removing it would quietly foreclose option 3.

The fourteen families now live in one place, `NoStoreApiScopes`, read by both filters. A copied list
would have passed every case and diverged the first time a family was added to one of them —
ADR-0135's argument about a second copy of an authorisation rule, applied to a rule about caching.
The invariant is asserted directly: for seventeen paths, `no-store` written by one filter must mean
"skip the buffer" in the other, in both directions.

One side effect worth recording rather than discovering later: eleven of the fourteen families are
`/**` patterns, which match a literal `.` segment, where the two streaming exemptions are **exact**
patterns and do not. So `/api/v1/notifications/./stream` is now recognised and
`/api/v1/live-sync/./stream` still is not. The asymmetry is pinned by a test so that whoever
reconciles the two lists knows it is there.

**Still owed:** the measurement in option 1. Narrowing was chosen over measuring because this
particular narrowing is header-neutral by construction, which makes it the rare case where shipping
first costs nothing — but how much CPU and heap it returns is still unknown.

### 8.4 Take protobuf's *evolution discipline* without protobuf  ·  *closes an admitted gap*

[ADR-0136](adr/0136-external-contract-set-for-shipped-clients.md) already names its own weakness:
`ExternalContractTest` *"does not compare types, nullability or enum values. A field that turns from
string to object, or an enum that loses a constant, passes it and still breaks an old build"* — and it
names the answer: *"a schema diff of the contract subset against the previous release tag"*.

That is the guarantee protobuf field numbers would have provided, obtainable by diffing the committed
`openapi.json` in CI. It protects the two clients that genuinely cannot be redeployed, and it is a test,
not a migration.

**This is not a new proposal.** It is `REQ-API-009`'s own open acceptance box, verbatim:
*"Type and nullability changes are caught. **Open** — needs a schema diff of the contract"*
(`docs/specs/api-conventions.md`). Whoever ships it ticks that box; it should not become a second
entry for one piece of work.

**Outcome (2026-09-10).** Both halves shipped, and the box is ticked.

The comparison covers **1,479 properties across 253 schemas**, reached from the 226 contract
operations by exactly `walkSchema`'s traversal — transitive and cycle-guarded, because a client
parses the whole payload and a type four levels down inside a participant's job type breaks it as
surely as one on the root object. Each entry is one line: the JSON type, the format, and whether the
schema lists the property as required.

> [!important] "Nullability" in this document means `required`, and nothing else
> `openapi.json` carries **no** `nullable` keyword and **no** `["string","null"]` type union —
> springdoc emits neither at OpenAPI 3.1. Checked: zero occurrences of either across 1.87 MB. So a
> schema's `required` list is the entire nullability signal the contract has, and freezing
> membership of it is what turns "a field stopped being required" into a build failure rather than
> a null on a member's phone.

Two guards, because they fail on different things:

- **`theContractTypesAndNullabilityAreFrozen`** compares against
  `backend/src/test/resources/api/frozen-contract-types.txt`, a committed record of what somebody
  wrote down and reviewed. It runs everywhere, including on a developer machine. Its weakness is
  that a pull request can edit the record and the document together, which is what a careless "make
  the build pass" looks like. A data file rather than the `Map.ofEntries` literal the enum guard
  uses: at 1,479 entries the literal would be longer than the test around it, and a contract change
  is far easier to review as a sorted data diff.
- **`theContractTypesMatchThePreviousRelease`** compares against the previous release tag's own
  `openapi.json`, which no pull request can edit. This is ADR-0136's wording taken literally. Its
  weakness is the mirror image — it needs a baseline, so it **skips** when there is none. CI fetches
  the tag (`--depth=1`, one tag, not the history) and passes `-Dcontract.baseline`; every failure
  mode of that step is non-fatal, because a guard that broke CI over a missing baseline would be
  removed within a week.

Both were verified by breaking them: flipping one property from optional to required in the frozen
record fails the first with the exact field named, and the second runs green against `v1.7.7`'s
document — confirming, incidentally, that nothing in the contract has changed shape since that
release.

### 8.5 Optional, measure-first: a binary JSON codec on seam 4

If §8.1 and §8.3 land and frontend CPU is *still* the constraint, the cheap next step is **not** gRPC but
a Jackson binary backend (CBOR or Smile) negotiated by `Content-Type` on seam 4 only. Same object model,
same records, **same ~870 Bean Validation annotations**, same RFC 7807 handling, same filters, same
metrics — only the bytes change, and content negotiation makes it revertable per endpoint.

This is the honest "modern binary format" option for this codebase: it collects most of the
serialization win and none of the §7 regressions. It should still be gated on a measurement showing
serialization is a real share of the cost, which no evidence available here establishes.

**Outcome (2026-09-10).** CBOR shipped on seam 4, behind `app.http.codec`, and the implementation is
one dependency.

Spring Framework 7 detects `tools.jackson.dataformat.cbor.CBORMapper` on the classpath and registers
both the servlet converter and the reactive codecs itself, so adding
`tools.jackson.dataformat:jackson-dataformat-cbor` to both modules *is* the wiring. The frontend then
asks for `application/cbor, application/json`, in that order, and everything else follows from
content negotiation. CBOR rather than Smile because it is an IANA-registered media type (RFC 8949)
that an outside reader recognises, where Smile is Jackson's own.

Four things had to be shown not to move, and each is asserted rather than reasoned about:

- **A JSON caller is completely unaffected.** The Android app and the extractor send `Accept:
  application/json` and are shipped builds that cannot be redeployed with the server. Negotiation is
  what keeps this invisible to them.
- **RFC 7807 problems stay JSON even under a CBOR `Accept`.** `GlobalExceptionHandler` presets
  `application/problem+json`, and Spring skips negotiation entirely for a preset concrete content
  type. Had that not held, the frontend would have stopped reading the stable machine-readable
  `code` that `krt-fetch.js` routes reload-vs-toast on — a transport change surfacing as a UI bug.
- **Request bodies stay JSON.** Spring registers the JSON encoder ahead of the CBOR one, so
  `bodyValue` keeps writing JSON without being told to. That is a property of a framework ordering,
  not of anything in this repository, so it is pinned by a test: a write path that silently turned
  binary would reach every `consumes = APPLICATION_JSON_VALUE` endpoint as a 415.
- **`openapi.json` does not change**, and therefore neither do the Android models. springdoc already
  emits `*/*` for these responses; verified by regenerating and diffing.

> [!warning] One bug this would have introduced, caught before it shipped
> `/api/**` now has **two representations at one URL**, and `ApiCacheControlFilter` named only
> `Accept-Encoding` in `Vary`. On the `no-cache, must-revalidate` families — which an intermediary
> is explicitly permitted to store — a cache keyed on the URL alone could hand a CBOR body to a
> JSON client, which is a parse failure on a client that did nothing wrong. `Vary: Accept` now goes
> out alongside it.
>
> [!bug] "Only the bytes change" was the claim, and it was wrong — by 209 properties
> Jackson's `UUIDSerializer` asks the generator `canWriteBinaryNatively()` and writes the **sixteen
> raw bytes** when the answer is yes. JSON answers no and emits
> `"00000000-0000-0000-0000-000000000001"`. CBOR answers yes. Anything that then treats the value as
> text renders `AAAAAAAAAAAAAAAAAAAAAQ==` — base64 of those bytes — and that is what appeared in a
> `<select>` value, in picker option ids and in table row keys, failing five end-to-end write flows
> on all three browsers.
>
> **Every unit test stayed green, and the one that should not have was vacuous.** The negotiation
> test compares the decoded trees of `/api/v1/job-types`, whose `JobTypeDto` does carry a
> `string/uuid` — but that list is empty in the test context, so it compared two empty arrays and
> passed. A document comparison proves nothing about a document with nothing in it. It now aborts
> loudly instead, and the real guarantee moved to `CborJsonFidelityTest`, which encodes one value
> carrying every type whose wire form could diverge and needs no seeded data at all.
>
> **Fixed at the encoder** (`CborFidelityConfig`), because 209 frozen contract properties are
> `type: string, format: uuid` and a representation that silently stops being a string on one
> encoding is precisely the in-place shape change REQ-API-009 exists to forbid. A UUID costs 36
> bytes as text against 16 as binary; paying those 20 is what keeps one contract instead of two.
>
> One further divergence is **recorded rather than fixed**: a `BigDecimal` comes back as a decimal
> node from CBOR and as a double from JSON — same value, different scale. That is CBOR being *more*
> faithful, and every consumer binds to a declared type rather than reading the tree. Stated in the
> test so the next reader meets it as a known difference.

Deliberately **not** done: `application/cbor` is absent from `server.compression.mime-types`, so a
CBOR response is not gzipped where the JSON one was. That is the trade §8.5 is about — bytes for CPU
on an internal Docker hop where ADR-0085 shows bandwidth is not the constraint — but it means a
measurement has to watch both, and a payload-size regression is the plausible way this could turn
out to be a bad idea.

**Still owed:** the measurement this section makes a precondition. Both of its prerequisites (§8.1
and §8.3) now exist, so the profiling it asks for is finally possible; `app.http.codec=JSON` is the
way back while it is pending.

### 8.6 Explicitly *not* recommended

- **zstd instead of gzip** — a genuine ratio/CPU improvement, but it is a change at the same layer as
  §8.1 for a fraction of the benefit, and on an internal hop where bandwidth is not scarce. Revisit only
  if payload bytes ever become the measured constraint.
- **GraphQL for the read paths** — it would address over-fetching (the 14 page-walked catalogues) but
  adds a second authorization surface next to `OwnerScopeService`'s scope triple, per-`sub` isolation and
  guest field redaction. [ADR-0135](adr/0135-public-api-vhost-not-a-gateway.md) already rejected a second
  copy of authorization rules — *"a second copy of an authorisation rule is a divergence waiting to
  happen"* — and that reasoning transfers unchanged. Server-side filtering (ADR-0100, ADR-0105) is the
  cheaper answer to over-fetching and is already the project's direction.

## 9. When this answer would flip

The recommendation is conditional on measured facts, so here is what would change it:

|                                           Trigger                                            |                Then reconsider                |
|----------------------------------------------------------------------------------------------|-----------------------------------------------|
| The backend is split into services that talk to each other across a real network             | gRPC for **service-to-service only**          |
| A third or fourth non-browser client appears, each hand-writing DTOs                         | schema-first codegen (not necessarily gRPC)   |
| Profiling shows JSON serialization is a **dominant** share of frontend CPU *after* §8.1/§8.3 | §8.5 first, gRPC only if that is insufficient |
| A genuinely high-frequency streaming need appears that is **not** browser-facing             | gRPC streaming for that one stream            |
| The deployment moves off one host and hops become real network hops                          | the whole §3 premise must be re-measured      |

Note what is **not** on that list: user growth. At 200 concurrent users on an 8-vCPU host averaging
3.2 %, growth alone will not make the transport the bottleneck.

## 10. Open questions for the owner — answered 2026-09-10

All four were put to @greluc and all four were decided. Kept with their answers rather than deleted,
because the answer to the first one is the reason the rest of this document reads the way it does.

1. **§8.1 (HTTP/2 on seam 4)** — proceed? It touches TLS/ALPN and pool sizing on the busiest hop, so it
   wants its own PR, a load test and probably its own ADR.
   → **Proceed, in this PR, behind a flag defaulting to on**, with the verification moved from
   production to the test environment. That is what surfaced the `strictConnectionReuse` default and
   the real Tomcat limits; on production, both would have shown up as "no improvement" with no
   indication why. The load test is still owed.
2. **§8.3 (ETag)** — measure first, or narrow `shouldNotFilter` straight away? The narrowing is small and
   provably inert on the `no-store` families.
   → **Narrow, and only the `no-store` families.** The large catalogues stay filtered: their ETag is
   inert because of what today's clients happen to do, not because of what the response is.
3. **§8.2 (frontend DTO generation)** — worth an epic? It is the largest maintainability win identified,
   and it is orthogonal to everything else here.
   → **Wire the generator and check the mirrors against it now; leave the swap to its own epic.**
   The check found two live drifts on its first run, which is a decent argument that the epic is
   worth scheduling rather than merely worth having.
4. **§8.4 (openapi.json schema diff in CI)** — this closes a gap ADR-0136 documents about itself; should
   it become a requirement under REQ-API-009?
   → **Yes, and both forms of it.** REQ-API-009's last open acceptance box is now ticked; the frozen
   record and the previous-release diff cover each other's blind spot.

### What is still open

Three things, all of them measurements, and none of them obtainable from a repository:

|                                   What                                    | Which section |                            Why it could not be done here                             |
|---------------------------------------------------------------------------|---------------|--------------------------------------------------------------------------------------|
| A before/after load test of mission detail and the materials matrix, p95  | §8.1          | needs production-shaped load; `app.http.backend-protocol=HTTP11` is the way back     |
| `http_server_requests` before/after on the matrix and a large list        | §8.3          | the narrowing is header-neutral, so how much it returns is still unknown             |
| Profiling that says whether serialization is a real share of frontend CPU | §8.5          | its two prerequisites now exist, so this is finally answerable; `codec=JSON` reverts |

Until the third one is answered, §8.5 is a capability rather than a justified change — which is
exactly what its own "measure first" clause says, and the flag is there so that stays true.

