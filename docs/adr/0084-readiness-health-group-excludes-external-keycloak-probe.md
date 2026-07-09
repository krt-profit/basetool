# ADR-0084 — The container-gating readiness health group excludes the external Keycloak probe

- **Status:** Accepted
- **Date:** 2026-07-09
- **Deciders:** @greluc
- **Related:** `backend/src/main/resources/application.yml` · `frontend/src/main/resources/application.yml` · `backend/Dockerfile` · `frontend/Dockerfile` · `KeycloakHealthIndicator` (backend + frontend) · REQ-SEC-024 · ADR-0083 · the 2026-07-09 native-thread exhaustion incident

## Context

Both the backend and the frontend container `HEALTHCHECK` probe `/actuator/health/readiness`, and
docker-compose gates `depends_on: condition: service_healthy` on that container health. To make
readiness meaningful, the `readiness` health group was widened beyond Spring's default
`readinessState` to include the indicators whose `DOWN` means "stop sending traffic":

- backend readiness: `readinessState, db, keycloak, diskSpace, ssl`
- frontend readiness: `readinessState, backend, keycloak, redis, diskSpace, ssl`

The custom `KeycloakHealthIndicator` makes a **live HTTP call to Keycloak's OIDC discovery endpoint
on every probe** (every ~10 s). Putting an **external-dependency** probe on the endpoint that gates
the container's own health has two failure modes, both realised on 2026-07-09:

1. A transient Keycloak outage (or a hairpin/DNS blip through the public edge) flips the app
   container `unhealthy` even though the JVM is alive and can serve health, metrics and — with cached
   JWKS — most requests.
2. When the app itself is resource-starved (the native-thread exhaustion incident), the readiness
   handler and the Keycloak probe are exactly the code that fails first (they need a thread / a
   socket), so readiness goes `DOWN` and the container flips `unhealthy` — which the deploy loop then
   thrashed against for ~2 hours.

The original intent — "a Keycloak blip should yank the container out of `service_healthy`" — assumed
a load-balanced topology where "not ready" removes one replica from rotation without restarting it.
The actual topology is a single-host compose stack with no load balancer: here `readiness` **is** the
container health signal, and there is no rotation to fall out of — a Keycloak blip simply marks the
whole app container bad.

## Decision

The `KeycloakHealthIndicator` is **removed from the `readiness` health group** on both modules:

- backend readiness: `readinessState, db, diskSpace, ssl`
- frontend readiness: `readinessState, backend, redis, diskSpace, ssl`

The indicator **bean stays registered**, so Keycloak health still contributes to the root
`/actuator/health` aggregate and still emits its `WARN` log line when the discovery endpoint is
unreachable — it is still observable, it just no longer gates container liveness.

The readiness group now carries only **local** signals ("can THIS instance serve"): its own state,
its database, disk and TLS cert (backend); plus Redis and the direct backend dependency (frontend).
An **external** auth-server outage is deliberately not one of them.

## Consequences

- A Keycloak outage no longer flips the backend/frontend containers `unhealthy`, so it no longer
  feeds the deploy loop or any health-based restart. Combined with ADR-0083 (which stops the deploy
  loop thrashing on a merely-unhealthy container at all), a dependency blip can no longer amplify into
  a multi-service restart storm.
- **Startup ordering is unaffected.** The backend still waits for Keycloak at startup via
  `depends_on: keycloak: condition: service_healthy`, which is gated by the **Keycloak container's own
  healthcheck** (its Quarkus `/health/ready` on the management port), not by the backend's
  `KeycloakHealthIndicator`. The frontend still waits on the backend indicator, which remains in its
  readiness group (a genuine backend outage does mean the frontend cannot serve).
- **Visibility is retained, not lost.** Keycloak reachability from the app is still surfaced by the
  indicator's `WARN` logs (captured by Loki) and the existing `Http5xxRateHigh` /
  frontend-5xx signals for the auth path; it is simply no longer coupled to container liveness. The
  trade-off — the app container can now report healthy while Keycloak is down — is the intended
  behaviour: an auth-server outage should degrade logins, not cycle the app containers.
- `application-test.yml` on both modules already excluded `keycloak`/`ssl` from readiness, so tests
  are unaffected by this change.

