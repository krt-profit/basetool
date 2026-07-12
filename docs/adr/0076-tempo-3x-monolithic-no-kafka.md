# ADR-0076 — Tempo 3.x stays monolithic (`-target=all`), no Kafka, local filesystem backend

- **Status:** Accepted
- **Date:** 2026-07-06
- **Deciders:** @greluc
- **Related:** issue [#1036](https://github.com/krt-profit/basetool/issues/1036) (verified migration plan of record) · extends ADR-0072 (monitoring stack) · spec `REQ-OBS-009` (distributed tracing) / `REQ-OBS-013` (telemetry-sink failures) ([`observability.md`](../specs/observability.md)) · runbook [`docs/MONITORING_ROLLOUT_RUNBOOK.md`](../MONITORING_ROLLOUT_RUNBOOK.md)

## Context

ADR-0072 introduced the trace store as `grafana/tempo` in monolithic mode (`-target=all`, local
filesystem backend, 14-day retention, no Kafka), pinned to the 2.10.x line. The pin was deliberate:
Tempo 3.0 is a breaking architecture rewrite whose monolithic config could not be verified at the
original rollout, so the 2.10 config was kept and 3.x deferred to a follow-up (the closed Dependabot
PR #1030 correctly failed, because a bare image bump without the config rewrite cannot start).

The 2.x line receives no LTS designation and no published EOL from Grafana; the maintainers' stated
plan is to retire the old architecture with 2.10 as its final feature line, so staying on 2.10
indefinitely is an unbounded security-patch risk. Issue #1036 is the researched, source-verified
follow-up that moves the store to the current 3.x line without regressions.

What 3.0 changes that forces a decision: the write path is rewritten — the `ingester` and `compactor`
blocks are **removed** (replaced in-process by a live-store plus a backend-scheduler/backend-worker
pair), and Tempo parses config with `yaml.UnmarshalStrict`, so the old blocks are a hard startup
failure. Microservices mode additionally **requires** a Kafka-compatible queue and object storage;
monolithic mode does not (the distributor pushes trace data in-process directly to the live-store).
The local filesystem backend remains supported in monolithic mode, and existing 2.x vParquet4 (RF3)
blocks are read automatically by 3.0.

## Decision

We will upgrade the trace store to the current Tempo 3.x line (pinned to `grafana/tempo:3.0.2`) and
**keep it monolithic** — `-target=all`, a single process, the **local filesystem backend**, and
**no Kafka**. Retention stays **unchanged at 336h (14 days)**, now expressed explicitly under the 3.x
`backend_scheduler`/`backend_worker` compaction keys (it equals the 3.x default; set explicitly so
the ADR-0072 retention decision stays visible in config). The existing data directory is **kept, not
wiped**, across the upgrade so historical traces remain queryable. The port/endpoint contract
(3200/9095/4317/4318, `/ready`, `/metrics`, `up{job="tempo"}` liveness) and the backup exclusion
(traces are ephemeral diagnostics, ADR-0072 §10) are unchanged.

We will **not** adopt microservices mode, Kafka/Redpanda/WarpStream, or an object-storage backend.

## Consequences

- **Positive.** The store leaves an unmaintained line for the actively-developed 3.x line with no
  functional regression: the single `tempo_distributor_spans_received_total` panel keeps working (the
  metric is unchanged in 3.x), the Grafana datasource contract is untouched, and the officially
  simple monolithic upgrade path ("update the configuration and upgrade the binary", same storage,
  no cutover) applies. The config was config-verified offline and validated on a throwaway stack,
  including an upgrade-with-existing-data simulation confirming 2.x-written vParquet4 blocks stay
  queryable under 3.0.
- **Accepted costs.** 3.x logs the known idle noise of grafana/tempo#7403 (a backend-worker "no jobs
  found" ERROR roughly once or twice a minute) — bounded and harmless here, because Tempo's container
  logs are not shipped to Loki (Alloy's keep-filter excludes it) and are json-file-capped, so it can
  trip no alert. There is no in-place downgrade from 3.0, but a rollback is a config-bundle rollback
  (re-pin 2.10.7 + restore the old `tempo.yaml`): 3.0-written blocks are vParquet4/RF1 and remain
  readable by 2.x, so 2.10 restarts cleanly on the same data dir.
- **Follow-up.** Prod memory stays capped at 512M; if it does not hold under real load it is tuned
  via `memory.automemlimit_enabled` or a data-driven limit bump under #937 — not by reverting.

## Alternatives considered

- **Microservices mode (distributor + live-store + block-builder + backend-scheduler/worker +
  queriers).** Rejected: it would add a mandatory Kafka-compatible queue and object storage plus
  several containers on a memory-capped 16 GB host, for a span volume orders of magnitude below
  Grafana's own ~25–35 MB/s microservices threshold. Failure-domain isolation buys nothing here — the
  whole monitoring plane is best-effort and non-gating by design.
- **Stay on Tempo 2.10.x.** Rejected: 2.x is the retiring architecture with no LTS/EOL commitment, an
  unbounded security-patch risk once the line quietly stops.
- **Rewrite the config with `tempo-cli migrate config`.** Rejected: it silently emits empty output
  when stdout is piped (grafana/tempo#7415); the 35-line config was rewritten manually instead.

## Amendment 2026-07-06 (#1041 item 22a) — enable the service-graph metrics-generator

Traces were ingested but the Grafana serviceMap/nodeGraph panels (already wired to the Prometheus
datasource) stayed empty because nothing produced `traces_service_graph_*`. This amendment enables
the Tempo **metrics-generator with only the `service-graphs` processor**, which survives 3.0 and runs
in-process under `-target=all` (the distributor pushes to it directly — no Kafka).

- **Config shape** (config-verified with `tempo -config.verify`; the generator + service-graph
  metric names were also verified against a live 3.0.2 scrape): top-level `metrics_generator`
  (`registry`, `storage.path` + `remote_write`, `processor.service_graphs.histogram_buckets`) plus
  **scoped overrides only** — `overrides.defaults.metrics_generator.processors: ["service-graphs"]`
  (hyphenated in the override list, unlike the `service_graphs` config block; the legacy flat form
  makes 3.0 refuse to start under `yaml.UnmarshalStrict`).
- **Remote-write auth (owner decision).** Prometheus's web listener is basic-auth-protected, so the
  generator's `remote_write` reuses the **existing `grafana` web-auth user** rather than provisioning
  a dedicated one (no host secret change). Tempo runs with `-config.expand-env=true` and
  `${PROMETHEUS_WEB_PASSWORD}` is injected from the service env (the same value Grafana already uses).
  Prometheus gains `--web.enable-remote-write-receiver`.
- **Cardinality** is bounded by a trimmed 8-bucket histogram + `max_active_series: 15000`; the graph
  is tiny (~4 services), so `filter_policies` are omitted for now. `TempoGeneratorSeriesLimited`
  alerts if the cap is ever hit; `TempoGeneratorRemoteWriteFailing` alerts if the shared credential
  drifts.
- **Span-metrics remote_write stays a non-goal**: the server-side `http_server_requests` histograms
  back the latency alerts and the RED dashboard (`14-tracing.json`), so no new Prometheus series are
  introduced (see the 2026-07-10 amendment below for why the RED panels are Prometheus- rather than
  TraceQL-metrics-backed).
- **Memory:** the 512M cap is unchanged; the generator's embedded agent adds pressure, and a raise (if
  needed) stays tracked under #937, not a revert. `tempo.yaml` needs a container **restart** (not a
  SIGHUP) to pick up the generator.

## Amendment 2026-07-10 — the RED dashboard runs on Prometheus, not TraceQL metrics

The `14-tracing.json` RED panels (request rate / p95 / 5xx by route) were originally authored as
**TraceQL-metrics** queries (`{ kind = server } | rate() by (span:name)`, `quantile_over_time`)
against the Tempo datasource. In prod they rendered a query error + "No data" while the two TraceQL
*search* tables (`{ duration > 1s }`, `{ status = error }`) worked. Root cause: TraceQL metrics are
served by the metrics-generator's **`local-blocks` processor**, and only `service-graphs` is enabled
(the 22a amendment) — so nothing produced the metrics blocks those panels read. The premise "TraceQL
metrics read RF1 blocks directly" was wrong: `local-blocks` (with `flush_to_storage`) is what *writes*
those blocks.

Rather than enable `local-blocks`, we **rebuilt the three panels on the existing
`http_server_requests_seconds` histograms** (Prometheus datasource, grouped `by (application, uri)`;
5xx via `status=~"5.."`, matching the `Http5xxRateHigh` alert). Rationale, consistent with this ADR's
span-metrics-as-non-goal stance:

- **No memory cost.** `local-blocks` keeps recent spans in memory to serve metrics; the host already
  went 512M→1G for `service-graphs` alone and has an OOM-flap history. This adds zero Tempo memory,
  needs no restart, and requires no further raise under #937.
- **Zero new series** — the histograms already exist (Spring-Boot default; `percentiles-histogram`
  on for `http.server.requests` in backend/frontend/ingest, and Keycloak exports them under
  `job="keycloak"`), and are already used by `03-spring-apps.json`.
- **Alert-consistent & robust** — same series the latency/5xx alerts fire on; exact counters, full
  Prometheus retention, no RF1/last-30s caveat and no `query_frontend.metrics.max_duration` 3h vs 6h
  clash.

**Accepted loss:** the "Error rate" panel now counts HTTP 5xx only, not the broader OTel span-error
status (a failed DB/outbound span that didn't surface as 5xx) — but that broader view stays visible in
the **Error traces** Tempo table. `local-blocks` remains the option if per-span-level RED
(DB/outbound/ad-hoc grouping, client-perceived latency) is ever wanted, at the documented memory cost.

## Amendment 2026-07-12 — the live-store needs a graceful-shutdown window (`stop_grace_period: 45s`)

The 3.x rewrite moved the write path into the **live-store**, which holds recent traces in memory and,
on SIGTERM, flushes them to its local WAL and finishes cutting in-flight blocks as part of Tempo's
dskit graceful shutdown (`server.graceful_shutdown_timeout`, default **30s**; Tempo's shutdown
"always takes at least 30 seconds", grafana/tempo#2353). The monitoring compose set **no**
`stop_grace_period`, so Docker's **10s** default applied — a `docker compose up -d --force-recreate
tempo` (deploy.sh's routine way to apply a `tempo.yaml` change past the inode-pinned single-file bind
mount) **SIGKILLed Tempo ~20s into its 30s drain**, truncating the live-store WAL mid-flush. On the
next start the partial block fails to complete/flush, incrementing
`tempo_live_store_failed_completions_total` / `tempo_live_store_local_failed_flushes_total` and firing
**`TempoWritePathFailing`** ("accepted traces lost before a persisted block") until Tempo discards the
bad block and self-heals — a self-resolving warning with **no** `ContainerOomKilled` / `HostDisk*`
co-alert. Observed in prod on 2026-07-12, right after the remote_write-401 fix force-recreated Tempo.

**Fix:** `tempo` gets an explicit `stop_grace_period: 45s` (docker-compose.monitoring.yml) — comfortably
above the 30s internal drain with disk-flush headroom, and (because Docker stops the instant the
process exits) a ceiling rather than a fixed wait, so routine force-recreates cost no extra time.
This mirrors the app stack's existing explicit grace periods (docker-compose.yml: DBs 60s, JVM apps
30s); the monitoring plane simply never carried them. No `live_store:` config block is added — its WAL
already defaults under the mounted `/var/tempo/live-store/traces`, and the corruption window was the
truncated shutdown, not the path. Loki (the other Grafana dskit store on the same 10s default) shares
the pattern; left as a scoped follow-up since it did not alert.

