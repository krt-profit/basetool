> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-06.
> **Owner area:** OBS · **Related:** [`security-and-access.md`](security-and-access.md), [`org-unit-tenancy.md`](org-unit-tenancy.md), [ADR-0072](../adr/0072-monitoring-stack-prometheus-grafana.md), [ADR-0095](../adr/0095-ship-app-container-stdout-to-loki.md), monitoring epic [#936](https://github.com/krt-profit/basetool/issues/936)

# Observability & logging

## Context & goal

Every request is traceable end-to-end across all three modules (backend, frontend, ingest)
via shared MDC fields, with machine-parseable JSON logs in prod — and never any PII in the
logs. The monitoring stack (Prometheus/Grafana/Loki/Tempo/Alloy, epic
[#936](https://github.com/krt-profit/basetool/issues/936), ADR-0072) builds on these
guarantees; REQ-OBS-005 onward record its binding rules.

## Requirements

### REQ-OBS-001 — Access log + MDC enrichment

The backend and frontend emit one access-log line per request and enrich every log line with MDC
fields `correlationId`, `userId`, and `orgUnitId` (the last per
[`org-unit-tenancy.md`](org-unit-tenancy.md) REQ-ORG-007). Logback patterns must include
`%X{orgUnitId}` to keep audit trails intact. The ingest gateway emits the same
one-line-per-request access log (`RequestLoggingFilter`, scoped to `/v1`) but carries only the
`correlationId` MDC field — it owns no per-user data (REQ-OBS-003).

A relayed backend failure is logged **exactly once, at the level its status warrants.** The
frontend's `BackendApiClient` boundary logs every backend error once — a 5xx server fault at
`ERROR`, a 4xx client error (validation `400`, conflict `409`, …) at `WARN` — with method, URI,
stable `code`, correlationId and detail. Frontend page/write controllers therefore relay the error
to the caller (`propagateBackendError`) **without re-logging it at `ERROR`**; an expected client 4xx
must never reach `ERROR`, or it inflates `logback_events_total{level="error"}` and trips the
`LogbackErrorSpike` alert on normal user-input mistakes. Only a genuinely unexpected failure (a bare
`catch (Exception …)`, not a mapped `BackendServiceException`) logs at `ERROR`.

A call **short-circuited by an open circuit breaker** (`CallNotPermittedException`) is logged at
`DEBUG`, never `WARN`. The one-time breaker state transition (`ResilienceEventLogger.onStateTransition`,
`WARN`) plus the `basetool_backend_client_errors_total{reason="circuit_open"}` counter and the
`resilience4j_circuitbreaker_state`-backed `CircuitBreakerOpen` alert are the health signal; the
per-blocked-call line at all three touch points (`ResilienceEventLogger.onCallNotPermitted`, the
`WebClientLoggingFilter` outer log filter, and the `BackendApiClient` boundary) would otherwise emit
**three identical `WARN` lines for every** short-circuited call for the whole open window, flooding
the log during a routine backend restart/deploy and making an expected self-healing blip
indistinguishable from a real incident (issue #1203). Genuine transport failures (timeout, connection
reset) that *open* the breaker still log at `WARN` — those are the signal that the backend is down.

The backend's `GlobalExceptionHandler` logs every mapped 4xx at `WARN` — method, URI, status, stable
`code`, `correlationId` — **except a `401 UNAUTHENTICATED`, which logs at `DEBUG`.** A 401 is the
expected, non-actionable default for any unauthenticated caller, and the app has no anonymous root
handler, so the internal-TLS health probe (`blackbox-internal-tls`) alone — it GETs each app root
every 30 s purely to read the cert-expiry — would emit ~2 WARN lines/minute (≈2 880/day) of pure
probe noise, with bots, scanners and pre-login navigation adding more. The
`basetool_http_error_total{code="UNAUTHENTICATED"}` counter is minted at the handler independent of
log level, so the signal survives for the dashboard/alerts; every other 4xx — including `403
ACCESS_DENIED`, the security-relevant "authenticated but not allowed" case — stays at `WARN`.

### REQ-OBS-002 — Correlation-id propagation

`correlationId` comes from the inbound `X-Correlation-Id` header (configurable via
`APP_LOGGING_CORRELATION_ID_HEADER`) or a generated UUID, and is echoed in the response
header. The frontend's `WebClientLoggingFilter` propagates the same id to outbound backend
calls so both modules share one id per user interaction. `userId` is the JWT `sub`, or
`anonymous`.

Errors raised **before** `CorrelationIdFilter` runs — the rate-limit 429, the pending-approval 403,
and the Spring Security filter-level 401/403 — mint their own `correlationId`, put it in the MDC (so
the problem body and the log line share it — `WARN` for the 403, `DEBUG` for the 401 per REQ-OBS-001),
and echo it as the `X-Correlation-Id` response
header themselves, because that filter never runs to echo it on a short-circuited request. Every
error response therefore carries the header, not just the ones that reach the servlet. See
[`api-conventions.md`](api-conventions.md) REQ-API-004 for the full producer list.

### REQ-OBS-003 — Prod JSON appender

In `prod`, a PII-masking `LogstashEncoder` JSON appender writes `logs/{backend,frontend}.json`;
errors split into `*-error.log` for fast triage. Configurable via `APP_LOGGING_*` env vars.
The ingest gateway now logs the same way as backend/frontend — a PII-masking console + rolling
text log + dedicated `*-error.log` in every profile, plus `logs/ingest.json` in prod — so its JSON
is tailed from the file (`loki.source.file` → `app_json`), no longer via the Docker log API.

### REQ-OBS-004 — Never log PII

**Never log names, emails, or tokens.** This is unconditional and applies to every log
level and all three modules. "Names" includes the Keycloak `preferred_username` / callsign handle:
the `PiiMasker` only scrubs JWTs, e-mail-shaped strings and token keywords, so a bare handle
would reach the appenders verbatim — log the user's `sub` UUID instead (the row id is in the
same UUID space and is not PII).

### REQ-OBS-005 — Prometheus metrics endpoint, fail-closed

All three modules expose Micrometer metrics at `GET /actuator/prometheus` for the monitoring
scrape (epic #936, ADR-0072). The endpoint is **never public**:

> **Amended by ADR-0090 (frontend + ingest, prod):** the two internet-facing modules now serve
> **all** of `/actuator/**` (health + prometheus) on a dedicated **internal-only management port**
> (frontend `18091`, ingest `11272`; `management.server.port` in `application-prod.yml`, HTTPS via the
> shared keystore). That port is reachable only on `net-monitoring-scrape` (Prometheus) and
> `localhost` (the Docker `HEALTHCHECK`) — never host-published and never on an NPM proxy network —
> so `/actuator/**` is off the public connector entirely (a public probe gets 404 at the app level,
> independent of the NPM edge deny). On that internal-only port Actuator is **unauthenticated** (a
> per-module `ManagementPortSecurityConfig` permit-all chain, gated by `@ConditionalOnProperty`), the
> Keycloak port-9000 posture: the fail-closed basic-auth **compensating control below is superseded
> by port isolation** for frontend/ingest and their scrape jobs drop `basic_auth`. The bullets below
> therefore describe **backend** (unchanged — app-port scrape, basic-auth) and **dev/test/e2e** (no
> management port set → Actuator stays on the app port, `MonitoringScrapeSecurityConfig` fail-closes
> it). REQ-OBS-012's edge assertions remain as belt-and-braces drift detection.

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
- `/actuator/health` is **unauthenticated at the app** (`permitAll`) so the Docker `HEALTHCHECK`
  can reach it over `localhost` inside the container — but it is **not internet-reachable**: the
  same `location /actuator` NPM edge deny that hides `/actuator/prometheus` also hides
  `/actuator/health`, and `blackbox-edge-deny` now asserts the 404 for **both** paths on both public
  hosts (REQ-OBS-012). "Public" here means unauthenticated-at-the-app, not edge-exposed. The
  frontend/ingest `BotProtectionFilter` whitelists `/actuator/health` (incl. its liveness/readiness
  sub-paths, case-insensitively) and `/actuator/prometheus` as an **exact, case-sensitive match
  only**, mirroring the scrape chain's `securityMatcher`; prometheus sub-paths/case variants and
  every other `/actuator/**` path stay blocked with 404 before the security chains run.
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

- **backend / frontend / ingest JSON** (`app="backend"` / `"frontend"` / `"ingest"`) — the masked
  JSON file sinks, PII-masked **at the source** (REQ-OBS-003/-004); the shipper adds no further
  masking. These carry the `level` label.
- **backend / frontend / ingest container stdout/stderr** (`app="backend-stdout"` /
  `"frontend-stdout"` / `"ingest-stdout"`; ADR-0095) — the raw container console, shipped via
  `loki.source.docker` **in addition to** the JSON file above and kept under a **distinct** `app`
  label so a mixed masked-JSON / raw-stdout label never muddies the JSON stream's
  `{app="backend",level="error"}` queries. Motive: JVM/glibc native errors (`pthread_create failed` /
  `unable to create native thread`, the `hs_err` preamble) print to the container's stderr **outside
  logback**, so they land in `docker logs` but never in the JSON file — this stream is the only place
  Loki can see them. The app's own console lines *are* masked at source (the prod logback CONSOLE
  appender runs through `PiiMaskingPatternLayout`), but the truly-raw non-logback stderr is not, so
  the shipper masks these streams **in Alloy** (`loki.process.container_mask`) mirroring the
  source-side `PiiMasker` (JWT / e-mail / bearer-token keyword) as defense-in-depth. No client IPs or
  usernames by design → PII-free operational logging (like the `mon-*` streams below), global 744h
  retention, **no** REQ-OBS-010 IP-retention impact. This **reverses** the original file-only shipping
  decision for these three modules (ADR-0072); the reversal has owner sign-off (2026-07-12) and its
  rationale/cost live in ADR-0095. The `JvmNativeThreadExhaustion` Loki rule that consumes this stream
  ships **staged** (commented) until the native line is verified present on the test stack (REQ-OBS-014
  dead-alert guard).
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
- **Monitoring-plane container stdout** (`app="mon-<service>"` for the monitoring services —
  Prometheus, Grafana, Loki, Tempo, Alloy, Alertmanager, the exporters, the socket proxy; #1041
  item 24) — shipped so a misbehaving monitoring component (Grafana and Tempo have OOM-looped in
  prod) leaves evidence in Loki rather than only in the rotation-capped host docker-json logs. Two
  streams carry PII and are masked **in the shipper**: `mon-grafana` (Keycloak-OIDC admin logins
  log `uname=` + e-mail) and `mon-alertmanager` (SMTP-failure lines carry the recipient e-mail),
  both scrubbed by Alloy `stage.replace` (gated by a `stage.match` on the app label) mirroring the
  Keycloak mask. The streams inherit the global 744h retention — no REQ-OBS-010 IP-retention impact
  once masked.
- Loki labels stay low-cardinality (`app`, `level`, bounded `host`); log lines are never
  turned into per-user labels. The `level` label is carried by the three JSON app streams
  (`backend` / `frontend` / `ingest`), all tailed from their `logs/<app>.json` file sinks through the
  shared `app_json` stage, and holds Logback's **uppercase** level string (`ERROR`, `WARN`, `INFO`, …)
  verbatim. Loki `=` matchers are case-sensitive, so any dashboard/alert selecting by level must use a
  case-insensitive matcher (`{level=~"(?i)error"}`), never `{level="error"}`.

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
  hold one span open for its whole lifetime). The **server side** of that same stream is
  excluded symmetrically: each module's `NotificationStreamObservationPredicate` drops the
  `http.server.requests` observation for its notification SSE endpoint
  (`/api/v1/notifications/stream` on the backend, `/notifications/stream` on the frontend).
  Spring MVC books an async request's whole lifetime into `http.server.requests` on completion,
  so without this every closed stream recorded an ~1800s latency sample that fell in the
  histogram's `+Inf` bucket (capped at the 10s `maximum-expected-value`) and — once stream
  turnover exceeded ~5% of requests in a scrape window — pinned the aggregate
  `histogram_quantile(0.95, …)` to the top bucket, firing `HttpLatencyP95High` on both modules
  with no real user-facing slowness. Skipping the observation keeps the SSE lifetime out of the
  latency metric and its p95 alert / dashboard panels; stream health stays visible through the
  dedicated `basetool_sse_connections`, `basetool_sse_send_failures_total` and
  `basetool_notification_relay_connections` meters.
- Trace retention is short (14 days, Tempo, Phase 2) and access is admin-only via Grafana
  (REQ-OBS-008).
- **Traces are consumed** (#1041 item 22): the `14-tracing.json` dashboard runs RED/latency panels
  (request rate / p95 / 5xx **by route**) off the server-side `http_server_requests` histograms on the
  **Prometheus** datasource — the same series that back the latency / 5xx alerts, so panels and alerts
  stay consistent and no new Prometheus series are added (ADR-0076 amendment 2026-07-10; the panels
  were originally TraceQL-metrics, but those require the metrics-generator `local-blocks` processor,
  which is not enabled — only `service-graphs` is). Trace data itself is queried by the TraceQL search
  tables (`{ duration > 1s }`, `{ status = error }`) **directly against the Tempo datasource**. Tempo
  pipeline health is alerted in `meta.yml`:
  `TempoSpansRefused` (`tempo_receiver_refused_spans`), `TempoReceiverSilent`
  (`tempo_receiver_accepted_spans` rate 0 for 1h while the counter is non-zero, so it stays quiet
  when tracing is disabled) and `TempoWritePathFailing` (live-store completion/flush failures) —
  metric names verified against a live Tempo 3.0.2 scrape, since REQ-OBS-013 keeps sink failures out
  of the app logs. An ungraceful container stop is a distinct trigger of this write-path failure
  mode: the two dskit stores (`loki`, `tempo`) both set `stop_grace_period: 45s` so a routine
  `deploy.sh --force-recreate` cannot `SIGKILL` them mid-drain (dskit
  `server.graceful_shutdown_timeout` 30s) and truncate the write-ahead log (ADR-0072 amendment
  2026-07-12). The service-graph (node graph) is lit by the metrics-generator's `service-graphs`
  processor → Prometheus `remote_write` (#1041 item 22a, ADR-0076 amendment): it authenticates as the
  shared `grafana` web-auth user (Tempo runs `-config.expand-env`), Prometheus adds
  `--web.enable-remote-write-receiver`, cardinality is capped by `max_active_series`, and
  `TempoGeneratorRemoteWriteFailing` / `TempoGeneratorSeriesLimited` alert on a credential or
  stale-loaded-config drift (a `prometheus-web.yml` change not picked up until Prometheus/Tempo are
  recreated) or a cardinality-cap hit. Span-metrics remote-write stays a non-goal.

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

- `basetool_scheduled_job_executions_total{task,outcome}` counter,
  `basetool_scheduled_job_duration_seconds{task}` timer,
  `basetool_scheduled_job_last_success_timestamp_seconds{task}` gauge and — for the jobs that
  process a countable batch — `basetool_scheduled_job_items_total{task}` counter for the seven
  wrapped jobs (`user_sync`, `notification_retention`, `default_blueprint_provisioning`,
  `bank_ledger_integrity`, `uex_sync`, `scwiki_sync`, `business_metrics`) via `TaskMetrics` (`record`
  / `recordCounting`). The `business_metrics` job wraps `BusinessMetricsCollector.refresh()` (the 60s
  queue-depth sampler) so a wedged sampler surfaces via its frozen last-success (`BusinessMetricsStale`)
  instead of silently freezing every queue gauge under the `*ApprovalOverdue` alerts (#1041 item 3).
  The scheduled-job timer publishes latency histogram buckets (bounded 10ms..600s, `task` label only)
  so the operations dashboard charts per-job p95 duration. The job-identity tag is `task`, NOT `job`
  (#1041 item 23): a metric tag named `job` collides with the Prometheus scrape `job` label and gets
  renamed to `exported_job` — so the alerts once matched `exported_job` and one silently never fired.
  The rename splits the 180d history (old series carry `exported_job`, new ones `task`; a Grafana
  annotation marks the cutover). The last-success gauge is the source of the
  staleness alerts — `UserSyncStale` (`user_sync`, > 26h — daily 05:00 cadence, see `app.keycloak.sync.cron`), `ExternalSyncStale` (the catalogue syncs,

  > 48 h), `ScheduledJobStale` (`notification_retention` / `default_blueprint_provisioning`, > 26 h),
  > `BankLedgerIntegritySweepStale` (`bank_ledger_integrity`, > 6 h, **critical** — while stale the
  > violations gauge freezes and `BankLedgerIntegrityViolation` cannot fire) and `BusinessMetricsStale`
  > (`business_metrics`, > 10 min); it is registered lazily so a config-gated-off job never reports a
  > falsely-stale `0`. The items counter is present only for jobs that report a count: user sync,
  > notification retention, default-blueprint provisioning, and — since #1041 item 2 — `uex_sync` (the
  > `UexItemSyncService` `game_item` upsert tally, with the unchanged-catalogue carve-out below) and
  > `scwiki_sync` (the sum of the five SC-Wiki step counts, a failing step contributing `0`; same
  > carve-out below). For the two catalogue syncs it is populated from the same
  > per-run tallies the sync-report summary uses and backs the `SyncZeroItems` alert, which fires when
  > a sync records a successful run in 48 h but has processed zero rows — the empty-200 catalogue outage
  > that neither `ExternalSyncStale` (last-success stays fresh) nor `ExternalFetchErrors` (an empty 200
  > is not a fetch error) catches. **Restart-robust guard (2026-07-17):** the second leg is
  > `increase(...executions_total{outcome="success"}[48h]) > 0`, not `(time() - last_success) < 172800`.
  > The items counter is registered lazily (its 0→N birth is never scraped) and resets on each backend
  > restart, so across restarts more frequent than the daily cadence Prometheus sees a permanently-flat
  > items series and `increase(items[48h])` reads 0 even on a healthy sync — the old last-success leg
  > then false-fired continuously (the 2026-07-15…17 storm; UEX was upserting ~7499 rows/run).
  > `executions_total{outcome="success"}` shares the items counter's lazy-registration + per-restart-reset
  > behaviour, so it cancels the false `0` while still climbing on a genuine outage; same robust shape as
  > `UserSyncZeroItems`. **Unchanged-catalogue carve-out (`uex_sync` + `scwiki_sync`, #1182):**
  > `UexClient` and `ScWikiClient` both do conditional GETs, so when the whole catalogue is unchanged
  > every category/endpoint returns `304 Not Modified` and the run upserts nothing — a healthy no-op
  > indistinguishable from an empty-200 outage by the raw upsert tally alone. To stop that false
  > `SyncZeroItems` firing, `UexItemSyncService` reports the live catalogue size
  > (`GameItemRepository.countLiveUexItems()`) instead of `0` when nothing was upserted but at least
  > one category was served from the `304` cache, and each `scwiki_sync` step likewise reports its
  > live linked-row count (`MaterialRepository.countLiveScwikiMaterials` for commodities, plus
  > `countLiveScwikiShipTypes` / `countLiveScwikiBlueprints` / `countLiveScwikiManufacturers` /
  > `countLiveScwikiItems` for the other steps) on an all-`304` fetch; a genuine empty-200 (no `304`
  > at all) still reports `0` and correctly trips the alert. The same success-with-zero-work idea
  > backs `UserSyncZeroItems`
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
- `basetool_keycloak_sync_fetch_failures_total` counter (untagged, `KeycloakService.fetchUsers`) and
  `basetool_scheduled_job_step_failures_total{task,step}` counter (`ScWikiScheduler.runStep`; `step`
  = the bounded `commodity`/`vehicle`/`item`/`blueprint`/`manufacturer` literal) both cover a
  swallowed failure the scheduled-job *outcome* signal cannot see. The Keycloak roster fetch is
  swallowed to an empty list, so the user sync records `success`/0-items and a Keycloak Admin-API
  outage is indistinguishable from a legitimately empty roster (access-control-relevant — a stalled
  sync leaves departed users with their local roles); a single SC-Wiki sync step throws but the
  other steps still run, so `scwiki_sync` records `success` with a non-zero item tally and a
  reliably-failing step is invisible to `UserSyncStale` / `SyncZeroItems` / `ExternalSyncStale`. They
  back `KeycloakSyncFetchFailing` and `ScWikiStepFailing` (logging audit).
- Frontend→backend seam (#1041 item 11): the frontend enables the `http.client.requests`
  percentile-histogram (same bounded 5ms..10s window as `http.server.requests`, so both stay on the
  same ~14 buckets) to drive a client-p95-vs-server-p95 overlay that separates "backend slow" from
  "frontend slow". The already-exported resilience4j meters gain two leading-indicator alerts —
  `BulkheadNearSaturation` (< 5 free bulkhead slots for 10m) and `RetryRateElevated`
  (`successful_with_retry` > 0.2/s for 10m) — that fire before `circuit_open` / `bulkhead_full`, i.e.
  before users fail; plus a backend-call resilience row on `03-spring-apps.json` and a
  frontend-usage row (`basetool_active_sessions`, `basetool_mission_presence_missions`) on `07`.
- Read-amplification fan-out (#1128): a derived panel on `03-spring-apps.json` plots the
  frontend→backend fan-out ratio —
  `sum(rate(http_client_requests_seconds_count{application="basetool-frontend"}[5m])) /
  sum(rate(http_server_requests_seconds_count{application="basetool-frontend"}[5m]))`, the outbound
  backend calls per inbound frontend request — and `FrontendBackendFanoutHigh` (`apps.yml`) warns on
  a sustained step change (> 10 for 15m, an intentionally generous initial baseline). It catches a
  per-render read-amplification regression (a page/fragment that lost its read-gating and fans out
  into N backend GETs, the pre-ADR-0078 mission-page failure mode) early, before the lagging
  `HikariPoolPending` / `HttpLatencyP95High` fire. Coarse by design — the frontend also serves
  static assets / polls with no backend call, diluting the average down — so it is a regression
  tripwire, not a precise per-route SLO.
- JVM/Hikari depth (#1041 item 12, all Actuator-exported): `HikariConnectionTimeouts` (every
  `hikaricp_connections_timeout_total` increment is a request that waited ~30s for a pool slot and
  threw — can hide below `HikariPoolPending`'s window), `JvmFileDescriptorsHigh`
  (`process_files_open_files` > 85% of max — FD leaks kill a JVM with confusing symptoms),
  `JvmThreadsHigh` (`jvm_threads_live_threads` > 1638 = 80% of the container's hardcoded 2048 `pids`
  cap — at the cap the JVM throws `OutOfMemoryError: unable to create native thread` regardless of
  heap/RAM headroom, the 2026-07-09 native-thread exhaustion root cause; no JVM/Micrometer metric
  exports the cgroup pids limit so the cap is hardcoded, and the cgroup-level `ContainerPidsHigh`
  companion (REQ-OBS-014, below) catches the non-JVM tasks this JVM-only gauge cannot see — unreaped
  child-process zombies and virtual-thread OS carriers) and
  `JvmGcOverheadHigh` (`rate(jvm_gc_pause_seconds_sum)` > 20% — GC thrash degrades latency before
  `JvmHeapHigh`'s 90% trips). No metaspace rule (nonheap max is often -1 → NaN). Deepened
  `03-spring-apps.json`: per-pool heap, GC pause max by action/cause, thread states, open FDs vs max,
  per-app CPU.
- `basetool_http_error_total{code}` counter at the `GlobalExceptionHandler` 409/401/403 methods
  (`OPTIMISTIC_LOCK` = optimistic-locking regression indicator, `PESSIMISTIC_LOCK`,
  `UNAUTHENTICATED`, `ACCESS_DENIED`) plus two filter-level codes that bypass the advice and are
  incremented directly at their servlet-filter reject site: `SERVICE_UNAVAILABLE`
  (`IdentityProviderUnavailableFilter`, an unreachable Keycloak JWKS re-mapped to a retryable 503,
  REQ-SEC-024) and `PENDING_APPROVAL` (`PendingApprovalAccessFilter`, the REQ-SEC-017 403 that
  refuses a `ROLE_PENDING_APPROVAL`-only user on every `/api/**` endpoint — a mass spike means the
  authorities converter / approval sync regressed and is 403ing legitimate users, backing
  `PendingApprovalBlockSpike`). "One non-double-counted increment site" therefore holds per code, not
  per handler. The ingest gateway emits the same metric name with the `SERVICE_UNAVAILABLE` code from
  its own filter; the `application` common tag distinguishes the module.
- `basetool_audit_events_total{domain}` counter at the single `AuditService.record` choke point
  (`domain` = the `AuditDomain` values, including `MARKET` since the Materialbörse). Silence
  detection is two-tier: `AuditSilenceAnomaly` (no audited mutation anywhere for 5 d while the
  backend is up) plus, since #1041 item 10, `AuditDomainSilenceAnomaly` (a single domain silent for
  14 d while others stay active — the domain-lost-its-wiring failure mode the global sum masks;
  `PROMOTION` / `PERSONAL_INVENTORY` / `MARKET` are excluded as legitimately-quiet and reviewed on
  the operations dashboard's per-domain table instead). Item-order production bookings need no
  dedicated meter — `JOB_ORDER_PRODUCTION_BOOKED` and `INVENTORY_CONSUMED_BY_PRODUCTION` roll into
  the existing `JOB_ORDER` and `INVENTORY` domain counts (REQ-ORDERS-025).
- `basetool_material_exchange_active_count{status="ACTIVE"}` gauge sampled by
  `BusinessMetricsCollector` — the number of active Materialbörse offers on the board, spanning
  **both** offer kinds (material and item, REQ-MARKET-012), via `countByStatus(ACTIVE)`
  (REQ-MARKET-*, REQ-OBS-011). Counts only; the board never emits a per-offer, per-user, per-kind or
  location label.
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
  reverse-proxy buffering drift). The cross-replica SSE fan-out (#1102, REQ-FE-015 / ADR-0094) adds
  `basetool_sse_redis_published_total` / `basetool_sse_redis_consumed_total` (real-time notification
  signals this replica published to / consumed from the `basetool:notify:published` Redis channel;
  own-origin messages are excluded) and `basetool_sse_redis_errors_total{op}` (`publish` / `consume`
  — a swallowed fan-out failure; the local same-replica delivery already happened, so it only
  degrades cross-replica push). These emit only where the fan-out is enabled (prod); a sustained
  `publish` error stream drives the `LiveSyncRedisFanoutBroken` alert (below).

**Frontend.** `basetool_mission_presence_missions` gauge (missions with a live editor; single-JVM
edit-awareness, unlabelled), `basetool_active_sessions` gauge (active Spring Session sessions;
`@Profile("!test")`, maintained by `ActiveSessionsTracker` from Spring Session create/delete/expire
events and seeded once at startup from the Redis session namespace — it MUST NOT sample the
Redis-backed `SpringSessionBackedSessionRegistry`, whose `getAllPrincipals()` throws and left the
gauge permanently `NaN`, silently disarming `SsePushChannelDead`, #1158), and
`basetool_backend_client_errors_total{reason,method}` counter at the
`BackendApiClient` failure funnels. `reason` is a fixed **local** enumeration
(`backend_4xx`/`backend_5xx`/`circuit_open`/`bulkhead_full`/`timeout`/`unknown`) derived from the
failure branch — never the backend's response-body code, which could be arbitrary — and `method`
is the HTTP verb. The push-channel surfaces (#1041 item 17) add `basetool_notification_relay_connections`
(open browser→backend notification SSE relays, `NotificationPageController`) and
`basetool_presence_ws_sessions` (live live-sync WebSocket sessions summed across all topic rooms,
`LiveSyncWebSocketHandler`) gauges, plus the `basetool_presence_relay_frames_total{type,topic_class}`
(`type` = `changed` / `snapshot`) and `basetool_presence_relay_dropped_total{reason,topic_class}`
(`reason` = `throttled` / `send_failed` / `topic_cap` / `authorize_saturated` / `topic_throttled`)
counters at the previously-silent throttle, send-failure, topic-cap, subscribe-saturation and
per-topic-throttle branches of the relay — the `topic_throttled` reason (F2/#1243) fires when a
room's *aggregate* publish rate exceeds its per-topic token bucket regardless of the per-session
limit —
the component that shipped the REQ-FE-010 staleness defect. Since #1102 (REQ-FE-015 / ADR-0094) both
counters carry a bounded `topic_class` label (one of the eight `LiveSyncTopicClass` labels: `mission`,
`operation`, `order_detail`, `orders_queue`, `bank_account`, `bank_staff`, `orgunit_bank`, `materialboard`), and
the meter names stay put — a rename would break the `07` panels and this alert set. A `changed`-frame
flatline (overall on panel 28, or per surface on the `topic_class` breakdown) while `snapshot` frames
keep flowing is the early indicator for that defect class (panels only, baselined before alerting).
The tool-wide live-sync relay adds five more meters: `basetool_livesync_subscriptions{topic_class}`
(open `/ws/sync` subscriptions per topic class — the live per-surface load denominator),
`basetool_livesync_subscribe_total{topic_class,outcome}` (`outcome` = `allowed` / `denied`, the
subscribe-authorization verdict; a saturated-executor fail-open is instead a `authorize_saturated`
relay drop), `basetool_livesync_socket_rejected_total{reason}` (`reason` = `user_cap`; a `/ws/sync`
socket refused at connect because the user is already at the per-user socket cap — F2/#1243, no
`topic_class` because a rejected socket has bound no topic; plotted alongside the relay drops on the
`07` "Presence relay drops/hour" panel), the unlabelled `basetool_livesync_invalid_topic_total`
(a `/ws/sync` subscribe to an unknown/unparseable topic — `LiveSyncTopic.parse(...) == null` — the
signature of a client/server topic-vocabulary skew where a client subscribes to a topic this server
no longer knows; no `topic_class` because the topic did not parse into a class — a dedicated
unlabelled meter rather than an `unknown` sentinel keeps the bounded `topic_class` set to the real
classes, the REQ-OBS-011 design call deferred from #1102 step 11 and resolved in #1239; plotted on
the same `07` "Presence relay drops/hour" panel), and the cross-replica fan-out counters
`basetool_livesync_redis_published_total{topic_class}` / `basetool_livesync_redis_consumed_total{topic_class}`
(`changed` signals published to / consumed from the `basetool:livesync:changed` Redis channel;
own-origin excluded) plus `basetool_livesync_redis_errors_total{op}` (`publish` / `consume`, a
swallowed fan-out failure that degrades only cross-replica delivery). Together with the backend
`basetool_sse_redis_*` counters above, a sustained `publish`-error stream on either fan-out drives the
`LiveSyncRedisFanoutBroken` alert (both fire only where the Redis fan-out is enabled, i.e. prod). All labels are fixed literals, pure counts. The `frontend-sse-pool` and
`frontend-pool` Reactor-Netty connection pools additionally export `reactor.netty.connection.provider.*`
(`.metrics(true)`, #1127); `basetool_notification_relay_connections` backs the
`SseRelayPoolNearSaturation` alert (> 0.8 of the 1000-slot SSE pool for 10m) — the early warning
before the pool starts silently dropping the 1001st live viewer. `basetool_active_sessions`
additionally backs the `ActiveSessionsRunaway` alert (> 2000 for 1h): the frontend session
idle timeout is two-tier (REQ-SEC-025 / ADR-0088) — un-authenticated sessions get a short window
(`app.session.anonymous-timeout`) so the throwaway CSRF-token / pre-login-OAuth2 sessions minted for
anonymous traffic cannot accrete, and only a successful login promotes the session to the 30-day
`app.session.authenticated-timeout`. A sustained climb past a few hundred means that split regressed
(orphan sessions accreting again, as they did to >16000 against ~30 real principals) and the Redis
session store is heading for its `maxmemory noeviction` ceiling where login/token-refresh writes
fail.

The auth surfaces (#1041 item 18) add `basetool_login_total{outcome,reason}` (`SecurityConfig`'s
OAuth2 success/failure handlers: `outcome` = `success` / `failure`; on failure `reason` =
`invalid_state` / `provider_error` / `other`, **mapped from the exception type and bounded OAuth2
error code — never the raw error description**; on success `reason` = `none`) and the unlabelled
`basetool_csrf_rejections_total` (a custom `AccessDeniedHandler` counts CSRF-token rejections before
the 403). They drive `FrontendLoginBroken` (>= 3 `reason="provider_error"` failures in 15m with zero
concurrent successes, sustained 10m — the code-to-token / JWKS / bad-IdP-response break
`KeycloakLoginErrorSpike`'s event regex misses because it fails *after* the user already authenticated
at Keycloak; the failure side is scoped to `provider_error` and floored so the benign `invalid_state`
noise cannot trip it when fresh successes are naturally sparse under the 30-day login window.
Unauthenticated bots hitting the bare OAuth callback raise `invalid_request` — a bare/partial callback
is not a valid authorization response, so `OAuth2LoginAuthenticationFilter` rejects it before any token
exchange — and abandoned / expired-state logins raise the state codes; `LoginFailureMetricsHandler`
folds **both** into the `invalid_state` bucket (2026-07-15 fix — before it, `invalid_request` leaked
into `provider_error` and off-peak scanner traffic false-tripped the alert), keeping `provider_error` a
genuine post-authorization token/IdP-break signal. A state/session-loss break also surfaces as
`invalid_state` and via the `redis` readiness indicator instead) and
`CsrfRejectionSpike` (a systematic CSRF-wiring regression that `krtFetch`'s silent single-retry
otherwise masks as intermittent failed writes). The pre-auth `BotProtectionFilter` adds
`basetool_bot_blocked_total{rule}` (#1041 item 19; `rule` = `method` / `path_prefix` /
`file_extension`) at its three reject branches, which were otherwise `log.debug`-only and
prod-invisible — the counter also surfaces a self-inflicted false positive when a new legit route
matches a blocked prefix. The **ingest gateway** carries the same filter (`REQ-INGEST-009`) and emits
the same `basetool_bot_blocked_total{rule}` series, distinguished by the `application` common tag
(`basetool-ingest` vs `basetool-frontend`); the "Bot-blocked/hour by rule" panel groups by
`application` + `rule` so both modules are visible. Panels only, all labels fixed literals.

**Ingest.** `basetool_ingest_handoff_total{kind}` (accepted+staged handoffs per `HandoffKind`),
`basetool_ingest_handoff_errors_total{reason}` (relay failures: `backend_reject` /
`backend_unavailable` / `internal`; pre-relay rejections are not counted here), and
`basetool_ratelimit_rejections_total{bucket}` (`bucket` = `ip` / `subject`; shares the metric name
with the backend counter, the `application` common tag separating the modules) — paired since #1041
item 19 with `basetool_ratelimit_requests_total{bucket}` on the per-IP filter and the per-subject
limiter, feeding the same `RateLimitRejectionRatioHigh` ratio alert.
`basetool_ingest_payload_rejected_total` (untagged, `PayloadSizeLimitFilter`) counts each
oversized-body 413 the INGEST-DOS-1 guard refuses — previously silent (no log, no metric) unlike the
sibling bot / rate-limit filters — and backs `IngestPayloadRejectedSpike` (logging audit). Its
backend twin `basetool_request_body_rejected_total` (untagged, `RequestBodySizeLimitFilter`) counts
each oversized non-multipart JSON body the backend refuses with 413 on a capped import path (the
refinery `import-extract`, before Jackson binds it — security review, memory-DoS) and backs
`RequestBodyRejectedSpike`. The
gateway also now emits one INFO access-log line per `/v1` request (`RequestLoggingFilter`; method /
path / status / duration), matching the backend/frontend one-line-per-request contract
(REQ-OBS-001).

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
`SERVICE_UNAVAILABLE`, `ACCESS_DENIED` and `PENDING_APPROVAL` codes feed `IdentityProviderUnavailable`
/ `AccessDeniedSpike` / `PendingApprovalBlockSpike` (and the all-codes HTTP-error panel on dashboard
`07`); the `basetool_keycloak_sync_fetch_failures_total` and `basetool_scheduled_job_step_failures_total`
counters feed `KeycloakSyncFetchFailing` / `ScWikiStepFailing`; and `KeycloakEventMetricsAbsent` guards
the `keycloak_user_events_total` series that `KeycloakLoginErrorSpike` depends on. Adding, renaming or
removing one of these metrics keeps its alert in `monitoring/prometheus/alerts/business.yml` in sync in
the same change.

### REQ-OBS-012 — Edge posture assertions (deny / redirect / HSTS probes)

Security-relevant edge configuration lives only in the NPM admin database (per-host toggles and
Advanced snippets under `/var/iri/npm/data`), not in git — a UI misclick or a proxy-host recreate
could silently undo it. The monitoring plane therefore **asserts** that posture continuously
instead of trusting the one-time rollout verification:

- **`/actuator` edge deny** — the `blackbox-edge-deny` job probes **both** `/actuator/prometheus`
  **and** `/actuator/health` on **both** public app hosts (`profit-base.online`,
  `ingest.profit-base.online`) with the `http_deny_404` module: probe success means the edge answers
  exactly 404 (the live deny). The whole `/actuator` prefix is denied (`location /actuator` on the
  NPM host), so neither the metrics surface nor the health/liveness/readiness/build-info surface is
  internet-reachable — Prometheus reaches metrics over `net-monitoring-scrape` and the Docker
  `HEALTHCHECK` reaches health over `localhost`, so neither needs any edge exposure. Health is
  asserted explicitly (not only metrics) so a deny narrowed to just `/actuator/prometheus` cannot
  silently re-expose the health surface. If any of the four probes drifts, the request reaches the
  app endpoint (metrics → fail-closed 401; health → 200) and `EdgeActuatorDenyBroken` (critical)
  fires — the continuous version of the ADR-0072 compensating control. The daily external
  `edge-deny-probe.yml` re-asserts the same four URLs from a GitHub runner.
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
records (NODATA fails the probe, not only NXDOMAIN). For those v6-pinned probes to test the real edge,
the `blackbox-exporter` container needs its own IPv6 egress: the two nets it otherwise joins
(`net-monitoring-core`, `net-monitoring-scrape`) are IPv4-only, so before #1252 the container had **no
v6 route** and every probe CONNECT-failed (`probe_success=0`) purely from the missing local route —
making `EdgeIpv6Unreachable` a **structural false positive** that fired even while the edge answered over
IPv6. The exporter now also joins a dedicated `net-blackbox-v6` bridge (`enable_ipv6: true`, ULA subnet
masqueraded onto the host's global v6), so the probe exercises the real edge path and the alert is a
**true** signal. `EdgeIpv6Unreachable` (warning) fires only when a
vhost answers over IPv4 but not IPv6 (guarded `on(instance)` against the v4 probe, so a full outage
pages once via `BlackboxProbeFailed`); `DnsResolutionFailed` (warning) fires when the apex stops
resolving an A or AAAA record. These are reachability probes, not posture assertions — a v6-only or
DNS-only regression is invisible to the IPv4 liveness job.

**Probe scrape timeout + `TargetDown` scoping (probe-liveness semantics).** Every blackbox `/probe`
job carries its own `scrape_timeout: 15s` (> the 10s module timeout, well under the 30s interval), and
the generic `TargetDown` alert is scoped to `up{job!~"blackbox-(http|edge|force|hsts|internal|dns).*"}
== 0` — excluding all 11 `/probe` jobs while keeping the `blackbox-exporter` self-scrape and every
non-probe target in scope. On a `/probe` job `up==0` is a **scrape-timeout artifact**, not a
target-down signal: the module timeout previously equalled the global `scrape_timeout` (both 10s), so a
probe that ran to its deadline (an IPv6 connect with no v4 fallback that black-holes, or a slow
internal-TLS handshake under the host's designed memory pressure) tipped the Prometheus→exporter scrape
past the window → `up==0` → a false-critical `TargetDown`. A **genuine** probe failure is
`probe_success==0` while `up` stays 1, caught fast by the dedicated probe alerts above
(`BlackboxProbeFailed` / `EdgeActuatorDenyBroken` / `EdgeForceSslRedirectBroken` /
`EdgeHstsHeaderMissing` / `EdgeIpv6Unreachable` / `DnsResolutionFailed`) and by `CertificateExpiringSoon`
for the internal-TLS jobs; app-down liveness for the `blackbox-internal-tls` targets is paged by the
`basetool-backend` / `-frontend` / `-ingest` scrape jobs, not by their probe. So scoping `TargetDown`
off the probe jobs removes only the false page, not any real signal. (2026-07-12 false-critical fix.)

**Acceptance**

- [ ] `EdgeActuatorDenyBroken` fires when a public app host stops answering 404 on
  `/actuator/prometheus` while the edge itself is up, and stays silent during a full edge outage.
- [ ] `EdgeForceSslRedirectBroken` fires when port 80 of a public vhost stops redirecting to
  `https://`; `EdgeHstsHeaderMissing` fires when the frontend's first response drops the header.
- [ ] The scheduled `edge-deny-probe` workflow fails when
  `https://keycloak.profit-base.online/admin/` answers 2xx/3xx from a GitHub runner or the
  `/actuator` paths stop answering 404 externally.
- [ ] `TargetDown` does **not** fire for any blackbox `/probe` job whose `up==0` (scrape-timeout
  artifact), but still fires for the `blackbox-exporter` self-scrape and every non-probe job
  (`targetdown_probe_scope_test.yml`).

**Enforced by:** `monitoring/blackbox/blackbox.yml` (`http_deny_404` / `http_force_ssl_redirect` /
`http_2xx_hsts`; the `http_2xx_ipv6` / `http_2xx_or_401_ipv6` / `dns_apex_a` / `dns_apex_aaaa`
reachability modules) · `monitoring/prometheus/prometheus.yml` (the three posture jobs; the
`blackbox-http-ipv6` / `blackbox-http-auth-ipv6` / `blackbox-dns-a` / `blackbox-dns-aaaa` reachability
jobs; the per-`/probe`-job `scrape_timeout: 15s`) · `monitoring/prometheus/alerts/infrastructure.yml`
(`EdgeActuatorDenyBroken`, `EdgeForceSslRedirectBroken`, `EdgeHstsHeaderMissing`, `EdgeIpv6Unreachable`,
`DnsResolutionFailed`, scoped `BlackboxProbeFailed`, and the `TargetDown` regex that excludes the
`/probe` jobs) · `monitoring/prometheus/tests/targetdown_probe_scope_test.yml` (promtool unit test) ·
`.github/workflows/edge-deny-probe.yml`

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
series). `deploy.sh` reconciles the bind-mounted components (Prometheus/Alloy/blackbox) against the
on-disk config on **every** healthy tick — the config-changing apply, the steady-state idempotence
no-op, all of them — **force-recreating** a component whenever its on-disk config drifts from a
persisted per-service snapshot of what it was last applied. (A `SIGHUP` cannot be trusted here: the
configs are single-file bind mounts and `rsync` replaces the file by a new inode, so the container
keeps reading the old inode until recreated — the trap behind the ingest incident below.) So a
missed, lost or rolled-back apply **self-heals** on the next tick instead of leaving the process on a
stale config. The Watchdog only proves the pipeline is alive, not that it is correct; the plane
therefore alerts on:

- **Config-reload failures.** A failed SIGHUP silently keeps the last-good config running.
  Prometheus, Alertmanager, Alloy and the blackbox exporter each expose a
  `*_config_last_reload_successful` / `alloy_config_last_load_successful` gauge;
  `PrometheusConfigReloadFailed`, `AlertmanagerConfigReloadFailed`, `AlloyConfigReloadFailed` and
  `BlackboxConfigReloadFailed` (critical) fire when the running config diverges from the deployed
  one. The blackbox exporter's own metrics are scraped by a dedicated `blackbox-exporter` job (its
  `/metrics`, distinct from the `/probe` posture/liveness jobs).
- **A config that never LANDED in the running process.** The gauges above only catch a reload that
  was *attempted and failed*; a config that was **never applied to the process** leaves
  `*_config_last_reload_successful == 1` (the last load, at startup, was fine) while the running
  config silently outruns the on-disk one — the 2026-07-11 ingest `TargetDown` (ADR-0090 moved
  ingest's actuator `11262`→`11272`, the committed `prometheus.yml` followed, but the running
  Prometheus kept the retired `11262` target through its **inode-pinned** single-file mount, so even
  the on-disk file being correct did not help). `deploy.sh` stamps
  `basetool_monitoring_config_applied_timestamp{component="prometheus"}` (a node_exporter textfile
  gauge) whenever it force-recreates Prometheus for a config change; `PrometheusConfigStale` (warning)
  fires when that stamp stays newer than Prometheus's own
  `prometheus_config_last_reload_success_timestamp_seconds` for 15 minutes — long enough for the
  self-healing reconcile to converge, so a persistent alert means it is *not* converging (the
  recreate is failing, or the `iri-deploy` timer stopped). The acute case (a wrong/absent scrape
  target) still pages `TargetDown`; this is the complementary early signal that the running Prometheus
  config is out of date at all.
- **A reconcile disabled while the stack runs.** `PrometheusConfigStale` has a blind spot of its own:
  its `basetool_monitoring_config_applied_timestamp` stamp is written **only** from inside `deploy.sh`'s
  monitoring reconcile, which returns early when `IRI_MONITORING_ENABLED != true`. On a host that runs
  the monitoring stack but leaves that flag unset, the config-bundle rsync keeps rewriting
  `monitoring/**` on disk every tick while the running containers are never recreated — on-disk
  rule/scrape changes silently never reach the running Prometheus — yet no applied stamp is ever
  produced, so `PrometheusConfigStale` has no data and cannot fire: **the same condition that causes the
  drift disables its alarm** (the 2026-07-13 overnight false-positive re-fire, where the shipped-and-
  promoted v1.3.6 rule fixes lived on disk but the running Prometheus kept the old rules). `deploy.sh`
  closes this by emitting `basetool_monitoring_reconcile_disabled{component="deploy"}` (a node_exporter
  textfile gauge) on its **own** path — `1` when the `iri-monitoring` compose project is running but the
  reconcile is gated off, `0` when the reconcile is enabled and runs — plus a loud per-tick WARN;
  `MonitoringReconcileDisabled` (warning) fires on the `== 1` gauge after 30m. Because that gauge is
  written precisely in the failure state and never depends on the applied-stamp path, it cannot be
  self-disabled the way `PrometheusConfigStale` was. The fix is a systemd drop-in on the `iri-deploy`
  service (`Environment=IRI_MONITORING_ENABLED=true`).
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
  it. The `<svc>-stdout` container streams (ADR-0095) are deliberately given **no** per-stream liveness
  alert: a native-error breadcrumb is rare by design, so a `rate()`/`absent()` liveness check on such a
  quiet stream would be a permanent false alarm — whole-pipeline silence is still caught by
  `LokiIngestSilent`.
- **Container-metric blackout.** cAdvisor can stay "up" while emitting zero name-labelled series (a
  real incident, CHANGELOG v1.1.1), silently blinding the container alerts. `ContainerMetricsMissing`
  (critical) and `CoreContainerMetricsMissing` (warning) guard the named-series count;
  `ContainerOomKilled` and `ContainerCpuThrottledHigh` (warning) surface a single OOM kill and
  sustained CFS throttling that the coarse `ContainerRestartLoop` / `ContainerWorkingSetHigh` alerts
  miss. `ContainerRestartLoop` counts restarts with `changes(container_start_time_seconds[15m]) > 3`,
  **not** `increase()` — that metric is a start-time *gauge* (Unix epoch), so `increase()` returned the
  epoch delta between the old and new start times and paged CRITICAL on any single restart, including a
  routine deploy recreate of backend/frontend/ingest (fixed 2026-07-15; the sibling Grafana "Container
  Restarts" panel already used `changes()`; locked by
  `tests/containerrestartloop_changes_test.yml`).
- **cgroup-pids exhaustion.** The `pids` cgroup cap (2048 for the four JVM containers
  backend/frontend/ingest/keycloak) limits **every** task in the container, not just JVM threads —
  unreaped child-process zombies and the OS carrier threads behind virtual threads both count against
  it yet are invisible to Micrometer's `jvm_threads_live_threads`. In the 2026-07-12 ingest
  native-thread OOM the cap was exhausted (by `ssl_client` healthcheck zombies) while that JVM-only
  gauge stayed flat (~36), so `JvmThreadsHigh` never fired and only the post-crash `TargetDown` /
  `DeployHealthRestartFailing` paged — no leading warning at all. That specific zombie source is now
  fixed at the root by **REQ-OPS-019** (`init: true` PID-1 reaping on backend/frontend/ingest);
  `ContainerPidsHigh` (warning) is the observability complement that closes the *monitoring* blind
  spot and catches **any future** pids/task leak (a different zombie source, a thread runaway) before
  the cap is hit. It fires when cAdvisor's `container_threads` (the cgroup `pids.current` task count;
  companion `container_threads_max` = `pids.max`) exceeds 80% (1638) of the 2048 cap for 10m. It is
  the cgroup-level companion to `JvmThreadsHigh` and additionally covers keycloak as defense-in-depth
  — keycloak carries the same 2048 cap but exports no `basetool-*` Micrometer series, so
  `JvmThreadsHigh` cannot see a pids/thread runaway in it at all. The signal exists **only because**
  the cadvisor `process` metric group is enabled in `docker-compose.monitoring.yml`
  (`--disable_metrics` set to cadvisor's default minus `process`, plus `disk`; `--enable_metrics=process`
  is wrong — it *replaces* the whole set and would blind the memory/cpu container alerts). The `disk`
  group is additionally disabled because the host's Docker (containerd `overlayfs` snapshotter) makes
  cAdvisor's fsHandler fail to stat per-container layers — only log noise, no usable `container_fs_*`;
  per-container filesystem metrics are unused (host filesystem usage comes from node-exporter's
  `node_filesystem_*`). The 2048 cap is
  hardcoded to stay in lockstep with `JvmThreadsHigh` and the compose `pids` limit; do **not** raise
  the cap to silence the alert.
- **Alertmanager routing & root-cause suppression.** One real fault fans out into many true-positive
  downstream symptoms; the notification plane collapses them so an operator sees the cause, not the
  storm. The route groups by `alertname` only (`group_by: ['alertname']`) — grouping *also* by `job`
  needlessly split a multi-target `TargetDown` into one mail per job. Five `inhibit_rules` suppress a
  strictly-more-root cause's symptoms, each joined on a label the two alerts actually share so a
  missing-label match can never over-suppress: (1) an app `TargetDown` mutes that app's warnings
  (`application`); (2) a `BlackboxProbeFailed` mutes that endpoint's cert/edge alerts (`instance`); (3)
  `HostDiskCritical` mutes `HostDiskWarning` (`mountpoint`); (4) a `ContainerRestartLoop` mutes that
  **same** container's resource-pressure warnings — working-set / OOM / CPU-throttle / pids, which a
  crash-looping container trips as a symptom (`name`); (5) a `TargetDown` mutes every warning derived
  from that dead target's series (`instance`, both sides gated `instance=~".+"`). Rules 4–5 were added
  after the 2026-07-15 cascade (a Postgres FATAL crash-looped the `depends_on` chain) sent ~18 mails.
  Cross-service symptoms that carry no shared join label (`FrontendLoginBroken`,
  `BackendCallFailureSustained`, the label-less sync-zero warnings) and the independent criticals still
  fire — inhibition suppresses the *notification*, never the firing, and criticals must keep paging.
  The template is rendered by the runbook with `envsubst` and validated with `amtool check-config`;
  because monitoring configs are inode-pinned bind mounts the reconcile force-recreates Alertmanager to
  apply a change (verify `AlertmanagerConfigReloadFailed == 0` after deploy).

All labels stay bounded (REQ-OBS-006): these alerts read only the exporters' own low-cardinality
series (`job` / `instance` / `reason` / `name` / `path` / `health_type` / `component`), never per-user
or free-text values.

**Enforced by:** `monitoring/prometheus/alerts/meta.yml` (`meta-self-health` + `meta-log-pipeline`
groups, incl. `MonitoringReconcileDisabled`) · `monitoring/prometheus/alerts/infrastructure.yml`
(container guards, incl. `ContainerPidsHigh` + the `changes()`-based `ContainerRestartLoop`) ·
`monitoring/alertmanager/alertmanager.yml.tmpl` (route grouping + the five root-cause `inhibit_rules`) ·
`monitoring/prometheus/tests/` (`promtool test rules` units, incl. `monitoring_reconcile_disabled_test.yml`
and `containerrestartloop_changes_test.yml`) · `docker-compose.monitoring.yml` (cadvisor
`process` metric group enabling `container_threads`/`container_processes`) ·
`monitoring/prometheus/prometheus.yml` (the `blackbox-exporter` self-metrics scrape job) ·
`scripts/deploy.sh` (`reconcile_monitoring_reload(s)` self-healing force-recreate + the
`basetool_monitoring_config_applied_timestamp` and `basetool_monitoring_reconcile_disabled` textfile
metrics) · `scripts/deploy.test.sh` (config-apply / self-heal / reconcile-disabled self-tests) ·
`monitoring/grafana/dashboards/13-meta-monitoring.json` (log-pipeline panels) · `monitoring/README.md`
(alert-response runbook).

### REQ-OBS-015 — Framework false-positive log noise is removed at the source, not muted

A framework log line that is a false positive for this application — correct behaviour the framework
merely warns about — must be eliminated at its source rather than silenced by raising a logger
threshold (muting hides genuine future messages from the same logger and leaves the dead machinery
running). The canonical case is Spring Data Web's `ProxyingHandlerMethodArgumentResolver`, which
inspects every `@ModelAttribute`-annotated handler parameter whose static type is an **interface**
and, when it is not a `@ProjectedPayload` projection, logs `… is not annotated with @ProjectedPayload
…` at WARN before correctly delegating to the standard resolver. The frontend's
`OrgUnitContextAdvice` cross-injects the already-loaded `List<SquadronDto>` /
`List<OrgUnitMembershipOptionDto>` catalogues between its `@ModelAttribute` methods so each is fetched
once per request and reused (re-deriving them in the dependent method would double the un-cached
`/api/v1/users/me` + `/memberships` round-trip on every non-admin request); `java.util.List` is an
interface, so the resolver emits that WARN for the `availableSquadrons` and `availableOrgUnits`
parameters.

The frontend does **no** Spring Data web binding at all — no `Pageable` / `Sort` / projection
parameters, and the backend is paged through the module's own `PageResponse` DTO — so that resolver
(and the rest of `@EnableSpringDataWebSupport`) is dead weight, present only because
`spring-data-commons` arrives transitively via `spring-data-redis` (the session store). The fix is
therefore to **exclude `DataWebAutoConfiguration`** in the frontend so the resolver is never
registered; the false positive then cannot be raised, and no application logger is muted. The
single-fetch `@ModelAttribute` injection is preserved unchanged. This applies to the frontend only —
the backend genuinely uses Spring Data web paging and keeps the auto-config.

**Acceptance**

- [ ] The frontend context contains no `ProxyingHandlerMethodArgumentResolver` in the
  `RequestMappingHandlerAdapter` resolver chain, so a page render can no longer emit the
  `@ProjectedPayload` WARN for the `OrgUnitContextAdvice` catalogue parameters.
- [ ] No application logger is muted to achieve this (the fix removes the resolver, not the log line).
- [ ] `OrgUnitContextAdvice` still fetches each catalogue at most once per request (the
  `@ModelAttribute` cross-injection is preserved, not replaced by an in-method re-fetch).

**Enforced by:**
`frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/FrontendApplication.java`
(`@SpringBootApplication(exclude = … DataWebAutoConfiguration.class)`) ·
`frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/FrontendApplicationTests.java`
(asserts the resolver is absent from the live chain) ·
`frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/config/OrgUnitContextAdvice.java`
(the single-fetch `@ModelAttribute` cross-injection the exclusion protects).
