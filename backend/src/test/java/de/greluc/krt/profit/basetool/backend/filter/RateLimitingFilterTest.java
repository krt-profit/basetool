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

package de.greluc.krt.profit.basetool.backend.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RateLimitingFilter}. Previously had no test file at all — 36% branch
 * coverage. The most security-critical path is {@code resolveClientKey}, which decides which
 * "bucket" a request lands in:
 *
 * <ul>
 *   <li>If the immediate peer is on the trusted-proxies allow-list, the filter takes the
 *       original-client IP from {@code X-Forwarded-For}; otherwise the spoofable header is ignored.
 *   <li>The literal {@code "*"} is NOT a valid trust value (would let any client spoof the header
 *       and get a fresh bucket per request).
 * </ul>
 *
 * <p>The tests use Spring's {@link MockHttpServletRequest}/Response so no Spring context is needed.
 */
class RateLimitingFilterTest {

  private RateLimitProperties properties;
  private AppProblemProperties problemProperties;
  private RateLimitingFilter filter;

  /**
   * Empty message source: unresolved keys fall back to the English default passed at the call site,
   * so the 429 body carries the hardcoded English title/detail the assertions expect.
   */
  private final MessageSource messageSource = new StaticMessageSource();

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    properties = new RateLimitProperties();
    properties.setEnabled(true);
    properties.setPaths(List.of("/api/**"));
    properties.setCapacity(2); // tight bucket so we can hit the limit quickly
    properties.setRefillTokens(2);
    properties.setRefillPeriod(Duration.ofMinutes(1));

    problemProperties = new AppProblemProperties();
    problemProperties.setBaseUri("https://profit-base.online/problems/");

    filter = new RateLimitingFilter(properties, problemProperties, messageSource, meterRegistry);
  }

  /**
   * Runs the production filter pair in order: client-IP resolution, then rate limiting.
   *
   * <p>They are only correct together. {@code ClientIpContextFilter} is the sole reader of the raw
   * proxy chain — it runs ahead of {@code ForwardedHeaderFilter}, which would otherwise overwrite
   * the peer and hide the header — and {@code RateLimitingFilter} is the sole consumer of its
   * verdict. Driving the limiter alone would test a fallback path that production never takes.
   *
   * <p>The resolver is built per call because tests mutate {@code properties.trustedProxies} in
   * their bodies and the allowlist is compiled once per instance.
   *
   * @param limiter the rate-limiting filter under test
   * @param request the request to send through the pair
   * @param response the response the pair writes to
   * @throws ServletException if the chain fails
   * @throws IOException if the chain fails
   */
  private void runChain(
      RateLimitingFilter limiter, MockHttpServletRequest request, MockHttpServletResponse response)
      throws ServletException, IOException {
    // Chained by hand rather than through MockFilterChain(servlet, filters...): that overload
    // ends in a bare HttpServlet, whose default doGet answers 405 and masks every assertion.
    new ClientIpContextFilter(properties)
        .doFilter(
            request, response, (req, res) -> limiter.doFilter(req, res, new MockFilterChain()));
  }

  // ---------------------------------------------------------------
  // shouldNotFilter — path matching + global disable
  // ---------------------------------------------------------------

  @Nested
  class ShouldNotFilterTests {

    @Test
    void disabled_globally_bypassesEntirely() {
      properties.setEnabled(false);
      MockHttpServletRequest req = newRequest("/api/v1/missions");

      assertTrue(
          filter.shouldNotFilter(req), "enabled=false must short-circuit before path matching");
    }

    @Test
    void nullPathsList_bypasses() {
      properties.setPaths(null);
      MockHttpServletRequest req = newRequest("/api/v1/missions");

      assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    void emptyPathsList_bypasses() {
      properties.setPaths(List.of());
      MockHttpServletRequest req = newRequest("/api/v1/missions");

      assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    void pathMatchesPattern_filterApplies() {
      properties.setPaths(List.of("/api/**"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");

      assertEquals(
          false, filter.shouldNotFilter(req), "matching path must NOT be skipped (filter applies)");
    }

    @Test
    void pathDoesNotMatchPattern_bypasses() {
      properties.setPaths(List.of("/api/**"));
      MockHttpServletRequest req = newRequest("/health");

      assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    void multiplePatterns_anyMatchTriggersFilter() {
      properties.setPaths(List.of("/health", "/api/**"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");

      assertEquals(false, filter.shouldNotFilter(req));
    }

    @Test
    void unparseablePattern_mutatedAfterStartup_isIgnored_doesNotThrow() {
      // Configured patterns are validated at construction (see StartupValidationTests). This case
      // mutates the paths to an unparseable value AFTER the filter was built — the runtime
      // defense-in-depth fallback (tryParse) must then treat it as a permanent non-match and never
      // 500 the request, even though such a mid-path ** can no longer survive a fresh startup.
      properties.setPaths(List.of("/api/**/legacy/**"));
      MockHttpServletRequest req = newRequest("/api/v1/legacy/x");

      assertTrue(
          filter.shouldNotFilter(req),
          "an unparseable pattern must be ignored (treated as non-matching), never thrown");
    }

    @Test
    void singleSegmentWildcard_matchesOneSegmentOnly() {
      // A `*` matches exactly one path segment — the participant-rule shape `/api/v1/missions/*`
      // must match a one-segment id but NOT a deeper path, where only `**` would.
      properties.setPaths(List.of("/api/v1/missions/*"));

      assertEquals(false, filter.shouldNotFilter(newRequest("/api/v1/missions/m1")));
      assertTrue(filter.shouldNotFilter(newRequest("/api/v1/missions/m1/participants")));
    }

    @Test
    void globalUmbrella_matchesSubPathsAndTrailingSlash() {
      // Parity with the AntPathMatcher the filter replaced: `/api/**` must still cover every shape
      // a real request can take under the umbrella — a deep sub-path and a trailing-slash variant.
      properties.setPaths(List.of("/api/**"));

      assertEquals(false, filter.shouldNotFilter(newRequest("/api/v1/missions")));
      assertEquals(
          false, filter.shouldNotFilter(newRequest("/api/v1/missions/m1/participants/p1")));
      assertEquals(false, filter.shouldNotFilter(newRequest("/api/")));
    }
  }

  // ---------------------------------------------------------------
  // Startup validation — a misconfigured pattern must fail fast at construction (boot), not
  // silently disable rate limiting for the matching endpoints at runtime.
  // ---------------------------------------------------------------

  @Nested
  class StartupValidationTests {

    @Test
    void constructor_failsFast_onUnparseableGlobalPattern() {
      // A mid-path ** is rejected by PathPattern. The filter must refuse to start rather than boot
      // with an umbrella that silently never matches — that would leave /api/** unprotected.
      RateLimitProperties bad = newValidProperties();
      bad.setPaths(List.of("/api/**/legacy/**"));

      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> new RateLimitingFilter(bad, problemProperties, messageSource, meterRegistry),
              "an unparseable global pattern must abort startup");
      assertTrue(
          ex.getMessage().contains("/api/**/legacy/**"),
          "the failure must name the offending pattern: " + ex.getMessage());
      assertTrue(
          ex.getMessage().contains("app.rate-limit.paths"),
          "the failure must name the offending config key: " + ex.getMessage());
    }

    @Test
    void constructor_failsFast_onUnparseableRulePattern() {
      // The same guard covers per-rule patterns, naming the rule so the operator can find it.
      RateLimitProperties bad = newValidProperties();
      RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
      rule.setName("broken-rule");
      rule.setMethods(List.of("POST"));
      rule.setPaths(List.of("/api/**/x/**"));
      rule.setCapacity(1);
      rule.setRefillTokens(1);
      rule.setRefillPeriod(Duration.ofMinutes(1));
      bad.setRules(List.of(rule));

      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> new RateLimitingFilter(bad, problemProperties, messageSource, meterRegistry));
      assertTrue(
          ex.getMessage().contains("broken-rule"),
          "the failure must name the offending rule: " + ex.getMessage());
    }

    @Test
    void constructor_failsFast_onBlankPattern() {
      RateLimitProperties bad = newValidProperties();
      bad.setPaths(List.of("   "));

      assertThrows(
          IllegalStateException.class,
          () -> new RateLimitingFilter(bad, problemProperties, messageSource, meterRegistry));
    }

    @Test
    void constructor_succeeds_onAllValidPatterns() {
      // A representative valid configuration (global umbrella + a single-* + final-** rule) must
      // construct cleanly — proving the validator does not reject the shapes actually shipped.
      RateLimitProperties ok = newValidProperties();
      RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
      rule.setName("participant-mutations");
      rule.setMethods(List.of("POST", "PUT", "DELETE"));
      rule.setPaths(
          List.of("/api/v1/missions/*/participants", "/api/v1/missions/*/participants/**"));
      rule.setCapacity(30);
      rule.setRefillTokens(30);
      rule.setRefillPeriod(Duration.ofMinutes(1));
      ok.setRules(List.of(rule));

      assertNotNull(new RateLimitingFilter(ok, problemProperties, messageSource, meterRegistry));
    }

    private RateLimitProperties newValidProperties() {
      RateLimitProperties p = new RateLimitProperties();
      p.setEnabled(true);
      p.setPaths(List.of("/api/**"));
      p.setCapacity(300);
      p.setRefillTokens(300);
      p.setRefillPeriod(Duration.ofMinutes(1));
      return p;
    }
  }

  // ---------------------------------------------------------------
  // resolveClientKey + bucket bookkeeping — the spoofable-header guard
  // ---------------------------------------------------------------

  @Nested
  class ClientIpResolutionTests {

    @Test
    void noTrustedProxies_ignoresXForwardedFor() throws Exception {
      properties.setTrustedProxies(null);
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("198.51.100.10");
      req.addHeader("X-Forwarded-For", "1.1.1.1"); // spoofed header — must be ignored

      assertConsumesBucketKeyContaining("198.51.100.10", req);
    }

    @Test
    void emptyTrustedProxies_ignoresXForwardedFor() throws Exception {
      properties.setTrustedProxies(List.of());
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("198.51.100.10");
      req.addHeader("X-Forwarded-For", "1.1.1.1");

      assertConsumesBucketKeyContaining("198.51.100.10", req);
    }

    @Test
    void wildcardOnlyTrustedProxies_ignoresXForwardedFor() throws Exception {
      // The "*" literal is NOT a valid trust value — must be silently filtered out
      // by the lambda. This is the core rate-limit-bypass guard called out in
      // the production Javadoc.
      properties.setTrustedProxies(List.of("*"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("198.51.100.10");
      req.addHeader("X-Forwarded-For", "1.1.1.1");

      assertConsumesBucketKeyContaining("198.51.100.10", req);
    }

    @Test
    void trustedPeerWithNullXff_fallsBackToRemoteAddr() throws Exception {
      properties.setTrustedProxies(List.of("10.0.0.1"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("10.0.0.1");
      // no XFF set

      assertConsumesBucketKeyContaining("10.0.0.1", req);
    }

    @Test
    void trustedPeerWithBlankXff_fallsBackToRemoteAddr() throws Exception {
      properties.setTrustedProxies(List.of("10.0.0.1"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("10.0.0.1");
      req.addHeader("X-Forwarded-For", "   ");

      assertConsumesBucketKeyContaining("10.0.0.1", req);
    }

    @Test
    void untrustedPeerWithXff_ignoresXForwardedFor() throws Exception {
      properties.setTrustedProxies(List.of("10.0.0.1"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("198.51.100.10"); // NOT in trustedProxies
      req.addHeader("X-Forwarded-For", "1.1.1.1");

      assertConsumesBucketKeyContaining("198.51.100.10", req);
    }

    @Test
    void trustedPeerWithSingleXff_usesXffIp() throws Exception {
      properties.setTrustedProxies(List.of("10.0.0.1"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("10.0.0.1");
      req.addHeader("X-Forwarded-For", "203.0.113.7");

      assertConsumesBucketKeyContaining("203.0.113.7", req);
    }

    @Test
    void trustedPeerWithCommaSeparatedXff_usesRightmostUntrustedHop() throws Exception {
      // An appending proxy puts the truth on the RIGHT. Walking right-to-left and skipping our own
      // hops lands on the client; the old leftmost read landed on whatever the client typed.
      properties.setTrustedProxies(List.of("10.0.0.1"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("10.0.0.1");
      req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1, 198.51.100.99");

      assertConsumesBucketKeyContaining("198.51.100.99", req);
    }

    @Test
    void spoofedLeadingEntry_cannotMintAFreshBucket() throws Exception {
      // The bypass this change closes: two requests whose only difference is the attacker-supplied
      // leading entry must share one bucket, because both resolve to the proxy-appended client.
      properties.setTrustedProxies(List.of("10.0.0.1"));
      properties.setCapacity(1);
      properties.setRefillTokens(1);

      MockHttpServletRequest first = newRequest("/api/v1/missions");
      first.setRemoteAddr("10.0.0.1");
      first.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.99");
      MockHttpServletResponse firstResponse = new MockHttpServletResponse();
      runChain(filter, first, firstResponse);
      assertEquals(200, firstResponse.getStatus(), "first request must consume cleanly");

      MockHttpServletRequest second = newRequest("/api/v1/missions");
      second.setRemoteAddr("10.0.0.1");
      second.addHeader("X-Forwarded-For", "2.2.2.2, 198.51.100.99");
      MockHttpServletResponse secondResponse = new MockHttpServletResponse();
      runChain(filter, second, secondResponse);

      assertEquals(
          429,
          secondResponse.getStatus(),
          "rotating the spoofable leading entry must not yield a second bucket");
    }

    @Test
    void everyHopTrusted_fallsBackToPeer() throws Exception {
      // A chain consisting only of our own proxies carries no client address at all. Falling back
      // to the peer keeps the budget shared rather than keying on one of our own hops.
      properties.setTrustedProxies(List.of("10.0.0.0/24"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("10.0.0.1");
      req.addHeader("X-Forwarded-For", "10.0.0.5, 10.0.0.9");

      assertConsumesBucketKeyContaining("10.0.0.1", req);
    }

    @Test
    void trustedPeerWithXffWhitespace_trimsBeforeUsing() throws Exception {
      properties.setTrustedProxies(List.of("10.0.0.1"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("10.0.0.1");
      req.addHeader("X-Forwarded-For", "   203.0.113.7   ");

      assertConsumesBucketKeyContaining("203.0.113.7", req);
    }

    @Test
    void trustedProxiesListContainsBothWildcardAndRealIp_realIpStillWorks() throws Exception {
      // Defensive: if someone misconfigures with "*" mixed in, the real IP must
      // still be honoured (the "*" entry is filtered out, not the whole list).
      properties.setTrustedProxies(List.of("*", "10.0.0.1"));
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("10.0.0.1");
      req.addHeader("X-Forwarded-For", "203.0.113.7");

      assertConsumesBucketKeyContaining("203.0.113.7", req);
    }

    @Test
    void twoRequestsFromSameSpoofedXffShareABucket_whenTrustedProxyNotConfigured()
        throws Exception {
      // The "rate-limit bypass" attacker scenario: client puts a random IP in
      // X-Forwarded-For on every request hoping for a fresh bucket. With NO
      // trusted proxy configured, the filter must ignore the header entirely
      // and bucket on remoteAddr — so 2 such requests share one bucket.
      properties.setTrustedProxies(List.of()); // not trusted
      properties.setCapacity(1);
      properties.setRefillTokens(1);

      // First request: capacity=1 -> consumed, no 429 yet.
      MockHttpServletRequest req1 = newRequest("/api/v1/missions");
      req1.setRemoteAddr("198.51.100.10");
      req1.addHeader("X-Forwarded-For", "1.1.1.1");
      MockHttpServletResponse resp1 = new MockHttpServletResponse();
      filter.doFilter(req1, resp1, new MockFilterChain());
      assertEquals(200, resp1.getStatus(), "first request must pass");

      // Second request: same remote, different spoofed XFF -> would have been a
      // fresh bucket if XFF were honoured -> 429.
      MockHttpServletRequest req2 = newRequest("/api/v1/missions");
      req2.setRemoteAddr("198.51.100.10");
      req2.addHeader("X-Forwarded-For", "2.2.2.2");
      MockHttpServletResponse resp2 = new MockHttpServletResponse();
      filter.doFilter(req2, resp2, new MockFilterChain());

      assertEquals(
          429,
          resp2.getStatus(),
          "spoofed XFF must NOT yield a fresh bucket when proxy not in trust list");
    }

    // ----- helper --------------------------------------------------------

    private void assertConsumesBucketKeyContaining(String expectedIp, MockHttpServletRequest req)
        throws ServletException, IOException {
      // Tight bucket (capacity=1). The first call consumes; a second call with
      // the same bucket key MUST be rate-limited (429). If the resolved IP
      // doesn't match, the second call would land in a different bucket and
      // still pass.
      properties.setCapacity(1);
      properties.setRefillTokens(1);

      MockHttpServletResponse resp1 = new MockHttpServletResponse();
      runChain(filter, copy(req), resp1);
      assertEquals(200, resp1.getStatus(), "first request must consume cleanly");

      MockHttpServletResponse resp2 = new MockHttpServletResponse();
      runChain(filter, copy(req), resp2);
      assertEquals(
          429,
          resp2.getStatus(),
          "second request with the same resolved IP (" + expectedIp + ") must be rate-limited");
    }

    private MockHttpServletRequest copy(MockHttpServletRequest src) {
      MockHttpServletRequest copy =
          new MockHttpServletRequest(src.getMethod(), src.getRequestURI());
      copy.setRemoteAddr(src.getRemoteAddr());
      String xff = src.getHeader("X-Forwarded-For");
      if (xff != null) {
        copy.addHeader("X-Forwarded-For", xff);
      }
      return copy;
    }
  }

  // ---------------------------------------------------------------
  // doFilterInternal — happy path + 429 response shape
  // ---------------------------------------------------------------

  @Nested
  class DoFilterInternalTests {

    @Test
    void firstRequest_consumesBucket_andAddsHeaders() throws Exception {
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("192.0.2.10");
      MockHttpServletResponse resp = new MockHttpServletResponse();

      filter.doFilter(req, resp, new MockFilterChain());

      assertEquals(200, resp.getStatus());
      assertEquals("2", resp.getHeader("X-Rate-Limit-Limit"), "capacity exposed via header");
      assertNotNull(
          resp.getHeader("X-Rate-Limit-Remaining"),
          "remaining-tokens header must be set after a successful consume");
    }

    @Test
    void exhaustedBucket_returns429ProblemJson_withRetryAfterHeader() throws Exception {
      properties.setCapacity(1);
      properties.setRefillTokens(1);

      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr("192.0.2.20");

      // Drain the bucket.
      filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

      // Second hit: rejected.
      MockHttpServletResponse resp2 = new MockHttpServletResponse();
      filter.doFilter(copyRequest(req), resp2, new MockFilterChain());

      assertEquals(429, resp2.getStatus());
      assertEquals("application/problem+json", resp2.getContentType());
      assertEquals("1", resp2.getHeader("X-Rate-Limit-Limit"));
      assertEquals("0", resp2.getHeader("X-Rate-Limit-Remaining"));
      assertNotNull(resp2.getHeader("X-Rate-Limit-Retry-After-Seconds"));
      // Body must look like a problem document.
      String body = resp2.getContentAsString();
      assertTrue(body.contains("\"status\":429"), body);
      assertTrue(body.contains("\"title\":\"Too Many Requests\""), body);
      assertTrue(body.contains("\"instance\":\"/api/v1/missions\""), body);
      assertTrue(
          body.contains(problemProperties.getBaseUri() + "rate-limit-exceeded"),
          "type URI must be built off AppProblemProperties.baseUri");
      assertTrue(
          body.contains("\"code\":\"RATE_LIMIT_EXCEEDED\""),
          "body must carry the stable machine-readable code");
      assertTrue(
          body.matches("(?s).*\"correlationId\":\"[0-9a-fA-F-]{36}\".*"),
          "body must carry a per-response UUID correlationId: " + body);
      // The minted correlationId is also echoed as the app-wide X-Correlation-Id response header:
      // the rate limiter rejects before CorrelationIdFilter runs, so it must echo it here
      // (RFC-7807 hardening, REQ-OBS).
      String correlationHeader = resp2.getHeader("X-Correlation-Id");
      assertNotNull(correlationHeader, "429 response must echo X-Correlation-Id");
      assertTrue(
          body.contains("\"correlationId\":\"" + correlationHeader + "\""),
          "X-Correlation-Id header must match the body correlationId");
      // The rejection is counted once under the bounded `global` bucket label (the umbrella
      // /api/** budget), never under the client IP or URI (REQ-OBS-011).
      assertEquals(
          1.0d,
          meterRegistry
              .get(MetricNames.RATELIMIT_REJECTIONS)
              .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_GLOBAL)
              .counter()
              .count());
      // (#1041 item 19) Every bucket evaluation — the successful consumptions AND the rejection —
      // is counted under the same bounded bucket label, so requests > rejections here. This is the
      // denominator for the rejection ratio.
      assertTrue(
          meterRegistry
                  .get(MetricNames.RATELIMIT_REQUESTS)
                  .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_GLOBAL)
                  .counter()
                  .count()
              > 1.0d,
          "requests counter must include the successful evaluations, not just the rejection");
    }

    private MockHttpServletRequest copyRequest(MockHttpServletRequest src) {
      MockHttpServletRequest copy =
          new MockHttpServletRequest(src.getMethod(), src.getRequestURI());
      copy.setRemoteAddr(src.getRemoteAddr());
      return copy;
    }
  }

  // ---------------------------------------------------------------
  // chain continuation — make sure the filter actually delegates to the next link
  // ---------------------------------------------------------------

  @Test
  void successful_consume_invokesNextFilterInChain() throws Exception {
    MockHttpServletRequest req = newRequest("/api/v1/missions");
    req.setRemoteAddr("192.0.2.30");
    MockHttpServletResponse resp = new MockHttpServletResponse();

    InvocationTrackingChain chain = new InvocationTrackingChain();
    filter.doFilter(req, resp, chain);

    assertTrue(chain.wasCalled, "the next filter in the chain must be invoked");
  }

  @Test
  void rateLimited_request_doesNOTInvokeNextFilter() throws Exception {
    properties.setCapacity(1);
    properties.setRefillTokens(1);

    MockHttpServletRequest req = newRequest("/api/v1/missions");
    req.setRemoteAddr("192.0.2.40");
    // Drain
    filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

    MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/v1/missions");
    req2.setRemoteAddr("192.0.2.40");
    MockHttpServletResponse resp2 = new MockHttpServletResponse();
    InvocationTrackingChain chain = new InvocationTrackingChain();

    filter.doFilter(req2, resp2, chain);

    assertEquals(429, resp2.getStatus());
    assertEquals(
        false,
        chain.wasCalled,
        "downstream filters must NOT be invoked when the request is rate-limited");
  }

  // ---------------------------------------------------------------
  // Endpoint-specific rules layered on top of the global default — audit finding L-5.
  // The per-rule budgets are designed to trip before the loose global budget on the
  // anonymous-spam POST endpoints (mission create, joborder create, finance-entry,
  // participant CRUD); the tests below pin the layered semantics.
  // ---------------------------------------------------------------

  @Nested
  class EndpointSpecificRuleTests {

    @Test
    void specificRule_runsOutFirst_andDoesNotDrainGlobalBucket() throws Exception {
      // Global default leaves plenty of headroom (10/min), the rule is tight (2/min). After 2
      // POSTs the per-rule bucket is empty -> 429, but the global bucket has only consumed 2 of
      // 10 tokens, so a different endpoint covered only by the global default still goes through.
      properties.setCapacity(10);
      properties.setRefillTokens(10);

      RateLimitProperties.Rule missionCreate =
          newRule("mission-create", List.of("POST"), List.of("/api/v1/missions"), 2);
      properties.setRules(List.of(missionCreate));

      // Drain the per-rule bucket with two POSTs to /api/v1/missions.
      assertEquals(200, post("/api/v1/missions", "192.0.2.50"));
      assertEquals(200, post("/api/v1/missions", "192.0.2.50"));
      // Third POST: rule depleted -> 429.
      MockHttpServletResponse blocked = postResponse("/api/v1/missions", "192.0.2.50");
      assertEquals(429, blocked.getStatus());
      // Headers attribute the rejection to the per-rule limit (2), not the global (10).
      assertEquals("2", blocked.getHeader("X-Rate-Limit-Limit"));

      // Different endpoint, same IP -> global bucket still has tokens -> 200. Proves the per-rule
      // failure short-circuited without draining the global bucket for unrelated paths.
      assertEquals(200, get("/api/v1/orders", "192.0.2.50"));
    }

    @Test
    void specificRule_consumed_alsoDebitsGlobalBucket() throws Exception {
      // The global bucket DOES still tick on every request that matches a tight rule — that's the
      // layered "defense-in-depth" semantics: an attacker who finds an unlimited rule can't use
      // it to escape the global cap.
      properties.setCapacity(3);
      properties.setRefillTokens(3);

      RateLimitProperties.Rule missionCreate =
          newRule(
              "mission-create",
              List.of("POST"),
              List.of("/api/v1/missions"),
              100); // huge per-rule budget
      properties.setRules(List.of(missionCreate));

      // Three POSTs drain the global bucket.
      assertEquals(200, post("/api/v1/missions", "192.0.2.60"));
      assertEquals(200, post("/api/v1/missions", "192.0.2.60"));
      assertEquals(200, post("/api/v1/missions", "192.0.2.60"));
      // Fourth POST: global bucket empty -> 429 with the GLOBAL capacity reflected in the header
      // (the per-rule bucket is the loose one this time).
      MockHttpServletResponse blocked = postResponse("/api/v1/missions", "192.0.2.60");
      assertEquals(429, blocked.getStatus());
      assertEquals("3", blocked.getHeader("X-Rate-Limit-Limit"));
    }

    @Test
    void specificRule_doesNotApply_whenMethodMismatches() throws Exception {
      // The rule targets POST; a GET to the same path must NOT be limited by the rule. The global
      // bucket (capacity 10) is the only check.
      properties.setCapacity(10);
      properties.setRefillTokens(10);

      RateLimitProperties.Rule missionCreate =
          newRule("mission-create", List.of("POST"), List.of("/api/v1/missions"), 1);
      properties.setRules(List.of(missionCreate));

      // Three GETs to /api/v1/missions are fine even though the per-rule capacity is 1.
      assertEquals(200, get("/api/v1/missions", "192.0.2.70"));
      assertEquals(200, get("/api/v1/missions", "192.0.2.70"));
      assertEquals(200, get("/api/v1/missions", "192.0.2.70"));
    }

    @Test
    void specificRule_emptyMethodsList_matchesAnyMethod() throws Exception {
      // Empty methods list means "any HTTP method" — mirrors how Spring's @RequestMapping treats
      // an empty methods array.
      properties.setCapacity(10);
      properties.setRefillTokens(10);

      RateLimitProperties.Rule mutateParticipants =
          newRule(
              "participant-mutations", List.of(), List.of("/api/v1/missions/*/participants/**"), 1);
      properties.setRules(List.of(mutateParticipants));

      assertEquals(200, put("/api/v1/missions/abc/participants/xyz/slim", "192.0.2.71"));
      // Second mutation of any kind on a participants sub-resource -> 429.
      MockHttpServletResponse blocked =
          postResponse("/api/v1/missions/abc/participants/xyz/check-in/slim", "192.0.2.71");
      assertEquals(429, blocked.getStatus());
    }

    @Test
    void twoRulesMatchSameRequest_tightestRunsOutFirst() throws Exception {
      // Two overlapping rules: a wider participants umbrella (5/min) and a tighter check-in slot
      // (2/min). The filter must trip the tightest first; the wider one only kicks in if the
      // request streams keep hitting after the tight cap recovers.
      properties.setCapacity(50);
      properties.setRefillTokens(50);

      RateLimitProperties.Rule wide =
          newRule("participant-wide", List.of(), List.of("/api/v1/missions/*/participants/**"), 5);
      RateLimitProperties.Rule tight =
          newRule(
              "participant-check-in",
              List.of("POST"),
              List.of("/api/v1/missions/*/participants/*/check-in/slim"),
              2);
      properties.setRules(List.of(wide, tight));

      String path = "/api/v1/missions/m1/participants/p1/check-in/slim";
      assertEquals(200, post(path, "192.0.2.80"));
      assertEquals(200, post(path, "192.0.2.80"));
      // Third hit: tight bucket exhausted; wide still has 3 tokens, global has 47.
      MockHttpServletResponse blocked = postResponse(path, "192.0.2.80");
      assertEquals(429, blocked.getStatus());
      // The 429 must attribute the rejection to the tight rule (capacity 2).
      assertEquals("2", blocked.getHeader("X-Rate-Limit-Limit"));
    }

    @Test
    void specificRule_buckets_perIp_independently() throws Exception {
      // The per-rule bucket key includes the client IP, so a flooder on one IP cannot starve a
      // legitimate user on another IP.
      properties.setCapacity(100);
      properties.setRefillTokens(100);

      RateLimitProperties.Rule missionCreate =
          newRule("mission-create", List.of("POST"), List.of("/api/v1/missions"), 1);
      properties.setRules(List.of(missionCreate));

      // IP A drains its rule bucket.
      assertEquals(200, post("/api/v1/missions", "203.0.113.1"));
      assertEquals(429, postResponse("/api/v1/missions", "203.0.113.1").getStatus());
      // IP B still has its own fresh bucket.
      assertEquals(200, post("/api/v1/missions", "203.0.113.2"));
    }

    @Test
    void orderCreateRule_coversTheItemOrderChildPath() throws Exception {
      // Regression for the rate-limit coverage gap: the anonymous item-order create at
      // POST /api/v1/orders/items must share the tight order-create budget. Listing the child path
      // explicitly is required because PathPattern uses exact-segment matching.
      properties.setCapacity(100);
      properties.setRefillTokens(100);

      RateLimitProperties.Rule orderCreate =
          newRule(
              "order-create",
              List.of("POST"),
              List.of("/api/v1/orders", "/api/v1/orders/items"),
              1);
      properties.setRules(List.of(orderCreate));

      // First item-order POST consumes the tight per-rule bucket; the second is blocked by it.
      assertEquals(200, post("/api/v1/orders/items", "203.0.113.10"));
      MockHttpServletResponse blocked = postResponse("/api/v1/orders/items", "203.0.113.10");
      assertEquals(429, blocked.getStatus());
      assertEquals("1", blocked.getHeader("X-Rate-Limit-Limit"));
    }

    @Test
    void orderCreateRule_parentPathAlone_doesNotCoverItemChild() throws Exception {
      // Documents WHY the child path must be listed: with only `/api/v1/orders`, the item-order
      // create at `/api/v1/orders/items` is NOT matched by the rule (exact-segment PathPattern), so
      // a tight per-rule budget of 1 never bites it — the coverage gap the fix closes.
      properties.setCapacity(100);
      properties.setRefillTokens(100);

      RateLimitProperties.Rule orderCreateParentOnly =
          newRule("order-create", List.of("POST"), List.of("/api/v1/orders"), 1);
      properties.setRules(List.of(orderCreateParentOnly));

      // Two POSTs to the child path both pass — the parent-only pattern does not cover it.
      assertEquals(200, post("/api/v1/orders/items", "203.0.113.11"));
      assertEquals(200, post("/api/v1/orders/items", "203.0.113.11"));
    }

    private RateLimitProperties.Rule newRule(
        String name, List<String> methods, List<String> paths, int capacity) {
      RateLimitProperties.Rule r = new RateLimitProperties.Rule();
      r.setName(name);
      r.setMethods(methods);
      r.setPaths(paths);
      r.setCapacity(capacity);
      r.setRefillTokens(capacity);
      r.setRefillPeriod(Duration.ofMinutes(1));
      return r;
    }

    private int post(String path, String ip) throws ServletException, IOException {
      return postResponse(path, ip).getStatus();
    }

    private MockHttpServletResponse postResponse(String path, String ip)
        throws ServletException, IOException {
      MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
      req.setRemoteAddr(ip);
      MockHttpServletResponse resp = new MockHttpServletResponse();
      filter.doFilter(req, resp, new MockFilterChain());
      return resp;
    }

    private int get(String path, String ip) throws ServletException, IOException {
      MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
      req.setRemoteAddr(ip);
      MockHttpServletResponse resp = new MockHttpServletResponse();
      filter.doFilter(req, resp, new MockFilterChain());
      return resp.getStatus();
    }

    private int put(String path, String ip) throws ServletException, IOException {
      MockHttpServletRequest req = new MockHttpServletRequest("PUT", path);
      req.setRemoteAddr(ip);
      MockHttpServletResponse resp = new MockHttpServletResponse();
      filter.doFilter(req, resp, new MockFilterChain());
      return resp.getStatus();
    }
  }

  // ---------------------------------------------------------------
  // key_source — the 2026-07-06 bucket-collapse diagnosis. A 429 spike must be attributable to
  // either "many clients tripped their own budgets" (forwarded) or "trusted-proxies drifted and
  // everyone collapsed onto one bucket" (peer), without ever writing a client IP to the log.
  // ---------------------------------------------------------------

  @Nested
  class KeySourceTests {

    /**
     * Drains a capacity-1 bucket and returns the rejected second response.
     *
     * <p>Rebuilds the filter rather than reusing the {@code @BeforeEach} instance so each case
     * starts with an empty bucket cache and the two requests below are the only ones in the bucket.
     * The request goes through {@link #runChain}, so the {@code key_source} tag reflects the same
     * resolution production performs.
     */
    private MockHttpServletResponse drainAndReject(String remoteAddr, String xff) throws Exception {
      properties.setCapacity(1);
      properties.setRefillTokens(1);
      RateLimitingFilter freshFilter =
          new RateLimitingFilter(properties, problemProperties, messageSource, meterRegistry);
      runChain(freshFilter, request(remoteAddr, xff), new MockHttpServletResponse());
      MockHttpServletResponse rejected = new MockHttpServletResponse();
      runChain(freshFilter, request(remoteAddr, xff), rejected);
      return rejected;
    }

    private MockHttpServletRequest request(String remoteAddr, String xff) {
      MockHttpServletRequest req = newRequest("/api/v1/missions");
      req.setRemoteAddr(remoteAddr);
      if (xff != null) {
        req.addHeader("X-Forwarded-For", xff);
      }
      return req;
    }

    @Test
    void rejection_fromAnUntrustedPeer_isTaggedPeer() throws Exception {
      properties.setTrustedProxies(List.of());

      assertEquals(429, drainAndReject("198.51.100.10", "1.1.1.1").getStatus());

      assertEquals(
          1.0d,
          meterRegistry
              .get(MetricNames.RATELIMIT_REJECTIONS)
              .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_GLOBAL)
              .tag(MetricNames.TAG_KEY_SOURCE, MetricNames.KEY_SOURCE_PEER)
              .counter()
              .count(),
          "an ignored X-Forwarded-For means everyone behind that hop shares one budget");
    }

    @Test
    void rejection_behindATrustedProxy_isTaggedForwarded() throws Exception {
      properties.setTrustedProxies(List.of("10.0.0.1"));

      assertEquals(429, drainAndReject("10.0.0.1", "203.0.113.7").getStatus());

      assertEquals(
          1.0d,
          meterRegistry
              .get(MetricNames.RATELIMIT_REJECTIONS)
              .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_GLOBAL)
              .tag(MetricNames.TAG_KEY_SOURCE, MetricNames.KEY_SOURCE_FORWARDED)
              .counter()
              .count(),
          "per-client bucketing behind the edge must report `forwarded`");
    }

    @Test
    void rejection_withAnEmptyLeadingXffEntry_stillFindsTheClientAndReportsForwarded()
        throws Exception {
      // The leftmost read had to special-case " , 1.2.3.4", because an empty first element would
      // key every such request on the empty string while still reporting `forwarded` — hiding the
      // very collapse this tag exists to expose. Walking from the right removes the special case:
      // the empty element is simply skipped and the real client is found.
      properties.setTrustedProxies(List.of("10.0.0.1"));

      assertEquals(429, drainAndReject("10.0.0.1", " , 203.0.113.7").getStatus());

      assertEquals(
          1.0d,
          meterRegistry
              .get(MetricNames.RATELIMIT_REJECTIONS)
              .tag(MetricNames.TAG_KEY_SOURCE, MetricNames.KEY_SOURCE_FORWARDED)
              .counter()
              .count(),
          "the client is resolvable despite the empty element, so the tag must say so");
    }

    @Test
    void rejection_withoutTheResolverInTheChain_isTaggedPeer() throws Exception {
      // Defensive: a dispatch the resolver is not mapped to must not silently trust a header.
      properties.setTrustedProxies(List.of("10.0.0.1"));
      properties.setCapacity(1);
      properties.setRefillTokens(1);
      RateLimitingFilter lone =
          new RateLimitingFilter(properties, problemProperties, messageSource, meterRegistry);

      lone.doFilter(
          request("10.0.0.1", "203.0.113.7"), new MockHttpServletResponse(), new MockFilterChain());
      MockHttpServletResponse rejected = new MockHttpServletResponse();
      lone.doFilter(request("10.0.0.1", "203.0.113.7"), rejected, new MockFilterChain());

      assertEquals(429, rejected.getStatus());
      assertEquals(
          1.0d,
          meterRegistry
              .get(MetricNames.RATELIMIT_REJECTIONS)
              .tag(MetricNames.TAG_KEY_SOURCE, MetricNames.KEY_SOURCE_PEER)
              .counter()
              .count(),
          "without a resolved attribute the peer is the only trustworthy key");
    }

    /**
     * REQ-OBS-004: the 429 short-circuits before {@code RequestLoggingFilter}, so this DEBUG line
     * is the only log record of the rejection — and it must carry the branch, never the address.
     * The backend stdout stream's 744h retention is predicated on "no client IPs by design". DEBUG
     * is also the contract: the global bucket is anonymous-reachable, so a higher level would be a
     * log-flood vector.
     */
    @Test
    void rejection_logsTheKeySourceAtDebug_andNeverTheClientIp() throws Exception {
      properties.setTrustedProxies(List.of());

      Logger logger = (Logger) LoggerFactory.getLogger(RateLimitingFilter.class);
      Level original = logger.getLevel();
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      logger.setLevel(Level.DEBUG);
      try {
        drainAndReject("198.51.100.10", "1.1.1.1");

        ILoggingEvent event =
            appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Rate limit exceeded"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected the rejection log line"));
        assertEquals(Level.DEBUG, event.getLevel(), "anonymous-reachable — must stay at DEBUG");
        String formatted = event.getFormattedMessage();
        assertTrue(
            formatted.contains("keySource=" + MetricNames.KEY_SOURCE_PEER),
            "the rejection line must name the branch: " + formatted);
        assertFalse(
            formatted.contains("198.51.100.10") || formatted.contains("1.1.1.1"),
            "no client IP may reach the log stream: " + formatted);
      } finally {
        logger.setLevel(original);
        logger.detachAppender(appender);
      }
    }
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static MockHttpServletRequest newRequest(String path) {
    return new MockHttpServletRequest("GET", path);
  }

  private static class InvocationTrackingChain implements FilterChain {
    boolean wasCalled = false;

    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
      wasCalled = true;
    }
  }
}
