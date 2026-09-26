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
import org.hibernate.validator.constraints.URL;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the scheduled Keycloak user sync under {@code app.keycloak.sync.*}, used by
 * {@link de.greluc.krt.profit.basetool.backend.task.UserSyncTask}.
 *
 * <p>Validated at startup; {@link #toString()} redacts the client secret.
 *
 * @param enabled whether the periodic user sync runs
 * @param cron the 6-field Spring cron expression of the sync; defaults to {@code 0 0 5 * * *}
 * @param zone the IANA time zone {@code cron} is evaluated in; defaults to {@code Europe/Berlin}
 * @param adminUrl the Keycloak base URL for the Admin API
 * @param realm the realm to sync users from
 * @param clientId the admin client id; needs {@code manage-users} or {@code view-users}
 * @param clientSecret the admin client secret; never printed by {@link #toString()}
 * @param pageSize the page size used to page through the Admin API user listing
 */
@Validated
@ConfigurationProperties(prefix = "app.keycloak.sync")
public record KeycloakSyncProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("0 0 5 * * *") @NotBlank String cron,
    @DefaultValue("Europe/Berlin") @NotBlank String zone,
    @NotBlank @URL String adminUrl,
    @NotBlank String realm,
    @NotBlank String clientId,
    @NotBlank String clientSecret,
    @DefaultValue("100") @Min(1) @Max(1000) int pageSize) {

  /**
   * Describes the record without its client secret, so a logged or printed instance never carries
   * it.
   *
   * @return every component, the secret shown only as configured or blank
   */
  @Override
  @NotNull
  public String toString() {
    boolean hasSecret = clientSecret != null && !clientSecret.isBlank();
    return "KeycloakSyncProperties[enabled="
        + enabled
        + ", cron="
        + cron
        + ", zone="
        + zone
        + ", adminUrl="
        + adminUrl
        + ", realm="
        + realm
        + ", clientId="
        + clientId
        + ", clientSecret="
        + (hasSecret ? "<redacted>" : "")
        + ", pageSize="
        + pageSize
        + "]";
  }
}
