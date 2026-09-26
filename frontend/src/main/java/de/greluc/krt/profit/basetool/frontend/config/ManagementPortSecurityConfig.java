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
 * Opens {@code /actuator/**} on the dedicated management port (ADR-0090). Active only when {@code
 * management.server.port} is set; otherwise Actuator stays on the public connector behind {@link
 * MonitoringScrapeSecurityConfig}.
 *
 * <p>The management port is reachable only from the monitoring network and {@code localhost}, so it
 * needs no authentication (REQ-OBS-005).
 */
@Configuration
@ConditionalOnProperty(name = "management.server.port")
public class ManagementPortSecurityConfig {

  /**
   * Permit-all, stateless chain for {@code /actuator/**}, ordered before {@link
   * MonitoringScrapeSecurityConfig} and the main {@link SecurityConfig} chain. CSRF stays enabled,
   * which costs nothing because the chain serves only GETs.
   *
   * @param http the Spring Security builder for this chain
   * @return the permit-all Actuator filter chain for the management port
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  @Order(0)
  public SecurityFilterChain managementPortActuatorFilterChain(@NotNull HttpSecurity http)
      throws Exception {
    http.securityMatcher("/actuator/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .requestCache(RequestCacheConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
