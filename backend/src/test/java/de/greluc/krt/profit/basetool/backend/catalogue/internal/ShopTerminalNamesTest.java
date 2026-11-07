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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The name match of a shop to the UEX terminals of its place (REQ-P4K-043). */
class ShopTerminalNamesTest {

  /**
   * Builds a shop with LIVE names.
   *
   * @param registryName the registry name
   * @param entityName the entity name
   * @param brand the brand, or {@code null}
   * @return the shop
   */
  private static @NotNull Shop shop(
      @NotNull String registryName, @NotNull String entityName, @Nullable String brand) {
    Shop shop = new Shop();
    shop.setRegistryName(registryName);
    shop.setEntityName(entityName);
    shop.setBrand(brand);
    return shop;
  }

  /**
   * Builds a terminal row of a place.
   *
   * @param name the UEX name
   * @return the row
   */
  private static @NotNull StarmapPlaceRow terminal(@NotNull String name) {
    return new StarmapPlaceRow(
        StarmapPlaceKind.TERMINAL,
        UUID.randomUUID(),
        name,
        "Stanton",
        null,
        null,
        null,
        "New Babbage",
        null,
        null,
        null,
        false);
  }

  @Test
  void theTddExchange_matchesTheTddTerminal_notTheAdminOffice() {
    StarmapPlaceRow tdd = terminal("TDD - Trade and Development Division - New Babbage");
    List<StarmapPlaceRow> terminals = List.of(terminal("Admin - New Babbage"), tdd);

    assertThat(
            ShopTerminalNames.bestMatches(
                shop("L_TDD_NewBabbage", "SCShop_CommEx_TDD_NewBabbage", "tdd"), terminals))
        .containsExactly(tdd);
  }

  @Test
  void anApostropheAndSpaces_doNotStopAMatch() {
    StarmapPlaceRow dumpers = terminal("Dumper's Depot - Area 18");

    assertThat(
            ShopTerminalNames.bestMatches(
                shop("DumpersDepot_Area18", "Dumpers_Depot_Area18", "dumpersdepot"),
                List.of(dumpers, terminal("Cubby Blast - Area 18"))))
        .containsExactly(dumpers);
  }

  @Test
  void theLongestShopPartWins() {
    StarmapPlaceRow cargo = terminal("Cargo Admin - Area 18");

    assertThat(
            ShopTerminalNames.bestMatches(
                shop("CargoAdmin_Area18", "SCShop_CargoAdmin", null),
                List.of(terminal("Admin - Area 18"), cargo)))
        .containsExactly(cargo);
  }

  @Test
  void aShopPartShorterThanThreeCharacters_matchesNothing() {
    assertThat(ShopTerminalNames.shopPart("KC - Area 18")).isNull();
    assertThat(ShopTerminalNames.shopPart("TDD - Area 18")).isEqualTo("tdd");
    assertThat(ShopTerminalNames.shopPart(null)).isNull();
  }
}
