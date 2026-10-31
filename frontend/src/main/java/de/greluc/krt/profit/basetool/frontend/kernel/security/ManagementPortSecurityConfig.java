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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

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
 * Secures {@code /actuator/**} on the dedicated management port (ADR-0090, REQ-OBS-005). Active
 * only when {@code management.server.port} is set; otherwise Actuator stays on the public connector
 * behind {@link MonitoringScrapeSecurityConfig}.
 *
 * <p>Health is open; every other Actuator path requires the scrape identity of {@link
 * MonitoringScrapeProperties} and is denied to everyone while it is not configured.
 */
@Configuration
@ConditionalOnProperty(name = "management.server.port")
@RequiredArgsConstructor
public class ManagementPortSecurityConfig {

  /** The health probes the container health check calls without credentials. */
  private static final String[] HEALTH_ENDPOINTS = {"/actuator/health", "/actuator/health/**"};

  /** The scrape credentials that unlock every Actuator path but health. */
  private final MonitoringScrapeProperties properties;

  /**
   * Stateless chain for {@code /actuator/**}, ordered before {@link MonitoringScrapeSecurityConfig}
   * and the main {@link SecurityConfig} chain. CSRF stays enabled, which costs nothing because the
   * chain serves only GETs.
   *
   * @param http the Spring Security builder for this chain
   * @return the chain: health open, the rest behind the scrape identity or denied
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  @Order(0)
  public SecurityFilterChain managementPortActuatorFilterChain(@NotNull HttpSecurity http)
      throws Exception {
    http.securityMatcher("/actuator/**")
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
