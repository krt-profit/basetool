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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The gauge {@code basetool_catalogue_items_with_stats{source}}: the game items with a stored
 * blueprint stat base value per {@link GameItemStatSource} (REQ-OBS-023, ADR-0342).
 */
@Component
@RequiredArgsConstructor
public class CatalogueStatBusinessGauges implements BusinessGaugeSource {

  private final GameItemStatRepository gameItemStatRepository;

  private final Map<GameItemStatSource, AtomicLong> itemsWithStats =
      new EnumMap<>(GameItemStatSource.class);

  @Override
  public void register(@NotNull MeterRegistry registry) {
    for (GameItemStatSource source : GameItemStatSource.values()) {
      AtomicLong holder = itemsWithStats.computeIfAbsent(source, _ -> new AtomicLong());
      Gauge.builder(
              CatalogueMetricNames.CATALOGUE_ITEMS_WITH_STATS, holder, AtomicLong::doubleValue)
          .tag(CatalogueMetricNames.TAG_SOURCE, source.name())
          .register(registry);
    }
  }

  @Override
  public void sample() {
    itemsWithStats.forEach(
        (source, holder) -> holder.set(gameItemStatRepository.countItemsWithStatsBySource(source)));
  }
}
