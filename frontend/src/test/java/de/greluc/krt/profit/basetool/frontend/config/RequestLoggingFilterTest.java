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
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies that the frontend {@link RequestLoggingFilter}
 *
 * <ul>
 *   <li>emits exactly one INFO entry per business request,
 *   <li>escalates to WARN for slow requests,
 *   <li>never escalates the long-lived notification SSE relay to that WARN,
 *   <li>skips noisy URIs (static assets, actuator, webjars).
 * </ul>
 */
class RequestLoggingFilterTest {

  private ListAppender<ILoggingEvent> appender;

  /**
   * Builds a {@link LoggingProperties} record carrying the given slow-request threshold and the
   * canonical defaults for every other field.
   *
   * @param slowRequestThresholdMs the threshold above which a request is logged at WARN
   * @return a fully populated {@link LoggingProperties} for the filter under test
   */
  private static LoggingProperties propsWithThreshold(long slowRequestThresholdMs) {
    return new LoggingProperties(
        "X-Correlation-Id", "correlationId", "userId", slowRequestThresholdMs, 1500L, false);
  }

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
    // Given
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(10_000L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage()).contains("GET", "/missions", "-> 200");
  }

  @Test
  void slowRequest_ShouldBeLoggedAtWarn() throws ServletException, IOException {
    // Given: threshold 0 ms makes every request "slow"
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(0L));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/inventory/book-out");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains("Slow request", "POST", "/inventory/book-out", "-> 200");
  }

  @Test
  void slowNotificationStreamRelay_ShouldStayAtInfo() throws ServletException, IOException {
    // Given: threshold 0 ms makes every request "slow", but the SSE relay is exempt
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(0L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notifications/stream");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then: exactly one line, at INFO, without the "Slow request" WARN prefix
    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage())
        .doesNotContain("Slow request")
        .contains("GET", "/notifications/stream", "-> 200");
  }

  @Test
  void staticAssetRequest_ShouldBeSkipped() throws ServletException, IOException {
    // Given
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(2000L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/css/app.css");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    assertThat(appender.list).isEmpty();
  }

  @Test
  void actuatorRequest_ShouldBeSkipped() throws ServletException, IOException {
    // Given
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(2000L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    assertThat(appender.list).isEmpty();
  }
}
