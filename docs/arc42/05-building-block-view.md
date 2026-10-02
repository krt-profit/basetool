# 5. Building block view

## 5.1 Level 1 — the whitebox of the whole system

```
  internet ── :80 / :443 ──► haproxy (host service, TCP only)
                                 │ PROXY protocol v2 → loopback :8080 / :8443
                                 ▼
      ┌──────────────────────────────────────────────────────────────────┐
      │ edge (nginx-unprivileged) — TLS for the four public names        │
      └───┬──────────────────┬────────────────┬──────────────┬─────────┬─┘
          │ profit-base      │ profit-base    │ ingest.*     │ api.*   │ grafana.*
          │ .online          │ .online/auth   │              │         │
          ▼                  ▼                ▼              │         ▼
    ┌──────────┐      ┌─────────────┐    ┌────────┐          │    ┌─────────┐
    │ frontend │      │  keycloak   │    │ ingest │          │    │ grafana │
    └────┬─────┘      │ SPI · theme │    └───┬────┘          │    └─────────┘
         │ WebClient  └──────┬──────┘        │ service       │    + prometheus · loki · tempo
         │ + bearer          │ OIDC · SPI    │ token         │      alertmanager · blackbox
         ▼                   ▼               ▼               ▼      exporters — the monitoring plane
    ┌─────────────────────────────────────────────────────────────────┐
    │ backend                                                         │
    └──────┬──────────────────────────┬───────────────────────────────┘
           ▼                          ▼
    ┌────────────┐             ┌────────────┐              ┌─────────────┐
    │ db-backend │             │   redis    │              │ db-keycloak │
    └────────────┘             └────────────┘              └─────────────┘

    acme (lego): writes edge-certs, answers HTTP-01 from edge-acme-webroot
```

The drawing shows the request path only. Beside it: the frontend and ingest use redis too; the
frontend and backend reach keycloak (OIDC, the admin API) and keycloak's SPI calls the backend back;
keycloak alone owns `db-keycloak`.

The connections run over **eighteen** container networks, each named for what it joins
(`net-proxy-*`, `net-db-*`, `net-redis-*`, `net-backend-*`, `net-edge-ingress`, `net-acme-egress`,
`net-monitoring-core`, `net-monitoring-scrape`, `net-blackbox-v6`). Apart from the two monitoring
networks each joins a small named set — usually one pair, plus a data store's exporter — and a
container reaches exactly the containers it shares a network with. The units are in
[`quadlet/systemd/`](../../quadlet/systemd/); the segment-by-segment reasoning is the knowledge
base's Topology note.

| Building block | Responsibility | Deliberately does **not** |
| --- | --- | --- |
| **haproxy** (host service) | Bind the public `:80`/`:443`, forward bytes to the edge's loopback ports, and state the client address in a PROXY v2 header (ADR-0187) | Terminate TLS or hold a key |
| **edge** (nginx-unprivileged) | TLS termination for four vhosts plus Keycloak under `/auth`, per-vhost rate limits, deny rules, header rewriting, the `X-Forwarded-*` family; trusts the PROXY header only from its own six pinned addresses | Hold any application logic or state |
| **acme** (lego) | Issue and renew the certificates the edge serves | Serve traffic |
| **frontend** | Render the UI, hold session state, drive live update | Talk to PostgreSQL or the Keycloak Admin API; contain business rules |
| **backend** | The whole domain: REST API, persistence, authorisation, scheduled work | Serve HTML; be reachable from the internet except through the `api` vhost |
| **ingest** | Gate, limit and relay the exchange API for approved clients, the SC Extractor included (§5.5); stage a returned draft in Redis for a one-time browser pickup | Own a database or save anything itself |
| **keycloak** | Identity, OIDC tokens, the Discord provider and guild/role gate, the KRT theme, the device-grant consent for exchange clients and the `basetool-exchange` admin extension | Store domain data |
| **db-backend / db-keycloak** | Two separate PostgreSQL instances | Share a cluster — a Keycloak upgrade must not be able to touch domain data |
| **redis** | Spring Session store, the live-sync and notification pub/sub fanout, the ingest handoffs, the exchange registry mirror and the gateway's byte-bounded exchange partition (ADR-0221) — one instance on three separate networks | Be a cache of record for anything that matters |

## 5.2 Level 2 — inside `backend`

Layered, with the direction enforced by ArchUnit rather than by convention:

`controller` → `service` → `repository` → `model`

| Package | What lives there |
| --- | --- |
| `controller` | REST endpoints, `@PreAuthorize`, `@Valid`, RFC 7807 problem responses |
| `service` | Business rules, transactions, **scoping** (`OwnerScopeService`), audit recording |
| `repository` | Spring Data JPA; fetch strategies that keep the no-N+1 rule |
| `model` | JPA entities, `@Version`, the OrgUnit hierarchy |
| `dto` / `mapper` | Records on the boundary, MapStruct between them and entities |
| `support` | Cross-cutting helpers, including the `OptimisticLock` family |
| `task` | Scheduled jobs |
| `integration` | Outbound third parties — `UexClient`, `scwiki` — on the blocking `RestClient` from `config.RestClientConfig` (JDK HTTP client, no WebFlux; ADR-0204), which `KeycloakService` shares |
| `event` | Domain events, including what drives notifications and live sync |
| `metrics` / `health` / `logging` | `basetool_*` business metrics, health indicators, MDC enrichment |
| `filter` / `interceptor` / `annotation` / `validation` / `util` / `web` / `exception` / `config` | The usual Spring surface |

## 5.3 Level 2 — inside `frontend`

| Package | What lives there |
| --- | --- |
| `controller` | Thymeleaf page and fragment endpoints; AJAX mutation endpoints that return fragments; the domain-specific view shaping (`MissionDetailModelBuilder`, `BankDashboardViewAssembler`, …) |
| `service` | `BackendApiClient` and its catalogue cache, `ParallelPageLoader`, the ingest handoff, live-sync presence, Markdown rendering |
| `model` | The hand-mirrored DTO records (`model.dto`) and the form objects (`model.form`) |
| `view` | `MoneyFormat` |
| `websocket` | `/ws/sync`, the handler and the Redis fanout |
| `config` | WebClient, Resilience4j, Redis session, Reactor context propagation, security, the layout model |
| `oss` | The open-source licence report |
| `support` / `validation` / `exception` / `health` / `logging` / `metrics` | As on the backend |

*Corrected 2026-09-29:* this table called `BackendApiClient` "the single seam" (see §4.1), placed
view-shaping services in `service` and view models in `view`; the view shaping lives in
`controller`, and `view` holds one class. The
[domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) (§5.9) proposes the per-domain
package tree that replaces this layout.

**One trap lives here and is worth naming in an architecture document**, because it is invisible
from the code that suffers from it: `WebClient.exchange()` runs on a Reactor-Netty worker thread,
not the servlet thread, so a plain `ThreadLocal` is not visible inside an exchange filter. Anything
that has to cross that boundary — the active-OrgUnit pin, the correlation id — needs a registered
`ThreadLocalAccessor`. Forgetting it does not fail; the value simply arrives empty.

## 5.4 Level 2 — the other modules

- **`ingest`** — a gateway in front of the exchange API: DPoP authentication (`REQ-INGEST-012`,
  REQ-XCH-006), the registry gate, per-IP and per-client limits, payload size limits, idempotency,
  a relay to the backend under the gateway's own service identity, and the single-use Redis handoff
  (§5.5). Its contract is the committed `exchange-v1.openapi.json`; it has no springdoc and no
  generated document since the extractor's `/v1` routes were removed on 2026-09-28. Its two
  outbound calls — the relay and its own token grant — are blocking `RestClient`s on the JDK HTTP
  client (`config.RestClientConfig`, ADR-0204); the module has no WebFlux and no Reactor Netty, so
  the worker-thread trap of §5.3 does not exist there. Specification:
  [`desktop-ingest.md`](../specs/desktop-ingest.md).
- **`keycloak-spi`** — a provider JAR, deliberately free of the application stack: no Spring Boot,
  Java-21 bytecode for the Keycloak JVM, its own Lombok pin, `@JBossLog` rather than `@Slf4j`. It
  holds the Discord identity provider and its mappers, the guild/role gate authenticator, the
  guild-nickname reader, the backend account checker, the `basetool-exchange` admin extension
  that ends one client inside a member's shared sessions (ADR-0226, REQ-XCH-008), and the
  `krt-freemarker` login forms provider that hands a device login's user code to the consent page
  (ADR-0228, REQ-XCH-005); every Discord call
  goes through one shared HTTP client, and a first login reads the guild-member object once. Analysed by SpotBugs +
  FindSecBugs and held to its own JaCoCo floor like the applications (since 2026-09-22;
  `build-settings.properties`, REQ-OPS-037). Shipped as its
  own signed artifact (ADR-0055).
- **`keycloak-theme/krt-theme`** — not a Gradle module: the `login` and `account` themes in the
  organisation's design, shipped inside the config bundle.
- **`logging-support`** — a plain library, **shipped** inside the three application JARs: the
  one `LogSafe` and the PII maskers every `logback-spring.xml` names (ADR-0205). Closed to domain
  meaning — no DTO, no validation rule, no bean — so it cannot become the shared-code module the
  frontend/backend split deliberately avoids.
- **`test-support`** — a test-only library, never shipped: endpoint enumeration and the frontend
  page-route inventory behind the backend and frontend anonymous-surface sweeps, and behind ingest's
  `IngestEndpointSurfaceTest`, which pins the gateway's routed surface to the exchange route table
  and fails on any mapping under `/v1`; and `ContextShape`, the bean count each application's
  `ContextShapeTest` ratchets (REQ-OPS-038).

## 5.5 Level 2 — the external client exchange

Four modules share it (ingest, backend, frontend, keycloak-spi); the contract, the routes and
every rule are in [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-*`), the
decisions in ADR-0216 … ADR-0221 and ADR-0224 … ADR-0228.

| Where | Building block | Responsibility |
| --- | --- | --- |
| ingest | `ExchangeTokenGateFilter` | DPoP-bound token with a proof and a server nonce, the `basetool-ingest` audience checked in code |
| ingest | `ExchangeGateFilter` (`ExchangeRoutes`, `ExchangeRegistryReader`, `ExchangeRevocationReader`) | Deny-by-default route table; global switch, client status, revocations (read uncached), route scope ∩ registry grant, minimum client version; fail-closed on an unreadable mirror |
| ingest | `ExchangeLimitFilter`, `ExchangeQuotas`, `ExchangeBudget` | Per-minute buckets in-process, the daily write quota and the hard byte budget in Redis (`ingest:xch:*`) |
| ingest | `ExchangeIdempotencyFilter` | `Idempotency-Key` on every write, answers cached per client, member and key |
| ingest | `web.ExchangeController`, `ExchangeSchemas`, `ExchangeRelay` | Schema check of request and answer, relay under the gateway's service identity naming member, client, capabilities and installation; staging of drafts and of a held mass change (`HandoffKind.MASS_CHANGE`) for the browser |
| backend | Registry (`ExchangeRegistryService`, `AdminExchangeRegistryController`, `ExchangeRegistryMirrorSync`, `ExchangeRegistryReconcileTask`) | `exchange_client` and the switch, `ADMIN` only; the Redis mirror written restrictive-first, reconciled every 60 s |
| backend | `config.ActingMemberFilter`, `ExchangeGate`, `ExchangeInstallationService`, `ExchangeConnectionRetentionTask` | The acting member's reduced authentication, `@exchangeGate` re-checking every capability, installations, revocations and departures; disconnected installations and revocations deleted after 90 days (REQ-XCH-035) |
| backend | Change feed (`V252`, `ChangeSourceTransactionManager`, `ExchangeFeedReader`) | Trigger-written key log with the writer, tombstones, cursors, the 90-day retention (ADR-0224) |
| backend | Journal (`V253`, `ExchangeJournalService`) | Every written entry before and after, 90 days |
| backend | Write services for blueprints, stock and ships (ship links `V254`) | Plan a change set, ask `ExchangeMassChangeGuard`, write, journal each entry; `ExchangeLiveSync` after commit. Ships and blueprints are written through the hangar's and the blueprint domain's own services; stock takes the Lager's lot locks through `InventoryItemRepository` and books out through the Lager's book-out, but books in by creating the `InventoryItem` row and its audit event itself |
| backend | `ExchangeUndoService`, `ExchangeMassChangeService` | The member's undo, and the confirmation of a held mass change |
| backend | Bulk undo (`ExchangeBulkUndoService`, `ExchangeBulkUndoRunner`, `ExchangeBulkUndoStep`, `AdminExchangeBulkUndoController`, `V256`) | An admin's undo of one client for every member: suspends the client, then one member per transaction on the single-thread `exchangeBulkUndoExecutor`; runs and skipped entries kept 90 days (ADR-0227) |
| backend | `ExchangeResolveService`, `ExchangeDemandService`, `ExchangeDraftService` | Catalogue resolve through the web import's matching, the anonymised org demand, review drafts |
| frontend | „Verbundene Anwendungen" (`/connected-apps`, `/connected-apps/confirm`) | The member's clients, installations and activity; disconnect, undo, confirm a mass change — over `/api/v1/connected-apps`, member session only |
| frontend | Admin „Verbundene Anwendungen" (`/admin/exchange-clients`) | The registry and the global switch; a client's undo for every member with its runs |
| keycloak-spi | `ExchangeClientSessionResourceProviderFactory` (`basetool-exchange`) | Ends one client inside a member's shared user sessions when the member disconnects it (ADR-0226, REQ-XCH-008) |
| keycloak-spi | `DeviceConsentLoginFormsProviderFactory` (`krt-freemarker`) | Hands a device login's user code to the consent page, beside the phishing warning (ADR-0228, REQ-XCH-005) |

*Corrected 2026-09-29:* the write-services row said every exchange write goes "through the domain's
own services". The stock book-in does not: it is a second write path into the Lager, which the
[domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) (§5.3, §7.5) routes through the
inventory module's command API.

## 5.6 The monitoring plane

Generated from its own compose file (`docker-compose.monitoring.yml`); it reaches the application
through `net-monitoring-scrape` and the exporters' data-store networks, and is reached from outside
only through the edge's `net-proxy-grafana`: **prometheus**,
**grafana**, **loki**, **tempo**, **alertmanager**, **blackbox-exporter**, two
**postgres-exporters** and a **redis-exporter** as containers — and **node-exporter**, **alloy** and
**podman-exporter** as *host* services. §7.3 says why those three are not containers, and what was
deleted instead of being carried across. Rules, dashboards and probes:
[`monitoring/`](../../monitoring/README.md).
