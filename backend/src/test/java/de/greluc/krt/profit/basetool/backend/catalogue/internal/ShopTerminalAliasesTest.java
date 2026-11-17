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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A UEX terminal that names a shop in other words matches it by alias (REQ-P4K-043). */
class ShopTerminalAliasesTest {

  private static Shop shop(String registryName) {
    Shop shop = new Shop();
    shop.setRegistryName(registryName);
    shop.setEntityName("SCShop_" + registryName);
    return shop;
  }

  private static StarmapPlaceRow terminal(String name) {
    return StarmapPlaceRowBuilder.terminal(name, "Lorville", "item").build();
  }

  @ParameterizedTest
  @CsvSource({
    "Medical Shop - ARC-L1, Pharmacy_RestStop_ARC-L1",
    "Shop Terminal - Fallow Field, M_HH_Trader_Trdpst",
    "Landing Services - Lorville, CryAstro_Resupply_All",
    "Torpedo Burrito - Area 18, BurritoCart_Area18"
  })
  void aTerminalNamingTheShopByAnAlias_matches(String terminalName, String registryName) {
    StarmapPlaceRow terminal = terminal(terminalName);

    assertThat(ShopTerminalAliases.matches(shop(registryName), List.of(terminal(""), terminal)))
        .containsExactly(terminal);
  }

  @Test
  void aTerminalWhoseAliasTheShopDoesNotHold_matchesNothing() {
    assertThat(
            ShopTerminalAliases.matches(
                shop("Pharmacy_RestStop_ARC-L1"), List.of(terminal("Landing Services - ARC-L1"))))
        .isEmpty();
  }
}
