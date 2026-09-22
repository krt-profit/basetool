# ADR-0187 — The edge learns the client address from a host-level PROXY-protocol front end

- **Status:** Accepted — implemented: in production since the rootless-Podman cutover of 2026-09-22 (host haproxy from the Ansible role, `docker/edge/render-and-run.sh` switching the listeners to `proxy_protocol`). On that host it **supersedes the client-address transport of [ADR-0112](0112-edge-real-client-ip-restore-native-ipv6.md)** (native IPv6 DNAT on the proxy bridge); ADR-0112's `/64` limiter key stands
- **Date:** 2026-09-16
- **Deciders:** @greluc (the decision), Claude (measurement and analysis)
- **Related:** [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  [ADR-0112](0112-edge-real-client-ip-restore-native-ipv6.md) ·
  [ADR-0135](0135-public-api-vhost-not-a-gateway.md) ·
  specs `REQ-SEC-023`, `REQ-OBS-005`, `REQ-OPS-004` ·
  [`PODMAN_MIGRATION_PLAN.md`](../archive/PODMAN_MIGRATION_PLAN.md) §13

## Context

Rootless Podman's default port forwarder, `rootlessport`, is a userspace proxy: every request
reaches the container with the forwarder's address in place of the client's. Six things in this
repository read `$remote_addr`, and the two that matter are security controls — the per-client rate
limiter (`REQ-SEC-023`, ADR-0112) and the Keycloak admin allow-list, which does not merely degrade
but **inverts**, because it distinguishes operator traffic from internet traffic by peer address.

[ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) chose its platform
to obtain the fix: `rootless_port_forwarder="pasta"`, which Podman 6 forwards through pesto with the
source address preserved. Measured on 2026-09-16, that option **preserves the address on IPv4 and
does not deliver IPv6 at all** — the socket binds, the connection is reset, nothing reaches the
container. The cause was traced (plan §13): `pasta --config-net` copies the host's IPv4 address into
the rootless network namespace but Podman overrides IPv6 with a private ULA, so the host's global v6
address is not local there, and netavark's host-port DNAT chain — entered through
`fib daddr type local` — is never reached.

That leaves two positions, both bad. With the pasta forwarder the deployment has correct client
addresses and **no IPv6 service**. With `rootlessport` it has IPv6 and **no client addresses** — on
IPv6 not even the right address family, since a v6 client is logged as an IPv4 bridge address. The
edge serves dual-stack and `ipv6-reachable` is one of the conformance suite's assertions, so each
position fails a check that passes today.

The edge's own `nginx.conf` already anticipated this situation and prescribed its handling: there is
no `set_real_ip_from` today because nothing sits in front, and the comment says that if something
ever is put in front, the setting has to come back **with its ranges, and never as `0.0.0.0/0`**.

## Decision

**A host-level L4 front end terminates the public ports and hands the edge the client address out
of band, using PROXY protocol v2.**

- **haproxy runs as a host service** — not a container — in `mode tcp`, binding `:80` and `:443`.
  It is pure byte forwarding: **TLS still terminates at the edge**, so the certificate handover of
  plan §3.4 is untouched and no key exists in a second place.
- **v4 and v6 are bound separately.** A single `bind :::80 v4v6` makes IPv4 clients arrive as
  `::ffff:10.9.0.14`, and every CIDR-based `allow` rule behind it stops matching. Measured.
- **The edge container publishes on loopback only**, on both families. This is what makes the PROXY
  header unforgeable: nothing outside the host can reach the container's port to invent one.
- **The edge trusts exactly the forwarder** via `set_real_ip_from`, with `real_ip_header
  proxy_protocol`. A single address, **not a subnet**, and never `0.0.0.0/0` — as the edge's own
  configuration has said since before this decision. **This requires the edge container's address
  to be pinned** with `IP=` in its Quadlet unit — measured, not assumed.

> [!note] Why the pin is not optional, and what it drags in with it
> The peer the edge sees is the container's **own** address. Measured on Rocky against the real
> `net-edge-ingress` subnet: with `IP=172.28.15.10` pinned, three recreations in a row produced
> `peer=172.28.15.10`. Without it, two recreations produced `172.28.15.2` and then
> `172.28.15.3`. So the single address ADR asks for exists only with the pin; without it the rule
> would have to widen to the subnet, and would be quietly broken by the first restart.
>
> The pin also **presupposes a user-defined bridge network** — podman refuses it otherwise:
> *"static ip/mac address can only be used with Bridge mode networking"*. So the pin and the
> ingress network's isolation properties are decisions about the **same** network, and are taken
> together rather than one after the other.

Why that last pair is load-bearing, and not a detail of configuration style:

> [!danger] PROXY protocol is a trust statement, not a measurement
> The first bytes of the connection **assert** a source address and nginx believes them. If anything
> other than the front end can reach the edge's port, this is not a recovered client address — it is
> a **source-address forgery tool**, and one that undermines precisely the `allow` rules and log
> lines it was built to save.
>
> Two properties therefore have to hold **together**, and both are verified rather than assumed:
> `set_real_ip_from` names only the front end, and the edge's port is reachable from nowhere else —
> not from the host's own public addresses, not from another machine on the management network, not
> from the container's own neighbours. The test is a plain connection to that port **from a different
> host**, and it has to fail before anything speaks PROXY protocol at all.
>
> This is stated here because it is obvious while the thing is being built and invisible to whoever
> rearranges it in a year.

The pasta forwarder is **not** used; `rootless_port_forwarder` stays at its default. **The rate
limiter, the admin allow-list, the API allow-list and the access log are unchanged** — they keep
reading `$remote_addr`, which is the client's address again.

### Measured before deciding, on Podman 6.1.0

|                                      |                                Result                                 |
|--------------------------------------|-----------------------------------------------------------------------|
| loopback-only publish, both families | `127.0.0.1` and `[::1]` bound; unreachable from either global address |
| a connection without a PROXY header  | rejected — *broken header while reading PROXY protocol*               |
| the full chain, IPv4                 | `remote=::ffff:10.9.0.14`, matching `$proxy_protocol_addr`            |
| the full chain, IPv6                 | `remote=2003:…:fe85:2fe9`, matching `$proxy_protocol_addr`            |

SELinux required one narrow grant: the container's loopback port must be labelled
(`semanage port -a -t http_port_t -p tcp <port>`) before confined haproxy may connect to it. **The
broad `haproxy_connect_any` boolean stays off** — it was tried second and was not needed.

## Alternatives considered

**Ship on `rootlessport` and rebuild the two controls.** Rejected. The admin allow-list can be
replaced by something better than an IP rule, but **the rate limiter cannot be replaced at all**:
a session cookie only keys authenticated routes while the login endpoint that most needs limiting is
unauthenticated, and `X-Forwarded-For` neither exists nor would be trustworthy. `REQ-SEC-023` would
become one bucket for the entire internet — the permanent form of an outage this deployment has
already had once. This remains the fallback if the decision below proves unworkable.

**Wait for an upstream fix.** The defect is filed against a documented feature of the installed
version, so it is worth reporting — but no release fixes it today, and it is not a plan on its own.

**Take the edge out of the bridge topology.** Measured dead: pasta as the container's network mode
does not deliver IPv6 on Podman 6.1.0 either. Host networking was never measured and fails on a
different ground — the edge would sit in the host namespace while every backend sits in the rootless
one, reaching none of them without publishing every backend port on the host.

**Change distribution.** Does not help: the defect is in Podman 6.1.x plus netavark 2.1.0, and
Fedora 45 carries the same netavark.

## Consequences

- **One more host service**, which Ansible provisions and which belongs in the promotable
  configuration artifact (`REQ-OPS-004`, ADR-0188's boundary: Ansible provisions, `deploy.sh`
  deploys). It sits in front of everything and is a single point of failure outside the Quadlet
  lifecycle.
- **The loopback-only publish becomes a security invariant, not a detail.** If the container's port
  is ever reachable from outside, the PROXY header becomes forgeable and both the admin allow-list
  and the rate limiter can be bypassed. The conformance suite asserts it rather than leaving it to
  memory.
- **A misconfiguration is loud.** An nginx listener with `proxy_protocol` rejects header-less
  connections, so getting this wrong breaks the site rather than silently disabling the controls.
- **It closes the open question of binding `:80`/`:443` rootless.** The host service may bind
  privileged ports; the container no longer needs to. Neither `ip_unprivileged_port_start` nor
  `CAP_NET_BIND_SERVICE` is required.
- **The migration no longer depends on an experimental upstream feature.** PROXY protocol is old,
  dull and widely deployed; that is the point.
- HTTP/3 would bypass a TCP front end, since QUIC is UDP. Checked: this deployment serves no
  HTTP/3, and adopting it later would need its own answer.

## Status of this decision

Accepted on the measurements above, taken on the CentOS Stream 10 testing VM. They are **re-run on
Rocky Linux 10** before production, because ADR-0163's platform changed with this decision — the
only reason to be on a development stream was the pasta forwarder, and this ADR removes it.

> [!note] The target platform, measured 2026-09-17 — the rejected alternative is not merely
> unattractive there, it is absent
> The Rocky 10.2 host runs **podman 5.8.2, netavark 1.17.2, passt `0^20251210`** — one major version
> behind the CentOS Stream VM these measurements were taken on. That matters in the deployment's
> favour twice. **`rootless_port_forwarder` does not exist on 5.8.2 at all**, so the option this ADR
> weighed and rejected is not a choice anyone can make here by accident. And the IPv6 defect above
> is specific to *"Podman 6.1.x plus netavark 2.1.0"*, neither of which is installed — so the
> decision rests on the front end being the right shape, not on a defect that followed us.
>
> It also **removes a belt this ADR never claimed but a reader might assume**: measured by the PVE
> operator on netavark 1.17.2, `--internal` does **not** strip inbound DNAT, so an internal network
> is not an ingress control on this version. Checked against these units — only `edge` publishes a
> port, and only on loopback, and the five `Internal=true` networks carry none — so nothing is
> exposed by it. The consequence is that the loopback-only publish is the **sole** barrier, which is
> exactly why the test below is not optional.

Two things are deliberately **not** settled yet, and neither may be guessed at deployment time:

1. ~~**The exact address `set_real_ip_from` names.**~~ **Answered on 2026-09-16, and the answer was
   wrong — corrected 2026-09-22 after it cost a live regression.** What was measured in September
   held: pinning `net-edge-ingress` to `172.28.15.10` produced `peer=172.28.15.10` across three
   recreations, and without a pin the address moved. What it did **not** establish is that the
   ingress network is the one the peer comes from. On the production host it is not, and it is not
   consistently any single network either.

   The peer is the edge's **own** address — `rootlessport` dials the container's published port
   from inside its netns — and podman chooses **which** of the container's networks that address
   belongs to. Measured on `rocky-16gb-nbg1-1` on 2026-09-22, three recreations with nothing else
   changed: the peer appeared on `net-proxy-frontend`, then `net-proxy-grafana`, then
   `net-proxy-api`. Pinning one network only moves the choice to the next, which is exactly what
   happened when `net-proxy-frontend` was pinned in response to the first finding.

   **What it cost.** nginx does not reject a `set_real_ip_from` that never matches: it discards the
   PROXY header and falls back to the TCP peer. So the edge started clean, served traffic, and
   logged **every** request from one bridge address — 2340 in ten minutes, a probe issued over the
   public internet among them. One rate-limit bucket for the whole internet and every
   `$remote_addr` allow-list keyed on it: the 2026-07-20 outage's shape, reached by a different
   road, with a valid configuration and a green build throughout. It was not a recreate that caused
   it — the first deploy after the cutover happened to produce a matching peer, so the next release
   would have done it unattended.

   **The correction, and why it is not a weakening.** Every network the edge joins is pinned, and
   `EDGE_TRUSTED_PROXY` names **all six** of those addresses. The rule this ADR set is *name the
   address, never a prefix* — and six named addresses are no more a range than one is. Each is the
   edge itself, on a network nothing else can reach it from, and the set is finite precisely
   because every membership is pinned. `render-and-run.sh` validates each entry separately and
   still refuses a prefix or a wildcard, including one appended to a list that otherwise works.
   What would be a weakening is `172.28.0.0/16`, and that is still refused.

   **The guard is now structural.** Three files have to agree on the set — the generator's
   `FRONT_END["edge"]["pins"]`, the emitted `edge.container`, and the Ansible
   `basetool_host_edge_trusted_proxies` — and `.github/scripts/check_edge_trust_pins.py` fails the
   build when they do not, including when the edge gains a network without a pin. That last check
   is what keeps the candidate set finite; the behavioural one
   (`check-conformance.py`'s `client-address-visible`, which is how this was found) stays as the
   backstop that reads the running system.

   > **Note (2026-09-22):** `.github/scripts/check_edge_trust_pins.py` does not exist. The
   > structural check shipped inside `scripts/generate-quadlet.py` (the pin/trust comparison that
   > refuses when a joined network carries no pin, a pin falls outside its subnet, or
   > `basetool_host_edge_trusted_proxies` differs from the pinned set), run by the `quadlet-drift`
   > job in `repo-lint.yml` (commit 5bb8607c2); `scripts/check-edge-nginx.sh` renders the edge
   > with `EDGE_TRUSTED_PROXY` set.
2. **Unreachability from a third machine.** The probe above established that the port is closed on
   the host's own global addresses. The stronger test — a direct connection from somewhere else —
   runs on Rocky before this is built for real, and the note above is why it carries the whole
   invariant rather than confirming a second layer.

   **Pick the vantage point deliberately.** A host on the management network is the *weaker* one:
   the testing host's guest firewall admits `10.1.0.0/24` and `192.168.2.0/24` in full, so a
   connection from there is more permissive than the internet and a success proves nothing. The
   asymmetry is still useful — a **failure** from the permissive side is strong evidence — but the
   claim being made is about the internet, so the test belongs on the internet. @greluc's
   suggestion, recorded because it is the cheapest correct instrument: a **throwaway GitHub Actions
   runner**, which is genuinely off-network, needs no standing infrastructure, and leaves a dated
   log. Its one limitation has to be stated with it — GitHub-hosted runners have historically had
   **no IPv6 egress**, so the v6 half of the assertion needs a different vantage point or an
   explicit check that the runner can reach any v6 address at all before its result means anything.
   Production is where this matters most: the Hetzner host has a public v4 and v6 and no proxy in
   front, so the loopback publish is the only thing between the internet and a forgeable header.
