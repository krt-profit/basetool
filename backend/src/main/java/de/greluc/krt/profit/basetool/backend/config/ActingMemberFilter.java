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
 * Makes the <em>acting member</em> the security identity of an ingest-gateway request (ADR-0129).
 *
 * <p>The gateway calls the backend under its own service-account identity and names the member it
 * acts for. The first cut substituted that member at two call sites, for the {@code owner}
 * parameter only — which fixed attribution and nothing else. Everything that reads the {@link
 * SecurityContext} still saw the gateway: {@code @PreAuthorize}, {@code @CurrentUserId}, the
 * org-unit scope, the audit trail, and both person-gates. The gates in particular are the reason
 * this filter exists rather than a per-call-site resolver: consent (REQ-SEC-028) and approval
 * (REQ-SEC-017) must be evaluated against the person who is sending, exactly as they were while the
 * gateway still relayed that person's token.
 *
 * <p>So the context is replaced instead, and the per-call-site substitution is gone.
 *
 * <p><strong>That only works because identity is read through one seam.</strong> The claim
 * "everything downstream sees the member" was false when this filter first shipped: a large share
 * of the consumers branched on the authentication <em>type</em> rather than on the subject, and the
 * acting member — which deliberately carries no token — split them into two camps. {@code
 * CurrentUserArgumentResolver} failed closed and 403'd every gateway call at argument resolution;
 * {@code TermsAcceptanceAccessFilter} failed <em>open</em> and skipped the consent gate entirely.
 * Every consumer now asks {@link
 * de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject}, and {@code ArchitectureTest
 * #identityMustBeReadThroughTheSeamNotTheAuthenticationType} keeps it that way, so the next
 * authentication type cannot reopen the same split.
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

  /** App-wide correlation-id response header, matching the neighbouring person-gates. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** Stable machine-readable code; the frontend and the ingest module branch on it. */
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
    if (onBehalfOf == null || onBehalfOf.isBlank()) {
      filterChain.doFilter(request, response);
      return;
    }

    Authentication caller = SecurityContextHolder.getContext().getAuthentication();
    if (!(caller instanceof JwtAuthenticationToken jwtCaller)) {
      // Guard 3. Never "ignore and continue": that is precisely how the first version failed —
      // filters running before authentication saw an empty context and silently skipped their
      // check.
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
    if (!matchesActingPath(request)) {
      refuse(
          request,
          response,
          "endpoint does not accept an on-behalf-of header",
          MetricNames.ON_BEHALF_OF_ENDPOINT_NOT_BOUND);
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
      // Unknown here, or no longer in the identity provider. Both fail closed, and the answer is
      // byte-identical to every other refusal so it cannot be used to enumerate subjects. The
      // distinction lives in that class's log line and in this counter -- see
      // MetricNames.ON_BEHALF_OF_MEMBER_NOT_LIVE for why this one is worth alerting on.
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
   * in isolation would have passed.
   *
   * <p>The neighbouring person-gates write their own bodies for the same reason, and this writes
   * the same shape they do — a full RFC 7807 document with a stable {@code code}, a localized title
   * and detail, and a correlation id in both the body and the header. A client cannot tell the
   * three refusing filters apart by the shape of the answer, and the ingest module's error mapping
   * does not need a special case for this one.
   *
   * <p><strong>Every reason produces a byte-identical body.</strong> The reason is carried by the
   * metric and the log line, never by the response: an unknown member and an offboarded one must
   * look the same from outside, or this endpoint becomes an oracle for which subjects exist.
   *
   * @param request the refused request, for the problem {@code instance} and the caller's locale
   * @param response the response to write into
   * @param detail the developer-facing reason; deliberately terse and free of caller-supplied text
   * @param metricReason the bounded {@code MetricNames.ON_BEHALF_OF_*} reason for this refusal
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

    // LocaleContextHolder is not populated this early in the filter chain, so the request's own
    // Accept-Language is the authoritative source -- same reasoning as the neighbouring gates.
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
   * The authentication that stands in for the acting member.
   *
   * <p>Not a {@link JwtAuthenticationToken}: there is no token for this member, and manufacturing
   * one would put a forged {@link Jwt} into a context where anything may read its claims.
   *
   * <p>It advertises the subject through {@link SubjectAuthentication} rather than relying on
   * {@code getName()}. The seam cannot read names generically — for a username/password caller the
   * name is a callsign, which REQ-OBS-004 keeps out of logs — so this type states explicitly that
   * its name is an OIDC {@code sub}.
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
