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
import de.greluc.krt.profit.basetool.backend.support.ActingSubjectResolver;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
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

/**
 * Makes the <em>acting member</em> the security identity of an ingest-gateway request (ADR-0129).
 *
 * <p>The gateway calls the backend under its own service-account identity and names the member it
 * acts for. The first cut substituted that member at two call sites, for the {@code owner}
 * parameter only — which fixed attribution and nothing else. Everything that reads the {@link
 * SecurityContext} still saw the gateway: {@code @PreAuthorize}, {@code @CurrentUserId}, the
 * org-unit scope, the audit trail, and both person-gates. The gates in particular are the reason
 * this filter exists rather than a wider {@code ActingSubjectResolver}: consent (REQ-SEC-028) and
 * approval (REQ-SEC-017) must be evaluated against the person who is sending, exactly as they were
 * while the gateway still relayed that person's token.
 *
 * <p>So the context is replaced instead. Everything downstream then sees the member without knowing
 * this filter exists, and the per-call-site substitution is gone.
 *
 * <p><strong>Four guards, each closing a way this could go wrong.</strong>
 *
 * <ol>
 *   <li><b>Only a configured gateway.</b> Keyed on {@code azp} through {@link
 *       IngestGatewayProperties#isGatewayClient(String)} — the same rule the resolver uses, so the
 *       two cannot drift. An empty allowlist admits nobody.
 *   <li><b>Only the two ingest endpoints.</b> Matched as parsed {@link PathPattern}s against the
 *       <em>decoded</em> path (REQ-SEC-029): {@code getRequestURI()} is percent-encoded while MVC
 *       routes on the decoded path, so a {@code startsWith} test would let {@code /%61pi/...}
 *       through. ADR-0129 bounds the header to these two endpoints, and this is where that bound is
 *       enforced rather than assumed.
 *   <li><b>Fail closed on a header with no authenticated caller.</b> A present header without a
 *       {@link Jwt} is refused, not ignored — so a future change to the authentication filters
 *       cannot silently reproduce the ordering bug this filter was written after.
 *   <li><b>Liveness.</b> See {@link #actingAuthorities}.
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

  private final IngestGatewayProperties gatewayProperties;
  private final ActingMemberAuthorities actingMemberAuthorities;
  private final MeterRegistry meterRegistry;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    String onBehalfOf = request.getHeader(ActingSubjectResolver.ON_BEHALF_OF_HEADER);
    if (onBehalfOf == null || onBehalfOf.isBlank()) {
      filterChain.doFilter(request, response);
      return;
    }

    Authentication caller = SecurityContextHolder.getContext().getAuthentication();
    if (!(caller instanceof JwtAuthenticationToken jwtCaller)) {
      // Guard 3. Never "ignore and continue": that is precisely how the first version failed —
      // filters running before authentication saw an empty context and silently skipped their
      // check.
      refuse(response, "an on-behalf-of header requires an authenticated caller", null);
      return;
    }
    if (!gatewayProperties.isGatewayClient(jwtCaller.getToken().getClaimAsString("azp"))) {
      refuse(
          response, "caller is not a configured gateway", MetricNames.ON_BEHALF_OF_NOT_A_GATEWAY);
      return;
    }
    if (!matchesActingPath(request)) {
      refuse(response, "endpoint does not accept an on-behalf-of header", null);
      return;
    }

    UUID member;
    try {
      member = UUID.fromString(onBehalfOf);
    } catch (IllegalArgumentException malformed) {
      refuse(response, "named subject is not a UUID", MetricNames.ON_BEHALF_OF_MALFORMED);
      return;
    }

    Collection<GrantedAuthority> authorities;
    try {
      authorities = actingMemberAuthorities.authoritiesFor(member);
    } catch (AccessDeniedException notLive) {
      // Unknown here, or no longer in the identity provider. Both fail closed; the distinction is
      // in
      // that class's log line, not in the answer, which must not tell a caller which subjects
      // exist.
      refuse(response, "named member is not usable", null);
      return;
    }

    SecurityContext original = SecurityContextHolder.getContext();
    try {
      SecurityContext acting = SecurityContextHolder.createEmptyContext();
      acting.setAuthentication(new ActingMemberAuthentication(member, authorities));
      SecurityContextHolder.setContext(acting);
      filterChain.doFilter(request, response);
    } finally {
      // Restored rather than cleared: this thread is pooled, and leaving the acting member behind
      // would attribute the NEXT request on it to them.
      SecurityContextHolder.setContext(original);
    }
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
   * Answers the refusal directly, rather than throwing.
   *
   * <p><strong>Not an {@link AccessDeniedException}.</strong> This filter runs immediately after
   * the bearer-token filter, which is <em>before</em> {@code ExceptionTranslationFilter} — so a
   * thrown {@code AccessDeniedException} escapes the chain untranslated and reaches the client as a
   * 500 instead of a 403. That was not theory: the real-chain test caught it, where a filter tested
   * in isolation would have passed. The neighbouring person-gates write their own bodies for the
   * same reason.
   *
   * @param response the response to write into
   * @param detail the developer-facing reason; deliberately terse and free of caller-supplied text
   * @param metricReason the bounded {@code MetricNames.ON_BEHALF_OF_*} reason, or {@code null} when
   *     this refusal is not one of the two counted security signals
   * @throws IOException if writing fails
   */
  private void refuse(@NotNull HttpServletResponse response, String detail, String metricReason)
      throws IOException {
    log.warn("Refused an on-behalf-of request: {}", detail);
    if (metricReason != null) {
      meterRegistry
          .counter(MetricNames.ON_BEHALF_OF_REFUSED, MetricNames.TAG_REASON, metricReason)
          .increment();
    }
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write("{\"status\":403,\"code\":\"ACTING_MEMBER_REFUSED\"}");
    response.getWriter().flush();
  }

  /**
   * The authentication that stands in for the acting member.
   *
   * <p>Not a {@link JwtAuthenticationToken}: there is no token for this member, and manufacturing
   * one would put a forged {@link Jwt} into a context where anything may read its claims. Carrying
   * the subject as the principal keeps {@code getName()} equal to the {@code sub}, which is what
   * every identity consumer in this application actually reads.
   */
  static final class ActingMemberAuthentication extends AbstractAuthenticationToken {

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
  }
}
