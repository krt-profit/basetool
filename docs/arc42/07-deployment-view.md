# 7. Deployment view

> **This section describes the system after the Podman cutover.** The Docker-era shape is recorded
> in [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md) and
> [`PODMAN_CUTOVER_RUNBOOK.md`](../PODMAN_CUTOVER_RUNBOOK.md).

## 7.1 One host, three privilege levels

Everything runs on a single Linux host (Rocky Linux). There is no cluster, no orchestrator and no
second continuously-running environment — a deliberate consequence of the one-maintainer constraint
(§2).

| Runs as | What | Why at that level |
| --- | --- | --- |
| **`root`** (system systemd) | The host services: `node-exporter`, `alloy`; the operational timers | They need host-level access — journal, `/var/log`, `/run/systemd/private` |
| **`deploy`** (unprivileged) | `deploy.sh`, `backup.sh`, `restore-drill.sh`, the cleanup and metrics jobs | Runs the automation; can read `.env` and the registry token, cannot administer the host |
| **`iri`** (unprivileged, lingering enabled) | **Every container**, as rootless Podman Quadlet units under the *user* systemd instance | A container escape lands on an account that owns nothing but the containers |

**The uid translation is the fact that surprises everyone.** A rootless container's uid *N* appears
on the host as `subuid_base + N − 1`. With a base of 100000 that makes nginx's 101 → `100100`,
Grafana's 472 → `100471`, Redis's 999 → `100998`, Postgres's 70 → `100069`, and nobody-style 65534
→ `165533`. Every file a container must read has to be owned by the *translated* uid, and a file
copied in as the host's uid 101 is owned by an unrelated account as far as the container is
concerned. This is why data is moved with `podman unshare tar`, which extracts *inside* the user
namespace — and why both the destination directory **and the source archive** have to be readable
in there.

## 7.2 Containers are systemd units

Quadlet `.container`, `.network` and `.volume` files are generated from the compose files by
[`scripts/generate-quadlet.py`](../../scripts/generate-quadlet.py) and shipped in the config
bundle; `systemd` renders them into services. The generator refuses to emit anything it cannot
translate faithfully rather than quietly dropping it — an unrecognised compose key is an error, not
a warning.

Two consequences worth stating:

- **`systemctl` is the operator interface for containers**, and a container's state is a unit
  state. `node_systemd_unit_state` therefore covers the application, which it could not under
  Docker.
- **Named volumes are pinned** (`VolumeName=edge-certs`), so a volume seeded by hand before the
  first deploy is *adopted* rather than shadowed by a `systemd-` prefixed twin.

## 7.3 What moved out of containers, and what was deleted

The cutover did not carry the observability plane across unchanged. Three components could not keep
working as rootless containers, and two had no reason to exist any more:

| Component | Post-cutover | Why |
| --- | --- | --- |
| **node-exporter** | **host service** | Mounts `/run/systemd/private` for its systemd collector, which a rootless container cannot reach — and that collector is exactly the signal this migration gains. It also owns the textfile directory. |
| **alloy** | **host service** | Reads `/var/log` and needs real supplementary groups (`adm`) for `root:adm` files such as `auth.log`. A rootless container's supplementary groups are *namespace* groups, not host groups. |
| **podman-exporter** | **host service** (a *user* unit of the service user) | Has no compose counterpart at all. A system-level one would talk to the root podman and see nothing. |
| **cadvisor** | **deleted** | Its rootless-Podman support is closed as not-planned upstream. Its series return from `prometheus-podman-exporter` plus `scripts/cgroup-container-metrics.py`. |
| **socket-proxy** | **deleted** | It existed only to hand cAdvisor and Alloy a read-only view of the Docker socket. There is no Docker socket. |

Because those names belong to *host* services now, the Quadlet units carry
`AddHost=<name>:host-gateway` aliases. The alias lives in the generated unit rather than in
`prometheus.yml`, so the scrape configuration keeps saying *what* it scrapes and the one
runtime-specific fact stays in the one file that is generated per runtime.

Alloy also changed *how* it reads container logs: it reads the **journal**, keyed on
`__journal_container_name`, instead of the Docker API. A host running a newer config bundle on the
Docker runtime would therefore lose its container-stdout streams — which is why the rollback path
insists the old host's deploy timer is **disabled**, not merely stopped.

## 7.4 The operational timers

| Timer | Does |
| --- | --- |
| `iri-deploy` | Pull, Cosign-verify, reconcile (§6.6) |
| `iri-backup` | The nightly restic backup to Nextcloud |
| `iri-restore-drill` | Restore the latest snapshot into a throwaway Postgres and score seven artifacts |
| `iri-cert-expiry` | Write `basetool_certificate_expiry_timestamp_seconds` for the certificate *files* |
| `iri-container-metrics` | The cgroup textfile collector that replaces part of cAdvisor |
| `iri-docker-cleanup` | **Broken after the cutover — see below** |

> [!warning] `iri-docker-cleanup` does not work on this runtime, and its alert cannot be satisfied
> `scripts/docker-cleanup.sh` calls `docker system df` and `docker volume prune` **directly**. It is
> the only operational script that does not source `scripts/lib/container-runtime.sh`, the
> abstraction `deploy.sh`, `backup.sh` and `restore-drill.sh` all use.
>
> Measured on the migration target on 2026-09-21: **there is no `docker` binary** (the role installs
> `podman` and does not install `podman-docker`), the timer is nevertheless **`enabled`**, and no
> `docker_cleanup` series exists in any textfile. The alert is
> `(time() - basetool_docker_cleanup_last_success_timestamp) > 8d **or absent(...)**`, so it starts
> firing an hour after the cutover and stays firing, and the weekly run fails at its first command.
>
> It is recorded here rather than quietly fixed because it is a *cutover gap*, not a style problem:
> see §11.1 for what closing it involves.

## 7.5 Delivery

```
  git push ──► GitHub Actions ──► GHCR
                 │                 ├── basetool-backend / frontend / ingest   (images)
                 │                 ├── basetool-config                        (config bundle)
                 │                 └── basetool-keycloak-spi                  (provider JAR)
                 │                        │
                 │   promote.yml  ────────┘  moves :stable to a chosen digest
                 │   (a deliberate act)
                 ▼
            Cosign signatures + SLSA provenance + SBOM attestations
                                          │
                        iri-deploy.timer ─┴─► verify → pull → render env.d → reconcile units
```

`deploy.sh` and the systemd units are **not** part of the config bundle: a bundle cannot rewrite the
thing that applies bundles. Provider JARs are barred from the config bundle and get their own
promotable, signed artifact.

## 7.6 Backups leave the host

`restic` through an `rclone` WebDAV remote to **Nextcloud**, on a GFS retention policy, with the
repository password and the WebDAV app-password in `/etc/iri/backup.env` — readable by `deploy`
only. Deliberately a different provider account from the host, so that losing one does not lose
both.
