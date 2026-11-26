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
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventorySelectionSaleValueDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventorySelectionValuation;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MySelectionSaleValueRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The estimated sale value of the entries the caller marked in "Mein Lager" (REQ-INV-072). A read
 * whose id list can exceed a query string, so it takes a body; the JWT scopes it to the caller's
 * own entries.
 */
@RestController
@RequestMapping("/api/v1/inventory/my-inventory/sale-value")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MySelectionSaleValueController {

  private final UserService userService;
  private final InventorySelectionValuation selectionValuation;

  /**
   * Values the caller's marked entries at the caller's price basis.
   *
   * @param jwt the caller's token
   * @param request the marked entry ids
   * @return the total, the basis, the unpriced materials and whether stolen stock was left out
   */
  @Operation(
      summary = "Estimate the sale value of marked own Lager entries",
      description =
          "Totals the caller's marked material entries at the caller's price basis like the Mein"
              + " Lager sum row: stock marked „gestohlen\", PIECE materials and materials no"
              + " terminal buys stay unvalued. Ids of other members' entries, of game-item entries"
              + " or of no entry add nothing.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Valued"),
    @ApiResponse(responseCode = "400", description = "Empty id list")
  })
  @PostMapping
  public InventorySelectionSaleValueDto valueSelection(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @Valid @NotNull MySelectionSaleValueRequest request) {
    return selectionValuation.valueOf(userService.getUserIdFromJwt(jwt), request.itemIds());
  }
}
