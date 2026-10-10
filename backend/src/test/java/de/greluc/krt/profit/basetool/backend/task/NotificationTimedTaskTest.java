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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.NotificationTimedRunner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the scheduled trigger of the time-based notices (REQ-NOTIF-026). */
@ExtendWith(MockitoExtension.class)
class NotificationTimedTaskTest {

  private static final String TASK = "notification_timed";

  @Mock private NotificationTimedRunner runner;

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final TaskMetrics taskMetrics = new TaskMetrics(meterRegistry);

  private double executions(String outcome) {
    return meterRegistry
        .counter(
            "basetool.scheduled.job.executions", "task", TASK, MetricNames.TAG_OUTCOME, outcome)
        .count();
  }

  private double items() {
    return meterRegistry.counter("basetool.scheduled.job.items", "task", TASK).count();
  }

  @Test
  void aRunRecordsTheNoticesItRaisedAsTheItemCount() {
    when(runner.runOnce(any(Instant.class))).thenReturn(4);

    new NotificationTimedTask(runner, taskMetrics).raiseTimedNotices();

    assertThat(executions(MetricNames.OUTCOME_SUCCESS)).isEqualTo(1);
    assertThat(items()).isEqualTo(4);
  }

  @Test
  void aFailingRunIsRecordedAndSwallowedSoTheSchedulerSurvives() {
    when(runner.runOnce(any(Instant.class))).thenThrow(new IllegalStateException("boom"));

    new NotificationTimedTask(runner, taskMetrics).raiseTimedNotices();

    assertThat(executions(MetricNames.OUTCOME_FAILURE)).isEqualTo(1);
    assertThat(executions(MetricNames.OUTCOME_SUCCESS)).isZero();
  }

  @Test
  void theEnabledGaugeIsPublishedForTheStalenessAlert() {
    new NotificationTimedTask(runner, taskMetrics).publishEnabledGauge();

    assertThat(
            meterRegistry.find("basetool.scheduled.job.enabled").tag("task", TASK).gauge().value())
        .isEqualTo(1.0);
  }
}
