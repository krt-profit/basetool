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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintNameNormalizer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A product is keyed by its produced item's GUID, else by its normalised name (REQ-INV-070). */
class ProductKeysTest {

  private static final UUID GUID = UUID.fromString("de20695c-eb2f-4b68-be6f-9806cbd205a7");

  private final ProductKeys keys = new ProductKeys(new BlueprintNameNormalizer());

  @Test
  void aRecipeWithAProducedItemGuidIsKeyedByItsLowerCaseText() {
    assertThat(keys.of(GUID, "Light Arms")).isEqualTo("de20695c-eb2f-4b68-be6f-9806cbd205a7");
  }

  @Test
  void aRecipeWithoutAGuidIsKeyedByItsNormalisedName() {
    assertThat(keys.of(null, "  Light   Arms ")).isEqualTo("light arms");
  }

  @Test
  void onlyAGuidKeyReadsBackAsAGuid() {
    assertThat(ProductKeys.guidOf(GUID.toString())).isEqualTo(GUID);
    assertThat(ProductKeys.guidOf("light arms")).isNull();
    assertThat(ProductKeys.guidOf("DE20695C-EB2F-4B68-BE6F-9806CBD205A7")).isNull();
    assertThat(ProductKeys.guidOf(null)).isNull();
  }
}
