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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Basic-auth credentials for the Prometheus scrape endpoint {@code /actuator/prometheus} (prefix
 * {@code app.monitoring.scrape}, fed by the {@code MONITORING_SCRAPE_USER} / {@code
 * MONITORING_SCRAPE_PASSWORD} environment variables — REQ-OBS-005, ADR-0072). Mirrors the
 * backend/frontend classes of the same name; each module keeps its own copy per the established
 * no-shared-module convention.
 *
 * <p>Both values are deliberately optional and carry no {@code @NotBlank} constraint: an
 * environment without a Prometheus scraper (dev, test, e2e, prod before the monitoring rollout)
 * simply leaves them unset. {@link MonitoringScrapeSecurityConfig} reacts fail-closed — with {@link
 * #isConfigured()} {@code false} the endpoint denies every request instead of falling back to an
 * unauthenticated default. Registered via {@code @ConfigurationPropertiesScan} on {@code
 * IngestApplication}.
 */
@Data
@ConfigurationProperties(prefix = "app.monitoring.scrape")
public class MonitoringScrapeProperties {

  /**
   * Username the Prometheus scraper presents via HTTP basic auth. Blank (the default) means "no
   * scraper in this environment" and keeps the endpoint in its fail-closed deny-all state.
   */
  private String username = "";

  /**
   * Password the Prometheus scraper presents via HTTP basic auth. Blank (the default) means "no
   * scraper in this environment" and keeps the endpoint in its fail-closed deny-all state. The
   * plaintext value from the environment is BCrypt-hashed at startup by {@link
   * MonitoringScrapeSecurityConfig}; it is never stored or logged beyond this binding.
   */
  private String password = "";

  /**
   * Whether a complete scrape credential pair is configured. Only when this returns {@code true}
   * does {@link MonitoringScrapeSecurityConfig} enable basic auth on {@code /actuator/prometheus};
   * otherwise the endpoint denies all requests (fail-closed, REQ-OBS-005).
   *
   * @return {@code true} when both {@link #username} and {@link #password} are non-blank
   */
  public boolean isConfigured() {
    return username != null && !username.isBlank() && password != null && !password.isBlank();
  }
}
