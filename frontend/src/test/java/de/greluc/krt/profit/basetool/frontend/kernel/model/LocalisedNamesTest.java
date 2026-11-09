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

package de.greluc.krt.profit.basetool.frontend.kernel.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.model.LocalisedNames.GameTextLanguage;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The game-text language policy picks the name a page shows (REQ-P4K-023). */
class LocalisedNamesTest {

  @Test
  void everyPageShowsTheEnglishName() {
    assertThat(LocalisedNames.SHOWN).isEqualTo(GameTextLanguage.ENGLISH);
    assertThat(LocalisedNames.display("Aslarite", "Aslarit", Locale.GERMANY)).isEqualTo("Aslarite");
    assertThat(LocalisedNames.display("Aslarite", "Aslarit", Locale.ENGLISH)).isEqualTo("Aslarite");
    assertThat(LocalisedNames.display("Aslarite", "Aslarit", null)).isEqualTo("Aslarite");
  }

  @Test
  void underThePageLanguageAGermanPageShowsTheGermanName() {
    assertThat(
            LocalisedNames.display(
                "Aslarite", "Aslarit", Locale.GERMANY, GameTextLanguage.PAGE_LANGUAGE))
        .isEqualTo("Aslarit");
  }

  @Test
  void underThePageLanguageAnEnglishPageShowsTheName() {
    assertThat(
            LocalisedNames.display(
                "Aslarite", "Aslarit", Locale.ENGLISH, GameTextLanguage.PAGE_LANGUAGE))
        .isEqualTo("Aslarite");
  }

  @Test
  void underThePageLanguageAGermanPageWithoutAGermanNameShowsTheName() {
    GameTextLanguage page = GameTextLanguage.PAGE_LANGUAGE;
    assertThat(LocalisedNames.display("Aslarite", null, Locale.GERMAN, page)).isEqualTo("Aslarite");
    assertThat(LocalisedNames.display("Aslarite", " ", Locale.GERMAN, page)).isEqualTo("Aslarite");
    assertThat(LocalisedNames.display("Aslarite", "Aslarit", null, page)).isEqualTo("Aslarite");
  }
}
