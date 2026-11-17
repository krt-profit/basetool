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

import org.junit.jupiter.api.Test;

/** The other names a UEX location row matches a starmap object by (REQ-P4K-035). */
class StarmapNameKeysTest {

  @Test
  void aStarSystemGroup_isStrippedAsASecondName_andTheNicknameComesLast() {
    StarmapPlaceRow station =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.SPACE_STATION, "Pyro Gateway (Stanton)")
            .nickname("Gateway")
            .build();

    assertThat(StarmapNameKeys.names(station))
        .containsExactly("Pyro Gateway (Stanton)", "Pyro Gateway", "Gateway");
  }

  @Test
  void onlyTheRowsOwnStarSystem_isStripped() {
    assertThat(StarmapNameKeys.withoutSystemGroup("Pyro Gateway (Stanton system)", "Stanton"))
        .isEqualTo("Pyro Gateway");
    assertThat(StarmapNameKeys.withoutSystemGroup("Stanton (Star)", "Stanton")).isNull();
    assertThat(StarmapNameKeys.withoutSystemGroup("Nyx Gateway (Pyro)", "Stanton")).isNull();
  }

  @Test
  void aNameThatNormalisesLikeAnEarlierOne_isLeftOut() {
    StarmapPlaceRow outpost =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.OUTPOST, "Shady Glen")
            .nickname("shady  glen")
            .build();

    assertThat(StarmapNameKeys.names(outpost)).containsExactly("Shady Glen");
  }

  @Test
  void theLetterO_readsAsZero_onlyInANameWithADigit() {
    assertThat(StarmapNameKeys.folded("Mining Base #4RO-NVX"))
        .isEqualTo(StarmapNameKeys.folded("Mining Base #4R0-NVX"));
    assertThat(StarmapNameKeys.folded("Port Olisar")).isNull();
  }

  @Test
  void aNameExtends_anotherByWholeTrailingWordsOnly() {
    assertThat(StarmapNameKeys.extendsByWords("deakins research", "deakins research outpost"))
        .isTrue();
    assertThat(StarmapNameKeys.extendsByWords("kudre ore mine closed", "kudre ore")).isTrue();
    assertThat(StarmapNameKeys.extendsByWords("deakins research", "deakins researcher")).isFalse();
  }
}
