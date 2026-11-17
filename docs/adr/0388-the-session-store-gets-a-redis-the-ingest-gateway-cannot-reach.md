# ADR-0388 — The session store gets a Redis the ingest gateway cannot reach

- **Status:** Accepted — the frontend holds its two connections; the second instance, its
  operations and the cut-over follow (#3493)
- **Date:** 2026-11-16
- **Deciders:** @greluc
- **Related:** [REQ-SEC-089](../specs/security-and-access.md),
  [REQ-SEC-068](../specs/security-and-access.md), [REQ-OPS-018](../specs/deployment-delivery.md),
  [REQ-OPS-010](../specs/backup-recovery.md), [REQ-XCH-008](../specs/external-exchange.md),
  [REQ-XCH-023](../specs/external-exchange.md) ·
  [ADR-0079](0079-redis-session-store-aof-and-maxmemory-noeviction.md),
  [ADR-0084](0084-readiness-health-group-excludes-external-keycloak-probe.md),
  [ADR-0085](0085-scale-user-sync-and-stack-capacity-for-5000-accounts.md),
  [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md),
  [ADR-0207](0207-each-service-reaches-redis-as-its-own-acl-user.md),
  [ADR-0221](0221-redis-grows-to-768-mb-and-holds-a-bounded-exchange-partition.md),
  [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md),
  [ADR-0389](0389-the-ingest-gateway-runs-no-lua-in-redis.md) · amends ADR-0207 and ADR-0221 ·
  issue [#3493](https://github.com/krt-profit/basetool/issues/3493)

## Context

One Redis instance holds seven things for three services:

| Data | Keys or channel | Writer → reader | On loss or outage |
| --- | --- | --- | --- |
| Spring Session, with every member's OAuth2 access **and refresh** token | `basetool:session:*` | frontend | no login; a lost session is a re-login |
| Ingest handoffs, full draft bodies, single use, 30 minutes | `ingest:handoff:*` | ingest → frontend (`GETDEL`) | the pickup fails |
| Live-sync `changed` relay | `basetool:livesync:changed` | frontend ↔ frontend, backend → frontend | local delivery only |
| Editor presence gossip | `basetool:livesync:presence` | frontend ↔ frontend | local presence only |
| Notification push fan-out | `basetool:notify:published` | backend ↔ backend | polling takes over |
| Exchange registry mirror, switch, revocations, deny list | `exchange:*` | backend → ingest | the exchange fails closed; the backend rewrites it from Postgres every 60 s |
| Gateway state: quotas, idempotency answers, byte budget | `ingest:xch:*` | ingest | quotas and replay protection restart empty |

ADR-0207 gave each service its own ACL user on its own network, so a compromised **client** reaches
only its own keys. The ACL does not bound a fault in the Redis **server**: a service that can make
the server run its own code reads every key in the process. The ingest gateway is the service with
the widest exposure — the internet-facing exchange API for third-party clients and the extractor's
uploads (ADR-0268) — and it shares that process with every session token. Lua scripting has carried
exactly this class of fault: CVE-2025-46818 lets an authenticated user run code in another user's
context, and CVE-2025-49844, rated critical, was published with it.

The instance couples the two sides in three more ways:

- **Memory.** Under `noeviction` a full Redis refuses writes, and logins fail first (ADR-0221).
  Sessions are shielded from the exchange only by the gateway's own byte budget (128 MiB since
  ADR-0221 amendment 3) and the handoff quotas, so a fault in that code is a login outage.
- **Restarts.** `frontend` and `ingest` require `redis` (arc42 §7), and the frontend's readiness
  includes it (ADR-0084). A Redis restart for the exchange's sake — the resize of ADR-0221 was one —
  restarts the frontend, and a Redis outage stops login and the exchange at once.
- **Sizing.** ADR-0085 measured the session store at 7 MB for 8 191 keys; the 768 MB ceiling exists
  for the exchange partition.

A split into "security-critical" and "non-critical" data separates nothing: the exchange mirror is
authorization data that must never be evicted (ADR-0221) and needs the same `noeviction` and
persistence as the sessions. What differs is **who can reach** the data.

ADR-0221 rejected a separate Redis for the exchange as "one more service to run, secure and back
up for a few megabytes". The exchange's budget has since grown to 128 MiB, and the server-side
exposure of the gateway's user was not weighed there.

## Decision

We will run two Redis instances, cut along the services that reach them:

1. **`redis-session`** holds only Spring Session: `basetool:session:*`, its event channels and the
   keyspace notifications it listens to. The frontend is its only application user; `monitoring` and
   `admin` stay. It keeps ADR-0079's profile — AOF `everysec`, `noeviction`,
   `--notify-keyspace-events Egx` — at ADR-0085's size, `maxmemory 384mb` in a 512 MB container.
2. **`redis-shared`** holds everything else: `exchange:*`, `ingest:*` and the three pub/sub channels.
   `basetool-ingest` and `basetool-backend` keep their ADR-0207 rules; `basetool-frontend` gets only
   `GETDEL` on `ingest:handoff:*` and the two live-sync channels. It keeps AOF `everysec` and
   `noeviction`, because an evicted revocation re-opens access, and is sized for the exchange budget
   and the handoff quotas.
3. **The frontend holds two connections.** The auto-configured one (`spring.data.redis.*`) is
   `redis-session`: Spring Session, the session gauge and the readiness probe use it. Live sync and
   the handoff pickup use a second connection to `redis-shared` (`app.shared-redis.*`,
   `SharedRedis`), which is deliberately not a `RedisConnectionFactory` bean: a second bean of that
   type switches Spring Boot's connection auto-configuration off and lets an unqualified injection
   pick either store. Only `redis-session` is in the frontend's readiness group and in its
   `Requires=`; `ingest` requires `redis-shared`; the backend stays without a Redis dependency at
   start.
4. **Each instance has its own ACL template and file**, rendered by `render-redis-acl.py` with
   exactly one `default` line, `off`, and its own `admin` password. Both files are in the backup set;
   the data of both stays out of it (REQ-OPS-010).
5. **The cut-over moves no data.** The existing instance and its data directory become
   `redis-session`, so nobody is logged out. `redis-shared` starts empty: the backend's 60-second
   reconcile rewrites the registry and the revocations from Postgres, and the exchange answers
   `503 REGISTRY_UNAVAILABLE` until it has; quotas, budgets, idempotency answers and handoffs start
   empty. The exchange keys left in `redis-session` are then deleted by the operator.

## Consequences

- A code execution in the gateway, even combined with a Redis server fault, reaches only exchange
  data that is rebuilt from Postgres or lives an hour at most — not a session.
- The exchange cannot fill the session store, and a restart or outage of `redis-shared` leaves
  login working: live sync falls back to local delivery, the handoff pickup and the exchange are
  unavailable.
- The session store's ACL shrinks to one application user, and `SCAN` by the frontend lists only
  sessions.
- **More to run:** a second stateful container, network, ACL file, exporter target and backup
  artifact; every Redis alert and dashboard panel has to tell the two instances apart, and the
  restore drill checks both ACL files. The E2E stack and the Testcontainers ACL suites run two
  instances.
- The roll-out is a sequence of owner-approved production writes with one maintenance window for
  the exchange (the reconcile interval).
- REQ-SEC-068, REQ-OPS-018, REQ-OPS-010, the Redis rules of `observability.md`, arc42 §5, §7 and
  §11, the Redis ACL runbook in `deployment.md` and the processing activities move with the
  implementation; ADR-0207's user table and ADR-0221's rejected alternative are amended by this
  decision.
- Removing Lua from the gateway (ADR-0389) stays in force: it closes the scripting path into
  `redis-shared` as well.

## Alternatives considered

- **Keep one instance.** Rejected: the ACL bounds a compromised client, not a server fault, and
  the process that holds every refresh token stays reachable from the internet-facing gateway.
- **Split by criticality** (sessions apart from "the rest"). Rejected: the exchange mirror is
  security data with the same eviction and persistence needs; the split has to follow who reaches
  an instance.
- **Keep the pub/sub channels with the sessions.** Rejected: the backend would need a second
  connection and the session store a second application user, for channels that carry no secret.
  A forged `changed` frame carries section keys only and triggers re-fetches each viewer
  re-authorizes, the risk ADR-0094 already accepts for every member.
- **A third instance for the pub/sub channels.** Rejected: they hold no keys and no memory, and
  their loss already degrades to local delivery.
- **Move the gateway off Redis** (the registry by HTTP from the backend, its state in process, the
  handoff through the backend). Rejected: it reworks REQ-XCH-008's per-request revocation read,
  drops idempotency on every deploy and puts short-lived drafts of up to 2 MiB into Postgres.
- **Encrypt the session values.** Rejected as a substitute: a server fault still exposes the keys,
  and a session's key is its cookie, so every session could be ridden without decrypting anything.
- **Remove Lua from the gateway only** (ADR-0389). Taken as well, but not enough on its own: it
  leaves the memory, restart and readiness coupling, and it narrows one known path into the server
  rather than taking the gateway out of the session store's process.
