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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Keycloak clients whose tokens carry a deliberately incomplete realm-role claim that must never be
 * written to {@code app_user} (REQ-SEC-036).
 *
 * <p>Matched on the token's {@code azp}. Non-empty by default; an empty list disables the guard.
 *
 * @param clientIds the {@code azp} values whose role claim is not authoritative
 */
@Validated
@ConfigurationProperties(prefix = "app.security.partial-role-scope")
public record PartialRoleScopeProperties(@DefaultValue List<String> clientIds) {

  /**
   * Whether {@code azp} names a client whose realm-role claim must not be persisted. A blank or
   * absent {@code azp} never does.
   *
   * @param azp the authorized-party claim from the caller's token, may be {@code null}
   * @return {@code true} when this caller's role claim describes less than the whole member
   */
  public boolean isPartialRoleScopeClient(String azp) {
    return azp != null && !azp.isBlank() && clientIds.contains(azp);
  }
}
