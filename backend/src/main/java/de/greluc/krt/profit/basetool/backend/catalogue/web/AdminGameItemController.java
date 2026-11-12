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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.GameItemAdminDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.GameItemAdminService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only endpoints of the game items' obtainability (REQ-P4K-048, ADR-0373).
 *
 * <ul>
 *   <li>{@code GET} — the game items with their obtainability, filtered and paged.
 *   <li>{@code PUT /{id}/obtainability} — hide an item as not obtainable, or offer it again.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/catalog/admin/game-items")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – Game items",
    description =
        "Administrator endpoints that hide a catalogue game item as not obtainable in the game and"
            + " offer it again.")
@SecurityRequirement(name = "bearer-jwt")
public class AdminGameItemController {

  private final GameItemAdminService service;

  /**
   * An admin's choice whether the item pickers offer a game item.
   *
   * @param obtainable {@code false} hides the item as not obtainable in the game, {@code true}
   *     offers it again
   */
  public record GameItemObtainabilityRequest(@NotNull Boolean obtainable) {}

  /**
   * Pages the game items with their obtainability.
   *
   * @param query a fragment of the item name, or none
   * @param unobtainableOnly whether only the items an admin hid as not obtainable are listed
   * @param page zero-based page index
   * @param size page size
   * @param sort sort terms over {@code name} and {@code id}
   * @return the page of items
   */
  @GetMapping
  @Operation(
      summary = "List the catalogue's game items with their obtainability.",
      description =
          "Every item the P4K lists, or with unobtainableOnly=true the items an admin hid as not"
              + " obtainable, retired ones included.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The items."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<GameItemAdminDto> items(
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(required = false, defaultValue = "false") boolean unobtainableOnly,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size,
      @RequestParam(required = false, defaultValue = "name,asc;id,asc") String sort) {
    return PageResponse.of(
        service.items(
            StringNormalization.trimToNull(query),
            unobtainableOnly,
            PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name")));
  }

  /**
   * Hides a game item as not obtainable in the game, or offers it again.
   *
   * @param id the item
   * @param request whether the pickers offer the item
   * @return the item after the change
   */
  @PutMapping("/{id}/obtainability")
  @Operation(
      summary = "Hide a game item as not obtainable in the game, or offer it again.",
      description =
          "A hidden item leaves every item picker and no new write may take it; existing"
              + " references keep it. The request names the wanted state, so repeating it changes"
              + " nothing.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The item."),
    @ApiResponse(responseCode = "400", description = "The request names no state."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No item with the given id."),
    @ApiResponse(
        responseCode = "409",
        description = "A P4K import or a Wiki or UEX sync is running; retry once it has finished.")
  })
  public GameItemAdminDto setObtainability(
      @PathVariable("id") UUID id, @RequestBody @Valid GameItemObtainabilityRequest request) {
    return service.setObtainable(id, request.obtainable());
  }
}
