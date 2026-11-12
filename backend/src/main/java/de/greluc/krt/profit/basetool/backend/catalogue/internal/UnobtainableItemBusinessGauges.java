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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The gauge {@code basetool_catalogue_items_unobtainable}: the game items an admin hid as not
 * obtainable in the game (REQ-P4K-048, REQ-OBS-022), read fresh on every sample.
 */
@Component
@RequiredArgsConstructor
public class UnobtainableItemBusinessGauges implements BusinessGaugeSource {

  private final GameItemRepository gameItemRepository;

  private final AtomicLong unobtainable = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    Gauge.builder(
            CatalogueMetricNames.CATALOGUE_ITEMS_UNOBTAINABLE,
            unobtainable,
            AtomicLong::doubleValue)
        .register(registry);
  }

  @Override
  public void sample() {
    unobtainable.set(gameItemRepository.countByUnobtainableSinceIsNotNull());
  }
}
