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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ItemAttributeFilter} (REQ-INV-063). */
class ItemAttributeFilterTest {

  @Test
  void none_narrowsNothing() {
    ItemAttributeFilter filter = ItemAttributeFilter.NONE;

    assertThat(filter.isEmpty()).isTrue();
    assertThat(filter.hasKinds()).isFalse();
    assertThat(filter.kindsOrNull()).isNull();
    assertThat(filter.hasWikiTypes()).isFalse();
    assertThat(filter.wikiTypesOrNull()).isNull();
    assertThat(filter.hasSizes()).isFalse();
    assertThat(filter.sizesOrNull()).isNull();
    assertThat(filter.hasGrades()).isFalse();
    assertThat(filter.gradesOrNull()).isNull();
  }

  @Test
  void nullAndEmptyLists_narrowNothing() {
    ItemAttributeFilter filter =
        new ItemAttributeFilter(List.of(), null, Arrays.asList((Integer) null), List.of(" "));

    assertThat(filter.isEmpty()).isTrue();
  }

  @Test
  void kinds_areForwardedDistinct() {
    ItemAttributeFilter filter =
        new ItemAttributeFilter(
            List.of(GameItemKind.WEAPON, GameItemKind.ARMOR, GameItemKind.WEAPON),
            null,
            null,
            null);

    assertThat(filter.hasKinds()).isTrue();
    assertThat(filter.kindsOrNull()).containsExactly(GameItemKind.WEAPON, GameItemKind.ARMOR);
    assertThat(filter.hasWikiTypes()).isFalse();
  }

  @Test
  void componentTypes_resolveToTheirWikiTypes() {
    ItemAttributeFilter filter =
        new ItemAttributeFilter(
            null, List.of(ShipComponentType.QUANTUM_DRIVE, ShipComponentType.SHIELD), null, null);

    assertThat(filter.hasWikiTypes()).isTrue();
    assertThat(filter.wikiTypesOrNull()).containsExactly("QuantumDrive", "Shield");
  }

  @Test
  void sizeAlone_narrowsToEveryComponentWikiType() {
    ItemAttributeFilter filter = new ItemAttributeFilter(null, null, List.of(2, 2, 1), null);

    assertThat(filter.sizesOrNull()).containsExactly(2, 1);
    assertThat(filter.hasWikiTypes()).isTrue();
    assertThat(filter.wikiTypesOrNull())
        .containsExactlyElementsOf(
            Arrays.stream(ShipComponentType.values()).map(ShipComponentType::getWikiType).toList());
  }

  @Test
  void gradeAlone_isTrimmedAndNarrowsToComponents() {
    ItemAttributeFilter filter =
        new ItemAttributeFilter(null, null, null, List.of(" A", "B ", "A"));

    assertThat(filter.gradesOrNull()).containsExactly("A", "B");
    assertThat(filter.hasWikiTypes()).isTrue();
  }

  @Test
  void componentTypeWithSize_keepsTheChosenTypesOnly() {
    ItemAttributeFilter filter =
        new ItemAttributeFilter(null, List.of(ShipComponentType.QUANTUM_DRIVE), List.of(2), null);

    assertThat(filter.wikiTypesOrNull()).containsExactly("QuantumDrive");
    assertThat(filter.sizesOrNull()).containsExactly(2);
  }
}
