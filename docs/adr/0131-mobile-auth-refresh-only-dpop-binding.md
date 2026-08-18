# ADR-0131 — The mobile client binds only its refresh token to DPoP

- **Status:** Proposed
- **Date:** 2026-08-17
- **Related:** spec `REQ-SEC-030` ([`security-and-access.md`](../specs/security-and-access.md)) ·
  `REQ-SEC-012` (refresh-token rotation is off realm-wide) ·
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the opposite case) ·
  [`ANDROID_API_EXPOSURE_PLAN.md`](../ANDROID_API_EXPOSURE_PLAN.md) section 7 · script
  `scripts/provision-keycloak-mobile-client.py`

## Context

The native Android app is a **public** OAuth client: it ships in an open-source APK, so it has no
secret, and RFC 9700 §2.2.2 is unambiguous about what follows — *"refresh tokens for public clients
MUST be sender-constrained or use refresh token rotation."*

Rotation is not available to us. `revokeRefreshToken = false` is a **realm** setting and a
deliberate REQ-SEC-012 amendment: rotation broke the frontend's BFF sessions under concurrent
refresh. Turning it on for the app turns it on for the web app too. That leaves sender-constraining,
which for Keycloak means DPoP (RFC 9449).

The obvious way to switch DPoP on is the per-client toggle **"Require DPoP bound tokens"**. It binds
**both** tokens, and that breaks us at the other end: Spring Security's
`BearerTokenAuthenticationFilter` explicitly refuses an access token carrying `cnf.jkt` when it
arrives on the plain Bearer path — *"prevent downgraded usage of DPoP-bound access tokens"* — after
the JWT has otherwise validated. Every API call from the app would 401.

So the question the whole mobile token design rested on was narrow and empirical: **can Keycloak
bind only the refresh token?** The documentation says a client-policy executor exists for it. There
was **no precedent in this realm** — production carries zero client profiles and zero policies — and
the desktop extractor is not one either: since ADR-0129 its gateway validates the proof at the hop
that consumes the token, so binding *both* tokens is the wanted state there.

Experiment E1 (2026-08-17, against a throwaway Keycloak 26.7) answered it. The measurements and the
reproducible configuration are in the exposure plan's section 7; what matters here is the verdict
and the four constraints that came with it.

## Decision

**The mobile client uses DPoP with refresh-token-only binding, scoped to that one client by a marker
client role. Access tokens stay plain Bearer, and the backend is not changed.**

Concretely, three settings that are each load-bearing:

1. A client profile `krt-mobile-dpop` whose only executor is `dpop-bind-enforcer` with
   `allow-only-refresh-token-binding: true`.
2. A policy `krt-mobile-dpop-policy` scoped by the `client-roles` condition naming a marker client
   role `dpop-refresh-only`. Keycloak has **no** condition that names clients directly; the marker
   role is the documented way to confine a policy to one client, and it was verified to confine it —
   a control client without the role keeps the default behaviour.
3. The per-client attribute `dpop.bound.access.tokens` pinned to `false`.

In the authorization-code flow this yields `token_type: Bearer` with **no `cnf`** on the access
token and a **bound** refresh token; a refresh without a proof or with a different key is refused,
and the binding survives token rotation. The app therefore holds a per-install P-256 key in the
Android Keystore, sends a DPoP proof on **token endpoint calls only**, and never on an API call.

### What the measurements forced into the design

**The direct grant lies.** Under ROPC the same realm binds the access token on the initial grant and
narrows it only from the first refresh onward, and a proof-less ROPC login is accepted outright.
Reading the result off a direct grant produces the opposite verdict to the flow the app uses. The
client therefore keeps `directAccessGrantsEnabled = false`, and this decision must never be
re-validated through a password grant.

**The two DPoP switches do not compose.** Setting `dpop.bound.access.tokens = true` overrides the
profile and re-binds the access token even on refresh — and it is also the prerequisite of
`enforce-authorization-code-binding-to-dpop`. A DPoP-bound authorization code and a plain Bearer
access token are mutually exclusive in Keycloak 26.7. We take the unbound access token; the residual
gap is small because the token exchange still demands a proof, so a stolen code cannot be redeemed
without the key. The RFC 9449 §10 `dpop_jkt` parameter is accepted on the authorization request and
is sent anyway as defence in depth.

**The policy freezes the client.** While it is attached, Keycloak refuses **every** admin update to
that client with `invalid_client_metadata: DPoP token is disabled` — down to a description change,
and including removing the attribute. Provisioning must therefore configure the client first and
attach the policy last, and any later edit needs detach → edit → re-attach.

**Keycloak's own `/userinfo` breaks under this policy.** Called without a proof it answers **HTTP
500** (`IllegalArgumentException: Unrecognized OAuth 2.0 error: invalid_dpop_proof`) rather than a
401. The backend is unaffected — it validates JWTs locally against the JWKS and never calls
userinfo — but the app takes its profile claims from the ID token.

## Consequences

**No backend change, and that is the point.** The resource server keeps its plain Bearer path. Had
the answer gone the other way, the app's whole networking layer plus a backend security-config
change would have been in scope.

**The realm gains its first client policy.** The two client-policy endpoints are realm-global lists
that are **replaced wholesale** on write, so a careless edit deletes an unrelated policy silently.
That is why the configuration ships as `scripts/provision-keycloak-mobile-client.py`, which merges
by name rather than overwriting, applies the operations in the order above, and verifies the result.
It was run end-to-end against a throwaway Keycloak 26.7, and its merge and ordering behaviour is
pinned by `scripts/provision-keycloak-mobile-client.test.sh`, which runs in CI without Docker.

**Operator work, and a trap that will bite.** The frozen-client behaviour is genuinely surprising:
the next person to change a redirect URI in the Admin Console will get a metadata error that names
DPoP for no obvious reason. The script's detach → edit → re-attach path is the supported route, and
the failure mode is safe — an interrupted run leaves the client unbound rather than half-bound, and
re-running finishes the job.

**A stolen refresh token is worth nothing off-device; a stolen access token is worth five minutes.**
That asymmetry is the deal we are accepting. The long-lived credential is hardware-bound; the
short-lived one is a bearer token with a 300 s lifespan, an `aud=basetool-backend` restriction and
per-subject rate limits behind it.

**Clock drift becomes a login failure mode.** Keycloak accepts a proof lifetime of 10 s with 15 s
skew, which is tighter than ordinary mobile clock drift, so the app computes the proof `iat` from
server time tracked via the `Date` header — the desktop extractor documents the same as its primary
DPoP failure mode.

**This decision is scoped to one client on purpose.** The realm-wide "S256 for public clients"
policy of Phase 0 is a separate change with realm-wide blast radius and is deliberately not carried
by the same script.

## Alternatives considered

*Bind both tokens and teach the backend DPoP.* Spring Security has shipped servlet-side
resource-server DPoP since **6.5**, auto-enabled whenever `oauth2-jose` is on the classpath. Viable,
and it is the fallback if the posture ever has to change — but it costs a backend security change, a
proof on **every** API call, and it brings constraints of its own: the `htu` check is plain string
equality against `getRequestURL()` with no supported customisation seam, needing
`ForwardedHeaderFilter` to line up behind the proxy, and there is no reactive equivalent. Rejected
because refresh-only achieves the RFC 9700 requirement without touching the server at all.

*Refresh-token rotation instead of sender-constraining.* Rejected: the toggle is realm-wide and was
turned off deliberately (REQ-SEC-012) because it broke the frontend's BFF sessions.

*Plain PKCE with short sessions and revocation levers.* Rejected as the primary posture: it leaves
the refresh token a pure bearer credential on a device, which is exactly what RFC 9700 §2.2.2
forbids for public clients. It remains the documented deviation of last resort, and would need owner
sign-off.

*Scope the policy with a client-name condition.* Not available — Keycloak offers no condition that
names clients directly. The marker client role is the documented substitute.
