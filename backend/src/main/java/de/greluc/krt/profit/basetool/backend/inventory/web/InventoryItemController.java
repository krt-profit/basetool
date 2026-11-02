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

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkStolenMarkResultDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAggregationService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryCatalog;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemPoolChangeDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryMergeCandidatesDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryOrgUnitChangeService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryPoolChangeService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStolenMarkService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.LagerFilter;
import de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springdoc.core.annotations.ParameterObject;
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
import org.springframework.web.bind.annotation.ModelAttribute;
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
 * class-level {@code isAuthenticated()} gate is only the floor (REQ-SEC-052); the endpoints outside
 * {@code /my-inventory} and the owner-checked item routes carry {@link #INVENTORY_ACCESS} on the
 * method, which the URL rule repeats.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class InventoryItemController {

  /** Role gate of the squadron-wide inventory endpoints: ADMIN, LOGISTICIAN or KRT_MEMBER. */
  public static final String INVENTORY_ACCESS =
      "hasAnyRole('" + Roles.ADMIN + "','" + Roles.LOGISTICIAN + "','" + Roles.KRT_MEMBER + "')";

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
  private final InventoryPoolChangeService inventoryPoolChangeService;
  private final InventoryStolenMarkService inventoryStolenMarkService;
  private final InventoryAggregationService inventoryAggregationService;
  private final UserService userService;
  private final InventoryAccessPolicy inventoryAccessPolicy;

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
  @PreAuthorize(INVENTORY_ACCESS)
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
  @PreAuthorize(INVENTORY_ACCESS)
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
  @PreAuthorize(INVENTORY_ACCESS)
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
   * <p>A filter that does not match the catalog is rejected with 400 (REQ-INV-029/031); {@code
   * personalOnly} and {@code nonPersonalOnly}, like {@code stolenOnly} and {@code nonStolenOnly},
   * exclude each other and answer empty together (REQ-INV-053).
   *
   * @param jwt the caller's token (owner scope)
   * @param filter the Lager filters bound from the query string
   * @return grouped DTOs
   */
  @GetMapping("/my-inventory/grouped")
  @Transactional(readOnly = true)
  public List<GroupedInventoryDto> getMyGroupedInventory(
      @AuthenticationPrincipal Jwt jwt, @ParameterObject @ModelAttribute LagerFilter filter) {
    filter.requireMatchingCatalog();
    UUID userId = userService.getUserIdFromJwt(jwt);
    if (filter.catalog() == InventoryCatalog.ITEM) {
      return inventoryAggregationService.getMyAggregatedItemInventory(userId, filter);
    }
    return inventoryItemService.getMyAggregatedInventory(userId, filter);
  }

  /**
   * Returns the ids of every one of the caller's own entries matching the grouped-view filter,
   * backing the "Alle markieren" select-all (REQ-INV-034). Applies the same filters and
   * catalog-mismatch rejection as {@link #getMyGroupedInventory}.
   *
   * @param jwt the caller's token, resolved to the owning user id
   * @param filter the Lager filters bound from the query string
   * @return the ids of every matching entry, in creation order
   */
  @GetMapping("/my-inventory/entry-ids")
  @Transactional(readOnly = true)
  public List<UUID> getMyEntryIds(
      @AuthenticationPrincipal Jwt jwt, @ParameterObject @ModelAttribute LagerFilter filter) {
    filter.requireMatchingCatalog();
    UUID userId = userService.getUserIdFromJwt(jwt);
    if (filter.catalog() == InventoryCatalog.ITEM) {
      return inventoryAggregationService.getMyItemEntryIds(userId, filter);
    }
    return inventoryItemService.getMyEntryIds(userId, filter);
  }

  /**
   * Squadron-wide flat inventory list (admin/logistician view). A filter that does not match the
   * catalog is rejected with 400 (REQ-INV-029/031); the personal and stolen flags and the item
   * attribute lists do not apply to this list.
   *
   * @param filter the Lager filters bound from the query string
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec; {@code null} falls back to the per-catalog default
   * @return paged inventory items
   */
  @GetMapping("/all")
  @PreAuthorize(INVENTORY_ACCESS)
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getAllInventory(
      @ParameterObject @ModelAttribute LagerFilter filter,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    filter.withoutAttributes().requireMatchingCatalog();
    if (filter.catalog() == InventoryCatalog.ITEM) {
      Pageable pageable =
          PaginationUtil.createPageRequest(
              page,
              size,
              sort != null ? sort : ITEM_FLAT_DEFAULT_SORT,
              ITEM_FLAT_SORT_FIELDS,
              "gameItem.name");
      return PageResponse.of(inventoryAggregationService.getAllItemInventory(filter, pageable));
    }
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort != null ? sort : MATERIAL_FLAT_DEFAULT_SORT,
            MATERIAL_FLAT_SORT_FIELDS,
            "quality");
    return PageResponse.of(inventoryItemService.getAllInventory(filter, pageable));
  }

  /**
   * Lists every inventory item linked to a mission, for the mission-detail "Lagereinträge" table.
   * Visible to every member, not org-unit-scoped.
   *
   * @param missionId the mission whose linked inventory to list
   * @return the mission's inventory items
   */
  @GetMapping("/allocations")
  @PreAuthorize(INVENTORY_ACCESS)
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getMissionInventory(@RequestParam @NotNull UUID missionId) {
    return inventoryItemService.getMissionInventory(missionId);
  }

  /**
   * Squadron-wide variant of {@link #getMyGroupedInventory}. A filter that does not match the
   * catalog is rejected with 400 (REQ-INV-029/031); the personal flags do not apply squadron-wide.
   *
   * @param filter the Lager filters bound from the query string
   * @return grouped DTOs
   */
  @GetMapping("/all/grouped")
  @PreAuthorize(INVENTORY_ACCESS)
  @Transactional(readOnly = true)
  public List<GroupedInventoryDto> getAllGroupedInventory(
      @ParameterObject @ModelAttribute LagerFilter filter) {
    filter.requireMatchingCatalog();
    if (filter.catalog() == InventoryCatalog.ITEM) {
      return inventoryAggregationService.getAllAggregatedItemInventory(filter);
    }
    return inventoryItemService.getAllAggregatedInventory(filter);
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
      @RequestParam(required = false, defaultValue = "false") Boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    if (catalog == InventoryCatalog.ITEM) {
      CatalogFilterGuard.requireItemStackKey(gameItemId, quality);
      return PageResponse.of(
          inventoryAggregationService.getMyItemStackEntries(
              userService.getUserIdFromJwt(jwt),
              gameItemId,
              locationId,
              personal,
              stolen,
              owningOrgUnitId,
              stackEntriesPageRequest(page, size)));
    }
    CatalogFilterGuard.requireMaterialStackKey(materialId);
    Page<InventoryItemDto> p =
        inventoryItemService.getMyStackEntries(
            userService.getUserIdFromJwt(jwt),
            materialId,
            locationId,
            quality,
            personal,
            stolen,
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
  @PreAuthorize(INVENTORY_ACCESS)
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getAllStackEntries(
      @RequestParam(required = false) UUID materialId,
      @RequestParam(required = false) UUID gameItemId,
      @RequestParam @NotNull UUID userId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false, defaultValue = "false") Boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false, defaultValue = "MATERIAL") InventoryCatalog catalog,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    if (catalog == InventoryCatalog.ITEM) {
      CatalogFilterGuard.requireItemStackKey(gameItemId, quality);
      return PageResponse.of(
          inventoryAggregationService.getAllItemStackEntries(
              gameItemId,
              userId,
              locationId,
              stolen,
              owningOrgUnitId,
              stackEntriesPageRequest(page, size)));
    }
    CatalogFilterGuard.requireMaterialStackKey(materialId);
    Page<InventoryItemDto> p =
        inventoryItemService.getAllStackEntries(
            materialId,
            userId,
            locationId,
            quality,
            stolen,
            owningOrgUnitId,
            stackEntriesPageRequest(page, size));
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
   * Answers whether an Einbuchen of a material with this stock identity would find an existing row
   * to merge with, so the form offers the {@code SCU} merge opt-in only then (REQ-INV-026). Probing
   * for another member requires the same scope as booking for them (REQ-SEC-005).
   *
   * @param jwt the caller's token
   * @param userId the member booked for, or {@code null} for the caller
   * @param materialId the material being booked in
   * @param locationId the target location
   * @param quality the quality grade
   * @param personal whether the row would be personal
   * @param stolen whether the row would be marked „gestohlen"
   * @param owningOrgUnitId the picked owning org unit, or {@code null} for the auto-stamp
   * @return whether a mergeable row exists
   */
  @GetMapping("/merge-candidates")
  @PreAuthorize(INVENTORY_ACCESS)
  @Operation(
      summary = "Probe for mergeable stock",
      description =
          "Returns whether a book-in of the given material stock identity (owner, material,"
              + " location, quality, personal, stolen, resolved owning org unit) would find an"
              + " existing row to merge with. Rows backing a Materialbörse offer do not count.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Probe answered"),
    @ApiResponse(responseCode = "403", description = "Not allowed to book for that member")
  })
  public InventoryMergeCandidatesDto getMergeCandidates(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID userId,
      @RequestParam UUID materialId,
      @RequestParam UUID locationId,
      @RequestParam int quality,
      @RequestParam(required = false, defaultValue = "false") boolean personal,
      @RequestParam(required = false, defaultValue = "false") boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId) {
    return new InventoryMergeCandidatesDto(
        inventoryItemService.hasMergeCandidates(
            userId,
            userService.getUserIdFromJwt(jwt),
            materialId,
            locationId,
            quality,
            personal,
            stolen,
            owningOrgUnitId));
  }

  /**
   * Creates an inventory item. Booking for another member requires shared editable org-unit scope
   * with that receiver, checked in the service (REQ-SEC-005).
   *
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize(INVENTORY_ACCESS)
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
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public ResponseEntity<InventoryItemDto> bookOutInventoryItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemBookOutDto dto) {
    boolean isLogistician = inventoryAccessPolicy.isLogisticianOfItem(id);
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
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto rebookPersonal(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemPersonalRebookDto dto) {
    boolean isLogistician = inventoryAccessPolicy.isLogisticianOfItem(id);
    return inventoryItemService.rebookPersonal(
        id, dto, userService.getUserIdFromJwt(jwt), isLogistician);
  }

  /**
   * Sets, updates or removes the free-text note of an inventory item. Owner may always modify their
   * own note; non-owners require LOGISTICIAN (or higher via role hierarchy) role. Optimistic
   * locking is enforced via the {@code version} field in the request.
   */
  @PutMapping("/{id}/note")
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto updateInventoryItemNote(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemNoteUpdateRequest request) {
    boolean isLogistician = inventoryAccessPolicy.isLogisticianOfItem(id);
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
  @PreAuthorize(INVENTORY_ACCESS)
  public void bulkCheckout(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BulkCheckoutRequest request) {
    inventoryItemService.bulkCheckout(request, userService.getUserIdFromJwt(jwt));
  }

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
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto changeOrgUnit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemOrgUnitChangeDto dto) {
    return inventoryOrgUnitChangeService.changeOrgUnit(id, dto, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Moves a shared row, or a part of it, into the pool of another org unit its holder directly
   * belongs to (REQ-INV-059), for a Logistician of both units.
   *
   * @param jwt the caller's token
   * @param id the row
   * @param dto the version, the target unit, the part and the merge opt-in
   * @return the row that holds the moved stock, or the stack row it was merged into
   */
  @Operation(
      summary = "Move a shared row into another pool",
      description =
          "Moves a shared row, or a part split off its free stock, into the pool of another org"
              + " unit the holder directly belongs to. The caller must be a Logistician of both"
              + " units. Merges per the stock-merge rules, notifies the holder when somebody else"
              + " moved it, and applies optimistic locking via the version field.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Moved; the resulting row is returned"),
    @ApiResponse(
        responseCode = "400",
        description = "A personal row, a holder outside the unit, or an invalid part"),
    @ApiResponse(responseCode = "403", description = "Not a Logistician of both units"),
    @ApiResponse(responseCode = "404", description = "Inventory item or org unit not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Optimistic locking conflict, or the rest would fall below its offer"),
    @ApiResponse(responseCode = "422", description = "The rest could not carry its earmarks")
  })
  @PostMapping("/{id}/pool")
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto changePool(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemPoolChangeDto dto) {
    return inventoryPoolChangeService.changePool(id, dto, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Lists the pools the caller may move a shared row into (REQ-INV-059).
   *
   * @param id the row
   * @return the holder's other units in which the caller is a Logistician, by name
   */
  @Operation(
      summary = "List the pools a shared row may move into",
      description =
          "The org units the row's holder directly belongs to, other than the row's own, in which"
              + " the caller is a Logistician; empty for a personal row or when the caller is no"
              + " Logistician of the row's pool.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The units"),
    @ApiResponse(responseCode = "403", description = "Access denied"),
    @ApiResponse(responseCode = "404", description = "Inventory item not found")
  })
  @GetMapping("/{id}/pool-targets")
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public List<OrgUnitReferenceDto> poolTargets(@PathVariable @NotNull UUID id) {
    return inventoryPoolChangeService.poolTargets(id);
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
  @PreAuthorize(INVENTORY_ACCESS)
  public BulkOrgUnitChangeResultDto bulkChangeOrgUnit(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BulkOrgUnitChangeRequest request) {
    return inventoryOrgUnitChangeService.bulkChangeOrgUnit(
        request, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Sets or removes the „gestohlen" marker on a row or on a part of it (REQ-INV-053). A part is
   * split off as a new row; it and a flipped whole row merge into a stack of the new identity where
   * the merge rules allow. The row's owner and logisticians or above may change its marker.
   *
   * @param jwt the caller's token
   * @param id the row
   * @param dto the version, the requested marker and the amount, {@code null} for the whole row
   * @return the row carrying the requested marker, or the stack row it was merged into
   */
  @Operation(
      summary = "Mark or unmark stock as stolen",
      description =
          "Sets or removes the „gestohlen\" marker on a whole row or, with an amount, on a part"
              + " split off as a new row. Refused while marking is switched off, and when a split"
              + " would leave the row below the amount it offers on the Materialbörse.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Changed; the resulting row is returned"),
    @ApiResponse(
        responseCode = "400",
        description = "Amount not positive, larger than the row, or fractional on whole units"),
    @ApiResponse(
        responseCode = "403",
        description = "Access denied - another member's row and not a logistician"),
    @ApiResponse(responseCode = "404", description = "Inventory item not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Marking switched off, below the offered amount, or a stale version"),
    @ApiResponse(responseCode = "422", description = "The rest could not carry its earmarks")
  })
  @PostMapping("/{id}/stolen")
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto markStolen(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemStolenMarkDto dto) {
    return inventoryStolenMarkService.mark(
        id, dto, userService.getUserIdFromJwt(jwt), inventoryAccessPolicy.isLogisticianOfItem(id));
  }

  /**
   * Sets or removes the „gestohlen" marker on a selection of the caller's own rows, whole rows only
   * (REQ-INV-053); rows already carrying it are skipped and counted.
   *
   * @param jwt the caller's token
   * @param request the selection and the requested marker
   * @return the changed and skipped counts
   */
  @Operation(
      summary = "Bulk mark or unmark stock as stolen",
      description =
          "Sets or removes the „gestohlen\" marker on every listed row of the caller. Rows already"
              + " carrying it are skipped; an unknown id or a foreign row aborts the whole action.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Changed; changed/skipped counts returned"),
    @ApiResponse(responseCode = "400", description = "Empty list"),
    @ApiResponse(responseCode = "403", description = "Access denied - row of another member"),
    @ApiResponse(responseCode = "404", description = "One or more rows not found"),
    @ApiResponse(responseCode = "409", description = "Marking switched off")
  })
  @PostMapping("/bulk-stolen")
  @PreAuthorize(INVENTORY_ACCESS)
  public BulkStolenMarkResultDto bulkMarkStolen(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BulkStolenMarkRequest request) {
    return inventoryStolenMarkService.bulkMark(request, userService.getUserIdFromJwt(jwt));
  }

  /**
   * Rebooks a list of the caller's own inventory rows in one transaction (Massen-Umbuchen,
   * REQ-INV-036) — to another location/owner or across the personal marker. Every row moves in
   * full. Owner-scoped from the JWT like {@link #bulkCheckout}: the caller can only ever move their
   * own stock, so no logistician escalation applies.
   *
   * @return how many rows were moved and how many were skipped as already-at-target
   */
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
  @PreAuthorize(INVENTORY_ACCESS)
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
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto updateDelivered(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid UpdateDeliveredRequest request) {
    boolean isLogistician = inventoryAccessPolicy.isLogisticianOfItem(id);
    return inventoryItemService.updateDelivered(
        id, request, userService.getUserIdFromJwt(jwt), isLogistician);
  }

  /**
   * Adds a quantity slice earmarking part of an entry to a job order or mission (Variante C,
   * REQ-INV-027). One entry may carry several slices per dimension, each with its own amount, split
   * independently of the other dimension. The request echoes the entry's {@code version}; the write
   * force-increments it so the response carries the version the client must echo next.
   *
   * @param jwt the caller's token
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
    @ApiResponse(
        responseCode = "403",
        description = "Access denied - another member's entry and not a logistician"),
    @ApiResponse(responseCode = "404", description = "Entry, job order or mission not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict"),
    @ApiResponse(responseCode = "422", description = "Over-allocation (dimension Σ exceeds amount)")
  })
  @PostMapping("/{id}/allocation")
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto addAllocation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryAllocationWriteDto dto) {
    return inventoryItemService.addAllocation(
        id, dto, userService.getUserIdFromJwt(jwt), inventoryAccessPolicy.isLogisticianOfItem(id));
  }

  /**
   * Changes the amount of an existing quantity slice (Variante C, REQ-INV-027). Same version echo /
   * force-increment as {@link #addAllocation}.
   *
   * @param jwt the caller's token
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
    @ApiResponse(
        responseCode = "403",
        description = "Access denied - another member's entry and not a logistician"),
    @ApiResponse(responseCode = "404", description = "Entry or allocation not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict"),
    @ApiResponse(responseCode = "422", description = "Over-allocation (dimension Σ exceeds amount)")
  })
  @PatchMapping("/{id}/allocation")
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto changeAllocation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryAllocationWriteDto dto) {
    return inventoryItemService.changeAllocation(
        id, dto, userService.getUserIdFromJwt(jwt), inventoryAccessPolicy.isLogisticianOfItem(id));
  }

  /**
   * Removes a quantity slice, releasing its amount back to the entry's unallocated remainder
   * (Variante C, REQ-INV-027). The {@code amount} field of the payload is ignored. Same version
   * echo / force-increment as {@link #addAllocation}.
   *
   * @param jwt the caller's token
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
    @ApiResponse(
        responseCode = "403",
        description = "Access denied - another member's entry and not a logistician"),
    @ApiResponse(responseCode = "404", description = "Entry or allocation not found"),
    @ApiResponse(responseCode = "409", description = "Optimistic locking conflict")
  })
  @DeleteMapping("/{id}/allocation")
  @PreAuthorize("isAuthenticated() and @inventoryAccessPolicy.canEditInventoryItem(#id)")
  public InventoryItemDto removeAllocation(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryAllocationWriteDto dto) {
    return inventoryItemService.removeAllocation(
        id, dto, userService.getUserIdFromJwt(jwt), inventoryAccessPolicy.isLogisticianOfItem(id));
  }

  /**
   * Admin-only: removes every non-personal inventory item ("globales Lager leeren"). Personal
   * entries are deliberately left untouched. Gated by {@code hasRole('ADMIN')} so neither an
   * officer nor a {@code LOGISTICIAN} can trigger the squadron-wide wipe.
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
