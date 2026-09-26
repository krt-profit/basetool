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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration tests asserting that with {@code MONITORING_TRACING_ENABLED} unset the tracing
 * instrumentation contributes no tracer and no span exporter (REQ-OBS-009).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "management.opentelemetry.enabled=false")
class MonitoringTracingInertTest {

  @Autowired private ApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void shouldNotCreateSdkTracerProviderWhenTracingDisabled() {
    assertThat(context.getBeansOfType(SdkTracerProvider.class))
        .as("no SDK tracer provider must exist while tracing is disabled")
        .isEmpty();
  }

  @Test
  void shouldNotCreateAnySpanExporterWhenTracingDisabled() {
    assertThat(context.getBeansOfType(SpanExporter.class))
        .as("no SpanExporter bean must exist while tracing is disabled")
        .isEmpty();
  }
}
