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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the inert default of the tracing instrumentation (REQ-OBS-009, epic #936 Phase 1b): with
 * {@code MONITORING_TRACING_ENABLED} unset, {@code management.opentelemetry.enabled} is {@code
 * false} and the OpenTelemetry starter on the classpath must contribute nothing — no SDK tracer
 * provider, no span exporter, and therefore no network export attempts or exporter errors in
 * dev/test/e2e or a prod host without the monitoring stack.
 */
@SpringBootTest(properties = "management.opentelemetry.enabled=false")
class MonitoringTracingInertTest {

  @Autowired private ApplicationContext context;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @Test
  void shouldNotCreateSdkTracerProviderWhenTracingDisabled() {
    // Given the default configuration (gate pinned false, mirroring an unset env var)
    // When / Then: without an SDK tracer provider no span can ever be recorded or exported. (A
    // Micrometer OtelTracer bean may still exist by Boot 4 design, but it is backed by the no-op
    // OpenTelemetry fallback.)
    assertThat(context.getBeansOfType(SdkTracerProvider.class))
        .as("no SDK tracer provider must exist while tracing is disabled")
        .isEmpty();
  }

  @Test
  void shouldNotCreateAnySpanExporterWhenTracingDisabled() {
    // Given / When / Then: no exporter means no OTLP connection attempts against the (absent)
    // collector — the "fully inert" guarantee of REQ-OBS-009.
    assertThat(context.getBeansOfType(SpanExporter.class))
        .as("no SpanExporter bean must exist while tracing is disabled")
        .isEmpty();
  }
}
