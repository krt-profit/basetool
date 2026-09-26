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

package de.greluc.krt.profit.basetool.frontend.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code basetool_tracing_enabled}, which lets the {@code TraceIngestSilent} alert tell a
 * silent trace pipeline from a switched-off one (REQ-OBS-009).
 *
 * <p>Read from {@code management.opentelemetry.enabled}, the flag Boot's OpenTelemetry
 * auto-configuration honours.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TracingEnabledMetric {

  /** Registry the gauge is published to. */
  private final MeterRegistry registry;

  /**
   * Whether this module is configured to emit spans, from {@code management.opentelemetry.enabled};
   * defaults to {@code false}. The package-private setter serves tests that construct the bean
   * directly.
   */
  @Getter
  @Setter(AccessLevel.PACKAGE)
  @Value("${management.opentelemetry.enabled:false}")
  private boolean tracingEnabled;

  /**
   * Registers the gauge once at startup with the configured, constant value and logs it at INFO.
   */
  @PostConstruct
  public void register() {
    Gauge.builder(MetricNames.TRACING_ENABLED, this, metric -> metric.tracingEnabled ? 1.0d : 0.0d)
        .description(
            "1 while this module is configured to emit spans, 0 while tracing is switched off.")
        .register(registry);
    log.info(
        "Tracing is {}; basetool_tracing_enabled reports {}.",
        tracingEnabled ? "ENABLED" : "disabled",
        tracingEnabled ? 1 : 0);
  }

  /**
   * The registry this metric publishes to, for tests that need to read the series back.
   *
   * @return the meter registry, never {@code null}
   */
  @NotNull
  MeterRegistry registry() {
    return registry;
  }
}
