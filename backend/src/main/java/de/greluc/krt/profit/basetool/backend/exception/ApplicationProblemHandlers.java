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
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.expression.EvaluationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Handlers for the application's own refusals and the framework's generic ones: {@link
 * AppException} and its subtypes, illegal arguments and states, status exceptions, an absent
 * resource and a failed outbound call.
 */
@Slf4j(topic = ProblemSupport.LOGGER_NAME)
public abstract class ApplicationProblemHandlers extends RequestProblemHandlers {
  /** Package prefix that tells this application's stack frames from the framework's. */
  private static final String APP_PACKAGE = "de.greluc.krt.profit.basetool";

  /**
   * Creates the handler family over the collaborators every problem response needs.
   *
   * @param problemProperties the problem type base URI
   * @param problemResponseFactory the factory that fills the standard problem fields
   * @param messageSource the bundle for the localized title and detail
   * @param meterRegistry the registry for the error counters
   */
  ApplicationProblemHandlers(
      AppProblemProperties problemProperties,
      ProblemResponseFactory problemResponseFactory,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    super(problemProperties, problemResponseFactory, messageSource, meterRegistry);
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
    ResponseEntity<ProblemDetail> entity = toEntity(pd);
    if (ex.responseHeaders().isEmpty()) {
      return entity;
    }
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(entity.getHeaders());
    ex.responseHeaders().forEach(headers::set);
    return new ResponseEntity<>(pd, headers, entity.getStatusCode());
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
   * Registers both {@code basetool_security_expression_failures_total} series at zero, so the first
   * failure after a start is an increase a rate query can see (REQ-OBS-020).
   */
  @PostConstruct
  void registerSecurityExpressionFailureCounters() {
    for (String kind :
        List.of(
            MetricNames.SECURITY_EXPRESSION_EVALUATION, MetricNames.SECURITY_EXPRESSION_OTHER)) {
      meterRegistry.counter(MetricNames.SECURITY_EXPRESSION_FAILURES, MetricNames.TAG_KIND, kind);
    }
  }

  /**
   * Classifies an {@link IllegalArgumentException} that Spring Security raised while evaluating a
   * method-security expression (REQ-OBS-020).
   *
   * @param ex the exception the handler received
   * @return {@link MetricNames#SECURITY_EXPRESSION_EVALUATION} when the SpEL evaluation threw,
   *     {@link MetricNames#SECURITY_EXPRESSION_OTHER} for any other argument error thrown by Spring
   *     Security itself, {@code null} when the exception was not thrown by Spring Security
   */
  static @Nullable String securityExpressionFailureKind(@NotNull IllegalArgumentException ex) {
    StackTraceElement[] trace = ex.getStackTrace();
    if (trace.length == 0 || !trace[0].getClassName().startsWith("org.springframework.security.")) {
      return null;
    }
    return ex.getCause() instanceof EvaluationException
        ? MetricNames.SECURITY_EXPRESSION_EVALUATION
        : MetricNames.SECURITY_EXPRESSION_OTHER;
  }

  /**
   * Maps {@link IllegalArgumentException} to 400 with code {@code ILLEGAL_ARGUMENT}; the message is
   * logged but never echoed to the client. One that Spring Security raised while evaluating a
   * method-security expression is also counted on {@code
   * basetool_security_expression_failures_total} (REQ-OBS-020).
   *
   * @param ex thrown {@link IllegalArgumentException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    String expressionFailure = securityExpressionFailureKind(ex);
    if (expressionFailure != null) {
      meterRegistry
          .counter(
              MetricNames.SECURITY_EXPRESSION_FAILURES, MetricNames.TAG_KIND, expressionFailure)
          .increment();
    }
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
      case TOO_MANY_REQUESTS -> CODE_RATE_LIMIT_EXCEEDED;
      default -> status.is5xxServerError() ? CODE_INTERNAL_ERROR : CODE_BAD_REQUEST;
    };
  }
}
