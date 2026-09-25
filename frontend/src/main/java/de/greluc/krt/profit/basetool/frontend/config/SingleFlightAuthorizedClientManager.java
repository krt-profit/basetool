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

package de.greluc.krt.profit.basetool.frontend.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A {@link OAuth2AuthorizedClientManager} decorator that collapses the concurrent token refreshes
 * of one user session into a single refresh, so Keycloak's refresh-token reuse detection never
 * revokes the session (REQ-SEC-012, ADR-0019).
 *
 * <p>Refreshes are serialised per session by a striped {@link ReentrantLock} and the result is held
 * in a bounded freshness cache ({@link #MAX_CACHE_ENTRIES}). Lock and cache are JVM-local; calls
 * without a derivable session or principal are delegated without caching.
 */
public class SingleFlightAuthorizedClientManager implements OAuth2AuthorizedClientManager {

  /** Number of lock stripes; bounds memory and avoids a per-session lock-map leak. */
  private static final int STRIPE_COUNT = 64;

  /** Hard cap on cached entries; the map is cleared wholesale if it is ever exceeded. */
  private static final int MAX_CACHE_ENTRIES = 50_000;

  /**
   * Margin subtracted from the access-token expiry, within which a cached token counts as stale.
   * Must be at least the refresh provider's clock skew (60 s by default).
   */
  private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

  private final OAuth2AuthorizedClientManager delegate;
  private final ReentrantLock[] stripes;
  private final Map<String, OAuth2AuthorizedClient> freshClients = new ConcurrentHashMap<>();

  /**
   * Wraps the real authorized-client manager.
   *
   * @param delegate the underlying manager that performs the actual authorization-code / refresh
   *     grants (typically a {@code DefaultOAuth2AuthorizedClientManager})
   */
  public SingleFlightAuthorizedClientManager(@NotNull OAuth2AuthorizedClientManager delegate) {
    this.delegate = delegate;
    this.stripes = new ReentrantLock[STRIPE_COUNT];
    for (int i = 0; i < STRIPE_COUNT; i++) {
      this.stripes[i] = new ReentrantLock();
    }
  }

  /**
   * Authorizes or refreshes the client, serialising concurrent requests of the same session so only
   * one refresh-token grant is issued per expiry window.
   *
   * @param authorizeRequest the authorize request supplied by the WebClient OAuth2 filter
   * @return the authorized client (with a usable access token), or {@code null} if the delegate
   *     could not produce one
   */
  @Override
  @Nullable
  public OAuth2AuthorizedClient authorize(@NotNull OAuth2AuthorizeRequest authorizeRequest) {
    String key = cacheKey(authorizeRequest);
    if (key == null) {
      return delegate.authorize(authorizeRequest);
    }
    ReentrantLock lock = stripes[Math.floorMod(key.hashCode(), STRIPE_COUNT)];
    lock.lock();
    try {
      OAuth2AuthorizedClient cached = freshClients.get(key);
      if (isFresh(cached)) {
        return cached;
      }
      OAuth2AuthorizedClient authorized = delegate.authorize(authorizeRequest);
      if (isFresh(authorized)) {
        if (freshClients.size() >= MAX_CACHE_ENTRIES) {
          freshClients.clear();
        }
        freshClients.put(key, authorized);
      } else {
        freshClients.remove(key);
      }
      return authorized;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Builds the per-session cache and lock key: {@code <registrationId>|s:<sessionId>} when a
   * session can be resolved, from the request attribute or {@link RequestContextHolder}, else
   * {@code <registrationId>|p:<principalName>}.
   *
   * @param request the authorize request
   * @return the cache key, or {@code null} if no session / principal context is available
   */
  @Nullable
  private static String cacheKey(@NotNull OAuth2AuthorizeRequest request) {
    String registrationId = request.getClientRegistrationId();
    HttpServletRequest servletRequest = request.getAttribute(HttpServletRequest.class.getName());
    if (servletRequest == null) {
      servletRequest = currentServletRequest();
    }
    if (servletRequest != null && servletRequest.getSession(false) != null) {
      return registrationId + "|s:" + servletRequest.getSession(false).getId();
    }
    if (request.getPrincipal() != null && request.getPrincipal().getName() != null) {
      return registrationId + "|p:" + request.getPrincipal().getName();
    }
    return null;
  }

  /**
   * Returns the {@link HttpServletRequest} bound to the current thread via {@link
   * RequestContextHolder}.
   *
   * @return the current servlet request, or {@code null} if none is bound to this thread
   */
  @Nullable
  private static HttpServletRequest currentServletRequest() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      return servletAttributes.getRequest();
    }
    return null;
  }

  /**
   * Reports whether {@code client} carries an access token that is still valid beyond the {@link
   * #EXPIRY_SKEW} margin. A {@code null} client, a missing access token, or an absent / imminent
   * expiry all count as stale so the caller refreshes.
   *
   * @param client the candidate authorized client; may be {@code null}
   * @return {@code true} if the access token is safely usable for the near future
   */
  private static boolean isFresh(@Nullable OAuth2AuthorizedClient client) {
    if (client == null || client.getAccessToken() == null) {
      return false;
    }
    Instant expiresAt = client.getAccessToken().getExpiresAt();
    return expiresAt != null && Instant.now().plus(EXPIRY_SKEW).isBefore(expiresAt);
  }
}
