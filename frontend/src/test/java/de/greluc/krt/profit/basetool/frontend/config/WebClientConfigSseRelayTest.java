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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpContext;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.UserLocaleRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.WebClientLoggingFilter;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Wiring guard for {@link WebClientConfig#sseWebClient()} (#1110): the notification SSE relay MUST
 * carry the client-IP relay filter, otherwise every live viewer's stream — and every reconnect
 * after a redeploy — collapses onto the single frontend-container IP bucket (REQ-SEC-011).
 *
 * <p>{@link ClientIpRelayFilter} itself is unit-tested in {@code ClientIpRelayFilterTest}; this
 * test proves the filter is actually <em>attached to the SSE client</em> by capturing the built
 * client's exchange-filter chain and asserting it emits {@code X-Forwarded-For} for a bound client
 * IP. So deleting the {@code .filter(clientIpRelayFilter.relayClientIp())} line can no longer
 * compile and pass while silently re-opening the bug. Runs fully offline — the {@code "test"}
 * profile pins the insecure trust manager, so {@code connector(true)} builds without a network or
 * SSL bundle.
 */
class WebClientConfigSseRelayTest {

  @AfterEach
  void cleanUp() {
    ClientIpContext.clear();
  }

  /** Builds the real {@code sseWebClient} bean with light test doubles for its collaborators. */
  private static WebClient buildSseWebClient() {
    ExchangeFilterFunction passthrough = (request, next) -> next.exchange(request);

    WebClientLoggingFilter logging = mock(WebClientLoggingFilter.class);
    when(logging.correlationIdPropagation()).thenReturn(passthrough);
    ActiveSquadronRelayFilter squadron = mock(ActiveSquadronRelayFilter.class);
    when(squadron.relayActiveSquadron()).thenReturn(passthrough);
    UserLocaleRelayFilter locale = mock(UserLocaleRelayFilter.class);
    when(locale.relayUserLocale()).thenReturn(passthrough);

    Environment environment = mock(Environment.class);
    // "test" profile => connector(true) pins InsecureTrustManagerFactory and never reads
    // SslBundles.
    when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});

    WebClientConfig config =
        new WebClientConfig(
            new AppBackendProperties("https://backend:11261"),
            new AppHttpProperties(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            logging,
            squadron,
            locale,
            new ClientIpRelayFilter(),
            environment,
            mock(SslBundles.class),
            ObservationRegistry.NOOP);

    return config.sseWebClient();
  }

  /**
   * Captures the exchange filters configured on a built client and composes them into one chain.
   */
  private static ExchangeFilterFunction filterChainOf(WebClient client) {
    List<ExchangeFilterFunction> filters = new ArrayList<>();
    client.mutate().filters(filters::addAll);
    return filters.stream()
        .reduce(ExchangeFilterFunction::andThen)
        .orElseThrow(() -> new AssertionError("sseWebClient has no exchange filters"));
  }

  /** Runs the whole filter chain against a capturing exchange and returns the outbound request. */
  private static ClientRequest runChain(ExchangeFilterFunction chain) {
    AtomicReference<ClientRequest> sent = new AtomicReference<>();
    ExchangeFunction capture =
        request -> {
          sent.set(request);
          return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
    ClientRequest request =
        ClientRequest.create(HttpMethod.GET, URI.create("https://backend/stream")).build();
    chain.filter(request, capture).block();
    return sent.get();
  }

  @Test
  void sseWebClientRelaysBoundClientIpAsForwardedFor() {
    ClientIpContext.set("203.0.113.7");

    ClientRequest sent = runChain(filterChainOf(buildSseWebClient()));

    assertThat(sent.headers().getFirst(ClientIpRelayFilter.FORWARDED_FOR_HEADER))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void sseWebClientOmitsForwardedForWhenNoClientIpBound() {
    ClientIpContext.clear();

    ClientRequest sent = runChain(filterChainOf(buildSseWebClient()));

    assertThat(sent.headers().getFirst(ClientIpRelayFilter.FORWARDED_FOR_HEADER)).isNull();
  }
}
