# ADR-0265 — The management ports require the scrape credential, except for health

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** [ADR-0090](0090-isolate-app-actuator-on-internal-management-port.md) and
  [ADR-0134](0134-backend-joins-the-management-port-isolation.md) (amended, not superseded) ·
  [ADR-0072](0072-monitoring-stack-prometheus-grafana.md) · spec `REQ-OBS-005`, `REQ-OBS-016` ·
  issue #2660 · `ManagementPortSecurityConfig`, `MonitoringScrapeSecurityConfig`,
  `ManagementPortIsolationTest`, `ManagementPortFailClosedTest` (backend, frontend, ingest) ·
  `monitoring/prometheus/prometheus.yml`

## Context

ADR-0090 and ADR-0134 put Actuator on a dedicated management port in each application and left it
unauthenticated there, on the premise that the port "is reachable only from the monitoring scrape
network and localhost". The configuration does not enforce that premise. No
`management.server.address` is set, so the connector listens on every interface of the container,
and every container that shares *any* network with the service reaches it:

| Service  | Networks besides `net-monitoring-scrape` |
|----------|------------------------------------------|
| backend  | `net-db-backend`, `net-backend-frontend`, `net-backend-keycloak`, `net-backend-ingest`, `net-proxy-api`, `net-redis-backend` |
| frontend | `net-backend-frontend`, `net-proxy-frontend`, `net-backend-keycloak`, `net-redis-frontend` |
| ingest   | `net-proxy-ingest`, `net-backend-ingest`, `net-redis-ingest` |

The edge proxy, Keycloak, Redis and the other applications could therefore read the metrics (business
counters, route-level request data) and the logger levels of a service they should only talk to on
its main port. Nothing is reachable from the internet; the gap is defence in depth.

Two ways to close it were considered:

1. **Restrict by source address.** Bind `management.server.address` to the container's address on
   `net-monitoring-scrape`, or filter the management chain to the scrape subnet and loopback. The
   Quadlet network declares no fixed subnet and the container addresses are not stable, and the
   scrape network is itself shared: Keycloak, the blackbox exporter and the database and Redis
   exporters sit on it too. An address rule would narrow the set of neighbours, not reduce it to
   Prometheus.
2. **Require a credential.** The scrape identity of ADR-0072 (`MONITORING_SCRAPE_USER` /
   `MONITORING_SCRAPE_PASSWORD`) is still configured in every application, and Prometheus still
   mounts its password as `/etc/prometheus/secrets/scrape_password`. A credential names exactly one
   caller, whichever network it comes from.

## Decision

**On the management port only `/actuator/health` and `/actuator/health/**` stay open. Every other
Actuator path that the management chain owns requires the scrape identity, and is denied to everyone
while no scrape identity is configured.**

- Each module's `ManagementPortSecurityConfig` chain keeps its matcher — `/actuator/**` in frontend
  and ingest, the enumerated `health`, `health/**`, `prometheus` and `info` in the backend, so the
  backend's `loggers` paths stay with the main chain and its `ROLE_ADMIN` gate (REQ-OBS-016). Inside
  that chain health is `permitAll`, everything else `hasRole(MONITORING_SCRAPE)` over HTTP basic
  auth against the in-memory scrape user that `MonitoringScrapeSecurityConfig` builds. Without a
  configured identity it is `denyAll`, as on the application port.
- The management chain authenticates the scrape user itself instead of leaving
  `/actuator/prometheus` to the separate `MonitoringScrapeSecurityConfig` chain, which ADR-0090
  found does not reliably take over on the management connector.
- The `basetool-backend`, `basetool-frontend` and `basetool-ingest` Prometheus jobs send
  `basic_auth` with username `monitoring` and `password_file: /etc/prometheus/secrets/scrape_password`.
- Health stays open because three callers send no credential: the Quadlet `HealthCmd` over
  `localhost`, the compose health checks, and the frontend's `BackendHealthIndicator`, which reads
  `https://backend:11271/actuator/health/readiness` over `net-backend-frontend`.

## Consequences

- A neighbour on a shared network gets `401` for `/actuator/prometheus`, `/actuator/info` and (in
  frontend and ingest) `/actuator/loggers` on the management port. What it can still read is the
  health status.
- **The prod `.env` and the Prometheus secret must agree before this is promoted.**
  `MONITORING_SCRAPE_USER` must be `monitoring` and `MONITORING_SCRAPE_PASSWORD` must equal the
  content of `/var/iri/monitoring/secrets/scrape_password`. Since ADR-0134 nothing has used the pair,
  so a mismatch would have gone unnoticed. If they disagree, the three scrape jobs answer `401`, the
  targets go `down` and the existing target-down alerts fire. The containers stay healthy and keep
  serving.
- The application image (the chain) and the config bundle (`prometheus.yml`) move together, as
  ADR-0049 already requires. If the bundle goes out ahead of the image, Prometheus sends a credential
  to an open endpoint, which does no harm. If the image goes out ahead of the bundle, the scrape
  answers `401` until the bundle arrives.
- `scrape_password` is read by a scrape job again, so it is no longer a secret that exists only so
  that the bind mount resolves.
- Every scrape costs one BCrypt check, as on the application port under ADR-0072. At a 30-second
  interval and three targets that is negligible.
- Ad-hoc reads of the metrics from inside a container (`scripts/sandbox-load.py`) pass the
  credential the container runs with. The sandbox load stack sets a throwaway pair for that.
