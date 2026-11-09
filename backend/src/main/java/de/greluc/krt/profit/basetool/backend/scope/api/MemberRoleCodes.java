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

package de.greluc.krt.profit.basetool.backend.scope.api;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The flat role codes a member holds from the database (REQ-SEC-088): the roles stored in {@code
 * user_roles} and the {@code LOGISTICIAN} / {@code MISSION_MANAGER} codes their memberships confer,
 * before the role hierarchy applies. The one derivation behind the token authorities of {@link
 * CustomJwtGrantedAuthoritiesConverter} and every actor read from the database.
 */
public final class MemberRoleCodes {

  /** Not instantiable. */
  private MemberRoleCodes() {}

  /**
   * The role codes of an approved member; a pending or rejected registration holds none
   * (REQ-SEC-017).
   *
   * @param user the member, with their stored roles
   * @param memberships the member's membership rows
   * @return the role codes without the {@code ROLE_} prefix; empty for a non-approved registration
   */
  @NotNull
  @Unmodifiable
  public static Set<String> of(
      @NotNull User user, @NotNull Collection<OrgUnitMembership> memberships) {
    if (!user.isApproved()) {
      return Set.of();
    }
    Set<String> codes = new LinkedHashSet<>();
    for (Role role : user.getRoles()) {
      codes.add(codeOf(role));
    }
    codes.addAll(derivedFromMemberships(memberships, codes.contains(Roles.KRT_MEMBER)));
    return Set.copyOf(codes);
  }

  /**
   * The code a stored role grants as an authority: its stable {@link Role#getCode() code}, so a
   * renamed display label changes no authority.
   *
   * @param role the stored role
   * @return the role code without the {@code ROLE_} prefix
   */
  @NotNull
  static String codeOf(@NotNull Role role) {
    return role.getCode();
  }

  /**
   * The flat codes the memberships confer: {@code LOGISTICIAN} for a logistician of their own unit,
   * {@code MISSION_MANAGER} for a mission-manager flag or, while seats confer, any functional rank
   * (REQ-ROLE-001/002).
   *
   * @param memberships the member's membership rows
   * @param seatsConfer whether the member holds {@code KRT Member}, without which a rank confers
   *     nothing (ADR-0300)
   * @return the derived codes; empty when no membership confers one
   */
  @NotNull
  @Unmodifiable
  static Set<String> derivedFromMemberships(
      @NotNull Collection<OrgUnitMembership> memberships, boolean seatsConfer) {
    Set<String> derived = new LinkedHashSet<>();
    if (memberships.stream().anyMatch(m -> m.isLogisticianOfOwnUnit(seatsConfer))) {
      derived.add(Roles.LOGISTICIAN);
    }
    if (memberships.stream()
        .anyMatch(
            m -> m.isMissionManager() || (seatsConfer && m.getRole().confersOwnLevelOversight()))) {
      derived.add(Roles.MISSION_MANAGER);
    }
    return Set.copyOf(derived);
  }
}
