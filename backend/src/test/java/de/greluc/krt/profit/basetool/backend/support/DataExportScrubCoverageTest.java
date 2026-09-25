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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.support.DataExportSections.Section;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the export's scrub registry against {@link PersonSearchTargets} (REQ-SEC-058,
 * REQ-SEC-060, ADR-0185).
 *
 * <p>Every person-name column, including {@link PersonSearchTargets#EXEMPT_BUT_MAY_HOLD_A_NAME},
 * must be scrubbed or exempted with a reason, and every registry entry must name an existing
 * section and column. The SQL is parsed, not executed.
 */
class DataExportScrubCoverageTest {

  @Test
  void everyPersonNameColumnIsEitherScrubbedOrExemptedWithAReason() {
    List<String> unaccounted = new ArrayList<>();

    for (Section section : DataExportSections.SECTIONS) {
      Set<String> tables = DataExportProjection.tablesOf(section.sql());
      for (Map.Entry<String, String> selected :
          DataExportProjection.selectedColumns(section.sql()).entrySet()) {
        String alias = selected.getKey();
        String column = selected.getValue();
        if (!namesAPerson(tables, column)) {
          continue;
        }
        boolean scrubbed = DataExportSections.isScrubbed(section.key(), alias);
        boolean exempted =
            DataExportSections.UNSCRUBBED_PERSON_COLUMNS.containsKey(section.key() + "." + alias);
        if (!scrubbed && !exempted) {
          unaccounted.add(section.key() + "." + alias + " (" + column + ")");
        }
      }
    }

    assertThat(unaccounted)
        .as(
            "Every export column that PersonSearchTargets registers as a place a person is named"
                + " must be listed in DataExportSections.FREE_TEXT_COLUMNS or, with a reason, in"
                + " UNSCRUBBED_PERSON_COLUMNS. Add it to whichever is true -- leaving it absent is"
                + " the state that ships a third party's name in somebody's Art. 15 export.")
        .isEmpty();
  }

  /** Verifies that the name-bearing exemptions remain a subset of the search's exempt columns. */
  @Test
  void theNameBearingExemptionsAreRealExemptions() {
    assertThat(PersonSearchTargets.EXEMPT_COLUMNS)
        .as(
            "EXEMPT_BUT_MAY_HOLD_A_NAME names the exemptions that can still contain somebody's"
                + " name, so every entry must be an exemption")
        .containsAll(PersonSearchTargets.EXEMPT_BUT_MAY_HOLD_A_NAME);
  }

  @Test
  void everyScrubbedColumnExists() {
    Map<String, Set<String>> aliasesBySection = aliasesBySection();
    List<String> unknown = new ArrayList<>();

    DataExportSections.FREE_TEXT_COLUMNS.forEach(
        (sectionKey, columns) -> {
          Set<String> aliases = aliasesBySection.get(sectionKey);
          if (aliases == null) {
            unknown.add(sectionKey + " (no such section)");
            return;
          }
          columns.stream()
              .filter(c -> !aliases.contains(c))
              .forEach(c -> unknown.add(sectionKey + "." + c));
        });

    assertThat(unknown)
        .as(
            "A scrub entry that names a column no statement yields does nothing, silently. This is"
                + " what a renamed section key or column alias looks like: the export keeps"
                + " succeeding and keeps reporting that third-party names were removed.")
        .isEmpty();
  }

  @Test
  void everyExemptedColumnExists() {
    Map<String, Set<String>> aliasesBySection = aliasesBySection();
    List<String> unknown = new ArrayList<>();

    for (String key : DataExportSections.UNSCRUBBED_PERSON_COLUMNS.keySet()) {
      int dot = key.lastIndexOf('.');
      assertThat(dot).as("exemption keys are section.column: " + key).isGreaterThan(0);
      String sectionKey = key.substring(0, dot);
      String column = key.substring(dot + 1);
      Set<String> aliases = aliasesBySection.get(sectionKey);
      if (aliases == null || !aliases.contains(column)) {
        unknown.add(key);
      }
    }

    assertThat(unknown)
        .as("A stale exemption reads as a considered decision about a column that is not there.")
        .isEmpty();
  }

  @Test
  void everyExemptionStatesAReason() {
    assertThat(DataExportSections.UNSCRUBBED_PERSON_COLUMNS)
        .allSatisfy(
            (key, reason) ->
                assertThat(reason)
                    .as("reason for " + key)
                    .isNotBlank()
                    .hasSizeGreaterThan(30)
                    .doesNotContainIgnoringCase("todo"));
  }

  @Test
  void noColumnIsBothScrubbedAndExempted() {
    List<String> both = new ArrayList<>();
    DataExportSections.FREE_TEXT_COLUMNS.forEach(
        (sectionKey, columns) ->
            columns.stream()
                .map(c -> sectionKey + "." + c)
                .filter(DataExportSections.UNSCRUBBED_PERSON_COLUMNS::containsKey)
                .forEach(both::add));

    assertThat(both).as("a column is scrubbed or it is not; both entries cannot be true").isEmpty();
  }

  @Test
  void everySectionStatesWhyItCarriesItsLegalBasis() {
    assertThat(DataExportSections.SECTIONS)
        .allSatisfy(
            section ->
                assertThat(section.rationale())
                    .as("rationale for section " + section.key())
                    .isNotBlank()
                    .hasSizeGreaterThan(20)
                    .doesNotContainIgnoringCase("todo"));
  }

  @Test
  void everySectionCarriesAKnownLegalBasis() {
    assertThat(DataExportSections.SECTIONS)
        .allSatisfy(
            section ->
                assertThat(section.legalBasis())
                    .as("legal basis for section " + section.key())
                    .isIn(DataExportSections.ART_15, DataExportSections.ART_15_20));
  }

  /**
   * Every section's selected aliases, keyed by section.
   *
   * @return section key to the set of aliases its statement yields
   */
  private static Map<String, Set<String>> aliasesBySection() {
    Map<String, Set<String>> out = new LinkedHashMap<>();
    for (Section section : DataExportSections.SECTIONS) {
      out.put(section.key(), DataExportProjection.selectedColumns(section.sql()).keySet());
    }
    return out;
  }

  /**
   * Whether any table this statement reads has this column as a searched person-name target or as
   * an exemption that may still hold a name. Matched by column name across all tables, which can
   * over-flag but never under-flag.
   *
   * @param tables the tables the statement reads
   * @param column the underlying column name
   * @return {@code true} when the pair is a searched target, or an exemption that can still hold a
   *     name
   */
  private static boolean namesAPerson(Set<String> tables, String column) {
    boolean searched =
        PersonSearchTargets.TARGETS.stream()
            .anyMatch(t -> tables.contains(t.table()) && t.column().equals(column));
    return searched
        || tables.stream()
            .anyMatch(
                table ->
                    PersonSearchTargets.EXEMPT_BUT_MAY_HOLD_A_NAME.contains(table + "." + column));
  }
}
