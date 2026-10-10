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
import de.greluc.krt.profit.basetool.backend.service.NotificationTimedRunner;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger of the time-based notices (REQ-NOTIF-026), paced by {@code
 * app.notifications.timed.interval} and gated by {@code app.notifications.timed.enabled}.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.notifications.timed",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class NotificationTimedTask {

  private final NotificationTimedRunner runner;
  private final TaskMetrics taskMetrics;

  /**
   * Runs every timed-notice producer, publishing the {@code notification_timed} job metrics; the
   * count of notices raised is the run's item count. A failure is recorded and swallowed by {@link
   * TaskMetrics} so the scheduler thread survives.
   */
  @Scheduled(fixedDelayString = "${app.notifications.timed.interval:PT1M}")
  public void raiseTimedNotices() {
    taskMetrics.recordCounting(
        ScheduledJob.NOTIFICATION_TIMED, () -> runner.runOnce(Instant.now()));
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="notification_timed"} = 1}, so the
   * staleness alert can tell a disabled task from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.NOTIFICATION_TIMED);
  }
}
