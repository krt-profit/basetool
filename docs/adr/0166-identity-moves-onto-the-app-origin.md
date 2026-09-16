# ADR-0166 — Identity moves onto the app origin, so the installed app can sign in

- **Status:** Accepted — implemented (owner decision 2026-09-13)
- **Date:** 2026-09-13
- **Deciders:** @greluc (the decision and the no-fallback call), Claude (implementation)
- **Related:** [ADR-0164](0164-an-installable-web-app-without-a-service-worker.md) (which predicted
  exactly this ADR) · [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) (the edge
  this is built on) · specs [`ui-design-system.md`](../specs/ui-design-system.md) `REQ-UI-020`,
  [`security-and-access.md`](../specs/security-and-access.md) `REQ-SEC-052`

## Context

ADR-0164 made the web app installable to a home screen, and named the one thing it could not
resolve:

> `scope` is the app origin and cannot be anything else, but both `/oauth2/authorization/keycloak`
> and the logout redirect navigate to the Keycloak origin. […] If it fails, the fix is
> reverse-proxying Keycloak onto the app origin at the edge — a separate ADR.

This is that ADR, and it was taken **without** waiting for the device test. The cost of being wrong
is asymmetric: the change is a configuration move behind a CI-gated `nginx -t`, while what it
removes is a failure mode that presents to a member as "the tool is broken" and to us as nothing at
all.

**The mechanism, checked against the current platform documentation rather than assumed.** The first
draft of this ADR said the installed login was broken on iOS. That is too strong, and the accurate
version is the better argument.

A manifest `scope` is a single URL prefix, so it cannot span two origins — there was never a way to
write one that covered both the app and a Keycloak on a host of its own. Apple's own guidance
(WWDC23, *What's new in web apps*) then says what happens to a navigation that leaves it:

> In Home Screen web apps on iOS, links outside the scope will open in Safari View Controller.

…with one carve-out, and the carve-out is the whole point:

> Authentication through OAuth on a third-party domain will still open in your web app. **This is
> done through heuristics.** If you test and find that your OAuth flow opens the authentication
> experience in the user's default browser, please send us feedback using apple.com/feedback.

So the login probably worked. It worked **by heuristic**, on a platform whose vendor asks to be told
when the heuristic misfires and is — in the same talk — "engaging with standards bodies to offer
developers a way to indicate that links to authentication domains should stay within the context of
the web app", which is the plainest possible statement that no declarative mechanism exists yet. A
Safari View Controller has its own storage, so when the heuristic does misfire the PKCE verifier and
`state` Spring Security wrote before the hop are in the wrong jar and the member sees a login that
fails for no visible reason.

That is what this ADR removes: not a known breakage, but a dependency on an undocumented heuristic
for the single flow every member has to pass through, on the platform the installable app exists
for. The repository already documented the second navigation in its own words, in
`SecurityHeaders.java` — *"exactly one form submits cross-origin: the POST `/logout`, whose success
redirect targets Keycloak's `end_session_endpoint`"* — which is why the frontend CSP carries the
Keycloak origin in `form-action` at all. The logout redirect is **not** an OAuth authorization
request, so the heuristic above has no reason to cover it at all.

## Decision

**Keycloak serves at `/auth` on the web host, and nowhere else.**

- Keycloak serves under that path itself: `KC_HTTP_RELATIVE_PATH=/auth`. The edge proxies `/auth` on
  the web vhost through **without rewriting the prefix**, so the path Keycloak is asked for is the
  path it is mounted at.
- `KC_HOSTNAME` carries the path too — `https://<web host>/auth`, not the bare origin. **The two
  settings are a pair, and this was measured rather than argued**, because reading the upstream
  reverse-proxy guide alone produces the wrong answer: it presents three ways to live under a proxy
  sub-path as *alternatives*, which reads as "pick one". They describe proxy shapes. What actually
  decides the advertised URLs is the hostname's path component, and it does not double when the
  relative path agrees with it. Keycloak 26.7 was run with each combination and its discovery
  document read back (2026-09-13):

  |    `KC_HOSTNAME`    | `KC_HTTP_RELATIVE_PATH` | serves at |      advertised issuer       |                         |
  |---------------------|-------------------------|-----------|------------------------------|-------------------------|
  | `https://host`      | `/auth`                 | `/auth`   | `https://host/realms/…`      | **broken**              |
  | `https://host/auth` | `/auth`                 | `/auth`   | `https://host/auth/realms/…` | **correct**             |
  | `https://host/auth` | *(unset)*               | `/`       | —                            | needs a stripping proxy |

  The first row is the trap, and it is silent: Keycloak answers on `/auth` and hands out root links,
  so the server looks healthy, the discovery document parses, and the login dies at the first
  redirect with nothing wrong in any log. A first draft of this ADR specified exactly that
  combination on the strength of the guide's wording; the measurement is what caught it.

  The test stack uses a **bare** hostname instead, and that combination was measured too rather than
  assumed: `KC_HOSTNAME=host.docker.internal` with `KC_HTTP_RELATIVE_PATH=/auth` advertises
  `http://host.docker.internal:18080/auth/realms/…`, answers 404 at the root, and keeps
  `:9000/health/ready` at 200. Scheme, port **and context path** all come from the request when no
  full URL is configured — which is also why `KC_HOSTNAME_PORT` could be dropped: it is not an
  option of Keycloak 26 at all, and the port was never coming from it.

  The e2e override is the exception and needs the full URL for a documented reason of its own
  (`docker-compose.e2e.yml`: KC 26 refused the browser's plain-HTTP auth request under a bare
  hostname). It therefore carries the path as well — and getting that wrong is precisely how the
  first attempt failed.

- **The management interface stays at the root**, `KC_HTTP_MANAGEMENT_RELATIVE_PATH=/`. Left alone,
  `http-relative-path` takes port 9000's endpoints with it, which would have moved `/health/ready`
  (this stack's container healthcheck) and `/metrics` (Prometheus's `keycloak` job) to `/auth/…` and
  broken both at the next deploy, silently and for a reason nobody would look for in a PWA change.
  Pinning it confines the path change to the public surface, which is the only place that needs it.
  Verified on the pinned image: `:9000/health/ready` answers 200 and `:9000/auth/health/ready` 404.

- `KC_HOSTNAME_STRICT` is kept at `true` and does nothing while `KC_HOSTNAME` is set — upstream
  states it is ignored once a hostname is configured. It stays so that clearing `KC_HOSTNAME` in
  some future environment cannot silently re-enable header-derived hostnames.

- The issuer becomes `https://<web host>/auth/realms/iri` everywhere it is configured: backend,
  frontend, ingest, Grafana's OIDC login, the blackbox discovery probes and the Android app.

**The old `keycloak.` host is retired outright — there is no second path.** Owner decision: the
Android app is in the hands of testers only, who install the new build directly, so nothing is owed
to installs pinned to the old issuer. Keeping the old vhost alive as a fallback would mean two
origins minting tokens for one realm, two certificates, and a second surface to keep hardened — for
a compatibility window nobody needs. Its vhost template, its `EDGE_HOST_KEYCLOAK` variable, its
certificate SAN and its force-SSL probe target are all deleted rather than commented out.

The admin console moves with it, to `/auth/admin`, keeping the same bridge-gateway allow-list and
the same closing `deny all` — the control is unchanged, only its address.

## Consequences

**What this fixes.** Every navigation of both flows is now same-origin: `/oauth2/authorization/…` →
`/auth/realms/iri/protocol/openid-connect/auth` → the login form → `/login/oauth2/code/keycloak`, and
the logout equivalent. All of it is inside `scope: "/"`, so an installed app stays in its own window
throughout. `REQ-UI-020`'s open question is closed by construction rather than by a device report.

**The CSP simplifies itself.** `SecurityHeaders` derives the `form-action` origin from the configured
issuer, so it now resolves to the app's own origin and the directive collapses to the equivalent of
`'self'`. No code change was needed for that, which is the sign the original was written correctly:
it expressed the *rule*, not the hostname.

**Keycloak now receives the app's session cookie.** Same origin means the `SESSION` cookie, scoped to
`/`, is sent on every `/auth/**` request. This is recorded rather than mitigated, and deliberately:
Keycloak already holds every member's credentials and issues their tokens, so a session identifier it
ignores adds nothing to what a compromised Keycloak could already do. Stripping it at the edge would
mean hand-filtering a `Cookie` header with an nginx `map` — a fragile rule in front of the login path,
bought for no reduction in blast radius.

**This is a cutover, not a rolling change.** It invalidates nothing stored, but the moment the issuer
moves, every token minted under the old one fails validation and every session tied to it ends. The
sequence, and the fact that the Android app must ship the matching build, are in
[`docs/deployment.md`](../deployment.md); the old DNS record can be retired once the new path answers.

**Production is still the only place the full arrangement exists.** The test stack has no edge, so it
cannot reproduce the same-*origin* half — it runs Keycloak on its own port as before. It does run the
same `/auth` **path**, which is the half that configuration gets wrong, and the e2e login exercises it
on every run. The remaining gap is the edge routing itself, covered by `nginx -t` in
`scripts/check-edge-nginx.sh` and by the external deny probe, not by an end-to-end login.

## Alternatives considered

- **Wait for a device test.** The honest option, and rejected on cost asymmetry (see Context). The
  test is still worth doing after the cutover, as confirmation rather than as a gate.
- **Keep both origins during a transition.** Explicitly refused by the owner. Two issuers for one
  realm is a token-validation puzzle, not a safety net.
- **Give the manifest a `scope` that spans both origins.** Not possible: the spec requires
  same-origin, and browsers ignore a scope that is not a prefix of `start_url`.
- **Move only the login and leave the rest of Keycloak where it was.** The account console and the
  end-session endpoint are navigations too, and splitting a realm across two origins is the same
  puzzle as the alternative above with extra steps.
- **Put Keycloak at the web host's root instead of under `/auth`.** It would need `/realms`,
  `/resources` and `/js` carved out of the app's own namespace at the edge, forever, and any future
  app route that collides silently shadows an identity route.
