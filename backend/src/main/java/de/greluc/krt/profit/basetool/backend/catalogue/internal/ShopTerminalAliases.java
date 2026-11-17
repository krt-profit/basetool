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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Matches a shop to the UEX terminals around its place whose shop part names it in other words
 * (REQ-P4K-043): UEX calls a pharmacy {@code Medical Shop}, a trading post {@code Shop Terminal}. A
 * terminal matches when its compact shop part ({@link ShopTerminalNames#shopPart}) is a key of the
 * table and the shop's compact registry or entity name contains one of the key's words.
 */
final class ShopTerminalAliases {

  /** The compact UEX shop parts and the compact words a shop's own names hold for each. */
  private static final Map<String, List<String>> ALIASES =
      Map.ofEntries(
          Map.entry("medicalshop", List.of("pharmacy")),
          Map.entry("guns", List.of("blackmarketfpsitems", "weaponsmith")),
          Map.entry("shipparts", List.of("shipitems", "shipweapons")),
          Map.entry("refineryshop", List.of("refinerystore", "refineryitemshop")),
          Map.entry("refineryoresales", List.of("miningkiosks")),
          Map.entry("filteredwater", List.of("waterstall")),
          Map.entry("cargoservices", List.of("cargooffice")),
          Map.entry("shopterminal", List.of("trdpst", "tradingpost")),
          Map.entry("friedgrubs", List.of("grubstallfried")),
          Map.entry("clothingshop", List.of("clothingstall")),
          Map.entry("hotdogs", List.of("hotdog")),
          Map.entry("torpedoburrito", List.of("burritocart")),
          Map.entry("burritobar", List.of("burritocart")),
          Map.entry("landingservices", List.of("cryastroresupply")));

  /** Non-instantiable holder of static helpers. */
  private ShopTerminalAliases() {}

  /**
   * Picks the terminals whose shop part names a shop by an alias.
   *
   * @param shop the shop
   * @param terminals the UEX terminals at and around the shop's place
   * @return the matching terminals; empty when none matches, several when more do
   */
  @NotNull
  @Unmodifiable
  static List<StarmapPlaceRow> matches(
      @NotNull Shop shop, @NotNull List<StarmapPlaceRow> terminals) {
    List<String> names =
        Stream.of(shop.getRegistryName(), shop.getEntityName())
            .map(StarmapNames::compact)
            .filter(Objects::nonNull)
            .toList();
    return terminals.stream()
        .filter(terminal -> {
          String part = ShopTerminalNames.shopPart(terminal.name());
          List<String> words = part == null ? List.of() : ALIASES.getOrDefault(part, List.of());
          return words.stream().anyMatch(word -> names.stream().anyMatch(n -> n.contains(word)));
        })
        .toList();
  }
}
