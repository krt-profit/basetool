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
| **Approved exchange clients** (epic #2078) | both | The member's own blueprints, Lager lots (personal and shared, ADR-0230) and ships, both ways, plus a read-only anonymised demand feed (with the single orders for a Logistician of the unit, REQ-XCH-047), the member's direct org units (REQ-XCH-048) and the shared stock of their pools (REQ-XCH-042), which a Logistician of the unit also books into and out of (REQ-XCH-045), the location list and review drafts, over `/exchange/v1/**` on the ingest gateway — consent per capability, DPoP-bound tokens, a database client registry ([`external-exchange.md`](../specs/external-exchange.md), ADR-0216 … ADR-0220). Live since the go-live of 2026-09-28 (release 1.13.0) | Programs on the member's PC: third-party tools approved one by one (VerseKit first) and the SC Extractor (repository `basetool-sc-extractor`) as the first-party client, which sends its refinery orders and blueprints as drafts here since release 2.10.0. Its former `/v1` routes on the ingest gateway were removed on 2026-09-28 (REQ-XCH-033) |
| **GitHub Pages** | out | The third-party documentation at <https://krt-profit.github.io/basetool/> (`docs/exchange/`, rendered OpenAPI reference, schemas), built by `exchange-docs.yml` and deployed from `main` only; the service document's `docsUrl` points there. A static site, no runtime unit — the schemas' permanent `$id`s stay on the ingest host (REQ-XCH-002, REQ-XCH-011) | Documentation hosting |
| **Discord** | both | OAuth2 social login, guild membership, in-guild role and nickname — all asked by the Keycloak SPI, fail-closed; the applications never call Discord | An identity the organisation already uses; the tool does not own it |
| **Keycloak** | — | *Inside* the boundary as a deployed component, but *outside* the applications: they never see a credential | See §5 |
| **UEX** | out | Commodity and item prices, the universe's locations, vehicles, refinery methods and yields (`catalogue/internal/UexClient`) | Third-party game-economy data |
| **Star Citizen wiki** | out | Reference data synchronised by the backend (`integration/scwiki`) | Third-party game data |
| **SMTP relay** | out | Transactional account mails — approval and rejection notices (`REQ-NOTIF-013/-014`); a no-op unless `SPRING_MAIL_HOST` is set | Mail delivery is not something to host |
| **Let's Encrypt** | out | ACME HTTP-01 for the three public names | Certificate authority |
| **GHCR / GitHub / Sigstore** | out | Signed images, the promotable config and provider-JAR bundles, provenance and attestations; the host verifies every digest against Sigstore before applying it (`REQ-OPS-015`) | Build and distribution |
| **Nextcloud (WebDAV)** | out | The nightly encrypted restic backup | Off-site storage, deliberately not on the same host or provider account |

> [!note] WoltLab is not a neighbour yet
> The organisation's forum is intended to become the roster's source of truth. Nothing in the code talks to it or anticipates it today.

## 3.2 Technical context — what is reachable from the internet

Exactly **three** names resolve to the host, and all three terminate on the same edge. They reach it
through a host-level **haproxy** on `:80`/`:443` that forwards bytes and hands the edge the client
address over PROXY protocol v2; TLS still terminates at the edge, which publishes on loopback only
(ADR-0187).

| Public name | Terminates at | Reaches | Notes |
| --- | --- | --- | --- |
| `profit-base.online` | edge (nginx) | `frontend`; `keycloak` under `/auth` | The whole interactive UI, and identity on the same origin (ADR-0166). The landing page and the legal pages are the only unauthenticated content (ADR-0159). |
| `ingest.profit-base.online` | edge | `ingest` | The desktop-extractor gateway (`/v1`) and the exchange API (`/exchange/v1`), through the edge's catch-all location. Approved clients only; an unapproved caller gets `403 CLIENT_NOT_ALLOWED`. |
| `grafana.profit-base.online` | edge | `grafana` | Operator-facing, behind Grafana's own OIDC login against the same Keycloak. |

Everything else — the backend's REST API, both PostgreSQL instances, Redis, Prometheus, Loki,
Tempo, Alertmanager and every exporter — is reachable only on the container network or on loopback.

**The ingest gateway is the single public API entry point, and the backend is internal-only**
(ADR-0268). `/exchange/v1/**` and the connected-apps surface on `ingest.profit-base.online` are the
whole public API of the system; the backend's `11261` connector and its `11271` management port are
reached by the frontend, the ingest gateway and the Keycloak SPI over container networks, and by
nothing else. That is why the gateway exists at all: it takes the internet-facing exposure so the
backend does not have to.

`api.profit-base.online` was the exception from 2026-08-18 until 2026-10-17 — a vhost opened for the
native Android app (ADR-0135) and removed with it (ADR-0268, #2672), together with the DNS record,
the certificate SAN and the `net-proxy-api` network. Because a closed hole emits no signal of its
own, a probe now asserts the absence: the name must carry no address record, `11261` and `11271`
must refuse a connection on the public address, and no edge hostname may answer the backend's API
(REQ-OBS-012).

> [!note] Two certificate directories exist and nothing serves them
> `edge-certs` carries a `keycloak.<domain>` directory left from when Keycloak had its own vhost
> (before ADR-0166 moved identity to `/auth` on the app origin), and since #2672 an
> `api.<domain>` one left from the retired API vhost. Both are served by nothing and renewed by
> nothing — `acme` only writes the directories `ACME_HOSTS` names; they are recorded so that
> counting five directories against three live vhosts does not read as a missing certificate.
> §11.8 carries them as known debt.

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
