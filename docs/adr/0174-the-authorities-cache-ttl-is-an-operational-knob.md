# ADR-0174 — The authorities cache TTL is an operational knob, defaulting to five minutes

- **Status:** Accepted — implemented; **amended 2026-09-23** (the cache key, see *Amendment 1*). *Status corrected 2026-09-22:* it read "Proposed", but the change it decides has been on `main` since 2026-09-13 (`e401742db`: `support.AuthoritiesCacheProperties`, `APP_SECURITY_AUTHORITIES_CACHE_TTL`).
- **Date:** 2026-09-13
- **Deciders:** @greluc (pending)
- **Related:** specs `REQ-SEC-056` (new), `REQ-DATA-016` (new) ·
  [`security-and-access.md`](../specs/security-and-access.md) ·
  [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-003`, the no-N+1 rule) ·
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the machine-identity
  carve-out that shares this cache key) ·
  [ADR-0175](0175-jvm-garbage-collectors-are-set-explicitly.md) (the other half of the same
  2026-09-13 production investigation) · PR #1141 (the cache itself)

## Context

`CustomJwtGrantedAuthoritiesConverter` memoises the fully-assembled authority collection in
Caffeine, keyed on `(sub, token issuedAt, azp)`, with `expireAfterWrite` hard-coded at **30
seconds** and a maximum of 10 000 entries. The converter runs on **every** authenticated API call.

Its own Javadoc already stated what a **miss** costs: `syncUser` — a write-capable transaction —
plus roughly five to eight SELECTs, namely *"user load, `user_roles`, one role lookup per realm
role, and the membership read"*. What nobody had done was measure what that came to in aggregate.

Read on production on 2026-09-13, `pg_stat_user_tables` gave this, cumulative since 2026-04-08:

|         Table         | seq scans  | rows per scan |  size  |
|-----------------------|------------|---------------|--------|
| `role`                | 22,943,252 | 5             | 64 kB  |
| `org_unit`            | 21,477,515 | 30            | 96 kB  |
| `role_permissions`    | 15,468,935 | 20            | 8 kB   |
| `org_unit_membership` | 10,735,896 | 62            | 136 kB |
| `app_user`            | 7,159,098  | 41            | 120 kB |
| `user_roles`          | 6,314,034  | 62            | 88 kB  |

**84.1 million sequential scans** against tables holding five to sixty rows. The ratios reproduce
the documented miss cost almost exactly, which is what identifies the traffic as this cache's misses
rather than something else:

|               Ratio                | Observed |                Predicted by the code                 |
|------------------------------------|----------|------------------------------------------------------|
| `role` / `user_roles`              | **3.6**  | one role lookup per realm role, ~3.6 roles per user  |
| `app_user` / `user_roles`          | **1.1**  | one user load and one `user_roles` read per miss     |
| `org_unit` / `org_unit_membership` | **2.0**  | the `OrgUnitCascadeService` tree walk per membership |

The database is 81 MB with a 99.984 % buffer cache hit ratio, so none of this is I/O — it is CPU,
in bursts. `db-backend` took **161 s of CFS throttling in 29 hours** at an average of **1.6 % of its
own CPU quota**, with a **229 ms average stall per throttle event**: the worst in the stack, and
more than two whole 100 ms scheduling periods each time.

The connection pool is not implicated — Hikari sat at 39 idle of 100, `pending` 0, `timeout_total`
0, mean acquire 0.55 ms over 193 040 acquisitions.

At a 30-second TTL an actively clicking member pays this storm **twice a minute**, for facts —
roles, permissions, approvals, memberships — that change on the order of once a week.

The tempting fix is not available. `RoleRepository.findByNameIgnoreCase` must **not** become
`@Cacheable`: `syncUser` sets the looked-up `Role`s on the *managed* `User` and flushes, so handing
it cached **detached** entities risks `NonUniqueObjectException`. That is already recorded, and the
remedy it points at is caching the assembled authorities — which is what this cache is. The
remaining variable is its TTL.

## Decision

**The TTL becomes configuration, and its default becomes five minutes.**

- New `AuthoritiesCacheProperties` (`app.security.authorities-cache.ttl`), `@Validated`, default
  `PT5M`, reaching the container as `APP_SECURITY_AUTHORITIES_CACHE_TTL`.
- Validated at startup: **strictly positive and at most `PT15M`**. Zero or negative would disable
  the cache and silently restore the storm; above the ceiling would widen the revocation window
  past what the access model assumes. Both fail the context rather than degrading at run time.
- The class lives in `support`, not `config`. `config` already depends on `service` (the security
  configuration wires the converter), so a properties class the `service` layer reads would close a
  package cycle `ArchitectureTest.backendPackagesShouldBeFreeOfDependencyCycles` forbids.
- The converter gains an explicit constructor: the Caffeine window comes from configuration, so it
  cannot be built in a field initialiser.

The security property this trades against is stated plainly: **the TTL is the window in which a
revoked role, a withdrawn permission, a reversed approval or a removed org-unit membership stays
effective on an already-issued token.** A fresh login always misses — `issuedAt` is part of the
key — so re-authentication picks up new authorities immediately and this bounds staleness only
*within* one token's life. Five minutes was chosen by the repository owner.

## Alternatives rejected

- **Leave it at 30 seconds and raise `db-backend`'s CPU quota only.** The quota rise happens anyway
  (ADR-0085's budget makes it free), but it treats the symptom: the work is still done, just with
  room to do it. Ten times the necessary queries is a defect whether or not there is CPU to absorb
  them.
- **Hard-code five minutes.** Smallest diff, no new environment variable, no validation test. But
  this is precisely the value an operator wants to move during an incident — down to tighten
  revocation after a compromised account, up to shed database load — and a code deploy is the wrong
  instrument for that.
- **Make `RoleRepository.findByNameIgnoreCase` `@Cacheable`.** The detached-entity hazard is
  documented and real; it would trade a load problem for a correctness one.
- **Evict the cache on role, permission and membership writes instead of expiring it.** Correct in
  principle and strictly better than a TTL, but the eviction would have to cover every write path
  that can change an authority — Keycloak realm changes included, which the backend does not
  observe. A TTL that is honest about being a staleness bound beats an eviction that is nearly
  complete.
- **No ceiling on the configured value.** Rejected: without it, a mistyped `PT50M` silently widens
  the revocation window and nothing anywhere would say so.

## Consequences

- Cache misses drop by roughly an order of magnitude at steady state, and with them the permission
  table scans and the throttling they drive. The effect is **not** yet measured on production; this
  ADR records the decision and the reasoning, not a verified outcome.
- Revoking a role now takes up to five minutes to bite on an already-issued token, where it took up
  to thirty seconds. Forcing a re-login remains the immediate remedy and is unaffected.
- `docker-compose.yml`'s backend `environment:` is a **closed allow-list** with no `env_file:`, so
  the variable had to be named there or it would never reach the container whatever the host `.env`
  says — the same trap the frontend's `APP_HTTP_*` levers were added to escape.
- The next question about this path becomes measurable rather than reconstructible:
  `pg_stat_statements` is installed alongside (`REQ-DATA-016`, V240), because this whole analysis
  had to be inferred from table counters for want of it.

## Amendment 1 — 2026-09-23: the key is the Keycloak session, not the token

*Improvement audit 2026-09, finding BE-PERF-08, approved by @greluc.*

**What was wrong with the decision above.** The TTL it made configurable could not take effect.
The cache key contained the token's `issuedAt`, Keycloak issues a new access token every five minutes
(`accessTokenLifespan` 300 s), and every refreshed token was therefore a new key: whatever the TTL,
no entry outlived the token it was made for. Raising the TTL above `PT5M` bought nothing.

**Decision.** The key becomes `sub | session | azp | claims fingerprint`:

- **session** is the token's `sid` — stable for the whole login, and verified to be present and
  unchanged across a refresh on Keycloak 26.7 with the E2E realm (password grant, then refresh
  grant). A token without `sid` — a client-credentials grant has no user session — falls back to
  `issuedAt`, i.e. to the old key.
- **azp** stays, for the REQ-SEC-036 reason given in the converter.
- **claims fingerprint** is a SHA-256 over every claim except `iat`, `exp`, `nbf` and `jti`, rendered
  unambiguously (sorted keys, tagged and length-prefixed values). It is what keeps a **role change as
  fast as before**: a realm role granted or revoked in Keycloak changes the refreshed token's
  `realm_access`, the fingerprint differs, and the next request re-assembles — exactly when the old
  `issuedAt` key would have. The same holds for a renamed account or a changed e-mail address, which
  `syncUser` writes down.

**What the TTL now bounds, and what it does not.** It bounds changes a token cannot carry — an
approval, a local role's permission set, an org-unit membership flag. Before, those were picked up at
the next token refresh or at TTL expiry, whichever came first; now at TTL expiry alone. At the
default `PT5M` the worst case is unchanged (five minutes either way). Raising the TTL towards the
`PT15M` ceiling now genuinely trades that window for fewer misses — which is what the knob was always
meant to do.

**Observability.** The cache is bound to Micrometer as `cache="jwt-authorities"` (`recordStats()`),
so its hit ratio appears in the Spring-apps dashboard's cache panels and `CacheHitRatioLow` /
`CacheSizeEvictionsHigh` cover it. The effect on production misses is to be read there after the
rollout; it is not measured yet.

**Guards.** `CustomJwtGrantedAuthoritiesConverterTest`: a refreshed token of the same session is a
hit; a refreshed token with a revoked realm role misses and no longer carries the role; a new session
misses; two `azp` values of one session never share an entry; the per-token claims are outside the
fingerprint and everything else inside it; hits and misses reach the meters.
`FirstLoginAuthoritiesIntegrationTest` stays green.
