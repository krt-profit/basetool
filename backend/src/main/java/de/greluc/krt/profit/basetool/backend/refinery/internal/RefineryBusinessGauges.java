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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The refinery's business gauges: open and in-progress orders and the age of the oldest open one
 * (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class RefineryBusinessGauges implements BusinessGaugeSource {

  private final RefineryOrderRepository refineryOrderRepository;

  private final AtomicLong refineryOpen = new AtomicLong();
  private final AtomicLong refineryInProgress = new AtomicLong();
  private final AtomicLong refineryOldestAge = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(
        registry, MetricNames.REFINERY_ORDER_OPEN, refineryOpen, RefineryOrderStatus.OPEN.name());
    BusinessGauges.countGauge(
        registry,
        MetricNames.REFINERY_ORDER_OPEN,
        refineryInProgress,
        RefineryOrderStatus.IN_PROGRESS.name());
    BusinessGauges.ageGauge(
        registry, MetricNames.REFINERY_ORDER_OPEN_OLDEST_AGE, refineryOldestAge);
  }

  @Override
  public void sample() {
    refineryOpen.set(refineryOrderRepository.countByStatus(RefineryOrderStatus.OPEN));
    refineryInProgress.set(refineryOrderRepository.countByStatus(RefineryOrderStatus.IN_PROGRESS));
    refineryOldestAge.set(
        BusinessGauges.ageSeconds(
            refineryOrderRepository.findOldestCreatedAtByStatus(RefineryOrderStatus.OPEN)));
  }
}
