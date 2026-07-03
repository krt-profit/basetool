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
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Spring configuration for Web Client. */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

  /**
   * Max bytes a single backend response may buffer in memory before the reactive codec aborts with
   * {@code DataBufferLimitException}. Sized for the heaviest read path — the materials trade matrix
   * ({@code /api/v1/materials/matrix?size=100000}) returns one verbose row per material×terminal
   * price and grows with the UEX catalog; at 16 MB a large universe tipped the buffer and the
   * overview page failed outright. 64 MB leaves generous headroom (still well inside the frontend's
   * ~576 MB heap, even with the 10-minute matrix cache holding one such response).
   */
  private static final int MAX_IN_MEMORY_BYTES = 64 * 1024 * 1024;

  /**
   * Context-attributes mapper for the {@link DefaultOAuth2AuthorizedClientManager} that yields an
   * empty map, deliberately replacing Spring's request-parameter-derived default.
   *
   * <p>Spring's {@code DEFAULT_CONTEXT_ATTRIBUTES_MAPPER} copies an HTTP request parameter
   * literally named {@code scope} into {@code
   * OAuth2AuthorizationContext.REQUEST_SCOPE_ATTRIBUTE_NAME}, and the {@code
   * RefreshTokenOAuth2AuthorizedClientProvider} then forwards those values to Keycloak as the
   * requested scope of the refresh-token grant. The job-orders page's "Staffel" filter submits
   * {@code scope=all|mine} (the same own-vs-all squadron concept the refinery list exposes), so
   * whenever a token refresh happens to coincide with such a request Keycloak rejects the grant
   * with {@code invalid_scope ("Invalid scopes: all"/"Invalid scopes: mine")}; the whole SSO
   * session is then bounced into re-authentication and the user sees "Fehler beim Laden". This is a
   * refresh failure mode independent of refresh-token rotation/reuse detection (REQ-SEC-012), which
   * is why disabling rotation did not stop it. The frontend never requests scopes dynamically —
   * they are fixed on the {@code keycloak} client registration — so severing the request-parameter
   * &rarr; OAuth-scope path entirely is both correct and the complete fix.
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
  private final de.greluc.krt.profit.basetool.frontend.logging.GuestEditTokenRelayFilter
      guestEditTokenRelayFilter;
  private final org.springframework.core.env.Environment environment;
  private final SslBundles sslBundles;

  /**
   * Micrometer observation registry wired into the request/response WebClients (REQ-OBS-009, epic
   * #936 Phase 1b). These clients are hand-built via {@code WebClient.builder()} (not the
   * auto-configured {@code WebClient.Builder} bean), so Boot's observation customizer does not
   * apply — without this explicit wiring no {@code http.client.requests} metrics are recorded and,
   * with tracing enabled, no {@code traceparent} header would propagate to the backend. With
   * tracing disabled (the default) the registry only feeds metrics; no tracing machinery runs.
   */
  private final io.micrometer.observation.ObservationRegistry observationRegistry;

  /**
   * Builds the Netty SSL context for the backend WebClient. Three behaviours, picked by active
   * profile and presence of a configured SSL bundle:
   *
   * <ul>
   *   <li>{@code dev} / {@code test}: {@link InsecureTrustManagerFactory} (= accept any
   *       certificate). The bundled bootstrap {@code keystore.p12} cert is self-signed and the test
   *       docker stack uses an ephemeral cert; trust validation would only get in the way.
   *   <li>Other profiles WITH a {@code backend-trust} Spring SSL bundle configured (production
   *       default — the bundle is defined in {@code application-prod.yml} and points at the same
   *       bind-mounted {@code keystore.p12} that Tomcat uses for the frontend's own HTTPS
   *       listener): the bundle's truststore is loaded and pinned as the only valid trust anchor
   *       for the backend WebClient. This is what makes the {@code https://backend:11261} call work
   *       when the backend serves a self-signed cert — without restoring the indiscriminate {@code
   *       InsecureTrustManagerFactory} that the 2026-05-20 audit (finding M-13) closed.
   *   <li>Other profiles WITHOUT the bundle (e.g. a future operator fronts the backend with a
   *       publicly-trusted cert): falls back to the default JVM trust store. No MITM exposure
   *       because the cert chain must validate against a well-known CA.
   * </ul>
   *
   * <h3>Hostname verification</h3>
   *
   * <p>On the two "pinned trust" paths (dev/test InsecureTrustManagerFactory and prod {@code
   * backend-trust} bundle) endpoint identification is explicitly disabled via {@link
   * SSLParameters#setEndpointIdentificationAlgorithm}. The trust set is already pinned to exactly
   * the cert we ship (or accept-any in dev), so the hostname check only ever defends against the
   * pinned cert being presented under a different hostname — which an attacker can only do by
   * stealing the private key from {@code keystore.p12}, in which case the entire trust boundary has
   * already collapsed. Disabling the check lets the prod stack work even when the operator's cert
   * was generated without {@code dns:backend} / {@code dns:frontend} in its SAN list (the Docker
   * network aliases used by service-to-service traffic), without weakening security further than
   * M-13 deliberately allowed. The fallback (default JVM trust store) keeps hostname verification
   * enabled — that path validates against a well-known CA pool where the hostname check is the only
   * thing tying the cert to the target host.
   */
  private ReactorClientHttpConnector connector(boolean streaming) {
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
          // No `backend-trust` SSL bundle defined for the active profile —
          // fall back to the JVM default trust store. This path supports
          // deployments where the backend is fronted by a publicly-trusted
          // cert (Let's Encrypt, internal corporate CA already in cacerts,
          // etc.) and no per-deployment truststore configuration is needed.
          // pinnedTrust stays false so hostname verification remains enabled
          // on this fallback path.
        }
      }
      SslContext sslContext = builder.build();
      boolean disableHostnameVerification = pinnedTrust;

      // Pool sized at 100 connections: a single mission-detail render now fans out to four
      // parallel backend calls via `ParallelPageLoader`, so ~25 concurrent users can exhaust a
      // 50-slot pool. 100 gives comfortable headroom; the `pendingAcquireTimeout` is raised in
      // step from 5 s to 10 s so a transient backend slowdown queues rather than failing fast,
      // which the page render cannot recover from gracefully. Idle/life timeouts unchanged.
      reactor.netty.resources.ConnectionProvider provider =
          reactor.netty.resources.ConnectionProvider.builder("frontend-pool")
              .maxConnections(100)
              .maxIdleTime(java.time.Duration.ofSeconds(20))
              .maxLifeTime(java.time.Duration.ofSeconds(60))
              .pendingAcquireTimeout(java.time.Duration.ofSeconds(10))
              .evictInBackground(java.time.Duration.ofSeconds(10))
              .build();

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
      if (streaming) {
        // SSE relay: a long-lived response delivering sparse events. A response / read timeout
        // would sever the stream between events, so neither is applied (the backend heartbeat
        // keeps the connection warm); only a write timeout guards the outbound request.
        httpClient =
            httpClient.doOnConnected(
                conn ->
                    conn.addHandlerLast(
                        new WriteTimeoutHandler(
                            httpProperties.writeTimeout().toMillis(), TimeUnit.MILLISECONDS)));
      } else {
        // Request gzip on the regular request/response path: send `Accept-Encoding` and decompress
        // transparently. The backend already advertises `server.compression.enabled=true` for
        // application/json, so the heavy read payloads (the materials trade matrix, full order /
        // hangar lists) travel the frontend↔backend hop compressed. NOT applied on the streaming
        // path (see the `if (streaming)` branch): the SSE relay is `text/event-stream` — outside
        // the
        // backend's compression mime-types anyway — and per-event gzip would only buffer the
        // stream.
        // The in-memory codec limit is unaffected: it bounds the decompressed body, not the wire
        // size.
        httpClient =
            httpClient
                .compress(true)
                .responseTimeout(httpProperties.responseTimeout())
                .doOnConnected(
                    conn ->
                        conn.addHandlerLast(
                                new ReadTimeoutHandler(
                                    httpProperties.readTimeout().toMillis(), TimeUnit.MILLISECONDS))
                            .addHandlerLast(
                                new WriteTimeoutHandler(
                                    httpProperties.writeTimeout().toMillis(),
                                    TimeUnit.MILLISECONDS)));
      }
      return new ReactorClientHttpConnector(httpClient);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize SSL context", e);
    }
  }

  private ExchangeFilterFunction resilienceFilter(
      String instanceName,
      CircuitBreakerRegistry cbRegistry,
      RetryRegistry retryRegistry,
      TimeLimiterRegistry timeLimiterRegistry,
      BulkheadRegistry bulkheadRegistry) {
    CircuitBreaker cb = cbRegistry.circuitBreaker(instanceName);
    Retry retry = retryRegistry.retry(instanceName);
    TimeLimiter tl = timeLimiterRegistry.timeLimiter(instanceName);
    Bulkhead bh = bulkheadRegistry.bulkhead(instanceName);

    return (request, next) ->
        next.exchange(request)
            // Map HTTP 4xx/5xx to exceptions BEFORE applying resilience operators,
            // so Retry/CircuitBreaker can react properly
            .flatMap(
                resp -> {
                  if (resp.statusCode().is4xxClientError()
                      || resp.statusCode().is5xxServerError()) {
                    return resp.createException().flatMap(Mono::error);
                  }
                  return reactor.core.publisher.Mono.just(resp);
                })
            // Apply operators (order: bulkhead -> timeLimiter -> retry -> circuitBreaker)
            // Retry before CB so all retry attempts are executed against backend;
            // CB will evaluate across top-level calls.
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
   * OAuth2 authorised-client manager providing {@code authorization_code} and {@code refresh_token}
   * flows for the authenticated backend WebClient.
   *
   * <p>The {@code DefaultOAuth2AuthorizedClientManager} is wrapped in a {@link
   * SingleFlightAuthorizedClientManager} so the parallel backend calls a single page render fans
   * out (page + notification SSE relay + unread-count poll) collapse into <b>one</b> refresh-token
   * grant per expiry window. Without it, concurrent requests each replay the same refresh token and
   * Keycloak's reuse detection revokes the whole token family, surfacing as a flood of {@code
   * client_authorization_required} until the user logs in again (REQ-SEC-012, ADR-0019).
   */
  @Bean
  public OAuth2AuthorizedClientManager authorizedClientManager(
      ClientRegistrationRepository clientRegistrationRepository,
      OAuth2AuthorizedClientRepository authorizedClientRepository) {

    OAuth2AuthorizedClientProvider authorizedClientProvider =
        OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build();

    DefaultOAuth2AuthorizedClientManager delegate =
        new DefaultOAuth2AuthorizedClientManager(
            clientRegistrationRepository, authorizedClientRepository);
    delegate.setAuthorizedClientProvider(authorizedClientProvider);
    // Stop the servlet request's parameters (notably the "Staffel" filter's scope=all|mine) from
    // leaking into the refresh-token grant as the OAuth2 requested scope (REQ-SEC-012).
    delegate.setContextAttributesMapper(NO_REQUEST_DERIVED_ATTRIBUTES);

    return new SingleFlightAuthorizedClientManager(delegate);
  }

  /**
   * Authenticated WebClient against the backend: {@value #MAX_IN_MEMORY_BYTES}-byte max in-memory
   * codec, Resilience4j chain (timeout, retry, circuit breaker, bulkhead), correlation-id
   * propagation, OAuth2 bearer relay, defaults to {@code Accept: application/json}.
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
        .clientConnector(connector(false))
        .observationRegistry(observationRegistry)
        .apply(oauth2Client.oauth2Configuration())
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(activeSquadronRelayFilter.relayActiveSquadron())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .filter(clientIpRelayFilter.relayClientIp())
        .filter(guestEditTokenRelayFilter.relayGuestEditToken())
        .filter(webClientLoggingFilter.callLogging())
        .filter(
            resilienceFilter(
                "backendApi", cbRegistry, retryRegistry, timeLimiterRegistry, bulkheadRegistry))
        .defaultHeaders(headers -> headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON)))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }

  /**
   * Anonymous WebClient against the backend's public endpoints. Same resilience and logging chain
   * as {@link #webClient} but without OAuth2 bearer relay.
   */
  @Bean
  public WebClient publicWebClient(
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
        .clientConnector(connector(false))
        .observationRegistry(observationRegistry)
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .filter(clientIpRelayFilter.relayClientIp())
        // Anonymous guest path: relay the per-row guest edit token so a guest can edit/withdraw
        // their own sign-up (security audit M1 / REQ-SEC-018).
        .filter(guestEditTokenRelayFilter.relayGuestEditToken())
        .filter(webClientLoggingFilter.callLogging())
        .filter(
            resilienceFilter(
                "backendApi", cbRegistry, retryRegistry, timeLimiterRegistry, bulkheadRegistry))
        .defaultHeaders(headers -> headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON)))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }

  /**
   * Streaming WebClient for the notification SSE relay (REQ-NOTIF-010). Relays the correlation /
   * active-org-unit / locale headers like {@link #webClient}, but deliberately omits both the
   * Resilience4j chain (its 5-second {@code TimeLimiter} and retry would sever a long-lived stream)
   * and the response / read timeouts (see {@link #connector(boolean)}) <b>and</b> the OAuth2 {@code
   * oauth2Configuration()} exchange filter.
   *
   * <p>Dropping the OAuth2 filter is load-bearing for REQ-SEC-012 / ADR-0019. With the filter
   * applied, attaching an authorized client routes the call into {@code
   * ServletOAuth2AuthorizedClientExchangeFilterFunction.reauthorizeClient}, which invokes {@code
   * OAuth2AuthorizedClientManager.authorize(...)} <i>unconditionally</i> — so on a stale/empty
   * single-flight cache this 30-minute async relay could drive a refresh-token grant (and write the
   * rotated client back to the session) against the snapshot it captured at stream-open, replaying
   * a refresh token Keycloak's reuse detection then revokes the whole SSO session for. Without the
   * filter the relay can never reach {@code authorize}; {@code NotificationPageController.stream}
   * resolves the bearer read-only and sets it as a plain {@code Authorization} header instead, so
   * the relay is structurally refresh-incapable rather than depending on a warm cache. Used only by
   * the frontend stream relay; all request/response traffic still goes through {@link #webClient}.
   *
   * <p>Also deliberately NOT wired to the observation registry (REQ-OBS-009): a ~30-minute SSE
   * relay would hold a single client observation/span open for the whole stream, skewing the
   * latency metrics and delaying span export; the correlation-id relay already covers debuggability
   * here.
   *
   * @return the streaming WebClient
   */
  @Bean
  public WebClient sseWebClient() {
    return WebClient.builder()
        .clientConnector(connector(true))
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(activeSquadronRelayFilter.relayActiveSquadron())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .defaultHeaders(
            headers -> headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM)))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }
}
