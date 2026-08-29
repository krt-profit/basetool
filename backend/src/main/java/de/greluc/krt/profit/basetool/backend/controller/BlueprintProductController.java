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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing read surface for the blueprint master (#327). Exposes a slim type-ahead search over
 * the SC Wiki blueprint products, de-duplicated to the per-product unit of ownership. Open to any
 * authenticated user (blueprints are non-sensitive global reference data); the richer admin detail
 * view stays on the admin-only {@link BlueprintController}.
 */
@RestController
@RequestMapping("/api/v1/blueprints/products")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Blueprint Products", description = "User-facing blueprint product search (#327).")
@SecurityRequirement(name = "bearerAuth")
public class BlueprintProductController {

  private final BlueprintProductService blueprintProductService;

  /**
   * Searches blueprint products by a case-insensitive product-name substring. Each result carries a
   * flag indicating whether the calling user already owns the product. The owner is derived from
   * the JWT {@code sub}, never from a request parameter.
   *
   * @param q optional case-insensitive product-name substring; blank returns the first products
   * @param limit optional maximum number of results; clamped to {@code [1, MAX_LIMIT]}
   * @param ownerUserId the caller's {@code app_user.id}
   * @return up to {@code limit} matching products, alphabetically by name
   */
  @GetMapping("/search")
  @Operation(
      summary = "Search blueprint products (type-ahead) with an owned-by-caller flag.",
      description =
          "Returns de-duplicated SC Wiki blueprint products matching the name filter, each marked "
              + "with whether the calling user already owns it. Capped at a small result count for "
              + "type-ahead use.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Matching products returned."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public List<BlueprintProductDto> search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer limit,
      @CurrentUserId UUID ownerUserId) {
    int effectiveLimit = limit == null ? BlueprintProductService.DEFAULT_LIMIT : limit;
    return blueprintProductService.searchProducts(q, effectiveLimit, ownerUserId);
  }
}
