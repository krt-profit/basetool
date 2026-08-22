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
if ($uri ~ "^/api/v1/missions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/finance-entries/summary$") { set $krt_api_allowed 1; }
# Phase 2 - the Operationen segment of the same screen, its detail, the Finanz-Rollup and the
# payout rows. Anchored like the Einsatz detail and for a sharper reason: below `{id}` sits
# `/payouts/paid-out`, a PUT that marks a member as paid. `/payouts$` is the read; the write is a
# different path and stays off this list until phase 3 ships the manager toggle.
if ($uri = "/api/v1/operations/search") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/finance-summary$") { set $krt_api_allowed 1; }
if ($uri ~ "^/api/v1/operations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/payouts$") { set $krt_api_allowed 1; }
# Phase 2 - the notification inbox, its badge count and its push stream. EXACT paths: the
# family's mutating half (`/read-all`, `/read`, `/{id}`, `/{id}/read`) is a POST and two
# DELETEs, and the read-only guard below refuses them by verb as well.
if ($uri = "/api/v1/notifications") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/notifications/unread-count") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/notifications/stream") { set $krt_api_allowed 1; }
# Phase 2 - the dashboard's announcement band. EXACT path: /api/v1/announcement/admin is
# the admin read of the same row and /api/v1/announcement itself also answers PUT, so a
# prefix would carry both onto a vhost that exists to keep them off it.
if ($uri = "/api/v1/announcement") { set $krt_api_allowed 1; }
# Phase 2 - the Hangar. EXACT paths again: the family also carries /hangar/ships (a
# permission-gated read of EVERY member's ships), /hangar/users/{id}/ships (admin) and the
# create/update/delete verbs. Only the caller's own list and the org aggregate belong here.
if ($uri = "/api/v1/hangar/my-ships") { set $krt_api_allowed 1; }
if ($uri = "/api/v1/hangar/squadron-overview") { set $krt_api_allowed 1; }
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
if ($uri ~ "^/api/v1/(missions|operations|notifications|announcement|hangar)") { set $krt_readonly_family "R"; }
if ($request_method !~ "^(GET|HEAD)$") { set $krt_readonly_family "${krt_readonly_family}W"; }
if ($krt_readonly_family = "RW") { return 405; }

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
   inherits whatever the backend requires of it, and that is deliberately not uniform — two are
   anonymous by design (REQ-SEC-037). Reading "401 is the pass" off a `permitAll` path produces a
   false alarm, which is exactly what happened the first time this step was run.

   |                       Path                        |                           Without a token                           |                                                   Why                                                    |
   |---------------------------------------------------|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
   | `/api/v1/terms/document`                          | **200**                                                             | anonymous by design (ADR-0138): wording that must be read *before* agreeing cannot require having agreed |
   | `/api/v1/missions/search`                         | **200**                                                             | anonymous by design: the public home page already renders its guest-redacted rows                        |
   | `/api/v1/missions/<uuid>`                         | **200** for a non-internal, non-terminal Einsatz, **403** otherwise | anonymous by design, redacted per ADR-0034                                                               |
   | `/api/v1/missions/<uuid>` with `PUT`/`DELETE`     | **405**                                                             | the family is read-only on this vhost                                                                    |
   | `/api/v1/missions/<uuid>/finance-entries`         | **403**                                                             | member-or-above **and** `canSeeMission`, refused at the method seam — see below                          |
   | `/api/v1/missions/<uuid>/finance-entries/summary` | **403**                                                             | member-or-above **and** `canSeeMission`, refused at the method seam — see below                          |
   | `/api/v1/operations/search`                       | **401**                                                             | `isAuthenticated()`, and no chain matcher makes it public                                                |
   | `/api/v1/operations/<uuid>`                       | **401**                                                             | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>/finance-summary`       | **401**                                                             | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>/payouts`               | **401**                                                             | `isAuthenticated()` + `canSeeOperation`                                                                  |
   | `/api/v1/operations/<uuid>` with `PUT`/`DELETE`   | **405**                                                             | the family is read-only on this vhost                                                                    |
   | `/api/v1/terms/status`                            | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/terms/acceptance` (POST)                 | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/me/active-org-unit`                      | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/me/capabilities`                         | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/hangar/my-ships`                         | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/hangar/squadron-overview`                | **401**                                                             | scoped to the active org unit                                                                            |
   | `/api/v1/announcement`                            | **401**                                                             | no chain matcher makes it public                                                                         |
   | `/api/v1/notifications`                           | **401**                                                             | me-scoped inbox                                                                                          |
   | `/api/v1/notifications/unread-count`              | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/notifications/stream`                    | **401**                                                             | me-scoped SSE                                                                                            |
   | `/api/v1/users/me`                                | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/users/me/registration-status`            | **401**                                                             | me-scoped                                                                                                |
   | `/api/v1/users/me/memberships`                    | **401**                                                             | me-scoped                                                                                                |
   | anything not on the list                          | **404**                                                             | default deny                                                                                             |

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
   IRI_INGEST_EXPECTED_AUDIENCES=basetool-backend
   ```

   Both together — the ingest gateway's tokens are validated by the same rule.

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
     for c in backend ingest; do echo -n "$c: "; docker inspect $c --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -c 'APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-backend'; done
     ```

     Two `1`s. A `0` means that container was not recreated, whatever the compose output said;

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

