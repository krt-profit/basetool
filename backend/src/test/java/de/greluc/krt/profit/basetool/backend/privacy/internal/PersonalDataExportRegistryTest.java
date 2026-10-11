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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies how {@link PersonalDataExportRegistry} orders and checks the contributed sections. */
class PersonalDataExportRegistryTest {

  private static Section section(int position, String key) {
    return new Section(
        position,
        key,
        LegalBasis.ART_15,
        "A section of the test registry.",
        "SELECT 1 FROM app_user WHERE id = :userId");
  }

  @Test
  void sectionsOfAllParticipantsComeBackAscendingByPosition() {
    PersonalDataExportSection first = () -> List.of(section(30, "c"), section(10, "a"));
    PersonalDataExportSection second = () -> List.of(section(20, "b"));

    PersonalDataExportRegistry registry = new PersonalDataExportRegistry(List.of(first, second));

    assertThat(registry.sections()).extracting(Section::key).containsExactly("a", "b", "c");
  }

  @Test
  void twoSectionsWithTheSameKeyAreRefused() {
    PersonalDataExportSection first = () -> List.of(section(10, "a"));
    PersonalDataExportSection second = () -> List.of(section(20, "a"));

    assertThatThrownBy(() -> new PersonalDataExportRegistry(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("key a");
  }

  @Test
  void twoSectionsAtTheSamePositionAreRefused() {
    PersonalDataExportSection first = () -> List.of(section(10, "a"));
    PersonalDataExportSection second = () -> List.of(section(10, "b"));

    assertThatThrownBy(() -> new PersonalDataExportRegistry(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("position 10");
  }

  @Test
  void aSectionIsImmutableAndScrubsOnlyWhatItDeclares() {
    Section scrubbing =
        section(10, "a").scrubbing("note", "name").keeping("label", "The subject's own label.");

    assertThat(scrubbing.scrubs("note")).isTrue();
    assertThat(scrubbing.scrubs("name")).isTrue();
    assertThat(scrubbing.scrubs("label")).isFalse();
    assertThat(scrubbing.unscrubbedPersonColumns()).containsOnlyKeys("label");
    assertThat(section(10, "a").scrubs("note")).isFalse();
  }

  @Test
  void everyDeclaredSectionOfTheApplicationIsUniqueAndOrdered() {
    List<Section> sections = ExportSectionDeclarations.inReadingOrder();

    assertThat(sections).hasSizeGreaterThanOrEqualTo(45);
    assertThat(sections).extracting(Section::position).isSorted().doesNotHaveDuplicates();
    assertThat(sections).extracting(Section::key).doesNotHaveDuplicates();
  }
}
