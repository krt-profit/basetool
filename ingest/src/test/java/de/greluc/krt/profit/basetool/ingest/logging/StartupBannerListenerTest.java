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

package de.greluc.krt.profit.basetool.ingest.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.IngestGatePostureMetric;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the ingest startup banner. Two things are worth pinning: the banner really does
 * surface the runtime facts an on-call engineer needs (otherwise it is decoration), and it never
 * prints a secret — the Redis endpoint is the one field that can carry inline credentials, so it is
 * sanitised (REQ-OBS-004).
 */
class StartupBannerListenerTest {

  private static IngestProperties ingestProperties() {
    return TestProperties.ingest(
        "backend-base-url",
        "https://backend:11261",
        "frontend-base-url",
        "https://app.profit-base.online",
        "handoff-ttl",
        "PT30M",
        "max-payload-bytes",
        "2097152");
  }

  private static List<ILoggingEvent> emitBanner(String redisHost) {
    return emitBanner(
        redisHost,
        new ClientIdentityProperties(
            List.of("secret-client-id"), "secret-scope", List.of("secret-tool"), false),
        List.of("secret-audience"));
  }

  private static List<ILoggingEvent> emitBanner(
      String redisHost, ClientIdentityProperties clientIdentity, List<String> audiences) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    StartupBannerListener listener =
        new StartupBannerListener(
            environment,
            ingestProperties(),
            TestLoggingProperties.defaults(),
            TestProperties.rateLimit(),
            new IngestGatePostureMetric(new SimpleMeterRegistry(), clientIdentity, audiences));
    ReflectionTestUtils.setField(listener, "applicationName", "ingest");
    ReflectionTestUtils.setField(
        listener, "keycloakIssuerUri", "https://keycloak.profit-base.online/realms/iri");
    ReflectionTestUtils.setField(listener, "redisHost", redisHost);
    ReflectionTestUtils.setField(listener, "redisPort", "6379");
    return LogCapture.capture(StartupBannerListener.class, Level.INFO, listener::onReady);
  }

  @Test
  void surfacesTheRuntimeFactsAnOnCallEngineerNeeds() {
    String banner =
        String.join(
            "\n", emitBanner("redis").stream().map(ILoggingEvent::getFormattedMessage).toList());

    assertThat(banner)
        .contains("ingest ready")
        .contains("[prod]")
        .contains("https://backend:11261")
        .contains("https://app.profit-base.online")
        .contains("PT30M")
        .contains("2097152")
        .contains("redis:6379")
        .contains("https://keycloak.profit-base.online/realms/iri")
        .contains("X-Correlation-Id")
        .contains("2000")
        .contains("subject 30/PT1M, ip 120/PT1M");
  }

  /**
   * The client-gate posture line reports which gates refuse and how many values each holds — and
   * never a configured client id, scope, tool or audience (REQ-OBS-004, ING-SEC-03).
   */
  @Test
  void reportsTheGatePostureWithoutAnyConfiguredValue() {
    String banner =
        String.join(
            "\n", emitBanner("redis").stream().map(ILoggingEvent::getFormattedMessage).toList());

    assertThat(banner)
        .contains("azp=on(1) scope=on tool=on(1) audience=on(1) auditOnly=false")
        .doesNotContain("secret-client-id")
        .doesNotContain("secret-scope")
        .doesNotContain("secret-tool")
        .doesNotContain("secret-audience");
  }

  /** Audit-only switches the three client-identity gates off in the posture, not the audience. */
  @Test
  void reportsAuditOnlyGatesAsNotEnforcing() {
    String banner =
        String.join(
            "\n",
            emitBanner(
                    "redis",
                    new ClientIdentityProperties(List.of("c"), "s", List.of("t"), true),
                    List.of())
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList());

    assertThat(banner).contains("azp=off(1) scope=off tool=off(1) audience=off(0) auditOnly=true");
  }

  @Test
  void neverPrintsCredentialsEmbeddedInTheRedisHost() {
    String banner =
        String.join(
            "\n",
            emitBanner("app:sup3r-secret@redis").stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList());

    assertThat(banner).doesNotContain("sup3r-secret").contains("***@redis:6379");
  }

  @Test
  void rendersAnUnknownRedisEndpointWhenTheHostIsMissing() {
    assertThat(StartupBannerListener.sanitiseRedisEndpoint(null, "6379")).isEqualTo("unknown");
    assertThat(StartupBannerListener.sanitiseRedisEndpoint("  ", "6379")).isEqualTo("unknown");
  }

  @Test
  void rendersTheHostAloneWhenNoPortIsConfigured() {
    assertThat(StartupBannerListener.sanitiseRedisEndpoint("redis", null)).isEqualTo("redis");
    assertThat(StartupBannerListener.sanitiseRedisEndpoint("redis", "")).isEqualTo("redis");
  }
}
