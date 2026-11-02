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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.LinkedStockAttributionDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderInventoryOwnerRedactor;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemBlueprintOwnersService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMaterialDemandService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMissionAllocationRedactor;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over the materials of a job order and the stock linked to them: the cross-order
 * material demand, the blueprint coverage of an item order, the linked-stock reads and the unlink
 * operations.
 *
 * <p>Reads are limited to the caller's visibility of the order; unlinking needs LOGISTICIAN or
 * ADMIN.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Operations related to job orders")
public class JobOrderMaterialController {
  private final JobOrderService jobOrderService;
  private final JobOrderQueryService jobOrderQueryService;
  private final JobOrderMaterialDemandService jobOrderMaterialDemandService;
  private final JobOrderAccessPolicy jobOrderAccessPolicy;
  private final JobOrderItemBlueprintOwnersService jobOrderItemBlueprintOwnersService;
  private final JobOrderInventoryOwnerRedactor inventoryOwnerRedactor;
  private final JobOrderMissionAllocationRedactor missionAllocationRedactor;

  /**
   * Aggregates the outstanding material of every visible {@code OPEN} / {@code IN_PROGRESS} job
   * order into one row per responsible org unit, material and quality (REQ-ORDERS-034).
   *
   * <p>Unpaged, since paging would truncate the sums (ADR-0104).
   *
   * @return the aggregated demand, empty when the caller may see no non-terminal order
   */
  @GetMapping("/material-demand")
  @Operation(
      summary = "Get the cross-order material demand",
      description =
          "Returns the material still to be gathered across all open / in-progress job orders"
              + " visible to the caller, grouped by responsible org unit.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MaterialDemandOverviewDto getMaterialDemand() {
    return jobOrderMaterialDemandService.getMaterialDemandOverview();
  }

  /**
   * Shows which members of the order's responsible org unit own the blueprints for the ordered
   * items. Visible only to members of that unit and admins; empty for {@code MATERIAL} orders.
   *
   * @param id job-order id
   * @return the blueprint-coverage view (required products with owner counts + owning members)
   */
  @GetMapping("/{id}/item-blueprint-owners")
  @Operation(
      summary = "Get item-order blueprint coverage",
      description =
          "Returns which members of the responsible squadron/SK own the blueprints for the"
              + " order's required items. Members of the responsible org unit only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Blueprint coverage for the item order."),
    @ApiResponse(responseCode = "401", description = "Authentication required."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is not a member of the order's responsible squadron/SK."),
    @ApiResponse(responseCode = "404", description = "Job order not found.")
  })
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrderBlueprintOwners(#id)")
  @Transactional(readOnly = true)
  public JobOrderItemBlueprintOwnersDto getItemBlueprintOwners(@PathVariable UUID id) {
    return jobOrderItemBlueprintOwnersService.getBlueprintOwners(id);
  }

  /**
   * Returns every inventory item linked to a specific material of a job order. Drives the
   * per-material drill-down in the order detail view. The owner/location of each item is blanked
   * for a requesting-side viewer of an SK-public order ({@code canSeeJobOrderInventoryOwners} is
   * {@code false}, REQ-ORDERS-029 / ADR-0107); mission slices of missions the caller may not read
   * are anonymised for every viewer.
   *
   * @param id job-order id
   * @param matId material id
   * @return inventory-item DTOs, owner/location redacted for requesting-side viewers
   */
  @GetMapping("/{id}/materials/{matId}/inventory")
  @Operation(
      summary = "Get inventory items for a job order material",
      description = "Returns all inventory items linked to a specific material in a job order.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getInventoryItemsForJobOrderMaterial(
      @PathVariable UUID id, @PathVariable UUID matId) {
    return redactLinkedStock(
        id, jobOrderQueryService.getInventoryItemsForJobOrderMaterial(id, matId));
  }

  /**
   * Reports how much of each stock row linked to one material of the order counts toward which
   * quality bucket, so every row counts exactly once (REQ-ORDERS-037). Carries row ids, tier ids
   * and amounts only — no owner or location.
   *
   * @param id job-order id
   * @param matId material id
   * @return one entry per row and bucket, plus an entry without a bucket for stock no bucket
   *     accepts
   */
  @GetMapping("/{id}/materials/{matId}/attribution")
  @Operation(
      summary = "Get the quality-bucket attribution of a job order material's linked stock",
      description =
          "Returns, per linked inventory row, the amount counted toward each quality bucket.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  @Transactional(readOnly = true)
  public List<LinkedStockAttributionDto> getStockAttributionForJobOrderMaterial(
      @PathVariable UUID id, @PathVariable UUID matId) {
    return jobOrderQueryService.getStockAttributionForJobOrderMaterial(id, matId);
  }

  /**
   * Returns the inventory items linked to the order whose material the order does not require —
   * "orphaned" links surfaced as a warning on the order detail (REQ-ORDERS-019). Such links bind
   * stock to the order while staying invisible in every material row. The owner/location of each
   * item is blanked for a requesting-side viewer of an SK-public order ({@code
   * canSeeJobOrderInventoryOwners} is {@code false}, REQ-ORDERS-029 / ADR-0107).
   *
   * @param id job-order id
   * @return orphaned inventory-item DTOs (empty when every linked item matches a requirement),
   *     owner/location redacted for requesting-side viewers
   */
  @GetMapping("/{id}/allocations/orphaned")
  @Operation(
      summary = "Get orphaned linked inventory for a job order",
      description =
          "Returns inventory items linked to the order whose material is not among the order's"
              + " requirements (invisible orphaned links).")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getOrphanedLinkedInventory(@PathVariable UUID id) {
    return redactLinkedStock(id, jobOrderQueryService.getOrphanedLinkedInventory(id));
  }

  /**
   * Applies the two redactions of an order's linked stock: the mission slices of missions the
   * caller may not read are anonymised for everyone, owner and location are blanked for a
   * requesting-side viewer.
   *
   * @param id job-order id
   * @param items the linked-stock rows
   * @return the redacted rows
   */
  private List<InventoryItemDto> redactLinkedStock(UUID id, List<InventoryItemDto> items) {
    List<InventoryItemDto> missionSafe =
        missionAllocationRedactor.anonymiseUnreadableMissions(items);
    return jobOrderAccessPolicy.canSeeJobOrderInventoryOwners(id)
        ? missionSafe
        : inventoryOwnerRedactor.redactInventoryItems(missionSafe);
  }

  /**
   * Removes a material from the order and atomically unlinks every inventory item that pointed at
   * it.
   *
   * @param jobOrderId job-order id
   * @param materialId material id
   */
  @DeleteMapping("/{jobOrderId}/materials/{materialId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Unlink a material from a job order",
      description =
          "Removes the link between a material and a job order, and unlinks all associated"
              + " inventory items.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Material successfully unlinked"),
    @ApiResponse(responseCode = "403", description = "Forbidden – insufficient role"),
    @ApiResponse(responseCode = "404", description = "Job order or material not found")
  })
  @PreAuthorize("hasAnyRole('" + Roles.LOGISTICIAN + "', '" + Roles.ADMIN + "')")
  public void unlinkMaterial(@PathVariable UUID jobOrderId, @PathVariable UUID materialId) {
    jobOrderService.unlinkMaterial(jobOrderId, materialId);
  }

  /**
   * Removes a single inventory item from the order — sets {@code jobOrderId=null} via Hibernate
   * dirty-checking (no bulk update, so the surrounding aggregate stays managed).
   *
   * @param id job-order id
   * @param inventoryItemId inventory-item id
   */
  @DeleteMapping("/{id}/allocations/{inventoryItemId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Unlink a single inventory item from a job order",
      description =
          "Removes the link between a single inventory item and a job order by setting jobOrderId"
              + " to null. Uses Hibernate dirty-checking (no bulk update).")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Inventory item successfully unlinked"),
    @ApiResponse(responseCode = "403", description = "Forbidden – insufficient role"),
    @ApiResponse(responseCode = "404", description = "Job order or inventory item not found")
  })
  @PreAuthorize("hasAnyRole('" + Roles.LOGISTICIAN + "', '" + Roles.ADMIN + "')")
  public void unlinkInventoryItem(@PathVariable UUID id, @PathVariable UUID inventoryItemId) {
    jobOrderService.unlinkInventoryItem(id, inventoryItemId);
  }
}
