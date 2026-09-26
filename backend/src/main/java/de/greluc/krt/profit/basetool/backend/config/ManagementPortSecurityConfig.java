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

package de.greluc.krt.profit.basetool.backend.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Opens the read-only Actuator endpoints without authentication on the backend's dedicated
 * management port (ADR-0134).
 *
 * <p>Active only when {@code management.server.port} is set. Only the read endpoints are matched,
 * so {@code POST /actuator/loggers/**} stays behind the main chain's {@code ROLE_ADMIN} gate
 * (REQ-OBS-016).
 */
@Configuration
@ConditionalOnProperty(name = "management.server.port")
public class ManagementPortSecurityConfig {

  /**
   * The read-only Actuator surface the monitoring plane needs without credentials.
   *
   * <p>Deliberately enumerated rather than expressed as {@code /actuator/**}: every path absent
   * from this list keeps whatever protection the main chain gives it, which is what preserves the
   * {@code ROLE_ADMIN} gate on the log-level mutator.
   */
  private static final String[] UNAUTHENTICATED_READ_ENDPOINTS = {
    "/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/actuator/info"
  };

  /**
   * Permit-all, stateless chain for the read-only Actuator endpoints, ordered ahead of {@code
   * MonitoringScrapeSecurityConfig} and the main chain.
   *
   * @param http the Spring Security builder for this chain
   * @return the permit-all filter chain for the management port's read endpoints
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  @Order(0)
  public SecurityFilterChain managementPortActuatorFilterChain(@NotNull HttpSecurity http)
      throws Exception {
    http.securityMatcher(UNAUTHENTICATED_READ_ENDPOINTS)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .requestCache(RequestCacheConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
