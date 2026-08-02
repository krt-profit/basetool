# ADR-0090 — Isolate frontend + ingest actuator on an internal-only management port

- **Status:** Accepted
- **Date:** 2026-07-10
- **Deciders:** @greluc
- **Related:** `frontend`/`ingest` `application-prod.yml` (`management.server.*`) · `docker-compose.yml` (prod `frontend`/`ingest` healthcheck override) · `monitoring/prometheus/prometheus.yml` (scrape targets) · `ManagementPortSecurityConfig` · `ManagementPortIsolationTest` · `MonitoringScrapeSecurityConfig` · `BotProtectionFilter` · `SecurityConfig` (backend, the `ROLE_ADMIN` matcher on `POST /actuator/loggers/**`) · `ActuatorLoggersAuthorizationTest` · REQ-OBS-005 · REQ-OBS-008 · REQ-OBS-012 · REQ-OBS-016 · REQ-SEC-014 · REQ-INGEST-001 · ADR-0072 · ADR-0018

## Context

The two internet-facing Spring Boot modules (`frontend`, `ingest`) served their Actuator
endpoints — `/actuator/health` and `/actuator/prometheus` — on the **same connector** as the
public application (frontend `18081`, ingest `11262`). External reachability was prevented only at
the **edge**: an NPM `location /actuator` deny (→ 404), plus fail-closed HTTP basic auth on
`/actuator/prometheus` as the ADR-0072 compensating control. That deny lives only in the NPM admin
DB (not in git); REQ-OBS-012 continuously *asserts* it, but the guarantee is a network-edge control,
not an application-level one.

Neither surface actually needs to be on the public connector:

- **Metrics** are scraped by Prometheus over the isolated `net-monitoring-scrape` network.
- **Health** is probed by the Docker `HEALTHCHECK` over `localhost` **inside** the container.

Keycloak already models the stronger posture: its management/metrics interface runs on a **separate
port 9000** that is *"never published on the host and never on an NPM proxy network"* (REQ-SEC-014).
The two Spring Boot apps were the outliers.

Options considered:

1. **Edge-deny only (status quo, hardened).** Keep actuator on the app port; extend the continuous
   deny assertions to `/actuator/health` too. Closes the observability gap but the enforcement stays
   in the NPM DB — a UI misclick can still momentarily expose the surface until the probe fires.
2. **Dedicated internal-only management port.** Move `/actuator/**` onto `management.server.port`,
   reachable only on `net-monitoring-scrape` (Prometheus) and `localhost` (healthcheck), never
   host-published and never NPM-proxied. The public connector then serves **no** actuator at all —
   404 at the application level, independent of the edge. Mirrors the Keycloak port-9000 precedent.

Both were adopted together (belt + braces): option 1 as continuous drift detection, option 2 as the
app-level guarantee. This ADR records option 2.

A Spring Boot consequence forces a security decision (established **empirically** against Spring
Boot 4.1 — `ManagementPortIsolationTest`, since Boot 4.1 no longer ships the old
`ManagementWebSecurityAutoConfiguration`): with `management.server.port` set, the apps' **main**
security chains still apply to the management connector, so out of the box `/actuator/health` is open
but `/actuator/prometheus` is answered **401** (the JWT/OAuth2 chain's bearer/login challenge) — a
credential-free Prometheus scrape would fail. Meanwhile the `MonitoringScrapeSecurityConfig`
`securityMatcher` does **not** reliably take over that path on the management connector. Two ways
out: (a) keep basic-auth and hand Prometheus credentials, or (b) add a dedicated permit-all chain for
`/actuator/**`. Since the endpoint is now internal-only, (b) — unauthenticated, matching Keycloak —
was chosen.

## Decision

Give `frontend` and `ingest` a dedicated **internal-only management port** in **prod only**
(`application-prod.yml`): frontend `18091`, ingest `11272`, each served over **HTTPS** with the same
bind-mounted `keystore.p12` (`management.server.ssl.*`), so the Prometheus scrape stays HTTPS with
the pinned CA (REQ-OBS-008 unchanged). The port is reachable only where the container already sits —
`net-monitoring-scrape` for the scrape and `localhost` for the healthcheck — and is **never**
host-published and **never** exposed on any `net-proxy-*` network, so NPM cannot route to it.

Actuator on that management port is **unauthenticated** — the Keycloak port-9000 model. A new
`ManagementPortSecurityConfig` (per module) contributes an `@Order(0)` permit-all `SecurityFilterChain`
scoped to `/actuator/**`, gated by `@ConditionalOnProperty("management.server.port")` so it exists
**only** when the dedicated port is configured (prod, and the isolation test). Because the endpoint is
no longer reachable from the internet, the ADR-0072 fail-closed basic-auth *compensating control is
superseded by port isolation* for these two modules; the metrics payload is only reachable from the
monitoring plane. The Prometheus `basetool-frontend` / `basetool-ingest` jobs therefore drop
`basic_auth` and target the management port (HTTPS + pinned CA + `server_name` unchanged). The Docker
`HEALTHCHECK` is overridden in the **prod** compose service (not the shared image) to probe
`https://localhost:<mgmt-port>/actuator/health/readiness`.

`MonitoringScrapeSecurityConfig` and the `BotProtectionFilter` `/actuator` handling are **kept
unchanged**: in dev/test/e2e no management port is set, so `ManagementPortSecurityConfig` is absent,
Actuator stays on the app port, and those main-context chains still fail-close it exactly as before.
In prod they simply guard a path the public connector no longer serves (harmless). **`backend` is unchanged** — it is off every
`net-proxy-*` network and is not internet-reachable at all, so it keeps its app-port scrape with
fail-closed basic auth; moving it would add risk with no external-exposure benefit.

Codified as an amendment to **REQ-OBS-005** (metrics endpoint isolation + the frontend/ingest
basic-auth carve-out) and **REQ-OBS-008**; the edge assertions of **REQ-OBS-012** remain as
belt-and-braces drift detection.

## Consequences

- The frontend/ingest public connectors serve **no** `/actuator/**` in prod: an external probe of
  `https://profit-base.online/actuator/health` gets a 404 from the application even if the NPM deny
  were ever removed. The external guarantee no longer depends solely on the NPM DB.
- Metrics on the management port are unauthenticated within `net-monitoring-scrape`. This is the same
  posture Keycloak's port 9000 already carries and is acceptable because the port is not reachable
  from the internet or the host. The fail-closed basic-auth requirement of REQ-OBS-005 now applies to
  **backend only**; frontend/ingest rely on network isolation.
- Prod-only change: dev, test and the CI e2e stack (which run the `-dev` profile) keep Actuator on
  the app port and their existing healthchecks, so local/CI behaviour is untouched.
- The prod compose healthcheck is decoupled from the image's default `HEALTHCHECK`. A future
  management-port change must update the compose override in lock-step; a mismatch keeps the
  container `unhealthy` and is caught by the deploy health-gate (clean rollback, not silent breakage).
- **Deployment must be lock-step (app image ⇄ config bundle).** The management port moves with the
  **app image** (`application-prod.yml`), while the healthcheck override and the Prometheus scrape
  target move with the **config bundle** (`docker-compose.yml`, `prometheus.yml`). A new config
  bundle on an old image (healthcheck probes `18091`/`11272` while Actuator is still on the app port)
  — or the reverse — fails the healthcheck and rolls back. The existing promote-in-lock-step
  discipline (ADR-0049: app images and `basetool-config` promoted together) already guarantees this;
  a split promotion of only one side must be avoided.
- One more connector per app (a second SSL port) — negligible resource cost; the same cert serves it.
- **First-deploy verification (cannot be exercised without the live monitoring stack):** after
  rollout, confirm (a) both containers report `healthy`, (b) Prometheus `basetool-frontend` /
  `basetool-ingest` targets are `up` on the new ports, and (c) `https://profit-base.online/actuator/health`
  and the ingest equivalent still answer 404 from outside (the REQ-OBS-012 probes assert this
  continuously).

### Amendment 2026-08 (PR #1472) — the exposure list gained a *mutator*

The unauthenticated permit-all chain above was decided when **everything** reachable on the
management port was read-only (`health`, `prometheus`, `info`): there was nothing an unauthenticated
caller inside `net-monitoring-scrape` could *change*. REQ-OBS-016 then added the Actuator **`loggers`**
endpoint, whose `POST {"configuredLevel":…}` is a write. The `@Order(0)` chain matches `/actuator/**`
by **path** and cannot tell a read operation from a write one, so on `frontend` and `ingest` the level
mutator became reachable unauthenticated from `net-monitoring-scrape` and `localhost`. Setting `ROOT`
to `TRACE` there makes Spring Security, WebClient and Netty write bearer tokens and request bodies
into a Loki stream retained 744 h — a read-only exposure trade quietly turned into a
credential-disclosure lever. The decision above stands; the two modules resolve it in opposite ways
because their connector posture differs:

- **`frontend` + `ingest`, prod only — the write is removed, not gated.**
  `management.endpoint.loggers.access: read-only` under the existing `management:` block of each
  `application-prod.yml` (next to `server.port: 18091` / `11272`). The write operation is not
  registered at all, so there is nothing for the permit-all chain to guard and that chain stays
  exactly as decided. `GET /actuator/loggers` still answers on the management port. Dev, test and e2e
  set no management port and never load these files, so full runtime level control is unchanged there
  (REQ-OBS-016).
- **`backend` — the write is kept and gated on `ROLE_ADMIN`.** The backend is out of scope of the
  decision above and deliberately sets **no** `management.server.port`: Actuator rides the ordinary
  `11261` app connector, which is not host-published and sits only on internal Docker networks. That
  connector is **not** an "internal-only management port" and must not be described as one — the
  distinction is exactly what makes the two shapes differ, because the app connector is covered by the
  module's *main* security chain. `SecurityConfig` therefore adds
  `.requestMatchers(HttpMethod.POST, "/actuator/loggers/**").hasRole(Roles.ADMIN)` immediately after
  the `/actuator/health` `permitAll()` and well before `anyRequest().authenticated()`; `GET` keeps
  falling through to the authenticated catch-all. Pinned by `ActuatorLoggersAuthorizationTest`
  (anonymous → 401, `KRT_MEMBER` → 403, `OFFICER` → 403, `ADMIN` → 204, member `GET` → 200).
- **Standing rule this ADR now carries.** Every endpoint added to the `frontend` / `ingest`
  `management.endpoints.web.exposure.include` list must be checked for write operations, and any it
  has set `access: read-only` in `application-prod.yml` **in the same change**. On an unauthenticated
  port, "expose" and "expose for writing" are the same act unless `access` says otherwise.
- **Cost, accepted:** a prod DEBUG dive is now a backend-only, admin-only operation. Raising a
  frontend or ingest logger in prod is back to a config edit plus a force-recreate, which is what
  REQ-OBS-016 set out to avoid — the trade is taken because the alternative is an unauthenticated
  write on a port whose whole security model is "nobody can reach it".

