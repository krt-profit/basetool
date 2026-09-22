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
| `/var/iri/code/realm-export.json` | `root:100999` + ACL `u:iri:r` | Keycloak realm seed, bind-mounted into keycloak | only seeds an empty realm; the live realm is in `db-keycloak` |
| `/var/iri/redis/users.acl` | `root:root 0644` | Redis ACL, **with** a `user default …` line | without that line redis resets `default` to `nopass`; check `grep -c '^user default ' …` = 1 |
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
the internal network (the 2026-07-10 defect). Two entries: `default`, carrying exactly
`REDIS_PASSWORD` from `.env`, and a read-only `monitoring` user for `redis-exporter` that can run
introspection commands but cannot list or read keys.

```bash
# default first, its password read straight out of .env (not typed, not in history)
printf 'user default on >%s ~* &* +@all\n' \
  "$(sed -n 's/^REDIS_PASSWORD=//p' /var/iri/code/.env | tail -1)" > /var/iri/redis/users.acl
printf 'user monitoring on >%s -@all +@connection +@read +client +config|get +info +latency +slowlog +memory +cluster|info +cluster|slots +cluster|nodes +xinfo +pfcount -keys sanitize-payload\n' \
  "$(openssl rand -base64 30 | tr -d '/+=\n')" >> /var/iri/redis/users.acl
chown root:root /var/iri/redis/users.acl && chmod 0644 /var/iri/redis/users.acl
restorecon -F /var/iri/redis/users.acl
grep -c '^user default ' /var/iri/redis/users.acl        # must be 1
```

Put the monitoring user's password into `.env` as `REDIS_EXPORTER_PASSWORD` (read it back with
`grep -oP '(?<=^user monitoring on >)[^ ]+' /var/iri/redis/users.acl`). **Rotating `REDIS_PASSWORD`
means changing both places** — the `default` line and `.env` — then restarting redis and every
client (`${UCTL} restart redis.service backend.service frontend.service ingest.service
redis-exporter.service`); one without the other locks the apps out of their sessions.
`check-conformance.py --only redis-requires-auth` proves an unauthenticated `PING` is refused.

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

`repo-lint.yml`'s `quadlet-drift` job runs `--check` and the translation self-test
(`generate-quadlet.test.sh`). Dispositions: `node-exporter` and `alloy` become host services, the
podman exporter is a user unit the role installs, `cadvisor` and `socket-proxy` are deleted; every
other service is a container. The edge's six network addresses are pinned by the generator and must
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
| `/var/iri/code/scripts/` | the role | `deploy.sh`, `backup.sh`, `restore-drill.sh`, `container-cleanup.sh`, `lib/container-runtime.sh`, `render-env-d.py`, the two collectors — `root:root 0755`, so `deploy` cannot rewrite its own deployer |
| `/etc/containers/systemd/users/<iri-uid>/` | `deploy.sh` | the 39 units, plus `<svc>.container.d/10-digest-pin.conf` (the release's digest) and the role's host drop-ins |
| `/var/iri/code/env.d/` | `deploy.sh` via `render-env-d.py` | one rendered environment file per service |
| `/var/lib/iri/` | `deploy.sh` | digest-pin record and its predecessor, `last-deployed.digests`, backoff records, `config-stage/`, `config-previous/`, `config-blocked.marker`, `edge/` and `monitoring-reload/` snapshots |

`~iri/.config/containers/systemd/` must stay **empty**: Quadlet searches it before the delivery
directory, so a unit of the same name there silently shadows every release.

**Networks** are 18 `.network` units with pinned `/24` subnets under `172.28.0.0/16` (IPv6 on the
ingress and proxy networks). `deploy.sh` takes no special action for a changed `.network` unit under
Quadlet — the Compose-only clean-slate recreate does not apply. Whether an existing network picks up
a changed subnet without being removed has not been demonstrated; treat a network-topology change as
a maintenance action and verify with `${UPOD} network inspect <name>` afterwards.

### The runtime seam

Every container operation in `deploy.sh`, `backup.sh`, `restore-drill.sh` and
`container-cleanup.sh` goes through [`scripts/lib/container-runtime.sh`](../scripts/lib/container-runtime.sh)
(`rt_*`, ADR-0163), which detects the runtime by trying it: it finds the lingering user that owns
the containers and reaches it through the sudoers bridge. Under Podman: tags resolve with
`skopeo inspect`, images are pulled as `iri`, "apply and wait" is `systemctl --user start` or
`restart` (restart for every service whose pin or unit this run changed — `start` on an active unit
re-reads nothing), and the wait is structural: `Notify=healthy` makes each unit `Type=notify`,
bounded by the unit's own `TimeoutStartSec=`. The Docker branches remain for the test stack and a
Docker host; they are not the production path.

---

## Releases and promotion

### Cutting a release

Two phases, PR-based; no hand-pushed tag, no tag ever moved.

1. **Prepare.** *Actions → Release · Prepare → Run workflow*, version without the `v` (e.g.
   `1.9.3`). It cuts `[Unreleased]` into a dated CHANGELOG section, regenerates the CycloneDX SBOMs
   (`*/docs/*-bom.{json,xml}` — release-only artefacts), and opens a `chore(release): vX.Y.Z` PR.
2. **Merge that PR.** `release-publish.yml` creates the tag once, at the merge commit, publishes the
   GitHub Release with the eight SBOM files (backend, frontend, ingest, keycloak-spi — REQ-OPS-025),
   attests them (REQ-OPS-023), and the tag push fires `release-images.yml`.

The tag run **does not rebuild**: it cosign-verifies and re-tags the `:sha-<short>` digest `main`
already built, so `:X.Y.Z` and `:sha-<short>` are the same bytes (REQ-OPS-021, ADR-0137). Any doubt
falls back to a full build. The tag is created with the `RELEASE_TOKEN` secret so that it triggers
`release-images.yml`; without it the publish job warns and the images have to be started by hand
(*Actions → Release Images → Run workflow*), which always does a full build.

Nothing is deployed yet: `:stable` still names the previous release.

### Promoting to production

```bash
gh workflow run promote.yml -f version=1.9.3
```

Three gates, in order (REQ-OPS-002, REQ-OPS-024):

1. **Vulnerability scan** of the three app images at the digest the tag resolves to, both
   architectures, failing on a fixed HIGH/CRITICAL finding. Break-glass: `-f allow_vulnerable=true`,
   which is announced in the approval record.
2. **Approval** by the required reviewer on the `production` GitHub Environment (one `approve` job).
3. **Signature** — cosign-verify against the `release-images.yml@refs/(heads/main|tags/v.+)`
   identity, then re-tag all five artifacts to `:stable` in lock-step, `fail-fast`.

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
   wrong image) is logged as `drift: …` and re-applied. A sick container on the **right** image gets
   a targeted restart of that service only, never a release rollback (ADR-0083);
3. otherwise **cosign-verifies every digest** (REQ-OPS-015) — a failure aborts before anything is
   pulled or staged — and writes the digest-pin record and the per-service pin drop-ins;
4. if the config digest moved: extracts the bundle, asserts it carries no secret, applies the
   stateful-infra gate (below), snapshots the live tree to `config-previous/`, mirrors the new tree
   into `/var/iri/code`, **renders `env.d/`**, installs changed units and stops-then-removes units
   the release no longer names;
5. pulls the three app images, restarts every application service whose pin or unit changed,
   starts the rest of the application stack, and waits for health; then stages a moved provider
   JAR and restarts keycloak alone;
6. on success writes the marker, clears the failure records, reconciles the monitoring units and
   the edge (config drift or renewed certificates → edge recreate), and prunes dangling images older
   than 30 days;
7. on a health failure restores the previous config tree, the previous units **and** the previous
   pin drop-ins, restarts, records an exponential backoff for that target (600 s doubling, capped
   at 6 h; `--force` bypasses it) and exits non-zero — `DeployRolledBack` / `DeployFailed`.

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
later.

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

Delivered automatically (REQ-OPS-007, ADR-0055): when `basetool-keycloak-spi:stable` moves,
`deploy.sh` stages `keycloak-spi.jar` into `/var/iri/code/keycloak/providers/` after the stack is
healthy and restarts keycloak alone; a failure restores the previous JAR. The JAR is Java-21
bytecode for Keycloak's JVM. The Discord realm setup is a one-time step in
[`keycloak/DISCORD_KEYCLOAK_SETUP.md`](keycloak/DISCORD_KEYCLOAK_SETUP.md).

Manual fallback only:

```bash
./gradlew :keycloak-spi:jar                                   # on a build machine
install -o deploy -g deploy -m 0644 keycloak-spi-<version>.jar /var/iri/code/keycloak/providers/keycloak-spi.jar
restorecon -F /var/iri/code/keycloak/providers/keycloak-spi.jar
${UCTL} restart keycloak.service
${UPOD} logs --since 2m keycloak | grep -iE 'error|exception|provider' | head
```

### Updating the operational scripts and units

`deploy.sh`, the other scripts and the `iri-*` units are **not** in the config bundle — a deployer
that replaces itself mid-run is a self-update hazard. They change only through the role, as a
deliberate host change:

```bash
ansible-playbook site.yml --limit production --tags deploy,scripts --check --diff
ansible-playbook site.yml --limit production --tags deploy,scripts
ansible-playbook site.yml --limit production --tags observability   # the two collectors and their timers
```

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
- **The API vhost's allow-list** is `docker/edge/include/api-allowlist.conf`, the source of truth
  (ADR-0135). `edge-deny-probe.yml` probes the public deny rules from outside every day.

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

## Internal keystore and certificate rotation

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
${UCTL} restart keycloak.service backend.service ingest.service frontend.service edge.service
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

- Identity `https://github.com/krt-profit/basetool/.github/workflows/release-images.yml@refs/(heads/main|tags/v.+)`,
  issuer `https://token.actions.githubusercontent.com` — the same as `promote.yml`. A
  `workflow_dispatch` build off a feature branch is not trusted.
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
enforced. Its scope is account-wide, which the short expiry compensates for. `RELEASE_TOKEN` is a
separate CI secret and unrelated.

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

**Two steps are Docker-only, deliberately (ADR-0194).** `podman builder prune` is only an alias for
`image prune`, so it is skipped. And **`podman volume prune` is never run**: unlike Docker's, it
removes every volume not currently attached — `edge-certs` and `edge-acme-state` included whenever
the stack is down. The anonymous-volume leak Docker's prune used to absorb is fixed at its source
(`rt_rm_force` removes a container with its anonymous volume). Never run `podman volume prune` by
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
| Health check fails, rollback | `${UPOD} ps`, `${UPOD} logs <svc>`, `${UCTL} status <svc>.service` | a broken release — inspect the rolled-back container's logs |
| Container never starts, `statfs …: no such file or directory` | `${UCTL} status <svc>.service` | a missing bind-mount source — see [Secrets and host-only files](#secrets-and-host-only-files) |
| keycloak cannot read `/run/secrets/keystore.p12` | `${UPOD} logs keycloak`, `getfacl -p /var/iri/secrets/keystore.p12` | the ACL for uid 100999 is missing — re-apply step 4 of the [rotation](#internal-keystore-and-certificate-rotation) |
| backend/frontend/ingest die on the OIDC issuer (`Connect timed out`, `did not match`) | `${UPOD} logs backend` | missing public-name alias drop-in (ADR-0196), or `IRI_KEYCLOAK_HOSTNAME` without its `/auth` path (ADR-0167) |
| every request logged from one internal address | `${UPOD} logs edge` | `EDGE_TRUSTED_PROXY` does not name all six pinned addresses — see [The edge](#the-edge) |
| `curl: … wrong version number` on 443 | `journalctl -u haproxy`, `${UPOD} logs edge` | `EDGE_TRUSTED_PROXY` empty: the edge's listeners are plain while haproxy sends a PROXY header |
| nothing answers on 80/443 | `systemctl status haproxy`, `firewall-cmd --list-all` | haproxy not started (fresh host), or a firewall layer — probe from a third machine |
| Stack comes back after a manual stop | `drift:` lines in the deploy log | the drift check (REQ-OPS-013); stop the timer first |
| `CARVE-OUT: postgres/Keycloak image pin changed` | the deploy log, `config-blocked.marker` | a gated upgrade — see [Stateful-infra upgrades](#stateful-infra-upgrades) |
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
