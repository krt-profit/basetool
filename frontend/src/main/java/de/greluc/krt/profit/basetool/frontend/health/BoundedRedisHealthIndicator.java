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
 * Hard wall-clock bound around Spring Boot's reactive Redis health check ({@link
 * DataRedisReactiveHealthIndicator}): the {@code PING} either completes within {@link #TIMEOUT} or
 * the contributor reports {@code DOWN} — it can never hang the health endpoint.
 *
 * <p><b>Why the ADR-0114 property bound is not enough.</b> {@code spring.data.redis.timeout} (2s,
 * ADR-0114) reaches Lettuce as a command timeout, and Boot enables {@code TimeoutOptions} so it
 * covers reactive command dispatch too — but it can only bound a command that <em>reached the
 * dispatch layer</em>. During the 2026-07-22 incident the frontend's shared reactive Lettuce
 * channel wedged at the connection-acquisition/reconnect layer (monitor-synchronised, no timeout
 * applies there), and successive health {@code PING}s queued behind it for 161&ndash;836
 * <em>seconds</em> on a build that already carried the 2s property. Readiness — and with it the
 * Docker {@code HEALTHCHECK} (5s budget) — hung for ~15 minutes. This wrapper bounds the whole
 * check at the health layer, immune to which Lettuce-internal layer stalls.
 *
 * <p><b>Wiring.</b> The bean is deliberately named {@code redisHealthIndicator}: Boot's {@code
 * DataRedisReactiveHealthContributorAutoConfiguration} backs off on exactly that name, so this bean
 * transparently replaces the auto-configured indicator while keeping the contributor key {@code
 * redis} (bean name minus the {@code HealthIndicator} suffix) — the {@code readiness} health-group
 * include in {@code application.yml} continues to match. {@code
 * management.health.redis.enabled=false} disables it exactly like the auto-configured bean it
 * replaces.
 *
 * <p>A timed-out check reports {@code DOWN} truthfully: a Redis that cannot answer a {@code PING}
 * within {@link #TIMEOUT} cannot serve Spring Session either, so failing readiness is the correct
 * signal, and it now arrives deterministically instead of after the edge of a multi-minute queue.
 */
@Component("redisHealthIndicator")
@ConditionalOnEnabledHealthIndicator("redis")
public class BoundedRedisHealthIndicator implements ReactiveHealthIndicator {

  /**
   * Upper bound for the whole health check. Sits above the 2s Lettuce command timeout (ADR-0114) so
   * a regular slow-command failure keeps its own, more specific error detail, and below the 5s
   * Docker {@code HEALTHCHECK} budget so a wedged connection surfaces as a deterministic {@code
   * DOWN} instead of an infrastructure-level probe timeout.
   */
  static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final ReactiveHealthIndicator delegate;
  private final Duration timeout;

  /**
   * Production constructor used by Spring; wraps the real {@link DataRedisReactiveHealthIndicator}
   * with the {@link #TIMEOUT} bound. {@link Autowired} is required because the class declares a
   * second (package-private, test-only) constructor; without it Spring 4+'s constructor-selection
   * logic falls back to a non-existent default constructor and fails at startup.
   *
   * @param connectionFactory the reactive Redis connection factory the delegate {@code PING}s over
   *     (the shared auto-configured {@code LettuceConnectionFactory})
   */
  @Autowired
  public BoundedRedisHealthIndicator(@NotNull ReactiveRedisConnectionFactory connectionFactory) {
    this(new DataRedisReactiveHealthIndicator(connectionFactory), TIMEOUT);
  }

  /**
   * Visible-for-testing constructor that lets unit tests substitute a scripted delegate and a short
   * timeout to make the bound observable without a real Redis.
   *
   * @param delegate the health indicator whose result is bounded
   * @param timeout maximum wall-clock time the delegate may take before the check is reported
   *     {@code DOWN}
   */
  BoundedRedisHealthIndicator(
      @NotNull ReactiveHealthIndicator delegate, @NotNull Duration timeout) {
    this.delegate = delegate;
    this.timeout = timeout;
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
