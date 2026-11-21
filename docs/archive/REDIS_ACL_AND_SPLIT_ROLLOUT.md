> **Archived 2026-11-21.** Doc type: Historical record — frozen, kept as a record and no longer updated. Commands and states below describe the hosts and the code as they were.
>
> **Current truth:** [`deployment.md` → *The Redis ACL*](../deployment.md#the-redis-acl). Index of the archive: [`README.md`](README.md).

# Redis: the per-service ACL rollout and the session-store split

The two one-time procedures that took production Redis from one instance authenticated as `default`
to one ACL user per service (REQ-SEC-068, production 2026-09-25) and then to two instances
(ADR-0388, testing and production with v1.26.0, 2026-11-18). Both were part of
[`deployment.md`](../deployment.md) until they had run on every host.

## Cut-over: the session store gets its own instance (ADR-0388, needs the owner's yes)

The release that carries ADR-0388's second instance retires the `redis` and `redis-exporter` units
and starts `redis-session`, `redis-shared` and an exporter for each. `redis-session` keeps
`/var/lib/basetool/redis`, so the sessions stay and nobody is signed out; it loads
`/etc/basetool/redis/session-users.acl` and leaves `/var/lib/basetool/redis/users.acl` untouched for
a rollback. `redis-shared` starts empty in `/var/lib/basetool/redis-shared` and loads
`/etc/basetool/redis/shared-users.acl`. Both ACL files live outside the data directories, which
each container owns. Every step below is a production write; run them in one sitting with
`basetool-deploy.timer` stopped.

**Done on testing and production with v1.26.0** ([release runbook](RELEASE_1.26.0_PRODUCTION_RUNBOOK.md)
§6): what stays open is step 8's deletion of the old `users.acl`, and the *Rollback* below. Steps 0–7
stay for a host that still runs the single `redis` unit.

0. **Read first: the data fits the session store** (read-only, no approval needed).
   `redis-session` loads the whole of today's AOF — the exchange and ingest keys included, until
   step 6 removes them — under `maxmemory 384mb` with `noeviction`, and refuses every write,
   logins included, while it is over:

   ```bash
   ${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin INFO memory' \
     | grep -E '^used_memory:'   # must be below 300000000 (~286 MiB); above it, stop
   ```

1. **Three new passwords into `basetool.env`**, generated on the host. The running release reads
   none of them, so this step is inert until the deploy:

   ```bash
   cd /
   cp -p /etc/basetool/basetool.env /etc/basetool/basetool.env.backup-$(date +%Y%m%d-%H%M%S)
   for v in REDIS_SESSION_PASSWORD REDIS_SESSION_EXPORTER_PASSWORD REDIS_SESSION_FRONTEND_PASSWORD; do
     grep -q "^${v}=" /etc/basetool/basetool.env || \
       printf '%s=%s\n' "$v" "$(openssl rand -base64 36 | tr -d '/+=\n')" >> /etc/basetool/basetool.env
   done
   grep -c -E '^REDIS_SESSION_(EXPORTER_|FRONTEND_)?PASSWORD=' /etc/basetool/basetool.env   # 3
   ```

2. **The role** (from WSL): `ansible-playbook site.yml --limit production --tags
   directories,selinux,scripts --check --diff`, then without `--check --diff`. It creates
   `/var/lib/basetool/redis-shared` owned by the container's user and the root-owned
   `/etc/basetool/redis`, and installs both templates; without the data directory `redis-shared`
   dies on its first write and the health gate rolls the deploy back.
3. **Render both ACL files** as new files, before the units that mount them exist. No `ACL LOAD`:
   the running `redis` keeps its own file.

   ```bash
   cd /
   stat -c '%U:%G %a' /etc/basetool/redis   # root:root 755, from step 2
   for pair in redis-session-users.acl.tmpl:/etc/basetool/redis/session-users.acl \
               redis-shared-users.acl.tmpl:/etc/basetool/redis/shared-users.acl; do
     /opt/basetool/scripts/render-redis-acl.py --env /etc/basetool/basetool.env \
       --template "/opt/basetool/scripts/${pair%%:*}" --out "${pair#*:}.new" || break
     grep -c '^user default ' "${pair#*:}.new"   # must be 1
     grep -c '>' "${pair#*:}.new"                 # must be 0: hashes only
     cat "${pair#*:}.new" > "${pair#*:}" && rm "${pair#*:}.new"
     restorecon -F "${pair#*:}"
     stat -c '%U:%G %a' "${pair#*:}"              # root:root 644
   done
   ```
4. **Deploy the release.** Until the backend's reconcile has rewritten the registry and the
   revocations into `redis-shared` (at most 60 s) the exchange answers `503 REGISTRY_UNAVAILABLE`;
   quotas, budgets, idempotency answers and pending handoffs start empty.
5. **Verify**: `check-conformance.py --only redis-requires-auth` and `--only
   redis-default-user-off` pass for both instances, `--only redis-session-isolated` finds only
   `redis-session`, `frontend` and `redis-session-exporter` on `net-redis-session`; `ACL
   USERS` in `redis-session` lists `default`, `admin`, `monitoring` and `basetool-frontend` only;
   `redis_up` is 1 for `redis="session"` and `redis="shared"`; a signed-in browser stays signed in,
   a mission page receives live updates, a refinery import reaches its review form, and an exchange
   call succeeds a minute after the deploy.
6. **Delete what moved out of the session store**, as `admin`, without printing a key:

   ```bash
   ${UPOD} exec redis-session sh -c 'for p in "exchange:*" "ingest:*"; do
     REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin --scan --pattern "$p" \
       | xargs -r -n 200 env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin UNLINK >/dev/null
   done
   REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin --scan --pattern "*:*" \
     | grep -c -v "^basetool:session:"'   # 0
   ```

7. **Take a snapshot now**: `systemctl start basetool-backup.service`, and check its log names
   both `session-users.acl` and `shared-users.acl`. Until a snapshot carries them, the Sunday
   restore drill reads the last one from before the split, misses both ACLs and fires
   `RestoreDrillArtifactNotRestorable`.
8. Start `basetool-deploy.timer` again. Once no rollback to a release before the split is wanted,
   delete `/var/lib/basetool/redis/users.acl` and its `users.acl.backup-*` copies.

**Rollback**: an application rollback to the release before the split brings back the `redis` unit
on `/var/lib/basetool/redis` with its kept `users.acl`. The sessions survive; the exchange answers
`503 REGISTRY_UNAVAILABLE` until the backend's reconcile has rewritten the registry, and
`/var/lib/basetool/redis-shared` stays unused until the split is deployed again. Snapshots from
after the split no longer carry `users.acl`; a host restored for a rollback takes it from the last
snapshot before the cut-over.

## Rollout: one ACL user per service (needs the owner's yes)

On a host that runs the two instances of ADR-0388, run each step below against `redis-session` and
`redis-shared` with that instance's template, ACL file and exporter; the commands name the single
instance the rollout was written for.

> [!note] Production: steps 1–5 done on 2026-09-25
> Step 1 with the 1.11.0 role run (~11:5x UTC), steps 2–5 at ~15:47–15:51 UTC in one sitting with
> the deploy timer stopped; the render went through `.new` + `cat` (inode kept) and the first
> `ACL LOAD` authenticated as `default`. The procedure stays for the testing host and a rebuilt one.

Merging and deploying REQ-SEC-068 changes no credential. The deploy restarts `redis` once — its
unit gained `--notify-keyspace-events Egx` and the credential-free health probe — and every
application still authenticates as `default`. The rollout is the owner's, in this order; each step
can be stopped and rolled back on its own.

1. **Install the renderer** (from WSL): `ansible-playbook site.yml --limit production --tags scripts
   --check --diff`, then without `--check --diff`. Verify
   `ls -l /opt/basetool/scripts/render-redis-acl.py /opt/basetool/scripts/redis-*-users.acl.tmpl`.

> [!warning] Steps 2–4 are one sitting, with the deploy timer stopped *(corrected 2026-09-25)*
> Step 2 is **not** inert. The env templates pick each service's password with
> `REDIS_PASSWORD=${REDIS_<SVC>_PASSWORD:-${REDIS_PASSWORD…}}` — whether or not its
> `REDIS_<SVC>_USERNAME` is set. So from the moment the three passwords are in `basetool.env`, **any**
> `env.d/` render hands each application its new password with no username: a password-only `AUTH`
> as `default` with the wrong password, `WRONGPASS`, and the service goes unhealthy on its next
> restart. `deploy.sh` re-renders `env.d/` on every config change, so a deploy tick between step 2
> and step 4 is exactly that render. Hence: `systemctl stop basetool-deploy.timer` before step 2, run
> steps 2–4 back to back, and `systemctl start basetool-deploy.timer` only after step 4 verified (or after
> a rollback). And never add the three passwords before the release carrying REQ-SEC-068 (1.11.0)
> is live.

2. **Three new passwords into `basetool.env`**, generated on the host so they never cross a terminal:

   ```bash
   cd /
   cp -p /etc/basetool/basetool.env /etc/basetool/basetool.env.backup-$(date +%Y%m%d-%H%M%S)
   for v in REDIS_FRONTEND_PASSWORD REDIS_BACKEND_PASSWORD REDIS_INGEST_PASSWORD; do
     grep -q "^${v}=" /etc/basetool/basetool.env || \
       printf '%s=%s\n' "$v" "$(openssl rand -base64 36 | tr -d '/+=\n')" >> /etc/basetool/basetool.env
   done
   grep -c -E '^REDIS_(FRONTEND|BACKEND|INGEST)_PASSWORD=' /etc/basetool/basetool.env   # 3
   ```

3. **Render with `default` still on, and load it** — append `REDIS_DEFAULT_USER=on` to
   `basetool.env` (the renderer refuses while it is unset), then the block above, first load without
   `--user admin`. Nothing changes for the applications yet; `admin` and the three service users now exist.
   Before rendering, confirm that `basetool.env`'s `REDIS_EXPORTER_PASSWORD` is the password the hand-written
   file gives `monitoring` — the render re-derives that user's hash from `basetool.env`, and a mismatch takes
   `redis-exporter` down (`redis_up == 0`). Compare digests, never the values:
   `grep '^user monitoring ' /var/lib/basetool/redis/users.acl | grep -o '>[^ ]*' | cut -c2- | tr -d '\n' | sha256sum`
   against `sed -n 's/^REDIS_EXPORTER_PASSWORD=//p' /etc/basetool/basetool.env | tr -d '"\n' | sha256sum` —
   the two sums must be equal; if they differ, stop and settle which one is right first.
   Check: `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL
   USERS'` lists all six.
4. **Move the applications**, one at a time, watching each come back healthy:

   ```bash
   printf '%s\n' REDIS_BACKEND_USERNAME=basetool-backend REDIS_INGEST_USERNAME=basetool-ingest \
     REDIS_FRONTEND_USERNAME=basetool-frontend >> /etc/basetool/basetool.env
   sudo -u deploy /opt/basetool/scripts/render-env-d.py \
     --env /etc/basetool/basetool.env --templates /opt/basetool/quadlet/env.d --out /etc/basetool/env.d
   ${UCTL} restart backend.service     # live sync + notifications: basetool_redis_fanout_subscribed == 1
   ${UCTL} restart ingest.service      # a desktop import reaches "Import-Link" and opens
   ${UCTL} restart frontend.service    # nobody is signed out: the sessions are in Redis
   ```

   Verify: `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin CLIENT
   LIST' | grep -o 'user=[^ ]*' | sort | uniq -c` shows the three service users and no application
   on `default`; `RedisAclDenials` stays silent; log in, open a mission (live sync), run one
   refinery import. On 1.11.0, `ACL LOG` shows the frontend's refused `config|get` from its
   restart (see the note above; verified gone on production after the v1.12.0 deploy, 2026-09-25); nothing else.
5. **Switch `default` off**: set `REDIS_DEFAULT_USER=off` in `basetool.env`, render, `ACL LOAD` as
   `admin`. Verify `${UPOD} exec redis sh -c 'redis-cli ping'` answers `NOAUTH`,
   `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'` (password-only, i.e.
   `default`) answers `WRONGPASS … or user is disabled`, `${UPOD} healthcheck run redis` is healthy,
   and `redis-exporter` still scrapes (`redis_up == 1`); `check-conformance.py --only
   redis-default-user-off` passes.

**Rollback**, from any step: step 5 — set `REDIS_DEFAULT_USER=on`, render,
`ACL LOAD`. Step 4 — delete the three `REDIS_*_USERNAME` lines **and** the three
`REDIS_{FRONTEND,BACKEND,INGEST}_PASSWORD` lines, render `env.d/`, restart the three services: they
are back on `default` with the shared password — except ingest from the release after 1.12.0 on,
which then refuses to start; there, keep `REDIS_INGEST_USERNAME` and `REDIS_INGEST_PASSWORD` (and
its ACL user) or roll the release back first. Step 3 — `cat` the `users.acl.backup-*` back into
`users.acl` (keeping the inode, as above) and `ACL LOAD` (authenticating as `default`); step 2 —
delete the three password lines. *(Corrected 2026-09-25: this used to say the passwords may stay in
`basetool.env` because nothing reads them without the usernames — the templates read them either way, see
the warning above.)* **A release rollback** to a version before REQ-SEC-068 (1.10.0 or older) needs
`default` **on** first: its health check and its applications authenticate as `default`.
