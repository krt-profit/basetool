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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies that the {@link BackendApiClient} correctly parses RFC7807 Problem+JSON responses
 * produced by the backend's {@code GlobalExceptionHandler} and exposes the stable {@code code},
 * {@code correlationId} and {@code fieldErrors[]} via {@link BackendServiceException}. Covers the
 * main error classes defined in the prompt (tasks 7-12): optimistic locking, access denied,
 * validation errors and service-unavailable fall-through.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "app.http.connect-timeout=500ms",
      "app.http.response-timeout=2s",
      "app.http.read-timeout=2s",
      "app.http.write-timeout=2s",
      "resilience4j.retry.instances.backendApi.max-attempts=1",
      "resilience4j.retry.instances.backend.max-attempts=1"
    })
class BackendApiClientProblemJsonTest {

  private static MockWebServer server;

  @Autowired private BackendApiClient backendApiClient;

  @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @MockitoBean private OAuth2AuthorizedClientRepository authorizedClientRepository;

  /**
   * Gives the authenticated WebClient a resolvable {@code keycloak} registration and a live token.
   *
   * <p>These cases used to pass {@code isPublic = true} and go out on the anonymous WebClient,
   * which carries no OAuth2 exchange filter — a way of reaching the Problem+JSON mapping without a
   * Keycloak registration. That client is gone (ADR-0159), and routing around the authenticated
   * chain was never the point of this class anyway: what it asserts is the mapping, and it now
   * asserts it on the client the application actually uses.
   */
  @BeforeEach
  void bearAToken() {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("basetool-frontend")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://keycloak.invalid/auth")
            .tokenUri("https://keycloak.invalid/token")
            .userInfoUri("https://keycloak.invalid/userinfo")
            .userNameAttributeName("sub")
            .build();
    Mockito.when(clientRegistrationRepository.findByRegistrationId("keycloak"))
        .thenReturn(registration);
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "test-token",
            Instant.now(),
            Instant.now().plusSeconds(300),
            Set.of());
    Mockito.when(
            authorizedClientRepository.loadAuthorizedClient(
                ArgumentMatchers.eq("keycloak"),
                ArgumentMatchers.any(),
                ArgumentMatchers.<jakarta.servlet.http.HttpServletRequest>any()))
        .thenReturn(new OAuth2AuthorizedClient(registration, "test-principal", token));
  }

  @BeforeAll
  static void startServer() throws IOException {
    server = new MockWebServer();
    server.start(0);
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public @NotNull MockResponse dispatch(@NotNull RecordedRequest request) {
            String path = request.getPath();
            if (path == null) {
              return new MockResponse().setResponseCode(404);
            }
            return switch (path) {
              case "/api/v1/optimistic-lock" ->
                  problemJson(
                      409,
                      "{\"type\":\"urn:problem:optimistic-lock\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"Entity"
                          + " was updated concurrently\","
                          + "\"code\":\"OPTIMISTIC_LOCK\",\"correlationId\":\"corr-123\"}");
              case "/api/v1/forbidden" ->
                  problemJson(
                      403,
                      "{\"type\":\"urn:problem:access-denied\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Missing"
                          + " privilege\","
                          + "\"code\":\"ACCESS_DENIED\",\"correlationId\":\"corr-403\"}");
              case "/api/v1/validation" ->
                  problemJson(
                      400,
                      "{\"type\":\"urn:problem:validation\",\"title\":\"Bad"
                          + " Request\",\"status\":400,\"detail\":\"Validation failed\","
                          + "\"code\":\"VALIDATION_FAILED\",\"correlationId\":\"corr-400\",\"fieldErrors\":[{\"field\":\"name\",\"message\":\"must"
                          + " not be blank\"},{\"field\":\"amount\",\"message\":\"must be"
                          + " positive\"}]}");
              case "/api/v1/terms-gated" ->
                  problemJson(
                      403,
                      "{\"type\":\"urn:problem:terms-not-accepted\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Accept"
                          + " the terms\","
                          + "\"code\":\"TERMS_NOT_ACCEPTED\",\"correlationId\":\"corr-terms\"}");
              case "/api/v1/role-gated" ->
                  problemJson(
                      403,
                      "{\"type\":\"urn:problem:no-role\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Your"
                          + " account holds no role.\","
                          + "\"code\":\"NO_ROLE\",\"correlationId\":\"corr-no-role\"}");
              case "/api/v1/no-body" -> new MockResponse().setResponseCode(500);
              default -> new MockResponse().setResponseCode(404);
            };
          }
        });
  }

  private static @NotNull MockResponse problemJson(int status, @NotNull String body) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json")
        .setBody(body);
  }

  @DynamicPropertySource
  static void registerProps(@NotNull DynamicPropertyRegistry registry) {
    registry.add("app.backend-url", () -> "http://localhost:" + server.getPort());
  }

  @AfterAll
  static void stopServer() throws IOException {
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  void get_ShouldMapOptimisticLockProblemJsonTo409() {
    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> backendApiClient.get("/api/v1/optimistic-lock", String.class));
    assertEquals(409, ex.getStatusCode());
    assertEquals("OPTIMISTIC_LOCK", ex.getProblemCode());
    assertEquals("corr-123", ex.getCorrelationId());
    assertEquals("Entity was updated concurrently", ex.getReadableErrorMessage());
  }

  @Test
  void get_ShouldMapAccessDeniedProblemJsonTo403() {
    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> backendApiClient.get("/api/v1/forbidden", String.class));
    assertEquals(403, ex.getStatusCode());
    assertEquals("ACCESS_DENIED", ex.getProblemCode());
    assertEquals("corr-403", ex.getCorrelationId());
  }

  @Test
  void get_ShouldExposeFieldErrorsFromValidationProblem() {
    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> backendApiClient.get("/api/v1/validation", String.class));
    assertEquals(400, ex.getStatusCode());
    assertEquals("VALIDATION_FAILED", ex.getProblemCode());
    assertEquals(2, ex.getFieldErrors().size());
    assertTrue(
        ex.getFieldErrors().stream()
            .anyMatch(fe -> "name".equals(fe.field()) && "must not be blank".equals(fe.message())));
    assertTrue(ex.getFieldErrors().stream().anyMatch(fe -> "amount".equals(fe.field())));
  }

  /**
   * A consent-gate 403 is logged at DEBUG, not WARN.
   *
   * <p>Not cosmetic. After a Terms-of-Use wording change every member is unconsented at once, and
   * the role sync plus three unconditional {@code @ControllerAdvice} model attributes each 403 on
   * every non-static request — so a WARN here is several lines per navigation, per user,
   * indefinitely, for a feature that is working exactly as designed (REQ-SEC-028, REQ-OBS-001).
   * Pinned so a future refactor of this branch order cannot quietly reintroduce the flood; the
   * {@code backend_4xx} metric increment sits outside the branch, so the monitoring signal is
   * unaffected either way.
   */
  @Test
  void get_ShouldLogTermsGate403AtDebug_NotWarn() {
    Logger logger = (Logger) LoggerFactory.getLogger(BackendApiClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    Level previous = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    try {
      BackendServiceException ex =
          assertThrows(
              BackendServiceException.class,
              () -> backendApiClient.get("/api/v1/terms-gated", String.class));
      assertEquals("TERMS_NOT_ACCEPTED", ex.getProblemCode());

      assertTrue(
          appender.list.stream().noneMatch(event -> event.getLevel() == Level.WARN),
          "a consent-gate 403 must not reach WARN");
      assertTrue(
          appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG),
          "the refusal is still recorded, at DEBUG");
    } finally {
      logger.setLevel(previous);
      logger.detachAppender(appender);
    }
  }

  /**
   * A consent-gate 403 is not counted as a backend-call failure.
   *
   * <p>{@code BackendCallFailureSustained} alerts on {@code
   * sum(rate(basetool_backend_client_errors_total[5m])) > 0.5}. Counting the gate's refusals there
   * made it fire 38 minutes after the consent gate shipped, at 3.2/s, because every unconsented
   * session hits the gate on every request — the alert could not tell "the backend is failing" from
   * "the gate is working". The backend counts each refusal itself by code, so no signal is lost.
   */
  @Test
  void get_ShouldNotCountTermsGate403AsABackendCallFailure() {
    double before = backendErrorCount();

    assertThrows(
        BackendServiceException.class,
        () -> backendApiClient.get("/api/v1/terms-gated", String.class));

    assertEquals(before, backendErrorCount(), "a consent-gate refusal is not a call failure");
  }

  /**
   * A {@code NO_ROLE} 403 is the third member of the same family, and is treated like the other
   * two.
   *
   * <p>It was missed when REQ-SEC-053 shipped, and its arrival rate is the reason that matters: one
   * role-less member loading one page produces a refusal per fragment on it — {@code /users/me},
   * terms status, capabilities, notification count, active org unit, org units, mission search — so
   * a single account waiting for an administrator raises the same alert the consent gate raised at
   * 3.2/s. The counter measures backend health; a working gate is not ill health.
   */
  @Test
  void get_ShouldNotCountNoRole403AsABackendCallFailure() {
    double before = backendErrorCount();

    assertThrows(
        BackendServiceException.class,
        () -> backendApiClient.get("/api/v1/role-gated", String.class));

    assertEquals(before, backendErrorCount(), "a role-gate refusal is not a call failure");
  }

  /** And it is logged at DEBUG, for the same reason and at the same rate. */
  @Test
  void get_ShouldLogNoRole403AtDebug_NotWarn() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BackendApiClient.class);
    Level original = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThrows(
          BackendServiceException.class,
          () -> backendApiClient.get("/api/v1/role-gated", String.class));

      assertTrue(
          appender.list.stream().noneMatch(event -> event.getLevel() == Level.WARN),
          "a role-gate 403 must not reach WARN");
      assertTrue(
          appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG),
          "the refusal is still recorded, at DEBUG");
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(original);
    }
  }

  /** A genuine 4xx still counts, so the alert keeps its sensitivity to real problems. */
  @Test
  void get_ShouldStillCountAGenuine4xxAsABackendCallFailure() {
    double before = backendErrorCount();

    assertThrows(
        BackendServiceException.class,
        () -> backendApiClient.get("/api/v1/forbidden", String.class));

    assertEquals(before + 1.0, backendErrorCount(), "a real 4xx must still be counted");
  }

  /**
   * Sums the backend-client-error counter across all label combinations.
   *
   * @return the current total
   */
  private double backendErrorCount() {
    return meterRegistry.find("basetool.backend.client.errors").counters().stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  @Test
  void get_ShouldFallBackToUnknownCode_WhenNoProblemBody() {
    BackendServiceException ex =
        assertThrows(
            BackendServiceException.class,
            () -> backendApiClient.get("/api/v1/no-body", String.class));
    assertEquals(500, ex.getStatusCode());
    assertNotNull(ex.getProblemCode());
    assertFalse(ex.getProblemCode().isBlank());
  }
}
