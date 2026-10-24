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

package de.greluc.krt.profit.basetool.backend.orgunit.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link MemberMembershipController}: the {@code /me} endpoints take the caller id
 * from the JWT, the picker options honour {@code allKinds}, and the admin delta write returns the
 * complete post-write membership list.
 */
@ExtendWith(MockitoExtension.class)
class MemberMembershipControllerTest {

  @Mock private UserService userService;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @Mock private Jwt jwt;
  @InjectMocks private MemberMembershipController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();

  @Test
  void getUserMemberships_delegatesToService() {
    UUID userId = UUID.randomUUID();
    OrgUnitMembershipOptionDto option =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "ALPHA", "ALP", OrgUnitKind.SQUADRON, false);
    when(orgUnitMembershipQueryService.listOptionsForUser(userId)).thenReturn(List.of(option));

    List<OrgUnitMembershipOptionDto> result = controller.getUserMemberships(userId, false);

    assertEquals(1, result.size());
    assertSame(option, result.getFirst());
    verify(orgUnitMembershipQueryService).listOptionsForUser(userId);
  }

  @Test
  void getUserMemberships_allKinds_delegatesToDirectMembershipOptions() {
    UUID userId = UUID.randomUUID();
    OrgUnitMembershipOptionDto bereich =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Bereich Logistik", "LOG", OrgUnitKind.BEREICH, false);
    when(orgUnitMembershipQueryService.listDirectMembershipOptions(userId))
        .thenReturn(List.of(bereich));

    List<OrgUnitMembershipOptionDto> result = controller.getUserMemberships(userId, true);

    assertSame(bereich, result.getFirst());
    verify(orgUnitMembershipQueryService).listDirectMembershipOptions(userId);
  }

  @Test
  void getUserMemberships_emptyResult_returnsEmptyList() {
    UUID userId = UUID.randomUUID();
    when(orgUnitMembershipQueryService.listOptionsForUser(userId)).thenReturn(List.of());

    List<OrgUnitMembershipOptionDto> result = controller.getUserMemberships(userId, false);

    assertTrue(result.isEmpty());
  }

  @Test
  void getMyOrgUnitIds_derivesCallerFromJwt_andDelegatesToService() {
    java.util.Set<UUID> ids = java.util.Set.of(UUID.randomUUID(), UUID.randomUUID());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(orgUnitMembershipQueryService.findDirectMembershipOrgUnitIds(CALLER_ID)).thenReturn(ids);

    java.util.Set<UUID> result = controller.getMyOrgUnitIds(jwt);

    assertSame(ids, result);
    verify(userService).getUserIdFromJwt(jwt);
    verify(orgUnitMembershipQueryService).findDirectMembershipOrgUnitIds(CALLER_ID);
  }

  @Test
  void getMyPickableOrgUnits_resolvesTheCallerFromTheJwt() {
    OrgUnitMembershipOptionDto option =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "ALPHA", "ALP", OrgUnitKind.SQUADRON, false);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(orgUnitMembershipQueryService.listPickerOptionsWithDescendants(CALLER_ID))
        .thenReturn(List.of(option));

    assertSame(option, controller.getMyPickableOrgUnits(jwt).getFirst());
  }

  @Test
  void getMyMemberships_withoutAllKinds_listsStaffelAndSpecialCommandOptions() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(orgUnitMembershipQueryService.listOptionsForUser(CALLER_ID)).thenReturn(List.of());

    assertTrue(controller.getMyMemberships(jwt, false).isEmpty());
    verify(orgUnitMembershipQueryService).listOptionsForUser(CALLER_ID);
    verify(orgUnitMembershipQueryService, never()).listDirectMembershipOptions(any());
  }

  @Test
  void getMyMemberships_withAllKinds_listsEveryDirectMembership() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(orgUnitMembershipQueryService.listDirectMembershipOptions(CALLER_ID))
        .thenReturn(List.of());

    assertTrue(controller.getMyMemberships(jwt, true).isEmpty());
    verify(orgUnitMembershipQueryService).listDirectMembershipOptions(CALLER_ID);
  }

  @Test
  void patchMemberships_appliesTheDeltaThenReturnsTheCompleteMembershipList() {
    UUID id = UUID.randomUUID();
    MembershipDeltaRequest request = new MembershipDeltaRequest(null, null);
    when(orgUnitMembershipQueryService.findAllMembershipDtosForUser(id)).thenReturn(List.of());

    MembershipDeltaResponse response = controller.patchMemberships(id, request);

    assertNotNull(response);
    InOrder order = inOrder(userService, orgUnitMembershipQueryService);
    order.verify(userService).applyMembershipDelta(id, request);
    order.verify(orgUnitMembershipQueryService).findAllMembershipDtosForUser(id);
  }

  @Test
  void getMembershipsDetail_returnsTheCompleteMembershipList() {
    UUID id = UUID.randomUUID();
    when(orgUnitMembershipQueryService.findAllMembershipDtosForUser(id)).thenReturn(List.of());

    assertNotNull(controller.getMembershipsDetail(id));
    verify(orgUnitMembershipQueryService).findAllMembershipDtosForUser(id);
    verifyNoInteractions(userService);
  }
}
