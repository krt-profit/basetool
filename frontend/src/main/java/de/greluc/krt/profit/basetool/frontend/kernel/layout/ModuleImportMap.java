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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

/**
 * Renders the import map of a page: the content-hashed URL of every ES module the page's scripts
 * import, keyed by the absolute asset path the {@code import} statement names (REQ-FE-035).
 *
 * <p>Assets are cached immutably for a year under content-hashed URLs, but a module's {@code
 * import} specifier is part of its source and cannot carry the hash. The map rewrites each
 * specifier to the hashed URL the template links would produce, so a changed module is a new URL
 * and a stale cached copy can never be loaded. The modules a page needs are found by scanning its
 * template (and the fragments it includes) for {@code type="module"} scripts, then following the
 * static {@code import} and {@code export ... from} statements of each.
 */
@Slf4j
@Component("moduleImportMap")
@RequiredArgsConstructor
public class ModuleImportMap {

  /** The template every page is composed through; scanned for every page's head scripts. */
  static final String LAYOUT_TEMPLATE = "kernel/fragments/layout";

  /** A module script tag: group 1 holds its attributes. */
  private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b([^>]*)>");

  /** The script source of a module tag, as a Thymeleaf link expression. */
  private static final Pattern MODULE_SRC =
      Pattern.compile("th:src\\s*=\\s*\"@\\{(/js/[^}?\"]+\\.js)[}?]");

  /** A fragment reference {@code ~{template :: name}}: group 1 is the template name. */
  private static final Pattern FRAGMENT_REFERENCE =
      Pattern.compile("~\\{\\s*([A-Za-z][A-Za-z0-9_/\\-]*)\\s*(?:::|\\})");

  /** A side-effect import: {@code import '/js/x.js';}. */
  private static final Pattern BARE_IMPORT = Pattern.compile("(?m)^import\\s*(['\"])([^'\"]+)\\1");

  /** A named, default or namespace import and a re-export: {@code ... from '/js/x.js'}. */
  private static final Pattern FROM_IMPORT =
      Pattern.compile("(?m)^(?:import|export)\\b[^;'\"()]*?\\bfrom\\s*(['\"])([^'\"]+)\\1");

  /** The only shape of path the map ever names. */
  private static final Pattern ASSET_PATH = Pattern.compile("/js/[A-Za-z0-9._/\\-]+\\.js");

  /** Looks up the content-hashed URL of an asset path, or {@code null} when none is served. */
  private final Function<String, String> versioner;

  /** The rendered map of each view, computed once. */
  private final Map<String, String> byView = new ConcurrentHashMap<>();

  /** The static imports of each module, parsed once. */
  private final Map<String, Set<String>> importsByModule = new ConcurrentHashMap<>();

  /**
   * Creates the map renderer over the application's own resource chain.
   *
   * @param resourceUrlProvider the provider whose hashed URLs the template links use
   */
  @Autowired
  public ModuleImportMap(@NotNull ResourceUrlProvider resourceUrlProvider) {
    this(resourceUrlProvider::getForLookupPath);
  }

  /**
   * The import map of one page as the JSON text of a {@code <script type="importmap">} element.
   *
   * @param view the page's top-level template name, such as {@code bank/bank-dashboard}; a name
   *     that matches no template yields the map of the layout alone
   * @return the JSON object {@code {"imports":{...}}}; its keys are sorted and contain only
   *     characters of {@code /js/...} asset paths, so it cannot close the script element
   */
  public @NotNull String json(@Nullable String view) {
    String key = view == null ? "" : view;
    return byView.computeIfAbsent(key, this::render);
  }

  /**
   * The asset paths of the {@code type="module"} scripts a view's template tree links.
   *
   * @param view the page's top-level template name
   * @return the entry module paths in document order, layout scripts first
   */
  public @NotNull Set<String> entryModules(@NotNull String view) {
    Set<String> entries = new LinkedHashSet<>();
    Set<String> visited = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(LAYOUT_TEMPLATE);
    if (!view.isEmpty()) {
      queue.add(view);
    }
    while (!queue.isEmpty()) {
      String template = queue.poll();
      if (!visited.add(template)) {
        continue;
      }
      String source = read("templates/" + template + ".html");
      if (source == null) {
        continue;
      }
      Matcher tag = SCRIPT_TAG.matcher(source);
      while (tag.find()) {
        String attributes = tag.group(1);
        Matcher src = MODULE_SRC.matcher(attributes);
        if (attributes.contains("type=\"module\"") && src.find()) {
          entries.add(src.group(1));
        }
      }
      Matcher fragment = FRAGMENT_REFERENCE.matcher(source);
      while (fragment.find()) {
        queue.add(fragment.group(1));
      }
    }
    return entries;
  }

  /**
   * The static imports of a module, as the asset paths its {@code import} and {@code export ...
   * from} statements name.
   *
   * @param module the module's asset path, such as {@code /js/bank/bank.js}
   * @return the imported paths, in source order; empty when the file is missing or imports nothing
   */
  public @NotNull Set<String> importsOf(@NotNull String module) {
    return importsByModule.computeIfAbsent(module, this::parseImports);
  }

  /**
   * Every module reachable from the entries through static imports, the entries excluded unless
   * another module imports them.
   *
   * @param entries the entry module paths
   * @return the imported module paths in discovery order
   */
  public @NotNull Set<String> importClosure(@NotNull Set<String> entries) {
    Set<String> imported = new LinkedHashSet<>();
    Set<String> expanded = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>(entries);
    while (!queue.isEmpty()) {
      String module = queue.poll();
      if (!expanded.add(module)) {
        continue;
      }
      for (String dependency : importsOf(module)) {
        imported.add(dependency);
        queue.add(dependency);
      }
    }
    return imported;
  }

  private @NotNull String render(@NotNull String view) {
    Map<String, String> imports = new TreeMap<>();
    for (String module : importClosure(entryModules(view))) {
      String hashed = versioner.apply(module);
      if (hashed == null) {
        log.warn("Module {} is imported but no resource handler serves it", module);
        continue;
      }
      imports.put(module, hashed);
    }
    StringBuilder json = new StringBuilder("{\"imports\":{");
    boolean first = true;
    for (Map.Entry<String, String> entry : imports.entrySet()) {
      if (!ASSET_PATH.matcher(entry.getKey()).matches()
          || !entry.getValue().startsWith("/js/")
          || entry.getValue().contains("\"")) {
        log.warn("Module {} is skipped: its URL is not a plain asset path", entry.getKey());
        continue;
      }
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
    }
    return json.append("}}").toString();
  }

  private @NotNull Set<String> parseImports(@NotNull String module) {
    String source = read("static" + module);
    if (source == null) {
      return Set.of();
    }
    Map<Integer, String> byOffset = new TreeMap<>();
    for (Pattern pattern : new Pattern[] {BARE_IMPORT, FROM_IMPORT}) {
      Matcher matcher = pattern.matcher(source);
      while (matcher.find()) {
        byOffset.put(matcher.start(), matcher.group(2));
      }
    }
    Set<String> imports = new LinkedHashSet<>();
    for (String specifier : byOffset.values()) {
      if (ASSET_PATH.matcher(specifier).matches()) {
        imports.add(specifier);
      } else {
        log.warn(
            "Module {} imports {}, which is not an absolute /js/ asset path", module, specifier);
      }
    }
    return Set.copyOf(imports);
  }

  private static @Nullable String read(@NotNull String classpathLocation) {
    ClassPathResource resource = new ClassPathResource(classpathLocation);
    if (!resource.exists()) {
      return null;
    }
    try {
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      log.warn("Cannot read {}", classpathLocation, ex);
      return null;
    }
  }
}
