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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-driven tests for {@link MeController}. The {@code GET /active-org-unit} read endpoint
 * pulls from the {@code OwnerScopeService.currentOrgUnitId()} resolver; the {@code GET
 * /capabilities} endpoint reflects the blueprint-overview gate (#364).
 */
@ExtendWith(MockitoExtension.class)
class MeControllerTest {

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuthHelperService authHelperService;

  @InjectMocks private MeController controller;

  @Test
  void getActiveOrgUnit_present_returnsUuid() {
    UUID active = UUID.randomUUID();
    when(ownerScopeService.currentOrgUnitId()).thenReturn(Optional.of(active));

    MeController.ActiveOrgUnitResponse response = controller.getActiveOrgUnit();

    assertEquals(active, response.orgUnitId());
    verify(ownerScopeService).currentOrgUnitId();
  }

  @Test
  void getActiveOrgUnit_empty_returnsNull() {
    when(ownerScopeService.currentOrgUnitId()).thenReturn(Optional.empty());

    assertNull(controller.getActiveOrgUnit().orgUnitId());
  }

  @Test
  void getCapabilities_reflectsBlueprintOverviewAccess_true() {
    when(ownerScopeService.canAccessBlueprintOverview()).thenReturn(true);

    assertTrue(controller.getCapabilities().canSeeBlueprintOverview());
    verify(ownerScopeService).canAccessBlueprintOverview();
  }

  @Test
  void getCapabilities_reflectsBlueprintOverviewAccess_false() {
    when(ownerScopeService.canAccessBlueprintOverview()).thenReturn(false);

    assertFalse(controller.getCapabilities().canSeeBlueprintOverview());
  }

  @Test
  void getCapabilities_reflectsJobOrderViewAccess_true() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);

    assertTrue(controller.getCapabilities().canViewJobOrders());
    verify(ownerScopeService).canViewJobOrders();
  }

  @Test
  void getCapabilities_reflectsJobOrderViewAccess_false() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    assertFalse(controller.getCapabilities().canViewJobOrders());
  }

  @Test
  void getCapabilities_bankEmployee_seesTheStaffSurfaceButNotTheLifecycle() {
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE))).thenReturn(true);
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);

    MeController.CapabilitiesResponse response = controller.getCapabilities();

    assertTrue(response.canViewBankStaff());
    assertFalse(response.canManageBank());
  }

  /**
   * The whole reason these two flags exist rather than the client reading role names.
   *
   * <p>A Bankleitung holds {@code BANK_MANAGEMENT} and <strong>not</strong> {@code BANK_EMPLOYEE};
   * the hierarchy is what connects them, and it lives here rather than on the wire. A client
   * matching role names would hide the staff bank from the people who run the bank &mdash; and the
   * names it would be matching are display names ({@code "Bank Employee"}), not the codes the gates
   * use.
   */
  @Test
  void getCapabilities_bankManagement_reachesTheEmployeeRoleThroughTheHierarchy() {
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE))).thenReturn(true);
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(true);

    MeController.CapabilitiesResponse response = controller.getCapabilities();

    assertTrue(response.canViewBankStaff());
    assertTrue(response.canManageBank());
  }

  @Test
  void getCapabilities_ordinaryMember_reachesNeither() {
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE)))
        .thenReturn(false);
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);

    MeController.CapabilitiesResponse response = controller.getCapabilities();

    assertFalse(response.canViewBankStaff());
    assertFalse(response.canManageBank());
  }
}
