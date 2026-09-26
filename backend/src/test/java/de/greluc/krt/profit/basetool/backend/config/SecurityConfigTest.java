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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Unit test for {@link SecurityConfig#roleHierarchy()}: pins the exact reachability semantics of
 * the hierarchy built from {@link Roles} constants.
 */
class SecurityConfigTest {

  private final RoleHierarchy hierarchy = SecurityConfig.roleHierarchy();

  private Set<String> reachableFrom(String bareRole) {
    Collection<? extends GrantedAuthority> reachable =
        hierarchy.getReachableGrantedAuthorities(
            List.of(new SimpleGrantedAuthority(Roles.authority(bareRole))));
    return reachable.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
  }

  @Test
  void admin_reachesLogisticianAndMissionManager() {
    Set<String> reachable = reachableFrom(Roles.ADMIN);
    assertTrue(reachable.contains(Roles.authority(Roles.LOGISTICIAN)));
    assertTrue(reachable.contains(Roles.authority(Roles.MISSION_MANAGER)));
  }

  @Test
  void admin_reachesBankManagementAndTransitivelyBankEmployee() {
    Set<String> reachable = reachableFrom(Roles.ADMIN);
    assertTrue(reachable.contains(Roles.authority(Roles.BANK_MANAGEMENT)));
    assertTrue(
        reachable.contains(Roles.authority(Roles.BANK_EMPLOYEE)),
        "ADMIN > BANK_MANAGEMENT > BANK_EMPLOYEE must be transitive");
  }

  @Test
  void officer_reachesLogisticianAndMissionManagerButNotBankRoles() {
    Set<String> reachable = reachableFrom(Roles.OFFICER);
    assertTrue(reachable.contains(Roles.authority(Roles.LOGISTICIAN)));
    assertTrue(reachable.contains(Roles.authority(Roles.MISSION_MANAGER)));
    assertFalse(
        reachable.contains(Roles.authority(Roles.BANK_MANAGEMENT)),
        "OFFICER must not imply BANK_MANAGEMENT — only ADMIN does");
    assertFalse(reachable.contains(Roles.authority(Roles.BANK_EMPLOYEE)));
  }

  @Test
  void bankManagement_reachesBankEmployeeButNotLogisticianOrMissionManager() {
    Set<String> reachable = reachableFrom(Roles.BANK_MANAGEMENT);
    assertTrue(reachable.contains(Roles.authority(Roles.BANK_EMPLOYEE)));
    assertFalse(reachable.contains(Roles.authority(Roles.LOGISTICIAN)));
    assertFalse(reachable.contains(Roles.authority(Roles.MISSION_MANAGER)));
  }

  @Test
  void krtMember_hasNoImpliedRoles() {
    Set<String> reachable = reachableFrom(Roles.KRT_MEMBER);
    assertTrue(
        reachable.equals(Set.of(Roles.authority(Roles.KRT_MEMBER))),
        "KRT_MEMBER carries no hierarchy entries, so it only reaches itself: " + reachable);
  }
}
