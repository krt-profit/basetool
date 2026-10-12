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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** The Materialboerse's business gauges: active offers and open requests (REQ-OBS-011). */
@Component
@RequiredArgsConstructor
public class MaterialExchangeBusinessGauges implements BusinessGaugeSource {

  private final MaterialExchangeOfferRepository materialExchangeOfferRepository;
  private final MaterialExchangeRequestRepository materialExchangeRequestRepository;

  private final AtomicLong materialExchangeActive = new AtomicLong();
  private final AtomicLong materialRequestOpen = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(
        registry,
        MetricNames.MATERIAL_EXCHANGE_ACTIVE,
        materialExchangeActive,
        MaterialExchangeOfferStatus.ACTIVE.name());
    BusinessGauges.countGauge(
        registry,
        MetricNames.MATERIAL_REQUEST_OPEN,
        materialRequestOpen,
        MaterialExchangeRequestStatus.ACTIVE.name());
  }

  @Override
  public void sample() {
    materialExchangeActive.set(
        materialExchangeOfferRepository.countByStatus(MaterialExchangeOfferStatus.ACTIVE));
    materialRequestOpen.set(
        materialExchangeRequestRepository.countByStatus(MaterialExchangeRequestStatus.ACTIVE));
  }
}
