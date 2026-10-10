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
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

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
@Slf4j(topic = ProblemSupport.LOGGER_NAME)
public class GlobalExceptionHandler extends ApplicationProblemHandlers {

  /**
   * Creates the handler family over the collaborators every problem response needs.
   *
   * @param problemProperties the problem type base URI
   * @param problemResponseFactory the factory that fills the standard problem fields
   * @param messageSource the bundle for the localized title and detail
   * @param meterRegistry the registry for the error counters
   */
  public GlobalExceptionHandler(
      AppProblemProperties problemProperties,
      ProblemResponseFactory problemResponseFactory,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    super(problemProperties, problemResponseFactory, messageSource, meterRegistry);
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
  @Override
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
}
