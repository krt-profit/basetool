# Profit Basetool — Deployment Runbook

> **Doc type:** Operator runbook — living, kept in sync with `main`. Last reviewed: 2026-09-22.
>
> **Written for the post-cutover host.** Since 2026-09-22 production is `rocky-16gb-nbg1-1`: Rocky
> Linux 10, rootless Podman, every container a Quadlet-generated systemd user unit of the service
> account `iri`. The retired root-Docker-Compose host and its procedures (apt bootstrap, `docker
> compose` restarts, Nginx Proxy Manager, the Docker-bridge admin tunnel) are recorded in
> [`docs/archive/`](archive/README.md) — above all
> [`PODMAN_CUTOVER_RUNBOOK.md`](archive/PODMAN_CUTOVER_RUNBOOK.md) — and nowhere here.

Binding requirements behind this runbook: [`specs/deployment-delivery.md`](specs/deployment-delivery.md)
(`REQ-OPS-*`). The architecture view is [arc42 §7](arc42/07-deployment-view.md). Backups and
disaster recovery: [`backup.md`](backup.md). The monitoring plane:
[`monitoring/README.md`](../monitoring/README.md). Host provisioning:
[`ansible/README.md`](../ansible/README.md). Decisions:
[ADR-0049](adr/0049-config-as-promotable-oci-artifact.md) (config bundle),
[ADR-0055](adr/0055-keycloak-spi-jar-as-promotable-oci-artifact.md) (provider JAR),
[ADR-0075](adr/0075-host-side-cosign-signature-verification.md) (host cosign gate),
[ADR-0162](adr/0162-edge-is-native-nginx-with-a-separate-acme-client.md) (edge),
[ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) (rootless Podman),
[ADR-0187](adr/0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md) (PROXY-protocol front end),
[ADR-0188](adr/0188-the-host-bootstrap-is-an-ansible-role.md) (Ansible bootstrap),
[ADR-0189](adr/0189-stateful-containers-run-as-their-own-uid.md) /
[ADR-0190](adr/0190-every-container-but-keycloak-runs-read-only.md) (container posture),
[ADR-0194](adr/0194-the-weekly-cleanup-is-runtime-aware-and-drops-volume-pruning-on-podman.md) (cleanup),
[ADR-0196](adr/0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md) (host aliases).

> [!danger] For AI agents: reading the production host is free, writing to it is gated
> Every command below that changes the host — a restart, a file edit, a role run, a deploy — is a
> **write** under the production-host access rule in the repository `CLAUDE.md`, and needs an
> explicit per-action yes from @greluc first. A recipe in this file is documentation, never approval.

---

## Overview

```
┌──────────────────────────────┐        ┌─────────────────────────────┐
│ GitHub Actions               │  push  │ GHCR  ghcr.io/krt-profit/   │
│  release-images.yml          ├───────►│  basetool-backend           │
│   build · scan · sign        │        │  basetool-frontend          │
│  promote.yml (approved)      ├───────►│  basetool-ingest            │
│   re-tag digest → :stable    │        │  basetool-config            │
└──────────────────────────────┘        │  basetool-keycloak-spi      │
                                        └──────────────┬──────────────┘
                                                       │ pull (read-only token)
┌──────────────────────────────────────────────────────▼──────────────┐
│ Production host  rocky-16gb-nbg1-1  (Rocky 10, SELinux enforcing)   │
│                                                                     │
│  iri-deploy.timer (5 min) → deploy.sh  as user `deploy`             │
│     resolve :stable → digests · cosign verify · stage config bundle │
│     install units → /etc/containers/systemd/users/<iri-uid>/        │
│     render env.d  → /var/iri/code/env.d/<svc>.env                   │
│     pin digests   → <svc>.container.d/10-digest-pin.conf            │
│     systemctl --user (as iri) start/restart → wait for healthy      │
│                                                                     │
│  :80/:443 haproxy (host) ──PROXY v2──► edge 127.0.0.1:8080/8443     │
│  18 containers + 18 networks + 3 volumes, all systemd user units    │
│  of `iri`; alloy, node_exporter, podman-exporter on the host        │
└─────────────────────────────────────────────────────────────────────┘
```

- **Pull, never push, for delivery (REQ-OPS-001).** The host holds one read-only GHCR token. There
  is no webhook and no GitHub-issued credential that can run anything on the box. The operator's
  key-only SSH is the administrative entrance, not a delivery path.
- **Promotion is deliberate (REQ-OPS-002).** A `main` merge or a release publishes images; nothing
  moves `:stable` except an approved `promote.yml` run. `:stable` is the only tag production reads.
- **Configuration rides the image channel (REQ-OPS-004, ADR-0049).** The signed `basetool-config`
  bundle (`docker/config/Dockerfile`, `FROM scratch`) carries `docker-compose.yml`,
  `docker-compose.monitoring.yml`, `docker/maintenance`, `docker/edge`, `docker/acme`,
  `keycloak-theme`, `monitoring` and **`quadlet/`** — the unit files and their `env.d` templates.
  It is promoted in lock-step with the app images and the `basetool-keycloak-spi` JAR bundle
  (ADR-0055), so a promoted unit change reaches the host by the next tick.
- **The three app images are one Dockerfile (ADR-0209).** `docker/app/Dockerfile`, built with
  `--build-arg MODULE=backend|frontend|ingest` from the repository root. Each image carries a Java
  AOT cache its build verified; a host that starts one with a different
  `-XX:UseCompactObjectHeaders` (for instance the ADR-0180 rollback through `IRI_EXTRA_JAVA_OPTS`)
  starts without the cache — slower, not broken — and `JvmStartupCacheRejected` says so.
- **Provisioning is separate from delivery (ADR-0188).** Packages, users, directories, SELinux,
  firewall, haproxy, the host monitoring services and the operational scripts and timers come from
  the Ansible role in [`ansible/`](../ansible/README.md), run by an operator. It never deploys a
  release.
- **Secrets never travel.** `.env`, the keystore, the realm export, the Redis ACL and the monitoring
  secrets exist only on the host (REQ-OPS-005); the bundle is asserted secret-free in CI and again
  by `deploy.sh` before it is applied.

---

## The host

### Accounts and what runs where

| Who | What it is | What it owns |
|---|---|---|
| `root` | the operator's SSH login | haproxy, alloy, node_exporter, fail2ban, firewalld, the `iri-*` system units |
| `deploy` | system account, `/sbin/nologin`, home `/var/lib/iri` | runs `deploy.sh`, `backup.sh`, `restore-drill.sh`, `container-cleanup.sh`; owns `/var/lib/iri`, `/etc/iri`, `/var/iri/code`, the unit directory |
| `iri` | the rootless service user (lingering, subuid base 100000) | the container store and all 39 Quadlet units; the podman-exporter user unit |

`deploy` reaches `iri`'s containers only through `/etc/sudoers.d/basetool-deploy`: `podman *` and
`systemctl --user *` as `iri`, plus `systemctl restart alloy.service` as root, nothing else
(`ansible/roles/basetool_host/tasks/22-deploy-user.yml`). That is strictly narrower than the
`docker` group of the retired host, which was root-equivalent.

**Container uids are translated.** Container uid *N* is host uid `100000 + N − 1`: 10001 → 110000
(backend, frontend, ingest, loki, tempo), 1000 → 100999 (keycloak), 999 → 100998 (redis),
70 → 100069 (postgres), 101 → 100100 (edge), 472 → 100471 (grafana), 65534 → 165533 (prometheus,
alertmanager, exporters). Derive, never transcribe:
`B=$(grep '^iri:' /etc/subuid | cut -d: -f2); echo $((B + 10001 - 1))`.

**The timers** (all system units, installed by the role; logs in `/var/log/iri-*.log`, in Loki as
`{app="ops-*"}`):

| Timer | When | Runs |
|---|---|---|
| `iri-deploy.timer` | 5 min after boot, then every 5 min | `deploy.sh` as `deploy` |
| `iri-backup.timer` | daily 04:15 | `backup.sh` — see [`backup.md`](backup.md) |
| `iri-restore-drill.timer` | Sun 05:30 | `restore-drill.sh` — see [`backup.md`](backup.md) |
| `iri-container-cleanup.timer` | Sat 02:00 UTC | `container-cleanup.sh` — see [Weekly cleanup](#weekly-container-cleanup) |
| `iri-cert-expiry.timer` | daily 03:40 | `cert-expiry-metrics.py` as root |
| `iri-container-metrics.timer` | every 30 s | `cgroup-container-metrics.py` as root |

**Management access** is key-only SSH as `root`; `cloud-init/hetzner-rocky10.yaml` also creates a
key-only `sysadm`. There is **no VPN**: a read-only check on 2026-09-22 found no WireGuard interface
and no `/etc/wireguard` on the production host. fail2ban guards SSH; firewalld is default-deny and
opens SSH, 80 and 443 only; the provider's cloud firewall sits in front of it, deliberately
([`PODMAN_HOST_BOOTSTRAP.md` §10](archive/PODMAN_HOST_BOOTSTRAP.md)).

### Shell conventions used below

Run from `/`, as root. `sudo -u <user>` keeps the caller's working directory, and neither `deploy`
nor `iri` can enter `/root` — from there every rootless call fails with `cannot chdir to /root:
Permission denied`, which the runtime detection reports as "no lingering user could be found".
`deploy.sh` and `container-cleanup.sh` change to `/` themselves (deploy.sh since 2026-09-22), so for
them the `cd /` below is belt and braces; for the ad-hoc `${UCTL}` / `${UPOD}` commands it is not.

```bash
cd /
IRI_UID=$(id -u iri)                                                   # 994 on production
UCTL="sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} systemctl --user"
UPOD="sudo -u iri podman"
```

### Bootstrapping a host

1. **Create the machine** with [`ansible/cloud-init/hetzner-rocky10.yaml`](../ansible/cloud-init/hetzner-rocky10.yaml)
   and decide the disk layout at creation time.
2. **Run the role, without a tag limit**, from WSL or a Linux controller:
   [`ansible/README.md`](../ansible/README.md). Expect `failed=0`, then `changed=0` on a second run.
   The inventory must list the host's own public names in `basetool_host_public_name_aliases`
   (ADR-0196): a rootless container cannot reach the host through its public address, so without
   them the apps cannot load the OIDC issuer and never become healthy.
3. **Place the operator-provided files** in the next section. Podman refuses a missing bind-mount
   source outright (`statfs …: no such file or directory`), so every file in the table has to exist
   before the first deploy.
4. **First deploy:**

   ```bash
   systemctl start iri-deploy.service
   systemctl show iri-deploy.service -p Result --value      # success
   tail -40 /var/log/iri-deploy.log
   ```

   Expect `container runtime: podman`, five `signature OK`, the config bundle staged,
   `quadlet units: 39 installed/updated, 0 retired`, `applying`, `deploy successful`.

   > [!note] The first deploy has no rollback anchor
   > A health failure on a fresh host ends with `no previous pin available — manual intervention
   > required`: there is nothing to roll back to. Fix the cause and re-run with `--force` — the
   > failed attempt recorded a backoff for that target:
   > `cd / && sudo -u deploy /var/iri/code/scripts/deploy.sh --force`.

5. **Start the front end.** The role installs, configures and **enables** haproxy but does not start
   it; nothing answers on 80/443 until it runs (it starts by itself on every later boot):

   ```bash
   systemctl start haproxy && systemctl is-active haproxy
   ss -tlnp | grep -E ':80 |:443 '          # haproxy on both, IPv4 and IPv6
   ```

   A `wrong version number` from `curl` means the edge's `EDGE_TRUSTED_PROXY` is empty or wrong —
   see [The edge](#the-edge).
6. **Start the timers** (the role enables them for the next boot, it does not start them):
   `systemctl start iri-deploy.timer iri-backup.timer iri-restore-drill.timer iri-container-cleanup.timer`.

   > [!danger] On a host rebuilt for disaster recovery, not the backup timer — not yet
   > Every host writes to the same restic repository and the restore takes `latest`. A backup from
   > a host whose data has not been restored yet becomes `latest` and is what the next restore
   > would bring back. Restore first ([`backup.md` → *Restoring*](backup.md#restoring-disaster-recovery)),
   > compare, then start `iri-backup.timer`.
7. **Accept the host** with the conformance suite, from a checkout on the workstation — as `root`,
   or the container checks see an empty store:

   ```bash
   python scripts/check-conformance.py --ssh root@<host>
   ```

   `client-address-visible` and the other external checks only mean something once DNS points at
   the host. `rate-limit-active` is opt-in (`--include-load`).

A unit a release adds later is enabled but not started until the next boot; `containers-running`
reports it as absent. Start it with `${UCTL} start <svc>.service`.

### Secrets and host-only files

Shapes and locations only. Values live on the host and in the off-site backup, never here.

| Path | Owner / mode | What | Notes |
|---|---|---|---|
| `/var/iri/code/.env` | `deploy:deploy 0640` | every environment value the stack reads | the role repairs owner/mode; `deploy.sh` reads it, `render-env-d.py` renders `env.d/` from it. Keep it **LF**: a CRLF file hands the pre-flight a path ending in `\r` (`required file missing` for a file that exists). |
| `/var/iri/code/env.d/<svc>.env` | `deploy:iri 0640`, dir `2750` | per-service environment, one closed allow-list each | **generated** on every config change; never edit — edit `.env` |
| `/var/iri/secrets/keystore.p12` | `root:110000 0640` + ACL `u:100999:r`, `u:iri:r` | the shared internal TLS keystore (backend, frontend, ingest, keycloak) | ACL for keycloak and for the backup helper; see [rotation](#internal-keystore-and-certificate-rotation) |
| `/var/iri/secrets/tls/` | `iri:iri 0755`; `<svc>.p12` `0640` (apps `root:110000` + ACL `u:iri:r`, keycloak `root:root` + ACL `u:100999:r`, `u:iri:r`); `truststore.p12`, `ca.crt` `root:root 0644` | the per-service internal TLS material (REQ-SEC-070) — absent on a host until its rollout step 2; **present on production since 2026-09-25**, nothing mounts it before step 3 | minted by the owner with `mint-internal-tls.sh`; see [Internal TLS](#internal-tls-per-service-certificates-from-a-private-ca) |
| `/var/iri/secrets/backend-truststore.p12` | `root:root 0644` (public certificates only) | PKCS#12 trust store for the Keycloak SPI's account-existence precheck (REQ-SEC-022): alias `backend` = the shared certificate, alias `internal-ca` = `/var/iri/secrets/tls/ca.crt` | mounted into keycloak at the path `.env`'s `KRT_BACKEND_TRUSTSTORE_PATH` names (`/run/secrets/backend-truststore.p12` on production) by the hand-installed drop-in `keycloak.container.d/50-backend-truststore.conf`; built 2026-09-25 — see [`DISCORD_KEYCLOAK_SETUP.md` §7.3](keycloak/DISCORD_KEYCLOAK_SETUP.md#73-truststore-for-the-backend-certificate). **Neither the file nor the drop-in is captured by `backup.sh`**: a host rebuilt from backup must rebuild both |
| `/var/iri/code/realm-export.json` | `root:100999` + ACL `u:iri:r` | Keycloak realm seed, bind-mounted into keycloak | only seeds an empty realm; the live realm is in `db-keycloak` |
| `/var/iri/redis/users.acl` | `root:root 0644` | Redis ACL, one user per service, **rendered** by `render-redis-acl.py` (SHA-256 hashes, no password) and **with** a `user default …` line | without that line redis resets `default` to `nopass`; check `grep -c '^user default ' …` = 1 — see [The Redis ACL](#the-redis-acl) |
| `/etc/iri/ghcr-pull-token` | `deploy:deploy 0600`, dir `0700` | classic PAT, `read:packages` only | optional sidecar `ghcr-pull-token.expiry` (ISO date) |
| `/etc/iri/backup.env`, `/etc/iri/rclone.conf` | `deploy`-readable | restic repository, password, rclone remote | [`backup.md`](backup.md) |
| `/var/iri/monitoring/secrets/*`, `/var/iri/monitoring/certs/*` | translated uids | Prometheus web auth, scrape password, Alertmanager routes, `basetool-ca.crt`, Grafana's own cert | [`monitoring/README.md`](../monitoring/README.md) |
| `/var/iri/code/keycloak/providers/keycloak-spi.jar` | `deploy`, `0644` | Discord SPI | delivered by `deploy.sh`; manual fallback [below](#keycloak-provider-jar) |
| volumes `edge-certs`, `edge-acme-state` | `iri`'s store, files uid 100100 | public TLS certificates, the ACME account | **in no volume prune, ever** — see [Weekly cleanup](#weekly-container-cleanup) |
| `/var/lib/iri/.docker/config.json` | `deploy 0700` | registry credential for `skopeo` and `cosign` | written by `deploy.sh`'s login every tick |

#### The Redis ACL

`/var/iri/redis/users.acl` is the **only** authentication Redis has: `--requirepass` was removed on
2026-09-16, and once `--aclfile` is in play a file without a `default` entry makes Redis reset
`default` to `nopass ~* &* +@all` — the whole session store, OAuth2 refresh tokens included, open on
the internal network (the 2026-07-10 defect).

Since REQ-SEC-068 / ADR-0207 the file is **rendered, never written by hand**:
[`scripts/render-redis-acl.py`](../scripts/render-redis-acl.py) fills
[`scripts/redis-users.acl.tmpl`](../scripts/redis-users.acl.tmpl) — the rules, committed and
reviewed — from `.env`, with every password replaced by its SHA-256 (`#<hex>`), so the file on disk
and in every backup holds no credential. The role installs both next to `render-env-d.py`. It
refuses, writing nothing, when a variable is missing or the result lacks exactly one `default` line.

| User | Password in `.env` | May do |
|---|---|---|
| `default` | `REDIS_PASSWORD` | everything while `REDIS_DEFAULT_USER` is `on` (the default); **nothing** once it is `off` |
| `admin` | `REDIS_PASSWORD` | everything — the operator's user for `ACL LOAD` and inspection; only the redis container's own environment carries it |
| `monitoring` | `REDIS_EXPORTER_PASSWORD` | introspection for `redis-exporter`; no key, no `SCAN` (a key's name is a session id) |
| `basetool-frontend` | `REDIS_FRONTEND_PASSWORD` | `basetool:session:*`, `GETDEL` of `ingest:handoff:*`, the session-event, keyspace-event and live-sync channels, `SCAN`, `INFO` |
| `basetool-backend` | `REDIS_BACKEND_PASSWORD` | publish/subscribe on `basetool:livesync:changed` and `basetool:notify:published`, `INFO`; no key |
| `basetool-ingest` | `REDIS_INGEST_PASSWORD` | `SET`/`RPUSH`/`LPOP`/`EXPIRE`/`DEL` on `ingest:*`, `INFO`; no channel, no `SCAN` |

An application reaches Redis as its own user only when its `REDIS_<SVC>_USERNAME` is set; with it
empty it sends a password-only `AUTH` with the shared `REDIS_PASSWORD`, which is the `default` user —
exactly the pre-rollout behaviour. The server carries `--notify-keyspace-events Egx` itself, so the
frontend no longer needs `CONFIG`, and the unit's health probe is an unauthenticated `PING` that
accepts `NOAUTH`, so it does not care which users exist.

**On production the rollout is done** (2026-09-25, steps 2–5 at ~15:47–15:51 UTC with
`iri-deploy.timer` stopped, exactly as below): six users, backend and frontend connected as their
own users (ingest connects on demand), `REDIS_DEFAULT_USER=off`. Verified: an unauthenticated
`PING` answers `NOAUTH`, a password-only `AUTH` answers `WRONGPASS … user is disabled`, the unit's
health check is healthy and the exporter reports `redis_up 1`. A release rollback now needs
`default` back **on** first (see *Rollback* below).

> [!note] A refused `CONFIG GET` per frontend start — on 1.11.0 only, verified gone on production after the v1.12.0 deploy, 2026-09-25
> On release 1.11.0 the frontend's `TolerantKeyspaceNotificationsAction` still runs Spring
> Session's `CONFIG GET notify-keyspace-events` at every start under its own user; the ACL refuses it
> by design and the action carries on. It shows in `ACL LOG` as `reason=command`,
> `context=toplevel`, object `config|get`, user `basetool-frontend` — measured on production
> 2026-09-25 at a count of **2 per frontend start** — and increments
> `redis_acl_access_denied_cmd_total`; `RedisAclDenials` did not fire for it that day. #2067
> (merged 2026-09-25) sends a `PING` instead under a named user, so from the release that carries it
> **no** refusal is expected at all — verified gone on production after the v1.12.0 deploy, 2026-09-25 (after the frontend's
> restarts at 17:43, 18:01 and 18:03 UTC `ACL LOG` gained no entry; the only two `config|get`
> refusals were more than an hour old, from 1.11.0). On 1.11.0 that one entry is benign; any other `ACL LOG` entry —
> another user, another command, a `key` or `channel` reason, an `auth` refusal — is a real finding.

**Render and apply** (as root, from `/`; `${UCTL}` / `${UPOD}` from
[Shell conventions](#shell-conventions-used-below)):

```bash
cd /
cp -p /var/iri/redis/users.acl /var/iri/redis/users.acl.backup-$(date +%Y%m%d-%H%M%S)
# Render NEXT TO the live file, never onto it -- see "Why .new and cat" below.
/var/iri/code/scripts/render-redis-acl.py --env /var/iri/code/.env \
  --template /var/iri/code/scripts/redis-users.acl.tmpl --out /var/iri/redis/users.acl.new
grep -c '^user default ' /var/iri/redis/users.acl.new             # must be 1
grep -c '>' /var/iri/redis/users.acl.new                           # must be 0: hashes only
cat /var/iri/redis/users.acl.new > /var/iri/redis/users.acl && rm /var/iri/redis/users.acl.new
stat -c '%U:%G %a' /var/iri/redis/users.acl                        # root:root 644, unchanged
# live and atomic -- a malformed file is rejected and the running ACL stays. As `admin` once the
# rendered file has been loaded at least once; the very first load authenticates as `default`
# (drop `--user admin`), because the hand-written file before it has no admin user.
${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL LOAD'   # OK
${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL USERS'  # the users the render printed
```

`ACL LOAD` needs no restart and signs nobody out. `check-conformance.py --only redis-requires-auth`
still proves an unauthenticated `PING` is refused.

**Why `.new` and `cat`** *(corrected 2026-09-25)*. The unit mounts the single file
(`Volume=/var/iri/redis/users.acl:/etc/redis/users.acl:ro`), and a single-file bind mount follows
the **inode**, not the name. `render-redis-acl.py` writes atomically — a temporary file renamed over
the target, i.e. a **new** inode — so rendering straight onto the live path would leave the
container on the old inode, and `ACL LOAD` would quietly reload the **old** rules (the same reason
`grafana.crt` needs an edge restart after a re-mint). Writing the checked result into the existing
file with `cat … >` keeps the inode, its owner, mode and SELinux label, so no `chown`/`restorecon`
is needed and the running container sees the new content. The `ACL USERS` line is the proof: if it
still lists the old set, the content did not reach the container — `${UCTL} restart redis.service`
then loads it from the mount (it also restarts `frontend` and `ingest`, which `Requires=` redis;
sessions survive in the AOF).

> [!warning] Read the ACL log by field, never whole
> `redis-cli ACL LOG` answers what was refused and by whom — and its `object` field is the key or
> channel, which for a session key **is a session id**. Print the usernames and reasons only:
> `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL LOG 20' | awk 'p{print; p=0} /^(username|reason|context)$/{printf "%s: ", $0; p=1}'`.

**No service is refused anything in normal operation — a restart included.** Under its own user
the frontend's startup check is a `PING` and it sends no `CONFIG`; only with an empty
`REDIS_FRONTEND_USERNAME` (i.e. as `default`) does it still run Spring Session's `CONFIG GET` /
`CONFIG SET`. So an `ACL LOG` entry, and every `RedisAclDenials`, is a finding to chase, never
restart noise. *(Since 2026-09-25: before that fix the frontend's `CONFIG GET` was refused on every
start under `basetool-frontend` — the `config|get` entries production's `ACL LOG` showed after the
rollout. They stop with the release carrying it; an older one still produces them, harmlessly.)*

**Rotating a password** is `.env` + render (the `.new` + `cat` block above) + `ACL LOAD` + restarting the one service that uses it
(`render-env-d.py` first, so its `env.d` file carries the new value). Rotating `REDIS_PASSWORD`
touches `default`/`admin` and the redis unit's own environment: render both, `ACL LOAD`, then
`${UCTL} restart redis.service` so the container sees the new `REDIS_PASSWORD` for the next `ACL
LOAD`.

##### Rollout: one ACL user per service (needs the owner's yes)

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
   `ls -l /var/iri/code/scripts/render-redis-acl.py /var/iri/code/scripts/redis-users.acl.tmpl`.

> [!warning] Steps 2–4 are one sitting, with the deploy timer stopped *(corrected 2026-09-25)*
> Step 2 is **not** inert. The env templates pick each service's password with
> `REDIS_PASSWORD=${REDIS_<SVC>_PASSWORD:-${REDIS_PASSWORD…}}` — whether or not its
> `REDIS_<SVC>_USERNAME` is set. So from the moment the three passwords are in `.env`, **any**
> `env.d/` render hands each application its new password with no username: a password-only `AUTH`
> as `default` with the wrong password, `WRONGPASS`, and the service goes unhealthy on its next
> restart. `deploy.sh` re-renders `env.d/` on every config change, so a deploy tick between step 2
> and step 4 is exactly that render. Hence: `systemctl stop iri-deploy.timer` before step 2, run
> steps 2–4 back to back, and `systemctl start iri-deploy.timer` only after step 4 verified (or after
> a rollback). And never add the three passwords before the release carrying REQ-SEC-068 (1.11.0)
> is live.

2. **Three new passwords into `.env`**, generated on the host so they never cross a terminal:

   ```bash
   cd /
   cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
   for v in REDIS_FRONTEND_PASSWORD REDIS_BACKEND_PASSWORD REDIS_INGEST_PASSWORD; do
     grep -q "^${v}=" /var/iri/code/.env || \
       printf '%s=%s\n' "$v" "$(openssl rand -base64 36 | tr -d '/+=\n')" >> /var/iri/code/.env
   done
   grep -c -E '^REDIS_(FRONTEND|BACKEND|INGEST)_PASSWORD=' /var/iri/code/.env   # 3
   ```

3. **Render with `default` still on, and load it** — the block above, first load without `--user
   admin`. Nothing changes for the applications yet; `admin` and the three service users now exist.
   Before rendering, confirm that `.env`'s `REDIS_EXPORTER_PASSWORD` is the password the hand-written
   file gives `monitoring` — the render re-derives that user's hash from `.env`, and a mismatch takes
   `redis-exporter` down (`redis_up == 0`). Compare digests, never the values:
   `grep '^user monitoring ' /var/iri/redis/users.acl | grep -o '>[^ ]*' | cut -c2- | tr -d '\n' | sha256sum`
   against `sed -n 's/^REDIS_EXPORTER_PASSWORD=//p' /var/iri/code/.env | tr -d '"\n' | sha256sum` —
   the two sums must be equal; if they differ, stop and settle which one is right first.
   Check: `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin ACL
   USERS'` lists all six.
4. **Move the applications**, one at a time, watching each come back healthy:

   ```bash
   printf '%s\n' REDIS_BACKEND_USERNAME=basetool-backend REDIS_INGEST_USERNAME=basetool-ingest \
     REDIS_FRONTEND_USERNAME=basetool-frontend >> /var/iri/code/.env
   sudo -u deploy /var/iri/code/scripts/render-env-d.py \
     --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
   ${UCTL} restart backend.service     # live sync + notifications: basetool_redis_fanout_subscribed == 1
   ${UCTL} restart ingest.service      # a desktop import reaches "Import-Link" and opens
   ${UCTL} restart frontend.service    # nobody is signed out: the sessions are in Redis
   ```

   Verify: `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --user admin CLIENT
   LIST' | grep -o 'user=[^ ]*' | sort | uniq -c` shows the three service users and no application
   on `default`; `RedisAclDenials` stays silent; log in, open a mission (live sync), run one
   refinery import. On 1.11.0, `ACL LOG` shows the frontend's refused `config|get` from its
   restart (see the note above; verified gone on production after the v1.12.0 deploy, 2026-09-25); nothing else.
5. **Switch `default` off**: append `REDIS_DEFAULT_USER=off` to `.env`, render, `ACL LOAD` as
   `admin`. Verify `${UPOD} exec redis sh -c 'redis-cli ping'` answers `NOAUTH`,
   `${UPOD} exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'` (password-only, i.e.
   `default`) answers `WRONGPASS … or user is disabled`, `${UPOD} healthcheck run redis` is healthy,
   and `redis-exporter` still scrapes (`redis_up == 1`).

**Rollback**, from any step: step 5 — set `REDIS_DEFAULT_USER=on` (or delete the line), render,
`ACL LOAD`. Step 4 — delete the three `REDIS_*_USERNAME` lines **and** the three
`REDIS_{FRONTEND,BACKEND,INGEST}_PASSWORD` lines, render `env.d/`, restart the three services: they
are back on `default` with the shared password. Step 3 — `cat` the `users.acl.backup-*` back into
`users.acl` (keeping the inode, as above) and `ACL LOAD` (authenticating as `default`); step 2 —
delete the three password lines. *(Corrected 2026-09-25: this used to say the passwords may stay in
`.env` because nothing reads them without the usernames — the templates read them either way, see
the warning above.)* **A release rollback** to a version before REQ-SEC-068 (1.10.0 or older) needs
`default` **on** first: its health check and its applications authenticate as `default`.

---

## How the stack is laid out

### The units are generated, and CI keeps them honest

`docker-compose.yml` and `docker-compose.monitoring.yml` remain the **source**: the local and test
stacks run them directly, and [`scripts/generate-quadlet.py`](../scripts/generate-quadlet.py)
translates them into `quadlet/systemd/` (18 `.container`, 18 `.network`, 3 `.volume`) and
`quadlet/env.d/<svc>.env.tmpl`. Both are committed.

```bash
python scripts/generate-quadlet.py            # regenerate after any compose edit, commit the result
python scripts/generate-quadlet.py --check    # what CI runs: fail on drift
python scripts/generate-quadlet.py --list     # each service's disposition and why
```

`repo-lint.yml`'s `quadlet-drift` check runs `--check` and the translation self-test
(`generate-quadlet.test.sh`). Dispositions: `node-exporter` and `alloy` become host services, the
podman exporter is a user unit the role installs; every other service is a container. (`cadvisor`
and `socket-proxy` were deleted by the translation and removed from the compose file on 2026-09-22.) The edge's six network addresses are pinned by the generator and must
equal the role's `basetool_host_edge_trusted_proxies`; the generator refuses the build otherwise.

What Quadlet cannot interpolate is resolved at generation time: every `*_HOST_PATH`,
`IRI_IMAGE_NAMESPACE` and `IRI_KEYCLOAK_HOST_ALIAS` is baked in at its default. Setting one of them
in the host `.env` changes a Compose stack only; a Quadlet host needs a drop-in, which is what the
role writes for the public-name aliases (`<svc>.container.d/10-host-alias.conf`) and, on a host with
a privately signed edge, the JVM truststore (`20-jvm-truststore.conf`). `check-conformance.py`'s
`env-reaches-the-units` fails when `.env` and the units disagree without a drop-in to explain it.

### On the host

| Path | Written by | Content |
|---|---|---|
| `/var/iri/code/` | `deploy.sh` (bundle) | compose files, `docker/{edge,acme,maintenance}`, `keycloak-theme/`, `monitoring/`, `quadlet/` |
| `/var/iri/code/scripts/` | the role | `deploy.sh`, `backup.sh`, `restore-drill.sh`, `container-cleanup.sh`, `lib/container-runtime.sh`, `render-env-d.py`, `render-redis-acl.py`, `mint-internal-tls.sh`, the two collectors — `root:root 0755`, so `deploy` cannot rewrite its own deployer |
| `/etc/containers/systemd/users/<iri-uid>/` | `deploy.sh` | the 39 units, plus `<svc>.container.d/10-digest-pin.conf` (the release's digest) and the role's host drop-ins |
| `/var/iri/code/env.d/` | `deploy.sh` via `render-env-d.py` | one rendered environment file per service |
| `/var/lib/iri/` | `deploy.sh` | digest-pin record and its predecessor, `last-deployed.digests`, backoff records, `config-stage/`, `config-previous/`, `config-blocked.marker`, `config-apply.incomplete` (only while a config apply is unfinished or could not be undone), `edge/` and `monitoring-reload/` snapshots |

`~iri/.config/containers/systemd/` must stay **empty**: Quadlet searches it before the delivery
directory, so a unit of the same name there silently shadows every release.

**Networks** are 18 `.network` units with pinned `/24` subnets under `172.28.0.0/16` (IPv6 on the
ingress and proxy networks; `Internal=true` on the proxy, database and Redis networks). `deploy.sh`
installs a changed `.network` unit and does not apply it: Quadlet creates networks with `--ignore`, so
an existing one keeps its settings until it is removed and recreated — see *Network changes are
installed, not applied* below, and verify with `${UPOD} network inspect <name>` afterwards.

### The runtime seam

Every container operation in `deploy.sh`, `backup.sh`, `restore-drill.sh` and
`container-cleanup.sh` goes through [`scripts/lib/container-runtime.sh`](../scripts/lib/container-runtime.sh)
(`rt_*`, ADR-0163), which detects the runtime by trying it: it finds the lingering user that owns
the containers and reaches it through the sudoers bridge. Under Podman: tags resolve with
`skopeo inspect`, images are pulled as `iri`, "apply and wait" is `systemctl --user start` or
`restart` (restart for every service whose pin or unit this run changed — `start` on an active unit
re-reads nothing), and the wait is structural: `Notify=healthy` makes each unit `Type=notify`,
bounded by the unit's own `TimeoutStartSec=`. The seam carried a Docker branch beside each
Podman one until 2026-09-22; they were removed with the retired Docker host (OPS-SIMP-01), which keeps
its own copy of the old scripts on its own disk for the way back. [`lib/common.sh`](../scripts/lib/common.sh)
holds the `log`, `fail`, `read_env` and atomic textfile write the four scripts share, and the
monitoring units are derived from the unit directory rather than listed.

---

## Releases and promotion

### Cutting a release

Two phases, PR-based; no hand-pushed tag, no tag ever moved.

1. **Prepare.** *Actions → Release · Prepare → Run workflow*, version without the `v` (e.g.
   `1.9.3`). It cuts `[Unreleased]` into a dated CHANGELOG section, regenerates the CycloneDX SBOMs
   (`*/docs/*-bom.{json,xml}` — release-only artefacts), and opens a `chore(release): vX.Y.Z` PR.
   The SBOMs are always a fresh generation: both SBOM tasks are untracked in the root build and
   the step passes `--no-build-cache`, so a restored Gradle cache cannot hand back an older
   component list, and each module's `verifyCyclonedxBom` fails the run unless its BOM lists
   exactly the resolved runtime classpath, project dependencies such as `logging-support` included
   (REQ-OPS-025).
2. **Merge that PR.** `release-publish.yml` creates the tag once, at the merge commit, publishes the
   GitHub Release with the eight SBOM files (backend, frontend, ingest, keycloak-spi — REQ-OPS-025),
   attests them (REQ-OPS-023), and the tag push fires `release-images.yml`.

The tag run **does not rebuild**: it cosign-verifies and re-tags the `:sha-<short>` digest `main`
already built, so `:X.Y.Z` and `:sha-<short>` are the same bytes (REQ-OPS-021, ADR-0137). Any doubt
falls back to a full build. A `main` push does the same, per image, for every image whose inputs it
did not change (ADR-0210) — so the three `:edge` / `:sha-<short>` images can carry different, earlier
commits' revision labels, and the frontend's version chip can name an earlier commit; the release
commit itself always builds all three, and `promote.yml` orders `:testing` against `:stable` by the
config bundle's revision, which always names the published commit. A `main`-push run whose commit
has been superseded by a newer `main` push before it starts skips entirely (green, "skipped —
superseded" in its summary), so a busy merge day no longer queues one full pipeline per merge; a
skipped commit has no `:sha-<short>` tag. A release commit's run never skips. The tag is created with a short-lived token of the **`basetool-release`
GitHub App** (ADR-0201), minted from the secret `RELEASE_APP_PRIVATE_KEY`: the tag ruleset "Version"
lets only that App and @greluc create `v*` tags, and an App token's events trigger
`release-images.yml` where `GITHUB_TOKEN`'s would not. There is no fallback — without the key the
publish job stops with an error. The manual path is @greluc creating the tag at the release PR's
merge commit and re-running the failed publish job, which then skips the tag and publishes the
rest.

Nothing is deployed yet: `:stable` still names the previous release.

### Promoting to production

> [!note] A release whose PRs need more than this path gets its own runbook
> When a release carries host steps, ordering constraints or switches beyond the promotion below,
> they are collected per release from an audit of its PRs: **1.11.0** →
> [`RELEASE_1.11.0_PRODUCTION_RUNBOOK.md`](RELEASE_1.11.0_PRODUCTION_RUNBOOK.md).

```bash
gh workflow run promote.yml -f version=1.9.3
```

Three gates, in order (REQ-OPS-002, REQ-OPS-024):

1. **Vulnerability scan** of the three app images at the digest the tag resolves to, both
   architectures, failing on a fixed HIGH/CRITICAL finding. Break-glass: `-f allow_vulnerable=true`,
   which is announced in the approval record.
2. **Approval** by the required reviewer on the `production` GitHub Environment (one `approve` job).
3. **Signature** — cosign-verify against the anchored
   `release-images.yml@refs/(heads/main|tags/vMAJOR.MINOR.PATCH)` identity, then re-tag all five
   artifacts to `:stable` in lock-step, `fail-fast`.

`promote.yml` must be dispatched **from `main`**: its first job fails on any other ref, and the
`production` environment accepts deployments from `main` only. The approval gate guards against a
mistaken promotion; the signature gate guards against a tampered image — neither stands in for the
other.

A production promotion also carries `:testing` forward whenever testing would otherwise fall behind
(`sync-testing`, decided by commit ancestry, never by timestamp).

### What happens on the host

Within about five minutes the timer fires `deploy.sh`, which:

1. logs in to GHCR (as `iri` for the pull, as `deploy` for `skopeo`/`cosign`), resolves `:stable`
   for all five artifacts and compares the five-field marker
   `backend|frontend|ingest|config|keycloak-spi` with `/var/lib/iri/last-deployed.digests`;
2. on a match, **verifies the running stack** (REQ-OPS-013): each app service has a container that
   is running and healthy (or still starting) from the target digest. A converged stack is a no-op
   — plus the edge and monitoring reconciles below. A structural divergence (missing container,
   wrong image) is logged as `drift: …` and re-applied — a missing container is **started**, and
   nothing it requires is restarted. A sick container on the **right** image gets a targeted
   restart, never a release rollback (ADR-0083): since 2026-09-25 one `systemctl --user stop` of the
   sick service(s) — which takes down what `Requires=` them, and nothing they require — then a
   `start` of the stack in order, each unit waited for. `health drift resolved` is logged, and the
   healthy heartbeat stamped, only once all of them are up;
3. otherwise **cosign-verifies every digest** (REQ-OPS-015) — a failure aborts before anything is
   pulled or staged; then, if the provider-JAR digest moved, **extracts the JAR** into
   `/var/lib/iri/keycloak-spi-stage.jar`, before anything on the host changes;
4. if the config digest moved: extracts the bundle, asserts it carries no secret, applies the
   stateful-infra gate (below), **checks that `deploy` owns and can write every directory it is
   about to mirror into** (and the compose directory, the unit directory and `env.d/`), snapshots
   the live tree to `config-previous/`, mirrors the new tree into `/var/iri/code`, **renders
   `env.d/`**, installs changed units and stops-then-removes units the release no longer names;
5. writes the digest-pin record and the per-service pin drop-ins — after the config, so a release
   the stateful-infra gate holds back leaves no pin behind — pulls the three app images, swaps a
   moved provider JAR into `keycloak/providers/` (the previous one kept as
   `keycloak-spi-previous.jar`), logs `release parts: …`, and applies the release in **one restart
   window**: one `systemctl --user stop` of every unit it re-defined — re-pinned app services, units
   the config replaced, keycloak for a moved JAR — which takes what `Requires=` them down with them,
   then a `start` of every stack unit in dependency order, each waited for until healthy. Keycloak
   comes up on the new JAR before backend starts, and one health gate covers everything (ADR-0213,
   since 2026-09-25 — until then the JAR was swapped only after this gate, and its keycloak restart
   took the app down a second time; see [Keycloak provider JAR](#keycloak-provider-jar));
6. on success writes the marker, clears the failure records, reconciles the monitoring units and
   the edge (config drift or renewed certificates → edge recreate), and prunes dangling images older
   than 30 days;
7. on a health failure logs which units did not come up and what the release changed, restores the
   previous config tree, the previous units, the previous pin drop-ins **and the previous provider
   JAR** — together, never one without the other — applies them the same way, records an
   exponential backoff for that target (600 s doubling, capped at 6 h; `--force` bypasses it) and
   exits non-zero — `DeployRolledBack` / `DeployFailed`.

**Expected outage per release:** the units the release re-defined and everything that requires them
are down from the one `stop` until their start completes — for a release that moves an app image or
the JAR, about two minutes of maintenance page on production, **once**, whatever the release moves.
Each unit restarts once: until 2026-09-25 the apply restarted re-defined units one by one, and a
`restart` of backend followed by `restart`s of ingest and frontend restarted those two twice
([Driving the stack](#driving-the-stack) explains why a restart does that).

A failure in steps 3–5 **before** the health gate — the pre-flight, an extraction (the config bundle
or the provider JAR), a mirror, the `env.d` render, the unit install, the pull — is recorded the same
way: a `FATAL: deploy aborted
before the health gate — step '…' failed (exit N)` line, the previous config tree, units, pin and
provider JAR put back if this run had changed them, the same backoff record, and `basetool_deploy_last_failure_timestamp`
(`DeployFailed`). Nothing has been restarted at that point, so the stack keeps running the previous
release. If the restore itself fails the log says the tree is **INCONSISTENT**, and
`/var/lib/iri/config-apply.incomplete` stays, which stops the next tick from snapshotting the
half-applied tree over `config-previous/`.

> [!warning] Until 2026-09-25 such a failure was silent
> A command failing under `set -e` ended the run with no FATAL line, no metric, no backoff and no
> restore. With v1.11.0 a root-owned `/var/iri/code/docker/acme` failed the acme mirror (`rsync …
> mkstemp … Permission denied (13)`, exit 23) on every tick from 12:25 to 12:35 after three other
> subtrees had already been mirrored; `deploy.prom` kept `basetool_deploy_last_failure_timestamp 0`,
> `DeployFailed` never fired, and production stayed on the old release until someone looked. The
> second of those ticks also snapshotted the half-mirrored tree as `config-previous/` and saved the
> first tick's new pin as the rollback anchor.

Read the result in `/var/log/iri-deploy.log`, or off-host in Grafana → Explore → Loki with
`{app="ops-deploy"}`. **Not** `journalctl -u iri-deploy.service`: the unit's `StandardOutput=append:`
replaces journald for the script's output, so the journal holds only systemd's start/stop records.

### Promoting to testing

```bash
gh workflow run promote-testing.yml -f version=sha-abc1234
```

Only needed to put testing **ahead** of production. Same signature gate and lock-step as
production, no required reviewer and no vulnerability gate (REQ-OPS-022). The testing host runs the
identical `deploy.sh` and timer with one override:

```ini
# /etc/systemd/system/iri-deploy.service.d/override.conf
[Service]
ExecStart=
ExecStart=/var/iri/code/scripts/deploy.sh --tag testing
```

It serves its own domain from the same bundle: `IRI_KEYCLOAK_HOSTNAME` in its `.env` (with the
`/auth` path) moves Keycloak's hostname and the issuer the apps validate together (ADR-0167).

### Manual deploy, rollback and checks

All as `deploy`, from `/`:

```bash
cd /
sudo -u deploy /var/iri/code/scripts/deploy.sh --check-only     # resolve + cosign-verify, apply nothing
sudo -u deploy /var/iri/code/scripts/deploy.sh                  # one tick now (or: systemctl start iri-deploy.service)
sudo -u deploy /var/iri/code/scripts/deploy.sh --tag 1.9.2      # pin a version for THIS run only
sudo -u deploy /var/iri/code/scripts/deploy.sh --force          # bypass the backoff and the stateful-infra gate
```

`--tag` is not sticky: the next tick resolves `:stable` again. A durable rollback is a promotion:
`gh workflow run promote.yml -f version=1.9.2`. To force a full re-apply of the current target,
delete `/var/lib/iri/last-deployed.digests` and start `iri-deploy.service`; a missing marker also
re-stages the config bundle.

> [!warning] Rolling production back past a host switch *(added 2026-09-25)*
> Some switches applied on production pin the releases that support them, and a rollback below
> them needs the switch undone **first**: to 1.11.0 or older, the internal-TLS step 4
> ([Step 4 → release rollback](#step-4--drop-the-old-certificate)); to 1.10.0 or older, additionally
> Redis `default` back on ([The Redis ACL](#the-redis-acl)) and the confidential frontend client
> back to public ([`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md)).

`--check-only` doubles as the signature preflight in the real `deploy` context and writes no metric.

---

## Driving the stack

A restart **is** a recreate: the generated `ExecStart` carries `--replace`, and every unit applies
its digest-pin drop-in, so a hand-started service always runs the digest the last deploy pinned — the
2026-07-02 "outdated build from the local cache" failure cannot happen through `systemctl`.

```bash
${UCTL} list-units --all '*.service' --no-pager      # the stack as systemd sees it
${UPOD} ps --format '{{.Names}}\t{{.Status}}'        # the containers, with health
${UCTL} status backend.service
${UCTL} restart backend.service                      # blocks until healthy (Notify=healthy)
${UCTL} stop frontend.service
${UCTL} start frontend.service
${UPOD} exec -it db-backend sh                       # a shell in a running container
```

> [!warning] A restart travels along `Requires=` — `restart keycloak.service` is a full outage
> systemd restarts every unit that `Requires=` the one you restart. The units say:
> `keycloak` requires `db-keycloak`; `backend` requires `db-backend` and `keycloak`; `frontend`
> requires `backend`, `keycloak` and `redis`; `ingest` requires `backend` and `redis`. So:
>
> | You restart | Also restarted |
> |---|---|
> | `keycloak.service` | backend, frontend, ingest — **the whole app** |
> | `db-keycloak.service` | keycloak, and through it backend, frontend, ingest |
> | `db-backend.service` | backend, frontend, ingest |
> | `backend.service` | frontend, ingest |
> | `redis.service` | frontend, ingest (sessions survive in the AOF) |
>
> Measured on production 2026-09-25: a `restart keycloak.service` at 15:52:40 UTC took the app down
> until 15:54:50 — about **two minutes**, 175 maintenance-page 502/503/504 answers at the edge;
> frontend and ingest were briefly `failed` and came back by `Restart=always`. Treat every Keycloak
> restart as planned downtime, and do not restart the dependents again afterwards "to be sure".
>
> **The restart returns before the outage ends.** `restart keycloak.service` blocks until
> *keycloak* is healthy, not until the units that require it are. At that moment backend is still
> starting, and frontend and ingest are stopped — **no container at all** — with a start job queued
> behind backend (reproduced under systemd 255 on 2026-09-25; the same day `deploy.sh`'s provider-JAR
> step reported success in that window). To wait for the outage to end, **start** them — a start
> joins the queued job and returns when it is done, while a second `restart` would stop what just
> came up:
>
> ```bash
> ${UCTL} restart keycloak.service
> ${UCTL} start backend.service ingest.service frontend.service   # returns once all three are healthy
> ```
>
> **Restarting several units, restart none of them.** A `restart` of a unit whose start job is
> already running is not merged into it — systemd runs it again — so `restart backend` followed by
> `restart frontend` restarts frontend twice. Stop them all in one call, then start in order; a stop
> merges with anything pending, and each unit goes down and comes up once. This is what `deploy.sh`
> does for a release since 2026-09-25 (ADR-0213):
>
> ```bash
> ${UCTL} stop keycloak.service backend.service       # also stops ingest and frontend: Requires=
> ${UCTL} start keycloak.service backend.service ingest.service frontend.service
> ```

The application stack, in dependency order, is `db-backend db-keycloak redis keycloak backend ingest
frontend edge`, plus `acme`. The monitoring units are `prometheus loki tempo grafana alertmanager
blackbox-exporter postgres-exporter-backend postgres-exporter-keycloak redis-exporter`; `alloy` and
`prometheus-node-exporter` are system services (`systemctl restart alloy.service`).

**Planned downtime.** Stop the timer first — the drift check otherwise brings the stack back on the
next tick — and wait for an in-flight run, which holds the lock for its whole lifetime:

```bash
systemctl stop iri-deploy.timer
flock /var/lock/iri-deploy.lock true               # returns once no deploy is running
for s in edge frontend ingest backend keycloak redis db-keycloak db-backend; do ${UCTL} stop "${s}.service"; done
# … maintenance …
systemctl start iri-deploy.service                 # brings everything back to the pinned state
systemctl start iri-deploy.timer
```

**Reboot.** `iri` lingers and every unit is `WantedBy=default.target`, so the stack starts at boot
without a login; haproxy and the timers are enabled. The first deploy tick follows five minutes
later. A backup, drill or cleanup that fell due while the host was down runs once at boot
(`Persistent=true`), waits for `iri`'s manager and then for its startup, and succeeds; nothing else
runs at boot. Until #2069 all four `iri-*` jobs ran at boot and failed — see *Host patching* in
[Updating the operational scripts and units](#updating-the-operational-scripts-and-units).

### Logs

| What | Where |
|---|---|
| container stdout/stderr | `${UPOD} logs --since 10m backend`, or as root `journalctl CONTAINER_NAME=backend --since -10m` (the units use `LogDriver=journald`); Loki `{app="backend-stdout"}` etc. |
| application file logs | `/var/iri/{backend,frontend,ingest}/log/`, `/var/iri/keycloak/log/keycloak.log`; Loki `{app="backend"}` etc. |
| unit lifecycle | `journalctl --user-unit=backend.service` as root |
| edge access and error log | the edge's stdout: `${UPOD} logs edge`; Loki `{app="edge"}` |
| operational scripts | `/var/log/iri-{deploy,backup,restore-drill,container-cleanup}.log`; Loki `{app="ops-deploy"}`, `ops-backup`, `ops-restore-drill`, `ops-cleanup` |
| haproxy | `journalctl -u haproxy` (it logs to `/dev/log`) |

The GHCR account name is masked in Loki (REQ-OBS-004); only the on-host log file carries it.

---

## Configuration changes that are not app releases

### Host-config and unit changes

A change to either compose file, `docker/edge`, `docker/acme`, the maintenance page, the Keycloak
theme, `monitoring/` or `quadlet/` rides the same path as an app release: regenerate the units if
compose changed (`generate-quadlet.py`), merge, cut a release, promote. The next tick stages the
bundle, installs the changed units and restarts exactly the application services whose definition
moved. Dependabot's image-pin bumps take this path too.

The subtrees a release owns — `docker/` (edge, acme, maintenance), `keycloak-theme/`, `monitoring/`
and `quadlet/` under `/var/iri/code` — must be owned by `deploy` all the way down, because the mirror
(`rsync -rlpt --delete`) creates a temp file in every directory it updates and sets each directory's
mode and mtime. A directory created by hand as root blocks every config change. Since 2026-09-25
`deploy.sh` checks this before it changes anything and refuses with one line naming the directory
(`PRE-FLIGHT: … is not owned or not writable by deploy`); the fix is in
[Troubleshooting](#troubleshooting). The role's `--tags directories` reclaims exactly these four.

> [!note] Monitoring and `acme` units are restarted like the rest — fixed 2026-09-22
> Every unit a release re-defines is **restarted**, every other one merely **started**: the nine
> application services (`db-backend db-keycloak redis keycloak backend ingest frontend edge acme`)
> in the gated apply, the nine monitoring units in the non-gating monitoring apply after it. A
> restarted monitoring unit is restarted once per release, not once per apply pass. Until
> 2026-09-22 this was a known gap (REQ-OPS-013): the monitoring apply only ever said
> `systemctl --user start`, a no-op on an active unit, and `acme` was in neither list, so a changed
> unit was installed and left running its old definition. Prometheus, Alloy and the blackbox
> exporter are additionally recreated when their *config files* change. To confirm a unit change
> landed: `${UPOD} container inspect <svc> --format '{{.ImageName}}'`.

A config-only change still needs a promotion — the
human gate is deliberate (REQ-OPS-002); cut a patch release if it cannot wait.

**A Postgres `-c` flag change auto-applies** and restarts `db-backend` / `db-keycloak` on the next
tick — the stateful-infra gate compares image tags, not command lines. It is safe (runtime settings,
no migration) but it is a database restart, so land it while watching:

```bash
systemctl stop iri-deploy.timer
gh workflow run promote.yml -f version=<version>          # after approval …
cd / && sudo -u deploy /var/iri/code/scripts/deploy.sh --force
${UPOD} exec db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 -c "SHOW shared_buffers; SHOW max_connections;"'
systemctl start iri-deploy.timer
```

`backend` and `keycloak` are not restarted with it; their pools reconnect.

### Session type allow-list: report, then enforce

The frontend reads a stored session value only if the class it names is on `SessionTypeAllowList`
(REQ-SEC-067, ADR-0206). It ships in **`report`** mode — every value is read exactly as before,
and a class outside the list is only counted and logged. Switching production to **`enforce`** is a
`.env` change plus a frontend restart: a production write, so it waits for the owner's yes.

> [!note] Applied on production 2026-09-25, 17:58 UTC — after ~5 hours of report data, not a week
> The owner chose to enforce the same day rather than wait out the week below. The evidence at the
> time: `basetool_session_type_refused_total` had not moved and the log had named no class since
> 1.11.0 went live (12:40 UTC) — about five hours of ordinary use, **not** the seven days the
> precondition asks for, so the classes that only a rarer path writes are covered by the parity
> test and the E2E suite (which runs `enforce`) rather than by production evidence. `.env` had no
> `APP_SESSION_TYPE_ALLOW_LIST` line (the template defaulted to `report`); one line
> `APP_SESSION_TYPE_ALLOW_LIST=enforce` was appended, the render changed only `frontend.env`, and
> the frontend logged `Session type allow-list mode: ENFORCE` with no refusal afterwards. **The
> watch that replaces the missing days:** `SessionTypeOutsideAllowList` and
> `SessionValueDropsSustained` — either firing names a class the list lacks; add it in a PR, or go
> back to `report` with the rollback below while it lands.

**Precondition** — since the release carrying the list went live, over at least a week of ordinary
use (logins, token refresh, a failed form, a refinery import, a live-sync page), the report counter
has stayed at zero and the log names no class:

```text
# Grafana → Explore → Prometheus: must return nothing
sum by (mode) (increase(basetool_session_type_refused_total[7d])) > 0
# Grafana → Explore → Loki, last 7 days: must return nothing
{app="frontend"} |= "not on the session type allow-list"
```

A hit names the class. If it is legitimate, add it to `SessionTypeAllowList` in a PR and restart the
week; do not enforce around it.

**Apply** (as root, from `/`; `${UCTL}` from [Shell conventions](#shell-conventions-used-below)):

```bash
cd /
cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
sudo -u deploy "${EDITOR:-vi}" /var/iri/code/.env      # set APP_SESSION_TYPE_ALLOW_LIST=enforce (one line)
grep -c '^APP_SESSION_TYPE_ALLOW_LIST=' /var/iri/code/.env       # 1
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
grep -c '^APP_SESSION_TYPE_ALLOW_LIST=enforce$' /var/iri/code/env.d/frontend.env   # 1
${UCTL} restart frontend.service                        # blocks until healthy; sessions live in Redis
journalctl CONTAINER_NAME=frontend --since -5m -o cat | grep 'Session type allow-list mode'   # ... mode: ENFORCE
```

**Expected effect:** none a member can see. Nobody is signed out by the restart, and every value the
parity test and the E2E suite (which runs `enforce`) cover reads identically. Watch for an hour:
`SessionTypeOutsideAllowList` and `SessionValueDropsSustained` stay silent, and
`sum by (mode) (increase(basetool_session_type_refused_total[1h]))` stays empty. Should a class
outside the list turn up after all, that one attribute is dropped once per session and repaired on
the same request (REQ-SEC-050) — the member keeps the login.

**Rollback:** edit the line back to `APP_SESSION_TYPE_ALLOW_LIST=report` (or delete it), render
`env.d/` again with the same command and `${UCTL} restart frontend.service`. No stored session is
touched either way: the mode governs reading only. `off` restores the pre-list validator exactly, for
the case where the reporting itself misbehaves.

### Internal JWKS for the backend

The backend can fetch the keys that sign access tokens from the **internal** Keycloak
(`https://keycloak:18443`, over `net-backend-keycloak` and the pinned `keycloak-trust` bundle)
instead of through the public edge (REQ-SEC-024, ADR-0073). `iss` is still checked against the
public issuer, so tokens do not change. Until 2026-09-23 `application-prod.yml` read the variable but
nothing passed it to the container, so this could not be switched on in production; the release
carrying the wiring passes it **empty**, which keeps today's issuer-location decoder exactly.

> [!note] Applied on production 2026-09-25 (~15:38 UTC, #2038)
> Exactly as below: the `.env` line, the render (only `env.d/backend.env` changed), the backend
> restart — healthy in 11 s. Afterwards no `JWKS` / `PKIX` / `No subject alternative` line, an
> authenticated `/api/v1/users/me` answered `200`, and no `401` followed.

**What changes when it is set:** the key fetch no longer hairpins through the edge, so an edge or
public-DNS blip can no longer fail token validation, and the accepted signature algorithms widen
from what the live JWKS advertises to the full asymmetric set. Members notice nothing. Only the
backend: the ingest gateway reads the same property but is on no network that reaches Keycloak.

**Precondition** (read-only): Keycloak's certificate names `keycloak` — it must already, because
the user sync verifies it (REQ-SEC-014); a failing daily sync would say so.

**Apply** (as root, from `/`; a production write, so it waits for the owner's yes):

```bash
cd /
cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
printf 'IRI_BACKEND_KEYCLOAK_JWK_SET_URI=https://keycloak:18443/auth/realms/iri/protocol/openid-connect/certs\n' \
  >> /var/iri/code/.env
grep -c '^IRI_BACKEND_KEYCLOAK_JWK_SET_URI=' /var/iri/code/.env                        # 1
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
grep '^KEYCLOAK_JWK_SET_URI=' /var/iri/code/env.d/backend.env    # ...=https://keycloak:18443/auth/realms/iri/protocol/openid-connect/certs
${UCTL} restart backend.service                                   # blocks until healthy
```

**Verify:** sign in on the web app and load a page that calls the API (the mission list); the app
works and the backend log shows no `401` burst and no `JWKS` / `PKIX` / `No subject alternative`
line (`${UPOD} logs --since 10m backend 2>&1 | grep -iE 'jwk|PKIX|subject alternative'`).
`basetool_http_error_total{code="SERVICE_UNAVAILABLE"}` stays flat.

**Rollback:** delete the line from `.env`, render `env.d/` again with the same command, restart the
backend. The variable then arrives empty and the issuer-location decoder is back.

### Network changes are installed, not applied

A changed `.network` unit reaches the host like any other and is **not** applied by it: Quadlet
creates a network with `podman network create --ignore`, so an existing network keeps its old
settings until it is removed and recreated. `deploy.sh` does not do that on its own (it would be a
full-stack outage behind an automatic tick). Take it as a maintenance, testing host first, with the
members of the changed networks stopped. For the 2026-09-22 change that made the data networks
`Internal=true` (ADR-0162):

> [!note] Applied on production 2026-09-25 (#1992), testing host not yet
> The owner chose production directly. The block below ran as written: members stopped 16:21:46 UTC,
> all five networks recreated `internal=true`, everything up again 16:24:00 — a full outage of about
> **2.5 minutes** (the edge served 166 maintenance-page 5xx). Checked afterwards: all ten members
> healthy; `db-backend`, `db-keycloak` and `redis` have no default route (`/proc/net/route`), while
> `backend` and `keycloak` keep theirs through their other networks (Keycloak still reaches Discord);
> `backend` resolves `db-backend`; both exporters report `pg_up 1` / `redis_up 1`. The testing host
> still runs the old networks.

```bash
systemctl stop iri-deploy.timer
# every member of the five networks; stopping a database also stops what Requires= it
${UCTL} stop frontend.service ingest.service backend.service keycloak.service \
  postgres-exporter-backend.service postgres-exporter-keycloak.service redis-exporter.service \
  db-backend.service db-keycloak.service redis.service
${UPOD} network rm net-db-backend net-db-keycloak net-redis-backend net-redis-frontend net-redis-ingest
${UCTL} restart net-db-backend-network.service net-db-keycloak-network.service \
  net-redis-backend-network.service net-redis-frontend-network.service net-redis-ingest-network.service
for n in net-db-backend net-db-keycloak net-redis-backend net-redis-frontend net-redis-ingest; do
  ${UPOD} network inspect "$n" --format "$n {{.Internal}}"          # expect: true
done
${UCTL} start db-backend.service db-keycloak.service redis.service
${UCTL} start keycloak.service backend.service ingest.service frontend.service \
  postgres-exporter-backend.service postgres-exporter-keycloak.service redis-exporter.service
systemctl start iri-deploy.timer
```

Then `python scripts/check-conformance.py --ssh <host>` from a workstation, and from inside a
database container confirm there is no way out: `${UPOD} exec db-backend wget -q -T 5 -O- https://1.1.1.1`
must fail, while `${UPOD} exec backend …` still resolves `db-backend`.

### Stateful-infra upgrades

A changed **`postgres:` or `quay.io/keycloak/keycloak:` tag** is operator-gated (REQ-OPS-006).
`deploy.sh` compares the tags in both the compose file and the `.container` units, logs
`CARVE-OUT: postgres/Keycloak image pin changed`, writes `/var/lib/iri/config-blocked.marker`, exits
3 once (`DeployConfigBlocked`) and then skips that target quietly. A same-tag **digest** refresh is
not gated and applies on the next tick.

To take a gated upgrade: do the component's upgrade work first, then
`cd / && sudo -u deploy /var/iri/code/scripts/deploy.sh --force`. For Keycloak that is checking the
provider JAR against the new version and that the keystore still carries `dns:keycloak`. For a
**Postgres major** no procedure is written yet — the running cluster will not start on a newer
major, so plan it as its own change (a dump and restore into a fresh data directory, the mechanism
[`backup.md`](backup.md) already restores with) before promoting it.

When bumping a third-party image, re-check the container still starts under its unit's hardening
(`ReadOnly=true`, `DropCapability=ALL`, its own `User=`, REQ-OPS-014); the health gate rolls a
failure back, but catching it in review is cheaper.

### Keycloak provider JAR

Delivered automatically (REQ-OPS-007, ADR-0055, ADR-0213): when `basetool-keycloak-spi:stable`
moves, `deploy.sh` applies the new `keycloak-spi.jar` **as part of the release**, in the same restart
window as the app images the release moves:

1. before anything on the host changes, it extracts the JAR to `/var/lib/iri/keycloak-spi-stage.jar`
   — a failure there is a recorded pre-gate failure (`FATAL … step 'extract the keycloak-spi provider
   JAR'`, backoff, `DeployFailed`) with nothing to undo;
2. as the last step before the apply, it copies the live JAR to
   `/var/lib/iri/keycloak-spi-previous.jar` and installs the new one into
   `/var/iri/code/keycloak/providers/`;
3. the release apply stops keycloak together with every other re-defined unit — and, through
   `Requires=`, backend, frontend and ingest — in one `systemctl --user stop`, then starts the stack
   in order: keycloak on the new JAR first, then backend, ingest, frontend, each waited for. Only when
   every stack unit is healthy does it write the marker and log `deploy successful`.

If the gate fails, the previous JAR goes back **with** the previous app digests, config tree and
units, and the stack is applied once more the same way — one rollback, `DeployRolledBack`, backoff.
A JAR-only release is the same apply with only keycloak re-defined. The deploy log of a release
that moves the JAR and the images reads:

```text
keycloak-spi changed → staging ghcr.io/…/basetool-keycloak-spi@sha256:… (applied with this release, one restart)
…
provider JAR swapped in — keycloak restarts on it with this release
release parts: app images [backend frontend ingest] · unit definitions [none] · config bundle: no · provider JAR: yes
one restart window: stopping [backend frontend ingest keycloak] and what requires them, then starting the stack in order
applying (timeout 180s)
keycloak-spi provider JAR applied with the release — keycloak and the application stack are healthy on it
deploy successful
```

and a failed gate, for example a keycloak that does not start on the new JAR:

```text
health check failed within 180s — rolling back
health gate: did not come up, in start order: [keycloak backend ingest frontend]
health gate: this release changed: app images [backend frontend ingest] · unit definitions [none] · config bundle: no · provider JAR: yes
health gate: KEYCLOAK did not come up, and the provider JAR is the only part of this release that changed keycloak — the JAR is the likely cause; what failed after keycloak requires it
provider JAR restored to the previous one
rolled back to previous digest pin successfully — the previous app digests + provider JAR are live again and the stack is healthy
```

**Blame is weaker than it was, by decision** (ADR-0213). One gate covers the JAR and the images, so
when an **app** unit is the first that fails, the log says keycloak came up on the new JAR and that it
cannot tell a bad image from a bad JAR — a frontend that cannot log in through the new JAR fails the
same way as a broken frontend image. Read `${UPOD} logs --since 10m <unit>` for the unit it names. The
one case it narrows: keycloak itself failing when the JAR is its only change.

**Expected outage:** one keycloak start, then one backend start, then the slower of frontend and
ingest — about two minutes of maintenance page — **for the whole release**. Until ADR-0213 the JAR
was swapped only after the app apply had passed its gate, so a release that moved both paid this
**twice**: v1.12.0 on 2026-09-25 served maintenance-page 5xx from 17:39 to 17:43 UTC, about two
minutes of it the JAR step's. (That step also once logged `deploy successful` at 17:43:17 while
frontend and ingest still had no container — #2072 fixed the wait; ADR-0213 removed the second
restart.) The JAR is Java-21 bytecode for Keycloak's JVM. The Discord realm setup is a one-time step
in [`keycloak/DISCORD_KEYCLOAK_SETUP.md`](keycloak/DISCORD_KEYCLOAK_SETUP.md).

Manual fallback only:

```bash
./gradlew :keycloak-spi:jar                                   # on a build machine
install -o deploy -g deploy -m 0644 keycloak-spi-<version>.jar /var/iri/code/keycloak/providers/keycloak-spi.jar
restorecon -F /var/iri/code/keycloak/providers/keycloak-spi.jar
${UCTL} restart keycloak.service      # ~2 min full outage: restarts backend, frontend, ingest too
${UCTL} start backend.service ingest.service frontend.service   # waits until the outage is over
${UPOD} logs --since 2m keycloak | grep -iE 'error|exception|provider' | head
```

### Keycloak realm shape

The realm lives in `db-keycloak`, not in any artifact: delivery never touches it, and
`realm-export.json` only seeds an empty one. What the Basetool needs from it — its clients, the two
ingest audience scopes, scope assignments, the DPoP policy, service-account roles, token settings —
is brought to production's shape by `scripts/provision-keycloak-realm.py` (`REQ-OPS-033`,
[ADR-0202](adr/0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md)):
dry run by default, `--apply` to write, origins from `--public-origin`, nothing deleted that only
the target has. Run it on a **new** host's realm, and on the **testing** host whenever
`scripts/keycloak-config-snapshot.sql` diffs against production outside the environment-specific
lines. The procedure, and the `.env` values a newly created confidential client needs, are in
[`INGEST_KEYCLOAK_SETUP.md` → *New or out-of-date realm*](INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner).
On production an `--apply` is a gated write like any other.

**The frontend's client type** (public or confidential, ADR-0001) is changed only by
`--frontend-client public|confidential`; a run without it leaves the type as it is. The switch to
confidential is a two-step owner rollout with no login window — the frontend receives
`KEYCLOAK_FRONTEND_CLIENT_SECRET` first, then the provisioner flips Keycloak with the same value:
[`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md). **Production
is confidential since 2026-09-25** (both steps applied), so every production provisioner run passes
`--frontend-client confidential` with the secret from `.env`, and a release rollback to 1.10.0 or
older needs `--frontend-client public --apply` first — through a new provisioner session.

The backend refuses to start under `prod` without `IRI_BACKEND_EXPECTED_AUDIENCES`, and the
frontend's token carries that audience only once the realm is in shape — so on a host whose realm
was never provisioned, **provision first, then set the variable**.

### Updating the operational scripts and units

`deploy.sh`, the other scripts and the `iri-*` units are **not** in the config bundle — a deployer
that replaces itself mid-run is a self-update hazard. They change only through the role, as a
deliberate host change:

```bash
ansible-playbook site.yml --limit production --tags deploy,scripts --check --diff
ansible-playbook site.yml --limit production --tags deploy,scripts
ansible-playbook site.yml --limit production --tags observability   # the two collectors and their timers
ansible-playbook site.yml --limit production --tags updates         # dnf-automatic (REQ-OPS-032)
```

**Host patching** is `dnf-automatic`, set up by the same role: security advisories daily at 07:00
(+ up to 15 minutes), the container runtime excluded, **never a reboot** (ADR-0199). A due reboot
raises `HostRebootRequired`; take it as a maintenance — the stack comes back on its own, because the
units are `WantedBy=default.target` and `iri` lingers. The runtime (podman, crun, conmon, netavark,
aardvark-dns, containers-common, passt) is updated by hand, testing host first:
`dnf upgrade --security podman crun conmon netavark aardvark-dns containers-common passt`.

> [!success] Fixed by #2069 (2026-09-25): four failed `iri-*` units after a reboot
> Production was rebooted for kernel 6.12.0-211.58.1 on 2026-09-25 at 15:56 UTC; all 18 containers
> were back healthy by 15:59 and `basetool_host_reboot_required` went to 0. But at 15:57:30 the
> **services** `iri-deploy`, `iri-backup`, `iri-restore-drill` and `iri-container-cleanup` were
> started — their timers' `LastTrigger` did not move — and each failed within a second with
> `FATAL: podman is installed but no lingering user could be found that owns the containers (looked in /var/lib/systemd/linger)`.
> Two causes, both reproduced in a Rocky 10.2 / systemd 257 / podman 5.8.2 container
> (arc42 §7.4b):
>
> - **Why they ran at all:** every `iri-*.timer` carried `Requires=<its service>`, which starts the
>   service whenever the *timer* starts — at every boot — without the timer elapsing. Nothing was
>   due; not a `Persistent=` catch-up. The timers no longer pull their service in.
> - **Why they failed:** the jobs run in a sandbox (`ProtectHome=read-only`, which covers `/run/user`
>   too). A job started before `user@<iri uid>.service` mounted `/run/user/<uid>` never sees that
>   mount; one started just after sees it read-only and cannot reach a container until the manager's
>   first one runs (podman: `set sticky bit on: chmod /run/user/<uid>/libpod: read-only file system`).
>   The jobs are now ordered after the manager (`20-service-user.conf`, templated by the role), and
>   `rt_detect` waits, bounded, while that manager is still starting — and quotes podman when it
>   refuses.
>
> What a reboot looks like now is under **Reboot** in [Driving the stack](#driving-the-stack).
> **Starting a timer no longer runs its job.** `systemctl start iri-backup.timer` arms it for 04:15;
> run a job now with `systemctl start iri-backup.service`. (`iri-deploy.timer` still fires at once on
> a host that has been up more than five minutes, because its `OnBootSec` has passed.) Reaches a host
> only through the role: `--tags scripts`.

Confirm by content, never by mtime:
`sha256sum /var/iri/code/scripts/deploy.sh` against `git show origin/main:scripts/deploy.sh | sha256sum`.

---

## The edge

```
client ──► haproxy :80/:443 (host, v4+v6) ──send-proxy-v2──► edge 127.0.0.1|[::1]:8080/8443
                                                              nginx, uid 101, read-only
```

- **haproxy** (ADR-0187, `ansible/roles/basetool_host/templates/haproxy.cfg.j2`) binds the public
  ports and hands the client address to the edge in a PROXY-protocol header. The edge publishes on
  loopback only, which is what makes that header unforgeable.
- **The edge** is native nginx (ADR-0162). Its configuration is `docker/edge/` in the repository:
  `nginx.conf`, `conf.d/*.conf.template` (one per vhost, rendered at start by `render-and-run.sh`
  from `EDGE_HOST_FRONTEND`, `EDGE_HOST_INGEST`, `EDGE_HOST_GRAFANA`, `EDGE_HOST_API` — it refuses to
  start with one unset) and `include/`.
- **`EDGE_TRUSTED_PROXY`** in `.env` must name the edge's six pinned addresses, space-separated —
  the edge's own address on each of its networks, because rootless port forwarding presents the
  peer from whichever network it chooses. Empty, the listeners stay plain and every request fails
  with `wrong version number`; incomplete, nginx silently discards the header and the whole internet
  shares one rate-limit bucket. Check it against the unit:

  ```bash
  UD=/etc/containers/systemd/users/${IRI_UID}
  diff <(sed -n 's/^EDGE_TRUSTED_PROXY=//p' /var/iri/code/.env | tr ' ' '\n' | sort) \
       <(sed -n 's/^Network=.*:ip=//p' "${UD}/edge.container" | sort) && echo "trusted == pinned"
  ```

- **Changes** are made in the repository and arrive with a promotion; `deploy.sh`'s `reconcile_edge`
  recreates the edge whenever its on-disk config differs from the last applied snapshot. Validate
  locally before merging with `scripts/check-edge-nginx.sh` (renders every vhost through
  `render-and-run.sh` and runs `nginx -t`; CI runs it in `repo-lint.yml`).
- **Compression happens at the edge only.** `nginx.conf` strips `Accept-Encoding` towards the
  upstreams and gzips on the way out: HTML plus the `gzip_types` list (CSS, both JavaScript
  labels, JSON, problem+json, the manifest, SVG, plain text), `gzip_vary on`, from 1 KB. The event
  streams are deliberately not in the list, because gzip would buffer them. Until 2026-09-23 the
  list was missing and only HTML left the edge compressed; `check-edge-nginx.sh` now asserts it.
- **The API vhost's allow-list** is `docker/edge/include/api-allowlist.conf`, the source of truth
  (ADR-0135). `edge-deny-probe.yml` probes the public deny rules from outside every day.

### The edge verifies Grafana

Every upstream the edge re-encrypts to is verified against the internal CA
(`include/upstream-tls.conf`), except Grafana: it serves its own self-signed certificate
(`/var/iri/monitoring/certs/grafana.crt`, minted once per host, [`monitoring/README.md`](../monitoring/README.md)
step 3). Since 2026-09-23 the edge can verify that hop too, pinning that very certificate as the
Grafana upstream's only anchor and checking the name `grafana` (`include/upstream-grafana-tls.conf`,
REQ-OBS-008). It is behind **`EDGE_GRAFANA_UPSTREAM_VERIFY`**, `off` by default, so the release that
carries it changes nothing but one read-only mount: the edge now mounts `grafana.crt`, the same file
Grafana itself needs to start. `deploy.sh`'s mount pre-flight reads the units **already installed**
at the start of a tick (before the tick installs the incoming bundle's), so it does not stop the
release that *adds* this mount: with the file missing, that release fails at the edge's start and
the health gate rolls it back; only later ticks refuse it up front *(corrected 2026-09-25)*. The
file exists wherever Grafana runs, and on production it was confirmed on 2026-09-25.

> [!note] Applied on production 2026-09-25 (~15:40 UTC, #2039)
> Exactly as below. The edge logged `Grafana's upstream certificate is verified (pinned)` and
> Grafana's `/api/health` answered `200`. The re-mint rule at the end of this section now applies
> to production.

**Precondition** (read-only): the certificate names `grafana`.

```bash
openssl x509 -in /var/iri/monitoring/certs/grafana.crt -noout -subject -enddate -ext subjectAltName
# subjectAltName must list DNS:grafana; enddate in the future
```

If it does not, re-mint it first (monitoring/README.md step 3) and restart Grafana.

**Apply** (as root, from `/`; a production write, so it waits for the owner's yes):

```bash
cd /
cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
printf 'EDGE_GRAFANA_UPSTREAM_VERIFY=on\n' >> /var/iri/code/.env
grep -c '^EDGE_GRAFANA_UPSTREAM_VERIFY=' /var/iri/code/.env              # 1
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
${UCTL} restart edge.service                                            # blocks until healthy
${UPOD} logs --since 2m edge 2>&1 | grep "Grafana's upstream"           # ... is verified (pinned)
curl -sS -o /dev/null -w '%{http_code}\n' "https://$(sed -n 's/^EDGE_HOST_GRAFANA=//p' /var/iri/code/.env)/api/health"   # 200
```

A verification failure shows as the maintenance page / `503` on the Grafana host and an
`upstream SSL certificate verify error` line in the edge log. **Rollback:** delete the line, render
`env.d/` again, restart the edge.

**From then on, a re-minted `grafana.crt` needs the edge restarted as well as Grafana:** the edge
mounts the file (a single-file mount pins the inode) and pins its contents. Until the restart the
edge refuses the new certificate and Grafana answers `503`.

### Maintenance page

`include/maintenance.conf` turns an upstream 502/503/504 into a `503` with `Retry-After: 60` — the
branded `maintenance.html` (auto-refreshes every 30 s) or, for an `Accept: application/json` caller,
RFC 7807 `maintenance.json`. The static files are `docker/maintenance/static/`, mounted into the
edge. It covers every vhost, including `/auth`: during a restart there is nothing to log in to.

### Edge rate limiting

`include/limits.conf` applies, on every vhost, **20 r/s with burst 80** (`nodelay`) and at most
**500 concurrent connections** per client, keyed by `$krt_limit_key` from `conf.d/00-maps.conf` —
the full IPv4 address or the IPv6 `/64` (REQ-SEC-023). The token endpoint has a tighter
`burst=10`. Rejections are **429**, never 503, or the maintenance intercept would answer a flood.
Rejections land in the edge log at `warn`; a sustained flood raises `EdgeRateLimitSpike`.

The limiter is only as good as the client address: it keys on what the PROXY header asserts, so an
`EDGE_TRUSTED_PROXY` mismatch collapses it onto one bucket. `check-conformance.py`'s
`client-address-visible` is the check. To exercise the limit (a load test — decide deliberately):

```bash
for i in $(seq 1 120); do curl -s -o /dev/null -w '%{http_code}\n' https://profit-base.online/ & done | sort | uniq -c
# expect 200s and 429s, no 503
```

### Public certificates and ACME renewal

The `acme` container (lego, `docker/acme/publish-loop.sh`) runs every 12 hours: it renews the one
multi-SAN certificate for `ACME_HOSTS` (HTTP-01 through the edge's webroot) once it is within 30
days of expiry, and publishes it into the `edge-certs` volume for each host, readable by the edge's
uid (REQ-OPS-026). It needs `ACME_EMAIL` and `ACME_HOSTS` in `.env`; with `ACME_HOSTS` empty it
idles. The edge cannot be signalled by `acme`; `deploy.sh` fingerprints the served certificates
through the edge every tick and recreates it when they change, so a renewal is live within one tick.
`AcmeRenewalFailing` and `CertificateExpiringSoon` watch both halves.

```bash
${UPOD} logs --since 24h acme                     # the last renewal pass
${UCTL} restart acme.service                      # run a pass now
echo | openssl s_client -connect profit-base.online:443 -servername profit-base.online 2>/dev/null \
  | openssl x509 -noout -enddate                   # what the edge is serving right now
```

Let's Encrypt allows five duplicate certificates per week for one SAN set; do not re-issue
casually. `edge-certs` and `edge-acme-state` exist only in `iri`'s volume store and in the backup.

### Keycloak Admin Console via SSH tunnel

The console, `https://profit-base.online/auth/admin`, is on the public vhost but locked to the host
itself: `location ^~ /auth/admin` in `docker/edge/conf.d/10-frontend.conf.template` allows only
the container-bridge gateways and — rendered by `render-and-run.sh` whenever the listeners speak
PROXY protocol — `127.0.0.1` and `::1`, then `deny all`. Behind haproxy, a connection that
originates on the host arrives as loopback, and loopback can only be produced by a process already
on the host. `EDGE_ADMIN_ALLOW` can add literal addresses, never a prefix.

```bash
ssh -N -L 443:127.0.0.1:443 root@<production host>
```

**The local port must be 443**: Keycloak is hostname-strict and emits portless URLs, so any other
port ends in redirect loops and `Invalid parameter: redirect_uri`. If the host's sshd restricts
forwarding with `PermitOpen`, `127.0.0.1:443` has to be in it. Then map the name to the tunnel —
preferably in a throwaway browser profile, because since ADR-0166 the console shares its name with
the whole app:

```bash
chrome --host-resolver-rules="MAP profit-base.online 127.0.0.1:443" --user-data-dir=/tmp/kc-admin "https://profit-base.online/auth/admin"
```

A `127.0.0.1 profit-base.online` hosts entry works too, and sends the entire web app through the
tunnel while it is in place. A **403** means the allow-list and the arrival address disagree — read
the edge log for `access forbidden by rule, client: …`. The console still requires Keycloak's own
admin login; the lock-down keeps the login form off the internet. `edge-deny-probe.yml` checks
daily that it answers 403/404 from outside.

---

## Internal TLS: per-service certificates from a private CA

REQ-SEC-070, ADR-0211, audit finding ING-SEC-04. **Every production step below is a write and
needs @greluc's explicit yes, in chat, per step.** Nothing here happens by itself: the release that
ships this is inert, and the switches are host files the owner creates and host variables the owner
sets.

> [!note] Where production stands (2026-09-25)
> - **Step 0** — done with the 1.11.0 role run (~11:5x UTC).
> - **Step 1** — done ~15:40 UTC. The pre-check gave `Verification: OK` for all four services;
>   `INTERNAL_TLS_VERIFY_HOSTNAME=true`, frontend and ingest restarted healthy.
> - **Step 2** — 2a–2e done ~15:52 UTC exactly as written: the mint printed `The CA key no longer
>   exists.`; `/var/iri/secrets/tls` holds `backend.p12 ca.crt frontend.p12 ingest.p12 keycloak.p12
>   legacy-shared.crt truststore.p12`; `basetool-ca.crt` carries two anchors; edge, Prometheus and
>   blackbox restarted with no edge verify error. **2f** turned up a pre-existing defect — the SPI
>   truststore `.env` named had never existed — and was done by building it (see 2f below and
>   [`DISCORD_KEYCLOAK_SETUP.md` §7.3](keycloak/DISCORD_KEYCLOAK_SETUP.md#73-truststore-for-the-backend-certificate)).
> - **Step 3** — done with **v1.12.0** (promote run 36168223771 at 17:39 UTC, deploy 17:38–17:44
>   UTC): backend, frontend, ingest and Keycloak mount `/var/iri/secrets/tls/<service>.p12`, the
>   three apps mount `/var/iri/secrets/tls/truststore.p12` as the internal truststore, and all four
>   answer `Verification: OK` against `/var/iri/secrets/tls/ca.crt` with `-verify_hostname`.
> - **Step 4** — done 17:58–18:03 UTC, the owner's choice the same evening rather than a day
>   later. `basetool-ca.crt` is the CA alone (one anchor), the SPI truststore holds only
>   `internal-ca`, `truststore.p12` only `ca`, and `iri-cert-expiry` reports the CA's expiry (2036).
>   The step-4 command as documented **failed** on the root-owned truststore and was redone with a
>   working copy at 18:01 — the step below is now written in that form (see *As run on
>   production*). **Rollout complete on production.** A release rollback to 1.11.0 or older now
>   needs the old certificate re-trusted first ([Step 4](#step-4--drop-the-old-certificate)).

**Why.** The shared `/var/iri/secrets/keystore.p12` is the identity of backend, frontend, ingest and
Keycloak at once, and — self-signed — also the anchor every one of them trusts. A key read out of
the internet-facing ingest container is therefore the backend's and Keycloak's key too. After the
rollout each service holds a leaf of its own, signed by a CA whose key no longer exists, and clients
trust only that CA and check the name.

**What the release changes on its own: nothing.** Every new mount falls back to the shared keystore
and every new switch defaults to today's behaviour:

| Knob | Where | Default (= today) | After the rollout |
|---|---|---|---|
| `INTERNAL_TLS_VERIFY_HOSTNAME` | host `.env` → `env.d` (frontend, ingest) | `false` | `true` |
| `IRI_BACKEND_KEYSTORE_HOST_PATH` / `_FRONTEND_` / `_INGEST_` / `_KEYCLOAK_` | baked into the units by `generate-quadlet.py` (`PATH_VARS`) | `/var/iri/secrets/keystore.p12` | `/var/iri/secrets/tls/<service>.p12` |
| `IRI_INTERNAL_TRUSTSTORE_HOST_PATH` → `/run/secrets/internal-truststore.p12` | baked, as above | `/var/iri/secrets/keystore.p12` | `/var/iri/secrets/tls/truststore.p12` |
| `IRI_TRUSTSTORE_HOST_PATH` → `/run/secrets/truststore.p12` (REQ-OPS-022's JVM-truststore default; production's JVM truststore is the role's separate `jvm-truststore.p12` drop-in) | baked, as above | `/var/iri/secrets/keystore.p12` — the shared **private key**, mounted into all three apps | `/var/iri/secrets/tls/truststore.p12`, so no container holds the old key |
| `/var/iri/monitoring/certs/basetool-ca.crt` (edge, Prometheus, blackbox) | host file | the shared certificate | the internal CA |
| `/var/iri/secrets/backend-truststore.p12` (Keycloak SPI precheck, if configured) | host file + a hand-installed keycloak drop-in | the backend's shared certificate (alias `backend`) | the internal CA only (alias `internal-ca`; both from step 2f to step 4) |

The four `*_KEYSTORE_HOST_PATH` and the two truststore paths are **baked**: setting them in `.env` does
nothing on the Podman host (`check-conformance.py` → `env-reaches-the-units` says so). They move with
the follow-up release that flips `PATH_VARS`, and that release must not be promoted before step 2.

**The transition trick.** Until step 4 the CA-only truststore and `basetool-ca.crt` carry **the old
shared certificate as a second anchor**. Every client then accepts either certificate, so the order
in which services restart in step 3 cannot break a handshake, and a rollback to the old release is
a restart, not a re-mint.

Common prelude for every step (as root on the host):

```bash
cd /
IRI_UID=$(id -u iri); B=$(grep '^iri:' /etc/subuid | cut -d: -f2)
UCTL="sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} systemctl --user"; UPOD="sudo -u iri podman"
```

### Step 0 — tooling on the host

The mint script, the per-mount deploy pre-flight and the backup capture of `/var/iri/secrets/tls`
reach the host through the role, not through the release (ADR-0188). From the workstation (WSL):

```bash
ansible-playbook site.yml --limit production --tags scripts --check --diff
ansible-playbook site.yml --limit production --tags scripts
```

Expected: `mint-internal-tls.sh`, `deploy.sh`, `backup.sh` changed under `/var/iri/code/scripts/`,
plus `render-redis-acl.py` and `redis-users.acl.tmpl` if the Redis ACL renderer
([The Redis ACL](#the-redis-acl)) was not installed before — the same run installs both.
Rollback: re-run the role from the previous commit.

### Step 1 — check the names (independent of everything else)

The shared certificate already names every service, so hostname verification can go on first. Prove
it before switching, from inside the stack's network namespace (read-only; `openssl` is the host's):

```bash
IP=$(${UPOD} inspect backend --format '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' | awk '{print $1}')
sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} podman unshare --rootless-netns \
  openssl s_client -connect "${IP}:11261" -servername backend -verify_hostname backend \
  -CAfile /var/iri/monitoring/certs/basetool-ca.crt -verify_return_error </dev/null 2>&1 \
  | grep -E 'Verify return code|Verification'
```

Expected: `Verification: OK` and `Verify return code: 0 (ok)`. A `hostname mismatch` stops here. (The
blackbox `https_internal` probes give the same verdict continuously — `probe_success` of the
internal targets.)

Then switch it on — the variable reaches the containers through `env.d`:

```bash
cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
printf 'INTERNAL_TLS_VERIFY_HOSTNAME=true\n' >> /var/iri/code/.env
grep -c '^INTERNAL_TLS_VERIFY_HOSTNAME=' /var/iri/code/.env      # 1
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
grep -H INTERNAL_TLS_VERIFY_HOSTNAME /var/iri/code/env.d/frontend.env /var/iri/code/env.d/ingest.env
${UCTL} restart ingest.service frontend.service     # each blocks until healthy
```

Expected: both `=true`, both units healthy (the frontend's readiness follows the same flag, so a
wrong name fails the restart instead of failing silently later). **Rollback:** delete the line,
re-render, restart the same two units.

### Step 2 — mint the material and widen every trust anchor

Nothing serves the new certificates yet; this step only makes every client **also** trust them.

```bash
# 2a. The directory: iri-owned (container root), traversable for the deploy pre-flight.
install -d -o iri -g iri -m 0755 /var/iri/secrets/tls

# 2b. Mint, through the backend image (the host has no JDK). The password is the keystore password
#     every consumer already reads (SERVER_SSL_KEY_STORE_PASSWORD / KC_HTTPS_KEY_STORE_PASSWORD),
#     passed through the environment, never argv.
IMG=$(${UPOD} container inspect backend --format '{{.ImageName}}')
export TLS_STORE_PASSWORD="$(sed -n 's/^SERVER_SSL_KEY_STORE_PASSWORD=//p' /var/iri/code/.env | tail -1)"
sudo --preserve-env=TLS_STORE_PASSWORD -u iri podman run --rm -i --user 0 -e TLS_STORE_PASSWORD \
  --entrypoint sh -v /var/iri/secrets/tls:/work "${IMG}" -s -- --out /work \
  --service backend=dns:backend,dns:localhost,ip:127.0.0.1 \
  --service frontend=dns:frontend,dns:localhost,ip:127.0.0.1 \
  --service ingest=dns:ingest,dns:localhost,ip:127.0.0.1 \
  --service keycloak=dns:keycloak,dns:localhost,ip:127.0.0.1 \
  < /var/iri/code/scripts/mint-internal-tls.sh

# 2c. The old shared certificate as a second anchor in the internal truststore (removed in step 4).
cp /var/iri/monitoring/certs/basetool-ca.crt /var/iri/secrets/tls/legacy-shared.crt
sudo --preserve-env=TLS_STORE_PASSWORD -u iri podman run --rm --user 0 -e TLS_STORE_PASSWORD \
  --entrypoint keytool -v /var/iri/secrets/tls:/work "${IMG}" \
  -importcert -noprompt -alias legacy-shared -file /work/legacy-shared.crt \
  -keystore /work/truststore.p12 -storepass:env TLS_STORE_PASSWORD
unset TLS_STORE_PASSWORD
#     This in-place write works only HERE, while the minted store is still iri-owned. From 2d on it is
#     root:root 0644, and root in the rootless container is iri on the host: any later keytool write
#     (step 4, its rollback, a rotation) goes through an iri-owned working copy -- see step 4.

# 2d. Ownership: app keystores readable by the app group (10001), Keycloak's by its uid (1000), all
#     by iri for the backup helper; the truststore and the CA hold no key.
chown root:$((B + 10001 - 1)) /var/iri/secrets/tls/backend.p12 /var/iri/secrets/tls/frontend.p12 /var/iri/secrets/tls/ingest.p12
chown root:root /var/iri/secrets/tls/keycloak.p12
chmod 0640 /var/iri/secrets/tls/backend.p12 /var/iri/secrets/tls/frontend.p12 /var/iri/secrets/tls/ingest.p12 /var/iri/secrets/tls/keycloak.p12
setfacl -m u:iri:r /var/iri/secrets/tls/backend.p12 /var/iri/secrets/tls/frontend.p12 /var/iri/secrets/tls/ingest.p12
setfacl -m u:$((B + 1000 - 1)):r -m u:iri:r /var/iri/secrets/tls/keycloak.p12
chown root:root /var/iri/secrets/tls/truststore.p12 /var/iri/secrets/tls/ca.crt /var/iri/secrets/tls/legacy-shared.crt
chmod 0644 /var/iri/secrets/tls/truststore.p12 /var/iri/secrets/tls/ca.crt /var/iri/secrets/tls/legacy-shared.crt
restorecon -RF /var/iri/secrets/tls
ls -l /var/iri/secrets/tls; getfacl -p /var/iri/secrets/tls/keycloak.p12

# 2e. The edge, Prometheus and blackbox anchor: the new CA AND the old certificate.
cat /var/iri/secrets/tls/ca.crt /var/iri/secrets/tls/legacy-shared.crt > /var/iri/monitoring/certs/basetool-ca.crt
restorecon -F /var/iri/monitoring/certs/basetool-ca.crt
${UCTL} restart edge.service prometheus.service blackbox-exporter.service

# 2f. Only if the Keycloak SPI precheck is configured (KRT_BACKEND_TRUSTSTORE_PATH in .env) --
#     see "Step 2f" below: it may have to BUILD the truststore, not just extend it.
```

Expected: the mint prints `The CA key no longer exists.`; `ls` shows `backend.p12 ca.crt
frontend.p12 ingest.p12 keycloak.p12 legacy-shared.crt truststore.p12`; the edge still serves the
app (it verifies the unchanged upstreams against the old certificate in the bundle). **Rollback:**
put the old certificate back into `basetool-ca.crt` (`cp /var/iri/secrets/tls/legacy-shared.crt
/var/iri/monitoring/certs/basetool-ca.crt`), restart the same three units; `/var/iri/secrets/tls`
may stay, nothing mounts it yet.

#### Step 2f — the Keycloak SPI precheck's truststore

The account-existence precheck (REQ-SEC-022) calls the backend from inside Keycloak and trusts it
through its own PKCS#12 store, named by `.env`'s `KRT_BACKEND_TRUSTSTORE_PATH` (a path **inside**
the keycloak container) and mounted there by a drop-in the operator installs
([`DISCORD_KEYCLOAK_SETUP.md` §7.3](keycloak/DISCORD_KEYCLOAK_SETUP.md#73-truststore-for-the-backend-certificate)).
Check first, read-only, that the store really exists — **do not assume it**:

```bash
sed -n 's/^KRT_BACKEND_TRUSTSTORE_PATH=//p' /var/iri/code/.env          # empty: skip 2f entirely
ls -l /var/iri/secrets/backend-truststore.p12
ls -l /etc/containers/systemd/users/${IRI_UID}/keycloak.container.d/
journalctl CONTAINER_NAME=keycloak --since -7d -o cat | grep -c 'Failed to load the backend truststore'   # must be 0
```

> [!warning] Found on production 2026-09-25: the variable was set, the store never existed
> `.env` named `/run/secrets/backend-truststore.p12` (with a password), but no such file was on the
> host and no drop-in mounted one, so Keycloak logged
> `WARN [BackendTrustSupport] Failed to load the backend truststore; the Discord account-existence precheck will fail open until it is fixed.: java.nio.file.NoSuchFileException: /run/secrets/backend-truststore.p12`
> at every start, over at least seven days of log history: the duplicate-account guard was **failing
> open on production**. Fixed ~15:52 UTC the same day by building the store with **both** entries
> and installing the drop-in, as below; no truststore warning since.

**If the store is missing**, build it with both entries — alias `backend` = the current shared
certificate (what the backend serves until step 3), alias `internal-ca` = the new CA (what it
serves from step 3) — through the backend image's `keytool`, with the password `.env` already
holds, in a scratch directory the rootless container can write to, then install it root-owned.
**As run on production 2026-09-25 (~15:52 UTC)**, as root from `/`, after step 2, with `IRI_UID`,
`UCTL` and `UPOD` from the prelude:

```bash
T=/var/iri/secrets/tls
# a scratch dir the rootless container (root in it = iri on the host) can write into; /root is not traversable for iri
W=$(mktemp -d /var/iri/secrets/.kc-trust.XXXXXX)
cp "$T/legacy-shared.crt" "$T/ca.crt" "$W/"; chown -R iri:iri "$W"; chmod 0700 "$W"; chmod 0644 "$W"/*.crt
IMG=$(${UPOD} container inspect backend --format '{{.ImageName}}')
export KRT_TS_PASSWORD="$(sed -n 's/^KRT_BACKEND_TRUSTSTORE_PASSWORD=//p' /var/iri/code/.env | tail -1)"
[ -n "$KRT_TS_PASSWORD" ] || { echo "KRT_BACKEND_TRUSTSTORE_PASSWORD is empty"; exit 1; }
for a in backend:legacy-shared.crt internal-ca:ca.crt; do
  sudo --preserve-env=KRT_TS_PASSWORD -u iri podman run --rm --user 0 -e KRT_TS_PASSWORD \
    --entrypoint keytool -v "$W":/work "${IMG}" -importcert -noprompt -storetype PKCS12 \
    -alias "${a%%:*}" -file "/work/${a##*:}" -keystore /work/backend-truststore.p12 \
    -storepass:env KRT_TS_PASSWORD
done
unset KRT_TS_PASSWORD
install -o root -g root -m 0644 "$W/backend-truststore.p12" /var/iri/secrets/backend-truststore.p12
restorecon -F /var/iri/secrets/backend-truststore.p12
rm -rf "$W"
D=/etc/containers/systemd/users/${IRI_UID}/keycloak.container.d
printf '[Container]\nVolume=/var/iri/secrets/backend-truststore.p12:/run/secrets/backend-truststore.p12:ro\n' > "$D/50-backend-truststore.conf"
chown deploy:deploy "$D/50-backend-truststore.conf"; chmod 0644 "$D/50-backend-truststore.conf"
${UCTL} daemon-reload && ${UCTL} restart keycloak.service   # restarts backend, frontend, ingest too (Requires=) -- ~2 min outage
${UCTL} start backend.service ingest.service frontend.service   # returns once they are healthy again, not before
```

Output on production: `Certificate was added to keystore` twice; the drop-in landed beside the
existing `30-log-driver.conf`. Two things the block takes for granted that another host may not
have: the drop-in directory `$D` already existed on production (create it first with
`install -d -o deploy -g deploy -m 0755 "$D"` where it does not), and the mount target
`/run/secrets/backend-truststore.p12` is production's `KRT_BACKEND_TRUSTSTORE_PATH` — on any host
it must be exactly what that variable names.

**If the store exists**, add only the CA the same way (one `-importcert` with `-alias internal-ca`
on a working copy in such a scratch directory, then `install` it back), and restart keycloak. The
installed store is `root:root 0644`, which the container's root (= `iri` on the host) cannot
write: copy it **into** `$W` together with `ca.crt` *before* the `chown -R iri:iri "$W"`, never
point `keytool` at `/var/iri/secrets/backend-truststore.p12` itself.

Verify: `journalctl CONTAINER_NAME=keycloak --since -5m -o cat | grep -c 'Failed to load the backend truststore'`
is `0`, and `${UPOD} exec keycloak ls /run/secrets` lists `backend-truststore.p12` (both held on
production since 2026-09-25). The drop-in survives every release (`deploy.sh` never touches a
`.container.d/`), but **neither it nor the store is in the backup**. **Rollback:** remove the
drop-in, `daemon-reload`, restart keycloak — the precheck fails open again, which is where it was.

### Step 3 — serve the new certificates

Merge and promote the follow-up release that bakes the step-2 files into the units (`PATH_VARS`) —
**only after step 2 has put every file in place**. The `deploy.sh` mount pre-flight reads the units
already installed at the start of the tick, not the incoming ones, so it does **not** refuse this
release before applying it: a missing file makes the containers fail to start and the health gate
roll the release back (and later ticks then refuse it). Check the files by hand first
(`ls -l /var/iri/secrets/tls/`) *(corrected 2026-09-25: this used to say the pre-flight refuses the
release before anything is applied)*. The deploy recreates backend, frontend, ingest and Keycloak on their own leaves; the
truststore still carries the old certificate, so the restart order does not matter.

Verify:

```bash
for svc in backend:11261 frontend:18081 ingest:11262 keycloak:18443; do
  n=${svc%%:*}; IP=$(${UPOD} inspect "$n" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' | awk '{print $1}')
  echo "== $n"; sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} podman unshare --rootless-netns \
    openssl s_client -connect "${IP}:${svc##*:}" -servername "$n" -verify_hostname "$n" \
    -CAfile /var/iri/secrets/tls/ca.crt -verify_return_error </dev/null 2>&1 | grep -E 'Verification|subject='
done
for n in backend frontend ingest; do journalctl CONTAINER_NAME="$n" --since -10m -o cat; done \
  | grep -iE 'PKIX|No subject alternative|certificate_unknown' || echo "no TLS errors"
curl -fsS https://profit-base.online/auth/realms/iri/.well-known/openid-configuration >/dev/null && echo OK
```

Expected: `Verification: OK` against **the CA alone**, and each `subject=` names its own service.
*(Changed 2026-09-25: the log check reads the journal instead of `podman logs`. The 1.11.0 runbook
§9 saw `podman logs` return nothing for the Quadlet containers in one context, and a grep over
empty input prints "no TLS errors" just the same.)*

**On production** this step arrived with **v1.12.0** on 2026-09-25 (promote run 36168223771 at
17:39 UTC, the deploy 17:38–17:44 UTC): all four services `Verification: OK` against `ca.crt` with
`-verify_hostname`, each on its own leaf, the apps on the CA-only truststore.

**kcadm after step 3** *(added 2026-09-25; still untested on production after v1.12.0)*: the documented kcadm session trusts
`/run/secrets/keystore.p12` inside the keycloak container. From this release on, that path is
Keycloak's own leaf keystore, and the keycloak unit mounts no truststore. The session probably keeps
working, but nobody has verified it; the fallback, and whether keycloak should mount the CA-only
truststore, are an open question in
[`keycloak/README.md` → *Runbook — provisioning the mobile client*](keycloak/README.md#runbook--provisioning-the-mobile-client-basetool-android).
**Rollback:** re-promote the previous release (the old units mount the shared keystore, which is
untouched and still trusted by every client).

### Step 4 — drop the old certificate

Once step 3 has run without TLS errors (the plan said a day; production did it the same evening, see
below). Every store step 4 edits is **root-owned** by then — `truststore.p12` since 2d,
`backend-truststore.p12` since 2f — and root inside the rootless container is `iri` on the host, so
`keytool` cannot write either in place. Each one is edited on an `iri`-owned working copy and
`install`ed back, the pattern 2f already used. Do all file changes first and restart once at the
end: the restart of keycloak and the apps is a ~2-minute full outage, and one is enough.

```bash
T=/var/iri/secrets/tls; S=$(date +%Y%m%d-%H%M%S)
IMG=$(${UPOD} container inspect backend --format '{{.ImageName}}')

# 4a. Backups beside the originals -- the rollback below, and the basis of a release rollback.
cp -p "$T/truststore.p12" "$T/truststore.p12.backup-$S"
cp -p /var/iri/monitoring/certs/basetool-ca.crt "/var/iri/monitoring/certs/basetool-ca.crt.backup-$S"
cp -p /var/iri/secrets/backend-truststore.p12 "/var/iri/secrets/backend-truststore.p12.backup-$S"   # only if 2f was done

# 4b. The internal truststore: delete legacy-shared on a working copy, check, install back.
W=$(mktemp -d /var/iri/secrets/.tls-trust.XXXXXX)
cp "$T/truststore.p12" "$W/"; chown -R iri:iri "$W"; chmod 0700 "$W"
export TLS_STORE_PASSWORD="$(sed -n 's/^SERVER_SSL_KEY_STORE_PASSWORD=//p' /var/iri/code/.env | tail -1)"
sudo --preserve-env=TLS_STORE_PASSWORD -u iri podman run --rm --user 0 -e TLS_STORE_PASSWORD \
  --entrypoint keytool -v "$W":/work "${IMG}" \
  -delete -alias legacy-shared -storetype PKCS12 -keystore /work/truststore.p12 -storepass:env TLS_STORE_PASSWORD
sudo --preserve-env=TLS_STORE_PASSWORD -u iri podman run --rm --user 0 -e TLS_STORE_PASSWORD \
  --entrypoint keytool -v "$W":/work "${IMG}" \
  -list -storetype PKCS12 -keystore /work/truststore.p12 -storepass:env TLS_STORE_PASSWORD   # one entry: ca
unset TLS_STORE_PASSWORD
install -o root -g root -m 0644 "$W/truststore.p12" "$T/truststore.p12"
restorecon -F "$T/truststore.p12"
rm -rf "$W"

# 4c. Only if 2f was done: the SPI truststore keeps internal-ca alone -- same pattern, its own password.
W=$(mktemp -d /var/iri/secrets/.kc-trust.XXXXXX)
cp /var/iri/secrets/backend-truststore.p12 "$W/"; chown -R iri:iri "$W"; chmod 0700 "$W"
export KRT_TS_PASSWORD="$(sed -n 's/^KRT_BACKEND_TRUSTSTORE_PASSWORD=//p' /var/iri/code/.env | tail -1)"
sudo --preserve-env=KRT_TS_PASSWORD -u iri podman run --rm --user 0 -e KRT_TS_PASSWORD \
  --entrypoint keytool -v "$W":/work "${IMG}" \
  -delete -alias backend -storetype PKCS12 -keystore /work/backend-truststore.p12 -storepass:env KRT_TS_PASSWORD
sudo --preserve-env=KRT_TS_PASSWORD -u iri podman run --rm --user 0 -e KRT_TS_PASSWORD \
  --entrypoint keytool -v "$W":/work "${IMG}" \
  -list -storetype PKCS12 -keystore /work/backend-truststore.p12 -storepass:env KRT_TS_PASSWORD   # one entry: internal-ca
unset KRT_TS_PASSWORD
install -o root -g root -m 0644 "$W/backend-truststore.p12" /var/iri/secrets/backend-truststore.p12
restorecon -F /var/iri/secrets/backend-truststore.p12
rm -rf "$W"

# 4d. The edge, Prometheus and blackbox anchor: the CA alone.
install -m 0644 "$T/ca.crt" /var/iri/monitoring/certs/basetool-ca.crt
restorecon -F /var/iri/monitoring/certs/basetool-ca.crt
grep -c 'BEGIN CERTIFICATE' /var/iri/monitoring/certs/basetool-ca.crt    # 1

# 4e. Restart. One combined job: systemd orders it along Requires= and restarts each unit once.
${UCTL} restart edge.service prometheus.service blackbox-exporter.service
${UCTL} restart keycloak.service backend.service ingest.service frontend.service   # ~2 min full outage
#   (without 2f: ${UCTL} restart backend.service ingest.service frontend.service)
systemctl start iri-cert-expiry.service      # the metric now reports the CA's own expiry
```

Verify: the step-3 loop again (`Verification: OK` for all four against `ca.crt`), no
`PKIX|No subject alternative|certificate_unknown` in the app logs,
`journalctl CONTAINER_NAME=keycloak --since -5m -o cat | grep -c 'Failed to load the backend truststore'`
is `0`, the edge serves the app, and in Prometheus
`basetool_certificate_expiry_timestamp_seconds{path=~".*/basetool-ca.crt"}` carries
`subject="CN=Profit Basetool internal CA,…"`.

> [!warning] As run on production, 2026-09-25 17:58–18:03 UTC — and why this step was rewritten
> *(corrected 2026-09-25)* This step used to run `keytool -delete -alias legacy-shared -keystore
> /work/truststore.p12` directly against `-v /var/iri/secrets/tls:/work`. On production that failed
> with `keytool error: java.io.FileNotFoundException: /work/truststore.p12 (Permission denied)`:
> 2d had made the store `root:root 0644`, and the container's root is `iri` on the host. The rest of
> the step went through — the three backups (paths in the box below), `basetool-ca.crt` with one anchor, the
> SPI truststore down to `internal-ca` on a working copy (4c), the edge/Prometheus/blackbox restart
> and one combined `${UCTL} restart keycloak.service backend.service ingest.service frontend.service`.
> That restart cost the edge's maintenance page **74, 121 and 7** 5xx answers per minute over
> 17:58–18:00. At 18:01 the truststore was redone exactly as 4b above (`-list` showed only `ca`),
> followed by `${UCTL} restart backend.service ingest.service frontend.service` (18:01:47–18:03:36)
> — a second outage of about two minutes that the order above avoids. `iri-cert-expiry` then
> reported the CA (`CN=Profit Basetool internal CA`, expiring 2036).

From here on, **the old `keystore.p12` is no longer a trust anchor anywhere**, and since step 3 no
unit mounts it any more (the step-3 release also moved the REQ-OPS-022 `/run/secrets/truststore.p12`
mount onto the CA-only truststore), so no container holds the old key. **Leave the file in place**
anyway: it is what the rollback of step 3 — the previous release — mounts. Confirm the next nightly
backup carries `config/internal-tls.tar`.

**Rollback:** `install -o root -g root -m 0644` each `*.backup-$S` file from 4a back over its
original, `restorecon -F` each, and restart as in 4e. Without the backups: re-import
`legacy-shared.crt` into `truststore.p12` and `backend` into the SPI truststore on working copies
as in 4b/4c (copy the `.crt` into `$W` too; `-importcert -noprompt -storetype PKCS12 -alias … -file
/work/…`), rebuild the bundle as in 2e, restart the same units.

> [!important] A release rollback to 1.11.0 or older needs the old certificate trusted again first
> *(added 2026-09-25, after step 4 on production)* 1.11.0's units mount the shared `keystore.p12`
> as every service's identity again, and after step 4 nothing trusts it: the edge, Prometheus and
> blackbox would refuse every upstream. **And the Discord duplicate-account precheck would fail
> open again, silently** (REQ-SEC-022): the SPI truststore holds only `internal-ca`, so Keycloak's
> call to a backend back on the shared certificate fails its handshake — no outage, the guard just
> stops working, and the only signal is the `Account-existence probe could not reach the backend`
> `WARN` per first login. **Before** `promote.yml -f version=1.11.0`, undo step 4 as in its rollback
> above:
>
> 1. `legacy-shared` back into `/var/iri/secrets/tls/truststore.p12`, so services still on 1.12.0
>    during the lock-step restarts accept those already back on the shared certificate;
> 2. the old certificate back into `basetool-ca.crt` (edge, Prometheus, blackbox restarted);
> 3. the `backend` alias (the old shared certificate, `/var/iri/secrets/tls/legacy-shared.crt`)
>    back into `/var/iri/secrets/backend-truststore.p12`, then restart keycloak.
>
> Production's step-4 backups are `/var/iri/secrets/tls/truststore.p12.backup-20260925-175805`,
> `/var/iri/monitoring/certs/basetool-ca.crt.backup-20260925-175805` and
> `/var/iri/secrets/backend-truststore.p12.backup-20260925-175805` (plus
> `/var/iri/code/.env.backup-20260925-175805-step4`). A rollback to **1.10.0 or older** also needs
> `REDIS_DEFAULT_USER` back on ([The Redis ACL](#the-redis-acl)) and the frontend client back to
> public (`--frontend-client public --apply` through a new provisioner session,
> [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md)) — then promote.

**Rotation after the rollout** is a re-mint: all leaves and the CA together, into a fresh directory,
then the same widening (old CA as second anchor) → switch → narrowing. No single leaf can be
re-issued, by design — the CA key is gone. The ownership trap holds here too: `keytool` may write
in place only into a directory and stores that are still `iri`-owned, as the fresh mint's are
before its 2d-style `chown`; every edit of a store that is already root-owned — the narrowing, the
SPI truststore — goes through a working copy as in 4b/4c.

---

## Internal keystore and certificate rotation

> [!note] Applies to the **shared** keystore, i.e. until step 3 of
> [*Internal TLS: per-service certificates from a private CA*](#internal-tls-per-service-certificates-from-a-private-ca)
> has run. Afterwards a rotation is a re-mint (the last paragraph of that section). **Production
> is past that point** since 2026-09-25 (step 3 with v1.12.0, step 4 the same evening): there, this
> section describes only the file a rollback to 1.11.0 or older would mount again.

The shared `/var/iri/secrets/keystore.p12` is the internal TLS identity of backend, frontend,
ingest and Keycloak, **and** their truststore: frontend and ingest pin it to call the backend, the
backend pins it to call Keycloak, the edge verifies every upstream against its public half
(`/var/iri/monitoring/certs/basetool-ca.crt`), and so do Prometheus and the blackbox exporter. So a
rotation is one coordinated change, and **every consumer has to restart**: a JVM left on the old
certificate fails with `PKIX path building failed`, and an edge left on the old CA answers every
request with the maintenance page.

Its SAN must carry every name a peer dials: `backend`, `frontend`, `ingest`, `keycloak`, plus
`localhost` and `127.0.0.1`. The backend keeps hostname verification on for the Keycloak admin
call, so a keystore without `dns:keycloak` breaks the user sync. Keycloak serves HTTPS only
(`--http-enabled=false`); its management port 9000 stays plain HTTP on container loopback for the
healthcheck.

**When:** before `SelfSignedCertificateExpiring` (90 days out; `iri-cert-expiry` reads
`basetool-ca.crt` daily), or at once after a suspected key compromise. It is a short outage of the
whole app — schedule it.

The host has no JDK; `keytool` runs from the backend image, rootless, with the password taken from
`.env` through the environment rather than the command line:

```bash
cd /
IRI_UID=$(id -u iri); B=$(grep '^iri:' /etc/subuid | cut -d: -f2)
UCTL="sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} systemctl --user"; UPOD="sudo -u iri podman"

# 1. No deploy tick in between.
systemctl stop iri-deploy.timer && flock /var/lock/iri-deploy.lock true

# 2. Keep the old keystore as the rollback, and clear the name (keytool refuses an existing alias).
install -m 0600 -o root -g root /var/iri/secrets/keystore.p12 /var/iri/secrets/keystore.p12.bak
rm -f /var/iri/secrets/keystore.p12

# 3. Generate. --user 0 in a rootless container is `iri` on the host, which owns /var/iri/secrets.
IMG=$(${UPOD} container inspect backend --format '{{.ImageName}}')
export KS_PW="$(sed -n 's/^SERVER_SSL_KEY_STORE_PASSWORD=//p' /var/iri/code/.env | tail -1)"
sudo --preserve-env=KS_PW -u iri podman run --rm --user 0 -e KS_PW --entrypoint keytool \
  -v /var/iri/secrets:/work "${IMG}" \
  -genkeypair -alias basetool -storetype PKCS12 -keystore /work/keystore.p12 \
  -storepass:env KS_PW -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=basetool, OU=IRIDIUM, O=DAS KARTELL, C=DE" \
  -ext "san=dns:localhost,ip:127.0.0.1,dns:backend,dns:frontend,dns:ingest,dns:keycloak"
unset KS_PW

# 4. Ownership for the containers (app group 10001, keycloak 1000 by ACL) and the backup helper (iri).
chown root:$((B + 10001 - 1)) /var/iri/secrets/keystore.p12
chmod 0640 /var/iri/secrets/keystore.p12
setfacl -m u:$((B + 1000 - 1)):r -m u:iri:r /var/iri/secrets/keystore.p12
restorecon -F /var/iri/secrets/keystore.p12
getfacl -p /var/iri/secrets/keystore.p12          # group::r--, user:100999:r--, user:iri:r--, mask::r--

# 5. Re-export the public half (openssl prompts for the password; add -legacy after `pkcs12`
#    if OpenSSL 3 rejects the keytool-made file). Confirm the SAN.
openssl pkcs12 -in /var/iri/secrets/keystore.p12 -clcerts -nokeys \
  | openssl x509 -out /var/iri/monitoring/certs/basetool-ca.crt
chmod 0644 /var/iri/monitoring/certs/basetool-ca.crt
restorecon -F /var/iri/monitoring/certs/basetool-ca.crt
openssl x509 -in /var/iri/monitoring/certs/basetool-ca.crt -noout -subject -enddate -ext subjectAltName

# 6. Restart every consumer; each blocks until healthy.
${UCTL} restart keycloak.service backend.service ingest.service frontend.service edge.service   # a full outage (Requires=)
${UCTL} restart prometheus.service blackbox-exporter.service
systemctl start iri-cert-expiry.service          # re-read the certificate files now, not at 03:40

# 7. Verify, then resume.
curl -fsS https://profit-base.online/auth/realms/iri/.well-known/openid-configuration >/dev/null && echo OK
${UPOD} logs --since 5m backend | grep -iE 'PKIX|subject alternative|fetch users from keycloak' || echo "no TLS errors"
systemctl start iri-deploy.timer
```

Then run `check-conformance.py --ssh root@<host> --only scrape-targets-up --only
containers-running` from the workstation. The monitoring-side follow-ups — Grafana's own
certificate, the `blackbox-internal-tls` probes, what to check in the meta-monitoring dashboard —
are in [`monitoring/README.md`](../monitoring/README.md). If Keycloak's Discord precheck uses a
backend truststore (`KRT_BACKEND_TRUSTSTORE_PATH` in `.env`), rebuild it from the new certificate
per [`keycloak/DISCORD_KEYCLOAK_SETUP.md`](keycloak/DISCORD_KEYCLOAK_SETUP.md).

**Rollback:** move `keystore.p12.bak` back, repeat steps 4–6. The next nightly backup captures the
new keystore; restic does not carry POSIX ACLs, so a restore re-applies step 4 by hand.

---

## Signature verification (cosign)

Every artifact the host is about to run is cosign-verified on the host before it is pulled,
extracted or applied (REQ-OPS-015, ADR-0075) — the host half of the supply-chain seam whose CI half
is `promote.yml`. The host re-resolves `:stable` on every tick, so without this a `:stable` moved
out-of-band would be pulled unverified.

```
verifying image signatures (cosign keyless)
  backend: signature OK
  frontend: signature OK
  ingest: signature OK
  config: signature OK
  keycloak-spi: signature OK
```

- Identity `^https://github\.com/krt-profit/basetool/\.github/workflows/release-images\.yml@refs/(heads/main|tags/v[0-9]+\.[0-9]+\.[0-9]+)$`,
  issuer `https://token.actions.githubusercontent.com` — the same as `promote.yml`. A
  `workflow_dispatch` build off a feature branch is not trusted. **Anchored since 2026-09-22**: the
  earlier unanchored `…@refs/(heads/main|tags/v.+)` also matched `refs/heads/main-x` or
  `refs/tags/vfoo`. A host running a `deploy.sh` from before that date still verifies with the old
  regexp — `deploy.sh` reaches the host only through the Ansible role (`--tags scripts`), not through
  the promoted bundle.
- **Fail-closed**, with three attempts and doubling delay first (`IRI_COSIGN_VERIFY_ATTEMPTS`,
  `IRI_COSIGN_VERIFY_DELAY`), so a registry blip is not a security alarm; the abort quotes cosign's
  own error and records `DeployFailed`.
- **Break-glass**, only for a Sigstore outage that blocks every deploy, logged on every skip:
  `cd / && sudo -u deploy IRI_COSIGN_VERIFY=false /var/iri/code/scripts/deploy.sh --force`.
- `IRI_COSIGN_REPO`, `IRI_COSIGN_IDENTITY_REGEXP`, `IRI_COSIGN_OIDC_ISSUER` override the identity
  for a fork; `deploy.sh --help` lists every variable.

**The host cosign** is installed by the role (`15-cosign.yml`) from the upstream release, verified
against the sha256 pinned in `ansible/roles/basetool_host/defaults/main.yml`
(`basetool_host_cosign_version`, currently v3.1.3) — it is in no Rocky or EPEL repository. It must
never be an older major than the cosign CI signs with (`sigstore/cosign-installer`, v4.1.2 →
cosign 3.x): cosign 2.x cannot verify 3.x signatures, and the fail-closed gate would stop every
deploy. To upgrade: change the version and the checksum in `defaults/main.yml` in a reviewed commit,
then `ansible-playbook site.yml --limit production --tags cosign`, then
`cd / && sudo -u deploy /var/iri/code/scripts/deploy.sh --check-only` as the proof.

---

## Token rotation

The GHCR pull token has to be a **classic** PAT: GitHub Packages does not accept fine-grained
tokens. Scope `read:packages` only, 90-day expiry, authorised for the organisation's SSO if
enforced. Its scope is account-wide, which the short expiry compensates for. The release workflows'
`basetool-release` App key (ADR-0201) is a separate CI secret and unrelated.

If the token expires, record the date in the sidecar: `deploy.sh` publishes it every tick as
`basetool_ghcr_token_expiry_timestamp`, and `GhcrPullTokenExpiring` (under 14 days) /
`GhcrPullTokenExpired` fire from it. No sidecar, no metric, no alert — deleting the sidecar removes
the metric on the next tick.

```bash
# 1. Create the new classic PAT in GitHub.
# 2. Install it without putting it in shell history or the process list.
read -rs TOKEN
printf '%s\n' "${TOKEN}" | install -m 0600 -o deploy -g deploy /dev/stdin /etc/iri/ghcr-pull-token.new
unset TOKEN
mv /etc/iri/ghcr-pull-token.new /etc/iri/ghcr-pull-token
echo '2026-12-21' | install -m 0640 -o deploy -g deploy /dev/stdin /etc/iri/ghcr-pull-token.expiry   # only if it expires
# 3. Prove it before revoking the old one.
cd / && sudo -u deploy /var/iri/code/scripts/deploy.sh --check-only && tail -n 20 /var/log/iri-deploy.log
# 4. Revoke the old token in GitHub.
```

---

## Weekly container cleanup

`iri-container-cleanup.timer` (Saturday 02:00 UTC) runs
[`scripts/container-cleanup.sh`](../scripts/container-cleanup.sh) as `deploy` through the runtime
seam: stopped containers older than 24 h, unused images older than 14 days (outliving the deploy
rollback anchor), unused networks older than 24 h. Each is overridable with `IRI_CLEANUP_*`
(`--help`).

**Two steps the Docker job had are gone, deliberately (ADR-0194).** `podman builder prune` is only an
alias for `image prune`. And **`podman volume prune` is never run**: unlike Docker's, it removes every
volume not currently attached — `edge-certs` and `edge-acme-state` included whenever the stack is
down. The anonymous-volume leak Docker's prune used to absorb is fixed at its source (`rt_rm_force`
removes a container with its anonymous volume). Never run `podman volume prune` by
hand on this host either. Data under `/var/iri` is bind-mounted and out of every prune's reach.

```bash
cd / && sudo -u deploy /var/iri/code/scripts/container-cleanup.sh --dry-run   # the plan and current usage
systemctl start iri-container-cleanup.service && tail -f /var/log/iri-container-cleanup.log
```

`ContainerCleanupStaleOrMissing` fires when the last success is over eight days old.

---

## Open follow-up: decommission the retired Docker host

`ubuntu-8gb-nbg1-1` was shut down after the cutover on 2026-09-22, with its `iri-deploy.timer`
**disabled** so that bringing it back cannot pull anything promoted since. Until the owner decides
to delete it, [`PODMAN_CUTOVER_RUNBOOK.md` §2](archive/PODMAN_CUTOVER_RUNBOOK.md) is the way back:
shut the new host down, bring the old one up on the configuration it has, revert DNS — losing any
writes made on the new host since. Once it is deleted, that section stops applying and this entry
goes.

---

## Troubleshooting

| Symptom | Where to look | Common cause |
|---|---|---|
| Timer fires, nothing updates | `/var/log/iri-deploy.log`, Loki `{app="ops-deploy"}` | `:stable` not promoted yet; or the target is in its backoff window (`in backoff window` in the log) |
| `login to ghcr.io failed` / `cannot resolve …:stable` | the same log | expired or revoked token, or `deploy` cannot read it — see [Token rotation](#token-rotation) |
| `SECURITY: cosign signature verification failed` | the same log, it quotes cosign | Sigstore/GHCR outage (it retried three times), or a genuinely untrusted digest — treat as a supply-chain incident until disproved |
| `no lingering user could be found` / `cannot chdir to /root` | the command's own output | run from `/`; `iri` must linger (`ls /var/lib/systemd/linger`) |
| `no lingering user …; it never became visible to this process` | the job's log | the unit started before `iri`'s manager and its sandbox cannot see `/run/user/<uid>`: the `20-service-user.conf` drop-in is missing — `systemctl show iri-backup.service -p After` must name `user@<uid>.service`; re-run the role, `--tags scripts` |
| `no lingering user …; podman said: … /run/user/<uid>/libpod: read-only file system` | the job's log | `iri`'s manager is up but no container of its has run since boot, so there is no podman pause process for the sandboxed job to join; start the stack (`systemctl --user start` as `iri`) and run the job again |
| Health check fails, rollback | `${UPOD} ps`, `${UPOD} logs <svc>`, `${UCTL} status <svc>.service` | a broken release — inspect the rolled-back container's logs |
| Container never starts, `statfs …: no such file or directory` | `${UCTL} status <svc>.service` | a missing bind-mount source — see [Secrets and host-only files](#secrets-and-host-only-files) |
| keycloak cannot read `/run/secrets/keystore.p12` | `${UPOD} logs keycloak`, `getfacl -p /var/iri/secrets/keystore.p12` | the ACL for uid 100999 is missing — re-apply step 4 of the [rotation](#internal-keystore-and-certificate-rotation) |
| backend/frontend/ingest die on the OIDC issuer (`Connect timed out`, `did not match`) | `${UPOD} logs backend` | missing public-name alias drop-in (ADR-0196), or `IRI_KEYCLOAK_HOSTNAME` without its `/auth` path (ADR-0167) |
| every request logged from one internal address | `${UPOD} logs edge` | `EDGE_TRUSTED_PROXY` does not name all six pinned addresses — see [The edge](#the-edge) |
| `curl: … wrong version number` on 443 | `journalctl -u haproxy`, `${UPOD} logs edge` | `EDGE_TRUSTED_PROXY` empty: the edge's listeners are plain while haproxy sends a PROXY header |
| nothing answers on 80/443 | `systemctl status haproxy`, `firewall-cmd --list-all` | haproxy not started (fresh host), or a firewall layer — probe from a third machine |
| Stack comes back after a manual stop | `drift:` lines in the deploy log | the drift check (REQ-OPS-013); stop the timer first |
| `health drift: … did NOT restore health (attempt #N)`, with `health drift: did not come up, in start order: […]`; `DeployHealthRestartFailing` fires | `{app="ops-deploy"} \|~ "health drift"`, `${UCTL} status <first unit named>.service`, `${UPOD} logs --since 10m <first unit named>` | a runtime fault on the deployed release, not a release problem — nothing was rolled back. The heal stopped the unhealthy service(s) and what requires them once, then started the stack in order; the **first** unit named did not come up and the rest require it. Fix the cause, then `sudo -u deploy /var/iri/code/scripts/deploy.sh --force` to skip the heal backoff — or `${UCTL} start <units named>`, which joins anything still queued and restarts nothing. Do not `restart` backend or keycloak to "help": it takes what requires them down again (ADR-0083, amended 2026-09-25). *(Before 2026-09-25 the heal was a `restart` per service and could log `health drift resolved` while frontend and ingest had no container yet.)* |
| `drift: […] unhealthy on the target image — not part of this re-apply` and `stack-health heartbeat NOT stamped` | the deploy log | a tick found a missing container **and** an unhealthy one; the re-apply brought the missing one back and deliberately left the unhealthy one to the next tick's targeted heal (ADR-0083). Nothing to do unless the next tick's heal fails |
| `CARVE-OUT: postgres/Keycloak image pin changed` | the deploy log, `config-blocked.marker` | a gated upgrade — see [Stateful-infra upgrades](#stateful-infra-upgrades) |
| `health gate: did not come up, in start order: […]` with `provider JAR: yes` in the `this release changed:` line; `DeployRolledBack` fires | the `health gate:` lines of the deploy log, `${UPOD} logs --since 10m <first unit named>`, `${UCTL} status keycloak.service backend.service frontend.service ingest.service` | one release moved the JAR (and possibly images), and the whole of it — pin, config, units, JAR — was rolled back together (ADR-0213); the target backs off. `KEYCLOAK did not come up … the JAR is the likely cause` narrows it to the JAR; `keycloak is up on the new provider JAR; the first unit that did not come up is …` cannot tell a bad image from a bad JAR — read that unit's log. If it then says `rollback ALSO failed — did not come up: […]`, something outside the release is wrong — `${UCTL} start backend.service ingest.service frontend.service` waits for them without restarting anything. *(Before 2026-09-25 the JAR had its own post-gate step, logging `keycloak did not become healthy with the new provider JAR` and `DeployFailed`.)* See [Keycloak provider JAR](#keycloak-provider-jar) |
| `FATAL: deploy aborted before the health gate — step 'extract the keycloak-spi provider JAR' failed`; `DeployFailed` fires | the deploy log; `skopeo inspect docker://ghcr.io/<owner>/basetool-keycloak-spi:stable` as `deploy` | the promoted provider-JAR image could not be read or carries no `/providers/keycloak-spi.jar`; nothing on the host was changed and the target backs off. Until 2026-09-25 this failed after the gate and recorded nothing |
| Deploy stuck on a config apply: `PRE-FLIGHT: … is not owned or not writable by deploy`, or `FATAL: deploy aborted before the health gate — step 'mirror …' failed (exit 23)` after an `rsync: … mkstemp … Permission denied (13)`; `DeployFailed` fires, then `target failed Nx; in backoff window` | the deploy log, `find /var/iri/code/{docker,keycloak-theme,monitoring,quadlet} ! -user deploy` | a release-owned subtree (here `docker/acme`, 2026-09-25) was created or copied as root. Fix the owner — `chown -R deploy:deploy /var/iri/code/docker` (or the subtree named), or the role with `--tags directories` — then `sudo -u deploy /var/iri/code/scripts/deploy.sh --force` to skip the backoff. If the log also says **INCONSISTENT**, the restore failed too: the same fix, then `--force`; `config-previous/` was kept |
| A promoted unit change is ignored | `ls ~iri/.config/containers/systemd/` | a hand-placed unit of the same name shadows the delivered one |
| Monitoring config changes never load | the deploy log (`IRI_MONITORING_ENABLED != 'true'`) | set `IRI_MONITORING_ENABLED=true` in `.env`; `MonitoringReconcileDisabled` fires meanwhile |

---

## Why this design

- **Pull, not push.** The host accepts no connection from GitHub. A compromised workflow or a stolen
  `GITHUB_TOKEN` cannot run code on it; the token on the host can only read what was published.
- **Digest pin between resolution and apply.** `:stable` is resolved once per tick and written as
  per-service drop-ins; a tag flip mid-deploy is picked up on the next tick, never half-applied,
  and a hand-started unit runs the pinned digest too.
- **Verify before apply.** The host verifies every digest itself; `promote.yml`'s verify alone would
  leave a window between "verified at promotion" and "re-resolved on the host".
- **Health gate with auto-rollback.** `Notify=healthy` makes a unit's start the health check; the
  deployer keeps the previous pin, units and config tree and restores all three together.
- **Config rides the image channel.** One read-only credential delivers images, units, edge and
  monitoring configuration alike, content-addressed and signed; the config digest is part of the
  idempotence marker, so a config-only change is never skipped.
- **Rootless, with one narrow bridge.** The workload runs as an unprivileged user with no privileged
  group; the deployer reaches it through two named sudo commands instead of a root-equivalent
  socket, and every container runs read-only, capability-less and, for the stateful ones, as its
  own uid (ADR-0189, ADR-0190).
- **Provisioning stays out of the delivery path.** The role builds and changes the host on an
  operator's decision; it never ships a release, so the pull-only property cannot erode by
  convenience (ADR-0188).
- **No image holds a secret.** `.gitignore`, `.dockerignore`, the bundle's COPY allowlist, a CI
  assertion and `deploy.sh`'s own check keep keys, `.env` and realm exports out of every artifact.
