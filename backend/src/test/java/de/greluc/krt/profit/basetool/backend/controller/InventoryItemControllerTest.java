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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure-Mockito unit tests for {@link InventoryItemController}. The controller is otherwise a
 * delegating thin shell, but four behaviours need an explicit pin because regressing them is silent
 * at the type level:
 *
 * <ul>
 *   <li>{@code /my-inventory*} derives the owner id from the JWT via {@link
 *       UserService#getUserIdFromJwt} — never a URL parameter. This is the personal-inventory
 *       data-isolation guarantee from CLAUDE.md.
 *   <li>{@code create}, {@code book-out}, {@code update-delivered} and {@code update-note} read
 *       {@code authHelperService.isLogisticianOrAbove()} at the HTTP boundary and pass the boolean
 *       down so the service stays free of {@code SecurityContextHolder} (ArchUnit rule). The
 *       role-driven branch is exercised for both {@code true} and {@code false}.
 *   <li>{@code POST /{id}/book-out} returns {@code 200 OK} when the service yields a DTO and {@code
 *       204 No Content} when the row was removed entirely (service returns {@code null}). The
 *       branch decision lives in the controller, not the service.
 *   <li>{@code POST /bulk-checkout} forwards only the calling user's id — never an {@code
 *       isLogistician} flag — because the service deliberately refuses to remove items owned by
 *       another user, regardless of role. The test confirms the boundary helper is NEVER consulted
 *       for bulk checkout.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemControllerTest {

  @Mock private InventoryItemService inventoryItemService;
  @Mock private UserService userService;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private InventoryItemController controller;

  private static Jwt jwt(String sub) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(sub)
        .claim("sub", sub)
        .build();
  }

  private static InventoryItemDto inventoryItem(UUID id) {
    return new InventoryItemDto(
        id,
        null,
        null,
        null,
        750,
        10.0,
        false,
        null,
        null,
        null,
        null,
        java.util.List.of(),
        0.0,
        java.util.List.of(),
        0.0,
        null,
        null,
        1L,
        null);
  }

  // ── GET /aggregated ───────────────────────────────────────────────────

  @Test
  void getAggregatedInventory_wrapsPageIntoPageResponse() {
    AggregatedInventoryDto agg = new AggregatedInventoryDto(null, 750.0, 25.0);
    Page<AggregatedInventoryDto> page = new PageImpl<>(List.of(agg), PageRequest.of(0, 20), 1);
    when(inventoryItemService.getAggregatedInventory(any(Pageable.class))).thenReturn(page);

    PageResponse<AggregatedInventoryDto> result =
        controller.getAggregatedInventory(0, 20, "material.name,asc");

    assertThat(result.content()).containsExactly(agg);
    assertThat(result.totalElements()).isEqualTo(1L);
    verify(inventoryItemService).getAggregatedInventory(any(Pageable.class));
  }

  // ── GET /material/{materialId} ───────────────────────────────────────

  @Test
  void getInventoryByMaterial_forwardsMaterialIdAndPageableToService() {
    UUID materialId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryItemService.getInventoryByMaterial(eq(materialId), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getInventoryByMaterial(materialId, 0, 20, null);

    assertThat(result.content()).containsExactly(dto);
    verify(inventoryItemService).getInventoryByMaterial(eq(materialId), any(Pageable.class));
  }

  // ── GET /my-inventory (JWT-derived owner) ────────────────────────────

  @Test
  void getMyInventory_resolvesOwnerFromJwt_neverFromCallerParameters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getUserInventory(eq(ownerId), any(Pageable.class))).thenReturn(page);

    PageResponse<InventoryItemDto> result = controller.getMyInventory(jwt, 0, 20, null);

    // The owner id MUST come from UserService.getUserIdFromJwt — the test pins that the captured
    // owner argument matches the JWT-derived id, not the URL/page params. This is the same guard
    // as in HangarController.getMyShips.
    ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(inventoryItemService).getUserInventory(ownerCaptor.capture(), any(Pageable.class));
    assertThat(ownerCaptor.getValue()).isEqualTo(ownerId);
    assertThat(result.content()).containsExactly(dto);
  }

  @Test
  void getMyGroupedInventory_resolvesOwnerFromJwt_andForwardsFilters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, 25.0, 750.0, 800, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId,
            List.of(materialId),
            700,
            List.of(jobOrderId),
            List.of(missionId),
            false,
            false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt, List.of(materialId), 700, List.of(jobOrderId), List.of(missionId), false, false);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(
            ownerId,
            List.of(materialId),
            700,
            List.of(jobOrderId),
            List.of(missionId),
            false,
            false);
  }

  @Test
  void getMyGroupedInventory_personalOnly_forwardsFlagToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, 5.0, 600.0, 600, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId, null, null, null, null, true, false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(jwt, null, null, null, null, true, false);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(ownerId, null, null, null, null, true, false);
  }

  @Test
  void getMyGroupedInventory_nonPersonalOnly_forwardsFlagToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, 5.0, 600.0, 600, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId, null, null, null, null, false, true))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(jwt, null, null, null, null, false, true);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(ownerId, null, null, null, null, false, true);
  }

  // ── GET /all (admin/logistician wide read) ───────────────────────────

  @Test
  void getAllInventory_forwardsFiltersAndPageableToService() {
    UUID materialId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryItemService.getAllInventory(
            eq(List.of(materialId)), eq(700), eq(null), eq(null), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllInventory(List.of(materialId), 700, null, null, 0, 20, null);

    assertThat(result.content()).containsExactly(dto);
    verify(inventoryItemService)
        .getAllInventory(eq(List.of(materialId)), eq(700), eq(null), eq(null), any(Pageable.class));
  }

  @Test
  void getAllGroupedInventory_delegatesWithoutJwt() {
    GroupedInventoryDto group = new GroupedInventoryDto(null, 25.0, 750.0, 800, List.of());
    when(inventoryItemService.getAllAggregatedInventory(null, null, null, null))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result = controller.getAllGroupedInventory(null, null, null, null);

    // The squadron-wide read MUST NOT touch the JWT helper — its access is gated by
    // @PreAuthorize on the controller method (LOGISTICIAN or above) plus service-level checks,
    // not by a runtime ownership decision.
    assertThat(result).containsExactly(group);
    verifyNoInteractions(userService, authHelperService);
  }

  // ── GET /my-inventory/stack/entries (JWT-derived owner, clamped paging) ──

  @Test
  void getMyStackEntries_resolvesOwnerFromJwt_clampsPageSize_andWrapsPage() {
    Jwt jwt = jwt("owner-sub");
    UUID ownerId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    UUID owningOrgUnitId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 100), 1);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyStackEntries(
            eq(ownerId),
            eq(materialId),
            eq(locationId),
            eq(800),
            eq(Boolean.TRUE),
            eq(owningOrgUnitId),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getMyStackEntries(
            jwt, materialId, locationId, 800, true, owningOrgUnitId, 0, 500);

    assertThat(result.content()).containsExactly(dto);
    // The owner id is derived from the JWT, never a request parameter (personal-inventory
    // isolation).
    verify(userService).getUserIdFromJwt(jwt);
    // A request for size=500 is clamped to the STACK_ENTRIES_MAX_SIZE bound (100).
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(inventoryItemService)
        .getMyStackEntries(
            eq(ownerId),
            eq(materialId),
            eq(locationId),
            eq(800),
            eq(Boolean.TRUE),
            eq(owningOrgUnitId),
            pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    assertThat(pageable.getValue().getPageNumber()).isZero();
  }

  // ── GET /all/stack/entries (per-owner stack key, default paging) ──

  @Test
  void getAllStackEntries_forwardsUserIdParam_appliesDefaultPaging_andWrapsPage() {
    UUID materialId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryItemService.getAllStackEntries(
            eq(materialId), eq(userId), eq(locationId), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllStackEntries(materialId, userId, locationId, null, null, null, null);

    assertThat(result.content()).containsExactly(dto);
    // The squadron-wide drill-down is gated by @PreAuthorize + service scope, not a JWT-owner read.
    verifyNoInteractions(userService, authHelperService);
    // Null page/size fall back to the first page of STACK_ENTRIES_DEFAULT_SIZE (20).
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(inventoryItemService)
        .getAllStackEntries(
            eq(materialId), eq(userId), eq(locationId), isNull(), isNull(), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    assertThat(pageable.getValue().getPageNumber()).isZero();
  }

  // ── POST /inventory (create) ─────────────────────────────────────────

  @Test
  void createInventoryItem_logisticianBranch_passesTrueToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto createDto =
        new InventoryItemCreateDto(
            null, materialId, locationId, 750, 25.0, false, null, null, null, null, null, null);
    InventoryItemDto persisted = inventoryItem(UUID.randomUUID());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(inventoryItemService.createInventoryItem(createDto, ownerId, true)).thenReturn(persisted);

    InventoryItemDto result = controller.createInventoryItem(jwt, createDto);

    // LOGISTICIAN branch — the boolean MUST reach the service so the service can decide whether
    // the caller-supplied userId (impersonation) is honoured. The decision lives at the HTTP
    // boundary so the service stays free of SecurityContextHolder reads (ArchUnit rule).
    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).createInventoryItem(createDto, ownerId, true);
  }

  @Test
  void createInventoryItem_nonLogisticianBranch_passesFalseToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    InventoryItemCreateDto createDto =
        new InventoryItemCreateDto(
            UUID.randomUUID(), // caller tried to set an explicit owner ...
            UUID.randomUUID(),
            UUID.randomUUID(),
            750,
            25.0,
            false,
            null,
            null,
            null,
            null,
            null,
            null);
    InventoryItemDto persisted = inventoryItem(UUID.randomUUID());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(inventoryItemService.createInventoryItem(createDto, ownerId, false)).thenReturn(persisted);

    InventoryItemDto result = controller.createInventoryItem(jwt, createDto);

    // ... but the service receives isLogistician=false, so the impersonation attempt will be
    // collapsed inside the service. The controller does NOT decide on its own which owner wins.
    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).createInventoryItem(createDto, ownerId, false);
  }

  // ── POST /inventory/{id}/book-out (200 / 204 split) ──────────────────

  @Test
  void bookOutInventoryItem_returns200_whenServiceYieldsDto() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemBookOutDto bookOutDto =
        new InventoryItemBookOutDto(5.0, null, null, null, null, null, 1L, null, null, null);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(inventoryItemService.bookOutInventoryItem(itemId, bookOutDto, ownerId, false))
        .thenReturn(persisted);

    ResponseEntity<InventoryItemDto> response =
        controller.bookOutInventoryItem(jwt, itemId, bookOutDto);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(persisted);
  }

  @Test
  void bookOutInventoryItem_returns204_whenServiceYieldsNull() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemBookOutDto bookOutDto =
        new InventoryItemBookOutDto(25.0, null, null, null, null, null, 1L, null, null, null);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(inventoryItemService.bookOutInventoryItem(itemId, bookOutDto, ownerId, true))
        .thenReturn(null);

    ResponseEntity<InventoryItemDto> response =
        controller.bookOutInventoryItem(jwt, itemId, bookOutDto);

    // The 200/204 split lives in the CONTROLLER, not in the service: service returning null is
    // the signal "row was removed entirely (post-decrement quantity < epsilon)". A regression
    // that returns 200 with an empty body would break the frontend's "removed-from-list"
    // animation contract, which keys off the status code, not the body.
    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
  }

  // ── POST /inventory/{id}/personal-rebook (Umbuchung) ─────────────────

  @Test
  void rebookPersonal_logisticianBranch_passesTrueToService_andReturnsServiceDto() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemPersonalRebookDto rebookDto =
        new InventoryItemPersonalRebookDto(4.0, 1L, UUID.randomUUID(), null);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(inventoryItemService.rebookPersonal(itemId, rebookDto, ownerId, true))
        .thenReturn(persisted);

    InventoryItemDto result = controller.rebookPersonal(jwt, itemId, rebookDto);

    // The role flag from the HTTP boundary reaches the service as the isAdmin arg, the owner id is
    // the JWT-derived id, and the controller returns the service DTO directly (no 200/204 split).
    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).rebookPersonal(itemId, rebookDto, ownerId, true);
  }

  @Test
  void rebookPersonal_nonLogisticianBranch_passesFalseToService_withJwtDerivedOwner() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemPersonalRebookDto rebookDto =
        new InventoryItemPersonalRebookDto(2.0, 3L, null, null);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(inventoryItemService.rebookPersonal(itemId, rebookDto, ownerId, false))
        .thenReturn(persisted);

    InventoryItemDto result = controller.rebookPersonal(jwt, itemId, rebookDto);

    // Non-logistician path — the boolean flows through unchanged; the captured service arguments
    // pin that the owner id is the JWT-derived id and the isAdmin flag is false.
    assertThat(result).isSameAs(persisted);
    ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(inventoryItemService)
        .rebookPersonal(eq(itemId), eq(rebookDto), ownerCaptor.capture(), eq(false));
    assertThat(ownerCaptor.getValue()).isEqualTo(ownerId);
  }

  // ── PUT /inventory/{id}/note ─────────────────────────────────────────

  @Test
  void updateInventoryItemNote_logisticianBranch_passesTrueToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemNoteUpdateRequest request = new InventoryItemNoteUpdateRequest("new note", 1L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(inventoryItemService.updateNote(itemId, request, ownerId, true)).thenReturn(persisted);

    InventoryItemDto result = controller.updateInventoryItemNote(jwt, itemId, request);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).updateNote(itemId, request, ownerId, true);
  }

  @Test
  void updateInventoryItemNote_nonLogisticianBranch_passesFalseToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemNoteUpdateRequest request = new InventoryItemNoteUpdateRequest(null, 1L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(inventoryItemService.updateNote(itemId, request, ownerId, false)).thenReturn(persisted);

    InventoryItemDto result = controller.updateInventoryItemNote(jwt, itemId, request);

    // Non-logistician path — the service rejects with 403 if itemId belongs to another user.
    // The test pins the boolean flow, NOT the service's downstream decision.
    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).updateNote(itemId, request, ownerId, false);
  }

  // ── POST /inventory/bulk-checkout ────────────────────────────────────

  @Test
  void bulkCheckout_forwardsOwnerOnly_neverConsultsIsLogistician() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    BulkCheckoutRequest request =
        new BulkCheckoutRequest(List.of(UUID.randomUUID(), UUID.randomUUID()));
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);

    controller.bulkCheckout(jwt, request);

    // Bulk checkout is deliberately NOT role-overridable: even an admin must use the
    // single-item endpoints to delete someone else's stock. The test pins that
    // isLogisticianOrAbove() is NEVER consulted for this flow — silent regression in the
    // future (e.g. "let's just accept the same isLogistician flag everywhere") would change
    // the data-isolation contract.
    verify(inventoryItemService).bulkCheckout(request, ownerId);
    verify(authHelperService, never()).isLogisticianOrAbove();
  }

  // ── PATCH /inventory/{id}/delivered ──────────────────────────────────

  @Test
  void updateDelivered_logisticianBranch_passesTrueToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UpdateDeliveredRequest request = new UpdateDeliveredRequest(true, UUID.randomUUID(), 1L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(inventoryItemService.updateDelivered(itemId, request, ownerId, true))
        .thenReturn(persisted);

    InventoryItemDto result = controller.updateDelivered(jwt, itemId, request);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).updateDelivered(itemId, request, ownerId, true);
  }

  @Test
  void updateDelivered_nonLogisticianBranch_passesFalseToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UpdateDeliveredRequest request = new UpdateDeliveredRequest(false, UUID.randomUUID(), 2L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(inventoryItemService.updateDelivered(itemId, request, ownerId, false))
        .thenReturn(persisted);

    InventoryItemDto result = controller.updateDelivered(jwt, itemId, request);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).updateDelivered(itemId, request, ownerId, false);
  }

  // ── DELETE /inventory/all (ADMIN-only nuke) ──────────────────────────

  @Test
  void deleteAllGlobalInventory_returns204_andDelegatesToService() {
    ResponseEntity<Void> response = controller.deleteAllGlobalInventory();

    // Admin-only "globales Lager leeren" — the @PreAuthorize gate is the entire access story
    // for this endpoint. The controller test confirms (1) the delegation and (2) the 204
    // status code — the ADMIN gate itself is covered by Spring Security and not duplicated
    // here in a pure-Mockito unit test.
    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    verify(inventoryItemService).deleteAllGlobalInventory();
  }

  // ── POST/PATCH/DELETE /inventory/{id}/allocation (Variante C splits) ──

  @Test
  void addAllocation_delegatesIdAndDto_withoutJwtOrRoleHelper() {
    UUID itemId = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 4.0, 1L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(inventoryItemService.addAllocation(itemId, dto)).thenReturn(persisted);

    InventoryItemDto result = controller.addAllocation(itemId, dto);

    // The allocation endpoints are gated purely by @PreAuthorize(@ownerScopeService
    // .canEditInventoryItem) plus the service's personal-entry guard — no JWT-owner read and no
    // isLogistician boundary flag, so neither helper is consulted.
    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).addAllocation(itemId, dto);
    verifyNoInteractions(userService, authHelperService);
  }

  @Test
  void changeAllocation_delegatesIdAndDto() {
    UUID itemId = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.MISSION, UUID.randomUUID(), 6.0, 2L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(inventoryItemService.changeAllocation(itemId, dto)).thenReturn(persisted);

    InventoryItemDto result = controller.changeAllocation(itemId, dto);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).changeAllocation(itemId, dto);
    verifyNoInteractions(userService, authHelperService);
  }

  @Test
  void removeAllocation_delegatesIdAndDto() {
    UUID itemId = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), null, 3L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(inventoryItemService.removeAllocation(itemId, dto)).thenReturn(persisted);

    InventoryItemDto result = controller.removeAllocation(itemId, dto);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).removeAllocation(itemId, dto);
    verifyNoInteractions(userService, authHelperService);
  }
}
