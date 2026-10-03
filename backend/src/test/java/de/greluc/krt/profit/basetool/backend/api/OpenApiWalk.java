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

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads shapes out of an OpenAPI document: schema references, one-line property signatures and the
 * transitive property walk the contract guards share (REQ-API-009, REQ-API-017).
 */
final class OpenApiWalk {

  /** Not instantiable. */
  private OpenApiWalk() {}

  /** What a traversal does with one property of one schema. */
  @FunctionalInterface
  interface PropertyVisitor {

    /**
     * Called once per property of every schema the walk reaches.
     *
     * @param owner the schema the property belongs to
     * @param property the property name
     * @param value the property's schema node
     * @param required whether the owning schema lists it as required
     */
    void visit(String owner, String property, JsonNode value, boolean required);
  }

  /**
   * Reads a schema node's {@code $ref} target name.
   *
   * @param node the schema node
   * @return the referenced schema's name, or {@code null} when the node is not a reference
   */
  static @Nullable String schemaName(@Nullable JsonNode node) {
    JsonNode ref = node == null ? null : node.get("$ref");
    return ref == null ? null : ref.asString().substring(ref.asString().lastIndexOf('/') + 1);
  }

  /**
   * The one-line shape of a property node.
   *
   * @param node the property's schema node
   * @return {@code $Ref}, {@code array<...>}, {@code map<...>}, {@code type/format} or {@code type}
   */
  static @NotNull String signature(@NotNull JsonNode node) {
    String ref = schemaName(node);
    if (ref != null) {
      return "$" + ref;
    }
    String type = node.path("type").asString("");
    if ("array".equals(type)) {
      JsonNode items = node.path("items");
      String itemRef = schemaName(items);
      return "array<" + (itemRef != null ? "$" + itemRef : signature(items)) + ">";
    }
    JsonNode additional = node.path("additionalProperties");
    if (additional.isObject()) {
      String value = schemaName(additional);
      return "map<" + (value != null ? "$" + value : signature(additional)) + ">";
    }
    if (type.isEmpty()) {
      return node.has("properties") ? "object" : "any";
    }
    String format = node.path("format").asString("");
    return format.isEmpty() ? type : type + "/" + format;
  }

  /**
   * Walks a schema and everything it references transitively, via {@code $ref}, array {@code items}
   * and map {@code additionalProperties}, calling the visitor for each property.
   *
   * @param schemas the document's {@code components.schemas} node
   * @param name the schema to walk; {@code null} and already-visited names are no-ops
   * @param visited the shared cycle guard
   * @param visitor what to do with each property
   */
  static void walkProperties(
      @NotNull JsonNode schemas,
      @Nullable String name,
      @NotNull Set<String> visited,
      @NotNull PropertyVisitor visitor) {
    if (name == null || !visited.add(name)) {
      return;
    }
    JsonNode schema = schemas.get(name);
    if (schema == null) {
      return;
    }
    Set<String> required = requiredNames(schema);
    JsonNode properties = schema.get("properties");
    if (properties == null) {
      return;
    }
    for (Map.Entry<String, JsonNode> property : properties.properties()) {
      JsonNode value = property.getValue();
      visitor.visit(name, property.getKey(), value, required.contains(property.getKey()));
      if ("array".equals(value.path("type").asString(""))) {
        walkProperties(schemas, schemaName(value.path("items")), visited, visitor);
        continue;
      }
      if (value.path("additionalProperties").isObject()) {
        walkProperties(schemas, schemaName(value.path("additionalProperties")), visited, visitor);
        continue;
      }
      walkProperties(schemas, schemaName(value), visited, visitor);
    }
  }

  /**
   * The {@code required} entries a schema declares.
   *
   * @param schema the schema node
   * @return the required property names, sorted; empty when the node declares none
   */
  static @NotNull Set<String> requiredNames(@NotNull JsonNode schema) {
    Set<String> names = new TreeSet<>();
    JsonNode required = schema.path("required");
    if (required.isArray()) {
      required.forEach(entry -> names.add(entry.asString()));
    }
    return names;
  }

  /**
   * Returns one operation node of the document.
   *
   * @param document the parsed API document
   * @param path the path template as the document writes it
   * @param method the HTTP verb in lower case
   * @return the operation node, or a missing node when the document does not serve it
   */
  static @NotNull JsonNode operation(
      @NotNull JsonNode document, @NotNull String path, @NotNull String method) {
    return document.path("paths").path(path).path(method.toLowerCase(Locale.ROOT));
  }

  /**
   * Names the schema an operation's JSON request body resolves to.
   *
   * @param document the parsed API document
   * @param path the path template
   * @param method the HTTP verb in lower case
   * @return the schema name, or {@code null} for an operation that carries no JSON body
   */
  static @Nullable String requestSchemaName(
      @NotNull JsonNode document, @NotNull String path, @NotNull String method) {
    return schemaName(
        operation(document, path, method)
            .path("requestBody")
            .path("content")
            .path("application/json")
            .path("schema"));
  }

  /**
   * Names the schemas an operation's 2xx responses resolve to.
   *
   * @param document the parsed API document
   * @param path the path template
   * @param method the HTTP verb in lower case
   * @return the schema names, following an array response through its {@code items}
   */
  static @NotNull Set<String> responseSchemaNames(
      @NotNull JsonNode document, @NotNull String path, @NotNull String method) {
    Set<String> names = new TreeSet<>();
    for (Map.Entry<String, JsonNode> response :
        operation(document, path, method).path("responses").properties()) {
      if (!response.getKey().startsWith("2")) {
        continue;
      }
      for (Map.Entry<String, JsonNode> mediaType :
          response.getValue().path("content").properties()) {
        JsonNode schema = mediaType.getValue().path("schema");
        String name = schemaName(schema);
        if (name == null) {
          name = schemaName(schema.path("items"));
        }
        if (name != null) {
          names.add(name);
        }
      }
    }
    return names;
  }

  /**
   * Returns the query parameters an operation declares with their schema types.
   *
   * @param document the parsed API document
   * @param path the path template
   * @param method the HTTP verb in lower case
   * @return parameter name to its schema type, sorted; empty when it declares none
   */
  static @NotNull Map<String, String> queryParameterTypes(
      @NotNull JsonNode document, @NotNull String path, @NotNull String method) {
    Map<String, String> declared = new TreeMap<>();
    for (JsonNode parameter : operation(document, path, method).path("parameters")) {
      if ("query".equals(parameter.path("in").asString(""))) {
        declared.put(
            parameter.path("name").asString(""),
            parameter.path("schema").path("type").asString(""));
      }
    }
    return declared;
  }

  /**
   * Records the shape of everything one operation exchanges: every property reachable from its
   * request and 2xx response schemas, plus its inline and multipart bodies.
   *
   * @param document the parsed API document
   * @param path the path template
   * @param method the HTTP verb in lower case
   * @return field key to signature, sorted; a key is {@code Schema.property} for a named schema,
   *     {@code request[media]}, {@code request[media].required.name} or {@code
   *     response[status][media]} for a body without one
   */
  static @NotNull Map<String, String> operationSignatures(
      @NotNull JsonNode document, @NotNull String path, @NotNull String method) {
    Map<String, String> found = new TreeMap<>();
    JsonNode node = operation(document, path, method);
    if (node.isMissingNode()) {
      return found;
    }
    JsonNode schemas = document.path("components").path("schemas");
    Set<String> visited = new TreeSet<>();
    PropertyVisitor record =
        (owner, property, value, required) ->
            found.put(owner + "." + property, signature(value) + (required ? "!" : ""));
    for (String root : responseSchemaNames(document, path, method)) {
      walkProperties(schemas, root, visited, record);
    }
    walkProperties(schemas, requestSchemaName(document, path, method), visited, record);
    for (Map.Entry<String, JsonNode> media :
        node.path("requestBody").path("content").properties()) {
      JsonNode schema = media.getValue().path("schema");
      found.put("request[" + media.getKey() + "]", signature(schema));
      for (String required : requiredNames(schema)) {
        found.put("request[" + media.getKey() + "].required." + required, "required");
      }
    }
    for (Map.Entry<String, JsonNode> response : node.path("responses").properties()) {
      if (!response.getKey().startsWith("2")) {
        continue;
      }
      for (Map.Entry<String, JsonNode> media : response.getValue().path("content").properties()) {
        found.put(
            "response[" + response.getKey() + "][" + media.getKey() + "]",
            signature(media.getValue().path("schema")));
      }
    }
    return found;
  }
}
