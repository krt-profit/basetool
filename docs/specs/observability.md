> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-06.
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

A relayed backend failure is logged **exactly once, at the level its status warrants.** The
frontend's `BackendApiClient` boundary logs every backend error once — a 5xx server fault at
`ERROR`, a 4xx client error (validation `400`, conflict `409`, …) at `WARN` — with method, URI,
stable `code`, correlationId and detail. Frontend page/write controllers therefore relay the error
to the caller (`propagateBackendError`) **without re-logging it at `ERROR`**; an expected client 4xx
must never reach `ERROR`, or it inflates `logback_events_total{level="error"}` and trips the
`LogbackErrorSpike` alert on normal user-input mistakes. Only a genuinely unexpected failure (a bare
`catch (Exception …)`, not a mapped `BackendServiceException`) logs at `ERROR`.

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
  in ADR-0072). The deny returns HTTP **404** (not 403) so it does not reveal that the
  Actuator endpoints exist behind the edge; any future blackbox probe asserting the deny
  must expect 404.
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
- **NPM access logs, SSH/host-auth logs, and the host security logs
  (auditd `sshd_config`/`authorized_keys` tamper watches, fail2ban SSH-jail bans)** — ingested **including
  client IPs and usernames** at a **31-day retention**. This is a deliberate,
  owner-approved data-protection decision (2026-07-02) for security monitoring and abuse
  detection; it is conditioned on the privacy-policy extension (`privacy.html` + DE/EN
  bundles) that ships with the Phase-2 PR, and Loki is deliberately excluded from backups so
  the GFS retention cannot silently extend those 31 days (ADR-0072).
- **NPM container stdout** (`app="npm"`) — operational logs only (nginx reloads, cert
  renewals, `[emerg]`/`[error]`); **no client IPs or usernames**, so no shipper masking and
  not part of the 31-day IP-retention set. Surfaced on the SSH/host-auth dashboard's *NPM
  errors & warnings* panel. Admin-UI login monitoring was originally planned on this stream,
  but nginx-proxy-manager does not log admin logins to stdout and the admin UI is
  loopback-only — descoped as an accepted gap (REQ-OBS-010).
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
- **Internal-cert expiry is monitored.** The self-signed internal certs — the basetool-CA-signed
  `keystore.p12` on the app modules and Grafana's own cert — are probed from inside the monitoring
  plane by the blackbox `https_internal` / `https_internal_insecure` modules (the CA is mounted into
  the blackbox exporter; Grafana uses the `insecure_skip_verify` variant since its cert is not
  CA-signed, and `probe_ssl_earliest_cert_expiry` is still emitted). Their expiry gauge feeds the
  unfiltered `CertificateExpiringSoon` alert so an internal-cert expiry — which would otherwise break
  all three app scrapes, the frontend→backend WebClient and the NPM→Grafana re-encryption at once —
  is caught ~14 days ahead instead of only by a same-day `TargetDown`. These probe jobs stay outside
  `BlackboxProbeFailed`'s liveness include-list (a down app is already paged by `TargetDown`), so they
  add no double-paging. Enforced by `monitoring/blackbox/blackbox.yml`,
  `monitoring/prometheus/prometheus.yml` (`blackbox-internal-tls*` jobs) and the blackbox CA mount in
  `docker-compose.monitoring.yml`.

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

### REQ-OBS-010 — Edge / host-auth log streams: 31-day IP retention + privacy-policy linkage

The monitoring plane ingests four log streams **including client IPs / usernames** at a
**31-day retention** — a deliberate, owner-approved data-protection trade-off (2026-07-02, epic
[#936](https://github.com/krt-profit/basetool/issues/936), ADR-0072) for security monitoring and
abuse detection:

- **NPM edge access logs** — all public proxy hosts, client IPs (edge 4xx/5xx rates, scan/probe
  detection, per-host traffic).
- **SSH / host-auth logs** — `/var/log/auth.log` (or the journal per distro): failed-auth spikes,
  invalid users, sudo failures, successful root logins, and a successful password/keyboard-interactive
  login on a key-only host.
- **Host auditd log** — `/var/log/audit/audit.log`: file-integrity watch events on `sshd_config`(.d)
  and root `authorized_keys` (the acting `auid` + path; catches a smuggled key or a security
  downgrade). Ingested unmasked so the acting user stays attributable.
- **Host fail2ban log** — `/var/log/fail2ban.log`: SSH-jail ban/unban events carrying the offending
  client IP — the active-blocking complement to the `SshFailedAuthSpike` detection.

An NPM admin-UI login stream was originally planned as a fifth stream but was **descoped**:
nginx-proxy-manager does not log admin logins to its container stdout, and the admin UI is
loopback-only. Its stdout is still ingested — as **PII-free operational logging** (REQ-OBS-007),
not an IP-bearing stream — and surfaced on the *NPM errors & warnings* dashboard panel.

Binding conditions on this retention:

- **31 days, then automatic deletion**, enforced by the Loki compactor (`retention_period: 744h`);
  Loki is **deliberately excluded from backups** so restic's GFS retention cannot silently extend the
  31 days (ADR-0072).
- **Admin-only access** — these streams are readable only through Grafana behind Keycloak OIDC
  restricted to the realm role `Admin` (REQ-OBS-008).
- **Privacy-policy linkage (mandatory):** the decision is conditioned on the privacy policy covering
  the temporary IP storage. `frontend/src/main/resources/templates/privacy.html` §3.8 plus the
  `privacy.h2_3_8` / `privacy.p_3_8_1` / `privacy.p_3_8_2` keys in the DE/EN bundles document the
  streams, the 31-day retention, the purpose (security monitoring / abuse detection) and the
  admin-only access. A change that widens these streams or their retention must update the privacy
  policy in the same PR.
- Keycloak's own file log is masked in the shipper (`username=` / `ipAddress=`, REQ-OBS-007); the
  IP-bearing streams above are the app/edge/host layers, not the Keycloak file log.

The metrics/dashboards derived from these streams carry **no** per-user labels (REQ-OBS-006) — only
aggregated counts and bounded `app`/`host` labels.

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
  `basetool_scheduled_job_duration_seconds{job}` timer,
  `basetool_scheduled_job_last_success_timestamp_seconds{job}` gauge and — for the jobs that
  process a countable batch — `basetool_scheduled_job_items_total{job}` counter for the seven
  wrapped jobs (`user_sync`, `notification_retention`, `default_blueprint_provisioning`,
  `bank_ledger_integrity`, `uex_sync`, `scwiki_sync`, `business_metrics`) via `TaskMetrics` (`record`
  / `recordCounting`). The `business_metrics` job wraps `BusinessMetricsCollector.refresh()` (the 60s
  queue-depth sampler) so a wedged sampler surfaces via its frozen last-success (`BusinessMetricsStale`)
  instead of silently freezing every queue gauge under the `*ApprovalOverdue` alerts (#1041 item 3).
  The scheduled-job timer publishes latency histogram buckets (bounded 10ms..600s, `job` label only)
  so the operations dashboard charts per-job p95 duration. The last-success gauge is the source of the
  staleness alerts — `UserSyncStale` (`user_sync`, > 15 min), `ExternalSyncStale` (the catalogue syncs,

  > 48 h), `ScheduledJobStale` (`notification_retention` / `default_blueprint_provisioning`, > 26 h),
  > `BankLedgerIntegritySweepStale` (`bank_ledger_integrity`, > 6 h, **critical** — while stale the
  > violations gauge freezes and `BankLedgerIntegrityViolation` cannot fire) and `BusinessMetricsStale`
  > (`business_metrics`, > 10 min); it is registered lazily so a config-gated-off job never reports a
  > falsely-stale `0`. The items counter is present only for jobs that report a count: user sync,
  > notification retention, default-blueprint provisioning, and — since #1041 item 2 — `uex_sync` (the
  > `UexItemSyncService` `game_item` upsert tally) and `scwiki_sync` (the sum of the five SC-Wiki step
  > counts, a failing step contributing `0`). For the two catalogue syncs it is populated from the same
  > per-run tallies the sync-report summary uses and backs the `SyncZeroItems` alert, which fires when
  > a sync keeps succeeding but has processed zero rows for 48 h — the empty-200 catalogue outage that
  > neither `ExternalSyncStale` (last-success stays fresh) nor `ExternalFetchErrors` (an empty 200 is
  > not a fetch error) catches. The same success-with-zero-work idea backs `UserSyncZeroItems`
  > (`user_sync` synced zero users for 30 min while successful runs happened — Keycloak returned an
  > empty roster; #1041 item 3).

- `basetool_sync_events_total{source,event_type}` counter at the three `SyncReportService`
  `log*Event` write sites (`source` = `SyncSourceSystem`, `event_type` = `SyncEventType`; both
  bounded enums — never the external asset name/uuid/detail).
- `basetool_external_fetch_errors_total{source}` counter incremented where the `UexClient` /
  `ScWikiClient` swallow an upstream fetch or parse error into an empty result (`source` = the fixed
  literal `uex` / `scwiki`). Because every upstream failure is mapped to an empty list and the sync
  job still records a success, this is the only signal of a sustained catalogue outage; it backs the
  `ExternalFetchErrors` alert. The backend `WebClient.Builder` is wired to the `ObservationRegistry`
  (REQ-OBS-009) so these same calls also emit `http_client_requests_seconds` + client spans.
- Frontend→backend seam (#1041 item 11): the frontend enables the `http.client.requests`
  percentile-histogram (same bounded 5ms..10s window as `http.server.requests`, so both stay on the
  same ~14 buckets) to drive a client-p95-vs-server-p95 overlay that separates "backend slow" from
  "frontend slow". The already-exported resilience4j meters gain two leading-indicator alerts —
  `BulkheadNearSaturation` (< 5 free bulkhead slots for 10m) and `RetryRateElevated`
  (`successful_with_retry` > 0.2/s for 10m) — that fire before `circuit_open` / `bulkhead_full`, i.e.
  before users fail; plus a backend-call resilience row on `03-spring-apps.json` and a
  frontend-usage row (`basetool_active_sessions`, `basetool_mission_presence_missions`) on `07`.
- `basetool_http_error_total{code}` counter at the `GlobalExceptionHandler` 409/401/403 methods
  (`OPTIMISTIC_LOCK` = optimistic-locking regression indicator, `PESSIMISTIC_LOCK`,
  `UNAUTHENTICATED`, `ACCESS_DENIED`) plus `SERVICE_UNAVAILABLE`, incremented directly by
  `IdentityProviderUnavailableFilter` when an unreachable Keycloak JWKS is re-mapped to a retryable
  503 (REQ-SEC-024) — a filter-level site that bypasses the advice, so "one non-double-counted
  increment site" holds per code, not per handler. The ingest gateway emits the same metric name
  with the `SERVICE_UNAVAILABLE` code from its own filter; the `application` common tag
  distinguishes the module.
- `basetool_audit_events_total{domain}` counter at the single `AuditService.record` choke point
  (`domain` = the 8 `AuditDomain` values). Silence detection is two-tier: `AuditSilenceAnomaly`
  (no audited mutation anywhere for 5 d while the backend is up) plus, since #1041 item 10,
  `AuditDomainSilenceAnomaly` (a single domain silent for 14 d while others stay active — the
  domain-lost-its-wiring failure mode the global sum masks; `PROMOTION` / `PERSONAL_INVENTORY` are
  excluded as legitimately-quiet and reviewed on the operations dashboard's per-domain table
  instead).
- `basetool_bank_audit_events_total{event_type}` counter at the single `BankAuditService.record`
  choke point (`event_type` = the bounded `BankAuditEventType` enum). The bank keeps a physically
  separate `bank_audit_event` table excluded from `AuditDomain`, so before #1041 item 10 the most
  sensitive audited area had **zero** volume signal; this counter is that signal — **counts only,
  never amounts, account numbers or holder identities** (REQ-OBS-006). It backs
  `BankAuditSilenceAnomaly` (the bank analogue of `AuditSilenceAnomaly`) and a bank-volume panel on
  the operations dashboard.
- `basetool_ratelimit_rejections_total{bucket}` counter at the `RateLimitingFilter` reject branch
  (`bucket` = the rule name, or `global` for the umbrella `/api/**` budget), paired since #1041
  item 19 with `basetool_ratelimit_requests_total{bucket}` bumped on **every** bucket evaluation, so
  rejections/requests is a rejection ratio (`RateLimitRejectionRatioHigh`) rather than 429-only
  detection.
- `basetool_discord_precheck_total{outcome}` counter (`DiscordAccountExistenceController`, #1041
  item 19; `outcome` = `ok` / `unauthorized` / `disabled`). The endpoint sits outside `/api/**`, the
  rate limiter and the `basetool_http_error` funnel, so this is the only signal for secret-guessing
  (`DiscordPrecheckUnauthorizedSpike`) or a blank-secret config drift after a rotation
  (`DiscordPrecheckDisabledOnProd`); no PII, only the coarse outcome.
- `basetool_bank_ledger_integrity_violations{category}` gauge fed by the hourly integrity sweep
  (six `category` values; **any value > 0 is CRITICAL** — the ledger broke an invariant).
- Queue-depth gauges (`BusinessMetricsCollector`): `basetool_registration_pending_count` +
  `_oldest_age_seconds`, `basetool_bank_booking_request_pending_count` + `_oldest_age_seconds`,
  and `{status}`-labelled `basetool_job_order_open_count` / `basetool_operation_open_count` /
  `basetool_refinery_order_open_count` / `basetool_p4k_import_job_pending_count` each with an
  `_oldest_age_seconds` companion. Every oldest-age gauge now drives a stuck-queue alert: the
  registration + bank pairs the "oldest pending > 48 h" `*ApprovalOverdue` alerts, and (since #1041
  item 15) the four work queues `P4kImportStuck` (> 6 h — imports finish in minutes), `JobOrderStale`
  / `RefineryOrderStale` / `OperationStale` (> 30 d, baseline-tune). An empty queue reports `0`, so
  there is no `absent()` ambiguity.
- `basetool_p4k_import_jobs_total{outcome,kind}` counter (`P4kImportJobService`, #1041 item 15),
  bumped at each terminal transition — `outcome` = `succeeded` / `failed` (the lowercased terminal
  status, including a restart orphan-fail), `kind` = `PREVIEW` / `APPLY`. It makes a reliably-failing
  import observable (`P4kImportFailed`): the pending-queue gauge drains back to `0` after a failed
  run, so an empty queue alone cannot distinguish "nothing to do" from "every run fails".
- `basetool_mail_total{outcome}` counter (`SmtpMailService`, #1041 item 16), one bounded `outcome`
  per delivery path — `sent`, `failed` (swallowed `MailException`), and the three config-gate drops
  `dropped_disabled` / `dropped_no_host` / `dropped_no_sender`; never the recipient or subject
  (PII). `MailDeliveryFailing` fires on `failed` > 2/h; `MailDroppedConfigDrift` fires on any
  `dropped_*` (on the configured prod deployment a drop is a config-drift regression that silently
  swallows registration / approval mail, previously visible only via `LogbackErrorSpike`).
- `basetool_sse_connections` gauge + `basetool_sse_send_failures_total{event}` counter
  (`NotificationStreamService`, #1041 item 17). The gauge sums the live SSE subscriber count across
  all recipients (unlabelled — `sub` is PII); the counter is bumped at each drop-on-send-failure
  branch with a fixed `event` (`connected` / `notification` / `heartbeat`). Zero connections while
  the frontend still reports active sessions drives `SsePushChannelDead` (a dead push channel, e.g.
  reverse-proxy buffering drift).

**Frontend.** `basetool_mission_presence_missions` gauge (missions with a live editor; single-JVM
edit-awareness, unlabelled), `basetool_active_sessions` gauge (active Spring Session sessions;
`@Profile("!test")`), and `basetool_backend_client_errors_total{reason,method}` counter at the
`BackendApiClient` failure funnels. `reason` is a fixed **local** enumeration
(`backend_4xx`/`backend_5xx`/`circuit_open`/`bulkhead_full`/`timeout`/`unknown`) derived from the
failure branch — never the backend's response-body code, which could be arbitrary — and `method`
is the HTTP verb. The push-channel surfaces (#1041 item 17) add `basetool_notification_relay_connections`
(open browser→backend notification SSE relays, `NotificationPageController`) and
`basetool_presence_ws_sessions` (live mission-presence WebSocket sessions summed across missions,
`MissionPresenceWebSocketHandler`) gauges, plus the `basetool_presence_relay_frames_total{type}`
(`changed` / `snapshot`) and `basetool_presence_relay_dropped_total{reason}` (`throttled` /
`send_failed`) counters at the previously-silent throttle and send-failure branches of the presence
relay — the component that shipped the REQ-FE-010 staleness defect. A `changed`-frame flatline while
`snapshot` frames keep flowing is the early indicator for that defect class (panels only, baselined
before alerting). All labels are fixed literals, pure counts.

The auth surfaces (#1041 item 18) add `basetool_login_total{outcome,reason}` (`SecurityConfig`'s
OAuth2 success/failure handlers: `outcome` = `success` / `failure`; on failure `reason` =
`invalid_state` / `provider_error` / `other`, **mapped from the exception type and bounded OAuth2
error code — never the raw error description**; on success `reason` = `none`) and the unlabelled
`basetool_csrf_rejections_total` (a custom `AccessDeniedHandler` counts CSRF-token rejections before
the 403). They drive `FrontendLoginBroken` (failures with zero concurrent successes — the
code-to-token / JWKS / state break `KeycloakLoginErrorSpike`'s event regex misses) and
`CsrfRejectionSpike` (a systematic CSRF-wiring regression that `krtFetch`'s silent single-retry
otherwise masks as intermittent failed writes). The pre-auth `BotProtectionFilter` adds
`basetool_bot_blocked_total{rule}` (#1041 item 19; `rule` = `method` / `path_prefix` /
`file_extension`) at its three reject branches, which were otherwise `log.debug`-only and
prod-invisible — the counter also surfaces a self-inflicted false positive when a new legit route
matches a blocked prefix. Panels only, all labels fixed literals.

**Ingest.** `basetool_ingest_handoff_total{kind}` (accepted+staged handoffs per `HandoffKind`),
`basetool_ingest_handoff_errors_total{reason}` (relay failures: `backend_reject` /
`backend_unavailable` / `internal`; pre-relay rejections are not counted here), and
`basetool_ratelimit_rejections_total{bucket}` (`bucket` = `ip` / `subject`; shares the metric name
with the backend counter, the `application` common tag separating the modules) — paired since #1041
item 19 with `basetool_ratelimit_requests_total{bucket}` on the per-IP filter and the per-subject
limiter, feeding the same `RateLimitRejectionRatioHigh` ratio alert.

**Deliberately excluded** (documented so the gap is intentional, not an oversight): notifications
(no org-wide queue — only per-recipient unread, which is PII-adjacent), org units (no lifecycle
status) and missions (a free-text `status` column, not a bounded enum, so it cannot back a
bounded label). Bank amounts and per-user breakdowns are out of scope by rule.

**Metrics move with the code.** Every change that adds, renames or removes one of these surfaces
updates its metric in the same change — a new scheduled job without `TaskMetrics`, a new bounded
status queue without a gauge, or a renamed metric that silently breaks a dashboard/alert is
incomplete (epic [#936](https://github.com/krt-profit/basetool/issues/936); the binding
"monitoring moves with every feature" rule ships with the Phase-2 stack).

**Alert coverage of these signals.** Previously-unalerted `basetool_*` signals now back named alerts
so an exported metric cannot silently regress unnoticed: the ingest handoff metrics feed
`IngestHandoffErrors` / `IngestBackendUnavailable`; the `basetool_http_error_total`
`SERVICE_UNAVAILABLE` and `ACCESS_DENIED` codes feed `IdentityProviderUnavailable` /
`AccessDeniedSpike` (and the all-codes HTTP-error panel on dashboard `07`); and
`KeycloakEventMetricsAbsent` guards the `keycloak_user_events_total` series that
`KeycloakLoginErrorSpike` depends on. Adding, renaming or removing one of these metrics keeps its
alert in `monitoring/prometheus/alerts/business.yml` in sync in the same change.

### REQ-OBS-012 — Edge posture assertions (deny / redirect / HSTS probes)

Security-relevant edge configuration lives only in the NPM admin database (per-host toggles and
Advanced snippets under `/var/iri/npm/data`), not in git — a UI misclick or a proxy-host recreate
could silently undo it. The monitoring plane therefore **asserts** that posture continuously
instead of trusting the one-time rollout verification:

- **`/actuator` edge deny** — the `blackbox-edge-deny` job probes `/actuator/prometheus` on both
  public app hosts with the `http_deny_404` module: probe success means the edge answers exactly
  404 (the live deny). If the block drifts, the request reaches the fail-closed app endpoint
  (401) and `EdgeActuatorDenyBroken` (critical) fires — the continuous version of the ADR-0072
  compensating control.
- **Force-SSL redirect** — the `blackbox-force-ssl` job probes plain-HTTP port 80 of all four
  public vhosts with `http_force_ssl_redirect` (301/308 + `Location: https://…`, redirects not
  followed); `EdgeForceSslRedirectBroken` (warning) fires on drift.
- **HSTS** — the `blackbox-hsts` job asserts `Strict-Transport-Security` on the **first**
  response of `https://profit-base.online` (app-side HSTS, security-audit finding H-9);
  `EdgeHstsHeaderMissing` (warning). Extended to the keycloak/grafana/ingest vhosts once their
  header posture is verified in the NPM UI.
- **Keycloak `/admin` allow-list** — asserted **externally** by the daily
  `.github/workflows/edge-deny-probe.yml` run. The internal blackbox exporter cannot carry this
  signal: its hairpinned probe traffic is SNAT'd to the Docker bridge gateways, which are exactly
  the allow-listed sources, so from inside the console always looks reachable. The workflow also
  re-asserts the `/actuator` 404 from outside.

The posture jobs are separate from the `blackbox-http` liveness job; `BlackboxProbeFailed` is
scoped to liveness, and every posture alert carries an `and on()` guard on the main-page probe so
a full edge outage pages once (liveness), not once per posture assertion.

**IPv6 + public-DNS reachability.** The public vhosts carry AAAA records (owner-confirmed 2026-07-06),
so the edge is also probed over IPv6 and for public DNS resolution: the `blackbox-http-ipv6` /
`blackbox-http-auth-ipv6` jobs re-run the liveness probes pinned to IPv6 (no v4 fallback), and the
`blackbox-dns-a` / `blackbox-dns-aaaa` jobs query a public resolver (1.1.1.1) for the apex's A and AAAA
records (NODATA fails the probe, not only NXDOMAIN). `EdgeIpv6Unreachable` (warning) fires only when a
vhost answers over IPv4 but not IPv6 (guarded `on(instance)` against the v4 probe, so a full outage
pages once via `BlackboxProbeFailed`); `DnsResolutionFailed` (warning) fires when the apex stops
resolving an A or AAAA record. These are reachability probes, not posture assertions — a v6-only or
DNS-only regression is invisible to the IPv4 liveness job.

**Acceptance**

- [ ] `EdgeActuatorDenyBroken` fires when a public app host stops answering 404 on
  `/actuator/prometheus` while the edge itself is up, and stays silent during a full edge outage.
- [ ] `EdgeForceSslRedirectBroken` fires when port 80 of a public vhost stops redirecting to
  `https://`; `EdgeHstsHeaderMissing` fires when the frontend's first response drops the header.
- [ ] The scheduled `edge-deny-probe` workflow fails when
  `https://keycloak.profit-base.online/admin/` answers 2xx/3xx from a GitHub runner or the
  `/actuator` paths stop answering 404 externally.

**Enforced by:** `monitoring/blackbox/blackbox.yml` (`http_deny_404` / `http_force_ssl_redirect` /
`http_2xx_hsts`; the `http_2xx_ipv6` / `http_2xx_or_401_ipv6` / `dns_apex_a` / `dns_apex_aaaa`
reachability modules) · `monitoring/prometheus/prometheus.yml` (the three posture jobs; the
`blackbox-http-ipv6` / `blackbox-http-auth-ipv6` / `blackbox-dns-a` / `blackbox-dns-aaaa` reachability
jobs) · `monitoring/prometheus/alerts/infrastructure.yml` (`EdgeActuatorDenyBroken`,
`EdgeForceSslRedirectBroken`, `EdgeHstsHeaderMissing`, `EdgeIpv6Unreachable`, `DnsResolutionFailed`,
scoped `BlackboxProbeFailed`) · `.github/workflows/edge-deny-probe.yml`

### REQ-OBS-013 — Telemetry-sink failures are not application errors

A failure to reach the observability plane must never look like an application fault. In
particular, the OpenTelemetry OTLP span exporter logs a failed export batch (Alloy/Tempo unreachable
or slow) at ERROR by default; that noise flows into `logback_events_total{level="error"}` and can
trip the `LogbackErrorSpike` alert on a monitoring-plane outage that has nothing to do with the
application. All three modules therefore pin the `io.opentelemetry.exporter` logger to WARN, so a
telemetry-sink outage stays visible (a WARN breadcrumb) without inflating the app error-rate signal.
Detection of a genuine tracing outage is owned by the `up{job=~"alloy|tempo"}` targets and the
dead-man's switch, not by the app's own error log.

**Acceptance**

- [ ] With Alloy/Tempo unreachable, backend/frontend/ingest keep logging OTLP export failures at
  WARN (not ERROR), so `logback_events_total{level="error"}` does not rise and `LogbackErrorSpike`
  does not fire on the export failures alone.
- [ ] A real application error still logs at ERROR and still counts toward `LogbackErrorSpike`.

**Enforced by:** `{backend,frontend,ingest}/src/main/resources/application.yml`
(`logging.level."io.opentelemetry.exporter": WARN`) · `monitoring/prometheus/alerts/apps.yml`
(`LogbackErrorSpike`)

### REQ-OBS-014 — Monitoring-plane self-observation & pipeline liveness

The monitoring plane must detect its **own** silent failures — a signal that stops flowing
without any alert noticing (frozen gauges, failed config reloads, dead log streams, absent
series). `deploy.sh` SIGHUP-reloads Prometheus/Alloy/blackbox on every config change and the
Watchdog only proves the pipeline is alive, not that it is correct; the plane therefore alerts on:

- **Config-reload failures.** A failed SIGHUP silently keeps the last-good config running.
  Prometheus, Alertmanager, Alloy and the blackbox exporter each expose a
  `*_config_last_reload_successful` / `alloy_config_last_load_successful` gauge;
  `PrometheusConfigReloadFailed`, `AlertmanagerConfigReloadFailed`, `AlloyConfigReloadFailed` and
  `BlackboxConfigReloadFailed` (critical) fire when the running config diverges from the deployed
  one. The blackbox exporter's own metrics are scraped by a dedicated `blackbox-exporter` job (its
  `/metrics`, distinct from the `/probe` posture/liveness jobs).
- **Rule-evaluation & notification failures.** The two independent alert evaluators (Prometheus and
  the Loki ruler) must keep evaluating and delivering. `PrometheusRuleEvaluationFailures`,
  `PrometheusNotificationsDropped`, `LokiRuleEvaluationFailures`, `LokiRulerNotificationsFailing`
  (warning) catch a broken rule or a severed Alertmanager link; `PrometheusTsdbProblems` (critical)
  catches WAL corruption / failed compaction; `NodeTextfileScrapeError` and `AlloyComponentUnhealthy`
  (warning) catch a broken ops-automation textfile metric and an unhealthy log/trace component.
- **Log-pipeline liveness.** The log-derived alerts (SSH-compromise, Postgres-FATAL, …) silently
  stop firing if Alloy stops tailing, because `rate()` over an absent stream is empty, not zero, and
  `TargetDown` cannot see a healthy-but-not-shipping Alloy. `LokiIngestSilent` (whole-pipeline
  silence), per-critical-path `LogStreamSilent` (auth.log / audit.log / npm-access, via `absent()` of
  the file's `loki_source_file_read_lines_total` series — a tailed file keeps its series present even
  when quiet, so absence means the file is not being tailed at all, the permission-drift failure
  `config.alloy` warns about) and `LokiWriteFailing` (shipper-side entry drops) — all warning — cover
  it.
- **Container-metric blackout.** cAdvisor can stay "up" while emitting zero name-labelled series (a
  real incident, CHANGELOG v1.1.1), silently blinding the container alerts. `ContainerMetricsMissing`
  (critical) and `CoreContainerMetricsMissing` (warning) guard the named-series count;
  `ContainerOomKilled` and `ContainerCpuThrottledHigh` (warning) surface a single OOM kill and
  sustained CFS throttling that the coarse `ContainerRestartLoop` / `ContainerWorkingSetHigh` alerts
  miss.

All labels stay bounded (REQ-OBS-006): these alerts read only the exporters' own low-cardinality
series (`job` / `instance` / `reason` / `name` / `path` / `health_type`), never per-user or
free-text values.

**Enforced by:** `monitoring/prometheus/alerts/meta.yml` (`meta-self-health` + `meta-log-pipeline`
groups) · `monitoring/prometheus/alerts/infrastructure.yml` (container guards) ·
`monitoring/prometheus/prometheus.yml` (the `blackbox-exporter` self-metrics scrape job) ·
`monitoring/grafana/dashboards/13-meta-monitoring.json` (log-pipeline panels) ·
`monitoring/README.md` (alert-response runbook).
