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
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests the per-subject budget (REQ-SEC-033): the key is the JWT {@code sub}, the surface is API
 * writes plus the SSE connect, and an anonymous caller is somebody else's problem.
 */
class SubjectRateLimitingFilterTest {

  private RateLimitProperties properties;
  private MeterRegistry meterRegistry;
  private SubjectRateLimitingFilter filter;

  @BeforeEach
  void setUp() {
    properties = new RateLimitProperties();
    properties.getSubject().setCapacity(1);
    properties.getSubject().setRefillTokens(1);
    properties.getSubject().setRefillPeriod(Duration.ofMinutes(10));
    meterRegistry = new SimpleMeterRegistry();
    filter = newFilter();
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /**
   * Builds a filter over the current properties.
   *
   * @return a filter with an empty bucket cache.
   */
  private SubjectRateLimitingFilter newFilter() {
    AppProblemProperties problemProperties = new AppProblemProperties();
    problemProperties.setBaseUri("https://profit-base.online/problems/");
    return new SubjectRateLimitingFilter(
        properties,
        new StaticMessageSource(),
        new ProblemResponseFactory(problemProperties),
        new ObjectMapper(),
        meterRegistry);
  }

  /**
   * Puts an authenticated caller with the given subject into the security context.
   *
   * @param sub the JWT subject to authenticate as.
   */
  private static void authenticateAs(String sub) {
    Jwt jwt =
        new Jwt(
            "token-" + sub,
            Instant.now(),
            Instant.now().plusSeconds(300),
            Map.of("alg", "none"),
            Map.of("sub", sub));
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
  }

  /**
   * Sends one request through the filter.
   *
   * @param method the HTTP method.
   * @param uri the request URI.
   * @return the response the filter produced.
   * @throws Exception if the chain fails.
   */
  private MockHttpServletResponse send(String method, String uri) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  @DisplayName("a second write from the same subject is refused once the budget is spent")
  void secondWriteFromTheSameSubjectIsRefused() throws Exception {
    authenticateAs("member-a");

    assertEquals(200, send("POST", "/api/v1/missions").getStatus());
    MockHttpServletResponse rejected = send("POST", "/api/v1/missions");

    assertEquals(429, rejected.getStatus());
    assertTrue(
        rejected.getContentAsString().contains("RATE_LIMIT_EXCEEDED"),
        "the body must carry the stable code the frontend switches on");
    assertEquals("0", rejected.getHeader("X-Rate-Limit-Remaining"));
    assertTrue(
        Integer.parseInt(rejected.getHeader("X-Rate-Limit-Retry-After-Seconds")) >= 1,
        "a caller must be told when to come back");
  }

  @Test
  void differentSubjectsDoNotShareABucket() throws Exception {
    // The whole point of keying on the identity: one member exhausting their budget must not
    // throttle another, which is exactly what a shared per-IP bucket does behind CGNAT.
    authenticateAs("member-a");
    assertEquals(200, send("POST", "/api/v1/missions").getStatus());
    assertEquals(429, send("POST", "/api/v1/missions").getStatus());

    authenticateAs("member-b");
    assertEquals(200, send("POST", "/api/v1/missions").getStatus());
  }

  @Test
  void ordinaryReadsAreLeftToThePerIpBudget() throws Exception {
    authenticateAs("member-a");

    for (int i = 0; i < 5; i++) {
      assertEquals(200, send("GET", "/api/v1/missions").getStatus(), "reads must not spend tokens");
    }
  }

  @Test
  @DisplayName("the SSE connect is covered, because it holds a server-side resource open")
  void theSseConnectSpendsFromTheSameBudget() throws Exception {
    authenticateAs("member-a");

    assertEquals(200, send("GET", "/api/v1/notifications/stream").getStatus());
    assertEquals(429, send("GET", "/api/v1/notifications/stream").getStatus());
  }

  @Test
  void anEncodedSpellingCannotShedTheBudget() throws Exception {
    // REQ-SEC-029: the scope is decided on the decoded path, so /%61pi/... is still an API write.
    authenticateAs("member-a");

    assertEquals(200, send("POST", "/%61pi/v1/missions").getStatus());
    assertEquals(429, send("POST", "/%61pi/v1/missions").getStatus());
  }

  @Test
  void anonymousCallersPassThrough() throws Exception {
    // They carry no subject to key on; the per-IP limiter and the anonymous page-size ceiling
    // (REQ-SEC-032) are their bounds.
    for (int i = 0; i < 5; i++) {
      assertEquals(200, send("POST", "/api/v1/orders/items").getStatus());
    }
  }

  @Test
  void disablingTheSubjectBudgetLeavesTheRequestUntouched() throws Exception {
    properties.getSubject().setEnabled(false);
    filter = newFilter();
    authenticateAs("member-a");

    assertEquals(200, send("POST", "/api/v1/missions").getStatus());
    assertEquals(200, send("POST", "/api/v1/missions").getStatus());
  }

  @Test
  void rejectionsAreCountedUnderTheBoundedSubjectLabel() throws Exception {
    authenticateAs("member-a");
    send("POST", "/api/v1/missions");
    send("POST", "/api/v1/missions");

    assertEquals(
        1.0d,
        meterRegistry
            .get(MetricNames.RATELIMIT_REJECTIONS)
            .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
            .counter()
            .count(),
        "the label is the bounded literal, never the subject itself");
    assertEquals(
        2.0d,
        meterRegistry
            .get(MetricNames.RATELIMIT_REQUESTS)
            .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
            .counter()
            .count(),
        "every attempt is counted, so rejections/requests is the per-subject rejection ratio");
  }
}
