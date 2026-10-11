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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.web.ClientIpContext;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link ClientIpRelayFilter} (security audit DOS-1): the originating client IP must
 * be relayed to the backend as {@code X-Forwarded-For} so the backend's per-IP rate limiter sees
 * the real client rather than the frontend container; absent when no client IP is bound, and never
 * overwriting an existing header.
 */
class ClientIpRelayFilterTest {

  private final ClientIpRelayFilter filter = new ClientIpRelayFilter();

  @AfterEach
  void cleanUp() {
    ClientIpContext.clear();
  }

  @Test
  void relayClientIp_addsForwardedForFromBoundContext() {
    ClientIpContext.set("203.0.113.7");
    AtomicReference<ClientRequest> sent = new AtomicReference<>();

    filter.relayClientIp().filter(request(), capture(sent)).block();

    assertThat(sent.get().headers().getFirst(ClientIpRelayFilter.FORWARDED_FOR_HEADER))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void relayClientIp_omitsHeaderWithoutBoundClientIp() {
    ClientIpContext.clear();
    AtomicReference<ClientRequest> sent = new AtomicReference<>();

    filter.relayClientIp().filter(request(), capture(sent)).block();

    assertThat(sent.get().headers().getFirst(ClientIpRelayFilter.FORWARDED_FOR_HEADER)).isNull();
  }

  @Test
  void relayClientIp_doesNotOverwriteExistingHeader() {
    ClientIpContext.set("203.0.113.7");
    AtomicReference<ClientRequest> sent = new AtomicReference<>();

    ClientRequest preset =
        ClientRequest.from(request())
            .header(ClientIpRelayFilter.FORWARDED_FOR_HEADER, "198.51.100.4")
            .build();

    filter.relayClientIp().filter(preset, capture(sent)).block();

    assertThat(sent.get().headers().getFirst(ClientIpRelayFilter.FORWARDED_FOR_HEADER))
        .isEqualTo("198.51.100.4");
  }

  private static ClientRequest request() {
    return ClientRequest.create(HttpMethod.GET, URI.create("https://backend/api/v1/ping")).build();
  }

  private static ExchangeFunction capture(AtomicReference<ClientRequest> sink) {
    return request -> {
      sink.set(request);
      return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
    };
  }
}
