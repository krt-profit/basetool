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
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * Unit tests for the outbound relay access log. The levels are the contract here, not the wording:
 * REQ-OBS-001 requires a relay failure to be logged exactly once at the level its status warrants,
 * and {@code GlobalExceptionHandler} already owns that decision — so this interceptor must stay at
 * INFO/DEBUG and never double the operator-facing WARN.
 */
class BackendCallLoggingInterceptorTest {

  private static final MockClientHttpRequest REQUEST =
      new MockClientHttpRequest(
          HttpMethod.POST, URI.create("https://backend:11261/api/v1/refinery-orders/import"));

  private static List<ILoggingEvent> exchange(
      BackendCallLoggingInterceptor interceptor, ClientHttpRequestExecution upstream) {
    return LogCapture.capture(
        BackendCallLoggingInterceptor.class,
        Level.DEBUG,
        () -> {
          try {
            interceptor.intercept(REQUEST, new byte[0], upstream);
          } catch (IOException | RuntimeException propagated) {
          }
        });
  }

  private static ClientHttpRequestExecution responding(HttpStatus status) {
    return (request, body) -> new MockClientHttpResponse(new byte[0], status);
  }

  @Test
  void logsOneInfoLineForASuccessfulRelay() {
    List<ILoggingEvent> events =
        exchange(
            new BackendCallLoggingInterceptor(TestLoggingProperties.defaults()),
            responding(HttpStatus.OK));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.INFO);
    assertThat(events.getFirst().getFormattedMessage())
        .startsWith("Backend call POST backend/api/v1/refinery-orders/import -> 200 in ");
  }

  @Test
  void marksASlowRelayAtInfoRatherThanEscalatingToWarn() {
    List<ILoggingEvent> events =
        exchange(
            new BackendCallLoggingInterceptor(TestLoggingProperties.withThresholds(2000L, 0L)),
            responding(HttpStatus.OK));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.INFO);
    assertThat(events.getFirst().getFormattedMessage()).startsWith("Slow backend call POST ");
  }

  @Test
  void keepsABackend5xxAtDebugSoTheHandlerOwnsTheSingleWarn() {
    List<ILoggingEvent> events =
        exchange(
            new BackendCallLoggingInterceptor(TestLoggingProperties.defaults()),
            responding(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.DEBUG);
  }

  @Test
  void keepsATransportFailureAtDebugAndNeverLogsItsMessage() {
    List<ILoggingEvent> events =
        exchange(
            new BackendCallLoggingInterceptor(TestLoggingProperties.defaults()),
            (request, body) -> {
              throw new ConnectException("connect failed to https://backend:11261");
            });

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.DEBUG);
    assertThat(events.getFirst().getFormattedMessage())
        .contains("ConnectException")
        .doesNotContain("connect failed");
  }
}
