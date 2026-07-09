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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code app.keycloak.sync.*}.
 *
 * <p>Drives {@link de.greluc.krt.profit.basetool.backend.task.UserSyncTask}: the admin URL, realm
 * and client credentials let the backend authenticate against the Keycloak Admin API; {@code cron}
 * plus {@code zone} set the once-per-day cadence; {@code enabled} short-circuits the task in
 * environments where the Admin API is unreachable (e.g. CI). All values are validated at startup so
 * a missing secret fails the boot rather than producing 401s at the first scheduled run.
 */
@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "app.keycloak.sync")
public class KeycloakSyncProperties {
  /** Whether to enable the periodic user sync. */
  private boolean enabled = true;

  /**
   * Spring cron expression (6-field {@code sec min hour dom mon dow}) for the daily reconciliation.
   * Defaults to {@code 0 0 5 * * *} — 05:00 every day, off-peak. The sync is a drift-correction
   * safety net, not a live feed: a once-per-day cadence keeps its Keycloak Admin-API load a single
   * off-peak burst instead of the pre-2026-07 per-minute hammering that accelerated the
   * native-thread exhaustion incident. Admins who need an immediate refresh use the "Sync now"
   * button (POST {@code /api/v1/users/sync}) rather than a hot schedule. Interpreted in {@link
   * #zone}. A syntactically invalid expression fails the context at startup (Spring parses it when
   * wiring {@code @Scheduled}), so a typo can never silently disable the sync.
   */
  @NotBlank private String cron = "0 0 5 * * *";

  /**
   * IANA time-zone id the {@link #cron} expression is evaluated in. Defaults to {@code
   * Europe/Berlin} so "05:00" tracks the org's local night across DST rather than drifting with the
   * host's UTC clock.
   */
  @NotBlank private String zone = "Europe/Berlin";

  /** Keycloak base URL for admin API (e.g. http://localhost:8080). */
  @NotBlank @URL private String adminUrl;

  /** Realm to sync users from. */
  @NotBlank private String realm;

  /** Client ID for admin access (must have manage-users or view-users role). */
  @NotBlank private String clientId;

  /** Client Secret for admin access. */
  @NotBlank private String clientSecret;

  /**
   * Page size for the Keycloak Admin API user listing. The {@code GET /users} endpoint caps each
   * response at a server-side maximum (~100 by default), so the sync must page through {@code
   * first}/{@code max} until a short page returns; without paging it would only ever see the first
   * page and then wrongly flag every user beyond it as missing. Bounded to a sane range.
   */
  @Min(1)
  @Max(1000)
  private int pageSize = 100;
}
