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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemCode;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.Getter;
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
 *   <li>the decoded path matches one of the exchange endpoints (REQ-SEC-029, REQ-XCH-009);
 *   <li>the caller is authenticated with a {@link Jwt} — a header without one is refused;
 *   <li>the caller's {@code azp} is a configured gateway ({@link
 *       IngestGatewayProperties#isGatewayClient(String)}); an empty allowlist admits nobody;
 *   <li>the relay names a valid client and installation;
 *   <li>the named member is live.
 * </ol>
 *
 * <p>The exchange relay headers {@code X-Exchange-Client} and {@code X-Exchange-Capabilities} are
 * refused from anyone but the gateway acting for a member (REQ-XCH-010). The member holds the
 * reduced exchange authorities and the authentication carries the external client.
 */
@Slf4j
@RequiredArgsConstructor
public class ActingMemberFilter extends OncePerRequestFilter {

  /** Parses the patterns once; matching is per request and allocation-light. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /**
   * The only endpoints on which the gateway may act for a member, with the reduced exchange
   * authentication (REQ-XCH-009).
   *
   * <p>Deliberately the exhaustive list rather than a prefix: a prefix would silently widen the
   * boundary the moment a sibling endpoint is added under the same path.
   */
  static final List<String> ACTING_PATHS =
      List.of(
          "/api/v1/exchange/catalog/locations",
          "/api/v1/exchange/catalog/resolve",
          "/api/v1/exchange/me/account-check",
          "/api/v1/exchange/me/blueprints",
          "/api/v1/exchange/me/blueprints/changes",
          "/api/v1/exchange/me/drafts/blueprints",
          "/api/v1/exchange/me/drafts/refinery-orders",
          "/api/v1/exchange/me/installation",
          "/api/v1/exchange/me/org-demand",
          "/api/v1/exchange/me/ships",
          "/api/v1/exchange/me/ships/changes",
          "/api/v1/exchange/me/stock",
          "/api/v1/exchange/me/stock/changes");

  /** {@link #ACTING_PATHS} parsed once, matched on the decoded path. */
  private static final List<PathPattern> EXCHANGE_PATHS =
      ACTING_PATHS.stream().map(PATH_PARSER::parse).toList();

  /** The path prefix of the backend's exchange layer. */
  private static final String EXCHANGE_PREFIX = "/api/v1/exchange/";

  /** The shape of a registry client id, identical to the database check. */
  private static final Pattern EXCHANGE_CLIENT_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

  /** The shape of a DPoP key thumbprint: base64url SHA-256 without padding. */
  private static final Pattern KEY_THUMBPRINT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  /** The shape of a relayed connection time: an epoch second. */
  private static final Pattern EPOCH_SECOND = Pattern.compile("^[0-9]{1,12}$");

  /** App-wide correlation-id response header, matching the neighbouring person-gates. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /**
   * Stable machine-readable problem {@code code} for this filter's refusals, matching the shape of
   * the person-gates' refusals.
   */
  static final String CODE_ACTING_MEMBER_REFUSED = ExchangeProblemCode.ACTING_MEMBER_REFUSED.code();

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
    String exchangeClient = request.getHeader(ActingMemberHeader.EXCHANGE_CLIENT_HEADER);
    String exchangeCapabilities =
        request.getHeader(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER);
    String installationKey = request.getHeader(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER);
    String connectedAt = request.getHeader(ActingMemberHeader.EXCHANGE_CONNECTED_AT_HEADER);
    boolean exchangeHeaders =
        exchangeClient != null
            || exchangeCapabilities != null
            || installationKey != null
            || connectedAt != null;
    if (isAbsent(onBehalfOf)) {
      if (exchangeHeaders) {
        refuse(
            request,
            response,
            "exchange relay header without an acting member",
            MetricNames.ON_BEHALF_OF_FORGED_EXCHANGE_HEADER);
        return;
      }
      filterChain.doFilter(request, response);
      return;
    }

    if (!matches(request, EXCHANGE_PATHS)) {
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
    RelayRefusal relayRefusal = relayRefusal(exchangeClient, installationKey);
    if (relayRefusal != null) {
      refuse(request, response, relayRefusal.getDetail(), relayRefusal.getMetricReason());
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
      authorities =
          actingMemberAuthorities.exchangeAuthoritiesFor(member, knownScopes(exchangeCapabilities));
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
      acting.setAuthentication(
          new ActingMemberAuthentication(
              member, authorities, exchangeClient, installationKey, epochSecond(connectedAt)));
      SecurityContextHolder.setContext(acting);
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.setContext(original);
    }
  }

  /**
   * Checks the relay headers every exchange call must carry, in the order the refusals are counted:
   * a well-formed client first, then a well-formed installation key.
   *
   * @param exchangeClient the {@code X-Exchange-Client} value, or {@code null}
   * @param installationKey the {@code X-Exchange-Installation} value, or {@code null}
   * @return the first refusal, or {@code null} when both headers are well-formed
   */
  private static @Nullable RelayRefusal relayRefusal(
      @Nullable String exchangeClient, @Nullable String installationKey) {
    if (exchangeClient == null || !EXCHANGE_CLIENT_ID.matcher(exchangeClient).matches()) {
      return RelayRefusal.CLIENT_INVALID;
    }
    if (installationKey == null || !KEY_THUMBPRINT.matcher(installationKey).matches()) {
      return RelayRefusal.INSTALLATION_INVALID;
    }
    return null;
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
   * Whether this request targets one of the given endpoints.
   *
   * @param request the current request
   * @param patterns the endpoints to match against
   * @return {@code true} when the decoded path matches one of the patterns
   */
  private static boolean matches(
      @NotNull HttpServletRequest request, @NotNull List<PathPattern> patterns) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    return patterns.stream().anyMatch(pattern -> pattern.matches(path));
  }

  /**
   * Parses the relayed connection time; anything but a plain epoch second counts as absent, which
   * the exchange gate treats as a connection made before any disconnect.
   *
   * @param header the {@code X-Exchange-Connected-At} value, or {@code null}
   * @return the epoch second, or {@code null}
   */
  private static @Nullable Long epochSecond(@Nullable String header) {
    if (header == null || !EPOCH_SECOND.matcher(header).matches()) {
      return null;
    }
    return Long.parseLong(header);
  }

  /**
   * Whether the request targets the backend's exchange layer, listed route or not, so its refusal
   * speaks of an exchange request rather than an import.
   *
   * @param request the current request
   * @return {@code true} when the path lies under {@value #EXCHANGE_PREFIX}
   */
  private static boolean isExchangeRoute(@NotNull HttpServletRequest request) {
    return request
        .getRequestURI()
        .substring(request.getContextPath().length())
        .startsWith(EXCHANGE_PREFIX);
  }

  /**
   * Parses the relayed capabilities, keeping only the scopes of known capabilities.
   *
   * @param header the comma-separated {@code X-Exchange-Capabilities} value, or {@code null}
   * @return the known scopes, possibly empty
   */
  private static @NotNull List<String> knownScopes(@Nullable String header) {
    if (header == null || header.isBlank()) {
      return List.of();
    }
    return Arrays.stream(header.split(","))
        .map(String::strip)
        .filter(scope -> ExchangeCapability.fromScope(scope).isPresent())
        .distinct()
        .toList();
  }

  /**
   * Writes the refusal as an RFC 7807 403 problem document instead of throwing, because this filter
   * runs before exception translation.
   *
   * <p>Every reason produces the same body, worded for an exchange request on the exchange layer
   * and, elsewhere, as the rule that acting for a member is only possible there; the reason goes
   * only to the metric and the log, so the endpoint cannot reveal which subjects exist.
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
    boolean exchangeRoute = isExchangeRoute(request);
    String title =
        messageSource.getMessage(
            exchangeRoute
                ? "problem.acting_member_refused.exchange_title"
                : "problem.acting_member_refused.title",
            null,
            "Forbidden",
            locale);
    String message =
        messageSource.getMessage(
            exchangeRoute
                ? "problem.acting_member_refused.exchange_detail"
                : "problem.acting_member_refused.detail",
            null,
            exchangeRoute
                ? "The exchange request could not be attributed to a valid member and application."
                : "Acting for a member is only possible through the exchange routes.",
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
   * A refusal of a malformed exchange relay header: the developer-facing detail and the bounded
   * metric reason.
   */
  @Getter
  @RequiredArgsConstructor
  private enum RelayRefusal {
    /** The client header is missing or not a registry client id. */
    CLIENT_INVALID(
        "exchange request without a valid client",
        MetricNames.ON_BEHALF_OF_EXCHANGE_CLIENT_INVALID),

    /** The installation header is missing or not a DPoP key thumbprint. */
    INSTALLATION_INVALID(
        "exchange request without a valid installation key",
        MetricNames.ON_BEHALF_OF_EXCHANGE_INSTALLATION_INVALID);

    /** The developer-facing reason, free of caller-supplied text. */
    private final String detail;

    /** The bounded {@code MetricNames.ON_BEHALF_OF_*} reason. */
    private final String metricReason;
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
    private final @Nullable String externalClient;
    private final @Nullable String installationKey;
    private final @Nullable Long connectedAt;

    /**
     * Creates the authentication.
     *
     * @param member the acting member's subject
     * @param authorities the authorities assembled for that member
     * @param externalClient the external client of an exchange request, or {@code null}
     * @param installationKey the installation's key thumbprint of an exchange request, or {@code
     *     null}
     * @param connectedAt the connection time the gateway compared for an exchange request, in epoch
     *     seconds, or {@code null}
     */
    ActingMemberAuthentication(
        UUID member,
        Collection<GrantedAuthority> authorities,
        @Nullable String externalClient,
        @Nullable String installationKey,
        @Nullable Long connectedAt) {
      super(authorities);
      this.member = member;
      this.externalClient = externalClient;
      this.installationKey = installationKey;
      this.connectedAt = connectedAt;
      setAuthenticated(true);
    }

    @Override
    public @Nullable String exchangeInstallationKey() {
      return installationKey;
    }

    @Override
    public @Nullable Long exchangeConnectedAt() {
      return connectedAt;
    }

    @Override
    public @Nullable String externalClient() {
      return externalClient;
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
