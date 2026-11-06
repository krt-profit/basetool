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

package de.greluc.krt.profit.basetool.backend.exchange;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The field ratchet of the outbound integrations (REQ-XCH-050): which properties a set of published
 * JSON Schemas declares, which of them carry a refused class, and which fields of a DTO tree its
 * schema does not declare.
 *
 * <p>A property is named by its document and the JSON Pointer of its declaration, e.g. {@code
 * ship.schema.json#/properties/name}. Only leaves are listed: a property whose schema declares
 * properties or items, or that is an array, is a container and is classified through its leaves.
 */
final class ExchangeFieldRatchet {

  /** The permanent base of every published schema {@code $id}. */
  static final String BASE = "https://ingest.profit-base.online/exchange/v1/schemas/";

  /** The keywords that combine schema branches. */
  private static final List<String> COMBINATORS = List.of("allOf", "anyOf", "oneOf");

  /** The package whose records the DTO walk follows; any other type is a leaf. */
  private static final String BACKEND_PACKAGE = "de.greluc.krt.profit.basetool";

  private ExchangeFieldRatchet() {}

  /**
   * The class of a published field. Identifiers, short names, fact values, member free text and
   * client data are green and may reach a third party; game texts (yellow) and game media (red)
   * never do (ADR-0344).
   */
  enum FieldClass {
    /** An id, key, reference, cursor, version or code. */
    IDENTIFIER(true),
    /** A name or shorthand of an entity, not a description. */
    SHORT_NAME(true),
    /** A quantity, flag, closed value, count or timestamp. */
    FACT_VALUE(true),
    /** Text the member wrote, such as a note. */
    MEMBER_FREE_TEXT(true),
    /** Data a client sent, such as extensions or the metadata of a draft. */
    CLIENT_DATA(true),
    /** A text of the game: a description, a display type, lore. */
    GAME_TEXT(false),
    /** Media of the game archive. */
    GAME_MEDIA(false);

    private final boolean green;

    FieldClass(boolean green) {
      this.green = green;
    }

    /**
     * Whether a field of this class may reach a third party.
     *
     * @return {@code true} for the five green classes
     */
    boolean green() {
      return green;
    }
  }

  /**
   * Lists the leaf properties every document declares.
   *
   * @param documents the documents by file name
   * @return the property names, sorted
   */
  static @NotNull Set<String> leaves(@NotNull Map<String, JsonNode> documents) {
    Set<String> out = new TreeSet<>();
    documents.forEach((name, root) -> collect(documents, name, root, "", out));
    return out;
  }

  /**
   * Lists the leaf properties an OpenAPI document declares inline: below every {@code schema}
   * member and every entry of {@code components.schemas}.
   *
   * @param schemas the schemas the document refers to, by file name
   * @param name the document's file name
   * @param openApi the document
   * @return the property names, sorted
   */
  static @NotNull Set<String> openApiLeaves(
      @NotNull Map<String, JsonNode> schemas, @NotNull String name, @NotNull JsonNode openApi) {
    Map<String, JsonNode> documents = new HashMap<>(schemas);
    documents.put(name, openApi);
    Set<String> out = new TreeSet<>();
    findSchemas(documents, name, openApi, "", out);
    return out;
  }

  /**
   * Lists the properties whose class is not green.
   *
   * @param classes the listed classes by property name
   * @return the refused property names, sorted
   */
  static @NotNull Set<String> refused(@NotNull Map<String, FieldClass> classes) {
    Set<String> out = new TreeSet<>();
    classes.forEach((name, fieldClass) -> {
      if (!fieldClass.green()) {
        out.add(name);
      }
    });
    return out;
  }

  /**
   * Walks a DTO tree along the schema that publishes it and lists every field the schema does not
   * declare.
   *
   * @param documents the documents by file name
   * @param root the DTO the schema describes
   * @param schemaRef the schema's file name, optionally with a fragment
   * @param reached collects every record the walk visits
   * @return the undeclared fields as {@code Dto.field}, sorted
   * @throws IllegalArgumentException if the schema cannot be found
   */
  static @NotNull Set<String> undeclaredFields(
      @NotNull Map<String, JsonNode> documents,
      @NotNull Class<?> root,
      @NotNull String schemaRef,
      @NotNull Set<Class<?>> reached) {
    Located schema = resolve(documents, file(schemaRef), schemaRef);
    if (schema == null) {
      throw new IllegalArgumentException("No published schema is named " + schemaRef);
    }
    Set<String> out = new TreeSet<>();
    walk(documents, root, List.of(schema), reached, out, new HashSet<>());
    return out;
  }

  /**
   * Lists every record a class declares, itself and its nested records at any depth.
   *
   * @param type the class
   * @return the records
   */
  static @NotNull Set<Class<?>> records(@NotNull Class<?> type) {
    Set<Class<?>> out = new HashSet<>();
    if (type.isRecord()) {
      out.add(type);
    }
    for (Class<?> nested : type.getDeclaredClasses()) {
      out.addAll(records(nested));
    }
    return out;
  }

  /**
   * Finds the inline schemas of an OpenAPI document and collects their leaf properties.
   *
   * @param documents the documents
   * @param document the OpenAPI document's file name
   * @param node the current node
   * @param pointer the node's JSON Pointer
   * @param out the collected names
   */
  private static void findSchemas(
      @NotNull Map<String, JsonNode> documents,
      @NotNull String document,
      @NotNull JsonNode node,
      @NotNull String pointer,
      @NotNull Set<String> out) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        String child = pointer + "/" + escape(field.getKey());
        if ("schema".equals(field.getKey()) || "/components/schemas".equals(pointer)) {
          collect(documents, document, field.getValue(), child, out);
        } else {
          findSchemas(documents, document, field.getValue(), child, out);
        }
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        findSchemas(documents, document, node.get(i), pointer + "/" + i, out);
      }
    }
  }

  /**
   * Collects the leaf properties below one schema node.
   *
   * @param documents the documents
   * @param document the node's document
   * @param node the node
   * @param pointer the node's JSON Pointer
   * @param out the collected names
   */
  private static void collect(
      @NotNull Map<String, JsonNode> documents,
      @NotNull String document,
      @NotNull JsonNode node,
      @NotNull String pointer,
      @NotNull Set<String> out) {
    if (!node.isObject()) {
      return;
    }
    JsonNode properties = node.get("properties");
    if (properties != null && properties.isObject()) {
      for (Map.Entry<String, JsonNode> property : properties.properties()) {
        String child = pointer + "/properties/" + escape(property.getKey());
        if (!container(documents, new Located(document, property.getValue()))) {
          out.add(document + "#" + child);
        }
        collect(documents, document, property.getValue(), child, out);
      }
    }
    for (String keyword : List.of("items", "additionalProperties")) {
      JsonNode child = node.get(keyword);
      if (child != null && child.isObject()) {
        collect(documents, document, child, pointer + "/" + keyword, out);
      }
    }
    for (String keyword : COMBINATORS) {
      JsonNode branches = node.get(keyword);
      if (branches != null && branches.isArray()) {
        for (int i = 0; i < branches.size(); i++) {
          collect(documents, document, branches.get(i), pointer + "/" + keyword + "/" + i, out);
        }
      }
    }
    JsonNode definitions = node.get("$defs");
    if (definitions != null && definitions.isObject()) {
      for (Map.Entry<String, JsonNode> definition : definitions.properties()) {
        collect(
            documents,
            document,
            definition.getValue(),
            pointer + "/$defs/" + escape(definition.getKey()),
            out);
      }
    }
  }

  /**
   * Tells whether a property schema is a container: an array, or an object that declares properties
   * or items, in any of its branches.
   *
   * @param documents the documents
   * @param property the property's schema
   * @return {@code true} for a container
   */
  private static boolean container(
      @NotNull Map<String, JsonNode> documents, @NotNull Located property) {
    for (Located branch : expand(documents, property, new HashSet<>())) {
      JsonNode node = branch.node();
      JsonNode type = node.get("type");
      boolean array =
          type != null
              && (type.isString()
                  ? "array".equals(type.stringValue())
                  : type.isArray()
                      && type.valueStream().anyMatch(t -> "array".equals(t.asString())));
      if (array || node.has("properties") || node.has("items")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Walks one Java type along the schemas that describe it.
   *
   * @param documents the documents
   * @param type the Java type
   * @param schemas the schema nodes
   * @param reached the visited records
   * @param out the undeclared fields
   * @param path the records on the current path, against cycles
   */
  private static void walk(
      @NotNull Map<String, JsonNode> documents,
      @NotNull Type type,
      @NotNull List<Located> schemas,
      @NotNull Set<Class<?>> reached,
      @NotNull Set<String> out,
      @NotNull Set<Class<?>> path) {
    Class<?> raw = raw(type);
    List<Located> branches = new ArrayList<>();
    for (Located schema : schemas) {
      branches.addAll(expand(documents, schema, new HashSet<>()));
    }
    if (raw.isArray() || Collection.class.isAssignableFrom(raw)) {
      List<Located> items = new ArrayList<>();
      for (Located branch : branches) {
        JsonNode node = branch.node().get("items");
        if (node != null && node.isObject()) {
          items.add(new Located(branch.document(), node));
        }
      }
      walk(documents, element(type), items, reached, out, path);
      return;
    }
    if (raw.getName().startsWith(BACKEND_PACKAGE)) {
      reached.add(raw);
    }
    if (!walksInto(raw) || !path.add(raw)) {
      return;
    }
    boolean open = branches.stream().anyMatch(ExchangeFieldRatchet::open);
    for (RecordComponent component : raw.getRecordComponents()) {
      if (component.getAccessor().isAnnotationPresent(JsonIgnore.class)) {
        continue;
      }
      String name = jsonName(component);
      List<Located> declarations = new ArrayList<>();
      for (Located branch : branches) {
        JsonNode properties = branch.node().get("properties");
        if (properties != null && properties.has(name)) {
          declarations.add(new Located(branch.document(), properties.get(name)));
        }
      }
      if (declarations.isEmpty()) {
        if (!open) {
          out.add(raw.getSimpleName() + "." + name);
        }
        continue;
      }
      walk(documents, component.getGenericType(), declarations, reached, out, path);
    }
    path.remove(raw);
  }

  /**
   * Tells whether the DTO walk follows a type's components: a record of the Basetool that Jackson
   * reads and writes field by field.
   *
   * @param raw the type
   * @return {@code true} for such a record
   */
  private static boolean walksInto(@NotNull Class<?> raw) {
    return raw.isRecord()
        && raw.getName().startsWith(BACKEND_PACKAGE)
        && !raw.isAnnotationPresent(JsonDeserialize.class)
        && Arrays.stream(raw.getMethods()).noneMatch(m -> m.isAnnotationPresent(JsonValue.class));
  }

  /**
   * Tells whether a schema node accepts properties it does not declare.
   *
   * @param branch the node
   * @return {@code true} when it carries {@code patternProperties} or an {@code
   *     additionalProperties} other than {@code false}
   */
  private static boolean open(@NotNull Located branch) {
    JsonNode additional = branch.node().get("additionalProperties");
    return branch.node().has("patternProperties")
        || (additional != null && !(additional.isBoolean() && !additional.booleanValue()));
  }

  /**
   * Returns the JSON name Jackson gives a record component.
   *
   * @param component the component
   * @return its {@code @JsonProperty} value, or its name
   */
  private static @NotNull String jsonName(@NotNull RecordComponent component) {
    JsonProperty property = component.getAccessor().getAnnotation(JsonProperty.class);
    return property != null && !property.value().isEmpty() ? property.value() : component.getName();
  }

  /**
   * Returns the class of a type.
   *
   * @param type the type
   * @return its raw class
   */
  private static @NotNull Class<?> raw(@NotNull Type type) {
    return switch (type) {
      case Class<?> plain -> plain;
      case ParameterizedType parameterized -> (Class<?>) parameterized.getRawType();
      case GenericArrayType array -> raw(array.getGenericComponentType()).arrayType();
      default -> Object.class;
    };
  }

  /**
   * Returns the element type of an array or a collection.
   *
   * @param type the array or collection type
   * @return the element type, {@link Object} when it is not declared
   */
  private static @NotNull Type element(@NotNull Type type) {
    return switch (type) {
      case Class<?> plain when plain.isArray() -> plain.getComponentType();
      case GenericArrayType array -> array.getGenericComponentType();
      case ParameterizedType parameterized -> parameterized.getActualTypeArguments()[0];
      default -> Object.class;
    };
  }

  /**
   * Resolves a node's {@code $ref} and collects it with its combined branches.
   *
   * @param documents the documents
   * @param located the node
   * @param seen references already followed, against cycles
   * @return the node and every branch, references resolved
   */
  private static @NotNull List<Located> expand(
      @NotNull Map<String, JsonNode> documents,
      @NotNull Located located,
      @NotNull Set<String> seen) {
    List<Located> out = new ArrayList<>();
    JsonNode ref = located.node().get("$ref");
    if (ref != null && ref.isString()) {
      if (!seen.add(located.document() + "|" + ref.stringValue())) {
        return out;
      }
      Located target = resolve(documents, located.document(), ref.stringValue());
      if (target != null) {
        out.addAll(expand(documents, target, seen));
      }
    }
    out.add(located);
    for (String keyword : COMBINATORS) {
      JsonNode branches = located.node().get(keyword);
      if (branches != null && branches.isArray()) {
        for (JsonNode branch : branches) {
          out.addAll(expand(documents, new Located(located.document(), branch), seen));
        }
      }
    }
    return out;
  }

  /**
   * Resolves a reference relative to the document it appears in.
   *
   * @param documents the documents
   * @param document the referring document
   * @param ref the reference
   * @return the target, or {@code null} when it cannot be found
   */
  private static @Nullable Located resolve(
      @NotNull Map<String, JsonNode> documents, @NotNull String document, @NotNull String ref) {
    int hash = ref.indexOf('#');
    String file = hash < 0 ? ref : ref.substring(0, hash);
    String fragment = hash < 0 ? "" : ref.substring(hash + 1);
    String target = file.isEmpty() ? document : file.replace(BASE, "");
    JsonNode root = documents.get(target);
    if (root == null) {
      return null;
    }
    JsonNode node = fragment.isEmpty() ? root : root.at(fragment);
    return node.isMissingNode() ? null : new Located(target, node);
  }

  /**
   * Returns the file part of a schema reference.
   *
   * @param schemaRef the reference
   * @return the file name without a fragment
   */
  private static @NotNull String file(@NotNull String schemaRef) {
    int hash = schemaRef.indexOf('#');
    return hash < 0 ? schemaRef : schemaRef.substring(0, hash);
  }

  /**
   * Escapes one JSON Pointer reference token (RFC 6901).
   *
   * @param token the token
   * @return the escaped token
   */
  private static @NotNull String escape(@NotNull String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }

  /**
   * A schema node and the document it sits in.
   *
   * @param document the file name
   * @param node the node
   */
  private record Located(@NotNull String document, @NotNull JsonNode node) {}
}
