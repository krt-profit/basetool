# Host layout migration — `iri` to `basetool` — production rollout runbook

> **Doc type:** Operator runbook for **one** rollout — moving production (and first the testing host)
> from the old account, directory and realm names to the RHEL/FHS layout of
> [ADR-0319](adr/0319-the-host-follows-the-rhel-fhs-layout-and-the-product-carries-no-legacy-name.md).
> Written 2026-10-24 from the repository and from the read-only host research of that day. **Historical
> once the cutover is verified and §9 is done**: move it to `docs/archive/` then and let
> [`deployment.md`](deployment.md) stay the living truth.
>
> **Every step marked WRITE changes the host, the realm or an external service: it waits for
> @greluc's explicit yes, in chat, to that exact command at run time** (CLAUDE.md → *Production host
> access*). Nothing here is approval, and no write in this document has been executed. Steps without the
> mark only read. External writes are marked too: the Discord portal, the Hetzner console,
> `promote.yml`, deleting a snapshot.

This is the one document that names the old identifiers (`iri`, `/var/iri`, `IRI_*`, `iri-*` units) on
purpose: they are the subject of the migration. Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are
those of [`deployment.md` → Shell conventions](deployment.md#shell-conventions-used-below), but
**until §5 step 11 the service user still has its old name**; the commands that need it spell it out.

The tools: [`scripts/migrate-host-layout.sh`](../scripts/migrate-host-layout.sh) (with
[`scripts/migrate-host-env.py`](../scripts/migrate-host-env.py) beside it) and
[`scripts/rename-keycloak-realm.py`](../scripts/rename-keycloak-realm.py) (with
[`scripts/keycloak_kcadm.py`](../scripts/keycloak_kcadm.py) beside it). They are not in
`scripts/host-scripts.list`: they run once, by hand, from a copy, before the role has installed anything
under `/opt/basetool` (ADR-0247 keeps that list to what the role installs).

---

## 1. What changes

| | Before | After |
|---|---|---|
| Service user and group | `iri` (uid/gid derived per host, subuid and subgid `100000:65536`, home `/home/iri`) | `basetool` — same uid/gid and range, home `/home/basetool` |
| Deploy user | `deploy`, home `/var/lib/iri` | `deploy`, home `/var/lib/basetool/deploy` |
| Release tree (compose, `docker/`, `monitoring/`, `quadlet/`, `scripts/`, `keycloak/providers`) | `/var/iri/code` | `/opt/basetool` |
| Host env file | `/var/iri/code/.env` (+ `.env.backup-*`) | `/etc/basetool/basetool.env` — **outside the release tree** |
| Rendered env, realm export | `/var/iri/code/env.d`, `/var/iri/code/realm-export.json` | `/etc/basetool/env.d`, `/etc/basetool/realm-export.json` |
| Secrets, monitoring secrets and certs | `/var/iri/secrets`, `/var/iri/monitoring/{secrets,certs}` | `/etc/basetool/secrets`, `/etc/basetool/monitoring/{secrets,certs}` |
| Deployer credentials | `/etc/iri` (`backup.env`, `rclone.conf`, token), `/etc/iri/ssg-tailoring.xml` | `/etc/basetool/deploy`, `/etc/basetool/ssg-tailoring.xml` |
| Data | `/var/iri/{db-backend,db-keycloak,redis,backup}`, `/var/iri/monitoring/{data,textfile}` | `/var/lib/basetool/{db-backend,db-keycloak,redis,backup}`, `/var/lib/basetool/{monitoring,textfile}` |
| Deployer state | `/var/lib/iri` | `/var/lib/basetool/deploy` |
| Service logs | `/var/iri/{backend,frontend,ingest,keycloak}/log` | `/var/log/basetool/{backend,frontend,ingest,keycloak}` |
| Host-script logs | `/var/log/iri-<x>.log` | `/var/log/basetool/<x>.log` (Alloy: `/hostlog/basetool/<x>.log`) |
| restic cache | `/var/lib/iri/restic-cache` | `/var/cache/basetool/restic` |
| Locks | `/var/lock/iri-<x>.lock` | `/run/lock/basetool-<x>.lock` |
| Units, drop-ins, logrotate, tmpfiles | `iri-<x>.{service,timer}`, `/etc/logrotate.d/iri-*`, `/etc/tmpfiles.d/iri-locks.conf` | `basetool-<x>…`, `basetool-*`, `basetool-locks.conf` |
| SELinux | local rules `/var/iri(/.*)?` `container_file_t`, `/var/iri/code/scripts(/.*)?` `bin_t` | seven rules, see ADR-0319; the two old ones are deleted |
| Keycloak realm | `iri` (default role `default-roles-iri`) | `basetool` (default role `default-roles-basetool`) |
| Environment keys | `IRI_<NAME>` | `BASETOOL_<NAME>`; `IRI_BASETOOL_VERSION` → `BASETOOL_VERSION`; `IRI_MONITORING_DIR` has no successor key: dropped when it equals `/var/iri/monitoring`, otherwise `rewrite` refuses and names `BASETOOL_MONITORING_DATA_DIR` / `BASETOOL_MONITORING_CONFIG_DIR` |
| Entries the plan does not map, in `/var/iri/code`, `/var/iri/monitoring`, `/var/iri` and `/var/iri/<svc>` | beside the release or data | `/etc/basetool/host-local/{release,monitoring,data,data/<svc>}/<name>` (root:root 0700 directories, entries keep owner and mode); never into `/opt/basetool`. Unknown entries in `/var/lib/iri` and `/etc/iri` travel with those directories (`check`: `[info]`) |
| `.env.backup-<suffix>` | `/var/iri/code/` | `/etc/basetool/basetool.env.backup-<suffix>` with `IRI_` in the suffix renamed to `BASETOOL_`; a name collision blocks `check` and refuses `move` |
| Hand-made unit drop-ins | `iri-<x>.<type>.d/` | carried by `cleanup` to `basetool-<x>.<type>.d/`, rewritten |
| Rootless Podman store | under `/home/iri` | reset and re-created; the three named volumes (`edge-certs`, `edge-acme-state`, `edge-acme-webroot`) travel as exports |

What does **not** change: the uid/gid and subuid range (so every file owned by a container uid stays
valid), the squadron IRIDIUM and its data, the restic tag `basetool` and host name `basetool-prod`
(retention keeps grouping old and new snapshots), the public host name, the archive layout of a backup.

**User-visible cost.** The issuer changes, so every browser session, refresh token and offline token is
void: **every member signs in again, once**, and every external exchange client must switch to the new
issuer (§2). The site shows the maintenance page from §5 step 3 (the writers stop) until the first deploy is healthy
(step 20).

---

## 2. Preconditions and preparation

Nothing in this section touches the production host except the marked steps.

1. **Rehearse the whole of §3 to §6 on the testing host first**, end to end, after a Proxmox snapshot of
   its VM. It runs the same packages as production, so it proves the order and measures the window; do
   not announce a length before it. Differences on testing: promote with `promote-testing.yml`
   ([`deployment.md` → Promoting to testing](deployment.md#promoting-to-testing)) instead of
   `promote.yml`. Its hand-made `iri-deploy.service.d/override.conf` (`--tag testing`) is carried over by
   `cleanup` (`check` lists it beforehand); `basetool-deploy.service.d/override.conf` must exist afterwards
   (verification 2).
   The rehearsal must also prove what the repository cannot: the kcadm truststore step on the old stack
   (the open question in [`keycloak/README.md`](keycloak/README.md), after #2036), that the podman pause
   process is gone after `stop` (the script ends it; the rehearsal confirms the new home is then
   accepted), that Keycloak 26.8 accepts the
   default-role rename by id (`rename-keycloak-realm.py` verifies it by reading back and fails loudly
   if not), that a realm-local service account with `manage-realm` may rename its own realm, and that
   `podman volume import` restores the edge volumes with the translated owners.
   Testing cannot prove the Discord login: there, verification 7 signs in with a local Keycloak user of
   the realm instead, and the Discord identity provider is compared read-only before and after the
   rename (`kcadm.sh get identity-provider/instances/discord -r <realm> --fields 'alias,enabled,config(clientId,syncMode)'`,
   which never selects the client secret, plus `get identity-provider/instances/discord/mappers`): alias, client
   id, enabled flag and mappers must be unchanged. The Discord redirect itself is first proven on
   production, in verification 7, before the members come back.
2. **Discord developer portal (needs the owner): ADD** the redirect URI
   `https://profit-base.online/auth/realms/basetool/broker/discord/endpoint` **before** the cutover and
   keep the old `…/realms/iri/broker/discord/endpoint` until §9
   ([`keycloak/DISCORD_KEYCLOAK_SETUP.md`](keycloak/DISCORD_KEYCLOAK_SETUP.md)). **WRITE** (external). Without it
   every Discord login answers `Invalid OAuth2 redirect_uri`.
3. **SC extractor v2.13.0 is prepared** (the release that carries the new issuer) and **published right
   after the verification of §6**, not before: until then members of the old extractor are the ones who
   still sign in against `iri`.
4. **Announce** to the members (maintenance window; everyone signs in again) and **to the developers of
   every approved exchange client** ([`exchange/README.md`](exchange/README.md)): the new issuer
   `https://profit-base.online/auth/realms/basetool`, the date, and that old refresh tokens stop working.
5. **A fresh, verified off-site backup** exists: the last nightly run succeeded and the last restore drill
   passed (`basetool_restore_drill_last_success_timestamp` is younger than 8 days). §5 step 4 adds a
   final run inside the window, with the writers stopped.
6. **The rollback is a Hetzner server snapshot taken immediately before the window** (owner decision,
   step 5), after the writers stopped and the final backup ran, so it holds every member write. It is
   the only complete undo: databases, Podman store, accounts and realm. The migration's staging
   directory only helps a partial manual undo. What a restore loses, and the deadline for choosing it,
   are in §7.
7. **The release is cut but not promoted.** Cut the version that carries this change
   ([`deployment.md` → Cutting a release](deployment.md#cutting-a-release)); `:stable` keeps naming the
   previous release. **Do not run `promote.yml` before §5 step 19**: an old host's scripts refuse a bundle
   built with the new ones (the host-scripts hash check, ADR-0247) and the new `deploy.sh` stops on a
   host without `/etc/basetool/basetool.env` — both safe, but a promotion in the middle of the migration
   would still deploy the new release onto half a host.
8. **The new host scripts reach the host through the role, not the bundle.** From a checkout **of the
   release tag** (not `main`), in WSL, in a copy on the Linux filesystem
   ([`ansible/README.md` → Running it](../ansible/README.md#running-it)), with
   `ansible/inventory/hosts.yml` filled in. The role has a guard: it refuses to run while another account
   holds subuid base 100000, so it **cannot** be run before the `accounts` phase by mistake.
9. **The four migration tools are on the host, byte-exact.** From a Linux checkout of the release tag:

   ```bash
   ssh greluc@<host> "mkdir -p basetool-migration"
   scp scripts/migrate-host-layout.sh scripts/migrate-host-env.py scripts/rename-keycloak-realm.py \
       scripts/keycloak_kcadm.py "greluc@<host>:basetool-migration/"
   ssh greluc@<host> "cd basetool-migration && sha256sum *" > host.sha256
   for f in migrate-host-layout.sh migrate-host-env.py rename-keycloak-realm.py keycloak_kcadm.py; do
     printf '%s  %s\n' "$(git show <tag>:scripts/${f} | sha256sum | cut -d' ' -f1)" "${f}"
   done > expected.sha256
   diff <(sort -k2 expected.sha256) <(tr -d '\r' < host.sha256 | sort -k2)
   ```

   **WRITE** (creates files in the operator's home; needs approval). No output from `diff` is a match.
   Hash with Git-Bash or WSL, never through PowerShell
   ([`deployment.md` → Before a promotion](deployment.md#before-a-promotion-the-host-scripts)). Below,
   `M=/home/greluc/basetool-migration`.
10. **Provisioning identity in the realm `iri`.** `rename-keycloak-realm.py` drives the operator's kcadm
    session; create the short-lived confidential client `basetool-provisioner` with the roles
    `manage-clients` and `manage-realm`
    ([`keycloak/README.md` → Why a service account](keycloak/README.md#why-a-service-account-and-not-the-admin-user)).
    **WRITE** (Admin Console). Have its secret at hand; kcadm prompts for it.
11. **Read-only checks on the production host** (name each as you run it; none needs the password):

    ```bash
    systemctl list-unit-files 'iri-*'                 # the unit set the cutover replaces
    ls -d /etc/systemd/system/iri-*.d; ls /etc/systemd/system/iri-*.d   # hand-made drop-ins? only 10-/20- are expected
    getent passwd iri deploy; grep -E '^(iri|basetool):' /etc/subuid /etc/subgid
    sudo -u iri podman volume ls                      # the three edge-* volumes must be there (as root if the read grant does not cover it)
    ls -A /var/iri /var/iri/monitoring                # anything outside the plan is listed by `check` and moved to host-local
    df -h /var /opt /etc /home                        # room for the three volume exports
    ```

    A drop-in other than the role's two (`10-deploy-account-sandbox.conf`, `20-service-user.conf`) is carried
    over by `cleanup`; `check` lists it (and blocks on a line it cannot map).

---

## 3. The release and the order of things

```text
cut release (not promoted) ─► check ─► window ─► stop timers ─► stop writers ─► final backup ─► snapshot
   ─► check ─► rename realm ─► migrate-host-layout.sh: stop · export-volumes · reset-podman · accounts
   · move · rewrite ─► Ansible role ─► cleanup ─► import-volumes
   ─► hash check ─► image-pin check ─► go/no-go ─► promote.yml ─► first deploy ─► verify ─► extractor v2.13.0
```

Why this order:

- **Nothing writes after the backup and the snapshot.** The job timers and the writers (`backend`,
  `frontend`, `ingest`) stop first; Keycloak, its database and the edge stay up, because kcadm needs the
  running container and the edge serves the maintenance page. Only then come the final backup and the
  snapshot, so a restore loses no member write (§7).
- **The realm is renamed while the old stack's Keycloak still runs**, under the `iri` account, before the
  `accounts` phase makes that impossible.
- **`rewrite` precedes the role and `cleanup` follows it**: the role runs against the moved, rewritten
  tree, and `cleanup` refuses until the `basetool-*` units exist, so the host is never left without a
  unit set.
- **Nothing is promoted and the deploy timer is not started until the host is migrated.** Starting it
  earlier would re-apply the *previous* release's bundle, whose units name the old paths.
- **The first deploy has no way back.** `rewrite` parks the old rollback anchors, the pin files and the old
  release definition, so `deploy.sh` takes its first-bundle path: no config restore, no pin rollback, the
  stateful-infra gate does not apply. A failed health gate ends with `no previous pin available - manual
  intervention required`, exit `2`, and the new tree stays for fixing forward (§7).

Exit codes, per tool:

| Tool | 0 | 1 | 2 | 3 |
|---|---|---|---|---|
| `migrate-host-layout.sh PHASE` | done, or `nothing to do, already done` | failed (`FATAL`, a command failed; `cleanup` found entries left in `/var/iri`) | usage | a precondition **refused** the phase: it stopped there, earlier steps of that run may have run (`stop` has disabled timers); fix and re-run, it resumes |
| `migrate-host-layout.sh check` | no `[block]` | | | at least one `[block]`, or not root |
| `rename-keycloak-realm.py` | done or nothing to do, verified | a real failure (both realms, no realm, role clash, Keycloak error, failed verification): read it, fix, re-run | `--check` found changes pending, or the kcadm session is invalid (`PENDING:`, the re-login is printed): log in, re-run | |
| `migrate-host-env.py` | ok | file missing | | refused, nothing written (unmapped path, collision, retired key, invalid JSON) |

---

## 4. Before the window opens (read-only)

Confirm: §2 is complete, the rehearsal passed, the announcement is out, the owner is at the Hetzner
console and Discord portal, and `ssh greluc@<host>` plus `sudo -i` work. Then, as root from `/`:

```bash
cd /
M=/home/greluc/basetool-migration
bash "$M/migrate-host-layout.sh" check
openssl s_client -connect profit-base.online:443 -servername profit-base.online </dev/null 2>/dev/null \
  | openssl x509 -noout -serial -enddate
```

`check` is read-only, runs with the stack up and blocks on nothing that the window itself fixes (unknown entries are moved, not blocking; a target-name collision of two `.env.backup-*` copies does block, and so does an env file, env
backup, rendered `env.d` file or host-local env snapshot that is group-writable or readable by
others; `move` refuses on the same file). It prints
the tools, accounts, units, every move with its state, the env and realm-export rewrites it would do
(counts only, never a value), the carried drop-ins, what lands in `host-local` (`[todo] move`, by name), the label rules, the
staging directory and **`window:` lines** (how many old units are active, the state of alloy,
`dnf-automatic.timer` and `user@<uid>`, and how many processes run in the subuid range). Exit `3` or any
`[block]`: resolve it **now**, not inside the window. Write the certificate serial and end date down for
§6. Also compare the `Image=` lines of `quadlet/systemd/db-backend.container`, `db-keycloak.container` and
`keycloak.container` of the tag with the live containers
(`sudo -u iri podman ps --format '{{.Names}} {{.Image}}'`): a different Postgres or Keycloak **tag** is a
stateful upgrade that does not belong in this window
([`deployment.md` → Stateful-infra upgrades](deployment.md#stateful-infra-upgrades)).

---

## 5. The window

Run as root from `/` (`ssh greluc@<host>`, `sudo -i`, `M` as in §4). Every WRITE waits for the yes. After
each `migrate-host-layout.sh` phase read its closing line and the exit code (§3); each phase takes
`--dry-run` first (prints `would: …`, changes nothing) and is safe to run again after a failure.

A command block is easier to run as a script than to paste into the ssh session: write it to
`/home/<operator>/stepN.sh`, start it with `cd /`, read it once, then run `bash /home/<operator>/stepN.sh`
as root (in a `sudo -i` shell `~` is `/root`, so name the operator's home explicitly). Delete the file
afterwards when it names a secret or its path.

1. **Announce the start** to the members and the exchange developers. **WRITE** (external).

2. **Stop the job timers** and wait for running jobs. Optionally silence Alertmanager for the window (the
   old stack's realm and target alerts fire from step 7). **WRITE.**

   ```bash
   systemctl disable --now iri-deploy.timer iri-backup.timer iri-restore-drill.timer iri-container-cleanup.timer
   systemctl is-active iri-deploy.service iri-backup.service iri-restore-drill.service iri-container-cleanup.service
   flock /var/lock/iri-deploy.lock true
   ```

   Every `is-active` must answer `inactive`; `flock` returns once no deploy is running.

3. **Stop the writers.** **WRITE.** The edge now serves the maintenance page; Keycloak, `db-keycloak`
   and the edge stay up.

   ```bash
   IRI_UID=$(id -u iri)
   sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} systemctl --user stop frontend.service ingest.service backend.service
   sudo -u iri podman ps --format '{{.Names}}'
   ```

   The list must no longer name `backend`, `frontend` or `ingest`.

4. **Final backup**, writers already stopped (`--no-quiesce`, so the script does not restart them).
   **WRITE.**

   ```bash
   sudo -u deploy /var/iri/code/scripts/backup.sh --no-quiesce
   set -a; . /etc/iri/backup.env; set +a
   restic snapshots --no-cache --compact | tail -n 5
   ```

   The run must end without `FATAL`, the snapshot count must have risen, and the `edge-certs` and
   `edge-acme-state` lines must say `captured` ([`backup.md` → Verify](backup.md#7-verify)).

5. **Hetzner snapshot** of the production server — the rollback path. **WRITE** (the owner, in the
   console). Record its name and id in the chat. The application databases are idle (their writers are
   stopped); it is crash-consistent for the rest, and step 4's backup is the logical fallback.

6. **`check` again**, read-only — the gate before the first migration write:

   ```bash
   bash "$M/migrate-host-layout.sh" check
   ```

   Exit `3` or any `[block]`: stop; nothing has changed yet except the stopped timers and writers
   (restart them, §7).

7. **Rename the realm.** **WRITE** (Keycloak). The kcadm session is issued by the realm `iri`, which stops
   existing at the rename: the script then prints `PENDING:` with the re-login command and exits `2`, and
   the second run continues with what is open. The hint names the account from the host's account
   database (`iri` until step 11); use the commands below if in doubt.

   ```bash
   KCCFG=/opt/keycloak/data/tmp/kcadm.config
   KCADM="sudo -u iri podman exec -i keycloak sh -c 'exec /opt/keycloak/bin/kcadm.sh \"\$@\" --config $KCCFG' kcadm"
   sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config truststore \
       --trustpass - /run/secrets/keystore.p12 --config "$KCCFG"
   sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
       --server https://localhost:18443/auth --realm iri --client basetool-provisioner --config "$KCCFG"
   python3 "$M/rename-keycloak-realm.py" --check --kcadm-command "$KCADM"     # exit 2: changes pending
   python3 "$M/rename-keycloak-realm.py" --apply --kcadm-command "$KCADM"
   ```

   `--check` lists the open `~` changes (realm, default role, the frontend client's labels when they still
   carry the old brand) and no `!` line. If `--apply` ends with `PENDING:` (exit `2`), log in against the
   **new** realm and run it again; exit `1` is a real failure: read it, fix the cause, re-run. The first
   `config credentials` against the new realm is rejected when the client secret is mistyped; nothing is
   half-done then, so the resume is only: log in to `basetool` again and run `--apply` again.

   ```bash
   sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
       --server https://localhost:18443/auth --realm basetool --client basetool-provisioner --config "$KCCFG"
   python3 "$M/rename-keycloak-realm.py" --apply --kcadm-command "$KCADM"
   python3 "$M/rename-keycloak-realm.py" --check --kcadm-command "$KCADM"   # exit 0, "Nothing to do"
   sudo -u iri podman exec keycloak rm -f "$KCCFG"
   curl -fsS https://profit-base.online/auth/realms/basetool/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
   curl -s -o /dev/null -w '%{http_code}\n' https://profit-base.online/auth/realms/iri/.well-known/openid-configuration
   ```

   The issuer must end in `/realms/basetool`, and the old realm must answer `404`. The final `--apply`
   prints the read-back (`realm 'basetool' exists`, `the default role is 'default-roles-basetool'`); if it
   reports a `!` for the default role, **stop and restore the snapshot** — the app code assumes that name
   (`KeycloakService`, the provisioner).

8. **`stop`** — disables the old timers and services, waits for running jobs, stops Alloy,
   `dnf-automatic.timer` and the service user's manager, parks the Quadlet units except the three
   `.volume` ones. **WRITE.**

   ```bash
   bash "$M/migrate-host-layout.sh" stop
   ```

9. **`export-volumes`** — exports the three edge volumes as `iri` into the root-only
   `/root/basetool-migration/volumes/`, each with a `.sha256`. **WRITE.** The exports hold private keys.

   ```bash
   bash "$M/migrate-host-layout.sh" export-volumes
   ls -l /root/basetool-migration/volumes
   ```

10. **`reset-podman`** — `podman system reset --force` as `iri`: **deletes every image, container, volume
    and network of that user**; it runs only after the three exports are verified, and it asserts that the
    old store directory is gone afterwards. Podman may recreate an empty skeleton there (a lock file and
    empty `overlay` directories, no database, no images, no containers); the phase removes exactly that
    and refuses on anything else. **WRITE — destructive, named in the approval.**

    ```bash
    bash "$M/migrate-host-layout.sh" reset-podman
    ```

11. **`accounts`** — renames group and user (uid/gid kept), the entries in `/etc/subuid` and
    `/etc/subgid` (same range), moves the home, and enables linger for `basetool`. **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" accounts
    getent passwd basetool; getent group basetool; grep '^basetool:' /etc/subuid /etc/subgid
    ls -ld /home/basetool; ls /var/lib/systemd/linger
    ```

    Expect the host's uid and gid unchanged (derived, not fixed), home `/home/basetool`, `basetool:100000:65536` in both files and a `basetool`
    entry in the linger directory.

12. **`move`** — every directory and file of §1 to its new place on the same filesystem (renames: owners,
    modes and labels travel), the `deploy` home, the seven new SELinux rules, the two old ones deleted,
    `restorecon -RF` on the five roots. Entries of `/var/iri` and its service directories, `/var/iri/monitoring`
    and `/var/iri/code` that the plan does not map go to `/etc/basetool/host-local/…` (see §1). It refuses, and prints the
    table, while a row is in conflict. The `deploy` account has its own rootless Podman store in its home
    (used only for `podman login`); it would fail after the home moves with `database configuration
    mismatch`, so the phase first moves it to `/root/basetool-migration/deploy-podman-storage` (never
    deleted; the next `podman login` creates a fresh one) and refuses while a `deploy` process runs.
    **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" move
    find /etc/basetool/host-local -maxdepth 2 -ls 2>/dev/null
    ```

13. **`rewrite`** — renames the `IRI_*` keys, maps the old paths and the realm in `basetool.env` and
    `/etc/basetool/deploy/backup.env`, rewrites `realm-export.json`; an unmappable `.env.backup-*` copy is
    reported and left as it is. It stages the originals under `/root/basetool-migration/originals/` and
    parks, once, the old rollback anchors, pin files and the old release definition under `anchors/` and
    `release-tree/` (§3). It refuses before writing anything if the live env, `backup.env` or the realm
    export cannot be rewritten (an unmappable old path, `IRI_MONITORING_DIR` with another value than
    `/var/iri/monitoring`). **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" rewrite
    ```

    Decide a refusal by hand, then run it again. The output carries counts and key names only.

14. **The Ansible role** — full, untagged, from the WSL checkout of the tag (§2 item 8); it sets the new
    directories' owners and modes, installs the scripts, the `basetool-*` units with their drop-ins,
    logrotate and tmpfiles files, the sudoers rule for `(basetool)`, the Alloy drop-in, the node-exporter
    flag, and starts the metric timers and `dnf-automatic.timer`. **WRITE.**

    ```bash
    ansible-playbook site.yml --limit production -K --check --diff
    ansible-playbook site.yml --limit production -K
    ansible-playbook site.yml --limit production -K            # must report changed=0
    ```

15. **`cleanup`** — carries every hand-made drop-in to `basetool-<x>.<type>.d/` (rewritten; an existing,
    different target refuses), then stages and removes the old `iri-*` units, timers, drop-ins, `*.wants`
    links, logrotate and tmpfiles files, the old locks, timer stamps and linger file; `daemon-reload`;
    drops the old label rules; removes the empty `/var/iri` tree. It refuses until the `basetool-*` units
    are installed, `basetool.env` has no `IRI_` key and `/var/iri/code` is gone. If it ends `FATAL … still
    holds the entries above`, move or remove what it lists by hand and run it again. **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" cleanup
    ```

16. **`import-volumes`** — starts the user manager, creates the three volumes through their Quadlet
    units and imports the exports (one marker per volume, never into a non-empty volume), starts
    `dnf-automatic.timer` again. **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" import-volumes
    BASETOOL_UID=$(id -u basetool)
    UCTL="sudo -u basetool XDG_RUNTIME_DIR=/run/user/${BASETOOL_UID} systemctl --user"
    UPOD="sudo -u basetool podman"
    ${UPOD} volume ls
    for v in edge-certs edge-acme-state edge-acme-webroot; do d="$(${UPOD} volume inspect "$v" --format '{{.Mountpoint}}')"; ls -ldn "$d"; ls -ln "$d" | head -n 5; done
    ```

    The listing must show the three volumes. `ls -ldn` of a volume's mountpoint shows the directory owned by
    the service uid itself (container root maps to it); the entries listed inside, the key files, belong to
    the translated container uid `101`, which is subuid `100100` on a host with the subuid range starting at
    `100000`.

17. **Host scripts match the tag** — the mandatory comparison of
    [`deployment.md` → Before a promotion: the host scripts](deployment.md#before-a-promotion-the-host-scripts),
    steps 1 to 3, against `/opt/basetool/scripts`. No output from `diff` is a match.

18. **Image-pin check, by hand.** The first deploy takes the first-bundle path, so the stateful-infra gate
    (REQ-OPS-006) does not apply. The Podman store was reset, so `podman ps` is empty: from the tag
    checkout, compare the `Image=` lines of `quadlet/systemd/db-backend.container`, `db-keycloak.container`
    and `keycloak.container` with the parked old units in
    `/root/basetool-migration/release-tree/quadlet/systemd/{db-backend,db-keycloak,keycloak}.container`
    (the same comparison against the live containers is in §4). A different Postgres or Keycloak **tag** is a
    stateful upgrade: do it first by
    [`deployment.md` → Stateful-infra upgrades](deployment.md#stateful-infra-upgrades), not in this window.

19. **Go / no-go, then promote.** This is the last point at which the snapshot restores without losing a
    member write (§7); the owner decides here. **WRITE** (the required reviewer on the `production`
    environment approves).

    ```bash
    gh workflow run promote.yml -f version=<version>
    ```

20. **First deploy** — **WRITE.** The new deploy timer is enabled but not started; run one tick by hand
    and read it to the end.

    ```bash
    systemctl start basetool-deploy.service
    tail -f /var/log/basetool/deploy.log
    ```

    It ends with `deploy successful`. It renders `/etc/basetool/env.d`, installs the units into
    `/etc/containers/systemd/users/<basetool uid>/`, pulls the pinned images (the Podman store was reset) and
    starts the stack. Exit `2` with `manual intervention required` means the health gate failed and
    nothing was rolled back: read the log, fix the cause, then
    `sudo -u deploy /opt/basetool/scripts/deploy.sh --force`. Then start the timers: **WRITE.**

    ```bash
    systemctl start basetool-deploy.timer basetool-backup.timer basetool-restore-drill.timer basetool-container-cleanup.timer
    ```

    A `Persistent=true` timer may run once right after its first start; read `backup.log` before
    assuming a fault.

---

## 6. Verification

Read-only unless stated.

1. **Accounts and linger:** `loginctl show-user basetool -p Linger` → `Linger=yes`; `getent passwd deploy`
   → home `/var/lib/basetool/deploy`; `ls /var/lib/systemd/linger` lists `basetool`.
2. **Units and timers:** `systemctl list-timers 'basetool-*' --all`; `systemctl is-active dnf-automatic.timer`
   → `active`; `systemctl list-unit-files 'iri-*'` prints nothing; `${UCTL} list-units --state=failed`
   prints nothing; carried drop-ins exist (`ls /etc/systemd/system/basetool-*.d/`, on testing
   `basetool-deploy.service.d/override.conf`).
3. **Containers:** `${UPOD} ps --format '{{.Names}} {{.Status}}'` — every service up, the app services
   `healthy`.
4. **Layout:** `stat -c '%U:%G %a %n' /etc/basetool /etc/basetool/basetool.env /etc/basetool/env.d
   /etc/basetool/env.d/* /etc/basetool/deploy /opt/basetool/scripts /var/lib/basetool /var/log/basetool`
   against [`deployment.md` → Secrets and host-only files](deployment.md#secrets-and-host-only-files)
   (`env.d` `deploy:basetool 2750`, its files `deploy:basetool 0640`, the env file `deploy:deploy 0640`);
   no `/var/iri`, `/etc/iri`, `/var/lib/iri`, `/home/iri` remains. `ls -Z /opt/basetool/scripts` is `bin_t`, a
   host-script log under `/var/log/basetool` is `var_log_t`, `ls -dZ /var/log/basetool/backend` is
   `container_file_t`. **Review and empty `/etc/basetool/host-local`** (§9): what is there came from the old
   release tree.
5. **Issuer:** `curl -fsS https://profit-base.online/auth/realms/basetool/.well-known/openid-configuration |
   grep -o '"issuer":"[^"]*"'` ends in `/realms/basetool`; `…/realms/iri/…` answers `404`.
6. **The served certificate is the imported one:** `openssl s_client -connect profit-base.online:443
   -servername profit-base.online </dev/null 2>/dev/null | openssl x509 -noout -serial -enddate` equals
   the values of §4. ACME state: `${UPOD} logs acme --tail 30` shows no error, and the renewal check of
   [`deployment.md` → Public certificates and ACME renewal](deployment.md#public-certificates-and-acme-renewal)
   finds its account.
7. **A Discord login** by the owner, end to end, and an admin page that reads the realm's roles. On
   production this is the first proof that Discord accepts the new redirect URI, so it runs right after
   the first deploy, before the window is announced as over, and inside the rollback deadline of §7; a failure here is decided against the
   snapshot. On testing, sign in with a local Keycloak user of the realm instead (§2 step 1).
8. **Ingest and exchange:** one post by the desktop extractor (or a client of the owner) to the ingest
   gateway, and one call of an approved exchange client against the new issuer
   ([`exchange/quickstart.md`](exchange/quickstart.md)).
9. **Backup and restore drill** — **WRITE:** `systemctl start basetool-backup.service`, read
   `/var/log/basetool/backup.log` (the first run is slower: the restic cache is new), then
   `systemctl start basetool-restore-drill.service` and read `restore-drill.log`; the drill picks the
   newest, new-layout snapshot.
10. **Alerts quiet:** Grafana → Alerting shows nothing firing that is not explained; `DeployFailed`,
    `TargetDown`, `HostScriptsCheckStale` and `RestoreDrillArtifactNotRestorable` are the ones to look at.
11. **Alloy tails the new paths:** `systemctl is-active alloy`; Grafana → Explore → Loki
    `{app=~"ops-.*"}` shows lines from the last minutes for `ops-deploy` and `ops-backup`
    (`/hostlog/basetool/<x>.log`); `{app="backend"}` shows current lines. A burst of older lines on the
    first pass is expected. If `alloy` is still `inactive` after step 20 (the role enables it but starts it
    only once its config exists), start it: **WRITE** `systemctl start alloy.service`, then re-check.
12. **Node-exporter textfiles:** `grep -o -- '--collector.textfile.directory=[^ "]*' /etc/default/prometheus-node-exporter`
    names `/var/lib/basetool/textfile`; `ls -l /var/lib/basetool/textfile/*.prom` shows files younger than
    a few minutes.
13. **Logrotate and SELinux:** `logrotate -d /etc/logrotate.d/basetool-deploy` reports no error, in
    particular no "insecure permissions"; `ausearch -m AVC -ts <the time of step 8>` shows no denial that
    names a `/opt/basetool`, `/etc/basetool`, `/var/lib/basetool` or `/var/log/basetool` path.
14. **Conformance:** from the workstation, `python scripts/check-conformance.py --ssh greluc@<host> --sudo`.
15. **Then** publish the SC extractor **v2.13.0** (**WRITE**, external) and tell the exchange developers it
    is done.

---

## 7. Rollback

**What a restore loses.** The snapshot of step 5 was taken after the writers stopped and the final backup
ran, so until step 20 starts the stack again, restoring it loses **nothing a member wrote**. After step 20
reopens the site, a restore loses **every member write since the snapshot**. **Deadline: the owner decides
at step 19 (go/no-go), and at the latest before §6 item 7 passes.** After that, fix forward and use restic
for data problems.

**Primary path: restore the Hetzner snapshot** (rebuild the existing server from it in the console; it
keeps its address). **WRITE** (the owner, in the console). It returns the accounts, the Podman store, the
databases and the realm `iri` to the moment of the snapshot; the old Discord redirect URI is still
registered, which is why §9 removes it last. Afterwards the job timers and writers are still stopped, as
they were when the snapshot was taken. **First**, if `promote.yml` already moved `:stable`, promote the
previous version again (`gh workflow run promote.yml -f version=<previous>`, **WRITE**), so the old
deploy timer finds the old release. **Then** start the timers and writers (**WRITE**):
`systemctl enable --now iri-deploy.timer iri-backup.timer iri-restore-drill.timer iri-container-cleanup.timer`
and `sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user start backend.service ingest.service frontend.service`.
Announce that the cutover is postponed (**WRITE**, external).

**Partial manual undo.** The migration has **no undo phase**; the staging directory
`/root/basetool-migration` (root-only) keeps what a manual undo needs: `state/` (phase markers),
`volumes/` (the three exports), `originals/` (the files before `rewrite`), `anchors/` and `release-tree/`
(the parked rollback state and the old release definition), `units/` (the parked Quadlet units),
`old-files/` (the removed `iri-*` files), `user-containers-config.tar`. By phase; every row is a WRITE:

| Failed at | State | Way back |
|---|---|---|
| steps 2–6 | timers and writers stopped, nothing migrated | `systemctl enable --now` the four `iri-*` timers, start the writers (`sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user start backend.service ingest.service frontend.service`) |
| realm rename (7) | realm renamed, host untouched | no reverse script; restore the snapshot, or go forward and finish the host |
| `stop`, `export-volumes` | timers/units stopped, volumes exported | `systemctl enable --now` the four `iri-*` timers, `systemctl start user@$(id -u iri).service`; the stack comes back from the parked units in `units/` |
| `reset-podman` | the old Podman store is gone | forward only (the exports re-create the volumes), or the snapshot |
| `accounts`, `move`, `rewrite` | partly renamed | forward: every phase resumes where it stopped; backwards by hand is the reverse of the §1 table, untested, prefer the snapshot |
| role, `cleanup`, `import-volumes` | new units present | forward; `old-files/` holds the removed files |

**The first deploy has no automatic way back** (§3): its failure is exit `2`, the new tree stays, and the
choices are to fix forward (`deploy.sh --force` after the cause) or to restore the snapshot (inside the
deadline). Going back to the old release by hand needs the staged `anchors/` and `release-tree/` and is
untested.

---

## 8. Restoring a backup taken before the migration

Every restic snapshot up to the migration was backed up from `/var/iri/backup/staging/<TS>` and carries
the old environment keys, the old paths and the realm `iri`. `restore-drill.sh` is unaffected (it finds
artifacts by name). A manual restore ([`backup.md` → Restoring](backup.md#restoring-disaster-recovery)) is
done on a migrated host and works with two additions; **every command is a WRITE**, as in that section.
The tools are on the host only while `basetool-migration/` in the operator's home stays; copy them again
from a tag checkout as in §2 item 9 when it is gone, and set `M=/home/greluc/basetool-migration`.

1. **Before** step 2 of the restore, rewrite the two restored host-config files in place with the helper
   (it keeps mode, owner and ACL, writes atomically and never prints a value; `--check` first):

   ```bash
   python3 -I "$M/migrate-host-env.py" env --check "${R}/config/dotenv"
   python3 -I "$M/migrate-host-env.py" env "${R}/config/dotenv"
   python3 -I "$M/migrate-host-env.py" realm-export "${R}/config/realm-export.json"
   ```

   Exit `3` names a key or path it cannot map ("decide by hand"); `config/internal-tls.tar`,
   `monitoring/secrets.tar.gz`, `providers.tar.gz` and `users.acl` need nothing.
2. The Keycloak dump holds the realm `iri`. At step 6, start `keycloak.service` alone and rename the realm
   with the commands of §5 step 7, with two changes: the account is `basetool` (`sudo -u basetool podman
   exec …` in `KCADM` and the credentials lines), and a snapshot older than the window has **no
   `basetool-provisioner` client** in its realm. Create it first in the restored realm `iri` through the
   Admin Console ([`deployment.md` → Keycloak Admin Console via SSH tunnel](deployment.md#keycloak-admin-console-via-ssh-tunnel);
   confidential, service accounts on, `manage-clients` and `manage-realm`), then log in with
   `--realm iri`, apply, log in with `--realm basetool`, apply again. Only then start backend, ingest and
   frontend. Sessions are void either way.

---

## 9. After the cutover

Every item is a WRITE.

1. **Discord:** remove the old redirect URI `…/realms/iri/broker/discord/endpoint` after a week without
   incident (the owner, in the portal).
2. **Keycloak:** disable or delete the client `basetool-provisioner`
   ([`keycloak/README.md`](keycloak/README.md#why-a-service-account-and-not-the-admin-user)).
3. **Host-local:** review `/etc/basetool/host-local`, move what is still needed to its proper home, delete
   the rest.
4. **Staging:** delete `/root/basetool-migration` and the operator's `basetool-migration/` copy after 14
   days at most — the volume exports hold private keys (`shred -u` the tar files, then `rm -rf`).
5. **Hetzner snapshot:** delete it once §6 has held for the same period (the owner, in the console).
6. **Testing host:** it was migrated by the rehearsal; delete its Proxmox snapshot after the same period.
7. **Record the outcome:** add a short outcome section here (date, duration, anything that differed), then
   archive this runbook ([`archive/README.md`](archive/README.md)). Open the follow-up to remove
   `scripts/migrate-host-layout.sh`, `migrate-host-env.py`, `rename-keycloak-realm.py` and their tests once
   no host and no restorable snapshot needs them (the last pre-migration snapshot ages out of the GFS
   retention after six months).
