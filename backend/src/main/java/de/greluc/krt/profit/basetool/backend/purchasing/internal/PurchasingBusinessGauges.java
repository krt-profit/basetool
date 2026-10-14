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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The purchasing module's business gauges: the deals per state, the settlements past their due
 * date, the global pause and the active delegates (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class PurchasingBusinessGauges implements BusinessGaugeSource {

  private final PurchasingDealRepository dealRepository;
  private final PurchasingDelegateRepository delegateRepository;
  private final PurchasingSettingRepository settingRepository;

  private final Map<DealStatus, AtomicLong> byStatus = new EnumMap<>(DealStatus.class);
  private final AtomicLong overdue = new AtomicLong();
  private final AtomicLong paused = new AtomicLong();
  private final AtomicLong delegates = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    for (DealStatus status : DealStatus.values()) {
      AtomicLong holder = new AtomicLong();
      byStatus.put(status, holder);
      BusinessGauges.countGauge(
          registry, MetricNames.PURCHASING_DEALS_BY_STATUS, holder, status.name());
    }
    BusinessGauges.countGauge(registry, MetricNames.PURCHASING_SETTLEMENTS_OVERDUE, overdue);
    BusinessGauges.countGauge(registry, MetricNames.PURCHASING_PAUSED, paused);
    BusinessGauges.countGauge(registry, MetricNames.PURCHASING_DELEGATES_ACTIVE, delegates);
  }

  @Override
  public void sample() {
    Instant now = Instant.now();
    for (Map.Entry<DealStatus, AtomicLong> entry : byStatus.entrySet()) {
      entry.getValue().set(dealRepository.countByStatus(entry.getKey()));
    }
    overdue.set(dealRepository.countOverdue(now));
    paused.set(
        settingRepository
                .findById(PurchasingSetting.SINGLETON_ID)
                .filter(PurchasingSetting::isPaused)
                .isPresent()
            ? 1
            : 0);
    delegates.set(delegateRepository.countActive(now));
  }
}
