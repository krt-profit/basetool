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
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
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

  /**
   * The {@code app.rate-limit.*} keys the test configures, relative to that prefix; {@link
   * #newFilter()} binds them, so every other key keeps its production default.
   */
  private final Map<String, Object> config = new HashMap<>();

  private MeterRegistry meterRegistry;
  private SubjectRateLimitingFilter filter;

  @BeforeEach
  void setUp() {
    config.put("subject.capacity", 1);
    config.put("subject.refill-tokens", 1);
    config.put("subject.refill-period", "10m");
    meterRegistry = new SimpleMeterRegistry();
    filter = newFilter();
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /**
   * Builds a filter over the currently configured keys.
   *
   * @return a filter with an empty bucket cache.
   */
  private SubjectRateLimitingFilter newFilter() {
    AppProblemProperties problemProperties =
        new AppProblemProperties("https://profit-base.online/problems/");
    return new SubjectRateLimitingFilter(
        BoundProperties.bind(RateLimitProperties.class, config),
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
    config.put("subject.enabled", false);
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

  /**
   * Gives the export budget two tokens per ten minutes, so the tests can tell its bucket from the
   * one-token write bucket of {@link #setUp()}.
   */
  private void withExportBudgetOfTwo() {
    config.put("subject.export.capacity", 2);
    config.put("subject.export.refill-tokens", 2);
    config.put("subject.export.refill-period", "10m");
    filter = newFilter();
  }

  // covers REQ-SEC-033 export carve-out (APPSEC-10)
  @Test
  @DisplayName("an export beyond the export budget is refused although it is a GET")
  void exportsBeyondTheExportBudgetAreRefused() throws Exception {
    withExportBudgetOfTwo();
    authenticateAs("member-a");

    assertEquals(200, send("GET", "/api/v1/users/me/export").getStatus());
    assertEquals(200, send("GET", "/api/v1/users/me/export/pdf").getStatus());
    MockHttpServletResponse rejected =
        send("GET", "/api/v1/bank/accounts/" + java.util.UUID.randomUUID() + "/statement");

    assertEquals(429, rejected.getStatus());
    assertTrue(rejected.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
    assertEquals(
        "2",
        rejected.getHeader("X-Rate-Limit-Limit"),
        "the header reports the budget that refused, not the write budget");
  }

  @Test
  void everyExportFamilyIsCovered() throws Exception {
    // One token each: every family must spend from the budget, so the second call of each refuses.
    config.put("subject.export.capacity", 1);
    config.put("subject.export.refill-tokens", 1);
    config.put("subject.export.refill-period", "10m");
    String[] exports = {
      "/api/v1/users/me/export",
      "/api/v1/admin/users/u/export/pdf",
      "/api/v1/audit/BANK/export.json",
      "/api/v1/bank/admin/audit/export",
      "/api/v1/org-units/bank/accounts/a/statement",
      "/api/v1/bank/export/three-month-report",
      "/api/v1/orders/o/item-handovers/h/report",
      "/api/v1/orders/o/handovers/h/report",
    };
    for (String uri : exports) {
      filter = newFilter();
      authenticateAs("member-a");
      assertEquals(200, send("GET", uri).getStatus(), uri);
      assertEquals(429, send("GET", uri).getStatus(), uri + " must spend from the export budget");
    }
  }

  @Test
  void exportsAndWritesDoNotShareABucket() throws Exception {
    // A burst of downloads must not cost the account its writes, and the reverse.
    withExportBudgetOfTwo();
    authenticateAs("member-a");

    assertEquals(200, send("GET", "/api/v1/bank/export/three-month-report").getStatus());
    assertEquals(200, send("GET", "/api/v1/bank/export/three-month-report").getStatus());
    assertEquals(429, send("GET", "/api/v1/bank/export/three-month-report").getStatus());

    assertEquals(200, send("POST", "/api/v1/missions").getStatus());
  }

  @Test
  @DisplayName("an export that is also a write spends from both budgets")
  void aPostExportSpendsFromBothBudgets() throws Exception {
    withExportBudgetOfTwo();
    authenticateAs("member-a");

    // The preview spends the one write token and one of the two export tokens.
    assertEquals(200, send("POST", "/api/v1/orders/o/handovers/report/preview").getStatus());
    // The write bucket is now empty, so a plain write is refused ...
    assertEquals(429, send("POST", "/api/v1/missions").getStatus());
    // ... while a GET export still has its second token.
    assertEquals(200, send("GET", "/api/v1/users/me/export").getStatus());
  }

  @Test
  void ordinaryReadsStillSpendNothingNextToTheExportBudget() throws Exception {
    withExportBudgetOfTwo();
    authenticateAs("member-a");

    for (int i = 0; i < 5; i++) {
      assertEquals(200, send("GET", "/api/v1/bank/accounts").getStatus());
      assertEquals(200, send("GET", "/api/v1/sync-reports").getStatus());
    }
  }

  @Test
  void anEncodedExportSpellingCannotShedTheExportBudget() throws Exception {
    // REQ-SEC-029: the segment is compared decoded, so %65xport is still `export`.
    withExportBudgetOfTwo();
    authenticateAs("member-a");

    assertEquals(200, send("GET", "/api/v1/users/me/%65xport").getStatus());
    assertEquals(200, send("GET", "/api/v1/users/me/%65xport").getStatus());
    assertEquals(429, send("GET", "/api/v1/users/me/%65xport").getStatus());
  }

  @Test
  void disablingTheExportBudgetLeavesExportsToThePerIpBudget() throws Exception {
    withExportBudgetOfTwo();
    config.put("subject.export.enabled", false);
    filter = newFilter();
    authenticateAs("member-a");

    for (int i = 0; i < 5; i++) {
      assertEquals(200, send("GET", "/api/v1/users/me/export").getStatus());
    }
  }

  @Test
  void exportRejectionsAreCountedUnderTheirOwnBoundedLabel() throws Exception {
    withExportBudgetOfTwo();
    authenticateAs("member-a");
    for (int i = 0; i < 3; i++) {
      send("GET", "/api/v1/users/me/export");
    }

    assertEquals(
        1.0d,
        meterRegistry
            .get(MetricNames.RATELIMIT_REJECTIONS)
            .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT_EXPORT)
            .counter()
            .count());
    assertEquals(
        3.0d,
        meterRegistry
            .get(MetricNames.RATELIMIT_REQUESTS)
            .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT_EXPORT)
            .counter()
            .count());
  }
}
