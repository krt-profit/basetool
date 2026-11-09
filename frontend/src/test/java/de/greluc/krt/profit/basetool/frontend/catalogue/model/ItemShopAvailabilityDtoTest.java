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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** A shop's place shows its English name on every page (REQ-P4K-023). */
class ItemShopAvailabilityDtoTest {

  private static ItemShopAvailabilityDto at(String placeName, String placeNameDe) {
    return new ItemShopAvailabilityDto(null, null, null, null, null, placeName, placeNameDe);
  }

  @Test
  void aGermanPage_showsTheEnglishPlaceName() {
    assertThat(at("Lorville", "Lorville DE").placeName(Locale.GERMAN)).isEqualTo("Lorville");
  }

  @Test
  void aGermanPage_showsTheEnglishName_whenTheGermanOneIsBlankOrAbsent() {
    assertThat(at("Lorville", " ").placeName(Locale.GERMAN)).isEqualTo("Lorville");
    assertThat(at("Lorville", null).placeName(Locale.GERMAN)).isEqualTo("Lorville");
  }

  @Test
  void anEnglishPage_showsTheEnglishPlaceName() {
    assertThat(at("Lorville", "Lorville DE").placeName(Locale.ENGLISH)).isEqualTo("Lorville");
  }
}
