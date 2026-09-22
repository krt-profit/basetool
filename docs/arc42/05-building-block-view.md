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
| **ingest** | Authenticate and relay approved desktop-extractor payloads; stage the returned draft in Redis for a one-time browser pickup | Own a database or save anything itself |
| **keycloak** | Identity, OIDC tokens, the Discord provider and guild/role gate, the KRT theme | Store domain data |
| **db-backend / db-keycloak** | Two separate PostgreSQL instances | Share a cluster — a Keycloak upgrade must not be able to touch domain data |
| **redis** | Spring Session store, the live-sync and notification pub/sub fanout, and the ingest handoffs — one instance on three separate networks | Be a cache of record for anything that matters |

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
| `integration` | Outbound third parties — `UexClient`, `scwiki` |
| `event` | Domain events, including what drives notifications and live sync |
| `metrics` / `health` / `logging` | `basetool_*` business metrics, health indicators, MDC enrichment |
| `filter` / `interceptor` / `annotation` / `validation` / `util` / `web` / `exception` / `config` | The usual Spring surface |

## 5.3 Level 2 — inside `frontend`

| Package | What lives there |
| --- | --- |
| `controller` | Thymeleaf page and fragment endpoints; AJAX mutation endpoints that return fragments |
| `service` | `BackendApiClient` — the single seam — plus view-shaping services |
| `view` / `model` | View models and the hand-mirrored DTO records |
| `websocket` | `/ws/sync`, the handler and the Redis fanout |
| `config` | WebClient, Resilience4j, Redis session, Reactor context propagation |
| `support` / `validation` / `exception` / `health` / `logging` / `metrics` | As on the backend |

**One trap lives here and is worth naming in an architecture document**, because it is invisible
from the code that suffers from it: `WebClient.exchange()` runs on a Reactor-Netty worker thread,
not the servlet thread, so a plain `ThreadLocal` is not visible inside an exchange filter. Anything
that has to cross that boundary — the active-OrgUnit pin, the correlation id — needs a registered
`ThreadLocalAccessor`. Forgetting it does not fail; the value simply arrives empty.

## 5.4 Level 2 — the other modules

- **`ingest`** — a gateway: authentication (DPoP accepted, `REQ-INGEST-012`), the approved-client
  check, rate limiting, payload size limits, a relay to the backend under the gateway's own service
  identity, and the single-use Redis handoff. Ships its own committed `openapi.json`.
  Specification: [`desktop-ingest.md`](../specs/desktop-ingest.md).
- **`keycloak-spi`** — a provider JAR, deliberately free of the application stack: no Spring Boot,
  Java-21 bytecode for the Keycloak JVM, its own Lombok pin, `@JBossLog` rather than `@Slf4j`. It
  holds the Discord identity provider and its mappers, the guild/role gate authenticator, the
  guild-nickname reader, and the backend account checker. Shipped as its own signed artifact
  (ADR-0055).
- **`keycloak-theme/krt-theme`** — not a Gradle module: the `login` and `account` themes in the
  organisation's design, shipped inside the config bundle.
- **`test-support`** — a test-only library, never shipped: endpoint enumeration and the frontend
  page-route inventory behind the backend and frontend anonymous-surface sweeps.

## 5.5 The monitoring plane

Generated from its own compose file (`docker-compose.monitoring.yml`); it reaches the application
through `net-monitoring-scrape` and the exporters' data-store networks, and is reached from outside
only through the edge's `net-proxy-grafana`: **prometheus**,
**grafana**, **loki**, **tempo**, **alertmanager**, **blackbox-exporter**, two
**postgres-exporters** and a **redis-exporter** as containers — and **node-exporter**, **alloy** and
**podman-exporter** as *host* services. §7.3 says why those three are not containers, and what was
deleted instead of being carried across. Rules, dashboards and probes:
[`monitoring/`](../../monitoring/README.md).
