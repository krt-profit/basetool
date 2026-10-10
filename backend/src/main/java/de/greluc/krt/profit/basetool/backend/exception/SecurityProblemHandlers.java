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
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Handlers for the 401 and 403 outcomes: a missing or invalid authentication and a denied
 * authorisation.
 */
public abstract class SecurityProblemHandlers extends ConcurrencyProblemHandlers {

  /**
   * Creates the handler family over the collaborators every problem response needs.
   *
   * @param problemProperties the problem type base URI
   * @param problemResponseFactory the factory that fills the standard problem fields
   * @param messageSource the bundle for the localized title and detail
   * @param meterRegistry the registry for the error counters
   */
  SecurityProblemHandlers(
      AppProblemProperties problemProperties,
      ProblemResponseFactory problemResponseFactory,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    super(problemProperties, problemResponseFactory, messageSource, meterRegistry);
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
}
