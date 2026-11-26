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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Activity;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Processing;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Retention;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Source;
import de.greluc.krt.profit.basetool.backend.privacy.internal.ExportSectionDeclarations;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies how {@link PersonalDataExportRegistry} orders and checks the contributed sections. */
class PersonalDataExportRegistryTest {

  private static Section section(int position, String key) {
    return undeclared(position, key)
        .processedAs(Activity.ACCOUNT, Retention.ACCOUNT_LIFETIME, Source.MEMBER);
  }

  private static Section undeclared(int position, String key) {
    return new Section(
        position,
        key,
        Right.ART_15,
        "A section of the test registry.",
        "SELECT 1 FROM app_user WHERE id = :userId");
  }

  @Test
  void aSectionWithoutAProcessingIsRefused() {
    PersonalDataExportSection participant = () -> List.of(undeclared(10, "a"));

    assertThatThrownBy(() -> new PersonalDataExportRegistry(List.of(participant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("a names no processing");
  }

  @Test
  void aProcessingKeepsItsDeclarationThroughScrubbingAndKeeping() {
    Section declared =
        section(10, "a")
            .scrubbing("note")
            .keeping("label", "The subject's own label, kept as written.");

    assertThat(declared.processing())
        .isEqualTo(
            new Processing(Activity.ACCOUNT, Retention.ACCOUNT_LIFETIME, Set.of(Source.MEMBER)));
  }

  @Test
  void aPortableSectionOfAnActivityWithoutConsentOrContractIsRefused() {
    Section bank =
        portable(10, "bank")
            .processedAs(Activity.BANKING, Retention.ACCOUNT_LIFETIME, Source.MEMBER);
    PersonalDataExportSection participant = () -> List.of(bank);

    assertThatThrownBy(() -> new PersonalDataExportRegistry(List.of(participant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bank is marked portable");
  }

  @Test
  void aPortableSectionWhoseRowsOthersRecordedIsRefused() {
    Section assigned =
        portable(10, "assigned")
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.OTHER_MEMBERS);
    PersonalDataExportSection participant = () -> List.of(assigned);

    assertThatThrownBy(() -> new PersonalDataExportRegistry(List.of(participant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("assigned is marked portable");
  }

  @Test
  void aPortableSectionTheMemberOrTheirApplicationProvidedIsAccepted() {
    Section entered =
        portable(10, "entered")
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER);
    Section synced =
        portable(20, "synced")
            .processedAs(
                Activity.CONNECTED_APPLICATIONS,
                Retention.CONNECTION,
                Source.CONNECTED_APPLICATION,
                Source.SYSTEM);

    assertThat(new PersonalDataExportRegistry(List.of(() -> List.of(entered, synced))).sections())
        .extracting(Section::key)
        .containsExactly("entered", "synced");
  }

  private static Section portable(int position, String key) {
    return new Section(
        position,
        key,
        Right.ART_15_20,
        "A portable section of the test registry.",
        "SELECT 1 FROM app_user WHERE id = :userId");
  }

  @Test
  void aProcessingWithoutASourceIsRefused() {
    assertThatThrownBy(() -> new Processing(Activity.ACCOUNT, Retention.ACCOUNT_LIFETIME, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
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
