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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the places the admin Personensuche looks for a name (REQ-SEC-060) against a committed
 * catalogue: every searched column with its area, id column and link kind, and the columns and
 * tables deliberately left out.
 *
 * <p>The catalogue is {@code privacy/person-mention-sources.txt}, one line per entry: {@code
 * target|area|table|column|idColumn|linkKind}, {@code exempt-column|table.column}, {@code
 * exempt-name-holder|table.column} or {@code exempt-table|table}, each kind sorted. The test holds
 * whoever declares the places to this catalogue, so moving a declaration between modules cannot
 * change what the search reaches. A deliberate change updates the catalogue in the same commit.
 */
class PersonMentionCatalogueTest {

  private static final String CATALOGUE = "/privacy/person-mention-sources.txt";

  @Test
  @DisplayName("the declared places equal the committed catalogue")
  void theDeclaredPlacesEqualTheCommittedCatalogue() throws IOException {
    List<String> declared = declaredLines();
    Files.writeString(
        Path.of("build/person-mention-sources.actual.txt"), String.join("\n", declared) + "\n");

    assertThat(differences(declared, committedLines()))
        .as("The searched columns and the exemptions against %s", CATALOGUE)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a changed link kind, a missing and an extra entry differ")
  void aChangedLinkKindAMissingAndAnExtraEntryDiffer() {
    List<String> committed = committedLines();
    List<String> changed = new ArrayList<>(committed);
    changed.set(0, committed.get(0) + "|PLANTED");
    assertThat(differences(changed, committed)).hasSize(1);

    assertThat(differences(committed.subList(1, committed.size()), committed)).isNotEmpty();

    List<String> extra = new ArrayList<>(committed);
    extra.add("exempt-table|planted");
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
    new TreeSet<>(
            PersonSearchTargets.TARGETS.stream()
                .map(
                    t ->
                        "target|"
                            + t.area()
                            + "|"
                            + t.table()
                            + "|"
                            + t.column()
                            + "|"
                            + t.idColumn()
                            + "|"
                            + t.linkKind())
                .toList())
        .forEach(lines::add);
    add(lines, "exempt-column|", PersonSearchTargets.EXEMPT_COLUMNS);
    add(lines, "exempt-name-holder|", PersonSearchTargets.EXEMPT_BUT_MAY_HOLD_A_NAME);
    add(lines, "exempt-table|", PersonSearchTargets.EXEMPT_TABLES);
    return lines;
  }

  private static void add(List<String> lines, String prefix, Collection<String> entries) {
    new TreeSet<>(entries).forEach(entry -> lines.add(prefix + entry));
  }

  private static List<String> committedLines() {
    try (InputStream in = PersonMentionCatalogueTest.class.getResourceAsStream(CATALOGUE)) {
      assertThat(in).as("the catalogue " + CATALOGUE).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank())
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
