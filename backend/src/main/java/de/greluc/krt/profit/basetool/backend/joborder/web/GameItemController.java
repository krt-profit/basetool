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

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ItemDerivationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemCatalogService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only game-item catalogue behind the item pickers of the job-order create form and the Lager
 * booking form: the game items, the blueprints that produce one, and the derivation of a
 * blueprint's materials.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Game items", description = "Game-item catalogue for the order and Lager pickers")
public class GameItemController {

  /**
   * Gate of {@code GET /game-items}: any login for the orderable subset, ADMIN, OFFICER,
   * LOGISTICIAN or KRT_MEMBER for the full bookable set (the former {@code /inventory/**} role
   * rule).
   */
  static final String GAME_ITEM_ACCESS =
      "isAuthenticated() and (#orderable or hasAnyRole('"
          + Roles.ADMIN
          + "','"
          + Roles.OFFICER
          + "','"
          + Roles.LOGISTICIAN
          + "','"
          + Roles.KRT_MEMBER
          + "'))";

  private final InventoryItemCatalogService inventoryItemCatalogService;
  private final JobOrderItemService jobOrderItemService;

  /**
   * Pages the game items that are the output of at least one active blueprint, or only those
   * orderable as item-order lines.
   *
   * @param q optional case-insensitive item-name filter
   * @param orderable {@code true} narrows to items with at least one resolvable material
   * @param page zero-based page index
   * @param size page size
   * @param sort sort spec (whitelist: {@code name}, {@code id}; the default appends {@code id} as a
   *     tiebreaker)
   * @return paged game-item references
   */
  @GetMapping("/game-items")
  @PreAuthorize(GAME_ITEM_ACCESS)
  @Operation(
      summary = "List game items",
      description =
          "Returns a paginated list of game items that are the output of at least one active"
              + " blueprint; orderable=true keeps those with a resolvable material (item-order"
              + " lines). The full set needs ADMIN, OFFICER, LOGISTICIAN or KRT_MEMBER, the"
              + " orderable subset any login.")
  @Transactional(readOnly = true)
  public PageResponse<InventoryGameItemReferenceDto> getGameItems(
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "false") boolean orderable,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "name,asc;id,asc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    Page<InventoryGameItemReferenceDto> p =
        orderable
            ? inventoryItemCatalogService.findOrderableItems(q, pageable)
            : inventoryItemCatalogService.findBookableItems(q, pageable);
    return PageResponse.of(p);
  }

  /**
   * Lists the blueprints that produce a given game item, for the create form's blueprint picker.
   *
   * @param gameItemId the game item
   * @return blueprint references producing that item
   */
  @GetMapping("/game-items/{gameItemId}/blueprints")
  @Operation(
      summary = "List blueprints for a game item",
      description = "Returns the blueprints that produce the given item.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<BlueprintReferenceDto> getBlueprintsForItem(@PathVariable UUID gameItemId) {
    return jobOrderItemService.blueprintsForItem(gameItemId);
  }

  /**
   * Previews the material derivation for a chosen blueprint at a given amount: resolved materials
   * (with default quality), adoptable sub-assembly suggestions, and unresolved-ingredient names for
   * the create-form warning banner.
   *
   * @param id the chosen blueprint
   * @param amount the whole-unit amount to scale by (defaults to 1)
   * @return the derivation preview
   */
  @GetMapping("/blueprints/{id}/derivation")
  @Operation(
      summary = "Preview blueprint material derivation",
      description =
          "Returns the materials, sub-assembly suggestions and unresolved ingredients derived from"
              + " a blueprint at the given amount.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public ItemDerivationDto getBlueprintDerivation(
      @PathVariable UUID id, @RequestParam(required = false, defaultValue = "1") int amount) {
    return jobOrderItemService.deriveForPreview(id, amount);
  }
}
