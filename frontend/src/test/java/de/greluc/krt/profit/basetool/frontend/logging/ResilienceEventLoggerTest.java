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
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ResilienceEventLoggerTest {

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(ResilienceEventLogger.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  void logsCircuitBreakerStateTransitionAsWarn() {
    CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.ofDefaults();
    // Force a circuit breaker to exist so the logger subscribes to it.
    CircuitBreaker cb = cbReg.circuitBreaker("test-instance");
    RetryRegistry retryReg = RetryRegistry.ofDefaults();
    BulkheadRegistry bhReg = BulkheadRegistry.ofDefaults();
    TimeLimiterRegistry tlReg = TimeLimiterRegistry.ofDefaults();

    ResilienceEventLogger rel = new ResilienceEventLogger(cbReg, retryReg, bhReg, tlReg);
    rel.subscribe();

    cb.transitionToOpenState();

    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("CircuitBreaker[test-instance]")
                    && e.getFormattedMessage().contains("state transition"));
  }

  @Test
  void logsCallNotPermittedAsDebugNotWarn() {
    // A call rejected by an already-open breaker must NOT be logged at WARN: it fires for every
    // short-circuited call for the whole open window and would flood the log during a routine
    // backend restart/deploy (issue #1203). The one-time OPEN state transition carries the WARN.
    CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.ofDefaults();
    CircuitBreaker cb = cbReg.circuitBreaker("test-instance");
    ResilienceEventLogger rel =
        new ResilienceEventLogger(
            cbReg,
            RetryRegistry.ofDefaults(),
            BulkheadRegistry.ofDefaults(),
            TimeLimiterRegistry.ofDefaults());
    rel.subscribe();
    logger.setLevel(Level.DEBUG);

    cb.transitionToOpenState();
    // Invoking any call while OPEN is rejected and fires onCallNotPermitted.
    try {
      cb.executeSupplier(() -> "unreachable");
    } catch (CallNotPermittedException expected) {
      // expected — the breaker short-circuits the call
    }

    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.DEBUG
                    && e.getFormattedMessage().contains("CircuitBreaker[test-instance]")
                    && e.getFormattedMessage().contains("call not permitted"));
    assertThat(appender.list)
        .noneMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("call not permitted"));
  }
}
