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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
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

/** The nightly exchange connection sweep and its startup check (REQ-XCH-035). */
@ExtendWith(MockitoExtension.class)
class ExchangeConnectionRetentionTaskTest {

  @Mock private ExchangeConnectionRetentionService retentionService;

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final TaskMetrics taskMetrics = new TaskMetrics(meterRegistry);

  private ExchangeConnectionRetentionTask task(Duration connections, Duration changes) {
    return new ExchangeConnectionRetentionTask(
        retentionService,
        new ExchangeConnectionRetentionProperties(true, connections),
        new ExchangeChangeRetentionProperties(true, changes),
        taskMetrics);
  }

  @Test
  void theSweepDeletesWhatIsOlderThanTheRetentionAndCountsIt() {
    when(retentionService.purgeBefore(any()))
        .thenReturn(new ExchangeConnectionRetentionService.Purged(2, 1));

    task(Duration.ofDays(90), Duration.ofDays(90)).purgeExpiredConnections();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(retentionService).purgeBefore(cutoff.capture());
    assertThat(cutoff.getValue())
        .isBeforeOrEqualTo(Instant.now().minus(90, ChronoUnit.DAYS))
        .isAfter(Instant.now().minus(91, ChronoUnit.DAYS));
    assertThat(
            meterRegistry
                .get(MetricNames.SCHEDULED_JOB_ITEMS)
                .tag(MetricNames.TAG_JOB, ScheduledJob.EXCHANGE_CONNECTION_RETENTION.label())
                .counter()
                .count())
        .isEqualTo(3.0);
  }

  @Test
  void aFailedSweepIsSwallowedSoTheSchedulerSurvives() {
    when(retentionService.purgeBefore(any())).thenThrow(new IllegalStateException("db down"));

    assertThatCode(() -> task(Duration.ofDays(90), Duration.ofDays(90)).purgeExpiredConnections())
        .doesNotThrowAnyException();
  }

  @Test
  void aConnectionRetentionShorterThanTheChangeRetentionRefusesToStart() {
    ExchangeConnectionRetentionTask task = task(Duration.ofDays(90), Duration.ofDays(120));

    assertThatThrownBy(task::verifyAndPublishEnabledGauge)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.exchange.change-retention.max-age");
  }

  @Test
  void anEqualOrLongerConnectionRetentionStartsAndPublishesTheEnabledGauge() {
    task(Duration.ofDays(90), Duration.ofDays(90)).verifyAndPublishEnabledGauge();
    task(Duration.ofDays(120), Duration.ofDays(90)).verifyAndPublishEnabledGauge();

    assertThat(
            meterRegistry
                .get(MetricNames.SCHEDULED_JOB_ENABLED)
                .tag(MetricNames.TAG_JOB, ScheduledJob.EXCHANGE_CONNECTION_RETENTION.label())
                .gauge()
                .value())
        .isEqualTo(1.0);
  }
}
