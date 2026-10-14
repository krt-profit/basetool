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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The supply module's business gauges: the needs open for donations and the pledges awaiting their
 * receipt (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class SupplyBusinessGauges implements BusinessGaugeSource {

  private final SupplyNeedRepository needRepository;
  private final SupplyDonationRepository donationRepository;

  private final AtomicLong needsOpen = new AtomicLong();
  private final AtomicLong donationsPledged = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(registry, MetricNames.SUPPLY_NEEDS_OPEN, needsOpen);
    BusinessGauges.countGauge(
        registry, MetricNames.SUPPLY_DONATIONS_PLEDGED_OPEN, donationsPledged);
  }

  @Override
  public void sample() {
    needsOpen.set(needRepository.countByStatus(NeedStatus.OPEN));
    donationsPledged.set(donationRepository.countByStatus(DonationStatus.PLEDGED));
  }
}
