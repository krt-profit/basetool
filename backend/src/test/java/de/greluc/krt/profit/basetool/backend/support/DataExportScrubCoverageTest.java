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
 * Holds the export's scrub registry against the person-search registry, so the two cannot disagree
 * about the same question (REQ-SEC-058, REQ-SEC-060, ADR-0185).
 *
 * <p><b>Why this is a test and not a convention.</b> Which export columns get scrubbed used to be a
 * hand-kept list of section keys with nothing tying it to the sections themselves. Two failures
 * followed from that and both reached review: {@code notificationRuleTargets} selected an
 * administrator's free text and was not in the list, and a renamed section key would have dropped
 * silently out of the scrub set while the export still succeeded and still reported the removal as
 * complete. {@link PersonSearchTargets} already answers "is this column a place a person is named",
 * for the whole schema, and is itself gate-enforced by {@code PersonSearchCoverageTest}. This class
 * makes the export read that answer instead of restating it.
 *
 * <p><b>Both directions are checked.</b> A person-name column must be scrubbed or exempted with a
 * reason — that is the leak direction. And every entry in either registry must name a section and a
 * column that actually exist — that is the drift direction, the one that fails silently.
 *
 * <p><b>Widened 2026-09-17.</b> The leak direction asked {@link PersonSearchTargets#TARGETS} only,
 * so a column the search exempts was invisible to it — and {@code notifications.params} is exactly
 * that: scrubbed by the export because the payload carries a handle, exempt from the search because
 * searching it returns one hit per admin inbox for the same event. It was the single entry in the
 * scrub registry whose deletion no test would have caught. The question now includes {@link
 * PersonSearchTargets#EXEMPT_BUT_MAY_HOLD_A_NAME}.
 *
 * <p>The SQL is parsed rather than executed, so this is a plain unit test: no container, no schema,
 * and it runs in milliseconds on every build. {@code DataExportIntegrationTest} covers the part
 * that needs a database.
 */
class DataExportScrubCoverageTest {

  // covers REQ-SEC-058 - a column that can name a person is scrubbed, or exempted with a reason
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

  /**
   * The named payload class has to stay a subset of the exemptions it is drawn from.
   *
   * <p>It is a second list of the same strings, so it can drift: a renamed or searched-again column
   * would leave {@code EXEMPT_COLUMNS} while still being named here, and then this gate would be
   * demanding a scrub decision about a column that is searched anyway (harmless) or about one that
   * no longer exists (misleading). Checked here rather than in {@code PersonSearchCoverageTest}
   * because that one needs a database, and this is the gate that depends on the subset.
   */
  @Test
  void theNameBearingExemptionsAreRealExemptions() {
    assertThat(PersonSearchTargets.EXEMPT_COLUMNS)
        .as(
            "EXEMPT_BUT_MAY_HOLD_A_NAME names the exemptions that can still contain somebody's"
                + " name, so every entry must be an exemption")
        .containsAll(PersonSearchTargets.EXEMPT_BUT_MAY_HOLD_A_NAME);
  }

  // covers REQ-SEC-058 - the scrub registry cannot drift out of step with the sections it names
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

  // covers REQ-SEC-058 - an exemption has to be about a column that is really selected
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

  // covers REQ-SEC-058 - no exemption may be left without a stated reason
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

  // covers REQ-SEC-058 - and no column may be claimed by both registries at once
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

  // covers REQ-SEC-058 - the classification's reason stays recorded beside the statement
  @Test
  void everySectionStatesWhyItCarriesItsLegalBasis() {
    // The reason used to be shipped to the member as English prose in the JSON download, which the
    // i18n rule forbids and the PDF never rendered. It is developer-facing now -- which only works
    // if it keeps being written, so it is checked rather than trusted.
    assertThat(DataExportSections.SECTIONS)
        .allSatisfy(
            section ->
                assertThat(section.rationale())
                    .as("rationale for section " + section.key())
                    .isNotBlank()
                    .hasSizeGreaterThan(20)
                    .doesNotContainIgnoringCase("todo"));
  }

  // covers REQ-SEC-058 - and the basis itself is one of the two the export knows
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
   * Whether any table this statement reads has this column registered as a person-name surface.
   *
   * <p>Matched on the column name across the statement's tables rather than resolved per table
   * alias, which over-flags and never under-flags: a column that is a person-name surface in one of
   * the joined tables has to be accounted for even when the reader has to work out which. That is
   * the safe direction for a registry whose failure mode is a name nobody noticed.
   *
   * <p><b>Searched targets plus the exemptions that can still hold a name.</b> Asking {@link
   * PersonSearchTargets#TARGETS} alone tied this gate to the search's *classification* rather than
   * to its inventory, and the two are not the same question: a column is exempt from the search
   * when finding a name there adds nothing, not only when no name can be there. {@code
   * notification.params} is the case in point — it holds the handle {@code
   * AccountDeletionRequestedEvent} writes into one row per administrator, the export scrubs it for
   * exactly that reason, and this gate did not require it. It was the one entry in the whole scrub
   * registry that could be deleted with every gate still green.
   *
   * <p>{@link PersonSearchTargets#EXEMPT_BUT_MAY_HOLD_A_NAME} names that class beside the reasons
   * it is drawn from. Asking all of {@code EXEMPT_COLUMNS} instead would flag some forty status
   * codes, enum values and identifiers and bury the one that matters.
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
