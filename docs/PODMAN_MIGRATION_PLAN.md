# Rootless Podman on CentOS Stream 10 — Migration Plan

> **Doc type:** Implementation plan — **living** until shipped, then freeze and point at the living
> truth (planned: [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md),
> the reworked delivery section of [`docs/deployment.md`](deployment.md), and the `REQ-OPS-*` /
> `REQ-OBS-014` amendments in [`docs/specs/deployment-delivery.md`](specs/deployment-delivery.md) and
> [`docs/specs/observability.md`](specs/observability.md)).
> **Status:** **Phase 0 is done** and the platform is **decided** (2026-09-16): path A on
> **CentOS Stream 10**, on a second Hetzner CPX42. Phases 1-6 are ready to start. **No host has
> been touched.** What the decision changes is in §9.
> §3.1 — the one measurement the plan says can reject ADR-0163 — was answered on the same day from
> vendor documentation, without needing a host. It **rejected Debian 13**, which is what the
> ruling in §9 responds to. Read §3.1 for the finding, §8 for how path A was examined, §9 for
> what was decided.
> **Decision record:** [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md)
> — **Accepted 2026-09-16** with choice 1 amended from Debian 13 to CentOS Stream 10. The file
> name keeps the old spelling so no link breaks.
> **Last updated:** 2026-09-16 (§3 answered; §4 re-verified; §6-§8 added; §9 records the ruling).

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

### Phase 0 — **done, 2026-09-16**

`scripts/check-conformance.py` and `scripts/check-conformance.test.sh`, with the self-test wired
into `repo-lint.yml` as the `conformance-selftest` job. Both halves of the acceptance passed.

**Green against production-on-Docker** — the baseline a Podman run has to reproduce:

|          Check           |      Requirement       |                                    Baseline observed 2026-09-16                                     |
|--------------------------|------------------------|-----------------------------------------------------------------------------------------------------|
| `vhost-reachable`        | REQ-OPS-014            | four vhosts answer; nothing 5xx                                                                     |
| `certificate-valid`      | REQ-OPS-026            | all four covered by the SAN set, soonest expiry 86 d                                                |
| `certificate-shared`     | REQ-OPS-026 / ADR-0162 | **one** leaf served across all four                                                                 |
| `http-redirects`         | REQ-SEC-023            | all four redirect `:80`                                                                             |
| `ipv6-reachable`         | ADR-0112               | all four answer over IPv6                                                                           |
| `client-address-visible` | REQ-SEC-023 / ADR-0112 | the edge logged the probe from the **client's own public address**; 25-28 distinct clients per hour |
| `containers-running`     | REQ-OPS-003            | 9/9 prod containers up and healthy                                                                  |
| `scrape-targets-up`      | REQ-OBS-005            | 4/4 application targets `up`                                                                        |
| `container-metrics`      | REQ-OBS-006            | 6/6 required `container_*` series populated                                                         |
| `log-streams`            | REQ-OBS-005            | Loki ingesting ~11 lines/s                                                                          |
| `rate-limit-active`      | REQ-SEC-023            | skipped - opt-in, it is load against the target                                                     |

**Every check demonstrably red** — 37 assertions, no host, no daemon, loopback only. The external
checks run against a local TLS fixture with throwaway certificates generated per run; the
host-side checks against a stub that prints what the host would have printed.

> [!important] A red for the wrong reason reads as proof, and the first draft produced twelve
> Every red assertion now also names a substring its failure message must contain, and the
> coverage gate counts only scenarios that **ran and behaved** rather than scenarios that were
> written down. The draft's twelve host-side reds were all red because Windows could not execute
> the stub at all - twelve green ticks asserting nothing about the checks they named.

Building it found three defects that a configuration review would not have:

1. **The external `/healthz` assertion was wrong about the deployment.** `/healthz` is declared in
   `05-default.conf` on the **default** server behind `allow 127.0.0.1; allow ::1; deny all;`,
   because it exists for the container `HEALTHCHECK`, which runs inside the container. Asserting
   it from the internet asserted something the design forbids. Replaced by `scrape-targets-up`,
   which reads Prometheus's `up` for the four application targets - a genuinely different signal
   from container state, and the pair has been fooled separately before.
2. **Three host commands were mis-quoted in ways only a real shell would show.** `awk {print $1}`
   unquoted lets the *remote* shell expand `$1` to the empty string, so the distinct-client count
   was always 1; `--format {{.Names}}\t{{.Status}}` loses its backslash and docker receives a
   literal `t`; and one command carried a single quote, which the runner's own guard rejects.
3. **`ipv6-reachable` conflated two different failures.** Its preflight probed the target, so a
   runner with no IPv6 and a target with no IPv6 produced the same answer. A missing AAAA or a
   refused connection is now a failure; no route from this machine is a skip.

**Value if the migration is abandoned:** the suite stays. It is the missing external assertion for
the edge, and `client-address-visible` is the check that turns §3.1 from an argument into a
measurement - on Docker today, and on whatever runs the edge tomorrow.

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

## 3. The questions that can stop this — answered 2026-09-16

These came first because a negative answer is cheap now and expensive later. **A negative result
rejects ADR-0163 rather than triggering a workaround.**

Every answer below was established on 2026-09-16 from vendor documentation and from read-only
inspection of both hosts. Nothing here is recalled, and nothing is inferred from another
deployment's result. Where a question still needs an experiment, it says so.

### 3.1 Does the edge see the client's source address? — **NO, and it is now measured.**

The plan asked the wrong question, and asking it correctly answers it without a host.

**The question is not "does pasta preserve the source address".** It does. The question is whether
*this* deployment's edge gets pasta at all — and it does not, because the edge is on bridge
networks. Pasta is the default for the rootless **default network mode**; a container attached to
user-defined bridges is forwarded by something else.

Podman's own documentation, on `main`:

> By default, rootless bridge networks use `rootlessport` for port forwarding, which is a userspace
> proxy that does not preserve client source IPs.

`containers.conf(5)` has said the same thing about the underlying mechanism for years:

> `port_handler=rootlesskit`: Use rootlesskit for port forwarding. Default. Note: Rootlesskit
> changes the source IP address of incoming packets to a IP address in the container network
> namespace …

There **is** a fix, and it is too new for the platform ADR-0163 picked. From the Podman **v6.0.0**
release notes:

> A new experimental option for the `rootless_port_forwarder` field in `containers.conf` has been
> added, `rootless_port_forwarder="pasta"`. When set, rootless bridge networks will use Pasta's
> kernel-level port forwarding via Pesto instead of rootlessport, preserving the original client
> source IP in network traffic in rootless containers. **The default remains `rootlessport` (the
> default for Podman 5.x)**, but we will investigate switching at a later date when stability is
> more certain.

That last clause is the whole finding: *rootlessport is the default for Podman 5.x*, and Debian 13
ships Podman 5.4.2.

|                  Suite                   |        Podman        | `rootless_port_forwarder` |                passt                | `pesto` |
|------------------------------------------|----------------------|---------------------------|-------------------------------------|---------|
| **Debian 13 trixie** (ADR-0163's choice) | **5.4.2+ds1-2+b2**   | **absent**                | `0.0~git20250503.587980c-2+deb13u1` | no      |
| trixie-backports                         | — (no podman)        | —                         | `0.0~git20260728.f8df3f1-1~bpo13+1` | yes     |
| Debian forky / sid                       | 5.8.6+ds1-2          | **absent**                | `0.0~git20260728.f8df3f1-1`         | yes     |
| Debian experimental                      | 6.1.1+ds1-1          | present                   | —                                   | —       |
| Ubuntu 24.04 LTS (today's host)          | 4.9.3+ds1-1ubuntu0.2 | absent                    | —                                   | —       |
| Ubuntu 26.04 LTS "resolute"              | 5.7.0+ds2-3build1    | **absent**                | —                                   | —       |

Read against the requirement (`>= passt-0^20260507.g1afd4ed`, tightened to `>= 0:20260526.g038c51e`
for pesto): **no current Debian or Ubuntu stable release ships a Podman that can do this.** The
newest Podman is 6.1.2, released 2026-09-16; 6.0.0 landed on 2026-06-24, after Debian 13 froze. The
`podman` column was re-verified on the testing host itself with `apt-cache policy` — trixie/main
offers 5.4.2 and **trixie-backports carries no podman at all**, only the newer passt.

#### Measured on a host, 2026-09-16 — and it was right to measure it

Everything above is documentation. The PVE operator challenged the premise, correctly, on the
grounds that a distribution change for the whole stack should not rest on an unmeasured claim —
and offered a counter-measurement: rootless Podman 5.4.2 on Debian 13, in production for four
days, preserving the real client address.

**Their measurement is sound, and it does not contradict this one.** Their container is on the
**pasta default network**; the edge here is on user-defined bridges. They said so themselves. The
two facts sit side by side, and the experiment they proposed settles it in a quarter of an hour.

It was run on the Debian 13 testing guest with `podman 5.4.2` freshly installed: two containers of
the **same image** (`nginxinc/nginx-unprivileged:1.31.5-alpine`), started in the same minute,
probed from the **same client** in the same second — one attached to a user-defined bridge, one on
the pasta default.

|               Network mode                | Published port |   What the container logged as the client    |
|-------------------------------------------|----------------|----------------------------------------------|
| `--network srctest` (user-defined bridge) | `18080:8080`   | **`10.89.0.2`** — the rootlessport forwarder |
| default (pasta)                           | `18081:8080`   | **`10.1.0.30`** — the real client address    |

One variable, two answers. The finding is no longer documentary.

> [!quote] Debian 13's own `containers.conf(5)`, shipped with podman 5.4.2, states it outright
>
> ```
> port_handler=rootlesskit: Use rootlesskit for port forwarding. Default.
> Note: Rootlesskit changes the source IP address of incoming packets to a IP address in
> the container network namespace, usually 10.0.2.100. If your application requires the
> real source IP address, e.g. web server logs, use the slirp4netns port handler. The
> rootlesskit port handler is also used for rootless containers when connected to
> user-defined networks.
> ```
>
> The last sentence is the whole finding, in the shipped documentation of the very version
> under discussion.

The slirp4netns escape that sentence points at is not available to a bridge-networked container,
and podman refuses the combination rather than silently ignoring it:

```
Error: can only set extra network names, selected mode slirp4netns conflicts with bridge
```

`rootless_port_forwarder` matches **zero** times in that man page, and `port_handler` matches twice
— both in the slirp4netns section. So on the version Debian 13 ships there is no knob for this at
all, which is what Podman 6.0's release notes describe adding.

Four independent lines now agree: the shipped man page, the live measurement, podman's own refusal
of the workaround, and the upstream release notes. The testing guest was left as it was found —
the two containers and the test network removed, the nine-container Docker stack untouched.

#### What it costs here, specifically

This is not "the rate limiter gets less accurate". Six things in this repository read
`$remote_addr`, and every one of them is an access-control or forensic surface:

|                           Surface                           |               Under rootlessport               |
|-------------------------------------------------------------|------------------------------------------------|
| `limit_req_zone $krt_limit_key` — REQ-SEC-023, ADR-0112     | one bucket for the entire internet             |
| `location ^~ /auth/admin` allow-list of the bridge gateways | **inverts** — see below                        |
| `05-default.conf` `allow 127.0.0.1; allow ::1; deny all;`   | same class                                     |
| `api-allowlist.conf` default-deny (ADR-0135)                | same class                                     |
| `X-Forwarded-For` / `X-Real-IP` set from `$remote_addr`     | every module's view of the client              |
| the edge access log                                         | the only record of a rejected request's origin |

> [!danger] The Keycloak admin ACL does not degrade, it inverts
> `10-frontend.conf.template` allows exactly the bridge gateways — `172.28.15.1`, `fd00:28:15::1`,
> `172.28.3.1`, `172.28.4.1`, `172.28.7.1`, `172.28.13.1` — and the comment above it states the
> reason: the ACL matches `$remote_addr`, which is the TCP peer, and traffic tunnelled from the host
> reaches the edge through docker-proxy, so its peer address is the bridge gateway rather than the
> operator's address. The rule's entire security rests on operator traffic and internet traffic
> arriving with **different** peer addresses.
>
> rootlessport collapses that distinction. Every request — tunnel and internet alike — arrives
> from the forwarder's address inside the container network namespace. Either that address is in
> the allow-list, and the admin console is open to the internet, or it is not, and the operator is
> locked out. There is no third outcome, and `nginx -t` cannot see it.

None of this is a new class of failure here. It is one the deployment has already survived once and
wrote down at the time.

> [!bug] `docker-compose.yml` already names this exact failure, as a past outage
> The comment on `net-edge-ingress` records the 2026-07-20 outage: the userland docker-proxy relayed
> every IPv6 client through the bridge gateway, so nginx saw ONE address for all of them and the
> per-IP limiter collapsed into a single bucket. Giving that bridge a real IPv6 subnet is what made
> Docker install the kernel DNAT, so the client address survived.
>
> **rootlessport is that userland proxy.** It applies to IPv4 and IPv6 alike, and the escape Docker
> offered — give the bridge a real IPv6 subnet so the kernel does the DNAT — does not exist,
> because there is no kernel DNAT to reach. The migration would re-introduce, for every client, the
> outage ADR-0112 fixed for IPv6 clients.

#### What this does **not** say

It does not say rootless Podman cannot do this. It says **the version Debian 13 ships cannot**, and
that ADR-0163's choice 2 ("the distribution's own Podman, no third-party repository") is what
makes choice 1 fail. The paths out are in §7 and the choice between them belongs to @greluc.

The *empirical* half is closed as of 2026-09-16 — see the measurement above. The documentation
said what was supposed to happen, and this project's own history is a list of things that were
supposed to happen, so it was measured.

The other half was measured on CentOS Stream 10 with Podman 6.1.0 on the same day, and it is
**split**: a bridge-networked container with `rootless_port_forwarder = "pasta"` logged the real
client address **over IPv4**, and over **IPv6 the forwarder does not deliver the connection at
all**. The default `rootlessport` serves IPv6 but reports the client as an IPv4 bridge address.
Neither option currently satisfies both halves of what the edge needs — see §13, which also lists
what was ruled out and what the options are.

### 3.2 Can the edge bind :80 and :443 rootless, and at what cost? — open, and now cheaper

`net.ipv4.ip_unprivileged_port_start` is **1024** on the production host, re-verified 2026-09-16.

The edge already listens on **8080/8443 inside the container** (`nginxinc/nginx-unprivileged`,
`user: 101:101`) and compose publishes `80:8080` / `443:8443`. So nginx itself never binds a
privileged port and never will; the bind is done by whatever forwards the host side. Three options,
unchanged in shape but now attached to a smaller problem:

- lower `net.ipv4.ip_unprivileged_port_start` to 80 — host-wide, lets *any* unprivileged process
  bind those ports;
- `AmbientCapabilities=CAP_NET_BIND_SERVICE` on the single generated unit — narrower, and the one
  worth measuring first;
- socket activation — systemd owns the listener. Note nginx speaks no `LISTEN_FDS`, so this only
  works for the forwarder, not for nginx directly.

**Decide after §3.1**, because the answer depends on which process does the binding.

### 3.3 Do the network semantics survive? — mostly, with one real gap

Read off `podman-network-create(1)` on 2026-09-16:

|                                  Compose today                                  |       netavark equivalent        |                   Verdict                    |
|---------------------------------------------------------------------------------|----------------------------------|----------------------------------------------|
| `ipam.config.subnet` / `gateway` (all 18 nets)                                  | `--subnet`, `--gateway`          | direct                                       |
| `enable_ipv6: true` (3 app nets + `net-blackbox-v6`)                            | `--ipv6`, or a second `--subnet` | direct                                       |
| `internal: true` (5 `net-proxy-*` + `net-docker-proxy`)                         | `--internal`                     | direct in name; **measure the inbound half** |
| `com.docker.network.bridge.enable_ip_masquerade: "false"` on `net-edge-ingress` | **none**                         | **gap**                                      |

> [!warning] There is no netavark option to turn masquerading off on a managed, non-internal bridge
> The documented driver options are `mtu`, `metric`, `no_default_route`, `vlan`, `isolate`, `vrf`,
> `mode`, `com.docker.network.bridge.name`, `com.docker.network.driver.mtu`. None disables NAT. The
> only "no NAT, no port forwarding" setting is `mode: unmanaged`, which means an existing bridge
> Podman does not manage — not what this is.
>
> The candidate equivalent is **`-o no_default_route=true`** on `net-edge-ingress`: the edge is on
> that one non-internal network and five `internal: true` ones, so with no default route anywhere it
> has no egress, which is the property the masquerade switch was buying. That is a hypothesis about
> a security control and it gets measured, not assumed.

`--internal` is documented as disabling IP forwarding and preventing default routes. Under Docker,
`internal: true` turned out to remove **inbound DNAT as well** — which no documentation said, and
which is the reason `net-edge-ingress` exists at all. Whether netavark's `--internal` behaves the
same way decides whether the ingress bridge is still needed. **Measure it; do not read it.**

**Measured 2026-09-16 — §13.** Both answers came back: `no_default_route=true` does block egress
and leaves the container with no default route, so it is the masquerade equivalent; and netavark's
`--internal` does remove inbound DNAT, so **`net-edge-ingress` stays necessary**.

### 3.4 Does the certificate handover survive user-namespace mapping? — sharpened, and testable

The handover is more delicate than §3.4 assumed, and reading the actual `acme` command makes the
constraint precise. `acme` runs as root-in-container with `cap_drop: [ALL]` plus
**`cap_add: [CHOWN]`**, and for each host it writes a temp file, `chmod`s it, `chown 101:101`s it
and renames it into place — mode **before** ownership, because `CAP_FOWNER` is dropped and root may
not chmod a file it does not own. The directories stay `0:0` deliberately; a `chown -R` broke this
on 2026-09-12.

Under a user namespace, "root in container" is the service user on the host and uid 101 is
`subuid_base + 100`. Three conditions have to hold, and all three are checkable in an afternoon:

1. `CAP_CHOWN` inside the userns is enough to chown to a uid **inside the mapping** — it should be,
   and 101 sits well inside a 65536-wide range;
2. `acme` and `edge` must share **one** mapping. `--userns=auto` gives each container its own range
   and would break the handover outright — so the units must take the default mapping, and that has
   to be stated in the unit files rather than left to whoever writes them;
3. the `edge-certs` volume must be a Podman-managed named volume, as it is under compose today.

`REQ-OPS-026` (a renewed certificate is not delivered until the edge can open it) is the acceptance
here, unchanged.

**Measured 2026-09-16 — §13.** The handover works on CentOS Stream 10 under SELinux: `CAP_CHOWN`
alone sufficed, the chmod-before-chown order held, and `edge` as uid 101 opened the file. The host
uid turned out to be **524388**, from a subuid base of 524288 rather than the Debian guest's
100000 — which is the argument for deriving that number instead of writing it down.

### 3.5 Do healthchecks and resource limits work under a user slice? — yes, with one spelling change

Measured on the Debian 13 testing host, 2026-09-16: cgroup v2, and **`user.slice` already delegates
`cpuset cpu io memory hugetlb pids rdma misc`**. The memory and pids controllers a rootless service
needs are therefore present without further host work.

**The target platform delegates less — and still enough.** CentOS Stream 10 gives the user slice
`cpu memory pids` only (§13). `Memory=`, `PidsLimit=` and `--cpus` each have their controller, and
nothing here uses `io`. The wider Debian list is not a property of rootless Podman, so it should
not be relied on as one.

Read off the **Podman 5.4.2** `podman-systemd.unit(5)` man page — the version Debian 13 ships, not
the latest:

|             Need             |                    5.4.2 Quadlet                    |   6.x Quadlet    |
|------------------------------|-----------------------------------------------------|------------------|
| memory limit (`REQ-OPS-020`) | **no `Memory=`** → `PodmanArgs=--memory=…`          | `Memory=` exists |
| pids limit                   | `PidsLimit=`                                        | `PidsLimit=`     |
| health gate (`REQ-OPS-003`)  | the full `Health*` family, and **`Notify=healthy`** | same             |
| image update                 | `AutoUpdate=`                                       | `AutoUpdate=`    |

This confirms the PVE operator's third finding against the primary source rather than by report.
**It no longer applies to the chosen platform:** CentOS Stream 10 ships Podman 6.1, whose Quadlet
does have `Memory=` — verified in its own shipped man page. The 5.4.2 column is kept because it
is why the workaround was ever planned.
`Notify=healthy` is worth calling out as an **improvement**: it postpones the unit's startup
notification until Podman marks the container healthy, which is a stronger and simpler health gate
than `docker compose up --wait`.

Lingering is **not** configured on the production host today (`/var/lib/systemd/linger` is empty,
verified 2026-09-16), and `/etc/subuid` and `/etc/subgid` exist but are **empty** — so a rootless
service user needs both, as ADR-0163 said.

### 3.6 Is there a container-metrics source with the same series? — **NO. This is a rebuild, not a re-point.**

`prometheus-podman-exporter` prefixes everything `podman_`, and its series are a **subset** of
cAdvisor's. The monitoring configuration reads **17 distinct `container_*` series**; this is what
happens to them:

|                               cAdvisor series (uses in `monitoring/`)                               |                Podman exporter                |
|-----------------------------------------------------------------------------------------------------|-----------------------------------------------|
| `container_threads` (15), `container_threads_max` (15)                                              | **none**                                      |
| `container_memory_rss` (14)                                                                         | **none**                                      |
| `container_spec_memory_limit_bytes` (12)                                                            | `podman_container_mem_limit_bytes`            |
| `container_memory_working_set_bytes` (11)                                                           | **none** (only `mem_usage_bytes`)             |
| `container_label_com_docker_compose_service` (7)                                                    | **none** — the whole label vocabulary changes |
| `container_start_time_seconds` (6), `container_last_seen` (5)                                       | partial                                       |
| `container_memory_mapped_file` (5)                                                                  | **none**                                      |
| `container_cpu_cfs_periods_total` / `_throttled_periods_total` / `_throttled_seconds_total` (4/4/2) | **none**                                      |
| `container_oom_events_total` (2)                                                                    | **none**                                      |
| `container_network_receive_bytes_total` / `_transmit_bytes_total` (1/1)                             | to be confirmed                               |
| `container_cpu_usage_seconds_total` (1)                                                             | `podman_container_cpu_seconds_total`          |

> [!danger] Two of the losses are alerts ADR-0163 named as the reason not to proceed blind
> `container_oom_events_total` is what `ContainerOomKilled` reads, and the `container_threads` /
> `container_threads_max` pair — the most-used series in the whole configuration — is the thread-OOM
> detection that came out of *Three thread-OOMs, and a wget that leaked one process every 30
> seconds*. Neither has a Podman-exporter equivalent.

Version matrix, for whichever platform is chosen: exporter **v2 → Podman 6**, **≥ 1.11 → Podman
5.x**, **≤ 1.10 → Podman 4.x**.

So Phase 4 is not "re-point the alerts". It is: decide, per alert, whether the signal is
reconstructable from `podman_container_*` plus the applications' own `/actuator/prometheus` (which
already carries JVM thread counts and is unaffected by the runtime), or whether the alert is
retired. That is an owner decision with a security-monitoring consequence, and it belongs in the
re-ruling of ADR-0163 rather than after it.

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

### Re-verified 2026-09-16 — three of the facts above have changed

Read directly off `sysadm@10.9.0.12`, not reported:

- **`sudo` works now.** `sudo -n true` succeeds. The paragraph above says the locked password makes
  `sudo` impossible and "until then the host is read-only to us" — that is **superseded**. Phase 1
  is no longer blocked on the owner, and the host can be prepared, snapshotted and broken freely.
- **`trixie-backports` is enabled on it**, which is how the newer `passt` above is visible to
  `apt-cache policy`. It carries no `podman`, so it does not rescue §3.1.
- **Debian 13 does not restrict unprivileged user namespaces.**
  `kernel.apparmor_restrict_unprivileged_userns` is **absent** there, while the Ubuntu production
  host has it set to `1`. AppArmor is active on both. This is one obstacle *fewer* than the current
  host, and it is worth knowing before somebody debugs a rootless failure that was never going to
  happen.
- Unchanged and re-confirmed: Debian 13 trixie, Docker 26.1.5+dfsg1 / Compose 2.26.1-4, cgroup v2,
  `subuid`/`subgid` only for `sysadm` (`100000:65536`), a global IPv6 address on `eth0`
  (`2003:c5:5f03:e509::/64`).

### The trap, re-assessed 2026-09-16 — smaller than recorded

The section below says the edge's `server_name` directives are "literal `profit-base.online` names
with no substitution". **They are not.** All four vhosts are templated — `${EDGE_HOST_FRONTEND}`,
`${EDGE_HOST_INGEST}`, `${EDGE_HOST_GRAFANA}`, `${EDGE_HOST_API}` — and
`docker/edge/render-and-run.sh` **refuses to start** when any of them is unset:

```
edge: refusing to start — unset host variable(s): …
```

So a `:testing` promotion onto a host without those variables produces a container that fails
loudly and is rolled back by the health gate, not an edge quietly serving production host names.
The `acme` half is likewise guarded: an empty `ACME_HOSTS` makes the container **idle** by design
(`"acme: ACME_HOSTS is empty — no certificates are managed on this host"`) rather than request
certificates for the production names.

What remains true: `deploy.sh` still hardcodes `PROFILE=prod` (line 190), so the testing host will
still swap `npm` for `edge` on its next `:testing` promotion, and it needs `EDGE_HOST_*` in its
`.env` before that happens. That is a **configuration task on the testing host**, not a defect to
fix in the repository first — which is a different and much cheaper conclusion than the one below.

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

---

## 6. The production host, as measured 2026-09-16

Read-only inspection under the standing read permission. Every line below was observed, not
recalled, and the commands were `cat /etc/os-release`, `uname -a`, `docker --version`,
`docker compose version`, `stat -fc %T /sys/fs/cgroup`, `sysctl net.ipv4.ip_unprivileged_port_start`,
`nproc`, `free -h`, `df -h`, `lsblk`, `ip -brief addr`, `docker network ls`, `docker ps -a`,
`systemctl list-units/list-timers "iri-*"`, `du -shx`, `ls -la /var/iri`, `getent passwd deploy`,
`cat /etc/subuid /etc/subgid`, `ss -ltnp`, `journalctl --disk-usage`, `sha256sum`, and the Hetzner
metadata endpoint.

### What it is

|                     |                                                                                                                                                                                                |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Provider / location | Hetzner vServer, `eu-central`, **`nbg1-dc3`**, instance-id `124332793`                                                                                                                         |
| CPU                 | **8 vCPU**, AMD EPYC-Genoa                                                                                                                                                                     |
| Memory              | **15 982 920 kB** (16 GB)                                                                                                                                                                      |
| Disk                | **327 684 194 304 B** (305.2 GiB) on a single `/dev/sda`, one root partition                                                                                                                   |
| OS                  | Ubuntu 24.04.5 LTS, kernel 6.8.0-139                                                                                                                                                           |
| Runtime             | Docker **29.8.0**, Compose **v5.5.1**, storage driver `overlayfs`, cgroup driver `systemd`, cgroup **v2**                                                                                      |
| Addresses           | `178.104.94.14/32`, `2a01:4f8:1c19:6462::1/64`, working IPv4 **and** IPv6 default routes                                                                                                       |
| Published ports     | **only** `:80`, `:443` (both via `docker-proxy`, v4 and v6) and `:22`                                                                                                                          |
| Containers          | **22 running**, 3 exited (`npm` in the `rollback` profile, two `edge-probe*`)                                                                                                                  |
| Networks            | **18 project bridges** (`net-*` and `code_net-*`) plus the stock three                                                                                                                         |
| Rootless readiness  | `/etc/subuid` and `/etc/subgid` exist and are **empty**; `/var/lib/systemd/linger` **empty**; `net.ipv4.ip_unprivileged_port_start = 1024`; `kernel.apparmor_restrict_unprivileged_userns = 1` |
| `deploy` user       | uid 999, gid 987, in group `docker`, shell `/sbin/nologin`                                                                                                                                     |

> [!success] The `deploy.sh` staleness gap recorded on 2026-09-12 is closed
> `sha256sum /var/iri/code/scripts/deploy.sh` on the host and `git show origin/main:scripts/deploy.sh
> | sha256sum` both answer `b61d8b7b207f5c25b43a5581c2f85ecbe8688cd2d9ecc8a38fa6133506a80e6f`. The
> host is running the current deployer. The *mechanism* that let it drift is unchanged, so this is a
> point-in-time fact and not a fix.

### How much actually has to move

`/` is 41 % used — 116 GB of 301 GB — and most of that is not data:

|                        Path                         |               Size               |                                        Migrate?                                         |
|-----------------------------------------------------|----------------------------------|-----------------------------------------------------------------------------------------|
| `/var/iri-http2`                                    | **37 GB**                        | **no** — a full copy of `/var/iri` taken 2026-09-11, referenced by no running container |
| `/var/lib/containerd`                               | **35 GB**                        | no — the image store; the new host pulls its own                                        |
| `/var/iri-userid`                                   | **26 GB**                        | **no** — a full copy of `/var/iri` taken 2026-08-29, likewise unreferenced              |
| `/var/iri/monitoring/data`                          | 15 GB                            | a decision — Prometheus TSDB, Loki and Tempo history                                    |
| `/var/iri/frontend` · `backend`                     | 191 MB · 155 MB                  | yes (app state)                                                                         |
| `/var/iri/db-backend` · `db-keycloak`               | 188 MB · 71 MB                   | **restored from backup, not copied**                                                    |
| `/var/iri/redis`                                    | 22 MB                            | yes                                                                                     |
| `/var/iri/code` · `secrets` · `keycloak` · `ingest` | 2.9 MB · 20 KB · 116 KB · 496 KB | yes                                                                                     |
| `/var/iri/npm`                                      | 143 MB                           | no — the retired proxy; its Let's Encrypt archive is superseded by `edge-certs`         |
| journal                                             | 1.6 GB                           | no                                                                                      |

**The live application state is under a gigabyte**, plus whatever monitoring history is judged worth
carrying. The 63 GB in `/var/iri-userid` and `/var/iri-http2` are two undocumented pre-change
snapshots; they are named here so the next person does not size a server around them, and whether
they are deleted is a separate decision (a host write, and therefore the owner's).

### The "equivalent" Hetzner VM — answered by the owner, 2026-09-16

The measured shape is **8 vCPU / 16 GB / 327.68 GB**. The metadata endpoint does not expose the
plan name and the hostname `ubuntu-8gb-nbg1-1` records what the server was created as rather than
what it is, so this was the one fact the inventory could not settle from inside. The owner read it
off the Console.

**It is a CPX42** — 8 shared AMD vCPU, 16 GB RAM, 320 GB NVMe, 20 TB traffic — which matches the
measurements exactly and is a current type rather than a legacy one (the older CPX41 carries the
same CPU and RAM with 240 GB). An equivalent new host is a second CPX42 in `nbg1`, and no Volume
is needed: the data that has to move is under a gigabyte plus whatever monitoring history is
carried, once the two dead snapshots and the image store are excluded.

> [!note] Debian 13 images are offered by Hetzner Cloud; the ARM lines are not a candidate
> Every application image in this deployment is `linux/amd64` **and** `linux/arm64` (the release
> matrix builds both), but Keycloak, the exporters and the monitoring images are pinned by digest
> per architecture, and `check-monitoring-image-pins.sh` compares those pins against the documents.
> Moving to `CAX`-class ARM would be a second migration riding inside this one. Out of scope.

## 7. The paths out of §3.1 — **A was chosen, 2026-09-16**

Four, and they are genuinely different amounts of work. Each keeps rootless Podman except the last
two; none of them is "work around §3.1", because there is nothing to work around — the address is
either preserved or it is not.

**A — Podman 6.x, from a source newer than Debian 13 stable.** **Examined on 2026-09-16 — see §8: feasible, on CentOS Stream 10 or Fedora 45, not on Debian 13.** The only path that keeps the
architecture ADR-0163 describes intact: bridge networks, the 18 segments, the edge where it is.
Costs choice 2 of the ADR ("the distribution's own Podman"). The feature is **experimental and
off by default upstream**, which for an internet-facing rate limiter is the part to weigh, not the
packaging. Needs: a Podman ≥ 6.0 source Debian 13 can carry, `passt ≥ 0:20260526` (trixie-backports
already has it), and `rootless_port_forwarder="pasta"` measured against a real client before
anything else is built.

**B — the edge in the host network namespace.** No port forwarding at all, so the source address is
the client's by construction, and §3.2 shrinks to one `CAP_NET_BIND_SERVICE`. The price is the
topology: the edge stops being on `net-proxy-*` and reaches its five upstreams over host-published
loopback ports, so those five services become reachable by anything else on the host. That is a real
loss against `Topology`'s "a container can reach exactly the containers it has a named network in
common with", and it is bounded — the data networks and every other segment are untouched.

**C — a hybrid: the edge stays rootful, everything else goes rootless.** ADR-0163 already considered
and rejected this as "halving the benefit". It is worth re-reading now rather than re-rejecting from
memory, because the benefit it halves is smaller than it looked: the edge is already uid 101,
`cap_drop: [ALL]`, `read_only: true`, no egress. What stays rootful is one hardened container and
the daemon under it.

**D — stay on Docker.** ADR-0163's own stated consequence of a negative §3.1: "A negative result does
not mean 'work around it'. It means this ADR is rejected and the host stays on Docker." Cheapest, and
it keeps a root daemon and the socket that two monitoring components can reach through a GET-only
proxy.

> [!important] Phase 0 is worth doing under **all four**
> The conformance suite asserts the invariants against a running host and has to go green against
> today's Docker stack first. It is the missing external assertion for the edge whatever happens
> next, and it is the only part of this plan that needs no decision. It is also the thing that would
> have caught §3.1 empirically, three phases later and after the work.

---

## 8. Path A, examined — 2026-09-16

**Verdict: A is feasible, and not on Debian 13.** It is available today, entirely from a
distribution's own base repositories, on **CentOS Stream 10** and on **Fedora 45**. Choosing it
therefore does not cost ADR-0163's choice 2 (no third-party repositories, no cross-release pinning);
it costs choice 1, the Debian-13-to-match-testing decision.

### Measured by running it, not by reading about it

Both sides were checked by installing the distribution's own package in a throwaway container and
asking the binary. No inference.

|                                                            |        Debian 13 `debian:13`        |                                          CentOS Stream 10 `quay.io/centos/centos:stream10`                                           |
|------------------------------------------------------------|-------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `podman --version`                                         | **5.4.2**                           | **6.1.0**                                                                                                                            |
| `passt`                                                    | `0.0~git20250503.587980c-2+deb13u1` | `0^20260728.gf8df3f1-1.el10`                                                                                                         |
| `/usr/bin/pesto`                                           | **No such file or directory**       | present, 40 920 bytes                                                                                                                |
| `rootless_port_forwarder` in the shipped `containers.conf` | **absent**                          | line 424, `#rootless_port_forwarder = "rootlessport"`                                                                                |
| the same name in `containers.conf(5)`                      | **0 matches**                       | documented: *Select the port forwarding mechanism for rootless bridge networks. Valid options are rootlessport (default) and pasta.* |

### The distribution matrix, as measured

|     Distribution     |  podman   |    passt     | `pesto` | netavark | satisfies A |
|----------------------|-----------|--------------|---------|----------|-------------|
| **CentOS Stream 10** | **6.1.0** | `0^20260728` | **yes** | 2.1.0    | **yes**     |
| **Fedora 45**        | **6.1.1** | `0^20260728` | **yes** | 2.1.0    | **yes**     |
| Rocky Linux 10       | 5.8.2     | `0^20251210` | no      | 1.17.2   | no          |
| AlmaLinux 10         | 5.8.2     | `0^20251210` | no      | 1.17.2   | no          |
| Debian 13 trixie     | 5.4.2     | `0^20250503` | no      | 1.14.0   | no          |
| Ubuntu 26.04 LTS     | 5.7.0     | —            | —       | —        | no          |

CentOS Stream 10 also carries aardvark-dns 2.1.0, crun 1.29.1 and conmon 2.2.1; Fedora 45 matches on
the first two.

> [!note] Rocky and AlmaLinux are behind by construction, not by neglect
> Both are rebuilds of *released* RHEL — 10.2 at the time of writing — while Stream is the branch RHEL
> is cut from. They will get Podman 6 when a RHEL minor rebases container-tools onto it, and Red Hat
> documents that stream as rebasing on the latest stable upstream Podman up to four times a year.
> That is a direction, not a date, and a migration cannot be scheduled against it.

### Why Debian 13 cannot get there

Four independent reasons, any one of which is sufficient:

1. **No official Podman apt repository exists.** The Podman project's own installation page directs
   Debian and Ubuntu users to `apt-get install podman` from the distribution and offers nothing of
   its own. The retired Kubic repositories are not a fallback.
2. **Debian has 6.x in `experimental` only** — not in `sid`, where `5.8.6` still sits. That is
   Debian's own judgement of readiness, and it is worth reading next to upstream's: the feature in
   question is flagged **experimental** by Podman too.
3. **Installing the experimental package on trixie means a library transition.** It depends on
   `libgpgme45 (>= 2.2.0)`, and `libgpgme45` exists **only in forky and sid**; trixie has
   `libgpgme11t64` at gpgme 1.24.2. Pulling gpgme 2.2 into a stable host, for the library Podman
   verifies image signatures with, is exactly the supply chain ADR-0163 rejected when it turned down
   APT pinning from a non-LTS Ubuntu.
4. **Rebuilding it for trixie is not a backport, it is adopting the stack.** The source package
   build-depends on `golang-github-containers-buildah-dev (>= 1.45.0~)`, `-common-dev (>= 0.69.1~)`,
   `-image-dev (>= 5.41.1~)`, `-storage-dev (>= 1.64.0~)`, `-gvisor-tap-vsocks-dev (>= 0.7.4)`,
   `-psgo-dev (>= 1.10)` and `golang-github-opencontainers-runc-dev (>= 1.3)` — trixie carries the
   5.4.2-era versions of all of them. That is six or more source packages to maintain, with their
   security updates, forever.

### And there is no architectural way around it

Confirmed on 2026-09-16, and it closes the last alternative that would have kept Debian 13:
**pasta and bridge networks are mutually exclusive on one container.** Podman refuses with `cannot
set multiple networks without bridge network mode, selected mode pasta`. Upstream carries the exact
shape of this deployment as a known limitation — a reverse proxy that must see the client address
while its upstreams are reachable only to it, and not to the host — and `rootless_port_forwarder`
is the answer that was written for it.

So the edge cannot take pasta for ingress and bridges for its five upstreams. It is Podman 6 or a
different topology.

### CentOS Stream 10 against Fedora Server — the one that decides it is the lifecycle

Both satisfy A today. They differ in what they cost afterwards, and the difference is not subtle.

|                          |                                            CentOS Stream 10                                            |                                      Fedora Server                                      |
|--------------------------|--------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| Podman today             | 6.1.0                                                                                                  | 6.1.1                                                                                   |
| Supported until          | **2030-05-31**                                                                                         | ~13 months per release (F43 ends 2026-12-09; F44, released 2026-04-28, ends 2027-06-02) |
| New release every        | ~3 years                                                                                               | ~6 months                                                                               |
| How Podman stays current | **container-tools is a rolling AppStream that rebases on the latest stable upstream, up to 4x a year** | by upgrading the whole distribution                                                     |
| Host rebuilds implied    | one, then roughly 2030                                                                                 | **roughly one a year**                                                                  |
| Relationship to upstream | Red Hat is upstream for Podman, Quadlet, netavark and passt; Stream is the branch RHEL is cut from     | closest to upstream of anything shipping                                                |
| Confinement              | SELinux enforcing                                                                                      | SELinux enforcing                                                                       |
| Offered by Hetzner       | yes (version to confirm in the Console)                                                                | yes (version to confirm in the Console)                                                 |

> [!important] The decisive argument is not stability, it is where the Podman version comes from
> This migration failed on Debian 13 for one structural reason: **the distribution freezes Podman and
> the project needed a newer one.** Fedora solves that by making you replace the distribution every
> year. CentOS Stream solves it by moving the container stack *inside* a base supported to 2030 —
> which is the same property, without an annual rebuild and DNS cutover on a host this project's own
> rules say must be rebuilt rather than upgraded.

Where Fedora genuinely wins: if the experimental `rootless_port_forwarder` needs a fix, Fedora gets
it first. That is worth something precisely because the feature is experimental. It is not worth an
annual production rebuild, and Stream's four-times-a-year rebase is not far behind.

Where Stream genuinely loses: it is the *forward* branch of RHEL, not a frozen one. Packages reach it
before RHEL customers see them, so a regression can arrive here first — and the container stack in
particular will keep moving under the deployment. For a stack that would be resting on an
experimental flag, that cuts both ways and should be said out loud rather than filed under
"enterprise distribution".

Rocky Linux 10 and AlmaLinux 10 are the frozen alternative and they do not qualify: 5.8.2, measured.

> [!question] The option that is not on the list, and should be
> If the only reason to leave Debian is Podman 6, then **waiting** is a real option, not a
> non-answer. Debian 14 "forky" is at 5.8.6 today and would very likely carry 6.x at release. That
> is path D with an expiry date attached: stay on Docker, re-run §3.1 against forky when it freezes,
> and migrate then onto a Debian that matches the testing host — which is what ADR-0163 wanted in the
> first place. It costs the security case another year and costs nothing else.

### What choosing the RHEL family costs

None of these is a blocker; all of them are work that has to be planned rather than discovered.

- **SELinux replaces AppArmor**, enforcing by default. Every bind mount in the stack needs a correct
  label — `:z` / `:Z` on the Quadlet `Volume=` lines, or a matching `semanage fcontext` rule. This
  is the RHEL-family equivalent of the certificate-handover question in §3.4, and it should be
  measured on the same afternoon. It is also a genuine gain: a second confinement layer under the
  user namespace.
- **The bootstrap documentation is Debian/Ubuntu-shaped.** `docs/deployment.md` speaks `apt`. The
  host preparation becomes `dnf`, and the unattended-upgrades equivalent is `dnf-automatic`.
- **The testing host diverges again.** ADR-0163's choice 1 existed to end exactly that. Either the
  testing host moves to CentOS Stream 10 as well — it is a PVE VM with ZFS snapshots, so this is
  cheap — or the rehearsal environment stops proving what it is there to prove. **This is the real
  cost of A, and it should be decided together with A rather than after it.**
- Nothing about the images changes. All five published images are OCI and architecture-matched; the
  host distribution is invisible to them.

### The risk that does not go away

`rootless_port_forwarder="pasta"` is **experimental and off by default**, by upstream's own
description, and the control it would be carrying is an internet-facing rate limiter plus an admin
allow-list. Two separate parties have said *not yet* about this code path: upstream by defaulting it
off, Debian by keeping 6.x out of unstable.

That does not make A wrong. It makes A conditional on a measurement that has to be done properly,
and on a decision about what happens if the flag regresses in a later Podman.

> [!important] The acceptance test for A, before anything is built on it
> Not "does it start". On a host with the real edge configuration, from a client outside the host:
>
> 1. the edge access log shows the **client's own** address, for IPv4 and for IPv6;
> 2. `limit_req_zone` buckets two different clients separately, and the IPv6 `/64` key from ADR-0112
>    still collapses one subscriber's rotating addresses into one bucket;
> 3. the `location ^~ /auth/admin` allow-list still **admits** the tunnelled operator and **refuses**
>    an external client — both directions asserted, because a rule that admits everyone and a rule
>    that admits no one look identical from one side;
> 4. all of the above survives a container recreate and a host reboot.
>
> That is four assertions, and they are the same four the Phase 0 conformance suite has to make
> against the current Docker stack anyway. **Build the suite first; it is the acceptance test.**

---

## 9. The decision, and what it changes in the phases — 2026-09-16

**Path A, on CentOS Stream 10.** Ruled by @greluc on 2026-09-16; ADR-0163 is Accepted with choice 1
amended. The platform moved; the decision did not. It is still rootless Podman under Quadlet on a
rebuilt host, and still distribution packages only — from a distribution that is not Debian.

The target host is a second Hetzner **CPX42** (8 shared AMD vCPU, 16 GB RAM, 320 GB NVMe, 20 TB
traffic) in `nbg1`, matching the current one, on Hetzner's CentOS Stream 10 image.

### What is now settled

|                         |                                                                                                                                                         |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| §3.1 the source address | **answerable** — `rootless_port_forwarder="pasta"` exists on the chosen platform. Still to be *measured*, because it is experimental and off by default |
| §3.2 binding :80/:443   | unchanged in shape; decide `AmbientCapabilities=CAP_NET_BIND_SERVICE` against the host-wide sysctl once it is known which process binds                 |
| §3.5 memory limits      | **closed** — Quadlet 6.1 has `Memory=`; the `PodmanArgs=--memory=` workaround is unnecessary                                                            |
| §3.6 container metrics  | unchanged as a problem; the exporter version is now pinned by the platform — **v2 pairs with Podman 6**                                                 |

### What the phases gain

Three work items that did not exist while the target was Debian, and one that got smaller.

**SELinux is a first-class work item.** It is enforcing by default on the RHEL family, and every
bind mount in the stack needs a correct label — `:z` / `:Z` on the Quadlet `Volume=` lines, or a
matching `semanage fcontext` rule. It ranks with the certificate handover in §3.4 and belongs in the
same Phase 2 afternoon, because the two interact: `acme` writing files that `edge` must open, under
a user namespace **and** a label transition. The payoff is a second confinement layer beneath the
user namespace, which is the security case this ADR was made for in the first place.

**The host bootstrap becomes `dnf`-shaped.** `docs/deployment.md` speaks `apt` throughout, and the
unattended-upgrade equivalent is `dnf-automatic`. Straightforward, but it is a rewrite rather than a
translation, and it is the document a future rebuild is driven from.

**`isolate` now defaults to `strict`.** Netavark 2 isolates bridge networks from one another unless
told otherwise, and `podman-network-create(1)` names `isolate=false` as the way to restore the
pre-Podman-6 behaviour. This **agrees with** the design — the topology's model never routes between
bridges and treats shared membership as the only path — but it is a default that changed underneath
the plan, so it gets asserted rather than assumed. A change in the friendly direction is still a
change.

**Still no masquerade switch**, on netavark 2.1 either: the documented bridge options are `mtu`,
`metric`, `no_default_route` and `isolate`, and masquerading is tied to `mode=managed`. So
`net-edge-ingress`'s `enable_ip_masquerade=false` has no direct equivalent and
`-o no_default_route=true` remains the candidate to measure.

> [!warning] Podman 6.0 removed five things. None is used here, and one will still surprise a reader
> slirp4netns, CNI, iptables (in favour of **nftables**), cgroups v1 and BoltDB. The nftables move
> is the one to remember: somebody reading firewall state on the new host and expecting `iptables`
> output will conclude the rules are missing.

### The rehearsal environment — **answered in §11**

Choice 1 existed so production and testing would match. Moving production to CentOS Stream 10
re-opens exactly that, and there are two honest answers:

- **Run Phases 1-4 on a short-lived CentOS Stream 10 VM on PVE**, which §4 already establishes there
  is room for, and leave the permanent testing host on Debian 13. Cheap, and it rehearses the real
  platform — but the *permanent* rehearsal ground then differs from production again.
- **Move the testing host to CentOS Stream 10 as well.** It is a PVE VM with ZFS snapshots that roll
  back in seconds, so the cost is low and the property is restored.

**The second was chosen on 2026-09-16 — see §11.** The testing host migrates first, and production is not touched until everything provable there has been proven.

### Sequencing from here

1. **Phase 1 on a CentOS Stream 10 VM**: the §3 questions re-asked against Podman 6.1 — pasta port
   forwarding with a real external client, `--internal` inbound behaviour, `no_default_route` as the
   egress block, cgroup delegation on a user slice, and SELinux labels on the acme/edge handover.
2. **Phase 2**, the Quadlet translation, with `Memory=` and `Notify=healthy` rather than the 5.4.2
   spellings.
3. **Phases 3-6** unchanged in shape.

Phase 0 is done and does not need redoing: the conformance suite is platform-agnostic by
construction, and `client-address-visible` is the acceptance test for step 1.

---

## 10. Container observability under Podman — what is preserved, and what is gained

§3.6 said this is a rebuild rather than a re-point, and that stands. What it did **not** establish,
and what decides how much work it is, is whether anything is genuinely *lost*. It is not.

> [!success] Every signal the alerts read is in cgroup v2. Only the collector changes.
> Verified on 2026-09-16 by reading the cgroup files of a running container directly:
>
> |     Alert needs      |                cgroup v2 file                 |                                  field                                  |
> |----------------------|-----------------------------------------------|-------------------------------------------------------------------------|
> | OOM kills            | `memory.events`                               | `oom_kill`                                                              |
> | CPU throttling       | `cpu.stat`                                    | `nr_periods`, `nr_throttled`, `throttled_usec`                          |
> | pids and the ceiling | `pids.current`, `pids.max`                    | the values themselves                                                   |
> | memory and its limit | `memory.stat`, `memory.current`, `memory.max` | `anon` (RSS), `inactive_file` (working set = `current - inactive_file`) |
>
> cAdvisor never had privileged access to anything the kernel does not publish here. It read these
> files and gave them Docker's names.

### The seven alerts, one by one

|             Alert             |                         reads today                          |                                              under Podman                                              |         source         |
|-------------------------------|--------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|------------------------|
| `ContainerRestartLoop`        | `container_start_time_seconds`                               | `podman_container_started_seconds`                                                                     | exporter, **direct**   |
| `ContainerMetricsMissing`     | `container_last_seen`                                        | `absent(podman_container_state{...})`                                                                  | exporter, rewritten    |
| `CoreContainerMetricsMissing` | `container_last_seen`                                        | same                                                                                                   | exporter, rewritten    |
| `ContainerMemoryHigh`         | `container_memory_rss` ÷ `container_spec_memory_limit_bytes` | `podman_container_mem_usage_bytes` ÷ `podman_container_mem_limit_bytes`, or exactly from `memory.stat` | exporter **or** cgroup |
| `ContainerPidsHigh`           | `container_threads` ÷ `container_threads_max`                | `podman_container_pids` **plus a ceiling the exporter does not publish**                               | exporter + cgroup      |
| `ContainerOomKilled`          | `container_oom_events_total`                                 | `memory.events` → `oom_kill`                                                                           | **cgroup only**        |
| `ContainerCpuThrottledHigh`   | `container_cpu_cfs_*`                                        | `cpu.stat` → `nr_periods` / `nr_throttled`                                                             | **cgroup only**        |

Five of seven come from `prometheus-podman-exporter` directly or with a rewritten expression. Two
have no exporter equivalent at all, and one more is only half-served — and all three of those are a
`cat` away in the cgroup tree.

> [!warning] The threshold on `ContainerMemoryHigh` has to be re-derived, not carried across
> `podman_container_mem_usage_bytes` is what `podman stats` reports, which is not
> `container_memory_rss`. A percentage tuned against one will misfire against the other. If the
> alert is to keep its meaning rather than its number, take memory from the cgroup bridge below,
> where `anon` and `working set` are exactly what cAdvisor was reporting.

### The bridge is a pattern this deployment already runs

node_exporter's **textfile collector** is already in use here: `/var/iri/monitoring/textfile` exists
on the production host and `deploy.sh` writes `basetool_monitoring_config_applied_timestamp` into it.
So a small periodic reader that walks the rootless containers' cgroup paths and writes the six
series above as a `.prom` file is not a new mechanism — it is the established one, and it needs no
socket, no daemon and no privilege beyond reading `/sys/fs/cgroup`.

That matters for the security case: cAdvisor and Alloy reach the Docker socket today through a
GET-only proxy that exists precisely because the raw socket is root-equivalent. The cgroup reader
needs none of that.

> [!note] cAdvisor is not the way back, and it is worth saying why
> Its rootless-Podman issue upstream is **closed as not planned**, with reporters on Podman 4.9.3 /
> cAdvisor 0.49.1 getting no CPU or memory metrics at all. Keeping cAdvisor would mean betting the
> `container_*` family on an integration its maintainers have declined.

### What the migration gains, which is the other half of the question

Three signals that do not exist in the deployment today:

- **`podman_container_health`** — health status as a *metric* (`-1` unknown, `0` healthy,
  `1` unhealthy, `2` starting). Today a container's health is visible only to `docker inspect`, and
  `REQ-OPS-003`'s health gate is the only thing that ever looks. An unhealthy container that is
  still `Up` currently raises nothing.
- **`podman_container_exit_code`** — `137` is the OOM-kill signature, and a second, independent
  witness beside `memory.events`.
- **systemd unit state.** Quadlet units *are* systemd services, so node_exporter's
  `--collector.systemd` yields `node_systemd_unit_state` and restart counters for every container.
  That is a whole signal class with no equivalent under Compose, and it is free.

Plus the exporter's own block-IO and per-interface network counters, which the current cAdvisor
configuration deliberately does not collect (`disk` was disabled because Docker 29's containerd
snapshotter made it spam `fsHandler overlayfs no such file`).

> [!danger] The label vocabulary changes, and it is not cosmetic
> Seven places in `monitoring/` group by `container_label_com_docker_compose_service`, which exists
> only because Compose sets it. The exporter labels by `name`, `id`, `image` and `pod`. Every
> dashboard query and alert expression carrying that label has to move to `name`, and
> `REQ-OBS-006`'s cardinality bound has to be re-checked against the new label set rather than
> assumed to hold.

### Acceptance

Unchanged from Phase 4 and deliberately strict: not "the metric exists" but **the alert fires**.
`ContainerRestartLoop`, `ContainerOomKilled`, `ContainerMemoryHigh`, `ContainerPidsHigh`,
`ContainerCpuThrottledHigh` and both `*MetricsMissing` guards each have to be induced on the testing
host and observed firing, with their promtool unit tests updated in the same change.

---

## 11. The testing host goes first — ruled 2026-09-16

**The permanent testing host migrates to CentOS Stream 10 and rootless Podman first.** Production is
not touched until everything that *can* be proven there has been. @greluc's ruling, and it restores
what ADR-0163's choice 1 was for: a rehearsal environment that matches production.

It also settles §9's open question. There is no throwaway-VM compromise: the testing host **is** the
rehearsal ground, it is rebuilt as the target platform, and it stays there.

> [!tip] The rollback is a snapshot, not a restore
> A ZFS snapshot is taken **immediately after the bootstrap succeeds and before the first
> experiment**, and again at each phase boundary. Going back is then seconds rather than a rebuild,
> which is what makes it reasonable to break things on purpose — and breaking things on purpose is
> the entire point of the next four phases. The host's disk is marked `backup=0`, so the snapshots
> are the only net; there is no vzdump copy behind them.

### What is there today, measured 2026-09-16

|                |                                                                                      |
|----------------|--------------------------------------------------------------------------------------|
| Virtualisation | KVM guest, `qemu-guest-agent` active                                                 |
| Sizing         | **4 vCPU, 12 GB RAM, 120 GB disk** with 12 GB used                                   |
| OS             | Debian 13 trixie, cgroup v2, AppArmor active, **no** unprivileged-userns restriction |
| Runtime        | Docker 26.1.5+dfsg1, Compose 2.26.1-4                                                |
| Stack          | **9 containers** — the full app profile, including the native `edge` and `acme`      |
| Access         | `ssh sysadm@10.9.0.12` from the LAN, key auth, **`sudo` now works**                  |
| Networks       | `eth0` with a global IPv6 and a working v6 default route; `eth1` on a second segment |

> [!success] The `:testing` edge cutover already happened, and it was clean — corrected 2026-09-16
> §4 records a trap: `deploy.sh` hardcodes `PROFILE=prod`, so the next `:testing` promotion would
> swap `npm` for an `edge` matching none of that host's names, beside an `acme` asking for
> production certificates. **It sprang, and nothing broke.** The edge has been `healthy` for 17
> hours, logged `edge: rendered 4 vhost(s)`, and `npm` is `Exited (0)`. `acme` is up with no output
> at all, which is its documented idle behaviour when `ACME_HOSTS` is empty.
>
> Two guards did the work, and both were added after that section was written: the vhost names are
> `${EDGE_HOST_*}` and `render-and-run.sh` refuses to start on an unset one, and the empty
> `ACME_HOSTS` makes `acme` idle rather than request anything. The trap as recorded is resolved.

### The gap that has to close with the rebuild

**There is no monitoring plane on the testing host at all** — no Prometheus, no Grafana, no Loki,
no exporters. Nine containers against production's twenty-two. §4 already noted this; §10 makes it
decisive, because container observability is now known to be a **rebuild** rather than a re-point,
and a rebuild cannot be rehearsed on a host that has nothing to rebuild.

So the monitoring stack comes up on the testing host as part of this migration. That is new scope,
and it is not optional: without it, Phase 4 has no rehearsal ground and would arrive at production
unproven — which is the one thing this plan exists to prevent.

Sizing that against what is there: 12 GB RAM for twenty-two containers, where production uses
4.5 GB of 16 GB for the same set, and ~100 GB free disk against production's 15 GB of monitoring
data. Retention on testing can be short, so the disk is comfortable; **the memory is the thing to
watch**, and it is a reason to bring the monitoring plane up early rather than last.

### Sequence

1. **Provision** a CentOS Stream 10 VM to replace the current testing guest — same sizing or better.
   The old guest is kept until the new one is proven, so this is a build-beside, exactly as
   production will be.
2. **Bootstrap** it as the documented procedure, rewritten `dnf`-shaped: the rootless service user
   with its subuid/subgid range, lingering, cgroup delegation, SELinux, and the `:80`/`:443`
   decision from §3.2. The documentation is the deliverable, not a by-product — production is built
   from it afterwards.
3. **Snapshot.** Before anything is experimented with.
4. **Phase 1**, the §3 measurements against Podman 6.1, snapshotting between experiments:
   `rootless_port_forwarder="pasta"` with a real external client from the LAN (this host's `eth0`
   carries a global IPv6 and its guest firewall admits the management networks in full, so both
   families are testable), `--internal` inbound behaviour, `no_default_route` as the egress block,
   cgroup delegation on a user slice, and the SELinux labels on the acme → edge handover.
5. **Phase 2**, the Quadlet translation of all twenty-two services.
6. **Phase 3**, the delivery rebuild, and **Phase 4**, the observability rebuild — which is why the
   monitoring plane has to be standing by then.
7. **Only then** Phase 5, the new production host.

### The acceptance, and it already exists

The Phase 0 conformance suite is the gate at every step, and its testing-host baseline was taken on
2026-09-16, **before** the platform changes — a suite that has never run there proves nothing when it
passes later.

**Testing on Docker, 2026-09-16: 3 passed, 6 failed, 2 skipped.** Every failure is a true finding
about that host, and together they are the list the migration has to close:

|  Result  |                            Check                            |                                                                                 What it says about the testing host                                                                                  |
|----------|-------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **fail** | `client-address-visible`                                    | the edge logs `10.98.0.10` — the reverse proxy in front of the VM. **This independently confirms §3.1's claim** that the testing edge already sees one bucket for every request, under Docker, today |
| **fail** | `scrape-targets-up`, `container-metrics`, `log-streams`     | no monitoring plane at all                                                                                                                                                                           |
| **fail** | `ipv6-reachable`                                            | the public names carry no AAAA; they reach the host through a v4 proxy                                                                                                                               |
| **fail** | `vhost-reachable`                                           | the grafana vhost closes the connection — it is rendered, and there is no Grafana behind it. Resolves itself when the monitoring plane comes up                                                      |
| skip     | `certificate-shared`                                        | `ACME_HOSTS` is empty, so certificates are provided rather than issued and the one-leaf property is not claimed here                                                                                 |
| pass     | `certificate-valid`, `http-redirects`, `containers-running` | the parts that already match production                                                                                                                                                              |

> [!important] The source-address measurement cannot use the public name on this host
> `client-address-visible` failing here is **correct and unavoidable**: the reverse proxy in front
> of the VM rewrites the source before the edge ever sees it, whatever the container runtime is. So
> Phase 1's pasta measurement must connect to the VM's **own LAN interface directly**, not to
> `basetool.greluc.me`. Its `eth0` has a global IPv6 and its guest firewall admits the two
> management networks in full, which is exactly the property that makes the mechanism testable
> there — and it is the reason §3.1 split the mechanism from the production-shaped behaviour.

Running the suite against the testing host is also what found two defects in the suite itself, both
fixed the same day: it matched SAN entries as literal strings, so a wildcard certificate read as
four uncovered vhosts; and it asserted "one leaf across every vhost" as a universal invariant when
it is a property of *this deployment issuing its own certificates*. The second now reads
`ACME_HOSTS` and skips where nothing is issued. **A check that has only ever run against one host
has only ever been tested against one host.**

---

## 12. The host bootstrap is an Ansible role — ruled 2026-09-16

[ADR-0186](adr/0186-the-host-bootstrap-is-an-ansible-role.md). @greluc's decision, and it follows
from §11 rather than from a preference for the tool: **the testing host is built first and
production is built from the same procedure afterwards.** A prose checklist executed twice by a
human is not the same procedure twice. It is two procedures that resemble each other, and the
resemblance is exactly what that sequence was supposed to guarantee.

Three facts made it more than a convenience. This project already decided that host configuration is
not a document (ADR-0049 / `REQ-OPS-004` — it is a promotable, digest-pinned artifact, and
hand-editing it on the host is forbidden), so the bootstrap being prose was the **exception** to a
rule already made everywhere else. Phases 1-4 snapshot, break and re-bootstrap the testing host
repeatedly, which makes idempotence the loop rather than a nicety. And ADR-0163 chose "rebuild
rather than upgrade", so this recurs by design.

`ansible/` holds the role; [`PODMAN_HOST_BOOTSTRAP.md`](PODMAN_HOST_BOOTSTRAP.md) keeps the **why**
and is not duplicated into it. One thing the role does better than the prose: the uid arithmetic is
**derived from the same variable that grants the subuid range**, rather than transcribed — so a
hand-computed `110000` cannot go silently wrong the day that base changes, and the ownership tasks
gain real idempotence that a `command:` wrapping `podman unshare` could not have.

> [!danger] The boundary is the load-bearing half
> **Ansible provisions; `deploy.sh` deploys.** The playbook ships no unit files, pulls no image, and
> is never run against a host that is serving traffic. The temptation is concrete and will arrive
> quickly — once a playbook configures the host, running the playbook is one short step from
> pushing the next compose file with it, and `REQ-OPS-001`'s pull-only property is gone by
> convenience rather than by decision.

### A correction the decision forced — `REQ-OPS-001`

The obvious objection was that Ansible is push-over-SSH while `REQ-OPS-001` is *pull-only delivery*.
Checking it turned up a defect in the requirement rather than in the plan.

Its prose read **"There is no inbound SSH"**, flatly. Its own acceptance criteria have always said
something narrower — *"no SSH key, deploy key, or git credential is provisioned **for the deploy
path**"* — and the reality, documented at length in the production-access runbook, is that the
operator's SSH **is** the host's sole administrative entrance and the only route to the two
loopback-bound admin interfaces. @greluc confirmed on 2026-09-16 that it exists and stays.

So the prose was false and the acceptance criteria were right: the requirement governs the
**delivery mechanism, not human access**. Corrected the same day in
[`docs/specs/deployment-delivery.md`](specs/deployment-delivery.md),
[`docs/deployment.md`](deployment.md) and [ADR-0049](adr/0049-config-as-promotable-oci-artifact.md).
Ansible at bootstrap therefore adds no inbound path that did not already exist.

It is written down because an objection resting on a sentence that turns out to be wrong is worth
saying out loud rather than quietly dropping — and because a requirement whose prose is false
teaches its readers not to trust the ones that are true.

## 13. Phase 1 — measured on the target platform, 2026-09-16

Every open experiment in §3 has now been run, on the **CentOS Stream 10 testing VM with Podman
6.1.0**, against a client on the LAN. The PVE operator took the snapshot `vor-phase-1` first; the
host was left clean afterwards — no containers, no unit files, no failed units.

This section is the record of what the host said. Where it disagrees with §3, §3 is now annotated to
point here.

> [!danger] One of the answers is negative, and it is the load-bearing one
> `rootless_port_forwarder="pasta"` preserves the client address over IPv4 and **does not forward
> IPv6 at all**. ADR-0163 chose this platform to obtain that forwarder, so the premise now holds
> for one address family out of two. The measurement and everything ruled out are below; the
> decision belongs to @greluc.

### §3.1 The source address — **the measurement passes on the chosen platform**

Two arms, thirteen seconds apart, same image, same host, same client, one variable:

|                   Arm                   | What the container logged as the client |
|-----------------------------------------|-----------------------------------------|
| default (`rootlessport`)                | **`10.89.2.2`** — the forwarder         |
| **`rootless_port_forwarder = "pasta"`** | **`10.1.0.30`** — the real client       |

A container on a **user-defined bridge** with a published port, probed from a LAN machine against
the VM's own address. So the setting does on this platform exactly what the Podman 6.0 release
notes describe, and the reason ADR-0163 was re-ruled onto CentOS Stream 10 holds up under
measurement rather than under citation.

That is the same experimental shape that produced the negative result on Debian 13 — same image,
same minute, same client, one variable — which is what makes the pair comparable.

> [!warning] What this settles, and what it does not
> It settles the **mechanism**: on this platform, with that setting, a bridge-networked container
> sees the client. It does not settle the IPv6 half, because the probe was IPv4, and it does not
> settle behaviour under production-shaped traffic — which is Phase 5 on the new host while it is
> idle, exactly as §3.1 split it. The option also remains **experimental upstream**, so the standing
> assertion stays the conformance suite's `client-address-visible` check, not this one measurement.
>
> The IPv6 arm has since been run, and it **fails** — see immediately below. That result, not
> this one, is what decides whether the plan can proceed as written.

### §3.1 The IPv6 arm — **it fails, and it blocks the plan as written**

The v4 arm above passes. The v6 arm does not, and the two were measured on the same container, in
the same minute, with the same publish specification, from the same external client:

| Family |        Result        |         What the container logged         |
|--------|----------------------|-------------------------------------------|
| IPv4   | HTTP 200             | **`10.1.0.30`** — the real client address |
| IPv6   | **connection reset** | **nothing at all**                        |

Not a wrong address. Nothing arrives. The socket is bound — both `0.0.0.0:18080` and `[::]:18080`
are listening — and inbound IPv6 connections are reset.

**The control arm serves both families.** `rootlessport` answered the same v6 request with HTTP 200
and logged it as `10.89.0.2` — an **IPv4** bridge address. So the default forwarder does not merely
lose the client's address over IPv6, it collapses the request into a different address family
before the container ever sees it. That is worse than §3.1 assumed, and it is the other half of why
neither option is currently acceptable.

#### What was ruled out, so nobody re-runs it

- **The hypervisor firewall.** The reset happens from `::1` on the VM itself.
- **The topology.** netavark installs correct DNAT rules for **both** families; the v6 rule was read
  out of the rootless netns and reads `dnat ip6 to [fd2f:…::2]:8080`.
- **SELinux.** Zero AVC denials, and the failure is identical with SELinux permissive. The upstream
  PR shipped alongside a policy fix, which made this the first thing to check. It is not that.
- **IPv6 forwarding.** The rootless netns already has `forwarding=1`; setting the host's to 1 changes
  nothing. Reverted.
- **Stale packages.** podman 6.1.0, passt `0^20260728`, netavark 2.1.0, and `dnf check-update`
  reports nothing newer. This is the distribution's current best.

#### Why it fails — traced, 2026-09-16

The first write-up of this finding implied the feature simply had no IPv6 path. **That was wrong,
and the correction matters**, because it changes what kind of problem this is. Podman's own release
notes for **6.1.0 — the version on this host** — say:

> The Pesto rootless port forwarding tool now supports IPv6 port forwarding with source IP
> preservation.

So IPv6 is a **documented, shipped feature of exactly this version**, and it does not work. That is a
defect against a promise, not a gap waiting to be filled.

The cause is an addressing asymmetry inside the rootless network namespace. `pasta --config-net`
copies the host's **IPv4** address and route into the namespace, but Podman starts it with
`--address fc00::3 --gateway fc00::1`, which overrides the IPv6 template with a private ULA:

|      |                               in the rootless netns                                |
|------|------------------------------------------------------------------------------------|
| IPv4 | `inet 10.9.0.14/24 … eth0` — **the host's real address**, copied from the template |
| IPv6 | `inet6 fc00::3/64` — a private ULA; the host's `2003:…` address is **nowhere**     |

netavark enters its host-port DNAT chain through **`fib daddr type local`**. A v4 packet addressed
to `10.9.0.14` is local in the namespace, so it reaches DNAT and the container. A v6 packet
addressed to the host's global address is **not local there**, never reaches the chain, and is
reset — even though the correct v6 DNAT rule exists and was read out of the namespace.

The PR that added the option states the requirement in as many words: netavark's rules inside the
rootless netns "must not restrict on destination address". On this host they do.

**Tested directly.** Adding the host's global v6 to the namespace's `eth0` changed the failure from
**reset to timeout** — the packet stops being rejected outright, which is what the explanation
predicts for the first hop. It did not become a 200, and the remaining leg was not chased further:
a probe from the host to the host's own address is not a clean rig for the return path, and the
external client needed for a clean one was not available a second time.

> [!note] There is no configuration that fixes this from the outside
> `containers.conf` offers `pasta_options`, and it does reach the right process — passing
> `--address <host v6>` appears on pasta's command line. Podman's own `--address fc00::3` still
> wins, and the namespace still receives only the ULA. Verified, not assumed.

#### No newer version fixes it either

Checked against the actual package archives rather than from impression:

|                                  |                   podman                    | netavark  |      passt       |
|----------------------------------|---------------------------------------------|-----------|------------------|
| **this host** (CentOS Stream 10) | **6.1.0**                                   | **2.1.0** | **`0^20260728`** |
| Fedora 45 / rawhide              | 6.1.1                                       | 2.1.0     | `0^20260611`     |
| Fedora 44 / 43                   | 5.8.4 — no `rootless_port_forwarder` at all | 1.17.2    | —                |

The only upgrade available anywhere is podman 6.1.1, and **its port-forwarding fix is for
`rootlessport` on WSL**, not for pesto on Linux. 6.1.2 carries no bugfixes at all. netavark 2.1.0 is
the newest build in any branch and is already installed, and this host's passt is *newer* than
Fedora's.

So there is nothing to upgrade to. The nearest neighbouring report — podman issue #29772, "v4-only
`-a` silently disables guest IPv6", on Fedora 44 with **the same passt build** — was closed as *not
planned*.

> [!danger] The choice, as it stands, is between two unacceptable options
> **pasta** gives correct client addresses and **no IPv6 service**. **rootlessport** gives IPv6 and
> **no client addresses** — on IPv6 it does not even preserve the address family. The edge serves
> dual-stack, three application networks and `net-blackbox-v6` carry IPv6, and `ipv6-reachable` is
> one of the twelve assertions the Phase 0 conformance suite makes. Either option fails a check the
> suite already makes today.
>
> **This is a decision for @greluc, not a problem to engineer around quietly.** ADR-0163 chose this
> platform specifically to obtain the pasta forwarder; that premise now holds only for IPv4 — and
> it holds only for IPv4 because of a **defect**, not because the feature was never meant to do it.

#### The options — two are closed, two are live

**Closed: re-open the platform question.** The defect is in the combination of Podman 6.1.x and
netavark 2.1.0, not in the distribution. Fedora 45 carries the **same netavark 2.1.0**, and its
Podman 6.1.1 fixes `rootlessport` on WSL rather than pesto on Linux. No distribution escapes this.

**Closed: split the edge out of the bridge topology.** Measured 2026-09-16 and dead — pasta as the
network mode does not deliver IPv6 on Podman 6.1.0 either.

> [!note] One honest distinction that an earlier revision blurred
> Host networking and pasta-as-network-mode were listed as one option and then declared dead on a
> pasta measurement. They are different mechanisms. **Host networking was never measured**; it dies
> on a topology argument — the edge would sit in the host namespace while every backend sits in the
> rootless one, and it would reach none of them without publishing every backend port on the host,
> which is what the five internal networks exist to prevent. That is a strong argument. It is still
> an argument and not a measurement, and it is labelled as one.

That leaves **A** (file the defect upstream), which is worth doing whichever path is chosen but is
no plan on its own, and the two that actually decide the migration. Both **B and C abandon the pasta
forwarder**; they differ in whether the client address is recovered by another route or lived
without.

#### B — ship on `rootlessport` and rebuild the two controls

|                                                                           For                                                                           |                                      Against                                      |
|---------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| No new moving part; the default forwarder is the best-tested path Podman has                                                                            | **The rate limiter cannot be replaced equivalently** — see below                  |
| Immediately actionable, nothing to measure                                                                                                              | The obvious "fix" to the admin ACL opens the console to the internet              |
| Independent of an experimental feature that just failed its own promise                                                                                 | The client address is lost on IPv4 **and** IPv6 — on IPv6 even the address family |
| The admin ACL fails **closed**, not open                                                                                                                | The access log stops being usable as a forensic record                            |
| The admin ACL can be replaced by something **better**: stop exposing `/auth/admin` publicly and reach it through an SSH forward, which prod already has | §3.2 stays open — the edge must still bind :80/:443 itself                        |

**The rate limiter is the whole cost, and it is permanent.** There is no usable substitute for a
client address at the edge. A session cookie only keys authenticated routes, and the login endpoint
that most needs limiting is unauthenticated; `X-Forwarded-For` does not exist because nothing sits
in front, and would be forgeable if it did. REQ-SEC-023 / ADR-0112 therefore becomes **one bucket
for the entire internet**, by construction rather than by accident — which is the permanent form of
an outage this deployment has already had once.

#### C — an L4 front end on the host speaking PROXY protocol

|                                                                                      For                                                                                       |                                                                 Against                                                                  |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **Every control keeps working unchanged** — rate limiter, admin ACL, API allow-list, access log                                                                                | A new host service to package, configure, monitor and patch, and to carry in the promotable config artifact (`REQ-OPS-004`)              |
| Both address families, with no special case                                                                                                                                    | It sits **in front of everything** and is a single point of failure outside the Quadlet lifecycle                                        |
| The edge stays in the bridge topology; the nineteen segments are untouched                                                                                                     | **A forgeable PROXY header** if the container's port is reachable directly — see below                                                   |
| Entirely independent of the pasta defect; it does not matter whether upstream ever fixes it                                                                                    | An nginx listener with `proxy_protocol` rejects header-less connections, so a misconfiguration breaks everything (fail-closed, at least) |
| PROXY protocol is old, dull and deployed everywhere — the opposite of the experimental path that led here                                                                      | The migration wanted fewer moving parts, and this adds one                                                                               |
| **It closes §3.2 in passing**: the host service binds the privileged ports, the container publishes on a high one — no `ip_unprivileged_port_start`, no `CAP_NET_BIND_SERVICE` | **Unmeasured**                                                                                                                           |
| TLS stays at the edge; the front end is pure TCP pass-through, so §3.4's certificate handover is untouched and no key exists in a second place                                 |                                                                                                                                          |

**The forgeable header is the serious objection.** `set_real_ip_from` must trust the front end and
nothing else. If the container's published port is reachable directly, a client can bypass the front
end and invent the header — defeating exactly the admin ACL and rate limiter that C exists to
preserve. It closes with one property, publishing the container on loopback only, and that property
is assertable by the conformance suite rather than left to memory.

#### Weighing them

The two costs are not the same kind of thing, and that is what decides it.

**B costs security, permanently.** The rate limiter afterwards is not a rate limiter; it is a switch
with which a single attacker locks out everyone else. That is not residual risk, it is a regression
behind what runs today, and no amount of care shrinks it — the information the control rests on
simply stops arriving.

**C costs operational complexity.** That is real, but it is bounded, inspectable, and the same kind
of cost this migration has already accepted deliberately for node-exporter and alloy, for the same
reason: something that does not work inside a container works on the host.

C also settles two things at once. §3.2 has been open since the beginning and stays open under B;
under C it disappears, because a host service may bind privileged ports anyway. An option that
closes a standing question alongside its own is worth more than the list suggests.

> [!important] Decided 2026-09-16 by @greluc: **C**, recorded as [ADR-0187](adr/0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md)
> B stays the written-down fallback if C proves unworkable in Phase 2.
>
> Both of the questions that were open when the recommendation was made have since been answered.
> HTTP/3 is **not** in play — nothing in `docker/edge/` mentions QUIC or h3, so a TCP front end
> passes nothing by. And a rootless container **can** publish on loopback only, on both families,
> which is what the forgery argument rests on. The full chain was then measured end to end.

A middle path — keep pasta for IPv4, where it does preserve the address, and front only IPv6 — is
rejected: two paths with different trust configuration, and one of them stays the experimental one.

#### C, measured end to end — 2026-09-16

On the CentOS Stream 10 VM with Podman 6.1.0, haproxy 3.0.5 in `mode tcp` on `:80`, the container
published on loopback only, `set_real_ip_from` plus `real_ip_header proxy_protocol` in nginx:

|                                            |                               Result                                |
|--------------------------------------------|---------------------------------------------------------------------|
| loopback-only publish, both families       | `127.0.0.1:18080` and `[::1]:18080` bound — not `0.0.0.0` or `[::]` |
| the port, from the host's global v4 and v6 | **refused** on both                                                 |
| a connection carrying no PROXY header      | **rejected** — *broken header while reading PROXY protocol*         |
| the full chain, IPv4                       | `remote=::ffff:10.9.0.14`, equal to `$proxy_protocol_addr`          |
| the full chain, IPv6                       | `remote=2003:…:fe85:2fe9`, equal to `$proxy_protocol_addr`          |

Three things came out of it that reading would not have produced:

1. **`bind :::80 v4v6` is a trap.** IPv4 clients then arrive as `::ffff:10.9.0.14`, and every
   CIDR-based `allow` rule behind it silently stops matching. The connection works; only the
   address has the wrong shape. haproxy binds the two families **separately**.
2. **SELinux needs one narrow grant, and only one.** Confined haproxy may not connect to an
   unlabelled port; `semanage port -a -t http_port_t -p tcp <port>` is enough. The broad
   `haproxy_connect_any` boolean was tried afterwards, was **not needed**, and stays off.
3. **The first bind attempt failed with `Permission denied` on a port above 1024** — which is
   SELinux confinement doing its job, not a privilege problem. Worth recording because it looks
   like the latter and would send the next person to `ip_unprivileged_port_start`.

> [!warning] Two properties are still unmeasured, and neither may be guessed at build time
> The probe trusted a **subnet** in `set_real_ip_from`, which is fine for a probe and not fine for
> production: the exact address the edge sees behind `rootlessport` on a one-member
> `net-edge-ingress` has to be measured and pinned as a single address. And unreachability was
> established only from the host's own addresses — the stronger test, a direct connection **from a
> different machine**, is still owed. Both are on the Rocky list below.
>
> [!warning] What the neighbouring deployment's measurement does and does not establish
> The PVE operator reached their own rootless Caddy over IPv6 from an external client and got a
> 200, on **Podman 5.4.2** with **pasta as the network mode**. That is worth having: it refutes
> "pasta cannot do IPv6" and narrows this finding to the Podman 6 forwarder option on bridge
> networks. It establishes **delivery, on 5.4.2, externally measured** — and nothing more.
>
> It does **not** establish what source address that Caddy saw. They said so themselves and
> declined to find out, because reading it would have meant writing an access log into a live
> proxy configuration to answer a question with no consequence on their side: none of their
> hostnames carries an AAAA record, so nothing reaches them over IPv6 unless it is forced to.
> Their result must not be carried further than that, and the version gap from 5.4.2 to 6.1.0
> stays open. It shrinks the uncertainty; it does not remove it.
>
  #### Option 4, measured — pasta as the **network mode**, Podman 6.1.0

Both of option 4's questions in one probe, external client, same port, no proxy, `$remote_addr`
read from the container's own access log:

| Family |        Result        |         What the container logged         |
|--------|----------------------|-------------------------------------------|
| IPv4   | HTTP 200             | **`10.1.0.30`** — the real client address |
| IPv6   | **connection reset** | **nothing at all**                        |

So the earlier network-mode arm, filed as indicative because it was probed from the host, is now
**confirmed by a proper external measurement**. And the second question answers itself: the source
address survives on IPv4, and on IPv6 there is no connection for it to survive on.

**On this platform — CentOS Stream 10, Podman 6.1.0, passt `0^20260728`, netavark 2.1.0, kernel
6.12.0-267 — pasta does not deliver inbound IPv6 in either mode.** Not as the forwarder on a
bridge network, not as the container's network. `rootlessport` serves both families over the same
path.

> [!warning] What this is not evidence for
> It is **not** evidence that Podman 6 broke something Podman 5 could do. The neighbouring
> deployment that delivers IPv6 through pasta differs in four variables at once — Podman 5.4.2
> against 6.1.0, Debian against CentOS, two passt versions, two kernels. The two results do not
> contradict each other; they are two points with four differences between them. The narrow claim
> is the one that matters here: on the platform ADR-0163 chose, pasta is IPv6-dead, and option 4
> falls whatever the cause turns out to be.
>
> [!note] How the measurement came to be possible, and a test defect that is not a finding
> The probe needed a temporary firewall rule on the hypervisor. The PVE session's own guard
> refused to write it, classifying an externally reachable port as a weakening of security, and
> **that refusal was respected on both sides** — not routed around from here by lowering
> `ip_unprivileged_port_start` to reuse a port that was already open, which would have reached the
> same outcome without approval and put a second variable into a measurement built to isolate one.
> Both sessions asked @greluc independently; he approved; the rule was opened and then removed.
>
> Separately: an attempt to check whether IPv6 is broken **outbound** as well reported v4 and v6
> both blocked, which is a broken test rather than a result — the image's busybox `wget` does not
> take `-4`/`-6`. It is recorded as a defect and used as evidence for nothing.
>
> [!note] One honest limitation on the evidence
> pasta was also tried as a **network mode** rather than a forwarder, and IPv6 failed there too —
> but that arm is **confounded** and is recorded as indicative only: in that mode the container
> shares the host's address space, and the probe came from the host itself, so it is not the same
> shape as an external client. The forwarder finding above carries the weight: an external client,
> two families, one variable, reproduced twice.

### §3.3 The two network semantics — **both answered, and both matter**

**`-o no_default_route=true` is the masquerade equivalent.** The hypothesis §3.3 refused to assume
is confirmed:

|            Arm             |   Egress    |
|----------------------------|-------------|
| a plain network            | reachable   |
| `-o no_default_route=true` | **blocked** |

And on the second arm the container's routing table carries a link-scope route and **no default
route at all**. The property `com.docker.network.bridge.enable_ip_masquerade=false` was buying —
ingress without egress — is therefore reproducible on netavark, by a documented driver option.

**`--internal` removes inbound DNAT, exactly as Docker's `internal: true` did.** A container on an
internal network with a published port is not reachable from outside: the probe times out and the
container logs no request at all.

So **`net-edge-ingress` stays necessary.** The one-member, non-internal bridge that the published
ports land on is not a Docker artefact to be tidied away during the translation; netavark needs it
for the same reason Docker did. That is a finding worth having before the topology was simplified
on the assumption that it was legacy.

### §3.4 The certificate handover — **works, and the host uid was not what memory would have said**

`acme` simulated with `--cap-drop ALL --cap-add CHOWN`: wrote the key, `chmod` **before** `chown`
(CAP_FOWNER is dropped, so root may not chmod a file it does not own), chowned to `101:101`, renamed
atomically. `edge` simulated as `--user 101:101` **opened it**. SELinux enforcing throughout, on a
Podman named volume — which is what production uses for `edge-certs`. All three conditions §3.4
listed hold, and `REQ-OPS-026` is satisfiable on this platform.

> [!tip] The host uid was 524388, and that is the argument for deriving it rather than writing it
> Inside the namespace the file is `101:101`; on the host it is **524388**, because this host's
> subuid base is **524288** — not the 100000 the Debian testing guest uses. A bootstrap that
> hard-coded `chown 100100` would have been silently wrong here, and wrong in the direction that
> produces a file nobody can read at renewal time. The Ansible role derives the host uid from the
> same variable that grants the range, and this is the measurement that says why that was worth
> doing.

### §3.5 cgroup delegation — narrower here, and still sufficient

The user slice on this host delegates `cpu memory pids` — noticeably less than the Debian guest's
`cpuset cpu io memory hugetlb pids rdma misc`. It is **enough**: `Memory=`, `PidsLimit=` and
`--cpus` each have their controller, and nothing in this stack uses `io`. Worth recording because
§3.5's "already delegates everything" was measured on the platform that is no longer the target.

### Three things the units did that nobody asked for

Enabling lingering started the user manager, which read the unit files already lying there and
**tried to start the whole stack** — because the generated units carry `WantedBy=default.target`,
faithfully translating compose's `restart: unless-stopped`. Fourteen services failed on absent
images and configuration. The accident was worth more than a tidy run would have been.

1. **The `.network` units materialise the real topology.** Not in a dry run — the networks were
   created, and `net-edge-ingress` came up dual-stack with `172.28.15.0/24` and `fd00:28:15::/64`,
   `net-proxy-api` with `internal=true` and its pinned pair. The nineteen-segment model reproduces
   on netavark with its addresses intact.
2. **The dependency graph held.** `backend`, `frontend`, `ingest` and `keycloak` never started at
   all — `inactive`, zero restart attempts — because `Requires=` on their failed databases held them
   back. That is the `depends_on: condition: service_healthy` behaviour surviving the translation.
3. **`Restart=always` needs tuning, and that is a real Phase 2 item.** `edge` burned five restarts
   in seconds and then stopped for good with *"Start request repeated too quickly"* — systemd's
   default start limiter. Compose's `unless-stopped` backs off instead and keeps trying. Without an
   explicit `RestartSec=` and a widened `StartLimitBurst` / `StartLimitIntervalSec`, a transient
   failure at boot — a database slow to come up, a registry briefly unreachable — leaves a unit
   permanently down rather than retrying. It would look exactly like a broken deploy and would not
   be one.

> [!important] Bootstrap order now matters in a way it did not under Compose
> Under Compose nothing starts until `docker compose up` runs. Under Quadlet the units are *enabled*
> by their `[Install]` section, so the stack starts the moment the user manager does — which is when
> lingering is switched on. `loginctl enable-linger` therefore belongs **after** the images, the
> environment files and the configuration are in place, never before. It is one line in the
> bootstrap, and it is the difference between a clean first start and eighteen failing units.

### What Phase 1 leaves open

- **the IPv6 decision** — no longer a measurement. The arm was run and it failed; what remains is
  a choice between the four options in this section, and it is @greluc's;
- §3.2, binding `:80`/`:443` rootless, which is a host-configuration question and not an experiment;
- everything in Phase 2 — read-only with real mounts, the capability reduction for the databases,
  and the restart tuning that finding 3 above just added to it.

## 14. The platform moves to Rocky Linux 10 — ruled 2026-09-16

[ADR-0187](adr/0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md) removed
the only reason this migration was on a development stream. ADR-0163's choice 1 went from Debian 13
to CentOS Stream 10 because **only Podman 6 had `rootless_port_forwarder="pasta"`**, and only that
preserved the client's source address for a bridge-networked edge. The PROXY-protocol front end does
not need Podman 6, does not need pasta, and works on any of the three candidates.

With the reason gone, the trade reverses:

|                                  |                     CentOS Stream 10                     |            **Rocky Linux 10**             |                      AlmaLinux 10                      |
|----------------------------------|----------------------------------------------------------|-------------------------------------------|--------------------------------------------------------|
| supported until                  | 2030-05-31 (~5 years)                                    | **2035-05 (10 years)**                    | 2035-05 (10 years)                                     |
| relative to RHEL                 | **upstream** — changes arrive before RHEL validates them | downstream rebuild of released RHEL       | downstream, **ABI-compatible** rather than bug-for-bug |
| Podman                           | 6.1.0                                                    | 5.6.0 on 10.1, rebased over the lifecycle | comparable                                             |
| SELinux, container-selinux, SCAP | yes                                                      | yes                                       | yes                                                    |
| on Hetzner                       | yes                                                      | **yes**, rapid-deploy image               | yes, rapid-deploy image                                |

A hardened production host should take changes **after** RHEL has validated them, not before. That
is the whole argument, and it only became available once Podman 6 stopped being mandatory.

AlmaLinux was weighed as equally viable and set aside on a preference rather than a defect: it is
ABI-compatible rather than bug-for-bug, ships its own security backports — sometimes faster than
RHEL — and the hardening content this deployment is measured against is written for RHEL. Rocky's
1:1 rebuild keeps that mapping exact. Either would have been defensible.

### What this costs, and it is not nothing

**Every Phase 1 measurement was taken on Podman 6.1.0 with netavark 2.1.0.** Rocky 10 carries an
older netavark, so the results do not transfer by assertion. Re-measured on the new host before
anything is promoted:

1. `no_default_route=true` as an egress block — the replacement for the masquerade switch (§3.3)
2. `--internal` against inbound DNAT — decides whether `net-edge-ingress` stays necessary (§3.3)
3. the certificate handover under userns with SELinux enforcing, and **the subuid base**, which
   differed between the two platforms already and is the reason the Ansible role derives it (§3.4)
4. cgroup delegation in the user slice (§3.5)
5. ADR-0186's full chain, plus the two properties it left open — the exact `set_real_ip_from`
   address, and unreachability of the edge port **from a different machine**

Quadlet's `Memory=` needs Podman 6, so on 5.6 the generator emits `PodmanArgs=--memory=` instead.
That path was already written for Debian 13 and is kept rather than deleted, which is now the second
time it has been useful.

> [!note] What does *not* need re-measuring
> The pasta findings in §13 are not re-run. They rejected an option that is no longer taken, and
> repeating them on Rocky would establish nothing that changes a decision. They stay recorded because
> they are why ADR-0186 exists.

## 15. Re-measured on Rocky Linux 10.2 — 2026-09-16

Everything in §13 was measured on Podman 6.1.0 with netavark 2.1.0. The target platform is a major
version back on both — **Podman 5.8.2, netavark 1.17.2, passt `0^20251210`, crun 1.27, systemd
257**, on kernel 6.12.0-211. So the §13 results do not transfer by assertion, and the first question
for each was not *does it behave the same* but *is it there at all*.

### What transfers unchanged

|                                         |                               Rocky 10.2                                |                  same as Stream 10?                  |
|-----------------------------------------|-------------------------------------------------------------------------|------------------------------------------------------|
| SELinux                                 | **Enforcing**, `container-selinux` present                              | yes                                                  |
| subuid base                             | **524288**, so container uid 101 is **524388** on the host              | yes — the RHEL-family convention, not a CentOS quirk |
| cgroup delegation                       | `cpu memory pids`                                                       | yes                                                  |
| `no_default_route=true`                 | **blocks egress**; the container sees a link-scope route and no default | yes                                                  |
| the certificate handover                | works: `CAP_CHOWN` alone, chmod before chown, edge as uid 101 opens it  | yes                                                  |
| `RestartSteps=` / `RestartMaxDelaySec=` | present (systemd 257)                                                   | yes                                                  |

The subuid result is the third host to confirm it and the second to produce **524388**, which is the
argument for the Ansible role deriving every host-side uid instead of writing one down.

### What is better than expected

**Quadlet 5.8.2 has `Memory=`.** Podman 5.4.2 did not, and the generator carries a
`PodmanArgs=--memory=` fallback written for Debian 13. It is not needed here: `Memory=`,
`PidsLimit=`, `ReadOnly=`, `DropCapability=`, `NoNewPrivileges=`, `AutoUpdate=` and `Notify=healthy`
all exist. The fallback stays in the generator anyway, because it costs nothing and has now been
relevant twice.

**Every netavark option the plan depends on exists** in 1.17.2 — `no_default_route`, `--internal`,
`isolate`, `metric`, `mode`. The concern that a major version back might simply lack them is closed.

### What **reverses** a §13 finding

> [!warning] `--internal` does **not** remove inbound DNAT on netavark 1.17.2
> A container on an `--internal` network with a published port answered **HTTP 200** to an external
> client, and logged the request. On netavark 2.1.0 the same probe — same rig, external client —
> timed out and the container logged nothing.
>
> So §13's conclusion that *"`net-edge-ingress` stays necessary"* was a statement about **netavark
> 2.1.0**, not about netavark. It does not hold on the target platform.

That cuts both ways and the second way matters more. It means `--internal` **cannot be relied on for
ingress isolation**: a network marked internal will still accept published traffic here. The
five `net-proxy-*` networks publish nothing, so nothing is exposed today — but the property the
topology leans on is version-dependent, and a future netavark could flip it back.

**`net-edge-ingress` is therefore kept**, and the reason changes. It is no longer *"netavark forces
it"* but *"the topology should not depend on a behaviour that changed between two adjacent major
versions"*. Publishing nothing on the internal networks is the invariant; `--internal` is a
belt-and-braces measure on top of it, not the thing being trusted.

### ADR-0187's chain, on the platform that will run it

Validated end to end from a **real external client** on both families, with haproxy on the host and
the container published on loopback only:

|                        Probe                         |              What the edge logged              |
|------------------------------------------------------|------------------------------------------------|
| IPv4 from the workstation, over the management VPN   | `remote=10.1.0.30`                             |
| IPv6 from the workstation, over the public path      | `remote=2003:c5:5f03:e501:e891:1846:54c8:c5b6` |
| the edge's own port, directly, from that workstation | **not reachable**                              |

The IPv4 value is the visible improvement over the Stream run, where it arrived as
`::ffff:10.9.0.15`. Separate `bind 0.0.0.0:80` and `bind [::]:80 v6only` produce a plain IPv4
address, so CIDR-based `allow` rules match again. The unreachability check is the stronger one this
time — a different machine, not the host probing itself.

> [!important] The exact `set_real_ip_from` value cannot be a fixed address unless the container's is
> Measured: the peer the edge sees is the **container's own address**, and it moved from `10.89.0.2`
> to `10.89.0.3` across a recreation. ADR-0187 asks for a single address rather than a subnet, and
> that is only achievable with `IP=` pinned in the edge's Quadlet unit. The ADR now says so.

Then measured against the **real** `net-edge-ingress` subnet rather than a scratch one:

|                                      |    Container address     |      Peer the edge saw       |
|--------------------------------------|--------------------------|------------------------------|
| `IP=172.28.15.10`, three recreations | `172.28.15.10` each time | **`172.28.15.10` each time** |
| no pin, two recreations              | `172.28.15.2`, then `.3` | `172.28.15.2`, then `.3`     |

And the pin **presupposes a user-defined bridge network** — podman refuses it on the default one:
*"static ip/mac address can only be used with Bridge mode networking"*. So the pin and the ingress
network's isolation properties are decisions about the **same** network, and have to be taken
together rather than in sequence. That is the second reason `net-edge-ingress` is kept.

### A methodological finding, which cost an hour

On a host **without lingering**, a detached rootless container does not survive the SSH session that
started it: the user's systemd instance exists only while a session does, and it takes the
containers with it. A measurement split across two `ssh` invocations therefore silently loses its
subject, and the symptom — haproxy healthy, backend gone, empty reply — reads like a chain defect.
Lingering was enabled (safe here precisely because no unit files exist yet, per §13) and the chain
was then run inside a single invocation.

## 16. The Ansible role, run for real — 2026-09-16

[ADR-0186](adr/0186-the-host-bootstrap-is-an-ansible-role.md) exists because a prose checklist
executed twice is two procedures that resemble each other. The role that replaced it had itself
never been executed — only linted — and running it against Rocky 10.2 found **nine defects**, one of
which would have stopped it at the first task.

The control node was the host itself (`ansible_connection: local`). That avoids putting a copy of a
private key anywhere and exercises every task; what it does **not** exercise is the SSH transport,
which is a difference worth naming rather than glossing.

### What only running it could find

| # |                             Defect                              |                                                              Why it survived review                                                              |
|---|-----------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Preflight demanded podman **≥ 6.0** and `/usr/bin/pesto`        | Written while the pasta forwarder was the mechanism. ADR-0187 replaced it, and the assertion would have **refused the chosen platform outright** |
| 2 | `containers.conf` still set `rootless_port_forwarder = "pasta"` | The one setting the IPv6 finding says must **not** be applied                                                                                    |
| 3 | Eight read-only tasks lacked `check_mode: false`                | Ansible skips `command` in `--check`, so every assertion downstream compared against an empty string. **`--check` could never have worked**      |
| 4 | `stdout_callback = yaml`                                        | Resolves to `community.general.yaml`, **removed** in community.general 12 — it aborts the run before the first task rather than degrading        |
| 5 | No upper bound on `community.general`                           | 12.0 requires ansible-core ≥ 2.17; the platform ships 2.16.16                                                                                    |
| 6 | `regex_search` with a capture group                             | Raised `'NoneType' object has no attribute 'group'`, which names neither the pattern nor the input                                               |
| 7 | Three tasks read state that does not exist in `--check`         | A uid of an uncreated user, a `become` onto it, and `acl` not yet installed                                                                      |
| 8 | The SCAP datastream was pinned to `ssg-cs10-ds.xml`             | CentOS's. Rocky ships `ssg-rl10-ds.xml` and `ssg-rhel10-ds.xml` and **neither of that name**                                                     |
| 9 | `ansible.builtin.user` with `group:` does not create the group  | **Only the real run found this.** In `--check` the module never reaches the system call that would say *Group iri does not exist*                |

Defects 1, 2 and 8 are the same failure in three places: the role was written for a platform and a
mechanism that two decisions on the same day replaced. They are the cost of deciding quickly, and
they were all caught before a host depended on them.

Defect 3 is the more interesting one. A dry run that cannot work is worse than none, because its
green result is read as evidence. This one reported six tasks `ok` while comparing assertions
against empty strings.

### What the run produced

```
PLAY RECAP
testing : ok=46  changed=18  unreachable=0  failed=0  skipped=5
```

The datastream selection picked `ssg-rl10-ds.xml` on its own, and the scan reported
`cis_server_l1: 166 passed, 124 failed` — the expected shape for a host that has not been remediated,
since `basetool_hardening_remediate` defaults to **false**. The role scans and reports; it does not
rewrite `sshd` behind the operator's back.

haproxy is installed, configured and **enabled but not started**, which is deliberate: the role
provisions a host that is not serving traffic yet, and a front end with no backend answers every
request with a connection error for as long as the gap lasts.

### The second pass, which is the actual point of the role

```
PLAY RECAP
testing : ok=45  changed=0  unreachable=0  failed=0  skipped=5
```

**Zero.** Not one task reports a change on a re-run — stricter than expected, since `restorecon` and
`semanage` tasks commonly report `changed` on every pass for want of a clean idempotence check.
That is the property ADR-0186 was written to obtain: the testing host and the production host are
built by the same procedure, and "the same procedure" only means anything if running it twice is a
no-op.

### Verified on the host afterwards, over a new connection

A **new** SSH connection rather than the one that was already open — an existing session survives an
`sshd` change that already blocks new logins, which is the same trap as measuring a container whose
session has ended.

|                   |                                                                               |
|-------------------|-------------------------------------------------------------------------------|
| access            | `sshd active`, login works                                                    |
| service user      | `iri:992:992`, `/sbin/nologin`, lingering **yes**                             |
| subordinate range | `iri:100000:65536`, uid and gid                                               |
| `/var/iri`        | `iri:iri 755`, context `container_file_t`                                     |
| front end         | `inactive / enabled`, config valid, separate v4 and v6 binds, `send-proxy-v2` |
| port label        | `http_port_t` now carries 8080 and 8443                                       |
| `containers.conf` | present, **0 setting lines**                                                  |

**The derived-uid arithmetic is visible in the result**, which is the part worth looking at twice:
`/var/iri/redis` came out `100998:100998` and `/var/iri/db-backend` `100069:100069` — base 100000
plus the container uids 999 and 70. Not one of those numbers is written down anywhere in the role.

> [!note] Two things that looked like findings and were not
> The hardening report directory appeared empty. It is `root:root 750`, so listing it as the
> unprivileged operator fails — the reports are there, 3.5 MB of HTML and 30 MB of ARF, and a
> directory that hides which controls a host fails is the right shape for that content.
>
> And one failure looked like a role defect: re-extracting the tree removed the installed collection
> with it, and the next run died on *couldn't resolve community.general.sefcontext*. The run script
> installs the collection every time now. Worth recording because the message points squarely at the
> role, and the cause was one directory above it.

## 17. The SCAP remediation, applied for the first time — 2026-09-16

`basetool_hardening_remediate` had been `false` since the role was written, and the block behind it
had never run. Turning it on against Rocky 10.2 changed **131 files under `/etc`** — `sshd_config`,
the whole PAM/authselect stack, `auditd`, file modes — and produced three findings, two of which are
about the *procedure* rather than about the host.

### The remediation reported that it had changed nothing

```
166 rules passed before, 166 after (0 newly passing).
```

That was written hours earlier, in the task whose entire purpose was to replace a `changed_when: true`
that always lied. It lies differently: **`oscap --remediate` prints the evaluation it made BEFORE
applying anything**, so comparing its stdout against the pre-scan compares one state with itself.

The honest signal needs a **third** evaluation. The role now re-scans after remediating and compares
that, which costs a couple of minutes and is the only thing that distinguishes *"the host was already
compliant"* from *"the remediation did nothing"* — two situations that look identical and are not.

> [!warning] This is the fourth variant of the same mistake in one day
> A filter that matched its own documentation, a version grep that found a number in a title, a
> `--check` run that compared against empty strings, and now a comparison of a state with itself.
> Each returned a plausible value **in the right shape**. The question that catches them is asked
> before the first hypothesis, not after the second: *what would this check look like if it were not
> working?* If the answer is "the same", it is not a check yet.

### What it actually achieved

Measured by the third evaluation, which is the whole point of adding it:

|         | before  |  after  |
|---------|---------|---------|
| passing | **166** | **281** |
| failing | **124** | **12**  |

So the remediation worked substantially, and the task that reported *"0 newly passing"* was wrong
about its own run rather than describing a host that needed nothing.

> [!warning] Two more of the same family, both in the checking rather than the thing checked
> Reading those numbers back took two attempts, and both failures are worth writing down because
> they are the cheapest kind to repeat.
>
> `grep -oE 'Result +[a-z]+'` found **nothing** in a 1299-line result log. `oscap` separates the
> word from the value with a **tab**, and the pattern demanded a space, so an empty result read as
> though the scan had produced nothing at all. The role's own expression uses `\s` and was never
> affected — it was the ad-hoc check that lied.
>
> And `pgrep -f 'oscap xccdf eval'`, run inline over ssh, reported the scan as still running for
> more than ten minutes after it had finished: **the pattern matched the checking command's own
> command line**. The report files were timestamped a minute after the scan started. Running the
> same `pgrep` from a script file — whose command line is the script's name — answered correctly at
> once.

### `nohup` does not detach far enough

The run was started with `nohup` so a dropped SSH connection could not abort it half-way — a
partially remediated host being worse than either end state. It survived. But `nohup` detaches from
the **process group**, not from the **cgroup**: the process stayed in `session-81.scope`, and
`systemd-logind` would have killed it on session close had `KillUserProcesses` been `yes` — which is
systemd's own default since v230, and `no` here only because the distribution package overrides it.

**It worked because of a distribution default, not because the construction guaranteed it.** The
right tool makes no such assumption:

```bash
systemd-run --unit=remediation --collect \
  --property=StandardOutput=file:/var/log/remediation.log \
  ansible-playbook -i inventory/hosts.yml site.yml
```

A transient unit outside any session, `systemctl status remediation` at any time, and `--collect`
cleans it up afterwards.

### What the host actually did, and the order that mattered

|                          |              |
|--------------------------|--------------|
| `sshd` restarted at      | **18:45:16** |
| `sshd_config` written at | **18:45:48** |

The service was restarted **before** its new configuration was written, so the running instance still
held the old one in memory. Restarting it blind would have been the first moment anyone learned what
the new configuration does — on a host where `root` and the service account are both password-locked,
the serial console accepts no login, and `guest-exec` is disabled. **The snapshot is not a safety
net there; it is the only way back.**

So the new configuration was authenticated against **for real**, on a spare port, while the working
instance kept running:

```bash
sudo /usr/sbin/sshd -D -p 2222 -f /etc/ssh/sshd_config &
ssh -p 2222 -i <key> sysadm@<host> true      # from the client that actually has to get in
```

It answered. `sshd -t` would not have: a syntactically perfect configuration can still refuse a key
through `PubkeyAuthentication`, `AuthorizedKeysFile`, `AllowGroups`, or a crypto policy that drops
its algorithm — and `usepam yes` with a freshly rebuilt authselect stack is exactly the case that
parses cleanly and rejects. Only then was `sshd` restarted, and only then was a new connection made.

### The two delayed effects, neither of which fired

Both are things that do nothing on the day they are applied, which is why the role now reads them
explicitly rather than trusting the report:

|                                  |    Measured    |                                                                       Why it is checked                                                                        |
|----------------------------------|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tmp.mount`                      | **`disabled`** | CIS wants `/tmp` as its own filesystem; the usual fix enables this unit, which does nothing until the next boot and then mounts `/tmp` `noexec`                |
| `auditd admin_space_left_action` | **`SUSPEND`**  | `halt` there is a mechanism by which the host switches itself off when the audit partition fills — in three weeks, when nobody is looking at the hardening run |

## 18. The reboot, and the firewall nobody asked for — 2026-09-16

The reboot was the second dangerous moment, because mount options and boot parameters only take
effect there. It was clean:

|                  |                                                                 |
|------------------|-----------------------------------------------------------------|
| boot             | 19:00:32 UTC, eight seconds after the trigger                   |
| failed units     | **0**                                                           |
| `sshd`, `auditd` | active                                                          |
| SELinux          | Enforcing                                                       |
| lingering        | still on                                                        |
| `tmp.mount`      | **still `disabled`** — the boot trap did not fire               |
| `/dev/shm`       | gained `nosuid,nodev,noexec` — a hardening that did take effect |

### And then: `:80` and `:443` timed out from another machine

`haproxy` was `active`, listening on both ports and both families. Locally everything looked right.
From a second machine, both ports timed out.

> [!warning] That probe did not prove what it was used for, and the real one is thirty seconds long
> A high-level HTTP client cannot tell a refused connection from one that was accepted and then
> produced nothing — it reports both the same way — and `haproxy` with no backend behind it is the
> second case. So the timeout was *consistent with* a block and could not establish one. Only
> firewalld's configuration could, and a configuration is an argument rather than a measurement.
>
> Settled by removing the rule again, from the **runtime only**, and probing the TCP handshake
> separately from HTTP:
>
> |               |     TCP handshake      |                            HTTP                            |
> |---------------|------------------------|------------------------------------------------------------|
> | with the rule | **connects**           | `curl-exit 52` — empty reply, i.e. haproxy with no backend |
> | without it    | **refused / filtered** | `curl-exit 7`                                              |
>
> So firewalld really does drop `:80` when nothing allows it, and the finding stands — now on a
> measurement rather than on an inference. The same probe showed `:8080`, the edge container's
> published port, **refused from outside**, which is ADR-0187's invariant holding.
>
> [!danger] The CIS remediation **installs firewalld**, and its default zone does not know about this
> stack
>
> ```
> firewalld  active / enabled,  default zone: public
> services   cockpit  dhcpv6-client  ssh
> ports      (none)
> ```
>
> This host had **no firewalld at all** earlier the same day — measured, while chasing an unrelated
> problem, and ruled out as a cause. The remediation brought it in as a dependency, enabled it, and
> left the `public` zone allowing three things, none of which is the application.
>
> After the reboot the edge would have been unreachable from the internet, **and every health check
> on the host would have stayed green** — because every health check on the host is on the host.

That is, word for word, the failure the role's own hardening section warns about: *an oscap report
will happily show a perfect score on a host whose edge is down.* The sentence was written before the
run and did not prevent it; only probing from a different machine did.

### The answer is not to avoid the firewall — it is to stop getting one by accident

The first reaction to this was to treat firewalld as damage. That framing is wrong, and @greluc said
so: **a default-deny packet filter in front of a host that publishes two ports to the internet is
straightforwardly right**, and getting one for free is a gain. The defect was never that a firewall
appeared. It was that it appeared *unannounced*, configured by a profile that knows nothing about
this stack, on a host whose own checks cannot see the difference.

So the role now owns it:

- **`firewalld` is installed explicitly** (`10-packages.yml`) rather than arriving as a dependency of
  a remediation that may or may not be enabled. It is present either way, and it is a decision.
- **`65-firewall.yml`** enables it, reads the default zone, and states the policy in its own output.
- **`70-frontend.yml` opens the front end's two ports**, beside the service that binds them —
  guarded on firewalld being active, idempotent via `--query-port`. A port list that lives away from
  the service it serves drifts from it.

The resulting policy, stated rather than inferred:

|                              |                                                                                                                                                                                   |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| open                         | `ssh`, and `:80` / `:443` for the front end                                                                                                                                       |
| **closed, and deliberately** | everything else the stack publishes — because it publishes on **loopback**. The edge binds `127.0.0.1` and `[::1]` only, which is exactly what makes its PROXY header unforgeable |

Two judgement calls are written down rather than left in the diff. **`cockpit` is removed from the
zone**: it is a root-privileged administration web UI, the default zone permits it whether or not it
is installed, and that is an open door waiting for someone to install the thing behind it.
**`dhcpv6-client` is left alone**: this host takes IPv6 by SLAAC so the rule is *probably*
unnecessary — and "probably" is doing the work in that sentence, while the failure mode of being
wrong is a host that silently loses its IPv6 address at the next lease event.

> [!note] And one more of the day's pattern, in the fix itself
> The first version of the reporting task used `lookup('pipe', 'firewall-cmd --get-default-zone')`.
> **`lookup` runs on the control node**, not on the target — and the control node is a WSL Ubuntu
> with no `firewall-cmd`. The run failed at the very last task, after correctly opening both ports.
> A lookup reads the machine you are sitting at; a `command` reads the machine you are configuring.

### Two attributions corrected before they were written down

**The kernel changed across the reboot** — `6.12.0-211.16.1` to `211.54.1` — and the remediation was
the obvious suspect. The dnf history says otherwise: `kernel-core 211.54.1` was installed at 17:08,
during the hypervisor operator's `dnf update` when building the VM, *before* the snapshot. The
machine simply had not rebooted since. Neither the remediation's doing nor a problem.

**The host answered five seconds after the reboot was triggered**, which looked far too fast to be a
real reboot. `uptime -s` said `19:00:32` and `uptime -p` said `up 0 minutes`: it really is that
quick. The suspicion was wrong, and checking was still right — that is only knowable afterwards.

## 19. One CIS rule that must never be satisfied — 2026-09-16

`cis_server_l1` requires every file to have an owner and a group that exist in `passwd`/`group`. **A
rootless-container host cannot satisfy that, by design**, and the rule will fail on every such host
forever.

Every uid inside a container maps to a **subuid** on the host, and subuids deliberately have no
`passwd` entry. That covers two kinds of file:

- the **image store** — `containers/storage/overlay/*/diff/etc/shadow` and its neighbours, owned by
  whatever uid the image built them as;
- the **data directories this role creates**, with exactly the namespace-translated ownership that
  ADR-0186 derives rather than writes down: `/var/iri/redis` at `100998`, `/var/iri/db-backend` at
  `100069`, the three application modules at `110000`.

Those are correct. They are also, to CIS, ownerless.

> [!danger] The failing rule is not the risk — the remediation for it is
> `chown -R` across those paths rewrites the image store's ownership, breaking every container built
> on those layers, **and** hands every database its data directory under the wrong uid. It is the
> kind of fix that looks like housekeeping and is not reversible by repeating it.
>
> So the role reports this as a **known exception** rather than leaving twelve unexplained failures
> for someone to tidy up later.

### Getting the predicate right took four attempts, and every one of them measured

The check that separates this exception from a real orphan was wrong three times, and each wrong
version produced a **plausible number** rather than an error. Recorded in full, because the shape of
the mistake repeats and the number is what makes it invisible.

| Attempt |                           Predicate                           | Reported |                                                Why it was wrong                                                 |
|---------|---------------------------------------------------------------|----------|-----------------------------------------------------------------------------------------------------------------|
| 1       | not under `containers/storage/`                               | **11**   | Those were the data directories, equally deliberate. *Where a file sits* describes today's layout, not the rule |
| 2       | uid outside the **service user's** subuid range               | **14**   | Containers run under another account's subuids. One user's range says nothing about another's                   |
| 3       | uid outside **every** subuid range                            | **1000** | Not a count — a **uid**. The YAML folded scalar joined the lines with spaces and ate the trailing `| wc -l`     |
| 4       | uid vs `/etc/subuid` **and** gid vs `/etc/subgid`, separately | —        | Correct                                                                                                         |

The fourth is the interesting one. Attempt 3's surviving entry was `uid=1000` on a container's
`/etc/shadow` — and uid 1000 is the operator, perfectly valid. The file had been matched by
**`-nogroup`**, not `-nouser`: its *group* was a subgid. Checking the uid of a file that was flagged
for its group asks the wrong question of the right file.

`-nouser` is about the uid and is explained by `/etc/subuid`. `-nogroup` is about the gid and is
explained by `/etc/subgid`. They are two questions, and the check now asks both:

```
find / -xdev -nouser  -printf '%U\n' | sort -u | awk ... /etc/subuid -  | wc -l
find / -xdev -nogroup -printf '%G\n' | sort -u | awk ... /etc/subgid -  | wc -l
```

> [!note] Three plausible numbers, none of them an error message
> Eleven, fourteen, a thousand. Each was returned by a check that ran cleanly and answered exactly
> the question it was asked. Two asked the wrong question; one was reshaped by YAML into a different
> command than the one written. **A literal block (`|`) rather than a folded one (`>-`) is not a
> style preference where a shell pipeline is concerned** — folding reflows it, and an `awk` program
> with a trailing pipe does not survive being reflowed.
>
> What turned each of them into progress was the same reflex: the number looked like a finding, so
> it got investigated instead of filed.

## 20. Phase 2 — the databases, read-only and without capabilities — 2026-09-16

The two Phase 2 items the plan called *indicative* rather than *measured* — **read-only with real
mounts** and **the capability reduction for the databases** — are now measured on Rocky 10.2 with
Podman 5.8.2, against the real image digests, the real `PGDATA`, the real data mount, the real
`appendonly` persistence and the real server flags from `docker-compose.yml`.

**Both databases run read-only.** Both can run with **no capabilities at all**. And the reduction
that looks most obviously safe — dropping capabilities from redis — turned out to be the one that
silently makes it *more* privileged.

### The measured sets

Every arm is judged by a health probe **and** a real write, and reports the **uid of pid 1**,
because *the container came up* stopped being evidence of anything partway through this.

| Service | Configuration | Result | pid 1 |
|---------|---------------|--------|-------|
| postgres | the compose set: `CHOWN DAC_OVERRIDE FOWNER SETGID SETUID` | OK | 70 |
| postgres | **without `FOWNER`** | OK | 70 |
| postgres | without `CHOWN` / without `DAC_OVERRIDE` | FAIL | — |
| postgres | without `SETGID` / without `SETUID` | FAIL | — |
| postgres | **`--user 70:70`, `--cap-drop=ALL`** | OK | 70 |
| redis | the compose set (same five) | OK | 999 |
| redis | **`SETGID`+`SETUID` only** | OK | 999 |
| redis | `CHOWN`+`DAC_OVERRIDE`+`FOWNER`, no gosu caps | **OK** | **0** |
| redis | `--cap-drop=ALL` | FAIL | — |
| redis | **`--user 999:999`, `--cap-drop=ALL`** | OK | 999 |

So postgres needs **four** of its five, redis needs **two** of its five, and both need **none** if
the container is started as its own uid instead of dropping to it.

`DAC_OVERRIDE` is postgres's least obvious requirement: the data directory belongs to container uid
70 and the entrypoint's root phase has to create `pgdata` inside it. Pre-creating that directory on
the host, owned by 70, does **not** buy the capability back — measured, because it was worth asking.

> [!danger] Redis fails **open**, and that is a one-way door
> The redis entrypoint tests its own capabilities before dropping privileges:
>
> ```
> # our uid is 0 (container started without explicit --user)
> # and we have capabilities required to drop privs
> if [ "$IS_REDIS_SERVER" ] && ... && has_cap setuid && has_cap setgid; then
> ```
>
> Without those two it **skips the drop and runs redis as root** — healthy, answering `PING`,
> passing any check that asks whether the container is up. Postgres has no such test: it runs
> `exec gosu postgres`, which fails loudly.
>
> The damage is on disk. Measured: the root fallback writes `appendonlydir` and every AOF file as
> `0:0`, mode `0600`. Restarting afterwards with the **correct** `--user 999:999` then **refuses**
> to come up — `Error moving temp append only file on the final destination: Permission denied`.
> A capability reduction that reads as a success leaves data the right configuration can no longer
> open.

### What this means for the units

`--user` is the better shape for both, and the number it needs already exists: ADR-0186's role owns
each data directory as `basetool_subuid_base + container_uid - 1`, with `container_uid` written once
per service in `basetool_container_owners` — 70 for postgres, 999 for redis.

It is not free. The entrypoint's root phase also *repairs*: if a data directory's ownership is ever
wrong, root fixes it, and `--user` merely fails. The trade is a container that cannot repair itself
against one with no root phase to escape from.

> [!warning] Adopting `--user` puts the same number in two files
> The role owns the directory as container uid 70; the unit would run the process as uid 70. Today
> that number lives in `basetool_container_owners` only. A Quadlet `User=` line makes a second place
> for it to be true, and a first place for it to drift. If this is adopted, the conformance suite has
> to assert the two against each other — and it should assert the **uid of pid 1** regardless of
> which shape is chosen, because that is the check redis's root fallback would have failed.

### Podman mounts `/run` for you, and copies the image into it

`--read-only` with **no tmpfs at all** worked, which should have been impossible: postgres has to
write `/var/run/postgresql`. Podman's `--read-only` mounts a tmpfs over `/run`, `/tmp` and
`/var/tmp` by default and **copies the image's content up into it**:

```
image:                  drwxrwsrwt  70  70  /run/postgresql
--read-only container:  drwxrwsrwt  70  70  /run/postgresql   <- nothing inside created this
```

The container ran as uid 70 with `--cap-drop=ALL` against a `/run` owned by root at `0755`; it could
not have made that directory. `--read-only-tmpfs=false` leaves all three unmounted, which is the
control that proves who did it.

**Docker does not do this**, and that is why `edge` carries explicit `tmpfs:` entries. Under Podman
they become redundant rather than wrong — so they stay, because they are what makes the requirement
legible.

### Four failures that were mine, not the software's

Recorded because each produced a **clean, plausible, wrong answer**, and two of them were reported
to @greluc as findings before they were checked.

1. **`postgres fails read-only`** — it does not. The harness left the data directory owned by the
   host user, which is container **root**, at `0750`; the entrypoint re-execs as uid 70, which then
   cannot traverse its own mount point. Every arm failed identically, *including the writable
   control* — and every arm failing the same way is the shape of a broken harness, not of a finding.
2. **`redis needs no capabilities`** — it does, unless it is given `--user`. That arm had no real
   data mount, so nothing ever wrote to disk and the only failing path was never taken. The
   difference between a container that starts and a container that persists is the whole question.
3. **The `PGDATA`-pre-created arms measured nothing.** They created the directory with the host's
   `mkdir` inside one already `chown`ed to `100069`, so it never existed and three arms re-ran the
   same configuration. `podman unshare mkdir` was the fix: inside the namespace, `70` means 70.
4. **`grep -iE 'error'` blamed `bf-error-rate`** — a redis startup line about bloom-filter defaults
   — for a failure it had nothing to do with. The verdict came from the health probe and was right;
   the explanation printed beside it was a substring match. A failing arm now prints its log
   verbatim.

