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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import org.junit.jupiter.api.Test;

/** The exchange's ship component data follows the Basetool's component rule (REQ-XCH-049). */
class ExchangeComponentDtoTest {

  @Test
  void aShipComponentCarriesItsTypeSizeGradeAndClassAsStableCodes() {
    assertThat(ExchangeComponentDto.of("Shield", 1, "A", "MILITARY"))
        .isEqualTo(new ExchangeComponentDto("SHIELD", 1, "A", "MILITARY"));
  }

  @Test
  void aWikiTypeThatIsNoShipComponentCarriesNoComponent() {
    assertThat(ExchangeComponentDto.of("Paints", 1, "A", "MILITARY")).isNull();
    assertThat(ExchangeComponentDto.of(null, 1, "A", "MILITARY")).isNull();
  }

  @Test
  void aComponentLeavesItsUnknownKeysOut() {
    assertThat(ExchangeComponentDto.of("Cooler", null, "  ", null))
        .isEqualTo(new ExchangeComponentDto("COOLER", null, null, null));
    assertThat(ExchangeComponentDto.of("PowerPlant", 2, " C ", " "))
        .isEqualTo(new ExchangeComponentDto("POWER_PLANT", 2, "C", null));
  }

  @Test
  void aCatalogueItemFollowsTheSameRule() {
    GameItem shield = new GameItem();
    shield.setWikiType("Shield");
    shield.setSizeClass(1);
    shield.setGrade("A");
    shield.setItemClass(ShipComponentClass.MILITARY);
    GameItem helmet = new GameItem();
    helmet.setWikiType("Char_Armor_Helmet");

    assertThat(ExchangeComponentDto.of(shield))
        .isEqualTo(new ExchangeComponentDto("SHIELD", 1, "A", "MILITARY"));
    assertThat(ExchangeComponentDto.of(helmet)).isNull();
  }
}
