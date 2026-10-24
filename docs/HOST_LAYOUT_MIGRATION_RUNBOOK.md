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
> mark only read.

This is the one document that names the old identifiers (`iri`, `/var/iri`, `IRI_*`, `iri-*` units) on
purpose: they are the subject of the migration. Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are
those of [`deployment.md` → Shell conventions](deployment.md#shell-conventions-used-below), but
**until §5 step 10 the service user still has its old name**; the commands that need it spell it out.

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
| Service user and group | `iri` (uid/gid 994, subuid and subgid `100000:65536`, home `/home/iri`) | `basetool` — same uid/gid and range, home `/home/basetool` |
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
| Environment keys | `IRI_<NAME>` | `BASETOOL_<NAME>`; `IRI_BASETOOL_VERSION` → `BASETOOL_VERSION`; `IRI_MONITORING_DIR` → `BASETOOL_MONITORING_DATA_DIR` + `BASETOOL_MONITORING_CONFIG_DIR` |
| Rootless Podman store | under `/home/iri` | reset and re-created; the three named volumes (`edge-certs`, `edge-acme-state`, `edge-acme-webroot`) travel as exports |

What does **not** change: the uid/gid and subuid range (so every file owned by a container uid stays
valid), the squadron IRIDIUM and its data, the restic tag `basetool` and host name `basetool-prod`
(retention keeps grouping old and new snapshots), the public host name, the archive layout of a backup.

**User-visible cost.** The issuer changes, so every browser session, refresh token and offline token is
void: **every member signs in again, once**, and every external exchange client must switch to the new
issuer (§2). The site is unavailable from §5 step 7 until the first deploy is healthy (step 18).

---

## 2. Preconditions and preparation

Nothing in this section touches the production host except the marked steps.

1. **Rehearse the whole of §3 to §6 on the testing host first**, end to end, after a Proxmox snapshot of
   its VM. It runs the same packages as production, so it proves the order and measures the window; do
   not announce a length before it. Differences on testing: promote with `promote-testing.yml`
   ([`deployment.md` → Promoting to testing](deployment.md#promoting-to-testing)) instead of
   `promote.yml`, and recreate its `basetool-deploy.service.d/override.conf` (`--tag testing`) after the
   `cleanup` phase — `cleanup` stages the old drop-ins and removes them, and the copy to re-create it
   from is in `/root/basetool-migration/old-files/etc/systemd/system/iri-deploy.service.d/`.
   The rehearsal must also prove the two things the repository cannot: that Keycloak 26.8 accepts the
   default-role rename by id (`rename-keycloak-realm.py` verifies it by reading back and fails loudly
   if not) and that `podman volume import` restores the edge volumes with the translated owners.
2. **Discord developer portal (needs the owner): ADD** the redirect URI
   `https://profit-base.online/auth/realms/basetool/broker/discord/endpoint` **before** the cutover and
   keep the old `…/realms/iri/broker/discord/endpoint` until §9
   ([`keycloak/DISCORD_KEYCLOAK_SETUP.md`](keycloak/DISCORD_KEYCLOAK_SETUP.md)). Without it every
   Discord login answers `Invalid OAuth2 redirect_uri`.
3. **SC extractor v2.13.0 is prepared** (the release that carries the new issuer) and **published right
   after the verification of §6**, not before: until then members of the old extractor are the ones who
   still sign in against `iri`.
4. **Announce** to the members (maintenance window; everyone signs in again) and **to the developers of
   every approved exchange client** ([`exchange/README.md`](exchange/README.md)): the new issuer
   `https://profit-base.online/auth/realms/basetool`, the date, and that old refresh tokens stop working.
5. **A fresh, verified off-site backup** exists: the last nightly run succeeded and the last restore drill
   passed (`basetool_restore_drill_last_success_timestamp` is younger than 8 days). §5 step 2 adds a
   final run inside the window.
6. **The rollback is a Hetzner server snapshot taken immediately before the window** (owner decision,
   step 3). It is the only complete undo: it carries the databases, the Podman store, the accounts and
   the realm. The migration's staging directory only helps a partial manual undo (§7).
7. **The release is cut but not promoted.** Cut the version that carries this change
   ([`deployment.md` → Cutting a release](deployment.md#cutting-a-release)); `:stable` keeps naming the
   previous release. **Do not run `promote.yml` before §5 step 17**: an old host's scripts refuse a bundle
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
    ls -A /var/iri /var/iri/monitoring                # anything outside the plan makes `check` block
    df -h /var /opt /etc /home                        # room for the three volume exports
    ```

    A drop-in other than the role's two (`10-deploy-account-sandbox.conf`, `20-service-user.conf`) is lost
    by `cleanup` unless you re-create it afterwards from the staged copy.

---

## 3. The release and the order of things

```text
cut release (not promoted) ─► window ─► stop timers ─► rename realm ─► migrate-host-layout.sh phases
   stop · export-volumes · reset-podman · accounts · move · rewrite
                                      ─► Ansible role ─► cleanup ─► import-volumes
                                      ─► hash check ─► promote.yml ─► first deploy ─► verify ─► extractor v2.13.0
```

Why this order:

- **The realm is renamed first**, while the old stack still runs: kcadm needs the running Keycloak
  container, which belongs to `iri` only until the `accounts` phase. The old apps lose their login at that
  moment; the window is declared, and the Hetzner snapshot is the way back.
- **`rewrite` precedes the role and `cleanup` follows it**: the role runs against the moved, rewritten
  tree, and `cleanup` refuses until the `basetool-*` units exist, so the host is never left without a
  unit set.
- **Nothing is promoted and the deploy timer is not started until the host is migrated.** Starting it
  earlier would re-apply the *previous* release's bundle, whose units name the old paths.

---

## 4. Before the window opens (read-only)

Confirm: §2 is complete, the rehearsal passed, the announcement is out, the owner is at the Hetzner
console and Discord portal, and `ssh greluc@<host>` plus `sudo -i` work. Then:

```bash
cd /
M=/home/greluc/basetool-migration
bash "$M/migrate-host-layout.sh" --help
```

---

## 5. The window

Run as root from `/` (`ssh greluc@<host>`, `sudo -i`). Every WRITE waits for the yes. After each
`migrate-host-layout.sh` phase read its closing line: `… nothing to do, already done` or the number of
steps it took; exit `3` means a precondition refused the phase and **nothing was changed**. Each phase
takes `--dry-run` first (prints `would: …`, changes nothing) and is safe to run again after a failure.

1. **Announce the start** to the members and the exchange developers.

2. **Final backup.** **WRITE.**

   ```bash
   systemctl start iri-backup.service
   tail -n 40 /var/log/iri-backup.log
   set -a; . /etc/iri/backup.env; set +a
   restic snapshots --compact | tail -n 5
   ```

   The log must end without `FATAL`, the snapshot count must have risen, and the `edge-certs` and
   `edge-acme-state` lines must say `captured` ([`backup.md` → Verify](backup.md#7-verify)).

3. **Hetzner snapshot** of the production server — the rollback path. **WRITE** (the owner, in the
   console). Record its name and id in the chat. It is crash-consistent (the databases run);
   the backup of step 2 is the logical fallback.

4. **Stop the job timers** and wait for running jobs. **WRITE.**

   ```bash
   systemctl disable --now iri-deploy.timer iri-backup.timer iri-restore-drill.timer iri-container-cleanup.timer
   systemctl is-active iri-deploy.service iri-backup.service iri-restore-drill.service iri-container-cleanup.service
   flock /var/lock/iri-deploy.lock true
   ```

   Every `is-active` must answer `inactive`; `flock` returns once no deploy is running.

5. **Rename the realm.** **WRITE** (Keycloak). The kcadm session is issued by the realm `iri`, whose name
   dies at the rename: the script then exits `1`, says to sign in again, and **continues with what is
   still open** on the second run. Expect that, do not treat it as a failure.

   ```bash
   cd /
   KCCFG=/opt/keycloak/data/tmp/kcadm.config
   KCADM="sudo -u iri podman exec -i keycloak sh -c 'exec /opt/keycloak/bin/kcadm.sh \"\$@\" --config $KCCFG' kcadm"
   sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config truststore \
       --trustpass - /run/secrets/keystore.p12 --config "$KCCFG"
   sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
       --server https://localhost:18443/auth --realm iri --client basetool-provisioner --config "$KCCFG"
   python3 "$M/rename-keycloak-realm.py" --check --kcadm-command "$KCADM"
   python3 "$M/rename-keycloak-realm.py" --apply --kcadm-command "$KCADM"
   ```

   The `--check` run is read-only and must list the open `~` changes (realm, default role, and the
   frontend client's labels when they still carry the old brand) and no `!` line. If `--apply` ends with `FAILED` and the hint to sign in again, log in
   against the **new** realm and run it again:

   ```bash
   sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
       --server https://localhost:18443/auth --realm basetool --client basetool-provisioner --config "$KCCFG"
   python3 "$M/rename-keycloak-realm.py" --apply --kcadm-command "$KCADM"
   python3 "$M/rename-keycloak-realm.py" --check --kcadm-command "$KCADM"   # "Nothing to do"
   sudo -u iri podman exec keycloak rm -f "$KCCFG"
   curl -fsS https://profit-base.online/auth/realms/basetool/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
   curl -s -o /dev/null -w '%{http_code}\n' https://profit-base.online/auth/realms/iri/.well-known/openid-configuration
   ```

   The issuer must end in `/realms/basetool`, and the old realm must answer `404`. The final `--apply`
   prints the read-back (`realm 'basetool' exists`, `the default role is 'default-roles-basetool'`); if
   it reports a `!` for the default role, **stop and restore the snapshot** — the app code assumes that
   name (`KeycloakService`, the provisioner).

6. **`check`** — read-only, but it is the gate. **Exit `3` or any `[block]` line: stop.**

   ```bash
   bash "$M/migrate-host-layout.sh" check
   ```

   It prints the tools, the accounts, the units, every move with its state, the env and realm-export
   rewrites it would do (counts only, never a value), the label rules, the staging directory and every
   file that still names the old layout.

7. **`stop`** — disables the old timers and services, waits for running jobs, stops Alloy and the service
   user's manager (the stack goes down here), parks the Quadlet units except the three `.volume` ones.
   **WRITE.**

   ```bash
   bash "$M/migrate-host-layout.sh" stop
   ```

8. **`export-volumes`** — exports the three edge volumes as `iri` into the root-only
   `/root/basetool-migration/volumes/`, each with a `.sha256`. **WRITE.** The exports hold private keys.

   ```bash
   bash "$M/migrate-host-layout.sh" export-volumes
   ls -l /root/basetool-migration/volumes
   ```

9. **`reset-podman`** — `podman system reset --force` as `iri`: **deletes every image, container,
   volume and network of that user**; the next phase may only start after the three exports are
   verified, which the script checks. **WRITE — destructive, named in the approval.**

   ```bash
   bash "$M/migrate-host-layout.sh" reset-podman
   ```

10. **`accounts`** — renames group and user (uid/gid kept), the entries in `/etc/subuid` and
    `/etc/subgid` (same range), moves the home, and enables linger for `basetool`. **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" accounts
    getent passwd basetool; getent group basetool; grep '^basetool:' /etc/subuid /etc/subgid
    ls -ld /home/basetool; ls /var/lib/systemd/linger
    ```

    Expect `basetool:x:994:994::/home/basetool:/sbin/nologin`, `basetool:100000:65536` twice and a
    `basetool` entry in the linger directory.

11. **`move`** — every directory and file of §1 to its new place on the same filesystem (renames: owners,
    modes and labels travel), the `deploy` home, the seven new SELinux rules, the two old ones deleted,
    `restorecon -RF` on the five roots. It refuses, and prints the table, while a row is in conflict.
    **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" move
    ```

12. **`rewrite`** — renames the `IRI_*` keys, maps the old paths and the realm in `basetool.env`, its
    `.env.backup-*` copies and `/etc/basetool/deploy/backup.env`, rewrites `realm-export.json`; stages
    the originals under `/root/basetool-migration/originals/` and parks the old rollback anchors
    (`config-previous`, `config-stage`, `previous-digest-pin.yml`, `keycloak-spi-previous.jar`) under
    `anchors/`. It refuses before writing anything if one file cannot be rewritten (an unmappable old
    path, a retired key such as `IRI_MONITORING_DIR`). **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" rewrite
    ```

    Decide a refusal by hand, then run it again. The output carries counts and key names only.

13. **The Ansible role** — full, untagged, from the WSL checkout of the tag (step 8 of §2); it creates the
    new directories' owners and modes, installs the scripts, the `basetool-*` units with their drop-ins,
    logrotate and tmpfiles files, the sudoers rule for `(basetool)`, the Alloy drop-in, the node-exporter
    flag, and starts the metric timers. **WRITE.**

    ```bash
    ansible-playbook site.yml --limit production -K --check --diff
    ansible-playbook site.yml --limit production -K
    ansible-playbook site.yml --limit production -K            # must report changed=0
    ```

    On testing, re-create `basetool-deploy.service.d/override.conf` now.

14. **`cleanup`** — stages, then removes, the old `iri-*` units, timers, drop-ins, `*.wants` links,
    logrotate and tmpfiles files, the old locks and the old linger file; `daemon-reload`; drops the old
    label rules; removes the empty `/var/iri` tree. It refuses until `basetool-deploy.service` and
    `.timer` are installed and `basetool.env` has no `IRI_` key. If it ends `FATAL … still holds the
    entries above`, move or remove what it lists by hand and run it again. **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" cleanup
    ```

15. **`import-volumes`** — starts the user manager, creates the three volumes through their Quadlet
    units and imports the exports (one marker per volume, never into a non-empty volume). **WRITE.**

    ```bash
    bash "$M/migrate-host-layout.sh" import-volumes
    BASETOOL_UID=$(id -u basetool)
    UCTL="sudo -u basetool XDG_RUNTIME_DIR=/run/user/${BASETOOL_UID} systemctl --user"
    UPOD="sudo -u basetool podman"
    ${UPOD} volume ls
    for v in edge-certs edge-acme-state edge-acme-webroot; do ls -ln "$(${UPOD} volume inspect "$v" --format '{{.Mountpoint}}')" | head -n 5; done
    ```

    The listing must show the three volumes and file owners inside `100000-165535` (the translated
    container uids).

16. **Host scripts match the tag** — the mandatory comparison of
    [`deployment.md` → Before a promotion: the host scripts](deployment.md#before-a-promotion-the-host-scripts),
    steps 1 to 3, against `/opt/basetool/scripts`. No output from `diff` is a match.

17. **Promote** — **WRITE** (the required reviewer on the `production` environment approves).

    ```bash
    gh workflow run promote.yml -f version=<version>
    ```

18. **First deploy** — **WRITE.** The new deploy timer is enabled but not started; run one tick by hand
    and read it to the end.

    ```bash
    systemctl start basetool-deploy.service
    tail -f /var/log/basetool/deploy.log
    ```

    It ends with `deploy successful`. It renders `/etc/basetool/env.d`, installs the units into
    `/etc/containers/systemd/users/994/`, pulls the pinned images (the Podman store was reset) and
    starts the stack. Then start the timers: **WRITE.**

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
2. **Units and timers:** `systemctl list-timers 'basetool-*' --all`; `systemctl list-unit-files 'iri-*'`
   prints nothing; `${UCTL} list-units --state=failed` prints nothing.
3. **Containers:** `${UPOD} ps --format '{{.Names}} {{.Status}}'` — every service up, the app services
   `healthy`.
4. **Layout:** `stat -c '%U:%G %a %n' /etc/basetool /etc/basetool/basetool.env /etc/basetool/deploy
   /opt/basetool/scripts /var/lib/basetool /var/log/basetool` against
   [`deployment.md` → Secrets and host-only files](deployment.md#secrets-and-host-only-files); no `/var/iri`,
   `/etc/iri`, `/var/lib/iri`, `/home/iri` remains. `ls -Z /opt/basetool/scripts` is `bin_t`, a host-script
   log under `/var/log/basetool` is `var_log_t`, `ls -dZ /var/log/basetool/backend` is `container_file_t`.
5. **Issuer:** `curl -fsS https://profit-base.online/auth/realms/basetool/.well-known/openid-configuration |
   grep -o '"issuer":"[^"]*"'` ends in `/realms/basetool`; `…/realms/iri/…` answers `404`.
6. **A Discord login** by the owner, end to end, and an admin page that reads the realm's roles.
7. **Backup and restore drill** — **WRITE:** `systemctl start basetool-backup.service`, read
   `/var/log/basetool/backup.log` (the first run is slower: the restic cache is new), then
   `systemctl start basetool-restore-drill.service` and read `restore-drill.log`; the drill picks the
   newest, new-layout snapshot.
8. **Alerts quiet:** Grafana → Alerting shows nothing firing that is not explained; `DeployFailed`,
   `TargetDown`, `HostScriptsCheckStale` and `RestoreDrillArtifactNotRestorable` are the ones to look at.
9. **Alloy tails the new paths:** `systemctl is-active alloy`; Grafana → Explore → Loki
   `{app=~"ops-.*"}` shows lines from the last minutes for `ops-deploy` and `ops-backup`
   (`/hostlog/basetool/<x>.log`); `{app="backend"}` shows current lines. Alloy re-reads files from the new
   paths, so a burst of older lines on the first pass is expected.
10. **Node-exporter textfiles:** `grep -o -- '--collector.textfile.directory=[^ "]*' /etc/default/prometheus-node-exporter`
    names `/var/lib/basetool/textfile`; `ls -l /var/lib/basetool/textfile/*.prom` shows files younger than
    a few minutes (deploy, backup, cert-expiry, container metrics).
11. **Logrotate:** `logrotate -d /etc/logrotate.d/basetool-deploy` reports no error, in particular no
    "insecure permissions"; the host-script logs stay `var_log_t` so it can rotate them.
12. **Conformance:** from the workstation, `python scripts/check-conformance.py --ssh greluc@<host> --sudo`.
13. **Then** publish the SC extractor **v2.13.0** and tell the exchange developers it is done.

---

## 7. Rollback

**Primary path: restore the Hetzner snapshot of §5 step 3** (rebuild the existing server from it in the
console; it keeps its address). That returns the accounts, the Podman store, the databases and the realm
`iri` to the moment of the snapshot; the old Discord redirect URI is still registered, which is why §9
removes it last. After the restore: if `promote.yml` already moved `:stable`, promote the previous version
again (`gh workflow run promote.yml -f version=<previous>`) before the old deploy timer ticks, announce
that the cutover is postponed. The snapshot loses everything written after it — which is why the window
is announced as a full stop and the final backup (step 2) precedes it.

**Partial manual undo.** The migration has **no undo phase**; the staging directory
`/root/basetool-migration` (root-only) keeps what a manual undo needs: `state/` (phase markers),
`volumes/` (the three exports), `originals/` (the files before `rewrite`), `anchors/` (the parked rollback
anchors), `units/` (the parked Quadlet units), `old-files/` (the removed `iri-*` files),
`user-containers-config.tar`. By phase:

| Failed at | State | Way back |
|---|---|---|
| realm rename (step 5) | realm renamed, host untouched | no reverse script; restore the snapshot, or go forward and finish the host |
| `stop`, `export-volumes` | timers/units stopped, volumes exported | `systemctl enable --now` the four `iri-*` timers, start the user manager; the stack comes back from the parked units in `units/` |
| `reset-podman` | the old Podman store is gone | forward only (the exports re-create the volumes), or the snapshot |
| `accounts`, `move`, `rewrite` | partly renamed | forward: every phase resumes where it stopped; backwards by hand is the reverse of the §1 table — untested, prefer the snapshot |
| role, `cleanup`, `import-volumes` | new units present | forward; `old-files/` holds the removed files |

The old rollback anchors were parked on purpose (they name units with the old paths), so the first
deploy of the new release has no earlier release to return to: a failure there is solved forward, or by
the snapshot.

---

## 8. Restoring a backup taken before the migration

Every restic snapshot up to the migration was backed up from `/var/iri/backup/staging/<TS>` and carries
the old environment keys, the old paths and the realm `iri`. `restore-drill.sh` is unaffected (it finds
artifacts by name). A manual restore ([`backup.md` → Restoring](backup.md#restoring-disaster-recovery))
works with three additions:

1. `R=$(dirname "$(find /var/lib/basetool/backup/restore -name krt_basetool.dump -print -quit)")` finds the
   timestamped directory whatever path the snapshot recorded (the glob form fails for these snapshots).
2. **Before** step 2 of the restore, rewrite the two restored host-config files in place with the helper
   (it keeps mode, owner and ACL, writes atomically and never prints a value; `--check` first):

   ```bash
   python3 -I "$M/migrate-host-env.py" env --check "${R}/config/dotenv"
   python3 -I "$M/migrate-host-env.py" env "${R}/config/dotenv"
   python3 -I "$M/migrate-host-env.py" realm-export "${R}/config/realm-export.json"
   ```

   Exit `3` names a key or path it cannot map ("decide by hand"); `config/internal-tls.tar`,
   `monitoring/secrets.tar.gz`, `providers.tar.gz` and `users.acl` need nothing.
3. The Keycloak dump holds the realm `iri`. At step 6, start `keycloak.service` alone, rename the realm
   with the §5 step 5 commands (kcadm session `--realm iri`, then `--realm basetool`), and only then start
   backend, ingest and frontend. Sessions are void either way.

(The tools are on the host only while `basetool-migration/` in the operator's home stays; copy them again
from a tag checkout as in §2 step 9 when it is gone.)

---

## 9. After the cutover

1. **Discord:** remove the old redirect URI `…/realms/iri/broker/discord/endpoint` after a week without
   incident (the owner, in the portal). **WRITE.**
2. **Keycloak:** disable or delete the client `basetool-provisioner`
   ([`keycloak/README.md`](keycloak/README.md#why-a-service-account-and-not-the-admin-user)). **WRITE.**
3. **Staging:** delete `/root/basetool-migration` and the operator's `basetool-migration/` copy after 14
   days at most — the volume exports hold private keys (`shred -u` the tar files, then `rm -rf`).
   **WRITE.**
4. **Hetzner snapshot:** delete it once §6 has held for the same period (the owner).
5. **Testing host:** it was migrated by the rehearsal; delete its Proxmox snapshot after the same period.
6. **Record the outcome:** add a short outcome section here (date, duration, anything that differed), then
   archive this runbook ([`archive/README.md`](archive/README.md)). Open the follow-up to remove
   `scripts/migrate-host-layout.sh`, `migrate-host-env.py`, `rename-keycloak-realm.py` and their tests once
   no host and no restorable snapshot needs them (the last pre-migration snapshot ages out of the GFS
   retention after six months).
