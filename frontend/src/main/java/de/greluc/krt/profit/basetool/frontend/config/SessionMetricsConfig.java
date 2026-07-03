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

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.session.SessionRegistry;

/**
 * Binds the {@code basetool_active_sessions} gauge to the Spring Session-backed {@link
 * SessionRegistry} (REQ-OBS-011) — the count of active sessions across all logged-in principals, a
 * proxy for concurrent live users.
 *
 * <p>The gauge samples the registry lazily on each Prometheus scrape (no stored id or username is
 * ever a label — the value is a pure count). Gated to the non-{@code test} profiles because the
 * {@link SessionRegistry} bean itself is {@code @Profile("!test")} (see {@code
 * RedisSessionConfig}); under the test profile the session registry is absent, so this config — and
 * the gauge — simply do not load.
 */
@Configuration
@Profile("!test")
@RequiredArgsConstructor
public class SessionMetricsConfig {

  private final SessionRegistry sessionRegistry;
  private final MeterRegistry meterRegistry;

  /** Registers the active-session gauge against the session registry once at startup. */
  @PostConstruct
  void registerActiveSessionsGauge() {
    Gauge.builder(
            MetricNames.ACTIVE_SESSIONS, sessionRegistry, SessionMetricsConfig::countActiveSessions)
        .description("Active Spring Session sessions across all logged-in principals.")
        .register(meterRegistry);
  }

  /**
   * Sums the non-expired sessions of every registered principal.
   *
   * @param registry the Spring Security session registry backed by Spring Session
   * @return the total number of active sessions
   */
  private static double countActiveSessions(SessionRegistry registry) {
    long total = 0L;
    for (Object principal : registry.getAllPrincipals()) {
      total += registry.getAllSessions(principal, false).size();
    }
    return total;
  }
}
