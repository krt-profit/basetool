# Rootless Podman on Debian 13 — Migration Plan

> **Doc type:** Implementation plan — **living** until shipped, then freeze and point at the living
> truth (planned: [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md),
> the reworked delivery section of [`docs/deployment.md`](deployment.md), and the `REQ-OPS-*` /
> `REQ-OBS-014` amendments in [`docs/specs/deployment-delivery.md`](specs/deployment-delivery.md) and
> [`docs/specs/observability.md`](specs/observability.md)).
> **Status:** Phase 0 not started. **No host has been touched.**
> **Decision record:** [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md)
> (Proposed) — the four owner choices and the alternatives that were rejected.
> **Last updated:** 2026-09-12.

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

IPv4 is measurable on the testing host. **IPv6 is measurable there only if that host carries v6**;
otherwise the measurement moves to Phase 5, on the new host while it is still idle.

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

## 4. Open — awaiting the PVE session

Asked on 2026-09-12; not yet answered. These shape Phase 1 and 2 and are recorded here so the plan
is not written over them.

1. **Access.** How to reach the testing host from the owner's workstation — host, user, key, jump
   host or VPN. The owner has asked that this become self-service.
2. **VM or LXC?** Rootless Podman inside an unprivileged LXC has its own constraints (cgroup
   delegation, nested user namespaces). If it is an LXC, Phase 1 has to establish what it can still
   prove about a bare-metal Hetzner host.
3. **IPv6.** Whether the testing host has v6 at all — decides whether §3.1 can be answered in Phase 1
   or has to wait for Phase 5.
4. **Snapshots.** Whether the VM can be snapshotted and rolled back quickly, which is worth a great
   deal across Phases 1–4.
5. **Capacity** for a second short-lived Debian 13 VM, so a clean bootstrap can be rehearsed without
   dismantling the testing environment.
6. **Existing host state** — sysctls, AppArmor, cgroup delegation, subuid ranges, configuration
   management.

---

## 5. What this plan does not cover

- **Decommissioning the old host.** A separate decision after the soak.
- **Migrating the testing host to Podman permanently.** It is the rehearsal ground; whether it stays
  on Podman afterwards is decided once production has.
- **Anything about the application.** No image, no schema and no endpoint changes here. If this
  migration requires an application change, that is a finding worth stopping for.

