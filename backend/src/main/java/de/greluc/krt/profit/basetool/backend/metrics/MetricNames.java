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
 * Backend {@code basetool_*} business-metric names, tag keys and bounded tag values (REQ-OBS-011).
 *
 * <p>Names use Micrometer's dotted convention; Prometheus renders dots as underscores and appends
 * the type suffix. Every tag value must come from a bounded set (an application enum such as {@link
 * ScheduledJob}, or a constant below), never a free string (REQ-OBS-006).
 */
public final class MetricNames {

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
   * Gauge {@code basetool_scheduled_job_enabled} — {@code 1} while this job is configured to run,
   * tag {@code task}.
   *
   * <p>Published at startup by the job's own bean, so its absence means the job is switched off and
   * lets staleness alerts tell "never succeeded" from "disabled on purpose".
   */
  public static final String SCHEDULED_JOB_ENABLED = "basetool.scheduled.job.enabled";

  /**
   * Counter {@code basetool_scheduled_job_step_failures_total} — tags {@code task}, {@code step}.
   * Bumped when one step of a multi-step sync job throws and is swallowed while the other steps
   * still run; {@code step} is a bounded literal (REQ-OBS-011).
   */
  public static final String SCHEDULED_JOB_STEP_FAILURES = "basetool.scheduled.job.step.failures";

  /**
   * Counter {@code basetool_catalogue_orphan_sweep_skipped_total} — tags {@code sweep}, {@code
   * reason}. Bumped when a catalogue sync stands its orphan sweep down because the fetch was not a
   * full census.
   *
   * <p>{@code reason} is {@link #SWEEP_SKIP_INCOMPLETE} / {@link #SWEEP_SKIP_NOT_MODIFIED} / {@link
   * #SWEEP_SKIP_NO_ROWS}; {@code sweep} is {@link #SWEEP_ITEM}, the only instrumented sweep
   * (REQ-OBS-011).
   */
  public static final String CATALOGUE_ORPHAN_SWEEP_SKIPPED =
      "basetool.catalogue.orphan.sweep.skipped";

  /**
   * Gauge {@code basetool_redis_fanout_subscribed} — tag {@code fanout} ({@code livesync} / {@code
   * notifications}); 1 while the cross-instance pub/sub container is listening, 0 while it is not.
   * Bound to {@code isListening()}, not {@code isRunning()}.
   */
  public static final String REDIS_FANOUT_SUBSCRIBED = "basetool.redis.fanout.subscribed";

  /** Counter {@code basetool_sync_events_total} — tags {@code source}, {@code event_type}. */
  public static final String SYNC_EVENTS = "basetool.sync.events";

  /**
   * Counter {@code basetool_external_fetch_errors_total} — tag {@code source} ({@link #SOURCE_UEX}
   * / {@link #SOURCE_SCWIKI}). Incremented where an upstream fetch/parse error is swallowed into an
   * empty result, so a weeks-long catalogue outage is visible even though the sync job still
   * "succeeds" (REQ-OBS-011).
   */
  public static final String EXTERNAL_FETCH_ERRORS = "basetool.external.fetch.errors";

  /**
   * Counter {@code basetool_keycloak_sync_fetch_failures_total} (untagged). Bumped when the daily
   * user-sync roster fetch against the Keycloak Admin API throws and is swallowed into an empty
   * list, which would otherwise look like a legitimately empty roster (REQ-OBS-011).
   */
  public static final String KEYCLOAK_SYNC_FETCH_FAILURES = "basetool.keycloak.sync.fetch.failures";

  /**
   * Counter {@code basetool_account_deletion_keycloak_failures_total} (untagged). Bumped when an
   * erasure's local half has committed but the Keycloak user could not be deleted (REQ-SEC-061).
   * Normally zero; any increment needs manual deletion in Keycloak (REQ-OBS-011).
   */
  public static final String ACCOUNT_DELETION_KEYCLOAK_FAILURES =
      "basetool.account.deletion.keycloak.failures";

  /**
   * Counter {@code basetool_admin_registration_auto_activated_total} (untagged). Bumped when the
   * reconciliation inserts a new {@code app_user} row that is {@code ACTIVE} on arrival because the
   * subject holds the Keycloak ADMIN realm role (REQ-SEC-017). Normally zero (REQ-OBS-011).
   */
  public static final String ADMIN_REGISTRATION_AUTO_ACTIVATED =
      "basetool.admin.registration.auto.activated";

  /**
   * Counter {@code basetool_notification_retention_deleted_total} — tag {@code kind} ({@code read}
   * / {@code unread}), the two halves of the inbox retention sweep (REQ-NOTIF-009).
   */
  public static final String NOTIFICATION_RETENTION_DELETED =
      "basetool.notification.retention.deleted";

  /**
   * Counter {@code basetool_user_callsign_collisions_total} (untagged). Bumped when a login
   * presents a subject that matches no {@code app_user} row while another row holds the same {@code
   * preferred_username} (ADR-0142).
   *
   * <p>Normally zero. Untagged because a username is unbounded and PII (REQ-OBS-011, REQ-OBS-004).
   */
  public static final String USER_CALLSIGN_COLLISIONS = "basetool.user.callsign.collisions";

  /**
   * Counter {@code basetool_user_sync_failures_total} (untagged). Bumped once per user whose
   * reconciliation threw during a Keycloak sync run; such a user keeps its last local roles
   * (REQ-OBS-011).
   */
  public static final String USER_SYNC_FAILURES = "basetool.user.sync.failures";

  /**
   * Counter {@code basetool_user_discord_link_collisions_total} (untagged). Bumped when a
   * reconciliation skips writing a Discord snowflake that a <em>different</em> {@code app_user} row
   * already holds.
   *
   * <p>Normally zero; stays non-zero on every run until the duplicate accounts are consolidated.
   * Untagged because a snowflake is unbounded personal data (REQ-OBS-004, REQ-OBS-011).
   */
  public static final String USER_DISCORD_LINK_COLLISIONS = "basetool.user.discord.link.collisions";

  /** Counter {@code basetool_http_error_total} — tag {@code code} (stable RFC-7807 code). */
  public static final String HTTP_ERROR = "basetool.http.error";

  /** Counter {@code basetool_audit_events_total} — tag {@code domain} ({@code AuditDomain}). */
  public static final String AUDIT_EVENTS = "basetool.audit.events";

  /**
   * Counter {@code basetool_bank_audit_events_total} — tag {@code event_type} ({@code
   * BankAuditEventType}). The only volume signal for the separate {@code bank_audit_event} trail;
   * counts only, never amounts or holder identities (REQ-OBS-011).
   */
  public static final String BANK_AUDIT_EVENTS = "basetool.bank.audit.events";

  /**
   * Counter {@code basetool_ratelimit_rejections_total} — tags {@code bucket} and {@code
   * key_source} ({@link #KEY_SOURCE_FORWARDED} / {@link #KEY_SOURCE_PEER}), recording whether the
   * bucket key came from a trusted proxy's {@code X-Forwarded-For} chain or the peer address. The
   * address itself is never a label (REQ-OBS-004, REQ-OBS-006).
   */
  public static final String RATELIMIT_REJECTIONS = "basetool.ratelimit.rejections";

  /**
   * Counter {@code basetool_ratelimit_requests_total} — tag {@code bucket}. Bumped for <b>every</b>
   * bucket evaluation, consumed or rejected, so rejections/requests gives a rejection ratio.
   */
  public static final String RATELIMIT_REQUESTS = "basetool.ratelimit.requests";

  /**
   * Counter {@code basetool_request_body_rejected_total} — incremented by {@code
   * RequestBodySizeLimitFilter} each time a non-multipart request body on a capped import path is
   * refused with 413 before Jackson binds it (security review, memory-DoS). Unlabelled: the capped
   * path set is tiny and fixed.
   */
  public static final String REQUEST_BODY_REJECTED = "basetool.request.body.rejected";

  /**
   * Counter {@code basetool_api_client_requests_total} — tag {@code client_id}. Bumped once per
   * authenticated {@code /api/**} request with the calling client's {@code azp}.
   *
   * <p>The {@code azp} is used verbatim only for a known client id; otherwise it collapses to
   * {@link #CLIENT_ID_OTHER}, or {@link #CLIENT_ID_NONE} when the token has no {@code azp}
   * (REQ-OBS-006).
   */
  public static final String API_CLIENT_REQUESTS = "basetool.api.client.requests";

  /**
   * Counter {@code basetool_auth_failures_total} — tag {@code reason}: the RFC 6750 bearer error
   * code ({@link #AUTH_INVALID_TOKEN} / {@link #AUTH_INVALID_REQUEST} / {@link
   * #AUTH_INSUFFICIENT_SCOPE}), {@link #AUTH_NO_CREDENTIALS} for a request with no credential, or
   * {@link #AUTH_OTHER}.
   *
   * <p>{@link #AUTH_INVALID_TOKEN} is the series a credential-guessing alert watches; missing
   * credentials are ordinary background traffic.
   */
  public static final String AUTH_FAILURES = "basetool.auth.failures";

  /**
   * Counter {@code basetool_discord_precheck_total} — tag {@code outcome} ({@link
   * #DISCORD_PRECHECK_OK} / {@link #DISCORD_PRECHECK_UNAUTHORIZED} / {@link
   * #DISCORD_PRECHECK_DISABLED}). The endpoint sits outside {@code /api/**}, the rate limiter and
   * the {@code basetool_http_error} funnel.
   */
  public static final String DISCORD_PRECHECK = "basetool.discord.precheck";

  /** Gauge {@code basetool_bank_ledger_integrity_violations} — tag {@code category}. */
  public static final String BANK_LEDGER_INTEGRITY_VIOLATIONS =
      "basetool.bank.ledger.integrity.violations";

  /** Gauge {@code basetool_job_order_integrity_violations} — tag {@code category}. */
  public static final String JOB_ORDER_INTEGRITY_VIOLATIONS =
      "basetool.job.order.integrity.violations";

  /** Gauge {@code basetool_registration_pending_count} — pending user registrations. */
  public static final String REGISTRATION_PENDING = "basetool.registration.pending.count";

  /** Gauge {@code basetool_registration_pending_oldest_age_seconds}. */
  public static final String REGISTRATION_PENDING_OLDEST_AGE =
      "basetool.registration.pending.oldest.age";

  /**
   * Gauge {@code basetool_deletion_request_pending_count} — members' Art. 17 erasure requests
   * awaiting an admin decision (REQ-SEC-061).
   */
  public static final String DELETION_REQUEST_PENDING = "basetool.deletion.request.pending.count";

  /**
   * Gauge {@code basetool_deletion_request_pending_oldest_age_seconds} — how long the
   * longest-waiting erasure request has waited (REQ-SEC-061). Its alert threshold sits well inside
   * the one-month statutory deadline of GDPR Art. 12(3).
   */
  public static final String DELETION_REQUEST_PENDING_OLDEST_AGE =
      "basetool.deletion.request.pending.oldest.age";

  /**
   * Gauge {@code basetool_users_pending_deletion_count} — accounts present locally but already gone
   * from Keycloak, waiting for an admin to complete their deletion (REQ-SEC-059).
   */
  public static final String USERS_PENDING_DELETION = "basetool.users.pending.deletion.count";

  /**
   * Gauge {@code basetool_users_pending_deletion_oldest_age_seconds} — how long the longest-waiting
   * orphaned account has been waiting, measured from {@code app_user.keycloak_absent_since}
   * (REQ-SEC-059).
   */
  public static final String USERS_PENDING_DELETION_OLDEST_AGE =
      "basetool.users.pending.deletion.oldest.age";

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
   * #MAIL_DROPPED_NO_SENDER}). Counts the delivery failures and config-gated drops {@code
   * SmtpMailService} swallows; never the recipient or subject.
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
   * at each drop-on-send-failure branch.
   *
   * <p>{@code cause} is derived from the caught exception's type, never its message (REQ-OBS-006).
   */
  public static final String SSE_SEND_FAILURES = "basetool.sse.send.failures";

  /**
   * Counter {@code basetool_sse_emitters_evicted_total} (untagged) — bumped each time {@code
   * NotificationStreamService.subscribe} retires a recipient's oldest stream because the
   * per-recipient emitter cap ({@code MAX_EMITTERS_PER_SUB}) is full.
   *
   * <p>A sustained rate means the cap is set too low. The recipient {@code sub} is never a label
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

  /**
   * Tag key: the scheduled job ({@link ScheduledJob#label()}). Named {@code task}, not {@code job},
   * because the Prometheus scrape's own {@code job} label would rename a colliding tag to {@code
   * exported_job}.
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
   * Tag key on {@link #RATELIMIT_REJECTIONS}: where the bucket key came from — {@link
   * #KEY_SOURCE_FORWARDED} or {@link #KEY_SOURCE_PEER}. The address itself is never a tag value
   * (REQ-OBS-004).
   */
  public static final String TAG_KEY_SOURCE = "key_source";

  /**
   * Tag key: the calling client's Keycloak client id on {@link #API_CLIENT_REQUESTS}, bounded by
   * the known-client configuration rather than taken from the token unfiltered.
   */
  public static final String TAG_CLIENT_ID = "client_id";

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
   * Tag key: the P4K import job kind ({@code P4kImportJobKind#name()}) on {@link #P4K_IMPORT_JOBS},
   * and the inbox retention half ({@code read} / {@code unread}) on {@link
   * #NOTIFICATION_RETENTION_DELETED}. Bounded in both uses.
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

  /**
   * Tag key: the catalogue whose orphan sweep stood down, on {@link
   * #CATALOGUE_ORPHAN_SWEEP_SKIPPED} ({@code item} / {@code vehicle} / {@code commodity} / {@code
   * blueprint} / {@code manufacturer}). Bounded literal set, mirroring {@link #TAG_STEP}.
   */
  public static final String TAG_SWEEP = "sweep";

  /**
   * Tag key: which cross-instance pub/sub container a {@link #REDIS_FANOUT_SUBSCRIBED} sample
   * belongs to — {@link #FANOUT_LIVESYNC} or {@link #FANOUT_NOTIFICATIONS}. Two series, fixed at
   * compile time.
   */
  public static final String TAG_FANOUT = "fanout";

  /** Catalogue identity of the cross-kind game-item sweep, on {@link #TAG_SWEEP}. */
  public static final String SWEEP_ITEM = "item";

  /** Orphan-sweep stand-down: at least one kind pass came back an incomplete census. */
  public static final String SWEEP_SKIP_INCOMPLETE = "incomplete";

  /**
   * Orphan-sweep stand-down: every kind pass answered 304, so the run held no census to sweep
   * against. The healthy, expected case — a fully-cached run — and the reason this counter is
   * tagged at all.
   */
  public static final String SWEEP_SKIP_NOT_MODIFIED = "not_modified";

  /** Orphan-sweep stand-down: the run ended up with no rows at all, so a sweep would wipe. */
  public static final String SWEEP_SKIP_NO_ROWS = "no_rows";

  /** Fan-out identity: the app live-sync bridge's Redis pub/sub container (ADR-0143). */
  public static final String FANOUT_LIVESYNC = "livesync";

  /** Fan-out identity: the notification push's Redis pub/sub container (ADR-0094). */
  public static final String FANOUT_NOTIFICATIONS = "notifications";

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
   * timed out, a server-side lifecycle race rather than a dead client.
   */
  public static final String CAUSE_ILLEGAL_STATE = "illegal_state";

  /** SSE failure cause: any other runtime exception thrown by the emitter write. */
  public static final String CAUSE_OTHER = "other";

  /** Base unit rendered as the {@code _seconds} Prometheus suffix on epoch/age gauges. */
  public static final String UNIT_SECONDS = "seconds";

  /** Rate-limit bucket value for the global {@code /api/**} path budget. */
  public static final String BUCKET_GLOBAL = "global";

  /**
   * Rate-limit bucket label: the per-authenticated-subject budget (REQ-SEC-033), as opposed to the
   * per-IP buckets. A rejection here means one account drove the API past its own budget, which is
   * actionable in a way an IP-keyed rejection is not — the identity is real and cannot be rotated.
   * The subject itself is never a label value: it is unbounded and it is PII.
   */
  public static final String BUCKET_SUBJECT = "subject";

  /**
   * Rate-limit bucket label: the per-subject export budget (REQ-SEC-033 carve-out, APPSEC-10) over
   * the export, statement, report and PDF endpoints, kept apart from {@link #BUCKET_SUBJECT} so a
   * rejection says which of the two budgets an account ran out of. Bounded like its sibling; the
   * subject is never a label value.
   */
  public static final String BUCKET_SUBJECT_EXPORT = "subject_export";

  /**
   * Rate-limit key source: the key was resolved from a trusted proxy's {@code X-Forwarded-For}
   * chain, the first untrusted hop walking from the right.
   */
  public static final String KEY_SOURCE_FORWARDED = "forwarded";

  /**
   * Rate-limit key source: the key is the immediate peer address, because the peer is not a trusted
   * proxy, sent no {@code X-Forwarded-For}, or sent a chain consisting only of trusted hops.
   * Sustained rejections on this value behind a reverse proxy mean every client shares one bucket —
   * the signature of a broken {@code app.rate-limit.trusted-proxies} list.
   */
  public static final String KEY_SOURCE_PEER = "peer";

  /**
   * Bounded {@code client_id} value for a caller whose {@code azp} names no known client. It says
   * "something else called the API", never which something — that is the point of the bound, and
   * the reason {@code ApiUnknownClient} alerts on this series rather than on a name.
   */
  public static final String CLIENT_ID_OTHER = "other";

  /**
   * Bounded {@code client_id} value for an authenticated caller whose token carries no {@code azp}
   * claim, which points at a Keycloak mapper regression rather than an unregistered client ({@link
   * #CLIENT_ID_OTHER}).
   */
  public static final String CLIENT_ID_NONE = "none";

  /** Bearer error (RFC 6750): the token was rejected — expired, bad signature, wrong audience. */
  public static final String AUTH_INVALID_TOKEN = "invalid_token";

  /** Bearer error: the request itself was malformed, e.g. two authentication schemes at once. */
  public static final String AUTH_INVALID_REQUEST = "invalid_request";

  /** Bearer error: the token is valid but lacks the required scope. */
  public static final String AUTH_INSUFFICIENT_SCOPE = "insufficient_scope";

  /**
   * No credential was presented: the caller sent no {@code Authorization} header and was rejected
   * with a plain {@link
   * org.springframework.security.authentication.InsufficientAuthenticationException} (or the
   * method-security equivalent).
   *
   * <p>Not an RFC 6750 code, since the spec omits the error code in that case (REQ-OBS-018).
   */
  public static final String AUTH_NO_CREDENTIALS = "no_credentials";

  /**
   * Bearer error: anything outside the RFC set and not {@link #AUTH_NO_CREDENTIALS}, collapsed so
   * the label stays bounded. A sustained non-zero rate means an unenumerated failure mode.
   */
  public static final String AUTH_OTHER = "other";

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
   * Gauge: how many distinct subjects the consent gate refused in the last 15 minutes
   * (REQ-SEC-028); the counterpart to {@link #TERMS_ACCEPTED_USERS}.
   *
   * <p>Untagged (REQ-OBS-011). Per process — read it with {@code max()}, never {@code sum()}.
   */
  public static final String TERMS_REFUSED_SUBJECTS = "basetool.terms.refused.subjects";

  /**
   * Gauge: distinct subjects the role gate refused with {@code 403 NO_ROLE} in the last 15 minutes
   * (REQ-SEC-053); the counterpart of {@link #TERMS_REFUSED_SUBJECTS}.
   *
   * <p>Untagged (REQ-OBS-011). Per process — read it with {@code max()}, never {@code sum()}.
   */
  public static final String NO_ROLE_REFUSED_SUBJECTS = "basetool.norole.refused.subjects";

  /**
   * Counter {@code basetool_on_behalf_of_refused_total} — tag {@code reason} ({@link
   * #ON_BEHALF_OF_NOT_A_GATEWAY}, {@link #ON_BEHALF_OF_ENDPOINT_NOT_BOUND}, {@link
   * #ON_BEHALF_OF_NO_CALLER}, {@link #ON_BEHALF_OF_MALFORMED}, {@link
   * #ON_BEHALF_OF_MEMBER_NOT_LIVE}).
   *
   * <p>Counts on-behalf-of headers refused because the presenter is not the ingest gateway or the
   * request is otherwise invalid (ADR-0129).
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
   * <p>Usually a probe or an expired token; a sustained rate alongside failing uploads points at a
   * changed filter ordering (ADR-0129).
   */
  public static final String ON_BEHALF_OF_NO_CALLER = "no_authenticated_caller";

  /** {@link #ON_BEHALF_OF_REFUSED} reason: the endpoint does not accept an acting member. */
  public static final String ON_BEHALF_OF_ENDPOINT_NOT_BOUND = "endpoint_not_bound";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: the named member is unknown here, or the last roster sync
   * no longer found them in the identity provider.
   */
  public static final String ON_BEHALF_OF_MEMBER_NOT_LIVE = "member_not_live";

  /**
   * Tag: the Terms-of-Use version a measurement belongs to. Bounded by construction — one process
   * serves exactly one version, because the value is a build artifact (REQ-OBS-011).
   */
  public static final String TAG_TERMS_VERSION = "terms_version";

  /**
   * Gauge {@code basetool_livesync_streams} — app live-sync SSE streams currently open, summed
   * across all members (ADR-0143).
   */
  public static final String LIVESYNC_STREAMS = "basetool.livesync.streams";

  /**
   * Counter {@code basetool_livesync_streams_evicted_total} — bumped when a member's oldest live-
   * sync stream is dropped because they reached the per-member cap. Non-zero in normal use only if
   * a client leaks streams on navigation.
   */
  public static final String LIVESYNC_STREAMS_EVICTED = "basetool.livesync.streams.evicted";

  /**
   * Counter {@code basetool_livesync_send_failures_total} — tags {@code event} ({@link #TAG_EVENT})
   * and {@code cause} ({@link #TAG_CAUSE}); a frame that could not be written to a stream, which
   * also retires it.
   */
  public static final String LIVESYNC_SEND_FAILURES = "basetool.livesync.send.failures";

  /**
   * Counter {@code basetool_livesync_frames_dropped_total} — tag {@code event} ({@link
   * #TAG_EVENT}); a frame dropped because an app live-sync stream's bounded delivery queue was
   * full. A rising rate means streams stopped draining.
   */
  public static final String LIVESYNC_FRAMES_DROPPED = "basetool.livesync.frames.dropped";

  /**
   * Gauge {@code basetool_livesync_frames_queued} — frames waiting in the app live-sync streams'
   * delivery queues, summed across all streams (BE-PERF-13). Hovers near zero; a standing value is
   * a subscriber that stopped reading.
   */
  public static final String LIVESYNC_FRAMES_QUEUED = "basetool.livesync.frames.queued";

  /**
   * Counter {@code basetool_livesync_delivered_total} — tag {@code topic_class}; {@code changed}
   * frames written into a room, counted once per frame rather than once per receiver.
   */
  public static final String LIVESYNC_DELIVERED = "basetool.livesync.delivered";

  /**
   * Counter {@code basetool_livesync_subscribe_total} — tags {@code topic_class}, {@code outcome}
   * ({@link #OUTCOME_ALLOWED} / {@link #OUTCOME_DENIED}) and {@code reason}; the verdict of an app
   * live-sync subscribe (ADR-0143).
   *
   * <p>Uses the same name, tags and values as the frontend's {@code /ws/sync} counter. A denial's
   * {@code reason} is {@link #SUBSCRIBE_DENY_AUTHZ} or {@link #SUBSCRIBE_DENY_CHECK_FAILED}; an
   * allowed subscribe carries {@link #REASON_NONE}.
   */
  public static final String LIVESYNC_SUBSCRIBE = "basetool.livesync.subscribe";

  /**
   * Counter {@code basetool_livesync_invalid_topic_total} (unlabelled) — a subscribe or publish
   * naming a topic that does not parse against this backend's registry (REQ-OBS-011).
   */
  public static final String LIVESYNC_INVALID_TOPIC = "basetool.livesync.invalid.topic";

  /**
   * Counter {@code basetool_livesync_publish_accepted_total} — tag {@code topic_class}; client
   * {@code changed} frames taken and relayed.
   */
  public static final String LIVESYNC_PUBLISH_ACCEPTED = "basetool.livesync.publish.accepted";

  /**
   * Counter {@code basetool_livesync_publish_rejected_total} — tags {@code reason} ({@link
   * #REASON_NO_SECTIONS} / {@link #REASON_SUBJECT_BUCKET} / {@link #REASON_TOPIC_BUCKET}) and
   * {@code topic_class}; client frames refused before they were relayed.
   */
  public static final String LIVESYNC_PUBLISH_REJECTED = "basetool.livesync.publish.rejected";

  /**
   * Counter {@code basetool_livesync_redis_published_total} — tag {@code topic_class}; frames this
   * instance put on the shared channel for the web frontend and peer replicas.
   */
  public static final String LIVESYNC_REDIS_PUBLISHED = "basetool.livesync.redis.published";

  /**
   * Counter {@code basetool_livesync_redis_consumed_total} — tag {@code topic_class}; frames taken
   * off the shared channel and delivered to this instance's streams. Zero while browsers are active
   * means the bridge is not actually bridging.
   */
  public static final String LIVESYNC_REDIS_CONSUMED = "basetool.livesync.redis.consumed";

  /**
   * Counter {@code basetool_livesync_redis_errors_total} — tag {@code op} ({@link #OP_PUBLISH} /
   * {@link #OP_CONSUME}); a swallowed fan-out failure. A frame for a room this backend does not
   * serve is not an error and is counted under {@link #LIVESYNC_REDIS_SKIPPED} instead.
   */
  public static final String LIVESYNC_REDIS_ERRORS = "basetool.livesync.redis.errors";

  /**
   * {@link #TAG_EVENT} value: the once-per-stream event naming the topics that were accepted. A
   * failure to send it means the client never learns which rooms are live.
   */
  public static final String LIVESYNC_EVENT_SUBSCRIBED = "subscribed";

  /** {@link #TAG_EVENT} value: a relayed change signal. */
  public static final String LIVESYNC_EVENT_CHANGED = "changed";

  /** {@link #TAG_EVENT} value: the keep-alive that holds an idle stream open through a proxy. */
  public static final String LIVESYNC_EVENT_HEARTBEAT = "heartbeat";

  /**
   * Counter {@code basetool_livesync_redis_skipped_total} — tag {@code reason}; a frame taken off
   * the shared channel and deliberately not delivered, such as one for a frontend-only room. Kept
   * separate from {@link #LIVESYNC_REDIS_ERRORS}.
   */
  public static final String LIVESYNC_REDIS_SKIPPED = "basetool.livesync.redis.skipped";

  /**
   * {@link #TAG_REASON} value on {@link #LIVESYNC_REDIS_SKIPPED}: a room this backend does not
   * serve.
   */
  public static final String REASON_UNKNOWN_TOPIC = "unknown_topic";

  /** {@link #TAG_OUTCOME} value on {@link #LIVESYNC_SUBSCRIBE}: the room was opened. */
  public static final String OUTCOME_ALLOWED = "allowed";

  /** {@link #TAG_OUTCOME} value on {@link #LIVESYNC_SUBSCRIBE}: the room was refused. */
  public static final String OUTCOME_DENIED = "denied";

  /**
   * {@link #TAG_REASON} placeholder on an {@link #OUTCOME_ALLOWED} subscribe. Micrometer rejects a
   * meter whose series disagree on their tag keys, so the allowed series needs a value here.
   */
  public static final String REASON_NONE = "none";

  /** Subscribe-deny reason: the room's own read refused this caller. */
  public static final String SUBSCRIBE_DENY_AUTHZ = "authz";

  /**
   * Subscribe-deny reason: the check threw and the room was refused (fail closed); an
   * infrastructure signal, unlike {@link #SUBSCRIBE_DENY_AUTHZ}.
   */
  public static final String SUBSCRIBE_DENY_CHECK_FAILED = "check_failed";

  /**
   * {@link #TAG_REASON} value on {@link #LIVESYNC_PUBLISH_REJECTED}: every section the client named
   * was outside the topic class's whitelist, so the frame would have carried nothing.
   */
  public static final String REASON_NO_SECTIONS = "no_known_sections";

  /** {@link #TAG_REASON} value: the emitting member's own token bucket was empty. */
  public static final String REASON_SUBJECT_BUCKET = "subject_bucket";

  /** {@link #TAG_REASON} value: the room's aggregate token bucket was empty. */
  public static final String REASON_TOPIC_BUCKET = "topic_bucket";

  /**
   * Tag key: the live-sync room class a measurement belongs to (ADR-0143). Bounded by the {@code
   * LiveSyncTopicClass} enum, one value per constant, distinct even where two classes share a wire
   * prefix so the Auftrags-queue and one Auftrag never fold into one series (REQ-OBS-011).
   */
  public static final String TAG_TOPIC_CLASS = "topic_class";

  /**
   * Gauge {@code basetool_tracing_enabled} — {@code 1} while this module is configured to emit
   * spans, {@code 0} while it is not.
   *
   * <p>Always registered, so {@code 0} means "off on purpose" while absence means "not scraped".
   * Untagged (REQ-OBS-011).
   */
  public static final String TRACING_ENABLED = "basetool.tracing.enabled";

  private MetricNames() {}
}
