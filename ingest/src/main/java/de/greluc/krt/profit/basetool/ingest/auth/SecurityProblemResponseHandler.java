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

package de.greluc.krt.profit.basetool.ingest.auth;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.edge.IngestPathScope;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.ProblemResponseWriter;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the gateway's filter-level {@code 401} and {@code 403} as RFC 7807 problem bodies like
 * every other ingest error (REQ-API-004), keeping the {@code WWW-Authenticate} challenge of {@link
 * BearerTokenAuthenticationEntryPoint}.
 *
 * <p>The correlation id is taken from the MDC. A {@code 401} is logged at DEBUG and a {@code 403}
 * at WARN (REQ-OBS-001); both are counted on {@code basetool_http_error_total} (REQ-OBS-011).
 */
@Slf4j
@RequiredArgsConstructor
public class SecurityProblemResponseHandler
    implements AuthenticationEntryPoint, AccessDeniedHandler {

  /** Emits the RFC 6750 {@code WWW-Authenticate} challenge before the problem body is written. */
  private final BearerTokenAuthenticationEntryPoint bearerEntryPoint =
      new BearerTokenAuthenticationEntryPoint();

  /** Serializes the problem body. */
  private static final String BEARER_SCHEME = "Bearer ";

  /** The scheme of a DPoP-bound request. */
  private static final String DPOP_SCHEME = "DPoP ";

  /** The header carrying the DPoP proof. */
  private static final String DPOP_PROOF_HEADER = "DPoP";

  /** How much of a request path a log line keeps. */
  static final int MAX_LOGGED_PATH = 256;

  /** How much of a request method a log line keeps. */
  static final int MAX_LOGGED_METHOD = 16;

  /** What the proof verifier reports for an access token without {@code cnf.jkt}. */
  static final String UNBOUND_TOKEN_DESCRIPTION = "jkt claim is required.";

  private final ObjectMapper objectMapper;

  /** Counts every 401/403 on the bounded auth-failure and error counters. */
  private final MeterRegistry meterRegistry;

  /** Supplies the MDC key the problem body's {@code correlationId} is read from. */
  private final LoggingProperties loggingProperties;

  private final ExchangeDpopNonces nonces;

  private final ExchangeRefusals refusals;

  /**
   * Answers an unauthenticated request to a protected endpoint with a {@code 401} problem body,
   * keeping the {@code WWW-Authenticate} challenge Spring Security would have sent on its own.
   *
   * @param request the rejected request
   * @param response the response to write the problem body into
   * @param authException the authentication failure Spring Security raised
   * @throws IOException if writing the body fails
   */
  @Override
  public void commence(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AuthenticationException authException)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    String bearerErrorCode = bearerErrorCode(authException);
    if (IngestPathScope.isExchangeRequest(request)) {
      meterRegistry
          .counter(
              MetricNames.INGEST_AUTH_FAILURES,
              MetricNames.TAG_REASON,
              bearerErrorCode,
              MetricNames.TAG_PATH_SCOPE,
              MetricNames.PATH_SCOPE_EXCHANGE)
          .increment();
      commenceExchange(request, response, bearerErrorCode, authException);
      return;
    }
    bearerEntryPoint.commence(request, response, authException);
    log.debug(
        "Unauthenticated ingest request {} {} ({}, {})",
        LogSafe.text(request.getMethod(), MAX_LOGGED_METHOD),
        LogSafe.text(request.getRequestURI(), MAX_LOGGED_PATH),
        authException.getClass().getSimpleName(),
        bearerErrorCode);
    meterRegistry
        .counter(
            MetricNames.INGEST_AUTH_FAILURES,
            MetricNames.TAG_REASON,
            bearerErrorCode,
            MetricNames.TAG_PATH_SCOPE,
            IngestPathScope.scopeLabel(request))
        .increment();
    write(
        response,
        HttpStatus.UNAUTHORIZED,
        "Unauthenticated",
        MetricNames.CODE_UNAUTHENTICATED,
        "A valid bearer token is required.");
  }

  /**
   * Maps an authentication failure to its RFC 6750 bearer error code, or to {@link
   * MetricNames#AUTH_NO_CREDENTIALS} for a request without a credential (REQ-OBS-018), keeping the
   * metric label bounded (REQ-OBS-011). The error description is never read, as it can echo token
   * fragments.
   *
   * @param authException the failure Spring Security raised
   * @return one of the bounded {@code MetricNames.AUTH_*} values
   */
  private static @NotNull String bearerErrorCode(@NotNull AuthenticationException authException) {
    if (!(authException instanceof OAuth2AuthenticationException oauth2Exception)) {
      return authException instanceof InsufficientAuthenticationException
              || authException instanceof AuthenticationCredentialsNotFoundException
          ? MetricNames.AUTH_NO_CREDENTIALS
          : MetricNames.AUTH_OTHER;
    }
    if (asksForNonce(oauth2Exception)) {
      return MetricNames.AUTH_USE_DPOP_NONCE;
    }
    if (proofLimitRetryAfter(oauth2Exception) != null) {
      return MetricNames.AUTH_DPOP_PROOF_LIMIT;
    }
    if (storeFullRetryAfter(oauth2Exception) != null) {
      return MetricNames.AUTH_DPOP_STORE_FULL;
    }
    String code =
        oauth2Exception.getError() == null ? null : oauth2Exception.getError().getErrorCode();
    if (OAuth2ErrorCodes.INVALID_DPOP_PROOF.equals(code)) {
      return MetricNames.AUTH_INVALID_DPOP_PROOF;
    }
    if (MetricNames.AUTH_INVALID_TOKEN.equals(code)
        || MetricNames.AUTH_INVALID_REQUEST.equals(code)
        || MetricNames.AUTH_INSUFFICIENT_SCOPE.equals(code)) {
      return code;
    }
    return MetricNames.AUTH_OTHER;
  }

  /**
   * Answers an unauthenticated exchange request with the DPoP challenge and the current nonce
   * (REQ-XCH-006): a bearer token, a DPoP-scheme request without a proof and a token without a key
   * binding are {@code DPOP_REQUIRED}, a missing nonce gets the nonce to retry with and, as the
   * protocol's normal first round trip, is not counted as an exchange refusal, a proof of a member
   * at its proof cap is {@code 429 DPOP_PROOF_LIMIT} and a proof refused by a full replay store
   * {@code 503 SERVICE_UNAVAILABLE}, both with {@code Retry-After}, a bad proof is {@code
   * DPOP_INVALID}, anything else {@code UNAUTHENTICATED}.
   *
   * @param request the request
   * @param response the response
   * @param reason the failure's metric reason
   * @param authException the failure
   * @throws IOException if writing fails
   */
  private void commenceExchange(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull String reason,
      @NotNull AuthenticationException authException)
      throws IOException {
    response.setHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER, nonces.current());
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    String code;
    String detail;
    if (StringUtils.startsWithIgnoreCase(authorization, BEARER_SCHEME)
        || (StringUtils.startsWithIgnoreCase(authorization, DPOP_SCHEME)
            && request.getHeader(DPOP_PROOF_HEADER) == null)
        || isUnbound(authException)) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, ExchangeChallenge.header(null));
      code = ExchangeRefusals.DPOP_REQUIRED;
      detail = "Exchange routes need a DPoP-bound token and a DPoP proof.";
    } else if (MetricNames.AUTH_USE_DPOP_NONCE.equals(reason)) {
      response.setHeader(
          HttpHeaders.WWW_AUTHENTICATE,
          ExchangeChallenge.header(ExchangeDpopProofValidation.USE_DPOP_NONCE));
      write(
          response,
          HttpStatus.UNAUTHORIZED,
          "Unauthenticated",
          ExchangeRefusals.DPOP_INVALID,
          "Retry with the server nonce from the DPoP-Nonce header.");
      return;
    } else if (MetricNames.AUTH_DPOP_PROOF_LIMIT.equals(reason)) {
      Long retryAfter = proofLimitRetryAfter(authException);
      response.setHeader(
          HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter == null ? 1L : retryAfter));
      refusals.count(ExchangeRefusals.DPOP_PROOF_LIMIT, MetricNames.EXCHANGE_CLIENT_NONE);
      write(
          response,
          HttpStatus.TOO_MANY_REQUESTS,
          "Too many requests",
          ExchangeRefusals.DPOP_PROOF_LIMIT,
          "The member holds too many live DPoP proofs; retry after Retry-After.");
      return;
    } else if (MetricNames.AUTH_DPOP_STORE_FULL.equals(reason)) {
      Long retryAfter = storeFullRetryAfter(authException);
      response.setHeader(
          HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter == null ? 1L : retryAfter));
      refusals.count(ExchangeRefusals.SERVICE_UNAVAILABLE, MetricNames.EXCHANGE_CLIENT_NONE);
      write(
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          "Service unavailable",
          ExchangeRefusals.SERVICE_UNAVAILABLE,
          "The gateway cannot take more DPoP proofs right now; retry after Retry-After.");
      return;
    } else if (MetricNames.AUTH_INVALID_DPOP_PROOF.equals(reason)) {
      response.setHeader(
          HttpHeaders.WWW_AUTHENTICATE,
          ExchangeChallenge.header(OAuth2ErrorCodes.INVALID_DPOP_PROOF));
      code = ExchangeRefusals.DPOP_INVALID;
      detail = "The DPoP proof is invalid, replayed or bound to another key.";
    } else {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, ExchangeChallenge.header(null));
      code = ExchangeRefusals.UNAUTHENTICATED;
      detail = "A valid DPoP-bound token is required.";
    }
    refusals.count(code, MetricNames.EXCHANGE_CLIENT_NONE);
    write(response, HttpStatus.UNAUTHORIZED, "Unauthenticated", code, detail);
  }

  /**
   * Whether a failure is the proof verifier finding no key binding in the access token.
   *
   * @param exception the failure
   * @return {@code true} when a cause reports the token's missing {@code cnf.jkt}
   */
  private static boolean isUnbound(@NotNull Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof JwtValidationException validation
          && validation.getErrors().stream()
              .anyMatch(error -> UNBOUND_TOKEN_DESCRIPTION.equals(error.getDescription()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Finds the proof verifier's refusal of a proof whose member holds its cap of live proofs.
   *
   * @param exception the failure
   * @return the seconds until the member may send a proof again, or {@code null} when no cause is
   *     that refusal
   */
  private static @Nullable Long proofLimitRetryAfter(@NotNull Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof JwtValidationException validation) {
        for (var error : validation.getErrors()) {
          if (error instanceof DpopProofLimitError limit) {
            return limit.getRetryAfterSeconds();
          }
        }
      }
    }
    return null;
  }

  /**
   * Finds the proof verifier's refusal of a proof because the exchange's replay store is full.
   *
   * @param exception the failure
   * @return the seconds until a proof may be sent again, or {@code null} when no cause is that
   *     refusal
   */
  private static @Nullable Long storeFullRetryAfter(@NotNull Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof JwtValidationException validation) {
        for (var error : validation.getErrors()) {
          if (error instanceof DpopProofStoreFullError full) {
            return full.getRetryAfterSeconds();
          }
        }
      }
    }
    return null;
  }

  /**
   * Whether a failure is the proof verifier asking for the server nonce.
   *
   * @param exception the failure
   * @return {@code true} when a cause carries {@code use_dpop_nonce}
   */
  private static boolean asksForNonce(@NotNull Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof JwtValidationException validation
          && validation.getErrors().stream()
              .anyMatch(
                  error ->
                      ExchangeDpopProofValidation.USE_DPOP_NONCE.equals(error.getErrorCode()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Answers an authenticated-but-not-allowed request with a {@code 403} problem body.
   *
   * @param request the rejected request
   * @param response the response to write the problem body into
   * @param accessDeniedException the authorization failure Spring Security raised
   * @throws IOException if writing the body fails
   */
  @Override
  public void handle(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AccessDeniedException accessDeniedException)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    log.warn(
        "Access denied on ingest request {} {}",
        LogSafe.text(request.getMethod(), MAX_LOGGED_METHOD),
        LogSafe.text(request.getRequestURI(), MAX_LOGGED_PATH));
    write(
        response,
        HttpStatus.FORBIDDEN,
        "Access denied",
        MetricNames.CODE_ACCESS_DENIED,
        "You are not allowed to use this endpoint.");
  }

  /**
   * Counts the rejection under its stable code and writes the problem body through the same writer
   * the pre-security filters use, so all short-circuited ingest responses share one shape.
   *
   * @param response the response to populate
   * @param status the HTTP status to send
   * @param title the short, stable problem title
   * @param code the stable machine-readable code, also the metric tag
   * @param detail the non-sensitive, human-readable detail
   * @throws IOException if writing the body fails
   */
  private void write(
      HttpServletResponse response, HttpStatus status, String title, String code, String detail)
      throws IOException {
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
    ProblemResponseWriter.write(
        response, objectMapper, loggingProperties, status, title, code, detail);
  }
}
