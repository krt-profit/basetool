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

package de.greluc.krt.profit.basetool.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.net.SocketTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link IdentityProviderUnavailableFilter} (REQ-SEC-024). The critical regression
 * guarantees: a transport / upstream-5xx failure talking to Keycloak's JWKS endpoint becomes a
 * retryable {@code 503} problem+json (with {@code Retry-After} and the error counter), while an
 * {@link AuthenticationServiceException} <em>without</em> a transport cause — and any other
 * exception — propagates unchanged, so genuine 401/403/500 semantics are never swallowed.
 */
class IdentityProviderUnavailableFilterTest {

  private static final String URI = "/api/v1/notifications/unread-count";

  private IdentityProviderUnavailableFilter filter;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    // MessageSource returns the caller-supplied default (arg 2) so assertions run against a stable,
    // locale-independent body; the i18n wiring itself is covered by the bundle test.
    MessageSource messageSource = mock(MessageSource.class);
    when(messageSource.getMessage(anyString(), any(), anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    meterRegistry = new SimpleMeterRegistry();
    filter =
        new IdentityProviderUnavailableFilter(
            messageSource,
            new ProblemResponseFactory(new AppProblemProperties()),
            JsonMapper.builder().build(),
            meterRegistry);
  }

  private MockHttpServletResponse run(FilterChain chain) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
    request.setRequestURI(URI);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private double serviceUnavailableCount() {
    return meterRegistry
        .counter(
            MetricNames.HTTP_ERROR,
            MetricNames.TAG_CODE,
            IdentityProviderUnavailableFilter.CODE_SERVICE_UNAVAILABLE)
        .count();
  }

  @Test
  void jwksReadTimeout_isRemappedToRetryable503ProblemJson() throws Exception {
    FilterChain chain =
        (req, res) -> {
          throw new AuthenticationServiceException(
              "An error occurred while attempting to decode the Jwt: I/O error on GET request",
              new SocketTimeoutException("Read timed out"));
        };

    MockHttpServletResponse response = run(chain);

    assertEquals(503, response.getStatus());
    assertTrue(response.getContentType().contains("application/problem+json"));
    assertEquals("5", response.getHeader(HttpHeaders.RETRY_AFTER));
    String body = response.getContentAsString();
    assertTrue(body.contains("\"status\":503"), body);
    assertTrue(
        body.contains(
            "\"code\":\"" + IdentityProviderUnavailableFilter.CODE_SERVICE_UNAVAILABLE + "\""),
        body);
    assertEquals(1.0, serviceUnavailableCount());
  }

  @Test
  void keycloak5xx_isRemappedTo503() throws Exception {
    FilterChain chain =
        (req, res) -> {
          throw new AuthenticationServiceException(
              "decode failed", new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        };

    assertEquals(503, run(chain).getStatus());
    assertEquals(1.0, serviceUnavailableCount());
  }

  @Test
  void dockerDnsStrand_unresolvedAddress_isRemappedTo503() throws Exception {
    FilterChain chain =
        (req, res) -> {
          throw new AuthenticationServiceException(
              "decode failed", new UnresolvedAddressException());
        };

    assertEquals(503, run(chain).getStatus());
  }

  @Test
  void authenticationServiceException_withoutTransportCause_isRethrownUnchanged() {
    // A non-transport AuthenticationServiceException must keep its existing behaviour (escapes to
    // the
    // container error dispatch → 500). The filter must NOT swallow it into a 503.
    FilterChain chain =
        (req, res) -> {
          throw new AuthenticationServiceException("programming bug", new IllegalStateException());
        };
    MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThrows(
        AuthenticationServiceException.class, () -> filter.doFilter(request, response, chain));
    assertEquals(200, response.getStatus());
    assertEquals(0.0, serviceUnavailableCount());
  }

  @Test
  void unrelatedException_propagatesUnchanged() {
    // Anything that is not an AuthenticationServiceException (e.g. a bad-token 401 is handled
    // inside
    // the entry point and never reaches here) must propagate untouched.
    FilterChain chain =
        (req, res) -> {
          throw new ServletException("downstream");
        };
    MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));
    assertEquals(0.0, serviceUnavailableCount());
  }

  @Test
  void happyPath_passesThroughUntouched() throws Exception {
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run(chain);

    assertEquals(200, response.getStatus());
    verify(chain).doFilter(any(), any());
    assertEquals(0.0, serviceUnavailableCount());
  }
}
