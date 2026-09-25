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

package de.greluc.krt.profit.basetool.frontend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link UserLocaleRelayFilter}: the {@code Accept-Language} header must carry the
 * user's resolved locale on outbound backend calls (so backend-localized RFC 7807 problem details
 * arrive in the user's language, #435) and must be absent when no locale context is bound.
 */
class UserLocaleRelayFilterTest {

  private final UserLocaleRelayFilter filter = new UserLocaleRelayFilter();

  @AfterEach
  void cleanUp() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void relayUserLocale_addsAcceptLanguageFromLocaleContext() {
    LocaleContextHolder.setLocale(Locale.GERMAN);
    AtomicReference<ClientRequest> sent = new AtomicReference<>();

    filter.relayUserLocale().filter(request(), capture(sent)).block();

    assertThat(sent.get().headers().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("de");
  }

  @Test
  void relayUserLocale_omitsHeaderWithoutBoundLocaleContext() {
    LocaleContextHolder.resetLocaleContext();
    AtomicReference<ClientRequest> sent = new AtomicReference<>();

    filter.relayUserLocale().filter(request(), capture(sent)).block();

    assertThat(sent.get().headers().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isNull();
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
