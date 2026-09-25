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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Exercises the tracing instrumentation in its enabled state (REQ-OBS-009, epic #936 Phase 1b)
 * against an in-memory span exporter — no network export: server spans carry the <b>templated</b>
 * request URI (never raw path variables) and no user-identifying attributes, and an active span
 * puts {@code traceId}/{@code spanId} into the MDC for the JSON log appenders. OTLP export stays
 * off ({@code management.tracing.export.otlp.enabled=false}) so the only exporter is the in-memory
 * test double.
 */
@SpringBootTest(
    properties = {
      "management.opentelemetry.enabled=true",
      "management.tracing.export.otlp.enabled=false"
    })
@org.springframework.security.test.context.support.WithMockUser
class MonitoringTracingEnabledTest {

  /** Registers the in-memory exporter Boot wires into the tracer provider instead of OTLP. */
  @TestConfiguration
  static class InMemoryExporterConfig {

    /**
     * The in-memory span exporter the assertions read from.
     *
     * @return the exporter bean picked up by Boot's tracing auto-configuration
     */
    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }
  }

  @Autowired private WebApplicationContext context;

  @Autowired private ObservationRegistry observationRegistry;

  @Autowired private Tracer tracer;

  @Autowired private InMemorySpanExporter spanExporter;

  @Autowired private SdkTracerProvider sdkTracerProvider;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(new ServerHttpObservationFilter(observationRegistry))
            .apply(springSecurity())
            .build();
    spanExporter.reset();
  }

  @Test
  void shouldRecordServerSpanWithTemplatedUriAndNoUserIdentifyingAttributes() throws Exception {
    String rawId = UUID.randomUUID().toString();

    mockMvc.perform(get("/api/v1/locations/" + rawId + "?probe=user-entered-search-text"));
    sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertThat(spans).as("the observed request must produce at least one span").isNotEmpty();
    SpanData serverSpan = spans.getLast();
    String attributes = serverSpan.getAttributes().asMap().toString();
    assertThat(attributes)
        .contains("uri=/api/v1/locations/{id}")
        .doesNotContain("user-entered-search-text")
        .doesNotContain("probe=")
        .doesNotContain("@");
  }

  @Test
  void shouldPutTraceIdAndSpanIdIntoMdcWhileSpanIsInScope() {
    Span span = tracer.nextSpan().name("mdc-probe").start();

    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      assertThat(MDC.get("traceId")).isEqualTo(span.context().traceId());
      assertThat(MDC.get("spanId")).isEqualTo(span.context().spanId());
    } finally {
      span.end();
    }
    assertThat(MDC.get("traceId")).as("scope closed -> MDC cleaned up").isNull();
  }
}
