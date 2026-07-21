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
   * overview page failed outright. 64 MB leaves headroom for one such response.
   *
   * <p>Only ONE such response is ever buffered concurrently per catalogue: every {@code
   * BackendApiClient.getCached} overload is {@code @Cacheable(sync = true)} (#1154), so Caffeine
   * single-flights the loader and a cold-cache stampede (N users on the materials overview right
   * after a deploy / domain evict) collapses to a single in-flight fetch instead of N parallel
   * multi-ten-MB buffers + their decoded DTO graphs — which, on the ~768 MB heap the compose stack
   * grants ({@code mem_limit: 1024m} × {@code MaxRAMPercentage=75}), could otherwise OOM-kill the
   * single frontend instance and drop every live SSE relay / presence socket at once. If another
   * &gt;10 MB catalogue is added, additionally guard the matrix path with a small semaphore.
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
   * Dedicated reactor-netty connection pool for the OAuth2 token-endpoint backchannel to Keycloak
   * (the {@code authorization_code} exchange at login and the recurring {@code refresh_token}
   * grant).
   *
   * <p>Spring Security's default {@code RestClient}-based token-response clients run on
   * reactor-netty's <b>global</b> connection pool, which has <b>no idle eviction</b>. The token
   * endpoint ({@code spring.security.oauth2.client.provider.keycloak.token-uri}, derived from the
   * public {@code issuer-uri}) is reached over the public NPM edge, which reaps idle keep-alive
   * sockets after ~60&ndash;75&nbsp;s; a refresh grant that reuses such a server-closed socket
   * fails with reactor-netty's {@code PrematureCloseException}, surfacing as an intermittent
   * auth-path 5xx / forced re-login.
   *
   * <p>This pool evicts idle connections after 20&nbsp;s &mdash; comfortably <b>below</b> the
   * upstream keep-alive &mdash; and sweeps them in the background every 10&nbsp;s, so a stale
   * socket is discarded before it can be handed to a token call. {@code metrics(true)} exposes
   * {@code reactor.netty.connection.provider.*} tagged {@code frontend-oauth-pool}, mirroring the
   * {@code frontend-pool} / {@code frontend-sse-pool} request pools. It is a pure transport swap:
   * the token request, the refresh-token replay semantics and the REQ-SEC-012 single-flight
   * behaviour are unchanged (ADR-0115).
   *
   * <p>Initialised at the field (not via the Lombok constructor): a constant-config shared resource
   * with no injected dependency, so {@code @RequiredArgsConstructor} leaves it out of the generated
   * constructor.
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

      // The connection pool differs by traffic shape: request/response traffic reuses a bounded
      // pool, while the SSE relay needs a far larger one because each live stream holds its
      // connection for the whole time the viewer's page is open (ADR-0078 scale-hardening).
      final reactor.netty.resources.ConnectionProvider provider;
      if (streaming) {
        // SSE relay pool. Each viewing browser holds one long-lived (~30-min) frontend->backend
        // stream for as long as its page is open, and a streaming connection is never returned to
        // the pool until the stream ends -- so maxConnections here is a hard ceiling on *concurrent
        // live viewers*, not a connection-reuse cap. At the request pool's 100 the 101st concurrent
        // viewer's relay would block on pendingAcquireTimeout and then fail, silently dropping that
        // user's live notification push. Sized at 1000 so a full mission audience (200+ viewers)
        // has ample headroom on the 16 GB host; mostly-idle long-lived TCP sockets are cheap. No
        // maxLifeTime: a 30-minute stream must never be treated as "too old" to keep alive. The
        // 10 s pendingAcquireTimeout is live here (unlike the request pool's): the SSE relay runs
        // through NO Resilience4j TimeLimiter, so this is the only bound on an acquire wait once
        // the
        // 1000 ceiling is hit. metrics(true) exposes reactor.netty.connection.provider.* so pool
        // saturation -- the silent 1001st-viewer drop -- is visible on the dashboard and alertable.
        provider =
            reactor.netty.resources.ConnectionProvider.builder("frontend-sse-pool")
                .maxConnections(1000)
                .maxIdleTime(java.time.Duration.ofSeconds(30))
                .pendingAcquireTimeout(java.time.Duration.ofSeconds(10))
                .evictInBackground(java.time.Duration.ofSeconds(30))
                .metrics(true)
                .build();
      } else {
        // Request/response pool sized at 100 connections: a single mission-detail render now fans
        // out to four parallel backend calls via `ParallelPageLoader`, so ~25 concurrent users can
        // exhaust a 50-slot pool. 100 gives comfortable headroom. pendingAcquireTimeout is 5 s to
        // MATCH the backendApi Resilience4j TimeLimiter (timeoutDuration 5 s, cancelRunningFuture),
        // which wraps the whole exchange including pool acquisition: a longer acquire wait here was
        // dead config (the TimeLimiter cancels the exchange at 5 s before it could ever elapse) and
        // let the two budgets diverge. Keeping them equal means a saturation wait fails fast at the
        // caller's real patience instead of the backend later running a query for a request the
        // frontend already abandoned. metrics(true) exposes reactor.netty.connection.provider.* for
        // pool observability. Idle/life timeouts unchanged.
        provider =
            reactor.netty.resources.ConnectionProvider.builder("frontend-pool")
                .maxConnections(100)
                .maxIdleTime(java.time.Duration.ofSeconds(20))
                .maxLifeTime(java.time.Duration.ofSeconds(60))
                .pendingAcquireTimeout(java.time.Duration.ofSeconds(5))
                .evictInBackground(java.time.Duration.ofSeconds(10))
                .metrics(true)
                .build();
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
            // Surface ONLY 5xx server errors to the resilience operators below. A 4xx is a
            // client-side signal — a 429 rate-limit, a 404, a 409 conflict — NOT a backend-health
            // fault: retrying it wastes the budget (and for 429 ignores Retry-After, piling load
            // onto an already-throttled backend), and recording it as a circuit-breaker failure
            // would trip the shared 'backendApi' breaker OPEN and cascade a per-request client
            // error
            // into a total frontend outage (the 429 storm of 2026-07-06, ADR-0077). 4xx responses
            // therefore pass through untouched; retrieve() turns them into a
            // WebClientResponseException
            // downstream, where handleWebClientException maps each to its proper per-call
            // user-facing
            // result. Transport failures (timeout, connection reset) already arrive as errors and
            // still reach the operators. Mirrors the ingest gateway's breaker, which likewise never
            // opens on an HTTP status error.
            .flatMap(
                resp -> {
                  if (resp.statusCode().is5xxServerError()) {
                    return resp.createException().flatMap(Mono::error);
                  }
                  return Mono.just(resp);
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
   * Builds a {@link RestClient} for the OAuth2 token endpoint that replicates Spring Security's
   * default token-response-client HTTP stack &mdash; a {@link FormHttpMessageConverter} for the
   * {@code application/x-www-form-urlencoded} grant request, an {@link
   * OAuth2AccessTokenResponseHttpMessageConverter} for the token JSON, and an {@link
   * OAuth2ErrorResponseErrorHandler} &mdash; and swaps <b>only</b> the transport for a
   * reactor-netty {@link HttpClient} bound to the idle-evicting {@link #oauthTokenPool}. Only
   * {@code setRestClient} is overridden on the token client, so its own parameters/headers
   * converters are untouched: this is a transport-only change, neutral to the refresh-token replay
   * semantics of REQ-SEC-012 (ADR-0115).
   *
   * <p>No explicit {@code secure(...)} call: reactor-netty negotiates TLS per request from the URL
   * scheme, so the {@code https} Keycloak endpoint uses the default system trust store (its edge
   * cert is publicly trusted) while an {@code http} endpoint (e.g. a test double) stays plaintext.
   * Both token clients share the single {@link #oauthTokenPool}, so pool metrics stay one series.
   *
   * @return a {@code RestClient} whose transport is the idle-evicting OAuth pool
   */
  private RestClient oauthTokenRestClient() {
    HttpClient httpClient =
        HttpClient.create(oauthTokenPool)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.toIntExact(httpProperties.connectTimeout().toMillis()))
            .responseTimeout(httpProperties.responseTimeout());
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
   * Token-response client for the {@code refresh_token} grant, hardened onto {@link
   * #oauthTokenPool}.
   *
   * <p>This is the hot Keycloak backchannel: every active session refreshes its access token on
   * expiry, so it is the path most exposed to reusing a stale, upstream-reaped keep-alive socket
   * (the {@code PrematureClose} hairpin). Consumed by {@link #authorizedClientManager} as the
   * {@code refreshToken} provider's token client, replacing Spring Security's default global-pool
   * client (ADR-0115).
   *
   * @return the refresh-token response client on the idle-evicting OAuth pool
   */
  @Bean
  public OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>
      oauthRefreshTokenResponseClient() {
    RestClientRefreshTokenTokenResponseClient client =
        new RestClientRefreshTokenTokenResponseClient();
    client.setRestClient(oauthTokenRestClient());
    return client;
  }

  /**
   * Token-response client for the {@code authorization_code} grant (the login token exchange),
   * hardened onto {@link #oauthTokenPool}.
   *
   * <p>Wired into the security filter chain via {@code
   * oauth2Login().tokenEndpoint().accessTokenResponseClient(...)} ({@code SecurityConfig}) so the
   * interactive login exchange no longer runs on reactor-netty's un-evicting global pool either
   * &mdash; closing the same {@code PrematureClose} hairpin on the login path (ADR-0115).
   *
   * @return the authorization-code response client on the idle-evicting OAuth pool
   */
  @Bean
  public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
      oauthAuthorizationCodeTokenResponseClient() {
    RestClientAuthorizationCodeTokenResponseClient client =
        new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(oauthTokenRestClient());
    return client;
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
   *
   * <p>The {@code refresh_token} provider is given the {@link #oauthRefreshTokenResponseClient()
   * pool-hardened refresh-token response client} (ADR-0115) so the recurring refresh grant runs on
   * the idle-evicting {@link #oauthTokenPool} instead of reactor-netty's un-evicting global pool.
   * This is a transport swap only; the single-flight and no-request-scope guards above are
   * unchanged.
   *
   * @param clientRegistrationRepository the OAuth2 client registrations (Keycloak)
   * @param authorizedClientRepository the session-backed authorized-client store
   * @param oauthRefreshTokenResponseClient the pool-hardened refresh-token response client
   * @return the single-flight authorized-client manager
   */
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
   * active-org-unit / locale / client-IP headers like {@link #webClient}, but deliberately omits
   * both the Resilience4j chain (its 5-second {@code TimeLimiter} and retry would sever a
   * long-lived stream) and the response / read timeouts (see {@link #connector(boolean)})
   * <b>and</b> the OAuth2 {@code oauth2Configuration()} exchange filter.
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
   * <p>The {@code X-Forwarded-For} client-IP relay is applied here just as on {@link #webClient}
   * (REQ-SEC-011): without it every viewer's stream — and every browser reconnect after a frontend
   * redeploy — is attributed to the one frontend-container IP and shares a single org-wide per-IP
   * rate-limit bucket, so a reconnect burst can trip the shared limit and blank live push for
   * everyone. The guest-edit-token relay is intentionally omitted: the notification stream is an
   * authenticated-member surface with no anonymous guest path.
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
        .filter(clientIpRelayFilter.relayClientIp())
        .defaultHeaders(
            headers -> headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM)))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }

  /**
   * Backend WebClient for the {@code /ws/sync} subscribe-authorization probe (REQ-FE-015,
   * ADR-0094).
   *
   * <p>Deliberately carries <b>no</b> OAuth2 exchange filter: a subscribe is authorized on a
   * WebSocket message / auth-executor thread that has no servlet request context, so {@code
   * ServletOAuth2AuthorizedClientExchangeFilterFunction} could not resolve a bearer there anyway —
   * and, exactly as for {@link #sseWebClient}, letting it reach {@code
   * OAuth2AuthorizedClientManager.authorize(...)} against a snapshot token could drive a
   * refresh-token grant that Keycloak's reuse detection then punishes (REQ-SEC-012). {@code
   * LiveSyncSubscriptionAuthorizer} therefore sets the captured bearer and active-org-unit pin as
   * explicit headers instead. Unlike {@link #sseWebClient} this keeps the normal connect/read
   * timeouts ({@link #connector(boolean)} with {@code false}) — an auth probe is a short request,
   * and a timeout must abort quickly and fail the subscribe open — and does not need the
   * Resilience4j chain (a one-shot probe with an explicit block timeout). The correlation-id /
   * locale / client-IP relays are applied for parity with the other clients; the guest-edit-token
   * relay is omitted (a {@code /ws/sync} socket is an authenticated-member surface).
   *
   * @return the subscribe-authorization WebClient
   */
  @Bean
  public WebClient liveSyncAuthWebClient() {
    return WebClient.builder()
        .clientConnector(connector(false))
        .filter(webClientLoggingFilter.correlationIdPropagation())
        .filter(userLocaleRelayFilter.relayUserLocale())
        .filter(clientIpRelayFilter.relayClientIp())
        .defaultHeaders(headers -> headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON)))
        .baseUrl(backendProperties.backendUrl())
        .build();
  }
}
