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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.NotificationService;
import de.greluc.krt.profit.basetool.backend.support.NotificationRetentionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
@RequiredArgsConstructor
public class NotificationRetentionTask {

  /** The bounded {@code kind} tag value for the read-retention half. */
  private static final String KIND_READ = "read";

  /** The bounded {@code kind} tag value for the unread-retention half. */
  private static final String KIND_UNREAD = "unread";

  /** The inbox service performing the two deletes. */
  private final NotificationService notificationService;

  /** The scheduled-job instrumentation wrapper. */
  private final TaskMetrics taskMetrics;

  /** Where the per-half deleted counter is registered. */
  private final MeterRegistry meterRegistry;

  /**
   * The validated windows: {@code maxAge} for read, {@code unreadMaxAge} for unread notifications,
   * each at least one day and the unread one never shorter (BE-MOD-03).
   */
  private final NotificationRetentionProperties properties;

  /**
   * The first failure of the current run, held while the other half is still to be attempted.
   *
   * <p>Confined to one run: {@link #purgeExpired()} clears it as it rethrows, and the sweep is
   * single-threaded ({@code @Scheduled} with a {@code fixedDelay}, so a run cannot overlap itself).
   */
  private @Nullable RuntimeException firstFailure;

  /**
   * Deletes read notifications read longer ago than the configured {@code maxAge} and unread
   * notifications raised longer ago than {@code unreadMaxAge}, publishing the {@code
   * notification_retention} job metrics. A failure is recorded and swallowed by {@link TaskMetrics}
   * so the scheduler thread survives.
   */
  @Scheduled(fixedDelayString = "${app.notifications.retention.interval:PT24H}")
  public void purgeExpiredNotifications() {
    taskMetrics.recordCounting(ScheduledJob.NOTIFICATION_RETENTION, this::purgeExpired);
  }

  /**
   * Performs both retention deletes, each isolated from the other.
   *
   * <p><b>The halves are independent, so a failure in one must not skip the other.</b> They were
   * two sequential statements: a read purge that threw — a lock timeout on a large batch, a
   * constraint the inbox fanout writes — returned before the unread purge was reached, so the half
   * this feature added (REQ-NOTIF-009) silently never ran while the job reported a plain failure.
   * {@code AuditRetentionService}, written in the same work, isolates each audit domain for the
   * same reason; this is that shape, applied to the two windows that have nothing to do with each
   * other beyond sharing a schedule.
   *
   * <p><b>A failure is still a failure.</b> Both halves are attempted and then the first failure is
   * rethrown, so {@link TaskMetrics} records {@code outcome=failure} and {@code
   * ScheduledJobFailureStreak} can see it. Isolating the halves buys the other half a run; it does
   * not turn a broken sweep into a green one.
   *
   * <p>The two windows share one {@code Instant.now()} so a slow first delete cannot shift the
   * second window, which would make two rows of identical age fall on opposite sides of the cutoff
   * within a single run.
   *
   * @return the total number of notifications deleted this run (the {@code items} metric); the two
   *     halves are also counted separately under {@link MetricNames#NOTIFICATION_RETENTION_DELETED}
   *     because a sum cannot say which half did the work
   */
  private int purgeExpired() {
    log.info(
        "Starting scheduled notification retention sweep (read max age {}, unread max age {})...",
        properties.maxAge(),
        properties.unreadMaxAge());
    Instant now = Instant.now();
    Instant readCutoff = now.minus(properties.maxAge());
    Instant unreadCutoff = now.minus(properties.unreadMaxAge());
    int readDeleted =
        purgeHalf(KIND_READ, () -> notificationService.purgeReadOlderThan(readCutoff));
    int unreadDeleted =
        purgeHalf(KIND_UNREAD, () -> notificationService.purgeUnreadOlderThan(unreadCutoff));
    log.info(
        "Notification retention sweep finished — {} read and {} unread notification(s) deleted.",
        readDeleted,
        unreadDeleted);
    if (firstFailure != null) {
      RuntimeException failure = firstFailure;
      firstFailure = null;
      throw failure;
    }
    return readDeleted + unreadDeleted;
  }

  /**
   * Runs one half of the sweep, counting what it deleted and holding on to a failure instead of
   * letting it skip the other half.
   *
   * <p>The first failure is kept and rethrown once both halves have been attempted, so the run is
   * still reported as failed. A second failure is logged and dropped: the outcome is already
   * failure and there is only one exception to rethrow.
   *
   * @param kind the bounded {@code read} / {@code unread} tag value for the per-half counter
   * @param half the delete to perform
   * @return the rows the half deleted, or {@code 0} when it failed
   */
  private int purgeHalf(@NotNull String kind, @NotNull IntSupplier half) {
    try {
      int deleted = half.getAsInt();
      meterRegistry
          .counter(MetricNames.NOTIFICATION_RETENTION_DELETED, MetricNames.TAG_KIND, kind)
          .increment(deleted);
      return deleted;
    } catch (RuntimeException e) {
      log.warn("Notification retention: the {} half failed: {}", kind, e.toString());
      if (firstFailure == null) {
        firstFailure = e;
      }
      return 0;
    }
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
