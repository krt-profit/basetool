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

package de.greluc.krt.profit.basetool.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.BadRequestException;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Tests the gateway-side branches of {@link GlobalExceptionHandler}: a detected bad request keeps
 * its own message, an unreachable handoff staging is a retryable 503, and an unexpected failure is
 * a generic 500 that leaks nothing.
 */
class GlobalExceptionHandlerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(meterRegistry, TestLoggingProperties.defaults());

  @Test
  void gatewayDetectedBadRequest_becomesA400WithItsOwnMessage() {
    ProblemDetail problem =
        handler.handleBadRequest(new BadRequestException("The import draft is too large."));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("The import draft is too large.");
    assertThat(problem.getProperties()).containsEntry("code", "BAD_REQUEST");
    assertThat(meterRegistry.find(MetricNames.INGEST_HANDOFF_ERRORS).counters()).isEmpty();
  }

  @Test
  void unexpectedFailure_becomesAGeneric500ThatLeaksNothing() {
    ProblemDetail problem =
        handler.handleUnexpected(new IllegalStateException("jdbc://user:pw@host exploded"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
    assertThat(problem.getDetail()).doesNotContain("jdbc", "pw");
    assertThat(failures(MetricNames.REASON_INTERNAL)).isEqualTo(1.0d);
  }

  @Test
  void problemsCarryTheCurrentCorrelationId() {
    MDC.put("correlationId", "cid-77");
    try {
      ProblemDetail problem = handler.handleBadRequest(new BadRequestException("nope"));

      assertThat(problem.getProperties()).containsEntry("correlationId", "cid-77");
    } finally {
      MDC.remove("correlationId");
    }
  }

  @Test
  void problemsOmitTheCorrelationIdWhenTheMdcIsEmpty() {
    MDC.remove("correlationId");

    ProblemDetail problem = handler.handleBadRequest(new BadRequestException("nope"));

    assertThat(problem.getProperties()).doesNotContainKey("correlationId");
  }

  @Test
  void redisStagingOutage_becomesARetryable503RatherThanAGeneric500() {
    ResponseEntity<ProblemDetail> response =
        handler.handleStagingUnavailable(
            new RedisConnectionFailureException("Unable to connect to Redis"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getProperties())
        .containsEntry("code", MetricNames.CODE_SERVICE_UNAVAILABLE);
    assertThat(failures(MetricNames.REASON_STAGING_UNAVAILABLE)).isEqualTo(1.0d);
    assertThat(failures(MetricNames.REASON_INTERNAL)).isZero();
    assertThat(
            meterRegistry
                .get(MetricNames.HTTP_ERROR)
                .tag(MetricNames.TAG_CODE, MetricNames.CODE_SERVICE_UNAVAILABLE)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void redisStagingOutage_isWarnedNotErrored_andNeverEchoesTheEndpoint() {
    List<ILoggingEvent> events =
        LogCapture.capture(
            GlobalExceptionHandler.class,
            Level.DEBUG,
            () ->
                handler.handleStagingUnavailable(
                    new RedisConnectionFailureException("Unable to connect to redis:6379")));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(events.getFirst().getFormattedMessage())
        .contains("RedisConnectionFailureException")
        .doesNotContain("redis:6379");
  }

  /**
   * Reads the {@code basetool_ingest_handoff_errors_total} count for one bounded reason.
   *
   * @param reason the {@code MetricNames.REASON_*} tag value
   * @return the counter value, or {@code 0.0} when the counter was never created
   */
  private double failures(String reason) {
    var counter =
        meterRegistry
            .find(MetricNames.INGEST_HANDOFF_ERRORS)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0.0d : counter.count();
  }
}
