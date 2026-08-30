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
 * Refuses the API to a caller who has not accepted the Terms of Use (REQ-SEC-028).
 *
 * <p>This is the boundary, not the frontend's redirect. It is enforced here because the backend is
 * the only place every caller passes through: the web UI, and — since {@link ActingMemberFilter}
 * runs ahead of this one and makes an ingest-gateway request carry the sending member's identity
 * (ADR-0129) — the desktop extractor too. The gateway does not relay that member's token: it
 * authenticates with its own service account and names the member in an on-behalf-of header, so it
 * is the identity substitution, not a relayed bearer, that puts a person in front of this gate. One
 * filter therefore covers both, and the gateway inherits the refusal without needing its own copy
 * of the rule.
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

  /** Parses the patterns below once; matching is per request and allocation-light. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /**
   * The surface this filter guards.
   *
   * <p>Matched as a parsed {@link PathPattern} rather than with {@code
   * requestURI.startsWith("/api/")}, because {@code getRequestURI()} is the <em>raw</em>
   * percent-encoded URI while Spring MVC routes on the <em>decoded</em> path. A request for {@code
   * /%61pi/v1/missions} fails a raw prefix test, so the gate would wave it through, and {@code
   * RequestMappingHandlerMapping} would then decode {@code %61pi} to {@code api} and dispatch it —
   * the consent record REQ-SEC-028 exists to produce would silently not be required. The default
   * {@code StrictHttpFirewall} blocks {@code %2e}, {@code %2f}, {@code %25} and friends, but not
   * {@code %61}.
   *
   * <p>{@link PathPattern} matches on {@code PathSegment#valueToMatch()}, which is decoded, so
   * filter and routing agree. Note that {@code ServletRequestPathUtils} does <em>not</em> solve
   * this: {@code PathContainer.Element#value()} is contractually the unmodified original.
   *
   * <p>Precedent: {@code filter.RateLimitingFilter} matches its configured paths the same way.
   */
  private static final PathPattern API_SCOPE = PATH_PARSER.parse("/api/**");

  /**
   * The only endpoints an unconsented caller may still reach. Patterns, not string prefixes: a bare
   * {@code startsWith("/api/v1/terms")} would also exempt a future {@code /api/v1/terms-export}.
   */
  private static final List<PathPattern> EXEMPT_PATHS =
      List.of(
          // The consent resource and its sub-resources — refusing these makes the block permanent
          // for everyone, because no request would be left that could record consent.
          PATH_PARSER.parse("/api/v1/terms"),
          PATH_PARSER.parse("/api/v1/terms/**"),
          // Lets a caller who is ALSO pending approval be routed to the waiting page instead.
          PATH_PARSER.parse("/api/v1/users/me/registration-status"));

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
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    if (!API_SCOPE.matches(path) || EXEMPT_PATHS.stream().anyMatch(p -> p.matches(path))) {
      return null;
    }
    // Asked of AuthenticatedSubject, not of the type — and this one failed OPEN. A request the
    // ingest gateway makes on behalf of a member carries no token (ADR-0129), so the old
    // `instanceof JwtAuthenticationToken` test found none and returned null, which here means "let
    // through". The consent gate silently stopped applying to the one path REQ-SEC-028 was extended
    // to cover. A type check that waves callers through is the worst kind to get wrong.
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    UUID userId = AuthenticatedSubject.idOf(auth).orElse(null);
    if (userId == null) {
      // No subject the seam recognises as a member id. NOT because a service account's `sub` looks
      // different - a Keycloak service-account subject IS a UUID - but because the seam resolves a
      // subject to a LOCAL member and a service account has no member row. Either way it is not a
      // person who can accept anything; the audience and scope checks govern those callers.
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
    // Counted as a distinct subject, not just as a request. See MetricNames.TERMS_REFUSED_SUBJECTS:
    // the refusal rate alone cannot separate a locked-out membership from one client retrying.
    refusedSubjects.record(userId);
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
