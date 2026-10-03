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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A snapshot of the migrated schema the guards of REQ-DATA-020 read: its base tables, their
 * columns, its functions with their source, and its user-defined triggers.
 *
 * @param tables every base table of the current schema except Flyway's history table
 * @param columns every {@code table.column} of those tables
 * @param functions every function of the current schema mapped to its source; overloads are
 *     concatenated
 * @param triggers every user-defined trigger
 */
record SchemaCatalog(
    @NotNull Set<String> tables,
    @NotNull Set<String> columns,
    @NotNull Map<String, String> functions,
    @NotNull List<Trigger> triggers) {

  /** Flyway's own bookkeeping table, owned by no module. */
  static final String FLYWAY_HISTORY = "flyway_schema_history";

  /**
   * One user-defined trigger.
   *
   * @param name the trigger's name
   * @param table the table it is attached to
   * @param function the function it executes
   */
  record Trigger(@NotNull String name, @NotNull String table, @NotNull String function) {}

  /**
   * Reads the current schema through {@code jdbc}, which may be bound to an open transaction.
   *
   * @param jdbc the template to query with
   * @return the snapshot
   */
  static @NotNull SchemaCatalog read(@NotNull JdbcTemplate jdbc) {
    Set<String> tables =
        new TreeSet<>(
            jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                """,
                String.class));
    tables.remove(FLYWAY_HISTORY);
    Set<String> columns =
        new TreeSet<>(
            jdbc.queryForList(
                """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                """,
                String.class));
    Map<String, String> functions = new TreeMap<>();
    jdbc.query(
        """
        SELECT p.proname, p.prosrc FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = current_schema()
        """,
        rs -> {
          functions.merge(rs.getString(1), rs.getString(2), (a, b) -> a + "\n" + b);
        });
    List<Trigger> triggers =
        jdbc.query(
            """
            SELECT t.tgname, c.relname, p.proname
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_proc p ON p.oid = t.tgfoid
            WHERE NOT t.tgisinternal AND n.nspname = current_schema()
            ORDER BY t.tgname
            """,
            (rs, row) -> new Trigger(rs.getString(1), rs.getString(2), rs.getString(3)));
    return new SchemaCatalog(tables, columns, functions, triggers);
  }

  /**
   * Returns the source of {@code function} and of every schema function it calls, transitively.
   *
   * @param function the entry function
   * @return each reached function mapped to its source, in discovery order
   */
  @NotNull
  Map<String, String> reachableFunctions(@NotNull String function) {
    Map<String, String> reached = new LinkedHashMap<>();
    collect(function, reached);
    return reached;
  }

  private void collect(String function, Map<String, String> reached) {
    String source = functions.get(function);
    if (source == null || reached.containsKey(function)) {
      return;
    }
    reached.put(function, source);
    for (String callee : SqlReferences.calledFunctions(source, functions.keySet())) {
      collect(callee, reached);
    }
  }
}
