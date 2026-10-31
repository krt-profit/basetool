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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the export's scrub declarations against the person-mention sources (REQ-SEC-058,
 * REQ-SEC-060, ADR-0185).
 *
 * <p>Every person-name column, including the exemptions that may still hold a name, must be
 * scrubbed or exempted with a reason by the section that selects it, and every declaration must
 * name an existing column of its own section. The sections are the union of what the modules'
 * participants declare, and the SQL is parsed, not executed.
 */
class DataExportScrubCoverageTest {

  @Test
  void everyPersonNameColumnIsEitherScrubbedOrExemptedWithAReason() {
    List<String> unaccounted = new ArrayList<>();

    for (Section section : ExportSectionDeclarations.inReadingOrder()) {
      Set<String> tables = DataExportProjection.tablesOf(section.sql());
      for (Map.Entry<String, String> selected :
          DataExportProjection.selectedColumns(section.sql()).entrySet()) {
        String alias = selected.getKey();
        String column = selected.getValue();
        if (!namesAPerson(tables, column)) {
          continue;
        }
        boolean scrubbed = section.scrubs(alias);
        boolean exempted = section.unscrubbedPersonColumns().containsKey(alias);
        if (!scrubbed && !exempted) {
          unaccounted.add(section.key() + "." + alias + " (" + column + ")");
        }
      }
    }

    assertThat(unaccounted)
        .as(
            "Every export column that a PersonMentionSource registers as a place a person is named"
                + " must be scrubbed by its section (scrubbing(...)) or, with a reason, kept"
                + " (keeping(...)). Add it to whichever is true -- leaving it absent is the state"
                + " that ships a third party's name in somebody's Art. 15 export.")
        .isEmpty();
  }

  /** Verifies that the name-bearing exemptions remain a subset of the search's exempt columns. */
  @Test
  void theNameBearingExemptionsAreRealExemptions() {
    assertThat(MentionSourceDeclarations.exemptColumns())
        .as(
            "EXEMPT_BUT_MAY_HOLD_A_NAME names the exemptions that can still contain somebody's"
                + " name, so every entry must be an exemption")
        .containsAll(MentionSourceDeclarations.exemptButMayHoldNames());
  }

  @Test
  void everyScrubbedColumnExists() {
    List<String> unknown = new ArrayList<>();

    for (Section section : ExportSectionDeclarations.inReadingOrder()) {
      Set<String> aliases = DataExportProjection.selectedColumns(section.sql()).keySet();
      section.scrubbedColumns().stream()
          .filter(c -> !aliases.contains(c))
          .forEach(c -> unknown.add(section.key() + "." + c));
    }

    assertThat(unknown)
        .as(
            "A scrub entry that names a column no statement yields does nothing, silently. This is"
                + " what a renamed section key or column alias looks like: the export keeps"
                + " succeeding and keeps reporting that third-party names were removed.")
        .isEmpty();
  }

  @Test
  void everyExemptedColumnExists() {
    List<String> unknown = new ArrayList<>();

    for (Section section : ExportSectionDeclarations.inReadingOrder()) {
      Set<String> aliases = DataExportProjection.selectedColumns(section.sql()).keySet();
      section.unscrubbedPersonColumns().keySet().stream()
          .filter(c -> !aliases.contains(c))
          .forEach(c -> unknown.add(section.key() + "." + c));
    }

    assertThat(unknown)
        .as("A stale exemption reads as a considered decision about a column that is not there.")
        .isEmpty();
  }

  @Test
  void everyExemptionStatesAReason() {
    for (Section section : ExportSectionDeclarations.inReadingOrder()) {
      assertThat(section.unscrubbedPersonColumns())
          .allSatisfy((column, reason) ->
              assertThat(reason)
                  .as("reason for " + section.key() + "." + column)
                  .isNotBlank()
                  .hasSizeGreaterThan(30)
                  .doesNotContainIgnoringCase("todo"));
    }
  }

  @Test
  void noColumnIsBothScrubbedAndExempted() {
    List<String> both = new ArrayList<>();
    for (Section section : ExportSectionDeclarations.inReadingOrder()) {
      section.scrubbedColumns().stream()
          .filter(c -> section.unscrubbedPersonColumns().containsKey(c))
          .forEach(c -> both.add(section.key() + "." + c));
    }

    assertThat(both).as("a column is scrubbed or it is not; both entries cannot be true").isEmpty();
  }

  @Test
  void everySectionStatesWhyItCarriesItsLegalBasis() {
    assertThat(ExportSectionDeclarations.inReadingOrder())
        .allSatisfy(section ->
            assertThat(section.rationale())
                .as("rationale for section " + section.key())
                .isNotBlank()
                .hasSizeGreaterThan(20)
                .doesNotContainIgnoringCase("todo"));
  }

  @Test
  void everySectionCarriesALegalBasisAndAStatementOverTheSubject() {
    assertThat(ExportSectionDeclarations.inReadingOrder())
        .allSatisfy(section -> {
          assertThat(section.legalBasis()).as("legal basis of " + section.key()).isNotNull();
          assertThat(section.sql())
              .as("statement of " + section.key())
              .containsIgnoringCase(":userId");
        });
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
        MentionSourceDeclarations.targets().stream()
            .anyMatch(t -> tables.contains(t.table()) && t.column().equals(column));
    return searched
        || tables.stream()
            .anyMatch(table ->
                MentionSourceDeclarations.exemptButMayHoldNames().contains(table + "." + column));
  }
}
