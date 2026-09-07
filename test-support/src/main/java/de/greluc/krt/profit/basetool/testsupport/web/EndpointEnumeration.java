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
import org.springframework.http.HttpMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Asks the dispatcher for every mapping it knows and expands them into concrete calls.
 *
 * <p><b>Why this is shared, and why that matters (#1804).</b> The backend's {@code
 * AnonymousSurfaceSweepTest} and the frontend's {@code AnonymousSurfaceSweepMvcTest} are worth
 * having for exactly one reason: they ask the dispatcher rather than asserting a list somebody
 * remembered to write. Until this class they each carried their own copy of this engine, differing
 * in one lambda parameter name. The 2026-09-06 review of the members-only change (#1803) found two
 * ways the enumeration can quietly stop covering something — an exclusion compared with {@code
 * startsWith} swallowed a neighbouring path, and an exemption list nothing verified could claim
 * more than the filters granted — and both had to be fixed in each copy separately. That is the
 * drift this class removes.
 *
 * <p><b>A defect here blinds both guards at once</b>, which is why it carries its own tests rather
 * than being covered only through its callers: a sweep that enumerates nothing passes every
 * assertion it makes.
 *
 * <p>The two sweeps keep everything else. Their {@code NOT_SWEPT} lists differ, their public-path
 * sets differ, and their questions differ — the backend asks "nothing answers a caller who is not a
 * member", the frontend asks "a navigation goes to the login and a background call is refused".
 * This class only enumerates.
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
   * The mapping registry to read, named rather than resolved by type.
   *
   * <p>By type alone this is ambiguous in both applications: Actuator contributes a second {@code
   * RequestMappingHandlerMapping} ({@code controllerEndpointHandlerMapping}) and the lookup fails.
   * The application's own mappings are the subject of both sweeps — the actuator tree is gated by
   * the management-port configuration, which neither application-connector context models.
   */
  private static final String MAPPING_BEAN = "requestMappingHandlerMapping";

  /** Not instantiable: this is a function, not a collaborator. */
  private EndpointEnumeration() {
    throw new AssertionError("no instances");
  }

  /**
   * Expands every mapping the dispatcher knows into concrete (verb, path) calls.
   *
   * <p>A mapping with no declared verb (rare, but legal) is issued as {@code GET}: it answers every
   * verb, so the read is the one that would leak. A pattern that cannot be made concrete is dropped
   * — see {@link #substituteVariables}.
   *
   * <p>Nothing is filtered here. A caller that wants to skip a subtree does so itself with {@link
   * #isUnder}, because what belongs in a {@code NOT_SWEPT} list is a property of the question being
   * asked, not of the enumeration.
   *
   * @param context the web application context whose dispatcher to read
   * @return every call to sweep, deduplicated and in a stable order
   */
  public static List<Call> mappings(WebApplicationContext context) {
    RequestMappingHandlerMapping handlerMapping =
        context.getBean(MAPPING_BEAN, RequestMappingHandlerMapping.class);
    Set<Call> calls = new LinkedHashSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      Set<String> patterns = new TreeSet<>();
      if (info.getPathPatternsCondition() != null) {
        info.getPathPatternsCondition()
            .getPatterns()
            .forEach(pattern -> patterns.add(pattern.getPatternString()));
      }
      Set<HttpMethod> verbs = new LinkedHashSet<>();
      info.getMethodsCondition().getMethods().forEach(m -> verbs.add(HttpMethod.valueOf(m.name())));
      if (verbs.isEmpty()) {
        verbs.add(HttpMethod.GET);
      }
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
   * Whether {@code path} is the given root or sits below it, compared segment by segment.
   *
   * <p><b>Not {@code startsWith}, and that is the point.</b> A plain prefix test makes {@code
   * /internal} swallow {@code /internal-facing} and {@code /error} swallow {@code /errors} —
   * silently removing a neighbouring path from a sweep whose entire value is that it covers
   * everything. That is one of the two defects the 2026-09-06 review found, in both copies of this
   * engine at once.
   *
   * @param path the substituted mapping path, e.g. {@code /actuator/health} or {@code
   *     /v3/api-docs.yaml}
   * @param root a subtree root, written without a trailing slash
   * @return {@code true} for the root itself and anything below it, {@code false} for a sibling
   *     that merely shares its opening characters
   */
  public static boolean isUnder(String path, String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }

  /**
   * Replaces every {@code {name}} segment with a value the binder accepts.
   *
   * <p>A nil UUID for anything whose name reads like an id, {@code x} otherwise. A pattern carrying
   * a wildcard ({@code **}) or a regex constraint is skipped: it has no single concrete spelling,
   * and guessing one would assert a path the application never routes.
   *
   * <p>Package-private on purpose — it is an implementation detail of {@link #mappings}, and it is
   * visible to this package's tests because its edge cases are where a silent gap in the sweep
   * would come from.
   *
   * @param pattern the mapping's path pattern
   * @return the concrete path, or {@code null} when the pattern cannot be made concrete
   */
  static String substituteVariables(String pattern) {
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
