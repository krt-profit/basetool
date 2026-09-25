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
import de.greluc.krt.profit.basetool.backend.support.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.SubjectAuthentication;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes the <em>acting member</em> named by the ingest gateway the security identity of the request
 * (ADR-0129), so authorization, scoping, audit and the consent and approval gates all evaluate that
 * member.
 *
 * <p>The header is honoured only when all of these hold:
 *
 * <ol>
 *   <li>the decoded path matches one of the two ingest endpoints (REQ-SEC-029);
 *   <li>the caller is authenticated with a {@link Jwt} — a header without one is refused;
 *   <li>the caller's {@code azp} is a configured gateway ({@link
 *       IngestGatewayProperties#isGatewayClient(String)}); an empty allowlist admits nobody;
 *   <li>the named member is live (see {@link #actingAuthorities}).
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class ActingMemberFilter extends OncePerRequestFilter {

  /** Parses the patterns once; matching is per request and allocation-light. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /**
   * The only endpoints on which a caller may act for someone else.
   *
   * <p>Deliberately the exhaustive list rather than a prefix: a prefix would silently widen the
   * boundary the moment a sibling endpoint is added under the same path.
   */
  private static final List<PathPattern> ACTING_PATHS =
      List.of(
          PATH_PARSER.parse("/api/v1/refinery-orders/import-extract"),
          PATH_PARSER.parse("/api/v1/personal-blueprints/import/preview"));

  /** App-wide correlation-id response header, matching the neighbouring person-gates. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /**
   * Stable machine-readable problem {@code code} for this filter's refusals, matching the shape of
   * the person-gates' refusals.
   */
  static final String CODE_ACTING_MEMBER_REFUSED = "ACTING_MEMBER_REFUSED";

  private final IngestGatewayProperties gatewayProperties;
  private final ActingMemberAuthorities actingMemberAuthorities;
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
    String onBehalfOf = request.getHeader(ActingMemberHeader.ON_BEHALF_OF_HEADER);
    if (isAbsent(onBehalfOf)) {
      filterChain.doFilter(request, response);
      return;
    }

    if (!matchesActingPath(request)) {
      refuse(
          request,
          response,
          "endpoint does not accept an on-behalf-of header",
          MetricNames.ON_BEHALF_OF_ENDPOINT_NOT_BOUND);
      return;
    }

    Authentication caller = SecurityContextHolder.getContext().getAuthentication();
    if (!(caller instanceof JwtAuthenticationToken jwtCaller)) {
      refuse(
          request,
          response,
          "an on-behalf-of header requires an authenticated caller",
          MetricNames.ON_BEHALF_OF_NO_CALLER);
      return;
    }
    if (!gatewayProperties.isGatewayClient(jwtCaller.getToken().getClaimAsString("azp"))) {
      refuse(
          request,
          response,
          "caller is not a configured gateway",
          MetricNames.ON_BEHALF_OF_NOT_A_GATEWAY);
      return;
    }

    UUID member;
    try {
      member = UUID.fromString(onBehalfOf);
    } catch (IllegalArgumentException malformed) {
      refuse(request, response, "named subject is not a UUID", MetricNames.ON_BEHALF_OF_MALFORMED);
      return;
    }

    Collection<GrantedAuthority> authorities;
    try {
      authorities = actingMemberAuthorities.authoritiesFor(member);
    } catch (AccessDeniedException notLive) {
      refuse(
          request,
          response,
          "named member is not usable",
          MetricNames.ON_BEHALF_OF_MEMBER_NOT_LIVE);
      return;
    }

    SecurityContext original = SecurityContextHolder.getContext();
    try {
      SecurityContext acting = SecurityContextHolder.createEmptyContext();
      acting.setAuthentication(new ActingMemberAuthentication(member, authorities));
      SecurityContextHolder.setContext(acting);
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.setContext(original);
    }
  }

  /**
   * Whether the request names no acting member; a blank value counts as absent.
   *
   * @param onBehalfOf the raw on-behalf-of header value, {@code null} when missing
   * @return {@code true} when the header is missing, empty or whitespace only
   */
  private static boolean isAbsent(@Nullable String onBehalfOf) {
    return onBehalfOf == null || onBehalfOf.isBlank();
  }

  /**
   * Whether this request targets one of the two endpoints that accept an acting member.
   *
   * @param request the current request
   * @return {@code true} when the decoded path matches one of {@link #ACTING_PATHS}
   */
  private static boolean matchesActingPath(@NotNull HttpServletRequest request) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    return ACTING_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
  }

  /**
   * Writes the refusal as an RFC 7807 403 problem document instead of throwing, because this filter
   * runs before exception translation.
   *
   * <p>Every reason produces a byte-identical body; the reason goes only to the metric and the log,
   * so the endpoint cannot reveal which subjects exist.
   *
   * @param request the refused request, for the problem {@code instance} and the locale
   * @param response the response to write into
   * @param detail the developer-facing reason, free of caller-supplied text
   * @param metricReason the bounded {@code MetricNames.ON_BEHALF_OF_*} refusal reason
   * @throws IOException if serialization or writing fails
   */
  private void refuse(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      String detail,
      @NotNull String metricReason)
      throws IOException {
    String correlationId = UUID.randomUUID().toString();
    log.warn("Refused an on-behalf-of request: {} [correlationId={}]", detail, correlationId);
    meterRegistry
        .counter(MetricNames.ON_BEHALF_OF_REFUSED, MetricNames.TAG_REASON, metricReason)
        .increment();

    Locale locale = request.getLocale();
    String title =
        messageSource.getMessage("problem.acting_member_refused.title", null, "Forbidden", locale);
    String message =
        messageSource.getMessage(
            "problem.acting_member_refused.detail",
            null,
            "The import could not be attributed to a valid member.",
            locale);

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.FORBIDDEN,
            title,
            message,
            request.getRequestURI(),
            "acting-member-refused",
            CODE_ACTING_MEMBER_REFUSED,
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }

  /**
   * Token-less authentication standing in for the acting member, exposing its OIDC {@code sub}
   * through {@link SubjectAuthentication}.
   *
   * <p>Deliberately not a {@link JwtAuthenticationToken}, so no forged {@link Jwt} enters the
   * context.
   */
  static final class ActingMemberAuthentication extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final UUID member;

    /**
     * Creates the authentication.
     *
     * @param member the acting member's subject
     * @param authorities the authorities assembled for that member
     */
    ActingMemberAuthentication(UUID member, Collection<GrantedAuthority> authorities) {
      super(authorities);
      this.member = member;
      setAuthenticated(true);
    }

    @NotNull
    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return member.toString();
    }

    @Override
    public String getName() {
      return member.toString();
    }

    @Override
    public @NotNull String subject() {
      return member.toString();
    }
  }
}
