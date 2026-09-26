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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.RefusedSubjectWindow;
import de.greluc.krt.profit.basetool.backend.support.TermsConsentCheck;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Refuses the API with an RFC 7807 403 to an authenticated caller who has not accepted the Terms of
 * Use (REQ-SEC-028).
 *
 * <p>Runs after {@link ActingMemberFilter}, so ingest-gateway requests are judged as the acting
 * member. Exempt are the consent endpoints, the registration-status endpoint and {@code
 * /api/v1/app/version-policy}. Shaped like {@link PendingApprovalAccessFilter}.
 */
@Slf4j
@RequiredArgsConstructor
public class TermsAcceptanceAccessFilter extends OncePerRequestFilter {

  /** Stable machine-readable code the frontend and the extractor map to a consent prompt. */
  static final String CODE_TERMS_NOT_ACCEPTED = "TERMS_NOT_ACCEPTED";

  /** The shared parser for this filter's path patterns. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /**
   * The guarded surface, {@code /api/**}.
   *
   * <p>Matched as a {@link PathPattern} on the decoded path, so a percent-encoded request such as
   * {@code /%61pi/...} cannot bypass the gate while still being routed.
   */
  private static final PathPattern API_SCOPE = PATH_PARSER.parse("/api/**");

  /**
   * The only endpoints an unconsented caller may still reach. Patterns, not string prefixes: a bare
   * {@code startsWith("/api/v1/terms")} would also exempt a future {@code /api/v1/terms-export}.
   */
  private static final List<PathPattern> EXEMPT_PATHS =
      List.of(
          PATH_PARSER.parse("/api/v1/terms"),
          PATH_PARSER.parse("/api/v1/terms/**"),
          PATH_PARSER.parse("/api/v1/users/me/registration-status"),
          PATH_PARSER.parse("/api/v1/app/version-policy"));

  /** App-wide correlation-id response header. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** MDC key the logback pattern renders as the user id. */
  static final String MDC_USER_ID = "userId";

  private final TermsConsentCheck termsConsentCheck;
  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final RefusedSubjectWindow refusedSubjects;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    UUID userId = blockedUserId(request);
    if (userId != null) {
      writeForbidden(request, response, userId);
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Resolves the caller and decides whether they must be refused.
   *
   * <p>Lets through non-{@code /api} and unauthenticated requests, the exempt endpoints, and a
   * {@code sub} that is not a UUID (a service account or malformed token).
   *
   * @param request the current request
   * @return the blocked user's id, or {@code null} when the request may proceed
   */
  @Nullable
  private UUID blockedUserId(@NotNull HttpServletRequest request) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    if (!API_SCOPE.matches(path) || EXEMPT_PATHS.stream().anyMatch(p -> p.matches(path))) {
      return null;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    UUID userId = AuthenticatedSubject.idOf(auth).orElse(null);
    if (userId == null) {
      return null;
    }
    return termsConsentCheck.hasAcceptedCurrentTerms(userId) ? null : userId;
  }

  /**
   * Writes the RFC 7807 403 with the {@code userId} MDC key owned for the duration of the write.
   *
   * @param request the refused request
   * @param response the response to write into
   * @param userId the refused caller, stamped into the MDC so the line names them
   * @throws IOException if serialization or writing fails
   */
  private void writeForbidden(HttpServletRequest request, HttpServletResponse response, UUID userId)
      throws IOException {
    refusedSubjects.record(userId);
    boolean owned = stampUserId(userId);
    try {
      writeForbiddenBody(request, response);
    } finally {
      if (owned) {
        MDC.remove(MDC_USER_ID);
      }
    }
  }

  /**
   * Puts the caller's id into the MDC unless something already owns that key.
   *
   * @param userId the refused caller
   * @return {@code true} when this call stamped the key and must remove it again
   */
  private static boolean stampUserId(UUID userId) {
    String existing = MDC.get(MDC_USER_ID);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
    MDC.put(MDC_USER_ID, userId.toString());
    return true;
  }

  /**
   * Builds and writes the problem document, logs the refusal and counts it.
   *
   * @param request the refused request
   * @param response the response to write into
   * @throws IOException if serialization or writing fails
   */
  private void writeForbiddenBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String correlationId = UUID.randomUUID().toString();
    log.debug(
        "Consent missing; refused {} {} [correlationId={}]",
        request.getMethod(),
        request.getRequestURI(),
        correlationId);

    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE_TERMS_NOT_ACCEPTED)
        .increment();

    Locale locale = request.getLocale();
    String title =
        messageSource.getMessage("problem.terms_not_accepted.title", null, "Forbidden", locale);
    String detail =
        messageSource.getMessage(
            "problem.terms_not_accepted.detail",
            null,
            "The Terms of Use must be accepted before using the platform.",
            locale);

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.FORBIDDEN,
            title,
            detail,
            request.getRequestURI(),
            "terms-not-accepted",
            CODE_TERMS_NOT_ACCEPTED,
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }
}
