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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.support.SessionIdFingerprint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionDebugFilterTest {

  private SessionDebugFilter filter;
  private FilterChain filterChain;

  @BeforeEach
  void setUp() {
    filter = new SessionDebugFilter();
    filterChain = mock(FilterChain.class);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldAlwaysDelegateToFilterChain() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void shouldDelegateToFilterChainWhenSessionExists() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/order/");
    request.getSession(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void shouldDelegateToFilterChainWhenAuthenticated() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions/");
    request.getSession(true);
    MockHttpServletResponse response = new MockHttpServletResponse();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("testuser", null, java.util.List.of()));

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void shouldDelegateToFilterChainForPostRequest() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/missions/create");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void shouldNotModifyResponseStatus() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertEquals(200, response.getStatus());
  }

  @Test
  void shouldNotModifyResponseStatusWhenSessionExists() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/hangar/");
    request.getSession(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertEquals(200, response.getStatus());
  }

  @Test
  void shouldNotModifySecurityContext() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/profile/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("user42", null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    filter.doFilter(request, response, filterChain);

    assertEquals(auth, SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void shouldNotSetAuthenticationWhenNonePresent() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    org.springframework.security.core.Authentication authAfter =
        SecurityContextHolder.getContext().getAuthentication();
    assert authAfter == null || !authAfter.isAuthenticated();
  }

  @Test
  void shouldDelegateForDeleteRequest() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/resource/1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void shouldDelegateForPutRequest() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/resource/1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void logsASessionFingerprintAndNeverTheRawSessionId() throws ServletException, IOException {
    Logger logger = (Logger) LoggerFactory.getLogger(SessionDebugFilter.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      String rawId = "0b8d7f0e-2c1a-4e9b-8f3d-6a5c4b3a2918";
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
      request.setSession(new MockHttpSession(null, rawId));

      filter.doFilter(request, new MockHttpServletResponse(), filterChain);

      assertFalse(appender.list.isEmpty(), "the DEBUG filter must have logged");
      for (ILoggingEvent event : appender.list) {
        assertFalse(
            event.getFormattedMessage().contains(rawId),
            "raw session id leaked into: " + event.getFormattedMessage());
      }
      assertTrue(
          appender.list.stream()
              .anyMatch(e -> e.getFormattedMessage().contains(SessionIdFingerprint.of(rawId))),
          "the fingerprint keeps the lines of one session correlatable");
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }
  }
}
