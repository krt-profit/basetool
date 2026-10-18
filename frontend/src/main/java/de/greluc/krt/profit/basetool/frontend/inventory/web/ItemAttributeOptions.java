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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The options of the item view's category, component-type, size and grade filters: only values
 * present in the viewer's stock (REQ-INV-063).
 *
 * @param kinds the distinct item category names, sorted
 * @param componentTypes the distinct ship component type names, sorted
 * @param sizes the distinct component sizes, ascending
 * @param grades the distinct component grades, sorted
 */
public record ItemAttributeOptions(
    @NotNull @Unmodifiable List<String> kinds,
    @NotNull @Unmodifiable List<String> componentTypes,
    @NotNull @Unmodifiable List<Integer> sizes,
    @NotNull @Unmodifiable List<String> grades) {

  /**
   * Collects the options from the game items of the given groups.
   *
   * @param groups the grouped item stock the options are drawn from
   * @return the options; empty lists when no group carries a value
   */
  public static @NotNull ItemAttributeOptions of(@NotNull List<GroupedInventoryDto> groups) {
    List<InventoryGameItemReferenceDto> gameItems =
        groups.stream().map(GroupedInventoryDto::gameItem).filter(Objects::nonNull).toList();
    return new ItemAttributeOptions(
        distinctSorted(gameItems, InventoryGameItemReferenceDto::kind),
        distinctSorted(gameItems, InventoryGameItemReferenceDto::componentType),
        distinctSorted(gameItems, InventoryGameItemReferenceDto::size),
        distinctSorted(gameItems, InventoryGameItemReferenceDto::grade));
  }

  private static <T extends Comparable<T>> @NotNull @Unmodifiable List<T> distinctSorted(
      @NotNull List<InventoryGameItemReferenceDto> gameItems,
      @NotNull Function<InventoryGameItemReferenceDto, T> attribute) {
    return gameItems.stream()
        .map(attribute)
        .filter(Objects::nonNull)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
  }
}
