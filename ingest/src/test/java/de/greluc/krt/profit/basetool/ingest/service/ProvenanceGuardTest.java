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

package de.greluc.krt.profit.basetool.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.Provenance;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import de.greluc.krt.profit.basetool.ingest.web.ClientNotAllowedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProvenanceGuard} (REQ-INGEST-011): the payload-level half of the
 * client-identity gate, inert until {@code allowed-tools} is configured and reduced to
 * log-and-count while {@code audit-only} is set.
 *
 * <p>Also pins the log-safety contract, which matters more here than anywhere else in the gate: the
 * rejected {@code tool} is the only reject input a caller fully controls, so it is both the whole
 * diagnostic value and the one value that could forge a log line.
 */
class ProvenanceGuardTest {

  private static final String APPROVED_TOOL = "basetool-sc-extractor";

  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
  }

  /**
   * Builds a guard over the given allowlist and enforcement mode.
   *
   * @param allowedTools the configured producer allowlist
   * @param auditOnly whether rejections are counted but not enforced
   * @return the guard under test
   */
  private ProvenanceGuard guard(List<String> allowedTools, boolean auditOnly) {
    ClientIdentityProperties properties = new ClientIdentityProperties();
    properties.setAllowedTools(allowedTools);
    properties.setAuditOnly(auditOnly);
    return new ProvenanceGuard(properties, registry);
  }

  /**
   * Reads the bad-provenance reject counter.
   *
   * @return the counter value, or {@code 0.0} when the series does not exist
   */
  private double rejected() {
    var counter =
        registry
            .find(MetricNames.INGEST_CLIENT_REJECTED)
            .tag(MetricNames.TAG_REASON, MetricNames.REASON_BAD_PROVENANCE)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void shouldStayInertWhenNoToolAllowlistIsConfigured() {
    assertThatCode(
            () ->
                guard(List.of(), false)
                    .requireApprovedTool(new Provenance("anything-at-all", "9.9", 1)))
        .doesNotThrowAnyException();
    assertThat(rejected()).isZero();
  }

  @Test
  void shouldAcceptTheApprovedProducer() {
    assertThatCode(
            () ->
                guard(List.of(APPROVED_TOOL), false)
                    .requireApprovedTool(new Provenance(APPROVED_TOOL, "1.2.3", 1)))
        .doesNotThrowAnyException();
    assertThat(rejected()).isZero();
  }

  @Test
  void shouldRejectAnUnknownProducer() {
    assertThatThrownBy(
            () ->
                guard(List.of(APPROVED_TOOL), false)
                    .requireApprovedTool(new Provenance("some-other-tool", "1.0", 1)))
        .isInstanceOf(ClientNotAllowedException.class)
        .hasMessageContaining("official basetool");
    assertThat(rejected()).isEqualTo(1.0d);
  }

  @Test
  void shouldRejectAPayloadThatDeclaresNoProducerAtAll() {
    // The casual hand-rolled payload: it never thought to set `tool`. Absent must be refused, not
    // waved through as "nothing to compare".
    assertThatThrownBy(
            () ->
                guard(List.of(APPROVED_TOOL), false)
                    .requireApprovedTool(new Provenance(null, null, 1)))
        .isInstanceOf(ClientNotAllowedException.class);
    assertThat(rejected()).isEqualTo(1.0d);
  }

  @Test
  void shouldCountButNotThrowWhileAuditOnly() {
    assertThatCode(
            () ->
                guard(List.of(APPROVED_TOOL), true)
                    .requireApprovedTool(new Provenance("some-other-tool", "1.0", 1)))
        .doesNotThrowAnyException();
    // Counted regardless — that is what makes the audit-only rollout measurable.
    assertThat(rejected()).isEqualTo(1.0d);
  }

  @Test
  void shouldSanitiseTheRejectedToolBeforeLoggingIt() {
    // `tool` is unvalidated internet-facing free text. A newline in it would forge a second log
    // line — and this reject path is exactly where an attacker-chosen value reaches the logger.
    Provenance forged = new Provenance("evil\nERROR fabricated entry", "1.0", 1);

    List<ILoggingEvent> events =
        LogCapture.capture(
            ProvenanceGuard.class,
            Level.WARN,
            () -> {
              try {
                guard(List.of(APPROVED_TOOL), false).requireApprovedTool(forged);
              } catch (ClientNotAllowedException expected) {
                // The reject is the point of the test; the log line is what is asserted.
              }
            });

    assertThat(events).isNotEmpty();
    assertThat(events.getFirst().getFormattedMessage()).doesNotContain("\n");
  }

  @Test
  void shouldNotEchoTheRejectedToolBackToTheCaller() {
    // The response must not quote unvalidated input back at the client; the log carries the value.
    assertThatThrownBy(
            () ->
                guard(List.of(APPROVED_TOOL), false)
                    .requireApprovedTool(new Provenance("<script>alert(1)</script>", "1.0", 1)))
        .isInstanceOf(ClientNotAllowedException.class)
        .hasMessageNotContaining("script");
  }
}
