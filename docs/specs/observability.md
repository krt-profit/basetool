> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-08-02.
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
one-line-per-request access log (`RequestLoggingFilter`, scoped to `/v1`) and carries
`correlationId` **and `userId`**, but no `orgUnitId` — it relays drafts and owns no
squadron-scoped data, so that field would be permanently empty.

The gateway's `userId` is populated in two steps, because its `CorrelationIdFilter` deliberately
runs **before** Spring Security so the bot-protection, size-cap and rate-limit filters are already
correlation-tagged. That filter seeds `userId=anonymous` and owns the MDC lifecycle (it is the only
place both keys are removed, which is what prevents bleed-through on a pooled or virtual thread);
`UserIdMdcFilter`, installed inside the security chain right after `BearerTokenAuthenticationFilter`,
then overwrites the seed with the caller's JWT `sub` and deliberately does **not** clear it, so the
subject survives into the access-log line emitted after the security chain has unwound. Pre-auth
rejections (413 / 429 / bot 404) therefore log `anonymous`, which is accurate — at that point no
caller has been authenticated.

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

**A client that closes an SSE stream is not an error, and gets no response at all.** Both long-lived
endpoints — `/api/v1/live-sync/stream` and `/api/v1/notifications/stream` — hand the container a
response that stays open for minutes, and every ordinary way a client leaves closes it mid-write: a
navigation, a closed tab, a phone whose screen went off, a proxy reaping an idle connection. Tomcat
reports the broken pipe and Spring wraps it as `AsyncRequestNotUsableException`. Without a handler of
its own it lands on the `Exception` fallback and costs **two** lines per disconnect: an `ERROR` with
a stack trace — feeding `logback_events_total{level="error"}` and `LogbackErrorSpike` on the most
routine thing an SSE endpoint experiences — and a `WARN` from Spring itself, because the
`ProblemDetail` that fallback returns cannot be written into a response whose content type is already
`text/event-stream`. In one 16-hour production window those two were **30 of the 50** WARN/ERROR lines
the backend produced. `GlobalExceptionHandler.handleDisconnectedClient` takes it at `DEBUG`, and its
`void` return type is the second half of the fix rather than an oversight: there is no socket left to
write to, and attempting to write one is what produced the second line.

The frontend's `GlobalExceptionHandler` applies the same expected-noise demotion to **asset-shaped
path-variable type mismatches**: when a request path whose final segment carries a filename
extension (e.g. `GET /missions/common-handlers.js`, a crawler resolving the shared script names of
`fragments/head.html` relative to a page URL) fails `UUID` path-variable conversion, the handler
renders the **404** error page (such a path names no resource — a 400 would mislabel it) and logs at
`DEBUG`, not `WARN`. Both keys are required — target type `UUID` *and* the dotted final segment — so
a genuinely malformed id on a real navigation (e.g. a truncated pasted link, no dot) keeps its
`400` + `WARN` signal, as does every non-UUID type mismatch. The rejected parameter value itself is
never logged at any level (REQ-OBS-004: it may carry PII).

**An unparseable request is the client's fault and is logged as such.** A query-string chunk with
an empty parameter name (`GET /?=phpinfo()`, a stock PHP-CGI scanner probe) makes Tomcat's parameter
parser throw, and it throws on the *first* `getParameter*()` call of the request — which in the
frontend is `LocaleChangeInterceptor` reading `?lang`, i.e. on **every** request regardless of what
it was addressed to. Left on the `Exception` catch-all it produced a `500` and an `ERROR` line with a
~200-frame stack trace; worse, the resulting `/error` dispatch re-read the same query string and threw
again, so one probe cost two ERROR lines. Three of them arrived inside eight seconds on 2026-08-27 —
`logback_events_total{level="error"}` and `LogbackErrorSpike` cannot tell that from a real fault.
Two changes, in that order: `BotProtectionFilter` rejects the malformed query string at the edge
with a **bare 400** (`setStatus`, never `sendError` — the error dispatch is the loop) before anything
parses parameters, counted as `basetool_bot_blocked_total{rule="query_string"}` and logged at `DEBUG`
like its sibling rules; and `GlobalExceptionHandler.handleInvalidParameter` maps whatever still gets
through — a percent-escape that fails to decode (`?q=100%`, reachable by a real user pasting a
truncated link), a `maxParameterCount` breach, a malformed `POST` body — onto a clean `400` at
`DEBUG`. The exception message is never logged at any level: it quotes the offending chunk verbatim,
i.e. raw attacker-controlled bytes that may contain line breaks (CWE-117, REQ-OBS-004).

**A masking keyword only counts when a separator follows it.** `PiiMasker`'s keyword rule
(`bearer` / `token` / `session-id` / `authorization`) previously treated the `:`/`=`/whitespace
separator as optional, so the keyword matched **inside** any identifier containing it and the value
class then ate the rest. In the centralized log a stack frame
`GuestEditTokenContextFilter.doFilterInternal(GuestEditTokenContextFilter.java:70)` arrived as
`GuestEditToken***(GuestEditToken***:70)` and `…intercept.AuthorizationFilter.doFilter` as
`Authorization***` — the frames an incident is triaged from, destroyed by the masker rather than by
the failure. The separator is now required. Nothing is unmasked by this: a secret is logged as
`token=x`, `token: x`, `token x` or `Bearer x`, never as `tokenx`; a keyword-suffixed field name
(`guestEditToken=…`) still masks, because the narrowing is about the separator, not about where the
keyword sits. All three module copies of the masker carry the change.

**A scheduled job run is one correlated unit.** A scheduler thread carries no request, so
`CorrelationIdFilter` never runs for it and every line the eight `@Scheduled` jobs emitted had an
empty `correlationId` — with overlapping schedules, a nightly window interleaved several jobs with
nothing to say which run a line belonged to. `TaskMetrics` (the wrapper every job already runs
through) now stamps `<job-label>-<8 hex>` into the MDC for the duration of the run and removes it
afterwards, so the label greps to all runs of a job and the full id narrows to one. An id that is
already present is left alone: the admin-triggered manual run (`recordCountingRethrow`) executes
inside a request that owns a real correlation id, and overwriting it would sever the trigger from
its HTTP call.

**A fail-closed login rejection names its cause.** The Keycloak SPI's `DiscordMembershipChecker`,
`BackendAccountChecker` and `DiscordGuildNicknameReader` collapsed every failure — timeout, DNS,
Discord 429, upstream 5xx, unreadable body — into an opaque `DENIED_ERROR` / `UNKNOWN` with no log
line at all, leaving `Discord membership gate denied login (reason=DENIED_ERROR)` as the only trace
of a user who cannot get in. Each of those paths now logs its cause (exception class or HTTP status)
via JBoss Logging, the SPI's runtime logger. The `BackendAccountChecker` matters for the opposite
reason: it fails **open**, so a silent failure means the duplicate-account check simply did not run
and nothing recorded that. Never logged: the brokered token, the request URL (it carries the guild
id) or any user identifier.

**Every ingest rejection leaves a line naming its cause.** The gateway is the surface a separately
released desktop client codes against, so "why did my send fail?" has to be answerable from the
gateway log alone:

- **Validation reject** — `WARN` with the failing field paths and constraint messages (schema text).
  The **rejected value** is never logged, and the joined string is sanitised (see below).
- **Unreadable body** — `WARN` with the exception class only; Jackson's message quotes the offending
  part of the body, which here is a user's extract.
- **413** — `DEBUG` with both the declared body size and the configured cap, so a cap set below what
  a legitimate extract needs is distinguishable from a hostile body (a chunked reject reports
  `declared=-1`, which is itself the diagnostic).
- **429** — the *per-subject* limiter (the enforceable one) logs `WARN` with the budget and the
  advertised `Retry-After`; the *per-IP* pre-auth limiter logs the same at `DEBUG`, because an
  attacker decides how often it fires and a higher level would be a log-flood vector. Absence of the
  `WARN` on a 429 therefore identifies the per-IP limiter, and the `bucket`-tagged counter
  distinguishes them in the metrics either way. Neither line repeats the subject (it is the `userId`
  MDC field) or the client IP (app logs stay PII-free — REQ-OBS-004).
- **401 / 403** — `DEBUG` and `WARN` respectively, per the same expected-noise reasoning the backend
  applies; see REQ-API-004 for the response shape.
- **Backend 4xx** — `DEBUG`, so the gateway log distinguishes "the backend rejected it" from "our own
  validation rejected it" without duplicating the `WARN` the backend already emitted.

**The accepted payload's shape is logged, its content never is.** Before relaying, the gateway
records `schemaVersion`, the producing tool/version and the order / goods-row / source-image counts
(or, for the opaque blueprint export, its byte count). The gateway interprets nothing, so these
counts are the only handle on what a client actually pushed. No screen read, material name or
quantity is logged. The client-supplied provenance strings pass through `LogSafe`, which replaces
ISO control characters and caps the length — a `\n` inside a JSON string would otherwise let an
internet-facing caller forge a second, fabricated log line, and neither the logback pattern nor
`PiiMasker` strips it. `LogSafe` complements `PiiMasker` rather than replacing it: the masker removes
secrets from what reached the appender, `LogSafe` removes structure-breaking characters before the
value is handed to the logger.

The gateway carries the same log-once discipline on its **outbound** relay. `WebClientLoggingFilter`
emits one line per backend call with method, host, path, status and elapsed time — `INFO` normally,
`INFO` with the `Slow backend call` marker past `app.logging.slow-backend-call-threshold-ms`
(1500 ms, issue #1204 — relay latency is alerted on through the `http.client.requests` p95
histogram, not by escalating this line), and `DEBUG` for a backend 5xx or any error signal, because
`GlobalExceptionHandler` already owns the single operator-facing `WARN` for those. The exception
message is never logged (it can carry the target URL) and the forwarded bearer never appears at any
level.

`RequestLoggingFilter` escalates the access-log line to `WARN` (`Slow request …`) when a request
exceeds `app.logging.slow-request-threshold-ms` (2000 ms) — in **all three** modules — **except the
notification SSE relay**
(`/api/v1/notifications/stream` on the backend, `/notifications/stream` on the frontend), which stays
at `INFO`. Spring MVC books an async request's whole lifetime as its elapsed duration, so a relay
held open for up to 30 minutes would cross the threshold on **every** close and flood the access log
with false-positive `Slow request` WARNs — the log-side twin of the `http.server.requests` latency
skew that `NotificationStreamObservationPredicate` already suppresses (REQ-OBS-009). The relay still
emits its single `INFO` access-log line, so the one-line-per-request guarantee holds; relay health
stays visible through the dedicated SSE meters, not the access log.

**The frontend binds `orgUnitId` too.** The MDC contract above names the field for backend *and*
frontend, but until 2026-08 only the backend ever bound it: the frontend's Logback patterns rendered
no third bracket and its prod JSON encoder exported no such key, so a frontend line could not be
attributed to an org-unit context at all — the spec and the code disagreed.
`ActiveSquadronContextFilter` now binds the active-pin UUID, or the literal `none` when the user has
no pin, and removes the key in a `finally` so a pooled or virtual thread cannot carry it into the
next request. **Filter order is load-bearing:** `CorrelationIdFilter` runs at
`LOWEST_PRECEDENCE-100` and `ActiveSquadronContextFilter` at `-99`, because the pin is not resolvable
yet inside the correlation filter — binding it there would have written `null` on every request.
Both Logback text patterns (console + file, `[%X{orgUnitId:-}]` after `userId`, so an unpinned
request keeps the column as an empty bracket pair) and the prod `PiiMaskingLogstashEncoder` carry the
field. The ingest carve-out above is unchanged: the gateway relays drafts and owns no
squadron-scoped data.

**A filter-level rejection names its subject.** The two backend rejections that short-circuit before
the servlet is reached — `SecurityProblemResponseHandler` (the Spring Security 401/403) and
`PendingApprovalAccessFilter` (the REQ-SEC-017 403) — already minted their own `correlationId`
(REQ-OBS-002) but left `userId` unset, so the security-relevant 403 "authenticated but not allowed"
line could not be attributed to anyone. Both now stamp the JWT `sub` into the `userId` MDC for the
duration of the rejection write and remove it in the **same** `finally` that removes the minted
correlation id. The stamp is skipped when the authentication is not a JWT (there is nothing to name —
an anonymous 401 stays unattributed, which is accurate) and when the key is already owned further up
the chain (the pre-existing value wins and is never overwritten). Levels are unchanged: the 403 stays
`WARN`, the 401 stays `DEBUG`. The `sub` UUID is the only identifier permitted here (REQ-OBS-004).

**Client free text is sanitised in all three modules, not only at the gateway.** The `LogSafe` guard
described above for the ingest gateway now exists as a module-local twin in the backend
(`backend…logging.LogSafe`) and the frontend (`frontend…logging.LogSafe`) with a byte-identical
contract: `text(value, maxLength)` truncates to the cap first, replaces every
`Character.isISOControl` character with `?`, appends the truncation marker only when the *original*
exceeded the cap, and renders null/blank as the stable token `none`. There is no shared module
between the three, so this is a deliberate triplicate rather than a missed extraction. The realistic
actor here is not an internet caller but an authenticated squadron member — or a guest holding an
edit link — typing into a search box, a filter or a form field: a pasted newline plus a fabricated
`ERROR ---` prefix reads as a genuine line during incident triage (CWE-117), and neither the Logback
pattern nor `PiiMasker` strips it. Every call site that hands a client-supplied string to a logger
routes through it — the type-ahead query parameters, a rejected live-sync section key and topic, a
relayed personal-blueprint `productKey`, a promotion-category name, the client-error beacon's message
and source. `LogSafe` complements the two existing utilities rather than replacing either:
`PiiMasker` strips secrets from a line that already reached the appender, `LogMasker` redacts a
known-sensitive value at the call site, `LogSafe` removes structure-breaking characters before the
logger sees the value; a value that is both sensitive and user-supplied needs a masker **and**
`LogSafe`. Sanitising is not a licence — a value forbidden by REQ-OBS-004 (callsign, name, e-mail,
token, client IP) stays forbidden after passing through it.

**The levels of the lines added in the 2026-08 logging audit**, with the reasoning that fixed each
one. The governing rule is the one stated above: anything a client or an attacker can trigger at will
is `DEBUG`, because at any higher level it is a log-flood vector; an operator-actionable fault is
`WARN`; a once-per-run summary is `INFO`.

*Backend.*

- **SC-Wiki census completeness** (`ScWikiClient`) — `WARN` when a **full** page 1 carries no
  `meta.last_page`, when a page fails mid-walk, when the walk was served the same row twice, when
  the **distinct** row count falls short of `meta.total`, or when the feed announces more pages by
  the end of the walk than page 1 did; each also bumps the fetch-error counter and marks the walk
  **incomplete**. A genuinely short single page stays `complete` and silent, which is what keeps the
  WARN off healthy runs. A **surplus** — more distinct rows than `meta.total` claims — is `INFO`,
  not `WARN`, and stays `complete`: it cannot hide a row from a sweep, and `/api/items` produces one
  on every run (12 331 distinct rows against a stated 12 283). Reading it as a census failure kept
  the item backfill's orphan sweep suppressed on every nightly run and fired
  `ScWikiCensusIncompleteStreak` daily on a defect in the check — REQ-DATA-014 / ADR-0147. The
  closing census line is one `INFO` per walk reporting merged vs. distinct rows and pages fetched
  vs. announced plus the `complete` flag. The flag is not cosmetic: `ScWikiOrphanSweep` (and the
  inline sweeps in the commodity/blueprint/item sync services) **refuse to tombstone** on an
  incomplete walk and WARN why — a truncated census previously read as "the rest of the catalogue
  was deleted".
- **UEX fetch completeness** (`UexClient` / `UexItemSyncService`) — the client's `FetchResult` now
  also carries `complete`, and the item sync `WARN`s once per run when any per-category fetch came
  back incomplete (failed, non-`ok` envelope, or `304`) and the `uex_deleted` sweep therefore stood
  down. No new meter: the failure branch already increments
  `basetool_external_fetch_errors_total{source="uex"}`, so a category that keeps failing shows up in
  `ExternalFetchErrors` while the WARN names which run lost its orphan detection (REQ-DATA-014).
- **UEX envelope outcome** (`UexClient`) — exactly **one** line per 2xx: `INFO` with the row count
  and the envelope status on a healthy response; `WARN` plus a fetch-error counter when `data` is
  `null` or when `status` is present and not `ok` (the rows are still returned). A blank/absent
  `status` is deliberately **not** an anomaly — no code ever read the field, so it cannot be asserted
  that all ~20 endpoints populate it.
- **UEX `304 Not Modified`** — raised from `DEBUG` to `INFO`, both in `UexClient` and at each sync
  service, which now logs `unchanged (304) — nothing to import.` and returns **before** any upsert or
  sweep. An unchanged catalogue is the normal healthy outcome of a nightly run and must be readable
  without raising a level mid-incident; the volume is bounded by the schedule, not by callers.
- **Role-sync summary** (`UserReconciliationService.logRoleSyncSummary`, `UserSyncService`) — one
  aggregate `INFO` per run (roles mapped, Guest fallbacks, dropped role names, accounts newly flagged
  as departed), escalating to `WARN` past a threshold (more than 3 Guest demotions, more than 10
  newly-missing accounts). Per-account detail stays `DEBUG` and carries the `sub` UUID only: a full
  roster at `INFO` would be a per-run flood and would name accounts.
- **Keycloak role-mapping summary** (`KeycloakService`) — `INFO` with matched / mappable / realm-role
  / holder counts; `WARN` **only** in the degenerate `matched == 0` case. A naive per-role "missing
  from the realm" WARN would fire on every single run, because the mappable set is the entire local
  catalogue including the seeded local-only `Guest` role.
- **SSE send failure and cap eviction** (`NotificationStreamService`) — `DEBUG`, deliberately not
  raised. A broken pipe arrives on every browser tab close, and a user opening a sixth tab evicts
  their oldest emitter by design; both are client-paced. The throwable is now passed to the logger
  (it was discarded), and the signal lives in the `cause`-tagged counter and the new eviction counter
  (REQ-OBS-011).
- **Optimistic-lock 409** (`GlobalExceptionHandler`) — level unchanged (`WARN`); the line now carries
  `entity`, `entityId` and `versions` (`expected=<client> persisted=<persisted>`), degrading to the
  exception text alone for the bare JPA variant that names no entity. Numbers and ids only. All
  `support.OptimisticLock` call sites pass a `UUID`, an `entity.getId()` or `null`; the single
  exception (`SystemSettingService`) passes a setting key that must already have matched a persisted
  row, so it is a bounded seeded key and not free text.
- **Rate-limit rejection** (`RateLimitingFilter`) — level unchanged (`DEBUG`, attacker-paced); the
  line now reports `keySource=peer|forwarded` **instead of the client IP** (REQ-OBS-004), and the
  same bounded value tags the rejection counter. An empty leading `X-Forwarded-For` element falls
  through to `peer` rather than keying on the empty string while claiming `forwarded`.
- **Role permission change** (`RoleService.updatePermissions`) — `INFO`, a rare admin mutation. It
  names the added/removed permission keys filtered through the fixed `Permissions` vocabulary and the
  actor's `sub`; an out-of-vocabulary value submitted by the client is applied but never named, which
  also keeps client free text out of the logger. The matching audit event is REQ-AUDIT-001.

*Frontend.*

- **Login failure** (`LoginFailureMetricsHandler`) — `WARN` for the `provider_error` bucket (a
  genuine post-authorization token/IdP break), carrying the bucket, the length-capped and
  control-stripped OAuth2 error **code** and the hop-bounded root cause's class simple name; `DEBUG`
  for `invalid_state` and `other`, which are driven by unauthenticated bots hitting the bare callback
  and by the `prompt=none` silent-SSO probe that fires on every unauthenticated navigation. The error
  **description**, the authorization `code`, the `state`, tokens and the principal never reach the
  logger at any level.
- **Session eviction** (`SessionEvictionLoggingStrategy`) — `WARN`. A user reached the
  10-concurrent-session cap and an older session was destroyed: rare, user-visible and worth a line.
  It carries the cap and the `userId` MDC (falling back to `unknown`), never the principal name and
  never a session id. The victim-facing response body is byte-identical to Spring Security's default.
- **Unrelayed active-org-unit pin** (`ActiveSquadronRelayFilter`) — `DEBUG` on the previously silent
  null branch, logging the method and the **path only**. The query string is deliberately excluded:
  it carries type-ahead terms.
- **Type-ahead catalogue-fetch failures** (the personal-inventory, personal-blueprint and
  admin-default-blueprint page controllers) — demoted from `WARN` to `DEBUG`. These fire once per
  keystroke on a search field, which is the textbook flood vector. The admin catch-all
  `catch (Exception)` branch stays `ERROR`; the query is wrapped in `LogSafe`.
- **Live-sync section filtering** (`LiveSyncWebSocketHandler`) — `DEBUG`, **one line per frame**,
  carrying the reject count and the first rejected key through `LogSafe`; an unknown changed-topic
  likewise gets one sanitised `DEBUG` line where it previously returned silently. Both are keyed on
  client-supplied values arriving at socket rate.
- **Live-sync subscribe verdicts** — an explicit backend 403/404 deny and a withheld capability stay
  `DEBUG` in `LiveSyncSubscriptionAuthorizer` (routine authorization outcomes). The **fail-closed
  indeterminate** deny (a 401/5xx/null-token probe on a presence-enabled class) and the previously
  **unlogged** `RejectedExecutionException` executor-saturation branch are `WARN`, one line each,
  emitted by whichever component produced the verdict — `completeSubscribe` deliberately logs nothing
  so the same denial cannot be reported twice.
- **Access log** (`RequestLoggingFilter`) — level unchanged; the single line gains a
  `[fragment=… ajax=true]` suffix so a fragment refresh is distinguishable from a full page load.
  `fragment` is read by scanning the raw query string for the literal `fragment=` prefix — never
  `getParameter`, which would consume a POST body in the `finally` block after the response is
  committed — left percent-encoded and passed through `LogSafe`; `ajax` comes from
  `X-Requested-With`. No second line and never the whole query string. `http.server.requests` cannot
  answer this: it is keyed by URI template and collapses `?fragment=` into the page-load bucket.

**Browser-side faults reach the server, at `DEBUG`.** A JavaScript exception after a fragment swap
left no trace anywhere — the user saw a dead panel and the operator saw a clean log. A small beacon
(`static/js/krt-client-error.js`, loaded as the first, non-`defer` script so it is installed before
anything it watches) listens for `error` (capturing, so non-bubbling subresource failures are seen)
and `unhandledrejection` and POSTs to **`POST /internal/client-error`**
(`ClientErrorReportController`, authenticated). Binding constraints on that surface:

- **`DEBUG` only**, on both the accept and the reject path. The endpoint is reachable by every
  authenticated user, so any higher level hands a caller a one-request log-flood — and a malformed
  body must not reach `GlobalExceptionHandler`'s `Exception` catch-all, which would hand the same
  caller a one-request `ERROR`-log generator and trip `LogbackErrorSpike`; a controller-local
  `@ExceptionHandler` answers a bare 400 at `DEBUG` instead.
- **The record shape is the input allowlist** — exactly `{message, source, line, column, kind}`, each
  field capped at 200 characters, both free-text fields through `LogSafe`. Never a stack trace,
  never `document.title`, DOM content, form values or `location.search`; `source` is stripped of its
  query and fragment on both sides.
- **The `kind` is resolved server-side** against the three bounded literals (REQ-OBS-011); an
  unknown value is rejected with 400 and creates **no** meter series, rather than merely a zero
  count. `userId` comes from the MDC only.
- Client-side a per-session token bucket (capacity 5, refill 1/60 s, persisted in `sessionStorage`
  so an F5 storm cannot refill it) plus an in-flight guard collapse a same-round-trip error storm.

### REQ-OBS-002 — Correlation-id propagation

`correlationId` comes from the inbound `X-Correlation-Id` header (configurable via
`APP_LOGGING_CORRELATION_ID_HEADER`) or a generated UUID, and is echoed in the response
header. The frontend's `WebClientLoggingFilter` propagates the same id to outbound backend
calls so both modules share one id per user interaction. `userId` is the JWT `sub`, or
`anonymous`.

All three modules bind these settings from the same `app.logging.*` keys through a `@Validated`
`LoggingProperties` — `correlation-id-header`, `correlation-id-mdc-key`, `user-id-mdc-key`,
`slow-request-threshold-ms` and `structured-enabled` everywhere, plus `org-unit-id-mdc-key` on the
backend and `slow-backend-call-threshold-ms` wherever a module calls the backend (frontend, ingest).
A blank MDC key or a negative threshold aborts the boot rather than silently emptying the `%X{…}`
fields. An inbound id that is not `[A-Za-z0-9._-]{1,128}` is discarded and replaced by a fresh UUID,
so it can neither forge a log line nor a response header. The ingest gateway relays the id onward
under the **configured** header name, so re-pointing `APP_LOGGING_CORRELATION_ID_HEADER` moves the
inbound, echoed and relayed header together.

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
is tailed from the file (`loki.source.file` → `app_json`), no longer via the Docker log API. Its
patterns and JSON encoder carry `correlationId` **and `userId`** (REQ-OBS-001).

All three modules also render `[%X{traceId:-},%X{spanId:-}]` in their console and file patterns,
following Spring Boot's own default layout. The JSON appender already carried both fields, so
without this a text line — the console stream and `logs/<app>.log` — was the one place from which a
trace could not be reached while tracing was enabled. The cost is a literal `[,]` on every line
while tracing is off (the default), which is the accepted trade for the link existing when it is
needed.

**All three modules** log a **startup banner** on `ApplicationReadyEvent`
(`StartupBannerListener`) naming the effective runtime configuration an on-call engineer reaches for
first: active profiles, the upstream/downstream URLs, the identity-provider issuer and the effective
logging knobs. Secrets are never printed, and any endpoint that can carry inline credentials is
sanitised before it is logged (the backend's JDBC URL, the frontend's and gateway's Redis endpoint).
The frontend's matters most: a wrong `BACKEND_URL` or Keycloak issuer does not fail its boot, it
surfaces much later as a login loop or a page of 502s.

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

> **Amended again by ADR-0134 (backend, prod):** the backend now does the same on port `11271`, so
> **all three** modules serve Actuator on an internal-only management port and **no** module keeps
> the basic-auth compensating control. ADR-0090 excluded the backend because it was not
> internet-reachable; the public API vhost removes that premise, and the connector a proxy forwards
> MUST serve no Actuator. The bullets below therefore now describe **dev/test/e2e only** (no
> management port set), where `MonitoringScrapeSecurityConfig` still fail-closes the app-port path
> unchanged.
>
> The backend's permit-all chain is **narrower** than the other two: it enumerates
> `/actuator/health`, `/actuator/health/**`, `/actuator/prometheus` and `/actuator/info` instead of
> `/actuator/**`. Every other Actuator path keeps the main chain's protection, which is what lets the
> backend retain the `ROLE_ADMIN` gate on `POST /actuator/loggers/**` (REQ-OBS-016) rather than
> deleting the write the way frontend and ingest must. Widening that matcher would silently un-gate
> the mutator; `ManagementPortIsolationTest` measures all of it — Actuator absent from the
> application connector, the read endpoints served unauthenticated on the management port, and the
> mutator answering 401/403 there.
>
> **Acceptance**
>
> - [x] `/actuator/health` and `/actuator/prometheus` answer 404 on the backend's application
>   connector in prod, independent of any edge deny.
> - [x] Prometheus scrapes `backend:11271` over HTTPS with the pinned CA and **no** credentials.
> - [x] `POST /actuator/loggers/**` on the management port is refused without `ROLE_ADMIN`.
> - [x] Dev, test and e2e are unchanged: no management port, Actuator on the app port, fail-closed.
>
> **Enforced by:** `ManagementPortIsolationTest` (backend, frontend, ingest) · **Code:**
> `ManagementPortSecurityConfig`, `application-prod.yml`, `docker-compose.yml` (healthcheck
> override), `monitoring/prometheus/prometheus.yml`

**"Dev/test/e2e are unchanged" is a claim about the compose stacks, not just about the YAML files,
and it has to be kept true on purpose.** A service running the `prod` Spring profile serves
`/actuator/**` on the management port *only*, so the app-port `HEALTHCHECK` baked into the image
answers `404` and the container can never become healthy. Nothing about that is loud: the app does
not crash and logs no error, it simply sits `unhealthy` forever while everything with a
`condition: service_healthy` on it waits. Two things therefore move together for every service in
every stack — **the effective Spring profile and the effective probe**. A prod-profile service needs
the compose `healthcheck` override onto its management port (the `backend` / `frontend` / `ingest`
services each carry one); a dev-profile service needs its profile to actually *be* `dev`, because it
inherits the image's app-port probe and no override corrects it.

The trap is inheritance. The `x-backend` / `x-frontend` / `x-ingest` anchors carry
`SPRING_PROFILES_ACTIVE: prod`, and a service that merges an anchor and then declares its own
`environment:` **replaces that map wholesale** — YAML merge does not deep-merge — so for a long time
the `*-dev` services declared no `environment:` at all and silently ran the prod profile. Every one
of them was therefore probing the wrong port: `ingest-dev` in the isolated test stack sat
permanently `unhealthy`, and in the plain `--profile dev` stack all three did, which also meant
`frontend-dev` and `ingest-dev` never started at all because both gate on
`backend-dev: condition: service_healthy`.

Both halves are fixed in `docker-compose.yml`: each template's `environment` map is anchored
(`&backend-env` / `&frontend-env` / `&ingest-env`), and each `*-dev` service re-merges it and
overrides `SPRING_PROFILES_ACTIVE: dev` (plus `REDIS_HOST: redis` for `frontend-dev`, whose dev
profile reads `${REDIS_HOST:localhost}` where only `application-prod.yml` hardcodes the alias).
`docker-compose.test.yml` keeps its own explicit pins on top. **A new `*-dev` service must do the
same** — merging the anchor without re-declaring the profile reintroduces exactly this failure, and
it is invisible until something waits on the container's health.

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
- **Keycloak file log** (`app="keycloak"`) — masked **in the shipper** (Alloy stages scrub
  `username=` / `ipAddress=` before ingestion).
- **Keycloak container stdout** (`app="keycloak-stdout"`) — Keycloak runs `--log=console,file`, so
  its console carries the same lines as the masked file log plus the JVM/container-level output that
  never enters the file at all (the ADR-0095 motive, applied to the identity provider). The
  container keep-regex is **anchored** so `keycloak-dev`, `db-keycloak` and `postgres-exporter-keycloak`
  do not match. It gets its own mapping rule and its own `loki.process` block with the `username=` /
  `ipAddress=` replaces copied byte-identically from the file mask — without them the console copy
  would re-introduce exactly the PII the file mask exists to remove. It is deliberately **not** in
  `KeycloakErrorRateHigh`'s selector, so the console copy cannot double-count the file stream's
  ERRORs. Extending the ADR-0095 stdout pattern to Keycloak is owner-approved; ADR-0095 (or an
  amendment) records it.
- **Ops-automation host logs** (`app="ops-deploy"` / `"ops-backup"` / `"ops-cleanup"` /
  `"ops-restore-drill"`) — the four systemd units' own log files under the existing
  `/var/log:/hostlog:ro` mount (`iri-deploy.log`, `iri-backup.log`, `iri-docker-cleanup.log`,
  `iri-restore-drill.log`). Motive: the units write with `StandardOutput=append:`, which **replaces**
  journald rather than teeing to it, so `journalctl -u iri-deploy.service` carries only systemd's own
  unit records — the script output that explains *why* a deploy rolled back was reachable over SSH
  and nowhere else, which is exactly the wrong triage path for a 03:40 alert mail. All four route
  through `loki.process.ops_automation_mask`: the three `<svc>-stdout` replaces (JWT / e-mail /
  bearer keyword) plus a fourth anchored on the deploy script's registry-login sentence, so an image
  reference that happens to contain `" as "` is untouched and the registry host stays readable.
  PII-free operational logging → global 744h retention, no REQ-OBS-010 IP-retention impact. Every
  `ops-automation.yml` alert description now carries the LogQL query for its stream, since that
  description is what reaches the operator's mailbox. **Precondition:** Alloy runs with
  `cap_drop: [ALL]` and therefore no `CAP_DAC_OVERRIDE`, so each log file must exist as
  `0640 deploy:adm` before the unit first writes it — a `0640 root:root` file ships silently empty.
  `docs/deployment.md` pre-creates the deploy and cleanup logs; the backup and restore-drill logs are
  a known gap in `docs/backup.md` and are also why those two streams carry no liveness guard
  (REQ-OBS-014).
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
  lines cannot leak row data. Both instances additionally run
  `log_line_prefix='%m [%p] %q%u@%d/%a '` (2026-07-29) so **every** line is self-attributing:
  terse strips the `HINT`, and Postgres' default `%m [%p] ` prefix identifies no sender, which
  left a bare `ERROR: column "…" does not exist at character N` indistinguishable between the
  application, `postgres_exporter` and an interactive `docker exec psql` forensics session —
  they all authenticate as the same `POSTGRES_USER` role. `%a` (`application_name`) is the
  discriminator: `psql` for an ad-hoc session, `PostgreSQL JDBC Driver` for the backend's Hikari
  pool, `postgres_exporter` for the scraper. `%q` suppresses the session fields for
  non-session processes so startup/checkpointer lines stay clean. The prefix adds **no row
  data and no end-user identity** — `%u`/`%d` are service-account and database names — so the
  terse PII guarantee and REQ-OBS-004 are untouched. `PostgresFatalOrPanic` matches with plain
  LogQL line filters, not a prefix-anchored regex, so it is unaffected; a future prefix change
  must re-check that.
- **Monitoring-plane container stdout** (`app="mon-<service>"` for the monitoring services —
  Prometheus, Grafana, Loki, Tempo, Alloy, Alertmanager, the exporters, the socket proxy; #1041
  item 24) — shipped so a misbehaving monitoring component (Grafana and Tempo have OOM-looped in
  prod) leaves evidence in Loki rather than only in the rotation-capped host docker-json logs. Two
  streams carry PII and are masked **in the shipper**: `mon-grafana` (Keycloak-OIDC admin logins
  log `uname=` + e-mail) and `mon-alertmanager` (SMTP-failure lines carry the recipient e-mail),
  both scrubbed by Alloy `stage.replace` (gated by a `stage.match` on the app label) mirroring the
  Keycloak mask. The streams inherit the global 744h retention — no REQ-OBS-010 IP-retention impact
  once masked.
- **Idle-container stale-line drop guard (2026-07-19).** Every docker-shipped container stream (the
  `<svc>-stdout`, `mon-<service>`, `npm`, `postgres-*` streams above) flows through
  `loki.process.container_mask`, whose first stage drops any entry older than **167h**
  (`stage.drop older_than`, a 1 h guard below Loki's `reject_old_samples_max_age` of 168h). A near-idle
  container keeps its last stdout line at the tail, and `loki.source.docker` re-delivers that same line
  on every tailer reconnect (`could not transfer logs: unexpected EOF`); while it is younger than the
  reject window Loki silently dedupes the repeat, but once it ages past 168h every re-delivery is
  400-rejected (`entry has timestamp too old`) and counted as a dropped entry, firing `LokiWriteFailing`
  continuously (the 2026-07-18 `redis-exporter` incident — line frozen at `2026-07-11T20:05:39Z`,
  rejected from exactly +168h onward, with the two `postgres-*` exporters and `mon-alertmanager` queued
  to hit the same wall days later). Dropping such week-old operational stdout at the source is lossless
  (Loki would reject it regardless) and generic across every current and future idle container. The file
  streams (npm/host-auth and the 31-day IP-retention paths) do **not** pass through this processor and
  stamp their timestamp at read time = now, so their deliberate retention is unaffected.
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
  downgrade). Ingested unmasked so the acting user stays attributable. `AuditdSshTamper` must match
  **`type=SYSCALL` only**: auditd stamps each rule's `key=` onto its own `add_rule` / `remove_rule`
  `CONFIG_CHANGE` records as well, so an unscoped `key=` match turns every auditd restart — the
  nightly unattended `libc6` upgrade restarts it — into a tamper page with no file actually touched
  (2026-07-28 fix). Both watches are directory-wide (`-w /root/.ssh/`, `-w /etc/ssh/sshd_config.d/`),
  so a benign `known_hosts` append by an outbound `ssh` is a genuine, attributable hit and stays in
  scope on purpose.
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
  process a countable batch — `basetool_scheduled_job_items_total{task}` counter for the eight
  wrapped jobs (`user_sync`, `notification_retention`, `default_blueprint_provisioning`,
  `bank_ledger_integrity`, `job_order_integrity`, `uex_sync`, `scwiki_sync`, `business_metrics`) via `TaskMetrics` (`record`
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
  > violations gauge freezes and `BankLedgerIntegrityViolation` cannot fire),
  > `JobOrderIntegritySweepStale` (`job_order_integrity`, > 6 h — same frozen-gauge trap for
  > `JobOrderItemBlueprintDrift`) and `BusinessMetricsStale`
  > (`business_metrics`, > 10 min); it is registered on a job's first success, so a job that has not
  > yet succeeded in this process publishes nothing at all rather than a falsely-stale `0` (see the
  > never-succeeded-sentinel bullet below). The items counter is present only for jobs that report a count: user sync,
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

- **Never-succeeded sentinel on the last-success gauge (2026-08-10).** The gauge is registered on a
  job's **first success**, seeded with that timestamp — never at run start, and never with a `0`. All
  six staleness rules subtract it from `time()`, so a published `0` reads as a success on 1970-01-01:
  an age of ~1.79 × 10⁹ s, above *every* threshold in `business.yml`. The holder is a per-process
  `AtomicLong`, so registering it when a run *started* published `0` for the whole duration of each
  job's first run after a backend restart. The prod SC-Wiki sweep takes ~10–15 min
  (`sync-all-items`), longer than `ExternalSyncStale`'s `for: 10m` — so that alert fired on every
  backend restart and cleared itself when the sweep finished. Registering on first success leaves the
  series **absent** until there is a real timestamp (subtracting an absent series yields an empty
  vector, so no rule fires), which also preserves the original intent that a config-gated-off job
  never reports a falsely-stale `0`. As defence in depth — alert rules reload without a backend
  deploy — the six rules and the operations dashboard's age panel filter the sentinel out inside the
  subtraction:

  ```promql
  (time() - (basetool_scheduled_job_last_success_timestamp_seconds{task="scwiki_sync"} > 0)) > 172800
  ```

  This is covered by `monitoring/prometheus/tests/staleness_never_succeeded_sentinel_test.yml`.
  **Triage corollary:** a staleness alert that resolves on its own within minutes is not staleness at
  all — a genuine one persists until someone acts.

- `basetool_sync_events_total{source,event_type}` counter at the three `SyncReportService`
  `log*Event` write sites (`source` = `SyncSourceSystem`, `event_type` = `SyncEventType`; both
  bounded enums — never the external asset name/uuid/detail).
- `basetool_external_fetch_errors_total{source}` counter incremented where the `UexClient` /
  `ScWikiClient` swallow an upstream fetch or parse error into an empty result (`source` = the fixed
  literal `uex` / `scwiki`). Because every upstream failure is mapped to an empty list and the sync
  job still records a success, this is the only signal of a sustained catalogue outage; it backs the
  `ExternalFetchErrors` alert. The backend `WebClient.Builder` is wired to the `ObservationRegistry`
  (REQ-OBS-009) so these same calls also emit `http_client_requests_seconds` + client spans.
  **Known gap, to close:** since the 2026-08 audit this one undifferentiated series carries three
  distinct causes — a transport failure, a non-`ok` envelope `status`, and the SC-Wiki
  pagination/`total` anomalies (REQ-OBS-001) — so the counter can no longer say *what* failed. The
  fix is a bounded `reason` tag (`transport` / `bad_status` / `pagination`) added to `MetricNames`;
  it splits the existing series, so the `{source=…}` panels and the `ExternalFetchErrors` rule need
  a `sum by (source)` review in the same change.
  **`data: null` is not a fetch error (2026-08-03).** A fourth cause used to feed this series — a
  `UexClient` `200` whose envelope `data` was absent — on the assumption that it could only mean a
  renamed field or an error document dressed as a success. The live API disproves it: UEX returns
  `{"status":"ok","http_code":200,"data":null}` for a query that legitimately matches nothing, and
  reports a genuine rejection as an empty *array* under a non-2xx code (`data: []` with
  `http_code: 400`) — which never reaches the envelope audit, because every non-2xx is already
  routed into the counting transport fallback. Errors carry `[]`; empty successes carry `null`. Two
  real but permanently empty item categories (12 `Clothing/Jumpsuits`, 69 `Consumable/Consumable`)
  therefore booked two bogus increments on *every* `uex_sync` run. That per-run baseline of 2 sits
  under the `> 3` threshold on its own, but the counter resets when the backend restarts and
  `increase()` adds the pre-reset segment back, so two restarts inside the 6 h window summed to 4
  and fired `ExternalFetchErrors` on 2026-08-03 with no upstream fault behind it. `unwrapEnvelope`
  now keys the audit off `status` — the field UEX actually uses to self-report — and treats absent
  `data` under an `ok` status as zero rows, logged at INFO. The catalogue-wide field rename the
  branch was meant to catch stays covered by `SyncZeroItems` (successful runs processing zero
  items), which a single legitimately empty category cannot trip. **Consequence for reading this
  metric:** the healthy `uex` baseline is now a flat `0`, so any non-zero value is a real signal —
  before this fix it was 2 per process and a genuine outage had to clear that floor to stand out.
  **One fetch, at most one increment (2026-08).** A single `ScWikiClient` page walk can show several
  symptoms of the *same* break at once — a page failing mid-walk plus the resulting row shortfall
  tripping the `meta.total` check, or a full page 1 with no `meta.last_page` plus a disagreeing
  `meta.total`. Each symptom keeps its own WARN (they name different problems), but a per-call
  `FetchErrorLatch` now lets only the first of them increment the counter, so the series counts failed
  *fetches* rather than symptoms: a 20-page walk that dies on page 2 contributes `1`, not `2`. One
  behaviour change rides along — a mid-walk page returning a bodiless `2xx` now contributes `1` where
  it previously contributed `0`. "One fetch" means one public client call (`fetchAllPagesResult` /
  `fetchOne`), not one HTTP request.
  Because the count is now exactly one per incomplete walk, it is also the only usable **proxy for
  "the SC-Wiki orphan sweep stood down"**: `ScWikiOrphanSweep` refuses to tombstone on an incomplete
  census and says so in a WARN and nowhere else — the skip touches no meter. The proxy held: the
  alert fired daily from 2026-08 because the census criterion itself was wrong (a row-count
  comparison against an upstream total that under-reports its own feed — REQ-DATA-014, ADR-0147),
  which is exactly the silent degradation it was built to surface. `ScWikiCensusIncompleteStreak`
  (warning) therefore keys on **persistence, not volume**: an error in each of three *consecutive*
  24 h windows means three daily runs in a row came back incomplete and orphan detection has been off
  for three days, while `scwiki_sync` kept recording success with a healthy item tally and
  `ExternalSyncStale` / `SyncZeroItems` / `ScWikiStepFailing` all stayed quiet. The burst-shaped
  `ExternalFetchErrors` (> 3 in 6 h) catches the outage; this one catches the silent degradation, and
  the two firing together read as an ordinary multi-day upstream outage. The proxy is a **superset** —
  a failed fetch on a pass that feeds no sweep also counts, which is the safe direction for a warning —
  and both the alert comment and the `monitoring/README.md` runbook row say so. Panel 44 on dashboard
  `07` plots the counter per `source`; it had no panel anywhere before. **Still open:** a dedicated
  "sweep stood down" meter would let the alert drop the proxy, and belongs with the `reason`-tag work
  above.
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
  14 d while others stay active — the domain-lost-its-wiring failure mode the global sum masks).
  Four domains are excluded from the per-domain rule and never notify at any horizon: `PROMOTION`,
  `PERSONAL_INVENTORY`, `MARKET`, and — since 2026-08-16 — `ROLE`. `ROLE` covers
  role/membership admin, Kommando groups and user deletion, all admin actions rather than daily
  traffic, so a fortnight without one is an ordinary quiet period; while it was still alerted it
  fired through every such period and, the condition being a level rather than an event, re-notified
  on the Alertmanager `repeat_interval` until somebody changed a role. Their volume is reviewed on
  the operations dashboard's per-domain tables (14 d and 60 d) instead of paged — a deliberate
  trade of coverage for signal. Everything not named there is alerted, so a newly added
  `AuditDomain` is covered by default and exempting one is a deliberate edit rather than an
  omission. The rule, its exclusions and the `up` guard are pinned by promtool unit tests in
  `monitoring/prometheus/tests/audit_domain_silence_alerts_test.yml`. Item-order production
  bookings need no dedicated meter — `JOB_ORDER_PRODUCTION_BOOKED` and
  `INVENTORY_CONSUMED_BY_PRODUCTION` roll into the existing `JOB_ORDER` and `INVENTORY` domain
  counts (REQ-ORDERS-025).
- `basetool_material_exchange_active_count{status="ACTIVE"}` gauge sampled by
  `BusinessMetricsCollector` — the number of active Materialbörse offers on the board, spanning
  **both** offer kinds (material and item, REQ-MARKET-012), via `countByStatus(ACTIVE)`
  (REQ-MARKET-*, REQ-OBS-011). Counts only; the board never emits a per-offer, per-user, per-kind or
  location label.
- `basetool_material_request_open_count{status="ACTIVE"}` gauge sampled by the same collector — the
  number of active Materialbörse requests (Gesuche) on the board, spanning both request kinds
  (material and item, REQ-MARKET-015), via `MaterialExchangeRequestRepository.countByStatus(ACTIVE)`
  (REQ-MARKET-018, REQ-OBS-011). Counts only, equally label-frugal (no per-request/per-user/per-kind
  label). Requests reuse `AuditDomain.MARKET`, so they inherit the existing
  `AuditDomainSilenceAnomaly` exclusion — no alert change is needed.
- `basetool_bank_audit_events_total{event_type}` counter at the single `BankAuditService.record`
  choke point (`event_type` = the bounded `BankAuditEventType` enum). The bank keeps a physically
  separate `bank_audit_event` table excluded from `AuditDomain`, so before #1041 item 10 the most
  sensitive audited area had **zero** volume signal; this counter is that signal — **counts only,
  never amounts, account numbers or holder identities** (REQ-OBS-006). It backs
  `BankAuditSilenceAnomaly` (the bank analogue of `AuditSilenceAnomaly`) and a bank-volume panel on
  the operations dashboard.
- `basetool_ratelimit_rejections_total{bucket,key_source}` counter at the `RateLimitingFilter` reject
  branch (`bucket` = the rule name, or `global` for the umbrella `/api/**` budget), paired since

  # 1041 item 19 with `basetool_ratelimit_requests_total{bucket}` bumped on **every** bucket

  evaluation, so rejections/requests is a rejection ratio (`RateLimitRejectionRatioHigh`) rather than
  429-only detection. The `key_source` tag (2026-08) is the bounded pair `forwarded` / `peer` and
  reports **which branch produced the bucket key** — a trusted-proxy `X-Forwarded-For` element or
  `getRemoteAddr()`. It exists because the log line no longer carries the client IP (REQ-OBS-004),
  and a sudden swing from `forwarded` to `peer` is the signature of an edge/proxy-trust
  misconfiguration that would silently collapse every caller onto one bucket. The address itself
  never becomes a label.

- `basetool_api_client_requests_total{client_id}` counter (`ApiClientMetricsFilter`, A8) — one per
  authenticated `/api/**` request, keyed on the token's `azp` and bounded by
  `app.monitoring.api-clients.known-client-ids` plus the configured ingest gateways; anything else
  is `other` (`ApiUnknownClient`) and a token without the claim is `none`. Paired with
  `basetool_auth_failures_total{reason}` (`SecurityProblemResponseHandler`), the RFC 6750 bearer
  error code behind each 401 — the cause `basetool_http_error_total{code="UNAUTHENTICATED"}` counts
  but cannot name. Full rules, placement and the staged rules: REQ-OBS-018.
- `basetool_discord_precheck_total{outcome}` counter (`DiscordAccountExistenceController`, #1041
  item 19; `outcome` = `ok` / `unauthorized` / `disabled`). The endpoint sits outside `/api/**`, the
  rate limiter and the `basetool_http_error` funnel, so this is the only signal for secret-guessing
  (`DiscordPrecheckUnauthorizedSpike`) or a blank-secret config drift after a rotation
  (`DiscordPrecheckDisabledOnProd`); no PII, only the coarse outcome.

- `basetool_bank_ledger_integrity_violations{category}` gauge fed by the hourly integrity sweep
  (six `category` values; **any value > 0 is CRITICAL** — the ledger broke an invariant).

- `basetool_job_order_integrity_violations{category}` gauge fed by the hourly `JobOrderIntegrityTask`
  (REQ-ORDERS-033; one `category` today, `item_line_blueprint_drift`). `> 0` means an ordered-item
  line's blueprint no longer produces the ordered item after an SC-Wiki re-point, so that order
  displays a **foreign recipe** as its material demand (`JobOrderItemBlueprintDrift`, warning — the
  data is wrong, not corrupt). The sweep logs one `ERROR` per drifted line carrying the order's
  display id, the ordered item and the blueprint's current output — catalogue names only, never the
  order's user-entered handle.

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
  (PII). `MailDeliveryFailing` fires on `failed` > 2/h; `MailDroppedConfigDrift` fires on
  `dropped_no_host` / `dropped_no_sender` — mail was enabled but is silently going nowhere (blank
  `spring.mail.host`, or no `JavaMailSender` bean), previously visible only via `LogbackErrorSpike`.
  **`dropped_disabled` is deliberately out of scope** (amended 2026-07-25): the rule originally fired
  on any `dropped_*` on the premise that "mail is configured on the monitored deployment", but on prod
  `APP_MAIL_ENABLED=false` is the *intended* state until the privacy policy gains an e-mail section, so
  every send took the kill-switch branch and the alert paged every 4 h about a policy decision rather
  than a regression. Because `SmtpMailService` checks its gates in the order enabled → host → sender,
  the two in-scope outcomes are unreachable while the kill-switch is off — the rule is correctly silent
  today and starts protecting the moment mail is switched on. An *unintended* kill-switch flip is still
  caught downstream by `RegistrationApprovalOverdue` (nobody acting on pending registrations). Locked
  by `monitoring/prometheus/tests/maildropped_disabled_scope_test.yml`.

- `basetool_sse_connections` gauge + `basetool_sse_send_failures_total{event,cause}` counter
  (`NotificationStreamService`, #1041 item 17). The gauge sums the live SSE subscriber count across
  all recipients (unlabelled — `sub` is PII); the counter is bumped at each drop-on-send-failure
  branch with a fixed `event` (`connected` / `notification` / `heartbeat`). The `cause` tag (2026-08)
  is the bounded triple `io` / `illegal_state` / `other`, derived from the caught exception's **type**
  and never from its message. All three catch sites caught `IOException | RuntimeException` and
  discarded it, so this split is the only way to tell a client hang-up (`io`, expected on every tab
  close) from a write to an already-completed emitter (`illegal_state`, a lifecycle defect) — a
  distinction the line itself cannot carry, because it is `DEBUG` by necessity (REQ-OBS-001).
  `basetool_sse_emitters_evicted_total` (untagged — the recipient `sub` must never be a label) counts
  each emitter dropped because its recipient reached the per-user cap of 5; read against the
  frontend's 20-socket `/ws/sync` cap it is the tuning signal for whether that cap is set too low.
  Zero connections while
  the frontend is still serving **real user page traffic** drives `SsePushChannelDead` (a dead push
  channel, e.g. reverse-proxy buffering drift). The "users are online" guard **must be live request
  rate, never `basetool_active_sessions`** (amended 2026-07-26): that gauge counts Spring Session
  entries in Redis, whose authenticated TTL is 720h (REQ-SEC-025), so on prod it sits at ~365
  against ~30 real principals and never dips. The original `basetool_active_sessions > 3` guard was
  therefore true 24/7 and reduced the rule to a bare `basetool_sse_connections == 0 for 30m`, which
  fires every night the org is simply asleep — measured 2026-07-26, SSE fell 25 → 0 at 00:45Z and
  returned at 06:30Z while the session gauge held flat at 362–370, producing two firing/resolved
  mail pairs for an idle site. The guard sums `http_server_requests_seconds_count{job="basetool-frontend"}`
  excluding the infrastructure floor that never sleeps (`/` — a constant ~0.133 req/s of blackbox
  probes and NPM health; `REDIRECTION` — those probes' 302 to the login; `NOT_FOUND` — scanner
  noise; `/actuator*`), leaving human traffic that measures 0.04–0.18 req/s on the site and exactly
  0.0 overnight, well separated by the 0.01 req/s floor. If the frontend job vanishes the guard goes
  absent and the alert stays silent by design — a full outage belongs to `TargetDown` / blackbox.
  Locked by `monitoring/prometheus/tests/ssepushchanneldead_traffic_guard_test.yml`.

  **None of these metrics can see a stream that delivers nothing** — the blind spot #1653 was found
  in. Every one of them is counted before the bytes leave the process: the gauge counts emitters
  that were *created*, the failure counter counts writes that *threw*, and a write into a filter's
  buffer neither fails nor closes. For eight months a `ShallowEtagHeaderFilter` registered on `/*`
  buffered both stream endpoints and never wrote the buffer back (it skips the write-back once
  async processing has started), so the notification push was dead while every panel above read
  healthy and `SsePushChannelDead` stayed quiet on a plentiful supply of connections. No
  server-side metric closes this: the only difference between the two states is whether a socket
  received a byte, and nothing inside the process observes that. The guard is therefore a test, not
  a rule -- `SseDeliveryThroughFilterChainTest` opens both streams over a real port through the
  real filter chain and waits for the first frame, so any future component that buffers, wraps or
  delays a streaming response fails the build regardless of which one it is.
  The cross-replica SSE fan-out (#1102, REQ-FE-015 / ADR-0094) adds
  `basetool_sse_redis_published_total` / `basetool_sse_redis_consumed_total` (real-time notification
  signals this replica published to / consumed from the `basetool:notify:published` Redis channel;
  own-origin messages are excluded) and `basetool_sse_redis_errors_total{op}` (`publish` / `consume`
  — a swallowed fan-out failure; the local same-replica delivery already happened, so it only
  degrades cross-replica push). These emit only where the fan-out is enabled (prod); a sustained
  `publish` error stream drives the `LiveSyncRedisFanoutBroken` alert (below).

The app live-sync bridge (ADR-0143, REQ-FE-019) adds a second family on the **backend**, and the
naming is a deliberate choice rather than an accident. Where the concept is the same as the
frontend's, the metric is the same: `basetool_livesync_subscribe_total{topic_class,outcome,reason}`,
`basetool_livesync_invalid_topic_total`, and the three
`basetool_livesync_redis_{published,consumed,errors}_total` series. The two are separated by `job`,
so a dashboard shows either half or both and nobody learns a second vocabulary for one thing — and
the panels and rules already built around those series covered the app the day it shipped. The one
place that mattered got an explicit split: the *Live-sync subscribe outcomes* panel now groups by
`job` as well, because "which client is being refused" is the only question anyone asks of it when
something is wrong, and folding web and app would have thrown exactly that away.

Backend-only, because they have no frontend counterpart: `basetool_livesync_streams` (open app SSE
streams, unlabelled — one per screen, so it reads as "members on a live surface"),
`basetool_livesync_streams_evicted_total`, `basetool_livesync_send_failures_total{event,cause}`
(same bounded `cause` triple as the notification stream), `basetool_livesync_delivered_total`,
`basetool_livesync_publish_{accepted,rejected}_total` (the client-published half, with `reason`
separating a client bug from either bucket doing its job), and
`basetool_livesync_redis_skipped_total{reason}`.

That last one is a distinction worth stating, because merging it would have been the easy mistake:
the frontend's staff-only rooms ride the same Redis channel, so this backend sees a **steady**
trickle of frames for rooms it does not serve. Counting those under `…redis_errors_total` would
have left a permanent non-zero rate beneath the series `LiveSyncRedisFanoutBroken` watches, and a
warning that is always slightly on is a warning nobody reads.

`AppLiveSyncBridgeSilent` is the rule the bridge actually needs. Its failure has no other signal —
both halves keep relaying among themselves, every health check stays green, nothing 5xx's, and a
browser edit merely never reaches a phone while a phone's write never reaches a browser. Three
conjuncts: the frontend is publishing, an app stream is open (so a quiet night cannot page anyone),
and the backend has consumed nothing for 30 minutes. The `or vector(0)` on the third is load-bearing
and is pinned by `monitoring/prometheus/tests/applivesyncbridgesilent_test.yml`: Micrometer creates
a counter lazily, so a backend that has consumed nothing *ever* exposes no series, the comparison
drops out of the vector, and without it the rule would be silent in precisely the total-failure case
it exists for.

**Frontend.** `basetool_mission_presence_missions` gauge (missions with a live editor tracked in
**this JVM** — it deliberately stayed local-only when presence became cross-instance in #1237, so
summing it across replicas still answers "who is editing here"; unlabelled),
`basetool_active_sessions` gauge (active Spring Session sessions;
`@Profile("!test")`, maintained by `ActiveSessionsTracker` from Spring Session create/delete/expire
events and seeded once at startup from the Redis session namespace — it MUST NOT sample the
Redis-backed `SpringSessionBackedSessionRegistry`, whose `getAllPrincipals()` throws and left the
gauge permanently `NaN`, silently disarming the alert that then consumed it, #1158; its remaining
consumer is `ActiveSessionsRunaway`, and it is **not** a presence signal — see the
`SsePushChannelDead` guard note above), and
`basetool_backend_client_errors_total{reason,method}` counter at the
`BackendApiClient` failure funnels. `reason` is a fixed **local** enumeration
(`backend_4xx`/`backend_5xx`/`circuit_open`/`bulkhead_full`/`timeout`/`unknown`) derived from the
failure branch — never the backend's response-body code, which could be arbitrary — and `method`
is the HTTP verb. The push-channel surfaces (#1041 item 17) add `basetool_notification_relay_connections`
(open browser→backend notification SSE relays, `NotificationPageController`) and
`basetool_presence_ws_sessions` (live live-sync WebSocket sessions summed across all topic rooms,
`LiveSyncWebSocketHandler`) gauges, plus the `basetool_presence_relay_frames_total{type,topic_class}`
(`type` = `changed` / `snapshot`) and `basetool_presence_relay_dropped_total{reason,topic_class}`
(`reason` = `throttled` / `send_failed` / `topic_cap` / `authorize_saturated` / `topic_throttled` /
`section_filtered`)
counters at the previously-silent throttle, send-failure, topic-cap, subscribe-saturation,
per-topic-throttle and section-filter branches of the relay. Since 2026-08 `reason="throttled"` is
**shared**: the per-session `subscribe`-frame token bucket (burst 24, refill 1/s — the bound that
keeps the WARN on the subscribe path honest, because a denied subscribe releases its reserved slot and
the topic cap therefore never limits a subscribe→deny→subscribe loop) reports its silent drops through
the same reason within the offending topic class, rather than minting a second throttle literal. A
`throttled` sample therefore no longer implies a presence frame; splitting them needs a new
`MetricNames` literal. The `topic_throttled` reason (F2/#1243)
fires when a room's *aggregate* publish rate exceeds its per-topic token bucket regardless of the
per-session limit, and `section_filtered` (2026-08) is bumped **once per frame** on any frame that
lost **at least one** section key to the allow-list filter, on all three publish paths (client
`changed`, server-side publish, Redis fan-out delivery). That is the point of the meter: the visible
symptom of a section-key skew is one panel going stale while the rest of the page keeps live-updating,
which an all-rejected-only counter would never see. The rejected key is client-supplied and therefore
never becomes a tag value; it appears once, sanitised, in the `DEBUG` line (REQ-OBS-001) —
the component that shipped the REQ-FE-010 staleness defect. Since #1102 (REQ-FE-015 / ADR-0094) both
counters carry a bounded `topic_class` label (one of the fourteen `LiveSyncTopicClass` labels:
`mission`, `operation`, `order_detail`, `orders_queue`, `bank_account`, `bank_staff`, `orgunit_bank`,
`materialboard`, `inventory_all`, since #1235 `missions_list`, `refinery_queue`, `members_roster`,
`org_structure`, and since #1238 `refinery_order`), and
the meter names stay put — a rename would break the `07` panels and this alert set.

Both drop signals are **alerted** since #1238, on a threshold measured rather than guessed: read on
2026-08-03 over the preceding 21 days, `basetool_presence_relay_dropped_total` had **no series at
all** in production — Micrometer creates a counter lazily on first increment, so an empty vector
means no drop branch has ever been taken, on any topic class. Against that zero,
`LiveSyncRelayDropsSustained` warns on >3 drops/h per (`topic_class`, `reason`) sustained 15m, which
still tolerates the one benign drop class (a `send_failed` race when a socket closes between the
`isOpen()` pre-check and the write). `section_filtered` is excluded from it and carries its own
`LiveSyncSectionKeySkew` rule instead — it is a deterministic *code* skew rather than a capacity
signal, so it triggers on **persistence** (`increase[1h] > 0` held for 2h) at a volume far below the
capacity threshold. Known gap: on a barely-used surface (`bank_account` relayed 2 frames in those
21 days) a real skew may never sustain 2h, and panel 29 remains the backstop there. The same gap
applies to `refinery_order`, added in #1238 and therefore absent from that baseline read entirely:
both rules are per-(`topic_class`, `reason`) and need no new series registered, but the new room
starts with no observed volume of its own, so treat panel 29 as its backstop until it has one.

The `changed`-frame **flatline** alert proposed alongside them in #1238 was evaluated and
**rejected as unsound**; the signal stays panel-only. Two structural reasons, both verified in code
and confirmed by the same baseline read: `relayLocal` skips the originating session, so a room with
a single viewer relays **zero** `changed` frames however hard that viewer edits — a flatline is the
normal state of an unoccupied surface, not a defect — and the guard the issue assumed ("while
`snapshot` frames keep flowing") exists **only** for `topic_class="mission"`, because every snapshot
path is gated on `presenceEnabled()`, true for `MISSION` alone. Measured peak *concurrent*
subscriptions per class over that window were `mission` 15, `bank_staff` 6, `order_detail` 4,
`inventory_all`/`materialboard`/`orders_queue`/`orgunit_bank` 3, `bank_account` 2 and `operation`
**1** — co-presence never once occurred on `operation`. A flatline rule would therefore be silent
where it could fire and false where it could not. `LiveSyncSectionKeySkew` detects the same
REQ-FE-010 defect class **positively**, with no occupancy assumption at all.

What a future flatline rule would need is co-presence, which `basetool_livesync_subscriptions`
cannot express (it sums sockets across a class, so two lone viewers in separate rooms read
identically to two peers in one). #1238 therefore adds the gauge
`basetool_livesync_peer_rooms{topic_class}` — live rooms of that class holding **two or more**
subscribers, the honest denominator for panel 39 (`07` panel 47). It is panel-only until it has its
own production baseline, and on the measured co-presence rates only `mission` looks likely to ever
carry enough traffic to support such a rule.

The four Phase-3 rooms added in #1235 (`missions_list`, `refinery_queue`, `members_roster`,
`org_structure`) join the same two rules automatically — both aggregate by `topic_class` rather than
enumerating it, so no rule or panel edit was needed. They carry **no baseline yet**: the 21-day read
above predates them, so their series start empty exactly as every other class did, and the
zero-based `LiveSyncRelayDropsSustained` threshold applies unchanged. The known low-traffic gap
applies to them too, and most sharply to `members_roster` and `org_structure` — admin-only surfaces
where co-presence is rare, so panel 29 stays the backstop for a section-key skew there.

The tool-wide live-sync relay adds five more meters: `basetool_livesync_subscriptions{topic_class}`
(open `/ws/sync` subscriptions per topic class — the live per-surface load denominator),
`basetool_livesync_subscribe_total{topic_class,outcome,reason}` (`outcome` = `allowed` / `denied`,
the subscribe-authorization verdict; a saturated-executor fail-open is instead a
`authorize_saturated` relay drop). The `reason` tag (2026-08) splits the denial into `authz` — an
explicit backend 403/404 or a withheld capability — and `indeterminate`, the fail-**closed** verdict
for a 401/5xx/null-token probe on a presence-enabled class, which is an infrastructure fault dressed
as a permission decision and must be readable as such. Micrometer rejects the same meter name
registered with differing tag-key sets, so the `outcome="allowed"` series carries `reason="none"`;
the wire value is unchanged from the existing login-reason literal, so no dashboard or alert is
affected. Both `07` dashboard panels aggregate with `sum by (topic_class, outcome)` /
`sum by (reason)` and therefore keep working unchanged — but neither yet **surfaces** the new deny
split, which is the outstanding follow-up. The same two literals now also travel **on the wire** in
the `denied` control frame, so the browser can treat `indeterminate` as retryable: it re-subscribes
that topic **exactly once** across the whole socket lifetime (`authz` stays terminal). Reading the
series accordingly — a single transient infrastructure fault can contribute up to **two**
`outcome="denied", reason="indeterminate"` samples per topic, never more, and the retry is per-topic
one-shot rather than per-reconnect.
`basetool_livesync_socket_rejected_total{reason}` (`reason` = `user_cap`; a `/ws/sync`
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
swallowed fan-out failure that degrades only cross-replica delivery). The cross-replica
**editor-presence** gossip (#1237, ADR-0126) keeps its own series rather than sharing those:
`basetool_livesync_presence_published_total{topic_class}` /
`basetool_livesync_presence_consumed_total{topic_class}` and the unlabelled gauge
`basetool_livesync_presence_remote_partitions` (live `(topic, peer instance)` partitions mirrored
here — the direct "is cross-instance presence arriving" signal; a flat zero is correct on a
single-replica deployment, and a zero on a multi-replica one while several replicas report
`basetool_mission_presence_missions > 0` means the gossip is not landing). Separate names because
the gossip is *periodic* while the changed relay is *event-driven*: folded together, the steady
gossip floor would swamp the changed-relay rate the fan-out panel exists to show. Gossip failures
count under `op=presence_publish` / `presence_consume` on the shared errors counter and are
deliberately **outside** the `LiveSyncRedisFanoutBroken` expression — a lost `changed` publish costs
correctness, a lost gossip costs a cosmetic dot. Together with the backend
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

Two frontend meters were added by the 2026-08 logging audit:

- `basetool_session_evicted_total` — unlabelled counter, bumped by `SessionEvictionLoggingStrategy`
  each time Spring Security destroys a user's oldest session because they reached the
  `MAX_CONCURRENT_SESSIONS` cap (10). Previously the cap enforced itself in complete silence, so a
  user reporting "I keep getting logged out in the other tab" had no server-side evidence at all.
  Unlabelled by rule — the principal is PII. Backed since 2026-08 by `SessionEvictionSpike`
  (> 3 evictions/h held 30 m, warning) and by panel 42 on dashboard `07`, beside "Active sessions".
  The alert is a **sustained rate, not a burst**: eviction is rare by construction (a principal must
  already hold ten live sessions), so the `for` clause — not the count — is what separates a member
  cycling devices from the failure mode the meter was written for, a session registry whose cap has
  filled with *dead* Redis entries so every fresh login evicts a live session. `ActiveSessionsRunaway`
  cannot corroborate it: `expireNow()` only marks the session, the registry entry and the Redis key
  both survive, so `basetool_active_sessions` does not even dip. The `> 3` floor is unbaselined
  (`baseline-tune:`) and errs low.
- `basetool_client_error_total{kind}` — counter minted by `ClientErrorReportController` for each
  accepted browser-error beacon (REQ-OBS-001). `kind` is resolved **server-side** against exactly
  three literals — `script_error`, `unhandled_rejection`, `resource_error` — and a beacon carrying
  anything else is rejected with 400 and creates **no** series: the endpoint is reachable by every
  authenticated user, so accepting the client's own string would hand a caller unbounded label
  cardinality (REQ-OBS-006). No other dimension is exported; the message and source live only in the
  `DEBUG` line — which is also why the metric has to carry the signal: a JS exception that kills a
  `krtFetch` handler issues no request at all, so it leaves no access-log line, no
  `http_server_requests` sample and no 5xx. The 2026-08 gap ("no panel, no rule") is **closed**: panel
  43 on dashboard `07` plots reports/hour by `kind`, and `ClientErrorSpike` (warning) fires on
  `> 20` reports/h of one `kind` **and** more than 3× that kind's own daily average, held 30 m. It is
  a **step change, not a ceiling** — a handful of client errors is permanent background (old browsers,
  extensions, a tab left open across a deploy still referencing the previous asset build), so a fixed
  threshold either sits above that floor and misses a surface only a few members use, or below it and
  fires forever. The baseline is the same series over `[24h]` rather than `offset 1d` on purpose: the
  counter is registered lazily on the first report, so an offset comparison has an absent-baseline
  hole where the right-hand side simply does not exist and the rule silently never fires, whereas the
  trailing window is present whenever the 1 h window is. The spike hour sits inside its own
  denominator, capping the achievable ratio at 24. Per `kind` so one class stepping up is not diluted
  by the other two. The `> 20` floor is unbaselined (`baseline-tune:`).

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
exchange — abandoned / expired-state logins raise the state codes, and the `prompt=none` silent-SSO
probe `SsoReAuthenticationEntryPoint` fires on every unauthenticated top-level navigation comes back as
the OIDC Core 3.1.2.6 error set (`login_required`, `interaction_required`, `consent_required`,
`account_selection_required`) whenever the browser carries no live Keycloak SSO cookie;
`LoginFailureMetricsHandler` folds **all three groups** into the `invalid_state` bucket (2026-07-15 fix
for `invalid_request`, 2026-07-28 fix for the `prompt=none` set — each time the code leaked into
`provider_error` and off-peak scanner traffic false-tripped the alert with login perfectly healthy),
keeping `provider_error` a genuine post-authorization token/IdP-break signal. `login_required` is the
highest-volume failure code the app records about itself — one per unauthenticated navigation — so it
must never be alertable. `access_denied` deliberately stays in `provider_error`: it is an explicit
refusal, not routine "no session yet" noise. A state/session-loss break also surfaces as
`invalid_state` and via the `redis` readiness indicator instead) and
`CsrfRejectionSpike` (a systematic CSRF-wiring regression that `krtFetch`'s silent single-retry
otherwise masks as intermittent failed writes). The pre-auth `BotProtectionFilter` adds
`basetool_bot_blocked_total{rule}` (#1041 item 19; `rule` = `method` / `path_prefix` /
`file_extension` / `query_string`) at its four reject branches, which were otherwise `log.debug`-only
and prod-invisible — the counter also surfaces a self-inflicted false positive when a new legit route
matches a blocked prefix, and it is the only prod-visible signal that the `query_string` rule is
firing at all. The **ingest gateway** carries the same filter (`REQ-INGEST-009`) and emits
the same `basetool_bot_blocked_total{rule}` series, distinguished by the `application` common tag
(`basetool-ingest` vs `basetool-frontend`); the "Bot-blocked/hour by rule" panel groups by
`application` + `rule` so both modules are visible. Panels only, all labels fixed literals.

**Ingest.** `basetool_ingest_handoff_total{kind}` (accepted+staged handoffs per `HandoffKind`),
`basetool_ingest_handoff_errors_total{reason}` (relay failures: `backend_reject` /
`backend_unavailable` / `staging_unavailable` / `internal`; pre-relay rejections are not counted
here — `staging_unavailable` is kept apart from `internal` because at that point the backend relay
already **succeeded** and only Redis is at fault, a different operator action, which is why it also
has its own `IngestStagingUnavailable` alert — REQ-INGEST-003), and
`basetool_ratelimit_rejections_total{bucket}` (`bucket` = `ip` / `subject`; shares the metric name
with the backend counter, the `application` common tag separating the modules) — paired since #1041
item 19 with `basetool_ratelimit_requests_total{bucket}` on the per-IP filter and the per-subject
limiter, feeding the same `RateLimitRejectionRatioHigh` ratio alert.
`basetool_ingest_auth_failures_total{reason}` counts every `401` under its RFC 6750 bearer error
code (`invalid_token` / `invalid_request` / `insufficient_scope`, anything else collapsing to
`other`). It exists because a `401` was otherwise **undiagnosable in production**: it is logged at
`DEBUG` with nothing but the exception class — deliberately, since this is the only internet-facing
surface and an anonymous scanner would flood the log at any higher level — so an operator chasing a
failing client had no signal whatsoever. On 2026-08-03 a client reporting "you must sign in" could
equally have meant a malformed header, a bad signature, a wrong issuer, an expired token or a failed
audience check, and nothing separated them. The tag is the error **code**, never the description:
Spring embeds the decode failure verbatim there and it can quote parts of the presented token, which
must never reach an appender or a label (REQ-OBS-004). **Deliberately not alerted** — unauthenticated
probes against a public surface are constant background noise, so a threshold here would be a pager
generator; it is a dashboard panel you consult when a specific client is failing, the same treatment
`basetool_bot_blocked_total` gets.
`basetool_ingest_client_total{client_id}` and `basetool_ingest_client_rejected_total{reason}`
(REQ-INGEST-011) cover the client-identity gate: the first answers "which software is actually
driving the gateway", which no other signal carried — the handoff counter is tagged by draft kind and
the access log by path, so a second producer appearing alongside the extractor used to be invisible.
The `client_id` value is bounded **by construction**: it is the matched allowlist entry or the literal
`other`, never the raw `azp`, because deriving a label from a token claim is the shape of an
unbounded-cardinality bug (REQ-OBS-011). The reject counter's `reason` (`unknown_client` /
`missing_azp` / `missing_scope` / `bad_provenance` / `dpop_required`) is kept as a label because it
splits into two operationally **opposite** causes: `unknown_client` / `bad_provenance` mean a foreign
tool is calling the restricted interface, while `missing_azp` / `missing_scope` mean a Keycloak mapper
or scope assignment regressed and the legitimate extractor is being locked out. It is also bumped
while `app.ingest.client-identity.audit-only` is set — counting what the gate *would* have rejected is
precisely how the operator measures the blast radius before enforcing — and it backs the
`IngestUnknownClient` alert, deliberately not baseline-tuned away: reaching that counter required a
valid realm token, so it cannot be produced by an anonymous scanner and a single occurrence is signal.
`basetool_ingest_payload_rejected_total` (untagged, `PayloadSizeLimitFilter`) counts each
oversized-body 413 the INGEST-DOS-1 guard refuses — previously silent (no log, no metric) unlike the
sibling bot / rate-limit filters — and backs `IngestPayloadRejectedSpike` (logging audit). Its
backend twin `basetool_request_body_rejected_total` (untagged, `RequestBodySizeLimitFilter`) counts
each oversized non-multipart JSON body the backend refuses with 413 on a capped import path (the
refinery `import-extract`, before Jackson binds it — security review, memory-DoS) and backs
`RequestBodyRejectedSpike`. The
gateway also now emits one INFO access-log line per `/v1` request (`RequestLoggingFilter`; method /
path / status / duration), matching the backend/frontend one-line-per-request contract
(REQ-OBS-001). Its `basetool_http_error_total{code}` carries `SERVICE_UNAVAILABLE` (unreachable
identity provider *or* unreachable handoff staging), plus `UNAUTHENTICATED` / `ACCESS_DENIED` from
the filter-level rejections — the counters that keep the 401's deliberate `DEBUG` demotion from
costing the signal (REQ-OBS-001, REQ-API-004).

`basetool_on_behalf_of_refused_total{reason}` counts every refused `X-Ingest-On-Behalf-Of` header
under one of five bounded reasons: `not_a_gateway`, `endpoint_not_bound`, `no_authenticated_caller`,
`malformed_subject`, `member_not_live` (ADR-0129). The label is bounded by construction — the values
are constants in `MetricNames`, never caller-supplied. This counter is the **only** place the
reasons are distinguishable: the HTTP answer is deliberately byte-identical for all five so the
endpoint cannot be used to enumerate which subjects exist. Three of the five are alerted
(`OnBehalfOfRefusedSpike` on `not_a_gateway`, `ActingMemberNotLiveRefused`,
`OnBehalfOfWithoutAuthenticatedCaller`) and all five are on the `Basetool operations` dashboard.
`endpoint_not_bound` is deliberately unalerted: the filter sees every path, so that reason absorbs
the ambient internet traffic that carries the header, which is also why the endpoint bound is
checked before the caller.

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
the `keycloak_user_events_total` series that `KeycloakLoginErrorSpike` depends on. The 2026-08 logging
audit closed the last three unwatched signals, all in `business-warning`: `basetool_client_error_total`
→ `ClientErrorSpike`, `basetool_session_evicted_total` → `SessionEvictionSpike`, and
`basetool_external_fetch_errors_total{source="scwiki"}` → `ScWikiCensusIncompleteStreak` (each
described with its own metric above, and each carrying a runbook row in `monitoring/README.md` plus a
panel on dashboard `07` — 42 session evictions, 43 client errors by `kind`, 44 external fetch errors by
`source`). Adding, renaming or removing one of these metrics keeps its alert in
`monitoring/prometheus/alerts/business.yml` in sync in the same change.

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
records, while `blackbox-dns-api-a` / `blackbox-dns-api-aaaa` do the same for the API vhost of the
mobile-client exposure (ADR-0135) — its records are separate from the apex's, so the apex probes prove
nothing about them (NODATA fails the probe, not only NXDOMAIN).

The answer-RR assertion uses `fail_if_none_matches_regexp` (“at least ONE answer RR carries an address
of the queried family”), never `fail_if_not_matches_regexp` (“EVERY answer RR matches”), and its regexp
does **not** bind the owner name. On a name that is a CNAME — `api.profit-base.online` is one, an alias
to the apex — the answer section carries the alias RR *alongside* the address RR, so an owner-bound
“every RR” assertion can never hold and the probe fails while DNS is perfectly healthy. That shipped
with the phase-F cut-over and fired `DnsResolutionFailed` for both api jobs from their first scrape on
2026-08-18. The regexp stays anchored to an address literal, so the real failures still fail: NXDOMAIN
(rcode), NODATA (empty answer), and a dangling CNAME whose target has no address record. For those v6-pinned probes to test the real edge,
the `blackbox-exporter` container needs its own IPv6 egress: the two nets it otherwise joins
(`net-monitoring-core`, `net-monitoring-scrape`) are IPv4-only, so before #1252 the container had **no
v6 route** and every probe CONNECT-failed (`probe_success=0`) purely from the missing local route —
making `EdgeIpv6Unreachable` a **structural false positive** that fired even while the edge answered over
IPv6. The exporter now also joins a dedicated `net-blackbox-v6` bridge (`enable_ipv6: true`, ULA subnet
masqueraded onto the host's global v6), so the probe exercises the real edge path and the alert is a
**true** signal. `EdgeIpv6Unreachable` (warning) fires only when a
vhost answers over IPv4 but not IPv6 (guarded `on(instance)` against the v4 probe, so a full outage
pages once via `BlackboxProbeFailed`); `DnsResolutionFailed` (warning) fires when the apex or the API
vhost stops resolving an A or AAAA record. These are reachability probes, not posture assertions — a v6-only or
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
- [ ] Every `blackbox-dns-*` module answers `probe_success == 1` against the live zone **as it is
  actually shaped** — including a query name that is a CNAME — while a dangling CNAME, a NODATA
  answer and NXDOMAIN each still fail it.

**Enforced by:** `monitoring/blackbox/blackbox.yml` (`http_deny_404` / `http_force_ssl_redirect` /
`http_2xx_hsts`; the `http_2xx_ipv6` / `http_2xx_or_401_ipv6` / `dns_apex_a` / `dns_apex_aaaa` /
`dns_api_a` / `dns_api_aaaa` reachability modules) · `monitoring/prometheus/prometheus.yml` (the three posture jobs; the
`blackbox-http-ipv6` / `blackbox-http-auth-ipv6` / `blackbox-dns-a` / `blackbox-dns-aaaa` /
`blackbox-dns-api-a` / `blackbox-dns-api-aaaa` reachability jobs; the per-`/probe`-job `scrape_timeout: 15s`) · `monitoring/prometheus/alerts/infrastructure.yml`
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
  silence), per-tail `LogStreamSilent` (via `absent()` of that file's
  `loki_source_file_read_lines_total` series — a tailed file keeps its series present even when
  quiet, so absence means the file is not being tailed at all, the permission-drift failure
  `config.alloy` warns about) and `LokiWriteFailing` (shipper-side entry drops) — all warning — cover
  it. **`LogStreamSilent` guards ten tails, each its own rule with a distinct `stream` label:**
  `host-auth`, `host-auditd`, `host-fail2ban`, `npm-access`, `npm-error`, `keycloak`, `backend`,
  `frontend`, `ingest` and `ops-deploy`. It is deliberately **one rule per path, never an
  alternation** — `absent()` returns 1 only when the selector matches *nothing*, so a combined
  `path=~"…(backend|frontend|ingest)…"` rule would stay perfectly silent while two of the three tails
  were dead. Three ops-automation tails are **not** guarded (`ops-backup`, `ops-cleanup`,
  `ops-restore-drill`): those units are separate or optional installs, and with no file there is no
  series, so a guard would fire forever on a correctly-configured host — their liveness is covered
  metric-side by `BackupStaleOrMissing` / `DockerCleanupStaleOrMissing` / `RestoreDrill*` instead.
  `LokiWriteFailing` fires on `rate(loki_write_dropped_entries_total[15m]) > 0`; a **persistent**
  firing with `reason="ingester_error"` and no other symptom is most often the idle-container stale-line
  re-delivery guarded by the `stage.drop older_than = "167h"` in `loki.process.container_mask` (see
  REQ-OBS-007) — read the exact rejected stream from Alloy's own `final error sending batch` log line
  before touching Loki limits. The **docker-sourced** container streams — the `<svc>-stdout` set
  (ADR-0095), `keycloak-stdout`, the `mon-*` streams, `npm` and `postgres-*` — are deliberately given
  **no** per-stream liveness alert, for two independent reasons: `loki.source.docker` exposes no
  per-target `loki_source_file_read_lines_total` series to take `absent()` of, and a native-error
  breadcrumb is rare by design, so a `rate()`/`absent()` liveness check on such a quiet stream would
  be a permanent false alarm. Whole-pipeline silence is still caught by `LokiIngestSilent`.
- **Container-metric blackout.** cAdvisor can stay "up" while emitting zero name-labelled series (a
  real incident, CHANGELOG v1.1.1), silently blinding the container alerts. `ContainerMetricsMissing`
  (critical) and `CoreContainerMetricsMissing` (warning) guard the named-series count;
  `ContainerOomKilled` and `ContainerCpuThrottledHigh` (warning) surface a single OOM kill and
  sustained CFS throttling that the coarse `ContainerRestartLoop` / `ContainerMemoryHigh` alerts
  miss. `ContainerRestartLoop` counts restarts with `changes(container_start_time_seconds[15m]) > 3`,
  **not** `increase()` — that metric is a start-time *gauge* (Unix epoch), so `increase()` returned the
  epoch delta between the old and new start times and paged CRITICAL on any single restart, including a
  routine deploy recreate of backend/frontend/ingest (fixed 2026-07-15; the sibling Grafana "Container
  Restarts" panel already used `changes()`; locked by
  `tests/containerrestartloop_changes_test.yml`).
- **Container memory pressure is measured on ANONYMOUS memory, not the working set** (amended
  2026-08-02). `ContainerMemoryHigh` (warning) fires when
  `container_memory_rss / container_spec_memory_limit_bytes > 0.90` for 10m. It was named
  `ContainerWorkingSetHigh` and divided `container_memory_working_set_bytes` by the limit until a
  plane-wide measurement showed that metric is **not an OOM predictor**: `working_set` = anon + *active*
  file pages, and the file term includes each service's own memory-mapped **binary**, which is clean,
  file-backed and reclaimed by the kernel rather than OOM-killed. On the 32M exporters that binary is
  roughly **half** the working set. Measured 7-day peaks, anon vs. what the old rule reported —
  `alertmanager` 48.3 % / 95.0 %, `alloy` 40.5 % / 94.8 %, `node-exporter` 33.4 % / 83.8 %, both
  `postgres-exporter`s ~42 % / ~78 % — while `blackbox-exporter` (95.3 % anon) was the only container
  in the stack genuinely near its limit. Page cache also expands to fill whatever cgroup limit it is
  given, so the old rule was **unfixable by more RAM**: `alloy` was raised 192M → 256M → 384M → 512M
  chasing it, and its 2026-07-31 jump turned out to be 183.9 MiB of mapped binary re-charged to its
  cgroup when the v1.18.0 image pull landed (page cache is billed to the cgroup that first faults a
  page in), against a flat 190–217 MB `rss`. `rss` is cgroup `anon`, so heap, thread stacks (the
  2026-07-12 ingest native-thread OOM stays covered) and JVM native memory all still count; the JVM
  apps map almost nothing (`frontend` 0.45 MiB, `ingest` 0.11 MiB), so the REQ-OPS JVM budgets are
  unaffected. Kernel slab/stack is out of scope here — `ContainerPidsHigh` below covers the task-count
  angle. Locked by `tests/containermemoryhigh_anon_scope_test.yml`; sizing method and the full table in
  [`monitoring/README.md`](../../monitoring/README.md) → "Go services".
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
  the cap is hit. It fires when cAdvisor's `container_threads` (the cgroup `pids.current` task count)
  exceeds **80% of that container's own `container_threads_max`** (= `pids.max`) for 10m. The
  comparison **must be cap-relative and must cover every named container** (amended 2026-07-26): the
  rule originally read `container_threads{name=~"backend|frontend|ingest|keycloak"} > 1638` and was
  blind twice over — the name list omitted every non-JVM container, and the `1638` literal (80% of
  2048) is *unreachable* for the services capped at `pids: 512`, so for those it could not fire even
  at 100% of their cap. Grafana fell through both holes: its wget-HTTPS healthcheck forks an
  `ssl_client` per probe that reparents to the Go server (no init, never `wait()`s), and on
  2026-07-26 it sat at 512/512 pids with 493 unreaped zombies and `pids.events max=7445`, refusing
  every fork and reporting `unhealthy` for two days **with no alert mail at all**. The root cause is
  fixed the same way as #1274 — `init: true` on the grafana service in
  `docker-compose.monitoring.yml` — and the denominator carries a `> 0` guard so the unnamed cgroup
  roots that export `container_threads_max=0` cannot divide to `+Inf` and permafire. Headroom is
  ample: measured on prod, grafana read 100% while the next-highest container (tempo) sat at 12.5%.
  It is the cgroup-level companion to `JvmThreadsHigh` and covers keycloak as defense-in-depth —
  keycloak carries the same 2048 cap but exports no `basetool-*` Micrometer series, so
  `JvmThreadsHigh` cannot see a pids/thread runaway in it at all. Locked by
  `monitoring/prometheus/tests/containerpidshigh_cap_relative_test.yml`. The signal exists **only because**
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
- **Notification cadence — one mail per event.** Alertmanager has no acknowledged state, so a
  still-firing alert is re-notified every `repeat_interval` indefinitely. At the original 4 h
  (warnings) / 1 h (criticals) that is six respectively twenty-four identical mails a day for as long
  as the condition holds, and an alert that tests a *state* rather than an event never clears on its
  own — `AuditDomainSilenceAnomaly` on the `ROLE` domain demonstrated it over several days in
  August 2026. Since 2026-08-16 e-mail repeats at **720 h (30 d)** for both severities, so an event is
  one mail plus, via `send_resolved: true`, one resolved mail; the hourly reminder for an open
  critical lives on the **Discord** route instead, where repetition is free. Muting an alert before it
  resolves is what a time-boxed **Silence** is for, not a shorter `repeat_interval`. The cadence is
  bounded from below by Alertmanager's `--data.retention` — the notification log that remembers
  “already sent”, default 120 h — so the compose file pins `--data.retention=744 h`; raising
  one without the other silently degrades the cadence back to the retention window.

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

### REQ-OBS-016 — Log levels are changeable at runtime

All three Spring modules expose the Actuator **`loggers`** endpoint (`management.endpoints.web.exposure.include`)
so a logger threshold can be read while the process runs, and — where the posture below allows it —
raised:

```bash
# backend, in prod: on the management port 11271 since ADR-0134, and STILL ROLE_ADMIN-gated there
# — its permit-all chain enumerates the read endpoints only, so the write keeps the main gate.
curl -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d '{"configuredLevel":"DEBUG"}' \
  https://backend:11271/actuator/loggers/de.greluc.krt.profit.basetool.backend.integration.scwiki

# frontend / ingest, in prod: read only — the write operation is not registered
curl https://localhost:11272/actuator/loggers/de.greluc.krt.profit.basetool.ingest.filter
```

Without it, every DEBUG diagnosis costs a config edit, a redeploy and — because each config is a
single-file bind mount read from a pinned inode — a force-recreate. That is the wrong cost for a
live incident, and it defeats a deliberate design choice: the **most diagnostic lines in this
codebase sit at DEBUG on purpose**, because at INFO an attacker or a routine restart would flood the
log (the bot-protection blocks of `REQ-INGEST-009`, the per-IP rate limit, the open circuit breaker
of issue #1203, a relayed backend 4xx). Those lines existed but were unreachable when they were
needed.

The **read** is available in all three modules on every profile. The **write** is not, and "unreachable
from a public connector" is not a sufficient statement of its posture — it says nothing about the
connector the endpoint actually lives on. The write posture is therefore stated per module. Since
ADR-0134 all three modules serve prod Actuator on an internal-only management port, so what the
posture now turns on is **how wide the permit-all chain on that port is** — and whether the module
has an identity to gate a write on at all:

- **`frontend` + `ingest`, prod — the mutator does not exist.** In prod these two serve Actuator on
  the internal-only management port (frontend `18091`, ingest `11272`) behind an
  unauthenticated permit-all chain (ADR-0090), because the port is reachable only from
  `net-monitoring-scrape` and `localhost`. That chain matches `/actuator/**` by **path** and cannot
  distinguish a read operation from a write one, so exposing `loggers` there exposed the level change
  too: anything on the monitoring plane could set `ROOT` to `TRACE`, at which point Spring Security,
  WebClient and Netty write bearer tokens and request bodies into a Loki stream retained 744 h. The
  fix removes the operation rather than gating it — `management.endpoint.loggers.access: read-only` in
  each `application-prod.yml`, so the write is never registered and there is nothing for the
  permit-all chain to guard. `GET /actuator/loggers` still answers on the management port.
- **`backend`, prod — the mutator survives, gated on `ROLE_ADMIN`.** Since ADR-0134 the backend has a
  management port too (`11271`), so the difference is no longer *whether* there is one — it is how
  wide the chain on it is. The backend's `ManagementPortSecurityConfig` scopes its `securityMatcher`
  to an enumerated read surface (`/actuator/health`, `/actuator/health/**`, `/actuator/prometheus`,
  `/actuator/info`) instead of `/actuator/**`, so `POST /actuator/loggers/**` selects that chain not
  at all and falls through to the module's *main* chain. `SecurityConfig` requires `ROLE_ADMIN` on it
  (placed after the `/actuator/health` `permitAll()`, before `anyRequest().authenticated()`), while
  `GET` falls through to the authenticated catch-all. The gate does **not** ride the role hierarchy: `OFFICER` is
  refused like any other non-admin. This is why the backend can keep a write the other two must
  delete: it is a resource server with an identity to gate on, and its permit-all chain is narrow
  enough that the mutator never reaches it. Widening that matcher to `/actuator/**` would silently
  un-gate the write — `ManagementPortIsolationTest` asserts it stays 401/403 on the management port.
- **dev / test / e2e — unchanged, full control.** No management port is configured, the
  `application-prod.yml` files are never loaded, so all three modules keep the runtime write. The
  backend's `ROLE_ADMIN` matcher lives in `SecurityConfig` and is profile-independent, so it applies
  locally too.

`/actuator/health*` remains the only actuator path permitted on a public connector; every other one
falls through to `anyRequest().authenticated()`. Level changes are **not persisted** — a restart
returns to the configured levels, so a forgotten `DEBUG` cannot silently outlive the incident that
motivated it. The accepted cost of the split: a prod DEBUG dive is a **backend-only, admin-only**
operation, and raising a frontend or ingest logger in prod is back to a config edit plus a
force-recreate.

**Acceptance**

- [x] `GET /actuator/loggers/<name>` reports the effective level in all three modules, on whichever
  connector that module serves Actuator on.
- [x] Backend: `POST /actuator/loggers/**` answers 401 anonymous, 403 for an authenticated non-admin
  (`KRT_MEMBER` **and** `OFFICER`), 204 for `ADMIN`, and the change takes effect without a restart;
  `GET /actuator/loggers` stays reachable for any authenticated user.
- [x] Frontend + ingest under the **prod** profile: the `loggers` write operation is not registered,
  so no caller can change a level — including a caller already inside `net-monitoring-scrape` or on
  `localhost`, where the management port answers without authentication.
- [x] No actuator **write** operation is reachable unauthenticated on **any** connector of any
  module — neither the public app connector (404 / authenticated) nor the internal management port
  (operation removed).
- [x] A restart discards a runtime level change.
- [ ] Every endpoint later added to the frontend/ingest `management.endpoints.web.exposure.include`
  list is checked for write operations, and any it has is set `access: read-only` in
  `application-prod.yml` in the same change. **Open** — a convention, asserted by no test today; the
  management port's permit-all chain cannot enforce it.

**Enforced by:** the module `application.yml` exposure lists ·
`{frontend,ingest}/src/main/resources/application-prod.yml`
(`management.endpoint.loggers.access: read-only`) ·
`backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/SecurityConfig.java` (the
`ROLE_ADMIN` matcher on `POST /actuator/loggers/**`) ·
`backend/src/test/java/de/greluc/krt/profit/basetool/backend/config/ActuatorLoggersAuthorizationTest.java` ·
**Related:** ADR-0090 (management-port isolation and its 2026-08 mutator amendment), REQ-OBS-005
(fail-closed scrape endpoint)

### REQ-OBS-017 — A self-disabled log appender must be detectable

The logging framework's **own** faults must leave a trace, and a shutdown must not truncate the tail
that explains it. Logback reports its internal faults — an appender whose file path could not be
opened, a malformed rolling policy, an encoder that failed to start — only through its internal
status system, which is silent by default unless the failure aborts configuration outright. An
appender can therefore disable itself at startup and every subsequent line addressed to it vanishes
with no error anywhere. The application-side error signal does not help: `logback_events_total` is a
TurboFilter on the **logger**, so it keeps counting events that were never written — the metric looks
perfectly healthy while the file stays empty. All three modules therefore:

- Declare `ch.qos.logback.core.status.OnErrorConsoleStatusListener` as the **first** element child of
  `<configuration>`, so it is installed before any appender is built. It reports WARN/ERROR-level
  status only, to `System.err` — which the `<svc>-stdout` Loki stream (ADR-0095, REQ-OBS-007) already
  ships and which, being outside logback's appender graph, survives precisely the failure it reports.
- Set `<maxFlushTime>5000</maxFlushTime>` on **every** `AsyncAppender` — nine in total, the
  `ASYNC_FILE` and `ASYNC_ERROR_FILE` pair in each module plus the prod-only `ASYNC_JSON_FILE`. The
  logback default is 1 s, after which the worker discards whatever is still queued: the tail lost on
  shutdown is exactly the stretch under investigation after a crash or a forced recreate. 5 s stays
  well below the container stop grace period (ADR-0072), so it cannot itself delay a stop into a
  `SIGKILL`.
- Place the `ERROR`-only `ThresholdFilter` on the `ASYNC_ERROR_FILE` **wrapper**, not on the inner
  `RollingFileAppender`. With `discardingThreshold=0` nothing was ever dropped either way and the
  file content is byte-identical; the win is that the filter chain runs on the **calling** thread, so
  non-ERROR events are rejected before enqueue instead of being copied into the queue with their MDC
  snapshot for the worker to discard — the 128-slot queue, sized for ERROR-only volume, stops
  carrying the full INFO stream.

**Acceptance**

- [ ] An appender that cannot open its target file produces a WARN/ERROR status line on `System.err`
  (and therefore in the `<svc>-stdout` Loki stream) instead of failing silently.
- [ ] A container stop does not truncate the async appenders' queued tail within the stop grace
  period.
- [ ] The `*-error.log` content is unchanged by the filter move (ERROR-only, nothing else added or
  dropped).
- [ ] A parity test pins the status listener, `maxFlushTime` and the filter placement across all
  three configs, plus the frontend's `orgUnitId` pattern slot (REQ-OBS-001). **Open** — the three
  Logback XMLs are asserted by no test today.

**Enforced by:** `{backend,frontend,ingest}/src/main/resources/logback-spring.xml` ·
**Related:** ADR-0095 (`<svc>-stdout` shipping), ADR-0072 (`stop_grace_period`), REQ-OBS-007

### REQ-OBS-018 — The public API surface must be attributable, and probed before it exists

Exposing `/api/v1` on its own internet-reachable vhost (ADR-0135) adds two questions the monitoring
plane could not answer, and both have to be answerable *before* the surface is live rather than
after the first incident.

**Which client software is calling.** The backend counts every authenticated `/api/**` request on
`basetool_api_client_requests_total{client_id}`, keyed on the token's `azp`. Until the native app
ships this is almost entirely the web frontend, and that is the point: the counter needs a baseline
to be read against, and afterwards it is the only place a request can be attributed to the app
rather than to a browser — the denominator of any per-client budget, and the one signal a client
kill switch could ever act on. The label is **bounded** and never taken from the token unfiltered
(REQ-OBS-006): an `azp` is used verbatim only while it names a client the deployment knows —
`app.monitoring.api-clients.known-client-ids` or a configured ingest gateway
(`app.security.ingest-gateway.client-ids`, so the two lists cannot drift) — collapsing to `other`
otherwise and to `none` when the token carries no `azp` at all. The two literals mean opposite
things: `other` is a client nobody registered here, `none` is a Keycloak mapper regression that
blinds the attribution for every client at once. `ApiUnknownClient` (warning) fires on a sustained
`other`; the `none` rule ships staged (below).

The counter **observes and never refuses**. The ingest gateway enforces a client allow-list because
it fronts a single approved tool (REQ-INGEST-011); this surface serves whichever first-party clients
the realm carries, and turning an unrecognised `azp` into a 403 would lock out a client on the day
it is registered in Keycloak and before it reaches a properties file. The gate that matters is the
audience check on the token itself.

Placement is load-bearing on **both** sides, and the metric is silent rather than loud when it is
wrong. The filter must run **after** `BearerTokenAuthenticationFilter` — before it there is no
`SecurityContext`, every request looks anonymous, and the filter skips anonymous requests by
design, so the metric simply stays empty. It must run **before** `ActingMemberFilter`, which
replaces an on-behalf-of call's authentication with an `ActingMemberAuthentication` that carries no
claims, and before the pending-approval, terms, page-size and per-subject gates, so a client cannot
hide from the counter behind its own 403s and 429s.

That narrow window was missed on the first attempt: `addFilterBefore(…, ActingMemberFilter.class)`
reads as "just before the identity swap" and actually lands the filter at the bearer filter's own
slot, one position too early. The counter therefore recorded **nothing at all** in production
between 2026-08-18 and the fix, while `basetool_ratelimit_requests_total{bucket="subject"}` proved
authenticated API traffic was flowing — that contradiction is what surfaced it. The working spelling
is `addFilterAfter(…, BearerTokenAuthenticationFilter.class)` registered *before* the
`ActingMemberFilter` registration; with two `addFilterAfter` calls on one anchor the earlier
registration ends up earlier, which is measured rather than assumed. `ApiClientMetricsChainTest`
pins both edges, and every wrong variant fails it.

**Why authentication failed.** `basetool_auth_failures_total{reason}` breaks the 401s down by the
RFC 6750 bearer error code (`invalid_token` / `invalid_request` / `insufficient_scope` / `other`),
counted in the one funnel every filter-level rejection passes through. `basetool_http_error_total`
`{code="UNAUTHENTICATED"}` already had the volume and drives `BackendAuthFailureSpike`; what it
could not say is whether a spike is a malformed header, an expired token, a wrong issuer or a failed
audience check — a distinction that otherwise costs a log-level change on a surface anonymous
scanners can reach, and that cost the ingest gateway an afternoon on 2026-08-03 (REQ-INGEST-011).
Only the code is taken, never the `OAuth2Error` description: Spring embeds the raw decode failure
there and it can quote fragments of the presented token (REQ-OBS-004).

**Probes for a host that does not resolve yet.** The API vhost's liveness, IPv6 twin, `/actuator`
edge deny, Force-SSL, HSTS and DNS A/AAAA probes are written, reviewed and deployed **staged** —
present in `prometheus.yml` and the external `edge-deny-probe.yml` as commented targets — with the
blackbox modules they need shipped live and inert. Rationale is REQ-OBS-014's: an un-staged probe of
a host that does not exist pages `BlackboxProbeFailed` and `DnsResolutionFailed` from the minute it
merges, and a permanently-firing channel is one an operator stops reading. The enable procedure,
including what to verify first and which alert scopes must be widened in the same edit, is
[`MONITORING_ROLLOUT_RUNBOOK.md`, Appendix C](../MONITORING_ROLLOUT_RUNBOOK.md). Enabling is
all-or-nothing per surface: a partially enabled probe set reads as "monitored".

The liveness probe deliberately targets an **allow-listed path that answers 401**
(`/api/v1/terms/status`), not the vhost root: the vhost is a default-deny allow-list that 404s its
own root, so a root probe would assert nothing about the backend behind it and would fail on a
perfectly healthy edge. For the same reason HSTS uses the `http_2xx_or_401_hsts` module rather than
loosening the frontend's `http_2xx_hsts` assertion.

**Acceptance**

- [x] Every authenticated `/api/**` request is counted under a bounded `client_id`; an unregistered
  client reads as `other` and its `azp` never becomes a label (`ApiClientMetricsFilterTest`).
- [x] The filter sits strictly between `BearerTokenAuthenticationFilter` and `ActingMemberFilter` in
  the chain as built, so an authenticated request is counted at all and a gateway call keeps its own
  client identity (`ApiClientMetricsChainTest`, both edges asserted).
- [x] An encoded path spelling cannot drop a request out of the attribution (REQ-SEC-029).
- [x] Every 401 is counted under its RFC 6750 code, an unknown code collapses to `other`, and a 403
  is not counted as an authentication failure (`SecurityProblemResponseHandlerTest`).
- [x] `ApiUnknownClient` fires on sustained `other` traffic, stays silent for known clients at any
  volume, and does not claim the `none` series (`tests/apiunknownclient_scope_test.yml`).
- [x] The staged probes are enabled and `EdgeHstsHeaderMissing` is widened to
  `job=~"blackbox-hsts.*"` in the same edit (2026-08-18, runbook Appendix C). Their **green** state
  is a deploy-time observation, not a repo property: confirm every new `blackbox-*` target is `up`
  with `probe_success == 1` after the config reaches production.
- [ ] The staged `ApiClientAttributionBlind` rule is enabled once a week of production data shows the
  `none` series flat at zero. **Open**.

**Enforced by:** `ApiClientMetricsFilter`, `ApiClientMetricsProperties`,
`SecurityProblemResponseHandler` (backend) · `monitoring/prometheus/alerts/business.yml`,
`monitoring/prometheus/alerts/apps.yml`, `monitoring/prometheus/prometheus.yml`,
`monitoring/blackbox/blackbox.yml`, `.github/workflows/edge-deny-probe.yml`,
`monitoring/grafana/dashboards/07-basetool-operations.json` ·
**Related:** ADR-0135, ADR-0129 (the identity swap this filter must precede), ADR-0134, REQ-OBS-006,
REQ-OBS-011, REQ-OBS-012, REQ-OBS-014, REQ-SEC-029, REQ-INGEST-011
