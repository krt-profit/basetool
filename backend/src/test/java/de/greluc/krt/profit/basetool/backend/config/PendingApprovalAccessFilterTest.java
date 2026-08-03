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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    MDC.clear();
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

  /**
   * A percent-encoded path prefix does not slip past the gate.
   *
   * <p>{@code getRequestURI()} is the raw, still-encoded URI while Spring MVC routes on the decoded
   * path, so the {@code startsWith("/api/")} test this replaced let {@code /%61pi/v1/missions}
   * through — and {@code RequestMappingHandlerMapping} then decodes {@code %61pi} to {@code api}
   * and dispatches it, handing a non-approved account the {@code isAuthenticated()}-only writes.
   * The default {@code StrictHttpFirewall} blocks {@code %2e}/{@code %2f}/{@code %25} but not
   * {@code %61}. Must be a direct filter test: MockMvc normalises the path before the filter sees
   * it, so it cannot reproduce this.
   */
  @Test
  void pendingUser_cannotBypassTheGateByPercentEncodingThePathPrefix() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run("GET", "/%61pi/v1/missions", chain);

    assertEquals(403, response.getStatus());
    verify(chain, never()).doFilter(any(), any());
  }

  /**
   * The exemption is equally encoding-proof in the other direction: an encoded spelling of the
   * status endpoint must stay reachable, or a client that happens to percent-encode loses its only
   * route to the waiting page.
   */
  @Test
  void pendingUser_mayReadOwnRegistrationStatus_evenPercentEncoded() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = run("GET", "/api/v1/users/me/%72egistration-status", chain);

    assertEquals(200, response.getStatus());
    verify(chain).doFilter(any(), any());
  }

  /**
   * The exemption stays exact: a path that merely begins with the status literal is still refused.
   * Guards the literal {@code PathPattern} against being widened into a {@code /**}-suffixed one.
   */
  @Test
  void pendingUser_pathMerelyPrefixedWithTheStatusLiteral_isStillForbidden() throws Exception {
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response =
        run("GET", PendingApprovalAccessFilter.SELF_STATUS_PATH + "-export", chain);

    assertEquals(403, response.getStatus());
    verify(chain, never()).doFilter(any(), any());
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

  /** Authenticates with a pending-approval bearer token carrying {@code sub}. */
  private void authenticateWithPendingJwt(String sub) {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub).build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt,
                List.of(
                    new SimpleGrantedAuthority(PendingApprovalAccessFilter.PENDING_AUTHORITY))));
  }

  /**
   * One captured log event: its level, its formatted message and the {@code userId} MDC value as it
   * stood at the moment the appender ran.
   *
   * @param level the event's level
   * @param message the fully formatted message
   * @param userId the MDC {@code userId} at append time; {@code null} when the key was unset
   */
  private record CapturedEvent(Level level, String message, String userId) {}

  /**
   * Records the {@code userId} MDC value as it stands <em>at append time</em>, which is the only
   * point where the logback pattern would read it. {@code ILoggingEvent.getMDCPropertyMap()}
   * resolves lazily, so inspecting it after the filter's {@code finally} has removed the key would
   * observe the post-removal state and silently pass whatever the filter did.
   */
  private static final class UserIdCapturingAppender extends AppenderBase<ILoggingEvent> {

    /** Every accepted event, in arrival order. */
    private final List<CapturedEvent> events = new ArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      events.add(
          new CapturedEvent(
              event.getLevel(),
              event.getFormattedMessage(),
              MDC.get(PendingApprovalAccessFilter.MDC_USER_ID)));
    }

    /**
     * Returns the MDC {@code userId} recorded for the first DEBUG event whose message contains
     * {@code needle}.
     *
     * @param needle substring identifying the wanted log line
     * @return the recorded value, possibly {@code null} when the key was unset
     * @throws AssertionError when no matching DEBUG event was captured
     */
    private String userIdOf(String needle) {
      return events.stream()
          .filter(e -> Level.DEBUG.equals(e.level()) && e.message().contains(needle))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no DEBUG event containing: " + needle))
          .userId();
    }
  }

  /**
   * Runs the filter against a blocked API write with a DEBUG-level capturing appender attached.
   *
   * @return the appender holding the captured events
   */
  private UserIdCapturingAppender runBlockedWithCapture() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(PendingApprovalAccessFilter.class);
    Level original = logger.getLevel();
    UserIdCapturingAppender appender = new UserIdCapturingAppender();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      run("POST", "/api/v1/inventory", mock(FilterChain.class));
    } finally {
      logger.setLevel(original);
      logger.detachAppender(appender);
    }
    return appender;
  }

  /**
   * The block line must name the blocked caller. {@code CorrelationIdFilter} is the backend's only
   * other {@code userId} writer and it never runs on a request this filter rejects, so without the
   * stamping the line would print the pattern's {@code anonymous} default for a caller who is
   * demonstrably authenticated.
   */
  @Test
  void pendingUser_isForbidden_stampsSubIntoUserIdMdcWhileLogging() throws Exception {
    String sub = "6a1f2c9e-0000-4000-8000-00000000abcd";
    authenticateWithPendingJwt(sub);

    UserIdCapturingAppender appender = runBlockedWithCapture();

    assertEquals(
        sub,
        appender.userIdOf("Pending-approval user blocked"),
        "the block line must carry the caller's sub, not 'anonymous'");
  }

  @Test
  void pendingUser_isForbidden_removesTheStampedUserIdAfterwards() throws Exception {
    authenticateWithPendingJwt("6a1f2c9e-0000-4000-8000-00000000abcd");

    run("POST", "/api/v1/inventory", mock(FilterChain.class));

    assertNull(
        MDC.get(PendingApprovalAccessFilter.MDC_USER_ID),
        "own-then-remove: nothing may bleed into the next request on a pooled thread");
  }

  @Test
  void pendingUser_withoutABearerToken_leavesUserIdUnset() throws Exception {
    // REQ-OBS-004: there is no sub to stamp here, and the principal name is the callsign — so the
    // key stays unset and the pattern's 'anonymous' default remains the truthful rendering.
    authenticateWith(PendingApprovalAccessFilter.PENDING_AUTHORITY);

    UserIdCapturingAppender appender = runBlockedWithCapture();

    assertNull(appender.userIdOf("Pending-approval user blocked"));
  }
}
