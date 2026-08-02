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
 *   <li>skips noisy URIs (static assets, actuator, webjars),
 *   <li>distinguishes a {@code ?fragment=} live-update refresh from a full page load of the same
 *       path — without ever putting a non-whitelisted query parameter in the line.
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

  @Test
  void fragmentRefresh_ShouldBeDistinguishableFromAFullPageLoad()
      throws ServletException, IOException {
    // Given: the live-update refresh and the page load hit the SAME path, so getRequestURI() alone
    // cannot tell them apart — which is what made "the list didn't refresh" unanswerable.
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(10_000L));
    MockHttpServletRequest refresh = new MockHttpServletRequest("GET", "/orders");
    refresh.setQueryString("fragment=results&page=2");
    refresh.addHeader("X-Requested-With", "XMLHttpRequest");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(refresh, response, (req, res) -> {});

    // Then: still exactly one line, widened — never a second line.
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("/orders", "fragment=results", "ajax=true");
  }

  @Test
  void plainPageLoad_ShouldCarryNoAjaxMarker() throws ServletException, IOException {
    // Given
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(10_000L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then: a navigation keeps the previous line shape, so the marker's presence IS the signal.
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("fragment=", "ajax=true");
  }

  @Test
  void xhrWithoutFragment_ShouldStillBeMarkedAsAjax() throws ServletException, IOException {
    // Given: an AJAX mutation (POST) carries no fragment parameter but is still not a navigation.
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(10_000L));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/inventory/book-out");
    request.addHeader("X-Requested-With", "XMLHttpRequest");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("ajax=true")
        .doesNotContain("fragment=");
  }

  @Test
  void freeTextQueryParameters_ShouldNeverReachTheLogLine() throws ServletException, IOException {
    // Given: the search and filter params carry callsigns. REQ-OBS-004 bans them outright, so the
    // whitelist must be by parameter NAME — logging the query string wholesale would leak them.
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(10_000L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/search");
    request.setQueryString("query=SomeCallsign&q=another&fragment=results");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then: the whitelisted fragment name is there; nothing else from the query string is.
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("fragment=results")
        .doesNotContain("SomeCallsign", "another", "query=", "q=");
  }

  @Test
  void fragmentValueWithControlCharacters_ShouldBeSanitised() throws ServletException, IOException {
    // Given: a crafted fragment value trying to forge a second log line (CWE-117).
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(10_000L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
    request.setQueryString("fragment=results\nWARN forged line");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then: LogSafe stripped the line break, so the forged suffix cannot read as its own entry.
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("\n");
  }

  @Test
  void slowFragmentRefresh_ShouldKeepTheMarkerOnTheWarnLine() throws ServletException, IOException {
    // Given: threshold 0 ms makes every request "slow" — the WARN branch must widen identically, so
    // a slow refresh is still distinguishable from a slow page load.
    RequestLoggingFilter filter = new RequestLoggingFilter(propsWithThreshold(0L));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
    request.setQueryString("fragment=results");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("Slow request", "fragment=results");
  }
}
