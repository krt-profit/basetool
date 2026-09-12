# ADR-0163 — The container runtime becomes rootless Podman on Debian 13, on a rebuilt host

- **Status:** Proposed
- **Date:** 2026-09-12
- **Deciders:** @greluc (four choices recorded below), Claude (analysis and measurement)
- **Related:** [ADR-0049](0049-host-configuration-as-a-promotable-artifact.md) ·
  [ADR-0072](0072-monitoring-stack-decoupled-from-the-app-deploy.md) ·
  [ADR-0112](0112-edge-per-ip-limit-keys-on-the-ipv6-64-prefix.md) ·
  [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) ·
  specs `REQ-OPS-002`, `REQ-OPS-003`, `REQ-OPS-004`, `REQ-OPS-013`, `REQ-OPS-014`,
  `REQ-OPS-015`, `REQ-OPS-022`, `REQ-OBS-014`, `REQ-SEC-023` ·
  [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md)

## Context

Every container on the production host runs under a Docker daemon that runs as root. That daemon's
socket is root-equivalent, and two components in the monitoring plane read it — cAdvisor and Alloy,
both through a GET-only `docker-socket-proxy` that exists precisely because handing them the raw
socket would hand them the host.

[ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) took the internet-facing
service as far as it goes inside that model: uid 101, `cap_drop: [ALL]`, `read_only: true`, no
egress. What it cannot remove is the daemon underneath. Rootless Podman removes it: there is no
long-running root process, containers run under an unprivileged user in its own user namespace, and
a compromise inside a container reaches a subuid range rather than root.

That is the whole of the security case, and it should be read next to its price. This is not a
runtime swap. It changes the runtime, the orchestration model, the delivery script, three operations
scripts, and the entire container-observability plane, because all of them are written against
Docker.

### What was measured before deciding

Nothing below is recalled; each line was established on 2026-09-12.

|                                           Claim                                           |                                                                               Evidence                                                                               |
|-------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| The production host has no Podman, and Ubuntu 24.04 offers only 4.9.3                     | `apt-cache policy podman` on the host                                                                                                                                |
| Podman 4.x uses slirp4netns, which does not preserve the client source address by default | Podman 5 is the release that makes `pasta` the default                                                                                                               |
| Rootless cannot bind :80/:443 as configured                                               | `net.ipv4.ip_unprivileged_port_start = 1024`                                                                                                                         |
| The `deploy` user is not prepared for rootless                                            | no `/etc/subuid` or `/etc/subgid` entry, no lingering                                                                                                                |
| cgroup v2 is in place                                                                     | `stat -fc %T /sys/fs/cgroup` → `cgroup2fs`                                                                                                                           |
| There is no officially supported Podman 5 repository for Ubuntu 24.04                     | the Kubic repositories were retired; the upstream discussion's own recommendation is APT pinning from Ubuntu 25.04, which it calls "dangerous if you aren't careful" |
| Ubuntu 26.04 LTS is released and ships Podman 5.7.0 + passt                               | the `resolute`, `resolute-updates` and `resolute-security` suites all resolve; archive `Packages` index                                                              |
| The in-place LTS upgrade path is not open                                                 | `meta-release-lts` lists Resolute with `Supported: 0`; `do-release-upgrade -c` refuses                                                                               |
| Debian 13 "trixie" ships Podman 5.4.2 + passt + netavark 1.14                             | Debian archive `Packages` index                                                                                                                                      |
| The testing environment is already Debian 13                                              | `docs/deployment.md` records its bootstrap, including two Debian-specific traps                                                                                      |

The last line is the one that reshaped the decision. `REQ-OPS-022`'s non-production environment runs
the identical `deploy.sh` on the identical timer over a separate promotion tag — but on a different
distribution from production. Every rehearsal there has therefore been proving slightly less than it
appeared to.

## Decision

**The container runtime becomes rootless Podman 5.4.2 on Debian 13 "trixie", orchestrated by
Quadlet, on a production host that is rebuilt rather than upgraded in place.**

Four choices, made by @greluc on 2026-09-12:

1. **Distribution: Debian 13 "trixie"** — for both production and testing. The testing host already
   is Debian 13; only production is rebuilt. From then on the two environments match, which is the
   entire point of having a second one. Podman 5.4.2 carries `pasta`, which is the hard requirement.
2. **Podman version: the distribution's own.** No third-party repository and no APT pinning across
   releases. This follows from choice 1 and retires the only genuinely dangerous option that was on
   the table.
3. **Orchestration: Quadlet**, the systemd-native model — one unit per container, no Compose
   emulation layer.
4. **Observability: rebuilt with the migration**, not after it. The `container_*` metric family
   carries `ContainerRestartLoop`, `ContainerOomKilled`, `ContainerMemoryHigh`, `ContainerPidsHigh`
   and both `*MetricsMissing` guards. Two of those were the safety net under the ACME defects of
   2026-09-12; the migration will not proceed with them blind.

And one that follows from them:

5. **The production host is rebuilt, not upgraded.** A fresh Debian 13 host is built beside the
   current one, verified in full, and cut over once. The old host stays as the rollback: going back
   is a DNS change, not a restore.

### Why rebuilding rather than upgrading

An in-place release upgrade is unavailable anyway (`Supported: 0`), but it would be the wrong choice
even if it were open. It mutates the running production host, disables third-party repositories
mid-flight — the Docker repository among them — and its rollback is a restore from backup. Building
beside costs one server for the overlap and buys three things the migration needs: the new host can
be proven before it serves anything, production is touched exactly once, and the way back is a
switch rather than a recovery. It also exercises the disaster-recovery path for real, which
`restore-drill.sh` today only rehearses in part.

## Alternatives considered

**Stay on Docker.** The cheapest option and not an unreasonable one: the edge is already
unprivileged with zero capabilities and a read-only filesystem, so the marginal gain is the daemon
and its socket rather than the exposed surface. Rejected because the socket is root-equivalent and
two monitoring components hold a path to it; the proxy in front of them mitigates, it does not
remove.

**Podman 4.9.3 from Ubuntu, with `port_handler=slirp4netns`.** Preserves the source address and
needs no new package source, but it is measurably slower than the default handler and it is a
configuration whose correctness rests on a flag rather than on the default. Rejected once Debian 13
made Podman 5 available without either compromise.

**Podman 5 on Ubuntu 24.04 via APT pinning from Ubuntu 25.04.** The upstream discussion's own
recommendation, and its own words are "this can be dangerous if you aren't careful". Mixing a
non-LTS release's packages into an LTS production host, for a component chain that includes
`netavark`, `crun` and `passt`, is not a supply chain worth having.

**Ubuntu 26.04 LTS.** Genuinely attractive: Podman 5.7.0, a `pasta` eight months newer, support to
2031, and the smallest delta from the current host's Ubuntu-shaped bootstrap. Rejected in favour of
matching the existing testing host — a rehearsal environment that differs from production is worth
less than a newer package, and aligning them is a one-time opportunity that only exists because the
host is being rebuilt anyway.

**Compose over Podman's Docker-compatible socket.** The smaller step: `docker-compose.yml` and
`deploy.sh` survive largely intact and the change is reversible in pieces. Rejected by @greluc in
favour of Quadlet. The trade is real and is recorded here because it is the decision most likely to
be revisited: Quadlet is the model Podman actually supports, but it rebuilds the delivery mechanism
— digest pinning, the health gate, the automatic rollback and the config bundle all have to be
re-established rather than carried across.

**A hybrid that leaves the edge rootful.** Sidesteps the source-address question entirely, and
halves the benefit: the internet-facing service would be the one still running on the old model.

## Consequences

### What has to be rebuilt

- **Delivery.** `deploy.sh` against Quadlet units instead of Compose, including a replacement for
  `docker buildx imagetools inspect`, which has no Podman equivalent (`skopeo` is the candidate).
  `REQ-OPS-003`'s digest pin, health gate and automatic rollback are guarantees, not implementation
  details: they move across or the migration does not ship.
- **The configuration artifact.** ADR-0049's bundle carries a Compose file today; it carries unit
  files afterwards.
- **Operations scripts.** `backup.sh`, `docker-cleanup.sh` and `restore-drill.sh` all speak Docker.
- **Container observability.** cAdvisor does not support rootless Podman. Both it and Alloy's
  `discovery.docker` need replacing, and every `container_*` alert re-pointed with its promtool
  tests.
- **Network semantics.** `internal: true`, the `com.docker.network.bridge.enable_ip_masquerade`
  driver option, the pinned subnets and the dual-stack ingress bridge are Docker vocabulary. Netavark
  equivalents exist for most of it; "most" is what the rehearsal is for.
- **Host preparation.** subuid/subgid ranges, lingering for the service user, cgroup delegation, and
  a decision on binding :80/:443 (lowering `net.ipv4.ip_unprivileged_port_start` is host-wide and
  security-relevant — it lets any unprivileged process bind those ports).

### What must not regress

The migration is gated on a conformance suite that asserts the invariants against a *running* host
rather than against configuration: the deny-by-default allow-list and its 221 directives, the per-IP
limiter keyed on the real client address, the vhost topology, certificate handover, the health
endpoints, container metrics present, log streams flowing. It runs green against the current Docker
stack first — otherwise it is measuring the wrong thing — and is the acceptance gate for every later
phase.

### The one measurement that can stop this

Whether `pasta` preserves the client source address, for IPv4 **and** IPv6. `REQ-SEC-023`'s limiter
and ADR-0112's `/64` key both read `$remote_addr`; if the forwarded address is a gateway, the limiter
collapses into a single bucket and the access logs stop identifying anyone. IPv4 is measurable on the
testing host. IPv6 depends on whether that host carries v6 at all; if it does not, the measurement
moves to the new production host, which exists and is idle before it serves traffic.

A negative result does not mean "work around it". It means this ADR is rejected and the host stays
on Docker.

### Cost

One additional server for the overlap, an IP change (DNS, SSH, any address-based rules), and a
migration that touches four subsystems. Certificates should be seeded onto the new host from the old
one rather than re-issued — the Let's Encrypt duplicate-certificate limit for this SAN set is five
per week and two were used on 2026-09-12.

## Status of this decision

Proposed. The plan it governs is [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md); no host
has been touched. The phases are sequenced so that the measurements which could reject this ADR come
first and cost nothing but time.
