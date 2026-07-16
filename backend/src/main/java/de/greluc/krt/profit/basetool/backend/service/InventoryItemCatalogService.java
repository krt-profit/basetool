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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service behind the Lager item-catalog picker ({@code GET /api/v1/inventory/item-catalog},
 * REQ-INV-029, design §5.3/§5.4): the game items that are <em>bookable</em> as Lager item stock.
 * Bookable means "output of at least one active blueprint" ({@link
 * BlueprintRepository#findItemsWithActiveBlueprint(String, Pageable)}) — deliberately a superset of
 * the order picker's orderable-items predicate, which additionally requires a resolved RESOURCE
 * ingredient. Kept as its own tiny service (rather than a method on the order-side item service)
 * because the Lager picker is role-gated where the order picker is anonymous, and the two
 * predicates must be allowed to diverge without coupling.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryItemCatalogService {

  private final BlueprintRepository blueprintRepository;
  private final InventoryItemMapper inventoryItemMapper;

  /**
   * Pages the game items bookable as Lager item stock, optionally narrowed by a case-insensitive
   * name fragment, projected into the slim Lager reference shape (id, name, manufacturer, kind).
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} sort)
   * @return a page of bookable game-item references; never {@code null}
   */
  @NotNull
  public Page<InventoryGameItemReferenceDto> findBookableItems(
      String search, @NotNull Pageable pageable) {
    // Empty string (not null) for "no filter": a null bind into the query's LOWER(CONCAT(...))
    // makes PostgreSQL infer bytea and fail; "" matches every row via the %% pattern.
    String q = search != null && !search.isBlank() ? search.strip() : "";
    return blueprintRepository
        .findItemsWithActiveBlueprint(q, pageable)
        .map(inventoryItemMapper::gameItemToReferenceDto);
  }
}
