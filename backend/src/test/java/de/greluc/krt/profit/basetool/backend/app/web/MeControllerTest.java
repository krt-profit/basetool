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

package de.greluc.krt.profit.basetool.backend.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssigneeLookup;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingTabAccess;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingTabAccess.PurchasingTabGrants;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyTabAccess;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyTabAccess.SupplyTabGrants;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MeController}: the active-org-unit read and the capabilities endpoint. */
@ExtendWith(MockitoExtension.class)
class MeControllerTest {

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;

  @Mock private AuthHelperService authHelperService;

  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  @Mock private UserService userService;

  @Mock private NotificationService notificationService;

  @Mock private InventoryProperties inventoryProperties;

  @Mock private JobOrderAssigneeLookup jobOrderAssigneeLookup;

  @Mock private SupplyTabAccess supplyTabAccess;

  @Mock private PurchasingTabAccess purchasingTabAccess;

  @InjectMocks private MeController controller;

  @BeforeEach
  void noTabsByDefault() {
    lenient().when(supplyTabAccess.current()).thenReturn(SupplyTabGrants.NONE);
    lenient().when(purchasingTabAccess.current()).thenReturn(PurchasingTabGrants.NONE);
  }

  @Test
  void getCapabilities_supplyTabFlagsAreTheSupplyModulesAnswer() {
    when(supplyTabAccess.current()).thenReturn(new SupplyTabGrants(true, false));
    MeController.CapabilitiesResponse incomingOnly = controller.getCapabilities();
    assertTrue(incomingOnly.canViewSupplyIncoming());
    assertFalse(incomingOnly.canViewSupplyStats());

    when(supplyTabAccess.current()).thenReturn(new SupplyTabGrants(false, true));
    MeController.CapabilitiesResponse statsOnly = controller.getCapabilities();
    assertFalse(statsOnly.canViewSupplyIncoming());
    assertTrue(statsOnly.canViewSupplyStats());
  }

  @Test
  void getCapabilities_purchasingTabFlagsAreThePurchasingModulesAnswer() {
    when(purchasingTabAccess.current()).thenReturn(new PurchasingTabGrants(true, false, true));
    MeController.CapabilitiesResponse delegate = controller.getCapabilities();
    assertTrue(delegate.canViewPurchasingPartners());
    assertFalse(delegate.canViewPurchasingDelegates());
    assertTrue(delegate.canViewPurchasingReport());

    when(purchasingTabAccess.current()).thenReturn(new PurchasingTabGrants(false, true, false));
    MeController.CapabilitiesResponse governing = controller.getCapabilities();
    assertFalse(governing.canViewPurchasingPartners());
    assertTrue(governing.canViewPurchasingDelegates());
    assertFalse(governing.canViewPurchasingReport());
  }

  @Test
  void getCapabilities_noTabWithoutTheirRights() {
    MeController.CapabilitiesResponse member = controller.getCapabilities();

    assertFalse(member.canViewSupplyIncoming());
    assertFalse(member.canViewSupplyStats());
    assertFalse(member.canViewPurchasingPartners());
    assertFalse(member.canViewPurchasingDelegates());
    assertFalse(member.canViewPurchasingReport());
  }

  @Test
  void getLayout_carriesTheSameTabFlags() {
    when(supplyTabAccess.current()).thenReturn(new SupplyTabGrants(false, true));
    when(purchasingTabAccess.current()).thenReturn(new PurchasingTabGrants(false, false, true));

    MeController.CapabilitiesResponse layout =
        controller.getLayout(null, UUID.randomUUID()).capabilities();

    assertTrue(layout.canViewSupplyStats());
    assertTrue(layout.canViewPurchasingReport());
    assertFalse(layout.canViewPurchasingPartners());
  }

  @Test
  void getCapabilities_assignedJobOrdersFlagFollowsTheCallersEntries() {
    UUID callerId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(jobOrderAssigneeLookup.holdsAnyEntry(callerId)).thenReturn(true);
    assertTrue(controller.getCapabilities().canViewAssignedJobOrders());

    when(jobOrderAssigneeLookup.holdsAnyEntry(callerId)).thenReturn(false);
    assertFalse(controller.getCapabilities().canViewAssignedJobOrders());
  }

  @Test
  void getCapabilities_assignedJobOrdersFlagIsIndependentOfTheProfitGateAndThePin() {
    UUID callerId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(jobOrderAssigneeLookup.holdsAnyEntry(callerId)).thenReturn(true);
    when(requestScopeResolver.canViewJobOrders()).thenReturn(false);
    when(requestScopeResolver.canViewOwnJobOrders()).thenReturn(false);
    when(requestScopeResolver.canActAsOfficerInActiveContext()).thenReturn(false);

    MeController.CapabilitiesResponse response = controller.getCapabilities();

    assertTrue(response.canViewAssignedJobOrders());
    assertFalse(response.canViewJobOrders());
    assertFalse(response.canViewOwnJobOrders());
  }

  @Test
  void getCapabilities_assignedJobOrdersFlagIsFalseWithoutAnIdentifiedCaller() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertFalse(controller.getCapabilities().canViewAssignedJobOrders());
    verify(jobOrderAssigneeLookup, never()).holdsAnyEntry(any());
  }

  @Test
  void getActiveOrgUnit_present_returnsUuid() {
    UUID active = UUID.randomUUID();
    when(requestScopeResolver.currentOrgUnitId()).thenReturn(Optional.of(active));

    MeController.ActiveOrgUnitResponse response = controller.getActiveOrgUnit();

    assertEquals(active, response.orgUnitId());
    verify(requestScopeResolver).currentOrgUnitId();
  }

  @Test
  void getActiveOrgUnit_empty_returnsNull() {
    when(requestScopeResolver.currentOrgUnitId()).thenReturn(Optional.empty());

    assertNull(controller.getActiveOrgUnit().orgUnitId());
  }

  @Test
  void getCapabilities_logisticianFlagFollowsTheGrantInTheActiveContext() {
    when(accessGateService.canActAsLogisticianInActiveContext()).thenReturn(true);

    assertTrue(controller.getCapabilities().isLogisticianOrAbove());
  }

  @Test
  void getCapabilities_logisticianFlagIsFalseWithoutAGrantInTheActiveContext() {
    when(accessGateService.canActAsLogisticianInActiveContext()).thenReturn(false);

    assertFalse(controller.getCapabilities().isLogisticianOrAbove());
  }

  @Test
  void getCapabilities_missionManagerFlagFollowsTheGrantInTheActiveContext() {
    when(requestScopeResolver.canActAsMissionManagerInActiveContext()).thenReturn(true);

    assertTrue(controller.getCapabilities().isMissionManagerOrAbove());
  }

  @Test
  void getCapabilities_missionManagerFlagIsFalseWithoutAGrantInTheActiveContext() {
    when(requestScopeResolver.canActAsMissionManagerInActiveContext()).thenReturn(false);

    assertFalse(controller.getCapabilities().isMissionManagerOrAbove());
  }

  @Test
  void getCapabilities_adminFlagIsItsOwnAnswer() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertTrue(controller.getCapabilities().isAdmin());
    verify(authHelperService).isAdmin();
  }

  @Test
  void getCapabilities_canMarkStolenIsTheServerSwitch() {
    when(inventoryProperties.stolenMarkingEnabled()).thenReturn(true);
    assertTrue(controller.getCapabilities().canMarkStolen());

    when(inventoryProperties.stolenMarkingEnabled()).thenReturn(false);
    assertFalse(controller.getCapabilities().canMarkStolen());
  }

  @Test
  void getCapabilities_officerFlagsFollowTheReachInTheActiveContext() {
    when(requestScopeResolver.canActAsOfficerInActiveContext()).thenReturn(true);

    MeController.CapabilitiesResponse response = controller.getCapabilities();

    assertTrue(response.hasOfficerReach());
    assertTrue(response.canOpenLeitung());
    assertTrue(response.canSeeHangarOwnerDetails());
    assertTrue(response.canManageJobOrders());
    assertTrue(response.canManageMissionFinance());
  }

  @Test
  void getCapabilities_officerFlagsOffWithoutReachInTheActiveContext() {
    when(requestScopeResolver.canActAsOfficerInActiveContext()).thenReturn(false);

    MeController.CapabilitiesResponse response = controller.getCapabilities();

    assertFalse(response.hasOfficerReach());
    assertFalse(response.canOpenLeitung());
    assertFalse(response.canSeeHangarOwnerDetails());
    assertFalse(response.canManageJobOrders());
    assertFalse(response.canManageMissionFinance());
  }

  @Test
  void getCapabilities_officerAnywhereIgnoresThePin() {
    when(requestScopeResolver.canActAsOfficerInActiveContext()).thenReturn(false);
    when(accessGateService.canActAsOfficerAnywhere()).thenReturn(true);

    MeController.CapabilitiesResponse response = controller.getCapabilities();

    assertFalse(response.hasOfficerReach());
    assertTrue(response.canActAsOfficerAnywhere());
  }

  @Test
  void getLayout_carriesTheSameOfficerFlags() {
    when(requestScopeResolver.canActAsOfficerInActiveContext()).thenReturn(true);

    assertTrue(controller.getLayout(null, UUID.randomUUID()).capabilities().hasOfficerReach());
  }

  @Test
  void getPinnableOrgUnits_adminGetsTheWholeActiveCatalogue() {
    List<OrgUnitMembershipOptionDto> catalogue = List.of();
    when(authHelperService.isAdmin()).thenReturn(true);
    when(orgUnitMembershipQueryService.listAllPinnableOptions()).thenReturn(catalogue);

    assertSame(catalogue, controller.getPinnableOrgUnits(null));
    verify(orgUnitMembershipQueryService).listAllPinnableOptions();
    verify(orgUnitMembershipQueryService, never()).listPickerOptionsWithDescendants(any());
  }

  @Test
  void getPinnableOrgUnits_everyoneElseGetsTheirReachNotOnlyTheirBelonging() {
    UUID callerId = UUID.randomUUID();
    List<OrgUnitMembershipOptionDto> mine = List.of();
    when(authHelperService.isAdmin()).thenReturn(false);
    when(userService.getUserIdFromJwt(null)).thenReturn(callerId);
    when(orgUnitMembershipQueryService.listPickerOptionsWithDescendants(callerId)).thenReturn(mine);

    assertSame(mine, controller.getPinnableOrgUnits(null));
    verify(orgUnitMembershipQueryService, never()).listAllPinnableOptions();
    verify(orgUnitMembershipQueryService, never()).listOptionsForUser(any());
  }

  @Test
  void getCapabilities_reflectsBlueprintOverviewAccess_true() {
    when(requestScopeResolver.canAccessBlueprintOverview()).thenReturn(true);

    assertTrue(controller.getCapabilities().canSeeBlueprintOverview());
    verify(requestScopeResolver).canAccessBlueprintOverview();
  }

  @Test
  void getCapabilities_reflectsBlueprintOverviewAccess_false() {
    when(requestScopeResolver.canAccessBlueprintOverview()).thenReturn(false);

    assertFalse(controller.getCapabilities().canSeeBlueprintOverview());
  }

  @Test
  void getCapabilities_reflectsJobOrderViewAccess_true() {
    when(requestScopeResolver.canViewJobOrders()).thenReturn(true);

    assertTrue(controller.getCapabilities().canViewJobOrders());
    verify(requestScopeResolver).canViewJobOrders();
  }

  @Test
  void getCapabilities_reflectsJobOrderViewAccess_false() {
    when(requestScopeResolver.canViewJobOrders()).thenReturn(false);

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
   * Verifies that a Bankleitung without {@code BANK_EMPLOYEE} still receives the bank-employee
   * capability through the role hierarchy.
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

  @Test
  void getLayout_answersTheFourLayoutQuestionsFromTheSameResolvers() {
    UUID active = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    List<OrgUnitMembershipOptionDto> catalogue = List.of();
    when(requestScopeResolver.currentOrgUnitId()).thenReturn(Optional.of(active));
    when(authHelperService.isAdmin()).thenReturn(true);
    when(orgUnitMembershipQueryService.listAllPinnableOptions()).thenReturn(catalogue);
    when(accessGateService.canActAsLogisticianInActiveContext()).thenReturn(true);
    when(notificationService.unreadCount(callerId)).thenReturn(7L);

    MeController.LayoutResponse layout = controller.getLayout(null, callerId);

    assertEquals(active, layout.activeOrgUnitId());
    assertSame(catalogue, layout.orgUnits());
    assertTrue(layout.capabilities().isAdmin());
    assertTrue(layout.capabilities().isLogisticianOrAbove());
    assertEquals(7L, layout.unreadNotifications());
  }
}
