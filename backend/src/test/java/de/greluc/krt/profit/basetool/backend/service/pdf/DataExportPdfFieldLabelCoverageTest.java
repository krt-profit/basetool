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

import de.greluc.krt.profit.basetool.backend.support.DataExportProjection;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every column the Art. 15 PDF prints by name has a German {@code pdf.export.field.*}
 * label (REQ-SEC-058).
 *
 * <p>Checked against the section projections parsed with {@link DataExportProjection}, since the
 * report falls back to the raw key for a missing label; no container or schema is needed.
 */
class DataExportPdfFieldLabelCoverageTest {

  /** The bundle the PDF's labels are resolved from — German, like the document. */
  private static final Path DEFAULT_BUNDLE = Path.of("src/main/resources/messages.properties");

  /** The key prefix one field label sits under. */
  private static final String PREFIX = "pdf.export.field.";

  private static Properties bundle() throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(DEFAULT_BUNDLE)) {
      properties.load(in);
    }
    return properties;
  }

  /** Every alias the four verbatim sections yield, deduplicated across them. */
  private static Set<String> printedFieldNames() {
    Set<String> out = new LinkedHashSet<>();
    for (DataExportSections.Section section : DataExportSections.SECTIONS) {
      if (!DataExportPdfFormat.VERBATIM_SECTIONS.contains(section.key())) {
        continue;
      }
      out.addAll(DataExportProjection.selectedColumns(section.sql()).keySet());
    }
    return out;
  }

  @Test
  void everyPrintedFieldNameHasAGermanLabel() throws IOException {
    Properties bundle = bundle();
    List<String> missing = new ArrayList<>();

    for (String field : printedFieldNames()) {
      if (bundle.getProperty(PREFIX + field) == null) {
        missing.add(PREFIX + field);
      }
    }

    assertThat(missing)
        .as(
            "Every column one of the PDF's verbatim sections selects is printed to the member by"
                + " name. A missing key resolves to the key itself, so the member reads the SQL"
                + " alias -- which is how discord_guild_nickname and share_blueprints_globally"
                + " reached an Art. 15 document. Add the label to all three backend bundles.")
        .isEmpty();
  }

  @Test
  void everyFieldLabelIsAColumnSomethingPrints() throws IOException {
    Set<String> printed = printedFieldNames();
    List<String> stale = new ArrayList<>();

    for (String key : bundle().stringPropertyNames()) {
      if (key.startsWith(PREFIX) && !printed.contains(key.substring(PREFIX.length()))) {
        stale.add(key);
      }
    }

    assertThat(stale)
        .as(
            "A field label for a column no verbatim section selects is dead weight that reads as a"
                + " considered translation. Remove it, or add the column back to the projection.")
        .isEmpty();
  }

  /** A label must not be the alias again, which would satisfy the first test and fix nothing. */
  @Test
  void noFieldLabelIsJustTheColumnName() throws IOException {
    Properties bundle = bundle();
    List<String> identity = new ArrayList<>();

    for (String field : printedFieldNames()) {
      String label = bundle.getProperty(PREFIX + field);
      if (field.equals(label)) {
        identity.add(field);
      }
    }

    assertThat(identity)
        .as("a label equal to its own column name is the defect written down, not a translation")
        .isEmpty();
  }
}
