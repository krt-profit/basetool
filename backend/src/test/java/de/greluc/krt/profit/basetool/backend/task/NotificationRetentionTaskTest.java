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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.NotificationService;
import de.greluc.krt.profit.basetool.backend.support.NotificationRetentionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the two-window notification retention sweep (REQ-NOTIF-009). */
@ExtendWith(MockitoExtension.class)
class NotificationRetentionTaskTest {

  private static final Duration READ_MAX_AGE = Duration.ofDays(90);
  private static final Duration UNREAD_MAX_AGE = Duration.ofDays(180);

  /** The sweep interval; irrelevant to these direct calls, but the properties record needs one. */
  private static final Duration INTERVAL = Duration.ofHours(24);

  @Mock private NotificationService notificationService;

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final TaskMetrics taskMetrics = new TaskMetrics(meterRegistry);

  private NotificationRetentionTask task() {
    return new NotificationRetentionTask(
        notificationService,
        taskMetrics,
        meterRegistry,
        new NotificationRetentionProperties(true, READ_MAX_AGE, UNREAD_MAX_AGE, INTERVAL));
  }

  private double deleted(String kind) {
    return meterRegistry
        .counter(MetricNames.NOTIFICATION_RETENTION_DELETED, MetricNames.TAG_KIND, kind)
        .count();
  }

  // covers REQ-NOTIF-009 — read notifications age from readAt against the read window
  @Test
  void purgesReadNotificationsOlderThanMaxAge() {
    when(notificationService.purgeReadOlderThan(any())).thenReturn(3);

    task().purgeExpiredNotifications();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(notificationService).purgeReadOlderThan(cutoff.capture());
    // Cutoff is "now - 90d"; allow a small window around the captured value.
    assertThat(cutoff.getValue()).isBeforeOrEqualTo(Instant.now().minus(89, ChronoUnit.DAYS));
    assertThat(cutoff.getValue()).isAfter(Instant.now().minus(91, ChronoUnit.DAYS));
  }

  // covers REQ-NOTIF-009 — unread notifications are bounded too, on their own longer window
  @Test
  void purgesUnreadNotificationsOlderThanUnreadMaxAge() {
    when(notificationService.purgeUnreadOlderThan(any())).thenReturn(2);

    task().purgeExpiredNotifications();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(notificationService).purgeUnreadOlderThan(cutoff.capture());
    assertThat(cutoff.getValue()).isBeforeOrEqualTo(Instant.now().minus(179, ChronoUnit.DAYS));
    assertThat(cutoff.getValue()).isAfter(Instant.now().minus(181, ChronoUnit.DAYS));
  }

  // covers REQ-NOTIF-009 — one run sweeps both windows, never only the read half
  @Test
  void sweepsBothWindowsInOneRun() {
    when(notificationService.purgeReadOlderThan(any())).thenReturn(3);
    when(notificationService.purgeUnreadOlderThan(any())).thenReturn(2);

    task().purgeExpiredNotifications();

    verify(notificationService).purgeReadOlderThan(any());
    verify(notificationService).purgeUnreadOlderThan(any());
  }

  // covers REQ-NOTIF-009 — the unread cutoff is strictly older than the read one, so a notification
  // is never reaped sooner for being unread than it would have been for being read
  @Test
  void unreadCutoffIsOlderThanReadCutoff() {
    task().purgeExpiredNotifications();

    ArgumentCaptor<Instant> readCutoff = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> unreadCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(notificationService).purgeReadOlderThan(readCutoff.capture());
    verify(notificationService).purgeUnreadOlderThan(unreadCutoff.capture());
    assertThat(unreadCutoff.getValue()).isBefore(readCutoff.getValue());
  }

  @Test
  void swallowsFailuresSoSchedulerSurvives() {
    when(notificationService.purgeReadOlderThan(any())).thenThrow(new RuntimeException("db down"));

    // Must not propagate.
    task().purgeExpiredNotifications();
  }

  /**
   * A failing read half must not skip the unread half.
   *
   * <p>They were two sequential statements, so a read purge that threw returned before the unread
   * purge was reached \u2014 and the unread half is the one this feature added: without it an inbox
   * nobody opened kept the triggering member's handle forever. The halves share nothing but a
   * schedule, so one failing is no reason to skip the other.
   */
  @Test
  void aFailingReadHalfStillLetsTheUnreadHalfRun() {
    when(notificationService.purgeReadOlderThan(any())).thenThrow(new RuntimeException("db down"));
    when(notificationService.purgeUnreadOlderThan(any())).thenReturn(7);

    task().purgeExpiredNotifications();

    verify(notificationService).purgeUnreadOlderThan(any());
    assertThat(deleted("unread")).isEqualTo(7.0);
  }

  /** And symmetrically: a failing unread half does not undo what the read half deleted. */
  @Test
  void aFailingUnreadHalfDoesNotUndoTheReadHalf() {
    when(notificationService.purgeReadOlderThan(any())).thenReturn(4);
    when(notificationService.purgeUnreadOlderThan(any()))
        .thenThrow(new RuntimeException("db down"));

    task().purgeExpiredNotifications();

    assertThat(deleted("read")).isEqualTo(4.0);
    assertThat(deleted("unread")).isZero();
  }

  /**
   * Isolating the halves must not turn a broken sweep into a green one.
   *
   * <p>The job's outcome still has to read {@code failure}, which is what {@code
   * ScheduledJobFailureStreak} watches; swallowing the exception here would have replaced a skipped
   * half with a silent one.
   */
  @Test
  void aFailingHalfStillRecordsTheRunAsFailed() {
    when(notificationService.purgeReadOlderThan(any())).thenThrow(new RuntimeException("db down"));
    when(notificationService.purgeUnreadOlderThan(any())).thenReturn(1);

    task().purgeExpiredNotifications();

    assertThat(
            meterRegistry
                .counter(
                    MetricNames.SCHEDULED_JOB_EXECUTIONS,
                    MetricNames.TAG_JOB,
                    ScheduledJob.NOTIFICATION_RETENTION.label(),
                    MetricNames.TAG_OUTCOME,
                    "failure")
                .count())
        .isEqualTo(1.0);
  }

  /**
   * The two halves are counted apart, because the {@code items} total cannot say which one worked.
   *
   * <p>{@code items} stays the job's total; the split counter is what answers "did the unread half
   * delete anything", the question a half that has quietly stopped raises.
   */
  @Test
  void countsTheTwoHalvesSeparatelyAsWellAsTogether() {
    when(notificationService.purgeReadOlderThan(any())).thenReturn(3);
    when(notificationService.purgeUnreadOlderThan(any())).thenReturn(2);

    task().purgeExpiredNotifications();

    assertThat(deleted("read")).isEqualTo(3.0);
    assertThat(deleted("unread")).isEqualTo(2.0);
    assertThat(
            meterRegistry
                .counter(
                    MetricNames.SCHEDULED_JOB_ITEMS,
                    MetricNames.TAG_JOB,
                    ScheduledJob.NOTIFICATION_RETENTION.label())
                .count())
        .isEqualTo(5.0);
  }
}
