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
 * MaterialCollectionController}. Same authorization as the sibling per-order stock reads (the
 * caller must be able to see the order).
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class JobOrderItemStockController {

  private final InventoryItemService inventoryItemService;

  /**
   * Returns the game-item stock earmarked to the given job order, grouped per game item.
   *
   * @param jobOrderId job order id
   * @return name-sorted game-item groups with per-entry owner, location, whole-unit amounts,
   *     this-order slice, delivered marker and entry version
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
    return inventoryItemService.getItemStockForJobOrder(jobOrderId);
  }
}
