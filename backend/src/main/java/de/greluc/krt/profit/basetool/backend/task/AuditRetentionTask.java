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
 * <p>Gated by {@code app.audit.retention.enabled} and paced by {@code
 * app.audit.retention.interval}; the window comes from the validated {@link
 * AuditRetentionProperties} (default 730 days, at least 30). Failures are recorded and swallowed by
 * {@link TaskMetrics}.
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
   * Publishes {@code basetool_scheduled_job_enabled{task="audit_retention"} = 1}, so {@code
   * ScheduledJobStale} can tell a disabled sweep (no bean, no gauge) from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.AUDIT_RETENTION);
  }
}
