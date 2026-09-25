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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.BulkRebookMode;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryCatalog;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.InventoryAggregationService;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemCatalogService;
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
 * delegating thin shell, but five behaviours need an explicit pin because regressing them is silent
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
 *   <li>{@code POST /bulk-rebook} (REQ-INV-036) carries the same owner-only contract: it forwards
 *       the calling user's id and never consults {@code isLogisticianOrAbove()}, so an admin cannot
 *       bulk-move another member's stock through it.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemControllerTest {

  @Mock private InventoryItemService inventoryItemService;
  @Mock private InventoryAggregationService inventoryAggregationService;
  @Mock private InventoryItemCatalogService inventoryItemCatalogService;
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
        null,
        750,
        10.0,
        false,
        java.util.List.of(),
        0.0,
        java.util.List.of(),
        0.0,
        null,
        null,
        1L,
        null,
        null);
  }

  @Test
  void getAggregatedInventory_wrapsPageIntoPageResponse() {
    AggregatedInventoryDto agg = new AggregatedInventoryDto(null, null, 750.0, 900.0, 25.0);
    Page<AggregatedInventoryDto> page = new PageImpl<>(List.of(agg), PageRequest.of(0, 20), 1);
    when(inventoryItemService.getAggregatedInventory(any(Pageable.class))).thenReturn(page);

    PageResponse<AggregatedInventoryDto> result =
        controller.getAggregatedInventory(InventoryCatalog.MATERIAL, 0, 20, "material.name,asc");

    assertThat(result.content()).containsExactly(agg);
    assertThat(result.totalElements()).isEqualTo(1L);
    verify(inventoryItemService).getAggregatedInventory(any(Pageable.class));
  }

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

  @Test
  void getMyInventory_resolvesOwnerFromJwt_neverFromCallerParameters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getUserInventory(eq(ownerId), any(Pageable.class))).thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getMyInventory(jwt, InventoryCatalog.MATERIAL, 0, 20, null);

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
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 25.0, 750.0, 800, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId,
            List.of(materialId),
            null,
            700,
            List.of(jobOrderId),
            List.of(missionId),
            false,
            false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            List.of(materialId),
            null,
            null,
            700,
            List.of(jobOrderId),
            List.of(missionId),
            false,
            false,
            InventoryCatalog.MATERIAL);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(
            ownerId,
            List.of(materialId),
            null,
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
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 5.0, 600.0, 600, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId, null, null, null, null, null, true, false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt, null, null, null, null, null, null, true, false, InventoryCatalog.MATERIAL);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(ownerId, null, null, null, null, null, true, false);
  }

  @Test
  void getMyGroupedInventory_nonPersonalOnly_forwardsFlagToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 5.0, 600.0, 600, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId, null, null, null, null, null, false, true))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt, null, null, null, null, null, null, false, true, InventoryCatalog.MATERIAL);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(ownerId, null, null, null, null, null, false, true);
  }

  @Test
  void getAllInventory_forwardsFiltersAndPageableToService() {
    UUID materialId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryItemService.getAllInventory(
            eq(List.of(materialId)), isNull(), eq(700), eq(null), eq(null), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllInventory(
            List.of(materialId),
            null,
            null,
            700,
            null,
            null,
            InventoryCatalog.MATERIAL,
            0,
            20,
            null);

    assertThat(result.content()).containsExactly(dto);
    verify(inventoryItemService)
        .getAllInventory(
            eq(List.of(materialId)), isNull(), eq(700), eq(null), eq(null), any(Pageable.class));
  }

  @Test
  void getAllGroupedInventory_delegatesWithoutJwt() {
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 25.0, 750.0, 800, List.of());
    when(inventoryItemService.getAllAggregatedInventory(null, null, null, null, null))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getAllGroupedInventory(
            null, null, null, null, null, null, InventoryCatalog.MATERIAL);

    assertThat(result).containsExactly(group);
    verifyNoInteractions(userService, authHelperService);
  }

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
            jwt,
            materialId,
            null,
            locationId,
            800,
            true,
            owningOrgUnitId,
            InventoryCatalog.MATERIAL,
            0,
            500);

    assertThat(result.content()).containsExactly(dto);
    verify(userService).getUserIdFromJwt(jwt);
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
        controller.getAllStackEntries(
            materialId,
            null,
            userId,
            locationId,
            null,
            null,
            InventoryCatalog.MATERIAL,
            null,
            null);

    assertThat(result.content()).containsExactly(dto);
    verifyNoInteractions(userService, authHelperService);
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(inventoryItemService)
        .getAllStackEntries(
            eq(materialId), eq(userId), eq(locationId), isNull(), isNull(), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    assertThat(pageable.getValue().getPageNumber()).isZero();
  }

  @Test
  void getAggregatedInventory_catalogItem_dispatchesToAggregationService() {
    AggregatedInventoryDto agg =
        new AggregatedInventoryDto(
            null,
            new InventoryGameItemReferenceDto(UUID.randomUUID(), "Drive", null, "GENERIC"),
            null,
            null,
            3.0);
    Page<AggregatedInventoryDto> page = new PageImpl<>(List.of(agg), PageRequest.of(0, 20), 1);
    when(inventoryAggregationService.getAggregatedItemInventory(any(Pageable.class)))
        .thenReturn(page);

    PageResponse<AggregatedInventoryDto> result =
        controller.getAggregatedInventory(InventoryCatalog.ITEM, 0, 20, null);

    assertThat(result.content()).containsExactly(agg);
    verify(inventoryAggregationService).getAggregatedItemInventory(any(Pageable.class));
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyInventory_catalogItem_dispatchesToUserItemInventory() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryAggregationService.getUserItemInventory(eq(ownerId), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getMyInventory(jwt, InventoryCatalog.ITEM, 0, 20, null);

    assertThat(result.content()).containsExactly(dto);
    verify(inventoryAggregationService).getUserItemInventory(eq(ownerId), any(Pageable.class));
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyGroupedInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt, null, null, null, 700, null, null, false, false, InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(UUID.randomUUID()),
                    false,
                    false,
                    InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyGroupedInventory_catalogItem_rejectsMaterialIds() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyGroupedInventory_catalogMaterial_rejectsGameItemIds() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    null,
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    InventoryCatalog.MATERIAL))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyGroupedInventory_catalogItem_dispatchesWithItemFilters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            null,
            new InventoryGameItemReferenceDto(gameItemId, "Drive", null, "GENERIC"),
            5.0,
            null,
            null,
            List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryAggregationService.getMyAggregatedItemInventory(
            ownerId, List.of(gameItemId), null, List.of(jobOrderId), true, false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            null,
            List.of(gameItemId),
            null,
            null,
            List.of(jobOrderId),
            null,
            true,
            false,
            InventoryCatalog.ITEM);

    assertThat(result).containsExactly(group);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyEntryIds_material_resolvesOwnerFromJwt_andForwardsFilters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    UUID entryA = UUID.randomUUID();
    UUID entryB = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyEntryIds(
            ownerId,
            List.of(materialId),
            null,
            700,
            List.of(jobOrderId),
            List.of(missionId),
            false,
            false))
        .thenReturn(List.of(entryA, entryB));

    List<UUID> result =
        controller.getMyEntryIds(
            jwt,
            List.of(materialId),
            null,
            null,
            700,
            List.of(jobOrderId),
            List.of(missionId),
            false,
            false,
            InventoryCatalog.MATERIAL);

    assertThat(result).containsExactly(entryA, entryB);
    ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(inventoryItemService)
        .getMyEntryIds(
            ownerCaptor.capture(),
            eq(List.of(materialId)),
            isNull(),
            eq(700),
            eq(List.of(jobOrderId)),
            eq(List.of(missionId)),
            eq(false),
            eq(false));
    assertThat(ownerCaptor.getValue()).isEqualTo(ownerId);
    verifyNoInteractions(inventoryAggregationService);
  }

  @Test
  void getMyEntryIds_catalogItem_dispatchesWithItemFilters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID entry = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryAggregationService.getMyItemEntryIds(
            ownerId, List.of(gameItemId), null, List.of(jobOrderId), true, false))
        .thenReturn(List.of(entry));

    List<UUID> result =
        controller.getMyEntryIds(
            jwt,
            null,
            List.of(gameItemId),
            null,
            null,
            List.of(jobOrderId),
            null,
            true,
            false,
            InventoryCatalog.ITEM);

    assertThat(result).containsExactly(entry);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyEntryIds_catalogItem_rejectsMaterialOnlyFilters() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyEntryIds(
                    jwt, null, null, null, 700, null, null, false, false, InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyEntryIds(
                    jwt,
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyEntryIds_catalogMaterial_rejectsGameItemIds() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyEntryIds(
                    jwt,
                    null,
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    InventoryCatalog.MATERIAL))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
                    null, null, null, 700, null, null, InventoryCatalog.ITEM, 0, 20, null))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(UUID.randomUUID()),
                    InventoryCatalog.ITEM,
                    0,
                    20,
                    null))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllInventory_catalogItem_rejectsMaterialIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    InventoryCatalog.ITEM,
                    0,
                    20,
                    null))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllInventory_catalogMaterial_rejectsGameItemIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
                    null,
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    InventoryCatalog.MATERIAL,
                    0,
                    20,
                    null))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllInventory_catalogItem_dispatchesToAllItemInventory() {
    UUID gameItemId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryAggregationService.getAllItemInventory(
            eq(List.of(gameItemId)), isNull(), eq(null), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllInventory(
            null, List.of(gameItemId), null, null, null, null, InventoryCatalog.ITEM, 0, 20, null);

    assertThat(result.content()).containsExactly(dto);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getAllGroupedInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    null, null, null, 700, null, null, InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(UUID.randomUUID()),
                    InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllGroupedInventory_catalogItem_rejectsMaterialIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllGroupedInventory_catalogMaterial_rejectsGameItemIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    null,
                    List.of(UUID.randomUUID()),
                    null,
                    null,
                    null,
                    null,
                    InventoryCatalog.MATERIAL))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllGroupedInventory_catalogItem_dispatchesWithItemFilters() {
    UUID gameItemId = UUID.randomUUID();
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            null,
            new InventoryGameItemReferenceDto(gameItemId, "Drive", null, "GENERIC"),
            5.0,
            null,
            null,
            List.of());
    when(inventoryAggregationService.getAllAggregatedItemInventory(List.of(gameItemId), null, null))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getAllGroupedInventory(
            null, List.of(gameItemId), null, null, null, null, InventoryCatalog.ITEM);

    assertThat(result).containsExactly(group);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyStackEntries_catalogItem_requiresGameItemId_andRejectsQuality() {
    Jwt jwt = jwt("owner-sub");
    UUID locationId = UUID.randomUUID();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyStackEntries(
                    jwt, null, null, locationId, null, false, null, InventoryCatalog.ITEM, 0, 20))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyStackEntries(
                    jwt,
                    null,
                    UUID.randomUUID(),
                    locationId,
                    800,
                    false,
                    null,
                    InventoryCatalog.ITEM,
                    0,
                    20))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyStackEntries_catalogItem_dispatchesToItemStackEntries() {
    Jwt jwt = jwt("owner-sub");
    UUID ownerId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryAggregationService.getMyItemStackEntries(
            eq(ownerId),
            eq(gameItemId),
            eq(locationId),
            eq(Boolean.TRUE),
            isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getMyStackEntries(
            jwt, null, gameItemId, locationId, null, true, null, InventoryCatalog.ITEM, 0, 20);

    assertThat(result.content()).containsExactly(dto);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getAllStackEntries_catalogItem_dispatchesToItemStackEntries() {
    UUID gameItemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryAggregationService.getAllItemStackEntries(
            eq(gameItemId), eq(userId), eq(locationId), isNull(), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllStackEntries(
            null, gameItemId, userId, locationId, null, null, InventoryCatalog.ITEM, null, null);

    assertThat(result.content()).containsExactly(dto);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllStackEntries(
                    null,
                    gameItemId,
                    userId,
                    locationId,
                    800,
                    null,
                    InventoryCatalog.ITEM,
                    null,
                    null))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getInventoryByGameItem_forwardsGameItemIdAndPageableToService() {
    UUID gameItemId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryAggregationService.getInventoryByGameItem(eq(gameItemId), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getInventoryByGameItem(gameItemId, 0, 20, null);

    assertThat(result.content()).containsExactly(dto);
    verify(inventoryAggregationService).getInventoryByGameItem(eq(gameItemId), any(Pageable.class));
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getItemCatalog_wrapsCatalogServicePageIntoPageResponse() {
    InventoryGameItemReferenceDto ref =
        new InventoryGameItemReferenceDto(UUID.randomUUID(), "Quantum Drive", "RSI", "GENERIC");
    Page<InventoryGameItemReferenceDto> page =
        new PageImpl<>(List.of(ref), PageRequest.of(0, 20), 1);
    when(inventoryItemCatalogService.findBookableItems(eq("drive"), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryGameItemReferenceDto> result =
        controller.getItemCatalog("drive", 0, 20, "name,asc");

    assertThat(result.content()).containsExactly(ref);
    assertThat(result.totalElements()).isEqualTo(1L);
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(inventoryItemCatalogService).findBookableItems(eq("drive"), pageable.capture());
    org.springframework.data.domain.Sort sort = pageable.getValue().getSort();
    assertThat(sort.getOrderFor("name")).isNotNull();
    assertThat(sort.getOrderFor("id")).isNotNull();
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void createInventoryItem_delegatesWithoutAnyRoleBoolean() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    InventoryItemCreateDto createDto =
        new InventoryItemCreateDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
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
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(inventoryItemService.createInventoryItem(createDto, callerId)).thenReturn(persisted);

    InventoryItemDto result = controller.createInventoryItem(jwt, createDto);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).createInventoryItem(createDto, callerId);
    verify(authHelperService, never()).isLogisticianOrAbove();
  }

  @Test
  void bookOutInventoryItem_returns200_whenServiceYieldsDto() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemBookOutDto bookOutDto =
        new InventoryItemBookOutDto(5.0, null, null, null, null, null, 1L, null, null, null, null);
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
        new InventoryItemBookOutDto(25.0, null, null, null, null, null, 1L, null, null, null, null);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(inventoryItemService.bookOutInventoryItem(itemId, bookOutDto, ownerId, true))
        .thenReturn(null);

    ResponseEntity<InventoryItemDto> response =
        controller.bookOutInventoryItem(jwt, itemId, bookOutDto);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
  }

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

    assertThat(result).isSameAs(persisted);
    ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(inventoryItemService)
        .rebookPersonal(eq(itemId), eq(rebookDto), ownerCaptor.capture(), eq(false));
    assertThat(ownerCaptor.getValue()).isEqualTo(ownerId);
  }

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

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).updateNote(itemId, request, ownerId, false);
  }

  @Test
  void bulkCheckout_forwardsOwnerOnly_neverConsultsIsLogistician() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    BulkCheckoutRequest request =
        new BulkCheckoutRequest(List.of(UUID.randomUUID(), UUID.randomUUID()));
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);

    controller.bulkCheckout(jwt, request);

    verify(inventoryItemService).bulkCheckout(request, ownerId);
    verify(authHelperService, never()).isLogisticianOrAbove();
  }

  @Test
  void bulkRebook_forwardsOwnerOnly_neverConsultsIsLogistician() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    BulkRebookRequest request =
        new BulkRebookRequest(
            List.of(UUID.randomUUID(), UUID.randomUUID()),
            BulkRebookMode.LOCATION,
            null,
            UUID.randomUUID(),
            null,
            Boolean.TRUE);
    BulkRebookResultDto expected = new BulkRebookResultDto(2, 0);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.bulkRebook(request, ownerId)).thenReturn(expected);

    BulkRebookResultDto result = controller.bulkRebook(jwt, request);

    assertThat(result).isSameAs(expected);
    verify(inventoryItemService).bulkRebook(request, ownerId);
    verify(authHelperService, never()).isLogisticianOrAbove();
  }

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

  @Test
  void deleteAllGlobalInventory_returns204_andDelegatesToService() {
    ResponseEntity<Void> response = controller.deleteAllGlobalInventory();

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    verify(inventoryItemService).deleteAllGlobalInventory();
  }

  @Test
  void addAllocation_delegatesIdAndDto_withoutJwtOrRoleHelper() {
    UUID itemId = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 4.0, 1L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(inventoryItemService.addAllocation(itemId, dto)).thenReturn(persisted);

    InventoryItemDto result = controller.addAllocation(itemId, dto);

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

  @Test
  void getMyGroupedInventory_catalogMaterial_forwardsLocationIds() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 12.0, 700.0, 700, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId, null, List.of(locationId), null, null, null, false, false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            null,
            null,
            List.of(locationId),
            null,
            null,
            null,
            false,
            false,
            InventoryCatalog.MATERIAL);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(
            eq(ownerId),
            isNull(),
            eq(List.of(locationId)),
            isNull(),
            isNull(),
            isNull(),
            eq(false),
            eq(false));
  }

  @Test
  void getMyGroupedInventory_catalogItem_forwardsLocationIdsInsteadOfRejectingThem() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 4.0, null, null, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryAggregationService.getMyAggregatedItemInventory(
            ownerId, null, List.of(locationId), null, false, false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            null,
            null,
            List.of(locationId),
            null,
            null,
            null,
            false,
            false,
            InventoryCatalog.ITEM);

    assertThat(result).containsExactly(group);
    verify(inventoryAggregationService)
        .getMyAggregatedItemInventory(
            eq(ownerId), isNull(), eq(List.of(locationId)), isNull(), eq(false), eq(false));
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyEntryIds_forwardsLocationIdsOnBothCatalogs() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID entry = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyEntryIds(
            ownerId, null, List.of(locationId), null, null, null, false, false))
        .thenReturn(List.of(entry));
    when(inventoryAggregationService.getMyItemEntryIds(
            ownerId, null, List.of(locationId), null, false, false))
        .thenReturn(List.of(entry));

    assertThat(
            controller.getMyEntryIds(
                jwt,
                null,
                null,
                List.of(locationId),
                null,
                null,
                null,
                false,
                false,
                InventoryCatalog.MATERIAL))
        .containsExactly(entry);
    assertThat(
            controller.getMyEntryIds(
                jwt,
                null,
                null,
                List.of(locationId),
                null,
                null,
                null,
                false,
                false,
                InventoryCatalog.ITEM))
        .containsExactly(entry);
  }

  @Test
  void getAllGroupedInventory_forwardsLocationIdsOnBothCatalogs() {
    UUID locationId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 9.0, 500.0, 500, List.of());
    when(inventoryItemService.getAllAggregatedInventory(
            null, List.of(locationId), null, null, null))
        .thenReturn(List.of(group));
    when(inventoryAggregationService.getAllAggregatedItemInventory(null, List.of(locationId), null))
        .thenReturn(List.of(group));

    assertThat(
            controller.getAllGroupedInventory(
                null, null, List.of(locationId), null, null, null, InventoryCatalog.MATERIAL))
        .containsExactly(group);
    assertThat(
            controller.getAllGroupedInventory(
                null, null, List.of(locationId), null, null, null, InventoryCatalog.ITEM))
        .containsExactly(group);

    verifyNoInteractions(userService);
  }
}
