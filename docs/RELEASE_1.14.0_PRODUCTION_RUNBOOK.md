# Release 1.14.0 — production rollout runbook

> **Doc type:** Operator runbook for **one** release — the move of production (and, through
> `promote.yml`'s `sync-testing`, the testing host) from **v1.13.7 to v1.14.0**. Written 2026-10-10 from
> a read-only audit of the 235 commits between `v1.13.7` and `4d60fd5cb`, against the code and the docs
> on `main`. **Historical once 1.14.0 is live**: freeze it then and let [`deployment.md`](deployment.md)
> stay the living truth. Several production facts below are **claims from the repository's own docs**,
> not reads; the pre-checks in §2.2 turn them into reads.
>
> **Every step that writes to a host is a production write**: it waits for @greluc's explicit yes,
> in chat, to that exact command (CLAUDE.md → *Production host access*). Nothing here is approval, and
> no command in this document has been executed.

Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are the ones in
[`deployment.md` → Shell conventions](deployment.md#shell-conventions-used-below).

> [!danger] **This release cannot be rolled back by code alone.**
> Migrations `V262`–`V264` make `quality_tier_id` `NOT NULL` on three job-order tables and `V264` drops
> an index; `V261`–`V265` are not reversible by any shipped script. Once they have run, v1.13.7 cannot
> write job orders on that schema. **The rollback of 1.14.0 is a restore from the backup taken
> immediately before the promotion**, and it loses every write since. §5 says what that means. Take the
> backup (§2.3) before you promote, not after.

---

## 0. What changes (audit of 2026-10-10)

| | Value |
|---|---|
| Range | `v1.13.7` (2026-10-03) to `4d60fd5cb`, 235 commits; the release is cut from the commit green CI names (§3) |
| Flyway | 18 migrations, `V261`–`V265`, `V267`, `V269`, `V271`–`V281`; production head before: `V260` (read 2026-10-10) |
| Schema | `V261` `quality_tier`; `V262`–`V264` back-fill and `NOT NULL` on `job_order_material`, `job_order_item_material`, `material_claim`; `V265` seven `CHECK`s bounding every quality to 0–1000; `V267`/`V269`/`V273`/`V274`/`V276`–`V281` seed 46 notification rules; `V271` `notification_mute`; `V272`/`V275` nullable marker columns |
| Gaps | `V266`, `V268`, `V270` do not exist on `main` (see §6) |
| One restart window | backend, frontend, ingest, the Keycloak provider JAR (Keycloak restarts), the edge (recreated, new admission map), the monitoring reconcile (Grafana, Tempo, Alloy, redis_exporter images) |
| Expected maintenance page | about two to three minutes, once |

Read 2026-10-10, counts only: 45 missions, 206 refinery orders, 91 users; no quality outside 0–1000 in
the seven checked columns.

---

## 1. What the deploy does by itself — no action, but know it

- **Flyway runs 18 migrations before the new backend serves.** `V262` and `V265` scan whole tables under
  `ACCESS EXCLUSIVE`; all three applications are stopped meanwhile, so nothing writes. Duration scales
  with row counts (expected seconds; not measured on production size).
- **`V262` and `V265` abort backend startup** on any quality value outside 0–1000 (`V262`: a
  `job_order_material.min_quality` that finds no tier). Each migration is its own transaction, so a
  failure at `V265` leaves `V261`–`V264` committed. §2.2 reads for exactly this; §5 describes the case.
- **Every member keeps their session.** The session allow-list change (`F2`, #2407) refuses nothing a
  v1.13.7 session holds; a refused value would only drop that one attribute.
- **The public API edge becomes verb-exact** (`docker/edge/include/api-admission.conf`, 248 entries): every
  operation of Android app `v0.4.x` and `v0.5.0` is admitted, 16 operations that were only path-admitted
  now answer 404 (none is an app call), `HEAD` is no longer admitted on the API vhost, and the app's
  order edit (`PUT /orders/{id}/requested`, blocked since app `v0.2.0`) works again.
- **The Android floor is part of the release**: minimum 17, latest 17. The `APP_ANDROID_*_VERSION_CODE`
  and `..._RELEASES_URL` variables in `.env` become dead and harmless.
- **The timed-notification producer starts on the first tick** (`app.notifications.timed.enabled`,
  default true) with a budget of 100 events per run and windows of 14 days (mission never ended) and
  7 days (refinery order ready), so history is not announced (REQ-NOTIF-026, -029). Reminders 24 h and
  1 h before a mission start at once.
- **Prometheus series step up**: `max-uri-tags` goes 100 to 1000 and `http.server.requests` gets a
  percentile histogram; routes silently dropped before now appear (§2.5, §4).
- **Monitoring images move**: Grafana 13.2.2 to 13.2.3, Tempo 3.0.3 to 3.1.0 (writes `vParquet5` blocks),
  Alloy v1.20.0 to v1.20.1, redis_exporter v1.92.0 to v1.93.0.
- **Trusted Types runs in report-only mode**; nothing is blocked. Enforcing it is a separate owner step
  ([`OWNER_STEPS_2026-10.md` § 8](OWNER_STEPS_2026-10.md#8-trusted-types-enforce)).

---

## 2. Before the promotion

### 2.1 Release commit is green (no host change)

For the exact commit *Release · Prepare* will cut: **CI**, **Release Images**, **Repo Lint** and the
**E2E** run all green. A cancelled run is not green; re-run it. The known flakes are the full backend
test run under contention and a registry fetch inside the Release Images build; a rerun is legitimate, a
red you cannot explain is not.

### 2.2 Read-only pre-checks on production

**The quality range** (`V262`, `V265`). The statements are read-only and written to nothing: the SQL goes in on
standard input, so no file is created on the host. Every `violating` count must be `0`.

```bash
cd /
UPOD="sudo -u iri podman"
${UPOD} exec -i db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -At' <<'SQL'
SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
SELECT 'inventory_item.quality' AS col, count(*) AS violating FROM inventory_item WHERE NOT (quality IS NULL OR quality BETWEEN 0 AND 1000)
UNION ALL SELECT 'refinery_good.quality', count(*) FROM refinery_good WHERE NOT (quality IS NULL OR quality BETWEEN 0 AND 1000)
UNION ALL SELECT 'job_order_handover_item.quality', count(*) FROM job_order_handover_item WHERE NOT (quality IS NULL OR quality BETWEEN 0 AND 1000)
UNION ALL SELECT 'blueprint_ingredient.min_quality', count(*) FROM blueprint_ingredient WHERE NOT (min_quality IS NULL OR min_quality BETWEEN 0 AND 1000)
UNION ALL SELECT 'job_order_material.min_quality', count(*) FROM job_order_material WHERE NOT (min_quality IS NULL OR min_quality BETWEEN 0 AND 1000)
UNION ALL SELECT 'blueprint_requirement_modifier.quality_min/max', count(*) FROM blueprint_requirement_modifier WHERE NOT ((quality_min IS NULL OR quality_min BETWEEN 0 AND 1000) AND (quality_max IS NULL OR quality_max BETWEEN 0 AND 1000))
UNION ALL SELECT 'blueprint_modifier_segment.quality_min/max', count(*) FROM blueprint_modifier_segment WHERE NOT ((quality_min IS NULL OR quality_min BETWEEN 0 AND 1000) AND (quality_max IS NULL OR quality_max BETWEEN 0 AND 1000));
SELECT 'job_order_item_material' AS tbl, quality_requirement, count(*) FROM job_order_item_material GROUP BY 2;
SELECT 'material_claim' AS tbl, quality_requirement, count(*) FROM material_claim GROUP BY 2;
SELECT min_quality, count(*) AS rows FROM job_order_material GROUP BY 1 ORDER BY 1;
SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE relname IN ('inventory_item','refinery_good','job_order_handover_item','job_order_material','job_order_item_material','material_claim','blueprint_ingredient','blueprint_requirement_modifier','blueprint_modifier_segment','mission','refinery_order') ORDER BY 2 DESC;
SQL
```

Expected: the first row `260|t`; every `violating` count `0`; `quality_requirement` only `GOOD` / `NONE`
(`V263`, `V264` find a tier by that code); the legacy `min_quality` values are the seven floors the spec names
(30, 100, 353, 500, 800, 850, 900) plus `NULL`, `650` and `0` (REQ-DATA-023). **Any non-zero count, or a
`quality_requirement` that is neither: do not promote** — fix the rows first (a production write, your
decision), or the backend will not start.

**The remaining reads:**

```bash
cd /
curl -s https://api.profit-base.online/api/v1/app/version-policy                      # minimum 17, latest 17, the GitHub releases URL
grep -cE '^APP_ANDROID_(MINIMUM|LATEST)_VERSION_CODE=' /var/iri/code/.env             # 0 or more; dead after the release, harmless (names only)
grep -c '^APP_NOTIFICATIONS_TIMED_ENABLED=' /var/iri/code/.env                        # 0 unless switched off on purpose
systemctl show iri-backup.service -p Result -p ExecMainExitTimestamp                  # success, last night
```

If the policy answers anything but 17 / 17, the promotion changes the floor: correct the literal in a PR
first or accept the change deliberately.

**Optional sizing of what the notification producer would announce.** With the windows and the budget in
the release this is bounded and informational; with the numbers read on 2026-10-10 the first tick raises
at most a handful of notices. To read it anyway (read-only, counts):

```bash
${UPOD} exec -i db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -At' <<'SQL'
SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;
SELECT count(*) AS missions_in_window FROM mission WHERE status IN ('ACTIVE','COMPLETED') AND actual_end_time IS NULL AND never_ended_notified_at IS NULL AND planned_end_time IS NOT NULL AND planned_end_time < now() - interval '6 hours' AND planned_end_time >= now() - interval '6 hours' - interval '14 days';
SELECT count(*) AS orders_in_window FROM refinery_order WHERE status IN ('OPEN','IN_PROGRESS') AND stored_at IS NULL AND ready_notified_at IS NULL AND started_at IS NOT NULL AND duration_minutes IS NOT NULL AND started_at + make_interval(0,0,0,0,0,0, duration_minutes * 60) <= now() AND started_at + make_interval(0,0,0,0,0,0, duration_minutes * 60) > now() - interval '7 days';
SQL
```

### 2.3 Backup right before the promotion — the only rollback this release has

A production write (a service start), so it needs your yes. Run it **after** the last pre-check and **before**
`promote.yml`, then read the log:

```bash
systemctl start iri-backup.service
systemctl show iri-backup.service -p Result --value        # success
tail -40 /var/log/iri-backup.log                           # ends without FATAL; note the snapshot id
```

Confirm the off-site copy exists and note the Flyway head (`260`) and the time. The restore procedure is
[`backup.md` → Restoring](backup.md#restoring-disaster-recovery); read it now, not during an incident. A
migration rehearsal on a copy of that snapshot (a throwaway Postgres) is the stronger check and is
optional.

### 2.4 Announce (no host change)

A short note to the members: one maintenance page of about three minutes; the visibly redesigned site
(supported browsers Chrome 122, Firefox 131, Safari / iOS 18.4 or newer); notifications with a per-type
switch under *Profil → Benachrichtigungen*. To the admins: the new *Qualitätsstufen* catalogue, and that an
Android app older than the unreleased next build cannot mute a type and shows generic wording for the 46 new
notification types.

### 2.5 Baseline for the comparison in §4

Note before promoting: the alerts firing in Grafana → Alerting; `prometheus_tsdb_head_series`;
`process_resident_memory_bytes{job="prometheus"}` (the container limit is 1 GiB); and
`count(count by (uri)(http_server_requests_seconds_count{application="basetool-backend"}))` (expected at most
100 today). The nightly *Edge Deny Probe* has been red since 2026-10-04 because the live edge answers 401 / 405
where the new map expects 404; it turns green after the deploy.

### 2.6 Optional: the host scripts

`deploy.sh` and the other scripts reach the host only through the Ansible role, not the config bundle. This
release changes `scripts/deploy.sh` (the mixed-release grace). To see whether the host's copy is current:
`sha256sum /var/iri/code/scripts/deploy.sh` against `git show origin/main:scripts/deploy.sh | sha256sum`. A
role run (`--tags deploy,scripts`) is a separate step after, not a precondition. The narrowed sudoers rule of
[`OWNER_STEPS_2026-10.md` § 2](OWNER_STEPS_2026-10.md#2-the-deploy-sudoers-rule-ops-sec-08) is not depended on.

---

## 3. Cut the release and promote

1. *Actions → Release · Prepare → Run workflow*, version `1.14.0`; merge the `chore(release): v1.14.0` PR.
   Everything on `main` when it merges is part of 1.14.0. The publish job tags the merge commit.
2. The tag run of `release-images.yml` re-tags the five images as `:1.14.0`. Confirm all five exist before
   promoting (the promote's own first step also refuses otherwise).
3. Optional: `gh workflow run promote-testing.yml -f version=1.14.0` first.
4. **Promote** (from `main`; the production write; you approve in the GitHub UI):

```powershell
gh workflow run promote.yml -f version=1.14.0
```

   Three gates: the vulnerability scan, your approval on the `production` environment, the signature and
   one-release check before the five tags move to `:stable`.

Within about five minutes the `deploy.sh` tick picks it up. Follow it (read-only):

```bash
cd / && tail -f /var/log/iri-deploy.log
```

Expected: `release parts: …`, every digest verified, the config bundle staged, the units installed, the
services restarted, `Successfully applied 18 migrations`, health green, `deploy successful`. **Not**
expected: `rolled back`, `drift`, `required file missing`. A `rolled back` line means §5.

---

## 4. After the tick — verify (read-only)

```bash
cd /
UPOD="sudo -u iri podman"
${UPOD} inspect backend frontend ingest --format '{{.Name}} {{index .Config.Labels "org.opencontainers.image.version"}}'   # v1.14.0 x3
${UPOD} exec db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -Atc "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; SELECT max(version::int) FROM flyway_schema_history WHERE success; SELECT count(*) FROM flyway_schema_history WHERE version::int >= 261 AND success;"'   # 281 and 18
${UPOD} exec db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -Atc "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; SELECT code, min_quality, active FROM quality_tier ORDER BY min_quality; SELECT count(*) FROM notification_rule WHERE id >= '"'"'62200000-0000-0000-0000-00000000000f'"'"' AND id <= '"'"'62200000-0000-0000-0000-00000000003c'"'"';"'   # NONE and GOOD active, the legacy tiers inactive; 46
curl -s https://api.profit-base.online/api/v1/app/version-policy                      # 17 / 17
curl -s -o /dev/null -w '%{http_code}\n' https://api.profit-base.online/api/v1/terms/status   # 401 (admitted, needs a login)
curl -s -o /dev/null -w '%{http_code}\n' https://api.profit-base.online/api/v1/me/layout     # 404 (no longer admitted on the API vhost)
curl -sI https://profit-base.online/ | grep -i 'content-security-policy-report-only'          # the Trusted Types report header
```

Then, off the host:

- **Edge probe:** `gh workflow run edge-deny-probe.yml` turns green (red since 2026-10-04).
- **Loki:** `{app="ops-deploy"}` shows `deploy successful` and no rollback; `{app="backend-stdout"} |= "Successfully applied"`
  shows 18 migrations to `281`; `{app="backend-stdout"} |= "rejected a task"` and `|= "RejectedExecutionException"`
  and `|= "LazyInitializationException"` stay empty; `{app="frontend"} |= "not on the session type allow-list"` stays empty.
- **Prometheus, notifications:** `basetool_scheduled_job_last_success_timestamp_seconds{task="notification_timed"}` moves every
  minute; `sum by (kind)(increase(basetool_notification_timed_produced_total[1h]))` over the first hour is the
  flood measurement (compare with the optional sizing in §2.2); `sum(increase(basetool_notification_executor_rejected_total[1h]))`
  is `0`; `sum by (notification_type)(increase(basetool_notification_created_total[1h]))`; `sum(increase(basetool_notification_muted_total[1h]))`.
- **Prometheus, the rest:** `basetool_android_version_policy_override == 1` returns nothing;
  `sum by (mode)(increase(basetool_session_type_refused_total[1h]))` is empty; `basetool_trusted_types_mode` shows `mode="report"`;
  `sum(increase(basetool_client_error_total{kind="csp_violation"}[1h]))` is low; `increase(basetool_security_expression_failures_total[1h])` is empty;
  `basetool_scheduled_job_last_success_timestamp_seconds{task="uex_sync"}` moves after the next sync (the UEX and SC-Wiki clients now refuse
  non-public addresses and swallow failures into an empty result, so a silent empty sync is the symptom).
- **Series growth** against the §2.5 baseline after one hour and after 24 hours: `prometheus_tsdb_head_series`, Prometheus RSS
  (limit 1 GiB), the distinct-`uri` count (it will exceed 100; `HttpUriTagCapNear` fires at 900), and the 5xx and latency baselines,
  which now include routes that were invisible before.
- **Smoke:** web login (the existing session survives, nobody is signed out); open a mission, the new Hangar and Materialbörse pages; create a
  job order with a `GOOD` and a `NONE` line; register a handover with the quality choice; one UEX-backed price page; one desktop import through
  ingest; Android `0.4.x` or `0.5.0`: sign in, open an order, edit an order as the requester.
- **Alerts:** compared with the §2.5 baseline. Expected and benign: possibly `ClientErrorSpike{kind="csp_violation"}`. `HttpUriTagCapNear`,
  `NotificationTimedStale`, `NotificationExecutorRejected`, `NotificationCreationFlood`, `DeployRolledBack` and `DeployFailed` must stay silent.

---

## 5. Rollback and its limits

**There is no safe code-only rollback.** `gh workflow run promote.yml -f version=1.13.7` and the deploy health
gate's automatic rollback restore **images, the config tree, units, pins and the provider JAR — not the database,
not Redis, not the Prometheus / Tempo data, not the Keycloak realm.** On the schema `V264` leaves behind:

- `quality_tier_id` is `NOT NULL` with no default on `job_order_material`, `job_order_item_material` and `material_claim`,
  so every insert into them fails under v1.13.7: creating, handing over and claiming on job orders break.
  Items created under 1.14.0 have `NULL` in the old columns (`min_quality`, `quality_requirement` are no longer written);
  the old unique index on `material_claim` is gone.
- Rows carrying the 46 new notification types make the v1.13.7 `NotificationType` deserialisation fail: the inbox and the
  admin rule list answer 500 for the affected members.
- Notices already created stay in the inboxes (retention 90 days read, 180 days unread).
- Tempo blocks written as `vParquet5` may not be readable by 3.0.3.

**The nastiest case is a failed `V265`.** `V261`–`V264` are committed, `V265` is not, the new backend fails its
health gate, and the gate rolls back to v1.13.7 **on the `V264` schema**, where job-order writes fail, and records a
backoff (600 s, doubling). Recovery: fix the offending rows (your decision, a production write) and let the next tick
retry, or `deploy.sh --force`; or restore the pre-promotion backup.

**So the rollback is: restore from the backup of §2.3.** It returns the database to the instant of the backup and
loses every write since, from every member. Procedure: [`backup.md` → Restoring](backup.md#restoring-disaster-recovery),
then promote the version the restored data fits (`1.13.7`). Stop the deploy timer first so no tick brings the new
release back mid-restore. Compensating SQL for a rollback that keeps the data (drop the three `NOT NULL`s, re-fill the old
columns from `quality_tier`, recreate the dropped unique index, delete the new rules and notices) is a sketch that was never
rehearsed; do not run it without a rehearsal on a copy.

Re-upgrading after a rollback runs the migrations again; the timed producer re-fires anything still unmarked, bounded by its
windows and budget.

---

## 6. Not part of this release — keep it that way

- **PR #2370** (drops the superseded quality columns; now `V282`) must stay **unmerged until after the 1.14.0 deploy**. `out-of-order`
  is off in Flyway: a migration merged below an applied `V281` never runs.
- `V266`, `V268`, `V270` are gaps on `main`; Flyway tolerates gaps.
- PR #2443 (the frontend package move) and the API-cut branches are not on `main`; this release carries the session allow-list
  change only.
- Host steps independent of this promotion: [`OWNER_STEPS_2026-10.md`](OWNER_STEPS_2026-10.md) steps 1–8.
