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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.RefineryOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.RefineryOrderService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Tests the manual security shaping in {@link RefineryOrderController}.
 *
 * <p>This is the only controller in the codebase that ships substantial owner-vs-Logistician logic
 * inline (per CLAUDE.md authorization should be centralised in {@code @PreAuthorize}, but here the
 * controller decides which {@code targetUserId} is used and whether to throw {@link
 * AccessDeniedException} directly). A regression here lets a normal user update someone else's
 * refinery order or silently re-route ownership.
 *
 * <p>No dedicated controller test existed before this PR. Coverage was 30% line / 19% branch.
 */
@ExtendWith(MockitoExtension.class)
class RefineryOrderControllerTest {

  @Mock private RefineryOrderService service;
  @Mock private UserService userService;
  @Mock private RefineryOrderMapper mapper;
  @Mock private AuthHelperService authHelperService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private Jwt jwt;

  @InjectMocks private RefineryOrderController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final UUID OTHER_USER_ID = UUID.randomUUID();
  private static final UUID ORDER_ID = UUID.randomUUID();

  @BeforeEach
  void stubCallerId() {
    lenient().when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
  }

  @Nested
  class UpdateMyRefineryOrderTests {

    @Test
    void logisticianWithExplicitOwnerInBody_routesToBodyOwner() {
      RefineryOrder existing = newOrder(CALLER_ID);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

      RefineryOrderDto incoming = dtoWithOwner(OTHER_USER_ID);
      RefineryOrder mapped = new RefineryOrder();
      when(mapper.toEntity(incoming)).thenReturn(mapped);
      RefineryOrder updated = new RefineryOrder();
      when(service.updateRefineryOrder(eq(OTHER_USER_ID), eq(ORDER_ID), eq(mapped), eq(true)))
          .thenReturn(updated);
      when(mapper.toDto(eq(updated), any())).thenReturn(incoming);

      RefineryOrderDto result = controller.updateMyRefineryOrder(jwt, ORDER_ID, incoming);

      assertSame(incoming, result);
      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service)
          .updateRefineryOrder(userIdCaptor.capture(), eq(ORDER_ID), eq(mapped), eq(true));
      assertEquals(
          OTHER_USER_ID,
          userIdCaptor.getValue(),
          "Logistician + body.owner.id present -> body's owner wins");
    }

    @Test
    void logisticianWithNoOwnerInBody_fallsBackToExistingOwner() {
      RefineryOrder existing = newOrder(OTHER_USER_ID);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

      RefineryOrderDto incoming = dtoWithOwner(null);
      RefineryOrder mapped = new RefineryOrder();
      when(mapper.toEntity(incoming)).thenReturn(mapped);
      when(service.updateRefineryOrder(
              any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.updateMyRefineryOrder(jwt, ORDER_ID, incoming);

      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service)
          .updateRefineryOrder(userIdCaptor.capture(), eq(ORDER_ID), eq(mapped), eq(true));
      assertEquals(
          OTHER_USER_ID,
          userIdCaptor.getValue(),
          "Logistician + no body.owner -> existing.owner wins (NOT the caller)");
    }

    @Test
    void logisticianWithNoOwnerAnywhere_fallsBackToCaller() {
      RefineryOrder existing = newOrder(null);
      existing.setOwner(null);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

      RefineryOrderDto incoming = dtoWithOwner(null);
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.updateRefineryOrder(
              any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.updateMyRefineryOrder(jwt, ORDER_ID, incoming);

      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service).updateRefineryOrder(userIdCaptor.capture(), any(), any(), eq(true));
      assertEquals(
          CALLER_ID,
          userIdCaptor.getValue(),
          "no owner anywhere -> caller is the ultimate fallback");
    }

    @Test
    void nonLogistician_andCallerIsOwner_passesThrough() {
      RefineryOrder existing = newOrder(CALLER_ID);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

      RefineryOrderDto incoming = dtoWithOwner(null);
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.updateRefineryOrder(
              any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.updateMyRefineryOrder(jwt, ORDER_ID, incoming);

      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service).updateRefineryOrder(userIdCaptor.capture(), any(), any(), eq(false));
      assertEquals(CALLER_ID, userIdCaptor.getValue());
    }

    @Test
    void nonLogistician_andCallerIsNotOwner_throwsAccessDenied() {
      RefineryOrder existing = newOrder(OTHER_USER_ID);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

      RefineryOrderDto incoming = dtoWithOwner(null);

      assertThrows(
          AccessDeniedException.class,
          () -> controller.updateMyRefineryOrder(jwt, ORDER_ID, incoming));
      verify(service, never())
          .updateRefineryOrder(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void nonLogistician_andExistingOwnerIsNull_throwsAccessDenied() {
      RefineryOrder existing = newOrder(null);
      existing.setOwner(null);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

      RefineryOrderDto incoming = dtoWithOwner(null);

      assertThrows(
          AccessDeniedException.class,
          () -> controller.updateMyRefineryOrder(jwt, ORDER_ID, incoming));
      verify(service, never())
          .updateRefineryOrder(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
  }

  @Nested
  class CreateMyRefineryOrderTests {

    @Test
    void inScopeCallerCanCreateForAnotherUser() {
      when(ownerScopeService.canManageUserRefineryOrders(OTHER_USER_ID)).thenReturn(true);

      RefineryOrderDto incoming = dtoWithOwner(OTHER_USER_ID);
      RefineryOrder mapped = new RefineryOrder();
      when(mapper.toEntity(incoming)).thenReturn(mapped);
      when(service.createRefineryOrder(any(), any(), any())).thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.createMyRefineryOrder(jwt, incoming);

      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service).createRefineryOrder(userIdCaptor.capture(), eq(mapped), any());
      assertEquals(
          OTHER_USER_ID,
          userIdCaptor.getValue(),
          "in-scope caller + body.owner.id -> create attributed to body's owner");
    }

    @Test
    void outOfScopeCaller_withBodyOwner_isIgnored_useCallerInstead() {
      when(ownerScopeService.canManageUserRefineryOrders(OTHER_USER_ID)).thenReturn(false);

      RefineryOrderDto incoming = dtoWithOwner(OTHER_USER_ID);
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.createRefineryOrder(any(), any(), any())).thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.createMyRefineryOrder(jwt, incoming);

      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service).createRefineryOrder(userIdCaptor.capture(), any(), any());
      assertEquals(
          CALLER_ID,
          userIdCaptor.getValue(),
          "out-of-scope caller spoofing body.owner.id must be ignored -> caller wins");
    }

    @Test
    void noOwnerInBody_useCallerInstead() {
      RefineryOrderDto incoming = dtoWithOwner(null);
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.createRefineryOrder(any(), any(), any())).thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.createMyRefineryOrder(jwt, incoming);

      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service).createRefineryOrder(userIdCaptor.capture(), any(), any());
      assertEquals(CALLER_ID, userIdCaptor.getValue());
    }
  }

  @Nested
  class GetMissionRefineryOrdersTests {

    private final UUID missionId = UUID.randomUUID();

    @Test
    void logistician_routesToOrgUnitScopedQuery() {
      when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
      when(service.getMissionRefineryOrdersScoped(missionId)).thenReturn(java.util.List.of());

      controller.getMissionRefineryOrders(jwt, missionId);

      verify(service).getMissionRefineryOrdersScoped(missionId);
      verify(service, never()).getMissionRefineryOrders(any(UUID.class), any(UUID.class));
    }

    @Test
    void nonLogistician_seesOnlyOwnOrdersOnMission() {
      when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
      when(service.getMissionRefineryOrders(missionId, CALLER_ID)).thenReturn(java.util.List.of());

      controller.getMissionRefineryOrders(jwt, missionId);

      verify(service).getMissionRefineryOrders(missionId, CALLER_ID);
      verify(service, never()).getMissionRefineryOrdersScoped(any(UUID.class));
    }
  }

  @Nested
  class DelegationTests {

    @Test
    void deleteMyRefineryOrder_delegatesWithLogisticianFlag() {
      when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

      controller.deleteMyRefineryOrder(jwt, ORDER_ID);

      verify(service).deleteRefineryOrder(CALLER_ID, ORDER_ID, true);
    }

    @Test
    void deleteUserRefineryOrder_admin_alwaysPassesLogisticianTrue() {
      controller.deleteUserRefineryOrder(OTHER_USER_ID, ORDER_ID);

      verify(service).deleteRefineryOrder(OTHER_USER_ID, ORDER_ID, true);
    }

    @Test
    void createUserRefineryOrder_admin_alwaysAttributesToPathUserId() {
      RefineryOrderDto incoming = dtoWithOwner(null);
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.createRefineryOrder(any(), any(), any())).thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.createUserRefineryOrder(OTHER_USER_ID, incoming);

      verify(service).createRefineryOrder(eq(OTHER_USER_ID), any(), any());
    }

    @Test
    void updateUserRefineryOrder_admin_alwaysPassesLogisticianTrue() {
      RefineryOrderDto incoming = dtoWithOwner(null);
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.updateRefineryOrder(
              any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(incoming);

      controller.updateUserRefineryOrder(OTHER_USER_ID, ORDER_ID, incoming);

      verify(service)
          .updateRefineryOrder(
              eq(OTHER_USER_ID), eq(ORDER_ID), any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void storeMyRefineryOrder_delegatesWithLogisticianFlag() {
      when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
      de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreDto dto =
          new de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreDto(
              java.util.List.of());

      controller.storeMyRefineryOrder(jwt, ORDER_ID, dto);

      verify(service).storeRefineryOrder(CALLER_ID, ORDER_ID, dto, false);
    }

    @Test
    void getRefineryOrder_returnsMappedDto() {
      RefineryOrder order = newOrder(CALLER_ID);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(order);
      RefineryOrderDto out = dtoWithOwner(CALLER_ID);
      when(mapper.toDto(eq(order), any())).thenReturn(out);

      RefineryOrderDto result = controller.getRefineryOrder(ORDER_ID);

      assertSame(out, result);
    }

    @Test
    void getYieldsForLocation_delegatesToService_andReturnsTheMap() {
      UUID locationId = UUID.randomUUID();
      UUID matA = UUID.randomUUID();
      UUID matB = UUID.randomUUID();
      java.util.Map<UUID, Integer> expected = java.util.Map.of(matA, 5, matB, -3);
      when(service.getYieldBonusByMaterialForLocationId(locationId)).thenReturn(expected);

      java.util.Map<UUID, Integer> result = controller.getYieldsForLocation(locationId);

      assertSame(expected, result);
      verify(service).getYieldBonusByMaterialForLocationId(locationId);
    }
  }

  private static RefineryOrder newOrder(UUID ownerId) {
    RefineryOrder o = new RefineryOrder();
    o.setId(ORDER_ID);
    if (ownerId != null) {
      User owner = new User();
      owner.setId(ownerId);
      o.setOwner(owner);
    }
    return o;
  }

  private static RefineryOrderDto dtoWithOwner(UUID ownerId) {
    UserReferenceDto owner =
        ownerId == null ? null : new UserReferenceDto(ownerId, "user-" + ownerId, null, null, null);
    return new RefineryOrderDto(
        ORDER_ID,
        owner,
        null,
        null,
        Instant.now(),
        10L,
        null,
        null,
        null,
        null,
        null,
        "OPEN",
        java.util.List.of(),
        null,
        1L,
        null);
  }
}
