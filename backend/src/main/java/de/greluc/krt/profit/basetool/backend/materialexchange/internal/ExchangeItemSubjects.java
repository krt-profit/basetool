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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.BlueprintProducts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredGameItemException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SelectableGameItems;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockItemCatalogue;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves the item an item offer or request names (ADR-0371): an item an active blueprint produces
 * by its blueprint product, any other item the Lager books by its catalogue row.
 */
@Component
@RequiredArgsConstructor
class ExchangeItemSubjects {

  private final BlueprintProducts blueprintProducts;
  private final SelectableGameItems selectableGameItems;
  private final StockItemCatalogue stockItemCatalogue;

  /**
   * Resolves the item a free-stated offer or a request names by exactly one of a product key and a
   * catalogue row. A row an active blueprint produces resolves to its product.
   *
   * @param productKey the blueprint product key, or {@code null}
   * @param gameItemId the catalogue row, or {@code null}
   * @return the named item
   * @throws BadRequestException when both or neither are given, or the row is no item the Lager
   *     books
   * @throws NotFoundException when the key resolves to no active product or the row is unknown
   * @throws RetiredGameItemException when the P4K retired the row
   */
  @NotNull
  ExchangeItemSubject named(@Nullable String productKey, @Nullable UUID gameItemId) {
    if ((productKey == null) == (gameItemId == null)) {
      throw new BadRequestException("Name exactly one of productKey and gameItemId.");
    }
    if (productKey != null) {
      return ExchangeItemSubject.of(
          Entities.require(
              blueprintProducts.resolveByProductKey(productKey),
              () -> "No craftable item (blueprint product) for key: " + productKey));
    }
    GameItem item = selectableGameItems.require(gameItemId);
    return blueprintProducts
        .resolveByGameItem(item.getId())
        .map(ExchangeItemSubject::of)
        .orElseGet(() -> stockedByType(item));
  }

  /**
   * Resolves the item of a Lager row a stock-backed offer releases: its blueprint product, else its
   * catalogue row, whatever its type, because the row is already stock.
   *
   * @param item the row's catalogue item
   * @return the named item
   * @throws RetiredGameItemException when no blueprint produces the item and the P4K retired it
   */
  @NotNull
  ExchangeItemSubject stocked(@NotNull GameItem item) {
    return blueprintProducts
        .resolveByGameItem(item.getId())
        .map(ExchangeItemSubject::of)
        .orElseGet(() -> ExchangeItemSubject.of(selectableGameItems.requireSelectable(item)));
  }

  /**
   * Names a catalogue row no blueprint produces when the Lager books it through its type.
   *
   * @param item the catalogue row
   * @return the row's subject
   * @throws BadRequestException when the Lager does not book the item
   */
  @NotNull
  private ExchangeItemSubject stockedByType(@NotNull GameItem item) {
    if (!stockItemCatalogue.isStockedByType(item)) {
      throw new BadRequestException("This item cannot be listed on the Materialbörse.");
    }
    return ExchangeItemSubject.of(item);
  }
}
