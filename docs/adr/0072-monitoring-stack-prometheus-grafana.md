# ADR-0072 — Monitoring stack: Prometheus + Grafana + Loki + Tempo + Alloy on an isolated monitoring plane

- **Status:** Accepted
- **Date:** 2026-07-02
- **Deciders:** @greluc
- **Related:** epic [#936](https://github.com/krt-profit/basetool/issues/936) (approved plan of record) · spec `REQ-OBS-005..008` ([`observability.md`](../specs/observability.md)) · amends the `REQ-SEC-014` HTTPS-only posture ([`security-and-access.md`](../specs/security-and-access.md), wording change ships with the Phase-2 PR) · follow-up [#937](https://github.com/krt-profit/basetool/issues/937) (host re-tuning) · builds on ADR-0049 (config bundle), ADR-0056 (backups)

## Context

The stack (backend, frontend, ingest, Keycloak, 2× PostgreSQL, Redis, NPM on one Docker
host) ran without operations monitoring: no metrics, no log aggregation, no alerting, no
tracing. Outages, disk-fill, failed-login storms and silent degradations (e.g. a
persistently erroring fail-open security gate) were only discoverable by hand. The
trigger question — "are the Caffeine caches worth tuning?" — was unanswerable because hit
ratios, while recorded, were not readable anywhere.

Hard constraints set by the owner: monitoring accessible to authorized admins only; top
priority on avoiding regressions, data leaks and new security holes. The full design was
adversarially reviewed from four lenses (security / ops / governance / completeness, 41
findings incorporated); epic #936 carries the reviewed plan, the scope decisions and the
phase checklists. This ADR records the architectural decisions and their trade-offs.

## Decision

1. **Stack:** Prometheus (metrics, **180-day retention** with a 40 GB size cap), Grafana
   OSS (the only UI), Loki (logs, 31 days), Tempo (traces, 14 days, monolithic mode — no
   Kafka), Grafana Alloy (log shipper + OTLP collector; Promtail is EOL), Alertmanager
   (e-mail via the owner's SMTP provider), plus node_exporter, cAdvisor, postgres_exporter
   ×2, redis_exporter, blackbox_exporter and a docker-socket-proxy.
   Latest stable versions, digest-pinned, Dependabot-maintained (owner version directive).
2. **Separate compose project** (`docker-compose.monitoring.yml`), not a profile of the
   main file: fully decoupled from the 5-minute deploy loop — no `--remove-orphans` risk,
   and a flapping monitoring healthcheck can never trigger an app-stack rollback.
3. **Three isolated networks:** `net-monitoring-scrape` (Prometheus + exporters + scrape
   targets + Alloy's OTLP receiver), `net-monitoring-core` (Prometheus, Alertmanager,
   Loki, Tempo, Alloy, Grafana — app containers are **never** members, so a compromised
   app cannot silence alerts, forge/read logs or touch the trace store), and
   `net-docker-proxy` (socket proxy ↔ cAdvisor/Alloy).
4. **App instrumentation fail-closed (Phase 1, `REQ-OBS-005`):** all three modules expose
   `/actuator/prometheus` behind a dedicated basic-auth `SecurityFilterChain` that denies
   everything until `MONITORING_SCRAPE_USER`/`MONITORING_SCRAPE_PASSWORD` are set; JWT or
   session identities never grant access. Metrics carry only bounded labels and no PII
   (`REQ-OBS-006`). Residual risk, accepted: once credentials are configured, each
   basic-auth attempt costs a BCrypt verification and nothing app-side throttles the path
   (the existing rate limiters cover `/api/**` / `/v1/**` only) — an internet client could
   drive unbounded credential guesses / CPU load. Compensating control: the Phase-2 runbook
   applies the NPM `/actuator` deny on **both** public hosts **before** the credentials are
   deployed, so the credentialed endpoint is reachable only from the internal scrape
   network; the ≥32-char random scrape password keeps the guessing margin comfortable
   either way.
5. **Transport model:** app/Keycloak/NPM edges stay HTTPS (Prometheus validates the pinned
   public certificate; no `insecure_skip_verify`). **Inside** the isolated monitoring
   networks traffic is deliberately plain HTTP — an owner-approved carve-out of the
   HTTPS-only rule (`REQ-OBS-008`; the `REQ-SEC-014` wording is amended in the Phase-2
   PR). The shared `keystore.p12` private key never leaves the four existing services;
   Grafana gets its own self-signed certificate.
6. **docker.sock least privilege:** a `:ro` socket mount is **not** a mitigation (connect
   is unaffected; the raw API is root-equivalent and exposes every container's env via
   inspect). The socket is mounted only into a GET-only docker-socket-proxy; cAdvisor and
   Alloy consume `tcp://socket-proxy:2375`. Residual risk: proxied consumers can still
   read inspect metadata including env values — accepted, because both consumers are on
   an isolated network and hardened (`no-new-privileges`, read-only rootfs, pids-limit,
   positive `oom_score_adj`; Alloy additionally `cap_drop: ALL`, cAdvisor does not drop
   caps as it must read host cgroup/sys state).

   **Amendment (2026-07-04, PR [#982](https://github.com/krt-profit/basetool/pull/982) —
   owner-approved):** cAdvisor **additionally** mounts the host containerd socket read-only
   (`/run/containerd/containerd.sock:ro`). Its Docker container factory does not work over
   the Docker REST API alone — it opens a direct containerd client (Docker delegates runtime
   state to containerd), taking the socket path from `docker info` and dialing it inside its
   own container. Without that socket the factory fails to register; under `--docker_only`
   cAdvisor then emits no `name`-labelled container series at all and the entire Container
   dashboard reads "No data" (observed live during the Phase-2 rollout — the log line was
   `Registration of the docker container factory failed: unable to create containerd
   client`). The docker-socket-proxy speaks only the Docker REST API, not containerd's gRPC,
   so it cannot satisfy this — there is no GET-only path to containerd. **Consequence:** the
   containerd socket is root-equivalent (create/exec containers → host takeover), so for
   cAdvisor the proxy's GET-only filtering no longer bounds the blast radius. Accepted
   because (a) cAdvisor already mounts `/:/rootfs:ro` and can read the whole host filesystem,
   so its confidentiality exposure was already near-total; (b) it sits only on the isolated
   `net-docker-proxy` / `net-monitoring-scrape`, has no host port, and runs
   `no-new-privileges`; (c) it is the canonical way cAdvisor is operated (Google's reference
   run mounts `/var/run`, which holds both sockets). The socket-proxy is retained: Alloy
   needs only the Docker REST API (`discovery.docker` + log streaming, no containerd), so the
   proxy keeps full value there, and cAdvisor's own Docker-API access stays GET-filtered —
   only the single containerd socket is added on top, nothing more.

   **Amendment (2026-07-17):** the mount is now the containerd run **directory**
   (`/run/containerd:/run/containerd:ro`), not the single socket file. A single-file bind mount pins
   the create-time inode, so a containerd restart that re-creates the socket strands cAdvisor on the
   old, dead inode — `dial unix /run/containerd/containerd.sock: connect: connection refused` on every
   housekeeping tick, and a core container recreated after that point loses its handler → a false
   `CoreContainerMetricsMissing` (observed 2026-07-16/17 after a host containerd restart). A directory
   mount always resolves the live socket. This is the same inode-pin class as the single-file
   `prometheus.yml` config mount (§6 above / REQ-OPS-013). The security envelope is unchanged: the
   directory holds the same root-equivalent containerd socket plus its per-container shim sockets, all
   already within cAdvisor's near-total `/:/rootfs:ro` host read access.

7. **Admin-only access:** Grafana behind NPM + Keycloak OIDC restricted to realm role
   `Admin` (PKCE, `role_attribute_strict`, dedicated protocol mapper); basic auth,
   sign-up, anonymous access, gravatar and external snapshots disabled. No other
   component gets a host port or public route.

8. **Log-privacy per stream (`REQ-OBS-007`):** app streams are PII-masked at the source
   (the ingest module gained the `PiiMaskingLogstashEncoder` for exactly this reason);
   Keycloak's file log is masked in the shipper; **NPM access, SSH/host-auth and the host
   security (auditd, fail2ban) streams are deliberately ingested with client IPs/usernames at
   31-day retention** — an owner-recorded data-protection decision conditioned on the
   privacy-policy extension (Phase-2 deliverable).

   **Amendment (2026-07-04, epic [#936](https://github.com/krt-profit/basetool/issues/936)):** an
   "NPM admin-UI" login stream was originally named here as an IP-bearing stream. It does not
   exist as designed — nginx-proxy-manager does not log admin-UI logins to its container
   stdout (only operational lines: nginx reloads, cert renewals, `[emerg]`/`[error]`), and the
   admin UI is loopback-only. Admin-UI login monitoring is therefore descoped (accepted gap);
   the NPM container stdout is retained as **PII-free operational logging**, surfaced on the
   *NPM errors & warnings* dashboard panel, and is **not** part of the 31-day IP-retention set.

9. **Single Prometheus with long local retention instead of Thanos/Mimir:** at this
   series volume (~11 jobs, one host) a remote-storage tier is pure overhead. The 180-day
   requirement is met with local TSDB retention (`180d` + 40 GB cap + a TSDB-size alert),
   protected by a **weekly TSDB snapshot into the restic backup** (admin API enabled only
   together with basic auth on the core network, since it includes deletion endpoints).

10. **Backup scope:** Grafana SQLite (consistent `sqlite3 .backup`), rendered monitoring
    secrets/certs, Alertmanager state (silences) and the weekly TSDB snapshot join the
    nightly restic backup (ADR-0056). **Deliberately excluded:** Loki data — restic's GFS
    retention (up to ~6 months) would silently extend the approved 31-day IP retention and
    undermine that decision; Tempo (ephemeral diagnostics) and exporter/textfile data
    (regenerable) are excluded too. The monthly restore drill covers the monitoring
    artifacts and writes its own outcome metrics.

11. **Dead-man's switch:** an always-firing `Watchdog` alert routed to healthchecks.io —
    a dead monitoring stack (or dead host) must page, which no in-host component can do.

12. **Ops-automation visibility:** deploy.sh, backup.sh, the restore drill and
    docker-cleanup write node_exporter textfile metrics (per-outcome timestamps,
    durations, per-artifact restore results) so the alert catalog can distinguish
    "failed", "stale" and "never ran" (`absent()`-guarded).

## Consequences

- Positive: outages, disk-fill, failed-login/abuse patterns, silent job failures, deploy
  rollbacks and backup/restore regressions become visible and alertable; Caffeine/pool
  tuning (#937) becomes data-driven; traces make cross-module latency debuggable.
- Negative / accepted: ~1.9 GB additional RAM limits and ~40–60 GB disk (resolved by the
  owner-decided CPX42 rescale); a deliberate cleartext carve-out inside isolated
  monitoring networks (documented, spec-amended); client IPs retained 31 days in Loki
  (documented, privacy-policy-covered); the config-bundle allowlist and deploy.sh gain a
  non-gating monitoring apply step (the earlier "deploy.sh unchanged" claim was withdrawn
  in review).
- Every future feature change must keep the monitoring in sync — a binding CLAUDE.md rule
  ships with the Phase-2 PR (wording recorded in epic #936).

## Amendment 2026-07-12 — `stop_grace_period: 45s` on the two dskit stores (Loki + Tempo)

`loki` and `tempo` are both Grafana **dskit** servers that flush chunks / drain their write-ahead
log on `SIGTERM` during a graceful shutdown bounded by `server.graceful_shutdown_timeout` (default
**30s**; neither `loki-config.yml` nor `tempo.yaml` overrides it). Both services carried **no**
`stop_grace_period` in `docker-compose.monitoring.yml`, so they ran on Docker's **10s** default.

The routine way to apply a `loki-config.yml` / `tempo.yaml` change is `deploy.sh --force-recreate
<svc>` — the only way past the inode-pinned single-file bind mount (the same trap documented for
Prometheus in the v1.3.3 config-reload fix). With a 10s grace, Docker `SIGKILL`s the service ~20s
into its 30s drain: Loki's ingester chunk-flush/WAL and Tempo's live-store WAL are truncated
mid-write, forcing a WAL replay on the next start and risking loss of recently-ingested log lines /
spans. For Tempo this is the exact failure mode the `TempoWritePathFailing` alert
(`live-store` completion/flush failures) surfaces after an ungraceful restart.

**Decision:** set `stop_grace_period: 45s` on both `loki` and `tempo` — comfortably above the 30s
dskit drain, consistent with the app compose's DB (60s) / JVM (30s) grace periods. **Prometheus is
deliberately excluded**: its TSDB WAL replay is crash-safe by design, so a hard stop loses at most
the in-memory head that replay reconstructs. The stateless exporters, the socket proxy, Alertmanager
and Alloy keep the 10s default (no persistent write path that an abrupt stop can corrupt). No alert,
metric, dashboard or scrape target changes — this is a shutdown-timing hardening of the compose
definition only.

