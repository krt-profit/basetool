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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Unit tests for {@link SecurityProblemResponseHandler}: filter-level 401/403 rejections are handed
 * to the MVC {@code handlerExceptionResolver} (so {@code GlobalExceptionHandler} renders the
 * RFC&nbsp;7807 body), with a {@code sendError} fallback only when the resolver does not handle the
 * exception or the response is already committed — plus the {@code userId} MDC stamping that keeps
 * a filter-level 403 from claiming {@code anonymous} for an authenticated caller.
 */
class SecurityProblemResponseHandlerTest {

  private static final String SUB = "6a1f2c9e-0000-4000-8000-00000000abcd";

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  /**
   * Builds the handler over the shared registry, so every rejection a test drives also lands on
   * {@code basetool_auth_failures_total} and the counter assertions below see the real call path.
   *
   * @param resolver the stubbed MVC exception resolver
   * @return the handler under test
   */
  private SecurityProblemResponseHandler newHandler(HandlerExceptionResolver resolver) {
    return new SecurityProblemResponseHandler(resolver, meterRegistry);
  }

  /**
   * Reads the auth-failure counter for one bearer error code.
   *
   * @param reason the bounded {@code reason} label
   * @return the count, or {@code 0} when the series does not exist yet
   */
  private double authFailures(String reason) {
    Counter counter =
        meterRegistry.find(MetricNames.AUTH_FAILURES).tag(MetricNames.TAG_REASON, reason).counter();
    return counter == null ? 0d : counter.count();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  /** Authenticates the context with a bearer token carrying {@link #SUB} as its {@code sub}. */
  private static void authenticateWithJwt() {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(SUB).build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))));
  }

  /**
   * Builds a resolver whose {@code resolveException} records the {@code userId} MDC value visible
   * at the moment the problem body would be rendered — i.e. exactly what the logback pattern would
   * print on the rejection line.
   *
   * @param seen receives the observed MDC value ({@code null} when the key is unset)
   * @return the stubbed resolver
   */
  private static HandlerExceptionResolver resolverCapturingUserId(AtomicReference<String> seen) {
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any()))
        .thenAnswer(
            invocation -> {
              seen.set(MDC.get("userId"));
              return new ModelAndView();
            });
    return resolver;
  }

  @Test
  void handle_stampsJwtSubIntoUserIdMdcForTheRejectionWrite() throws Exception {
    authenticateWithJwt();
    AtomicReference<String> seen = new AtomicReference<>();
    SecurityProblemResponseHandler handler = newHandler(resolverCapturingUserId(seen));

    handler.handle(
        new MockHttpServletRequest("POST", "/api/v1/x"),
        new MockHttpServletResponse(),
        new AccessDeniedException("denied"));

    assertEquals(
        SUB,
        seen.get(),
        "a filter-level 403 must log the real sub, exactly as a controller-thrown one does");
  }

  @Test
  void handle_removesTheUserIdItStampedSoNothingBleedsIntoTheNextRequest() throws Exception {
    authenticateWithJwt();
    SecurityProblemResponseHandler handler =
        newHandler(resolverCapturingUserId(new AtomicReference<>()));

    handler.handle(
        new MockHttpServletRequest("POST", "/api/v1/x"),
        new MockHttpServletResponse(),
        new AccessDeniedException("denied"));

    assertNull(MDC.get("userId"), "own-then-remove: the key must not survive the rejection write");
  }

  @Test
  void commence_leavesUserIdUnsetForAnAnonymousCaller() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>("sentinel");
    SecurityProblemResponseHandler handler = newHandler(resolverCapturingUserId(seen));

    handler.commence(
        new MockHttpServletRequest("GET", "/api/v1/x"),
        new MockHttpServletResponse(),
        new BadCredentialsException("no token"));

    assertNull(
        seen.get(),
        "a genuine anonymous 401 must keep reading 'anonymous' rather than borrow an identity");
  }

  @Test
  void handle_leavesUserIdUnsetForANonJwtAuthentication() throws Exception {
    // Nothing to stamp: the sub only exists on a bearer token, and REQ-OBS-004 forbids falling
    // back to the principal name (which is the callsign).
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("callsign", "n/a", List.of()));
    AtomicReference<String> seen = new AtomicReference<>("sentinel");
    SecurityProblemResponseHandler handler = newHandler(resolverCapturingUserId(seen));

    handler.handle(
        new MockHttpServletRequest("POST", "/api/v1/x"),
        new MockHttpServletResponse(),
        new AccessDeniedException("denied"));

    assertNull(seen.get());
  }

  @Test
  void handle_keepsAnAlreadyPresentUserIdInsteadOfOverwritingIt() throws Exception {
    authenticateWithJwt();
    MDC.put("userId", "already-set");
    AtomicReference<String> seen = new AtomicReference<>();
    SecurityProblemResponseHandler handler = newHandler(resolverCapturingUserId(seen));

    handler.handle(
        new MockHttpServletRequest("POST", "/api/v1/x"),
        new MockHttpServletResponse(),
        new AccessDeniedException("denied"));

    assertEquals("already-set", seen.get(), "an existing owner of the key wins");
    assertEquals(
        "already-set", MDC.get("userId"), "and a value we did not stamp must not be removed");
  }

  @Test
  void commence_delegatesAuthenticationExceptionToResolver() throws Exception {
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());
    SecurityProblemResponseHandler handler = newHandler(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AuthenticationException ex = new BadCredentialsException("invalid token");

    handler.commence(request, response, ex);

    verify(resolver).resolveException(eq(request), eq(response), isNull(), same(ex));
  }

  @Test
  void handle_delegatesAccessDeniedExceptionToResolver() throws Exception {
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());
    SecurityProblemResponseHandler handler = newHandler(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AccessDeniedException ex = new AccessDeniedException("denied");

    handler.handle(request, response, ex);

    verify(resolver).resolveException(eq(request), eq(response), isNull(), same(ex));
  }

  @Test
  void commence_fallsBackToSendErrorWhenResolverDoesNotHandle() throws Exception {
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(null);
    SecurityProblemResponseHandler handler = newHandler(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.commence(request, response, new BadCredentialsException("nope"));

    assertEquals(401, response.getStatus(), "fallback sendError uses the 401 status");
  }

  @Test
  void handle_skipsResolverWhenResponseAlreadyCommitted() throws Exception {
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    SecurityProblemResponseHandler handler = newHandler(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);

    handler.handle(request, response, new AccessDeniedException("denied"));

    verify(resolver, never()).resolveException(any(), any(), any(), any());
  }

  @Test
  @DisplayName("a rejected token is counted under its RFC 6750 code, not merely as a 401")
  void aRejectedTokenIsCountedUnderItsBearerErrorCode() throws Exception {
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());

    newHandler(resolver)
        .commence(
            new MockHttpServletRequest("GET", "/api/v1/missions"),
            new MockHttpServletResponse(),
            new OAuth2AuthenticationException(
                new OAuth2Error(MetricNames.AUTH_INVALID_TOKEN, "expired at …", null)));

    assertEquals(
        1.0d,
        authFailures(MetricNames.AUTH_INVALID_TOKEN),
        "the whole point of the metric is telling a bad token from a malformed header");
    assertEquals(0.0d, authFailures(MetricNames.AUTH_OTHER));
  }

  @Test
  void anErrorCodeOutsideTheRfcSetCollapsesToTheBoundedLiteral() throws Exception {
    // The code is a string on the wire: a custom authorization server (or a future Spring release)
    // can put anything there, and an unbounded label is a cardinality bomb (REQ-OBS-006).
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());

    newHandler(resolver)
        .commence(
            new MockHttpServletRequest("GET", "/api/v1/missions"),
            new MockHttpServletResponse(),
            new OAuth2AuthenticationException(new OAuth2Error("mint_your_own_error_code")));

    assertEquals(1.0d, authFailures(MetricNames.AUTH_OTHER));
    assertNull(
        meterRegistry
            .find(MetricNames.AUTH_FAILURES)
            .tag(MetricNames.TAG_REASON, "mint_your_own_error_code")
            .counter(),
        "an arbitrary code must never reach the label");
  }

  @Test
  void aNonBearerAuthenticationFailureIsStillCounted() throws Exception {
    // A failure that is not an OAuth2AuthenticationException carries no code at all; dropping it
    // would make the counter disagree with basetool_http_error_total{code="UNAUTHENTICATED"} and
    // leave the difference unexplained.
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());

    newHandler(resolver)
        .commence(
            new MockHttpServletRequest("GET", "/api/v1/missions"),
            new MockHttpServletResponse(),
            new BadCredentialsException("nope"));

    assertEquals(1.0d, authFailures(MetricNames.AUTH_OTHER));
  }

  @Test
  void anAccessDeniedVerdictIsNotAnAuthenticationFailure() throws Exception {
    // 403 means the caller authenticated fine and lacks an authority. Counting it here would
    // inflate
    // the spike alert with ordinary authorization outcomes.
    HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());

    newHandler(resolver)
        .handle(
            new MockHttpServletRequest("GET", "/api/v1/audit"),
            new MockHttpServletResponse(),
            new AccessDeniedException("denied"));

    assertNull(meterRegistry.find(MetricNames.AUTH_FAILURES).counter());
  }
}
