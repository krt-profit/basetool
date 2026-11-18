> **Archived 2026-11-18.** Doc type: Historical record — frozen, kept as a record and no longer updated. Commands and states below describe the hosts and the code as they were.
>
> **Current truth:** [`deployment.md`](../deployment.md). Index of the archive: [`README.md`](README.md).

# Release 1.26.0 — rollout runbook (testing and production)

> **Doc type:** Operator runbook for **one** release, the move of both hosts from **v1.25.0 to
> v1.26.0**, together with the host maintenance that has to happen around it:
> - the session store as a Redis of its own (ADR-0388, #3497) — a cut-over with owner steps;
> - Rocky Linux 10.3 as a **full** upgrade, Podman 6 included (#3516, ADR-0199), and the
>   `containers.conf.d` drop-in it needs (#3521);
> - host scripts that changed (#3500, #3497, #3538, #3498).
>
> Written 2026-11-18 from the diff of `v1.25.0` against `main` at `b9ebb0ab4` (#3445–#3540) and a
> read-only look at both hosts that day (both on v1.25.0, Podman 5.8.2, kernel 211.74.1 running,
> 264.16.1 installed, the `redis` unit holding 35–41 MB). The version number assumes *Release ·
> Prepare* runs with `1.26.0`. **Historical once 1.26.0 is live on both hosts and §6 is done**: move it
> to `docs/archive/` then and let [`deployment.md`](../deployment.md) stay the living truth.
>
> **Every step that writes to production is a production write.** It waits for @greluc's explicit
> yes, in chat, to that exact step (AGENTS.md → *Production host access*). Nothing here is approval.

Shell conventions (`cd /`, `${UCTL}`, `${UPOD}`) are the ones in
[`deployment.md` → Shell conventions](../deployment.md#shell-conventions-used-below). SSH to both hosts
goes over the tailnet; the hosts' public or LAN addresses do not answer on port 22. The Ansible
controller's inventory points at the tailnet names, and its key is admitted from the controller's
tailnet address on both hosts.

---

## 0. What changes

| | Value |
|---|---|
| Range | `v1.25.0` (2026-11-15) to `main` at `b9ebb0ab4` |
| Redis | `redis` and `redis-exporter` retire; `redis-session` (keeps `/var/lib/basetool/redis`, the sessions survive), `redis-shared` (starts empty in `/var/lib/basetool/redis-shared`) and an exporter for each start. ACL files move to the root-owned `/etc/basetool/redis/`. The gateway runs no Lua any more (ADR-0389) |
| New secrets | `REDIS_SESSION_PASSWORD`, `REDIS_SESSION_EXPORTER_PASSWORD`, `REDIS_SESSION_FRONTEND_PASSWORD` in `basetool.env`, generated on the host |
| Units | new `redis-session`, `redis-shared`, `redis-session-exporter`, `redis-shared-exporter`, `net-redis-session`, `net-monitoring-redis`; retired `redis`, `redis-exporter`. `deploy.sh` retires them inside the restart window (#3500) |
| JVM | `-XX:-UseBASE64Intrinsics` for the apps and Keycloak (#3534). Production's four hand-installed `50-base64-intrinsics.conf` drop-ins set the same value and stay until the bump that removes the flag |
| Flyway | 7, `V432`–`V438` (head before: `V431`). New tables and columns; `V432`/`V433` replace a check constraint |
| Host scripts | changed: `deploy.sh`, `lib/*.sh`, `render-redis-acl.py`, `render-env-d.py`, the two new ACL templates (the old `redis-users.acl.tmpl` is gone), and formatting-only changes. §3.2 is mandatory |
| Ansible | `50-podman.yml` (drop-in instead of the main `containers.conf`), new directories `/etc/basetool/redis` and `/var/lib/basetool/redis-shared`, the ACL templates |
| OS | Rocky 10.3 full upgrade: Podman 6.1.1, netavark/aardvark-dns 2.1.0, passt 0^20260728, kernel 264.16.1, then a reboot |
| Rollback | §7. The release is not rolled back by `dnf`; Podman 6 is not downgraded |

## 1. Order

Testing first, every phase; production follows only when testing is verified.

1. **§2** cut the release (no host is touched).
2. **§3** per host: role run for the Podman drop-in, full `dnf upgrade`, reboot, verify.
3. **§4** per host: role run for the release's scripts and directories, the Redis cut-over before the
   deploy, the promotion and a manual deploy, verify, the cut-over after the deploy.
4. **§5** after both hosts.

The OS upgrade and the release stay separate, so a failure points to one of them.

## 2. Cut the release

1. *Actions → Release · Prepare* with `1.26.0`; merge the `release/v1.26.0` PR; wait for
   `release-images.yml` on the tag.
2. Export the tag for the Ansible controller (`ansible/`, `scripts/`, `monitoring/` of `v1.26.0`,
   plus the private inventory), so every role run below installs the tag's bytes.

Nothing is deployed: `:stable` and `:testing` still name 1.25.0.

## 3. Rocky 10.3, full upgrade (per host)

### 3.1 The Podman 6 drop-in

From the tag's `ansible/`:

```bash
ansible-playbook site.yml --limit <host> --tags podman -K --check --diff
ansible-playbook site.yml --limit <host> --tags podman -K
```

It writes `~basetool/.config/containers/containers.conf.d/50-basetool.conf` and removes
`~basetool/.config/containers/containers.conf`. The running pasta keeps its arguments until the
reboot in §3.2.

### 3.2 Upgrade and reboot

```bash
sudo dnf upgrade --refresh
sudo systemctl reboot
```

Read the transaction before confirming it: Podman 6.1.1, the kernel 264.16.1 and the runtime
packages ADR-0199's dnf-automatic excludes are expected. The stack comes back on its own
(lingering `basetool`, `WantedBy=default.target`), in about three minutes.

### 3.3 Verify (read-only)

- `rpm -q podman netavark passt containers-common` names the 10.3 versions, `uname -r` is
  `6.12.0-264.16.1.el10_3.x86_64`;
- all containers are up and healthy, `systemctl --failed` is empty;
- the `pasta` process carries `--map-guest-addr 169.254.1.2` and `--map-guest-addr fd00:169:254::2`;
- `python scripts/check-conformance.py --ssh <host>` passes `pasta-maps-host-aliases`. The Redis
  checks fail until §4, because `main` already expects the split. `host-exporter-versions` failed on
  both hosts before the upgrade (alloy 1.21.3 against the pin 1.22.0); the upgrade takes alloy from
  `rpm.grafana.com`, and a version other than the pin afterwards is a pin change, not a blocker;
- `basetool_host_reboot_required` is 0; `/dev/shm` holds no `libpod_rootless_lock` of the `deploy`
  account.

## 4. The release (per host)

### 4.1 Stop the deploy timer

```bash
sudo systemctl stop basetool-deploy.timer
```

It stays stopped until §4.6. A tick between the password step and the deploy would render `env.d/`
with the new passwords for a release that does not read them yet.

### 4.2 Host scripts and directories

From the tag's `ansible/`:

```bash
ansible-playbook site.yml --limit <host> --tags directories,selinux,deploy,scripts,observability,updates -K --check --diff
ansible-playbook site.yml --limit <host> --tags directories,selinux,deploy,scripts,observability,updates -K
```

`observability` and `updates` are needed here: `cert-expiry-metrics.py`, `cgroup-container-metrics.py`
and `host-updates-metrics.sh` changed in this range, and only those two tags install them. The old
`redis-users.acl.tmpl` stays on the host as a `>` line; it is not in the list and does not block.

Then the comparison of [`deployment.md` → Before a promotion](../deployment.md#before-a-promotion-the-host-scripts)
against `v1.26.0`; a `<` line blocks the promotion.

### 4.3 Redis cut-over, before the deploy

Steps 0, 1 and 3 of
[`deployment.md` → Cut-over](../deployment.md#cut-over-the-session-store-gets-its-own-instance-adr-0388-needs-the-owners-yes):
the memory check (`used_memory` below 300000000), the three passwords, both ACL files rendered as
new files with `restorecon`. Step 2 is §4.2. `REDIS_DEFAULT_USER` must be set in `basetool.env`
(production: `off`); the renderer refuses an unset value.

### 4.4 Promote

- Testing: `gh workflow run promote-testing.yml -f version=1.26.0`.
- Production: `gh workflow run promote.yml -f version=1.26.0`, then approve on the `production`
  environment. `sync-testing` keeps `:testing` where it is, because testing is already on 1.26.0.

### 4.5 Deploy by hand

```bash
sudo systemctl start basetool-deploy.service
sudo tail -n 40 /var/log/basetool/deploy.log
```

Expected: the five targets of 1.26.0, `redis` and `redis-exporter` retired in the restart window,
the stack healthy, `deploy successful`; the backend logs `Successfully applied 7 migrations … now at
version v438`. Until the backend's reconcile has rewritten the registry into `redis-shared` (at most
60 s) the exchange answers `503 REGISTRY_UNAVAILABLE`.

Verify as in step 5 of the cut-over: `check-conformance.py --ssh <host> --sudo --only
redis-requires-auth`, `--only redis-default-user-off`, `--only redis-session-isolated`; `redis_up`
is 1 for `redis="session"` and `redis="shared"`; a signed-in browser stays signed in, a mission page
receives live updates, a refinery import reaches its review form, an exchange call succeeds a minute
after the deploy.

### 4.6 Redis cut-over, after the deploy

Steps 6, 7 and 8: delete `exchange:*` and `ingest:*` from `redis-session` without printing a key, a
backup snapshot that carries both ACL files, then `systemctl start basetool-deploy.timer`.

## 5. After both hosts

- #3516 gets the outcome; `HostRebootRequired` is silent on both hosts.
- The old `/var/lib/basetool/redis/users.acl` and its backups stay until no rollback to 1.25.0 is
  wanted, then go (cut-over step 8).
- Production's `50-base64-intrinsics.conf` drop-ins stay; they go with the bump that drops the flag.

## 6. Verification record

| Host | §3 OS | §4 release | §4.6 post | By |
|---|---|---|---|---|
| testing | 2026-11-18 10:24 UTC: Rocky 10.3, kernel 264.16.1, Podman 6.1.1, 18 containers healthy, `pasta-maps-host-aliases` and `host-exporter-versions` green | 10:55 UTC: `deploy successful`, `v438`, 20 containers healthy | 11:03 UTC: 0 keys left, snapshot with both ACLs, timer on; no `deploy` lock and no SELinux denial on the next tick | @greluc, Claude |
| production | 2026-11-18 10:34 UTC: the same versions, 18 containers healthy, `pasta-maps-host-aliases` and `host-exporter-versions` green | 11:26 UTC: `deploy successful`, `v438`, 20 containers healthy; `redis-requires-auth`, `redis-default-user-off`, `redis-session-isolated` green | 11:34 UTC: 0 keys left, snapshot with both ACLs, timer on; no `deploy` lock on the next tick | @greluc, Claude |

## 7. Rollback

- **Release:** an application rollback to 1.25.0 brings back `redis` on `/var/lib/basetool/redis`
  with its kept `users.acl`; the sessions survive, the exchange answers `503` until the backend's
  reconcile has run ([`deployment.md` → Cut-over, Rollback](../deployment.md#cut-over-the-session-store-gets-its-own-instance-adr-0388-needs-the-owners-yes)).
  The three new passwords may stay; 1.25.0 does not read them.
- **OS:** boot the previous kernel from the boot menu if 264.16.1 fails. Podman 6 is not downgraded
  in place; a host that cannot run it is restored from the PVE snapshot (testing) or rebuilt
  (production) — so take a PVE snapshot of the testing VM before §3.2.
