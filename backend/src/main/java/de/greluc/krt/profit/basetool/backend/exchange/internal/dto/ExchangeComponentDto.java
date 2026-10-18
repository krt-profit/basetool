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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The ship component data of a game item in the exchange, as stable codes (REQ-XCH-049,
 * REQ-INV-061, REQ-INV-062); an unknown key is left out.
 *
 * @param componentType the {@code ShipComponentType} name
 * @param size the component size, or {@code null} when unknown
 * @param grade the component grade, or {@code null} when unknown
 * @param itemClass the {@code ShipComponentClass} name, or {@code null} when unknown
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeComponentDto(
    @NotNull String componentType,
    @Nullable Integer size,
    @Nullable String grade,
    @Nullable String itemClass) {

  /**
   * Builds the component data from a game item's stored columns.
   *
   * @param wikiType the item's SC Wiki type
   * @param size the item's size class
   * @param grade the item's grade
   * @param itemClass the item's stored {@code ShipComponentClass} name
   * @return the component data, or {@code null} when the wiki type is no ship component
   */
  @Contract("null, _, _, _ -> null")
  public static @Nullable ExchangeComponentDto of(
      @Nullable String wikiType,
      @Nullable Integer size,
      @Nullable String grade,
      @Nullable String itemClass) {
    ShipComponentType type = ShipComponentType.fromWikiType(wikiType);
    if (type == null) {
      return null;
    }
    return new ExchangeComponentDto(
        type.name(),
        size,
        StringNormalization.trimToNull(grade),
        StringNormalization.trimToNull(itemClass));
  }

  /**
   * Builds the component data of a catalogue item.
   *
   * @param item the catalogue item
   * @return the component data, or {@code null} when the item is no ship component
   */
  public static @Nullable ExchangeComponentDto of(@NotNull GameItem item) {
    GameItemReferenceDto ref = GameItemReferenceDto.of(item);
    return ref.componentType() == null
        ? null
        : new ExchangeComponentDto(ref.componentType(), ref.size(), ref.grade(), ref.itemClass());
  }
}
