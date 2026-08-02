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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link ActiveSquadronRelayFilter} (audit finding M1): the pin is relayed as {@code
 * X-Active-Org-Unit-Id} when one is bound, and the drop branch — which used to be completely silent
 * and is the exact shape of "the user sees another Staffel's rows" — leaves a DEBUG line.
 */
class ActiveSquadronRelayFilterTest {

  private final ActiveSquadronRelayFilter filter = new ActiveSquadronRelayFilter();

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(ActiveSquadronRelayFilter.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void cleanUp() {
    logger.detachAppender(appender);
    ActiveSquadronContext.clear();
  }

  @Test
  void relayActiveSquadron_addsHeaderFromBoundContextWithoutLoggingTheDrop() {
    UUID pinned = UUID.randomUUID();
    ActiveSquadronContext.set(pinned);
    AtomicReference<ClientRequest> sent = new AtomicReference<>();

    filter.relayActiveSquadron().filter(request(), capture(sent)).block();

    assertThat(sent.get().headers().getFirst(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER))
        .isEqualTo(pinned.toString());
    assertThat(appender.list).isEmpty();
  }

  @Test
  void relayActiveSquadron_logsDroppedRelayAtDebug() {
    ActiveSquadronContext.clear();
    AtomicReference<ClientRequest> sent = new AtomicReference<>();

    filter.relayActiveSquadron().filter(request(), capture(sent)).block();

    assertThat(sent.get().headers().getFirst(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER))
        .isNull();
    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> {
              // DEBUG is the contract: every anonymous and every unpinned request takes this
              // branch, so anything higher would be a log-flood vector.
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage())
                  .contains(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER)
                  .contains("/api/v1/ping");
            });
  }

  /** The query string may carry user-typed search terms, so it must not reach the log line. */
  @Test
  void relayActiveSquadron_dropLineOmitsTheQueryString() {
    ActiveSquadronContext.clear();
    ClientRequest withQuery =
        ClientRequest.create(
                HttpMethod.GET, URI.create("https://backend/api/v1/ping?q=secret-search-term"))
            .build();

    filter.relayActiveSquadron().filter(withQuery, capture(new AtomicReference<>())).block();

    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> assertThat(event.getFormattedMessage()).doesNotContain("secret-search-term"));
  }

  private static ClientRequest request() {
    return ClientRequest.create(HttpMethod.GET, URI.create("https://backend/api/v1/ping")).build();
  }

  private static ExchangeFunction capture(AtomicReference<ClientRequest> sink) {
    return request -> {
      sink.set(request);
      return Mono.just(ClientResponse.create(HttpStatus.OK).build());
    };
  }
}
