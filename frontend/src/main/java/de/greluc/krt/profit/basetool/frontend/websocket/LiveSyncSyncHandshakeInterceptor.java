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
import de.greluc.krt.profit.basetool.frontend.support.TermsGateHandoff;
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
 * Prepares a multiplexed {@code /ws/sync} live-sync handshake (REQ-FE-015, ADR-0094).
 *
 * <p>Marks the future session {@linkplain LiveSyncWebSocketHandler#ATTR_MULTIPLEXED multiplexed}
 * and captures, on the servlet thread, what later subscribe authorization needs: the OAuth2 access
 * token (read-only, never refreshed), the active-org-unit pin ({@link ActiveSquadronContext}) and
 * the caller's authorities. It also relays the consent gate's mark via {@link #relayTermsGate}. A
 * missing token or pin never blocks the handshake; the affected subscribes fail open.
 */
@Slf4j
@RequiredArgsConstructor
public class LiveSyncSyncHandshakeInterceptor implements HandshakeInterceptor {

  /** OAuth2 client registration id whose token authorizes the backend reads. */
  private static final String REGISTRATION_ID = "keycloak";

  private final OAuth2AuthorizedClientRepository authorizedClientRepository;

  /**
   * Marks the future session multiplexed and captures the token, pin and authorities for later
   * subscribe authorization; always proceeds.
   *
   * @param request the handshake (HTTP upgrade) request
   * @param response the handshake response (unused)
   * @param wsHandler the target handler (unused)
   * @param attributes the future WebSocket-session attributes that receive the captured values
   * @return always {@code true}; authentication is enforced upstream
   */
  @Override
  public boolean beforeHandshake(
      @NotNull ServerHttpRequest request,
      @NotNull ServerHttpResponse response,
      @NotNull WebSocketHandler wsHandler,
      @NotNull Map<String, Object> attributes) {
    attributes.put(LiveSyncWebSocketHandler.ATTR_MULTIPLEXED, Boolean.TRUE);
    relayTermsGate(request, attributes);
    UUID activeOrgUnit = ActiveSquadronContext.get();
    if (activeOrgUnit != null) {
      attributes.put(LiveSyncWebSocketHandler.ATTR_ACTIVE_ORG_UNIT, activeOrgUnit);
    }
    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null) {
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
      log.debug("Live-sync /ws/sync token capture failed; subscribes will fail open", e);
    }
    return true;
  }

  /**
   * Copies the consent gate's mark from the handshake request onto the future session, so {@link
   * LiveSyncWebSocketHandler#afterConnectionEstablished} can close the socket with a terminal code
   * (REQ-SEC-028).
   *
   * @param request the handshake request, carrying the gate's mark as a request attribute
   * @param attributes the future WebSocket-session attributes
   */
  private static void relayTermsGate(
      @NotNull ServerHttpRequest request, @NotNull Map<String, Object> attributes) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)) {
      return;
    }
    String consentUrl = TermsGateHandoff.consentUrl(servletRequest.getServletRequest());
    if (consentUrl != null) {
      attributes.put(LiveSyncWebSocketHandler.ATTR_TERMS_GATE, consentUrl);
    }
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
      Exception exception) {}
}
