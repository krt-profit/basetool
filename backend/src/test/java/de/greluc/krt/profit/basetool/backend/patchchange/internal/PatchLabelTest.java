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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A patch label is a version of two or three numbers, compared number by number (REQ-PATCH-001).
 */
class PatchLabelTest {

  @ParameterizedTest
  @ValueSource(strings = {"4.4", "4.4.1", "10.0.25", "4.10"})
  void aVersionOfTwoOrThreeNumbersIsALabel(String value) {
    assertThat(PatchLabel.parse(value).value()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"4", "4.4.1.2", "v4.4", "4.4-ptu", "", " 4.4", "4..4", "4.4."})
  void anythingElseIsRefused(String value) {
    assertThatThrownBy(() -> PatchLabel.parse(value))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.patch_change.label_invalid");
  }

  @Test
  void numbersCompareAsNumbersNotAsText() {
    assertThat(PatchLabel.parse("4.10").isAfter(PatchLabel.parse("4.9"))).isTrue();
    assertThat(PatchLabel.parse("4.9").isAfter(PatchLabel.parse("4.10"))).isFalse();
  }

  @Test
  void aMissingPatchNumberCountsAsZero() {
    assertThat(PatchLabel.parse("4.4.1").isAfter(PatchLabel.parse("4.4"))).isTrue();
    assertThat(PatchLabel.parse("4.4.0").isAfter(PatchLabel.parse("4.4"))).isFalse();
    assertThat(PatchLabel.parse("4.4").isAfter(PatchLabel.parse("4.4.0"))).isFalse();
  }
}
