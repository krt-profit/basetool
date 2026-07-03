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

package de.greluc.krt.profit.basetool.ingest.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dedicated, fail-closed security filter chain for the Prometheus scrape endpoint {@code
 * /actuator/prometheus} (REQ-OBS-005, ADR-0072, epic #936 Phase 1). Mirrors the backend/frontend
 * configs of the same name.
 *
 * <p>Design decisions, all deliberate:
 *
 * <ul>
 *   <li><b>Own chain, ordered before the main chain</b> ({@code @Order(1)}, {@code securityMatcher}
 *       on exactly this path): the scrape must not ride the JWT resource-server rules of {@link
 *       SecurityConfig} — a Prometheus server holds no Keycloak token, and the metrics payload must
 *       never become reachable with a stolen extractor JWT either (particularly relevant on this
 *       internet-facing gateway). Only the dedicated basic-auth identity counts.
 *   <li><b>Fail-closed:</b> when {@link MonitoringScrapeProperties#isConfigured()} is {@code false}
 *       (env vars unset — dev, test, e2e, prod before the monitoring rollout) the chain is built
 *       with {@code denyAll()}; there is no unauthenticated fallback.
 *   <li><b>Basic auth against an in-memory user:</b> the single scrape principal exists only in
 *       this chain's local {@link InMemoryUserDetailsManager}; it is not a bean, so it can never
 *       leak into the main chain's authentication. The plaintext env value is BCrypt-hashed at
 *       startup via the delegating encoder ({@code {bcrypt}} storage format).
 *   <li><b>Stateless, no CSRF, no request cache:</b> the scraper is a machine calling with
 *       credentials on every request; sessions or saved requests would only create garbage state.
 *       CSRF does not apply to a credentialed GET with no browser session.
 * </ul>
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
   * Builds the scrape filter chain described in the class Javadoc. Ordered before the main {@link
   * SecurityConfig} chain so {@code /actuator/prometheus} never falls through to the JWT
   * resource-server rules.
   *
   * @param http the Spring Security builder for this chain
   * @return the configured chain — basic-auth-gated when credentials are configured, deny-all
   *     otherwise
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  @Order(1)
  public SecurityFilterChain monitoringScrapeFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(PROMETHEUS_PATH)
        // CSRF protection is deliberately off on this chain: it is a stateless, basic-auth-only
        // machine endpoint with no session cookie, so there is no browser credential a forged
        // cross-site request could ride on - the rule does not apply to this call site. A 30 s
        // scrape interval must also not accumulate sessions or saved requests.
        // lgtm[java/spring-disabled-csrf-protection]
        .csrf(AbstractHttpConfigurer::disable)
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
      // Fail-closed: no credentials configured -> nobody reaches the metrics payload. The
      // default Http403ForbiddenEntryPoint answers every request with 403.
      http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
    }
    return http.build();
  }
}
