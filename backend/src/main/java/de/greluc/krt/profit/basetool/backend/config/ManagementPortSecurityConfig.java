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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Opens the <b>read-only</b> Actuator endpoints for the backend's dedicated management port
 * (ADR-0090, extended to the backend by ADR-0134).
 *
 * <p>Active only when {@code management.server.port} is configured — the prod profile and {@code
 * ManagementPortIsolationTest}. In dev, test and e2e no management port exists, this configuration
 * is absent, and Actuator stays on the application connector guarded fail-closed by {@code
 * MonitoringScrapeSecurityConfig} exactly as before.
 *
 * <p><b>Why the matcher is narrower than the frontend's and ingest's.</b> Those two modules permit
 * all of {@code /actuator/**} on their management port and compensate by removing the log-level
 * write entirely ({@code management.endpoint.loggers.access: read-only}), because an
 * unauthenticated port offers no identity to gate a mutator on. The backend does not have to make
 * that trade: it is an OAuth2 resource server, so the main {@code SecurityConfig} chain still
 * applies on the management connector to every path this chain does not claim. Listing only the
 * three read endpoints therefore keeps {@code POST /actuator/loggers/**} on its {@code ROLE_ADMIN}
 * gate (REQ-OBS-016) while still letting a credential-free Prometheus scrape and Docker health
 * probe through. Widening this matcher to {@code /actuator/**} would silently un-gate the mutator,
 * and setting {@code ROOT} to {@code TRACE} makes Spring Security, WebClient and Netty write bearer
 * tokens and request bodies into a Loki stream retained for 744 h.
 *
 * <p>The endpoints opened here carry no authentication, matching Keycloak's internal port 9000
 * (REQ-SEC-014): the management port is reachable only from {@code net-monitoring-scrape} and
 * {@code localhost}, never host-published and never on an NPM proxy network, so ADR-0072's
 * fail-closed basic auth is superseded by the port isolation for this module too.
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
   * Permit-all chain scoped to the read-only Actuator endpoints, ordered ahead of {@code
   * MonitoringScrapeSecurityConfig} ({@code @Order(1)}) and the main chain.
   *
   * <p>On the application connector the matcher is inert, because in prod Actuator is not mapped
   * there at all — a stray {@code /actuator/**} request to the app port still yields 404. Stateless
   * with the request cache disabled: these are credential-free machine GETs with no browser
   * session.
   *
   * <p>CSRF protection is deliberately left <b>on</b> rather than disabled, unlike the frontend and
   * ingest copies of this class. It costs nothing here — Spring only enforces a token on unsafe
   * methods, and every endpoint this chain matches is a GET — while a {@code csrf().disable()} on a
   * permit-all chain is a genuine CodeQL finding ({@code java/spring-disabled-csrf-protection})
   * that a reader then has to re-triage every time. Leaving the default in place removes the
   * finding instead of arguing with it.
   *
   * @param http the Spring Security builder for this chain.
   * @return the permit-all filter chain for the management port's read endpoints.
   * @throws Exception propagated from {@link HttpSecurity#build()}.
   */
  @Bean
  @Order(0)
  public SecurityFilterChain managementPortActuatorFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(UNAUTHENTICATED_READ_ENDPOINTS)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .requestCache(RequestCacheConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
