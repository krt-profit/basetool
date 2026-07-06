# Management-Plane Access via WireGuard VPN — Implementation Plan

> **Doc type:** Implementation plan — **living** until shipped, then freeze and point at the living truth (planned: ADR-0077, `REQ-OPS-017` in [`docs/specs/deployment-delivery.md`](specs/deployment-delivery.md), the reworked "Management access" section of [`docs/deployment.md`](deployment.md), and the `REQ-OBS-012` extension in [`docs/specs/observability.md`](specs/observability.md)).
> **Status:** Phase 0 (inventory) **complete** 2026-07-06 — findings in §4. No change has been applied to the host, the UniFi gateway, NPM, or Hetzner yet; next up: Phase 1 (#1053, owner, UniFi UI).
> **GitHub epic:** [krt-profit/basetool#1051](https://github.com/krt-profit/basetool/issues/1051) · **Sub-issues:** #1052 (Phase 0), #1053 (Phase 1), #1054 (Phase 2), #1055 (Phase 3), #1056 (Phase 4), #1057 (Phase 5).
> **Execution model (owner decision):** mixed — the owner performs the UniFi UI work (Phase 1) and applies key material on the host; Claude performs the host inventory/setup over SSH (Phases 0/2), the NPM changes (Phase 3), and the repo PR (Phase 4), announcing every host-mutating step beforehand. Phase 5 is a joint, owner-approved cut-over.
> **Last updated:** 2026-07-06.
> **Goal (owner request):** management access moves onto a WireGuard tunnel between the owner's UniFi site (UniFi Cloud Gateway Fibre = WireGuard **server**) and the prod host (= WireGuard **client**). Afterwards the management surfaces are no longer reachable from the public internet, the tunnel re-establishes automatically after a reboot of **either** side, and it carries **only** management traffic (strict split tunnel). Scope note: **Grafana is exempted** — it stays public by owner decision D5 (other org members need the dashboards).

---

## 0. How to use this document

1. Written to be executed phase-by-phase (one session per phase is fine); each phase has a GitHub sub-issue.
2. **Read `CLAUDE.md` first**; its rules override this plan. In particular: monitoring moves in the same PR as the change (binding), ADRs before/with the change, CHANGELOG in German, all Git/GitHub/in-code prose in English, `spotlessApply` before every push (it formats Markdown too).
3. **No WireGuard private key, preshared key, or exported peer config ever enters this repo, the wiki, a CI log, or a chat transcript.** `wg0.conf` stays host-only and is backed up out-of-band by the operator (existing owner decision, `REQ-OPS-010`).
4. Commits need DCO `-s` + `Co-Authored-By` trailers per `CLAUDE.md`.
5. The phase ordering is a safety order: **public SSH is closed last**, only after the tunnel has proven reboot-resilience and its down-alert is live.

---

## 1. Current state (verified in-repo 2026-07-06, updated after owner answers)

|                 Surface                  |                                                                                                                                                                    Today                                                                                                                                                                     |                             Source                             |
|------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| Public edge                              | NPM publishes `80:80`, `443:443`; admin UI is **loopback-only** `127.0.0.1:10081:81`                                                                                                                                                                                                                                                         | `docker-compose.yml` (~line 758)                               |
| Keycloak Admin Console                   | Public vhost `keycloak.profit-base.online`, but `/admin` custom location has an nginx `allow` list = pinned Docker bridge gateways (`172.28.3.1`, `172.28.4.1`, `172.28.7.1`) + `deny all`; operator reaches it via `ssh -N -L 443:127.0.0.1:443 root@178.104.94.14` + hosts entry                                                           | `docs/deployment.md` → "Keycloak Admin Console via SSH tunnel" |
| Keycloak realm endpoints                 | Public and **must stay public** — end-user logins and the backend's JWKS fetch hairpin through the public edge                                                                                                                                                                                                                               | `docs/deployment.md`                                           |
| Grafana                                  | Public `grafana.profit-base.online` (NPM → `grafana:3000` over `net-proxy-grafana`), protected by Grafana login — **stays public by owner decision D5**                                                                                                                                                                                      | `docker-compose.yml`; §3                                       |
| Prometheus / Alertmanager / Loki / Tempo | No published host ports — reachable only inside the isolated monitoring networks (via Grafana)                                                                                                                                                                                                                                               | `docker-compose.monitoring.yml` (no `ports:`)                  |
| SSH                                      | `root@178.104.94.14`, key-only, `ssh.socket` owns the listener (a `ListenAddress` in `sshd_config` is ignored); public port 22 open                                                                                                                                                                                                          | `docs/deployment.md`; operator                                 |
| CI/CD                                    | **Pull-only host posture (`REQ-OPS-001`)** — no inbound SSH, no webhooks; deploys are host-side timers pulling GHCR/OCI artifacts. Closing inbound SSH breaks **nothing** in CI/CD                                                                                                                                                           | `docs/deployment.md`, `scripts/iri-deploy.*`                   |
| WireGuard                                | **The old host `wg0` no longer runs** (owner statement 2026-07-06; the "host already runs WireGuard" note in ADR-0056 is outdated). The daily `vpn-restart` cron was already removed in #1039. The interface name `wg0` is free; leftovers are swept in Phase 0. The `REQ-OPS-010` out-of-band key-backup duty applies to the **new** tunnel | Owner; ADR-0056; `CHANGELOG.md` #1039                          |
| External deny assertions                 | `.github/workflows/edge-deny-probe.yml` (daily, GitHub runner) asserts Keycloak `/admin` and the `/actuator` edge-deny from a genuinely external vantage point — internal blackbox probes hairpin and get SNAT'd to the allow-listed bridge gateways, so only an external probe can verify a deny                                            | workflow header comment                                        |
| Blackbox posture (since #1046/#1049)     | v4 liveness (`blackbox-http`), IPv6 liveness (`blackbox-http-ipv6`), force-SSL, HSTS, internal-TLS and DNS jobs exist; hairpinned v4 probes egress as the allow-listed bridge gateways, **v6 probes egress with the host's public IPv6** (relevant for the new vhost, §8.3)                                                                  | `monitoring/prometheus/prometheus.yml`                         |
| Host                                     | Hetzner Cloud **CPX42** (cloud VM). **Cloud Firewall `firewall-3` is active** (inbound TCP 22/ICMP/80/443 from Any v4+v6, outbound open); it sits outside the VM, immune to Docker's iptables DNAT. Public IPv4 + IPv6                                                                                                                       | `docs/MONITORING_ROLLOUT_RUNBOOK.md`; Phase 0                  |
| UniFi site                               | UCG Fibre with **public IPv4 + IPv6 behind DynDNS** (owner-confirmed) → the UCG-as-server direction works; no CGNAT role-flip needed                                                                                                                                                                                                         | Owner (D4)                                                     |
| Host systemd convention                  | Ops units live in-repo as `scripts/iri-<name>.service` + `.timer` + `.logrotate` pairs (see `iri-docker-cleanup.*`, #1039)                                                                                                                                                                                                                   | `scripts/`                                                     |

Two conclusions that shape the plan:

- **Most of the lock-down already exists.** NPM admin is loopback-only, Keycloak `/admin` is deny-by-default, actuators are edge-denied, the monitoring stores have no public ports, and Grafana is exempted. The genuinely new work is: the tunnel itself, a VPN-reachable NPM-admin vhost, tunnel self-healing + monitoring, and closing public SSH.
- **The `allow` bridge-gateway entries must stay** on every management vhost/location: they keep (a) the SSH-tunnel break-glass path working and (b) the internal blackbox liveness/cert probes green. External non-reachability is asserted by the GitHub workflow, not by removing them.

---

## 2. Target architecture

```
 Home / UniFi site                                   Hetzner prod host
┌───────────────────────────────┐                   ┌──────────────────────────────────────┐
│ UCG Fibre = WG **server**     │                   │ wg0 = WG **client** (initiates,      │
│  WG subnet: 192.168.3.0/24    │◄── UDP 51820 ────►│  PersistentKeepalive 25s)            │
│  host peer IP: 192.168.3.10   │  (host → DynDNS   │  AllowedIPs = 192.168.3.0/24,        │
│  endpoint: vpn.greluc.me      │   endpoint only)  │               10.1.0.0/24    (split) │
│                               │                   │                                      │
│ Mgmt clients in 10.1.0.0/24   │                   │  Via tunnel only:                    │
│  → allowed to 192.168.3.10:   │                   │   • SSH  root@192.168.3.10:22        │
│    22, 80, 443                │                   │   • npm.profit-base.online (443,     │
│ UniFi FW: block WG-client →   │                   │     allow-listed vhost)              │
│  any LAN/VLAN (no pivot)      │                   │   • KC /admin: ssh -L over the VPN   │
└───────────────────────────────┘                   │  Public (Hetzner CFW): 80, 443 only  │
                                                    └──────────────────────────────────────┘
```

- **Split tunnel, both directions.** On the host, `AllowedIPs = 192.168.3.0/24, 10.1.0.0/24` — app traffic, GHCR pulls, restic→Nextcloud backups, e-mail, DNS are untouched. `10.1.0.0/24` must be listed because UniFi routes (does not NAT) LAN sources into the WG server subnet, so replies to a mgmt client's VLAN IP must route back via `wg0`. On the UniFi side no client routes its default traffic through the tunnel; only traffic addressed to `192.168.3.10` enters it.
- **The NPM admin UI rides NPM's existing 443** via a new allow-listed vhost — no new listeners, no Docker-binding-to-`wg0`-IP ordering hazards (rejected option D7c). Enforcement is nginx `allow`/`deny` (the proven Keycloak-`/admin` pattern); reachability through the tunnel is split-horizon DNS on the UCG for `npm.profit-base.online` only.
- **Keycloak Admin Console keeps the SSH-tunnel pattern** (owner decision D6) — unchanged allow-list; from Phase 5 on, the `ssh -N -L 443:127.0.0.1:443` simply targets `root@192.168.3.10`, so the tunnel itself rides the VPN and nothing about it remains internet-reachable.
- **SSH is the only raw-port service consumed over the tunnel.** `sshd` keeps listening on `0.0.0.0` (via `ssh.socket`); the Hetzner Cloud Firewall stops public 22 (v4 **and** v6), while tunnel traffic arrives on `wg0` unfiltered by the CFW (it is payload of the established outbound UDP flow).
- **Auto-reconnect** is three mechanisms, each covering one failure mode: `systemctl enable wg-quick@wg0` (host reboot), client-side `PersistentKeepalive = 25` (UCG reboot — the host keeps re-initiating), and the `iri-wg-ensure` timer that re-resolves the DynDNS endpoint when the handshake goes stale (home WAN-IP change; also revives a wedged interface — the targeted replacement for the removed daily-restart cron; wg-quick alone never re-resolves an endpoint).

### Traffic matrix (target)

|          From           |                                 To                                  |     Path      |                        Allowed                         |
|-------------------------|---------------------------------------------------------------------|---------------|--------------------------------------------------------|
| Internet                | app / ingest / keycloak realm endpoints / **grafana** (443, 80→301) | public edge   | ✅ (grafana per owner decision D5)                      |
| Internet                | Keycloak `/admin`, `npm.profit-base.online` vhost                   | public edge   | ❌ nginx deny (asserted daily by `edge-deny-probe.yml`) |
| Internet                | SSH 22 (v4+v6), any other port                                      | public edge   | ❌ Hetzner CFW (default deny)                           |
| Mgmt VLAN `10.1.0.0/24` | `192.168.3.10` tcp 22/80/443                                        | tunnel        | ✅                                                      |
| Prod host (WG client)   | any home LAN/VLAN                                                   | tunnel        | ❌ UniFi firewall (no pivot); stateful replies only     |
| Prod host               | internet (GHCR, Nextcloud, SMTP, apt, DynDNS, WG handshake)         | public egress | ✅ (unchanged, not tunneled)                            |

---

## 3. Resolved owner decisions (2026-07-06)

| #  |        Decision        |                                                                          Answer                                                                           |
|----|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| D1 | WG transfer subnet     | `192.168.3.0/24` (UniFi default); host peer fixed at `192.168.3.10`. Phase 0 double-checks the net is unused                                              |
| D2 | Management source net  | Existing VLAN **`10.1.0.0/24`** (no new VLAN)                                                                                                             |
| D3 | Existing `wg0`         | **No longer runs** (ADR-0056-era info outdated) — fresh setup; Phase 0 sweeps leftovers                                                                   |
| D4 | UCG inbound endpoint   | Public IPv4 **and** IPv6, reachable via DynDNS → UCG = server as planned; endpoint = **`vpn.greluc.me:51820`** (Phase 0)                                  |
| D5 | Grafana                | **Stays public** (other org members need dashboards). Exempted from the lock-down; Grafana login remains the boundary                                     |
| D6 | Keycloak Admin Console | **Stays SSH-tunnel-only** (unchanged `/admin` allow-list); the `ssh -L` targets the WG address once public 22 closes                                      |
| D7 | NPM admin path         | **Dedicated vhost** `npm.profit-base.online` (public A record for HTTP-01, LE cert, allow-list, split-DNS via UCG); loopback publish stays as break-glass |
| D8 | SSH during soak        | **Stays open (key-only) until Phase 5** — no interim Hetzner-CFW restriction                                                                              |

---

## 4. Phase 0 — Inventory (read-only, no changes) — #1052 · Claude via SSH

On the host:

```bash
wg show all; systemctl is-enabled wg-quick@wg0 2>&1; ls -la /etc/wireguard/ 2>&1
grep -ril 'wg\|vpn' /etc/cron.* /etc/systemd/system/ 2>/dev/null   # vpn-restart leftovers
ss -tlnp | grep -E ':22|:10081'
sysctl net.ipv4.conf.all.rp_filter
ls /var/iri/monitoring/textfile/ 2>/dev/null   # confirm textfile-collector dir (locate mount in docker-compose.monitoring.yml if it differs)
```

On UniFi (owner) / Hetzner:

- UCG Fibre Network-application version (zone-based firewall UI?), confirm `192.168.3.0/24` unused, pick/confirm the DynDNS hostname, WAN type (PPPoE → wg MTU 1412 in Phase 2).
- Hetzner Cloud Console → existing Cloud Firewall + rules on the server.
- Record findings here; flag any deviation from §3.

**Exit criteria:** old-`wg0` leftovers identified (removal happens in Phase 2), textfile dir confirmed, UniFi/Hetzner prerequisites confirmed.

### Phase 0 findings — host side (executed 2026-07-06, read-only over SSH)

- **WireGuard state:** tools installed (`wireguard-tools 1.0.20210914-1ubuntu4`); `wg show all` empty, `wg-quick@wg0` **disabled** — owner statement confirmed, no tunnel runs.
- **Stale `/etc/wireguard/wg0.conf` exists** (309 B, 2026-03-20, **mode `644 root:root` — the old private key is world-readable**, including inside node-exporter's read-only `/` host mount). Content (secrets omitted): old client `Address 192.168.1.2/32`, `DNS = 192.168.1.1`, peer endpoint **`vpn.greluc.me:51822`**, `AllowedIPs = 10.1.0.0/24, 192.168.1.0/24`, **no `PersistentKeepalive`** — the missing keepalive plus the `DNS=` line explain the old tunnel's flakiness (and the former daily-restart band-aid). → **Phase 2 deletes this file**; the old key counts as exposed and must not be reused; the new conf ships `600` with keepalive and without `DNS=`.
- **DDNS:** `vpn.greluc.me` still resolves (91.21.156.212); the old server listened on **51822**. Owner confirmed `vpn.greluc.me` as the endpoint; the new server uses the UniFi default **51820** (51822 belonged to the defunct predecessor).
- **Listeners:** `sshd` on `0.0.0.0:22` **and** `[::]:22` (socket-activated, `ssh.socket` active, `00-hardening.conf` drop-in present) — Phase 5's CFW must cover v4 **and** v6, as planned. NPM admin on `127.0.0.1:10081` (docker-proxy) confirmed.
- **Host firewall posture:** `ufw` inactive, iptables `INPUT` policy `ACCEPT` — nothing **on the host** filters inbound; inbound filtering happens exclusively at the Hetzner Cloud Firewall layer (see the owner-side findings below).
- **Routing/collisions:** no `192.168.0.0/16` or `10.0.0.0/8` routes on the host (Docker uses `172.17–.19` + the pinned `172.28.0–11/24`) → `192.168.3.0/24` and `10.1.0.0/24` are collision-free host-side. `rp_filter = 2` (loose) on all/default — no asymmetric-routing trap for the tunnel's routed mgmt-VLAN return path.
- **Textfile collector confirmed:** `/var/iri/monitoring/textfile/` (holds `backup/deploy/docker_cleanup/restore_drill.prom`) — `wg-ensure.sh` writes `wireguard.prom` there (§8.2), matching `--collector.textfile.directory=/host/var/iri/monitoring/textfile` in `docker-compose.monitoring.yml`.
- **Repo rot to clean in Phase 4:** `scripts/iri-backup.timer` still references the removed `vpn-restart` cron in comments (lines 3/10). No other cron/systemd/logrotate leftovers; root crontab clean.

### Phase 0 findings — UniFi & Hetzner side (owner, 2026-07-06)

- **UniFi Network 10.5.56** on the UCG Fibre → zone-based firewall UI available.
- **`192.168.3.0/24` is free** in the home site → D1 stands.
- **WAN is PPPoE**; the exact MTU was not readable at inventory time → Phase 2 sets `MTU = 1412` (safe under any PPPoE MTU ≥ 1492).
- **DDNS endpoint confirmed: `vpn.greluc.me`**; WG server port = UniFi default **51820**.
- **Hetzner Cloud Firewall `firewall-3` already exists and is fully applied to the server** — inbound: TCP 22, ICMP, TCP 80, TCP 443, each from Any IPv4 + Any IPv6; outbound unrestricted. Everything else inbound is already default-denied at the cloud layer (which mitigates the host-side `INPUT ACCEPT` finding), and **Phase 5 reduces to deleting the TCP-22 inbound rule**.

**Phase 0 exit criteria met — phase complete (2026-07-06).**

## 5. Phase 1 — UniFi side (WireGuard server) — #1053 · Owner

1. **VPN server:** Settings → VPN → VPN Server → WireGuard: UDP `51820`, subnet `192.168.3.0/24`. One client peer "basetool-prod-host", fixed IP `192.168.3.10`. Hand the exported config to the host **over the existing SSH session only** — never repo/chat/cloud (`REQ-OPS-010` applies to the new key).
2. **DynDNS:** `vpn.greluc.me` (Phase-0-confirmed) — verify the record updates from the UCG.
3. **Firewall (zone-based):** allow `10.1.0.0/24` → `192.168.3.10` tcp `22,80,443` (+ ICMP for diagnostics); **block** new connections from the VPN peer into any internal zone (a compromised prod host must not pivot; stateful replies still flow; if the ADR-0056 SMB-to-UNAS backup fallback is ever activated, add a single host→UNAS:445 allow then); default-deny the rest.
4. **No "route all traffic" / full-tunnel option anywhere.**

**Exit criteria:** server up, peer created, rules in place (verified end-to-end in Phase 2).

## 6. Phase 2 — Host side (client + self-healing) — #1054 · Claude via SSH, owner applies the key

1. Delete the stale `/etc/wireguard/wg0.conf` (Phase 0 found it world-readable at mode 644 — the old private key counts as **exposed**; generate a **fresh keypair**, never reuse it), then write the new `/etc/wireguard/wg0.conf` (mode `600`; owner stores the key out-of-band per `REQ-OPS-010`):

   ```ini
   [Interface]
   Address = 192.168.3.10/32
   PrivateKey = <host-only>
   # No DNS= line — split tunnel, the host keeps its own resolver.
   MTU = 1412
   # 1412, not the wg default 1420: the UCG WAN is PPPoE (Phase 0), 1492 - 80 bytes WG overhead.

   [Peer]
   PublicKey = <UCG server pubkey>
   Endpoint = vpn.greluc.me:51820
   AllowedIPs = 192.168.3.0/24, 10.1.0.0/24
   PersistentKeepalive = 25
   ```
2. **Boot persistence:** `systemctl enable --now wg-quick@wg0`.
3. **Self-healing (`scripts/` additions, `iri-docker-cleanup` convention):** `scripts/wg-ensure.sh` — idempotent: (a) interface absent → `systemctl start wg-quick@wg0`; (b) `wg show wg0 latest-handshakes` older than 150 s → re-resolve `vpn.greluc.me` and `wg set wg0 peer <pub> endpoint vpn.greluc.me:51820` (stock `reresolve-dns.sh` logic; covers boot-with-DNS-not-ready — wg-quick is `Type=oneshot`, `Restart=` is not permitted, the timer is the retry — plus WAN-IP changes and wedged states); (c) write the §8.2 textfile metric. Plus `scripts/iri-wg-ensure.service` + `.timer` (every 1 min, `Persistent=true`) and logrotate if it logs.
4. **Verification (before touching any exposure):**
   - From a `10.1.0.0/24` client: `ssh root@192.168.3.10` works; `curl -k https://192.168.3.10` answers (NPM default site).
   - From the host: `nc -zw2 <LAN-IP> 445` **fails** (UniFi no-pivot rule works).
   - Split tunnel: `ip route get 8.8.8.8` still via the public interface; `wg show wg0 allowed-ips` shows exactly the two nets; backup/GHCR/e-mail unaffected.
   - **Reboot drill:** host reboot (maintenance window) → tunnel returns unaided; UCG reboot → handshake re-establishes ≤ ~2 min; provoked stale-endpoint → `iri-wg-ensure` repairs ≤ 2 min.

**Exit criteria:** all verifications green; soak starts (public 22 stays open per D8).

## 7. Phase 3 — Edge: NPM-admin vhost + split-DNS — #1055 · Claude (NPM UI), owner (UniFi DNS)

1. **New NPM proxy host `npm.profit-base.online`** → `http://127.0.0.1:81` (NPM's own admin app inside the container), LE cert via **HTTP-01** (public A record required; keep HTTP-01-only per edge posture), force-SSL. Apply the allow-list in a **custom location `/`** (not the server-wide Advanced field) so the ACME challenge path keeps renewing:

   ```nginx
   allow 192.168.3.0/24;   # management VPN transfer net
   allow 10.1.0.0/24;      # management VLAN (routed, not NAT'd)
   allow 172.28.3.1;       # net-proxy-frontend gateway — SSH-tunnel break-glass
   allow 172.28.4.1;       # net-proxy-keycloak gateway —  + internal blackbox
   allow 172.28.7.1;       # net-proxy-ingest gateway   —    hairpin probes
   deny all;
   ```
2. The loopback publish `127.0.0.1:10081:81` stays as break-glass.
3. **UniFi local DNS record** (owner): `npm.profit-base.online → 192.168.3.10` (LAN-wide is fine — the hostname is management-only, so no non-management traffic can hairpin). **No** LAN-wide record for `keycloak.…` (D6) and none for `grafana.…` (D5).
4. **Untouched by design:** Keycloak `/admin` allow-list (D6), Grafana vhost (D5).
5. **Verify:** via tunnel `https://npm.profit-base.online` loads with valid TLS; from an external network it answers 403; from the host loopback (break-glass tunnel) it still works; app/ingest/keycloak/grafana public surfaces unchanged; ACME renewal dry-run OK.

**Exit criteria:** NPM admin UI tunnel-only (+ break-glass); public surfaces unchanged.

## 8. Phase 4 — Monitoring & repo sync (the PR) — #1056 · Claude

Binding per `CLAUDE.md`: ships **together** in one PR (with the Phase-2 scripts/units and the Phase-2/3 doc updates).

1. **External deny assertion** — extend [`.github/workflows/edge-deny-probe.yml`](../.github/workflows/edge-deny-probe.yml): `https://npm.profit-base.online/` must not answer `2xx/3xx` from the internet (same shape as the Keycloak step). This is the enforcement check for "not reachable from outside"; internal blackbox cannot see it by construction.
2. **Tunnel health metric + alert** (management dependency → must be alive before SSH closes): `wg-ensure.sh` writes `wireguard_latest_handshake_age_seconds{interface="wg0"}` + `wireguard_up{interface="wg0"}` to the node-exporter textfile collector; new `WireGuardTunnelDown` alert in `monitoring/prometheus/alerts/infrastructure.yml` — `wireguard_latest_handshake_age_seconds > 300` `for: 5m` (healthy keepalive-25 handshakes are never older than ~2–3 min), severity per ops conventions, description names the break-glass paths (Hetzner console / SSH tunnel / loopback NPM). Optional stat panel on the infrastructure dashboard.
3. **Blackbox:** add `https://npm.profit-base.online` to `blackbox-http` (hairpinned v4 probe egresses as an allow-listed bridge gateway → liveness + cert expiry keep working) and `http://npm.profit-base.online` to `blackbox-force-ssl`. Keep the vhost **out of `blackbox-http-ipv6`**: the v6 hairpin egresses with the host's own public IPv6, which is *not* allow-listed → it would read as a permanent 403 failure; document this in the prometheus.yml comment (alternative, if v6 coverage is ever wanted: allow-list the host's static v6 — separate decision).
4. **Specs/docs (same PR):** ADR-0077 "Management-plane access via WireGuard VPN (UniFi gateway as server)" (alternatives: SSH-tunnel-only status quo, mesh services like Tailscale, WG-IP port bindings); `REQ-OPS-017` in `docs/specs/deployment-delivery.md` (management plane only via tunnel or documented break-glass; split tunnel; auto-reconnect after reboot of either side; no WG secrets in git/CI); `REQ-OBS-012` extension in `docs/specs/observability.md` (npm vhost joins the externally-asserted deny posture; tunnel metric+alert required); rework `docs/deployment.md` ("Management access via WireGuard VPN" — tunnel primary, SSH tunnel break-glass, Hetzner console last resort, `iri-wg-ensure` units, Phase-5 CFW rules, updated Keycloak-admin section referencing the WG address); `README.md` ops overview; `CHANGELOG.md` (German, terse). **Wiki: no change** (operator-facing, not end-user behaviour).
5. Lint gates: promtool/amtool via the ephemeral tool containers, `actionlint` for the workflow if available, `./gradlew spotlessApply` before push.

**Exit criteria:** PR merged; `WireGuardTunnelDown` proven fireable (brief `systemctl stop wg-quick@wg0` → fires → recovers); edge-deny probe green incl. the new target.

## 9. Phase 5 — Close public SSH (last step) — #1057 · Owner-approved cut-over

Precondition: Phases 2–4 verified, tunnel soaked ≥ several days incl. one host reboot, `WireGuardTunnelDown` live.

1. **Hetzner Cloud Firewall** (applies outside the VM — Docker's iptables can never bypass it): the existing **`firewall-3`** (Phase 0: inbound TCP 22 / ICMP / TCP 80 / TCP 443 from Any IPv4+IPv6, fully applied, outbound unrestricted) already default-denies everything else, so the cut-over is **deleting the TCP-22 inbound rule**. Keep 80/443 (public edge) and ICMP (diagnostics); no inbound 51820 needed (the host is the initiating client; the CFW is stateful, replies to the outbound UDP flow pass). Keep outbound unrestricted (GHCR, Nextcloud, SMTP, DynDNS, WG handshakes).
2. **Verify:** external `ssh root@178.104.94.14` times out (v4 and v6); `ssh root@192.168.3.10` via tunnel works; edge-deny workflow green; public app/ingest/keycloak/grafana unchanged; backup + deploy timers each ran once cleanly after the change.
3. **Break-glass (documented in `docs/deployment.md` by Phase 4):** ① Hetzner Cloud Console web terminal (independent of any network path); ② temporary CFW rule `22/tcp from <current-ip>/32` to recover SSH; ③ SSH-tunnel + hosts-entry path for the UIs once SSH is back. Update every doc/memory reference of routine `root@178.104.94.14` access to `root@192.168.3.10`.
4. **Rollback:** re-add the 22 rule (or detach the CFW) in the Hetzner console — no host access needed. That is the point of enforcing outside the VM.

---

## 10. Risks & mitigations

|                                      Risk                                       |                                                                    Mitigation                                                                     |
|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| Locked out of SSH (tunnel dead + 22 closed)                                     | Phase ordering (SSH last, after soak + reboot drill); `WireGuardTunnelDown` alert; Hetzner console + CFW rollback need no host access             |
| Home WAN IP changes silently                                                    | DynDNS + `iri-wg-ensure` endpoint re-resolution on stale handshake (wg-quick alone never re-resolves)                                             |
| Subnet collision (`192.168.3.0/24` vs. home VLANs, or vs. `172.28.0.0/16` pins) | Phase 0 confirms the net is free; the pinned bridge subnets are load-bearing for the `/admin` allow-list — never touch them                       |
| Compromised prod host pivots into home LAN                                      | UniFi zone rule: block VPN→internal (stateful replies only); host routes only the two AllowedIPs nets anyway                                      |
| NPM allow-list drift silently re-exposes the admin UI                           | Daily external `edge-deny-probe.yml` (extended in Phase 4) fails loudly; internal cert/liveness probes unaffected                                 |
| ACME renewal breaks on the locked vhost                                         | Allow-list lives in a custom location `/`, not server-wide; renewal dry-run in Phase 3; cert-expiry probe watches the vhost                       |
| `wg0.conf` lost on host rebuild                                                 | `REQ-OPS-010` out-of-band key backup (now for the new tunnel); restore step goes into the deployment bootstrap docs                               |
| Non-mgmt traffic sneaks into the tunnel                                         | `AllowedIPs` limited to two nets; no `DNS=`; split-DNS only for the management-only `npm.` hostname (D5/D6); verified via `ip route get`          |
| IPv6 blind spots                                                                | CFW rules cover `::/0`; npm vhost deliberately not in the v6 probe job (documented); external v6 deny covered by the workflow runner (dual-stack) |

## 11. Final acceptance checklist

- [ ] Via tunnel: SSH (`root@192.168.3.10`), NPM admin (`npm.profit-base.online`), Keycloak `/admin` (ssh -L over VPN) all reachable; certs valid.
- [ ] From the internet: npm vhost + Keycloak `/admin` deny (workflow-asserted daily); 22 filtered on v4+v6; app + realm endpoints + ingest + grafana unchanged.
- [ ] Host reboot → tunnel auto-returns; UCG reboot → tunnel auto-returns; DynDNS change → self-heals ≤ 2 min.
- [ ] `ip route get 8.8.8.8` not via wg0; backups/deploys/e-mail run unchanged.
- [ ] `WireGuardTunnelDown` fires on a provoked outage and recovers.
- [ ] ADR-0077, REQ-OPS-017, REQ-OBS-012-ext, deployment.md, README, CHANGELOG shipped; no secrets anywhere in git/CI.

