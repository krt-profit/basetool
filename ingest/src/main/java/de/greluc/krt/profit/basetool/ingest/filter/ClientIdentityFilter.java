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
import de.greluc.krt.profit.basetool.ingest.logging.LogSafe;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces <em>which client software</em> may drive the ingest endpoints (REQ-INGEST-011). The user
 * gate is unchanged and stays {@code isAuthenticated()} for every member (REQ-INGEST-002/-008):
 * this filter never asks whether the caller is entitled, only whether the program making the call
 * is an approved one.
 *
 * <p>Three token-level checks, each inert until configured (see {@link ClientIdentityProperties}):
 * the {@code azp} claim against the client-id allowlist, the configured ingest scope, and — once
 * {@code dpop-required} is set — that the request used the DPoP scheme rather than a plain bearer.
 * The payload-level provenance check cannot live here (the body is not parsed yet) and sits in
 * {@code ProvenanceGuard} instead; both share the {@code CLIENT_NOT_ALLOWED} problem code so a
 * client sees one coherent answer.
 *
 * <p><b>Placement.</b> Inside the Spring Security chain, after {@code
 * BearerTokenAuthenticationFilter} (there is no {@link
 * org.springframework.security.core.context.SecurityContext} before it) and after {@link
 * UserIdMdcFilter}, so every line this filter logs already carries the acting subject in the {@code
 * userId} MDC field and never has to repeat it (REQ-OBS-002/-004). Like its siblings it is
 * deliberately not a {@code @Component}: Boot would otherwise also register it as a plain servlet
 * filter, where it would run before authentication and see nothing.
 *
 * <p><b>What it is worth.</b> These gates segment <em>registered</em> clients from one another — a
 * frontend session token cannot drive the gateway, and an approved integration is individually
 * scoped and individually revocable. They are explicitly <em>not</em> anti-tamper: the extractor is
 * a public OAuth client whose client id is readable from the binary and from the wire, so a member
 * who deliberately reproduces it obtains a token that passes every check here. Native-client
 * attestation is not achievable on Windows (no App Attest / Play Integrity equivalent), which is
 * why the design leans on containment instead — the ingest path persists nothing (REQ-INGEST-004) —
 * and on making a foreign caller <em>visible</em> through {@code
 * basetool_ingest_client_rejected_total} rather than pretending it is impossible.
 */
@Slf4j
@RequiredArgsConstructor
public class ClientIdentityFilter extends OncePerRequestFilter {

  /** RFC 9449 {@code Authorization} scheme, compared case-insensitively per RFC 9110. */
  static final String DPOP_SCHEME = "dpop";

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

  private final ClientIdentityProperties properties;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;

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
    Jwt jwt = authenticatedJwt();
    if (jwt == null) {
      // Unauthenticated: the resource-server chain owns that answer (401 + WWW-Authenticate). This
      // filter must not turn a missing token into a 403, which would tell a client to stop retrying
      // when re-authenticating is exactly what it should do.
      filterChain.doFilter(request, response);
      return;
    }
    String authorizedParty = claimText(jwt, AUTHORIZED_PARTY_CLAIM);
    String clientLabel = boundedClientLabel(authorizedParty);
    Rejection rejection = evaluate(request, authorizedParty);

    if (rejection != null) {
      meterRegistry
          .counter(MetricNames.INGEST_CLIENT_REJECTED, MetricNames.TAG_REASON, rejection.reason())
          .increment();
      if (!properties.isAuditOnly()) {
        // WARN, not DEBUG: unlike the pre-auth bot/rate-limit rejects, reaching this point required
        // a VALID realm token, so this cannot be flooded by an anonymous scanner and every
        // occurrence is worth a human look (REQ-OBS-001).
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
            HttpStatus.FORBIDDEN,
            "Client not allowed",
            MetricNames.CODE_CLIENT_NOT_ALLOWED,
            rejection.detail());
        return;
      }
      // Audit-only: the operator is measuring the blast radius before enforcing, so the request is
      // served. Logged at WARN all the same — a silent "would have rejected" defeats the purpose.
      log.warn(
          "Ingest client would be rejected (audit-only): reason={}, clientId={}, path={} {}",
          rejection.reason(),
          LogSafe.text(authorizedParty, MAX_LOGGED_CLIENT_ID),
          request.getMethod(),
          request.getRequestURI());
    }

    warnOnDowngrade(request, jwt);
    meterRegistry
        .counter(MetricNames.INGEST_CLIENT, MetricNames.TAG_CLIENT_ID, clientLabel)
        .increment();
    filterChain.doFilter(request, response);
  }

  /**
   * Runs the configured checks in order of decreasing severity and returns the first failure.
   *
   * <p>Order matters for diagnosis, not for security: all three yield the same {@code 403}, but
   * reporting the token-scheme problem before the claim problems means an operator mid-DPoP-rollout
   * sees {@code dpop_required} rather than a confusing downstream symptom.
   *
   * <p>Takes the already-extracted {@code azp} rather than the {@link Jwt} itself: the only other
   * claim-derived input, the ingest scope, reaches this through the {@code SecurityContext} as a
   * {@code SCOPE_} authority, so passing the token would be a parameter nothing reads.
   *
   * @param request the current request, inspected for the {@code Authorization} scheme
   * @param authorizedParty the token's {@code azp} claim, or {@code null} when absent
   * @return the first failed check, or {@code null} when every configured check passed
   */
  private @Nullable Rejection evaluate(
      @NotNull HttpServletRequest request, @Nullable String authorizedParty) {
    if (properties.isDpopRequired() && !usesDpopScheme(request)) {
      return new Rejection(
          MetricNames.REASON_DPOP_REQUIRED,
          "This endpoint requires a DPoP-bound token (RFC 9449); a plain bearer token is not"
              + " accepted.");
    }
    if (!properties.getAllowedClientIds().isEmpty()) {
      if (authorizedParty == null) {
        // Fail-closed on an ABSENT claim. Treating "no azp" as "nothing to check" would silently
        // disable the gate the moment a realm change stopped stamping the claim.
        return new Rejection(
            MetricNames.REASON_MISSING_AZP, notApprovedDetail("no client identity in the token"));
      }
      if (!properties.getAllowedClientIds().contains(authorizedParty)) {
        return new Rejection(
            MetricNames.REASON_UNKNOWN_CLIENT, notApprovedDetail("client identity"));
      }
    }
    if (!properties.getRequiredScope().isBlank() && !hasRequiredScope()) {
      return new Rejection(
          MetricNames.REASON_MISSING_SCOPE, notApprovedDetail("missing ingest scope"));
    }
    return null;
  }

  /**
   * The user-facing sentence for a client-identity rejection, naming <em>which</em> check refused.
   *
   * <p>Deliberately explicit rather than a bare "forbidden": the extractor surfaces the problem
   * {@code detail} verbatim, so this is where the support boundary is actually communicated to
   * whoever built the calling tool.
   *
   * <p><b>Why the failing check is named.</b> All four checks used to answer with one identical
   * sentence, which cost real time during the 2026-08-03 production incident: the message could not
   * distinguish a token-level refusal from the payload-provenance one, so the operator had to reach
   * for the log to learn which of four gates had fired — and the container had already been
   * recreated by then, taking its log with it. The {@code failedCheck} clause is coarse on purpose:
   * it names the category, never the configured allowlist or scope value, so it diagnoses without
   * disclosing configuration to a caller that was just refused.
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
   * Logs a DPoP <em>downgrade</em>: a token that Keycloak sender-constrained to a key (it carries
   * {@code cnf.jkt}) was presented under the plain bearer scheme, so no proof of possession was
   * validated. Detected and reported independently of {@code dpop-required} because during the
   * dual-mode window this is the one DPoP anomaly that is never benign — a client that holds a
   * bound token has a key, so falling back to bearer is either a client bug or a replay of a token
   * lifted from somewhere else.
   *
   * @param request the current request
   * @param jwt the authenticated caller's token
   */
  private void warnOnDowngrade(@NotNull HttpServletRequest request, @NotNull Jwt jwt) {
    if (!isSenderConstrained(jwt) || usesDpopScheme(request)) {
      return;
    }
    log.warn(
        "DPoP-bound token presented as a plain bearer (no proof validated): path={} {}",
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
   * Reports whether the request presented its token under the RFC 9449 {@code DPoP} scheme.
   *
   * @param request the current request
   * @return {@code true} when the {@code Authorization} header uses the DPoP scheme
   */
  private static boolean usesDpopScheme(@NotNull HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null) {
      return false;
    }
    // RFC 9110 makes the auth-scheme case-insensitive; Keycloak and the Spring converter both emit
    // "DPoP", but a hand-written client may well send "dpop" and must not be misclassified.
    return authorization.toLowerCase(Locale.ROOT).startsWith(DPOP_SCHEME + " ");
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
    String required = SCOPE_AUTHORITY_PREFIX + properties.getRequiredScope();
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(required::equals);
  }

  /**
   * Maps a raw {@code azp} to a metric label from a finite set: the matching allowlist entry, or
   * {@link MetricNames#CLIENT_ID_OTHER}. Never returns the raw claim — a label derived from token
   * content is an unbounded-cardinality bug waiting to happen (REQ-OBS-011).
   *
   * @param authorizedParty the token's {@code azp}, or {@code null}
   * @return a bounded, safe metric tag value
   */
  private @NotNull String boundedClientLabel(@Nullable String authorizedParty) {
    if (authorizedParty != null && properties.getAllowedClientIds().contains(authorizedParty)) {
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
   * Extracts the authenticated caller's JWT from the security context.
   *
   * @return the token, or {@code null} when the request is unauthenticated or was authenticated by
   *     something other than a bearer/DPoP token
   */
  private static @Nullable Jwt authenticatedJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
      return jwtAuthentication.getToken();
    }
    return null;
  }

  /**
   * Restricts the filter to the two ingest endpoints. Actuator health, the Prometheus scrape and
   * {@code /v3/api-docs} are not client-identity gated — they are either unauthenticated by design
   * or guarded by their own chain, and gating them would break the container healthcheck.
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
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
