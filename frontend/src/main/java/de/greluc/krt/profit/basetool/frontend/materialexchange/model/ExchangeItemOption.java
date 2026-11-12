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

package de.greluc.krt.profit.basetool.frontend.materialexchange.model;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.ManufacturerRefDto;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One entry of the Materialbörse item pickers (ADR-0372): a blueprint product, named by its product
 * key, or a stocked item no blueprint produces, named by its catalogue row. Exactly one of the two
 * identities is set.
 *
 * @param productKey the normalized blueprint product key, or {@code null}
 * @param gameItemId the catalogue row, or {@code null}
 * @param name the item's display name
 * @param manufacturerName the manufacturer, or {@code null}
 * @param manufacturerNameDe the manufacturer's German name, or {@code null}
 */
public record ExchangeItemOption(
    @Nullable String productKey,
    @Nullable UUID gameItemId,
    @NotNull String name,
    @Nullable String manufacturerName,
    @Nullable String manufacturerNameDe) {

  /** Orders the picker by name, case-insensitively, as both backend reads do. */
  private static final Comparator<ExchangeItemOption> BY_NAME =
      Comparator.comparing(ExchangeItemOption::name, String.CASE_INSENSITIVE_ORDER);

  /**
   * Names a blueprint product.
   *
   * @param product the product
   * @return the option
   */
  @NotNull
  public static ExchangeItemOption of(@NotNull BlueprintProductDto product) {
    return new ExchangeItemOption(
        product.productKey(),
        null,
        product.name(),
        product.manufacturerName(),
        product.manufacturerNameDe());
  }

  /**
   * Names a stocked item no blueprint produces.
   *
   * @param item the catalogue row
   * @return the option
   */
  @NotNull
  public static ExchangeItemOption of(@NotNull InventoryGameItemReferenceDto item) {
    ManufacturerRefDto manufacturer = item.manufacturerRef();
    return new ExchangeItemOption(
        null,
        item.id(),
        item.name(),
        manufacturer == null ? item.manufacturer() : manufacturer.name(),
        manufacturer == null ? null : manufacturer.nameDe());
  }

  /**
   * Merges the two picker reads into one list in name order.
   *
   * @param products the blueprint products matching the search
   * @param items the stocked items without a blueprint matching the search
   * @param limit the most options to keep
   * @return the first {@code limit} options by name
   */
  @NotNull
  @Unmodifiable
  public static List<ExchangeItemOption> merge(
      @NotNull List<BlueprintProductDto> products,
      @NotNull List<InventoryGameItemReferenceDto> items,
      int limit) {
    return Stream.concat(
            products.stream().map(ExchangeItemOption::of),
            items.stream().map(ExchangeItemOption::of))
        .sorted(BY_NAME)
        .limit(limit)
        .toList();
  }
}
