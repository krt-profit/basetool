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
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.TermsConsentCheck;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Refuses the API to a caller who has not accepted the Terms of Use (REQ-SEC-028).
 *
 * <p>This is the boundary, not the frontend's redirect. It is enforced here because the backend is
 * the only place every caller passes through: the web UI, and — since the ingest gateway relays the
 * caller's own bearer (REQ-INGEST-001) — the desktop extractor too. One filter therefore covers
 * both, and the gateway inherits the refusal without needing its own copy of the rule.
 *
 * <p>Mirrors {@link PendingApprovalAccessFilter} in shape: RFC 7807 body, stable {@code code},
 * minted correlation id, {@code basetool_http_error_total} increment, and the JWT {@code sub}
 * stamped into the MDC only for the duration of the rejection write.
 *
 * <p>Two exemptions and no more. The consent endpoints themselves, or there is no way through the
 * gate — refusing those would make the block permanent for everyone. And the registration-status
 * endpoint, so a user who is <em>also</em> pending approval still gets routed to the waiting page
 * rather than to a consent page for a tool they cannot enter yet.
 */
@Slf4j
@RequiredArgsConstructor
public class TermsAcceptanceAccessFilter extends OncePerRequestFilter {

  /** Stable machine-readable code the frontend and the extractor map to a consent prompt. */
  static final String CODE_TERMS_NOT_ACCEPTED = "TERMS_NOT_ACCEPTED";

  /** Consent endpoints, which must stay reachable or the gate has no exit. */
  static final String TERMS_PATH_PREFIX = "/api/v1/terms";

  /** Routing endpoint for a caller who is also pending approval. */
  static final String REGISTRATION_STATUS_PATH = "/api/v1/users/me/registration-status";

  /** App-wide correlation-id response header. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** MDC key the logback pattern renders as the user id. */
  static final String MDC_USER_ID = "userId";

  private final TermsConsentCheck termsConsentCheck;
  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

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
   * <p>Returns {@code null} — meaning "let through" — for anything that is not an authenticated
   * {@code /api} call, for the exempt endpoints, and for a {@code sub} that is not a UUID. That
   * last case is a service account or a malformed token, neither of which is a person who can
   * accept anything; refusing them here would be the wrong control in the wrong place, and they are
   * already governed by the audience and scope checks.
   *
   * @param request the current request
   * @return the blocked user's id, or {@code null} when the request may proceed
   */
  private UUID blockedUserId(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/")
        || path.startsWith(TERMS_PATH_PREFIX)
        || path.equals(REGISTRATION_STATUS_PATH)) {
      return null;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
      return null;
    }
    String subject = jwtAuth.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      return null;
    }
    UUID userId;
    try {
      userId = UUID.fromString(subject);
    } catch (IllegalArgumentException e) {
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
    boolean owned = stampUserId(userId);
    try {
      writeForbiddenBody(request, response);
    } finally {
      if (owned) {
        org.slf4j.MDC.remove(MDC_USER_ID);
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
    String existing = org.slf4j.MDC.get(MDC_USER_ID);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
    org.slf4j.MDC.put(MDC_USER_ID, userId.toString());
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
    // DEBUG, not WARN: after a terms change this fires once per request for everyone who has not
    // accepted yet, which is the feature working, not an incident. The metric below is the
    // monitoring signal.
    log.debug(
        "Consent missing; refused {} {} [correlationId={}]",
        request.getMethod(),
        request.getRequestURI(),
        correlationId);

    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE_TERMS_NOT_ACCEPTED)
        .increment();

    // Declared here rather than at the top of the method so each sits next to its use
    // (Checkstyle VariableDeclarationUsageDistance). LocaleContextHolder is not populated this
    // early in the filter chain, so the request's own Accept-Language is the authoritative source.
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
