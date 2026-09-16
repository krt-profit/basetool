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
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cleanup of notifications past their retention window (REQ-NOTIF-009).
 *
 * <p>Two windows, swept in one run. A <b>read</b> notification ages from the moment it was consumed
 * ({@code max-age}, default 90 days); an <b>unread</b> one has no read timestamp to age from and so
 * ages from when it was raised ({@code unread-max-age}, default 180 days). The unread window is the
 * longer of the two on purpose — a notification still waiting to be seen is worth more than one
 * already consumed — but it is finite, which is the point: while the sweep reached read rows only,
 * an inbox nobody opened retained the triggering member's handle forever, so the retention period
 * stated in the privacy policy held for attentive members and not for absent ones.
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
  private final Duration unreadMaxAge;

  /**
   * Creates the retention task.
   *
   * @param notificationService the inbox service performing the delete
   * @param taskMetrics the scheduled-job instrumentation wrapper
   * @param maxAge how long a read notification is retained after being read before the sweep
   *     removes it (ISO-8601 duration; default {@code P90D})
   * @param unreadMaxAge how long an unread notification is retained after being raised before the
   *     sweep removes it (ISO-8601 duration; default {@code P180D})
   */
  public NotificationRetentionTask(
      NotificationService notificationService,
      TaskMetrics taskMetrics,
      @Value("${app.notifications.retention.max-age:P90D}") Duration maxAge,
      @Value("${app.notifications.retention.unread-max-age:P180D}") Duration unreadMaxAge) {
    this.notificationService = notificationService;
    this.taskMetrics = taskMetrics;
    this.maxAge = maxAge;
    this.unreadMaxAge = unreadMaxAge;
  }

  /**
   * Deletes read notifications read longer ago than {@link #maxAge} and unread notifications raised
   * longer ago than {@link #unreadMaxAge}, publishing the {@code notification_retention} job
   * metrics. A failure is recorded and swallowed by {@link TaskMetrics} so the scheduler thread
   * survives.
   */
  @Scheduled(fixedDelayString = "${app.notifications.retention.interval:PT24H}")
  public void purgeExpiredNotifications() {
    taskMetrics.recordCounting(ScheduledJob.NOTIFICATION_RETENTION, this::purgeExpired);
  }

  /**
   * Performs both retention deletes; any failure propagates to {@link TaskMetrics}.
   *
   * <p>The two statements share one {@code Instant.now()} so a slow first delete cannot shift the
   * second window, which would make two rows of identical age fall on opposite sides of the cutoff
   * within a single run.
   *
   * @return the total number of notifications deleted this run (the {@code items} metric)
   */
  private int purgeExpired() {
    log.info(
        "Starting scheduled notification retention sweep (read max age {}, unread max age {})...",
        maxAge,
        unreadMaxAge);
    Instant now = Instant.now();
    int readDeleted = notificationService.purgeReadOlderThan(now.minus(maxAge));
    int unreadDeleted = notificationService.purgeUnreadOlderThan(now.minus(unreadMaxAge));
    log.info(
        "Notification retention sweep finished — {} read and {} unread notification(s) deleted.",
        readDeleted,
        unreadDeleted);
    return readDeleted + unreadDeleted;
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="notification_retention"} = 1}.
   *
   * <p>A bean {@code @ConditionalOnProperty} never created publishes nothing, and that absence is
   * what lets {@code ScheduledJobStale} tell "switched off on purpose" from "has never succeeded".
   * Without it, following the documented instruction to disable a sweep before its first
   * irreversible run raised a permanent warning: the last-success gauge is registered lazily on
   * first success, so it never appeared and the alert's {@code absent()} leg stayed true.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.NOTIFICATION_RETENTION);
  }
}
