# ADR-0268 — The backend is internal-only, and the ingest gateway is the single public API entry point

- **Status:** Accepted — decided ahead of the change; the removal lands in #2672, #2673 and #2674.
  Supersedes [ADR-0135](0135-public-api-vhost-not-a-gateway.md). Amends
  [ADR-0131](0131-mobile-auth-refresh-only-dpop-binding.md),
  [ADR-0134](0134-backend-joins-the-management-port-isolation.md) and
  [ADR-0202](0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md),
  each of which rests on the exposure this ends. Amended 2026-10-17 (*Amendment 1*): the Android
  client is deleted without a disabled grace period, and the realm provisioner deletes it.
- **Date:** 2026-10-17
- **Deciders:** @greluc (issue #2638)
- **Related:** [ADR-0164](0164-an-installable-web-app-without-a-service-worker.md) (the PWA that
  replaces the app) · [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md) ·
  [ADR-0217](0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md) ·
  [ADR-0173](0173-a-security-alert-watches-a-signal-the-deployment-cannot-produce-itself.md) (the
  probe's reasoning) · [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) ·
  specs `REQ-SEC-011`, `REQ-SEC-031`, `REQ-SEC-032`, `REQ-SEC-033`, `REQ-SEC-037`, `REQ-OBS-012`,
  `REQ-OBS-018` · [`ANDROID_API_EXPOSURE_PLAN.md`](../archive/ANDROID_API_EXPOSURE_PLAN.md) and
  [`API_VHOST_ROLLOUT_RUNBOOK.md`](../archive/API_VHOST_ROLLOUT_RUNBOOK.md) (the plan and rollout
  this reverses)

## Context

ADR-0135 opened `/api/v1` to the internet through a dedicated vhost, `api.profit-base.online`, and
said in its first sentence why: *"The native Android client needs `/api/v1` reachable from the
internet. Today nothing does."* Everything the exposure cost followed from that one caller — the
default-deny admission map generated from the contract set, the per-vhost rate budgets, deny rules
and TLS, the DNS record, the nightly probes, the management-port isolation of ADR-0134, the DPoP
refresh-only binding of ADR-0131, and the realm shape ADR-0202's provisioner converges.

The app is discontinued (issue #2638). Members use the installable web app of ADR-0164, which is not
a second client: it *is* the Thymeleaf frontend, served from the same vhost, reaching the backend
through `BackendApiClient` over `net-backend-frontend`. A PWA tab is a browser tab. There is no
member-facing client left that speaks to the backend directly.

So the premise is gone, and what remains is an internet-reachable surface in front of the one
application that holds every authorisation decision and every row of member data. The backend today
joins seven container networks — `net-db-backend`, `net-backend-frontend`, `net-backend-keycloak`,
`net-backend-ingest`, `net-redis-backend`, `net-monitoring-scrape`, and `net-proxy-api`. Six of them
are its real neighbours. The seventh is the edge.

One thing the exposure did *not* create is the hardening that shipped ahead of it. Honest client-IP
attribution (REQ-SEC-011), the `no-store` families (REQ-SEC-031), the anonymous page-size ceiling
(REQ-SEC-032) and the per-`sub` budget (REQ-SEC-033) each improved the deployment that existed
before the vhost, and ADR-0135 said so.

## Decision

**The backend API is internal-only. The ingest gateway is the single public API entry point.**

1. **No public API surface.** `api.profit-base.online` ceases to exist: no vhost on the edge, no
   admission map, no DNS record, no certificate SAN. The backend leaves `net-proxy-api` and that
   network is deleted, so there is no route from the edge to its `11261` connector or to its
   management port.
2. **The backend's callers are enumerable and all internal:** the `frontend` module, the `ingest`
   gateway, and the Keycloak SPI (which calls `POST /internal/discord/account-existence` over
   `net-backend-keycloak`). Anything else is a defect, not a configuration option — ADR-0270 makes
   that statement a check on the token, and ADR-0272 narrows the forwarded-header trust to the first
   two.
3. **The ingest gateway stays public and becomes the only public API.** `/exchange/v1/**` and the
   connected-apps surface (ADR-0216, ADR-0217) are unchanged, and after this they are the whole
   public API of the system. The web UI, Keycloak and Grafana stay public as they are today. The
   specs and arc42 §3 are to **state this positively** rather than leave it to be inferred from an
   absence — §3 still carries the claim this reverses (*"the backend is not internet-reachable
   except through the `api` vhost"*), which stays true until #2672 removes the vhost and rewrites
   the chapter.
4. **A probe asserts the negative.** The API host must not resolve, and no edge hostname or port on
   the public address may return a backend answer. This is ADR-0173's rule applied to a surface that
   has been removed: the deployment cannot produce a signal for a hole that was closed, so a
   regression — a re-added proxy host, a re-joined network, a published port — would be silent.
   REQ-OBS-012 asserted the `/actuator` deny *on* the vhost; it is replaced by an assertion that the
   host is not there at all.
5. **The hardening does not revert.** Both rate limiters, the cache-control families and the
   client-IP attribution stay. A control justified by an exposure is not undone when the exposure
   goes; what changes is the rationale, which #2673 rewrites.

## Consequences

**ADR-0134's premise falls away and its decision stands.** The backend's management port (`11271`,
internal-only, prod) was moved off the application connector *because* a proxy was about to forward
that connector. That reason is gone; the port stays, because an internal-only management surface
with a `ROLE_ADMIN`-gated log-level write is better than an app-port scrape regardless of who can
reach it, and ADR-0265 has since made metrics and info on it require the scrape credential for a
reason that has nothing to do with the vhost. Reverting it would be work in the wrong direction.

**ADR-0131 loses its client, and ADR-0202 loses part of the shape it converges.** DPoP
refresh-token-only binding, the `krt-mobile-dpop` profile, its policy and the `dpop-refresh-only`
marker role exist for `basetool-android` and nothing else. They are deleted in #2674, without a
disabled grace period, and the realm provisioner removes them wherever they remain (*Amendment 1*).
The *decision* in ADR-0131 — that
Keycloak can bind only the refresh token, and how — remains the record of an experiment nobody
should have to repeat.

**The edge loses a certificate.** The SAN set changes, so one new certificate is issued; the Keycloak
admin ACL in the frontend vhost loses the `net-proxy-api` gateway address, and the trusted-proxy pins
that named the API network are re-pinned (#2672).

**A future native client is a new decision, not a restoration.** ADR-0135's reasoning against a
gateway module still holds and still applies; the exposure plan and the rollout runbook stay in the
archive. What a new client would not get is the assumption that the surface is already there.

**Nothing changes for the exchange.** Its clients are external by design, its contract is frozen
where they reach it (ADR-0219), and its gateway keeps the posture ADR-0129 gave it. This ADR narrows
the public API to that one surface; it does not touch it.

## Alternatives considered

*Keep the vhost with an empty allow-list.* The cheapest possible change and the worst shape: a host
that exists still resolves, still terminates TLS, still answers, and is one reviewed-looking config
line away from being open again. The DNS record and the certificate would go on asserting that a
public API exists, and the negative probe of decision 4 could not be written at all.

*Keep the vhost for a future app.* A surface maintained for a client that does not exist: budgets to
tune, probes to keep green, a certificate to renew, and an admission map whose generator has no
contract set to read once ADR-0269 lands. The option costs something every month and buys a head
start on work nobody has scheduled.

*Put the backend behind a gateway module instead of closing it.* Rejected for ADR-0135's own reasons,
which ADR-0129 settled first: a gateway buys no authorisation it could not get wrong twice, and its
only non-trivial variant is the token relay the project refused to build. With no public caller the
question does not even arise.

## Amendment 1 — 2026-10-17: the Android client is deleted outright, and the provisioner deletes it

- **Deciders:** @greluc (owner decision in #2674)

The first version planned to disable `basetool-android` first and delete it after a grace period,
each step a separate production write. The owner decided against the grace period, and production's
client was deleted by hand on 2026-10-17.

The realm provisioner now carries the retirement as an explicit, named section (`plan_retired` in
`scripts/provision-keycloak-realm.py`): it detaches and removes the `krt-mobile-dpop-policy` policy,
removes the `krt-mobile-dpop` profile, and deletes the client with its `dpop-refresh-only` marker
role, mappers and scope mappings, in that order and wherever any of them still exists. Every other
client policy and profile is carried forward. This is the one exception to ADR-0202's decision 4,
recorded there as well. The App Link surface on the frontend that the client's redirect URI needed
(REQ-SEC-038) is removed in the same change. `scripts/provision-keycloak-mobile-client.py` and
`scripts/verify-dpop-binding.py` are deleted; the kcadm wrapper both provisioners used lives in
`scripts/keycloak_kcadm.py`. Requirements: `REQ-OPS-033`, `REQ-SEC-030`, `REQ-SEC-035` and
`REQ-SEC-038` (the last three superseded); pinned by case 4 of
`scripts/provision-keycloak-realm.test.sh`.
