# External Client Exchange — production go-live runbook

> **Doc type:** Operator runbook for **one** rollout — the go-live of the External Client Exchange
> ([#2092](https://github.com/krt-profit/basetool/issues/2092), epic #2078; `REQ-XCH-002`,
> `REQ-XCH-023`, `REQ-XCH-033`), together with the coupled release of the „gestohlen" marker and the
> Android app (#2096, #2097) and the SC Extractor's move to the exchange (#2088, security finding H1).
> Written 2026-09-28 against `main` at `26ff2b6c7`, the #2092 body and the owner's extractor order of
> 2026-09-27 (issue comment 5858785595). **No host was read for it**: every value only production can
> tell is marked **TO BE READ** and comes with the read-only command that establishes it. Freeze it as
> a historical record once executed; [`deployment.md`](deployment.md) stays the living procedure.
>
> **Every step that writes is a production write.** It waits for @greluc's explicit yes, in chat, to
> that exact command (CLAUDE.md → *Production host access*). Nothing in this document is approval —
> not the order, not a „go ahead" given for an earlier step.

---

## 0. Conventions

**Reaching the host.** From the owner's Windows workstation only through PowerShell and the Windows
OpenSSH client, which reaches the agent that holds the key (Git Bash's `ssh` fails with
`Permission denied (publickey)`): `ssh root@46.225.24.180`. The commands below are typed in that
interactive root shell. A one-shot read can also be passed in single quotes,
`ssh root@46.225.24.180 'cd / && …'`; a multi-line script is piped in with its carriage returns
stripped (vault *Production Access*).

**Shell prelude**, once per session, as root, from `/` — the conventions of
[`deployment.md` → Shell conventions](deployment.md#shell-conventions-used-below):

```bash
cd /
IRI_UID=$(id -u iri)
UCTL="sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} systemctl --user"
UPOD="sudo -u iri podman"
ENVF=/var/iri/code/.env
```

`IRI_UID` is 994 on production. The containers are reached through `sudo -u iri`, never
`runuser -u iri`: `runuser` fails `podman exec` with a crun cgroup „Permission denied" on this host
(vault *Production Access*, *Keycloak*), and `sudo -u` keeps the working directory, which is why
every command starts at `/`.

**Editing `.env`.** Every `.env` change below goes through this helper, which keeps a dated backup,
replaces or appends the one line, keeps the file's inode, owner, mode and SELinux label (the file
stays `deploy:deploy 0640`, LF), and prints how many lines carry the key (must be `1`):

```bash
env_set() {
  local key="$1" value="$2"
  cp -p "$ENVF" "$ENVF.backup-$(date +%Y%m%d-%H%M%S)-$key"
  if grep -q "^${key}=" "$ENVF"; then
    ( umask 077; sed "s|^${key}=.*|${key}=${value}|" "$ENVF" > /root/.env.edit ) \
      && cat /root/.env.edit > "$ENVF" && rm -f /root/.env.edit
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENVF"
  fi
  grep -c "^${key}=" "$ENVF"
  stat -c '%U:%G %a' "$ENVF"
}
```

A container reads `env.d/<svc>.env`, never `.env`, so a `.env` change reaches a running service only
after the render and a restart ([`INGEST_KEYCLOAK_SETUP.md` → Applying an `.env` change](INGEST_KEYCLOAK_SETUP.md#applying-an-env-change-on-the-production-host)):

```bash
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
```

**Checking what a container really got** — present in `.env` is not the same as effective. Only named,
non-secret variables, never the whole environment:

```bash
${UPOD} exec <svc> printenv <NAME>
```

**Restarts travel along `Requires=`** ([`deployment.md` → Driving the stack](deployment.md#driving-the-stack)):
`ingest` alone restarts nothing else; `backend` also restarts `frontend` and `ingest` (about one
minute of web outage); `redis` restarts `frontend` and `ingest`; `keycloak` restarts everything.
A `restart` returns before its dependents are back — follow it with a `start` of them, which waits.

**Monitoring** is read off the host: Grafana dashboards **Exchange** (`basetool-exchange`), **Redis**
(`basetool-redis`), **Basetool operations** (panels 45, 46, 48, 51, 64–74, 80), **Ops automation**,
and Grafana → Explore for the PromQL and LogQL below. Note the firing alert set **before** each step,
so the comparison afterwards is against a baseline.

---

## 1. Where things stand (2026-09-28, from the repositories)

| Piece | State |
|---|---|
| Exchange code (gateway, backend layer, registry, audit domains, admin and member pages, admin bulk undo) | on `main`; E2E incl. the exchange flows green (run 36400316929 at `26ff2b6c7`) |
| Redis 768 MB / 1024 MB (ADR-0221, #2115) | in the units on `main` — `quadlet/systemd/redis.container`: `--maxmemory 768mb`, `Memory=1024M` |
| Redis ACL rows (`exchange:*`, `ingest:xch:*`, `+eval +evalsha +zrem +zscore`) | in `scripts/redis-users.acl.tmpl` on `main`; **not rendered on production** |
| Flyway `V246`–`V257` | on `main`; the latest release, **v1.12.0, ends at `V245`** |
| Keycloak: `exchange.*` scopes, `versekit`, the extractor's H1 shape, #2179 | in `scripts/provision-keycloak-realm.py` on `main`; **not applied on production** |
| keycloak-spi (ADR-0226 admin extension `basetool-exchange`, ADR-0228 login forms `krt-freemarker`) | on `main`; reaches production with the next release's provider JAR |
| **Terms change** (`terms.list_4_1_5`, REQ-SEC-027/-028) | **not built** — the three backend bundles still carry the v1.12.0 text (see §10, gap 1) |
| **Privacy notice** for approved clients (REQ-XCH-002, third box) | **not built** (see §10, gap 1) |
| SC Extractor 2.10.0 | on the extractor's `main`, **unreleased**; latest release v2.9.1 |
| Android app with #190–#196 | on the app's `main` at `versionCode` 16, **unreleased**; latest release v0.3.1 (`versionCode` 16) |
| VerseKit | **not approved**: `docs/legal/approved-clients.md` lists no client, while `scripts/keycloak/external-clients.json` already lists `versekit` |
| Load test of the feed/changes routes | **open** (#2092 pre-flight) |
| Final security review (G5) | **open** |

The next release cut from `main` therefore carries **everything** at once: the stolen-marker server
half, the Redis units, the migrations, the SPI jar and the dormant exchange. Sections 5 and 6 treat
the release as **one** step (S6) that is both step 1 of the coupled sequence and step 3 of the
exchange rollout of #2092.

---

## 2. The order, and why it is this order

| # | Step | Kind | Restarts |
|---|---|---|---|
| S1 | Install the new ACL renderer and template (role run) | PRODUCTION WRITE | nothing |
| S2 | Redis headroom check | read | — |
| S3 | Render the Redis ACL, `ACL LOAD` | PRODUCTION WRITE | nothing |
| S4 | Stage the exchange's `.env` values | PRODUCTION WRITE | nothing (read at S6) |
| S5 | Cut the release | GitHub | — |
| S6 | Promote the release; Redis 768 MB / 1024 MB, `V246`–`V257`, the SPI jar land | PRODUCTION WRITE | the whole stack, once (~2–3 min) |
| S7 | Publish the Android app | GitHub (app repo) | — |
| S8 | Raise the app's minimum version | PRODUCTION WRITE | backend → frontend, ingest (~1 min) |
| S9 | Switch „gestohlen" marking on | PRODUCTION WRITE | backend → frontend, ingest (~1 min) |
| S10 | Keycloak session and provisioner **dry run** | PRODUCTION WRITE (session) | nothing |
| S11 | Admin page: global exchange switch on | PRODUCTION WRITE (admin UI) | nothing |
| S12 | Admin page: registry entry `basetool-sc-extractor` | PRODUCTION WRITE (admin UI) | nothing |
| S13 | Publish SC Extractor 2.10.0 | GitHub (extractor repo) | — |
| S14 | Switch the legacy `/v1` endpoints off | PRODUCTION WRITE | ingest (~30 s) |
| S15 | Provisioner **apply** (H1, #2179, exchange scopes, `versekit`) | PRODUCTION WRITE | nothing |
| S16 | Admin page: registry entry `versekit` | PRODUCTION WRITE (admin UI) | nothing |
| S17 | Widen VerseKit: stock → org demand → hangar, one at a time | PRODUCTION WRITE (admin UI) each | nothing |

The constraints that fix it:

- **S3 before S6.** From the release on, the gateway reads `exchange:registry` every 30 s as
  `basetool-ingest` (`ExchangeRegistryMirrorAge`), and the backend writes it at start when the
  mirror is on. Without the rendered ACL both are refused `NOPERM`: `RedisAclDenials` fires within
  minutes, `ExchangeMirrorWriteFailed` too, and every exchange write would fail `503` on the first
  `EVALSHA`. The extra grants are harmless to v1.12.0, so S3 can run any time before S6.
- **S4 before S6.** `APP_EXCHANGE_MIRROR_ENABLED` is read only by the new backend template, which the
  release renders; staged before, it costs no extra restart, and v1.12.0 ignores it on a rollback.
- **S6 → S7 → S8 → S9** (#2092, *Stolen marker and app*). The app's new screens and routes exist only
  on the new server, `UpdateGate` reads the minimum once per process, and marking goes on last.
  S8 and S9 may share one backend restart if the owner chooses (§6, S9).
- **S13 → S14 → S15, in one sitting** (the owner's extractor order, H1). 2.10.0 must be the latest
  release before the switch-off (`UpdateChecker` polls `releases/latest` only at start); the
  provisioner must not run before the switch-off, because it withholds `extractor-ingest-only`, which
  2.9.1 still needs on `/v1`. The cost of this order: **from S13 until S15, 2.10.0 cannot sync**
  (the realm has no `exchange.*` scope yet), and **from S14 until S15 no extractor can**. Keep the
  gap to minutes: S10's dry run is done before S13, so S15 is only the apply.
- **S12 before S14.** Once the registry lists `basetool-sc-extractor`, `/v1` still admits it only
  because `IRI_INGEST_ALLOWED_CLIENT_IDS` names it too (review 2, L1) — keep that value.
- **S15 creates `versekit` only when it is listed** in `external-clients.json` — see S10 for running
  it without VerseKit when the approval PR is not merged yet. S16 needs both the approval and the
  Keycloak client.

---

## 3. TO BE READ — production values, all read-only

Each command is a standing read (CLAUDE.md → *Production host access*): it changes nothing, prints
no secret. Run them the day of the go-live, before S1; any answer other than the expected one stops
the step that depends on it.

| # | What | Command (host, root, from `/`, prelude loaded) | Expected |
|---|---|---|---|
| R1 | Deployed release | `${UPOD} inspect backend frontend ingest --format '{{.Name}} {{.ImageName}}'` and `cat /var/lib/iri/last-deployed.digests` | v1.12.0 digests |
| R2 | Deploy health and ownership | `tail -5 /var/log/iri-deploy.log; systemctl is-active iri-deploy.timer; find /var/iri/code/docker /var/iri/code/keycloak-theme /var/iri/code/monitoring /var/iri/code/quadlet ! -user deploy \| head` | last run `deploy successful` or no change; `active`; **no** path printed (the 1.11.0 lesson) |
| R3 | Installed scripts vs `main` | `sha256sum /var/iri/code/scripts/{deploy.sh,render-env-d.py,render-redis-acl.py,redis-users.acl.tmpl}` — compare on the workstation with `git show origin/main:scripts/<file> \| sha256sum` | equal → S1 can be skipped; different → S1 |
| R4 | The live ACL file | `stat -c '%U:%G %a %i' /var/iri/redis/users.acl; grep -c '^user default ' /var/iri/redis/users.acl; grep -c '>' /var/iri/redis/users.acl` | `root:root 644 <inode>`; `1`; `0` |
| R5 | The live ACL grants | `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL DRYRUN basetool-backend SET exchange:registry x'` and `… ACL DRYRUN basetool-ingest EVALSHA 0000000000000000000000000000000000000000 1 ingest:xch:probe` | before S3: both refused, naming the key or the command; after S3: `OK` |
| R6 | Redis memory now and at its peak | `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin INFO memory' \| grep -E '^(used_memory\|used_memory_peak\|used_memory_rss\|maxmemory):'`; Grafana → Explore: `max_over_time(redis_memory_used_bytes[30d])`, `max_over_time(redis_memory_used_rss_bytes[30d])`, `redis_memory_max_bytes` | peak well under 384 MB (`maxmemory` 402653184 until S6) |
| R7 | Host RAM headroom | `free -m`; Grafana → Explore: `min_over_time(node_memory_MemAvailable_bytes[30d])`, `node_memory_MemTotal_bytes`; `grep -h '^Memory=' /etc/containers/systemd/users/${IRI_UID}/*.container` | the 30-day minimum of available memory well above the 512 MiB the Redis limit grows by |
| R8 | Exchange and ingest switches in `.env` | `grep -cE '^APP_EXCHANGE_MIRROR_ENABLED=' $ENVF; grep -cE '^IRI_INGEST_LEGACY_ENDPOINTS_ENABLED=' $ENVF; grep -c '^IRI_INGEST_ALLOWED_CLIENT_IDS=basetool-sc-extractor$' $ENVF; grep -cE '^IRI_INGEST_CLIENT_AUDIT_ONLY=(true\|"true")$' $ENVF; grep -cE '^IRI_INGEST_PUBLIC_BASE_URL=https://ingest\.profit-base\.online$' $ENVF; grep -cE '^APP_INVENTORY_STOLEN_MARKING_ENABLED=' $ENVF` | `0`, `0`, `1`, `0`, `1`, `0` |
| R9 | The same, as the running ingest sees it | `for v in APP_INGEST_CLIENT_IDENTITY_ALLOWED_CLIENT_IDS APP_INGEST_CLIENT_IDENTITY_AUDIT_ONLY APP_INGEST_PUBLIC_BASE_URL; do printf '%s=' $v; ${UPOD} exec ingest printenv $v; done` | `basetool-sc-extractor`, `false`, `https://ingest.profit-base.online` |
| R10 | Android floor | `grep -E '^APP_ANDROID_(MINIMUM\|LATEST)_VERSION_CODE=[0-9]+$' $ENVF`; off the host: `curl -s https://api.profit-base.online/api/v1/app/version-policy` | `16` / `16` (vault *Android App*, 2026-09-25) |
| R11 | Redis users in `.env` | `grep -cE '^REDIS_(BACKEND\|INGEST\|FRONTEND)_USERNAME=' $ENVF; grep -c '^REDIS_DEFAULT_USER=off$' $ENVF` | `3`; `1` (APPSEC-04 done 2026-09-25) |
| R12 | Keycloak realm shape (extractor, exchange scopes, provisioner client) | the snapshot read below the table | extractor `consent=f`, `dpop.bound.access.tokens=false`, default scopes incl. `extractor-ingest` and `extractor-ingest-only`; **no** `exchange.*` scope; no `versekit`, no `basetool-provisioner` |
| R13 | Last backup | `systemctl show iri-backup.service -p Result -p ExecMainExitTimestamp` | `success`, today 04:15 |
| R14 | Extractor traffic to plan the announcement | Grafana → Basetool operations → panel 45 „Ingest calls/hour by client", last 7 days | how many sends a day the switch-off interrupts |
| R15 | Firing alerts baseline | Grafana → Alerting | written down before S1 |

R12 — `scripts/keycloak-config-snapshot.sql` (read-only by its first statement, secrets excluded) against `db-keycloak`, from PowerShell in a checkout, the carriage returns stripped:

```powershell
(Get-Content scripts\keycloak-config-snapshot.sql -Raw) -replace "`r","" |
  ssh root@46.225.24.180 'cd / && sudo -n -u iri podman exec -i db-keycloak sh -c "psql -qAt -U \$POSTGRES_USER -d \$POSTGRES_DB -p 15433 -f -"' |
  Select-String -Pattern '^(client\|basetool-sc-extractor\||clientattr\|basetool-sc-extractor\|dpop|scopeuse\|basetool-sc-extractor\||scope\|exchange\.|client\|versekit\||client\|basetool-provisioner\|)'
```

---

## 4. Before the first host step (no host write)

1. **Merge what the release must carry** — see §10, gap 1: the terms change (`terms.list_4_1_5` in
   `messages.properties`, `_de`, `_en`, linking `docs/legal/approved-clients.md`) and the privacy
   notice's approved-client paragraph (frontend `privacy.*`, DE and EN), with the wiki page
   „Nutzungsbedingungen". Without them the go-live release ships no terms change and no member
   re-consents; with them every member re-consents once at S6.
2. **Open boxes of #2092** the owner decides on before S5: the load test of the feed/changes routes
   on the testing host or the sandbox (never production), the final security review (G5), the
   forward-compatibility of `V246`–`V257` with v1.12.0 (read on `main`: all additive, new columns
   nullable or with a default, `V252`'s triggers fall back to `system` when
   `basetool.change_source` is unset — **not run** against a v1.12.0 backend), and the CHANGELOG line
   on the audit/metric attribution (`none` → real client ids; old rows stay „Ohne Client (System)").
3. **Have the three artefacts ready**: the extractor's 2.10.0 tag commit (TO BE DECIDED — the
   extractor's `main` is at `28a815d`, #75), the Android release PR bumping `versionCode` to **17**
   (TO BE DECIDED: its `versionName`), and — for S16 — the VerseKit approval (public issue plus the
   merged PR to `docs/legal/approved-clients.md`) with its first sync-capable version.
4. **Draft the announcements** (§9) and schedule the two short outages (S6, S8/S9).

---

## 5. Host preparation and the release

### S1 — Install the new ACL renderer and template

**PRODUCTION WRITE — needs @greluc's explicit per-action yes.** Skip it when R3 shows every file
equal to `main`.

- **Target:** production host, `/var/iri/code/scripts/` (root-owned). **Changes:** `deploy.sh`,
  `backup.sh`, `restore-drill.sh`, `container-cleanup.sh`, `lib/*.sh`, `render-env-d.py`,
  `render-redis-acl.py`, `mint-internal-tls.sh`, `redis-users.acl.tmpl` — whatever differs from
  `main`. **Restarts:** nothing; no credential changes.
- **Precondition:** a WSL controller with `ansible/`, `scripts/` **and** `monitoring/` mirrored from
  the same `origin/main` ([`ansible/README.md`](../ansible/README.md)); R2 clean.
- **Command** (WSL, in the mirrored `ansible/`):

  ```bash
  ansible-playbook site.yml --limit production --tags deploy,scripts --check --diff
  ansible-playbook site.yml --limit production --tags deploy,scripts
  ```

- **Expected:** the check lists only the files R3 found different; the run ends `failed=0`.
- **Verify:** R3 again — every sum equal to `git show origin/main:scripts/<file> | sha256sum`.
- **Watch:** nothing should move; `DeployFailed` stays silent at the next tick.
- **Rollback:** the same role run from a mirror of the previous commit (the one R3's old sums match,
  e.g. `v1.12.0`).

### S2 — Redis headroom (read)

ADR-0221 raises `maxmemory` from 384 MB to a **fixed 768 MB** and the container limit from 512 MB to
**1024 MB** (`maxmemory` + 256 MB AOF-rewrite headroom). It is **not a separate host step**: the
values sit in the generated unit's `Exec=` and `Memory=` lines, and `deploy.sh` restarts `redis` with
them at S6, because the release re-defines the unit (its image digest moved too). Before S6, confirm
with R6 and R7 that the host can give Redis 512 MB more: R7's 30-day minimum of available memory
must stay comfortably above that after S6. The unit limits sum to 13 968 MiB of `Memory=` on `main`
(ADR-0221 records 14 512 MiB across the compose files; the owner accepted passing ADR-0085's ~14 GB
trigger on 2026-09-26).

### S3 — Render the Redis ACL and load it

**PRODUCTION WRITE — needs @greluc's explicit per-action yes.**

- **Target:** `/var/iri/redis/users.acl` (bind-mounted **as a single file** into `redis`), then the
  running Redis. **Changes:** `basetool-backend` gains `~exchange:*` with `GET`/`SET`;
  `basetool-ingest` gains `%R~exchange:*`, `+incr`, the sorted-set commands and
  `+eval +evalsha +zrem +zscore`; nothing else. **Restarts:** nothing; nobody is signed out.
- **Precondition:** S1 done (R3 equal); R4 as expected; R5 refused.
- **Command** ([`deployment.md` → The Redis ACL](deployment.md#the-redis-acl) — render next to the
  live file and copy over it, because the single-file bind mount follows the **inode**):

  ```bash
  cd /
  cp -p /var/iri/redis/users.acl /var/iri/redis/users.acl.backup-$(date +%Y%m%d-%H%M%S)
  /var/iri/code/scripts/render-redis-acl.py --env /var/iri/code/.env \
    --template /var/iri/code/scripts/redis-users.acl.tmpl --out /var/iri/redis/users.acl.new
  grep -c '^user default ' /var/iri/redis/users.acl.new
  grep -c '>' /var/iri/redis/users.acl.new
  grep -c ' off ' /var/iri/redis/users.acl.new
  cat /var/iri/redis/users.acl.new > /var/iri/redis/users.acl && rm /var/iri/redis/users.acl.new
  stat -c '%U:%G %a %i' /var/iri/redis/users.acl
  ${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL LOAD'
  ```

- **Expected:** the renderer prints `render-redis-acl: wrote /var/iri/redis/users.acl.new (default,
  admin, monitoring, basetool-frontend, basetool-backend, basetool-ingest; default off). Apply it
  with ACL LOAD.`; the greps
  print `1`, `0`, `1` (`default` stays off); `stat` prints `root:root 644` and the **same inode** as
  R4; `ACL LOAD` answers `OK`.
- **Verify:**

  ```bash
  ${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL LIST' | sed -E 's/#[0-9a-f]{64}/#<hash>/g'
  for c in "basetool-backend SET exchange:registry x" \
           "basetool-backend GET exchange:registry" \
           "basetool-ingest GET exchange:registry" \
           "basetool-ingest SET exchange:registry x" \
           "basetool-ingest EVALSHA 0000000000000000000000000000000000000000 1 ingest:xch:probe" \
           "basetool-ingest EVAL return 1 ingest:xch:probe" \
           "basetool-ingest ZSCORE ingest:xch:budget:all x" \
           "basetool-ingest ZREM ingest:xch:budget:all x" \
           "basetool-ingest INCR ingest:xch:quota:probe" \
           "basetool-ingest GET basetool:session:probe"; do
    printf '%-80s ' "$c"
    ${UPOD} exec redis sh -c "REDISCLI_AUTH=\"\$REDIS_PASSWORD\" redis-cli --user admin ACL DRYRUN $c"
  done
  ```

  `ACL LIST` shows the new rows with `#<hash>` in place of each hash (never print the hashes
  themselves). `ACL DRYRUN` answers `OK` for the first three and for `EVALSHA`, `EVAL`, `ZSCORE`,
  `ZREM`, `INCR`; it **refuses** `basetool-ingest SET exchange:registry` (read-only there) and
  `basetool-ingest GET basetool:session:probe` (no session key). If `ACL LIST` still shows the old
  rows, the content did not reach the container (inode): `${UCTL} restart redis.service` loads it
  from the mount and also restarts `frontend` and `ingest` — a separate yes.
- **Watch:** `RedisAclDenials` stays silent; Redis dashboard *Connected Clients* unchanged;
  `check-conformance.py --only redis-requires-auth` from the workstation still passes.
- **Rollback:** `cat` the newest `users.acl.backup-*` back into `users.acl` (inode kept), then the
  same `ACL LOAD` as `admin`. Harmless to leave in place on a release rollback: v1.12.0 uses none of
  the new grants.

### S4 — Stage the exchange's `.env` values

**PRODUCTION WRITE — needs @greluc's explicit per-action yes.**

- **Target:** `/var/iri/code/.env`. **Changes:** one new line, `APP_EXCHANGE_MIRROR_ENABLED=true`.
  **Restarts:** nothing now — v1.12.0's `backend.env.tmpl` does not carry the variable, so a render
  before S6 drops it; S6's render puts it into `env.d/backend.env`.
- **Precondition:** S3 verified (the backend may write `exchange:*`); R8 and R9 as expected — the
  release's ingest refuses to start under `prod` with a blank `IRI_INGEST_PUBLIC_BASE_URL`
  (`PublicBaseUrlGuard`), and, while the legacy endpoints answer, with an empty
  `IRI_INGEST_ALLOWED_CLIENT_IDS` or `IRI_INGEST_CLIENT_AUDIT_ONLY=true` (`LegacyClientGateGuard`).
  If R8 or R9 differs, fix that first, as its own write.
- **Command:**

  ```bash
  env_set APP_EXCHANGE_MIRROR_ENABLED true
  ```

- **Expected:** `1`, then `deploy:deploy 640`.
- **Verify:** `grep -c '^APP_EXCHANGE_MIRROR_ENABLED=true$' $ENVF` → `1`; nothing else changed:
  `diff <(sort $ENVF.backup-*-APP_EXCHANGE_MIRROR_ENABLED) <(sort $ENVF) | grep -c '^[<>]'` → `1`
  (the diff prints the one new line; do not print the rest of the file).
- **Rollback:** delete the line with the same inode-keeping pattern —
  `( umask 077; grep -v '^APP_EXCHANGE_MIRROR_ENABLED=' $ENVF > /root/.env.edit ) && cat /root/.env.edit > $ENVF && rm -f /root/.env.edit`.
  After S6 that also needs a render and `${UCTL} restart backend.service` (§8).

The other exchange values stay as they are until their own steps: `IRI_INGEST_LEGACY_ENDPOINTS_ENABLED`
unset (default `true`) until S14; `APP_INVENTORY_STOLEN_MARKING_ENABLED` unset (default `false`)
until S9; `IRI_INGEST_ALLOWED_CLIENT_IDS=basetool-sc-extractor` and audit-only off, both kept for good.

### S5 — Cut the release

GitHub, no host change. [`deployment.md` → Cutting a release](deployment.md#cutting-a-release):
*Actions → Release · Prepare* with version **TO BE DECIDED** (v1.12.0 is the latest; this is at least
a minor, e.g. `1.13.0`) → merge the `chore(release): vX.Y.Z` PR → `release-publish.yml` tags it with the
`basetool-release` App token → the tag run of `release-images.yml` re-tags backend, frontend, ingest,
`config` and `keycloak-spi` as `:X.Y.Z`. Confirm all five exist before S6 (the promote refuses
otherwise). Everything on `main` at the merge is in the release — re-check any PR that lands after
this runbook against §2 and §10.

### S6 — Promote the release and watch the tick

**PRODUCTION WRITE — needs @greluc's explicit per-action yes** (the promotion is the write; the
`production` environment approval in GitHub is the owner's second gate, not a substitute for the
yes).

- **Target:** production, through `promote.yml` → `:stable` → the next `deploy.sh` tick.
- **What the tick does by itself:**
  - **One restart window for the whole stack**, ~2–3 minutes of maintenance page: the app images
    moved, the provider JAR moved (ADR-0226/0228 code), and the units of `redis`, `db-backend`,
    `db-keycloak`, `acme`, `grafana`, `prometheus` and `redis-exporter` changed (digest refreshes of
    the same tags — not gated by the stateful-infra check — plus Redis's new size). Keycloak comes up
    on the new JAR and the new `krt-theme` pages before backend.
  - **Redis** restarts with `--maxmemory 768mb` in a 1024 MB container (ADR-0221, REQ-OPS-018); the
    AOF keeps every session.
  - **Flyway** runs `V246`–`V257` before the new backend serves: RSI handle, `stolen` columns,
    the registry (switch **off**, registry **empty**), installations and revocations, catalogue name
    keys, the change feed with its triggers, the journal, ship links, blueprint provenance, bulk undo
    and two notification rules.
  - **The exchange stays dormant**: the mirror is written (S4), the switch is off, the registry
    empty — the gateway answers every exchange call `503 EXCHANGE_DISABLED`. The legacy `/v1`
    routes work as before. Marking is off (`canMarkStolen` false).
  - **Terms** — only if §4.1 merged: every member re-consents at the next page load, and every
    SC Extractor pauses until its member has (the backend refuses on-behalf-of imports without
    consent). Android v0.3.1 has no global re-consent handler (#2097's #194 ships with S7): an open
    app shows errors until it is restarted after the member re-consented on the web.
  - `sync-testing` moves `:testing`; the testing host's timer is stopped (held since 2026-09-25),
    so it stays on v1.10.0.
- **Precondition:** S3 and S4 verified; R1, R2, R13 fine; the §4.2 decisions taken; the outage
  announced.
- **Command** (workstation, on `main`):

  ```bash
  gh workflow run promote.yml -f version=X.Y.Z
  ```

  then approve the `production` deployment in GitHub, and on the host:

  ```bash
  cd / && tail -f /var/log/iri-deploy.log
  ```

- **Expected:** five `signature OK`, the bundle staged, `env.d` rendered, units installed,
  `provider JAR swapped in — keycloak restarts on it with this release`, `release parts: app images
  [backend frontend ingest] · unit definitions [...] · config bundle: yes · provider JAR: yes`,
  `applying`, `deploy successful`. **Not** `rolled back`, `FATAL`, `PRE-FLIGHT`, `CARVE-OUT`.
- **Verify** (host):

  ```bash
  ${UPOD} inspect backend frontend ingest --format '{{.Name}} {{.ImageName}}'
  ${UPOD} exec db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -Atc "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; SELECT version, success FROM flyway_schema_history WHERE version ~ '"'"'^2(4[6-9]|5[0-7])$'"'"' ORDER BY installed_rank;"'
  ${UPOD} exec db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -Atc "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; SELECT enabled FROM exchange_settings; SELECT count(*) FROM exchange_client;"'
  ${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin CONFIG GET maxmemory'
  ${UPOD} inspect redis --format '{{.HostConfig.Memory}}'
  ${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin EXISTS exchange:registry'
  ${UPOD} exec backend printenv APP_EXCHANGE_MIRROR_ENABLED
  journalctl CONTAINER_NAME=backend --since -15min -o cat | grep -m1 'Exchange registry mirror enabled'
  journalctl CONTAINER_NAME=keycloak --since -15min -o cat | grep -E 'krt-freemarker|basetool-exchange|ERROR' | head
  ```

  Expected: the three digests the promote run tagged `:X.Y.Z` (the page footer shows vX.Y.Z; the OCI
  version label may read `main`, cosmetic — 1.11.0 runbook §9); twelve rows
  `246|t` … `257|t`; `f` and `0`; `maxmemory` / `805306368`; `1073741824`; `1`; `true`; `Exchange
  registry mirror enabled (key=exchange:registry)`; Keycloak logs no `ERROR` (it may name the two
  provider ids in a `KC-SERVICES0047` warning for an internal SPI — if not, S15's device login is
  the functional proof of both).

  Off the host:

  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' https://ingest.profit-base.online/exchange/v1/openapi.json
  curl -s -o /dev/null -w '%{http_code}\n' https://ingest.profit-base.online/exchange/v1
  ```

  `200`, `401`. Sign in on the web (and re-consent if the terms changed), open „Mein Lager", a
  mission (live sync), *Administration → Verbundene Anwendungen* (switch „Ausgeschaltet", „Noch keine
  Anwendung registriert."); run one desktop import with 2.9.1 through `/v1`.
- **Watch** (first hour): Ops automation — no `DeployRolledBack`/`DeployFailed`; Redis dashboard —
  *Memory Used vs Limit* now against 768 MB, `RedisMemoryHigh` silent, `RedisAclDenials` silent;
  Exchange dashboard — *Registry mirror age at the gateway* under 60 s (`ExchangeRegistryMirrorStaleAtGateway`
  and `ExchangeRegistryReconcileStale` are armed from now on and must stay silent), *Registry clients
  by status* empty; Basetool operations — panel 71 „Legacy extractor endpoints" switch `1`, no 410s;
  panel 51 and `ApiUnknownClient` silent; `ExchangeMirrorWriteFailed`, `IngestUnknownClient`,
  `IngestAudienceGateOff` silent; Loki `{app="backend-stdout"} |= "ERROR"` and
  `{app="ingest-stdout"} |= "NOPERM"` empty.
- **Rollback:** lock-step, never per service — `gh workflow run promote.yml -f version=1.12.0`
  (§8). `V246`–`V257` stay. It also puts back v1.12.0's units, so Redis returns to 384 MB / 512 MB and
  restarts again.

---

## 6. The coupled release: app, minimum version, marking

### S7 — Publish the Android app

GitHub (basetool-android), no host change. The app release with #190–#196: merge the release PR
that bumps `versionCode` to **17**, tag it, let `release.yml` publish the APK and SBOM
(vault *Android App*). Its new actions appear only where `/api/v1/me/capabilities` advertises them,
so marking stays hidden until S9. **Precondition:** S6 verified (the app's new routes and
allowlist entries exist only on the new server). **Verify:** `gh release view --repo
krt-profit/basetool-android --json tagName,isLatest,assets`; install it on the Pixel AVD in German
(vault *Verifying the app on a device*), sign in, open „Mein Lager".

### S8 — Raise the app's minimum version

**PRODUCTION WRITE — needs @greluc's explicit per-action yes.** About **one minute of web and app
outage** — announce and schedule it.

- **Target:** `.env`, `env.d/backend.env`, `backend.service`. **Changes:**
  `APP_ANDROID_MINIMUM_VERSION_CODE` and `APP_ANDROID_LATEST_VERSION_CODE` 16 → 17. **Restarts:**
  backend, and through `Requires=` frontend and ingest.
- **Precondition:** S7 published and installable; R10 read (the current value for the rollback).
- **Command:**

  ```bash
  env_set APP_ANDROID_MINIMUM_VERSION_CODE 17
  env_set APP_ANDROID_LATEST_VERSION_CODE 17
  sudo -u deploy /var/iri/code/scripts/render-env-d.py \
    --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
  ${UCTL} restart backend.service
  ${UCTL} start ingest.service frontend.service
  ```

- **Expected:** `1` twice; the render names no missing variable; both systemctl calls return once
  the units are healthy.
- **Verify:** `curl -s https://api.profit-base.online/api/v1/app/version-policy` answers 17 / 17; an
  app older than 17 shows „Update erforderlich" at its next cold start; the web signs in.
- **Watch:** Spring apps dashboard — all three healthy; no `DeployHealthRestartFailing`; edge 5xx
  back to baseline within two minutes.
- **Rollback:** `env_set` both back to R10's value, render, the same restart pair.

### S9 — Switch „gestohlen" marking on

**PRODUCTION WRITE — needs @greluc's explicit per-action yes.** Another minute of outage — or run it
**in the same restart as S8** if the owner chooses (both are backend-only values; the order the plan
asks for is kept as long as S7 is published first).

- **Target:** `.env`, `env.d/backend.env`, `backend.service`. **Changes:**
  `APP_INVENTORY_STOLEN_MARKING_ENABLED=true` — booking in as, marking and unmarking „gestohlen" on
  the web, the app and (later) the exchange; `canMarkStolen` true. **Restarts:** backend → frontend,
  ingest.
- **Precondition:** S8 verified.
- **Command:**

  ```bash
  env_set APP_INVENTORY_STOLEN_MARKING_ENABLED true
  sudo -u deploy /var/iri/code/scripts/render-env-d.py \
    --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
  ${UCTL} restart backend.service
  ${UCTL} start ingest.service frontend.service
  ${UPOD} exec backend printenv APP_INVENTORY_STOLEN_MARKING_ENABLED
  ```

- **Expected:** `1`, the restarts return, `true`.
- **Verify:** on the web, a personal Lager row offers „als gestohlen markieren"; in the app (v17) the
  same action appears after a cold start.
- **Watch:** Lager audit tab shows the marking events; no `ERROR` in `{app="backend-stdout"}`.
- **Rollback:** `env_set APP_INVENTORY_STOLEN_MARKING_ENABLED false`, render, restart pair. Rows
  already marked stay marked (the column is data); only new marking stops.

---

## 7. The exchange go-live

### S10 — Keycloak session and provisioner dry run

**PRODUCTION WRITE — needs @greluc's explicit per-action yes** — the dry run writes nothing to the
realm, but its session does: a `basetool-provisioner` client created by the owner in the Admin
Console (SSH tunnel, [`deployment.md` → Keycloak Admin Console via SSH tunnel](deployment.md#keycloak-admin-console-via-ssh-tunnel);
confidential, service-account roles only, `realm-management` → `manage-clients` + `manage-realm`),
files under `/root/kc-realm/`, and a kcadm session file on the Keycloak tmpfs.

- **VerseKit decides which list the run uses.** `scripts/keycloak/external-clients.json` on `main`
  lists `versekit`, so a run with it **creates the VerseKit client**. That is right only once the
  approval PR to `docs/legal/approved-clients.md` is merged. If it is not, and H1 cannot wait, run
  with an empty list (`--external-clients /root/kc-realm/keycloak/none.json`, content `[]`) and repeat
  S10/S15 with the real list after the approval.
- **Copy the scripts** (PowerShell, in a clean checkout of the release tag; the files are LF):

  ```powershell
  ssh root@46.225.24.180 'install -d -m 0700 /root/kc-realm /root/kc-realm/keycloak'
  scp scripts\provision-keycloak-realm.py scripts\provision-keycloak-mobile-client.py root@46.225.24.180:/root/kc-realm/
  scp scripts\keycloak\external-clients.json root@46.225.24.180:/root/kc-realm/keycloak/
  Get-FileHash scripts\provision-keycloak-realm.py, scripts\provision-keycloak-mobile-client.py, scripts\keycloak\external-clients.json -Algorithm SHA256
  ```

  The realm provisioner imports the mobile one **and** reads `keycloak/external-clients.json` beside
  itself by default — copying only the two scripts ends in `cannot read the third-party client list`
  before anything is read. Without VerseKit: `printf '[]\n' > /root/kc-realm/keycloak/none.json`.
- **Open the session** (host; [`keycloak/README.md` → provisioning](keycloak/README.md#runbook--provisioning-the-mobile-client-basetool-android)):

  ```bash
  cd /
  chmod 0700 /root/kc-realm/*.py
  sha256sum /root/kc-realm/*.py /root/kc-realm/keycloak/*.json
  KCCFG=/opt/keycloak/data/tmp/kcadm.config
  kc() { sudo -u iri podman exec -i keycloak /opt/keycloak/bin/kcadm.sh "$@" --config "$KCCFG"; }
  KCADM="sudo -u iri podman exec -i keycloak sh -c 'exec /opt/keycloak/bin/kcadm.sh \"\$@\" --config $KCCFG' kcadm"
  sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config truststore \
      --trustpass - /run/secrets/keystore.p12 --config "$KCCFG"
  sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
      --server https://localhost:18443/auth --realm iri --client basetool-provisioner --config "$KCCFG"
  ```

  The sums must equal `Get-FileHash`. The truststore step is **untested** since internal TLS step 3
  (v1.12.0): `/run/secrets/keystore.p12` is now Keycloak's own leaf. If `config credentials` fails
  with a PKIX error, hand kcadm the CA-only truststore through the same tmpfs and repeat both
  commands with it:
  `sudo -u iri podman exec -i keycloak sh -c 'cat > /opt/keycloak/data/tmp/truststore.p12' < /var/iri/secrets/tls/truststore.p12`,
  then `config truststore --trustpass - /opt/keycloak/data/tmp/truststore.p12`. Both prompts want a
  value from the host (the keystore password `KC_HTTPS_KEY_STORE_PASSWORD`, the provisioner's secret
  from the Admin Console) — type them, never paste them into a transcript. Never retry a failing
  login blindly: the realm locks after five failures (vault *Keycloak* → *Administering the realm
  with kcadm*).
- **Rollback basis** (holds client secrets — stays on the host, `0700`, deleted at S15's end):

  ```bash
  kc get clients -r iri                   > /root/kc-realm/clients.before.json
  kc get client-scopes -r iri             > /root/kc-realm/client-scopes.before.json
  kc get client-policies/profiles -r iri  > /root/kc-realm/profiles.before.json
  kc get client-policies/policies -r iri  > /root/kc-realm/policies.before.json
  kc get realms/iri                       > /root/kc-realm/realm.before.json
  ```

- **Dry run:**

  ```bash
  export KEYCLOAK_FRONTEND_CLIENT_SECRET="$(sed -n 's/^KEYCLOAK_FRONTEND_CLIENT_SECRET=//p' /var/iri/code/.env | tail -1)"
  python3 /root/kc-realm/provision-keycloak-realm.py --realm iri \
    --public-origin https://profit-base.online --kcadm-command "$KCADM" --frontend-client confidential
  echo "exit=$?"
  unset KEYCLOAK_FRONTEND_CLIENT_SECRET
  ```

  (append `--external-clients /root/kc-realm/keycloak/none.json` without VerseKit). Every production
  run passes `--frontend-client confidential` with the secret: production's frontend client is
  confidential since 2026-09-25, and a run with that flag plans nothing for it.
- **Expected:** `[DRY RUN — nothing is written]`, `exit=2`, and only these changes (the exact list is
  **TO BE READ** from this output — anything else: stop and ask):
  - realm: whatever of `oauth2DeviceCodeLifespan` 600, `oauth2DevicePollingInterval` 5 and
    `loginTheme` `krt-theme` production does not have yet;
  - ten client scopes `exchange.connect` … `exchange.drafts.refinery` created, each with the
    `aud-basetool-ingest` mapper and a `${xchConsent…}` consent text;
  - `basetool-sc-extractor` (H1, #2201, #2179): `consentRequired` false → true;
    `dpop.bound.access.tokens` false → true; `client.offline.session.idle.timeout` 2592000 and
    `client.offline.session.max.lifespan` 7776000; `login_theme` `krt-theme`; default scopes reduced
    to `basic` — `extractor-ingest`, `extractor-ingest-only`, `profile`, `email`, `roles`,
    `web-origins`, `acr` withheld; optional scopes the five extractor exchange scopes plus
    `offline_access`, the rest withheld; the loopback redirect URIs and the code flow gone if still
    there;
  - `versekit` created from the template (only with the real list).
  No line may touch `basetool-frontend`'s type or secret, `basetool-android`, `backend-service` or
  `basetool-ingest-gateway`. `[only on this realm]` may list `basetool-provisioner` and `grafana`.
- **Watch:** nothing moves — a dry run only reads.
- **Rollback:** nothing to roll back in the realm; if S15 will not follow the same day, run S15's
  clean-up block now.

### S11 — Switch the exchange on

**PRODUCTION WRITE — needs @greluc's explicit per-action yes** (admin UI; no host access).

- **Target:** *Administration → Verbundene Anwendungen* → „Datenaustausch" → „Einschalten", confirm
  „Datenaustausch einschalten?". **Changes:** `exchange_settings.enabled` true; the mirror follows
  after the commit. With the registry empty every client is still refused `CLIENT_NOT_ALLOWED`.
- **Precondition:** S6 verified; the mirror age at the gateway under 60 s.
- **Expected:** toast „Datenaustausch umgeschaltet.", the switch reads „Eingeschaltet".
- **Verify:** within ~5 s (`app.exchange.registry-cache-ttl`) the gateway stops answering
  `EXCHANGE_DISABLED`; the „Verbundene Anwendungen" audit tab shows the change.
- **Watch:** `ExchangeRegistryChanged` fires once (expected — it fires on every registry change;
  note it); `ExchangeMirrorWriteFailed` silent.
- **Rollback:** „Ausschalten" — written to Redis **before** the commit, effective at once.

### S12 — Registry entry `basetool-sc-extractor`

**PRODUCTION WRITE — needs @greluc's explicit per-action yes** (admin UI).

- **Target:** „Anwendung registrieren". **Values:**

  | Field | Value |
  |---|---|
  | Client-ID (Keycloak) | `basetool-sc-extractor` |
  | Name | `SC Extractor` — **not** „Basetool SC Extractor": the registry refuses any display name containing „basetool" (review 2, L6) |
  | Berechtigungen | `exchange.connect`, `exchange.drafts.blueprints`, `exchange.drafts.refinery`, `exchange.blueprints.read`, `exchange.blueprints.write` — nothing else |
  | Mindestversion | `2.10.0` (builds below it, dev builds `1.0.0` included, are refused `403 CLIENT_VERSION_UNSUPPORTED`) |
  | Datenschutz und Sicherheitskontakt | TO BE DECIDED (optional; the extractor repository has no `SECURITY.md`) |
  | Limits | empty (defaults 120/min, 500 writes/day) |

  It starts `ACTIVE` („Freigegeben").
- **Precondition:** S11 done; R9's allowlist still names `basetool-sc-extractor` — the legacy
  routes refuse a registry client the allowlist does not name.
- **Verify:** the row shows „Freigegeben", five capabilities, „2.10.0"; a 2.9.1 import through `/v1`
  still works (panel 46 shows no `exchange_client` rejection).
- **Watch:** `ExchangeRegistryChanged` (expected); `IngestUnknownClient` with reason
  `exchange_client` must stay silent — if it fires, the allowlist is wrong.
- **Rollback:** „Sperren" (suspend) — the row stays; there is no delete.

### S13 — Publish SC Extractor 2.10.0

GitHub (basetool-sc-extractor), no host change — **the start of the one sitting S13–S15**. Tag the
decided commit `v2.10.0` and push the tag; the tag run builds the MSI, attests it and publishes a
non-prerelease GitHub release (vault *SC Extractor Release Pipeline*).

- **Precondition:** S10's dry run read and its session still open (or re-authenticate at S15 —
  the token lives 300 s); S12 done; the announcement ready.
- **Verify:** `gh release list --repo krt-profit/basetool-sc-extractor --limit 3` shows `v2.10.0`
  as **Latest**.
- From here until S15 an updated extractor cannot sync — move on at once.

### S14 — Switch the legacy endpoints off

**PRODUCTION WRITE — needs @greluc's explicit per-action yes.**

- **Target:** `.env`, `env.d/ingest.env`, `ingest.service`. **Changes:**
  `IRI_INGEST_LEGACY_ENDPOINTS_ENABLED=false` — `/v1/refinery-extract` and `/v1/blueprint-preview`
  answer `410 LEGACY_ENDPOINT_GONE` with „Diese Schnittstelle wurde abgeschaltet. Bitte aktualisiere
  den SC Extractor auf die neueste Version.", before authentication. **Restarts:** ingest only
  (~30 s of ingest outage; the web is unaffected).
- **Precondition:** S13 verified (2.10.0 is Latest).
- **Command:**

  ```bash
  env_set IRI_INGEST_LEGACY_ENDPOINTS_ENABLED false
  sudo -u deploy /var/iri/code/scripts/render-env-d.py \
    --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
  ${UCTL} restart ingest.service
  ${UPOD} exec ingest printenv APP_INGEST_LEGACYENDPOINTS_ENABLED
  ```

- **Expected:** `1`; the restart returns healthy; `false`.
- **Verify** (off the host): `curl -s -X POST -o /dev/null -w '%{http_code}\n' https://ingest.profit-base.online/v1/blueprint-preview`
  → `410`.
- **Watch:** panel 71 — switch `0`, 410s rising as members' extractors retry
  (`basetool_ingest_legacy_gone_total`); `IngestAuthFailureSpike` silent.
- **Rollback:** `env_set IRI_INGEST_LEGACY_ENDPOINTS_ENABLED true`, render, `${UCTL} restart
  ingest.service`. Before S15 that is all; **after S15** 2.9.1 also needs its ingest audience back —
  see §8, *Legacy flag back*.

### S15 — Provisioner apply

**PRODUCTION WRITE — needs @greluc's explicit per-action yes** — a realm write that changes what
every extractor token is; the approval must name it.

- **Target:** Keycloak realm `iri`. **Changes:** exactly S10's dry-run list. **Restarts:** nothing;
  existing extractor sessions keep working until their tokens are refreshed, then carry the new
  scope set (2.9.1's refreshes lose `aud=basetool-ingest` — it is on `410` anyway).
- **Precondition:** S14 verified; the session from S10 still valid (else repeat `config credentials`).
- **Command:**

  ```bash
  export KEYCLOAK_FRONTEND_CLIENT_SECRET="$(sed -n 's/^KEYCLOAK_FRONTEND_CLIENT_SECRET=//p' /var/iri/code/.env | tail -1)"
  python3 /root/kc-realm/provision-keycloak-realm.py --realm iri \
    --public-origin https://profit-base.online --kcadm-command "$KCADM" --frontend-client confidential --apply
  echo "exit=$?"
  python3 /root/kc-realm/provision-keycloak-realm.py --realm iri \
    --public-origin https://profit-base.online --kcadm-command "$KCADM" --frontend-client confidential
  echo "exit=$?"
  unset KEYCLOAK_FRONTEND_CLIENT_SECRET
  ```

  (both with `--external-clients /root/kc-realm/keycloak/none.json` if S10 used it).
- **Expected:** `[apply]`, `[verify] re-planning …`, `Applied. A second run reports no changes.`,
  `exit=0`; the second (dry) run: `The realm is in the production shape. Nothing to do.`, `exit=0`.
  `exit=3` with `[manual]` means a service-account role to assign by hand — none is expected.
- **Clean up** (same sitting):

  ```bash
  sudo -u iri podman exec keycloak rm -f /opt/keycloak/data/tmp/kcadm.config /opt/keycloak/data/tmp/truststore.p12
  rm -rf /root/kc-realm
  ```

  then delete `basetool-provisioner` in the Admin Console. `clients.before.json` holds client
  secrets; a later rollback starts from a fresh `kc get`.
- **Verify:**
  - R12 again: `client|basetool-sc-extractor|…|consent=t…`,
    `clientattr|basetool-sc-extractor|dpop.bound.access.tokens=true`, `scopeuse|basetool-sc-extractor|basic|default`,
    five `exchange.*` and `offline_access` as `optional`, **no** `extractor-ingest` /
    `extractor-ingest-only` row for the extractor; ten `scope|exchange.…` rows; `client|versekit`
    only with the real list; no `basetool-provisioner`.
  - **A real device login** with 2.10.0 on the owner's PC: the browser opens once, the device page
    warns and shows the code, the **consent page lists the five capabilities and the user code**
    (ADR-0228), the extractor asks for the installation's name, a blueprint send lands as a draft;
    „Verbundene Anwendungen" (member page) lists „SC Extractor – „‹label›"" with its capabilities.
- **Watch** (first hours): Exchange dashboard — *Relayed requests/min per client* shows
  `basetool-sc-extractor`, *Gateway refusals/hour by reason* without `scope_missing`,
  `client_version_unsupported` beyond stragglers, *Installations created/day* rising with the
  updates; `ExchangeDpopProofsFailing`, `IngestDpopReplayCacheFull`,
  `ExchangeDpopProofLimitSustained`, `ExchangeClientRefusalsSpike`, `ExchangeInstallationSurge`
  (a release day can trip it — read it, do not suspend reflexively), `ExchangeUnknownClient`,
  `ExchangeRelayFailing`, `ExchangeGateRefusing`, `ExchangeBudgetHigh`, `ApiUnknownClient` silent;
  Keycloak dashboard — no `LOGIN_ERROR` burst.
- **Owner decision, optional (its own yes):** S15 does not end the extractor sessions issued before
  it. What a pre-go-live refresh token yields after the apply is not tested here; to cut every one of
  them — a phished one included — set the client's not-before: Admin Console → *Clients →
  basetool-sc-extractor → Advanced → Revocation → Set to now*. Every member logs in once anyway with
  2.10.0.
- **Rollback:** §8, steps 2 and 4. It re-opens H1 only if `extractor-ingest` is restored — never do
  that.

### S16 — Registry entry `versekit`

**PRODUCTION WRITE — needs @greluc's explicit per-action yes** (admin UI).

- **Precondition:** VerseKit approved — public issue and the **merged** PR adding it to
  `docs/legal/approved-clients.md` with its capabilities; the Keycloak client exists (S15 with the
  real list; else S10/S15 again). Open to **all members at once**, no pilot group.
- **Values:** Client-ID `versekit`; Name `VerseKit`; Berechtigungen `exchange.connect`,
  `exchange.blueprints.read`, `exchange.blueprints.write`; Mindestversion **TO BE DECIDED** — the
  first sync-capable VerseKit release from #2089; contact URL from the approval issue (its privacy
  statement and security contact, `https`); limits empty.
- **Verify:** the row „Freigegeben"; the VerseKit author's first connection shows under
  *Installations created/day*; a member's new-connection notification arrives.
- **Watch:** as S15, per client `versekit`; plus `ExchangeRemoveSpike`, `ExchangeGuardStorm`
  (vault runbook *Suspending an exchange client*).
- **Rollback:** „Sperren"; for a bad release, the minimum version (vault *Blocking a bad exchange
  client release*).

### S17 — Widen VerseKit, one capability group at a time

**PRODUCTION WRITE — needs @greluc's explicit per-action yes, per group** (admin UI, „Bearbeiten",
confirm „Berechtigungen erweitern?"), each only after the previous group ran quietly and each
matching a capability the approval PR lists:

1. stock — `exchange.stock.read`, `exchange.stock.write` (raw materials and trade cargo);
2. org demand — `exchange.demand.read`;
3. hangar — `exchange.hangar.read`, `exchange.hangar.write`.

A member sees a new capability only after consenting to it at the next sign-in of VerseKit.
**Watch** per group: *Writes/day by resource and outcome*, *Removals/hour per client and resource*,
*Mass changes/day*, `ExchangeRemoveSpike`, `ExchangeGuardStorm`. **Rollback:** „Bearbeiten" and
untick — a capability removal is written to Redis before the commit.

### Follow-ups (not part of this sitting)

#2092 step 9: remove the legacy endpoint code. The Keycloak half it lists — `extractor-ingest` and
`extractor-ingest-only` off the extractor client only — is **already done by S15** (H1 withholds
both); `basetool-frontend` keeps `extractor-ingest` as a default scope, never touch it realm-wide.

---

## 8. Rollback — the global sequence of #2092, with commands

Fastest first; each is its own production write with its own yes.

1. **One client** — *Verbundene Anwendungen* → „Sperren" (seconds; mirror written before the
   commit). **All clients** — „Datenaustausch" → „Ausschalten" (`503 EXCHANGE_DISABLED` for every
   client). Both leave the members' data and connections in place.
2. **Keycloak client disable** — stops new tokens. Admin Console via the SSH tunnel: *Clients →
   ‹client› → Enabled* off. Or with a provisioner session (S10's first block):
   `kc update clients/$(kc get clients -r iri -q clientId=versekit --fields id --format csv --noquotes) -r iri -s enabled=false`.
   **The provisioner pins `enabled: true`**: a later provisioner run re-enables it unless the client
   is first taken out of `external-clients.json` (a third-party client) — for the extractor there is
   no such switch.
3. **Application rollback** — `gh workflow run promote.yml -f version=1.12.0`, lock-step.
   `V246`–`V257` stay (Flyway does not roll back; all additive). What else goes back with it:
   Redis to 384 MB in 512 MB (the previous units; redis restarts again — check R6's figure first, a
   Redis above 384 MB refuses writes under `noeviction`); the SPI jar (the disconnect extension
   answers `404`, which the backend of 1.12.0 does not call anyway); the terms text (members
   re-consent **again**, back to the old text); the gateway loses `/exchange/v1` (`404`), so 2.10.0
   stops, and 1.12.0's `/v1` ignores `IRI_INGEST_LEGACY_ENDPOINTS_ENABLED` and answers again — but
   after S15 no extractor token carries `aud=basetool-ingest` (step 4). After S7–S8 the app floor
   stays 17 while the server lacks the app's new routes — lower it first (S8's rollback) and accept
   that v17's new screens fail. S3's ACL, S4's `.env` line and S9's marking flag are harmless to
   1.12.0.
4. **Legacy flag back** (the extractor migration fails): `env_set IRI_INGEST_LEGACY_ENDPOINTS_ENABLED
   true`, render, `${UCTL} restart ingest.service`. **After S15** 2.9.1 additionally needs
   `extractor-ingest-only` back as a default scope of `basetool-sc-extractor` — **never**
   `extractor-ingest`, which stamps `aud=basetool-backend` and is H1 itself. With a provisioner
   session (untested):

   ```bash
   CID=$(kc get clients -r iri -q clientId=basetool-sc-extractor --fields id --format csv --noquotes)
   SID=$(kc get client-scopes -r iri --fields id,name --format csv --noquotes | awk -F, '$2=="extractor-ingest-only"{print $1}')
   kc update clients/$CID/default-client-scopes/$SID -r iri
   ```

   Consent and DPoP stay on (2.9.1 already binds its tokens, REQ-INGEST-012 — **not verified**
   against the consent page). The next provisioner run removes the scope again. Members then use
   2.9.1 until 2.10.0 is fixed; unpublish nothing — mark 2.10.0 as a pre-release instead if it must
   stop spreading.
5. **Data** — a member undoes one client's changes from the journal on „Verbundene Anwendungen";
   an admin undoes a client for every member since a point in time with the bulk undo on the admin
   page (REQ-XCH-034, which suspends the client first; vault *Suspending an exchange client*).
   Materialbörse offers a sync removed are not restored.
6. **Marking and the app floor** — S9's and S8's rollbacks; marked rows stay marked.
7. **What stays**: the Redis ACL (S3) and — except under step 3 — the Redis size.

---

## 9. Members

In German, in the forum (vault *Announcing a release*), in three posts:

1. **Before S6** — the outage window; **once**, every member confirms the changed terms of use at the
   next sign-in (only with §4.1); until a member has, their SC Extractor pauses; the Android app
   may show errors until restarted after confirming.
2. **With S7–S9** — the new app version, then „Update erforderlich" for older builds after the
   floor rises (about one minute of outage), then „gestohlen" in „Mein Lager".
3. **With S13–S15** — SC Extractor 2.10.0 is required; old versions show the update hint; after the
   update the first send opens the browser **once**, with a consent page listing what the extractor
   may do and the code to compare, and asks for a name for this PC; one new login, nothing else.
   Later, with S16: VerseKit can be connected under „Verbundene Anwendungen".

---

## 10. Gaps and contradictions found while writing this (2026-09-28)

1. **The terms change and the privacy notice are not built.** #2092 expects both „in the go-live
   release", REQ-SEC-027's callout says the clause changes at the go-live, REQ-XCH-002's third box is
   open — but `terms.list_4_1_5` is unchanged since v1.12.0 in all three bundles, no `privacy.*` key
   mentions approved clients, and no PR is open. A release cut today ships the exchange without them.
2. **„Enlarge Redis before the release" cannot be a separate step.** ADR-0221 and REQ-OPS-018 place
   the resize before the first exchange release, but it lives in the unit, and the next release is
   both the resize and the exchange release. Handled here by landing it in S6 with the exchange
   dormant. A separate earlier resize would need a hand-made drop-in (drift) or a release cut
   without the exchange, which `main` cannot produce.
3. **The coupled sequence's „server release" and the exchange's „release" are the same release** —
   everything since v1.12.0 is on `main` together.
4. **#2092's body and the extractor comment disagree on the order.** The body applies the
   provisioner (its step 4) before the registry entry and the switch-off; the comment — H1 — after
   the switch-off. This runbook follows the comment, which leaves a window (S13–S15) where 2.10.0
   cannot sync and a shorter one (S14–S15) where no extractor can; the provisioner has no way to give
   the extractor its exchange scopes without withholding the ingest scopes in the same run.
5. **„Redis size and ACL stay" is not true under an application rollback.** A promotion of 1.12.0
   restores its units: Redis goes back to 384 MB / 512 MB and restarts.
6. **`INGEST_KEYCLOAK_SETUP.md`'s provisioner procedure fails as written.** It copies only the two
   scripts; the realm provisioner reads `keycloak/external-clients.json` beside itself and stops with
   `cannot read the third-party client list` (reproduced 2026-09-28). Corrected there in the same
   change.
7. **The extractor's registry name cannot contain „Basetool".** The registry refuses it (L6), while
   the Keycloak client's name is „Basetool SC Extractor" — so the consent page and the member page
   name the same client differently.
8. **A disabled Keycloak client does not stay disabled.** The provisioner converges `enabled: true`
   on every client it manages; the rollback's „Keycloak client disable" is undone by the next run.
9. **The kcadm truststore step is untested** since internal TLS step 3 (v1.12.0) — S10 is its first
   production run.
10. **Sessions issued before S15 are not ended by it** — see S15's optional not-before; whether a
    pre-go-live extractor refresh token still yields `aud=basetool-backend` after the scope change is
    untested.
11. **Minor:** `INGEST_KEYCLOAK_SETUP.md` → *Configured state* still says the extractor „still carries"
    the loopback redirect URIs and the app both ingest scopes, „removed on the provisioner's next
    production apply", while the 2026-09-25 APPSEC-07 dry run planned only the frontend's type — R12
    settles it. The unit limits sum to 13 968 MiB while ADR-0221 records 14 512 MiB. No environment
    variable the new code reads is missing from the `env.d` templates: the ones not in a template
    (`APP_EXCHANGE_MIRROR_RECONCILE_INTERVAL`, `APP_EXCHANGE_CONNECTED_APPS_WEB_CLIENT_IDS`,
    `APP_INGEST_MAX_HANDOFF_BYTES`, `APP_INGEST_MAX_HANDOFFS_PER_SUBJECT`) all have defaults.
