# Release 1.11.0 — production rollout runbook

> [!note] **Executed 2026-09-25 — now a historical record.** Production runs v1.11.0 since the
> 12:40 UTC deploy tick; see [§9 Execution record](#9-execution-record-2026-09-25) for what was run,
> what went wrong on the way and what it taught. The living procedure is
> [`deployment.md`](deployment.md).

> **Doc type:** Operator runbook for **one** release — the move of production (and, through
> `promote.yml`'s `sync-testing`, the testing host) from **v1.10.0 to v1.11.0**. Written 2026-09-25
> from a per-PR audit of all 66 PRs between `v1.10.0` and `6076888cd` (the commit release PR #2056
> was cut from), plus #2057 merged after the cut, against the code and the docs on `main`, plus
> read-only reads of both hosts the same day. **Historical once 1.11.0 is live**: freeze it then and let
> [`deployment.md`](deployment.md) stay the living truth.
>
> **Every step that writes to a host is a production write**: it waits for @greluc's explicit yes,
> in chat, to that exact command (CLAUDE.md → *Production host access*). Nothing here is approval.

Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are the ones in
[`deployment.md` → Shell conventions](deployment.md#shell-conventions-used-below).

---

## 0. Where the hosts stand (read 2026-09-25, read-only)

| | Production (`46.225.24.180`) | Testing (`10.9.0.15`) |
|---|---|---|
| backend / frontend / ingest | `v1.10.0` (`25ee8fd5a`) | `v1.10.0` |
| `deploy.sh`, `backup.sh` on the host | the #2001 state (`8ba649fa1`); **not** #2034 / #2039 | not read |
| `IRI_BACKEND_EXPECTED_AUDIENCES` in `.env` | set, `basetool-backend` (the line is there **twice**, same value — harmless) | **absent** |
| `IRI_MONITORING_ENABLED=true` | yes | yes |
| new optional switches in `.env` (`APP_SESSION_TYPE_ALLOW_LIST`, `KEYCLOAK_FRONTEND_CLIENT_SECRET`, `IRI_BACKEND_KEYCLOAK_JWK_SET_URI`, `EDGE_GRAFANA_UPSTREAM_VERIFY`, `REDIS_{FRONTEND,BACKEND,INGEST}_{USERNAME,PASSWORD}`, `REDIS_DEFAULT_USER`, `INTERNAL_TLS_VERIFY_HOSTNAME`) | none set — every one ships **off** | — |
| `IRI_EXTRA_JAVA_OPTS` with `-XX:-UseCompactObjectHeaders` | no (the AOT cache will be used) | — |
| retention overrides in `.env` | none (the #1989 floors pass on the defaults) | — |
| `/var/iri/monitoring/certs/grafana.crt` | present, SAN `DNS:grafana`, valid to 2028-12-24; readable by `deploy` | — |
| `/var/iri/redis/users.acl` | the hand-written file, `root:root 0644`, 2 users | — |
| `basetool_host_reboot_required` | **1** — a kernel is pending | — |
| `container_cleanup.prom` textfile | **missing** (last cleanup 2026-09-22, before the #2001 script) | — |
| last backup | 2026-09-25 04:17 UTC, success | — |
| Podman | 5.8.2 | — |

Re-read the rows you rely on before you start; anything that differs from this table is a reason to
stop and re-check the step that depends on it.

---

## 1. What the deploy does by itself — no action, but know it

- **Every application service restarts**, `db-backend`, `db-keycloak` and `redis` included: #1992
  changed every `quadlet/systemd/*.container` (stop grace, `RunInit`), #2023 changed `redis`'s
  command line. No `Image=` of a stateful service changed, so the stateful-infra gate does not
  fire. Redis keeps its data (AOF). Watch the tick rather than leaving it to the timer
  ([`deployment.md` → Host-config and unit changes](deployment.md#host-config-and-unit-changes)).
- **Keycloak restarts** (unit change + new provider JAR from #1990), which also loads the #2053
  theme text.
- **The edge is recreated** (#2021 gzip, #2039 unit mount, #1996/#1999 API allow-list).
- **Flyway runs one migration, `V245__index_every_uncovered_foreign_key.sql`** (#2004): 38
  `CREATE INDEX IF NOT EXISTS`, additive and idempotent, seconds on this data size, run before the
  new backend serves. It stays harmless after a rollback.
- **Every member is signed out once** (#2002 renames the session cookie to `__Host-SESSION`). No
  Redis flush — the old sessions age out. Announce it (§2.1).
- **Android builds older than 2026-09-07 (≤ v0.2.6) lose the manager's "Teilnehmer hinzufügen"**
  (#1996 deletes the deprecated endpoint; CHANGELOG says so). v0.2.7+ are unaffected.
- **Monitoring**: new alerts and dashboards arrive with the config bundle and Prometheus is
  recreated (`IRI_MONITORING_ENABLED=true` is set). Two of the new/changed alerts will be **firing
  for pre-existing reasons**, not because of the deploy — see §2.4.
- **Session allow-list runs `report`** (#2018): it counts, it refuses nothing.

---

## 2. Before the promotion

### 2.1 Announce (no host change)

A short note to the members: one sign-in again after the update; owners of Android app versions older
than 07.09.2026 need to update for "Teilnehmer hinzufügen".

### 2.2 Read-only pre-checks on production

```bash
cd /
grep -c '^IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend$' /var/iri/code/.env   # >= 1  (#1989: blank = backend refuses to start)
grep -cE '^REDIS_(FRONTEND|BACKEND|INGEST)_(PASSWORD|USERNAME)=' /var/iri/code/.env   # 0  (#2023: must stay 0 until 1.11.0 is live)
grep -cE '^IRI_MONITORING_ENABLED="?true"?$' /var/iri/code/.env               # 1  (monitoring changes load)
grep -cE '^APP_(AUDIT|NOTIFICATIONS|REGISTRATIONS_REJECTED)_RETENTION_' /var/iri/code/.env   # 0, or values >= the #1989 floors
sudo -u deploy test -f /var/iri/monitoring/certs/grafana.crt && echo ok        # ok (#2039 edge mount)
systemctl show iri-backup.service -p Result -p ExecMainExitTimestamp          # success, today
```

Prints counts and states only — never a value. Any other result: stop.

### 2.3 Testing host — decide **before** promoting (owner decision)

`promote.yml`'s `sync-testing` job moves `:testing` to 1.11.0 together with production (testing is
behind). Testing's `.env` has **no** `IRI_BACKEND_EXPECTED_AUDIENCES`, so the 1.11.0 backend there
refuses to start (#1989 `JwtAudienceStartupCheck`), its `deploy.sh` rolls back and backs off, and
`DeployRolledBack` fires for testing on every retry. Production is not affected — but it is a broken
testing host. Pick one:

- **A — provision testing first (preferred).** The owner recreates `basetool-provisioner` on testing
  and opens a kcadm session; then the provisioner dry run and apply
  ([`INGEST_KEYCLOAK_SETUP.md` → new or out-of-date realm](INGEST_KEYCLOAK_SETUP.md)), then
  `IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend` into testing's `.env`. Setting the variable
  **without** the provisioned audience mapper is worse than not setting it: the backend starts and
  refuses every token.
- **B — hold testing.** On testing, `systemctl stop iri-deploy.timer` before the promotion; start it
  again only after A is done. Testing stays on 1.10.0 meanwhile.

### 2.4 Alerts that will fire for reasons older than this release

- **`HostRebootRequired`** (new in #1992): `basetool_host_reboot_required` is already `1`. Either
  take the reboot as a maintenance before or after the deploy (units are `WantedBy=default.target`,
  `iri` lingers — the stack comes back on its own), or expect the warning until you do.
- **`ContainerCleanupStaleOrMissing`**: `container_cleanup.prom` does not exist on production (the
  last cleanup ran 2026-09-22 16:26 with the pre-#2001 script). The v1.10.0 rule already fires on
  that absence, and the 1.11.0 rule does too; the next scheduled cleanup (Saturday 02:00 UTC, with
  the #2001 script installed on 2026-09-22) writes the file and clears it. A manual
  `systemctl start iri-container-cleanup.service` is a production write.

Note the firing set in Grafana → Alerting **before** the promotion, so the post-deploy comparison is
against a baseline, not against zero.

### 2.5 Install the operational scripts (role run, from WSL)

`deploy.sh` and `backup.sh` on production predate #2034 and #2039, and the Redis renderer (#2023)
and `mint-internal-tls.sh` (#2034) are not installed. The release itself would still deploy with the
installed `deploy.sh` (every mount source the new units name exists), but the new pre-flights, the
new backup coverage and the later rollouts need them, and the docs order it before the promotion.
Per [`deployment.md` → Updating the operational scripts and units](deployment.md#updating-the-operational-scripts-and-units)
and the controller caveats in [`ansible/README.md`](../ansible/README.md) — mirror `ansible/`,
`scripts/` **and** `monitoring/` from the same `origin/main` into the WSL filesystem, then:

```bash
ansible-playbook site.yml --limit production --tags deploy,scripts --check --diff   # read the diff
ansible-playbook site.yml --limit production --tags deploy,scripts
```

Expected changes: `deploy.sh`, `backup.sh`, `mint-internal-tls.sh`, `render-redis-acl.py`,
`redis-users.acl.tmpl` under `/var/iri/code/scripts/`. Verify by content:
`sha256sum /var/iri/code/scripts/deploy.sh` equals `git show origin/main:scripts/deploy.sh | sha256sum`
(same for the other four). Installing a script changes no credential and restarts nothing. Do the
same for testing (`--limit testing`) whichever option §2.3 took.

---

## 3. Cut the release

1. Merge release PR **#2056** (`chore(release): v1.11.0`) — it is `BEHIND` `main`, so update its
   branch first; there is no textual conflict, and #2057's CHANGELOG line lands in the 1.11.0
   section. *Everything on `main` when #2056 merges is part of 1.11.0.* Since the cut that is
   **#2057** (Gradle wrapper 9.8.0, merged 2026-09-25, `0168cfb39`): build only — the images are
   built with the new wrapper, nothing on the host changes, and CI, Release Images and the OWASP
   scan passed on that commit. Re-check any further PR that lands before #2056 against §1–§2
   before promoting (this runbook's own PR, #2058, is docs plus Loki-rule comments — no host step).
2. `release-publish.yml` creates the tag **with the `basetool-release` App token** — the first
   publish since the App key was replaced on 2026-09-25 (the token itself is proven by
   refresh-versions run 36127942075 and Release · Prepare run 36128198185). If the tag step fails:
   the manual fallback in [`deployment.md` → Cutting a release](deployment.md#cutting-a-release).
3. The tag run of `release-images.yml` re-tags `:sha-<release>` of all three app images plus
   `config` and `keycloak-spi` as `:1.11.0`. Confirm all five exist before promoting (the promote's
   own first step also refuses otherwise).

## 4. Promote and watch the tick

```bash
gh workflow run promote.yml -f version=1.11.0      # vuln gate → your approval → signature → :stable
```

Within ~5 minutes `deploy.sh` picks it up. Follow it on the host (read-only):

```bash
cd / && tail -f /var/log/iri-deploy.log
```

Expected: every digest cosign-verified, the config bundle staged and `env.d/` rendered, units
installed, application services restarted and healthy, keycloak restarted for the provider JAR,
marker written, monitoring and edge reconciled. **Not** expected: `rolled back`, `drift`,
`required file missing`. On a rollback the log names the service; its `${UPOD} logs --since 10m
<svc>` names the reason — then §6.

## 5. After the tick — verify (read-only)

```bash
cd /
${UPOD} inspect backend frontend ingest --format '{{.Name}} {{index .Config.Labels "org.opencontainers.image.version"}}'   # v1.11.0 ×3
${UPOD} logs --since 30m backend 2>&1 | grep -m1 'JWT audience check enforced'          # accepted audiences: [basetool-backend]
${UPOD} logs --since 30m frontend 2>&1 | grep -m1 'Session type allow-list mode'        # ... REPORT
for s in backend frontend ingest; do printf '%s ' "$s"; ${UPOD} logs --since 30m "$s" 2>&1 | grep -c 'Unable to use AOT cache'; done   # 0 ×3
${UPOD} container inspect backend --format '{{.Config.StopTimeout}}'                    # 30  (#1992)
${UPOD} healthcheck run redis && echo redis-healthy                                     # #2023 probe
${UPOD} exec db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -Atc "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; SELECT version, success FROM flyway_schema_history WHERE version = '"'"'245'"'"';"'   # 245|t
curl -s -o /dev/null -D - -H 'Accept-Encoding: gzip' https://profit-base.online/css/styles.css | grep -iE '^(content-encoding|vary)'   # gzip + Vary (#2021)
```

Then, off the host:

- **Sign in** (once more, #2002), open a mission (live sync), the bank, the inventory; run one desktop
  import through ingest.
- **Loki, first hours**: `{app="backend-stdout"} |= "LazyInitializationException"` and
  `|= "Fail on pagination over collection fetch"` stay empty (#2030, #2004);
  `{app="ops-deploy"}` shows no rollback; the first UEX sync after the deploy moves
  `basetool_scheduled_job_last_success_timestamp_seconds{task="uex_sync"}` and adds nothing to
  `basetool_scheduled_job_step_failures_total{task="uex_sync"}` (#2009).
- **Alerts**: compared with the §2.4 baseline, nothing new except what §2.4 predicts.
  `IngestAudienceGateOff`, `SessionTypeOutsideAllowList`, `AppLiveSyncFramesDropped`,
  `JvmStartupCacheRejected` stay silent.
- **Edge deny probe**: its next run (or a dispatch) turns the `…/participants` and
  `…/participants/by-id/slim` rows green (#1996, #1999) — they have been red since 2026-09-23
  because production was behind `main`.
- **Testing**: under option A, the same checks there; under B, it stays on 1.10.0 until A.

## 6. Rollback

A rollback is **lock-step, never per service**: `gh workflow run promote.yml -f version=1.10.0`.
The 1.11.0 frontend calls backend endpoints 1.10.0 does not have (`/api/v1/me/layout`,
`/users/search/references` — #2004/#2020), so a backend-only rollback breaks every page.
`V245` stays applied and is harmless. Members are signed out once more (the cookie name changes
back). **Before** rolling back, undo any §7 switch that pins the new release:
the confidential frontend client (#2028 → `--frontend-client public --apply` first) and a Redis
`REDIS_DEFAULT_USER=off` (#2023 → `default` back on first). The others are harmless under 1.10.0.

---

## 7. After the deploy — separate, optional, each owner-gated

None of these is part of the deploy, and leaving any of them undone is safe. Each is its own
production write with its own yes; do them one at a time, never inside the deploy.

| When | What | Where it is written down |
|---|---|---|
| after §5 is green | **Release the Android app (basetool-android #182)** — it calls `…/participants/by-id/slim`, which exists only from 1.11.0; bump `versionCode` to 16 first | vault *Android App*; #1999 |
| any time | **Host reboot** for the pending kernel (`HostRebootRequired`) | [`deployment.md` → Updating the operational scripts and units](deployment.md#updating-the-operational-scripts-and-units) (host patching) |
| any time after 1.11.0 | **#2053: `baseUrl` on `basetool-frontend`** — provisioner dry run with `--public-origin https://profit-base.online` and **no** `--frontend-client`; it must plan only the `baseUrl` line; then `--apply`, a second empty dry run, delete the session and the rollback basis | [`INGEST_KEYCLOAK_SETUP.md`](INGEST_KEYCLOAK_SETUP.md) (provisioner); needs a freshly created `basetool-provisioner` (the 2026-09-23 one is deleted) |
| ≥ 7 days after go-live, both report queries empty | **APPSEC-05: allow-list `enforce`** | [`deployment.md` → Session type allow-list](deployment.md#session-type-allow-list-report-then-enforce) |
| after 1.11.0 | **#2038: internal JWKS for the backend** | [`deployment.md` → Internal JWKS](deployment.md#internal-jwks-for-the-backend) |
| after 1.11.0 | **#2039: edge verifies Grafana** — precondition (SAN `DNS:grafana`) already confirmed | [`deployment.md` → The edge verifies Grafana](deployment.md#the-edge-verifies-grafana) |
| after 1.11.0 | **APPSEC-07: confidential frontend client** — frontend secret first, Keycloak second; blocks a plain release rollback (§6) | [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md) |
| after 1.11.0 | **APPSEC-04: one Redis ACL user per service** — steps 2–4 in one sitting with `iri-deploy.timer` stopped; render via `.new` + `cat`; step 5 blocks a plain release rollback (§6) | [`deployment.md` → The Redis ACL](deployment.md#the-redis-acl) (corrected 2026-09-25) |
| after 1.11.0 | **ING-SEC-04: per-service internal TLS**, steps 1–2, then #2036 (step 3) only after step 2's files exist, then step 4 (step 0 is §2.5) | [`deployment.md` → Internal TLS](deployment.md#internal-tls-per-service-certificates-from-a-private-ca) |
| any time, testing first | **#1992: `Internal=true` on the five internal networks** — a stop, `network rm`, restart window | [`deployment.md` → Network changes are installed, not applied](deployment.md#network-changes-are-installed-not-applied) |

Optional tidy-ups found on the way, no urgency: the duplicate `IRI_BACKEND_EXPECTED_AUDIENCES` line in
production's `.env`; the leftover `/var/iri/code/scripts/lib/container-runtime.sh.bak-2026-09-22`.

> [!note] Status on 2026-09-25 (evening) — added after the fact; the table above is left as written
> Every host write below had the owner's yes in chat for that exact command. The living procedures
> in `deployment.md` now carry the same record.
>
> **Done:**
> - **#2053 `baseUrl`** — set by the owner **by hand in the Admin Console** (Home URL
>   `https://profit-base.online/`), not through the provisioner run the table describes; confirmed by
>   a read-only query of the Keycloak database. REQ-SEC-071's last item is ticked.
> - **#2038 internal JWKS** — ~15:38 UTC, as documented; only `backend.env` changed, backend healthy
>   in 11 s, no JWKS/PKIX/SAN line, an authenticated `/api/v1/users/me` → `200`, no `401` after.
> - **#2039 edge verifies Grafana** — ~15:40 UTC; edge logs "Grafana's upstream certificate is
>   verified (pinned)", Grafana `/api/health` → `200`.
> - **ING-SEC-04 step 1** — ~15:40 UTC; all four services `Verification: OK` beforehand,
>   `INTERNAL_TLS_VERIFY_HOSTNAME=true`, frontend and ingest restarted healthy.
> - **ING-SEC-04 step 2 (2a–2e)** — ~15:52 UTC; the mint printed "The CA key no longer exists.",
>   `basetool-ca.crt` carries two anchors, edge/Prometheus/blackbox restarted, no edge verify error.
> - **Step 2f surfaced a pre-existing defect:** `.env` set `KRT_BACKEND_TRUSTSTORE_PATH` to a file
>   that had never existed, so the Discord duplicate-account precheck had been **failing open on
>   production** (≥ 7 days of `Failed to load the backend truststore` warnings). Fixed ~15:52 UTC:
>   `/var/iri/secrets/backend-truststore.p12` built with aliases `backend` and `internal-ca`, mounted
>   by the drop-in `keycloak.container.d/50-backend-truststore.conf`; no warning since.
>   [`DISCORD_KEYCLOAK_SETUP.md` §7.3](keycloak/DISCORD_KEYCLOAK_SETUP.md#73-truststore-for-the-backend-certificate)
>   and [`deployment.md` → Step 2f](deployment.md#step-2f--the-keycloak-spi-prechecks-truststore)
>   are corrected.
> - **The keycloak restart for 2f was a ~2-minute full outage** (15:52:40–15:54:50 UTC, 175
>   maintenance-page 502/503/504 at the edge): backend, frontend and ingest `Requires=` it and
>   restarted with it. The runbooks now warn at every Keycloak restart.
> - **APPSEC-04 steps 2–5** — ~15:47–15:51 UTC with `iri-deploy.timer` stopped; `REDIS_DEFAULT_USER=off`.
>   From now on a rollback to 1.10.0 needs `default` back on first (§6). The frontend's refused
>   `CONFIG GET` at each start is expected on 1.11.0 and gone once #2067 is released
>   (`deployment.md` → *The Redis ACL*).
> - **Tidy-ups** — the duplicate `IRI_BACKEND_EXPECTED_AUDIENCES` line removed from `.env` (inode
>   kept); `container-runtime.sh.bak-2026-09-22` deleted.
> - **Host reboot** for kernel 6.12.0-211.58.1 at 15:56 UTC — all 18 containers healthy by 15:59,
>   `basetool_host_reboot_required 0`. At boot the four `iri-*` services ran and failed within a
>   second ("no lingering user could be found"); the stack was unaffected and the next regular tick
>   succeeds. The code fix is an open follow-up (`deployment.md` → host patching, arc42 §7.4b).
>
> - **APPSEC-07 step 1** — `KEYCLOAK_FRONTEND_CLIENT_SECRET` generated on the host, frontend
>   restarted, log `OAuth2 client 'keycloak' is CONFIDENTIAL`.
> - **APPSEC-07 step 2** — 16:15 UTC, through a temporary `basetool-provisioner` session the owner
>   opened: the `--frontend-client confidential` dry run planned only `publicClient: true -> false`
>   and the secret, `--apply` succeeded, a second run planned nothing; no `invalid_client` since, a
>   private-window login works. Session file, rollback basis and scripts removed afterwards.
>   **§6 now applies:** a rollback to 1.10.0 needs `--frontend-client public --apply` first, and
>   that needs a new provisioner session. The table's "**no** `--frontend-client`" for the #2053 run
>   is superseded too: every provisioner run now passes `--frontend-client confidential` with the
>   secret ([`INGEST_KEYCLOAK_SETUP.md`](INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner)).
>
> **Still open:** APPSEC-05 `enforce` (not before 2026-10-02, once both report queries are empty);
> the host's `realm-export.json` seed for the confidential client (a host write); ING-SEC-04 step 3 (the `PATH_VARS` release, #2036)
> and step 4; the Android release (basetool-android #182).
>
> **Later the same evening:** #1992 was applied on **production** directly (owner's choice, testing
> skipped): the five data networks are `internal=true` since 16:24 UTC after a ~2.5-minute
> maintenance ([`deployment.md` → Network changes](deployment.md#network-changes-are-installed-not-applied));
> the testing host still has the old networks. The frontend's refused `CONFIG GET` noted above
> disappears with the release that carries #2067. The Android release v0.3.1 (versionCode 16) was
> tagged; the served-version floor moves to 16 only once it is published.

---

## 8. What the audit found and fixed alongside this runbook (2026-09-25)

- `deployment.md` → *The Redis ACL*: the render wrote a new inode under a single-file bind mount,
  so `ACL LOAD` would have reloaded the old rules — now `.new` + `cat`; step 2 is not inert and the
  step-4 rollback must remove the passwords too — now said, with the timer stopped for steps 2–4.
- `deployment.md`: two claims that the mount pre-flight refuses a release *before* applying it
  (Grafana edge mount, internal-TLS step 3) — it reads the installed units, so the release that adds
  the mount fails at start and is rolled back; corrected. Step 0's expected list names the Redis
  renderer too.
- `OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`: the release-rollback hazard of a confidential client.
- `INGEST_KEYCLOAK_SETUP.md`: `clients.before.json` holds client secrets — delete the rollback basis.
- `KEYCLOAK_HARDENING_RUNBOOK.md`, arc42 §11, `deployment-delivery.md`: the #2007 realm changes are
  applied on production (2026-09-23), not pending.
- `security-and-access.md` (REQ-SEC-025): the cookie rename signs everyone out at the 1.11.0 deploy,
  which has not happened yet.
- The Temurin runtime bump #2035 (`…@sha256:2ca9adf4…`) skipped the re-check `monitoring/README.md`
  requires; done: `JvmNativeThreadExhaustion` and `JvmStartupCacheRejected` still match the JVM's
  wording on the new digest, recorded in the rule file, the README and `observability.md`.

---

## 9. Execution record (2026-09-25)

Every host write below had the owner's yes in chat for that exact command.

| Time (UTC) | Step | Result |
|---|---|---|
| ~11:5x | §2.5 role run, production, `--tags deploy,scripts` (controller mirror of `origin/main` `229b9350e`) | `changed=2`; `deploy.sh`, `backup.sh`, `render-redis-acl.py`, `mint-internal-tls.sh`, `redis-users.acl.tmpl` sha256-equal to `origin/main` |
| — | §2.5 for testing | **not run**: the WSL controller's `known_hosts` still held a previous machine's keys for `10.9.0.15` (the live ED25519 key matches Windows' `known_hosts`, so no attack — a stale entry); testing is held (option B) anyway |
| ~12:0x | §2.3 option B: `systemctl stop iri-deploy.timer` on testing | `inactive`; testing stays on v1.10.0 until its realm is provisioned |
| 12:09 | §3 release PR #2056 merged (`af67518cc`), tag `v1.11.0` created by the `basetool-release` App | first App-token tag since the key rotation — worked |
| ~12:1x | tag run of `release-images.yml` | re-tagged all three app images, config and keycloak-spi signed |
| ~12:2x | §4 `promote.yml -f version=1.11.0` (run 36134206306), approved by the owner in GitHub | all six scans green; five artefacts on `:stable`; `sync-testing` moved `:testing` (held host, no effect) |
| 12:25–12:35 | three deploy ticks | **each aborted in the config apply** — see *Stuck on `docker/acme`* below; production stayed on v1.10.0, healthy |
| 12:3x | `chown deploy:deploy /var/iri/code/docker/acme` | the only entry in the release-owned subtrees not owned by `deploy` |
| 12:40–12:46 | deploy tick | config applied, 23 units installed, services restarted and healthy, keycloak recreated for the provider JAR, Prometheus/Alloy/edge recreated; `deploy successful`; next tick "no change" |
| 12:5x | `chown iri:iri /var/iri/secrets` | aligned with the role (see below) |

**Verified afterwards (§5):** the public page shows v1.11.0; `V245` applied 12:42:13; backend
"JWT audience check enforced; accepted audiences: [basetool-backend]"; frontend "Session type
allow-list mode: REPORT", no refused class; ingest gate posture all on; no `Unable to use AOT cache`;
no `LazyInitializationException`, no pagination failure; `StopTimeout=30`; redis
`--notify-keyspace-events Egx` and healthy; edge serves CSS gzipped with `Vary`; edge deny probe
(run 36137345395) green on every row, the `…/participants` rows included; deploy metrics without a
failure.

**What went wrong, and what it taught:**

- **Stuck on `docker/acme`.** `/var/iri/code/docker/acme` had been created `root:root` on
  2026-09-22 11:13, while the rest of `code/docker` is `deploy`'s. The bundle changed
  `publish-loop.sh`, `rsync` could not write, and `deploy.sh` died **without recording a failure**
  (`basetool_deploy_last_failure_timestamp` stayed `0`, so no alert), which is why it took a human
  reading the log to notice. The §2.5 role run used
  `--tags deploy,scripts`; the task that reclaims the release-owned subtrees ("Reclaim the subtrees a
  release owns end to end", tag `directories`) was not part of it. *Lesson for the next runbook:*
  before promoting, check `find /var/iri/code/{docker,keycloak-theme,monitoring,quadlet} ! -user
  deploy` is empty (read-only), or include `directories` in the role run.

  > **Corrected 2026-09-25.** This bullet first said `deploy.sh` "stopped before changing
  > anything". It had not: each tick had already mirrored `monitoring/`, `docker/maintenance` and
  > `docker/edge` before `rsync` exited 23 under `set -euo pipefail` with no trap. And because the
  > digest pin was written before the config apply, the second failed tick copied the new pin over
  > `previous-digest-pin.yml` and snapshotted the half-mirrored tree as `config-previous/`, so both
  > rollback anchors were gone until the 12:40 tick succeeded. #2063 records such a failure, restores
  > the previous tree, refuses up front on a non-`deploy`-owned directory, and writes the pin after
  > the config delivery ([`deployment.md`](deployment.md), REQ-OPS-003). It reaches a host only
  > through the role run `--tags deploy,scripts`.
- **`/var/iri/secrets` was `root:root`**, the role's target is `iri:iri 0755`
  (`basetool_host_service_user_dirs`). The keystore rotation in `deployment.md` relies on it: step 2
  deletes `keystore.p12`, step 3 has `iri` write the new one there — with a root-owned directory it
  would have failed after the delete. Aligned the same day.
- **A 3-minute ERROR burst in the frontend** (12:46–12:48, 33 lines,
  `AsyncRequestNotUsableException` on `GET /notifications/stream`). It stopped on its own and stayed
  below `LogbackErrorSpike`'s threshold.

  > **Corrected 2026-09-25.** This bullet first read the lines as anonymous stream requests from the
  > #2002 sign-out wave. They were async results of relays whose browser had already gone: the
  > frontend `GlobalExceptionHandler` had no handler for the disconnect, so its catch-all logged
  > `ERROR`. `userId=anonymous` was the logback fallback on an async dispatch, which the MDC filters
  > skipped. An anonymous stream request already got a clean 401; the sign-out wave caused reconnect
  > hammering. Fixed by #2060 (disconnect at DEBUG, client backoff) and #2061 (the MDC carries across
  > the async dispatch); #2062 keeps the `correlationId` on a backend 5xx's access line
  > (REQ-NOTIF-010, REQ-OBS-001/002).
- **The images' OCI `org.opencontainers.image.version` label reads `main`**, not `v1.11.0`: the tag
  run re-tags the `main` build (ADR-0137/0210), and `docker/metadata-action` labelled that build
  from its branch. Cosmetic — the version the app shows is baked by `.github/scripts/app_version.py`
  and reads v1.11.0.
- **`podman logs` returns nothing for the Quadlet containers** when run as `iri` here; the logs are
  in the journal: `journalctl CONTAINER_NAME=<svc> --since … -o cat`. And a script fed to `bash -s`
  over SSH needs `</dev/null` on every `podman` call — `podman healthcheck run` swallowed the rest of
  the script once.

**Still open after the deploy (each owner-gated):** the §7 list — above all the Android #182
release, the host reboot for the pending kernel, and the testing realm (then start testing's
`iri-deploy.timer` again). *(2026-09-25 evening: the reboot and most of §7 are done — see the
status note at the end of §7.)*
