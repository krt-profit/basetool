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

package de.greluc.krt.profit.basetool.backend.platform.internal;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Secures the read-only Actuator endpoints on the backend's dedicated management port (ADR-0134,
 * REQ-OBS-005).
 *
 * <p>Active only when {@code management.server.port} is set. Health is open; metrics and info
 * require the scrape identity of {@link MonitoringScrapeProperties} and are denied to everyone
 * while it is not configured. Every other path, {@code /actuator/loggers/**} included, keeps the
 * main chain's protection (REQ-OBS-016).
 */
@Configuration
@ConditionalOnProperty(name = "management.server.port")
@RequiredArgsConstructor
public class ManagementPortSecurityConfig {

  /** The health probes the container health check and the frontend call without credentials. */
  private static final String[] HEALTH_ENDPOINTS = {"/actuator/health", "/actuator/health/**"};

  /**
   * The Actuator surface this chain owns; every path absent from it keeps the main chain's
   * protection, which is what preserves the {@code ROLE_ADMIN} gate on the log-level mutator.
   */
  private static final String[] MANAGED_ENDPOINTS = {
    "/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/actuator/info"
  };

  /** The scrape credentials that unlock the metrics and info endpoints. */
  private final MonitoringScrapeProperties properties;

  /**
   * Stateless chain for the management port's read endpoints, ordered ahead of {@link
   * MonitoringScrapeSecurityConfig} and the main chain.
   *
   * @param http the Spring Security builder for this chain
   * @return the chain: health open, the rest behind the scrape identity or denied
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  @Order(0)
  public SecurityFilterChain managementPortActuatorFilterChain(@NotNull HttpSecurity http)
      throws Exception {
    http.securityMatcher(MANAGED_ENDPOINTS)
        .requestCache(RequestCacheConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (properties.isConfigured()) {
      http.userDetailsService(MonitoringScrapeSecurityConfig.scrapeUsers(properties))
          .httpBasic(Customizer.withDefaults())
          .authorizeHttpRequests(auth ->
              auth.requestMatchers(HEALTH_ENDPOINTS)
                  .permitAll()
                  .anyRequest()
                  .hasRole(MonitoringScrapeSecurityConfig.MONITORING_ROLE));
    } else {
      http.authorizeHttpRequests(
          auth -> auth.requestMatchers(HEALTH_ENDPOINTS).permitAll().anyRequest().denyAll());
    }
    return http.build();
  }
}
