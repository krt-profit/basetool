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

import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
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
 * REST controller for the material collection overview of a job order. Provides a sorted list of
 * all inventory items linked to a specific job order. The per-entry owner/location is redacted for
 * a requesting-side viewer of an SK-public order (REQ-ORDERS-029, ADR-0107) via {@link
 * JobOrderInventoryOwnerRedactor}, gated on {@code canSeeJobOrderInventoryOwners}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class MaterialCollectionController {

  private final InventoryItemService inventoryItemService;
  private final OwnerScopeService ownerScopeService;
  private final JobOrderInventoryOwnerRedactor inventoryOwnerRedactor;

  /**
   * Returns the inventory contributions linked to the given job order. The per-entry owner and
   * location are blanked when the caller is not entitled to the order's responsible side ({@code
   * canSeeJobOrderInventoryOwners} is {@code false} — a requesting-side viewer of an SK-public
   * order, REQ-ORDERS-029); material, quality, quantities and the delivered marker are always kept.
   *
   * @param jobOrderId job order id
   * @return inventory entries sorted by owner / location / material / quality / quantity, with
   *     owner/location redacted for requesting-side viewers
   */
  @Operation(
      summary = "Get material collection for a job order",
      description =
          "Returns all inventory items linked to the given job order, sorted by owner name,"
              + " location, material name, quality (desc), quantity (desc).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Material collection returned successfully"),
    @ApiResponse(
        responseCode = "403",
        description = "Access denied – not allowed to see this job order"),
    @ApiResponse(responseCode = "404", description = "Job order not found")
  })
  @GetMapping("/{jobOrderId}/material-collection")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#jobOrderId)")
  public List<MaterialCollectionEntryDto> getMaterialCollection(
      @PathVariable @NotNull UUID jobOrderId) {
    List<MaterialCollectionEntryDto> entries =
        inventoryItemService.getMaterialCollection(jobOrderId);
    return ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)
        ? entries
        : inventoryOwnerRedactor.redactMaterialCollection(entries);
  }
}
