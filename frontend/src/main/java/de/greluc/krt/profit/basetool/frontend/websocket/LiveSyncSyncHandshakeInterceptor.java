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

package de.greluc.krt.profit.basetool.frontend.websocket;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Prepares a multiplexed {@code /ws/sync} live-sync handshake (REQ-FE-015, ADR-0092).
 *
 * <p>Unlike the legacy per-resource sockets, {@code /ws/sync} binds no topic at handshake: it marks
 * the future session {@linkplain LiveSyncWebSocketHandler#ATTR_MULTIPLEXED multiplexed} and lets
 * the client {@code subscribe} to individual topics afterwards, each authorized when its frame
 * arrives. Authentication itself is already enforced by the Spring Security chain ({@code /ws/sync}
 * requires an authenticated principal); this interceptor's job is only to capture, <b>on the
 * servlet thread where the request context still exists</b>, the two things a later
 * subscribe-authorization probe needs but cannot obtain from a WebSocket message thread:
 *
 * <ul>
 *   <li>the OAuth2 access token (read <b>read-only</b> from the authorized-client store, exactly
 *       like {@code NotificationPageController.stream} — never triggering a refresh that Keycloak's
 *       reuse-detection would punish, REQ-SEC-012); and
 *   <li>the active-org-unit pin ({@link ActiveSquadronContext}), so a probe scopes exactly like the
 *       page's own reads; and
 *   <li>the caller's authorities, so a subscribe to a locally role-gated global room (the {@code
 *       bank} staff and {@code orgunit-bank} rooms) can be authorized without a backend call on a
 *       message thread that has no {@code SecurityContext}.
 * </ul>
 *
 * <p>Both are stashed in the future session's attributes for {@code LiveSyncSubscriptionAuthorizer}
 * to replay as explicit headers. The captured token lives in memory for the socket's lifetime and
 * is never logged; when it expires, subscribe probes 401 and {@linkplain
 * LiveSyncSubscriptionAuthorizer fail open} (opaque keys only). A missing token or pin is tolerated
 * — the handshake still proceeds and the affected subscribes fail open — so a transient
 * authorized-client hiccup never blocks the socket.
 */
@Slf4j
@RequiredArgsConstructor
public class LiveSyncSyncHandshakeInterceptor implements HandshakeInterceptor {

  /** OAuth2 client registration id whose token authorizes the backend reads. */
  private static final String REGISTRATION_ID = "keycloak";

  private final OAuth2AuthorizedClientRepository authorizedClientRepository;

  /**
   * Marks the future session multiplexed and captures the OAuth2 token and active-org-unit pin for
   * later subscribe authorization. Always proceeds — a capture failure only means the socket's
   * subscribes fail open.
   *
   * @param request the handshake (HTTP upgrade) request
   * @param response the handshake response (unused; the security chain already gated
   *     authentication)
   * @param wsHandler the target handler (unused)
   * @param attributes the future WebSocket-session attributes; the multiplexed flag, token and pin
   *     are stored here
   * @return always {@code true} — authentication is enforced upstream
   */
  @Override
  public boolean beforeHandshake(
      @NotNull ServerHttpRequest request,
      @NotNull ServerHttpResponse response,
      @NotNull WebSocketHandler wsHandler,
      @NotNull Map<String, Object> attributes) {
    attributes.put(LiveSyncWebSocketHandler.ATTR_MULTIPLEXED, Boolean.TRUE);
    UUID activeOrgUnit = ActiveSquadronContext.get();
    if (activeOrgUnit != null) {
      attributes.put(LiveSyncWebSocketHandler.ATTR_ACTIVE_ORG_UNIT, activeOrgUnit);
    }
    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null) {
        // Capture the caller's authorities verbatim (the frontend does a literal authority match —
        // no role hierarchy) so a later subscribe to a locally role-gated global room (the bank
        // staff / orgunit-bank rooms) can be authorized on a WebSocket message thread that has no
        // SecurityContext. Never logged.
        Set<String> authorities =
            authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        attributes.put(LiveSyncWebSocketHandler.ATTR_AUTHORITIES, authorities);
      }
      if (authentication != null && request instanceof ServletServerHttpRequest servletRequest) {
        OAuth2AuthorizedClient client =
            authorizedClientRepository.loadAuthorizedClient(
                REGISTRATION_ID, authentication, servletRequest.getServletRequest());
        if (client != null && client.getAccessToken() != null) {
          attributes.put(
              LiveSyncWebSocketHandler.ATTR_ACCESS_TOKEN, client.getAccessToken().getTokenValue());
        }
      }
    } catch (RuntimeException e) {
      // A best-effort token capture: a failure only degrades subscribe-auth to fail-open, so it
      // must
      // never abort the handshake. Logged without the token.
      log.debug("Live-sync /ws/sync token capture failed; subscribes will fail open", e);
    }
    return true;
  }

  /**
   * No-op: all preparation happens in {@link #beforeHandshake}.
   *
   * @param request the handshake request (unused)
   * @param response the handshake response (unused)
   * @param wsHandler the target handler (unused)
   * @param exception any handshake failure (unused)
   */
  @Override
  public void afterHandshake(
      @NotNull ServerHttpRequest request,
      @NotNull ServerHttpResponse response,
      @NotNull WebSocketHandler wsHandler,
      Exception exception) {
    // intentionally empty
  }
}
