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

package de.greluc.krt.profit.basetool.frontend.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operations of the committed backend {@code openapi.json}, matched by verb and path template
 * against the templates {@link BackendCallScanner} folds (REQ-FE-028).
 */
final class BackendOperations {

  /** The OpenAPI path-item keys that are HTTP operations. */
  private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");

  /** One operation: its verb and its path split into segments. */
  private record Operation(String verb, String[] segments) {}

  private final List<Operation> operations;

  private BackendOperations(List<Operation> operations) {
    this.operations = operations;
  }

  /**
   * Indexes every operation of an API document.
   *
   * @param openApi the parsed document
   * @return the index
   */
  @NotNull
  static BackendOperations of(@NotNull JsonNode openApi) {
    return of(openApi, operation -> true);
  }

  /**
   * Indexes the operations of an API document that a filter admits.
   *
   * @param openApi the parsed document
   * @param filter admits an operation by its OpenAPI operation object
   * @return the index
   */
  @NotNull
  static BackendOperations of(@NotNull JsonNode openApi, @NotNull Predicate<JsonNode> filter) {
    List<Operation> operations = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : openApi.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        if (VERBS.contains(operation.getKey()) && filter.test(operation.getValue())) {
          operations.add(
              new Operation(
                  operation.getKey().toUpperCase(Locale.ROOT), path.getKey().split("/", -1)));
        }
      }
    }
    return new BackendOperations(List.copyOf(operations));
  }

  /**
   * Indexes the committed backend document, found by walking up from the working directory.
   *
   * @return the index
   */
  @NotNull
  static BackendOperations committed() {
    return of(committedDocument());
  }

  /**
   * Reads the committed backend document, found by walking up from the working directory.
   *
   * @return the parsed document
   */
  @NotNull
  static JsonNode committedDocument() {
    Path relative = Paths.get("backend", "src", "main", "resources", "api", "openapi.json");
    for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        try {
          return JsonMapper.builder().build().readTree(Files.readString(candidate));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    throw new UncheckedIOException(
        new IOException("backend/src/main/resources/api/openapi.json not found"));
  }

  /**
   * The number of indexed operations.
   *
   * @return the operation count
   */
  int size() {
    return operations.size();
  }

  /**
   * Whether an operation with this verb serves this template.
   *
   * <p>A literal segment matches the same literal or a path variable; a runtime part of the
   * template matches any characters within one segment.
   *
   * @param verb the HTTP method
   * @param template a canonical template from {@link BackendCallScanner#canonical(String)}
   * @return {@code true} when some operation matches
   */
  boolean exists(@NotNull String verb, @NotNull String template) {
    String[] segments = template.split("/", -1);
    for (Operation operation : operations) {
      if (operation.verb().equals(verb) && matches(segments, operation.segments())) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(String[] call, String[] operation) {
    if (call.length != operation.length) {
      return false;
    }
    for (int i = 0; i < call.length; i++) {
      String spec = operation[i];
      if (spec.startsWith("{") && spec.endsWith("}")) {
        if (call[i].isEmpty()) {
          return false;
        }
        continue;
      }
      if (!segmentPattern(call[i]).matcher(spec).matches()) {
        return false;
      }
    }
    return true;
  }

  private static Pattern segmentPattern(String segment) {
    StringBuilder regex = new StringBuilder();
    StringBuilder literal = new StringBuilder();
    for (char c : segment.toCharArray()) {
      if (c == BackendCallScanner.DYNAMIC) {
        regex.append(Pattern.quote(literal.toString())).append("[^/]*");
        literal.setLength(0);
      } else {
        literal.append(c);
      }
    }
    regex.append(Pattern.quote(literal.toString()));
    return Pattern.compile(regex.toString());
  }
}
