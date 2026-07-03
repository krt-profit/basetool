> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-03.
> **Owner area:** OBS · **Related:** [`security-and-access.md`](security-and-access.md), [`org-unit-tenancy.md`](org-unit-tenancy.md), [ADR-0072](../adr/0072-monitoring-stack-prometheus-grafana.md), monitoring epic [#936](https://github.com/krt-profit/basetool/issues/936)

# Observability & logging

## Context & goal

Every request is traceable end-to-end across all three modules (backend, frontend, ingest)
via shared MDC fields, with machine-parseable JSON logs in prod — and never any PII in the
logs. The monitoring stack (Prometheus/Grafana/Loki/Tempo/Alloy, epic
[#936](https://github.com/krt-profit/basetool/issues/936), ADR-0072) builds on these
guarantees; REQ-OBS-005 onward record its binding rules.

## Requirements

### REQ-OBS-001 — Access log + MDC enrichment

Both modules emit one access-log line per request and enrich every log line with MDC fields
`correlationId`, `userId`, and `orgUnitId` (the last per
[`org-unit-tenancy.md`](org-unit-tenancy.md) REQ-ORG-007). Logback patterns must include
`%X{orgUnitId}` to keep audit trails intact.

### REQ-OBS-002 — Correlation-id propagation

`correlationId` comes from the inbound `X-Correlation-Id` header (configurable via
`APP_LOGGING_CORRELATION_ID_HEADER`) or a generated UUID, and is echoed in the response
header. The frontend's `WebClientLoggingFilter` propagates the same id to outbound backend
calls so both modules share one id per user interaction. `userId` is the JWT `sub`, or
`anonymous`.

Errors raised **before** `CorrelationIdFilter` runs — the rate-limit 429, the pending-approval 403,
and the Spring Security filter-level 401/403 — mint their own `correlationId`, put it in the MDC (so
the problem body and the WARN log line share it), and echo it as the `X-Correlation-Id` response
header themselves, because that filter never runs to echo it on a short-circuited request. Every
error response therefore carries the header, not just the ones that reach the servlet. See
[`api-conventions.md`](api-conventions.md) REQ-API-004 for the full producer list.

### REQ-OBS-003 — Prod JSON appender

In `prod`, a PII-masking `LogstashEncoder` JSON appender writes `logs/{backend,frontend}.json`;
errors split into `*-error.log` for fast triage. Configurable via `APP_LOGGING_*` env vars.
The ingest gateway emits its prod JSON to stdout through the same `PiiMaskingLogstashEncoder`
pattern (no file sink — its stream is collected via the Docker log API).

### REQ-OBS-004 — Never log PII

**Never log names, emails, or tokens.** This is unconditional and applies to every log
level and all three modules. "Names" includes the Keycloak `preferred_username` / callsign handle:
the `PiiMasker` only scrubs JWTs, e-mail-shaped strings and token keywords, so a bare handle
would reach the appenders verbatim — log the user's `sub` UUID instead (the row id is in the
same UUID space and is not PII).

### REQ-OBS-005 — Prometheus metrics endpoint, fail-closed

All three modules expose Micrometer metrics at `GET /actuator/prometheus` for the monitoring
scrape (epic #936, ADR-0072). The endpoint is **never public**:

- Each module guards exactly this path with a **dedicated `SecurityFilterChain`**
  (`MonitoringScrapeSecurityConfig`, ordered before the main chain) using HTTP basic auth
  against a single in-memory scrape identity fed by the `MONITORING_SCRAPE_USER` /
  `MONITORING_SCRAPE_PASSWORD` environment variables (BCrypt-hashed at startup).
- **Fail-closed:** with either variable unset/blank the chain is built with `denyAll()` —
  there is no unauthenticated fallback. Dev/test/e2e and a prod host without the monitoring
  stack therefore expose nothing.
- Only the scrape identity counts: a valid Keycloak JWT (backend/ingest) or a logged-in
  browser session (frontend) must **not** grant access to the metrics payload.
- The scrape chain is stateless (no session, no CSRF token, no request cache) so a 30-second
  scrape interval creates no session state; a scrape response never carries `Set-Cookie`.
- `/actuator/health` remains public for the Docker `HEALTHCHECK` — unchanged by this
  requirement. The frontend `BotProtectionFilter` whitelists `/actuator/health` (incl. its
  liveness/readiness sub-paths, case-insensitively — pre-existing behaviour) and
  `/actuator/prometheus` as an **exact, case-sensitive match only**, mirroring the scrape
  chain's `securityMatcher`; prometheus sub-paths/case variants and every other
  `/actuator/**` path stay blocked with 404 before the security chains run.
- **Prod precondition for setting the credentials:** the NPM `/actuator` deny rules on both
  public hosts (`profit-base.online`, `ingest.profit-base.online`) are applied **before**
  `MONITORING_SCRAPE_*` is deployed (Phase-2 runbook), so the credentialed endpoint is only
  reachable from the internal scrape network. This is also the compensating control for the
  per-request BCrypt cost of basic auth — without the edge deny, an internet client could
  drive unthrottled credential guesses / CPU load against the endpoint (residual-risk record
  in ADR-0072).
- Every meter carries the common tag `application=basetool-{backend,frontend,ingest}` so
  dashboards can select the module.

### REQ-OBS-006 — No PII and no unbounded labels in metrics

Metric names, label keys, label values and measured values must never contain
user-identifying data (usernames, e-mails, `sub` UUIDs, IPs, tokens) or free text. Labels
must come from **bounded, enumerable sets** (status enums, task names, HTTP status classes,
cache names); per-user, per-entity-id or otherwise unbounded label values are forbidden —
both as a privacy rule (metrics have 180-day retention) and as a cardinality guard for the
Prometheus TSDB. This applies to every meter exposed on `/actuator/prometheus`, including
the future `basetool_*` business metrics (epic #936 Phase 1c).

### REQ-OBS-007 — Log ingestion into the monitoring plane (per-stream rules)

When log streams are shipped to Loki (epic #936 Phase 2), each stream obeys its own recorded
rule — no blanket "everything is masked" claim:

- **backend / frontend / ingest JSON** — PII-masked **at the source** (REQ-OBS-003/-004);
  the shipper adds no further masking.
- **Keycloak file log** — masked **in the shipper** (Alloy stages scrub `username=` /
  `ipAddress=` before ingestion).
- **NPM access logs, NPM admin-UI stdout, and SSH/host-auth logs** — ingested **including
  client IPs and usernames** at a **31-day retention**. This is a deliberate,
  owner-approved data-protection decision (2026-07-02) for security monitoring and abuse
  detection; it is conditioned on the privacy-policy extension (`privacy.html` + DE/EN
  bundles) that ships with the Phase-2 PR, and Loki is deliberately excluded from backups so
  the GFS retention cannot silently extend those 31 days (ADR-0072).
- **PostgreSQL container logs** — ingested with `log_error_verbosity=terse` so `DETAIL`
  lines cannot leak row data.
- Loki labels stay low-cardinality (`app`, `level`, bounded `host`); log lines are never
  turned into per-user labels.

### REQ-OBS-008 — Monitoring plane: admin-only access, isolated cleartext carve-out

- **Admin-only UIs:** Grafana is the **only** monitoring UI, published via NPM and
  authenticated through Keycloak OIDC restricted to the realm role `Admin`
  (`ROLES_AND_PERMISSIONS.md`). No other monitoring component (Prometheus, Alertmanager,
  Loki, Tempo, exporters) exposes a host port or a public route; their APIs are reachable
  only inside the isolated monitoring Docker networks.
- **Transport:** all app/Keycloak/NPM edges stay HTTPS — Prometheus scrapes the three
  modules over HTTPS validating the pinned public certificate (no `insecure_skip_verify`).
  **Inside** the isolated monitoring networks (Prometheus→exporters, Grafana→datasources,
  Alloy→Loki/Tempo, apps→Alloy OTLP span push, →`keycloak:9000`) traffic is deliberately
  plain HTTP. This carve-out is
  owner-approved (2026-07-02, epic #936) and amends the HTTPS-only posture; the REQ-SEC-014
  wording in [`security-and-access.md`](security-and-access.md) is amended in the Phase-2 PR
  that actually creates those networks. Rationale and residual risk live in ADR-0072.
- The private key of the shared `keystore.p12` never leaves the four existing services;
  Grafana gets its own self-signed certificate.

### REQ-OBS-009 — Distributed tracing (OTLP via the monitoring plane only)

All three modules ship OpenTelemetry tracing (Boot's OpenTelemetry starter, Micrometer
Tracing on the OTel SDK) behind a hard master gate:

- **Inert by default:** `MONITORING_TRACING_ENABLED` (default `false`) drives BOTH Boot 4
  gates — `management.opentelemetry.enabled` (no SDK tracer provider, no span processor) and
  `management.tracing.export.otlp.enabled` (the OTLP exporter bean is not even instantiated).
  Disabled therefore means: no span is ever recorded to or exported anywhere, no OTLP
  connection attempts, no exporter errors — dev/test/e2e and a prod host without the
  monitoring stack are unaffected. (Boot's ungated bridge fallback may still mint in-process
  span contexts; they are attached to no processor/exporter and vanish on end. The per-request
  ids this puts into the MDC are dangling but harmless — the `correlationId` remains the
  primary correlation key.) Boot 4 note, verified against the 4.1.0 module bytecode: these two
  flags are the ones the auto-configuration actually honours; the legacy
  `management.tracing.enabled` is consumed by nothing, and an endpoint-only gate is likewise
  not possible — with the starter on the classpath, tracing would otherwise be active by
  default.
- **Export path:** spans go via OTLP/HTTP (`MONITORING_OTLP_ENDPOINT`, Phase 2:
  `http://alloy:4318/v1/traces` on the scrape network) to Alloy, which forwards to Tempo on
  the core network — apps never reach the trace store directly. Sampling probability comes
  from `MONITORING_TRACING_SAMPLING_PROBABILITY` (default 1.0; revisited in Phase-3 tuning).
- **No user-identifying span data:** span names and the low-cardinality `uri` attribute use
  templated routes (`/api/v1/locations/{id}`). Each module's `ObservationPrivacyFilter`
  scrubs every URL-carrying observation key-value before it becomes a metric tag or span
  attribute: query strings (user search text!) are always cut, and on the metric-facing
  `uri` tag UUID/numeric path segments are collapsed to `{id}` (cardinality guard for
  hand-assembled client URIs). The `http.url` attribute thus carries the query-stripped raw
  path — entity ids at most; attributes must never carry usernames, e-mails, `sub` UUIDs,
  IPs or tokens (mirror of REQ-OBS-006).
- **Logs↔traces:** while a span is active, `traceId`/`spanId` join the MDC and are emitted
  by the prod JSON appenders as first-class fields for Grafana's derived-field links. The
  correlation-id system (REQ-OBS-001/-002) is untouched — `traceId` is an additional field,
  not a replacement.
- The trace service identity is pinned to the module's `application` metric tag
  (`basetool-{backend,frontend,ingest}`); hand-built `WebClient`s are explicitly wired to
  the observation registry (Boot's customizer only covers the auto-configured builder).
  The frontend's SSE relay client is deliberately not observed (a ~30-minute stream would
  hold one span open for its whole lifetime).
- Trace retention is short (14 days, Tempo, Phase 2) and access is admin-only via Grafana
  (REQ-OBS-008).

### REQ-OBS-010 — NPM access-log IP retention (reserved, Phase 2/3)

Reserved for the deliberate, owner-approved decision to ingest the NPM edge access logs
(client IPs, 31-day retention) as a documented data-protection trade-off (epic
[#936](https://github.com/krt-profit/basetool/issues/936), ADR-0072). Not implemented in the
Phase-1 app instrumentation; the requirement is recorded here so the REQ-OBS numbering stays
continuous and the decision has a home before the Phase-2 stack rollout.

### REQ-OBS-011 — Business metrics (`basetool_*`)

The three modules expose custom `basetool_*` business metrics on existing choke points so
operations can alarm on regressions, security signals and work-queue backlog without scraping
logs. All of them obey REQ-OBS-006: **only bounded, enumerable labels** (application enums, a
fixed local literal set) — never a username, `sub`, IP, id, path, URI or amount — and bank
figures are exposed as **counts only**, never balances or transaction amounts.

**Mechanics.** Meter names live in a per-module `metrics.MetricNames` constants holder (the
single source of truth for names, tag keys and the non-enum label values). The backend
`metrics` package is a dependency leaf (Micrometer only) so `service` / `task` / `filter` /
`exception` reuse it without a package cycle (ADR-0047). Scheduled-job health flows through the
shared `metrics.TaskMetrics` wrapper; queue depth is sampled by the `task.BusinessMetricsCollector`
on a fixed timer (`app.monitoring.business-metrics.interval-ms`, default 60 s, one read-only
transaction per pass) rather than per-scrape.

**Backend.**

- `basetool_scheduled_job_executions_total{job,outcome}` counter,
  `basetool_scheduled_job_duration_seconds{job}` timer and
  `basetool_scheduled_job_last_success_timestamp_seconds{job}` gauge for the six batch jobs
  (`user_sync`, `notification_retention`, `default_blueprint_provisioning`,
  `bank_ledger_integrity`, `uex_sync`, `scwiki_sync`) via `TaskMetrics`. The last-success gauge
  is the source of the "user sync stale > 15 min" alert (`job="user_sync"`); it is registered
  lazily so a config-gated-off job never reports a falsely-stale `0`.
- `basetool_http_error_total{code}` counter at the `GlobalExceptionHandler` 409/401/403 methods
  (`OPTIMISTIC_LOCK` = optimistic-locking regression indicator, `PESSIMISTIC_LOCK`,
  `UNAUTHENTICATED`, `ACCESS_DENIED`). Filter-level 401/403 re-dispatch through the same advice,
  so there is exactly one non-double-counted increment site per status.
- `basetool_audit_events_total{domain}` counter at the single `AuditService.record` choke point
  (`domain` = the 8 `AuditDomain` values; the physically separate Bank audit trail is out of
  scope here).
- `basetool_ratelimit_rejections_total{bucket}` counter at the `RateLimitingFilter` reject branch
  (`bucket` = the rule name, or `global` for the umbrella `/api/**` budget).
- `basetool_bank_ledger_integrity_violations{category}` gauge fed by the hourly integrity sweep
  (six `category` values; **any value > 0 is CRITICAL** — the ledger broke an invariant).
- Queue-depth gauges (`BusinessMetricsCollector`): `basetool_registration_pending_count` +
  `_oldest_age_seconds`, `basetool_bank_booking_request_pending_count` + `_oldest_age_seconds`,
  and `{status}`-labelled `basetool_job_order_open_count` / `basetool_operation_open_count` /
  `basetool_refinery_order_open_count` / `basetool_p4k_import_job_pending_count` each with an
  `_oldest_age_seconds` companion. The oldest-age gauges drive the "oldest pending > 48 h" style
  alerts; an empty queue reports `0`.

**Frontend.** `basetool_mission_presence_missions` gauge (missions with a live editor; single-JVM
edit-awareness, unlabelled), `basetool_active_sessions` gauge (active Spring Session sessions;
`@Profile("!test")`), and `basetool_backend_client_errors_total{reason,method}` counter at the
`BackendApiClient` failure funnels. `reason` is a fixed **local** enumeration
(`backend_4xx`/`backend_5xx`/`circuit_open`/`bulkhead_full`/`timeout`/`unknown`) derived from the
failure branch — never the backend's response-body code, which could be arbitrary — and `method`
is the HTTP verb.

**Ingest.** `basetool_ingest_handoff_total{kind}` (accepted+staged handoffs per `HandoffKind`),
`basetool_ingest_handoff_errors_total{reason}` (relay failures: `backend_reject` /
`backend_unavailable` / `internal`; pre-relay rejections are not counted here), and
`basetool_ratelimit_rejections_total{bucket}` (`bucket` = `ip` / `subject`; shares the metric name
with the backend counter, the `application` common tag separating the modules).

**Deliberately excluded** (documented so the gap is intentional, not an oversight): notifications
(no org-wide queue — only per-recipient unread, which is PII-adjacent), org units (no lifecycle
status) and missions (a free-text `status` column, not a bounded enum, so it cannot back a
bounded label). Bank amounts and per-user breakdowns are out of scope by rule.

**Metrics move with the code.** Every change that adds, renames or removes one of these surfaces
updates its metric in the same change — a new scheduled job without `TaskMetrics`, a new bounded
status queue without a gauge, or a renamed metric that silently breaks a dashboard/alert is
incomplete (epic [#936](https://github.com/krt-profit/basetool/issues/936); the binding
"monitoring moves with every feature" rule ships with the Phase-2 stack).

