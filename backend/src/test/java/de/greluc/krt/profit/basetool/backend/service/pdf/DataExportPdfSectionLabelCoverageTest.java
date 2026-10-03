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

package de.greluc.krt.profit.basetool.backend.service.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.support.DataExportSections;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that every export section has a {@code pdf.export.section.*} label in all three backend
 * bundles, since the PDF inventory names each section by it and a missing key prints the raw key
 * (REQ-SEC-058).
 */
class DataExportPdfSectionLabelCoverageTest {

  /** The key prefix one section label sits under. */
  private static final String PREFIX = "pdf.export.section.";

  /** Today's number of sections; an emptied registry must not pass vacuously. */
  private static final int SECTION_FLOOR = 44;

  private static Properties bundle(String file) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources", file))) {
      properties.load(in);
    }
    return properties;
  }

  private static List<String> sectionKeys() {
    return DataExportSections.SECTIONS.stream().map(DataExportSections.Section::key).toList();
  }

  /**
   * The section keys a bundle cannot label.
   *
   * @param keys the section keys
   * @param bundle the loaded bundle
   * @return the missing label keys, sorted
   */
  static Set<String> missingLabels(List<String> keys, Properties bundle) {
    Set<String> missing = new TreeSet<>();
    for (String key : keys) {
      String label = bundle.getProperty(PREFIX + key);
      if (label == null || label.isBlank() || label.equals(key)) {
        missing.add(PREFIX + key);
      }
    }
    return missing;
  }

  /**
   * The section labels a bundle holds for no section.
   *
   * @param keys the section keys
   * @param bundle the loaded bundle
   * @return the stale label keys, sorted
   */
  static Set<String> staleLabels(List<String> keys, Properties bundle) {
    Set<String> stale = new TreeSet<>();
    for (String name : bundle.stringPropertyNames()) {
      if (name.startsWith(PREFIX) && !keys.contains(name.substring(PREFIX.length()))) {
        stale.add(name);
      }
    }
    return stale;
  }

  @Test
  void theRegistryIsNotEmpty() {
    assertThat(sectionKeys()).hasSizeGreaterThanOrEqualTo(SECTION_FLOOR).doesNotHaveDuplicates();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"messages.properties", "messages_de.properties", "messages_en.properties"})
  void everySectionHasALabel(String file) throws IOException {
    assertThat(missingLabels(sectionKeys(), bundle(file)))
        .as(
            "Every export section is named in the PDF inventory by its pdf.export.section.* key,"
                + " and a missing key prints the key itself. Add the label to all three backend"
                + " bundles.")
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"messages.properties", "messages_de.properties", "messages_en.properties"})
  void everySectionLabelNamesASection(String file) throws IOException {
    assertThat(staleLabels(sectionKeys(), bundle(file)))
        .as("a section label for a section that no longer exists")
        .isEmpty();
  }

  @Test
  void proofAMissingAndAStaleLabelAreReported() {
    Properties planted = new Properties();
    planted.setProperty(PREFIX + "account", "Stammdaten");
    planted.setProperty(PREFIX + "identity", "identity");
    planted.setProperty(PREFIX + "gone", "Entfernt");

    assertThat(missingLabels(List.of("account", "planted", "identity"), planted))
        .containsExactly(PREFIX + "identity", PREFIX + "planted");
    assertThat(staleLabels(List.of("account", "identity"), planted))
        .containsExactly(PREFIX + "gone");
  }
}
