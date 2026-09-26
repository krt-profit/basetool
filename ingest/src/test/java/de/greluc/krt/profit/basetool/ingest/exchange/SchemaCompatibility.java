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

package de.greluc.krt.profit.basetool.ingest.exchange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import tools.jackson.databind.JsonNode;

/**
 * Finds the changes between two versions of a JSON Schema that break the exchange's additive-only
 * rule within a major version (REQ-XCH-026, ADR-0219): a removed property, schema or alternative, a
 * changed type, {@code required}, {@code const}, {@code pattern} or {@code $ref}, a removed enum
 * value, and a narrowed bound.
 */
final class SchemaCompatibility {

  private static final Set<String> UPPER_BOUNDS =
      Set.of("maxLength", "maximum", "exclusiveMaximum", "maxItems", "maxProperties");

  private static final Set<String> LOWER_BOUNDS =
      Set.of("minLength", "minimum", "exclusiveMinimum", "minItems", "minProperties");

  private static final Set<String> FROZEN = Set.of("type", "const", "pattern", "$ref", "format");

  private static final Set<String> SUBSCHEMA_MAPS =
      Set.of("properties", "$defs", "patternProperties", "dependentSchemas");

  private static final Set<String> SUBSCHEMAS =
      Set.of(
          "items",
          "additionalProperties",
          "propertyNames",
          "contains",
          "if",
          "then",
          "else",
          "not");

  private static final Set<String> SUBSCHEMA_LISTS =
      Set.of("allOf", "anyOf", "oneOf", "prefixItems");

  private SchemaCompatibility() {}

  /**
   * Lists every breaking change from {@code baseline} to {@code current}.
   *
   * @param baseline the schema as a released version published it
   * @param current the schema as this change leaves it
   * @param path a JSON Pointer naming where the two schemas sit, for the messages
   * @return one message per breaking change; empty when the change is additive only
   */
  static @NotNull @Unmodifiable List<String> breakingChanges(
      @NotNull JsonNode baseline, @NotNull JsonNode current, @NotNull String path) {
    List<String> out = new ArrayList<>();
    compare(baseline, current, path, out);
    return List.copyOf(out);
  }

  /**
   * Compares one schema node and recurses into its subschemas.
   *
   * @param base the released node
   * @param cur the current node
   * @param path where the node sits
   * @param out collects the messages
   */
  private static void compare(
      @NotNull JsonNode base,
      @NotNull JsonNode cur,
      @NotNull String path,
      @NotNull List<String> out) {
    if (!base.isObject()) {
      if (!base.equals(cur)) {
        out.add(path + ": changed from " + base + " to " + cur);
      }
      return;
    }
    if (!cur.isObject()) {
      out.add(path + ": is no longer a schema object");
      return;
    }
    for (String key : FROZEN) {
      if (base.has(key) && !base.get(key).equals(cur.get(key))) {
        out.add(path + "/" + key + ": changed from " + base.get(key) + " to " + cur.get(key));
      }
    }
    if (!setOf(base.get("required")).equals(setOf(cur.get("required")))) {
      out.add(
          path + "/required: changed from " + base.get("required") + " to " + cur.get("required"));
    }
    if (base.has("enum")) {
      Set<JsonNode> kept = new HashSet<>();
      cur.path("enum").forEach(kept::add);
      base.get("enum")
          .forEach(
              value -> {
                if (!kept.contains(value)) {
                  out.add(path + "/enum: value " + value + " removed");
                }
              });
    }
    for (String key : UPPER_BOUNDS) {
      bound(base, cur, key, -1, path, out);
    }
    for (String key : LOWER_BOUNDS) {
      bound(base, cur, key, 1, path, out);
    }
    for (String key : SUBSCHEMA_MAPS) {
      JsonNode baseMap = base.path(key);
      for (String name : baseMap.propertyNames()) {
        if (!cur.path(key).has(name)) {
          out.add(path + "/" + key + "/" + name + ": removed");
        } else {
          compare(baseMap.get(name), cur.path(key).get(name), path + "/" + key + "/" + name, out);
        }
      }
    }
    for (String key : SUBSCHEMAS) {
      if (base.has(key)) {
        if (!cur.has(key)) {
          out.add(path + "/" + key + ": removed");
        } else {
          compare(base.get(key), cur.get(key), path + "/" + key, out);
        }
      }
    }
    for (String key : SUBSCHEMA_LISTS) {
      JsonNode baseList = base.path(key);
      for (int i = 0; i < baseList.size(); i++) {
        if (cur.path(key).size() <= i) {
          out.add(path + "/" + key + "/" + i + ": removed");
        } else {
          compare(baseList.get(i), cur.path(key).get(i), path + "/" + key + "/" + i, out);
        }
      }
    }
  }

  /**
   * Reports a bound that was added, or moved in the narrowing direction.
   *
   * @param base the released node
   * @param cur the current node
   * @param key the bound keyword
   * @param narrowing {@code -1} when a lower value narrows (an upper bound), {@code 1} when a
   *     higher value narrows (a lower bound)
   * @param path where the node sits
   * @param out collects the messages
   */
  private static void bound(
      @NotNull JsonNode base,
      @NotNull JsonNode cur,
      @NotNull String key,
      int narrowing,
      @NotNull String path,
      @NotNull List<String> out) {
    if (!cur.has(key)) {
      return;
    }
    if (!base.has(key)) {
      out.add(path + "/" + key + ": added");
    } else if (Integer.signum(cur.get(key).decimalValue().compareTo(base.get(key).decimalValue()))
        == narrowing) {
      out.add(path + "/" + key + ": narrowed from " + base.get(key) + " to " + cur.get(key));
    }
  }

  /**
   * Reads a {@code required} array as a set.
   *
   * @param node the array, or {@code null} when absent
   * @return its string values
   */
  private static @NotNull Set<String> setOf(@Nullable JsonNode node) {
    Set<String> out = new HashSet<>();
    if (node != null) {
      node.forEach(value -> out.add(value.asString()));
    }
    return out;
  }
}
