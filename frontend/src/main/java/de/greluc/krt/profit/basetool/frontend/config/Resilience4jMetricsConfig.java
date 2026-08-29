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

package de.greluc.krt.profit.basetool.frontend.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the Resilience4j meters that the alert rules and the Spring-apps dashboard are written
 * against, because Resilience4j's own auto-configuration silently stopped doing it on Spring Boot
 * 4.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>Resilience4j 2.4.0 registers its metrics publishers from {@code
 * CircuitBreakerMetricsAutoConfiguration} and friends, each guarded by
 * {@code @ConditionalOnBean(MeterRegistry.class)} and ordered with {@code @AutoConfigureAfter(name
 * = "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration")}.
 *
 * <p>Spring Boot 4 moved that class out of the actuator jar into the new {@code
 * spring-boot-micrometer-metrics} module, so the name no longer resolves — and
 * {@code @AutoConfigureAfter(name = …)} <b>ignores a class it cannot find</b> rather than failing.
 * The ordering hint evaporates, the Resilience4j auto-configuration runs before the {@code
 * MeterRegistry} bean definition exists, {@code @ConditionalOnBean} finds nothing, and the
 * publishers are never created.
 *
 * <p>Nothing about that is visible in a log. Spring's condition report even reports the
 * auto-configuration itself as {@code matched}; only its inner {@code @Bean} methods are skipped.
 * The symptom is three alert rules that can never fire and a dashboard row that is always empty — a
 * dead alert, which REQ-OBS-014 treats as worse than no alert because it reads as coverage. Found
 * on 2026-08-29 by reading the production Prometheus: the whole {@code resilience4j_*} family had
 * no series at all.
 *
 * <h2>Why a MeterBinder rather than the publishers</h2>
 *
 * <p>Declaring the publisher beans here would inherit the same ordering hazard. A {@link
 * MeterBinder} does not: Spring Boot binds every {@code MeterBinder} bean to the registry
 * <em>after</em> the registry exists, which is precisely the mechanism this situation calls for.
 * The three {@code Tagged*Metrics} types already implement it.
 *
 * <p>Remove this class if a Resilience4j release fixes the ordering for Boot 4 — and remove it only
 * with {@code AlertedMeterPresenceTest} still green, which is the check that would notice.
 */
@Configuration(proxyBeanMethods = false)
public class Resilience4jMetricsConfig {

  /**
   * Binds the circuit-breaker meters, including {@code resilience4j_circuitbreaker_state} — the
   * metric the {@code CircuitBreakerOpen} alert is written against.
   *
   * @param registry the auto-configured circuit-breaker registry
   * @return the binder Spring Boot attaches to the meter registry once it exists
   */
  @Bean
  @NotNull
  public MeterBinder circuitBreakerMetrics(@NotNull CircuitBreakerRegistry registry) {
    return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
  }

  /**
   * Binds the bulkhead meters, including {@code resilience4j_bulkhead_available_concurrent_calls} —
   * the metric the bulkhead-saturation alert is written against.
   *
   * @param registry the auto-configured bulkhead registry
   * @return the binder Spring Boot attaches to the meter registry once it exists
   */
  @Bean
  @NotNull
  public MeterBinder bulkheadMetrics(@NotNull BulkheadRegistry registry) {
    return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
  }

  /**
   * Binds the retry meters, including {@code resilience4j_retry_calls_total} — the metric the
   * retry-rate alert is written against.
   *
   * @param registry the auto-configured retry registry
   * @return the binder Spring Boot attaches to the meter registry once it exists
   */
  @Bean
  @NotNull
  public MeterBinder retryMetrics(@NotNull RetryRegistry registry) {
    return TaggedRetryMetrics.ofRetryRegistry(registry);
  }
}
