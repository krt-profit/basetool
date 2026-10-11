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

package de.greluc.krt.profit.basetool.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant;
import de.greluc.krt.profit.basetool.backend.privacy.internal.HandleErasureCoverage;
import de.greluc.krt.profit.basetool.backend.privacy.internal.HandleErasureCoverage.Disposition;
import de.greluc.krt.profit.basetool.backend.privacy.internal.PersonSearchTargets;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.service.HandleAnonymisationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds the registered {@link HandleAnonymisationParticipant}s against the registry and the live
 * schema (REQ-SEC-062, REQ-DATA-021): the union of the columns they rewrite is exactly the set
 * {@link HandleErasureCoverage} marks {@link Disposition#ANONYMISED}, every such column exists as a
 * text column and is searchable, and the participants run in the recorded order.
 */
@SpringBootTest
@Transactional
class HandleAnonymisationCoverageTest {

  /** The participants of today's modules, earliest first. */
  private static final List<String> EXPECTED_ORDER =
      List.of("AuditHandleAnonymisation", "BankHandleAnonymisation", "JobOrderHandleAnonymisation");

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private HandleAnonymisationService service;

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName(
      "the participants rewrite exactly the columns the coverage registry marks ANONYMISED")
  void theUnionOfTheParticipantsIsTheAnonymisedSet() {
    Set<String> union = union(service.inRunOrder());
    assertThat(union)
        .as("columns the participants rewrite (an emptied registry would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(12);

    assertThat(differences(union, anonymisedInTheRegistry()))
        .as(
            """
            A column a participant rewrites but HandleErasureCoverage does not mark ANONYMISED, or \
            the reverse. The participants' columns() are the one list; classify the column in \
            HandleErasureCoverage, or give a module's HandleAnonymisationParticipant the statement.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("every column a participant rewrites is a text column of the live schema")
  void everyRewrittenColumnExistsAsText() {
    Set<String> inSchema =
        new TreeSet<>(
            new JdbcTemplate(dataSource)
                .queryForList(
                    """
                    SELECT c.table_name || '.' || c.column_name
                    FROM information_schema.columns c
                    JOIN information_schema.tables t
                      ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                     AND t.table_type = 'BASE TABLE'
                    WHERE c.table_schema = current_schema()
                      AND c.data_type IN ('character varying', 'text', 'character')
                    """,
                    String.class));

    assertThat(missing(union(service.inRunOrder()), inSchema))
        .as("columns a participant claims to rewrite that are no text column of the schema")
        .isEmpty();
  }

  @Test
  @DisplayName("every column a participant rewrites is one the person search reaches")
  void everyRewrittenColumnIsSearchable() {
    Set<String> searchable =
        PersonSearchTargets.TARGETS.stream()
            .map(target -> target.table() + "." + target.column())
            .collect(Collectors.toSet());

    assertThat(missing(union(service.inRunOrder()), searchable))
        .as(
            "A column the erasure rewrites but the search cannot reach is a column an admin cannot"
                + " verify afterwards.")
        .isEmpty();
  }

  @Test
  @DisplayName("the participants run in the recorded order and never share a position")
  void theParticipantsRunInTheRecordedOrder() {
    List<HandleAnonymisationParticipant> ordered = service.inRunOrder();
    List<String> names = new ArrayList<>();
    Set<Integer> positions = new HashSet<>();
    for (HandleAnonymisationParticipant participant : ordered) {
      names.add(AopUtils.getTargetClass(participant).getSimpleName());
      assertThat(positions.add(participant.position()))
          .as("position of %s", participant.getClass().getSimpleName())
          .isTrue();
    }
    assertThat(names).isEqualTo(EXPECTED_ORDER);
  }

  @Test
  @DisplayName(
      "proof: a column no participant rewrites, and one only a participant claims, are found")
  void aDriftBetweenRegistryAndParticipantsIsReported() {
    Set<String> registry = anonymisedInTheRegistry();

    Set<String> withoutOne = new TreeSet<>(registry);
    withoutOne.remove("bank_holder.handle");
    assertThat(differences(withoutOne, registry)).containsExactly("bank_holder.handle");

    Set<String> withExtra = new TreeSet<>(registry);
    withExtra.add("g11_planted.handle");
    assertThat(differences(withExtra, registry)).containsExactly("g11_planted.handle");
    assertThat(missing(withExtra, Set.of("bank_holder.handle"))).contains("g11_planted.handle");
  }

  private static @NotNull Set<String> union(@NotNull List<HandleAnonymisationParticipant> all) {
    Set<String> union = new TreeSet<>();
    for (HandleAnonymisationParticipant participant : all) {
      union.addAll(participant.columns());
    }
    return union;
  }

  private static @NotNull Set<String> anonymisedInTheRegistry() {
    Set<String> columns = new TreeSet<>();
    for (Map.Entry<String, HandleErasureCoverage.Coverage> entry :
        HandleErasureCoverage.COVERAGE.entrySet()) {
      if (entry.getValue().disposition() == Disposition.ANONYMISED) {
        columns.add(entry.getKey());
      }
    }
    return columns;
  }

  private static @NotNull Set<String> differences(
      @NotNull Set<String> left, @NotNull Set<String> right) {
    Set<String> symmetric = new TreeSet<>(left);
    symmetric.addAll(right);
    Set<String> common = new TreeSet<>(left);
    common.retainAll(right);
    symmetric.removeAll(common);
    return symmetric;
  }

  private static @NotNull Set<String> missing(
      @NotNull Set<String> claimed, @NotNull Set<String> present) {
    Set<String> missing = new TreeSet<>(claimed);
    missing.removeAll(present);
    return missing;
  }
}
