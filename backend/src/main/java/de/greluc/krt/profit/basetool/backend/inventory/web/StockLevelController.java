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

import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelCreateRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelUpdateRequest;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitReferenceDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the minimum stock levels of the units' pools (REQ-INV-068): reads for everyone
 * who sees the pool, writes for the unit's Logisticians, the lead included.
 */
@RestController
@RequestMapping("/api/v1/inventory/stock-levels")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class StockLevelController {

  private final StockLevelService stockLevelService;

  /**
   * Lists the levels in the caller's Lager scope with their figures.
   *
   * @param orgUnitId only this unit, or {@code null} for every unit in scope
   * @param lowOnly whether to return only the low levels, emptiest first
   * @param limit the most levels to return, or {@code null} for all
   * @return the levels, by unit, material and tier floor descending unless {@code lowOnly}
   */
  @Operation(
      summary = "List minimum stock levels",
      description =
          "Lists the minimum stock levels of the active units in the caller's Lager scope with"
              + " their allocated free stock, shortfall, fill ratio and low flag. With lowOnly it"
              + " returns the low levels sorted by fill ratio, empty first, capped by limit.")
  @GetMapping
  @PreAuthorize(InventoryItemController.INVENTORY_ACCESS)
  public List<StockLevelDto> listStockLevels(
      @RequestParam(required = false) @Nullable UUID orgUnitId,
      @RequestParam(defaultValue = "false") boolean lowOnly,
      @RequestParam(required = false) @Nullable Integer limit) {
    return stockLevelService.list(orgUnitId, lowOnly, limit);
  }

  /**
   * Lists the units whose levels the caller may maintain.
   *
   * @return the units, by name
   */
  @Operation(
      summary = "List the units whose minimum stock levels the caller maintains",
      description = "The active units in the caller's Lager scope where the caller is Logistician.")
  @GetMapping("/units")
  @PreAuthorize(InventoryItemController.INVENTORY_ACCESS)
  public List<OrgUnitReferenceDto> listMaintainableStockLevelUnits() {
    return stockLevelService.maintainableUnits();
  }

  /**
   * Sets a new level.
   *
   * @param request the unit, material, tier and amounts
   * @return the new level
   */
  @Operation(summary = "Set a minimum stock level")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Set"),
    @ApiResponse(responseCode = "400", description = "Invalid amounts"),
    @ApiResponse(responseCode = "403", description = "Not a Logistician of the unit"),
    @ApiResponse(responseCode = "404", description = "Unit, material or tier not found"),
    @ApiResponse(responseCode = "409", description = "The unit already has this level")
  })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(
      "isAuthenticated() and"
          + " @stockLevelAccessPolicy.canMaintainStockLevelsOf(#request.orgUnitId())")
  public StockLevelDto createStockLevel(
      @RequestBody @Valid @NotNull StockLevelCreateRequest request) {
    return stockLevelService.create(request);
  }

  /**
   * Changes a level's minimum, target and tier.
   *
   * @param id the level
   * @param request the new values and the echoed version
   * @return the level
   */
  @Operation(summary = "Change a minimum stock level")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Changed or unchanged"),
    @ApiResponse(responseCode = "400", description = "Invalid amounts"),
    @ApiResponse(responseCode = "403", description = "Not a Logistician of the unit"),
    @ApiResponse(responseCode = "409", description = "Stale version or duplicate tier")
  })
  @PutMapping("/{id}")
  @PreAuthorize("isAuthenticated() and @stockLevelAccessPolicy.canMaintainStockLevel(#id)")
  public StockLevelDto updateStockLevel(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull StockLevelUpdateRequest request) {
    return stockLevelService.update(id, request);
  }

  /**
   * Removes a level.
   *
   * @param id the level
   * @param version the echoed version
   */
  @Operation(summary = "Remove a minimum stock level")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Removed"),
    @ApiResponse(responseCode = "403", description = "Not a Logistician of the unit"),
    @ApiResponse(responseCode = "409", description = "Stale version")
  })
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated() and @stockLevelAccessPolicy.canMaintainStockLevel(#id)")
  public void deleteStockLevel(@PathVariable @NotNull UUID id, @RequestParam long version) {
    stockLevelService.delete(id, version);
  }
}
