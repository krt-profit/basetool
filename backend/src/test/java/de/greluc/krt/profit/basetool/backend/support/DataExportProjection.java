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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an export section's {@code SELECT} statement: which aliases it yields, and which tables it
 * reads.
 *
 * <p><b>Deliberately a small parser rather than a real one.</b> Every statement in {@link
 * DataExportSections} is a flat {@code SELECT col, t.col, t.col AS alias FROM ...}, which is a
 * property worth keeping: a construct this cannot read is a construct that should not be added to a
 * projection whose whole job is to be reviewable by eye.
 *
 * <p><b>Why it is a shared class.</b> Two gates ask the same question of the same statements and
 * must not be able to answer it differently: {@code DataExportScrubCoverageTest} checks that every
 * selected person-name column is scrubbed or exempted, and {@code
 * DataExportPdfFieldLabelCoverageTest} checks that every selected column of a verbatim section has
 * a German PDF label. Two copies of a parser is two chances to disagree about what a statement
 * selects.
 *
 * <p>Test-tree only: nothing in {@code main} parses SQL.
 */
public final class DataExportProjection {

  /** The alias a selected expression yields, when it names one explicitly. */
  private static final Pattern EXPLICIT_ALIAS = Pattern.compile("\\bAS\\s+(\\w+)\\s*$");

  /** The tables a statement reads, whether through {@code FROM} or a {@code JOIN}. */
  private static final Pattern TABLE = Pattern.compile("\\b(?:FROM|JOIN)\\s+(\\w+)");

  /** Everything between the statement's {@code SELECT} and its first top-level {@code FROM}. */
  private static final Pattern SELECT_LIST =
      Pattern.compile("SELECT\\s+(.*?)\\s+FROM\\b", Pattern.DOTALL);

  /** Not instantiable: three static readers over a statement. */
  private DataExportProjection() {}

  /**
   * The statement's selected expressions, as alias to underlying column name.
   *
   * @param sql the section's statement
   * @return the aliases in statement order, each mapped to the column it comes from
   * @throws IllegalArgumentException when the statement is not a flat {@code SELECT ... FROM}
   */
  public static Map<String, String> selectedColumns(String sql) {
    Matcher list = SELECT_LIST.matcher(sql);
    if (!list.find()) {
      throw new IllegalArgumentException("every section is a SELECT ... FROM: " + sql);
    }
    String columns = list.group(1).replaceAll("\\s+", " ").trim();

    Map<String, String> out = new LinkedHashMap<>();
    for (String item : splitTopLevel(columns)) {
      String expression = item.trim();
      Matcher alias = EXPLICIT_ALIAS.matcher(expression);
      String name;
      String body;
      if (alias.find()) {
        name = alias.group(1);
        body = expression.substring(0, alias.start()).trim();
      } else {
        body = expression;
        name = lastIdentifier(expression);
      }
      out.put(name, lastIdentifier(body));
    }
    return out;
  }

  /**
   * The tables a statement reads.
   *
   * @param sql the statement
   * @return the table names, lower-cased
   */
  public static Set<String> tablesOf(String sql) {
    Set<String> out = new LinkedHashSet<>();
    Matcher m = TABLE.matcher(sql);
    while (m.find()) {
      out.add(m.group(1).toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /**
   * Splits a select list on commas that are not inside parentheses.
   *
   * @param columns the select list
   * @return the individual expressions
   */
  private static List<String> splitTopLevel(String columns) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    for (char c : columns.toCharArray()) {
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      }
      if (c == ',' && depth == 0) {
        out.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    if (!current.isEmpty()) {
      out.add(current.toString());
    }
    return out;
  }

  /**
   * The last bare identifier of an expression — {@code m.note} yields {@code note}.
   *
   * @param expression the selected expression
   * @return the identifier, lower-cased
   */
  private static String lastIdentifier(String expression) {
    String trimmed = expression.trim();
    int dot = trimmed.lastIndexOf('.');
    String tail = dot < 0 ? trimmed : trimmed.substring(dot + 1);
    return tail.replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
  }
}
