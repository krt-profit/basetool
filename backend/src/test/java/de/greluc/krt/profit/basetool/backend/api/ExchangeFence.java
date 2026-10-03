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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Splits the generated OpenAPI model into the published {@code openapi.json} and the exchange's
 * internal relay document, and joins the two again for the guards that read both (ADR-0216,
 * REQ-XCH-039).
 *
 * <p>The relay document holds every path under {@link #PREFIX}, the components those paths reach,
 * the security schemes and the domain tags its operations use. The published document keeps every
 * other path and every component it still reaches; a component only the relay reaches leaves it.
 */
public final class ExchangeFence {

  /** The backend path prefix the ingest gateway relays the exchange to. */
  public static final String PREFIX = "/api/v1/exchange/";

  /** How many operations the relay document holds. */
  public static final int RELAY_OPERATIONS = 14;

  /** The relay document's title. */
  public static final String RELAY_TITLE = "KRT Basetool Backend exchange relay API (internal)";

  /** The relay document's description. */
  private static final String RELAY_DESCRIPTION =
      "The backend operations the ingest gateway relays the exchange to. Internal: never served to"
          + " a client; the external contract is the gateway's exchange-v1.openapi.json"
          + " (ADR-0216).";

  /** The prefix of a reference into the document's components. */
  private static final String COMPONENT_REFERENCE = "#/components/";

  /** The component kind referenced by name, not by {@code $ref}, and kept in both documents. */
  private static final String SECURITY_SCHEMES = "securitySchemes";

  /** The verbs an OpenAPI path item may carry. */
  private static final Set<String> VERBS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  /** Not instantiable. */
  private ExchangeFence() {}

  /**
   * The two documents one generated model is split into.
   *
   * @param published the document every consumer reads, without the relay paths
   * @param relay the exchange's internal relay document
   */
  public record Split(@NotNull ObjectNode published, @NotNull ObjectNode relay) {}

  /**
   * Tells whether a path belongs to the relay document.
   *
   * @param path the path template
   * @return {@code true} under {@link #PREFIX}
   */
  public static boolean isRelayPath(@NotNull String path) {
    return path.startsWith(PREFIX);
  }

  /**
   * Splits a full generated document; the input is left unchanged.
   *
   * @param full the document springdoc generated for every controller
   * @return the published and the relay document
   */
  public static @NotNull Split split(@NotNull JsonNode full) {
    ObjectNode published = (ObjectNode) full.deepCopy();
    ObjectNode relay = (ObjectNode) full.deepCopy();
    List<String> relayPaths = new ArrayList<>();
    List<String> otherPaths = new ArrayList<>();
    full.path("paths")
        .propertyNames()
        .forEach(
            path -> {
              if (isRelayPath(path)) {
                relayPaths.add(path);
              } else {
                otherPaths.add(path);
              }
            });
    pathsOf(published).remove(relayPaths);
    pathsOf(relay).remove(otherPaths);

    Set<String> relayReach = reachable(full, pathsOf(relay));
    Set<String> publishedReach = reachable(full, pathsOf(published));
    Set<String> relayOnly = new TreeSet<>(relayReach);
    relayOnly.removeAll(publishedReach);
    retainComponents(published, component -> !relayOnly.contains(component));
    retainComponents(relay, relayReach::contains);

    retainUsedTags(published);
    retainUsedTags(relay);
    ObjectNode info = (ObjectNode) relay.path("info");
    info.put("description", RELAY_DESCRIPTION);
    info.put("title", RELAY_TITLE);
    return new Split(published, relay);
  }

  /**
   * Joins the published and the relay document into one, for the guards that compare the whole
   * frozen surface.
   *
   * @param published the published document
   * @param relay the relay document
   * @return a new document with the paths and components of both and the published document's other
   *     members
   * @throws IllegalArgumentException if both documents hold the same path, or a component of the
   *     same name with a different body
   */
  public static @NotNull ObjectNode merge(@NotNull JsonNode published, @NotNull JsonNode relay) {
    ObjectNode merged = (ObjectNode) published.deepCopy();
    ObjectNode paths =
        merged.has("paths") ? (ObjectNode) merged.get("paths") : merged.putObject("paths");
    for (Map.Entry<String, JsonNode> path : relay.path("paths").properties()) {
      if (paths.has(path.getKey())) {
        throw new IllegalArgumentException("both documents hold the path " + path.getKey());
      }
      paths.set(path.getKey(), path.getValue().deepCopy());
    }
    ObjectNode components =
        merged.has("components")
            ? (ObjectNode) merged.get("components")
            : merged.putObject("components");
    for (Map.Entry<String, JsonNode> kind : relay.path("components").properties()) {
      ObjectNode into =
          components.has(kind.getKey())
              ? (ObjectNode) components.get(kind.getKey())
              : components.putObject(kind.getKey());
      for (Map.Entry<String, JsonNode> component : kind.getValue().properties()) {
        JsonNode existing = into.get(component.getKey());
        if (existing != null && !existing.equals(component.getValue())) {
          throw new IllegalArgumentException(
              "the documents describe "
                  + kind.getKey()
                  + "/"
                  + component.getKey()
                  + " differently");
        }
        into.set(component.getKey(), component.getValue().deepCopy());
      }
    }
    return merged;
  }

  /**
   * Lists the {@code $ref}s of a document that name a component it does not hold.
   *
   * @param document the document
   * @return the dangling references, sorted
   */
  public static @NotNull @Unmodifiable List<String> danglingReferences(@NotNull JsonNode document) {
    Set<String> referenced = new TreeSet<>();
    collectReferences(document, referenced);
    List<String> dangling = new ArrayList<>();
    for (String component : referenced) {
      String[] parts = component.split("/", 2);
      if (parts.length != 2 || !document.path("components").path(parts[0]).has(parts[1])) {
        dangling.add(COMPONENT_REFERENCE + component);
      }
    }
    return List.copyOf(dangling);
  }

  /**
   * Counts the operations of a document.
   *
   * @param document the document
   * @return how many verbs its path items carry
   */
  public static int operationCount(@NotNull JsonNode document) {
    int count = 0;
    for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
      for (String verb : path.getValue().propertyNames()) {
        if (VERBS.contains(verb)) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Collects every component, as {@code kind/name}, that a set of path items reaches directly or
   * through other components.
   *
   * @param document the document holding the components
   * @param paths the path items to start from
   * @return the reached components
   */
  private static Set<String> reachable(JsonNode document, JsonNode paths) {
    Set<String> reached = new LinkedHashSet<>();
    Set<String> start = new TreeSet<>();
    collectReferences(paths, start);
    Deque<String> pending = new ArrayDeque<>(start);
    while (!pending.isEmpty()) {
      String component = pending.pop();
      if (!reached.add(component)) {
        continue;
      }
      String[] parts = component.split("/", 2);
      if (parts.length != 2) {
        continue;
      }
      Set<String> next = new TreeSet<>();
      collectReferences(document.path("components").path(parts[0]).path(parts[1]), next);
      next.removeAll(reached);
      pending.addAll(next);
    }
    return reached;
  }

  /**
   * Adds every {@code $ref} under a node that points into the components, as {@code kind/name}.
   *
   * @param node the node to walk
   * @param into the accumulator
   */
  private static void collectReferences(JsonNode node, Set<String> into) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> member : node.properties()) {
        JsonNode value = member.getValue();
        if ("$ref".equals(member.getKey()) && value.isString()) {
          String reference = value.asString();
          if (reference.startsWith(COMPONENT_REFERENCE)) {
            into.add(
                reference
                    .substring(COMPONENT_REFERENCE.length())
                    .replace("~1", "/")
                    .replace("~0", "~"));
          }
        } else {
          collectReferences(value, into);
        }
      }
    } else if (node.isArray()) {
      for (JsonNode element : node) {
        collectReferences(element, into);
      }
    }
  }

  /** Decides whether a component, as {@code kind/name}, stays in a document. */
  @FunctionalInterface
  private interface ComponentFilter {

    /**
     * Tells whether the component stays.
     *
     * @param component the component as {@code kind/name}
     * @return {@code true} to keep it
     */
    boolean keeps(String component);
  }

  /**
   * Removes the components a filter rejects, and every kind left empty; security schemes stay.
   *
   * @param document the document to change
   * @param filter the components to keep
   */
  private static void retainComponents(ObjectNode document, ComponentFilter filter) {
    if (!(document.get("components") instanceof ObjectNode components)) {
      return;
    }
    List<String> emptied = new ArrayList<>();
    for (Map.Entry<String, JsonNode> kind : components.properties()) {
      if (SECURITY_SCHEMES.equals(kind.getKey()) || !(kind.getValue() instanceof ObjectNode body)) {
        continue;
      }
      List<String> dropped = new ArrayList<>();
      for (String name : body.propertyNames()) {
        if (!filter.keeps(kind.getKey() + "/" + name)) {
          dropped.add(name);
        }
      }
      body.remove(dropped);
      if (body.isEmpty()) {
        emptied.add(kind.getKey());
      }
    }
    components.remove(emptied);
  }

  /**
   * Keeps only the entries of the document's tag list that one of its operations uses.
   *
   * @param document the document to change
   */
  private static void retainUsedTags(ObjectNode document) {
    if (!(document.get("tags") instanceof ArrayNode tags)) {
      return;
    }
    Set<String> used = new TreeSet<>();
    for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> verb : path.getValue().properties()) {
        if (VERBS.contains(verb.getKey())) {
          verb.getValue().path("tags").forEach(tag -> used.add(tag.asString("")));
        }
      }
    }
    for (int index = tags.size() - 1; index >= 0; index--) {
      if (!used.contains(tags.get(index).path("name").asString(""))) {
        tags.remove(index);
      }
    }
  }

  /**
   * Reads a document's path object.
   *
   * @param document the document
   * @return its {@code paths} member
   */
  private static ObjectNode pathsOf(ObjectNode document) {
    return (ObjectNode) document.get("paths");
  }
}
