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
import de.greluc.krt.profit.basetool.backend.service.RejectedRegistrationRetentionService;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled purge of registrations refused longer ago than the retention window (REQ-SEC-057).
 *
 * <p>Gated by {@code app.registrations.rejected-retention.enabled} (default on; disabled under
 * {@code test} so the sweep never races assertions) and paced by {@code
 * app.registrations.rejected-retention.interval}. Failures are recorded and swallowed by {@link
 * TaskMetrics}, so a bad sweep never tears down the scheduler thread.
 *
 * <p>The window doubles as the period in which an erroneous rejection can still be reversed through
 * {@code reopenRegistration} (REQ-SEC-034) — purging the row ends that possibility, which is why
 * the default is generous rather than immediate.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.registrations.rejected-retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
public class RejectedRegistrationRetentionTask {

  private final RejectedRegistrationRetentionService retentionService;
  private final TaskMetrics taskMetrics;
  private final Duration maxAge;

  /**
   * Creates the retention task.
   *
   * @param retentionService the service performing the per-registration purge
   * @param taskMetrics the scheduled-job instrumentation wrapper
   * @param maxAge how long a rejected registration is retained after the rejection before the sweep
   *     removes it (ISO-8601 duration; default {@code P90D})
   */
  public RejectedRegistrationRetentionTask(
      RejectedRegistrationRetentionService retentionService,
      TaskMetrics taskMetrics,
      @Value("${app.registrations.rejected-retention.max-age:P90D}") Duration maxAge) {
    this.retentionService = retentionService;
    this.taskMetrics = taskMetrics;
    this.maxAge = maxAge;
  }

  /**
   * Purges registrations rejected longer ago than {@link #maxAge}, publishing the {@code
   * rejected_registration_retention} job metrics. A failure is recorded and swallowed by {@link
   * TaskMetrics} so the scheduler thread survives.
   */
  @Scheduled(fixedDelayString = "${app.registrations.rejected-retention.interval:PT24H}")
  public void purgeExpiredRejectedRegistrations() {
    taskMetrics.recordCounting(ScheduledJob.REJECTED_REGISTRATION_RETENTION, this::purgeExpired);
  }

  /**
   * Performs the retention purge; any failure propagates to {@link TaskMetrics}.
   *
   * @return the number of registrations purged this run (the {@code items} metric)
   */
  private int purgeExpired() {
    log.info("Starting scheduled rejected-registration retention sweep (max age {})...", maxAge);
    int purged = retentionService.purgeRejectedOlderThan(Instant.now().minus(maxAge));
    log.info("Rejected-registration retention sweep finished — {} registration(s) purged.", purged);
    return purged;
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="rejected_registration_retention"} = 1}.
   *
   * <p>A bean {@code @ConditionalOnProperty} never created publishes nothing, and that absence is
   * what lets {@code ScheduledJobStale} tell "switched off on purpose" from "has never succeeded".
   * Without it, following the documented instruction to disable a sweep before its first
   * irreversible run raised a permanent warning: the last-success gauge is registered lazily on
   * first success, so it never appeared and the alert's {@code absent()} leg stayed true.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.REJECTED_REGISTRATION_RETENTION);
  }
}
