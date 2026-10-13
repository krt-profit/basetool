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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The operation module's business gauges: planned and active operations and the age of the oldest
 * planned one (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class OperationBusinessGauges implements BusinessGaugeSource {

  private final OperationRepository operationRepository;

  private final AtomicLong operationPlanned = new AtomicLong();
  private final AtomicLong operationActive = new AtomicLong();
  private final AtomicLong operationOldestAge = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(
        registry, MetricNames.OPERATION_OPEN, operationPlanned, OperationStatus.PLANNED.name());
    BusinessGauges.countGauge(
        registry, MetricNames.OPERATION_OPEN, operationActive, OperationStatus.ACTIVE.name());
    BusinessGauges.ageGauge(registry, MetricNames.OPERATION_OPEN_OLDEST_AGE, operationOldestAge);
  }

  @Override
  public void sample() {
    operationPlanned.set(operationRepository.countByStatus(OperationStatus.PLANNED));
    operationActive.set(operationRepository.countByStatus(OperationStatus.ACTIVE));
    operationOldestAge.set(
        BusinessGauges.ageSeconds(
            operationRepository.findOldestCreatedAtByStatus(OperationStatus.PLANNED)));
  }
}
