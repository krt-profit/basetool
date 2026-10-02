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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpContext;
import de.greluc.krt.profit.basetool.frontend.logging.CorrelationContext;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Drives real requests through the {@code webClient} bean and its siblings against a stand-in
 * backend and a second, foreign server (REQ-FE-029): a backend call carries the bearer, the
 * correlation, org-unit, locale and client-IP relays and passes the circuit breaker; a request to
 * any other origin is refused before the bearer is resolved and never reaches the foreign server.
 */
@SpringBootTest
class WebClientBackendSeamTest {

  /** The bearer the stubbed authorized-client manager hands out. */
  private static final String BEARER = "seam-test-bearer";

  private static MockWebServer backend;
  private static MockWebServer foreign;

  @Autowired private WebClient webClient;
  @Autowired private BackendApiClient backendApiClient;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
  @Autowired private ApplicationContext context;

  @MockitoBean private OAuth2AuthorizedClientManager authorizedClientManager;
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeAll
  static void startServers() throws IOException {
    backend = answeringServer();
    foreign = answeringServer();
  }

  @AfterAll
  static void stopServers() throws IOException {
    backend.shutdown();
    foreign.shutdown();
  }

  /**
   * Points {@code app.backend-url} at the stand-in backend.
   *
   * @param registry the dynamic property registry
   */
  @DynamicPropertySource
  static void backendUrl(DynamicPropertyRegistry registry) {
    registry.add("app.backend-url", () -> "http://localhost:" + backend.getPort());
  }

  @BeforeEach
  void stubTheBearer() {
    when(authorizedClientManager.authorize(any())).thenReturn(authorizedClient());
    circuitBreakerRegistry.circuitBreaker("backendApi").reset();
  }

  @AfterEach
  void clearRelays() {
    CorrelationContext.clear();
    ActiveSquadronContext.clear();
    ClientIpContext.clear();
    LocaleContextHolder.resetLocaleContext();
    circuitBreakerRegistry.circuitBreaker("backendApi").reset();
  }

  @Test
  void aBackendCallCarriesTheBearerAndEveryRelayAndPassesTheBreaker() throws Exception {
    UUID orgUnit = UUID.randomUUID();
    CorrelationContext.set("corr-seam-1");
    ActiveSquadronContext.set(orgUnit);
    ClientIpContext.set("203.0.113.9");
    LocaleContextHolder.setLocale(Locale.GERMANY);
    CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("backendApi");

    webClient.get().uri("/api/v1/seam/relays").retrieve().toBodilessEntity().block(timeout());

    RecordedRequest request = takeRequestFor(backend, "/api/v1/seam/relays");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + BEARER);
    assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("corr-seam-1");
    assertThat(request.getHeader("X-Active-Org-Unit-Id")).isEqualTo(orgUnit.toString());
    assertThat(request.getHeader("Accept-Language")).isEqualTo("de-DE");
    assertThat(request.getHeader("X-Forwarded-For")).isEqualTo("203.0.113.9");
    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
  }

  @Test
  void anOpenBreakerStopsTheCallBeforeTheBackend() {
    CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("backendApi");
    breaker.transitionToForcedOpenState();
    int before = backend.getRequestCount();

    assertThatThrownBy(
            () ->
                webClient
                    .get()
                    .uri("/api/v1/seam/open")
                    .retrieve()
                    .toBodilessEntity()
                    .block(timeout()))
        .isInstanceOf(CallNotPermittedException.class);
    assertThat(backend.getRequestCount()).isEqualTo(before);
  }

  @Test
  void anAbsoluteUrlToAnotherOriginIsRefusedBeforeTheBearerIsResolved() {
    clearInvocations(authorizedClientManager);
    int before = foreign.getRequestCount();

    assertThatThrownBy(
            () ->
                webClient
                    .get()
                    .uri(foreign.url("/steal").toString())
                    .retrieve()
                    .toBodilessEntity()
                    .block(timeout()))
        .isInstanceOf(BackendOriginViolationException.class);
    assertThatThrownBy(
            () ->
                webClient
                    .post()
                    .uri(foreign.url("/steal").uri())
                    .retrieve()
                    .toBodilessEntity()
                    .block(timeout()))
        .isInstanceOf(BackendOriginViolationException.class);

    assertThat(foreign.getRequestCount()).isEqualTo(before);
    verify(authorizedClientManager, never()).authorize(any());
  }

  @Test
  void theKernelSurfacesARefusedOriginAsABackendFailure() {
    clearInvocations(authorizedClientManager);
    int before = foreign.getRequestCount();

    assertThatThrownBy(() -> backendApiClient.get(foreign.url("/steal").toString(), String.class))
        .isInstanceOf(BackendServiceException.class)
        .extracting(Throwable::getCause, InstanceOfAssertFactories.THROWABLE)
        .isInstanceOf(BackendOriginViolationException.class);

    assertThat(foreign.getRequestCount()).isEqualTo(before);
    verify(authorizedClientManager, never()).authorize(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"termsDocumentClient", "sseWebClient", "liveSyncAuthWebClient"})
  void everyOtherBackendClientRefusesAnotherOriginToo(String bean) {
    WebClient client = context.getBean(bean, WebClient.class);
    int before = foreign.getRequestCount();

    assertThatThrownBy(
            () ->
                client
                    .get()
                    .uri(foreign.url("/steal").uri())
                    .retrieve()
                    .toBodilessEntity()
                    .block(timeout()))
        .isInstanceOf(BackendOriginViolationException.class);
    assertThat(foreign.getRequestCount()).isEqualTo(before);
  }

  private static Duration timeout() {
    return Duration.ofSeconds(10);
  }

  private static MockWebServer answeringServer() throws IOException {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            return new MockResponse().setResponseCode(200).setBody("{}");
          }
        });
    server.start();
    return server;
  }

  private static RecordedRequest takeRequestFor(MockWebServer server, String path)
      throws InterruptedException {
    for (int i = 0; i < 20; i++) {
      RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
      if (request == null) {
        break;
      }
      if (path.equals(request.getPath())) {
        return request;
      }
    }
    throw new AssertionError("the backend never saw " + path);
  }

  private static OAuth2AuthorizedClient authorizedClient() {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("frontend")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://idp.invalid/auth")
            .tokenUri("https://idp.invalid/token")
            .build();
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            BEARER,
            Instant.now(),
            Instant.now().plusSeconds(300));
    return new OAuth2AuthorizedClient(registration, "member", token);
  }
}
