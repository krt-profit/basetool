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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.NotificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRetentionTaskTest {

  @Mock private NotificationService notificationService;

  private final TaskMetrics taskMetrics = new TaskMetrics(new SimpleMeterRegistry());

  @Test
  void purgesReadNotificationsOlderThanMaxAge() {
    Duration maxAge = Duration.ofDays(90);
    NotificationRetentionTask task =
        new NotificationRetentionTask(notificationService, taskMetrics, maxAge);
    when(notificationService.purgeReadOlderThan(org.mockito.ArgumentMatchers.any())).thenReturn(3);

    task.purgeExpiredReadNotifications();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    org.mockito.Mockito.verify(notificationService).purgeReadOlderThan(cutoff.capture());
    // Cutoff is "now - 90d"; allow a small window around the captured value.
    assertThat(cutoff.getValue()).isBeforeOrEqualTo(Instant.now().minus(89, ChronoUnit.DAYS));
    assertThat(cutoff.getValue()).isAfter(Instant.now().minus(91, ChronoUnit.DAYS));
  }

  @Test
  void swallowsFailuresSoSchedulerSurvives() {
    NotificationRetentionTask task =
        new NotificationRetentionTask(notificationService, taskMetrics, Duration.ofDays(90));
    when(notificationService.purgeReadOlderThan(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new RuntimeException("db down"));

    // Must not propagate.
    task.purgeExpiredReadNotifications();
  }
}
