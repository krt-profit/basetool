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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code basetool_catalogue_items_with_stats} carries {@code source} with exactly {@code P4K} and
 * {@code SCWIKI} (REQ-OBS-023).
 */
class CatalogueStatBusinessGaugesTest {

  @Test
  void eachSourceHasItsSeries_atZeroBeforeAndAtItsCountAfterASample() {
    GameItemStatRepository repository = mock(GameItemStatRepository.class);
    when(repository.countItemsWithStatsBySource(GameItemStatSource.P4K)).thenReturn(1537L);
    when(repository.countItemsWithStatsBySource(GameItemStatSource.SCWIKI)).thenReturn(80L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    CatalogueStatBusinessGauges gauges = new CatalogueStatBusinessGauges(repository);

    gauges.register(registry);
    assertThat(values(registry)).isEqualTo(Map.of("P4K", 0.0, "SCWIKI", 0.0));

    gauges.sample();
    assertThat(values(registry)).isEqualTo(Map.of("P4K", 1537.0, "SCWIKI", 80.0));
  }

  private static Map<String, Double> values(SimpleMeterRegistry registry) {
    return registry.find(CatalogueMetricNames.CATALOGUE_ITEMS_WITH_STATS).gauges().stream()
        .collect(
            Collectors.toMap(
                gauge -> gauge.getId().getTag(CatalogueMetricNames.TAG_SOURCE), Gauge::value));
  }
}
