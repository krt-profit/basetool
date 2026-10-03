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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Extracts the tables, columns and functions a piece of SQL or PL/pgSQL names, for the schema
 * guards of REQ-DATA-020.
 *
 * <p>A lexical scan, not a parser: string literals and comments are blanked first, and every
 * candidate name is kept only when it names a known table or function, so keywords, variables and
 * aliases that happen to follow {@code FROM} fall away.
 */
final class SqlReferences {

  private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

  private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

  private static final Pattern TABLE_POSITION =
      Pattern.compile(
          "\\b(?:from|join|insert\\s+into|merge\\s+into|update|table)\\s+(?:only\\s+)?"
              + "([a-z_][a-z0-9_]*)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern ALIASED_TABLE =
      Pattern.compile(
          "\\b(?:from|join|update)\\s+(?:only\\s+)?([a-z_][a-z0-9_]*)\\s+(?:as\\s+)?([a-z_][a-z0-9_]*)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern QUALIFIED_COLUMN =
      Pattern.compile("\\b([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern INSERT_COLUMNS =
      Pattern.compile(
          "\\binsert\\s+into\\s+([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

  private static final Pattern CALL = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\s*\\(");

  private static final Set<String> NOT_AN_ALIAS =
      Set.of(
          "where",
          "on",
          "join",
          "left",
          "right",
          "inner",
          "outer",
          "cross",
          "full",
          "group",
          "order",
          "limit",
          "using",
          "natural",
          "union",
          "set",
          "values",
          "returning",
          "for",
          "lateral",
          "offset",
          "having",
          "window",
          "except",
          "intersect",
          "into",
          "then",
          "loop",
          "and",
          "or",
          "when",
          "default",
          "select");

  /** Not instantiable. */
  private SqlReferences() {}

  /**
   * Blanks string literals and comments and lower-cases the rest.
   *
   * @param sql the statement or function body
   * @return the scannable text
   */
  static @NotNull String scannable(@NotNull String sql) {
    String text = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
    text = STRING_LITERAL.matcher(text).replaceAll("''");
    text = LINE_COMMENT.matcher(text).replaceAll(" ");
    return text.toLowerCase(Locale.ROOT);
  }

  /**
   * Returns the known tables among the {@link #tableCandidates(String) table candidates}.
   *
   * @param sql the statement or function body
   * @param knownTables the tables of the schema
   * @return the referenced tables, sorted
   */
  static @NotNull Set<String> tables(@NotNull String sql, @NotNull Set<String> knownTables) {
    Set<String> found = tableCandidates(sql);
    found.retainAll(knownTables);
    return found;
  }

  /**
   * Returns every name that stands where a table is expected — after {@code FROM}, {@code JOIN},
   * {@code INSERT INTO}, {@code MERGE INTO}, {@code UPDATE} or {@code TABLE} — whether or not such
   * a table exists. A PL/pgSQL {@code SELECT … INTO variable} is not a table position.
   *
   * @param sql the statement or function body
   * @return the candidate names, sorted
   */
  static @NotNull Set<String> tableCandidates(@NotNull String sql) {
    Set<String> found = new TreeSet<>();
    Matcher m = TABLE_POSITION.matcher(scannable(sql));
    while (m.find()) {
      found.add(m.group(1));
    }
    return found;
  }

  /**
   * Returns the known functions the text calls.
   *
   * @param sql the statement or function body
   * @param knownFunctions the functions of the schema
   * @return the called functions, sorted
   */
  static @NotNull Set<String> calledFunctions(
      @NotNull String sql, @NotNull Set<String> knownFunctions) {
    Set<String> found = new TreeSet<>();
    Matcher m = CALL.matcher(scannable(sql));
    while (m.find()) {
      if (knownFunctions.contains(m.group(1))) {
        found.add(m.group(1));
      }
    }
    return found;
  }

  /**
   * Returns every {@code qualifier.column} reference whose qualifier is a table alias, a table name
   * or one of {@code rowVariables}, resolved to {@code table.column}.
   *
   * @param sql the statement or function body
   * @param knownTables the tables of the schema
   * @param rowVariables row variables mapped to the table they stand for, such as {@code new} and
   *     {@code old} in a trigger function
   * @return the resolved {@code table.column} references, sorted
   */
  static @NotNull Set<String> qualifiedColumns(
      @NotNull String sql,
      @NotNull Set<String> knownTables,
      @NotNull Map<String, String> rowVariables) {
    Map<String, String> qualifiers = new LinkedHashMap<>(rowVariables);
    qualifiers.putAll(aliases(sql, knownTables));
    for (String table : knownTables) {
      qualifiers.putIfAbsent(table, table);
    }
    Set<String> found = new TreeSet<>();
    Matcher m = QUALIFIED_COLUMN.matcher(scannable(sql));
    while (m.find()) {
      String table = qualifiers.get(m.group(1));
      if (table != null) {
        found.add(table + "." + m.group(2));
      }
    }
    return found;
  }

  /**
   * Returns the aliases the text gives known tables after {@code FROM}, {@code JOIN} or {@code
   * UPDATE}.
   *
   * @param sql the statement or function body
   * @param knownTables the tables of the schema
   * @return each alias mapped to its table
   */
  static @NotNull Map<String, String> aliases(
      @NotNull String sql, @NotNull Set<String> knownTables) {
    Map<String, String> aliases = new LinkedHashMap<>();
    Matcher aliased = ALIASED_TABLE.matcher(scannable(sql));
    while (aliased.find()) {
      String table = aliased.group(1);
      String alias = aliased.group(2);
      if (knownTables.contains(table) && !NOT_AN_ALIAS.contains(alias)) {
        aliases.put(alias, table);
      }
    }
    return aliases;
  }

  /**
   * Returns the columns of every {@code INSERT INTO table (…)} column list.
   *
   * @param sql the statement or function body
   * @param knownTables the tables of the schema
   * @return the inserted {@code table.column} names
   */
  static @NotNull List<String> insertedColumns(
      @NotNull String sql, @NotNull Set<String> knownTables) {
    List<String> found = new ArrayList<>();
    Matcher m = INSERT_COLUMNS.matcher(scannable(sql));
    while (m.find()) {
      if (!knownTables.contains(m.group(1))) {
        continue;
      }
      for (String column : m.group(2).split(",")) {
        String name = column.strip();
        if (!name.isEmpty()) {
          found.add(m.group(1) + "." + name);
        }
      }
    }
    return found;
  }
}
