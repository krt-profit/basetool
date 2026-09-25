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

package de.greluc.krt.profit.basetool.ingest.filter;

import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces which client software may call the ingest endpoints (REQ-INGEST-011) by checking the
 * token's {@code azp} against the allowlist and the configured ingest scope; the user gate stays
 * {@code isAuthenticated()}.
 *
 * <p>Runs inside the security chain after authentication and {@link UserIdMdcFilter}. Segments
 * registered clients from one another; it is not anti-tamper.
 */
@Slf4j
@RequiredArgsConstructor
public class ClientIdentityFilter extends OncePerRequestFilter {

  /** JWT confirmation claim (RFC 7800); its {@code jkt} member carries the DPoP key thumbprint. */
  static final String CONFIRMATION_CLAIM = "cnf";

  /** Member of {@link #CONFIRMATION_CLAIM} holding the JWK SHA-256 thumbprint (RFC 9449 §6). */
  static final String THUMBPRINT_MEMBER = "jkt";

  /** Authorized-party claim: the Keycloak client id a token was issued to (OIDC Core §2). */
  static final String AUTHORIZED_PARTY_CLAIM = "azp";

  /** Prefix Spring Security gives authorities derived from the {@code scope} claim. */
  static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

  /** Cap on a logged client id, so an unexpected {@code azp} cannot bloat the line. */
  private static final int MAX_LOGGED_CLIENT_ID = 80;

  /** Cap on a logged request path. */
  private static final int MAX_LOGGED_PATH = 256;

  /** The configured allowlists and the audit-only switch. */
  private final ClientIdentityProperties properties;

  /** Counts accepted clients and every rejection under a bounded reason. */
  private final MeterRegistry meterRegistry;

  /** Serializes the 403 problem body. */
  private final ObjectMapper objectMapper;

  /** Supplies the MDC key the problem body's {@code correlationId} is read from. */
  private final LoggingProperties loggingProperties;

  /**
   * Evaluates the configured client-identity checks and either rejects the request with a {@code
   * 403} problem or lets it through, counting the outcome either way.
   *
   * @param request the current ingest request
   * @param response the response, written to only on a rejection
   * @param filterChain the remaining chain
   * @throws ServletException propagated from the chain
   * @throws IOException propagated from the chain or from writing the problem body
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!isAuthenticated(authentication)) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
      refuseNonJwtPrincipal(request, response, authentication);
      return;
    }
    Jwt jwt = jwtAuthentication.getToken();
    String authorizedParty = claimText(jwt, AUTHORIZED_PARTY_CLAIM);
    String clientLabel = boundedClientLabel(authorizedParty);
    Rejection rejection = evaluate(request, authorizedParty);

    if (rejection != null) {
      meterRegistry
          .counter(MetricNames.INGEST_CLIENT_REJECTED, MetricNames.TAG_REASON, rejection.reason())
          .increment();
      if (!properties.auditOnly()) {
        log.warn(
            "Ingest client rejected: reason={}, clientId={}, path={} {}",
            rejection.reason(),
            LogSafe.text(authorizedParty, MAX_LOGGED_CLIENT_ID),
            request.getMethod(),
            request.getRequestURI());
        meterRegistry
            .counter(
                MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, MetricNames.CODE_CLIENT_NOT_ALLOWED)
            .increment();
        ProblemResponseWriter.write(
            response,
            objectMapper,
            loggingProperties,
            HttpStatus.FORBIDDEN,
            "Client not allowed",
            MetricNames.CODE_CLIENT_NOT_ALLOWED,
            rejection.detail());
        return;
      }
      log.warn(
          "Ingest client would be rejected (audit-only): reason={}, clientId={}, path={} {}",
          rejection.reason(),
          LogSafe.text(authorizedParty, MAX_LOGGED_CLIENT_ID),
          request.getMethod(),
          request.getRequestURI());
    }

    warnOnUnboundAccessToken(request, jwt);
    meterRegistry
        .counter(MetricNames.INGEST_CLIENT, MetricNames.TAG_CLIENT_ID, clientLabel)
        .increment();
    filterChain.doFilter(request, response);
  }

  /**
   * Runs the configured checks, client identity before scope, and returns the first failure.
   *
   * @param request the current request, inspected for the {@code Authorization} scheme
   * @param authorizedParty the token's {@code azp} claim, or {@code null} when absent
   * @return the first failed check, or {@code null} when every configured check passed
   */
  private @Nullable Rejection evaluate(
      @NotNull HttpServletRequest request, @Nullable String authorizedParty) {
    if (!properties.allowedClientIds().isEmpty()) {
      if (authorizedParty == null) {
        return new Rejection(
            MetricNames.REASON_MISSING_AZP, notApprovedDetail("no client identity in the token"));
      }
      if (!properties.allowedClientIds().contains(authorizedParty)) {
        return new Rejection(
            MetricNames.REASON_UNKNOWN_CLIENT, notApprovedDetail("client identity"));
      }
    }
    if (!properties.requiredScope().isBlank() && !hasRequiredScope()) {
      return new Rejection(
          MetricNames.REASON_MISSING_SCOPE, notApprovedDetail("missing ingest scope"));
    }
    return null;
  }

  /**
   * Builds the user-facing detail for a client-identity rejection, naming the category of the check
   * that refused without disclosing configured values.
   *
   * @param failedCheck short, non-sensitive name of the check that refused the caller
   * @return the problem detail for a non-approved client
   */
  private static @NotNull String notApprovedDetail(@NotNull String failedCheck) {
    return "This client is not approved for the basetool ingest path ("
        + failedCheck
        + "). Only the official basetool SC extractor is supported; other tools are not permitted.";
  }

  /**
   * Logs a warning when an access token arrives without the RFC 7800 {@code cnf.jkt} confirmation,
   * i.e. not sender-constrained (REQ-INGEST-012).
   *
   * <p>Logs only; the request is not rejected.
   *
   * @param request the current request
   * @param jwt the authenticated caller's token
   */
  private void warnOnUnboundAccessToken(@NotNull HttpServletRequest request, @NotNull Jwt jwt) {
    if (isSenderConstrained(jwt)) {
      return;
    }
    log.warn(
        "Access token is NOT DPoP-bound (no cnf.jkt) — the sender-constraining REQ-INGEST-012"
            + " requires has lapsed. Check that the realm still binds on a presented proof and that"
            + " no client policy restricts binding to the refresh token: path={} {}",
        request.getMethod(),
        request.getRequestURI());
  }

  /**
   * Reports whether the token carries the RFC 7800 {@code cnf.jkt} confirmation thumbprint, i.e.
   * was issued sender-constrained to a DPoP key.
   *
   * @param jwt the authenticated caller's token
   * @return {@code true} when a non-blank {@code cnf.jkt} is present
   */
  private static boolean isSenderConstrained(@NotNull Jwt jwt) {
    Map<String, Object> confirmation = jwt.getClaimAsMap(CONFIRMATION_CLAIM);
    if (confirmation == null) {
      return false;
    }
    return confirmation.get(THUMBPRINT_MEMBER) instanceof String thumbprint
        && !thumbprint.isBlank();
  }

  /**
   * Reports whether the current authentication carries the configured ingest scope, as the {@code
   * SCOPE_}-prefixed authority Spring Security derives from the token's {@code scope} claim.
   *
   * @return {@code true} when the required scope authority is present
   */
  private boolean hasRequiredScope() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return false;
    }
    String required = SCOPE_AUTHORITY_PREFIX + properties.requiredScope();
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(required::equals);
  }

  /**
   * Maps a raw {@code azp} to a bounded metric label: the matching allowlist entry or {@link
   * MetricNames#CLIENT_ID_OTHER} (REQ-OBS-011).
   *
   * @param authorizedParty the token's {@code azp}, or {@code null}
   * @return a bounded, safe metric tag value
   */
  private @NotNull String boundedClientLabel(@Nullable String authorizedParty) {
    if (authorizedParty != null && properties.allowedClientIds().contains(authorizedParty)) {
      return authorizedParty;
    }
    return MetricNames.CLIENT_ID_OTHER;
  }

  /**
   * Reads a string claim, normalising blank to {@code null} so callers need only one check.
   *
   * @param jwt the token to read from
   * @param claim the claim name
   * @return the non-blank claim value, or {@code null}
   */
  private static @Nullable String claimText(@NotNull Jwt jwt, @NotNull String claim) {
    String value = jwt.getClaimAsString(claim);
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * Whether the security context holds an authenticated, non-anonymous caller; anything else is
   * left to the resource-server chain's 401.
   *
   * @param authentication the current authentication, possibly {@code null}
   * @return {@code true} for an authenticated, non-anonymous principal
   */
  private static boolean isAuthenticated(@Nullable Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken);
  }

  /**
   * Refuses an authenticated principal that is not a JWT with a {@code 403 CLIENT_NOT_ALLOWED},
   * counted under its own {@code non_jwt_principal} reason and logged at {@code WARN} with the
   * authentication <em>type</em> only — never its name or credentials.
   *
   * @param request the current request
   * @param response the response the problem body is written to
   * @param authentication the non-JWT authentication that reached the gate
   * @throws IOException if writing the problem body fails
   */
  private void refuseNonJwtPrincipal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull Authentication authentication)
      throws IOException {
    meterRegistry
        .counter(
            MetricNames.INGEST_CLIENT_REJECTED,
            MetricNames.TAG_REASON,
            MetricNames.REASON_NON_JWT_PRINCIPAL)
        .increment();
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, MetricNames.CODE_CLIENT_NOT_ALLOWED)
        .increment();
    log.warn(
        "Ingest client rejected: reason={}, authentication={}, path={} {}",
        MetricNames.REASON_NON_JWT_PRINCIPAL,
        authentication.getClass().getSimpleName(),
        request.getMethod(),
        LogSafe.text(request.getRequestURI(), MAX_LOGGED_PATH));
    ProblemResponseWriter.write(
        response,
        objectMapper,
        loggingProperties,
        HttpStatus.FORBIDDEN,
        "Client not allowed",
        MetricNames.CODE_CLIENT_NOT_ALLOWED,
        notApprovedDetail("no client identity token"));
  }

  /**
   * Restricts the filter to the two ingest endpoints, decided on the decoded path via {@link
   * IngestPathScope}.
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !IngestPathScope.isIngestRequest(request);
  }

  /**
   * One failed client-identity check: the bounded metric reason and the sentence sent to the
   * client.
   *
   * @param reason the bounded {@code MetricNames.REASON_*} tag value
   * @param detail the non-sensitive problem detail
   */
  private record Rejection(@NotNull String reason, @NotNull String detail) {}
}
