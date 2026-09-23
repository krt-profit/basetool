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
 * Configuration properties under {@code app.keycloak.sync.*}.
 *
 * <p>Drives {@link de.greluc.krt.profit.basetool.backend.task.UserSyncTask}: the admin URL, realm
 * and client credentials let the backend authenticate against the Keycloak Admin API; {@code cron}
 * plus {@code zone} set the once-per-day cadence; {@code enabled} short-circuits the task in
 * environments where the Admin API is unreachable (e.g. CI). All values are validated at startup so
 * a missing secret fails the boot rather than producing 401s at the first scheduled run.
 *
 * <p>An immutable record bound by {@code @ConfigurationPropertiesScan} (BE-MOD-04). Its {@link
 * #toString()} redacts the client secret, which the former Lombok {@code @Data} class printed.
 *
 * @param enabled whether the periodic user sync runs
 * @param cron the Spring cron expression (6-field {@code sec min hour dom mon dow}) for the daily
 *     reconciliation. Defaults to {@code 0 0 5 * * *} — 05:00 every day, off-peak. The sync is a
 *     drift-correction safety net, not a live feed: a once-per-day cadence keeps its Keycloak
 *     Admin-API load a single off-peak burst instead of the pre-2026-07 per-minute hammering that
 *     accelerated the native-thread exhaustion incident. Admins who need an immediate refresh use
 *     the "Sync now" button (POST {@code /api/v1/users/sync}) rather than a hot schedule. An
 *     invalid expression fails the context at startup (Spring parses it when wiring
 *     {@code @Scheduled}), so a typo can never silently disable the sync.
 * @param zone the IANA time-zone id {@code cron} is evaluated in. Defaults to {@code Europe/Berlin}
 *     so "05:00" tracks the organisation's local night across DST rather than drifting with the
 *     host's UTC clock.
 * @param adminUrl the Keycloak base URL for the Admin API (e.g. {@code http://localhost:8080})
 * @param realm the realm to sync users from
 * @param clientId the client id used for admin access; it must hold the {@code manage-users} or
 *     {@code view-users} role
 * @param clientSecret the client secret used for admin access; never printed by {@link #toString()}
 * @param pageSize the page size for the Keycloak Admin API user listing. {@code GET /users} caps
 *     each response at a server-side maximum (~100 by default), so the sync pages through {@code
 *     first}/{@code max} until a short page returns; without paging it would only ever see the
 *     first page and then wrongly flag every user beyond it as missing. Bounded to a sane range.
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
