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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

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
 *
 * <p><b>Three properties of the cache, each fixing a failure mode the first version had:</b>
 *
 * <ul>
 *   <li><b>Token and expiry are one value.</b> They used to be two separate {@code volatile}
 *       fields, so a reader racing a mint could pair the new token with the old expiry or the
 *       reverse — a fresh token treated as expired (a needless grant) or, worse, a stale token
 *       served against a fresh expiry. One immutable {@link CachedToken} behind one {@code
 *       volatile} reference makes the pair atomic.
 *   <li><b>It can be invalidated.</b> When the backend refuses the token ({@code 401}/{@code 403},
 *       see {@code GlobalExceptionHandler}) the cache is dropped via {@link #invalidate()}, so the
 *       next upload mints a fresh one instead of replaying the refused token until it expires.
 *   <li><b>A failed grant backs off.</b> For {@link #FAILURE_BACKOFF} after a failure the provider
 *       refuses immediately instead of calling Keycloak again, so a burst of uploads against a
 *       broken or unreachable token endpoint costs one grant attempt per window rather than one per
 *       upload, each blocking its request thread for up to the configured timeout.
 * </ul>
 */
@Slf4j
@Service
public class ServiceAccountTokenProvider {

  /**
   * How long a failed grant suppresses further attempts. Short on purpose: long enough to collapse
   * a burst of uploads against a dead token endpoint into one attempt, short enough that a
   * recovered Keycloak is used again within seconds.
   */
  static final Duration FAILURE_BACKOFF = Duration.ofSeconds(5);

  /** The token answer, read as a plain map so this module needs no Jackson binding annotations. */
  private static final ParameterizedTypeReference<Map<String, Object>> TOKEN_ANSWER =
      new ParameterizedTypeReference<>() {};

  /** Guards the mint so a burst of concurrent uploads produces one grant, not one per request. */
  private final ReentrantLock mintLock = new ReentrantLock();

  /** The gateway's client credentials and cache tuning. */
  private final ServiceAccountProperties properties;

  /**
   * The client the token request is sent with. Its request factory bounds the call by {@code
   * timeoutMillis} (capped at 10&nbsp;s, see {@code RestClientConfig#keycloakRestClient}); the
   * reactive predecessor bounded it here with {@code block(timeout)}.
   */
  private final RestClient keycloakRestClient;

  /** Records the minted / cached / failed / backoff outcome of every call. */
  private final MeterRegistry meterRegistry;

  /** Time source for expiry and backoff; a fixed clock in the tests. */
  private final Clock clock;

  /** The current token and its usable-until instant, swapped atomically; {@code null} when cold. */
  private volatile @Nullable CachedToken cached;

  /** Until when a failed grant suppresses further attempts; {@link Instant#EPOCH} when none did. */
  private volatile Instant backoffUntil = Instant.EPOCH;

  /**
   * Creates the provider on the system clock.
   *
   * @param properties the gateway's client credentials and cache tuning
   * @param keycloakRestClient the client used for the token request
   * @param meterRegistry records the minted/cached/failed/backoff outcome
   */
  @Autowired
  public ServiceAccountTokenProvider(
      @NotNull ServiceAccountProperties properties,
      @Qualifier("keycloakRestClient") @NotNull RestClient keycloakRestClient,
      @NotNull MeterRegistry meterRegistry) {
    this(properties, keycloakRestClient, meterRegistry, Clock.systemUTC());
  }

  /**
   * Creates the provider on the given clock, so a test can move time across an expiry or a backoff
   * window without sleeping.
   *
   * @param properties the gateway's client credentials and cache tuning
   * @param keycloakRestClient the client used for the token request
   * @param meterRegistry records the minted/cached/failed/backoff outcome
   * @param clock the time source for expiry and backoff decisions
   */
  ServiceAccountTokenProvider(
      @NotNull ServiceAccountProperties properties,
      @NotNull RestClient keycloakRestClient,
      @NotNull MeterRegistry meterRegistry,
      @NotNull Clock clock) {
    this.properties = properties;
    this.keycloakRestClient = keycloakRestClient;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
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
    return !properties.tokenUri().isBlank()
        && !properties.clientId().isBlank()
        && !properties.clientSecret().isBlank();
  }

  /**
   * Returns a currently-valid gateway access token, minting one when the cached value is missing or
   * about to expire.
   *
   * @return the compact JWT to put on the backend hop
   * @throws ServiceAccountTokenException when no identity is configured, when Keycloak refuses or
   *     cannot be reached, or while a recent failure's backoff window is still open
   */
  public @NotNull String currentToken() {
    if (!isConfigured()) {
      throw new ServiceAccountTokenException(
          "no service-account identity configured (app.ingest.service-account.*)", null);
    }
    String token = validCachedToken();
    if (token != null) {
      return token;
    }
    mintLock.lock();
    try {
      token = validCachedToken();
      if (token != null) {
        return token;
      }
      if (clock.instant().isBefore(backoffUntil)) {
        count(MetricNames.SA_TOKEN_BACKOFF);
        throw new ServiceAccountTokenException(
            "service-account grant failed recently; backing off", null);
      }
      return mint();
    } finally {
      mintLock.unlock();
    }
  }

  /**
   * Drops the cached token, so the next {@link #currentToken()} mints a fresh one.
   *
   * <p>Called when the backend refuses the gateway's token ({@code 401}/{@code 403}). Without it a
   * revoked or otherwise refused token would be replayed on every upload until its natural expiry,
   * minutes of guaranteed failures after the cause may already be fixed. Deliberately does
   * <em>not</em> open a backoff window: the grant itself did not fail, and the very next upload
   * should try a fresh token.
   */
  public void invalidate() {
    cached = null;
  }

  /**
   * Reads the cache once and returns its token when it is still inside its usable window, counting
   * the cache hit.
   *
   * @return the cached token, or {@code null} when the cache is cold or the token is about to
   *     expire
   */
  private @Nullable String validCachedToken() {
    CachedToken snapshot = cached;
    if (snapshot != null && clock.instant().isBefore(snapshot.until())) {
      count(MetricNames.SA_TOKEN_CACHED);
      return snapshot.token();
    }
    return null;
  }

  /**
   * Performs the client-credentials grant and caches the result. Called with {@link #mintLock}
   * held.
   *
   * @return the freshly minted token
   * @throws ServiceAccountTokenException when the grant fails; the backoff window is opened first
   */
  private @NotNull String mint() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());
    Map<String, Object> response;
    try {
      response =
          keycloakRestClient
              .post()
              .uri(properties.tokenUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(TOKEN_ANSWER);
    } catch (RuntimeException e) {
      failed();
      log.error(
          "The gateway could not obtain its own access token ({}); ingest writes are refused until"
              + " this recovers",
          e.getClass().getSimpleName());
      throw new ServiceAccountTokenException("service-account grant failed", e);
    }
    String accessToken =
        response == null || !(response.get("access_token") instanceof String value) ? null : value;
    if (accessToken == null || accessToken.isBlank()) {
      failed();
      log.error("The gateway's token grant returned no access token; ingest writes are refused");
      throw new ServiceAccountTokenException("service-account grant returned no token", null);
    }
    long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 0L;
    long lifetime = Math.max(0, expiresIn - properties.refreshSkew().toSeconds());
    cached = new CachedToken(accessToken, clock.instant().plusSeconds(lifetime));
    backoffUntil = Instant.EPOCH;
    count(MetricNames.SA_TOKEN_MINTED);
    return accessToken;
  }

  /** Counts a failed grant and opens the {@link #FAILURE_BACKOFF} window. */
  private void failed() {
    count(MetricNames.SA_TOKEN_FAILED);
    backoffUntil = clock.instant().plus(FAILURE_BACKOFF);
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

  /**
   * One cached grant: the token and the instant until which it may be handed out. Immutable, so a
   * reader always sees a token together with its own expiry.
   *
   * @param token the compact access token
   * @param until the instant after which the token must be replaced (expiry minus the refresh skew)
   */
  private record CachedToken(@NotNull String token, @NotNull Instant until) {

    /**
     * Renders the entry without the token, so the record can never leak the credential through a
     * log line.
     *
     * @return the expiry only
     */
    @Override
    public @NotNull String toString() {
      return "CachedToken[until=" + until + "]";
    }
  }

  /** Signals that the gateway could not obtain an identity for the backend hop. */
  public static class ServiceAccountTokenException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a developer-facing description; never surfaced to a client
     * @param cause the originating failure, or {@code null} when the answer was merely unusable
     */
    public ServiceAccountTokenException(@NotNull String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }
}
