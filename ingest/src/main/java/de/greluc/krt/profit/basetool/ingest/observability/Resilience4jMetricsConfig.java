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

package de.greluc.krt.profit.basetool.ingest.observability;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the gateway's Resilience4j circuit-breaker and bulkhead meters, which Resilience4j's own
 * auto-configuration does not register on Spring Boot 4, so {@code CircuitBreakerOpen} and the
 * exchange relay's capacity panels see the exchange's breaker and the large-change-set bulkhead
 * (REQ-OBS-005, REQ-XCH-023).
 */
@Configuration(proxyBeanMethods = false)
public class Resilience4jMetricsConfig {

  /**
   * Binds the circuit-breaker meters, including {@code resilience4j_circuitbreaker_state}.
   *
   * @param registry the auto-configured circuit-breaker registry
   * @return the binder Spring Boot attaches to the meter registry
   */
  @Bean
  @NotNull
  public MeterBinder circuitBreakerMetrics(@NotNull CircuitBreakerRegistry registry) {
    return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
  }

  /**
   * Binds the bulkhead meters, including {@code resilience4j_bulkhead_available_concurrent_calls}.
   *
   * @param registry the auto-configured bulkhead registry
   * @return the binder Spring Boot attaches to the meter registry
   */
  @Bean
  @NotNull
  public MeterBinder bulkheadMetrics(@NotNull BulkheadRegistry registry) {
    return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
  }
}
