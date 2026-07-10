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
import de.greluc.krt.profit.basetool.frontend.config.LoggingProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class WebClientLoggingFilterTest {

  private MockWebServer server;
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;
  private LoggingProperties props;
  private WebClientLoggingFilter filter;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    props =
        new LoggingProperties("X-Correlation-Id", "correlationId", "userId", 2000L, 1500L, false);
    filter = new WebClientLoggingFilter(props);
    logger = (Logger) LoggerFactory.getLogger(WebClientLoggingFilter.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
  }

  @AfterEach
  void tearDown() throws Exception {
    logger.detachAppender(appender);
    server.shutdown();
    CorrelationContext.clear();
  }

  @Test
  void propagatesCorrelationIdHeaderWhenPresent() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    CorrelationContext.set("cid-xyz");
    WebClient wc =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .filter(filter.correlationIdPropagation())
            .build();

    wc.get().uri("/x").retrieve().toBodilessEntity().block();

    RecordedRequest recorded = server.takeRequest();
    assertThat(recorded.getHeader("X-Correlation-Id")).isEqualTo("cid-xyz");
  }

  @Test
  void doesNotAddCorrelationIdHeaderWhenAbsent() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    WebClient wc =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .filter(filter.correlationIdPropagation())
            .build();

    wc.get().uri("/x").retrieve().toBodilessEntity().block();

    RecordedRequest recorded = server.takeRequest();
    assertThat(recorded.getHeader("X-Correlation-Id")).isNull();
  }

  @Test
  void logsInfoLineOnFastSuccess() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    WebClient wc =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .filter(filter.callLogging())
            .build();

    wc.get().uri("/api/v1/ok").retrieve().toBodilessEntity().block();

    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("GET")
                    && e.getFormattedMessage().contains("/api/v1/ok")
                    && e.getFormattedMessage().contains("-> 200"));
  }

  @Test
  void escalatesToWarnOnServerError() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody(""));
    WebClient wc =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .filter(filter.callLogging())
            .build();

    try {
      wc.get().uri("/api/v1/boom").retrieve().toBodilessEntity().block();
    } catch (Exception ignored) {
      // expected – 500 is mapped to WebClientResponseException
    }

    assertThat(appender.list)
        .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("-> 500"));
  }

  @Test
  void logsCircuitBreakerShortCircuitAtDebugNotWarn() {
    // When the breaker is open the resilience filter (inner) short-circuits with a
    // CallNotPermittedException before any backend hit. The outer callLogging filter must log this
    // at DEBUG, not WARN, so a routine backend restart/deploy does not flood WARN (issue #1203).
    logger.setLevel(Level.DEBUG);
    CircuitBreaker cb = CircuitBreakerRegistry.ofDefaults().circuitBreaker("backendApi");
    cb.transitionToOpenState();
    ExchangeFilterFunction openBreaker =
        (request, next) ->
            Mono.error(CallNotPermittedException.createCallNotPermittedException(cb));
    WebClient wc =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .filter(filter.callLogging())
            .filter(openBreaker)
            .build();

    try {
      wc.get().uri("/api/v1/blocked").retrieve().toBodilessEntity().block();
    } catch (Exception ignored) {
      // expected – the breaker short-circuits with CallNotPermittedException
    }

    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.DEBUG
                    && e.getFormattedMessage().contains("/api/v1/blocked")
                    && e.getFormattedMessage().contains("short-circuited"));
    assertThat(appender.list)
        .noneMatch(
            e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("/api/v1/blocked"));
  }
}
