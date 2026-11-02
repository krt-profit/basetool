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

package de.greluc.krt.profit.basetool.backend.platform.api;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values shared across modules or
 * owned by the platform (REQ-OBS-011); a module's own names live in its {@code *MetricNames} class
 * in {@code <module>.api}.
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
   * Counter {@code basetool_notification_executor_rejected_total} (untagged), the after-commit
   * notification callbacks the executor refused even after waiting for room; each one is a lost
   * notice (REQ-NOTIF-029). Normally zero.
   */
  public static final String NOTIFICATION_EXECUTOR_REJECTED =
      "basetool.notification.executor.rejected";

  /** Counter {@code basetool_http_error_total} — tag {@code code} (stable RFC-7807 code). */
  public static final String HTTP_ERROR = "basetool.http.error";

  /**
   * Counter {@code basetool_security_expression_failures_total} — tag {@code kind} ({@link
   * #SECURITY_EXPRESSION_EVALUATION} / {@link #SECURITY_EXPRESSION_OTHER}). Bumped when Spring
   * Security cannot evaluate a method-security expression and the request is refused with {@code
   * 400 ILLEGAL_ARGUMENT}; normally zero (REQ-OBS-020).
   */
  public static final String SECURITY_EXPRESSION_FAILURES = "basetool.security.expression.failures";

  /**
   * {@code kind} on {@link #SECURITY_EXPRESSION_FAILURES}: the SpEL evaluation threw, e.g. on an
   * unresolvable bean or method.
   */
  public static final String SECURITY_EXPRESSION_EVALUATION = "evaluation";

  /**
   * {@code kind} on {@link #SECURITY_EXPRESSION_FAILURES}: any other argument error Spring Security
   * raised, e.g. an expression that yields no decision.
   */
  public static final String SECURITY_EXPRESSION_OTHER = "other";

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
   * Tag key: the scheduled job ({@link ScheduledJob#label()}). Named {@code task}, not {@code job},
   * because the Prometheus scrape's own {@code job} label would rename a colliding tag to {@code
   * exported_job}.
   */
  public static final String TAG_JOB = "task";

  /** Tag key: the run outcome ({@link #OUTCOME_SUCCESS} / {@link #OUTCOME_FAILURE}). */
  public static final String TAG_OUTCOME = "outcome";

  /** Tag key: the stable RFC-7807 error code. */
  public static final String TAG_CODE = "code";

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

  /**
   * Tag key: the event type of a bounded event enum — the external-sync {@code SyncEventType} on
   * {@code CatalogueMetricNames#SYNC_EVENTS}, or the {@code BankAuditEventType} on {@code
   * BankMetricNames#BANK_AUDIT_EVENTS}.
   */
  public static final String TAG_EVENT_TYPE = "event_type";

  /**
   * Tag key: the P4K import job kind ({@code P4kImportJobKind#name()}) on {@code
   * CatalogueMetricNames#P4K_IMPORT_JOBS}, the inbox retention half ({@code read} / {@code unread})
   * on {@code NotificationMetricNames#NOTIFICATION_RETENTION_DELETED}, and the catalogue on {@code
   * ExchangeMetricNames#EXCHANGE_RESOLVE_REFS}. Bounded in every use.
   */
  public static final String TAG_KIND = "kind";

  /**
   * Tag key: the sync step ({@code commodity} / {@code vehicle} / {@code item} / {@code blueprint}
   * / {@code manufacturer}) on {@link #SCHEDULED_JOB_STEP_FAILURES}. Bounded literal set.
   */
  public static final String TAG_STEP = "step";

  /**
   * Tag key: the SSE event name that failed to send, on {@code
   * NotificationMetricNames#SSE_SEND_FAILURES}.
   */
  public static final String TAG_EVENT = "event";

  /**
   * Tag key: the failure shape of an SSE push on {@code NotificationMetricNames#SSE_SEND_FAILURES}
   * ({@link #CAUSE_IO} / {@link #CAUSE_ILLEGAL_STATE} / {@link #CAUSE_OTHER}). Mapped from the
   * caught exception's type through that fixed three-value set — never from its message or class
   * name, which would be unbounded (REQ-OBS-006).
   */
  public static final String TAG_CAUSE = "cause";

  /**
   * Tag key: the notification Redis fan-out operation on {@code
   * NotificationMetricNames#SSE_REDIS_ERRORS}.
   */
  public static final String TAG_OP = "op";

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

  /**
   * Outcome tag value for a P4K import that reached {@code FAILED} (error or restart orphan), and
   * for a mirror write on {@code ExchangeMetricNames#EXCHANGE_MIRROR_WRITES} that Redis refused or
   * failed.
   */
  public static final String OUTCOME_FAILED = "failed";

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
