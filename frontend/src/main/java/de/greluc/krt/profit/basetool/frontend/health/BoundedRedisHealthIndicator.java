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

package de.greluc.krt.profit.basetool.frontend.health;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.health.DataRedisReactiveHealthIndicator;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Bounds Spring Boot's reactive Redis health check ({@link DataRedisReactiveHealthIndicator}) by
 * wall-clock time: if the {@code PING} does not complete within {@link #TIMEOUT}, the contributor
 * reports {@code DOWN}.
 *
 * <p>The bound covers stalls that Lettuce's command timeout cannot, such as a wedged connection
 * acquisition. Named {@code redisHealthIndicator}, it replaces the auto-configured indicator under
 * the same {@code redis} key.
 */
@Component("redisHealthIndicator")
@ConditionalOnEnabledHealthIndicator("redis")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class BoundedRedisHealthIndicator implements ReactiveHealthIndicator {

  /**
   * Upper bound for the whole health check. Sits above the 2s Lettuce command timeout (ADR-0114) so
   * a regular slow-command failure keeps its own, more specific error detail, and below the 5s
   * Docker {@code HEALTHCHECK} budget so a wedged connection surfaces as a deterministic {@code
   * DOWN} instead of an infrastructure-level probe timeout.
   */
  static final Duration TIMEOUT = Duration.ofSeconds(3);

  /** The health indicator whose result is bounded. */
  private final @NotNull ReactiveHealthIndicator delegate;

  /** Maximum wall-clock time the delegate may take before the check is reported {@code DOWN}. */
  private final @NotNull Duration timeout;

  /**
   * Creates the indicator around a {@link DataRedisReactiveHealthIndicator} on the given connection
   * factory.
   *
   * @param connectionFactory the reactive Redis connection factory the delegate {@code PING}s over
   */
  @Autowired
  public BoundedRedisHealthIndicator(@NotNull ReactiveRedisConnectionFactory connectionFactory) {
    this(new DataRedisReactiveHealthIndicator(connectionFactory), TIMEOUT);
  }

  /**
   * Runs the delegate's Redis {@code PING} with the wall-clock bound applied: if no result arrives
   * within the configured timeout, the returned {@link Mono} falls back to a {@code DOWN} health
   * with an {@code error} detail naming the bound — the delegate subscription is cancelled by the
   * timeout operator, so a wedged Lettuce acquisition cannot leak into later checks' wall time.
   *
   * @return the delegate's health, or a {@code DOWN} health when the bound is exceeded
   */
  @Override
  public @NotNull Mono<Health> health() {
    return delegate
        .health()
        .timeout(
            timeout,
            Mono.fromSupplier(
                () ->
                    Health.down()
                        .withDetail(
                            "error", "health check timed out after " + timeout.toMillis() + " ms")
                        .build()));
  }
}
