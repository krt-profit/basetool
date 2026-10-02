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

package de.greluc.krt.profit.basetool.backend.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import tools.jackson.databind.JsonNode;

/**
 * The declared-break ledger and the previous-release comparison it gates (REQ-API-017, ADR-0234).
 *
 * <p>The ledger is {@code backend/src/test/resources/api/declared-breaks.txt}: one line per removed
 * or changed frozen operation or field, {@code <VERB> <path> <field> <versionCode>}, separated by
 * single spaces. {@code field} is {@code -} for the operation itself (its path or verb is gone),
 * {@code Schema.property} for a property of a schema the operation reaches, {@code query:name} for
 * a query parameter, or a body key such as {@code request[application/json]}. {@code versionCode}
 * is the Android build that absorbs the break. No wildcard, no blank field, no comment line.
 */
final class DeclaredBreaks {

  /** The verbs a ledger line may name. */
  private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  /** Characters that would make a line match more than the one operation or field it names. */
  private static final Pattern WILDCARD = Pattern.compile("[*?]");

  /** The marker for a break of the operation itself rather than one of its fields. */
  static final String WHOLE_OPERATION = "-";

  /** Not instantiable. */
  private DeclaredBreaks() {}

  /**
   * One break: an operation, and the field of it that broke or {@link #WHOLE_OPERATION}.
   *
   * @param method the HTTP verb, upper case
   * @param path the path template as the document writes it
   * @param field the broken field key, or {@link #WHOLE_OPERATION}
   */
  record Break(@NotNull String method, @NotNull String path, @NotNull String field)
      implements Comparable<Break> {

    /**
     * Orders breaks by path, then verb, then field, so a report reads like the document.
     *
     * @param other the break to compare with
     * @return a negative, zero or positive number as this sorts before, with or after {@code other}
     */
    @Override
    public int compareTo(@NotNull Break other) {
      int byPath = path.compareTo(other.path);
      if (byPath != 0) {
        return byPath;
      }
      int byMethod = method.compareTo(other.method);
      return byMethod != 0 ? byMethod : field.compareTo(other.field);
    }

    /**
     * Renders the break as the ledger line that would declare it, without the version.
     *
     * @return {@code <VERB> <path> <field>}
     */
    @Override
    public @NotNull String toString() {
      return method + " " + path + " " + field;
    }
  }

  /**
   * One ledger line: a declared break and the Android build that absorbs it.
   *
   * @param declared the break the line accepts
   * @param absorbedBy the {@code versionCode} of the first app build that no longer depends on it
   */
  record Entry(@NotNull Break declared, int absorbedBy) {}

  /**
   * An operation the comparison looks at.
   *
   * @param method the HTTP verb, upper case
   * @param path the path template as the document writes it
   */
  record OperationKey(@NotNull String method, @NotNull String path) {}

  /**
   * Parses the ledger.
   *
   * @param lines the ledger's lines; blank lines are skipped
   * @return the entries in file order
   * @throws IllegalArgumentException naming the line number when a line is malformed, names a
   *     wildcard, an unknown verb, a non-positive version or repeats an earlier line's break
   */
  static @NotNull @Unmodifiable List<Entry> parse(@NotNull List<String> lines) {
    List<Entry> entries = new ArrayList<>();
    Set<Break> seen = new HashSet<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (line.isBlank()) {
        continue;
      }
      String[] tokens = line.strip().split(" ");
      int number = index + 1;
      if (tokens.length != 4) {
        throw new IllegalArgumentException(
            "declared-breaks line "
                + number
                + " must be '<VERB> <path> <field> <versionCode>' separated by single spaces: "
                + line);
      }
      if (!VERBS.contains(tokens[0])) {
        throw new IllegalArgumentException(
            "declared-breaks line " + number + " names no HTTP verb in upper case: " + line);
      }
      if (!tokens[1].startsWith("/api/") && !tokens[1].startsWith("/internal/")) {
        throw new IllegalArgumentException(
            "declared-breaks line " + number + " names no API path: " + line);
      }
      for (String token : List.of(tokens[1], tokens[2])) {
        if (WILDCARD.matcher(token).find()) {
          throw new IllegalArgumentException(
              "declared-breaks line "
                  + number
                  + " uses a wildcard; a ledger line names one operation and one field: "
                  + line);
        }
      }
      int version;
      try {
        version = Integer.parseInt(tokens[3]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "declared-breaks line " + number + " ends in no app versionCode: " + line, e);
      }
      if (version <= 0) {
        throw new IllegalArgumentException(
            "declared-breaks line " + number + " names a non-positive versionCode: " + line);
      }
      Break declared = new Break(tokens[0], tokens[1], tokens[2]);
      if (!seen.add(declared)) {
        throw new IllegalArgumentException(
            "declared-breaks line " + number + " repeats an earlier declaration: " + line);
      }
      entries.add(new Entry(declared, version));
    }
    return List.copyOf(entries);
  }

  /**
   * Lists every break of a frozen operation between two documents.
   *
   * <p>An operation the previous document did not serve is new and cannot break. One that it served
   * and the current document does not is a {@link #WHOLE_OPERATION} break. For one both serve,
   * every field key of {@link OpenApiWalk#operationSignatures} that is gone or changed is a break,
   * and so is every query parameter that is gone or changed its type.
   *
   * @param previous the previous release's document
   * @param current the document under test
   * @param operations the frozen operations to compare
   * @return each break with a {@code before -> after} description, sorted
   */
  static @NotNull Map<Break, String> between(
      @NotNull JsonNode previous,
      @NotNull JsonNode current,
      @NotNull Collection<OperationKey> operations) {
    Map<Break, String> breaks = new TreeMap<>();
    for (OperationKey operation : operations) {
      String method = operation.method();
      String path = operation.path();
      if (OpenApiWalk.operation(previous, path, method).isMissingNode()) {
        continue;
      }
      if (OpenApiWalk.operation(current, path, method).isMissingNode()) {
        breaks.put(new Break(method, path, WHOLE_OPERATION), "served -> gone");
        continue;
      }
      Map<String, String> was = OpenApiWalk.operationSignatures(previous, path, method);
      Map<String, String> now = OpenApiWalk.operationSignatures(current, path, method);
      for (Map.Entry<String, String> field : was.entrySet()) {
        String after = now.get(field.getKey());
        if (!field.getValue().equals(after)) {
          breaks.put(
              new Break(method, path, field.getKey()),
              field.getValue() + " -> " + (after == null ? "gone" : after));
        }
      }
      Map<String, String> wasQuery = OpenApiWalk.queryParameterTypes(previous, path, method);
      Map<String, String> nowQuery = OpenApiWalk.queryParameterTypes(current, path, method);
      for (Map.Entry<String, String> parameter : wasQuery.entrySet()) {
        String after = nowQuery.get(parameter.getKey());
        if (!parameter.getValue().equals(after)) {
          breaks.put(
              new Break(method, path, "query:" + parameter.getKey()),
              parameter.getValue() + " -> " + (after == null ? "gone" : after));
        }
      }
    }
    return breaks;
  }

  /**
   * Keeps the breaks no ledger line declares.
   *
   * @param found the breaks the comparison found, with their descriptions
   * @param ledger the parsed ledger
   * @return the undeclared breaks rendered as {@code <VERB> <path> <field>: before -> after}
   */
  static @NotNull @Unmodifiable List<String> undeclared(
      @NotNull Map<Break, String> found, @NotNull List<Entry> ledger) {
    Set<Break> declared = new HashSet<>();
    ledger.forEach(entry -> declared.add(entry.declared()));
    return found.entrySet().stream()
        .filter(entry -> !declared.contains(entry.getKey()))
        .map(entry -> entry.getKey() + ": " + entry.getValue())
        .toList();
  }
}
