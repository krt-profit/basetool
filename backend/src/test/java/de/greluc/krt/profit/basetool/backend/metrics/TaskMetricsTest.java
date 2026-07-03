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

package de.greluc.krt.profit.basetool.backend.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaskMetrics} — the shared scheduled-job instrumentation wrapper: the
 * executions counter (success/failure), the duration timer, the lazily-registered last-success
 * gauge, and the catch-record-swallow contract that keeps the scheduler thread alive.
 */
class TaskMetricsTest {

  private SimpleMeterRegistry registry;
  private TaskMetrics taskMetrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    taskMetrics = new TaskMetrics(registry);
  }

  @Test
  void record_onSuccess_incrementsSuccessCounterTimesDurationAndAdvancesLastSuccessGauge() {
    // Given / When
    taskMetrics.record(ScheduledJob.USER_SYNC, () -> {});

    // Then — one successful execution, one duration sample, gauge advanced past 0.
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.USER_SYNC.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_SUCCESS)
                .counter()
                .count())
        .isEqualTo(1.0d);
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_DURATION)
                .tag(MetricNames.TAG_JOB, ScheduledJob.USER_SYNC.label())
                .timer()
                .count())
        .isEqualTo(1L);
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_LAST_SUCCESS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.USER_SYNC.label())
                .gauge()
                .value())
        .isGreaterThan(0.0d);
  }

  @Test
  void record_onFailure_countsFailureSwallowsAndLeavesLastSuccessAtZero() {
    // Given a body that throws / When
    assertThatCode(
            () ->
                taskMetrics.record(
                    ScheduledJob.UEX_SYNC,
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .doesNotThrowAnyException();

    // Then — counted as a failure, never a success, and the gauge stays 0 (never succeeded).
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.UEX_SYNC.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_FAILURE)
                .counter()
                .count())
        .isEqualTo(1.0d);
    assertThat(
            registry
                .find(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.UEX_SYNC.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_SUCCESS)
                .counter())
        .isNull();
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_LAST_SUCCESS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.UEX_SYNC.label())
                .gauge()
                .value())
        .isEqualTo(0.0d);
  }

  @Test
  void recordCounting_addsTheReportedItemCountToTheItemsCounterOnSuccess() {
    taskMetrics.recordCounting(ScheduledJob.NOTIFICATION_RETENTION, () -> 7);

    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_ITEMS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.NOTIFICATION_RETENTION.label())
                .counter()
                .count())
        .isEqualTo(7.0d);
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.NOTIFICATION_RETENTION.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_SUCCESS)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void recordCounting_onFailure_countsFailureAndLeavesTheItemsCounterUnregistered() {
    assertThatCode(
            () ->
                taskMetrics.recordCounting(
                    ScheduledJob.USER_SYNC,
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .doesNotThrowAnyException();

    assertThat(
            registry
                .find(MetricNames.SCHEDULED_JOB_ITEMS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.USER_SYNC.label())
                .counter())
        .isNull();
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.USER_SYNC.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_FAILURE)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void record_lazilyRegistersLastSuccessGaugeOnlyForJobsThatRan() {
    // Given only one job has ever been recorded
    taskMetrics.record(ScheduledJob.USER_SYNC, () -> {});

    // Then a never-run job has no last-success gauge at all (rather than a falsely-stale 0).
    assertThat(
            registry
                .find(MetricNames.SCHEDULED_JOB_LAST_SUCCESS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.SCWIKI_SYNC.label())
                .gauge())
        .isNull();
  }

  @Test
  void record_successAfterFailure_advancesGaugeAndKeepsBothOutcomeCounters() {
    // Given a failing run then a successful run of the same job
    taskMetrics.record(
        ScheduledJob.BANK_LEDGER_INTEGRITY,
        () -> {
          throw new IllegalStateException("transient");
        });
    double afterFailure =
        registry
            .get(MetricNames.SCHEDULED_JOB_LAST_SUCCESS)
            .tag(MetricNames.TAG_JOB, ScheduledJob.BANK_LEDGER_INTEGRITY.label())
            .gauge()
            .value();

    AtomicInteger runs = new AtomicInteger();
    taskMetrics.record(ScheduledJob.BANK_LEDGER_INTEGRITY, runs::incrementAndGet);

    // Then the body ran, the gauge advanced past its post-failure 0, and both outcomes are counted.
    assertThat(runs.get()).isEqualTo(1);
    assertThat(afterFailure).isEqualTo(0.0d);
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_LAST_SUCCESS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.BANK_LEDGER_INTEGRITY.label())
                .gauge()
                .value())
        .isGreaterThan(0.0d);
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.BANK_LEDGER_INTEGRITY.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_FAILURE)
                .counter()
                .count())
        .isEqualTo(1.0d);
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.BANK_LEDGER_INTEGRITY.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_SUCCESS)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }
}
