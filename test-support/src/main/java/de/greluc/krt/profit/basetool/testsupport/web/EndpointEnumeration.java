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

package de.greluc.krt.profit.basetool.testsupport.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Enumerates every dispatcher mapping as concrete calls, shared by the backend and frontend
 * anonymous-surface sweeps.
 *
 * <p>Only enumerates; each sweep applies its own exclusions and assertions.
 */
public final class EndpointEnumeration {

  /**
   * The value substituted for a path variable whose name reads like an id.
   *
   * <p>A syntactically valid UUID that no row will ever carry, so a handler that does reach its
   * body answers "not found" rather than touching real data.
   */
  private static final String NIL_UUID = "00000000-0000-4000-8000-000000000000";

  /**
   * Bean name of the application's mapping registry, since lookup by type is ambiguous with
   * Actuator.
   */
  private static final String MAPPING_BEAN = "requestMappingHandlerMapping";

  /** Not instantiable: this is a function, not a collaborator. */
  private EndpointEnumeration() {
    throw new AssertionError("no instances");
  }

  /**
   * Expands every dispatcher mapping into concrete (verb, path) calls, unfiltered.
   *
   * <p>A mapping without a declared verb is issued as {@code GET}; a pattern without a concrete
   * spelling is dropped (see {@link #substituteVariables}).
   *
   * @param context the web application context whose dispatcher to read
   * @return every call to sweep, deduplicated and in a stable order
   */
  public static @NotNull List<Call> mappings(@NotNull WebApplicationContext context) {
    RequestMappingHandlerMapping handlerMapping =
        context.getBean(MAPPING_BEAN, RequestMappingHandlerMapping.class);
    Set<Call> calls = new LinkedHashSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      Set<String> patterns = patternsOf(info);
      Set<HttpMethod> verbs = verbsOf(info);
      for (String pattern : patterns) {
        String path = substituteVariables(pattern);
        if (path == null) {
          continue;
        }
        for (HttpMethod verb : verbs) {
          calls.add(new Call(verb, path));
        }
      }
    }
    List<Call> ordered = new ArrayList<>(calls);
    ordered.sort((a, b) -> a.toString().compareTo(b.toString()));
    return ordered;
  }

  /**
   * Every path pattern the dispatcher answers with the given verb, unsubstituted.
   *
   * <p>Unlike {@link #mappings}, patterns without a concrete spelling are kept; a mapping without a
   * declared verb is reported for every verb.
   *
   * @param context the web application context whose dispatcher to read
   * @param verb the verb to report patterns for
   * @return the matching patterns, deduplicated and in lexicographic order
   */
  public static @NotNull @Unmodifiable List<String> patterns(
      @NotNull WebApplicationContext context, @NotNull HttpMethod verb) {
    RequestMappingHandlerMapping handlerMapping =
        context.getBean(MAPPING_BEAN, RequestMappingHandlerMapping.class);
    Set<String> matched = new TreeSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      if (verbsOf(info).contains(verb)) {
        matched.addAll(patternsOf(info));
      }
    }
    return List.copyOf(matched);
  }

  /**
   * The path patterns one mapping declares.
   *
   * @param info the mapping to read
   * @return its pattern strings, in lexicographic order; empty when it declares none
   */
  private static @NotNull Set<String> patternsOf(@NotNull RequestMappingInfo info) {
    Set<String> patterns = new TreeSet<>();
    if (info.getPathPatternsCondition() != null) {
      info.getPathPatternsCondition()
          .getPatterns()
          .forEach(pattern -> patterns.add(pattern.getPatternString()));
    }
    return patterns;
  }

  /**
   * The verbs one mapping answers; a mapping without a declared verb is reported as {@code GET}.
   *
   * @param info the mapping to read
   * @return its verbs, never empty
   */
  private static @NotNull Set<HttpMethod> verbsOf(@NotNull RequestMappingInfo info) {
    Set<HttpMethod> verbs = new LinkedHashSet<>();
    info.getMethodsCondition().getMethods().forEach(m -> verbs.add(HttpMethod.valueOf(m.name())));
    if (verbs.isEmpty()) {
      verbs.add(HttpMethod.GET);
    }
    return verbs;
  }

  /**
   * Whether {@code path} is {@code root} or below it, compared segment by segment rather than by
   * string prefix.
   *
   * @param path the substituted mapping path, e.g. {@code /actuator/health}
   * @param root a subtree root, without a trailing slash
   * @return {@code true} for the root and anything below it, {@code false} for a sibling sharing
   *     its opening characters
   */
  @Contract(pure = true)
  public static boolean isUnder(@NotNull String path, @NotNull String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }

  /**
   * Replaces every {@code {name}} segment with a value the binder accepts: a nil UUID for id-like
   * names, {@code x} otherwise.
   *
   * @param pattern the mapping's path pattern
   * @return the concrete path, or {@code null} when the pattern has a wildcard or regex constraint
   */
  @Contract(pure = true)
  static @Nullable String substituteVariables(@NotNull String pattern) {
    if (pattern.contains("**") || pattern.contains(":")) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < pattern.length()) {
      char c = pattern.charAt(i);
      if (c != '{') {
        out.append(c);
        i++;
        continue;
      }
      int close = pattern.indexOf('}', i);
      if (close < 0) {
        return null;
      }
      String name = pattern.substring(i + 1, close).toLowerCase(Locale.ROOT);
      out.append(name.endsWith("id") || name.equals("uuid") ? NIL_UUID : "x");
      i = close + 1;
    }
    return out.toString();
  }
}
