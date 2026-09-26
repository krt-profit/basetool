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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.StaleObjectStateException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;

/**
 * Central RFC 7807 exception handler.
 *
 * <p>Every problem carries the standard fields plus two stable extensions:
 *
 * <ul>
 *   <li>{@code code} - a stable, machine-readable error code the frontend localizes (e.g. {@code
 *       OPTIMISTIC_LOCK}); never changed once published.
 *   <li>{@code correlationId} - a per-request id tying the error to the server log.
 * </ul>
 *
 * <p>4xx outcomes are logged at WARN/DEBUG without a stack trace, 5xx at ERROR with one. The
 * {@code @Order(HIGHEST_PRECEDENCE)} is required so this advice wins over Spring Boot's own
 * problem-details advice for the exception types both declare.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

  /** Stable error codes exposed via the {@code code} extension property. */
  public static final String CODE_OPTIMISTIC_LOCK = "OPTIMISTIC_LOCK";

  public static final String CODE_PESSIMISTIC_LOCK = "PESSIMISTIC_LOCK";
  public static final String CODE_ACCESS_DENIED = "ACCESS_DENIED";
  public static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
  public static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String CODE_CONSTRAINT_VIOLATION = "CONSTRAINT_VIOLATION";
  public static final String CODE_DUPLICATE_ENTITY = "DUPLICATE_ENTITY";
  public static final String CODE_ILLEGAL_ARGUMENT = "ILLEGAL_ARGUMENT";
  public static final String CODE_BAD_REQUEST = "BAD_REQUEST";
  public static final String CODE_TYPE_MISMATCH = "TYPE_MISMATCH";
  public static final String CODE_DATA_INTEGRITY = "DATA_INTEGRITY_VIOLATION";
  public static final String CODE_NOT_FOUND = "NOT_FOUND";
  public static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

  public static final String CODE_UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
  public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

  private static final String MDC_CORRELATION_ID = "correlationId";

  private final AppProblemProperties problemProperties;
  private final ProblemResponseFactory problemResponseFactory;
  private final MessageSource messageSource;
  private final MeterRegistry meterRegistry;

  private URI type(String suffix) {
    return URI.create(problemProperties.baseUri() + suffix);
  }

  /**
   * Increments {@code basetool_http_error_total} tagged with the stable error code (REQ-OBS-011).
   * Only the security and concurrency codes operators alert on (409, 401, 403) are counted.
   *
   * @param code the bounded {@code CODE_*} constant to tag the increment with
   */
  private void countHttpError(String code) {
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
  }

  /**
   * Resolves a localized message from the {@link MessageSource} for the locale in {@link
   * LocaleContextHolder}, falling back to the key itself when it is missing.
   */
  private String tr(String key, Object... args) {
    Locale locale = LocaleContextHolder.getLocale();
    return messageSource.getMessage(key, args, key, locale);
  }

  /**
   * Sentinel returned by {@link MessageSource#getMessage(String, Object[], String, Locale)} when
   * the key is not present in the bundle. The value is chosen so it cannot collide with any real
   * translation: two consecutive NUL characters never appear in human text.
   */
  private static final String MESSAGE_NOT_FOUND_SENTINEL = "\u0000\u0000__missing__\u0000\u0000";

  /**
   * Resolves the {@code detail} text for handlers that use the thrown exception's message.
   *
   * <ol>
   *   <li>{@code message} {@code null} or blank: the localized {@code fallbackKey}.
   *   <li>{@code message} is a key in the {@link MessageSource}: its localized translation.
   *   <li>Otherwise: {@code message} verbatim.
   * </ol>
   *
   * <p>A sentinel default distinguishes a missing key from a translation equal to the input.
   */
  private String resolveDetail(@Nullable String message, @NotNull String fallbackKey) {
    if (message == null || message.isBlank()) {
      return tr(fallbackKey);
    }
    Locale locale = LocaleContextHolder.getLocale();
    String resolved = messageSource.getMessage(message, null, MESSAGE_NOT_FOUND_SENTINEL, locale);
    return MESSAGE_NOT_FOUND_SENTINEL.equals(resolved) ? message : resolved;
  }

  private static ResponseEntity<ProblemDetail> toEntity(@NotNull ProblemDetail pd) {
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Resolve an existing correlation id from the SLF4J MDC or generate a fresh one. Using MDC makes
   * the same id appear in log lines emitted during the same request.
   */
  private static String correlationId() {
    return ProblemResponseFactory.correlationId();
  }

  /**
   * Runs {@code logStatement} with {@code cid} in the {@code correlationId} MDC key, then restores
   * the key exactly as it was: removed when absent, the previous value otherwise (REQ-OBS-002).
   *
   * @param cid the id shared by the ERROR line and the problem body
   * @param logStatement the log call to run while {@code cid} is in the MDC
   */
  private static void underCorrelationId(@NotNull String cid, @NotNull Runnable logStatement) {
    String previous = MDC.get(MDC_CORRELATION_ID);
    MDC.put(MDC_CORRELATION_ID, cid);
    try {
      logStatement.run();
    } finally {
      if (previous == null) {
        MDC.remove(MDC_CORRELATION_ID);
      } else {
        MDC.put(MDC_CORRELATION_ID, previous);
      }
    }
  }

  /** Package prefix that tells this application's stack frames from the framework's. */
  private static final String APP_PACKAGE = "de.greluc.krt.profit.basetool";

  /**
   * Postgres / H2 / generic JDBC constraint name pattern (e.g. "violates foreign key constraint
   * \"fk_xyz\"").
   */
  private static final Pattern CONSTRAINT_NAME_PATTERN =
      Pattern.compile("constraint\\s+\"?([A-Za-z0-9_]+)\"?");

  /**
   * Writes the structured 4xx log line at WARN; delegates to {@link #logProblem(HttpServletRequest,
   * ProblemDetail, String, Map, boolean)}.
   *
   * @param req the servlet request, for method and URI
   * @param pd the problem carrying status, {@code code} and {@code correlationId}
   * @param shortMessage the log prefix
   * @param extra optional structured context without PII; may be {@code null} or empty
   */
  private void logProblem(
      @NotNull HttpServletRequest req,
      ProblemDetail pd,
      @NotNull String shortMessage,
      @Nullable Map<String, ?> extra) {
    logProblem(req, pd, shortMessage, extra, false);
  }

  /**
   * Writes the structured 4xx log line with method, URI, status, {@code code} and {@code
   * correlationId}. The {@code extra} map is appended verbatim and must not contain rejected user
   * values.
   *
   * @param req the servlet request, for method and URI
   * @param pd the problem carrying status, {@code code} and {@code correlationId}
   * @param shortMessage the log prefix
   * @param extra optional structured context without PII; may be {@code null} or empty
   * @param debug {@code true} to log at DEBUG instead of WARN (used for 401)
   */
  private void logProblem(
      @NotNull HttpServletRequest req,
      @NotNull ProblemDetail pd,
      @NotNull String shortMessage,
      @Nullable Map<String, ?> extra,
      boolean debug) {
    Object cid = pd.getProperties() != null ? pd.getProperties().get("correlationId") : null;
    Object code = pd.getProperties() != null ? pd.getProperties().get("code") : null;
    boolean hasExtra = extra != null && !extra.isEmpty();
    String format =
        hasExtra
            ? "{} for {} {} [status={}, code={}, correlationId={}] {}"
            : "{} for {} {} [status={}, code={}, correlationId={}]";
    Object[] args =
        hasExtra
            ? new Object[] {
              shortMessage, req.getMethod(), req.getRequestURI(), pd.getStatus(), code, cid, extra
            }
            : new Object[] {
              shortMessage, req.getMethod(), req.getRequestURI(), pd.getStatus(), code, cid
            };
    if (debug) {
      log.debug(format, args);
    } else {
      log.warn(format, args);
    }
  }

  private ProblemDetail problem(
      HttpStatus status,
      String title,
      String detail,
      HttpServletRequest req,
      String typeSuffix,
      String code) {
    return problemResponseFactory.problem(
        status,
        title,
        detail,
        req != null ? req.getRequestURI() : null,
        typeSuffix,
        code,
        correlationId());
  }

  /**
   * Maps every optimistic-locking failure flavor to 409 with code {@code OPTIMISTIC_LOCK}; the WARN
   * line carries the entity, id and version pair from {@link #optimisticLockContext(Exception)}.
   *
   * @param ex the optimistic-locking exception
   * @param request servlet request for the {@code instance} URI and log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler({
    ObjectOptimisticLockingFailureException.class,
    OptimisticLockException.class,
    StaleObjectStateException.class
  })
  public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
      Exception ex, HttpServletRequest request) {
    countHttpError(CODE_OPTIMISTIC_LOCK);
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.optimistic_lock.title"),
            tr("problem.optimistic_lock.detail"),
            request,
            "concurrency-conflict",
            CODE_OPTIMISTIC_LOCK);
    logProblem(request, pd, "Optimistic locking conflict", optimisticLockContext(ex));
    return toEntity(pd);
  }

  /**
   * Builds the insertion-ordered log context for the 409 WARN line: the exception's simple name
   * and, for {@link ObjectOptimisticLockingFailureException}, the entity class, identifier and
   * message (the version pair). Contains no user-supplied text (REQ-OBS-004).
   *
   * @param ex the optimistic-locking throwable
   * @return the mutable context map; may hold a {@code null} identifier
   */
  @NotNull
  private static Map<String, Object> optimisticLockContext(Exception ex) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("exception", ex.getClass().getSimpleName());
    if (ex instanceof ObjectOptimisticLockingFailureException conflict) {
      context.put("entity", String.valueOf(conflict.getPersistentClassName()));
      context.put("entityId", String.valueOf(conflict.getIdentifier()));
      context.put("versions", String.valueOf(conflict.getMessage()));
    }
    return context;
  }

  /**
   * Maps pessimistic-lock acquisition failures (timeout / deadlock-victim) to 409 with code {@code
   * PESSIMISTIC_LOCK}. Distinguishable from {@code OPTIMISTIC_LOCK} so the frontend can surface a
   * different retry hint and operations can monitor the two failure modes separately.
   *
   * @param ex thrown {@link PessimisticLockingFailureException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handlePessimisticLocking(
      PessimisticLockingFailureException ex, HttpServletRequest request) {
    countHttpError(CODE_PESSIMISTIC_LOCK);
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.pessimistic_lock.title"),
            tr("problem.pessimistic_lock.detail"),
            request,
            "pessimistic-lock",
            CODE_PESSIMISTIC_LOCK);
    logProblem(
        request,
        pd,
        "Pessimistic locking conflict",
        Map.of("exception", ex.getClass().getSimpleName()));
    return toEntity(pd);
  }

  /**
   * Maps a missing or invalid bearer token to 401 with code {@code UNAUTHENTICATED}; the response
   * never reveals which of the two it was.
   *
   * @param ex Spring Security {@link AuthenticationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    countHttpError(CODE_UNAUTHENTICATED);
    ProblemDetail pd =
        problem(
            HttpStatus.UNAUTHORIZED,
            tr("problem.unauthenticated.title"),
            tr("problem.unauthenticated.detail"),
            request,
            "unauthenticated",
            CODE_UNAUTHENTICATED);
    logProblem(
        request,
        pd,
        "Authentication required",
        Map.of("exception", ex.getClass().getSimpleName()),
        true);
    return toEntity(pd);
  }

  /**
   * Maps {@link AccessDeniedException} and {@link AuthorizationDeniedException} to 403 with code
   * {@code ACCESS_DENIED} and a generic detail that does not name the required role.
   *
   * @param ex the access-denied exception
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      Exception ex, HttpServletRequest request) {
    countHttpError(CODE_ACCESS_DENIED);
    final ProblemDetail pd =
        problem(
            HttpStatus.FORBIDDEN,
            tr("problem.access_denied.title"),
            tr("problem.access_denied.detail"),
            request,
            "access-denied",
            CODE_ACCESS_DENIED);
    Map<String, Object> extra = new HashMap<>();
    extra.put("exception", ex.getClass().getSimpleName());
    if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
      extra.put("reason", ex.getMessage());
    }
    if (ex instanceof AuthorizationDeniedException ade && ade.getAuthorizationResult() != null) {
      extra.put("authorizationResult", String.valueOf(ade.getAuthorizationResult()));
    }
    logProblem(request, pd, "Access denied", extra);
    return toEntity(pd);
  }

  /**
   * Maps a failed {@code @Valid @RequestBody} to 400 with code {@code VALIDATION_FAILED}, exposing
   * the field errors as an {@code errors} map and a {@code fieldErrors} list. Rejected values are
   * never logged.
   *
   * @param ex Spring's bind-result wrapper with the field violations
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidationExceptions(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> errorsByField = new HashMap<>();
    List<Map<String, String>> errors = new ArrayList<>();
    List<String> logSummary = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(
            fieldError -> {
              String field = fieldError.getField();
              String message = fieldError.getDefaultMessage();
              errorsByField.put(field, message);
              Map<String, String> entry = new HashMap<>();
              entry.put("field", field);
              entry.put("message", message);
              errors.add(entry);
              logSummary.add(field + "=" + message + " (code=" + fieldError.getCode() + ")");
            });
    ex.getBindingResult()
        .getGlobalErrors()
        .forEach(
            globalError ->
                logSummary.add(
                    "[" + globalError.getObjectName() + "] " + globalError.getDefaultMessage()));
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.validation_failed.title"),
            tr("problem.validation_failed.detail"),
            request,
            "constraint-violation",
            CODE_VALIDATION_FAILED);
    pd.setProperty("errors", errorsByField);
    pd.setProperty("fieldErrors", errors);
    log.warn(
        "Validation failed for {} {} [correlationId={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        pd.getProperties() != null ? pd.getProperties().get("correlationId") : null,
        logSummary);
    return toEntity(pd);
  }

  /**
   * Maps a Jakarta constraint violation on parameters or service methods to 400 with code {@code
   * CONSTRAINT_VIOLATION}, with the same {@code fieldErrors} list as {@link
   * #handleValidationExceptions}; {@code field} is the property path (e.g. {@code method.arg}).
   *
   * @param ex thrown {@link ConstraintViolationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    List<String> logSummary = new ArrayList<>();
    ex.getConstraintViolations()
        .forEach(
            v -> {
              String field = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
              Map<String, String> entry = new HashMap<>();
              entry.put("field", field);
              entry.put("message", v.getMessage());
              errors.add(entry);
              logSummary.add(field + "=" + v.getMessage());
            });
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.constraint_violation.title"),
            tr("problem.constraint_violation.detail"),
            request,
            "constraint-violation",
            CODE_CONSTRAINT_VIOLATION);
    pd.setProperty("fieldErrors", errors);
    log.warn(
        "Constraint violation for {} {} [correlationId={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        pd.getProperties() != null ? pd.getProperties().get("correlationId") : null,
        logSummary);
    return toEntity(pd);
  }

  /**
   * Dispatches every {@link AppException} subtype except {@link NotFoundException}, reading status,
   * code, i18n keys and log label from the exception itself.
   *
   * <p>For {@link ErrorDisclosurePolicy#SUPPRESSED} the detail is always the generic localized text
   * and the exception is logged at ERROR with the correlation id; otherwise the message is resolved
   * and the problem logged at WARN.
   *
   * @param ex the thrown {@link AppException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(AppException.class)
  public ResponseEntity<ProblemDetail> handleAppException(
      @NotNull AppException ex, HttpServletRequest request) {
    boolean suppressed = ex.disclosurePolicy() == ErrorDisclosurePolicy.SUPPRESSED;
    String detail =
        suppressed ? tr(ex.detailKey()) : resolveDetail(ex.getMessage(), ex.detailKey());
    ProblemDetail pd =
        problem(ex.status(), tr(ex.titleKey()), detail, request, ex.typeSuffix(), ex.code());
    if (suppressed) {
      String cid = correlationId();
      underCorrelationId(
          cid,
          () ->
              log.error(
                  "{} at {} [correlationId={}]", ex.logLabel(), request.getRequestURI(), cid, ex));
      pd.setProperty("correlationId", cid);
    } else {
      ex.extraProperties().forEach(pd::setProperty);
      Map<String, Object> extra = new LinkedHashMap<>();
      if (ex.logExtra() != null) {
        extra.putAll(ex.logExtra());
      }
      String origin = originOf(ex);
      if (origin != null) {
        extra.put("thrownAt", origin);
      }
      logProblem(request, pd, ex.logLabel(), extra);
    }
    return toEntity(pd);
  }

  /**
   * Returns the application source location that raised a refusal, for the log line. The location
   * is logged instead of the message, which may contain a rejected user value (REQ-OBS-004).
   *
   * @param ex the refusal
   * @return {@code File.java:123} of the innermost application frame, or {@code null} when the
   *     stack has none
   */
  @Nullable
  private static String originOf(@NotNull Throwable ex) {
    for (StackTraceElement frame : ex.getStackTrace()) {
      if (frame.getClassName().startsWith(APP_PACKAGE) && frame.getFileName() != null) {
        return frame.getFileName() + ":" + frame.getLineNumber();
      }
    }
    return null;
  }

  /**
   * Maps {@link IllegalArgumentException} to 400 with code {@code ILLEGAL_ARGUMENT}; the message is
   * logged but never echoed to the client.
   *
   * @param ex thrown {@link IllegalArgumentException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.illegal_argument.title"),
            tr("problem.illegal_argument.detail"),
            request,
            "invalid-argument",
            CODE_ILLEGAL_ARGUMENT);
    logProblem(
        request,
        pd,
        "IllegalArgumentException",
        Map.of("exceptionMessage", String.valueOf(ex.getMessage())));
    return toEntity(pd);
  }

  /**
   * Maps a raw {@link IllegalStateException} to a generic 500 like the {@link #handleAllExceptions
   * catch-all}: it signals a server defect, so its message is logged with the stack trace and never
   * echoed (REQ-API-004).
   *
   * @param ex thrown {@link IllegalStateException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response with status 500 and a generic detail
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> handleIllegalState(
      @NotNull IllegalStateException ex, HttpServletRequest request) {
    return handleAllExceptions(ex, request);
  }

  /**
   * Maps a {@link ResponseStatusException} to a problem with its carried status and a code from
   * {@link #codeForStatus(HttpStatus)}.
   *
   * @param ex thrown {@link ResponseStatusException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatus(
      @NotNull ResponseStatusException ex, HttpServletRequest request) {
    HttpStatus status =
        (ex.getStatusCode() instanceof HttpStatus hs)
            ? hs
            : HttpStatus.valueOf(ex.getStatusCode().value());
    String code = codeForStatus(status);
    String safeDetail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
    ProblemDetail pd =
        problem(status, safeDetail, safeDetail, request, Integer.toString(status.value()), code);
    logProblem(
        request, pd, "ResponseStatusException", Map.of("reason", String.valueOf(ex.getReason())));
    return toEntity(pd);
  }

  /**
   * Handles Spring's {@link ErrorResponseException}, keeping its carried body and only filling in a
   * missing {@code instance}, {@code code} and {@code correlationId}.
   *
   * @param ex thrown {@link ErrorResponseException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response, possibly augmented from the carried body
   */
  @ExceptionHandler(ErrorResponseException.class)
  public ResponseEntity<ProblemDetail> handleErrorResponseException(
      @NotNull ErrorResponseException ex, HttpServletRequest request) {
    HttpStatus status =
        (ex.getStatusCode() instanceof HttpStatus hs)
            ? hs
            : HttpStatus.valueOf(ex.getStatusCode().value());
    ProblemDetail base = ex.getBody();
    if (base == null) {
      base =
          problem(
              status,
              status.getReasonPhrase(),
              ex.getMessage(),
              request,
              Integer.toString(status.value()),
              codeForStatus(status));
    } else {
      base.setInstance(URI.create(request.getRequestURI()));
      if (base.getProperties() == null || !base.getProperties().containsKey("code")) {
        base.setProperty("code", codeForStatus(status));
      }
      if (base.getProperties() == null || !base.getProperties().containsKey("correlationId")) {
        base.setProperty("correlationId", correlationId());
      }
    }
    logProblem(
        request,
        base,
        "ErrorResponseException",
        Map.of("exception", ex.getClass().getSimpleName()));
    return toEntity(base);
  }

  /**
   * Maps an unreadable request body to 400 with code {@code BAD_REQUEST}, logging the JSON path of
   * the offending node but no user values.
   *
   * @param ex Spring's wrapper around the parse failure
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(
      @NotNull HttpMessageNotReadableException ex, HttpServletRequest request) {
    Throwable rootCause = ex.getMostSpecificCause();
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.unreadable_body.title"),
            tr("problem.unreadable_body.detail"),
            request,
            "bad-request",
            CODE_BAD_REQUEST);
    Map<String, Object> extra = new HashMap<>();
    extra.put("contentType", String.valueOf(request.getContentType()));
    if (rootCause != null) {
      extra.put("rootCause", rootCause.getClass().getSimpleName());
      extra.put("causeMessage", maskQuotedValues(rootCause.getMessage()));
      if (rootCause instanceof DatabindException jme && jme.getPath() != null) {
        StringBuilder path = new StringBuilder();
        jme.getPath()
            .forEach(
                ref -> {
                  if (ref.getPropertyName() != null) {
                    if (path.length() > 0) {
                      path.append('.');
                    }
                    path.append(ref.getPropertyName());
                  } else if (ref.getIndex() >= 0) {
                    path.append('[').append(ref.getIndex()).append(']');
                  }
                });
        if (path.length() > 0) {
          extra.put("jsonPath", path.toString());
        }
      }
    }
    logProblem(request, pd, "Unreadable request body", extra);
    return toEntity(pd);
  }

  /**
   * Replaces every double-quoted segment of a Jackson parse message with {@code "***"}, removing
   * the rejected user value while keeping the structural text (REQ-OBS-004).
   *
   * @param message the parse cause's message; may be {@code null}
   * @return the masked message, or {@code null} when {@code message} is {@code null}
   */
  @Contract("null -> null")
  @Nullable
  static String maskQuotedValues(String message) {
    if (message == null) {
      return null;
    }
    return message.replaceAll("\"[^\"]*\"", "\"***\"");
  }

  /**
   * Maps a {@link DataIntegrityViolationException} to 409 with code {@code
   * DATA_INTEGRITY_VIOLATION}, logging the constraint name matched by {@link
   * #CONSTRAINT_NAME_PATTERN} and only the first line of the cause message.
   *
   * @param ex thrown {@link DataIntegrityViolationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.data_integrity.title"),
            tr("problem.data_integrity.detail"),
            request,
            "data-integrity-violation",
            CODE_DATA_INTEGRITY);
    Throwable cause = ex.getMostSpecificCause();
    Map<String, Object> extra = new HashMap<>();
    if (cause != null) {
      extra.put("rootCause", cause.getClass().getSimpleName());
      String msg = cause.getMessage();
      if (msg != null) {
        Matcher m = CONSTRAINT_NAME_PATTERN.matcher(msg);
        if (m.find()) {
          extra.put("constraint", m.group(1));
        }
        int nl = msg.indexOf('\n');
        extra.put("causeMessage", nl > 0 ? msg.substring(0, nl) : msg);
      }
    }
    logProblem(request, pd, "Data integrity violation", extra);
    return toEntity(pd);
  }

  /**
   * Maps a path or query parameter conversion failure to 400 with code {@code TYPE_MISMATCH},
   * logging only the parameter name and target type, never the raw value.
   *
   * @param ex Spring's binder wrapper for the conversion failure
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(
      @NotNull MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.type_mismatch.title"),
            tr("problem.type_mismatch.detail", ex.getName()),
            request,
            "type-mismatch",
            CODE_TYPE_MISMATCH);
    Map<String, Object> extra = new HashMap<>();
    extra.put("parameter", ex.getName());
    extra.put(
        "targetType", ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "n/a");
    logProblem(request, pd, "Type mismatch", extra);
    return toEntity(pd);
  }

  /**
   * Maps a request body in an unreadable media type (e.g. {@code application/cbor}, which is
   * response-only) to 415 (REQ-API-011).
   *
   * @param ex the refusal, carrying the offending and the supported content types
   * @param request the request, for the {@code instance} URI and the correlation id
   * @return a {@code 415} RFC 7807 problem naming the refused type
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(
      @NotNull HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            tr("problem.unsupported_media_type.title"),
            tr("problem.unsupported_media_type.detail", String.valueOf(ex.getContentType())),
            request,
            "unsupported-media-type",
            CODE_UNSUPPORTED_MEDIA_TYPE);
    logProblem(
        request,
        pd,
        "Unsupported media type",
        Map.of("supportedMediaTypes", String.valueOf(ex.getSupportedMediaTypes())));
    return toEntity(pd);
  }

  /**
   * Maps an unsupported HTTP method to 405 with code {@code METHOD_NOT_ALLOWED}, logging the
   * supported methods.
   *
   * @param ex Spring's method-not-supported exception
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      @NotNull HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.METHOD_NOT_ALLOWED,
            tr("problem.method_not_allowed.title"),
            tr("problem.method_not_allowed.detail", ex.getMethod()),
            request,
            "method-not-allowed",
            CODE_METHOD_NOT_ALLOWED);
    logProblem(
        request,
        pd,
        "Method not allowed",
        Map.of(
            "supportedMethods",
            String.valueOf(
                Arrays.toString(
                    ex.getSupportedMethods() == null ? new String[0] : ex.getSupportedMethods()))));
    return toEntity(pd);
  }

  /**
   * Maps {@link NotFoundException}, {@link EntityNotFoundException}, {@link NoSuchElementException}
   * and {@link NoResourceFoundException} to 404, using the literals of {@link
   * AppExceptionKind#NOT_FOUND}.
   */
  @ExceptionHandler({
    NotFoundException.class,
    EntityNotFoundException.class,
    NoSuchElementException.class,
    NoResourceFoundException.class
  })
  public ResponseEntity<ProblemDetail> handleNotFound(
      @NotNull Exception ex, @NotNull HttpServletRequest request) {
    log.debug("Not found at {}: {}", request.getRequestURI(), ex.getMessage());
    ProblemDetail pd =
        problem(
            AppExceptionKind.NOT_FOUND.status(),
            tr(AppExceptionKind.NOT_FOUND.titleKey()),
            resolveDetail(ex.getMessage(), AppExceptionKind.NOT_FOUND.detailKey()),
            request,
            AppExceptionKind.NOT_FOUND.typeSuffix(),
            AppExceptionKind.NOT_FOUND.code());
    return toEntity(pd);
  }

  /**
   * Maps any failed outbound {@code RestClient} call, including an upstream 4xx, to 502 with code
   * {@code EXTERNAL_SERVICE_ERROR} by delegating to {@link #handleAppException}, so the upstream
   * body is logged but never relayed (CWE-209).
   *
   * @param ex the failed outbound call
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response with status 502
   */
  @ExceptionHandler(RestClientException.class)
  public ResponseEntity<ProblemDetail> handleRestClientException(
      @NotNull RestClientException ex, HttpServletRequest request) {
    return handleAppException(
        new ExternalServiceException("Outbound call failed: " + ex.getClass().getSimpleName(), ex),
        request);
  }

  /**
   * Swallows a client disconnect during a streamed (SSE) response: logs at DEBUG and writes
   * nothing, since the connection is gone.
   *
   * @param ex the disconnect, used only as the debug line's cause
   * @param request servlet request, for the URI in the debug line
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void handleDisconnectedClient(
      @NotNull AsyncRequestNotUsableException ex, @NotNull HttpServletRequest request) {
    log.debug("Client disconnected from {}: {}", request.getRequestURI(), ex.getMessage());
  }

  /**
   * Maps any otherwise unhandled {@link Exception} to 500 with code {@code INTERNAL_ERROR} and a
   * generic localized detail, logging the stack trace at ERROR with the correlation id.
   *
   * @param ex any unhandled exception
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response with status 500
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleAllExceptions(
      Exception ex, HttpServletRequest request) {
    AuthenticationCredentialsNotFoundException wrapped = wrappedMissingCredentials(ex);
    if (wrapped != null) {
      return handleAuthentication(wrapped, request);
    }
    String cid = correlationId();
    underCorrelationId(
        cid,
        () ->
            log.error(
                "Unexpected error at {} [correlationId={}]", request.getRequestURI(), cid, ex));
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, tr("problem.internal_error.detail"));
    pd.setTitle(tr("problem.internal_error.title"));
    pd.setType(type("internal-error"));
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("code", CODE_INTERNAL_ERROR);
    pd.setProperty("correlationId", cid);
    return toEntity(pd);
  }

  /**
   * Finds an {@link AuthenticationCredentialsNotFoundException} in the cause chain. Only this type
   * matches, so an identity-provider outage is never downgraded to a 401.
   *
   * @param ex the exception that reached the catch-all
   * @return the first matching cause, or {@code null}; the walk is depth-bounded
   */
  @Nullable
  private static AuthenticationCredentialsNotFoundException wrappedMissingCredentials(
      Throwable ex) {
    Throwable current = ex;
    for (int depth = 0; current != null && depth < 10; depth++) {
      if (current instanceof AuthenticationCredentialsNotFoundException missing) {
        return missing;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return null;
  }

  /**
   * Map an arbitrary HTTP status to a reasonable default error code. Used for {@link
   * ResponseStatusException} / {@link ErrorResponseException} where the original cause is not known
   * to this handler.
   */
  @NotNull
  private static String codeForStatus(HttpStatus status) {
    return switch (status) {
      case UNAUTHORIZED -> CODE_UNAUTHENTICATED;
      case FORBIDDEN -> CODE_ACCESS_DENIED;
      case NOT_FOUND -> CODE_NOT_FOUND;
      case CONFLICT -> CODE_DUPLICATE_ENTITY;
      case METHOD_NOT_ALLOWED -> CODE_METHOD_NOT_ALLOWED;
      case BAD_REQUEST -> CODE_BAD_REQUEST;
      default -> status.is5xxServerError() ? CODE_INTERNAL_ERROR : CODE_BAD_REQUEST;
    };
  }
}
