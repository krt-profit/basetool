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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The Keycloak subjects that receive the Admin role at login while no active admin exists
 * (REQ-SEC-088, ADR-0355). Empty by default; a value that is not a UUID fails the startup.
 *
 * @param adminSubs the Keycloak {@code sub} values of the bootstrap admins
 */
@Validated
@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapAdminProperties(@DefaultValue List<@NotNull UUID> adminSubs) {

  /**
   * Whether a subject is one of the bootstrap admins.
   *
   * @param subject the Keycloak {@code sub} of a user
   * @return {@code true} when {@link #adminSubs} lists it
   */
  public boolean isBootstrapSubject(UUID subject) {
    return adminSubs.contains(subject);
  }
}
