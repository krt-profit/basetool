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

/** Unit tests for {@link P4kText} and the localised texts that decode through it (REQ-P4K-045). */
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
  void localisedTexts_decodeBothLanguages() {
    P4kLocalisedText text = new P4kLocalisedText("@key", "a\\nb", "c\\nd");

    assertThat(text.en()).isEqualTo("a\nb");
    assertThat(text.de()).isEqualTo("c\nd");
    assertThat(text.key()).isEqualTo("@key");
  }

  @Test
  void aVersion2TextThatAlreadyHoldsALineFeed_isStoredUnchanged() {
    P4kLocalisedText text = new P4kLocalisedText(null, "NDR: 08\nEffect: Toxic", null);

    assertThat(text.en()).isEqualTo("NDR: 08\nEffect: Toxic");
    assertThat(text.de()).isNull();
  }
}
