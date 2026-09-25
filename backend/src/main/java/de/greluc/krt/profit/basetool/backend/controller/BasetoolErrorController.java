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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Boot's {@code BasicErrorController} so container-level error dispatches also render an
 * RFC 7807 {@code application/problem+json} body (REQ-API-004).
 *
 * <p>The body matches {@code GlobalExceptionHandler}'s contract, including a {@code correlationId}
 * reused from the MDC or minted, echoed as {@code X-Correlation-Id} and logged. {@code /error} is
 * {@code permitAll}, so anonymous callers get the same shape.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class BasetoolErrorController implements ErrorController {

  /** App-wide correlation-id response header, mirroring {@code LoggingProperties} default. */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** MDC key the correlation-id filter populates; read here to reuse an already-assigned id. */
  private static final String MDC_CORRELATION_ID = "correlationId";

  private final ProblemResponseFactory problemResponseFactory;
  private final MessageSource messageSource;

  /**
   * Renders the container error as a problem document. Resolves the status from the {@code
   * jakarta.servlet.error.status_code} request attribute (defaulting to {@code 500}), maps it to a
   * localized title/detail + stable code, and returns the {@code application/problem+json} body.
   *
   * @param request the error dispatch, carrying the {@code jakarta.servlet.error.*} attributes
   * @return the RFC&nbsp;7807 response for the resolved status
   */
  @RequestMapping("${server.error.path:${error.path:/error}}")
  @PreAuthorize("permitAll()")
  public ResponseEntity<ProblemDetail> handleError(@NotNull HttpServletRequest request) {
    HttpStatus status = resolveStatus(request);
    ProblemMapping mapping = mappingFor(status);
    Locale locale = LocaleContextHolder.getLocale();

    Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    String instanceUri = originalUri instanceof String uri && !uri.isBlank() ? uri : null;
    String correlationId = correlationId();
    ProblemDetail body =
        problemResponseFactory.problem(
            status,
            tr(mapping.titleKey(), locale),
            tr(mapping.detailKey(), locale),
            instanceUri,
            mapping.typeSuffix(),
            mapping.code(),
            correlationId);

    log.warn(
        "Container error dispatch [status={}, code={}, uri={}, correlationId={}]",
        status.value(),
        mapping.code(),
        originalUri,
        correlationId);

    return ResponseEntity.status(status)
        .header(CORRELATION_ID_HEADER, correlationId)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  /**
   * Reads the {@code jakarta.servlet.error.status_code} attribute the servlet container sets on the
   * error dispatch, falling back to {@code 500} when it is absent or not a resolvable status.
   *
   * @param request the error dispatch
   * @return the resolved HTTP status, never {@code null}
   */
  private static @NotNull HttpStatus resolveStatus(@NotNull HttpServletRequest request) {
    Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (raw instanceof Integer code) {
      HttpStatus resolved = HttpStatus.resolve(code);
      if (resolved != null) {
        return resolved;
      }
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  /**
   * Maps an HTTP status to the same {@code problem.*} keys, type suffix and stable code as {@code
   * GlobalExceptionHandler}; unmapped statuses collapse to {@code BAD_REQUEST} (4xx) or {@code
   * INTERNAL_ERROR} (5xx).
   *
   * @param status the resolved response status
   * @return the problem mapping for {@code status}
   */
  private static @NotNull ProblemMapping mappingFor(@NotNull HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST ->
          new ProblemMapping(
              "problem.bad_request.title",
              "problem.bad_request.detail",
              "bad-request",
              "BAD_REQUEST");
      case UNAUTHORIZED ->
          new ProblemMapping(
              "problem.unauthenticated.title",
              "problem.unauthenticated.detail",
              "unauthenticated",
              "UNAUTHENTICATED");
      case FORBIDDEN ->
          new ProblemMapping(
              "problem.access_denied.title",
              "problem.access_denied.detail",
              "access-denied",
              "ACCESS_DENIED");
      case NOT_FOUND ->
          new ProblemMapping(
              "problem.not_found.title", "problem.not_found.detail", "not-found", "NOT_FOUND");
      case METHOD_NOT_ALLOWED ->
          new ProblemMapping(
              "problem.method_not_allowed.title",
              "problem.method_not_allowed.detail",
              "method-not-allowed",
              "METHOD_NOT_ALLOWED");
      case SERVICE_UNAVAILABLE ->
          new ProblemMapping(
              "problem.service_unavailable.title",
              "problem.service_unavailable.detail",
              "service-unavailable",
              "SERVICE_UNAVAILABLE");
      default ->
          status.is5xxServerError()
              ? new ProblemMapping(
                  "problem.internal_error.title",
                  "problem.internal_error.detail",
                  "internal-error",
                  "INTERNAL_ERROR")
              : new ProblemMapping(
                  "problem.bad_request.title",
                  "problem.bad_request.detail",
                  "bad-request",
                  "BAD_REQUEST");
    };
  }

  /**
   * Resolves a localized message via {@link MessageSource}, returning the key itself when it is not
   * present in the bundle (so a missing translation is visible in QA rather than crashing).
   *
   * @param key the {@code problem.*} bundle key
   * @param locale the request locale from {@link LocaleContextHolder}
   * @return the localized message, or {@code key} if unresolved
   */
  private @NotNull String tr(@NotNull String key, @NotNull Locale locale) {
    return messageSource.getMessage(key, null, key, locale);
  }

  /**
   * Reuses the request-scoped correlation id from the SLF4J {@code MDC} when the correlation filter
   * has already run for this dispatch, otherwise mints a fresh UUID so the error is still
   * traceable.
   *
   * @return the correlation id to stamp on the body, header and log line
   */
  private static @NotNull String correlationId() {
    String existing = MDC.get(MDC_CORRELATION_ID);
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    return UUID.randomUUID().toString();
  }

  /**
   * The four wire artifacts a container error status maps to.
   *
   * @param titleKey the {@code problem.*.title} bundle key
   * @param detailKey the {@code problem.*.detail} bundle key
   * @param typeSuffix the suffix appended to {@link AppProblemProperties#getBaseUri()} for {@code
   *     type}
   * @param code the stable machine-readable error code echoed in the body
   */
  private record ProblemMapping(
      String titleKey, String detailKey, String typeSuffix, String code) {}
}
