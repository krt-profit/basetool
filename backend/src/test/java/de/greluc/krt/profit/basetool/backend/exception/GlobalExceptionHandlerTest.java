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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Covers the most important RFC7807 error responses produced by {@link GlobalExceptionHandler}:
 * every handler must return {@code application/problem+json} and carry the stable {@code code} /
 * {@code correlationId} extension fields so that the frontend can map the problem to a localized
 * message.
 */
class GlobalExceptionHandlerTest {

  private GlobalExceptionHandler handler;
  private HttpServletRequest request;
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    AppProblemProperties props = new AppProblemProperties();
    props.setBaseUri("https://profit-base.online/problems/");
    // Use the real messages bundle so the test catches missing or wrong i18n keys.
    // Locale is forced to English to keep these assertions stable regardless of the
    // JVM default locale on the developer / CI machine.
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    handler =
        new GlobalExceptionHandler(
            props, new ProblemResponseFactory(props), messageSource, meterRegistry);
    request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/test");
  }

  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
  }

  private static void assertCommon(
      ResponseEntity<ProblemDetail> response, HttpStatus expectedStatus, String expectedCode) {
    assertEquals(expectedStatus.value(), response.getStatusCode().value());
    assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
    ProblemDetail pd = response.getBody();
    assertNotNull(pd);
    assertEquals(expectedStatus.value(), pd.getStatus());
    assertNotNull(pd.getType());
    assertNotNull(pd.getInstance());
    assertNotNull(pd.getProperties());
    assertEquals(expectedCode, pd.getProperties().get("code"));
    assertNotNull(pd.getProperties().get("correlationId"));
    assertTrue(((String) pd.getProperties().get("correlationId")).length() > 0);
  }

  /**
   * Asserts the handler incremented {@code basetool_http_error_total} exactly once for the given
   * stable code (each JUnit test instance owns a fresh registry, so the expected count is 1).
   *
   * @param code the stable {@code CODE_*} error code the increment is tagged with
   */
  private void assertHttpErrorCounted(String code) {
    assertEquals(
        1.0d,
        meterRegistry.get(MetricNames.HTTP_ERROR).tag(MetricNames.TAG_CODE, code).counter().count(),
        "one basetool_http_error_total increment expected for code " + code);
  }

  @Test
  void handleOptimisticLocking_springVariant_returns409WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleOptimisticLockingFailure(
            new ObjectOptimisticLockingFailureException("Mission", "id"), request);

    assertCommon(resp, HttpStatus.CONFLICT, GlobalExceptionHandler.CODE_OPTIMISTIC_LOCK);
    assertEquals("Concurrency conflict", resp.getBody().getTitle());
    assertHttpErrorCounted(GlobalExceptionHandler.CODE_OPTIMISTIC_LOCK);
  }

  /**
   * The 409 WARN line must name the conflicting entity, its identifier and the compared version
   * pair. Without the pair, "repeated 409 with nobody else editing" (a fragment that fails to
   * re-echo the bumped version, REQ-FE-003) is indistinguishable from real contention. Stays at
   * WARN — a 409 only fires on an actual conflict, so it is not a flood vector.
   */
  @Test
  void handleOptimisticLocking_logsEntityIdentifierAndVersionPairAtWarn() {
    when(request.getMethod()).thenReturn("PUT");
    when(request.getRequestURI()).thenReturn("/api/v1/orders/42");

    Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      handler.handleOptimisticLockingFailure(
          new ObjectOptimisticLockingFailureException(
              String.class, "00000000-0000-0000-0000-0000000000ab", "expected=4 persisted=9", null),
          request);

      ILoggingEvent warn =
          appender.list.stream()
              .filter(e -> e.getLevel() == Level.WARN)
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected a WARN log line for the 409"));
      String formatted = warn.getFormattedMessage();
      assertTrue(formatted.contains("entity=java.lang.String"), formatted);
      assertTrue(
          formatted.contains("entityId=00000000-0000-0000-0000-0000000000ab"),
          "the conflicting row must be identifiable: " + formatted);
      assertTrue(
          formatted.contains("versions=expected=4 persisted=9"),
          "the compared version pair is what separates contention from a stale echo: " + formatted);
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * A conflict that did not come from the {@code support.OptimisticLock} helpers (the JPA spec
   * exception, raised by Hibernate on flush) carries no entity/identifier, so the context map must
   * degrade to the exception name alone rather than blowing up on a missing accessor.
   */
  @Test
  void handleOptimisticLocking_jpaVariant_logsOnlyTheExceptionName() {
    when(request.getMethod()).thenReturn("PUT");

    Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      handler.handleOptimisticLockingFailure(new OptimisticLockException("stale"), request);

      ILoggingEvent warn =
          appender.list.stream()
              .filter(e -> e.getLevel() == Level.WARN)
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected a WARN log line for the 409"));
      String formatted = warn.getFormattedMessage();
      assertTrue(formatted.contains("exception=OptimisticLockException"), formatted);
      assertFalse(formatted.contains("versions="), "no version pair to report here: " + formatted);
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void handleOptimisticLocking_jpaVariant_returns409WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleOptimisticLockingFailure(new OptimisticLockException("stale"), request);

    assertCommon(resp, HttpStatus.CONFLICT, GlobalExceptionHandler.CODE_OPTIMISTIC_LOCK);
  }

  @Test
  void handlePessimisticLocking_returns409WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handlePessimisticLocking(new PessimisticLockingFailureException("busy"), request);

    assertCommon(resp, HttpStatus.CONFLICT, GlobalExceptionHandler.CODE_PESSIMISTIC_LOCK);
    assertEquals("Resource busy", resp.getBody().getTitle());
  }

  @Test
  void handleAuthentication_returns401WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleAuthentication(new BadCredentialsException("bad creds"), request);

    assertCommon(resp, HttpStatus.UNAUTHORIZED, GlobalExceptionHandler.CODE_UNAUTHENTICATED);
    assertHttpErrorCounted(GlobalExceptionHandler.CODE_UNAUTHENTICATED);
  }

  /**
   * REQ-OBS-001: a 401 UNAUTHENTICATED is the expected default for any unauthenticated caller
   * (internal-TLS health probes hitting {@code /}, bots, pre-login navigation), so it must log at
   * DEBUG — never WARN — to keep the log free of steady probe noise. The counter (asserted above)
   * preserves the signal.
   */
  @Test
  void handleAuthentication_logsAtDebugNotWarn() {
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/");

    Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    Level original = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      handler.handleAuthentication(new BadCredentialsException("bad creds"), request);

      assertTrue(
          appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
          "a 401 must not be logged at WARN (unauthenticated is the expected default → probe"
              + " noise)");
      ILoggingEvent debug =
          appender.list.stream()
              .filter(e -> e.getLevel() == Level.DEBUG)
              .filter(e -> e.getFormattedMessage().contains("Authentication required"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected a DEBUG log line for the 401"));
      String formatted = debug.getFormattedMessage();
      assertTrue(formatted.contains("GET"), "log must include the HTTP method");
      assertTrue(
          formatted.contains("correlationId="), "log must include the correlationId for tracing");
    } finally {
      logger.setLevel(original);
      logger.detachAppender(appender);
    }
  }

  @Test
  void handleAccessDenied_legacyException_returns403WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleAccessDenied(new AccessDeniedException("Access is denied"), request);

    assertCommon(resp, HttpStatus.FORBIDDEN, GlobalExceptionHandler.CODE_ACCESS_DENIED);
    assertHttpErrorCounted(GlobalExceptionHandler.CODE_ACCESS_DENIED);
  }

  @Test
  void handleAccessDenied_authorizationDeniedException_returns403WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleAccessDenied(
            new AuthorizationDeniedException(
                "Access denied",
                new AuthorizationResult() {
                  @Override
                  public boolean isGranted() {
                    return false;
                  }
                }),
            request);

    assertCommon(resp, HttpStatus.FORBIDDEN, GlobalExceptionHandler.CODE_ACCESS_DENIED);
  }

  @Test
  void handleAccessDenied_fallsBackToGenericDetailIfBlank() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleAccessDenied(new AccessDeniedException(""), request);

    assertEquals("You are not authorized to perform this action.", resp.getBody().getDetail());
  }

  @Test
  void handleValidationExceptions_returns400WithFieldErrors() throws NoSuchMethodException {
    // Build a BindingResult with a single field error.
    BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
    bindingResult.addError(new FieldError("target", "name", "must not be blank"));
    MethodParameter parameter =
        new MethodParameter(
            GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class), 0);
    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(parameter, bindingResult);

    ResponseEntity<ProblemDetail> resp = handler.handleValidationExceptions(ex, request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_VALIDATION_FAILED);
    @SuppressWarnings("unchecked")
    Map<String, String> errors = (Map<String, String>) resp.getBody().getProperties().get("errors");
    assertEquals("must not be blank", errors.get("name"));
    @SuppressWarnings("unchecked")
    List<Map<String, String>> fieldErrors =
        (List<Map<String, String>>) resp.getBody().getProperties().get("fieldErrors");
    assertEquals(1, fieldErrors.size());
    assertEquals("name", fieldErrors.get(0).get("field"));
  }

  @SuppressWarnings("unused")
  private void dummy(String s) {
    /* test target */
  }

  @Test
  void handleValidationExceptions_logsWarn_withMethodUriAndFieldErrors_butWithoutRejectedValue()
      throws NoSuchMethodException {
    // Given: a binding result containing a field error whose rejected value would normally
    // contain user input / PII (e.g. recipientHandle). The improved logging MUST log the
    // field name and constraint message but MUST NOT log the rejected value itself.
    BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "handover");
    bindingResult.addError(
        new FieldError(
            "handover",
            "recipientHandle",
            "secret-pii-handle",
            false,
            new String[] {"NotBlank"},
            null,
            "must not be blank"));
    MethodParameter parameter =
        new MethodParameter(
            GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class), 0);
    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(parameter, bindingResult);

    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/api/v1/orders/abc/handovers");

    Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      // When
      handler.handleValidationExceptions(ex, request);

      // Then
      ILoggingEvent warn =
          appender.list.stream()
              .filter(e -> e.getLevel() == Level.WARN)
              .filter(e -> e.getFormattedMessage().contains("Validation failed"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected WARN log for validation failure"));

      String formatted = warn.getFormattedMessage();
      assertTrue(formatted.contains("POST"), "log must include HTTP method");
      assertTrue(
          formatted.contains("/api/v1/orders/abc/handovers"), "log must include the request URI");
      assertTrue(
          formatted.contains("recipientHandle"), "log must include the offending field name");
      assertTrue(
          formatted.contains("must not be blank"), "log must include the constraint message");
      assertTrue(
          formatted.contains("correlationId="),
          "log must include the correlationId for cross-referencing");
      assertFalse(
          formatted.contains("secret-pii-handle"),
          "log must NOT leak the rejected user-provided value (PII)");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void handleConstraintViolation_returns400WithFieldErrors() {
    ConstraintViolation<?> violation = mock(ConstraintViolation.class);
    Path path = mock(Path.class);
    when(path.toString()).thenReturn("page");
    when(violation.getPropertyPath()).thenReturn(path);
    when(violation.getMessage()).thenReturn("must be >= 0");
    ConstraintViolationException ex = new ConstraintViolationException("bad", Set.of(violation));

    ResponseEntity<ProblemDetail> resp = handler.handleConstraintViolation(ex, request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_CONSTRAINT_VIOLATION);
    @SuppressWarnings("unchecked")
    List<Map<String, String>> fieldErrors =
        (List<Map<String, String>>) resp.getBody().getProperties().get("fieldErrors");
    assertEquals(1, fieldErrors.size());
    assertEquals("page", fieldErrors.get(0).get("field"));
    assertEquals("must be >= 0", fieldErrors.get(0).get("message"));
  }

  @Test
  void handleTypeMismatch_returns400WithCode() {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException(
            "abc", Integer.class, "page", null, new IllegalArgumentException("nope"));

    ResponseEntity<ProblemDetail> resp = handler.handleTypeMismatch(ex, request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_TYPE_MISMATCH);
    assertTrue(resp.getBody().getDetail().contains("page"));
  }

  @Test
  void handleMethodNotSupported_returns405WithCode() {
    HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("PUT");

    ResponseEntity<ProblemDetail> resp = handler.handleMethodNotSupported(ex, request);

    assertCommon(
        resp, HttpStatus.METHOD_NOT_ALLOWED, GlobalExceptionHandler.CODE_METHOD_NOT_ALLOWED);
    assertTrue(resp.getBody().getDetail().contains("PUT"));
  }

  @Test
  void handleNotFound_entityNotFoundException_returns404WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleNotFound(new EntityNotFoundException("Mission 42"), request);

    assertCommon(resp, HttpStatus.NOT_FOUND, GlobalExceptionHandler.CODE_NOT_FOUND);
  }

  @Test
  void handleNotFound_noSuchElement_returns404WithCode() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleNotFound(new NoSuchElementException("gone"), request);

    assertCommon(resp, HttpStatus.NOT_FOUND, GlobalExceptionHandler.CODE_NOT_FOUND);
  }

  // --- §3.9: GlobalExceptionHandler#resolveDetail i18n key resolution -------------------

  @Test
  void handleBadRequest_passesLiteralEnglishMessageThrough() {
    // Backwards-compat: existing throw sites that pass a literal English string keep
    // working byte-identically on the wire. The resolver only translates when the
    // message looks up as a key in the bundle.
    ResponseEntity<ProblemDetail> resp =
        handler.handleAppException(
            new BadRequestException("Some literal message that is not a key"), request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_BAD_REQUEST);
    assertEquals("Some literal message that is not a key", resp.getBody().getDetail());
  }

  @Test
  void handleBadRequest_resolvesI18nKeyToEnglish() {
    // setUp() forces Locale.ENGLISH so the key resolves against messages_en.properties.
    ResponseEntity<ProblemDetail> resp =
        handler.handleAppException(
            new BadRequestException("error.refinery_order.location_required"), request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_BAD_REQUEST);
    assertEquals("A refinery order requires a location.", resp.getBody().getDetail());
  }

  @Test
  void handleBadRequest_resolvesI18nKeyToGermanWhenLocaleIsGerman() {
    LocaleContextHolder.setLocale(Locale.GERMAN);
    ResponseEntity<ProblemDetail> resp =
        handler.handleAppException(
            new BadRequestException("error.refinery_order.location_required"), request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_BAD_REQUEST);
    // Equivalent to the messages_de.properties entry "Für einen Raffinerieauftrag ...".
    assertEquals(
        "Für einen Raffinerieauftrag muss ein Lagerort angegeben werden.",
        resp.getBody().getDetail());
  }

  @Test
  void handleBadRequest_blankMessageFallsBackToGenericLocalized() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleAppException(new BadRequestException(""), request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_BAD_REQUEST);
    assertEquals(
        "The request could not be processed because it contains invalid data.",
        resp.getBody().getDetail());
  }

  @Test
  void handleNotFound_resolvesI18nKeyToEnglish() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleNotFound(new NotFoundException("error.user.not_found"), request);

    assertCommon(resp, HttpStatus.NOT_FOUND, GlobalExceptionHandler.CODE_NOT_FOUND);
    assertEquals("User not found.", resp.getBody().getDetail());
  }

  @Test
  void handleNotFound_passesLiteralEnglishMessageThrough() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleNotFound(new NotFoundException("Mission 42 not found"), request);

    assertCommon(resp, HttpStatus.NOT_FOUND, GlobalExceptionHandler.CODE_NOT_FOUND);
    assertEquals("Mission 42 not found", resp.getBody().getDetail());
  }

  @Test
  void handleDuplicateEntity_resolvesI18nKey() {
    // Even though no dedicated key for duplicate scenarios exists yet, the same
    // resolveDetail() seam is wired up - a future key would Just Work without
    // touching the handler.
    ResponseEntity<ProblemDetail> resp =
        handler.handleAppException(new DuplicateEntityException("error.user.not_found"), request);

    assertCommon(resp, HttpStatus.CONFLICT, GlobalExceptionHandler.CODE_DUPLICATE_ENTITY);
    assertEquals("User not found.", resp.getBody().getDetail());
  }

  @Test
  void handleAllExceptions_returns500WithCorrelationIdAndGenericDetail() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleAllExceptions(new RuntimeException("secret internal detail"), request);

    assertCommon(
        resp, HttpStatus.INTERNAL_SERVER_ERROR, GlobalExceptionHandler.CODE_INTERNAL_ERROR);
    // Must NOT leak the internal exception message to the client.
    String detail = resp.getBody().getDetail();
    assertNotNull(detail);
    assertTrue(
        !detail.contains("secret internal detail"),
        "internal exception message must not leak to client");
  }

  // ---------------------------------------------------------------------
  // EntityInUseException — 409 with i18n-aware detail
  // ---------------------------------------------------------------------

  @Test
  void handleEntityInUse_messageIsPassedThroughWhenPresent() {
    EntityInUseException ex =
        new de.greluc.krt.profit.basetool.backend.exception.EntityInUseException(
            "Mission is referenced by 3 inventory items");
    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, ex.code());
    assertEquals("Mission is referenced by 3 inventory items", resp.getBody().getDetail());
  }

  @Test
  void handleEntityInUse_fallsBackToLocalizedDetailWhenMessageBlank() {
    EntityInUseException ex =
        new de.greluc.krt.profit.basetool.backend.exception.EntityInUseException("");
    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, ex.code());
    assertEquals(
        "The entry cannot be deleted because it is still in use.", resp.getBody().getDetail());
  }

  // ---------------------------------------------------------------------
  // BusinessConflictException — 409 with i18n-aware detail
  // ---------------------------------------------------------------------

  @Test
  void handleBusinessConflict_messageIsPassedThroughWhenPresent() {
    BusinessConflictException ex =
        new de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException(
            "Mission already completed");
    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, ex.code());
    assertEquals("Mission already completed", resp.getBody().getDetail());
  }

  @Test
  void handleBusinessConflict_fallsBackToLocalizedDetailWhenMessageBlank() {
    BusinessConflictException ex =
        new de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException(null);
    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, ex.code());
    assertNotNull(resp.getBody().getDetail());
  }

  // ---------------------------------------------------------------------
  // ResponseStatusException — adapt status code, derive `code`
  // ---------------------------------------------------------------------

  @Test
  void handleResponseStatus_400_mapsToBadRequestCode() {
    org.springframework.web.server.ResponseStatusException ex =
        new org.springframework.web.server.ResponseStatusException(
            HttpStatus.BAD_REQUEST, "missing field");

    ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex, request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_BAD_REQUEST);
    assertEquals("missing field", resp.getBody().getTitle());
  }

  @Test
  void handleResponseStatus_401_mapsToUnauthenticatedCode() {
    org.springframework.web.server.ResponseStatusException ex =
        new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, null);

    ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex, request);

    assertCommon(resp, HttpStatus.UNAUTHORIZED, GlobalExceptionHandler.CODE_UNAUTHENTICATED);
    // Reason was null -> title falls back to the status reason phrase.
    assertEquals(HttpStatus.UNAUTHORIZED.getReasonPhrase(), resp.getBody().getTitle());
  }

  @Test
  void handleResponseStatus_404_mapsToNotFoundCode() {
    org.springframework.web.server.ResponseStatusException ex =
        new org.springframework.web.server.ResponseStatusException(
            HttpStatus.NOT_FOUND, "no such mission");

    ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex, request);

    assertCommon(resp, HttpStatus.NOT_FOUND, GlobalExceptionHandler.CODE_NOT_FOUND);
  }

  @Test
  void handleResponseStatus_409_mapsToDuplicateEntityCode() {
    org.springframework.web.server.ResponseStatusException ex =
        new org.springframework.web.server.ResponseStatusException(
            HttpStatus.CONFLICT, "duplicate");

    ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, GlobalExceptionHandler.CODE_DUPLICATE_ENTITY);
  }

  @Test
  void handleResponseStatus_500_mapsToInternalErrorCode() {
    org.springframework.web.server.ResponseStatusException ex =
        new org.springframework.web.server.ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "boom");

    ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex, request);

    assertCommon(
        resp, HttpStatus.INTERNAL_SERVER_ERROR, GlobalExceptionHandler.CODE_INTERNAL_ERROR);
  }

  @Test
  void handleResponseStatus_405_mapsToMethodNotAllowedCode() {
    org.springframework.web.server.ResponseStatusException ex =
        new org.springframework.web.server.ResponseStatusException(
            HttpStatus.METHOD_NOT_ALLOWED, "GET only");

    ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex, request);

    assertCommon(
        resp, HttpStatus.METHOD_NOT_ALLOWED, GlobalExceptionHandler.CODE_METHOD_NOT_ALLOWED);
  }

  @Test
  void handleResponseStatus_403_mapsToAccessDeniedCode() {
    org.springframework.web.server.ResponseStatusException ex =
        new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, null);

    ResponseEntity<ProblemDetail> resp = handler.handleResponseStatus(ex, request);

    assertCommon(resp, HttpStatus.FORBIDDEN, GlobalExceptionHandler.CODE_ACCESS_DENIED);
  }

  // ---------------------------------------------------------------------
  // ErrorResponseException — preserves provided body, fills in missing props
  // ---------------------------------------------------------------------

  @Test
  void handleErrorResponseException_preservesProblemDetailBody() {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "preset detail");
    body.setTitle("Preset Title");

    org.springframework.web.ErrorResponseException ex =
        new org.springframework.web.ErrorResponseException(HttpStatus.BAD_REQUEST, body, null);

    ResponseEntity<ProblemDetail> resp = handler.handleErrorResponseException(ex, request);

    assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value());
    assertEquals("Preset Title", resp.getBody().getTitle());
    assertEquals("preset detail", resp.getBody().getDetail());
    // Handler must fill in code + correlationId if they weren't on the body already.
    assertNotNull(resp.getBody().getProperties().get("code"));
    assertNotNull(resp.getBody().getProperties().get("correlationId"));
    // Instance must be overridden to the request URI.
    assertEquals(request.getRequestURI(), resp.getBody().getInstance().toString());
  }

  @Test
  void handleErrorResponseException_preservesPreExistingCodeAndCorrelationId() {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "x");
    body.setProperty("code", "CUSTOM_CODE");
    body.setProperty("correlationId", "preset-cid");

    org.springframework.web.ErrorResponseException ex =
        new org.springframework.web.ErrorResponseException(HttpStatus.NOT_FOUND, body, null);

    ResponseEntity<ProblemDetail> resp = handler.handleErrorResponseException(ex, request);

    assertEquals(
        "CUSTOM_CODE",
        resp.getBody().getProperties().get("code"),
        "pre-existing code must NOT be overwritten");
    assertEquals(
        "preset-cid",
        resp.getBody().getProperties().get("correlationId"),
        "pre-existing correlationId must NOT be overwritten");
  }

  // ---------------------------------------------------------------------
  // HttpMessageNotReadableException — unreadable body / JSON parse error
  // ---------------------------------------------------------------------

  @Test
  void handleHttpMessageNotReadable_returns400WithGenericDetail() {
    // The handler must NOT leak the raw exception message to the client.
    org.springframework.http.converter.HttpMessageNotReadableException ex =
        new org.springframework.http.converter.HttpMessageNotReadableException(
            "broken json with sensitive payload",
            new org.springframework.http.HttpInputMessage() {
              @Override
              public java.io.InputStream getBody() {
                return java.io.InputStream.nullInputStream();
              }

              @Override
              public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
              }
            });

    ResponseEntity<ProblemDetail> resp = handler.handleHttpMessageNotReadable(ex, request);

    assertCommon(resp, HttpStatus.BAD_REQUEST, GlobalExceptionHandler.CODE_BAD_REQUEST);
    // The detail must come from the localized bundle, NOT contain the raw message.
    assertTrue(
        !resp.getBody().getDetail().contains("sensitive payload"),
        "raw exception message must NOT leak through the detail");
  }

  // ---------------------------------------------------------------------
  // DataIntegrityViolationException — constraint-name regex extraction
  // ---------------------------------------------------------------------

  @Test
  void handleDataIntegrityViolation_returns409WithGenericDetail() {
    org.springframework.dao.DataIntegrityViolationException ex =
        new org.springframework.dao.DataIntegrityViolationException(
            "could not execute statement",
            new RuntimeException(
                "ERROR: insert or update violates foreign key constraint \"fk_mission_owner\"\n  "
                    + "Detail: Key (owner_id)=(123) is not present in table \"users\""));

    ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrityViolation(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, GlobalExceptionHandler.CODE_DATA_INTEGRITY);
    // Generic detail; constraint-name extraction lives in the log only, NOT in the response.
    assertTrue(
        !resp.getBody().getDetail().contains("fk_mission_owner"),
        "constraint name must NOT leak through the detail");
    assertTrue(
        !resp.getBody().getDetail().contains("(owner_id)=(123)"),
        "row data from the cause message must NOT leak through the detail");
  }

  @Test
  void handleDataIntegrityViolation_handlesNullCauseGracefully() {
    org.springframework.dao.DataIntegrityViolationException ex =
        new org.springframework.dao.DataIntegrityViolationException("statement failed");

    ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrityViolation(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, GlobalExceptionHandler.CODE_DATA_INTEGRITY);
  }

  // ---------------------------------------------------------------------
  // ExternalServiceException — 502 with generic localized detail
  // ---------------------------------------------------------------------

  @Test
  void handleExternalService_returns502_andDoesNotLeakUpstreamMessage() {
    de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException ex =
        new de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException(
            "Keycloak returned 503: <html><body>realm offline</body></html>");

    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.BAD_GATEWAY, ex.code());
    assertTrue(
        !resp.getBody().getDetail().contains("realm offline"),
        "raw upstream response must NOT leak through the client-visible detail");
    // The correlationId in the body must be a real UUID.
    String cid = (String) resp.getBody().getProperties().get("correlationId");
    assertNotNull(cid);
    assertTrue(cid.length() > 0);
  }

  // ---------------------------------------------------------------------
  // RestClientException — an upstream call is 502, never an unexplained 500
  // ---------------------------------------------------------------------

  /**
   * A 403 from an upstream admin API is a 502, not a 500, and its body stays server-side.
   *
   * <p>The case that produced this handler: deleting the ingest gateway's stray member row called
   * Keycloak's admin API, whose client may manage users but not inspect clients, so the call came
   * back 403. Nothing handled {@link RestClientException}, so an admin was told "an unexpected
   * error occurred" — which named neither the dependency nor the cause and is indistinguishable
   * from a genuine bug in this application.
   *
   * <p>Both halves matter. The status must be 502 because a dependency answered badly, even though
   * the upstream status is a 4xx: a 403 from the identity provider means THIS service lacks a role,
   * which is never the caller's fault. And the upstream body must not be relayed — for an identity
   * provider it can carry realm names, client ids or token material (CWE-209).
   */
  @Test
  void handleRestClient_returns502_andDoesNotLeakTheUpstreamBody() {
    org.springframework.web.client.RestClientException ex =
        org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.FORBIDDEN,
            "Forbidden",
            org.springframework.http.HttpHeaders.EMPTY,
            "{\"error\":\"HTTP 403 Forbidden\",\"realm\":\"iri\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8);

    ResponseEntity<ProblemDetail> resp = handler.handleRestClientException(ex, request);

    assertCommon(resp, HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR");
    assertTrue(
        !resp.getBody().getDetail().contains("realm"),
        "the upstream response body must NOT reach the client-visible detail");
    assertNotNull(resp.getBody().getProperties().get("correlationId"));
  }

  /**
   * Spring actually dispatches an upstream failure here, and not to the {@code Exception} fallback.
   *
   * <p>The assertion the other two cannot make: they call the handler directly, so they would still
   * pass if {@code @ExceptionHandler(Exception.class)} kept winning at runtime and every upstream
   * failure carried on being an unexplained 500. This drives {@link ExceptionHandlerMethodResolver}
   * — the same resolution Spring MVC performs — over the real class.
   */
  @Test
  void springResolvesAnUpstreamFailureToTheRestClientHandlerNotTheFallback() {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    assertEquals(
        "handleRestClientException",
        resolver
            .resolveMethod(new org.springframework.web.client.ResourceAccessException("x"))
            .getName());
    assertEquals(
        "handleRestClientException",
        resolver
            .resolveMethod(
                org.springframework.web.client.HttpClientErrorException.create(
                    HttpStatus.FORBIDDEN,
                    "Forbidden",
                    org.springframework.http.HttpHeaders.EMPTY,
                    new byte[0],
                    java.nio.charset.StandardCharsets.UTF_8))
            .getName());
  }

  /** A connection failure takes the same route — there is no status to misread there at all. */
  @Test
  void handleRestClient_mapsAConnectionFailureToo() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleRestClientException(
            new org.springframework.web.client.ResourceAccessException("connect timed out"),
            request);

    assertCommon(resp, HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR");
    assertTrue(
        !resp.getBody().getDetail().contains("connect timed out"),
        "the transport-level message must NOT reach the client-visible detail");
  }

  // ---------------------------------------------------------------------
  // Disconnected SSE clients — handled, silent, and no response body
  // ---------------------------------------------------------------------

  /**
   * The routing half. Without a dedicated handler this lands on {@code handleAllExceptions}, which
   * is how a client closing an SSE tab became an {@code ERROR} with a stack trace in production —
   * 30 of 50 WARN/ERROR lines in one 16-hour window came from this single cause.
   */
  @Test
  void springResolvesADisconnectedClientToItsOwnHandlerNotTheFallback() {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    assertEquals(
        "handleDisconnectedClient",
        resolver
            .resolveMethod(
                new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                    "Servlet container error notification for disconnected client",
                    new java.io.IOException("Broken pipe")))
            .getName());
  }

  /**
   * The response half. The handler must be {@code void}: the second production log line was Spring
   * failing to write a {@link ProblemDetail} into a response whose content type is already {@code
   * text/event-stream}, and there is no socket left to write to in any case.
   */
  @Test
  void aDisconnectedClientHandlerReturnsNothingAtAll() throws Exception {
    assertEquals(
        void.class,
        GlobalExceptionHandler.class
            .getMethod(
                "handleDisconnectedClient",
                org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
                HttpServletRequest.class)
            .getReturnType(),
        "a body cannot be written to a closed connection, and writing one is what produced the"
            + " HttpMessageNotWritableException warning this handler exists to stop");

    // Must not throw, and must not touch the response.
    handler.handleDisconnectedClient(
        new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
            "Servlet container error notification for disconnected client",
            new java.io.IOException("Broken pipe")),
        request);
  }

  // ---------------------------------------------------------------------
  // ReportGenerationException — 500 with generic localized detail
  // ---------------------------------------------------------------------

  @Test
  void handleReportGeneration_returns500_andDoesNotLeakLibraryError() {
    de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException ex =
        new de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException(
            "openpdf: invalid font /usr/share/fonts/missing.ttf", new RuntimeException());

    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.INTERNAL_SERVER_ERROR, ex.code());
    assertTrue(
        !resp.getBody().getDetail().contains("/usr/share/fonts"),
        "internal file paths must NOT leak through the client-visible detail");
  }

  // ---------------------------------------------------------------------
  // BankConflictException — 409 with the bank-specific stable code (S4, #910)
  // ---------------------------------------------------------------------

  @Test
  void handleBankConflict_returns409WithBankSpecificCodeAndProperties() {
    BankConflictException ex =
        new BankConflictException(
            BankConflictException.CODE_BANK_OVERDRAFT, "Overdraft", Map.of("available", 100));

    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, BankConflictException.CODE_BANK_OVERDRAFT);
    assertEquals(100, resp.getBody().getProperties().get("available"));
  }

  @Test
  void handleBankConflict_fallsBackToLocalizedDetailWhenMessageBlank() {
    BankConflictException ex =
        new BankConflictException(BankConflictException.CODE_BANK_ACCOUNT_CLOSED, "");

    ResponseEntity<ProblemDetail> resp = handler.handleAppException(ex, request);

    assertCommon(resp, HttpStatus.CONFLICT, BankConflictException.CODE_BANK_ACCOUNT_CLOSED);
    assertEquals("The account is closed and rejects bookings.", resp.getBody().getDetail());
  }
}
