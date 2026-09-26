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

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;

/**
 * A role granted to a user in one specific org unit, with the string form {@code
 * ROLE_<NAME>@<orgUnitUuid>}; emitted alongside the flat {@code ROLE_*} authority.
 *
 * <p>When the org unit id is only known at runtime, use {@link
 * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#hasRoleInOrgUnit}.
 *
 * @param roleName the role, without the {@code ROLE_} prefix (e.g. {@code "LOGISTICIAN"}); not
 *     {@code null}
 * @param orgUnitId the org unit this authority is scoped to; not {@code null}
 */
public record OrgUnitContextualAuthority(String roleName, UUID orgUnitId)
    implements GrantedAuthority {

  /**
   * Compact constructor enforcing the non-null contract on both fields.
   *
   * @param roleName the role name; never {@code null}.
   * @param orgUnitId the OrgUnit id; never {@code null}.
   */
  public OrgUnitContextualAuthority {
    Objects.requireNonNull(roleName, "roleName");
    Objects.requireNonNull(orgUnitId, "orgUnitId");
  }

  /**
   * Returns the string form {@code ROLE_<roleName>@<orgUnitUuid>}, matched verbatim by {@code
   * hasAuthority(...)}.
   *
   * @return the authority string; never {@code null}
   */
  @NotNull
  @Override
  public String getAuthority() {
    return "ROLE_" + roleName + "@" + orgUnitId;
  }
}
