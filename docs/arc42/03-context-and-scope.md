# 3. Context and scope

## 3.1 Business context

```
                         ┌───────────────────────────────────────────────┐
   Member (browser) ────►│                                               │
                         │                                               │◄──── Discord
   Member (Android app) ►│              PROFIT BASETOOL                  │      (login identity +
                         │                                               │       guild/role gate,
                         │   missions · hangar · Lager · job orders ·    │       via Keycloak)
                         │   refinery · Materialbörse · Kartellbank ·    │
                         │   notifications · audit · org chart           │◄──── UEX · SC Wiki
   Exchange clients ◄───►│                                               │      (game data)
   (SC Extractor,        │                                               │
    VerseKit)            │                                               │
   Maintainer  ─────────►│                                               │
   (@greluc)             │                                               │────► SMTP relay
                         └───────────────────────────────────────────────┘      (account mails)
                                │                 │                │
                                ▼                 ▼                ▼
                        Let's Encrypt        GHCR + GitHub      Nextcloud
                        (certificates)       + Sigstore         (off-site
                                             (images, bundles,   backup via
                                              signatures)        restic/rclone)
```

| Neighbour | Direction | What crosses the boundary | Why it is outside |
| --- | --- | --- | --- |
| **Member, browser** | in | Interactive use of every feature, over one authenticated session | The primary actor |
| **Member, Android app** | in | The same data, over `api.profit-base.online` with its own vhost, rate limits and deny rules; sign-in against Keycloak on the app origin (ADR-0166) | A separate repository (`basetool-android`) with its own release cycle |
| **Approved exchange clients** (epic #2078) | both | The member's own blueprints, Lager lots (personal and shared, ADR-0230) and ships, both ways, plus a read-only anonymised demand feed, the location list and review drafts, over `/exchange/v1/**` on the ingest gateway — consent per capability, DPoP-bound tokens, a database client registry ([`external-exchange.md`](../specs/external-exchange.md), ADR-0216 … ADR-0220). Live since the go-live of 2026-09-28 (release 1.13.0) | Programs on the member's PC: third-party tools approved one by one (VerseKit first) and the SC Extractor (repository `basetool-sc-extractor`) as the first-party client, which sends its refinery orders and blueprints as drafts here since release 2.10.0. Its former `/v1` routes on the ingest gateway were removed on 2026-09-28 (REQ-XCH-033) |
| **GitHub Pages** | out | The third-party documentation at <https://krt-profit.github.io/basetool/> (`docs/exchange/`, rendered OpenAPI reference, schemas), built by `exchange-docs.yml` and deployed from `main` only; the service document's `docsUrl` points there. A static site, no runtime unit — the schemas' permanent `$id`s stay on the ingest host (REQ-XCH-002, REQ-XCH-011) | Documentation hosting |
| **Discord** | both | OAuth2 social login, guild membership, in-guild role and nickname — all asked by the Keycloak SPI, fail-closed; the applications never call Discord | An identity the organisation already uses; the tool does not own it |
| **Keycloak** | — | *Inside* the boundary as a deployed component, but *outside* the applications: they never see a credential | See §5 |
| **UEX** | out | Commodity and item prices, the universe's locations, vehicles, refinery methods and yields (`integration/UexClient`) | Third-party game-economy data |
| **Star Citizen wiki** | out | Reference data synchronised by the backend (`integration/scwiki`) | Third-party game data |
| **SMTP relay** | out | Transactional account mails — approval and rejection notices (`REQ-NOTIF-013/-014`); a no-op unless `SPRING_MAIL_HOST` is set | Mail delivery is not something to host |
| **Let's Encrypt** | out | ACME HTTP-01 for the four public names | Certificate authority |
| **GHCR / GitHub / Sigstore** | out | Signed images, the promotable config and provider-JAR bundles, provenance and attestations; the host verifies every digest against Sigstore before applying it (`REQ-OPS-015`) | Build and distribution |
| **Nextcloud (WebDAV)** | out | The nightly encrypted restic backup | Off-site storage, deliberately not on the same host or provider account |

> [!note] WoltLab is not a neighbour yet
> The organisation's forum is intended to become the roster's source of truth (the knowledge base's
> WoltLab note carries the decision). Nothing in the code talks to it or anticipates it today.

## 3.2 Technical context — what is reachable from the internet

Exactly **four** names resolve to the host, and all four terminate on the same edge. They reach it
through a host-level **haproxy** on `:80`/`:443` that forwards bytes and hands the edge the client
address over PROXY protocol v2; TLS still terminates at the edge, which publishes on loopback only
(ADR-0187).

| Public name | Terminates at | Reaches | Notes |
| --- | --- | --- | --- |
| `profit-base.online` | edge (nginx) | `frontend`; `keycloak` under `/auth` | The whole interactive UI, and identity on the same origin (ADR-0166). The landing page and the legal pages are the only unauthenticated content (ADR-0159). |
| `ingest.profit-base.online` | edge | `ingest` | The desktop-extractor gateway (`/v1`) and the exchange API (`/exchange/v1`), through the edge's catch-all location. Approved clients only; an unapproved caller gets `403 CLIENT_NOT_ALLOWED`. |
| `api.profit-base.online` | edge | `backend` | For the Android app. Rewrites the whole `X-Forwarded-*`/`Forwarded` family and denies `/actuator`. |
| `grafana.profit-base.online` | edge | `grafana` | Operator-facing, behind Grafana's own OIDC login against the same Keycloak. |

Everything else — the backend's REST API for the web UI, both PostgreSQL instances, Redis,
Prometheus, Loki, Tempo, Alertmanager and every exporter — is reachable only on the container
network or on loopback. **The backend is not internet-reachable except through the `api` vhost**,
which is why the ingest gateway exists at all: it takes the internet-facing exposure so the backend
does not have to.

> [!note] A fifth certificate directory exists and nothing serves it
> `edge-certs` carries a `keycloak.<domain>` directory left from when Keycloak had its own vhost
> (before ADR-0166 moved identity to `/auth` on the app origin). It is served by nothing and renewed
> by nothing; it is recorded so that counting five directories against four live vhosts does not
> read as a missing certificate. §11.8 carries it as known debt.

## 3.3 What is deliberately out of scope

- **Multi-tenancy across organisations.** The OrgUnit model divides *one* organisation. A second
  customer would be a second deployment, and nothing is built to make that cheap.
- **Public API access.** The exchange API (epic #2078) publishes its contract so approved clients
  can be built against it, but that is documentation of a restricted interface, not an invitation:
  it opens a narrow, capability-scoped contract to clients approved one by one in a public issue and
  PR, never the backend API (ADR-0216). The extractor's own `/v1` contract (`REQ-INGEST-010`) was
  retired with its routes on 2026-09-28.
- **Handover and location of traded goods.** The Materialbörse matches offers to requests and then
  gets out of the way; where and when members meet stays off-tool and private, on purpose.
- **Payment of any kind.** The Kartellbank is a ledger of in-game currency. No real money, no
  payment provider, nothing in scope for PCI.
