# ADR-0135 — The mobile API is exposed through a dedicated public vhost, not through a gateway

- **Status:** Proposed
- **Date:** 2026-08-18
- **Related:** [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the
  rejected alternative's own reasoning) · [ADR-0131](0131-mobile-auth-refresh-only-dpop-binding.md) ·
  [ADR-0134](0134-backend-joins-the-management-port-isolation.md) ·
  [ADR-0090](0090-isolate-app-actuator-on-internal-management-port.md) ·
  [ADR-0072](0072-monitoring-stack-prometheus-grafana.md) · specs `REQ-SEC-011`, `REQ-SEC-030`,
  `REQ-SEC-031`, `REQ-SEC-032`, `REQ-SEC-033`, `REQ-OBS-012`, `REQ-OBS-018` ·
  [`ANDROID_API_EXPOSURE_PLAN.md`](../ANDROID_API_EXPOSURE_PLAN.md) items B1, C, D3

## Context

The native Android client needs `/api/v1` reachable from the internet. Today nothing does: the
backend sits on no `net-proxy-*` network, and the only public surfaces are the Thymeleaf frontend,
the ingest gateway, Keycloak and Grafana. The app cannot use the frontend — it is server-rendered
HTML with a session cookie, not an API — so the backend has to become reachable in some shape.

Two shapes were on the table, and the choice decides where authorisation lives for years:

1. **A dedicated public vhost** in front of the existing backend connector.
2. **An ingest-style gateway module** — a third Spring app in front of the backend, mirroring what
   the desktop extractor already talks to.

Option 2 is tempting because the pattern exists and looks like defence in depth. It is worth being
precise about what it would actually buy.

## Decision

**`/api/v1` is exposed through a dedicated public vhost `api.profit-base.online`, configured as a
default-deny allow-list, with its own rate budgets, deny rules, probes and TLS. No gateway module is
introduced.**

The vhost proxies only the endpoint families the app consumes and answers 404 for everything else.
A blocklist was rejected: the anonymous surface is branchy — the `/slim` twins, guest participant
mutations across several verbs, `POST /api/v1/orders/items` with its table-wide pessimistic lock —
so a blocklist misses paths, and any *future* `permitAll` endpoint added for the web app would
become internet-reachable on the day it merges rather than on the day someone decides it should be.
The terms/consent endpoints and the registration-status read are on the allow-list from day one
because the app's terms gate and `PENDING_APPROVAL` handling depend on them; the anonymous **write**
paths stay off it until guest mode ships.

**Why not a gateway.** ADR-0129 settled what the ingest gateway is: a *trusted subsystem* that
persists nothing, exists because an unattended desktop tool posts screenshots, and was deliberately
kept from becoming a token relay. Putting the same shape in front of the whole API inherits none of
that justification and adds a fork in the contract:

- **It buys no authorisation.** Every authorisation decision the API makes — realm roles, the
  org-unit scope triple of `OwnerScopeService`, per-`sub` data isolation, guest field redaction — is
  made in the backend service layer against the caller's own token. A gateway could not re-decide
  any of them without a second copy of the rules, and a second copy of an authorisation rule is a
  divergence waiting to happen.
- **The two honest gateway designs are both worse.** Forward the token unchanged and it is a reverse
  proxy with extra hops, an extra TLS terminus and an extra JVM — which is what NPM already is,
  minus the JVM. Re-mint identity at the gateway and it becomes the thing ADR-0129 explicitly
  refused to build, holding a credential that can act for any member, on the most exposed host.
- **It would fork the endpoint contract.** The same endpoints would be reachable through two code
  paths with two sets of filters, and the interesting bugs live exactly in the difference.

What a gateway *would* buy — a narrower reachable surface — the vhost's allow-list buys without a
new deployable, and the edge is where a path-level allow-list belongs anyway.

## Consequences

**The backend's own hardening becomes load-bearing, and shipped first.** Track A landed before this:
honest client-IP attribution ahead of `ForwardedHeaderFilter` (REQ-SEC-011), Actuator off the
application connector (ADR-0134), `no-store` on the sensitive GET families (REQ-SEC-031), the
anonymous page-size ceiling (REQ-SEC-032) and a per-`sub` budget behind authentication
(REQ-SEC-033). None of them depended on this decision — each improves the current deployment — but
together they are what makes the connector safe to put a proxy in front of.

**The audience flip is a release gate, not a later hardening step.** The vhost must not go live
against a backend that still accepts audience-less tokens from arbitrary realm clients
(`IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend`, plan item D5). The `aud` mapper on the mobile
client exists for this and shipped with ADR-0131's provisioning.

**Separate switchability is the point of a separate host.** Rate budgets, the `/actuator` deny,
Force-SSL, HSTS and the certificate are per-vhost, so the API's posture is tuned — and if necessary
turned off — without touching the web app. That is also the crude kill switch: disabling one proxy
host stops every native client without a deploy.

**Monitoring must cover a surface that does not exist yet.** The probes for liveness, IPv6, the
`/actuator` deny, Force-SSL, HSTS and DNS ship **staged** (commented) with a documented enable
procedure, because an un-staged probe of a host that does not resolve pages from the minute it
merges, and a permanently-firing channel is one an operator stops reading (REQ-OBS-014, runbook
Appendix C). The client attribution and auth-failure breakdown of REQ-OBS-018 ship live, because
they measure the existing traffic and give the new surface a baseline to be read against.

**The identity provider stays a blind spot for now.** Keycloak's event log remains off (owner
decision, 2026-08-17), so failed authentication *at the token endpoint* is invisible. The API-side
counters see what reaches the API; password spraying that never gets past Keycloak is not covered by
anything but its brute-force lockout and the edge budget. Revisit if the public surface ever shows
abuse the API-side counters cannot explain.

## Alternatives considered

*An ingest-style gateway module.* Rejected above: no authorisation gain, a forked contract, and its
only non-trivial variant is the token relay ADR-0129 refused to build.

*Expose the API under the existing frontend vhost (`profit-base.online/api`).* One less certificate
and one less DNS record, and it welds the two surfaces together: a rate budget tuned for a browser
session applies to a mobile sync, the `/actuator` deny and HSTS posture are shared, and the API
cannot be switched off without taking the web app with it. The saving is trivial; the coupling is
not.

*Keep the API private and reach it over the management VPN.* Correct for an operator tool, useless
for a member-facing app: it would require every member to run a WireGuard client.

*A backend-for-frontend that speaks a mobile-specific protocol.* A real design with real benefits
(fewer round trips, tailored payloads) and the wrong problem to solve first. It is a second
implementation of the API surface, and the external-contract set (plan item B3) has to be settled
either way. Not excluded later; excluded as a prerequisite for the first release.
