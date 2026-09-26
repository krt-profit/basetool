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

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mirrors Resilience4j events (circuit breaker, retry, bulkhead, time limiter) onto the log.
 *
 * <p>Circuit-breaker state transitions, bulkhead rejections and time-limiter timeouts log at WARN;
 * calls rejected by an open breaker at DEBUG; retry attempts at INFO (DEBUG when they eventually
 * succeed). Only technical data is logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResilienceEventLogger {

  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final RetryRegistry retryRegistry;
  private final BulkheadRegistry bulkheadRegistry;
  private final TimeLimiterRegistry timeLimiterRegistry;

  @PostConstruct
  void subscribe() {
    circuitBreakerRegistry
        .getAllCircuitBreakers()
        .forEach(
            cb ->
                cb.getEventPublisher()
                    .onStateTransition(
                        e ->
                            log.warn(
                                "CircuitBreaker[{}] state transition {} -> {}",
                                cb.getName(),
                                e.getStateTransition().getFromState(),
                                e.getStateTransition().getToState()))
                    .onCallNotPermitted(
                        e -> log.debug("CircuitBreaker[{}] call not permitted", cb.getName()))
                    .onError(
                        e ->
                            log.info(
                                "CircuitBreaker[{}] call failed: {}",
                                cb.getName(),
                                e.getThrowable().getClass().getSimpleName())));

    retryRegistry
        .getAllRetries()
        .forEach(
            retry ->
                retry
                    .getEventPublisher()
                    .onRetry(
                        e ->
                            log.info(
                                "Retry[{}] attempt {} after {}: {}",
                                retry.getName(),
                                e.getNumberOfRetryAttempts(),
                                e.getWaitInterval(),
                                e.getLastThrowable() != null
                                    ? e.getLastThrowable().getClass().getSimpleName()
                                    : "n/a"))
                    .onError(
                        e ->
                            log.warn(
                                "Retry[{}] exhausted after {} attempts",
                                retry.getName(),
                                e.getNumberOfRetryAttempts())));

    bulkheadRegistry
        .getAllBulkheads()
        .forEach(
            bh ->
                bh.getEventPublisher()
                    .onCallRejected(e -> log.warn("Bulkhead[{}] call rejected", bh.getName())));

    timeLimiterRegistry
        .getAllTimeLimiters()
        .forEach(
            tl ->
                tl.getEventPublisher()
                    .onTimeout(e -> log.warn("TimeLimiter[{}] call timed out", tl.getName())));
  }
}
