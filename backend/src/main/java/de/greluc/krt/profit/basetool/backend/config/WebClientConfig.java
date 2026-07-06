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
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Centrally configured {@link WebClient.Builder} bean with timeouts and a bounded connection pool.
 *
 * <p>Explicit connect/read/write timeouts (5&nbsp;s connect, 30&nbsp;s read, 30&nbsp;s write) bound
 * every layer of an outbound call so the JVM never hangs on a slow or misbehaving upstream like the
 * UEX API. The previous configuration only had a Reactor-level {@code .timeout()} on the response
 * {@code Mono}, which does NOT bound the socket-connect phase — a TCP connect to an unreachable
 * host would still wait the OS default ({@code 1+}&nbsp;min on Linux, {@code 21}&nbsp;s on
 * Windows). The connection pool is sized for the typical UEX-sync workload (50 concurrent
 * connections, 20&nbsp;s idle eviction).
 */
@Configuration
public class WebClientConfig {

  /**
   * Returns a {@link WebClient.Builder} pre-configured with the Netty connector, bounded pool,
   * timeouts and the Micrometer {@link ObservationRegistry}; injected by services that talk to
   * external HTTP endpoints.
   *
   * <p>Wiring the {@code ObservationRegistry} into this hand-built builder (Boot's
   * auto-configuration only instruments the auto-configured {@code WebClient.Builder}) makes every
   * UEX / SC-Wiki outbound call emit {@code http_client_requests_seconds} metrics and OTLP client
   * spans, with the {@code ObservationPrivacyFilter} scrubbing query strings and collapsing id path
   * segments (REQ-OBS-009). Without it the backend was the only module leaving its outbound calls
   * unobserved.
   *
   * @param observationRegistry the Micrometer observation registry (auto-configured by Boot)
   * @return a {@link WebClient.Builder} pre-configured with the Netty connector, bounded pool,
   *     timeouts and observation registry; injected by services that talk to external HTTP
   *     endpoints
   */
  @Bean
  public WebClient.Builder webClientBuilder(ObservationRegistry observationRegistry) {
    reactor.netty.resources.ConnectionProvider provider =
        reactor.netty.resources.ConnectionProvider.builder("backend-pool")
            .maxConnections(50)
            .maxIdleTime(java.time.Duration.ofSeconds(20))
            .maxLifeTime(java.time.Duration.ofSeconds(60))
            .pendingAcquireTimeout(java.time.Duration.ofSeconds(5))
            .evictInBackground(java.time.Duration.ofSeconds(10))
            .build();

    // Explicit connect/read/write timeouts so the JVM never hangs on a slow or
    // misbehaving upstream (e.g. the UEX API). The previous build only had a
    // Reactor-level .timeout() on the response Mono, which does not bound the
    // socket-connect phase.
    reactor.netty.http.client.HttpClient httpClient =
        reactor.netty.http.client.HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(java.time.Duration.ofSeconds(30))
            .doOnConnected(
                conn ->
                    conn.addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

    return WebClient.builder()
        .clientConnector(
            new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
        .observationRegistry(observationRegistry);
  }
}
