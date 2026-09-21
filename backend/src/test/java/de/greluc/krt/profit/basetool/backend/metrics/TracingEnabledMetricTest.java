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

package de.greluc.krt.profit.basetool.backend.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TracingEnabledMetric}.
 *
 * <p>The gauge exists so an alert can require "tracing is on" positively before it treats a silent
 * trace pipeline as a fault, so the case that matters most is the one where tracing is OFF: the
 * series must still be there, reporting zero, rather than absent. An absent series is what a module
 * that is not being scraped looks like, and conflating the two is exactly the ambiguity this metric
 * was added to remove.
 */
class TracingEnabledMetricTest {

  private SimpleMeterRegistry registry;
  private TracingEnabledMetric metric;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metric = new TracingEnabledMetric(registry);
  }

  @Test
  @DisplayName("reports 1 while tracing is configured on")
  void reportsOneWhenEnabled() {
    metric.setTracingEnabled(true);
    metric.register();

    Gauge gauge = registry.find(MetricNames.TRACING_ENABLED).gauge();
    assertNotNull(gauge, "the gauge must be registered");
    assertEquals(1.0d, gauge.value(), "tracing is on, so the gauge reports 1");
  }

  @Test
  @DisplayName("reports 0 rather than disappearing while tracing is off")
  void reportsZeroWhenDisabled() {
    metric.setTracingEnabled(false);
    metric.register();

    Gauge gauge = registry.find(MetricNames.TRACING_ENABLED).gauge();
    assertNotNull(
        gauge,
        "the series must exist even when tracing is off -- absence is what an unscraped module "
            + "looks like, and TraceIngestSilent has to tell the two apart");
    assertEquals(0.0d, gauge.value(), "tracing is off, so the gauge reports 0");
  }

  @Test
  @DisplayName("publishes under the documented metric name")
  void usesTheDocumentedName() {
    metric.setTracingEnabled(true);
    metric.register();

    assertEquals(
        "basetool.tracing.enabled",
        MetricNames.TRACING_ENABLED,
        "the constant is what the alert rule's basetool_tracing_enabled is derived from; renaming "
            + "it silently breaks TraceIngestSilent");
    assertNotNull(registry.find("basetool.tracing.enabled").gauge());
  }

  @Test
  @DisplayName("registers exactly one series, not one per call")
  void registersOneSeries() {
    metric.setTracingEnabled(true);
    metric.register();
    metric.register();

    assertEquals(
        1,
        registry.find(MetricNames.TRACING_ENABLED).gauges().size(),
        "Micrometer de-duplicates by name and tags; a second registration must not add a series");
  }

  @Test
  @DisplayName("keeps the registry it was constructed with")
  void keepsItsRegistry() {
    assertEquals(registry, metric.registry(), "the gauge must land in the injected registry");
  }
}
