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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryCatalog;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.InventoryAggregationService;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemCatalogService;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.service.InventoryOrgUnitChangeService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for inventory items: grouped and aggregated reads, the caller's own and the
 * squadron-wide lists, create/update, book-out, rebook and allocation endpoints.
 *
 * <p>Reads take a {@code catalog} parameter defaulting to {@link InventoryCatalog#MATERIAL}; {@code
 * catalog=ITEM} rejects the material-only filters with 400 (REQ-INV-029, REQ-INV-031). The
 * class-level {@code isAuthenticated()} gate is only the floor (REQ-SEC-052) and is ANDed with the
 * URL rule requiring ADMIN, OFFICER, LOGISTICIAN or KRT_MEMBER.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class InventoryItemController {

  /** Default page size for a stack-entries drill-down when the caller does not specify one. */
  private static final int STACK_ENTRIES_DEFAULT_SIZE = 20;

  /** Upper bound on a stack-entries page size, clamping the per-request load. */
  private static final int STACK_ENTRIES_MAX_SIZE = 100;

  /** Default multi-key sort of the material flat lists ({@code /my-inventory}, {@code /all}). */
  private static final String MATERIAL_FLAT_DEFAULT_SORT =
      "material.name,asc;quality,desc;amount,desc";

  /** Default multi-key sort of the game-item flat lists ({@code catalog=ITEM}). */
  private static final String ITEM_FLAT_DEFAULT_SORT = "gameItem.name,asc;amount,desc";

  /** Sort whitelist of the material flat lists. */
  private static final Set<String> MATERIAL_FLAT_SORT_FIELDS =
      Set.of("amount", "quality", "id", "material.name");

  /** Sort whitelist of the game-item flat lists — no quality key (REQ-INV-029). */
  private static final Set<String> ITEM_FLAT_SORT_FIELDS = Set.of("amount", "id", "gameItem.name");

  /** Default sort of the aggregated game-item view ({@code catalog=ITEM}). */
  private static final String ITEM_AGGREGATED_DEFAULT_SORT = "gameItem.name,asc;amount,desc";

  /** Sort whitelist of the aggregated game-item view — grouped rows carry no id or quality. */
  private static final Set<String> ITEM_AGGREGATED_SORT_FIELDS = Set.of("amount", "gameItem.name");

  private final InventoryItemService inventoryItemService;
  private final InventoryOrgUnitChangeService inventoryOrgUnitChangeService;
  private final InventoryAggregationService inventoryAggregationService;
  private final InventoryItemCatalogService inventoryItemCatalogService;
  private final UserService userService;
  private final AuthHelperService authHelperService;

  /**
   * Aggregated per-catalog-entry inventory view. For {@code catalog=MATERIAL} (the default) the
   * sort favors material name, then quality descending, then amount — the order operators actually
   * want; {@code catalog=ITEM} aggregates per game item with no quality columns (REQ-INV-028/029)
   * under a {@code gameItem.name} / {@code amount} whitelist.
   *
   * @param catalog which stock catalog to aggregate; defaults to {@code MATERIAL}
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec; {@code null} falls back to the per-catalog default
   * @return paged aggregated DTOs
   */
  @GetMapping("/aggregated")
  @Transactional(readOnly = true)
  public PageResponse<AggregatedInventoryDto> getAggregatedInventory(
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    if (catalog == InventoryCatalog.ITEM) {
      Pageable pageable =
          PaginationUtil.createPageRequest(
              page,
              size,
              sort != null ? sort : ITEM_AGGREGATED_DEFAULT_SORT,
              ITEM_AGGREGATED_SORT_FIELDS,
              "gameItem.name");
      return PageResponse.of(inventoryAggregationService.getAggregatedItemInventory(pageable));
    }
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort != null ? sort : MATERIAL_FLAT_DEFAULT_SORT,
            Set.of("amount", "quality", "material.name"),
            "material.name");
    Page<AggregatedInventoryDto> p = inventoryItemService.getAggregatedInventory(pageable);
    return PageResponse.of(p);
  }

  /**
   * Per-material drilldown — every individual inventory row for the given material.
   *
   * @param materialId material to drill into
   * @return paged inventory items
   */
  @GetMapping("/material/{materialId}")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getInventoryByMaterial(
      @PathVariable @NotNull UUID materialId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false, defaultValue = "quality,desc;location.name,asc;amount,desc")
          String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("amount", "quality", "id", "location.name"), "quality");
    Page<InventoryItemDto> p = inventoryItemService.getInventoryByMaterial(materialId, pageable);
    return PageResponse.of(p);
  }

  /**
   * Lists every non-personal stock row of the given game item (REQ-INV-029).
   *
   * @param gameItemId game item to drill into
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec (whitelist: {@code location.name}, {@code amount}, {@code id})
   * @return paged inventory rows stocking that game item
   */
  @GetMapping("/game-item/{gameItemId}")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getInventoryByGameItem(
      @PathVariable @NotNull UUID gameItemId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false, defaultValue = "location.name,asc;amount,desc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("amount", "id", "location.name"), "location.name");
    Page<InventoryItemDto> p =
        inventoryAggregationService.getInventoryByGameItem(gameItemId, pageable);
    return PageResponse.of(p);
  }

  /**
   * Lists the calling user's own inventory rows of the given catalog; the owner comes from the JWT
   * (REQ-INV-029).
   *
   * @param jwt the caller's token (owner scope)
   * @param catalog which stock catalog to list; defaults to {@code MATERIAL}
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec; {@code null} falls back to the per-catalog default
   * @return paged inventory items
   */
  @GetMapping("/my-inventory")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getMyInventory(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    if (catalog == InventoryCatalog.ITEM) {
      Pageable pageable =
          PaginationUtil.createPageRequest(
              page,
              size,
              sort != null ? sort : ITEM_FLAT_DEFAULT_SORT,
              ITEM_FLAT_SORT_FIELDS,
              "gameItem.name");
      return PageResponse.of(
          inventoryAggregationService.getUserItemInventory(
              userService.getUserIdFromJwt(jwt), pageable));
    }
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort != null ? sort : MATERIAL_FLAT_DEFAULT_SORT,
            MATERIAL_FLAT_SORT_FIELDS,
            "quality");
    Page<InventoryItemDto> p =
        inventoryItemService.getUserInventory(userService.getUserIdFromJwt(jwt), pageable);
    return PageResponse.of(p);
  }

  /**
   * Returns the calling user's inventory grouped by material or game item with totals and average
   * quality.
   *
   * @param personalOnly when {@code true}, only the caller's personal rows; defaults to {@code
   *     false}
   * @param nonPersonalOnly when {@code true}, only the caller's shared rows; defaults to {@code
   *     false} and is mutually exclusive with {@code personalOnly}
   * @param catalog which stock catalog to group; defaults to {@code MATERIAL}; a filter that does
   *     not match the catalog is rejected with 400 (REQ-INV-029/031)
   * @param gameItemIds optional game-item filter ({@code catalog=ITEM} only; 400 otherwise)
   * @param locationIds optional storage-location filter, valid for both catalogs (REQ-INV-040)
   * @return grouped DTOs
   */
  @GetMapping("/my-inventory/grouped")
  @Transactional(readOnly = true)
  public List<GroupedInventoryDto> getMyGroupedInventory(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
      @RequestParam(required = false) List<UUID> locationIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false, defaultValue = "false") boolean personalOnly,
      @RequestParam(required = false, defaultValue = "false") boolean nonPersonalOnly,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog) {
    if (catalog == InventoryCatalog.ITEM) {
      rejectMaterialOnlyFilters(minQuality, missionIds, materialIds);
      return inventoryAggregationService.getMyAggregatedItemInventory(
          userService.getUserIdFromJwt(jwt),
          gameItemIds,
          locationIds,
          jobOrderIds,
          personalOnly,
          nonPersonalOnly);
    }
    rejectItemOnlyFilters(gameItemIds);
    return inventoryItemService.getMyAggregatedInventory(
        userService.getUserIdFromJwt(jwt),
        materialIds,
        locationIds,
        minQuality,
        jobOrderIds,
        missionIds,
        personalOnly,
        nonPersonalOnly);
  }

  /**
   * Returns the ids of every one of the caller's own entries matching the grouped-view filter,
   * backing the "Alle markieren" select-all (REQ-INV-034). Applies the same filters and
   * catalog-mismatch rejection as {@link #getMyGroupedInventory}.
   *
   * @param jwt the caller's token, resolved to the owning user id
   * @param materialIds optional material filter ({@code catalog=MATERIAL} only; 400 otherwise)
   * @param gameItemIds optional game-item filter ({@code catalog=ITEM} only; 400 otherwise)
   * @param locationIds optional storage-location filter (both catalogs, REQ-INV-040)
   * @param minQuality optional quality floor; rejected for {@code catalog=ITEM}
   * @param jobOrderIds optional job-order filter (both catalogs)
   * @param missionIds optional mission filter; rejected for {@code catalog=ITEM}
   * @param personalOnly when {@code true}, narrows to the caller's private stock rows
   * @param nonPersonalOnly when {@code true}, narrows to the caller's shared stock rows; mutually
   *     exclusive with {@code personalOnly}
   * @param catalog which stock catalog to select from; defaults to {@code MATERIAL}
   * @return the ids of every matching entry, in creation order
   */
  @GetMapping("/my-inventory/entry-ids")
  @Transactional(readOnly = true)
  public List<UUID> getMyEntryIds(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
      @RequestParam(required = false) List<UUID> locationIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false, defaultValue = "false") boolean personalOnly,
      @RequestParam(required = false, defaultValue = "false") boolean nonPersonalOnly,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog) {
    if (catalog == InventoryCatalog.ITEM) {
      rejectMaterialOnlyFilters(minQuality, missionIds, materialIds);
      return inventoryAggregationService.getMyItemEntryIds(
          userService.getUserIdFromJwt(jwt),
          gameItemIds,
          locationIds,
          jobOrderIds,
          personalOnly,
          nonPersonalOnly);
    }
    rejectItemOnlyFilters(gameItemIds);
    return inventoryItemService.getMyEntryIds(
        userService.getUserIdFromJwt(jwt),
        materialIds,
        locationIds,
        minQuality,
        jobOrderIds,
        missionIds,
        personalOnly,
        nonPersonalOnly);
  }

  /**
   * Squadron-wide flat inventory list (admin/logistician view). A filter that does not match the
   * catalog is rejected with 400 (REQ-INV-029/031).
   *
   * @param materialIds optional material filter ({@code catalog=MATERIAL} only; 400 otherwise)
   * @param gameItemIds optional game-item filter ({@code catalog=ITEM} only; 400 otherwise)
   * @param locationIds optional storage-location filter (both catalogs, REQ-INV-040)
   * @param minQuality optional quality floor; rejected for {@code catalog=ITEM}
   * @param jobOrderIds optional job-order filter (both catalogs)
   * @param missionIds optional mission filter; rejected for {@code catalog=ITEM}
   * @param catalog which stock catalog to list; defaults to {@code MATERIAL}
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec; {@code null} falls back to the per-catalog default
   * @return paged inventory items
   */
  @GetMapping("/all")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getAllInventory(
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
      @RequestParam(required = false) List<UUID> locationIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    if (catalog == InventoryCatalog.ITEM) {
      rejectMaterialOnlyFilters(minQuality, missionIds, materialIds);
      Pageable pageable =
          PaginationUtil.createPageRequest(
              page,
              size,
              sort != null ? sort : ITEM_FLAT_DEFAULT_SORT,
              ITEM_FLAT_SORT_FIELDS,
              "gameItem.name");
      return PageResponse.of(
          inventoryAggregationService.getAllItemInventory(
              gameItemIds, locationIds, jobOrderIds, pageable));
    }
    rejectItemOnlyFilters(gameItemIds);
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort != null ? sort : MATERIAL_FLAT_DEFAULT_SORT,
            MATERIAL_FLAT_SORT_FIELDS,
            "quality");
    Page<InventoryItemDto> p =
        inventoryItemService.getAllInventory(
            materialIds, locationIds, minQuality, jobOrderIds, missionIds, pageable);
    return PageResponse.of(p);
  }

  /**
   * Lists every inventory item linked to a mission, for the mission-detail "Lagereinträge" table.
   * Visible to every member, not org-unit-scoped.
   *
   * @param missionId the mission whose linked inventory to list
   * @return the mission's inventory items
   */
  @GetMapping("/mission/{missionId}")
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getMissionInventory(@PathVariable @NotNull UUID missionId) {
    return inventoryItemService.getMissionInventory(missionId);
  }

  /**
   * Squadron-wide variant of {@link #getMyGroupedInventory}. A filter that does not match the
   * catalog is rejected with 400 (REQ-INV-029/031).
   *
   * @param materialIds optional material filter ({@code catalog=MATERIAL} only; 400 otherwise)
   * @param gameItemIds optional game-item filter ({@code catalog=ITEM} only; 400 otherwise)
   * @param locationIds optional storage-location filter (both catalogs, REQ-INV-040)
   * @param minQuality optional quality floor; rejected for {@code catalog=ITEM}
   * @param jobOrderIds optional job-order filter (both catalogs)
   * @param missionIds optional mission filter; rejected for {@code catalog=ITEM}
   * @param catalog which stock catalog to group; defaults to {@code MATERIAL}
   * @return grouped DTOs
   */
  @GetMapping("/all/grouped")
  @Transactional(readOnly = true)
  public List<GroupedInventoryDto> getAllGroupedInventory(
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) List<UUID> gameItemIds,
      @RequestParam(required = false) List<UUID> locationIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog) {
    if (catalog == InventoryCatalog.ITEM) {
      rejectMaterialOnlyFilters(minQuality, missionIds, materialIds);
      return inventoryAggregationService.getAllAggregatedItemInventory(
          gameItemIds, locationIds, jobOrderIds);
    }
    rejectItemOnlyFilters(gameItemIds);
    return inventoryItemService.getAllAggregatedInventory(
        materialIds, locationIds, minQuality, jobOrderIds, missionIds);
  }

  /**
   * Returns one oldest-first page of the entries of one of the caller's own stacks, addressed by
   * the stack-key query parameters of {@code InventoryStackDto}.
   *
   * <p>A {@code null} owning-org-unit parameter selects rows without one. {@code MATERIAL} requires
   * {@code materialId}; {@code ITEM} requires {@code gameItemId} and rejects {@code quality}
   * (REQ-INV-005/029).
   *
   * @return one page of the stack's entries, oldest-first
   */
  @GetMapping("/my-inventory/stack/entries")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getMyStackEntries(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID materialId,
      @RequestParam(required = false) UUID gameItemId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false, defaultValue = "false") Boolean personal,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    if (catalog == InventoryCatalog.ITEM) {
      requireItemStackKey(gameItemId, quality);
      return PageResponse.of(
          inventoryAggregationService.getMyItemStackEntries(
              userService.getUserIdFromJwt(jwt),
              gameItemId,
              locationId,
              personal,
              owningOrgUnitId,
              stackEntriesPageRequest(page, size)));
    }
    requireMaterialStackKey(materialId);
    Page<InventoryItemDto> p =
        inventoryItemService.getMyStackEntries(
            userService.getUserIdFromJwt(jwt),
            materialId,
            locationId,
            quality,
            personal,
            owningOrgUnitId,
            stackEntriesPageRequest(page, size));
    return PageResponse.of(p);
  }

  /**
   * Squadron-wide variant of {@link #getMyStackEntries}, keyed additionally by the stack's owning
   * {@code userId} and restricted to the caller's org-unit scope (REQ-INV-005/029).
   *
   * @return one page of the stack's entries, oldest-first
   */
  @GetMapping("/all/stack/entries")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getAllStackEntries(
      @RequestParam(required = false) UUID materialId,
      @RequestParam(required = false) UUID gameItemId,
      @RequestParam @NotNull UUID userId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    if (catalog == InventoryCatalog.ITEM) {
      requireItemStackKey(gameItemId, quality);
      return PageResponse.of(
          inventoryAggregationService.getAllItemStackEntries(
              gameItemId,
              userId,
              locationId,
              owningOrgUnitId,
              stackEntriesPageRequest(page, size)));
    }
    requireMaterialStackKey(materialId);
    Page<InventoryItemDto> p =
        inventoryItemService.getAllStackEntries(
            materialId,
            userId,
            locationId,
            quality,
            owningOrgUnitId,
            stackEntriesPageRequest(page, size));
    return PageResponse.of(p);
  }

  /**
   * Paged picker of the game items bookable as Lager item stock: the output of at least one active
   * blueprint (REQ-INV-029).
   *
   * @param q optional case-insensitive item-name filter
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec (whitelist: {@code name}, {@code id}; the default appends {@code id} as a
   *     tiebreaker)
   * @return paged bookable game-item references
   */
  @GetMapping("/item-catalog")
  @Operation(
      summary = "List bookable game items",
      description =
          "Returns a paginated list of game items that can be booked as Lager item stock (output"
              + " of at least one active blueprint).")
  @Transactional(readOnly = true)
  public PageResponse<InventoryGameItemReferenceDto> getItemCatalog(
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "name,asc;id,asc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    Page<InventoryGameItemReferenceDto> p =
        inventoryItemCatalogService.findBookableItems(q, pageable);
    return PageResponse.of(p);
  }

  /**
   * Builds an unsorted, clamped page request for a stack-entries drill-down, so the repository's
   * oldest-first order is the sole ordering (REQ-INV-002).
   *
   * @param page the requested zero-based page index, or {@code null} for the first page
   * @param size the requested page size, or {@code null} for the default
   * @return a sortless page request with clamped page and size
   */
  private static Pageable stackEntriesPageRequest(Integer page, Integer size) {
    int resolvedPage = page != null && page >= 0 ? page : 0;
    int resolvedSize =
        size != null && size > 0
            ? Math.min(size, STACK_ENTRIES_MAX_SIZE)
            : STACK_ENTRIES_DEFAULT_SIZE;
    return PageRequest.of(resolvedPage, resolvedSize);
  }

  /**
   * Rejects the material-only filters {@code minQuality}, {@code missionIds} and {@code
   * materialIds} on a {@code catalog=ITEM} read (REQ-INV-029/031).
   *
   * @param minQuality the quality floor; must be {@code null} for the item catalog
   * @param missionIds the mission filter; must be {@code null} or empty for the item catalog
   * @param materialIds the material filter; must be {@code null} or empty for the item catalog
   * @throws BadRequestException when any material-only filter is present
   */
  private static void rejectMaterialOnlyFilters(
      Integer minQuality, List<UUID> missionIds, List<UUID> materialIds) {
    if (minQuality != null) {
      throw new BadRequestException("minQuality is not supported for catalog=ITEM");
    }
    if (missionIds != null && !missionIds.isEmpty()) {
      throw new BadRequestException("missionIds is not supported for catalog=ITEM");
    }
    if (materialIds != null && !materialIds.isEmpty()) {
      throw new BadRequestException("materialIds is not supported for catalog=ITEM");
    }
  }

  /**
   * Rejects the item-only {@code gameItemIds} filter on a {@code catalog=MATERIAL} read.
   *
   * @param gameItemIds the game-item filter; must be {@code null} or empty for the material catalog
   * @throws BadRequestException when the item-only filter is present
   */
  private static void rejectItemOnlyFilters(List<UUID> gameItemIds) {
    if (gameItemIds != null && !gameItemIds.isEmpty()) {
      throw new BadRequestException("gameItemIds is not supported for catalog=MATERIAL");
    }
  }

  /**
   * Validates the stack address of a {@code catalog=ITEM} stack-entries drill-down (REQ-INV-005/
   * 029): the stack is keyed by {@code gameItemId}, and a {@code quality} parameter is rejected
   * because item stacks carry no quality dimension.
   *
   * @param gameItemId the item stack key; must be present
   * @param quality the material-only quality key; must be absent
   * @throws BadRequestException when the item key is missing or a quality key is supplied
   */
  private static void requireItemStackKey(UUID gameItemId, Integer quality) {
    if (gameItemId == null) {
      throw new BadRequestException("gameItemId is required for catalog=ITEM");
    }
    if (quality != null) {
      throw new BadRequestException("quality is not supported for catalog=ITEM");
    }
  }

  /**
   * Validates that a {@code catalog=MATERIAL} stack-entries drill-down names its {@code materialId}
   * stack key.
   *
   * @param materialId the material stack key; must be present
   * @throws BadRequestException when the material key is missing
   */
  private static void requireMaterialStackKey(UUID materialId) {
    if (materialId == null) {
      throw new BadRequestException("materialId is required for catalog=MATERIAL");
    }
  }

  /**
   * Creates an inventory item. Booking for another member requires shared editable org-unit scope
   * with that receiver, checked in the service (REQ-SEC-005).
   *
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  public InventoryItemDto createInventoryItem(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid InventoryItemCreateDto dto) {
    return inventoryItemService.createInventoryItem(dto, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Books out an item (consume / transfer / sell). Returns 204 No Content when the post-decrement
   * quantity drops below the epsilon and the row is removed entirely; 200 OK with the persisted
   * item otherwise.
   *
   * @return the persisted DTO or 204
   */
  @PostMapping("/{id}/book-out")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public ResponseEntity<InventoryItemDto> bookOutInventoryItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemBookOutDto dto) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    InventoryItemDto result =
        inventoryItemService.bookOutInventoryItem(
            id, dto, userService.getUserIdFromJwt(jwt), isLogistician);
    if (result == null) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.ok(result);
  }

  /**
   * Rebooks (Umbuchung) part or all of an inventory row between the owner's personal pool and the
   * shared squadron pool by toggling its {@code personal} marker (REQ-INV-007). The direction is
   * inferred from the source row's current flag; the moved quantity is always inserted as a new row
   * (append-only) and returned. Optimistic locking is enforced via the {@code version} field.
   *
   * @return the persisted new-row DTO
   */
  @Operation(
      summary = "Rebook personal marker",
      description =
          "Splits part or all of an inventory row into a new row with the opposite personal"
              + " marker (personal <-> shared squadron pool). Append-only; applies optimistic"
              + " locking via the version field.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rebooked; the new row is returned"),
    @ApiResponse(responseCode = "400", description = "Invalid amount or assigned personal target"),
    @ApiResponse(responseCode = "403", description = "Access denied"),
    @ApiResponse(responseCode = "404", description = "Inventory item not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict")
  })
  @PostMapping("/{id}/personal-rebook")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto rebookPersonal(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemPersonalRebookDto dto) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    return inventoryItemService.rebookPersonal(
        id, dto, userService.getUserIdFromJwt(jwt), isLogistician);
  }

  /**
   * Sets, updates or removes the free-text note of an inventory item. Owner may always modify their
   * own note; non-owners require LOGISTICIAN (or higher via role hierarchy) role. Optimistic
   * locking is enforced via the {@code version} field in the request.
   */
  @PutMapping("/{id}/note")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto updateInventoryItemNote(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemNoteUpdateRequest request) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    return inventoryItemService.updateNote(
        id, request, userService.getUserIdFromJwt(jwt), isLogistician);
  }

  /**
   * Books out a list of inventory items in one transaction, unlinking them with a single bulk
   * update after the loop.
   */
  @Operation(
      summary = "Bulk checkout",
      description =
          "Removes all specified inventory items that belong to the authenticated user."
              + " Associations to job orders and missions are cleared before deletion.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Bulk checkout successful"),
    @ApiResponse(responseCode = "400", description = "Invalid request (empty list)"),
    @ApiResponse(
        responseCode = "403",
        description = "Access denied – item belongs to another user"),
    @ApiResponse(responseCode = "404", description = "One or more items not found")
  })
  @PostMapping("/bulk-checkout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void bulkCheckout(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BulkCheckoutRequest request) {
    inventoryItemService.bulkCheckout(request, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Rebooks a list of the caller's own inventory rows in one transaction (Massen-Umbuchen,
   * REQ-INV-036) — to another location/owner or across the personal marker. Every row moves in
   * full. Owner-scoped from the JWT like {@link #bulkCheckout}: the caller can only ever move their
   * own stock, so no logistician escalation applies.
   *
   * @return how many rows were moved and how many were skipped as already-at-target
   */
  /**
   * Changes the owning org unit of one of the caller's own personal rows (REQ-INV-052): to one of
   * the caller's direct memberships, or to no unit. The row then merges into an existing stack of
   * the new unit where the merge rules allow; the version is checked optimistically.
   *
   * @param jwt the caller's token
   * @param id the row
   * @param dto the version, the target unit or {@code null}, and the merge opt-in
   * @return the row after the change, or the stack row it was merged into
   */
  @Operation(
      summary = "Change a personal row's org unit",
      description =
          "Sets the owning org unit of one of the caller's own personal rows to one of the"
              + " caller's direct memberships, or to none. Merges into an existing stack of the new"
              + " unit per the stock-merge rules; applies optimistic locking via the version"
              + " field.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Changed; the resulting row is returned"),
    @ApiResponse(
        responseCode = "400",
        description = "Not a personal row, or the unit is not one of the caller's memberships"),
    @ApiResponse(responseCode = "403", description = "Access denied - row of another member"),
    @ApiResponse(responseCode = "404", description = "Inventory item not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict")
  })
  @PostMapping("/{id}/org-unit")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto changeOrgUnit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemOrgUnitChangeDto dto) {
    return inventoryOrgUnitChangeService.changeOrgUnit(id, dto, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Changes the owning org unit of a selection of the caller's own personal rows (REQ-INV-052) in
   * one transaction; rows already carrying the target unit are skipped and counted.
   *
   * @param jwt the caller's token
   * @param request the selection, the target unit or {@code null}, and the merge opt-in
   * @return the changed and skipped counts
   */
  @Operation(
      summary = "Bulk change personal rows' org unit",
      description =
          "Sets the owning org unit of every listed personal row of the caller to one of the"
              + " caller's direct memberships, or to none. Rows already carrying it are skipped;"
              + " an unknown id, a foreign or a shared row aborts the whole action.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Changed; changed/skipped counts returned"),
    @ApiResponse(
        responseCode = "400",
        description = "Empty list, a shared row, or a unit that is not one of the memberships"),
    @ApiResponse(responseCode = "403", description = "Access denied - row of another member"),
    @ApiResponse(responseCode = "404", description = "One or more rows not found")
  })
  @PostMapping("/bulk-org-unit")
  @PreAuthorize("isAuthenticated()")
  public BulkOrgUnitChangeResultDto bulkChangeOrgUnit(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BulkOrgUnitChangeRequest request) {
    return inventoryOrgUnitChangeService.bulkChangeOrgUnit(
        request, userService.getUserIdFromJwt(jwt));
  }

  @Operation(
      summary = "Bulk rebook",
      description =
          "Moves all specified inventory rows belonging to the authenticated user in full — to a"
              + " target location/owner, or across the personal marker. Rows already in the"
              + " requested target state are skipped and reported; an unknown id, a foreign row or"
              + " an earmarked row blocking a personalize aborts the whole action.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rebooked; moved/skipped counts are returned"),
    @ApiResponse(
        responseCode = "400",
        description = "Empty list, no transfer target, or an earmarked row blocking a personalize"),
    @ApiResponse(responseCode = "403", description = "Access denied - row belongs to another user"),
    @ApiResponse(responseCode = "404", description = "One or more rows, or a target, not found")
  })
  @PostMapping("/bulk-rebook")
  @PreAuthorize("isAuthenticated()")
  public BulkRebookResultDto bulkRebook(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BulkRebookRequest request) {
    return inventoryItemService.bulkRebook(request, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Admin/logistician shortcut to flip the {@code delivered} flag without going through the full
   * book-out machinery.
   *
   * @return the persisted DTO
   */
  @Operation(
      summary = "Update delivered status",
      description =
          "Updates the delivered flag of an inventory item. Applies optimistic locking via the"
              + " version field.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Delivered status updated successfully"),
    @ApiResponse(responseCode = "400", description = "Invalid request"),
    @ApiResponse(responseCode = "403", description = "Access denied"),
    @ApiResponse(responseCode = "404", description = "Inventory item not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict")
  })
  @PatchMapping("/{id}/delivered")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto updateDelivered(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid UpdateDeliveredRequest request) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    return inventoryItemService.updateDelivered(
        id, request, userService.getUserIdFromJwt(jwt), isLogistician);
  }

  /**
   * Adds a quantity slice earmarking part of an entry to a job order or mission (Variante C,
   * REQ-INV-027). One entry may carry several slices per dimension, each with its own amount, split
   * independently of the other dimension. The request echoes the entry's {@code version}; the write
   * force-increments it so the response carries the version the client must echo next.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, amount, version).
   * @return the updated entry DTO (new version + both refreshed slice lists).
   */
  @Operation(
      summary = "Add an inventory allocation",
      description =
          "Earmarks part of an inventory entry's quantity to a job order or mission. Rejects a"
              + " personal entry, a material not required by the order, a duplicate target, and an"
              + " amount that would over-allocate the dimension (422).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Allocation added; the updated entry returned"),
    @ApiResponse(
        responseCode = "400",
        description = "Personal entry, wrong material, or bad amount"),
    @ApiResponse(responseCode = "403", description = "Access denied"),
    @ApiResponse(responseCode = "404", description = "Entry, job order or mission not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict"),
    @ApiResponse(responseCode = "422", description = "Over-allocation (dimension Σ exceeds amount)")
  })
  @PostMapping("/{id}/allocation")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto addAllocation(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryAllocationWriteDto dto) {
    return inventoryItemService.addAllocation(id, dto);
  }

  /**
   * Changes the amount of an existing quantity slice (Variante C, REQ-INV-027). Same version echo /
   * force-increment as {@link #addAllocation}.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, new amount, version).
   * @return the updated entry DTO.
   */
  @Operation(
      summary = "Change an inventory allocation amount",
      description =
          "Updates the earmarked amount of an existing slice. Rejects a bad amount and an amount"
              + " that would over-allocate the dimension (422).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Allocation changed; the updated entry returned"),
    @ApiResponse(responseCode = "400", description = "Personal entry or bad amount"),
    @ApiResponse(responseCode = "403", description = "Access denied"),
    @ApiResponse(responseCode = "404", description = "Entry or allocation not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict"),
    @ApiResponse(responseCode = "422", description = "Over-allocation (dimension Σ exceeds amount)")
  })
  @PatchMapping("/{id}/allocation")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto changeAllocation(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryAllocationWriteDto dto) {
    return inventoryItemService.changeAllocation(id, dto);
  }

  /**
   * Removes a quantity slice, releasing its amount back to the entry's unallocated remainder
   * (Variante C, REQ-INV-027). The {@code amount} field of the payload is ignored. Same version
   * echo / force-increment as {@link #addAllocation}.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, version).
   * @return the updated entry DTO.
   */
  @Operation(
      summary = "Remove an inventory allocation",
      description = "Removes a job-order or mission slice from an inventory entry.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Allocation removed; the updated entry returned"),
    @ApiResponse(responseCode = "403", description = "Access denied"),
    @ApiResponse(responseCode = "404", description = "Entry or allocation not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict")
  })
  @DeleteMapping("/{id}/allocation")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto removeAllocation(
      @PathVariable @NotNull UUID id, @RequestBody @Valid InventoryAllocationWriteDto dto) {
    return inventoryItemService.removeAllocation(id, dto);
  }

  /**
   * Admin-only: removes every non-personal inventory item ("globales Lager leeren"). Personal
   * entries are deliberately left untouched. Gated by {@code hasRole('ADMIN')} so neither {@code
   * OFFICER} nor {@code LOGISTICIAN} can trigger the squadron-wide wipe.
   *
   * @return 204 No Content
   */
  @Operation(
      summary = "Delete all global inventory",
      description =
          "Removes every non-personal inventory item (the shared squadron stock). Personal"
              + " entries (personal = true) remain untouched. Admin-only.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "All global inventory items removed"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
  })
  @DeleteMapping("/all")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public ResponseEntity<Void> deleteAllGlobalInventory() {
    inventoryItemService.deleteAllGlobalInventory();
    return ResponseEntity.noContent().build();
  }
}
