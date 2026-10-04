# Profit Basetool — Monitoring Plane

The living operator document for the observability stack that watches the Profit Basetool
deployment (host, containers, apps, edge, business signals): what runs where, how configuration
reaches the host, what to do when an alert fires, and the procedures that recur — rebuilding the
plane on a new host, re-rendering the Alertmanager config, re-checking a log signature after a JVM
bump, and the monitoring half of the restore drill.

The binding rules (what is collected, what may be labelled, what must be alerted) are
[`docs/specs/observability.md`](../docs/specs/observability.md) (`REQ-OBS-*`). The one-time rollout
of 2026-07 and the Podman cutover of 2026-09-22 are historical records in
[`docs/archive/MONITORING_ROLLOUT_RUNBOOK.md`](../docs/archive/MONITORING_ROLLOUT_RUNBOOK.md) and
[`docs/archive/PODMAN_CUTOVER_RUNBOOK.md`](../docs/archive/PODMAN_CUTOVER_RUNBOOK.md); everything from
them that recurs lives here.

> [!important] Production is rootless Podman under Quadlet since 2026-09-22
> There is no Docker, no cAdvisor, no docker-socket-proxy and no Nginx Proxy Manager on the
> production host (ADR-0162, ADR-0163). `docker-compose.monitoring.yml` is still the **source** the
> Quadlet units are generated from and still runs on a Docker workstation, so Docker commands in
> this file are for local validation only. Any production command below runs on the Rocky host; on
> production, reading is standing-permitted and **every write needs @greluc's explicit per-action
> approval** (repository `CLAUDE.md` → *Production host access*).

## Overview

**What runs where.** The monitoring plane is two halves on one host:

- **Nine Quadlet container units** of the rootless service user `iri`, generated from
  `docker-compose.monitoring.yml` by [`scripts/generate-quadlet.py`](../scripts/generate-quadlet.py)
  into [`quadlet/systemd/`](../quadlet/systemd/) and installed by `deploy.sh` into
  `/etc/containers/systemd/users/<uid>/`: `prometheus`, `grafana`, `loki`, `tempo`, `alertmanager`,
  `blackbox-exporter`, `postgres-exporter-backend`, `postgres-exporter-keycloak`, `redis-exporter`.
  Each is a `systemctl --user` unit of `iri`, and its environment is rendered from the host `.env`
  into `/var/iri/code/env.d/<svc>.env` by [`scripts/render-env-d.py`](../scripts/render-env-d.py).
- **Host services** installed and configured by the Ansible role
  ([`ansible/roles/basetool_host/tasks/27-observability.yml`](../ansible/roles/basetool_host/tasks/27-observability.yml)),
  because a rootless container cannot do their job: `node_exporter` (`:9100`; needs the systemd
  dbus socket), `alloy` (`:12345`; needs the real `adm` and `systemd-journal` groups), and
  `prometheus-podman-exporter` (`:9882`, a **user** unit of `iri`, so it sees the rootless
  containers). Two collector timers write into node_exporter's textfile directory
  `/var/iri/monitoring/textfile`: `iri-container-metrics.timer` (every 30 s,
  [`scripts/cgroup-container-metrics.py`](../scripts/cgroup-container-metrics.py) — the replacement
  for cAdvisor) and `iri-cert-expiry.timer` (daily 03:40,
  [`scripts/cert-expiry-metrics.py`](../scripts/cert-expiry-metrics.py)). `deploy.sh`, `backup.sh`,
  `restore-drill.sh` and `container-cleanup.sh` write their own textfile metrics there too.

**How the two halves reach each other.** Container-to-host: Prometheus reaches the three host
services through `AddHost=node-exporter|alloy|podman-exporter:host-gateway`, and the backend,
frontend, ingest and Keycloak units reach Alloy's OTLP receiver through `AddHost=alloy:host-gateway`
(`PODMAN_HOST_ALIASES` in the generator). Host-to-container: rootless networks live in a user
namespace the host has no route into, so Loki (`127.0.0.1:3100`) and Tempo's OTLP port
(`127.0.0.1:4327`) are published on loopback for the host-native Alloy (`PODMAN_LOOPBACK_PUBLISH`,
`IRI_ALLOY_LOKI_ENDPOINT` / `IRI_ALLOY_TEMPO_ENDPOINT` in `/etc/sysconfig/alloy`). The host services
bind all interfaces and are kept off the internet by the default-deny `firewalld` zone.

**Networks** (Quadlet `.network` units): `net-monitoring-scrape` (Prometheus, the exporters, the app
management ports), `net-monitoring-core` (Prometheus, Alertmanager, Loki, Tempo, Grafana — never an
application container), `net-blackbox-v6` (the blackbox exporter's own IPv6 egress for the v6
probes), plus the app data networks the exporters bridge into. `net-docker-proxy` exists only in the
compose file.

**Grafana is the only UI**, published by the native edge (`docker/edge/conf.d/40-grafana.conf.template`)
behind Keycloak OIDC and restricted to the realm role `Admin` (REQ-OBS-008).

| Component | Runs as | Defined in |
| --- | --- | --- |
| Prometheus | Quadlet unit | `docker-compose.monitoring.yml` → `quadlet/systemd/prometheus.container` |
| Grafana (OSS) | Quadlet unit | same pattern |
| Loki | Quadlet unit | same pattern |
| Tempo | Quadlet unit | same pattern |
| Alertmanager | Quadlet unit | same pattern |
| blackbox_exporter | Quadlet unit | same pattern |
| postgres_exporter ×2 | Quadlet units | same pattern |
| redis_exporter | Quadlet unit | same pattern |
| Alloy | host service (`alloy.service`, rpm.grafana.com) | `27-observability.yml` + `alloy.service.d/10-log-sources.conf`, `20-resources.conf` (512M, `GOMEMLIMIT=360MiB`) |
| node_exporter | host service (`prometheus-node-exporter.service`, EPEL) | `27-observability.yml` (`basetool_host_node_exporter_flags`) + `prometheus-node-exporter.service.d/20-resources.conf` (64M, `GOMEMLIMIT=48MiB`) |
| prometheus-podman-exporter | user unit of `iri` (EPEL) | `27-observability.yml` |
| container metrics collector | `iri-container-metrics.timer` (root, read-only) | `scripts/cgroup-container-metrics.py` — the container cgroups plus the two host services above |
| certificate expiry collector | `iri-cert-expiry.timer` (root, read-only) | `scripts/cert-expiry-metrics.py` |

**Versions are deliberately not listed here.** A hand-maintained version column went stale on the
next Dependabot bump (it was wrong on 8 of 12 rows before it was removed), and it answers the wrong
question anyway, because the *running* process can differ from the committed pin. Ask the sources:

```bash
# Intended (committed) versions — the compose file is where a version is chosen:
grep -E '^\s+image:' docker-compose.monitoring.yml
grep -h '^Image=' quadlet/systemd/{prometheus,grafana,loki,tempo,alertmanager,blackbox-exporter}.container

# Actually running on the host — the one that matters during an incident:
sudo -u iri podman ps --format '{{.Names}} {{.Image}}'
rpm -q alloy node-exporter prometheus-podman-exporter     # the host services
```

The two host packages are not pinned by the role: `alloy` and `node-exporter` resolve to the
versions the compose file pins (measured 2026-09-18), and a second pin would be a drift source.
Whether they still do is checked, not assumed: `python scripts/check-conformance.py --ssh <host>
--only host-exporter-versions` compares each running `*_build_info` with the compose pin and fails
on any difference (OPS-SEC-06, 2026-09-22).
Tags quoted inside runnable commands in this repository *are* pinned, and
[`scripts/check-monitoring-image-pins.sh`](../scripts/check-monitoring-image-pins.sh) (CI,
`repo-lint.yml`) fails the build if any of them drifts from the compose file. It scans every tracked
text file, including the promtool test headers under `prometheus/tests/`; a file whose tags are
deliberately not pins opts out with an `image-pin-gate: ignore-file` marker in its first ten lines.

## Config tree map

- **`prometheus/prometheus.yml`** — scrape configuration. Retention (180d / 40GB), the admin API and
  the remote-write receiver are **not** in this file (`--web.enable-lifecycle` was removed on
  2026-09-22 — nothing used it); they are flags in
  the Prometheus service's `command:` in the compose file, carried into `prometheus.container`'s
  `Exec=` line.
- **`prometheus/alerts/*.yml`** — alerting and recording rules by concern: `infrastructure.yml`,
  `containers-runtime.yml` (the `basetool:container:*` recording rules every container alert and
  `02-containers.json` read, plus the collector-liveness alerts), `apps.yml`, `business.yml`,
  `ops-automation.yml`, `meta.yml`.
- **`prometheus/tests/*_test.yml`** — `promtool test rules` unit tests for the rules above.
- **`loki/loki-config.yml`** — Loki: 744h (31d) compactor retention for every stream, ingest rate
  caps, `reject_old_samples_max_age: 168h`.
- **`loki/rules/fake/basetool-log-alerts.yml`** — the LogQL ruler alerts evaluated by Loki.
- **`tempo/tempo.yaml`** — Tempo 3.x, monolithic, 14d trace retention.
- **`alloy/config.alloy`** — log shipping (file tails, the journal for container stdout), OTLP →
  Tempo forwarding, and the per-stream masks. One file, true for both the container and the host
  shape (REQ-OBS-019). The app JSON streams index only `level`; the ingest gateway's exchange
  lines additionally carry `client_id` and `route` as Loki **structured metadata** (not labels),
  so `{app="ingest"} | client_id="versekit"` filters by client without parsing (REQ-XCH-028).
- **`blackbox/blackbox.yml`** — blackbox_exporter modules.
- **`alertmanager/alertmanager.yml.tmpl`** — **template**, rendered by hand on the host (see
  *Re-rendering the Alertmanager config* below). The rendered file carries secrets and is never
  committed.
- **`grafana/provisioning/{datasources,dashboards}`** + **`grafana/dashboards/*.json`** — 14
  dashboards, provisioned **read-only** (see the sandbox-export workflow below). `15-exchange.json`
  („Exchange") is the per-client view of the third-party exchange, with a `client_id` variable
  that filters its metrics and its gateway log panel; the operations dashboard keeps its
  cross-client exchange panels.

> **Client attribution of relayed exchange calls (operator note).** `basetool_api_client_requests_total`
> counts a call the ingest gateway relays for an exchange client under that client's registered id
> (`client_id="versekit"`), not under the gateway's own id; a relayed id the registry does not hold
> counts as `other` and raises `ApiUnknownClient`. Since the legacy extractor relays were removed
> (#2092 step 9, 2026-09-28) the gateway's own series stays flat, and a series per client appears on
> the operations dashboard's per-client panel once a client goes live — expected, not an incident.
> The audit trail follows the same rule (REQ-AUDIT-005, REQ-XCH-010): exchange writes name the
> client in the viewer's client filter, and rows written before keep what they recorded — nothing
> is backfilled.

### How a config change reaches the running process

The config bundle is mirrored to `/var/iri/code` on every release. What happens next depends on the
component:

| Change to | Reaches the process | How |
| --- | --- | --- |
| `prometheus/**` | automatically | `deploy.sh` restarts `prometheus.service` |
| `alloy/**` | automatically | `deploy.sh` restarts the host `alloy.service` (one named sudoers entry) |
| `blackbox/**` | automatically | `deploy.sh` restarts `blackbox-exporter.service` |
| `grafana/dashboards/**` | automatically | Grafana file-provisioning |
| `loki/rules/**` | automatically | the Loki ruler polls its rule directory |
| a unit definition (image, limit, env) | automatically | `deploy.sh` installs changed units, renders `env.d`, restarts what changed |
| `alertmanager/alertmanager.yml.tmpl` | **never on its own** | re-render by hand, then restart `alertmanager.service` |
| `loki/loki-config.yml`, `tempo/tempo.yaml` | on the next restart | restart the unit by hand |

`deploy.sh` diffs each reconciled subtree against a persisted snapshot of what the process was last
started with and restarts it on any drift, on **every** healthy tick — the release apply and the
steady-state no-op alike — so a missed apply self-heals on the next tick. A `SIGHUP` is deliberately
not used: the configs are single-file bind mounts, the mirror writes a fresh inode, and a reloaded
process would re-read the old one. The reconcile runs only while `IRI_MONITORING_ENABLED=true`
(read from the host `.env`; see **MonitoringReconcileDisabled** below). Manual restarts:

```bash
SYSTEMCTL="sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user"
${SYSTEMCTL} restart alertmanager.service       # or loki / tempo / prometheus / grafana
sudo systemctl restart alloy.service            # the host-native shipper
```

## Privacy / retention at a glance

- **Metrics — 180d.** No PII; bounded labels only (REQ-OBS-006).
- **Logs — 31d, for every stream.** The edge access/error log (`app="edge"`), SSH/host-auth,
  host-auditd (config / `authorized_keys` tamper) and host-fail2ban streams retain client IPs /
  usernames (owner-approved, covered by the privacy policy — REQ-OBS-010). **Both** Keycloak streams
  are masked in the shipper (`username=` / `ipAddress=`): the file log (`app="keycloak"`) and the
  container console (`app="keycloak-stdout"`). The `<svc>-stdout`, `mon-grafana` and
  `mon-alertmanager` streams are masked in the shipper as well. The four ops-automation streams
  (`ops-deploy` / `ops-backup` / `ops-cleanup` / `ops-restore-drill`) carry no end-user identity; the
  shipper masks JWTs, e-mail addresses, bearer-token keywords and the GHCR account name in them as
  defence in depth. Every mask is gate-checked by `scripts/check-alloy-log-masking.py` (REQ-OBS-007).
- **Loki is excluded from backups**, so its retention window cannot be extended by restoring an old
  snapshot.
- **Traces — 14d.**
- **Grafana is admin-only** (Keycloak realm role `Admin`).

## Alert-response runbook (alert → first actions)

Every alert rule in `prometheus/alerts/` and the Loki ruler has a row, alone or grouped with its
siblings; a new rule gets one in the same change. Every rule's own `description` carries the rest,
and that text is what reaches the mailbox. **Alert e-mails never contain user data.** Confirm the signal in the
matching Grafana dashboard, then act. `${SYSTEMCTL}` is the service-user `systemctl --user` from the
section above.

| Alert | First actions |
| --- | --- |
| **TargetDown** | A **non-probe** scrape target is down. For a container: `${SYSTEMCTL} status <svc>.service`. For `node`, `alloy` or `podman-exporter`: the host service (`systemctl status prometheus-node-exporter alloy`, or `${SYSTEMCTL} status prometheus-podman-exporter`). Blackbox `/probe` jobs are deliberately excluded — there `up==0` is a scrape-timeout artifact; a real probe failure is `probe_success==0` and pages via `BlackboxProbeFailed` / `EdgeIpv6Unreachable` / `DnsResolutionFailed` / `CertificateExpiringSoon` instead. Only `blackbox-exporter` (self `/metrics`) firing here means the exporter itself is down. |
| **BlackboxProbeFailed** | An HTTPS liveness probe failed. Curl the endpoint from outside and from the host; check the edge (`${SYSTEMCTL} status edge.service`), the host front end (`systemctl status haproxy`), the certificate and upstream health. If it fails from inside only, check the public-name aliases (ADR-0196): a rootless container cannot reach its own host through the public address. |
| **EdgeIpv6Unreachable** | A public vhost answers over IPv4 but not IPv6. Check the AAAA record and the front end's v6 listener; `curl -6` from an IPv6-capable host. If it answers from outside, the probe path itself is broken: the v6 alias and pasta's v6 guest mapping (`basetool_host_public_name_aliases_v6`, `basetool_host_pasta_map_guest_addrs`) — the 2026-09-22 false positive. |
| **DnsResolutionFailed** | The public DNS probe against 1.1.1.1 failed — the apex or the API vhost is not resolving an A/AAAA record. Check the zone (`dig A`/`dig AAAA profit-base.online @1.1.1.1`). |
| **HostDiskCritical / HostDiskWarning** | Disk filling. Find the largest consumers (`du -xh /var/iri --max-depth 2`, `journalctl --disk-usage`, `sudo -u iri podman system df`). Reclaim images with the cleanup job (`systemctl start iri-container-cleanup.service`); **never** `podman volume prune` — it removes the named `edge-certs` / `edge-acme-state` volumes (ADR-0194). Critical = act now; the monitoring stores share `/var/iri` with both PGDATA directories. |
| **HostInodesCritical** | **CRITICAL.** A filesystem has used more than 90 % of its inodes — writes fail with free space left. Look for directories with many small files (`find /var/iri -xdev -type d -size +1M`, the journal, container storage) and reclaim them the same way as disk space above. |
| **HostMemoryLow** | Under 10 % of host memory available for 10 minutes; the next spike risks the OOM killer, which sacrifices Grafana/Tempo first by design. Read dashboard `01-host` and the container memory panels on `02` to find the grower. |
| **HostCpuHigh / HostLoadHigh** | CPU above 90 % busy, or a 15-minute load above twice the vCPU count, for 15 minutes — every co-located service slows. Find the workload on `02` (per-container CPU and throttling) before touching limits; a high load with idle CPU is I/O contention (`HostIoPressure`). |
| **HostClockDrift** | The host clock is more than 0.25 s off NTP — JWT `iat`/`exp` checks and TLS handshakes start failing (spurious login errors). `chronyc tracking` and `systemctl status chronyd`. |
| **HostNetworkErrors** | A physical interface keeps counting receive/transmit errors — a failing NIC or a saturated link. `dmesg`, `ip -s link show <device>` and the provider's network status. |
| **HostMemoryPressureStalled / HostCpuPressure / HostIoPressure** | PSI pressure on the host; memory full-pressure is the earliest saturation signal before the OOM killer. Read dashboard `01-host`, then the container memory panels. If the panels are **empty**, PSI is off: `ls /proc/pressure` must exist — the role sets `psi=1` for the next boot and does not reboot (`grubby --info=DEFAULT` shows what the next boot will carry). |
| **PostgresDown / RedisDown** | The exporter answers but cannot reach its database or Redis (`pg_up == 0` / `redis_up == 0`) for 2 minutes — `TargetDown` cannot see this, because the exporter itself is up. `sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user status db-backend.service` (or `db-keycloak` / `redis`) and `journalctl CONTAINER_NAME=<svc>`. A missing or unreadable `/var/iri/redis/users.acl` keeps Redis from starting at all (ADR-0088). The nightly backup does not stop either, so these never fire for the 04:15 quiesce. |
| **ContainerUnhealthy** | A container has failed its **own** health check for 10 minutes (`podman_container_health == 1`, joined to `podman_container_info` for the name). deploy.sh restarts only backend/frontend/ingest; for every other container nothing restarts it. `sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) podman healthcheck run <name>` and `journalctl CONTAINER_NAME=<name>`. |
| **HostSecurityUpdatesFailing / HostSecurityUpdatesStale** | `dnf-automatic` failed its last run, or has not finished one for over two days (or never recorded one). `journalctl -u dnf-automatic.service`, `systemctl list-timers dnf-automatic.timer`, and that `/etc/systemd/system/dnf-automatic.service.d/20-basetool-metrics.conf` exists (role `tasks/45-updates.yml`). REQ-OPS-032. |
| **HostRebootRequired** | An installed update (kernel, glibc, systemd) has waited a day for a reboot (`needs-restarting -r`). dnf-automatic never reboots — it is an owner decision. The stack comes back on its own after a reboot; the boot unit `iri-host-updates-metrics.service` clears the flag. |
| **PostgresConnectionsCritical** | Connection pool near max. Check for a connection leak or runaway query; inspect HikariCP and `pg_stat_activity`. |
| **PostgresWraparoundCritical** | XID wraparound age > 1.5 billion (the metric value is an XID count, not seconds). Autovacuum is not freezing; the DB nears a forced read-only shutdown. Check autovacuum progress and terminate long-idle transactions urgently. |
| **PostgresFatalOrPanic** | **CRITICAL.** A PostgreSQL container logged a `PANIC` or a non-benign `FATAL` (the restart-lifecycle FATALs are excluded). Read `{app=~"postgres-.+"} \|~ "FATAL\|PANIC"` in Loki; a PANIC means the server restarted — check disk space on `/var/iri`, then `journalctl CONTAINER_NAME=db-backend` (or `db-keycloak`). |
| **PostgresDeadlocks** | Deadlocks in the last 15 minutes. The PostgreSQL log names both statements; in the backend it is usually two transactions locking the same rows in opposite order — check the multi-step transaction rules in `backend/CLAUDE.md`. |
| **PostgresLongTransaction** | A transaction has been open for over an hour and blocks autovacuum (bloat, XID age). Find it in `pg_stat_activity` (`state`, `xact_start`, `application_name`) and end it with `pg_terminate_backend(pid)` once you know what it is — a gated write on production. |
| **PostgresCacheHitLow** | The buffer-cache hit ratio of one database stayed under 90 % for 30 minutes — queries read from disk. Check recent query or plan changes and `shared_buffers` against the container's memory. |
| **PostgresTempFiles** | More than 1 GB of temp files in an hour — queries spill to disk (a bad plan or `work_mem` too small). Find the heavy queries by `temp_blks_written` in `pg_stat_statements` if it is loaded, else the PostgreSQL log. |
| **RedisRdbStale** | Redis has held **unsaved changes** for over an hour with no completed RDB snapshot — the snapshot fork is failing or blocked. AOF is the primary durability layer, so this is not a data-loss signal by itself; check disk space on `/var/iri/redis` and the Redis log. It does **not** fire on an idle store: `--save "60 1"` snapshots only when a key changed (the unguarded rule paged twice on 2026-09-08 for exactly that). |
| **RedisRdbSaveFailing** | `rdb_last_bgsave_status:err` — the background save itself failed. Usual causes: no disk space or no write permission on `/var/iri/redis` (owned by the translated uid of container uid 999), or a fork refused for memory. |
| **RedisAofWriteFailing** | `aof_last_write_status:err` while AOF is enabled — the **primary** durability layer is not reaching disk. Check disk space and permissions on `/var/iri/redis` and the Redis log. |
| **RedisMemoryHigh** | Redis used >85% of its maxmemory. Under `noeviction` the ceiling makes new logins/session writes fail. Check for session growth or a leak on the Redis dashboard. |
| **RedisEvictions** | Redis evicted keys — impossible under `noeviction`. Someone changed `maxmemory-policy`, which silently logs users out. Restore `noeviction` and find what changed the config. |
| **RedisConnectionsRejected** | `maxclients` reached — a connection leak or client storm. Check Lettuce pool usage and the Redis client list. |
| **RedisAclDenials** | Redis refused an `AUTH` or a command under its ACL for five minutes (`acl_access_denied_auth/cmd/key/channel`). With one ACL user per service (REQ-SEC-068) that is a `REDIS_<SVC>_PASSWORD` not matching the rendered `users.acl`, a template rule too narrow for what the service does, or a foreign client. Read `ACL LOG` by field only — its `object` can be a session id ([`docs/deployment.md`](../docs/deployment.md#the-redis-acl)). **Not a cause on release 1.11.0 only:** `basetool-frontend` refused `config\|get` (`reason=command`) twice per frontend start — its keyspace-notification check, refused by design; it did not fire the alert on 2026-09-25. From the release carrying #2067 the frontend sends a `PING` instead and no refusal is expected — verified gone on production after the v1.12.0 deploy, 2026-09-25. |
| **CertificateExpiringSoon** | A **served** certificate is near expiry. Public edge certificates: the `acme` unit renews them into the `edge-certs` volume and `deploy.sh`'s `reconcile_edge` restarts the edge within one tick — check `{app="acme"}` in Loki and `AcmeRenewalFailing`. Internal certificates (`keystore.p12` on the apps and Keycloak, Grafana's own leaf): rotate per [`docs/deployment.md`](../docs/deployment.md) (keystore rotation). |
| **CertificateFileExpiringSoon** | A CA-issued certificate **file** under `/var/iri/monitoring/certs` expires in under 14 days. No listener serves it, so no probe sees it; the labels name `path`, `subject` and `issuer`. Re-issue it, put the file in place, restart what reads it, then re-read with `sudo systemctl start iri-cert-expiry.service`. |
| **SelfSignedCertificateExpiring** | A **self-signed** certificate file expires in under 90 days — in practice `basetool-ca.crt`, the internal trust anchor. 90 days because replacing a root means re-issuing everything it signed and rolling the anchor through the edge's `proxy_ssl_trusted_certificate` and the blackbox `https_internal` module together. Start the rotation now. Grafana's self-signed leaf lands here too and is a one-file job. |
| **CertificateMetricsStale** | The certificate collector stopped writing (newest sample >36h), or never wrote on a host whose node_exporter has been up 36h. `systemctl status iri-cert-expiry.timer iri-cert-expiry.service`; check `/var/iri/monitoring/textfile/certificates.prom`. |
| **ContainerRestartLoop** | A container is crash-looping. `${SYSTEMCTL} status <svc>.service` and `journalctl _SYSTEMD_USER_UNIT=<svc>.service`, check the last deploy and the unit's `Memory=` / `PidsLimit=`; roll back if a bad image shipped. Reads `basetool:container:start_time_seconds` (the podman exporter's start time, one series per name across recreates). Until 2026-09-22 it read cAdvisor's `container_start_time_seconds` and could not fire on the Podman host (REQ-OBS-014). |
| **ContainerMetricsMissing / CoreContainerMetricsMissing** | Fewer containers report `basetool:container:present` than expected, so the cgroup-fed container alerts (working set, OOM, CPU throttle) are blind. Check the collector: `systemctl status iri-container-metrics.timer`, `/var/iri/monitoring/textfile/containers.prom`, and `ContainerCgroupCollectorStale` / `…FoundNothing`. |
| **ContainerStartTimeMissing** | Fewer than 10 containers report `basetool:container:start_time_seconds` (or none), so `ContainerRestartLoop` cannot fire. The source is the podman exporter, not the cgroup collector: check the `podman-exporter` container and its socket access, and `TargetDown`. |
| **ContainerCgroupCollectorStale / ContainerCgroupCollectorFoundNothing** | The cgroup collector stopped writing (or never wrote — `absent()`, since 2026-09-22), or writes a fresh file matching **no** cgroup (a changed slice layout). Every container memory/OOM/CPU/pids alert has lost its input. `sudo /var/iri/code/scripts/cgroup-container-metrics.py --dry-run` shows what it would write; it finds containers as `user.slice/user-<uid>.slice/user@<uid>.service/app.slice/<name>.service` and reads each one's `libpod-payload-<id>` child, where podman puts the limits under Quadlet's `--cgroups=split` (the unit's own cgroup reads `memory.max max`); Podman's transient healthcheck units (`<64-hex id>-<hex>.service`) are skipped. |
| **HostServiceMetricsMissing** | The cgroup collector stopped publishing `alloy` or `node-exporter`, the two host services it reads beside the containers. Either the service is down (`TargetDown` / `SystemdUnitFailed` say so) or its unit was renamed or moved out of `system.slice`, and its memory/OOM/pids alerts have lost their input. Compare `DEFAULT_HOST_SERVICES` in `scripts/cgroup-container-metrics.py` with `systemctl status alloy prometheus-node-exporter`. |
| **ContainerOomKilled** | A container was OOM-killed. Check its `Memory=` limit and anon memory on dashboard `02`; on the memory-capped host confirm the OOM hit the intended sacrifice victim, not a core service. |
| **ContainerMemoryHigh** | A container's anonymous (unreclaimable) memory is within 10 % of its `Memory=` limit for 10 minutes — the OOM early warning. Page cache is deliberately excluded; read *Why `ContainerMemoryHigh` keys off anon memory* below before raising a limit. |
| **ContainerCpuThrottledHigh** | A container is CPU-starved against its `--cpus` quota (>25% of CFS periods throttled). Read the throttled seconds, not only the ratio (sizing section below). |
| **ContainerPidsHigh** | A container is within 20% of its **own** pids cap (cap-relative, so it covers the 512-capped monitoring services and the 2048-capped JVMs). Every task counts, so an unreaped-zombie or thread leak (2026-07-12 ingest native-thread OOM; 2026-07-26 grafana at 512/512 with 493 `ssl_client` zombies) exhausts it and the kernel refuses new tasks — usually first seen as a health check failing with `can't fork`. Confirm with `cat /sys/fs/cgroup/user.slice/user-$(id -u iri).slice/user@$(id -u iri).service/app.slice/<svc>.service/libpod-payload-*/pids.{current,max,events}` (the payload child — the unit's own `pids.max` is the user manager's default, not the container's cap) and count zombies with `ps -eo stat,ppid \| awk '$1 ~ /^Z/'`. Reap them (`--init`) or cap the leaking pool. Do NOT raise the cap. |
| **SystemdUnitFailed** | A **system** unit failed (`iri-deploy`, `iri-backup`, `iri-restore-drill`, the collectors, `alloy`, …). `systemctl status <unit>` + `journalctl -u <unit>`. The application containers are **user** units and are not covered here — their state is `podman_container_state` / `podman_container_health` from the podman exporter. |
| **BankLedgerIntegrityViolation** | **CRITICAL.** Investigate the ledger before any further bank mutation. Reconcile balances and identify the divergent entry first. |
| **BankLedgerIntegritySweepStale** | **CRITICAL.** The hourly bank integrity sweep stopped running — while stale the violations gauge is frozen and `BankLedgerIntegrityViolation` cannot fire. Restart the sweep / backend and confirm it succeeds before trusting a clean reading. |
| **JobOrderItemBlueprintDrift** | The integrity sweep found ordered-item lines whose blueprint no longer produces the ordered item — an SC-Wiki re-sync re-pointed it after the order was placed, so those orders show a foreign recipe. The backend log names each order; re-saving an affected order re-derives its lines. |
| **JobOrderIntegritySweepStale** | The hourly job-order integrity sweep has not succeeded for 6 hours; while it is stale `JobOrderItemBlueprintDrift` cannot fire. Check the backend scheduler log for the `job_order_integrity` task and restore it before trusting a "no drift" reading. |
| **UserSyncStale** | The Keycloak → basetool user sync has not succeeded recently. Check the sync job logs and Keycloak reachability. |
| **ExternalSyncStale** | A UEX/SC-Wiki catalogue sync has not succeeded in >48h. Check `/admin/sync-reports`, the upstream API and the backend sync logs; correlate with `ExternalFetchErrors` and `ScWikiStepFailing`. A fire that resolves on its own within minutes is not staleness: check `changes(process_start_time_seconds[1h])` for a restart. |
| **SyncZeroItems** | A catalogue sync records successful runs but imported zero rows for 48h — an empty-200 upstream outage that `ExternalSyncStale` misses. Check the upstream API and `/admin/sync-reports`; correlate with `ExternalFetchErrors`. |
| **ExternalFetchErrors** | The named catalogue client swallowed >3 fetch/parse errors in 6h. Check the upstream API and the backend WARN lines from `UexClient` / `ScWikiClient`. Before blaming upstream: the counter resets on backend restart and `increase()` adds the pre-reset segment back (check `resets(...[6h])`), and a fixed count repeating every run is a client-side bug, not an outage (the 2026-08-03 false positive). |
| **ScWikiCensusIncompleteStreak** | The SC-Wiki page walk came back INCOMPLETE on three consecutive daily runs. Since ADR-0195 that no longer implies the orphan sweep stood down — **ScWikiOrphanSweepStandingDown** is the one that means it stopped. Known permanent floor: `/api/vehicle-items` is incomplete on every run (ADR-0195), so treat a change in *which* endpoint fails as the signal. Grep the backend log for `INCOMPLETE` / `Skipping the`. Firing together with `ExternalFetchErrors` = an ordinary multi-day upstream outage; alone = a pagination-contract drift that needs a code fix. A *surplus* over `meta.total` is never what fires this (ADR-0147). |
| **ScWikiOrphanSweepStandingDown** | `basetool_catalogue_orphan_sweep_skipped_total{reason="incomplete"}` rose at least three times in 72h: three daily syncs in a row stood the cross-kind `scwiki_deleted` sweep down, which since ADR-0195 needs the residual `/api/items` census itself to have failed, or a kind pass to have been served a row outside it. Grep the backend log for `Skipping cross-kind orphan sweep (reason=incomplete` and read the census WARNs above it. `reason="not_modified"` (a fully-cached healthy night) is deliberately not matched. |
| **ScheduledJobStale** | A scheduled job has not succeeded in >26h, or has never succeeded since a restart longer ago than that while it is enabled. Check the backend scheduler and the job's logs. |
| **ScheduledJobFailureStreak** | One scheduled job (`task` label) failed at least three times in an hour. Read its ERROR lines in the backend log; `ScheduledJobStale` follows if it never recovers. |
| **BusinessMetricsStale** | The 60s business-metrics sampler wedged — the queue-depth gauges are frozen and the `*ApprovalOverdue` alerts read stale values. Check the backend log for the `BusinessMetricsCollector.refresh()` failure. |
| **ExchangeRegistryChanged** | An admin changed the third-party exchange registry or its global switch (`action` label). Expected to be rare and deliberate — confirm it in the „Verbundene Anwendungen“ audit tab (REQ-XCH-003). |
| **ExchangeMirrorWriteFailed** | The backend could not write the registry mirror `exchange:registry` to Redis. `phase=pre_commit` means an admin's restriction was refused; any other phase means the mirror lags until the reconcile succeeds. Check Redis and the `basetool-backend` ACL user's `~exchange:*` grant. |
| **ExchangeRegistryReconcileStale** | The 60s mirror reconcile has not succeeded for 10 minutes, so a lagging mirror is not repaired. Only armed while `APP_EXCHANGE_MIRROR_ENABLED=true` (the job's enabled gauge). |
| **ExchangeRegistryMirrorStaleAtGateway** | The ingest gateway's last successful read of `exchange:registry` is more than 5 minutes old (or it has not read it once since it started) for 10 minutes, while the backend's mirror is enabled. The gateway reads it every 30 s on its own, so this fires without any exchange traffic; `basetool_exchange_registry_mirror_age_seconds` on the Exchange dashboard shows the age. Same checks as the next row (REQ-XCH-003, REQ-XCH-028). |
| **ExchangeRegistryUnreadableAtGateway** | The ingest gateway cannot read `exchange:registry` or the revocation keys and answers every exchange request `503 REGISTRY_UNAVAILABLE` (fail-closed). Check the backend's mirror writes, Redis, and the ingest ACL user's `~exchange:*` read grant (REQ-XCH-003). |
| **ExchangeBudgetHigh** | The gateway's exchange data in Redis (the idempotency cache) uses more than 80 % of its 64 MiB budget; at 100 % exchange writes are refused `503 EXCHANGE_BUDGET_EXHAUSTED` while sessions keep their room. Look for a client that writes in a loop (`ingest:xch:budget:c:*`) (REQ-XCH-023). |
| **ExchangeClientBudgetHigh** | One client's exchange data in Redis uses more than 80 % of that client's 16 MiB budget (`client_id` label). When it is full every write of every member of that client is refused `503 EXCHANGE_BUDGET_EXHAUSTED` — while one client exists, long before **ExchangeBudgetHigh** can fire, since a client's budget is a quarter of the total. It frees only as cached answers expire, up to 24 hours. Check on the Exchange dashboard whether the client pushes its whole list every sync or keeps re-sending conflicting change sets, and contact its author (REQ-XCH-023). |
| **ExchangeRelayFailing** | Admitted exchange requests keep ending in `502 BACKEND_RELAY_FAILED`: the backend answered 5xx, refused with a code outside the exchange error registry, sent an answer that breaks the v1 schema, or could not be reached at all (circuit open, no gateway token). Check the backend and its exchange layer and the gateway's `Exchange relay to` / `Exchange answer breaks` log lines (REQ-XCH-011). |
| **ExchangeLargeChangeSetsBusy** | More than 30 change sets of over 100 ops were refused `503 RELAY_BUSY` in 15 minutes, for 15 minutes: the four slots of the gateway's `exchangeLargeChangeSets` bulkhead stay taken. Usual after a client release or on a launch day, when many members send a first sync at once; clients retry after 10 s and nothing is lost. Check the backend's CPU and the p99 of `POST /exchange/v1/me/stock/changes` (Exchange dashboard, *Relay capacity*); raise `resilience4j.bulkhead.instances.exchangeLargeChangeSets.max-concurrent-calls` only with headroom there (REQ-XCH-023). |
| **ExchangeClientRefusalsSpike** | One registered client (`client_id`) was refused more than 30 times in 15 minutes for a reason other than its own limits (`rate_limited`, `quota_exceeded`, `idempotency_in_progress`, `relay_busy`) — typically a release below the minimum version, an ungranted capability, revoked keys or broken DPoP proofs. Check the per-client panel on the operations dashboard, then contact the client's author or suspend it under *Administration → Verbundene Anwendungen* (REQ-XCH-028). |
| **ExchangeDpopProofsFailing** | Exchange clients keep presenting DPoP proofs the gateway refuses — invalid, replayed or for another key (REQ-XCH-006). A misimplemented client or a replay attempt; nonce challenges do not count. |
| **ExchangeDpopProofLimitSustained** | For 15 minutes the gateway refused more than 3 exchange proofs a minute with `429 DPOP_PROOF_LIMIT` — a member's clients keep 600 live proofs (about 20 requests a second); a single burst cannot fire it. The refusal carries no client, so find it by `sum by (client_id) (rate(basetool_ingest_exchange_refused_total{reason="rate_limited"}[10m]))` and the Exchange dashboard's per-client panels; contact the author, and suspend the client under *Administration → Verbundene Anwendungen* if it does not stop (REQ-XCH-028). A suspected stolen token: suspend first, then ask the member to disconnect the installation. Raise `app.exchange.limits.dpop-proofs-per-member` only for a client with a registry `requestsPerMinute` far above the default (REQ-XCH-006). |
| **IngestDpopReplayCacheFull** | The DPoP `jti` replay cache of one path scope (`exchange` / `other`) holds its total cap (`app.exchange.limits.dpop-proofs-total`, 100 000) and refuses every new proof there — on `exchange` with `503 SERVICE_UNAVAILABLE` and `Retry-After` (which `IdentityProviderUnavailable` leaves out); on `other` with `401`. Each member is capped on its own (600 live proofs), so this takes many members at their cap — check `basetool_ingest_dpop_replay_refused_total{reason="member_cap"}` and the per-IP limiter before raising the cap (REQ-XCH-006). |
| **ExchangeRelayHeaderForged** | `X-Exchange-Client` / `X-Exchange-Capabilities` keep arriving from something other than the gateway acting for a member on an exchange path — somebody is trying to borrow an external client's attribution or capabilities (REQ-XCH-010). The correlationId in the backend log names the request. |
| **ExchangeGateRefusing** | The backend's `ExchangeGate` has refused exchange requests the gateway let through for 15 minutes (`reason` label). Gateway and backend disagree about the registry or a client revocation — check `ExchangeMirrorWriteFailed` and the gateway's registry cache; `revocations_unreadable` means the backend could not read the Redis revocation mirror and answered `502`. |
| **ExchangeDepartureIncomplete** | A departed member's exchange access could not be fully ended — a revocation, a consent removal or the Keycloak logout failed, and the step is not retried. End the member's sessions and consents in Keycloak by hand (REQ-XCH-008). |
| **ExchangeBulkUndoFailed** | An admin's bulk undo under *Administration → Verbundene Anwendungen* ended `FAILED`: the members it processed are undone, the others still carry the client's changes. Open the run's details on the admin page for the members marked as failed, check the backend log for the run id, and start the undo again once the cause is fixed — it only touches what is not undone yet (REQ-XCH-034, ADR-0227). |
| **ExchangeRemoveSpike** | One registered client (`client_id`) removed more than 100 blueprints, stock lots or ships across its members in an hour — the per-member guard does not catch a faulty release spread over many members. Suspend the client, then let the members undo (vault runbook *Suspending an exchange client*, REQ-XCH-028). |
| **ExchangeGuardStorm** | The mass-change guard held back more than 10 change sets of one client in an hour; members get many confirmation links. Check the client's writes and consider suspending it (REQ-XCH-021). |
| **ExchangeInstallationSurge** | More than 20 new installations of one client in an hour — a release day, or a device-code phishing wave / leaked client. Check the notifications and the admin page (REQ-XCH-007). |
| **ExchangeUnknownClient** | The gateway keeps refusing valid tokens of a Keycloak client the registry does not list (`client_id="unregistered"`): register it deliberately or remove it from Keycloak (REQ-XCH-003). |
| **UserSyncZeroItems** | User sync succeeds but processes zero users (Keycloak returned an empty roster) — local accounts will drift. Check Keycloak reachability and the sync path. |
| **KeycloakSyncFetchFailing** | The Keycloak Admin-API roster fetch threw in the last 6 hours and was swallowed into an empty roster: the user sync reports success with zero users while local roles drift — departed members keep access. Check Keycloak reachability from the backend and the sync log; pairs with `UserSyncZeroItems`. |
| **UserSyncPerUserFailure** | The user sync skipped accounts that threw during reconciliation; they keep their last local roles until a run succeeds. The backend log names the account id and the cause; a repeating id is permanent — a duplicate Discord identity is the known cause (`UserDiscordLinkCollision`). |
| **UserDiscordLinkCollision** | One Discord identity is reachable from two accounts, so the link could not be written to the second — one member has two accounts. Consolidate them in the member administration (the duplicate folds into the survivor). Repeats every sync until then; the log names both account ids. |
| **UserCallsignCollision** | A login with an unknown subject met an existing account holding the same callsign, so a new pending registration was created. Resolve it in the registration queue (the row carries the duplicate-callsign marker) by an explicit merge — never by approving a second account for one member. |
| **IngestHandoffErrors** | The ingest gateway is failing requests (`reason` label): `staging_unavailable` — Redis refused a staged draft or change set (see `IngestStagingUnavailable`); `internal` — an unexpected `500`, whose stack trace is in the gateway log. Relay failures to the backend are `ExchangeRelayFailing`'s. |
| **IngestStagingUnavailable** | The gateway could not park a client's draft or held-back change set in Redis. Check the `redis` unit and the gateway's Redis configuration — the backend itself is fine. |
| **IngestAudienceGateOff** | The gateway's JWT audience check is off (`basetool_ingest_gate_enforcing{gate="audience"}` = 0), so any valid realm token passes its decoder. Set `IRI_INGEST_EXPECTED_AUDIENCES=basetool-ingest` (**never** the backend's audience; the `exchange.*` client scopes stamp it) per `docs/INGEST_KEYCLOAK_SETUP.md` step 7a, re-render `env.d`, restart `ingest`. The startup log's `Audience gate` line shows the posture. |
| **AndroidVersionPolicyOverrideActive** | For a day, an `APP_ANDROID_*_OVERRIDE` in the host `.env` has replaced the Android version policy's release default (`basetool_android_version_policy_override{field}` = 1), so that value no longer deploys or rolls back with the release (REQ-API-020). Fold it into the release default (`app.android.version-policy.release.*` in the backend's `application.yml`) in a PR, release it, then empty the override, re-render `env.d` and restart `backend` (`docs/deployment.md` → *The Android app floor*; a production write). The backend's startup log names the source of each value. |
| **IngestServiceAccountTokenFailing** | The gateway's own client-credentials grant against Keycloak has failed for 10 minutes: every exchange relay fails, and the client cannot tell the member why. Check `IRI_INGEST_SERVICE_ACCOUNT_CLIENT_ID` / `_SECRET` / `_TOKEN_URI` in the rendered `env.d/ingest.env` (without printing the secret) and Keycloak reachability from the gateway. |
| **IngestAuthFailureSpike** | The gateway rejects presented tokens (`reason="invalid_token"`: expired, wrong issuer or audience, bad signature) at an elevated rate on one `path_scope` — `exchange` is the exchange routes, `other` every other path. Normally zero; possible token guessing, or a client sending stale tokens. Split with `sum by (reason, path_scope) (rate(basetool_ingest_auth_failures_total[5m]))`. |
| **IngestUnauthenticatedFlood** | More than 60 credential-less requests a minute on one `path_scope` for 15 minutes — scanning or a looping client, not credential guessing. Check `basetool_bot_blocked_total` and the per-IP rate limiter before widening anything. |
| **IngestPayloadRejectedSpike** | The gateway refuses oversized `/exchange` bodies (`413`) at an elevated rate — oversized-body probing against the public surface, or a misconfigured client. Read the source addresses in `{app="edge"}` for `ingest.profit-base.online` and check the fail2ban jails (`{app="host-fail2ban"}`). |
| **OnBehalfOfRefusedSpike** | The backend keeps refusing an `X-Ingest-On-Behalf-Of` header from a caller that is not on the gateway allowlist (`reason="not_a_gateway"`). Either `IRI_INGEST_GATEWAY_CLIENT_IDS` does not match the gateway's real client id — then uploads fail too — or somebody is probing for impersonation. Check the gateway's own token first, then the access log for the source. |
| **ActingMemberNotLiveRefused** | The gateway keeps naming a member the backend will not act for — unknown locally, or gone from the last Keycloak roster. Usually an offboarded member's connected application still running: find them by the correlationId in the backend log. If the refused subjects vary, somebody is enumerating user ids; the source is in the access log. |
| **OnBehalfOfWithoutAuthenticatedCaller** | For 30 minutes `ActingMemberFilter` refused on-behalf-of requests that reached it with no authenticated caller. If uploads fail too, suspect the security filter order (`SecurityConfig` against ADR-0129) or silent authentication failures; if they work, an unauthenticated caller is sending the header — check the access log. |
| **ApiUnknownClient** | A token whose `azp` names no known client drove `/api/**` for 10 minutes. Either a first-party client was added to the realm without `app.monitoring.api-clients.known-client-ids`, or something unapproved uses the API — identify it from Keycloak's client sessions and `{app="edge"}` for the api vhost. The label stays `other` by design. |
| **OptimisticLockConflictSpike** | A likely locking regression. Check recent deploys; look for a coarse lock or a missed `data-version` propagation on the affected surface. |
| **IdentityProviderUnavailable** | The app is re-mapping unreachable-Keycloak-JWKS failures to 503. Check Keycloak reachability / DNS before 5xx surfaces to users. The ingest gateway's 503 for a full DPoP replay store is subtracted (its auth-failure reason `dpop_store_full`); `IngestDpopReplayCacheFull` covers that. |
| **Http5xxRateHigh / HttpLatencyP95High / LogbackErrorSpike** | One application returns more than 1 % 5xx, answers its p95 slower than 2 s, or logs ERRORs above 0.2/s, for 10 minutes. Check the last deploy first, then the application's ERROR lines in Loki (the `level` label) and dashboard `03`; a matching `CircuitBreakerOpen` or `HikariPoolPending` names the dependency. |
| **JvmHeapHigh / JvmGcOverheadHigh** | The heap has been above 90 % of its max, or GC pauses have taken more than 20 % of wall time, for 10 minutes — the collector is thrashing and latency degrades. Read the heap and allocation panels on `03`; a steady climb after a deploy is a leak, not a sizing problem. |
| **JvmThreadsHigh** | More than 1638 live threads — 80 % of the JVM containers' `PidsLimit=2048`. At the cap the JVM cannot create native threads (`JvmNativeThreadExhaustion`). A steady climb is a thread leak: take a thread dump (`jcmd <pid> Thread.print` through `podman exec` — a gated write on production) or compare the thread-state panels on `03`. |
| **JvmNativeThreadExhaustion** | **CRITICAL.** A JVM logged `pthread_create failed` / `unable to create native thread`: its container hit the pids cap and the app is failing. Restart the service to recover, then find the leak — `ContainerPidsHigh` and `JvmThreadsHigh` are the leading signals. |
| **JvmFileDescriptorsHigh** | More than 85 % of the file-descriptor limit for 10 minutes — an FD leak (unclosed sockets or files). Investigate before the JVM starts failing to open sockets. |
| **HikariPoolPending / HikariConnectionTimeouts** | Threads wait for a database connection, or a request waited ~30 s for one and failed. Look for a slow query holding connections (`pg_stat_activity`) before enlarging the pool; `PostgresLongTransaction` is often the cause. |
| **CircuitBreakerOpen** | A Resilience4j circuit breaker (`application` / `name` labels — the frontend's backend client, or in `basetool-ingest` `exchange` for the exchange relay) is open: its downstream, usually the backend, is failing. Check the backend first; the breaker closes on its own once calls succeed. |
| **BulkheadNearSaturation** | A bulkhead (`name` label) has had fewer than 5 free slots for 10 minutes; in the frontend, page renders are about to queue behind the parallel backend fan-out. Check backend latency and concurrent load. The gateway's `exchangeLargeChangeSets` bulkhead is left out: it has four slots by design and alerts as **ExchangeLargeChangeSetsBusy**. |
| **RetryRateElevated / BackendCallFailureSustained** | Frontend→backend calls succeed only after a retry (above 0.2/s), or keep failing (above 0.5/s), for 10 minutes while the breaker may still be closed. Check backend health and the frontend→backend network path. |
| **FrontendBackendFanoutHigh** | The frontend made more than 10 backend calls per inbound request for 15 minutes — a page or fragment lost its read gating. Check recent frontend controller changes for an ungated backend fan-out. |
| **HttpUriTagCapNear** | An application reports 900 distinct `uri` values on `http.server.requests` or `http.client.requests` (`meter` label), 90 % of its `max-uri-tags` cap of 1000. Past the cap Micrometer drops every new route without a metric, so it silently leaves the 5xx and latency alerts. Raise `management.metrics.web.*.max-uri-tags` in the module's `application.yml` together with `UriTagCapacityTest`, or find the raw path leaking into the tag. |
| **CacheSizeEvictionsHigh / CacheHitRatioLow** | A cache (`cache` label) evicts live entries at its size cap, or serves under 50 % hits at real traffic. Raise the cap or find the key explosion (an unbounded pageable-keyed cache); otherwise the database is re-hit. |
| **HealthContributorHanging** | A Spring Boot health contributor took more than 10 s; readiness and the container health check (5 s timeout) fail while the app looks alive. The log line names the contributor — usually Redis or the database behind it. |
| **FrontendKeycloakBackchannelFailing** | The frontend logs a sustained rate of `PrematureCloseException` against Keycloak's token endpoint — refresh grants are lost and logins may fail. Check the frontend→Keycloak path (the internal alias, the edge) and the re-auth rate on `07`. |
| **AccessDeniedSpike** | 403 access-denied at an elevated rate — possible authorization probing or a permission gate broken by a deploy. |
| **SecurityExpressionEvaluationFailed** | Any `basetool_security_expression_failures_total` increase in 10 minutes: a `@PreAuthorize` expression could not be evaluated, so that operation answers `400 ILLEGAL_ARGUMENT` to every caller. `kind="evaluation"` is a bean or method the context lacks (a renamed security bean), `kind="other"` an expression without a decision. A deploy defect — the WARN line of the 400 names the expression (REQ-OBS-020). |
| **BackendAuthFailureSpike** | The backend rejects presented tokens (`reason="invalid_token"`) at more than 0.05/s for 10 minutes — possible token guessing. Split with `sum by (reason) (rate(basetool_auth_failures_total{application="basetool-backend"}[5m]))`; `no_credentials` is the ordinary background and counts elsewhere. |
| **BackendUnauthenticatedFlood** | More than 60 credential-less requests a minute on the backend for 15 minutes — scanning or a looping client. Check whether `basetool_ratelimit_rejections_total` is already absorbing it. |
| **RateLimitRejectionSpike** | One application rejects more than one request a second through its rate limiter for 10 minutes. Find the source (`{app="edge"}`) and decide whether it is abuse or a limit too tight for a real client; `RateLimitRejectionRatioHigh` gives the ratio. |
| **RequestBodyRejectedSpike** | The backend refuses oversized JSON bodies (`413`) on a capped import path at an elevated rate — memory-DoS probing of the refinery import, or a client bypassing the frontend's proxy cap. Read the source in `{app="edge"}`. |
| **PendingApprovalBlockSpike** | Pending-approval 403s at more than 0.2/s for 10 minutes — more than the occasional pending account; likely an authorities-converter or approval-sync regression leaving approved members with only `ROLE_PENDING_APPROVAL`. Check the last deploy and the user sync. |
| **NoRoleBlockSpike** | More than three distinct accounts refused with `403 NO_ROLE` in 15 minutes (REQ-SEC-053). One or two is an admin who has not assigned a role yet; more is a realm-side role rename, a hand edit or tokens from the wrong realm — check the Keycloak role index line in the sync summary. |
| **TermsConsentRolloutStalled** | At least three members have been refused by the consent gate for an hour and nobody accepted in two hours — the consent path is broken and they are locked out of the tool and the extractor. Check `/admin/terms` and the frontend log for failures on `POST /terms/accept`. |
| **AuditSilenceAnomaly** | An audited area has gone unexpectedly quiet. Verify the audit pipeline is recording; a silent area may mean logging broke, not that activity stopped. Carries `keep_firing_for: 15m` so the nightly 04:15 backup quiesce does not produce a RESOLVED + FIRING pair every night. |
| **AuditDomainSilenceAnomaly** | One audit domain recorded nothing for 30d while others stay active — it may have lost its audit wiring (REQ-AUDIT-001). Check the domain's `auditRecorder.record` calls. `ROLE`/`PROMOTION`/`PERSONAL_INVENTORY`/`MARKET`/`HANGAR`/`BLUEPRINT`/`CONNECTED_APPS` are excluded; review their volume on the `07` per-domain tables. Same `keep_firing_for: 15m` hold. |
| **BankAuditSilenceAnomaly** | The bank audit trail recorded nothing for 60d while the backend is up. Verify bank mutations still write `bank_audit_event` rows. Same hold. |
| **RegistrationApprovalOverdue / BankBookingApprovalOverdue** | An approval queue is aging past SLA. Notify the approvers; clear the backlog. |
| **DeletionRequestOverdue** | A member's Art. 17 erasure request has been pending more than 14 days. Art. 12(3) gives one month, so this is a warning with headroom: decide it in Administration -> Löschanträge. A refusal needs a written reason (REQ-SEC-061). |
| **UserDeletionUnfinished** | An account has been gone from Keycloak for more than 7 days while its local row is still there. Finish it in Administration -> Mitglieder (REQ-SEC-059). Service-account rows (`service-account-%`) are excluded. |
| **AccountErasureKeycloakDeleteFailed / AdminAccountAutoActivated** | An erasure deleted the local data and not the Keycloak account, or a new account arrived already holding `Admin` and skipped the approval queue. The remedy is manual; the paired audit row carries the account id the Keycloak console needs. |
| **P4kImportStuck / P4kImportFailed** | A P4K import has been PENDING > 6h (the worker is wedged), or reached FAILED in the last 24h. Check the backend log and the job list. |
| **JobOrderStale / RefineryOrderStale / OperationStale** | A work-queue item has been open past its baseline (job order > 180d, refinery order / operation > 90d). Close, reassign or cancel it. |
| **MailDeliveryFailing** | Outgoing SMTP mail is failing (>2/h). Check the relay and the backend log. |
| **MailDroppedConfigDrift** | Mail is enabled but going nowhere (`dropped_no_host` / `dropped_no_sender`). The deliberate `APP_MAIL_ENABLED=false` kill switch is excluded, so this stays silent until mail is switched on. |
| **SsePushChannelDead** | Zero live SSE connections while the frontend serves real page traffic — the notification push channel is down. Check the edge for response buffering on the stream endpoints and the relay. |
| **LiveSyncRelayDropsSustained** | The live-sync relay dropped >3 frames/h on one `topic_class`/`reason`, sustained 15m. `throttled`/`topic_throttled` = a client or room over its bucket; `send_failed` = sockets breaking mid-write (edge WebSocket timeouts); `topic_cap` = a client subscribing past its cap; `authorize_saturated` = subscribes failing **open** — security-relevant. |
| **AppLiveSyncFramesDropped** | The backend dropped >3 app live-sync frames/h for one `event` because a stream's bounded delivery queue (64 frames) was full, sustained 15m — app subscribers not reading their SSE stream. Check `basetool_livesync_frames_queued`, `basetool_livesync_send_failures_total` and the edge's buffering / read timeout on `/api/v1/live-sync/stream` (BE-PERF-13). |
| **LiveSyncRedisFanoutBroken** | The live-sync or notification fan-out has failed to publish to Redis for 10 minutes; cross-instance delivery silently degrades to single-instance. Check Redis reachability and the services' ACL channel grants for `basetool:livesync:changed` / `basetool:notify:published` ([`docs/deployment.md` → *The Redis ACL*](../docs/deployment.md#the-redis-acl)); `RedisAclDenials` fires beside it when the grant is the cause. |
| **AppLiveSyncBridgeSilent** | For 30 minutes the frontend published live-sync frames and the backend consumed none while app streams were open — browser edits do not reach the app and app writes do not reach browsers, with no error anywhere. Check `app.live-sync.redis-fanout.enabled` on the backend (`APP_LIVE_SYNC_REDIS_FANOUT_ENABLED`, `true` in prod), that it listens on `basetool:livesync:changed`, and the backend's ACL channel grant. |
| **LiveSyncSectionKeySkew** | A section key is broadcast that the relay's accept-list does not know (REQ-FE-010). Check what the last frontend deploy changed; raise `LiveSyncWebSocketHandler` to DEBUG to see the rejected key. |
| **FrontendLoginBroken** | Repeated OAuth2 `provider_error`s with zero successes — login is likely broken at the token-exchange step. Check frontend logs and Keycloak reachability from the frontend. `invalid_state` / `other` dominating on dashboard `07` is bot noise. |
| **SseRelayPoolNearSaturation** | The frontend holds more than 800 of its 1000 notification SSE relay slots for 10 minutes; past 1000 the next viewer's push is dropped without an error. Check the concurrent viewers and streams per user before raising the `frontend-sse-pool` size. |
| **SessionEvictionSpike** | More than 3 concurrent-session evictions per hour, sustained — a session registry holding dead Redis sessions against the cap. Compare the registry against the live keys and correlate with `ActiveSessionsRunaway`. |
| **SessionValueDropsSustained** | Unreadable session values dropped at >20/15m for 30m. The frontend WARN from `SessionAttributeDiagnosticMapper` names the attribute and the type id. A decaying burst after a deploy is a backlog repairing itself; a held rate means something is still *writing* an unreadable value (REQ-SEC-049, REQ-SEC-050). Do **not** raise the threshold. |
| **SessionUnmappableSustained** | More than 20 session hashes per 15 minutes lack a required field, for 30 minutes; those members are signed out. The frontend WARN from `SessionAttributeDiagnosticMapper` names the field. A burst after a Redis restart, an AOF truncation or a session purge decays on its own; a climbing rate means hashes are lost in volume, and the whole active population at once means the session wire format broke (REQ-SEC-063). |
| **SessionTypeOutsideAllowList** | A session value names a class outside `SessionTypeAllowList` (the frontend WARN names it once per lifetime). `mode=report`: nothing broke, but enforcing would drop it — add a legitimate class to the list and keep reporting until this stays silent. `mode=enforce`: the attribute was refused, correct for a foreign class and a defect for a legitimate one (REQ-SEC-067). |
| **RedisFanoutUnsubscribed** | A backend cross-instance pub/sub container has not been listening for 10 minutes; the backend stays healthy while cross-instance delivery is dead. Check the `redis` unit and `net-redis-backend`. |
| **CsrfRejectionSpike** | CSRF rejections >0.1/s for 15m — likely a CSRF-wiring regression. Check recent security/template changes. |
| **ClientErrorSpike** | The browser error beacon reports one `kind` at >20/h and >3x its 24h average. Check the last frontend deploy; raise `ClientErrorReportController` to DEBUG for the message and script URL (for `csp_violation`: the violated directive and the blocked origin). |
| **DiscordPrecheckUnauthorizedSpike / DiscordPrecheckDisabledOnProd** | The Discord precheck is answering 401 at an elevated rate (secret guessing), or 503 with a blank secret (config drift — the SPI then fails open). |
| **RateLimitRejectionRatioHigh** | More than 1% of rate-limit evaluations on one module/bucket are rejected. Check the source and whether the limit needs tuning. |
| **KeycloakLoginErrorSpike / KeycloakErrorRateHigh / KeycloakEventMetricsAbsent** | Login errors jumped, Keycloak is logging ERRORs (check the fail-open Discord SPI path first), or the event counter is missing while Keycloak serves token traffic (check `KC_METRICS_ENABLED=true` in `.env` — the generated template defaults it to off). |
| **JvmStartupCacheRejected** | A backend, frontend or ingest JVM refused the AOT cache baked into its image and started without it (`{app=~"(backend\|frontend\|ingest)-stdout"} \|~ "Unable to use AOT cache"`). The service is up, only slower to start. The usual cause is a `-XX:UseCompactObjectHeaders` in `JAVA_TOOL_OPTIONS` / `IRI_EXTRA_JAVA_OPTS` that differs from the image's (ADR-0180 rollback lever); expected while that lever is pulled on purpose, otherwise align the flag or rebuild the image (REQ-OPS-030, ADR-0209). |
| **SshPasswordLoginOnKeyOnlyHost** | **CRITICAL — possible compromise.** Treat as an intrusion: review auth logs, lock the account, rotate keys. |
| **SshFailedAuthSpike** | Failed SSH auth surge. Check source IPs; confirm fail2ban and firewalld are active. |
| **SshRootLoginAccepted** | A successful **root** SSH login. Expected only for a deliberate operator session — confirm it was you; if not, treat it as compromise (review auth logs, rotate keys). |
| **SudoAuthFailure** | A `sudo` failure on the host — investigate the source and session. |
| **AuditdSshTamper** | An auditd watch hit inside `/etc/ssh/sshd_config(.d)` or `/root/.ssh`. Confirm the acting `auid`/`comm` was you. The watches are directory-wide, so a `known_hosts` append by an outbound `ssh` lands here too — attribute it (`ausearch -k sshd-authkeys`) before treating it as tampering. |
| **EdgeServerErrorSpike / EdgeRateLimitSpike** | 5xx or 429 spike at the edge. Read `{app="edge"}` in Loki: upstream health and recent deploys for 5xx, source addresses for 429. If every line carries the same bridge address, the edge has lost the real client address (the PROXY-protocol trust list, ADR-0187) and the rate limiter sees one client. |
| **EdgeActuatorDenyBroken** | **CRITICAL.** A public app host no longer answers 404 on `/actuator/*`. The deny is `location /actuator { return 404; }` in `docker/edge/conf.d/*.conf.template` and `include/api-allowlist.conf`; check what the last release changed there and whether the running edge carries it. |
| **EdgeForceSslRedirectBroken** | Port 80 of a public vhost stopped redirecting to HTTPS. Check the port-80 server blocks in the edge templates and the front end. |
| **EdgeHstsHeaderMissing** | The first response lost its `Strict-Transport-Security` header. Check the app security config and the edge for a header-stripping change. |
| **EdgeMembersOnlyRedirectBroken** | **CRITICAL.** `/missions`, `/operations` or `/orders` stopped redirecting an anonymous browser navigation to the login — member data may be public. Check the edge cache/`location` rules and the frontend's security chain. |
| **EdgePublicSurfaceNot200 / EdgeAppLinkFallbackBroken** | A page that must stay public no longer answers 200, or `/app/callback` no longer answers 303 to `/app/link-help` (REQ-SEC-038). |
| **AcmeRenewalFailing** | The `acme` unit logged more than one failed renewal in 26h. Read `{app="acme"}`; the edge keeps serving the old certificate until it expires, and `CertificateExpiringSoon` is the backstop. |
| **DeployRolledBack / DeployFailed** | A promoted release did not ship. Read why in Loki: `{app="ops-deploy"} \|~ "rolling back\|SECURITY: cosign\|health check failed\|FATAL"`, then widen to `{app="ops-deploy"}`. **Not** `journalctl -u iri-deploy.service`: the unit appends its output to `/var/log/iri-deploy.log`. Determine the cause before re-promoting. |
| **DeployHealthRestartFailing** | The deployed release could not be restored: a container runs the target image but is unhealthy and the targeted restart will not stick, or (since 2026-09-25) a re-apply of the deployed release — a drift re-apply after a missing container or a wrong image, or (since 2026-09-26) an operator's `deploy.sh --reapply` — did not bring it up; it is retried after 300 s, doubling to 1 h. A runtime fault, deliberately not a rollback: the rollback anchors still name the release before it. `{app="ops-deploy"} \|~ "health drift\|re-appl\|did not come up"` names the service(s). |
| **DeployHeartbeatStale** | No tick has found the stack at target and healthy for over an hour (or the heartbeat was never written): the deployer stopped, is stuck on its lock, aborts before writing, or is deliberately skipping. `systemctl list-timers iri-deploy.timer`, `systemctl status iri-deploy.service`, then `{app="ops-deploy"}` for the last tick. A gated stateful-infra change and a post-rollback backoff also stop it — `DeployConfigBlocked` / `DeployRolledBack` name those. |
| **DeployConfigBlocked** | A deploy was blocked on a stateful-infra guard. `{app="ops-deploy"} \|~ "CARVE-OUT"` names the image pin that tripped it. Run the documented stateful-infra upgrade ([`docs/deployment.md`](../docs/deployment.md)), then re-run `deploy.sh --force`. |
| **GhcrPullTokenExpired / GhcrPullTokenExpiring** | The GHCR pull token's recorded expiry has passed or is <14d away; a lapsed token stops every deploy at the registry login. Rotate it and update the `.expiry` sidecar ([`docs/deployment.md`](../docs/deployment.md)). The account name in `{app="ops-deploy"} \|~ "logging in to"` is masked (REQ-OBS-004). |
| **BackupStaleOrMissing** | No successful backup for 26h, or the metric is absent. `{app="ops-backup"} \|~ "FATAL\|WARN"` carries the abort reason; an empty stream means the unit never fired (`systemctl list-timers iri-backup.timer`). See [`docs/backup.md`](../docs/backup.md). If the log shows `403 Forbidden` from rclone, the store's edge has blocked the host — see *The store's edge blocks the host* there. |
| **BackupRetentionNotApplied** | The repository holds more snapshots than `IRI_KEEP_DAILY + IRI_KEEP_WEEKLY + IRI_KEEP_MONTHLY`, so `restic forget` is not removing what it should and every run reads more from the store. `{app="ops-backup"} \|~ "retention\|snapshots kept"`; `backup.sh --dry-run` lists the snapshots and their paths. |
| **RestoreDrillStaleOrMissing / RestoreDrillArtifactNotRestorable** | No successful drill for 8 days (one weekly drill missed; 35 days until 2026-09-22), or one artifact did not restore. `{app="ops-restore-drill"} \|~ "FATAL\|WARN"`, filtered on the artifact from the alert label. A non-restorable artifact means backups are unverified — fix before relying on them. See the restore-drill section below. |
| **ContainerCleanupStaleOrMissing** | The weekly cleanup job is overdue. `{app="ops-cleanup"}` over 10d shows the last run; an empty stream means the timer never fired. |
| **PrometheusTsdbApproachingCap** | Prometheus TSDB nearing the 40GB / 180d cap. Verify retention and disk; investigate label cardinality if growth is abnormal. |
| **AlertmanagerNotificationsFailing** | Alertmanager cannot deliver. Check its log (`{app="mon-alertmanager"}`) and the receiver config; this alert itself routes to Discord because e-mail may be what is broken. |
| **PrometheusConfigReloadFailed / AlertmanagerConfigReloadFailed / AlloyConfigReloadFailed / BlackboxConfigReloadFailed** | A config load failed and the component runs the last-good config. Run the matching lint (Local validation below) and re-deploy. **`alloy validate` exits 0 even when it fails** (1.19.2, measured 2026-09-20) — read its output, not `$?`. |
| **PrometheusConfigStale** | The on-disk Prometheus config is newer than the last successful load, and `deploy.sh`'s self-heal is not converging. Check that `iri-deploy.timer` runs and `IRI_MONITORING_ENABLED=true`, then `${SYSTEMCTL} restart prometheus.service`. |
| **MonitoringReconcileDisabled** | The monitoring units run but `IRI_MONITORING_ENABLED` is not `true`, so config changes never reach them and `PrometheusConfigStale` cannot catch it. Set `IRI_MONITORING_ENABLED=true` in `/var/iri/code/.env` — `deploy.sh` reads that one key from it — or in an `Environment=` drop-in on `iri-deploy.service`, which wins. To retire monitoring on a host instead, stop and disable the nine units so the gauge stops being written. |
| **PrometheusRuleEvaluationFailures / LokiRuleEvaluationFailures** | A rule fails to evaluate, so its alerts cannot fire. Check the named group for a bad expression. |
| **PrometheusNotificationsDropped / LokiRulerNotificationsFailing** | Firing alerts may not reach Alertmanager. Check the Prometheus/Loki-ruler → Alertmanager link. |
| **PrometheusTsdbProblems** | WAL corruption or a failed compaction. Check disk health/space on `/var/iri` and the Prometheus log. |
| **NodeTextfileScrapeError** | node_exporter could not parse a `.prom` file in `/var/iri/monitoring/textfile` — an ops-automation, container or certificate metric may be stale. |
| **AlloyComponentUnhealthy** | An Alloy component is unhealthy. `journalctl -u alloy.service --since -1h`; the component graph is on Alloy's UI at `:12345` (host-local only). |
| **TempoReceiverSilent / AlloyOtlpReceiverSilent** | The apps report tracing on (`basetool_tracing_enabled=1`) and spans are not arriving. **Both** firing: the spans never reached the collector — on this host the usual cause is an app unit missing `AddHost=alloy:host-gateway` (the drop happens inside the app's OTLP exporter, which logs nothing). **Only `TempoReceiverSilent`**: Alloy accepts spans and cannot forward them — check `IRI_ALLOY_TEMPO_ENDPOINT` in `/etc/sysconfig/alloy` and Tempo's loopback publish. |
| **TempoSpansRefused / TempoWritePathFailing** | Tempo refused spans at its receiver, or its live store failed to complete or flush blocks — traces are lost. Right after a Tempo restart with no `ContainerOomKilled` / `HostDisk*` it is the ungraceful-shutdown WAL transient and heals itself; otherwise read `{app="mon-tempo"}`, the container's memory on `02` and disk space on `/var/iri`. |
| **TempoGeneratorRemoteWriteFailing** | Tempo's metrics generator cannot remote-write service-graph samples to Prometheus for 10 minutes, so the service map goes stale. In order: the `grafana` web-auth credential Tempo sends matches the bcrypt hash in `/var/iri/monitoring/secrets/prometheus-web.yml`; both units were restarted after that file last changed (`${SYSTEMCTL} restart prometheus.service tempo.service` — a stale loaded hash gives `401` despite a correct password); Prometheus's remote-write receiver is on. |
| **TempoGeneratorSeriesLimited** | The metrics generator dropped series at its `max_active_series` cap in the last hour, so the service graph is incomplete. Raise the cap or add service-graph `filter_policies` in `tempo/tempo.yaml`. |
| **LokiIngestSilent / LogStreamSilent / LokiWriteFailing / LokiDiscardingLines** | Log shipping is broken or a tailed file is dead. `LogStreamSilent`'s `stream` label names the tail: `keycloak` (highest impact — the only signal for the fail-open Discord SPI gate), `backend` / `frontend` / `ingest`, `host-auth`, `host-auditd`, `host-fail2ban`, `ops-deploy`. It fires on absence of that path's `loki_source_file_read_lines_total`, i.e. the file is not tailed at all. Read the cause from Alloy first: `journalctl -u alloy.service --since -6h \| grep "final error sending batch"`, then check the drop-in binds (`systemctl cat alloy.service`), the file's group (`adm`) and `runuser -u alloy -- test -r <file>`. **`LokiIngestSilent` does not see partial silence**: if the *container* streams (`<svc>-stdout`, `edge`, `mon-*`) vanish while the file tails keep flowing, check that the journal is persistent (`ls /var/log/journal`) — the cutover-day failure. A persistent `LokiWriteFailing` with `reason="ingester_error"` naming one near-idle container is an entry older than Loki's 168h window; `stage.drop older_than = "167h"` in `container_mask` guards it — do **not** raise Loki's reject window. |
| **Watchdog** | The dead-man's switch — it **always** fires and routes only to the external healthchecks.io heartbeat. If healthchecks.io goes red, the monitoring stack or the host is down. |

**Root-cause inhibition.** Alertmanager suppresses derived warnings under their root cause so an
outage pages once: a `TargetDown` on an app scrape suppresses that app's `application`-scoped
warnings, a `BlackboxProbeFailed` suppresses that endpoint's `CertificateExpiringSoon` / `Edge*`
posture alerts, `HostDiskCritical` suppresses `HostDiskWarning` for the same mountpoint, a
`ContainerRestartLoop` suppresses that container's resource-pressure warnings (joined on `name`),
and a `TargetDown` suppresses every `instance`-scoped warning derived from that dead target.
Notifications are grouped by `alertname` only. If an expected warning is missing during an incident,
check whether its root-cause critical is firing.

**Notification cadence — one mail per event.** Alertmanager has no "acknowledged" state, so a
still-firing alert is re-sent every `repeat_interval` forever. Since 2026-08-16 e-mail repeats at
**720h (30d)** for both severities: one mail when it starts and, with `send_resolved: true`, one when
it clears. The hourly nudge for an open critical is on **Discord**. To mute an alert before it
resolves, create a time-boxed **Silence** in the Alertmanager UI; do not shorten `repeat_interval`.
`repeat_interval` is bounded from below by `--data.retention` (default 120h), which the unit pins at
`744h` — raise both or neither. And a merged change to the routing only takes effect after the
template is re-rendered (below): on 2026-09-10 mails arrived exactly `4h + 5m` apart because the
running process still held the retired `4h`.

**One edge assertion runs outside this stack**: the daily
[`edge-deny-probe`](../.github/workflows/edge-deny-probe.yml) GitHub Action asserts from a genuinely
external vantage point that the Keycloak Admin Console (`/auth/admin/`) is not reachable from the
internet and that the `/actuator` denies answer 404 (REQ-OBS-012). The internal blackbox exporter
cannot carry that signal — its traffic never arrives from a public address, and the admin allow-list
names exactly the container-network gateways. A failing run notifies via GitHub's workflow-failure
e-mail, not Alertmanager.

## Operating procedures

Every command here runs on the host as root unless it says otherwise. The container uid `N` is
stored on disk as `subuid_base + N - 1` under rootless Podman; derive it, never transcribe it:

```bash
IRI_UID=$(id -u iri)
SUB=$(grep '^iri:' /etc/subuid | cut -d: -f2)          # 100000 on both hosts today
own() { echo $(( SUB + $1 - 1 )); }                     # own 65534 -> 165533, own 472 -> 100471
SYSTEMCTL="sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRI_UID} systemctl --user"
PODMAN="sudo -u iri podman"
```

### Querying Prometheus on the host

Prometheus publishes no host port and the host has no route into the rootless networks, so ask it
from **inside** its own container, reading the password from the container's own mounted secret — it
never crosses into the shell. This is what `scripts/check-conformance.py` does:

```bash
q() {   # percent-encode on the host, so the shell fragment carries no quotes or spaces
  local enc; enc=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1")
  ${PODMAN} exec prometheus sh -c "p=\$(cat /etc/prometheus/secrets/web_password); \
    a=\$(printf grafana:%s \"\$p\" | base64 -w0); \
    wget -q -O- --header=\"Authorization: Basic \$a\" 'http://127.0.0.1:9090/api/v1/query?query=${enc}'"
}
q 'up == 0'
```

For anything longer than a one-liner, use Grafana → Explore.

### Rebuilding the monitoring plane on a new host

The Ansible role and the first deploy do most of it; four things stay manual because they are
secrets or per-host material. [`docs/backup.md`](../docs/backup.md) → *Restoring* is the parent
procedure; this is its monitoring half.

**Automatic — verify, do not redo:**

| What | Done by |
| --- | --- |
| `/var/iri/monitoring/{data,certs,secrets}` (owned by `iri`), `textfile` (owned by `deploy`, 0755) | role, `30-directories.yml` |
| the per-service data dirs with **translated** owners — `data/{prometheus,alertmanager}` → 65534, `data/grafana` → 472, `data/{loki,tempo}` → 10001 | role, `basetool_host_container_owners` |
| node_exporter, alloy (+ its log-source drop-in and `/etc/sysconfig/alloy`), the podman exporter, both collector timers, the persistent journal, `psi=1`, rsyslog/auditd `adm` readability | role, `27-observability.yml` |
| the nine Quadlet units and their `env.d` files (from `.env`) | the first `deploy.sh` run |
| `.env` (all `MONITORING_*`, `PROMETHEUS_WEB_PASSWORD`, `PG_EXPORTER_*`, `REDIS_EXPORTER_PASSWORD`, `GRAFANA_*`, `KC_METRICS_ENABLED`, `IRI_MONITORING_ENABLED`), the Redis ACL with its `monitoring` user, the Keycloak `grafana` client | the backup restore |

Podman refuses to start a unit whose bind source is missing (it does not create it the way Docker
did), so the files below must exist **before** the first deploy starts the monitoring units.

**1. The four secrets** — `/var/iri/monitoring/secrets/{scrape_password, prometheus_web_password,
prometheus-web.yml, alertmanager.yml}`, owner `own 65534`, mode 600.

- **From a backup** (the normal case): restore `monitoring/secrets.tar.gz` per
  [`docs/backup.md`](../docs/backup.md) step 4. The restored values match the restored `.env` by
  construction — do not mint new ones next to a restored `.env`.
- **Without a usable backup:** mint them. `alertmanager.yml` cannot be minted — it carries the SMTP
  credential, the healthchecks.io ping URL and the Discord webhook — so re-render it (next section).
  `scrape_password` is read by no scrape job since ADR-0134, but `prometheus.container` still mounts
  it.

  ```bash
  cd /var/iri/monitoring/secrets
  openssl rand -base64 36 | tr -d '\n' > scrape_password
  openssl rand -base64 36 | tr -d '\n' > prometheus_web_password
  WEB_PW="$(cat prometheus_web_password)"
  BCRYPT="$(${PODMAN} run --rm docker.io/library/httpd:2.4-alpine htpasswd -nbBC 10 "" "${WEB_PW}" | tr -d ':\n')"
  printf 'basic_auth_users:\n  grafana: %s\n' "${BCRYPT}" > prometheus-web.yml
  unset WEB_PW BCRYPT
  chown "$(own 65534):$(own 65534)" scrape_password prometheus_web_password prometheus-web.yml
  chmod 600 scrape_password prometheus_web_password prometheus-web.yml
  restorecon -RF /var/iri/monitoring/secrets
  ```

  Then set the **same** values in `.env`: `PROMETHEUS_WEB_PASSWORD` (read by Grafana's datasource
  and Tempo's remote write — a mismatch makes every panel 401 and fires
  `TempoGeneratorRemoteWriteFailing`) and `MONITORING_SCRAPE_PASSWORD`. The hash must start with
  `$2y$`; if the shell ate the `$` signs, re-run it.

**2. `certs/basetool-ca.crt`** — the public half of the shared keystore, which Prometheus pins for
the four app scrapes and the blackbox `https_internal` module trusts. From a backup it arrives with
`secrets.tar.gz`. Otherwise export it (openssl prompts for the keystore password; never pass it on
the command line):

```bash
openssl pkcs12 -in /var/iri/secrets/keystore.p12 -clcerts -nokeys \
  | openssl x509 -out /var/iri/monitoring/certs/basetool-ca.crt
# add -legacy after `pkcs12` if OpenSSL 3.x rejects the keytool-made p12
chmod 644 /var/iri/monitoring/certs/basetool-ca.crt && restorecon -F /var/iri/monitoring/certs/basetool-ca.crt
openssl x509 -in /var/iri/monitoring/certs/basetool-ca.crt -noout -subject -ext subjectAltName
```

Once the per-service internal TLS is rolled out (REQ-SEC-070) the file is the **internal CA**
instead — `install -m 0644 /var/iri/secrets/tls/ca.crt /var/iri/monitoring/certs/basetool-ca.crt`
— and the steps above no longer apply: the per-service keystores hold leaves, not the anchor. Every
consumer (Prometheus `server_name`, the blackbox module, the edge's `proxy_ssl_name`) already checks
the service's name, so nothing in their configuration changes; during the rollout the file briefly
holds the CA **and** the old shared certificate, so either side verifies — production's does since
2026-09-25 (rollout step 2), until step 4
([`deployment.md` → *Internal TLS*](../docs/deployment.md#internal-tls-per-service-certificates-from-a-private-ca)).

**3. `certs/grafana.{crt,key}`** — Grafana's own **self-signed, per-host** leaf. `grafana.container`
will not start without it. **Never restore it from another host's backup** (the archive carries the
old host's pair — extract around it, or re-mint afterwards); nothing verifies that leaf, so a wrong
one fails quietly — **unless the edge verifies it**, as production's has since 2026-09-25 (`EDGE_GRAFANA_UPSTREAM_VERIFY=on`,
[`deployment.md` → *The edge verifies Grafana*](../docs/deployment.md#the-edge-verifies-grafana)):
then the edge pins this exact file, and after re-minting it **restart the edge as well as
Grafana**, or the Grafana host answers `503`.

```bash
cd /var/iri/monitoring/certs
GH="$(sed -n 's/^EDGE_HOST_GRAFANA=//p' /var/iri/code/.env | tr -d '"')"; GH="${GH:-grafana.profit-base.online}"
openssl req -x509 -newkey rsa:2048 -nodes -keyout grafana.key -out grafana.crt \
  -subj "/CN=grafana" -addext "subjectAltName=DNS:grafana,DNS:${GH}" -days 825
chown "$(own 472):$(own 472)" grafana.crt grafana.key
chmod 640 grafana.key && chmod 644 grafana.crt && restorecon -F grafana.crt grafana.key
```

**4. The PostgreSQL `monitoring` roles.** The exporters log in as `monitoring` with only
`pg_monitor`. A `pg_dump` restore does **not** carry roles (they are cluster-wide, not part of a
database dump), so on a host restored from dumps check `pg_up` for both `postgres-*` jobs after the
first deploy and, where it is `0` with `role "monitoring" does not exist`, create the role in that
cluster. `\password` keeps the value out of shell history and the server log; it must equal
`PG_EXPORTER_BACKEND_PASSWORD` / `PG_EXPORTER_KEYCLOAK_PASSWORD` in `.env`:

```bash
${PODMAN} exec -it db-backend sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432'
#   CREATE USER monitoring;  GRANT pg_monitor TO monitoring;  \password monitoring
${PODMAN} exec -it db-keycloak sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15433'
#   the same, with PG_EXPORTER_KEYCLOAK_PASSWORD
```

**5. Verify.** After the first deploy with `IRI_MONITORING_ENABLED=true`:

```bash
${SYSTEMCTL} is-active prometheus grafana loki tempo alertmanager blackbox-exporter \
  postgres-exporter-backend postgres-exporter-keycloak redis-exporter prometheus-podman-exporter
systemctl is-active alloy prometheus-node-exporter iri-container-metrics.timer iri-cert-expiry.timer
ls /proc/pressure /var/log/journal >/dev/null && echo "psi + persistent journal ok"
q 'up == 0'                                   # expect an empty result
q 'count(basetool:container:present)'         # expect every running container
systemctl start iri-cert-expiry.service
grep -c '^basetool_certificate_expiry_timestamp_seconds' /var/iri/monitoring/textfile/certificates.prom   # expect 2
python3 scripts/check-conformance.py --ssh <host>   # from a workstation; covers scrape-targets-up, container-metrics, log-streams
```

In Grafana, confirm the Keycloak login lets an `Admin` in and refuses anyone else, and that
Explore shows one trace in Tempo, one `{app="edge"}` line and one `{app="host-auth"}` line in Loki.
healthchecks.io must go green within minutes (the `Watchdog` heartbeat).

### Re-rendering the Alertmanager config

Needed after any change to `alertmanager/alertmanager.yml.tmpl` and after rotating an SMTP
credential, the healthchecks.io ping URL or the Discord webhook. Nothing does it automatically.
The template's placeholders are `SMTP_SMARTHOST`, `SMTP_FROM`, `SMTP_AUTH_USERNAME`,
`SMTP_AUTH_PASSWORD`, `ALERT_EMAIL_TO`, `HEARTBEAT_URL` and `DISCORD_WEBHOOK_URL` (the Discord
channel is required — the render must not leave it empty). Export them in a root shell without
echoing them (e.g. `read -rs SMTP_AUTH_PASSWORD; export SMTP_AUTH_PASSWORD`), then:

```bash
envsubst < /var/iri/code/monitoring/alertmanager/alertmanager.yml.tmpl > /var/iri/monitoring/secrets/alertmanager.yml.new
grep -n '\${' /var/iri/monitoring/secrets/alertmanager.yml.new | grep -v '^[0-9]*:[[:space:]]*#'   # expect no output: nothing left unrendered
chown "$(own 65534):$(own 65534)" /var/iri/monitoring/secrets/alertmanager.yml.new
chmod 600 /var/iri/monitoring/secrets/alertmanager.yml.new
${PODMAN} run --rm -v /var/iri/monitoring/secrets/alertmanager.yml.new:/cfg.yml:ro \
  --entrypoint amtool quay.io/prometheus/alertmanager:v0.34.1 check-config /cfg.yml
mv /var/iri/monitoring/secrets/alertmanager.yml.new /var/iri/monitoring/secrets/alertmanager.yml
restorecon -F /var/iri/monitoring/secrets/alertmanager.yml
${SYSTEMCTL} restart alertmanager.service       # a single-file mount: restart, never reload
unset SMTP_SMARTHOST SMTP_FROM SMTP_AUTH_USERNAME SMTP_AUTH_PASSWORD ALERT_EMAIL_TO HEARTBEAT_URL DISCORD_WEBHOOK_URL
```

Do not ship a file `amtool` rejects — a bad config means alerts silently do not route. Confirm
`AlertmanagerConfigReloadFailed` stays at 0 afterwards.

### Rotating the monitoring secrets

After a compromise-driven restore ([`docs/backup.md`](../docs/backup.md) → *Rotate secrets*) or on
suspicion: mint new `scrape_password` / `prometheus_web_password` / `prometheus-web.yml` as in step 1
above, put the same values into `.env`, then restart **everything that holds the old value** —
`prometheus`, `tempo` and `grafana` (the last two authenticate to Prometheus with the shared
`grafana` credential and can wedge on a cached 401) — and let the next deploy re-render `env.d`, or
run `deploy.sh --force`. Rotate the Alertmanager receiver credentials at their providers and
re-render. The PostgreSQL / Redis exporter passwords live in the databases and the Redis ACL as well
as in `.env`, and have to move in both places at once.

### Keystore and certificate rotation

The procedure is [`docs/deployment.md`](../docs/deployment.md) (keystore rotation); it owns the
monitoring-side steps too. Afterwards, from here: all four app targets and `keycloak` are `up`
(`q 'up{job=~"basetool-.*|keycloak"}'`), every `blackbox-internal-tls*` target has
`probe_success == 1`, and `sudo systemctl start iri-cert-expiry.service` has re-read the files — the
collector otherwise reports the **old** CA's expiry until the next 03:40.

### After a Temurin bump: re-check the JvmNativeThreadExhaustion signature

`JvmNativeThreadExhaustion` (`loki/rules/fake/basetool-log-alerts.yml`) matches
`pthread_create failed|unable to create native thread` in the `<svc>-stdout` streams. That wording is
written by HotSpot and glibc outside logback and is **JVM-version-dependent**, so every bump of the
`eclipse-temurin:25-jre-alpine` runtime digest in `docker/app/Dockerfile` owes a re-check — otherwise
the rule keeps parsing, keeps deploying and can never fire again. It was verified on
`…@sha256:28db6fdf…` (2026-08-29), **re-verified on `…@sha256:3137541d…` (Temurin 25.0.4+7) on
2026-09-22** (steps 1–3 below, plus the stream check on production:
`{app=~"(backend|frontend|ingest)-stdout"}` present in Loki under the Podman journald path) and
**again on `…@sha256:2ca9adf4…` on 2026-09-25**, the digest `docker/app/Dockerfile` pins since #2035
(steps 1–5; the #2035 bump itself had skipped it) and **again on `…@sha256:3c0a9084…` (Temurin
25.0.4.1+1) on 2026-10-04**, the digest `docker/app/Dockerfile` pins now (steps 1–3 and 5; wording
unchanged). The next digest bump owes it again.

On a workstation, never on production:

1. Reproduce the line on the **new** digest under a pids cap well below what a thread-spawning class
   needs — for example a class that starts threads in a loop, run with
   `docker run --rm --pids-limit 60 eclipse-temurin:25-jre-alpine@sha256:<new> java Spawn.java`.
2. Compare what it prints with the filter and with the verbatim lines recorded in
   `scripts/check-loki-rule-signatures.py`. If the wording changed, widen the filter so it matches
   both, and replace the recorded lines.
3. Confirm the three masking replaces in `alloy/config.alloy`'s `container_mask` cannot touch either
   phrase (they match a JWT, an e-mail address and a bearer/token/session-id keyword), and that the
   only `stage.drop` is still `older_than = "167h"`.
4. Record the verified digest and date in the paragraph above and in REQ-OBS-007
   (`docs/specs/observability.md`), and run `scripts/check-loki-rules.sh`.
5. The same bump owes `JvmStartupCacheRejected` its re-check (same file, same stream): build an app
   image on the new digest, start it with `JAVA_TOOL_OPTIONS=-XX:-UseCompactObjectHeaders` (the
   image's cache is trained with the flag on), and compare the `[aot]` lines it prints with the
   filter. Any re-recorded line goes into `scripts/check-loki-rule-signatures.py`, which also holds
   the recorded lines of step 2 — run it after either change.

For an end-to-end check, provoke it on the isolated test stack (`.env.test`, lowered `pids` limit on
one app service), confirm the line appears in Loki under `{app="<svc>-stdout"}` and not only in the
container log, then restore the limit and tear the stack down with `down --volumes`.

### Restore drill: the monitoring artifacts

`iri-restore-drill.timer` runs [`scripts/restore-drill.sh`](../scripts/restore-drill.sh) weekly
(Sunday 05:30, REQ-OPS-011). Besides the two databases it restores the monitoring artifacts from the
latest snapshot and reports each on its own — they never gate the database proof:

| `artifact` label | Proves |
| --- | --- |
| `grafana_sqlite` | `monitoring/grafana.db` restores as a valid SQLite file |
| `monitoring_secrets` | `monitoring/secrets.tar.gz` (secrets **and** certs) is readable |

The drill also reports `edge_certs`, `acme_state` and `redis_acl`. On a **new host**, run one backup
before the first drill, or the drill restores a snapshot without the monitoring artifacts and fires
`RestoreDrillArtifactNotRestorable` on a false negative:

```bash
systemctl start iri-backup.service            # capture a snapshot that contains them
systemctl start iri-restore-drill.service
grep '^basetool_restore_drill' /var/iri/monitoring/textfile/restore_drill.prom
# expect every basetool_restore_drill_artifact_ok{artifact="…"} at 1, and a fresh last_success timestamp
```

A `0` on a monitoring artifact means that half of a host rebuild would fail today: read
`{app="ops-restore-drill"} |~ "WARN"` for which file, and check the backup log
(`{app="ops-backup"} |~ "monitoring"`) for whether `grafana` was running when the backup ran — the
capture is skipped when it is not.

## Container sizing — how to measure before you tune

**Never size a container from a percentage — size it from a measured budget.** Take one
*time-aligned* snapshot (pass a `time=` while the alert was firing; an instant query resolves each
series within a 5 min lookback), then solve `heapCeiling + overhead <= ~80% of limit`. Read the
container side from the normalised `basetool:container:*` names — the only ones that exist on this
host:

```promql
basetool:container:memory_working_set_bytes{name=~"backend|frontend|ingest"}
sum by (application,area) (jvm_memory_committed_bytes)
sum by (application) (jvm_memory_used_bytes{area="heap"})
```

> [!note] Names in the dated measurements below
> Everything measured before 2026-09-22 was read on the Docker host from cAdvisor. The mapping is
> one-to-one: `container_memory_rss` → `basetool:container:memory_anon_bytes`,
> `container_memory_working_set_bytes` → `…memory_working_set_bytes`,
> `container_memory_mapped_file` → `…memory_mapped_file_bytes`,
> `container_spec_memory_limit_bytes` → `…memory_limit_bytes`,
> `container_cpu_cfs_{periods,throttled_periods,throttled_seconds}_total` →
> `…cpu_{periods,throttled_periods,throttled_seconds}_total`, `container_threads{,_max}` →
> `…pids{,_max}`. The limits themselves are now `Memory=` / `PodmanArgs=--cpus=` / `PidsLimit=` in
> the generated units, derived from the compose file.

Since **2026-09-15** all three JVMs run `-XX:+UseCompactObjectHeaders` (ADR-0180), so every heap
figure recorded before that date was taken on a different object layout — exactly as every
pre-2026-09-13 frontend/ingest figure was taken on a different collector (ADR-0175). A
re-measurement under the new layout is owed (`REQ-OPS-030`) and no limit may be re-derived from the
older numbers. The cutover to Podman is a third such boundary for anything cgroup-derived.

Three traps, all of which have produced a wrong fix here (the measured figures are in the REQ-OPS-020
[sizing ledger](../docs/specs/deployment-delivery.md#sizing-ledger)):

- **`working_set != heap + nonheap`.** A third term — JVM-internal native memory that no JVM metric
  reports (G1 auxiliary structures sized off *max* heap, JIT scratch, glibc malloc arenas) — was
  **226 MB on the frontend**, more than its entire nonheap. Derive it as
  `working_set - (heap_committed + nonheap_committed)` and budget it explicitly. Rule out the innocent
  explanations first: page cache via `memory.stat` (`file`), direct buffers via
  `jvm_buffer_memory_used_bytes`, thread stacks via `jvm_threads_live_threads`.
- **Do not `sum by (name)` over a multi-day window.** Every recreation starts a new series
  generation under the same `name`, and overlapping lives add up — that is how you get a "peak" of
  2183 MB in a 2048 MB container. Use `max by (name) (max_over_time(...))`, or snapshot one instant.
- **A percentage cannot fix a container whose floor already exceeds the target.** Check
  `heap_used + overhead` against the limit *before* tuning. Raising the limit alone never helps a
  JVM, because `MaxRAMPercentage` scales the heap ceiling with it.

Cheap host-side cross-check without Prometheus (working set ≈ `anon` + active file pages), and
confirmation that a changed limit actually **reached** the running container — a stale limit was
the real root cause of a recurring working-set alert in July 2026:

```bash
CG=/sys/fs/cgroup/user.slice/user-${IRI_UID}.slice/user@${IRI_UID}.service/app.slice
# libpod-payload-<id> is the container itself; frontend.service above it reads memory.max "max"
grep -E '^(anon|file|file_mapped|kernel_stack|slab) ' ${CG}/frontend.service/libpod-payload-*/memory.stat
cat ${CG}/frontend.service/libpod-payload-*/memory.max
${PODMAN} inspect --format '{{.Name}} limit={{.HostConfig.Memory}}' frontend backend ingest
```

> [!note] The host-native Alloy and node_exporter — limited and watched since 2026-09-22
> On the Podman host `alloy` and `node_exporter` are system services, not containers. Until
> 2026-09-22 they had no limit at all (`MemoryMax=infinity`, no `GOMEMLIMIT`, measured on
> production) and no alert watched their memory, because the container alerts only saw containers.
> The Ansible role now writes a `20-resources.conf` drop-in for each — Alloy **512M /
> `GOMEMLIMIT=360MiB`**, the budget `docker-compose.monitoring.yml` carried; node_exporter **64M /
> 48MiB**, double the container's 32M because the host service also runs the systemd collector —
> and `scripts/cgroup-container-metrics.py` reads their unit cgroups under the names `alloy` and
> `node-exporter`, so `ContainerMemoryHigh`, `ContainerOomKilled` and `ContainerPidsHigh` and
> dashboard `02` cover them as before the cutover. `HostServiceMetricsMissing` says so if that
> coverage disappears. Read them with `systemctl show alloy prometheus-node-exporter -p MemoryMax
> -p MemoryCurrent` and `go_memstats_*{job="alloy"}`. Measured before the change: alloy anon
> 105 MiB (plus 208 MiB file pages, 170 MiB of them its mapped binary), node_exporter anon 15 MiB.

### Go services (Prometheus, Alloy, Loki, Tempo, the exporters)

Same budget rule, different metrics — and one extra failure mode. A Go process that is **not** given
`GOMEMLIMIT` does not know its cgroup limit exists: the GC sizes the heap off `GOGC` alone (target 2×
live heap) and the scavenger returns arena pages only lazily, so every concurrency spike ratchets the
resident set **up and it never comes back down**. The symptom is a container pinned near its limit
whose *live* heap is a fraction of it — which reads like a leak and is not one:

```promql
go_memstats_heap_alloc_bytes{job="<svc>"}   # live heap — the number that matters
go_memstats_next_gc_bytes{job="<svc>"}      # GC target; ~2x live under default GOGC
go_goroutines{job="<svc>"}                  # flat baseline + spikes = pile-ups, not a leak
go_memstats_sys_bytes - go_memstats_heap_released_bytes   # resident — what GOMEMLIMIT bounds
```

**Do not compare `go_memstats_sys_bytes` against the limit.** It is *reserved address space* and
still counts pages the scavenger has handed back: on Tempo the gap was 164 MiB (436.8 reserved vs a
272.6 MiB working set at the same instant).

> **`GOMEMLIMIT` = 75 % of the container limit**, cross-checked to sit well above the measured live
> heap — every service here is at ≥3.7×. Re-derive it whenever a limit changes.

**Take every reading time-aligned** (the same `time=` for all queries). Mixing a multi-day
`max_over_time` working set with an instant `go_*` reading once produced a *negative* overhead here.

### Why `ContainerMemoryHigh` keys off anon memory — decompose before you size

This trap cost four Alloy limit bumps. Until 2026-08-02 the alert was `ContainerWorkingSetHigh`,
dividing the working set by the limit — and the working set is `anon + ACTIVE file pages (+ kernel)`.
The file-page term includes the service's **own memory-mapped binary**: clean, file-backed and
reclaimable, so it can never cause an OOM, yet it counted fully toward the ratio. Page cache also
**expands to fill whatever limit it is given**, so raising a limit can never resolve such an alert.

Always split the reading first:

```promql
basetool:container:memory_anon_bytes{name="<svc>"}          # anonymous — this is what predicts OOM
basetool:container:memory_mapped_file_bytes{name="<svc>"}   # the mapped binary — reclaimable
```

Measured 7-day peaks on the Docker host, 2026-08-02 — anon vs. what the old alert reported:

| service | anon | working set | mapped binary |
| --- | --- | --- | --- |
| `blackbox-exporter` | **95.3 %** | ~100 % | 16.0 MiB |
| `alertmanager` | 48.3 % | 95.0 % | 24.5 MiB |
| `alloy` | 40.5 % | 94.8 % | 183.9 MiB |
| `node-exporter` | 33.4 % | 83.8 % | 13.5 MiB |
| `loki` | 68.0 % | 80.2 % | 90.9 MiB |
| `postgres-exporter-backend` | 42.4 % | 77.9 % | 9.5 MiB |
| `redis-exporter` | 40.0 % | 77.2 % | 9.5 MiB |

On the metric that predicts OOM, **`blackbox-exporter` was the only service anywhere near its
limit**; everything else was reporting its binary. Three cases:

- **High anon, live heap far below it** → the runtime is hoarding arena; `GOMEMLIMIT` is the fix and
  the limit usually need not move (this was `blackbox-exporter`).
- **High working set, low anon** → mapped binary or page cache; not an OOM risk, do **not** raise the
  limit (this was `alloy` and all four small exporters). Page cache is charged to the cgroup that
  *first faults a page in*, so after a fresh image pull the container running the new binary is
  billed for it — `alloy`'s jump on 2026-07-31 was the v1.18.0 binary, re-attributed.
- **Anon climbing monotonically, or goroutines never returning to baseline** → a real leak; more RAM
  only postpones it.

### CPU limits — read the *average*, size for the *burst* (#937, 2026-08-03)

```promql
# how OFTEN a container is stopped mid-period — peak of a 5m ratio over 7 days
max by (name) (max_over_time((rate(basetool:container:cpu_throttled_periods_total[5m])
  / clamp_min(rate(basetool:container:cpu_periods_total[5m]), 1))[7d:5m]))
# the same thing averaged over the whole window — the sustained tax
sum by (name) (increase(basetool:container:cpu_throttled_periods_total[7d]))
  / clamp_min(sum by (name) (increase(basetool:container:cpu_periods_total[7d])), 1)
# how MUCH time was actually lost — the number to argue from
sum by (name) (increase(basetool:container:cpu_throttled_seconds_total[7d]))
```

**Take all three.** The peak ratio and the average disagree by two orders of magnitude (frontend:
66.7 % peak vs 0.725 % average); **the absolute throttled seconds are** the decision input (frontend:
1506 s/week ≈ 25 minutes of stalled rendering). Three things make CPU different from memory:

- **A quota is a burst ceiling, not a reservation.** Unused quota costs nothing; overcommitting the
  sum past the physical core count is normal.
- **Throttling is per-cgroup against its OWN quota**, not a contention signal. There is no culprit
  neighbour to look for.
- **5-minute averages hide the workload that gets clipped.** JIT compilation, TLS handshakes and a
  WebClient fan-out exceed a 1.0 quota for tens of milliseconds without moving a 5m average. Low
  average plus high throttle seconds is the signature — widen the ceiling.

Measured on the Docker host 2026-08-03, 7 days, for reference (the `npm` row is NPM, retired since):

| service | quota | 5m-peak cores | peak ratio | avg ratio | throttled s |
| --- | --- | --- | --- | --- | --- |
| `frontend` | 1.0 | 0.18 | 66.7 % | **0.725 %** | **1506** |
| `redis` | 0.5 | 0.015 | 3.3 % | 0.661 % | 545 |
| `ingest` | 1.0 | 0.10 | **76.7 %** | 0.245 % | 452 |
| `keycloak` | 2.0 | 0.28 | 53.0 % | 0.106 % | 417 |
| `backend` | 2.0 | 0.51 | 47.0 % | 0.168 % | 284 |
| `db-backend` | 1.5 | 0.096 | 6.0 % | 0.104 % | 164 |
| `npm` | 0.5 | 0.030 | 20.0 % | 0.056 % | 121 |
| `db-keycloak` | 1.0 | 0.018 | 1.5 % | 0.132 % | 23 |

Host context then: 21 containers averaging 0.256 cores together, `load15` 0.36. The four raises #937
made (`frontend` 1.0→2.0, `ingest` 1.0→1.5, `redis` 0.5→1.0, `npm` 0.5→1.0) bought latency, not
throughput.

### Postgres — anon under-reports a database, and the textbook bound is wrong

**Anon under-reports Postgres.** `shared_buffers` is shared memory and lands in the cgroup's file
term, not in `anon`: `db-backend` read **6.96 %** of its limit on anon while its working set was
295 MB. This is the one place the "size from anon" rule is wrong — **read a DB container from
`basetool:container:memory_working_set_bytes`.**

**Do not size a DB container from `max_connections × work_mem`.** That product presumes every pooled
connection runs a `work_mem`-sized sort at once — an analytics shape this OLTP app does not have:

```promql
sum by (job) (increase(pg_stat_database_temp_files[7d]))   # 0 = work_mem never overflowed
sum by (job) (pg_database_size_bytes)                      # how big the data ACTUALLY is
sum by (job) (rate(pg_stat_database_blks_hit[7d]))
  / clamp_min(sum by (job) (rate(pg_stat_database_blks_hit[7d]))
            + sum by (job) (rate(pg_stat_database_blks_read[7d])), 1)   # cache hit ratio
```

2026-08-03: 107.7 MB / 39.4 MB of data, 99.990 % / 99.998 % hit ratio, 0 temp files on both. Size
from `pg_database_size_bytes`, and let `temp_files` — not arithmetic — say whether `work_mem` is big
enough.

## Grafana sandbox-export workflow

Dashboards are provisioned with **`allowUiUpdates: false`**, so the prod Grafana UI is **read-only**.
To change a dashboard:

1. Open it in a **throwaway / sandbox Grafana** (or a local copy) and edit there.
2. **Share → Export → "Export for sharing externally" OFF**, then save the JSON model.
3. Commit the file under [`monitoring/grafana/dashboards/`](grafana/dashboards/).

It ships with the next config bundle and is re-provisioned. **Never hand-edit dashboards on the prod
Grafana** — changes are not persisted. A panel that is empty *by design* (a lazily created counter, a
single-replica fan-out row) says so in its own description; keep it that way for new ones
(REQ-OBS-014).

## Local validation

Lint the configs with ephemeral containers before committing (from the repo root, on a Docker
workstation). CI runs the structural gates in `repo-lint.yml`:

```bash
# All four configuration files at once -- Prometheus (+ the rule files it loads), Alertmanager
# (rendered with dummy values the way the runbook renders it), Alloy (fmt, and validate gated on
# EMPTY OUTPUT) and Loki -- in the digest-pinned images docker-compose.monitoring.yml names. This is
# the CI gate (repo-lint, since 2026-09-22); the .test.sh breaks each file once and must see it fail.
scripts/check-monitoring-configs.sh
scripts/check-monitoring-configs.test.sh

# Prometheus scrape config by hand. The --entrypoint is required: the image's entrypoint is
# /bin/prometheus, so passing `promtool` as the first argument fails with "unexpected promtool".
# Mount where the unit mounts: `rule_files: /etc/prometheus/alerts/*.yml` is an absolute glob, and
# with the directory elsewhere it matches nothing and promtool silently checks no rule file at all.
docker run --rm --entrypoint promtool \
  -v "$PWD/monitoring/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "$PWD/monitoring/prometheus/alerts:/etc/prometheus/alerts:ro" prom/prometheus:v3.15.0 \
  check config /etc/prometheus/prometheus.yml
# The glob'd commands go through `sh -c` so the pattern is expanded INSIDE the container.
docker run --rm --entrypoint sh -v "$PWD/monitoring/prometheus:/cfg" prom/prometheus:v3.15.0 \
  -c 'promtool check rules /cfg/alerts/*.yml'

# Alert-rule unit tests (-w /work makes the tests' ../alerts paths resolve)
docker run --rm --entrypoint sh -v "$PWD/monitoring/prometheus:/work" -w /work \
  prom/prometheus:v3.15.0 -c 'promtool test rules tests/*_test.yml'

# Loki ruler rules — every file parses and every expr is valid LogQL (CI: repo-lint)
scripts/check-loki-rules.sh

# Alertmanager — check a RENDERED alertmanager.yml, not the .tmpl, from the directory holding it.
docker run --rm --entrypoint amtool -v "$PWD:/cfg" quay.io/prometheus/alertmanager:v0.34.1 \
  check-config /cfg/alertmanager.yml

# Blackbox — no `check-config` subcommand, so start it on the config and assert it logs
# "Loaded config file". Then run the DNS modules against the LIVE zone: their regexps assert the
# answer section's real shape, and a CNAME'd query name (api.profit-base.online is one) answers with
# an alias RR next to the address RR — how the 2026-08-18 DnsResolutionFailed false alarm shipped.
docker run --rm -d --name bb-lint -p 19115:9115 \
  -v "$PWD/monitoring/blackbox/blackbox.yml:/etc/blackbox/blackbox.yml:ro" \
  prom/blackbox-exporter:v0.28.0 --config.file=/etc/blackbox/blackbox.yml
docker logs bb-lint 2>&1 | grep -E "Loaded config file|level=ERROR"
for m in dns_apex_a dns_apex_aaaa dns_api_a dns_api_aaaa; do
  echo "$m: $(curl -s "http://localhost:19115/probe?module=$m&target=1.1.1.1" \
    | awk '/^probe_success /{print $2}')"   # expect 1; add &debug=true to see the failing RR
done
docker rm -f bb-lint

# Alloy — format check + validate. `validate` exits 0 even on failure: read its output.
docker run --rm -v "$PWD/monitoring/alloy:/cfg" grafana/alloy:v1.20.1 \
  fmt --test /cfg/config.alloy
docker run --rm -v "$PWD/monitoring/alloy:/cfg" grafana/alloy:v1.20.1 \
  validate /cfg/config.alloy
# The shipper-side masks (CI: repo-lint -> alloy-log-masking)
python3 scripts/check-alloy-log-masking.py

# Compose source — syntax/interpolation check; then the generated units must match it
docker compose -f docker-compose.monitoring.yml config -q
scripts/generate-quadlet.test.sh

# Dashboards — JSON validity, unique dashboard uids, unique panel ids (collapsed rows walked), and
# every referenced datasource uid provisioned. Each fails SILENTLY in Grafana. CI: repo-lint ->
# grafana-dashboards; the .test.sh runs first so the gate cannot pass vacuously.
python3 scripts/check-grafana-dashboards.py
scripts/check-grafana-dashboards.test.sh
```
