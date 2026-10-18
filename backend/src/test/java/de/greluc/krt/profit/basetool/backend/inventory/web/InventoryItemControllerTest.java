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

package de.greluc.krt.profit.basetool.backend.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkRebookMode;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkStolenMarkResultDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAggregationService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryCatalog;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryMergeCandidatesDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryOrgUnitChangeService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStolenMarkService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.ItemAttributeFilter;
import de.greluc.krt.profit.basetool.backend.inventory.internal.LagerFilter;
import de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
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
 * Pure-Mockito unit tests for {@link InventoryItemController}.
 *
 * <ul>
 *   <li>{@code /my-inventory*} takes the owner id from the JWT via {@link
 *       UserService#getUserIdFromJwt}, never from the URL.
 *   <li>{@code create}, {@code book-out}, {@code update-delivered}, {@code update-note}, {@code
 *       stolen} and the three {@code allocation} writes pass the boundary's {@code
 *       isLogisticianOrAbove()} result to the service.
 *   <li>{@code POST /{id}/book-out} answers {@code 200} with a DTO and {@code 204} when the row was
 *       removed.
 *   <li>{@code POST /bulk-checkout} and {@code POST /bulk-rebook} (REQ-INV-036) forward only the
 *       caller's id and never consult the role check.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemControllerTest {

  @Mock private InventoryItemService inventoryItemService;
  @Mock private InventoryAggregationService inventoryAggregationService;
  @Mock private UserService userService;
  @Mock private AuthHelperService authHelperService;
  @Mock private InventoryOrgUnitChangeService inventoryOrgUnitChangeService;
  @Mock private InventoryStolenMarkService inventoryStolenMarkService;

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
            LagerFilter.builder()
                .materialIds(List.of(materialId))
                .minQuality(700)
                .jobOrderIds(List.of(jobOrderId))
                .missionIds(List.of(missionId))
                .catalog(InventoryCatalog.MATERIAL)
                .build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            LagerFilter.builder()
                .materialIds(List.of(materialId))
                .minQuality(700)
                .jobOrderIds(List.of(jobOrderId))
                .missionIds(List.of(missionId))
                .catalog(InventoryCatalog.MATERIAL)
                .build());

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(
            ownerId,
            LagerFilter.builder()
                .materialIds(List.of(materialId))
                .minQuality(700)
                .jobOrderIds(List.of(jobOrderId))
                .missionIds(List.of(missionId))
                .catalog(InventoryCatalog.MATERIAL)
                .build());
  }

  @Test
  void getMyGroupedInventory_personalOnly_forwardsFlagToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 5.0, 600.0, 600, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId,
            LagerFilter.builder().personalOnly(true).catalog(InventoryCatalog.MATERIAL).build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            LagerFilter.builder().personalOnly(true).catalog(InventoryCatalog.MATERIAL).build());

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(
            ownerId,
            LagerFilter.builder().personalOnly(true).catalog(InventoryCatalog.MATERIAL).build());
  }

  @Test
  void getMyGroupedInventory_nonPersonalOnly_forwardsFlagToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 5.0, 600.0, 600, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId,
            LagerFilter.builder().nonPersonalOnly(true).catalog(InventoryCatalog.MATERIAL).build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            LagerFilter.builder().nonPersonalOnly(true).catalog(InventoryCatalog.MATERIAL).build());

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(
            ownerId,
            LagerFilter.builder().nonPersonalOnly(true).catalog(InventoryCatalog.MATERIAL).build());
  }

  @Test
  void getAllInventory_forwardsFiltersAndPageableToService() {
    UUID materialId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryItemService.getAllInventory(
            eq(
                LagerFilter.builder()
                    .materialIds(List.of(materialId))
                    .minQuality(700)
                    .catalog(InventoryCatalog.MATERIAL)
                    .build()),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllInventory(
            LagerFilter.builder()
                .materialIds(List.of(materialId))
                .minQuality(700)
                .catalog(InventoryCatalog.MATERIAL)
                .build(),
            0,
            20,
            null);

    assertThat(result.content()).containsExactly(dto);
    verify(inventoryItemService)
        .getAllInventory(
            eq(
                LagerFilter.builder()
                    .materialIds(List.of(materialId))
                    .minQuality(700)
                    .catalog(InventoryCatalog.MATERIAL)
                    .build()),
            any(Pageable.class));
  }

  @Test
  void getAllGroupedInventory_delegatesWithoutJwt() {
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 25.0, 750.0, 800, List.of());
    when(inventoryItemService.getAllAggregatedInventory(
            LagerFilter.builder().catalog(InventoryCatalog.MATERIAL).build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getAllGroupedInventory(
            LagerFilter.builder().catalog(InventoryCatalog.MATERIAL).build());

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
            eq(false),
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
            false,
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
            eq(false),
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
            eq(materialId),
            eq(userId),
            eq(locationId),
            isNull(),
            eq(false),
            isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllStackEntries(
            materialId,
            null,
            userId,
            locationId,
            null,
            false,
            null,
            InventoryCatalog.MATERIAL,
            null,
            null);

    assertThat(result.content()).containsExactly(dto);
    verifyNoInteractions(userService, authHelperService);
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(inventoryItemService)
        .getAllStackEntries(
            eq(materialId),
            eq(userId),
            eq(locationId),
            isNull(),
            eq(false),
            isNull(),
            pageable.capture());
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
                    jwt,
                    LagerFilter.builder().minQuality(700).catalog(InventoryCatalog.ITEM).build()))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    LagerFilter.builder()
                        .missionIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.ITEM)
                        .build()))
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
                    LagerFilter.builder()
                        .materialIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.ITEM)
                        .build()))
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
                    LagerFilter.builder()
                        .gameItemIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build()))
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
            ownerId,
            LagerFilter.builder()
                .gameItemIds(List.of(gameItemId))
                .jobOrderIds(List.of(jobOrderId))
                .personalOnly(true)
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            LagerFilter.builder()
                .gameItemIds(List.of(gameItemId))
                .jobOrderIds(List.of(jobOrderId))
                .personalOnly(true)
                .catalog(InventoryCatalog.ITEM)
                .build());

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
            LagerFilter.builder()
                .materialIds(List.of(materialId))
                .minQuality(700)
                .jobOrderIds(List.of(jobOrderId))
                .missionIds(List.of(missionId))
                .catalog(InventoryCatalog.MATERIAL)
                .build()))
        .thenReturn(List.of(entryA, entryB));

    List<UUID> result =
        controller.getMyEntryIds(
            jwt,
            LagerFilter.builder()
                .materialIds(List.of(materialId))
                .minQuality(700)
                .jobOrderIds(List.of(jobOrderId))
                .missionIds(List.of(missionId))
                .catalog(InventoryCatalog.MATERIAL)
                .build());

    assertThat(result).containsExactly(entryA, entryB);
    ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(inventoryItemService)
        .getMyEntryIds(
            ownerCaptor.capture(),
            eq(
                LagerFilter.builder()
                    .materialIds(List.of(materialId))
                    .minQuality(700)
                    .jobOrderIds(List.of(jobOrderId))
                    .missionIds(List.of(missionId))
                    .catalog(InventoryCatalog.MATERIAL)
                    .build()));
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
            ownerId,
            LagerFilter.builder()
                .gameItemIds(List.of(gameItemId))
                .jobOrderIds(List.of(jobOrderId))
                .personalOnly(true)
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of(entry));

    List<UUID> result =
        controller.getMyEntryIds(
            jwt,
            LagerFilter.builder()
                .gameItemIds(List.of(gameItemId))
                .jobOrderIds(List.of(jobOrderId))
                .personalOnly(true)
                .catalog(InventoryCatalog.ITEM)
                .build());

    assertThat(result).containsExactly(entry);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyEntryIds_catalogItem_rejectsMaterialOnlyFilters() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyEntryIds(
                    jwt,
                    LagerFilter.builder().minQuality(700).catalog(InventoryCatalog.ITEM).build()))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyEntryIds(
                    jwt,
                    LagerFilter.builder()
                        .materialIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.ITEM)
                        .build()))
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
                    LagerFilter.builder()
                        .gameItemIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
                    LagerFilter.builder().minQuality(700).catalog(InventoryCatalog.ITEM).build(),
                    0,
                    20,
                    null))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
                    LagerFilter.builder()
                        .missionIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.ITEM)
                        .build(),
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
                    LagerFilter.builder()
                        .materialIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.ITEM)
                        .build(),
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
                    LagerFilter.builder()
                        .gameItemIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build(),
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
            eq(
                LagerFilter.builder()
                    .gameItemIds(List.of(gameItemId))
                    .catalog(InventoryCatalog.ITEM)
                    .build()),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllInventory(
            LagerFilter.builder()
                .gameItemIds(List.of(gameItemId))
                .catalog(InventoryCatalog.ITEM)
                .build(),
            0,
            20,
            null);

    assertThat(result.content()).containsExactly(dto);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getAllGroupedInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    LagerFilter.builder().minQuality(700).catalog(InventoryCatalog.ITEM).build()))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    LagerFilter.builder()
                        .missionIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.ITEM)
                        .build()))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllGroupedInventory_catalogItem_rejectsMaterialIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    LagerFilter.builder()
                        .materialIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.ITEM)
                        .build()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllGroupedInventory_catalogMaterial_rejectsGameItemIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    LagerFilter.builder()
                        .gameItemIds(List.of(UUID.randomUUID()))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build()))
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
    when(inventoryAggregationService.getAllAggregatedItemInventory(
            LagerFilter.builder()
                .gameItemIds(List.of(gameItemId))
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getAllGroupedInventory(
            LagerFilter.builder()
                .gameItemIds(List.of(gameItemId))
                .catalog(InventoryCatalog.ITEM)
                .build());

    assertThat(result).containsExactly(group);
    verifyNoInteractions(inventoryItemService);
  }

  @Test
  void getMyGroupedInventory_catalogItem_forwardsTheAttributeFilters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    ItemAttributeFilter expected =
        new ItemAttributeFilter(
            List.of(GameItemKind.VEHICLE_ITEM),
            List.of(ShipComponentType.QUANTUM_DRIVE),
            List.of(2),
            List.of("A", "B"),
            List.of(ShipComponentClass.MILITARY));
    when(inventoryAggregationService.getMyAggregatedItemInventory(
            ownerId,
            LagerFilter.builder()
                .kinds(expected.kinds())
                .componentTypes(expected.componentTypes())
                .sizes(expected.sizes())
                .grades(expected.grades())
                .itemClasses(expected.itemClasses())
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of());

    controller.getMyGroupedInventory(
        jwt,
        LagerFilter.builder()
            .kinds(List.of(GameItemKind.VEHICLE_ITEM))
            .componentTypes(List.of(ShipComponentType.QUANTUM_DRIVE))
            .sizes(List.of(2))
            .grades(List.of("A", "B"))
            .itemClasses(List.of(ShipComponentClass.MILITARY))
            .catalog(InventoryCatalog.ITEM)
            .build());

    verify(inventoryAggregationService)
        .getMyAggregatedItemInventory(
            ownerId,
            LagerFilter.builder()
                .kinds(expected.kinds())
                .componentTypes(expected.componentTypes())
                .sizes(expected.sizes())
                .grades(expected.grades())
                .itemClasses(expected.itemClasses())
                .catalog(InventoryCatalog.ITEM)
                .build());
  }

  @Test
  void getMyEntryIds_catalogItem_forwardsTheAttributeFilters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID entry = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    ItemAttributeFilter expected =
        new ItemAttributeFilter(null, List.of(ShipComponentType.SHIELD), null, List.of("C"), null);
    when(inventoryAggregationService.getMyItemEntryIds(
            ownerId,
            LagerFilter.builder()
                .kinds(expected.kinds())
                .componentTypes(expected.componentTypes())
                .sizes(expected.sizes())
                .grades(expected.grades())
                .itemClasses(expected.itemClasses())
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of(entry));

    List<UUID> result =
        controller.getMyEntryIds(
            jwt,
            LagerFilter.builder()
                .componentTypes(List.of(ShipComponentType.SHIELD))
                .grades(List.of("C"))
                .catalog(InventoryCatalog.ITEM)
                .build());

    assertThat(result).containsExactly(entry);
  }

  @Test
  void getAllGroupedInventory_catalogItem_forwardsTheAttributeFilters() {
    ItemAttributeFilter expected =
        new ItemAttributeFilter(
            List.of(GameItemKind.WEAPON),
            null,
            List.of(1),
            null,
            List.of(ShipComponentClass.STEALTH));
    when(inventoryAggregationService.getAllAggregatedItemInventory(
            LagerFilter.builder()
                .kinds(expected.kinds())
                .componentTypes(expected.componentTypes())
                .sizes(expected.sizes())
                .grades(expected.grades())
                .itemClasses(expected.itemClasses())
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of());

    controller.getAllGroupedInventory(
        LagerFilter.builder()
            .kinds(List.of(GameItemKind.WEAPON))
            .sizes(List.of(1))
            .itemClasses(List.of(ShipComponentClass.STEALTH))
            .catalog(InventoryCatalog.ITEM)
            .build());

    verify(inventoryAggregationService)
        .getAllAggregatedItemInventory(
            LagerFilter.builder()
                .kinds(expected.kinds())
                .componentTypes(expected.componentTypes())
                .sizes(expected.sizes())
                .grades(expected.grades())
                .itemClasses(expected.itemClasses())
                .catalog(InventoryCatalog.ITEM)
                .build());
  }

  @Test
  void getMyGroupedInventory_catalogMaterial_rejectsTheClassFilter() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    LagerFilter.builder()
                        .itemClasses(List.of(ShipComponentClass.MILITARY))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("itemClasses");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyGroupedInventory_catalogMaterial_rejectsTheAttributeFilters() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    LagerFilter.builder()
                        .kinds(List.of(GameItemKind.VEHICLE_ITEM))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("catalog=MATERIAL");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyEntryIds_catalogMaterial_rejectsTheAttributeFilters() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyEntryIds(
                    jwt,
                    LagerFilter.builder()
                        .componentTypes(List.of(ShipComponentType.COOLER))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("componentTypes");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getAllGroupedInventory_catalogMaterial_rejectsTheAttributeFilters() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    LagerFilter.builder()
                        .sizes(List.of(3))
                        .grades(List.of("B"))
                        .catalog(InventoryCatalog.MATERIAL)
                        .build()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("sizes");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  @Test
  void getMyStackEntries_catalogItem_requiresGameItemId_andRejectsQuality() {
    Jwt jwt = jwt("owner-sub");
    UUID locationId = UUID.randomUUID();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyStackEntries(
                    jwt,
                    null,
                    null,
                    locationId,
                    null,
                    false,
                    false,
                    null,
                    InventoryCatalog.ITEM,
                    0,
                    20))
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
            eq(false),
            isNull(),
            any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getMyStackEntries(
            jwt,
            null,
            gameItemId,
            locationId,
            null,
            true,
            false,
            null,
            InventoryCatalog.ITEM,
            0,
            20);

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
            eq(gameItemId), eq(userId), eq(locationId), eq(false), isNull(), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllStackEntries(
            null,
            gameItemId,
            userId,
            locationId,
            null,
            false,
            null,
            InventoryCatalog.ITEM,
            null,
            null);

    assertThat(result.content()).containsExactly(dto);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllStackEntries(
                    null,
                    gameItemId,
                    userId,
                    locationId,
                    800,
                    false,
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
  void changeOrgUnit_passesTheCallerToTheService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemOrgUnitChangeDto dto = new InventoryItemOrgUnitChangeDto(1L, null, Boolean.TRUE);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);

    controller.changeOrgUnit(jwt, itemId, dto);

    verify(inventoryOrgUnitChangeService).changeOrgUnit(itemId, dto, ownerId);
  }

  @Test
  void bulkChangeOrgUnit_returnsTheServiceCounts() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    BulkOrgUnitChangeRequest request =
        new BulkOrgUnitChangeRequest(List.of(UUID.randomUUID()), UUID.randomUUID(), null);
    BulkOrgUnitChangeResultDto expected = new BulkOrgUnitChangeResultDto(1, 0);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryOrgUnitChangeService.bulkChangeOrgUnit(request, ownerId)).thenReturn(expected);

    assertThat(controller.bulkChangeOrgUnit(jwt, request)).isSameAs(expected);
  }

  @Test
  void markStolen_passesTheCallerAndTheLogisticianFlagToTheService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemStolenMarkDto dto = new InventoryItemStolenMarkDto(1L, true, 2.0);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

    controller.markStolen(jwt, itemId, dto);

    verify(inventoryStolenMarkService).mark(itemId, dto, ownerId, true);
  }

  @Test
  void markStolen_nonLogisticianBranch_passesFalseToTheService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItemStolenMarkDto dto = new InventoryItemStolenMarkDto(1L, false, null);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    controller.markStolen(jwt, itemId, dto);

    verify(inventoryStolenMarkService).mark(itemId, dto, ownerId, false);
  }

  @Test
  void bulkMarkStolen_returnsTheServiceCounts() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    BulkStolenMarkRequest request = new BulkStolenMarkRequest(List.of(UUID.randomUUID()), false);
    BulkStolenMarkResultDto expected = new BulkStolenMarkResultDto(1, 0);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryStolenMarkService.bulkMark(request, ownerId)).thenReturn(expected);

    assertThat(controller.bulkMarkStolen(jwt, request)).isSameAs(expected);
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
  void addAllocation_passesTheCallerAndTheLogisticianFlagToTheService() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 4.0, 1L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(inventoryItemService.addAllocation(itemId, dto, callerId, true)).thenReturn(persisted);

    InventoryItemDto result = controller.addAllocation(jwt, itemId, dto);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).addAllocation(itemId, dto, callerId, true);
  }

  @Test
  void changeAllocation_nonLogisticianBranch_passesFalseToTheService() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.MISSION, UUID.randomUUID(), 6.0, 2L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(inventoryItemService.changeAllocation(itemId, dto, callerId, false)).thenReturn(persisted);

    InventoryItemDto result = controller.changeAllocation(jwt, itemId, dto);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).changeAllocation(itemId, dto, callerId, false);
  }

  @Test
  void removeAllocation_passesTheCallerAndTheLogisticianFlagToTheService() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), null, 3L);
    InventoryItemDto persisted = inventoryItem(itemId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(inventoryItemService.removeAllocation(itemId, dto, callerId, false)).thenReturn(persisted);

    InventoryItemDto result = controller.removeAllocation(jwt, itemId, dto);

    assertThat(result).isSameAs(persisted);
    verify(inventoryItemService).removeAllocation(itemId, dto, callerId, false);
  }

  @Test
  void getMyGroupedInventory_catalogMaterial_forwardsLocationIds() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 12.0, 700.0, 700, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId,
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.MATERIAL)
                .build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.MATERIAL)
                .build());

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(
            eq(ownerId),
            eq(
                LagerFilter.builder()
                    .locationIds(List.of(locationId))
                    .catalog(InventoryCatalog.MATERIAL)
                    .build()));
  }

  @Test
  void getMyGroupedInventory_catalogItem_forwardsLocationIdsInsteadOfRejectingThem() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 4.0, null, null, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryAggregationService.getMyAggregatedItemInventory(
            ownerId,
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.ITEM)
                .build());

    assertThat(result).containsExactly(group);
    verify(inventoryAggregationService)
        .getMyAggregatedItemInventory(
            eq(ownerId),
            eq(
                LagerFilter.builder()
                    .locationIds(List.of(locationId))
                    .catalog(InventoryCatalog.ITEM)
                    .build()));
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
            ownerId,
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.MATERIAL)
                .build()))
        .thenReturn(List.of(entry));
    when(inventoryAggregationService.getMyItemEntryIds(
            ownerId,
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of(entry));

    assertThat(
            controller.getMyEntryIds(
                jwt,
                LagerFilter.builder()
                    .locationIds(List.of(locationId))
                    .catalog(InventoryCatalog.MATERIAL)
                    .build()))
        .containsExactly(entry);
    assertThat(
            controller.getMyEntryIds(
                jwt,
                LagerFilter.builder()
                    .locationIds(List.of(locationId))
                    .catalog(InventoryCatalog.ITEM)
                    .build()))
        .containsExactly(entry);
  }

  @Test
  void getAllGroupedInventory_forwardsLocationIdsOnBothCatalogs() {
    UUID locationId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 9.0, 500.0, 500, List.of());
    when(inventoryItemService.getAllAggregatedInventory(
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.MATERIAL)
                .build()))
        .thenReturn(List.of(group));
    when(inventoryAggregationService.getAllAggregatedItemInventory(
            LagerFilter.builder()
                .locationIds(List.of(locationId))
                .catalog(InventoryCatalog.ITEM)
                .build()))
        .thenReturn(List.of(group));

    assertThat(
            controller.getAllGroupedInventory(
                LagerFilter.builder()
                    .locationIds(List.of(locationId))
                    .catalog(InventoryCatalog.MATERIAL)
                    .build()))
        .containsExactly(group);
    assertThat(
            controller.getAllGroupedInventory(
                LagerFilter.builder()
                    .locationIds(List.of(locationId))
                    .catalog(InventoryCatalog.ITEM)
                    .build()))
        .containsExactly(group);

    verifyNoInteractions(userService);
  }

  @Test
  void getMergeCandidates_passesTheCallerFromTheTokenAndWrapsTheAnswer() {
    UUID caller = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    Jwt token = jwt(caller.toString());
    when(userService.getUserIdFromJwt(token)).thenReturn(caller);
    when(inventoryItemService.hasMergeCandidates(
            null, caller, materialId, locationId, 640, false, false, unitId))
        .thenReturn(true);

    InventoryMergeCandidatesDto result =
        controller.getMergeCandidates(
            token, null, materialId, locationId, 640, false, false, unitId);

    assertThat(result.exists()).isTrue();
  }
}
