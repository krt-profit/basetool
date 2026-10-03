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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeIdempotencyFilter;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.filter.IngestPathScope;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.service.ServiceAccountTokenProvider;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates gateway failures into RFC 7807 {@code application/problem+json} (REQ-INGEST-001).
 *
 * <p>Validation and malformed bodies are 400, on an exchange route {@code SCHEMA_INVALID}; a body
 * of another media type is 415; an unreachable store or a missing gateway identity is a retryable
 * 503; anything else is 500. Never echoes a token or PII.
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /** Stable {@code code} extension values, so clients can branch without parsing prose. */
  private static final String CODE_VALIDATION = "VALIDATION_FAILED";

  private static final String CODE_BAD_REQUEST = "BAD_REQUEST";

  private static final String CODE_NOT_FOUND = "NOT_FOUND";
  private static final String CODE_INTERNAL = "INTERNAL_ERROR";

  /** An exchange body that does not match the v1 contract, a body that is no JSON included. */
  private static final String CODE_SCHEMA_INVALID = "SCHEMA_INVALID";

  /** A body sent with a media type the route does not take. */
  static final String CODE_UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

  /** The gateway could not obtain its own backend identity (ADR-0129). */
  private static final String CODE_GATEWAY_IDENTITY = "GATEWAY_IDENTITY_UNAVAILABLE";

  /** Cap on the joined field-error string written to the log, keeping the line bounded. */
  private static final int MAX_LOGGED_FIELD_ERRORS = 500;

  /** {@code Retry-After} advertised for a transient handoff-staging (Redis) outage, in seconds. */
  private static final String STAGING_RETRY_AFTER_SECONDS = "5";

  /** {@code Retry-After} of an exchange store outage, in seconds, as the exchange answers it. */
  private static final String EXCHANGE_RETRY_AFTER_SECONDS = "60";

  /** Counts every mapped failure on the bounded {@code basetool_*} counters (REQ-OBS-011). */
  private final MeterRegistry meterRegistry;

  /** Supplies the MDC key the {@code correlationId} problem member is read from. */
  private final LoggingProperties loggingProperties;

  /**
   * Increments {@code basetool_ingest_handoff_errors_total}, tagged by the bounded {@code reason}
   * (REQ-OBS-011).
   *
   * @param reason the bounded failure reason ({@code MetricNames.REASON_*})
   */
  private void countHandoffError(String reason) {
    meterRegistry
        .counter(MetricNames.INGEST_HANDOFF_ERRORS, MetricNames.TAG_REASON, reason)
        .increment();
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      @NotNull MethodArgumentNotValidException ex,
      @NotNull HttpHeaders headers,
      @NotNull HttpStatusCode status,
      @NotNull WebRequest request) {
    List<String> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();
    log.warn(
        "Ingest payload rejected by validation: {}",
        LogSafe.text(String.join("; ", fieldErrors), MAX_LOGGED_FIELD_ERRORS));
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "Validation failed", CODE_VALIDATION, "Validation failed.");
    problem.setProperty("fieldErrors", fieldErrors);
    return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  /**
   * Answers a body that cannot be read as JSON with {@code 400}: on an exchange route as {@code
   * SCHEMA_INVALID} with one error at the document root, marked never to be replayed under its
   * {@code Idempotency-Key} (REQ-XCH-020, REQ-XCH-025); elsewhere as {@code BAD_REQUEST}.
   *
   * @param ex the read failure
   * @param headers the headers Spring prepared for the answer
   * @param status the status Spring chose
   * @param request the current request
   * @return the {@code 400} problem
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      @NotNull HttpMessageNotReadableException ex,
      @NotNull HttpHeaders headers,
      @NotNull HttpStatusCode status,
      @NotNull WebRequest request) {
    log.warn("Ingest body could not be parsed as JSON ({})", ex.getClass().getSimpleName());
    HttpServletRequest exchange = exchangeRequest(request);
    if (exchange != null) {
      exchange.setAttribute(ExchangeIdempotencyFilter.NOT_REPLAYABLE, Boolean.TRUE);
      ProblemDetail problem =
          problem(
              HttpStatus.BAD_REQUEST,
              "Bad request",
              CODE_SCHEMA_INVALID,
              "The body is not a JSON document.");
      problem.setProperty("errors", List.of(new Problems.FieldError("", "is not a JSON document")));
      return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "Malformed request body",
            CODE_BAD_REQUEST,
            "The request body could not be read as JSON.");
    return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  /**
   * Answers a body of a media type the route does not take with {@code 415} and the code {@code
   * UNSUPPORTED_MEDIA_TYPE}, keeping the {@code Accept} header Spring adds.
   *
   * @param ex the media-type failure
   * @param headers the headers Spring prepared for the answer
   * @param status the status Spring chose
   * @param request the current request
   * @return the {@code 415} problem
   */
  @Override
  protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
      @NotNull HttpMediaTypeNotSupportedException ex,
      @NotNull HttpHeaders headers,
      @NotNull HttpStatusCode status,
      @NotNull WebRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported media type",
            CODE_UNSUPPORTED_MEDIA_TYPE,
            "The body must be sent as application/json.");
    return handleExceptionInternal(
        ex, problem, headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
  }

  /**
   * Returns the servlet request behind a web request when it targets an exchange route.
   *
   * @param request the web request
   * @return the servlet request, or {@code null} outside {@code /exchange}
   */
  private static @Nullable HttpServletRequest exchangeRequest(@NotNull WebRequest request) {
    return request instanceof ServletWebRequest servlet
            && IngestPathScope.isExchangeRequest(servlet.getRequest())
        ? servlet.getRequest()
        : null;
  }

  /**
   * Gateway-detected client problems → 400.
   *
   * @param ex the bad-request exception (its message is a safe, non-sensitive detail)
   * @return a 400 problem
   */
  @ExceptionHandler(BadRequestException.class)
  public @NotNull ProblemDetail handleBadRequest(@NotNull BadRequestException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Bad request", CODE_BAD_REQUEST, ex.getMessage());
  }

  /**
   * Answers a request for a document that does not exist with a {@code 404} and the code {@code
   * NOT_FOUND}.
   *
   * @param ex the exception, its message a safe detail
   * @return a 404 problem
   */
  @ExceptionHandler(NotFoundException.class)
  public @NotNull ProblemDetail handleNotFound(@NotNull NotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not found", CODE_NOT_FOUND, ex.getMessage());
  }

  /**
   * Answers an exchange store that cannot be reached, whichever route it escaped from, with the
   * exchange's retryable {@code 503 SERVICE_UNAVAILABLE} and {@code Retry-After: 60} instead of a
   * {@code 500} (REQ-XCH-023).
   *
   * @param ex the failure
   * @return a 503 problem carrying {@code Retry-After}
   */
  @ExceptionHandler(ExchangeUnavailableException.class)
  public @NotNull ResponseEntity<ProblemDetail> handleExchangeUnavailable(
      @NotNull ExchangeUnavailableException ex) {
    log.warn("Exchange store unavailable: {}", ex.getClass().getSimpleName());
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, MetricNames.CODE_SERVICE_UNAVAILABLE)
        .increment();
    ProblemDetail problem =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Service unavailable",
            MetricNames.CODE_SERVICE_UNAVAILABLE,
            "A store the exchange needs cannot be reached; try again later.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, EXCHANGE_RETRY_AFTER_SECONDS)
        .body(problem);
  }

  /**
   * Answers an unreachable Redis handoff store with a retryable {@code 503} and {@code
   * Retry-After}, logged at WARN (REQ-INGEST-003).
   *
   * @param ex the data-access failure raised by the Redis staging write
   * @return a 503 problem carrying {@code Retry-After}
   */
  @ExceptionHandler(DataAccessException.class)
  public @NotNull ResponseEntity<ProblemDetail> handleStagingUnavailable(
      @NotNull DataAccessException ex) {
    log.warn("Handoff staging unavailable: {}", ex.getClass().getSimpleName());
    countHandoffError(MetricNames.REASON_STAGING_UNAVAILABLE);
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, MetricNames.CODE_SERVICE_UNAVAILABLE)
        .increment();
    ProblemDetail problem =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Service unavailable",
            MetricNames.CODE_SERVICE_UNAVAILABLE,
            "The import could not be staged for pickup. Please retry shortly.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, STAGING_RETRY_AFTER_SECONDS)
        .body(problem);
  }

  /**
   * Answers a gateway that cannot obtain its own backend identity, whether unconfigured or after a
   * failed grant, with 503 and a dedicated code.
   *
   * @param ex the identity failure
   * @return a 503 problem naming the gateway, not the caller
   */
  @ExceptionHandler(ServiceAccountTokenProvider.ServiceAccountTokenException.class)
  public @NotNull ResponseEntity<ProblemDetail> handleGatewayIdentityUnavailable(
      @NotNull ServiceAccountTokenProvider.ServiceAccountTokenException ex) {
    log.error("The gateway has no usable identity for the backend hop", ex);
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE_GATEWAY_IDENTITY)
        .increment();
    ProblemDetail problem =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Service unavailable",
            CODE_GATEWAY_IDENTITY,
            "The basetool gateway is not able to reach the login server right now. This is a"
                + " server-side problem, not a problem with your export — please try again shortly"
                + " and report it if it persists.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, STAGING_RETRY_AFTER_SECONDS)
        .body(problem);
  }

  /**
   * Catch-all → 500, with the cause logged but never leaked into the response.
   *
   * @param ex the unexpected exception
   * @return a generic 500 problem
   */
  @ExceptionHandler(Exception.class)
  public @NotNull ProblemDetail handleUnexpected(@NotNull Exception ex) {
    log.error("Unexpected ingest failure", ex);
    countHandoffError(MetricNames.REASON_INTERNAL);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Internal error",
        CODE_INTERNAL,
        "An unexpected error occurred.");
  }

  /**
   * Builds a {@link ProblemDetail} with the stable {@code code} and the current correlation id via
   * {@link Problems#of}.
   *
   * @param status the HTTP status
   * @param title a short, stable title
   * @param code the stable machine-readable code
   * @param detail the human-readable, non-sensitive detail
   * @return the assembled problem
   */
  private @NotNull ProblemDetail problem(
      @NotNull HttpStatusCode status,
      @NotNull String title,
      @NotNull String code,
      @Nullable String detail) {
    return Problems.of(loggingProperties, status, title, code, detail);
  }
}
