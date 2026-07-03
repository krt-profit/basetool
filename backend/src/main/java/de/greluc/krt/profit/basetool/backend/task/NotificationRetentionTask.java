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
import de.greluc.krt.profit.basetool.backend.service.NotificationService;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cleanup of read notifications older than the configured max age (REQ-NOTIF-009).
 *
 * <p>Gated by {@code app.notifications.retention.enabled} (default on; disabled under {@code test}
 * so the sweep never races assertions) and paced by {@code app.notifications.retention.interval}.
 * Failures are logged, not rethrown, so a bad sweep never tears down the scheduler thread. This is
 * orthogonal to the user-initiated delete (REQ-NOTIF-005): users may remove any of their own
 * notifications at any time regardless of age or read state.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.notifications.retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
public class NotificationRetentionTask {

  private final NotificationService notificationService;
  private final TaskMetrics taskMetrics;
  private final Duration maxAge;

  /**
   * Creates the retention task.
   *
   * @param notificationService the inbox service performing the delete
   * @param taskMetrics the scheduled-job instrumentation wrapper
   * @param maxAge how long a read notification is retained before the sweep removes it (ISO-8601
   *     duration; default {@code P90D})
   */
  public NotificationRetentionTask(
      NotificationService notificationService,
      TaskMetrics taskMetrics,
      @Value("${app.notifications.retention.max-age:P90D}") Duration maxAge) {
    this.notificationService = notificationService;
    this.taskMetrics = taskMetrics;
    this.maxAge = maxAge;
  }

  /**
   * Deletes read notifications whose read timestamp is older than {@link #maxAge}, publishing the
   * {@code notification_retention} job metrics. A failure is recorded and swallowed by {@link
   * TaskMetrics} so the scheduler thread survives.
   */
  @Scheduled(fixedDelayString = "${app.notifications.retention.interval:PT24H}")
  public void purgeExpiredReadNotifications() {
    taskMetrics.record(ScheduledJob.NOTIFICATION_RETENTION, this::purgeExpired);
  }

  /** Performs the retention delete; any failure propagates to {@link TaskMetrics}. */
  private void purgeExpired() {
    log.info("Starting scheduled notification retention sweep (max age {})...", maxAge);
    int deleted = notificationService.purgeReadOlderThan(Instant.now().minus(maxAge));
    log.info("Notification retention sweep finished — {} notification(s) deleted.", deleted);
  }
}
