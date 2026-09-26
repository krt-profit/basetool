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
 * Basic-auth credentials for the Prometheus scrape endpoint, bound under {@code
 * app.monitoring.scrape} (REQ-OBS-005).
 *
 * <p>Both values are optional; while unset, {@link MonitoringScrapeSecurityConfig} denies every
 * request. {@link #toString()} redacts the password.
 *
 * @param username the scraper's basic-auth username; blank means no scraper is configured
 * @param password the scraper's basic-auth password, BCrypt-hashed at startup; blank means no
 *     scraper is configured
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
