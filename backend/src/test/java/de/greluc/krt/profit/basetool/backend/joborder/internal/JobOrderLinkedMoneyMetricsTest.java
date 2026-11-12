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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Linked money attempts contribute aggregate gauges and a separate technical failure counter. */
@ExtendWith(MockitoExtension.class)
class JobOrderLinkedMoneyMetricsTest {
  @Mock private JobOrderLinkedMoneyAttemptRepository attempts;

  private SimpleMeterRegistry registry;
  private JobOrderLinkedMoneyMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new JobOrderLinkedMoneyMetrics(attempts, registry);
    metrics.register(registry);
  }

  @Test
  void samplingReportsCurrentCountsAndOldestAgeThenClearsEmptyGauges() {
    Instant oldest = Instant.now().minusSeconds(120);
    when(attempts.countByActiveTrueAndState(JobOrderLinkedMoneyState.WAITING)).thenReturn(4L, 0L);
    when(attempts.oldestWaiting()).thenReturn(Optional.of(oldest)).thenReturn(Optional.empty());
    when(attempts.countUnresolvedClarifications()).thenReturn(2L, 0L);
    when(attempts.countFinancialContradictions()).thenReturn(1L, 0L);

    double ageBeforeSample = Instant.now().getEpochSecond() - oldest.getEpochSecond();
    metrics.sample();
    double ageAfterSample = Instant.now().getEpochSecond() - oldest.getEpochSecond();

    assertThat(value(JobOrderMetricNames.LINKED_MONEY_WAITING)).isEqualTo(4.0);
    assertThat(value(JobOrderMetricNames.LINKED_MONEY_WAITING_AGE))
        .isBetween(ageBeforeSample, ageAfterSample);
    assertThat(value(JobOrderMetricNames.LINKED_MONEY_CLARIFICATION)).isEqualTo(2.0);
    assertThat(value(JobOrderMetricNames.LINKED_MONEY_CONTRADICTIONS)).isEqualTo(1.0);
    assertThat(registry.get(JobOrderMetricNames.LINKED_MONEY_TECHNICAL_FAILURES).counter().count())
        .isZero();

    metrics.sample();

    assertThat(value(JobOrderMetricNames.LINKED_MONEY_WAITING)).isZero();
    assertThat(value(JobOrderMetricNames.LINKED_MONEY_WAITING_AGE)).isZero();
    assertThat(value(JobOrderMetricNames.LINKED_MONEY_CLARIFICATION)).isZero();
    assertThat(value(JobOrderMetricNames.LINKED_MONEY_CONTRADICTIONS)).isZero();
  }

  @Test
  void technicalFailuresIncrementTheCounterAndAllMetersRemainUntagged() {
    metrics.technicalFailure();
    metrics.technicalFailure();

    assertThat(registry.get(JobOrderMetricNames.LINKED_MONEY_TECHNICAL_FAILURES).counter().count())
        .isEqualTo(2.0);
    assertThat(registry.getMeters())
        .hasSize(5)
        .allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
  }

  private double value(String name) {
    return registry.get(name).gauge().value();
  }
}
