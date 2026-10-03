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

package de.greluc.krt.profit.basetool.ingest.auth;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.edge.IngestPathScope;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.ProblemResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The token gate of the exchange routes (REQ-XCH-004, REQ-XCH-006): a request must present a
 * DPoP-bound token with a proof, and the token must be issued for this gateway — checked in code,
 * whatever {@code app.security.jwt.expected-audiences} says. Every exchange response carries a
 * fresh {@code DPoP-Nonce}.
 *
 * <p>Runs after authentication; an anonymous request is left to the authorization rules. The
 * anonymous contract documents are not gated.
 */
@RequiredArgsConstructor
public class ExchangeTokenGateFilter extends OncePerRequestFilter {

  /** The audience every exchange token must carry. */
  public static final String AUDIENCE = "basetool-ingest";

  /** The response header carrying the server nonce. */
  public static final String DPOP_NONCE_HEADER = "DPoP-Nonce";

  /** The confirmation claim (RFC 7800). */
  private static final String CONFIRMATION_CLAIM = "cnf";

  /** The confirmation member naming the DPoP key thumbprint. */
  private static final String THUMBPRINT_MEMBER = "jkt";

  /** The authorization scheme of a DPoP-bound request. */
  private static final String DPOP_SCHEME = "DPoP ";

  /** The anonymous contract documents. */
  private static final List<String> DOCUMENT_PREFIXES =
      List.of("/exchange/v1/openapi.json", "/exchange/v1/schemas/");

  private final ExchangeDpopNonces nonces;
  private final ExchangeRefusals refusals;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final MeterRegistry meterRegistry;

  /**
   * Refuses an unbound or foreign token, otherwise continues with a fresh nonce on the response.
   *
   * @param request the exchange request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if a later filter fails
   * @throws IOException if writing fails
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader(DPOP_NONCE_HEADER, nonces.current());
    Jwt jwt = token(SecurityContextHolder.getContext().getAuthentication());
    if (jwt == null) {
      filterChain.doFilter(request, response);
      return;
    }
    String client = refusals.clientLabel(jwt.getClaimAsString("azp"));
    if (!isDpopRequest(request) || !isBound(jwt)) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, ExchangeChallenge.header(null));
      refuse(
          client,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.DPOP_REQUIRED,
          "Exchange routes need a DPoP-bound token and a DPoP proof.");
      return;
    }
    List<String> audience = jwt.getAudience();
    if (audience == null || !audience.contains(AUDIENCE)) {
      refuse(
          client,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.UNAUTHENTICATED,
          "The token is not issued for this gateway.");
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Leaves everything outside the exchange alone, and the anonymous contract documents.
   *
   * @param request the current request
   * @return {@code true} to bypass the gate
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return isUngated(request);
  }

  /**
   * Whether a request lies outside the exchange gates: every path outside {@code /exchange}, and a
   * read of the anonymous contract documents.
   *
   * @param request the request
   * @return {@code true} when neither exchange gate applies
   */
  public static boolean isUngated(@NotNull HttpServletRequest request) {
    if (!IngestPathScope.isExchangeRequest(request)) {
      return true;
    }
    if (!HttpMethod.GET.matches(request.getMethod())) {
      return false;
    }
    String uri = request.getRequestURI();
    return DOCUMENT_PREFIXES.stream().anyMatch(uri::startsWith);
  }

  /**
   * Writes and counts one refusal.
   *
   * @param client the {@code client_id} label of the refused request
   * @param response the response
   * @param status the status
   * @param code the problem code
   * @param detail the detail
   * @throws IOException if writing fails
   */
  private void refuse(
      @NotNull String client,
      @NotNull HttpServletResponse response,
      @NotNull HttpStatus status,
      @NotNull String code,
      @NotNull String detail)
      throws IOException {
    refusals.count(code, client);
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
    ProblemResponseWriter.write(
        response, objectMapper, loggingProperties, status, "Refused", code, detail);
  }

  /**
   * Returns the caller's token.
   *
   * @param authentication the current authentication
   * @return the token, or {@code null} when the caller is anonymous
   */
  private static @Nullable Jwt token(@Nullable Authentication authentication) {
    return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
  }

  /**
   * Whether the request authenticated with the DPoP scheme.
   *
   * @param request the request
   * @return {@code true} for {@code Authorization: DPoP …}
   */
  private static boolean isDpopRequest(@NotNull HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    return StringUtils.startsWithIgnoreCase(authorization, DPOP_SCHEME);
  }

  /**
   * Whether the token is bound to a DPoP key.
   *
   * @param jwt the token
   * @return {@code true} when it carries a {@code cnf.jkt}
   */
  private static boolean isBound(@NotNull Jwt jwt) {
    Map<String, Object> confirmation = jwt.getClaimAsMap(CONFIRMATION_CLAIM);
    return confirmation != null
        && confirmation.get(THUMBPRINT_MEMBER) instanceof String thumbprint
        && !thumbprint.isBlank();
  }
}
