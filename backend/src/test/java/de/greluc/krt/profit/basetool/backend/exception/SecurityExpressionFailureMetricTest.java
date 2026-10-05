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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.util.SimpleMethodInvocation;

/**
 * Covers {@code basetool_security_expression_failures_total} (REQ-OBS-020): a method-security
 * expression that cannot be evaluated is counted by kind, while the response stays the fail-closed
 * {@code 400 ILLEGAL_ARGUMENT}; any other {@link IllegalArgumentException} is not counted.
 */
class SecurityExpressionFailureMetricTest {

  /** Fixture methods whose expressions fail the way a renamed bean or a bad expression does. */
  static class GatedFixture {

    /** References a bean that does not exist. */
    @PreAuthorize("@renamedSecurityService.canSee(#id)")
    public void missingBean() {}

    /** Evaluates to a string instead of a decision. */
    @PreAuthorize("'granted'")
    public void notADecision() {}
  }

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
  private GlobalExceptionHandler handler;
  private HttpServletRequest request;

  @BeforeEach
  void setUp() {
    AppProblemProperties props = new AppProblemProperties("https://profit-base.online/problems/");
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    handler =
        new GlobalExceptionHandler(
            props, new ProblemResponseFactory(props), messageSource, meterRegistry);
    handler.registerSecurityExpressionFailureCounters();
    request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/test");
  }

  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  @DisplayName("both kinds are registered at zero, so the first failure is an increase")
  void countersStartAtZero() {
    assertThat(count(MetricNames.SECURITY_EXPRESSION_EVALUATION)).isZero();
    assertThat(count(MetricNames.SECURITY_EXPRESSION_OTHER)).isZero();
  }

  @Test
  @DisplayName("an unresolvable bean is counted as evaluation and still answers 400")
  void unresolvableBeanIsCounted() throws NoSuchMethodException {
    IllegalArgumentException failure = evaluate("missingBean");

    ResponseEntity<ProblemDetail> response = handler.handleIllegalArgument(failure, request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getProperties())
        .containsEntry("code", GlobalExceptionHandler.CODE_ILLEGAL_ARGUMENT);
    assertThat(count(MetricNames.SECURITY_EXPRESSION_EVALUATION)).isEqualTo(1.0d);
    assertThat(count(MetricNames.SECURITY_EXPRESSION_OTHER)).isZero();
  }

  @Test
  @DisplayName("an expression that yields no decision is counted as other")
  void nonDecisionIsCounted() throws NoSuchMethodException {
    IllegalArgumentException failure = evaluate("notADecision");

    ResponseEntity<ProblemDetail> response = handler.handleIllegalArgument(failure, request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(count(MetricNames.SECURITY_EXPRESSION_OTHER)).isEqualTo(1.0d);
    assertThat(count(MetricNames.SECURITY_EXPRESSION_EVALUATION)).isZero();
  }

  @Test
  @DisplayName("an ordinary illegal argument is not a security-expression failure")
  void ordinaryIllegalArgumentIsNotCounted() {
    ResponseEntity<ProblemDetail> response =
        handler.handleIllegalArgument(new IllegalArgumentException("bad page size"), request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(
            GlobalExceptionHandler.securityExpressionFailureKind(
                new IllegalArgumentException("bad page size")))
        .isNull();
    assertThat(count(MetricNames.SECURITY_EXPRESSION_EVALUATION)).isZero();
    assertThat(count(MetricNames.SECURITY_EXPRESSION_OTHER)).isZero();
  }

  /**
   * Runs Spring Security's {@code @PreAuthorize} evaluation on a fixture method and captures the
   * exception it raises.
   *
   * @param method the fixture method name
   * @return the exception Spring Security threw
   * @throws NoSuchMethodException when the fixture lacks the method
   */
  private static IllegalArgumentException evaluate(String method) throws NoSuchMethodException {
    GatedFixture target = new GatedFixture();
    SimpleMethodInvocation invocation =
        new SimpleMethodInvocation(target, GatedFixture.class.getMethod(method));
    PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();
    IllegalArgumentException failure =
        catchThrowableOfType(
            IllegalArgumentException.class,
            () ->
                manager.authorize(
                    () -> new TestingAuthenticationToken("member", "n/a", "ROLE_KRT_MEMBER"),
                    invocation));
    assertThat(failure).as("Spring Security must refuse to evaluate " + method).isNotNull();
    return failure;
  }

  /**
   * Reads the failure counter for one kind.
   *
   * @param kind the {@code kind} tag value
   * @return the count, or {@code -1} when the series is not registered
   */
  private double count(String kind) {
    Counter counter =
        meterRegistry
            .find(MetricNames.SECURITY_EXPRESSION_FAILURES)
            .tag(MetricNames.TAG_KIND, kind)
            .counter();
    return counter == null ? -1 : counter.count();
  }
}
