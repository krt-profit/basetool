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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.web.session.SessionInformationExpiredEvent;

/**
 * Unit tests for {@link SecurityConfig.SessionEvictionLoggingStrategy} (audit finding M9). A
 * concurrent-session eviction used to leave no trace at all — Spring's own account of it is DEBUG
 * on a logger this app pins to INFO, and {@code basetool_active_sessions} cannot see it because
 * {@code expireNow()} only marks the session. The three things under test are therefore: it is
 * counted, it is logged at WARN with the cap value and a non-PII identity, and the evicted user's
 * response is unchanged.
 */
class SessionEvictionLoggingStrategyTest {

  /**
   * The response body Spring Security's own default strategy writes. Duplicated here on purpose:
   * the assertion exists to fail loudly if the copy inside {@code SessionEvictionLoggingStrategy}
   * is ever "improved", because that would silently change what an evicted member sees.
   */
  private static final String SPRING_DEFAULT_EXPIRED_BODY =
      "This session has been expired (possibly due to multiple concurrent logins being attempted"
          + " as the same user).";

  private static final int CAP = 10;

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(SecurityConfig.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
  }

  @AfterEach
  void cleanUp() {
    logger.detachAppender(appender);
    MDC.clear();
  }

  @Test
  void onExpiredSessionDetected_countsLogsAtWarnAndKeepsTheVictimResponseVerbatim()
      throws Exception {
    String sub = UUID.randomUUID().toString();
    MDC.put("userId", sub);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MockHttpServletResponse response = new MockHttpServletResponse();

    new SecurityConfig.SessionEvictionLoggingStrategy(registry, CAP)
        .onExpiredSessionDetected(event(response));

    assertThat(registry.counter(MetricNames.SESSION_EVICTED).count()).isEqualTo(1.0);
    assertThat(response.getContentAsString()).isEqualTo(SPRING_DEFAULT_EXPIRED_BODY);
    assertThat(appender.list)
        .singleElement()
        .satisfies(
            logged -> {
              assertThat(logged.getLevel()).isEqualTo(Level.WARN);
              assertThat(logged.getFormattedMessage()).contains(String.valueOf(CAP)).contains(sub);
            });
  }

  /**
   * Identity comes from the {@code userId} MDC key alone. The security filter chain runs ahead of
   * the frontend's {@code CorrelationIdFilter}, so the key is frequently unset — the line must then
   * degrade to a placeholder rather than fall back to the principal name (the Keycloak callsign) or
   * the session id, both of which REQ-OBS-004 forbids.
   */
  @Test
  void onExpiredSessionDetected_neverLogsThePrincipalNameOrSessionId() throws Exception {
    MDC.clear();
    MockHttpServletResponse response = new MockHttpServletResponse();

    new SecurityConfig.SessionEvictionLoggingStrategy(new SimpleMeterRegistry(), CAP)
        .onExpiredSessionDetected(event(response));

    assertThat(appender.list)
        .singleElement()
        .satisfies(
            logged -> {
              assertThat(logged.getFormattedMessage())
                  .doesNotContain("PilotCallsign")
                  .doesNotContain("session-id-4711");
              assertThat(logged.getFormattedMessage()).contains("unknown");
            });
  }

  private static SessionInformationExpiredEvent event(MockHttpServletResponse response) {
    SessionInformation information =
        new SessionInformation("PilotCallsign", "session-id-4711", new Date());
    return new SessionInformationExpiredEvent(information, new MockHttpServletRequest(), response);
  }
}
