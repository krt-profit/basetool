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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import de.greluc.krt.profit.basetool.frontend.shell.web.QuickAccessSearchMetrics.Outcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link QuickAccessSearchMetrics}: bounded series, all registered at zero. */
class QuickAccessSearchMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final QuickAccessSearchMetrics metrics = new QuickAccessSearchMetrics(registry);

  /** Every kind and outcome pair exists at zero before the first search. */
  @Test
  void registersEveryPairAtZero() {
    List<Meter> meters = List.copyOf(registry.find(MetricNames.QUICK_ACCESS_SEARCH).meters());

    assertThat(meters).hasSize(3 * 4);
    assertThat(meters)
        .extracting(m -> m.getId().getTag(MetricNames.TAG_KIND))
        .containsOnly("mission", "operation", "material");
    assertThat(meters)
        .extracting(m -> m.getId().getTag(MetricNames.TAG_OUTCOME))
        .containsOnly("hits", "empty", "error", "timeout");
    assertThat(registry.find(MetricNames.QUICK_ACCESS_SEARCH).counters())
        .allSatisfy(c -> assertThat(c.count()).isZero());
  }

  /** A recorded outcome increments exactly its own series. */
  @Test
  void recordsOneSeries() {
    metrics.record(QuickAccessSearchKind.OPERATION, Outcome.TIMEOUT);

    assertThat(
            registry
                .get(MetricNames.QUICK_ACCESS_SEARCH)
                .tags(
                    List.of(
                        Tag.of(MetricNames.TAG_KIND, "operation"),
                        Tag.of(MetricNames.TAG_OUTCOME, "timeout")))
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.find(MetricNames.QUICK_ACCESS_SEARCH).counters())
        .filteredOn(c -> c.count() > 0)
        .hasSize(1);
  }
}
