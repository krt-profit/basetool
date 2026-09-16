# ADR-0190 — Every container but Keycloak runs on a read-only root filesystem

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** @greluc (the decision), Claude (measurement and analysis)
- **Related:** [ADR-0189](0189-stateful-containers-run-as-their-own-uid.md) ·
  [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) ·
  specs `REQ-OPS-014` ·
  [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md) §21

## Context

`REQ-OPS-014` has always said a read-only root filesystem is **not** part of the shared baseline,
required only of `edge`, and gave a reason: *the JVM and DB working dirs write across the
filesystem*. That reason was never measured. It was a plausible sentence, and it stood for as long
as nobody ran the arms.

The Podman migration ran them. `PODMAN_MIGRATION_PLAN.md` §21 records fourteen services measured in
two arms each — the image run **writable**, with `podman diff` asked what it put on its own root
filesystem, then the same thing `--read-only` to see whether it still comes up. The sentence was
wrong about both halves.

**The databases do not write across the filesystem.** ADR-0189 already put them on read-only root
filesystems with no capabilities at all.

**Neither do the JVM modules.** Their own source contains **no filesystem write API whatsoever** —
no `new File`, no `Files.*`, no `FileOutputStream`, no `createTempFile`. The only writer is
logback, whose path is relative to `/app` and therefore inside the log directory that is already
mounted. Measured against the built images, a **healthy** Spring Boot service — `ingest`, up and
`health=healthy` on a read-only root filesystem — writes exactly three things:

```
/tmp/tomcat.<port>.<random>/work/Tomcat/localhost/ROOT
/tmp/tomcat-docbase.<port>.<random>
/tmp/hsperfdata_app/1
```

Tomcat's work directory, its docbase, and the JVM's perf data. All three under `/tmp`.

And **Podman mounts `/run`, `/tmp` and `/var/tmp` as tmpfs under `--read-only` and copies the
image's content up into them** (measured, plan §20). So the paths that made read-only look expensive
are handed over by the runtime for free.

Of the ten third-party images, nine write nothing outside their mounts or write only under `/tmp`.

## Decision

**Every container gets `ReadOnly=true` except `keycloak`.** Seventeen of the eighteen units.

**It is a Quadlet-side table, not `read_only: true` in the compose file.** That is not stylistic:
**Docker does not mount those three tmpfs** — Podman's `--read-only` does and Docker's does not. The
same line in the compose file would break most of these services on the Docker host that still runs
them. What was measured is Podman's behaviour, so it is expressed where Podman reads it. The table
sits beside `FRONT_END` and `RUN_AS`, which exist for the same reason.

**`keycloak` is exempt, and the exemption is structural rather than a missing tmpfs.** `kc.sh start`
without `--optimized` **re-augments the Quarkus application into its own installation directory** at
every boot: 476 paths under `/opt/keycloak/lib`, measured, plus `/opt/keycloak/data/transaction-logs`.
Read-only stops it dead with `FileSystemException: /opt/keycloak/lib/quarkus/transformed-…`.

That augmentation is **required here**. The Keycloak SPI provider arrives as a JAR mounted into
`/opt/keycloak/providers` at deploy time, and a provider that appears at runtime is exactly what
forces the re-augmentation. Making Keycloak read-only means baking the provider into a custom image
and running `start --optimized` — which changes how the provider is delivered and promoted. That is
a separate decision with its own trade-offs, and it is not made here.

**Grafana gets one literal environment variable with it.** Its background installer tries to refresh
a *bundled* plugin inside its own installation directory and logs
`unlinkat /usr/share/grafana/data/plugins-bundled/elasticsearch: read-only file system` at every
start. Grafana serves regardless — measured, `HTTP 200` on `/login` — but an error line per boot is
what the log-based alerting reads. `GF_PLUGINS_PREINSTALL_DISABLED=true` removes it, measured both
ways, and changes nothing this deployment uses: the datasources are provisioned from files and
Elasticsearch is not one of them. It also removes an outbound call at every start.

**The conformance suite asserts the posture in both directions.** `containers-read-only` fails on a
container that should be read-only and is not, **and** on `keycloak` becoming read-only — because
that would mean this record is stale, which is worth reading rather than quietly agreeing with.

## Alternatives considered

**Put `read_only: true` in the compose file.** Refused: Docker mounts no tmpfs under `--read-only`,
so every service that writes to `/tmp` would need an explicit `tmpfs:` entry to keep the Docker host
working — a set of entries that exist only to compensate for a runtime the stack is leaving.

**Give Keycloak a tmpfs over `/opt/keycloak/lib`.** Refused: that hides the installation the
augmentation is meant to produce, and what survives a restart would be whatever the tmpfs happened
to hold. It would look like it worked.

**Bake the SPI provider into a custom Keycloak image and run `start --optimized`.** Not refused —
deferred. It is the only real route to a read-only Keycloak, and it is a change to the promotion
path (`REQ-OPS-003`), not a hardening flag.

**Drop the `tmpfs:` entries `edge` carries, now that Podman provides them.** Refused, and only one
of the two would have been safe to drop anyway: `edge` mounts `/tmp` — which Podman does supply —
and **`/var/cache/nginx`, which it does not**. Podman covers `/run`, `/tmp` and `/var/tmp` and
nothing else, so a service writing anywhere else still needs its own entry. `/tmp` stays as well,
because it is what makes the requirement legible to a reader who does not know the defaults.

**Assert the posture by reading the unit files instead of the running host.** Refused for the same
reason as ADR-0189's uid check: a unit file records what was asked for.

## Consequences

- Seventeen of eighteen containers cannot rewrite their own installation. Whatever gets inside one
  is gone at the next restart, and the image is the image.
- No `Tmpfs=` line was needed anywhere. That is a Podman property, and it is written down in the
  generator so the next reader does not spend the afternoon rediscovering it.
- **Keycloak stays writable, and the reason is recorded where somebody will look for it** — in the
  generator's table, in the conformance suite's exemption, and here. An unexplained exception is how
  a posture quietly becomes a suggestion.
- An image bump that starts writing to its own root filesystem now fails at deploy time, where
  `REQ-OPS-003`'s health gate rolls it back, rather than succeeding and drifting.
- `REQ-OPS-014`'s statement that read-only is not part of the baseline is amended in the same change.

## Status of this decision

Accepted. Seventeen units carry `ReadOnly=true`, `scripts/generate-quadlet.py` refuses a table that
disagrees with itself or names something that is not a container, and `containers-read-only` has
three red scenarios that behaved. Nothing is deployed: these are Quadlet units for a host that does
not serve traffic yet.
