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

import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.JobOrderInventoryOwnerRedactor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the order-detail Item-Bestand panel (REQ-ORDERS-028): lists the game-item
 * stock earmarked to a job order, grouped per game item — the item sibling of {@link
 * MaterialCollectionController}. Reading the panel requires being able to see the order ({@code
 * canSeeJobOrder}); the per-entry owner/location is additionally redacted for a requesting-side
 * viewer of an SK-public order (REQ-ORDERS-029, ADR-0106) via {@link
 * JobOrderInventoryOwnerRedactor}, gated on {@code canSeeJobOrderInventoryOwners}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class JobOrderItemStockController {

  private final InventoryItemService inventoryItemService;
  private final OwnerScopeService ownerScopeService;
  private final JobOrderInventoryOwnerRedactor inventoryOwnerRedactor;

  /**
   * Returns the game-item stock earmarked to the given job order, grouped per game item. The
   * per-entry owner and location are blanked when the caller is not entitled to the order's
   * responsible side ({@code canSeeJobOrderInventoryOwners} is {@code false} — a requesting-side
   * viewer of an SK-public order, REQ-ORDERS-029); amounts and the delivered marker are always
   * kept.
   *
   * @param jobOrderId job order id
   * @return name-sorted game-item groups with per-entry owner (redacted for requesting-side
   *     viewers), location, whole-unit amounts, this-order slice, delivered marker and entry
   *     version
   */
  @Operation(
      summary = "Get the earmarked item stock for a job order",
      description =
          "Returns the game-item inventory rows earmarked to the given job order, grouped per game"
              + " item (name-sorted) with per-entry owner, location, whole-unit stock, the order's"
              + " own earmark slice, its delivered marker and the entry version.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Item stock returned successfully"),
    @ApiResponse(
        responseCode = "403",
        description = "Access denied – not allowed to see this job order"),
    @ApiResponse(responseCode = "404", description = "Job order not found")
  })
  @GetMapping("/{jobOrderId}/item-stock")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#jobOrderId)")
  public List<JobOrderItemStockGroupDto> getItemStock(@PathVariable @NotNull UUID jobOrderId) {
    List<JobOrderItemStockGroupDto> groups =
        inventoryItemService.getItemStockForJobOrder(jobOrderId);
    return ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)
        ? groups
        : inventoryOwnerRedactor.redactItemStockGroups(groups);
  }
}
