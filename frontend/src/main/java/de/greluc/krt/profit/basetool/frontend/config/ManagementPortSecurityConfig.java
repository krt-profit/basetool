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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Opens {@code /actuator/**} for the ADR-0090 dedicated management port. Active <b>only</b> when a
 * separate {@code management.server.port} is configured — i.e. the prod profile (and this module's
 * {@code ManagementPortIsolationTest}); in dev/test/e2e no management port is set, this whole
 * configuration is absent (via {@link ConditionalOnProperty}), and Actuator stays on the public
 * connector guarded fail-closed by {@link MonitoringScrapeSecurityConfig}.
 *
 * <p>Rationale (ADR-0090, REQ-OBS-005 amended): the management port is reachable only from {@code
 * net-monitoring-scrape} (Prometheus) and {@code localhost} (the Docker {@code HEALTHCHECK}) —
 * never host-published and never on an NPM proxy network — so it cannot be reached from the
 * internet. The endpoint therefore does not need the ADR-0072 basic-auth compensating control (that
 * control existed for the edge-exposed connector, now superseded by port isolation), mirroring
 * Keycloak's unauthenticated internal management port 9000 (REQ-SEC-014). Without this chain the
 * main OAuth2 chain would answer {@code /actuator/prometheus} on the management port with a login
 * challenge (health is open by default), so Prometheus — which sends no credentials on these jobs —
 * could not scrape.
 */
@Configuration
@ConditionalOnProperty(name = "management.server.port")
public class ManagementPortSecurityConfig {

  /**
   * Permit-all chain scoped to {@code /actuator/**}, ordered before {@link
   * MonitoringScrapeSecurityConfig} (its {@code @Order(1)}) and the main {@link SecurityConfig}
   * chain so the management-port scrape and health probes are served without authentication. On the
   * public connector this matcher is inert because Actuator is not mapped there in prod (the
   * management endpoints moved to the dedicated port), so a stray {@code /actuator/**} request to
   * the public port still yields 404. Stateless with the request cache disabled — these are
   * credential-free machine GETs with no browser session.
   *
   * <p>CSRF protection is deliberately left <b>on</b> rather than disabled, matching the backend
   * copy of this class. It costs nothing: Spring only enforces a token on unsafe methods, and this
   * chain serves none — {@code management.endpoint.loggers.access: read-only} (REQ-OBS-016) removes
   * the one Actuator POST this module would otherwise expose, so every request the matcher sees is
   * a GET. A {@code csrf().disable()} on a permit-all chain, by contrast, is a standing CodeQL
   * finding ({@code java/spring-disabled-csrf-protection}, alerts 869/870) that a reader has to
   * re-triage every time. Leaving the default in place removes the finding instead of arguing with
   * it, and if a state-changing endpoint is ever added here it fails closed rather than open.
   *
   * @param http the Spring Security builder for this chain
   * @return the permit-all Actuator filter chain for the management port
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  @Order(0)
  public SecurityFilterChain managementPortActuatorFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/actuator/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .requestCache(RequestCacheConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
