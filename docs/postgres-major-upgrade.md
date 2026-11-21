# PostgreSQL major upgrade — operator runbook

> **Doc type:** Runbook — living, kept in sync with `main`. Last reviewed: 2026-11-21. The binding
> requirements are [`REQ-OPS-063`](specs/deployment-delivery.md#req-ops-063--a-postgresql-major-moves-beside-the-untouched-old-cluster)
> and the operator gate in front of it, [`REQ-OPS-006`](specs/deployment-delivery.md#req-ops-006--stateful-infra-changes-are-operator-gated);
> the decision record is [ADR-0399](adr/0399-a-postgresql-major-moves-by-dump-and-restore-beside-the-old-cluster.md).
>
> **Runtime:** written for the rootless Podman host ([`deployment.md` → *The host*](deployment.md#the-host)).
> Run every command as root from `/` with the shell conventions of
> [`deployment.md`](deployment.md#shell-conventions-used-below).

> [!danger] For AI agents: reading the production host is free, writing to it is gated
> Every command below that changes a host — a stop, a `mv`, a container, a deploy — is a **write**
> under the production-host access rule in the repository `AGENTS.md` and needs an explicit
> per-action yes from @greluc. This runbook is documentation, never approval.

## What this covers

Moving the two PostgreSQL clusters, `db-backend` (`krt_basetool`) and `db-keycloak` (`keycloak`), from
one major version to the next, for example 18 to 19. A new digest behind the same tag is not a major
upgrade; `deploy.sh` applies it on its own (REQ-OPS-006).

The method is **dump and restore into a fresh cluster beside the old data directory**:

- each cluster lives in `/var/lib/basetool/<db>/pgdata` (the unit mounts `/var/lib/basetool/<db>` at
  `/var/lib/postgresql/data` and sets `PGDATA=/var/lib/postgresql/data/pgdata`);
- the old cluster is renamed to `pgdata-<old major>` and **not touched again** until the new one is
  verified and has been backed up and drilled;
- the new major initialises a fresh `pgdata` in the same mount, and the dump of the old cluster is
  restored into it **before** any application, Keycloak or exporter can reach it — the backend's
  Flyway would otherwise build an empty schema there, and Keycloak an empty realm;
- per-table row counts, sequence values and constraint and index validity are compared between the
  two clusters, and the release is applied only when they agree.

The two clusters can move in one window or in two. Move `db-keycloak` only once Keycloak lists the
new major as supported; until then move `db-backend` alone (see *The repository change*).

## Before the window

### 1. Decide that the new major is ready

All of these hold, or the upgrade waits:

- [ ] The new major has its first minor release (`x.1`), unless a fix in `x.0` is needed.
- [ ] The release notes' *Migration to Version N* section was read against this stack: the `Exec=`
  flags of `quadlet/systemd/db-*.container`, the roles and their settings, the extensions
  (`pg_trgm`, `btree_gist`, `pg_stat_statements`) and the SQL in the Flyway migrations.
- [ ] [Keycloak's database page](https://www.keycloak.org/server/db) lists the major — for
  `db-keycloak` only.
- [ ] Flyway, pgJDBC, Hibernate and `postgres_exporter` know of no incompatibility. Flyway logs
  `… newer than the version Flyway has been verified with` on every start until it lists the major;
  that warning alone is no blocker.
- [ ] The full CI run of the repository change below is green, the backend's Testcontainers suite
  and the E2E stack included — both run on the pinned image.

### 2. The repository change

One pull request that changes the major and nothing else, shipped as a **release of its own**: the
stateful-infra gate holds back the whole release until `--force` (REQ-OPS-006), and a rollback
should take back only the database.

`git grep -n -e 'postgres:<old>' -e 'postgresql:<old>' -e 'PostgreSQL <old>'` finds the places.
The ones that matter:

| Where | What |
|---|---|
| `docker-compose.yml` (`x-db-backend`, `x-db-keycloak`), `docker-compose.sandbox.yml` | the image tag and its digest |
| `quadlet/systemd/db-backend.container`, `db-keycloak.container` | regenerated with `python scripts/generate-quadlet.py`, never edited |
| `test-support/…/TestImages.java` (`POSTGRES`), `backend/src/test/resources/application-test.yml` | the test image and the `jdbc:tc:postgresql:<tag>` URL |
| `PinnedPostgresImageTest`, `TestImagesTest`, `V383MaterialReceiptMigrationTest` | they assert the tag and the server's major |
| `.github/scripts/dependabot_compose_followup.py` | the tag it expects |
| `README.md`, arc42 §2, `backend/src/main/resources/db/migration/README.md`, `CONTRIBUTING.md`, `AGENTS.md` | the major named in prose |

`PGDATA` stays set explicitly in both `env.d` templates. The images of PostgreSQL 18 and later
default to `/var/lib/postgresql/<major>/docker`, which this layout does not use.

Moving `db-backend` alone gives the two compose anchors and the two units different tags, so
`TestImages` needs a constant per cluster and `TestImagesTest` has to stop expecting two equal
lines.

### 3. Rehearse

1. **On the testing host, end to end.** Promote the release there
   (`gh workflow run promote-testing.yml -f version=<release>`), run the whole window below on it,
   then the rollback *before reopening* once and the window again. The testing host's deploy
   resolves `:testing`, so its apply is `deploy.sh --tag testing --force`. Note how long the dump,
   the restore and the analyze took.
2. **On production, a restore drill into the new major** with the latest real backup. It starts a
   throwaway container and touches neither live cluster:

   ```bash
   cd / && sudo -u deploy env BASETOOL_DRILL_IMAGE='docker.io/postgres:<new>-alpine@sha256:<digest>' \
     /opt/basetool/scripts/restore-drill.sh
   ```

   It must end with `RESTORE DRILL PASSED`. Its duration is the restore time to expect in the window.

### 4. Take stock on production (read-only)

```bash
cd /
UPOD="sudo -u basetool podman"
for c in db-backend db-keycloak; do
  echo "== ${c}"
  ${UPOD} exec -i "${c}" sh -c 'psql -X -q -tA -F " " -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT version();
SELECT 'size', pg_size_pretty(pg_database_size(current_database()));
SELECT 'locale', datlocprovider, datcollate, datctype, pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database();
SELECT 'checksums', current_setting('data_checksums');
SELECT 'role', rolname, rolcanlogin, rolsuper FROM pg_roles WHERE rolname !~ '^pg_' ORDER BY rolname;
SELECT 'setting', coalesce(r.rolname, '-'), coalesce(d.datname, '-'), s.setconfig FROM pg_db_role_setting s LEFT JOIN pg_roles r ON r.oid = s.setrole LEFT JOIN pg_database d ON d.oid = s.setdatabase;
SELECT 'extension', extname, extversion FROM pg_extension ORDER BY extname;
SQL
done
du -sh /var/lib/basetool/db-backend/pgdata /var/lib/basetool/db-keycloak/pgdata
df -h /var/lib/basetool
```

- **Roles:** a dump carries no roles. Every role besides the superuser of `POSTGRES_USER` /
  `KC_POSTGRES_USER` is recreated in step 8 — on this stack the exporters' `monitoring` role.
- **Settings:** a role or database setting (`setting` lines) is applied again by hand in step 8,
  and only when the new major still knows the parameter.
- **Locale:** the new cluster is initialised by the image's `initdb` with `LANG=en_US.utf8`, which
  gives `libc` with `en_US.utf8`. A cluster with another provider or locale needs the same values in
  `POSTGRES_INITDB_ARGS` for the container of step 7.
- **Disk:** the old cluster stays beside the new one, and the dumps lie in the working directory,
  so `/var/lib/basetool` needs room for about twice both clusters. `HostDiskWarning` fires at 80 %.

## The window

Announce the downtime to the members first, and create a time-boxed silence for the host in
Alertmanager: `PostgresDown`, the application alerts and the blackbox probes fire while the stack is
down.

**0. Shell setup** — every later step uses these names:

```bash
cd /
BASETOOL_UID=$(id -u basetool)
UCTL="sudo -u basetool XDG_RUNTIME_DIR=/run/user/${BASETOOL_UID} systemctl --user"
UPOD="sudo -u basetool podman"
OLD_MAJOR=18
NEW_MAJOR=19
NEW_PG='docker.io/postgres:19-alpine@sha256:<digest>'
W="/var/lib/basetool/backup/pg-major-${NEW_MAJOR}"
CLUSTERS="db-backend db-keycloak"
sql() { ${UPOD} exec -i "$1" sh -c 'psql -X -q -v ON_ERROR_STOP=1 -tA -F " " -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; }
FINGERPRINT="
SELECT 'rows ' || table_schema || '.' || table_name, (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text FROM information_schema.tables WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY 1;
SELECT 'sequence ' || schemaname || '.' || sequencename, coalesce(last_value::text, '-') FROM pg_sequences ORDER BY 1;
SELECT 'invalid-constraints', count(*) FROM pg_constraint WHERE NOT convalidated;
SELECT 'invalid-indexes', count(*) FROM pg_index WHERE NOT indisvalid;
"
```

`NEW_PG` is the `Image=` line of `quadlet/systemd/db-backend.container` at the release tag
(`git show v<release>:quadlet/systemd/db-backend.container`), with the `docker.io/` prefix: rootless
Podman on this host refuses a short name. `CLUSTERS` names only `db-backend` when Keycloak stays.
`sql` reads its SQL from standard input and connects over the container's socket; `PGPORT` comes
from the container's environment.

**1. Promote, and let the gate hold it.** `gh workflow run promote.yml -f version=<release>`. The
next tick logs `CARVE-OUT: postgres/Keycloak image pin changed` in `/var/log/basetool/deploy.log`,
raises `DeployConfigBlocked` once and then skips the target quietly; the old release keeps running.

**2. Stop the timers and wait for a running job:**

```bash
systemctl stop basetool-deploy.timer basetool-backup.timer basetool-restore-drill.timer
flock /run/lock/basetool-deploy.lock true
```

**3. Take a fresh off-site backup and pull the new image:**

```bash
systemctl start basetool-backup.service        # oneshot: returns when the backup is done
tail -n 3 /var/log/basetool/backup.log         # must end with "backup complete"
${UPOD} pull "${NEW_PG}"
```

**4. Stop everything that talks to the databases.** The edge stays up and serves the maintenance
page; the exporters stay up and report `pg_up 0`.

```bash
${UCTL} stop frontend.service ingest.service backend.service keycloak.service
```

**5. Fingerprint and dump the old clusters:**

```bash
install -d -m 0700 "${W}"
for c in ${CLUSTERS}; do
  sql "${c}" <<<"${FINGERPRINT}" >"${W}/${c}.old"
  ${UPOD} exec "${c}" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"${W}/${c}.dump" \
    || echo "DUMP OF ${c} FAILED"
  echo "${c}: $(grep -c '^rows ' "${W}/${c}.old") tables, dump $(du -h "${W}/${c}.dump" | cut -f1)"
done
```

Stop on a `FAILED` line or an empty dump: nothing has changed yet, so step 4 is undone by
`${UCTL} start keycloak.service backend.service ingest.service frontend.service`.

**6. Stop the old clusters and set their data aside:**

```bash
for c in ${CLUSTERS}; do ${UCTL} stop "${c}.service"; done
for c in ${CLUSTERS}; do mv "/var/lib/basetool/${c}/pgdata" "/var/lib/basetool/${c}/pgdata-${OLD_MAJOR}"; done
```

A rename inside the mounted directory keeps the owner (container uid 70, host uid `100069`) and
the SELinux label; the old cluster's files are not opened.

**7. Initialise the new clusters** in throwaway containers that run as the units do — the same image,
uid, environment file and mount — but with no network, so nothing can connect:

```bash
for c in ${CLUSTERS}; do
  ${UPOD} run -d --name "${c}-upgrade" --user 70:70 --network none \
    --env-file "/etc/basetool/env.d/${c}.env" \
    -v "/var/lib/basetool/${c}:/var/lib/postgresql/data" "${NEW_PG}"
done
for c in ${CLUSTERS}; do
  until ${UPOD} exec "${c}-upgrade" pg_isready -q -h 127.0.0.1; do sleep 2; done
  sql "${c}-upgrade" <<<"SELECT version(); SELECT datlocprovider, datctype FROM pg_database WHERE datname = current_database();"
done
```

`pg_isready` asks over TCP on purpose: during `initdb` the image runs a temporary server on the socket
only, and only the final server listens on TCP. The locale must match step 4.

**8. Recreate the roles** the stock-taking found, before any data arrives — on this stack
`monitoring`, whose password is `PG_EXPORTER_BACKEND_PASSWORD` / `PG_EXPORTER_KEYCLOAK_PASSWORD` in
`/etc/basetool/basetool.env`. `\password` keeps it out of the shell history and the server log:

```bash
${UPOD} exec -it db-backend-upgrade sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
#   CREATE ROLE monitoring LOGIN;  GRANT pg_monitor TO monitoring;  \password monitoring
${UPOD} exec -it db-keycloak-upgrade sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
#   the same, with PG_EXPORTER_KEYCLOAK_PASSWORD
```

Re-apply any `setting` line from step 4 here with `ALTER ROLE … SET` / `ALTER DATABASE … SET`.

**9. Restore, then collect statistics** — a restore carries no planner statistics:

```bash
for c in ${CLUSTERS}; do
  ${UPOD} exec -i "${c}-upgrade" sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error' \
    <"${W}/${c}.dump" && echo "${c}: restored" || echo "RESTORE OF ${c} FAILED"
  ${UPOD} exec "${c}-upgrade" sh -c 'vacuumdb -U "$POSTGRES_USER" -d "$POSTGRES_DB" --analyze-only --quiet'
done
```

A `FAILED` line is a no-go: go to *Rollback before reopening*.

**10. Verify — the go/no-go:**

```bash
for c in ${CLUSTERS}; do
  sql "${c}-upgrade" <<<"${FINGERPRINT}" >"${W}/${c}.new"
  diff "${W}/${c}.old" "${W}/${c}.new" && echo "${c}: identical ($(grep -c '^rows ' "${W}/${c}.new") tables)"
  sql "${c}-upgrade" <<<"SELECT 'extension', extname, extversion FROM pg_extension ORDER BY extname;"
done
```

**Go** only when `diff` prints nothing for every cluster, both `invalid-…` lines read `0` as they did
on the old cluster, and every extension of step 4 is there. The extension versions may be higher:
the restore creates each extension in the new major's default version. Anything else is a
**no-go**. This is the last point at which nothing is lost by going back: from step 12 on, members
write to the new cluster.

**11. Stop the throwaway containers.** PostgreSQL shuts down cleanly on the image's stop signal:

```bash
for c in ${CLUSTERS}; do ${UPOD} stop --time 60 "${c}-upgrade" && ${UPOD} rm "${c}-upgrade"; done
```

**12. Apply the release.** It installs the units with the new image and starts the whole stack on
the restored clusters:

```bash
cd / && sudo -u deploy /opt/basetool/scripts/deploy.sh --force
```

It must log `deploy successful`. If its health gate fails, `deploy.sh` restores the previous release,
whose units carry the old major and cannot start on the new data directories: go to *Rollback
before reopening*.

**13. Check, then end the window:**

```bash
sql db-backend <<<"SELECT version();"
${UPOD} logs --since 15m backend 2>&1 | grep -E 'Flyway|Successfully validated|ERROR' | tail -n 5
```

- the backend validated every migration and applied none;
- signing in through the browser works, and a list page loads;
- `pg_up` is `1` for both `postgres-*` jobs in Grafana — `0` with `role "monitoring" does not exist`
  means step 8 missed a cluster.

Then remove the silence, start the timers, and prove the backup chain on the new major:

```bash
systemctl start basetool-deploy.timer basetool-backup.timer basetool-restore-drill.timer
systemctl start basetool-backup.service && tail -n 1 /var/log/basetool/backup.log
systemctl start basetool-restore-drill.service && grep 'RESTORE DRILL' /var/log/basetool/restore-drill.log | tail -n 1
```

The drill now restores into the new major, because it uses `db-backend`'s pinned image.

## Rollback before reopening

From any step up to a failed step 12, while no member has written to the new cluster:

```bash
for c in ${CLUSTERS}; do ${UPOD} rm -f "${c}-upgrade" 2>/dev/null; ${UCTL} stop "${c}.service"; done
for c in ${CLUSTERS}; do
  [ -d "/var/lib/basetool/${c}/pgdata" ] && mv "/var/lib/basetool/${c}/pgdata" "/var/lib/basetool/${c}/pgdata-failed-${NEW_MAJOR}"
  mv "/var/lib/basetool/${c}/pgdata-${OLD_MAJOR}" "/var/lib/basetool/${c}/pgdata"
done
```

Then bring the old release back:

- **Step 12 never ran:** the units still carry the old major.
  `${UCTL} start db-backend.service db-keycloak.service keycloak.service backend.service ingest.service frontend.service`,
  then promote the previous release again (`gh workflow run promote.yml -f version=<previous>`) so
  `:stable` no longer names the gated one, and start the timers.
- **Step 12 failed its gate:** `deploy.sh` has already put the previous release's units back.
  `cd / && sudo -u deploy /opt/basetool/scripts/deploy.sh --reapply`, then promote the previous
  release and start the timers as above.

Keep `pgdata-failed-<new>` and `${W}` until the cause is understood.

## After members have written to the new cluster

Going back to `pgdata-<old>` then loses every write since step 12, and the old major's `pg_dump`
refuses to dump a newer server. Fix forward. Only if the new major itself is unusable: take a dump
with the new major's `pg_dump` and restore it into a fresh cluster of the old major — PostgreSQL
does not promise that this works, so try it on the testing host first.

## Clean up

- **`${W}`** holds plaintext dumps with personal data: `rm -rf "${W}"` once the first backup on the
  new major has succeeded (step 13). The off-site backup keeps the encrypted copy.
- **`pgdata-<old>`** stays until the Sunday restore drill on the new major has passed and a week has
  gone by: `rm -rf /var/lib/basetool/db-backend/pgdata-<old> /var/lib/basetool/db-keycloak/pgdata-<old>`.
- The old image needs no removal by hand: the weekly cleanup prunes it once no container uses it and
  it is two weeks old (`BASETOOL_CLEANUP_IMAGE_UNTIL`).

## What looks different afterwards

- **Statistics start empty.** `pg_stat_statements` and the cumulative statistics restart, so the
  PostgreSQL dashboard's rates and the slow-query view begin again from zero.
- **Constraint text.** A major may print the same constraint differently: PostgreSQL 19 writes a
  restored `IN ('A', 'B')` check as `ANY (ARRAY[('A'::character varying)::text, …])` where 18 wrote
  `ANY ((ARRAY['A'::character varying, …])::text[])`. The meaning is the same; code that compares
  `pg_get_constraintdef` text sees a difference.
- **Extension versions** are the new major's defaults, without an `ALTER EXTENSION … UPDATE`.
- **Server defaults** of the new major apply wherever the unit sets nothing — its release notes list
  them (for 19: `log_lock_waits` on, JIT off, `lz4` TOAST compression).
- **Checksums** are on in a cluster that `initdb` of PostgreSQL 18 or later created, whatever the
  old cluster had.
