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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * {@code basetool_catalogue_items_unobtainable} counts the game items an admin hid as not
 * obtainable, untagged (REQ-P4K-048, REQ-OBS-022).
 */
class UnobtainableItemBusinessGaugesTest {

  @Test
  void theGaugeIsRegisteredAtZero_andReadsTheCountOnEverySample() {
    GameItemRepository repository = mock(GameItemRepository.class);
    when(repository.countByUnobtainableSinceIsNotNull()).thenReturn(7L, 6L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    UnobtainableItemBusinessGauges gauges = new UnobtainableItemBusinessGauges(repository);

    gauges.register(registry);
    Gauge gauge = registry.get(CatalogueMetricNames.CATALOGUE_ITEMS_UNOBTAINABLE).gauge();
    assertThat(gauge.getId().getTags()).isEmpty();
    assertThat(gauge.value()).isZero();

    gauges.sample();
    assertThat(gauge.value()).isEqualTo(7.0);

    gauges.sample();
    assertThat(gauge.value()).isEqualTo(6.0);
  }
}
