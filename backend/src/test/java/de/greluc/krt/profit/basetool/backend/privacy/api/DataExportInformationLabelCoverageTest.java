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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Activity;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Retention;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Source;
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
 * Verifies that every text the Art. 15(1) and (2) information of the data export resolves exists in
 * all three backend bundles, and that no bundle keeps a text nothing resolves (REQ-SEC-058).
 *
 * <p>A missing key resolves to the key itself, so without this check the member would read {@code
 * pdf.export.retention.CONNECTION} where the retention period belongs.
 */
class DataExportInformationLabelCoverageTest {

  /** The key prefixes whose every key the information or the PDF resolves. */
  private static final List<String> OWNED_PREFIXES =
      List.of(
          DataExportInformationService.ACTIVITY,
          DataExportInformationService.RETENTION,
          DataExportInformationService.SOURCE,
          DataExportInformationService.INFO,
          DataExportInformationService.NOT_INCLUDED);

  private static Properties bundle(String file) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources", file))) {
      properties.load(in);
    }
    return properties;
  }

  /**
   * Every key the information and the PDF's information block resolve.
   *
   * @return the keys, sorted
   */
  static Set<String> resolvedKeys() {
    Set<String> keys = new TreeSet<>();
    for (Activity activity : Activity.values()) {
      String prefix = DataExportInformationService.ACTIVITY + activity.name();
      keys.add(prefix + ".name");
      keys.add(prefix + ".purpose");
      keys.add(prefix + ".basis");
    }
    keys.add(DataExportInformationService.ACTIVITY + "purposeLabel");
    keys.add(DataExportInformationService.ACTIVITY + "basisLabel");
    for (Retention retention : Retention.values()) {
      keys.add(DataExportInformationService.RETENTION + retention.name());
    }
    for (Source source : Source.values()) {
      keys.add(DataExportInformationService.SOURCE + source.name());
    }
    String info = DataExportInformationService.INFO;
    for (String recipient : DataExportInformationService.RECIPIENTS) {
      keys.add(info + "recipient." + recipient);
    }
    for (String text :
        List.of(
            "heading",
            "recipients",
            "rights",
            "rights.text",
            "complaint",
            "complaint.text",
            "automatedDecisions",
            "automatedDecisions.text",
            "thirdCountry",
            "thirdCountry.discord",
            "thirdCountry.none")) {
      keys.add(info + text);
    }
    keys.add("pdf.export.outOfScope");
    for (String surface : DataExportService.OUT_OF_SCOPE) {
      keys.add(DataExportInformationService.NOT_INCLUDED + surface);
    }
    return keys;
  }

  private static Set<String> missing(Set<String> keys, Properties bundle) {
    Set<String> missing = new TreeSet<>();
    for (String key : keys) {
      String text = bundle.getProperty(key);
      if (text == null || text.isBlank() || text.equals(key)) {
        missing.add(key);
      }
    }
    return missing;
  }

  private static Set<String> stale(Set<String> keys, Properties bundle) {
    Set<String> stale = new TreeSet<>();
    for (String name : bundle.stringPropertyNames()) {
      boolean owned = OWNED_PREFIXES.stream().anyMatch(name::startsWith);
      if (owned && !keys.contains(name)) {
        stale.add(name);
      }
    }
    return stale;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"messages.properties", "messages_de.properties", "messages_en.properties"})
  void everyInformationTextExists(String file) throws IOException {
    assertThat(missing(resolvedKeys(), bundle(file)))
        .as(
            "Every activity, retention rule, source and information text of the data export needs"
                + " its key in all three backend bundles; a missing key prints the key itself.")
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"messages.properties", "messages_de.properties", "messages_en.properties"})
  void noBundleKeepsAnInformationTextNothingResolves(String file) throws IOException {
    assertThat(stale(resolvedKeys(), bundle(file)))
        .as("an information text for an activity, rule, source or surface that no longer exists")
        .isEmpty();
  }

  @Test
  void proofAMissingAndAStaleTextAreReported() {
    Properties planted = new Properties();
    planted.setProperty(DataExportInformationService.RETENTION + "GONE", "Entfernt");

    assertThat(missing(resolvedKeys(), planted))
        .contains(DataExportInformationService.RETENTION + Retention.CONNECTION.name());
    assertThat(stale(resolvedKeys(), planted))
        .containsExactly(DataExportInformationService.RETENTION + "GONE");
  }
}
