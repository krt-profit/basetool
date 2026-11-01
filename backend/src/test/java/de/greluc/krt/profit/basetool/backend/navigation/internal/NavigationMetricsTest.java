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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of the navigation meters (REQ-OBS-011). */
class NavigationMetricsTest {

  private final MeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName("the write counter exists at zero for every section and outcome")
  void registersEveryWriteCounterAtZero() {
    new NavigationMetrics(registry).registerWriteCounters();

    for (NavigationSection section : NavigationSection.values()) {
      for (NavigationWriteOutcome outcome : NavigationWriteOutcome.values()) {
        assertThat(
                registry
                    .get(NavigationMetrics.PREFERENCE_WRITES)
                    .tag(NavigationMetrics.TAG_SECTION, section.getTag())
                    .tag(MetricNames.TAG_OUTCOME, outcome.getTag())
                    .counter()
                    .count())
            .isZero();
      }
    }
  }

  @Test
  @DisplayName("recording a write increments only its own section and outcome")
  void recordsOneWrite() {
    NavigationMetrics metrics = new NavigationMetrics(registry);
    metrics.registerWriteCounters();

    metrics.recordWrite(NavigationSection.FAVOURITES, NavigationWriteOutcome.CONFLICT);

    assertThat(count(NavigationWriteOutcome.CONFLICT)).isEqualTo(1);
    assertThat(count(NavigationWriteOutcome.OK)).isZero();
  }

  @Test
  @DisplayName("the personalised-accounts gauge reports the sampled count per section")
  void gaugeReportsTheSampledCount() {
    NavigationPreferencesRepository repository = mock(NavigationPreferencesRepository.class);
    when(repository.countWithFavourites()).thenReturn(7L);
    when(repository.countWithHidden()).thenReturn(3L);
    NavigationAppearanceRepository appearances = mock(NavigationAppearanceRepository.class);
    when(appearances.countPersonalised()).thenReturn(4L);
    NavigationBusinessGauges gauges = new NavigationBusinessGauges(repository, appearances);

    gauges.register(registry);
    assertThat(gauge(NavigationSection.FAVOURITES)).isZero();
    assertThat(gauge(NavigationSection.HIDDEN)).isZero();
    gauges.sample();

    assertThat(gauge(NavigationSection.FAVOURITES)).isEqualTo(7);
    assertThat(gauge(NavigationSection.HIDDEN)).isEqualTo(3);
    assertThat(gauge(NavigationSection.APPEARANCE)).isEqualTo(4);
  }

  private double count(NavigationWriteOutcome outcome) {
    return registry
        .get(NavigationMetrics.PREFERENCE_WRITES)
        .tag(NavigationMetrics.TAG_SECTION, NavigationSection.FAVOURITES.getTag())
        .tag(MetricNames.TAG_OUTCOME, outcome.getTag())
        .counter()
        .count();
  }

  private double gauge(NavigationSection section) {
    return registry
        .get(NavigationMetrics.PERSONALISED_ACCOUNTS)
        .tag(NavigationMetrics.TAG_SECTION, section.getTag())
        .gauge()
        .value();
  }
}
