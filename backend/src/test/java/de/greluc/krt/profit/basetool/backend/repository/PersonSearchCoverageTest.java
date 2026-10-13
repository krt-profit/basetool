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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource.Target;
import de.greluc.krt.profit.basetool.backend.privacy.api.PersonSearchService;
import de.greluc.krt.profit.basetool.backend.privacy.internal.MentionSourceDeclarations;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requires every text column in the schema to be either searched by a {@code PersonMentionSource}
 * target or listed in its exempt columns or exempt tables, and every registered target and
 * exemption to exist (REQ-SEC-060). The sources are the union of what the modules declare, found by
 * scanning.
 */
@SpringBootTest
class PersonSearchCoverageTest {

  /**
   * Column names that are technical plumbing on every table and never prose. Excluded from the
   * sweep so they do not have to be listed per table.
   */
  private static final Set<String> STRUCTURAL_COLUMNS = Set.of("id", "version");

  /** The schema-history table of Flyway, which no module owns and which is exempt as a table. */
  private static final String SCHEMA_HISTORY_TABLE = "flyway_schema_history";

  @Autowired private PersonSearchService personSearchService;

  @PersistenceContext private EntityManager entityManager;

  /**
   * Every text column of the application's base tables, as {@code table.column}; views are
   * excluded.
   *
   * @return the columns, excluding structural ones
   */
  private List<String> allTextColumns() {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT c.table_name, c.column_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public'
                  AND t.table_type = 'BASE TABLE'
                  AND c.data_type IN ('text', 'character varying', 'character')
                ORDER BY c.table_name, c.column_name
                """)
            .getResultList();
    List<String> columns = new ArrayList<>();
    for (Object[] row : rows) {
      String table = String.valueOf(row[0]);
      String column = String.valueOf(row[1]);
      if (STRUCTURAL_COLUMNS.contains(column)) {
        continue;
      }
      columns.add(table + '.' + column);
    }
    return columns;
  }

  @Test
  @Transactional(readOnly = true)
  void everyTextColumnIsEitherSearchedOrExplicitlyExempt() {
    Set<String> searched =
        MentionSourceDeclarations.targets().stream()
            .map(t -> t.table() + '.' + t.column())
            .collect(Collectors.toSet());

    List<String> unaccounted =
        allTextColumns().stream()
            .filter(c -> !searched.contains(c))
            .filter(c -> !MentionSourceDeclarations.exemptColumns().contains(c))
            .filter(
                c ->
                    !MentionSourceDeclarations.exemptTables()
                        .contains(c.substring(0, c.indexOf('.'))))
            .toList();

    assertThat(unaccounted)
        .withFailMessage(
            """
            These text columns are neither searched by the admin Personensuche nor recorded as \
            exempt:

              %s

            Decide which they are, in the PersonMentionSource of the module that owns the table:
              * free text a human typed into the tool  -> add a Target, so an Art. 16/17 request \
            can find it
              * synced catalogue data / a code, key, slug or URL / a machine-written payload \
            -> add it to exemptColumns() (or its table to exemptTables()) and say why

            This guard exists because docs/privacy/data-subject-requests.md tells an admin to run \
            that search for EVERY such request. A column nobody added makes that instruction \
            quietly false.\
            """,
            String.join("\n  ", unaccounted))
        .isEmpty();
  }

  @Test
  @Transactional(readOnly = true)
  void everyRegisteredTargetStillExists() {
    Set<String> existing = Set.copyOf(allTextColumns());
    List<String> missing = new ArrayList<>();
    for (Target t : MentionSourceDeclarations.targets()) {
      if (!existing.contains(t.table() + '.' + t.column())) {
        missing.add(t.table() + '.' + t.column());
      }
    }
    assertThat(missing)
        .withFailMessage(
            "The person-mention sources name columns that do not exist (renamed or dropped?): %s",
            String.join(", ", missing))
        .isEmpty();
  }

  @Test
  @Transactional(readOnly = true)
  void everyExemptionStillExists() {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                """
                SELECT c.table_name, c.column_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                """)
            .getResultList();
    Set<String> columns =
        rows.stream()
            .map(r -> String.valueOf(r[0]) + '.' + String.valueOf(r[1]))
            .collect(Collectors.toSet());
    Set<String> tables = rows.stream().map(r -> String.valueOf(r[0])).collect(Collectors.toSet());

    List<String> staleColumns =
        MentionSourceDeclarations.exemptColumns().stream()
            .filter(c -> !columns.contains(c))
            .sorted()
            .toList();
    List<String> staleTables =
        MentionSourceDeclarations.exemptTables().stream()
            .filter(t -> !tables.contains(t) && !SCHEMA_HISTORY_TABLE.equals(t))
            .sorted()
            .toList();

    assertThat(staleColumns)
        .as("exempt columns that no longer exist (an exemption for nothing reads as a decision)")
        .isEmpty();
    assertThat(staleTables)
        .as("exempt tables that no longer exist (an exemption for nothing reads as a decision)")
        .isEmpty();
  }

  @Test
  @Transactional(readOnly = true)
  void noColumnIsBothSearchedAndExempt() {
    Set<String> searched =
        MentionSourceDeclarations.targets().stream()
            .map(t -> t.table() + '.' + t.column())
            .collect(Collectors.toSet());

    List<String> both =
        MentionSourceDeclarations.exemptColumns().stream()
            .filter(searched::contains)
            .sorted()
            .toList();

    assertThat(both)
        .as("a column is searched or it is exempt; both entries cannot be true")
        .isEmpty();
  }

  @Test
  @Transactional(readOnly = true)
  void everyTargetIdColumnExists() {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT table_name, column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'public'")
            .getResultList();
    Set<String> all =
        rows.stream()
            .map(r -> String.valueOf(r[0]) + '.' + String.valueOf(r[1]))
            .collect(Collectors.toSet());

    List<String> missing =
        MentionSourceDeclarations.targets().stream()
            .map(t -> t.table() + '.' + t.idColumn())
            .distinct()
            .filter(c -> !all.contains(c))
            .toList();

    assertThat(missing)
        .withFailMessage(
            "The person-mention sources name id columns that do not exist: %s", missing)
        .isEmpty();
  }

  @Test
  void theSweepRunsAgainstTheRealSchema() {
    PersonSearchService.PersonSearchResult result =
        personSearchService.search("zzz-no-such-handle-zzz");

    assertThat(result.hits()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void aTooShortTermIsRefused() {
    assertThatThrownBy(() -> personSearchService.search("ab"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> personSearchService.search("   x   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void likeWildcardsInTheTermAreEscaped() {
    assertThat(personSearchService.search("%%%").hits()).isEmpty();
    assertThat(personSearchService.search("___").hits()).isEmpty();
  }
}
