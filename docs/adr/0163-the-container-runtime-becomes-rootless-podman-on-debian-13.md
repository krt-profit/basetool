# ADR-0163 — The container runtime becomes rootless Podman on Rocky Linux 10, on a rebuilt host

- **Status:** **Accepted 2026-09-16**, with choice 1 amended **twice on the same day**. Read
  *The second re-ruling* immediately below; then *The re-ruling* and *The measurement came back
  negative* for the first one. Choices 2 to 5 were never in question.
- **Published title:** this ADR shipped as *… rootless Podman on Debian 13 …* and the file name
  keeps that spelling so no link breaks. The decision is **Rocky Linux 10**.

## The second re-ruling — 2026-09-16, and it undoes the first one's reason

Choice 1 moved from Debian 13 to CentOS Stream 10 for exactly one reason: only Podman 6 offers
`rootless_port_forwarder="pasta"`, and only that preserved the client's source address for a
bridge-networked edge. Everything below about CentOS is kept verbatim, because it is the honest
record of why that was right at the time.

**It stopped being right the same day.** The pasta forwarder was measured on CentOS Stream 10 and
**does not deliver IPv6 at all** — see the plan's §13. [ADR-0187](0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md)
then solved the source-address problem a different way, with a host-level PROXY-protocol front
end, and that solution **needs no Podman 6 and no pasta forwarder**.

With the reason gone, the cost of a development stream is no longer worth paying:

|                                          |                     CentOS Stream 10                     |         **Rocky Linux 10**          |
|------------------------------------------|----------------------------------------------------------|-------------------------------------|
| supported until                          | 2030-05-31 (~5 years)                                    | **2035-05 (10 years)**              |
| position relative to RHEL                | **upstream** — changes arrive before RHEL validates them | downstream rebuild of released RHEL |
| SELinux, container-selinux, SCAP content | yes                                                      | yes                                 |
| available on Hetzner                     | yes                                                      | yes, rapid-deploy image             |

A hardened production host should receive changes **after** RHEL has validated them, not before.
AlmaLinux 10 was weighed as equally viable and rejected only on a preference: it is ABI-compatible
rather than bug-for-bug, and the hardening guidance this deployment is measured against is written
for RHEL.

**What this costs, stated plainly:** every Phase 1 measurement was taken on Podman 6.1.0 with
netavark 2.1.0. Rocky 10 carries an older netavark, so `no_default_route` as an egress block and
`--internal` against inbound DNAT are **re-measured there before production**, together with the
certificate handover, the subuid base, cgroup delegation and ADR-0186's full chain.
- **Date:** 2026-09-12, re-ruled 2026-09-16
- **Deciders:** @greluc (four choices recorded below), Claude (analysis and measurement)
- **Related:** [ADR-0049](0049-config-as-promotable-oci-artifact.md) ·
[ADR-0072](0072-monitoring-stack-prometheus-grafana.md) ·
[ADR-0112](0112-edge-real-client-ip-restore-native-ipv6.md) ·
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

## The re-ruling — @greluc, 2026-09-16

**Path A, on CentOS Stream 10.** The negative measurement below rejected the *platform*, not the
decision: rootless Podman with Quadlet on a rebuilt host stands, and choice 1 changes.

|          Choice           |                        Was                        |                      Is                      |
|---------------------------|---------------------------------------------------|----------------------------------------------|
| 1 — distribution          | Debian 13 "trixie", to match the testing host     | **CentOS Stream 10**                         |
| 2 — Podman version        | the distribution's own, no third-party repository | **unchanged** — CentOS Stream 10's own 6.1.0 |
| 3 — orchestration         | Quadlet                                           | unchanged                                    |
| 4 — observability         | rebuilt with the migration                        | unchanged                                    |
| 5 — rebuilt, not upgraded | a fresh host beside the current one               | unchanged                                    |

Choice 2 is what survived, and it is the one that mattered: this is still distribution packages
only, from a distribution that is not Debian. The reason Debian 13 cannot serve is set out below —
four independent blockers, any one sufficient.

**What CentOS Stream 10 brings, measured rather than read.** `dnf install podman` in a throwaway
`quay.io/centos/centos:stream10` container answers `podman 6.1.0`, ships `/usr/bin/pesto`, carries
`#rootless_port_forwarder = "rootlessport"` at line 424 of its own `containers.conf`, and documents
the option in its own `containers.conf(5)`. Alongside: `passt 0^20260728.gf8df3f1` (the pesto
requirement is `>= 0:20260526`), `netavark 2.1.0`, `aardvark-dns 2.1.0`, `crun 1.29.1`,
`conmon 2.2.1`. The control, `debian:13`, answers `podman 5.4.2`, has no `pesto`, and matches the
option **zero** times in either its config or its man page.

**The decisive property is not the version, it is where the version comes from.** Debian 13 froze
Podman at 5.4.2 and the project needed a newer one; Fedora would solve that by replacing the
distribution every ~13 months. CentOS Stream 10 is supported to **2030-05-31**, and its
container-tools is a rolling AppStream that rebases on the latest stable upstream Podman up to four
times a year. That is a current Podman without an annual host rebuild — on a host this project's own
rules say must be rebuilt rather than upgraded in place.

Rocky Linux 10 and AlmaLinux 10 were measured and do not qualify (`podman 5.8.2`,
`passt 0^20251210`, no `pesto`). They are rebuilds of *released* RHEL while Stream is the branch
RHEL is cut from, so they trail by construction.

### What the re-ruling costs, and what it does not

- **SELinux replaces AppArmor**, enforcing by default. Every bind mount needs a correct label —
  `:z` / `:Z` on the Quadlet `Volume=` lines, or a matching `semanage fcontext` rule. This is now a
  first-class work item of the same rank as the certificate handover, and it is also a gain: a
  second confinement layer under the user namespace.
- **The host bootstrap becomes `dnf`-shaped.** `docs/deployment.md` speaks `apt` throughout.
- **The rehearsal environment diverges again unless it follows.** Choice 1 existed to end exactly
  that. The PVE testing host is Debian 13 and has room for a second, short-lived VM, so the
  recommendation is to run Phases 1-4 on a CentOS Stream 10 VM there and decide separately whether
  the permanent testing host moves. **Open, and it belongs to the owner.**
- **Nothing about the images changes.** All five published images are OCI and the host distribution
  is invisible to them.

### Three things Podman 6.1 changes against the plan as written

The plan measured Debian's 5.4.2. The chosen platform is two minor versions and one major ahead, and
three of its findings move:

1. **Quadlet has `Memory=`** — verified in the shipped `podman-systemd.unit(5)` on CentOS Stream 10.
   `REQ-OPS-020`'s measured limits need no `PodmanArgs=--memory=` workaround.
2. **`isolate` defaults to `strict`** on netavark 2, and the man page names `isolate=false` as the
   way to "restore the pre-Podman 6 / Netavark 2 behavior". Bridge networks no longer reach each
   other by default. That **aligns with** this deployment's model, which never routes between
   bridges and treats membership as the only path — but it is a default that changed under us and
   must be asserted rather than assumed.
3. **Still no masquerade switch.** netavark 2.1's documented bridge options remain `mtu`, `metric`,
   `no_default_route` and `isolate`; masquerading is tied to `mode=managed`. So
   `net-edge-ingress`'s `enable_ip_masquerade=false` still has no direct equivalent, and
   `-o no_default_route=true` remains the candidate to be measured.

Podman 6.0 also removed slirp4netns, CNI, iptables (in favour of nftables), cgroups v1 and BoltDB.
None of those is used here, but the nftables change is worth knowing before anybody reads a firewall
rule on the new host and expects `iptables` output.

### The target host

The current production server is a Hetzner **CPX42** — 8 shared AMD vCPU, 16 GB RAM, 320 GB NVMe,
20 TB traffic — in `nbg1-dc3`, which matches the measured 8 vCPU / 15 982 920 kB /
327 684 194 304 B. An equivalent new host is therefore a second CPX42 in `nbg1`, and Hetzner offers
a CentOS Stream 10 image. Sizing note from the inventory: of the 116 GB in use on the current host,
63 GB are two undocumented pre-change copies of `/var/iri` and 35 GB is the containerd image store,
so the data that actually has to move is **under a gigabyte** plus whatever monitoring history is
judged worth carrying.

### Still open after this ruling

The ruling settles the platform. It does not settle: whether the permanent testing host moves to
CentOS Stream 10; which `container_*` alerts survive Phase 4 and which are retired; and the
acceptance measurement for `rootless_port_forwarder="pasta"` itself, which is **experimental and
off by default upstream** and has to be demonstrated on a real host before anything is built on it.
That measurement is four assertions, and they are the ones the Phase 0 conformance suite already
makes against the current Docker stack.

## Decision

**The container runtime becomes rootless Podman on Rocky Linux 10, orchestrated by Quadlet, on
a production host that is rebuilt rather than upgraded in place.**

> [!note] Amended twice, and the second amendment is at the top of this file
> This sentence named Debian 13 when written on 2026-09-12 and CentOS Stream 10 after the first
> re-ruling. The analysis of CentOS below is kept as written; it records why that platform was
> correct while Podman 6 was required, which [ADR-0187](0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md)
> removed.
>
> [!note] As originally written, 2026-09-12
> The sentence above read "rootless Podman 5.4.2 on Debian 13 trixie". The four choices below
> are the originals and are kept verbatim, because the reasoning behind choices 2 to 5 is
> unchanged and choice 1's rejection is only legible next to what it claimed. See *The
> re-ruling* above.

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

### The measurement came back negative — 2026-09-16

**It did not need a host, and it rejects choices 1 and 2 as a pair.** The question above is phrased
as "whether `pasta` preserves the client source address". Pasta does. The question that decides this
ADR is a different one, and the evidence table missed it:

> **Pasta is the default network mode for rootless containers. It is not what forwards published
> ports into a container attached to user-defined bridge networks.**

The edge sits on six bridges (`net-edge-ingress` plus five `net-proxy-*`). Podman's documentation on
`main`: "By default, rootless bridge networks use `rootlessport` for port forwarding, which is a
userspace proxy that **does not preserve client source IPs**." The fix exists and arrived in
**Podman 6.0** — `rootless_port_forwarder="pasta"` in `containers.conf`, routing bridge port
forwarding through pasta's `pesto` — and the v6.0.0 release notes state the rest plainly: "The
default remains `rootlessport` (**the default for Podman 5.x**)", and the option is **experimental**.

Debian 13 "trixie" ships **Podman 5.4.2**. There is no `podman` in `trixie-backports`; `forky` and
`sid` carry 5.8.6, which is also below 6.0; only `experimental` has 6.1.1. Ubuntu 26.04 LTS ships
5.7.0. **No current Debian or Ubuntu stable release ships a Podman that can preserve the source
address for a bridge-networked rootless edge.** Verified against `packages.debian.org`,
`packages.ubuntu.com` and `apt-cache policy` on the Debian 13 testing host itself.

So the two choices defeat each other: **choice 1** (Debian 13, to match the testing host) combined
with **choice 2** (the distribution's own Podman, no third-party repository) forecloses the only
configuration that satisfies `REQ-SEC-023` and ADR-0112.

What that costs is not a degraded rate limiter. Six surfaces in this repository read `$remote_addr`,
and one of them **inverts** rather than degrades: the Keycloak admin console ACL
(`location ^~ /auth/admin`) allows exactly the bridge gateway addresses, because under Docker the
operator's tunnelled traffic arrives with a gateway peer address and internet traffic does not.
rootlessport gives every request the same peer address, so the ACL either admits the internet or
locks the operator out. `docker-compose.yml`'s own comment on `net-edge-ingress` names this
mechanism as the **2026-07-20 outage** — "the userland docker-proxy relays every IPv6 client through
the bridge gateway, so nginx sees ONE address for all of them and the per-IP limiter collapses into
a single bucket" — and rootlessport is that userland proxy for IPv4 and IPv6 alike, with no
"give the bridge a real subnet" escape.

**This ADR therefore needs a re-ruling by @greluc before any further phase runs.** Four paths, set
out with their costs in [`PODMAN_MIGRATION_PLAN.md` §7](../PODMAN_MIGRATION_PLAN.md): (A) Podman 6.x
from a source newer than Debian 13 stable, dropping choice 2; (B) the edge in the host network
namespace, trading five segments for a correct source address; (C) the hybrid with a rootful edge
this ADR rejected, which is worth re-reading now; (D) stay on Docker, which is what this ADR's own
closing sentence prescribes for a negative result.

**Measured on a host, 2026-09-16, after the PVE operator challenged the premise.** They were right
to: a distribution change for the whole stack should not rest on documentation alone, and they
offered a counter-measurement — rootless Podman 5.4.2 on Debian 13 preserving the real client
address in production. Their container is on the **pasta default network**; this deployment's edge
is on user-defined bridges, so both facts hold at once. The experiment they proposed settled it in
a quarter of an hour, on the Debian 13 testing guest with podman 5.4.2 freshly installed: the same
image, started in the same minute, probed by the same client in the same second, differing only in
network mode. On a **user-defined bridge** the container logged `10.89.0.2`, the rootlessport
forwarder. On **pasta** it logged `10.1.0.30`, the real client. One variable, two answers.

Debian 13's own shipped `containers.conf(5)` says the same thing in one sentence — *"The rootlesskit
port handler is also used for rootless containers when connected to user-defined networks"*, in a
paragraph that explicitly warns it rewrites the source address for web-server logs — and podman
refuses the slirp4netns escape it points at (`can only set extra network names, selected mode
slirp4netns conflicts with bridge`). Four independent lines agree: the shipped man page, the live
measurement, podman's refusal of the workaround, and the upstream release notes.

**Path A was examined on 2026-09-16 and is feasible — on a different distribution.** Podman 6.1.0,
`passt 0^20260728` and `/usr/bin/pesto` all ship in the **base repositories of CentOS Stream 10**,
and `rootless_port_forwarder` is present in its shipped `containers.conf` and documented in its
`containers.conf(5)`; Fedora 45 carries Podman 6.1.1 and the same passt. Both were verified by
installing the distribution's own package in a throwaway container and asking the binary, against a
`debian:13` control that answered `podman 5.4.2`, no `pesto`, and zero matches for the option in
either the config or the man page. Rocky Linux 10 and AlmaLinux 10 carry 5.8.2 and do **not**
qualify — they are rebuilds of released RHEL while Stream is its forward branch.

So A costs **choice 1**, not choice 2: it is still distribution packages only, from a distribution
that is not Debian 13. The consequences that follow are SELinux in place of AppArmor (every bind
mount needs a label), a `dnf`-shaped host bootstrap, and — the one that matters — **the testing host
diverging again unless it moves too**, which is the exact condition choice 1 existed to end. Two
things remain true regardless: the upstream feature is experimental and off by default, and Debian
keeps Podman 6.x out of `sid`. Full examination, including why a Debian 13 backport is not a
backport but an adoption of six source packages: `PODMAN_MIGRATION_PLAN.md` §8.

There is also no architectural escape that would keep Debian 13: **pasta and bridge networks are
mutually exclusive on one container** (`cannot set multiple networks without bridge network mode,
selected mode pasta`), and upstream carries this deployment's exact shape — a reverse proxy that
must see the client address while its upstreams stay unreachable from the host — as the known
limitation `rootless_port_forwarder` was written to answer.

Two further corrections to this ADR's consequences, from the same verification:

- **`prometheus-podman-exporter` is not a replacement for cAdvisor here, it is a subset.** The
  monitoring reads 17 distinct `container_*` series; the exporter's `podman_container_*` family has
  no equivalent for `container_oom_events_total` (which `ContainerOomKilled` reads), for the
  `container_threads` / `container_threads_max` pair (the most-used series in the configuration, and
  the thread-OOM detection from *Three thread-OOMs…*), for `container_memory_working_set_bytes`,
  `container_memory_rss`, or for the three CFS-throttling series. Choice 4 said observability is
  rebuilt with the migration; it is now clear that "rebuilt" includes deciding which alerts survive.
- **Quadlet 5.4.2 has no `Memory=`** — confirmed against that version's `podman-systemd.unit(5)`
  rather than by report, so `REQ-OPS-020`'s limits would go through `PodmanArgs=--memory=…`. It does
  carry `PidsLimit=`, the full `Health*` family and `Notify=healthy`, the last of which is a
  genuinely better health gate than `docker compose up --wait`.

### Cost

One additional server for the overlap, an IP change (DNS, SSH, any address-based rules), and a
migration that touches four subsystems. Certificates should be seeded onto the new host from the old
one rather than re-issued — the Let's Encrypt duplicate-certificate limit for this SAN set is five
per week and two were used on 2026-09-12.

## Status of this decision

**Accepted** on 2026-09-16 by @greluc, with choice 1 amended to CentOS Stream 10 and then, the
same day, to **Rocky Linux 10** once [ADR-0187](0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md)
removed the need for Podman 6. The plan it
governs is [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md); no host
has been touched. The phases were sequenced so that the measurements which could reject this ADR came
first and cost nothing but time — and that is what happened: §3.1 was answered from vendor
documentation on 2026-09-16, before a host was built, and it rejected the platform as specified.
