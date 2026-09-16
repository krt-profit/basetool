# ADR-0187 — The edge learns the client address from a host-level PROXY-protocol front end

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** @greluc (the decision), Claude (measurement and analysis)
- **Related:** [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  [ADR-0112](0112-edge-real-client-ip-restore-native-ipv6.md) ·
  [ADR-0135](0135-public-api-vhost-not-a-gateway.md) ·
  specs `REQ-SEC-023`, `REQ-OBS-005`, `REQ-OPS-004` ·
  [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md) §13

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
  to be pinned** (`IP=` in its Quadlet unit): measured on Rocky, the peer the edge sees is the
  container's *own* address, and it moved from `10.89.0.2` to `10.89.0.3` across a recreation.
  Without a pinned address there is no single value to name, and the rule would have to widen to
  the one-member ingress subnet.

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
  configuration artifact (`REQ-OPS-004`, ADR-0186's boundary: Ansible provisions, `deploy.sh`
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

Two things are deliberately **not** settled yet, and neither may be guessed at deployment time:

1. **The exact address `set_real_ip_from` names.** The measurement above trusted a subnet, which is
   good enough for a probe and not good enough for production. The address the edge actually sees
   behind `rootlessport` on a one-member `net-edge-ingress` is measured on the target platform and
   pinned as a single address.
2. **Unreachability from a third machine.** The probe above established that the port is closed on
   the host's own global addresses. The stronger test — a direct connection from another host on the
   management network — runs on Rocky before this is built for real.

