# ADR-0190 — Every container runs on a read-only root filesystem

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** @greluc (the decision), Claude (measurement and analysis)
- **Related:** [ADR-0189](0189-stateful-containers-run-as-their-own-uid.md) ·
  [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  [ADR-0055](0055-keycloak-spi-jar-as-promotable-oci-artifact.md) ·
  [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) ·
  specs `REQ-OPS-014` ·
  [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md) §21

> [!important] Corrected the same day it was written — the file name keeps the original spelling
> The first version of this ADR was titled *"Every container **but Keycloak**"* and recorded
> Keycloak as unable to have a read-only root filesystem at all. That was wrong, and it was wrong
> because of a measurement that was not taken rather than one that misled. The correction and its
> evidence are under *Keycloak took two passes* below. The file name is unchanged so no link
> breaks.

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
no `new File`, no `Files.*`, no `FileOutputStream`, no `createTempFile`. The only writer is logback,
whose path is relative to `/app` and therefore inside the log directory that is already mounted.
Measured against the built images, a **healthy** Spring Boot service — `ingest`, up and
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

**Every container gets `ReadOnly=true`.** Eighteen of eighteen units.

**It is a Quadlet-side table, not `read_only: true` in the compose file.** That is not stylistic:
**Docker does not mount those three tmpfs** — Podman's `--read-only` does and Docker's does not. The
same line in the compose file would break most of these services on the Docker host that still runs
them. What was measured is Podman's behaviour, so it is expressed where Podman reads it. The table
sits beside `FRONT_END` and `RUN_AS`, which exist for the same reason.

**`keycloak` gets three tmpfs entries with it**, and nothing else changes about how it is delivered:

```ini
Tmpfs=/opt/keycloak/lib/quarkus:rw,tmpcopyup
Tmpfs=/opt/keycloak/data/transaction-logs:rw
Tmpfs=/opt/keycloak/data/tmp:rw
```

> [!warning] The third was missing until 2026-09-17, and the cost was invisible
> Keycloak serves a static theme resource two ways. Asked with `Accept-Encoding: identity` it
> streams the file and answers **200**. Asked with `gzip` it serves a compressed copy from a cache
> under `/opt/keycloak/data/tmp/kc-gzip-cache` — and on a read-only root filesystem that directory
> cannot be created, so the answer is **404**. Not a fallback to the uncompressed file: a 404.
>
> Every browser sends `gzip`, and Go's `http.Transport` **adds it when a request has none**, so a Go
> reverse proxy in front sends it too. `curl` on the host does not. The login page therefore rendered
> **completely unstyled for every real client** while `curl` reported 200 on the same URL, which
> reads as a proxy fault and is not one — the first hand-over of this went to the wrong team.
>
> Measured both ways against the same image, one variable: `gzip` 404 → 200, and `kc-gzip-cache`
> appears. All eight assets of the login page now load, including the fonts.

> [!important] What this says about the re-verification rule below
> Keycloak **stayed healthy throughout**. `/health/ready` answered, the container was up, the
> conformance suite's `containers-running` and `containers-read-only` both passed, and the realm
> served its OIDC discovery document. Everything that is checked was fine; the thing that was
> broken is not checked by anything.
>
> The rule this ADR already states — re-verify Keycloak's entry on every image bump — was followed
> on 26.7.4 and still missed it, because what was re-verified was **that it starts and reports
> ready**. A read-only posture has to be re-verified against what the service *serves*, not only
> against whether it comes up. For Keycloak that means fetching the login page **with the headers a
> browser sends** and checking that its assets arrive.

**Grafana gets one literal environment variable.** Its background installer tries to refresh a
*bundled* plugin inside its own installation directory and logs
`unlinkat /usr/share/grafana/data/plugins-bundled/elasticsearch: read-only file system` at every
start. Grafana serves regardless — measured, `HTTP 200` on `/login` — but an error line per boot is
what the log-based alerting reads. `GF_PLUGINS_PREINSTALL_DISABLED=true` removes it, measured both
ways, and changes nothing this deployment uses: the datasources are provisioned from files and
Elasticsearch is not one of them. It also removes an outbound call at every start.

**The conformance suite asserts the posture on a running host.** `containers-read-only` fails on any
app container with a writable root filesystem, and carries a scenario for `keycloak` specifically,
because it is the entry most likely to be quietly dropped on an image bump.

## Keycloak took two passes, and the first was a wrong conclusion from a real failure

`kc.sh start` without `--optimized` **re-augments the Quarkus application at every boot**. Plain
read-only stops that dead:

```
Caused by: java.nio.file.FileSystemException: /opt/keycloak/lib/quarkus/transformed-…
```

The first reading of that was **Keycloak cannot be read-only**, with a tmpfs refused on the grounds
that it *"hides the installation the augmentation is meant to produce, and what survives a restart
would be whatever the tmpfs happened to hold."* Both halves of that were wrong:

- **Podman's tmpfs takes `tmpcopyup`**, so the image's content is present under the mount. Measured:
  the directory has its entries, and the mount is a tmpfs.
- **The augmentation is already thrown away.** It lands in the container's writable layer and is
  redone at every start — Keycloak says so itself: *Updating the configuration and installing your
  custom providers*. A tmpfs has exactly the lifetime it already had. Nothing was being preserved
  that a tmpfs takes away.

Measured with the **real SPI provider JAR**, staged `0644` into `/opt/keycloak/providers` the way
`deploy.sh` stages it:

|                      Arm                       |  Ready  | cgroup of 2560M |
|------------------------------------------------|---------|-----------------|
| writable, provider present                     | yes     | 991M            |
| read-only, no tmpfs                            | **no**  | —               |
| read-only + tmpfs over `lib`, 172M             | yes     | 630M            |
| **read-only + tmpfs over `lib/quarkus`, 4.7M** | **yes** | **466M**        |

The narrow mount is enough: the 476 paths `podman diff` reported across `lib/` were overlay
metadata, not writes. And the provider is genuinely compiled in rather than skipped — same
configuration, one variable, `generated-bytecode.jar` **768 bytes larger** with the JAR present than
against an empty `providers/`.

> [!important] What this preserves is ADR-0055, and that is the point
> The provider JAR stays its own cosign-signed promotable artifact. A provider-only change still
> **auto-applies**, still recreates **only** keycloak, and still rolls back at **JAR level**. The
> start-time rebuild that read-only appeared to forbid is the mechanism that delivery depends on,
> and it keeps running — into a tmpfs.

## Alternatives considered

**Put `read_only: true` in the compose file.** Refused: Docker mounts no tmpfs under `--read-only`,
so every service that writes to `/tmp` would need an explicit `tmpfs:` entry to keep the Docker host
working — a set of entries that exist only to compensate for a runtime the stack is leaving.

**Bake the SPI provider into a custom Keycloak image and run `start --optimized`.** This is what the
upstream documentation points at, and it is **refused** here. It buys the same read-only property by
dismantling ADR-0055: a provider-only change would become a Keycloak **image** rebuild, and an image
change is operator-gated by the `REQ-OPS-006` carve-out, so it would no longer auto-apply and would
no longer roll back at JAR level. It also means owning a Keycloak image against upstream CVEs. The
only thing it adds over the tmpfs is a faster start, and that is not worth a delivery path.

**A tmpfs over the whole of `/opt/keycloak/lib`.** Works — measured — but costs 172M of RAM to hold
a tree of which 4.7M is ever written. The narrow mount is the same property for 3% of the memory.

**Leave Keycloak writable.** Refused once the tmpfs was measured: the identity provider is the worst
container to leave modifiable within its own lifetime, and containers here live for weeks.

**Drop the `tmpfs:` entries `edge` carries, now that Podman provides them.** Refused, and only one of
the two would have been safe to drop anyway: `edge` mounts `/tmp` — which Podman does supply — and
**`/var/cache/nginx`, which it does not**. Podman covers `/run`, `/tmp` and `/var/tmp` and nothing
else, so a service writing anywhere else still needs its own entry. `/tmp` stays as well, because it
is what makes the requirement legible to a reader who does not know the defaults.

**Assert the posture by reading the unit files instead of the running host.** Refused for the same
reason as ADR-0189's uid check: a unit file records what was asked for.

## Consequences

- Eighteen of eighteen containers cannot rewrite their own installation. Whatever gets inside one is
  gone at the next restart, and the image is the image.
- Four `Tmpfs=` lines exist across the whole stack, on `keycloak` and `edge`, and each one is there
  because something was measured writing to that path.
- **Keycloak's entry is the fragile one**, and it was exercised immediately: `main` bumped Keycloak
  to 26.7.4 the next day, and the re-measurement against the new digest came back ready at 466M with
  the provider compiled in. The rule held on its first use. An upstream change to where the
  augmentation writes turns into a container that fails at start — loud, health-gated, rolled back by `REQ-OPS-003` — but it
  has to be re-verified on every Keycloak image bump, the same rule `REQ-OPS-014` already applies to
  the capability sets.
- Podman's `/tmp` tmpfs is now load-bearing for the JVM modules. It is a documented default rather
  than a configured one, which is why it is written down in the generator and here.
- `REQ-OPS-014`'s statement that read-only is not part of the baseline is amended in the same change.

## Status of this decision

Accepted. Eighteen units carry `ReadOnly=true`, `scripts/generate-quadlet.py` refuses a table that
disagrees with itself or names something that is not a container, and `containers-read-only` has
three red scenarios that behaved. Nothing is deployed: these are Quadlet units for a host that does
not serve traffic yet.
