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

package de.greluc.krt.profit.basetool.frontend.kernel.session;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests for {@link SessionTypeAllowListModeMetric}: exactly the series of the configured mode reads
 * {@code 1}, whatever the property's case, and a missing or unknown value reads as the parser's
 * fallback.
 */
class SessionTypeAllowListModeMetricTest {

  @Test
  void theConfiguredModeIsTheOnlySeriesAtOne() {
    SimpleMeterRegistry registry = bound("Enforce");

    assertThat(value(registry, "enforce")).isEqualTo(1.0);
    assertThat(value(registry, "report")).isZero();
    assertThat(value(registry, "off")).isZero();
  }

  @Test
  void offIsPublishedToo() {
    SimpleMeterRegistry registry = bound("off");

    assertThat(value(registry, "off")).isEqualTo(1.0);
    assertThat(value(registry, "enforce")).isZero();
  }

  @Test
  void aMissingOrUnknownValueReadsAsTheParsersFallback() {
    SessionTypeAllowList.Mode fallback = SessionTypeAllowList.Mode.parse(null);

    for (String raw : new String[] {null, "", "bogus"}) {
      SimpleMeterRegistry registry = bound(raw);
      assertThat(value(registry, fallback.name().toLowerCase(java.util.Locale.ROOT)))
          .isEqualTo(1.0);
    }
  }

  @Test
  void exactlyOneSeriesPerModeIsRegistered() {
    SimpleMeterRegistry registry = bound("report");

    assertThat(registry.find(MetricNames.SESSION_TYPE_ALLOW_LIST_MODE).gauges())
        .hasSize(SessionTypeAllowList.Mode.values().length);
  }

  private static SimpleMeterRegistry bound(String raw) {
    MockEnvironment environment = new MockEnvironment();
    if (raw != null) {
      environment.setProperty(SessionTypeAllowListModeMetric.PROPERTY, raw);
    }
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new SessionTypeAllowListModeMetric(environment).bindTo(registry);
    return registry;
  }

  private static double value(SimpleMeterRegistry registry, String mode) {
    return registry
        .get(MetricNames.SESSION_TYPE_ALLOW_LIST_MODE)
        .tag(MetricNames.TAG_MODE, mode)
        .gauge()
        .value();
  }
}
