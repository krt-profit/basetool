# 5. Building block view

## 5.1 Level 1 — the whitebox of the whole system

```
                                    ┌───────────────────────────┐
  internet ──── 80/443 ────────────►│  edge  (nginx-unprivileged)│
                                    └───┬────┬────┬─────────┬────┘
                                        │    │    │         │
                     profit-base.online │    │    │         │ grafana.*
                                        ▼    │    │         ▼
                                  ┌──────────┴─┐  │   ┌──────────┐
                                  │  frontend  │  │   │ grafana  │   ── monitoring plane ──
                                  │(Thymeleaf) │  │   └──────────┘
                                  └─────┬──────┘  │   prometheus · loki · tempo
                            WebClient   │         │   alertmanager · blackbox
                            bearer      │         │   postgres-exporter ×2 · redis-exporter
                                        ▼         │
         ingest.*  ┌──────────┐   ┌───────────┐   │ api.*
         ─────────►│  ingest  │──►│  backend  │◄──┘
                   └──────────┘   └──┬─────┬──┘
                                     │     │
                             ┌───────▼─┐ ┌─▼──────┐    ┌──────────┐    ┌───────┐
                             │db-backend│ │ redis │    │ keycloak │◄──►│  db-  │
                             │(Postgres)│ │session│    │ + SPI +  │    │keycloak│
                             └──────────┘ │+ pub/ │    │  theme   │    └───────┘
                                          │  sub  │    └──────────┘
                                          └───────┘
                              ┌──────┐
                              │ acme │ ── writes edge-certs, reads edge-acme-webroot
                              └──────┘
```

| Building block | Responsibility | Deliberately does **not** |
| --- | --- | --- |
| **edge** (nginx-unprivileged) | TLS termination for four vhosts, per-vhost rate limits, deny rules, header rewriting, the `X-Forwarded-*` family | Hold any application logic or state |
| **acme** (lego) | Issue and renew the certificates the edge serves | Serve traffic |
| **frontend** | Render the UI, hold session state, drive live update | Talk to PostgreSQL or the Keycloak Admin API; contain business rules |
| **backend** | The whole domain: REST API, persistence, authorisation, scheduled work | Serve HTML; be reachable from the internet except through the `api` vhost |
| **ingest** | Authenticate and relay approved desktop-extractor payloads | Own a database |
| **keycloak** | Identity, OIDC tokens, the Discord provider and guild/role gate, the KRT theme | Store domain data |
| **db-backend / db-keycloak** | Two separate PostgreSQL instances | Share a cluster — a Keycloak upgrade must not be able to touch domain data |
| **redis** | Spring Session store **and** the live-sync pub/sub fanout | Be a cache of record for anything that matters |

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

- **`ingest`** — a gateway: authentication, the approved-client check, rate limiting, payload size
  limits, and a relay to the backend. Ships its own committed `openapi.json`.
- **`keycloak-spi`** — a provider JAR, deliberately free of the application stack: no Spring Boot,
  its own Lombok pin, `@JBossLog` rather than `@Slf4j`. It holds the Discord identity provider, the
  guild/role gate authenticator, and the backend account checker.
- **`keycloak-theme/krt-theme`** — login and account UI in the organisation's design.
- **`test-support`** — shared test scaffolding (endpoint enumeration, route inventories) used to
  assert coverage across modules.

## 5.5 The monitoring plane

A second compose project on the same host, with its own network: **prometheus**, **grafana**,
**loki**, **tempo**, **alertmanager**, **blackbox-exporter**, two **postgres-exporters** and a
**redis-exporter** as containers — and, since the cutover, **node-exporter**, **alloy** and
**podman-exporter** as *host* services rather than containers. §7.3 says why each of the three
moved, and what was deleted instead of being carried across.
