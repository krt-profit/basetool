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
 * Publishes {@code basetool_tracing_enabled}, the one signal that tells a silent trace pipeline
 * apart from a switched-off one.
 *
 * <p>Until 2026-09-20 nothing in the monitoring plane looked at the trace path at all — no alert on
 * the collector's receiver, none on the trace store's ingest. A pipeline that had never carried a
 * single span therefore reported exactly like a healthy one, and it took a runtime migration and a
 * hand-written probe to find it: on the Podman host the application containers could not resolve
 * {@code alloy}, so every span was dropped here, in this module's own exporter, which logs nothing.
 *
 * <p>The missing piece for an alert is not the span count — {@code
 * otelcol_receiver_accepted_spans_total} is right there — but the ability to read "and it was
 * supposed to be carrying some". Tracing is inert by default (REQ-OBS-009): dev, test, e2e and any
 * host without the monitoring stack run with it off, and their zero span count is correct. This
 * gauge is what lets {@code TraceIngestSilent} require {@code basetool_tracing_enabled == 1} before
 * it treats silence as a fault.
 *
 * <p>The value is read from {@code management.opentelemetry.enabled}, which is the flag Boot's OTel
 * auto-configuration actually honours ({@code @ConditionalOnEnabledOpenTelemetry}) — deliberately
 * not the legacy {@code management.tracing.enabled}, which Boot 4.1 consumes nowhere and which
 * would make this gauge report a state no exporter agrees with.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TracingEnabledMetric {

  /** Registry the gauge is published to. */
  private final MeterRegistry registry;

  /**
   * Whether this module is configured to emit spans, from {@code management.opentelemetry.enabled}.
   *
   * <p>Defaults to {@code false} to match the property's own default, so a deployment that never
   * sets it reports {@code 0} rather than failing to start.
   *
   * <p>{@code @Getter} publishes this as {@code isTracingEnabled()} — the same value the gauge
   * reports, for a caller that wants it without reading the registry. The package-private
   * {@code @Setter} exists for the tests, which construct this bean directly rather than through
   * Spring and therefore never have the {@code @Value} injected.
   */
  @Getter
  @Setter(AccessLevel.PACKAGE)
  @Value("${management.opentelemetry.enabled:false}")
  private boolean tracingEnabled;

  /**
   * Registers the gauge once, at startup, with the configured value.
   *
   * <p>Deliberately a constant rather than a live supplier: the property is read at startup and
   * cannot change without one, so a supplier would suggest a liveness the value does not have. The
   * log line is at INFO because "tracing is off" is a fact worth finding in a startup log when the
   * spans are missing and nobody remembers which way the flag was left.
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
