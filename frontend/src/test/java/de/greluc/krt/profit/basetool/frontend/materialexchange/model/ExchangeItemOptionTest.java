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

package de.greluc.krt.profit.basetool.frontend.materialexchange.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.ManufacturerRefDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The merged entries of the Materialbörse item pickers (ADR-0371). */
class ExchangeItemOptionTest {

  private static final UUID RAILGUN = UUID.fromString("0b7c3e1a-2d4f-4a5b-9c6d-7e8f9a0b1c2d");
  private static final UUID SHOVEL = UUID.fromString("5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d");

  @Test
  void bothSourcesMergeIntoOneCaseInsensitiveNameOrderUpToTheLimit() {
    List<BlueprintProductDto> products =
        List.of(
            new BlueprintProductDto("venture helmet", "Venture Helmet", 1, "RSI", null, false),
            new BlueprintProductDto(
                "arclight", "arclight Pistol", 1, "Klaus & Werner", null, false));
    List<InventoryGameItemReferenceDto> items =
        List.of(
            new InventoryGameItemReferenceDto(RAILGUN, "Scourge Railgun", "Behring", "WEAPON"),
            new InventoryGameItemReferenceDto(SHOVEL, "Zipper Shovel", null, "GENERIC"));

    List<ExchangeItemOption> merged = ExchangeItemOption.merge(products, items, 3);

    assertThat(merged)
        .extracting(ExchangeItemOption::name)
        .containsExactly("arclight Pistol", "Scourge Railgun", "Venture Helmet");
    assertThat(merged.get(0).productKey()).isEqualTo("arclight");
    assertThat(merged.get(0).gameItemId()).isNull();
    assertThat(merged.get(1).productKey()).isNull();
    assertThat(merged.get(1).gameItemId()).isEqualTo(RAILGUN);
  }

  @Test
  void anItemTakesItsManufacturerFromTheReferenceWithItsGermanName() {
    InventoryGameItemReferenceDto withRef =
        new InventoryGameItemReferenceDto(
            RAILGUN,
            "Scourge Railgun",
            "Behring",
            "WEAPON",
            null,
            null,
            null,
            null,
            false,
            null,
            new ManufacturerRefDto(UUID.randomUUID(), "Behring", "Behring DE", "BEH", null));
    InventoryGameItemReferenceDto plain =
        new InventoryGameItemReferenceDto(SHOVEL, "Zipper Shovel", "Greycat", "GENERIC");

    assertThat(ExchangeItemOption.of(withRef))
        .isEqualTo(
            new ExchangeItemOption(null, RAILGUN, "Scourge Railgun", "Behring", "Behring DE"));
    assertThat(ExchangeItemOption.of(plain))
        .isEqualTo(new ExchangeItemOption(null, SHOVEL, "Zipper Shovel", "Greycat", null));
  }

  @Test
  void aProductKeepsItsKeyAndManufacturerNames() {
    BlueprintProductDto product =
        new BlueprintProductDto("venture helmet", "Venture Helmet", 2, "RSI", "x", true, "RSI DE");

    assertThat(ExchangeItemOption.of(product))
        .isEqualTo(
            new ExchangeItemOption("venture helmet", null, "Venture Helmet", "RSI", "RSI DE"));
  }
}
