# Compose → Quadlet — the translation

> **Doc type:** Implementation plan — **living**, and **not yet validated on a host**. Phase 2 of
> [`PODMAN_MIGRATION_PLAN.md`](PODMAN_MIGRATION_PLAN.md), drafted before the VM exists so that the
> places where the translation is *not* one-to-one are known before anybody is debugging them at
> two in the morning. Everything marked *measured* was; the rest is derived from
> `podman-systemd.unit(5)` as CentOS Stream 10 ships it and has to survive first contact.
> **Decision record:** [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md).
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

HealthCmd=wget -q -O /dev/null http://127.0.0.1:8080/healthz
HealthInterval=30s
HealthTimeout=5s
HealthRetries=3
HealthStartPeriod=10s
Notify=healthy

Memory=192M
PodmanArgs=--cpus=1.0 --ulimit nofile=65536:65536

[Service]
Restart=always
TimeoutStopSec=30

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
  [`scripts/cgroup-container-metrics.py`](../scripts/cgroup-container-metrics.py) — see
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

## 9. Acceptance

The Phase 0 conformance suite, green against the host running these units — with
`client-address-visible` the one that matters, because every other check can pass while the edge
has quietly become a single bucket for the entire internet.
