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
  back the latency alerts, and the RED dashboard (`14-tracing.json`) uses TraceQL metrics on the
  Tempo datasource directly (zero new Prometheus series).
- **Memory:** the 512M cap is unchanged; the generator's embedded agent adds pressure, and a raise (if
  needed) stays tracked under #937, not a revert. `tempo.yaml` needs a container **restart** (not a
  SIGHUP) to pick up the generator.

