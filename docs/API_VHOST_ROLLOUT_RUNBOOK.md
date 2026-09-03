> **Doc type:** Living spec — the operator procedure for exposing `/api/v1` on its own public
> vhost. Last reviewed: 2026-08-21.
> **Status: phases A–G were executed on 2026-08-21** (D3/D4/D5). The vhost is live and the
> audience gate is closed; the nightly `edge-deny-probe` sees `/actuator` answering 404 on the host
> from outside the network. The procedure below stays as written — it is what a rebuild, a host
> move or a second environment repeats, and phase F's "merging is not enabling" warning applies
> again every time the monitoring config changes.
> **Owner area:** OPS · **Related:** [ADR-0135](adr/0135-public-api-vhost-not-a-gateway.md),
> [`ANDROID_API_EXPOSURE_PLAN.md`](ANDROID_API_EXPOSURE_PLAN.md) items **D3/D4/D5**,
> [`MONITORING_ROLLOUT_RUNBOOK.md` Appendix C](MONITORING_ROLLOUT_RUNBOOK.md),
> [`deployment.md`](deployment.md), specs `REQ-SEC-011`, `REQ-SEC-030`…`REQ-SEC-033`,
> `REQ-OBS-012`, `REQ-OBS-018`

# API vhost rollout runbook (`api.profit-base.online`)

Everything the native Android client needs on the production side, in the order it has to happen.
The backend hardening it depends on (A1–A4, A6, A8) is already on `main`; this is the part that
lives on the host, in the NPM admin database and at the DNS provider.

**Read this in full before starting.** Two steps are awkward to undo on a short timescale (a DNS
record, a CAA record) and one causes a **brief full-stack outage** (phase C). The order is not
arbitrary: phase A exists purely to stop phase C from locking you out of the tool you need for
phase D.

## 0. Who runs what

| Phase |                               What                               |                   Owner                   |            Where             |
|-------|------------------------------------------------------------------|-------------------------------------------|------------------------------|
| A     | Widen the Keycloak `/admin` allow-list                           | you                                       | NPM admin UI, via SSH tunnel |
| B     | DNS `A`/`AAAA` for the new host + `CAA` for the zone (**D4**)    | you                                       | DNS provider                 |
| C     | `net-proxy-api` network, backend + NPM join it                   | Claude prepares the PR, you merge/promote | repo → deploy loop           |
| D     | The vhost itself: proxy host, cert, allow-list, headers (**D3**) | you                                       | NPM admin UI                 |
| E     | Verify from outside (**E3**)                                     | you                                       | your workstation             |
| F     | Enable the staged probes (done — PR, pending deploy)             | Claude prepares the PR, you merge/promote | repo → deploy loop           |
| G     | Flip the audience enforcement (**D5**, release gate)             | you                                       | `/var/iri/code/.env`         |

Claude never touches production. Where a phase needs a repo change, the deliverable is a PR; the
exact host commands are below.

## 1. Preconditions

- [ ] PR **#1572** (client attribution + staged probes + ADR-0135) is merged **and promoted** — the
  staged probe config must already be on the host before phase F is a one-line change.
- [ ] A **backup** was taken and verified today: `sudo -u deploy /usr/local/bin/backup.sh`, then
  confirm the newest snapshot exists (the monitoring rollout's phase 0 does the same).
- [ ] A maintenance window is agreed for **phase C** (a few minutes of full-stack downtime).
- [ ] You can reach the NPM admin UI through the SSH tunnel (phase A's first step anyway).
- [ ] The app is **not** released yet. Nothing here is urgent; a half-finished phase D is a 404 for
  a client that does not exist.

---

## Phase A — widen the Keycloak `/admin` allow-list (no outage, ~5 min)

**Do this before phase C, not after.** The Keycloak Admin Console is locked to the operator SSH
tunnel by an nginx `allow …/deny all` that enumerates the gateways of NPM's proxy bridges
(`deployment.md` → *Keycloak Admin Console via SSH tunnel*). Tunnel traffic is SNAT'd to the gateway
of **whichever** `net-proxy-*` bridge Docker's published-port DNAT resolves to, and that choice can
move when a bridge is added. Phase C adds a fourth one. If the DNAT lands on `172.28.13.1` and the
allow-list does not contain it, you lose the admin console — and the console is where you would fix
it.

1. Open the tunnel and the NPM admin UI:

   ```bash
   ssh -N -L 10081:127.0.0.1:10081 root@178.104.94.14
   ```

   then browse to `http://127.0.0.1:10081`.

2. *Proxy Hosts → `keycloak.profit-base.online` → Edit → Custom locations → `/admin` → Advanced.*
   Add the new gateway to the existing list:

   ```nginx
   allow 172.28.3.1;   # net-proxy-frontend gateway
   allow 172.28.4.1;   # net-proxy-keycloak gateway
   allow 172.28.7.1;   # net-proxy-ingest gateway
   allow 172.28.13.1;  # net-proxy-api gateway (created in phase C)
   deny all;
   ```

   Equivalently, replace the four lines with `allow 172.28.0.0/16;` — the whole pinned range. Both
   are safe: no external client can present a `172.28.x` source over a completed TCP handshake, and
   NPM matches on `$remote_addr`, never on a client-supplied `X-Forwarded-For`.

3. Save, then reload `https://keycloak.profit-base.online/admin` through the 443 tunnel and confirm
   the console still loads. A `403` here means the edit did not save — fix it now, while the old
   gateway is still the one in use.

**Rollback:** remove the added line. Nothing else depends on it.

---

## Phase B — DNS: `A`/`AAAA` + `CAA` (D4, no outage)

At the DNS provider, not on the host.

1. **New host records** for `api.profit-base.online`, pointing at the same addresses the apex uses:

   ```bash
   dig +short profit-base.online A
   dig +short profit-base.online AAAA
   ```

   Create `api` `A` → the IPv4 above and `api` `AAAA` → the IPv6 above. Set **TTL 300** for now;
   raise it to your normal value once phase E is green.

   Both record types are required, not optional: the edge binds `[::]:443`, the monitoring plane
   probes the IPv6 path separately (`EdgeIpv6Unreachable`), and a missing `AAAA` on a dual-stack
   mobile network is a slow, confusing failure rather than a clean one.

2. **`CAA` for the zone apex** `profit-base.online` — cheap, unrelated to the app, worth doing:

   ```
   profit-base.online.  CAA  0 issue "letsencrypt.org"
   ```

   > **Read the zone first — it may already carry CAA records.** `dig +short profit-base.online CAA`
   > on 2026-08-18 returned a provider-supplied default set permitting five issuers (`comodoca`,
   > `digicert`, `letsencrypt`, `pki.goog`, `ssl.com`) for both `issue` and `issuewild`. Adding the
   > line below to that is a no-op; achieving "Let's Encrypt only" means *removing* the other four,
   > which is a deliberate narrowing rather than part of this rollout.
   >
   > **Check before you change it.** A `CAA` record makes every CA *not* listed refuse to issue. NPM is
   > the only thing that requests certificates here and it uses Let's Encrypt, so this is safe — but
   > if any certificate for this zone was ever obtained elsewhere (a mail provider, a CDN, a
   > registrar-managed cert), its renewal breaks silently at the next issuance. Confirm first.

3. Verify from your workstation:

   ```bash
   dig +short api.profit-base.online A
   dig +short api.profit-base.online AAAA
   dig +short profit-base.online CAA
   ```

   All three must answer. `api.*` must resolve **before** phase D, or the Let's Encrypt HTTP-01
   challenge in the SSL tab fails.

**Rollback:** delete the records. Removing the `CAA` record restores the previous "any CA may
issue" state.

---

## Phase C — `net-proxy-api`, backend and NPM join it (BRIEF FULL-STACK OUTAGE)

The backend sits on no proxy network today, which is exactly why it is not internet-reachable. The
vhost needs a path from NPM to `backend:11261`, and it gets a dedicated bridge rather than a
widened existing one, so no new lateral path opens.

### C.1 The change (Claude's PR)

```diff
   backend:
     networks:
       - net-db-backend
       - net-backend-frontend
       - net-backend-keycloak
       - net-backend-ingest
+      - net-proxy-api
       - net-redis-backend
       - net-monitoring-scrape

   npm:
     networks:
       - net-proxy-frontend
       - net-proxy-keycloak
       - net-proxy-ingest
       - net-proxy-grafana
+      - net-proxy-api

 networks:
+  # NPM -> backend for the public API vhost (ADR-0135). Dual-stack for the same reason
+  # net-proxy-frontend is (ADR-0112): with an IPv4-only bridge Docker installs no ip6tables DNAT and
+  # every IPv6 client arrives as the bridge gateway, which collapses the edge per-IP limiter AND
+  # feeds one shared address to the backend's client-IP attribution (REQ-SEC-011). Subnet continues
+  # the pinned 172.28.0.0/16 block (.13) — .12 is net-redis-backend, the last one taken.
+  net-proxy-api:
+    driver: bridge
+    enable_ipv6: true
+    ipam:
+      config:
+        - subnet: 172.28.13.0/24
+          gateway: 172.28.13.1
+        - subnet: fd00:28:13::/64
+          gateway: fd00:28:13::1
```

`app.rate-limit.trusted-proxies` needs **no** change: it is already `172.28.0.0/16`, which contains
the new bridge. Narrowing it further is a separate, optional PR — the pinned block is a private
range no external client can present.

### C.2 Why this one is an outage

`deploy.sh` compares the compose `networks:` block across deploys (`network_block()`), and a
topology change cannot be applied by an in-place `up -d`: Docker can neither move a running
container onto a differently-addressed bridge nor recreate a bridge that still has endpoints. An
in-place apply silently strands container name resolution — the 2026-07 incident (#974). So the
deploy takes the **whole stack** down (the app project *and* the monitoring project, which holds
shared nets as `external`), prunes, and brings it back up. Expect a few minutes of downtime, once.

### C.3 Applying it

1. Merge the PR to `main`.
2. Cut a release: *Actions → Release · Prepare* → merge the `chore(release): vX.Y.Z` PR.
3. Promote: `gh workflow run promote.yml -f version=X.Y.Z` (approve the `production` environment).
4. Either wait for the timer (≤ 5 min) or force it inside your window:

   ```bash
   sudo systemctl start iri-deploy.service
   ```
5. Watch it:

   ```bash
   sudo tail -f /var/log/iri-deploy.log
   ```

   You want the line `network topology changed -> clean recreate (brief full-stack downtime)`, then
   `app down`, then a healthy `up`. If any service is unhealthy within 180 s, `deploy.sh` restores
   the previous digest pin *and* config tree and re-ups by itself — including a second clean
   recreate, because the rollback also crosses the topology change.

6. Verify:

   ```bash
   docker network inspect net-proxy-api --format '{{range .Containers}}{{.Name}} {{.IPv4Address}} {{.IPv6Address}}{{"\n"}}{{end}}'
   docker compose -f /var/iri/code/docker-compose.yml --profile prod ps
   ```

   Expect exactly `npm` and `backend` on the network, both with an IPv4 **and** an IPv6 address, and
   every service `healthy`.

7. **Re-check the admin console through the tunnel** (phase A's insurance). If it 403s now, the DNAT
   moved to `172.28.13.1` and phase A was not applied — fix it in the NPM UI, which you can still
   reach because the UI itself is on `127.0.0.1:10081`, not behind the `/admin` allow-list.

**Rollback:** revert the PR, re-release, re-promote. The stack crosses the topology change a second
time (another brief outage).

---

## Phase D — the vhost (D3)

NPM admin UI (`http://127.0.0.1:10081` through the tunnel) → *Proxy Hosts → Add Proxy Host*.

### D.1 Details tab

|         Field         |                         Value                          |
|-----------------------|--------------------------------------------------------|
| Domain Names          | `api.profit-base.online`                               |
| Scheme                | **https**                                              |
| Forward Hostname      | `backend`                                              |
| Forward Port          | **11261**                                              |
| Cache Assets          | off                                                    |
| Block Common Exploits | on                                                     |
| Websockets Support    | off (the app uses SSE over plain HTTP, not `/ws/sync`) |

nginx does not verify the upstream certificate by default, so the backend's self-signed
basetool-CA cert is accepted with no extra toggle — the same arrangement the Keycloak host has used
since it moved to `18443`.

### D.2 SSL tab

Request a **new Let's Encrypt certificate** for `api.profit-base.online`, then enable **Force SSL**,
**HTTP/2** and **HSTS**. Phase B must already be resolving, or the challenge fails.

### D.3 Advanced (server-level custom config)

The default-deny allow-list, the `X-Forwarded-*` overwrite and the `/actuator` deny, in one block.
Paste it into the proxy host's **Advanced** field:

```nginx
# --- Default-deny allow-list (ADR-0135) --------------------------------------
# The API vhost proxies ONLY the endpoint families the app consumes and answers 404 for everything
# else. A blocklist was rejected: the anonymous surface is branchy, and any future permitAll
# endpoint added for the web app would otherwise become internet-reachable the day it merges.
#
# Matched on $uri — the DECODED, normalised path nginx itself routes on — not on $request_uri.
# Matching the raw form would give the edge and the application two different views of the same
# request, which is the class of bug REQ-SEC-029 exists to prevent.
#
# Grow this list one app phase at a time, together with the REQ-API-009 contract set and the
# ExternalContractTest entry — opening a family to the app and freezing its shape are the same
# decision seen from two sides.
#
# EXACT paths under /api/v1/users/me, never the prefix: /api/v1/users/me/** would carry the payout
# preference, the blueprint-sharing flag and the description write along with the reads the app
# actually makes.
set $krt_api_allowed 0;
if ($uri ~ "^/api/v1/terms/")  { set $krt_api_allowed 1; }   # terms status + acceptance
if ($uri ~ "^/api/v1/me/")     { set $krt_api_allowed 1; }   # active-org-unit, capabilities
if ($uri = "/api/v1/users/me/registration-status") { set $krt_api_allowed 1; }
# Phase 2 - the caller's own record, for its `id` alone: an Operation's payout rows are keyed by
# the backend user id, so "Dein Anteil" cannot be located without it. EXACT path again, never the
# /api/v1/users/ prefix, which would reach every other member's record.
if ($uri = "/api/v1/users/me") { set $krt_api_allowed 1; }
# Phase 2 — the org-unit switcher. Deliberately NOT /api/v1/users/{id}/memberships: that one can
# name another user, and this vhost is default-deny so that such a path never has to be on it.
if ($uri = "/api/v1/users/me/memberships") { set $krt_api_allowed 1; }
# Phase 2 — the Einsatz list. EXACT path, not the /api/v1/missions/ prefix: this allow-list matches
# on the path and cannot see the verb, so a prefix would open every write under the family
# (create, update, delete, participants, finance entries) the day it is pasted. The backend's
# @PreAuthorize would still refuse them, but the vhost exists so that a second layer does not have
# to be the only one. The app consumes /search alone today; the detail path joins this list when
# the detail screen actually ships — one app phase at a time, as the note above says.
if ($uri = "/api/v1/missions/search") { set $krt_api_allowed 1; }
# Phase 2 - the Einsatz detail and its Finanzen tab. UUID-shaped and $-ANCHORED, because the
# family below `{id}` is dense with writes: /join, /participants/**, /steps/**, /units/**,
# /objectives/**, /party-lead, /owner, ... An unanchored prefix would admit every one of them.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/finance-entries$") { set $krt_api_allowed 1; }
# Phase 3 - the four things a member does to their own participation. The participant paths name a
# SECOND uuid, the participant row, and the backend's canAccessParticipant is what decides whether
# it is the caller's: the vhost does not need to tell them apart. The leave is the SLIM delete, not
# the legacy full one - that pair is deprecated with a sunset already announced.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/join$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/participants/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/participants/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(check-in|check-out|payout-preference)/slim$") { set $krt_api_allowed 1; }
# Phase 3 - booking money against an Einsatz. A family of its own: the write paths are
# /api/v1/finance-entries, NOT under /missions, so nothing in the read-only guard's prefix list
# matches them and every verb the backend serves is admitted. That is the same shape
# /personal-inventory has, and it is safe for the same reason - the whole prefix is the feature.
if ($uri = "/api/v1/finance-entries") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/finance-entries/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# The payout confirmation. /operations stays a read-only family: the prefix carries the whole
# Operation edit surface, and only this one path is named.
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/payouts/paid-out$") { set $krt_api_allowed 1; }
# Phase 3 - the only bank writes a MEMBER has: the settings of an account they are responsible
# for. The staff surface is admitted separately in phase L, and the direct booking forms
# (deposits, withdrawals, transfers) are not admitted there either - no artboard draws them.
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/settings$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/balance-target$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/visibility/role/[A-Za-z0-9_-]{1,64}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/visibility/all-members/(true|false)$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/finance-entries/summary$") { set $krt_api_allowed 1; }
# Phase 2 - the Operationen segment of the same screen, its detail, the Finanz-Rollup and the
# payout rows. Anchored like the Einsatz detail and for a sharper reason: below `{id}` sits
# `/payouts/paid-out`, a PUT that marks a member as paid. `/payouts$` is the read; the write is a
# different path and stays off this list until phase 3 ships the manager toggle.
if ($uri = "/api/v1/operations/search") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/finance-summary$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/payouts$") { set $krt_api_allowed 1; }
# Phase 2 - the notification inbox, its badge count and its push stream. EXACT paths.
if ($uri = "/api/v1/notifications") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/notifications/unread-count") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/notifications/stream") { set $krt_api_allowed 1; }
# Phase 5 - the inbox's mutating half, which phase 2 deliberately left off this list. All four
# are me-scoped: the backend resolves the caller's own notifications and a member cannot name
# somebody else's row. `/read-all` and `/read` are literal segments beside `{id}`, which is a
# [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12} - they cannot collide, so naming the three by name does not admit the family.
# Each one ALSO needs the carve-out below: this list matches on the path and cannot see the verb.
if ($uri = "/api/v1/notifications/read-all") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/notifications/read") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/notifications/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/notifications/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/read$") { set $krt_api_allowed 1; }
# Phase 2 - the dashboard's announcement band. EXACT path: /api/v1/announcement/admin is
# the admin read of the same row and /api/v1/announcement itself also answers PUT, so a
# prefix would carry both onto a vhost that exists to keep them off it.
if ($uri = "/api/v1/announcement") { set $krt_api_allowed 1; }
# Phase 2 - the Hangar. EXACT paths again: the family also carries /hangar/ships (a
# permission-gated read of EVERY member's ships), /hangar/users/{id}/ships (admin) and the
# create/update/delete verbs. Only the caller's own list and the org aggregate belong here.
if ($uri = "/api/v1/hangar/my-ships") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/hangar/squadron-overview") { set $krt_api_allowed 1; }
# Phase 2 - the Lager tree. Two levels, two exact paths. NOT /api/v1/inventory/all, which
# is the flat entry list, and not the booking paths beside them.
if ($uri = "/api/v1/inventory/aggregated") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/inventory/all/grouped") { set $krt_api_allowed 1; }
# Phase 2 - the Auftraege queue and one order. The queue path is EXACT: the same path
# answers POST, and that POST is permitAll in the chain (the public request form). The
# read-only guard below refuses it here, and the allow-list never names it.
if ($uri = "/api/v1/orders") { set $krt_api_allowed 1; }
# Phase 5 - the two unit pickers on the app's „Neuer Auftrag" form. GET only, and it is already
# permitAll in the backend chain for exactly this reason: the public request form's pickers have
# to be fillable anonymously. The payload carries no PII - name, shorthand, kind and the profit
# flag.
if ($uri = "/api/v1/org-units/active-all-kinds") { set $krt_api_allowed 1; }
# Phase 5 - the item order's three paths, the other half of the same „Neuer Auftrag" form. The
# catalogue and its blueprints are GET-only reads of the game catalogue: no member data, no org
# data, and both require a login since ADR-0149. The create is an exact match, so it opens the
# collection POST and nothing beneath it - the same shape /api/v1/orders has, and for the same
# reason. They join the same paste as the unit pickers above; one application covers both.
#
# Neither catalogue line is reachable through the /orders/<uuid> pattern above: "item-catalog" and
# "items" are not uuids, so the two families cannot collide however they are ordered.
if ($uri = "/api/v1/orders/item-catalog") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/item-catalog/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/blueprints$") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/orders/items") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# Phase 3 - the two things a member does to an order they can see: put their own name on it and
# write the note that says which part they take. Plus the status change, which is LOGISTICIAN-only
# in the chain. The assignee paths name a SECOND uuid - the member - and that is deliberate: the
# backend refuses anyone but yourself unless you are a Logistician, so the vhost does not need to
# tell them apart.
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/assignees/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/assignees/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/note$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/status$") { set $krt_api_allowed 1; }
# Phase 2 - the org bank a member may see. /org-units/bank/** answers with the accounts THIS
# caller may see; /bank/accounts/** is the staff surface and lists every account in the
# organisation. The latter is admitted separately in phase L, path by path.
if ($uri = "/api/v1/org-units/bank/balances") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/transactions$") { set $krt_api_allowed 1; }
# Phase 3 - "Mein Inventar", the member's own stock. The FIRST writes on this vhost: the two
# paths below answer POST / PUT / DELETE as well as GET, and the read-only guard further down
# names them explicitly rather than opening the family. Me-scoped by the service - neither path
# can name another member - so nothing here widens who a caller can reach.
# Phase 4: the app's live-sync bridge (ADR-0143, REQ-FE-019). Two paths and no prefix wildcard --
# `live-sync` is NOT in the read-only family list below, so the POST is admitted by being named
# here and nothing else under the stem is reachable at all.
# Phase 4: Beforderung. Two me-scoped reads and no id in either path, so no uuid group is needed
# -- and `promotion` is NOT in the read-only family list, which is safe here because these two are
# the only paths of that stem this vhost admits at all.
if ($uri = "/api/v1/promotion/evaluations/my") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/promotion/eligibility/my") { set $krt_api_allowed 1; }
# Phase 4: the served-version floor the forced-update gate reads (REQ-API-010). The ONLY anonymous
# path on this vhost, decided by the owner on 2026-08-24 -- an app too old to authenticate must
# still be able to learn that it is too old. It answers 200 without a token BY DESIGN; a 401 here
# is the broken state, not the hardened one.
if ($uri = "/api/v1/app/version-policy") { set $krt_api_allowed 1; }
# Phase 4: Raffinerie. `refinery-orders` is NOT in the read-only family list, so the booking POST
# is admitted by being named and nothing else under the stem -- not /all, not /users/<id>, not the
# create -- is reachable at all. That is the same choice `live-sync` made, and it is available here
# for the same reason: the app touches three of that controller's eleven paths.
if ($uri = "/api/v1/refinery-orders/my-orders") { set $krt_api_allowed 1; }
# Phase M - the app can now record a run itself (app REQ-APP-REF-009). The bare stem is the create
# POST; `refinery-orders` is NOT in the read-only family, so naming it opens the verbs the backend
# serves there - which for the stem is exactly POST plus the caller-scoped GET. The two picker reads
# below are mandatory fields of that form: without them the member has nothing to choose from.
if ($uri = "/api/v1/refinery-orders") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/locations/refineries") { set $krt_api_allowed 1; }
# Read-only since 2026-09-02, and this one is load-bearing. `POST /api/v1/refining-methods` carries
# a bare `hasRole('ADMIN')` and was the ONLY allow-listed path that did. That cost nothing while the
# mobile client's token could not be ADMIN; the REQ-SEC-035 reversal made it an internet-reachable
# admin write, so `refining-methods` joined the read-only family above. The app only ever GETs it
# (the refinery form's method picker), and the web admin does not come through this vhost at all -
# the frontend calls the backend over the docker network - so nothing loses a capability.
if ($uri = "/api/v1/refining-methods") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/refinery-orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/refinery-orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/store$") { set $krt_api_allowed 1; }
# Phase 4: Materialboerse. Same stance -- neither `material-exchange` nor `material-requests` is a
# read-only family, so every write below is admitted by name. The item creates (/item-offers,
# /material-requests/item) are deliberately NOT here: the app cannot send a P4K productKey and has
# no picker for one.
if ($uri = "/api/v1/material-exchange/offers") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/material-exchange/releasable-items") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/material-exchange/offers/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(interest|deactivate)$") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/material-requests") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/material-requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(interest|deactivate)$") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/live-sync/stream") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/live-sync/changed") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/personal-inventory") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/personal-inventory/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# Phase 3 - the location picker behind that editor. Read-only, and deliberately NOT the
# /api/v1/locations family beside it, which carries the org's own places and their writes.
if ($uri = "/api/v1/uex/locations/search") { set $krt_api_allowed 1; }
# Phase 3 - the Blueprints half of "Mein Inventar". Writes again, and me-scoped again: the two
# paths below carry only the caller's own owned-blueprint rows. NOT the /overview family beside
# them, which lists who else owns what, and NOT the /import pair, which is phase 4.
if ($uri = "/api/v1/personal-blueprints") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/personal-blueprints/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# Phase 3 - the craftability read behind the chip, and the product picker behind "hinzufuegen".
# EXACT paths: /blueprints/** carries the whole catalogue and stays off this vhost.
if ($uri = "/api/v1/personal-blueprints/craftability") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/blueprints/products/search") { set $krt_api_allowed 1; }
# Phase 5 - the recipe of ONE owned blueprint, which design ch. 09's tablet master-detail reads.
# The comment above used to say this path stays off "until something actually reads them"; the
# Blueprints detail pane now does. Still me-scoped and still a single row: the backend resolves
# {id} against the caller's own owned blueprints, so it cannot name somebody else's.
if ($uri ~ "^/api/v1/personal-blueprints/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/recipe$") { set $krt_api_allowed 1; }
# Phase 3 - the member's own ships. EXACT paths, and deliberately NOT /hangar/users/<uuid>/ships:
# that one names a member and is the admin surface. /hangar/ships answers a bulk DELETE with no
# id as well, which the carve-out below does not open - only the per-ship verbs are named.
if ($uri = "/api/v1/hangar/ships") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/hangar/ships/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# Phase 3 - the two pickers the ship editor needs. Read-only families; /ship-types also answers a
# PUT on /{id}/visibility, which the read-only guard refuses by verb.
if ($uri = "/api/v1/ship-types") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/locations/home-locations") { set $krt_api_allowed 1; }
# Phase 3 - the Lager's three bookings. EXACT paths: the /inventory prefix also carries
# /inventory/all (every member's entries), the two bulk endpoints and the allocation family, none
# of which this vhost admits. The per-entry paths are gated by canEditInventoryItem, which is what
# keeps a member to their own stock and their unit's.
if ($uri = "/api/v1/inventory") { set $krt_api_allowed 1; }
# Phase 3 - the entry level of the tree. A member cannot book out what they cannot select, and the
# two levels phase 2 admitted stop at the stack. Read-only, and NOT /inventory/all beside it.
if ($uri = "/api/v1/inventory/all/stack/entries") { set $krt_api_allowed 1; }
# Phase 5 - one material's entries, flat and paged: the app's tablet detail pane, and the same read
# the web's /inventory/material/{id} page makes. GET only, and scoped by the chain exactly as the
# tree's own reads are - the pane shows what the caller may already see in the tree, laid out
# differently. UUID-shaped and $-anchored, so nothing beneath it is opened.
if ($uri ~ "^/api/v1/inventory/material/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/inventory/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(book-out|personal-rebook|note)$") { set $krt_api_allowed 1; }
# Phase 3 - the four pickers the booking form needs. All reads.
if ($uri = "/api/v1/materials/search") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/locations/search") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/users/search") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/materials/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/terminals$") { set $krt_api_allowed 1; }
# Phase 5 - the member's bank booking requests (app REQ-APP-BANK-008). Still /org-units/bank/**
# and never /bank/**: confirming and rejecting are BANK_EMPLOYEE acts on the surface the app does
# not carry. `requests` and `requests/foreign` are literal; the four id-bearing paths carry the
# request's uuid. The three writes are cleared out of the read-only family further down, one path
# at a time, because `/org-units` also carries the org-unit admin surface.
if ($uri = "/api/v1/org-units/bank/requests") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/org-units/bank/requests/foreign") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/org-units/bank/transfer-targets") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/cancel$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/org-units/bank/requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/owner-approval$") { set $krt_api_allowed 1; }
# Phase 5 - one member's org-unit memberships, which the Lager's Umbuchen picker needs for the
# DESTINATION member rather than the caller, so /users/me/memberships cannot serve it. A GET the
# backend gates on any member and documents as carrying no personally identifying data - no display
# name, no email, no rank - which is the same stance as /users/search two lines up. NOT cleared out
# of the read-only family: the PATCH the backend serves on this very path stays 405 here.
if ($uri ~ "^/api/v1/users/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/memberships$") { set $krt_api_allowed 1; }
# Phase L - the bank-STAFF surface (app REQ-APP-BANK-007, amended 2026-08-27). This is the first
# time /api/v1/bank/** is admitted at all, and the scope is what design chapter 12 draws in
# artboards 4-8. `bank` is NOT in the read-only family below, so naming a path opens every verb the
# backend serves on it - GET+PATCH on an account, GET+PATCH on a holder, PATCH+DELETE on a grant.
# That is deliberate and it is safe only because this list defaults to 404: nothing under /bank is
# reachable that is not named here.
#
# THREE THINGS STAY OUT, and each for its own reason:
#   * /api/v1/bank/admin/** - wipe-reset and the bank audit log. The admin area is web-only by
#     owner decision and is never named below, so it keeps answering 404.
#   * /bank/deposits, /bank/withdrawals, /bank/transfers, /bank/transfer-fee-rate - the direct
#     booking forms. No artboard draws them; a booking that had no request stays a browser act.
#   * /bank/accounts/<uuid>/approval-tiers and /balance-target - the KRT ladder editor is not one
#     of the app's four tabs, and the balance target is already reachable on the member surface.
if ($uri = "/api/v1/bank/dashboard") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/accounts") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(close|reopen|transactions|statement)$") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/requests") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/bank/requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(confirm|reject)$") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/grants") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/bank/grants/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/holders") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/holders/transfer") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/bank/holders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/bank/holders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/transactions$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/bank/transactions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/reversal$") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/export/three-month-report") { set $krt_api_allowed 1; }
# Phase L - the grantee picker of artboard 7's create modal. The bank twin of /users/search two
# groups up, and the app must use THIS one: the two run the same query over the same scope with the
# same peer-redacted projection, and differ only in the role gate, which here is widened to
# BANK_EMPLOYEE (so BANK_MANAGEMENT via the hierarchy). A bank manager who holds no org role gets
# 403 on /users/search and would have no picker at all. Read-only.
if ($uri = "/api/v1/users/search-bank") { set $krt_api_allowed 1; }
if ($krt_api_allowed = 0) { return 404; }

# --- The missions and operations families are READ-ONLY on this vhost ---------
# Anchoring keeps the sub-paths out, but `/api/v1/missions/<uuid>` and `/api/v1/operations/<uuid>`
# themselves also answer PUT and DELETE, and the allow-list matches on the path alone - it cannot
# see the verb. The backend's @PreAuthorize refuses them; this vhost exists so that it does not
# have to be the only layer. Two flags because nginx cannot nest `if`; the concatenation is the
# standard idiom.
#
# Phase 3 brings the first legitimate write - PUT /api/v1/operations/<uuid>/payouts/paid-out for a
# mission manager. It needs a carve-out HERE as well as an allow-list entry, and the carve-out is
# the harder half: this guard is verb-blind by design, so opening one write means naming it
# explicitly rather than widening the family.
set $krt_readonly_family "";
if ($uri ~ "^/api/v1/(missions|operations|notifications|announcement|hangar|inventory|orders|org-units|uex|blueprints|ship-types|locations|materials|users|refining-methods)") { set $krt_readonly_family "R"; }
if ($request_method !~ "^(GET|HEAD)$") { set $krt_readonly_family "${krt_readonly_family}W"; }
# Named exceptions: the writes phase 3 opens INSIDE a read-only family. Each one clears the verdict
# before it is judged, which is the only shape nginx allows - it cannot nest `if`, so an exception
# has to erase the flag rather than qualify it.
#
# /hangar stays in the family above because /hangar/users/<uuid>/ships (the admin surface, which
# names a member) and the two /import paths (phase 4) live under the same prefix. None of the three
# is admitted today, so the deny above answers them 404 before this guard runs at all - what the
# family membership buys is the day one of them IS admitted: it is then 405 rather than open, and
# opening it has to be a named carve-out. Only the two own-ship paths are named here, and naming a
# path opens EVERY verb the backend serves on it - which for these is POST, PUT and DELETE, each
# gated by @PreAuthorize.
if ($uri = "/api/v1/hangar/ships") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/hangar/ships/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_readonly_family ""; }
# /inventory stays in the family because the prefix also carries /inventory/all, the two bulk
# endpoints and the allocation family. Only the three per-entry bookings and the create are named.
if ($uri = "/api/v1/inventory") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/inventory/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(book-out|personal-rebook|note)$") { set $krt_readonly_family ""; }
# /orders stays in the family because the prefix also carries the handovers, the production
# reports and the whole Logistician edit surface. Only the assignee edge, the status change and
# the create are named.
#
# The create used to be the one path where only the VERB separated two surfaces: POST
# /api/v1/orders was permitAll, the public request form, and this guard was what kept it off this
# host. It requires a login since ADR-0149, so the two surfaces no longer differ in kind and the
# exact-match line below opens the collection POST and nothing beneath it.
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/assignees/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(/note)?$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/status$") { set $krt_readonly_family ""; }
if ($uri = "/api/v1/orders") { set $krt_readonly_family ""; }
# The item order's create, the same shape as the material one. The two catalogue reads above are
# GET and need no carve-out; naming them here would open a POST the backend does not serve.
if ($uri = "/api/v1/orders/items") { set $krt_readonly_family ""; }
# /notifications stays in the family because the prefix also carries /notification-rules, the
# admin surface that creates and deletes the rules every member's inbox is generated from. Only
# the four me-scoped inbox mutations are named. Naming a path opens every verb the backend serves
# on it, which for `/{id}` is GET and DELETE and for the other three is exactly one POST or DELETE.
if ($uri = "/api/v1/notifications/read-all") { set $krt_readonly_family ""; }
if ($uri = "/api/v1/notifications/read") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/notifications/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/notifications/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/read$") { set $krt_readonly_family ""; }
# /missions stays in the family because the prefix carries the whole planning surface - units,
# crews, steps, objectives, the mission itself. Only the caller's own participation is named.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/join$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/participants/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(/(check-in|check-out|payout-preference))?/slim$") { set $krt_readonly_family ""; }
# /operations stays in the family because the prefix carries the whole Operation edit surface.
# Only the payout confirmation is named.
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/payouts/paid-out$") { set $krt_readonly_family ""; }
# /org-units stays in the family: the prefix carries the org-unit admin surface as well. Only the
# three account-settings writes are named.
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/balance-target$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/visibility/(role/[A-Za-z0-9_-]{1,64}|all-members/(true|false))$") { set $krt_readonly_family ""; }
# The three booking-request writes. POST /requests raises one and POST /requests/{id}/cancel
# withdraws it; PUT /requests/{id} corrects it, which is why the bare id path is named; the
# owner-approval path answers POST and DELETE and needs both. Naming a path opens every verb the
# backend serves on it, and on each of these that is exactly the set above.
if ($uri = "/api/v1/org-units/bank/requests") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/org-units/bank/requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/org-units/bank/requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(cancel|owner-approval)$") { set $krt_readonly_family ""; }
if ($krt_readonly_family = "RW") { return 405; }

# --- Which family gets which shape ---------------------------------------------------------------
# Two shapes, and the choice is decided by what else lives under a prefix:
#
#   * a WRITE FAMILY - admitted by path, every verb allowed, gated by the backend's @PreAuthorize.
#     /personal-inventory and /personal-blueprints are these: two me-scoped paths each, and nothing
#     else under the prefix that has to stay out.
#   * a READ-ONLY FAMILY with named exceptions - listed in the guard above, writes cleared one path
#     at a time. /hangar is this, because its prefix also carries the admin surface and the imports.
#
# /missions will be the second of the two: PUT /missions/<uuid> edits an Einsatz for everyone, and
# phase 3 opens only the signup and check-in beneath it.

# --- Actuator: second layer --------------------------------------------------
# The backend already serves no Actuator on 11261 since ADR-0134 (it moved to the internal-only
# management port 11271), so this cannot currently leak. It stays because the guarantee should not
# depend on one config file, and because REQ-OBS-012's probes assert it from inside and outside.
location /actuator { return 404; }

# --- Forwarded headers: OVERWRITE, never append ------------------------------
# REQ-SEC-011: the backend walks the X-Forwarded-For chain right-to-left and trusts only the pinned
# compose range. NPM's default is $proxy_add_x_forwarded_for, which APPENDS to whatever the client
# sent — so a client-supplied chain would survive into the value the backend parses. Overwriting
# with the real peer leaves exactly one element and makes the walk trivially correct.
proxy_set_header X-Forwarded-For   $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host  $host;
proxy_set_header X-Forwarded-Port  443;
proxy_set_header Forwarded         "";

# --- Body size ---------------------------------------------------------------
# No upload path is on the allow-list yet. Raise it per location when the hangar/refinery import
# families are opened, never globally.
client_max_body_size 256k;
```

### D.3a Growing the allow-list after the vhost is live

**The block above is the source of truth; NPM holds a copy.** Adding a path here changes nothing in
production until the whole Advanced block is pasted back into the proxy host and saved — there is no
reconcile job and no drift alarm for it, so an app release whose new screen 404s against a vhost that
was never updated is a failure mode with no signal at all.

The safe order, and the reason for it:

1. Merge the path here **and** into the `REQ-API-009` contract set + `ExternalContractTest` in the
   same PR. Opening a family to the app and freezing its shape are the same decision seen from two
   sides.
2. Paste the block into **Clients → the proxy host → Advanced**, save.
3. Verify from outside, not from the host — a hairpinned request does not prove what a phone
   sees:

   ```bash
   curl -s -o /dev/null -w '%{http_code}' https://api.profit-base.online/api/v1/missions/search; echo
   ```

   **The expected status is per path, not one number for all of them.** An allow-listed path
   inherits whatever the backend requires of it, and that is deliberately not uniform — several
   paths are anonymous by design, and several more are refused at the method seam with `403`
   rather than `401` (REQ-SEC-037). Reading "401 is the pass" off a `permitAll` path produces a
   false alarm, which is exactly what happened the first time this step was run — and again on
   phase M's two pickers, written down as `401` when the backend has always answered `403` and
   `200`. Corrected 2026-08-31, after three nights of a red probe against a correctly pasted
   vhost.

   |                         Path                          |                           Without a token                           |                                                   Why                                                    |
   |-------------------------------------------------------|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
   | `/api/v1/terms/document`                              | **200**                                                             | anonymous by design (ADR-0138): wording that must be read *before* agreeing cannot require having agreed |
   | `/api/v1/missions/search`                             | **200**                                                             | anonymous by design: the public home page already renders its guest-redacted rows                        |
   | `/api/v1/missions/<uuid>`                             | **200** for a non-internal, non-terminal Einsatz, **403** otherwise | anonymous by design, redacted per ADR-0034                                                               |
   | `/api/v1/missions/<uuid>` with `PUT`/`DELETE`         | **405**                                                             | the family is read-only on this vhost                                                                    |
   | `/api/v1/missions/<uuid>/finance-entries`             | **403**                                                             | member-or-above **and** `canSeeMission`, refused at the method seam — see below                          |
   | `/api/v1/missions/<uuid>/finance-entries/summary`     | **403**                                                             | member-or-above **and** `canSeeMission`, refused at the method seam — see below                          |
   | `/api/v1/operations/search`                           | **401**                                                             | `isAuthenticated()`, and no chain matcher makes it public                                                |
   | `/api/v1/operations/<uuid>`                           | **401**                                                             | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>/finance-summary`           | **401**                                                             | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>/payouts`                   | **401**                                                             | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>` with `PUT`/`DELETE`       | **405**                                                             | the family is read-only on this vhost                                                                    |
   | `/api/v1/terms/status`                                | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/terms/acceptance` (POST)                     | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/me/active-org-unit`                          | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/me/capabilities`                             | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/inventory/aggregated`                        | **401**                                                             | chain requires a member role                                                                             |
   | `/api/v1/inventory/all/grouped`                       | **401**                                                             | same                                                                                                     |
   | `/api/v1/orders`                                      | **401**                                                             | `isAuthenticated()`; the `POST` on the same path is refused by the read-only guard                       |
   | `/api/v1/orders/item-catalog`                         | **401**                                                             | `isAuthenticated()` since ADR-0149; the orderable finished items                                         |
   | `/api/v1/orders/item-catalog/<uuid>/blueprints`       | **401**                                                             | same; the blueprints one item may be built from                                                          |
   | `/api/v1/orders/items` (POST)                         | **401**                                                             | `isAuthenticated()`; raises an item order                                                                |
   | `/api/v1/orders/<uuid>`                               | **401**                                                             | `isAuthenticated()` + scope                                                                              |
   | `/api/v1/orders/<uuid>/assignees/<uuid>`              | **401**                                                             | `isAuthenticated()` + scope; self-assignment is open to every member, anyone else needs LOGISTICIAN      |
   | `/api/v1/orders/<uuid>/assignees/<uuid>/note`         | **401**                                                             | same, and locked on the assignee edge's own version                                                      |
   | `/api/v1/orders/<uuid>/status`                        | **401**                                                             | `hasRole(LOGISTICIAN)` + per-order scope; a member without the role gets **403** once authenticated      |
   | `/api/v1/missions/<uuid>/join`                        | **401**                                                             | `isAuthenticated()` + `canSeeMission`                                                                    |
   | `/api/v1/missions/<uuid>/participants/<uuid>/slim`    | **401**                                                             | `canAccessParticipant` — the caller's own row, or a mission manager's                                    |
   | `…/participants/<uuid>/check-in/slim`                 | **401**                                                             | same                                                                                                     |
   | `…/participants/<uuid>/payout-preference/slim`        | **401**                                                             | same                                                                                                     |
   | `/api/v1/finance-entries`                             | **401**                                                             | `isAuthenticated()` + member-or-above + `canSeeMission` on the body's mission                            |
   | `/api/v1/finance-entries/<uuid>`                      | **401**                                                             | `isAuthenticated()`; owner-vs-admin is decided at the service seam                                       |
   | `/api/v1/operations/<uuid>/payouts/paid-out`          | **401**                                                             | `hasRole(MISSION_MANAGER)` + scope; taking a confirmation back additionally needs OFFICER or ADMIN       |
   | `…/org-units/bank/accounts/<uuid>/settings`           | **401**                                                             | `isAuthenticated()`; what the caller may change is stated in the answer, not in the chain                |
   | `…/bank/accounts/<uuid>/balance-target`               | **401**                                                             | `isAuthenticated()` + the responsible-holder seam                                                        |
   | `…/bank/accounts/<uuid>/visibility/…`                 | **401**                                                             | same                                                                                                     |
   | `/api/v1/org-units/bank/balances`                     | **401**                                                             | me-scoped to the accounts the caller may see                                                             |
   | `/api/v1/org-units/bank/accounts/<uuid>`              | **401**                                                             | same                                                                                                     |
   | `/api/v1/personal-inventory`                          | **401**                                                             | me-scoped; the same path answers POST, which is 401 anonymously too                                      |
   | `/api/v1/personal-inventory/<uuid>`                   | **401**                                                             | me-scoped; PUT and DELETE likewise                                                                       |
   | `/api/v1/uex/locations/search`                        | **401**                                                             | `isAuthenticated()`                                                                                      |
   | `/api/v1/personal-blueprints`                         | **401**                                                             | me-scoped; POST likewise                                                                                 |
   | `/api/v1/personal-blueprints/<uuid>`                  | **401**                                                             | me-scoped; PUT and DELETE likewise                                                                       |
   | `/api/v1/personal-blueprints/craftability`            | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/personal-blueprints/<uuid>/recipe`           | **401**                                                             | me-scoped; GET only, phase 5                                                                             |
   | `/api/v1/blueprints/products/search`                  | **401**                                                             | `isAuthenticated()`                                                                                      |
   | `/api/v1/hangar/ships`                                | **401**                                                             | me-scoped; POST likewise                                                                                 |
   | `/api/v1/hangar/ships/<uuid>`                         | **401**                                                             | me-scoped; PUT and DELETE likewise                                                                       |
   | `/api/v1/ship-types`                                  | **200**                                                             | anonymous by design: `permitAll` game data the public web frontend already renders (REQ-SEC-037)         |
   | `/api/v1/locations/home-locations`                    | **403**                                                             | `permitAll` chain + method guard — refused at the method seam, like the Finanzen paths                   |
   | `/api/v1/inventory` (POST)                            | **401**                                                             | chain requires a member role                                                                             |
   | `/api/v1/inventory/all/stack/entries`                 | **401**                                                             | chain requires a member role                                                                             |
   | `/api/v1/inventory/material/<uuid>`                   | **401**                                                             | same; one material's entries, for the app's tablet pane                                                  |
   | `/api/v1/inventory/<uuid>/book-out`                   | **401**                                                             | same, plus `canEditInventoryItem`                                                                        |
   | `/api/v1/inventory/<uuid>/personal-rebook`            | **401**                                                             | same                                                                                                     |
   | `/api/v1/inventory/<uuid>/note`                       | **401**                                                             | same                                                                                                     |
   | `/api/v1/materials/search`                            | **200**                                                             | anonymous by design: `permitAll` catalogue, like `/ship-types` (REQ-SEC-037)                             |
   | `/api/v1/locations/search`                            | **200**                                                             | same catalogue family                                                                                    |
   | `/api/v1/users/search`                                | **401**                                                             | member records; `isAuthenticated()` and role-gated                                                       |
   | `/api/v1/materials/<uuid>/terminals`                  | **401**                                                             | carved back **out** of the catalogue family with `/materials/matrix` (REQ-SEC-032) — UEX trade prices    |
   | `/api/v1/org-units/bank/accounts/<uuid>/transactions` | **401**                                                             | same                                                                                                     |
   | `/api/v1/hangar/my-ships`                             | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/hangar/squadron-overview`                    | **401**                                                             | scoped to the active org unit                                                                            |
   | `/api/v1/announcement`                                | **401**                                                             | no chain matcher makes it public                                                                         |
   | `/api/v1/notifications`                               | **401**                                                             | me-scoped inbox                                                                                          |
   | `/api/v1/notifications/unread-count`                  | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/notifications/stream`                        | **401**                                                             | me-scoped SSE                                                                                            |
   | `/api/v1/notifications/read-all`                      | **401**                                                             | me-scoped write; POST only, phase 5                                                                      |
   | `/api/v1/notifications/read`                          | **401**                                                             | me-scoped write; DELETE only, phase 5                                                                    |
   | `/api/v1/notifications/<uuid>`                        | **401**                                                             | me-scoped; GET + DELETE, phase 5                                                                         |
   | `/api/v1/notifications/<uuid>/read`                   | **401**                                                             | me-scoped write; POST only, phase 5                                                                      |
   | `/api/v1/notification-rules`                          | **404**                                                             | admin surface, NOT admitted — the four rows above must not have widened the prefix                       |
   | `/api/v1/users/me`                                    | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/users/me/registration-status`                | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/users/me/memberships`                        | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/refinery-orders` (POST)                      | **401**                                                             | phase M; `hasRole(KRT_MEMBER)` — the create form's write                                                 |
   | `/api/v1/locations/refineries`                        | **403**                                                             | phase M; `permitAll` chain + method guard, refused at the method seam like `/locations/home-locations`   |
   | `/api/v1/refining-methods`                            | **200**                                                             | phase M; anonymous master-data catalogue with no method gate, like `/ship-types` (REQ-SEC-037)           |
   | anything not on the list                              | **404**                                                             | default deny                                                                                             |

   **Two refusals, two numbers, and the difference is structural rather than a policy gap.** The
   me-scoped paths are `authenticated()` in the filter chain, so Spring Security turns them away
   before the dispatch and the entry point writes `401`. The Finanzen paths sit under `GET
   /api/v1/missions/**`, which is `permitAll` in that same chain — the request reaches the
   controller, `@PreAuthorize` refuses it there, and `GlobalExceptionHandler` renders the refusal as
   `403`. Nothing upgrades it to `401`, because `ExceptionTranslationFilter` — the one component
   that would — never sees an exception the MVC advice has already handled. Both callers are
   refused identically; only the number differs, and it is the number an operator reads off this
   table. `ApiVhostAnonymousSurfaceTest` pins both statuses so the table cannot drift from the code
   again.

   A **404 where the table names a status** means the paste did not take. A **200 where the table
   names a refusal** is the serious one — an unauthenticated read of member data. A **refusal where
   the table says 200** means the backend's rule moved under the vhost, which is worth knowing too.

4. Add the new paths to the nightly `edge-deny-probe` workflow's allow-list step **once the paste
   is in**, not before — it asserts this table from outside every night, and an entry added ahead
   of the paste makes the run red for a change that has not happened yet.

5. A path that is no longer consumed comes back **out** on the same terms.

The edge per-IP rate limiter needs no entry here: `docker/maintenance/nginx/server_proxy.conf` is
included into **every** proxy host's server block, so the 20 r/s (burst 80) safety net of
REQ-SEC-023 already applies to this host, keyed per IPv4 address and per IPv6 `/64`.

### D.4 The Keycloak token endpoint

The app authenticates against `keycloak.profit-base.online`, which is now reachable by a wider
audience than a browser on a desktop. Give the token endpoint its own, tighter budget on the
**Keycloak** proxy host (*Custom locations → `/realms/iri/protocol/openid-connect/token` →
Advanced*):

```nginx
limit_req zone=krt_req_perip burst=10 nodelay;
limit_req_status 429;
```

The zone itself is version-controlled in `docker/maintenance/nginx/http.conf`; only the per-location
`limit_req` lives in the NPM database. A dedicated, stricter zone is a repo change — ask for it if
this burst turns out too generous once real app traffic exists.

**Rollback for the whole phase:** toggle the proxy host to *Disabled*. It keeps its certificate and
config; nothing else on the edge is affected.

---

## Phase E — verify from outside (E3)

From your **workstation**, never from the host: a probe from inside hairpins and is SNAT'd to a
Docker gateway, which is exactly the source the allow-lists trust.

```bash
# 1. An allow-listed path reaches the app: 401, because it needs a token.
curl -sS -o /dev/null -w '%{http_code}\n' https://api.profit-base.online/api/v1/terms/status

# 2. A path that is NOT on the allow-list is refused at the edge: 404, no server-side work.
curl -sS -o /dev/null -w '%{http_code}\n' https://api.profit-base.online/api/v1/missions

# 3. Actuator is denied: 404.
curl -sS -o /dev/null -w '%{http_code}\n' https://api.profit-base.online/actuator/health

# 4. Plain HTTP redirects to HTTPS.
curl -sSI http://api.profit-base.online | head -n 3

# 5. HSTS is present on the 401.
curl -sSI https://api.profit-base.online/api/v1/terms/status | grep -i strict-transport

# 6. The certificate validates over both address families.
curl -sS -o /dev/null -w '%{http_code} %{ssl_verify_result}\n' --ipv4 https://api.profit-base.online/api/v1/terms/status
curl -sS -o /dev/null -w '%{http_code} %{ssl_verify_result}\n' --ipv6 https://api.profit-base.online/api/v1/terms/status
```

Expected: `401`, `404`, `404`, a `301`/`308` to `https://`, a `max-age=…` line, and `401 0` over
both address families. Anything else: fix it before phase F, so the probes you enable assert a state
that actually holds.

**Then assert the DNS modules against the real zone.** The checks above prove the vhost answers;
they say nothing about whether the *probe definitions* match the zone's actual answer section, and
that is a separate failure mode. `api.profit-base.online` is a **CNAME** to the apex, so its answer
carries an alias RR next to the address RR — a probe regexp written for a direct A/AAAA record
fails against it from the first scrape, while DNS is perfectly healthy. That is exactly how phase F
shipped on 2026-08-18: both `blackbox-dns-api-*` jobs went straight to `DnsResolutionFailed`, a
false alarm that cost a night of pages. Run the modules locally before enabling their jobs:

```bash
docker run --rm -d --name bb-precheck -p 19115:9115 \
  -v "$PWD/monitoring/blackbox/blackbox.yml:/etc/blackbox/blackbox.yml:ro" \
  prom/blackbox-exporter:v0.28.0 --config.file=/etc/blackbox/blackbox.yml
for m in dns_apex_a dns_apex_aaaa dns_api_a dns_api_aaaa; do
  echo "$m: $(curl -s "http://localhost:19115/probe?module=$m&target=1.1.1.1" \
    | awk '/^probe_success /{print $2}')"
done
docker rm -f bb-precheck
```

Expected: `1` for all four. On a `0`, add `&debug=true` to the probe URL — the response names the
RR that failed the regexp. Fix the module, never the alert threshold.

One server-side check, over the monitoring plane rather than the host — in Grafana → Explore →
Loki, `{app="npm-access"} |= "api.profit-base.online"` must show your probes. That confirms the new
vhost's log stream is tailed (it is, by the per-file glob) and that the 31-day client-IP retention
of REQ-OBS-010 covers it.

---

## Phase F — enable the staged probes

Follow [`MONITORING_ROLLOUT_RUNBOOK.md`, Appendix C](MONITORING_ROLLOUT_RUNBOOK.md) exactly. In
short: un-comment the staged targets and jobs in `prometheus.yml`, the two URLs in
`.github/workflows/edge-deny-probe.yml`, widen `EdgeHstsHeaderMissing` to `job=~"blackbox-hsts.*"`
in the same commit, lint, promote, and confirm every new blackbox target is `up` with
`probe_success == 1`.

Ask Claude for that PR; it is a mechanical edit and the appendix lists every line.

**Merging is not enabling.** The config bundle reaches `/var/iri/code/monitoring/**` on a deploy,
but the running Prometheus only picks it up when the container is **recreated** — the config is a
single-file bind mount and `rsync` replaces the inode, so the process keeps reading the old one
(REQ-OBS-014). `deploy.sh` force-recreates on drift, but only on a tick that actually applies
something: once the config has landed, every following tick reports `no change — already at target
digests` and returns before the monitoring reconcile. On 2026-08-18 that left the api probes present
on disk and absent from the running config for over an hour.

Check both sides, and do not take the merge as the answer:

```bash
grep -c "api.profit-base.online" /var/iri/code/monitoring/prometheus/prometheus.yml
```

then in Prometheus `up{job=~"blackbox-hsts.*|blackbox-dns.*"}` — if `blackbox-hsts-auth` and
`blackbox-dns-api-*` are missing while the file has them, force the reload:

```bash
sudo -u deploy env DOCKER_CONFIG=/var/lib/iri/.docker /usr/bin/docker compose -p iri-monitoring --project-directory /var/iri/code -f /var/iri/code/docker-compose.monitoring.yml up -d --force-recreate prometheus
```

A SIGHUP is not enough here, for the inode reason above.

---

---

## Phase H — the phase-2 allow-list, in one paste

> **Done: 2026-08-23.** Pasted by @greluc and verified from outside the host in the same session:
> `200` for `/api/v1/missions/search`, `401` for the eight authenticated phase-2 reads
> (`/operations/search`, `/orders`, `/inventory/aggregated`, `/org-units/bank/balances`,
> `/hangar/my-ships`, `/notifications`, `/announcement`, `/users/me`) and `405` for
> `POST /api/v1/orders`. The steps stay here because the next phase repeats them.

Every read-only screen of the Android app is built and its paths are in the block of § D.3. The
vhost still serves only what was pasted before them, so **the app's new screens answer 404 against
production until this is done**. Nothing else is outstanding: no deploy, no restart, no Keycloak
change.

This is the whole procedure.

### 1. Merge the stack

The server-side PRs land in order; each one's base retargets itself as the one below it merges. Once
`main` carries them, the block in § D.3 above is the one to copy.

### 2. Paste the block

Nginx Proxy Manager → **Hosts → Proxy Hosts → `api.profit-base.online` → Advanced**. Replace the
whole custom-config field with the block from § D.3, and save. Partial pastes are the failure this
step has already produced once: in August the read-only guard from a block arrived while three
allow-list lines from the *same* block did not, and the app's Einsatz detail answered 404 with
nothing anywhere reporting it.

### 3. Verify from outside, not from the host

A hairpinned request does not prove what a phone sees.

**PowerShell** — the shell this is actually run from. The `bash` loop below is a parse error there
(`Missing opening '(' after keyword 'for'`), which is how this step failed the first time it was
handed over:

```powershell
foreach ($p in '/api/v1/missions/search','/api/v1/operations/search','/api/v1/orders','/api/v1/inventory/aggregated','/api/v1/org-units/bank/balances','/api/v1/hangar/my-ships','/api/v1/notifications','/api/v1/announcement','/api/v1/users/me') { '{0,-42} {1}' -f $p, (curl.exe -s -o NUL -w '%{http_code}' "https://api.profit-base.online$p") }
```

`curl.exe`, not `curl`: PowerShell 5.1 aliases the name to `Invoke-WebRequest`, which does not take
these flags. PowerShell 7 dropped the alias, so both work there — spelling it out makes the line
safe in either.

The same thing from a POSIX shell (WSL, Git Bash, a runner):

```bash
for p in /api/v1/missions/search /api/v1/operations/search /api/v1/orders /api/v1/inventory/aggregated /api/v1/org-units/bank/balances /api/v1/hangar/my-ships /api/v1/notifications /api/v1/announcement /api/v1/users/me; do printf '%-42s ' "$p"; curl -s -o /dev/null -w '%{http_code}' "https://api.profit-base.online$p"; echo; done
```

Expected, per § D.3a's table: **200** for `/missions/search`, **401** for every other line above. A
**404** anywhere means the paste did not take — that path is missing from the block. **404 on every
line means the block has not been pasted at all**, which is also what the write check below reports
as a 404 instead of a 405: default-deny answers an unknown path, and an unpasted allow-list makes
every phase-2 path unknown.

Then one write, which must be refused by the vhost rather than by the backend:

```bash
curl -s -o /dev/null -w '%{http_code}' -X POST https://api.profit-base.online/api/v1/orders; echo
```

Expected **405**. A **404** here means step 2 has not happened yet: the read-only guard cannot
refuse a verb on a path the allow-list has never admitted. This check matters more than it looks —
the same path answers a `POST` that is `permitAll` by design (the public request form), so only the
verb separates it from the queue read.

### 4. Let the nightly probe take over

`edge-deny-probe` asserts the whole table every night from a GitHub runner, which is the only
vantage point outside the host. From the merge until the paste it will be **red, and correctly so**:
it is reporting exactly the state step 2 fixes. If you paste the same day, you will not see it.

---

## Phase I — the phase-3 writes, in one paste at the end

**Now.** Phase 3 opens the app's first write paths, and the owner decided (2026-08-23) that they
reach production as **one** paste when the phase is complete, rather than seven. All seven slices
are merged, so this is that paste — the § D.3 block below goes to production in one edit.

**The nightly probe was deliberately NOT extended as the slices landed.** Adding a phase-3 path to
`edge-deny-probe` before the paste would have made it red for the length of the whole phase — days
of an alarm reporting a state nobody intended to fix yet, which is how a red bar stops meaning
anything. It gains every row below in **this** PR, and the run right after the paste is the one that
proves it.

### What the paste must contain

Everything in § D.3, which by then carries all six slices. The lines below are what phase 3 adds, in
merge order, so the block can be reviewed against this list rather than diffed by eye.

|     Slice     |                                                     Paths added                                                      |         Verbs          |
|---------------|----------------------------------------------------------------------------------------------------------------------|------------------------|
| Mein Inventar | `/api/v1/personal-inventory`, `/api/v1/personal-inventory/<uuid>`                                                    | GET, POST, PUT, DELETE |
| Mein Inventar | `/api/v1/uex/locations/search`                                                                                       | GET                    |
| Blueprints    | `/api/v1/personal-blueprints`, `/api/v1/personal-blueprints/<uuid>`                                                  | GET, POST, PUT, DELETE |
| Blueprints    | `/api/v1/personal-blueprints/craftability`, `/api/v1/blueprints/products/search`                                     | GET                    |
| Hangar        | `/api/v1/hangar/ships`, `/api/v1/hangar/ships/<uuid>`                                                                | POST, PUT, DELETE      |
| Hangar        | `/api/v1/ship-types`, `/api/v1/locations/home-locations`                                                             | GET                    |
| Lager         | `/api/v1/inventory`, `/api/v1/inventory/<uuid>/{book-out,personal-rebook,note}`                                      | POST, PUT              |
| Lager         | `/api/v1/materials/search`, `/api/v1/locations/search`, `/api/v1/users/search`, `/api/v1/materials/<uuid>/terminals` | GET                    |
| Lager         | `/api/v1/inventory/all/stack/entries`                                                                                | GET                    |
| Lager         | `/api/v1/inventory/material/{materialId}`                                                                            | GET                    |
| Aufträge      | `/api/v1/orders/<uuid>/assignees/<uuid>`, `…/note`                                                                   | POST, PUT, DELETE      |
| Aufträge      | `/api/v1/orders/<uuid>/status`                                                                                       | PUT                    |
| Einsatz       | `/api/v1/missions/<uuid>/join`                                                                                       | POST                   |
| Einsatz       | `/api/v1/missions/<uuid>/participants/<uuid>/slim` and its `check-in`, `check-out`, `payout-preference` siblings     | POST, PUT, DELETE      |
| Einsatz-Geld  | `/api/v1/finance-entries`, `/api/v1/finance-entries/<uuid>`                                                          | POST, PUT, DELETE      |
| Einsatz-Geld  | `/api/v1/operations/<uuid>/payouts/paid-out`                                                                         | PUT                    |
| Bank          | `/api/v1/org-units/bank/accounts/<uuid>/settings`                                                                    | GET                    |
| Bank          | `…/balance-target`, `…/visibility/role/<code>`, `…/visibility/all-members/<bool>`                                    | POST, PUT, DELETE      |

### What to expect afterwards

Anonymously, from outside the host — the same shape as Phase H's check:

|                     Path                     |                                  Without a token                                  |
|----------------------------------------------|-----------------------------------------------------------------------------------|
| `/api/v1/personal-inventory`                 | **401**                                                                           |
| `/api/v1/personal-inventory/<uuid>`          | **401**                                                                           |
| `/api/v1/uex/locations/search`               | **401**                                                                           |
| `/api/v1/personal-blueprints`                | **401**                                                                           |
| `/api/v1/personal-blueprints/<uuid>`         | **401**                                                                           |
| `/api/v1/personal-blueprints/craftability`   | **401**                                                                           |
| `/api/v1/blueprints/products/search`         | **401**                                                                           |
| `/api/v1/hangar/ships`                       | **401**                                                                           |
| `/api/v1/hangar/ships/<uuid>`                | **401**                                                                           |
| `/api/v1/ship-types`                         | **200** — anonymous by design, see REQ-SEC-037                                    |
| `/api/v1/locations/home-locations`           | **403** — method-seam refusal, not a `401`                                        |
| `/api/v1/inventory/<uuid>/book-out`          | **401**                                                                           |
| `/api/v1/users/search`                       | **401**                                                                           |
| `/api/v1/materials/search`                   | **200** — anonymous catalogue, see REQ-SEC-037                                    |
| `/api/v1/locations/search`                   | **200** — same                                                                    |
| `POST /api/v1/inventory/all`                 | **404** — the every-member list is not on the allow-list at all                   |
| `/api/v1/orders/<uuid>/assignees/<uuid>`     | **401**                                                                           |
| `/api/v1/orders/<uuid>/status`               | **401** — and **403** for an authenticated member without LOGISTICIAN             |
| `/api/v1/missions/<uuid>/join`               | **401**                                                                           |
| `…/participants/<uuid>/check-in/slim`        | **404** — the guard resolves the row before it judges the caller, see REQ-SEC-037 |
| `/api/v1/finance-entries`                    | **401**                                                                           |
| `/api/v1/operations/<uuid>/payouts/paid-out` | **401**                                                                           |
| `…/bank/accounts/<uuid>/settings`            | **401**                                                                           |
| `…/bank/accounts/<uuid>/balance-target`      | **401**                                                                           |
| `POST /api/v1/bank/deposits`                 | **404** — a direct booking form; not drawn, so not admitted even in phase L       |
| `POST /api/v1/orders`                        | **405** — the public request form stays refused on this vhost                     |
| `POST /api/v1/hangar/import/fleetview`       | **404** — phase 4, on no allow-list line; refused before the verb is judged       |

A **405** on any row listed **401** would be the read-only guard swallowing a write the phase is
supposed to open: `/personal-inventory` and `/personal-blueprints` must NOT be in the guard's family
list, while `uex` and `blueprints` must — the picker and the location search are reads, and the
catalogue behind them has writes this vhost never admits. `POST /api/v1/orders` is the one row where
**405** is the right answer, and it reads that way because the path *is* admitted — as the phase-2
queue — and only the verb is refused.

A **404** where a **401** is listed means the path did not match the allow-list — a typo in the
regex, most likely a `<uuid>` group that lost a brace. Anything other than **404** on
`/inventory/all`, `/bank/deposits` or `/hangar/import/fleetview` means the opposite: something was
admitted that should not have been — a **405** included, because reaching the read-only guard at all
means the path got past the deny. That one is worth stopping for.

### Doing it — step by step

Everything below runs on the production host and is yours to execute; nothing here is automated and
nothing in this repo reaches that host.

1. **Open the vhost's Advanced tab.** Nginx Proxy Manager → *Hosts → Proxy Hosts* →
   `api.profit-base.online` → *Advanced*.

2. **Replace the whole custom-configuration block** with § D.3 of this document, exactly as it
   stands after this PR. Not a merge of the old and the new: the block is written to be pasted
   whole, and hand-merging is how the read-only guard ends up with two `set $krt_readonly_family ""`
   lines that disagree.

3. **Save.** NPM tests the configuration before it writes it — a syntax error is refused here rather
   than taking the vhost down. If it refuses, nothing has changed yet; the old block is still live.

4. **Check the paste from your own shell**, before trusting the nightly probe:

   ```powershell
   foreach ($p in '/api/v1/personal-inventory','/api/v1/hangar/ships','/api/v1/inventory','/api/v1/orders/00000000-0000-4000-8000-00000000cafe/status','/api/v1/missions/00000000-0000-4000-8000-00000000cafe/join','/api/v1/finance-entries','/api/v1/org-units/bank/accounts/00000000-0000-4000-8000-00000000cafe/settings','/api/v1/ship-types','/api/v1/materials/search') { '{0,-78} {1}' -f $p, (curl.exe -s -o NUL -w '%{http_code}' "https://api.profit-base.online$p") }
   ```

   Expected: `401` for the first seven, `200` for `ship-types` and `materials/search`. Anything else
   is in the table above.

5. **Check that what should still be refused, is:**

   ```powershell
   foreach ($p in '/api/v1/bank/deposits','/api/v1/inventory/all','/api/v1/hangar/import/fleetview') { '{0,-46} {1}' -f $p, (curl.exe -s -o NUL -w '%{http_code}' -X POST "https://api.profit-base.online$p") }
   ```

   Expected: `404` for all three — none of them is on the allow-list, and the deny answers before
   the read-only guard can. A `401` or a `405` here would mean a path was admitted that should not
   have been.

6. **Run the probe once by hand** rather than waiting for the night:
   *Actions → Edge deny probe → Run workflow*. It must come back green; it now carries every path of
   this phase.

If step 4 or 5 disagrees with the table, put the previous block back — it is in this file's git
history — and say what you saw. Nothing in the app breaks while the old block is live: the phase-3
paths simply stay unreachable from outside, which is where they have been all along.

---

## Phase J — the phase-4 paths, in one paste at the end

**Not yet.** Phase 4 gives the app live parity and three more areas, and it follows phase 3's rule
(owner decision, 2026-08-23): its paths reach production as **one** paste when the phase is
complete, not once per slice. Each slice lands its allow-list lines in § D.3 above and its expected
statuses in the table below; nothing is pasted until the last one is merged, and the nightly probe
gains its rows in the same PR as the paste instruction so it never reports a state nobody intends to
fix yet.

**The owner confirmed this on 2026-08-24, with the probe already red.** It was asked directly --
paste phase I now and get a green run tonight, or hold for one paste -- and the answer was to hold.
So the nightly `edge-deny-probe` reports 23 phase-3 paths answering `404` where it lists `401`, and
**that is the correct reading of production, not a defect**: those paths have never been admitted to
the vhost. Do not investigate that run again, do not "fix" it in the repo, and do not silence the
block -- the fix is the paste below, and it happens when phase 4 closes. Anything failing that is
*not* in the phase-3 list is a different matter and does deserve stopping for.

**If Phase I has not been applied yet, apply it after this phase closes and it covers both.** The
§ D.3 block is pasted *whole*, so the copy in this repo is always the complete current intent —
phase 2's reads, phase 3's writes and phase 4's paths together. If Phase I has already been applied,
this phase is a re-paste of the same block, which is safe and is the only supported way to update
it.

### What the paste must contain

|    Slice    |                                 Paths                                  |    Verbs     |
|-------------|------------------------------------------------------------------------|--------------|
| Beförderung | `/api/v1/promotion/evaluations/my`, `/api/v1/promotion/eligibility/my` | GET          |
| Live-Sync   | `/api/v1/live-sync/stream`                                             | GET (SSE)    |
| Live-Sync   | `/api/v1/live-sync/changed`                                            | POST         |
| App-Gate    | `/api/v1/app/version-policy`                                           | GET          |
| Raffinerie  | `/api/v1/refinery-orders/my-orders`, `/<uuid>`                         | GET          |
| Raffinerie  | `/api/v1/refinery-orders/<uuid>/store`                                 | POST         |
| Börse       | `/api/v1/material-exchange/offers`, `/material-requests`               | GET, POST    |
| Börse       | `/api/v1/material-exchange/releasable-items`                           | GET          |
| Börse       | `…/offers/<uuid>/interest`, `…/material-requests/<uuid>/interest`      | POST, DELETE |
| Börse       | `…/offers/<uuid>/deactivate`, `…/material-requests/<uuid>/deactivate`  | POST         |

`live-sync` is deliberately **not** in the read-only family list, so it needs no carve-out: the two
paths are admitted by being named, and nothing else under the stem is reachable at all. That is the
opposite choice from `/hangar` or `/inventory`, and it is available here only because the family is
two endpoints rather than a surface with an admin half hiding in it.

### What to expect afterwards

|                       Path                       | Anonymous status |
|--------------------------------------------------|------------------|
| `GET /api/v1/promotion/evaluations/my`           | **401**          |
| `GET /api/v1/promotion/eligibility/my`           | **401**          |
| `GET /api/v1/live-sync/stream`                   | **401**          |
| `POST /api/v1/live-sync/changed`                 | **401**          |
| `GET /api/v1/app/version-policy`                 | **200**          |
| `GET /api/v1/refinery-orders/my-orders`          | **401**          |
| `GET /api/v1/material-exchange/offers`           | **401**          |
| `GET /api/v1/material-requests`                  | **401**          |
| `GET /api/v1/material-exchange/releasable-items` | **401**          |

**The `200` in that table is not a typo and not a finding.** `version-policy` is the only path this
vhost admits that was *decided* anonymous rather than inheriting it from something already public
(REQ-SEC-037), and a `401` there would mean the gate is broken: the build that most needs to be told
it is too old is the one that cannot log in. Check it deliberately, and do not "fix" it upward. It
is not the only `200` on the vhost, though — the master-data catalogues are anonymous too
(`/ship-types`, `/materials/search`, `/locations/search`, and phase M's `/refining-methods`), and
so are `/terms/document` and the guest-redacted Einsatz reads.

Two things worth knowing before reading a result. The stream answers `403`, not `401`, for an
*authenticated* caller none of whose topics were accepted — the caller authenticated fine, they
simply may not enter any room they asked for. And `GET /api/v1/live-sync/stream` **without** the
`topics` parameter is a `400` even with a valid token, so verify it with one: a bare probe of the
path tells you the allow-list matched and nothing else.

A **404** on either path means the block was never pasted, which is the failure with no other
signal — the app keeps working and simply never goes live.

### Doing it — step by step

Everything below runs on the production host and is yours to execute; nothing in this repo reaches
that host. The steps are Phase I's, because the block is the same block — if you have not applied
Phase I yet, doing this once covers both.

1. **Open the vhost's Advanced tab.** Nginx Proxy Manager → *Hosts → Proxy Hosts* →
   `api.profit-base.online` → *Advanced*.

2. **Replace the whole custom-configuration block** with § D.3 of this document as it stands after
   the last phase-4 PR. Not a merge of the old and the new: the block is written to be pasted
   whole, and hand-merging is how the read-only guard ends up with two `set $krt_readonly_family ""`
   lines that disagree.

3. **Save.** NPM tests the configuration before writing it, so a syntax error is refused here rather
   than taking the vhost down. If it refuses, nothing changed and the old block is still live.

4. **Check the two new paths from your own shell.** The stream needs its `topics` parameter — a
   bare probe of the path answers `400` even with a valid token and tells you only that the
   allow-list matched:

   ```powershell
   foreach ($p in '/api/v1/live-sync/stream?topics=inventory','/api/v1/live-sync/changed','/api/v1/promotion/evaluations/my','/api/v1/promotion/eligibility/my','/api/v1/app/version-policy','/api/v1/refinery-orders/my-orders','/api/v1/material-exchange/offers','/api/v1/material-requests','/api/v1/material-exchange/releasable-items') { '{0,-52} {1}' -f $p, (curl.exe -s -o NUL -w '%{http_code}' "https://api.profit-base.online$p") }
   ```

   Expected: `401` for everything except **`version-policy`, which must answer `200`** — it is
   the one anonymous path on this vhost (REQ-SEC-037) and is meant to answer without a token. A
   `404` anywhere means the block was never pasted — the failure with no other signal, because the
   app keeps working and simply never goes live.

5. **Check that phase 2's and phase 3's paths still answer as they did**, since you replaced the
   whole block:

   ```powershell
   foreach ($p in '/api/v1/personal-inventory','/api/v1/hangar/ships','/api/v1/inventory','/api/v1/finance-entries','/api/v1/ship-types','/api/v1/materials/search') { '{0,-40} {1}' -f $p, (curl.exe -s -o NUL -w '%{http_code}' "https://api.profit-base.online$p") }
   ```

   Expected: `401` for the first four, `200` for the last two.

6. **Run the probe once by hand** rather than waiting for the night:
   *Actions → Edge deny probe → Run workflow*. It must come back green.

If step 4 or 5 disagrees, put the previous block back — it is in this file's git history — and say
what you saw. Nothing breaks while the old block is live: the phase-4 paths simply stay unreachable
from outside, which is where they have been all along.

## Phase K — the member's bank booking requests, and one membership read

**Not yet, and for the same reason as Phase J:** these lines join the one pending paste rather than
prompting a separate visit to the host. Nothing below is applied until it goes with them.

Two app slices need this, and both were verified against the local test stack — which is reached
**directly, with no vhost in front of it**, and therefore cannot show a missing allow-list line.
That is how both gaps survived a full device verification.

|     Slice      |                                     Paths                                     |    Verbs     |
|----------------|-------------------------------------------------------------------------------|--------------|
| Bank-Anträge   | `/api/v1/org-units/bank/requests`, `…/requests/foreign`, `…/transfer-targets` | GET          |
| Bank-Anträge   | `/api/v1/org-units/bank/requests`                                             | POST         |
| Bank-Anträge   | `/api/v1/org-units/bank/requests/<uuid>`                                      | PUT          |
| Bank-Anträge   | `…/requests/<uuid>/cancel`                                                    | POST         |
| Bank-Anträge   | `…/requests/<uuid>/owner-approval`                                            | POST, DELETE |
| Lager-Umbuchen | `/api/v1/users/<uuid>/memberships`                                            | **GET only** |

**The bank-employee surface is untouched by *this* phase.** Confirming and rejecting a request
are `/api/v1/bank/requests/<uuid>/(confirm|reject)`, `hasRole(BANK_EMPLOYEE)`; they are
admitted in phase L below, together with the rest of the staff surface the app gained when
`REQ-APP-BANK-007` was amended. Nothing in phase K opens them.

**Why `/users/<uuid>/memberships` and not `me`.** The Umbuchen picker offers the org units of the
**destination** member, because the server validates the target unit against *their* memberships,
not the caller's. The me-scoped path cannot answer that. The backend gates the endpoint on any
member and documents that a non-admin derives no personally identifying data from it — no display
name, no email, no rank — which is the same stance as `/api/v1/users/search`, already admitted.

It is **not** cleared out of the read-only family, so the `PATCH` the backend serves on the very
same path — the org-unit admin write — keeps answering `405` here. That asymmetry is the point of
the entry, and re-pasting the § D.3 block whole is what preserves it.

### What to expect afterwards

|                        Path                        | Anonymous status |
|----------------------------------------------------|------------------|
| `GET /api/v1/org-units/bank/requests`              | **401**          |
| `GET /api/v1/org-units/bank/requests/foreign`      | **401**          |
| `GET /api/v1/org-units/bank/transfer-targets`      | **401**          |
| `POST /api/v1/org-units/bank/requests`             | **401**          |
| `PUT /api/v1/org-units/bank/requests/<uuid>`       | **401**          |
| `POST …/requests/<uuid>/cancel`                    | **401**          |
| `POST` / `DELETE …/requests/<uuid>/owner-approval` | **401**          |
| `GET /api/v1/users/<uuid>/memberships`             | **401**          |
| `PATCH /api/v1/users/<uuid>/memberships`           | **405**          |

A `405` on any of the six writes means the read-only carve-out was lost; a `404` means the
allow-list line never matched. The single deliberate `405` is the last row, and a `401` there would
mean the org-unit admin write had been opened by accident.

---

## Phase L — the bank-staff surface

**Owner decision, 2026-08-27.** Asked which endpoints the app's Verwaltung scope should put on the
public mobile vhost — everything the design draws, everything but the destructive and exporting
ones, or reads only — the answer was **everything design chapter 12 draws in artboards 4 to 8**.
`/api/v1/bank/admin` stays out permanently, and so do the direct booking forms and the KRT ladder
editor, because no artboard draws them (app `REQ-APP-BANK-007`).

This is the first time `/api/v1/bank/**` is admitted at all. Until now the runbook's own comments
said "never `/bank/accounts/**`"; that was true while the app had no staff surface and stopped
being true when `REQ-APP-BANK-007` was amended.

|   Tab / screen    |                            Paths                            |      Verbs       |
|-------------------|-------------------------------------------------------------|------------------|
| Übersicht (ab. 4) | `/api/v1/bank/dashboard`                                    | GET              |
| Anträge (ab. 5)   | `/api/v1/bank/requests`                                     | GET              |
| Anträge           | `…/requests/<uuid>/confirm`, `…/reject`                     | POST             |
| Konten (ab. 6)    | `/api/v1/bank/accounts`                                     | GET, POST        |
| Konten            | `…/accounts/<uuid>`                                         | GET, PATCH       |
| Konten            | `…/accounts/<uuid>/close`, `/reopen`                        | POST             |
| Konto-Detail      | `…/accounts/<uuid>/transactions`, `/statement`              | GET              |
| Konto-Detail      | `/api/v1/bank/transactions/<uuid>/reversal`                 | POST             |
| Konto-Detail      | `/api/v1/bank/export/three-month-report`                    | GET              |
| Grants (ab. 7)    | `/api/v1/bank/grants`                                       | GET, POST        |
| Grants            | `/api/v1/users/search-bank`                                 | GET              |
| Grants            | `…/grants/<uuid>/<uuid>`                                    | PATCH, DELETE    |
| Halter (ab. 6/8)  | `/api/v1/bank/holders`, `…/<uuid>`, `…/<uuid>/transactions` | GET, POST, PATCH |
| Halter-Umbuchung  | `/api/v1/bank/holders/transfer`                             | POST             |

**The write family, and why that is the right shape here.** `bank` is not in the read-only family
list, so a named path answers every verb the backend serves on it. Each of the pairs above wants
exactly that — GET+PATCH on an account, PATCH+DELETE on a grant — and the safety comes from the
allow-list's `404` default rather than from a verb guard. That is the same stance
`/personal-inventory` takes, and it is available here **only** because every path is named
individually and anchored: the admin half of the stem is never named, so it is never reachable.

**Why the grantee picker needs its own path.** `/api/v1/users/search` is already admitted, but the
app cannot use it here: it is gated on ADMIN/OFFICER/KRT_MEMBER, and a bank manager who holds no org
role — the exact caller this tab exists for — gets 403. `/users/search-bank` is the same search with
`BANK_EMPLOYEE` added to the gate and nothing else changed (same query, same scope, same
peer-redacted projection), which is why the backend keeps it as a separate path rather than widening
the first one. Admitting it widens the mobile surface by no rows: both answer the same set.

**What stays 404, and is probed for it:** `/api/v1/bank/admin/wipe-reset`,
`/api/v1/bank/admin/audit`, `/api/v1/bank/deposits`, `/api/v1/bank/withdrawals`,
`/api/v1/bank/transfers`, `/api/v1/bank/transfer-fee-rate`, and
`/api/v1/bank/accounts/<uuid>/approval-tiers`.

### What to expect afterwards

Every admitted path answers **401** anonymously — the entry point refuses before any row is read —
and every excluded one answers **404**. A `405` anywhere in the first group would mean the
read-only guard swallowed a write this phase opens; a `404` there would mean the line never
matched. A `401` in the second group would mean something was opened by accident.

---

## Phase M — creating a refinery order

**App decision, design chapter 11 artboards 4–5.** The app could read a run and book its yield but
not record one; the form that closes that gap needs three paths the vhost has never named.

|      Screen       |             Paths              | Verbs |
|-------------------|--------------------------------|-------|
| Neuer Auftrag     | `/api/v1/refinery-orders`      | POST  |
| Raffinerie-Picker | `/api/v1/locations/refineries` | GET   |
| Methoden-Picker   | `/api/v1/refining-methods`     | GET   |

**Why the bare stem is safe to name.** `refinery-orders` is not in the read-only family, so the line
opens every verb the backend serves on that exact path — which is the create `POST` and the
caller-scoped `GET`. The id-bearing paths beneath it were already admitted in phase 3 and are
unaffected; `/refinery-orders/all` stays unnamed and therefore still `404`, because it is the
org-wide list the app does not show.

**The two picker reads are mandatory fields, not conveniences.** The form refuses to submit without
a refinery and a method, so a 404 on either leaves it permanently unsendable — the failure would
look like a broken form rather than a missing allow-list line.

**No extractor import.** `/api/v1/refinery-orders/import-extract` stays out permanently: the
Extractor's handoff is consumed once in a browser through the ingest gateway and a phone cannot
receive it.

### What to expect afterwards

|                Path                | Anonymous status |
|------------------------------------|------------------|
| `POST /api/v1/refinery-orders`     | **401**          |
| `GET /api/v1/locations/refineries` | **403**          |
| `GET /api/v1/refining-methods`     | **200**          |

`/api/v1/refinery-orders/all` and `/api/v1/refinery-orders/import-extract` answer **404**.

> [!warning] Corrected 2026-08-31 — this section said all three answer `401`, and neither picker
> ever did
> The number was reasoned from the form around the field rather than read off the rule that judges
> the caller, and the nightly probe was written from this table, so both carried the same mistake.
> The first red run was the expected pre-paste `404` on both paths, which is precisely how this
> hides: night one looked like the missing block it is supposed to look like, and the three nights
> after the paste reported a drift on a vhost that was configured correctly.
>
> `GET /api/v1/locations/refineries` is `permitAll` in the filter chain under
> `/api/v1/locations/**`, so the request is dispatched and the method-level `isAuthenticated()`
> refuses it at the method seam — which `GlobalExceptionHandler` renders as **403**. Identical in
> shape and number to `/api/v1/locations/home-locations`, which this runbook already recorded as
> `403` a few phases up.
>
> `GET /api/v1/refining-methods` is an anonymous master-data catalogue with no method gate at all,
> the same family as `/ship-types` and `/materials/search`: it answers **200**, and
> [`ROLES_AND_PERMISSIONS.md`](../ROLES_AND_PERMISSIONS.md) has said so all along. Method names and
> their UEX ratings — no member, org unit or order is reachable through it.
>
> All three are now pinned in `ApiVhostAnonymousSurfaceTest`, which is what REQ-SEC-037 asks for on
> every admitted path and is the step this phase skipped.

---

## Phase G — flip the audience enforcement (D5, release gate)

**This gate must close before the app is released**, not later. Until it is flipped, the backend
accepts any signed realm token — including one minted for an unrelated client — and the vhost is
public.

1. **Confirm the realm actually stamps the audience** before flipping anything. In the Keycloak
   Admin Console (through the SSH tunnel): *Clients → `basetool-frontend` → Client scopes →
   Evaluate* → pick a user → *Generated access token* → check that `aud` contains
   `basetool-backend`. Repeat for `basetool-android`. A flip without this locks every client out of
   the API at once.

2. Edit the prod env file:

   ```bash
   sudo -u deploy nano /var/iri/code/.env
   ```

   ```dotenv
   IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend
   IRI_INGEST_EXPECTED_AUDIENCES=basetool-ingest
   ```

   > **⚠️ The two values differ, and this step used to get the gateway's wrong.** As written until
   > 2026-08-28 it set **both** to `basetool-backend`. That is the backend's audience and every
   > `basetool-frontend` session token carries it, so on the gateway the check passes for exactly
   > the tokens the ingest interface exists to refuse — which is what ADR-0018 amendment 1
   > (`REQ-INGEST-011`) reversed on 2026-08-03, and what
   > [`INGEST_KEYCLOAK_SETUP.md`](INGEST_KEYCLOAK_SETUP.md) step 7a has forbidden in a boxed warning
   > ever since. The realm has carried the correct `extractor-ingest-only` scope — stamping
   > `aud=basetool-ingest` on the extractor's tokens and on no one else's — since before this
   > rollout.
   >
   > **If this step was executed as originally written, the deployed gateway carries the wrong
   > value and needs correcting** — check it before assuming otherwise. The backend's half was and
   > remains right. Verify a live extractor token actually carries `aud=basetool-ingest` before
   > correcting the gateway: the value is already enforcing, so a wrong one answers `401` on every
   > send.

3. Apply. An `.env` change is host state: the deploy timer sees no digest change and exits as a
   no-op, so it must be applied by hand.

   ```bash
   sudo -u deploy env DOCKER_CONFIG=/var/lib/iri/.docker /usr/bin/docker compose -f /var/iri/code/docker-compose.yml -f /var/lib/iri/current-digest-pin.yml --profile prod up -d backend ingest
   ```

   Both extra pieces are load-bearing, and the command fails without them:

   - **`DOCKER_CONFIG=/var/lib/iri/.docker`** — the `deploy` user was created with
     `--no-create-home`, so its GHCR credentials live there rather than in a home directory, and
     `deploy.sh` exports exactly this before its first docker call. Without it the pull is anonymous
     and GHCR answers `unauthorized` on the private image (observed 2026-08-18).
   - **`-f /var/lib/iri/current-digest-pin.yml`** — the override `deploy.sh` writes with the exact
     digests currently deployed. With it the images are already local and nothing is pulled at all;
     without it compose resolves `:stable`, which may have moved since the last deploy and would
     quietly bring up a different version than the one running.
4. Verify within a minute or two:
   - **both** containers actually carry the variable — the first attempt at this step failed on the
     pull above and left `ingest` behind, with the backend enforcing and the gateway not:

     ```bash
     docker inspect backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -c 'APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-backend'; docker inspect ingest --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -c 'APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-ingest'
     ```

     A `1` from each — note that the two greps look for **different** audiences, per the warning
     above; the earlier `for c in backend ingest` one-liner checked both against the backend's value
     and so reported the wrong gateway setting as a pass. A `0` means either that container was not
     recreated, whatever the compose output said, or that it carries the other module's value;

   - the web app still works (log in, open a page that loads data) — that proves the frontend's
     tokens carry the audience;

   - check 1 of phase E still returns `401`, not `500`;

   - Grafana dashboard 07 → *Auth failures/hour by reason*: a jump in `invalid_token` right after
     the flip means the audience is **not** being stamped for some client. Roll back immediately.

**Rollback:** blank both variables in `.env` and re-run the `up -d backend ingest` above. Effect is
immediate; no release, no promotion.

---

## Never, under any circumstances

- **Do not** hand-edit `docker-compose.yml`, the maintenance snippets or the monitoring configs on
  the host. They arrive through the promoted config bundle (ADR-0049); a hand edit is silently
  overwritten on the next deploy tick, which is the worst of both worlds.
- **Do not** widen the allow-list of phase D.3 to `^/api/` "for now". Every family you open is
  internet-reachable for whichever anonymous branches it contains; open them per app phase, with the
  matching rate budget.
- **Do not** enable the probes (phase F) before phase E is green. A probe that fails from the day it
  merges trains you to ignore the channel it fires on.
- **Do not** flip the audience (phase G) before its verification step. It is the one change here
  that can lock out every client simultaneously.

