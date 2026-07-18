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
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
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
        null);
  }

  // ── GET /aggregated ───────────────────────────────────────────────────

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

    PageResponse<InventoryItemDto> result =
        controller.getMyInventory(jwt, InventoryCatalog.MATERIAL, 0, 20, null);

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
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 25.0, 750.0, 800, List.of());
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
            jwt,
            List.of(materialId),
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
            ownerId, null, null, null, null, true, false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt, null, null, null, null, null, true, false, InventoryCatalog.MATERIAL);

    assertThat(result).containsExactly(group);
    verify(inventoryItemService)
        .getMyAggregatedInventory(ownerId, null, null, null, null, true, false);
  }

  @Test
  void getMyGroupedInventory_nonPersonalOnly_forwardsFlagToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 5.0, 600.0, 600, List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryItemService.getMyAggregatedInventory(
            ownerId, null, null, null, null, false, true))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt, null, null, null, null, null, false, true, InventoryCatalog.MATERIAL);

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
        controller.getAllInventory(
            List.of(materialId), null, 700, null, null, InventoryCatalog.MATERIAL, 0, 20, null);

    assertThat(result.content()).containsExactly(dto);
    verify(inventoryItemService)
        .getAllInventory(eq(List.of(materialId)), eq(700), eq(null), eq(null), any(Pageable.class));
  }

  @Test
  void getAllGroupedInventory_delegatesWithoutJwt() {
    GroupedInventoryDto group = new GroupedInventoryDto(null, null, 25.0, 750.0, 800, List.of());
    when(inventoryItemService.getAllAggregatedInventory(null, null, null, null))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getAllGroupedInventory(null, null, null, null, null, InventoryCatalog.MATERIAL);

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

  // ── catalog=ITEM read family (V220, REQ-INV-029/030/031) ─────────────

  // covers REQ-INV-030 (catalog=ITEM aggregated view dispatches to the item sibling)
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

    // The ITEM catalog bypasses the historical material facade entirely — pre-item clients that
    // never send the parameter keep hitting inventoryItemService (pinned by the MATERIAL test).
    assertThat(result.content()).containsExactly(agg);
    verify(inventoryAggregationService).getAggregatedItemInventory(any(Pageable.class));
    verifyNoInteractions(inventoryItemService);
  }

  // covers REQ-INV-030 (catalog=ITEM flat my-inventory dispatches to the item sibling)
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

  // covers REQ-INV-029/031 (catalog=ITEM grouped view rejects the material-only filters with 400)
  @Test
  void getMyGroupedInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    Jwt jwt = jwt("alice-sub");

    // minQuality is meaningless without a quality dimension → 400, never silently ignored.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt, null, null, 700, null, null, false, false, InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    // missionIds could only ever match the empty set (item rows are never mission-allocated).
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
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

  // covers REQ-INV-029/031 (catalog=ITEM grouped view rejects a material filter with 400)
  @Test
  void getMyGroupedInventory_catalogItem_rejectsMaterialIds() {
    Jwt jwt = jwt("alice-sub");

    // Item rows carry no material — a silently dropped materialIds filter would return
    // plausible-looking but unfiltered data, so the mismatch is a 400 contract error.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    List.of(UUID.randomUUID()),
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

  // covers REQ-INV-029/031 (catalog=MATERIAL grouped view rejects an item filter with 400)
  @Test
  void getMyGroupedInventory_catalogMaterial_rejectsGameItemIds() {
    Jwt jwt = jwt("alice-sub");

    // The mirror guard: material rows carry no game item, so gameItemIds under the (default)
    // MATERIAL catalog is rejected before any owner resolution or service dispatch.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyGroupedInventory(
                    jwt,
                    null,
                    List.of(UUID.randomUUID()),
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

  // covers REQ-INV-030 (catalog=ITEM grouped view dispatches with the item filter surface)
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
            ownerId, List.of(gameItemId), List.of(jobOrderId), true, false))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getMyGroupedInventory(
            jwt,
            null,
            List.of(gameItemId),
            null,
            List.of(jobOrderId),
            null,
            true,
            false,
            InventoryCatalog.ITEM);

    assertThat(result).containsExactly(group);
    verifyNoInteractions(inventoryItemService);
  }

  // ── GET /my-inventory/entry-ids (select-all, REQ-INV-034) ─────────────

  // covers REQ-INV-034 (material select-all resolves the owner from the JWT and forwards the same
  // material filter surface as the grouped view, returning the flat id set).
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
            eq(700),
            eq(List.of(jobOrderId)),
            eq(List.of(missionId)),
            eq(false),
            eq(false));
    assertThat(ownerCaptor.getValue()).isEqualTo(ownerId);
    verifyNoInteractions(inventoryAggregationService);
  }

  // covers REQ-INV-034 (item select-all dispatches to the item id query with the item filter
  // surface and the personal toggle, never the material facade).
  @Test
  void getMyEntryIds_catalogItem_dispatchesWithItemFilters() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID entry = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(inventoryAggregationService.getMyItemEntryIds(
            ownerId, List.of(gameItemId), List.of(jobOrderId), true, false))
        .thenReturn(List.of(entry));

    List<UUID> result =
        controller.getMyEntryIds(
            jwt,
            null,
            List.of(gameItemId),
            null,
            List.of(jobOrderId),
            null,
            true,
            false,
            InventoryCatalog.ITEM);

    assertThat(result).containsExactly(entry);
    verifyNoInteractions(inventoryItemService);
  }

  // covers REQ-INV-034 + REQ-INV-029/031 (item select-all rejects the material-only filters with
  // 400, exactly like the grouped item view — never a silently-ignored filter).
  @Test
  void getMyEntryIds_catalogItem_rejectsMaterialOnlyFilters() {
    Jwt jwt = jwt("alice-sub");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyEntryIds(
                    jwt, null, null, 700, null, null, false, false, InventoryCatalog.ITEM))
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
                    false,
                    false,
                    InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  // covers REQ-INV-034 + REQ-INV-029/031 (material select-all rejects a game-item filter with 400).
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
                    false,
                    false,
                    InventoryCatalog.MATERIAL))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  // covers REQ-INV-029/031 (catalog=ITEM flat /all rejects the material-only filters with 400)
  @Test
  void getAllInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
                    null, null, 700, null, null, InventoryCatalog.ITEM, 0, 20, null))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllInventory(
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

  // covers REQ-INV-029/031 (catalog=ITEM flat /all rejects a material filter with 400)
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
                    InventoryCatalog.ITEM,
                    0,
                    20,
                    null))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  // covers REQ-INV-029/031 (catalog=MATERIAL flat /all rejects an item filter with 400)
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
                    InventoryCatalog.MATERIAL,
                    0,
                    20,
                    null))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  // covers REQ-INV-030 (catalog=ITEM flat /all dispatches to the item sibling)
  @Test
  void getAllInventory_catalogItem_dispatchesToAllItemInventory() {
    UUID gameItemId = UUID.randomUUID();
    InventoryItemDto dto = inventoryItem(UUID.randomUUID());
    Page<InventoryItemDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(inventoryAggregationService.getAllItemInventory(
            eq(List.of(gameItemId)), eq(null), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<InventoryItemDto> result =
        controller.getAllInventory(
            null, List.of(gameItemId), null, null, null, InventoryCatalog.ITEM, 0, 20, null);

    assertThat(result.content()).containsExactly(dto);
    verifyNoInteractions(inventoryItemService);
  }

  // covers REQ-INV-029/031 (catalog=ITEM grouped /all rejects the material-only filters with 400)
  @Test
  void getAllGroupedInventory_catalogItem_rejectsMinQualityAndMissionIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    null, null, 700, null, null, InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    null, null, null, null, List.of(UUID.randomUUID()), InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  // covers REQ-INV-029/031 (catalog=ITEM grouped /all rejects a material filter with 400)
  @Test
  void getAllGroupedInventory_catalogItem_rejectsMaterialIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    List.of(UUID.randomUUID()), null, null, null, null, InventoryCatalog.ITEM))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("materialIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  // covers REQ-INV-029/031 (catalog=MATERIAL grouped /all rejects an item filter with 400)
  @Test
  void getAllGroupedInventory_catalogMaterial_rejectsGameItemIds() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getAllGroupedInventory(
                    null, List.of(UUID.randomUUID()), null, null, null, InventoryCatalog.MATERIAL))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("gameItemIds");
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
  }

  // covers REQ-INV-030 (catalog=ITEM grouped /all dispatches to the item sibling)
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
    when(inventoryAggregationService.getAllAggregatedItemInventory(List.of(gameItemId), null))
        .thenReturn(List.of(group));

    List<GroupedInventoryDto> result =
        controller.getAllGroupedInventory(
            null, List.of(gameItemId), null, null, null, InventoryCatalog.ITEM);

    assertThat(result).containsExactly(group);
    verifyNoInteractions(inventoryItemService);
  }

  // covers REQ-INV-005/029 (catalog=ITEM stack drill-down: gameItemId required, quality rejected)
  @Test
  void getMyStackEntries_catalogItem_requiresGameItemId_andRejectsQuality() {
    Jwt jwt = jwt("owner-sub");
    UUID locationId = UUID.randomUUID();

    // The stack address of an item drill-down is the gameItemId — absent → 400.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.getMyStackEntries(
                    jwt, null, null, locationId, null, false, null, InventoryCatalog.ITEM, 0, 20))
        .isInstanceOf(BadRequestException.class);
    // Item stacks carry no quality key — a quality param is a contract error → 400.
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

  // covers REQ-INV-005/029 (catalog=ITEM my-stack drill-down dispatches to the item sibling)
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

  // covers REQ-INV-005/029 (catalog=ITEM global stack drill-down dispatches to the item sibling)
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
    // Quality on the global item drill-down is rejected exactly like the my-variant.
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

  // ── GET /game-item/{gameItemId} (item drilldown) ─────────────────────

  // covers REQ-INV-030 (per-game-item drilldown wired to the aggregation service)
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

  // ── GET /item-catalog (bookable game items picker) ───────────────────

  // covers REQ-INV-029 (item-catalog picker returns the paged reference shape with a stable
  // id tiebreaker)
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
    // The sort whitelist includes `id`, so PaginationUtil appends it as a tiebreaker even when the
    // caller sorts by name only — equal-named UEX variants keep a deterministic page order (the
    // name,asc;id,asc default-sort contract). A whitelist regression to {name} drops the appended
    // key and this captor catches it.
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(inventoryItemCatalogService).findBookableItems(eq("drive"), pageable.capture());
    org.springframework.data.domain.Sort sort = pageable.getValue().getSort();
    assertThat(sort.getOrderFor("name")).isNotNull();
    assertThat(sort.getOrderFor("id")).isNotNull();
    verifyNoInteractions(inventoryItemService, inventoryAggregationService);
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
            null,
            materialId,
            null,
            locationId,
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
