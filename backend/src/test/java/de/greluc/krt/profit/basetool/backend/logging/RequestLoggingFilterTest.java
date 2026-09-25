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

package de.greluc.krt.profit.basetool.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.config.LoggingProperties;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies that {@link RequestLoggingFilter}
 *
 * <ul>
 *   <li>emits exactly one INFO entry per business request,
 *   <li>escalates to WARN for slow requests,
 *   <li>never escalates the long-lived notification SSE relay to that WARN,
 *   <li>skips noisy URIs (actuator, swagger-ui, webjars).
 * </ul>
 */
class RequestLoggingFilterTest {

  private RequestLoggingFilter filter =
      new RequestLoggingFilter(BoundProperties.defaults(LoggingProperties.class));
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    logger.setLevel(Level.INFO);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    logger.detachAppender(appender);
  }

  @Test
  void fastRequest_ShouldBeLoggedAtInfo() throws ServletException, IOException {
    filter = filterWithThreshold(10_000L);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage()).contains("GET", "/api/v1/missions", "-> 200");
  }

  @Test
  void slowRequest_ShouldBeLoggedAtWarn() throws ServletException, IOException {
    filter = filterWithThreshold(0L);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/missions");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(201);

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains("Slow request", "POST", "/api/v1/missions", "-> 201");
  }

  @Test
  void slowNotificationStreamRelay_ShouldStayAtInfo() throws ServletException, IOException {
    filter = filterWithThreshold(0L);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage())
        .doesNotContain("Slow request")
        .contains("GET", "/api/v1/notifications/stream", "-> 200");
  }

  @Test
  void actuatorRequest_ShouldBeSkipped() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(appender.list).isEmpty();
  }

  @Test
  void swaggerRequest_ShouldBeSkipped() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(appender.list).isEmpty();
  }

  /**
   * Builds the filter under test with the given slow-request threshold; the logging properties are
   * an immutable record (BE-MOD-04), so a different threshold means a different filter.
   *
   * @param thresholdMs the slow-request threshold in milliseconds
   * @return a filter reading that threshold
   */
  private static RequestLoggingFilter filterWithThreshold(long thresholdMs) {
    return new RequestLoggingFilter(
        BoundProperties.bind(LoggingProperties.class, "slow-request-threshold-ms", thresholdMs));
  }
}
