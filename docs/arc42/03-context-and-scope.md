# 3. Context and scope

## 3.1 Business context

```
                         ┌───────────────────────────────────────────────┐
   Member (browser) ────►│                                               │
                         │                                               │
   Member (Android app) ►│              PROFIT BASETOOL                  │◄──── Discord
                         │                                               │      (login identity +
   Desktop extractor ───►│   missions · hangar · Lager · job orders ·    │       guild/role gate)
   (screenshot → JSON)   │   refinery · Materialbörse · Kartellbank ·    │
                         │   notifications · audit · org chart           │◄──── UEX
   Maintainer  ─────────►│                                               │      (commodity prices)
   (@greluc)             │                                               │
                         └───────────────────────────────────────────────┘
                                │                 │                │
                                ▼                 ▼                ▼
                        Let's Encrypt        GHCR + GitHub      Nextcloud
                        (certificates)       (images, config    (off-site
                                              bundle, prove-     backup via
                                              nance)             restic/rclone)
```

| Neighbour | Direction | What crosses the boundary | Why it is outside |
| --- | --- | --- | --- |
| **Member, browser** | in | Interactive use of every feature, over one authenticated session | The primary actor |
| **Member, Android app** | in | The same data, over `api.profit-base.online` with its own vhost, rate limits and deny rules | A separate repository (`basetool-android`) with its own release cycle |
| **Desktop extractor** | in | Refinery/inventory screenshots turned into JSON, token-authenticated `POST` to the ingest gateway | A separate repository (`basetool-sc-extractor`); **a restricted interface**, not an open API |
| **Discord** | both | OAuth2 social login, guild membership and in-guild role, checked fail-closed | An identity the organisation already uses; the tool does not own it |
| **Keycloak** | — | *Inside* the boundary as a deployed component, but *outside* the applications: they never see a credential | See §5 |
| **UEX** | out | Commodity prices for valuations | Third-party game-economy data |
| **Star Citizen wiki** | out | Reference data synchronised by an agent (`integration/scwiki`) | Third-party game data |
| **Let's Encrypt** | out | ACME HTTP-01 for the four public names | Certificate authority |
| **GHCR / GitHub** | out | Signed images, the promotable config bundle, provenance and attestations | Build and distribution |
| **Nextcloud (WebDAV)** | out | The nightly encrypted restic backup | Off-site storage, deliberately not on the same host or provider account |

## 3.2 Technical context — what is reachable from the internet

Exactly **four** names resolve to the host, and all four terminate on the same edge:

| Public name | Terminates at | Reaches | Notes |
| --- | --- | --- | --- |
| `profit-base.online` | edge (nginx) | `frontend` | The whole interactive UI. The landing page and the legal pages are the only unauthenticated content (ADR-0159). |
| `ingest.profit-base.online` | edge | `ingest` | The desktop-extractor gateway. Approved clients only; an unapproved caller gets `403 CLIENT_NOT_ALLOWED`. |
| `api.profit-base.online` | edge | `backend` | For the Android app. Rewrites the whole `X-Forwarded-*`/`Forwarded` family and denies `/actuator`. |
| `grafana.profit-base.online` | edge | `grafana` | Operator-facing, behind Grafana's own OIDC login against the same Keycloak. |

Everything else — the backend's REST API for the web UI, both PostgreSQL instances, Redis,
Prometheus, Loki, Tempo, Alertmanager and every exporter — is reachable only on the container
network or on loopback. **The backend is not internet-reachable except through the `api` vhost**,
which is why the ingest gateway exists at all: it takes the internet-facing exposure so the backend
does not have to.

> [!note] A fifth certificate directory exists and nothing serves it
> `edge-certs` carries a `keycloak.<domain>` directory left from when Keycloak had its own vhost.
> It is served by nothing and renewed by nothing. It is recorded here and in the cutover runbook so
> that counting five directories against four live vhosts does not read as a missing certificate —
> which is the wrong thing to start investigating during a migration window.

## 3.3 What is deliberately out of scope

- **Multi-tenancy across organisations.** The OrgUnit model divides *one* organisation. A second
  customer would be a second deployment, and nothing is built to make that cheap.
- **Public API access.** The ingest interface publishes an OpenAPI document so the official
  extractor can be built against a stable contract; that is documentation of a restricted
  interface, not an invitation (`REQ-INGEST-011`).
- **Handover and location of traded goods.** The Materialbörse matches offers to requests and then
  gets out of the way; where and when members meet stays off-tool and private, on purpose.
- **Payment of any kind.** The Kartellbank is a ledger of in-game currency. No real money, no
  payment provider, nothing in scope for PCI.
