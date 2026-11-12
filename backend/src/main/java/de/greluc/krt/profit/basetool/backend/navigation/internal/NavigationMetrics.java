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

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Counts the preference writes as {@code basetool_navigation_preference_writes_total{section,
 * outcome}} (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class NavigationMetrics {

  private final MeterRegistry meterRegistry;

  /** Registers the counter for every section and outcome at zero. */
  @PostConstruct
  void registerWriteCounters() {
    for (NavigationSection section : NavigationSection.values()) {
      for (NavigationWriteOutcome outcome : NavigationWriteOutcome.values()) {
        counter(section, outcome);
      }
    }
  }

  /**
   * Counts one finished write.
   *
   * @param section the section that was written
   * @param outcome how the write ended
   */
  public void recordWrite(
      @NotNull NavigationSection section, @NotNull NavigationWriteOutcome outcome) {
    counter(section, outcome).increment();
  }

  private Counter counter(NavigationSection section, NavigationWriteOutcome outcome) {
    return meterRegistry.counter(
        NavigationMetricNames.PREFERENCE_WRITES,
        NavigationMetricNames.TAG_SECTION,
        section.getTag(),
        MetricNames.TAG_OUTCOME,
        outcome.getTag());
  }
}
