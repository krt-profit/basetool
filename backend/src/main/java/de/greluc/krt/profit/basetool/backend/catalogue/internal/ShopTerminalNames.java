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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Matches a shop to the UEX terminals of its place by name (REQ-P4K-043). UEX names a terminal
 * {@code <shop> - <place>}; its shop part, lower case without separators, matches a shop whose
 * registry name, entity name or brand, compacted the same way, contains it. The longest match wins,
 * so {@code Cargo Admin} beats {@code Admin} for a cargo office.
 */
final class ShopTerminalNames {

  /** UEX's separator between a terminal's shop part and its place. */
  private static final String PLACE_SEPARATOR = " - ";

  /** The shortest shop part that matches; {@code TDD} is the shortest UEX uses. */
  private static final int MIN_PART_LENGTH = 3;

  /** Non-instantiable holder of static helpers. */
  private ShopTerminalNames() {}

  /**
   * Picks the terminals of a place whose shop part matches a shop the longest.
   *
   * @param shop the shop
   * @param terminals the UEX terminals of the shop's place
   * @return the best matches; empty when none matches, several when they tie
   */
  @NotNull
  @Unmodifiable
  static List<StarmapPlaceRow> bestMatches(
      @NotNull Shop shop, @NotNull List<StarmapPlaceRow> terminals) {
    List<String> keys =
        Stream.of(shop.getRegistryName(), shop.getEntityName(), shop.getBrand())
            .map(ShopTerminalNames::compact)
            .filter(Objects::nonNull)
            .toList();
    List<StarmapPlaceRow> best = new ArrayList<>();
    int bestLength = 0;
    for (StarmapPlaceRow terminal : terminals) {
      String part = shopPart(terminal.name());
      if (part == null || keys.stream().noneMatch(k -> k.contains(part))) {
        continue;
      }
      if (part.length() > bestLength) {
        best.clear();
        bestLength = part.length();
      }
      if (part.length() == bestLength) {
        best.add(terminal);
      }
    }
    return List.copyOf(best);
  }

  /**
   * Returns a terminal name's shop part, compacted.
   *
   * @param name the UEX name, or {@code null}
   * @return the part before the first {@code " - "}, lower case without separators, or {@code null}
   *     when it is shorter than {@value #MIN_PART_LENGTH} characters
   */
  @Contract(pure = true)
  @Nullable
  static String shopPart(@Nullable String name) {
    if (name == null) {
      return null;
    }
    int cut = name.indexOf(PLACE_SEPARATOR);
    String part = compact(cut < 0 ? name : name.substring(0, cut));
    return part == null || part.length() < MIN_PART_LENGTH ? null : part;
  }

  /**
   * Compacts a name: normalised (REQ-P4K-035) and without spaces.
   *
   * @param name the name, or {@code null}
   * @return the compact name, or {@code null} when nothing is left
   */
  @Contract(pure = true)
  @Nullable
  static String compact(@Nullable String name) {
    String normalised = StarmapNames.normalise(name);
    return normalised == null ? null : normalised.replace(" ", "");
  }
}
