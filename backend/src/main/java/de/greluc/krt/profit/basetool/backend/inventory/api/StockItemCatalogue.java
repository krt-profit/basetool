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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The game-item catalogue behind {@code GET /api/v1/game-items} (REQ-INV-029): the items bookable
 * as Lager item stock and the narrower set orderable as job-order item lines.
 */
public interface StockItemCatalogue {

  /**
   * Pages the game items bookable as Lager item stock, optionally filtered by name.
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} sort)
   * @return a page of bookable game-item references
   */
  @NotNull
  Page<InventoryGameItemReferenceDto> findBookableItems(
      @Nullable String search, @NotNull Pageable pageable);

  /**
   * Pages the game items the Lager books through their type alone, those no active blueprint
   * produces, optionally filtered by name (ADR-0371).
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} / {@code id} sort)
   * @return a page of stocked game-item references without a blueprint
   */
  @NotNull
  Page<InventoryGameItemReferenceDto> findBookableItemsWithoutBlueprint(
      @Nullable String search, @NotNull Pageable pageable);

  /**
   * Tells whether the Lager books an item through its type, regardless of any blueprint
   * (REQ-INV-029).
   *
   * @param item the catalogue item
   * @return {@code true} when a stocked item group covers the item's type and sub-type
   */
  boolean isStockedByType(@NotNull GameItem item);

  /**
   * Pages the game items orderable as job-order item lines, optionally filtered by name.
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} / {@code id} sort)
   * @return a page of orderable game-item references
   */
  @NotNull
  Page<InventoryGameItemReferenceDto> findOrderableItems(
      @Nullable String search, @NotNull Pageable pageable);
}
