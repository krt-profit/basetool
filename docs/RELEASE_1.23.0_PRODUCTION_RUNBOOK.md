# Release 1.23.0 — production rollout runbook

> **Doc type:** Operator runbook for **one** release — the move of production (and, through
> `promote.yml`'s `sync-testing`, the testing host) from **v1.22.3 to v1.23.0**: the roles held in
> the Basetool (ADR-0355), the P4K catalogue version 2 (ADR-0335…0347), the Kartellbank on the
> exchange (ADR-0354) and the refinery orders on the exchange (#3174, ADR-0351). Written 2026-11-09
> from an audit of the 38 pull requests between `v1.22.3` and `87f6a5896`, plus #3174, and from
> read-only reads of production the same day. **Historical once 1.23.0 is live and §4 is done**:
> move it to `docs/archive/` then and let [`deployment.md`](deployment.md) stay the living truth.
>
> **Every step that writes to a host, to Keycloak or to the live app is a production write**: it
> waits for @greluc's explicit yes, in chat, to that exact command (AGENTS.md → *Production host
> access*). Nothing here is approval, and no write in this document has been executed.

Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are the ones in
[`deployment.md` → Shell conventions](deployment.md#shell-conventions-used-below).

---

## 0. What changes

| | Value |
|---|---|
| Range | `v1.22.3` (2026-11-05) to `87f6a5896`, 38 pull requests, plus #3174 |
| Roles | the four application roles come from `user_roles` only; login and roster sync write none; the provisioner deletes the realm roles `KRT Member`, `Bank Employee`, `Bank Management` and withholds the `roles` scope from `basetool-frontend` (ADR-0355, REQ-SEC-088). `Admin` stays in Keycloak only as the admin-OTP condition |
| Grafana | its login reads the client role `grafana-admin` of the `grafana` client, no longer the realm role `Admin` (REQ-OBS-008) |
| Keycloak Admin API | Keycloak 26.8.2 enforces an Admin API audience; `backend-service` needs the built-in `kc-admin-api` default scope (§1.2) |
| Exchange | bank resources (switch off by default, REQ-BXCH-006), refinery orders and store choices (`exchange.refinery.read/.write/.store`) |
| Flyway | 37, `V338`–`V374` (head before: `V337`). `V344` **drops** `personal_inventory_item.location_uex_id` and `location_type`; `V343` clears duplicate P4K GUIDs. Small tables; expected seconds |
| Stateful infra | `postgres:18-alpine` digest refresh on the same tag — not gated, recreates `db-backend` and `db-keycloak` in the release window. Keycloak unchanged (26.8.2) |
| Config bundle | `backend.env.tmpl` adds `APP_BOOTSTRAP_ADMIN_SUBS` and drops `APP_SECURITY_PARTIAL_ROLE_SCOPE_CLIENT_IDS`; `grafana.env.tmpl` changes the role path; Prometheus 3.16.0, redis-exporter 1.97.0; the Keycloak theme gains the refinery consent texts; new dashboard `12-p4k-catalogue`, new alert rules |
| Host scripts | none of `scripts/host-scripts.list` changed. Compared on 2026-11-09: production matches `v1.22.3`, `main` and #3174's head (§1.1) |
| New env vars | none required; `APP_BOOTSTRAP_ADMIN_SUBS` stays empty while an active admin exists |
| Expected maintenance page | about two to three minutes, once (databases, apps, Grafana) |
| Rollback | **not code-only**: v1.22.3 maps the columns `V344` drops and does not start on the migrated schema (`ddl-auto = validate`). A rollback is a restore of the §1.3 backup — see §5 |

## 1. Before the promotion

### 1.1 Host scripts

Mandatory for every promotion ([`deployment.md` → Before a promotion](deployment.md#before-a-promotion-the-host-scripts)).
Run on 2026-11-09 against `v1.22.3`, `origin/main` and #3174's head: all 14 files match. Repeat it
against the tag `v1.23.0` once it exists; a `<` line blocks the promotion.

### 1.2 Repair the roster sync on the running release

Since Keycloak 26.8.2 started on 2026-11-05 20:57 UTC, every scheduled user sync fails (backend log,
2026-11-06 to 2026-11-09):

```text
KeycloakService : Keycloak Admin API rejected the user sync with 401 UNAUTHORIZED: …
UserSyncService : No users fetched from Keycloak.
```

The last successful sync ran on 2026-11-05 04:00. `UserSyncStale` covers it. Disconnecting a
connected app reaches the same Admin API. Step 1 of the role switch
([`deployment.md` → Roles in the Basetool](deployment.md#roles-in-the-basetool-the-switch-and-its-rollback))
needs a working sync, and the provisioner that assigns the scope also deletes the realm roles, so it
must not run before the promotion. Assign the one scope by hand instead — exactly what the
provisioner does later, so its run reports nothing for it:

1. Admin Console → *Clients* → `backend-service` → *Client scopes* → *Add client scope* →
   `kc-admin-api` → *Add* → **Default**.
2. Basetool → admin user list → run the Keycloak user sync (`POST /api/v1/users/sync`).
3. Check (read-only, agent): the backend log shows `User sync finished`, not `401 UNAUTHORIZED`.

Rollback: remove the scope from `backend-service` again.

From here on, roles are changed only in the Basetool.

### 1.3 Backup, read-only checks and the role export

One block for the operator, from a PowerShell prompt; `sudo` asks for the password twice at most.
The files are in `D:\Coding\Agents\Temp\claude\release-1.23.0` (outside the repository); the
export holds user ids and role codes only.

```powershell
$h = 'greluc@<production host>'; $d = 'D:\Coding\Agents\Temp\claude\release-1.23.0'
ssh $h 'install -d -m 0700 ~/bt-1.23.0'
scp "$d\precheck.sql" "$d\role-assignments-export.sql" "${h}:bt-1.23.0/"
ssh -t $h 'cd /tmp && sudo systemctl start basetool-backup.service && sudo -u basetool XDG_RUNTIME_DIR=/run/user/994 podman exec -i db-backend sh -c "psql -qAt -U \$POSTGRES_USER -d \$POSTGRES_DB -p 15432 -f -" < ~/bt-1.23.0/precheck.sql > ~/bt-1.23.0/precheck.out && sudo -u basetool XDG_RUNTIME_DIR=/run/user/994 podman exec -i db-backend sh -c "psql -qAt -U \$POSTGRES_USER -d \$POSTGRES_DB -p 15432 -f -" < ~/bt-1.23.0/role-assignments-export.sql > ~/bt-1.23.0/roles.tsv && echo DONE'
scp "${h}:bt-1.23.0/precheck.out" "${h}:bt-1.23.0/roles.tsv" $d
ssh $h 'rm -rf ~/bt-1.23.0'
```

It writes a restic snapshot (the backup), runs two `READ ONLY` sessions and leaves nothing on the
host. Expected: `DONE`; `precheck.out` lists `flyway_head 337`, `active_admins` ≥ 1, the holder
count per role and every merged-away account that still holds a role (`merged_away_with_role`,
withdrawn in §3.4). Check the backup log (`/var/log/basetool/backup.log`) ends successfully.
**Keep `roles.tsv` until 1.23.0 is stable** — it is the input of the realm-role rollback.

## 2. Promotion

1. Merge #3174, run *Release · Prepare* with `1.23.0`, merge the `release/v1.23.0` PR; wait for
   `release-images.yml` on the tag.
2. §1.1 against `v1.23.0`.
3. `gh workflow run promote.yml -f version=1.23.0`, then approve on the `production` environment.

The next deploy tick applies it in one restart window. Check (read-only): `deploy.log` ends with
`deploy successful`; the backend logs `Successfully applied 37 migrations … now at version v374`;
the stack is healthy.

From this moment until §3.2, a new Grafana login is denied; open Grafana sessions keep working.

## 3. Right after the promotion

### 3.1 Bring the realm to the new shape

The kcadm session of [`keycloak/README.md` → Runbook](keycloak/README.md#runbook--a-kcadm-session-on-the-production-container),
with the provisioner of the tag `v1.23.0` (copy `provision-keycloak-realm.py`, `keycloak_kcadm.py` and
`scripts/keycloak/` to the host), dry run first. The `basetool-provisioner` client needs the
`kc-admin-api` default scope besides its two roles: without it the login succeeds and the first
read answers `HTTP 401 Unauthorized`.

```sh
python3 provision-keycloak-realm.py --realm basetool --public-origin https://profit-base.online --grafana-origin https://grafana.profit-base.online --kcadm-command "$KCADM" --frontend-client confidential
python3 provision-keycloak-realm.py --realm basetool --public-origin https://profit-base.online --grafana-origin https://grafana.profit-base.online --kcadm-command "$KCADM" --frontend-client confidential --apply
```

The dry run exits `2` and lists, besides what
[`deployment.md` step 6](deployment.md#roles-in-the-basetool-the-switch-and-its-rollback) names
(the three realm roles deleted, `roles` withheld from `basetool-frontend`, Grafana's `client roles`
mapper added and `realm roles` mapper removed, `view-realm` withdrawn from `backend-service` — under
`[manual]` when the withdrawal is refused): the three `exchange.refinery.*` and the three
`exchange.bank.*` client scopes with their audience mappers, all six as optional scopes of every
approved external client, and the `grafana-admin` client role where the realm lacks it. `kc-admin-api` on
`backend-service` is already in place from §1.2. Anything else in the plan: stop and compare.

Afterwards delete `basetool-provisioner` and the session file (README step 4).

### 3.2 Grafana admins

Admin Console → *Users* → each Grafana admin → *Role mapping* → *Assign role* → *Filter by clients*
→ `grafana-admin`.

The deploy renders `env.d/grafana.env` but does not restart Grafana for it, so the container keeps
the old role path until it is restarted. Check the rendered file, then restart (root, from `/`):

```sh
grep '^GF_AUTH_GENERIC_OAUTH_ROLE_ATTRIBUTE_PATH=' /etc/basetool/env.d/grafana.env
sudo -u basetool XDG_RUNTIME_DIR=/run/user/994 systemctl --user restart grafana.service
```

The first line must name `grafana_roles`. Then sign in to Grafana as one of the admins.

### 3.3 Roster sync on the new release

Run the user sync once more (as in §1.2); the log shows `User sync finished` with the `view-realm`
role gone.

### 3.4 Admin role in step, merged accounts

- Compare `active_admins` (§1.3) with *Realm roles* → `Admin` → *Users in role*; an admin without
  the realm role gets no second factor (ADR-0355 Amendment 3).
- Withdraw the roles of every `merged_away_with_role` account on its member page (*Rollen* tab).

## 4. Admin follow-ups in the app

1. **P4K import with a catalogue version 2** (p4kx, LIVE, `.json`/`.json.gz`/`.json.zst`, ≤ 64,000,000
   bytes) under Admin → P4K import: preview, then apply, with *Neue Items und Schiffe anlegen* as
   wanted. The old KRT P4K Reader format is refused. Until a run is applied the star map, the shops,
   the loadouts and the German names are empty, **a new storage place without a city or station
   cannot be created** (REQ-P4K-036), and `P4kImportDue` fires after seven days. Work the review list
   afterwards.
2. **VerseKit in the registry** (Admin → Verbundene Anwendungen): grant `exchange.refinery.read`,
   `exchange.refinery.write`, `exchange.refinery.store` and `writesPerDay` = 2000
   ([`legal/approved-clients.md`](legal/approved-clients.md)).
3. **Not now:** the three bank scopes for VerseKit and the bank switch wait for VerseKit 5.3.0; the
   switch stays off (REQ-BXCH-006).

## 5. Rollback

Prefer a fix forward: there is no rollback that keeps the data written after the promotion.

1. Stop the deploy timer, promote `1.22.3` (`gh workflow run promote.yml -f version=1.22.3`).
2. Restore the §1.3 snapshot of the Basetool database ([`backup.md` → Restoring](backup.md#restoring-disaster-recovery));
   v1.22.3 does not start on the 1.23.0 schema.
3. Run v1.22.3's provisioner, then `scripts/restore-keycloak-realm-roles.py` with `roles.tsv`, as in
   [`deployment.md` → Rollback](deployment.md#roles-in-the-basetool-the-switch-and-its-rollback).
   v1.22.3's provisioner does not know `kc-admin-api`; keep the §1.2 assignment.
4. Start the deploy timer.

## 6. As run on 2026-11-09

| Step | Result |
|---|---|
| §1.2 | `kc-admin-api` assigned to `backend-service`; sync at 11:31 UTC: `Synced 104 users` |
| §1.3 | backup complete 12:03 UTC; `flyway_head 337`, two admins (one assigned by mistake, withdrawn and synced at 12:08, its line removed from the export), no merged-away account with a role, no `DEPARTED` account |
| §2 | promotion approved about 12:21 UTC; `deploy successful` at 12:28:44; 37 migrations in 1.3 s, now at `v374`; one restart window incl. both databases |
| §3.1 | 32 changes applied, re-plan clean; `view-realm` withdrawn without `manage-users`; the provisioner client needed `kc-admin-api` first |
| §3.2 | Grafana restarted by hand after the apply; login works with `grafana-admin` |
| §3.3 | sync at 12:52 UTC: `Synced 104 users` |
| §4.2 | VerseKit's refinery scopes and quota set in the registry |
| Open | §4.1, the first P4K import of catalogue version 2 |

Unrelated to the release: the UEX commodity price fetch has failed to parse since 2026-11-08; with
1.23.0 the incomplete slice marks `uex_sync` as failed.
