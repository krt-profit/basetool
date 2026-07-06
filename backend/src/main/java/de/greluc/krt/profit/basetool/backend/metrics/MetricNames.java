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

  /** Counter {@code basetool_scheduled_job_executions_total}; tags: job, outcome. */
  public static final String SCHEDULED_JOB_EXECUTIONS = "basetool.scheduled.job.executions";

  /** Timer {@code basetool_scheduled_job_duration_seconds} — tag {@code job}. */
  public static final String SCHEDULED_JOB_DURATION = "basetool.scheduled.job.duration";

  /** Gauge {@code basetool_scheduled_job_last_success_timestamp_seconds} — tag {@code job}. */
  public static final String SCHEDULED_JOB_LAST_SUCCESS =
      "basetool.scheduled.job.last.success.timestamp";

  /** Counter {@code basetool_scheduled_job_items_total} — items processed, tag {@code job}. */
  public static final String SCHEDULED_JOB_ITEMS = "basetool.scheduled.job.items";

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

  /** Counter {@code basetool_ratelimit_rejections_total} — tag {@code bucket}. */
  public static final String RATELIMIT_REJECTIONS = "basetool.ratelimit.rejections";

  // --- Bank ledger integrity (BankLedgerIntegrityTask) -----------------------------------

  /** Gauge {@code basetool_bank_ledger_integrity_violations} — tag {@code category}. */
  public static final String BANK_LEDGER_INTEGRITY_VIOLATIONS =
      "basetool.bank.ledger.integrity.violations";

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

  // --- Tag keys --------------------------------------------------------------------------

  /** Tag key: the scheduled job ({@link ScheduledJob#label()}). */
  public static final String TAG_JOB = "job";

  /** Tag key: the run outcome ({@link #OUTCOME_SUCCESS} / {@link #OUTCOME_FAILURE}). */
  public static final String TAG_OUTCOME = "outcome";

  /** Tag key: the stable RFC-7807 error code. */
  public static final String TAG_CODE = "code";

  /** Tag key: the audit domain ({@code AuditDomain#name()}). */
  public static final String TAG_DOMAIN = "domain";

  /** Tag key: the rate-limit bucket that rejected the request. */
  public static final String TAG_BUCKET = "bucket";

  /** Tag key: the bank ledger-integrity violation category. */
  public static final String TAG_CATEGORY = "category";

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

  // --- Bounded tag values (not an application enum) --------------------------------------

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

  /** Base unit rendered as the {@code _seconds} Prometheus suffix on epoch/age gauges. */
  public static final String UNIT_SECONDS = "seconds";

  /** Rate-limit bucket value for the global {@code /api/**} path budget. */
  public static final String BUCKET_GLOBAL = "global";

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

  private MetricNames() {
    // Constants holder — not instantiable.
  }
}
