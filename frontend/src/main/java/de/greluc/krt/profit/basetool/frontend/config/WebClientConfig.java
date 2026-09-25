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

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.UserLocaleRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.WebClientLoggingFilter;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.Http2AllocationStrategy;
import reactor.netty.http.client.HttpClient;

/** Spring configuration for Web Client. */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

  /**
   * Maximum bytes a single backend response may buffer before the codec aborts with {@code
   * DataBufferLimitException}, sized for the materials price matrix. Cached catalogue loads are
   * single-flighted, so at most one such buffer exists per catalogue.
   */
  private static final int MAX_IN_MEMORY_BYTES = 64 * 1024 * 1024;

  /**
   * How long a backend-facing pooled connection may idle before this side discards it.
   *
   * <p>Must stay well below the backend Tomcat's HTTP/2 keep-alive timeout (20 s), so the client
   * always evicts first and never dispatches onto a connection the server has already closed.
   * Package-private for a test asserting that margin.
   */
  static final java.time.Duration BACKEND_POOL_MAX_IDLE_TIME = java.time.Duration.ofSeconds(10);

  /**
   * Background sweep period for {@link #BACKEND_POOL_MAX_IDLE_TIME}. Half the idle bound, so an
   * expired connection is closed promptly instead of lingering up to a full bound past it — which
   * is what pushed the old pair's effective retention from 20&nbsp;s out to 30.
   */
  private static final java.time.Duration BACKEND_POOL_EVICT_INTERVAL =
      java.time.Duration.ofSeconds(5);

  /**
   * Context-attributes mapper for the {@link DefaultOAuth2AuthorizedClientManager} that yields an
   * empty map, replacing Spring's default.
   *
   * <p>The default copies a request parameter named {@code scope} into the refresh-token grant, so
   * a page filter such as {@code scope=all} made Keycloak reject the refresh with {@code
   * invalid_scope}. Scopes are fixed on the client registration.
   */
  static final Function<OAuth2AuthorizeRequest, Map<String, Object>> NO_REQUEST_DERIVED_ATTRIBUTES =
      authorizeRequest -> Map.of();

  private final AppBackendProperties backendProperties;
  private final AppHttpProperties httpProperties;
  private final WebClientLoggingFilter webClientLoggingFilter;
  private final ActiveSquadronRelayFilter activeSquadronRelayFilter;
  private final UserLocaleRelayFilter userLocaleRelayFilter;
  private final de.greluc.krt.profit.basetool.frontend.logging.ClientIpRelayFilter
      clientIpRelayFilter;
  private final org.springframework.core.env.Environment environment;
  private final SslBundles sslBundles;

  /**
   * Observation registry wired into the hand-built request/response WebClients, which Boot's
   * observation customizer does not reach; records {@code http.client.requests} and propagates
   * trace context when tracing is enabled (REQ-OBS-009).
   */
  private final io.micrometer.observation.ObservationRegistry observationRegistry;

  /**
   * Dedicated connection pool for the OAuth2 token-endpoint calls to Keycloak ({@code
   * authorization_code} and {@code refresh_token}), named {@code frontend-oauth-pool} (ADR-0115).
   *
   * <p>Evicts idle connections after 20 s and sweeps every 10 s, below the edge proxy's keep-alive,
   * so no token call reuses a server-closed socket. Exposes pool metrics.
   */
  private final reactor.netty.resources.ConnectionProvider oauthTokenPool =
      reactor.netty.resources.ConnectionProvider.builder("frontend-oauth-pool")
          .maxConnections(20)
          .maxIdleTime(java.time.Duration.ofSeconds(20))
          .maxLifeTime(java.time.Duration.ofSeconds(60))
          .pendingAcquireTimeout(java.time.Duration.ofSeconds(5))
          .evictInBackground(java.time.Duration.ofSeconds(10))
          .metrics(true)
          .build();

  /**
   * Builds the connector for the backend WebClients.
   *
   * <p>TLS: {@code dev}/{@code test} trust any certificate; other profiles pin the {@code
   * backend-trust} SSL bundle when configured, otherwise use the JVM trust store. On the pinned
   * paths hostname verification is off unless {@code app.http.verify-backend-hostname} is set
   * (REQ-SEC-070, ADR-0211).
   *
   * <p>Protocol: request/response clients use {@code app.http.backend-protocol} (default HTTP/2)
   * with {@code strictConnectionReuse} and at most {@code app.http.max-concurrent-streams} streams
   * per connection, and no channel-level read timeout; streaming stays on HTTP/1.1 (ADR-0161).
   */
  @NotNull
  private ReactorClientHttpConnector connector(boolean streaming) {
    return connector(streaming, "frontend-pool");
  }

  /**
   * Builds a connector with a named connection pool.
   *
   * @param streaming whether this connector serves the SSE relay
   * @param poolName the connection provider's name, which is also its metric tag
   * @return the configured connector
   */
  @NotNull
  private ReactorClientHttpConnector connector(boolean streaming, String poolName) {
    boolean http2 =
        !streaming && httpProperties.backendProtocol() == AppHttpProperties.BackendProtocol.H2;
    try {
      SslContextBuilder builder = SslContextBuilder.forClient();
      java.util.List<String> profiles = java.util.Arrays.asList(environment.getActiveProfiles());
      boolean pinnedTrust = false;
      if (profiles.contains("dev") || profiles.contains("test")) {
        builder = builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        pinnedTrust = true;
      } else {
        try {
          SslBundle bundle = sslBundles.getBundle("backend-trust");
          KeyStore truststore = bundle.getStores().getTrustStore();
          TrustManagerFactory tmf =
              TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          tmf.init(truststore);
          builder = builder.trustManager(tmf);
          pinnedTrust = true;
        } catch (NoSuchSslBundleException ignored) {
        }
      }
      if (http2) {
        builder =
            builder.applicationProtocolConfig(
                new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1));
      }
      SslContext sslContext = builder.build();
      boolean disableHostnameVerification =
          pinnedTrust
              && (profiles.contains("dev")
                  || profiles.contains("test")
                  || !httpProperties.verifyBackendHostname());

      final reactor.netty.resources.ConnectionProvider provider;
      if (streaming) {
        provider =
            reactor.netty.resources.ConnectionProvider.builder(poolName)
                .maxConnections(1000)
                .maxIdleTime(BACKEND_POOL_MAX_IDLE_TIME)
                .pendingAcquireTimeout(java.time.Duration.ofSeconds(10))
                .evictInBackground(BACKEND_POOL_EVICT_INTERVAL)
                .metrics(true)
                .build();
      } else {
        reactor.netty.resources.ConnectionProvider.Builder pool =
            reactor.netty.resources.ConnectionProvider.builder(poolName)
                .maxConnections(100)
                .maxIdleTime(BACKEND_POOL_MAX_IDLE_TIME)
                .maxLifeTime(java.time.Duration.ofSeconds(60))
                .pendingAcquireTimeout(java.time.Duration.ofSeconds(5))
                .evictInBackground(BACKEND_POOL_EVICT_INTERVAL)
                .metrics(true);
        if (http2) {
          pool =
              pool.allocationStrategy(
                  Http2AllocationStrategy.builder()
                      .maxConnections(
                          Math.max(1, 100 / Math.max(1, httpProperties.maxConcurrentStreams())))
                      .maxConcurrentStreams(httpProperties.maxConcurrentStreams())
                      .strictConnectionReuse(true)
                      .build());
        }
        provider = pool.build();
      }

      HttpClient httpClient =
          HttpClient.create(provider)
              .secure(
                  t -> {
                    var spec = t.sslContext(sslContext);
                    if (disableHostnameVerification) {
                      spec.handlerConfigurator(
                          sslHandler -> {
                            SSLParameters params = sslHandler.engine().getSSLParameters();
                            params.setEndpointIdentificationAlgorithm("");
                            sslHandler.engine().setSSLParameters(params);
                          });
                    }
                  })
              .option(
                  ChannelOption.CONNECT_TIMEOUT_MILLIS,
                  Math.toIntExact(httpProperties.connectTimeout().toMillis()));
      if (http2) {
        httpClient = httpClient.protocol(HttpProtocol.H2, HttpProtocol.HTTP11);
      }
      if (streaming) {
        httpClient =
            httpClient.doOnConnected(
                conn ->
                    conn.addHandlerLast(
                        new WriteTimeoutHandler(
                            httpProperties.writeTimeout().toMillis(), TimeUnit.MILLISECONDS)));
      } else {
        httpClient = httpClient.responseTimeout(httpProperties.responseTimeout());
        if (http2) {
          httpClient =
              httpClient.doOnConnected(
                  conn ->
                      conn.addHandlerLast(
                          new WriteTimeoutHandler(
                              httpProperties.writeTimeout().toMillis(), TimeUnit.MILLISECONDS)));
        } else {
          httpClient =
              httpClient.doOnConnected(
                  conn ->
                      conn.addHandlerLast(
                              new ReadTimeoutHandler(
                                  httpProperties.readTimeout().toMillis(), TimeUnit.MILLISECONDS))
                          .addHandlerLast(
                              new WriteTimeoutHandler(
                                  httpProperties.writeTimeout().toMillis(),
                                  TimeUnit.MILLISECONDS)));
        }
      }
      return new ReactorClientHttpConnector(httpClient);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize SSL context", e);
    }
  }

  private ExchangeFilterFunction resilienceFilter(
      String instanceName,
      @NotNull CircuitBreakerRegistry cbRegistry,
      RetryRegistry retryRegistry,
      TimeLimiterRegistry timeLimiterRegistry,
      BulkheadRegistry bulkheadRegistry) {
    CircuitBreaker cb = cbRegistry.circuitBreaker(instanceName);
    Retry retry = retryRegistry.retry(instanceName);
    TimeLimiter tl = timeLimiterRegistry.timeLimiter(instanceName);
    Bulkhead bh = bulkheadRegistry.bulkhead(instanceName);

    return (request, next) ->
        next.exchange(request)
            .flatMap(
                resp -> {
                  if (resp.statusCode().is5xxServerError()) {
                    return resp.createException().flatMap(Mono::error);
                  }
                  return Mono.just(resp);
                })
            .transformDeferred(BulkheadOperator.of(bh))
            .transformDeferred(TimeLimiterOperator.of(tl))
            .transformDeferred(
                mono -> {
                  String method = request.method().name();
                  if ("GET".equals(method)
                      || "HEAD".equals(method)
                      || "OPTIONS".equals(method)
                      || "TRACE".equals(method)) {
                    return mono.transformDeferred(RetryOperator.of(retry));
                  }
                  return mono;
                })
            .transformDeferred(CircuitBreakerOperator.of(cb));
  }

  /**
   * Builds the {@link RestClient} for the OAuth2 token endpoint: Spring Security's default
   * converters and error handler on a reactor-netty {@link HttpClient} bound to {@link
   * #oauthTokenPool} (ADR-0115).
   *
   * <p>Read and write timeouts bound every phase of the exchange, including a stalled request body.
   *
   * @return a {@code RestClient} whose transport is the idle-evicting OAuth pool
   */
  private RestClient oauthTokenRestClient() {
    HttpClient httpClient =
        HttpClient.create(oauthTokenPool)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.toIntExact(httpProperties.connectTimeout().toMillis()))
            .responseTimeout(httpProperties.responseTimeout())
            .doOnConnected(
                conn ->
                    conn.addHandlerLast(
                            new ReadTimeoutHandler(
                                httpProperties.readTimeout().toMillis(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(
                            new WriteTimeoutHandler(
                                httpProperties.writeTimeout().toMillis(), TimeUnit.MILLISECONDS)));
    return RestClient.builder()
        .configureMessageConverters(
            converters ->
                converters
                    .disableDefaults()
                    .addCustomConverter(new FormHttpMessageConverter())
                    .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
        .requestFactory(new ReactorClientHttpRequestFactory(httpClient))
        .build();
  }

  /**
   * Token-response client for the {@code refresh_token} grant on {@link #oauthTokenPool}, used by
   * {@link #authorizedClientManager} (ADR-0115).
   *
   * @return the refresh-token response client on the idle-evicting OAuth pool
   */
  @NotNull
  @Bean
  public OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>
      oauthRefreshTokenResponseClient() {
    RestClientRefreshTokenTokenResponseClient client =
        new RestClientRefreshTokenTokenResponseClient();
    client.setRestClient(oauthTokenRestClient());
    return client;
  }

  /**
   * Token-response client for the {@code authorization_code} login exchange on {@link
   * #oauthTokenPool}, wired into {@code SecurityConfig} (ADR-0115).
   *
   * @return the authorization-code response client on the idle-evicting OAuth pool
   */
  @NotNull
  @Bean
  public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
      oauthAuthorizationCodeTokenResponseClient() {
    RestClientAuthorizationCodeTokenResponseClient client =
        new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(oauthTokenRestClient());
    return client;
  }

  /**
   * OAuth2 authorized-client manager for the authenticated backend WebClient, wrapped in a {@link
   * SingleFlightAuthorizedClientManager} so concurrent calls trigger only one refresh-token grant
   * per expiry (REQ-SEC-012, ADR-0019). The refresh grant runs on the {@link
   * #oauthRefreshTokenResponseClient() pool-hardened client}.
   *
   * @param clientRegistrationRepository the OAuth2 client registrations (Keycloak)
   * @param authorizedClientRepository the session-backed authorized-client store
   * @param oauthRefreshTokenResponseClient the pool-hardened refresh-token response client
   * @return the single-flight authorized-client manager
   */
  @NotNull
  @Bean
  public OAuth2AuthorizedClientManager authorizedClientManager(
      ClientRegistrationRepository clientRegistrationRepository,
      OAuth2AuthorizedClientRepository authorizedClientRepository,
      OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>
          oauthRefreshTokenResponseClient) {

    OAuth2AuthorizedClientProvider authorizedClientProvider =
        OAuth2AuthorizedClientProviderBuilder.builder()
            .authorizationCode()
            .refreshToken(
                refresh -> refresh.accessTokenResponseClient(oauthRefreshTokenResponseClient))
            .build();

    DefaultOAuth2AuthorizedClientManager delegate =
        new DefaultOAuth2AuthorizedClientManager(
            clientRegistrationRepository, authorizedClientRepository);
    delegate.setAuthorizedClientProvider(authorizedClientProvider);
    delegate.setContextAttributesMapper(NO_REQUEST_DERIVED_ATTRIBUTES);

    return new SingleFlightAuthorizedClientManager(delegate);
  }

  /**
   * Authenticated WebClient against the backend: {@value #MAX_IN_MEMORY_BYTES}-byte max in-memory
   * codec, Resilience4j chain (timeout, retry, circuit breaker, bulkhead), correlation-id
   * propagation, OAuth2 bearer relay, and the {@code Accept} list {@link #backendAcceptTypes()}
   * derives from {@code app.http.codec} — CBOR first and JSON second by default (REQ-API-011).
   */
  @Bean
  public WebClient webClient(
      OAuth2AuthorizedClientManager authorizedClientManager,
      CircuitBreakerRegistry cbRegistry,
      RetryRegistry retryRegistry,
      TimeLimiterRegistry timeLimiterRegistry,
      BulkheadRegistry bulkheadRegistry) {
    ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2Client =
        new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
    oauth2Client.setDefaultOAuth2AuthorizedClient(true);
    oauth2Client.setDefaultClientRegistrationId("keycloak");

    ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
            .build();

    return WebClient.builder()
        .exchangeStrategies(strategies)
        .clientConnector(connector(false, "frontend-pool"))
        .observationRegistry(observationRegistry)
        .apply(oauth2Client.oauth2Configuration())
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(activeSquadronRelayFilter.relayActiveSquadron())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .filter(clientIpRelayFilter.relayClientIp())
        .filter(webClientLoggingFilter.callLogging())
        .filter(
            resilienceFilter(
                "backendApi", cbRegistry, retryRegistry, timeLimiterRegistry, bulkheadRegistry))
        .defaultHeaders(headers -> headers.setAccept(backendAcceptTypes()))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }

  /**
   * Returns the {@code Accept} list for backend reads (ADR-0161): CBOR first, then JSON, when
   * {@code app.http.codec} selects CBOR. Used by all request/response clients; request bodies stay
   * JSON.
   *
   * @return the media types this client accepts from the backend, most preferred first
   */
  private java.util.List<MediaType> backendAcceptTypes() {
    if (httpProperties.codec() == AppHttpProperties.BackendCodec.CBOR) {
      return java.util.List.of(MediaType.APPLICATION_CBOR, MediaType.APPLICATION_JSON);
    }
    return java.util.List.of(MediaType.APPLICATION_JSON);
  }

  /**
   * The only anonymous WebClient, used solely to fetch the Terms-of-Use document for the public
   * {@code /terms} page (REQ-SEC-028). Same resilience and logging chain as {@link #webClient},
   * without the OAuth2 bearer relay.
   */
  @Bean
  public WebClient termsDocumentClient(
      CircuitBreakerRegistry cbRegistry,
      RetryRegistry retryRegistry,
      TimeLimiterRegistry timeLimiterRegistry,
      BulkheadRegistry bulkheadRegistry) {
    ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
            .build();

    return WebClient.builder()
        .exchangeStrategies(strategies)
        .clientConnector(connector(false, "frontend-terms-pool"))
        .observationRegistry(observationRegistry)
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .filter(clientIpRelayFilter.relayClientIp())
        .filter(webClientLoggingFilter.callLogging())
        .filter(
            resilienceFilter(
                "backendApi", cbRegistry, retryRegistry, timeLimiterRegistry, bulkheadRegistry))
        .defaultHeaders(headers -> headers.setAccept(backendAcceptTypes()))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }

  /**
   * Streaming WebClient for the notification SSE relay (REQ-NOTIF-010).
   *
   * <p>Relays the correlation, org-unit, locale and client-IP headers like {@link #webClient}, but
   * has no Resilience4j chain, no response/read timeouts, no observation and no OAuth2 exchange
   * filter, so the long-lived relay can never trigger a refresh-token grant (REQ-SEC-012). The
   * bearer is set as a plain header by the caller.
   *
   * @return the streaming WebClient
   */
  @Bean
  public WebClient sseWebClient() {
    return WebClient.builder()
        .clientConnector(connector(true, "frontend-sse-pool"))
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(activeSquadronRelayFilter.relayActiveSquadron())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .filter(clientIpRelayFilter.relayClientIp())
        .defaultHeaders(
            headers -> headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM)))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }

  /**
   * WebClient for the {@code /ws/sync} subscribe-authorization probe (REQ-FE-015, ADR-0094).
   *
   * <p>Has no OAuth2 exchange filter and no Resilience4j chain; the caller sets the bearer and
   * org-unit pin as headers. Keeps the normal timeouts so a probe fails fast.
   *
   * @return the subscribe-authorization WebClient
   */
  @Bean
  public WebClient liveSyncAuthWebClient() {
    return WebClient.builder()
        .clientConnector(connector(false, "frontend-livesync-probe-pool"))
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .filter(clientIpRelayFilter.relayClientIp())
        .defaultHeaders(headers -> headers.setAccept(backendAcceptTypes()))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }
}
