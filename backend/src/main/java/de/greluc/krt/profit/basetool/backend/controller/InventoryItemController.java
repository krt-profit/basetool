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

import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
 * REST surface for inventory items — covers the aggregated/grouped read variants used by the
 * inventory page, the user-scoped {@code /my-inventory} subset, the admin-wide {@code /all}, the
 * create / update / note-only update endpoints, the book-out flow and the bulk-checkout.
 *
 * <p>Owner-vs-logistician decisions happen at the HTTP boundary via {@link
 * AuthHelperService#isLogisticianOrAbove()} and are passed as a boolean to the service — the
 * service stays free of {@code SecurityContextHolder} reads (ArchUnit rule).
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryItemController {

  /** Default page size for a stack-entries drill-down when the caller does not specify one. */
  private static final int STACK_ENTRIES_DEFAULT_SIZE = 20;

  /** Upper bound on a stack-entries page size, clamping the per-request load. */
  private static final int STACK_ENTRIES_MAX_SIZE = 100;

  private final InventoryItemService inventoryItemService;
  private final UserService userService;
  private final AuthHelperService authHelperService;

  /**
   * Aggregated per-material inventory view. Default sort favors material name, then quality
   * descending, then amount — the order operators actually want.
   *
   * @return paged aggregated DTOs
   */
  @GetMapping("/aggregated")
  @Transactional(readOnly = true)
  public PageResponse<AggregatedInventoryDto> getAggregatedInventory(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false, defaultValue = "material.name,asc;quality,desc;amount,desc")
          String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("amount", "quality", "material.name"), "material.name");
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
   * Calling user's own inventory items. Owner id derived from the JWT — no impersonation.
   *
   * @return paged inventory items
   */
  @GetMapping("/my-inventory")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getMyInventory(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false, defaultValue = "material.name,asc;quality,desc;amount,desc")
          String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("amount", "quality", "id", "material.name"), "quality");
    Page<InventoryItemDto> p =
        inventoryItemService.getUserInventory(userService.getUserIdFromJwt(jwt), pageable);
    return PageResponse.of(p);
  }

  /**
   * Calling user's inventory grouped by material with totals/average-quality — drives the "personal
   * inventory" page's outer rows.
   *
   * @param personalOnly when {@code true}, narrows the result to the caller's private stock ({@code
   *     personal = true} rows) — the "Mein Lager" personal-entries-only filter; defaults to {@code
   *     false} (both shared and personal stacks)
   * @param nonPersonalOnly when {@code true}, narrows the result to the caller's shared stock
   *     ({@code personal = false} rows) — the "Mein Lager" non-personal-entries-only filter;
   *     defaults to {@code false} and is mutually exclusive with {@code personalOnly}
   * @return grouped DTOs
   */
  @GetMapping("/my-inventory/grouped")
  @Transactional(readOnly = true)
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getMyGroupedInventory(
          @AuthenticationPrincipal Jwt jwt,
          @RequestParam(required = false) List<UUID> materialIds,
          @RequestParam(required = false) Integer minQuality,
          @RequestParam(required = false) List<UUID> jobOrderIds,
          @RequestParam(required = false) List<UUID> missionIds,
          @RequestParam(required = false, defaultValue = "false") boolean personalOnly,
          @RequestParam(required = false, defaultValue = "false") boolean nonPersonalOnly) {
    return inventoryItemService.getMyAggregatedInventory(
        userService.getUserIdFromJwt(jwt),
        materialIds,
        minQuality,
        jobOrderIds,
        missionIds,
        personalOnly,
        nonPersonalOnly);
  }

  /**
   * Squadron-wide flat inventory list (admin/logistician view).
   *
   * @return paged inventory items
   */
  @GetMapping("/all")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getAllInventory(
      @RequestParam(required = false) List<UUID> materialIds,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) List<UUID> jobOrderIds,
      @RequestParam(required = false) List<UUID> missionIds,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false, defaultValue = "material.name,asc;quality,desc;amount,desc")
          String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("amount", "quality", "id", "material.name"), "quality");
    Page<InventoryItemDto> p =
        inventoryItemService.getAllInventory(
            materialIds, minQuality, jobOrderIds, missionIds, pageable);
    return PageResponse.of(p);
  }

  /**
   * Lists every inventory item linked to a mission — the mission-detail Wirtschaft "Lagereinträge"
   * table (#1138). Replaces the former eagerly embedded {@code MissionDto.inventoryEntries} field
   * with a dedicated read so the hottest mission GET no longer drags an unbounded list through its
   * payload. Member-visible: the {@code /api/v1/inventory/**} security rule already requires a
   * member role, so guests never reach it (matching the removed field, which was cleared for
   * guests). Deliberately unscoped among members — the shared mission-stockpile view, reproducing
   * the removed field's behaviour exactly.
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
   * Squadron-wide grouped variant — same shape as {@link #getMyGroupedInventory} but scoped to all
   * users.
   *
   * @return grouped DTOs
   */
  @GetMapping("/all/grouped")
  @Transactional(readOnly = true)
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getAllGroupedInventory(
          @RequestParam(required = false) List<UUID> materialIds,
          @RequestParam(required = false) Integer minQuality,
          @RequestParam(required = false) List<UUID> jobOrderIds,
          @RequestParam(required = false) List<UUID> missionIds) {
    return inventoryItemService.getAllAggregatedInventory(
        materialIds, minQuality, jobOrderIds, missionIds);
  }

  /**
   * Lazily loads one of the caller's own stacks' entries, oldest-first and paginated — the
   * drill-down behind a collapsed stack on the "my inventory" page. The stack is identified by the
   * stock-identity query params the grouped view already exposes on each {@code InventoryStackDto};
   * {@code null} job-order / mission / owning-org-unit params select the rows where that
   * association is itself absent. Owner-scoped to the calling user (no impersonation). Append-only
   * inventory grows unboundedly per stack, so this is the only path that materialises the
   * individual entries.
   *
   * @return one page of the stack's entries, oldest-first
   */
  @GetMapping("/my-inventory/stack/entries")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getMyStackEntries(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @NotNull UUID materialId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false) UUID jobOrderId,
      @RequestParam(required = false) UUID missionId,
      @RequestParam(required = false, defaultValue = "false") Boolean personal,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    Page<InventoryItemDto> p =
        inventoryItemService.getMyStackEntries(
            userService.getUserIdFromJwt(jwt),
            materialId,
            locationId,
            quality,
            jobOrderId,
            missionId,
            personal,
            owningOrgUnitId,
            stackEntriesPageRequest(page, size));
    return PageResponse.of(p);
  }

  /**
   * Squadron-wide variant of {@link #getMyStackEntries} — the drill-down behind a collapsed stack
   * on the admin/logistician "global Lager" page. Includes the stack's owning {@code userId}
   * because a global stack is per-owner; the service re-applies the same org-unit scope predicate
   * as the grouped view, so the drill-down can never widen visibility beyond the caller's org-unit
   * slice.
   *
   * @return one page of the stack's entries, oldest-first
   */
  @GetMapping("/all/stack/entries")
  @Transactional(readOnly = true)
  public PageResponse<InventoryItemDto> getAllStackEntries(
      @RequestParam @NotNull UUID materialId,
      @RequestParam @NotNull UUID userId,
      @RequestParam @NotNull UUID locationId,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false) UUID jobOrderId,
      @RequestParam(required = false) UUID missionId,
      @RequestParam(required = false) UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    Page<InventoryItemDto> p =
        inventoryItemService.getAllStackEntries(
            materialId,
            userId,
            locationId,
            quality,
            jobOrderId,
            missionId,
            owningOrgUnitId,
            stackEntriesPageRequest(page, size));
    return PageResponse.of(p);
  }

  /**
   * Builds the page request for a stack-entries drill-down: a clamped page/size with no sort, so
   * the repository's oldest-first {@code ORDER BY createdAt ASC} (the REQ-INV-002 contract) is the
   * sole ordering. Defaults to the first page of {@code STACK_ENTRIES_DEFAULT_SIZE} and clamps the
   * size to {@code STACK_ENTRIES_MAX_SIZE} to bound the per-request load.
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
    return org.springframework.data.domain.PageRequest.of(resolvedPage, resolvedSize);
  }

  /**
   * Creates an inventory item. Logistician role lets the caller set an arbitrary owner; everyone
   * else gets the calling user as owner.
   *
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  public InventoryItemDto createInventoryItem(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid InventoryItemCreateDto dto) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    return inventoryItemService.createInventoryItem(
        dto, userService.getUserIdFromJwt(jwt), isLogistician);
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
  public org.springframework.http.ResponseEntity<InventoryItemDto> bookOutInventoryItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemBookOutDto dto) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    InventoryItemDto result =
        inventoryItemService.bookOutInventoryItem(
            id, dto, userService.getUserIdFromJwt(jwt), isLogistician);
    if (result == null) {
      return org.springframework.http.ResponseEntity.noContent().build();
    }
    return org.springframework.http.ResponseEntity.ok(result);
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
   * Removes a list of inventory items in one transaction. Same concurrency pattern as {@link
   * #bookOutInventoryItem} — collected ids run through a single bulk-update after the loop instead
   * of one bulk-update per loop iteration.
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
   * Updates the soft associations of an inventory item (mission, job order, owner). Quantity and
   * material identity go through {@link #bookOutInventoryItem} instead.
   *
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)")
  public InventoryItemDto updateInventoryItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid InventoryItemUpdateDto dto) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    return inventoryItemService.updateInventoryItem(
        id, dto, userService.getUserIdFromJwt(jwt), isLogistician);
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
  public org.springframework.http.ResponseEntity<Void> deleteAllGlobalInventory() {
    inventoryItemService.deleteAllGlobalInventory();
    return org.springframework.http.ResponseEntity.noContent().build();
  }
}
