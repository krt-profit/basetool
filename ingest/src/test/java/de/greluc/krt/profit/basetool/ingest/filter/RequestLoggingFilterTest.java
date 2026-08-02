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

package de.greluc.krt.profit.basetool.ingest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for the ingest {@link RequestLoggingFilter} one-line-per-request access log. */
class RequestLoggingFilterTest {

  private static MockHttpServletRequest ingestRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    request.setRequestURI("/v1/refinery-extract");
    return request;
  }

  @Test
  void logsExactlyOneInfoAccessLineForAV1Request() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    List<ILoggingEvent> events =
        LogCapture.capture(
            RequestLoggingFilter.class,
            Level.INFO,
            () ->
                new RequestLoggingFilter(TestLoggingProperties.defaults())
                    .doFilter(ingestRequest(), response, new MockFilterChain()));

    assertThat(events)
        .filteredOn(e -> e.getLevel() == Level.INFO)
        .filteredOn(e -> e.getFormattedMessage().contains("POST /v1/refinery-extract -> 200"))
        .hasSize(1);
  }

  @Test
  void escalatesToWarnOnceTheSlowRequestThresholdIsCrossed() throws Exception {
    // A zero-millisecond threshold makes every request "slow", so the WARN branch is exercised
    // without sleeping (REQ-OBS-001).
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);

    List<ILoggingEvent> events =
        LogCapture.capture(
            RequestLoggingFilter.class,
            Level.INFO,
            () ->
                new RequestLoggingFilter(TestLoggingProperties.withThresholds(0L, 1500L))
                    .doFilter(ingestRequest(), response, new MockFilterChain()));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(events.getFirst().getFormattedMessage())
        .startsWith("Slow request POST /v1/refinery-extract -> 200 in ");
  }

  @Test
  void stillLogsTheAccessLineWhenTheChainThrows() {
    // The line is emitted from a finally block, so an exploding downstream filter must not swallow
    // it — that is what keeps a 500 attributable.
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(500);
    MockFilterChain exploding =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest request,
              jakarta.servlet.ServletResponse servletResponse) {
            throw new IllegalStateException("boom");
          }
        };

    List<ILoggingEvent> events =
        LogCapture.capture(
            RequestLoggingFilter.class,
            Level.INFO,
            () -> {
              try {
                new RequestLoggingFilter(TestLoggingProperties.defaults())
                    .doFilter(ingestRequest(), response, exploding);
              } catch (IllegalStateException expected) {
                // Propagated to the container as usual; the access line must exist regardless.
              }
            });

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getFormattedMessage())
        .startsWith("POST /v1/refinery-extract -> 500 in ");
  }

  @Test
  void doesNotFilterNonV1Paths() {
    MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");
    actuator.setRequestURI("/actuator/health");

    assertThat(new RequestLoggingFilter(TestLoggingProperties.defaults()).shouldNotFilter(actuator))
        .isTrue();
  }

  @Test
  void filtersV1Paths() {
    assertThat(
            new RequestLoggingFilter(TestLoggingProperties.defaults())
                .shouldNotFilter(ingestRequest()))
        .isFalse();
  }
}
