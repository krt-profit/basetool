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

package de.greluc.krt.profit.basetool.frontend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.config.AppBackendProperties;
import de.greluc.krt.profit.basetool.frontend.config.LoggingProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the frontend startup banner. The frontend is where a misconfiguration hides
 * longest — a wrong backend URL or Keycloak issuer does not fail the boot, it surfaces later as a
 * login loop or a page of 502s — so the banner has to actually carry those values, and it must
 * never carry a secret (REQ-OBS-004).
 */
class StartupBannerListenerTest {

  private static List<ILoggingEvent> emitBanner(String redisHost) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    StartupBannerListener listener =
        new StartupBannerListener(
            environment,
            new LoggingProperties(
                "X-Correlation-Id", "correlationId", "userId", 2000L, 1500L, false),
            new AppBackendProperties("https://backend:11261"));
    ReflectionTestUtils.setField(listener, "applicationName", "frontend");
    ReflectionTestUtils.setField(
        listener, "keycloakIssuerUri", "https://keycloak.profit-base.online/realms/iri");
    ReflectionTestUtils.setField(listener, "redisHost", redisHost);
    ReflectionTestUtils.setField(listener, "redisPort", "6379");

    Logger logger = (Logger) LoggerFactory.getLogger(StartupBannerListener.class);
    Level original = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    try {
      listener.onReady();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(original);
      appender.stop();
    }
    return List.copyOf(appender.list);
  }

  private static String bannerText(String redisHost) {
    return String.join(
        "\n", emitBanner(redisHost).stream().map(ILoggingEvent::getFormattedMessage).toList());
  }

  @Test
  void surfacesTheRuntimeFactsAnOnCallEngineerNeeds() {
    assertThat(bannerText("redis"))
        .contains("frontend ready")
        .contains("[prod]")
        .contains("https://backend:11261")
        .contains("https://keycloak.profit-base.online/realms/iri")
        .contains("redis:6379")
        .contains("X-Correlation-Id")
        .contains("2000")
        .contains("1500");
  }

  @Test
  void neverPrintsCredentialsEmbeddedInTheRedisHost() {
    assertThat(bannerText("app:sup3r-secret@redis"))
        .doesNotContain("sup3r-secret")
        .contains("***@redis:6379");
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
