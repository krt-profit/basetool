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

package de.greluc.krt.profit.basetool.ingest.service;

import de.greluc.krt.profit.basetool.ingest.config.ServiceAccountProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Obtains and caches the gateway's own access token for the backend hop (ADR-0129).
 *
 * <p>Since the gateway stopped relaying the caller's token it needs an identity of its own. This is
 * a plain RFC 6749 client-credentials grant against Keycloak, cached in memory until shortly before
 * expiry.
 *
 * <p><strong>Deliberately unbound.</strong> No DPoP proof is presented here, so the issued token
 * carries no {@code cnf} and crosses to the backend as an ordinary bearer. That is the whole point
 * of the split: the sender-constrained token is validated at the internet-facing hop and stops
 * there, while this second hop uses a credential that belongs to the party actually making the
 * call.
 *
 * <p>The token is a process-wide singleton because it identifies the <em>gateway</em>, not a user —
 * the caller is named separately, in the on-behalf-of header. Caching it per request would ask
 * Keycloak for a token on every upload for no gain.
 */
@Slf4j
@Service
public class ServiceAccountTokenProvider {

  /** The token answer, read as a plain map so this module needs no Jackson binding annotations. */
  private static final ParameterizedTypeReference<Map<String, Object>> TOKEN_ANSWER =
      new ParameterizedTypeReference<>() {};

  /** Guards the mint so a burst of concurrent uploads produces one grant, not one per request. */
  private final ReentrantLock mintLock = new ReentrantLock();

  private final ServiceAccountProperties properties;
  private final WebClient keycloakWebClient;
  private final MeterRegistry meterRegistry;

  private volatile String cachedToken;
  private volatile Instant cachedUntil = Instant.EPOCH;

  /**
   * Creates the provider.
   *
   * @param properties the gateway's client credentials and cache tuning
   * @param keycloakWebClient the client used for the token request
   * @param meterRegistry records the minted/cached/failed outcome
   */
  public ServiceAccountTokenProvider(
      @NotNull ServiceAccountProperties properties,
      @Qualifier("keycloakWebClient") @NotNull WebClient keycloakWebClient,
      @NotNull MeterRegistry meterRegistry) {
    this.properties = properties;
    this.keycloakWebClient = keycloakWebClient;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Whether the gateway has been given an identity at all.
   *
   * <p>Read by the ingest path so a missing configuration fails as a named, actionable refusal
   * rather than as a null token on the wire that the backend answers with an opaque 401.
   *
   * @return {@code true} when a client id, secret and token URI are all configured
   */
  public boolean isConfigured() {
    return !properties.getTokenUri().isBlank()
        && !properties.getClientId().isBlank()
        && !properties.getClientSecret().isBlank();
  }

  /**
   * Returns a currently-valid gateway access token, minting one when the cached value is missing or
   * about to expire.
   *
   * @return the compact JWT to put on the backend hop
   * @throws ServiceAccountTokenException when no identity is configured, or when Keycloak refuses
   *     or cannot be reached
   */
  public @NotNull String currentToken() {
    if (!isConfigured()) {
      // Deliberately the SAME type as a failed grant. To the sender both are "the gateway cannot
      // act"; the distinction that matters to an operator lives in the log and in the metric, not
      // in the exception type - and a dedicated type keeps the handler from having to catch
      // IllegalStateException, which would swallow unrelated faults into a misleading 503.
      throw new ServiceAccountTokenException(
          "no service-account identity configured (app.ingest.service-account.*)", null);
    }
    String token = cachedToken;
    if (token != null && Instant.now().isBefore(cachedUntil)) {
      count(MetricNames.SA_TOKEN_CACHED);
      return token;
    }
    mintLock.lock();
    try {
      // Re-check under the lock: while this thread waited, another may have minted one.
      token = cachedToken;
      if (token != null && Instant.now().isBefore(cachedUntil)) {
        count(MetricNames.SA_TOKEN_CACHED);
        return token;
      }
      return mint();
    } finally {
      mintLock.unlock();
    }
  }

  /**
   * Performs the client-credentials grant and caches the result.
   *
   * @return the freshly minted token
   * @throws ServiceAccountTokenException when the grant fails
   */
  private @NotNull String mint() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", properties.getClientId());
    form.add("client_secret", properties.getClientSecret());
    Map<String, Object> response;
    try {
      response =
          keycloakWebClient
              .post()
              .uri(properties.getTokenUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(BodyInserters.fromFormData(form))
              .retrieve()
              .bodyToMono(TOKEN_ANSWER)
              .block(Duration.ofMillis(properties.getTimeoutMillis()));
    } catch (RuntimeException e) {
      count(MetricNames.SA_TOKEN_FAILED);
      // Exception class only. Keycloak echoes the client_id in its error body and the stack can
      // carry the form, which holds the client secret (REQ-OBS-004).
      log.error(
          "The gateway could not obtain its own access token ({}); ingest writes are refused until"
              + " this recovers",
          e.getClass().getSimpleName());
      throw new ServiceAccountTokenException("service-account grant failed", e);
    }
    String accessToken =
        response == null || !(response.get("access_token") instanceof String value) ? null : value;
    if (accessToken == null || accessToken.isBlank()) {
      count(MetricNames.SA_TOKEN_FAILED);
      log.error("The gateway's token grant returned no access token; ingest writes are refused");
      throw new ServiceAccountTokenException("service-account grant returned no token", null);
    }
    cachedToken = accessToken;
    // expires_in is seconds; the skew keeps a token that expires mid-flight off the wire. A missing
    // or unparseable value is treated as already expired, so the next call re-mints rather than
    // caching something whose lifetime is unknown.
    long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 0L;
    long lifetime = Math.max(0, expiresIn - properties.getRefreshSkew().toSeconds());
    cachedUntil = Instant.now().plusSeconds(lifetime);
    count(MetricNames.SA_TOKEN_MINTED);
    return cachedToken;
  }

  /**
   * Records one token outcome.
   *
   * @param outcome one of the bounded {@code MetricNames.SA_TOKEN_*} literals
   */
  private void count(@NotNull String outcome) {
    meterRegistry
        .counter(MetricNames.INGEST_SERVICE_ACCOUNT_TOKEN, MetricNames.TAG_OUTCOME, outcome)
        .increment();
  }

  /** Signals that the gateway could not obtain an identity for the backend hop. */
  public static class ServiceAccountTokenException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a developer-facing description; never surfaced to a client
     * @param cause the originating failure, or {@code null} when the answer was merely unusable
     */
    public ServiceAccountTokenException(@NotNull String message, Throwable cause) {
      super(message, cause);
    }
  }
}
