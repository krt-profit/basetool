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

package de.greluc.krt.profit.basetool.ingest.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code basetool_ingest_gate_enforcing{gate="audience"}} (ING-SEC-03,
 * REQ-INGEST-011): the value, the single bounded series, and the rule that no configured value
 * reaches the log.
 */
class IngestGatePostureMetricTest {

  private final MeterRegistry registry = new SimpleMeterRegistry();

  private double audienceGate() {
    Gauge gauge =
        registry
            .find(MetricNames.INGEST_GATE_ENFORCING)
            .tag(MetricNames.TAG_GATE, IngestGatePostureMetric.GATE_AUDIENCE)
            .gauge();
    assertThat(gauge).as("the audience gate is published").isNotNull();
    return gauge.value();
  }

  private IngestGatePostureMetric register(List<String> audiences) {
    IngestGatePostureMetric metric = new IngestGatePostureMetric(registry, audiences);
    metric.register();
    return metric;
  }

  @Test
  void reportsTheGateOffWithoutAnAudience() {
    register(List.of());

    assertThat(audienceGate()).isZero();
  }

  @Test
  void reportsTheGateOnWithAnAudience() {
    register(List.of("basetool-ingest"));

    assertThat(audienceGate()).isEqualTo(1.0d);
  }

  /** Blank audience entries are configuration noise, exactly as the decoder treats them. */
  @Test
  void blankAudiencesDoNotCountAsEnforcing() {
    register(List.of(" ", ""));

    assertThat(audienceGate()).isZero();
  }

  /** The label set is the one literal and nothing derived from configuration (REQ-OBS-011). */
  @Test
  void publishesExactlyOneBoundedSeries() {
    register(List.of("basetool-ingest"));

    assertThat(registry.find(MetricNames.INGEST_GATE_ENFORCING).gauges())
        .extracting(gauge -> gauge.getId().getTag(MetricNames.TAG_GATE))
        .containsExactly("audience");
  }

  /**
   * The posture line is logged at WARN while the audience is off and carries only a boolean and a
   * count (REQ-OBS-004).
   */
  @Test
  void logsThePostureAtWarnWhileTheAudienceIsOff() {
    List<ILoggingEvent> events =
        LogCapture.capture(IngestGatePostureMetric.class, Level.INFO, () -> register(List.of()));

    assertThat(events).hasSize(1);
    ILoggingEvent event = events.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage()).contains("audience=off(0)");
  }

  /** With the audience on the same line is informational and names no audience. */
  @Test
  void logsAtInfoOnceTheAudienceIsOn() {
    List<ILoggingEvent> events =
        LogCapture.capture(
            IngestGatePostureMetric.class, Level.INFO, () -> register(List.of("basetool-ingest")));

    assertThat(events)
        .singleElement()
        .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    assertThat(events.getFirst().getFormattedMessage())
        .contains("audience=on(1)")
        .doesNotContain("basetool-ingest");
  }
}
