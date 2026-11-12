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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockItemCatalogue;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service behind the game-item catalogue {@code GET /api/v1/game-items} (REQ-INV-029): lists
 * the game items bookable as Lager item stock, i.e. every blueprint output and every item of a
 * {@link StockableItemGroup} ({@link GameItemRepository#findStockable(String, Collection,
 * Collection, Pageable)}), or the narrower set orderable as job-order item lines ({@link
 * BlueprintRepository#findOrderableItems(String, Pageable)}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryItemCatalogService implements StockItemCatalogue {

  private final BlueprintRepository blueprintRepository;
  private final GameItemRepository gameItemRepository;
  private final InventoryItemMapper inventoryItemMapper;

  /**
   * Pages the game items bookable as Lager item stock, optionally filtered by name, as slim Lager
   * references (id, name, manufacturer, kind).
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} sort)
   * @return a page of bookable game-item references; never {@code null}
   */
  @Override
  @NotNull
  public Page<InventoryGameItemReferenceDto> findBookableItems(
      @Nullable String search, @NotNull Pageable pageable) {
    return gameItemRepository
        .findStockable(
            likeTerm(search),
            StockableItemGroup.TYPES,
            StockableItemGroup.EXCLUDED_TYPE_SUB_TYPES,
            pageable)
        .map(inventoryItemMapper::gameItemToReferenceDto);
  }

  /**
   * Pages the game items the Lager books through their type alone, those no active blueprint
   * produces, optionally filtered by name, as slim Lager references.
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} / {@code id} sort)
   * @return a page of stocked game-item references without a blueprint; never {@code null}
   */
  @Override
  @NotNull
  public Page<InventoryGameItemReferenceDto> findBookableItemsWithoutBlueprint(
      @Nullable String search, @NotNull Pageable pageable) {
    return gameItemRepository
        .findStockedWithoutBlueprint(
            likeTerm(search),
            StockableItemGroup.TYPES,
            StockableItemGroup.EXCLUDED_TYPE_SUB_TYPES,
            pageable)
        .map(inventoryItemMapper::gameItemToReferenceDto);
  }

  /**
   * Tells whether a stocked item group covers the item's P4K type and sub-type.
   *
   * @param item the catalogue item
   * @return {@code true} when the Lager books the item through its type
   */
  @Override
  public boolean isStockedByType(@NotNull GameItem item) {
    return StockableItemGroup.covers(item.getWikiType(), item.getWikiSubType());
  }

  /**
   * Pages the game items orderable as job-order item lines (output of an active blueprint with at
   * least one resolved resource ingredient), optionally filtered by name.
   *
   * @param search case-insensitive name substring, or {@code null}/blank for no filter
   * @param pageable page request (whitelisted {@code name} / {@code id} sort)
   * @return a page of orderable game-item references; never {@code null}
   */
  @Override
  @NotNull
  public Page<InventoryGameItemReferenceDto> findOrderableItems(
      @Nullable String search, @NotNull Pageable pageable) {
    return blueprintRepository
        .findOrderableItems(likeTerm(search), pageable)
        .map(inventoryItemMapper::gameItemToReferenceDto);
  }

  /**
   * Turns a user search term into the escaped LIKE operand, {@code ""} for no filter.
   *
   * @param search the raw term, or {@code null}
   * @return the escaped, stripped term
   */
  @NotNull
  private static String likeTerm(@Nullable String search) {
    return search != null && !search.isBlank() ? LikePatterns.escape(search.strip()) : "";
  }
}
