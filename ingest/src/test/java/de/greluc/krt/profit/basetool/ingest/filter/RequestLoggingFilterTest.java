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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for the ingest {@link RequestLoggingFilter} one-line-per-request access log. */
class RequestLoggingFilterTest {

  @Test
  void logsExactlyOneInfoAccessLineForAV1Request() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    Level original = logger.getLevel();
    logger.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
      request.setRequestURI("/v1/refinery-extract");
      MockHttpServletResponse response = new MockHttpServletResponse();
      response.setStatus(200);

      new RequestLoggingFilter().doFilter(request, response, new MockFilterChain());

      long lines =
          appender.list.stream()
              .filter(e -> e.getLevel() == Level.INFO)
              .filter(e -> e.getFormattedMessage().contains("POST /v1/refinery-extract -> 200"))
              .count();
      assertThat(lines).isEqualTo(1);
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(original);
    }
  }

  @Test
  void doesNotFilterNonV1Paths() {
    MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");
    actuator.setRequestURI("/actuator/health");

    assertThat(new RequestLoggingFilter().shouldNotFilter(actuator)).isTrue();
  }
}
