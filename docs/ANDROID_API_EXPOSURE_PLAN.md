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

| #  |                                                                                                                                                                                             Work package                                                                                                                                                                                             |                                                               Why it blocks the vhost                                                                |
|----|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| A1 | **[code]** Right-to-left `X-Forwarded-For` walk in `RateLimitingFilter`, modelled on the frontend's `ClientIpContextFilter`; narrow `app.rate-limit.trusted-proxies` from `172.28.0.0/16` to the API vhost's proxy network                                                                                                                                                                           | Without it a client-chosen leftmost entry mints a fresh bucket per request — rate limiting becomes decorative and foreign IPs get framed in the logs |
| A2 | ✅ **Done — backend Actuator moved to the internal-only management port 11271** (ADR-0134, extending ADR-0090, which had excluded the backend precisely because it was not internet-reachable). The permit-all chain is deliberately narrower than frontend/ingest — it enumerates the read endpoints — so `POST /actuator/loggers/**` keeps its `ROLE_ADMIN` gate instead of the write being deleted | The connector the vhost proxies must serve no Actuator at all; the edge deny stays as the second layer, not the only one                             |
| A3 | **[code]** Per-`sub` rate limiting behind authentication (service-level, ingest `SubjectRateLimiter` as the model) for the app-consumed write families and the SSE connect                                                                                                                                                                                                                           | CGNAT puts many members behind one IPv4; a per-IP bucket either throttles innocents or is set so loose it stops mattering                            |
| A4 | **[code]** `Cache-Control: private, no-store` on the sensitive GET families (bank, member PII, notifications) — `ApiCacheControlFilter` emits only `no-cache, must-revalidate` today                                                                                                                                                                                                                 | Storage-with-revalidation is allowed by the current header; a public ingress makes intermediaries plausible                                          |
| A5 | **[code]** Minimum-app-version gate: the app sends `User-Agent: basetool-android/<semver>`, the backend refuses versions below a configured floor with a dedicated RFC 7807 code                                                                                                                                                                                                                     | The only lever between "do nothing" and the all-or-nothing client kill switch; without it a defective app version cannot be retired                  |
| A6 | **[code]** Review the `PaginationUtil` clamp (`MAX_PAGE_SIZE` 100 000) for public ingress and cap the anonymous-reachable list endpoints                                                                                                                                                                                                                                                             | One request returning 100 k rows is an amplification lever that the query timeout bounds only in time                                                |
| A7 | **[code]** Serve `/.well-known/assetlinks.json` on `profit-base.online` with the app's signing-cert SHA-256                                                                                                                                                                                                                                                                                          | Prerequisite for the verified App Link redirect URI; a custom scheme is claimable by any installed app                                               |
| A8 | **[code]** Observability for the new surface: per-`azp` client counter with an unknown-client alert, auth-failure counter, rate-limit rejection counter (ingest's `basetool_ingest_*` are the model)                                                                                                                                                                                                 | The "detect" half of the abuse ladder; without it revocation has nothing to act on                                                                   |

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

| #  |                                                                                                                                                                                                                                                             Work package                                                                                                                                                                                                                                                             |                                                                     Note                                                                      |
|----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| D1 | **[owner]** Keycloak realm hardening: enable user events with a bounded `eventsExpiration`, set `sslRequired = external`, create the Client Policies infrastructure (the realm has **none** today), add the realm-wide "S256 required for public clients" policy and clean up `fullScopeAllowed` on the existing public clients                                                                                                                                                                                                      | The Client Policies vehicle is also what D2 needs; the event log is the prerequisite for detecting attacks on the newly public token endpoint |
| D2 | ✅ **Done, 2026-08-17 — applied to the production `iri` realm** with `scripts/provision-keycloak-mobile-client.py` after E1 passed and a full rehearsal against a throwaway Keycloak 26.7 stood in for the test realm. Both client-policy lists were empty beforehand and were captured as the rollback basis; the independent `--verify-only` pass is clean. The client `basetool-android` now exists with the marker role, the `aud=basetool-backend` mapper and `offline_access` withheld — unused until the vhost of D3 goes live | Includes the `aud=basetool-backend` mapper, which must exist before D5                                                                        |
| D3 | **[owner]** NPM: new vhost `api.profit-base.online` → `https://backend:11261`, backend joins a dual-stack proxy network; the vhost **overwrites** the whole `X-Forwarded-*`/`Forwarded` family, denies `/actuator`, carries a stricter version-controlled budget for the Keycloak token endpoint and per-location `client_max_body_size`                                                                                                                                                                                             | I deliver the snippet contents; the NPM admin database is host state                                                                          |
| D4 | **[owner]** DNS: A/AAAA records for the new host, plus a **CAA** record for `profit-base.online` (Let's Encrypt only)                                                                                                                                                                                                                                                                                                                                                                                                                | Cheap, unrelated to the app, worth doing anyway                                                                                               |
| D5 | **[owner]** Flip `IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend` — **release gate**, not a later hardening step                                                                                                                                                                                                                                                                                                                                                                                                                    | The vhost must not go live against a backend that accepts audience-less tokens from arbitrary realm clients                                   |
| D6 | **[owner]** Add the app to the approved client software list in the Terms of Use (REQ-SEC-027)                                                                                                                                                                                                                                                                                                                                                                                                                                       | Policy lever against unofficial clients                                                                                                       |

### Track E — verification (test stack, code)

| #  |                                                                                                                                                                                                                                                                                                                                                                                   Work package                                                                                                                                                                                                                                                                                                                                                                                    |
|----|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| E1 | ✅ **Done, 2026-08-17 — refresh-only binding works; full result and its four landmines in section 7.** The experiment was a first, not a repeat: production carries zero client profiles and zero policies, and the extractor deliberately needs none — since ADR-0129 it *wants* both tokens bound, because the gateway validates the proof at the hop that consumes it. The app is the opposite case: it talks to the backend directly, and Spring Security's bearer filter rejects a `cnf`-bound access token outright. Outcome: in the authorization-code flow the access token comes back as a plain `Bearer` without `cnf`, the refresh token is bound, and a refresh with a wrong key or no proof is refused. The fallback ladder stays unused; **B2 and D2 are unblocked** |
| E2 | Tests for the A1 XFF walk (spoofed chains, trusted and untrusted peers) and for the A3 per-subject limiter                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| E3 | Verify the vhost allowlist behaviour against the test stack before it is applied to production                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |

## 3. Order and gates

```
E1 (DPoP verification, test stack)  ──►  B2 (auth ADR)  ──►  D2 (client, test → prod)
A1 A2 A3 A4 A6 (backend hardening)  ──►  C (probes/alerts/dashboards)  ──►  D3 (vhost live)
                                                    D5 (audience flip) ──┘  = release gate
A7 (assetlinks) ──► App Link redirect usable
B1 B3 B4 B5 B6 travel with the PRs that implement them
```

Nothing in Track D runs before its Track A/C counterpart is merged and probed. E1 came first
because it is cheap, needs no production access, and decides an app-architecture question that
gets expensive to revisit later — **it has since passed** (section 7), so the gate it held is open
and the app's networking layer can be built against plain Bearer access tokens.

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

## 5. Decisions (owner, 2026-08-17)

| # |                                                                                  Decision                                                                                  |
|---|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | **Hostname: `api.profit-base.online`** — its own vhost with its own rate limits, probes and deny rules, separately switchable from the frontend                            |
| 2 | **Keycloak event log stays off for now** — see the accepted risk below                                                                                                     |
| 3 | **Keycloak configuration ships as a versioned `kcadm` script**, built against a **fresh sanitized export** of the current production realm rather than against assumptions |
| 4 | **Track A hardening lands early and as individual PRs** — each improves the current deployment on its own merits, independent of the exposure decision                     |
| 5 | **E1 runs on the existing `docker-compose.test.yml` stack** with a stripped realm export and throwaway credentials (never production artefacts — hard repo rule)           |

### Accepted risk: no Keycloak event log

Decision 2 leaves the realm without login-failure, token-error and client-disable events. The
consequence, stated plainly: **on the newly public token endpoint, failed authentication is
invisible at the identity provider.** The abuse ladder of the app's security concept keeps its
"raise cost", "throttle" and "revoke" rungs, but loses one of its "detect" sources.

What still covers part of that gap:

- Keycloak's brute-force protection stays on and locks accounts regardless of logging.
- The edge budget for the token endpoint (D3) throttles volumetric abuse before Keycloak sees it,
  and edge rejections are visible in the NPM access log and the `EdgeRateLimitSpike` alert.
- The backend-side counters of A8 see authentication failures at the **API** — a token that fails
  validation, an unknown `azp`, a rate-limit rejection. What they cannot see is an attack that
  never gets past Keycloak, for example password spraying against the token endpoint.

Revisit if the public surface ever shows abuse the API-side counters cannot explain. Because this
is a deliberate deviation from the app security concept's §2.11 recommendation, it is recorded
there as well.

## 6. Realm export handling

The `kcadm` script of decision 3 needs the current production realm, and a raw export carries
operator secrets (SMTP credentials, identity-provider secrets, the service-account user). The
split follows the production-access rule of `CLAUDE.md`: **the export is the owner's to take, the
sanitization is automated, and only the sanitized result is ever read or committed.**

1. @greluc exports the `iri` realm from the Admin Console (partial export including clients,
   groups and roles) to a local file **outside the repository**.
2. `python scripts/sanitize-realm-export.py RAW.json docs/keycloak/realm-config.reference.json`
   applies the list documented in `docs/keycloak/README.md`: secrets to `__SET_AT_DEPLOY__`, SMTP
   block replaced, public hostnames neutralized, users, realm keys, flows and Keycloak's built-in
   clients dropped.
3. The script **refuses to write anything** when a guard pattern — an e-mail address, a real
   hostname, an unreplaced secret value — survives; it reports counts and labels, never the
   matched text. A missed secret therefore fails loudly instead of landing in a commit.
4. The raw export is deleted afterwards. It never enters the repository, a transcript or a diff.

## 7. E1 result — refresh-only DPoP binding works (verified 2026-08-17)

**Verdict: the app can keep plain Bearer access tokens.** In the authorization-code flow the app
actually uses, Keycloak 26.7 issues `token_type: Bearer` with **no `cnf` claim** on the access
token while the refresh token carries `cnf.jkt`. The backend needs no change, and the fallback
ladder is not required.

The configuration that produces it — all three parts are load-bearing:

|                    Part                     |                                  Value                                  |
|---------------------------------------------|-------------------------------------------------------------------------|
| Client profile executor                     | `dpop-bind-enforcer`                                                    |
| Executor configuration                      | `allow-only-refresh-token-binding: true`, the other two options **off** |
| Policy condition                            | `client-roles` with a marker **client role** on `basetool-android`      |
| Client attribute `dpop.bound.access.tokens` | **`false` or absent** — see landmine 2                                  |

Measured behaviour, authorization code + PKCE S256:

|                  Case                   |                            Result                             |
|-----------------------------------------|---------------------------------------------------------------|
| Login with a proof                      | `Bearer`, access **unbound**, refresh **bound**               |
| Token exchange without a proof          | refused — `invalid_grant`, "DPoP proof is missing"            |
| Refresh with the proof                  | `Bearer`, access unbound, refresh stays bound across rotation |
| Refresh with a **different** key        | refused — "DPoP confirmation doesn't match DPoP proof"        |
| Refresh with **no** proof               | refused — `invalid_dpop_proof`                                |
| `dpop_jkt` on the authorization request | optional; accepted, and worth sending as defence in depth     |
| Client **without** the marker role      | unaffected — still gets bound tokens, proof still optional    |

### Four landmines this experiment surfaced

1. **Never verify this with a direct grant.** Under ROPC the same realm binds the access token on
   the initial grant and only narrows it from the first refresh onward, and a proof-less ROPC login
   is accepted outright. An earlier interim reading of this plan's question was wrong for exactly
   that reason. The production client keeps `directAccessGrantsEnabled = false`.
2. **While the policy is attached, every admin edit to the client is refused** with
   `invalid_client_metadata` / "DPoP token is disabled" — including edits as harmless as a
   description, and including removing the attribute. Provisioning order is therefore: create and
   fully configure the client **first**, attach the policy **last**. Any later change to the client
   requires detaching the policy, editing, and re-attaching — the `kcadm` script must do it in that
   order and verify the re-attach.
3. **The refresh-only profile and the per-client "require DPoP" switch do not compose.** Setting
   `dpop.bound.access.tokens = true` re-binds the access token even on refresh, and
   `enforce-authorization-code-binding-to-dpop` requires that switch. A DPoP-bound authorization
   code and an unbound access token are therefore mutually exclusive in Keycloak 26.7. The residual
   gap this leaves is small: the token exchange still demands a proof, so the code cannot be
   redeemed without the key.
4. **Keycloak's own `/userinfo` breaks for clients under this policy.** Called without a proof it
   answers **HTTP 500** (`IllegalArgumentException: Unrecognized OAuth 2.0 error:
   invalid_dpop_proof`) instead of a 401 — a Keycloak bug in `UserInfoEndpoint.issueUserInfo`. The
   backend is unaffected because it validates JWTs locally against the JWKS, but **the app must
   take profile claims from the ID token and never call `/userinfo`.**

### The fallback, confirmed available but not needed

Spring Security's `BearerTokenAuthenticationFilter` does reject a `cnf.jkt`-bearing token on the
plain Bearer path, deliberately ("prevent downgraded usage of DPoP-bound access tokens"), after the
JWT has otherwise validated. Full DPoP resource-server support exists since Spring Security **6.5**
(servlet only; no WebFlux equivalent) and is **auto-enabled** whenever `oauth2-jose` is on the
classpath — there is no DSL to switch on, and equally no supported seam to customise the `htu`
comparison, which is plain string equality against `getRequestURL()`. Should the posture ever have
to change, that is the rung to take; it needs `ForwardedHeaderFilter` to line the URL up behind the
proxy, and it cannot survive a relay hop.

### Deviation from decision 5, disclosed

The experiment ran against a **throwaway `quay.io/keycloak/keycloak:26.7` container** on port
18099, not the `docker-compose.test.yml` stack that decision 5 named. Reason: the question is pure
Keycloak realm behaviour, and the test stack's `.env.test`, local keystore and stripped realm export
do not exist in this worktree. No production artefact, credential or export was involved — realm,
client, marker role and user were created by the script — and the container was removed afterwards.
Everything above is reproducible from a bare Keycloak image.

## 8. What this plan deliberately does not do

No production access is assumed or requested. Every Track D item is described so that @greluc can
execute it with the exact command and its rollback in hand; nothing in this repository reaches for
the host.
