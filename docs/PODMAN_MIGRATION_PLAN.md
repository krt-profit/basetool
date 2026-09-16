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

### 3.1 Does the edge see the client's source address? — **NO. This rejects ADR-0163 as written.**

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

It also does not close the *empirical* half. Once a platform is chosen, the behaviour is still
measured before anything is built on it — the documentation says what is supposed to happen, and
this project's own history is a list of things that were supposed to happen.

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

### 3.5 Do healthchecks and resource limits work under a user slice? — yes, with one spelling change

Measured on the Debian 13 testing host, 2026-09-16: cgroup v2, and **`user.slice` already delegates
`cpuset cpu io memory hugetlb pids rdma misc`**. The memory and pids controllers a rootless service
needs are therefore present without further host work.

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

