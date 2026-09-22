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

package de.greluc.krt.profit.basetool.ingest.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code basetool_ingest_gate_enforcing{gate}} (ING-SEC-03, REQ-INGEST-011): the
 * value per gate, the audit-only carve-out that does NOT apply to the audience, the bounded label
 * set, and the rule that no configured value reaches the log.
 */
class IngestGatePostureMetricTest {

  private final MeterRegistry registry = new SimpleMeterRegistry();

  private double gate(String gate) {
    Gauge gauge =
        registry.find(MetricNames.INGEST_GATE_ENFORCING).tag(MetricNames.TAG_GATE, gate).gauge();
    assertThat(gauge).as("gate %s is published", gate).isNotNull();
    return gauge.value();
  }

  private IngestGatePostureMetric register(
      ClientIdentityProperties properties, List<String> audiences) {
    IngestGatePostureMetric metric = new IngestGatePostureMetric(registry, properties, audiences);
    metric.register();
    return metric;
  }

  @Test
  void reportsEveryGateOffOnAnUnconfiguredGateway() {
    register(new ClientIdentityProperties(List.of(), "", List.of(), false), List.of());

    assertThat(gate(IngestGatePostureMetric.GATE_AZP)).isZero();
    assertThat(gate(IngestGatePostureMetric.GATE_SCOPE)).isZero();
    assertThat(gate(IngestGatePostureMetric.GATE_TOOL)).isZero();
    assertThat(gate(IngestGatePostureMetric.GATE_AUDIENCE)).isZero();
  }

  @Test
  void reportsEveryConfiguredGateOnWhenEnforcing() {
    register(
        new ClientIdentityProperties(
            List.of("basetool-sc-extractor"),
            "extractor-ingest-only",
            List.of("basetool-sc-extractor"),
            false),
        List.of("basetool-ingest"));

    assertThat(gate(IngestGatePostureMetric.GATE_AZP)).isEqualTo(1.0d);
    assertThat(gate(IngestGatePostureMetric.GATE_SCOPE)).isEqualTo(1.0d);
    assertThat(gate(IngestGatePostureMetric.GATE_TOOL)).isEqualTo(1.0d);
    assertThat(gate(IngestGatePostureMetric.GATE_AUDIENCE)).isEqualTo(1.0d);
  }

  /**
   * Audit-only counts but never refuses, so the three client-identity gates report 0 — but it does
   * not reach the audience, which lives in the decoder and refuses from the moment it is set. That
   * asymmetry is exactly what bit production on 2026-08-03.
   */
  @Test
  void auditOnlyTurnsTheClientGatesOffButNotTheAudience() {
    register(
        new ClientIdentityProperties(List.of("c"), "s", List.of("t"), true),
        List.of("basetool-ingest"));

    assertThat(gate(IngestGatePostureMetric.GATE_AZP)).isZero();
    assertThat(gate(IngestGatePostureMetric.GATE_SCOPE)).isZero();
    assertThat(gate(IngestGatePostureMetric.GATE_TOOL)).isZero();
    assertThat(gate(IngestGatePostureMetric.GATE_AUDIENCE)).isEqualTo(1.0d);
  }

  /** Blank audience entries are configuration noise, exactly as the decoder treats them. */
  @Test
  void blankAudiencesDoNotCountAsEnforcing() {
    register(new ClientIdentityProperties(List.of(), "", List.of(), false), List.of(" ", ""));

    assertThat(gate(IngestGatePostureMetric.GATE_AUDIENCE)).isZero();
  }

  /** The label set is the four literals and nothing derived from configuration (REQ-OBS-011). */
  @Test
  void publishesExactlyFourBoundedSeries() {
    register(
        new ClientIdentityProperties(List.of("a", "b"), "s", List.of("t"), false),
        List.of("basetool-ingest"));

    assertThat(registry.find(MetricNames.INGEST_GATE_ENFORCING).gauges())
        .extracting(gauge -> gauge.getId().getTag(MetricNames.TAG_GATE))
        .containsExactlyInAnyOrder("azp", "scope", "tool", "audience");
  }

  /**
   * The posture line is logged at WARN while the audience is off and carries only booleans and
   * counts — never a client id, scope, tool or audience value (REQ-OBS-004).
   */
  @Test
  void logsThePostureWithoutAnyConfiguredValue() {
    List<ILoggingEvent> events =
        LogCapture.capture(
            IngestGatePostureMetric.class,
            Level.INFO,
            () ->
                register(
                    new ClientIdentityProperties(
                        List.of("secret-client"), "secret-scope", List.of("secret-tool"), false),
                    List.of()));

    assertThat(events).hasSize(1);
    ILoggingEvent event = events.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains("azp=on(1) scope=on tool=on(1) audience=off(0) auditOnly=false")
        .doesNotContain("secret-client")
        .doesNotContain("secret-scope")
        .doesNotContain("secret-tool");
  }

  /** With the audience on the same line is informational, not a warning. */
  @Test
  void logsAtInfoOnceTheAudienceIsOn() {
    List<ILoggingEvent> events =
        LogCapture.capture(
            IngestGatePostureMetric.class,
            Level.INFO,
            () ->
                register(
                    new ClientIdentityProperties(List.of(), "", List.of(), false),
                    List.of("basetool-ingest")));

    assertThat(events)
        .singleElement()
        .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    assertThat(events.getFirst().getFormattedMessage()).doesNotContain("basetool-ingest");
  }
}
