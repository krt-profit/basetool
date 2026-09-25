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
 * Provides the backend's outbound HTTP clients: a {@link RestClient.Builder} on the JDK {@link
 * HttpClient} with bounded timeouts and the Micrometer {@link ObservationRegistry} (ADR-0204).
 *
 * <p>Every call is observed as {@code http.client.requests} with the default key values, plus a
 * client span when tracing is on (REQ-OBS-009).
 */
@Configuration
public class RestClientConfig {

  /**
   * Upper bound on establishing a TCP (and TLS) connection. Without it a connect to an unreachable
   * host waits the operating system's default — a minute or more on Linux.
   */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Upper bound on one exchange once connected, applied as the JDK request timeout and to the body
   * read.
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
   * Creates a fresh {@link RestClient.Builder} per injection point, pre-wired with the shared JDK
   * request factory and the observation registry.
   *
   * <p>Prototype-scoped because a builder is mutable and each client sets its own base URL.
   *
   * @param observationRegistry the Micrometer observation registry
   * @return a new builder bounded by {@link #CONNECT_TIMEOUT} and {@link #READ_TIMEOUT}
   */
  @Bean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  public RestClient.Builder restClientBuilder(@NotNull ObservationRegistry observationRegistry) {
    return RestClient.builder()
        .requestFactory(requestFactory)
        .observationRegistry(observationRegistry);
  }

  /**
   * Builds a {@link JdkClientHttpRequestFactory} over a JDK {@link HttpClient} pinned to HTTP/1.1
   * with the given timeouts.
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
