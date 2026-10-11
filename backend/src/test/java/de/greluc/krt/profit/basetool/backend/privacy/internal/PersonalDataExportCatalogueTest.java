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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the Art. 15 / Art. 20 export (REQ-SEC-058) section by section against a committed catalogue:
 * the reading order, each section's key and legal basis, a digest of its statement, the columns it
 * scrubs and the person-name columns it exempts.
 *
 * <p>The catalogue is {@code privacy/personal-data-export-sections.txt}, one line per section:
 * {@code order|key|basis|statementDigest|scrubbed|exempted}. The test holds whoever declares the
 * sections to this catalogue, so moving a declaration between modules cannot change the export. A
 * deliberate change to a section updates the catalogue in the same commit.
 */
class PersonalDataExportCatalogueTest {

  private static final String CATALOGUE = "/privacy/personal-data-export-sections.txt";

  @Test
  @DisplayName("the declared sections equal the committed catalogue, in reading order")
  void theDeclaredSectionsEqualTheCommittedCatalogue() {
    assertThat(differences(declaredLines(), committedLines()))
        .as(
            "The export's sections, their order, legal bases, statements and scrub declarations"
                + " against %s",
            CATALOGUE)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a swapped pair, an edited statement, a missing and an extra section differ")
  void aSwappedPairAnEditedStatementAMissingAndAnExtraSectionDiffer() {
    List<String> committed = committedLines();
    List<String> swapped = new ArrayList<>(committed);
    swapped.set(3, committed.get(4));
    swapped.set(4, committed.get(3));
    assertThat(differences(swapped, committed)).isNotEmpty();

    List<String> edited = new ArrayList<>(committed);
    edited.set(0, committed.get(0).replaceAll("\\|[0-9a-f]{16}\\|", "|0000000000000000|"));
    assertThat(differences(edited, committed)).hasSize(1);

    assertThat(differences(committed.subList(1, committed.size()), committed)).isNotEmpty();

    List<String> extra = new ArrayList<>(committed);
    extra.add("99|planted|ART_15|0000000000000000||");
    assertThat(differences(extra, committed)).isNotEmpty();
  }

  /**
   * Compares the declared lines with the committed ones.
   *
   * @param declared what the code declares
   * @param committed what the catalogue holds
   * @return one message per line that differs or is missing on either side, empty when equal
   */
  static List<String> differences(List<String> declared, List<String> committed) {
    List<String> out = new ArrayList<>();
    int common = Math.min(declared.size(), committed.size());
    for (int i = 0; i < common; i++) {
      if (!declared.get(i).equals(committed.get(i))) {
        out.add(
            "line "
                + (i + 1)
                + ": declared "
                + declared.get(i)
                + " but committed "
                + committed.get(i));
      }
    }
    for (int i = common; i < declared.size(); i++) {
      out.add("line " + (i + 1) + ": declared but not committed: " + declared.get(i));
    }
    for (int i = common; i < committed.size(); i++) {
      out.add("line " + (i + 1) + ": committed but not declared: " + committed.get(i));
    }
    return out;
  }

  private static List<String> declaredLines() {
    List<String> lines = new ArrayList<>();
    int order = 0;
    for (Declared section : Declared.inReadingOrder()) {
      order++;
      lines.add(
          order
              + "|"
              + section.key()
              + "|"
              + section.basis()
              + "|"
              + digest(section.sql())
              + "|"
              + String.join(",", new TreeSet<>(section.scrubbed()))
              + "|"
              + String.join(",", new TreeSet<>(section.exempted())));
    }
    return lines;
  }

  private static List<String> committedLines() {
    try (InputStream in = PersonalDataExportCatalogueTest.class.getResourceAsStream(CATALOGUE)) {
      assertThat(in).as("the catalogue " + CATALOGUE).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank())
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String digest(String sql) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(sql.replaceAll("\\s+", " ").trim().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * One declared section as the catalogue sees it.
   *
   * @param key the section key
   * @param basis the legal basis name
   * @param sql the statement
   * @param scrubbed the scrubbed columns
   * @param exempted the exempted person-name columns
   */
  record Declared(
      String key, String basis, String sql, Set<String> scrubbed, Set<String> exempted) {

    static List<Declared> inReadingOrder() {
      List<Declared> out = new ArrayList<>();
      for (Section section : ExportSectionDeclarations.inReadingOrder()) {
        out.add(
            new Declared(
                section.key(),
                section.legalBasis().name(),
                section.sql(),
                section.scrubbedColumns(),
                section.unscrubbedPersonColumns().keySet()));
      }
      return out;
    }
  }
}
