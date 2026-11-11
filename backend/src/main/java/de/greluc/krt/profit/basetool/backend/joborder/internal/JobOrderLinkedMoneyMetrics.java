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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** Reports bounded waiting and contradiction counts from committed linked order attempts. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedMoneyMetrics implements BusinessGaugeSource {
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final MeterRegistry registry;
  private final AtomicLong waiting = new AtomicLong();
  private final AtomicLong oldestWaiting = new AtomicLong();
  private final AtomicLong clarification = new AtomicLong();
  private final AtomicLong contradictions = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry meters) {
    BusinessGauges.countGauge(meters, JobOrderMetricNames.LINKED_MONEY_WAITING, waiting);
    BusinessGauges.ageGauge(meters, JobOrderMetricNames.LINKED_MONEY_WAITING_AGE, oldestWaiting);
    BusinessGauges.countGauge(
        meters, JobOrderMetricNames.LINKED_MONEY_CLARIFICATION, clarification);
    BusinessGauges.countGauge(
        meters, JobOrderMetricNames.LINKED_MONEY_CONTRADICTIONS, contradictions);
    meters.counter(JobOrderMetricNames.LINKED_MONEY_TECHNICAL_FAILURES);
  }

  @Override
  public void sample() {
    waiting.set(attempts.countByActiveTrueAndState(JobOrderLinkedMoneyState.WAITING));
    oldestWaiting.set(BusinessGauges.ageSeconds(attempts.oldestWaiting().orElse(null)));
    clarification.set(attempts.countUnresolvedClarifications());
    contradictions.set(attempts.countFinancialContradictions());
  }

  void technicalFailure() {
    registry.counter(JobOrderMetricNames.LINKED_MONEY_TECHNICAL_FAILURES).increment();
  }
}
