# ADR-0134 — The backend joins the management-port isolation, and keeps its gated log-level write

- **Status:** Proposed
- **Date:** 2026-08-18
- **Related:** [ADR-0090](0090-isolate-app-actuator-on-internal-management-port.md) (extended,
  not superseded) · [ADR-0072](0072-monitoring-stack-prometheus-grafana.md) ·
  [ADR-0049](0049-config-as-promotable-oci-artifact.md) · spec `REQ-OBS-005`, `REQ-OBS-008`,
  `REQ-OBS-012`, `REQ-OBS-016`, `REQ-SEC-014` ·
  [`ANDROID_API_EXPOSURE_PLAN.md`](../ANDROID_API_EXPOSURE_PLAN.md) item A2 ·
  `ManagementPortSecurityConfig`, `ManagementPortIsolationTest` (backend)

## Context

ADR-0090 moved Actuator off the public connector for `frontend` and `ingest` and stated plainly why
the backend was left out:

> **`backend` is unchanged** — its management surface is not internet-reachable at
> all, so it keeps its app-port scrape with fail-closed basic auth; moving it would add risk with no
> external-exposure benefit.

That reasoning was correct and is now obsolete. The Android app's exposure plan puts a public vhost
(`api.profit-base.online`) in front of the backend's `11261` connector. The moment that lands, the
connector a proxy forwards also serves `/actuator/health` and `/actuator/prometheus`, and the only
thing standing between the internet and the metrics payload is an NPM `location /actuator` deny that
lives in the NPM admin database rather than in git. REQ-OBS-012 asserts that deny continuously, but
an assertion detects a misconfiguration after the fact; it does not prevent one.

The premise that justified the carve-out is being removed, so the carve-out has to go with it.

There is a complication ADR-0090 documented from the other side. On an unauthenticated management
port there is no identity to gate a mutator on, so `frontend` and `ingest` deleted the log-level
write outright (`management.endpoint.loggers.access: read-only`, PR #1472). The backend deliberately
went the other way: it kept `POST /actuator/loggers/**` and gated it on `ROLE_ADMIN`, and three
separate config comments name that asymmetry as intentional. Naively copying the frontend shape
would trade a live capability — runtime log-level control during an incident — for the isolation.

## Decision

**The backend gets a dedicated internal-only management port (`11271`, HTTPS, prod only), and keeps
its `ROLE_ADMIN`-gated log-level write by opening only the read endpoints on it.**

`ManagementPortSecurityConfig` contributes an `@Order(0)` permit-all chain whose `securityMatcher`
enumerates exactly `/actuator/health`, `/actuator/health/**`, `/actuator/prometheus` and
`/actuator/info` — not `/actuator/**`. Every path absent from that list keeps whatever the main
`SecurityConfig` chain gives it, and the main chain does still apply on the management connector.
So the credential-free Prometheus scrape and Docker health probe get through while `POST
/actuator/loggers/**` is still refused without `ROLE_ADMIN`.

This is not a reasoned expectation. `ManagementPortIsolationTest` measures all six properties
against a running context — Actuator absent from the application port, read endpoints served
unauthenticated on the management port, and the mutator answering 401/403 there — and is the guard
that stops a later widening of the matcher to `/actuator/**` from silently un-gating the write.

The port choice follows the existing convention (application port + 10): frontend `18081` → `18091`,
ingest `11262` → `11272`, backend `11261` → `11271`. It is served over HTTPS with the same
bind-mounted `keystore.p12`, so the scrape keeps its pinned CA and `server_name` (REQ-OBS-008
unchanged). Reachability is unchanged from ADR-0090's model: `net-monitoring-scrape` for Prometheus,
`localhost` for the Docker `HEALTHCHECK`, never host-published, never on an NPM proxy network.

The `basetool-backend` Prometheus job drops `basic_auth` and targets `backend:11271`. ADR-0072's
fail-closed basic auth existed to compensate for an edge-exposed connector; port isolation
supersedes it here exactly as it did for the other two modules.

## Consequences

**The application connector serves no Actuator in prod.** When the API vhost goes live, an external
probe of `https://api.profit-base.online/actuator/health` gets a 404 from the application, whether or
not the edge deny is in place. That removes the last dependency of this guarantee on the NPM
database. The Track C edge-deny probes still ship with the vhost as drift detection, and they now
assert something the application already enforces.

**The asymmetry with frontend and ingest inverts, and that is deliberate.** Those two have the
broader permit-all and no log-level write; the backend has the narrower permit-all and keeps the
write. The reason is structural rather than stylistic — the backend is an OAuth2 resource server, so
an identity exists on the management connector to gate on. Anyone widening the backend's matcher for
symmetry will fail `theLogLevelMutatorStaysGatedOnTheManagementPort`.

**REQ-OBS-005's fail-closed basic-auth carve-out now applies to no module.** All three rely on
network isolation. The compensating control is not weakened so much as relocated: the reason it
existed — a connector an outsider could reach — no longer describes any of them.

**Deployment must be lock-step, and the failure mode is safe.** The port move travels with the app
image (`application-prod.yml`); the healthcheck override and the scrape target travel with the
config bundle (`docker-compose.yml`, `prometheus.yml`). A config bundle ahead of the image probes a
port that does not exist yet; an image ahead of the bundle serves health on a port nothing probes.
Either way the container reports unhealthy and the deploy health gate rolls back rather than leaving
a silently unmonitored backend. ADR-0049's promote-together discipline already covers this; a split
promotion of one side must be avoided.

**Metrics on the management port are unauthenticated inside `net-monitoring-scrape`.** Same posture
as Keycloak's port 9000 (REQ-SEC-014) and as the other two modules since ADR-0090. Acceptable
because the port is reachable neither from the internet nor from the host.

**Dev, test and e2e are untouched.** They configure no management port, so
`ManagementPortSecurityConfig` is absent via `@ConditionalOnProperty`, Actuator stays on the
application port, and `MonitoringScrapeSecurityConfig` keeps failing it closed exactly as before.
Full runtime log-level control also stays available there.

**The frontend probes this port too, and that was missed — it cost a rolled-back release.** The
frontend's `BackendHealthIndicator` polls the backend's `/actuator/health/readiness` and sits in the
readiness group that gates its Docker HEALTHCHECK (ADR-0084). Moving the Actuator turned that probe
into a 404, so on 2026-08-18 the frontend container never became healthy, `deploy.sh` rolled v1.5.47
back after 180 s, and it retried on the bad-digest backoff until `:stable` was demoted. Nothing in CI
could see it: `ManagementPortSecurityConfig` is conditional on `management.server.port`, which only
the prod profile sets, and the frontend's `test` profile redefines the readiness group without the
`backend` indicator — the combination existed nowhere but production. The fix gives the frontend its
own `app.backend-health-url` (prod: `https://backend:11271`, defaulting to the API base URL
everywhere else) and pins the two ports together with `BackendHealthUrlProdParityTest`. The lesson
generalises past this ADR: **moving an endpoint means auditing every consumer of it, not only the
module that serves it.** The management port now has two consumers inside the compose network —
Prometheus over `net-monitoring-scrape` and the frontend over `net-backend-frontend`; both are
closed networks, so the isolation argument is unchanged.

**First-deploy verification, which cannot be exercised without the live stack.** After rollout
confirm that the backend container reports `healthy`, that the **frontend** container reports
`healthy` (the check this ADR originally omitted), that the Prometheus `basetool-backend` target is
`up` on `11271`, and that `/actuator/prometheus` on `11261` answers 404 from inside the network.

## Alternatives considered

*Leave the backend on the application connector and rely on the edge deny.* This is ADR-0090's
option 1, and it was already judged the weaker half of the belt-and-braces pair when the module was
not even internet-reachable. Making the connector public while keeping enforcement in an unversioned
NPM database inverts that judgement for no gain.

*Copy the frontend/ingest shape exactly — permit all of `/actuator/**` and delete the log-level
write.* Simpler and symmetrical, and it costs a capability the backend deliberately kept: the ability
to raise a log level during an incident without a redeploy, behind an admin identity. The narrower
matcher achieves the isolation without paying that, and the test makes the extra precision durable.

*Keep basic auth on the backend's `/actuator/prometheus` as an extra layer.* Rejected on evidence
rather than taste: ADR-0090 established that `MonitoringScrapeSecurityConfig`'s `securityMatcher`
does not reliably take over on the management connector, so the layer would be unreliable exactly
where it was supposed to apply, and a scrape that intermittently 401s is worse than no scrape.

*A separate management network instead of a separate port.* The container already sits on
`net-monitoring-scrape`; a port is the smaller change and matches both the Keycloak precedent and
the two modules that went first.
