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

package de.greluc.krt.profit.basetool.backend.config;

import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The backend's one source of outbound HTTP clients: a {@link RestClient.Builder} on the JDK {@link
 * HttpClient}, with bounded timeouts and the Micrometer {@link ObservationRegistry} (ADR-0204).
 *
 * <p>Every outbound call the backend makes is blocking — the UEX and SC-Wiki catalogue syncs run on
 * scheduler threads, the Keycloak Admin API calls on request or scheduler threads — so the reactive
 * {@code WebClient} this class used to configure bought nothing but a second HTTP stack (WebFlux,
 * Reactor Netty) on the runtime classpath and a {@code block()} at every call site. The JDK client
 * is part of the runtime already.
 *
 * <p><b>Why a hand-built builder and not Boot's.</b> Boot 4 auto-configures a {@code
 * RestClient.Builder} only from its separate {@code spring-boot-restclient} module, which this
 * module does not ship. The builder here therefore wires the observation registry itself, exactly
 * as the {@code WebClient.Builder} it replaces did, so every call still records {@code
 * http.client.requests} (Prometheus {@code http_client_requests_seconds}) with the default {@code
 * method}, {@code uri}, {@code status}, {@code outcome}, {@code exception} and {@code client.name}
 * key values, and a client span when tracing is on (REQ-OBS-009). The {@code
 * ObservationPrivacyFilter} is registered on the registry, not on a client, so it keeps scrubbing
 * these observations unchanged.
 */
@Configuration
public class RestClientConfig {

  /**
   * Upper bound on establishing a TCP (and TLS) connection. Without it a connect to an unreachable
   * host waits the operating system's default — a minute or more on Linux.
   */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Upper bound on one exchange once the connection is up: Spring's {@link
   * JdkClientHttpRequestFactory} applies it as the JDK request timeout (until the response headers
   * arrive) and to the body read. 30&nbsp;s is what the replaced client used as its per-call
   * timeout for the UEX and SC-Wiki fetches, whose largest pages are several megabytes.
   */
  static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  /**
   * The request factory every builder from {@link #restClientBuilder} shares, so the backend runs
   * one JDK {@link HttpClient} — one connection pool and one selector thread — instead of one per
   * injection point.
   */
  private final ClientHttpRequestFactory requestFactory =
      jdkRequestFactory(CONNECT_TIMEOUT, READ_TIMEOUT);

  /**
   * A fresh {@link RestClient.Builder} per injection point, pre-wired with the shared JDK request
   * factory and the observation registry.
   *
   * <p>Prototype-scoped because a builder is mutable: {@code UexClient} and {@code ScWikiClient}
   * each set their own base URL, and a shared singleton builder would hand one caller's base URL,
   * interceptors or request factory to the next. Boot scopes its own auto-configured builder the
   * same way.
   *
   * @param observationRegistry the Micrometer observation registry auto-configured by Boot
   * @return a new builder whose clients are observed and bounded by {@link #CONNECT_TIMEOUT} and
   *     {@link #READ_TIMEOUT}
   */
  @Bean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  public RestClient.Builder restClientBuilder(@NotNull ObservationRegistry observationRegistry) {
    return RestClient.builder()
        .requestFactory(requestFactory)
        .observationRegistry(observationRegistry);
  }

  /**
   * Builds a {@link JdkClientHttpRequestFactory} over a JDK {@link HttpClient} with the given
   * timeouts.
   *
   * <p>The client is pinned to HTTP/1.1. The JDK client defaults to HTTP/2, which over plain {@code
   * http://} means an {@code Upgrade: h2c} offer on every request and over TLS an ALPN negotiation
   * the replaced Reactor Netty client never made; pinning keeps the wire behaviour of the migration
   * identical. Package-private so a test can build one with a short read timeout.
   *
   * @param connectTimeout the bound on establishing a connection
   * @param readTimeout the bound on one exchange once connected
   * @return a request factory over a new JDK client
   */
  static @NotNull ClientHttpRequestFactory jdkRequestFactory(
      @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(readTimeout);
    return factory;
  }
}
