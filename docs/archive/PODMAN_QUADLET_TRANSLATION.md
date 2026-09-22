> **Archived 2026-09-22.** Doc type: Historical plan — frozen, kept as a record and no longer updated. Superseded by the generated units in [`quadlet/`](../../quadlet/) and their generator [`scripts/generate-quadlet.py`](../../scripts/generate-quadlet.py), which the `quadlet-drift` CI job keeps in step with the compose files.
>
> **Current truth:** [`quadlet/`](../../quadlet/), [`scripts/generate-quadlet.py`](../../scripts/generate-quadlet.py), [arc42 §7](../arc42/07-deployment-view.md). Index of the archive: [`README.md`](README.md).

# Compose → Quadlet — the translation

> **Doc type:** Implementation plan — **living**, and **not yet validated on a host**. Phase 2 of
> [`PODMAN_MIGRATION_PLAN.md`](PODMAN_MIGRATION_PLAN.md), drafted before the VM exists so that the
> places where the translation is *not* one-to-one are known before anybody is debugging them at
> two in the morning. Everything marked *measured* was; the rest is derived from
> `podman-systemd.unit(5)` as CentOS Stream 10 ships it and has to survive first contact.
> **Decision record:** [ADR-0163](../adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md).
> **Last updated:** 2026-09-16.

---

## 1. What is being translated

Twenty-two running containers across two Compose projects, nineteen networks, three named volumes.
The inventory, with the measured resource limits that `REQ-OPS-020` fixes:

|           Service            | Memory | CPUs | Networks |                Becomes                |
|------------------------------|--------|------|----------|---------------------------------------|
| `edge`                       | 192M   | 1.0  | 6        | `.container` — **the hard one**, §5   |
| `acme`                       | 128M   | 0.5  | 1        | `.container` — the other hard one, §6 |
| `keycloak`                   | 2560M  | 3.0  | 4        | `.container`                          |
| `backend`                    | 2048M  | 3.0  | 7        | `.container`                          |
| `frontend`                   | 1792M  | 2.0  | 5        | `.container`                          |
| `ingest`                     | 512M   | 1.5  | 4        | `.container`                          |
| `db-backend`                 | 1536M  | 4.0  | 1        | `.container`                          |
| `db-keycloak`                | 512M   | 1.0  | 1        | `.container`                          |
| `redis`                      | 512M   | 1.0  | 3        | `.container`                          |
| `prometheus`                 | 1024M  | —    | 2        | `.container`                          |
| `grafana`                    | 1024M  | —    | 2        | `.container`                          |
| `loki`                       | 384M   | —    | 1        | `.container`                          |
| `tempo`                      | 1G     | —    | 1        | `.container`                          |
| `alertmanager`               | 48M    | —    | 1        | `.container`                          |
| `blackbox-exporter`          | 64M    | —    | 3        | `.container`                          |
| `postgres-exporter-backend`  | 32M    | —    | 2        | `.container`                          |
| `postgres-exporter-keycloak` | 32M    | —    | 2        | `.container`                          |
| `redis-exporter`             | 32M    | —    | 2        | `.container`                          |
| `alloy`                      | 512M   | —    | 3        | **host service** — §7                 |
| `node-exporter`              | 32M    | —    | 1        | **host service** — §7                 |
| `cadvisor`                   | 128M   | —    | 2        | **deleted** — §7                      |
| `socket-proxy`               | 32M    | —    | 1        | **deleted** — §7                      |
| —                            | —      | —    | —        | **new:** `prometheus-podman-exporter` |

The app limits total **9.75 GB** and the monitoring limits **3.4 GB**. That is the arithmetic behind
asking for 16 GB rather than 12 on the testing VM: 13.2 GB of ceilings on a 12 GB host is
over-committed before a single process starts.

---

## 2. The mechanical part

Most of it is a rename. This table is the whole of the easy half:

|                 Compose                  |                                     Quadlet `[Container]`                                     |
|------------------------------------------|-----------------------------------------------------------------------------------------------|
| `image: x@sha256:…`                      | `Image=x@sha256:…`                                                                            |
| `user: 101:101`                          | `User=101` + `Group=101`                                                                      |
| `read_only: true`                        | `ReadOnly=true`                                                                               |
| `cap_drop: [ALL]`                        | `DropCapability=ALL`                                                                          |
| `cap_add: [CHOWN, …]`                    | `AddCapability=CHOWN …`                                                                       |
| `security_opt: [no-new-privileges:true]` | `NoNewPrivileges=true`                                                                        |
| `tmpfs: [/tmp:rw,…]`                     | `Tmpfs=/tmp:rw,…`                                                                             |
| `volumes: [/src:/dst:ro]`                | `Volume=/src:/dst:ro`                                                                         |
| `networks: [net-a, net-b]`               | `Network=net-a.network` (repeated)                                                            |
| `ports: ["80:8080"]`                     | `PublishPort=80:8080`                                                                         |
| `healthcheck.test`                       | `HealthCmd=` + `HealthInterval=` + `HealthTimeout=` + `HealthRetries=` + `HealthStartPeriod=` |
| `deploy.resources.limits.memory`         | `Memory=` — **6.1 only**, see §3                                                              |
| `deploy.resources.limits.cpus`           | `PodmanArgs=--cpus=…` — no Quadlet key exists                                                 |
| `ulimits.nofile`                         | `PodmanArgs=--ulimit nofile=65536:65536`                                                      |
| `stop_grace_period: 30s`                 | `[Service] TimeoutStopSec=30`                                                                 |
| `restart: unless-stopped` / `always`     | `[Service] Restart=always` + `[Install] WantedBy=default.target`                              |
| `depends_on`                             | `[Unit] After=` + `Requires=` — see §4                                                        |
| `environment:`                           | `Environment=` per entry, or `EnvironmentFile=`                                               |

Networks and volumes get their own unit types:

```ini
# net-proxy-api.network
[Network]
NetworkName=net-proxy-api
Subnet=172.28.13.0/24
Gateway=172.28.13.1
Subnet=fd00:28:13::/64
Gateway=fd00:28:13::1
IPv6=true
Internal=true
```

```ini
# edge-certs.volume
[Volume]
VolumeName=edge-certs
```

---

## 3. The traps

Six places where the translation is not a rename. Each one produces a stack that starts.

### 3.1 `${VAR}` in a volume path does not expand

Compose interpolates `${IRI_KEYSTORE_HOST_PATH:-./keystore.p12}` when it *loads the file*. Quadlet
units are static: `EnvironmentFile=` sets variables **inside the container**, not in the unit, and
`Volume=` is turned into a `--volume` argument at generation time. So this, which appears on four
services:

```yaml
volumes:
  - ${IRI_KEYSTORE_HOST_PATH:-./keystore.p12}:/run/secrets/keystore.p12:ro
```

has no direct equivalent. Two ways out, and the choice belongs with the config bundle's design:

- **Fix the paths.** `/var/iri/secrets/keystore.p12` is what the variable resolves to on both hosts
  anyway; the indirection exists for local stacks, which do not use these units.
- **Render the units when the bundle is built**, so the bundle carries resolved paths. This keeps
  the variable meaningful but makes the unit files a build artefact rather than a source file.

The first is simpler and is what §8 assumes. **Whichever is chosen, it must be decided before the
units are written, not discovered when a unit silently mounts a directory named `${IRI_…}`.**

### 3.2 `Memory=` exists only from Podman 6

Measured: Debian 13's Quadlet 5.4.2 has **no** `Memory=` key, and CentOS Stream 10's 6.1 has it.
Since the platform is CentOS Stream 10 the key is available, and the `PodmanArgs=--memory=…`
workaround the plan first assumed is unnecessary. `PidsLimit=` exists in both. There is no CPU key
in either, so `cpus` goes through `PodmanArgs` regardless.

### 3.3 Compose's `depends_on: service_healthy` is two directives, not one

`Requires=` orders *startup*, not readiness. The readiness half is `Notify=healthy` on the
**dependency**, which postpones that unit's own start-up notification until Podman has marked the
container healthy. Without it, `backend` starts the instant `db-backend`'s container exists, which
is exactly the race Compose's `condition: service_healthy` was added to remove.

This is one of the few places Quadlet is *better* than what it replaces: it is systemd's own
readiness protocol rather than a compose-specific flag, and `systemctl --user start backend` then
genuinely blocks until the thing is up.

### 3.4 `isolate` now defaults to `strict`

Netavark 2 isolates bridge networks from one another unless told otherwise; the man page names
`isolate=false` as the way back to pre-Podman-6 behaviour. The topology never routes *between*
bridges — membership is the only path — so `strict` agrees with the design and is arguably what it
always wanted. It is still a default that changed underneath the plan, so it gets asserted.

### 3.5 There is no masquerade switch

`net-edge-ingress` runs with `com.docker.network.bridge.enable_ip_masquerade=false`, which is how
the edge has ingress but no egress. netavark 2.1's documented bridge options are `mtu`, `metric`,
`no_default_route` and `isolate`; masquerading is tied to `mode=managed`. The candidate equivalent
is `-o no_default_route=true` — the edge is on that one non-internal network and five `Internal=true`
ones, so with no default route anywhere it has no egress. **That is a hypothesis about a security
control and it gets measured, not assumed.**

### 3.6 `Internal=true` may or may not keep inbound DNAT

Under Docker, `internal: true` removed **inbound** DNAT as well as outbound NAT — which no
documentation said, and which is the entire reason `net-edge-ingress` exists as a separate bridge
with one member. Whether netavark behaves the same decides whether that bridge is still needed.
Measure it; do not read it.

---

## 4. Ordering, in place of `depends_on`

Compose's dependency graph becomes systemd's. The four edges that matter:

```ini
# backend.container
[Unit]
After=db-backend.service keycloak.service
Requires=db-backend.service keycloak.service
```

with `Notify=healthy` set on `db-backend` and `keycloak`. Likewise `frontend` after
`backend keycloak redis`, and `ingest` after `backend redis`.

> [!note] `Requires=` is stronger than Compose was, and that is a decision
> `Requires=` means a dependency that fails takes the dependent down with it. Compose's
> `depends_on` only ordered start-up. If that turns out to be too strict during a partial restart,
> the softer pair is `Wants=` + `After=` — but the stricter form is the better default for a stack
> whose health gate is supposed to fail loudly.

---

## 5. The edge, in full

The hardest unit, because it carries the port publishing, six networks, a read-only root, three
tmpfs mounts and the `nofile` ceiling that its `worker_connections` depends on.

```ini
# ~iri/.config/containers/systemd/edge.container
[Unit]
Description=Profit Basetool — internet-facing nginx
After=frontend.service backend.service keycloak.service ingest.service
Wants=frontend.service backend.service keycloak.service ingest.service

[Container]
Image=docker.io/nginxinc/nginx-unprivileged:1.31.5-alpine@sha256:19c132c9ab02d3b783f478743dafc7a7f42e27aa7d2bdcbec1bb1128ca8f2a07
ContainerName=edge
User=101
Group=101
ReadOnly=true
DropCapability=ALL
NoNewPrivileges=true

Tmpfs=/tmp:rw,noexec,nosuid,size=64m
Tmpfs=/var/cache/nginx:rw,noexec,nosuid,size=64m

PublishPort=80:8080
PublishPort=443:8443

Network=net-edge-ingress.network
Network=net-proxy-frontend.network
Network=net-proxy-keycloak.network
Network=net-proxy-ingest.network
Network=net-proxy-grafana.network
Network=net-proxy-api.network

Volume=/var/iri/code/docker/edge:/etc/nginx/edge:ro
Volume=edge-certs.volume:/etc/nginx/certs:ro
Volume=edge-acme-webroot.volume:/var/www/acme:ro
Volume=/var/iri/monitoring/certs/basetool-ca.crt:/etc/nginx/upstream-ca.crt:ro
Volume=/var/iri/code/docker/maintenance/static:/usr/share/nginx/html/maintenance:ro

Environment=EDGE_HOST_FRONTEND=%E{EDGE_HOST_FRONTEND}
# ... see §3.1: the host names come from the bundle, not from a shell variable

Exec=/bin/sh /etc/nginx/edge/render-and-run.sh

# :8081, NOT :8080. ADR-0187 made the public listener speak PROXY protocol, which rejects a
# plain HTTP probe, so /healthz moved to a loopback-only listener on 8081
# (docker/edge/conf.d/05-default.conf.template). A probe against :8080 now falls into
# `location / { return 308 ... }` and follows a redirect to an unresolvable host.
HealthCmd=wget -q -O /dev/null http://127.0.0.1:8081/healthz
HealthInterval=30s
HealthTimeout=5s
HealthRetries=3
HealthStartPeriod=10s
Notify=healthy

Memory=192M
PodmanArgs=--cpus=1.0 --ulimit nofile=65536:65536

[Service]
# Derived from the health numbers above: start_period + retries x (interval + timeout), plus a
# margin for the pull and the container's own creation. Notify=healthy makes this unit
# Type=notify, and systemd's 90s default would otherwise kill a slow start.
TimeoutStartSec=175
Restart=always
# NO TimeoutStopSec here. An earlier revision of this document showed one; the generator emits it
# only from a compose `stop_grace_period:`, and the edge service declares none.

[Install]
WantedBy=default.target
```

> [!warning] Three things about this unit are not settled and are marked as such
> The `Environment=` line for the vhost names depends on §3.1's decision. `PublishPort=80` needs
> §5 of the bootstrap document — a rootless process cannot bind it without help. And the whole unit
> is worthless unless `rootless_port_forwarder="pasta"` is in effect, because otherwise nginx sees
> the forwarder's address for every client on earth.

---

## 6. The certificate handover

`acme` is small and is the most likely thing to break differently, because it crosses two
boundaries that did not exist under Docker at the same time.

```ini
# acme.container
[Container]
Image=docker.io/goacme/lego:v5.4.1@sha256:ac04a7aaac0270ca2c32f1e79b157087d763e78c4473551c6e093070614536e2
ContainerName=acme
DropCapability=ALL
AddCapability=CHOWN
NoNewPrivileges=true
Network=net-acme-egress.network
Volume=edge-acme-state.volume:/data
Volume=edge-acme-webroot.volume:/webroot
Volume=edge-certs.volume:/certs
Memory=128M
PodmanArgs=--cpus=0.5
Entrypoint=/bin/sh
Exec=-c '…the publish loop, unchanged…'

[Service]
Restart=always
```

Three conditions have to hold, and all three are checkable in an afternoon:

1. **`CAP_CHOWN` inside the namespace** must be enough to `chown 101:101` a published file. 101 is
   well inside a 65536-wide subuid range, so it should be — measured, not assumed.
2. **`acme` and `edge` must share one userns mapping.** `--userns=auto` gives each container its
   own range and would break the handover outright. The units therefore take the **default**
   mapping, and that has to be stated here rather than left to whoever writes them next.
3. **SELinux must not eat it.** The container writes a file that another container opens, under a
   label transition. A failure here and a failure in (1) produce the identical symptom: the edge
   exits three seconds after start and the deploy rolls back. `ausearch -m AVC -ts recent` is how
   they are told apart.

`REQ-OPS-026` — a renewed certificate is not delivered until the edge can open it — is the
acceptance, unchanged.

---

## 7. The monitoring plane splits in two

Not every container survives as a container, and this is a design change rather than a translation.

**Deleted outright:**

- **`socket-proxy`.** It exists only to hand cAdvisor and Alloy a GET-only view of the Docker
  socket. There is no Docker socket.
- **`cadvisor`.** Its rootless-Podman support is closed as not planned upstream. Its series come
  back from `prometheus-podman-exporter` plus
  [`scripts/cgroup-container-metrics.py`](../../scripts/cgroup-container-metrics.py) — see
  [`PODMAN_MIGRATION_PLAN.md`](PODMAN_MIGRATION_PLAN.md) §10.

**Moved out of containers, onto the host:**

- **`node-exporter`** mounts `/:/host:ro,rslave` **and `/run/systemd/private`**. The second is
  root-owned and is how its systemd collector works — a rootless container cannot reach it, and
  `node_systemd_unit_state` is precisely the new signal this migration gains, because Quadlet units
  *are* systemd units. It also owns the textfile directory the cgroup collector writes into. As a
  packaged host service it needs none of those mounts and gets the system bus for free.
- **`alloy`** mounts `/var/log:/hostlog:ro` and carries `group_add: [473, 4]` to read files like
  `auth.log`, which is `root:adm 0640`. A rootless container's supplementary groups are namespace
  groups, not host groups, so that read stops working. Running it as a host service restores it and
  removes the last consumer of the container socket at the same time.

> [!important] This is a reduction in privilege, not an expansion
> Both were already reaching outside their container for host-level data; the container was
> decoration around a host-level job. Making them host services states that honestly, and it lets
> every *remaining* container be genuinely rootless with nothing mounted from `/`, `/sys` or
> `/run`.

**New:** `prometheus-podman-exporter`, version **v2** (the matrix is v2 ↔ Podman 6, ≥1.11 ↔ 5.x),
as a rootless container with the podman socket of its own user.

---

## 8. What has to be decided before the units are written

Five things, each of which changes the files rather than being tuned afterwards:

1. **§3.1** — static paths in the units, or units rendered when the bundle is built.
2. **Bootstrap §5** — which mechanism lets the forwarder bind `:80`/`:443`.
3. **§3.5** — whether `no_default_route=true` really removes the edge's egress.
4. **§3.6** — whether `Internal=true` keeps inbound DNAT, which decides whether
   `net-edge-ingress` still needs to exist.
5. **§4** — `Requires=` or `Wants=` for the four dependency edges.

Questions 3 and 4 are measurements, and they are Phase 1's. Questions 1, 2 and 5 are decisions, and
they belong to the same sitting.

## 9. Container hardening — what is already there, and what the migration adds

Measured across all twenty translated services on 2026-09-16, from the compose files rather than
from impressions:

|       Control       |                                  Coverage today                                  |
|---------------------|----------------------------------------------------------------------------------|
| `no-new-privileges` | **20 of 20**                                                                     |
| `cap_drop: ALL`     | 19 of 20 — the exception is `node-exporter`, which becomes a host service anyway |
| `pids` cap          | 20 of 20 (and the generator lost all of them once — see below)                   |
| `oom_score_adj`     | the 9 monitoring containers, deliberately                                        |
| `read_only`         | **1 of 20** — the edge, and nothing else                                         |
| explicit `user:`    | the edge (101) and the three app modules (10001); elsewhere the entrypoint drops |

So the posture is already strong on capabilities and privilege escalation, and the one wide-open
surface is the **writable root filesystem**.

### What rootless Podman adds for free

Three things that are not configuration and cannot be forgotten:

- **A user namespace.** Container root is a subuid on the host, not root. A container escape lands
  on an unprivileged uid with no host presence rather than on uid 0.
- **SELinux**, enforcing, in place of AppArmor — a second confinement layer under the namespace,
  and the reason the bootstrap document gives it its own section.
- **No daemon, and therefore no socket.** `socket-proxy` exists today only to hand two monitoring
  components a GET-only view of a root-equivalent socket. Both it and the socket disappear.

### `read_only` — measured, and honestly only indicative

Fifteen of the images are public, so they were run on the CentOS Stream 10 host with `--read-only`
to see which tolerate it.

**Six start with no writable path whatsoever** — redis, prometheus, alertmanager,
blackbox-exporter, redis-exporter and postgres-exporter. For those, `ReadOnly=true` is free.

The others need one writable path each, and **in production they already have it**: `--read-only`
makes the *image's* filesystem read-only and leaves mounted volumes writable, and loki, tempo,
grafana and the databases all mount their data directory. Grafana confirmed the shape directly: it
failed with no writable path and started once `/var/lib/grafana` was writable, which is exactly the
mount it has in production.

> [!warning] That experiment was indicative, not conclusive, and the difference matters
> The containers were started **without their real volumes and configuration**, so a failure there
> means "needed a writable path", not "cannot run read-only". The `postgres` row is a test defect
> rather than a finding at all — it was run with `--version`, which exits immediately.
>
> Turning `read_only: true` on for a production container on this evidence is precisely the kind of
> change that looks proven and breaks at an awkward hour. It is a **Phase 2 task**: bring each
> service up read-only *with* its real mounts on the testing host, and let the conformance suite
> say whether the stack still works. The measurement above says the task is worth doing and roughly
> how much of it is free; it does not say it is done.

### What is deliberately not changed

`redis`, `db-backend` and `db-keycloak` add back `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `SETGID` and
`SETUID` so their entrypoints can take ownership of the data directory and drop to the service user.
The Ansible role now pre-owns those directories, so in principle the chown is a no-op and the
capabilities could go — but "in principle" is not a reason to remove a capability an entrypoint
asks for. It is a Phase 2 experiment on the testing host with an easy verdict: the container either
starts or it does not.

### 10.1 Two controls the generator lost, and the guard that now prevents a third

Worth recording because the mistake is instructive rather than embarrassing.

`PidsLimit` was missing from **all nineteen units**. The caps are real — edge 512, the JVMs and
Keycloak 2048, acme 128, measured by reading `/sys/fs/cgroup` on production — but compose spells
them under `deploy.resources.limits.pids`, and the generator read only `memory` and `cpus` there
while checking the top-level `pids_limit` key this repository does not use. That cap is what
stopped the 2026-07-12 native-thread-OOM, and `ContainerPidsHigh` measures against it.

`oom_score_adj: 500` was missing from the nine monitoring containers. It makes the monitoring plane
**more** attractive to the OOM killer than the application, so the kernel takes Grafana before the
backend. Quadlet has no key for it; `podman run --oom-score-adj` does, and a positive adjustment is
what an unprivileged process may set, so it survives rootless.

The durable fix is neither of those two keys. The generator now carries an allow-list of the
compose keys it understands and **refuses** on anything else — because a generator that drops what
it does not recognise is worse than no generator, its output being indistinguishable from complete.
Its own drift check cannot help here: that compares generated against generated. The allow-list
found `oom_score_adj` on its first run, minutes after `pids` had been found by hand.

## 10. `restart:` — the translation that looked like one line and is four

`restart: unless-stopped` becomes **four keys across two sections**, and the reason is a measured
failure rather than a preference. On the testing VM on 2026-09-16 the edge burned five restarts in
seconds and stopped for good with *"Start request repeated too quickly"*. `Restart=always` on its
own does not mean what `unless-stopped` means.

```ini
[Unit]
StartLimitIntervalSec=0

[Service]
Restart=always
RestartSec=1
RestartSteps=6
RestartMaxDelaySec=60
```

|                    Key                     |                                                             Why it is there                                                              |
|--------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `Restart=always`                           | the obvious half, and the only half that was there before                                                                                |
| `RestartSec=1`                             | systemd's default is 100ms, so a fast-failing container burns its whole allowance inside one second                                      |
| `RestartSteps=6` + `RestartMaxDelaySec=60` | grow the interval geometrically to a 60s ceiling — the shape Docker's restart policy has. Both need systemd >= 254; the target ships 257 |
| `StartLimitIntervalSec=0`                  | never give up, which is what `unless-stopped` says                                                                                       |

`StartLimitIntervalSec=` lives in **`[Unit]`**. `systemd.service(5)` mentions it exactly once, and
only to point elsewhere — *"service restart is subject to unit start rate limiting configured with
StartLimitIntervalSec= and StartLimitBurst=, see systemd.unit(5) for details"*. Put it in
`[Service]` and it is silently ignored, so the unit reads as though it were limited and is not.
Verified against the shipped man pages on both candidate platforms.

> [!important] Backoff and the start limiter cancel each other out, quietly
> They cannot be tuned independently. Once the interval reaches the 60s ceiling, at most ten starts
> fit into a ten-minute window — so a burst threshold of 60 in 600s is **never reached** and the
> limit never fires. A unit configured that way would still *carry* a limit, and anyone reading it
> later would believe in a boundary that no longer exists.
>
> So the give-up rule is made as **one** decision and written where it can be seen. Here it is
> *never give up*, for three reasons: it is what `unless-stopped` already does today, so the
> migration changes no behaviour; this stack is **monitored**, so a `failed` unit is not the only
> signal anyone would get that something is down; and an edge that stays down after five minutes is
> a total outage, where one that retries every 60s recovers by itself when a slow database or a
> briefly unreachable registry clears.
>
> An unmonitored service would be argued the other way round, and correctly — there a visible
> `failed` is the only signal there is. The point is that it is a choice, not an interaction.

**Validated on the host**, not assumed: podman's own Quadlet generator passes all four through, and
`StartLimitIntervalSec=0` lands in the generated unit's `[Unit]` section. All eighteen `.container`
units carry all four; the generator emits no errors and no warnings.

## 11. Acceptance

The Phase 0 conformance suite, green against the host running these units — with
`client-address-visible` the one that matters, because every other check can pass while the edge
has quietly become a single bucket for the entire internet.
