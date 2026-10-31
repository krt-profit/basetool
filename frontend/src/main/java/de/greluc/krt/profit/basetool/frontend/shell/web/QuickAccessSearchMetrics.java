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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Counts {@code basetool_quick_access_search_total{kind,outcome}}, every series registered at zero
 * (REQ-UI-033, REQ-OBS-011).
 */
@Component
public class QuickAccessSearchMetrics {

  /** How one entity type answered a search. */
  public enum Outcome {
    /** At least one hit. */
    HITS,
    /** Answered without a hit. */
    EMPTY,
    /** The read failed. */
    ERROR,
    /** The read did not answer in time. */
    TIMEOUT;

    /**
     * The label value.
     *
     * @return the lower-case name
     */
    public @NotNull String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final Map<QuickAccessSearchKind, Map<Outcome, Counter>> counters =
      new EnumMap<>(QuickAccessSearchKind.class);

  /**
   * Registers every kind and outcome series.
   *
   * @param registry the registry the counters live in
   */
  public QuickAccessSearchMetrics(@NotNull MeterRegistry registry) {
    for (QuickAccessSearchKind kind : QuickAccessSearchKind.values()) {
      Map<Outcome, Counter> byOutcome = new EnumMap<>(Outcome.class);
      for (Outcome outcome : Outcome.values()) {
        byOutcome.put(
            outcome,
            Counter.builder(MetricNames.QUICK_ACCESS_SEARCH)
                .description("Entity types asked by a quick-access search, by outcome")
                .tag(MetricNames.TAG_KIND, kind.key())
                .tag(MetricNames.TAG_OUTCOME, outcome.label())
                .register(registry));
      }
      counters.put(kind, byOutcome);
    }
  }

  /**
   * Counts one type's answer.
   *
   * @param kind the type asked
   * @param outcome how it answered
   */
  public void record(@NotNull QuickAccessSearchKind kind, @NotNull Outcome outcome) {
    counters.get(kind).get(outcome).increment();
  }
}
