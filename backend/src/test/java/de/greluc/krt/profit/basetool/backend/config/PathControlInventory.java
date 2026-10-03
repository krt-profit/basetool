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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.filter.NoStoreApiScopes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Reads the real handler mappings as endpoints and holds the pure checks of the path-keyed control
 * guards (REQ-SEC-031, REQ-SEC-078…080), so each check can also be fed a planted violation.
 */
final class PathControlInventory {

  /** The value substituted for a path variable: a valid UUID no row carries. */
  static final String NIL_UUID = "00000000-0000-4000-8000-000000000000";

  /** Bean name of the application's own mapping registry. */
  private static final String DISPATCHER_MAPPING_BEAN = "requestMappingHandlerMapping";

  /** The verbs cookie CSRF protects. */
  private static final Set<HttpMethod> WRITE_VERBS =
      Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

  /** Not instantiable. */
  private PathControlInventory() {}

  /**
   * One mapping: the verbs it answers and its path pattern.
   *
   * @param verbs the declared verbs; empty means every verb
   * @param pattern the path pattern as declared
   */
  record Endpoint(@NotNull Set<HttpMethod> verbs, @NotNull String pattern) {

    /**
     * Answers whether the mapping answers a verb.
     *
     * @param verb the verb
     * @return {@code true} when the verb is declared or no verb is
     */
    boolean answers(@NotNull HttpMethod verb) {
      return verbs.isEmpty() || verbs.contains(verb);
    }

    /**
     * Answers whether the mapping answers a verb cookie CSRF protects.
     *
     * @return {@code true} for an undeclared verb set or any of POST, PUT, PATCH, DELETE
     */
    boolean isWrite() {
      return verbs.isEmpty() || verbs.stream().anyMatch(WRITE_VERBS::contains);
    }

    /**
     * The pattern with every variable and wildcard replaced by a concrete segment.
     *
     * @return a path the pattern matches
     */
    @NotNull
    String concretePath() {
      return concrete(pattern);
    }

    @Override
    @NotNull
    public String toString() {
      return (verbs.isEmpty()
              ? "ANY"
              : new TreeSet<>(verbs.stream().map(HttpMethod::name).toList()))
          + " "
          + pattern;
    }
  }

  /**
   * The application's own controller mappings.
   *
   * @param context the application context
   * @return every endpoint the dispatcher's request-mapping registry holds
   */
  @NotNull
  static List<Endpoint> dispatcher(@NotNull ApplicationContext context) {
    return endpointsOf(
        List.of(context.getBean(DISPATCHER_MAPPING_BEAN, RequestMappingInfoHandlerMapping.class)));
  }

  /**
   * Every request-mapping registry's mappings, the actuator's included.
   *
   * @param context the application context
   * @return every endpoint of every {@link RequestMappingInfoHandlerMapping} bean
   */
  @NotNull
  static List<Endpoint> everyRegistry(@NotNull ApplicationContext context) {
    return endpointsOf(context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values());
  }

  /**
   * Flattens registries into endpoints, one per declared pattern.
   *
   * @param registries the registries to read
   * @return the endpoints, deduplicated, in a stable order
   */
  @NotNull
  private static List<Endpoint> endpointsOf(
      @NotNull Collection<RequestMappingInfoHandlerMapping> registries) {
    Set<Endpoint> endpoints = new LinkedHashSet<>();
    for (RequestMappingInfoHandlerMapping registry : registries) {
      for (RequestMappingInfo info : registry.getHandlerMethods().keySet()) {
        Set<HttpMethod> verbs = new LinkedHashSet<>();
        info.getMethodsCondition()
            .getMethods()
            .forEach(m -> verbs.add(HttpMethod.valueOf(m.name())));
        for (String pattern : info.getPatternValues()) {
          endpoints.add(new Endpoint(verbs, pattern));
        }
      }
    }
    List<Endpoint> ordered = new ArrayList<>(endpoints);
    ordered.sort((a, b) -> a.toString().compareTo(b.toString()));
    return ordered;
  }

  /**
   * Replaces every variable and wildcard of a pattern with a concrete segment.
   *
   * @param pattern the path pattern
   * @return a concrete path the pattern matches
   */
  @NotNull
  static String concrete(@NotNull String pattern) {
    return Arrays.stream(pattern.split("/", -1))
        .map(PathControlInventory::concreteSegment)
        .collect(Collectors.joining("/"));
  }

  /**
   * Replaces one pattern segment with a concrete value.
   *
   * @param segment one segment of a path pattern
   * @return the concrete segment
   */
  @NotNull
  private static String concreteSegment(@NotNull String segment) {
    if (segment.equals("**") || segment.equals("*") || segment.startsWith("{*")) {
      return "x";
    }
    if (segment.startsWith("{") && segment.endsWith("}")) {
      String name = segment.substring(1, segment.length() - 1);
      int colon = name.indexOf(':');
      String bare = (colon < 0 ? name : name.substring(0, colon)).toLowerCase(Locale.ROOT);
      return bare.endsWith("id") || bare.equals("uuid") ? NIL_UUID : "x";
    }
    return segment;
  }

  /**
   * Parses a pattern with the parser the filters use.
   *
   * @param pattern the pattern
   * @return the parsed pattern
   */
  @NotNull
  static PathPattern parse(@NotNull String pattern) {
    return PathPatternParser.defaultInstance.parse(pattern);
  }

  /**
   * Answers whether a path is a root or below it, segment by segment.
   *
   * @param path the path
   * @param root the root, without a trailing slash
   * @return {@code true} for the root itself and anything below it
   */
  static boolean isUnder(@NotNull String path, @NotNull String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }

  /**
   * Lists the endpoints outside the served surface.
   *
   * @param endpoints the endpoints to check
   * @param roots the surface roots
   * @param exactPaths single paths allowed besides the roots
   * @return the endpoints neither under a root nor an exact path
   */
  @NotNull
  static List<String> outsideSurface(
      @NotNull Collection<Endpoint> endpoints,
      @NotNull Collection<String> roots,
      @NotNull Collection<String> exactPaths) {
    return endpoints.stream()
        .filter(e -> !exactPaths.contains(e.pattern()))
        .filter(e -> roots.stream().noneMatch(root -> isUnder(e.pattern(), root)))
        .map(Endpoint::toString)
        .toList();
  }

  /**
   * Lists the {@code /api} endpoints that no API family classifies (REQ-SEC-031).
   *
   * @param endpoints the endpoints to check
   * @return the unclassified endpoints
   */
  @NotNull
  static List<String> unclassifiedApiEndpoints(@NotNull Collection<Endpoint> endpoints) {
    return endpoints.stream()
        .filter(e -> isUnder(e.pattern(), "/api"))
        .filter(e -> NoStoreApiScopes.classify(PathContainer.parsePath(e.concretePath())) == null)
        .map(Endpoint::toString)
        .toList();
  }

  /**
   * Lists the patterns that match no endpoint answering one of the given verbs.
   *
   * @param patterns the patterns to check
   * @param verbs the verbs the patterns apply to; empty means any
   * @param endpoints the endpoints to match against
   * @return the patterns that match nothing
   */
  @NotNull
  static List<String> deadPatterns(
      @NotNull Collection<String> patterns,
      @NotNull Collection<HttpMethod> verbs,
      @NotNull Collection<Endpoint> endpoints) {
    List<String> dead = new ArrayList<>();
    for (String pattern : patterns) {
      PathPattern parsed = parse(pattern);
      boolean live =
          endpoints.stream()
              .filter(e -> verbs.isEmpty() || verbs.stream().anyMatch(e::answers))
              .anyMatch(e -> parsed.matches(PathContainer.parsePath(e.concretePath())));
      if (!live) {
        dead.add(pattern);
      }
    }
    return dead;
  }

  /**
   * Lists the literal segments that occur in no endpoint pattern.
   *
   * @param segments the segments to look for
   * @param endpoints the endpoints to search
   * @return the segments no endpoint carries
   */
  @NotNull
  static List<String> unusedSegments(
      @NotNull Collection<String> segments, @NotNull Collection<Endpoint> endpoints) {
    return segments.stream()
        .filter(
            segment ->
                endpoints.stream()
                    .noneMatch(e -> List.of(e.pattern().split("/")).contains(segment)))
        .sorted()
        .toList();
  }

  /**
   * Lists the exact paths no endpoint declares verbatim.
   *
   * @param paths the exact paths to look for
   * @param endpoints the endpoints to search
   * @return the paths that name no mapping
   */
  @NotNull
  static List<String> unmappedPaths(
      @NotNull Collection<String> paths, @NotNull Collection<Endpoint> endpoints) {
    return paths.stream()
        .filter(path -> endpoints.stream().noneMatch(e -> e.pattern().equals(path)))
        .toList();
  }

  /**
   * Lists the endpoints below a root that are missing from an exact set.
   *
   * @param root the root, e.g. {@code /api/v1/terms}
   * @param exactSet the set every mapping below the root must be in
   * @param endpoints the endpoints to check
   * @return the endpoints below the root that the set does not name
   */
  @NotNull
  static List<String> unlistedBelow(
      @NotNull String root,
      @NotNull Collection<String> exactSet,
      @NotNull Collection<Endpoint> endpoints) {
    return endpoints.stream()
        .filter(e -> isUnder(e.pattern(), root))
        .filter(e -> !exactSet.contains(e.pattern()))
        .map(Endpoint::toString)
        .toList();
  }
}
