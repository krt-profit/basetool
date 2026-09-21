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
 * SPEZIALKOMMANDO_PLAN.md §6.1 contextual GrantedAuthority subtype. Represents a role granted to a
 * user <em>in a specific OrgUnit</em> — e.g. "Logistician of Staffel IRIDIUM" — as opposed to the
 * flat {@code ROLE_LOGISTICIAN} that the R6.d converter still emits as the OR-union over all
 * memberships.
 *
 * <p>The string form is {@code ROLE_<NAME>@<orgUnitUuid>}, which lets a {@code @PreAuthorize} SpEL
 * expression match against {@code hasAuthority('ROLE_LOGISTICIAN@<uuid>')} when it knows the exact
 * OrgUnit id at evaluation time. For the common case where the OrgUnit id is only known at runtime
 * (the dto's {@code owningOrgUnitId}), callers should go through {@link
 * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#hasRoleInOrgUnit} instead — the
 * helper reads the {@code Authentication} object and matches contextual authorities by value,
 * regardless of the SpEL string the caller used.
 *
 * <p><b>Dual-track migration:</b> the converter emits BOTH the flat {@code ROLE_LOGISTICIAN} (R6.d
 * backwards-compatibility) AND this contextual form. Existing {@code hasRole('LOGISTICIAN')} SpEL
 * strings keep working unchanged; new code paths that need per-OrgUnit scoping can opt into the
 * contextual variant. The flat authority is the one that comes out with the destructive cleanup
 * release; the contextual one stays as the long-term shape.
 *
 * @param roleName the role this authority grants (e.g. {@code "LOGISTICIAN"} or {@code
 *     "MISSION_MANAGER"}). Stored without the {@code ROLE_} prefix; the prefix is added when {@link
 *     #getAuthority()} composes the string form. Must not be {@code null}.
 * @param orgUnitId the OrgUnit (Staffel or SK) this authority is scoped to. Must not be {@code
 *     null} — there is no "all-OrgUnits" form, that's what the flat authority is for.
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
   * Returns the canonical string form Spring Security consumes: {@code
   * ROLE_<roleName>@<orgUnitUuid>}. {@code hasAuthority('ROLE_LOGISTICIAN@<uuid>')} SpEL strings
   * match this verbatim. Equality is delegated to the record's auto-generated {@code equals} via
   * the underlying fields, so two instances with the same roleName + orgUnitId are interchangeable.
   *
   * @return the string Spring Security stores in the {@code Authentication} object; never {@code
   *     null}.
   */
  @NotNull
  @Override
  public String getAuthority() {
    return "ROLE_" + roleName + "@" + orgUnitId;
  }
}
