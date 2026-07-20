# ADR-0112 — Restore the real client IP at the NPM edge via native IPv6 on the proxy bridge

- **Status:** Proposed
- **Date:** 2026-07-20
- **Deciders:** @greluc
- **Related:** spec [REQ-SEC-023](../specs/security-and-access.md) · [ADR-0049](0049-config-as-promotable-oci-artifact.md) (config as a promotable OCI artifact) · runbook [`docs/deployment.md`](../deployment.md) → *Edge rate limiting* · the 2026-07-20 edge-rate-limit incident (PR #1382)

## Context

The edge per-IP rate limiter (REQ-SEC-023) keys `limit_req` / `limit_conn` on nginx's
`$binary_remote_addr`. On 2026-07-20 a handful of members on the mission page tripped the
60-connection cap and the edge 429'd everyone, because nginx was seeing the **same** source — the
Docker bridge gateway `172.28.3.1` — for (almost) every internet client. PR #1382 raised the cap to
10000 as a stopgap; this ADR records the verified root cause and the fix that lets the cap return
to a real per-client value.

Host forensics (Docker 29.6.2, no `/etc/docker/daemon.json`, so userland-proxy at its default
`true`) established that the masking is **IPv6-specific**, not a general SNAT:

- **IPv4 is already correct.** External IPv4 SYNs hit the iptables `nat` `DOCKER` DNAT rule
  (`! -i br-… --dport 443 -j DNAT --to 172.28.3.3:443`) via `PREROUTING`; DNAT rewrites only the
  destination, the packet is forwarded to the container, and `POSTROUTING` masquerade does not
  match an external source, so the real client IPv4 reaches nginx. The IPv4 `docker-proxy` holds
  **zero** external client legs, and NPM logs real IPv4 addresses (e.g. `88.133.68.219`).
- **IPv6 is relayed and masked.** The host is dual-stacked (`2a01:4f8:1c19:6462::1/64`) and
  `-p 443:443` also binds `[::]:443`, but the container network `net-proxy-frontend` is
  **IPv4-only** — so Docker installs **no** `ip6tables` `nat` DNAT (the chain is empty; you cannot
  DNAT an IPv6 packet onto an IPv4-only container). The **only** IPv6 datapath is the userland
  `docker-proxy` on `[::]:443`, an L4 relay: it accepts the client's IPv6 connection and opens a
  **fresh IPv4** connection to `172.28.3.3:443`, whose source the kernel picks as the bridge's own
  IPv4 = the gateway `172.28.3.1`. Confirmed live: `docker-proxy` holds 13
  `[client-v6] ↔ 172.28.3.1→172.28.3.3:443` relay pairs.
- **Why it looks universal.** The host advertises AAAA and essentially all EU consumer clients are
  dual-stack and prefer IPv6 (Happy Eyeballs), so real browser traffic arrives over IPv6 and
  collapses onto the gateway; the rare IPv4 client keeps its real IP.

Constraints that bound the fix: NPM reaches every upstream by Docker service DNS on the shared
`net-proxy-*` bridges; the subnets are pinned and a `networks:` change forces a gated clean-slate
recreate (`deploy.sh`); `/etc/docker/daemon.json` is **not** carried by the config bundle (a manual
host step); and the Keycloak `/admin` allow-list depends on the operator's IPv4 SSH-tunnel hairpin
still SNAT-ing to `172.28.3.1`.

## Decision

We will restore the real client IP by giving the `net-proxy-frontend` bridge a **native IPv6
subnet** (`enable_ipv6: true` + an IPv6 subnet in the top-level `networks:` block), so Docker 29
installs the `ip6tables` `nat` DNAT rule `[::]:443 → [npm-v6]:443` and IPv6 ingress traverses the
kernel DNAT path exactly like IPv4 — preserving the client's source address end-to-end. Once real
per-client IPs reach nginx (IPv4 already, IPv6 after this change), we retighten `limit_conn
krt_conn_perip` from the 10000 stopgap back to ~500 per client, and add an `http.conf` `map` that
derives the limiter key by collapsing IPv6 to its `/64` prefix (IPv4 stays the full address), so a
subscriber's rotating privacy-extension addresses do not fragment the bucket.

Because it edits the `networks:` block, it ships through the existing config bundle and lands via
`deploy.sh`'s gated clean-slate recreate (one brief, scheduled full-stack outage). No
`daemon.json` change, no new external infra, no NPM SQLite surgery, and — uniquely among the
alternatives — the `/admin` allow-list is untouched (the IPv4 tunnel hairpin still SNATs to
`172.28.3.1`).

Sequencing is strict and gated: keep `iri-deploy.timer` stopped during the window; leave
`limit_conn 10000` in place until real IPs are **confirmed** from an external dual-stack host (the
`[Client …]` access-log grep shows real GUAs / IPv4, and `ip6tables -t nat -S DOCKER | grep 443`
is non-empty); only **then**, in a **separate** bundle change (touching only `docker/maintenance`,
an in-band `up -d`, no outage), retighten `limit_conn` to ~500 and add the `/64` key map; re-enable
the timer once the bundle matches the deployed state. The confirmation gate must sit **between**
restoring IPs and retightening, so a mistaken IP fix can never re-arm a tight cap on the
still-shared gateway key.

## Consequences

- Both address families reach nginx with the real client IP, so REQ-SEC-023's per-IP zones become
  meaningful again and the cap returns to a tight per-client value — the DoS ceiling works as
  designed and the "other client IPs unaffected" acceptance criterion becomes achievable.
- One brief scheduled full-stack outage (the pinned-subnet clean-slate recreate) to land the
  `networks:` change; fully reversible (revert the block, one more recreate restores IPv4-only
  publishing).
- Ongoing care: the published-port DNAT must target NPM's v6 address (NPM is multi-homed across
  four bridges) — verify the `ip6tables nat DOCKER` chain gains the `:443` rule; validation must be
  done from an **external** dual-stack host (local / loopback always shows the gateway regardless).
- The Keycloak `/admin` gateway allow-list keeps working unchanged (unlike every rejected
  alternative, which each require rewriting it), and the external `edge-deny-probe` tripwire stays
  green.
- If the maintenance window cannot be scheduled promptly, dropping the host's AAAA record routes
  live traffic onto the already-correct IPv4 path (real IPs, at the cost of IPv6-only clients) as a
  zero-touch interim until this lands.

## Alternatives considered

- **Disable Docker userland-proxy (`"userland-proxy": false`)** — rejected, and actively harmful.
  It is a no-op for IPv4 (already correct via the `PREROUTING` DNAT) and deletes the **only** IPv6
  datapath (no `ip6tables` DNAT to fall back to on an IPv4-only container) → IPv6 `:443`
  connection-refused for dual-stack clients, i.e. an outage for most real users. Also out-of-band
  (`daemon.json`, and `systemctl restart docker` restarts every container) and it breaks the
  `/admin` SSH-tunnel and its gateway allow-list.
- **NPM `network_mode: host`** — rejected. NPM leaves the `net-proxy-*` bridges, Docker embedded
  DNS can no longer resolve `frontend` / `keycloak` / `ingest` / `grafana`, and the app services
  publish no prod host ports — so all proxying breaks unless every app service is republished on
  host ports and every upstream rewritten to `127.0.0.1`, dismantling the network-isolation model
  and widening the attack surface.
- **PROXY protocol via a front L4 load balancer (Hetzner LB) + nginx `real_ip`** — viable but
  rejected as the default: it adds new paid external infra, needs out-of-band NPM listener config
  (`listen … proxy_protocol; real_ip_header proxy_protocol;`, jc21 UI support is limited) that
  breaks the TLS handshake if mismatched on either end, and requires reworking the `/admin`
  allow-list. Kept as the fallback if a native IPv6 prefix genuinely cannot be routed to the host.
- **nginx `real_ip` alone (`real_ip_header X-Forwarded-For`)** — rejected: the userland relay is a
  header-less L4 relay and there is no trusted front hop, so there is no XFF / PROXY header to read;
  trusting an inbound XFF at the internet edge would be a spoofable rate-limit bypass. It is a
  necessary companion to the LB option, never a fix by itself.
- **Drop the AAAA record permanently** — rejected as an end state (kills IPv6 reachability); kept
  only as a zero-touch **interim** mitigation while this ADR is implemented.

