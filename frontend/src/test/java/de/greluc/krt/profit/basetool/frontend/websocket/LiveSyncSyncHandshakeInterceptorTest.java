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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.support.TermsGateHandoff;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.socket.WebSocketHandler;

/**
 * Tests for {@link LiveSyncSyncHandshakeInterceptor}: it always marks the future session
 * multiplexed and proceeds, captures the OAuth2 token snapshot and active-org-unit pin when
 * available, relays the consent gate's handoff mark, and fails open (still proceeds, no token) when
 * the authorized-client read is empty or throws.
 */
class LiveSyncSyncHandshakeInterceptorTest {

  private OAuth2AuthorizedClientRepository authorizedClientRepository;
  private LiveSyncSyncHandshakeInterceptor interceptor;
  private ServerHttpRequest request;
  private ServerHttpResponse response;
  private WebSocketHandler wsHandler;
  private Map<String, Object> attributes;

  @BeforeEach
  void setUp() {
    authorizedClientRepository = mock(OAuth2AuthorizedClientRepository.class);
    interceptor = new LiveSyncSyncHandshakeInterceptor(authorizedClientRepository);
    request = new ServletServerHttpRequest(new MockHttpServletRequest());
    response = new ServletServerHttpResponse(new MockHttpServletResponse());
    wsHandler = mock(WebSocketHandler.class);
    attributes = new HashMap<>();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("user", "n/a"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    ActiveSquadronContext.clear();
  }

  @Test
  void alwaysMarksMultiplexedAndProceeds() {
    boolean proceed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

    assertThat(proceed).isTrue();
    assertThat(attributes.get(LiveSyncWebSocketHandler.ATTR_MULTIPLEXED)).isEqualTo(Boolean.TRUE);
  }

  @Test
  void capturesAccessTokenSnapshot() {
    OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
    when(client.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "tok-123",
                Instant.now(),
                Instant.now().plusSeconds(300)));
    when(authorizedClientRepository.loadAuthorizedClient(eq("keycloak"), any(), any()))
        .thenReturn(client);

    interceptor.beforeHandshake(request, response, wsHandler, attributes);

    assertThat(attributes.get(LiveSyncWebSocketHandler.ATTR_ACCESS_TOKEN)).isEqualTo("tok-123");
  }

  @Test
  void capturesAuthoritiesVerbatim() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user",
                "n/a",
                List.of(
                    new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"),
                    new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))));

    interceptor.beforeHandshake(request, response, wsHandler, attributes);

    Object captured = attributes.get(LiveSyncWebSocketHandler.ATTR_AUTHORITIES);
    assertThat(captured).isInstanceOf(Set.class);
    assertThat((Set<?>) captured)
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_BANK_EMPLOYEE", "ROLE_KRT_MEMBER");
  }

  @Test
  void capturesActiveOrgUnitPin() {
    UUID pin = UUID.randomUUID();
    ActiveSquadronContext.set(pin);

    interceptor.beforeHandshake(request, response, wsHandler, attributes);

    assertThat(attributes.get(LiveSyncWebSocketHandler.ATTR_ACTIVE_ORG_UNIT)).isEqualTo(pin);
  }

  /**
   * The consent gate's mark is relayed onto the session — and the handshake still proceeds.
   *
   * <p>Proceeding is the point: refusing here would produce the same bare {@code 1006} the mark
   * exists to avoid, and only a socket that actually opens can be closed with a code the client can
   * read as terminal (REQ-SEC-028).
   */
  @Test
  void relaysTheConsentGateMarkAndStillProceeds() {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    TermsGateHandoff.mark(servletRequest, "/terms/accept");

    boolean proceed =
        interceptor.beforeHandshake(
            new ServletServerHttpRequest(servletRequest), response, wsHandler, attributes);

    assertThat(proceed).isTrue();
    assertThat(attributes.get(LiveSyncWebSocketHandler.ATTR_TERMS_GATE)).isEqualTo("/terms/accept");
  }

  /** An unmarked handshake — the overwhelmingly common case — carries no gate attribute. */
  @Test
  void unmarkedHandshakeCarriesNoConsentGateAttribute() {
    interceptor.beforeHandshake(request, response, wsHandler, attributes);

    assertThat(attributes).doesNotContainKey(LiveSyncWebSocketHandler.ATTR_TERMS_GATE);
  }

  @Test
  void noAuthorizedClient_proceedsWithoutToken() {
    when(authorizedClientRepository.loadAuthorizedClient(eq("keycloak"), any(), any()))
        .thenReturn(null);

    boolean proceed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

    assertThat(proceed).isTrue();
    assertThat(attributes).doesNotContainKey(LiveSyncWebSocketHandler.ATTR_ACCESS_TOKEN);
  }

  @Test
  void authorizedClientReadThrows_failsOpenAndProceeds() {
    when(authorizedClientRepository.loadAuthorizedClient(eq("keycloak"), any(), any()))
        .thenThrow(new IllegalStateException("boom"));

    boolean proceed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

    assertThat(proceed).isTrue();
    assertThat(attributes).doesNotContainKey(LiveSyncWebSocketHandler.ATTR_ACCESS_TOKEN);
  }
}
