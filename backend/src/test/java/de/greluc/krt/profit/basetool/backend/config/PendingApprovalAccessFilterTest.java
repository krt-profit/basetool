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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link PendingApprovalAccessFilter} (PR review #1: REQ-SEC-017 backend gate). */
class PendingApprovalAccessFilterTest {

  private PendingApprovalAccessFilter filter;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    // Message source returns the caller-supplied default (arg 2) so the assertions run against a
    // stable, locale-independent body; the i18n wiring itself is covered by the bundle test.
    MessageSource messageSource = mock(MessageSource.class);
    when(messageSource.getMessage(anyString(), any(), anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    meterRegistry = new SimpleMeterRegistry();
    filter =
        new PendingApprovalAccessFilter(
            messageSource,
            new ProblemResponseFactory(new AppProblemProperties()),
            JsonMapper.builder().build(),
            meterRegistry);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void authenticateWith(String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user",
                "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
  }

  private MockHttpServletResponse run(String method, String uri, FilterChain chain)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void pendingUser_isForbiddenOnApiWrite() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run("POST", "/api/v1/inventory", chain);

    assertEquals(403, response.getStatus());
    assertTrue(response.getContentType().contains("application/problem+json"));
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void pendingUser_forbiddenBody_isProblemJsonWithCodeAndCorrelationId() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run("POST", "/api/v1/inventory", chain);

    String body = response.getContentAsString();
    assertTrue(body.contains("\"status\":403"), body);
    assertTrue(
        body.contains("\"code\":\"" + PendingApprovalAccessFilter.CODE_PENDING_APPROVAL + "\""),
        "body must carry the stable machine-readable code: " + body);
    assertTrue(body.contains("\"instance\":\"/api/v1/inventory\""), body);
    assertTrue(
        body.matches("(?s).*\"correlationId\":\"[0-9a-fA-F-]{36}\".*"),
        "body must carry a minted UUID correlationId (the filter runs before CorrelationIdFilter): "
            + body);
  }

  @Test
  void pendingUser_forbiddenResponse_echoesCorrelationIdHeaderMatchingBody() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run("POST", "/api/v1/inventory", chain);

    String header = response.getHeader(PendingApprovalAccessFilter.CORRELATION_ID_HEADER);
    assertNotNull(
        header, "403 must echo X-Correlation-Id since CorrelationIdFilter never runs here");
    assertTrue(
        response.getContentAsString().contains("\"correlationId\":\"" + header + "\""),
        "the X-Correlation-Id header must match the body correlationId");
  }

  @Test
  void pendingUser_mayReadOwnRegistrationStatus() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response =
        run("GET", PendingApprovalAccessFilter.SELF_STATUS_PATH, chain);

    assertEquals(200, response.getStatus());
    verify(chain).doFilter(any(), any());
  }

  @Test
  void approvedUser_passesThrough() throws Exception {
    authenticateWith("ROLE_KRT_MEMBER", "HANGAR_WRITE");
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run("POST", "/api/v1/inventory", chain);

    assertEquals(200, response.getStatus());
    verify(chain).doFilter(any(), any());
  }

  @Test
  void pendingUser_nonApiPath_passesThrough() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run("GET", "/actuator/health", chain);

    assertEquals(200, response.getStatus());
    verify(chain).doFilter(any(), any());
  }

  @Test
  void pendingUser_isForbidden_incrementsHttpErrorCounter() throws Exception {
    // REQ-OBS-011: the 403 is written at the filter level, bypassing GlobalExceptionHandler, so it
    // must increment basetool_http_error_total{code=PENDING_APPROVAL} here
    // (PendingApprovalBlockSpike).
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);

    run("POST", "/api/v1/inventory", mock(FilterChain.class));

    assertEquals(
        1.0,
        meterRegistry
            .counter(
                MetricNames.HTTP_ERROR,
                MetricNames.TAG_CODE,
                PendingApprovalAccessFilter.CODE_PENDING_APPROVAL)
            .count());
  }
}
