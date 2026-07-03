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

  /**
   * The backend-facing {@link WebClient}: a 5&nbsp;s connect timeout, 15&nbsp;s read/write/response
   * timeouts, profile-gated TLS trust, and a response decoder capped at the configured max payload
   * size so a hostile or buggy backend response cannot exhaust heap.
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
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxInMemory))
        .build();
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
