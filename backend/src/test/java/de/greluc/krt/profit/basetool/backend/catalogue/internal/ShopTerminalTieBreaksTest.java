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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Terminals a shop's name matches equally well are told apart by type, then district. */
class ShopTerminalTieBreaksTest {

  private static Shop shop(String registryName, P4kShopKind kind, boolean commodityExchange) {
    Shop shop = new Shop();
    shop.setRegistryName(registryName);
    shop.setKind(kind);
    shop.setCommodityExchange(commodityExchange);
    return shop;
  }

  @Test
  void anItemShop_takesTheItemTwin_andAnExchangeTheCommodityTwin() {
    StarmapPlaceRow items =
        StarmapPlaceRowBuilder.terminal("Dumper's Depot - GrimHEX", "Grim HEX", "item").build();
    StarmapPlaceRow commodities =
        StarmapPlaceRowBuilder.terminal("Dumper's Depot - GrimHEX", "Grim HEX", "commodity")
            .build();
    List<StarmapPlaceRow> twins = List.of(items, commodities);

    assertThat(
            ShopTerminalTieBreaks.pick(
                shop("DumpersDepot_GrimHEX", P4kShopKind.PERSONAL_WEAPON, false), List.of(), twins))
        .containsExactly(items);
    assertThat(
            ShopTerminalTieBreaks.pick(shop("DumpersDepot_GrimHEX", null, true), List.of(), twins))
        .containsExactly(commodities);
  }

  @Test
  void aShopOfNoKindWhoseNameSaysCommodity_takesTheCommodityTwin() {
    StarmapPlaceRow items =
        StarmapPlaceRowBuilder.terminal("TDD - Orison", "Orison", "item").build();
    StarmapPlaceRow raw =
        StarmapPlaceRowBuilder.terminal("TDD - Orison", "Orison", "commodity_raw").build();

    assertThat(
            ShopTerminalTieBreaks.pick(
                shop("TDD_Commodities_Orison", null, false), List.of(), List.of(items, raw)))
        .containsExactly(raw);
  }

  @Test
  void theDistrictTheShopsContainersName_picksTheTerminal() {
    StarmapPlaceRow plaza =
        StarmapPlaceRowBuilder.terminal("Ellroy's - Central Plaza - Area 18", "Area 18", "item")
            .build();
    StarmapPlaceRow riverside =
        StarmapPlaceRowBuilder.terminal("Ellroy's - Riverside - Area 18", "Area 18", "item")
            .build();

    assertThat(
            ShopTerminalTieBreaks.pick(
                shop("Ellroys_Area18", null, false),
                List.of(
                    "OOC_Stanton_3_ArcCorp", "Area18_City_objectContainer", "Area18_central-001"),
                List.of(plaza, riverside)))
        .containsExactly(plaza);
  }

  @Test
  void terminalsNeitherTypeNorDistrictTellApart_stayTied() {
    StarmapPlaceRow first =
        StarmapPlaceRowBuilder.terminal("Cubby Blast - Area 18", "Area 18", "item").build();
    StarmapPlaceRow second =
        StarmapPlaceRowBuilder.terminal("Cubby Blast - Area 18 Plaza", "Area 18", "item").build();

    assertThat(
            ShopTerminalTieBreaks.pick(
                shop("CubbyBlast_Area18", null, false), List.of("Area18"), List.of(first, second)))
        .containsExactly(first, second);
  }
}
