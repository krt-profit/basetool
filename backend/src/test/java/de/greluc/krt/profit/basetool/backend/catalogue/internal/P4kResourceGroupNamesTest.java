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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kResourceGroupDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Resource groups sharing a name stay distinguishable by their class name (REQ-P4K-025). */
class P4kResourceGroupNamesTest {

  private static final String DATABASE =
      "libs/foundry/records/resourcetypedatabase/resourcetypedatabase";

  private static final P4kResourceGroupDto SYNTHETIC =
      group("361e993d-9b2e-450c-9315-0fd1bfd3af20", "SyntheticMaterials", "Man-made");

  private static final P4kResourceGroupDto BULK =
      group("5ae71dc9-d210-42bc-9f2a-1188c3de0aec", "Bulk_Supplies", "man-made");

  private static final P4kResourceGroupDto METAL =
      group("9b28b2e4-7df3-43f3-a69a-d82b119e25bf", "Metal", "Metal");

  private static P4kResourceGroupDto group(String guid, String className, String name) {
    return new P4kResourceGroupDto(
        guid,
        className,
        DATABASE,
        new P4kLocalisedText("@test", name, "Hergestellte Produkte"),
        null);
  }

  @Test
  void ofGroupsSharingAName_theLowestGuidKeepsItAndTheOthersTakeTheirClassName() {
    P4kResourceGroupNames names = P4kResourceGroupNames.of(List.of(BULK, METAL, SYNTHETIC));

    assertThat(names.english(SYNTHETIC)).isEqualTo("Man-made");
    assertThat(names.english(BULK)).isEqualTo("man-made (Bulk Supplies)");
    assertThat(names.english(METAL)).isEqualTo("Metal");
    assertThat(names.german(SYNTHETIC)).isEqualTo("Hergestellte Produkte");
    assertThat(names.german(BULK)).isEqualTo("Hergestellte Produkte (Bulk Supplies)");
    assertThat(names.german(METAL)).isEqualTo("Hergestellte Produkte (Metal)");
  }

  @Test
  void theOrderOfTheCatalogue_doesNotDecideWhichGroupKeepsThePlainName() {
    P4kResourceGroupNames forward = P4kResourceGroupNames.of(List.of(SYNTHETIC, BULK));
    P4kResourceGroupNames backward = P4kResourceGroupNames.of(List.of(BULK, SYNTHETIC));

    assertThat(backward.english(BULK)).isEqualTo(forward.english(BULK));
    assertThat(backward.english(SYNTHETIC)).isEqualTo(forward.english(SYNTHETIC));
  }

  @Test
  void aClassNameReadsAsWords() {
    assertThat(P4kResourceGroupNames.readable("Bulk_Supplies")).isEqualTo("Bulk Supplies");
    assertThat(P4kResourceGroupNames.readable("SyntheticMaterials"))
        .isEqualTo("Synthetic Materials");
    assertThat(P4kResourceGroupNames.readable("ShipAmmoSize1")).isEqualTo("Ship Ammo Size1");
  }
}
