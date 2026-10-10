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

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Shared base of the {@link GlobalExceptionHandler} families: the stable error codes, the localized
 * message lookup, the problem factory and the structured 4xx log line.
 *
 * <p>Every family below it logs under the {@code GlobalExceptionHandler} logger name, so the log
 * lines and the tests that capture them stay where they were.
 */
@Slf4j(topic = ProblemSupport.LOGGER_NAME)
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ProblemSupport {

  /** Logger name shared by every family. */
  static final String LOGGER_NAME =
      "de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler";

  /** Stable error codes exposed via the {@code code} extension property. */
  public static final String CODE_OPTIMISTIC_LOCK = CoreProblemCode.OPTIMISTIC_LOCK.code();

  public static final String CODE_PESSIMISTIC_LOCK = CoreProblemCode.PESSIMISTIC_LOCK.code();
  public static final String CODE_ACCESS_DENIED = CoreProblemCode.ACCESS_DENIED.code();
  public static final String CODE_UNAUTHENTICATED = CoreProblemCode.UNAUTHENTICATED.code();
  public static final String CODE_VALIDATION_FAILED = CoreProblemCode.VALIDATION_FAILED.code();
  public static final String CODE_CONSTRAINT_VIOLATION =
      CoreProblemCode.CONSTRAINT_VIOLATION.code();
  public static final String CODE_DUPLICATE_ENTITY = CoreProblemCode.DUPLICATE_ENTITY.code();
  public static final String CODE_ILLEGAL_ARGUMENT = CoreProblemCode.ILLEGAL_ARGUMENT.code();
  public static final String CODE_BAD_REQUEST = CoreProblemCode.BAD_REQUEST.code();
  public static final String CODE_TYPE_MISMATCH = CoreProblemCode.TYPE_MISMATCH.code();
  public static final String CODE_DATA_INTEGRITY = CoreProblemCode.DATA_INTEGRITY_VIOLATION.code();
  public static final String CODE_NOT_FOUND = CoreProblemCode.NOT_FOUND.code();
  public static final String CODE_METHOD_NOT_ALLOWED = CoreProblemCode.METHOD_NOT_ALLOWED.code();

  public static final String CODE_UNSUPPORTED_MEDIA_TYPE =
      CoreProblemCode.UNSUPPORTED_MEDIA_TYPE.code();
  public static final String CODE_INTERNAL_ERROR = CoreProblemCode.INTERNAL_ERROR.code();
  public static final String CODE_NOT_ACCEPTABLE = CoreProblemCode.NOT_ACCEPTABLE.code();
  public static final String CODE_REQUEST_BODY_TOO_LARGE =
      CoreProblemCode.REQUEST_BODY_TOO_LARGE.code();
  public static final String CODE_RATE_LIMIT_EXCEEDED = CoreProblemCode.RATE_LIMIT_EXCEEDED.code();

  private static final String MDC_CORRELATION_ID = "correlationId";

  private final AppProblemProperties problemProperties;
  private final ProblemResponseFactory problemResponseFactory;
  private final MessageSource messageSource;
  protected final MeterRegistry meterRegistry;

  /**
   * Builds the problem type URI for a suffix.
   *
   * @param suffix the type suffix
   * @return the absolute type URI
   */
  protected URI type(String suffix) {
    return URI.create(problemProperties.baseUri() + suffix);
  }

  /**
   * Increments {@code basetool_http_error_total} tagged with the stable error code (REQ-OBS-011).
   * Only the security and concurrency codes operators alert on (409, 401, 403) are counted.
   *
   * @param code the bounded {@code CODE_*} constant to tag the increment with
   */
  protected void countHttpError(String code) {
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
  }

  /**
   * Resolves a localized message from the {@link MessageSource} for the locale in {@link
   * LocaleContextHolder}, falling back to the key itself when it is missing.
   *
   * @param key the message key
   * @param args the message arguments
   * @return the localized text, or the key when the bundle has none
   */
  protected String tr(String key, Object... args) {
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
   *
   * @param message the exception message, possibly a message key
   * @param fallbackKey the key of the text used when the message is blank
   * @return the detail text
   */
  protected String resolveDetail(@Nullable String message, @NotNull String fallbackKey) {
    if (message == null || message.isBlank()) {
      return tr(fallbackKey);
    }
    Locale locale = LocaleContextHolder.getLocale();
    String resolved = messageSource.getMessage(message, null, MESSAGE_NOT_FOUND_SENTINEL, locale);
    return MESSAGE_NOT_FOUND_SENTINEL.equals(resolved) ? message : resolved;
  }

  /**
   * Wraps a problem in a response with its own status and the problem media type.
   *
   * @param pd the problem
   * @return the response entity
   */
  protected static ResponseEntity<ProblemDetail> toEntity(@NotNull ProblemDetail pd) {
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Resolve an existing correlation id from the SLF4J MDC or generate a fresh one. Using MDC makes
   * the same id appear in log lines emitted during the same request.
   *
   * @return the correlation id of the current request
   */
  protected static String correlationId() {
    return ProblemResponseFactory.correlationId();
  }

  /**
   * Runs {@code logStatement} with {@code cid} in the {@code correlationId} MDC key, then restores
   * the key exactly as it was: removed when absent, the previous value otherwise (REQ-OBS-002).
   *
   * @param cid the id shared by the ERROR line and the problem body
   * @param logStatement the log call to run while {@code cid} is in the MDC
   */
  protected static void underCorrelationId(@NotNull String cid, @NotNull Runnable logStatement) {
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

  /**
   * Writes the structured 4xx log line at WARN; delegates to {@link #logProblem(HttpServletRequest,
   * ProblemDetail, String, Map, boolean)}.
   *
   * @param req the servlet request, for method and URI
   * @param pd the problem carrying status, {@code code} and {@code correlationId}
   * @param shortMessage the log prefix
   * @param extra optional structured context without PII; may be {@code null} or empty
   */
  protected void logProblem(
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
  protected void logProblem(
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

  /**
   * Builds a problem through the shared factory, with the request URI as its instance.
   *
   * @param status the HTTP status
   * @param title the localized title
   * @param detail the localized detail
   * @param req the request; may be {@code null}
   * @param typeSuffix the suffix of the problem type URI
   * @param code the stable error code
   * @return the problem carrying the standard fields, {@code code} and {@code correlationId}
   */
  protected ProblemDetail problem(
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
   * Maps any otherwise unhandled exception; declared here so the families that fall back to the
   * generic 500 can call it.
   *
   * @param ex the exception
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  public abstract ResponseEntity<ProblemDetail> handleAllExceptions(
      Exception ex, HttpServletRequest request);
}
