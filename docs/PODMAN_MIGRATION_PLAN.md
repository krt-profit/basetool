# Rootless Podman on Debian 13 — Migration Plan

> **Doc type:** Implementation plan — **living** until shipped, then freeze and point at the living
> truth (planned: [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md),
> the reworked delivery section of [`docs/deployment.md`](deployment.md), and the `REQ-OPS-*` /
> `REQ-OBS-014` amendments in [`docs/specs/deployment-delivery.md`](specs/deployment-delivery.md) and
> [`docs/specs/observability.md`](specs/observability.md)).
> **Status:** Phase 0 not started. **No host has been touched.**
> **Decision record:** [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md)
> (Proposed) — the four owner choices and the alternatives that were rejected.
> **Last updated:** 2026-09-12 (testing-host facts from the PVE operator; §3.1 moved to Phase 5).

---

## 0. How to use this document

Read §1 first: it states what this plan promises and the three rules it is built to satisfy. Then
§2, the phases. Each phase carries its own **acceptance** and **rollback**; a phase is not finished
because its work is done, it is finished because its acceptance passed.

**§3 is the list of things that can stop this.** Those measurements come first on purpose. A
negative result there is a successful outcome of this plan — it costs days rather than a migration.

---

## 1. What this plan promises

Three requirements were set by the owner, and the structure below exists to satisfy them rather than
to assert them.

### "No regressions"

Not a promise anyone can make by being careful. It is made by writing down what must stay true, as
something executable, **before** anything changes — and then running it after every step.

That is **Phase 0**, the conformance suite. It asserts invariants against a *running host*, not
against configuration files: the deny-by-default allow-list, the per-IP limiter keyed on the real
client address, the vhost topology, the certificate handover, the health endpoints, container
metrics present, log streams flowing. It must go **green against the current Docker stack first** —
a suite that has never passed proves nothing when it passes later.

From then on, "no regression" has a definition: the suite that passes on Docker passes on Podman.

### "Not a hundred deploys"

**Production is touched exactly twice**, and the first time changes nothing that is running:

1. Certificates and data are copied onto the new host, which is serving nobody.
2. The cutover.

Everything else — every failed experiment, every wrong assumption, every iteration — happens on the
testing host and on the new host before it carries traffic. The 2026-09-12 edge migration cost six
production applies because it was debugged where it ran. This plan is built so that the production
host sees only the step that has already worked somewhere else.

### "Test everything beforehand"

Every phase states what is measured and records the result in this document. **No step is planned on
top of an assumption that has not been measured.** Where something cannot be measured before it is
built, that is said plainly rather than glossed — see §3.

---

## 2. Phases

### Phase 0 — The conformance suite (repository only, no host)

Write the regression net, and prove it detects regressions.

- A suite that takes a target (`--target https://…` plus optional SSH access for host-side checks)
  and asserts the invariants listed in §1.
- Each check must be shown to **fail** against a deliberately broken target, not merely to pass
  against a working one. A green check that cannot go red is decoration; that lesson cost a day on
  2026-09-12.
- Runs against the current production host in read-only mode, and against the testing host.

**Acceptance:** the suite is green against production-on-Docker and against testing-on-Docker, and
every individual check has been shown red at least once.
**Rollback:** none needed — nothing outside the repository changes.
**Value if the migration is abandoned:** the suite stays. It is the missing external assertion for
the edge, and it makes every future host change checkable.

### Phase 1 — Measurements on the testing host

The questions in §3, answered in order, on the existing Debian 13 testing host. Each is a small
scripted experiment whose result is recorded here.

**Acceptance:** every question in §3 has a recorded answer, and none of them is negative in a way
that rejects ADR-0163.
**Rollback:** a PVE snapshot before each experiment (pending confirmation — §4).

### Phase 2 — Quadlet translation, on testing

Translate the Compose stack into Quadlet units and bring it up on the testing host under rootless
Podman. This is where the unknown-unknowns live, and it is deliberately the phase with the most
iterations and the least consequence.

- `.container`, `.network`, `.volume` units for all ~22 services.
- Host preparation as a documented, repeatable procedure: subuid/subgid, lingering, cgroup
  delegation, the :80/:443 decision.
- The certificate handover (`acme` → `edge`) under user-namespace uid mapping, which is the part
  most likely to behave differently from Docker.

**Acceptance:** the Phase 0 suite is green against the testing host running rootless Podman, with
the same results it produced against Docker.
**Rollback:** snapshot restore; the testing host returns to Docker.

### Phase 3 — Delivery rebuild

`deploy.sh` against Quadlet, keeping every guarantee `REQ-OPS-003` and `REQ-OPS-015` name: digest
pin, signature verification, health gate, automatic rollback on failure, the idempotence no-op over
a verified running stack.

- A replacement for `docker buildx imagetools inspect` (candidate: `skopeo inspect`).
- The configuration bundle carries unit files instead of a Compose file.
- `deploy.test.sh` extended — the stubbed-CLI harness is the reason the deployer's decision logic is
  testable at all, and it must cover the Podman paths before they run anywhere.
- **Carried over deliberately:** the lesson that `scripts/deploy.sh` reaches the host by no automated
  path. Whatever replaces it needs an answer to that, or the same class of silent staleness returns.

**Acceptance:** a full promote → deploy → health-gate → rollback cycle demonstrated on the testing
host, including a deliberately broken image that must roll back cleanly.
**Rollback:** the testing host keeps the Docker `deploy.sh` until this passes.

### Phase 4 — Observability rebuild

- Container metrics without cAdvisor. Candidate: `prometheus-podman-exporter`; the acceptance is not
  "it runs" but that every `container_*` series the alerts read is present with the same label
  vocabulary.
- Log collection without `discovery.docker`. Rootless Podman logs to journald by default, which
  Alloy reads natively — likely simpler than today, and to be measured rather than assumed.
- Every alert re-pointed, with its promtool unit tests updated and shown red against the old
  expression where the metric name changed.

**Acceptance:** `ContainerRestartLoop`, `ContainerOomKilled`, `ContainerMemoryHigh`,
`ContainerPidsHigh`, `ContainerMetricsMissing` and `CoreContainerMetricsMissing` all demonstrably
fire on the testing host when the condition is induced. Not "the metric exists" — the alert fires.
**Rollback:** none needed; testing only.

### Phase 5 — The new production host

A fresh Debian 13 host, built from the (now Podman-shaped) bootstrap documentation, serving nobody.

- Data restored from backup — which doubles as the disaster-recovery drill `restore-drill.sh` only
  partly rehearses today.
- Certificates **seeded from the old host**, not re-issued. The Let's Encrypt duplicate-certificate
  limit for this SAN set is five per week; two were used on 2026-09-12.
- The IPv6 source-address measurement, if the testing host could not answer it (§3.1).

**Acceptance:** the Phase 0 suite is green against the new host, addressed directly by IP, with the
old host still serving the public names.
**Rollback:** delete the host. Nothing has moved.

### Phase 6 — Cutover and soak

- DNS moved. The old host keeps running, untouched.
- The Phase 0 suite green against the public names.
- Soak with both hosts alive for at least a week. Going back is a DNS change, not a restore.
- Decommission only after the soak, as a separate decision.

**Acceptance:** suite green, alerts quiet, a full deploy cycle observed on the new host.
**Rollback:** DNS back to the old host — minutes, not hours.

---

## 3. The questions that can stop this

These come first because a negative answer is cheap now and expensive later. **A negative result
rejects ADR-0163 rather than triggering a workaround.**

### 3.1 Does `pasta` preserve the client source address — IPv4 *and* IPv6?

The decisive one. `REQ-SEC-023`'s per-IP limiter and [ADR-0112](adr/0112-edge-per-ip-limit-keys-on-the-ipv6-64-prefix.md)'s
`/64` key both read `$remote_addr`. If rootless port forwarding presents a gateway address, the
limiter collapses into one bucket and the access log identifies nobody — the exact shape of the
2026-07-20 outage.

**Neither half can be measured on the testing host** — established with the PVE operator on
2026-09-12, and it invalidates the first version of this plan. The guest firewall runs
`policy_in: DROP` and admits exactly one source for application traffic, the reverse proxy in front
of it; there is no public port forward, and the interface the traffic arrives on carries only
link-local IPv6. It is not an inference from the firewall rules either: every line of that host's
proxy access log carries the same single client address. The testing edge already sees one bucket
for every request, under Docker, today.

That closes the *production-shaped* rehearsal: traffic arriving the way real traffic arrives cannot
carry a distinct client address there. **It does not close the measurement itself**, and the first
version of this section overstated it.

What pasta has to be shown to do is preserve *whatever* source address reaches the host. The testing
VM's DMZ interface `eth0` has a global IPv6 address and a working v6 default route, and its guest
firewall admits the two management networks **in full** — so a client on the owner's LAN connecting
directly to that interface arrives with its own address, proxied by nothing. That is exactly the
property under test.

So §3.1 splits:

- **The mechanism** — does pasta hand the container the client's real address? — is measurable on the
  testing host by connecting to `eth0` directly from a LAN client, for IPv4 and, if the management
  network carries v6, for IPv6 too. To be confirmed by one experiment in Phase 1 rather than assumed
  here.
- **The behaviour under production-shaped traffic** — the `/64` bucket key, 429-not-503 under load —
  moves to Phase 5, on the new host while it is idle.

Corroborating but not sufficient: the PVE operator migrated a different VM in the same estate to
rootless Podman on 2026-09-11 and reports pasta preserving the source address there. Another host's
result on another network shape informs the risk; it does not discharge the measurement.

### 3.2 Can the edge bind :80 and :443 rootless, and at what cost?

`net.ipv4.ip_unprivileged_port_start` is 1024 today. Lowering it to 80 is host-wide and
security-relevant: it lets *any* unprivileged process bind those ports. The alternatives — a
socket-activated forwarder, or `CAP_NET_BIND_SERVICE` on a single unit — need to be weighed against
it rather than assumed away.

### 3.3 Do the network semantics survive?

`internal: true`, the no-masquerade driver option, the pinned subnets, and the dual-stack ingress
bridge. Netavark equivalents exist for most of this. Each one is measured the way the Docker
behaviour was measured on 2026-09-12 — by observing it, because `internal: true` turned out to
remove inbound DNAT as well as outbound NAT, which no documentation said.

### 3.4 Does the certificate handover survive user-namespace mapping?

`acme` writes as root-in-container and hands the files to uid 101 so the edge can read them. Under
rootless, both uids are subuids on the host. `REQ-OPS-026` and its check exist because this broke
twice under Docker; it is the single most likely thing to break differently under Podman.

### 3.5 Do healthchecks and resource limits work under a user slice?

Podman implements rootless healthchecks with transient systemd timers, and cgroup limits need
delegation for the user slice. `REQ-OPS-003`'s health gate and `REQ-OPS-020`'s measured limits both
depend on these.

### 3.6 Is there a container-metrics source with the same series?

Not "does an exporter exist" — whether the specific series the alerts read are present, with a label
vocabulary that keeps `REQ-OBS-006`'s cardinality bounds.

---

## 4. The testing host — answered 2026-09-12

Established with the PVE operator. Verified in this repository where the finding touches it.

**It is a real VM, not an LXC** — so the rootless constraints it exercises are the ones a bare-metal
host has. Debian 13, cgroup v2, ~107 GB free, and ZFS snapshots that roll back in seconds, which is
worth a great deal across Phases 1–4. There is room for a second, short-lived VM, so a clean
bootstrap can be rehearsed without dismantling the testing environment.

**Access:** `ssh sysadm@10.9.0.12`, directly from the LAN, no VPN; the owner's desktop key is already
deployed. One thing is missing and only the owner can supply it: `sysadm` has a **locked password**,
so `sudo` cannot work at all. It is set from the noVNC console (`sudo passwd sysadm` as the console
user). Until then the host is read-only to us.

**The rate limiter cannot be rehearsed there** — §3.1.

**Two gaps between testing and production, both of which shape the phases:**

- **The testing stack runs 8 containers, not 22 — there is no monitoring plane there at all** (no
  Prometheus, Grafana or Loki). Phase 4 rebuilds container observability, and it cannot be rehearsed
  on a host that has none. Either the monitoring stack comes up on testing first, or Phase 4 has no
  rehearsal ground and moves to Phase 5 with §3.1.
- **Docker 26.1.5 / Compose 2.26.1** on testing. If "the same state" is to mean anything, that is a
  second divergence beside the distribution one.

**Four things the PVE operator hit migrating another VM in this estate to rootless Podman on
2026-09-11**, recorded so we do not rediscover them:

1. Rootless cannot bind ports below 1024; they set `net.ipv4.ip_unprivileged_port_start=80` via
   `/etc/sysctl.d/`. An nftables redirect is the alternative — more moving parts for the same effect.
   On a single-purpose VM the blast radius is nil; on the production host it is a deliberate decision
   (§3.2).
2. `pasta` is the default from Podman 5 and preserved the real source address for them.
3. **Quadlet 5.4 does not know `Memory=`** — it has to be `PodmanArgs=--memory=...`. `REQ-OPS-020`'s
   measured limits go through that spelling.
4. `pasta` logs `epoll_ctl` errors on connection teardown that look like a fault and are not. It
   fills the journal, which matters for a log pipeline with alerting on it.

**Other host facts:** AppArmor is active (one more variable than a host without it); `subuid`/`subgid`
exist only for `sysadm`, so a rootless service user needs its own range; the VM's disk is marked
`backup=0`, so ZFS snapshots are the only net — there is no vzdump copy behind them.

### The trap this plan has to clear first

`deploy.sh` hardcodes `PROFILE=prod` (line 190), and ADR-0162 put `edge` and `acme` in the `prod`
profile while moving `npm` to `rollback`. The edge's `server_name` directives are literal
`profit-base.online` names with no substitution. **The testing host therefore swaps its own proxy on
the next `:testing` promotion** — to an edge that matches none of its host names, beside an `acme`
container that would try to obtain certificates for the production names from a host with no public
route.

Verified rather than assumed: the last `:testing` promotion was 2026-09-09 and the edge change
reached `main` on 2026-09-12, so nothing has broken yet. It is armed, not sprung, and the deploy
timer there runs every five minutes once it is.

This is a defect introduced by ADR-0162 and it is **not** part of the Podman migration — it has to be
closed first, on its own, or the rehearsal environment breaks the moment it is used.

## 4b. Sequencing

The PVE operator's advice, recorded because it is right: the edge moved from NPM to native nginx on
2026-09-12, hours before this plan was written. Running an edge replacement and a runtime replacement
concurrently makes every investigation twice as expensive, because a symptom has two plausible
causes. Phase 0 is repository work and can start immediately; Phase 1 should wait until the new edge
has been quiet for a few days.

## 5. What this plan does not cover

- **Decommissioning the old host.** A separate decision after the soak.
- **Migrating the testing host to Podman permanently.** It is the rehearsal ground; whether it stays
  on Podman afterwards is decided once production has.
- **Anything about the application.** No image, no schema and no endpoint changes here. If this
  migration requires an application change, that is a finding worth stopping for.
