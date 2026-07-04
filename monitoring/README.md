# Profit Basetool — Monitoring Plane

In-repo reference for the observability stack that watches the Profit Basetool
deployment (host, containers, apps, edge, business signals). This is the
operator-facing map of the config tree, a short alert-response runbook, and the
workflow for editing Grafana dashboards. For the full rollout — host prep,
secrets, DNS, NPM proxy hosts, Keycloak client, first boot — see
[`docs/MONITORING_ROLLOUT_RUNBOOK.md`](../docs/MONITORING_ROLLOUT_RUNBOOK.md).

## Overview

The monitoring plane ships as a **dedicated compose project**,
[`docker-compose.monitoring.yml`](../docker-compose.monitoring.yml) at the repo
root, plus this `monitoring/` config tree. Both ride the config bundle to
`/var/iri/code` on the host and are brought up as their own Compose project,
separate from the application stack.

**Architecture — three isolated Docker networks:**

- **`net-monitoring-scrape`** — Prometheus, the exporters, the app/Keycloak
  scrape targets, and Alloy's OTLP receiver. This is the only network where
  Prometheus reaches metrics endpoints.
- **`net-monitoring-core`** — Prometheus, Alertmanager, Loki, Tempo, Alloy, and
  Grafana talk to each other here. **Application containers are never members of
  this network.**
- **`net-docker-proxy`** — docker-socket-proxy ↔ cAdvisor / Alloy. The Docker
  socket is never mounted directly into cAdvisor or Alloy; they reach a
  read-scoped proxy instead.

**Grafana is the only UI**, published behind NPM + Keycloak OIDC and restricted
to the realm role `Admin`. There is no other exposed monitoring surface.

**Components and versions:**

|      Component       |                      Image                      | Version |
|----------------------|-------------------------------------------------|---------|
| Prometheus           | `prom/prometheus`                               | v3.13.0 |
| Grafana (OSS)        | `grafana/grafana-oss`                           | 13.0.2  |
| Loki                 | `grafana/loki`                                  | 3.7.3   |
| Tempo                | `grafana/tempo`                                 | 2.10.7  |
| Alloy                | `grafana/alloy`                                 | v1.17.1 |
| Alertmanager         | `quay.io/prometheus/alertmanager`               | v0.33.0 |
| node_exporter        | `quay.io/prometheus/node-exporter`              | v1.11.1 |
| cAdvisor             | `ghcr.io/google/cadvisor`                       | v0.60.3 |
| postgres_exporter ×2 | `quay.io/prometheuscommunity/postgres-exporter` | v0.20.0 |
| redis_exporter       | `oliver006/redis_exporter`                      | v1.86.0 |
| blackbox_exporter    | `prom/blackbox-exporter`                        | v0.28.0 |
| docker-socket-proxy  | `tecnativa/docker-socket-proxy`                 | v0.4.2  |
| github_exporter      | `quay.io/promhippie/github-exporter`            | 15.0.1  |

## Config tree map

- **`prometheus/prometheus.yml`** — scrape configuration (jobs, targets,
  relabeling). Retention (180d / 40GB) and the admin API are **not** in this
  file; they are CLI flags on the Prometheus service in the compose
  (`--storage.tsdb.retention.time=180d`, `--storage.tsdb.retention.size=40GB`,
  `--web.enable-admin-api`).
- **`prometheus/alerts/*.yml`** — Prometheus alerting rules, grouped by concern:
  `infrastructure.yml`, `apps.yml`, `business.yml`, `ops-automation.yml`,
  `meta.yml`.
- **`loki/loki-config.yml`** — Loki config: 31d compactor retention and ingest
  rate caps.
- **`loki/rules/fake/*.yml`** — LogQL ruler alerts (`basetool-log-alerts.yml`);
  log-derived alerts evaluated by Loki.
- **`tempo/tempo.yaml`** — Tempo, monolithic mode, 14d trace retention.
- **`alloy/config.alloy`** — Alloy pipeline: log shipping to Loki, OTLP → Tempo
  trace forwarding, and per-stream masking.
- **`blackbox/blackbox.yml`** — blackbox_exporter modules (HTTPS probes).
- **`alertmanager/alertmanager.yml.tmpl`** — **template**, rendered on the host
  via `envsubst` at deploy time. The rendered `alertmanager.yml` is **not**
  committed (it carries secrets).
- **`grafana/provisioning/{datasources,dashboards}`** + **`grafana/dashboards/*.json`**
  — 13 dashboards, provisioned **read-only** (see the sandbox-export workflow
  below).

## Privacy / retention at a glance

- **Metrics — 180d.** No PII; bounded labels only (REQ-OBS-006).
- **Logs — 31d.** The NPM-access, NPM-admin, and SSH-host-auth streams retain
  client IPs / usernames (owner-approved, covered by the privacy policy —
  REQ-OBS-010). The Keycloak file log is masked in the shipper before it reaches
  Loki.
- **Loki is excluded from backups**, so its retention window cannot be extended
  by restoring an old snapshot.
- **Traces — 14d.**
- **Grafana is admin-only** (Keycloak realm role `Admin`).

## Alert-response runbook (alert → first actions)

These are the committed alertnames. **Alert e-mails never contain user data.**
Keep triage fast: confirm the signal in the matching Grafana dashboard, then act.

|                               Alert                                |                                                                                      First actions                                                                                       |
|--------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **TargetDown**                                                     | A scrape target is down. Check the container/host is up and the exporter port is reachable on `net-monitoring-scrape`.                                                                   |
| **BlackboxProbeFailed**                                            | An HTTPS probe failed. Curl the endpoint from the host; check NPM proxy host, cert, and upstream health.                                                                                 |
| **HostDiskCritical / HostDiskWarning**                             | Disk filling. Find the largest consumers; clear logs/images (`docker system prune`), rotate, or grow the volume. Critical = act now.                                                     |
| **PostgresConnectionsCritical**                                    | Connection pool near max. Check for a connection leak or runaway query; inspect HikariCP and `pg_stat_activity`.                                                                         |
| **RedisRdbStale**                                                  | Last successful RDB save is too old. Check Redis persistence config, disk space, and the Redis log for save errors.                                                                      |
| **CertificateExpiringSoon**                                        | A TLS cert is near expiry. Renew/rotate it and reload the terminating proxy (NPM / backend keystore).                                                                                    |
| **ContainerRestartLoop**                                           | A container is crash-looping. Read its logs, check the last deploy and resource limits; roll back if a bad image shipped.                                                                |
| **SystemdUnitFailed**                                              | A host unit failed. `systemctl status <unit>` + `journalctl -u <unit>`; restart once the cause is clear.                                                                                 |
| **BankLedgerIntegrityViolation**                                   | **CRITICAL.** Investigate the ledger before any further bank mutation — do not let bookings continue. Reconcile balances and identify the divergent entry first.                         |
| **UserSyncStale**                                                  | The WoltLab → basetool user sync has not run/succeeded recently. Check the sync job logs and the ingest path.                                                                            |
| **OptimisticLockConflictSpike**                                    | A likely locking regression. Check recent deploys; look for a coarse lock or a missed `data-version` propagation on the affected surface.                                                |
| **AuditSilenceAnomaly**                                            | An audited area has gone unexpectedly quiet. Verify the audit pipeline is recording; a silent area may mean logging broke, not that activity stopped.                                    |
| **RegistrationApprovalOverdue / BankBookingApprovalOverdue**       | An approval queue is aging past SLA. Notify the approvers; clear the backlog.                                                                                                            |
| **KeycloakLoginErrorSpike**                                        | Login errors jumped. Check Keycloak logs and the identity provider; distinguish a real outage from a credential-stuffing attempt.                                                        |
| **KeycloakErrorRateHigh**                                          | Keycloak error rate elevated. Check the fail-open Discord SPI precheck path — it degrades open, so a spike there points at the SPI, not core auth.                                       |
| **SshPasswordLoginOnKeyOnlyHost**                                  | **CRITICAL — possible compromise.** A password login succeeded on a key-only host. Treat as an intrusion: review auth logs, lock the account, rotate keys.                               |
| **SshFailedAuthSpike**                                             | Failed SSH auth surge. Check source IPs; confirm fail2ban/firewall are active and consider blocking.                                                                                     |
| **NpmAdminLoginFailure**                                           | Failed logins against the NPM admin UI. Confirm it is not exposed publicly; review source IPs.                                                                                           |
| **EdgeServerErrorSpike**                                           | 5xx spike at the edge (NPM). Check upstream app health and NPM logs; correlate with recent deploys.                                                                                      |
| **DeployRolledBack / DeployFailed**                                | A promoted release did not ship. Check `deploy.sh` logs on the host; determine why it failed/rolled back before re-promoting.                                                            |
| **DeployConfigBlocked**                                            | A deploy was blocked on a stateful-infra guard. Run the documented stateful-infra upgrade, then re-run `deploy.sh --force`.                                                              |
| **BackupStaleOrMissing**                                           | The backup job is overdue or failed. Check the backup cron/logs and destination; run a manual backup once fixed.                                                                         |
| **RestoreDrillStaleOrMissing / RestoreDrillArtifactNotRestorable** | The restore drill is stale or its artifact will not restore. Investigate `restore-drill.sh` output; a non-restorable artifact means backups are unverified — fix before relying on them. |
| **DockerCleanupStaleOrMissing**                                    | The docker-cleanup job is overdue. Check `docker-cleanup.sh` logs; run it manually if disk pressure is building.                                                                         |
| **PrometheusTsdbApproachingCap**                                   | Prometheus TSDB nearing the 40GB / 180d cap. Verify retention flags and disk; investigate label cardinality if growth is abnormal.                                                       |
| **AlertmanagerNotificationsFailing**                               | Alertmanager cannot deliver notifications. Check its logs and the receiver config (SMTP / Discord); alerts may be firing silently.                                                       |
| **Watchdog**                                                       | The dead-man's switch — it **always** fires and is routed only to the external healthchecks.io heartbeat. If healthchecks.io goes red, the monitoring stack or the host is down.         |

## Grafana sandbox-export workflow

Dashboards are provisioned with **`allowUiUpdates: false`**, so the prod Grafana
UI is **read-only**. To change a dashboard:

1. Open the dashboard in a **throwaway / sandbox Grafana** (or a local copy).
2. Make your edits there.
3. **Share → Export → "Export for sharing externally" OFF**, then save the JSON
   model.
4. Commit the updated file under
   [`monitoring/grafana/dashboards/`](grafana/dashboards/).

It ships via the config bundle on the next deploy and is re-provisioned into the
prod Grafana. **Never hand-edit dashboards on the prod Grafana** — changes are
not persisted and are lost on the next re-provision.

## Local validation

Lint the configs with ephemeral containers before committing. The operator or CI
can run these from the repo root:

```bash
# Prometheus scrape config + alert rules
docker run --rm -v "$PWD/monitoring/prometheus:/cfg" prom/prometheus:v3.13.0 \
  promtool check config /cfg/prometheus.yml
docker run --rm -v "$PWD/monitoring/prometheus:/cfg" prom/prometheus:v3.13.0 \
  promtool check rules /cfg/alerts/*.yml

# Alertmanager — check the RENDERED alertmanager.yml (after envsubst), not the .tmpl
docker run --rm -v "$PWD:/cfg" quay.io/prometheus/alertmanager:v0.33.0 \
  amtool check-config /cfg/alertmanager.yml

# Alloy — format check + validate
docker run --rm -v "$PWD/monitoring/alloy:/cfg" grafana/alloy:v1.17.1 \
  fmt --test /cfg/config.alloy
docker run --rm -v "$PWD/monitoring/alloy:/cfg" grafana/alloy:v1.17.1 \
  validate /cfg/config.alloy

# Compose project — syntax/interpolation check
docker compose -f docker-compose.monitoring.yml config -q

# Dashboards — valid JSON
jq . monitoring/grafana/dashboards/*.json > /dev/null
```

