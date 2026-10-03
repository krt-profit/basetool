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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/** Unit tests for {@link BackendOriginGuard} (REQ-FE-029). */
class BackendOriginGuardTest {

  private final BackendOriginGuard guard = BackendOriginGuard.forBaseUrl("https://backend:11261");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://backend:11261/api/v1/missions",
        "HTTPS://BACKEND:11261/api/v1/missions?page=1",
        "https://backend:11261"
      })
  void admitsTheBackendOrigin(String url) {
    assertThat(guard.admits(URI.create(url))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://attacker.example/api/v1/missions",
        "https://backend:11262/api/v1/missions",
        "http://backend:11261/api/v1/missions",
        "https://backend/api/v1/missions",
        "https://backend.attacker.example:11261/api/v1/missions",
        "/api/v1/missions"
      })
  void refusesEveryOtherOrigin(String url) {
    assertThat(guard.admits(URI.create(url))).isFalse();
  }

  @Test
  void theSchemesDefaultPortIsTheSameOrigin() {
    BackendOriginGuard https = BackendOriginGuard.forBaseUrl("https://backend");

    assertThat(https.port()).isEqualTo(443);
    assertThat(https.admits(URI.create("https://backend:443/api/v1/x"))).isTrue();
    assertThat(BackendOriginGuard.forBaseUrl("http://backend").port()).isEqualTo(80);
  }

  @Test
  void aBaseUrlWithoutHostIsRejectedAtStartup() {
    assertThatThrownBy(() -> BackendOriginGuard.forBaseUrl("/api"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theFilterPassesTheBackendOriginOn() {
    AtomicInteger sent = new AtomicInteger();

    ClientResponse response =
        guard.filter().filter(request("https://backend:11261/api/v1/ping"), counting(sent)).block();

    assertThat(response).isNotNull();
    assertThat(sent).hasValue(1);
  }

  @Test
  void theFilterRefusesAnotherOriginWithoutCallingTheNextFilter() {
    AtomicInteger sent = new AtomicInteger();

    Mono<ClientResponse> refused =
        guard.filter().filter(request("https://attacker.example/steal?q=1"), counting(sent));

    assertThatThrownBy(refused::block)
        .isInstanceOf(BackendOriginViolationException.class)
        .hasMessageContaining("https://attacker.example")
        .hasMessageNotContaining("steal");
    assertThat(sent).hasValue(0);
  }

  private static ClientRequest request(String url) {
    return ClientRequest.create(HttpMethod.GET, URI.create(url)).build();
  }

  private static ExchangeFunction counting(AtomicInteger sent) {
    return request -> {
      sent.incrementAndGet();
      return Mono.just(ClientResponse.create(HttpStatus.OK).build());
    };
  }
}
