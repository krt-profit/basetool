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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintOverviewService;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the org-unit blueprint availability overview (#364): which blueprints are
 * available among the members of the caller's oversight org units, and which members own a given
 * blueprint. Read-only oversight surface, gated to admins, officers (their Staffel) and
 * Spezialkommando leads (their SK) by {@code @ownerScopeService.canAccessBlueprintOverview()}; the
 * per-row scope itself is resolved in {@link PersonalBlueprintOverviewService}.
 */
@RestController
@RequestMapping("/api/v1/personal-blueprints/overview")
@RequiredArgsConstructor
@PreAuthorize("@ownerScopeService.canAccessBlueprintOverview()")
@Tag(
    name = "Blueprint Availability",
    description = "Org-unit blueprint availability overview (#364).")
@SecurityRequirement(name = "bearerAuth")
public class PersonalBlueprintOverviewController {

  private final PersonalBlueprintOverviewService service;

  /**
   * Lists the blueprints available among the members of the caller's oversight org units
   * (paginated, sortable by product name), one row per product with the owning-member count. The
   * optional {@code search} narrows to products whose name contains the fragment
   * (case-insensitive); it is applied before pagination so the page numbers describe the filtered
   * set.
   *
   * @param page optional zero-based page index
   * @param size optional page size
   * @param sort optional sort expression over the whitelisted product-name field
   * @param search optional case-insensitive product-name fragment
   * @return the paged availability list
   */
  @GetMapping
  @Operation(
      summary = "List blueprints available among the caller's oversight org-unit members.",
      description =
          "Paginated; the optional product-name search filters before pagination so it spans"
              + " every entry, not just the requested page.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Paginated list of available blueprints."),
    @ApiResponse(responseCode = "401", description = "Authentication required."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is not an officer, admin, or Spezialkommando lead.")
  })
  public PageResponse<BlueprintOverviewEntryDto> listAvailableBlueprints(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String search) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            PersonalBlueprintOverviewService.SORTABLE_FIELDS,
            PersonalBlueprintOverviewService.DEFAULT_SORT_FIELD);
    Page<BlueprintOverviewEntryDto> result = service.listAvailableBlueprints(pageable, search);
    return PageResponse.of(result);
  }

  /**
   * Lists the in-scope members that own the given blueprint variant family, by display name only.
   * The scope is re-resolved server-side, so the {@code productKey} parameter cannot widen
   * visibility. The {@code productKey} carries the row's variant family key (a base item and its
   * cosmetic variants share one), which the service expands to the family's product keys before
   * resolving owners.
   *
   * @param productKey the variant family key (the availability row's key) whose owners to list
   * @return the owning in-scope members' display names
   */
  @GetMapping("/owners")
  @Operation(summary = "List the in-scope members who own a given blueprint (display names only).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Owners of the blueprint within the scope."),
    @ApiResponse(responseCode = "401", description = "Authentication required."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is not an officer, admin, or Spezialkommando lead.")
  })
  public List<BlueprintOverviewOwnerDto> listOwners(@RequestParam String productKey) {
    return service.listOwnersForProduct(productKey);
  }
}
