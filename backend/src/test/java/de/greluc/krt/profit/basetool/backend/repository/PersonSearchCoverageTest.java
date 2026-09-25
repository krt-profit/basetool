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

import de.greluc.krt.profit.basetool.backend.service.PersonSearchService;
import de.greluc.krt.profit.basetool.backend.support.PersonSearchTargets;
import de.greluc.krt.profit.basetool.backend.support.PersonSearchTargets.Target;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guard that makes "the Personensuche covers every free-text column" enforceable rather than
 * aspirational (REQ-SEC-060).
 *
 * <p>It sweeps {@code information_schema} for every text column in the schema and requires each one
 * to be <b>either</b> searched by {@link PersonSearchTargets#TARGETS} <b>or</b> recorded in {@link
 * PersonSearchTargets#EXEMPT_COLUMNS} / {@link PersonSearchTargets#EXEMPT_TABLES}. So a new
 * free-text column cannot be added silently: whoever adds it has to decide which it is, and say so.
 *
 * <p>That is the whole point. The privacy record tells an admin to run this search for
 * <em>every</em> Art. 16 or Art. 17 request; a column nobody remembered to add would make that
 * instruction quietly false, and the person whose data it is would have no way to know.
 *
 * <p>It also verifies the other half — that every registered target actually exists — so a renamed
 * or dropped column fails here instead of at the first search an admin runs during a real request.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonSearchCoverageTest {

  /**
   * Column names that are technical plumbing on every table and never prose. Excluded from the
   * sweep so they do not have to be listed per table.
   */
  private static final Set<String> STRUCTURAL_COLUMNS = Set.of("id", "version");

  @Autowired private PersonSearchService personSearchService;

  @PersistenceContext private EntityManager entityManager;

  /**
   * Every text column in the application schema, as {@code table.column}.
   *
   * <p>Restricted to {@code BASE TABLE}s. Views are excluded because {@code pg_stat_statements}
   * (V240) exposes a {@code query} column holding raw SQL: a diagnostic view, not application data,
   * and nothing writes a name into it.
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
        PersonSearchTargets.TARGETS.stream()
            .map(t -> t.table() + '.' + t.column())
            .collect(Collectors.toSet());

    List<String> unaccounted =
        allTextColumns().stream()
            .filter(c -> !searched.contains(c))
            .filter(c -> !PersonSearchTargets.EXEMPT_COLUMNS.contains(c))
            .filter(
                c -> !PersonSearchTargets.EXEMPT_TABLES.contains(c.substring(0, c.indexOf('.'))))
            .toList();

    assertThat(unaccounted)
        .withFailMessage(
            """
            These text columns are neither searched by the admin Personensuche nor recorded as \
            exempt:

              %s

            Decide which they are, in PersonSearchTargets:
              * free text a human typed into the tool  -> add a Target, so an Art. 16/17 request \
            can find it
              * synced catalogue data / a code, key, slug or URL / a machine-written payload \
            -> add it to EXEMPT_COLUMNS (or its table to EXEMPT_TABLES) and say why

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
    for (Target t : PersonSearchTargets.TARGETS) {
      if (!existing.contains(t.table() + '.' + t.column())) {
        missing.add(t.table() + '.' + t.column());
      }
    }
    assertThat(missing)
        .withFailMessage(
            "PersonSearchTargets names columns that do not exist (renamed or dropped?): %s",
            String.join(", ", missing))
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
        PersonSearchTargets.TARGETS.stream()
            .map(t -> t.table() + '.' + t.idColumn())
            .distinct()
            .filter(c -> !all.contains(c))
            .toList();

    assertThat(missing)
        .withFailMessage("PersonSearchTargets names id columns that do not exist: %s", missing)
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
