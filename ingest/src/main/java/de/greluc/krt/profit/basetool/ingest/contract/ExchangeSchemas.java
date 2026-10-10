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

package de.greluc.krt.profit.basetool.ingest.contract;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks exchange documents against the committed v1 JSON Schemas (REQ-XCH-011, REQ-XCH-026):
 * whether a body is valid, and which of its fields no schema declares — those are ignored and
 * reported as {@code UNKNOWN_FIELD} warnings, since the schemas stay open.
 */
@Component
public class ExchangeSchemas {

  /** The permanent base of every schema {@code $id}. */
  public static final String BASE = "https://ingest.profit-base.online/exchange/v1/schemas/";

  /** The most violations one answer lists, as the problem schema allows. */
  public static final int MAX_REPORTED = 50;

  /** The longest JSON Pointer a warning or a violation carries, as the schemas allow. */
  public static final int MAX_POINTER = 200;

  /** How deep the unknown-field walk follows nested schemas. */
  private static final int MAX_DEPTH = 32;

  private final SchemaRegistry registry;
  private final Map<String, JsonNode> documents;
  private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

  /**
   * Loads the committed schemas.
   *
   * @param exchangeDocuments the committed documents
   * @param objectMapper parses them
   */
  public ExchangeSchemas(
      @NotNull ExchangeDocuments exchangeDocuments, @NotNull ObjectMapper objectMapper) {
    this.registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder.schemaIdResolvers(
                    resolvers -> resolvers.mapPrefix(BASE, "classpath:exchange/v1/schemas/")));
    Map<String, JsonNode> parsed = new HashMap<>();
    for (String name : exchangeDocuments.schemaNames()) {
      byte[] bytes = exchangeDocuments.schema(name);
      if (bytes != null) {
        parsed.put(name, objectMapper.readTree(bytes));
      }
    }
    this.documents = Map.copyOf(parsed);
  }

  /**
   * Validates a document.
   *
   * @param schemaName the schema's file name, e.g. {@code resolve-request.schema.json}, optionally
   *     with a fragment such as {@code change-set.schema.json#/$defs/stockChangeSet}
   * @param instance the document
   * @return the violations, at most {@value #MAX_REPORTED}; empty when it is valid
   * @throws IllegalArgumentException if no committed schema has that name
   */
  public @NotNull @Unmodifiable List<Violation> validate(
      @NotNull String schemaName, @NotNull JsonNode instance) {
    List<Violation> violations = new ArrayList<>();
    for (com.networknt.schema.Error error : schema(schemaName).validate(instance)) {
      if (violations.size() == MAX_REPORTED) {
        break;
      }
      violations.add(
          new Violation(pointer(error.getInstanceLocation()), "violates " + error.getKeyword()));
    }
    return List.copyOf(violations);
  }

  /**
   * Lists the fields of a document that its schema does not declare, where the schema does not
   * accept arbitrary fields anyway.
   *
   * @param schemaName the schema's file name, optionally with a fragment
   * @param instance the document
   * @return JSON Pointers to the unknown fields, at most {@value #MAX_REPORTED}
   * @throws IllegalArgumentException if no committed schema has that name
   */
  public @NotNull @Unmodifiable List<String> unknownFields(
      @NotNull String schemaName, @NotNull JsonNode instance) {
    Located root = resolve(file(schemaName), schemaName);
    if (root == null) {
      throw new IllegalArgumentException("No exchange schema is named " + schemaName);
    }
    List<String> unknown = new ArrayList<>();
    walk(List.of(root), instance, "", unknown, 0);
    return List.copyOf(unknown);
  }

  /**
   * Returns the compiled schema of a name.
   *
   * @param schemaName the file name
   * @return the schema
   * @throws IllegalArgumentException if no committed schema has that name
   */
  private @NotNull Schema schema(@NotNull String schemaName) {
    if (!documents.containsKey(file(schemaName))) {
      throw new IllegalArgumentException("No exchange schema is named " + schemaName);
    }
    return compiled.computeIfAbsent(
        schemaName, name -> registry.getSchema(SchemaLocation.of(BASE + name)));
  }

  /**
   * Walks an instance along the schemas that describe it and collects the undeclared fields. A
   * field is declared when any of the schemas declares it; its value is then walked along every
   * declaration.
   *
   * @param schemas the schema nodes, each with the document it sits in
   * @param instance the instance node
   * @param pointer the instance node's JSON Pointer
   * @param unknown the collected pointers
   * @param depth the nesting depth
   */
  private void walk(
      @NotNull List<Located> schemas,
      @NotNull JsonNode instance,
      @NotNull String pointer,
      @NotNull List<String> unknown,
      int depth) {
    if (depth > MAX_DEPTH || unknown.size() >= MAX_REPORTED) {
      return;
    }
    List<Located> branches = new ArrayList<>();
    for (Located schema : schemas) {
      branches.addAll(expand(schema, new HashSet<>()));
    }
    if (instance.isObject()) {
      Map<String, List<Located>> declared = new HashMap<>();
      boolean open = false;
      for (Located branch : branches) {
        JsonNode properties = branch.node().get("properties");
        if (properties != null && properties.isObject()) {
          for (Map.Entry<String, JsonNode> property : properties.properties()) {
            declared
                .computeIfAbsent(property.getKey(), _ -> new ArrayList<>())
                .add(new Located(branch.document(), property.getValue()));
          }
        }
        JsonNode additional = branch.node().get("additionalProperties");
        if (branch.node().has("patternProperties")
            || (additional != null && !(additional.isBoolean() && !additional.booleanValue()))) {
          open = true;
        }
      }
      for (Map.Entry<String, JsonNode> field : instance.properties()) {
        String child = pointer + "/" + escape(field.getKey());
        List<Located> declarations = declared.get(field.getKey());
        if (declarations != null) {
          walk(declarations, field.getValue(), child, unknown, depth + 1);
        } else if (!open && !declared.isEmpty() && unknown.size() < MAX_REPORTED) {
          unknown.add(child);
        }
      }
    } else if (instance.isArray()) {
      List<Located> items = new ArrayList<>();
      for (Located branch : branches) {
        JsonNode node = branch.node().get("items");
        if (node != null && node.isObject()) {
          items.add(new Located(branch.document(), node));
        }
      }
      if (!items.isEmpty()) {
        for (int i = 0; i < instance.size(); i++) {
          walk(items, instance.get(i), pointer + "/" + i, unknown, depth + 1);
        }
      }
    }
  }

  /**
   * Resolves a schema node's {@code $ref} and collects it with its {@code allOf}, {@code anyOf} and
   * {@code oneOf} branches.
   *
   * @param located the schema node
   * @param seen references already followed, against cycles
   * @return the node and every branch, references resolved
   */
  private @NotNull List<Located> expand(@NotNull Located located, @NotNull Set<String> seen) {
    List<Located> out = new ArrayList<>();
    Located current = located;
    JsonNode ref = current.node().get("$ref");
    if (ref != null && ref.isString()) {
      String key = current.document() + "|" + ref.stringValue();
      if (!seen.add(key)) {
        return out;
      }
      Located target = resolve(current.document(), ref.stringValue());
      if (target == null) {
        return out;
      }
      out.addAll(expand(target, seen));
    }
    out.add(current);
    for (String keyword : new String[] {"allOf", "anyOf", "oneOf"}) {
      JsonNode branches = current.node().get(keyword);
      if (branches != null && branches.isArray()) {
        for (JsonNode branch : branches) {
          out.addAll(expand(new Located(current.document(), branch), seen));
        }
      }
    }
    return out;
  }

  /**
   * Resolves a reference relative to the document it appears in.
   *
   * @param document the referring document's file name
   * @param ref the reference, e.g. {@code item-ref.schema.json} or {@code #/$defs/good}
   * @return the target, or {@code null} when it cannot be found
   */
  private @Nullable Located resolve(@NotNull String document, @NotNull String ref) {
    int hash = ref.indexOf('#');
    String file = hash < 0 ? ref : ref.substring(0, hash);
    String fragment = hash < 0 ? "" : ref.substring(hash + 1);
    String target = file.isEmpty() ? document : file.replace(BASE, "");
    JsonNode root = documents.get(target);
    if (root == null) {
      return null;
    }
    JsonNode node = fragment.isEmpty() ? root : root.at(fragment);
    return node == null || node.isMissingNode() ? null : new Located(target, node);
  }

  /**
   * Returns the file part of a schema reference.
   *
   * @param schemaName the reference
   * @return the file name without a fragment
   */
  private static @NotNull String file(@NotNull String schemaName) {
    int hash = schemaName.indexOf('#');
    return hash < 0 ? schemaName : schemaName.substring(0, hash);
  }

  /**
   * Renders a validator location as a JSON Pointer.
   *
   * @param path the location
   * @return the pointer, empty for the root
   */
  static @NotNull String pointer(@Nullable NodePath path) {
    if (path == null) {
      return "";
    }
    StringBuilder pointer = new StringBuilder();
    for (int i = 0; i < path.getNameCount(); i++) {
      pointer.append('/').append(escape(String.valueOf(path.getElement(i))));
    }
    return pointer.toString();
  }

  /**
   * Shortens a JSON Pointer to one the contract can carry: the pointer itself when it has at most
   * {@link #MAX_POINTER} characters, otherwise its longest ancestor that does.
   *
   * @param pointer the pointer
   * @return a pointer of at most {@link #MAX_POINTER} characters, empty for the root
   */
  public static @NotNull String reportable(@NotNull String pointer) {
    String shortened = pointer;
    while (shortened.length() > MAX_POINTER) {
      shortened = shortened.substring(0, shortened.lastIndexOf('/'));
    }
    return shortened;
  }

  /**
   * Escapes one JSON Pointer reference token (RFC 6901).
   *
   * @param token the token
   * @return the escaped token
   */
  static @NotNull String escape(@NotNull String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }

  /**
   * One violation of a schema.
   *
   * @param pointer the JSON Pointer to the offending value
   * @param message the violated keyword
   */
  public record Violation(@NotNull String pointer, @NotNull String message) {}

  /**
   * A schema node and the file it sits in.
   *
   * @param document the file name
   * @param node the node
   */
  private record Located(@NotNull String document, @NotNull JsonNode node) {}
}
