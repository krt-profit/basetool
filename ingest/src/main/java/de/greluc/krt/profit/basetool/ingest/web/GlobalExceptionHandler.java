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

import de.greluc.krt.profit.basetool.ingest.logging.LogSafe;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.ratelimit.RateLimitedException;
import de.greluc.krt.profit.basetool.ingest.service.ServiceAccountTokenProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
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
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Translates gateway failures into RFC 7807 {@code application/problem+json} (REQ-INGEST-001,
 * REQ-API-*). Validation and malformed bodies are 400s; a backend 4xx keeps the backend status and
 * relays only the backend problem's {@code detail} (content-type-checked + length-capped, so the
 * envelope-reject message reaches the extractor without echoing a raw response body —
 * REQ-REFINERY-001/003); a backend 5xx, a connection failure, or an open circuit becomes 502;
 * anything else is a generic 500. The handler never echoes a token or PII into the response
 * (REQ-OBS-*).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the framework's standard MVC exceptions (and
 * therefore Spring Boot's auto-configured problem-details advice, which is conditional on no
 * user-provided handler) are owned here — the {@code code} extension is then attached consistently
 * to validation and body-parse problems too.
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

  private final MeterRegistry meterRegistry;

  /**
   * Increments {@code basetool_ingest_handoff_errors_total} for a failed backend relay, tagged by
   * the bounded {@code reason} (REQ-OBS-011). Only genuine relay failures are counted here; the
   * pre-relay rejections (validation, malformed body, rate limit) are not handoff failures. The
   * {@link de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind} is unavailable once the
   * controller stack has unwound, so this failure counter carries no {@code kind}.
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
    // "Mein Extrakt wird abgelehnt" is the most common ingest support question, and until now the
    // failing constraint existed only in the response body — so the operator had to ask the
    // reporter to paste it back. Field paths and Jakarta constraint messages are schema text, not
    // request content; the REJECTED VALUE is deliberately never touched (REQ-OBS-004), and the
    // joined string is sanitised because a field path can carry a client-supplied map key.
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
    // Class name only. Jackson's message quotes the offending part of the BODY, which on this
    // module is a user's extract — it must never reach an appender (REQ-OBS-004).
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
   * The caller's client software is not approved for the ingest path (REQ-INGEST-011) — the
   * payload-level provenance reject raised by {@code ProvenanceGuard}. Answered {@code 403} with
   * the same {@code CLIENT_NOT_ALLOWED} code the token-level gate writes from {@code
   * ClientIdentityFilter}, so a client sees one coherent answer regardless of which half refused
   * it.
   *
   * <p>No log line is emitted here: the guard already logged the reject at {@code WARN} with the
   * declared provenance, which is the whole diagnostic value, and REQ-OBS-001 allows exactly one
   * line per failure. The {@code basetool_ingest_client_rejected_total} counter is likewise the
   * guard's; this adds only the shared {@code basetool_http_error_total} tally so the 403 shows up
   * alongside every other error code on the dashboard.
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
   * The backend returned an error status. A 4xx keeps the backend status and relays only the
   * backend problem's sanitised {@code detail} (see {@link #backendDetail}, which
   * content-type-checks and caps it — never the raw body); a 5xx is collapsed to 502 so the gateway
   * never surfaces backend internals.
   *
   * @param ex the WebClient response exception
   * @return a relayed 4xx problem, or a 502 for backend 5xx
   */
  @ExceptionHandler(WebClientResponseException.class)
  public @NotNull ProblemDetail handleBackendResponse(@NotNull WebClientResponseException ex) {
    HttpStatusCode status = ex.getStatusCode();
    if (status.is4xxClientError()) {
      // DEBUG, not WARN: the backend already logged this reject at WARN with the full context, and
      // REQ-OBS-001 allows exactly one line per failure. This is the gateway-side breadcrumb that
      // says "the 400 the extractor saw came from the backend, not from our own validation" —
      // without it the two are indistinguishable in the ingest log.
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
   * The authenticated caller exhausted their per-subject ingest budget → 429 with {@code
   * Retry-After} (REQ-INGEST-005). Returned as a {@link ResponseEntity} rather than a bare {@link
   * ProblemDetail} so the {@code Retry-After} header can be attached.
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
   * A genuine transport failure reaching the backend (connection refused, timeout) → 502. This is
   * the signal the backend is down and is what opens the circuit breaker, so it is logged at WARN.
   *
   * @param ex the request exception
   * @return a 502 problem
   */
  @ExceptionHandler(WebClientRequestException.class)
  public @NotNull ProblemDetail handleBackendTransportFailure(
      @NotNull WebClientRequestException ex) {
    log.warn("Backend relay failed: {}", ex.getClass().getSimpleName());
    return backendUnavailable();
  }

  /**
   * A call short-circuited by the already-open {@code backend} circuit breaker → 502. Logged at
   * DEBUG, not WARN (REQ-OBS-001): the open breaker rejects every {@code /v1} call for its whole
   * wait-duration-in-open-state window, so at WARN a routine backend restart would flood the log
   * (the ingest analogue of issue #1203). The one-time state-transition WARN (the {@code
   * BackendImportClient} listener) plus the {@code resilience4j_circuitbreaker_state} gauge are the
   * health signal; nothing depends on this per-call line.
   *
   * @param ex the circuit-open exception
   * @return a 502 problem
   */
  @ExceptionHandler(CallNotPermittedException.class)
  public @NotNull ProblemDetail handleBackendCircuitOpen(@NotNull CallNotPermittedException ex) {
    // Reference the exception (class name only — no stack trace at DEBUG for a routine
    // short-circuit).
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
   * The Redis handoff staging was unreachable → retryable {@code 503} with {@code Retry-After}
   * (REQ-INGEST-003), rather than the generic {@code 500} this used to fall through to.
   *
   * <p>Redis is the gateway's only data store, so any {@link DataAccessException} here means the
   * relayed draft could not be parked for browser pickup. Two things were wrong with letting that
   * land in {@link #handleUnexpected}: the caller was handed a non-retryable {@code 500} for an
   * outage that self-heals in seconds, and the operator saw {@code ERROR "Unexpected ingest
   * failure"} with a stack trace — indistinguishable from a genuine code defect, and it inflates
   * {@code logback_events_total{level="error"}} enough to trip {@code LogbackErrorSpike}
   * (REQ-OBS-013). This is the same treatment {@code IdentityProviderUnavailableFilter} gives an
   * unreachable Keycloak: an availability event is a {@code WARN} and a {@code 503}.
   *
   * <p>Note the ordering guarantee this relies on: Spring picks the most specific
   * {@code @ExceptionHandler}, so this method wins over the {@link Exception} catch-all.
   *
   * @param ex the data-access failure raised by the Redis staging write
   * @return a 503 problem carrying {@code Retry-After}
   */
  @ExceptionHandler(DataAccessException.class)
  public @NotNull ResponseEntity<ProblemDetail> handleStagingUnavailable(
      @NotNull DataAccessException ex) {
    // Class name only — a Lettuce message can carry the configured Redis endpoint.
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
   * The gateway cannot obtain its own identity for the backend hop → 503 with a named cause.
   *
   * <p>Since ADR-0129 this grant sits on the critical path of every upload, and it fails in a hop
   * no client can see. Left to the catch-all below it surfaced as "An unexpected error occurred." —
   * which is what a member actually saw on 2026-08-04, with nothing to act on and nothing to tell
   * an operator where to look. It is a configuration or connectivity fault at the gateway, not
   * something the caller did, so it gets its own code and a retry hint.
   *
   * <p>Covers both shapes — no identity configured, and a grant that failed — because the provider
   * raises one type for both. To the sender they are the same situation: the gateway cannot act.
   * The distinction lives in the log and in {@code basetool_ingest_service_account_token_total},
   * where an operator can use it. Catching {@code IllegalStateException} here instead would have
   * been broader and worse: any unrelated state fault in the ingest path would report itself as a
   * login-server problem.
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
   * Builds a {@link ProblemDetail} with the stable {@code code} and the current correlation id
   * (when present in the MDC) attached as extension members.
   *
   * @param status the HTTP status
   * @param title a short, stable title
   * @param code the stable machine-readable code
   * @param detail the human-readable, non-sensitive detail
   * @return the assembled problem
   */
  private static @NotNull ProblemDetail problem(
      @NotNull HttpStatusCode status, @NotNull String title, @NotNull String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
    problem.setTitle(title);
    problem.setProperty("code", code);
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      problem.setProperty("correlationId", correlationId);
    }
    return problem;
  }

  /**
   * Extracts a safe detail string from a backend response error (security audit gap-fill). Only an
   * RFC 7807 {@code application/problem+json} body is consulted, and only its {@code detail} (or
   * {@code title}) field is relayed — never the raw body, which could be a non-JSON error page or
   * carry internal context — capped at {@value #MAX_RELAYED_DETAIL} characters. Falls back to a
   * generic phrase when the body is missing, not problem+json, or cannot be decoded.
   *
   * @param ex the backend response exception
   * @return the backend problem's detail/title (capped), or a generic fallback
   */
  private @NotNull String backendDetail(@NotNull WebClientResponseException ex) {
    MediaType contentType = ex.getHeaders().getContentType();
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
