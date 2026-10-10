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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Publishes the Trusted Types mode this process runs with as {@code
 * basetool_trusted_types_mode{mode}}, so the effective mode of a deploy can be read from Prometheus
 * (REQ-SEC-064, ADR-0239).
 *
 * <p>The mode is resolved once at startup from the same property and parser as {@link
 * SecurityConfig}.
 */
@Component
@RequiredArgsConstructor
public class TrustedTypesModeMetric implements MeterBinder {

  private final Environment environment;

  /**
   * Registers one gauge per {@link TrustedTypesMode}, {@code 1.0} for the effective mode and {@code
   * 0.0} for the other.
   *
   * @param registry the registry the gauges bind to
   */
  @Override
  public void bindTo(@NotNull MeterRegistry registry) {
    TrustedTypesMode effective =
        TrustedTypesMode.parse(environment.getProperty(TrustedTypesMode.PROPERTY));
    for (TrustedTypesMode mode : TrustedTypesMode.values()) {
      double value = mode == effective ? 1.0 : 0.0;
      Gauge.builder(MetricNames.TRUSTED_TYPES_MODE, () -> value)
          .tag(MetricNames.TAG_MODE, mode.tag())
          .description("1 on the series naming the Trusted Types mode in effect.")
          .register(registry);
    }
  }
}
