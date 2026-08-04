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

package de.greluc.krt.profit.basetool.ingest.config;

import de.greluc.krt.profit.basetool.ingest.logging.WebClientLoggingFilter;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Builds the single {@link WebClient} the gateway uses to relay an ingest call to the internal
 * backend. The bearer token and per-request headers are attached per call in {@code
 * BackendImportClient}, not here, because each forward carries the caller's own token.
 *
 * <p>TLS trust mirrors the frontend's audited approach (finding M-13) so the {@code
 * https://backend:11261} self-signed call works without a global trust-all. In {@code dev}/{@code
 * test} the ephemeral docker cert is trusted via {@link InsecureTrustManagerFactory} (trust pinned
 * per connector, hostname verification off). In other profiles a configured {@code backend-trust}
 * SSL bundle becomes the only trust anchor (hostname verification off, since the service-alias cert
 * lacks a matching SAN); with no such bundle it falls back to the default JVM trust store with
 * hostname verification left ON (publicly-trusted / corporate-CA backend cert).
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

  private final IngestProperties ingestProperties;
  private final Environment environment;
  private final SslBundles sslBundles;

  /**
   * Micrometer observation registry wired into the backend-relay WebClient (REQ-OBS-009, epic #936
   * Phase 1b). The client is hand-built via {@code WebClient.builder()} (not the auto-configured
   * {@code WebClient.Builder} bean), so Boot's observation customizer does not apply — without this
   * explicit wiring no {@code http.client.requests} metrics are recorded and, with tracing enabled,
   * no {@code traceparent} header would propagate to the backend. With tracing disabled (the
   * default) the registry only feeds metrics; no tracing machinery runs.
   */
  private final io.micrometer.observation.ObservationRegistry observationRegistry;

  /** Emits the one-line-per-relay outbound access log with its elapsed time (REQ-OBS-001). */
  private final WebClientLoggingFilter webClientLoggingFilter;

  /**
   * The backend-facing {@link WebClient}: a 5&nbsp;s connect timeout, 15&nbsp;s read/write/response
   * timeouts, profile-gated TLS trust, the outbound call log, and a response decoder capped at the
   * configured max payload size so a hostile or buggy backend response cannot exhaust heap.
   *
   * @return a {@link WebClient} bound to the configured backend base URL
   */
  @Bean
  public WebClient backendWebClient() {
    int maxInMemory = (int) Math.min(Integer.MAX_VALUE, ingestProperties.getMaxPayloadBytes());
    return WebClient.builder()
        .baseUrl(ingestProperties.getBackendBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(buildHttpClient()))
        .observationRegistry(observationRegistry)
        .filter(webClientLoggingFilter.callLogging())
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxInMemory))
        .build();
  }

  /**
   * The client used for the gateway's own client-credentials grant against Keycloak (ADR-0129).
   *
   * <p>Separate from {@link #backendWebClient()} for three reasons: it addresses a different host
   * (absolute token URI, so no base URL), it must not carry the backend-relay logging filter — that
   * filter names the call as a backend hop and this one is not — and its response is a handful of
   * bytes, so it needs no raised in-memory codec limit. It shares the same profile-gated SSL
   * context because it faces the same Keycloak the resource server already trusts.
   *
   * @return a {@link WebClient} for the Keycloak token endpoint
   */
  @Bean
  public WebClient keycloakWebClient() {
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(buildKeycloakHttpClient()))
        .observationRegistry(observationRegistry)
        .build();
  }

  /**
   * Builds the Keycloak-facing HTTP client with its OWN trust set.
   *
   * <p><strong>It must not reuse {@link #buildHttpClient()}.</strong> That one installs the {@code
   * backend-trust} bundle as the <em>only</em> trust anchor, which is right for the self-signed
   * {@code https://backend:11261} and catastrophic here: pinned to the backend's certificate, this
   * client cannot validate Keycloak's publicly-trusted one, so the TLS handshake fails and the
   * client-credentials grant dies as a transport error with no HTTP status to explain it. That is
   * exactly how it failed on 2026-08-04 — every send answered "An unexpected error occurred."
   *
   * <p>The trust set mirrors {@link KeycloakTrustSupport}, which the JWKS decoder already uses: pin
   * to {@code keycloak-trust} when that bundle is configured (an internal, self-signed Keycloak),
   * and otherwise fall through to the JVM's default anchors with hostname verification left ON,
   * which is what a publicly-trusted host needs. {@code dev}/{@code test} keep the insecure manager
   * for the local stack's ephemeral certificate.
   *
   * @return the configured reactor-netty HTTP client for the token endpoint
   */
  private HttpClient buildKeycloakHttpClient() {
    try {
      List<String> profiles = Arrays.asList(environment.getActiveProfiles());
      SslContext sslContext = null;
      boolean pinnedTrust = false;
      if (profiles.contains("dev") || profiles.contains("test")) {
        sslContext =
            SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        pinnedTrust = true;
      } else {
        try {
          SslBundle bundle = sslBundles.getBundle(KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE);
          KeyStore truststore = bundle.getStores().getTrustStore();
          TrustManagerFactory tmf =
              TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          tmf.init(truststore);
          sslContext = SslContextBuilder.forClient().trustManager(tmf).build();
        } catch (NoSuchSslBundleException noBundle) {
          // No pinned bundle: Keycloak is the public, publicly-trusted host. Leave the SSL context
          // untouched so reactor-netty uses the JVM default anchors AND keeps hostname
          // verification, which is the whole point of a public certificate.
          sslContext = null;
        }
      }
      SslContext effective = sslContext;
      boolean disableHostnameVerification = pinnedTrust;
      return HttpClient.create()
          .secure(
              spec -> {
                if (effective != null) {
                  var configured = spec.sslContext(effective);
                  if (disableHostnameVerification) {
                    configured.handlerConfigurator(
                        sslHandler -> {
                          SSLParameters params = sslHandler.engine().getSSLParameters();
                          params.setEndpointIdentificationAlgorithm("");
                          sslHandler.engine().setSSLParameters(params);
                        });
                  }
                }
              })
          .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
          .responseTimeout(Duration.ofSeconds(10))
          .doOnConnected(
              conn ->
                  conn.addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                      .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));
    } catch (GeneralSecurityException | SSLException e) {
      throw new IllegalStateException("Failed to build the Keycloak WebClient SSL context", e);
    }
  }

  /**
   * Builds the Netty {@link HttpClient} with the profile-gated SSL context and the connect/read/
   * write/response timeouts.
   *
   * @return the configured reactor-netty HTTP client
   */
  private HttpClient buildHttpClient() {
    try {
      SslContextBuilder builder = SslContextBuilder.forClient();
      List<String> profiles = Arrays.asList(environment.getActiveProfiles());
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
        } catch (NoSuchSslBundleException noBundle) {
          // No backend-trust bundle for this profile — fall back to the default JVM trust store
          // with hostname verification left ON (publicly-trusted / corporate-CA backend cert).
        }
      }
      SslContext sslContext = builder.build();
      boolean disableHostnameVerification = pinnedTrust;
      return HttpClient.create()
          .secure(
              spec -> {
                var configured = spec.sslContext(sslContext);
                if (disableHostnameVerification) {
                  configured.handlerConfigurator(
                      sslHandler -> {
                        SSLParameters params = sslHandler.engine().getSSLParameters();
                        params.setEndpointIdentificationAlgorithm("");
                        sslHandler.engine().setSSLParameters(params);
                      });
                }
              })
          .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
          .responseTimeout(Duration.ofSeconds(15))
          .doOnConnected(
              conn ->
                  conn.addHandlerLast(new ReadTimeoutHandler(15, TimeUnit.SECONDS))
                      .addHandlerLast(new WriteTimeoutHandler(15, TimeUnit.SECONDS)));
    } catch (GeneralSecurityException | SSLException e) {
      throw new IllegalStateException("Failed to build the backend WebClient SSL context", e);
    }
  }
}
