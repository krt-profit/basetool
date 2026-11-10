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

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;

/** Builds an isolated full-operation policy for scripted backend contract tests. */
public final class BackendCallPolicyFixture {

  private BackendCallPolicyFixture() {}

  /**
   * Creates a policy that consumes one scripted answer per request.
   *
   * @return independent resilience state with no retries and a thirty-second test budget
   */
  public static BackendCallPolicy create() {
    return create(Duration.ofSeconds(30));
  }

  /**
   * Creates a scripted-answer policy with an explicit binary-transfer budget.
   *
   * @param transferBudget the total binary-transfer budget
   * @return isolated resilience state
   */
  public static BackendCallPolicy create(Duration transferBudget) {
    return new BackendCallPolicy(
        CircuitBreakerRegistry.ofDefaults(),
        RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
        TimeLimiterRegistry.of(
            TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(30)).build()),
        BulkheadRegistry.ofDefaults(),
        new AppHttpProperties(
            Duration.ofSeconds(3),
            Duration.ofSeconds(30),
            transferBudget,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            AppHttpProperties.BackendProtocol.HTTP11,
            20,
            AppHttpProperties.BackendCodec.JSON,
            true));
  }
}
