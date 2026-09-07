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
# for. The staff surface is admitted separately in phase L; the direct booking forms
# (deposits, withdrawals, transfers) follow in phase O, on the staff surface only.
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
# serves there - which for the stem is exactly the create POST. Every GET this controller offers
# sits on a sub-path (/my-orders, /{id}, /mission/{id}, /locations/{id}/yields), so the stem itself
# answers no read at all; the two picker reads
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
# Phase 3 - the Lager's bookings. EXACT paths: the /inventory prefix also carries
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
# Phase N added `delivered` to this alternation: the Materialsammeluebersicht's third write,
# a PATCH that marks one linked row as handed over. Its gate is the ROW's
# (`canEditInventoryItem`), not the order's, so a Logistician editing another unit's stock is
# refused by the backend with a 403 the screen shows - which is the intended answer, not a
# reason to widen anything here.
if ($uri ~ "^/api/v1/inventory/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(book-out|personal-rebook|note|delivered|allocation)$") { set $krt_api_allowed 1; }
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
# TWO THINGS STAY OUT, and each for its own reason:
#   * /api/v1/bank/admin/** - wipe-reset and the bank audit log. The admin area is web-only by
#     owner decision and is never named below, so it keeps answering 404.
#   * /bank/accounts/<uuid>/approval-tiers and /balance-target - the KRT ladder editor is not one
#     of the app's four tabs, and the balance target is already reachable on the member surface.
#
# THE THIRD ONE WAS WRONG AND IS ADMITTED IN PHASE O (2026-09-03). This block used to exclude
# /bank/deposits, /bank/withdrawals, /bank/transfers and /bank/transfer-fee-rate on the ground
# that "no artboard draws them; a booking that had no request stays a browser act". Chapter 12
# artboard 9 draws exactly that sheet, round 8 decided it STAYS, REQ-APP-BANK-016 specifies it and
# the app shipped it -- so the sentence was false when it was written and the four paths were the
# only reason a built feature answered 404 in production. See phase O.
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
# Phase O - the Verwaltung's direct booking (app REQ-APP-BANK-016, design ch. 12 artboard 9).
# Four exact paths, no regex: the backend serves exactly one verb on each - POST on the three
# bookings, GET on the fee rate - and `bank` is not in the read-only family, so an exact line
# opens precisely that verb and nothing beneath it.
#
# The gate is `hasRole('BANK_EMPLOYEE')` plus a PER-ACCOUNT grant (`canDeposit` /
# `canWithdraw` / `canTransfer`, or management/admin unrestricted) - NOT Bank-Management, which
# is what both this runbook and the app had assumed. A plain Bankmitarbeiter with a grant on
# one account may book on that account and is refused on the others, by the backend, per call.
#
# /bank/holders/transfer is a DIFFERENT endpoint (moving custody between holders) and was
# already admitted in phase L. It is not part of this.
if ($uri = "/api/v1/bank/deposits") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/withdrawals") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/transfers") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/bank/transfer-fee-rate") { set $krt_api_allowed 1; }
# Phase P - the Freigabe-Limits (app REQ-APP-BANK-017, design ch. 12 artboard 10). ONE rule for
# all four leaves, because they are one screen and one endpoint family: the ceiling that
# decides which member may approve a booking on this account, set for everybody, for the
# Bereich, for a role bucket, or for one member.
#
# The account's `/settings` GET that carries the current values was admitted in phase 3, so
# the section has been DRAWING correctly all along and every 'Setzen' and 'Entfernen' answered
# 404 - the reason this was invisible. Whether these eight are among the 75 the audit counted is
# not recorded: no per-family breakdown of that number was committed, so this phase does not move
# it either way.
#
# `role/` reuses the same character class as the visibility rule above, and `user/` a uuid;
# both are $-anchored, so `approval-tiers` beside them - the KRT ladder editor, a DIFFERENT
# endpoint the app does not call - stays unnamed and keeps answering 404.
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/approval-limit/(all-members|area-members|role/[A-Za-z0-9_-]{1,64}|user/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$") { set $krt_api_allowed 1; }
# Phase N - taking somebody off an Einheit, and the `/slim` half of the pair only. The
# full-DTO `DELETE .../crew/{crewId}` beside it is @ApiDeprecation-marked with a sunset and
# stays unnamed on purpose: naming both would let a build fall back onto the path that is
# going away. The ADDING half (`POST .../units/{unitId}/crew`) also stays out - the app
# removes a crew slot, it does not assemble an Einheit.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/crew/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_api_allowed 1; }
# Phase N - the Materialsammeluebersicht (app REQ-APP-ORDERS-023): one read and three writes,
# opened together because the screen is one gate. Its two unlinks carry
# `hasAnyRole(LOGISTICIAN, OFFICER, ADMIN) and canEditJobOrder(#jobOrderId)`, which is exactly
# the `canEdit` flag the order's own response projects - the app draws the controls from it, so
# the screen and the endpoints agree by construction rather than by a rule written twice.
#
# `material-collection` is a GET and gets NO carve-out below, so a write the backend might one
# day serve on it stays 405 rather than arriving open. The two DELETE paths do, and each is
# $-anchored: `.../materials/<uuid>` in particular sits directly under the order, where the
# whole Logistician edit surface also lives.
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/material-collection$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/inventory/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/unlink$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/materials/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# Phase Q - the member's own two standing choices, and the read marker for the Aushang.
# EXACT paths, for the reason the /users/me comment in phase 2 already gives: the
# `/api/v1/users/` prefix reaches every other member's record, so this vhost names the
# me-scoped leaves one at a time and never the stem. The announcement marker carries an id
# and is therefore a $-anchored uuid regex rather than an exact path.
#
# All three are GET-and-PUT surfaces of the SAME user row, and the two settings rows share
# one optimistic-lock version. That is why the reads had to come with the writes: the app
# echoes the version its read returned, so admitting only the PUT would have made both rows
# operable and every tap a 409 - or, on a row still at version 0, a write that succeeds by
# accident. Read and write, together, or neither.
if ($uri = "/api/v1/users/me/payout-preference") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/users/me/blueprint-sharing") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/users/me/read-announcement/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# Phase S - the four pickers, and the class of defect they belong to. Each is a GET that the
# app SWALLOWS on failure by design: a picker is one field on a form about something else, so
# a banner over the whole screen would be about the wrong thing. That is right, and it is why
# these four were invisible - refused at the edge, they render as „there are none" and a
# member reads an empty list as an answer rather than as a fault.
#
# All four are reads in read-only families and take NO carve-out. `/job-types` is exact even
# though the app appends a query string: this guard matches on $uri, which stops at the `?`.
if ($uri = "/api/v1/orders/lookup") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/missions/lookup") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/operations/lookup") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/job-types") { set $krt_api_allowed 1; }
# Phase T - the Auftrags-Familie, the audit's own block. Nine rules, one anchored regex per
# sub-family and no prefix: `/api/v1/orders` also carries the Auftrag itself, the requester's
# edit and the delete, none of which belongs here.
#
# Reads first. `material-demand` is exact - it is a collection path, not a leaf under an id.
if ($uri = "/api/v1/orders/material-demand") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/item-stock$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/materials/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/inventory$") { set $krt_api_allowed 1; }
#
# Then the writes. Each is named because the read beside it is useless alone: the Zusagen tab
# is a list with an upsert and a withdrawal on the same path, and the Bestandszeilen read is
# what makes a Material-Uebergabe submittable at all - without it no `inventoryItemId` can be
# picked, and an Uebergabe is what closes an Auftrag.
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/claims$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/claims/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/handovers$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/item-handovers$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/items$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/items/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/production$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/priority$") { set $krt_api_allowed 1; }
# Phase U - the Lager writes. Three rules, and the allocation one rides the leaf group that
# already carries book-out, personal-rebook, note and delivered rather than adding a fourth
# line beside it: same shape, same family, same carve-out.
if ($uri = "/api/v1/inventory/bulk-checkout") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/inventory/bulk-rebook") { set $krt_api_allowed 1; }
# Phase V - the Einsatz planning set, the audit's largest block. Every rule is a leaf UNDER
# the Einsatz id and none is the id itself: `DELETE /api/v1/missions/<uuid>` deletes the whole
# Einsatz, the app never sends it, and it keeps answering 405 because that path has no
# carve-out and gets none here.
#
# The six with no /slim twin. `unit-ship-options` is the only read among them.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(core|schedule|flags|party-lead|participants|unit-ship-options)$") { set $krt_api_allowed 1; }
#
# Einheiten and their crew. SLIM ONLY, and deliberately: the full-DTO twins carry an
# `@ApiDeprecation` with a sunset of 2026-10-20, so naming them here would be work with an end
# date. The app was moved onto these in basetool-android#140, in the same week.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/crew/slim$") { set $krt_api_allowed 1; }
#
# Frequenzen and Verwalter, same reasoning. `custom` is not a uuid, so the two frequency
# rules cannot be folded into one alternation without loosening both.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/frequencies/custom/slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/frequencies/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/managers/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_api_allowed 1; }
#
# Ablauf and Ziele. These exist ONLY as /slim - there is no plain variant to fall back on -
# and they are the two sections a Kommandoleiter builds an Einsatz out of.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(steps|objectives)/slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(steps|objectives)/reorder/slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/steps/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(done/)?slim$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/objectives/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_api_allowed 1; }
# Phase W - the Handel family. Two halves with two different guards, and the difference is
# what makes this phase readable.
#
# The `materials` half is IN the read-only family, so naming a path admits its GET and
# nothing else. That matters here more than anywhere: `/api/v1/materials/<uuid>` also serves
# PUT and DELETE - editing and deleting a material from the catalogue - and neither gets a
# carve-out, so both keep answering 405.
if ($uri = "/api/v1/materials/prices-overview") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/materials/matrix") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/materials/profit-calculation") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/materials/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/materials/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/prices$") { set $krt_api_allowed 1; }
#
# The `material-exchange` / `material-requests` / `terminals` half is NOT in any read-only
# family, so admitting a path there opens every verb the backend serves ON THAT PATH. Each
# of these was checked against its controller: `/terminals` serves only the collection GET
# (its edits live on `/{id}/...`), `released-item-ids` only a GET, `item-offers` and
# `/material-requests/item` only a POST, `offers/<uuid>/remark` only a PUT, and
# `/material-requests/<uuid>` a GET and the PUT the app sends. No DELETE is opened anywhere
# in this phase.
if ($uri = "/api/v1/terminals") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/material-exchange/released-item-ids") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/material-exchange/item-offers") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/material-requests/item") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/material-exchange/offers/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/remark$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/material-requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
# Phase X - the last seven, and the two families that had no rule at all.
#
# Blaupausen: a WRITE family (see the note below on which shape a family gets), so naming a
# path opens every verb the backend serves on it - which is what `/personal-blueprints`
# already relies on for its bulk delete. `/import/apply` is admitted with `/import/preview`
# because the apply is unreachable without the preview and destructive with it: the audit
# called that pair „latent - and then work-destroying".
if ($uri = "/api/v1/personal-blueprints/batch") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/personal-blueprints/import/preview") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/personal-blueprints/import/apply") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/personal-blueprints/overview") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/personal-blueprints/overview/owners") { set $krt_api_allowed 1; }
#
# Hangar: a READ-ONLY family, because its prefix also carries the admin ship surface
# (`/hangar/users/<uuid>/ships`) and the P4K-adjacent imports. Both of these need their own
# exception below.
if ($uri = "/api/v1/hangar/import/fleetview") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/hangar/ships/home-location") { set $krt_api_allowed 1; }
#
# The two thresholds that colour an Auftrag by age. Named exactly, one per key: the
# `/settings` prefix carries every system setting there is, and the app reads two of them.
if ($uri = "/api/v1/settings/job_order.age_yellow_days") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/settings/job_order.age_red_days") { set $krt_api_allowed 1; }
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
# `settings` joined this list in phase X. `/api/v1/settings/<key>` serves a GET and a PUT, and
# the PUT is the admin write that changes a value for the whole organisation. The app reads two
# keys and writes none, so the family - not a carve-out - is what keeps the PUT at 405.
if ($uri ~ "^/api/v1/(missions|operations|notifications|announcement|hangar|inventory|orders|org-units|uex|blueprints|ship-types|locations|materials|users|refining-methods|settings)") { set $krt_readonly_family "R"; }
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
# endpoints and the allocation family. Only the four per-entry writes and the create are named.
if ($uri = "/api/v1/inventory") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/inventory/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(book-out|personal-rebook|note|delivered|allocation)$") { set $krt_readonly_family ""; }
# /orders stays in the family because the prefix also carries the handovers, the production
# reports and the whole Logistician edit surface. Only the assignee edge, the status change,
# the create and the Materialsammeluebersicht's two unlinks are named.
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
# Phase N - the Materialsammeluebersicht's two unlinks. Both are DELETE and the backend serves
# no other verb on either path; the carve-out is verb-blind, so if one ever gains a POST it
# arrives open and this line is what has to be revisited.
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/inventory/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/unlink$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/materials/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_readonly_family ""; }
# /notifications stays in the family because the prefix also carries /notification-rules, the
# admin surface that creates and deletes the rules every member's inbox is generated from. Only
# the four me-scoped inbox mutations are named. Naming a path opens every verb the backend serves
# on it, which for `/{id}` is GET and DELETE and for the other three is exactly one POST or DELETE.
if ($uri = "/api/v1/notifications/read-all") { set $krt_readonly_family ""; }
if ($uri = "/api/v1/notifications/read") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/notifications/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/notifications/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/read$") { set $krt_readonly_family ""; }
# /missions stays in the family because the prefix carries the whole planning surface - units,
# crews, steps, objectives, the mission itself. Only the caller's own participation and one
# crew removal are named.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/join$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/participants/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(/(check-in|check-out|payout-preference))?/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/crew/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_readonly_family ""; }
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
# Phase P - the Freigabe-Limits' two writes. The same one rule as above, and it opens exactly
# what the backend serves on those paths: PUT to set a ceiling, DELETE to clear it. Both answer
# with the account's whole settings object, which is what lets the section redraw from the
# answer instead of re-reading.
if ($uri ~ "^/api/v1/org-units/bank/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/approval-limit/(all-members|area-members|role/[A-Za-z0-9_-]{1,64}|user/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$") { set $krt_readonly_family ""; }
# Phase Q - the FIRST carve-out this guard has ever had under /users, which is why it is
# worth a line of its own. That prefix carries every other member's record and the whole
# member-admin surface, so nothing here may be widened: three exact me-scoped leaves, the
# third $-anchored on its uuid. `/users/<uuid>/memberships` beside them stays IN the family
# deliberately - the PATCH the backend serves on it is the org-unit admin write and keeps
# answering 405.
#
# Naming a path opens every verb the backend serves on it, which for the first two is the
# GET the row reads and the PUT it writes, and for the third exactly one PUT.
if ($uri = "/api/v1/users/me/payout-preference") { set $krt_readonly_family ""; }
if ($uri = "/api/v1/users/me/blueprint-sharing") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/users/me/read-announcement/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_readonly_family ""; }
# Phase R - the two 405s: the Logistician's `PUT /orders/<uuid>` and a manager's
# `PUT /operations/<uuid>`. Both paths are ALREADY admitted; only the read-only guard refuses
# them, which is the case the comment at the top of this guard calls the harder half.
#
# METHOD-SCOPED, and that is the whole point of the extra three lines each. A plain carve-out
# is verb-blind, and the backend serves DELETE on both of these paths - deleting an Auftrag
# and deleting an Operation. The app sends neither (its deletes are all on sub-paths), so a
# path-wide exception would open two destructive verbs nothing asks for. The compound-variable
# idiom below is the one this file already uses for the family flag itself: nginx cannot nest
# `if`, so a two-term condition is spelled by concatenating into one variable and testing it.
set $krt_put_only "";
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_put_only "P"; }
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_put_only "P"; }
if ($request_method = PUT) { set $krt_put_only "${krt_put_only}U"; }
if ($krt_put_only = "PU") { set $krt_readonly_family ""; }
# Phase T - the Auftrags-Familie's writes. `/api/v1/orders` is in the read-only family, so
# every one of these needs its own exception. All seven are path-wide rather than
# method-scoped, and that is safe here for a reason worth stating: on each of these paths the
# backend serves exactly the verb the app sends. The destructive ones live elsewhere -
# `DELETE /orders/<uuid>` is kept shut by phase R's method-scoped rule, and
# `DELETE …/materials/<uuid>` already has its own exception from phase N.
#
# `…/claims$` is the exception to the exception: it carries BOTH the GET the tab reads and
# the POST that upserts a Zusage. Opening it path-wide opens exactly those two.
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/claims$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/claims/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(handovers|item-handovers|items|priority)$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/orders/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/items/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/production$") { set $krt_readonly_family ""; }
# Phase U. The two bulk paths are POST-only on the backend, so a path-wide exception opens
# exactly the verb the app sends. `allocation` is carved out by the leaf group above, which
# this phase extended - it serves POST, PATCH and DELETE, and the app sends all three.
if ($uri ~ "^/api/v1/inventory/(bulk-checkout|bulk-rebook)$") { set $krt_readonly_family ""; }
# Phase V - the Einsatz planning set. `unit-ship-options` is a GET and is deliberately NOT
# here: it stays in the read-only family, where it belongs. Everything else in the phase is a
# write and needs its own exception.
#
# Each of these serves exactly the verbs the app sends: PATCH on the three section patches,
# PUT on party-lead and the two reorders, POST on the creates, PUT+DELETE on the leaves that
# have both. Checked against MissionController rather than assumed.
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(core|schedule|flags|party-lead|participants)$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/units/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/crew/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/frequencies/custom/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/frequencies/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/managers/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(steps|objectives)/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(steps|objectives)/reorder/slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/steps/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(done/)?slim$") { set $krt_readonly_family ""; }
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/objectives/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/slim$") { set $krt_readonly_family ""; }
# Phase X - the two hangar writes. Both are POST-only on the backend, so a path-wide exception
# opens exactly the verb the app sends. Nothing is carved out for Blaupausen (a write family,
# never in the read-only list) or for the two settings keys (a read family, on purpose).
if ($uri = "/api/v1/hangar/import/fleetview") { set $krt_readonly_family ""; }
if ($uri = "/api/v1/hangar/ships/home-location") { set $krt_readonly_family ""; }
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
# Raised from 256k in phase X, when the first two upload paths were admitted: the Fleetview import
# and the Blaupausen import preview, both `multipart/form-data`.
#
# THE NOTE THAT STOOD HERE SAID „per location, never globally", AND THAT IS NOT AVAILABLE HERE.
# `client_max_body_size` is settable in `server` and `location`, and NPM supplies the `proxy_pass`
# and its whole directive set from its own template - this Advanced block only ADDS to the server.
# A `location` written here would therefore have to repeat NPM's proxy configuration to keep
# proxying at all; `location /actuator` gets away with it only because it returns 404 and proxies
# nothing. Guessing at that duplication on a production host is worse than a stated ceiling.
#
# 4m, and the number is measured rather than round: the backend's own multipart comment records the
# Fleetview JSON topping out „under ~500 KB for a 100-ship hangar", and a blueprint export is the
# same order. 4m leaves an eightfold margin and is two orders below the backend's own 64 MB cap -
# which exists for the admin P4K catalog import, a path this allow-list deliberately never admits.
#
# What the ceiling actually exposes is bounded by the list above it: this vhost is default-deny,
# only two admitted paths accept a body of any size, and every write path behind it is
# authenticated. A larger ceiling therefore costs an authenticated member's bandwidth, not an
# anonymous one's.
client_max_body_size 4m;
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

   |                              Path                               | Without a token |                                                   Why                                                    |
   |-----------------------------------------------------------------|-----------------|----------------------------------------------------------------------------------------------------------|
   | `/api/v1/terms/document`                                        | **200**         | anonymous by design (ADR-0138): wording that must be read *before* agreeing cannot require having agreed |
   | `/api/v1/app/version-policy`                                    | **200**         | REQ-API-010 / D2: an app too old to log in must still learn that it is too old                           |
   | `/api/v1/missions/search`                                       | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/missions/<uuid>`                                       | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/missions/<uuid>` with `PUT`/`DELETE`                   | **405**         | the family is read-only on this vhost                                                                    |
   | `/api/v1/missions/<uuid>/finance-entries`                       | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/missions/<uuid>/finance-entries/summary`               | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/operations/search`                                     | **401**         | `isAuthenticated()`, and no chain matcher makes it public                                                |
   | `/api/v1/operations/<uuid>`                                     | **401**         | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>/finance-summary`                     | **401**         | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>/payouts`                             | **401**         | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>` with `PUT`/`DELETE`                 | **405**         | the family is read-only on this vhost                                                                    |
   | `/api/v1/terms/status`                                          | **401**         | me-scoped                                                                                                |
   | `/api/v1/terms/acceptance` (POST)                               | **401**         | me-scoped                                                                                                |
   | `/api/v1/me/active-org-unit`                                    | **401**         | me-scoped                                                                                                |
   | `/api/v1/me/capabilities`                                       | **401**         | me-scoped                                                                                                |
   | `/api/v1/inventory/aggregated`                                  | **401**         | chain requires a member role                                                                             |
   | `/api/v1/inventory/all/grouped`                                 | **401**         | same                                                                                                     |
   | `/api/v1/orders`                                                | **401**         | `isAuthenticated()`; the `POST` on the same path is refused by the read-only guard                       |
   | `/api/v1/orders/item-catalog`                                   | **401**         | `isAuthenticated()` since ADR-0149; the orderable finished items                                         |
   | `/api/v1/orders/item-catalog/<uuid>/blueprints`                 | **401**         | same; the blueprints one item may be built from                                                          |
   | `/api/v1/orders/items` (POST)                                   | **401**         | `isAuthenticated()`; raises an item order                                                                |
   | `/api/v1/orders/<uuid>`                                         | **401**         | `isAuthenticated()` + scope                                                                              |
   | `/api/v1/orders/<uuid>/assignees/<uuid>`                        | **401**         | `isAuthenticated()` + scope; self-assignment is open to every member, anyone else needs LOGISTICIAN      |
   | `/api/v1/orders/<uuid>/assignees/<uuid>/note`                   | **401**         | same, and locked on the assignee edge's own version                                                      |
   | `/api/v1/orders/<uuid>/status`                                  | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/missions/<uuid>/join`                                  | **401**         | `isAuthenticated()` + `canSeeMission`                                                                    |
   | `/api/v1/missions/<uuid>/participants/<uuid>/slim`              | **401**         | `canAccessParticipant` — the caller's own row, or a mission manager's                                    |
   | `…/participants/<uuid>/check-in/slim`                           | **401**         | same                                                                                                     |
   | `…/participants/<uuid>/payout-preference/slim`                  | **401**         | same                                                                                                     |
   | `/api/v1/finance-entries`                                       | **401**         | `isAuthenticated()` + member-or-above + `canSeeMission` on the body's mission                            |
   | `/api/v1/finance-entries/<uuid>`                                | **401**         | `isAuthenticated()`; owner-vs-admin is decided at the service seam                                       |
   | `/api/v1/operations/<uuid>/payouts/paid-out`                    | **401**         | `hasRole(MISSION_MANAGER)` + scope; taking a confirmation back additionally needs OFFICER or ADMIN       |
   | `…/org-units/bank/accounts/<uuid>/settings`                     | **401**         | `isAuthenticated()`; what the caller may change is stated in the answer, not in the chain                |
   | `…/bank/accounts/<uuid>/balance-target`                         | **401**         | `isAuthenticated()` + the responsible-holder seam                                                        |
   | `…/bank/accounts/<uuid>/visibility/…`                           | **401**         | same                                                                                                     |
   | `/api/v1/org-units/bank/balances`                               | **401**         | me-scoped to the accounts the caller may see                                                             |
   | `/api/v1/org-units/bank/accounts/<uuid>`                        | **401**         | same                                                                                                     |
   | `/api/v1/personal-inventory`                                    | **401**         | me-scoped; the same path answers POST, which is 401 anonymously too                                      |
   | `/api/v1/personal-inventory/<uuid>`                             | **401**         | me-scoped; PUT and DELETE likewise                                                                       |
   | `/api/v1/uex/locations/search`                                  | **401**         | `isAuthenticated()`                                                                                      |
   | `/api/v1/personal-blueprints`                                   | **401**         | me-scoped; POST likewise                                                                                 |
   | `/api/v1/personal-blueprints/<uuid>`                            | **401**         | me-scoped; PUT and DELETE likewise                                                                       |
   | `/api/v1/personal-blueprints/craftability`                      | **401**         | me-scoped                                                                                                |
   | `/api/v1/personal-blueprints/<uuid>/recipe`                     | **401**         | me-scoped; GET only, phase 5                                                                             |
   | `/api/v1/blueprints/products/search`                            | **401**         | `isAuthenticated()`                                                                                      |
   | `/api/v1/hangar/ships`                                          | **401**         | me-scoped; POST likewise                                                                                 |
   | `/api/v1/hangar/ships/<uuid>`                                   | **401**         | me-scoped; PUT and DELETE likewise                                                                       |
   | `/api/v1/ship-types`                                            | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/locations/home-locations`                              | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/inventory` (POST)                                      | **401**         | chain requires a member role                                                                             |
   | `/api/v1/inventory/all/stack/entries`                           | **401**         | chain requires a member role                                                                             |
   | `/api/v1/inventory/material/<uuid>`                             | **401**         | same; one material's entries, for the app's tablet pane                                                  |
   | `/api/v1/inventory/<uuid>/book-out`                             | **401**         | same, plus `canEditInventoryItem`                                                                        |
   | `/api/v1/inventory/<uuid>/personal-rebook`                      | **401**         | same                                                                                                     |
   | `/api/v1/inventory/<uuid>/note`                                 | **401**         | same                                                                                                     |
   | `/api/v1/materials/search`                                      | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/locations/search`                                      | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/users/search`                                          | **401**         | member records; `isAuthenticated()` and role-gated                                                       |
   | `/api/v1/materials/<uuid>/terminals`                            | **401**         | carved back **out** of the catalogue family with `/materials/matrix` (REQ-SEC-032) — UEX trade prices    |
   | `/api/v1/org-units/bank/accounts/<uuid>/transactions`           | **401**         | same                                                                                                     |
   | `/api/v1/hangar/my-ships`                                       | **401**         | me-scoped                                                                                                |
   | `/api/v1/hangar/squadron-overview`                              | **401**         | scoped to the active org unit                                                                            |
   | `/api/v1/announcement`                                          | **401**         | no chain matcher makes it public                                                                         |
   | `/api/v1/notifications`                                         | **401**         | me-scoped inbox                                                                                          |
   | `/api/v1/notifications/unread-count`                            | **401**         | me-scoped                                                                                                |
   | `/api/v1/notifications/stream`                                  | **401**         | me-scoped SSE                                                                                            |
   | `/api/v1/notifications/read-all`                                | **401**         | me-scoped write; POST only, phase 5                                                                      |
   | `/api/v1/notifications/read`                                    | **401**         | me-scoped write; DELETE only, phase 5                                                                    |
   | `/api/v1/notifications/<uuid>`                                  | **401**         | me-scoped; GET + DELETE, phase 5                                                                         |
   | `/api/v1/notifications/<uuid>/read`                             | **401**         | me-scoped write; POST only, phase 5                                                                      |
   | `/api/v1/notification-rules`                                    | **404**         | admin surface, NOT admitted — the four rows above must not have widened the prefix                       |
   | `/api/v1/users/me`                                              | **401**         | me-scoped                                                                                                |
   | `/api/v1/users/me/registration-status`                          | **401**         | me-scoped                                                                                                |
   | `/api/v1/users/me/memberships`                                  | **401**         | me-scoped                                                                                                |
   | `/api/v1/refinery-orders` (POST)                                | **401**         | phase M; `hasRole(KRT_MEMBER)` — the create form's write                                                 |
   | `/api/v1/locations/refineries`                                  | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/refining-methods`                                      | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/orders/<uuid>/material-collection`                     | **401**         | phase N; `isAuthenticated()` + `canSeeJobOrder`                                                          |
   | `/api/v1/orders/<uuid>/inventory/<uuid>/unlink`                 | **401**         | phase N; LOGISTICIAN / OFFICER / ADMIN + `canEditJobOrder`                                               |
   | `/api/v1/orders/<uuid>/materials/<uuid>`                        | **401**         | phase N; same gate                                                                                       |
   | `/api/v1/inventory/<uuid>/delivered`                            | **401**         | phase N; `isAuthenticated()` + `canEditInventoryItem` — the ROW's gate, not the order's                  |
   | `/api/v1/missions/<uuid>/units/<uuid>/crew/<uuid>/slim`         | **401**         | phase N; `canManageMission`. The deprecated sibling without `/slim` stays **404**                        |
   | `/api/v1/bank/deposits` (POST)                                  | **401**         | phase O; `hasRole(BANK_EMPLOYEE)` + per-account `canDeposit`                                             |
   | `/api/v1/bank/withdrawals` (POST)                               | **401**         | phase O; same, `canWithdraw`                                                                             |
   | `/api/v1/bank/transfers` (POST)                                 | **401**         | phase O; same, `canTransfer` on the SOURCE account                                                       |
   | `/api/v1/bank/transfer-fee-rate`                                | **401**         | phase O; `hasRole(BANK_EMPLOYEE)` only — an org-wide rate, no account in it                              |
   | `…/org-units/bank/accounts/<uuid>/approval-limit/all-members`   | **401**         | phase P; `isAuthenticated()` + `canConfigureApprovalLimits` on the account                               |
   | `…/approval-limit/area-members`                                 | **401**         | phase P; same                                                                                            |
   | `…/approval-limit/role/<code>`                                  | **401**         | phase P; same                                                                                            |
   | `…/approval-limit/user/<uuid>`                                  | **401**         | phase P; same                                                                                            |
   | `/api/v1/users/me/payout-preference`                            | **401**         | phase Q; me-scoped, `isAuthenticated()`; GET and PUT                                                     |
   | `/api/v1/users/me/blueprint-sharing`                            | **401**         | phase Q; same                                                                                            |
   | `/api/v1/users/me/read-announcement/<uuid>`                     | **401**         | phase Q; me-scoped write, PUT only                                                                       |
   | `/api/v1/orders/<uuid>` with `PUT`                              | **401**         | phase R; the Logistician's edit. `DELETE` on the same path stays **405**                                 |
   | `/api/v1/operations/<uuid>` with `PUT`                          | **401**         | phase R; a mission manager's edit. `DELETE` likewise stays **405**                                       |
   | `/api/v1/orders/lookup`                                         | **401**         | phase S; the Auftrag picker in the booking sheet                                                         |
   | `/api/v1/missions/lookup`                                       | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/operations/lookup`                                     | **401**         | phase S; the Operation picker on the Einsatz's Kern section                                              |
   | `/api/v1/job-types`                                             | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/orders/material-demand`                                | **401**         | phase T; the Materialbedarf screen, which showed a retry button that retried the same refusal            |
   | `/api/v1/orders/<uuid>/item-stock`                              | **401**         | phase T; the Verfügbarkeits-Chip on a sub-assembly                                                       |
   | `/api/v1/orders/<uuid>/claims` (`GET`, `POST`)                  | **401**         | phase T; the Zusagen tab and its upsert, on one path                                                     |
   | `/api/v1/orders/<uuid>/claims/<uuid>` with `DELETE`             | **401**         | phase T; withdrawing a Zusage. The only destructive verb this phase opens, and it is on the leaf         |
   | `/api/v1/orders/<uuid>/materials/<uuid>/inventory`              | **401**         | phase T; the Bestandszeilen an Übergabe is picked from                                                   |
   | `/api/v1/orders/<uuid>/handovers` with `POST`                   | **401**         | phase T; the Material-Übergabe                                                                           |
   | `/api/v1/orders/<uuid>/item-handovers` with `POST`              | **401**         | phase T; the Gegenstands-Übergabe                                                                        |
   | `/api/v1/orders/<uuid>/items` with `PUT`                        | **401**         | phase T; editing the ordered lines                                                                       |
   | `/api/v1/orders/<uuid>/items/<uuid>/production` with `POST`     | **401**         | phase T; recording a Herstellung                                                                         |
   | `/api/v1/orders/<uuid>/priority` with `PUT`                     | **401**         | phase T; „nach vorn/hinten“, the position as a query parameter                                           |
   | `/api/v1/inventory/bulk-checkout` with `POST`                   | **401**         | phase U; Sammel-Ausbuchen                                                                                |
   | `/api/v1/inventory/bulk-rebook` with `POST`                     | **401**         | phase U; Sammel-Umbuchen. The Standort-Picker beside it was already open, only the submit died           |
   | `/api/v1/inventory/<uuid>/allocation` (`POST`,`PATCH`,`DELETE`) | **401**         | phase U; the earmark. Three verbs on one path, and all three were refused                                |
   | `/api/v1/missions/<uuid>/(core\|schedule\|flags)` with `PATCH`  | **401**         | phase V; the three Einsatz sections, each with its own counter                                           |
   | `/api/v1/missions/<uuid>/party-lead` with `PUT`                 | **401**         | phase V                                                                                                  |
   | `/api/v1/missions/<uuid>/participants` with `POST`              | **401**         | phase V; „Teilnehmer hinzufügen“                                                                         |
   | `/api/v1/missions/<uuid>/unit-ship-options`                     | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/missions/<uuid>/units/…/slim` (all verbs)              | **401**         | phase V; Einheiten and their crew, slim only                                                             |
   | `/api/v1/missions/<uuid>/frequencies/…/slim`                    | **401**         | phase V                                                                                                  |
   | `/api/v1/missions/<uuid>/managers/<uuid>/slim`                  | **401**         | phase V                                                                                                  |
   | `/api/v1/missions/<uuid>/(steps\|objectives)/…/slim`            | **401**         | phase V; Ablauf and Ziele, which exist only as `/slim`                                                   |
   | `/api/v1/materials/prices-overview`                             | **401**         | phase W; **was 200** until this phase closed it (REQ-SEC-032)                                            |
   | `/api/v1/materials/profit-calculation`                          | **401**         | phase W; **was 500** — dispatched anonymously and crashing, which is not a gate either                   |
   | `/api/v1/materials/<uuid>/prices`                               | **401**         | phase W; **was 200**. Same UEX trade data as `matrix`, through another door                              |
   | `/api/v1/materials/<uuid>`                                      | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | `/api/v1/terminals`                                             | **401**         | phase W; the star-system filter page-walks it                                                            |
   | `/api/v1/material-exchange/released-item-ids`                   | **401**         | phase W                                                                                                  |
   | `/api/v1/material-exchange/item-offers` with `POST`             | **401**         | phase W; releasing a Gegenstand to the Börse                                                             |
   | `/api/v1/material-requests/item` with `POST`                    | **401**         | phase W                                                                                                  |
   | `/api/v1/material-exchange/offers/<uuid>/remark` with `PUT`     | **401**         | phase W                                                                                                  |
   | `/api/v1/material-requests/<uuid>` with `PUT`                   | **401**         | phase W                                                                                                  |
   | `/api/v1/personal-blueprints/overview` (+ `/owners`)            | **401**         | phase X; the Blaupausen-Übersicht and its owner list                                                     |
   | `/api/v1/personal-blueprints/batch` with `POST`                 | **401**         | phase X                                                                                                  |
   | `/api/v1/personal-blueprints/import/preview` with `POST`        | **401**         | phase X; `multipart/form-data`, and the reason the body ceiling moved                                    |
   | `/api/v1/personal-blueprints/import/apply` with `POST`          | **401**         | phase X; admitted WITH the preview — the pair the audit called latent and then work-destroying           |
   | `/api/v1/hangar/import/fleetview` with `POST`                   | **401**         | phase X; `multipart/form-data`                                                                           |
   | `/api/v1/hangar/ships/home-location` with `POST`                | **401**         | phase X                                                                                                  |
   | `/api/v1/settings/job_order.age_yellow_days` (and `…red_days`)  | **401**         | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path                              |
   | anything not on the list                                        | **404**         | default deny                                                                                             |

> [!important] ADR-0159 needs no paste at this edge, and that is the point
> The members-only change (REQ-SEC-052 / REQ-SEC-053) does not add, remove or reorder a
> single allow-list rule. Every path the app sends is still admitted; what changed is that
> the **backend** now refuses the caller behind them. The status column above is therefore
> almost entirely `401` where it used to read `200` or `403`, with no nginx work at all.
>
> The two exceptions answer as before: `/api/v1/terms/document` and
> `/api/v1/app/version-policy`. Both are `GET`-scoped in the backend matrix, so a `HEAD` on
> either answers `401` — the nightly probe asserts that, because a method-scoped rule above
> an all-verb one is how REQ-SEC-032 leaked a price query to a `HEAD` once.
>
> The `403` rows are the ones worth re-reading. They said `403` because their path sat under
> a `permitAll` stem: the request was dispatched and refused at the method seam, and the MVC
> advice rendered that as `403`. With the stem gone they are turned away at the entry point
> instead, which writes `401`. Same closure, different number — and the number is what the
> rollout check reads.

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
|----------------------------------------------|-----------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `/api/v1/personal-inventory`                 | **401**                                                                           |
| `/api/v1/personal-inventory/<uuid>`          | **401**                                                                           |
| `/api/v1/uex/locations/search`               | **401**                                                                           |
| `/api/v1/personal-blueprints`                | **401**                                                                           |
| `/api/v1/personal-blueprints/<uuid>`         | **401**                                                                           |
| `/api/v1/personal-blueprints/craftability`   | **401**                                                                           |
| `/api/v1/blueprints/products/search`         | **401**                                                                           |
| `/api/v1/hangar/ships`                       | **401**                                                                           |
| `/api/v1/hangar/ships/<uuid>`                | **401**                                                                           |
| `/api/v1/ship-types`                         | **401**                                                                           | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |
| `/api/v1/locations/home-locations`           | **401**                                                                           | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |
| `/api/v1/inventory/<uuid>/book-out`          | **401**                                                                           |
| `/api/v1/users/search`                       | **401**                                                                           |
| `/api/v1/materials/search`                   | **401**                                                                           | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |
| `/api/v1/locations/search`                   | **401**                                                                           | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |
| `POST /api/v1/inventory/all`                 | **404** — the every-member list is not on the allow-list at all                   |
| `/api/v1/orders/<uuid>/assignees/<uuid>`     | **401**                                                                           |
| `/api/v1/orders/<uuid>/status`               | **401**                                                                           | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |
| `/api/v1/missions/<uuid>/join`               | **401**                                                                           |
| `…/participants/<uuid>/check-in/slim`        | **404** — the guard resolves the row before it judges the caller, see REQ-SEC-037 |
| `/api/v1/finance-entries`                    | **401**                                                                           |
| `/api/v1/operations/<uuid>/payouts/paid-out` | **401**                                                                           |
| `…/bank/accounts/<uuid>/settings`            | **401**                                                                           |
| `…/bank/accounts/<uuid>/balance-target`      | **401**                                                                           |
| `POST /api/v1/bank/deposits`                 | **404** at the time of phase I; **401** since phase O admitted it                 |
| `POST /api/v1/orders`                        | **405** — the public request form stays refused on this vhost                     |
| `POST /api/v1/hangar/import/fleetview`       | **404** at the time of phase 4; **401** since phase X admitted it                 |

A **405** on any row listed **401** would be the read-only guard swallowing a write the phase is
supposed to open: `/personal-inventory` and `/personal-blueprints` must NOT be in the guard's family
list, while `uex` and `blueprints` must — the picker and the location search are reads, and the
catalogue behind them has writes this vhost never admits. `POST /api/v1/orders` is the one row where
**405** is the right answer, and it reads that way because the path *is* admitted — as the phase-2
queue — and only the verb is refused.

A **404** where a **401** is listed means the path did not match the allow-list — a typo in the
regex, most likely a `<uuid>` group that lost a brace. Anything other than **404** on
`/inventory/all` or `/bank/admin/wipe-reset` means the opposite: something was admitted that should
not have been — a **405** included, because reaching the read-only guard at all means the path got
past the deny. That one is worth stopping for.

> [!note] `/bank/deposits` used to be the canary named here, and is one no longer
> Phase O admitted it (2026-09-03). The exclusion it stood for was reasoned from a claim that no
> artboard drew the direct booking, and artboard 9 does. A canary has to guard a decision that is
> still a decision, so the admin wipe-reset — web-only by owner decision — took its place.

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
   foreach ($p in '/api/v1/bank/admin/wipe-reset','/api/v1/inventory/all') { '{0,-46} {1}' -f $p, (curl.exe -s -o NUL -w '%{http_code}' -X POST "https://api.profit-base.online$p") }
   ```

   Expected: `404` for both — neither is on the allow-list, and the deny answers before the
   read-only guard can. A `401` or a `405` here would mean a path was admitted that should not have
   been. `/hangar/import/fleetview` was the third path in this list until phase X admitted it; it
   answers `401` now and is checked by the admitted table above.

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
`/api/v1/bank/admin/audit`, and `/api/v1/bank/accounts/<uuid>/approval-tiers`.

> [!warning] Corrected 2026-09-03 — this list also named the four direct-booking paths, and they
> were excluded on a premise that was never true
> `/bank/deposits`, `/bank/withdrawals`, `/bank/transfers` and `/bank/transfer-fee-rate` are
> admitted by **phase O**. The reason given for keeping them out — „no artboard draws them" — was
> wrong on the day it was written: design chapter 12 artboard 9 draws the sheet, round 8 decided it
> stays, `REQ-APP-BANK-016` specifies it, and the app shipped it with tests. The four rules were
> the only thing standing between a built feature and production, and the probe that asserted their
> 404 turned that mistake into a nightly green light.

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

## Phase N — the Materialsammelübersicht, and one crew removal

**Five paths, closing three of the 75 the 2026-09-03 audit found.** That audit compared, for the
first time, the paths the app sends against the rules this vhost carries. Three of the confirmed
defects are closed here — the collection read, the delivered flag and the crew removal — and the
other two paths are the collection's unlinks, which the audit had filed as *latent*: real blockades
behind a control that was never enabled. Opening them in the same change that enables the control is
the difference between de-fusing them and discovering them.

Each is a **pair.** A change to the app alone or to this list alone leaves the defect standing, or
replaces it with a worse one. **72 remain.**

|         Screen          |                       Paths                       | Verbs  |
|-------------------------|---------------------------------------------------|--------|
| Einsatz → Einheiten     | `…/missions/<uuid>/units/<uuid>/crew/<uuid>/slim` | DELETE |
| Materialsammelübersicht | `…/orders/<uuid>/material-collection`             | GET    |
| Materialsammelübersicht | `…/orders/<uuid>/inventory/<uuid>/unlink`         | DELETE |
| Materialsammelübersicht | `…/orders/<uuid>/materials/<uuid>`                | DELETE |
| Materialsammelübersicht | `/api/v1/inventory/<uuid>/delivered`              | PATCH  |

**The crew removal needed both halves.** The app was sending the legacy full-DTO
`DELETE …/crew/{crewId}`, which is `@ApiDeprecation`-marked with a sunset — so the fix is not to
admit what it sends. It now sends the `/slim` replacement, which answers `204` and therefore makes
the app re-read the Einsatz instead of folding the answer, and *that* path is what this phase
admits. Switching the app alone would have traded one 404 for another; admitting the old path alone
would have written a rule with an expiry date into a list nobody re-reads. The **adding** half
(`POST …/units/{unitId}/crew`) stays out: a phone takes somebody off a slot, it does not assemble an
Einheit.

**The Materialsammelübersicht was write-dead for every role, and that hid the blockade.** Its three
controls are drawn from `OrderCollectionState.allowed`, and the only function that ever set it had
no caller — so the screen sent none of its three writes, from any account, and the three 404s
underneath were invisible. Wiring the gate without this phase would have converted two harmless
unreachable calls into visible errors, which is why the audit filed them as *paired*. The gate is
now read off the order's own `canEdit`, which is `isLogisticianOrAbove() && canEditJobOrder(id)` —
the identical expression the two unlink endpoints carry in their own `@PreAuthorize`, so the screen
agrees with the server by construction rather than by a rule written twice.

**The read comes with the writes.** `material-collection` is what the screen lists; opening the
three writes without it would have opened a door into an empty room. It gets **no** carve-out below,
so a write the backend might one day serve on that path arrives `405` rather than open.

**One gate on the screen is not one gate on the server.** The delivered PATCH lives on `/inventory`
and is gated by `canEditInventoryItem` — the *row's* rule, not the order's. A Logistician editing an
order whose linked stock belongs to another unit is refused there with a `403`, which the screen
shows. That is the intended answer and not a reason to widen anything: the alternative is a client
that silently hides a control the server would have allowed.

### What to expect afterwards

|                           Path                           | Anonymous status |
|----------------------------------------------------------|------------------|
| `GET /api/v1/orders/<uuid>/material-collection`          | **401**          |
| `DELETE /api/v1/orders/<uuid>/inventory/<uuid>/unlink`   | **401**          |
| `DELETE /api/v1/orders/<uuid>/materials/<uuid>`          | **401**          |
| `PATCH /api/v1/inventory/<uuid>/delivered`               | **401**          |
| `DELETE …/missions/<uuid>/units/<uuid>/crew/<uuid>/slim` | **401**          |

All five are pinned in `ApiVhostAnonymousSurfaceTest` **before** this table was written, which is
the step phase M skipped and spent three red probe nights on. `DELETE …/crew/<uuid>` without
`/slim`, and `POST …/units/<uuid>/crew`, must still answer **404**.

---

## Phase O — the direct booking, and an exclusion that was never true

**This phase is a correction, not an addition.** Four paths — `POST /bank/deposits`,
`/bank/withdrawals`, `/bank/transfers` and `GET /bank/transfer-fee-rate` — were kept off this
allow-list from phase L onwards with the reason written into the block itself:

> `/bank/deposits`, `/bank/withdrawals`, `/bank/transfers`, `/bank/transfer-fee-rate` — the direct
> booking forms. No artboard draws them; a booking that had no request stays a browser act.

**The first half of that sentence is false, and the second follows from it.** Design chapter 12
**artboard 9** draws the sheet („Direktbuchung — Verwaltung (Ein/Aus/Um)"), design round 8 settled
it explicitly — *„die Direktbuchung BLEIBT, weil sie den Fall deckt, für den niemand einen Antrag
stellt (Bargeld-Übergabe im Spiel, Korrektur einer Fremdbuchung)"* — `REQ-APP-BANK-016` specifies
it, and the app shipped it with tests and all acceptance criteria met. These four rules were the
only thing between a finished feature and the members using it.

|      Screen       |              Paths               | Verbs |
|-------------------|----------------------------------|-------|
| Direktbuchung     | `/api/v1/bank/deposits`          | POST  |
| Direktbuchung     | `/api/v1/bank/withdrawals`       | POST  |
| Direktbuchung     | `/api/v1/bank/transfers`         | POST  |
| Gebühren-Vorschau | `/api/v1/bank/transfer-fee-rate` | GET   |

**Four exact lines, no regex.** The backend serves exactly one verb on each — POST on the three
bookings, GET on the rate — and `bank` is not in the read-only family, so an exact line opens
precisely that verb and nothing beneath it. No carve-out is needed or wanted.

**The gate is not Bank-Management, and both this runbook and the app had it wrong.** All four ask
for `hasRole('BANK_EMPLOYEE')`; the three bookings add a **per-account** grant on top —
`canDeposit` / `canWithdraw` / `canTransfer`, with management and admin unrestricted
(`BankSecurityService.hasCapability`). The web has never gated its own entry on anything more: its
CTA appears whenever at least one active account is visible. The app, following artboard 9's state
list („403 (Rolle Bank-Management fehlt)"), locked the entry on Bank-Management and so refused
exactly the caller the Grants tab exists to create. That lock is gone; the per-account half is
decided per call by the backend and surfaces as a 403 the sheet shows.

> [!warning] The 202 had to be fixed BEFORE these rules could be pasted
> `POST /bank/withdrawals` and `/bank/transfers` do not always book. Over the KRT employee ceiling
> the server files the attempt as a band-routed approval request and answers **202** with a
> `pendingRequest` where a booking carries a `transaction` (REQ-BANK-047, ADR-0109). The app sent
> both through a helper that treats **any** 2xx as success and discards the body — so on a 202 the
> sheet closed, the balance had not moved, and nothing said why. Invisible while the edge answered
> 404; a live money-path defect the moment it did not. The app now reads the answer and says
> „Zur Freigabe eingereicht". **Do not paste this phase onto a build that predates that fix.**

**What still stays out, and why it is a different kind of exclusion.** `/api/v1/bank/admin/**`
(wipe-reset, bank audit) is web-only by **owner decision**, and
`/bank/accounts/<uuid>/approval-tiers` is not one of the app's four tabs. Both remain unnamed and
both are still probed at 404. The lesson of this phase is worth keeping beside them: an exclusion
that cites a *fact* („no artboard draws them") has to be re-checked when the fact can change, and
an exclusion that cites a *decision* does not.

### What to expect afterwards

|                 Path                 | Anonymous status |
|--------------------------------------|------------------|
| `POST /api/v1/bank/deposits`         | **401**          |
| `POST /api/v1/bank/withdrawals`      | **401**          |
| `POST /api/v1/bank/transfers`        | **401**          |
| `GET /api/v1/bank/transfer-fee-rate` | **401**          |

All four are pinned in `ApiVhostAnonymousSurfaceTest` and frozen in `ExternalContractTest`, whose
reachability guard now asserts these very rules admit them. `/api/v1/bank/admin/wipe-reset`,
`/api/v1/bank/admin/audit` and `/api/v1/bank/accounts/<uuid>/approval-tiers` must still answer
**404** — `edge-deny-probe.yml` moved the four booking paths onto the 401 side and kept those three
where they are.

---

## Phase P — the Freigabe-Limits

**A gap of the same class as phase O, found while writing it.** The Freigabe-Limits shipped in the
app on 2026-08-30 (`REQ-APP-BANK-017`, design ch. 12 artboard 10): the ceiling that decides which
member may approve a booking on an account, set for everybody, for the Bereich, for a role bucket,
or for one named member. The section draws correctly, because the `/settings` GET that carries the
current values was admitted in phase 3 — and **every „Setzen" and „Entfernen" has answered 404**.

It is worse hidden than phase O's four paths, because it was never even *stated*. `approval-limit`
appears nowhere in this runbook: not on the allow-list, and not among the deliberate exclusions.

> [!note] Whether these eight are among the 75 is not recorded, so this phase does not move that
> number
> The audit's own summary says it checked „all 113 paths the app can send", which would include
> them — but no per-family breakdown of the 75 was ever committed, so nobody can say which side of
> the line these fell on without re-running it. Asserting either way would put a number in this
> runbook that nothing can check. **72** stands where phase N left it.
> The exclusion that *is* written down names `/bank/accounts/<uuid>/approval-tiers` — the KRT ladder
> editor, a **different** endpoint the app does not call and which artboard 10 explicitly replaced.
> A reader checking whether the limits were deliberately kept out would have found that line and
> stopped.

|     Screen      |                             Paths                             |    Verbs    |
|-----------------|---------------------------------------------------------------|-------------|
| Freigabe-Limits | `…/org-units/bank/accounts/<uuid>/approval-limit/all-members` | PUT, DELETE |
| Freigabe-Limits | `…/approval-limit/area-members`                               | PUT, DELETE |
| Freigabe-Limits | `…/approval-limit/role/<code>`                                | PUT, DELETE |
| Freigabe-Limits | `…/approval-limit/user/<uuid>`                                | PUT, DELETE |

**One rule, not four.** They are one screen and one endpoint family, they share a request body
(`{"limit": …}`) and they all answer with the account's whole settings object. Four lines that can
only ever be edited together are four chances to edit three of them. The alternation is
`$`-anchored and reuses the visibility rule's role-code class, so `approval-tiers` beside it stays
unnamed and still answers 404.

**`/org-units` is a read-only family**, so the writes need the carve-out as well as the rule — and
the carve-out opens exactly what the backend serves on those paths, which is the PUT and the
DELETE.

> [!note] Why the response is worth having, not just the write
> Both verbs answer with `OrgUnitBankAccountSettingsDto`, so the section redraws from the answer
> instead of re-reading. That also means the response is now part of the frozen contract:
> `approvalLimits` and `canConfigureApprovalLimits` were **missing** from the already-frozen
> `/settings` operation — the app has always read both — and freezing the writes without them would
> have left the read they depend on unguarded.

### What to expect afterwards

|                  Path                  | Anonymous status |
|----------------------------------------|------------------|
| `PUT …/approval-limit/all-members`     | **401**          |
| `DELETE …/approval-limit/area-members` | **401**          |
| `PUT …/approval-limit/role/<code>`     | **401**          |
| `DELETE …/approval-limit/user/<uuid>`  | **401**          |

Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written, and frozen in
`ExternalContractTest`, whose reachability guard cross-checks the rule above.
`PATCH /api/v1/bank/accounts/<uuid>/approval-tiers` must still answer **404**.

---

## Phase Q — the member's own two settings, and the Aushang's read marker

**Three me-scoped leaves, and the first carve-out `/users` has ever had.** Reported from a device
on 2026-09-05: in Einstellungen, „Auszahlungspräferenz" and „Blueprints mit Org teilen" are greyed
out and cannot be tapped — on every account, since the first release.

**The cause is the quietest shape this list produces.** Both rows are drawn `enabled` only once
their value has arrived, and the `GET` that would deliver it was admitted by no rule. So the read
answered `404`, the app logged it, and the rows sat in exactly the state a never-set value would
produce. Nothing looked broken; two settings simply looked like they had no value and could not be
given one.

|        Screen         |                 Paths                 |  Verbs   |
|-----------------------|---------------------------------------|----------|
| Einstellungen → Konto | `/api/v1/users/me/payout-preference`  | GET, PUT |
| Einstellungen → Konto | `/api/v1/users/me/blueprint-sharing`  | GET, PUT |
| Übersicht (Aushang)   | `…/users/me/read-announcement/<uuid>` | PUT      |

**The read had to come with the write, and this is the pair that proves the rule.** Both settings
are columns of one `User` row and share one optimistic-lock version, which the app echoes from
whatever its read returned. Admitting only the `PUT` would have made both rows operable and every
tap a `409` — or worse, on a row still at version `0`, a write that succeeds by accident against a
version nobody read. The audit filed them as a pair for exactly this reason.

**The third path is here because it is the same family and the same `/users/me` carve-out.** The
Aushang's read marker has its own visible symptom — the „UNGELESEN" band is marked optimistically,
the `PUT` is refused, and the band springs back on every dashboard load. One rule closes it.

> [!warning] `/users` is not `/users/me`
> The prefix carries every other member's record and the member-admin surface. Phase 2 already
> settled that this vhost names me-scoped leaves one at a time and never the stem; phase Q keeps
> that and extends it to the read-only guard, which until now had no `/users` exception at all.
> `/users/<uuid>/memberships` stays **in** the family on purpose — the `PATCH` the backend serves
> on it is the org-unit admin write, and it keeps answering `405`.

### What to expect afterwards

|                   Path                    | Anonymous status |
|-------------------------------------------|------------------|
| `GET /api/v1/users/me/payout-preference`  | **401**          |
| `PUT /api/v1/users/me/blueprint-sharing`  | **401**          |
| `PUT …/users/me/read-announcement/<uuid>` | **401**          |

Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written, and frozen in
`ExternalContractTest`, whose reachability guard cross-checks the rules above.
`PATCH /api/v1/users/<uuid>/memberships` must still answer **405**.

---

## Phase R — the two 405s, and the first method-scoped exception

**No new path is admitted.** Both `/api/v1/orders/<uuid>` and `/api/v1/operations/<uuid>` have been
on the allow-list since phase 2 and 3; what refuses their edits is the read-only guard, which is
verb-blind by design. This is the case the comment at the top of that guard already names as *„the
harder half"*: opening one write means saying so explicitly rather than widening a family.

Two of the 75, and the only two the audit classed as **405** rather than 404 — the path is
reachable, the verb is not.

|        Screen        |            Path             | Verb |
|----------------------|-----------------------------|------|
| Auftrag bearbeiten   | `/api/v1/orders/<uuid>`     | PUT  |
| Operation bearbeiten | `/api/v1/operations/<uuid>` | PUT  |

> [!warning] A plain carve-out would have opened the two DELETEs with them
> The backend serves `DELETE` on **both** of these paths — deleting an Auftrag and deleting an
> Operation — and a carve-out clears the flag for every verb. The app sends neither: its deletes
> are all on sub-paths (`…/claims/<uuid>`, `…/materials/<uuid>`, `…/inventory/<uuid>/unlink`). So
> the exception is written **method-scoped**, and `DELETE` on both paths keeps answering `405`.
>
> The idiom is the one this guard already uses for its own flag: nginx cannot nest `if`, so a
> two-term condition is spelled by concatenating into one variable and testing the result.

**The requester's edit is a different path and is not opened here.** `PUT /orders/<uuid>/requested`
is the narrower form a member of the requesting unit may send; the app learned to send it in
basetool-android#129, and it needs its own rule. Until then a requester still reaches the
Logistician's path and is refused by the backend with a `403` — a refusal about *permission*, which
is the truthful one, rather than the `405` about a verb they were entitled to use.

### What to expect afterwards

|              Path               | Anonymous status |
|---------------------------------|------------------|
| `PUT /api/v1/orders/<uuid>`     | **401**          |
| `PUT /api/v1/operations/<uuid>` | **401**          |
| `DELETE` on either              | **405**          |

The `405` rows are the assertion that matters here: they prove the exception stayed method-scoped.
Both are pinned in `ApiVhostAnonymousSurfaceTest` and probed nightly.

---

## Phase S — the four pickers that answered „there are none"

**The class the audit called unreportable.** Four reads, each feeding a picker, each refused at the
edge — and none of them produced an error a member could describe. A picker is one field on a form
about something else, so the app swallows its failure on purpose: a banner over the Kern section
about a lookup would be about the wrong thing. That decision is right. Combined with a `404` it
means the control renders **empty**, and an empty picker reads as an answer.

|               Screen               |            Path             |
|------------------------------------|-----------------------------|
| Lager → Buchung: Auftrag zuordnen  | `/api/v1/orders/lookup`     |
| Lager → Buchung: Einsatz zuordnen  | `/api/v1/missions/lookup`   |
| Einsatz → Kern: Operation zuordnen | `/api/v1/operations/lookup` |
| Einsatz → Teilnehmer / Einheiten   | `/api/v1/job-types`         |

**`job-types` is the worst of the four**, because it does not merely show an empty list: the tab
says the organisation has defined **no CREW functions**. That is a statement about the
organisation, produced by a refused request.

**No carve-out for any of them.** All four are `GET`s inside read-only families, so naming the path
is the whole change — nothing here opens a verb.

**`/job-types` is named exactly, although the app appends a query string.** This guard matches on
`$uri`, which stops at the `?`; the parameters (`archetype`, `page`, `size`) are frozen in the
contract instead, where a rename would actually be caught.

> [!warning] The four do not answer alike, and every number here was measured before it was written
> Grouping them in one assertion is what hid it. `/orders/**` and `/operations/**` are
> authenticated in the filter chain, so the entry point turns them away before dispatch: **401**.
> `GET /missions/**` is `permitAll` — the whole Einsatz read surface is, so a guest can see the
> board — so that one is dispatched and refused at the method seam: **403**.
>
> And `/job-types` answers **200**. It is anonymous **by design**: `JobTypeController`'s own Javadoc
> says „Read is public; mutations are OFFICER/ADMIN", and the list carries role names and nothing
> else — no member, no org unit, no Einsatz is reachable through it. It sits in the same `permitAll`
> catalogue block as `/ship-types`, `/materials/search` and `/refining-methods`, every one of which
> this runbook already records as anonymous. Admitting it publishes a catalogue that was already
> public; the pin is what keeps that a decision rather than a side effect (REQ-SEC-037).
>
> Phase M is the reason this order is fixed: it wrote its table by reasoning about the form around
> the field instead of the rule that judges the caller, generated the nightly probe from the table,
> and spent three red nights on it.

### What to expect afterwards

|            Path             | Anonymous status |
|-----------------------------|------------------|-----------------------------------------------------------------------------|
| `/api/v1/orders/lookup`     | **401**          |
| `/api/v1/operations/lookup` | **401**          |
| `/api/v1/missions/lookup`   | **401**          | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |
| `/api/v1/job-types`         | **401**          | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |

Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written.

### What this makes whole, and what it only makes reportable

A picker is worth filling only if the control it feeds can save. Two of these can, today; three
fill ahead of their write, and that is stated here rather than discovered on the host.

|                        Picker                         |                                        The write it feeds                                         |                            After phase S                             |
|-------------------------------------------------------|---------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| Auftrag/Einsatz in the **Einbuchung**                 | `POST /api/v1/inventory`, which carries `jobOrderAllocations` and `missionAllocations` **inline** | **Whole.** That path has been admitted and carved out since phase 2. |
| Funktion on a **Teilnehmer** (`archetype=MISSION`)    | `PUT …/missions/<uuid>/participants/<uuid>/slim`                                                  | **Whole.** Admitted and carved out in phase 2.                       |
| Auftrag/Einsatz on an **existing Lager row**          | `…/inventory/<uuid>/allocation`                                                                   | Fills, save refused — the Lager write phase.                         |
| Operation on the Einsatz's **Kern**                   | `PATCH …/missions/<uuid>/core`                                                                    | Fills, save refused — the Einsatz planning set.                      |
| Funktions-Chips on a **Crew-Slot** (`archetype=CREW`) | `PUT …/missions/<uuid>/units/<uuid>/crew/<uuid>`                                                  | Fills, save refused — the Einsatz planning set.                      |

**The three that fill ahead of their write are still an improvement, and the reason is the audit's
own.** The class this phase closes is *„falscher Leerzustand"* — a wrong empty state that a member
cannot report, because nothing about it looks like a fault. A picker that fills and then fails on
save is an ordinary error message: it names itself, it can be described, and it lands in the same
bucket as every other refused write on that screen. The Crew chips are the clearest case — every
write on the Einheiten tab is refused already (adding a unit, editing it, deleting it, putting
somebody aboard, taking them off), so the chips appear on a surface that was write-dead before they
did.

> [!note] The audit tracked the Crew-Chip write as a *latent* defect, armed by exactly this change
> `PUT …/units/<uuid>/crew/<uuid>` was not among the 75: the chips were never drawn, so the call
> was unreachable. Phase S draws them. It is recorded here because a latent entry that goes live
> silently is how a ledger stops being trustworthy.

---

## Phase T — the Auftrags-Familie, and the pairs that do not work apart

**The audit's own block of ten, minus the one phase R already opened.** Nine rules: four reads and
five writes, and they are one phase rather than two because the pairs inside it are useless apart.

|                 Screen                 |                        Path                        |   Verb    |
|----------------------------------------|----------------------------------------------------|-----------|
| Materialbedarf (Aufträge-Überlauf)     | `/api/v1/orders/material-demand`                   | GET       |
| Verfügbarkeits-Chip an Unterbaugruppen | `/api/v1/orders/<uuid>/item-stock`                 | GET       |
| Zusagen-Tab (SK-Aufträge)              | `/api/v1/orders/<uuid>/claims`                     | GET, POST |
| Zusage zurückziehen                    | `/api/v1/orders/<uuid>/claims/<uuid>`              | DELETE    |
| Lagerzeilen im Übergabe-Sheet          | `/api/v1/orders/<uuid>/materials/<uuid>/inventory` | GET       |
| Material-Übergabe erfassen             | `/api/v1/orders/<uuid>/handovers`                  | POST      |
| Gegenstands-Übergabe erfassen          | `/api/v1/orders/<uuid>/item-handovers`             | POST      |
| Auftrag bearbeiten (Gegenstände)       | `/api/v1/orders/<uuid>/items`                      | PUT       |
| Herstellung erfassen                   | `/api/v1/orders/<uuid>/items/<uuid>/production`    | POST      |
| Auftrags-Priorität ändern              | `/api/v1/orders/<uuid>/priority`                   | PUT       |

### Why the reads and the writes ship together

Two of these pairs are the reason. **The Zusagen list carries its own upsert and withdrawal**: the
tab, the pledge button and the withdrawal all hang on `…/claims`, so admitting the read alone gives
a member a populated list with two dead buttons. And **the Bestandszeilen read is what makes an
Übergabe submittable at all** — without a row there is no `inventoryItemId` to send, so the submit
button stays disabled; the audit filed `POST …/handovers` as *latent* for exactly that reason, and
this phase is what arms it.

`material-demand` is the one that was loudest and still unfixable: its screen shows a hard error
branch with *„Erneut versuchen"*, and every retry repeated the same blocked request.

### The carve-outs are path-wide, and here that is safe

Unlike phase R, none of these needs method scoping — **on each of these paths the backend serves
exactly the verb the app sends.** That was checked against the controller mappings rather than
assumed:

- `…/items` serves `PUT` only (`…/items/requested` is a different path, and the collection-level
  `/api/v1/orders/items` is a different path again).
- `…/priority`, `…/handovers`, `…/item-handovers` and `…/items/<uuid>/production` each serve one
  verb.
- `…/claims` serves `GET` and `POST`, which are the two this phase wants.
- `…/claims/<uuid>` serves `DELETE` only.

**The destructive verbs stay where they were.** `DELETE /api/v1/orders/<uuid>` — deleting the whole
Auftrag — is kept shut by phase R's method-scoped rule, and every carve-out here is on a leaf
*under* the id, never on the id itself. The nightly probe keeps asserting that `405`.

> [!note] `…/priority` takes its position as a query parameter and sends no body
> `PUT …/priority?priority=1`, and no `version` either: the service reorders the whole queue under
> a pessimistic write lock, so an optimistic version would suggest a conflict check that does not
> happen. The vhost guard matches on `$uri`, which stops at the `?`, so the rule is unaffected; the
> parameter is frozen in the contract instead, where a rename would be caught.

### What to expect afterwards

|                    Path                    | Anonymous status |
|--------------------------------------------|------------------|
| all nine, every verb                       | **401**          |
| `DELETE /api/v1/orders/<uuid>` (unchanged) | **405**          |

Nothing under `/api/v1/orders` is `permitAll` except the item catalogue and the two creates, and
those are `authenticated` explicitly — so unlike phase S's pickers there is no seam to fall through
and no `403` among them. Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written.

---

## Phase U — the three Lager writes

Three rules, and the logistics half of the audit's step 4. Two of them are the bulk actions on the
Lager list; the third is the earmark that ties a stock row to an Auftrag or an Einsatz.

|                Screen                 |                 Path                  |        Verb         |
|---------------------------------------|---------------------------------------|---------------------|
| Sammel-Ausbuchen                      | `/api/v1/inventory/bulk-checkout`     | POST                |
| Sammel-Umbuchen                       | `/api/v1/inventory/bulk-rebook`       | POST                |
| Zuordnung (Bestand → Auftrag/Einsatz) | `/api/v1/inventory/<uuid>/allocation` | POST, PATCH, DELETE |

**The allocation is the worst of the three, and not because it is three verbs.** The app's save loop
is sequential and version-chained: it writes one row, takes the new `version` from the answer, and
uses it for the next. The first refusal therefore leaves the counter at zero and **nothing is ever
written** — not a partial save, not a half-applied sheet. A member re-picks and re-submits into the
same wall.

**Sammel-Umbuchen is the one that looked closest to working.** `/api/v1/locations/search` has been
admitted since phase 2, so the Standort-Picker fills, the member chooses a place and presses save
— and only then does it die, with the selection still standing.

### The allocation rides an existing rule rather than getting its own

`^/api/v1/inventory/<uuid>/(book-out|personal-rebook|note|delivered|allocation)$` — the leaf group
that already carried four names gains a fifth, in the allow-list and in the carve-out alike. Same
shape, same family, same exception; a separate pair of lines would have said the same thing twice
and given the next reader two places to keep in step.

The two bulk paths are `POST`-only on the backend, so their path-wide carve-out opens exactly the
verb the app sends.

> [!note] `bulk-rebook` sends a `mode`, and the app only ever sends one of its three values
> `LOCATION` — never `PERSONALIZE` or `DEPERSONALIZE`, which the web uses. It is frozen with all
> three constants, because the failure this guards against is a *reordered* enum rather than a
> shortened one: renaming a value the app does not send looks harmless in the document and is not,
> if the list is reordered rather than extended.

### What to expect afterwards

|              Path               | Anonymous status |
|---------------------------------|------------------|
| both bulk paths                 | **401**          |
| `…/allocation`, all three verbs | **401**          |

Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written.

---

## Phase V — the Einsatz planning set

**The audit's largest block, and its own recommendation was to do it as one reviewable rule
section.** Everything a Kommandoleiter builds an Einsatz out of: the three folded sections, the
party lead, adding a Teilnehmer, the Einheiten and their crew, the Frequenzen, the Verwalter, and
the Ablauf and Ziele.

|                 Section                 |                          Paths                           |
|-----------------------------------------|----------------------------------------------------------|
| Kern, Zeitplan, Flags                   | `…/(core\|schedule\|flags)` — `PATCH`                    |
| Party-Lead, Teilnehmer, Schiffsoptionen | `…/party-lead`, `…/participants`, `…/unit-ship-options`  |
| Einheiten und Crew                      | `…/units/slim`, `…/units/<uuid>/slim`, `…/crew/slim`     |
| Frequenzen                              | `…/frequencies/custom/slim`, `…/frequencies/<uuid>/slim` |
| Verwalter                               | `…/managers/<uuid>/slim`                                 |
| Ablauf und Ziele                        | `…/(steps\|objectives)/…/slim`                           |

### Slim only, and that is the decision worth reading

Seven of these paths have a full-DTO twin the app used to send, and **every one of those twins
carries an `@ApiDeprecation` with a sunset of `2026-10-20`.** Naming them here would have been work
with an end date six weeks out. The app was moved onto the slim endpoints instead
(basetool-android#140), in the same week, and only the slim paths are admitted.

> [!success] One of them repaired without any rule at all
> `PUT …/units/<uuid>/crew/<uuid>/slim` has been admitted since **phase N**, because the crew
> *removal* needed exactly that path. The audit had filed the Funktions-Chips write as a *latent*
> defect — the chips were never drawn, since `GET /api/v1/job-types` was refused — and **phase S**
> admits that catalogue. So the app change alone finished it: catalogue admitted, chips drawn, and
> the write already pointed at an open path.

`Ablauf` and `Ziele` never had a plain variant — they exist **only** as `/slim` — so nothing there
changes on the app side.

### Every rule is a leaf under the id, and none is the id

`DELETE /api/v1/missions/<uuid>` deletes the whole Einsatz. The app never sends it, the path has no
carve-out, and it gets none here: the nightly probe keeps asserting its `405`. The same reading
applies inside the phase — `…/frequencies/custom/slim` is named separately from
`…/frequencies/<uuid>/slim` rather than folded into one alternation, because `custom` is not a
uuid and folding them would loosen both.

> [!note] The two deletes carry their section counter as a **query parameter**
> `DELETE …/steps/<uuid>/slim?stepsVersion=n` and the objectives twin. It is the one place in this
> phase where the optimistic version is not in the payload, and losing it would not fail the write
> — it would make it *unconditional*. Frozen in the contract, where a rename is caught; the vhost
> guard matches on `$uri` and never sees it.

### What to expect afterwards

|                     Path                     | Anonymous status |
|----------------------------------------------|------------------|
| every write in the phase                     | **401**          |
| `…/unit-ship-options`                        | **403**          |
| `DELETE /api/v1/missions/<uuid>` (unchanged) | **405**          |

**The `403` is the one that had to be measured.** `GET /api/v1/missions/**` is `permitAll` — the
whole Einsatz read surface is, so a guest can see the board — which means this read is *dispatched*
and refused at the method seam rather than turned away at the entry point. Same seam
`/missions/lookup` met in phase S. A write is not covered by that `permitAll` and answers `401`.
Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written.

---

## Phase W — the Handel family, and three doors REQ-SEC-032 had left open

The price screens and the Materialbörse. **This phase did not start with a rule; it started with a
measurement that had to be acted on first.**

> [!danger] Three of the four price reads were anonymous, and they carry the data REQ-SEC-032 exists
> to keep off this vhost
> Measured before anything was written:
>
> |                    Path                    |                   Anonymous, before                    |
> |--------------------------------------------|--------------------------------------------------------|
> | `GET /api/v1/materials/prices-overview`    | **200**                                                |
> | `GET /api/v1/materials/<uuid>/prices`      | **200**                                                |
> | `GET /api/v1/materials/profit-calculation` | **500** — dispatched and crashing, which is not a gate |
> | `GET /api/v1/materials/matrix`             | 401 — already closed                                   |
>
> `matrix` and `*/terminals` were carved back to `authenticated()` when REQ-SEC-032 was written,
> and the comment there says why: *leaving it anonymous publishes UEX trade prices per material to
> the whole internet from the API vhost.* `MaterialPriceOverviewDto` carries `minPriceBuy` and
> `maxPriceSell`; `MaterialPriceDto` carries `priceBuy`, `priceSell`, `scu*` and `terminalName`;
> the profit calculation is the route arithmetic over both. **Same data, three more doors.**
>
> Admitting them at the edge as they stood would have published trade prices to the internet: the
> vhost would have let them through and the backend would not have stopped them. They joined the
> existing all-verb `authenticated()` carve-out **before** these rules were written.

**`GET /api/v1/materials/<uuid>` stays anonymous, and that is a decision rather than an oversight.**
`MaterialDto` is catalogue only — name, quantity type, category, flags, **no price** — and
`/api/v1/materials/search` has published those same fields anonymously since phase 2. Closing it
would be a different change with a different reason, and it is pinned so the decision stays one.

### Two halves, two guards

|                         Half                          | In the read-only family? |                     What that means                     |
|-------------------------------------------------------|--------------------------|---------------------------------------------------------|
| `/api/v1/materials/…`                                 | **yes**                  | naming a path admits its `GET` and nothing else         |
| `material-exchange`, `material-requests`, `terminals` | **no**                   | naming a path opens every verb the backend serves on it |

That difference is the whole reason this phase reads the way it does. `/api/v1/materials/<uuid>`
also serves `PUT` and `DELETE` — editing and deleting a material from the catalogue — and gets no
carve-out here, so both keep answering `405`. The other half was checked path by path against its
controller: `/terminals` serves only the collection `GET` (its edits live on `/{id}/…`),
`released-item-ids` only a `GET`, `item-offers` and `/material-requests/item` only a `POST`,
`offers/<uuid>/remark` only a `PUT`, and `/material-requests/<uuid>` a `GET` and the `PUT` the app
sends. **No `DELETE` is opened anywhere in this phase**, and no carve-out is written at all.

### What to expect afterwards

|             Path             | Anonymous status |
|------------------------------|------------------|-----------------------------------------------------------------------------|
| the four price reads         | **401**          |
| `/api/v1/materials/<uuid>`   | **401**          | REQ-SEC-052: the backend refuses an anonymous caller on every admitted path |
| everything else in the phase | **401**          |

Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written — and here that order was
not a formality: the pins are what found the three open doors.

---

## Phase X — the last seven, and the first upload

The end of the audit's list: Blaupausen, the Fleetview import, and the two thresholds that colour an
Auftrag by age.

|                 Screen                 |                         Path                          |
|----------------------------------------|-------------------------------------------------------|
| Blaupausen-Übersicht (+ Besitzerliste) | `…/personal-blueprints/overview`, `…/overview/owners` |
| Blaupausen anlegen (Mehrfachauswahl)   | `…/personal-blueprints/batch`                         |
| Blaupausen-Import                      | `…/import/preview` **and** `…/import/apply`           |
| Fleetview-Import                       | `/api/v1/hangar/import/fleetview`                     |
| Heimatort für alle Schiffe             | `/api/v1/hangar/ships/home-location`                  |
| Auftrags-Alter (gelb / rot)            | `/api/v1/settings/job_order.age_*_days`               |

### The import pair goes in together, and that is the point

`…/import/preview` is a multipart upload whose answer the member then edits — which name maps to
which product, and when it was acquired — and `…/import/apply` sends those edits back. The audit
filed the apply as *„latent — and then work-destroying"*: unreachable while the preview was refused,
and the thing that discards a member's whole resolution pass the moment it becomes reachable.
Admitting the preview alone would have created exactly that.

### `settings` joins the read-only family rather than getting a carve-out

`GET /api/v1/settings/<key>` serves a value; `PUT` on the same path is the admin write that changes
it **for the whole organisation**. The app reads two keys and writes none. Adding `settings` to the
read-only family is what keeps that `PUT` at `405` — one word in an alternation, rather than a
carve-out that would have had to be written and then not written.

> [!note] The two keys are named exactly, one rule each
> `/api/v1/settings` carries every system setting there is. The app reads two, and the guard names
> those two. They answer **200** anonymously, and that is by design: they sit in the same
> `permitAll` catalogue block as `/locations` and `/job-types`, and they carry two integers — how
> many days before an Auftrag turns yellow, and before it turns red. Pinned as a decision
> (REQ-SEC-037).

### The body ceiling moves, and the old note here was not achievable

The note that stood at `client_max_body_size` said *„raise it per location … never globally"*. That
is not available in this file. `client_max_body_size` is settable in `server` and `location`; NPM
supplies `proxy_pass` and its whole directive set from its own template, and this Advanced block
only **adds** to the server. A `location` written here would have to repeat NPM's proxy
configuration to keep proxying at all — `location /actuator` gets away with it only because it
returns `404` and proxies nothing. Guessing at that duplication on a production host is worse than a
stated ceiling.

**4m, and the number is measured rather than round.** The backend's own multipart comment records the
Fleetview JSON topping out *„under ~500 KB for a 100-ship hangar"*, and a blueprint export is the
same order; 4m leaves an eightfold margin. It is two orders below the backend's own 64 MB cap, which
exists for the admin P4K catalog import — a path this allow-list deliberately never admits.

What the ceiling exposes is bounded by the list above it: this vhost is default-deny, only two
admitted paths accept a body of any size, and every write path behind it is authenticated. A larger
ceiling costs an authenticated member's bandwidth, not an anonymous one's.

### What to expect afterwards

|                      Path                       | Anonymous status |
|-------------------------------------------------|------------------|
| the two settings keys                           | **200**          |
| everything else in the phase                    | **401**          |
| `PUT` on a settings key                         | **405**          |
| `/api/v1/hangar/users/<uuid>/ships` (unchanged) | **404**          |

The last two rows are the assertions that matter: they prove `settings` went in as a *family* and
that `/hangar` stayed one. Pinned in `ApiVhostAnonymousSurfaceTest` before this table was written.

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

