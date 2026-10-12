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

package de.greluc.krt.profit.basetool.backend.refinery.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderCreateRequest;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderMapper;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderService;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Tests the inline owner-vs-Logistician shaping in {@link RefineryOrderController}: which {@code
 * targetUserId} is used, when {@link AccessDeniedException} is thrown, and that the request records
 * bind no server-managed field.
 */
@ExtendWith(MockitoExtension.class)
class RefineryOrderControllerTest {

  @Mock private RefineryOrderService service;
  @Mock private UserService userService;
  @Mock private RefineryOrderMapper mapper;
  @Mock private AuthHelperService authHelperService;
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
    void logistician_routesToTheExistingOwner() {
      RefineryOrder existing = newOrder(OTHER_USER_ID);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

      RefineryOrderUpdateRequest incoming = updateRequest();
      RefineryOrder mapped = new RefineryOrder();
      when(mapper.toEntity(incoming)).thenReturn(mapped);
      RefineryOrder updated = new RefineryOrder();
      when(service.updateRefineryOrder(eq(OTHER_USER_ID), eq(ORDER_ID), eq(mapped), eq(true)))
          .thenReturn(updated);
      RefineryOrderDto out = dto();
      when(mapper.toDto(eq(updated), any())).thenReturn(out);

      RefineryOrderDto result = controller.updateMyRefineryOrder(jwt, ORDER_ID, incoming);

      assertSame(out, result);
      ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
      verify(service)
          .updateRefineryOrder(userIdCaptor.capture(), eq(ORDER_ID), eq(mapped), eq(true));
      assertEquals(
          OTHER_USER_ID,
          userIdCaptor.getValue(),
          "Logistician -> existing.owner wins (NOT the caller); the request names no owner");
    }

    @Test
    void logisticianWithNoOwnerAnywhere_fallsBackToCaller() {
      RefineryOrder existing = newOrder(null);
      existing.setOwner(null);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(existing);
      when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

      RefineryOrderUpdateRequest incoming = updateRequest();
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.updateRefineryOrder(
              any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(dto());

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

      RefineryOrderUpdateRequest incoming = updateRequest();
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.updateRefineryOrder(
              any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(dto());

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

      RefineryOrderUpdateRequest incoming = updateRequest();

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

      RefineryOrderUpdateRequest incoming = updateRequest();

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
    void theOrderIsAlwaysAttributedToTheCaller() {
      RefineryOrderCreateRequest incoming = createRequest(UUID.randomUUID());
      RefineryOrder mapped = new RefineryOrder();
      when(mapper.toEntity(incoming)).thenReturn(mapped);
      when(service.createRefineryOrder(any(), any(), any())).thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(dto());

      controller.createMyRefineryOrder(jwt, incoming);

      verify(service).createRefineryOrder(CALLER_ID, mapped, incoming.owningOrgUnitId());
    }

    @Test
    void theRequestCarriesNoOwnerToHonour() {
      assertEquals(
          java.util.List.of(
              "location",
              "mission",
              "startedAt",
              "durationMinutes",
              "expenses",
              "otherExpenses",
              "oreSales",
              "refiningMethod",
              "goods",
              "owningOrgUnitId"),
          java.util.Arrays.stream(RefineryOrderCreateRequest.class.getRecordComponents())
              .map(java.lang.reflect.RecordComponent::getName)
              .toList(),
          "no id, owner, profit, owningSquadron, status or version may be bound on create");
    }
  }

  @Nested
  class UpdateRequestShapeTests {

    @Test
    void theRequestCarriesNoOwnerIdProfitOrSquadron() {
      assertEquals(
          java.util.List.of(
              "location",
              "mission",
              "startedAt",
              "durationMinutes",
              "expenses",
              "otherExpenses",
              "oreSales",
              "refiningMethod",
              "status",
              "goods",
              "version"),
          java.util.Arrays.stream(RefineryOrderUpdateRequest.class.getRecordComponents())
              .map(java.lang.reflect.RecordComponent::getName)
              .toList(),
          "no id, owner, profit or owningSquadron may be bound on update");
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
      RefineryOrderCreateRequest incoming = createRequest(null);
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.createRefineryOrder(any(), any(), any())).thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(dto());

      controller.createUserRefineryOrder(OTHER_USER_ID, incoming);

      verify(service).createRefineryOrder(eq(OTHER_USER_ID), any(), any());
    }

    @Test
    void updateUserRefineryOrder_admin_alwaysPassesLogisticianTrue() {
      RefineryOrderUpdateRequest incoming = updateRequest();
      when(mapper.toEntity(incoming)).thenReturn(new RefineryOrder());
      when(service.updateRefineryOrder(
              any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
          .thenReturn(new RefineryOrder());
      when(mapper.toDto(any(), any())).thenReturn(dto());

      controller.updateUserRefineryOrder(OTHER_USER_ID, ORDER_ID, incoming);

      verify(service)
          .updateRefineryOrder(
              eq(OTHER_USER_ID), eq(ORDER_ID), any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void storeMyRefineryOrder_delegatesWithLogisticianFlag() {
      when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
      de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderStoreDto dto =
          new de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderStoreDto(
              java.util.List.of());

      controller.storeMyRefineryOrder(jwt, ORDER_ID, dto);

      verify(service).storeRefineryOrder(CALLER_ID, ORDER_ID, dto, false);
    }

    @Test
    void getRefineryOrder_returnsMappedDto() {
      RefineryOrder order = newOrder(CALLER_ID);
      when(service.getRefineryOrder(ORDER_ID)).thenReturn(order);
      RefineryOrderDto out = dto();
      when(mapper.toDto(eq(order), any())).thenReturn(out);

      RefineryOrderDto result = controller.getRefineryOrder(ORDER_ID);

      assertSame(out, result);
    }
  }

  @Nested
  class ListFilterTests {

    @Test
    void getAllRefineryOrders_passesStatusesReadyQueryAndEndsAtSortToTheService() {
      List<RefineryOrderStatus> statuses =
          List.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS);
      when(service.getAllRefineryOrders(eq(statuses), eq(true), eq("laranite"), any()))
          .thenReturn(new PageImpl<>(List.of()));

      PageResponse<RefineryOrderListDto> result =
          controller.getAllRefineryOrders(statuses, true, "laranite", 2, 10, "endsAt,asc");

      ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(service)
          .getAllRefineryOrders(eq(statuses), eq(true), eq("laranite"), pageable.capture());
      assertEquals(2, pageable.getValue().getPageNumber());
      assertEquals(10, pageable.getValue().getPageSize());
      assertEquals(
          Sort.by(Sort.Order.asc("endsAt"), Sort.Order.asc("id")), pageable.getValue().getSort());
      assertEquals(0, result.totalElements());
    }

    @Test
    void getAllRefineryOrders_absentReady_isFalseAndTheDefaultSortStaysStartedAt() {
      when(service.getAllRefineryOrders(any(), eq(false), any(), any()))
          .thenReturn(new PageImpl<>(List.of()));

      controller.getAllRefineryOrders(null, null, null, null, null, null);

      ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(service).getAllRefineryOrders(isNull(), eq(false), isNull(), pageable.capture());
      assertEquals(
          Sort.by(Sort.Order.asc("startedAt"), Sort.Order.asc("id")),
          pageable.getValue().getSort());
    }

    @Test
    void getMyRefineryOrders_passesTheCallerReadyAndQueryToTheService() {
      when(service.getMyRefineryOrders(eq(CALLER_ID), any(), eq(true), eq("ARC"), any()))
          .thenReturn(new PageImpl<>(List.of()));

      controller.getMyRefineryOrders(jwt, null, true, "ARC", 0, 50, "endsAt,asc");

      verify(service).getMyRefineryOrders(eq(CALLER_ID), isNull(), eq(true), eq("ARC"), any());
    }

    @Test
    void getMyRefineryOrders_unknownSortKey_isRejected() {
      assertThrows(
          IllegalArgumentException.class,
          () -> controller.getMyRefineryOrders(jwt, null, null, null, 0, 50, "owner,asc"));
      verify(service, never()).getMyRefineryOrders(any(), any(), anyBoolean(), any(), any());
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

  private static RefineryOrderDto dto() {
    return new RefineryOrderDto(
        ORDER_ID,
        null,
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

  private static RefineryOrderCreateRequest createRequest(UUID owningOrgUnitId) {
    return new RefineryOrderCreateRequest(
        null,
        null,
        Instant.now(),
        10L,
        null,
        null,
        null,
        null,
        java.util.List.of(),
        owningOrgUnitId);
  }

  private static RefineryOrderUpdateRequest updateRequest() {
    return new RefineryOrderUpdateRequest(
        null, null, Instant.now(), 10L, null, null, null, null, "OPEN", java.util.List.of(), 1L);
  }
}
