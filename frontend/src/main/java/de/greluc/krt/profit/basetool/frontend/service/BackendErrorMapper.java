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

import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps every failure of a backend call onto one {@link Outcome} and that outcome onto the log line,
 * the {@code basetool_backend_client_errors_total} increment and the exception the caller sees
 * (ADR-0032, plan F1).
 *
 * <p>{@link #classify(Throwable)} is pure; {@link #map(Throwable, String, String)} adds the side
 * effects in one exhaustive pattern switch. Log lines go to the logger the owner hands in, so they
 * keep the owner's category.
 */
@RequiredArgsConstructor
public final class BackendErrorMapper {

  /** Reads a backend problem body; only its tree is used. */
  private static final ObjectMapper PROBLEM_READER = JsonMapper.builder().build();

  /** Receives the failure counter. */
  private final @NotNull MeterRegistry meterRegistry;

  /** The logger every mapped failure is written to. */
  private final @NotNull Logger log;

  /** What a failed backend call turned out to be. */
  public sealed interface Outcome {}

  /**
   * The backend answered with an error status; its RFC 7807 body is parsed into {@code exception}.
   *
   * @param exception the parsed refusal, thrown unchanged to the caller
   */
  public record Problem(@NotNull BackendServiceException exception) implements Outcome {}

  /**
   * The OAuth2 client holds no usable token, typically after the refresh token expired.
   *
   * @param cause the failure carrying the {@code ClientAuthorizationException}
   */
  public record Reauthentication(@NotNull Throwable cause) implements Outcome {}

  /**
   * The circuit breaker refused the call without sending it.
   *
   * @param cause the failure as raised
   */
  public record CircuitOpen(@NotNull Throwable cause) implements Outcome {}

  /**
   * The bulkhead refused the call because every permit was taken.
   *
   * @param cause the failure as raised
   */
  public record BulkheadFull(@NotNull Throwable cause) implements Outcome {}

  /**
   * The time limiter fired, or the connection failed or broke.
   *
   * @param cause the failure as raised
   */
  public record Timeout(@NotNull Throwable cause) implements Outcome {}

  /**
   * Anything else, including a malformed URI template.
   *
   * @param cause the failure as raised
   */
  public record Unexpected(@NotNull Throwable cause) implements Outcome {}

  /**
   * Classifies a failed backend call. An error-status response wins over everything else; then a
   * re-authentication signal anywhere in the cause chain; then the first resilience or transport
   * failure in the chain.
   *
   * @param error the failure the call raised
   * @return the outcome, never {@code null}
   */
  @NotNull
  public static Outcome classify(@NotNull Throwable error) {
    if (error instanceof WebClientResponseException response
        && response.getStatusCode().isError()) {
      return new Problem(parseProblem(response));
    }
    if (ReauthenticationRequiredException.isReauthSignal(error)) {
      return new Reauthentication(error);
    }
    Throwable root = transportRoot(error);
    if (root instanceof CallNotPermittedException) {
      return new CircuitOpen(error);
    }
    if (root instanceof BulkheadFullException) {
      return new BulkheadFull(error);
    }
    if (root != null) {
      return new Timeout(error);
    }
    return new Unexpected(error);
  }

  /**
   * Parses a backend error response into the exception the frontend relays.
   *
   * @param response the backend's error response
   * @return the parsed refusal; status-derived code when the body is no problem document
   */
  @NotNull
  public static BackendServiceException parseProblem(@NotNull WebClientResponseException response) {
    return BackendServiceException.fromProblem(response, PROBLEM_READER);
  }

  /**
   * Logs, counts and translates a failed backend call.
   *
   * @param error the failure the call raised
   * @param method the HTTP verb, the log field and the {@code method} metric label
   * @param uri the path or URI template, used only in log lines and exception messages
   * @return the {@link BackendServiceException} or {@link ReauthenticationRequiredException} to
   *     throw
   */
  @NotNull
  public RuntimeException map(
      @NotNull Throwable error, @NotNull String method, @NotNull String uri) {
    return switch (classify(error)) {
      case Problem(BackendServiceException parsed) -> {
        logProblem(parsed, method, uri);
        if (!isExpectedAccessGateRefusal(parsed.getProblemCode())) {
          count(
              parsed.getStatusCode() >= 500
                  ? MetricNames.REASON_BACKEND_5XX
                  : MetricNames.REASON_BACKEND_4XX,
              method);
        }
        yield parsed;
      }
      case Reauthentication(Throwable cause) -> {
        log.debug(
            "Re-authentication required on {} {} (correlationId={})",
            method,
            uri,
            MDC.get("correlationId"));
        yield new ReauthenticationRequiredException(
            "Re-authentication required for " + method + " " + uri, cause);
      }
      case CircuitOpen(Throwable cause) -> {
        log.debug("Circuit breaker open for {} {}: {}", method, uri, rootMessage(cause));
        count(MetricNames.REASON_CIRCUIT_OPEN, method);
        yield unavailable("Backend circuit breaker open", cause);
      }
      case BulkheadFull(Throwable cause) -> {
        log.warn("Bulkhead saturated for {} {}: {}", method, uri, rootMessage(cause));
        count(MetricNames.REASON_BULKHEAD_FULL, method);
        yield unavailable("Backend bulkhead full", cause);
      }
      case Timeout(Throwable cause) -> {
        log.warn(
            "Backend timeout / connection failure on {} {}: {}", method, uri, rootMessage(cause));
        count(MetricNames.REASON_TIMEOUT, method);
        yield new BackendServiceException(
            "Backend timeout",
            cause,
            504,
            BackendServiceException.CODE_BACKEND_TIMEOUT,
            null,
            Collections.emptyList(),
            null);
      }
      case Unexpected(Throwable cause) -> {
        log.error("Unexpected backend error on {} {}: {}", method, uri, cause.getMessage(), cause);
        count(MetricNames.REASON_UNKNOWN, method);
        yield new BackendServiceException("Error on " + method + " data from backend", cause, 500);
      }
    };
  }

  /**
   * Whether a problem code is one of the access gates refusing an authenticated user (pending
   * approval, unaccepted terms, no role): expected and self-clearing (REQ-SEC-017, REQ-SEC-028,
   * REQ-SEC-053), so logged at DEBUG and not counted as a failure.
   *
   * @param problemCode the RFC 7807 {@code code} the backend returned
   * @return {@code true} when the refusal is an expected access gate
   */
  public static boolean isExpectedAccessGateRefusal(@NotNull String problemCode) {
    return BackendServiceException.CODE_PENDING_APPROVAL.equals(problemCode)
        || BackendServiceException.CODE_TERMS_NOT_ACCEPTED.equals(problemCode)
        || BackendServiceException.CODE_NO_ROLE.equals(problemCode);
  }

  /**
   * Logs a backend refusal: ERROR for a 5xx, DEBUG for an expected access gate, WARN otherwise.
   *
   * @param parsed the parsed refusal
   * @param method the HTTP verb
   * @param uri the path or URI template
   */
  private void logProblem(
      @NotNull BackendServiceException parsed, @NotNull String method, @NotNull String uri) {
    if (parsed.getStatusCode() >= 500) {
      log.error(
          "Backend error on {} {}: status={}, code={}, correlationId={}, detail={}, fieldErrors={}",
          method,
          uri,
          parsed.getStatusCode(),
          parsed.getProblemCode(),
          parsed.getCorrelationId(),
          parsed.getProblemDetail(),
          parsed.getFieldErrors());
    } else if (isExpectedAccessGateRefusal(parsed.getProblemCode())) {
      log.debug(
          "Backend client error on {} {}: status={}, code={}, correlationId={}",
          method,
          uri,
          parsed.getStatusCode(),
          parsed.getProblemCode(),
          parsed.getCorrelationId());
    } else {
      log.warn(
          "Backend client error on {} {}: status={}, code={}, correlationId={}, detail={},"
              + " fieldErrors={}",
          method,
          uri,
          parsed.getStatusCode(),
          parsed.getProblemCode(),
          parsed.getCorrelationId(),
          parsed.getProblemDetail(),
          parsed.getFieldErrors());
    }
  }

  /**
   * Increments {@code basetool_backend_client_errors_total} (REQ-OBS-011); both labels are bounded,
   * and the request URI is never one.
   *
   * @param reason the bounded failure reason ({@code MetricNames.REASON_*})
   * @param method the HTTP verb
   */
  private void count(@NotNull String reason, @NotNull String method) {
    meterRegistry
        .counter(
            MetricNames.BACKEND_CLIENT_ERRORS,
            MetricNames.TAG_REASON,
            reason,
            MetricNames.TAG_METHOD,
            method)
        .increment();
  }

  /**
   * Builds the {@code 503 SERVICE_UNAVAILABLE} refusal of a resilience gate.
   *
   * @param message the developer-facing message
   * @param cause the failure as raised
   * @return the refusal
   */
  @NotNull
  private static BackendServiceException unavailable(
      @NotNull String message, @NotNull Throwable cause) {
    return new BackendServiceException(
        message,
        cause,
        503,
        BackendServiceException.CODE_SERVICE_UNAVAILABLE,
        null,
        Collections.emptyList(),
        null);
  }

  /**
   * Returns the message of the resilience or transport failure inside {@code error}.
   *
   * @param error the failure as raised
   * @return that failure's message, or {@code error}'s own when none is in the chain
   */
  @Nullable
  private static String rootMessage(@NotNull Throwable error) {
    Throwable root = transportRoot(error);
    return root != null ? root.getMessage() : error.getMessage();
  }

  /**
   * Finds the first resilience or transport failure in the cause chain; stops at a self-referential
   * cause.
   *
   * @param error the failure as raised
   * @return the failure found, or {@code null} when the chain holds none
   */
  @Nullable
  private static Throwable transportRoot(@NotNull Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof CallNotPermittedException
          || current instanceof BulkheadFullException
          || current instanceof TimeoutException
          || current instanceof WebClientRequestException
          || current instanceof IOException) {
        return current;
      }
      if (current.getCause() == current) {
        return null;
      }
      current = current.getCause();
    }
    return null;
  }
}
