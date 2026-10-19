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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link P4kText} and the DTOs that decode through it (REQ-DATA-025). */
class P4kTextTest {

  @Test
  void decodeLineBreaks_turnsEscapedLineBreaksIntoRealOnes() {
    assertThat(P4kText.decodeLineBreaks("NDR: 08\\nHEI: 06\\n\\nText"))
        .isEqualTo("NDR: 08\nHEI: 06\n\nText");
  }

  @Test
  void decodeLineBreaks_leavesNullAndPlainTextAlone() {
    assertThat(P4kText.decodeLineBreaks(null)).isNull();
    assertThat(P4kText.decodeLineBreaks("Line one\nLine two")).isEqualTo("Line one\nLine two");
  }

  @Test
  void dtos_decodeBothDescriptions() {
    P4kCommodityDto commodity =
        new P4kCommodityDto(
            null, null, null, null, null, "a\\nb", "c\\nd", null, null, null, null, null);
    P4kManufacturerDto manufacturer =
        new P4kManufacturerDto(null, null, null, null, "a\\nb", "c\\nd", null, null);
    P4kShipDto ship =
        new P4kShipDto(
            null, null, null, null, null, "a\\nb", "c\\nd", null, null, null, null, null, null,
            null);
    P4kItemDto item =
        new P4kItemDto(
            null, null, null, null, null, null, null, null, null, null, null, null, "a\\nb",
            "c\\nd", null, null, null);

    assertThat(commodity.desc()).isEqualTo("a\nb");
    assertThat(commodity.descDe()).isEqualTo("c\nd");
    assertThat(manufacturer.desc()).isEqualTo("a\nb");
    assertThat(manufacturer.descDe()).isEqualTo("c\nd");
    assertThat(ship.desc()).isEqualTo("a\nb");
    assertThat(ship.descDe()).isEqualTo("c\nd");
    assertThat(item.desc()).isEqualTo("a\nb");
    assertThat(item.descDe()).isEqualTo("c\nd");
  }
}
