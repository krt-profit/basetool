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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Basic-auth credentials for the Prometheus scrape endpoint {@code /actuator/prometheus} (prefix
 * {@code app.monitoring.scrape}, fed by the {@code MONITORING_SCRAPE_USER} / {@code
 * MONITORING_SCRAPE_PASSWORD} environment variables — REQ-OBS-005, ADR-0072).
 *
 * <p>Both values are deliberately optional and carry no {@code @NotBlank} constraint: an
 * environment without a Prometheus scraper (dev, test, e2e, prod before the monitoring rollout)
 * simply leaves them unset. {@link MonitoringScrapeSecurityConfig} reacts fail-closed — with {@link
 * #isConfigured()} {@code false} the endpoint denies every request instead of falling back to an
 * unauthenticated default. An immutable record registered via {@code @ConfigurationPropertiesScan}
 * on {@code BackendApplication} (BE-MOD-04); its {@link #toString()} redacts the password.
 *
 * @param username the username the Prometheus scraper presents via HTTP basic auth. Blank (the
 *     default) means "no scraper in this environment" and keeps the endpoint in its fail-closed
 *     deny-all state.
 * @param password the password the Prometheus scraper presents via HTTP basic auth. Blank (the
 *     default) means "no scraper in this environment" and keeps the endpoint in its fail-closed
 *     deny-all state. The plaintext value from the environment is BCrypt-hashed at startup by
 *     {@link MonitoringScrapeSecurityConfig}; it is never stored or logged beyond this binding.
 */
@ConfigurationProperties(prefix = "app.monitoring.scrape")
public record MonitoringScrapeProperties(
    @DefaultValue("") String username, @DefaultValue("") String password) {

  /**
   * Whether a complete scrape credential pair is configured. Only when this returns {@code true}
   * does {@link MonitoringScrapeSecurityConfig} enable basic auth on {@code /actuator/prometheus};
   * otherwise the endpoint denies all requests (fail-closed, REQ-OBS-005).
   *
   * @return {@code true} when both {@code username} and {@code password} are non-blank
   */
  public boolean isConfigured() {
    return username != null && !username.isBlank() && password != null && !password.isBlank();
  }

  /**
   * Describes the record without its password, so a logged or printed instance never carries it.
   *
   * @return the record name with the username and the password shown only as configured or blank
   */
  @Override
  @NotNull
  public String toString() {
    boolean hasPassword = password != null && !password.isBlank();
    return "MonitoringScrapeProperties[username="
        + username
        + ", password="
        + (hasPassword ? "<redacted>" : "")
        + "]";
  }
}
