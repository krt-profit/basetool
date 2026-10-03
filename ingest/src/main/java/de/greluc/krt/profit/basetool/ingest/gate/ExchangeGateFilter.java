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

package de.greluc.krt.profit.basetool.ingest.gate;

import de.greluc.krt.profit.basetool.ingest.auth.ExchangeTokenGateFilter;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeLogContext;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.ProblemResponseWriter;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The registry gate of the exchange routes, after the token gate: the route must exist, the
 * exchange be switched on, the client be active in the registry, neither the installation key nor
 * the client be revoked for this member, the route's capability be both in the token and granted,
 * and the client's version meet its minimum (REQ-XCH-001, -003, -004, -008, -024). An admitted
 * request carries an {@link ExchangeRequestContext}, and every request past the token gate is
 * tagged in the log with its registry client and route ({@link ExchangeLogContext}).
 *
 * <p>The registry comes through a five-second cache; the revocations are read on every request.
 * Anything unreadable fails closed with {@code 503 REGISTRY_UNAVAILABLE}.
 */
@RequiredArgsConstructor
public class ExchangeGateFilter extends OncePerRequestFilter {

  /** What a client should wait before retrying a {@code 503}. */
  public static final String RETRY_AFTER_SECONDS = "30";

  /** The detail of {@code 503 REGISTRY_UNAVAILABLE}. */
  public static final String REGISTRY_UNAVAILABLE_DETAIL =
      "The exchange registry cannot be read; try again later.";

  /** The detail of {@code 503 EXCHANGE_DISABLED}. */
  public static final String EXCHANGE_DISABLED_DETAIL = "The exchange is switched off.";

  /** The detail of {@code 403 CLIENT_NOT_ALLOWED}. */
  public static final String CLIENT_NOT_ALLOWED_DETAIL =
      "This client is not approved for the exchange.";

  /** The detail of {@code 403 CLIENT_SUSPENDED}. */
  public static final String CLIENT_SUSPENDED_DETAIL = "This client is suspended.";

  /** The detail of {@code 401 INSTALLATION_REVOKED}. */
  public static final String INSTALLATION_REVOKED_DETAIL =
      "This installation was disconnected; connect again with a new key.";

  /** The detail of {@code 401 CLIENT_REVOKED}. */
  public static final String CLIENT_REVOKED_DETAIL =
      "The member disconnected this client after this connection was made.";

  /** The detail of {@code 403 SCOPE_MISSING}. */
  public static final String SCOPE_MISSING_DETAIL =
      "This route needs a capability the token or the client does not hold.";

  /** The scope that marks a token of an offline session. */
  static final String OFFLINE_ACCESS = "offline_access";

  /** The claim holding the time of the sign-in a token descends from. */
  static final String AUTH_TIME = "auth_time";

  private final ExchangeRegistryReader registryReader;
  private final ExchangeRevocationReader revocationReader;
  private final ExchangeRefusals refusals;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final MeterRegistry meterRegistry;

  /**
   * Admits or refuses one authenticated exchange request.
   *
   * @param request the request
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
    Jwt jwt = token(SecurityContextHolder.getContext().getAuthentication());
    if (jwt == null) {
      filterChain.doFilter(request, response);
      return;
    }
    Optional<ExchangeRoutes.Route> route =
        ExchangeRoutes.find(request.getMethod(), request.getRequestURI());
    if (route.isEmpty()) {
      refuse(
          refusals.clientLabel(jwt.getClaimAsString("azp")),
          response,
          HttpStatus.NOT_FOUND,
          ExchangeRefusals.NOT_FOUND,
          "No such exchange route.");
      return;
    }
    ExchangeLogContext.route(route.get());
    ExchangeRequestContext context;
    try {
      context = admit(jwt, route.get(), request, response);
    } catch (ExchangeUnavailableException e) {
      response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      refuse(
          refusals.clientLabel(jwt.getClaimAsString("azp")),
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          ExchangeRefusals.REGISTRY_UNAVAILABLE,
          REGISTRY_UNAVAILABLE_DETAIL);
      return;
    }
    if (context == null) {
      return;
    }
    ExchangeLogContext.client(context.clientId());
    request.setAttribute(ExchangeRequestContext.ATTRIBUTE, context);
    filterChain.doFilter(request, response);
  }

  /**
   * Runs the registry checks.
   *
   * @param jwt the token
   * @param route the route
   * @param request the request
   * @param response the response a refusal is written to
   * @return the context, or {@code null} when the request was refused
   * @throws IOException if writing a refusal fails
   * @throws ExchangeUnavailableException if the registry or the revocations cannot be read
   */
  private @Nullable ExchangeRequestContext admit(
      @NotNull Jwt jwt,
      @NotNull ExchangeRoutes.Route route,
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response)
      throws IOException {
    ExchangeRegistry registry = registryReader.current();
    String clientId = jwt.getClaimAsString("azp");
    String label = ExchangeRefusals.clientLabel(clientId, registry);
    ExchangeLogContext.client(label);
    if (!registry.enabled()) {
      response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      refuse(
          label,
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          ExchangeRefusals.EXCHANGE_DISABLED,
          EXCHANGE_DISABLED_DETAIL);
      return null;
    }
    ExchangeRegistry.Client client = clientId == null ? null : registry.clients().get(clientId);
    if (client == null) {
      refuse(
          label,
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.CLIENT_NOT_ALLOWED,
          CLIENT_NOT_ALLOWED_DETAIL);
      return null;
    }
    if (!client.active()) {
      refuse(
          label,
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.CLIENT_SUSPENDED,
          CLIENT_SUSPENDED_DETAIL);
      return null;
    }
    String thumbprint = thumbprint(jwt);
    String member = jwt.getSubject();
    if (thumbprint == null || member == null) {
      refuse(
          label,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.UNAUTHENTICATED,
          "The token names no member or key.");
      return null;
    }
    if (revocationReader.isDenied(thumbprint)) {
      refuse(
          label,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.INSTALLATION_REVOKED,
          INSTALLATION_REVOKED_DETAIL);
      return null;
    }
    Set<String> granted = scopes(jwt);
    Instant connectedAt = connectionTime(jwt, granted);
    Long revokedAt = revocationReader.revokedAt(clientId, member);
    if (revokedAt != null && !connectedAfter(connectedAt, revokedAt)) {
      refuse(
          label,
          response,
          HttpStatus.UNAUTHORIZED,
          ExchangeRefusals.CLIENT_REVOKED,
          CLIENT_REVOKED_DETAIL);
      return null;
    }
    granted.retainAll(client.capabilities());
    if (!route.admits(granted)) {
      refuse(
          label,
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.SCOPE_MISSING,
          SCOPE_MISSING_DETAIL);
      return null;
    }
    if (!ClientVersions.meets(
        request.getHeader(HttpHeaders.USER_AGENT), client.minClientVersion())) {
      refuse(
          label,
          response,
          HttpStatus.FORBIDDEN,
          ExchangeRefusals.CLIENT_VERSION_UNSUPPORTED,
          "This client version is no longer supported; please update.");
      return null;
    }
    return new ExchangeRequestContext(
        clientId,
        member,
        thumbprint,
        granted,
        client,
        connectedAt == null ? null : connectedAt.getEpochSecond());
  }

  /**
   * Gates every exchange request except the anonymous contract documents.
   *
   * @param request the current request
   * @return {@code true} to bypass the gate
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return ExchangeTokenGateFilter.isUngated(request);
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
    ExchangeLogContext.client(client);
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
   * Returns the token's DPoP key thumbprint.
   *
   * @param jwt the token
   * @return the thumbprint, or {@code null}
   */
  private static @Nullable String thumbprint(@NotNull Jwt jwt) {
    Map<String, Object> confirmation = jwt.getClaimAsMap("cnf");
    return confirmation != null && confirmation.get("jkt") instanceof String jkt && !jkt.isBlank()
        ? jkt
        : null;
  }

  /**
   * Returns when the token's connection was made, the time a client revocation is compared with
   * (REQ-XCH-008): an offline token's {@code iat}, because the disconnect ended every offline
   * session of the client; any other token's {@code auth_time}, which a refresh keeps and only a
   * new sign-in renews.
   *
   * @param jwt the token
   * @param scopes the token's scopes
   * @return the connection time, or {@code null} when the token lacks the claim it is judged by
   */
  static @Nullable Instant connectionTime(@NotNull Jwt jwt, @NotNull Set<String> scopes) {
    return scopes.contains(OFFLINE_ACCESS) ? jwt.getIssuedAt() : authTime(jwt);
  }

  /**
   * Tells whether a connection was made after the member disconnected the client; one without a
   * connection time is refused.
   *
   * @param connectedAt the connection time, or {@code null}
   * @param revokedAt the revocation's epoch second
   * @return {@code true} when the connection is later than the revocation
   */
  static boolean connectedAfter(@Nullable Instant connectedAt, long revokedAt) {
    return connectedAt != null && connectedAt.getEpochSecond() > revokedAt;
  }

  /**
   * Returns the token's {@code auth_time}.
   *
   * @param jwt the token
   * @return the time of the sign-in the token descends from, or {@code null} when the claim is
   *     absent or not a time
   */
  private static @Nullable Instant authTime(@NotNull Jwt jwt) {
    try {
      return jwt.getClaimAsInstant(AUTH_TIME);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  /**
   * Returns the token's scopes.
   *
   * @param jwt the token
   * @return a mutable set of the space-separated {@code scope} claim
   */
  private static @NotNull Set<String> scopes(@NotNull Jwt jwt) {
    String scope = jwt.getClaimAsString("scope");
    Set<String> scopes = new HashSet<>();
    if (scope != null) {
      scopes.addAll(Arrays.asList(scope.trim().split("\\s+")));
      scopes.remove("");
    }
    return scopes;
  }
}
