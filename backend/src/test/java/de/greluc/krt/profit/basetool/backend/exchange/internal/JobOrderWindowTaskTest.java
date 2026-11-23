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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The nightly job-order window run as a scheduled job (REQ-OBS-025): it records as {@code
 * job_order_exchange_window}, counts the orders it aged out as items, records a failed run and
 * publishes its enabled gauge for {@code ScheduledJobStale}.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderWindowTaskTest {

  private static final String TASK = "job_order_exchange_window";

  @Mock private JobOrderWindowRun run;

  private SimpleMeterRegistry registry;
  private JobOrderWindowTask task;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    task = new JobOrderWindowTask(run, new TaskMetrics(registry));
  }

  @Test
  void aRunRecordsTheWindowJobWithTheOrdersItAgedOutAsItems() {
    when(run.ageOut()).thenReturn(3);

    task.ageOut();

    assertThat(counter(MetricNames.SCHEDULED_JOB_EXECUTIONS, MetricNames.OUTCOME_SUCCESS))
        .isEqualTo(1.0);
    assertThat(
            registry.get(MetricNames.SCHEDULED_JOB_ITEMS).tag(MetricNames.TAG_JOB, TASK).counter())
        .extracting(Counter::count)
        .isEqualTo(3.0);
  }

  @Test
  void aFailedRunIsRecordedAsAFailure() {
    when(run.ageOut()).thenThrow(new IllegalStateException("database unreachable"));

    task.ageOut();

    assertThat(counter(MetricNames.SCHEDULED_JOB_EXECUTIONS, MetricNames.OUTCOME_FAILURE))
        .isEqualTo(1.0);
  }

  @Test
  void theTaskPublishesItsEnabledGauge() {
    task.publishEnabledGauge();

    assertThat(
            registry.get(MetricNames.SCHEDULED_JOB_ENABLED).tag(MetricNames.TAG_JOB, TASK).gauge())
        .extracting(Gauge::value)
        .isEqualTo(1.0);
  }

  /**
   * Reads one executions counter of the window job.
   *
   * @param name the counter's name
   * @param outcome the outcome tag value
   * @return the count, {@code 0} when the counter was never registered
   */
  private double counter(String name, String outcome) {
    Counter counter =
        registry
            .find(name)
            .tag(MetricNames.TAG_JOB, TASK)
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }
}
