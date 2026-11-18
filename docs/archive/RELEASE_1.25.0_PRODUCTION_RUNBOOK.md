> **Archived 2026-11-18.** Doc type: Historical record — frozen, kept as a record and no longer updated. Commands and states below describe the hosts and the code as they were.
>
> **Current truth:** [`deployment.md`](../deployment.md). Index of the archive: [`README.md`](README.md).

# Release 1.25.0 — production rollout runbook

> **Doc type:** Operator runbook for **one** release, the move of production (and, through
> `promote.yml`'s `sync-testing`, the testing host) from **v1.24.2 to v1.25.0**:
> - the patch change (epic #3328, #3423, ADR-0380…0385);
> - the ship origin (ADR-0386);
> - the patch-change resource on the exchange;
> - Grafana's Keycloak-only sign-in (#3438).
>
> Written 2026-11-14 from the diff of `v1.24.2` against `main` at `69901001d` (#3433–#3440) and
> #3423 at `22f37f888`. No production read was made for it. The version number assumes *Release ·
> Prepare* runs with `1.25.0`; rename the file and the commands if it does not. **Historical once
> 1.25.0 is live and §4 is done**: move it to `docs/archive/` then and let
> [`deployment.md`](../deployment.md) stay the living truth.
>
> **Every step that writes to a host, to Keycloak or to the live app is a production write.** It
> waits for @greluc's explicit yes, in chat, to that exact command (AGENTS.md → *Production host
> access*). Nothing here is approval, and no write in this document has been executed.

Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are the ones in
[`deployment.md` → Shell conventions](../deployment.md#shell-conventions-used-below).

---

## 0. What changes

| | Value |
|---|---|
| Range | `v1.24.2` (2026-11-14) to `main` at `69901001d` (#3433–#3440), plus #3423 |
| Patch change | Admin → *Einstellungen* → *Patchwechsel* records a patch change for every org unit: wipe, refinery cancellation, currency wipe, home-location prompt (REQ-PATCH-*). **Nothing changes for members until an admin triggers one.** A home-location phase holds members on `/home-location` (ADR-0385 session gate) |
| Ship origin | Every ship is `PLEDGE`, `IN_GAME` or `UNKNOWN`. `V418` backfills: a ship with a pledge id or a production status becomes a locked `PLEDGE`, every other ship `UNKNOWN`. The ship forms require the origin (ADR-0386) |
| Exchange | New resource `GET /exchange/v1/me/patch-change` and `POST …/home-location` behind `exchange.patch.read` and `exchange.patch.write` (`V428`). `origin` on ships. Names are cut to the v1 schema bounds (REQ-XCH-026) |
| Keycloak | The provisioner adds the client scopes `exchange.patch.read` and `exchange.patch.write` (§3.2). It now writes a WebAuthn policy whole in a realm update (#3434). The theme gains the consent texts `xchConsentPatchRead` and `xchConsentPatchWrite` |
| Grafana | Login form off, automatic sign-in through Keycloak (`env.d/grafana.env`, #3438; §3.1) |
| Flyway | 13, `V418`–`V430` (head before: `V417`). All additive: new tables, nullable columns, `ship.origin` `NOT NULL DEFAULT 'UNKNOWN'` with the backfill. `V421` and `V429` replace the exchange feed's trigger functions, `V428` widens the capability check. Small tables; expected seconds |
| Config bundle | `quadlet/env.d/grafana.env.tmpl` (two keys). Dashboard `07-basetool-operations` gains *Members still to relocate after a patch change* (`basetool_patch_change_relocations_pending`). The audit-domain silence alert exempts `PATCH_CHANGE`. Keycloak theme messages |
| Host scripts | None of `scripts/host-scripts.list` changed between `v1.24.2` and the release head (compared in the repository, 2026-11-14). §1.1 against the tag is still mandatory |
| Units, new env vars | none |
| Rollback | Expected code-only, **not tested**: the schema is additive, and Flyway skips applied migrations newer than the code by default. See §5 |

## 1. Before the promotion

### 1.1 Host scripts

Mandatory for every promotion ([`deployment.md` → Before a promotion](../deployment.md#before-a-promotion-the-host-scripts)).
Run it against the tag `v1.25.0` once it exists; a `<` line blocks the promotion.

### 1.2 Backup and read-only check

As root, from `/`:

```sh
systemctl start basetool-backup.service
tail -n 3 /var/log/basetool/backup.log
sudo -u basetool XDG_RUNTIME_DIR=/run/user/994 podman exec -i db-backend sh -c 'psql -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -c "BEGIN READ ONLY; SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1; COMMIT;"'
```

The first line writes a restic snapshot (the backup); the rest only reads. Expected:

- the backup log ends successfully;
- the query prints `417`.

Another head means another release is live: stop and compare.

### 1.3 Grafana admins

After §3.1 the Grafana login form is gone, so a Grafana admin signs in only through Keycloak with
the client role `grafana-admin` (set up in 1.23.0, §3.2 there). Check in the Admin Console →
*Clients* → `grafana` → *Roles* → `grafana-admin` → *Users in role* that every Grafana admin is
listed.

## 2. Promotion

1. Merge #3423, run *Release · Prepare* with `1.25.0`, merge the `release/v1.25.0` PR, and wait for
   `release-images.yml` on the tag.
2. Run §1.1 against `v1.25.0`.
3. `gh workflow run promote.yml -f version=1.25.0`, then approve on the `production` environment.

The next deploy tick applies it in one restart window. Check (read-only):

- `deploy.log` ends with `deploy successful`;
- the backend logs `Successfully applied 13 migrations … now at version v430`;
- the stack is healthy.

## 3. Right after the promotion

### 3.1 Grafana sign-in

The deploy renders `env.d/grafana.env` and recreates Grafana for the changed file. `deploy.log`
shows `env.d: grafana.env differs from the one it replaced → grafana is re-defined and restarts with
this apply`. Check the rendered file (read-only, root, from `/`):

```sh
grep -E '^GF_AUTH_(DISABLE_LOGIN_FORM|GENERIC_OAUTH_AUTO_LOGIN)=' /etc/basetool/env.d/grafana.env
```

The `grep` must print both keys with `true`. Then open Grafana: `/login` goes straight to Keycloak,
and a Grafana admin signs in. Only if the log line is missing and the form still shows, restart
Grafana (a write):

```sh
sudo -u basetool XDG_RUNTIME_DIR=/run/user/994 systemctl --user restart grafana.service
```

### 3.2 Realm: the two patch scopes

Run the kcadm session of
[`keycloak/README.md` → Runbook](../keycloak/README.md#runbook--a-kcadm-session-on-the-production-container)
with the provisioner of the tag `v1.25.0`. Copy `provision-keycloak-realm.py`, `keycloak_kcadm.py`
and `scripts/keycloak/` to the host, and run the dry run first. The `basetool-provisioner` client
needs the `kc-admin-api` default scope besides its two roles; without it the login succeeds and
the first read answers `HTTP 401 Unauthorized`.

```sh
python3 provision-keycloak-realm.py --realm basetool --public-origin https://profit-base.online --grafana-origin https://grafana.profit-base.online --kcadm-command "$KCADM" --frontend-client confidential
python3 provision-keycloak-realm.py --realm basetool --public-origin https://profit-base.online --grafana-origin https://grafana.profit-base.online --kcadm-command "$KCADM" --frontend-client confidential --apply
```

The dry run exits `2` and lists:

- the client scopes `exchange.patch.read` and `exchange.patch.write` with their audience mappers;
- both as optional scopes of every approved external client: `versekit` and `hangar-exporter`
  (`scripts/keycloak/external-clients.json`).

#3434 changes only how an update carries a WebAuthn policy, so it shows up only if the plan
touches a WebAuthn key. Anything else in the plan: stop and compare. After the apply, a second dry
run exits `0`.

Afterwards delete `basetool-provisioner` and the session file (README step 4).

### 3.3 Read-only checks

- **Patch-change page:** Admin → *Einstellungen* → *Patchwechsel* (`/admin/settings/patch-change`) loads, with the history saying
  *Noch keine Patchwechsel*. Trigger nothing.
- **Ship origin:**
  - The add-ship dialog in the hangar shows the origin field.
  - A ship with a pledge id shows *Gepledged*, locked.
- **Relocations metric:** `basetool_patch_change_relocations_pending` is `0`, and dashboard 07
  shows the new panel.
- **Nobody held:** no member is sent to `/home-location`, since no phase is open.

## 4. Admin follow-ups in the app

1. **VerseKit in the registry** (Admin → *Verbundene Anwendungen*):
   - Grant `exchange.patch.read` and `exchange.patch.write` once VerseKit ships its patch-change
     support. This is a write and needs @greluc's own yes.
   - Update [`legal/approved-clients.md`](../legal/approved-clients.md) the same day.
   - VerseKit's next device login asks for the two new consents. If the consent screen shows the
     raw key `xchConsentPatchRead` instead of its text, Keycloak still serves the old theme: restart
     `keycloak` in a quiet moment.
2. **Home locations:** before the first patch change with a home-location prompt, mark the curated
   home locations (Admin → *Orte verwalten* → *Als Home-Location markieren*). A trigger with the
   prompt on is refused while none is on offer (REQ-PATCH-062).
3. **The first real patch change** follows the game. Its live time may lie in the past, for a patch
   that went live before it was recorded (REQ-PATCH-003).

## 5. Rollback

Prefer a fix forward. A code-only rollback is expected to work, but it has not been tested:

1. Stop the deploy timer and promote `1.24.2` (`gh workflow run promote.yml -f version=1.24.2`).
2. Check that the backend starts. It should:
   - every 1.25.0 change to existing tables is additive;
   - `ship.origin` has a default;
   - Flyway's default ignore pattern `*:future` skips `V418`–`V430`.
3. What stays behind:
   - Patch changes, relocations and ship origins stay in the database, unused. A member held by an
     open phase is free again, since 1.24.2 has no such gate.
   - The two Keycloak scopes stay, because the provisioner never deletes (ADR-0202), and 1.24.2
     ignores them.
   - Grafana's login form returns with 1.24.2's `grafana.env`, which the deploy recreates Grafana
     for.
4. If the backend does not start on the 1.25.0 schema, restore the §1.2 snapshot
   ([`backup.md` → Restoring](../backup.md#restoring-disaster-recovery)).
5. Start the deploy timer.

## 6. As run

| Step | Result |
|---|---|
| §1.1 | |
| §1.2 | |
| §1.3 | |
| §2 | |
| §3.1 | |
| §3.2 | |
| §3.3 | |
| Open | |
