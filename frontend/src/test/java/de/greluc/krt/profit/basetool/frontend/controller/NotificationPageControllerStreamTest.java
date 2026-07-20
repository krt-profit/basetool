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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link NotificationPageController#stream(HttpServletRequest, Authentication)}: the
 * notification SSE relay must resolve the OAuth2 bearer <b>read-only</b> and never drive a token
 * refresh on this long-lived request (REQ-SEC-012, ADR-0019). A refresh here would rotate the
 * session's online refresh token and a late session write-back could resurrect a stale token,
 * tripping Keycloak's reuse detection and revoking the whole session. The relay attaches the
 * snapshot token as a plain {@code Authorization} header on the filter-free {@code sseWebClient},
 * so it is structurally refresh-incapable — these tests pin that it fails soft without a usable
 * token and otherwise relays the snapshot bearer verbatim, even when expired.
 */
class NotificationPageControllerStreamTest {

  private static final String REGISTRATION_ID = "keycloak";

  @Test
  void stream_withNoBoundToken_failsSoft_withoutCallingTheBackendStream() {
    // Given a session whose authorized client cannot be loaded (e.g. a freshly-lost session)
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MessageSource messageSource = mock(MessageSource.class);
    WebClient sseWebClient = mock(WebClient.class);
    OAuth2AuthorizedClientRepository authorizedClientRepository =
        mock(OAuth2AuthorizedClientRepository.class);
    NotificationPageController controller =
        new NotificationPageController(
            backendApiClient,
            messageSource,
            sseWebClient,
            authorizedClientRepository,
            new SimpleMeterRegistry());

    HttpServletRequest request = mock(HttpServletRequest.class);
    Authentication authentication = mock(Authentication.class);
    when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
        .thenReturn(null);

    // When the browser opens the stream
    SseEmitter emitter = controller.stream(request, authentication);

    // Then the token was resolved read-only and the relay failed soft: no upstream subscription,
    // so the long-lived request never asks the manager to refresh/rotate the token.
    assertNotNull(emitter);
    verify(authorizedClientRepository)
        .loadAuthorizedClient(REGISTRATION_ID, authentication, request);
    verifyNoInteractions(sseWebClient);
  }

  @Test
  void stream_withTokenlessClient_failsSoft_withoutCallingTheBackendStream() {
    // Given an authorized client present in the session but carrying no access token
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MessageSource messageSource = mock(MessageSource.class);
    WebClient sseWebClient = mock(WebClient.class);
    OAuth2AuthorizedClientRepository authorizedClientRepository =
        mock(OAuth2AuthorizedClientRepository.class);
    NotificationPageController controller =
        new NotificationPageController(
            backendApiClient,
            messageSource,
            sseWebClient,
            authorizedClientRepository,
            new SimpleMeterRegistry());

    HttpServletRequest request = mock(HttpServletRequest.class);
    Authentication authentication = mock(Authentication.class);
    OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
    when(client.getAccessToken()).thenReturn((OAuth2AccessToken) null);
    when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
        .thenReturn(client);

    // When the browser opens the stream
    SseEmitter emitter = controller.stream(request, authentication);

    // Then it still fails soft rather than relaying a tokenless call.
    assertNotNull(emitter);
    verifyNoInteractions(sseWebClient);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void stream_withExpiredClient_relaysSnapshotBearerVerbatim_withoutRefreshing() {
    // Given a session whose authorized client is PRESENT but its access token has already expired —
    // the exact production condition that fired the incident. The relay must NOT refresh it (a
    // refresh would rotate the online refresh token and trip Keycloak's reuse detection); it must
    // relay the snapshot token verbatim as a plain bearer and let the backend reject it, leaving
    // re-authentication to the 60s unread-count poll.
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MessageSource messageSource = mock(MessageSource.class);
    WebClient sseWebClient = mock(WebClient.class);
    OAuth2AuthorizedClientRepository authorizedClientRepository =
        mock(OAuth2AuthorizedClientRepository.class);
    NotificationPageController controller =
        new NotificationPageController(
            backendApiClient,
            messageSource,
            sseWebClient,
            authorizedClientRepository,
            new SimpleMeterRegistry());

    HttpServletRequest request = mock(HttpServletRequest.class);
    Authentication authentication = mock(Authentication.class);
    OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
    OAuth2AccessToken expiredToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "expired-access-token",
            Instant.now().minusSeconds(600),
            Instant.now().minusSeconds(300));
    when(client.getAccessToken()).thenReturn(expiredToken);
    when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
        .thenReturn(client);

    WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(sseWebClient.get()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(headersSpec);
    when(headersSpec.headers(any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToFlux(anyTypeRef())).thenReturn(Flux.empty());

    // When the browser opens the stream
    SseEmitter emitter = controller.stream(request, authentication);

    // Then the upstream call was issued (no fail-soft on a present token) carrying the snapshot
    // bearer verbatim — proving the relay used the read-only token without obtaining a fresh one.
    assertNotNull(emitter);
    ArgumentCaptor<Consumer<HttpHeaders>> headersCaptor = ArgumentCaptor.captor();
    verify(headersSpec).headers(headersCaptor.capture());
    HttpHeaders applied = new HttpHeaders();
    headersCaptor.getValue().accept(applied);
    assertEquals("Bearer expired-access-token", applied.getFirst(HttpHeaders.AUTHORIZATION));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void stream_withOpenUpstream_countsTheRelayInTheConnectionsGauge() {
    // Given a valid token and an upstream that never terminates (a live relay)
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MessageSource messageSource = mock(MessageSource.class);
    WebClient sseWebClient = mock(WebClient.class);
    OAuth2AuthorizedClientRepository authorizedClientRepository =
        mock(OAuth2AuthorizedClientRepository.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    NotificationPageController controller =
        new NotificationPageController(
            backendApiClient, messageSource, sseWebClient, authorizedClientRepository, registry);
    controller.registerRelayGauge();

    HttpServletRequest request = mock(HttpServletRequest.class);
    Authentication authentication = mock(Authentication.class);
    OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "live-token",
            Instant.now(),
            Instant.now().plusSeconds(300));
    when(client.getAccessToken()).thenReturn(token);
    when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
        .thenReturn(client);

    WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(sseWebClient.get()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(headersSpec);
    when(headersSpec.headers(any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToFlux(anyTypeRef())).thenReturn(Flux.never());

    // When the browser opens the stream
    controller.stream(request, authentication);

    // Then the live relay is reflected in the gauge — doFinally has not fired, the stream is open
    assertEquals(1.0, registry.get(MetricNames.NOTIFICATION_RELAY_CONNECTIONS).gauge().value());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void stream_withValidToken_commitsWithAnInitialSseCommentFromTheRequestThread() throws Exception {
    // Given a valid token and an upstream that never emits (Flux.never), so the ONLY send is the
    // controller's own initial commit. The relay MUST flush its response headers from the request
    // thread (ADR-0113 / spring-ai #6169): its forwarded writes arrive on a reactor-netty thread
    // that Spring Web 7 + Tomcat 11 never commit, so without an initial request-thread send every
    // stream header-times-out at the proxy (the 100%-dead-SSE incident).
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MessageSource messageSource = mock(MessageSource.class);
    WebClient sseWebClient = mock(WebClient.class);
    OAuth2AuthorizedClientRepository authorizedClientRepository =
        mock(OAuth2AuthorizedClientRepository.class);
    SseEmitter mockEmitter = mock(SseEmitter.class);
    NotificationPageController controller =
        new NotificationPageController(
            backendApiClient,
            messageSource,
            sseWebClient,
            authorizedClientRepository,
            new SimpleMeterRegistry()) {
          @Override
          protected SseEmitter newEmitter() {
            return mockEmitter;
          }
        };

    HttpServletRequest request = mock(HttpServletRequest.class);
    Authentication authentication = mock(Authentication.class);
    OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "live-token",
            Instant.now(),
            Instant.now().plusSeconds(300));
    when(client.getAccessToken()).thenReturn(token);
    when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
        .thenReturn(client);

    WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
    WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(sseWebClient.get()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString())).thenReturn(headersSpec);
    when(headersSpec.headers(any())).thenReturn(headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToFlux(anyTypeRef())).thenReturn(Flux.never());

    // When the browser opens the stream
    controller.stream(request, authentication);

    // Then exactly one send ran synchronously (the request thread) carrying an SSE comment — the
    // response is committed before any reactor-netty forward could run (none does; Flux.never).
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.captor();
    verify(mockEmitter).send(captor.capture());
    String serialized =
        captor.getValue().build().stream()
            .map(data -> String.valueOf(data.getData()))
            .collect(Collectors.joining());
    assertTrue(
        serialized.contains(":ready"),
        "initial commit must be an invisible SSE comment, was: " + serialized);
  }
}
