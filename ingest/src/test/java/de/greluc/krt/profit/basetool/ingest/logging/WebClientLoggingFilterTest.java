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

package de.greluc.krt.profit.basetool.ingest.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Unit tests for the outbound relay access log. The levels are the contract here, not the wording:
 * REQ-OBS-001 requires a relay failure to be logged exactly once at the level its status warrants,
 * and {@code GlobalExceptionHandler} already owns that decision — so this filter must stay at
 * INFO/DEBUG and never double the operator-facing WARN.
 */
class WebClientLoggingFilterTest {

  private static final ClientRequest REQUEST =
      ClientRequest.create(
              HttpMethod.POST, URI.create("https://backend:11261/api/v1/refinery-orders/import"))
          .build();

  private static List<ILoggingEvent> exchange(
      WebClientLoggingFilter filter, ExchangeFunction upstream) {
    return LogCapture.capture(
        WebClientLoggingFilter.class,
        Level.DEBUG,
        () -> {
          ExchangeFilterFunction logging = filter.callLogging();
          try {
            logging.filter(REQUEST, upstream).block();
          } catch (RuntimeException propagated) {
            // The filter never swallows a failure; the log line is what is under test.
          }
        });
  }

  private static ExchangeFunction responding(HttpStatus status) {
    return request -> Mono.just(ClientResponse.create(status).build());
  }

  @Test
  void logsOneInfoLineForASuccessfulRelay() {
    List<ILoggingEvent> events =
        exchange(
            new WebClientLoggingFilter(TestLoggingProperties.defaults()),
            responding(HttpStatus.OK));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.INFO);
    assertThat(events.getFirst().getFormattedMessage())
        .startsWith("Backend call POST backend/api/v1/refinery-orders/import -> 200 in ");
  }

  @Test
  void marksASlowRelayAtInfoRatherThanEscalatingToWarn() {
    // Issue #1204: a slow-but-successful call is still a success; latency is alerted on through the
    // http.client.requests p95 histogram, not by crying wolf in the log.
    List<ILoggingEvent> events =
        exchange(
            new WebClientLoggingFilter(TestLoggingProperties.withThresholds(2000L, 0L)),
            responding(HttpStatus.OK));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.INFO);
    assertThat(events.getFirst().getFormattedMessage()).startsWith("Slow backend call POST ");
  }

  @Test
  void keepsABackend5xxAtDebugSoTheHandlerOwnsTheSingleWarn() {
    List<ILoggingEvent> events =
        exchange(
            new WebClientLoggingFilter(TestLoggingProperties.defaults()),
            responding(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.DEBUG);
  }

  @Test
  void keepsATransportFailureAtDebugAndNeverLogsItsMessage() {
    // The message can carry the full target URL; the handler's WARN is the operator-facing line.
    List<ILoggingEvent> events =
        exchange(
            new WebClientLoggingFilter(TestLoggingProperties.defaults()),
            request ->
                Mono.error(new IllegalStateException("connect failed to https://backend:11261")));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.DEBUG);
    assertThat(events.getFirst().getFormattedMessage())
        .contains("IllegalStateException")
        .doesNotContain("connect failed");
  }
}
