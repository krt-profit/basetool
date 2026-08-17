# Android API Exposure — Phase 0 execution plan

Doc type: **living plan** (draft, pending approval by @greluc).
Scope: everything that must happen **in this repository and on the production host** before the
native Android app (`krt-profit/basetool-android`) can talk to the Basetool for real. The app-side
concept lives in the app repo (`docs/ANDROID_APP_PLAN.md`, `docs/ANDROID_APP_SECURITY.md`); this
document is the server half of its Phase 0 and the only place where the work is ordered.

Fact base: the code-level security audit of 2026-08-17 (findings folded into the app repo's
security concept, "code-verified" markers) plus the current `main` of this repository.

## 1. Why this exists as its own plan

The app consumes the existing `/api/v1`. Nothing about the API needs to be *built* — but the
backend container sits on no `net-proxy-*` network today, so `/api/v1` is not internet-reachable
at all. Making it reachable is a security-relevant change to the production edge, and the audit
found four preconditions that must land **before** the vhost goes live, not after:

1. the backend's rate limiter takes the **leftmost** `X-Forwarded-For` element — safe behind the
   single sanitising frontend hop, a full bypass behind an appending proxy;
2. the backend serves `/actuator/**` on its ordinary app connector, with the deny rule living only
   in the unversioned NPM admin database;
3. audience validation is unset in production, so any realm client's token is accepted;
4. the anonymous surface (guest mission edits, anonymous order creation) would become
   internet-reachable on day one.

Each is harmless today and load-bearing the moment a public vhost exists.

## 2. Work packages

Ownership is explicit because it decides who can execute: **[code]** lands as a PR in this repo and
I can prepare it; **[owner]** touches production or Keycloak and is @greluc's to run — my
deliverable there is the exact configuration, command and rollback.

### Track A — backend hardening (code, independent of the exposure)

| #  |                                                                                                        Work package                                                                                                        |                                                               Why it blocks the vhost                                                                |
|----|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| A1 | **[code]** Right-to-left `X-Forwarded-For` walk in `RateLimitingFilter`, modelled on the frontend's `ClientIpContextFilter`; narrow `app.rate-limit.trusted-proxies` from `172.28.0.0/16` to the API vhost's proxy network | Without it a client-chosen leftmost entry mints a fresh bucket per request — rate limiting becomes decorative and foreign IPs get framed in the logs |
| A2 | **[code]** Move the backend Actuator to a dedicated management port on the monitoring network (ADR-0090 pattern, as ingest already does)                                                                                   | The connector the vhost proxies must serve no Actuator at all; the edge deny stays as the second layer, not the only one                             |
| A3 | **[code]** Per-`sub` rate limiting behind authentication (service-level, ingest `SubjectRateLimiter` as the model) for the app-consumed write families and the SSE connect                                                 | CGNAT puts many members behind one IPv4; a per-IP bucket either throttles innocents or is set so loose it stops mattering                            |
| A4 | **[code]** `Cache-Control: private, no-store` on the sensitive GET families (bank, member PII, notifications) — `ApiCacheControlFilter` emits only `no-cache, must-revalidate` today                                       | Storage-with-revalidation is allowed by the current header; a public ingress makes intermediaries plausible                                          |
| A5 | **[code]** Minimum-app-version gate: the app sends `User-Agent: basetool-android/<semver>`, the backend refuses versions below a configured floor with a dedicated RFC 7807 code                                           | The only lever between "do nothing" and the all-or-nothing client kill switch; without it a defective app version cannot be retired                  |
| A6 | **[code]** Review the `PaginationUtil` clamp (`MAX_PAGE_SIZE` 100 000) for public ingress and cap the anonymous-reachable list endpoints                                                                                   | One request returning 100 k rows is an amplification lever that the query timeout bounds only in time                                                |
| A7 | **[code]** Serve `/.well-known/assetlinks.json` on `profit-base.online` with the app's signing-cert SHA-256                                                                                                                | Prerequisite for the verified App Link redirect URI; a custom scheme is claimable by any installed app                                               |
| A8 | **[code]** Observability for the new surface: per-`azp` client counter with an unknown-client alert, auth-failure counter, rate-limit rejection counter (ingest's `basetool_ingest_*` are the model)                       | The "detect" half of the abuse ladder; without it revocation has nothing to act on                                                                   |

A1–A4 and A6 are improvements on their own merits and can land before any exposure decision is
final. A5, A7 and A8 only make sense together with the app.

### Track B — requirements and decisions (code)

| #  |                                                                                                                                                      Work package                                                                                                                                                       |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| B1 | **[code]** ADR: expose `/api/v1` through a dedicated public vhost (with the rejected alternative — an ingest-style gateway — and why it buys no authorisation)                                                                                                                                                          |
| B2 | **[code]** ADR: mobile auth posture — public client, PKCE S256, DPoP refresh-token-only binding via Client Policies, and the fallback ladder                                                                                                                                                                            |
| B3 | **[code]** ADR + REQ-API amendment: the **external contract set** — which endpoints the app consumes become a shipped contract that may only change through `/api/v2` + `@ApiDeprecation`. Today `docs/specs/api-conventions.md` lets frontend-only endpoints change shape in place, which a shipped app cannot survive |
| B4 | **[code]** REQ-SEC amendments: public exposure, trusted-proxy semantics, per-subject limits, client allowlist and kill switch, approved-client-software entry for the app (REQ-SEC-027)                                                                                                                                 |
| B5 | **[code]** REQ-OBS amendment: probes, metrics, alerts and log pipeline for the new surface                                                                                                                                                                                                                              |
| B6 | **[code]** Privacy: extend `privacy.html` and the DE/EN bundles for the new vhost's access log (31-day client-IP retention) and for the Keycloak event log of D1; add the VVT entry                                                                                                                                     |

### Track C — monitoring (code, ships with the exposure PRs)

Blackbox liveness (`http_2xx_or_401`) on the API root plus IPv6 twin · edge-deny probes for
`/actuator/prometheus` and `/actuator/health` · force-SSL and HSTS probes · DNS A/AAAA probes ·
alert rules staged per REQ-OBS-014 with promtool tests · dashboard updates (03, 07, 08) · Alloy
mapping and masking for the new vhost's log stream · extension of the external
`.github/workflows/edge-deny-probe.yml`.

### Track D — infrastructure and Keycloak (owner)

| #  |                                                                                                                                                               Work package                                                                                                                                                               |                                                                     Note                                                                      |
|----|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| D1 | **[owner]** Keycloak realm hardening: enable user events with a bounded `eventsExpiration`, set `sslRequired = external`, create the Client Policies infrastructure (the realm has **none** today), add the realm-wide "S256 required for public clients" policy and clean up `fullScopeAllowed` on the existing public clients          | The Client Policies vehicle is also what D2 needs; the event log is the prerequisite for detecting attacks on the newly public token endpoint |
| D2 | **[owner]** Create the client `basetool-android` per the spec in the app repo's security concept §3 — **test realm first**, production only after E1 passes                                                                                                                                                                              | Includes the `aud=basetool-backend` mapper, which must exist before D5                                                                        |
| D3 | **[owner]** NPM: new vhost `api.profit-base.online` → `https://backend:11261`, backend joins a dual-stack proxy network; the vhost **overwrites** the whole `X-Forwarded-*`/`Forwarded` family, denies `/actuator`, carries a stricter version-controlled budget for the Keycloak token endpoint and per-location `client_max_body_size` | I deliver the snippet contents; the NPM admin database is host state                                                                          |
| D4 | **[owner]** DNS: A/AAAA records for the new host, plus a **CAA** record for `profit-base.online` (Let's Encrypt only)                                                                                                                                                                                                                    | Cheap, unrelated to the app, worth doing anyway                                                                                               |
| D5 | **[owner]** Flip `IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend` — **release gate**, not a later hardening step                                                                                                                                                                                                                        | The vhost must not go live against a backend that accepts audience-less tokens from arbitrary realm clients                                   |
| D6 | **[owner]** Add the app to the approved client software list in the Terms of Use (REQ-SEC-027)                                                                                                                                                                                                                                           | Policy lever against unofficial clients                                                                                                       |

### Track E — verification (test stack, code)

| #  |                                                                                                                                                                                                                                             Work package                                                                                                                                                                                                                                              |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| E1 | **The decisive experiment:** on the isolated test stack, create the refresh-only DPoP client policy and confirm for `basetool-android` that the access token is issued **without** `cnf` (the backend accepts it as a plain Bearer), the refresh token is bound, and a refresh replayed with a different key fails. The entire token posture depends on this; if it fails, the fallback ladder (backend `.dPoP()` support, or plain PKCE as an approved deviation) changes the app's networking layer |
| E2 | Tests for the A1 XFF walk (spoofed chains, trusted and untrusted peers) and for the A3 per-subject limiter                                                                                                                                                                                                                                                                                                                                                                                            |
| E3 | Verify the vhost allowlist behaviour against the test stack before it is applied to production                                                                                                                                                                                                                                                                                                                                                                                                        |

## 3. Order and gates

```
E1 (DPoP verification, test stack)  ──►  B2 (auth ADR)  ──►  D2 (client, test → prod)
A1 A2 A3 A4 A6 (backend hardening)  ──►  C (probes/alerts/dashboards)  ──►  D3 (vhost live)
                                                    D5 (audience flip) ──┘  = release gate
A7 (assetlinks) ──► App Link redirect usable
B1 B3 B4 B5 B6 travel with the PRs that implement them
```

Nothing in Track D runs before its Track A/C counterpart is merged and probed. E1 comes first
because it is cheap, needs no production access, and decides an app-architecture question that
gets expensive to revisit later.

## 4. Anonymous surface — the stance to confirm

The vhost is a **default-deny allowlist**: it proxies only the endpoint families the app consumes
and answers 404 for everything else. Rationale: the anonymous surface is branchy (the `/slim`
twins, guest participant mutations across several verbs, `POST /api/v1/orders/items` with its
table-wide pessimistic lock), so a blocklist misses paths — and any *future* `permitAll` endpoint
added for the web app would otherwise become internet-reachable the day it merges.

The terms/consent endpoints (`/api/v1/terms/**`) and the registration-status read are on the
allowlist from day one; the app's terms gate and `PENDING_APPROVAL` handling depend on them. The
anonymous **write** paths open only when the app's guest mode ships (owner decision Q6: with the
first release), each with its own rate budget and abuse counter.

## 5. Open questions

1. **Hostname** — `api.profit-base.online`, or a different name?
2. **Keycloak event log** (D1) — enabling user events creates a new personal-data store. Retention
   period, and confirmation that the privacy notice extension (B6) is acceptable?
3. **Configuration form** — should the Keycloak client and policies be delivered as an importable
   JSON/`kcadm` script (repeatable, reviewable) or as documented console steps?
4. **Sequencing** — may the Track A hardening PRs land independently and early (they improve the
   current deployment regardless), or should the whole package move as one series?
5. **Test-stack Keycloak** — E1 needs a throwaway realm with the DPoP policy. Confirm that the
   existing `docker-compose.test.yml` stack plus a stripped realm export is the right vehicle
   (never the production realm export — hard repo rule).

## 6. What this plan deliberately does not do

No production access is assumed or requested. Every Track D item is described so that @greluc can
execute it with the exact command and its rollback in hand; nothing in this repository reaches for
the host.
