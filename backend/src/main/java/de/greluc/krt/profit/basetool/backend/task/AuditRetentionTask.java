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

package de.greluc.krt.profit.basetool.backend.task;

import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.AuditRetentionService;
import de.greluc.krt.profit.basetool.backend.support.AuditRetentionProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled retention sweep over the activity and bank audit trails (REQ-AUDIT-006).
 *
 * <p>Gated by {@code app.audit.retention.enabled} (default on; disabled under {@code test} so the
 * sweep never races assertions) and paced by {@code app.audit.retention.interval}. Failures are
 * recorded and swallowed by {@link TaskMetrics}, so a bad sweep never tears down the scheduler
 * thread.
 *
 * <p>The default window is <b>730 days</b>, two years. Expressed in days rather than months because
 * {@link java.time.Duration} has no month unit — a month is not a fixed length — and the precision
 * does not matter for a retention boundary. There is no statutory retention obligation behind this
 * number: it is chosen to outlast the organisation's own operating cycles so an old dispute stays
 * reconstructible, and to stop there, because "indefinitely" is not a retention period.
 *
 * <p>The window is read from the validated {@link AuditRetentionProperties}, which refuse to start
 * the context for a window under 30 days — a {@code P0D} or negative value would otherwise have
 * deleted the whole trail on the next run (BE-MOD-03).
 */
@Component
@ConditionalOnProperty(
    prefix = "app.audit.retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class AuditRetentionTask {

  /** The service performing the per-domain purge. */
  private final AuditRetentionService auditRetentionService;

  /** The scheduled-job instrumentation wrapper. */
  private final TaskMetrics taskMetrics;

  /** The validated retention window; its {@code maxAge} sets the cutoff. */
  private final AuditRetentionProperties properties;

  /**
   * Purges audit rows older than the configured {@code maxAge}, publishing the {@code
   * audit_retention} job metrics. A failure is recorded and swallowed by {@link TaskMetrics} so the
   * scheduler thread survives.
   */
  @Scheduled(fixedDelayString = "${app.audit.retention.interval:PT24H}")
  public void purgeExpiredAuditEvents() {
    taskMetrics.recordCounting(ScheduledJob.AUDIT_RETENTION, this::purgeExpired);
  }

  /**
   * Performs the retention purge; any failure propagates to {@link TaskMetrics}.
   *
   * @return the number of audit rows deleted this run (the {@code items} metric)
   */
  private int purgeExpired() {
    log.info("Starting scheduled audit retention sweep (max age {})...", properties.maxAge());
    int deleted = auditRetentionService.purgeOlderThan(Instant.now().minus(properties.maxAge()));
    log.info("Audit retention sweep finished — {} audit row(s) deleted.", deleted);
    return deleted;
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="audit_retention"} = 1}.
   *
   * <p>A bean {@code @ConditionalOnProperty} never created publishes nothing, and that absence is
   * what lets {@code ScheduledJobStale} tell "switched off on purpose" from "has never succeeded".
   * Without it, following the documented instruction to disable a sweep before its first
   * irreversible run raised a permanent warning: the last-success gauge is registered lazily on
   * first success, so it never appeared and the alert's {@code absent()} leg stayed true.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.AUDIT_RETENTION);
  }
}
