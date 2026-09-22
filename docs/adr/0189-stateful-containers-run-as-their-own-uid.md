# ADR-0189 — The stateful containers run as their own uid, not as root that steps down

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** @greluc (the decision), Claude (measurement and analysis)
- **Related:** [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  [ADR-0188](0188-the-host-bootstrap-is-an-ansible-role.md) ·
  specs `REQ-OPS-014` ·
  [`PODMAN_MIGRATION_PLAN.md`](../archive/PODMAN_MIGRATION_PLAN.md) §20

## Context

`REQ-OPS-014` gives every service `cap_drop: [ALL]` and `no-new-privileges`, then hands three of
them five capabilities back — `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `SETGID`, `SETUID`. The two
Postgres instances and Redis need them because their images boot as **root**, chown their data
directory, and step down to an unprivileged user with `gosu`. The compose file asks, in a comment,
for that set to be re-verified on every image bump. Nobody ever had, because there was no way to
verify it that did not involve breaking a database.

The Podman migration forced the question, and `PODMAN_MIGRATION_PLAN.md` §20 answers it by
measurement — Rocky 10.2, Podman 5.8.2, the real digests, the real `PGDATA`, the real data mounts,
Redis with its real `--aclfile` and the read-only ACL mount that a 2026-07-10 defect made
load-bearing. Every arm was judged by a health probe, a write, a restart, and a second write.

Two results decided this.

**The set is wrong in both directions.** Postgres needs four of its five — `FOWNER` never was
load-bearing. Redis needs two. So three capabilities were granted to Redis and one to each Postgres
for no reason anybody could have discovered without running it.

**Reducing Redis's capabilities does not reduce its privilege — it raises it.** The Redis entrypoint
tests its own capabilities before dropping privileges:

```sh
# our uid is 0 (container started without explicit --user)
# and we have capabilities required to drop privs
if [ "$IS_REDIS_SERVER" ] && ... && has_cap setuid && has_cap setgid; then
```

Without `SETUID`/`SETGID` it **skips the drop and keeps running as root**. The container is healthy,
answers `PING`, and passes `containers-running`. It then writes `appendonlydir` and every AOF file
as `0:0` mode `0600` — and the correct configuration afterwards **refuses to start** on them:
`Error moving temp append only file on the final destination: Permission denied`. Postgres has no
such test and fails loudly on `exec gosu`.

A partial capability set is therefore the dangerous state for Redis, and it is the state a
well-meaning hardening change produces.

## Decision

**`db-backend`, `db-keycloak` and `redis` run as their own uid.** Their Quadlet units carry
`User=`/`Group=` — 70 for the two Postgres instances, 999 for Redis — together with `ReadOnly=true`
and `DropCapability=ALL`, and **no** `AddCapability=` line at all. The root phase never runs, so
there is nothing to drop from and nothing to grant.

**The four settings are emitted as one set**, by `scripts/generate-quadlet.py`, because that is the
combination that was measured. There is deliberately no way to express half of it.

**It is a Quadlet-side override, not a compose change.** The table lives beside `FRONT_END`, which
already exists for the same reason: the Docker deployment still runs these containers, and changing
how *it* starts a live database is a separate change with its own deploy and its own approval.
Compose stays authoritative for everything else, and the drift check still compares the two.

**The uid is checked against the bootstrap role at generation time.** `User=70` in a unit and a data
directory owned as if the container were uid 70 are the same fact in two places. ADR-0188's role
owns each directory as `basetool_host_subuid_base + container_uid - 1`; the generator reads
`basetool_host_container_owners` and **refuses to generate anything** if the two stop agreeing. The
failure is a red build, not a database that will not start.

**The conformance suite asserts the uid of pid 1.** `containers-unprivileged` reads the container's
pid from the host, that pid's real uid, and its `uid_map`, then translates back to the uid **as the
container sees it**. No `docker exec`. The identity map makes it correct on a rootful Docker host
and the subuid map makes it correct on a rootless Podman one, so on Podman it asserts the namespace
translation as well.

> [!important] The check exists because *the container came up* stopped being evidence
> `containers-running` is green on a Redis that is running as root. Every other check in the suite
> is green on it too. The uid of pid 1 is the only thing that is not, which is exactly why the
> assertion is about the uid rather than about the capability list — a capability list describes
> what was asked for, and this ADR exists because of a case where what was asked for and what
> happened differed silently.

## Alternatives considered

**Keep the root phase and merely correct the capability sets** (Postgres to four, Redis to two).
Refused: it leaves Redis one careless edit away from the root fallback, and the edit that causes it
looks like hardening. The reduction that is safe to write down is the one that cannot be written
down partially.

**Put `user:` in the compose file instead.** Refused for now, not on merit: it would very likely
work under rootful Docker, and *very likely* is not a claim worth making about a production
database. It changes how a running stack starts, which is a deploy with its own approval, and this
change needs none. `edge` already carries `user: "101:101"` in compose, so the door stays open.

**Derive the uid in the conformance suite from the generator or the role.** Refused: a check that
reads its expectation from the thing it is checking cannot disagree with it. The three numbers are
written out a third time on purpose.

**Do nothing until after the migration.** Refused: the units are being written now, and writing them
with a capability set that measurement has already contradicted would put a known-wrong value into
the artefact the migration is judged by.

## Consequences

- Three services lose fifteen granted capabilities between them, and gain a read-only root
  filesystem. Podman mounts `/run`, `/tmp` and `/var/tmp` as tmpfs under `--read-only` and copies
  the image's content up into them, so no explicit `Tmpfs=` is needed — measured, and it is the
  reason these images find their socket directories without being given one.
- **The containers can no longer repair their own data directory.** The root phase also chowns; with
  `User=` a wrong ownership is a container that does not start. That is the trade, and it is
  acceptable only because the ownership is derived rather than hoped for — ADR-0188's role sets it
  from the same three numbers, and the generator refuses when they diverge.
- A future image bump that changes the built-in uid breaks the container **loudly**, at deploy time,
  where `REQ-OPS-003`'s health gate rolls it back. That is the intended failure mode, and it is
  better than the one this ADR removes.
- `REQ-OPS-014`'s capability grant no longer describes these three services. The spec is amended in
  the same change.

## Status of this decision

Accepted. The measurements are in `PODMAN_MIGRATION_PLAN.md` §20, the units are generated, and
`containers-unprivileged` has four red scenarios that behaved plus a green one that exercises the
rootless `uid_map` translation. Nothing is deployed: these are Quadlet units for a host that does
not serve traffic yet.
