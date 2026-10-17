# ADR-0270 — The backend accepts tokens only from named clients

- **Status:** Accepted — decided ahead of the change; implemented in #2673.
- **Date:** 2026-10-17
- **Deciders:** @greluc (issue #2638)
- **Related:** [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md)
  (what makes the caller set enumerable) ·
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the first
  authorisation decision taken on `azp`) ·
  [ADR-0202](0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md)
  (the realm shape the list is written against) · specs `REQ-SEC-022`, `REQ-SEC-024`,
  `REQ-OBS-018` · `SecurityConfig`, `JwtAudienceStartupCheck`

## Context

The resource server validates a token's signature, issuer and timestamps, and — when
`app.security.jwt.expected-audiences` is set — that its `aud` intersects the configured set.
`JwtAudienceStartupCheck` refuses to start under the `prod` profile while that property is blank, so
the audience check is fail-closed in production and was a release gate for the public vhost
(ADR-0135).

`aud` answers *"was this token minted for this API?"*. It does not answer *"which client asked for
it?"*. In Keycloak the audience arrives through a mapper on a client's dedicated scope, so any realm
client that carries the `basetool-backend` audience mapper produces tokens this backend accepts, for
every member who authenticates through it. That was tolerable while the realm's client list was
itself the security boundary and a public vhost made the question academic — the app had to be
accepted from the internet anyway.

Two things change it. The caller set becomes small and known (ADR-0268): `basetool-frontend` and
`basetool-ingest-gateway`, with the Keycloak SPI reaching `/internal/**` on a shared secret and no
token at all (REQ-SEC-022). And the realm holds clients that must *not* reach the backend directly —
`basetool-sc-extractor` talks to the gateway, `basetool-android` is being retired — so "which client"
stops being a label and becomes a decision.

The claim is already load-bearing for more than identification. Three places read `azp` today and
act on it: `IngestGatewayProperties.isGatewayClient` decides whether a caller may act for another
member (ADR-0129), `PartialRoleScopeProperties` decides whether a client's token may carry a partial
role scope, and `ApiClientMetricsProperties.isKnownClient` bounds the `client_id` metric label
(REQ-OBS-018). A claim trusted to widen authorisation is a strange thing not to trust to narrow it.

## Decision

**A token is accepted only when its `azp` names a configured client. The list is
`basetool-frontend` and `basetool-ingest-gateway`, and under `prod` an empty list aborts the start.**

- The check is a **token validator on the decoder**, beside the audience validator, not a filter. It
  refuses before any authentication is built, so nothing downstream can read a caller the gate would
  have rejected, and no filter ordering has to be maintained to keep that true.
- **Both checks stay, because they answer different questions.** `aud` says the token was minted for
  this API; `azp` says which client asked for it. A client that is given the audience mapper by
  accident — the realm is converged by a provisioner that never deletes, so an accident persists — is
  still refused.
- **A token with no `azp` is refused.** Keycloak sets it on every token it issues to a client,
  including a service-account token, so an absent claim is not a shape this deployment produces.
- **Empty means refuse, not "disabled".** The audience property treats blank as "no check" outside
  `prod` for historical reasons; this one does not get that door. A blank list under `prod` aborts
  the start with the message naming the property, exactly as `JwtAudienceStartupCheck` does.
- **`/internal/**` is out of scope and stays out.** It carries no token; its gate is the constant-time
  shared-secret header of REQ-SEC-022. The Prometheus scrape chain is HTTP basic and is not touched
  either.

## Consequences

**Adding a client to the realm no longer grants API access.** A new caller is a reviewed change to
the release's configuration, in the same commit as whatever needs it. That is the whole point: the
realm is converged by a provisioner, and a provisioner run is not a code review.

**The failure mode is a refused start, not a refused request.** A mis-set list under `prod` takes the
backend down at boot rather than returning 401 to every member, which is the shape the audience check
already has and the shape ADR-0202 argues for — a realm-coupled misconfiguration should be loud where
it is cheap. It also means the list has to be right in the config bundle before the deploy, and the
rollback is the previous bundle.

**One more thing couples the backend's config to the realm's client ids.** A client rename is now a
config change in two repositories' worth of artifacts. The ids are stable and few, and the
alternative — inferring the caller from something softer — is how `aud` ended up proving less than
people assumed.

**Attribution and authorisation now read the same claim for different purposes**, and the metric
label stays bounded independently (`isKnownClient`), so an unlisted `azp` cannot both be refused and
create an unbounded label.

**A member's own token cannot be replayed from a client we did not name.** If a token leaks, it is
only usable through a client whose `azp` is already accepted — which does not stop the leak, and does
remove the case where an attacker-registered or forgotten realm client is the vehicle.

## Alternatives considered

*Rely on the audience check alone.* It is already there and already fail-closed, and it proves the
wrong thing: an audience is a property of the mapper configuration, shared by every client that has
it. The public vhost made this acceptable by making the caller set open; closing the surface is
exactly when the second question becomes answerable.

*Model per-client scopes or roles instead of an allow-list.* The more expressive option, and it
answers a question nobody is asking: what each client may *call* is already decided per member in the
service layer, by `@PreAuthorize` and `OwnerScopeService`. A second, client-shaped copy of that is
ADR-0135's "second copy of an authorisation rule" argument, one layer down.

*Check `azp` in a filter after authentication.* Simpler to write and strictly worse: the
authentication exists by then, every filter ordered before the check sees it, and the invariant
becomes an ordering property that a future `addFilterAfter` can break silently.

*mTLS between the three apps instead.* It would prove the *network* peer, which the private networks
and ADR-0272's trust narrowing already settle, and it would add a third credential to rotate for a
guarantee about transport rather than about which client minted the token.
