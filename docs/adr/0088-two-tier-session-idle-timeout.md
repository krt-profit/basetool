# ADR-0088 — Two-tier session idle timeout: short for anonymous, 30 days after login

- **Status:** Accepted
- **Date:** 2026-07-10
- **Deciders:** @greluc
- **Related:** `RedisSessionConfig` · `SessionLifetimeUpgradeSuccessHandler` · `SecurityConfig#oauth2LoginSuccessHandler` · `ActiveSessionsTracker` / `SessionMetricsConfig` (`basetool_active_sessions`) · `application.yml` (`app.session.*`, `server.servlet.session`) · REQ-SEC-025 · REQ-SEC-010 · REQ-OBS-011 · ADR-0085 · #1188

## Context

Prod monitoring showed `basetool_active_sessions` climbing monotonically — ~10 000 → 16 300 in ~24 h,
surviving frontend restarts — while only ~30 members were actually logged in. Inspection of the Redis
session store confirmed the leak was real, not a metric artefact:

- **16 375** `basetool:session:sessions:*` hashes vs **31** principal-index keys.
- A 200-session sample: **200/200 orphans** — each carrying only
  `sessionAttr:…HttpSessionCsrfTokenRepository.CSRF_TOKEN`, no `SPRING_SECURITY_CONTEXT`.
- Every orphan `maxInactiveInterval = 2592000` (30 days), TTL ~29.9 days.

Root cause: with `@EnableRedisIndexedHttpSession` and a single 30-day idle window
(`server.servlet.session.timeout = 720h`, applied to the repository default by `RedisSessionConfig`),
**every** session Spring Security minted lived 30 days — including the throwaway sessions created for
**un-authenticated** traffic. The dominant source is the CSRF token: when an anonymous client renders
a form-bearing permit-all page (`/`, `/missions`, …), Spring materialises a `CsrfToken` and persists
it into a new `HttpSession`. Non-interactive clients (uptime/blackbox probes, crawlers) hammer such
pages without keeping cookies, so each hit minted a fresh 30-day Redis session. At the observed
~4–5/min that is ~5 700 orphans/day, accreting toward the Redis `maxmemory 384mb` /
`maxmemory-policy noeviction` ceiling — where the store stops accepting writes and login /
token-refresh (which write to Redis) begin to fail.

ADR-0085 preserved the 30-day rolling login by **sizing Redis up** rather than cutting the TTL. That
addressed authenticated volume, but the actual growth was **anonymous orphan** sessions, which sizing
alone cannot bound — it is unbounded in time. A structural fix is needed that keeps the 30-day
"stay logged in" UX for real members while denying anonymous traffic a 30-day footprint.

Options considered:

1. **Cookie-based CSRF (`CookieCsrfTokenRepository`)** — considered and **rejected** (@greluc,
   2026-07-10). It removes the dominant session-creation source entirely (the token lives in a
   cookie, not a session), but (a) it is a broad change to the central CSRF transport every write
   path depends on, coordinated with the `krtCsrf` / `GET /csrf` self-heal (REQ-SEC-010, REQ-FE-004),
   verifiable only via CI Playwright e2e because the OIDC login cannot be driven locally — high blast
   radius for a prod-live fix; and, decisively, (b) Spring's `CookieCsrfTokenRepository` is an
   **unsigned double-submit cookie**, a *weaker* CSRF model than the current server-side synchronizer
   token + `SameSite=Strict`: it trusts that only same-origin JS can read and echo the token, so a
   cookie-write vector (subdomain takeover, cookie injection) could forge it — which the
   session-bound synchronizer token cannot. Trading the stronger security model down to fix a
   session-lifetime problem is the wrong trade.
2. **Two-tier idle timeout.** Short window for un-authenticated sessions, long window only after a
   successful login. Surgical, does not touch the CSRF transport, and caps the orphan population
   regardless of the exact creating request.

## Decision

Adopt the **two-tier idle timeout** (option 2). `RedisSessionConfig`'s `SessionRepositoryCustomizer`
sets the repository default `maxInactiveInterval` to `app.session.anonymous-timeout` (default `30m`),
so every new session starts short. `SessionLifetimeUpgradeSuccessHandler` — wrapped into the OAuth2
login success chain by `SecurityConfig#oauth2LoginSuccessHandler` — promotes the session to
`app.session.authenticated-timeout` (default `720h`) on login success. It runs after Spring
Security's session-fixation `changeSessionId` (which preserves the interval) and never mints a
session when none exists.

Unchanged: the 30-day cookie `max-age`, the `maximumSessions(10)` principal cap, and the CSRF
repository/handler (`HttpSessionCsrfTokenRepository`, REQ-SEC-010). Keeping the session-backed
synchronizer-token CSRF is a **deliberate decision, not an omission** — option 1 is rejected (see
above), so this change never weakens the CSRF posture. The pre-existing `server.servlet.session.timeout:
720h` stays (aligned with the authenticated window) but is no longer what expires an authenticated
user's Redis session.

A new alert, `ActiveSessionsRunaway` (`basetool_active_sessions > 2000` for 1h), catches a regression
of the split with days of lead time before the Redis ceiling.

Codified as **REQ-SEC-025**.

## Consequences

- Orphan CSRF / pre-login sessions expire in ~30 min, so the anonymous-session population is bounded
  by `creation_rate × anonymous_timeout` (~120 at the observed rate) instead of growing unbounded.
  Steady-state `basetool_active_sessions` drops from tens of thousands to a few hundred.
- Real members are unaffected: a login immediately restores the 30-day window, and the 30-day cookie
  `max-age` still survives browser restarts.
- Two timeouts to reason about instead of one. Mitigated by explicit `app.session.*` keys, the
  `SessionLifetimeUpgradeSuccessHandler` Javadoc, and REQ-SEC-025.
- The 30-min anonymous window must comfortably exceed the OAuth2 round-trip (seconds) — it does, with
  large margin, including `prompt=none` silent re-auth.
- Cookie-based CSRF (option 1) is **not pursued** (rejected, @greluc 2026-07-10). It would stop
  anonymous session *creation* at the source, but Spring's unsigned double-submit cookie is a weaker
  CSRF model than the retained server-side synchronizer token + `SameSite=Strict`, and the two-tier
  TTL already bounds the leak without touching CSRF. The residual few-hundred short-lived anonymous
  sessions are an accepted, bounded cost.
- **Operational note (separate finding, same investigation):** the prod Redis `default` user was
  `nopass` because the mounted `users.acl` defined only the `monitoring` user, and loading an ACL
  file that omits `default` resets it to `nopass +@all`, silently overriding `--requirepass`. Fixed
  out-of-band by adding an explicit password-bearing `default` entry to the ACL file. The
  `docker-compose.yml` comment claiming the ACL file "MUST NOT define a `default` user" is backwards
  and is corrected alongside this change.

