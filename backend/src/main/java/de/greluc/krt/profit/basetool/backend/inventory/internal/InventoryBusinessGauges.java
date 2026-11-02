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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The inventory module's business gauge: the minimum stock levels that are low right now, counted
 * from the real allocation and not from the notice marker (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
class InventoryBusinessGauges implements BusinessGaugeSource {

  /** Gauge {@code basetool_inventory_minimum_levels_below}: minimum stock levels that are low. */
  static final String MINIMUM_LEVELS_BELOW = "basetool.inventory.minimum.levels.below";

  private final StockLevelStates states;

  private final AtomicLong levelsBelow = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(registry, MINIMUM_LEVELS_BELOW, levelsBelow);
  }

  @Override
  public void sample() {
    levelsBelow.set(states.ofActiveLevels().stream().filter(state -> state.fill().low()).count());
  }
}
