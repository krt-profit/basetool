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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.config.SecurityConfig;
import de.greluc.krt.profit.basetool.backend.kernel.Permissions;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.util.SimpleMethodInvocation;

/**
 * Proves the role and authority gates that were lifted from the URL rules of {@code SecurityConfig}
 * into the controller annotations: each is evaluated here from the annotation alone, with the real
 * role hierarchy and no HTTP layer, so the URL rule cannot be the one deciding.
 */
class LiftedRoleGatesTest {

  private static final ObjenesisStd OBJENESIS = new ObjenesisStd();

  private static final PreAuthorizeAuthorizationManager MANAGER = manager();

  private static PreAuthorizeAuthorizationManager manager() {
    DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
    handler.setRoleHierarchy(SecurityConfig.roleHierarchy());
    PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();
    manager.setExpressionHandler(handler);
    return manager;
  }

  private static Method method(Class<?> type, String name) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  static Stream<Method> inventoryGates() {
    return Stream.of(
            "getAggregatedInventory",
            "getInventoryByMaterial",
            "getInventoryByGameItem",
            "getAllInventory",
            "getMissionInventory",
            "getAllGroupedInventory",
            "getAllStackEntries",
            "getItemCatalog",
            "createInventoryItem",
            "bulkCheckout",
            "bulkChangeOrgUnit",
            "bulkMarkStolen",
            "bulkRebook")
        .map(name -> method(InventoryItemController.class, name));
  }

  static Stream<Method> hangarGates() {
    return Stream.of("getSquadronOverview", "setHomeLocationForMyShips")
        .map(name -> method(HangarController.class, name));
  }

  private static boolean granted(Method method, String... authorities) {
    Object target = OBJENESIS.newInstance(method.getDeclaringClass());
    Authentication authentication =
        new TestingAuthenticationToken(
            "subject", "n/a", AuthorityUtils.createAuthorityList(authorities));
    return MANAGER
        .authorize(() -> authentication, new SimpleMethodInvocation(target, method))
        .isGranted();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inventoryGates")
  void inventoryGateAdmitsExactlyTheFourRolesTheUrlRuleAdmitted(Method method) {
    for (String role : List.of(Roles.ADMIN, Roles.OFFICER, Roles.LOGISTICIAN, Roles.KRT_MEMBER)) {
      assertThat(granted(method, Roles.authority(role))).as(role).isTrue();
    }
    for (String role : List.of(Roles.BANK_EMPLOYEE, Roles.BANK_MANAGEMENT, Roles.MISSION_MANAGER)) {
      assertThat(granted(method, Roles.authority(role))).as(role).isFalse();
    }
    assertThat(granted(method, Roles.NO_ROLE_MARKER)).as("NO_ROLE").isFalse();
    assertThat(granted(method)).as("no authority").isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hangarGates")
  void hangarGateAdmitsExactlyTheAuthoritiesTheUrlRuleAdmitted(Method method) {
    assertThat(granted(method, Permissions.HANGAR_READ)).as("HANGAR_READ").isTrue();
    assertThat(granted(method, Permissions.HANGAR_WRITE)).as("HANGAR_WRITE").isTrue();
    assertThat(granted(method, Roles.authority(Roles.ADMIN))).as("ADMIN").isTrue();
    for (String role :
        List.of(Roles.KRT_MEMBER, Roles.OFFICER, Roles.LOGISTICIAN, Roles.BANK_MANAGEMENT)) {
      assertThat(granted(method, Roles.authority(role))).as(role).isFalse();
    }
    assertThat(granted(method, Roles.NO_ROLE_MARKER)).as("NO_ROLE").isFalse();
    assertThat(granted(method, Permissions.MISSION_READ)).as("MISSION_READ").isFalse();
  }

  @Test
  void everyLiftedHandlerCarriesTheSharedGateConstantOnTheMethod() {
    inventoryGates()
        .forEach(
            m ->
                assertThat(m.getAnnotation(PreAuthorize.class).value())
                    .as(m.getName())
                    .isEqualTo(InventoryItemController.INVENTORY_ACCESS));
    hangarGates()
        .forEach(
            m ->
                assertThat(m.getAnnotation(PreAuthorize.class).value())
                    .as(m.getName())
                    .isEqualTo(HangarController.HANGAR_ACCESS));
  }
}
