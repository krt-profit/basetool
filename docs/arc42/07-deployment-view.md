# 7. Deployment view

> **This section describes production as it has run since the Podman cutover of 2026-09-22.** The
> Docker-era shape and the migration itself are history, recorded in the archived
> [`PODMAN_MIGRATION_PLAN.md`](../archive/PODMAN_MIGRATION_PLAN.md) and
> [`PODMAN_CUTOVER_RUNBOOK.md`](../archive/PODMAN_CUTOVER_RUNBOOK.md).

## 7.1 One host, three privilege levels

Production is a single Rocky Linux 10 host with SELinux enforcing, rootless Podman and systemd.
There is no cluster, no orchestrator and no standby — a deliberate consequence of the
one-maintainer constraint (§2). Two other machines exist around it: a **testing host** built by the
same role and fed by its own `:testing` channel (`REQ-OPS-022`), and the **retired Docker host**,
shut down on cutover day but not yet decommissioned (§7.7). Addresses live in the knowledge base's
Hosts note, not here.

The host is provisioned by the Ansible role in [`ansible/`](../../ansible/README.md) (ADR-0188):
packages, the two accounts below and the subuid range, directory ownership, SELinux contexts,
`containers.conf`, the CIS level-1 scan, firewalld and fail2ban, haproxy, the host half of the
monitoring plane, and unattended **security** updates (`dnf-automatic`, the container runtime
excluded, never rebooting — `REQ-OPS-032`, since 2026-09-22). It provisions and never delivers — nothing it does ships a unit, an image or a
configuration bundle.

| Runs as | What | Why at that level |
| --- | --- | --- |
| **system services** (system systemd) | `haproxy` on `:80`/`:443` (ADR-0187), `node-exporter`, `alloy`; the `iri-cert-expiry` and `iri-container-metrics` collectors as `root` | They need the public ports or host-level access — journal, `/var/log`, the cgroup tree, `/run/systemd/private` |
| **`deploy`** (unprivileged) | `deploy.sh`, `backup.sh`, `restore-drill.sh`, `container-cleanup.sh` | Runs the automation; can read `.env` and the registry token, cannot administer the host |
| **`iri`** (unprivileged, lingering enabled) | **Every container**, as rootless Podman Quadlet units under its *user* systemd instance, plus `prometheus-podman-exporter` as a user unit | A container escape lands on an account that owns nothing but the containers |

**The uid translation is the fact that surprises everyone.** A rootless container's uid *N* appears
on the host as `subuid_base + N − 1`. With a base of 100000 that makes nginx's 101 → `100100`,
Grafana's 472 → `100471`, Redis's 999 → `100998`, Postgres's 70 → `100069`, and nobody-style 65534
→ `165533`. Every file a container must read has to be owned by the *translated* uid, and a file
copied in as the host's uid 101 is owned by an unrelated account as far as the container is
concerned. This is why volume data is restored with `podman unshare tar`, which extracts *inside*
the user namespace ([`docs/backup.md`](../backup.md)) — and why both the destination directory
**and the source archive** have to be readable in there.

## 7.2 Containers are systemd units

Quadlet `.container`, `.network` and `.volume` files — eighteen containers, eighteen networks and
three volumes, plus one `env.d` template per container — are generated from the compose files by
[`scripts/generate-quadlet.py`](../../scripts/generate-quadlet.py), committed under
[`quadlet/`](../../quadlet/), kept honest by the `quadlet-drift` CI job, and shipped in the config
bundle; `systemd` renders them into services. The generator refuses to emit anything it cannot
translate faithfully rather than quietly dropping it — an unrecognised compose key or a service with
no recorded disposition is an error, not a warning. The compose files stay the source, and the local
and test stacks still run on them.

Consequences worth stating:

- **`systemctl --user` is the operator interface for containers**, and a container's state is a
  unit state. `node_systemd_unit_state` therefore covers the application, which it could not under
  Docker.
- **Every container runs on a read-only root filesystem with every capability dropped**, `acme`
  alone getting `CHOWN` back (ADR-0190, `REQ-OPS-014`). The stateful ones — both PostgreSQL
  instances and Redis — start as their own uid instead of as root that steps down, so they need no
  capability back (ADR-0189).
- **Named volumes are pinned** (`VolumeName=edge-certs`), so a volume seeded by hand is *adopted*
  rather than shadowed by a `systemd-` prefixed twin.
- **A container is stopped with its own grace.** Compose's `stop_grace_period` becomes two keys:
  `StopTimeout=` (what Quadlet's `podman rm -f` waits before `SIGKILL`) and `TimeoutStopSec=` fifteen
  seconds longer (what systemd waits for podman). Until 2026-09-22 only the second was generated,
  and podman killed every container after its 10 s default — the JVMs mid-shutdown, Loki and Tempo
  mid-drain, PostgreSQL mid-checkpoint.
- **The config tree is mounted read-only.** Every bind mount from `/var/iri/code` — what the deployer
  rewrites on each release — carries `:ro`, and the generator refuses one that does not (`REQ-OPS-014`).
- **The data networks have no egress.** `net-db-*` and `net-redis-*` are `Internal=true` in the
  units, so the two databases and Redis, which sit on nothing else, cannot reach the internet
  (ADR-0162, extended 2026-09-22). Compose keeps them non-internal for the local `-dev` twins, which
  publish their ports there.
- **Redis's ACL is rendered, not written.** `/var/iri/redis/users.acl` comes from
  `scripts/redis-users.acl.tmpl` through `render-redis-acl.py` (installed by the role), one user per
  service and SHA-256 hashes only, and is applied live with `ACL LOAD` (REQ-SEC-068, ADR-0207).
  The unit carries `--notify-keyspace-events Egx` and an unauthenticated `PING` health probe, so
  neither depends on which ACL users exist.
- **Each service mounts its own keystore and an internal truststore** — `/run/secrets/keystore.p12`
  from `IRI_<SERVICE>_KEYSTORE_HOST_PATH`, `/run/secrets/internal-truststore.p12` from
  `IRI_INTERNAL_TRUSTSTORE_HOST_PATH` (REQ-SEC-070, ADR-0211). The generator bakes all five to
  the shared `/var/iri/secrets/keystore.p12` until the owner has minted `/var/iri/secrets/tls/` with
  `mint-internal-tls.sh` (installed by the role, run through the backend image); a later release
  flips them. `deploy.sh` refuses a release whose units mount any PKCS#12 the host lacks.
- **Podman features go through Quadlet keys, not raw arguments** — `RunInit=`, `Ulimit=` and the
  network's `Options=` since 2026-09-22. Only `--cpus` and `--oom-score-adj`, which have no key in
  podman 5.8, remain `PodmanArgs=`.
- **The edge publishes on loopback only** and is pinned with `ip=` on every network it joins, so
  the set of addresses haproxy's PROXY header can arrive from is finite (§11.5c).

## 7.3 What moved out of containers, and what was deleted

The cutover did not carry the observability plane across unchanged. Three components could not keep
working as rootless containers, and two had no reason to exist any more:

| Component | Now | Why |
| --- | --- | --- |
| **node-exporter** | **host service** | Mounts `/run/systemd/private` for its systemd collector, which a rootless container cannot reach — and that collector is exactly the signal this migration gains. It also owns the textfile directory. |
| **alloy** | **host service** | Reads `/var/log` and needs real supplementary groups (`adm`) for `root:adm` files such as `auth.log`. A rootless container's supplementary groups are *namespace* groups, not host groups. |
| **podman-exporter** | **host service** (a *user* unit of the service user) | Has no compose counterpart at all. A system-level one would talk to the root podman and see nothing. |
| **cadvisor** | **deleted** | Its rootless-Podman support is closed as not-planned upstream. Its series return from `prometheus-podman-exporter` plus `scripts/cgroup-container-metrics.py`, normalised by the `basetool:container:*` recording rules that dashboards and alerts read. The exporter labels by `id` alone, so the network and start-time rules join `podman_container_info` to recover the container name. |
| **socket-proxy** | **deleted** | It existed only to hand cAdvisor and Alloy a read-only view of the Docker socket. There is no Docker socket. Both services were removed from `docker-compose.monitoring.yml` on 2026-09-22 (OPS-SIMP-02), and the cAdvisor legs of the recording rules with them. |

Becoming host services cost node-exporter and alloy two things the container shape gave them for
free, and both came back on 2026-09-22: a **memory ceiling** (a role-written `20-resources.conf`
drop-in per unit, `MemoryMax` + `GOMEMLIMIT` at the container budgets) and **being watched** — the
cgroup collector now reads their unit cgroups under `system.slice` and publishes them under their old
container names, so the container memory, OOM and pids alerts cover them as before. See
REQ-OBS-014.

Because those names belong to *host* services, the Quadlet units carry
`AddHost=<name>:host-gateway` aliases (`alloy` for the JVMs and Keycloak; `node-exporter`, `alloy`
and `podman-exporter` for Prometheus). The alias lives in the generated unit rather than in
`prometheus.yml`, so the scrape configuration keeps saying *what* it scrapes and the one
runtime-specific fact stays in the one file that is generated per runtime. The same mechanism, as a
role-written drop-in, points the host's **own public names** at `host-gateway` for the containers
that dial them, because a rootless container cannot hairpin to the host's public address
(ADR-0196, §11.5a).

Alloy also changed *how* it reads container logs: it reads the **journal**, keyed on
`__journal_container_name`, instead of the Docker API. A host running a newer config bundle on the
Docker runtime would therefore lose its container-stdout streams — which is why the retired host's
deploy timer is **disabled**, not merely stopped (§7.7).

That path has **two** host-side preconditions, and both are stated rather than inherited, because
neither is a default one can rely on:

- every `.container` carries **`LogDriver=journald`** — Podman's rootless default resolved to
  `k8s-file` on the production host, which writes where Alloy never looks;
- the host has a **persistent journal** (`/var/log/journal`, `Storage=persistent`), and the role
  restarts journald **and flushes it**, because journald moves to persistent storage only on a
  flush — measured 2026-09-22, when four restarts left the directory empty.

Either one missing is silent in the same way: Alloy is `active`, its scrape target is up, and Loki
simply never gains `<svc>-stdout`, `mon-*`, `postgres-*`, `edge` or `ops-cleanup` — while
`log-streams` stays green, because the file-based streams alone keep Loki's ingest rate up. Both
were missing on cutover day; the fix ships with v1.9.2 (§11.6).

## 7.4 The operational timers

All seven are installed by the Ansible role — six from [`scripts/`](../../scripts/), plus the
distribution's own `dnf-automatic.timer` with a role drop-in; none rides the config bundle.

| Timer | Schedule | Runs as | Does |
| --- | --- | --- | --- |
| `iri-deploy` | every 5 min | `deploy` | Pull, Cosign-verify, reconcile (§6.6) |
| `iri-backup` | daily 04:15 | `deploy` | The restic backup to Nextcloud (§6.7) |
| `iri-restore-drill` | Sunday 05:30 | `deploy` | Restore the latest snapshot into a throwaway Postgres and score seven artifacts |
| `iri-cert-expiry` | daily 03:40, and at boot | `root` | Write `basetool_certificate_expiry_timestamp_seconds` for the certificate *files* |
| `iri-container-metrics` | every 30 s | `root` | The cgroup textfile collector that replaces part of cAdvisor |
| `iri-container-cleanup` | Saturday 02:00 UTC | `deploy` | Weekly prune of stopped containers, unused images and networks — never volumes (§7.4a) |
| `dnf-automatic` | daily 07:00 (+ up to 15 min) | `root` | Security advisories only, container runtime excluded, never reboots; each run records itself for `HostSecurityUpdates*` / `HostRebootRequired` (`REQ-OPS-032`) |

### 7.4a The weekly cleanup never prunes volumes

`iri-container-cleanup` (ADR-0194) runs `container-cleanup.sh` through `lib/container-runtime.sh`
as the rootless service user, and what it does **not** prune is the part worth knowing:

| what | weekly cleanup |
| --- | --- |
| stopped containers, unused images, unused networks | pruned |
| build cache | not run — `podman builder prune` is an alias for `image prune`, which already ran |
| volumes, anonymous or named | **never** |

> [!danger] `podman volume prune` would take the edge's certificates with it
> It removes every volume not currently owned by a container — *"Note all data will be
> destroyed"* — and its only filter is `label=`. Measured on the production host,
> `podman volume ls --filter dangling=true` listed **`edge-certs` and `edge-acme-state`**: the TLS
> material and the ACME account. They are "dangling" whenever the stack is down, which is exactly
> when a maintenance job runs — and although the nightly backup carries both, a prune would still
> take the live copy and the edge with it.
>
> The one producer of anonymous volumes — the restore drill's throwaway Postgres, 156 MB per run,
> measured — was fixed at its source instead: `rt_rm_force` removes a container together with its
> anonymous volume.

History — the job's Docker form (`iri-docker-cleanup`, five steps, volume pruning included) and why
two steps did not survive the move: ADR-0194, `docs/archive/PODMAN_MIGRATION_PLAN.md` and git.

### 7.4b What the timers wait for after a boot, and what they deliberately do not

The four units that reach the containers -- `iri-deploy`, `iri-backup`, `iri-restore-drill`,
`iri-container-cleanup` -- run as the deploy account and reach the stack through the rootless
service user. They used to be ordered on `docker.service`, which on this host does not exist: inert,
and read as if it ordered them on their runtime, so nothing noticed that nothing did.

The first reboot after the cutover showed what that costs. Three of the timers carry
`Persistent=true`, so their catch-up runs fired eleven seconds after boot -- one second before
`user@<uid>.service` came up. All three failed in runtime detection, `SystemdUnitFailed` paged
critical, and each stayed failed until its next scheduled run: the next night, or the next week.
The catch-up `Persistent=true` exists for was the run that was lost.

Two waits now live in `scripts/lib/container-runtime.sh`, not in the units, because only the script
knows the service user's uid and only the script can see the signal that matters. (The four units
share one sandbox, the drop-in `10-deploy-account-sandbox.conf` the role installs beside each —
OPS-SIMP-03.)

| wait | who | on what | bound |
| --- | --- | --- | --- |
| `rt_detect` | all four | the service user's runtime directory appearing, **only** when it is absent -- a runtime that exists and refuses is a real answer and is not waited on | 120 s |
| `rt_wait_for_startup` | backup, drill, cleanup | the user manager's `is-system-running` leaving `starting` -- `running` or `degraded` | 600 s |

The second exists because the first is not enough. `user@<uid>.service` reports ready as soon as
the manager runs; measured on the same boot, that was 16:26:03, while the manager's own startup
finished at 16:27:33. An `After=user@<uid>.service` would have fixed detection and then let the
backup's quiesce stop the backend while it was still starting.

**`iri-deploy` does not wait for startup, on purpose.** A stack stuck in `starting` because a unit
will not come up may be exactly what the next release exists to fix, and a deployer that refused to
act until startup finished could never deliver it. `container-runtime.test.sh` asserts both the
three calls and the one absence.

## 7.5 Delivery

```
  git push ──► GitHub Actions ──► GHCR
                 │                 ├── basetool-backend / frontend / ingest   (images)
                 │                 ├── basetool-config                        (config bundle: compose,
                 │                 │                                           Quadlet units, monitoring,
                 │                 │                                           Keycloak theme)
                 │                 └── basetool-keycloak-spi                  (provider JAR)
                 │                        │
                 │   promote.yml  ────────┤  moves :stable to a chosen digest (production)
                 │   promote-testing.yml ─┘  moves :testing (the testing host)
                 │   (each a deliberate act)
                 ▼
            Cosign signatures + SLSA provenance + SBOM attestations
                                          │
                        iri-deploy.timer ─┴─► verify → pull → render env.d → reconcile units
```

Only `main` and a `vMAJOR.MINOR.PATCH` tag on a commit already on `main` may sign (the `ref-guard`
job in `release-images.yml`), and only the jobs that sign hold the OIDC token that signing needs;
the jobs that run the build do not. `promote.yml`, `promote-testing.yml` and `deploy.sh` trust the
same anchored signer identity (REQ-OPS-015).

**The three app images come from one Dockerfile** (`docker/app/Dockerfile`, since 2026-09-23), with
`MODULE` selecting the Gradle project, the training stubs and a per-module tail stage for the port and
the healthcheck. The build runs `:<module>:bootJar` — exactly the artefact that ships, none of the
`check` gates, which CI runs — and bakes a Java **AOT cache** (`/app/app.aot`) from a training start
that refreshes the whole Spring context against stubs for the database, Keycloak and Redis. A
training run that does not complete, or a cache a JVM with the image's object layout would refuse,
fails the image build; a deployment whose `JAVA_TOOL_OPTIONS` layout differs starts without the cache
and trips `JvmStartupCacheRejected` (REQ-OPS-030,
[ADR-0209](../adr/0209-the-images-ship-a-java-aot-cache-trained-eagerly-and-verified-at-build.md)).
The entrypoint is `java` itself, in exec form — no shell between the runtime and the JVM.

**A push builds only when it changes an image.** The release-tag run re-tags what `main` built for
the same commit (ADR-0137), and a `main` push whose range touches no image input — the paths
`docker/app/Dockerfile` copies, the Dockerfile, `.dockerignore`, the release workflow and its BuildKit
and version inputs — re-tags the previous `main` build after verifying its signature, both
architectures and an age of at most seven days (ADR-0210, REQ-OPS-021). Release commits always
build.

`deploy.sh` and the host's own `iri-*` units are **not** part of the config bundle: a bundle cannot
rewrite the thing that applies bundles, so they arrive with the Ansible role. The Quadlet units do
ride the bundle. Provider JARs are barred from the config bundle and get their own promotable,
signed artifact (ADR-0055). Requirements:
[`deployment-delivery.md`](../specs/deployment-delivery.md).

**The Keycloak realm is the one piece of the deployment no artifact carries.** It lives in
`db-keycloak` on each host, so delivery keeps images, units and provider JAR in lock-step across
production and testing while the two realms were free to diverge — and did: on 2026-09-22 the
testing realm lacked three of the Basetool's clients, both audience scopes and the DPoP policy.
Its Basetool-owned part is therefore code too, applied by an operator rather than by the timer:
`scripts/provision-keycloak-realm.py` converges a realm to production's shape, additively, and
`scripts/keycloak-config-snapshot.sql` is the diff that shows whether two realms still agree
(`REQ-OPS-033`, ADR-0202).

## 7.6 Backups leave the host

`restic` through an `rclone` WebDAV remote to **Nextcloud**, on a GFS retention policy, with the
repository password and the WebDAV app-password in `/etc/iri/backup.env` — readable by `deploy`
only. Deliberately a different provider account from the host, so that losing one does not lose
both. Procedure: [`docs/backup.md`](../backup.md).

## 7.7 The retired Docker host

The Docker Compose host that served production until 2026-09-22 is **shut down, not
decommissioned**. Its deploy timer is disabled, so powering it back on would serve the configuration
it had at the cutover and pull nothing newer — that is the way back the archived cutover runbook
describes, and the only reason the machine still exists. It runs **its own copy** of the operational
scripts, which is why the repository's no longer carry a Docker branch (OPS-SIMP-01, 2026-09-22): a
release never reaches that host, and a way back that depended on today's scripts would not be one. It still holds a full copy of the
production data and secrets as of that day; §11.6 carries it as an open risk until it is wiped and
deleted.
