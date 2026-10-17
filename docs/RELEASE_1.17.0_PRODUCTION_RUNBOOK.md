# Release 1.17.0 — production rollout runbook

> **Doc type:** Operator runbook for **one** release — the move of production (and, through
> `promote.yml`'s `sync-testing`, the testing host) from **v1.16.5 to v1.17.0**, the first release of
> #2638 (backend API internal-only, Android app discontinued), together with #2693 (VerseKit: share
> own stock into a unit pool). Written 2026-10-17 from an audit of the 45 pull requests between
> `v1.16.5` and `61a692bc7` and from read-only reads of production the same day. **Historical once 1.17.0 is live and §5 is done**: move it to `docs/archive/` then and let
> [`deployment.md`](deployment.md) stay the living truth.
>
> **Every step that writes to a host is a production write**: it waits for @greluc's explicit yes,
> in chat, to that exact command (CLAUDE.md → *Production host access*). Nothing here is approval, and
> no write in this document has been executed.

Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are the ones in
[`deployment.md` → Shell conventions](deployment.md#shell-conventions-used-below).

---

## 0. What changes

| | Value |
|---|---|
| Range | `v1.16.5` (2026-10-17) to `61a692bc7`, 45 pull requests |
| #2638 | parts 1–4 merged: #2681 (ADRs), #2686 (API vhost and `net-proxy-api` gone, absence probes), #2692 (Android-only API surface gone, `azp` gate, CSRF/CORS off, forwarded headers narrowed), #2700 (`basetool-android` client and App Link surface gone), plus #2701, #2703, #2704, #2706. Part 5 (#2675) runs 7 days **after** this release |
| #2693 | #2702 (Lager `RebookCommands`), #2708 (`split` on `GET /exchange/v1/me/stock`), #2709 (`share` op), #2711 (`unshare` op). No new scope: both ops ride `exchange.stock.write`, which VerseKit's approval already lists |
| Other security | #2669: the management ports require the scrape credential; #2667: frontend hostname check on by default |
| Flyway | three, `V304`–`V306` (head before: `V303`): `V304` replaces the trigger function `exchange_inventory_item_changed()`; `V305` and `V306` swap `exchange_journal`'s `ck_exchange_journal_action` for one that also admits `STOCK_SHARE` and `STOCK_UNSHARE`. The `ADD CONSTRAINT` validates every journal row under an exclusive lock while the apps are stopped; expected seconds |
| Stateful infra | no image bump; the stateful-infra gate does not trigger |
| Keycloak SPI / theme | unchanged |
| Host scripts | none of `scripts/host-scripts.list` changed; no Ansible role run needed |
| One restart window | backend, frontend, ingest, the edge (unit and config changed), the monitoring reconcile (`prometheus.yml`, `blackbox.yml`, alert rules) |
| Expected maintenance page | about two minutes, once, plus the three migrations |
| Rollback | code-only: `promote.yml -f version=1.16.5`, **as long as §5 has not started**. `V304`–`V306` stay applied and are compatible with v1.16.5 (the constraint only widens, the trigger reads existing columns); see §4 for the one exception |

---

## 1. What the deploy does by itself — no action, but know it

- **The API vhost disappears.** The edge is recreated without `50-api.conf` and without
  `net-proxy-api`; `deploy.sh` removes the `net-proxy-api.network` unit file. The network object itself
  lingers until the weekly `container-cleanup` prunes it (§5).
- **The backend accepts tokens only from `basetool-frontend` and `basetool-ingest-gateway`** (ADR-0270).
  The default in `backend.env.tmpl` applies; the host needs no `IRI_BACKEND_ACCEPTED_CLIENT_IDS`. A wrong
  list does **not** stop the start — it answers `401` to every request — so §2.2 reads the ids.
- **Prometheus starts sending the scrape credential** to the three `basetool-*` jobs, and the apps
  start requiring it (ADR-0265). A mismatch does not affect members, but the three targets answer `401`
  and `TargetDown` fires. §2.2 checks it.
- **New alerts and probes:** `PublicApiHostResolvesAgain`, the `blackbox-api-absent*` DNS jobs; the
  `blackbox-app-link` job, `EdgeAppLinkFallbackBroken`, `AndroidVersionPolicyOverrideActive` and the
  API-vhost probes are gone.
- **The stolen marking is on** (REQ-INV-053). Production already sets
  `APP_INVENTORY_STOLEN_MARKING_ENABLED=true` (read 2026-10-17), so nothing changes for members.
- **Stale `.env` lines are inert.** `render-env-d.py` substitutes only what the templates name, so
  `EDGE_HOST_API`, the `APP_ANDROID_*` lines and the sixth `EDGE_TRUSTED_PROXY` address reach no
  container that needs them. They stay until §5, because **v1.16.5 needs them** (§4).

---

## 2. Before the promotion

### 2.1 Production state, read 2026-10-17 (no action)

| Fact | Read |
|---|---|
| Deployed release | `v1.16.5` (`49097a84d`), host scripts 14/14 match its manifest |
| `api.profit-base.online` | no `A`, `AAAA` or `CNAME` answer from 1.1.1.1 — the DNS record is gone |
| `ACME_HOSTS` (`.env` and `env.d/acme.env`) | already the three names `profit-base.online ingest.… grafana.…`; acme restarted 11:25 UTC |
| Served certificate | still the old five-name SAN set (`api`, `grafana`, `ingest`, `keycloak`, apex), valid until 2026-12-11 |
| `IRI_INGEST_SERVICE_ACCOUNT_CLIENT_ID` | `basetool-ingest-gateway` |
| `IRI_BACKEND_ACCEPTED_CLIENT_IDS` | not set (default applies) |
| Frontend client-id override (`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_*`) | none in `.env` or `env.d/frontend.env` |
| `/var/iri/monitoring/secrets/scrape_password` | present (2026-07-04) |
| `.env` still holds | `EDGE_HOST_API`, `APP_ANDROID_MINIMUM_VERSION_CODE`, `APP_ANDROID_LATEST_VERSION_CODE`, `APP_ANDROID_RELEASES_URL`, six `EDGE_TRUSTED_PROXY` addresses incl. `172.28.13.250` |
| Network `net-proxy-api` | present |

### 2.2 Blocking check — the scrape credential (#2669)

`MONITORING_SCRAPE_USER` must be `monitoring` and `MONITORING_SCRAPE_PASSWORD` must equal
`/var/iri/monitoring/secrets/scrape_password`. Nothing used this pair since ADR-0134, so a drift
would have gone unnoticed. The comparison prints only the verdict, never a value; run it as root on
production:

```bash
f=/var/iri/monitoring/secrets/scrape_password; e=/var/iri/code/.env
[ "$(grep -E '^MONITORING_SCRAPE_USER=' "$e" | cut -d= -f2-)" = monitoring ] && echo "user ok" || echo "user MISMATCH"
[ "$(grep -E '^MONITORING_SCRAPE_PASSWORD=' "$e" | cut -d= -f2-)" = "$(cat "$f")" ] && echo "password ok" || echo "password MISMATCH"
```

Two `ok` lines: promote. A `MISMATCH`: set the `.env` value to the file's content (a production
write; the promotion re-renders `env.d/` itself), then promote. Promoting with a mismatch does not
take the app down, it only blinds the three app scrape targets.

### 2.3 The host scripts (mandatory, every promotion)

[`deployment.md` → Before a promotion: the host scripts](deployment.md#before-a-promotion-the-host-scripts)
with `--rev v1.17.0`. Expected: no difference, since no listed script changed.

### 2.4 Announce (no host change)

Rollout step 1 of #2638: tell the members the Android app is gone and point them at the wiki page
*Basetool als App installieren (PWA)*. The release notes (`release-notes` skill) carry it.

---

## 3. Cut the release and promote

1. *Actions → Release · Prepare → Run workflow*, version `1.17.0`; merge the `chore(release): v1.17.0`
   PR once it is green.
2. `gh workflow run promote.yml -f version=1.17.0`, approve the `production` gate.
3. Follow `/var/log/iri-deploy.log` (or Loki `{app="ops-deploy"}`) until
   `deploy successful`.

---

## 4. After the promotion — verify (read-only)

- The three `basetool-*` targets are `up` in Prometheus; no `TargetDown`.
- `PublicApiHostResolvesAgain` and `DnsResolutionFailed` stay quiet; the `blackbox-api-absent*` jobs
  report success.
- Members log in and use the frontend (a `401` storm would show as `BackendAuthFailureSpike`,
  `basetool_auth_failures_total{reason="invalid_token"}`).
- An exchange or SC-extractor relay succeeds: no `ExchangeRelayFailing`,
  `basetool_ingest_exchange_relay_total{outcome="failed"}` flat.
- The backend log shows Flyway at `V306` (`Successfully applied 3 migrations`).
- Run the `edge-deny-probe` workflow once by hand; it asserts the API host does not resolve and that
  `11261`/`11271` refuse from outside.

**Rollback** is `gh workflow run promote.yml -f version=1.16.5`. It works only while the host still
holds what v1.16.5 needs: its `render-and-run.sh` refuses to start the edge without `EDGE_HOST_API`,
and its edge joins `net-proxy-api` and expects the sixth trusted-proxy address. That is why §5 waits.
Flyway in v1.16.5 ignores the applied `V304`–`V306` as future migrations (no
`ignore-migration-patterns` override), so the backend starts. The one exception: v1.16.5 maps
`exchange_journal.action` to an enum without `STOCK_SHARE` / `STOCK_UNSHARE`, so once a client has
used `share` or `unshare`, reading those journal entries (undo of that change set) fails on the old
release. Members' pages are not affected.

---

## 5. Cleanup — once v1.16.5 is no longer a rollback target

Each line is a production write with its own approval. None of it is urgent.

| Step | What | Why |
|---|---|---|
| C1 | Delete `EDGE_HOST_API` from `/var/iri/code/.env` | #2686; inert on 1.17.0, required by v1.16.5 |
| C2 | Drop `172.28.13.250` from `EDGE_TRUSTED_PROXY` | #2686; then the *trusted == pinned* check in [`deployment.md` → The edge](deployment.md#the-edge) passes again |
| C3 | Delete `APP_ANDROID_MINIMUM_VERSION_CODE`, `APP_ANDROID_LATEST_VERSION_CODE`, `APP_ANDROID_RELEASES_URL` | #2692/#2701; read by nothing since 1.17.0 |
| C4 | `cd / && ${UPOD} network rm net-proxy-api`, or leave it to `container-cleanup` | #2686; unused, no subnet clash |
| C5 | Keycloak: provisioner dry run, then `--apply`, to delete `krt-mobile-dpop-policy` and the `krt-mobile-dpop` profile if they still exist | #2700, ADR-0202 amendment; the client itself was deleted by hand on 2026-10-17. Runbook: [`docs/keycloak/README.md`](keycloak/README.md) → *a kcadm session on the production container* |
| C6 | Private Ansible inventory: drop `api.profit-base.online` from `basetool_host_public_name_aliases`, then `--tags podman` | cosmetic; the alias resolves nothing that matters |

After C1–C3, render `env.d/` (`render-env-d.py`); no restart is needed, since no running container
reads those keys.

### The certificate — watch, no step

`ACME_HOSTS` already lists three names, but the served certificate still carries the old five
(valid until 2026-12-11) — lego reissues only inside its 30-day renewal window, from about
2026-11-11. That renewal requests the three names; the `api.` and `keycloak.` directories on
`edge-certs` then stay behind, renewed by nothing (arc42 §11). Watch `AcmeRenewalFailing` from
2026-11-11; if it fires, the renewal with the changed name set is the cause to look at first.

---

## 6. Afterwards

- **#2675 (part 5):** 7 days after the promotion, measure the `401` floor without the API-host probes,
  set `BackendUnauthenticatedFlood`'s threshold, amend ADR-0173 and REQ-OBS-018. Then #2638 can close.
- **Testing host (optional):** `APP_INSTANCE_TEST_SYSTEM=true` in its `.env` turns on the footer marker
  (#2650, [`deployment.md` → Promoting to testing](deployment.md#promoting-to-testing)).
- Move this file to `docs/archive/` with the archive header.
