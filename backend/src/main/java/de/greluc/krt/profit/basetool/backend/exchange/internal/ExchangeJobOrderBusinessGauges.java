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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The job-order fingerprint backlog (REQ-OXCH-003, REQ-OBS-025): the marked orders and the age of
 * the oldest mark, sampled by the business-metrics collector rather than the worker, so a wedged
 * worker cannot freeze its own signal.
 */
@Component
@RequiredArgsConstructor
public class ExchangeJobOrderBusinessGauges implements BusinessGaugeSource {

  private final ExchangeJobOrderMarkRepository marks;

  private final AtomicLong dirtyCount = new AtomicLong();
  private final AtomicLong dirtyOldestAge = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(
        registry, ExchangeMetricNames.EXCHANGE_JOB_ORDER_DIRTY_COUNT, dirtyCount);
    BusinessGauges.ageGauge(
        registry, ExchangeMetricNames.EXCHANGE_JOB_ORDER_DIRTY_OLDEST_AGE, dirtyOldestAge);
  }

  @Override
  public void sample() {
    dirtyCount.set(marks.countMarkedOrders());
    dirtyOldestAge.set(BusinessGauges.ageSeconds(marks.findOldestMarkedAt()));
  }
}
