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

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dedicated, fail-closed security filter chain for {@code /actuator/prometheus} (REQ-OBS-005,
 * ADR-0072).
 *
 * <p>Ordered before the main {@link SecurityConfig} chain, it accepts only basic auth against a
 * chain-local {@link InMemoryUserDetailsManager} with a BCrypt-hashed password, and denies
 * everything when {@link MonitoringScrapeProperties#isConfigured()} is {@code false}. Stateless and
 * without request cache; CSRF stays on and never fires for a {@code GET} scrape. {@link
 * BotProtectionFilter} must whitelist the path.
 */
@Configuration
@RequiredArgsConstructor
public class MonitoringScrapeSecurityConfig {

  /** Role granted to the single in-memory scrape user and required by this chain's matcher. */
  static final String MONITORING_ROLE = "MONITORING_SCRAPE";

  /** The exact servlet path this chain owns; kept identical across all three modules. */
  static final String PROMETHEUS_PATH = "/actuator/prometheus";

  private final MonitoringScrapeProperties properties;

  /**
   * Builds the scrape filter chain, ordered before the main {@link SecurityConfig} chain.
   *
   * @param http the Spring Security builder for this chain
   * @return the configured chain — basic-auth-gated when credentials are configured, deny-all
   *     otherwise
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  @Order(1)
  public SecurityFilterChain monitoringScrapeFilterChain(@NotNull HttpSecurity http)
      throws Exception {
    http.securityMatcher(PROMETHEUS_PATH)
        .requestCache(RequestCacheConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    if (properties.isConfigured()) {
      PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
      UserDetails scrapeUser =
          User.withUsername(properties.getUsername())
              .password(encoder.encode(properties.getPassword()))
              .roles(MONITORING_ROLE)
              .build();
      http.userDetailsService(new InMemoryUserDetailsManager(scrapeUser))
          .httpBasic(Customizer.withDefaults())
          .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(MONITORING_ROLE));
    } else {
      http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
    }
    return http.build();
  }
}
