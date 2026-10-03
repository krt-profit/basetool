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

import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Resolves every table and column a trigger function names against today's schema (REQ-DATA-020).
 *
 * <p>PL/pgSQL bodies are checked only when they run, so a renamed column in a table the change feed
 * of ADR-0224 watches would let the migration pass and fail every later insert into that table.
 * This test reads the trigger functions and every schema function they call and requires each
 * {@code NEW.x}, {@code OLD.x}, {@code alias.x} and {@code INSERT} column, and each table name, to
 * exist.
 */
@SpringBootTest
class TriggerColumnResolutionTest {

  /** The four change-feed triggers of V252, which must stay among the checked triggers. */
  static final Set<String> CHANGE_FEED_TRIGGERS =
      Set.of(
          "trg_personal_blueprint_exchange_change",
          "trg_default_blueprint_exchange_change",
          "trg_inventory_item_exchange_change",
          "trg_ship_exchange_change");

  /** Today's user-defined trigger count; the sweep must see at least this many. */
  private static final int TRIGGER_FLOOR = 18;

  /** Row variables and keywords that stand where a table name may otherwise be expected. */
  private static final Set<String> NOT_TABLES = Set.of("new", "old");

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("every column and table a trigger function names exists in today's schema")
  void everyTriggerReferenceResolves() {
    SchemaCatalog schema = SchemaCatalog.read(new JdbcTemplate(dataSource));
    Set<String> checked =
        schema.triggers().stream()
            .map(SchemaCatalog.Trigger::name)
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(checked)
        .as("user-defined triggers (an emptied selection would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(TRIGGER_FLOOR)
        .containsAll(CHANGE_FEED_TRIGGERS);

    assertThat(unresolvedReferences(schema))
        .as(
            """
            Trigger functions name tables or columns the schema no longer has. PostgreSQL checks \
            a PL/pgSQL body only when it runs, so every write to the trigger's table would fail \
            at run time. Recreate the function in the migration that renamed or dropped the \
            column.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a renamed column a change-feed trigger reads is reported")
  void aRenamedChangeFeedColumnIsReported() throws SQLException {
    List<String> unresolved =
        RolledBackTransaction.run(
            dataSource,
            jdbc -> {
              jdbc.execute("ALTER TABLE ship RENAME COLUMN owner_id TO owner_ref");
              return unresolvedReferences(SchemaCatalog.read(jdbc));
            });
    assertThat(unresolved)
        .contains("trg_ship_exchange_change -> exchange_ship_changed: column ship.owner_id");
  }

  @Test
  @DisplayName("proof: a renamed table the change feed writes is reported")
  void aRenamedChangeFeedTableIsReported() throws SQLException {
    List<String> unresolved =
        RolledBackTransaction.run(
            dataSource,
            jdbc -> {
              jdbc.execute("ALTER TABLE exchange_change RENAME TO exchange_change_renamed");
              return unresolvedReferences(SchemaCatalog.read(jdbc));
            });
    assertThat(unresolved)
        .contains(
            "trg_ship_exchange_change -> exchange_record_change: table exchange_change",
            "trg_inventory_item_exchange_change -> exchange_record_change: table exchange_change");
  }

  /**
   * Lists every unresolved reference as {@code trigger -> function: kind name}.
   *
   * @param schema the schema to resolve against
   * @return the unresolved references, sorted
   */
  static @NotNull List<String> unresolvedReferences(@NotNull SchemaCatalog schema) {
    Set<String> unresolved = new TreeSet<>();
    for (SchemaCatalog.Trigger trigger : schema.triggers()) {
      Map<String, String> reached = schema.reachableFunctions(trigger.function());
      reached.forEach(
          (function, source) -> {
            String prefix = trigger.name() + " -> " + function + ": ";
            Map<String, String> rows =
                function.equals(trigger.function())
                    ? Map.of("new", trigger.table(), "old", trigger.table())
                    : Map.of();
            for (String candidate : SqlReferences.tableCandidates(source)) {
              if (!schema.tables().contains(candidate)
                  && !schema.functions().containsKey(candidate)
                  && !NOT_TABLES.contains(candidate)) {
                unresolved.add(prefix + "table " + candidate);
              }
            }
            Set<String> columns =
                new TreeSet<>(SqlReferences.qualifiedColumns(source, schema.tables(), rows));
            columns.addAll(SqlReferences.insertedColumns(source, schema.tables()));
            for (String column : columns) {
              if (!schema.columns().contains(column)) {
                unresolved.add(prefix + "column " + column);
              }
            }
          });
    }
    return List.copyOf(unresolved);
  }
}
