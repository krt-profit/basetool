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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Pins the inert tracing default (REQ-OBS-009): with {@code management.opentelemetry.enabled=false}
 * no SDK tracer provider or span exporter is created.
 */
@SpringBootTest(properties = "management.opentelemetry.enabled=false")
class MonitoringTracingInertTest {

  @Autowired private ApplicationContext context;

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
