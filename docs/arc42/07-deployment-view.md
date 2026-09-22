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
| **cadvisor** | **deleted** | Its rootless-Podman support is closed as not-planned upstream. Its series return from `prometheus-podman-exporter` plus `scripts/cgroup-container-metrics.py` — but only where a `basetool:container:*` recording rule normalises the two families. Three did not exist until 2026-09-22 (network receive/transmit and the start time), and the panels that queried cAdvisor for them read **No data** about containers that were running and serving. The exporter labels by `id` alone, so those three join `podman_container_info` to recover the name every other rule is keyed on. |
| **socket-proxy** | **deleted** | It existed only to hand cAdvisor and Alloy a read-only view of the Docker socket. There is no Docker socket. |

Because those names belong to *host* services now, the Quadlet units carry
`AddHost=<name>:host-gateway` aliases. The alias lives in the generated unit rather than in
`prometheus.yml`, so the scrape configuration keeps saying *what* it scrapes and the one
runtime-specific fact stays in the one file that is generated per runtime.

Alloy also changed *how* it reads container logs: it reads the **journal**, keyed on
`__journal_container_name`, instead of the Docker API. A host running a newer config bundle on the
Docker runtime would therefore lose its container-stdout streams — which is why the rollback path
insists the old host's deploy timer is **disabled**, not merely stopped.

That path has **two** host-side preconditions, and both are stated rather than inherited, because
neither is a default one can rely on:

- every `.container` carries **`LogDriver=journald`**. Podman's rootless default is not the same
  everywhere — the testing host resolved `journald`, the production host `k8s-file`, from the same
  release — and a container on `k8s-file` writes into podman's own storage where Alloy never looks.
- the host has a **persistent journal** (`/var/log/journal`, `Storage=persistent`), because
  `loki.source.journal` names that path and systemd keeps the journal in RAM under
  `/run/log/journal` when the directory is absent. Setting the two is not enough: journald switches
  to persistent storage only on a **flush**, which on an ordinary boot comes once from
  `systemd-journal-flush.service` and never again. Measured 2026-09-22 — with `Storage=persistent`
  set and the directory created correctly, four restarts left it empty and `journalctl` kept
  answering from RAM; one `journalctl --flush` moved 127 017 entries in 1.5 s.

Either one missing is silent in the same way: Alloy is `active`, its scrape target is up, and Loki
simply never gains `<svc>-stdout`, `mon-*`, `postgres-*`, `edge` or `ops-cleanup`. `log-streams`
stays green throughout, because it measures Loki's total ingest *rate* and the file-based streams
alone produce one. Measured on the production host on cutover day, 2026-09-22, with both missing.

## 7.4 The operational timers

| Timer | Does |
| --- | --- |
| `iri-deploy` | Pull, Cosign-verify, reconcile (§6.6) |
| `iri-backup` | The nightly restic backup to Nextcloud |
| `iri-restore-drill` | Restore the latest snapshot into a throwaway Postgres and score seven artifacts |
| `iri-cert-expiry` | Write `basetool_certificate_expiry_timestamp_seconds` for the certificate *files* |
| `iri-container-metrics` | The cgroup textfile collector that replaces part of cAdvisor |
| `iri-container-cleanup` | Weekly prune of stopped containers, unused images and networks — runtime-aware (§7.4a) |

### 7.4a The weekly cleanup is runtime-aware, and two of its steps are not portable

The job was `iri-docker-cleanup` until 2026-09-21 and called `docker` directly — the only
operational script that did not go through `lib/container-runtime.sh`. On this host there is no
`docker` binary at all, so the weekly run failed at its first command while the timer stayed
enabled and the alert fired on `absent()` with no way to satisfy it. ADR-0194 records the fix.

Three steps translate; **two do not**, and the difference is the part worth knowing:

| step | Docker | Podman |
| --- | --- | --- |
| stopped containers, unused images, unused networks | pruned | pruned |
| build cache | pruned | skipped — `podman builder prune` is an alias for `image prune`, already run |
| anonymous volumes | pruned | **skipped** |

> [!danger] `podman volume prune` would take the edge's certificates with it
> Docker's `volume prune`, without `--all`, removes **only anonymous** volumes. Podman has no such
> distinction — *"Volumes that are not currently owned by a container will be removed. Note all
> data will be destroyed"* — and its only filter is `label=`. Measured on the target,
> `podman volume ls --filter dangling=true` listed **`edge-certs` and `edge-acme-state`**: the TLS
> material and the ACME account, which are in no snapshot (§1.6 of the cutover runbook carries them
> by hand). They are "dangling" whenever the stack is down, which is exactly when a maintenance job
> runs.
>
> A mechanical rename of this job would therefore have been **worse than the broken job it
> replaced**. The step is skipped on Podman, and the reason it existed was removed at its source:
> the restore drill's throwaway Postgres left its anonymous data volume behind on every run
> (156 MB, measured), and `rt_rm_force` now passes `-v`. On Docker, the weekly prune had been
> silently absorbing that leak for as long as it existed — which is why nobody had seen it.

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
