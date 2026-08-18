/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.metrics;

/**
 * Single source of truth for the backend {@code basetool_*} business-metric names, tag keys and the
 * bounded tag values that are not already an application enum (REQ-OBS-011).
 *
 * <p>Meter <em>names</em> are declared in Micrometer's dotted convention; the Prometheus scrape
 * renders each dot as an underscore and appends the type/base-unit suffix, so {@code
 * basetool.scheduled.job.executions} surfaces as {@code basetool_scheduled_job_executions_total}.
 * The resulting Prometheus name is noted on each constant.
 *
 * <p>Every tag value used with these meters must come from a bounded, enumerable set (an
 * application enum such as {@code AuditDomain} / {@link ScheduledJob}, or one of the value
 * constants below) — never a username, id, path or other free/unbounded string (REQ-OBS-006).
 */
public final class MetricNames {

  // --- Scheduled-job health (TaskMetrics) ------------------------------------------------

  /** Counter {@code basetool_scheduled_job_executions_total}; tags: task, outcome. */
  public static final String SCHEDULED_JOB_EXECUTIONS = "basetool.scheduled.job.executions";

  /** Timer {@code basetool_scheduled_job_duration_seconds} — tag {@code task}. */
  public static final String SCHEDULED_JOB_DURATION = "basetool.scheduled.job.duration";

  /** Gauge {@code basetool_scheduled_job_last_success_timestamp_seconds} — tag {@code task}. */
  public static final String SCHEDULED_JOB_LAST_SUCCESS =
      "basetool.scheduled.job.last.success.timestamp";

  /** Counter {@code basetool_scheduled_job_items_total} — items processed, tag {@code task}. */
  public static final String SCHEDULED_JOB_ITEMS = "basetool.scheduled.job.items";

  /**
   * Counter {@code basetool_scheduled_job_step_failures_total} — tags {@code task}, {@code step}.
   * Bumped when one step of a multi-step sync job throws and is swallowed so the remaining steps
   * still run: the umbrella job then records {@code outcome=success} with a non-zero item tally
   * from the other steps, so a single reliably-failing step is invisible to the outcome / items /
   * stale signals. Only the SC-Wiki sync is multi-step today; {@code step} is a bounded literal
   * ({@code commodity} / {@code vehicle} / {@code item} / {@code blueprint} / {@code manufacturer},
   * REQ-OBS-011).
   */
  public static final String SCHEDULED_JOB_STEP_FAILURES = "basetool.scheduled.job.step.failures";

  // --- External-sync events (SyncReportService) ------------------------------------------

  /** Counter {@code basetool_sync_events_total} — tags {@code source}, {@code event_type}. */
  public static final String SYNC_EVENTS = "basetool.sync.events";

  // --- External outbound-fetch failures (UexClient / ScWikiClient) -----------------------

  /**
   * Counter {@code basetool_external_fetch_errors_total} — tag {@code source} ({@link #SOURCE_UEX}
   * / {@link #SOURCE_SCWIKI}). Incremented where an upstream fetch/parse error is swallowed into an
   * empty result, so a weeks-long catalogue outage is visible even though the sync job still
   * "succeeds" (REQ-OBS-011).
   */
  public static final String EXTERNAL_FETCH_ERRORS = "basetool.external.fetch.errors";

  // --- Keycloak Admin-API sync (KeycloakService) -----------------------------------------

  /**
   * Counter {@code basetool_keycloak_sync_fetch_failures_total} (untagged). Bumped when the daily
   * user-sync roster fetch against the Keycloak Admin API throws and is swallowed into an empty
   * list: the sync then records a {@code success} with zero users and {@code UserSyncStale} /
   * {@code UserSyncZeroItems} stay quiet, so a Keycloak outage is otherwise indistinguishable from
   * a legitimately empty roster. Access-control-relevant — a stalled sync means departed users keep
   * their local roles (REQ-OBS-011).
   */
  public static final String KEYCLOAK_SYNC_FETCH_FAILURES = "basetool.keycloak.sync.fetch.failures";

  // --- HTTP error rate (GlobalExceptionHandler) ------------------------------------------

  /** Counter {@code basetool_http_error_total} — tag {@code code} (stable RFC-7807 code). */
  public static final String HTTP_ERROR = "basetool.http.error";

  // --- Audited mutations (AuditService) --------------------------------------------------

  /** Counter {@code basetool_audit_events_total} — tag {@code domain} ({@code AuditDomain}). */
  public static final String AUDIT_EVENTS = "basetool.audit.events";

  /**
   * Counter {@code basetool_bank_audit_events_total} — tag {@code event_type} ({@code
   * BankAuditEventType}). The bank keeps a physically separate {@code bank_audit_event} table
   * excluded from {@code AuditDomain}, so this dedicated counter is the bank trail's only volume
   * signal (counts only — never amounts or holder identities; #1041 item 10, REQ-OBS-011).
   */
  public static final String BANK_AUDIT_EVENTS = "basetool.bank.audit.events";

  // --- Rate-limit rejections (RateLimitingFilter) ----------------------------------------

  /**
   * Counter {@code basetool_ratelimit_rejections_total} — tags {@code bucket} and {@code
   * key_source} ({@link #KEY_SOURCE_FORWARDED} / {@link #KEY_SOURCE_PEER}). The {@code key_source}
   * tag records where the bucket key came from: a trusted proxy's {@code X-Forwarded-For} chain —
   * the first untrusted hop walking from the right — or the peer's own address when no proxy is
   * trusted, the header is absent, or every hop in the chain is itself a trusted proxy. That
   * distinction decides how to read a 429 spike — forwarded keys mean many real clients behind the
   * edge tripped their own budgets, peer keys mean everything collapsed onto one shared bucket (a
   * trusted-proxies misconfiguration or an untrusted hop), which throttles unrelated users
   * together. Two bounded literals only: the address itself is never exported as a label
   * (REQ-OBS-004, REQ-OBS-006).
   */
  public static final String RATELIMIT_REJECTIONS = "basetool.ratelimit.rejections";

  /**
   * Counter {@code basetool_ratelimit_requests_total} — tag {@code bucket}. Bumped for <b>every</b>
   * bucket evaluation (consumed or rejected), so rejections/requests gives a rejection ratio and a
   * spike surfaces before the 429s alone would (#1041 item 19).
   */
  public static final String RATELIMIT_REQUESTS = "basetool.ratelimit.requests";

  /**
   * Counter {@code basetool_request_body_rejected_total} — incremented by {@code
   * RequestBodySizeLimitFilter} each time a non-multipart request body on a capped import path is
   * refused with 413 before Jackson binds it (security review, memory-DoS). Unlabelled: the capped
   * path set is tiny and fixed.
   */
  public static final String REQUEST_BODY_REJECTED = "basetool.request.body.rejected";

  // --- Discord SPI precheck (DiscordAccountExistenceController) ---------------------------

  /**
   * Counter {@code basetool_discord_precheck_total} — tag {@code outcome} ({@link
   * #DISCORD_PRECHECK_OK} / {@link #DISCORD_PRECHECK_UNAUTHORIZED} / {@link
   * #DISCORD_PRECHECK_DISABLED}). The endpoint sits outside {@code /api/**}, the rate limiter and
   * the {@code basetool_http_error} funnel, so without this counter secret-guessing or a broken
   * secret after rotation is log-only (#1041 item 19).
   */
  public static final String DISCORD_PRECHECK = "basetool.discord.precheck";

  // --- Bank ledger integrity (BankLedgerIntegrityTask) -----------------------------------

  /** Gauge {@code basetool_bank_ledger_integrity_violations} — tag {@code category}. */
  public static final String BANK_LEDGER_INTEGRITY_VIOLATIONS =
      "basetool.bank.ledger.integrity.violations";

  // --- Job-order integrity (JobOrderIntegrityTask) ---------------------------------------

  /** Gauge {@code basetool_job_order_integrity_violations} — tag {@code category}. */
  public static final String JOB_ORDER_INTEGRITY_VIOLATIONS =
      "basetool.job.order.integrity.violations";

  // --- Approval / work-queue depth (BusinessMetricsCollector) ----------------------------

  /** Gauge {@code basetool_registration_pending_count} — pending user registrations. */
  public static final String REGISTRATION_PENDING = "basetool.registration.pending.count";

  /** Gauge {@code basetool_registration_pending_oldest_age_seconds}. */
  public static final String REGISTRATION_PENDING_OLDEST_AGE =
      "basetool.registration.pending.oldest.age";

  /** Gauge {@code basetool_bank_booking_request_pending_count} — tag {@code required_approver}. */
  public static final String BANK_BOOKING_REQUEST_PENDING =
      "basetool.bank.booking.request.pending.count";

  /** Gauge {@code basetool_bank_booking_request_pending_oldest_age_seconds}. */
  public static final String BANK_BOOKING_REQUEST_PENDING_OLDEST_AGE =
      "basetool.bank.booking.request.pending.oldest.age";

  /** Gauge {@code basetool_job_order_open_count} — tag {@code status}. */
  public static final String JOB_ORDER_OPEN = "basetool.job.order.open.count";

  /** Gauge {@code basetool_job_order_open_oldest_age_seconds}. */
  public static final String JOB_ORDER_OPEN_OLDEST_AGE = "basetool.job.order.open.oldest.age";

  /** Gauge {@code basetool_operation_open_count} — tag {@code status}. */
  public static final String OPERATION_OPEN = "basetool.operation.open.count";

  /** Gauge {@code basetool_operation_open_oldest_age_seconds}. */
  public static final String OPERATION_OPEN_OLDEST_AGE = "basetool.operation.open.oldest.age";

  /** Gauge {@code basetool_refinery_order_open_count} — tag {@code status}. */
  public static final String REFINERY_ORDER_OPEN = "basetool.refinery.order.open.count";

  /** Gauge {@code basetool_refinery_order_open_oldest_age_seconds}. */
  public static final String REFINERY_ORDER_OPEN_OLDEST_AGE =
      "basetool.refinery.order.open.oldest.age";

  /** Gauge {@code basetool_material_exchange_active_count} — active Materialbörse offers. */
  public static final String MATERIAL_EXCHANGE_ACTIVE = "basetool.material.exchange.active.count";

  /**
   * Gauge {@code basetool_material_request_open_count} — active Materialbörse requests (Gesuche).
   */
  public static final String MATERIAL_REQUEST_OPEN = "basetool.material.request.open.count";

  /** Gauge {@code basetool_p4k_import_job_pending_count} — tag {@code status}. */
  public static final String P4K_IMPORT_JOB_PENDING = "basetool.p4k.import.job.pending.count";

  /** Gauge {@code basetool_p4k_import_job_pending_oldest_age_seconds}. */
  public static final String P4K_IMPORT_JOB_PENDING_OLDEST_AGE =
      "basetool.p4k.import.job.pending.oldest.age";

  /**
   * Counter {@code basetool_p4k_import_jobs_total} — tags {@code outcome} ({@link
   * #OUTCOME_SUCCEEDED} / {@link #OUTCOME_FAILED}) and {@code kind} ({@link #TAG_KIND}). Bumped at
   * each terminal transition so a reliably-failing import surfaces as failures rather than an
   * innocuous-looking empty pending queue.
   */
  public static final String P4K_IMPORT_JOBS = "basetool.p4k.import.jobs";

  /**
   * Counter {@code basetool_mail_total} — tag {@code outcome} ({@link #MAIL_SENT} / {@link
   * #MAIL_FAILED} / {@link #MAIL_DROPPED_DISABLED} / {@link #MAIL_DROPPED_NO_HOST} / {@link
   * #MAIL_DROPPED_NO_SENDER}). {@code SmtpMailService} swallows delivery failures and silently
   * drops mail behind three config gates, so without this counter a broken relay or env-var
   * regression is invisible until someone notices missing registration mail. Never the recipient or
   * subject.
   */
  public static final String MAIL = "basetool.mail";

  /**
   * Gauge {@code basetool_sse_connections} — the live Server-Sent-Event subscriber count summed
   * across all recipients in {@code NotificationStreamService}. Drives {@code SsePushChannelDead}
   * (zero here while the frontend still reports active sessions = a dead push channel).
   */
  public static final String SSE_CONNECTIONS = "basetool.sse.connections";

  /**
   * Counter {@code basetool_sse_send_failures_total} — tags {@code event} ({@link
   * #SSE_EVENT_CONNECTED} / {@link #SSE_EVENT_NOTIFICATION} / {@link #SSE_EVENT_HEARTBEAT}) and
   * {@code cause} ({@link #CAUSE_IO} / {@link #CAUSE_ILLEGAL_STATE} / {@link #CAUSE_OTHER}), bumped
   * at each drop-on-send-failure branch so a broken push (e.g. proxy buffering drift) is counted
   * rather than only silently dropping the emitter.
   *
   * <p>The {@code cause} split exists because the three branches catch {@code IOException |
   * RuntimeException} and discard the exception: an ordinary client hang-up ({@code io}) and a
   * write against an already-completed emitter ({@code illegal_state} — a registry lifecycle
   * defect, not a dead client) are otherwise the same number. Distinguishing them without flipping
   * a logger in production is the entire point. Derived from the caught exception's <em>type</em>,
   * never its message (REQ-OBS-006).
   */
  public static final String SSE_SEND_FAILURES = "basetool.sse.send.failures";

  /**
   * Counter {@code basetool_sse_emitters_evicted_total} (untagged) — bumped each time {@code
   * NotificationStreamService.subscribe} retires the oldest stream of a recipient because the
   * per-recipient emitter cap ({@code MAX_EMITTERS_PER_SUB}, currently 5) is already full. The
   * eviction is deliberately quiet — the retired stream only receives a terminal {@code replaced}
   * event — so a user whose tabs keep knocking each other off the push channel produces no signal
   * at all today.
   *
   * <p>Doubles as the tuning signal for the cap: the frontend admits up to 20 concurrent {@code
   * /ws/sync} sockets per user, so a user can legitimately hold far more live tabs than 5 SSE
   * streams. A sustained eviction rate means the notification cap, not the socket cap, is the
   * binding one and is set too low. Untagged — the recipient {@code sub} must never become a label
   * (REQ-OBS-006).
   */
  public static final String SSE_EMITTERS_EVICTED = "basetool.sse.emitters.evicted";

  /**
   * Counter {@code basetool_sse_redis_published_total} — real-time notification signals this
   * instance published to the cross-replica Redis channel (ADR-0094).
   */
  public static final String SSE_REDIS_PUBLISHED = "basetool.sse.redis.published";

  /**
   * Counter {@code basetool_sse_redis_consumed_total} — real-time notification signals this
   * instance consumed from a peer replica (own-origin messages excluded).
   */
  public static final String SSE_REDIS_CONSUMED = "basetool.sse.redis.consumed";

  /**
   * Counter {@code basetool_sse_redis_errors_total} — tag {@code op} ({@link #OP_PUBLISH} / {@link
   * #OP_CONSUME}); a Redis fan-out publish or consume that failed (swallowed — local delivery
   * already happened, so the failure only degrades cross-replica push; polling remains the
   * fallback, ADR-0094).
   */
  public static final String SSE_REDIS_ERRORS = "basetool.sse.redis.errors";

  // --- Tag keys --------------------------------------------------------------------------

  /**
   * Tag key: the scheduled job ({@link ScheduledJob#label()}). Named {@code task}, NOT {@code job}
   * (#1041 item 23): the Prometheus scrape adds its own {@code job="basetool-backend"} label, and a
   * metric tag also named {@code job} collides — Prometheus keeps the scrape value and renames the
   * metric tag to {@code exported_job}, so alerts once had to match the awkward {@code
   * exported_job} (and one silently never fired when they matched plain {@code job}). {@code task}
   * sidesteps the collision so the job identity keeps its intended label. Do not rename back to
   * {@code job}.
   */
  public static final String TAG_JOB = "task";

  /** Tag key: the run outcome ({@link #OUTCOME_SUCCESS} / {@link #OUTCOME_FAILURE}). */
  public static final String TAG_OUTCOME = "outcome";

  /** Tag key: the stable RFC-7807 error code. */
  public static final String TAG_CODE = "code";

  /** Tag key: the audit domain ({@code AuditDomain#name()}). */
  public static final String TAG_DOMAIN = "domain";

  /** Tag key: the rate-limit bucket that rejected the request. */
  public static final String TAG_BUCKET = "bucket";

  /**
   * Tag key: where the rate-limit bucket key was derived from, on {@link #RATELIMIT_REJECTIONS} —
   * {@link #KEY_SOURCE_FORWARDED} when a trusted proxy's {@code X-Forwarded-For} supplied the
   * client address, {@link #KEY_SOURCE_PEER} when the immediate peer address was used. Carries only
   * which of the two paths produced the key; the address itself is never a tag value (REQ-OBS-004).
   */
  public static final String TAG_KEY_SOURCE = "key_source";

  /** Tag key: the bank ledger-integrity violation category. */
  public static final String TAG_CATEGORY = "category";

  /** Tag: why an operation was refused; bounded per meter (REQ-OBS-011). */
  public static final String TAG_REASON = "reason";

  /** Tag key: a bounded lifecycle status enum value. */
  public static final String TAG_STATUS = "status";

  /** Tag key: the bank approval-ladder tier ({@code BankRequestApprover#name()}). */
  public static final String TAG_REQUIRED_APPROVER = "required_approver";

  /** Tag key: the external-sync source system ({@code SyncSourceSystem#name()}). */
  public static final String TAG_SOURCE = "source";

  /**
   * Tag key: the event type of a bounded event enum — the external-sync {@code SyncEventType} on
   * {@link #SYNC_EVENTS}, or the {@code BankAuditEventType} on {@link #BANK_AUDIT_EVENTS}.
   */
  public static final String TAG_EVENT_TYPE = "event_type";

  /**
   * Tag key: the P4K import job kind ({@code P4kImportJobKind#name()}) on {@link #P4K_IMPORT_JOBS}.
   */
  public static final String TAG_KIND = "kind";

  /**
   * Tag key: the sync step ({@code commodity} / {@code vehicle} / {@code item} / {@code blueprint}
   * / {@code manufacturer}) on {@link #SCHEDULED_JOB_STEP_FAILURES}. Bounded literal set.
   */
  public static final String TAG_STEP = "step";

  /** Tag key: the SSE event name that failed to send, on {@link #SSE_SEND_FAILURES}. */
  public static final String TAG_EVENT = "event";

  /**
   * Tag key: the failure shape of an SSE push on {@link #SSE_SEND_FAILURES} ({@link #CAUSE_IO} /
   * {@link #CAUSE_ILLEGAL_STATE} / {@link #CAUSE_OTHER}). Mapped from the caught exception's type
   * through that fixed three-value set — never from its message or class name, which would be
   * unbounded (REQ-OBS-006).
   */
  public static final String TAG_CAUSE = "cause";

  /** Tag key: the notification Redis fan-out operation on {@link #SSE_REDIS_ERRORS}. */
  public static final String TAG_OP = "op";

  // --- Bounded tag values (not an application enum) --------------------------------------

  /** Notification Redis fan-out operation: publishing a signal to peer replicas. */
  public static final String OP_PUBLISH = "publish";

  /** Notification Redis fan-out operation: consuming a peer replica's signal. */
  public static final String OP_CONSUME = "consume";

  /** Outcome tag value for a job run that completed without throwing. */
  public static final String OUTCOME_SUCCESS = "success";

  /** Outcome tag value for a job run that threw. */
  public static final String OUTCOME_FAILURE = "failure";

  /**
   * Outcome tag value for a P4K import that reached {@code SUCCEEDED} — distinct from {@link
   * #OUTCOME_SUCCESS} because it names the terminal job status, not a scheduled-run result.
   */
  public static final String OUTCOME_SUCCEEDED = "succeeded";

  /** Outcome tag value for a P4K import that reached {@code FAILED} (error or restart orphan). */
  public static final String OUTCOME_FAILED = "failed";

  /** Mail outcome: the SMTP relay accepted the message. */
  public static final String MAIL_SENT = "sent";

  /** Mail outcome: the relay threw a {@code MailException} (swallowed, best-effort). */
  public static final String MAIL_FAILED = "failed";

  /** Mail outcome: dropped because the {@code app.mail.enabled} kill-switch is off. */
  public static final String MAIL_DROPPED_DISABLED = "dropped_disabled";

  /** Mail outcome: dropped because {@code spring.mail.host} is blank (SMTP not configured). */
  public static final String MAIL_DROPPED_NO_HOST = "dropped_no_host";

  /** Mail outcome: host set but no {@code JavaMailSender} bean was available. */
  public static final String MAIL_DROPPED_NO_SENDER = "dropped_no_sender";

  /** SSE event value: the initial {@code connected} handshake event. */
  public static final String SSE_EVENT_CONNECTED = "connected";

  /** SSE event value: an unread-state-changed {@code notification} push. */
  public static final String SSE_EVENT_NOTIFICATION = "notification";

  /** SSE event value: the periodic keep-alive {@code heartbeat}. */
  public static final String SSE_EVENT_HEARTBEAT = "heartbeat";

  /**
   * SSE failure cause: an {@link java.io.IOException} from the emitter write — the client hung up
   * or the connection broke mid-push. The expected, benign shape; it dominates in healthy
   * operation.
   */
  public static final String CAUSE_IO = "io";

  /**
   * SSE failure cause: an {@link IllegalStateException} — the emitter had already completed or
   * timed out when the push was attempted. Server-side lifecycle race in the registry rather than a
   * dead client, so it is worth separating from {@link #CAUSE_IO} even though both end in the same
   * drop.
   */
  public static final String CAUSE_ILLEGAL_STATE = "illegal_state";

  /** SSE failure cause: any other runtime exception thrown by the emitter write. */
  public static final String CAUSE_OTHER = "other";

  /** Base unit rendered as the {@code _seconds} Prometheus suffix on epoch/age gauges. */
  public static final String UNIT_SECONDS = "seconds";

  /** Rate-limit bucket value for the global {@code /api/**} path budget. */
  public static final String BUCKET_GLOBAL = "global";

  /**
   * Rate-limit key source: the key was resolved from a trusted proxy's {@code X-Forwarded-For}
   * chain — the first untrusted hop walking from the right, which is the address the proxy appended
   * — i.e. per-client bucketing behind the edge works as designed.
   */
  public static final String KEY_SOURCE_FORWARDED = "forwarded";

  /**
   * Rate-limit key source: the key is the immediate peer address, because the peer is not a trusted
   * proxy, sent no {@code X-Forwarded-For}, or sent a chain consisting only of trusted hops.
   * Sustained rejections on this value behind a reverse proxy mean every client shares one bucket —
   * the signature of a broken {@code app.rate-limit.trusted-proxies} list.
   */
  public static final String KEY_SOURCE_PEER = "peer";

  /** Discord precheck outcome: an existence check ran and answered {@code 200}. */
  public static final String DISCORD_PRECHECK_OK = "ok";

  /** Discord precheck outcome: a missing/invalid shared secret was rejected with {@code 401}. */
  public static final String DISCORD_PRECHECK_UNAUTHORIZED = "unauthorized";

  /** Discord precheck outcome: the feature is unconfigured (blank secret), answered {@code 503}. */
  public static final String DISCORD_PRECHECK_DISABLED = "disabled";

  /** {@code source} value for the UEX API client ({@link #EXTERNAL_FETCH_ERRORS}). */
  public static final String SOURCE_UEX = "uex";

  /** {@code source} value for the Star Citizen Wiki client ({@link #EXTERNAL_FETCH_ERRORS}). */
  public static final String SOURCE_SCWIKI = "scwiki";

  /** Integrity category: an account whose derived (SQL-summed) balance is negative. */
  public static final String CATEGORY_NEGATIVE_ACCOUNT_BALANCE = "negative_account_balance";

  /** Integrity category: a transfer whose account legs do not sum to the transfer fee. */
  public static final String CATEGORY_UNBALANCED_TRANSFER = "unbalanced_transfer";

  /** Integrity category: a transfer whose holder legs do not sum to the transfer fee. */
  public static final String CATEGORY_UNBALANCED_HOLDER_MOVEMENT = "unbalanced_holder_movement";

  /** Integrity category: a reversal that is not the account-side mirror of its original. */
  public static final String CATEGORY_BROKEN_REVERSAL = "broken_reversal";

  /** Integrity category: a reversal that is not the holder-side mirror of its original. */
  public static final String CATEGORY_BROKEN_HOLDER_REVERSAL = "broken_holder_reversal";

  /** Integrity category: an audited transaction missing its mandatory audit row. */
  public static final String CATEGORY_TRANSACTION_WITHOUT_AUDIT = "transaction_without_audit";

  /**
   * Integrity category: an ordered-item line whose blueprint no longer produces the ordered game
   * item, so its snapshotted materials mirror a foreign recipe (REQ-ORDERS-033).
   */
  public static final String CATEGORY_ITEM_LINE_BLUEPRINT_DRIFT = "item_line_blueprint_drift";

  /**
   * Counter: a user recorded consent to the Terms of Use (REQ-SEC-028). Untagged on purpose — the
   * version is already carried by {@link #TERMS_ACCEPTED_USERS}, and tagging a monotonically
   * growing counter with it would leave a dead series behind after every terms change.
   */
  public static final String TERMS_ACCEPTANCES = "basetool.terms.acceptances";

  /**
   * Gauge: how many users have accepted the terms version this process serves (REQ-SEC-028). The
   * rollout signal — after a terms change the series starts at zero and climbs, and a flat line
   * means people are hitting the gate instead of getting through it.
   */
  public static final String TERMS_ACCEPTED_USERS = "basetool.terms.accepted.users";

  /**
   * Gauge: how many <em>distinct</em> subjects the consent gate refused in the last 15 minutes
   * (REQ-SEC-028). The counterpart to {@link #TERMS_ACCEPTED_USERS} — together they answer "how
   * many are still outstanding, and is anyone getting through".
   *
   * <p>Deliberately subjects rather than requests. The refusal <em>rate</em> ({@link
   * #HTTP_ERROR}{@code {code="TERMS_NOT_ACCEPTED"}}) cannot tell one retrying client from a
   * locked-out membership: a single looping tab sustains an arbitrary rate forever, which is how it
   * fired {@code TermsConsentRolloutStalled} twice overnight on 2026-08-03 with nobody awake.
   * Untagged, so the series stays a single bounded number (REQ-OBS-011). Per process — read it with
   * {@code max()}, never {@code sum()}, or a subject refused on two instances counts twice.
   */
  public static final String TERMS_REFUSED_SUBJECTS = "basetool.terms.refused.subjects";

  /**
   * Counter {@code basetool_on_behalf_of_refused_total} — tag {@code reason} ({@link
   * #ON_BEHALF_OF_NOT_A_GATEWAY}, {@link #ON_BEHALF_OF_ENDPOINT_NOT_BOUND}, {@link
   * #ON_BEHALF_OF_NO_CALLER}, {@link #ON_BEHALF_OF_MALFORMED}, {@link
   * #ON_BEHALF_OF_MEMBER_NOT_LIVE}).
   *
   * <p>A security signal, not noise. Since ADR-0129 the ingest gateway may name the member it acts
   * for; anyone else presenting that header is refused and counted here. A non-zero {@code
   * not_a_gateway} rate is either a misconfigured gateway client id or somebody probing for an
   * impersonation primitive, and the two are worth telling apart quickly.
   */
  public static final String ON_BEHALF_OF_REFUSED = "basetool.on.behalf.of.refused";

  /** {@link #ON_BEHALF_OF_REFUSED} reason: the caller's {@code azp} is not an approved gateway. */
  public static final String ON_BEHALF_OF_NOT_A_GATEWAY = "not_a_gateway";

  /** {@link #ON_BEHALF_OF_REFUSED} reason: the named subject was not a UUID. */
  public static final String ON_BEHALF_OF_MALFORMED = "malformed_subject";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: the header arrived at an ingest endpoint with no
   * authenticated caller behind it.
   *
   * <p>Two causes, and they need different responses. Ordinarily it is a probe or a client whose
   * token expired — the header ships in the extractor and is documented, so an unauthenticated
   * caller can send it. A <em>sustained</em> rate alongside failing uploads means something else:
   * the filter runs after authentication, so a persistent inability to see a caller points at the
   * filter ordering having changed underneath it, which is the bug ADR-0129 was written after.
   *
   * <p>This was documented as structurally unreachable until the endpoint bound was moved ahead of
   * the caller check; before that, any unauthenticated request to <em>any</em> path carrying the
   * header landed here, and the alert on it fired for an hour off a single internet probe.
   */
  public static final String ON_BEHALF_OF_NO_CALLER = "no_authenticated_caller";

  /** {@link #ON_BEHALF_OF_REFUSED} reason: the endpoint does not accept an acting member. */
  public static final String ON_BEHALF_OF_ENDPOINT_NOT_BOUND = "endpoint_not_bound";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: the named member is unknown here, or the last roster sync
   * no longer found them in the identity provider.
   *
   * <p>The highest-value signal of the five. A named subject never expires the way a token does, so
   * this counter is what distinguishes "a member was offboarded and their extractor is still
   * running" from "someone is probing which subjects exist". Both refuse identically to the caller;
   * only this metric tells them apart.
   */
  public static final String ON_BEHALF_OF_MEMBER_NOT_LIVE = "member_not_live";

  /**
   * Tag: the Terms-of-Use version a measurement belongs to. Bounded by construction — one process
   * serves exactly one version, because the value is a build artifact (REQ-OBS-011).
   */
  public static final String TAG_TERMS_VERSION = "terms_version";

  private MetricNames() {
    // Constants holder — not instantiable.
  }
}
