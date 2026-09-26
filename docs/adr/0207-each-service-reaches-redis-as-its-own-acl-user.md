# ADR-0207 — Each service reaches Redis as its own ACL user

- **Status:** Accepted — shipped inert; rolled out on production 2026-09-25 (steps 2–5, `default` off); two exchange key families proposed by [ADR-0221](0221-redis-grows-to-768-mb-and-holds-a-bounded-exchange-partition.md) (epic #2078)
- **Date:** 2026-09-23
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-068](../specs/security-and-access.md)
- **Related:** [ADR-0079](0079-redis-session-store-aof-and-maxmemory-noeviction.md) (the store),
  [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md) (the fan-out channels),
  [ADR-0110](0110-ingest-handoff-consume-off-navigational-get.md) (the handoff keys),
  [ADR-0084](0084-readiness-health-group-excludes-external-keycloak-probe.md) (Redis is mandatory
  for the frontend), REQ-SEC-025, REQ-OPS-018
- **Source:** improvement audit 2026-09-22, finding APPSEC-04

## Context

One Redis holds three things with three owners: the frontend's Spring Session store — every
member's OAuth2 access and **refresh** token — the backend's and frontend's live-sync and
notification fan-out, and the ingest gateway's single-use handoff. All three services reached it as
`default` (`~* &* +@all`) with one shared `REDIS_PASSWORD`. The ingest gateway is internet-facing and
needs two key prefixes; with the shared user, a remote-code-execution in it would have been a read
of every session token and a write into every session. The backend needs no key at all.

The ACL file itself was written by hand from a two-line recipe, with the `default` password in clear
text, and nothing checked it. That recipe already failed once: on 2026-07-10 a file without a
`default` line left Redis's `default` user `nopass ~* &* +@all`.

Two things made a naive split impossible. Spring Session runs `CONFIG GET` / `CONFIG SET
notify-keyspace-events` at every frontend start, and `CONFIG SET` can move Redis's directory and
snapshot file — the classic Redis-to-file-write path — so it must not be granted to an application
user. And the redis unit's health probe authenticated as `default`, so switching `default` off would
have restarted Redis in a loop.

## Decision

1. **Five users plus `default`**, rules in a committed template (`scripts/redis-users.acl.tmpl`):
   `basetool-frontend` (`basetool:session:*`, `GETDEL` of `ingest:handoff:*`, the session-event,
   keyspace-event and live-sync channels, `SCAN`, `INFO`, no dangerous command), `basetool-backend`
   (publish/subscribe on its two channels, no key), `basetool-ingest` (seven write commands on
   `ingest:*`, no channel, no `SCAN`), `monitoring` (as before, minus `SCAN` and `RANDOMKEY`, which
   are not key-checked and would list session ids), `admin` (the operator, `REDIS_PASSWORD`), and
   `default`, `on` until the rollout completes and `off` afterwards.
2. **Rendered, hashed, all-or-nothing.** `scripts/render-redis-acl.py`, installed by the role next to
   `render-env-d.py`, fills the template from `.env` with SHA-256 hashes, refuses a missing variable or
   a result without exactly one `default` line, writes atomically, and is applied live with `ACL LOAD`.
   The operator runs it; `deploy.sh` does not — rewriting the ACL of a live session store is a
   decision, not a deploy step.
3. **Inert on merge.** Each application reads `REDIS_USERNAME` / `REDIS_PASSWORD`, which compose maps
   from `REDIS_<SVC>_USERNAME` / `REDIS_<SVC>_PASSWORD` with the shared `REDIS_PASSWORD` as fallback.
   An empty username is a password-only `AUTH`, i.e. `default` — today's behaviour exactly.
4. **Nothing depends on the ACL state that must not.** Redis carries `--notify-keyspace-events Egx`
   on its own command line; the frontend's `TolerantKeyspaceNotificationsAction` runs Spring
   Session's step and logs a `NOPERM` instead of failing (any other failure still fails the start).
   The health probe is an unauthenticated `PING` that accepts `NOAUTH`.

   > **Amended 2026-09-25.** Tolerating the refusal was not enough: Redis still counts it. After
   > the production rollout, `ACL LOG` showed a `config|get` refusal for `basetool-frontend` on
   > every frontend start, each one feeding `redis_acl_access_denied_cmd_total` and so
   > `RedisAclDenials` — an alert that must mean "an ACL is wrong", not "the frontend restarted".
   > The frontend now picks the step from `spring.data.redis.username`, i.e. from the credentials it
   > actually uses, never from catching the refusal: with no username (or `default`) the
   > `CONFIG` step above, unchanged; with its own user `ServerConfiguredKeyspaceNotificationsAction`,
   > which sends a `PING` (allowed by `+@connection`) and no `CONFIG`, so a store that cannot be
   > reached or refuses the credentials still fails the start (ADR-0084). The AOT training run's
   > `NO_OP` (IMG-PERF-12) is untouched. Rejected: granting `basetool-frontend` `CONFIG GET` alone —
   > harmless in itself, but it widens the one user that holds every session to buy a read whose
   > answer the server's own command line already fixes.
5. **Proven where it runs.** Testcontainers suites in all three modules run the real Spring Session
   repository, the real fan-outs and the real handoff staging under their users against the
   template, plus an `ACL DRYRUN` matrix; the E2E stack loads the template rendered with throwaway
   passwords and `default` off, so the whole Playwright suite runs on the per-service users.
6. **Watched.** `RedisAclDenials` fires on five minutes of `acl_access_denied_*`.

## Consequences

- The deploy that carries this restarts `redis` once (its unit changed) and nothing else changes.
- The rollout is five owner steps — install the renderer, generate three passwords on the host,
  render and `ACL LOAD` with `default` on, move the applications one at a time, switch `default` off —
  each with its own rollback ([`deployment.md` → *The Redis ACL*](../deployment.md#the-redis-acl)).
- **A new key prefix or channel is a template change** plus a render and `ACL LOAD` on the host, and
  the E2E ACL is re-rendered (a test fails until it is). The compose comment that promised "a new
  channel needs no ACL change" is withdrawn.
- The ACL file and every backup of it hold hashes, not passwords.
- `admin` keeps an all-powerful user in existence. It is the operator's, lives only in the redis
  container's environment, and is not reachable by an unauthenticated or password-only client; the
  alternative — no admin at all — would make every ACL change a Redis restart.

## Alternatives rejected

- **Rendering the ACL inside the redis container at start** from environment variables. Every
  service password would sit in the redis container's environment, every ACL change would be a
  restart, and the file would no longer exist to be backed up or diffed.
- **Granting the frontend `CONFIG GET` / `CONFIG SET`.** `CONFIG SET dir` + `dbfilename` is a file
  write; the server can carry the setting instead.
- **Category-only rules for all users.** `+@read` includes `SCAN`, which is not key-checked; the
  backend and the gateway get explicit command lists for that reason.
- **Switching `default` off on merge.** Every application still authenticates as `default` until
  its username is set, so the deploy would lock all three out of Redis at once — for the frontend
  that is no login and no session — with no way back short of an `ACL LOAD` by hand.
