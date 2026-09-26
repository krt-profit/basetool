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
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.ratelimit.RateLimitedException;
import de.greluc.krt.profit.basetool.ingest.service.ServiceAccountTokenProvider;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Translates gateway failures into RFC 7807 {@code application/problem+json} (REQ-INGEST-001).
 *
 * <p>Validation and malformed bodies are 400; a backend 4xx keeps its status with only the
 * sanitized {@code detail}; a backend 401/403 or 5xx, a transport failure or an open circuit is
 * 502; anything else is 500. Never echoes a token or PII.
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /** Stable {@code code} extension values, so clients can branch without parsing prose. */
  private static final String CODE_VALIDATION = "VALIDATION_FAILED";

  private static final String CODE_BAD_REQUEST = "BAD_REQUEST";
  private static final String CODE_UPSTREAM = "BACKEND_RELAY_FAILED";
  private static final String CODE_INTERNAL = "INTERNAL_ERROR";

  /** The gateway could not obtain its own backend identity (ADR-0129). */
  private static final String CODE_GATEWAY_IDENTITY = "GATEWAY_IDENTITY_UNAVAILABLE";

  private static final String CODE_RATE_LIMITED = "RATE_LIMITED";

  /** Hard cap on the backend-supplied detail relayed to the extractor (security audit gap-fill). */
  private static final int MAX_RELAYED_DETAIL = 500;

  /** Cap on the joined field-error string written to the log, keeping the line bounded. */
  private static final int MAX_LOGGED_FIELD_ERRORS = 500;

  /** {@code Retry-After} advertised for a transient handoff-staging (Redis) outage, in seconds. */
  private static final String STAGING_RETRY_AFTER_SECONDS = "5";

  /** Generic detail used when no safe backend detail can be relayed. */
  private static final String GENERIC_BACKEND_REJECT = "The import backend rejected the request.";

  /**
   * Jackson mapper used to extract only the {@code detail}/{@code title} from a backend problem.
   */
  private final ObjectMapper objectMapper;

  /** Counts every mapped failure on the bounded {@code basetool_*} counters (REQ-OBS-011). */
  private final MeterRegistry meterRegistry;

  /** Supplies the MDC key the {@code correlationId} problem member is read from. */
  private final LoggingProperties loggingProperties;

  /**
   * The gateway's own backend identity, invalidated when the backend refuses it (a relayed {@code
   * 401}/{@code 403}) so the next upload mints a fresh token instead of replaying the refused one.
   */
  private final ServiceAccountTokenProvider serviceAccountTokenProvider;

  /**
   * Increments {@code basetool_ingest_handoff_errors_total} for a failed backend relay, tagged by
   * the bounded {@code reason} (REQ-OBS-011).
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

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      @NotNull HttpMessageNotReadableException ex,
      @NotNull HttpHeaders headers,
      @NotNull HttpStatusCode status,
      @NotNull WebRequest request) {
    log.warn("Ingest body could not be parsed as JSON ({})", ex.getClass().getSimpleName());
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "Malformed request body",
            CODE_BAD_REQUEST,
            "The request body could not be read as JSON.");
    return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
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
   * Answers a rejected client software (REQ-INGEST-011) with a {@code 403} and the code {@code
   * CLIENT_NOT_ALLOWED}; logs nothing, since the guard already did.
   *
   * @param ex the provenance rejection, carrying the detail sent to the caller
   * @return a 403 problem naming the approved-clients-only rule
   */
  @ExceptionHandler(ClientNotAllowedException.class)
  public @NotNull ProblemDetail handleClientNotAllowed(@NotNull ClientNotAllowedException ex) {
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, MetricNames.CODE_CLIENT_NOT_ALLOWED)
        .increment();
    return problem(
        HttpStatus.FORBIDDEN,
        "Client not allowed",
        MetricNames.CODE_CLIENT_NOT_ALLOWED,
        ex.getMessage());
  }

  /**
   * Handles an error status from the backend: a 4xx keeps its status and relays only the sanitized
   * {@link #backendDetail}; a 5xx becomes 502.
   *
   * <p>A backend 401 or 403 refuses the gateway's own token (ADR-0129), so it becomes a 502, is
   * logged at WARN and invalidates the cached service-account token.
   *
   * @param ex the relay's response exception
   * @return a relayed 4xx problem, or a 502 for a backend auth refusal or a backend 5xx
   */
  @ExceptionHandler(RestClientResponseException.class)
  public @NotNull ProblemDetail handleBackendResponse(@NotNull RestClientResponseException ex) {
    HttpStatusCode status = ex.getStatusCode();
    if (status.value() == HttpStatus.UNAUTHORIZED.value()
        || status.value() == HttpStatus.FORBIDDEN.value()) {
      log.warn(
          "Backend refused the gateway's own identity with {} — invalidating the cached"
              + " service-account token and surfacing as 502",
          status.value());
      serviceAccountTokenProvider.invalidate();
      countHandoffError(MetricNames.REASON_BACKEND_AUTH);
      return problem(
          HttpStatus.BAD_GATEWAY,
          "Backend relay failed",
          CODE_UPSTREAM,
          "The basetool gateway could not authenticate to the import backend. This is a"
              + " server-side problem, not a problem with your export or your login — please try"
              + " again shortly and report it if it persists.");
    }
    if (status.is4xxClientError()) {
      log.debug("Backend relay rejected the import with {}", status.value());
      countHandoffError(MetricNames.REASON_BACKEND_REJECT);
      return problem(status, "Backend rejected the import", CODE_BAD_REQUEST, backendDetail(ex));
    }
    log.warn("Backend relay returned {} — surfacing as 502", status.value());
    countHandoffError(MetricNames.REASON_BACKEND_UNAVAILABLE);
    return problem(
        HttpStatus.BAD_GATEWAY,
        "Backend unavailable",
        CODE_UPSTREAM,
        "The import backend returned an error. Please try again.");
  }

  /**
   * Answers an exhausted per-subject ingest budget with 429 and a {@code Retry-After} header
   * (REQ-INGEST-005).
   *
   * @param ex the rate-limit exception carrying the suggested retry delay
   * @return a 429 problem with a {@code Retry-After} header
   */
  @ExceptionHandler(RateLimitedException.class)
  public @NotNull ResponseEntity<ProblemDetail> handleRateLimited(
      @NotNull RateLimitedException ex) {
    ProblemDetail problem =
        problem(
            HttpStatus.TOO_MANY_REQUESTS,
            "Rate limit exceeded",
            CODE_RATE_LIMITED,
            "Too many ingest requests. Please retry later.");
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
        .body(problem);
  }

  /**
   * Answers a transport failure reaching the backend (connection refused, timeout, unreadable body)
   * with 502, logged at WARN.
   *
   * @param ex the request exception
   * @return a 502 problem
   */
  @ExceptionHandler(RestClientException.class)
  public @NotNull ProblemDetail handleBackendTransportFailure(@NotNull RestClientException ex) {
    log.warn("Backend relay failed: {}", ex.getClass().getSimpleName());
    return backendUnavailable();
  }

  /**
   * Answers a call rejected by the open {@code backend} circuit breaker with 502, logged at DEBUG
   * (REQ-OBS-001).
   *
   * @param ex the circuit-open exception
   * @return a 502 problem
   */
  @ExceptionHandler(CallNotPermittedException.class)
  public @NotNull ProblemDetail handleBackendCircuitOpen(@NotNull CallNotPermittedException ex) {
    log.debug("Backend circuit open ({}); rejecting relay", ex.getClass().getSimpleName());
    return backendUnavailable();
  }

  /**
   * Shared 502 body + {@code backend_unavailable} handoff-error count for both the
   * transport-failure and open-circuit branches.
   *
   * @return the 502 problem
   */
  private @NotNull ProblemDetail backendUnavailable() {
    countHandoffError(MetricNames.REASON_BACKEND_UNAVAILABLE);
    return problem(
        HttpStatus.BAD_GATEWAY,
        "Backend unavailable",
        CODE_UPSTREAM,
        "The import backend could not be reached. Please try again.");
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

  /**
   * Extracts a safe detail from a backend error: only the {@code detail} or {@code title} of an
   * {@code application/problem+json} body, capped at {@value #MAX_RELAYED_DETAIL} characters.
   *
   * @param ex the backend response exception
   * @return the backend problem's detail/title (capped), or a generic fallback
   */
  private @NotNull String backendDetail(@NotNull RestClientResponseException ex) {
    HttpHeaders headers = ex.getResponseHeaders();
    MediaType contentType = headers == null ? null : headers.getContentType();
    if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)) {
      return GENERIC_BACKEND_REJECT;
    }
    String body = ex.getResponseBodyAsString();
    if (body.isBlank()) {
      return GENERIC_BACKEND_REJECT;
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      String message = root.path("detail").asText("");
      if (message.isBlank()) {
        message = root.path("title").asText("");
      }
      if (!message.isBlank()) {
        return message.length() <= MAX_RELAYED_DETAIL
            ? message
            : message.substring(0, MAX_RELAYED_DETAIL);
      }
    } catch (JacksonException e) {
      log.debug("Could not parse backend problem+json body; using a generic detail.", e);
    }
    return GENERIC_BACKEND_REJECT;
  }
}
